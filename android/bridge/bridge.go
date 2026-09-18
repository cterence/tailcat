// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

// Package bridge wraps the tailcat library behind the small set of
// types that gomobile bind can cross the JNI boundary: string, int,
// int64, bool, []byte, error, and interface-typed callbacks. It is the
// bridge layer between the Android Kotlin/Compose app and the Go
// tailcat library.
//
// The package compiles on every platform (the tests run on linux/darwin),
// but it is designed to be built with gomobile bind for GOOS=android
// using the build tags from internal/buildtags.AndroidTags. Under
// GOOS=android the SSH server is compiled out automatically by the
// !(linux || darwin || windows) build constraint in tailcat_ssh_stub.go,
// so file transfer uses raw TCP streams — the same pattern as the web app.
package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	_ "net/http/pprof"
	"net/netip"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/pkg/sftp"
	"github.com/tailscale/tailcat"
	gossh "golang.org/x/crypto/ssh"
	"tailscale.com/net/netmon"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
	"tailscale.com/wgengine/filter"
)

func init() {
	// On Android, /etc/resolv.conf doesn't exist. Without netgo, the
	// cgo resolver calls getaddrinfo which may crash or return nil on
	// Android under gomobile. Force the pure Go resolver via GODEBUG
	// and override net.DefaultResolver to dial Google's public DNS
	// directly, bypassing the missing resolv.conf.
	os.Setenv("GODEBUG", "netdns=go")

// Raise the tunnel's inner MTU above tailscale's conservative default
// of 1280: fewer packets per byte means fewer per-packet costs
// (sendto syscalls, runtime, scheduling) for the same throughput.
// The value stays under the ~1500 path MTU including WireGuard's ~60
// bytes of overhead, so the outer datagram does not IP-fragment:
// fragmentation amplifies loss (one lost fragment kills the whole
// datagram and forces a full-segment retransmit), which profiling
// showed as heavy TCP loss recovery. Read at engine creation, so it
// must be set before the first engine starts. The peer should run
// with the same value.
os.Setenv("TS_DEBUG_MTU", "1420")

	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			return net.Dial(network, "8.8.8.8:53")
		},
	}
	// Android denies app UIDs the netlink route socket (b/155595000)
	// and /proc/net (tailscale issue 2293), so net.Interfaces fails —
	// and net.InterfaceByName("lo") fails the same way. The previous
	// loopback fallback therefore returned (nil, nil), leaving netmon
	// with an empty interface state, which made magicsock's netcheck
	// believe UDP was blocked (forcing DERP-relayed TLS paths) and made
	// the engine's home-DERP connection a startup race it could not
	// recover from on its own.
	//
	// Instead, mirror tailscale.com/feature/androidbin: report a single
	// synthetic interface whose addresses are the kernel's choice of
	// outbound source address, discovered by dialing a UDP socket,
	// which sends no packets and is permitted under the app sandbox.
	// That is enough for magicsock to discover local endpoints and run
	// a real netcheck, making both relay bring-up and direct P2P paths
	// work.
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		if ifs, err := net.Interfaces(); err == nil && len(ifs) > 0 {
			ret := make([]netmon.Interface, len(ifs))
			for i := range ifs {
				ret[i].Interface = &ifs[i]
			}
			return ret, nil
		}
		return syntheticInterfaces()
	})
}

// SetAppDataDir sets the writable directory for persistent data (SSH
// host keys, stable identity key). Call this from Kotlin with
// context.filesDir.absolutePath before starting any server.
var appDataDir string

// syntheticInterfaces returns a single made-up interface carrying the
// process's outbound IPv4 and IPv6 source addresses, for platforms
// where interface enumeration is denied (Android app sandbox). The
// addresses are discovered by dialing UDP sockets, which makes the
// kernel pick a route and a source address without sending any
// packets. This mirrors tailscale.com/feature/androidbin.
func syntheticInterfaces() ([]netmon.Interface, error) {
	var addrs []net.Addr
	if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
	}
	if ip, ok := outboundIP("udp6", "[2001:4860:4860::8888]:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(128, 128)})
	}
	if len(addrs) == 0 {
		// No network at all right now. Report loopback so the engine
		// doesn't think the machine has no interfaces; a real
		// interface appears on the next poll once a route exists.
		return []netmon.Interface{{
			Interface: &net.Interface{
				Index: 1,
				MTU:   65536,
				Name:  "lo",
				Flags: net.FlagUp | net.FlagLoopback,
			},
			AltAddrs: []net.Addr{&net.IPNet{IP: net.IPv4(127, 0, 0, 1), Mask: net.CIDRMask(8, 32)}},
		}}, nil
	}
	return []netmon.Interface{{
		Interface: &net.Interface{
			MTU:   1500,
			Name:  "android",
			Flags: net.FlagUp | net.FlagRunning,
		},
		AltAddrs: addrs,
	}}, nil
}

// outboundIP reports the local source address the kernel picks for an
// outbound UDP socket to addr. No packets are sent, so this works
// under Android's app sandbox where interface enumeration does not.
func outboundIP(network, addr string) (netip.Addr, bool) {
	d := net.Dialer{Timeout: 2 * time.Second}
	c, err := d.Dial(network, addr)
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(ua.IP)
	if !ok {
		return netip.Addr{}, false
	}
	ip = ip.Unmap()
	if ip.IsLoopback() || ip.IsUnspecified() {
		return netip.Addr{}, false
	}
	return ip, true
}

func SetAppDataDir(dir string) {
	appDataDir = dir
	tailcat.SetAppDataDir(dir)
}

// StartPprof serves net/http/pprof on 127.0.0.1:port, so the Go side
// of the app can be profiled from the dev machine while transfers
// run on the phone. Reachable only through USB debugging:
//
//	adb forward tcp:6060 tcp:6060
//	go tool pprof http://localhost:6060/debug/pprof/profile?seconds=30
//
// The endpoint is inert unless something dials it. A negative port
// picks a free one but is not reported back; use a fixed port.
func StartPprof(port int) {
	go func() {
		addr := fmt.Sprintf("127.0.0.1:%d", port)
		log.Printf("tailcat: bridge: pprof listening on %s", addr)
		if err := http.ListenAndServe(addr, nil); err != nil {
			log.Printf("tailcat: bridge: pprof server: %v", err)
		}
	}()
}

// SetBlockProfileRate controls the goroutine blocking profile; see
// runtime.SetBlockProfileRate. 0 disables it (the default). A rate of
// one sample per millisecond (1000000) is a reasonable starting point
// for diagnosing slow-but-idle transfers.
func SetBlockProfileRate(rate int) {
	runtime.SetBlockProfileRate(rate)
}

// keyPath returns the path to the stable identity key file inside the
// app data directory. The key is persisted as JSON so the server's
// identity (and thus the "tc..." address) stays stable across restarts.
func keyPath() string {
	if appDataDir == "" {
		return ""
	}
	return filepath.Join(appDataDir, "tailcat-identity.json")
}

// LoadOrCreateKey loads the stable identity key from disk, or generates
// a new one and saves it if none exists. The key is a tailcat.PrivateKey
// serialized as JSON. Using a stable key means the server's "tc..." address
// stays the same across app restarts, so clients don't need to re-scan.
func LoadOrCreateKey() (string, error) {
	p := keyPath()
	if p != "" {
		if data, err := os.ReadFile(p); err == nil {
			var pk tailcat.PrivateKey
			if err := json.Unmarshal(data, &pk); err == nil && !pk.Private.IsZero() {
				b, _ := json.Marshal(pk)
				return string(b), nil
			}
		}
	}

	pk := tailcat.NewPrivateKey()
	data, err := json.MarshalIndent(pk, "", "\t")
	if err != nil {
		return "", fmt.Errorf("marshal key: %w", err)
	}
	if p != "" {
		if err := os.MkdirAll(filepath.Dir(p), 0700); err != nil {
			return "", fmt.Errorf("mkdir: %w", err)
		}
		if err := os.WriteFile(p, data, 0600); err != nil {
			return "", fmt.Errorf("write key: %w", err)
		}
	}
	return string(data), nil
}

// RotateKey deletes the existing identity key and generates a new one,
// saving it to disk. The server's "tc..." address will change the next
// time it starts. With withPSK the new key embeds a pre-shared key
// (matching the CLI's genkey default and LoadOrCreateKey), so the
// address itself stays a secret credential. Without one, knowledge of
// the address alone locates the server and access control must come
// from the allowed-clients list instead.
func RotateKey(withPSK bool) (string, error) {
	p := keyPath()
	if p != "" {
		os.Remove(p)
	}
	pk := tailcat.NewPrivateKey()
	if !withPSK {
		pk.Public.PresharedKey = tailcat.PresharedKey{}
	}
	data, err := json.MarshalIndent(pk, "", "\t")
	if err != nil {
		return "", fmt.Errorf("marshal key: %w", err)
	}
	if p != "" {
		if err := os.WriteFile(p, data, 0600); err != nil {
			return "", fmt.Errorf("write key: %w", err)
		}
	}
	return string(data), nil
}

// parseKey unmarshals a JSON-serialized tailcat.PrivateKey.
func parseKey(keyJSON string) (*tailcat.PrivateKey, error) {
	if keyJSON == "" {
		return tailcat.NewPrivateKey(), nil
	}
	var pk tailcat.PrivateKey
	if err := json.Unmarshal([]byte(keyJSON), &pk); err != nil {
		return nil, fmt.Errorf("parse key: %w", err)
	}
	if pk.Private.IsZero() {
		return nil, errors.New("parsed key is zero")
	}
	return &pk, nil
}

// newTailcatServer builds the tailcat.Server for the given identity
// key and DERP region. The key's embedded pre-shared key (present by
// default in keys from LoadOrCreateKey and RotateKey, matching the
// CLI's genkey default) is restored so the server's tailcat address,
// which embeds the PSK, stays stable across app restarts. Keys
// without a PSK disable the PSK layer instead of letting Start mint
// a random PSK per launch, which would change the address on every
// restart.
func newTailcatServer(pk *tailcat.PrivateKey, reg *tailcfg.DERPRegion, logf logger.Logf) *tailcat.Server {
	usePSK := !pk.Public.PresharedKey.IsZero()
	return &tailcat.Server{
		Key:                 pk.Private,
		PresharedKey:        pk.Public.PresharedKey,
		DisablePresharedKey: !usePSK,
		Logf:                logf,
		Region:              reg,
	}
}

// ConnectionListener is implemented by the Kotlin side to receive
// incoming TCP connections when the phone is acting as a server.
// OnConnection is called on a new goroutine for each connection.
type ConnectionListener interface {
	OnConnection(c *Conn)
}

// parseAllowedKeys parses a JSON array of base64 node public keys (as
// produced by AddrKey). An empty or blank string means no restriction:
// every client may connect.
func parseAllowedKeys(keysJSON string) ([]key.NodePublic, error) {
	if strings.TrimSpace(keysJSON) == "" {
		return nil, nil
	}
	var keys []string
	if err := json.Unmarshal([]byte(keysJSON), &keys); err != nil {
		return nil, fmt.Errorf("parse allowed keys: %w", err)
	}
	out := make([]key.NodePublic, 0, len(keys))
	for _, k := range keys {
		var nk key.NodePublic
		if err := nk.UnmarshalText([]byte(k)); err != nil {
			return nil, fmt.Errorf("parse allowed key %q: %w", k, err)
		}
		out = append(out, nk)
	}
	return out, nil
}

// Server wraps tailcat.Server for Android. It listens for incoming
// TCP connections relayed through DERP and hands each one to the
// ConnectionListener.
type Server struct {
	srv    *tailcat.Server
	addr   string
	mu     sync.Mutex
	conns  map[*Conn]struct{}
	closed bool

	// rebuild, if non-nil, constructs a fresh tailcat.Server with the
	// same identity key and DERP region, for use when the engine
	// loses its relay connection. The address is unchanged by a
	// rebuild because the identity key (including its pre-shared key)
	// and the region are pinned.
	rebuild func() (*tailcat.Server, error)

	// relayMiss counts consecutive watchdog checks with no relay
	// connection; the engine is rebuilt once it reaches
	// relayRestartAfter.
	relayMiss int

	// stopWatch is closed by Close to stop the relay watchdog
	// goroutine. It is nil for Servers built outside the constructors.
	stopWatch chan struct{}

	// allowedKeys, when non-empty, restricts incoming connections to
	// these client node keys. It is the authoritative copy: every
	// engine (re)build applies it, and AddAllowedClient extends it.
	// Guarded by mu.
	allowedKeys []key.NodePublic
}

// NewServer starts a tailcat server, auto-selecting the nearest DERP
// region. derpMapURL is the DERP map URL (empty = tailcat.DefaultDERPMapURL).
// listener receives each incoming TCP connection. keyJSON is the
// JSON-serialized tailcat.PrivateKey (empty = generate new ephemeral key).
// allowedKeysJSON, if non-empty, is a JSON array of base64 client node
// public keys (see AddrKey) restricted to which clients may connect.
// It returns an error if startup fails.
func NewServer(derpMapURL string, keyJSON string, allowedKeysJSON string, listener ConnectionListener) (*Server, error) {
	pk, err := parseKey(keyJSON)
	if err != nil {
		return nil, fmt.Errorf("key: %w", err)
	}
	allowedKeys, err := parseAllowedKeys(allowedKeysJSON)
	if err != nil {
		return nil, err
	}
	pk.Public.RegionID = -1 // auto-select nearest region

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	ci := pk.Public
	expandOpts := []any{tailcat.ExpandForServer}
	if derpMapURL != "" {
		expandOpts = append(expandOpts, tailcat.DERPMapURL(derpMapURL))
	}
	if err := ci.Expand(ctx, expandOpts...); err != nil {
		return nil, fmt.Errorf("Expand: %w", err)
	}
	reg := ci.Region[0]
	// Pin the picked region so the address stays stable.
	pk.Public.RegionID = reg.RegionID

	s := &Server{
		conns:       make(map[*Conn]struct{}),
		stopWatch:   make(chan struct{}),
		allowedKeys: allowedKeys,
	}
	// wire configures a fresh engine's handlers. It runs for the
	// initial start and for every rebuild.
	wire := func(srv *tailcat.Server) {
		srv.AllowedClients = append([]key.NodePublic(nil), s.allowedKeys...)
		srv.OnTCP = func(port uint16) (handler func(net.Conn)) {
			// Accept a connection on any port, like the CLI's default mode.
			return func(c net.Conn) {
				bc := s.wrapConn(c)
				if listener != nil {
					listener.OnConnection(bc)
				} else {
					bc.Close()
				}
			}
		}
	}
	if err := s.startServer(pk, reg, wire); err != nil {
		return nil, err
	}
	return s, nil
}

// fileServeMode maps a mode string from the Kotlin side to a
// tailcat.FileServeMode. Unknown values default to read-write.
//
//	"rw"  read-write (default)
//	"ro"  read-only: clients can list and download, not upload
//	"wo"  write-only flat drop box: uploads only, no listing
//	"wo+" write-only recursive drop box
func fileServeMode(mode string) tailcat.FileServeMode {
	switch mode {
	case "ro":
		return tailcat.FileServeRO
	case "wo":
		return tailcat.FileServeWO
	case "wo+":
		return tailcat.FileServeWOPlus
	default:
		return tailcat.FileServeRW
	}
}

// NewSFTPServer starts a tailcat server that serves SFTP on port 22
// (for scp/sftp clients) and raw TCP on all other ports (for the
// phone app's raw stream mode). filesDir is the directory to serve
// via SFTP, with the access given by mode ("rw", "ro", "wo", "wo+";
// see fileServeMode). derpMapURL is the DERP map URL (empty = default).
// keyJSON is the JSON-serialized tailcat.PrivateKey (empty = generate
// new ephemeral key). allowedKeysJSON, if non-empty, is a JSON array
// of base64 client node public keys (see AddrKey) restricted to which
// clients may connect. listener receives each incoming raw TCP
// connection (non-SFTP).
func NewSFTPServer(derpMapURL string, filesDir string, mode string, keyJSON string, allowedKeysJSON string, listener ConnectionListener) (*Server, error) {
	pk, err := parseKey(keyJSON)
	if err != nil {
		return nil, fmt.Errorf("key: %w", err)
	}
	allowedKeys, err := parseAllowedKeys(allowedKeysJSON)
	if err != nil {
		return nil, err
	}
	pk.Public.RegionID = -1

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	ci := pk.Public
	expandOpts := []any{tailcat.ExpandForServer}
	if derpMapURL != "" {
		expandOpts = append(expandOpts, tailcat.DERPMapURL(derpMapURL))
	}
	if err := ci.Expand(ctx, expandOpts...); err != nil {
		return nil, fmt.Errorf("Expand: %w", err)
	}
	reg := ci.Region[0]
	pk.Public.RegionID = reg.RegionID

	s := &Server{
		conns:       make(map[*Conn]struct{}),
		stopWatch:   make(chan struct{}),
		allowedKeys: allowedKeys,
	}
	wire := func(srv *tailcat.Server) {
		srv.AllowedClients = append([]key.NodePublic(nil), s.allowedKeys...)
		// SFTP handler on port 22 (only if a directory is configured)
		var sshHandler func(net.Conn)
		if filesDir != "" {
			sshHandler = srv.SSHConnHandler(tailcat.SSHOptions{
				Files: &tailcat.FileService{
					Dir:  filesDir,
					Mode: fileServeMode(mode),
				},
			})
		}

		srv.OnTCP = func(port uint16) (handler func(net.Conn)) {
			if port == 22 && sshHandler != nil {
				return sshHandler
			}
			// All other ports: raw TCP stream (like NewServer)
			return func(c net.Conn) {
				bc := s.wrapConn(c)
				if listener != nil {
					listener.OnConnection(bc)
				} else {
					bc.Close()
				}
			}
		}
		if sshHandler != nil {
			srv.ServedTCPPorts = []filter.PortRange{{First: 22, Last: 22}}
		}
	}
	if err := s.startServer(pk, reg, wire); err != nil {
		return nil, err
	}
	return s, nil
}

// Addr returns the "tc..." tailcat address to share with the peer.
func (s *Server) Addr() string {
	return s.addr
}

// peerInfo is one entry of PeersJSON's output.
type peerInfo struct {
	// Key is the connected client's tailcat node public key, base64.
	// It matches the key embedded in that client's own "tc..." address,
	// as returned by AddrKey, so the app can show a saved device's
	// alias instead of a raw key.
	Key string `json:"key"`
	// CurAddr is the direct peer-to-peer endpoint, empty when the
	// connection is relayed through DERP.
	CurAddr string `json:"curAddr"`
	// Relay is the DERP region serving the connection, empty when
	// direct.
	Relay  string `json:"relay"`
	Rx     int64  `json:"rx"`
	Tx     int64  `json:"tx"`
	Active bool   `json:"active"`
}

// PeersJSON returns a JSON array with one entry per connected client
// (see peerInfo). It returns "[]" when no client is connected. The
// app polls this alongside RelayOnline to show who is connected and
// whether each connection is direct or relayed.
func (s *Server) PeersJSON() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.srv == nil || s.closed {
		return "[]"
	}
	st := s.srv.Status()
	if st == nil {
		return "[]"
	}
	peers := make([]peerInfo, 0, len(st.Peer))
	for k, ps := range st.Peer {
		peers = append(peers, peerInfo{
			Key:     k.String(),
			CurAddr: ps.CurAddr,
			Relay:   ps.Relay,
			Rx:      ps.RxBytes,
			Tx:      ps.TxBytes,
			Active:  ps.Active,
		})
	}
	b, err := json.Marshal(peers)
	if err != nil {
		return "[]"
	}
	return string(b)
}

// AddrKey returns the node public key of the device that shared the
// given "tc..." address, base64-encoded. The key is the device's
// tunnel identity: it matches the Key field of that device's entry in
// PeersJSON, so the app can recognize a connected peer from a scanned
// or saved address.
func AddrKey(addr string) (string, error) {
	ci, err := tailcat.ParseAddr(tailcat.Addr(addr))
	if err != nil {
		return "", fmt.Errorf("parse address: %w", err)
	}
	return ci.ServerPublic.String(), nil
}

// AddAllowedClient allows one more client node key, given in base64
// as produced by AddrKey. It takes effect on the running engine
// immediately: an already-listening server accepts the new client
// without a restart or address change. The key is also remembered, so
// engines rebuilt by the watchdog keep allowing it.
func (s *Server) AddAllowedClient(keyB64 string) error {
	var k key.NodePublic
	if err := k.UnmarshalText([]byte(keyB64)); err != nil {
		return fmt.Errorf("parse key: %w", err)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return errors.New("server is closed")
	}
	s.allowedKeys = append(s.allowedKeys, k)
	if s.srv != nil {
		s.srv.AddAllowedClient(k)
	}
	return nil
}

// relayCheckInterval is how often the relay watchdog checks whether
// the engine still has a connection to its home DERP relay.
const relayCheckInterval = 10 * time.Second

// While the relay has never been online since the server started,
// the watchdog checks more often and rebuilds sooner: a fresh engine
// that came up without a home DERP connection cannot recover on its
// own, and there are no transfers to interrupt yet. This keeps a bad
// start from costing half a minute per retry.
const (
	earlyCheckInterval = 3 * time.Second
	earlyRestartAfter  = 2
)

// relayRestartAfter is the number of consecutive watchdog checks with
// no relay connection after which the engine is rebuilt (steady state,
// about 30s).
const relayRestartAfter = 3

// startServer builds and starts the first engine, installs the
// rebuild closure from the identity key, DERP region, and wire
// function, and starts the relay watchdog. wire configures a fresh
// engine's handlers and must be idempotent, as it runs again for
// every rebuild.
func (s *Server) startServer(pk *tailcat.PrivateKey, reg *tailcfg.DERPRegion, wire func(*tailcat.Server)) error {
	s.rebuild = func() (*tailcat.Server, error) {
		srv := newTailcatServer(pk, reg, logger.WithPrefix(log.Printf, "tailcat: "))
		wire(srv)
		if err := srv.Start(); err != nil {
			srv.Close()
			return nil, fmt.Errorf("Server.Start: %w", err)
		}
		return srv, nil
	}
	srv, err := s.rebuild()
	if err != nil {
		return err
	}
	s.srv = srv
	s.addr = string(srv.TailcatAddr())
	go s.watchRelay()
	return nil
}

// RelayOnline reports whether the server currently has a connection
// to its home DERP relay. tailcat is reachable only through that
// relay (or a direct path), so when this returns false the address
// shown to peers is unreachable even though the server is listening.
// The relay watchdog rebuilds the engine when the connection stays
// down; see watchRelay.
func (s *Server) RelayOnline() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.relayOnlineLocked()
}

func (s *Server) relayOnlineLocked() bool {
	if s.srv == nil || s.closed {
		return false
	}
	st := s.srv.Status()
	return st != nil && st.Self != nil && st.Self.Relay != ""
}

// RelayMisses reports how many consecutive watchdog checks found no
// relay connection. It resets to zero once the relay connection is
// re-established, and the engine is rebuilt when it reaches
// relayRestartAfter; the UI can use it to show retry progress while
// the address is unreachable.
func (s *Server) RelayMisses() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.relayMiss
}

// Restart rebuilds the server's network engine, keeping the same
// identity key and DERP region, so the tailcat address does not
// change. It is used to recover from relay connection loss and can
// be called manually to repair an unreachable server. Connections
// accepted by the old engine are dropped.
func (s *Server) Restart() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return errors.New("server is closed")
	}
	old, err := s.rebuildLocked()
	if err != nil {
		s.mu.Unlock()
		return err
	}
	s.mu.Unlock()
	old.Close()
	return nil
}

// rebuildLocked swaps in a fresh engine, replacing s.srv and s.addr.
// The caller must hold s.mu. The old engine is returned for the
// caller to close after unlocking.
func (s *Server) rebuildLocked() (*tailcat.Server, error) {
	if s.rebuild == nil {
		return nil, errors.New("server cannot be rebuilt")
	}
	old := s.srv
	srv, err := s.rebuild()
	if err != nil {
		return nil, err
	}
	s.srv = srv
	s.addr = string(srv.TailcatAddr())
	s.relayMiss = 0
	return old, nil
}

// watchRelay periodically checks the relay connection and rebuilds
// the engine when it has been down for consecutive checks. The engine
// can come up without ever connecting to its home DERP relay (for
// example when the network monitor reports no interfaces, or a
// netcheck races network bring-up), and in that state the server is
// unreachable and magicsock does not recover on its own; rebuilding
// the engine re-establishes the connection. The address is unchanged
// because the identity key and region are pinned. Rebuilds are skipped
// while connections are open, so active transfers are never
// interrupted by the watchdog.
//
// Until the relay has been online once, the loop runs on the fast
// early cadence (earlyCheckInterval / earlyRestartAfter) so a bad
// start recovers within seconds; once the relay has been up, the
// steady cadence applies (relayCheckInterval / relayRestartAfter).
func (s *Server) watchRelay() {
	everOnline := false
	t := time.NewTimer(earlyCheckInterval)
	defer t.Stop()
	for {
		select {
		case <-s.stopWatch:
			return
		case <-t.C:
		}
		s.mu.Lock()
		if s.closed {
			s.mu.Unlock()
			return
		}
		if s.relayOnlineLocked() {
			s.relayMiss = 0
			everOnline = true
			s.mu.Unlock()
			t.Reset(relayCheckInterval)
			continue
		}
		s.relayMiss++
		if s.relayMiss == 1 {
			log.Printf("tailcat: bridge: no relay connection (address unreachable); watching")
		}
		restartAfter, nextCheck := relayRestartAfter, relayCheckInterval
		if !everOnline {
			restartAfter, nextCheck = earlyRestartAfter, earlyCheckInterval
		}
		if s.relayMiss < restartAfter || s.rebuild == nil || len(s.conns) != 0 {
			s.mu.Unlock()
			t.Reset(nextCheck)
			continue
		}
		old, err := s.rebuildLocked()
		s.mu.Unlock()
		if err != nil {
			log.Printf("tailcat: bridge: engine rebuild failed: %v", err)
			t.Reset(nextCheck)
			continue
		}
		old.Close()
		log.Printf("tailcat: bridge: rebuilt engine to restore relay connection")
		t.Reset(nextCheck)
	}
}

// Close stops the server and all active connections.
func (s *Server) Close() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	if s.stopWatch != nil {
		close(s.stopWatch)
	}
	conns := s.conns
	s.conns = nil
	s.mu.Unlock()

	for c := range conns {
		c.Close()
	}
	s.srv.Close()
}

// wrapConn registers a net.Conn with the server's tracking map so the
// Go GC doesn't finalize it while Kotlin still holds a reference.
func (s *Server) wrapConn(c net.Conn) *Conn {
	bc := &Conn{c: c, server: s}
	s.mu.Lock()
	if s.conns != nil {
		s.conns[bc] = struct{}{}
	}
	s.mu.Unlock()
	return bc
}

// forgetConn removes a Conn from the server's tracking map.
func (s *Server) forgetConn(bc *Conn) {
	s.mu.Lock()
	if s.conns != nil {
		delete(s.conns, bc)
	}
	s.mu.Unlock()
}

// Client wraps tailcat.Client for Android. It connects to a remote
// server identified by a "tc..." address and can dial TCP ports on it.
type Client struct {
	cl     *tailcat.Client
	mu     sync.Mutex
	closed bool
}

// NewClient creates a client for the given "tc..." address.
// derpMapURL is the DERP map URL (empty = tailcat.DefaultDERPMapURL).
func NewClient(addr, derpMapURL string) *Client {
	return &Client{
		cl: &tailcat.Client{
			Server:     tailcat.Addr(addr),
			DERPMapURL: derpMapURL,
			Logf:       logger.WithPrefix(log.Printf, "tailcat: "),
		},
	}
}

// Ping retries the meow/meowed handshake until success or a 60s
// total timeout. Returns an error on failure.
func (c *Client) Ping() error {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	return pingUntil(ctx, c.cl)
}

// PingWithTimeout is like Ping but with a caller-specified timeout in seconds.
func (c *Client) PingWithTimeout(timeoutSeconds int) error {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSeconds)*time.Second)
	defer cancel()
	return pingUntil(ctx, c.cl)
}

// Dial opens a TCP connection to the given port on the server.
// It first performs a ping if one hasn't succeeded yet.
func (c *Client) Dial(port int) (*Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	conn, err := c.cl.DialTCPPort(ctx, uint16(port))
	if err != nil {
		return nil, fmt.Errorf("DialTCPPort: %w", err)
	}
	return &Conn{c: conn, client: c}, nil
}

// SFTPClient wraps an SFTP client session over the tunnel.
type SFTPClient struct {
	sc    *sftp.Client
	conn  net.Conn
	close func()
}

// DialSFTP connects to port 22 on the server, does the SSH handshake
// (no auth), and returns an SFTP client for browsing and downloading
// files. The client must have been pinged first.
func (c *Client) DialSFTP() (*SFTPClient, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	conn, err := c.cl.DialTCPPort(ctx, 22)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("DialTCPPort: %w", err)
	}

	// SSH config: no auth, ignore host key.
	sshCfg := &gossh.ClientConfig{
		HostKeyCallback: gossh.InsecureIgnoreHostKey(),
	}
	sshConn, chans, reqs, err := gossh.NewClientConn(conn, "", sshCfg)
	if err != nil {
		conn.Close()
		cancel()
		return nil, fmt.Errorf("SSH handshake: %w", err)
	}
	sshClient := gossh.NewClient(sshConn, chans, reqs)
	session, err := sshClient.NewSession()
	if err != nil {
		sshClient.Close()
		cancel()
		return nil, fmt.Errorf("SSH session: %w", err)
	}

	// Open SFTP subsystem.
	pw, err := session.StdinPipe()
	if err != nil {
		session.Close()
		sshClient.Close()
		cancel()
		return nil, err
	}
	pr, err := session.StdoutPipe()
	if err != nil {
		session.Close()
		sshClient.Close()
		cancel()
		return nil, err
	}
	if err := session.RequestSubsystem("sftp"); err != nil {
		session.Close()
		sshClient.Close()
		cancel()
		return nil, fmt.Errorf("RequestSubsystem sftp: %w", err)
	}

	sc, err := sftp.NewClientPipe(pr, pw,
		sftp.UseConcurrentWrites(true),
		sftp.MaxConcurrentRequestsPerFile(64),
	)
	if err != nil {
		session.Close()
		sshClient.Close()
		cancel()
		return nil, fmt.Errorf("SFTP client: %w", err)
	}

	closeFn := func() {
		sc.Close()
		session.Close()
		sshClient.Close()
		cancel()
	}

	return &SFTPClient{sc: sc, close: closeFn}, nil
}

// FileLister is implemented by Kotlin to receive file entries from
// ListDir one at a time.
type FileLister interface {
	OnFile(name string, size int64, isDir bool)
}

// ListDir lists files in the given remote directory path. If path is
// empty, lists the root "/". Each file is passed to the listener.
func (s *SFTPClient) ListDir(path string, listener FileLister) error {
	if path == "" {
		path = "/"
	}
	entries, err := s.sc.ReadDir(path)
	if err != nil {
		return err
	}
	for _, e := range entries {
		if listener != nil {
			listener.OnFile(e.Name(), e.Size(), e.IsDir())
		}
	}
	return nil
}

// DownloadFile downloads the remote file at remotePath and saves it
// to localPath on the phone. Returns the number of bytes written.
func (s *SFTPClient) DownloadFile(remotePath, localPath string) (int64, error) {
	return s.downloadFile(remotePath, localPath, nil)
}

// DownloadFileWithProgress is DownloadFile with progress reported
// through progress (bytes written of the file's total size).
func (s *SFTPClient) DownloadFileWithProgress(remotePath, localPath string, progress ProgressListener) (int64, error) {
	return s.downloadFile(remotePath, localPath, progress)
}

func (s *SFTPClient) downloadFile(remotePath, localPath string, progress ProgressListener) (int64, error) {
	r, err := s.sc.Open(remotePath)
	if err != nil {
		return 0, fmt.Errorf("open remote: %w", err)
	}
	defer r.Close()

	w, err := os.Create(localPath)
	if err != nil {
		return 0, fmt.Errorf("create local: %w", err)
	}
	defer w.Close()

	var dst io.Writer = w
	var pw *progressWriter
	if progress != nil {
		if st, err := r.Stat(); err == nil {
			// Report 0 at the start so the UI can show the total and a
			// 0% bar immediately.
			progress.OnProgress(0, st.Size())
			pw = &progressWriter{w: w, total: st.Size(), lastReport: time.Now(), progress: progress}
			dst = pw
		}
		// A Stat failure just means no progress reporting; the download
		// itself proceeds.
	}

	n, err := io.Copy(dst, r)
	if err != nil {
		// The local file was created fresh by this call, and a partial
		// file is corrupt data — remove it so a cancelled or failed
		// download leaves nothing misleading behind. Best-effort: the
		// message says the file remains if removal fails.
		w.Close()
		if rmErr := os.Remove(localPath); rmErr != nil {
			return n, fmt.Errorf("copy: %w (partial file left at %s: %v)", err, localPath, rmErr)
		}
		return n, fmt.Errorf("copy: %w (partial file removed)", err)
	}
	if progress != nil && pw != nil {
		progress.OnProgress(n, pw.total)
	}
	return n, nil
}

// progressWriter wraps a writer, reporting download progress every
// progressInterval bytes; the final report from the copy loop is the
// authoritative one. Mirror of progressReader.
type progressWriter struct {
	w          io.Writer
	total      int64
	sent       int64
	reported   int64
	lastReport time.Time
	progress   ProgressListener
}

func (p *progressWriter) Write(buf []byte) (int, error) {
	if p.progress.IsCancelled() { // per-transfer, safe with parallel transfers
		return 0, errors.New("transfer cancelled")
	}
	n, err := p.w.Write(buf)
	p.sent += int64(n)
	if p.sent-p.reported >= progressInterval && time.Since(p.lastReport) >= progressMinInterval {
		p.reported = p.sent
		p.lastReport = time.Now()
		p.progress.OnProgress(p.sent, p.total)
	}
	return n, err
}

// UploadFile uploads the local file at localPath to the remote server
// at remotePath. If the file already exists, it appends "(n)" before the
// extension (e.g. "file.txt" -> "file(1).txt") until a free name is found.
// Returns the number of bytes written.
func (s *SFTPClient) UploadFile(localPath, remotePath string) (int64, error) {
	n, _, err := s.uploadFile(localPath, remotePath, nil)
	return n, err
}

// UploadFileGetPath uploads the local file and returns the actual remote
// path used (which may differ from remotePath if a file with that name
// already existed, in which case a "(n)" suffix is inserted).
func (s *SFTPClient) UploadFileGetPath(localPath, remotePath string) (string, error) {
	_, actualPath, err := s.uploadFile(localPath, remotePath, nil)
	return actualPath, err
}

// ProgressListener receives upload or download progress: the bytes
// transferred so far and the file's total size. Called from a
// background goroutine roughly every progressMinInterval. Each
// transfer polls IsCancelled before every chunk and aborts if it
// returns true, so several transfers can run in parallel over one
// SFTP session and be cancelled independently.
type ProgressListener interface {
	OnProgress(sent, total int64)
	IsCancelled() bool
}

// UploadFileWithProgress is UploadFile with per-file progress
// reported through progress.
func (s *SFTPClient) UploadFileWithProgress(localPath, remotePath string, progress ProgressListener) (int64, error) {
	n, _, err := s.uploadFile(localPath, remotePath, progress)
	return n, err
}

// progressReader wraps a reader, reporting progress every
// progressInterval bytes and once at EOF. The final report after the
// copy loop (from uploadFile) is the authoritative one.
type progressReader struct {
	r          io.Reader
	total      int64
	sent       int64
	reported   int64
	lastReport time.Time
	progress   ProgressListener
}

// progressInterval is the minimum number of bytes between progress
// reports.
const progressInterval = 256 << 10

// progressMinInterval is the minimum time between progress reports.
// Fast transfers would otherwise fire dozens of callbacks per second
// (and the UI recomposition on the Kotlin side costs real CPU —
// profiling a 13 MB/s send showed the progress rendering alone
// taking ~40% of a core, competing with the transfer itself).
var progressMinInterval = 150 * time.Millisecond

func (p *progressReader) Read(buf []byte) (int, error) {
	if p.progress.IsCancelled() { // per-transfer, safe with parallel transfers
		return 0, errors.New("transfer cancelled")
	}
	n, err := p.r.Read(buf)
	p.sent += int64(n)
	if (p.sent-p.reported >= progressInterval && time.Since(p.lastReport) >= progressMinInterval) || err == io.EOF {
		p.reported = p.sent
		p.lastReport = time.Now()
		p.progress.OnProgress(p.sent, p.total)
	}
	return n, err
}

// Stat delegates to the underlying file. pkg/sftp's File.ReadFrom only
// takes its concurrent-writes pipeline (up to
// maxConcurrentRequestsPerFile chunks in flight) when it can learn the
// reader's remaining size through Len/Size/LimitedReader/Stat — an
// opaque io.Reader falls back to a fully serialized loop of one
// maxPacket-sized write per SFTP round trip, which caps throughput at
// chunk size over round-trip time. The wrapper hiding Stat is what
// reduced uploads to ~3 MB/s on an otherwise fast link.
func (p *progressReader) Stat() (os.FileInfo, error) {
	if s, ok := p.r.(interface{ Stat() (os.FileInfo, error) }); ok {
		return s.Stat()
	}
	return nil, os.ErrInvalid
}

// uniqueRemotePath returns the first free name for remotePath: the
// path itself if nothing exists there, or a numbered variant
// ("file(1).ext") otherwise. Any stat error other than an existing
// file counts as free; the later operations surface real problems.
func (s *SFTPClient) uniqueRemotePath(remotePath string) string {
	if _, err := s.sc.Stat(remotePath); err != nil {
		return remotePath
	}
	for i := 1; i < 1000; i++ {
		alt := numberedPath(remotePath, i)
		if _, err := s.sc.Stat(alt); err != nil {
			return alt
		}
	}
	return remotePath
}

func (s *SFTPClient) uploadFile(localPath, remotePath string, progress ProgressListener) (int64, string, error) {
	r, err := os.Open(localPath)
	if err != nil {
		return 0, "", fmt.Errorf("open local: %w", err)
	}
	defer r.Close()

	var src io.Reader = r
	var pr *progressReader
	if progress != nil {
		if fi, err := r.Stat(); err == nil {
			// Report 0 at the start so the UI can show the total and
			// a 0% bar immediately.
			progress.OnProgress(0, fi.Size())
			pr = &progressReader{r: r, total: fi.Size(), lastReport: time.Now(), progress: progress}
			src = pr
		}
		// A Stat failure just means no progress reporting; the upload
		// itself proceeds.
	}

	// Upload under a .part name and rename into place on completion:
	// a file only ever appears at its final name once it is complete,
	// and an aborted upload leaves nothing behind. The final name is
	// chosen free up front (the old flow created the empty final file
	// immediately, which was itself a misleading "incomplete" file).
	finalPath := s.uniqueRemotePath(remotePath)
	partPath := finalPath + ".part"
	if _, err := s.sc.Stat(partPath); err == nil {
		// A stale part from an attempt that crashed before its
		// cleanup — incomplete by definition, so replace it.
		if err := s.sc.Remove(partPath); err != nil {
			return 0, finalPath, fmt.Errorf("remove stale %s: %w", partPath, err)
		}
	}
	w, err := s.sc.OpenFile(partPath, os.O_WRONLY|os.O_CREATE|os.O_EXCL)
	if err != nil {
		return 0, finalPath, fmt.Errorf("create remote: %w", err)
	}

	complete := false
	defer func() {
		// Aborted or failed: close the handle and remove the partial
		// so nothing misleading remains. Best-effort; the connection
		// may itself be dead.
		if !complete {
			w.Close()
			s.sc.Remove(partPath)
		}
	}()

	n, err := io.Copy(w, src)
	if err != nil {
		return n, finalPath, fmt.Errorf("copy: %w (partial %s removed)", err, partPath)
	}
	if err := w.Close(); err != nil {
		return n, finalPath, fmt.Errorf("close remote: %w", err)
	}

	// Rename the completed part into place. The final name was chosen
	// free; if it was taken meanwhile, fall back to numbered variants
	// like the old exclusive-create flow did.
	dst := finalPath
	for i := 0; ; i++ {
		if err := s.sc.Rename(partPath, dst); err == nil {
			break
		}
		if i >= 999 {
			return n, dst, fmt.Errorf("rename %s: no free destination", partPath)
		}
		dst = numberedPath(finalPath, i+1)
	}
	complete = true
	if progress != nil && pr != nil {
		progress.OnProgress(n, pr.total)
	}
	return n, dst, nil
}

// numberedPath inserts "(n)" before the extension in path:
// "/foo/bar.txt" -> "/foo/bar(1).txt", "/foo/bar" -> "/foo/bar(1)".
func numberedPath(path string, n int) string {
	dot := strings.LastIndexByte(path, '.')
	slash := strings.LastIndexByte(path, '/')
	if dot > slash {
		return fmt.Sprintf("%s(%d)%s", path[:dot], n, path[dot:])
	}
	return fmt.Sprintf("%s(%d)", path, n)
}

// UploadDir uploads all files from the local directory at localDir to
// the remote directory at remoteDir, creating subdirectories as needed.
// Returns the total number of bytes uploaded.
func (s *SFTPClient) UploadDir(localDir, remoteDir string) (int64, error) {
	files, _, err := collectFiles(localDir)
	if err != nil {
		return 0, fmt.Errorf("collect files: %w", err)
	}

	// Create remote directories
	remoteDirs := make(map[string]bool)
	for _, f := range files {
		relDir := f.relDir
		for i := 1; i <= len(strings.Split(relDir, "/")); i++ {
			dir := strings.Join(strings.Split(relDir, "/")[:i], "/")
			if dir == "" {
				continue
			}
			fullDir := remoteDir + "/" + dir
			if !remoteDirs[fullDir] {
				s.sc.Mkdir(fullDir)
				remoteDirs[fullDir] = true
			}
		}
	}
	s.sc.Mkdir(remoteDir)

	var uploadedBytes int64
	for _, f := range files {
		remotePath := remoteDir + "/" + f.relPath
		n, err := s.UploadFile(f.fullPath, remotePath)
		uploadedBytes += n
		if err != nil {
			return uploadedBytes, fmt.Errorf("upload %s: %w", f.relPath, err)
		}
	}
	return uploadedBytes, nil
}

type localFile struct {
	fullPath string
	relPath  string
	relDir   string
	size     int64
}

func collectFiles(root string) ([]localFile, int64, error) {
	var files []localFile
	var totalSize int64
	err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		rel = filepath.ToSlash(rel)
		relDir := filepath.ToSlash(filepath.Dir(rel))
		if relDir == "." {
			relDir = ""
		}
		files = append(files, localFile{
			fullPath: path,
			relPath:  rel,
			relDir:   relDir,
			size:     info.Size(),
		})
		totalSize += info.Size()
		return nil
	})
	return files, totalSize, err
}

// Close releases the SFTP session and SSH connection.
func (s *SFTPClient) Close() {
	s.close()
}

// DiscoPingResult holds the result of a disco ping, indicating whether
// the connection is direct (peer-to-peer UDP) or relayed via DERP, along
// with latency and the endpoint/region details.
type DiscoPingResult struct {
	Direct   bool   // true if the connection is direct (not via DERP)
	Latency  int64  // round-trip latency in milliseconds
	Via      string // human-readable description of the path
	Endpoint string // the direct endpoint address (empty if via DERP)
}

// DiscoPing sends a disco ping to the server and returns whether the
// connection is direct or relayed via DERP. The client must have been
// pinged first (meow/meowed handshake completed).
func (c *Client) DiscoPing() (*DiscoPingResult, error) {
	return c.DiscoPingWithTimeout(15)
}

// DiscoPingWithTimeout is DiscoPing with a caller-specified timeout in
// seconds. Unlike Ping, whose meow/meowed handshake only happens once
// per client (later Ping calls return as soon as the first handshake
// succeeded, alive server or not), a disco ping is a genuine round
// trip on every call, so it is the right probe for repeated liveness
// checks on a long-lived client.
func (c *Client) DiscoPingWithTimeout(timeoutSeconds int) (*DiscoPingResult, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSeconds)*time.Second)
	defer cancel()
	res, err := c.cl.DiscoPing(ctx)
	if err != nil {
		return nil, fmt.Errorf("DiscoPing: %w", err)
	}
	latency := time.Duration(res.LatencySeconds * float64(time.Second)).Round(time.Millisecond)
	via := res.Endpoint
	direct := res.Endpoint != ""
	if !direct {
		if res.DERPRegionCode != "" {
			via = fmt.Sprintf("DERP(%s)", res.DERPRegionCode)
		} else {
			via = fmt.Sprintf("DERP(%v)", res.DERPRegionID)
		}
	}
	return &DiscoPingResult{
		Direct:   direct,
		Latency:  latency.Milliseconds(),
		Via:      via,
		Endpoint: res.Endpoint,
	}, nil
}

// DiscoPingUntilDirect repeats disco pings up to maxAttempts times,
// stopping early once a direct (P2P) connection is established. Returns
// the result of the last ping. The client must have been pinged first.
func (c *Client) DiscoPingUntilDirect(maxAttempts int) (*DiscoPingResult, error) {
	var lastResult *DiscoPingResult
	for i := 0; i < maxAttempts; i++ {
		res, err := c.DiscoPing()
		if err != nil {
			lastResult = &DiscoPingResult{Direct: false, Via: fmt.Sprintf("error: %v", err)}
			continue
		}
		lastResult = res
		if res.Direct {
			return res, nil
		}
		// Brief pause between attempts to let magicsock discover endpoints.
		time.Sleep(500 * time.Millisecond)
	}
	if lastResult == nil {
		return nil, errors.New("all disco pings failed")
	}
	return lastResult, nil
}

// CheckPermissionsResult reports whether the remote SFTP server allows
// read (directory listing) and write (file creation) operations.
type CheckPermissionsResult struct {
	CanRead  bool
	CanWrite bool
}

// CheckPermissions probes the remote SFTP server to determine read and
// write permissions. It tries listing the root directory (read test) and
// creating then removing a temporary file (write test). The client must
// have been pinged first.
func (c *Client) CheckPermissions() (*CheckPermissionsResult, error) {
	sftp, err := c.DialSFTP()
	if err != nil {
		return nil, err
	}
	defer sftp.Close()

	canRead := true
	canWrite := true

	// Read test: try listing the root directory.
	entries, err := sftp.sc.ReadDir("/")
	if err != nil {
		canRead = false
	}
	_ = entries

	// Write test: try creating and removing a temporary file.
	testPath := "/.__tailcat_perm_test__"
	f, err := sftp.sc.OpenFile(testPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC)
	if err != nil {
		canWrite = false
	} else {
		f.Close()
		sftp.sc.Remove(testPath)
	}

	return &CheckPermissionsResult{
		CanRead:  canRead,
		CanWrite: canWrite,
	}, nil
}

// Close releases the client's network resources.
func (c *Client) Close() {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.closed = true
	c.mu.Unlock()
	c.cl.Close()
}

// forgetConn is a no-op for clients; the Conn.Close path handles cleanup.
func (c *Client) forgetConn(bc *Conn) {}

// Conn wraps a tunneled TCP connection (a net.Conn from either
// tailcat.Server.OnTCP or tailcat.Client.DialTCPPort) behind
// gomobile-compatible types.
type Conn struct {
	c      net.Conn
	server *Server
	client *Client
	mu     sync.Mutex
	closed bool
}

// Read returns the next chunk of data (up to 64 KB), or nil on EOF.
// gomobile translates nil []byte to a null byte array on the Kotlin
// side, which the app distinguishes from a non-nil (possibly empty)
// byte array.
func (c *Conn) Read() ([]byte, error) {
	buf := make([]byte, 64<<10)
	n, err := c.c.Read(buf)
	if n > 0 {
		return buf[:n], nil
	}
	if err == nil || errors.Is(err, io.EOF) {
		return nil, nil
	}
	return nil, err
}

// Write sends data through the tunnel.
func (c *Conn) Write(data []byte) error {
	_, err := c.c.Write(data)
	return err
}

// CloseWrite half-closes the write side (sends a FIN, netcat style),
// allowing the peer to still send data back.
func (c *Conn) CloseWrite() error {
	cw, ok := c.c.(interface{ CloseWrite() error })
	if !ok {
		return errors.New("connection does not support half-close")
	}
	return cw.CloseWrite()
}

// Close closes the connection and removes it from the server's
// tracking map.
func (c *Conn) Close() {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.closed = true
	c.mu.Unlock()

	c.c.Close()
	if c.server != nil {
		c.server.forgetConn(c)
	} else if c.client != nil {
		c.client.forgetConn(c)
	}
}

// pingUntil retries the meow/meowed handshake until it succeeds or
// ctx expires. The first pings can be lost while either side's DERP
// connection is still coming up. Mirrors web/main_js.go:pingUntil.
func pingUntil(ctx context.Context, cl *tailcat.Client) error {
	for {
		pctx, cancel := context.WithTimeout(ctx, 5*time.Second)
		_, err := cl.Ping(pctx)
		cancel()
		if err == nil {
			return nil
		}
		if ctx.Err() != nil {
			return fmt.Errorf("ping: %w", err)
		}
	}
}

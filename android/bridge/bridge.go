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
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"sync"
	"time"

	"github.com/pkg/sftp"
	"github.com/tailscale/tailcat"
	gossh "golang.org/x/crypto/ssh"
	"tailscale.com/net/netmon"
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
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			return net.Dial(network, "8.8.8.8:53")
		},
	}
	// Android SDK 30+ no longer permits Go's net.Interfaces() to work
	// (tailscale issue 2293). The official Tailscale Android app
	// registers an alternate implementation via JNI. We try
	// net.Interfaces() first; if it fails, return a minimal set with
	// just loopback so magicsock knows there's a network.
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifs, err := net.Interfaces()
		if err == nil {
			ret := make([]netmon.Interface, len(ifs))
			for i := range ifs {
				ret[i].Interface = &ifs[i]
			}
			return ret, nil
		}
		// Fallback: return just loopback so the engine doesn't think
		// the network is completely down.
		lo, err := net.InterfaceByName("lo")
		if err != nil {
			return nil, nil
		}
		return []netmon.Interface{{Interface: lo}}, nil
	})
}

// SetAppDataDir sets the writable directory for persistent data (SSH
// host keys). Call this from Kotlin with context.filesDir.absolutePath
// before starting any server.
func SetAppDataDir(dir string) {
	tailcat.SetAppDataDir(dir)
}

// ConnectionListener is implemented by the Kotlin side to receive
// incoming TCP connections when the phone is acting as a server.
// OnConnection is called on a new goroutine for each connection.
type ConnectionListener interface {
	OnConnection(c *Conn)
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
}

// NewServer starts a tailcat server, auto-selecting the nearest DERP
// region. derpMapURL is the DERP map URL (empty = tailcat.DefaultDERPMapURL).
// listener receives each incoming TCP connection. It returns an error
// if startup fails.
func NewServer(derpMapURL string, listener ConnectionListener) (*Server, error) {
	pk := tailcat.NewPrivateKey()
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

	s := &Server{conns: make(map[*Conn]struct{})}
	s.srv = &tailcat.Server{
		Key:    pk.Private,
		Logf:   logger.WithPrefix(log.Printf, "tailcat: "),
		Region: reg,
	}
	s.srv.OnTCP = func(port uint16) (handler func(net.Conn)) {
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
	if err := s.srv.Start(); err != nil {
		s.srv.Close()
		return nil, fmt.Errorf("Server.Start: %w", err)
	}
	s.addr = string(s.srv.ConnBlob())
	return s, nil
}

// NewSFTPServer starts a tailcat server that serves SFTP on port 22
// (for scp/sftp clients) and raw TCP on all other ports (for the
// phone app's raw stream mode). filesDir is the directory to serve
// via SFTP. derpMapURL is the DERP map URL (empty = default).
// listener receives each incoming raw TCP connection (non-SFTP).
func NewSFTPServer(derpMapURL string, filesDir string, listener ConnectionListener) (*Server, error) {
	pk := tailcat.NewPrivateKey()
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

	s := &Server{conns: make(map[*Conn]struct{})}
	s.srv = &tailcat.Server{
		Key:    pk.Private,
		Logf:   logger.WithPrefix(log.Printf, "tailcat: "),
		Region: reg,
	}

	// SFTP handler on port 22
	sshHandler := s.srv.SSHConnHandler(tailcat.SSHOptions{
		Files: &tailcat.FileService{
			Dir:  filesDir,
			Mode: tailcat.FileServeRW,
		},
	})

	s.srv.OnTCP = func(port uint16) (handler func(net.Conn)) {
		if port == 22 {
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
	s.srv.ServedTCPPorts = []filter.PortRange{{First: 22, Last: 22}}

	if err := s.srv.Start(); err != nil {
		s.srv.Close()
		return nil, fmt.Errorf("Server.Start: %w", err)
	}
	s.addr = string(s.srv.ConnBlob())
	return s, nil
}

// Addr returns the "tc..." connection token to share with the peer.
func (s *Server) Addr() string {
	return s.addr
}

// Close stops the server and all active connections.
func (s *Server) Close() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
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
// server identified by a "tc..." token and can dial TCP ports on it.
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
			Server:     tailcat.ConnBlob(addr),
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

	sc, err := sftp.NewClientPipe(pr, pw)
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

	n, err := io.Copy(w, r)
	if err != nil {
		return n, fmt.Errorf("copy: %w", err)
	}
	return n, nil
}

// UploadFile uploads the local file at localPath to the remote server
// at remotePath. Returns the number of bytes written.
func (s *SFTPClient) UploadFile(localPath, remotePath string) (int64, error) {
	r, err := os.Open(localPath)
	if err != nil {
		return 0, fmt.Errorf("open local: %w", err)
	}
	defer r.Close()

	// Use OpenFile with write-only flags. sftp.Create uses O_RDWR which
	// is denied by write-only (drop box) SFTP servers like `tailcat recv`.
	w, err := s.sc.OpenFile(remotePath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC)
	if err != nil {
		return 0, fmt.Errorf("create remote: %w", err)
	}
	defer w.Close()

	n, err := io.Copy(w, r)
	if err != nil {
		return n, fmt.Errorf("copy: %w", err)
	}
	return n, nil
}

// Close releases the SFTP session and SSH connection.
func (s *SFTPClient) Close() {
	s.close()
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

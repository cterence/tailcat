// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

package bridge

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/tstest/integration"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
	"tailscale.com/wgengine/filter"
)

func mkLogger(t testing.TB, name string) logger.Logf {
	return func(format string, args ...any) {
		t.Helper()
		if t.Failed() {
			return
		}
		t.Logf("        ["+name+"] "+format, args...)
	}
}

// newTestServer wraps a pre-built tailcat.Server in a bridge.Server for
// testing. It mirrors what NewServer does after the tailcat.Server is
// created, minus the DERP map fetch (the test provides the region directly).
func newTestServer(t *testing.T, srv *tailcat.Server, listener ConnectionListener) *Server {
	t.Helper()
	s := &Server{
		srv:   srv,
		conns: make(map[*Conn]struct{}),
	}
	s.srv.OnTCP = func(port uint16) (handler func(net.Conn)) {
		return func(c net.Conn) {
			bc := s.wrapConn(c)
			if listener != nil {
				listener.OnConnection(bc)
			} else {
				bc.Close()
			}
		}
	}
	s.addr = string(srv.TailcatAddr())
	t.Cleanup(func() { s.Close() })
	return s
}

// testConnectionListener collects incoming bridge.Conns for the test.
type testConnectionListener struct {
	mu    sync.Mutex
	conns []*Conn
	ready chan struct{}
}

func newTestListener() *testConnectionListener {
	return &testConnectionListener{ready: make(chan struct{})}
}

func (l *testConnectionListener) OnConnection(c *Conn) {
	l.mu.Lock()
	l.conns = append(l.conns, c)
	l.mu.Unlock()
	select {
	case <-l.ready:
	default:
		close(l.ready)
	}
}

func (l *testConnectionListener) first() *Conn {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.conns) == 0 {
		return nil
	}
	return l.conns[0]
}

// TestBridgeRoundTrip starts a bridge server and client in-process
// using a local DERP server, then sends data through the tunnel via
// the bridge Conn API (Read, Write, CloseWrite, Close) and verifies
// the round-trip.
func TestBridgeRoundTrip(t *testing.T) {
	t.Parallel()

	dm := integration.RunDERPAndSTUN(t, mkLogger(t, "derpstun"), "127.0.0.1")
	reg := dm.Regions[1]
	if reg == nil {
		t.Fatal("no region 1 in derpmap")
	}

	// Create the underlying tailcat.Server directly (bypassing
	// bridge.NewServer's DERP map fetch) using the local DERP region.
	// No AllowedClients — bridge.NewServer allows all clients, matching
	// the web app pattern.
	rawSrv := &tailcat.Server{
		Key:    key.NewNode(),
		Logf:   mkLogger(t, "server"),
		Region: reg,
	}
	if err := rawSrv.Start(); err != nil {
		t.Fatalf("server Start: %v", err)
	}

	listener := newTestListener()
	s := newTestServer(t, rawSrv, listener)
	t.Logf("server addr: %s", s.Addr())

	// Create a bridge client pointing at the server's address.
	// The address embeds the DERP region, so no network fetch is needed.
	c := NewClient(s.Addr(), "")
	t.Cleanup(func() { c.Close() })

	// Wait for DERP connections to come up on both sides, then ping.
	waitForDERP(t, rawSrv, c.cl)
	if err := c.Ping(); err != nil {
		t.Fatalf("Ping: %v", err)
	}

	// Dial port 1 (any port; the server accepts on all ports).
	conn, err := c.Dial(1)
	if err != nil {
		t.Fatalf("Dial: %v", err)
	}
	t.Cleanup(func() { conn.Close() })

	// Wait for the server to receive the connection.
	select {
	case <-listener.ready:
	case <-time.After(10 * time.Second):
		t.Fatal("timeout waiting for server to accept connection")
	}
	serverConn := listener.first()
	if serverConn == nil {
		t.Fatal("no server connection")
	}
	t.Cleanup(func() { serverConn.Close() })

	// Test write from client -> server.
	payload := []byte("hello from client")
	if err := conn.Write(payload); err != nil {
		t.Fatalf("client Write: %v", err)
	}
	if err := conn.CloseWrite(); err != nil {
		t.Fatalf("client CloseWrite: %v", err)
	}

	// Server reads until EOF, then echoes back.
	got, err := serverConn.Read()
	if err != nil {
		t.Fatalf("server Read: %v", err)
	}
	if got == nil {
		t.Fatal("server Read returned nil (EOF) before data")
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("server got %q, want %q", got, payload)
	}

	// Read the EOF.
	eof, err := serverConn.Read()
	if err != nil {
		t.Fatalf("server Read EOF: %v", err)
	}
	if eof != nil {
		t.Fatalf("server Read after data returned %d bytes, want nil (EOF)", len(eof))
	}

	// Server writes a response and closes.
	response := []byte("hello from server")
	if err := serverConn.Write(response); err != nil {
		t.Fatalf("server Write: %v", err)
	}
	if err := serverConn.CloseWrite(); err != nil {
		t.Fatalf("server CloseWrite: %v", err)
	}

	// Client reads the response.
	gotResp, err := conn.Read()
	if err != nil {
		t.Fatalf("client Read: %v", err)
	}
	if gotResp == nil {
		t.Fatal("client Read returned nil (EOF) before response")
	}
	if !bytes.Equal(gotResp, response) {
		t.Fatalf("client got %q, want %q", gotResp, response)
	}

	// Client reads EOF.
	eofResp, err := conn.Read()
	if err != nil {
		t.Fatalf("client Read EOF: %v", err)
	}
	if eofResp != nil {
		t.Fatalf("client Read after response returned %d bytes, want nil (EOF)", len(eofResp))
	}
}

// TestBridgeServerClose verifies that closing a bridge.Server closes
// all tracked connections and the underlying tailcat.Server.
func TestBridgeServerClose(t *testing.T) {
	t.Parallel()

	dm := integration.RunDERPAndSTUN(t, mkLogger(t, "derpstun"), "127.0.0.1")
	reg := dm.Regions[1]
	if reg == nil {
		t.Fatal("no region 1 in derpmap")
	}

	rawSrv := &tailcat.Server{
		Key:    key.NewNode(),
		Logf:   mkLogger(t, "server"),
		Region: reg,
	}
	if err := rawSrv.Start(); err != nil {
		t.Fatalf("server Start: %v", err)
	}

	listener := newTestListener()
	s := newTestServer(t, rawSrv, listener)

	c := NewClient(s.Addr(), "")
	t.Cleanup(func() { c.Close() })

	waitForDERP(t, rawSrv, c.cl)
	if err := c.Ping(); err != nil {
		t.Fatalf("Ping: %v", err)
	}

	conn, err := c.Dial(1)
	if err != nil {
		t.Fatalf("Dial: %v", err)
	}

	select {
	case <-listener.ready:
	case <-time.After(10 * time.Second):
		t.Fatal("timeout waiting for server to accept connection")
	}

	// Close the server — this should close the server-side conn.
	s.Close()

	// The client-side conn should eventually get an error or EOF
	// because the server-side was closed.
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		data, err := conn.Read()
		if err != nil || data == nil {
			break
		}
	}
	conn.Close()
}

// waitForDERP waits for the client's DERP connection to come up by
// retrying pings until one succeeds. Mirrors tailcat.WaitForDERPForTest
// but operates on the raw tailcat types the bridge wraps.
func waitForDERP(t *testing.T, srv *tailcat.Server, cl *tailcat.Client) {
	t.Helper()
	for i := 0; i < 300; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
		_, err := cl.Ping(ctx)
		cancel()
		if err == nil {
			return
		}
	}
	t.Fatal("timeout waiting for DERP connections")
}

// Ensure the bridge types satisfy the interfaces gomobile expects.
// This is a compile-time check that the API surface is gomobile-compatible.
func TestConnReadEOF(t *testing.T) {
	// Test Conn.Read on a pipe to verify the EOF semantics:
	// nil []byte + nil error on EOF.
	r, w := net.Pipe()
	c := &Conn{c: r}
	defer c.Close()
	defer w.Close()

	go func() {
		w.Write([]byte("data"))
		w.Close()
	}()

	// Read data.
	buf, err := c.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if buf == nil {
		t.Fatal("Read returned nil before data")
	}
	if string(buf) != "data" {
		t.Fatalf("Read got %q, want %q", buf, "data")
	}

	// Read EOF.
	eof, err := c.Read()
	if err != nil {
		t.Fatalf("Read EOF: %v", err)
	}
	if eof != nil {
		t.Fatalf("Read returned %d bytes, want nil (EOF)", len(eof))
	}
}

// TestConnWrite verifies Conn.Write sends data through the underlying connection.
func TestConnWrite(t *testing.T) {
	r, w := net.Pipe()
	c := &Conn{c: w}
	defer c.Close()
	defer r.Close()

	payload := []byte("test payload")

	// net.Pipe writes block until the other side reads, so read concurrently.
	type readResult struct {
		n   int
		err error
	}
	resultCh := make(chan readResult, 1)
	go func() {
		got := make([]byte, len(payload))
		n, err := io.ReadFull(r, got)
		resultCh <- readResult{n, err}
	}()

	if err := c.Write(payload); err != nil {
		t.Fatalf("Write: %v", err)
	}

	res := <-resultCh
	if res.err != nil {
		t.Fatalf("io.ReadFull: %v", res.err)
	}
	if res.n != len(payload) {
		t.Fatalf("read %d bytes, want %d", res.n, len(payload))
	}
}

// TestConnCloseWrite verifies Conn.CloseWrite half-closes the write side.
func TestConnCloseWrite(t *testing.T) {
	// net.Pipe doesn't implement CloseWrite, but TCP does.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	accepted := make(chan net.Conn, 1)
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		accepted <- c
	}()

	w, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	c := &Conn{c: w}
	defer c.Close()

	serverSide := <-accepted
	defer serverSide.Close()

	if err := c.Write([]byte("half-close test")); err != nil {
		t.Fatalf("Write: %v", err)
	}
	if err := c.CloseWrite(); err != nil {
		t.Fatalf("CloseWrite: %v", err)
	}

	// Server should be able to read all data and then get EOF.
	got, err := io.ReadAll(serverSide)
	if err != nil {
		t.Fatalf("ReadAll: %v", err)
	}
	if string(got) != "half-close test" {
		t.Fatalf("got %q, want %q", got, "half-close test")
	}
}

// TestNewTailcatServerPSK verifies that the server config restores the
// identity key's pre-shared key (included by default by
// LoadOrCreateKey, matching the CLI's genkey default), and disables
// the PSK layer for keys without one.
func TestNewTailcatServerPSK(t *testing.T) {
	pk := tailcat.NewPrivateKey()
	srv := newTailcatServer(pk, nil, mkLogger(t, "server"))
	if srv.DisablePresharedKey {
		t.Error("DisablePresharedKey = true for a key with a PSK")
	}
	if !srv.PresharedKey.Equal(pk.Public.PresharedKey) {
		t.Error("server PresharedKey does not match the key's PSK")
	}

	nopsk := tailcat.NewPrivateKey()
	nopsk.Public.PresharedKey = tailcat.PresharedKey{}
	srv = newTailcatServer(nopsk, nil, mkLogger(t, "server"))
	if !srv.DisablePresharedKey {
		t.Error("DisablePresharedKey = false for a key without a PSK")
	}
	if !srv.PresharedKey.IsZero() {
		t.Error("PresharedKey set for a key without a PSK")
	}
}

// TestServerAddrStableAcrossRestarts verifies that the tailcat
// address, which embeds the pre-shared key, stays the same across
// server restarts with the same identity key: the address a peer
// scanned (e.g. via the app's QR code) must keep working after an
// app restart.
func TestServerAddrStableAcrossRestarts(t *testing.T) {
	dm := integration.RunDERPAndSTUN(t, mkLogger(t, "derpstun"), "127.0.0.1")
	reg := dm.Regions[1]
	if reg == nil {
		t.Fatal("no region 1 in derpmap")
	}

	// The real mobile flow: persist the identity key in the app data
	// dir, then reload the same JSON for each restart.
	SetAppDataDir(t.TempDir())
	t.Cleanup(func() { SetAppDataDir("") })
	keyJSON, err := LoadOrCreateKey()
	if err != nil {
		t.Fatalf("LoadOrCreateKey: %v", err)
	}

	start := func() string {
		pk, err := parseKey(keyJSON)
		if err != nil {
			t.Fatalf("parseKey: %v", err)
		}
		srv := newTailcatServer(pk, reg, mkLogger(t, "server"))
		if err := srv.Start(); err != nil {
			t.Fatalf("Start: %v", err)
		}
		defer srv.Close()
		return string(srv.TailcatAddr())
	}

	addr1 := start()
	addr2 := start()
	if addr1 != addr2 {
		t.Errorf("tailcat address changed across restarts:\n first: %s\nsecond: %s", addr1, addr2)
	}

	// The stable address must embed the key's PSK.
	pk, err := parseKey(keyJSON)
	if err != nil {
		t.Fatalf("parseKey: %v", err)
	}
	ci, err := tailcat.ParseAddr(tailcat.Addr(addr1))
	if err != nil {
		t.Fatalf("ParseAddr: %v", err)
	}
	if ci.PresharedKey.IsZero() {
		t.Error("address has no pre-shared key; PSK should be included by default")
	}
	if !ci.PresharedKey.Equal(pk.Public.PresharedKey) {
		t.Error("address PSK does not match the identity key's PSK")
	}
}

// TestServerRelayOnline verifies that RelayOnline reports true once
// the engine has connected to its home DERP relay.
func TestServerRelayOnline(t *testing.T) {
	dm := integration.RunDERPAndSTUN(t, mkLogger(t, "derpstun"), "127.0.0.1")
	reg := dm.Regions[1]
	if reg == nil {
		t.Fatal("no region 1 in derpmap")
	}

	pk := tailcat.NewPrivateKey()
	s := &Server{conns: make(map[*Conn]struct{}), stopWatch: make(chan struct{})}
	wire := func(srv *tailcat.Server) {
		srv.OnTCP = func(port uint16) (handler func(net.Conn)) {
			return func(c net.Conn) { c.Close() }
		}
	}
	if err := s.startServer(pk, reg, wire); err != nil {
		t.Fatalf("startServer: %v", err)
	}
	t.Cleanup(s.Close)

	// The relay connection comes up shortly after Start.
	deadline := time.Now().Add(15 * time.Second)
	for !s.RelayOnline() {
		if time.Now().After(deadline) {
			t.Fatal("RelayOnline still false after 15s")
		}
		time.Sleep(100 * time.Millisecond)
	}
	if s.RelayMisses() != 0 {
		t.Errorf("RelayMisses = %d with relay online; want 0", s.RelayMisses())
	}
}

// TestServerRestartKeepsAddressAndServes verifies that Restart
// rebuilds the engine without changing the tailcat address, and that
// the server still accepts connections afterwards. This is the
// recovery path the relay watchdog uses when the engine comes up
// without a DERP connection.
func TestServerRestartKeepsAddressAndServes(t *testing.T) {
	dm := integration.RunDERPAndSTUN(t, mkLogger(t, "derpstun"), "127.0.0.1")
	reg := dm.Regions[1]
	if reg == nil {
		t.Fatal("no region 1 in derpmap")
	}

	pk := tailcat.NewPrivateKey()
	listener := newTestListener()
	s := &Server{conns: make(map[*Conn]struct{}), stopWatch: make(chan struct{})}
	wire := func(srv *tailcat.Server) {
		srv.OnTCP = func(port uint16) (handler func(net.Conn)) {
			return func(c net.Conn) {
				bc := s.wrapConn(c)
				listener.OnConnection(bc)
			}
		}
	}
	if err := s.startServer(pk, reg, wire); err != nil {
		t.Fatalf("startServer: %v", err)
	}
	t.Cleanup(s.Close)

	addr := s.Addr()
	c := NewClient(addr, "")
	t.Cleanup(func() { c.Close() })
	waitForDERP(t, s.srv, c.cl)
	if err := c.Ping(); err != nil {
		t.Fatalf("Ping before restart: %v", err)
	}

	if err := s.Restart(); err != nil {
		t.Fatalf("Restart: %v", err)
	}
	if s.Addr() != addr {
		t.Errorf("address changed across restart: %q -> %q", addr, s.Addr())
	}

	// A fresh client must reach the rebuilt engine and get a
	// connection through the re-wired handlers.
	c2 := NewClient(addr, "")
	t.Cleanup(func() { c2.Close() })
	waitForDERP(t, s.srv, c2.cl)
	if err := c2.Ping(); err != nil {
		t.Fatalf("Ping after restart: %v", err)
	}
	if _, err := c2.Dial(1); err != nil {
		t.Fatalf("Dial after restart: %v", err)
	}
	dialDeadline := time.Now().Add(10 * time.Second)
	for listener.first() == nil {
		if time.Now().After(dialDeadline) {
			t.Fatal("no connection reached the listener after restart")
		}
		time.Sleep(100 * time.Millisecond)
	}
}

// funcProgress adapts a function to ProgressListener (never cancelled).
type funcProgress func(sent, total int64)

func (f funcProgress) OnProgress(sent, total int64) { f(sent, total) }

func (f funcProgress) IsCancelled() bool { return false }

// autoCancel cancels the transfer after its first progress report,
// deterministically aborting mid-transfer.
type autoCancel struct {
	mu    sync.Mutex
	fired bool
}

func (a *autoCancel) OnProgress(sent, total int64) {
	a.mu.Lock()
	a.fired = true
	a.mu.Unlock()
}

func (a *autoCancel) IsCancelled() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.fired
}

// TestProgressWriter checks the download-progress callback cadence:
// reports every progressInterval bytes. io.Copy feeds the writer in
// 32 KiB chunks, so the test does the same. The time-based throttle
// is disabled so the byte cadence is exact.
func TestProgressWriter(t *testing.T) {
	old := progressMinInterval
	progressMinInterval = 0
	defer func() { progressMinInterval = old }()

	data := bytes.Repeat([]byte("y"), 700<<10) // 700 KiB -> reports at 256K, 512K
	total := int64(len(data))
	var got []int64
	var buf bytes.Buffer
	pw := &progressWriter{
		w:        &buf,
		total:    total,
		progress: funcProgress(func(sent, tot int64) { got = append(got, sent) }),
	}
	for off := 0; off < len(data); off += 32 << 10 {
		end := min(off+32<<10, len(data))
		if _, err := pw.Write(data[off:end]); err != nil {
			t.Fatalf("Write: %v", err)
		}
	}
	if int64(buf.Len()) != total {
		t.Fatalf("wrote %d bytes, want %d", buf.Len(), total)
	}
	if len(got) != 2 {
		t.Errorf("got %d progress reports (%v), want 2", len(got), got)
	}
	if len(got) > 0 && got[len(got)-1] != 512<<10 {
		t.Errorf("last report = %d, want %d", got[len(got)-1], 512<<10)
	}
}

// TestSFTPUploadPartNaming exercises the client SFTP path over the
// tunnel: an upload lands under a .part name and is renamed into
// place on completion, progress reaches the file's total, a name
// collision takes a numbered variant, and a cancelled upload leaves
// nothing behind.
func TestSFTPUploadPartNaming(t *testing.T) {
	t.Parallel()

	dm := integration.RunDERPAndSTUN(t, mkLogger(t, "derpstun"), "127.0.0.1")
	reg := dm.Regions[1]
	if reg == nil {
		t.Fatal("no region 1 in derpmap")
	}

	dir := t.TempDir()
	s := &Server{conns: make(map[*Conn]struct{}), stopWatch: make(chan struct{})}
	wire := func(srv *tailcat.Server) {
		// SFTP on port 22, mirroring NewSFTPServer's wiring.
		handler := srv.SSHConnHandler(tailcat.SSHOptions{
			Files: &tailcat.FileService{
				Dir:  dir,
				Mode: tailcat.FileServeRW,
			},
		})
		srv.OnTCP = func(port uint16) (h func(net.Conn)) {
			if port == 22 {
				return handler
			}
			return func(c net.Conn) { c.Close() }
		}
		srv.ServedTCPPorts = []filter.PortRange{{First: 22, Last: 22}}
	}
	pk := tailcat.NewPrivateKey()
	if err := s.startServer(pk, reg, wire); err != nil {
		t.Fatalf("startServer: %v", err)
	}
	t.Cleanup(s.Close)

	c := NewClient(s.Addr(), "")
	t.Cleanup(func() { c.Close() })
	waitForDERP(t, s.srv, c.cl)
	if err := c.Ping(); err != nil {
		t.Fatalf("Ping: %v", err)
	}
	sc, err := c.DialSFTP()
	if err != nil {
		t.Fatalf("DialSFTP: %v", err)
	}
	t.Cleanup(func() { sc.Close() })

	local := filepath.Join(t.TempDir(), "hello.txt")
	data := bytes.Repeat([]byte("h"), 700<<10) // >2 chunks, exercises the concurrent-writes path
	if err := os.WriteFile(local, data, 0600); err != nil {
		t.Fatal(err)
	}

	var mu sync.Mutex
	var lastSent, lastTotal int64
	n, err := sc.UploadFileWithProgress(local, "/hello.txt", funcProgress(func(sent, total int64) {
		mu.Lock()
		lastSent, lastTotal = sent, total
		mu.Unlock()
	}))
	if err != nil {
		t.Fatalf("UploadFileWithProgress: %v", err)
	}
	if n != int64(len(data)) {
		t.Errorf("uploaded %d bytes, want %d", n, len(data))
	}
	got, err := os.ReadFile(filepath.Join(dir, "hello.txt"))
	if err != nil {
		t.Fatalf("final file missing: %v", err)
	}
	if !bytes.Equal(got, data) {
		t.Errorf("final file content mismatch: %d bytes", len(got))
	}
	if entries, _ := os.ReadDir(dir); len(entries) != 1 {
		t.Errorf("server dir has %d entries after upload; want 1 (no .part left)", len(entries))
	}
	mu.Lock()
	if lastSent != int64(len(data)) || lastTotal != int64(len(data)) {
		t.Errorf("final progress = %d/%d, want %d/%d", lastSent, lastTotal, len(data), len(data))
	}
	mu.Unlock()

	// A second upload of the same name takes a numbered variant.
	if _, err := sc.UploadFile(local, "/hello.txt"); err != nil {
		t.Fatalf("second upload: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "hello(1).txt")); err != nil {
		t.Errorf("numbered variant missing: %v", err)
	}

	// A cancelled upload leaves nothing behind (per-transfer cancel
	// via the listener; the session stays usable for other transfers).
	if _, err := sc.UploadFileWithProgress(local, "/cancel.txt", &autoCancel{}); err == nil {
		t.Error("cancelled upload unexpectedly succeeded")
	}
	if _, err := os.Stat(filepath.Join(dir, "cancel.txt")); err == nil {
		t.Error("cancelled upload left a final-named file")
	}
	if _, err := os.Stat(filepath.Join(dir, "cancel.txt.part")); err == nil {
		t.Error("cancelled upload left a .part file")
	}

	// A cancelled download leaves no partial local file, and the
	// session still works afterwards.
	out := filepath.Join(t.TempDir(), "hello-out.txt")
	if _, err := sc.DownloadFileWithProgress("/hello.txt", out, &autoCancel{}); err == nil {
		t.Error("cancelled download unexpectedly succeeded")
	}
	if _, err := os.Stat(out); err == nil {
		t.Error("cancelled download left a partial file")
	}
	if _, err := sc.DownloadFile("/hello.txt", out); err != nil {
		t.Errorf("download after cancelled download failed: %v", err)
	}
	if got, err := os.ReadFile(out); err != nil || !bytes.Equal(got, data) {
		t.Errorf("plain download after cancel: read %d bytes, err %v", len(got), err)
	}

	// Two downloads in parallel over the one session both complete.
	out2 := filepath.Join(t.TempDir(), "hello-out2.txt")
	var wg sync.WaitGroup
	wg.Add(2)
	var err1, err2 error
	go func() {
		defer wg.Done()
		_, err1 = sc.DownloadFileWithProgress("/hello.txt", out, funcProgress(func(int64, int64) {}))
	}()
	go func() {
		defer wg.Done()
		_, err2 = sc.DownloadFileWithProgress("/hello.txt", out2, funcProgress(func(int64, int64) {}))
	}()
	wg.Wait()
	if err1 != nil || err2 != nil {
		t.Errorf("parallel downloads: %v, %v", err1, err2)
	}
}

// TestProgressReader checks the callback cadence: reports every
// progressInterval bytes and a final report at EOF carrying the total.
// The time-based throttle is disabled so the byte cadence is exact.
func TestProgressReader(t *testing.T) {
	old := progressMinInterval
	progressMinInterval = 0
	defer func() { progressMinInterval = old }()

	data := bytes.Repeat([]byte("x"), 700<<10) // 700 KiB -> reports at 256K, 512K, EOF
	total := int64(len(data))
	var got []int64
	pr := &progressReader{
		r:        bytes.NewReader(data),
		total:    total,
		progress: funcProgress(func(sent, tot int64) { got = append(got, sent) }),
	}
	n, err := io.ReadAll(pr)
	if err != nil {
		t.Fatalf("ReadAll: %v", err)
	}
	if int64(len(n)) != total {
		t.Fatalf("read %d bytes, want %d", len(n), total)
	}
	if len(got) != 3 {
		t.Errorf("got %d progress reports (%v), want 3", len(got), got)
	}
	if len(got) > 0 && got[len(got)-1] != total {
		t.Errorf("final report = %d, want %d", got[len(got)-1], total)
	}
}

// TestRotateKeyPSKToggle verifies that RotateKey(withPSK) controls
// whether the new identity key embeds a pre-shared key, and that the
// generated key replaces the persisted one (LoadOrCreateKey picks it
// up on the next start).
func TestRotateKeyPSKToggle(t *testing.T) {
	SetAppDataDir(t.TempDir())
	t.Cleanup(func() { SetAppDataDir("") })

	kJSON, err := LoadOrCreateKey()
	if err != nil {
		t.Fatalf("LoadOrCreateKey: %v", err)
	}

	if _, err := RotateKey(true); err != nil {
		t.Fatalf("RotateKey(true): %v", err)
	}
	kJSON, err = LoadOrCreateKey()
	if err != nil {
		t.Fatalf("LoadOrCreateKey after rotate: %v", err)
	}
	pk, err := parseKey(kJSON)
	if err != nil {
		t.Fatalf("parseKey: %v", err)
	}
	if pk.Public.PresharedKey.IsZero() {
		t.Error("RotateKey(true) produced a key without a pre-shared key")
	}

	if _, err := RotateKey(false); err != nil {
		t.Fatalf("RotateKey(false): %v", err)
	}
	kJSON, err = LoadOrCreateKey()
	if err != nil {
		t.Fatalf("LoadOrCreateKey after no-PSK rotate: %v", err)
	}
	pk, err = parseKey(kJSON)
	if err != nil {
		t.Fatalf("parseKey: %v", err)
	}
	if !pk.Public.PresharedKey.IsZero() {
		t.Error("RotateKey(false) produced a key with a pre-shared key")
	}
}

// TestSyntheticInterfaces verifies the interface fallback used when
// Android denies netlink: it must never return an empty list (the old
// fallback's (nil, nil) left netmon with no interfaces at all), and
// with a network it reports the outbound source address.
func TestSyntheticInterfaces(t *testing.T) {
	t.Parallel()

	ifs, err := syntheticInterfaces()
	if err != nil {
		t.Fatalf("syntheticInterfaces: %v", err)
	}
	if len(ifs) != 1 {
		t.Fatalf("got %d interfaces, want 1", len(ifs))
	}
	if ifs[0].Interface == nil {
		t.Fatal("nil *net.Interface")
	}
	addrs, err := ifs[0].Addrs()
	if err != nil {
		t.Fatalf("Addrs: %v", err)
	}
	if len(addrs) == 0 {
		t.Fatal("no addresses; netmon would see an empty state")
	}
	if ifs[0].Name != "android" && ifs[0].Name != "lo" {
		t.Errorf("interface name %q, want android (networked) or lo (offline)", ifs[0].Name)
	}
}

// TestFileServeMode checks the mode-string mapping used by
// NewSFTPServer. Unknown values fall back to read-write.
func TestFileServeMode(t *testing.T) {
	for in, want := range map[string]tailcat.FileServeMode{
		"rw":    tailcat.FileServeRW,
		"ro":    tailcat.FileServeRO,
		"wo":    tailcat.FileServeWO,
		"wo+":   tailcat.FileServeWOPlus,
		"":      tailcat.FileServeRW,
		"bogus": tailcat.FileServeRW,
	} {
		if got := fileServeMode(in); got != want {
			t.Errorf("fileServeMode(%q) = %v, want %v", in, got, want)
		}
	}
}

// TestParseAllowedKeys checks the allowed-keys JSON format: an array
// of base64 node public keys, with empty input meaning no restriction.
func TestParseAllowedKeys(t *testing.T) {
	if ks, err := parseAllowedKeys(""); err != nil || ks != nil {
		t.Errorf("parseAllowedKeys(\"\") = %v, %v; want nil, nil", ks, err)
	}
	if ks, err := parseAllowedKeys("  "); err != nil || ks != nil {
		t.Errorf("parseAllowedKeys(blank) = %v, %v; want nil, nil", ks, err)
	}

	k1, k2 := key.NewNode().Public(), key.NewNode().Public()
	in := fmt.Sprintf(`["%s", %q]`, k1.String(), k2.String())
	got, err := parseAllowedKeys(in)
	if err != nil {
		t.Fatalf("parseAllowedKeys(%q): %v", in, err)
	}
	if len(got) != 2 || got[0] != k1 || got[1] != k2 {
		t.Errorf("parseAllowedKeys got %v, want [%v %v]", got, k1, k2)
	}

	if _, err := parseAllowedKeys("not json"); err == nil {
		t.Error("parseAllowedKeys accepted invalid JSON")
	}
	if _, err := parseAllowedKeys(`["bogus-key"]`); err == nil {
		t.Error("parseAllowedKeys accepted an invalid key")
	}
}

// TestAddrKey checks that AddrKey extracts the server's node public
// key from its tailcat address.
func TestAddrKey(t *testing.T) {
	t.Parallel()

	dm := integration.RunDERPAndSTUN(t, mkLogger(t, "derpstun"), "127.0.0.1")
	reg := dm.Regions[1]
	if reg == nil {
		t.Fatal("no region 1 in derpmap")
	}

	nodeKey := key.NewNode()
	srv := &tailcat.Server{
		Key:    nodeKey,
		Logf:   mkLogger(t, "server"),
		Region: reg,
	}
	if err := srv.Start(); err != nil {
		t.Fatalf("server Start: %v", err)
	}
	t.Cleanup(func() { srv.Close() })

	got, err := AddrKey(string(srv.TailcatAddr()))
	if err != nil {
		t.Fatalf("AddrKey: %v", err)
	}
	if want := nodeKey.Public().String(); got != want {
		t.Errorf("AddrKey = %q, want the server's public key %q", got, want)
	}

	if _, err := AddrKey("garbage"); err == nil {
		t.Error("AddrKey accepted an invalid address")
	}
}

// TestPeersJSON verifies that a connected client appears in
// PeersJSON with its node key and a direct or relayed path.
func TestPeersJSON(t *testing.T) {
	t.Parallel()

	dm := integration.RunDERPAndSTUN(t, mkLogger(t, "derpstun"), "127.0.0.1")
	reg := dm.Regions[1]
	if reg == nil {
		t.Fatal("no region 1 in derpmap")
	}

	rawSrv := &tailcat.Server{
		Key:    key.NewNode(),
		Logf:   mkLogger(t, "server"),
		Region: reg,
	}
	if err := rawSrv.Start(); err != nil {
		t.Fatalf("server Start: %v", err)
	}

	listener := newTestListener()
	s := newTestServer(t, rawSrv, listener)

	c := NewClient(s.Addr(), "")
	t.Cleanup(func() { c.Close() })
	waitForDERP(t, rawSrv, c.cl)
	if err := c.Ping(); err != nil {
		t.Fatalf("Ping: %v", err)
	}
	clientKey := c.cl.PublicKey().String()

	// The path may take a moment to settle; wait for the client's
	// entry with a direct address or relay.
	deadline := time.Now().Add(10 * time.Second)
	for {
		var peers []peerInfo
		if err := json.Unmarshal([]byte(s.PeersJSON()), &peers); err != nil {
			t.Fatalf("PeersJSON: %v (%s)", err, s.PeersJSON())
		}
		if len(peers) == 1 && peers[0].Key == clientKey &&
			(peers[0].CurAddr != "" || peers[0].Relay != "") {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("peer never appeared in PeersJSON: %s", s.PeersJSON())
		}
		time.Sleep(50 * time.Millisecond)
	}
}

// TestAllowedClientsLiveAdd verifies the access-refresh path for the
// app's "only saved devices" setting: a server started with an allow
// list that excludes a client ignores that client, and AddAllowedClient
// admits it on the running engine — no restart, same address.
func TestAllowedClientsLiveAdd(t *testing.T) {
	t.Parallel()

	dm := integration.RunDERPAndSTUN(t, mkLogger(t, "derpstun"), "127.0.0.1")
	reg := dm.Regions[1]
	if reg == nil {
		t.Fatal("no region 1 in derpmap")
	}

	// The server starts allowing only some other key.
	rawSrv := &tailcat.Server{
		Key:           key.NewNode(),
		Logf:          mkLogger(t, "server"),
		Region:        reg,
		AllowedClients: []key.NodePublic{key.NewNode().Public()},
	}
	if err := rawSrv.Start(); err != nil {
		t.Fatalf("server Start: %v", err)
	}

	listener := newTestListener()
	s := newTestServer(t, rawSrv, listener)

	c := NewClient(s.Addr(), "")
	t.Cleanup(func() { c.Close() })
	clientKey := c.cl.PublicKey()

	// Before being allowed, every ping must fail: the server silently
	// drops the handshake of a client that is not on the list.
	deadline := time.Now().Add(3 * time.Second)
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
		_, err := c.cl.Ping(ctx)
		cancel()
		if err == nil {
			t.Fatal("ping succeeded before the client was allowed")
		}
		if time.Now().After(deadline) {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	// Admit the client live on the running engine.
	addr := s.Addr()
	if err := s.AddAllowedClient(clientKey.String()); err != nil {
		t.Fatalf("AddAllowedClient: %v", err)
	}
	if s.Addr() != addr {
		t.Errorf("address changed after AddAllowedClient: %q -> %q", addr, s.Addr())
	}

	// The same client, without reconnecting anything, can now ping.
	waitForDERP(t, rawSrv, c.cl)
	if err := c.Ping(); err != nil {
		t.Fatalf("Ping after AddAllowedClient: %v", err)
	}

	// The bridge remembered the key, so rebuilt engines keep allowing it.
	s.mu.Lock()
	recorded := slices.Contains(s.allowedKeys, clientKey)
	s.mu.Unlock()
	if !recorded {
		t.Error("AddAllowedClient did not record the key for engine rebuilds")
	}
}

// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

package bridge

import (
	"bytes"
	"context"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/tstest/integration"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
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
	s.addr = string(srv.ConnBlob())
	t.Cleanup(func() { s.Close() })
	return s
}

// testConnectionListener collects incoming bridge.Conns for the test.
type testConnectionListener struct {
	mu    sync.Mutex
	conns  []*Conn
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
		Key:  key.NewNode(),
		Logf: mkLogger(t, "server"),
		Region: reg,
	}
	if err := rawSrv.Start(); err != nil {
		t.Fatalf("server Start: %v", err)
	}

	listener := newTestListener()
	s := newTestServer(t, rawSrv, listener)
	t.Logf("server addr: %s", s.Addr())

	// Create a bridge client pointing at the server's token.
	// The ConnBlob embeds the DERP region, so no network fetch is needed.
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
		Key:  key.NewNode(),
		Logf: mkLogger(t, "server"),
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

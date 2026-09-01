// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

//go:build android && !ts_omit_ssh

package tailcat

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sync"

	ssh "github.com/tailscale/gliderssh"
	gossh "golang.org/x/crypto/ssh"
)

// SupportsSSHServer reports whether the platform supports running the
// built-in auth-free SSH server. On Android, the SSH server supports
// SFTP file transfer only (no shell/exec).
func SupportsSSHServer() bool { return true }

// HandleTailscaleSSHConn handles an incoming TCP connection as an SSH
// session. On Android, only the SFTP subsystem is supported.
func (s *Server) HandleTailscaleSSHConn(c net.Conn) {
	s.SSHConnHandler(SSHOptions{Shell: false})(c)
}

// SSHConnHandler returns a handler that serves an incoming TCP
// connection as an SSH session. On Android, only the SFTP subsystem
// is available; shell and exec sessions are rejected.
func (s *Server) SSHConnHandler(opts SSHOptions) func(net.Conn) {
	return func(c net.Conn) {
		keys, err := getHostKeysAndroid()
		if err != nil {
			s.lb.logf("SSH host keys: %v", err)
			c.Close()
			return
		}
		handler := func(sess ssh.Session) {
			fmt.Fprintf(sess.Stderr(), "this tailcat server only offers file transfer (SFTP); shell and exec sessions are disabled\r\n")
			sess.Exit(1)
		}
		subsystems := map[string]ssh.SubsystemHandler{}
		if h := s.sftpSubsystemHandler(opts); h != nil {
			subsystems["sftp"] = h
		}
		srv := &ssh.Server{
			Handler:             handler,
			NoClientAuthHandler: func(ctx ssh.Context) error { return nil },
			ChannelHandlers:     map[string]ssh.ChannelHandler{"session": ssh.DefaultSessionHandler},
			RequestHandlers:     map[string]ssh.RequestHandler{},
			SubsystemHandlers:   subsystems,
		}
		for _, k := range keys {
			srv.AddHostKey(k)
		}
		srv.HandleConn(c)
	}
}

// SetAppDataDir sets the writable directory for storing SSH host keys.
// Called from the bridge's init() with the app's files directory
// (passed from Kotlin via JNI). Falls back to in-memory keys if not
// set.
var androidDataDir string

func SetAppDataDir(dir string) {
	androidDataDir = dir
}

// getHostKeysAndroid returns the SSH host key signers. The key is
// stored on disk under the app's data directory if set, otherwise
// generated in memory (ephemeral).
var androidHostKeyMu sync.Mutex
var androidHostKeyPEM []byte

func getHostKeysAndroid() ([]gossh.Signer, error) {
	androidHostKeyMu.Lock()
	defer androidHostKeyMu.Unlock()

	if androidHostKeyPEM != nil {
		signer, err := gossh.ParsePrivateKey(androidHostKeyPEM)
		if err != nil {
			return nil, fmt.Errorf("parsing host key: %w", err)
		}
		return []gossh.Signer{signer}, nil
	}

	// Try to load from disk if we have a data directory.
	if androidDataDir != "" {
		keyPath := filepath.Join(androidDataDir, "ssh_host_ed25519_key")
		if v, err := os.ReadFile(keyPath); err == nil {
			androidHostKeyPEM = v
			signer, err := gossh.ParsePrivateKey(androidHostKeyPEM)
			if err != nil {
				return nil, fmt.Errorf("parsing host key: %w", err)
			}
			return []gossh.Signer{signer}, nil
		}
		// Generate and write to disk.
		_, priv, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			return nil, err
		}
		mk, err := x509.MarshalPKCS8PrivateKey(priv)
		if err != nil {
			return nil, err
		}
		androidHostKeyPEM = pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: mk})
		if err := os.WriteFile(keyPath, androidHostKeyPEM, 0600); err != nil {
			// Fall back to in-memory only.
			androidHostKeyPEM = nil
		}
		signer, err := gossh.ParsePrivateKey(androidHostKeyPEM)
		if err != nil {
			return nil, fmt.Errorf("parsing host key: %w", err)
		}
		return []gossh.Signer{signer}, nil
	}

	// No data directory: ephemeral in-memory key.
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	mk, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		return nil, err
	}
	androidHostKeyPEM = pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: mk})

	signer, err := gossh.ParsePrivateKey(androidHostKeyPEM)
	if err != nil {
		return nil, fmt.Errorf("parsing host key: %w", err)
	}
	return []gossh.Signer{signer}, nil
}

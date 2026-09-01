// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

//go:build !android || ts_omit_ssh

package tailcat

// SetAppDataDir sets the writable directory for persistent data (SSH
// host keys). On non-Android platforms, this is a no-op.
func SetAppDataDir(dir string) {}

// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

// Command printtags prints the build tag list for native builds of
// cmd/tailcat. It regenerates the checked-in build-tags.txt file and
// the -tags= line in .goreleaser.yaml, both of which a test in
// internal/buildtags keeps in sync.
//
// With the -android flag, it prints the Android (gomobile) build tags
// instead, for use in the Makefile's android-aar target.
package main

import (
	"flag"
	"fmt"

	"github.com/tailscale/tailcat/internal/buildtags"
)

func main() {
	android := flag.Bool("android", false, "print Android (gomobile) build tags instead of release tags")
	flag.Parse()
	if *android {
		fmt.Println(buildtags.AndroidTags())
		return
	}
	fmt.Println(buildtags.ReleaseTags())
}

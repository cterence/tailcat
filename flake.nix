{
  description = "tailcat: netcat over Tailscale's data plane, without its control plane";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        # The Android SDK packages are unfree; allow them in this flake.
        pkgsAllowUnfree = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };
        # flakehashes.json is maintained by `make tidy`
        # (tool/updateflakes); do not edit it by hand.
        flakeHashes = builtins.fromJSON (builtins.readFile ./flakehashes.json);
        # go.mod requires Go 1.27.1 (via tailscale.com), but as of
        # 2026-09 nixpkgs-unstable ships go_1_27 = 1.27.0, so build
        # 1.27.1 from source. Remove this override and go back to
        # plain pkgs.go_1_27 once the nixpkgs bump
        # (NixOS/nixpkgs#559618) reaches nixpkgs-unstable.
        go = pkgs.go_1_27.overrideAttrs (old: {
          version = "1.27.1";
          src = pkgs.fetchurl {
            url = "https://go.dev/dl/go1.27.1.src.tar.gz";
            hash = "sha256-TkCKuuEm2Ra2FkYnGT8sVPDjyhMS1pO4bbRfhiqyOLE=";
          };
        });
        buildGoModule = pkgs.buildGoModule.override { inherit go; };
        androidSdkPkgs = pkgsAllowUnfree.androidenv.composeAndroidPackages {
          platformToolsVersion = "37.0.1";
          buildToolsVersions = [ "34.0.0" "35.0.0" ];
          includeNDK = true;
          ndkVersions = [ "27.0.12077973" ];
          includeEmulator = false;
          platformVersions = [ "34" "35" ];
          includeSources = false;
          includeSystemImages = false;
        };
        androidSdk = androidSdkPkgs.androidsdk;
        androidSdkPath = "${androidSdk}/libexec/android-sdk";
      in
      {
        packages.default = buildGoModule {
          pname = "tailcat";
          version = self.shortRev or "dev";
          src = self;
          subPackages = [ "cmd/tailcat" ];
          vendorHash = flakeHashes.vendor.sri;
          meta = {
            description = "netcat over Tailscale's data plane, without its control plane";
            homepage = "https://github.com/tailscale/tailcat";
            license = pkgs.lib.licenses.bsd3;
            mainProgram = "tailcat";
          };
        };

        devShells.default = pkgs.mkShell {
          packages = [ go ];
        };

        # Android development shell: Go, gomobile, Kotlin, Gradle, JDK, and the
        # Android SDK with NDK for cross-compiling the Go bridge via gomobile
        # and building the APK via Gradle.
        # The SDK is unfree and requires license acceptance, so enter with:
        #   NIXPKGS_ALLOW_UNFREE=1 NIXPKGS_ACCEPT_ANDROID_SDK_LICENSE=1 nix develop --impure .#android
        devShells.android = pkgs.mkShell {
          packages = [
            go
            pkgs.kotlin
            pkgs.gradle
            pkgs.gomobile
            pkgs.jdk17
          ];
          ANDROID_HOME = androidSdkPath;
          ANDROID_SDK_ROOT = androidSdkPath;
          ANDROID_NDK_HOME = "${androidSdkPath}/ndk-bundle";
          # Gradle needs to find aapt2 from the Nix SDK rather than
          # downloading its own copy from Maven.
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdkPath}/build-tools/35.0.0/aapt2";
        };
      });
}
# nix-direnv cache busting line: sha256-U3PbzVcFM3Ce41QR4lU6C1azz4c9JH0h9xoFazjVXEE=

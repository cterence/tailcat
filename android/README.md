# tailcat Android Client

A native Android app (Kotlin + Jetpack Compose) that uses the tailcat Go
library via a [gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile)
bridge. The phone can scan a `tc...` connection token via camera QR code,
send and receive files via SFTP, and share its receive token with peers.

## Architecture

```
android/
├── bridge/             Go bridge package (gomobile bind target)
│   ├── bridge.go       Server, Client, Conn, SFTPClient types
│   └── bridge_test.go  In-process round-trip tests
├── app/                Android app module (Kotlin/Compose)
│   ├── build.gradle.kts
│   ├── libs/tailcat.aar (built by gomobile bind)
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/tailcat/android/
│           ├── MainActivity.kt          Tab navigation + lifecycle
│           ├── TailcatBridge.kt         Kotlin wrappers for gomobile bindings
│           ├── ocr/TokenScanner.kt      ML Kit QR code scanning
│           └── ui/
│               ├── theme/Theme.kt       Material 3 theme
│               └── screens/
│                   ├── ScanScreen.kt    Camera + QR token scanning
│                   ├── SendScreen.kt    SFTP browse/download + file upload
│                   └── ReceiveScreen.kt Start SFTP server, copy/share token
├── Makefile            gomobile bind + gradle build targets
└── settings.gradle.kts
```

## Development

### Prerequisites

Enter the Nix Android dev shell (requires unfree SDK license):

```bash
NIXPKGS_ALLOW_UNFREE=1 NIXPKGS_ACCEPT_ANDROID_SDK_LICENSE=1 nix develop --impure .#android
```

This provides: Go 1.27, gomobile, Kotlin, Gradle, JDK 17, Android SDK 35
with NDK. `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and `ANDROID_NDK_HOME` are
set automatically. You also need `GOPATH`/`GOCACHE`/`GOMODCACHE` pointing
at writable directories (the Nix store is read-only):

```bash
export GOPATH=/tmp/tailcat-gopath
export GOCACHE=/tmp/tailcat-gocache
export GOMODCACHE=$GOPATH/pkg/mod
export PATH="$GOPATH/bin:$PATH"
```

### One-shot build (AAR + APK)

From the repo root, inside the Nix dev shell:

```bash
# 1. Build the Go AAR
gomobile bind -tags "$(go run ./internal/buildtags/printtags -android)" \
  -androidapi 26 -o android/app/libs/tailcat.aar -target=android/arm64 \
  ./android/bridge

# 2. Build the APK
cd android
./gradlew assembleDebug --no-daemon
```

The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.

### Deploy to a device via ADB (wireless debugging)

```bash
# Pair the device (one-time): Settings > Developer options > Wireless debugging > Pair
adb pair <device-ip>:<pairing-port> <pairing-code>

# Connect
adb connect <device-ip>:<connection-port>

# Install and launch
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.tailcat.android
adb shell am start -n com.tailcat.android/.MainActivity
```

### View Go logs from the app

```bash
adb logcat -d | grep "GoLog.*tailcat"
```

### Quick rebuild + redeploy (Kotlin only, no Go changes)

```bash
cd android && ./gradlew assembleDebug --no-daemon && \
adb install -r app/build/outputs/apk/debug/app-debug.apk && \
adb shell am force-stop com.tailcat.android && \
adb shell am start -n com.tailcat.android/.MainActivity
```

### Quick rebuild + redeploy (Go changes included)

```bash
# From repo root, in the Nix dev shell:
gomobile bind -tags "$(go run ./internal/buildtags/printtags -android)" \
  -androidapi 26 -o android/app/libs/tailcat.aar -target=android/arm64 \
  ./android/bridge && \
cd android && ./gradlew assembleDebug --no-daemon && \
adb install -r app/build/outputs/apk/debug/app-debug.apk && \
adb shell am force-stop com.tailcat.android && \
adb shell am start -n com.tailcat.android/.MainActivity
```

### Go bridge unit tests

```bash
go test ./android/bridge/ -v
```

## Usage

### Receive files (phone as SFTP server)

1. Open the Receive tab, tap "Start listening"
2. The phone starts an SFTP server serving `~/Downloads/` on port 22
3. Copy or share the displayed `tc...` token
4. On the desktop: `tailcat cp file <token>:` or `tailcat ls <token>:`

### Browse and download remote files (phone as SFTP client)

1. On the desktop: `tailcat serve files:rw` (or `tailcat recv .` for write-only)
2. On the phone: open the Send tab, enter the desktop's token
3. Tap "List files" to browse the remote directory
4. Tap "Download" next to any file to save it to `~/Downloads/`

### Send a file to a desktop drop box

1. On the desktop: `tailcat recv .`
2. On the phone: open the Send tab, enter the desktop's token
3. Tap "Choose file", select a file, tap "Send file"
4. The file appears in the desktop's directory with its original name

### Generate a QR code on the desktop for phone scanning

```bash
tailcat --json 2>/dev/null > /tmp/tc.json & sleep 3 && \
jq -r .listenAddr /tmp/tc.json | nix run nixpkgs#qrencode -- -t ANSIUTF8
```

Point the phone's Scan tab at the QR code.

## Protocol

Both directions use SFTP over SSH (port 22) through the WireGuard tunnel
relayed via DERP. The SSH server on Android is a pure-Go implementation
using gliderssh + pkg/sftp (no shell/exec, SFTP subsystem only).

Raw TCP streams are also available as a secondary transfer mode on
non-port-22 connections.

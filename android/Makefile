# tailcat Android Makefile
#
# Prerequisites (nix shell):
#   nix develop .#android
#
# Or manually: Go 1.27, gomobile, Android SDK + NDK, ANDROID_HOME set.

ANDROID_TAGS := $(shell go run ../../internal/buildtags/printtags -android)
AAR := app/libs/tailcat.aar

.PHONY: all aar apk clean

all: aar

aar: $(AAR)

$(AAR): ../../android/bridge/bridge.go ../../android/bridge/bridge_test.go
	mkdir -p app/libs
	GOOS=android GOARCH=arm64 gomobile bind \
		-tags '$(ANDROID_TAGS)' \
		-androidapi 26 \
		-o $(AAR) \
		-target=android/arm64 \
		../../android/bridge

apk: aar
	cd app && ./gradlew assembleDebug

clean:
	rm -f $(AAR)
	rm -rf app/build

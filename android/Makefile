# tailcat Android Makefile
#
# Prerequisites (nix shell):
#   nix develop .#android
#
# Or manually: Go 1.27, gomobile, Android SDK + NDK, ANDROID_HOME set.

ANDROID_TAGS := $(shell go run ../internal/buildtags/printtags -android)
AAR := app/libs/tailcat.aar
APK := app/build/outputs/apk/debug/app-debug.apk
PKG := com.tailcat.android

# Target a specific device when multiple are connected:
#   make install SERIAL=<serial>
# or set the ANDROID_SERIAL environment variable.
# Without SERIAL, install auto-selects if exactly one device is found;
# if multiple are found it lists them and exits.
ADB := adb $(if $(SERIAL),-s $(SERIAL))

# Shell snippet: count real device lines from "adb devices" (skip header + blanks).
ADB_DEVICES := $(shell adb devices 2>/dev/null | sed -n '2,/$$=/p' | awk 'NF && $$2=="device" {print $$1}')
ADB_COUNT := $(words $(ADB_DEVICES))

.PHONY: all aar apk install devices clean

all: aar

aar: $(AAR)

devices:
	@adb devices

$(AAR): ../android/bridge/bridge.go ../android/bridge/bridge_test.go
	mkdir -p app/libs
	GOOS=android GOARCH=arm64 gomobile bind \
		-tags '$(ANDROID_TAGS)' \
		-androidapi 26 \
		-o $(AAR) \
		-target=android/arm64 \
		../android/bridge

apk: aar
	./gradlew assembleDebug

install: apk
ifeq ($(SERIAL),)
ifeq ($(ADB_COUNT),0)
	@echo "No devices found. Connect a device and try again." >&2
	@exit 1
else ifeq ($(ADB_COUNT),1)
	@echo "Installing to $(ADB_DEVICES)..."
	adb -s $(ADB_DEVICES) install -r $(APK)
	adb -s $(ADB_DEVICES) shell appops set $(PKG) MANAGE_EXTERNAL_STORAGE allow
	adb -s $(ADB_DEVICES) shell am force-stop $(PKG)
	adb -s $(ADB_DEVICES) shell am start -n $(PKG)/.MainActivity
else
	@echo "Multiple devices found:" >&2
	@adb devices 2>/dev/null | sed -n '2,/$$=/p' | awk 'NF {print "  "$$0}' >&2
	@echo "Specify one with: make install SERIAL=<serial>" >&2
	@exit 1
endif
else
	@echo "Installing to $(SERIAL)..."
	$(ADB) install -r $(APK)
	$(ADB) shell appops set $(PKG) MANAGE_EXTERNAL_STORAGE allow
	$(ADB) shell am force-stop $(PKG)
	$(ADB) shell am start -n $(PKG)/.MainActivity
endif

clean:
	rm -f $(AAR)
	rm -rf app/build

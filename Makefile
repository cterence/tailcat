tidy: ## Run go mod tidy and update nix flake hashes
	go mod tidy
	go run ./tool/updateflakes

.PHONY: tidy

ANDROID_TAGS := $(shell go run ./internal/buildtags/printtags -android)

android-aar: ## Build the Android AAR via gomobile bind
	mkdir -p android/app/libs
	GOOS=android GOARCH=arm64 gomobile bind -tags '$(ANDROID_TAGS)' -androidapi 26 -o android/app/libs/tailcat.aar -target=android/arm64 ./android/bridge

.PHONY: android-aar

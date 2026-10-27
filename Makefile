# Everything builds and runs on this Mac. Nothing here goes to CI.

SDK      ?= $(HOME)/Library/Android/sdk
JAVA_HOME ?= /Applications/Android Studio.app/Contents/jbr/Contents/Home
ADB      := $(SDK)/platform-tools/adb
AVD      ?= tv-gtv-31
APK      := apps/tv/app/build/outputs/apk/debug/app-debug.apk
RELEASE  := apps/tv/app/build/outputs/apk/release/app-release.apk
export JAVA_HOME

.PHONY: tv-build tv-release tv-install tv-emulator tv-run tv-screenshot ios-project ios-build mac-build mac-run mac-install

## Launcher (apps/tv)

tv-build:
	cd apps/tv && ./gradlew -q assembleDebug

# Google TV on Android 12, the same system as the TV.
tv-emulator:
	$(SDK)/emulator/emulator -avd $(AVD) -no-snapshot-save -no-boot-anim

# The TV gets the release build: R8-optimised and not debuggable, so Compose runs a good deal smoother on its
# small CPU. Signed with the debug key, so it installs over a debug build and back.
tv-release:
	cd apps/tv && ./gradlew -q assembleRelease

tv-install: tv-release
	tools/tv install -r $(RELEASE)
	ssh ginebra adb kill-server

tv-run: tv-build
	$(ADB) -e install -r $(APK)
	$(ADB) -e shell am start -n dev.cardozo.hollywood.launcher/.MainActivity

tv-screenshot:
	$(ADB) -e exec-out screencap -p > /tmp/tv-launcher.png && open /tmp/tv-launcher.png

## iPhone remote (apps/ios)

ios-project:
	cd apps/ios && xcodegen generate

ios-build: ios-project
	cd apps/ios && xcodebuild -project HollywoodRemote.xcodeproj -scheme HollywoodRemote \
		-destination 'generic/platform=iOS Simulator' -quiet build

## Mac menu-bar remote (apps/ios/HollywoodMenu, same XcodeGen project)

# Built under apps/ios/build (gitignored), not /tmp: macOS empties /tmp on restart, and the app (and its
# login item) went with it.
MAC_DERIVED := apps/ios/build/mac
MAC_APP := $(MAC_DERIVED)/Build/Products/Debug/HollywoodMenu.app
MAC_RELEASE := $(MAC_DERIVED)/Build/Products/Release/HollywoodMenu.app

mac-build: ios-project
	cd apps/ios && xcodebuild -project HollywoodRemote.xcodeproj -scheme HollywoodMenu \
		-destination 'platform=macOS' -derivedDataPath $(MAC_DERIVED) -allowProvisioningUpdates -quiet build

mac-run: mac-build
	pkill -x HollywoodMenu || true
	open $(MAC_APP)

# The Release build, installed as /Applications/Hollywood.app and opened. Its login item (Abrir al iniciar
# sesión, in the panel's ⋯ menu) points at this copy.
mac-install: ios-project
	cd apps/ios && xcodebuild -project HollywoodRemote.xcodeproj -scheme HollywoodMenu -configuration Release \
		-destination 'platform=macOS' -derivedDataPath build/mac -allowProvisioningUpdates -quiet build
	pkill -x HollywoodMenu || true
	rm -rf /Applications/Hollywood.app
	ditto $(MAC_RELEASE) /Applications/Hollywood.app
	open /Applications/Hollywood.app

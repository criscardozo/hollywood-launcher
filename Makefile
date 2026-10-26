# Everything builds and runs on this Mac. Nothing here goes to CI.

SDK      ?= $(HOME)/Library/Android/sdk
JAVA_HOME ?= /Applications/Android Studio.app/Contents/jbr/Contents/Home
ADB      := $(SDK)/platform-tools/adb
AVD      ?= tv-gtv-31
APK      := apps/tv/app/build/outputs/apk/debug/app-debug.apk
RELEASE  := apps/tv/app/build/outputs/apk/release/app-release.apk
export JAVA_HOME

.PHONY: tv-build tv-release tv-install tv-emulator tv-run tv-screenshot ios-project ios-build mac-build mac-run

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

MAC_APP := /tmp/hollywood-menu/Build/Products/Debug/HollywoodMenu.app

mac-build: ios-project
	cd apps/ios && xcodebuild -project HollywoodRemote.xcodeproj -scheme HollywoodMenu \
		-destination 'platform=macOS' -derivedDataPath /tmp/hollywood-menu -allowProvisioningUpdates -quiet build

mac-run: mac-build
	pkill -x HollywoodMenu || true
	open $(MAC_APP)

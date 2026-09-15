#!/usr/bin/env bash
set -euo pipefail
set -x

chmod +x gradlew
./gradlew --stacktrace :app:assembleAutomotiveDebug :app:assembleAutomotiveRelease

DEBUG_APK="app/build/outputs/apk/automotive/debug/app-automotive-debug.apk"
RELEASE_UNSIGNED="app/build/outputs/apk/automotive/release/app-automotive-release-unsigned.apk"
RELEASE_TEST_APK="/tmp/app-automotive-release-test.apk"

test -f "$DEBUG_APK"
test -f "$RELEASE_UNSIGNED"

# Exercise the same wide/landscape resource qualifiers as the Renault 5 display. The vehicle
# panel is 1280x720 and AnExplorer receives roughly 1168x580 after OEM chrome. A 160 dpi test
# configuration deliberately selects the sw600dp-land layouts that a phone-sized smoke test
# never touched.
adb shell wm size 1280x720
adb shell wm density 160
sleep 2
adb shell wm size
adb shell wm density
adb shell dumpsys activity | grep -m 1 'mConfiguration' || true

# Sign the release APK with the standard debug key for CI runtime testing only.
# Production/release signing remains completely separate.
mkdir -p "$HOME/.android"
if [ ! -f "$HOME/.android/debug.keystore" ]; then
  keytool -genkeypair -v \
    -keystore "$HOME/.android/debug.keystore" \
    -storepass android \
    -alias androiddebugkey \
    -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000
fi

BUILD_TOOLS="$(ls -1 "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS/apksigner"
"$APKSIGNER" sign \
  --ks "$HOME/.android/debug.keystore" \
  --ks-pass pass:android \
  --ks-key-alias androiddebugkey \
  --key-pass pass:android \
  --out "$RELEASE_TEST_APK" \
  "$RELEASE_UNSIGNED"

PACKAGE="com.ast.anexplorer"
ACTIVITY="com.ast.anexplorer/dev.dworks.apps.anexplorer.AutomotiveDocumentsActivity"

log_state() {
  local label="$1"
  echo "=== $label ==="
  adb shell pidof "$PACKAGE" || true
  adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|com.ast.anexplorer|CarUx|Blocking' | head -140 || true
  adb logcat -d -v threadtime | grep -E 'AndroidRuntime|FATAL EXCEPTION|com\.ast\.anexplorer|dev\.dworks\.apps\.anexplorer|InflateException|SecurityException|ActivityNotFoundException|NoClassDefFoundError|NoSuchMethodError|VerifyError|SQLite|CarPackage|CarUx|ActivityTaskManager' | tail -700 || true
}

assert_running() {
  local label="$1"
  if ! adb shell pidof "$PACKAGE" >/dev/null; then
    echo "AnExplorer process died during $label." >&2
    adb logcat -d -v threadtime | tail -1600
    exit 1
  fi
  if adb logcat -d -v brief | grep -q 'FATAL EXCEPTION'; then
    echo "Fatal exception detected during $label." >&2
    adb logcat -d -v threadtime | tail -1600
    exit 1
  fi
}

launch_from_launcher() {
  adb shell am force-stop "$PACKAGE"
  adb logcat -c
  adb shell am start -W \
    -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER \
    -p "$PACKAGE" || true
}

run_with_storage_access() {
  local apk="$1"
  local label="$2"
  adb install -r "$apk"
  adb shell appops set "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow || true
  adb shell pm grant "$PACKAGE" android.permission.READ_EXTERNAL_STORAGE || true
  launch_from_launcher
  sleep 8
  log_state "$label WITH STORAGE ACCESS"
  assert_running "$label with storage access"
}

# Test the same launcher path that AAOS uses, first in debug then in release mode.
run_with_storage_access "$DEBUG_APK" "AUTOMOTIVE DEBUG"
adb uninstall "$PACKAGE" || true
run_with_storage_access "$RELEASE_TEST_APK" "AUTOMOTIVE RELEASE"

# Exercise the fresh-install path without All Files Access. AAOS must keep the app alive and
# must not automatically jump to an OEM Settings screen during the first frame.
adb shell am force-stop "$PACKAGE"
adb shell appops set "$PACKAGE" MANAGE_EXTERNAL_STORAGE default || true
launch_from_launcher
sleep 5
log_state "AUTOMOTIVE RELEASE WITHOUT ALL-FILES ACCESS"
assert_running "release first-run without all-files access"

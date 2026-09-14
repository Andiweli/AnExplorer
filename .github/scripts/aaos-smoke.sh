#!/usr/bin/env bash
set -euo pipefail
set -x

chmod +x gradlew
./gradlew --stacktrace :app:assembleAutomotiveDebug

APK="app/build/outputs/apk/automotive/debug/app-automotive-debug.apk"
test -f "$APK"
adb install -r "$APK"

ACTIVITY="com.ast.anexplorer/dev.dworks.apps.anexplorer.AutomotiveDocumentsActivity"

# Grant broad storage access for the first core-launch test so the legacy permission
# flow cannot hide an unrelated startup crash. A separate no-grant pass follows.
adb shell appops set com.ast.anexplorer MANAGE_EXTERNAL_STORAGE allow || true
adb shell pm grant com.ast.anexplorer android.permission.READ_EXTERNAL_STORAGE || true

adb logcat -c
adb shell am force-stop com.ast.anexplorer
adb shell am start -W -n "$ACTIVITY" || true
sleep 8

echo '=== CORE LAUNCH WITH STORAGE ACCESS ==='
adb shell pidof com.ast.anexplorer || true
adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|com.ast.anexplorer' | head -80 || true
adb logcat -d -v threadtime | grep -E 'AndroidRuntime|FATAL EXCEPTION|com\.ast\.anexplorer|dev\.dworks\.apps\.anexplorer|InflateException|SecurityException|NoClassDefFoundError|NoSuchMethodError|VerifyError|SQLite' | tail -400 || true

if ! adb shell pidof com.ast.anexplorer >/dev/null; then
  echo 'AnExplorer process died during core launch.' >&2
  adb logcat -d -v threadtime | tail -1200
  exit 1
fi

# Now revoke all-files access and launch from a clean task to exercise the first-run
# permission path used by a freshly installed AAOS build.
adb shell am force-stop com.ast.anexplorer
adb shell appops set com.ast.anexplorer MANAGE_EXTERNAL_STORAGE default || true
adb logcat -c
adb shell am start -W -n "$ACTIVITY" || true
sleep 5

echo '=== FIRST-RUN LAUNCH WITHOUT ALL-FILES ACCESS ==='
adb shell pidof com.ast.anexplorer || true
adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|com.ast.anexplorer|Settings' | head -120 || true
adb logcat -d -v threadtime | grep -E 'AndroidRuntime|FATAL EXCEPTION|com\.ast\.anexplorer|dev\.dworks\.apps\.anexplorer|InflateException|SecurityException|ActivityNotFoundException|NoClassDefFoundError|NoSuchMethodError|VerifyError|SQLite' | tail -500 || true

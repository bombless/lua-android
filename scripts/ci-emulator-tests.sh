#!/usr/bin/env bash
# Runs inside the emulator action: installs the host app and runs the cross-app acceptance tests
# (instrumentation of the example client, a different UID that talks to the host over Binder).
set -uo pipefail
: > emulator-diag.txt
note() { echo "== $* ==" | tee -a emulator-diag.txt; }

adb logcat -c || true
./gradlew --no-daemon --stacktrace --console=plain :app:installDebug :client-example:assembleDebug 2>&1 | tee gradle-emulator.log
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ]; then adb logcat -d -v time > logcat.txt || true; exit "$status"; fi

note "APK checks"
scripts/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk debug 2>&1 | tee -a emulator-diag.txt
note "installed packages"
adb shell pm list packages | grep -i luaandroid 2>&1 | tee -a emulator-diag.txt
note "host package: providers and services as the package manager sees them"
adb shell dumpsys package com.bombless.luaandroid | grep -iE "Provider|authority|testcontrol|LuaRuntimeService|extractNativeLibs|legacyNativeLibraryDir|nativeLibraryRootDir|primaryCpuAbi" | head -30 2>&1 | tee -a emulator-diag.txt
note "provider reachable from the shell user?"
adb shell content call --uri content://com.bombless.luaandroid.testcontrol --method max_concurrency 2>&1 | tee -a emulator-diag.txt
note "example client manifest (queries)"
"${ANDROID_HOME:-/usr/local/lib/android/sdk}"/build-tools/*/aapt2 dump xmltree --file AndroidManifest.xml client-example/build/outputs/apk/debug/client-example-debug.apk 2>&1 | grep -iE "queries|package|uses-permission|name=" | head -20 | tee -a emulator-diag.txt

adb logcat -c || true
# The two instrumentation runs must not overlap: connected*AndroidTest installs and afterwards UNINSTALLS the app
# under test, and the cross-app tests need the host app to stay installed. Hence two sequential Gradle runs.
# 1) cross-app acceptance tests: the example client is the test target, the host app is installed beforehand.
./gradlew --no-daemon --stacktrace --console=plain --no-parallel :client-example:connectedDebugAndroidTest 2>&1 | tee -a gradle-emulator.log
status_client=${PIPESTATUS[0]}
adb logcat -d -v time > logcat.txt || true
# 2) owner UI smoke test: the host app itself is the test target.
adb logcat -c || true
./gradlew --no-daemon --stacktrace --console=plain --no-parallel :app:connectedDebugAndroidTest 2>&1 | tee -a gradle-emulator.log
status_ui=${PIPESTATUS[0]}
adb logcat -d -v time >> logcat.txt || true
# screenshots left by the UI smoke test (adb pull works on the shared storage even after the app was uninstalled)
mkdir -p screenshots
for f in $(adb shell ls /sdcard/Download 2>/dev/null | tr -d '\r' | grep -E '^lua-.*\.png$'); do adb pull "/sdcard/Download/$f" screenshots/ > /dev/null 2>&1 || true; done
ls -la screenshots | tee -a emulator-diag.txt
echo "client acceptance tests: exit $status_client; owner UI smoke test: exit $status_ui" | tee -a emulator-diag.txt
[ "$status_client" -eq 0 ] && [ "$status_ui" -eq 0 ]

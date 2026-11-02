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
./gradlew --no-daemon --stacktrace --console=plain :client-example:connectedDebugAndroidTest 2>&1 | tee -a gradle-emulator.log
status=${PIPESTATUS[0]}
adb logcat -d -v time > logcat.txt || true
exit "$status"

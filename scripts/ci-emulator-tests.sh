#!/usr/bin/env bash
# Runs inside the emulator action: installs the host app and runs the cross-app acceptance tests
# (instrumentation of the example client, a different UID that talks to the host over Binder).
set -uo pipefail
adb logcat -c || true
./gradlew --no-daemon --stacktrace --console=plain :app:installDebug :client-example:connectedDebugAndroidTest 2>&1 | tee gradle-emulator.log
status=${PIPESTATUS[0]}
adb logcat -d -v time > logcat.txt || true
exit "$status"

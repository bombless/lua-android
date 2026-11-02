#!/usr/bin/env bash
# Prepares the Android SDK on a GitHub-hosted runner: platform, build-tools and NDK, licences accepted.
# The runner image ships the SDK (ANDROID_HOME) but sdkmanager is not necessarily on PATH.
set -euo pipefail
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}
ndk=${ANDROID_NDK_VERSION:-27.2.12479018}
echo "Android SDK: $sdk"
ls "$sdk" || true
sm=$(ls "$sdk"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | sort -V | tail -1 || true)
[ -n "$sm" ] || sm=$(command -v sdkmanager || true)
if [ -z "$sm" ]; then
  echo "sdkmanager not found; SDK layout:"; ls -la "$sdk" "$sdk/cmdline-tools" 2>&1 || true
  exit 1
fi
echo "sdkmanager: $sm"
yes | "$sm" --sdk_root="$sdk" --licenses > /dev/null || true
"$sm" --sdk_root="$sdk" "platforms;android-35" "build-tools;35.0.0" "ndk;$ndk"
echo "ANDROID_HOME=$sdk" >> "$GITHUB_ENV"
echo "ORG_GRADLE_PROJECT_ndkDir=$sdk/ndk/$ndk" >> "$GITHUB_ENV"
ls "$sdk/ndk" "$sdk/platforms" "$sdk/build-tools"

#!/bin/sh
# Assemble the Lua runtime directory exactly as the app installs it on device
# (files/lua/runtime). Used by the local/CI runner tests; the Gradle build performs
# the same copy into the APK assets (see app/build.gradle.kts, task stageRuntimeAssets).
#   usage: scripts/stage-runtime.sh <output-dir>
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
out=${1:?usage: stage-runtime.sh <output-dir>}
rm -rf "$out"
mkdir -p "$out/shims"
cp "$here"/runner/lua/*.lua "$out/"
cp -r "$here"/runner/lua/shims/. "$out/shims/"
cp "$here/third_party/luasocket/ltn12.lua" "$out/shims/ltn12.lua"
cp -r "$here/third_party/luarocks/luarocks" "$out/luarocks"

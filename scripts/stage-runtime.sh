#!/bin/sh
# Assemble the Lua runtime exactly as the app installs it on device:
#   <out>/runtime   (bootstrap.lua, rocks_main.lua, shims/)     -> files/lua/runtime
#   <out>/luarocks  (LuaRocks sources, package "luarocks")      -> files/lua/luarocks
# Used by the runner tests; the Gradle build performs the same copy into the APK assets
# (app/build.gradle.kts, task stageRuntimeAssets).
#   usage: scripts/stage-runtime.sh <output-dir>
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
out=${1:?usage: stage-runtime.sh <output-dir>}
rm -rf "$out"
mkdir -p "$out/runtime/shims"
cp "$here"/runner/lua/*.lua "$out/runtime/"
cp -r "$here"/runner/lua/shims/. "$out/runtime/shims/"
cp "$here/third_party/luasocket/ltn12.lua" "$out/runtime/shims/ltn12.lua"
cp -r "$here/third_party/luarocks/luarocks" "$out/luarocks"

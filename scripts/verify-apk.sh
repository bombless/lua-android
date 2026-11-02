#!/usr/bin/env bash
# Sanity checks on a built host APK.   usage: verify-apk.sh <apk> [debug|release]
set -euo pipefail
apk=${1:?usage: verify-apk.sh <apk> [debug|release]}
variant=${2:-release}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
fail() { echo "verify-apk: FAIL: $*" >&2; exit 1; }
ok()   { echo "verify-apk: ok: $*"; }

listing=$(unzip -l "$apk")
for abi in arm64-v8a armeabi-v7a x86_64; do
  echo "$listing" | grep -q "lib/$abi/libluart.so" || fail "lib/$abi/libluart.so is missing (the native runner was not packaged)"
done
ok "runner packaged for arm64-v8a, armeabi-v7a, x86_64"
for f in assets/lua/MANIFEST assets/lua/runtime/bootstrap.lua assets/lua/runtime/rocks_main.lua assets/lua/runtime/shims/ltn12.lua \
         assets/lua/runtime/shims/socket/http.lua assets/lua/luarocks/cmd.lua assets/lua/luarocks/build/builtin.lua; do
  echo "$listing" | grep -q "$f" || fail "$f is missing from the assets"
done
ok "Lua runtime and LuaRocks assets present"

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$apk" 'lib/*/libluart.so' -d "$tmp"
declare -A machine=( [arm64-v8a]="ARM aarch64" [armeabi-v7a]="ARM," [x86_64]="x86-64" )
for abi in "${!machine[@]}"; do
  desc=$(file -b "$tmp/lib/$abi/libluart.so")
  echo "$desc" | grep -q "ELF" || fail "$abi runner is not an ELF file: $desc"
  echo "$desc" | grep -q "${machine[$abi]}" || fail "$abi runner has the wrong architecture: $desc"
  echo "$desc" | grep -qE "pie executable|shared object" || fail "$abi runner is not position independent: $desc"
  case "$abi" in arm64-v8a|x86_64)
    align=$(readelf -lW "$tmp/lib/$abi/libluart.so" | awk '/LOAD/ {print $NF}' | sort -u | tr '\n' ' ')
    echo "$align" | grep -q "0x4000" || fail "$abi runner is not 16 KB aligned ($align)" ;;
  esac
done
ok "runner binaries are PIE executables of the right architecture (64-bit ones 16 KB aligned)"

manifest=$("$bt/aapt2" dump xmltree --file AndroidManifest.xml "$apk")
echo "$manifest" | grep -q "extractNativeLibs.*=(type 0x12)0xffffffff" || fail "extractNativeLibs is not true: the runner would not be extracted to nativeLibraryDir"
echo "$manifest" | grep -q "com.bombless.luaandroid.permission.USE_LUA_RUNTIME" || fail "service permission missing"
echo "$manifest" | grep -q "com.bombless.luaandroid.action.BIND_RUNTIME" || fail "BIND_RUNTIME action missing"
echo "$manifest" | grep -q "com.bombless.luaandroid.results" || fail "result provider missing"
if [ "$variant" = release ]; then
  echo "$manifest" | grep -q "testcontrol" && fail "the debug-only test control provider leaked into a release build"
  ok "release manifest has no test hooks"
else
  echo "$manifest" | grep -q "com.bombless.luaandroid.testcontrol" || fail "debug build lacks the test control provider"
fi
ok "manifest declares the service, the permission and the provider"

"$bt/apksigner" verify --verbose "$apk" | head -6
ok "signature verifies"
echo "verify-apk: all checks passed for $apk ($variant)"

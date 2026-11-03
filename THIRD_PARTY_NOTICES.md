# Third-party notices

Everything below is vendored **unmodified** in `third_party/` (copy of the upstream sources at the given version) and is
distributed under the MIT license; the license texts are next to the sources.

| Component | Version | Source | License file |
|---|---|---|---|
| Lua | 5.4.9 | https://github.com/lua/lua (tag v5.4.9) — core library sources only (`lua.c`, `luac.c` omitted) | `third_party/lua/LICENSE` |
| LuaRocks | 3.13.0 | https://github.com/luarocks/luarocks (tag v3.13.0) — Lua sources of `src/luarocks` | `third_party/luarocks/COPYING` |
| &nbsp;&nbsp;dkjson (bundled in LuaRocks) | 2.7 | by David Kolf, in `third_party/luarocks/luarocks/vendor/dkjson.lua` | MIT, header of the file |
| &nbsp;&nbsp;argparse (bundled in LuaRocks) | — | by Peter Melnichenko, in `third_party/luarocks/luarocks/vendor/argparse.lua` | MIT, header of the file |
| LuaFileSystem | 1.9.0 | https://github.com/lunarmodules/luafilesystem (`src/lfs.c`, `src/lfs.h`) | `third_party/lfs/LICENSE` |
| lua-zlib | 1.4 | https://github.com/brimworks/lua-zlib (`lua_zlib.c`) | `third_party/lua-zlib/LICENSE` |
| LuaSocket (`ltn12.lua` only) | 3.1.0 | https://github.com/lunarmodules/luasocket | `third_party/luasocket/LICENSE` |

zlib is not bundled: the runner links the zlib shipped with Android (`libz.so`, part of the NDK's stable API).

The code in `runner/`, `ipc-api/`, `app/` and `client-example/` is original to this repository.

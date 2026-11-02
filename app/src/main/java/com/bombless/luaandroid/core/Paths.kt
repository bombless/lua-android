package com.bombless.luaandroid.core

import android.content.Context
import java.io.File

/** Everything lives in app-private storage (Manual.md section 5.1). */
class Paths(context: Context) {
    val root = File(context.filesDir, "lua")
    val runtime = File(root, "runtime")      // bootstrap.lua, rocks_main.lua, shims
    val luarocks = File(root, "luarocks")    // LuaRocks Lua sources (package "luarocks")
    val rocks = File(root, "rocks")          // the one LuaRocks tree
    val jobs = File(root, "jobs")
    val etc = File(root, "etc")              // generated LuaRocks configuration
    val home = File(root, "home")
    val tmp = File(root, "tmp")              // download scratch space for LuaRocks
    val access = File(root, "access.json")
    val runtimeMarker = File(root, ".runtime-version")

    /** The Lua runner is packaged as libluart.so so Android extracts it to the one exec-able directory. */
    val runner = File(context.applicationInfo.nativeLibraryDir, "libluart.so")

    fun ensureDirs() {
        for (d in listOf(root, jobs, rocks, etc, home, tmp)) d.mkdirs()
    }
}

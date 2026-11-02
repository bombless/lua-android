package com.bombless.luaandroid.ipc

import android.content.Intent

/**
 * Published identifiers of the Lua Android host app. These are part of the stable contract
 * (Manual.md section 8): action, permission, package and AIDL package never change within API v1.
 */
object LuaRuntimeContract {
    const val HOST_PACKAGE = "com.bombless.luaandroid"
    const val ACTION_BIND_RUNTIME = "com.bombless.luaandroid.action.BIND_RUNTIME"
    const val ACTION_BIND_ROCKS = "com.bombless.luaandroid.action.BIND_ROCKS"
    const val PERMISSION_USE = "com.bombless.luaandroid.permission.USE_LUA_RUNTIME"
    const val RESULT_AUTHORITY = "com.bombless.luaandroid.results"

    /** Highest API version this library speaks. */
    const val API_VERSION = 1

    /** Explicit, package-scoped intent for binding the script runtime. */
    @JvmStatic
    fun runtimeIntent(): Intent = Intent(ACTION_BIND_RUNTIME).setPackage(HOST_PACKAGE)

    /** Explicit, package-scoped intent for binding the LuaRocks package service. */
    @JvmStatic
    fun rocksIntent(): Intent = Intent(ACTION_BIND_ROCKS).setPackage(HOST_PACKAGE)
}

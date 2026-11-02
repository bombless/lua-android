package com.bombless.luaandroid.core

import android.content.Context
import java.io.File

/**
 * Installs the Lua side of the runtime (bootstrap, shims, LuaRocks sources) from the APK assets into
 * files/lua/{runtime,luarocks}. The asset manifest carries a content hash, so the files are replaced
 * exactly when the app ships different content (first start, app update).
 */
class RuntimeInstaller(private val context: Context, private val paths: Paths) {
    @Synchronized
    fun ensureInstalled() {
        paths.ensureDirs()
        val manifest = context.assets.open("lua/MANIFEST").bufferedReader().use { it.readLines() }
        val hash = manifest.first().removePrefix("sha256 ").trim()
        if (paths.runtimeMarker.isFile && paths.runtimeMarker.readText().trim() == hash &&
            File(paths.runtime, "bootstrap.lua").isFile && File(paths.luarocks, "cmd.lua").isFile
        ) {
            return
        }
        val staging = File(paths.root, ".staging")
        staging.deleteRecursively()
        for (rel in manifest.drop(1).filter { it.isNotBlank() }) {
            val out = File(staging, rel)
            out.parentFile?.mkdirs()
            context.assets.open("lua/$rel").use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        paths.runtime.deleteRecursively()
        paths.luarocks.deleteRecursively()
        check(File(staging, "runtime").renameTo(paths.runtime)) { "cannot install Lua runtime" }
        check(File(staging, "luarocks").renameTo(paths.luarocks)) { "cannot install LuaRocks" }
        staging.deleteRecursively()
        paths.runtimeMarker.writeText(hash)
    }
}

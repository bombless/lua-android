package com.bombless.luaandroid

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            text = "Lua Android Runtime\n\nIPC API v1 is available to authorized client applications.\n\nLua engine: LuaJ (JVM compatibility runtime). Native Lua/LuaRocks ABI support is not bundled in this bootstrap."
            textSize = 18f
            setPadding(32, 48, 32, 32)
        }
        setContentView(text)
    }
}

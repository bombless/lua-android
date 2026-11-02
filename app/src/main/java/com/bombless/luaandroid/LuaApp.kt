package com.bombless.luaandroid

import android.app.Application

class LuaApp : Application() {
    /** Process-wide singletons. Created lazily: the Application must stay cheap to start. */
    val graph: AppGraph by lazy { AppGraph(this) }
}

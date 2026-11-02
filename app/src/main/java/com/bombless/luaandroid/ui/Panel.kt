package com.bombless.luaandroid.ui

import android.os.Handler
import android.os.Looper
import android.view.View

/** One tab of the main screen. Listeners are attached in onShow() and removed in onHide(). */
abstract class Panel(protected val activity: MainActivity) {
    abstract val view: View
    open fun onShow() {}
    open fun onHide() {}

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRefresh = false

    /** Coalesces bursts of change notifications (they arrive on arbitrary threads) into one UI refresh. */
    protected fun refreshSoon(delayMs: Long = 150, block: () -> Unit) {
        synchronized(this) {
            if (pendingRefresh) return
            pendingRefresh = true
        }
        handler.postDelayed({
            synchronized(this) { pendingRefresh = false }
            block()
        }, delayMs)
    }
}

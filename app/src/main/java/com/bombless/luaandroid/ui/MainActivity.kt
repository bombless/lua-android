package com.bombless.luaandroid.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.bombless.luaandroid.AppGraph
import com.bombless.luaandroid.BuildConfig
import com.bombless.luaandroid.LuaApp
import java.util.concurrent.Executors

/**
 * The owner's UI. It uses the very same JobManager / RockManager as external clients (as the "owner"
 * caller), so what you see here is exactly what other apps get.
 */
class MainActivity : Activity() {
    lateinit var graph: AppGraph
    val background = Executors.newCachedThreadPool { r -> Thread(r, "lua-ui-bg").apply { isDaemon = true } }

    private lateinit var content: FrameLayout
    private lateinit var tabViews: List<TextView>
    private lateinit var panels: List<Panel>
    private lateinit var accessBadge: TextView
    private lateinit var subtitle: TextView
    private var current = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as LuaApp).graph

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = false
        }
        applyInsets(root)

        root.addView(header())
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(tabBar())
        setContentView(root)

        panels = listOf(RunPanel(this), JobsPanel(this), PackagesPanel(this), AccessPanel(this), AboutPanel(this))
        show(initialTab(intent))

        // Install the Lua runtime / recover jobs off the main thread; the Run tab shows its state.
        background.execute {
            try {
                graph.ensureReady()
                runOnUiThread { subtitle.text = subtitleText() }
            } catch (t: Throwable) {
                runOnUiThread { Ui.toast(this, "Runtime setup failed: ${t.message}") }
            }
        }
        graph.accessStore.addListener(accessListener)
        updateBadge()
        maybeAskForNotifications()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(initialTab(intent))
    }

    override fun onDestroy() {
        graph.accessStore.removeListener(accessListener)
        panels.getOrNull(current)?.onHide()
        super.onDestroy()
    }

    private val accessListener: () -> Unit = { runOnUiThread { updateBadge() } }

    private fun initialTab(i: Intent?): Int = i?.getIntExtra(EXTRA_TAB, TAB_RUN) ?: TAB_RUN

    private fun subtitleText(): String {
        val r = try { graph.runtimeInfo.runner } catch (_: Throwable) { null }
        return if (r != null) "${r.optString("lua", "Lua 5.4")} · ${r.optString("abi")} · v${BuildConfig.VERSION_NAME}" else "v${BuildConfig.VERSION_NAME}"
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 10))
        addView(Ui.text(this@MainActivity, "Lua Android", 22f, Ui.TEXT, bold = true))
        subtitle = Ui.text(this@MainActivity, "Lua 5.4 · starting…", 12f, Ui.MUTED, mono = true)
        addView(subtitle)
    }

    private fun tabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF16161E.toInt())
        }
        val names = listOf("Run", "Jobs", "Rocks", "Access", "About")
        tabViews = names.mapIndexed { i, name ->
            Ui.text(this, name, 13f, Ui.MUTED, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(this@MainActivity, 14), 0, Ui.dp(this@MainActivity, 14))
                setOnClickListener { show(i) }
            }
        }
        accessBadge = Ui.text(this, "", 10f, Ui.BG, bold = true).apply {
            background = Ui.rounded(this@MainActivity, Ui.WARN, 8)
            setPadding(Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 6), 0)
            visibility = View.GONE
        }
        tabViews.forEachIndexed { i, tv ->
            if (i == TAB_ACCESS) {
                val frame = FrameLayout(this)
                frame.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                frame.addView(accessBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    topMargin = Ui.dp(this@MainActivity, 6); rightMargin = Ui.dp(this@MainActivity, 8)
                })
                bar.addView(frame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            } else {
                bar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        return bar
    }

    fun show(index: Int) {
        if (index == current || index !in panels.indices) return
        panels.getOrNull(current)?.onHide()
        current = index
        content.removeAllViews()
        content.addView(panels[index].view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabViews.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == index) Ui.ACCENT else Ui.MUTED)
            tv.background = if (i == index) Ui.rounded(this, 0x1F7AA2F7, 0) else null
        }
        panels[index].onShow()
    }

    private fun updateBadge() {
        val n = graph.accessStore.pendingRequests().size
        accessBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
        accessBadge.text = n.toString()
    }

    private fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getSharedPreferences("ui", MODE_PRIVATE)
            if (!prefs.getBoolean("askedNotifications", false)) {
                prefs.edit().putBoolean("askedNotifications", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    /** Edge-to-edge: draw behind the system bars (Android 15 enforces it) and pad content by their insets. */
    @Suppress("DEPRECATION")
    private fun applyInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_RUN = 0
        const val TAB_JOBS = 1
        const val TAB_ROCKS = 2
        const val TAB_ACCESS = 3
        const val TAB_ABOUT = 4
    }
}

package com.bombless.luaandroid.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.bombless.luaandroid.BuildConfig
import org.json.JSONObject

class AboutPanel(activity: MainActivity) : Panel(activity) {
    private val ctx = activity
    private val settings get() = activity.graph.settings
    private val info = Ui.codeBox(ctx)
    private val retention = Ui.text(ctx, "", 14f, Ui.TEXT, mono = true)
    private val concurrency = Ui.text(ctx, "", 14f, Ui.TEXT, mono = true)

    override val view: View = ScrollView(ctx).apply {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 4), Ui.dp(ctx, 16), Ui.dp(ctx, 24))
        }
        col.addView(Ui.heading(ctx, "Trust model"))
        col.addView(Ui.text(ctx,
            "Every job runs in its own process with a fresh Lua 5.4 state, a hard deadline, a memory ceiling, capped output and a private working directory " +
                "(input/ read-only, output/, tmp/). Scripts cannot spawn programs, load native code, open arbitrary paths or reach the network.\n\n" +
                "This is process-level isolation inside this app's Android sandbox, NOT a separate UID: a bug in the Lua VM or in a prebuilt native rock could in principle read this app's data. " +
                "Install only rocks you trust, and approve only apps you trust.",
            13f, Ui.TEXT))
        col.addView(Ui.heading(ctx, "Settings"))
        col.addView(stepper("Keep finished jobs for", retention, { settings.retentionDays -= 1; refresh() }, { settings.retentionDays += 1; refresh() }))
        col.addView(Ui.space(ctx, 6))
        col.addView(stepper("Parallel jobs (after restart)", concurrency, { settings.maxConcurrentJobs -= 1; refresh() }, { settings.maxConcurrentJobs += 1; refresh() }))
        col.addView(Ui.heading(ctx, "Runtime (what getRuntimeInfo() returns to clients)"))
        col.addView(info)
        col.addView(Ui.heading(ctx, "Open source"))
        col.addView(Ui.text(ctx, "Lua ${"5.4.9"} · LuaRocks ${BuildConfig.LUAROCKS_VERSION} · LuaFileSystem 1.9.0 · lua-zlib 1.4 · LuaSocket ltn12 3.1.0 (all MIT). App version ${BuildConfig.VERSION_NAME}.", 12.5f, Ui.MUTED))
        addView(col)
    }

    private fun stepper(label: String, value: android.widget.TextView, minus: () -> Unit, plus: () -> Unit): View =
        Ui.row(ctx).apply {
            addView(Ui.text(ctx, label, 13.5f, Ui.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.button(ctx, "−") { minus() })
            addView(value.apply { setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12), 0) })
            addView(Ui.button(ctx, "+") { plus() })
        }

    private fun refresh() {
        retention.text = "${settings.retentionDays} d"
        concurrency.text = "${settings.maxConcurrentJobs}"
    }

    override fun onShow() {
        refresh()
        info.text = "loading…"
        activity.background.execute {
            val text = try {
                activity.graph.ensureReady()
                JSONObject(activity.graph.runtimeInfo.json()).toString(2)
            } catch (t: Throwable) {
                "Runtime not ready: ${t.message}"
            }
            ctx.runOnUiThread { info.text = text }
        }
    }
}

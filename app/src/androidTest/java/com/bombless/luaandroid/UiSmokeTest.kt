package com.bombless.luaandroid

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bombless.luaandroid.core.Caller
import com.bombless.luaandroid.ipc.JobRequest
import com.bombless.luaandroid.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The owner UI is built in code: this catches crashes (casts, layout params, missing runtime) that no unit test can,
 * and leaves one screenshot per tab in /sdcard/Download (CI uploads them).
 */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = (ctx.applicationContext as LuaApp).graph
    private val owner get() = Caller(Process.myUid(), listOf(ctx.packageName), true)

    @Before
    fun prepare() {
        // The permission dialog would pause the activity and stall ActivityScenario.
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("askedNotifications", true).commit()
        graph.ensureReady()
        graph.jobs.snapshot().forEach { graph.jobs.delete(it.id) }
        seed()
    }

    @After
    fun cleanUp() {
        graph.accessStore.dismissPending("org.example.notes")
        graph.accessStore.revoke("org.example.sheets")
        graph.jobs.snapshot().forEach { graph.jobs.delete(it.id) }
    }

    /** Some realistic content so the screenshots show more than empty states. */
    private fun seed() {
        fun run(source: String, timeoutMillis: Long = 5_000, name: String = "console.lua") {
            val id = graph.jobs.submit(owner, JobRequest().apply { this.source = source; scriptName = name; this.timeoutMillis = timeoutMillis })
            val deadline = System.currentTimeMillis() + 30_000
            while (graph.jobs.recordOrNull(id)?.isTerminal != true && System.currentTimeMillis() < deadline) Thread.sleep(50)
        }
        run("print('hello from the owner') local f = assert(io.open('report.txt', 'w')) f:write('rows=3\\n') f:close() return { ok = true, rows = 3 }", name = "report.lua")
        run("error('something went wrong')", name = "broken.lua")
        run("while true do end", timeoutMillis = 500, name = "spin.lua")
        val now = System.currentTimeMillis()
        graph.accessStore.recordAttempt("org.example.notes", "Notes", setOf("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"), true, now)
        graph.accessStore.allow("org.example.sheets", "Sheets", setOf("2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae"), false, now)
    }

    private fun texts(root: View, out: MutableList<String> = ArrayList()): List<String> {
        if (root is TextView) out += root.text.toString()
        if (root is ViewGroup) for (i in 0 until root.childCount) texts(root.getChildAt(i), out)
        return out
    }

    private fun findButton(root: View, label: String): Button? {
        if (root is Button && root.text.toString() == label) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findButton(root.getChildAt(i), label)?.let { return it }
        return null
    }

    private fun findScroll(root: View): ScrollView? {
        if (root is ScrollView) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findScroll(root.getChildAt(i))?.let { return it }
        return null
    }

    private fun screenshot(name: String) {
        Thread.sleep(1_000) // let layout and async content settle
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/lua-$name.png")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    @Test
    fun everyTabRendersAndTheRunTabExecutesAScript() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // visit every tab twice (build + refresh paths)
            repeat(2) { for (tab in 0..4) scenario.onActivity { it.show(tab) } }

            scenario.onActivity { it.show(MainActivity.TAB_RUN) }
            screenshot("1-run")
            var clicked = false
            scenario.onActivity { a -> findButton(a.window.decorView, "Run")?.let { it.performClick(); clicked = true } }
            assertTrue("the Run button exists", clicked)

            val deadline = System.currentTimeMillis() + 60_000
            var done = false
            while (!done && System.currentTimeMillis() < deadline) {
                Thread.sleep(250)
                scenario.onActivity { a -> done = texts(a.window.decorView).any { it == "SUCCEEDED" } }
            }
            val seen = ArrayList<String>()
            scenario.onActivity { act -> seen += texts(act.window.decorView) }
            assertTrue("the sample script finished; the screen shows: $seen", done)
            screenshot("2-run-finished")

            scenario.onActivity { it.show(MainActivity.TAB_JOBS) }
            screenshot("3-jobs")
            scenario.onActivity { a -> findButton(a.window.decorView, "Details")?.performClick() }
            screenshot("3b-jobs-details")
            var listed = false
            scenario.onActivity { a -> listed = texts(a.window.decorView).any { it == "SUCCEEDED" } }
            assertTrue("the Jobs tab lists the job", listed)

            scenario.onActivity { it.show(MainActivity.TAB_ROCKS) }
            screenshot("4-rocks")
            scenario.onActivity { a -> findScroll(a.window.decorView)?.fullScroll(View.FOCUS_DOWN) }
            screenshot("4b-rocks-bottom")
            scenario.onActivity { it.show(MainActivity.TAB_ACCESS) }
            screenshot("5-access")
            scenario.onActivity { a -> findScroll(a.window.decorView)?.fullScroll(View.FOCUS_DOWN) }
            screenshot("5b-access-bottom")
            scenario.onActivity { it.show(MainActivity.TAB_ABOUT) }
            screenshot("6-about")
        }
    }
}

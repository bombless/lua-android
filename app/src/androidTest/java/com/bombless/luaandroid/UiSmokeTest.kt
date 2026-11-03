package com.bombless.luaandroid

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bombless.luaandroid.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The owner UI is built in code: this catches crashes (casts, layout params, missing runtime) that no unit test can. */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @Before
    fun skipTheNotificationPrompt() {
        // The permission dialog would pause the activity and stall ActivityScenario.
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("ui", Context.MODE_PRIVATE)
            .edit().putBoolean("askedNotifications", true).commit()
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

    @Test
    fun everyTabRendersAndTheRunTabExecutesAScript() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // visit every tab twice (build + refresh paths)
            repeat(2) { for (tab in 0..4) scenario.onActivity { it.show(tab) } }

            scenario.onActivity { it.show(MainActivity.TAB_RUN) }
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

            // the finished job is listed on the Jobs tab, and the other tabs still render with data around
            scenario.onActivity { it.show(MainActivity.TAB_JOBS) }
            Thread.sleep(500)
            var listed = false
            scenario.onActivity { a -> listed = texts(a.window.decorView).any { it == "SUCCEEDED" } }
            assertTrue("the Jobs tab lists the job", listed)
            for (tab in listOf(MainActivity.TAB_ROCKS, MainActivity.TAB_ACCESS, MainActivity.TAB_ABOUT)) scenario.onActivity { it.show(tab) }
        }
    }
}

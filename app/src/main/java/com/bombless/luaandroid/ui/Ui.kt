package com.bombless.luaandroid.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bombless.luaandroid.ipc.JobState

/** Small view-building helpers: the whole UI is code, in a dark, terminal-flavoured palette. */
object Ui {
    const val BG = 0xFF1A1B26.toInt()
    const val SURFACE = 0xFF24283B.toInt()
    const val SURFACE_HI = 0xFF2F3452.toInt()
    const val BORDER = 0xFF3B4261.toInt()
    const val TEXT = 0xFFC0CAF5.toInt()
    const val MUTED = 0xFF8089B3.toInt()
    const val ACCENT = 0xFF7AA2F7.toInt()
    const val OK = 0xFF9ECE6A.toInt()
    const val ERR = 0xFFF7768E.toInt()
    const val WARN = 0xFFE0AF68.toInt()
    const val CODE_BG = 0xFF16161E.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(ctx: Context, fill: Int, radiusDp: Int = 10, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(ctx, 1), stroke)
        }

    fun text(ctx: Context, s: CharSequence, sizeSp: Float = 14f, color: Int = TEXT, bold: Boolean = false, mono: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (mono) typeface = Typeface.MONOSPACE
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun button(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(if (primary) BG else TEXT)
            val fill = if (primary) ACCENT else SURFACE_HI
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(ctx, fill, 10), null)
            minHeight = 0
            minimumHeight = dp(ctx, 40)
            setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
            stateListAnimator = null
            setOnClickListener { onClick() }
        }

    fun chip(ctx: Context, label: String, color: Int): TextView =
        text(ctx, label, 11f, color, bold = true).apply {
            setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2))
            background = rounded(ctx, (color and 0x00FFFFFF) or 0x33000000, 8)
        }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        background = rounded(ctx, SURFACE, 12, BORDER)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(ctx, 10)
        }
    }

    fun row(ctx: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
    }

    fun space(ctx: Context, heightDp: Int = 8, widthDp: Int = 0): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ctx, widthDp), dp(ctx, heightDp))
    }

    fun codeBox(ctx: Context, minLines: Int = 1): TextView = text(ctx, "", 12.5f, TEXT, mono = true).apply {
        setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        background = rounded(ctx, CODE_BG, 8, BORDER)
        setTextIsSelectable(true)
        this.minLines = minLines
    }

    fun input(ctx: Context, hint: String, multiline: Boolean = false, mono: Boolean = false): EditText = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(MUTED)
        setTextColor(TEXT)
        textSize = 13.5f
        if (mono) typeface = Typeface.MONOSPACE
        // NO_SUGGESTIONS keeps the keyboard from "correcting" code and rock names.
        inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if (multiline) { gravity = Gravity.TOP or Gravity.START; isSingleLine = false }
        setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        background = rounded(ctx, CODE_BG, 8, BORDER)
    }

    fun heading(ctx: Context, s: String): TextView = text(ctx, s, 12f, MUTED, bold = true).apply {
        isAllCaps = true
        letterSpacing = 0.08f
        setPadding(0, dp(ctx, 10), 0, dp(ctx, 6))
    }

    fun stateColor(state: Int): Int = when (state) {
        JobState.QUEUED -> MUTED
        JobState.RUNNING -> ACCENT
        JobState.SUCCEEDED -> OK
        JobState.CANCELLED -> WARN
        JobState.TIMED_OUT -> WARN
        else -> ERR
    }

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun confirm(ctx: Context, title: String, message: String, positive: String, onYes: () -> Unit) {
        AlertDialog.Builder(ctx).setTitle(title).setMessage(message)
            .setPositiveButton(positive) { _, _ -> onYes() }.setNegativeButton("Cancel", null).show()
    }

    fun shortId(id: String): String = id.take(8)

    fun duration(startMs: Long, endMs: Long): String {
        if (startMs <= 0) return "-"
        val ms = (if (endMs > 0) endMs else System.currentTimeMillis()) - startMs
        return if (ms < 1000) "$ms ms" else String.format("%.1f s", ms / 1000.0)
    }

    fun readTextTail(file: java.io.File, maxBytes: Int = 128 * 1024): String {
        if (!file.isFile) return ""
        java.io.RandomAccessFile(file, "r").use { f ->
            val len = f.length()
            val start = maxOf(0L, len - maxBytes)
            f.seek(start)
            val buf = ByteArray((len - start).toInt())
            f.readFully(buf)
            val text = String(buf, Charsets.UTF_8)
            return if (start > 0) "…(truncated)\n$text" else text
        }
    }
}

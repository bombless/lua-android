package com.bombless.luaandroid.core

import com.bombless.luaandroid.ipc.ApiLimits

/**
 * Validation of every name a client can influence (Manual.md section 3.1): file names, working
 * directory, environment keys. Rules are deliberately strict: anything that could be interpreted as a
 * path (separators, "..", NUL, empty names) is rejected rather than sanitised.
 */
object PathRules {
    private val NAME = Regex("[A-Za-z0-9_][A-Za-z0-9._-]*")
    private val ENV_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The only top-level directories a script can touch, and whether they are writable. */
    val ROOTS: Map<String, Boolean> = mapOf("input" to false, "output" to true, "tmp" to true)

    fun isValidInputName(name: String): Boolean =
        name.length in 1..ApiLimits.MAX_FILE_NAME_BYTES && NAME.matches(name)

    /** Display / chunk name: printable, no separators. */
    fun isValidScriptName(name: String): Boolean =
        name.length in 1..ApiLimits.MAX_FILE_NAME_BYTES &&
            name.none { it < ' ' || it == '\u007f' || it == '/' || it == '\\' }

    fun isValidEnvKey(key: String): Boolean =
        key.length in 1..ApiLimits.MAX_ENVIRONMENT_KEY_BYTES && ENV_KEY.matches(key)

    /**
     * Returns the normalised working directory ("output" when null/empty) or throws
     * ServiceError(INVALID_REQUEST). The result is always `input|output|tmp` optionally followed by
     * up to five plain segments.
     */
    fun normalizeWorkingDirectory(raw: String?): String {
        if (raw == null || raw.isEmpty()) return "output"
        if (raw.length > 200) throw invalid("workingDirectory is too long")
        val segments = raw.split('/')
        if (segments.size > 6) throw invalid("workingDirectory is nested too deeply")
        for (s in segments) {
            if (s.isEmpty()) throw invalid("workingDirectory must be a clean relative path (no leading, trailing or doubled '/')")
            if (s == "." || s == "..") throw invalid("workingDirectory must not contain '.' or '..'")
            if (!NAME.matches(s)) throw invalid("workingDirectory contains an invalid segment")
        }
        if (segments[0] !in ROOTS) throw invalid("workingDirectory must start with input, output or tmp")
        return segments.joinToString("/")
    }

    private fun invalid(msg: String) = ServiceError("INVALID_REQUEST", msg)
}

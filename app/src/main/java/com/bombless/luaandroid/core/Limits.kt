package com.bombless.luaandroid.core

import com.bombless.luaandroid.ipc.ApiLimits

/** Service-side limits (the client-visible ones live in ApiLimits). */
object Limits {
    const val MAX_QUEUED_TOTAL = 64
    const val MAX_ACTIVE_PER_CALLER = 16
    const val MAX_OUTPUT_FILES = 64

    /** Per output file (enforced by the runner through RLIMIT_FSIZE). */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** output/ + tmp/ of one job; the supervisor kills the runner when exceeded. */
    const val MAX_JOB_OUTPUT_BYTES = 128L * 1024 * 1024
    const val DEFAULT_STREAM_CAP_BYTES = 1024L * 1024
    const val RESULT_JSON_CAP_BYTES = 1024L * 1024

    /** Time between the in-runner deadline and the host's SIGKILL. */
    const val KILL_GRACE_MILLIS = 2_000L

    fun clampTimeout(requested: Long, maxMillis: Long): Long = when {
        requested <= 0 -> ApiLimits.DEFAULT_TIMEOUT_MILLIS.coerceAtMost(maxMillis)
        else -> requested.coerceIn(ApiLimits.MIN_TIMEOUT_MILLIS, maxMillis)
    }

    fun clampMemory(requested: Long): Long = when {
        requested <= 0 -> ApiLimits.DEFAULT_MEMORY_LIMIT_BYTES
        else -> requested.coerceIn(ApiLimits.MIN_MEMORY_LIMIT_BYTES, ApiLimits.MAX_MEMORY_LIMIT_BYTES)
    }
}

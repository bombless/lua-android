package com.bombless.luaandroid.ipc

/**
 * Job life cycle (Manual.md section 3.2):
 *
 *     QUEUED -> RUNNING -> SUCCEEDED | FAILED | CANCELLED | TIMED_OUT
 *     QUEUED -> CANCELLED
 *
 * Terminal states are immutable. States are plain ints on the wire; clients must tolerate values
 * they do not know (treat anything >= [SUCCEEDED] as terminal).
 */
object JobState {
    const val QUEUED = 0
    const val RUNNING = 1
    const val SUCCEEDED = 2
    const val FAILED = 3
    const val CANCELLED = 4
    const val TIMED_OUT = 5

    @JvmStatic
    fun isTerminal(state: Int): Boolean = state >= SUCCEEDED

    @JvmStatic
    fun name(state: Int): String = when (state) {
        QUEUED -> "QUEUED"
        RUNNING -> "RUNNING"
        SUCCEEDED -> "SUCCEEDED"
        FAILED -> "FAILED"
        CANCELLED -> "CANCELLED"
        TIMED_OUT -> "TIMED_OUT"
        else -> "UNKNOWN($state)"
    }
}

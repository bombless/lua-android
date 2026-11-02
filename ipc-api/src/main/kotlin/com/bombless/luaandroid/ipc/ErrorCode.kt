package com.bombless.luaandroid.ipc

/**
 * Stable machine-readable error codes (Manual.md section 7). Clients must tolerate codes they do
 * not know. Codes appear in two places:
 *  - [JobInfo.errorCode] for jobs that ended in FAILED / TIMED_OUT / CANCELLED;
 *  - as the prefix ("CODE: details") of the message of exceptions thrown by the Binder calls.
 */
object ErrorCode {
    const val UNAUTHORIZED_CALLER = "UNAUTHORIZED_CALLER"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val UNSUPPORTED_API_VERSION = "UNSUPPORTED_API_VERSION"
    const val INPUT_TOO_LARGE = "INPUT_TOO_LARGE"
    const val QUEUE_FULL = "QUEUE_FULL"
    const val JOB_NOT_FOUND = "JOB_NOT_FOUND"
    const val JOB_ALREADY_FINISHED = "JOB_ALREADY_FINISHED"
    const val SCRIPT_COMPILE_ERROR = "SCRIPT_COMPILE_ERROR"
    const val SCRIPT_RUNTIME_ERROR = "SCRIPT_RUNTIME_ERROR"
    const val EXECUTION_TIMEOUT = "EXECUTION_TIMEOUT"
    const val EXECUTION_CANCELLED = "EXECUTION_CANCELLED"
    const val MEMORY_LIMIT_EXCEEDED = "MEMORY_LIMIT_EXCEEDED"
    const val RESULT_TOO_LARGE = "RESULT_TOO_LARGE"
    const val RESULT_NOT_SERIALIZABLE = "RESULT_NOT_SERIALIZABLE"
    const val ROCK_NOT_FOUND = "ROCK_NOT_FOUND"
    const val ROCK_INSTALL_FAILED = "ROCK_INSTALL_FAILED"
    const val NATIVE_ROCK_UNSUPPORTED = "NATIVE_ROCK_UNSUPPORTED"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    /** Returns the "CODE" prefix of a Binder exception message ("CODE: details"), or null. */
    @JvmStatic
    fun fromException(e: Throwable): String? = fromMessage(e.message)

    @JvmStatic
    fun fromMessage(message: String?): String? {
        if (message == null) return null
        val i = message.indexOf(':')
        if (i <= 0) return null
        val code = message.substring(0, i)
        return if (code.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }) code else null
    }

    /** The human readable part of "CODE: details". */
    @JvmStatic
    fun detail(e: Throwable): String {
        val m = e.message ?: return e.toString()
        return if (fromMessage(m) != null) m.substringAfter(':').trim() else m
    }
}

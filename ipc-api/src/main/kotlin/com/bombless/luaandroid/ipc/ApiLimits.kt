package com.bombless.luaandroid.ipc

/**
 * Hard limits of API v1. Clients can validate against these before calling; the service enforces
 * them and rejects violations with INVALID_REQUEST / INPUT_TOO_LARGE. Values that are "clamped"
 * (timeout, memory) are adjusted to the allowed range instead of rejected.
 */
object ApiLimits {
    const val MAX_INLINE_SOURCE_BYTES = 256 * 1024
    const val MAX_SCRIPT_FILE_BYTES = 8L * 1024 * 1024
    const val MAX_ARGUMENTS = 64
    const val MAX_ARGUMENT_BYTES = 4096
    const val MAX_ENVIRONMENT_ENTRIES = 64
    const val MAX_ENVIRONMENT_KEY_BYTES = 128
    const val MAX_ENVIRONMENT_VALUE_BYTES = 4096
    const val MAX_INPUT_FILES = 16
    const val MAX_FILE_NAME_BYTES = 128
    const val MAX_INPUT_FILE_BYTES = 256L * 1024 * 1024
    const val MAX_TOTAL_INPUT_BYTES = 512L * 1024 * 1024

    const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    const val MIN_TIMEOUT_MILLIS = 100L
    const val MAX_TIMEOUT_MILLIS = 600_000L

    const val DEFAULT_MEMORY_LIMIT_BYTES = 128L * 1024 * 1024
    const val MIN_MEMORY_LIMIT_BYTES = 8L * 1024 * 1024
    const val MAX_MEMORY_LIMIT_BYTES = 512L * 1024 * 1024

    /** Serialised return values larger than this are available only through the "result" artifact. */
    const val MAX_INLINE_RESULT_BYTES = 32 * 1024

    /** Artifact IDs that always have a fixed meaning. Generated files get opaque "f<number>" IDs. */
    const val ARTIFACT_STDOUT = "stdout"
    const val ARTIFACT_STDERR = "stderr"
    const val ARTIFACT_RESULT = "result"
}

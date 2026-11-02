package com.bombless.luaandroid.core

import com.bombless.luaandroid.ipc.ApiLimits
import com.bombless.luaandroid.ipc.ErrorCode

/** A request that passed validation. Sizes of file descriptors are checked while staging. */
class ValidatedRequest(
    val source: String?,
    val scriptName: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: String,
    val timeoutMillis: Long,
    val memoryLimitBytes: Long,
    val inputNames: List<String>,
)

/** Pure validation (no Android types) so it is unit-testable on the JVM. */
object RequestValidator {
    fun validate(
        apiVersion: Int,
        source: String?,
        hasScriptFd: Boolean,
        scriptName: String?,
        arguments: List<String>,
        environment: Map<String, String>,
        workingDirectory: String?,
        timeoutMillis: Long,
        memoryLimitBytes: Long,
        inputNames: List<String>,
        maxTimeoutMillis: Long,
    ): ValidatedRequest {
        if (apiVersion < 1 || apiVersion > com.bombless.luaandroid.ipc.LuaRuntimeContract.API_VERSION) {
            throw ServiceError(ErrorCode.UNSUPPORTED_API_VERSION, "API version $apiVersion is not supported (supported: 1..${com.bombless.luaandroid.ipc.LuaRuntimeContract.API_VERSION})")
        }
        if (source != null && hasScriptFd) throw invalid("set either source or scriptFd, not both")
        if (source == null && !hasScriptFd) throw invalid("either source or scriptFd is required")
        if (source != null && source.toByteArray(Charsets.UTF_8).size > ApiLimits.MAX_INLINE_SOURCE_BYTES) {
            throw ServiceError(ErrorCode.INPUT_TOO_LARGE, "inline source exceeds ${ApiLimits.MAX_INLINE_SOURCE_BYTES} bytes; pass it as scriptFd")
        }

        val name = scriptName ?: "script.lua"
        if (!PathRules.isValidScriptName(name)) throw invalid("scriptName must be 1..${ApiLimits.MAX_FILE_NAME_BYTES} printable characters without '/' or '\\'")

        if (arguments.size > ApiLimits.MAX_ARGUMENTS) throw ServiceError(ErrorCode.INPUT_TOO_LARGE, "at most ${ApiLimits.MAX_ARGUMENTS} arguments are allowed")
        for (a in arguments) {
            if (a.toByteArray(Charsets.UTF_8).size > ApiLimits.MAX_ARGUMENT_BYTES) {
                throw ServiceError(ErrorCode.INPUT_TOO_LARGE, "an argument exceeds ${ApiLimits.MAX_ARGUMENT_BYTES} bytes")
            }
        }

        if (environment.size > ApiLimits.MAX_ENVIRONMENT_ENTRIES) {
            throw ServiceError(ErrorCode.INPUT_TOO_LARGE, "at most ${ApiLimits.MAX_ENVIRONMENT_ENTRIES} environment entries are allowed")
        }
        for ((k, v) in environment) {
            if (!PathRules.isValidEnvKey(k)) throw invalid("invalid environment key (allowed: [A-Za-z_][A-Za-z0-9_]*, up to ${ApiLimits.MAX_ENVIRONMENT_KEY_BYTES} bytes)")
            if (v.toByteArray(Charsets.UTF_8).size > ApiLimits.MAX_ENVIRONMENT_VALUE_BYTES) {
                throw ServiceError(ErrorCode.INPUT_TOO_LARGE, "environment value of '$k' exceeds ${ApiLimits.MAX_ENVIRONMENT_VALUE_BYTES} bytes")
            }
        }

        val wd = PathRules.normalizeWorkingDirectory(workingDirectory)

        if (inputNames.size > ApiLimits.MAX_INPUT_FILES) throw ServiceError(ErrorCode.INPUT_TOO_LARGE, "at most ${ApiLimits.MAX_INPUT_FILES} input files are allowed")
        val seen = HashSet<String>()
        for (n in inputNames) {
            if (!PathRules.isValidInputName(n)) throw invalid("invalid input file name (allowed: [A-Za-z0-9_][A-Za-z0-9._-]*, up to ${ApiLimits.MAX_FILE_NAME_BYTES} bytes)")
            if (!seen.add(n)) throw invalid("duplicate input file name '$n'")
        }

        return ValidatedRequest(
            source = source,
            scriptName = name,
            arguments = arguments,
            environment = environment,
            workingDirectory = wd,
            timeoutMillis = Limits.clampTimeout(timeoutMillis, maxTimeoutMillis),
            memoryLimitBytes = Limits.clampMemory(memoryLimitBytes),
            inputNames = inputNames,
        )
    }

    private fun invalid(msg: String) = ServiceError(ErrorCode.INVALID_REQUEST, msg)
}

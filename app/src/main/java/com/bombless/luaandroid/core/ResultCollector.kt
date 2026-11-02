package com.bombless.luaandroid.core

import android.webkit.MimeTypeMap
import com.bombless.luaandroid.ipc.ApiLimits
import com.bombless.luaandroid.ipc.ErrorCode
import com.bombless.luaandroid.ipc.JobState
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** What the runner reported in `status.json`. */
class RunnerStatus(val status: String, val exitCode: Int?, val errorCode: String?, val errorMessage: String?, val truncated: Boolean) {
    companion object {
        fun parse(text: String): RunnerStatus? = try {
            val o = JSONObject(text)
            RunnerStatus(
                status = o.getString("status"),
                exitCode = if (o.has("exitCode")) o.getInt("exitCode") else null,
                errorCode = o.optString("errorCode").takeIf { it.isNotEmpty() },
                errorMessage = o.optString("errorMessage").takeIf { it.isNotEmpty() },
                truncated = o.optBoolean("truncated"),
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** Turns what a finished (or killed) runner left in the job directory into the final [JobRecord]. */
object ResultCollector {
    fun collect(rec: JobRecord, jobDir: File, outcome: RunOutcome, now: Long, interruptedMessage: String? = null): JobRecord {
        val statusFile = File(jobDir, "status.json")
        val status = if (statusFile.isFile && statusFile.length() <= 64 * 1024) RunnerStatus.parse(statusFile.readText()) else null

        var state: Int
        var code: String? = null
        var message: String? = null
        when {
            outcome.startFailure != null -> { state = JobState.FAILED; code = ErrorCode.INTERNAL_ERROR; message = outcome.startFailure }
            outcome.killedFor == KillReason.CANCEL -> { state = JobState.CANCELLED; code = ErrorCode.EXECUTION_CANCELLED; message = "The job was cancelled." }
            outcome.killedFor == KillReason.DEADLINE -> { state = JobState.TIMED_OUT; code = ErrorCode.EXECUTION_TIMEOUT; message = "The job exceeded its ${rec.timeoutMillis} ms deadline and was killed." }
            outcome.killedFor == KillReason.QUOTA -> { state = JobState.FAILED; code = ErrorCode.RESULT_TOO_LARGE; message = "The job wrote more than ${Limits.MAX_JOB_OUTPUT_BYTES / (1024 * 1024)} MiB of files and was killed." }
            status == null -> {
                state = JobState.FAILED
                code = ErrorCode.INTERNAL_ERROR
                message = interruptedMessage ?: "The Lua runner ended unexpectedly (exit code ${outcome.exitCode})."
            }
            status.status == "ok" -> state = JobState.SUCCEEDED
            status.status == "timeout" -> { state = JobState.TIMED_OUT; code = ErrorCode.EXECUTION_TIMEOUT; message = status.errorMessage ?: "Execution deadline exceeded" }
            status.status == "cancelled" -> { state = JobState.CANCELLED; code = ErrorCode.EXECUTION_CANCELLED; message = status.errorMessage ?: "Execution cancelled" }
            else -> { state = JobState.FAILED; code = status.errorCode ?: ErrorCode.SCRIPT_RUNTIME_ERROR; message = status.errorMessage }
        }

        val artifacts = ArrayList<ArtifactRecord>()
        val stdout = File(jobDir, "stdout.txt")
        val stderr = File(jobDir, "stderr.txt")
        artifacts += ArtifactRecord(ApiLimits.ARTIFACT_STDOUT, "stdout.txt", "text/plain", stdout.length(), "stdout.txt")
        artifacts += ArtifactRecord(ApiLimits.ARTIFACT_STDERR, "stderr.txt", "text/plain", stderr.length(), "stderr.txt")

        var inline: String? = null
        val resultFile = File(jobDir, "result.json")
        if (state == JobState.SUCCEEDED && resultFile.isFile) {
            artifacts += ArtifactRecord(ApiLimits.ARTIFACT_RESULT, "result.json", "application/json", resultFile.length(), "result.json")
            if (resultFile.length() <= ApiLimits.MAX_INLINE_RESULT_BYTES) inline = resultFile.readText(Charsets.UTF_8)
        }

        val outputDir = File(jobDir, "output")
        var n = 0
        outputDir.walkTopDown().maxDepth(4).filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.sortedBy { it.path }.forEach { f ->
            if (n < Limits.MAX_OUTPUT_FILES) {
                n++
                val rel = f.relativeTo(jobDir).invariantSeparatorsPath
                artifacts += ArtifactRecord("f$n", f.relativeTo(outputDir).invariantSeparatorsPath, mimeFor(f.name), f.length(), rel)
            }
        }

        return rec.copy(
            state = state,
            finishedAt = now,
            exitCode = status?.exitCode ?: outcome.exitCode,
            returnValueJson = inline,
            stdoutBytes = stdout.length(),
            stderrBytes = stderr.length(),
            truncated = status?.truncated ?: false,
            errorCode = code,
            errorMessage = message?.take(4096),
            artifacts = artifacts,
        )
    }

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return when (ext) {
            "txt", "log", "lua" -> "text/plain"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "md" -> "text/markdown"
            else -> MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}

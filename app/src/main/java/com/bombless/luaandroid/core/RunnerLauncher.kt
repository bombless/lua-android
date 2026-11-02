package com.bombless.luaandroid.core

import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Set by the manager to request cancellation of a running job. */
class RunControl {
    @Volatile var cancelRequested = false
}

enum class KillReason { CANCEL, DEADLINE, QUOTA }

/** [startFailure] is set when the process could not be started at all. */
class RunOutcome(val exitCode: Int, val killedFor: KillReason? = null, val startFailure: String? = null)

/**
 * Starts one `libluart.so exec` process per job and supervises it. The runner enforces the deadline,
 * memory and output caps itself; this class is the guarantee behind it: SIGKILL of the runner's process
 * group on cancellation, on `timeout + grace`, or when the job's files outgrow the quota.
 */
class RunnerLauncher(private val paths: Paths) {

    fun run(jobDir: File, timeoutMillis: Long, memoryLimitBytes: Long, streamCapBytes: Long, control: RunControl): RunOutcome {
        val runner = paths.runner
        if (!runner.isFile) {
            return RunOutcome(-1, startFailure = "The native Lua runner is missing for this device (ABIs: ${Build.SUPPORTED_ABIS.joinToString()}).")
        }
        File(jobDir, "tmp").mkdirs()
        val command = listOf(
            runner.path, "exec",
            "--runtime", paths.runtime.path,
            "--job", jobDir.path,
            "--rocks", paths.rocks.path,
            "--timeout-ms", timeoutMillis.toString(),
            "--mem-bytes", memoryLimitBytes.toString(),
            "--out-bytes", streamCapBytes.toString(),
            "--result-bytes", Limits.RESULT_JSON_CAP_BYTES.toString(),
            "--fsize-bytes", Limits.MAX_FILE_BYTES.toString(),
        )
        val builder = ProcessBuilder(command)
            .directory(jobDir)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.to(File(jobDir, "stdout.txt")))
            .redirectError(ProcessBuilder.Redirect.to(File(jobDir, "stderr.txt")))
        builder.environment().apply {
            clear() // scripts see only what the request put in `environment`
            put("TMPDIR", File(jobDir, "tmp").path)
        }

        val process = try {
            builder.start()
        } catch (e: IOException) {
            return RunOutcome(-1, startFailure = "Cannot start the Lua runner: ${e.message}")
        }

        val startedAt = SystemClock.elapsedRealtime()
        val hardDeadline = startedAt + timeoutMillis + Limits.KILL_GRACE_MILLIS
        var killedFor: KillReason? = null
        var termAt = 0L
        var escalated = false
        var lastQuotaCheck = startedAt
        while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
            val now = SystemClock.elapsedRealtime()
            if (killedFor == null) {
                if (control.cancelRequested) {
                    killedFor = KillReason.CANCEL
                    termAt = now
                    signal(jobDir, process, OsConstants.SIGTERM)
                } else if (now >= hardDeadline) {
                    killedFor = KillReason.DEADLINE
                    signal(jobDir, process, OsConstants.SIGKILL)
                    escalated = true
                } else if (now - lastQuotaCheck >= 250) {
                    lastQuotaCheck = now
                    if (outputSize(jobDir) > Limits.MAX_JOB_OUTPUT_BYTES) {
                        killedFor = KillReason.QUOTA
                        signal(jobDir, process, OsConstants.SIGKILL)
                        escalated = true
                    }
                }
            } else if (!escalated && now - termAt >= 500) {
                escalated = true
                signal(jobDir, process, OsConstants.SIGKILL)
            }
        }
        return RunOutcome(process.exitValue(), killedFor)
    }

    /** Signals the runner's process group (the runner calls setpgid), falling back to the process itself. */
    private fun signal(jobDir: File, process: Process, sig: Int) {
        val pid = readPid(jobDir)
        if (pid != null && pid > 1) {
            try {
                Os.kill(-pid, sig)
                return
            } catch (_: ErrnoException) {
                // group does not exist (yet): fall through
            }
        }
        if (sig == OsConstants.SIGKILL) process.destroyForcibly() else process.destroy()
    }

    companion object {
        fun readPid(jobDir: File): Int? = try {
            File(jobDir, "runner.pid").readText().trim().toIntOrNull()
        } catch (_: IOException) {
            null
        }

        /** Bytes the job has produced so far (stdout/stderr caps are enforced by the runner itself). */
        fun outputSize(jobDir: File): Long {
            var total = 0L
            for (name in listOf("output", "tmp")) {
                File(jobDir, name).walkTopDown().forEach { if (it.isFile) total += it.length() }
            }
            return total + File(jobDir, "stdout.txt").length() + File(jobDir, "stderr.txt").length()
        }

        /** Kills a runner left behind by a crashed host, after checking the pid still belongs to this job. */
        fun killOrphan(jobDir: File) {
            val pid = readPid(jobDir) ?: return
            if (pid <= 1) return
            try {
                val cmdline = File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8)
                if (!cmdline.contains(jobDir.path)) return
                Os.kill(-pid, OsConstants.SIGKILL)
            } catch (_: Exception) {
                // already gone
            }
        }
    }
}

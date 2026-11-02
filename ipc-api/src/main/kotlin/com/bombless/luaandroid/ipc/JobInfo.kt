package com.bombless.luaandroid.ipc

import android.os.Parcel
import android.os.Parcelable

/** Snapshot of a job (Manual.md section 3.1). Timestamps are epoch milliseconds, 0 = not set yet. */
class JobInfo() : Parcelable {
    var jobId: String = ""
    var state: Int = JobState.QUEUED
    var createdAtMillis: Long = 0
    var startedAtMillis: Long = 0
    var finishedAtMillis: Long = 0

    /** Lua process exit status; null until the job is terminal. */
    var exitCode: Int? = null

    /**
     * JSON of the script's return value (policy in README "Return values"), null when the script
     * returned nothing, failed, or the JSON exceeds [ApiLimits.MAX_INLINE_RESULT_BYTES]; the full
     * text is then in the "result" artifact.
     */
    var returnValueJson: String? = null
    var stdoutBytes: Long = 0
    var stderrBytes: Long = 0

    /** True when stdout/stderr were cut at the output cap. */
    var truncated: Boolean = false
    var errorCode: String? = null
    var errorMessage: String? = null
    var artifacts: Array<ArtifactInfo> = emptyArray()

    val isTerminal: Boolean get() = JobState.isTerminal(state)

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, parcelFlags: Int) {
        Wire.write(dest, 1) {
            dest.writeString(jobId)
            dest.writeInt(state)
            dest.writeLong(createdAtMillis)
            dest.writeLong(startedAtMillis)
            dest.writeLong(finishedAtMillis)
            val exit = exitCode
            dest.writeInt(if (exit != null) 1 else 0)
            dest.writeInt(exit ?: 0)
            dest.writeString(returnValueJson)
            dest.writeLong(stdoutBytes)
            dest.writeLong(stderrBytes)
            dest.writeInt(if (truncated) 1 else 0)
            dest.writeString(errorCode)
            dest.writeString(errorMessage)
            dest.writeInt(artifacts.size)
            for (a in artifacts) a.writeToParcel(dest, parcelFlags)
        }
    }

    override fun toString(): String =
        "JobInfo(id=$jobId, state=${JobState.name(state)}, exit=$exitCode, error=$errorCode, artifacts=${artifacts.size})"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<JobInfo> = object : Parcelable.Creator<JobInfo> {
            override fun createFromParcel(source: Parcel): JobInfo = Wire.read(source) { _ ->
                val j = JobInfo()
                j.jobId = source.readString() ?: ""
                j.state = source.readInt()
                j.createdAtMillis = source.readLong()
                j.startedAtMillis = source.readLong()
                j.finishedAtMillis = source.readLong()
                val hasExit = source.readInt() != 0
                val exit = source.readInt()
                j.exitCode = if (hasExit) exit else null
                j.returnValueJson = source.readString()
                j.stdoutBytes = source.readLong()
                j.stderrBytes = source.readLong()
                j.truncated = source.readInt() != 0
                j.errorCode = source.readString()
                j.errorMessage = source.readString()
                val n = Wire.count(source, 1024, "artifacts")
                j.artifacts = Array(n) { ArtifactInfo.CREATOR.createFromParcel(source) }
                j
            }

            override fun newArray(size: Int): Array<JobInfo?> = arrayOfNulls(size)
        }
    }
}

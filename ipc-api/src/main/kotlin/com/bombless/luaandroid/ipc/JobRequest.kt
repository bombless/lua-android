package com.bombless.luaandroid.ipc

import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

/**
 * A request to run one Lua script (Manual.md section 3.1). Exactly one of [source] and [scriptFd]
 * must be set. Defaults make `JobRequest().apply { source = "return 1" }` a valid request.
 *
 * Script environment (see README "Lua environment"): the script runs in a fresh Lua 5.4 state in its
 * own process with `arg`/`...` = [arguments], `os.getenv` = [environment], and files confined to
 * `input/` (read-only), `output/` and `tmp/`. [workingDirectory] is the directory relative paths
 * resolve against (default `output`).
 */
class JobRequest() : Parcelable {
    /** Request schema version; 1 is the only version defined. */
    var apiVersion: Int = LuaRuntimeContract.API_VERSION

    /** Inline Lua source (at most [ApiLimits.MAX_INLINE_SOURCE_BYTES] bytes of UTF-8). */
    var source: String? = null

    /** Script file when [source] is omitted (at most [ApiLimits.MAX_SCRIPT_FILE_BYTES]); copied during submit(). */
    var scriptFd: ParcelFileDescriptor? = null

    /** Display/debug name; also the chunk name in error messages. Default "script.lua". */
    var scriptName: String? = null

    /** Lua `arg` values (`...` of the main chunk). */
    var arguments: Array<String> = emptyArray()

    /** String-only environment map visible through `os.getenv`. Keys: `[A-Za-z_][A-Za-z0-9_]*`. */
    var environment: Bundle = Bundle()

    /** Relative path inside the job sandbox: `input[/...]`, `output[/...]` or `tmp[/...]`. Null = `output`. */
    var workingDirectory: String? = null

    /** Execution deadline; clamped to [ApiLimits.MIN_TIMEOUT_MILLIS]..[ApiLimits.MAX_TIMEOUT_MILLIS]. 0 = default. */
    var timeoutMillis: Long = ApiLimits.DEFAULT_TIMEOUT_MILLIS

    /** Lua heap limit; clamped to the service range. 0 = default. */
    var memoryLimitBytes: Long = 0

    /** Named inputs staged into `input/`. */
    var inputFiles: Array<InputFile> = emptyArray()

    /** Reserved for future execution flags; unknown bits are ignored by API v1. */
    var flags: Int = 0

    override fun describeContents(): Int =
        if (scriptFd != null || inputFiles.isNotEmpty()) Parcelable.CONTENTS_FILE_DESCRIPTOR else 0

    override fun writeToParcel(dest: Parcel, parcelFlags: Int) {
        Wire.write(dest, 1) {
            dest.writeInt(apiVersion)
            dest.writeString(source)
            val script = scriptFd
            if (script != null) {
                dest.writeInt(1)
                script.writeToParcel(dest, parcelFlags)
            } else {
                dest.writeInt(0)
            }
            dest.writeString(scriptName)
            dest.writeInt(arguments.size)
            for (a in arguments) dest.writeString(a)
            // The environment is sent as plain string pairs, never as a Bundle: the service must not
            // unparcel arbitrary objects supplied by another app.
            val keys = environment.keySet().toList()
            dest.writeInt(keys.size)
            for (k in keys) {
                val v = environment.getString(k)
                    ?: throw IllegalArgumentException("INVALID_REQUEST: environment values must be strings (key '$k')")
                dest.writeString(k)
                dest.writeString(v)
            }
            dest.writeString(workingDirectory)
            dest.writeLong(timeoutMillis)
            dest.writeLong(memoryLimitBytes)
            dest.writeInt(inputFiles.size)
            for (f in inputFiles) f.writeToParcel(dest, parcelFlags)
            dest.writeInt(flags)
        }
    }

    /** Never prints the script: sources may contain secrets. */
    override fun toString(): String =
        "JobRequest(apiVersion=$apiVersion, script=${scriptName ?: "-"}, args=${arguments.size}, inputs=${inputFiles.size}, timeoutMillis=$timeoutMillis)"

    companion object {
        private const val WIRE_MAX = 4096

        @JvmField
        val CREATOR: Parcelable.Creator<JobRequest> = object : Parcelable.Creator<JobRequest> {
            override fun createFromParcel(source: Parcel): JobRequest = Wire.read(source) { _ ->
                val r = JobRequest()
                r.apiVersion = source.readInt()
                r.source = source.readString()
                r.scriptFd = if (source.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(source) else null
                r.scriptName = source.readString()
                val argc = Wire.count(source, WIRE_MAX, "arguments")
                r.arguments = Array(argc) { source.readString() ?: "" }
                val envc = Wire.count(source, WIRE_MAX, "environment")
                val env = Bundle()
                repeat(envc) {
                    val k = source.readString() ?: ""
                    env.putString(k, source.readString() ?: "")
                }
                r.environment = env
                r.workingDirectory = source.readString()
                r.timeoutMillis = source.readLong()
                r.memoryLimitBytes = source.readLong()
                val inc = Wire.count(source, WIRE_MAX, "input files")
                r.inputFiles = Array(inc) { InputFile.CREATOR.createFromParcel(source) }
                r.flags = source.readInt()
                r
            }

            override fun newArray(size: Int): Array<JobRequest?> = arrayOfNulls(size)
        }
    }
}

package com.bombless.luaandroid.ipc

import android.os.Parcel
import android.os.Parcelable

/** Options of [ILuaRocksService.installRock]. */
class RockOptions() : Parcelable {
    /** Additional rocks server; must be on the owner's allow-list. Null = configured default servers. */
    var server: String? = null

    /** Reserved. */
    var flags: Int = 0

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, parcelFlags: Int) {
        Wire.write(dest, 1) {
            dest.writeString(server)
            dest.writeInt(flags)
        }
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<RockOptions> = object : Parcelable.Creator<RockOptions> {
            override fun createFromParcel(source: Parcel): RockOptions = Wire.read(source) { _ ->
                val o = RockOptions()
                o.server = source.readString()
                o.flags = source.readInt()
                o
            }

            override fun newArray(size: Int): Array<RockOptions?> = arrayOfNulls(size)
        }
    }
}

/** An installed rock. */
class RockInfo() : Parcelable {
    var name: String = ""
    var version: String = ""

    /** LuaRocks status column: "installed", "provided by VM", ... */
    var status: String = "installed"

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, parcelFlags: Int) {
        Wire.write(dest, 1) {
            dest.writeString(name)
            dest.writeString(version)
            dest.writeString(status)
        }
    }

    override fun toString(): String = "$name $version"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<RockInfo> = object : Parcelable.Creator<RockInfo> {
            override fun createFromParcel(source: Parcel): RockInfo = Wire.read(source) { _ ->
                val r = RockInfo()
                r.name = source.readString() ?: ""
                r.version = source.readString() ?: ""
                r.status = source.readString() ?: ""
                r
            }

            override fun newArray(size: Int): Array<RockInfo?> = arrayOfNulls(size)
        }
    }
}

/**
 * A package-manager operation. [state] uses the [JobState] values (QUEUED, RUNNING, SUCCEEDED,
 * FAILED, CANCELLED, TIMED_OUT); operation IDs are unrelated to script job IDs.
 */
class RockOperation() : Parcelable {
    var operationId: String = ""

    /** "install", "remove", "search", "show". */
    var kind: String = ""
    var target: String? = null
    var state: Int = JobState.QUEUED
    var createdAtMillis: Long = 0
    var startedAtMillis: Long = 0
    var finishedAtMillis: Long = 0
    var exitCode: Int? = null

    /** ROCK_NOT_FOUND, ROCK_INSTALL_FAILED, NATIVE_ROCK_UNSUPPORTED, EXECUTION_TIMEOUT, INTERNAL_ERROR, ... */
    var errorCode: String? = null
    var errorMessage: String? = null

    /** Output of the LuaRocks command (bounded; the tail is kept). For search/show this is the result. */
    var log: String = ""

    val isTerminal: Boolean get() = JobState.isTerminal(state)

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, parcelFlags: Int) {
        Wire.write(dest, 1) {
            dest.writeString(operationId)
            dest.writeString(kind)
            dest.writeString(target)
            dest.writeInt(state)
            dest.writeLong(createdAtMillis)
            dest.writeLong(startedAtMillis)
            dest.writeLong(finishedAtMillis)
            val exit = exitCode
            dest.writeInt(if (exit != null) 1 else 0)
            dest.writeInt(exit ?: 0)
            dest.writeString(errorCode)
            dest.writeString(errorMessage)
            dest.writeString(log)
        }
    }

    override fun toString(): String = "RockOperation($kind $target, ${JobState.name(state)}, error=$errorCode)"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<RockOperation> = object : Parcelable.Creator<RockOperation> {
            override fun createFromParcel(source: Parcel): RockOperation = Wire.read(source) { _ ->
                val o = RockOperation()
                o.operationId = source.readString() ?: ""
                o.kind = source.readString() ?: ""
                o.target = source.readString()
                o.state = source.readInt()
                o.createdAtMillis = source.readLong()
                o.startedAtMillis = source.readLong()
                o.finishedAtMillis = source.readLong()
                val hasExit = source.readInt() != 0
                val exit = source.readInt()
                o.exitCode = if (hasExit) exit else null
                o.errorCode = source.readString()
                o.errorMessage = source.readString()
                o.log = source.readString() ?: ""
                o
            }

            override fun newArray(size: Int): Array<RockOperation?> = arrayOfNulls(size)
        }
    }
}

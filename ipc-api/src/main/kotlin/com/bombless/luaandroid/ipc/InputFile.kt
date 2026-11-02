package com.bombless.luaandroid.ipc

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

/**
 * A named input staged into the job. The service copies the stream into the job's private
 * `input/` directory; the script reads it as `input/<name>` (see [name] rules below).
 *
 * [name]: 1..128 bytes of `[A-Za-z0-9._-]`, must not start with '.', must be unique in the request.
 * [fd]: must refer to a regular file (not a pipe/socket); it is fully read during submit().
 */
class InputFile() : Parcelable {
    var name: String = ""
    var fd: ParcelFileDescriptor? = null

    constructor(name: String, fd: ParcelFileDescriptor) : this() {
        this.name = name
        this.fd = fd
    }

    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    override fun writeToParcel(dest: Parcel, parcelFlags: Int) {
        val descriptor = fd ?: throw IllegalArgumentException("INVALID_REQUEST: InputFile '$name' has no descriptor")
        Wire.write(dest, 1) {
            dest.writeString(name)
            descriptor.writeToParcel(dest, parcelFlags)
        }
    }

    override fun toString(): String = "InputFile(name=$name)"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<InputFile> = object : Parcelable.Creator<InputFile> {
            override fun createFromParcel(source: Parcel): InputFile = Wire.read(source) { _ ->
                val file = InputFile()
                file.name = source.readString() ?: ""
                file.fd = ParcelFileDescriptor.CREATOR.createFromParcel(source)
                file
            }

            override fun newArray(size: Int): Array<InputFile?> = arrayOfNulls(size)
        }
    }
}

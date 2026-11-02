package com.bombless.luaandroid.ipc

import android.os.BadParcelableException
import android.os.Parcel

/**
 * Parcel helpers shared by the parcelables of this package.
 *
 * Every parcelable is written as  [int version][int payloadLength][payload]. A reader that only knows
 * older versions skips the unknown tail, so fields can be appended in later versions without
 * breaking old clients/services (Manual.md section 8). Counts read from a parcel are always
 * bounded before anything is allocated: parcels coming from other apps are untrusted.
 */
internal object Wire {
    inline fun write(dest: Parcel, version: Int, body: () -> Unit) {
        dest.writeInt(version)
        val lengthPos = dest.dataPosition()
        dest.writeInt(0)
        val start = dest.dataPosition()
        body()
        val end = dest.dataPosition()
        dest.setDataPosition(lengthPos)
        dest.writeInt(end - start)
        dest.setDataPosition(end)
    }

    inline fun <T> read(src: Parcel, body: (version: Int) -> T): T {
        val version = src.readInt()
        val length = src.readInt()
        val start = src.dataPosition()
        if (version < 1 || length < 0 || start + length > src.dataSize() || start + length < start) {
            throw BadParcelableException("malformed parcelable header")
        }
        val result = body(version)
        val end = start + length
        if (src.dataPosition() > end) throw BadParcelableException("parcelable payload overrun")
        src.setDataPosition(end)
        return result
    }

    fun count(src: Parcel, max: Int, what: String): Int {
        val n = src.readInt()
        if (n < 0 || n > max) throw BadParcelableException("$what count out of range: $n")
        return n
    }
}

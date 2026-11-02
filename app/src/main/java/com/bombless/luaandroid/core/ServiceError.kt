package com.bombless.luaandroid.core

import com.bombless.luaandroid.ipc.ErrorCode

/**
 * A rejected request with a stable machine-readable [code]. The message is "CODE: detail" so that it
 * survives the Binder boundary unchanged (Binder only transports a few exception types plus their message).
 */
class ServiceError(val code: String, val detail: String) : RuntimeException("$code: $detail") {
    fun toBinderException(): RuntimeException = when (code) {
        ErrorCode.UNAUTHORIZED_CALLER -> SecurityException(message)
        ErrorCode.QUEUE_FULL, ErrorCode.INTERNAL_ERROR -> IllegalStateException(message)
        else -> IllegalArgumentException(message)
    }
}

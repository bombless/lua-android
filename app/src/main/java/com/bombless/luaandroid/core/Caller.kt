package com.bombless.luaandroid.core

/**
 * Identity of whoever is calling, derived from Binder.getCallingUid() - never from request contents.
 * [isOwner] is true for this app itself (its own UI); owners may see every job.
 */
class Caller(val uid: Int, val packages: List<String>, val isOwner: Boolean) {
    val primaryPackage: String get() = packages.firstOrNull() ?: "uid:$uid"
}

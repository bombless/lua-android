package com.bombless.luaandroid.rocks

import com.bombless.luaandroid.ipc.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RockManagerTest {
    @Test
    fun failuresAreClassified() {
        assertEquals(ErrorCode.ROCK_NOT_FOUND, RockManager.classifyFailure("install", "No results matching query were found for Lua 5.4."))
        assertEquals(ErrorCode.ROCK_NOT_FOUND, RockManager.classifyFailure("remove", "Could not find rock 'x' in /tree"))
        assertEquals(ErrorCode.ROCK_NOT_FOUND, RockManager.classifyFailure("show", "Could not find a rock 'x'"))
        assertEquals(ErrorCode.ROCK_INSTALL_FAILED, RockManager.classifyFailure("install", "Error: Failed extracting foo.tar.gz"))
        assertEquals(ErrorCode.INTERNAL_ERROR, RockManager.classifyFailure("remove", "something odd"))
        assertEquals(ErrorCode.INTERNAL_ERROR, RockManager.classifyFailure("search", ""))
    }

    @Test
    fun theLastErrorLineIsTheMessage() {
        assertEquals("Error: boom", RockManager.lastErrorLine("Installing x\nError: boom\n\nUsage: ...\n"))
        assertEquals("last line", RockManager.lastErrorLine("one\nlast line\n"))
        assertNull(RockManager.lastErrorLine(""))
        assertEquals(500, RockManager.lastErrorLine("Error: " + "x".repeat(900))!!.length)
    }
}

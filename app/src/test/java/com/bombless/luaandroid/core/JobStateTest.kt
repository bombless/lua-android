package com.bombless.luaandroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class JobStateTest {
    @Test fun terminalStatesAreExplicit() {
        val terminal = setOf("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT")
        assertEquals(4, terminal.size)
        assert("RUNNING" !in terminal)
    }
}

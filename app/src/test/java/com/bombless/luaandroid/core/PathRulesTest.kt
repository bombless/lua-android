package com.bombless.luaandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathRulesTest {
    @Test
    fun inputNamesAreStrict() {
        for (ok in listOf("a", "data.csv", "my-file_1.txt", "_x", "A.B.C", "a..b", "x".repeat(128))) {
            assertTrue(ok, PathRules.isValidInputName(ok))
        }
        for (bad in listOf("", ".", "..", ".hidden", "-x", "a/b", "a\\b", "a b", "a\u0000b", "\u00e9", "x".repeat(129), "a:b", "a\nb")) {
            assertFalse("'$bad' must be rejected", PathRules.isValidInputName(bad))
        }
    }

    @Test
    fun scriptNamesAllowPrintableTextButNoSeparators() {
        for (ok in listOf("script.lua", "my script.lua", "\u00e9t\u00e9.lua", "a")) assertTrue(ok, PathRules.isValidScriptName(ok))
        for (bad in listOf("", "a/b", "a\\b", "a\nb", "a\u0000b", "a\u007fb", "x".repeat(129))) assertFalse(bad, PathRules.isValidScriptName(bad))
    }

    @Test
    fun environmentKeys() {
        for (ok in listOf("A", "_a", "PATH2", "a_b_c")) assertTrue(ok, PathRules.isValidEnvKey(ok))
        for (bad in listOf("", "1A", "A B", "A=B", "A-B", "\u00e9", "x".repeat(129))) assertFalse(bad, PathRules.isValidEnvKey(bad))
    }

    @Test
    fun workingDirectoryDefaultsAndNormalisation() {
        assertEquals("output", PathRules.normalizeWorkingDirectory(null))
        assertEquals("output", PathRules.normalizeWorkingDirectory(""))
        assertEquals("output", PathRules.normalizeWorkingDirectory("output"))
        assertEquals("input", PathRules.normalizeWorkingDirectory("input"))
        assertEquals("tmp/a", PathRules.normalizeWorkingDirectory("tmp/a"))
        assertEquals("output/a/b/c/d/e", PathRules.normalizeWorkingDirectory("output/a/b/c/d/e"))
    }

    @Test
    fun workingDirectoryRejectsEverythingElse() {
        for (bad in listOf("..", "../x", "output/..", "output/../input", "/abs", "/", "output/", "output//a", "./output", "output/./a",
            "etc", "outputs", "output/a b", "output/a/b/c/d/e/f", "x".repeat(201), "output\\a", "output/a\u0000")) {
            try {
                PathRules.normalizeWorkingDirectory(bad)
                throw AssertionError("'$bad' must be rejected")
            } catch (e: ServiceError) {
                assertEquals("INVALID_REQUEST", e.code)
            }
        }
    }
}

package com.bombless.luaandroid.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AccessStoreTest {
    private fun tempFile(): File = Files.createTempDirectory("access").toFile().resolve("access.json")

    @Test
    fun pendingAllowDenyRevokeAndPersistence() {
        val f = tempFile()
        val s = AccessStore(f)
        assertTrue("first attempt is new", s.recordAttempt("com.a", "A", setOf("c1"), false, 10))
        assertFalse("second is not", s.recordAttempt("com.a", "A", setOf("c1"), true, 20))
        val p = s.pendingRequests().single()
        assertEquals(2, p.attempts)
        assertTrue(p.wantsRocks)
        assertEquals(10L, p.firstSeen)
        assertEquals(20L, p.lastSeen)
        assertFalse(p.signatureChanged)

        s.allow("com.a", "A", setOf("c1"), false, 30)
        assertTrue(s.pendingRequests().isEmpty())
        val a = s.findAllowed("com.a")!!
        assertEquals(setOf("c1"), a.certs)
        assertTrue(a.canRun)
        assertFalse(a.canManageRocks)

        val reloaded = AccessStore(f) // survives a restart
        assertEquals("com.a", reloaded.allowedApps().single().packageName)

        // the same package name now signed with a different key: unknown again, and flagged
        reloaded.recordAttempt("com.a", "A", setOf("other"), false, 40)
        assertTrue(reloaded.pendingRequests().single().signatureChanged)

        reloaded.deny("com.a", 50)
        assertTrue(reloaded.isBlocked("com.a"))
        assertTrue(reloaded.pendingRequests().isEmpty())
        assertFalse("blocked apps do not create requests", reloaded.recordAttempt("com.a", "A", setOf("c1"), false, 60))
        assertTrue(reloaded.pendingRequests().isEmpty())
        reloaded.unblock("com.a")
        assertFalse(reloaded.isBlocked("com.a"))

        reloaded.revoke("com.a")
        assertNull(reloaded.findAllowed("com.a"))
        assertTrue(AccessStore(f).allowedApps().isEmpty())
    }

    @Test
    fun packageManagementIsAnExplicitPerAppGrant() {
        val f = tempFile()
        val s = AccessStore(f)
        s.allow("com.b", "B", setOf("c"), false, 1)
        assertFalse(s.findAllowed("com.b")!!.canManageRocks)
        s.setRocksAccess("com.b", true)
        assertTrue(AccessStore(f).findAllowed("com.b")!!.canManageRocks)
        s.setRocksAccess("com.b", false)
        assertFalse(AccessStore(f).findAllowed("com.b")!!.canManageRocks)
        s.setRocksAccess("com.unknown", true) // no entry: no effect, no crash
        assertNull(s.findAllowed("com.unknown"))
    }

    @Test
    fun aCorruptFileGrantsNothing() {
        val f = tempFile()
        f.writeText("{ this is not json")
        val s = AccessStore(f)
        assertTrue(s.allowedApps().isEmpty())
        assertTrue(s.pendingRequests().isEmpty())
        // and the store keeps working afterwards
        s.allow("com.c", "C", setOf("x"), false, 1)
        assertEquals(1, AccessStore(f).allowedApps().size)
    }

    @Test
    fun listenersAreNotified() {
        val s = AccessStore(tempFile())
        var calls = 0
        val l: () -> Unit = { calls++ }
        s.addListener(l)
        s.recordAttempt("com.d", "D", setOf("x"), false, 1)
        s.allow("com.d", "D", setOf("x"), false, 2)
        s.revoke("com.d")
        assertEquals(3, calls)
        s.removeListener(l)
        s.deny("com.d", 3)
        assertEquals(3, calls)
    }
}

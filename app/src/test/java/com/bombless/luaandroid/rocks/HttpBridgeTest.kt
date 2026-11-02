package com.bombless.luaandroid.rocks

import com.bombless.luaandroid.core.ServiceError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpBridgeTest {
    private val hosts = setOf("luarocks.org", "github.com")

    @Test
    fun httpsToAllowedHostsAndTheirSubdomains() {
        assertNull(HttpBridge.checkUrl("https://luarocks.org/manifest-5.4", hosts, false))
        assertNull(HttpBridge.checkUrl("https://www.luarocks.org/x?y=1", hosts, false))
        assertNull(HttpBridge.checkUrl("https://codeload.github.com/a/b/tar.gz/refs/tags/v1", hosts, false))
        assertNull(HttpBridge.checkUrl("https://LUAROCKS.ORG:443/x", hosts, false))
    }

    @Test
    fun everythingElseIsRefused() {
        val refused = listOf(
            "http://luarocks.org/x",                 // cleartext
            "https://evil.example.org/",             // not allowed
            "https://notluarocks.org/",              // suffix without a dot boundary
            "https://luarocks.org.evil.com/",        // allowed name as a prefix
            "https://luarocks.org@evil.com/",        // credentials trick
            "https://user:pw@luarocks.org/",         // embedded credentials
            "ftp://luarocks.org/", "file:///etc/passwd", "jar:file:///x!/y", "https:///nohost",
            "https://127.0.0.1/", "https://localhost/", "https://[::1]/", // loopback only with the explicit switch
        )
        for (u in refused) assertNotNull("$u must be refused", HttpBridge.checkUrl(u, hosts, false))
        assertNotNull(HttpBridge.checkUrl("not a url", hosts, false))
    }

    @Test
    fun loopbackIsOptIn() {
        for (u in listOf("http://127.0.0.1:8080/manifest", "http://localhost:1/x", "http://[::1]:9/y", "https://127.0.0.1/z")) {
            assertNotNull(u, HttpBridge.checkUrl(u, hosts, false))
            assertNull(u, HttpBridge.checkUrl(u, hosts, true))
        }
        // a name that merely starts with an allowed loopback literal is an ordinary host
        assertNotNull(HttpBridge.checkUrl("http://127.0.0.1.evil.com/", hosts, true))
        assertNotNull(HttpBridge.checkUrl("http://localhost.evil.com/", hosts, true))
        // and loopback allowance does not open plain http for other hosts
        assertNotNull(HttpBridge.checkUrl("http://luarocks.org/", hosts, true))
    }

    @Test
    fun hostMatching() {
        assertTrue(HttpBridge.hostAllowed("a.b.luarocks.org", hosts))
        assertFalse(HttpBridge.hostAllowed("luarocks.org.", hosts))
        assertFalse(HttpBridge.hostAllowed("", hosts))
        assertTrue(HttpBridge.isLoopback("localhost"))
        assertFalse(HttpBridge.isLoopback("127.0.0.2"))
        assertEquals(false, HttpBridge.isLoopback("localhost.localdomain"))
        assertEquals(ServiceError::class.java.simpleName, "ServiceError") // keeps the import honest
    }
}

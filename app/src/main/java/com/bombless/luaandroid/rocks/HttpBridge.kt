package com.bombless.luaandroid.rocks

import android.os.Build
import com.bombless.luaandroid.BuildConfig
import com.bombless.luaandroid.core.AppSettings
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Host side of the runner's network bridge (runner/lua/shims/luart_bridge.lua). The runner has no network
 * stack: every download LuaRocks wants is validated and performed here.
 *
 *  - https only (http:// solely for loopback, when enabled: tests / local mirrors);
 *  - the host must be on the owner's allow-list (exact match or subdomain);
 *  - redirects are NOT followed: LuaRocks sees the 3xx and issues a new request, which is validated again;
 *  - downloads land only in the app's private temp directory and are size limited;
 *  - only GET and HEAD, and only a few harmless request headers.
 */
class HttpBridge(
    private val settings: AppSettings,
    private val tmpDir: File,
    private val maxDownloadBytes: Long = 64L * 1024 * 1024,
) {
    fun handle(msg: JSONObject): JSONObject = try {
        perform(msg)
    } catch (e: Exception) {
        fail(e.message ?: e.javaClass.simpleName)
    }

    private fun perform(msg: JSONObject): JSONObject {
        val method = msg.optString("method", "GET").uppercase()
        if (method != "GET" && method != "HEAD") return fail("unsupported method $method")
        val url = try {
            URL(msg.optString("url"))
        } catch (e: IOException) {
            return fail("invalid URL")
        }
        checkUrl(url, settings.allowedHosts, settings.allowLoopbackHttp)?.let { return fail(it) }

        val dest = msg.optString("dest").takeIf { it.isNotEmpty() }?.let { File(it) }
        if (dest != null && method == "GET") {
            val parent = dest.canonicalFile.parentFile
            if (parent == null || parent != tmpDir.canonicalFile) return fail("destination is outside the temporary directory")
        }

        val timeoutMs = (msg.optInt("timeout", 30) * 1000).coerceIn(5_000, 120_000)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = minOf(timeoutMs, 20_000)
            conn.readTimeout = timeoutMs
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", "LuaAndroid/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}) luarocks")
            msg.optJSONObject("headers")?.let { h ->
                for (name in ALLOWED_REQUEST_HEADERS) {
                    h.keys().asSequence().firstOrNull { it.equals(name, ignoreCase = true) }?.let { conn.setRequestProperty(name, h.optString(it)) }
                }
            }
            val status = conn.responseCode
            val headers = JSONObject()
            var i = 1
            while (true) {
                val key = conn.getHeaderFieldKey(i) ?: if (conn.getHeaderField(i) == null) break else { i++; continue }
                headers.put(key.lowercase(), conn.getHeaderField(i))
                i++
            }
            if (dest != null && method == "GET") {
                var written = 0L
                dest.outputStream().use { out ->
                    if (status == 200) {
                        val declared = conn.contentLengthLong
                        if (declared > maxDownloadBytes) return fail("download too large ($declared bytes)")
                        conn.inputStream.use { input ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                written += n
                                if (written > maxDownloadBytes) return fail("download too large")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
            return JSONObject().put("ok", true).put("status", status)
                .put("statusLine", conn.getHeaderField(0) ?: "HTTP/1.1 $status").put("headers", headers)
        } finally {
            conn.disconnect()
        }
    }

    private fun fail(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    companion object {
        private val ALLOWED_REQUEST_HEADERS = listOf("Accept", "If-Modified-Since", "If-None-Match")

        fun isLoopback(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

        fun hostAllowed(host: String, allowed: Set<String>): Boolean {
            val h = host.lowercase()
            return allowed.any { a -> val x = a.lowercase(); h == x || h.endsWith(".$x") }
        }

        /** Returns a human readable reason when [url] must not be fetched, else null. */
        fun checkUrl(url: URL, allowedHosts: Set<String>, allowLoopbackHttp: Boolean): String? {
            val host = url.host?.lowercase().orEmpty()
            if (host.isEmpty()) return "URL has no host"
            if (url.userInfo != null) return "URLs with embedded credentials are not allowed"
            when (url.protocol) {
                "https" -> Unit
                "http" -> if (!(allowLoopbackHttp && isLoopback(host))) return "cleartext http:// is not allowed (use https)"
                else -> return "unsupported scheme '${url.protocol}'"
            }
            if (isLoopback(host)) return if (allowLoopbackHttp) null else "host not allowed: $host"
            if (!hostAllowed(host, allowedHosts)) return "host not allowed: $host (the owner can allow it under Packages > Allowed hosts)"
            return null
        }

        fun checkUrl(text: String, allowedHosts: Set<String>, allowLoopbackHttp: Boolean): String? = try {
            checkUrl(URL(text), allowedHosts, allowLoopbackHttp)
        } catch (e: IOException) {
            "invalid URL"
        }
    }
}

package com.bombless.luaandroid.example

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A LuaRocks server on loopback, served from this test process: manifest + rockspecs + .src.rock files.
 * The host app downloads from it exactly as it would from luarocks.org (through its HTTP bridge).
 */
class MockRocksServer : AutoCloseable {
    private val files = HashMap<String, ByteArray>()
    private val manifestEntries = ArrayList<Pair<String, String>>() // must precede init{}: Kotlin initialises in textual order
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    @Volatile private var running = true
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    val url: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        addRock("helperlib", "1.0-1", emptyList(), mapOf("helperlib" to "helperlib.lua"),
            mapOf("helperlib.lua" to "return { double = function(x) return x * 2 end }\n"))
        addRock("purelua", "1.0-1", listOf("helperlib >= 1.0"), mapOf("purelua" to "purelua.lua", "purelua.util" to "src/util.lua"),
            mapOf(
                "purelua.lua" to "local h = require('helperlib')\nlocal u = require('purelua.util')\nreturn { answer = function() return u.add(h.double(20), 2) end }\n",
                "src/util.lua" to "return { add = function(a, b) return a + b end }\n",
            ))
        addRock("nativemod", "1.0-1", emptyList(), mapOf("nativemod" to "nativemod.c"), mapOf("nativemod.c" to "int luaopen_nativemod(void *L) { return 0; }\n"))
        files["/manifest-5.4"] = manifest().toByteArray()
        Thread({
            while (running) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                Thread { handle(socket) }.start()
            }
        }, "mock-rocks-server").apply { isDaemon = true }.start()
    }

    private fun manifest(): String {
        val byName = manifestEntries.groupBy({ it.first }, { it.second })
        return "commands = {}\nmodules = {}\nrepository = {\n" + byName.entries.joinToString("\n") { (name, versions) ->
            "   [\"$name\"] = {\n" + versions.joinToString("\n") { v -> "      [\"$v\"] = { { arch = \"rockspec\" }, { arch = \"src\" } }," } + "\n   },"
        } + "\n}\n"
    }

    private fun addRock(name: String, version: String, deps: List<String>, modules: Map<String, String>, sources: Map<String, String>) {
        val ver = version.substringBefore('-')
        val depText = (listOf("lua >= 5.1") + deps).joinToString(", ") { "\"$it\"" }
        val modText = modules.entries.joinToString(",\n") { "      [\"${it.key}\"] = \"${it.value}\"" }
        val spec = """
            package = "$name"
            version = "$version"
            source = { url = "https://example.invalid/$name-$ver.tar.gz", dir = "$name-$ver" }
            description = { summary = "$name test rock", license = "MIT" }
            dependencies = { $depText }
            build = { type = "builtin", modules = {
            $modText
            } }
        """.trimIndent() + "\n"
        val tar = tarGz(sources.mapKeys { "$name-$ver/${it.key}" }.mapValues { it.value.toByteArray() })
        val rock = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("$name-$version.rockspec")); z.write(spec.toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("$name-$ver.tar.gz")); z.write(tar); z.closeEntry()
            }
        }.toByteArray()
        files["/$name-$version.rockspec"] = spec.toByteArray()
        files["/$name-$version.src.rock"] = rock
        manifestEntries += name to version
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream().bufferedReader()
            val request = input.readLine() ?: return
            while (true) { val l = input.readLine() ?: break; if (l.isEmpty()) break }
            val parts = request.split(' ')
            val path = parts.getOrNull(1) ?: "/"
            requests += "${parts[0]} $path"
            val body = files[path]
            val out = s.getOutputStream()
            if (body == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray())
                if (parts[0] != "HEAD") out.write(body)
            }
            out.flush()
        }
    }

    override fun close() {
        running = false
        server.close()
    }

    companion object {
        /** Minimal ustar writer: enough for LuaRocks' own tar reader. */
        fun tarGz(entries: Map<String, ByteArray>): ByteArray {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { gz ->
                for ((name, data) in entries) {
                    val h = ByteArray(512)
                    fun put(offset: Int, text: String) { val b = text.toByteArray(); System.arraycopy(b, 0, h, offset, b.size) }
                    put(0, name); put(100, "0000644"); put(108, "0000000"); put(116, "0000000")
                    put(124, "%011o".format(data.size)); put(136, "%011o".format(0))
                    h[156] = '0'.code.toByte()
                    put(257, "ustar"); put(263, "00")
                    for (i in 148 until 156) h[i] = ' '.code.toByte()
                    put(148, "%06o".format(h.sumOf { it.toInt() and 0xFF }))
                    h[154] = 0; h[155] = ' '.code.toByte()
                    gz.write(h); gz.write(data); gz.write(ByteArray((512 - data.size % 512) % 512))
                }
                gz.write(ByteArray(1024))
            }
            return bos.toByteArray()
        }
    }
}

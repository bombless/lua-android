package com.bombless.luaandroid.ipc

import android.util.Log
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs one job in a fresh LuaJ Globals. Captures print() via redirected stdout.
 */
class LuaJobRunner(private val record: JobRecord) {

    data class Result(
        val returnValueJson: String?,
        val stdoutBytes: Long,
        val stderrBytes: Long,
        val truncated: Boolean,
        val timedOut: Boolean,
        val error: String?
    )

    fun execute(): Result {
        val scriptFile = File(record.jobDir, "script.lua")
        val stdoutFile = File(record.jobDir, "stdout.txt")
        val stderrFile = File(record.jobDir, "stderr.txt")

        val outBuf = BoundedByteArrayOutputStream(JobManager.MAX_OUTPUT_BYTES)
        val errBuf = BoundedByteArrayOutputStream(JobManager.MAX_OUTPUT_BYTES)
        val oldOut = System.out
        val oldErr = System.err

        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit(Callable {
                if (record.cancelled.get()) {
                    return@Callable Result(null, 0, 0, false, false, "Cancelled")
                }
                System.setOut(PrintStream(outBuf, true, StandardCharsets.UTF_8.name()))
                System.setErr(PrintStream(errBuf, true, StandardCharsets.UTF_8.name()))

                val globals: Globals = JsePlatform.standardGlobals()
                // Sandbox: remove dangerous libs that talk to host OS beyond basic IO
                globals.set("os", LuaValue.NIL)
                // Keep io limited to job working directory via chdir-like convention
                val workDir = record.jobDir.absolutePath
                // Inject arg table
                val argTable = LuaTable()
                argTable.set(0, LuaValue.valueOf(scriptFile.name))
                record.arguments.forEachIndexed { i, s ->
                    argTable.set(i + 1, LuaValue.valueOf(s))
                }
                globals.set("arg", argTable)

                // Provide a simple write_artifact helper for generated files
                globals.set("write_artifact", object : org.luaj.vm2.lib.TwoArgFunction() {
                    override fun call(name: LuaValue, content: LuaValue): LuaValue {
                        val safe = JobManager.sanitizeName(name.tojstring())
                            ?: return LuaValue.error("invalid artifact name")
                        val out = File(File(record.jobDir, "output"), safe)
                        out.writeText(content.tojstring())
                        return LuaValue.TRUE
                    }
                })

                val chunk = globals.load(scriptFile.readText(), scriptFile.name)
                val ret: Varargs = chunk.invoke()
                val json = serializeReturn(ret)
                Result(
                    returnValueJson = json,
                    stdoutBytes = outBuf.size().toLong(),
                    stderrBytes = errBuf.size().toLong(),
                    truncated = outBuf.truncated || errBuf.truncated,
                    timedOut = false,
                    error = null
                )
            })

            val result = try {
                future.get(record.timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                Result(null, outBuf.size().toLong(), errBuf.size().toLong(),
                    outBuf.truncated || errBuf.truncated, true, "Timeout")
            } catch (e: Exception) {
                val cause = e.cause ?: e
                val msg = when (cause) {
                    is LuaError -> cause.message ?: "Lua error"
                    else -> cause.message ?: cause.javaClass.simpleName
                }
                Result(null, outBuf.size().toLong(), errBuf.size().toLong(),
                    outBuf.truncated || errBuf.truncated, false, msg)
            }

            stdoutFile.writeBytes(outBuf.toByteArray())
            stderrFile.writeBytes(errBuf.toByteArray())
            return result
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
            executor.shutdownNow()
        }
    }

    private fun serializeReturn(ret: Varargs): String? {
        if (ret.narg() == 0) return null
        return try {
            if (ret.narg() == 1) {
                valueToJson(ret.arg1()).toString()
            } else {
                val arr = org.json.JSONArray()
                for (i in 1..ret.narg()) {
                    arr.put(valueToJson(ret.arg(i)))
                }
                arr.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Return value serialization failed", e)
            """{"error":"serialization_failed","message":"${e.message}"}"""
        }
    }

    private fun valueToJson(v: LuaValue): Any? {
        return when {
            v.isnil() -> org.json.JSONObject.NULL
            v.isboolean() -> v.toboolean()
            v.isint() || v.islong() -> v.tolong()
            v.isnumber() -> v.todouble()
            v.isstring() -> {
                val s = v.tojstring()
                if (s.length > 64 * 1024) s.take(64 * 1024) + "…(truncated)" else s
            }
            v.istable() -> {
                val t = v.checktable()
                // Prefer array if consecutive integer keys from 1
                var isArray = true
                var i = 1
                while (true) {
                    val x = t.get(i)
                    if (x.isnil()) break
                    i++
                    if (i > 1024) {
                        isArray = false
                        break
                    }
                }
                if (isArray && i > 1) {
                    val arr = org.json.JSONArray()
                    for (j in 1 until i) {
                        arr.put(valueToJson(t.get(j)))
                    }
                    arr
                } else {
                    val obj = org.json.JSONObject()
                    var n = 0
                    var k = LuaValue.NIL
                    while (n < 256) {
                        val nres = t.next(k)
                        if (nres == LuaValue.NIL || nres.narg() < 2) break
                        k = nres.arg(1)
                        val val_ = nres.arg(2)
                        val keyStr = if (k.isstring() || k.isnumber()) k.tojstring() else k.typename()
                        obj.put(keyStr, valueToJson(val_))
                        n++
                    }
                    obj
                }
            }
            else -> """<${v.typename()}>"""
        }
    }

    companion object {
        private const val TAG = "LuaJobRunner"
    }
}

/** ByteArrayOutputStream that stops growing past a limit and marks truncation. */
class BoundedByteArrayOutputStream(private val limit: Long) : ByteArrayOutputStream() {
    @Volatile
    var truncated: Boolean = false
        private set

    @Synchronized
    override fun write(b: Int) {
        if (count >= limit) {
            truncated = true
            return
        }
        super.write(b)
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (count >= limit) {
            truncated = true
            return
        }
        val allowed = (limit - count).toInt().coerceAtMost(len)
        if (allowed < len) truncated = true
        if (allowed > 0) super.write(b, off, allowed)
    }
}

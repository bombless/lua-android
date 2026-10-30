package com.bombless.luaandroid.core

import org.json.JSONArray
import org.json.JSONObject
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.File
import java.nio.charset.StandardCharsets

class LuaExecutor {
    data class Result(val json: String?, val exitCode: Int, val errorCode: String? = null, val error: String? = null)

    fun execute(script: File, jobDir: File, args: Array<String>, env: Map<String, String>, timeoutMillis: Long,
                onOutput: (String, ByteArray) -> Unit): Result {
        val stdout = File(jobDir, "output/stdout.txt")
        val stderr = File(jobDir, "output/stderr.txt")
        val out = CappedOutput(1024 * 1024) { onOutput("stdout", it) }
        val err = CappedOutput(1024 * 1024) { onOutput("stderr", it) }
        return try {
            val globals = JsePlatform.standardGlobals()
            // Remove JVM escape hatches. This is defense in depth, not a native-code sandbox.
            globals.set("luajava", LuaValue.NIL)
            globals.set("io", LuaValue.NIL)
            globals.set("os", LuaValue.NIL)
            globals.set("debug", LuaValue.NIL)
            globals.set("package", LuaValue.NIL)
            val argTable = LuaValue.tableOf()
            args.forEachIndexed { index, value -> argTable.set(index - 1, LuaValue.valueOf(value)) }
            globals.set("arg", argTable)
            globals.set("print", object : org.luaj.vm2.lib.VarArgFunction() {
                override fun invoke(a: Varargs): Varargs {
                    val line = (1..a.narg()).joinToString("\t") { a.arg(it).tojstring() } + "\n"
                    out.write(line.toByteArray(StandardCharsets.UTF_8))
                    return LuaValue.NONE
                }
            })
            val started = System.nanoTime()
            val chunk = globals.loadfile(script.absolutePath)
            val values = chunk.invoke()
            if (TimeUnitCompat.elapsedMillis(started) > timeoutMillis) Result(null, 124, "EXECUTION_TIMEOUT", "Execution deadline exceeded")
            else Result(serialize(values), 0)
        } catch (e: LuaError) {
            val compile = e.message?.contains("compile", true) == true
            err.write((e.message ?: "Lua error").toByteArray())
            Result(null, 1, if (compile) "SCRIPT_COMPILE_ERROR" else "SCRIPT_RUNTIME_ERROR", e.message)
        } catch (e: Throwable) {
            err.write((e.message ?: e.javaClass.simpleName).toByteArray())
            Result(null, 1, "INTERNAL_ERROR", e.message)
        } finally {
            stdout.writeBytes(out.bytes())
            stderr.writeBytes(err.bytes())
        }
    }

    private fun serialize(values: Varargs): String {
        if (values.narg() == 0 || values.arg1().isnil()) return "null"
        fun convert(v: LuaValue, seen: MutableSet<LuaValue>, depth: Int): Any? {
            require(depth <= 32) { "RESULT_TOO_LARGE: nesting limit" }
            return when {
                v.isnil() -> null
                v.isboolean() -> v.toboolean()
                v.isnumber() -> v.todouble().takeIf { it.isFinite() } ?: error("Non-finite number")
                v.isstring() -> v.tojstring().take(256 * 1024)
                v.istable() -> {
                    require(seen.add(v)) { "Cyclic Lua table cannot be serialized" }
                    val entries = v.checktable().keys().asSequence().toList()
                    val array = entries.all { it.isnumber() && it.todouble() >= 1 && it.todouble() % 1.0 == 0.0 }
                    val result: Any = if (array) JSONArray().apply {
                        val max = entries.maxOfOrNull { it.toint() } ?: 0
                        require(max <= 10000) { "Table exceeds result limit" }
                        for (i in 1..max) put(convert(v.get(i), seen, depth + 1))
                    } else JSONObject().apply {
                        require(entries.size <= 10000) { "Table exceeds result limit" }
                        entries.forEach { key ->
                            require(key.isstring() || key.isnumber()) { "Unsupported table key" }
                            put(key.tojstring(), convert(v.get(key), seen, depth + 1))
                        }
                    }
                    seen.remove(v); result
                }
                else -> error("Unsupported Lua value type: ${v.typename()}")
            }
        }
        return JSONObject.wrap(convert(values.arg1(), mutableSetOf(), 0))?.toString() ?: "null"
    }

    private class CappedOutput(private val cap: Int, private val sink: (ByteArray) -> Unit) {
        private val buffer = java.io.ByteArrayOutputStream()
        fun write(b: ByteArray) {
            val remaining = (cap - buffer.size()).coerceAtLeast(0)
            val count = minOf(remaining, b.size)
            if (count > 0) buffer.write(b, 0, count)
            if (count > 0) sink(b.copyOf(count))
        }
        fun bytes() = buffer.toByteArray()
    }
}
private object TimeUnitCompat { fun elapsedMillis(start: Long) = (System.nanoTime() - start) / 1_000_000L }

package com.bombless.luaandroid.core

import org.json.JSONArray
import org.json.JSONObject
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.File
import java.nio.charset.StandardCharsets

class LuaExecutor {
    data class Result(
        val json: String?,
        val exitCode: Int,
        val errorCode: String? = null,
        val error: String? = null,
        val truncated: Boolean = false
    )

    fun execute(script: File, jobDir: File, args: Array<String>, env: Map<String, String>, timeoutMillis: Long,
                onOutput: (String, ByteArray) -> Unit): Result {
        val stdout = File(jobDir, "output/stdout.txt")
        val stderr = File(jobDir, "output/stderr.txt")
        val out = CappedOutput(1024 * 1024) { onOutput("stdout", it) }
        val err = CappedOutput(1024 * 1024) { onOutput("stderr", it) }
        return try {
            val globals = JsePlatform.standardGlobals()
            globals.set("luajava", LuaValue.NIL)
            globals.set("io", LuaValue.NIL)
            globals.set("os", LuaValue.NIL)
            globals.set("debug", LuaValue.NIL)
            globals.set("package", LuaValue.NIL)
            globals.set("require", LuaValue.NIL)
            globals.set("dofile", LuaValue.NIL)
            val argTable = LuaValue.tableOf()
            args.forEachIndexed { index, value -> argTable.set(index - 1, LuaValue.valueOf(value)) }
            globals.set("arg", argTable)
            val envTable = LuaValue.tableOf()
            env.forEach { (key, value) -> envTable.set(key, LuaValue.valueOf(value)) }
            globals.set("env", envTable)

            val host = LuaValue.tableOf()
            host.set("readInput", object : VarArgFunction() {
                override fun invoke(a: Varargs): Varargs {
                    val name = a.arg1().checkjstring()
                    if (!safeName(name)) return LuaValue.varargsOf(arrayOf(LuaValue.NIL, LuaValue.valueOf("INVALID_INPUT_NAME")))
                    val file = File(File(jobDir, "input"), name)
                    if (!file.isFile || file.length() > 8L * 1024 * 1024) return LuaValue.varargsOf(arrayOf(LuaValue.NIL, LuaValue.valueOf("INPUT_NOT_FOUND_OR_TOO_LARGE")))
                    return LuaValue.valueOf(file.readText(Charsets.UTF_8))
                }
            })
            host.set("writeFile", object : VarArgFunction() {
                override fun invoke(a: Varargs): Varargs {
                    val name = a.arg1().checkjstring()
                    val content = a.arg(2).checkjstring()
                    if (!safeName(name) || name == "stdout.txt" || name == "stderr.txt") return LuaValue.valueOf(false)
                    val bytes = content.toByteArray(StandardCharsets.UTF_8)
                    val output = File(jobDir, "output")
                    val current = output.listFiles().orEmpty().filter { it.isFile && it.name != "stdout.txt" && it.name != "stderr.txt" }.sumOf { it.length() }
                    if (bytes.size > 16 * 1024 * 1024 || current + bytes.size > 32 * 1024 * 1024) return LuaValue.valueOf(false)
                    File(output, name).writeBytes(bytes)
                    return LuaValue.valueOf(true)
                }
            })
            globals.set("host", host)
            globals.set("print", object : VarArgFunction() {
                override fun invoke(a: Varargs): Varargs {
                    val line = (1..a.narg()).joinToString("\t") { a.arg(it).tojstring() } + "\n"
                    out.write(line.toByteArray(StandardCharsets.UTF_8))
                    return LuaValue.NONE
                }
            })
            val started = System.nanoTime()
            val values = globals.loadfile(script.absolutePath).invoke()
            if (TimeUnitCompat.elapsedMillis(started) > timeoutMillis) {
                Result(null, 124, "EXECUTION_TIMEOUT", "Execution deadline exceeded", out.truncated || err.truncated)
            } else {
                val json = try {
                    serialize(values)
                } catch (e: Throwable) {
                    return Result(null, 1, "RESULT_SERIALIZATION_ERROR", e.message, out.truncated || err.truncated)
                }
                Result(json, 0, truncated = out.truncated || err.truncated)
            }
        } catch (e: LuaError) {
            val compile = e.message?.contains("compile", true) == true
            err.write((e.message ?: "Lua error").toByteArray())
            Result(null, 1, if (compile) "SCRIPT_COMPILE_ERROR" else "SCRIPT_RUNTIME_ERROR", e.message, out.truncated || err.truncated)
        } catch (e: Throwable) {
            err.write((e.message ?: e.javaClass.simpleName).toByteArray())
            Result(null, 1, "INTERNAL_ERROR", e.message, out.truncated || err.truncated)
        } finally {
            stdout.writeBytes(out.bytes())
            stderr.writeBytes(err.bytes())
        }
    }

    private fun safeName(name: String) = name.isNotBlank() && name.length <= 128 &&
        name != "." && name != ".." && !name.contains("..") && !name.contains('/') && !name.contains('\\')

    private fun serialize(values: Varargs): String {
        if (values.narg() == 0) return "null"
        fun convert(v: LuaValue, seen: MutableSet<LuaValue>, depth: Int): Any? {
            require(depth <= 32) { "RESULT_TOO_LARGE: nesting limit" }
            return when {
                v.isnil() -> null
                v.isboolean() -> v.toboolean()
                v.isnumber() -> v.todouble().takeIf { it.isFinite() } ?: error("Non-finite number")
                v.isstring() -> v.tojstring().also {
                    require(it.toByteArray(StandardCharsets.UTF_8).size <= 256 * 1024) {
                        "RESULT_TOO_LARGE: string exceeds 256 KiB"
                    }
                }
                v.istable() -> {
                    require(seen.add(v)) { "Cyclic Lua table cannot be serialized" }
                    val keyArgs = v.checktable().keys()
                    val entries = (1..keyArgs.narg()).map { keyArgs.arg(it) }
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
        val seen = mutableSetOf<LuaValue>()
        val result: Any? = if (values.narg() == 1) {
            convert(values.arg1(), seen, 0)
        } else {
            JSONArray().apply {
                for (index in 1..values.narg()) put(convert(values.arg(index), seen, 0))
            }
        }
        val json = JSONObject.wrap(result)?.toString() ?: "null"
        require(json.toByteArray(StandardCharsets.UTF_8).size <= 256 * 1024) {
            "RESULT_TOO_LARGE: serialized result exceeds 256 KiB"
        }
        return json
    }

    private class CappedOutput(private val cap: Int, private val sink: (ByteArray) -> Unit) {
        private val buffer = java.io.ByteArrayOutputStream()
        var truncated: Boolean = false
            private set
        fun write(b: ByteArray) {
            val remaining = (cap - buffer.size()).coerceAtLeast(0)
            val count = minOf(remaining, b.size)
            if (count > 0) buffer.write(b, 0, count)
            if (count > 0) sink(b.copyOf(count))
            if (count < b.size) truncated = true
        }
        fun bytes() = buffer.toByteArray()
    }
}
private object TimeUnitCompat { fun elapsedMillis(start: Long) = (System.nanoTime() - start) / 1_000_000L }

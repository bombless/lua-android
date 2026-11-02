package com.bombless.luaandroid.core

/**
 * Generates the `spec.lua` consumed by the runner's bootstrap. Every string is emitted with plain
 * ASCII plus decimal escapes (`\ddd`), so arbitrary bytes (quotes, newlines, NUL, UTF-8) can never
 * break out of the literal.
 */
object LuaLiteral {
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == ' ' || ch == '_' || ch == '.' || ch == '/' || ch == ':' || ch == '-') {
                sb.append(ch)
            } else {
                sb.append('\\').append(c.toString().padStart(3, '0'))
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun spec(jobId: String, scriptName: String, args: List<String>, env: Map<String, String>, workingDirectory: String): String {
        val sb = StringBuilder()
        sb.append("return {\n")
        sb.append("  apiVersion = 1,\n")
        sb.append("  jobId = ").append(quote(jobId)).append(",\n")
        sb.append("  scriptName = ").append(quote(scriptName)).append(",\n")
        sb.append("  workdir = ").append(quote(workingDirectory)).append(",\n")
        sb.append("  args = {").append(args.joinToString(", ") { quote(it) }).append("},\n")
        sb.append("  env = {").append(env.entries.joinToString(", ") { "[" + quote(it.key) + "] = " + quote(it.value) }).append("},\n")
        sb.append("}\n")
        return sb.toString()
    }
}

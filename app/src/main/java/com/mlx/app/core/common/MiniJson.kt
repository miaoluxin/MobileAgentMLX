package com.mlx.app.core.common

/**
 * 轻量 JSON 解析/序列化（纯 Kotlin，可在 JVM 单元测试中运行）。
 * 覆盖本应用需要的全部场景：对象/数组/字符串/数字/布尔/null。
 * 核心引擎统一使用本实现，不依赖 Android 的 org.json。
 */
object MiniJson {

    fun parse(text: String): Any? {
        return try {
            val p = Parser(text)
            val v = p.parseValue()
            p.skipWs()
            v
        } catch (e: JsonException) {
            null // 非法 JSON 一律返回 null（调用方按"无效"处理）
        }
    }

    private class JsonException(msg: String) : RuntimeException(msg)

    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(value, sb)
        return sb.toString()
    }

    fun toMap(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

    fun optLong(map: Map<String, Any?>, key: String): Long = (map[key] as? Number)?.toLong() ?: 0L

    fun optInt(map: Map<String, Any?>, key: String): Int = (map[key] as? Number)?.toInt() ?: 0

    fun optBool(map: Map<String, Any?>, key: String): Boolean = map[key] as? Boolean ?: false

    private fun write(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(value, sb)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long -> sb.append(value.toString())
            is Double, is Float -> sb.append(value.toString())
            is Number -> sb.append(value.toDouble().toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k.toString(), sb)
                    sb.append(':')
                    write(v, sb)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(v, sb)
                }
                sb.append(']')
            }
            else -> writeString(value.toString(), sb)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWs()
            return when {
                pos >= text.length -> null
                text[pos] == '{' -> parseObject()
                text[pos] == '[' -> parseArray()
                text[pos] == '"' -> parseString()
                text.startsWith("true", pos) -> { pos += 4; true }
                text.startsWith("false", pos) -> { pos += 5; false }
                text.startsWith("null", pos) -> { pos += 4; null }
                else -> parseNumber()
            }
        }

        fun skipWs() { while (pos < text.length && text[pos].isWhitespace()) pos++ }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < text.length && text[pos] == '}') { pos++; return map }
            while (pos < text.length) {
                skipWs()
                val key = parseString()
                skipWs()
                if (pos < text.length && text[pos] == ':') pos++
                val v = parseValue()
                map[key] = v
                skipWs()
                if (pos < text.length && text[pos] == ',') { pos++; continue }
                if (pos < text.length && text[pos] == '}') { pos++; return map }
            }
            throw JsonException("unterminated object") // 严格：未闭合即非法
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (pos < text.length && text[pos] == ']') { pos++; return list }
            while (pos < text.length) {
                list.add(parseValue())
                skipWs()
                if (pos < text.length && text[pos] == ',') { pos++; continue }
                if (pos < text.length && text[pos] == ']') { pos++; return list }
            }
            throw JsonException("unterminated array") // 严格：未闭合即非法
        }

        private fun parseString(): String {
            val sb = StringBuilder()
            pos++ // "
            while (pos < text.length) {
                val c = text[pos]
                when {
                    c == '"' -> { pos++; return sb.toString() }
                    c == '\\' -> {
                        pos++
                        if (pos >= text.length) break
                        when (val e = text[pos]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('')
                            'u' -> {
                                if (pos + 4 < text.length) {
                                    val hex = text.substring(pos + 1, pos + 5)
                                    sb.append(hex.toInt(16).toChar())
                                    pos += 4
                                }
                            }
                            else -> sb.append(e)
                        }
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
            throw JsonException("unterminated string") // 严格：未闭合即非法
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
            if (pos == start) throw JsonException("expected number")
            return text.substring(start, pos).toDoubleOrNull()
                ?: throw JsonException("invalid number")
        }
    }
}

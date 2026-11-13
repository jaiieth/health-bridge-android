package dev.healthbridge

/** JSON.parse and JSON.stringify semantics for receiver accounting and strict ACK syntax. */
internal object EcmaJson {
    fun quote(value: String): String = buildString {
        append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> when {
                    c < ' ' -> append("\\u%04x".format(c.code))
                    c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                        append(c)
                        append(value[++i])
                    }
                    c.isSurrogate() -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            i++
        }
        append('"')
    }

    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Number -> ecmaNumber(value.toDouble())
        is List<*> -> value.joinToString(",", "[", "]") { stringify(it) }
        is Map<*, *> -> {
            val keys = value.keys.map { it as String }
            val numeric = keys.mapNotNull { key -> arrayIndex(key)?.let { key to it } }.sortedBy { it.second }.map { it.first }
            val ordered = numeric + keys.filter { arrayIndex(it) == null }
            ordered.joinToString(",", "{", "}") { quote(it) + ":" + stringify(value[it]) }
        }
        else -> error("UNSUPPORTED_JSON_VALUE")
    }

    private fun arrayIndex(key: String): Long? = key.toLongOrNull()?.takeIf { it >= 0 && it < 4_294_967_295L && it.toString() == key }
    fun parse(text: String): Any? = Parser(text).parse()

    private class Parser(private val text: String) {
        private var index = 0
        fun parse(): Any? {
            val result = value(0)
            whitespace()
            require(index == text.length) { "INVALID_JSON" }
            return result
        }
        private fun whitespace() { while (index < text.length && text[index] in " \t\r\n") index++ }
        private fun take(c: Char): Boolean {
            if (index < text.length && text[index] == c) { index++; return true }
            return false
        }
        private fun expect(c: Char) { require(take(c)) { "INVALID_JSON" } }
        private fun value(depth: Int): Any? {
            require(depth <= 128) { "JSON_TOO_DEEP" }
            whitespace()
            require(index < text.length) { "INVALID_JSON" }
            return when (text[index]) {
                '"' -> string()
                '{' -> {
                    index++
                    val result = linkedMapOf<String, Any?>()
                    whitespace()
                    if (!take('}')) {
                        do {
                            whitespace()
                            val key = string()
                            require(!result.containsKey(key)) { "DUPLICATE_JSON_KEY" }
                            whitespace(); expect(':')
                            result[key] = value(depth + 1)
                            whitespace()
                        } while (take(','))
                        expect('}')
                    }
                    result
                }
                '[' -> {
                    index++
                    val result = mutableListOf<Any?>()
                    whitespace()
                    if (!take(']')) {
                        do { result += value(depth + 1); whitespace() } while (take(','))
                        expect(']')
                    }
                    result
                }
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }
        private fun literal(token: String, value: Any?): Any? {
            require(text.startsWith(token, index)) { "INVALID_JSON" }
            index += token.length
            return value
        }
        private fun string(): String {
            expect('"')
            return buildString {
                while (true) {
                    require(index < text.length) { "INVALID_JSON" }
                    val c = text[index++]
                    if (c == '"') break
                    require(c >= ' ') { "INVALID_JSON" }
                    if (c != '\\') append(c) else {
                        require(index < text.length) { "INVALID_JSON" }
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> append(escaped)
                            'b' -> append('\b')
                            'f' -> append('\u000c')
                            'n' -> append('\n')
                            'r' -> append('\r')
                            't' -> append('\t')
                            'u' -> {
                                require(index + 4 <= text.length) { "INVALID_JSON" }
                                val code = text.substring(index, index + 4)
                                require(code.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "INVALID_JSON" }
                                append(code.toInt(16).toChar()); index += 4
                            }
                            else -> error("INVALID_JSON")
                        }
                    }
                }
            }
        }
        private fun number(): Double {
            val start = index
            take('-')
            if (!take('0')) {
                require(index < text.length && text[index] in '1'..'9') { "INVALID_JSON" }
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (take('.')) digits()
            if (take('e') || take('E')) { if (!take('+')) take('-'); digits() }
            return text.substring(start, index).toDouble()
        }
        private fun digits() {
            val start = index
            while (index < text.length && text[index] in '0'..'9') index++
            require(index > start) { "INVALID_JSON" }
        }
    }
}

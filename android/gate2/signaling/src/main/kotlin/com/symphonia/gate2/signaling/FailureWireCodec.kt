package com.symphonia.gate2.signaling

import com.symphonia.gate2.contracts.FailureReport
import com.symphonia.gate2.contracts.GATE2_PROTOCOL_VERSION
import com.symphonia.gate2.contracts.Gate2Failure

object FailureWireCodec {
    private const val MAX_PAYLOAD_CHARS = 8_192
    private val expectedKeys = setOf("schemaVersion", "code", "category", "retryable", "occurredAtEpochMs", "operation")

    fun encode(report: FailureReport): String = buildString {
        append('{')
        append("\"schemaVersion\":").append(GATE2_PROTOCOL_VERSION)
        append(",\"code\":").append(jsonString(report.failure.wireCode))
        append(",\"category\":").append(jsonString(report.failure.category.name.lowercase()))
        append(",\"retryable\":").append(report.failure.retryable)
        append(",\"occurredAtEpochMs\":").append(report.occurredAtEpochMs)
        append(",\"operation\":").append(jsonString(report.operation))
        append('}')
    }

    fun decode(json: String): FailureReport {
        require(json.length <= MAX_PAYLOAD_CHARS) { "Failure payload is too large" }
        val fields = JsonObjectParser(json).parse()
        require(fields.keys == expectedKeys) { "Unexpected failure payload fields" }
        require(fields.string("schemaVersion").toIntStrict() == GATE2_PROTOCOL_VERSION) {
            "Unsupported failure schema version"
        }
        val failure = Gate2Failure.fromWireCode(fields.stringValue("code"))
        require(fields.stringValue("category") == failure.category.name.lowercase()) {
            "Failure category does not match code"
        }
        require(fields.string("retryable").toBooleanStrict() == failure.retryable) {
            "Failure retryability does not match code"
        }
        val occurredAt = fields.string("occurredAtEpochMs").toLongStrict()
        require(occurredAt >= 0) { "Failure timestamp must be non-negative" }
        return FailureReport(failure, occurredAt, fields.stringValue("operation"))
    }

    private fun Map<String, JsonValue>.string(key: String): String =
        (getValue(key) as? JsonValue.Primitive)?.value
            ?: throw IllegalArgumentException("Expected primitive JSON value for $key")

    private fun Map<String, JsonValue>.stringValue(key: String): String =
        (getValue(key) as? JsonValue.StringValue)?.value
            ?: throw IllegalArgumentException("Expected JSON string for $key")

    private fun String.toIntStrict(): Int {
        require(matches(Regex("0|[1-9][0-9]*"))) { "Expected non-negative integer" }
        return toInt()
    }

    private fun String.toLongStrict(): Long {
        require(matches(Regex("0|[1-9][0-9]*"))) { "Expected non-negative integer" }
        return toLong()
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private sealed interface JsonValue {
        data class StringValue(val value: String) : JsonValue
        data class Primitive(val value: String) : JsonValue
    }

    private class JsonObjectParser(private val text: String) {
        private var index = 0

        fun parse(): Map<String, JsonValue> {
            whitespace()
            expect('{')
            whitespace()
            val fields = linkedMapOf<String, JsonValue>()
            if (peek('}')) {
                index++
            } else {
                while (true) {
                    val key = string()
                    require(fields[key] == null) { "Duplicate JSON key: $key" }
                    whitespace()
                    expect(':')
                    whitespace()
                    fields[key] = value()
                    whitespace()
                    if (peek('}')) {
                        index++
                        break
                    }
                    expect(',')
                    whitespace()
                    require(!peek('}')) { "Trailing JSON comma is not allowed" }
                }
            }
            whitespace()
            require(index == text.length) { "Unexpected content after JSON object" }
            return fields
        }

        private fun value(): JsonValue = if (peek('"')) {
            JsonValue.StringValue(string())
        } else {
            val start = index
            while (index < text.length && text[index] !in charArrayOf(',', '}', ' ', '\t', '\r', '\n')) index++
            require(index > start) { "Expected JSON value" }
            JsonValue.Primitive(text.substring(start, index))
        }

        private fun string(): String {
            expect('"')
            val result = StringBuilder()
            while (index < text.length) {
                val character = text[index++]
                when {
                    character == '"' -> return result.toString()
                    character.code < 0x20 -> throw IllegalArgumentException("Unescaped control character in JSON string")
                    character != '\\' -> result.append(character)
                    else -> result.append(escape())
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun escape(): Char {
            require(index < text.length) { "Invalid JSON escape" }
            return when (val escaped = text[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    require(index + 4 <= text.length) { "Incomplete Unicode escape" }
                    val digits = text.substring(index, index + 4)
                    require(digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Invalid Unicode escape" }
                    index += 4
                    digits.toInt(16).toChar()
                }
                else -> throw IllegalArgumentException("Unsupported JSON escape: $escaped")
            }
        }

        private fun whitespace() {
            while (index < text.length && text[index] in charArrayOf(' ', '\t', '\r', '\n')) index++
        }

        private fun expect(character: Char) {
            require(index < text.length && text[index] == character) { "Expected '$character'" }
            index++
        }

        private fun peek(character: Char): Boolean = index < text.length && text[index] == character
    }
}

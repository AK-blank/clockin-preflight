package com.clockin.preflight.data

/**
 * A tiny, dependency-free JSON reader for the data layer.
 *
 * ### Why hand-rolled instead of kotlinx.serialization
 * `kotlinx-serialization-json:1.9.0` *is* present in the build, but only on the **runtime**
 * classpath: `web3-solana-jvm:0.3.1` lists it under `jvmRuntimeElements-published`, not
 * `jvmApiElements-published`. On top of that, the `org.jetbrains.kotlin.plugin.serialization`
 * compiler plugin is not applied to `:app`, so `@Serializable` codegen would not run even if the
 * artifact were on the compile classpath. `org.json` is a stub on the plain-JVM unit-test
 * classpath, so relying on it would make hermetic tests assert against fake behaviour.
 *
 * This reader therefore has **zero dependencies**, behaves identically in unit tests and on
 * device, and never throws for absent fields — a missing key is `null`, not an exception, which
 * matters because both risk APIs add and drop fields frequently.
 *
 * Numbers keep their original text in [JsonNum.raw] so 64-bit values survive untouched:
 * Solana returns `rentEpoch: 18446744073709551615` (u64 max), which overflows a signed `Long`.
 */
sealed interface JsonValue {

    /** JSON `null`, or the value of an absent key once [JsonObj.get] has run. */
    object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    /** A number. [raw] is the literal source text, preserved for full 64-bit fidelity. */
    data class Num(val raw: String) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
}

/** Thrown only for genuinely malformed input, never for missing fields. */
class JsonParseException(
    message: String,
    val index: Int,
) : Exception("$message at character $index")

// ---------------------------------------------------------------------------------------------
// Lenient accessors. Every one of these returns null rather than throwing, so callers can treat
// "field missing" and "field has the wrong type" identically — the correct posture when talking
// to third-party APIs that change shape without notice.
// ---------------------------------------------------------------------------------------------

fun JsonValue?.asObj(): JsonValue.Obj? = this as? JsonValue.Obj

fun JsonValue?.asArr(): List<JsonValue>? = (this as? JsonValue.Arr)?.items

fun JsonValue?.asStr(): String? = (this as? JsonValue.Str)?.value

fun JsonValue?.asBool(): Boolean? = (this as? JsonValue.Bool)?.value

fun JsonValue?.asDouble(): Double? = (this as? JsonValue.Num)?.raw?.toDoubleOrNull()

/** Returns null when the number does not fit a signed 64-bit integer (e.g. a u64 above 2^63-1). */
fun JsonValue?.asLong(): Long? = (this as? JsonValue.Num)?.raw?.toLongOrNull()

/** Use for unsigned 64-bit fields such as Solana's `rentEpoch`. */
fun JsonValue?.asULong(): ULong? = (this as? JsonValue.Num)?.raw?.toULongOrNull()

fun JsonValue?.asInt(): Int? = asLong()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }

/** `true`/`false` as JSON booleans, but also tolerates GoPlus' `"1"`/`"0"` string convention. */
fun JsonValue?.asFlag(): Boolean? = when (this) {
    is JsonValue.Bool -> value
    is JsonValue.Str -> when (value.trim()) {
        "1", "true", "TRUE" -> true
        "0", "false", "FALSE" -> false
        else -> null
    }
    is JsonValue.Num -> raw.toLongOrNull()?.let { it != 0L }
    else -> null
}

operator fun JsonValue?.get(key: String): JsonValue? = asObj()?.fields?.get(key)

/** Convenience typed reads on an object. Each returns null when the key is absent or mistyped. */
fun JsonValue.Obj.obj(key: String): JsonValue.Obj? = fields[key].asObj()

fun JsonValue.Obj.arr(key: String): List<JsonValue>? = fields[key].asArr()

fun JsonValue.Obj.str(key: String): String? = fields[key].asStr()

fun JsonValue.Obj.long(key: String): Long? = fields[key].asLong()

fun JsonValue.Obj.uLong(key: String): ULong? = fields[key].asULong()

fun JsonValue.Obj.int(key: String): Int? = fields[key].asInt()

fun JsonValue.Obj.double(key: String): Double? = fields[key].asDouble()

fun JsonValue.Obj.bool(key: String): Boolean? = fields[key].asBool()

fun JsonValue.Obj.flag(key: String): Boolean? = fields[key].asFlag()

fun JsonValue.Obj.has(key: String): Boolean = fields.containsKey(key)

/** Non-blank strings only — APIs happily return `""` where a value is expected. */
fun JsonValue.Obj.nonBlankStr(key: String): String? = str(key)?.takeIf { it.isNotBlank() }

/**
 * A 64-bit integer that the API may send either as a JSON number or as a numeric string.
 *
 * GoPlus is inconsistent about this within a single object: `holder_count` arrives as the string
 * `"9193555"` while `is_locked` in the same payload arrives as the number `0`. Reading only one
 * shape silently loses data.
 */
fun JsonValue.Obj.longLenient(key: String): Long? = long(key) ?: str(key)?.trim()?.toLongOrNull()

/** A number sent as either a JSON number or a numeric string. GoPlus `percent` is `"0.1415"`. */
fun JsonValue.Obj.doubleLenient(key: String): Double? =
    double(key) ?: str(key)?.trim()?.toDoubleOrNull()

/** An integer sent as either a JSON number or a numeric string. */
fun JsonValue.Obj.intLenient(key: String): Int? =
    longLenient(key)?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }

/** Every string in the array at [key], skipping non-string entries. */
fun JsonValue.Obj.stringList(key: String): List<String> =
    arr(key).orEmpty().mapNotNull { it.asStr() }

/** Objects in the array at [key], skipping non-object entries. */
fun JsonValue.Obj.objList(key: String): List<JsonValue.Obj> =
    arr(key).orEmpty().mapNotNull { it.asObj() }

/**
 * Renders any value as a short, human-readable diagnostic. Used to put raw RPC error payloads
 * into typed failures without leaking a wall of JSON into the UI.
 */
fun JsonValue.compact(): String = when (this) {
    is JsonValue.Null -> "null"
    is JsonValue.Bool -> value.toString()
    is JsonValue.Num -> raw
    is JsonValue.Str -> value
    is JsonValue.Arr -> items.joinToString(",", "[", "]") { it.compact() }
    is JsonValue.Obj -> fields.entries.joinToString(",", "{", "}") { "${it.key}=${it.value.compact()}" }
}

/** Strict, non-recursive-descent-free parser: nesting is bounded by [MAX_DEPTH]. */
object JsonParser {

    /** Deep enough for any real API payload, shallow enough to make hostile input fail fast. */
    private const val MAX_DEPTH = 64

    fun parse(text: String): JsonValue {
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        val value = cursor.parseValue(0)
        cursor.skipWhitespace()
        if (!cursor.atEnd) throw JsonParseException("unexpected trailing content", cursor.index)
        return value
    }

    /** Parses and asserts the top level is an object — the only shape both APIs return. */
    fun parseObject(text: String): JsonValue.Obj {
        val value = parse(text)
        return value as? JsonValue.Obj
            ?: throw JsonParseException("expected a JSON object at the top level", 0)
    }

    private class Cursor(private val text: String) {
        var index = 0
            private set

        val atEnd: Boolean get() = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) index++
        }

        private fun peek(): Char {
            if (atEnd) throw JsonParseException("unexpected end of input", index)
            return text[index]
        }

        private fun expect(expected: Char) {
            if (atEnd || text[index] != expected) {
                throw JsonParseException("expected '$expected' but found ${describeHere()}", index)
            }
            index++
        }

        private fun describeHere(): String =
            if (atEnd) "end of input" else "'${text[index]}'"

        fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw JsonParseException("nesting deeper than $MAX_DEPTH levels", index)
            return when (val c = peek()) {
                '{' -> parseObjectBody(depth)
                '[' -> parseArrayBody(depth)
                '"' -> JsonValue.Str(parseString())
                't' -> parseKeyword("true", JsonValue.Bool(true))
                'f' -> parseKeyword("false", JsonValue.Bool(false))
                'n' -> parseKeyword("null", JsonValue.Null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else {
                    throw JsonParseException("unexpected character '$c'", index)
                }
            }
        }

        private fun parseKeyword(word: String, value: JsonValue): JsonValue {
            if (index + word.length > text.length || text.substring(index, index + word.length) != word) {
                throw JsonParseException("expected '$word'", index)
            }
            index += word.length
            return value
        }

        private fun parseObjectBody(depth: Int): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd && text[index] == '}') {
                index++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                fields[key] = parseValue(depth + 1)
                skipWhitespace()
                when {
                    atEnd -> throw JsonParseException("unterminated object", index)
                    text[index] == ',' -> index++
                    text[index] == '}' -> {
                        index++
                        return JsonValue.Obj(fields)
                    }
                    else -> throw JsonParseException("expected ',' or '}' but found ${describeHere()}", index)
                }
            }
        }

        private fun parseArrayBody(depth: Int): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (!atEnd && text[index] == ']') {
                index++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items.add(parseValue(depth + 1))
                skipWhitespace()
                when {
                    atEnd -> throw JsonParseException("unterminated array", index)
                    text[index] == ',' -> index++
                    text[index] == ']' -> {
                        index++
                        return JsonValue.Arr(items)
                    }
                    else -> throw JsonParseException("expected ',' or ']' but found ${describeHere()}", index)
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd) throw JsonParseException("unterminated string", index)
                when (val c = text[index++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd) throw JsonParseException("unterminated escape sequence", index)
                        when (val esc = text[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) {
                                    throw JsonParseException("truncated \\u escape", index)
                                }
                                val hex = text.substring(index, index + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonParseException("invalid \\u escape '$hex'", index)
                                // Surrogate pairs arrive as two consecutive \u escapes and combine
                                // naturally because Kotlin strings are UTF-16.
                                sb.append(code.toChar())
                                index += 4
                            }
                            else -> throw JsonParseException("invalid escape '\\$esc'", index - 1)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = index
            if (!atEnd && text[index] == '-') index++
            if (atEnd || !text[index].isDigit()) throw JsonParseException("malformed number", start)
            while (index < text.length && text[index].isDigit()) index++
            if (index < text.length && text[index] == '.') {
                index++
                if (index >= text.length || !text[index].isDigit()) throw JsonParseException("malformed fraction", index)
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                if (index >= text.length || !text[index].isDigit()) throw JsonParseException("malformed exponent", index)
                while (index < text.length && text[index].isDigit()) index++
            }
            return JsonValue.Num(text.substring(start, index))
        }
    }
}

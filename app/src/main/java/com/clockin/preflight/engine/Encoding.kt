package com.clockin.preflight.engine

/**
 * Base58 (Bitcoin/Solana alphabet) codec, hand-rolled so the engine has zero external dependencies.
 *
 * Never throws: [decode] returns `null` for any input containing a character outside the alphabet.
 */
object Base58 {

    const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private val INDEX = IntArray(128) { -1 }.also { table ->
        for (i in ALPHABET.indices) table[ALPHABET[i].code] = i
    }

    /** Encodes [bytes] as base58. Leading zero bytes become leading `'1'` characters. */
    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var zeros = 0
        while (zeros < bytes.size && bytes[zeros] == 0.toByte()) zeros++

        // Little-endian base-58 digit accumulator.
        val digits = ArrayList<Int>(bytes.size * 2)
        for (i in zeros until bytes.size) {
            var carry = bytes[i].toInt() and 0xFF
            for (d in digits.indices) {
                val value = digits[d] * 256 + carry
                digits[d] = value % 58
                carry = value / 58
            }
            while (carry > 0) {
                digits.add(carry % 58)
                carry /= 58
            }
        }

        val sb = StringBuilder(zeros + digits.size)
        repeat(zeros) { sb.append('1') }
        for (i in digits.indices.reversed()) sb.append(ALPHABET[digits[i]])
        return sb.toString()
    }

    /** Decodes base58 text, or returns `null` when any character is not in the alphabet. */
    fun decode(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        var zeros = 0
        while (zeros < text.length && text[zeros] == '1') zeros++

        val out = ArrayList<Byte>(text.length)
        for (i in zeros until text.length) {
            val c = text[i]
            val digit = if (c.code < 128) INDEX[c.code] else -1
            if (digit < 0) return null
            var carry = digit
            for (j in out.indices) {
                val value = (out[j].toInt() and 0xFF) * 58 + carry
                out[j] = (value and 0xFF).toByte()
                carry = value ushr 8
            }
            while (carry > 0) {
                out.add((carry and 0xFF).toByte())
                carry = carry ushr 8
            }
        }

        val result = ByteArray(zeros + out.size)
        for (i in out.indices) result[zeros + out.size - 1 - i] = out[i]
        return result
    }

    /** Encodes a 32-byte public key, or returns a `?`-prefixed preview for odd-length input. */
    fun encodeKey(bytes: ByteArray): String = encode(bytes)
}

/**
 * Standard and URL-safe Base64, hand-rolled. [decode] is lenient about ASCII whitespace
 * (RPC payloads are often wrapped) and returns `null` for anything else that is malformed —
 * it never throws.
 */
object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val INDEX = IntArray(128) { -1 }.also { table ->
        for (i in ALPHABET.indices) table[ALPHABET[i].code] = i
        table['-'.code] = 62
        table['_'.code] = 63
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n ushr 18) and 0x3F])
            sb.append(ALPHABET[(n ushr 12) and 0x3F])
            sb.append(ALPHABET[(n ushr 6) and 0x3F])
            sb.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[(n ushr 18) and 0x3F])
                sb.append(ALPHABET[(n ushr 12) and 0x3F])
                sb.append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[(n ushr 18) and 0x3F])
                sb.append(ALPHABET[(n ushr 12) and 0x3F])
                sb.append(ALPHABET[(n ushr 6) and 0x3F])
                sb.append('=')
            }
        }
        return sb.toString()
    }

    /** Decodes standard or URL-safe base64. Returns `null` on malformed input. */
    fun decode(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream((text.length / 4) * 3 + 3)
        var buffer = 0
        var bits = 0
        var seenPadding = false
        var chars = 0
        for (ch in text) {
            when {
                ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t' -> continue
                ch == '=' -> {
                    seenPadding = true
                    continue
                }
                seenPadding -> return null // data after padding
                else -> {
                    val digit = if (ch.code < 128) INDEX[ch.code] else -1
                    if (digit < 0) return null
                    buffer = (buffer shl 6) or digit
                    bits += 6
                    chars++
                    if (bits >= 8) {
                        bits -= 8
                        out.write((buffer ushr bits) and 0xFF)
                    }
                }
            }
        }
        // A single leftover character can never carry a whole byte, so it must be padding.
        if (chars % 4 == 1) return null
        // Non-zero bits left over mean the payload was not canonically encoded.
        if (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0) return null
        return out.toByteArray()
    }
}

/** Hex helpers — used for evidence strings and test fixtures. */
object Hex {

    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[i * 2], 16)
            val lo = Character.digit(text[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

/** Little-endian integer readers used by the Solana wire format. */
object LittleEndian {
    fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    fun u32(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }

    fun u64(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }
}

/**
 * Solana `shortvec` (compact-u16) length encoding: 7 bits per byte, little-endian,
 * with the high bit set on every byte except the last. Values above 0xFFFF are rejected.
 */
object ShortVec {

    const val MAX_VALUE = 0xFFFF
    const val MAX_BYTES = 3

    data class Decoded(val value: Int, val nextOffset: Int) {
        val bytesConsumed: Int get() = nextOffset
    }

    fun encode(value: Int): ByteArray {
        require(value in 0..MAX_VALUE) { "shortvec value $value is out of range" }
        val out = ArrayList<Byte>(MAX_BYTES)
        var remaining = value
        while (true) {
            var byte = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining == 0) {
                out.add(byte.toByte())
                return out.toByteArray()
            }
            byte = byte or 0x80
            out.add(byte.toByte())
        }
    }

    /**
     * Decodes a shortvec at [offset], or returns `null` when the bytes run out or the encoding
     * uses more than [MAX_BYTES] bytes.
     */
    fun decode(bytes: ByteArray, offset: Int = 0): Decoded? {
        var value = 0
        var shift = 0
        var position = offset
        var consumed = 0
        while (consumed < MAX_BYTES) {
            if (position >= bytes.size) return null
            val byte = bytes[position].toInt() and 0xFF
            position++
            consumed++
            value = value or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) {
                if (value > MAX_VALUE) return null
                return Decoded(value, position)
            }
            shift += 7
        }
        return null // continuation bit still set after MAX_BYTES
    }
}

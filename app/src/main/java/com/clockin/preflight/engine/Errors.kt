package com.clockin.preflight.engine

/**
 * A typed decoding failure.
 *
 * The decoder guarantees it **never throws** for caller-supplied bytes: every failure path ends
 * here, as a value the caller can branch on and show to a human.
 */
sealed class DecodeError {

    /** Stable machine-readable code, safe to assert on in tests and to log. */
    abstract val code: String

    /** Human-readable explanation, safe to surface in the UI. */
    abstract val message: String

    /** Byte offset inside the transaction blob where decoding gave up, when known. */
    open val offset: Int? get() = null

    data class EmptyInput(
        override val message: String = "no transaction bytes were supplied",
    ) : DecodeError() {
        override val code: String get() = "EMPTY_INPUT"
    }

    data class InvalidBase64(
        override val message: String,
    ) : DecodeError() {
        override val code: String get() = "INVALID_BASE64"
    }

    data class Truncated(
        override val message: String,
        override val offset: Int,
        val needed: Int = 0,
        val remaining: Int = 0,
    ) : DecodeError() {
        override val code: String get() = "TRUNCATED"
    }

    data class ValueOverflow(
        override val message: String,
        override val offset: Int,
    ) : DecodeError() {
        override val code: String get() = "VALUE_OVERFLOW"
    }

    data class UnsupportedMessageVersion(
        val version: Int,
    ) : DecodeError() {
        override val code: String get() = "UNSUPPORTED_MESSAGE_VERSION"
        override val message: String
            get() = "message version v$version is not supported (this build understands legacy and v0)"
    }

    data class UnsupportedMessageLayout(
        override val message: String,
    ) : DecodeError() {
        override val code: String get() = "UNSUPPORTED_MESSAGE_LAYOUT"
    }

    data class BadInstruction(
        override val message: String,
        override val offset: Int,
    ) : DecodeError() {
        override val code: String get() = "BAD_INSTRUCTION"
    }

    data class TrailingBytes(
        val count: Int,
    ) : DecodeError() {
        override val code: String get() = "TRAILING_BYTES"
        override val message: String
            get() = "transaction blob has $count trailing byte(s) that do not belong to the message"
    }

    data class Malformed(
        override val message: String,
    ) : DecodeError() {
        override val code: String get() = "MALFORMED"
    }
}

/** Result of decoding a raw transaction: either a [DecodedTransaction] or a typed [DecodeError]. */
sealed class DecodeResult {

    data class Success(val transaction: DecodedTransaction) : DecodeResult()

    data class Failure(val error: DecodeError) : DecodeResult()

    val isSuccess: Boolean get() = this is Success

    fun transactionOrNull(): DecodedTransaction? = (this as? Success)?.transaction

    fun errorOrNull(): DecodeError? = (this as? Failure)?.error
}

/** Internal sentinel used while decoding; converted into a [DecodeError] at the API boundary. */
internal class DecodeFailure(val error: DecodeError) : Exception(error.message)

/**
 * Bounds-checked cursor over a transaction blob.
 *
 * All readers signal failure by throwing [DecodeFailure]; the only public entry points
 * ([TransactionDecoder.decode]) catch it, so nothing escapes to the caller as an exception.
 */
internal class ByteCursor(private val bytes: ByteArray, var position: Int = 0) {

    val remaining: Int get() = bytes.size - position

    fun require(count: Int, what: String) {
        if (count < 0) throw DecodeFailure(
            DecodeError.ValueOverflow("negative length for $what", position)
        )
        if (remaining < count) throw DecodeFailure(
            DecodeError.Truncated(
                message = "ran out of bytes reading $what: needed $count, only $remaining left",
                offset = position,
                needed = count,
                remaining = remaining,
            )
        )
    }

    fun u8(what: String): Int {
        require(1, what)
        return bytes[position++].toInt() and 0xFF
    }

    fun bytes(count: Int, what: String): ByteArray {
        require(count, what)
        val out = bytes.copyOfRange(position, position + count)
        position += count
        return out
    }

    fun skip(count: Int, what: String) {
        require(count, what)
        position += count
    }

    /** Solana `shortvec` / compact-u16: 7 bits per byte, little-endian, high bit = continue. */
    fun shortVec(what: String): Int {
        val start = position
        val decoded = ShortVec.decode(bytes, position)
            ?: throw DecodeFailure(
                if (remaining == 0) {
                    DecodeError.Truncated(
                        message = "ran out of bytes reading the $what length",
                        offset = position,
                        needed = 1,
                        remaining = 0,
                    )
                } else {
                    DecodeError.ValueOverflow(
                        "$what length is not a valid compact-u16 (max ${ShortVec.MAX_VALUE})",
                        start,
                    )
                }
            )
        position = decoded.nextOffset
        return decoded.value
    }
}

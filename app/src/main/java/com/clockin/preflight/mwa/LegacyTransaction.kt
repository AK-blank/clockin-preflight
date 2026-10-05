package com.clockin.preflight.mwa

import java.math.BigInteger

/**
 * The smallest legacy-Solana-transaction codec the wallet hand-off needs.
 *
 * Pure Kotlin on purpose — no `android.*`, no SDK types — so the exact bytes we hand to the wallet
 * (and the split of the wallet's answer back into signature + message) are covered by plain JVM
 * unit tests (see `app/src/test/java/com/clockin/preflight/mwa/`).
 *
 * Wire layout of a legacy transaction:
 *
 * ```
 * compact-u16 signature count | 64 bytes per signature | message
 * message = 3-byte header
 *         | compact-u16 account-key count | 32 bytes per key
 *         | 32-byte recent blockhash
 *         | compact-u16 instruction count | instructions
 * instruction = programIdIndex:u8 | compact-u16 account count | account indices:u8[] |
 *               compact-u16 data length | data
 * ```
 *
 * Everything here is byte-for-byte the format a production wallet (Phantom, Solflare, Seed Vault)
 * expects inside `sign_transactions`, because that is what MWA carries: opaque serialized
 * transactions.
 */
object LegacyTransaction {

    /** Base58 of 32 zero bytes; the System Program is the all-zero address. */
    const val SYSTEM_PROGRAM_ID_BASE58: String = "11111111111111111111111111111111"

    /** System Program instruction index for `Transfer`. */
    const val SYSTEM_TRANSFER: Int = 2

    val SYSTEM_PROGRAM_ID: ByteArray = ByteArray(32)

    /** A 32-byte placeholder blockhash. Sign-only payload: never broadcast, so it need not be real. */
    val DEMO_BLOCKHASH: ByteArray = ByteArray(32) { (it + 1).toByte() }

    /**
     * Build a legacy transaction that transfers [lamports] from [from] to [to], with [from] in the
     * single signature slot.
     *
     * [from] must be the wallet's authorized public key: a real wallet only signs for keys it owns,
     * so the payload has to be built *after* authorize — which is exactly why the pre-flight verdict
     * is produced after the wallet is connected but before it is asked to sign.
     */
    fun transfer(
        from: ByteArray,
        to: ByteArray,
        lamports: Long,
        recentBlockhash: ByteArray = DEMO_BLOCKHASH,
    ): ByteArray {
        require(from.size == 32) { "payer must be a 32-byte public key, got ${from.size}" }
        require(to.size == 32) { "recipient must be a 32-byte public key, got ${to.size}" }
        require(recentBlockhash.size == 32) {
            "blockhash must be 32 bytes, got ${recentBlockhash.size}"
        }
        require(lamports > 0) { "lamports must be positive, got $lamports" }

        val data = ByteArray(12)
        writeU32Le(data, 0, SYSTEM_TRANSFER)
        writeU64Le(data, 4, lamports)

        // 1 required signature, 0 readonly signed accounts, 1 readonly unsigned account
        // (the System Program). Account order: signer(s), writable unsigned, readonly unsigned.
        val message = buildMessage(
            header = byteArrayOf(1, 0, 1),
            accountKeys = listOf(from, to, SYSTEM_PROGRAM_ID),
            recentBlockhash = recentBlockhash,
            instructions = listOf(
                Instruction(
                    programIdIndex = 2,
                    accountIndices = byteArrayOf(0, 1),
                    data = data,
                ),
            ),
        )

        val tx = ByteArray(1 + SIGNATURE_LENGTH + message.size)
        tx[0] = 1 // compact-u16 signature count = 1
        message.copyInto(tx, 1 + SIGNATURE_LENGTH)
        return tx
    }

    /** Number of signature slots the transaction declares. */
    fun signatureCount(tx: ByteArray): Int = readCompactU16(tx, 0).first

    /** Byte offset at which the message starts (i.e. where signatures end). */
    fun messageOffset(tx: ByteArray): Int = readCompactU16(tx, 0).second + SIGNATURE_LENGTH * signatureCount(tx)

    /** The message bytes — the exact bytes an Ed25519 signature must cover. */
    fun message(tx: ByteArray): ByteArray {
        val offset = messageOffset(tx)
        require(offset <= tx.size) { "transaction is truncated: message offset $offset > ${tx.size}" }
        return tx.copyOfRange(offset, tx.size)
    }

    /** Signature slot [index] of a signed transaction. */
    fun signature(tx: ByteArray, index: Int = 0): ByteArray {
        val count = signatureCount(tx)
        require(index in 0 until count) { "no signature slot $index (transaction declares $count)" }
        val start = readCompactU16(tx, 0).second + SIGNATURE_LENGTH * index
        return tx.copyOfRange(start, start + SIGNATURE_LENGTH)
    }

    /** Copy of [tx] with [signature] written into slot [index]. */
    fun withSignature(tx: ByteArray, signature: ByteArray, index: Int = 0): ByteArray {
        require(signature.size == SIGNATURE_LENGTH) {
            "signature must be $SIGNATURE_LENGTH bytes, got ${signature.size}"
        }
        require(index < signatureCount(tx)) { "no signature slot $index" }
        val start = readCompactU16(tx, 0).second + SIGNATURE_LENGTH * index
        val out = tx.copyOf()
        signature.copyInto(out, start)
        return out
    }

    /** Rebuild "the request with slot [index] filled in" and compare with what the wallet returned. */
    fun signatureSlotMatches(request: ByteArray, response: ByteArray, index: Int = 0): Boolean {
        if (request.size != response.size) return false
        return withSignature(request, signature(response, index), index).contentEquals(response)
    }

    // ------------------------------------------------------------------ helpers

    private fun buildMessage(
        header: ByteArray,
        accountKeys: List<ByteArray>,
        recentBlockhash: ByteArray,
        instructions: List<Instruction>,
    ): ByteArray {
        val out = ByteArrayBuilder()
        out.append(header)
        out.appendCompactU16(accountKeys.size)
        accountKeys.forEach {
            require(it.size == 32) { "account key must be 32 bytes, got ${it.size}" }
            out.append(it)
        }
        out.append(recentBlockhash)
        out.appendCompactU16(instructions.size)
        instructions.forEach { instruction ->
            out.append(byteArrayOf(instruction.programIdIndex.toByte()))
            out.appendCompactU16(instruction.accountIndices.size)
            out.append(instruction.accountIndices)
            out.appendCompactU16(instruction.data.size)
            out.append(instruction.data)
        }
        return out.toByteArray()
    }

    /** Decoded compact-u16 value plus the number of bytes it occupied. */
    private fun readCompactU16(data: ByteArray, offset: Int): Pair<Int, Int> {
        var value = 0
        var shift = 0
        var index = offset
        while (true) {
            require(index < data.size) { "truncated compact-u16 at offset $offset" }
            val byte = data[index++].toInt() and 0xff
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
            require(shift <= 14) { "compact-u16 at offset $offset is longer than 3 bytes" }
        }
        return value to (index - offset)
    }

    private fun writeU32Le(target: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) target[offset + i] = ((value ushr (8 * i)) and 0xff).toByte()
    }

    private fun writeU64Le(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) target[offset + i] = ((value ushr (8 * i)) and 0xff).toByte()
    }

    private class Instruction(
        val programIdIndex: Int,
        val accountIndices: ByteArray,
        val data: ByteArray,
    )

    private class ByteArrayBuilder {
        private var buffer = ByteArray(256)
        private var size = 0

        fun append(bytes: ByteArray) {
            ensure(bytes.size)
            bytes.copyInto(buffer, size)
            size += bytes.size
        }

        fun appendCompactU16(value: Int) {
            require(value in 0..0xffff) { "value $value does not fit in compact-u16" }
            var remaining = value
            while (true) {
                val byte = remaining and 0x7f
                remaining = remaining ushr 7
                if (remaining == 0) {
                    append(byteArrayOf(byte.toByte()))
                    break
                }
                append(byteArrayOf((byte or 0x80).toByte()))
            }
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun ensure(extra: Int) {
            if (size + extra <= buffer.size) return
            var capacity = buffer.size
            while (capacity < size + extra) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }
    }

    // ------------------------------------------------------------------ base58

    private const val BASE58_ALPHABET =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    const val SIGNATURE_LENGTH: Int = 64
    const val PUBLIC_KEY_LENGTH: Int = 32

    /** Base58 — the encoding Solana wallets and explorers show for keys and signatures. */
    fun base58(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val alphabet = BigInteger.valueOf(58)
        var value = BigInteger(1, bytes)
        val encoded = StringBuilder()
        while (value.signum() > 0) {
            val (quotient, remainder) = value.divideAndRemainder(alphabet)
            encoded.append(BASE58_ALPHABET[remainder.toInt()])
            value = quotient
        }
        for (byte in bytes) {
            if (byte.toInt() != 0) break
            encoded.append(BASE58_ALPHABET[0])
        }
        return encoded.reverse().toString()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

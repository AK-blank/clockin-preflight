package com.clockin.preflight.engine

/**
 * Test-only encoder for the Solana wire format.
 *
 * Used for two things:
 *  1. building the handful of instruction shapes that are too rare to find on mainnet inside a
 *     reasonable scan (SetAuthority, Revoke, an unlimited Approve) — these are clearly labelled
 *     `SYNTHETIC` in the tests, and only the *instruction payload* is synthetic while the layout is
 *     the same one the real fixtures exercise;
 *  2. hand-crafting deliberately malformed blobs (truncation, bad shortvec, trailing bytes).
 *
 * It is written independently of [TransactionDecoder] so a bug in one does not hide a bug in the
 * other, and is cross-checked against the real mainnet fixtures in `TransactionDecoderTest`.
 */
object TxBuilder {

    class Instruction(
        val programIdIndex: Int,
        val accountIndexes: List<Int>,
        val data: ByteArray,
    )

    class Message(
        val accountKeys: List<ByteArray>,
        val instructions: List<Instruction>,
        val requiredSignatures: Int = 1,
        val readonlySigned: Int = 0,
        val readonlyUnsigned: Int = 0,
        val blockhash: ByteArray = ByteArray(32) { 7 },
    )

    // ------------------------------------------------------------- primitives

    fun shortVec(value: Int): ByteArray {
        require(value >= 0) { "shortvec cannot encode a negative value" }
        val out = ArrayList<Byte>(3)
        var remaining = value
        while (true) {
            var byte = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining == 0) {
                out.add(byte.toByte())
                break
            }
            byte = byte or 0x80
            out.add(byte.toByte())
        }
        return out.toByteArray()
    }

    fun u64le(value: Long): ByteArray = ByteArray(8) { i -> ((value ushr (8 * i)) and 0xFF).toByte() }

    fun u32le(value: Long): ByteArray = ByteArray(4) { i -> ((value ushr (8 * i)) and 0xFF).toByte() }

    /** Deterministic 32-byte pseudo-pubkey for test scaffolding. */
    fun key(seed: Int): ByteArray = ByteArray(32) { i -> ((seed * 37 + i * 11 + 3) and 0xFF).toByte() }

    fun keyBase58(seed: Int): String = Base58.encode(key(seed))

    // --------------------------------------------------------------- encoding

    fun encode(
        message: Message,
        signatureCount: Int = message.requiredSignatures,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(shortVec(signatureCount))
        repeat(signatureCount) { out.write(ByteArray(64) { i -> (i and 0xFF).toByte() }) }
        out.write(message.requiredSignatures)
        out.write(message.readonlySigned)
        out.write(message.readonlyUnsigned)
        out.write(shortVec(message.accountKeys.size))
        message.accountKeys.forEach { out.write(it) }
        out.write(message.blockhash)
        out.write(shortVec(message.instructions.size))
        message.instructions.forEach { instruction ->
            out.write(instruction.programIdIndex)
            out.write(shortVec(instruction.accountIndexes.size))
            instruction.accountIndexes.forEach { out.write(it) }
            out.write(shortVec(instruction.data.size))
            out.write(instruction.data)
        }
        return out.toByteArray()
    }

    fun encodeBase64(
        message: Message,
        signatureCount: Int = message.requiredSignatures,
    ): String = Base64Codec.encode(encode(message, signatureCount))

    // ------------------------------------------------- instruction payloads

    fun systemTransfer(lamports: Long): ByteArray = u32le(2) + u64le(lamports)

    fun systemCreateAccount(lamports: Long, space: Long, owner: ByteArray): ByteArray =
        u32le(0) + u64le(lamports) + u64le(space) + owner

    fun systemAssign(owner: ByteArray): ByteArray = u32le(1) + owner

    fun tokenTransfer(amount: Long): ByteArray = byteArrayOf(3) + u64le(amount)

    fun tokenTransferChecked(amount: Long, decimals: Int): ByteArray =
        byteArrayOf(12) + u64le(amount) + byteArrayOf(decimals.toByte())

    fun tokenApprove(amount: Long): ByteArray = byteArrayOf(4) + u64le(amount)

    fun tokenApproveChecked(amount: Long, decimals: Int): ByteArray =
        byteArrayOf(13) + u64le(amount) + byteArrayOf(decimals.toByte())

    fun tokenRevoke(): ByteArray = byteArrayOf(5)

    fun tokenSetAuthority(authorityType: Int, newAuthority: ByteArray?): ByteArray =
        if (newAuthority == null) {
            byteArrayOf(6, authorityType.toByte(), 0)
        } else {
            byteArrayOf(6, authorityType.toByte(), 1) + newAuthority
        }

    fun tokenBurn(amount: Long): ByteArray = byteArrayOf(8) + u64le(amount)

    fun tokenCloseAccount(): ByteArray = byteArrayOf(9)

    fun token2022InitializePermanentDelegate(delegate: ByteArray): ByteArray =
        byteArrayOf(35) + delegate

    fun token2022InitializeTransferHook(authority: ByteArray, program: ByteArray): ByteArray =
        byteArrayOf(36, 0) + authority + program

    fun token2022InitializeDefaultAccountState(state: Int): ByteArray =
        byteArrayOf(28, 0, state.toByte())

    fun token2022InitializeTransferFeeConfig(
        configAuthority: ByteArray?,
        withdrawAuthority: ByteArray?,
        basisPoints: Int,
        maximumFee: Long,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(26, 0))
        if (configAuthority == null) out.write(0) else {
            out.write(1); out.write(configAuthority)
        }
        if (withdrawAuthority == null) out.write(0) else {
            out.write(1); out.write(withdrawAuthority)
        }
        out.write(ByteArray(2) { i -> ((basisPoints ushr (8 * i)) and 0xFF).toByte() })
        out.write(u64le(maximumFee))
        return out.toByteArray()
    }

    fun token2022TransferCheckedWithFee(amount: Long, decimals: Int, fee: Long): ByteArray =
        byteArrayOf(26, 1) + u64le(amount) + byteArrayOf(decimals.toByte()) + u64le(fee)

    fun computeUnitLimit(units: Long): ByteArray = byteArrayOf(2) + u32le(units)

    fun computeUnitPrice(microLamports: Long): ByteArray = byteArrayOf(3) + u64le(microLamports)

    fun memo(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
}

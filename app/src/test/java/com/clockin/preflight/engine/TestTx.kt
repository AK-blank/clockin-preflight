package com.clockin.preflight.engine

import org.junit.Assert.assertTrue

/**
 * Test-only message builder.
 *
 * Real mainnet fixtures are preferred everywhere they exist; this builder covers the instruction
 * shapes that a bounded mainnet scan does not turn up (an unlimited `Approve`, `SetAuthority`,
 * `Revoke`) and the deliberately malformed envelopes. It encodes the same wire format as
 * [TransactionDecoder] reads, but independently, so the two cross-check each other.
 */
internal class TestTx {

    private val keys = ArrayList<ByteArray>()
    private val instructions = ArrayList<TxBuilder.Instruction>()

    /** Adds a caller-supplied key and returns its index. */
    fun key(bytes: ByteArray): Int {
        keys.add(bytes)
        return keys.size - 1
    }

    /** Adds a deterministic pseudo-key and returns its index. */
    fun key(seed: Int): Int = key(TxBuilder.key(seed))

    fun ix(programIndex: Int, accounts: List<Int>, data: ByteArray): TestTx {
        instructions.add(TxBuilder.Instruction(programIndex, accounts, data))
        return this
    }

    val keyCount: Int get() = keys.size

    fun message(
        requiredSignatures: Int = 1,
        readonlySigned: Int = 0,
        readonlyUnsigned: Int = 0,
    ) = TxBuilder.Message(
        accountKeys = keys.toList(),
        instructions = instructions.toList(),
        requiredSignatures = requiredSignatures,
        readonlySigned = readonlySigned,
        readonlyUnsigned = readonlyUnsigned,
    )

    fun base64(
        signatureCount: Int = 1,
        requiredSignatures: Int = 1,
        readonlySigned: Int = 0,
        readonlyUnsigned: Int = 0,
    ): String = TxBuilder.encodeBase64(
        message(requiredSignatures, readonlySigned, readonlyUnsigned),
        signatureCount = signatureCount,
    )

    fun build(
        signatureCount: Int = 1,
        requiredSignatures: Int = 1,
        readonlySigned: Int = 0,
        readonlyUnsigned: Int = 0,
    ): DecodedTransaction {
        val result = TransactionDecoder.decodeBase64(
            base64(signatureCount, requiredSignatures, readonlySigned, readonlyUnsigned)
        )
        assertTrue("synthetic transaction failed to decode: $result", result is DecodeResult.Success)
        return (result as DecodeResult.Success).transaction
    }

    companion object {
        val TOKEN_PROGRAM: ByteArray = Base58.decode(ProgramRegistry.TOKEN)!!
        val TOKEN_2022_PROGRAM: ByteArray = Base58.decode(ProgramRegistry.TOKEN_2022)!!
        val SYSTEM_PROGRAM: ByteArray = Base58.decode(ProgramRegistry.SYSTEM)!!
        val ATA_PROGRAM: ByteArray = Base58.decode(ProgramRegistry.ASSOCIATED_TOKEN)!!
        val MEMO_PROGRAM: ByteArray = Base58.decode(ProgramRegistry.MEMO_V1)!!

        /**
         * A wallet + two token accounts + owner + program + mint, in that order.
         *
         * Indices 0..2 are writable, 3..5 readonly; only index 0 signs, which mirrors a real
         * single-signer wallet transaction closely enough for the rules under test.
         */
        fun tokenMessage(
            payerSeed: Int = 1,
            tokenAccountSeed: Int = 2,
            destinationSeed: Int = 3,
            ownerSeed: Int = 4,
            mintSeed: Int = 5,
            program: ByteArray = TOKEN_PROGRAM,
            data: ByteArray,
            accounts: List<Int> = listOf(1, 5, 2, 0),
        ): TestTx = TestTx().apply {
            key(payerSeed)          // 0 signer, writable
            key(tokenAccountSeed)   // 1 writable
            key(destinationSeed)    // 2 writable
            key(ownerSeed)          // 3 readonly
            key(program)            // 4 readonly
            key(mintSeed)           // 5 readonly
            ix(4, accounts, data)
        }

        fun tokenTx(
            data: ByteArray,
            accounts: List<Int> = listOf(1, 5, 2, 0),
            program: ByteArray = TOKEN_PROGRAM,
        ): DecodedTransaction = tokenMessage(program = program, data = data, accounts = accounts)
            .build(readonlyUnsigned = 3)

        fun systemTx(data: ByteArray, accounts: List<Int> = listOf(0, 1)): DecodedTransaction =
            TestTx().apply {
                key(1)                     // 0 signer, writable
                key(2)                     // 1 writable
                key(SYSTEM_PROGRAM)        // 2 readonly
                ix(2, accounts, data)
            }.build(readonlyUnsigned = 1)

        /** Wallet + an arbitrary unknown program, for the unknown-program rule. */
        fun unknownProgramTx(
            programSeed: Int = 77,
            data: ByteArray = byteArrayOf(9, 9, 9),
            accounts: List<Int> = listOf(0),
        ): DecodedTransaction = TestTx().apply {
            key(1)                          // 0 signer, writable
            key(TxBuilder.key(programSeed)) // 1 readonly
            ix(1, accounts, data)
        }.build(readonlyUnsigned = 1)

        /** An unlimited approve and then a call into an unrecognised program. */
        fun drainerTx(): DecodedTransaction = TestTx().apply {
            key(1)                    // 0 payer
            key(2)                    // 1 token account
            key(3)                    // 2 delegate
            key(TOKEN_PROGRAM)        // 3 readonly
            val attacker = key(99)    // 4 readonly
            ix(3, listOf(1, 2, 0), TxBuilder.tokenApprove(-1L))
            ix(attacker, listOf(0), byteArrayOf(1, 2, 3))
        }.build(readonlyUnsigned = 2)
    }
}

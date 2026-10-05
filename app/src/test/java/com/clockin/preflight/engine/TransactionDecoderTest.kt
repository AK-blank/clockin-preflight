package com.clockin.preflight.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests driven by real mainnet transactions committed in [MainnetFixtures].
 *
 * Nothing here touches the network or the Solana SDK: the fixtures are base64 blobs captured once
 * and compiled into the test sources.
 */
class TransactionDecoderTest {

    // ------------------------------------------------------------ real fixtures

    @Test
    fun `every committed mainnet fixture decodes without error`() {
        assertTrue("expected several committed fixtures", Fixtures.names.size >= 8)
        for (name in Fixtures.names) {
            val result = TransactionDecoder.decodeBase64(Fixtures.base64(name))
            val tx = (result as? DecodeResult.Success)?.transaction
            assertNotNull(
                "fixture '$name' failed: " +
                    (result as? DecodeResult.Failure)?.error?.let { "${it.code} ${it.message}" },
                tx,
            )
            tx!!
            assertTrue("fixture '$name' has no instructions", tx.instructions.isNotEmpty())
            assertTrue("fixture '$name' has no account keys", tx.accountKeys.isNotEmpty())
            assertEquals(
                "fixture '$name' signature count disagrees with the header",
                tx.message.header.requiredSignatures,
                tx.signatures.size,
            )
        }
    }

    @Test
    fun `a real legacy transaction decodes with a valid header and account keys`() {
        val tx = Fixtures.decode("system_transfer")
        assertNull("expected a legacy message", tx.version)
        assertFalse(tx.isVersioned)
        assertEquals(32, Base58.decode(tx.accountKeys.first())!!.size)
        assertEquals(32, Base58.decode(tx.message.recentBlockhash)!!.size)
        assertTrue(tx.wireBytes > 0)
        assertNotNull(tx.feePayer)
    }

    @Test
    fun `a real v0 transaction reports version 0 and its lookup tables`() {
        val tx = Fixtures.decode("versioned_v0")
        assertEquals(0, tx.version)
        assertTrue(tx.isVersioned)
        // v0 is only ever produced by wallets that use lookup tables, but an empty list is legal.
        assertNotNull(tx.message.addressTableLookups)
        val unresolved = tx.message.accountMetas.filter { !it.isResolved }
        if (tx.message.addressTableLookups.isNotEmpty()) {
            assertTrue("v0 lookups produced no placeholder accounts", unresolved.isNotEmpty())
            assertTrue(unresolved.all { it.kind == AccountKind.UNRESOLVED_LOOKUP && !it.isSigner })
        }
    }

    @Test
    fun `account metas follow the header signer and writable rules`() {
        val tx = Fixtures.decode("system_transfer")
        val header = tx.message.header
        for ((index, meta) in tx.message.accountMetas.withIndex()) {
            if (index < header.requiredSignatures) {
                assertTrue("index $index should be a signer", meta.isSigner)
            } else {
                assertFalse("index $index should not be a signer", meta.isSigner)
            }
        }
        val signerWritable = header.requiredSignatures - header.readonlySignedAccounts
        assertTrue(tx.message.accountMetas.take(signerWritable).all { it.isWritable })
        assertFalse(tx.message.accountMetas.first().kind == AccountKind.SIGNER_READONLY)
    }

    @Test
    fun `a real system transfer decodes its lamport amount`() {
        val (name, tx) = Fixtures.requireWith("a System Program transfer") {
            it.decoded is InstructionKind.SystemTransfer
        }
        val transfer = Fixtures.instructionOf(tx) { it.decoded is InstructionKind.SystemTransfer }
        val kind = transfer.decoded as InstructionKind.SystemTransfer
        assertTrue("$name: expected a positive lamport amount", kind.lamports > 0)
        assertEquals(ProgramRegistry.SYSTEM, transfer.programId)
        assertEquals("System Program", transfer.programName)
        assertEquals(2, transfer.accounts.size)
        assertEquals(4 + 8, transfer.dataLength)
    }

    @Test
    fun `a real SPL Token transfer decodes amount and accounts`() {
        val unchecked = { ix: DecodedInstruction ->
            ix.decoded is InstructionKind.TokenTransfer &&
                !(ix.decoded as InstructionKind.TokenTransfer).checked
        }
        val (name, tx) = Fixtures.requireWith("a plain (unchecked) SPL Token transfer", unchecked)
        val transfer = Fixtures.instructionOf(tx, unchecked)
        val kind = transfer.decoded as InstructionKind.TokenTransfer
        assertTrue("$name: expected a positive token amount", kind.amount > 0)
        assertEquals(ProgramRegistry.TOKEN, transfer.programId)
        assertFalse("plain Transfer has no decimals byte", kind.checked)
        assertEquals(3, transfer.accounts.size)
        assertEquals(1 + 8, transfer.dataLength)
    }

    @Test
    fun `a real TransferChecked decodes the mint and the decimals`() {
        val (_, tx) = Fixtures.requireWith("a TransferChecked") {
            it.decoded is InstructionKind.TokenTransfer && (it.decoded as InstructionKind.TokenTransfer).checked
        }
        val transfer = Fixtures.instructionOf(tx) {
            it.decoded is InstructionKind.TokenTransfer && (it.decoded as InstructionKind.TokenTransfer).checked
        }
        val kind = transfer.decoded as InstructionKind.TokenTransfer
        assertTrue(kind.amount > 0)
        assertNotNull("TransferChecked must carry a decimals byte", kind.decimals)
        assertTrue("decimals should be plausible", kind.decimals!! in 0..18)
        assertEquals(4, transfer.accounts.size)
        assertEquals(1 + 8 + 1, transfer.dataLength)
        assertNotNull("the mint at index 1 must be a real account key", Base58.decode(transfer.accounts[1]))
        assertEquals(32, Base58.decode(transfer.accounts[1])!!.size)
        assertTrue(tx.accountKeys.contains(transfer.accounts[1]))
    }

    @Test
    fun `a real ComputeBudget instruction decodes its unit limit and price`() {
        val (_, tx) = Fixtures.requireWith("a ComputeBudget instruction") {
            it.programId == ProgramRegistry.COMPUTE_BUDGET
        }
        val decoded = tx.instructions
            .filter { it.programId == ProgramRegistry.COMPUTE_BUDGET }
            .mapNotNull { it.decoded }
        assertTrue("expected recognisable ComputeBudget instructions", decoded.isNotEmpty())
        val limit = decoded.filterIsInstance<InstructionKind.ComputeUnitLimit>().firstOrNull()
        val price = decoded.filterIsInstance<InstructionKind.ComputeUnitPrice>().firstOrNull()
        assertTrue("expected a compute unit limit", limit != null || price != null)
        limit?.let { assertTrue(it.units > 0) }
        price?.let { assertTrue(it.microLamports >= 0) }
    }

    @Test
    fun `a real Memo instruction decodes to readable text`() {
        val (_, tx) = Fixtures.requireWith("a Memo instruction") {
            it.decoded is InstructionKind.Memo
        }
        val memo = Fixtures.instructionOf(tx) { it.decoded is InstructionKind.Memo }
        val kind = memo.decoded as InstructionKind.Memo
        assertTrue("memo text should not be blank", kind.text.isNotBlank())
        assertTrue(memo.programId == ProgramRegistry.MEMO_V1 || memo.programId == ProgramRegistry.MEMO_V3)
    }

    @Test
    fun `a real Associated Token Account creation decodes`() {
        val (_, tx) = Fixtures.requireWith("an ATA creation") {
            it.decoded is InstructionKind.AtaCreate
        }
        val ix = Fixtures.instructionOf(tx) { it.decoded is InstructionKind.AtaCreate }
        val kind = ix.decoded as InstructionKind.AtaCreate
        assertEquals(ProgramRegistry.ASSOCIATED_TOKEN, ix.programId)
        // Mainnet carries both forms: empty data (original instruction) and a single-byte
        // discriminator (0 = Create, 1 = CreateIdempotent) from program version 1.1 onwards.
        assertTrue("ATA data is empty or a one-byte discriminator, was ${ix.dataLength}", ix.dataLength <= 1)
        assertTrue("ATA create takes at least six accounts", kind.accountCount >= 6)
        assertEquals(kind.idempotent, kind.variant.isNotEmpty())
    }

    @Test
    fun `a real token CloseAccount decodes`() {
        val (_, tx) = Fixtures.requireWith("a token CloseAccount") {
            it.decoded is InstructionKind.TokenCloseAccount
        }
        val ix = Fixtures.instructionOf(tx) { it.decoded is InstructionKind.TokenCloseAccount }
        assertEquals(1, ix.dataLength)
        assertTrue(ix.accounts.size >= 3)
    }

    @Test
    fun `real instructions carry raw data bytes alongside the decoded form`() {
        val tx = Fixtures.decode("system_transfer")
        for (ix in tx.instructions) {
            assertEquals(ix.data.size, ix.dataLength)
            assertTrue(ix.dataHex.length == ix.data.size * 2)
            assertTrue(ix.dataBase58.isNotEmpty() || ix.data.isEmpty())
        }
    }

    // -------------------------------------------------------------- malformed

    @Test
    fun `truncated real transaction yields a typed TRUNCATED error`() {
        val full = Base64Codec.decode(Fixtures.base64("system_transfer"))!!
        val truncated = full.copyOfRange(0, full.size / 2)
        val result = TransactionDecoder.decode(truncated)
        assertTrue("expected failure, got $result", result is DecodeResult.Failure)
        val error = (result as DecodeResult.Failure).error
        assertEquals("TRUNCATED", error.code)
        assertTrue("error should carry an offset", error.offset != null)
    }

    @Test
    fun `trailing bytes are rejected rather than silently ignored`() {
        val full = Base64Codec.decode(Fixtures.base64("system_transfer"))!!
        val padded = full + byteArrayOf(1, 2, 3)
        val result = TransactionDecoder.decode(padded)
        assertTrue(result is DecodeResult.Failure)
        val error = (result as DecodeResult.Failure).error
        assertEquals("TRAILING_BYTES", error.code)
        assertTrue(error.message.contains("3"))
    }

    @Test
    fun `invalid base64 and empty input produce typed errors`() {
        val badBase64 = TransactionDecoder.decodeBase64("not base64 !!!")
        assertEquals("INVALID_BASE64", (badBase64 as DecodeResult.Failure).error.code)

        val blank = TransactionDecoder.decodeBase64("   ")
        assertEquals("EMPTY_INPUT", (blank as DecodeResult.Failure).error.code)

        val empty = TransactionDecoder.decode(ByteArray(0))
        assertEquals("EMPTY_INPUT", (empty as DecodeResult.Failure).error.code)
    }

    @Test
    fun `an unsupported message version is reported, not decoded`() {
        // Same envelope as a valid single-signer transaction, with the version guard byte (0x80|1)
        // spliced in where the message header starts. This is the shape a future message version
        // would take, and we must refuse it explicitly instead of misreading it as a header.
        val tx = TxBuilder.encodeBase64(
            TxBuilder.Message(
                accountKeys = listOf(TxBuilder.key(1), TxBuilder.key(2)),
                instructions = listOf(
                    TxBuilder.Instruction(1, listOf(0), TxBuilder.systemTransfer(1_000))
                ),
            ),
            signatureCount = 1,
        )
        val bytes = Base64Codec.decode(tx)!!
        bytes[1 + 64] = (0x80 or 1).toByte()
        val result = TransactionDecoder.decode(bytes)
        assertTrue("expected a version failure, got $result", result is DecodeResult.Failure)
        val error = (result as DecodeResult.Failure).error
        assertEquals("UNSUPPORTED_MESSAGE_VERSION", error.code)
        assertTrue(error.message.contains("v1"))
    }

    @Test
    fun `a header claiming more signers than accounts is rejected`() {
        val tx = TxBuilder.encode(
            TxBuilder.Message(
                accountKeys = listOf(TxBuilder.key(1)),
                instructions = emptyList(),
                requiredSignatures = 3,
            ),
            signatureCount = 3,
        )
        val result = TransactionDecoder.decode(tx)
        assertTrue(result is DecodeResult.Failure)
        assertEquals("MALFORMED", (result as DecodeResult.Failure).error.code)
    }

    @Test
    fun `the decoder never throws, whatever bytes it is given`() {
        val full = Base64Codec.decode(Fixtures.base64("system_transfer"))!!
        // Every prefix of a real transaction, plus some deliberate corruption.
        for (length in 0..full.size) {
            val result = TransactionDecoder.decode(full.copyOfRange(0, length))
            assertNotNull(result)
        }
        var seed = 12345
        repeat(400) {
            val size = (seed % 300).let { if (it < 0) -it else it }
            val noise = ByteArray(size) { i ->
                seed = seed * 1103515245 + 12345
                ((seed ushr 16) and 0xFF).toByte()
            }
            val result = TransactionDecoder.decode(noise)
            assertNotNull("decoder returned null for ${size} bytes of noise", result)
        }
    }
}

package com.clockin.preflight.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instruction-body decoding.
 *
 * Where mainnet gives us a real example ([MainnetFixtures]) the test uses it; for instruction shapes
 * that are too rare to find in a bounded scan (an unlimited `Approve`, `SetAuthority`, `Revoke`) the
 * message is assembled with [TxBuilder], which encodes the same documented layout.
 */
class InstructionDecoderTest {

    // ------------------------------------------------------------- scaffolding

    private val user = TxBuilder.key(1)
    private val tokenAccount = TxBuilder.key(2)
    private val destination = TxBuilder.key(3)
    private val owner = TxBuilder.key(4)
    private val mint = TxBuilder.key(5)

    private val tokenProgram = Base58.decode(ProgramRegistry.TOKEN)!!
    private val token2022Program = Base58.decode(ProgramRegistry.TOKEN_2022)!!
    private val systemProgram = Base58.decode(ProgramRegistry.SYSTEM)!!

    private val tokenKeys = listOf(user, tokenAccount, destination, owner, tokenProgram, mint)
    private val token2022Keys = listOf(user, tokenAccount, destination, owner, token2022Program, mint)
    private val systemKeys = listOf(user, destination, systemProgram)

    /** Keys 1 and 2 writable, 3..5 readonly; one signer at index 0. */
    private fun tokenTx(
        data: ByteArray,
        accounts: List<Int>,
        programIndex: Int = 4,
    ): DecodedTransaction = decode(
        TxBuilder.Message(
            accountKeys = tokenKeys,
            instructions = listOf(TxBuilder.Instruction(programIndex, accounts, data)),
            requiredSignatures = 1,
            readonlySigned = 0,
            readonlyUnsigned = 3,
        )
    )

    private fun token2022Tx(data: ByteArray, accounts: List<Int>): DecodedTransaction = decode(
        TxBuilder.Message(
            accountKeys = token2022Keys,
            instructions = listOf(TxBuilder.Instruction(4, accounts, data)),
            requiredSignatures = 1,
            readonlySigned = 0,
            readonlyUnsigned = 3,
        )
    )

    private fun systemTx(data: ByteArray, accounts: List<Int>): DecodedTransaction = decode(
        TxBuilder.Message(
            accountKeys = systemKeys,
            instructions = listOf(TxBuilder.Instruction(2, accounts, data)),
            requiredSignatures = 1,
            readonlySigned = 0,
            readonlyUnsigned = 1,
        )
    )

    private fun decode(message: TxBuilder.Message): DecodedTransaction {
        val result = TransactionDecoder.decodeBase64(TxBuilder.encodeBase64(message))
        assertTrue("scaffolding transaction failed to decode: $result", result is DecodeResult.Success)
        return (result as DecodeResult.Success).transaction
    }

    private fun only(tx: DecodedTransaction): DecodedInstruction = tx.instructions.single()

    // ------------------------------------------------------------------ System

    @Test
    fun `system transfer reads the lamport amount from a u32 tagged body`() {
        val tx = systemTx(TxBuilder.systemTransfer(2_500_000_000L), listOf(0, 1))
        val kind = only(tx).decoded
        assertTrue("expected SystemTransfer, got $kind", kind is InstructionKind.SystemTransfer)
        assertEquals(2_500_000_000L, (kind as InstructionKind.SystemTransfer).lamports)
        assertEquals("System Program", only(tx).programName)
    }

    @Test
    fun `system assign and create account decode their owner key`() {
        val assign = only(systemTx(TxBuilder.systemAssign(owner), listOf(0))).decoded
        assertTrue(assign is InstructionKind.SystemAssign)
        assertEquals(Base58.encode(owner), (assign as InstructionKind.SystemAssign).owner)

        val create = only(
            systemTx(TxBuilder.systemCreateAccount(1_000_000L, 165L, tokenProgram), listOf(0, 1, 2))
        ).decoded
        assertTrue(create is InstructionKind.SystemCreateAccount)
        create as InstructionKind.SystemCreateAccount
        assertEquals(1_000_000L, create.lamports)
        assertEquals(165L, create.space)
        assertEquals(ProgramRegistry.TOKEN, create.owner)
    }

    // --------------------------------------------------------------- SPL Token

    @Test
    fun `an unlimited approve decodes to u64 max`() {
        val tx = tokenTx(TxBuilder.tokenApprove(-1L), listOf(1, 2, 0))
        val kind = only(tx).decoded
        assertTrue("expected TokenApprove, got $kind", kind is InstructionKind.TokenApprove)
        kind as InstructionKind.TokenApprove
        assertTrue("u64::MAX must be recognised as unlimited", kind.isUnlimited)
        assertEquals(-1L, kind.amount)
        assertFalse(kind.checked)
        assertEquals(9, only(tx).dataLength)
    }

    @Test
    fun `a bounded approve keeps its amount`() {
        val tx = tokenTx(TxBuilder.tokenApprove(500_000_000L), listOf(1, 2, 0))
        val kind = only(tx).decoded as InstructionKind.TokenApprove
        assertFalse(kind.isUnlimited)
        assertEquals(500_000_000L, kind.amount)
    }

    @Test
    fun `approve checked carries the decimals byte`() {
        val tx = tokenTx(TxBuilder.tokenApproveChecked(1_000L, 6), listOf(1, 5, 2, 0))
        val kind = only(tx).decoded as InstructionKind.TokenApprove
        assertTrue(kind.checked)
        assertEquals(6, kind.decimals)
        assertEquals(1_000L, kind.amount)
        assertEquals(10, only(tx).dataLength)
    }

    @Test
    fun `set authority decodes the authority type and the new key`() {
        val newKey = TxBuilder.key(9)
        val mintAuthority = only(
            tokenTx(TxBuilder.tokenSetAuthority(0, newKey), listOf(1, 0))
        ).decoded as InstructionKind.TokenSetAuthority
        assertEquals(AuthorityType.MINT_TOKENS, mintAuthority.authorityType)
        assertEquals(Base58.encode(newKey), mintAuthority.newAuthority)
        assertEquals(3 + 32, only(tokenTx(TxBuilder.tokenSetAuthority(0, newKey), listOf(1, 0))).dataLength)

        val freeze = only(tokenTx(TxBuilder.tokenSetAuthority(1, newKey), listOf(1, 0))).decoded
            as InstructionKind.TokenSetAuthority
        assertEquals(AuthorityType.FREEZE_ACCOUNT, freeze.authorityType)

        val accountOwner = only(tokenTx(TxBuilder.tokenSetAuthority(2, newKey), listOf(1, 0))).decoded
            as InstructionKind.TokenSetAuthority
        assertEquals(AuthorityType.ACCOUNT_OWNER, accountOwner.authorityType)

        val close = only(tokenTx(TxBuilder.tokenSetAuthority(3, newKey), listOf(1, 0))).decoded
            as InstructionKind.TokenSetAuthority
        assertEquals(AuthorityType.CLOSE_ACCOUNT, close.authorityType)
    }

    @Test
    fun `set authority to none means the authority is being renounced`() {
        val tx = tokenTx(TxBuilder.tokenSetAuthority(0, null), listOf(1, 0))
        val kind = only(tx).decoded as InstructionKind.TokenSetAuthority
        assertNull(kind.newAuthority)
        assertEquals(AuthorityType.MINT_TOKENS, kind.authorityType)
        assertEquals(3, only(tx).dataLength)
    }

    @Test
    fun `close account, revoke and burn decode their one byte tags`() {
        val close = only(tokenTx(TxBuilder.tokenCloseAccount(), listOf(1, 2, 0)))
        assertTrue(close.decoded is InstructionKind.TokenCloseAccount)
        assertEquals(9, close.data[0].toInt())

        val revoke = only(tokenTx(TxBuilder.tokenRevoke(), listOf(1, 0)))
        assertTrue(revoke.decoded is InstructionKind.TokenRevoke)
        assertEquals(5, revoke.data[0].toInt())

        val burn = only(tokenTx(TxBuilder.tokenBurn(42L), listOf(1, 5, 0)))
        assertTrue(burn.decoded is InstructionKind.TokenBurn)
        assertEquals(42L, (burn.decoded as InstructionKind.TokenBurn).amount)
    }

    @Test
    fun `an unknown SPL Token tag is not guessed at`() {
        val tx = tokenTx(byteArrayOf(99, 1, 2, 3), listOf(1, 0))
        val ix = only(tx)
        assertNull("unknown tags must not be decoded into a kind", ix.decoded)
        assertEquals(99, ix.discriminator)
        assertEquals("SPL Token Program", ix.programName)
    }

    // ------------------------------------------------------------ Token-2022

    @Test
    fun `token-2022 permanent delegate initialisation decodes the delegate`() {
        val delegate = TxBuilder.key(11)
        val tx = token2022Tx(
            TxBuilder.token2022InitializePermanentDelegate(delegate),
            listOf(1, 0),
        )

        val kind = only(tx).decoded
        assertTrue("expected Token2022Extension, got $kind", kind is InstructionKind.Token2022Extension)
        kind as InstructionKind.Token2022Extension
        assertEquals(35, kind.extensionTag)
        assertEquals(Base58.encode(delegate), kind.permanentDelegate)
        assertEquals("SPL Token-2022 Program", only(tx).programName)
    }

    @Test
    fun `token-2022 transfer hook initialisation decodes authority and program`() {
        val authority = TxBuilder.key(12)
        val hook = TxBuilder.key(13)
        val tx = token2022Tx(
            TxBuilder.token2022InitializeTransferHook(authority, hook),
            listOf(1, 0),
        )

        val kind = only(tx).decoded as InstructionKind.Token2022Extension
        assertEquals(36, kind.extensionTag)
        assertEquals(0, kind.subInstruction)
        assertEquals(Base58.encode(authority), kind.transferHookAuthority)
        assertEquals(Base58.encode(hook), kind.transferHookProgram)
    }

    @Test
    fun `token-2022 default account state and transfer fee initialisation decode`() {
        val frozen = token2022Tx(
            TxBuilder.token2022InitializeDefaultAccountState(2),
            listOf(1, 0),
        )
        val stateKind = only(frozen).decoded as InstructionKind.Token2022Extension
        assertEquals(DefaultAccountState.FROZEN, stateKind.defaultAccountState)

        val fee = token2022Tx(
            TxBuilder.token2022InitializeTransferFeeConfig(TxBuilder.key(14), null, 500, 1_000_000L),
            listOf(1, 0),
        )
        val feeKind = only(fee).decoded as InstructionKind.Token2022Extension
        assertEquals(26, feeKind.extensionTag)
        assertEquals(0, feeKind.subInstruction)
        assertEquals(500, feeKind.transferFeeBasisPoints)
        assertEquals(1_000_000L, feeKind.transferFeeMaximum)
    }

    @Test
    fun `token-2022 transfer checked with fee decodes amount decimals and fee`() {
        val tx = token2022Tx(
            TxBuilder.token2022TransferCheckedWithFee(10_000L, 6, 250L),
            listOf(1, 5, 2, 0),
        )

        val kind = only(tx).decoded as InstructionKind.Token2022Extension
        assertEquals(26, kind.extensionTag)
        assertEquals(1, kind.subInstruction)
        assertEquals(10_000L, kind.amount)
        assertEquals(6, kind.decimals)
        assertEquals(250L, kind.fee)
    }

    @Test
    fun `a real token-2022 transfer fixture decodes through the same code path`() {
        val (name, tx) = Fixtures.firstWith { it.programId == ProgramRegistry.TOKEN_2022 }
            ?: return // the committed fixture set always contains one; skip silently if trimmed
        val token2022 = tx.instructions.filter { it.programId == ProgramRegistry.TOKEN_2022 }
        assertTrue("$name: token-2022 instructions should decode", token2022.any { it.decoded != null })
        assertEquals("SPL Token-2022 Program", token2022.first().programName)
    }

    // ------------------------------------------------------------- decoys

    @Test
    fun `an unrecognised program keeps its raw data and stays undecoded`() {
        val program = TxBuilder.key(77)
        val tx = decode(
            TxBuilder.Message(
                accountKeys = listOf(user, program),
                instructions = listOf(
                    TxBuilder.Instruction(1, listOf(0), byteArrayOf(1, 2, 3, 4, 5))
                ),
                requiredSignatures = 1,
                readonlyUnsigned = 1,
            )
        )
        val ix = only(tx)
        assertNull(ix.decoded)
        assertEquals(Base58.encode(program), ix.programId)
        assertEquals(5, ix.dataLength)
        assertTrue(ix.dataBase58.isNotEmpty())
        assertEquals(ProgramTrust.UNKNOWN, ProgramRegistry.trust(ix.programId))
    }

    @Test
    fun `a truncated instruction body yields no decoded kind but no exception`() {
        // Approve needs 9 bytes; give it 4. The decoder must keep going and just report "unknown".
        val tx = tokenTx(byteArrayOf(4, 1, 2, 3), listOf(1, 2, 0))
        val ix = only(tx)
        assertNull(ix.decoded)
        assertEquals(4, ix.dataLength)
        assertEquals(4, ix.discriminator)
    }
}

package com.clockin.preflight.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * SPL Token / Token-2022 account-data parsing.
 *
 * The real fixtures in [MainnetAccounts] are the important part: they prove the TLV walk matches
 * what mainnet actually stores. The synthetic accounts cover extension combinations that the
 * captured set does not happen to contain.
 */
class Token2022ParserTest {

    // ------------------------------------------------------------- real data

    @Test
    fun `a real USDC mint parses as a plain 82 byte SPL mint`() {
        val b64 = MainnetAccounts.ALL["plain_mint"]
        assumeTrue("no plain mint fixture committed", b64 != null)
        val parsed = Token2022Parser.parseBase64(b64!!)
        assertTrue("expected a mint, got $parsed", parsed is TokenAccountData.Mint)
        val mint = parsed.mintOrNull()!!
        assertEquals(82, mint.rawSize)
        assertFalse("a classic SPL mint has no extensions", mint.isToken2022)
        assertEquals(6, mint.decimals)
        assertTrue("USDC has a mint authority", mint.mintAuthority != null)
        assertTrue(mint.supply > 0)
    }

    @Test
    fun `a real token account parses its owner amount and state`() {
        val name = MainnetAccounts.ALL.keys.firstOrNull { it.contains("account") }
        assumeTrue("no token account fixture committed", name != null)
        val parsed = Token2022Parser.parseBase64(MainnetAccounts.ALL[name!!]!!)
        assertTrue("expected a token account, got $parsed", parsed is TokenAccountData.Account)
        val account = parsed.accountOrNull()!!
        assertTrue("token accounts are at least 165 bytes", account.rawSize >= Token2022Parser.BASE_ACCOUNT_SIZE)
        assertEquals(32, Base58.decode(account.mint)!!.size)
        assertEquals(32, Base58.decode(account.owner)!!.size)
        assertNotNull(account.state)
        assertTrue(MainnetAccounts.PUBKEYS.containsKey(name))
    }

    @Test
    fun `a real token account with a delegate parses the delegate fields`() {
        val b64 = MainnetAccounts.ALL["token_account_with_delegate"]
        assumeTrue("no delegated token account fixture committed", b64 != null)
        val parsed = Token2022Parser.parseBase64(b64!!)
        val account = parsed.accountOrNull()
        assertNotNull("expected a token account", account)
        assertTrue("fixture was selected for having a delegate", account!!.hasDelegate)
        assertEquals(32, Base58.decode(account.delegate!!)!!.size)
        // The delegated amount is a raw u64: a negative Long means u64::MAX, i.e. an unlimited
        // delegate, which is exactly the drainer primitive the risk rules look for.
        assertTrue("a delegate is set, so the allowance should not be zero",
            account.delegatedAmount != 0L)
        if (account.delegatedAmount < 0L) {
            assertTrue(
                "a u64 delegate allowance must be treated as unlimited",
                AmountFormat.isEffectivelyUnlimited(account.delegatedAmount),
            )
            assertTrue(
                "an unlimited delegate must render as unlimited, was " +
                    AmountFormat.token(account.delegatedAmount, 6, null),
                AmountFormat.token(account.delegatedAmount, 6, null).startsWith("unlimited"),
            )
        }
    }

    @Test
    fun `a real token-2022 mint with extensions parses its TLV region`() {
        val b64 = MainnetAccounts.ALL["t22_mint_ext"]
        assumeTrue("no Token-2022 mint with extensions committed", b64 != null)
        val parsed = Token2022Parser.parseBase64(b64!!)
        assertTrue("expected a mint, got $parsed", parsed is TokenAccountData.Mint)
        val mint = parsed.mintOrNull()!!

        assertTrue("expected Token-2022 extensions", mint.isToken2022)
        assertTrue("extension type list should not be empty", mint.extensions.extensionTypes.isNotEmpty())
        assertTrue(mint.rawSize > Token2022Parser.BASE_ACCOUNT_SIZE)
        assertTrue("decimals should be plausible", mint.decimals in 0..18)
        assertTrue("supply should be positive", mint.supply > 0)

        val names = mint.extensions.extensionNames()
        assertEquals(mint.extensions.extensionTypes.size, names.size)
        assertTrue("extension names should not be raw ids: $names", names.none { it.startsWith("Extension#") })

        // The captured mainnet mint is the flagship Token-2022 danger case: a permanent delegate
        // plus a transfer hook. Both must be read out of the real bytes.
        assertTrue(
            "expected a PermanentDelegate extension, got ${mint.extensions.extensionTypes}",
            mint.extensions.extensionTypes.contains(Token2022Parser.EXT_PERMANENT_DELEGATE),
        )
        assertNotNull("the permanent delegate key must be parsed", mint.extensions.permanentDelegate)
        assertEquals(32, Base58.decode(mint.extensions.permanentDelegate!!)!!.size)
        assertTrue(mint.extensions.hasPermanentDelegate)
        assertTrue(
            "expected a TransferHook extension, got ${mint.extensions.extensionTypes}",
            mint.extensions.extensionTypes.contains(Token2022Parser.EXT_TRANSFER_HOOK),
        )
        // This particular mainnet mint carries the TransferHook extension with the all-zero
        // (system program) key, which per `OptionalNonZeroPubkey` means "no hook installed". The
        // parser must report the extension type without inventing a hook program.
        if (mint.extensions.transferHookProgram != null) {
            assertEquals(32, Base58.decode(mint.extensions.transferHookProgram!!)!!.size)
        }
    }

    @Test
    fun `the padded 165-byte mint layout and the 82-byte layout both resolve`() {
        // Real mainnet mints of this family pad the base to 165 bytes; the synthetic fixture below
        // uses the layout from the Token-2022 specification (type byte at 82). Both must parse.
        val padded = MainnetAccounts.ALL["t22_mint_ext"]
        assumeTrue("no Token-2022 mint fixture committed", padded != null)
        val paddedMint = Token2022Parser.parseBase64(padded!!).mintOrNull()
        assertNotNull(paddedMint)
        assertTrue(paddedMint!!.extensions.extensionTypes.isNotEmpty())

        val spec = Token2022Parser.parse(
            mintWith(Token2022Parser.EXT_PERMANENT_DELEGATE to TxBuilder.key(42))
        ).mintOrNull()
        assertNotNull(spec)
        assertTrue(spec!!.extensions.extensionTypes.isNotEmpty())
        assertEquals(Base58.encode(TxBuilder.key(42)), spec.extensions.permanentDelegate)
    }

    // -------------------------------------------------------------- synthetic

    /** Builds a Token-2022 mint: 82-byte base + account type + TLV entries. */
    private fun mintWith(vararg extensions: Pair<Int, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(ByteArray(4) { 1 })                  // mint authority COption tag = 1
        out.write(ByteArray(32) { 9 })                 // mint authority
        out.write(ByteArray(8) { 0 })                  // supply
        out.write(6)                                   // decimals
        out.write(1)                                   // isInitialized
        out.write(ByteArray(4))                        // freeze authority COption tag = 0
        out.write(ByteArray(32))                       // freeze authority
        out.write(1)                                   // AccountType::Mint
        for ((type, body) in extensions) {
            out.write(byteArrayOf((type and 0xFF).toByte(), ((type ushr 8) and 0xFF).toByte()))
            out.write(byteArrayOf((body.size and 0xFF).toByte(), ((body.size ushr 8) and 0xFF).toByte()))
            out.write(body)
        }
        return out.toByteArray()
    }

    /** Encodes the 165-byte base layout plus the Token-2022 account type byte. */
    private fun token2022Account(
        amount: Long,
        delegate: ByteArray? = null,
        delegatedAmount: Long = 0L,
        state: Int = 1,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(TxBuilder.key(5))                        // mint (32)
        out.write(TxBuilder.key(1))                        // owner (32)
        out.write(TxBuilder.u64le(amount))                 // amount (8)
        // COption<Pubkey> is always 4 tag bytes + 32 key bytes, even when unset.
        if (delegate == null) {
            out.write(ByteArray(4)); out.write(ByteArray(32))
        } else {
            out.write(byteArrayOf(1, 0, 0, 0)); out.write(delegate)
        }
        out.write(state)                                   // state (1)
        out.write(ByteArray(4)); out.write(ByteArray(8))   // isNative option + amount (12)
        out.write(TxBuilder.u64le(delegatedAmount))        // delegatedAmount (8)
        out.write(ByteArray(4)); out.write(ByteArray(32))  // close authority (36)
        out.write(2)                                       // AccountType::Account (1)
        return out.toByteArray()
    }

    @Test
    fun `a token-2022 mint with a permanent delegate and a transfer hook parses both`() {
        val delegate = TxBuilder.key(42)
        val hook = TxBuilder.key(43)
        val authority = TxBuilder.key(44)
        val bytes = mintWith(
            Token2022Parser.EXT_PERMANENT_DELEGATE to delegate,
            Token2022Parser.EXT_TRANSFER_HOOK to (authority + hook),
            Token2022Parser.EXT_DEFAULT_ACCOUNT_STATE to byteArrayOf(2),
        )

        val mint = Token2022Parser.parse(bytes).mintOrNull()
        assertNotNull("mint did not parse", mint)
        assertTrue(mint!!.isToken2022)
        assertEquals(Base58.encode(delegate), mint.extensions.permanentDelegate)
        assertEquals(Base58.encode(hook), mint.extensions.transferHookProgram)
        assertEquals(Base58.encode(authority), mint.extensions.transferHookAuthority)
        assertEquals(DefaultAccountState.FROZEN, mint.extensions.defaultAccountState)
        assertTrue(mint.extensions.freezesNewAccounts)
        assertTrue(mint.extensions.hasPermanentDelegate)
        assertTrue(mint.extensions.hasTransferHook)
        assertEquals(
            listOf("PermanentDelegate", "TransferHook", "DefaultAccountState"),
            mint.extensions.extensionNames(),
        )
    }

    @Test
    fun `a transfer fee config decodes the current basis points and maximum`() {
        val body = java.io.ByteArrayOutputStream()
        body.write(TxBuilder.key(50))   // config authority
        body.write(TxBuilder.key(51))   // withdraw authority
        body.write(TxBuilder.u64le(0))  // withheld amount
        // older fee: epoch 0, max 0, bps 0
        body.write(TxBuilder.u64le(0)); body.write(TxBuilder.u64le(0)); body.write(byteArrayOf(0, 0))
        // newer fee: epoch 100, max 1_000_000, bps 500
        body.write(TxBuilder.u64le(100)); body.write(TxBuilder.u64le(1_000_000L))
        body.write(byteArrayOf(0xF4.toByte(), 0x01))

        val mint = Token2022Parser.parse(
            mintWith(Token2022Parser.EXT_TRANSFER_FEE_CONFIG to body.toByteArray())
        ).mintOrNull()!!

        assertEquals(500, mint.extensions.transferFeeBasisPoints)
        assertEquals(1_000_000L, mint.extensions.transferFeeMaximum)
        assertEquals(Base58.encode(TxBuilder.key(50)), mint.extensions.transferFeeConfigAuthority)
        assertTrue(mint.extensions.hasTransferFee)
    }

    @Test
    fun `an unset optional key is reported as absent rather than as a zero address`() {
        val mint = Token2022Parser.parse(
            mintWith(Token2022Parser.EXT_PERMANENT_DELEGATE to ByteArray(32))
        ).mintOrNull()!!
        assertNull("an all-zero optional pubkey means unset", mint.extensions.permanentDelegate)
        assertFalse(mint.extensions.hasPermanentDelegate)
        assertTrue("the extension is still listed", mint.extensions.extensionTypes.contains(12))
    }

    @Test
    fun `a token-2022 token account parses its state and delegate`() {
        val parsed = Token2022Parser.parse(
            token2022Account(amount = 4_200L, delegate = TxBuilder.key(60), state = 2)
        )
        val account = parsed.accountOrNull()
        assertNotNull("token account did not parse", account)
        assertEquals(4_200L, account!!.amount)
        assertEquals(TokenAccountState.FROZEN, account.state)
        assertTrue(account.isFrozen)
        assertEquals(Base58.encode(TxBuilder.key(60)), account.delegate)
        assertTrue(account.isToken2022)
    }

    @Test
    fun `a plain token account without a delegate reports no delegate`() {
        val built = token2022Account(amount = 7L)
        assertEquals("scaffolding account must be the 165-byte base plus an account type",
            Token2022Parser.BASE_ACCOUNT_SIZE + 1, built.size)
        val plainAccount = built.copyOfRange(0, 165)
        val account = Token2022Parser.parse(plainAccount).accountOrNull()
        assertNotNull(account)
        assertNull(account!!.delegate)
        assertFalse(account.hasDelegate)
        assertEquals(TokenAccountState.INITIALIZED, account.state)
    }

    @Test
    fun `truncated account data yields a typed failure instead of an exception`() {
        val tooShort = Token2022Parser.parse(ByteArray(40))
        assertTrue(tooShort is TokenAccountData.Failure)
        assertEquals("TRUNCATED", (tooShort as TokenAccountData.Failure).error.code)

        val badBase64 = Token2022Parser.parseBase64("!!! not base64 !!!")
        assertTrue(badBase64 is TokenAccountData.Failure)
        assertEquals("INVALID_BASE64", (badBase64 as TokenAccountData.Failure).error.code)

        // Longer than a mint base but shorter than a token account, with no recognisable extension
        // region: it can only be a mint, so the base fields are still reported.
        val truncatedMint = Token2022Parser.parse(ByteArray(100))
        val mint = truncatedMint.mintOrNull()
        assertNotNull("a 100-byte buffer can only be a truncated mint", mint)
        assertEquals(0, mint!!.decimals)
        assertTrue(mint.extensions.extensionTypes.isEmpty())

        // Longer than a token account with no account type and no well-formed TLV: give up.
        val unknownLayout = Token2022Parser.parse(ByteArray(200))
        assertTrue(unknownLayout is TokenAccountData.Failure)
        assertEquals("MALFORMED", (unknownLayout as TokenAccountData.Failure).error.code)
    }

    @Test
    fun `a truncated extension body stops the walk without losing earlier extensions`() {
        val full = mintWith(
            Token2022Parser.EXT_PERMANENT_DELEGATE to TxBuilder.key(42),
            Token2022Parser.EXT_TRANSFER_HOOK to TxBuilder.key(43),
        )
        // Cut into the second extension's body only: the first must still be reported and the
        // half-written second one must not be guessed at.
        val truncated = full.copyOfRange(0, full.size - 25)
        val mint = Token2022Parser.parse(truncated).mintOrNull()
        assertNotNull("mint should still parse", mint)
        assertEquals(Base58.encode(TxBuilder.key(42)), mint!!.extensions.permanentDelegate)
        assertNull("the truncated hook must not be guessed", mint.extensions.transferHookProgram)
    }
}

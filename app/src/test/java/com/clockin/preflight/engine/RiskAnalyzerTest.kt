package com.clockin.preflight.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Risk-rule coverage.
 *
 * Real mainnet fixtures are used wherever a rule can be exercised with one; the remaining shapes
 * (unlimited `Approve`, `SetAuthority`, `Revoke`, the drainer combination) are assembled with
 * [TestTx], which encodes the same layouts.
 */
class RiskAnalyzerTest {

    private val payer = TxBuilder.keyBase58(1)
    private val tokenAccount = TxBuilder.keyBase58(2)
    private val destination = TxBuilder.keyBase58(3)
    private val mint = TxBuilder.keyBase58(5)

    private fun usdcContext(
        holding: Long? = null,
        holder: String = tokenAccount,
        decimals: Int = 6,
        counterparty: String? = null,
        priorTxCount: Int? = null,
    ) = RiskContext(
        userAddress = payer,
        balances = holding?.let { mapOf(holder to it) } ?: emptyMap(),
        decimals = mapOf(mint to decimals),
        tokenMetadata = mapOf(mint to TokenMetadata(mint, symbol = "USDC", decimals = decimals)),
        counterpartyTxCount = if (counterparty != null && priorTxCount != null) {
            mapOf(counterparty to priorTxCount)
        } else emptyMap(),
        // A legacy (unchecked) Transfer/Approve does not carry the mint, so the caller is expected
        // to supply the source account it already fetched. Mirror that here.
        sourceTokenAccount = accountInfo(holder, amount = holding ?: 0L),
    )

    private fun accountInfo(
        address: String,
        amount: Long = 0,
        delegate: String? = null,
        delegatedAmount: Long = 0,
        state: TokenAccountState = TokenAccountState.INITIALIZED,
        mintAddress: String = mint,
    ) = TokenAccountInfo(
        address = address,
        mint = mintAddress,
        owner = payer,
        amount = amount,
        delegate = delegate,
        delegatedAmount = delegatedAmount,
        state = state,
        closeAuthority = null,
        isNative = false,
        extensions = TokenExtensions.NONE,
        isToken2022 = false,
        rawSize = 165,
    )

    // ------------------------------------------------------ unlimited approve

    @Test
    fun `an unlimited approval is a DANGER and names the delegate`() {
        val tx = TestTx.tokenTx(TxBuilder.tokenApprove(-1L), accounts = listOf(1, 2, 0))
        val verdict = RiskAnalyzer.analyze(tx, usdcContext())

        assertEquals(VerdictLevel.DANGER, verdict.level)
        assertTrue("expected UNLIMITED_TOKEN_APPROVAL in ${verdict.findings.map { it.code }}",
            verdict.has("UNLIMITED_TOKEN_APPROVAL"))
        val finding = verdict.first("UNLIMITED_TOKEN_APPROVAL")!!
        assertEquals(Severity.CRITICAL, finding.severity)
        assertTrue("sentence should say unlimited: ${finding.plainEnglish}",
            finding.plainEnglish.contains("unlimited"))
        assertTrue("sentence should name the delegate",
            finding.plainEnglish.contains(ProgramRegistry.shortId(destination)))
        assertEquals(AmountFormat.U64_MAX_TEXT, finding.evidence["amount"])
    }

    @Test
    fun `a bounded approval is a MEDIUM delegate risk, not a drain`() {
        val tx = TestTx.tokenTx(TxBuilder.tokenApprove(500_000_000L), accounts = listOf(1, 2, 0))
        val verdict = RiskAnalyzer.analyze(tx, usdcContext(holding = 1_000_000_000L))

        assertEquals(VerdictLevel.CAUTION, verdict.level)
        assertTrue(verdict.has("BOUNDED_TOKEN_APPROVAL"))
        assertFalse(verdict.has("UNLIMITED_TOKEN_APPROVAL"))
        assertEquals(Severity.MEDIUM, verdict.first("BOUNDED_TOKEN_APPROVAL")!!.severity)
        assertTrue(verdict.first("BOUNDED_TOKEN_APPROVAL")!!.plainEnglish.contains("500 USDC"))
    }

    @Test
    fun `an approve checked against token-2022 is still caught`() {
        val tx = TestTx.tokenTx(
            TxBuilder.tokenApproveChecked(-1L, 6),
            accounts = listOf(1, 5, 2, 0),
            program = TestTx.TOKEN_2022_PROGRAM,
        )
        val verdict = RiskAnalyzer.analyze(tx, usdcContext())
        assertTrue(verdict.has("UNLIMITED_TOKEN_APPROVAL"))
        assertEquals(VerdictLevel.DANGER, verdict.level)
    }

    // ------------------------------------------------------------- transfers

    @Test
    fun `moving the entire real token balance is flagged`() {
        val (_, tx) = Fixtures.requireWith("a TransferChecked") {
            it.decoded is InstructionKind.TokenTransfer &&
                (it.decoded as InstructionKind.TokenTransfer).checked
        }
        val ix = Fixtures.instructionOf(tx) {
            it.decoded is InstructionKind.TokenTransfer &&
                (it.decoded as InstructionKind.TokenTransfer).checked
        }
        val kind = ix.decoded as InstructionKind.TokenTransfer
        val source = ix.accounts[0]
        val realMint = ix.accounts[1]
        val target = ix.accounts[2]
        val context = RiskContext(
            userAddress = payer,
            balances = mapOf(source to kind.amount),
            decimals = mapOf(realMint to kind.decimals!!),
            tokenMetadata = mapOf(realMint to TokenMetadata(realMint, symbol = "USDC", decimals = kind.decimals)),
            counterpartyTxCount = mapOf(target to 0),
        )

        val verdict = RiskAnalyzer.analyze(tx, context)
        assertTrue("expected TRANSFER_FULL_BALANCE, got ${verdict.findings.map { it.code }}",
            verdict.has("TRANSFER_FULL_BALANCE"))
        val finding = verdict.first("TRANSFER_FULL_BALANCE")!!
        assertEquals(Severity.HIGH, finding.severity)
        assertTrue("sentence should say 'all of it': ${finding.plainEnglish}",
            finding.plainEnglish.contains("all of it"))
        assertTrue(finding.plainEnglish.contains("no prior history"))
        assertTrue(verdict.level == VerdictLevel.CAUTION || verdict.level == VerdictLevel.DANGER)
    }

    @Test
    fun `a transfer larger than the supplied holding is CRITICAL`() {
        val (_, tx) = Fixtures.requireWith("a TransferChecked") {
            it.decoded is InstructionKind.TokenTransfer &&
                (it.decoded as InstructionKind.TokenTransfer).checked
        }
        val ix = Fixtures.instructionOf(tx) {
            it.decoded is InstructionKind.TokenTransfer &&
                (it.decoded as InstructionKind.TokenTransfer).checked
        }
        val kind = ix.decoded as InstructionKind.TokenTransfer
        assumeTrue("fixture amount too small to shrink", kind.amount > 10)

        val verdict = RiskAnalyzer.analyze(
            tx,
            RiskContext(
                userAddress = payer,
                balances = mapOf(ix.accounts[0] to kind.amount / 2),
                decimals = mapOf(ix.accounts[1] to kind.decimals!!),
            ),
        )
        assertTrue(verdict.has("TRANSFER_EXCEEDS_HOLDING"))
        assertEquals(Severity.CRITICAL, verdict.first("TRANSFER_EXCEEDS_HOLDING")!!.severity)
        assertEquals(VerdictLevel.DANGER, verdict.level)
        assertTrue(verdict.first("TRANSFER_EXCEEDS_HOLDING")!!.plainEnglish.contains("larger than your"))
    }

    @Test
    fun `moving almost the whole balance is a MEDIUM warning`() {
        val (_, tx) = Fixtures.requireWith("a TransferChecked") {
            it.decoded is InstructionKind.TokenTransfer &&
                (it.decoded as InstructionKind.TokenTransfer).checked
        }
        val ix = Fixtures.instructionOf(tx) {
            it.decoded is InstructionKind.TokenTransfer &&
                (it.decoded as InstructionKind.TokenTransfer).checked
        }
        val kind = ix.decoded as InstructionKind.TokenTransfer
        assumeTrue("fixture amount too small", kind.amount >= 10)

        val verdict = RiskAnalyzer.analyze(
            tx,
            RiskContext(
                userAddress = payer,
                balances = mapOf(ix.accounts[0] to kind.amount + 1),
                decimals = mapOf(ix.accounts[1] to kind.decimals!!),
            ),
        )
        assertTrue(verdict.has("TRANSFER_NEARLY_ALL"))
        assertEquals(Severity.MEDIUM, verdict.first("TRANSFER_NEARLY_ALL")!!.severity)
    }

    @Test
    fun `a plain SOL transfer equal to the whole balance is flagged`() {
        val tx = TestTx.systemTx(TxBuilder.systemTransfer(5_000_000_000L))
        val verdict = RiskAnalyzer.analyze(
            tx,
            RiskContext(userAddress = payer, balances = mapOf(payer to 5_000_000_000L)),
        )
        assertTrue(verdict.has("TRANSFER_FULL_BALANCE"))
        assertEquals(VerdictLevel.CAUTION, verdict.level)
        assertTrue(verdict.first("TRANSFER_FULL_BALANCE")!!.plainEnglish.contains("SOL"))
    }

    // --------------------------------------------------------- set authority

    @Test
    fun `handing over mint authority is CRITICAL`() {
        val newAuthority = TxBuilder.key(9)
        val tx = TestTx.tokenTx(TxBuilder.tokenSetAuthority(0, newAuthority), accounts = listOf(1, 0))
        val verdict = RiskAnalyzer.analyze(tx, usdcContext())

        assertTrue(verdict.has("SET_MINT_AUTHORITY"))
        assertEquals(Severity.CRITICAL, verdict.first("SET_MINT_AUTHORITY")!!.severity)
        assertEquals(VerdictLevel.DANGER, verdict.level)
        assertTrue(verdict.first("SET_MINT_AUTHORITY")!!.plainEnglish.contains("print unlimited"))
        assertEquals(Base58.encode(newAuthority), verdict.first("SET_MINT_AUTHORITY")!!.addresses.last())
    }

    @Test
    fun `handing over freeze authority is HIGH`() {
        val tx = TestTx.tokenTx(TxBuilder.tokenSetAuthority(1, TxBuilder.key(9)), accounts = listOf(1, 0))
        val verdict = RiskAnalyzer.analyze(tx, usdcContext())
        assertTrue(verdict.has("SET_FREEZE_AUTHORITY"))
        assertEquals(Severity.HIGH, verdict.first("SET_FREEZE_AUTHORITY")!!.severity)
        assertEquals(VerdictLevel.CAUTION, verdict.level)
    }

    @Test
    fun `handing over account ownership is CRITICAL`() {
        val tx = TestTx.tokenTx(TxBuilder.tokenSetAuthority(2, TxBuilder.key(9)), accounts = listOf(1, 0))
        val verdict = RiskAnalyzer.analyze(tx, usdcContext())
        assertTrue(verdict.has("SET_ACCOUNT_OWNER"))
        assertEquals(Severity.CRITICAL, verdict.first("SET_ACCOUNT_OWNER")!!.severity)
        assertEquals(VerdictLevel.DANGER, verdict.level)
    }

    @Test
    fun `renouncing an authority is reported but not dangerous`() {
        val tx = TestTx.tokenTx(TxBuilder.tokenSetAuthority(0, null), accounts = listOf(1, 0))
        val verdict = RiskAnalyzer.analyze(tx, usdcContext())
        assertTrue(verdict.has("SET_AUTHORITY_RENOUNCED"))
        assertEquals(VerdictLevel.SAFE, verdict.level)
        assertTrue(verdict.first("SET_AUTHORITY_RENOUNCED")!!.plainEnglish.contains("cannot be undone"))
    }

    // --------------------------------------------------------- close account

    @Test
    fun `closing a token account back to yourself is a MEDIUM housekeeping risk`() {
        val tx = TestTx.tokenTx(TxBuilder.tokenCloseAccount(), accounts = listOf(1, 0, 0))
        val verdict = RiskAnalyzer.analyze(
            tx,
            usdcContext().copy(sourceTokenAccount = accountInfo(tokenAccount, amount = 12_000_000L)),
        )
        assertTrue(verdict.has("CLOSE_ACCOUNT"))
        assertFalse(verdict.has("CLOSE_ACCOUNT_TO_FOREIGN"))
        val finding = verdict.first("CLOSE_ACCOUNT")!!
        assertTrue("should mention the destroyed balance: ${finding.plainEnglish}",
            finding.plainEnglish.contains("destroys"))
    }

    @Test
    fun `closing a token account to somebody else is HIGH`() {
        val tx = TestTx.tokenTx(TxBuilder.tokenCloseAccount(), accounts = listOf(1, 2, 0))
        val verdict = RiskAnalyzer.analyze(tx, usdcContext())
        assertTrue(verdict.has("CLOSE_ACCOUNT_TO_FOREIGN"))
        assertEquals(Severity.HIGH, verdict.first("CLOSE_ACCOUNT_TO_FOREIGN")!!.severity)
        assertTrue(verdict.first("CLOSE_ACCOUNT_TO_FOREIGN")!!.plainEnglish.contains("not back to your wallet"))
    }

    @Test
    fun `a real mainnet close-account transaction is flagged`() {
        val (_, tx) = Fixtures.requireWith("a token CloseAccount") {
            it.decoded is InstructionKind.TokenCloseAccount
        }
        val verdict = RiskAnalyzer.analyze(tx, usdcContext())
        assertTrue(verdict.has("CLOSE_ACCOUNT") || verdict.has("CLOSE_ACCOUNT_TO_FOREIGN"))
    }

    // ------------------------------------------------ token-2022 extensions

    private fun token2022Transfer(
        amount: Long = 1_000_000L,
        extensions: TokenExtensions,
        destinationOwner: String = destination,
    ): Pair<DecodedTransaction, RiskContext> {
        val tx = TestTx.tokenTx(
            TxBuilder.tokenTransferChecked(amount, 6),
            accounts = listOf(1, 5, 2, 0),
            program = TestTx.TOKEN_2022_PROGRAM,
        )
        val context = RiskContext(
            userAddress = payer,
            balances = mapOf(tokenAccount to amount),
            decimals = mapOf(mint to 6),
            tokenMetadata = mapOf(mint to TokenMetadata(mint, symbol = "USDC", decimals = 6, isToken2022 = true)),
            mintExtensions = mapOf(mint to extensions),
            counterpartyTxCount = mapOf(destinationOwner to 0),
        )
        return tx to context
    }

    @Test
    fun `a permanent delegate on the mint is CRITICAL`() {
        val delegate = TxBuilder.keyBase58(42)
        val (tx, context) = token2022Transfer(
            extensions = TokenExtensions(permanentDelegate = delegate, extensionTypes = listOf(12))
        )
        val verdict = RiskAnalyzer.analyze(tx, context)

        assertTrue(verdict.has("TOKEN2022_PERMANENT_DELEGATE"))
        assertEquals(Severity.CRITICAL, verdict.first("TOKEN2022_PERMANENT_DELEGATE")!!.severity)
        assertEquals(VerdictLevel.DANGER, verdict.level)
        assertTrue(verdict.first("TOKEN2022_PERMANENT_DELEGATE")!!.plainEnglish.contains("never"))
    }

    @Test
    fun `a transfer hook on the mint is HIGH`() {
        val hook = TxBuilder.keyBase58(43)
        val (tx, context) = token2022Transfer(
            extensions = TokenExtensions(
                transferHookProgram = hook,
                transferHookAuthority = TxBuilder.keyBase58(44),
                extensionTypes = listOf(14),
            )
        )
        val verdict = RiskAnalyzer.analyze(tx, context)
        assertTrue(verdict.has("TOKEN2022_TRANSFER_HOOK"))
        assertEquals(Severity.HIGH, verdict.first("TOKEN2022_TRANSFER_HOOK")!!.severity)
        assertTrue(verdict.first("TOKEN2022_TRANSFER_HOOK")!!.plainEnglish.contains("every single transfer"))
    }

    @Test
    fun `a frozen default account state is HIGH`() {
        val (tx, context) = token2022Transfer(
            extensions = TokenExtensions(
                defaultAccountState = DefaultAccountState.FROZEN,
                extensionTypes = listOf(6),
            )
        )
        val verdict = RiskAnalyzer.analyze(tx, context)
        assertTrue(verdict.has("TOKEN2022_DEFAULT_STATE_FROZEN"))
        assertEquals(Severity.HIGH, verdict.first("TOKEN2022_DEFAULT_STATE_FROZEN")!!.severity)
    }

    @Test
    fun `a transfer fee is a MEDIUM cost warning`() {
        val (tx, context) = token2022Transfer(
            extensions = TokenExtensions(
                transferFeeBasisPoints = 500,
                transferFeeMaximum = 1_000_000L,
                extensionTypes = listOf(1),
            )
        )
        val verdict = RiskAnalyzer.analyze(tx, context)
        assertTrue(verdict.has("TOKEN2022_TRANSFER_FEE"))
        assertEquals(Severity.MEDIUM, verdict.first("TOKEN2022_TRANSFER_FEE")!!.severity)
        assertTrue(verdict.first("TOKEN2022_TRANSFER_FEE")!!.plainEnglish.contains("5%"))
    }

    @Test
    fun `a transaction touching the real mainnet mint with a permanent delegate is DANGER`() {
        val mintAddress = MainnetAccounts.PUBKEYS["t22_mint_ext"]
        val mintData = MainnetAccounts.ALL["t22_mint_ext"]
        assumeTrue("no real Token-2022 mint fixture committed", mintAddress != null && mintData != null)
        val mintInfo = Token2022Parser.parseBase64(mintData!!).mintOrNull()!!
        val mintKey = Base58.decode(mintAddress!!)!!

        // Instruction layout is the real TransferChecked one: [source, mint, destination, owner].
        val tx = TestTx().apply {
            key(1)                              // 0 payer, signer
            key(2)                              // 1 source token account
            key(3)                              // 2 destination token account
            key(TestTx.TOKEN_2022_PROGRAM)      // 3 program
            key(mintKey)                        // 4 real mainnet mint
            ix(3, listOf(1, 4, 2, 0), TxBuilder.tokenTransferChecked(1_000_000L, mintInfo.decimals))
        }.build(readonlyUnsigned = 2)

        val verdict = RiskAnalyzer.analyze(
            tx,
            RiskContext(
                userAddress = payer,
                mintExtensions = mapOf(mintAddress to mintInfo.extensions),
            ),
        )

        assertTrue(
            "expected TOKEN2022_PERMANENT_DELEGATE, got ${verdict.findings.map { it.code }}",
            verdict.has("TOKEN2022_PERMANENT_DELEGATE"),
        )
        // The captured mint also carries a TransferHook extension, but with the all-zero program
        // key (unset), so only the permanent delegate must produce a finding here.
        assertEquals(VerdictLevel.DANGER, verdict.level)
        assertTrue(verdict.first("TOKEN2022_PERMANENT_DELEGATE")!!.plainEnglish.contains("never"))
    }

    @Test
    fun `initialising a permanent delegate inside the transaction is CRITICAL`() {
        val tx = TestTx.tokenTx(
            TxBuilder.token2022InitializePermanentDelegate(TxBuilder.key(42)),
            accounts = listOf(1, 0),
            program = TestTx.TOKEN_2022_PROGRAM,
        )
        val verdict = RiskAnalyzer.analyze(tx, usdcContext())
        assertTrue(verdict.has("TOKEN2022_INIT_PERMANENT_DELEGATE"))
        assertEquals(VerdictLevel.DANGER, verdict.level)
    }

    @Test
    fun `installing a transfer hook inside the transaction is HIGH`() {
        val tx = TestTx.tokenTx(
            TxBuilder.token2022InitializeTransferHook(TxBuilder.key(43), TxBuilder.key(44)),
            accounts = listOf(1, 0),
            program = TestTx.TOKEN_2022_PROGRAM,
        )
        val verdict = RiskAnalyzer.analyze(tx, usdcContext())
        assertTrue(verdict.has("TOKEN2022_INIT_TRANSFER_HOOK"))
        assertEquals(Severity.HIGH, verdict.first("TOKEN2022_INIT_TRANSFER_HOOK")!!.severity)
    }

    @Test
    fun `freezing new accounts inside the transaction is HIGH`() {
        val tx = TestTx.tokenTx(
            TxBuilder.token2022InitializeDefaultAccountState(2),
            accounts = listOf(1, 0),
            program = TestTx.TOKEN_2022_PROGRAM,
        )
        val verdict = RiskAnalyzer.analyze(tx, usdcContext())
        assertTrue(verdict.has("TOKEN2022_DEFAULT_STATE_FROZEN"))
    }

    @Test
    fun `configuring a transfer fee inside the transaction is MEDIUM`() {
        val tx = TestTx.tokenTx(
            TxBuilder.token2022InitializeTransferFeeConfig(TxBuilder.key(45), null, 100, 500_000L),
            accounts = listOf(1, 0),
            program = TestTx.TOKEN_2022_PROGRAM,
        )
        val verdict = RiskAnalyzer.analyze(tx, usdcContext())
        assertTrue(verdict.has("TOKEN2022_SET_TRANSFER_FEE"))
        assertEquals(Severity.MEDIUM, verdict.first("TOKEN2022_SET_TRANSFER_FEE")!!.severity)
    }

    @Test
    fun `a token-2022 transfer that withholds a fee is reported`() {
        val tx = TestTx.tokenTx(
            TxBuilder.token2022TransferCheckedWithFee(10_000_000L, 6, 250_000L),
            accounts = listOf(1, 5, 2, 0),
            program = TestTx.TOKEN_2022_PROGRAM,
        )
        val verdict = RiskAnalyzer.analyze(tx, usdcContext())
        assertTrue(verdict.has("TOKEN2022_TRANSFER_FEE_CHARGED"))
    }

    // -------------------------------------------------------- unknown programs

    @Test
    fun `an unrecognised program is flagged with what it can touch`() {
        val tx = TestTx.unknownProgramTx(data = byteArrayOf(1, 2, 3, 4))
        val verdict = RiskAnalyzer.analyze(tx, RiskContext(userAddress = payer))
        assertTrue(verdict.has("UNKNOWN_PROGRAM"))
        val finding = verdict.first("UNKNOWN_PROGRAM")!!
        assertEquals(Severity.MEDIUM, finding.severity)
        assertTrue(finding.plainEnglish.contains("write access to your account"))
        assertEquals(VerdictLevel.CAUTION, verdict.level)
    }

    @Test
    fun `known third-party programs are marked unverified rather than unknown`() {
        val jupiter = Base58.decode(ProgramRegistry.JUPITER_V6)!!
        val tx = TestTx().apply {
            val user = key(1)
            val program = key(jupiter)
            ix(program, listOf(user), byteArrayOf(1, 2, 3))
        }.build(readonlyUnsigned = 1)

        val verdict = RiskAnalyzer.analyze(tx, RiskContext(userAddress = payer))
        assertTrue(verdict.has("UNVERIFIED_PROGRAM"))
        assertFalse(verdict.has("UNKNOWN_PROGRAM"))
        assertTrue(verdict.first("UNVERIFIED_PROGRAM")!!.plainEnglish.contains("Jupiter"))
    }

    @Test
    fun `a caller-supplied trusted program is left alone`() {
        val tx = TestTx.unknownProgramTx()
        val untrusted = RiskAnalyzer.analyze(tx)
        val trusted = RiskAnalyzer.analyze(
            tx,
            RiskContext(trustedPrograms = setOf(tx.instructions.first().programId)),
        )
        assertTrue(untrusted.has("UNKNOWN_PROGRAM"))
        assertFalse(trusted.has("UNKNOWN_PROGRAM"))
    }

    // ------------------------------------------------------------- scam text

    @Test
    fun `a memo asking for a seed phrase is HIGH`() {
        val tx = TestTx().apply {
            val user = key(1)
            val memo = key(TestTx.MEMO_PROGRAM)
            ix(memo, listOf(user), TxBuilder.memo("URGENT: verify your wallet and send your seed phrase to claim"))
        }.build(readonlyUnsigned = 1)

        val verdict = RiskAnalyzer.analyze(tx, RiskContext(userAddress = payer))
        assertTrue(verdict.has("SCAM_LURE_TEXT"))
        val finding = verdict.first("SCAM_LURE_TEXT")!!
        assertEquals(Severity.HIGH, finding.severity)
        assertTrue(finding.plainEnglish.contains("only ever appears in attempts to steal a wallet"))
    }

    @Test
    fun `an airdrop lure memo with a url is MEDIUM`() {
        val tx = TestTx().apply {
            val user = key(1)
            val memo = key(TestTx.MEMO_PROGRAM)
            ix(memo, listOf(user), TxBuilder.memo("Congratulations! claim your airdrop at https://free-sol.xyz"))
        }.build(readonlyUnsigned = 1)

        val verdict = RiskAnalyzer.analyze(tx, RiskContext(userAddress = payer))
        assertTrue(verdict.has("SCAM_LURE_LANGUAGE"))
        assertEquals(Severity.MEDIUM, verdict.first("SCAM_LURE_LANGUAGE")!!.severity)
    }

    @Test
    fun `a lure hidden in an unknown program's data is still found`() {
        val lure = "connect your wallet to claim the reward".toByteArray()
        val tx = TestTx.unknownProgramTx(data = lure)
        val verdict = RiskAnalyzer.analyze(tx, RiskContext(userAddress = payer))
        assertTrue("expected a lure finding", verdict.has("SCAM_LURE_TEXT"))
    }

    @Test
    fun `a real nft metadata uri in program data is not called a scam`() {
        val b64 = MainnetFixtures.ALL["metadata_uri"] ?: return
        val report = RiskEngine.analyze(b64)
        assertTrue(
            "a legitimate metadata URI must not be reported as a lure: " +
                report.findings.map { it.code },
            !report.hasFinding("SCAM_LURE_TEXT") && !report.hasFinding("SCAM_LURE_LANGUAGE"),
        )
        // It is still an unrecognised program, which is worth a cautious mention on its own.
        assertTrue(report.hasFinding("UNKNOWN_PROGRAM"))
    }

    @Test
    fun `a memo that is only a url is still worth a caution`() {
        val tx = TestTx().apply {
            val user = key(1)
            val memo = key(TestTx.MEMO_PROGRAM)
            ix(memo, listOf(user), TxBuilder.memo("https://claim-clockin-rewards.xyz"))
        }.build(readonlyUnsigned = 1)

        val verdict = RiskAnalyzer.analyze(tx, RiskContext(userAddress = payer))
        assertTrue(
            "a bare URL in a memo should still warn, because a memo is user-visible",
            verdict.has("SCAM_LURE_LANGUAGE") || verdict.has("SCAM_LURE_TEXT"),
        )
    }

    @Test
    fun `binary instruction data is not mistaken for lure text`() {
        val binary = ByteArray(64) { (it * 37 and 0xFF).toByte() }
        val tx = TestTx.unknownProgramTx(data = binary)
        val verdict = RiskAnalyzer.analyze(tx)
        assertFalse(verdict.has("SCAM_LURE_TEXT"))
        assertFalse(verdict.has("SCAM_LURE_LANGUAGE"))
    }

    // ----------------------------------------------------------- other rules

    @Test
    fun `assigning an account to another program is HIGH`() {
        val tx = TestTx.systemTx(TxBuilder.systemAssign(TxBuilder.key(9)), accounts = listOf(0))
        val verdict = RiskAnalyzer.analyze(tx)
        assertTrue(verdict.has("SYSTEM_ASSIGN"))
        assertEquals(Severity.HIGH, verdict.first("SYSTEM_ASSIGN")!!.severity)
    }

    @Test
    fun `extra signatures beyond the declared signers are flagged`() {
        val tx = TestTx().apply {
            val user = key(1)
            val system = key(TestTx.SYSTEM_PROGRAM)
            ix(system, listOf(user, user), TxBuilder.systemTransfer(1_000L))
        }.build(signatureCount = 3, requiredSignatures = 1, readonlyUnsigned = 1)

        val verdict = RiskAnalyzer.analyze(tx)
        assertTrue(verdict.has("SIGNATURE_COUNT_MISMATCH"))
        assertEquals(Severity.HIGH, verdict.first("SIGNATURE_COUNT_MISMATCH")!!.severity)
        assertTrue(verdict.first("SIGNATURE_COUNT_MISMATCH")!!.plainEnglish.contains("hand-assembled"))
    }

    @Test
    fun `unlimited approval plus an unknown program is the drainer pattern`() {
        val verdict = RiskAnalyzer.analyze(TestTx.drainerTx(), usdcContext())
        assertTrue("expected DRAINER_PATTERN in ${verdict.findings.map { it.code }}",
            verdict.has("DRAINER_PATTERN"))
        assertEquals(Severity.CRITICAL, verdict.first("DRAINER_PATTERN")!!.severity)
        assertEquals(VerdictLevel.DANGER, verdict.level)
        assertTrue(verdict.first("DRAINER_PATTERN")!!.plainEnglish.contains("wallet drainer"))
    }

    @Test
    fun `an existing delegate on the source account is reported`() {
        val tx = TestTx.tokenTx(TxBuilder.tokenTransferChecked(1_000L, 6), accounts = listOf(1, 5, 2, 0))
        val delegate = TxBuilder.keyBase58(60)
        val verdict = RiskAnalyzer.analyze(
            tx,
            usdcContext().copy(
                sourceTokenAccount = accountInfo(
                    tokenAccount,
                    amount = 1_000_000L,
                    delegate = delegate,
                    delegatedAmount = 1_000_000L,
                )
            ),
        )
        assertTrue(verdict.has("ACCOUNT_HAS_ACTIVE_DELEGATE"))
        assertEquals(Severity.MEDIUM, verdict.first("ACCOUNT_HAS_ACTIVE_DELEGATE")!!.severity)
        assertTrue(verdict.first("ACCOUNT_HAS_ACTIVE_DELEGATE")!!.plainEnglish.contains("without your signature"))
    }

    @Test
    fun `a frozen source account is HIGH`() {
        val tx = TestTx.tokenTx(TxBuilder.tokenTransferChecked(1_000L, 6), accounts = listOf(1, 5, 2, 0))
        val verdict = RiskAnalyzer.analyze(
            tx,
            usdcContext().copy(
                sourceTokenAccount = accountInfo(
                    tokenAccount, amount = 1_000L, state = TokenAccountState.FROZEN
                )
            ),
        )
        assertTrue(verdict.has("SOURCE_ACCOUNT_FROZEN"))
        assertEquals(Severity.HIGH, verdict.first("SOURCE_ACCOUNT_FROZEN")!!.severity)
    }

    @Test
    fun `a benign compute-budget-only transaction is SAFE`() {
        val tx = TestTx().apply {
            val user = key(1)
            val cb = key(Base58.decode(ProgramRegistry.COMPUTE_BUDGET)!!)
            ix(cb, emptyList(), TxBuilder.computeUnitLimit(200_000L))
            ix(cb, emptyList(), TxBuilder.computeUnitPrice(1_000L))
        }.build(readonlyUnsigned = 1)

        val verdict = RiskAnalyzer.analyze(tx, RiskContext(userAddress = payer))
        assertEquals(VerdictLevel.SAFE, verdict.level)
        assertEquals(0, verdict.score)
        assertTrue(verdict.has("NO_RED_FLAGS"))
        assertEquals("No red flags found", verdict.headline)
    }

    // ------------------------------------------------------- invariants

    @Test
    fun `analysis without any context still produces findings and never throws`() {
        for (name in Fixtures.names) {
            val tx = Fixtures.decode(name)
            val verdict = RiskAnalyzer.analyze(tx)
            assertNotNull("$name produced no verdict", verdict)
            assertTrue("$name produced no findings at all", verdict.findings.isNotEmpty())
            assertTrue("$name score out of range: ${verdict.score}", verdict.score in 0..100)
            assertTrue("$name headline blank", verdict.headline.isNotBlank())
        }
    }

    @Test
    fun `at least one real mainnet transaction is judged SAFE`() {
        val safe = Fixtures.names.filter {
            RiskAnalyzer.analyze(Fixtures.decode(it)).level == VerdictLevel.SAFE
        }
        assertTrue(
            "no committed mainnet fixture was judged SAFE; this suggests the rules are too eager. " +
                "Verdicts: " + Fixtures.names.associateWith {
                RiskAnalyzer.analyze(Fixtures.decode(it)).level
            },
            safe.isNotEmpty(),
        )
    }

    @Test
    fun `scores and levels are consistent with the documented thresholds`() {
        for (name in Fixtures.names) {
            val verdict = RiskAnalyzer.analyze(Fixtures.decode(name))
            val hasCritical = verdict.findings.any { it.severity == Severity.CRITICAL }
            when {
                hasCritical -> assertEquals("$name", VerdictLevel.DANGER, verdict.level)
                verdict.score >= 70 -> assertEquals("$name", VerdictLevel.DANGER, verdict.level)
                verdict.score >= 20 -> assertEquals("$name", VerdictLevel.CAUTION, verdict.level)
                else -> assertEquals("$name", VerdictLevel.SAFE, verdict.level)
            }
            assertEquals("$name", verdict.score, verdict.findings.sumOf { it.severity.weight }.coerceAtMost(100))
        }
    }

    @Test
    fun `findings are ordered worst first`() {
        val verdict = RiskAnalyzer.analyze(TestTx.drainerTx(), usdcContext())
        val ranks = verdict.findings.map { it.severity.rank }
        assertEquals("findings should be sorted by severity", ranks.sortedDescending(), ranks)
    }
}

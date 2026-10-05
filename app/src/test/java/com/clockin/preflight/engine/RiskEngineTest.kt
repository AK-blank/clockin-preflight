package com.clockin.preflight.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end behaviour of the public entry point, including the sentences the demo shows.
 */
class RiskEngineTest {

    private val payer = TxBuilder.keyBase58(1)
    private val tokenAccount = TxBuilder.keyBase58(2)
    private val destination = TxBuilder.keyBase58(3)
    private val mint = TxBuilder.keyBase58(5)

    private fun usdc(holding: Long?, priorTxCount: Int? = null) = RiskContext(
        userAddress = payer,
        balances = holding?.let { mapOf(tokenAccount to it) } ?: emptyMap(),
        decimals = mapOf(mint to 6),
        tokenMetadata = mapOf(mint to TokenMetadata(mint, symbol = "USDC", decimals = 6)),
        counterpartyTxCount = priorTxCount?.let { mapOf(destination to it) } ?: emptyMap(),
    )

    // ---------------------------------------------------------- demo sentence

    @Test
    fun `the summary is the sentence the demo shows`() {
        val amount = 1_000_000_000_000L // 1,000,000 USDC at 6 decimals
        val tx = TestTx.tokenTx(
            TxBuilder.tokenTransferChecked(amount, 6),
            accounts = listOf(1, 5, 2, 0),
        )

        val report = RiskEngine.analyze(tx, usdc(holding = amount, priorTxCount = 0))

        assertEquals(
            "This transaction moves 1,000,000 USDC (all of it) out of your account to " +
                "${ProgramRegistry.shortId(destination)}, a wallet with no prior history.",
            report.summary,
        )
        assertEquals(VerdictLevel.CAUTION, report.level)
        assertTrue("narrative should lead with the summary", report.narrative.startsWith(report.summary))
        assertTrue(report.narrative.contains("all of it"))
        assertEquals(Severity.HIGH, report.verdict.topFinding!!.severity)
    }

    @Test
    fun `a real mainnet transfer produces a readable sentence`() {
        val (name, tx) = Fixtures.requireWith("a token transfer") {
            it.decoded is InstructionKind.TokenTransfer
        }
        val report = RiskEngine.analyze(tx)

        assertTrue("summary was blank for $name", report.summary.isNotBlank())
        assertTrue("summary should be a sentence: ${report.summary}", report.summary.endsWith("."))
        assertTrue(
            "at least one action sentence should describe moving funds, got " +
                report.actions.map { it.sentence },
            report.actions.any { it.sentence.contains("moves") || it.sentence.contains("sends") },
        )
        assertTrue(report.narrative.startsWith(report.summary))
        assertTrue("narrative was blank for $name", report.narrative.length > report.summary.length)
    }

    @Test
    fun `the narrative explains the danger in plain english`() {
        val tx = TestTx.tokenTx(TxBuilder.tokenApprove(-1L), accounts = listOf(1, 2, 0))
        val report = RiskEngine.analyze(tx, usdc(holding = 1_000_000L))

        assertEquals(VerdictLevel.DANGER, report.level)
        assertTrue(report.narrative.contains("unlimited"))
        assertTrue(report.narrative.contains("without asking you again"))
        assertTrue(report.verdict.headline.startsWith("Danger:"))
    }

    @Test
    fun `every instruction gets a sentence, and housekeeping is marked as noise`() {
        val tx = TestTx().apply {
            val user = key(1)
            val cb = key(Base58.decode(ProgramRegistry.COMPUTE_BUDGET)!!)
            val token = key(TestTx.TOKEN_PROGRAM)
            val account = key(2)
            val dest = key(3)
            ix(cb, emptyList(), TxBuilder.computeUnitLimit(200_000L))
            ix(token, listOf(account, dest, user), TxBuilder.tokenTransfer(1_000L))
        }.build(readonlyUnsigned = 2)

        val report = RiskEngine.analyze(tx, RiskContext(userAddress = payer))
        assertEquals(tx.instructions.size, report.actions.size)
        assertTrue("compute budget should be noise", report.actions[0].isNoise)
        assertFalse("a token transfer is not noise", report.actions[1].isNoise)
        assertTrue(report.summary.contains("moves"))
    }

    // --------------------------------------------------------- failure paths

    @Test
    fun `an undecodable transaction is CAUTION, never SAFE, and never throws`() {
        val inputs = listOf(
            "",
            "   ",
            "not base64 !!!",
            Base64Codec.encode(byteArrayOf(1, 2, 3)),
            Base64Codec.encode(ByteArray(10)),
            Base64Codec.encode(ByteArray(400) { 0x7F }),
        )
        for (input in inputs) {
            val report = RiskEngine.analyze(input)
            assertNotNull(report)
            assertEquals("input '${input.take(20)}'", VerdictLevel.CAUTION, report.level)
            assertTrue(report.hasFinding("UNDECODABLE_TRANSACTION"))
            assertFalse(report.isDecodable)
            assertNotNull(report.decodeError)
            assertTrue(report.narrative.isNotBlank())
            assertTrue(report.narrative.contains("could not read"))
        }
    }

    @Test
    fun `a truncated real transaction degrades gracefully`() {
        val full = Base64Codec.decode(Fixtures.base64("system_transfer"))!!
        val report = RiskEngine.analyze(Base64Codec.encode(full.copyOfRange(0, full.size / 3)))
        assertEquals(VerdictLevel.CAUTION, report.level)
        assertEquals("TRUNCATED", report.decodeError!!.code)
        assertEquals(1, report.findings.size)
    }

    @Test
    fun `the raw transaction input model works too`() {
        val tx = TestTx.systemTx(TxBuilder.systemTransfer(1_000L))
        val base64 = TestTx().apply {
            val user = key(1)
            val dest = key(2)
            val system = key(TestTx.SYSTEM_PROGRAM)
            ix(system, listOf(user, dest), TxBuilder.systemTransfer(1_000L))
        }.base64(readonlyUnsigned = 1)

        val report = RiskEngine.analyze(RawTransaction(base64, source = "unit-test"))
        assertTrue(report.isDecodable)
        assertEquals(tx.instructions.size, report.transaction!!.instructions.size)
        assertTrue(report.summary.contains("sends"))
    }

    // ------------------------------------------------------------ invariants

    @Test
    fun `every committed fixture can be analysed end to end`() {
        for (name in Fixtures.names) {
            val report = RiskEngine.analyze(Fixtures.base64(name))
            assertTrue("$name failed to decode: ${report.decodeError}", report.isDecodable)
            assertTrue("$name has a blank summary", report.summary.isNotBlank())
            assertTrue("$name has a blank narrative", report.narrative.isNotBlank())
            assertTrue("$name has a blank headline", report.verdict.headline.isNotBlank())
            assertTrue("$name score out of range", report.score in 0..100)
            assertEquals("$name", report.actions.size, report.transaction!!.instructions.size)
        }
    }

    @Test
    fun `analysis is deterministic`() {
        for (name in Fixtures.names) {
            val first = RiskEngine.analyze(Fixtures.base64(name))
            val second = RiskEngine.analyze(Fixtures.base64(name))
            assertEquals("$name", first.verdict, second.verdict)
            assertEquals("$name", first.narrative, second.narrative)
        }
    }

    @Test
    fun `the engine reports which programs it cannot vouch for`() {
        val tx = TestTx.unknownProgramTx()
        val report = RiskEngine.analyze(tx, RiskContext(userAddress = payer))
        assertTrue(report.hasFinding("UNKNOWN_PROGRAM"))
        val finding = report.firstFinding("UNKNOWN_PROGRAM")!!
        assertEquals(1, finding.addresses.size)
        assertTrue(finding.evidence.containsKey("programId"))
        assertTrue(report.summary.contains("do") && report.summary.contains("not recognise"))
    }
}

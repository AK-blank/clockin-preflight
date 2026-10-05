package com.clockin.preflight.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end smoke test against the **real** Solana network and the two real risk APIs.
 *
 * This is the one test in the package that opens a socket, so it is opt-in and skipped by default:
 * the hermetic suite stays hermetic, and the acceptance command
 * `./build.sh :app:testDebugUnitTest --tests 'com.clockin.preflight.data.*'` never touches the
 * network. To run it deliberately:
 *
 * ```bash
 * CLOCKIN_LIVE_SMOKE=1 ./build.sh :app:testDebugUnitTest --tests 'com.clockin.preflight.data.LiveSmokeTest'
 * ```
 *
 * It exists to answer the one question fixtures cannot: *does the parsing still match what the
 * servers send today?* Fixtures prove the parser handles captured bytes; this proves the bytes are
 * still that shape. Run it before a demo.
 */
class LiveSmokeTest {

    private val live: Boolean = System.getenv("CLOCKIN_LIVE_SMOKE") == "1"

    private companion object {
        const val USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

        /** A well-known, high-traffic account so `getSignaturesForAddress` always has data. */
        const val BUSY_ACCOUNT = USDC
    }

    @Test
    fun `the live RPC endpoint answers the calls the app depends on`() {
        assumeTrue("set CLOCKIN_LIVE_SMOKE=1 to run the live smoke test", live)

        val rpc = SolanaRpc()

        assertEquals("ok", rpc.getHealth().getOrNull())

        val slot = rpc.getSlot().getOrNull()
        assertNotNull("getSlot should return a slot", slot)
        assertTrue("slot should be well past the 453M range seen in October 2026, got $slot", slot!! > 400_000_000L)

        val version = rpc.getVersion().getOrNull()
        assertNotNull("getVersion should return node software", version)

        val blockhash = rpc.getLatestBlockhash().getOrNull()
        assertNotNull("getLatestBlockhash should return a blockhash", blockhash)
        assertTrue(blockhash!!.blockhash.isNotBlank())

        val balance = rpc.getBalance(USDC).getOrNull()
        assertNotNull("getBalance should return lamports", balance)

        val signatures = rpc.getSignaturesForAddress(BUSY_ACCOUNT, limit = 3).getOrNull()
        assertNotNull("getSignaturesForAddress should return entries", signatures)
        assertTrue("expected at least one signature", signatures!!.isNotEmpty())
        assertTrue(signatures.first().signature.isNotBlank())

        // Fetch one real transaction and confirm the base64 body survives the round trip.
        val transaction = rpc.getTransactionAutoVersion(signatures.first().signature).getOrNull()
        assertNotNull("getTransactionAutoVersion should return a transaction", transaction)
        assertNotNull("the transaction should carry a base64 body", transaction!!.base64)
        assertTrue("base64 body should be substantial", transaction.base64!!.length > 100)
    }

    @Test
    fun `both live risk sources answer and merge for a known-good mint`() {
        assumeTrue("set CLOCKIN_LIVE_SMOKE=1 to run the live smoke test", live)

        val risk = TokenRiskClient().fetchTokenRiskBlocking(USDC)

        assertEquals(
            "both live sources should answer; got errors ${risk.sourceErrors}",
            listOf(RiskSource.RUGCHECK, RiskSource.GOPLUS),
            risk.sources,
        )
        assertNotNull("RugCheck should return a score", risk.score)
        assertEquals("USDC", risk.symbol)
        // The policy must not call real USDC a rug, live or from a fixture.
        assertTrue(
            "live USDC came back ${risk.verdict}; the attestation guard has regressed",
            risk.verdict == Verdict.SAFE || risk.verdict == Verdict.CAUTION,
        )
        assertTrue(risk.dangerFlags.isEmpty())
    }

    @Test
    fun `an unknown mint is reported as unchecked rather than safe`() {
        assumeTrue("set CLOCKIN_LIVE_SMOKE=1 to run the live smoke test", live)

        // A valid base58 shape that is not a real mint: sources answer, but with nothing.
        val risk = TokenRiskClient().fetchTokenRiskBlocking("11111111111111111111111111111111")
        assertTrue(
            "an unresolvable mint must never be presented as safe",
            risk.verdict == Verdict.UNKNOWN || risk.verdict == Verdict.CAUTION,
        )
    }
}

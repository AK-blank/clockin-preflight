package com.clockin.preflight.data

import com.clockin.preflight.data.fixtures.GoPlusFixtures
import com.clockin.preflight.data.fixtures.RugCheckFixtures
import com.clockin.preflight.data.fixtures.RpcFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing and merging, driven entirely by real captured responses.
 *
 * The two mints are deliberately opposite ends of the spectrum:
 * - **USDC** (`EPjFWdd5…`) has a live mint authority *and* a live freeze authority, yet is plainly
 *   not a rug. It is the false-positive trap every naive scanner falls into.
 * - **JETPACK** (`37vV3bR2…`) is a Token-2022 pump.fun mint whose liquidity is $4k against a $986
 *   stable pool, and which RugCheck itself flags `danger`.
 */
class TokenRiskClientTest {

    private companion object {
        const val USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val JETPACK = "37vV3bR2ewPZL9CWPn3atGWao3qLD3uUkYAxpLqJpump"
        const val AMM_OWNER = "7xz9efja18mk7cRrBDrAuCNS5cvJGUooC47CswDpq6g1"

        fun ok(body: String) = HttpResponse(200, body)

        fun clientFor(
            rugCheck: HttpResponse?,
            goPlus: HttpResponse?,
        ): Pair<TokenRiskClient, FakeTransport> {
            val transport = FakeTransport { request ->
                when {
                    request.url.contains("rugcheck") -> rugCheck
                        ?: throw AssertionError("unexpected RugCheck call: ${request.url}")
                    request.url.contains("goplus") -> goPlus
                        ?: throw AssertionError("unexpected GoPlus call: ${request.url}")
                    else -> throw AssertionError("unexpected URL: ${request.url}")
                }
            }
            return TokenRiskClient(transport = transport) to transport
        }
    }

    // -----------------------------------------------------------------------------------------
    // RugCheck parsing
    // -----------------------------------------------------------------------------------------

    @Test
    fun `parses a real RugCheck report for USDC`() {
        val report = RugCheckParser.parse(RugCheckFixtures.USDC_RUGCHECK_REPORT)

        assertEquals(USDC, report.mint)
        assertEquals("USD Coin", report.name)
        assertEquals("USDC", report.symbol)
        assertEquals(1L, report.score)
        assertEquals(1, report.scoreNormalised)
        assertFalse(report.rugged)
        assertTrue(report.risks.isEmpty())
        assertTrue("USDC has no jup_verified=false surprise", report.jupVerified)

        // The headline false-positive trap: both authorities are live on real USDC.
        assertEquals("BJE5MMbqXjVwjAF7oxwPYXnTXDyspzZyt4vwenNw5ruG", report.mintAuthority)
        assertEquals("7dGbd2QZcCKcTndnHcTL8q7SMVXAkp688NTQYwrRCrar", report.freezeAuthority)
        assertTrue(report.hasMintAuthority && report.hasFreezeAuthority)

        // RugCheck returns `topHolders: null` (not `[]`) for a mint this large — must not throw.
        assertTrue("null topHolders must read as empty", report.topHolders.isEmpty())
        assertNull("classic SPL token has no Token-2022 extension block", report.tokenExtensions)
        assertEquals("CREATOR", report.knownAccounts[report.creator]?.type)
    }

    @Test
    fun `parses a real RugCheck report for a flagged Token-2022 mint`() {
        val report = RugCheckParser.parse(RugCheckFixtures.JETPACK_RUGCHECK_REPORT)

        assertEquals(JETPACK, report.mint)
        assertEquals(2014L, report.score)
        assertEquals(29, report.scoreNormalised)
        assertNull(report.mintAuthority)
        assertNull(report.freezeAuthority)

        assertEquals(1, report.risks.size)
        val risk = report.risks.single()
        assertEquals("Low Liquidity", risk.name)
        assertTrue("RugCheck itself calls this a danger", risk.isDanger)
        assertEquals(1, report.dangerRisks.size)

        // Token-2022 extensions are populated here but every dangerous one is absent.
        val extensions = report.tokenExtensions
        assertNotNull(extensions)
        assertFalse(extensions!!.nonTransferable)
        assertFalse(extensions.hasPermanentDelegate)
        assertFalse(extensions.hasTransferHook)
        assertFalse(extensions.isDefaultFrozen)

        assertEquals("Pump.Fun", report.launchpad)
        assertEquals(5153, report.totalHolders)
        assertEquals(20, report.topHolders.size)
        assertFalse("RugCheck returns no verification block for this mint", report.jupVerified)
    }

    /**
     * The holder that owns 90% of JETPACK is the Pump.Fun AMM pool, which RugCheck maps in
     * `knownAccounts`. Any concentration check that ignored that mapping would flag a perfectly
     * ordinary liquidity pool as a rug.
     */
    @Test
    fun `the dominant holder is identified as an AMM pool`() {
        val report = RugCheckParser.parse(RugCheckFixtures.JETPACK_RUGCHECK_REPORT)
        val top = report.topHolders.first()

        assertEquals(90.04, top.pct, 0.01)
        assertEquals(AMM_OWNER, top.owner)
        val known = report.knownAccounts[top.owner]
        assertNotNull("the holder's owner should be a known account", known)
        assertEquals("AMM", known!!.type)
        assertTrue(known.isInfrastructure)
    }

    // -----------------------------------------------------------------------------------------
    // GoPlus parsing
    // -----------------------------------------------------------------------------------------

    @Test
    fun `parses a real GoPlus response for USDC, including its live issuer capabilities`() {
        val report = GoPlusParser.parse(GoPlusFixtures.USDC_GOPLUS_RESPONSE, USDC)
        assertNotNull(report)
        report!!

        assertEquals("USD Coin", report.name)
        assertEquals("USDC", report.symbol)

        // Circle can mint and freeze USDC. Reported as capability, not as intent.
        assertTrue(report.mintable.enabled)
        assertTrue(report.freezable.enabled)
        assertEquals("BJE5MMbqXjVwjAF7oxwPYXnTXDyspzZyt4vwenNw5ruG", report.mintable.authorities.single().address)
        assertFalse(report.closable.enabled)
        assertTrue(report.metadataMutable.enabled)
        assertTrue(report.transferHooks.isEmpty())
        assertFalse(report.hasTransferFee)

        // `"1"` means Initialized — the ordinary tradeable state — NOT frozen. `"2"` is frozen.
        assertEquals("1", report.defaultAccountState)
        assertFalse(report.isDefaultFrozen)
        assertFalse(report.nonTransferable)
        assertTrue("trusted_token is the false-positive guard", report.trustedToken)
        // GoPlus sends holder_count as the string "9193555" while sending is_locked as a number.
        // Reading only the numeric shape silently loses the holder count.
        assertEquals(9_193_555L, report.holderCount)
        assertFalse(report.hasMaliciousAddress)
    }

    @Test
    fun `parses a real GoPlus response for a fresh untrusted mint`() {
        val report = GoPlusParser.parse(GoPlusFixtures.JETPACK_GOPLUS_RESPONSE, JETPACK)
        assertNotNull(report)
        report!!

        assertFalse(report.mintable.enabled)
        assertFalse(report.freezable.enabled)
        assertFalse(report.closable.enabled)
        assertFalse(report.balanceMutableAuthority.enabled)
        assertFalse(report.metadataMutable.enabled)
        assertFalse(report.trustedToken)
        assertTrue(report.transferHooks.isEmpty())
        assertFalse(report.isDefaultFrozen)
        // GoPlus omits holder_count entirely for a mint it has not fully indexed.
        assertNull(report.holderCount)
    }

    @Test
    fun `parses GoPlus holder rows and their string percentages`() {
        val report = GoPlusParser.parse(GoPlusFixtures.USDC_GOPLUS_RESPONSE, USDC)!!
        assertEquals(10, report.topHolders.size)
        val top = report.topHolders.first()
        assertEquals(0.1415, top.percent!!, 0.0001)
        assertFalse(top.isLocked)
        // `account` is the owning wallet; `token_account` is the ATA. The wallet is the useful one.
        assertEquals("5tzFkiKscXHK5ZXCGbXZxdw7gTjjD1mBwuoFbhUvuAi9", top.address)
    }

    @Test
    fun `an unindexed mint yields no GoPlus entry rather than a parse failure`() {
        val (client, _) = clientFor(
            rugCheck = ok(RugCheckFixtures.USDC_RUGCHECK_REPORT),
            goPlus = ok("""{"code":1,"message":"ok","result":{}}"""),
        )
        val result = client.fetchGoPlus(USDC)
        assertTrue("an empty result is a valid answer", result.isOk)
        assertNull(result.getOrNull())
    }

    // -----------------------------------------------------------------------------------------
    // Merging
    // -----------------------------------------------------------------------------------------

    @Test
    fun `USDC is not reported as dangerous despite live mint and freeze authorities`() {
        val (client, _) = clientFor(
            rugCheck = ok(RugCheckFixtures.USDC_RUGCHECK_REPORT),
            goPlus = ok(GoPlusFixtures.USDC_GOPLUS_RESPONSE),
        )
        val risk = client.fetchTokenRiskBlocking(USDC)

        assertEquals(listOf(RiskSource.RUGCHECK, RiskSource.GOPLUS), risk.sources)
        assertEquals(1, risk.score)
        assertEquals("USDC", risk.symbol)
        assertEquals(
            "flagging real USDC as a rug is the failure mode this policy exists to prevent",
            Verdict.CAUTION,
            risk.verdict,
        )
        assertTrue(risk.dangerFlags.isEmpty())
        assertTrue("no source failed", risk.sourceErrors.isEmpty())
    }

    @Test
    fun `USDC keeps every finding attributable to the source that produced it`() {
        val (client, _) = clientFor(
            rugCheck = ok(RugCheckFixtures.USDC_RUGCHECK_REPORT),
            goPlus = ok(GoPlusFixtures.USDC_GOPLUS_RESPONSE),
        )
        val risk = client.fetchTokenRiskBlocking(USDC)
        val byId = risk.flags.associateBy { it.id }

        // Both sources independently report the live mint authority, at WARN because USDC is attested.
        assertEquals(RiskSource.RUGCHECK, byId.getValue(RiskPolicy.Flags.RUGCHECK_MINT_AUTHORITY).source)
        assertEquals(Severity.WARN, byId.getValue(RiskPolicy.Flags.RUGCHECK_MINT_AUTHORITY).severity)
        assertEquals(RiskSource.GOPLUS, byId.getValue(RiskPolicy.Flags.GOPLUS_MINTABLE).source)
        assertEquals(Severity.WARN, byId.getValue(RiskPolicy.Flags.GOPLUS_MINTABLE).severity)

        assertEquals(Severity.INFO, byId.getValue(RiskPolicy.Flags.GOPLUS_TRUSTED).severity)
        assertEquals(RiskSource.GOPLUS, byId.getValue(RiskPolicy.Flags.GOPLUS_TRUSTED).source)
    }

    @Test
    fun `the flagged Token-2022 mint is reported as dangerous`() {
        val (client, _) = clientFor(
            rugCheck = ok(RugCheckFixtures.JETPACK_RUGCHECK_REPORT),
            goPlus = ok(GoPlusFixtures.JETPACK_GOPLUS_RESPONSE),
        )
        val risk = client.fetchTokenRiskBlocking(JETPACK)

        assertEquals(Verdict.DANGER, risk.verdict)
        assertEquals(listOf(RiskSource.RUGCHECK, RiskSource.GOPLUS), risk.sources)
        assertEquals(29, risk.score)
        assertEquals(2014L, risk.rawScore)
        assertTrue(risk.isDangerous)
        assertTrue(
            risk.flags.any { it.id == "${RiskPolicy.Flags.RUGCHECK_RISK}.low_liquidity" && it.severity == Severity.DANGER },
        )
        assertEquals(RiskSource.RUGCHECK, risk.flags.first { it.id.endsWith("low_liquidity") }.source)
    }

    @Test
    fun `holder concentration ignores the liquidity pool`() {
        val (client, _) = clientFor(
            rugCheck = ok(RugCheckFixtures.JETPACK_RUGCHECK_REPORT),
            goPlus = ok(GoPlusFixtures.JETPACK_GOPLUS_RESPONSE),
        )
        val risk = client.fetchTokenRiskBlocking(JETPACK)
        assertTrue(
            "a holder that is the Pump.Fun AMM pool is not a whale",
            risk.flags.none { it.id == RiskPolicy.Flags.RUGCHECK_CONCENTRATION },
        )
    }

    @Test
    fun `a single source failure still produces a usable verdict and says what is missing`() {
        val (client, transport) = clientFor(
            rugCheck = HttpResponse(500, "rugcheck is down"),
            goPlus = ok(GoPlusFixtures.JETPACK_GOPLUS_RESPONSE),
        )
        val risk = client.fetchTokenRiskBlocking(JETPACK)

        assertEquals(listOf(RiskSource.GOPLUS), risk.sources)
        assertNotNull(risk.sourceErrors[RiskSource.RUGCHECK])
        assertNull(risk.sourceErrors[RiskSource.GOPLUS])
        // GoPlus alone reports nothing for this mint, so we can only say "nothing found".
        assertEquals(Verdict.SAFE, risk.verdict)
        // A 500 is retried before giving up.
        assertEquals(2, transport.callsTo("rugcheck").size)
    }

    @Test
    fun `when both sources fail the verdict is UNKNOWN, never SAFE`() {
        val (client, _) = clientFor(
            rugCheck = HttpResponse(503, "unavailable"),
            goPlus = HttpResponse(500, "unavailable"),
        )
        val risk = client.fetchTokenRiskBlocking(USDC)

        assertTrue(risk.sources.isEmpty())
        assertEquals(Verdict.UNKNOWN, risk.verdict)
        assertFalse("an unchecked token must never read as safe", risk.isConclusive)
        assertEquals(2, risk.sourceErrors.size)
    }

    @Test
    fun `a malformed RugCheck body is reported as unreadable, not as a rug`() {
        val (client, _) = clientFor(
            rugCheck = ok(RpcFixtures.MALFORMED_JSON),
            goPlus = ok(GoPlusFixtures.USDC_GOPLUS_RESPONSE),
        )
        val risk = client.fetchTokenRiskBlocking(USDC)

        assertEquals(listOf(RiskSource.GOPLUS), risk.sources)
        assertTrue(risk.sourceErrors.getValue(RiskSource.RUGCHECK).contains("unreadable"))
        assertEquals(Verdict.CAUTION, risk.verdict)
    }

    @Test
    fun `a RugCheck body with no mint field is rejected`() {
        val (client, _) = clientFor(
            rugCheck = ok("""{"score":1,"risks":[]}"""),
            goPlus = ok(GoPlusFixtures.USDC_GOPLUS_RESPONSE),
        )
        val risk = client.fetchTokenRiskBlocking(USDC)
        assertEquals(listOf(RiskSource.GOPLUS), risk.sources)
        assertNotNull(risk.sourceErrors[RiskSource.RUGCHECK])
    }

    @Test
    fun `a malformed address is rejected before any network call`() {
        val (client, transport) = clientFor(rugCheck = null, goPlus = null)
        val risk = client.fetchTokenRiskBlocking("not a mint address")

        assertEquals(Verdict.UNKNOWN, risk.verdict)
        assertTrue(risk.sources.isEmpty())
        assertEquals("no request should be made for an invalid mint", 0, transport.requestCount)
        assertEquals(2, risk.sourceErrors.size)
    }

    @Test
    fun `requests are addressed to the documented live endpoints`() {
        val (client, transport) = clientFor(
            rugCheck = ok(RugCheckFixtures.USDC_RUGCHECK_REPORT),
            goPlus = ok(GoPlusFixtures.USDC_GOPLUS_RESPONSE),
        )
        client.fetchTokenRiskBlocking(USDC)

        val urls = transport.requests.map { it.url }
        assertEquals(2, urls.size)
        assertTrue(urls.any { it == "https://api.rugcheck.xyz/v1/tokens/$USDC/report" })
        assertTrue(urls.any { it == "https://api.gopluslabs.io/api/v1/solana/token_security?contract_addresses=$USDC" })
        assertTrue("both risk endpoints are plain GETs", transport.requests.all { it.method == "GET" })
    }

    @Test
    fun `a network failure is contained instead of thrown at the caller`() {
        val client = TokenRiskClient(transport = FakeTransport.offline("host unreachable"))
        val risk = client.fetchTokenRiskBlocking(USDC)
        assertEquals(Verdict.UNKNOWN, risk.verdict)
        assertTrue(risk.sourceErrors.getValue(RiskSource.RUGCHECK).contains("host unreachable"))
    }

    @Test
    fun `fetchTokenRisk is the same call as fetchTokenRiskBlocking`() {
        val (a, _) = clientFor(
            rugCheck = ok(RugCheckFixtures.USDC_RUGCHECK_REPORT),
            goPlus = ok(GoPlusFixtures.USDC_GOPLUS_RESPONSE),
        )
        val (b, _) = clientFor(
            rugCheck = ok(RugCheckFixtures.USDC_RUGCHECK_REPORT),
            goPlus = ok(GoPlusFixtures.USDC_GOPLUS_RESPONSE),
        )
        assertEquals(a.fetchTokenRiskBlocking(USDC), b.fetchTokenRisk(USDC))
    }

    @Test
    fun `mint shape validation accepts real addresses and rejects junk`() {
        assertTrue(TokenRiskClient.isPlausibleMint(USDC))
        assertTrue(TokenRiskClient.isPlausibleMint(JETPACK))
        assertFalse("too short", TokenRiskClient.isPlausibleMint("abc"))
        assertFalse("contains 0, which is not in the base58 alphabet", TokenRiskClient.isPlausibleMint("0".repeat(44)))
        assertFalse("contains a path separator", TokenRiskClient.isPlausibleMint("EPjF/../../etc/passwd"))
        assertFalse(TokenRiskClient.isPlausibleMint(""))
    }
}

package com.clockin.preflight.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Threshold and escalation behaviour, exercised through the real parsers but with payloads built to
 * isolate one rule at a time.
 *
 * The live fixtures cover two mints; these cover the rules those two happen not to trigger — a
 * permanent delegate, a frozen default account state, an upgradable transfer hook, and the exact
 * score boundaries. The JSON builders below are shaped from the captured responses, so a field
 * rename fails these tests rather than silently disabling a rule in production.
 */
class RiskPolicyTest {

    private companion object {
        const val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val AUTHORITY = "BJE5MMbqXjVwjAF7oxwPYXnTXDyspzZyt4vwenNw5ruG"
        const val WALLET = "2wmVCSfPxGPjrnMMn7rchp4uaeoTqN39mXFC2zhPdri9"

        fun rugCheckJson(
            score: Int = 1,
            risks: String = "[]",
            mintAuthority: String? = null,
            freezeAuthority: String? = null,
            topHolders: String = "null",
            knownAccounts: String = "{}",
            extensions: String = "null",
            rugged: Boolean = false,
            totalHolders: Int = 0,
            liquidity: Double = 0.0,
            jupVerified: Boolean? = null,
            insiderNetworks: Int? = null,
            graphInsiders: Int = 0,
            transferFeePct: Double = 0.0,
        ): String = """
            {"mint":"$MINT",
             "token":{"mintAuthority":${mintAuthority?.let { "\"$it\"" } ?: "null"},
                      "freezeAuthority":${freezeAuthority?.let { "\"$it\"" } ?: "null"},
                      "supply":1000,"decimals":6,"isInitialized":true},
             "tokenMeta":{"name":"Test Token","symbol":"TST"},
             "risks":$risks,
             "score":$score,"score_normalised":$score,"rugged":$rugged,
             "topHolders":$topHolders,"knownAccounts":$knownAccounts,
             "token_extensions":$extensions,"lockers":{},
             "totalHolders":$totalHolders,"totalMarketLiquidity":$liquidity,
             "transferFee":{"pct":$transferFeePct,"maxAmount":0,"authority":"11111111111111111111111111111111"},
             "verification":${if (jupVerified == null) "null" else """{"jup_verified":$jupVerified}"""},
             "graphInsidersDetected":$graphInsiders,
             "insiderNetworks":${insiderNetworks?.toString() ?: "null"},
             "deployPlatform":""}
        """.trimIndent()

        fun goPlusJson(
            mintable: Boolean = false,
            freezable: Boolean = false,
            closable: Boolean = false,
            balanceMutable: Boolean = false,
            metadataMutable: Boolean = false,
            transferHooks: String = "[]",
            transferHookUpgradable: Boolean = false,
            defaultAccountState: String = "1",
            defaultStateUpgradable: Boolean = false,
            nonTransferable: String = "0",
            transferFee: String = "{}",
            transferFeeUpgradable: Boolean = false,
            trusted: Int = 0,
            maliciousCreator: Boolean = false,
        ): String {
            fun capability(enabled: Boolean) = """{"authority":[],"status":"${if (enabled) 1 else 0}"}"""
            val creators = if (maliciousCreator) """[{"address":"$AUTHORITY","malicious_address":1}]""" else "[]"
            return """
                {"code":1,"message":"ok","result":{"$MINT":{
                  "mintable":${capability(mintable)},
                  "freezable":${capability(freezable)},
                  "closable":${capability(closable)},
                  "balance_mutable_authority":${capability(balanceMutable)},
                  "metadata_mutable":{"metadata_upgrade_authority":[],"status":"${if (metadataMutable) 1 else 0}"},
                  "transfer_hook":$transferHooks,
                  "transfer_hook_upgradable":${capability(transferHookUpgradable)},
                  "transfer_fee_upgradable":${capability(transferFeeUpgradable)},
                  "default_account_state_upgradable":${capability(defaultStateUpgradable)},
                  "default_account_state":"$defaultAccountState",
                  "non_transferable":"$nonTransferable",
                  "transfer_fee":$transferFee,
                  "trusted_token":$trusted,
                  "creators":$creators,
                  "holders":[],"lp_holders":[],
                  "metadata":{"name":"Test Token","symbol":"TST"}
                }}}
            """.trimIndent()
        }

        fun rug(json: String): RugCheckReport = RugCheckParser.parse(json)

        fun goPlus(json: String): GoPlusReport =
            GoPlusParser.parse(json, MINT) ?: throw AssertionError("builder produced no GoPlus entry")

        fun evaluate(rug: String? = null, gp: String? = null): TokenRisk =
            RiskPolicy.evaluate(
                mint = MINT,
                rugcheck = rug?.let(::rug),
                goplus = gp?.let(::goPlus),
            )

        fun TokenRisk.flag(id: String): TokenFlag? = flags.firstOrNull { it.id == id }
    }

    // -----------------------------------------------------------------------------------------
    // The false-positive guard
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an unattested live mint authority is a danger`() {
        val risk = evaluate(rug = rugCheckJson(mintAuthority = AUTHORITY))
        assertEquals(Verdict.DANGER, risk.verdict)
        assertEquals(Severity.DANGER, risk.flag(RiskPolicy.Flags.RUGCHECK_MINT_AUTHORITY)?.severity)
    }

    @Test
    fun `an attested live mint authority is only a warning`() {
        val risk = evaluate(rug = rugCheckJson(mintAuthority = AUTHORITY, jupVerified = true))
        assertEquals(Verdict.CAUTION, risk.verdict)
        assertEquals(Severity.WARN, risk.flag(RiskPolicy.Flags.RUGCHECK_MINT_AUTHORITY)?.severity)
    }

    @Test
    fun `an unattested live freeze authority is a danger`() {
        val risk = evaluate(rug = rugCheckJson(freezeAuthority = AUTHORITY))
        assertEquals(Verdict.DANGER, risk.verdict)
        assertEquals(Severity.DANGER, risk.flag(RiskPolicy.Flags.RUGCHECK_FREEZE_AUTHORITY)?.severity)
    }

    @Test
    fun `a GoPlus trust attestation softens authority findings from either source`() {
        val risk = evaluate(
            rug = rugCheckJson(mintAuthority = AUTHORITY, freezeAuthority = AUTHORITY),
            gp = goPlusJson(mintable = true, freezable = true, trusted = 1),
        )
        assertEquals(
            "an attested issuer keeping its authorities is expected, not fatal",
            Verdict.CAUTION,
            risk.verdict,
        )
        assertEquals(Severity.WARN, risk.flag(RiskPolicy.Flags.RUGCHECK_MINT_AUTHORITY)?.severity)
        assertEquals(Severity.WARN, risk.flag(RiskPolicy.Flags.GOPLUS_MINTABLE)?.severity)
        assertEquals(Severity.WARN, risk.flag(RiskPolicy.Flags.GOPLUS_FREEZABLE)?.severity)
    }

    @Test
    fun `a clean mint with no findings is safe`() {
        val risk = evaluate(rug = rugCheckJson(), gp = goPlusJson())
        assertEquals(Verdict.SAFE, risk.verdict)
        assertTrue(risk.flags.isEmpty())
        assertEquals(listOf(RiskSource.RUGCHECK, RiskSource.GOPLUS), risk.sources)
    }

    // -----------------------------------------------------------------------------------------
    // Rules no attestation can excuse: they remove the holder's ability to move their own tokens
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a transfer hook is a danger even for an attested token`() {
        val risk = evaluate(
            gp = goPlusJson(transferHooks = """[{"address":"$AUTHORITY","malicious_address":0}]""", trusted = 1),
        )
        assertEquals(Verdict.DANGER, risk.verdict)
        assertEquals(Severity.DANGER, risk.flag(RiskPolicy.Flags.GOPLUS_TRANSFER_HOOK)?.severity)
    }

    @Test
    fun `an upgradable transfer hook is a danger because a hook can be added later`() {
        val risk = evaluate(gp = goPlusJson(transferHookUpgradable = true, trusted = 1))
        assertEquals(Verdict.DANGER, risk.verdict)
        assertNotNull(risk.flag(RiskPolicy.Flags.GOPLUS_TRANSFER_HOOK_UPGRADABLE))
    }

    @Test
    fun `a balance-mutable authority is a danger even for an attested token`() {
        val risk = evaluate(gp = goPlusJson(balanceMutable = true, trusted = 1))
        assertEquals(Verdict.DANGER, risk.verdict)
        assertEquals(Severity.DANGER, risk.flag(RiskPolicy.Flags.GOPLUS_BALANCE_MUTABLE)?.severity)
    }

    /**
     * GoPlus reports `"1"` (Initialized) for USDC and for every ordinary pump.fun mint. Reading the
     * field as a boolean would flag the entire chain, so only `"2"` counts.
     */
    @Test
    fun `a frozen default account state is a danger but an initialized one is not`() {
        val frozen = evaluate(gp = goPlusJson(defaultAccountState = "2"))
        assertEquals(Verdict.DANGER, frozen.verdict)
        assertEquals(Severity.DANGER, frozen.flag(RiskPolicy.Flags.GOPLUS_DEFAULT_FROZEN)?.severity)

        val initialized = evaluate(gp = goPlusJson(defaultAccountState = "1"))
        assertEquals(Verdict.SAFE, initialized.verdict)
        assertFalse(initialized.flags.any { it.id == RiskPolicy.Flags.GOPLUS_DEFAULT_FROZEN })
    }

    @Test
    fun `a non-transferable token is a danger`() {
        val risk = evaluate(gp = goPlusJson(nonTransferable = "1"))
        assertEquals(Verdict.DANGER, risk.verdict)
        assertNotNull(risk.flag(RiskPolicy.Flags.GOPLUS_NON_TRANSFERABLE))
    }

    @Test
    fun `a Token-2022 permanent delegate is a danger`() {
        val risk = evaluate(
            rug = rugCheckJson(
                extensions = """{"nonTransferable":false,"permanentDelegate":{"delegate":"$AUTHORITY"},
                                  "transferHook":null,"mintCloseAuthority":null,
                                  "defaultAccountState":null,"transferFeeConfig":null,"pausableConfig":null}""",
            ),
        )
        assertEquals(Verdict.DANGER, risk.verdict)
        assertNotNull(risk.flag(RiskPolicy.Flags.RUGCHECK_PERMANENT_DELEGATE))
    }

    @Test
    fun `Token-2022 extensions can independently reveal a frozen default state and a hook`() {
        val frozen = evaluate(
            rug = rugCheckJson(
                extensions = """{"nonTransferable":false,"permanentDelegate":null,"transferHook":null,
                                  "mintCloseAuthority":null,"defaultAccountState":{"state":"frozen"},
                                  "transferFeeConfig":null,"pausableConfig":null}""",
                jupVerified = true,
            ),
        )
        assertEquals(Verdict.DANGER, frozen.verdict)
        assertNotNull(frozen.flag(RiskPolicy.Flags.RUGCHECK_DEFAULT_FROZEN))

        val hooked = evaluate(
            rug = rugCheckJson(
                extensions = """{"nonTransferable":false,"permanentDelegate":null,
                                  "transferHook":{"programId":"$AUTHORITY"},
                                  "mintCloseAuthority":null,"defaultAccountState":null,
                                  "transferFeeConfig":null,"pausableConfig":null}""",
                jupVerified = true,
            ),
        )
        assertEquals(Verdict.DANGER, hooked.verdict)
        assertNotNull(hooked.flag(RiskPolicy.Flags.RUGCHECK_TRANSFER_HOOK))
    }

    @Test
    fun `the Token-2022 non-transferable extension is a danger`() {
        val risk = evaluate(
            rug = rugCheckJson(
                extensions = """{"nonTransferable":true,"permanentDelegate":null,"transferHook":null,
                                  "mintCloseAuthority":null,"defaultAccountState":null,
                                  "transferFeeConfig":null,"pausableConfig":null}""",
            ),
        )
        assertEquals(Verdict.DANGER, risk.verdict)
        assertNotNull(risk.flag(RiskPolicy.Flags.RUGCHECK_NON_TRANSFERABLE))
    }

    // -----------------------------------------------------------------------------------------
    // Score and derived thresholds
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the score thresholds are inclusive at their documented boundaries`() {
        assertEquals(
            "just below the caution band is still safe",
            Verdict.SAFE,
            evaluate(rug = rugCheckJson(score = RiskPolicy.RUGCHECK_CAUTION_SCORE - 1)).verdict,
        )
        assertEquals(
            Verdict.CAUTION,
            evaluate(rug = rugCheckJson(score = RiskPolicy.RUGCHECK_CAUTION_SCORE)).verdict,
        )
        assertEquals(
            Verdict.CAUTION,
            evaluate(rug = rugCheckJson(score = RiskPolicy.RUGCHECK_DANGER_SCORE - 1)).verdict,
        )
        assertEquals(
            Verdict.DANGER,
            evaluate(rug = rugCheckJson(score = RiskPolicy.RUGCHECK_DANGER_SCORE)).verdict,
        )
    }

    @Test
    fun `RugCheck's own danger verdict is taken as a danger regardless of score`() {
        val risk = evaluate(
            rug = rugCheckJson(
                score = 1,
                risks = """[{"name":"Low Liquidity","value":"${'$'}986.44","description":"Low amount of liquidity","score":2013,"level":"danger"}]""",
            ),
        )
        assertEquals(Verdict.DANGER, risk.verdict)
        assertEquals(
            Severity.DANGER,
            risk.flag("${RiskPolicy.Flags.RUGCHECK_RISK}.low_liquidity")?.severity,
        )
    }

    @Test
    fun `a concentration flag fires for an unlabelled whale and not for a liquidity pool`() {
        val holders = """[{"address":"HolderAta","owner":"$WALLET","pct":91.5,"amount":1,"decimals":6}]"""

        val unlabelled = evaluate(rug = rugCheckJson(topHolders = holders))
        assertEquals(Verdict.DANGER, unlabelled.verdict)
        val flag = unlabelled.flag(RiskPolicy.Flags.RUGCHECK_CONCENTRATION)
        assertNotNull(flag)
        assertEquals(Severity.DANGER, flag!!.severity)
        assertTrue(flag.detail.contains("HolderAta"))

        val pool = evaluate(
            rug = rugCheckJson(
                topHolders = holders,
                knownAccounts = """{"$WALLET":{"name":"Pump Fun AMM","type":"AMM"}}""",
            ),
        )
        assertEquals(
            "a 91% holding that is a liquidity pool is not a whale",
            Verdict.SAFE,
            pool.verdict,
        )
        assertFalse(pool.flags.any { it.id == RiskPolicy.Flags.RUGCHECK_CONCENTRATION })
    }

    @Test
    fun `concentration just above the warning band warns rather than escalates`() {
        val holders = """[{"address":"HolderAta","owner":"$WALLET","pct":55.0,"amount":1,"decimals":6}]"""
        val risk = evaluate(rug = rugCheckJson(topHolders = holders))
        assertEquals(Verdict.CAUTION, risk.verdict)
        assertEquals(Severity.WARN, risk.flag(RiskPolicy.Flags.RUGCHECK_CONCENTRATION)?.severity)
    }

    @Test
    fun `a transfer fee above the documented rate is a danger`() {
        val dangerous = evaluate(rug = rugCheckJson(transferFeePct = RiskPolicy.TRANSFER_FEE_DANGER_RATE))
        assertEquals(Verdict.DANGER, dangerous.verdict)
        assertEquals(Severity.DANGER, dangerous.flag(RiskPolicy.Flags.RUGCHECK_TRANSFER_FEE)?.severity)

        val small = evaluate(rug = rugCheckJson(transferFeePct = 0.01))
        assertEquals(Verdict.CAUTION, small.verdict)
        assertEquals(Severity.WARN, small.flag(RiskPolicy.Flags.RUGCHECK_TRANSFER_FEE)?.severity)
    }

    @Test
    fun `low liquidity is derived only when RugCheck has indexed the mint and stayed silent`() {
        val thin = evaluate(rug = rugCheckJson(totalHolders = 500, liquidity = 500.0))
        assertEquals(Verdict.DANGER, thin.verdict)
        assertEquals(Severity.DANGER, thin.flag(RiskPolicy.Flags.DERIVED_LOW_LIQUIDITY)?.severity)
        assertEquals(RiskSource.DERIVED, thin.flag(RiskPolicy.Flags.DERIVED_LOW_LIQUIDITY)?.source)

        // Same numbers, but RugCheck already named the problem — do not say it twice.
        val alreadyNamed = evaluate(
            rug = rugCheckJson(
                totalHolders = 500,
                liquidity = 500.0,
                risks = """[{"name":"Low Liquidity","level":"danger","description":"thin"}]""",
            ),
        )
        assertFalse(alreadyNamed.flags.any { it.id == RiskPolicy.Flags.DERIVED_LOW_LIQUIDITY })

        // No indexed holders means RugCheck simply has no liquidity data (this is USDC's shape).
        val unindexed = evaluate(rug = rugCheckJson(totalHolders = 0, liquidity = 0.0))
        assertFalse(unindexed.flags.any { it.id == RiskPolicy.Flags.DERIVED_LOW_LIQUIDITY })
        assertEquals(Verdict.SAFE, unindexed.verdict)
    }

    @Test
    fun `a rugged mint and a malicious creator are both dangers`() {
        assertEquals(Verdict.DANGER, evaluate(rug = rugCheckJson(rugged = true)).verdict)
        assertEquals(Verdict.DANGER, evaluate(gp = goPlusJson(maliciousCreator = true)).verdict)
        assertNotNull(evaluate(gp = goPlusJson(maliciousCreator = true)).flag(RiskPolicy.Flags.GOPLUS_MALICIOUS_ADDRESS))
    }

    @Test
    fun `insider networks escalate and a large insider graph warns`() {
        assertEquals(Verdict.DANGER, evaluate(rug = rugCheckJson(insiderNetworks = 3)).verdict)
        assertEquals(
            Verdict.CAUTION,
            evaluate(rug = rugCheckJson(graphInsiders = RiskPolicy.INSIDER_WARN_THRESHOLD)).verdict,
        )
        assertEquals(
            Verdict.SAFE,
            evaluate(rug = rugCheckJson(graphInsiders = 3)).verdict,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Verdict assembly
    // -----------------------------------------------------------------------------------------

    @Test
    fun `with no sources at all the verdict is UNKNOWN, never SAFE`() {
        val risk = RiskPolicy.evaluate(mint = MINT, rugcheck = null, goplus = null)
        assertEquals(Verdict.UNKNOWN, risk.verdict)
        assertTrue(risk.sources.isEmpty())
        assertFalse(risk.isConclusive)
        assertFalse("an unchecked token must not read as safe", risk.verdict == Verdict.SAFE)
    }

    @Test
    fun `flags are ordered worst first`() {
        val risk = evaluate(
            rug = rugCheckJson(mintAuthority = AUTHORITY, transferFeePct = 0.01),
            // The trust flag softens the authority findings to WARN, so the transfer hook is the
            // only DANGER here — and it must still sort to the front.
            gp = goPlusJson(
                trusted = 1,
                mintable = true,
                transferHooks = """[{"address":"$AUTHORITY","malicious_address":0}]""",
            ),
        )
        val severities = risk.flags.map { it.severity }
        assertEquals(severities.sortedByDescending { it.ordinal }, severities)
        assertEquals(Severity.DANGER, severities.first())
        assertEquals(RiskPolicy.Flags.GOPLUS_TRANSFER_HOOK, risk.flags.first().id)
    }

    @Test
    fun `source errors are carried through so the UI can disclose partial coverage`() {
        val risk = RiskPolicy.evaluate(
            mint = MINT,
            rugcheck = null,
            goplus = goPlus(goPlusJson()),
            sourceErrors = mapOf(RiskSource.RUGCHECK to "HTTP 500"),
        )
        assertEquals(listOf(RiskSource.GOPLUS), risk.sources)
        assertEquals("HTTP 500", risk.sourceErrors[RiskSource.RUGCHECK])
        assertEquals(Verdict.SAFE, risk.verdict)
    }
}

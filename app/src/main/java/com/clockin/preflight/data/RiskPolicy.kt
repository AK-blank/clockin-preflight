package com.clockin.preflight.data

/**
 * Turns two independent risk reports into one verdict.
 *
 * ### Why this is more than "if any flag then danger"
 *
 * The naive rule — *active mint or freeze authority means DANGER* — flags **USDC, USDT and PYUSD**
 * as rugs. That is not a hypothetical: all three report `mintable.status == "1"` and
 * `freezable.status == "1"` on GoPlus, and both a live mint authority and a live freeze authority on
 * RugCheck, because Circle and Tether genuinely retain those powers. A tool that cries wolf on USDC
 * on its first screen is a tool nobody opens twice.
 *
 * So authority is treated as **capability, not intent**. A token whose issuer is independently
 * attested ([GoPlusReport.trustedToken] or [RugCheckReport.jupVerified]) gets its live authorities
 * reported at [Severity.WARN] — visible, explained, not fatal. An *unattested* token with a live
 * mint or freeze authority stays [Severity.DANGER], because that is the classic rug shape.
 *
 * The rules that are unconditional — no trust flag can excuse them — are the ones that remove the
 * holder's ability to move their own tokens: a permanent delegate, a transfer hook, a frozen
 * default account state, non-transferability, or a balance-mutable authority.
 *
 * Every threshold below is a named constant so the policy can be argued about and changed in one
 * place. The values were chosen against the live fixtures in `com.clockin.preflight.data.fixtures`.
 */
object RiskPolicy {

    // -----------------------------------------------------------------------------------------
    // Thresholds
    // -----------------------------------------------------------------------------------------

    /**
     * RugCheck `score_normalised` (0..100, higher is riskier) at or above which the score alone is
     * a danger. Measured context: USDC is 1, a low-liquidity pump.fun Token-2022 mint is 29.
     */
    const val RUGCHECK_DANGER_SCORE = 50

    /** At or above this, the score is worth a warning even with no named risk behind it. */
    const val RUGCHECK_CAUTION_SCORE = 20

    /**
     * Share of supply a *non-infrastructure* holder may hold before we flag concentration.
     * Applied per holder after excluding AMM pools, lockers and other known accounts.
     */
    const val HOLDER_CONCENTRATION_WARN_PCT = 50.0

    /** At or above this, a single non-infrastructure holder can dump the token at will. */
    const val HOLDER_CONCENTRATION_DANGER_PCT = 80.0

    /**
     * RugCheck's insider-graph count. A token whose holders cluster into known insider networks is
     * a coordinated-supply risk; single-digit counts are common and benign.
     */
    const val INSIDER_WARN_THRESHOLD = 100

    /** A transfer fee at or above 10% makes the token practically unsellable — a honeypot shape. */
    const val TRANSFER_FEE_DANGER_RATE = 0.10

    /**
     * Market liquidity in USD below which we warn on our own account, used only when RugCheck has
     * indexed holders (otherwise its liquidity fields are simply absent, as they are for USDC).
     */
    const val LOW_LIQUIDITY_WARN_USD = 10_000.0

    /** Below this, the position cannot be exited at all in practice. */
    const val LOW_LIQUIDITY_DANGER_USD = 1_000.0

    // -----------------------------------------------------------------------------------------
    // Flag identifiers, stable enough for tests and UI copy
    // -----------------------------------------------------------------------------------------

    object Flags {
        const val RUGCHECK_SCORE = "rugcheck.score"
        const val RUGCHECK_RISK = "rugcheck.risk"
        const val RUGCHECK_MINT_AUTHORITY = "rugcheck.mint_authority"
        const val RUGCHECK_FREEZE_AUTHORITY = "rugcheck.freeze_authority"
        const val RUGCHECK_RUGGED = "rugcheck.rugged"
        const val RUGCHECK_NON_TRANSFERABLE = "rugcheck.non_transferable"
        const val RUGCHECK_PERMANENT_DELEGATE = "rugcheck.permanent_delegate"
        const val RUGCHECK_TRANSFER_HOOK = "rugcheck.transfer_hook"
        const val RUGCHECK_DEFAULT_FROZEN = "rugcheck.default_frozen"
        const val RUGCHECK_MINT_CLOSE_AUTHORITY = "rugcheck.mint_close_authority"
        const val RUGCHECK_PAUSABLE = "rugcheck.pausable"
        const val RUGCHECK_TRANSFER_FEE = "rugcheck.transfer_fee"
        const val RUGCHECK_CONCENTRATION = "rugcheck.holder_concentration"
        const val RUGCHECK_INSIDERS = "rugcheck.insider_networks"

        const val GOPLUS_MINTABLE = "goplus.mintable"
        const val GOPLUS_FREEZABLE = "goplus.freezable"
        const val GOPLUS_CLOSABLE = "goplus.closable"
        const val GOPLUS_BALANCE_MUTABLE = "goplus.balance_mutable_authority"
        const val GOPLUS_METADATA_MUTABLE = "goplus.metadata_mutable"
        const val GOPLUS_TRANSFER_HOOK = "goplus.transfer_hook"
        const val GOPLUS_TRANSFER_HOOK_UPGRADABLE = "goplus.transfer_hook_upgradable"
        const val GOPLUS_DEFAULT_FROZEN = "goplus.default_frozen"
        const val GOPLUS_DEFAULT_STATE_UPGRADABLE = "goplus.default_account_state_upgradable"
        const val GOPLUS_NON_TRANSFERABLE = "goplus.non_transferable"
        const val GOPLUS_TRANSFER_FEE = "goplus.transfer_fee"
        const val GOPLUS_TRANSFER_FEE_UPGRADABLE = "goplus.transfer_fee_upgradable"
        const val GOPLUS_MALICIOUS_ADDRESS = "goplus.malicious_address"
        const val GOPLUS_TRUSTED = "goplus.trusted_token"

        const val DERIVED_LOW_LIQUIDITY = "derived.low_liquidity"
    }

    // -----------------------------------------------------------------------------------------
    // Evaluation
    // -----------------------------------------------------------------------------------------

    /**
     * Merges whatever answered into a single [TokenRisk].
     *
     * A missing source is not a failure of this function: it narrows [TokenRisk.sources] and
     * records the reason in [TokenRisk.sourceErrors], so the UI can say "checked against GoPlus
     * only" instead of pretending the picture is complete.
     */
    fun evaluate(
        mint: String,
        rugcheck: RugCheckReport?,
        goplus: GoPlusReport?,
        sourceErrors: Map<RiskSource, String> = emptyMap(),
    ): TokenRisk {
        val flags = ArrayList<TokenFlag>()

        // An attestation from either source softens *authority* findings only. It never softens a
        // finding that takes away the holder's ability to move their own tokens.
        val attested = (goplus?.trustedToken == true) || (rugcheck?.jupVerified == true)

        rugcheck?.let { flags += rugCheckFlags(it, attested) }
        goplus?.let { flags += goPlusFlags(it, attested) }
        rugcheck?.let { flags += derivedFlags(it) }

        val sources = buildList {
            if (rugcheck != null) add(RiskSource.RUGCHECK)
            if (goplus != null) add(RiskSource.GOPLUS)
        }

        val sorted = flags.sortedWith(
            compareByDescending<TokenFlag> { it.severity.ordinal }.thenBy { it.id },
        )

        val verdict = when {
            sources.isEmpty() -> Verdict.UNKNOWN
            sorted.any { it.severity == Severity.DANGER } -> Verdict.DANGER
            sorted.any { it.severity == Severity.WARN } -> Verdict.CAUTION
            else -> Verdict.SAFE
        }

        return TokenRisk(
            mint = mint,
            score = rugcheck?.scoreNormalised,
            rawScore = rugcheck?.score,
            verdict = verdict,
            flags = sorted,
            sources = sources,
            sourceErrors = sourceErrors,
            name = rugcheck?.name ?: goplus?.name,
            symbol = rugcheck?.symbol ?: goplus?.symbol,
        )
    }

    // -----------------------------------------------------------------------------------------

    private fun rugCheckFlags(report: RugCheckReport, attested: Boolean): List<TokenFlag> {
        val flags = ArrayList<TokenFlag>()

        // RugCheck's own named risks carry the most context, so surface them verbatim.
        report.risks.forEachIndexed { index, risk ->
            val title = risk.name ?: "RugCheck risk ${index + 1}"
            flags += TokenFlag(
                id = "${Flags.RUGCHECK_RISK}.${slug(title)}",
                title = title,
                detail = listOfNotNull(risk.description, risk.value?.let { "observed: $it" })
                    .joinToString(" — ")
                    .ifBlank { "flagged by RugCheck" },
                severity = when {
                    risk.isDanger -> Severity.DANGER
                    risk.isWarn -> Severity.WARN
                    else -> Severity.INFO
                },
                source = RiskSource.RUGCHECK,
            )
        }

        report.scoreNormalised?.let { score ->
            when {
                score >= RUGCHECK_DANGER_SCORE -> flags += TokenFlag(
                    Flags.RUGCHECK_SCORE,
                    "Risk score $score/100",
                    "RugCheck scores this mint $score out of 100; $RUGCHECK_DANGER_SCORE or more is a danger.",
                    Severity.DANGER,
                    RiskSource.RUGCHECK,
                )
                score >= RUGCHECK_CAUTION_SCORE -> flags += TokenFlag(
                    Flags.RUGCHECK_SCORE,
                    "Elevated risk score $score/100",
                    "RugCheck scores this mint $score out of 100 (warning band starts at $RUGCHECK_CAUTION_SCORE).",
                    Severity.WARN,
                    RiskSource.RUGCHECK,
                )
            }
        }

        if (report.rugged) {
            flags += TokenFlag(
                Flags.RUGCHECK_RUGGED,
                "Already rugged",
                "RugCheck has marked this mint as rugged.",
                Severity.DANGER,
                RiskSource.RUGCHECK,
            )
        }

        report.mintAuthority?.let { authority ->
            flags += authorityFlag(
                id = Flags.RUGCHECK_MINT_AUTHORITY,
                capability = "the mint authority is still live",
                consequence = "whoever holds $authority can mint unlimited new supply and dilute you to zero.",
                authority = authority,
                attested = attested,
                source = RiskSource.RUGCHECK,
            )
        }

        report.freezeAuthority?.let { authority ->
            flags += authorityFlag(
                id = Flags.RUGCHECK_FREEZE_AUTHORITY,
                capability = "the freeze authority is still live",
                consequence = "whoever holds $authority can freeze your token account so you cannot sell.",
                authority = authority,
                attested = attested,
                source = RiskSource.RUGCHECK,
            )
        }

        report.tokenExtensions?.let { extensions ->
            if (extensions.nonTransferable) {
                flags += TokenFlag(
                    Flags.RUGCHECK_NON_TRANSFERABLE,
                    "Non-transferable token",
                    "The Token-2022 non-transferable extension is set, so this token cannot be moved between accounts at all.",
                    Severity.DANGER,
                    RiskSource.RUGCHECK,
                )
            }
            extensions.permanentDelegateAddress?.let { delegate ->
                flags += TokenFlag(
                    Flags.RUGCHECK_PERMANENT_DELEGATE,
                    "Permanent delegate",
                    "Token-2022 gives $delegate permanent authority to move tokens out of any account, including yours.",
                    Severity.DANGER,
                    RiskSource.RUGCHECK,
                )
            }
            extensions.transferHookAddress?.let { hook ->
                flags += TokenFlag(
                    Flags.RUGCHECK_TRANSFER_HOOK,
                    "Transfer hook",
                    "Every transfer runs through $hook, which can reject your sell at its own discretion.",
                    Severity.DANGER,
                    RiskSource.RUGCHECK,
                )
            }
            if (extensions.isDefaultFrozen) {
                flags += TokenFlag(
                    Flags.RUGCHECK_DEFAULT_FROZEN,
                    "New accounts start frozen",
                    "Token-2022's default account state is Frozen, so accounts arrive locked until the issuer unfreezes them.",
                    Severity.DANGER,
                    RiskSource.RUGCHECK,
                )
            }
            extensions.mintCloseAuthorityAddress?.let { closeAuthority ->
                flags += authorityFlag(
                    id = Flags.RUGCHECK_MINT_CLOSE_AUTHORITY,
                    capability = "the mint can still be closed",
                    consequence = "$closeAuthority can close the mint account and wipe the token out of existence.",
                    authority = closeAuthority,
                    attested = attested,
                    source = RiskSource.RUGCHECK,
                )
            }
            if (extensions.pausableConfig != null) {
                flags += TokenFlag(
                    Flags.RUGCHECK_PAUSABLE,
                    "Transfers can be paused",
                    "The Token-2022 pausable extension is present, so the issuer can halt all transfers.",
                    Severity.DANGER,
                    RiskSource.RUGCHECK,
                )
            }
        }

        report.transferFeePct?.takeIf { it > 0.0 }?.let { percent ->
            // RugCheck reports `pct` as a fraction (0.02 means 2%), GoPlus as a ratio too.
            flags += TokenFlag(
                Flags.RUGCHECK_TRANSFER_FEE,
                "Transfer fee ${formatPercent(percent)}",
                if (percent >= TRANSFER_FEE_DANGER_RATE) {
                    "A ${formatPercent(percent)} fee is charged on every transfer, which makes selling impractical."
                } else {
                    "A ${formatPercent(percent)} fee is charged on every transfer."
                },
                if (percent >= TRANSFER_FEE_DANGER_RATE) Severity.DANGER else Severity.WARN,
                RiskSource.RUGCHECK,
            )
        }

        concentratedHolder(report)?.let { (holder, pct) ->
            flags += TokenFlag(
                Flags.RUGCHECK_CONCENTRATION,
                "One wallet holds ${"%.1f".format(pct)}%",
                "A single non-pool wallet (${holder.address}) holds ${"%.1f".format(pct)}% of the supply and can dump it in one trade.",
                if (pct >= HOLDER_CONCENTRATION_DANGER_PCT) Severity.DANGER else Severity.WARN,
                RiskSource.RUGCHECK,
            )
        }

        report.insiderNetworks?.takeIf { it > 0 }?.let { networks ->
            flags += TokenFlag(
                Flags.RUGCHECK_INSIDERS,
                "Insider network detected",
                "RugCheck linked the holder graph to $networks known insider network(s).",
                Severity.DANGER,
                RiskSource.RUGCHECK,
            )
        } ?: report.graphInsidersDetected?.takeIf { it >= INSIDER_WARN_THRESHOLD }?.let { count ->
            flags += TokenFlag(
                Flags.RUGCHECK_INSIDERS,
                "$count insider-linked wallets",
                "RugCheck found $count wallets in the holder graph connected to known insiders.",
                Severity.WARN,
                RiskSource.RUGCHECK,
            )
        }

        return flags
    }

    /**
     * The largest holder that is *not* recognisable infrastructure.
     *
     * RugCheck maps token accounts to their owners and flags pools and lockers in `knownAccounts`.
     * Skipping those is essential: JETPACK's largest account holds 90% of supply and is simply the
     * Pump.Fun AMM pool, which is where the liquidity is supposed to live. A concentration check
     * that ignored this mapping would label almost every liquid token a rug.
     */
    private fun concentratedHolder(report: RugCheckReport): Pair<RugHolder, Double>? =
        report.topHolders
            .asSequence()
            .filter { it.pct >= HOLDER_CONCENTRATION_WARN_PCT }
            .filterNot { holder ->
                val known = report.knownAccounts
                known[holder.address]?.isInfrastructure == true ||
                    known[holder.owner]?.isInfrastructure == true
            }
            .maxByOrNull { it.pct }
            ?.let { it to it.pct }

    private fun authorityFlag(
        id: String,
        capability: String,
        consequence: String,
        authority: String,
        attested: Boolean,
        source: RiskSource,
    ): TokenFlag = TokenFlag(
        id = id,
        title = if (attested) "Issuer retains control" else "Unrenounced authority",
        detail = if (attested) {
            "This token is attested as well known, but $capability: $consequence"
        } else {
            "This token has no attestation and $capability: $consequence"
        },
        severity = if (attested) Severity.WARN else Severity.DANGER,
        source = source,
    )

    private fun goPlusFlags(report: GoPlusReport, attested: Boolean): List<TokenFlag> {
        val flags = ArrayList<TokenFlag>()

        if (report.trustedToken) {
            flags += TokenFlag(
                Flags.GOPLUS_TRUSTED,
                "Listed as a well-known token",
                "GoPlus classifies this mint as famous and trustworthy, so live issuer authorities below are treated as expected behaviour.",
                Severity.INFO,
                RiskSource.GOPLUS,
            )
        }

        fun capabilityFlag(
            id: String,
            capability: GoPlusCapability,
            capabilityText: String,
            consequence: String,
            attestedSeverity: Severity = Severity.WARN,
            unconditional: Boolean = false,
        ) {
            if (!capability.enabled) return
            val holders = capability.authorities.joinToString(", ") { it.address }.ifBlank { "the issuer" }
            flags += TokenFlag(
                id = id,
                title = if (attested && !unconditional) "Issuer retains control" else "Unrenounced authority",
                detail = if (attested && !unconditional) {
                    "Attested as well known, but $capabilityText: $holders $consequence"
                } else {
                    "$capabilityText: $holders $consequence"
                },
                severity = if (attested && !unconditional) attestedSeverity else Severity.DANGER,
                source = RiskSource.GOPLUS,
            )
        }

        capabilityFlag(
            Flags.GOPLUS_MINTABLE,
            report.mintable,
            "the token is still mintable",
            "can create unlimited new supply and dilute you to zero.",
        )
        capabilityFlag(
            Flags.GOPLUS_FREEZABLE,
            report.freezable,
            "the token is still freezable",
            "can freeze your account so you cannot sell.",
        )
        capabilityFlag(
            Flags.GOPLUS_CLOSABLE,
            report.closable,
            "the mint can still be closed",
            "can close the mint and wipe the token out of existence.",
        )
        // Balance mutability is unconditional: an issuer that can rewrite balances does not need
        // your signature to take your tokens, so no attestation makes it acceptable.
        capabilityFlag(
            Flags.GOPLUS_BALANCE_MUTABLE,
            report.balanceMutableAuthority,
            "the balance-mutable authority is live",
            "can rewrite token balances directly, including yours.",
            unconditional = true,
        )

        if (report.metadataMutable.enabled) {
            flags += TokenFlag(
                Flags.GOPLUS_METADATA_MUTABLE,
                "Metadata can still be changed",
                "The metadata upgrade authority is live, so the name, symbol or logo can be swapped after you buy — the standard impersonation trick.",
                if (attested) Severity.INFO else Severity.WARN,
                RiskSource.GOPLUS,
            )
        }

        if (report.transferHooks.isNotEmpty()) {
            val hooks = report.transferHooks.joinToString(", ") { it.address }
            flags += TokenFlag(
                Flags.GOPLUS_TRANSFER_HOOK,
                "Transfer hook",
                "Every transfer runs through $hooks, which can reject your sell at its own discretion.",
                Severity.DANGER,
                RiskSource.GOPLUS,
            )
        }

        // An upgradable hook is a hook that does not exist yet but can be added after you buy.
        if (report.transferHookUpgradable.enabled) {
            flags += TokenFlag(
                Flags.GOPLUS_TRANSFER_HOOK_UPGRADABLE,
                "Transfer hook can be added later",
                "The transfer-hook authority is live, so a hook that blocks selling can be attached to this mint at any time.",
                Severity.DANGER,
                RiskSource.GOPLUS,
            )
        }

        if (report.nonTransferable) {
            flags += TokenFlag(
                Flags.GOPLUS_NON_TRANSFERABLE,
                "Non-transferable token",
                "This token cannot be transferred between accounts at all.",
                Severity.DANGER,
                RiskSource.GOPLUS,
            )
        }

        // "2" is Frozen. "1" is Initialized — the ordinary, tradeable state — and GoPlus returns
        // "1" for both USDC and every brand-new pump.fun mint, so it must not be treated as frozen.
        if (report.isDefaultFrozen) {
            flags += TokenFlag(
                Flags.GOPLUS_DEFAULT_FROZEN,
                "New accounts start frozen",
                "GoPlus reports the default account state as Frozen (2), so accounts arrive locked until the issuer unfreezes them.",
                Severity.DANGER,
                RiskSource.GOPLUS,
            )
        } else if (report.defaultAccountStateUpgradable.enabled) {
            flags += TokenFlag(
                Flags.GOPLUS_DEFAULT_STATE_UPGRADABLE,
                "Default account state can be changed",
                "The default-account-state authority is live, so new accounts can be made to start frozen later.",
                Severity.WARN,
                RiskSource.GOPLUS,
            )
        }

        if (report.hasTransferFee) {
            val rate = report.transferFeeRate
            flags += TokenFlag(
                Flags.GOPLUS_TRANSFER_FEE,
                if (rate != null) "Transfer fee ${formatPercent(rate)}" else "Transfer fee configured",
                if (rate != null && rate >= TRANSFER_FEE_DANGER_RATE) {
                    "A ${formatPercent(rate)} fee is charged on every transfer, which makes selling impractical."
                } else {
                    "A transfer fee is charged on every transfer of this token."
                },
                if (rate != null && rate >= TRANSFER_FEE_DANGER_RATE) Severity.DANGER else Severity.WARN,
                RiskSource.GOPLUS,
            )
        }
        if (report.transferFeeUpgradable.enabled) {
            flags += TokenFlag(
                Flags.GOPLUS_TRANSFER_FEE_UPGRADABLE,
                "Transfer fee can be raised",
                "The transfer-fee authority is live, so the fee can be increased after you buy.",
                Severity.WARN,
                RiskSource.GOPLUS,
            )
        }

        if (report.hasMaliciousAddress) {
            val offenders = buildList {
                listOf(
                    report.mintable, report.freezable, report.closable,
                    report.balanceMutableAuthority, report.metadataMutable,
                ).forEach { addAll(it.authorities) }
                addAll(report.transferHooks)
                addAll(report.creators)
            }.filter { it.malicious }.map { it.address }.distinct()
            flags += TokenFlag(
                Flags.GOPLUS_MALICIOUS_ADDRESS,
                "Address flagged as malicious",
                "GoPlus lists ${offenders.joinToString(", ")} as malicious in connection with this token.",
                Severity.DANGER,
                RiskSource.GOPLUS,
            )
        }

        return flags
    }

    /**
     * Findings we compute ourselves rather than read from a source.
     *
     * Kept deliberately narrow. In particular we do **not** derive a concentration flag from
     * GoPlus' holder list: GoPlus reports token accounts without telling us which of them are
     * liquidity pools, so any threshold there would fire on healthy tokens. RugCheck's holder list
     * has the owner mapping that makes the check meaningful, so that is where concentration is
     * judged.
     */
    private fun derivedFlags(report: RugCheckReport): List<TokenFlag> {
        val flags = ArrayList<TokenFlag>()

        // RugCheck only populates liquidity for mints it has actually indexed; USDC reports
        // `totalHolders: 0` with zero liquidity, which is an indexing gap and not a rug signal.
        val hasHolderData = (report.totalHolders ?: 0) > 0
        val liquidity = report.totalMarketLiquidity
        val alreadyReported = report.risks.any { it.name?.contains("liquidity", ignoreCase = true) == true }
        if (hasHolderData && liquidity != null && !alreadyReported && liquidity < LOW_LIQUIDITY_WARN_USD) {
            flags += TokenFlag(
                Flags.DERIVED_LOW_LIQUIDITY,
                "Thin liquidity",
                "Only ${"%,.0f".format(liquidity)} USD of market liquidity backs this mint, so even a small sell moves the price hard.",
                if (liquidity < LOW_LIQUIDITY_DANGER_USD) Severity.DANGER else Severity.WARN,
                RiskSource.DERIVED,
            )
        }

        return flags
    }

    // -----------------------------------------------------------------------------------------

    private fun formatPercent(fraction: Double): String = "%.2f%%".format(fraction * 100)

    private fun slug(value: String): String =
        value.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_')
}

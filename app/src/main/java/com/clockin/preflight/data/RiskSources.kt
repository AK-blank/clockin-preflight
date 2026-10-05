package com.clockin.preflight.data

/**
 * Parsers for the two live token-risk sources, plus the typed reports they produce.
 *
 * Every read is lenient: a missing or retyped field becomes `null`, never an exception. Both APIs
 * ship fields without notice — GoPlus omits `holder_count` entirely for tokens it has not indexed,
 * and RugCheck returns `topHolders: null` (not `[]`) for blue chips like USDC. A parser that
 * insisted on those fields would report "unreadable" for perfectly good data.
 *
 * Raw responses captured for the tests live in `com.clockin.preflight.data.fixtures`.
 */

// ---------------------------------------------------------------------------------------------
// RugCheck — https://api.rugcheck.xyz/v1/tokens/{mint}/report
// ---------------------------------------------------------------------------------------------

/** One entry of RugCheck's own `risks` array. [level] is `danger`, `warn` or `info`. */
data class RugRisk(
    val name: String?,
    val level: String?,
    val description: String?,
    val value: String?,
    val score: Long?,
) {
    val isDanger: Boolean get() = level.equals("danger", ignoreCase = true)
    val isWarn: Boolean get() = level.equals("warn", ignoreCase = true) || level.equals("warning", ignoreCase = true)
}

/** A holder row. [pct] is already a percentage (90.04 means 90.04%). */
data class RugHolder(
    val address: String,
    val owner: String?,
    val pct: Double,
)

/** An address RugCheck recognises: an AMM pool, a locker, the creator, a known exchange. */
data class RugKnownAccount(
    val address: String,
    val name: String?,
    val type: String?,
) {
    /** True for liquidity pools and lockers — the two kinds that are *supposed* to hold a lot. */
    val isInfrastructure: Boolean
        get() = type?.uppercase() in setOf("AMM", "LOCKER", "MARKET", "POOL", "VAULT")
}

/**
 * The Token-2022 extension block. Present only for Token-2022 mints; `null` for classic SPL tokens.
 *
 * These are the highest-signal fields in the whole payload: a permanent delegate or a transfer hook
 * hands the issuer the ability to move or block a holder's tokens, which no amount of liquidity
 * makes acceptable.
 */
data class RugTokenExtensions(
    val nonTransferable: Boolean,
    val defaultAccountState: JsonValue?,
    val permanentDelegateAddress: String?,
    val transferHookAddress: String?,
    val mintCloseAuthorityAddress: String?,
    val transferFeeConfig: JsonValue?,
    val pausableConfig: JsonValue?,
    val raw: JsonValue.Obj,
) {
    val hasPermanentDelegate: Boolean get() = permanentDelegateAddress != null
    val hasTransferHook: Boolean get() = transferHookAddress != null
    val hasMintCloseAuthority: Boolean get() = mintCloseAuthorityAddress != null

    /**
     * True only for a *frozen* default state.
     *
     * Token-2022's `DefaultAccountState` is `0 Uninitialized / 1 Initialized / 2 Frozen`; `1` is the
     * ordinary, freely-tradable state. Treating the extension's mere presence as "frozen" is the
     * single most common false positive in Solana token scanners.
     */
    val isDefaultFrozen: Boolean
        get() = defaultAccountState?.let { state ->
            state.asStr()?.equals("frozen", ignoreCase = true) == true ||
                state.asObj()?.let { obj ->
                    obj.str("state")?.equals("frozen", ignoreCase = true) == true ||
                        obj.int("state") == 2 ||
                        obj.int("accountState") == 2
                } == true
        } ?: false
}

/** A parsed RugCheck report. */
data class RugCheckReport(
    val mint: String,
    val tokenProgram: String?,
    val creator: String?,
    val name: String?,
    val symbol: String?,
    val mintAuthority: String?,
    val freezeAuthority: String?,
    val supply: Long?,
    val decimals: Int?,
    val score: Long?,
    val scoreNormalised: Int?,
    val rugged: Boolean,
    val risks: List<RugRisk>,
    val topHolders: List<RugHolder>,
    val knownAccounts: Map<String, RugKnownAccount>,
    val lockers: List<String>,
    val lockerScanStatus: String?,
    val totalMarketLiquidity: Double?,
    val totalStableLiquidity: Double?,
    val totalHolders: Int?,
    val transferFeePct: Double?,
    val transferFeeAuthority: String?,
    val jupVerified: Boolean,
    val graphInsidersDetected: Int?,
    val insiderNetworks: Int?,
    val deployPlatform: String?,
    val launchpad: String?,
    val tokenExtensions: RugTokenExtensions?,
    val raw: JsonValue.Obj,
) {
    val hasMintAuthority: Boolean get() = mintAuthority != null
    val hasFreezeAuthority: Boolean get() = freezeAuthority != null

    /** All risk levels RugCheck itself reported, for scoring. */
    val dangerRisks: List<RugRisk> get() = risks.filter { it.isDanger }
}

object RugCheckParser {

    /** Throws [JsonParseException] only when the body is not JSON at all or lacks a `mint`. */
    fun parse(body: String): RugCheckReport {
        val root = JsonParser.parseObject(body)
        val mint = root.nonBlankStr("mint")
            ?: throw JsonParseException("RugCheck report has no `mint` field", 0)
        val token = root.obj("token")
        val meta = root.obj("tokenMeta")
        val verification = root.obj("verification")
        val transferFee = root.obj("transferFee")
        return RugCheckReport(
            mint = mint,
            tokenProgram = root.nonBlankStr("tokenProgram"),
            creator = root.nonBlankStr("creator"),
            name = meta?.nonBlankStr("name"),
            symbol = meta?.nonBlankStr("symbol"),
            // Explicitly null for a revoked authority — that is the good case, not missing data.
            mintAuthority = token?.nonBlankStr("mintAuthority"),
            freezeAuthority = token?.nonBlankStr("freezeAuthority"),
            supply = token?.long("supply"),
            decimals = token?.int("decimals"),
            score = root.long("score"),
            scoreNormalised = root.int("score_normalised"),
            rugged = root.bool("rugged") ?: false,
            risks = root.objList("risks").map { risk ->
                RugRisk(
                    name = risk.nonBlankStr("name"),
                    level = risk.nonBlankStr("level"),
                    description = risk.nonBlankStr("description"),
                    value = risk.nonBlankStr("value"),
                    score = risk.long("score"),
                )
            },
            // `topHolders` is `null`, not `[]`, for large established mints.
            topHolders = root.objList("topHolders").mapNotNull { holder ->
                val address = holder.nonBlankStr("address") ?: return@mapNotNull null
                RugHolder(
                    address = address,
                    owner = holder.nonBlankStr("owner"),
                    pct = holder.double("pct") ?: 0.0,
                )
            },
            knownAccounts = root.obj("knownAccounts")?.fields.orEmpty().mapNotNull { (address, value) ->
                val entry = value.asObj() ?: return@mapNotNull null
                address to RugKnownAccount(address, entry.nonBlankStr("name"), entry.nonBlankStr("type"))
            }.toMap(),
            lockers = root.obj("lockers")?.fields?.keys?.toList().orEmpty(),
            lockerScanStatus = root.nonBlankStr("lockerScanStatus"),
            totalMarketLiquidity = root.double("totalMarketLiquidity"),
            totalStableLiquidity = root.double("totalStableLiquidity"),
            totalHolders = root.int("totalHolders"),
            transferFeePct = transferFee?.double("pct"),
            transferFeeAuthority = transferFee?.nonBlankStr("authority"),
            jupVerified = verification?.bool("jup_verified") ?: false,
            graphInsidersDetected = root.int("graphInsidersDetected"),
            insiderNetworks = root.int("insiderNetworks"),
            deployPlatform = root.nonBlankStr("deployPlatform"),
            launchpad = root.obj("launchpad")?.nonBlankStr("name"),
            tokenExtensions = parseExtensions(root.obj("token_extensions")),
            raw = root,
        )
    }

    private fun parseExtensions(extensions: JsonValue.Obj?): RugTokenExtensions? {
        extensions ?: return null
        return RugTokenExtensions(
            nonTransferable = extensions.bool("nonTransferable") ?: false,
            defaultAccountState = extensions["defaultAccountState"],
            permanentDelegateAddress = extensions.obj("permanentDelegate")?.nonBlankStr("delegate"),
            transferHookAddress = extensions.obj("transferHook")?.nonBlankStr("programId"),
            mintCloseAuthorityAddress = extensions.obj("mintCloseAuthority")?.nonBlankStr("closeAuthority"),
            transferFeeConfig = extensions["transferFeeConfig"],
            pausableConfig = extensions["pausableConfig"],
            raw = extensions,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// GoPlus — https://api.gopluslabs.io/api/v1/solana/token_security?contract_addresses={mint}
// ---------------------------------------------------------------------------------------------

/** An address GoPlus flagged, optionally as malicious. */
data class GoPlusAuthorityRef(
    val address: String,
    val malicious: Boolean,
)

/**
 * GoPlus' uniform "is this capability available, and to whom" triple.
 *
 * `status == "1"` means the capability is live. It says nothing about intent — USDC reports
 * `mintable.status == "1"` because Circle can mint, which is exactly what you would expect from a
 * regulated stablecoin.
 */
data class GoPlusCapability(
    val enabled: Boolean,
    val authorities: List<GoPlusAuthorityRef>,
) {
    companion object {
        val ABSENT = GoPlusCapability(enabled = false, authorities = emptyList())
    }
}

/** A GoPlus holder row. [percent] is a percentage (e.g. `12.5`). */
data class GoPlusHolder(
    val address: String?,
    val percent: Double?,
    val isLocked: Boolean,
    val tag: String?,
)

/** A parsed GoPlus token-security entry. */
data class GoPlusReport(
    val mint: String,
    val name: String?,
    val symbol: String?,
    val mintable: GoPlusCapability,
    val freezable: GoPlusCapability,
    val closable: GoPlusCapability,
    val balanceMutableAuthority: GoPlusCapability,
    val metadataMutable: GoPlusCapability,
    val transferFeeUpgradable: GoPlusCapability,
    val transferHookUpgradable: GoPlusCapability,
    val defaultAccountStateUpgradable: GoPlusCapability,
    val transferHooks: List<GoPlusAuthorityRef>,
    /** `"0"` uninitialized, `"1"` initialized (normal), `"2"` frozen. */
    val defaultAccountState: String?,
    val nonTransferable: Boolean,
    val hasTransferFee: Boolean,
    val transferFeeRate: Double?,
    val trustedToken: Boolean,
    val topHolders: List<GoPlusHolder>,
    val lpHolders: List<GoPlusHolder>,
    val holderCount: Long?,
    val totalSupply: String?,
    val creators: List<GoPlusAuthorityRef>,
    val raw: JsonValue.Obj,
) {
    /**
     * Whether any address named in this report is on GoPlus' malicious list. A malicious *creator*
     * is as damning as a malicious authority, so both are folded in.
     */
    val hasMaliciousAddress: Boolean
        get() = (listOf(mintable, freezable, closable, balanceMutableAuthority, metadataMutable) +
            listOf(transferFeeUpgradable, transferHookUpgradable, defaultAccountStateUpgradable) +
            listOf(GoPlusCapability(false, transferHooks), GoPlusCapability(false, creators)))
            .any { capability -> capability.authorities.any { it.malicious } }

    /**
     * True only when the default account state is actually Frozen (`"2"`).
     *
     * GoPlus returns `"1"` for USDC and for every ordinary pump.fun token; treating `"1"` as frozen
     * would flag essentially the whole chain.
     */
    val isDefaultFrozen: Boolean get() = defaultAccountState?.trim() == "2"
}

object GoPlusParser {

    /**
     * Parses a `token_security` envelope.
     *
     * Returns `null` when the envelope is fine but carries no entry for [mint] — GoPlus answers
     * `{"code":1,"result":{}}` for any mint it has not indexed, which is a normal answer for a
     * brand-new token, not a parse failure.
     */
    fun parse(body: String, mint: String): GoPlusReport? {
        val root = JsonParser.parseObject(body)
        val result = root.obj("result") ?: return null
        val entry = result.obj(mint) ?: return null
        val metadata = entry.obj("metadata")
        return GoPlusReport(
            mint = mint,
            name = metadata?.nonBlankStr("name"),
            symbol = metadata?.nonBlankStr("symbol"),
            mintable = capability(entry.obj("mintable"), "authority"),
            freezable = capability(entry.obj("freezable"), "authority"),
            closable = capability(entry.obj("closable"), "authority"),
            balanceMutableAuthority = capability(entry.obj("balance_mutable_authority"), "authority"),
            metadataMutable = capability(entry.obj("metadata_mutable"), "metadata_upgrade_authority"),
            transferFeeUpgradable = capability(entry.obj("transfer_fee_upgradable"), "authority"),
            transferHookUpgradable = capability(entry.obj("transfer_hook_upgradable"), "authority"),
            defaultAccountStateUpgradable = capability(entry.obj("default_account_state_upgradable"), "authority"),
            transferHooks = authorityRefs(entry.arr("transfer_hook")),
            defaultAccountState = entry.nonBlankStr("default_account_state"),
            // The live API spells this `non_transferable`; GoPlus' own docs call it
            // `none_transferable`. Accept both so a docs-driven rename cannot blind us.
            nonTransferable = entry.flag("non_transferable") ?: entry.flag("none_transferable") ?: false,
            hasTransferFee = entry.obj("transfer_fee")?.fields?.isNotEmpty() == true,
            transferFeeRate = entry.obj("transfer_fee")?.obj("current_fee_rate")?.double("fee_rate"),
            trustedToken = entry.int("trusted_token") == 1 || entry.bool("trusted_token") == true,
            topHolders = holders(entry.arr("holders")),
            lpHolders = holders(entry.arr("lp_holders")),
            // Sent as the string "9193555", not a number — and omitted entirely for unindexed mints.
            holderCount = entry.longLenient("holder_count"),
            totalSupply = entry.nonBlankStr("total_supply"),
            creators = authorityRefs(entry.arr("creators")),
            raw = entry,
        )
    }

    private fun capability(source: JsonValue.Obj?, authorityKey: String): GoPlusCapability {
        source ?: return GoPlusCapability.ABSENT
        return GoPlusCapability(
            enabled = source.flag("status") == true,
            authorities = authorityRefs(source.arr(authorityKey)),
        )
    }

    private fun authorityRefs(items: List<JsonValue>?): List<GoPlusAuthorityRef> =
        items.orEmpty().mapNotNull { item ->
            val obj = item.asObj() ?: return@mapNotNull null
            val address = obj.nonBlankStr("address") ?: return@mapNotNull null
            GoPlusAuthorityRef(address, malicious = obj.int("malicious_address") == 1)
        }

    private fun holders(items: List<JsonValue>?): List<GoPlusHolder> =
        items.orEmpty().mapNotNull { item ->
            val obj = item.asObj() ?: return@mapNotNull null
            GoPlusHolder(
                // `account` is the wallet that owns the tokens; `token_account` is the ATA itself.
                // Prefer the wallet — it is the thing a human can act on.
                address = obj.nonBlankStr("account")
                    ?: obj.nonBlankStr("token_account")
                    ?: obj.nonBlankStr("address"),
                // GoPlus sends percentages as strings ("0.1415") and sometimes as numbers.
                percent = obj.doubleLenient("percent"),
                isLocked = obj.int("is_locked") == 1 || obj.flag("is_locked") == true,
                tag = obj.nonBlankStr("tag"),
            )
        }
}

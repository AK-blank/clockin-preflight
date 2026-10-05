package com.clockin.preflight.engine

/** How bad a single finding is. Weights feed the 0-100 aggregate score. */
enum class Severity(val rank: Int, val weight: Int) {
    INFO(0, 0),
    LOW(1, 4),
    MEDIUM(2, 20),
    HIGH(3, 40),
    CRITICAL(4, 70);

    val label: String get() = name.lowercase()
}

/** One reason to be worried, phrased for a human. */
data class RiskFinding(
    val severity: Severity,
    /** Stable identifier, e.g. `UNLIMITED_TOKEN_APPROVAL`. Safe to branch on. */
    val code: String,
    /** Short title, e.g. `Unlimited token approval`. */
    val title: String,
    /** The sentence we show the user. Specific: amount, asset, counterparty, consequence. */
    val plainEnglish: String,
    /** Machine-readable supporting facts, for the UI's "show details" expansion. */
    val evidence: Map<String, String> = emptyMap(),
    /** Index of the instruction that triggered this finding, when there is one. */
    val instructionIndex: Int? = null,
    /** Addresses this finding is about: counterparty, delegate, program, mint. */
    val addresses: List<String> = emptyList(),
) {
    val isProblem: Boolean get() = severity.rank >= Severity.MEDIUM.rank
}

/** Aggregate verdict shown next to the Confirm button. */
enum class VerdictLevel { SAFE, CAUTION, DANGER }

data class Verdict(
    val level: VerdictLevel,
    /** 0 (nothing wrong) to 100 (certain drain). */
    val score: Int,
    val findings: List<RiskFinding>,
    /** One-line judgement, e.g. `Danger: unlimited token approval`. */
    val headline: String,
) {
    val topFinding: RiskFinding?
        get() = findings.maxWithOrNull(
            compareBy<RiskFinding> { it.severity.rank }.thenBy { it.plainEnglish.length }
        )

    fun has(code: String): Boolean = findings.any { it.code == code }

    fun first(code: String): RiskFinding? = findings.firstOrNull { it.code == code }

    fun withSeverityAtLeast(severity: Severity): List<RiskFinding> =
        findings.filter { it.severity.rank >= severity.rank }

    val isDanger: Boolean get() = level == VerdictLevel.DANGER
    val isSafe: Boolean get() = level == VerdictLevel.SAFE
}

/** Human-facing token metadata, supplied by the data layer so sentences can name the asset. */
data class TokenMetadata(
    val mint: String,
    val symbol: String? = null,
    val name: String? = null,
    val decimals: Int? = null,
    val isToken2022: Boolean = false,
)

/**
 * Everything the rules need beyond the transaction bytes.
 *
 * Every field is optional: with an empty context the engine still produces a verdict, just a
 * blunter one (it cannot say "all of your balance" without knowing the balance).
 */
data class RiskContext(
    /** The wallet that is about to sign — lets the engine say "your account" and spot self-sends. */
    val userAddress: String? = null,
    /**
     * Raw (integer, un-scaled) holdings. Keyed by token account address; a mint address also works
     * as a fallback key. For native SOL use the wallet address itself.
     */
    val balances: Map<String, Long> = emptyMap(),
    /** Decimals per mint, used to render amounts. */
    val decimals: Map<String, Int> = emptyMap(),
    /** Friendly metadata per mint. */
    val tokenMetadata: Map<String, TokenMetadata> = emptyMap(),
    /** Owner wallet of a token account, from `getAccountInfo`. */
    val accountOwners: Map<String, String> = emptyMap(),
    /** Token-2022 extensions per mint, from [Token2022Parser]. */
    val mintExtensions: Map<String, TokenExtensions> = emptyMap(),
    /** The parsed source token account, when the caller fetched it. */
    val sourceTokenAccount: TokenAccountInfo? = null,
    /** Prior transaction count per counterparty address; `0` renders as "no prior history". */
    val counterpartyTxCount: Map<String, Int> = emptyMap(),
    /** Extra program ids the caller vouches for, on top of [ProgramRegistry]. */
    val trustedPrograms: Set<String> = emptySet(),
    /** Current slot, for freshness comments. */
    val slot: Long? = null,
) {
    fun decimalsFor(mint: String?): Int? {
        if (mint == null) return null
        return decimals[mint] ?: tokenMetadata[mint]?.decimals
    }

    fun metadataFor(mint: String?): TokenMetadata? = mint?.let { tokenMetadata[it] }

    fun extensionsFor(mint: String?): TokenExtensions? = mint?.let { mintExtensions[it] }

    fun holdingFor(vararg keys: String?): Long? {
        for (key in keys) {
            if (key == null) continue
            balances[key]?.let { return it }
        }
        return null
    }

    companion object {
        val EMPTY = RiskContext()
    }
}

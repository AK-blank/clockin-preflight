package com.clockin.preflight.ui

import com.clockin.preflight.data.DataResult
import com.clockin.preflight.data.SolanaRpc
import com.clockin.preflight.data.TokenRiskClient
import com.clockin.preflight.engine.RiskEngine

/**
 * Bridges the pure-Kotlin risk engine and the live data layer into the presentation types.
 *
 * Keeping this mapping in one small file means the UI never imports engine internals directly and
 * the engine can evolve without touching Compose code. Every call here is blocking; callers run it
 * off the main thread.
 */
object PreflightController {

    private val rpc = SolanaRpc()

    private val tokenRisk = TokenRiskClient()

    /** A base58 signature is 64-88 chars of base58 and contains none of base64's `=`, `/`, `+`. */
    fun looksLikeSignature(input: String): Boolean {
        val text = input.trim()
        if (text.length !in 60..90) return false
        if (text.contains('=') || text.contains('/') || text.contains('+')) return false
        return text.all { it in BASE58_ALPHABET }
    }

    /**
     * Resolve whatever the user pasted into a base64 transaction: either a raw base64 transaction,
     * or a signature we fetch from the chain.
     */
    fun resolveToBase64(input: String): Resolved {
        val text = input.trim()
        if (text.isEmpty()) {
            return Resolved.Failure("Paste a transaction signature or a base64 transaction.")
        }
        if (!looksLikeSignature(text)) return Resolved.Ok(text)

        return when (val result = rpc.getTransactionAutoVersion(text)) {
            is DataResult.Ok -> {
                // `Ok(null)` is the node's real answer for "no such transaction" — treat it as a
                // readable failure rather than an error, so the message stays honest.
                val base64 = result.value?.transactionBase64?.firstOrNull()
                if (base64.isNullOrBlank()) {
                    Resolved.Failure("The chain returned no transaction body for that signature.")
                } else {
                    Resolved.Ok(base64)
                }
            }
            is DataResult.Err -> Resolved.Failure("Could not fetch that signature: ${result.error.message}")
        }
    }

    /** Run the pre-flight engine over a base64 transaction (no wallet context needed). */
    fun analyze(base64: String): Result<VerdictView> = runCatching {
        val report = RiskEngine.analyze(base64)
        VerdictView(
            headline = report.verdict.headline,
            level = mapLevel(report.verdict.level.name),
            score = report.verdict.score,
            summary = report.narrative.ifBlank { report.summary },
            findings = report.verdict.findings.map { finding ->
                FindingView(
                    severity = mapSeverity(finding.severity.name),
                    title = finding.title,
                    plainEnglish = finding.plainEnglish,
                    evidence = finding.evidence.entries.joinToString("   ") { "${it.key}=${it.value}" },
                )
            },
            sources = listOf("on-device decode", "Solana RPC"),
            decodeFailed = report.decodeError != null,
        )
    }

    /** Live token authority / extension risk for a mint, merged from the reachable oracles. */
    fun tokenAuthority(mint: String): Result<TokenRiskView> = runCatching {
        val risk = tokenRisk.fetchTokenRiskBlocking(mint.trim())
        TokenRiskView(
            mint = risk.mint,
            symbol = risk.symbol ?: risk.name,
            score = risk.score,
            level = mapLevel(risk.verdict.name),
            flags = risk.flags.map { flag ->
                FindingView(
                    severity = mapSeverity(flag.severity.name),
                    title = flag.title,
                    plainEnglish = flag.detail,
                    evidence = flag.source.label,
                )
            },
            sources = risk.sources.map { it.label },
            errors = risk.sourceErrors.entries.joinToString("; ") { "${it.key.label}: ${it.value}" },
        )
    }

    fun chainStatus(): String = when (val health = rpc.getHealth()) {
        is DataResult.Ok -> {
            val slot = (rpc.getSlot() as? DataResult.Ok)?.value
            if (slot != null) "Solana mainnet live · slot $slot" else "Solana mainnet live"
        }
        is DataResult.Err -> "RPC unavailable: ${health.error.message}"
    }

    private fun mapSeverity(name: String): Severity = when (name) {
        "CRITICAL", "HIGH", "DANGER" -> Severity.DANGER
        "MEDIUM", "WARN" -> Severity.CAUTION
        else -> Severity.INFO
    }

    private fun mapLevel(name: String): Severity = when (name) {
        "DANGER" -> Severity.DANGER
        "CAUTION" -> Severity.CAUTION
        else -> Severity.INFO
    }

    private const val BASE58_ALPHABET =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    sealed class Resolved {
        data class Ok(val base64: String) : Resolved()
        data class Failure(val message: String) : Resolved()
    }
}

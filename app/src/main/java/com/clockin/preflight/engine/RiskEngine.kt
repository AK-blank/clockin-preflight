package com.clockin.preflight.engine

/**
 * The single entry point the rest of the app talks to.
 *
 * ```kotlin
 * val report = RiskEngine.analyze(base64Transaction, RiskContext(userAddress = me))
 * report.verdict.level   // SAFE | CAUTION | DANGER
 * report.narrative       // the sentence shown above the Confirm button
 * ```
 *
 * Nothing in this file — or anywhere under `engine/` — touches Android, Compose or the network, so
 * it runs identically in a JVM unit test and on device.
 */
object RiskEngine {

    /** Analyses a base64 transaction blob. Never throws. */
    fun analyze(
        base64: String,
        context: RiskContext = RiskContext.EMPTY,
    ): PreflightReport = analyze(RawTransaction(base64), context)

    /** Analyses a [RawTransaction]. Never throws. */
    fun analyze(
        raw: RawTransaction,
        context: RiskContext = RiskContext.EMPTY,
    ): PreflightReport = when (val decoded = TransactionDecoder.decode(raw)) {
        is DecodeResult.Success -> analyze(decoded.transaction, context)
        is DecodeResult.Failure -> undecodable(decoded.error)
    }

    /** Analyses an already-decoded transaction. Never throws. */
    fun analyze(
        transaction: DecodedTransaction,
        context: RiskContext = RiskContext.EMPTY,
    ): PreflightReport {
        val verdict = RiskAnalyzer.analyze(transaction, context)
        return PreflightReport(
            transaction = transaction,
            decodeError = null,
            verdict = verdict,
            summary = Narrator.summarize(transaction, context),
            narrative = Narrator.narrate(transaction, verdict, context),
            actions = Narrator.actions(transaction, context),
        )
    }

    /**
     * A transaction we could not read is not automatically malicious, but it is not something we can
     * vouch for either — so it lands on CAUTION with an explicit reason, never on SAFE.
     */
    private fun undecodable(error: DecodeError): PreflightReport {
        val finding = RiskFinding(
            severity = Severity.HIGH,
            code = "UNDECODABLE_TRANSACTION",
            title = "Transaction could not be read",
            plainEnglish = "We could not read this transaction, so we cannot vouch for it: " +
                "${error.message}. Do not sign it unless you trust exactly where it came from.",
            evidence = mapOf("errorCode" to error.code, "message" to error.message),
        )
        val verdict = Verdict(
            level = VerdictLevel.CAUTION,
            score = Severity.HIGH.weight,
            findings = listOf(finding),
            headline = "Caution: transaction could not be read",
        )
        return PreflightReport(
            transaction = null,
            decodeError = error,
            verdict = verdict,
            summary = "This transaction could not be decoded.",
            narrative = finding.plainEnglish,
            actions = emptyList(),
        )
    }
}

/** Everything the pre-flight screen needs, in one immutable value. */
data class PreflightReport(
    val transaction: DecodedTransaction?,
    val decodeError: DecodeError?,
    val verdict: Verdict,
    /** One-line "what does it do". */
    val summary: String,
    /** The demo paragraph: what it does, then why it matters. */
    val narrative: String,
    /** Per-instruction sentences. */
    val actions: List<NarrativeItem>,
) {
    val level: VerdictLevel get() = verdict.level
    val score: Int get() = verdict.score
    val findings: List<RiskFinding> get() = verdict.findings
    val isDecodable: Boolean get() = transaction != null

    fun hasFinding(code: String): Boolean = verdict.has(code)

    fun firstFinding(code: String): RiskFinding? = verdict.first(code)
}

package com.clockin.preflight.data

/** The single risk verdict the UI renders. */
enum class Verdict {
    /** No source reported anything worth a second look. */
    SAFE,

    /** Something a user should read before signing, but not a reason to stop. */
    CAUTION,

    /** A known rug pattern. The pre-flight screen should stop the user here. */
    DANGER,

    /** No source answered, so we genuinely do not know. Never render this as "safe". */
    UNKNOWN,
    ;

    /** Ordinal-free severity ordering so the worst finding wins regardless of declaration order. */
    val severityRank: Int
        get() = when (this) {
            SAFE -> 0
            CAUTION -> 1
            DANGER -> 2
            UNKNOWN -> 3
        }
}

/** Where a finding came from, so every claim on screen stays attributable. */
enum class RiskSource(val label: String) {
    RUGCHECK("RugCheck"),
    GOPLUS("GoPlus"),
    DERIVED("Pre-flight"),
}

/** How loudly a finding should be shown. */
enum class Severity { INFO, WARN, DANGER }

/**
 * One attributable finding.
 *
 * [id] is stable and machine-readable so the UI can pin copy to it and tests can assert on it
 * without matching prose.
 */
data class TokenFlag(
    val id: String,
    val title: String,
    val detail: String,
    val severity: Severity,
    val source: RiskSource,
)

/**
 * The merged risk picture for one mint.
 *
 * @property score RugCheck's `score_normalised` (0..100, higher is riskier) when RugCheck answered.
 * @property rawScore RugCheck's raw `score` — kept because the normalised figure is lossy.
 * @property flags every finding, worst first, each carrying its own [RiskSource].
 * @property sources the sources that answered, so an empty list means "nothing was checked".
 * @property sourceErrors why each source failed, keyed by source. A partial answer is still an
 *   answer, but the UI must be able to say *which* half is missing.
 */
data class TokenRisk(
    val mint: String,
    val score: Int?,
    val rawScore: Long?,
    val verdict: Verdict,
    val flags: List<TokenFlag>,
    val sources: List<RiskSource>,
    val sourceErrors: Map<RiskSource, String> = emptyMap(),
    val name: String? = null,
    val symbol: String? = null,
) {
    val isDangerous: Boolean get() = verdict == Verdict.DANGER

    val dangerFlags: List<TokenFlag> get() = flags.filter { it.severity == Severity.DANGER }

    /** True when at least one source produced data, i.e. the verdict is more than a guess. */
    val isConclusive: Boolean get() = sources.isNotEmpty()
}

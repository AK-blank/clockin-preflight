package com.clockin.preflight.engine

import org.junit.Assert.fail

/**
 * Access helpers over [MainnetFixtures] so tests can name a fixture and fail with a useful message
 * when the generated set changes.
 */
object Fixtures {

    val names: List<String> get() = MainnetFixtures.ALL.keys.sorted()

    fun base64(name: String): String = MainnetFixtures.ALL[name]
        ?: fail("fixture '$name' is missing; committed fixtures are $names").let { "" }

    fun decode(name: String): DecodedTransaction =
        when (val result = TransactionDecoder.decodeBase64(base64(name))) {
            is DecodeResult.Success -> result.transaction
            is DecodeResult.Failure ->
                fail("fixture '$name' failed to decode: ${result.error.code} ${result.error.message}")
                    .let { error("unreachable") }
        }

    /** Every committed fixture that decodes, keyed by fixture name. */
    fun allDecoded(): Map<String, DecodedTransaction> =
        names.mapNotNull { name ->
            (TransactionDecoder.decodeBase64(base64(name)) as? DecodeResult.Success)
                ?.let { name to it.transaction }
        }.toMap()

    /** First fixture containing an instruction matching [predicate], or `null`. */
    fun firstWith(
        predicate: (DecodedInstruction) -> Boolean,
    ): Pair<String, DecodedTransaction>? =
        allDecoded().entries
            .sortedBy { it.key }
            .firstOrNull { (_, tx) -> tx.instructions.any(predicate) }
            ?.let { it.key to it.value }

    fun requireWith(
        what: String,
        predicate: (DecodedInstruction) -> Boolean,
    ): Pair<String, DecodedTransaction> =
        firstWith(predicate)
            ?: fail("no committed mainnet fixture contains $what; fixtures: $names")
                .let { error("unreachable") }

    fun instructionOf(
        tx: DecodedTransaction,
        predicate: (DecodedInstruction) -> Boolean,
    ): DecodedInstruction = tx.instructions.first(predicate)
}

package com.clockin.preflight.data

import java.io.IOException
import java.net.URLEncoder

/**
 * Fetches token risk from two independent live sources and merges them into one [TokenRisk].
 *
 * - **RugCheck** (`api.rugcheck.xyz/v1/tokens/{mint}/report`) — holder graph, named risks, a
 *   normalised score, and the full Token-2022 extension block.
 * - **GoPlus** (`api.gopluslabs.io/api/v1/solana/token_security`) — an authoritative capability
 *   flag set: mintable, freezable, closable, balance-mutable, transfer hook, transfer fee,
 *   default account state, and a trust attestation.
 *
 * Neither source is required. A single-source answer is still returned, with
 * [TokenRisk.sourceErrors] recording what was missing so the UI never implies a completeness it
 * does not have. Only when *both* fail is the verdict [Verdict.UNKNOWN].
 *
 * ### Live-endpoint behaviour (measured 2026-10-05)
 * - Both endpoints answer `200` with **no API key**.
 * - RugCheck returns `topHolders: null` and `token_extensions: null` for large classic-SPL mints,
 *   and GoPlus omits `holder_count` for mints it has not fully indexed. Both are handled as
 *   "absent", not as errors.
 * - GoPlus answers `{"code":1,"result":{}}` for an unindexed mint; that is reported as "no data",
 *   not as a failure.
 */
class TokenRiskClient(
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val rugCheckBaseUrl: String = DEFAULT_RUGCHECK_BASE_URL,
    private val goPlusBaseUrl: String = DEFAULT_GOPLUS_BASE_URL,
    /** Total attempts per source, not retries. */
    private val maxAttempts: Int = 2,
) {

    /**
     * Blocking, suspension-free fetch of the merged risk picture.
     *
     * Deliberately plain: it can be called from a unit test, a `Thread`, or a coroutine on a
     * background dispatcher, without dragging a coroutine dependency into the data layer.
     * Never throws — an unreachable network yields [Verdict.UNKNOWN], not an exception.
     */
    fun fetchTokenRiskBlocking(mint: String): TokenRisk {
        if (!isPlausibleMint(mint)) {
            return TokenRisk(
                mint = mint,
                score = null,
                rawScore = null,
                verdict = Verdict.UNKNOWN,
                flags = emptyList(),
                sources = emptyList(),
                sourceErrors = mapOf(
                    RiskSource.RUGCHECK to "not a valid Solana mint address",
                    RiskSource.GOPLUS to "not a valid Solana mint address",
                ),
            )
        }

        val errors = LinkedHashMap<RiskSource, String>()
        val rugCheck = fetchRugCheck(mint).fold(
            onOk = { it },
            onErr = { errors[RiskSource.RUGCHECK] = it.message; null },
        )
        val goPlus = fetchGoPlus(mint).fold(
            onOk = { it },
            onErr = { errors[RiskSource.GOPLUS] = it.message; null },
        )
        return RiskPolicy.evaluate(mint, rugCheck, goPlus, errors)
    }

    /** Alias kept so callers can read either name; identical behaviour. */
    fun fetchTokenRisk(mint: String): TokenRisk = fetchTokenRiskBlocking(mint)

    /** Raw RugCheck report, for callers that need fields the merged model drops. */
    fun fetchRugCheck(mint: String): DataResult<RugCheckReport> {
        val url = "$rugCheckBaseUrl/tokens/${encodePathSegment(mint)}/report"
        val body = request(url)
        return when (body) {
            is DataResult.Err -> body
            is DataResult.Ok -> try {
                DataResult.Ok(RugCheckParser.parse(body.value))
            } catch (e: JsonParseException) {
                DataResult.Err(
                    DataError.MalformedJson(url, e.message ?: "invalid JSON", body.value.take(200)),
                )
            }
        }
    }

    /**
     * Raw GoPlus report.
     *
     * `Ok(null)` means "the source answered but has nothing on this mint" — distinct from
     * `Err(...)`, which means the source itself failed.
     */
    fun fetchGoPlus(mint: String): DataResult<GoPlusReport?> {
        val url = "$goPlusBaseUrl/api/v1/solana/token_security?contract_addresses=${encodeQuery(mint)}"
        val body = request(url)
        return when (body) {
            is DataResult.Err -> body
            is DataResult.Ok -> try {
                DataResult.Ok(GoPlusParser.parse(body.value, mint))
            } catch (e: JsonParseException) {
                DataResult.Err(
                    DataError.MalformedJson(url, e.message ?: "invalid JSON", body.value.take(200)),
                )
            }
        }
    }

    // -----------------------------------------------------------------------------------------

    private fun request(url: String): DataResult<String> {
        var lastError: DataError? = null
        var attempt = 1
        while (attempt <= maxAttempts) {
            try {
                val response = transport.get(url)
                if (response.isSuccess) return DataResult.Ok(response.body)
                // A 403 from publicnode still carries a JSON-RPC style body; keep the code visible.
                val error = DataError.Http(response.statusCode, url, response.body.take(500))
                lastError = error
                if (!error.isRetryable) return DataResult.Err(error)
            } catch (io: IOException) {
                lastError = DataError.Network(url, io.message ?: io.javaClass.simpleName)
            }
            attempt++
        }
        return DataResult.Err(lastError ?: DataError.Unexpected("request to $url failed with no error recorded"))
    }

    private val DataError.Http.isRetryable: Boolean
        get() = statusCode == 429 || statusCode >= 500

    private inline fun <T, R> DataResult<T>.fold(
        onOk: (T) -> R,
        onErr: (DataError) -> R,
    ): R = when (this) {
        is DataResult.Ok -> onOk(value)
        is DataResult.Err -> onErr(error)
    }

    companion object {
        const val DEFAULT_RUGCHECK_BASE_URL = "https://api.rugcheck.xyz/v1"
        const val DEFAULT_GOPLUS_BASE_URL = "https://api.gopluslabs.io"

        /** Base58 alphabet used by Solana addresses. */
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        /**
         * Cheap shape check before spending two network calls on it.
         *
         * Purely defensive: it keeps a malformed mint from being spliced into a request URL.
         */
        fun isPlausibleMint(mint: String): Boolean =
            mint.length in 32..44 && mint.all { it in BASE58_ALPHABET }

        /** Mints are base58, so no encoding is needed — but never let raw input reach the path. */
        private fun encodePathSegment(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}

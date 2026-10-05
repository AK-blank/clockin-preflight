package com.clockin.preflight.data

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Result of `getVersion`. */
data class VersionInfo(
    val solanaCore: String?,
    val featureSet: Long?,
)

/** Result of `getLatestBlockhash` — everything needed to judge "is this transaction still valid?". */
data class BlockhashInfo(
    val blockhash: String,
    val lastValidBlockHeight: Long?,
    val slot: Long?,
)

/** One entry of `getSignaturesForAddress`. */
data class SignatureInfo(
    val signature: String,
    val slot: Long?,
    val blockTime: Long?,
    val confirmationStatus: String?,
    val memo: String?,
    /**
     * Raw `err` payload: `null` on success, an `InstructionError` object on failure. A JSON `null`
     * is normalised to Kotlin `null` so callers never have to test for both.
     */
    val errJson: JsonValue?,
) {
    val failed: Boolean get() = errJson != null
}

/**
 * A confirmed transaction.
 *
 * [transactionBase64] is the raw wire payload (`result.transaction` is the two-element array
 * `[base64, "base64"]` when `encoding=base64`, **not** an object) and feeds straight into the
 * engine's decoder.
 */
data class TransactionInfo(
    val slot: Long?,
    val blockTime: Long?,
    /** `0` for a version-0 transaction, `legacy` for an old one, null when the field is absent. */
    val version: String?,
    val fee: Long?,
    val succeeded: Boolean,
    val errJson: JsonValue?,
    val logMessages: List<String>,
    val computeUnitsConsumed: Long?,
    val accountKeys: List<String>,
    val loadedAddresses: List<String>,
    val preBalances: List<Long>,
    val postBalances: List<Long>,
    val transactionBase64: List<String>,
    val raw: JsonValue.Obj,
) {
    /** The single base64 blob to hand to the decoder, or null if the node returned none. */
    val base64: String? get() = transactionBase64.firstOrNull()
}

/** Result of `getAccountInfo`. `raw` keeps every field the node sent, including extensions. */
data class AccountInfo(
    val pubkey: String,
    val lamports: Long?,
    val owner: String?,
    val executable: Boolean?,
    /** Unsigned on purpose: Solana sends `18446744073709551615` (u64 max) here. */
    val rentEpoch: ULong?,
    val space: Long?,
    val dataBase64: String?,
    /** `data.parsed.type`, e.g. `mint` or `account` when `encoding=jsonParsed`. */
    val parsedType: String?,
    /** `data.parsed.info` — the decoded token/mint body. */
    val parsedInfo: JsonValue.Obj?,
    val raw: JsonValue.Obj,
)

/**
 * A typed JSON-RPC client for a Solana node.
 *
 * Handles the JSON-RPC envelope, typed errors and HTTP failures, with a short timeout and a single
 * retry. The endpoint is injectable so the app can point at a private node and tests can point at
 * canned bytes.
 *
 * ### Live-endpoint behaviour worth knowing (measured against `solana-rpc.publicnode.com`)
 * - JSON-RPC errors arrive with **HTTP 200**, so the envelope must be inspected even on success.
 * - Indexed calls (`getTokenSupply`, `getTokenAccountsByOwner`, `getProgramAccounts`) are refused
 *   with **HTTP 403** and `-32602 "Indexed requests require a personal token"`. Owner-based token
 *   enumeration is therefore unavailable here; use [getBalance] and [getAccountInfo] instead.
 * - `getTransaction` with `maxSupportedTransactionVersion = 0` fails with `-32015` for version-1
 *   transactions. [getTransactionAutoVersion] retries automatically.
 */
class SolanaRpc(
    val endpoint: String = DEFAULT_ENDPOINT,
    private val transport: HttpTransport = UrlConnectionTransport(),
    /** Total attempts, not retries: 2 means "try, then try once more". */
    private val maxAttempts: Int = 2,
) {

    private val requestIds = AtomicInteger(0)

    // -----------------------------------------------------------------------------------------
    // Typed methods
    // -----------------------------------------------------------------------------------------

    /** `getHealth` → `"ok"` when the node is healthy. */
    fun getHealth(): DataResult<String> =
        call("getHealth", JsonValue.Arr(emptyList()))
            .flatMap { value ->
                value.asStr()?.let { DataResult.Ok(it) }
                    ?: DataResult.Err(DataError.Unexpected("getHealth returned ${value.compact()}"))
            }

    /** Current slot. */
    fun getSlot(commitment: String = "confirmed"): DataResult<Long> =
        call("getSlot", JsonValue.Arr(listOf(JsonValue.Obj(mapOf("commitment" to JsonValue.Str(commitment))))))
            .flatMap { value ->
                value.asLong()?.let { DataResult.Ok(it) }
                    ?: DataResult.Err(DataError.Unexpected("getSlot returned ${value.compact()}"))
            }

    /** Node software version. */
    fun getVersion(): DataResult<VersionInfo> =
        call("getVersion", JsonValue.Arr(emptyList()))
            .flatMap { value ->
                val obj = value.asObj()
                    ?: return@flatMap DataResult.Err(DataError.Unexpected("getVersion returned ${value.compact()}"))
                DataResult.Ok(VersionInfo(solanaCore = obj.nonBlankStr("solana-core"), featureSet = obj.long("feature-set")))
            }

    /** Freshest blockhash plus the height at which it stops being usable. */
    fun getLatestBlockhash(commitment: String = "confirmed"): DataResult<BlockhashInfo> =
        call("getLatestBlockhash", JsonValue.Arr(listOf(JsonValue.Obj(mapOf("commitment" to JsonValue.Str(commitment))))))
            .flatMap { value ->
                val result = value.asObj()
                val inner = result?.obj("value")
                    ?: return@flatMap DataResult.Err(DataError.Unexpected("getLatestBlockhash had no value object"))
                val hash = inner.nonBlankStr("blockhash")
                    ?: return@flatMap DataResult.Err(DataError.Unexpected("getLatestBlockhash had no blockhash"))
                DataResult.Ok(
                    BlockhashInfo(
                        blockhash = hash,
                        lastValidBlockHeight = inner.long("lastValidBlockHeight"),
                        slot = result.obj("context")?.long("slot"),
                    ),
                )
            }

    /** Lamport balance of any account. Works on this endpoint, unlike the indexed token calls. */
    fun getBalance(pubkey: String, commitment: String = "confirmed"): DataResult<Long> =
        call("getBalance", JsonValue.Arr(listOf(JsonValue.Str(pubkey), JsonValue.Obj(mapOf("commitment" to JsonValue.Str(commitment))))))
            .flatMap { value ->
                value.asObj()?.long("value")?.let { DataResult.Ok(it) }
                    ?: DataResult.Err(DataError.Unexpected("getBalance returned ${value.compact()}"))
            }

    /**
     * Recent transaction signatures touching [address], newest first.
     *
     * Each entry carries the raw `err` payload, so a caller can tell confirmed-success from
     * confirmed-failure without a second round trip.
     */
    fun getSignaturesForAddress(
        address: String,
        limit: Int = 10,
        before: String? = null,
        until: String? = null,
        commitment: String = "confirmed",
    ): DataResult<List<SignatureInfo>> {
        val options = LinkedHashMap<String, JsonValue>()
        options["limit"] = JsonValue.Num(limit.coerceIn(1, 1000).toString())
        options["commitment"] = JsonValue.Str(commitment)
        before?.let { options["before"] = JsonValue.Str(it) }
        until?.let { options["until"] = JsonValue.Str(it) }
        return call(
            "getSignaturesForAddress",
            JsonValue.Arr(listOf(JsonValue.Str(address), JsonValue.Obj(options))),
        ).flatMap { value ->
            val items = value.asArr()
                ?: return@flatMap DataResult.Err(DataError.Unexpected("getSignaturesForAddress returned ${value.compact()}"))
            DataResult.Ok(
                items.mapNotNull { item ->
                    val obj = item.asObj() ?: return@mapNotNull null
                    val signature = obj.nonBlankStr("signature") ?: return@mapNotNull null
                    SignatureInfo(
                        signature = signature,
                        slot = obj.long("slot"),
                        blockTime = obj.long("blockTime"),
                        confirmationStatus = obj.nonBlankStr("confirmationStatus"),
                        memo = obj.nonBlankStr("memo"),
                        errJson = obj["err"]?.takeIf { it !is JsonValue.Null },
                    )
                },
            )
        }
    }

    /**
     * A single transaction, base64-encoded so the engine can decode it itself.
     *
     * Returns `Ok(null)` when the node has no such transaction — that is a legitimate answer, not
     * an error.
     */
    fun getTransaction(
        signature: String,
        maxSupportedTransactionVersion: Int = 0,
        commitment: String = "confirmed",
        encoding: String = "base64",
    ): DataResult<TransactionInfo?> = call(
        "getTransaction",
        JsonValue.Arr(
            listOf(
                JsonValue.Str(signature),
                JsonValue.Obj(
                    linkedMapOf(
                        "encoding" to JsonValue.Str(encoding),
                        "maxSupportedTransactionVersion" to JsonValue.Num(maxSupportedTransactionVersion.toString()),
                        "commitment" to JsonValue.Str(commitment),
                    ),
                ),
            ),
        ),
    ).flatMap { value -> parseTransaction(value) }

    /**
     * [getTransaction] that copes with the endpoint's version ceiling.
     *
     * publicnode rejects version-1 transactions with `-32015` when asked for version 0. Rather than
     * surfacing that to the user as a failure, retry once at version 1 and let the node decide.
     */
    fun getTransactionAutoVersion(
        signature: String,
        commitment: String = "confirmed",
    ): DataResult<TransactionInfo?> {
        val first = getTransaction(signature, maxSupportedTransactionVersion = 0, commitment = commitment)
        val error = first.errorOrNull()
        if (error !is DataError.Rpc || !error.isUnsupportedTransactionVersion) return first
        return getTransaction(signature, maxSupportedTransactionVersion = 1, commitment = commitment)
    }

    /**
     * [getTransactionAutoVersion] with "the node has no such transaction" folded into a typed
     * failure.
     *
     * [getTransactionAutoVersion] answers `Ok(null)` for an unknown signature, because that is a
     * legitimate answer rather than an error. Callers that simply need a transaction body usually
     * prefer one call site and one error channel, which is what this gives them.
     */
    fun requireTransaction(
        signature: String,
        commitment: String = "confirmed",
    ): DataResult<TransactionInfo> =
        getTransactionAutoVersion(signature, commitment).flatMap { transaction ->
            transaction?.let { DataResult.Ok(it) }
                ?: DataResult.Err(
                    DataError.Unexpected("no transaction found for signature $signature"),
                )
        }

    /**
     * Account state. With `encoding = jsonParsed` the node decodes SPL token accounts for us, which
     * is how this app reads mint authorities without shipping a token-program parser.
     *
     * Returns `Ok(null)` when the account does not exist.
     */
    fun getAccountInfo(
        pubkey: String,
        encoding: String = "jsonParsed",
        commitment: String = "confirmed",
    ): DataResult<AccountInfo?> = call(
        "getAccountInfo",
        JsonValue.Arr(
            listOf(
                JsonValue.Str(pubkey),
                JsonValue.Obj(
                    linkedMapOf(
                        "encoding" to JsonValue.Str(encoding),
                        "commitment" to JsonValue.Str(commitment),
                    ),
                ),
            ),
        ),
    ).flatMap { value ->
        val wrapper = value.asObj()
            ?: return@flatMap DataResult.Err(DataError.Unexpected("getAccountInfo returned ${value.compact()}"))
        val account = wrapper.obj("value")
            ?: run {
                // `"value": null` is the node's way of saying "no such account".
                if (wrapper.has("value") && wrapper["value"] is JsonValue.Null) return@flatMap DataResult.Ok(null)
                return@flatMap DataResult.Err(DataError.Unexpected("getAccountInfo had no value object"))
            }
        val data = account.obj("data")
        DataResult.Ok(
            AccountInfo(
                pubkey = pubkey,
                lamports = account.long("lamports"),
                owner = account.nonBlankStr("owner"),
                executable = account.bool("executable"),
                rentEpoch = account.uLong("rentEpoch"),
                space = account.long("space") ?: data?.long("space"),
                dataBase64 = data?.asArr()?.firstOrNull()?.asStr(),
                parsedType = data?.obj("parsed")?.nonBlankStr("type"),
                parsedInfo = data?.obj("parsed")?.obj("info"),
                raw = account,
            ),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Envelope handling
    // -----------------------------------------------------------------------------------------

    /**
     * Sends one JSON-RPC call and unwraps `result`.
     *
     * Retries on transport failures and on 429/5xx, because a pre-flight check that fails once
     * should not be the reason a user cannot sign. Business errors (a JSON-RPC `error` object,
     * 4xx) are returned immediately: retrying them just burns the user's time.
     */
    private fun call(method: String, params: JsonValue): DataResult<JsonValue> {
        val id = requestIds.incrementAndGet()
        val payload = JsonValue.Obj(
            linkedMapOf(
                "jsonrpc" to JsonValue.Str("2.0"),
                "id" to JsonValue.Num(id.toString()),
                "method" to JsonValue.Str(method),
                "params" to params,
            ),
        ).let(::encode)

        var lastError: DataError? = null
        var attempt = 1
        while (attempt <= maxAttempts) {
            try {
                val response = transport.post(endpoint, payload)
                val error = interpret(response, method)
                if (error == null) return unwrapResult(response, method)
                lastError = error
                if (!isRetryable(error)) return DataResult.Err(error)
            } catch (io: IOException) {
                lastError = DataError.Network(endpoint, io.message ?: io.javaClass.simpleName)
            }
            attempt++
        }
        return DataResult.Err(lastError ?: DataError.Unexpected("$method failed with no error recorded"))
    }

    /** Returns null when the response is usable, otherwise the typed failure. */
    private fun interpret(response: HttpResponse, method: String): DataError? {
        if (!response.isSuccess) {
            // A 403 body on publicnode still carries a real JSON-RPC error; prefer its message.
            jsonRpcError(response.body, method)?.let { return it }
            return DataError.Http(response.statusCode, endpoint, response.body.take(500))
        }
        return jsonRpcError(response.body, method)
    }

    private fun isRetryable(error: DataError): Boolean = when (error) {
        is DataError.Network -> true
        is DataError.Http -> error.statusCode == 429 || error.statusCode >= 500
        else -> false
    }

    private fun unwrapResult(response: HttpResponse, method: String): DataResult<JsonValue> {
        val envelope = try {
            JsonParser.parseObject(response.body)
        } catch (e: JsonParseException) {
            return DataResult.Err(
                DataError.MalformedJson(endpoint, e.message ?: "invalid JSON", response.body.take(200)),
            )
        }
        return DataResult.Ok(envelope["result"] ?: JsonValue.Null)
    }

    private fun jsonRpcError(body: String, method: String): DataError.Rpc? {
        val envelope = try {
            JsonParser.parseObject(body)
        } catch (_: JsonParseException) {
            return null
        }
        val error = envelope.obj("error") ?: return null
        return DataError.Rpc(
            code = error.int("code") ?: 0,
            rpcMessage = error.nonBlankStr("message") ?: "unknown RPC error",
            method = method,
        )
    }

    private fun parseTransaction(value: JsonValue): DataResult<TransactionInfo?> {
        if (value is JsonValue.Null) return DataResult.Ok(null)
        val result = value.asObj()
            ?: return DataResult.Err(DataError.Unexpected("getTransaction returned ${value.compact()}"))
        val meta = result.obj("meta")
        val transaction = result["transaction"]
        val base64 = transaction.asArr()?.mapNotNull { it.asStr() }.orEmpty()
        val message = transaction.asObj()?.obj("message")
        return DataResult.Ok(
            TransactionInfo(
                slot = result.long("slot"),
                blockTime = result.long("blockTime"),
                version = when (val v = result["version"]) {
                    is JsonValue.Num -> v.raw
                    is JsonValue.Str -> v.value
                    else -> null
                },
                fee = meta?.long("fee"),
                // `meta.err == null` is how a successful transaction reports; some nodes also send
                // the newer `status: {"Ok": null}` shape alongside it. Normalise JSON null to
                // Kotlin null so "succeeded" is a single null check at every call site.
                succeeded = meta != null && (meta["err"] == null || meta["err"] is JsonValue.Null),
                errJson = meta?.let { it["err"] }?.takeIf { it !is JsonValue.Null },
                logMessages = meta?.stringList("logMessages").orEmpty(),
                computeUnitsConsumed = meta?.long("computeUnitsConsumed"),
                accountKeys = message?.arr("accountKeys").orEmpty()
                    .mapNotNull { it.asStr() ?: it.asObj()?.nonBlankStr("pubkey") },
                loadedAddresses = meta?.obj("loadedAddresses")?.let { loaded ->
                    loaded.stringList("writable") + loaded.stringList("readonly")
                }.orEmpty(),
                preBalances = meta?.arr("preBalances").orEmpty().mapNotNull { it.asLong() },
                postBalances = meta?.arr("postBalances").orEmpty().mapNotNull { it.asLong() },
                transactionBase64 = base64,
                raw = result,
            ),
        )
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://solana-rpc.publicnode.com"

        /**
         * Blocked indexed calls answer `403` with this phrase; exposed so callers can branch on the
         * cause instead of the status code.
         */
        const val INDEXED_REQUEST_MESSAGE = "Indexed requests require a personal token"

        /** `getTokenAccountsByOwner` answers `403` with this terse body on publicnode. */
        const val BLOCKED_REQUEST_MESSAGE = "Request blocked"

        /**
         * Minimal JSON writer. The request envelope is the only thing this package serialises, so a
         * full writer would be dead weight — but it still escapes strings properly.
         */
        fun encode(value: JsonValue): String = when (value) {
            is JsonValue.Null -> "null"
            is JsonValue.Bool -> value.value.toString()
            is JsonValue.Num -> value.raw
            is JsonValue.Str -> buildString {
                append('"')
                for (c in value.value) {
                    when (c) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                    }
                }
                append('"')
            }
            is JsonValue.Arr -> value.items.joinToString(",", "[", "]", transform = ::encode)
            is JsonValue.Obj -> value.fields.entries.joinToString(",", "{", "}") { (k, v) ->
                "${encode(JsonValue.Str(k))}:${encode(v)}"
            }
        }
    }
}

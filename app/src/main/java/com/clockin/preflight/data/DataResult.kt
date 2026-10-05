package com.clockin.preflight.data

/**
 * A typed outcome for every network read in this package.
 *
 * Nothing here throws for an expected failure — a rate limit, an unreachable endpoint or a 403
 * from a blocked method are all ordinary states the UI has to render, so they travel as values.
 */
sealed interface DataResult<out T> {

    data class Ok<out T>(val value: T) : DataResult<T>

    data class Err(val error: DataError) : DataResult<Nothing>
}

/** Why a read failed, in a shape the UI can act on without string-matching. */
sealed interface DataError {

    /** One-line, human-readable summary. Safe to show to a user. */
    val message: String

    /** The endpoint answered, but not with a success status. [body] is kept verbatim. */
    data class Http(
        val statusCode: Int,
        val url: String,
        val body: String,
    ) : DataError {
        override val message: String
            get() = "HTTP $statusCode from $url"

        /**
         * publicnode answers blocked indexed calls with `403` and this exact RPC error body,
         * so we can tell "this method needs a paid token" apart from a generic outage.
         */
        val isIndexedRequestBlocked: Boolean
            get() = statusCode == 403 && body.contains("Indexed requests require a personal token")
    }

    /** The socket never produced a response: DNS, TLS, timeout, connection reset. */
    data class Network(
        val url: String,
        val cause: String,
    ) : DataError {
        override val message: String get() = "network failure talking to $url: $cause"
    }

    /** The endpoint answered with something this parser could not read. */
    data class MalformedJson(
        val url: String,
        val detail: String,
        val bodyPreview: String,
    ) : DataError {
        override val message: String get() = "unreadable response from $url: $detail"
    }

    /** A well-formed JSON-RPC envelope carrying an `error` object. */
    data class Rpc(
        val code: Int,
        val rpcMessage: String,
        val method: String,
    ) : DataError {
        override val message: String get() = "RPC $code on $method: $rpcMessage"

        /**
         * `-32015` means the endpoint refuses the transaction's version under the
         * `maxSupportedTransactionVersion` we sent; the fix is to retry with a higher value,
         * not to give up. Observed live against publicnode for version-1 transactions.
         */
        val isUnsupportedTransactionVersion: Boolean get() = code == RPC_TX_VERSION_UNSUPPORTED

        /** `-32601`, an unknown method. */
        val isMethodNotFound: Boolean get() = code == RPC_METHOD_NOT_FOUND

        /** `-32602`, a bad argument — includes publicnode's blocked indexed requests. */
        val isInvalidParams: Boolean get() = code == RPC_INVALID_PARAMS

        companion object {
            const val RPC_INVALID_PARAMS = -32602
            const val RPC_METHOD_NOT_FOUND = -32601
            const val RPC_TX_VERSION_UNSUPPORTED = -32015
        }
    }

    /** A payload that parsed but did not have the shape the call requires. */
    data class Unexpected(val detail: String) : DataError {
        override val message: String get() = detail
    }
}

// ---------------------------------------------------------------------------------------------
// Small combinators so call sites stay flat.
// ---------------------------------------------------------------------------------------------

inline fun <T, R> DataResult<T>.map(transform: (T) -> R): DataResult<R> = when (this) {
    is DataResult.Ok -> DataResult.Ok(transform(value))
    is DataResult.Err -> this
}

inline fun <T, R> DataResult<T>.flatMap(transform: (T) -> DataResult<R>): DataResult<R> = when (this) {
    is DataResult.Ok -> transform(value)
    is DataResult.Err -> this
}

fun <T> DataResult<T>.getOrNull(): T? = (this as? DataResult.Ok)?.value

fun <T> DataResult<T>.errorOrNull(): DataError? = (this as? DataResult.Err)?.error

val DataResult<*>.isOk: Boolean get() = this is DataResult.Ok

/** Replaces a failure with a fallback value, keeping the call site free of `when`. */
inline fun <T> DataResult<T>.orElse(fallback: (DataError) -> T): T = when (this) {
    is DataResult.Ok -> value
    is DataResult.Err -> fallback(error)
}

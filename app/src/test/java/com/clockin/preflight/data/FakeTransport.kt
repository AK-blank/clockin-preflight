package com.clockin.preflight.data

import java.io.IOException

/**
 * A scripted [HttpTransport] — the reason the whole data layer can be tested without a socket.
 *
 * Every test in this package runs against bytes captured from the live endpoints, so the tests
 * assert on real server shapes while remaining completely hermetic.
 */
class FakeTransport(
    private val respond: (Request) -> HttpResponse,
) : HttpTransport {

    data class Request(
        val method: String,
        val url: String,
        val body: String?,
    )

    private val recorded = mutableListOf<Request>()

    /** Every request this transport has seen, in order. */
    val requests: List<Request> get() = synchronized(recorded) { recorded.toList() }

    val requestCount: Int get() = synchronized(recorded) { recorded.size }

    fun callsTo(fragment: String): List<Request> = requests.filter { it.url.contains(fragment) }

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val request = Request("GET", url, null)
        synchronized(recorded) { recorded += request }
        return respond(request)
    }

    override fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): HttpResponse {
        val request = Request("POST", url, body)
        synchronized(recorded) { recorded += request }
        return respond(request)
    }

    companion object {

        /** Always answers `200` with [body]. */
        fun ok(body: String): FakeTransport = FakeTransport { HttpResponse(200, body) }

        /** Always answers with the given status and body. */
        fun status(code: Int, body: String = ""): FakeTransport =
            FakeTransport { HttpResponse(code, body) }

        /** Fails every call the way an unreachable host does. */
        fun offline(message: String = "connection refused"): FakeTransport =
            FakeTransport { throw IOException(message) }

        /**
         * Routes by URL fragment; anything unmatched throws, so a test that accidentally hits the
         * wrong endpoint fails loudly instead of silently getting an empty body.
         */
        fun routing(routes: Map<String, HttpResponse>): FakeTransport = FakeTransport { request ->
            routes.entries.firstOrNull { request.url.contains(it.key) }?.value
                ?: throw AssertionError("unexpected request: ${request.method} ${request.url}")
        }

        /**
         * Routes JSON-RPC calls by the `method` field of the request body. Also proves the request
         * envelope this package emits is valid JSON.
         */
        fun jsonRpc(routes: Map<String, HttpResponse>): FakeTransport = FakeTransport { request ->
            val body = request.body ?: throw AssertionError("JSON-RPC call had no body")
            val method = JsonParser.parseObject(body).nonBlankStr("method")
                ?: throw AssertionError("JSON-RPC body had no method: $body")
            routes[method] ?: throw AssertionError("unexpected JSON-RPC method: $method")
        }

        /** [jsonRpc] that fails with [code]/[message] for every method. */
        fun jsonRpcFailing(code: Int, message: String): FakeTransport =
            FakeTransport {
                HttpResponse(200, """{"jsonrpc":"2.0","error":{"code":$code,"message":"$message"},"id":1}""")
            }
    }
}

package com.clockin.preflight.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A raw HTTP response. Non-2xx statuses are **values**, not exceptions: the Solana endpoint
 * answers blocked calls with `403` plus a perfectly informative JSON-RPC error body, and losing
 * that body would cost us the only explanation the user gets.
 */
data class HttpResponse(
    val statusCode: Int,
    val body: String,
) {
    val isSuccess: Boolean get() = statusCode in 200..299
}

/**
 * The single seam between the data layer and the network.
 *
 * Tests replace this with canned bytes, which is what keeps `:app:testDebugUnitTest` hermetic —
 * including the HTTP-500, HTTP-403 and malformed-body paths that cannot be provoked on demand
 * against a live endpoint.
 *
 * Implementations throw [IOException] for transport-level failures (DNS, TLS, timeout); they do
 * **not** throw for HTTP status codes.
 */
interface HttpTransport {

    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse
}

/**
 * [HttpTransport] backed by [HttpURLConnection] — the only HTTP client guaranteed to exist on
 * both the plain-JVM test classpath and Android, with no new dependency.
 *
 * Timeouts are deliberately short: this client sits between a user and a "Confirm" button, so a
 * hung socket must surface as an error long before the user gives up.
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 15_000,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpTransport {

    override fun get(url: String, headers: Map<String, String>): HttpResponse =
        execute("GET", url, null, headers)

    override fun post(url: String, body: String, headers: Map<String, String>): HttpResponse =
        execute("POST", url, body, headers)

    private fun execute(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            // HttpURLConnection raises FileNotFoundException from getInputStream() on 4xx/5xx,
            // so the error stream is the only place the server's explanation exists.
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT = "clockin-preflight/0.1 (+https://clockin.local)"
    }
}

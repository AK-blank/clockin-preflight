package com.clockin.probe

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI

/**
 * Proves the two things a "meaningfully interacts with Solana" submission needs on this
 * machine: (1) the Solana Kotlin SDK is on the classpath and usable from JVM unit tests,
 * and (2) a public Solana JSON-RPC endpoint is reachable from a plain JVM HTTP client.
 */
class SolanaProbeTest {

    @Test
    fun solanaKotlinSdkIsOnClasspath() {
        val marker = SolanaProbe.sdkMarker()
        println("SolanaProbe.sdkMarker() = $marker")
        // At minimum the SDK classes must resolve; ideally the calls succeed too.
        assertTrue("probe returned nothing", marker.isNotBlank())
    }

    @Test
    fun publicRpcEndpointAnswers() {
        val url = "https://solana-rpc.publicnode.com"
        val body = """{"jsonrpc":"2.0","id":1,"method":"getHealth"}"""
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val code: Int
        val response: String
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            code = connection.responseCode
            response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
        } finally {
            connection.disconnect()
        }
        println("RPC $url -> HTTP $code $response")
        assertTrue("RPC returned HTTP $code", code in 200..299)
        assertTrue("unexpected RPC payload: $response", response.contains("result"))
    }
}

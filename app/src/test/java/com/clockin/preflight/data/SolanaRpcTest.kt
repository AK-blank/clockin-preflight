package com.clockin.preflight.data

import com.clockin.preflight.data.fixtures.RpcFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every payload here is a real response captured from `https://solana-rpc.publicnode.com` on
 * 2026-10-05 and committed under `fixtures/`. No test opens a socket: [FakeTransport] serves the
 * bytes, which is also the only way to exercise the HTTP-500 and transport-failure paths.
 */
class SolanaRpcTest {

    private fun rpc(
        routes: Map<String, HttpResponse>,
        endpoint: String = SolanaRpc.DEFAULT_ENDPOINT,
    ): Pair<SolanaRpc, FakeTransport> {
        val transport = FakeTransport.jsonRpc(routes)
        return SolanaRpc(endpoint = endpoint, transport = transport) to transport
    }

    private fun ok(body: String) = HttpResponse(200, body)

    private val happyRoutes = mapOf(
        "getHealth" to ok(RpcFixtures.GET_HEALTH),
        "getSlot" to ok(RpcFixtures.GET_SLOT),
        "getVersion" to ok(RpcFixtures.GET_VERSION),
        "getLatestBlockhash" to ok(RpcFixtures.GET_LATEST_BLOCKHASH),
        "getBalance" to ok(RpcFixtures.GET_BALANCE),
        "getSignaturesForAddress" to ok(RpcFixtures.GET_SIGNATURES_FOR_ADDRESS),
        "getTransaction" to ok(RpcFixtures.GET_TRANSACTION_V0),
        "getAccountInfo" to ok(RpcFixtures.GET_ACCOUNT_INFO_PARSED),
    )

    @Test
    fun `getHealth reports node health`() {
        val (client, _) = rpc(happyRoutes)
        assertEquals("ok", client.getHealth().getOrNull())
    }

    @Test
    fun `getSlot returns the current slot`() {
        val (client, _) = rpc(happyRoutes)
        assertEquals(453_617_234L, client.getSlot().getOrNull())
    }

    @Test
    fun `getVersion returns node software`() {
        val (client, _) = rpc(happyRoutes)
        val version = client.getVersion().getOrNull()
        assertNotNull(version)
        assertEquals("4.3.0", version!!.solanaCore)
        assertEquals(3_383_571_666L, version.featureSet)
    }

    @Test
    fun `getLatestBlockhash returns the blockhash and its expiry height`() {
        val (client, _) = rpc(happyRoutes)
        val blockhash = client.getLatestBlockhash().getOrNull()
        assertNotNull(blockhash)
        assertEquals("BRKAF4N1XHBcVgjL8rbqsoEUTQnbayRjr2LYcDh8L6cs", blockhash!!.blockhash)
        assertEquals(431_655_475L, blockhash.lastValidBlockHeight)
        assertEquals(453_617_246L, blockhash.slot)
    }

    @Test
    fun `getBalance returns lamports`() {
        val (client, _) = rpc(happyRoutes)
        assertEquals(535_338_983_427L, client.getBalance("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v").getOrNull())
    }

    @Test
    fun `getSignaturesForAddress separates confirmed successes from confirmed failures`() {
        val (client, _) = rpc(happyRoutes)
        val signatures = client.getSignaturesForAddress("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", limit = 3).getOrNull()
        assertNotNull(signatures)
        assertEquals(3, signatures!!.size)
        assertFalse("first entry succeeded", signatures[0].failed)
        assertNull("a JSON null err must normalise to a Kotlin null", signatures[0].errJson)
        assertTrue("second entry carries an InstructionError", signatures[1].failed)
        assertNotNull(signatures[1].errJson)
        assertEquals("finalized", signatures[0].confirmationStatus)
        assertEquals(453_617_227L, signatures[0].slot)
    }

    @Test
    fun `getTransaction decodes a real version-0 transaction`() {
        val (client, _) = rpc(happyRoutes)
        val transaction = client.getTransaction("2XmesLxeYMaA6dEkvvp1skG9PCc6JVXGG4TMemVjD24f2Zy8u8DdZsf8xFEF5bPrKSnnhviM4Jx3i824D3bgX53k").getOrNull()
        assertNotNull(transaction)
        assertEquals(453_617_368L, transaction!!.slot)
        assertEquals("0", transaction.version)
        assertEquals(21_665L, transaction.fee)
        assertTrue(transaction.succeeded)
        assertNull(transaction.errJson)
        assertEquals(68, transaction.logMessages.size)
        assertEquals(176_986L, transaction.computeUnitsConsumed)
        // `result.transaction` is the two-element array [base64, "base64"], not an object.
        assertEquals(listOf("base64"), transaction.transactionBase64.drop(1))
        assertTrue("base64 payload should be substantial", transaction.base64!!.length > 500)
        // 36 funded accounts; with encoding=base64 the message is not decoded, so accountKeys is
        // empty and the extra addresses only appear under meta.loadedAddresses (5 writable + 14
        // readonly).
        assertEquals(36, transaction.preBalances.size)
        assertEquals(transaction.preBalances.size, transaction.postBalances.size)
        assertTrue(transaction.accountKeys.isEmpty())
        assertEquals(19, transaction.loadedAddresses.size)
    }

    @Test
    fun `getTransaction returns Ok null when the node does not know the signature`() {
        val (client, _) = rpc(mapOf("getTransaction" to ok(RpcFixtures.NULL_RESULT)))
        val result = client.getTransaction("unknown")
        assertTrue(result.isOk)
        assertNull(result.getOrNull())
    }

    @Test
    fun `getAccountInfo parses a jsonParsed mint account`() {
        val (client, _) = rpc(happyRoutes)
        val account = client.getAccountInfo("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v").getOrNull()
        assertNotNull(account)
        assertEquals("mint", account!!.parsedType)
        assertEquals("spl-token", account.raw.obj("data")?.nonBlankStr("program"))
        assertEquals(
            "BJE5MMbqXjVwjAF7oxwPYXnTXDyspzZyt4vwenNw5ruG",
            account.parsedInfo?.nonBlankStr("mintAuthority"),
        )
        assertEquals(ULong.MAX_VALUE, account.rentEpoch)
        assertEquals("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", account.owner)
        assertFalse(account.executable!!)
    }

    @Test
    fun `getAccountInfo returns Ok null for a missing account`() {
        val (client, _) = rpc(
            mapOf("getAccountInfo" to ok("""{"jsonrpc":"2.0","result":{"context":{"slot":1},"value":null},"id":1}""")),
        )
        val result = client.getAccountInfo("11111111111111111111111111111111")
        assertTrue(result.isOk)
        assertNull(result.getOrNull())
    }

    // -----------------------------------------------------------------------------------------
    // Error paths
    // -----------------------------------------------------------------------------------------

    /**
     * publicnode refuses version-1 transactions with `-32015` when asked for version 0. This is a
     * real captured body, and the reason [SolanaRpc.getTransactionAutoVersion] exists.
     */
    @Test
    fun `a version-1 transaction surfaces as a typed -32015 RPC error`() {
        val (client, _) = rpc(mapOf("getTransaction" to ok(RpcFixtures.ERR_UNSUPPORTED_TX_VERSION)))
        val error = client.getTransaction("sig").errorOrNull()
        assertNotNull(error)
        assertTrue(error is DataError.Rpc)
        val rpcError = error as DataError.Rpc
        assertEquals(-32015, rpcError.code)
        assertTrue(rpcError.isUnsupportedTransactionVersion)
        assertEquals("getTransaction", rpcError.method)
    }

    @Test
    fun `getTransactionAutoVersion retries at version 1 when the node demands it`() {
        val transport = FakeTransport { request ->
            val body = request.body!!
            if (body.contains(""""maxSupportedTransactionVersion":0""")) {
                HttpResponse(200, RpcFixtures.ERR_UNSUPPORTED_TX_VERSION)
            } else {
                HttpResponse(200, RpcFixtures.GET_TRANSACTION_V0)
            }
        }
        val client = SolanaRpc(transport = transport)
        val transaction = client.getTransactionAutoVersion("2XmesLxeYMaA6dEkvvp1skG9PCc6JVXGG4TMemVjD24f2Zy8u8DdZsf8xFEF5bPrKSnnhviM4Jx3i824D3bgX53k").getOrNull()
        assertNotNull(transaction)
        assertEquals(2, transport.requestCount)
        assertTrue(transport.requests[1].body!!.contains(""""maxSupportedTransactionVersion":1"""))
    }

    @Test
    fun `an unknown method surfaces as a typed -32601 error`() {
        val (client, _) = rpc(mapOf("getHealth" to ok(RpcFixtures.ERR_UNKNOWN_METHOD)))
        val error = client.getHealth().errorOrNull()
        assertTrue(error is DataError.Rpc)
        assertEquals(-32601, (error as DataError.Rpc).code)
        assertTrue(error.isMethodNotFound)
    }

    @Test
    fun `a bad argument surfaces as a typed -32602 error`() {
        val (client, _) = rpc(mapOf("getTransaction" to ok(RpcFixtures.ERR_INVALID_SIGNATURE)))
        val error = client.getTransaction("not-a-real-signature").errorOrNull()
        assertTrue(error is DataError.Rpc)
        assertEquals(-32602, (error as DataError.Rpc).code)
        assertTrue(error.isInvalidParams)
    }

    /**
     * publicnode blocks indexed calls at the HTTP layer with a `403`, but the body still carries a
     * real JSON-RPC error — so the body is preserved rather than replaced by a bare status code.
     */
    @Test
    fun `a blocked indexed call is an HTTP error that still carries the RPC explanation`() {
        val (client, _) = rpc(
            mapOf("getBalance" to HttpResponse(RpcFixtures.HTTP_FORBIDDEN, RpcFixtures.ERR_INDEXED_REQUEST)),
        )
        val error = client.getBalance("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v").errorOrNull()
        assertTrue("expected the RPC error inside the 403, got $error", error is DataError.Rpc)
        val rpcError = error as DataError.Rpc
        assertEquals(-32602, rpcError.code)
        assertTrue(rpcError.rpcMessage.contains(SolanaRpc.INDEXED_REQUEST_MESSAGE))
    }

    @Test
    fun `a 403 with an unparseable body degrades to a typed HTTP error`() {
        val (client, _) = rpc(mapOf("getHealth" to HttpResponse(403, "<html>Forbidden</html>")))
        val error = client.getHealth().errorOrNull()
        assertTrue(error is DataError.Http)
        assertEquals(403, (error as DataError.Http).statusCode)
    }

    @Test
    fun `a 500 is retried once and then reported`() {
        val (client, transport) = rpc(mapOf("getBalance" to HttpResponse(500, "upstream exploded")))
        val error = client.getBalance("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v").errorOrNull()
        assertTrue(error is DataError.Http)
        assertEquals(500, (error as DataError.Http).statusCode)
        assertEquals("a transient 500 should be attempted twice", 2, transport.requestCount)
    }

    @Test
    fun `a 400 is not retried`() {
        val (client, transport) = rpc(mapOf("getBalance" to HttpResponse(400, "bad request")))
        client.getBalance("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
        assertEquals("a client error will not fix itself", 1, transport.requestCount)
    }

    @Test
    fun `a transport failure is retried and reported as a network error`() {
        val transport = FakeTransport.offline("connection refused")
        val client = SolanaRpc(transport = transport)
        val error = client.getHealth().errorOrNull()
        assertTrue(error is DataError.Network)
        assertTrue((error as DataError.Network).cause.contains("connection refused"))
        assertEquals(2, transport.requestCount)
    }

    @Test
    fun `malformed JSON is reported without leaking a stack trace`() {
        val (client, _) = rpc(mapOf("getHealth" to ok(RpcFixtures.MALFORMED_JSON)))
        val error = client.getHealth().errorOrNull()
        assertTrue("expected a MalformedJson error, got $error", error is DataError.MalformedJson)
        assertTrue((error as DataError.MalformedJson).bodyPreview.isNotEmpty())
    }

    @Test
    fun `a well-formed envelope missing its result is reported as unexpected`() {
        val (client, _) = rpc(mapOf("getHealth" to ok("""{"jsonrpc":"2.0","id":1}""")))
        val error = client.getHealth().errorOrNull()
        assertTrue(error is DataError.Unexpected)
    }

    @Test
    fun `the endpoint is overridable and the request envelope is well formed`() {
        val (client, transport) = rpc(happyRoutes, endpoint = "https://example.invalid/rpc")
        client.getHealth()
        val request = transport.requests.single()
        assertEquals("https://example.invalid/rpc", request.url)
        assertEquals("POST", request.method)
        val body = JsonParser.parseObject(request.body!!)
        assertEquals("2.0", body.nonBlankStr("jsonrpc"))
        assertEquals("getHealth", body.nonBlankStr("method"))
        assertNotNull(body.long("id"))
    }

    @Test
    fun `each call gets a fresh request id`() {
        val (client, transport) = rpc(happyRoutes)
        client.getHealth()
        client.getSlot()
        val ids = transport.requests.map { JsonParser.parseObject(it.body!!).long("id") }
        assertEquals(2, ids.distinct().size)
    }

    @Test
    fun `getSignaturesForAddress clamps an absurd limit instead of sending it`() {
        val (client, transport) = rpc(happyRoutes)
        client.getSignaturesForAddress("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", limit = 100_000)
        val params = JsonParser.parseObject(transport.requests.single().body!!).arr("params")!!
        assertEquals(1000L, params[1].asObj()!!.long("limit"))
    }
}

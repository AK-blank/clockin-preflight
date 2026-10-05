package com.clockin.preflight.data

import com.clockin.preflight.data.fixtures.GoPlusFixtures
import com.clockin.preflight.data.fixtures.RpcFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The data layer has no JSON library to rely on — `kotlinx-serialization-json` is runtime-only on
 * this classpath and `org.json` is stubbed under plain JVM tests — so the hand-rolled reader is
 * load-bearing and gets tested directly, against real captured payloads.
 */
class JsonParserTest {

    @Test
    fun `parses a real RPC envelope`() {
        val root = JsonParser.parseObject(RpcFixtures.GET_HEALTH)
        assertEquals("2.0", root.nonBlankStr("jsonrpc"))
        assertEquals("ok", root.nonBlankStr("result"))
        assertEquals(1L, root.long("id"))
    }

    @Test
    fun `parses nested objects from a real response`() {
        val root = JsonParser.parseObject(RpcFixtures.GET_LATEST_BLOCKHASH)
        val value = root.obj("result")?.obj("value")
        assertNotNull(value)
        assertEquals("BRKAF4N1XHBcVgjL8rbqsoEUTQnbayRjr2LYcDh8L6cs", value!!.nonBlankStr("blockhash"))
        assertEquals(431655475L, value.long("lastValidBlockHeight"))
        assertEquals(453617246L, root.obj("result")?.obj("context")?.long("slot"))
    }

    @Test
    fun `parses arrays of objects from a real response`() {
        val root = JsonParser.parseObject(RpcFixtures.GET_SIGNATURES_FOR_ADDRESS)
        val entries = root.objList("result")
        assertEquals(3, entries.size)
        assertEquals(
            "4hcSGsDhBLiBZbDCxpfAuHZD9UapLr4SzyqhTndgw8L3QG6CpSi1rsEciLuWUErWjiorCoFsLCy6E3eE3oTwFuwq",
            entries[0].nonBlankStr("signature"),
        )
        // The second entry carries a real InstructionError payload.
        assertNotNull(entries[1].obj("err"))
    }

    /**
     * Solana sends `rentEpoch: 18446744073709551615`, which is u64 max and overflows a signed
     * `Long`. Numbers therefore keep their source text.
     */
    @Test
    fun `preserves unsigned 64-bit numbers that overflow a Long`() {
        val root = JsonParser.parseObject(RpcFixtures.GET_ACCOUNT_INFO_PARSED)
        val account = root.obj("result")?.obj("value")
        assertNotNull(account)

        assertEquals(ULong.MAX_VALUE, account!!.uLong("rentEpoch"))
        assertNull("a u64 max value must not silently wrap into a negative Long", account.long("rentEpoch"))
        // Nearby fields that do fit still read as Long.
        assertEquals(535338983427L, account.long("lamports"))
    }

    @Test
    fun `handles string escapes including surrogate pairs`() {
        val root = JsonParser.parseObject(
            """{"text":"line\nbreak\ttab \"quoted\" back\\slash \u00e9\ud83d\ude00 solidus\/"}""",
        )
        assertEquals(
            "line\nbreak\ttab \"quoted\" back\\slash \u00e9\ud83d\ude00 solidus/",
            root.nonBlankStr("text"),
        )
    }

    @Test
    fun `parses the real GoPlus payload without decoding escape artefacts`() {
        val root = JsonParser.parseObject(GoPlusFixtures.USDC_GOPLUS_RESPONSE)
        assertEquals(1, root.int("code"))
        assertEquals("ok", root.nonBlankStr("message"))
        val entry = root.obj("result")?.obj("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
        assertNotNull(entry)
        assertEquals("USD Coin", entry!!.obj("metadata")?.nonBlankStr("name"))
    }

    @Test
    fun `missing keys are null rather than an exception`() {
        val root = JsonParser.parseObject("""{"a":1}""")
        assertNull(root.str("absent"))
        assertNull(root.obj("absent"))
        assertNull(root.arr("absent"))
        assertNull(root.long("absent"))
        assertFalse(root.has("absent"))
    }

    @Test
    fun `a null value is distinguishable from an absent key`() {
        val root = JsonParser.parseObject("""{"present":null}""")
        assertTrue(root.has("present"))
        assertTrue(root["present"] is JsonValue.Null)
        assertNull(root["present"].asStr())
    }

    @Test
    fun `flag reads accept both JSON booleans and GoPlus string conventions`() {
        val root = JsonParser.parseObject("""{"a":true,"b":"1","c":"0","d":0,"e":"maybe"}""")
        assertEquals(true, root.flag("a"))
        assertEquals(true, root.flag("b"))
        assertEquals(false, root.flag("c"))
        assertEquals(false, root.flag("d"))
        assertNull(root.flag("e"))
    }

    @Test
    fun `rejects truncated input`() {
        try {
            JsonParser.parse(RpcFixtures.MALFORMED_JSON)
            fail("expected a JsonParseException for truncated JSON")
        } catch (e: JsonParseException) {
            assertTrue("message should say what was expected: ${e.message}", e.message!!.contains("expected"))
            assertTrue("the failure should point at a character offset", e.index > 0)
        }
    }

    @Test
    fun `reports an unterminated string`() {
        try {
            JsonParser.parse("""{"a":"never closed}""")
            fail("expected a JsonParseException for an unterminated string")
        } catch (e: JsonParseException) {
            assertTrue(e.message!!.contains("unterminated"))
        }
    }

    @Test
    fun `rejects trailing content after the top-level value`() {
        try {
            JsonParser.parse("""{"a":1} garbage""")
            fail("expected a JsonParseException for trailing content")
        } catch (e: JsonParseException) {
            assertTrue(e.message!!.contains("trailing"))
        }
    }

    @Test
    fun `rejects malformed structures`() {
        val bad = listOf(
            """{"a":}""",
            """{"a" 1}""",
            """[1,2""",
            """{"a":1,}""",
            """{a:1}""",
            """tru""",
            """{"a":"unterminated}""",
            """{"a":01x}""",
        )
        for (input in bad) {
            try {
                JsonParser.parse(input)
                fail("expected a JsonParseException for: $input")
            } catch (_: JsonParseException) {
                // expected
            }
        }
    }

    @Test
    fun `rejects nesting beyond the depth limit instead of overflowing the stack`() {
        val deep = "[".repeat(200) + "]".repeat(200)
        try {
            JsonParser.parse(deep)
            fail("expected a JsonParseException for over-deep nesting")
        } catch (e: JsonParseException) {
            assertTrue(e.message!!.contains("nesting"))
        }
    }

    @Test
    fun `round-trips through the request encoder`() {
        val encoded = SolanaRpc.encode(
            JsonValue.Obj(
                linkedMapOf(
                    "jsonrpc" to JsonValue.Str("2.0"),
                    "id" to JsonValue.Num("7"),
                    "method" to JsonValue.Str("getBalance"),
                    "params" to JsonValue.Arr(
                        listOf(JsonValue.Str("""quote"and\backslash"""), JsonValue.Bool(true), JsonValue.Null),
                    ),
                ),
            ),
        )
        val decoded = JsonParser.parseObject(encoded)
        assertEquals("2.0", decoded.nonBlankStr("jsonrpc"))
        assertEquals(7L, decoded.long("id"))
        assertEquals("""quote"and\backslash""", decoded.arr("params")!![0].asStr())
        assertEquals(true, decoded.arr("params")!![1].asBool())
        assertTrue(decoded.arr("params")!![2] is JsonValue.Null)
    }

    @Test
    fun `exposes numbers with their original literal text`() {
        val root = JsonParser.parseObject("""{"int":42,"neg":-7,"frac":1.5,"exp":2e3}""")
        assertEquals("42", (root["int"] as JsonValue.Num).raw)
        assertEquals("-7", (root["neg"] as JsonValue.Num).raw)
        assertEquals(1.5, root.double("frac")!!, 0.0)
        assertEquals(2000.0, root.double("exp")!!, 0.0)
    }
}

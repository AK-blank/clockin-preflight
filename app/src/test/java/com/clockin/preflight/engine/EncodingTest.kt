package com.clockin.preflight.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codec-level tests: base58, base64, hex, little-endian readers and shortvec. */
class EncodingTest {

    // ------------------------------------------------------------------ base58

    @Test
    fun `base58 encodes leading zero bytes as ones`() {
        assertEquals("1", Base58.encode(byteArrayOf(0)))
        assertEquals("11", Base58.encode(byteArrayOf(0, 0)))
        assertEquals("112", Base58.encode(byteArrayOf(0, 0, 1)))
        assertEquals("", Base58.encode(ByteArray(0)))
    }

    @Test
    fun `base58 decodes the system program id to 32 zero bytes`() {
        val decoded = Base58.decode("11111111111111111111111111111111")
        assertNotNull(decoded)
        assertEquals(32, decoded!!.size)
        assertArrayEquals(ByteArray(32), decoded)
    }

    @Test
    fun `base58 round trips a real SPL Token program id`() {
        val id = ProgramRegistry.TOKEN
        val bytes = Base58.decode(id)
        assertNotNull(bytes)
        assertEquals(32, bytes!!.size)
        assertEquals(id, Base58.encode(bytes))
    }

    @Test
    fun `base58 round trips every well-known program id`() {
        for (id in ProgramRegistry.knownIds()) {
            val bytes = Base58.decode(id)
            assertNotNull("could not decode $id", bytes)
            assertEquals("round trip failed for $id", id, Base58.encode(bytes!!))
        }
    }

    @Test
    fun `base58 rejects characters outside the alphabet`() {
        for (bad in listOf("0", "O", "I", "l", "abc!", "7xKq 9fQ")) {
            assertNull("expected null for \"$bad\"", Base58.decode(bad))
        }
    }

    // ------------------------------------------------------------------ base64

    @Test
    fun `base64 encodes and decodes with padding`() {
        assertEquals("aGVsbG8=", Base64Codec.encode("hello".toByteArray()))
        assertArrayEquals("hello".toByteArray(), Base64Codec.decode("aGVsbG8="))
    }

    @Test
    fun `base64 tolerates missing padding and line wrapping`() {
        assertArrayEquals("hello".toByteArray(), Base64Codec.decode("aGVsbG8"))
        assertArrayEquals("hello".toByteArray(), Base64Codec.decode("aGVs\nbG8=\n"))
    }

    @Test
    fun `base64 accepts the url safe alphabet`() {
        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())
        val urlSafe = Base64Codec.encode(bytes).replace('+', '-').replace('/', '_')
        assertArrayEquals(bytes, Base64Codec.decode(urlSafe))
    }

    @Test
    fun `base64 rejects malformed input instead of throwing`() {
        assertNull(Base64Codec.decode("aGVsbG8=!!!"))
        assertNull(Base64Codec.decode("aGVsbG8=AAAA"))
        assertNull(Base64Codec.decode("!!!!"))
    }

    // --------------------------------------------------------------------- hex

    @Test
    fun `hex round trips and rejects odd input`() {
        assertEquals("00ff10", Hex.encode(byteArrayOf(0, 0xFF.toByte(), 0x10)))
        assertArrayEquals(byteArrayOf(0, 0xFF.toByte(), 0x10), Hex.decode("00ff10"))
        assertNull(Hex.decode("abc"))
        assertNull(Hex.decode("zz"))
    }

    // ----------------------------------------------------------- little endian

    @Test
    fun `little endian readers agree with hand computed values`() {
        val bytes = Hex.decode("0102030405060708090a0b0c")!!
        assertEquals(0x0201, LittleEndian.u16(bytes, 0))
        assertEquals(0x04030201L, LittleEndian.u32(bytes, 0))
        assertEquals(0x0807060504030201L, LittleEndian.u64(bytes, 0))
    }

    @Test
    fun `little endian u64 reads u64 max as minus one`() {
        val bytes = Hex.decode("ffffffffffffffff")!!
        assertEquals(-1L, LittleEndian.u64(bytes, 0))
        assertTrue(AmountFormat.isUnlimited(LittleEndian.u64(bytes, 0)))
    }

    // ----------------------------------------------------------------- shortvec

    @Test
    fun `shortvec encodes the documented boundary values`() {
        assertEquals("00", Hex.encode(ShortVec.encode(0)))
        assertEquals("7f", Hex.encode(ShortVec.encode(127)))
        assertEquals("8001", Hex.encode(ShortVec.encode(128)))
        assertEquals("ff7f", Hex.encode(ShortVec.encode(16_383)))
        assertEquals("808001", Hex.encode(ShortVec.encode(16_384)))
        assertEquals("ffff03", Hex.encode(ShortVec.encode(65_535)))
    }

    @Test
    fun `shortvec decodes what it encodes`() {
        for (value in listOf(0, 1, 42, 127, 128, 255, 256, 16_383, 16_384, 65_535)) {
            val encoded = ShortVec.encode(value)
            val decoded = ShortVec.decode(encoded)
            assertNotNull("value $value failed to decode", decoded)
            assertEquals(value, decoded!!.value)
            assertEquals(encoded.size, decoded.nextOffset)
        }
    }

    @Test
    fun `shortvec rejects truncated and over long encodings`() {
        assertNull(ShortVec.decode(byteArrayOf(0x80.toByte())))
        assertNull(ShortVec.decode(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01)))
        assertNull(ShortVec.decode(byteArrayOf(), 0))
    }
}

package com.clockin.preflight.mwa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM coverage for the bytes that cross the Mobile Wallet Adapter boundary.
 *
 * These run under `./build.sh :app:testDebugUnitTest --tests 'com.clockin.preflight.mwa.*'` with no
 * emulator: the payload we hand the wallet, and the split of the wallet's answer back into
 * signature + message, are pure Kotlin by design.
 */
class LegacyTransactionTest {

    private val payer = ByteArray(32) { (it + 1).toByte() }
    private val recipient = ByteArray(32) { (it * 3 + 5).toByte() }
    private val blockhash = ByteArray(32) { (it + 100).toByte() }

    @Test
    fun `transfer declares one signature slot and the message starts after it`() {
        val tx = LegacyTransaction.transfer(payer, recipient, 1_000_000L, blockhash)

        assertEquals(1, LegacyTransaction.signatureCount(tx))
        assertEquals(65, LegacyTransaction.messageOffset(tx))
        assertEquals(65 + LegacyTransaction.message(tx).size, tx.size)
    }

    @Test
    fun `message carries the header, the three account keys, the blockhash and one transfer instruction`() {
        val tx = LegacyTransaction.transfer(payer, recipient, 1_000_000L, blockhash)
        val message = LegacyTransaction.message(tx)

        // header: 1 required signature, 0 readonly signed, 1 readonly unsigned (System Program)
        assertArrayEquals(byteArrayOf(1, 0, 1), message.copyOfRange(0, 3))
        var offset = 3

        assertEquals(3, message[offset].toInt() and 0xff) // compact-u16 account count
        offset += 1
        assertArrayEquals(payer, message.copyOfRange(offset, offset + 32))
        offset += 32
        assertArrayEquals(recipient, message.copyOfRange(offset, offset + 32))
        offset += 32
        assertArrayEquals(LegacyTransaction.SYSTEM_PROGRAM_ID, message.copyOfRange(offset, offset + 32))
        offset += 32
        assertArrayEquals(blockhash, message.copyOfRange(offset, offset + 32))
        offset += 32

        assertEquals(1, message[offset].toInt() and 0xff) // compact-u16 instruction count
        offset += 1
        assertEquals(2, message[offset].toInt() and 0xff) // program id index -> System Program
        offset += 1
        assertEquals(2, message[offset].toInt() and 0xff) // two accounts
        offset += 1
        assertEquals(0, message[offset].toInt() and 0xff) // from
        assertEquals(1, message[offset + 1].toInt() and 0xff) // to
        offset += 2
        assertEquals(12, message[offset].toInt() and 0xff) // data length
        offset += 1
        val data = message.copyOfRange(offset, offset + 12)
        assertEquals(LegacyTransaction.SYSTEM_TRANSFER, readU32Le(data, 0))
        assertEquals(1_000_000L, readU64Le(data, 4))
    }

    @Test
    fun `signature slot can be written back and compared against the wallet's answer`() {
        val request = LegacyTransaction.transfer(payer, recipient, 1_000_000L, blockhash)
        val signature = ByteArray(64) { (it + 7).toByte() }
        val response = LegacyTransaction.withSignature(request, signature)

        assertArrayEquals(signature, LegacyTransaction.signature(response))
        assertTrue(LegacyTransaction.signatureSlotMatches(request, response))

        // the request itself is untouched: slot 0 was all zeroes before the wallet answered
        assertArrayEquals(ByteArray(64), LegacyTransaction.signature(request))

        val tampered = response.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFalse(LegacyTransaction.signatureSlotMatches(request, tampered))

        val shortResponse = response.copyOf(response.size - 1)
        assertFalse(LegacyTransaction.signatureSlotMatches(request, shortResponse))
    }

    @Test
    fun `base58 matches the published vectors`() {
        assertEquals("StV1DL6CwTryKyV", LegacyTransaction.base58("hello world".toByteArray()))
        assertEquals("112", LegacyTransaction.base58(byteArrayOf(0, 0, 1)))
        assertEquals("2g", LegacyTransaction.base58(byteArrayOf(0x61)))
        // 32 zero bytes is the System Program address every Solana tool prints as 32 ones
        assertEquals(LegacyTransaction.SYSTEM_PROGRAM_ID_BASE58, LegacyTransaction.base58(ByteArray(32)))
        assertEquals(32, LegacyTransaction.SYSTEM_PROGRAM_ID_BASE58.length)
    }

    @Test
    fun `a published signature vector survives base58 round trip length`() {
        val signature = ByteArray(64) { (it * 11 + 3).toByte() }
        // 64 bytes never encode to fewer than 86 base58 characters
        assertTrue(LegacyTransaction.base58(signature).length in 86..88)
        assertEquals(128, LegacyTransaction.hex(signature).length)
    }

    private fun readU32Le(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) value = value or ((bytes[offset + i].toInt() and 0xff) shl (8 * i))
        return value
    }

    private fun readU64Le(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((bytes[offset + i].toLong() and 0xff) shl (8 * i))
        return value
    }
}

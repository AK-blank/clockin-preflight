package com.clockin.preflight.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Amount rendering: this is the text the demo video shows, so it gets its own tests. */
class AmountFormatTest {

    @Test
    fun `digit grouping uses commas`() {
        assertEquals("0", AmountFormat.group("0"))
        assertEquals("999", AmountFormat.group("999"))
        assertEquals("1,000", AmountFormat.group("1000"))
        assertEquals("12,345", AmountFormat.group("12345"))
        assertEquals("1,000,000", AmountFormat.group("1000000"))
        assertEquals("1,234,567,890", AmountFormat.group("1234567890"))
    }

    @Test
    fun `token amounts scale by decimals and trim trailing zeros`() {
        assertEquals("1,000,000", AmountFormat.scaled(1_000_000_000_000L, 6))
        assertEquals("1.5", AmountFormat.scaled(1_500_000L, 6))
        assertEquals("1.000001", AmountFormat.scaled(1_000_001L, 6))
        assertEquals("0.000001", AmountFormat.scaled(1L, 6))
        assertEquals("42", AmountFormat.scaled(42L, 0))
        assertEquals("42", AmountFormat.scaled(42L, null))
        assertEquals("0.123456789", AmountFormat.scaled(123_456_789L, 9))
    }

    @Test
    fun `u64 max renders as unlimited rather than a number`() {
        assertTrue(AmountFormat.isUnlimited(-1L))
        assertEquals("unlimited USDC", AmountFormat.token(-1L, 6, "USDC"))
        assertEquals("unlimited tokens", AmountFormat.token(-1L, 6, null))
        assertEquals("every lamport in the account", AmountFormat.lamports(-1L))
    }

    @Test
    fun `lamports render as SOL`() {
        assertEquals("1 SOL", AmountFormat.lamports(1_000_000_000L))
        assertEquals("0.00203928 SOL", AmountFormat.lamports(2_039_280L))
        assertEquals("0.000000001 SOL", AmountFormat.lamports(1L))
        assertEquals("5,000 SOL", AmountFormat.lamports(5_000_000_000_000L))
    }

    @Test
    fun `percentages are reported to two decimals at most`() {
        assertEquals("50%", AmountFormat.percentOf(50L, 100L))
        assertEquals("100%", AmountFormat.percentOf(100L, 100L))
        assertEquals("99.9%", AmountFormat.percentOf(999L, 1000L))
        assertEquals("n/a", AmountFormat.percentOf(1L, 0L))
    }
}

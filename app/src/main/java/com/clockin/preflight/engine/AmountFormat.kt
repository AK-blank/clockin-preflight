package com.clockin.preflight.engine

/**
 * Renders raw integer amounts as human strings.
 *
 * Deliberately locale-independent: the same input must produce the same sentence on every device,
 * because the demo video shows this text.
 */
object AmountFormat {

    /** `u64::MAX` seen through a signed Long. */
    const val U64_MAX_AS_LONG: Long = -1L

    private const val MAX_DECIMALS = 18

    /** `18446744073709551615`. */
    const val U64_MAX_TEXT = "18446744073709551615"

    private val POW10 = LongArray(MAX_DECIMALS + 1).also { pow ->
        pow[0] = 1L
        for (i in 1..MAX_DECIMALS) pow[i] = pow[i - 1] * 10L
    }

    /** Exactly `u64::MAX` — the canonical "unlimited approval" value. */
    fun isUnlimited(raw: Long): Boolean = raw == U64_MAX_AS_LONG

    /**
     * Any allowance at or above 2^63.
     *
     * Real mainnet accounts use values like `u64::MAX - 1` for "unlimited" too, and no token has a
     * supply anywhere near 2^63, so anything with the high bit set is unlimited in practice.
     */
    fun isEffectivelyUnlimited(raw: Long): Boolean = raw < 0L

    /** Groups a digit string in threes: `1000000` -> `1,000,000`. */
    fun group(digits: String): String {
        if (digits.length <= 3) return digits
        val sb = StringBuilder(digits.length + digits.length / 3)
        val firstGroup = digits.length % 3
        if (firstGroup > 0) sb.append(digits, 0, firstGroup)
        var i = firstGroup
        while (i < digits.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(digits, i, i + 3)
            i += 3
        }
        return sb.toString()
    }

    /**
     * Renders a raw token amount: `1000000` with 6 decimals -> `1 USDC`.
     *
     * When [raw] is `u64::MAX` the result is `unlimited tokens` / `unlimited USDC`.
     */
    fun token(raw: Long, decimals: Int?, symbol: String? = null): String {
        val unit = symbol?.takeIf { it.isNotBlank() } ?: "tokens"
        if (isEffectivelyUnlimited(raw)) return "unlimited $unit"
        return "${scaled(raw, decimals)} $unit"
    }

    /** Renders a raw lamport count as SOL: `2039280` -> `0.00203928 SOL`. */
    fun lamports(raw: Long): String {
        if (isEffectivelyUnlimited(raw)) return "every lamport in the account"
        return "${scaled(raw, 9)} SOL"
    }

    /** Renders `raw / 10^decimals` without a unit suffix, trimming trailing zeros. */
    fun scaled(raw: Long, decimals: Int?): String {
        if (raw < 0) return U64_MAX_TEXT
        val d = (decimals ?: 0).coerceIn(0, MAX_DECIMALS)
        if (d == 0) return group(raw.toString())
        val divisor = POW10[d]
        val whole = raw / divisor
        val fraction = raw % divisor
        if (fraction == 0L) return group(whole.toString())
        val fractionText = fraction.toString().padStart(d, '0').trimEnd('0')
        return "${group(whole.toString())}.$fractionText"
    }

    /** Percentage of [total] that [part] represents, for evidence strings. */
    fun percentOf(part: Long, total: Long): String {
        if (total <= 0L) return "n/a"
        val basisPoints = (part.toDouble() / total.toDouble()) * 10_000.0
        val rounded = Math.round(basisPoints) / 100.0
        return if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}%"
        else "$rounded%"
    }
}

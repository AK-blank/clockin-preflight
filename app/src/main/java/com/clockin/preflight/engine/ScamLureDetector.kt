package com.clockin.preflight.engine

/**
 * Deterministic "social engineering" text detector.
 *
 * This is intentionally a documented keyword table, not a model: the verdict must be reproducible
 * in a unit test and explainable to a judge. It only ever looks at text a user would actually see —
 * Memo program payloads and the data blob of unrecognised programs — never at opaque binary.
 *
 * Two tiers:
 *  - [LureLevel.HARD] phrases ask for secrets or impersonate a wallet ("seed phrase", "private key",
 *    "validate your wallet"). No legitimate Solana instruction body says these things.
 *  - [LureLevel.SOFT] phrases are the standard lure vocabulary of fake airdrops and claim pages
 *    ("claim", "airdrop", "reward", "visit", any URL, Telegram/Discord invites).
 */
object ScamLureDetector {

    enum class LureLevel { HARD, SOFT }

    data class Match(
        val level: LureLevel,
        val phrase: String,
        /** The offending text, truncated for evidence. */
        val excerpt: String,
        /**
         * True when the only thing wrong with the text is that it contains a URL.
         *
         * This matters: NFT metadata instructions routinely carry `https://ipfs.io/...` in their
         * data, and calling that a scam would drown the real warnings. Callers decide whether a
         * bare URL is worth mentioning based on where the text came from.
         */
        val isBareUrl: Boolean = false,
    )

    /** Phrases that only ever appear in an attempt to steal a key or a seed phrase. */
    private val HARD_PHRASES = listOf(
        "seed phrase", "seedphrase", "recovery phrase", "mnemonic phrase", "your mnemonic",
        "private key", "secret key", "export your key", "reveal your key",
        "import your wallet", "enter your wallet", "validate your wallet", "sync your wallet",
        "restore your wallet", "verify your wallet", "wallet verification",
        "connect your wallet to", "double your", "claim your private",
    )

    /** Lure vocabulary used by fake airdrop / claim pages. */
    private val SOFT_PHRASES = listOf(
        "claim", "airdrop", "air drop", "reward", "bonus", "giveaway", "free mint",
        "whitelist", "presale", "visit ", "click here", "sign to receive", "urgent",
        "last chance", "limited time", "congratulations", "you have won", "exclusive drop",
        "t.me/", "telegram", "discord.gg",
    )

    const val MAX_EXCERPT = 140

    /** Scans user-visible text. Returns the strongest match, or `null`. */
    fun scan(text: String): Match? {
        if (text.length < 3) return null
        val lower = text.lowercase()
        for (phrase in HARD_PHRASES) {
            if (lower.contains(phrase)) return Match(LureLevel.HARD, phrase, excerpt(text))
        }
        for (phrase in SOFT_PHRASES) {
            if (lower.contains(phrase)) return Match(LureLevel.SOFT, phrase, excerpt(text))
        }
        if (containsUrl(lower)) {
            return Match(LureLevel.SOFT, "http(s) URL", excerpt(text), isBareUrl = true)
        }
        return null
    }

    /** True when the text contains a URL or a bare `www.` host. */
    fun containsUrl(lowercaseText: String): Boolean =
        lowercaseText.contains("http://") ||
            lowercaseText.contains("https://") ||
            lowercaseText.contains("www.") ||
            lowercaseText.contains(".com/") ||
            lowercaseText.contains(".io/") ||
            lowercaseText.contains(".xyz") ||
            lowercaseText.contains(".app/")

    /**
     * Heuristic: bytes that decode to mostly printable text.
     *
     * Avoids running the keyword table over compressed or binary instruction payloads, which would
     * otherwise produce nonsense matches.
     */
    fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val text = String(bytes, Charsets.UTF_8)
        if (text.any { it == '\uFFFD' }) return false
        var printable = 0
        for (ch in text) {
            if (ch.code in 32..126) printable++
            else if (ch.code > 127) printable++ // treat other scripts as text too
        }
        return printable.toDouble() / text.length >= 0.85
    }

    private fun excerpt(text: String): String {
        val cleaned = text.replace("\n", " ").replace("\r", " ").trim()
        return if (cleaned.length <= MAX_EXCERPT) cleaned else cleaned.take(MAX_EXCERPT) + "\u2026"
    }
}

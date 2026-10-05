package com.clockin.preflight.engine

/**
 * How much the engine trusts a program id.
 *
 * This is deliberately a small, hand-maintained allowlist rather than a reputation feed: the
 * verdict has to be deterministic and reproducible offline.
 */
enum class ProgramTrust {
    /** Shipped with the Solana runtime or an SPL program maintained by Solana Labs. */
    CORE,

    /** Widely used third-party programs we recognise by name but have not audited. */
    WELL_KNOWN,

    /** Not recognised at all — the dangerous case. */
    UNKNOWN,
}

data class ProgramInfo(
    val id: String,
    val name: String,
    val trust: ProgramTrust,
)

/** Well-known Solana program ids, and the name/trust level attached to each. */
object ProgramRegistry {

    const val SYSTEM = "11111111111111111111111111111111"
    const val TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    const val ASSOCIATED_TOKEN = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    const val COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111"
    const val MEMO_V1 = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
    const val MEMO_V3 = "Memo1UhkJRfHyvLMcVucJwxXeuD728EqVDDwQDxFMNo"
    const val STAKE = "Stake11111111111111111111111111111111111111"
    const val VOTE = "Vote111111111111111111111111111111111111111"
    const val CONFIG = "Config1111111111111111111111111111111111111"
    const val ADDRESS_LOOKUP_TABLE = "AddressLookupTab1e1111111111111111111111111"
    const val ED25519 = "Ed25519SigVerify111111111111111111111111111"
    const val SECP256K1 = "KeccakSecp256k11111111111111111111111111111"
    const val BPF_LOADER = "BPFLoaderUpgradeab1e11111111111111111111111"
    const val BPF_LOADER_DEPRECATED = "BPFLoader2111111111111111111111111111111111"
    const val BPF_LOADER_V1 = "BPFLoader1111111111111111111111111111111111"
    const val NATIVE_LOADER = "NativeLoader1111111111111111111111111111111"
    const val SYSVAR_RENT = "SysvarRent111111111111111111111111111111111"
    const val SYSVAR_CLOCK = "SysvarC1ock11111111111111111111111111111111"
    const val SYSVAR_INSTRUCTIONS = "Sysvar1nstructions1111111111111111111111111"
    const val SYSVAR_RECENT_BLOCKHASHES = "SysvarRecentB1ockHashes11111111111111111111"
    const val SYSVAR_SLOT_HASHES = "SysvarS1otHashes111111111111111111111111111"
    const val SYSVAR_STAKE_HISTORY = "SysvarStakeHistory1111111111111111111111111"
    const val SYSVAR_EPOCH_SCHEDULE = "SysvarEpochSchedu1e111111111111111111111111"
    const val SYSVAR_REWARDS = "SysvarRewards111111111111111111111111111111"
    const val SYSVAR_FEES = "SysvarFees111111111111111111111111111111111"
    const val INSTRUCTIONS_SYSVAR = "Sysvar1nstructions1111111111111111111111111"

    // A few very common third-party programs, named so the UI can say what it is.
    const val JUPITER_V6 = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4"
    const val JUPITER_V4 = "JUP4Fb2cqiRUcaTHdrPC8h2gNsA2ETXiPDD33WcGuJB"
    const val METAPLEX_TOKEN_METADATA = "metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s"
    const val RAYDIUM_AMM_V4 = "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8"
    const val ORCA_WHIRLPOOL = "whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc"
    const val MAGIC_EDEN_V2 = "M2mx93ekt1fmXSVkTrUL9xVFHkmME8HTUi5Cyc5aF7K"
    const val SPL_TOKEN_SWAP = "SwaPpA9LAaLfeLi3a68M4DjnLqgtticKg6CnyNwgAC8"
    // Added after observing real mainnet traffic: without these, the "unknown program" finding fires
    // on ordinary pump.fun/Meteora swaps, which makes the warning meaningless. Every id here was
    // taken from the `Program <id> invoke` lines of an actual fetched mainnet transaction.
    const val PUMP_AMM = "pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA"
    const val PUMP_FEE = "pfeeUxB6jkeY1Hxd7CsFCAjcbHA9rWtchMGdZ6VojVZ"
    const val METEORA_DLMM = "LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo"

    private val CORE: List<ProgramInfo> = listOf(
        ProgramInfo(SYSTEM, "System Program", ProgramTrust.CORE),
        ProgramInfo(TOKEN, "SPL Token Program", ProgramTrust.CORE),
        ProgramInfo(TOKEN_2022, "SPL Token-2022 Program", ProgramTrust.CORE),
        ProgramInfo(ASSOCIATED_TOKEN, "Associated Token Account Program", ProgramTrust.CORE),
        ProgramInfo(COMPUTE_BUDGET, "Compute Budget Program", ProgramTrust.CORE),
        ProgramInfo(MEMO_V1, "Memo Program v1", ProgramTrust.CORE),
        ProgramInfo(MEMO_V3, "Memo Program v3", ProgramTrust.CORE),
        ProgramInfo(STAKE, "Stake Program", ProgramTrust.CORE),
        ProgramInfo(VOTE, "Vote Program", ProgramTrust.CORE),
        ProgramInfo(CONFIG, "Config Program", ProgramTrust.CORE),
        ProgramInfo(ADDRESS_LOOKUP_TABLE, "Address Lookup Table Program", ProgramTrust.CORE),
        ProgramInfo(ED25519, "Ed25519 Signature Verify (precompile)", ProgramTrust.CORE),
        ProgramInfo(SECP256K1, "Secp256k1 Recover (precompile)", ProgramTrust.CORE),
        ProgramInfo(BPF_LOADER, "BPF Upgradeable Loader", ProgramTrust.CORE),
        ProgramInfo(BPF_LOADER_DEPRECATED, "BPF Loader (deprecated)", ProgramTrust.CORE),
        ProgramInfo(BPF_LOADER_V1, "BPF Loader", ProgramTrust.CORE),
        ProgramInfo(NATIVE_LOADER, "Native Loader", ProgramTrust.CORE),
        ProgramInfo(SYSVAR_RENT, "Sysvar: Rent", ProgramTrust.CORE),
        ProgramInfo(SYSVAR_CLOCK, "Sysvar: Clock", ProgramTrust.CORE),
        ProgramInfo(SYSVAR_INSTRUCTIONS, "Sysvar: Instructions", ProgramTrust.CORE),
        ProgramInfo(SYSVAR_RECENT_BLOCKHASHES, "Sysvar: Recent Blockhashes", ProgramTrust.CORE),
        ProgramInfo(SYSVAR_SLOT_HASHES, "Sysvar: Slot Hashes", ProgramTrust.CORE),
        ProgramInfo(SYSVAR_STAKE_HISTORY, "Sysvar: Stake History", ProgramTrust.CORE),
        ProgramInfo(SYSVAR_EPOCH_SCHEDULE, "Sysvar: Epoch Schedule", ProgramTrust.CORE),
        ProgramInfo(SYSVAR_REWARDS, "Sysvar: Rewards", ProgramTrust.CORE),
        ProgramInfo(SYSVAR_FEES, "Sysvar: Fees", ProgramTrust.CORE),
    )

    private val WELL_KNOWN: List<ProgramInfo> = listOf(
        ProgramInfo(JUPITER_V6, "Jupiter Aggregator v6", ProgramTrust.WELL_KNOWN),
        ProgramInfo(JUPITER_V4, "Jupiter Aggregator v4", ProgramTrust.WELL_KNOWN),
        ProgramInfo(METAPLEX_TOKEN_METADATA, "Metaplex Token Metadata", ProgramTrust.WELL_KNOWN),
        ProgramInfo(RAYDIUM_AMM_V4, "Raydium AMM v4", ProgramTrust.WELL_KNOWN),
        ProgramInfo(ORCA_WHIRLPOOL, "Orca Whirlpool", ProgramTrust.WELL_KNOWN),
        ProgramInfo(MAGIC_EDEN_V2, "Magic Eden v2", ProgramTrust.WELL_KNOWN),
        ProgramInfo(SPL_TOKEN_SWAP, "SPL Token Swap", ProgramTrust.WELL_KNOWN),
        ProgramInfo(PUMP_AMM, "pump.fun AMM", ProgramTrust.WELL_KNOWN),
        ProgramInfo(PUMP_FEE, "pump.fun fee program", ProgramTrust.WELL_KNOWN),
        ProgramInfo(METEORA_DLMM, "Meteora DLMM", ProgramTrust.WELL_KNOWN),
    )

    private val BY_ID: Map<String, ProgramInfo> =
        (CORE + WELL_KNOWN).associateBy { it.id }

    private val UNKNOWN_INFO = ProgramInfo("", "Unknown program", ProgramTrust.UNKNOWN)

    fun lookup(programId: String): ProgramInfo = BY_ID[programId] ?: UNKNOWN_INFO

    fun name(programId: String): String = BY_ID[programId]?.name ?: shortId(programId)

    fun trust(programId: String): ProgramTrust = BY_ID[programId]?.trust ?: ProgramTrust.UNKNOWN

    fun isCore(programId: String): Boolean = BY_ID[programId]?.trust == ProgramTrust.CORE

    /** Every program id this registry knows, useful for tests and diagnostics. */
    fun knownIds(): Set<String> = BY_ID.keys

    /**
     * Shortens a base58 pubkey for display: `7xKq…9fQ`.
     *
     * For ids shorter than 10 characters the whole string is returned unchanged.
     */
    fun shortId(id: String, head: Int = 4, tail: Int = 4): String {
        if (id.length <= head + tail + 1) return id
        return id.substring(0, head) + "\u2026" + id.substring(id.length - tail)
    }
}

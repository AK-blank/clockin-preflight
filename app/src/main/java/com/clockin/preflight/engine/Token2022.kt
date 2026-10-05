package com.clockin.preflight.engine

/**
 * The dangerous Token-2022 mint/account extensions, as understood from on-chain account data.
 *
 * These cannot be inferred from a transaction alone: the mint account has to be inspected. The
 * engine accepts them through [RiskContext] so the data layer can fetch and parse them once.
 */
data class TokenExtensions(
    /** `PermanentDelegate`: someone can move or burn any holder's tokens, forever. */
    val permanentDelegate: String? = null,
    /** `TransferHook`: every transfer calls out to an arbitrary third-party program. */
    val transferHookProgram: String? = null,
    val transferHookAuthority: String? = null,
    /** `TransferFeeConfig` basis points currently in force. */
    val transferFeeBasisPoints: Int? = null,
    val transferFeeMaximum: Long? = null,
    val transferFeeConfigAuthority: String? = null,
    val transferFeeWithdrawAuthority: String? = null,
    /** `DefaultAccountState`: new accounts can start out frozen. */
    val defaultAccountState: DefaultAccountState? = null,
    /** `MintCloseAuthority`: someone other than the holder can close the mint. */
    val mintCloseAuthority: String? = null,
    val nonTransferable: Boolean = false,
    val memoTransferRequired: Boolean = false,
    val cpiGuard: Boolean = false,
    val immutableOwner: Boolean = false,
    /** Every extension type id found in the account's TLV region, for evidence/diagnostics. */
    val extensionTypes: List<Int> = emptyList(),
) {
    val hasPermanentDelegate: Boolean get() = permanentDelegate != null
    val hasTransferHook: Boolean get() = transferHookProgram != null
    val freezesNewAccounts: Boolean get() = defaultAccountState == DefaultAccountState.FROZEN
    val hasTransferFee: Boolean get() = (transferFeeBasisPoints ?: 0) > 0

    /** Extension names for display, e.g. `PermanentDelegate, TransferHook`. */
    fun extensionNames(): List<String> = extensionTypes.map { Token2022Parser.extensionName(it) }

    val isEmpty: Boolean
        get() = extensionTypes.isEmpty() && permanentDelegate == null && transferHookProgram == null

    companion object {
        val NONE = TokenExtensions()
    }
}

/** SPL Token mint account fields (the 82-byte base, shared by Token and Token-2022). */
data class MintAccountInfo(
    val decimals: Int,
    val supply: Long,
    val mintAuthority: String?,
    val freezeAuthority: String?,
    val isInitialized: Boolean,
    val extensions: TokenExtensions,
    /** `true` when the account carries Token-2022 TLV extension data. */
    val isToken2022: Boolean,
    val rawSize: Int,
)

/** SPL Token account fields (the 165-byte base, shared by Token and Token-2022). */
data class TokenAccountInfo(
    /** The token account's own address, when the caller knows it. */
    val address: String? = null,
    val mint: String,
    val owner: String,
    val amount: Long,
    val delegate: String?,
    val delegatedAmount: Long,
    val state: TokenAccountState?,
    val closeAuthority: String?,
    val isNative: Boolean,
    val extensions: TokenExtensions,
    val isToken2022: Boolean,
    val rawSize: Int,
) {
    val isFrozen: Boolean get() = state == TokenAccountState.FROZEN
    val hasDelegate: Boolean get() = delegate != null
}

/** Result of parsing token account data supplied as base64. */
sealed class TokenAccountData {
    data class Mint(val info: MintAccountInfo) : TokenAccountData()
    data class Account(val info: TokenAccountInfo) : TokenAccountData()
    data class Failure(val error: DecodeError) : TokenAccountData()

    fun mintOrNull(): MintAccountInfo? = (this as? Mint)?.info
    fun accountOrNull(): TokenAccountInfo? = (this as? Account)?.info
}

/**
 * Parser for SPL Token and Token-2022 account data.
 *
 * Handles the base layouts plus the Token-2022 TLV extension region, so the risk engine can see
 * `PermanentDelegate`, `TransferHook`, `DefaultAccountState` and `TransferFeeConfig` without
 * depending on the Solana SDK. Never throws; malformed data yields [TokenAccountData.Failure].
 */
object Token2022Parser {

    const val BASE_MINT_SIZE = 82
    const val BASE_ACCOUNT_SIZE = 165

    /** Token-2022 `AccountType::Mint`. */
    const val MINT_ACCOUNT_TYPE = 1

    /** Token-2022 `AccountType::Account`. */
    const val TOKEN_ACCOUNT_TYPE = 2

    // ExtensionType discriminants (spl-token-2022 `ExtensionType`).
    const val EXT_UNINITIALIZED = 0
    const val EXT_TRANSFER_FEE_CONFIG = 1
    const val EXT_TRANSFER_FEE_AMOUNT = 2
    const val EXT_MINT_CLOSE_AUTHORITY = 3
    const val EXT_CONFIDENTIAL_TRANSFER_MINT = 4
    const val EXT_CONFIDENTIAL_TRANSFER_ACCOUNT = 5
    const val EXT_DEFAULT_ACCOUNT_STATE = 6
    const val EXT_IMMUTABLE_OWNER = 7
    const val EXT_MEMO_TRANSFER = 8
    const val EXT_NON_TRANSFERABLE = 9
    const val EXT_INTEREST_BEARING_CONFIG = 10
    const val EXT_CPI_GUARD = 11
    const val EXT_PERMANENT_DELEGATE = 12
    const val EXT_NON_TRANSFERABLE_ACCOUNT = 13
    const val EXT_TRANSFER_HOOK = 14
    const val EXT_TRANSFER_HOOK_ACCOUNT = 15
    const val EXT_CONFIDENTIAL_TRANSFER_FEE_CONFIG = 16
    const val EXT_CONFIDENTIAL_TRANSFER_FEE_AMOUNT = 17
    const val EXT_METADATA_POINTER = 18
    const val EXT_TOKEN_METADATA = 19
    const val EXT_GROUP_POINTER = 20
    const val EXT_TOKEN_GROUP = 21
    const val EXT_GROUP_MEMBER_POINTER = 22
    const val EXT_TOKEN_GROUP_MEMBER = 23

    private val NAMES = mapOf(
        EXT_UNINITIALIZED to "Uninitialized",
        EXT_TRANSFER_FEE_CONFIG to "TransferFeeConfig",
        EXT_TRANSFER_FEE_AMOUNT to "TransferFeeAmount",
        EXT_MINT_CLOSE_AUTHORITY to "MintCloseAuthority",
        EXT_CONFIDENTIAL_TRANSFER_MINT to "ConfidentialTransferMint",
        EXT_CONFIDENTIAL_TRANSFER_ACCOUNT to "ConfidentialTransferAccount",
        EXT_DEFAULT_ACCOUNT_STATE to "DefaultAccountState",
        EXT_IMMUTABLE_OWNER to "ImmutableOwner",
        EXT_MEMO_TRANSFER to "MemoTransfer",
        EXT_NON_TRANSFERABLE to "NonTransferable",
        EXT_INTEREST_BEARING_CONFIG to "InterestBearingConfig",
        EXT_CPI_GUARD to "CpiGuard",
        EXT_PERMANENT_DELEGATE to "PermanentDelegate",
        EXT_NON_TRANSFERABLE_ACCOUNT to "NonTransferableAccount",
        EXT_TRANSFER_HOOK to "TransferHook",
        EXT_TRANSFER_HOOK_ACCOUNT to "TransferHookAccount",
        EXT_CONFIDENTIAL_TRANSFER_FEE_CONFIG to "ConfidentialTransferFeeConfig",
        EXT_CONFIDENTIAL_TRANSFER_FEE_AMOUNT to "ConfidentialTransferFeeAmount",
        EXT_METADATA_POINTER to "MetadataPointer",
        EXT_TOKEN_METADATA to "TokenMetadata",
        EXT_GROUP_POINTER to "GroupPointer",
        EXT_TOKEN_GROUP to "TokenGroup",
        EXT_GROUP_MEMBER_POINTER to "GroupMemberPointer",
        EXT_TOKEN_GROUP_MEMBER to "TokenGroupMember",
    )

    fun extensionName(type: Int): String = NAMES[type] ?: "Extension#$type"

    fun parseBase64(base64: String): TokenAccountData {
        val bytes = Base64Codec.decode(base64)
            ?: return TokenAccountData.Failure(
                DecodeError.InvalidBase64("account data is not valid base64")
            )
        return parse(bytes)
    }

    /**
     * Where the Token-2022 extension region sits inside an account.
     *
     * The SPL Token-2022 specification puts the `AccountType` byte immediately after the base
     * struct: offset 82 for a mint, 165 for a token account. Real mainnet mints in the wild (the
     * pump.fun/CASH family, captured in `MainnetAccounts`) instead pad the mint base out to 165
     * bytes, so their type byte and TLV region also start at 165/166. Rather than trusting either
     * convention, we locate the region by finding the offset whose TLV walk lands exactly on the
     * end of the account data.
     */
    private data class ExtensionRegion(val accountType: Int, val tlvStart: Int)

    fun parse(bytes: ByteArray): TokenAccountData {
        if (bytes.size < BASE_MINT_SIZE) {
            return TokenAccountData.Failure(
                DecodeError.Truncated(
                    message = "token account data is ${bytes.size} bytes, the base layout needs at " +
                        "least $BASE_MINT_SIZE",
                    offset = bytes.size,
                    needed = BASE_MINT_SIZE,
                    remaining = bytes.size,
                )
            )
        }
        val region = findExtensionRegion(bytes)
        val accountType = region?.accountType
        val isAccount = when {
            accountType == MINT_ACCOUNT_TYPE -> false
            accountType == TOKEN_ACCOUNT_TYPE -> true
            // No recognisable extension region: fall back to the classic fixed sizes.
            bytes.size == BASE_MINT_SIZE -> false
            bytes.size == BASE_ACCOUNT_SIZE -> true
            // Shorter than a token account: it can only be a mint whose extension region is
            // missing or truncated. Reading the base fields is still useful.
            bytes.size in (BASE_MINT_SIZE + 1) until BASE_ACCOUNT_SIZE -> false
            bytes.size > BASE_ACCOUNT_SIZE -> when (bytes[BASE_MINT_SIZE].toInt() and 0xFF) {
                MINT_ACCOUNT_TYPE -> false
                TOKEN_ACCOUNT_TYPE -> true
                else -> return TokenAccountData.Failure(
                    DecodeError.Malformed(
                        "unrecognised token account layout: ${bytes.size} bytes with account type " +
                            "$accountType"
                    )
                )
            }

            else -> return TokenAccountData.Failure(
                DecodeError.Malformed("unrecognised token account size: ${bytes.size} bytes")
            )
        }
        return if (isAccount) parseTokenAccount(bytes, region) else parseMint(bytes, region)
    }

    /**
     * Finds the `AccountType` byte and TLV start, or `null` when the account carries no
     * recognisable extension region.
     */
    private fun findExtensionRegion(bytes: ByteArray): ExtensionRegion? {
        for (base in intArrayOf(BASE_MINT_SIZE, BASE_ACCOUNT_SIZE)) {
            if (bytes.size <= base) continue
            val type = bytes[base].toInt() and 0xFF
            if (type != MINT_ACCOUNT_TYPE && type != TOKEN_ACCOUNT_TYPE) continue
            if (isWellFormedTlv(bytes, base + 1)) return ExtensionRegion(type, base + 1)
        }
        return null
    }

    /**
     * True when the TLV entries starting at [start] consume the rest of the buffer exactly.
     *
     * This is what makes the layout detection above unambiguous: a random byte inside a pubkey
     * can look like an account type, but it will not be followed by a length-prefixed TLV list
     * that lands precisely on the end of the account.
     */
    private fun isWellFormedTlv(bytes: ByteArray, start: Int): Boolean {
        var offset = start
        while (offset + 4 <= bytes.size) {
            val type = LittleEndian.u16(bytes, offset)
            val length = LittleEndian.u16(bytes, offset + 2)
            offset += 4
            if (type == EXT_UNINITIALIZED && length == 0) {
                // Zero padding is allowed only if it really is zero all the way to the end.
                for (i in offset until bytes.size) {
                    if (bytes[i] != 0.toByte()) return false
                }
                return true
            }
            if (length == 0 || offset + length > bytes.size) return false
            offset += length
        }
        return offset == bytes.size
    }

    private fun parseMint(bytes: ByteArray, region: ExtensionRegion?): TokenAccountData {
        val mintAuthority = readCOptionPubkey(bytes, 0)
        val supply = LittleEndian.u64(bytes, 36)
        val decimals = bytes[44].toInt() and 0xFF
        val isInitialized = (bytes[45].toInt() and 0xFF) != 0
        val freezeAuthority = readCOptionPubkey(bytes, 46)

        // Best effort when the region is not perfectly formed: reporting an extension we *can*
        // read is safer than silently dropping a PermanentDelegate because a later entry is torn.
        val extensions = region?.let { parseExtensions(bytes, it.tlvStart) }
            ?: parseExtensions(bytes, BASE_MINT_SIZE + 1)

        return TokenAccountData.Mint(
            MintAccountInfo(
                decimals = decimals,
                supply = supply,
                mintAuthority = mintAuthority,
                freezeAuthority = freezeAuthority,
                isInitialized = isInitialized,
                extensions = extensions,
                isToken2022 = extensions.extensionTypes.isNotEmpty(),
                rawSize = bytes.size,
            )
        )
    }

    private fun parseTokenAccount(bytes: ByteArray, region: ExtensionRegion?): TokenAccountData {
        if (bytes.size < BASE_ACCOUNT_SIZE) {
            return TokenAccountData.Failure(
                DecodeError.Truncated(
                    message = "token account data is ${bytes.size} bytes, expected " +
                        "$BASE_ACCOUNT_SIZE",
                    offset = bytes.size,
                    needed = BASE_ACCOUNT_SIZE,
                    remaining = bytes.size,
                )
            )
        }
        val mint = Base58.encode(bytes.copyOfRange(0, 32))
        val owner = Base58.encode(bytes.copyOfRange(32, 64))
        val amount = LittleEndian.u64(bytes, 64)
        val delegate = readCOptionPubkey(bytes, 72)
        val state = TokenAccountState.fromTag(bytes[108].toInt() and 0xFF)
        val isNative = (bytes[109].toInt() and 0xFF) == 1
        val delegatedAmount = LittleEndian.u64(bytes, 121)
        val closeAuthority = readCOptionPubkey(bytes, 129)

        val extensions = region?.let { parseExtensions(bytes, it.tlvStart) }
            ?: parseExtensions(bytes, BASE_ACCOUNT_SIZE + 1)

        return TokenAccountData.Account(
            TokenAccountInfo(
                mint = mint,
                owner = owner,
                amount = amount,
                delegate = delegate,
                delegatedAmount = delegatedAmount,
                state = state,
                closeAuthority = closeAuthority,
                isNative = isNative,
                extensions = extensions,
                isToken2022 = bytes.size > BASE_ACCOUNT_SIZE,
                rawSize = bytes.size,
            )
        )
    }

    /**
     * Walks the Token-2022 TLV region that follows the base layout.
     *
     * Returns the aggregated [TokenExtensions] plus the raw `(type, length)` list. A truncated or
     * nonsensical TLV entry stops the walk rather than failing the whole parse: whatever we already
     * understood is still worth reporting.
     */
    private fun parseExtensions(bytes: ByteArray, tlvStart: Int): TokenExtensions {
        if (bytes.size <= tlvStart) return TokenExtensions.NONE

        var offset = tlvStart
        val extensionTypes = ArrayList<Int>()

        var permanentDelegate: String? = null
        var transferHookProgram: String? = null
        var transferHookAuthority: String? = null
        var transferFeeBps: Int? = null
        var transferFeeMax: Long? = null
        var transferFeeConfigAuthority: String? = null
        var transferFeeWithdrawAuthority: String? = null
        var defaultAccountState: DefaultAccountState? = null
        var mintCloseAuthority: String? = null
        var nonTransferable = false
        var memoTransferRequired = false
        var cpiGuard = false
        var immutableOwner = false

        while (offset + 4 <= bytes.size) {
            val type = LittleEndian.u16(bytes, offset)
            val length = LittleEndian.u16(bytes, offset + 2)
            offset += 4
            if (type == EXT_UNINITIALIZED && length == 0) break // zero padding to account size
            if (length < 0 || offset + length > bytes.size) break // truncated TLV entry
            val body = bytes.copyOfRange(offset, offset + length)
            extensionTypes.add(type)

            when (type) {
                EXT_PERMANENT_DELEGATE ->
                    if (body.size >= 32) permanentDelegate = optionalKey(body, 0)

                EXT_TRANSFER_HOOK ->
                    if (body.size >= 64) {
                        transferHookAuthority = optionalKey(body, 0)
                        transferHookProgram = optionalKey(body, 32)
                    }

                EXT_TRANSFER_FEE_CONFIG ->
                    if (body.size >= 108) {
                        transferFeeConfigAuthority = optionalKey(body, 0)
                        transferFeeWithdrawAuthority = optionalKey(body, 32)
                        // olderTransferFee / newerTransferFee are 18 bytes each:
                        // epoch:u64, maximumFee:u64, transferFeeBasisPoints:u16
                        val olderBps = LittleEndian.u16(body, 88)
                        val olderMax = LittleEndian.u64(body, 80)
                        val newerEpoch = LittleEndian.u64(body, 90)
                        val newerBps = LittleEndian.u16(body, 106)
                        val newerMax = LittleEndian.u64(body, 98)
                        if (newerEpoch != 0L || newerBps != 0) {
                            transferFeeBps = newerBps
                            transferFeeMax = newerMax
                        } else {
                            transferFeeBps = olderBps
                            transferFeeMax = olderMax
                        }
                    }

                EXT_DEFAULT_ACCOUNT_STATE ->
                    if (body.isNotEmpty()) {
                        defaultAccountState = DefaultAccountState.fromTag(body[0].toInt() and 0xFF)
                    }

                EXT_MINT_CLOSE_AUTHORITY ->
                    if (body.size >= 32) mintCloseAuthority = optionalKey(body, 0)

                EXT_NON_TRANSFERABLE, EXT_NON_TRANSFERABLE_ACCOUNT -> nonTransferable = true

                EXT_MEMO_TRANSFER ->
                    if (body.isNotEmpty()) memoTransferRequired = (body[0].toInt() and 0xFF) == 1

                EXT_CPI_GUARD ->
                    if (body.isNotEmpty()) cpiGuard = (body[0].toInt() and 0xFF) == 1

                EXT_IMMUTABLE_OWNER -> immutableOwner = true
            }

            offset += length
        }

        return TokenExtensions(
            permanentDelegate = permanentDelegate,
            transferHookProgram = transferHookProgram,
            transferHookAuthority = transferHookAuthority,
            transferFeeBasisPoints = transferFeeBps,
            transferFeeMaximum = transferFeeMax,
            transferFeeConfigAuthority = transferFeeConfigAuthority,
            transferFeeWithdrawAuthority = transferFeeWithdrawAuthority,
            defaultAccountState = defaultAccountState,
            mintCloseAuthority = mintCloseAuthority,
            nonTransferable = nonTransferable,
            memoTransferRequired = memoTransferRequired,
            cpiGuard = cpiGuard,
            immutableOwner = immutableOwner,
            extensionTypes = extensionTypes,
        )
    }

    /** SPL `COption<Pubkey>`: a 4-byte little-endian tag followed by 32 key bytes. */
    private fun readCOptionPubkey(bytes: ByteArray, offset: Int): String? {
        if (offset + 36 > bytes.size) return null
        val tag = LittleEndian.u32(bytes, offset)
        if (tag != 1L) return null
        return Base58.encode(bytes.copyOfRange(offset + 4, offset + 36))
    }

    /** `OptionalNonZeroPubkey`: an all-zero key means "unset". */
    private fun optionalKey(bytes: ByteArray, offset: Int): String? {
        if (offset + 32 > bytes.size) return null
        var allZero = true
        for (i in offset until offset + 32) {
            if (bytes[i] != 0.toByte()) {
                allZero = false
                break
            }
        }
        if (allZero) return null
        return Base58.encode(bytes.copyOfRange(offset, offset + 32))
    }
}

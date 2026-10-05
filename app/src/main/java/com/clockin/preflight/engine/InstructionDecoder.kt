package com.clockin.preflight.engine

/**
 * Hand-rolled instruction-body decoding.
 *
 * Layouts implemented from the on-chain program sources:
 *  - System Program: 4-byte little-endian `u32` discriminant (bincode), then fields.
 *  - SPL Token / Token-2022: 1-byte `u8` discriminant, then fields.
 *  - ComputeBudget: 1-byte `u8` discriminant.
 *  - Associated Token Account: empty data; the account list is the payload.
 *  - Memo: raw UTF-8 bytes.
 *
 * Every function here is total: a body that does not match the expected length simply yields
 * `null` (unrecognised) instead of throwing.
 */
internal object InstructionDecoder {

    fun decode(programId: String, accounts: List<String>, data: ByteArray): InstructionKind? {
        return try {
            when (programId) {
                ProgramRegistry.SYSTEM -> decodeSystem(data)
                ProgramRegistry.TOKEN -> decodeToken(data, token2022 = false)
                ProgramRegistry.TOKEN_2022 -> decodeToken(data, token2022 = true)
                ProgramRegistry.ASSOCIATED_TOKEN -> decodeAssociatedToken(accounts, data)
                ProgramRegistry.COMPUTE_BUDGET -> decodeComputeBudget(data)
                ProgramRegistry.MEMO_V1 -> InstructionKind.Memo(memoText(data), "Memo v1")
                ProgramRegistry.MEMO_V3 -> InstructionKind.Memo(memoText(data), "Memo v3")
                else -> null
            }
        } catch (_: Exception) {
            // A malformed instruction body must never abort decoding of the whole transaction.
            null
        }
    }

    // --------------------------------------------------------------- helpers

    private fun has(data: ByteArray, offset: Int, length: Int): Boolean =
        offset >= 0 && length >= 0 && data.size >= offset + length

    private fun keyAt(data: ByteArray, offset: Int): String? =
        if (has(data, offset, 32)) Base58.encode(data.copyOfRange(offset, offset + 32)) else null

    private fun memoText(data: ByteArray): String =
        String(data, Charsets.UTF_8).replace("\u0000", "").trim()

    // ---------------------------------------------------------------- System

    private fun decodeSystem(data: ByteArray): InstructionKind? {
        if (data.size < 4) return null
        return when (LittleEndian.u32(data, 0).toInt()) {
            0 -> // CreateAccount { lamports, space, owner }
                if (has(data, 4, 48)) {
                    InstructionKind.SystemCreateAccount(
                        lamports = LittleEndian.u64(data, 4),
                        space = LittleEndian.u64(data, 12),
                        owner = keyAt(data, 20),
                    )
                } else null

            1 -> InstructionKind.SystemAssign(if (has(data, 4, 32)) keyAt(data, 4) else null)

            2 -> if (has(data, 4, 8)) InstructionKind.SystemTransfer(LittleEndian.u64(data, 4)) else null

            3 -> { // CreateAccountWithSeed { base, seed, lamports, space, owner }
                var p = 4
                if (!has(data, p, 32)) return null
                p += 32
                if (!has(data, p, 8)) return null
                val seedLen = LittleEndian.u64(data, p).toInt()
                p += 8
                if (seedLen < 0 || !has(data, p, seedLen)) return null
                val seed = String(data, p, seedLen, Charsets.UTF_8)
                p += seedLen
                if (!has(data, p, 16)) return null
                val lamports = LittleEndian.u64(data, p)
                val space = LittleEndian.u64(data, p + 8)
                p += 16
                val owner = keyAt(data, p)
                InstructionKind.SystemCreateAccount(lamports, space, owner)
            }

            4 -> InstructionKind.SystemNonce("AdvanceNonceAccount")

            5 -> if (has(data, 4, 8)) {
                InstructionKind.SystemNonce("WithdrawNonceAccount", LittleEndian.u64(data, 4))
            } else null

            6 -> InstructionKind.SystemNonce("InitializeNonceAccount")

            7 -> InstructionKind.SystemNonce("AuthorizeNonceAccount")

            8 -> if (has(data, 4, 8)) InstructionKind.SystemAllocate(LittleEndian.u64(data, 4)) else null

            9 -> InstructionKind.SystemNonce("AllocateWithSeed")

            10 -> InstructionKind.SystemNonce("AssignWithSeed")

            11 -> // TransferWithSeed { lamports, from_seed, from_owner }
                if (has(data, 4, 8)) {
                    var p = 12
                    var seed: String? = null
                    if (has(data, p, 8)) {
                        val seedLen = LittleEndian.u64(data, p).toInt()
                        p += 8
                        if (seedLen in 0..(data.size - p)) {
                            seed = String(data, p, seedLen, Charsets.UTF_8)
                        }
                    }
                    InstructionKind.SystemTransferWithSeed(LittleEndian.u64(data, 4), seed)
                } else null

            12 -> InstructionKind.SystemNonce("UpgradeNonceAccount")

            else -> null
        }
    }

    // ----------------------------------------------------------- SPL Token

    private fun decodeToken(data: ByteArray, token2022: Boolean): InstructionKind? {
        if (data.isEmpty()) return null
        val tag = data[0].toInt() and 0xFF
        return when (tag) {
            0 -> InstructionKind.TokenInitializeMint(variant = 0, token2022 = token2022)
            1 -> InstructionKind.TokenInitializeAccount(variant = 0, token2022 = token2022)
            2 -> InstructionKind.TokenOther(tag, token2022) // InitializeMultisig
            3 -> if (has(data, 1, 8)) {
                InstructionKind.TokenTransfer(LittleEndian.u64(data, 1), null, false, token2022)
            } else null

            4 -> if (has(data, 1, 8)) {
                InstructionKind.TokenApprove(LittleEndian.u64(data, 1), null, false, token2022)
            } else null

            5 -> InstructionKind.TokenRevoke(token2022)

            6 -> decodeSetAuthority(data, token2022)

            7 -> if (has(data, 1, 8)) {
                InstructionKind.TokenMintTo(LittleEndian.u64(data, 1), null, false, token2022)
            } else null

            8 -> if (has(data, 1, 8)) {
                InstructionKind.TokenBurn(LittleEndian.u64(data, 1), null, false, token2022)
            } else null

            9 -> InstructionKind.TokenCloseAccount(token2022)
            10 -> InstructionKind.TokenFreeze(thaw = false, token2022 = token2022)
            11 -> InstructionKind.TokenFreeze(thaw = true, token2022 = token2022)

            12 -> if (has(data, 1, 9)) {
                InstructionKind.TokenTransfer(
                    LittleEndian.u64(data, 1), data[9].toInt() and 0xFF, true, token2022
                )
            } else null

            13 -> if (has(data, 1, 9)) {
                InstructionKind.TokenApprove(
                    LittleEndian.u64(data, 1), data[9].toInt() and 0xFF, true, token2022
                )
            } else null

            14 -> if (has(data, 1, 9)) {
                InstructionKind.TokenMintTo(
                    LittleEndian.u64(data, 1), data[9].toInt() and 0xFF, true, token2022
                )
            } else null

            15 -> if (has(data, 1, 9)) {
                InstructionKind.TokenBurn(
                    LittleEndian.u64(data, 1), data[9].toInt() and 0xFF, true, token2022
                )
            } else null

            16 -> InstructionKind.TokenInitializeAccount(variant = 2, token2022 = token2022)
            17 -> InstructionKind.TokenSyncNative(token2022)
            18 -> InstructionKind.TokenInitializeAccount(variant = 3, token2022 = token2022)
            19 -> InstructionKind.TokenOther(tag, token2022) // InitializeMultisig2
            20 -> InstructionKind.TokenInitializeMint(variant = 2, token2022 = token2022)
            21 -> InstructionKind.TokenOther(tag, token2022) // GetAccountDataSize
            22 -> InstructionKind.TokenOther(tag, token2022) // InitializeImmutableOwner
            23 -> InstructionKind.TokenOther(tag, token2022) // AmountToUiAmount
            24 -> InstructionKind.TokenOther(tag, token2022) // UiAmountToAmount

            // Token-2022 only from here down.
            else -> if (token2022) decodeToken2022Extension(tag, data) else null
        }
    }

    /** `SetAuthority { authority_type: u8, new_authority: COption<Pubkey> }` (option is 1 byte). */
    private fun decodeSetAuthority(data: ByteArray, token2022: Boolean): InstructionKind? {
        if (data.size < 3) return null
        val typeTag = data[1].toInt() and 0xFF
        var newAuthority: String? = null
        if (data[2].toInt() and 0xFF == 1) {
            newAuthority = keyAt(data, 3) ?: return null
        }
        return InstructionKind.TokenSetAuthority(
            authorityType = AuthorityType.fromTag(typeTag),
            authorityTypeTag = typeTag,
            newAuthority = newAuthority,
            token2022 = token2022,
        )
    }

    // ------------------------------------------------------- Token-2022 ext

    private val EXTENSION_NAMES = mapOf(
        25 to "InitializeMintCloseAuthority",
        26 to "TransferFeeExtension",
        27 to "ConfidentialTransferExtension",
        28 to "DefaultAccountStateExtension",
        29 to "Reallocate",
        30 to "MemoTransferExtension",
        31 to "CreateNativeMint",
        32 to "InitializeNonTransferableMint",
        33 to "InterestBearingMintExtension",
        34 to "CpiGuardExtension",
        35 to "InitializePermanentDelegate",
        36 to "TransferHookExtension",
        37 to "ConfidentialTransferFeeExtension",
        38 to "WithdrawExcessLamports",
    )

    private fun decodeToken2022Extension(tag: Int, data: ByteArray): InstructionKind? {
        val name = EXTENSION_NAMES[tag] ?: return null
        val sub = if (data.size > 1) data[1].toInt() and 0xFF else null
        return when (tag) {
            25 -> InstructionKind.Token2022Extension(
                extensionTag = tag,
                extensionName = name,
                subInstruction = null,
                detail = "sets a close authority on the mint",
                closeAuthority = if (data.size >= 34 && (data[1].toInt() and 0xFF) == 1)
                    keyAt(data, 2) else null,
            )

            26 -> decodeTransferFeeExtension(tag, name, sub, data)

            28 -> {
                val state = if (sub == 0 && data.size >= 3) DefaultAccountState.fromTag(data[2].toInt() and 0xFF)
                else null
                InstructionKind.Token2022Extension(
                    extensionTag = tag,
                    extensionName = name,
                    subInstruction = sub,
                    detail = when (sub) {
                        0 -> "initialises the default account state for every new holder"
                        1 -> "updates the default account state for every new holder"
                        else -> "default account state extension"
                    },
                    defaultAccountState = state,
                )
            }

            35 -> InstructionKind.Token2022Extension(
                extensionTag = tag,
                extensionName = name,
                subInstruction = null,
                detail = "grants a permanent delegate the right to move or burn any holder's tokens",
                permanentDelegate = keyAt(data, 1),
            )

            36 -> {
                val authority = if (sub == 0) keyAt(data, 2) else null
                val program = when (sub) {
                    0 -> keyAt(data, 34)
                    1 -> keyAt(data, 2)
                    else -> null
                }
                InstructionKind.Token2022Extension(
                    extensionTag = tag,
                    extensionName = name,
                    subInstruction = sub,
                    detail = if (sub == 0) "installs a transfer hook program on the mint"
                    else "updates the transfer hook program on the mint",
                    transferHookAuthority = authority,
                    transferHookProgram = program,
                )
            }

            30 -> InstructionKind.Token2022Extension(
                extensionTag = tag,
                extensionName = name,
                subInstruction = sub,
                detail = if (sub == 0) "requires a memo on every transfer" else "disables the memo requirement",
                memoTransferEnabled = sub == 0,
            )

            34 -> InstructionKind.Token2022Extension(
                extensionTag = tag,
                extensionName = name,
                subInstruction = sub,
                detail = if (sub == 0) "enables the CPI guard on this account" else "disables the CPI guard",
                cpiGuardEnabled = sub == 0,
            )

            33 -> InstructionKind.Token2022Extension(
                extensionTag = tag,
                extensionName = name,
                subInstruction = sub,
                detail = if (sub == 0) "makes the token interest-bearing" else "updates the interest rate",
            )

            else -> InstructionKind.Token2022Extension(
                extensionTag = tag,
                extensionName = name,
                subInstruction = sub,
                detail = "Token-2022 extension instruction",
            )
        }
    }

    private fun decodeTransferFeeExtension(
        tag: Int,
        name: String,
        sub: Int?,
        data: ByteArray,
    ): InstructionKind {
        var basisPoints: Int? = null
        var maximum: Long? = null
        var amount: Long? = null
        var decimals: Int? = null
        var fee: Long? = null
        when (sub) {
            0 -> { // InitializeTransferFeeConfig
                var p = 2
                if (has(data, p, 1)) {
                    val hasConfigAuthority = data[p].toInt() and 0xFF == 1
                    p += 1
                    if (hasConfigAuthority) p += 32
                }
                if (has(data, p, 1)) {
                    val hasWithdrawAuthority = data[p].toInt() and 0xFF == 1
                    p += 1
                    if (hasWithdrawAuthority) p += 32
                }
                if (has(data, p, 2)) basisPoints = LittleEndian.u16(data, p)
                if (has(data, p + 2, 8)) maximum = LittleEndian.u64(data, p + 2)
            }

            1 -> { // TransferCheckedWithFee
                if (has(data, 2, 8)) amount = LittleEndian.u64(data, 2)
                if (has(data, 10, 1)) decimals = data[10].toInt() and 0xFF
                if (has(data, 11, 8)) fee = LittleEndian.u64(data, 11)
            }

            6 -> { // SetTransferFee
                if (has(data, 2, 2)) basisPoints = LittleEndian.u16(data, 2)
                if (has(data, 4, 8)) maximum = LittleEndian.u64(data, 4)
            }
        }
        val detail = when (sub) {
            0 -> "attaches a transfer-fee schedule to the mint"
            1 -> "transfer with a withheld fee"
            2 -> "withdraws withheld fees from the mint"
            3 -> "withdraws withheld fees from holder accounts"
            4 -> "harvests withheld fees into the mint"
            5 -> "harvests withheld fees from holder accounts"
            6 -> "changes the transfer-fee schedule on the mint"
            else -> "transfer-fee extension"
        }
        return InstructionKind.Token2022Extension(
            extensionTag = tag,
            extensionName = name,
            subInstruction = sub,
            detail = detail,
            transferFeeBasisPoints = basisPoints,
            transferFeeMaximum = maximum,
            amount = amount,
            decimals = decimals,
            fee = fee,
        )
    }

    // ------------------------------------------------------------------ ATA

    private fun decodeAssociatedToken(accounts: List<String>, data: ByteArray): InstructionKind? {
        // The original instruction carries no data at all. Version 1.1 of the program added a
        // single-byte discriminator: 0 = Create, 1 = CreateIdempotent. On mainnet both forms are
        // still in use, and the legacy idempotent form is instead recognisable by a 7th account
        // (the program itself), which turns "already exists" into a no-op rather than an error.
        val discriminator = data.firstOrNull()?.let { it.toInt() and 0xFF }
        if (discriminator != null && discriminator > 1) return null
        val idempotent = discriminator == 1 || (data.isEmpty() && accounts.size >= 7)
        return InstructionKind.AtaCreate(
            variant = if (idempotent) "Idempotent" else "",
            idempotent = idempotent,
            accountCount = accounts.size,
        )
    }

    // --------------------------------------------------------- ComputeBudget

    private fun decodeComputeBudget(data: ByteArray): InstructionKind? {
        if (data.isEmpty()) return null
        val tag = data[0].toInt() and 0xFF
        return when (tag) {
            0 -> if (has(data, 1, 8)) {
                InstructionKind.ComputeUnitLimit(LittleEndian.u32(data, 1))
            } else null

            1 -> if (has(data, 1, 4)) InstructionKind.HeapFrame(LittleEndian.u32(data, 1)) else null

            2 -> if (has(data, 1, 4)) {
                InstructionKind.ComputeUnitLimit(LittleEndian.u32(data, 1))
            } else null

            3 -> if (has(data, 1, 8)) {
                InstructionKind.ComputeUnitPrice(LittleEndian.u64(data, 1))
            } else null

            4 -> if (has(data, 1, 4)) {
                InstructionKind.LoadedAccountsDataSizeLimit(LittleEndian.u32(data, 1))
            } else null

            else -> InstructionKind.ComputeBudgetOther(tag)
        }
    }
}

package com.clockin.preflight.engine

/** SPL Token `AuthorityType` (instruction tag 6 / 6+sub). */
enum class AuthorityType(val tag: Int, val label: String) {
    MINT_TOKENS(0, "mint authority"),
    FREEZE_ACCOUNT(1, "freeze authority"),
    ACCOUNT_OWNER(2, "account owner"),
    CLOSE_ACCOUNT(3, "close authority");

    companion object {
        fun fromTag(tag: Int): AuthorityType? = entries.firstOrNull { it.tag == tag }
    }
}

/** SPL Token account state field. */
enum class TokenAccountState(val tag: Int, val label: String) {
    UNINITIALIZED(0, "uninitialized"),
    INITIALIZED(1, "initialized"),
    FROZEN(2, "frozen");

    companion object {
        fun fromTag(tag: Int): TokenAccountState? = entries.firstOrNull { it.tag == tag }
    }
}

/** Token-2022 `DefaultAccountState` extension value. */
enum class DefaultAccountState(val tag: Int, val label: String) {
    UNINITIALIZED(0, "uninitialized"),
    INITIALIZED(1, "initialized"),
    FROZEN(2, "frozen");

    companion object {
        fun fromTag(tag: Int): DefaultAccountState? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * Semantic form of an instruction whose layout this engine understands.
 *
 * Anything not recognised stays `null` on [DecodedInstruction.decoded] rather than being guessed at.
 */
sealed class InstructionKind {

    /** Short technical label, e.g. `"SPL Token TransferChecked"`. */
    abstract val summary: String

    // ---------------------------------------------------------------- System

    data class SystemTransfer(val lamports: Long) : InstructionKind() {
        override val summary: String get() = "System Transfer"
    }

    data class SystemCreateAccount(val lamports: Long, val space: Long, val owner: String?) :
        InstructionKind() {
        override val summary: String get() = "System CreateAccount"
    }

    data class SystemAssign(val owner: String?) : InstructionKind() {
        override val summary: String get() = "System Assign"
    }

    data class SystemAllocate(val space: Long) : InstructionKind() {
        override val summary: String get() = "System Allocate"
    }

    data class SystemTransferWithSeed(val lamports: Long, val seed: String?) : InstructionKind() {
        override val summary: String get() = "System TransferWithSeed"
    }

    data class SystemNonce(val operation: String, val lamports: Long? = null) : InstructionKind() {
        override val summary: String get() = "System $operation"
    }

    // ------------------------------------------------------------ SPL Token

    data class TokenTransfer(
        val amount: Long,
        val decimals: Int?,
        val checked: Boolean,
        val token2022: Boolean,
    ) : InstructionKind() {
        override val summary: String
            get() = if (checked) "Token TransferChecked" else "Token Transfer"
    }

    data class TokenApprove(
        val amount: Long,
        val decimals: Int?,
        val checked: Boolean,
        val token2022: Boolean,
    ) : InstructionKind() {
        override val summary: String
            get() = if (checked) "Token ApproveChecked" else "Token Approve"

        /** `u64::MAX` is the classic "unlimited delegate" drainer primitive. */
        val isUnlimited: Boolean get() = amount == UNLIMITED
    }

    data class TokenRevoke(val token2022: Boolean) : InstructionKind() {
        override val summary: String get() = "Token Revoke"
    }

    data class TokenSetAuthority(
        val authorityType: AuthorityType?,
        val authorityTypeTag: Int,
        val newAuthority: String?,
        val token2022: Boolean,
    ) : InstructionKind() {
        override val summary: String get() = "Token SetAuthority"
    }

    data class TokenCloseAccount(val token2022: Boolean) : InstructionKind() {
        override val summary: String get() = "Token CloseAccount"
    }

    data class TokenBurn(
        val amount: Long,
        val decimals: Int?,
        val checked: Boolean,
        val token2022: Boolean,
    ) : InstructionKind() {
        override val summary: String
            get() = if (checked) "Token BurnChecked" else "Token Burn"
    }

    data class TokenMintTo(
        val amount: Long,
        val decimals: Int?,
        val checked: Boolean,
        val token2022: Boolean,
    ) : InstructionKind() {
        override val summary: String
            get() = if (checked) "Token MintToChecked" else "Token MintTo"
    }

    data class TokenFreeze(val thaw: Boolean, val token2022: Boolean) : InstructionKind() {
        override val summary: String get() = if (thaw) "Token ThawAccount" else "Token FreezeAccount"
    }

    data class TokenInitializeAccount(val variant: Int, val token2022: Boolean) : InstructionKind() {
        override val summary: String get() = "Token InitializeAccount"
    }

    data class TokenInitializeMint(val variant: Int, val token2022: Boolean) : InstructionKind() {
        override val summary: String get() = "Token InitializeMint"
    }

    data class TokenSyncNative(val token2022: Boolean) : InstructionKind() {
        override val summary: String get() = "Token SyncNative"
    }

    /**
     * Token-2022 extension instructions (`TransferFeeExtension`, `TransferHookExtension`, …),
     * identified by their outer tag plus optional sub-instruction.
     */
    data class Token2022Extension(
        val extensionTag: Int,
        val extensionName: String,
        val subInstruction: Int?,
        val detail: String,
        val permanentDelegate: String? = null,
        val transferHookProgram: String? = null,
        val transferHookAuthority: String? = null,
        val closeAuthority: String? = null,
        val defaultAccountState: DefaultAccountState? = null,
        val transferFeeBasisPoints: Int? = null,
        val transferFeeMaximum: Long? = null,
        val amount: Long? = null,
        val decimals: Int? = null,
        val fee: Long? = null,
        val memoTransferEnabled: Boolean? = null,
        val cpiGuardEnabled: Boolean? = null,
    ) : InstructionKind() {
        override val summary: String get() = "Token-2022 $extensionName"
    }

    /** `GetAccountDataSize`, `AmountToUiAmount`, … — recognised but uninteresting. */
    data class TokenOther(val tag: Int, val token2022: Boolean) : InstructionKind() {
        override val summary: String get() = "Token instruction #$tag"
    }

    // ------------------------------------------------------------------ ATA

    data class AtaCreate(
        val variant: String,
        /** `CreateIdempotent` succeeds even when the account already exists. */
        val idempotent: Boolean,
        val accountCount: Int,
    ) : InstructionKind() {
        override val summary: String get() = "CreateAssociatedTokenAccount$variant"
    }

    // --------------------------------------------------------- ComputeBudget

    data class ComputeUnitLimit(val units: Long) : InstructionKind() {
        override val summary: String get() = "ComputeBudget SetComputeUnitLimit"
    }

    data class ComputeUnitPrice(val microLamports: Long) : InstructionKind() {
        override val summary: String get() = "ComputeBudget SetComputeUnitPrice"
    }

    data class HeapFrame(val bytes: Long) : InstructionKind() {
        override val summary: String get() = "ComputeBudget RequestHeapFrame"
    }

    data class LoadedAccountsDataSizeLimit(val bytes: Long) : InstructionKind() {
        override val summary: String get() = "ComputeBudget SetLoadedAccountsDataSizeLimit"
    }

    data class ComputeBudgetOther(val tag: Int) : InstructionKind() {
        override val summary: String get() = "ComputeBudget instruction #$tag"
    }

    // ----------------------------------------------------------------- Memo

    data class Memo(val text: String, val programName: String) : InstructionKind() {
        override val summary: String get() = "$programName: \"$text\""
    }

    // ------------------------------------------------------------ Fallback

    /** Recognised program, unrecognised instruction: kept so rules can still see the raw data. */
    data class UnknownInstruction(val programId: String, val discriminator: Int?) : InstructionKind() {
        override val summary: String get() = "unrecognised instruction"
    }

    companion object {
        /** `u64::MAX` as a signed Long. */
        const val UNLIMITED: Long = -1L

        fun unlimitedAsUnsigned(): String = "18446744073709551615"
    }
}

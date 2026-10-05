package com.clockin.preflight.engine

/** The 3-byte Solana message header. */
data class MessageHeader(
    val requiredSignatures: Int,
    val readonlySignedAccounts: Int,
    val readonlyUnsignedAccounts: Int,
) {
    companion object {
        const val SIZE = 3
    }
}

/** Role of an account key inside a message, derived from the header. */
enum class AccountKind(val isSigner: Boolean, val isWritable: Boolean) {
    SIGNER_WRITABLE(true, true),
    SIGNER_READONLY(true, false),
    NON_SIGNER_WRITABLE(false, true),
    NON_SIGNER_READONLY(false, false),

    /** v0 address-table lookup that can only be resolved with an RPC call. */
    UNRESOLVED_LOOKUP(false, false),
}

/** One account referenced by the message, with its derived signer/writable flags. */
data class AccountMeta(
    val index: Int,
    val pubkey: String,
    val kind: AccountKind,
) {
    val isSigner: Boolean get() = kind.isSigner
    val isWritable: Boolean get() = kind.isWritable
    val isResolved: Boolean get() = kind != AccountKind.UNRESOLVED_LOOKUP
}

/** A v0 address-table lookup entry. */
data class AddressTableLookup(
    val accountKey: String,
    val writableIndexes: List<Int>,
    val readonlyIndexes: List<Int>,
) {
    val totalIndexes: Int get() = writableIndexes.size + readonlyIndexes.size
}

/** A fully decoded instruction: raw fields plus a semantic [decoded] form when recognised. */
data class DecodedInstruction(
    val index: Int,
    val programIdIndex: Int,
    val programId: String,
    val programName: String,
    val accounts: List<String>,
    val accountIndexes: List<Int>,
    val data: ByteArray,
    val decoded: InstructionKind?,
    val discriminator: Int?,
) {
    val dataBase58: String get() = Base58.encode(data)
    val dataHex: String get() = Hex.encode(data)
    val dataLength: Int get() = data.size

    /** True when the instruction decoded into a known shape for its program. */
    val isKnown: Boolean get() = decoded != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedInstruction) return false
        return index == other.index &&
            programIdIndex == other.programIdIndex &&
            programId == other.programId &&
            programName == other.programName &&
            accounts == other.accounts &&
            accountIndexes == other.accountIndexes &&
            data.contentEquals(other.data) &&
            decoded == other.decoded &&
            discriminator == other.discriminator
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + programIdIndex
        result = 31 * result + programId.hashCode()
        result = 31 * result + programName.hashCode()
        result = 31 * result + accounts.hashCode()
        result = 31 * result + accountIndexes.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (decoded?.hashCode() ?: 0)
        result = 31 * result + (discriminator ?: 0)
        return result
    }

    override fun toString(): String =
        "DecodedInstruction(#$index $programName $programId accounts=$accounts data=${dataHex})"
}

/** The decoded message body. */
data class DecodedMessage(
    /** `null` for a legacy message, `0` for v0. */
    val version: Int?,
    val header: MessageHeader,
    /** Static account keys, in message order. */
    val accountKeys: List<String>,
    val recentBlockhash: String,
    val instructions: List<DecodedInstruction>,
    val addressTableLookups: List<AddressTableLookup>,
    /** Signer/writable flags for every index in [accountKeys] plus lookup placeholders. */
    val accountMetas: List<AccountMeta>,
) {
    val isVersioned: Boolean get() = version != null

    fun metaAt(index: Int): AccountMeta? = accountMetas.getOrNull(index)
}

/** A whole decoded transaction: signatures + message. */
data class DecodedTransaction(
    val signatures: List<String>,
    val message: DecodedMessage,
    /** Size of the raw wire blob, for display/diagnostics. */
    val wireBytes: Int,
) {
    val accountKeys: List<String> get() = message.accountKeys
    val instructions: List<DecodedInstruction> get() = message.instructions
    val version: Int? get() = message.version
    val isVersioned: Boolean get() = message.isVersioned

    /** The fee payer — by protocol the first writable signer, i.e. account index 0. */
    val feePayer: String? get() = accountKeys.firstOrNull()

    val signerKeys: List<String>
        get() = message.accountMetas.filter { it.isSigner && it.isResolved }.map { it.pubkey }

    fun accountMetas(indexes: List<Int>): List<AccountMeta> =
        indexes.mapNotNull { message.metaAt(it) }

    override fun toString(): String =
        "DecodedTransaction(v=${version ?: "legacy"} sigs=${signatures.size} " +
            "keys=${accountKeys.size} ixs=${instructions.size} lookups=${message.addressTableLookups.size})"
}

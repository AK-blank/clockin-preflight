package com.clockin.preflight.engine

/** A raw, still-encoded transaction as handed to us by a wallet or an RPC node. */
data class RawTransaction(
    val base64: String,
    /** Optional provenance label for diagnostics, e.g. the source RPC or wallet. */
    val source: String? = null,
) {
    companion object {
        fun of(base64: String): RawTransaction = RawTransaction(base64)
    }
}

/**
 * Hand-rolled decoder for the Solana transaction wire format.
 *
 * Supported layouts:
 *  - **legacy** messages (`header | accountKeys | recentBlockhash | instructions`)
 *  - **v0** versioned messages (`0x80` guard byte, plus `addressTableLookups`)
 *
 * Design rules:
 *  - the public API never throws; every failure comes back as [DecodeError];
 *  - decoding is strict about structure (a blob that is not exactly one transaction is rejected)
 *    but permissive about instruction *bodies*, which are left as raw bytes for the rules layer.
 *
 * Layout reference (all lengths are Solana `shortvec` = compact-u16, little-endian, 7 bits/byte):
 * ```
 * transaction := shortvec(sigCount) sig[64]* | message
 * message     := [0x80|version] header(3) accountKeys recentBlockhash instructions [lookups]
 * header      := requiredSignatures:u8 readonlySigned:u8 readonlyUnsigned:u8
 * accountKeys := shortvec(count) pubkey[32]*
 * instructions:= shortvec(count) { programIdIndex:u8 shortvec(acctCount) u8* shortvec(len) bytes }
 * lookups     := shortvec(count) { accountKey:u8 shortvec(w) u8* shortvec(r) u8* }
 * ```
 */
object TransactionDecoder {

    /** High bit of the first message byte marks a versioned message. */
    const val MESSAGE_VERSION_PREFIX = 0x80

    /** Highest message version this build understands. */
    const val MAX_SUPPORTED_VERSION = 0

    const val SIGNATURE_LENGTH = 64
    const val PUBKEY_LENGTH = 32
    const val BLOCKHASH_LENGTH = 32

    /** Convenience entry point taking the typed [RawTransaction] input model. */
    fun decode(raw: RawTransaction): DecodeResult = decodeBase64(raw.base64)

    /** Decodes a base64 transaction blob. */
    fun decodeBase64(base64: String): DecodeResult {
        if (base64.isBlank()) {
            return DecodeResult.Failure(DecodeError.EmptyInput("the transaction payload is empty"))
        }
        val bytes = Base64Codec.decode(base64)
            ?: return DecodeResult.Failure(
                DecodeError.InvalidBase64("payload is not valid base64 (length ${base64.length})")
            )
        if (bytes.isEmpty()) {
            return DecodeResult.Failure(DecodeError.EmptyInput("the decoded transaction is 0 bytes"))
        }
        return decode(bytes)
    }

    /** Decodes raw transaction bytes. Never throws. */
    fun decode(bytes: ByteArray): DecodeResult {
        return try {
            DecodeResult.Success(decodeOrThrow(bytes))
        } catch (failure: DecodeFailure) {
            DecodeResult.Failure(failure.error)
        } catch (t: Throwable) {
            DecodeResult.Failure(
                DecodeError.Malformed("unexpected ${t::class.simpleName} while decoding: ${t.message}")
            )
        }
    }

    private fun decodeOrThrow(bytes: ByteArray): DecodedTransaction {
        if (bytes.isEmpty()) throw DecodeFailure(DecodeError.EmptyInput())

        val cursor = ByteCursor(bytes)

        val signatureCount = cursor.shortVec("signature")
        if (signatureCount > MAX_PLAUSIBLE_SIGNATURES) {
            throw DecodeFailure(
                DecodeError.ValueOverflow(
                    "signature count $signatureCount is implausible", 0
                )
            )
        }
        val signatures = ArrayList<String>(signatureCount)
        for (i in 0 until signatureCount) {
            val raw = cursor.bytes(SIGNATURE_LENGTH, "signature #$i")
            signatures.add(Base58.encode(raw))
        }

        val message = decodeMessage(cursor)

        if (cursor.remaining != 0) {
            throw DecodeFailure(DecodeError.TrailingBytes(cursor.remaining))
        }

        return DecodedTransaction(
            signatures = signatures,
            message = message,
            wireBytes = bytes.size,
        )
    }

    private fun decodeMessage(cursor: ByteCursor): DecodedMessage {
        var version: Int? = null
        val first = cursor.u8("message version guard")
        if (first and MESSAGE_VERSION_PREFIX != 0) {
            version = first and 0x7F
            if (version > MAX_SUPPORTED_VERSION) {
                throw DecodeFailure(DecodeError.UnsupportedMessageVersion(version))
            }
        } else {
            // Legacy message: the byte we just consumed was the header's first field.
            cursor.position -= 1
        }

        val header = MessageHeader(
            requiredSignatures = cursor.u8("requiredSignatures"),
            readonlySignedAccounts = cursor.u8("readonlySignedAccounts"),
            readonlyUnsignedAccounts = cursor.u8("readonlyUnsignedAccounts"),
        )

        val keyCount = cursor.shortVec("account key")
        val accountKeys = ArrayList<String>(keyCount)
        repeat(keyCount) { accountKeys.add(Base58.encode(cursor.bytes(PUBKEY_LENGTH, "account key"))) }

        validateHeader(header, accountKeys.size)

        val recentBlockhash = Base58.encode(cursor.bytes(BLOCKHASH_LENGTH, "recentBlockhash"))

        val instructionCount = cursor.shortVec("instruction")
        val instructions = ArrayList<DecodedInstruction>(instructionCount)
        for (i in 0 until instructionCount) {
            instructions.add(decodeInstruction(cursor, i, accountKeys))
        }

        val lookups = ArrayList<AddressTableLookup>()
        if (version != null) {
            val lookupCount = cursor.shortVec("address table lookup")
            repeat(lookupCount) {
                val keyIndex = cursor.u8("lookup account key index")
                val tableKey = accountKeys.getOrNull(keyIndex) ?: "<lookup-key-$keyIndex>"
                val writableCount = cursor.shortVec("writable index")
                val writable = ArrayList<Int>(writableCount)
                repeat(writableCount) { writable.add(cursor.u8("writable index")) }
                val readonlyCount = cursor.shortVec("readonly index")
                val readonly = ArrayList<Int>(readonlyCount)
                repeat(readonlyCount) { readonly.add(cursor.u8("readonly index")) }
                lookups.add(AddressTableLookup(tableKey, writable, readonly))
            }
        }

        val metas = buildAccountMetas(header, accountKeys, lookups)

        return DecodedMessage(
            version = version,
            header = header,
            accountKeys = accountKeys,
            recentBlockhash = recentBlockhash,
            instructions = instructions,
            addressTableLookups = lookups,
            accountMetas = metas,
        )
    }

    private fun decodeInstruction(
        cursor: ByteCursor,
        index: Int,
        accountKeys: List<String>,
    ): DecodedInstruction {
        val programIdIndex = cursor.u8("programIdIndex")
        val accountCount = cursor.shortVec("instruction account")
        val accountIndexes = ArrayList<Int>(accountCount)
        repeat(accountCount) { accountIndexes.add(cursor.u8("instruction account index")) }
        val dataLength = cursor.shortVec("instruction data")
        val data = cursor.bytes(dataLength, "instruction data")

        val programId = accountKeys.getOrNull(programIdIndex)
            ?: "<program-index-$programIdIndex>"

        val accounts = accountIndexes.map { accountKeys.getOrNull(it) ?: "<account-index-$it>" }

        val decoded = InstructionDecoder.decode(programId, accounts, data)
        val discriminator = when {
            decoded != null -> null
            data.isEmpty() -> null
            ProgramRegistry.SYSTEM == programId && data.size >= 4 ->
                LittleEndian.u32(data, 0).toInt()
            else -> data[0].toInt() and 0xFF
        }

        return DecodedInstruction(
            index = index,
            programIdIndex = programIdIndex,
            programId = programId,
            programName = ProgramRegistry.name(programId),
            accounts = accounts,
            accountIndexes = accountIndexes,
            data = data,
            decoded = decoded,
            discriminator = discriminator,
        )
    }

    /**
     * Applies the header's signer/writable rules. Signer keys come first; within each half the
     * readonly keys come last.
     */
    private fun buildAccountMetas(
        header: MessageHeader,
        accountKeys: List<String>,
        lookups: List<AddressTableLookup>,
    ): List<AccountMeta> {
        val metas = ArrayList<AccountMeta>(accountKeys.size)
        val signerWritable = header.requiredSignatures - header.readonlySignedAccounts
        val nonSignerWritable =
            accountKeys.size - header.requiredSignatures - header.readonlyUnsignedAccounts

        accountKeys.forEachIndexed { index, key ->
            val kind = if (index < header.requiredSignatures) {
                if (index < signerWritable) AccountKind.SIGNER_WRITABLE else AccountKind.SIGNER_READONLY
            } else {
                val relative = index - header.requiredSignatures
                if (relative < nonSignerWritable) AccountKind.NON_SIGNER_WRITABLE
                else AccountKind.NON_SIGNER_READONLY
            }
            metas.add(AccountMeta(index, key, kind))
        }

        lookups.forEachIndexed { tableIndex, lookup ->
            lookup.writableIndexes.forEach { idx ->
                metas.add(
                    AccountMeta(
                        index = metas.size,
                        pubkey = "<lookup:$tableIndex:w:$idx>",
                        kind = AccountKind.UNRESOLVED_LOOKUP,
                    )
                )
            }
            lookup.readonlyIndexes.forEach { idx ->
                metas.add(
                    AccountMeta(
                        index = metas.size,
                        pubkey = "<lookup:$tableIndex:r:$idx>",
                        kind = AccountKind.UNRESOLVED_LOOKUP,
                    )
                )
            }
        }
        return metas
    }

    private fun validateHeader(header: MessageHeader, keyCount: Int) {
        if (header.requiredSignatures > keyCount) {
            throw DecodeFailure(
                DecodeError.Malformed(
                    "header requires ${header.requiredSignatures} signers but only $keyCount " +
                        "account keys are present"
                )
            )
        }
        if (header.readonlySignedAccounts > header.requiredSignatures) {
            throw DecodeFailure(
                DecodeError.Malformed(
                    "header marks ${header.readonlySignedAccounts} readonly signers but only " +
                        "${header.requiredSignatures} signers exist"
                )
            )
        }
        val nonSigners = keyCount - header.requiredSignatures
        if (header.readonlyUnsignedAccounts > nonSigners) {
            throw DecodeFailure(
                DecodeError.Malformed(
                    "header marks ${header.readonlyUnsignedAccounts} readonly unsigned accounts " +
                        "but only $nonSigners unsigned keys exist"
                )
            )
        }
    }

    /** Hard cap so a corrupt shortvec cannot make us allocate gigabytes. */
    private const val MAX_PLAUSIBLE_SIGNATURES = 64
}

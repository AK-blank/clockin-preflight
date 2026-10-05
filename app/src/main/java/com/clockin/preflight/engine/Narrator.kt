package com.clockin.preflight.engine

/**
 * Turns addresses, mints and raw amounts into the words that end up on screen.
 *
 * This class is the reason the demo sentence reads like English: it knows that `7xKq…9fQ` is "your
 * account", that a mint with 6 decimals and the symbol USDC means `1,000,000 USDC`, and that wallet
 * with no entries in [RiskContext.counterpartyTxCount] has "no prior history".
 */
class Describer(
    private val transaction: DecodedTransaction,
    private val context: RiskContext,
) {

    /** `your account`, a caller-supplied label, or a shortened pubkey. */
    fun label(address: String?): String {
        if (address == null) return "an unknown account"
        val user = context.userAddress
        val owner = context.accountOwners[address]
        if (user != null && (address == user || owner == user)) return "your account"
        val short = ProgramRegistry.shortId(address)
        return if (owner != null && owner != address) {
            "$short (a token account owned by ${ProgramRegistry.shortId(owner)})"
        } else short
    }

    /** Just the short form, for in-sentence references. */
    fun short(address: String?): String =
        address?.let { ProgramRegistry.shortId(it) } ?: "an unknown address"

    /** `USDC`, or `tokens` when we have no metadata for the mint. */
    fun tokenSymbol(mint: String?): String? =
        mint?.let { context.tokenMetadata[it]?.symbol ?: context.tokenMetadata[it]?.name }

    fun tokenUnit(mint: String?): String = tokenSymbol(mint) ?: "tokens"

    fun decimals(mint: String?): Int? = context.decimalsFor(mint)

    fun tokenAmount(raw: Long, mint: String?): String =
        AmountFormat.token(raw, decimals(mint), tokenSymbol(mint))

    fun lamports(raw: Long): String = AmountFormat.lamports(raw)

    /** The trailing clause the demo sentence is built around. */
    fun counterpartyClause(address: String?): String {
        if (address == null) return ""
        val count = context.counterpartyTxCount[address] ?: return ""
        return when {
            count <= 0 -> ", a wallet with no prior history"
            count == 1 -> ", a wallet with a single previous transaction"
            count < 10 -> ", a wallet with $count previous transactions"
            count < 1000 -> ", a wallet with $count transactions"
            else -> ", a wallet with ${AmountFormat.group(count.toString())} transactions"
        }
    }

    /** `1,000,000 USDC` plus a balance-relative parenthetical, when we know the holding. */
    fun amountWithHolding(raw: Long, mint: String?, holding: Long?): String {
        val amount = tokenAmount(raw, mint)
        if (holding == null || holding <= 0L) return amount
        return when {
            raw == holding -> "$amount (all of it)"
            raw > holding -> "$amount, which is more than the ${
                tokenAmount(holding, mint)
            } the account actually holds"
            else -> "$amount (${AmountFormat.percentOf(raw, holding)} of the ${
                tokenAmount(holding, mint)
            } in the account)"
        }
    }

    fun holdingOf(vararg keys: String?): Long? = context.holdingFor(*keys)
}

/** One decoded action, already phrased for a human. */
data class NarrativeItem(
    val instructionIndex: Int,
    val sentence: String,
    val severity: Severity,
    /** True for housekeeping instructions (compute budget, memo, ATA setup). */
    val isNoise: Boolean,
)

/**
 * Builds the single paragraph the pre-flight screen shows above the Confirm button.
 *
 * Shape: `<what it does>. <why we care>.` — the first sentence always names the amount, the asset
 * and the counterparty; the second is the highest-severity finding, or an all-clear.
 */
object Narrator {

    /** Instructions that never deserve their own sentence. */
    private fun isNoise(instruction: DecodedInstruction): Boolean = when (instruction.decoded) {
        is InstructionKind.ComputeUnitLimit,
        is InstructionKind.ComputeUnitPrice,
        is InstructionKind.HeapFrame,
        is InstructionKind.LoadedAccountsDataSizeLimit,
        is InstructionKind.ComputeBudgetOther,
        is InstructionKind.Memo,
        -> true

        else -> false
    }

    fun actions(
        transaction: DecodedTransaction,
        context: RiskContext,
    ): List<NarrativeItem> {
        val describer = Describer(transaction, context)
        return transaction.instructions.map { instruction ->
            NarrativeItem(
                instructionIndex = instruction.index,
                sentence = describe(instruction, describer, context),
                severity = Severity.INFO,
                isNoise = isNoise(instruction),
            )
        }
    }

    /** The one-line "what does this do" summary, ignoring housekeeping instructions. */
    fun summarize(transaction: DecodedTransaction, context: RiskContext): String {
        val items = actions(transaction, context)
        val primary = transaction.instructions
            .filter { !isNoise(it) && items.getOrNull(it.index)?.sentence?.isNotBlank() == true }
            .minWithOrNull(
                compareBy<DecodedInstruction> { actionPriority(it) }.thenBy { it.index }
            )
        return primary?.let { items[it.index].sentence }
            ?: items.firstOrNull { it.sentence.isNotBlank() }?.sentence
            ?: "This transaction contains no instructions this build understands."
    }

    /**
     * Ranks instructions by how much they tell the user about the transaction.
     *
     * Funds movements come first: if a transaction both moves 1,000,000 USDC and calls an unknown
     * program, the sentence has to lead with the movement, not with the program call.
     */
    private fun actionPriority(instruction: DecodedInstruction): Int = when (instruction.decoded) {
        is InstructionKind.TokenTransfer,
        is InstructionKind.SystemTransfer,
        is InstructionKind.SystemTransferWithSeed,
        is InstructionKind.TokenApprove,
        is InstructionKind.TokenSetAuthority,
        is InstructionKind.TokenCloseAccount,
        is InstructionKind.TokenBurn,
        is InstructionKind.TokenMintTo,
        is InstructionKind.Token2022Extension,
        is InstructionKind.SystemAssign,
        -> 0

        null, is InstructionKind.UnknownInstruction ->
            if (ProgramRegistry.trust(instruction.programId) == ProgramTrust.UNKNOWN) 1 else 3

        is InstructionKind.TokenRevoke,
        is InstructionKind.TokenFreeze,
        is InstructionKind.SystemCreateAccount,
        is InstructionKind.TokenInitializeAccount,
        is InstructionKind.TokenInitializeMint,
        is InstructionKind.SystemNonce,
        is InstructionKind.SystemAllocate,
        is InstructionKind.TokenSyncNative,
        is InstructionKind.TokenOther,
        -> 2

        is InstructionKind.AtaCreate -> 4

        else -> 5
    }

    /** The demo paragraph: what it does, then why it matters. */
    fun narrate(
        transaction: DecodedTransaction,
        verdict: Verdict,
        context: RiskContext,
    ): String {
        val lead = summarize(transaction, context)
        val why = when (verdict.level) {
            VerdictLevel.SAFE -> verdict.findings.firstOrNull { it.severity == Severity.INFO }
                ?.plainEnglish
                ?: "Nothing in it can move your funds without another signature from you."
            VerdictLevel.CAUTION, VerdictLevel.DANGER ->
                verdict.topFinding?.plainEnglish
                    ?: "We could not verify this transaction."
        }
        // The lead sentence ("what it does") and the top finding's sentence often overlap — a
        // finding like "This gives X unlimited permission…" restates the lead. Showing both reads
        // as a stutter above the Confirm button, so when one contains the other we keep the fuller
        // sentence and append only what the other adds.
        if (why.isBlank()) return lead
        if (lead.isBlank()) return why
        // The lead ("what it does") and the top finding's sentence usually cover the same ground in
        // a different clause order, so substring matching is not enough. If most of the finding's
        // significant words already appear in the lead, the reader has just been told this — showing
        // it again reads as a stutter directly above the Confirm button. Keep the lead, which is the
        // sentence that names the amount and the counterparty.
        if (overlap(lead, why) >= REPEAT_THRESHOLD) return lead
        return "$lead $why"
    }

    /** Fraction of [candidate]'s significant words that already appear in [reference]. */
    private fun overlap(reference: String, candidate: String): Double {
        val referenceWords = significantWords(reference)
        val candidateWords = significantWords(candidate)
        if (candidateWords.isEmpty()) return 1.0
        val shared = candidateWords.count { it in referenceWords }
        return shared.toDouble() / candidateWords.size
    }

    private fun significantWords(text: String): Set<String> =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toSet()

    /** Above this share of repeated words the finding is treated as already stated. */
    private const val REPEAT_THRESHOLD = 0.65

    private val STOP_WORDS = setOf(
        "the", "and", "you", "your", "this", "that", "with", "from", "they", "them", "can", "for",
        "not", "but", "any", "all", "out", "into", "are", "was", "were", "has", "have", "had",
        "will", "would", "about", "than", "then", "when", "what", "which", "who", "why", "how",
    )
    /** Phrase exactly one instruction. */
    fun describe(
        instruction: DecodedInstruction,
        describer: Describer,
        context: RiskContext,
    ): String = when (val kind = instruction.decoded) {

        is InstructionKind.SystemTransfer -> {
            val destination = instruction.accounts.getOrNull(1)
            val holding = describer.holdingOf(context.userAddress)
            "This transaction sends ${
                describer.amountWithHolding(kind.lamports, null, holding)
            } from your account to ${describer.short(destination)}${
                describer.counterpartyClause(destination)
            }."
        }

        is InstructionKind.SystemCreateAccount ->
            "This transaction creates a new account owned by ${describer.short(kind.owner)}."

        is InstructionKind.SystemAssign ->
            "This gives ownership of ${describer.short(instruction.accounts.getOrNull(0))} to " +
                "${describer.short(kind.owner)}."

        is InstructionKind.SystemNonce ->
            "This transaction uses an offline (durable nonce) signing flow."

        is InstructionKind.SystemAllocate ->
            "This transaction reserves space in ${describer.short(instruction.accounts.getOrNull(0))}."

        is InstructionKind.SystemTransferWithSeed ->
            "This transaction moves ${describer.lamports(kind.lamports)} out of an account derived " +
                "from a seed string."

        is InstructionKind.TokenTransfer -> tokenTransferSentence(kind, instruction, describer, context)

        is InstructionKind.TokenApprove -> {
            val tokenAccount = instruction.accounts.getOrNull(0)
            val mint = mintOfApproval(kind, instruction, context)
            val delegate = delegateOfApproval(kind, instruction)
            val holding = describer.holdingOf(tokenAccount, mint)
            if (kind.isUnlimited) {
                "This gives ${describer.short(delegate)} unlimited permission to move " +
                    "${describer.tokenUnit(mint)} out of your account — at any time, without asking " +
                    "you again."
            } else {
                "This lets ${describer.short(delegate)} move up to ${
                    describer.amountWithHolding(kind.amount, mint, holding)
                } from your account without asking you again."
            }
        }

        is InstructionKind.TokenRevoke ->
            "This cancels a previously granted delegate on your token account."

        is InstructionKind.TokenSetAuthority ->
            setAuthoritySentence(kind, instruction, describer)

        is InstructionKind.TokenCloseAccount -> closeAccountSentence(instruction, describer, context)

        is InstructionKind.TokenBurn ->
            "This burns ${describer.tokenAmount(kind.amount, instruction.accounts.getOrNull(1))}."

        is InstructionKind.TokenMintTo ->
            "This mints ${describer.tokenAmount(kind.amount, instruction.accounts.getOrNull(0))} " +
                "into ${describer.short(instruction.accounts.getOrNull(1))}."

        is InstructionKind.TokenFreeze ->
            if (kind.thaw) "This unfreezes token account ${describer.short(instruction.accounts.getOrNull(0))}."
            else "This freezes token account ${describer.short(instruction.accounts.getOrNull(0))}."

        is InstructionKind.TokenInitializeAccount ->
            "This sets up a new token account."

        is InstructionKind.TokenInitializeMint ->
            "This creates a new token mint."

        is InstructionKind.TokenSyncNative ->
            "This syncs the balance of a wrapped-SOL account."

        is InstructionKind.Token2022Extension ->
            "This configures a Token-2022 extension on ${describer.short(instruction.accounts.getOrNull(0))}: ${kind.detail}."

        is InstructionKind.TokenOther ->
            "This calls an SPL Token instruction (#${kind.tag}) with no funds-moving effect we recognise."

        is InstructionKind.AtaCreate ->
            "This creates the token account that will hold the asset."

        is InstructionKind.ComputeUnitLimit,
        is InstructionKind.ComputeUnitPrice,
        is InstructionKind.HeapFrame,
        is InstructionKind.LoadedAccountsDataSizeLimit,
        is InstructionKind.ComputeBudgetOther,
        -> ""

        is InstructionKind.Memo ->
            "The transaction carries a memo: \"${kind.text}\"."

        is InstructionKind.UnknownInstruction, null -> {
            if (ProgramRegistry.trust(instruction.programId) == ProgramTrust.UNKNOWN) {
                "This transaction asks ${describer.short(instruction.programId)}, a program we do " +
                    "not recognise, to run against your accounts."
            } else {
                "This calls ${instruction.programName} (instruction #${instruction.discriminator ?: -1})."
            }
        }
    }

    // ------------------------------------------------------------- fragments

    private fun tokenTransferSentence(
        kind: InstructionKind.TokenTransfer,
        instruction: DecodedInstruction,
        describer: Describer,
        context: RiskContext,
    ): String {
        val source = instruction.accounts.getOrNull(0)
        val mint = mintOfTransfer(kind, instruction, context)
        val destination = if (kind.checked) instruction.accounts.getOrNull(2)
        else instruction.accounts.getOrNull(1)
        val holding = describer.holdingOf(source, mint)
        val amount = describer.amountWithHolding(kind.amount, mint, holding)
        return "This transaction moves $amount out of your account to " +
            "${describer.short(destination)}${describer.counterpartyClause(destination)}."
    }

    /** Resolves the mint of a token transfer: checked variants carry it, legacy ones need context. */
    fun mintOfTransfer(
        kind: InstructionKind.TokenTransfer,
        instruction: DecodedInstruction,
        context: RiskContext,
    ): String? {
        if (kind.checked) return instruction.accounts.getOrNull(1)
        val source = instruction.accounts.firstOrNull()
        val account = context.sourceTokenAccount
        return if (account != null && (account.address == null || account.address == source)) {
            account.mint
        } else null
    }

    fun mintOfApproval(
        kind: InstructionKind.TokenApprove,
        instruction: DecodedInstruction,
        context: RiskContext,
    ): String? {
        if (kind.checked) return instruction.accounts.getOrNull(1)
        val source = instruction.accounts.firstOrNull()
        val account = context.sourceTokenAccount
        return if (account != null && (account.address == null || account.address == source)) {
            account.mint
        } else null
    }

    fun delegateOfApproval(
        kind: InstructionKind.TokenApprove,
        instruction: DecodedInstruction,
    ): String? = if (kind.checked) instruction.accounts.getOrNull(2)
    else instruction.accounts.getOrNull(1)

    private fun setAuthoritySentence(
        kind: InstructionKind.TokenSetAuthority,
        instruction: DecodedInstruction,
        describer: Describer,
    ): String {
        val owned = describer.label(instruction.accounts.getOrNull(0))
        val target = kind.newAuthority
        val what = kind.authorityType?.label ?: "authority (type ${kind.authorityTypeTag})"
        return if (target == null) {
            "This permanently gives up the $what over $owned — nobody will hold it afterwards."
        } else {
            "This hands the $what over $owned to ${describer.short(target)}${
                describer.counterpartyClause(target)
            }."
        }
    }

    private fun closeAccountSentence(
        instruction: DecodedInstruction,
        describer: Describer,
        context: RiskContext,
    ): String {
        val account = instruction.accounts.getOrNull(0)
        val destination = instruction.accounts.getOrNull(1)
        val mint = context.sourceTokenAccount
            ?.takeIf { it.address == null || it.address == account }?.mint
        val unit = describer.tokenUnit(mint)
        val leftover = context.sourceTokenAccount
            ?.takeIf { it.address == null || it.address == account }?.amount ?: 0L
        val holding = if (leftover > 0L) {
            " It also destroys the ${describer.tokenAmount(leftover, mint)} still in the account."
        } else ""
        val toYourself = context.userAddress != null && destination == context.userAddress
        val target = if (toYourself) "back to your wallet" else "to ${describer.short(destination)}"
        return "This closes your $unit token account and sends its rent back $target.$holding"
    }
}

package com.clockin.preflight.engine

/**
 * The deterministic risk rule engine.
 *
 * Rules are pure functions of the decoded transaction plus an optional [RiskContext]. Nothing here
 * consults the network, the clock or a random source: the same input always yields the same verdict,
 * which is what makes the "why" explainable and the tests hermetic.
 *
 * Scoring: every finding carries a [Severity] weight (INFO 0, LOW 4, MEDIUM 20, HIGH 40,
 * CRITICAL 70). Weights are summed and capped at 100. The level is DANGER when anything is
 * CRITICAL or the score reaches 70, CAUTION from 20, otherwise SAFE.
 */
object RiskAnalyzer {

    fun analyze(
        transaction: DecodedTransaction,
        context: RiskContext = RiskContext.EMPTY,
    ): Verdict = Analysis(transaction, context).run()
}

/** One pass of every rule over one transaction. */
internal class Analysis(
    private val tx: DecodedTransaction,
    private val context: RiskContext,
) {

    private val findings = ArrayList<RiskFinding>()
    private val describer = Describer(tx, context)

    fun run(): Verdict {
        checkSignatureCount()
        checkLookups()
        checkPrograms()
        checkUserVisibleText()
        tx.instructions.forEach { checkInstruction(it) }
        checkMintExtensions()
        checkSourceAccount()
        checkDrainerComposite()
        return build()
    }

    private fun add(
        severity: Severity,
        code: String,
        title: String,
        plainEnglish: String,
        instructionIndex: Int? = null,
        addresses: List<String> = emptyList(),
        evidence: Map<String, String> = emptyMap(),
    ) {
        findings.add(
            RiskFinding(
                severity = severity,
                code = code,
                title = title,
                plainEnglish = plainEnglish,
                evidence = evidence,
                instructionIndex = instructionIndex,
                addresses = addresses,
            )
        )
    }

    // ------------------------------------------------------------- structure

    private fun checkSignatureCount() {
        val required = tx.message.header.requiredSignatures
        val actual = tx.signatures.size
        if (actual == required) return
        val tooMany = actual > required
        add(
            severity = if (tooMany) Severity.HIGH else Severity.LOW,
            code = "SIGNATURE_COUNT_MISMATCH",
            title = if (tooMany) "More signatures than declared signers" else "Partially signed",
            plainEnglish = if (tooMany) {
                "This transaction carries $actual signatures but its message only declares " +
                    "$required signer${plural(required)}. Signature data that does not belong to the " +
                    "message is a sign the transaction was hand-assembled."
            } else {
                "This transaction is only partially signed ($actual of $required signatures); " +
                    "your wallet will add the rest."
            },
            evidence = mapOf(
                "signatures" to actual.toString(),
                "requiredSigners" to required.toString(),
            ),
        )
    }

    private fun checkLookups() {
        val unresolved = tx.message.accountMetas.count { !it.isResolved }
        if (unresolved == 0) return
        add(
            severity = Severity.LOW,
            code = "UNRESOLVED_LOOKUP_ACCOUNTS",
            title = "Accounts hidden behind lookup tables",
            plainEnglish = "This transaction pulls $unresolved account${plural(unresolved)} out of " +
                "address lookup tables, so not every counterparty could be checked offline.",
            evidence = mapOf("unresolvedAccounts" to unresolved.toString()),
        )
    }

    private fun checkPrograms() {
        val seen = HashMap<String, Int>()
        for (instruction in tx.instructions) {
            val programId = instruction.programId
            if (context.trustedPrograms.contains(programId)) continue
            if (seen.containsKey(programId)) continue
            seen[programId] = instruction.index
            val info = ProgramRegistry.lookup(programId)
            when (info.trust) {
                ProgramTrust.CORE -> Unit

                ProgramTrust.WELL_KNOWN -> add(
                    severity = Severity.LOW,
                    code = "UNVERIFIED_PROGRAM",
                    title = "Third-party program",
                    plainEnglish = "Instruction #${instruction.index + 1} calls ${info.name}, a " +
                        "well-known third-party program. We recognise it, but we have not audited " +
                        "what it does with your accounts.",
                    instructionIndex = instruction.index,
                    addresses = listOf(programId),
                    evidence = mapOf("program" to info.name, "programId" to programId),
                )

                ProgramTrust.UNKNOWN -> add(
                    severity = Severity.MEDIUM,
                    code = "UNKNOWN_PROGRAM",
                    title = "Unrecognised program",
                    plainEnglish = "Instruction #${instruction.index + 1} asks " +
                        "${describer.short(programId)} — a program we do not recognise — to run " +
                        "${accessClause(instruction)}. We cannot verify what it will do.",
                    instructionIndex = instruction.index,
                    addresses = listOf(programId),
                    evidence = mapOf("programId" to programId),
                )
            }
        }
    }

    /** Describes how much of the user's stuff an unknown program is being handed. */
    private fun accessClause(instruction: DecodedInstruction): String {
        val metas = tx.accountMetas(instruction.accountIndexes)
        val userWritable = context.userAddress?.let { user ->
            metas.any { it.isResolved && it.isWritable && it.pubkey == user }
        } ?: false
        val writable = metas.count { it.isResolved && it.isWritable }
        return when {
            userWritable -> "with write access to your account"
            writable > 0 -> "against $writable of the accounts in this transaction"
            else -> "against ${instruction.accounts.size} read-only account${plural(instruction.accounts.size)}"
        }
    }

    // -------------------------------------------------------- user-facing text

    private fun checkUserVisibleText() {
        for (instruction in tx.instructions) {
            val text = when (val kind = instruction.decoded) {
                is InstructionKind.Memo -> kind.text
                null -> if (ScamLureDetector.looksLikeText(instruction.data)) {
                    String(instruction.data, Charsets.UTF_8)
                } else null

                else -> null
            } ?: continue

            val match = ScamLureDetector.scan(text) ?: continue
            val isMemo = instruction.decoded is InstructionKind.Memo
            // A bare URL in opaque program data is normal (NFT metadata, IPFS links); only the
            // user-visible memo channel is worth warning about on a URL alone.
            if (match.isBareUrl && !isMemo) continue
            val where = if (isMemo) {
                "The transaction's memo"
            } else {
                "The data attached to instruction #${instruction.index + 1}"
            }
            when (match.level) {
                ScamLureDetector.LureLevel.HARD -> add(
                    severity = Severity.HIGH,
                    code = "SCAM_LURE_TEXT",
                    title = "Text asking for your keys",
                    plainEnglish = "$where says \u201c${match.excerpt}\u201d. Text like " +
                        "\u201c${match.phrase}\u201d only ever appears in attempts to steal a wallet.",
                    instructionIndex = instruction.index,
                    evidence = mapOf("matchedPhrase" to match.phrase, "text" to match.excerpt),
                )

                ScamLureDetector.LureLevel.SOFT -> add(
                    severity = Severity.MEDIUM,
                    code = "SCAM_LURE_LANGUAGE",
                    title = "Marketing text in a transaction",
                    plainEnglish = "$where says \u201c${match.excerpt}\u201d — " +
                        "the vocabulary of fake airdrops and claim pages. Real transfers do not " +
                        "need to advertise.",
                    instructionIndex = instruction.index,
                    evidence = mapOf("matchedPhrase" to match.phrase, "text" to match.excerpt),
                )
            }
        }
    }

    // ------------------------------------------------------------ per instruction

    private fun checkInstruction(instruction: DecodedInstruction) {
        when (val kind = instruction.decoded) {
            is InstructionKind.SystemTransfer ->
                checkLamportTransfer(kind, instruction)

            is InstructionKind.SystemAssign -> add(
                severity = Severity.HIGH,
                code = "SYSTEM_ASSIGN",
                title = "Account ownership handed over",
                plainEnglish = "This gives ownership of " +
                    "${describer.short(instruction.accounts.getOrNull(0))} to " +
                    "${describer.short(kind.owner)}. Whoever owns an account can change anything " +
                    "inside it.",
                instructionIndex = instruction.index,
                addresses = listOfNotNull(instruction.accounts.getOrNull(0), kind.owner),
            )

            is InstructionKind.TokenTransfer -> checkTokenTransfer(kind, instruction)

            is InstructionKind.TokenApprove -> checkApproval(kind, instruction)

            is InstructionKind.TokenSetAuthority -> checkSetAuthority(kind, instruction)

            is InstructionKind.TokenCloseAccount -> checkCloseAccount(instruction)

            is InstructionKind.Token2022Extension -> checkExtensionInstruction(kind, instruction)

            is InstructionKind.TokenBurn -> add(
                severity = Severity.INFO,
                code = "TOKEN_BURN",
                title = "Tokens burned",
                plainEnglish = "This permanently destroys " +
                    "${describer.tokenAmount(kind.amount, instruction.accounts.getOrNull(1))}.",
                instructionIndex = instruction.index,
            )

            is InstructionKind.TokenRevoke -> add(
                severity = Severity.INFO,
                code = "TOKEN_REVOKE",
                title = "Delegate revoked",
                plainEnglish = "This revokes a delegate's permission on your token account — that " +
                    "is a safety improvement, not a risk.",
                instructionIndex = instruction.index,
            )

            else -> Unit
        }
    }

    private fun checkLamportTransfer(
        kind: InstructionKind.SystemTransfer,
        instruction: DecodedInstruction,
    ) {
        val user = context.userAddress ?: return
        val holding = context.holdingFor(user) ?: return
        val destination = instruction.accounts.getOrNull(1)
        val clause = describer.counterpartyClause(destination)
        when {
            kind.lamports == holding -> add(
                severity = Severity.HIGH,
                code = "TRANSFER_FULL_BALANCE",
                title = "Your entire SOL balance",
                plainEnglish = "This transaction moves all ${describer.lamports(kind.lamports)} " +
                    "out of your account to ${describer.short(destination)}$clause, leaving you " +
                    "with nothing to pay fees with.",
                instructionIndex = instruction.index,
                addresses = listOfNotNull(destination),
                evidence = mapOf("amount" to kind.lamports.toString(), "holding" to holding.toString()),
            )

            kind.lamports > holding -> add(
                severity = Severity.CRITICAL,
                code = "TRANSFER_EXCEEDS_HOLDING",
                title = "More than you hold",
                plainEnglish = "This asks to move ${describer.lamports(kind.lamports)} out of an " +
                    "account that only holds ${describer.lamports(holding)}. A transfer larger than " +
                    "your balance is a strong sign the request was tampered with.",
                instructionIndex = instruction.index,
                evidence = mapOf("amount" to kind.lamports.toString(), "holding" to holding.toString()),
            )

            kind.lamports.toDouble() >= holding.toDouble() * 0.9 -> add(
                severity = Severity.MEDIUM,
                code = "TRANSFER_NEARLY_ALL",
                title = "Almost your whole balance",
                plainEnglish = "This moves ${describer.lamports(kind.lamports)} — " +
                    "${AmountFormat.percentOf(kind.lamports, holding)} of your SOL — to " +
                    "${describer.short(destination)}$clause.",
                instructionIndex = instruction.index,
                evidence = mapOf("amount" to kind.lamports.toString(), "holding" to holding.toString()),
            )
        }
    }

    private fun checkTokenTransfer(
        kind: InstructionKind.TokenTransfer,
        instruction: DecodedInstruction,
    ) {
        val source = instruction.accounts.getOrNull(0)
        val mint = Narrator.mintOfTransfer(kind, instruction, context)
        val destination = if (kind.checked) instruction.accounts.getOrNull(2)
        else instruction.accounts.getOrNull(1)
        val holding = describer.holdingOf(source, mint)
        val amountText = describer.tokenAmount(kind.amount, mint)
        val clause = describer.counterpartyClause(destination)

        if (holding != null && holding > 0L) {
            when {
                kind.amount > holding -> add(
                    severity = Severity.CRITICAL,
                    code = "TRANSFER_EXCEEDS_HOLDING",
                    title = "More than you hold",
                    plainEnglish = "This asks to move $amountText out of an account that only holds " +
                        "${describer.tokenAmount(holding, mint)}. A transfer larger than your " +
                        "balance is a strong sign the request was tampered with.",
                    instructionIndex = instruction.index,
                    addresses = listOfNotNull(destination),
                    evidence = mapOf(
                        "amount" to kind.amount.toString(),
                        "holding" to holding.toString(),
                        "mint" to (mint ?: "unknown"),
                    ),
                )

                kind.amount == holding -> add(
                    severity = Severity.HIGH,
                    code = "TRANSFER_FULL_BALANCE",
                    title = "All of it",
                    plainEnglish = "This transaction moves $amountText (all of it) out of your " +
                        "account to ${describer.short(destination)}$clause.",
                    instructionIndex = instruction.index,
                    addresses = listOfNotNull(destination),
                    evidence = mapOf(
                        "amount" to kind.amount.toString(),
                        "holding" to holding.toString(),
                        "mint" to (mint ?: "unknown"),
                    ),
                )

                kind.amount.toDouble() >= holding.toDouble() * 0.9 -> add(
                    severity = Severity.MEDIUM,
                    code = "TRANSFER_NEARLY_ALL",
                    title = "Almost everything",
                    plainEnglish = "This moves $amountText — " +
                        "${AmountFormat.percentOf(kind.amount, holding)} of the " +
                        "${describer.tokenAmount(holding, mint)} in your account — to " +
                        "${describer.short(destination)}$clause.",
                    instructionIndex = instruction.index,
                    addresses = listOfNotNull(destination),
                    evidence = mapOf(
                        "amount" to kind.amount.toString(),
                        "holding" to holding.toString(),
                    ),
                )
            }
        }

        val prior = context.counterpartyTxCount[destination]
        if (prior != null && prior == 0) {
            add(
                severity = Severity.INFO,
                code = "NEW_COUNTERPARTY",
                title = "First time sending here",
                plainEnglish = "This is the first transaction involving " +
                    "${describer.short(destination)}, which has no prior history.",
                instructionIndex = instruction.index,
                addresses = listOfNotNull(destination),
            )
        }
    }

    private fun checkApproval(
        kind: InstructionKind.TokenApprove,
        instruction: DecodedInstruction,
    ) {
        val delegate = Narrator.delegateOfApproval(kind, instruction)
        val mint = Narrator.mintOfApproval(kind, instruction, context)
        val tokenAccount = instruction.accounts.getOrNull(0)
        val holding = describer.holdingOf(tokenAccount, mint)
        val unlimited = kind.amount < 0L

        if (unlimited) {
            add(
                severity = Severity.CRITICAL,
                code = "UNLIMITED_TOKEN_APPROVAL",
                title = "Unlimited token approval",
                plainEnglish = "This gives ${describer.short(delegate)} unlimited permission to " +
                    "move ${describer.tokenUnit(mint)} out of your account. They can drain it at " +
                    "any time, without asking you again, and the approval never expires.",
                instructionIndex = instruction.index,
                addresses = listOfNotNull(delegate),
                evidence = mapOf(
                    "amount" to AmountFormat.U64_MAX_TEXT,
                    "delegate" to (delegate ?: "unknown"),
                    "tokenAccount" to (tokenAccount ?: "unknown"),
                    "mint" to (mint ?: "unknown"),
                ),
            )
        } else {
            add(
                severity = Severity.MEDIUM,
                code = "BOUNDED_TOKEN_APPROVAL",
                title = "Delegate approval",
                plainEnglish = "This lets ${describer.short(delegate)} move up to " +
                    "${describer.amountWithHolding(kind.amount, mint, holding)} from your account " +
                    "without asking you again.",
                instructionIndex = instruction.index,
                addresses = listOfNotNull(delegate),
                evidence = mapOf(
                    "amount" to kind.amount.toString(),
                    "delegate" to (delegate ?: "unknown"),
                    "mint" to (mint ?: "unknown"),
                ),
            )
        }
    }

    private fun checkSetAuthority(
        kind: InstructionKind.TokenSetAuthority,
        instruction: DecodedInstruction,
    ) {
        val owned = instruction.accounts.getOrNull(0)
        val target = kind.newAuthority
        val who = if (target == null) "nobody" else describer.short(target)
        val clause = describer.counterpartyClause(target)
        val type = kind.authorityType

        val severity: Severity
        val code: String
        val title: String
        val sentence: String

        when (type) {
            AuthorityType.MINT_TOKENS -> if (target == null) {
                severity = Severity.LOW
                code = "SET_AUTHORITY_RENOUNCED"
                title = "Mint authority renounced"
                sentence = "This gives up the mint authority over ${describer.short(owned)} for " +
                    "good, so no more tokens can ever be printed. Usually that is a safety win, " +
                    "but it cannot be undone."
            } else {
                severity = Severity.CRITICAL
                code = "SET_MINT_AUTHORITY"
                title = "Mint authority handed over"
                sentence = "This hands the mint authority over ${describer.short(owned)} to $who" +
                    "$clause. Whoever holds it can print unlimited new tokens and sell them into " +
                    "the market you are holding."
            }

            AuthorityType.FREEZE_ACCOUNT -> if (target == null) {
                severity = Severity.LOW
                code = "SET_AUTHORITY_RENOUNCED"
                title = "Freeze authority renounced"
                sentence = "This gives up the freeze authority over ${describer.short(owned)} for " +
                    "good. Nobody will be able to freeze holders any more."
            } else {
                severity = Severity.HIGH
                code = "SET_FREEZE_AUTHORITY"
                title = "Freeze authority handed over"
                sentence = "This hands the freeze authority over ${describer.short(owned)} to " +
                    "$who$clause. They will be able to freeze any holder's tokens — including " +
                    "yours — at any moment."
            }

            AuthorityType.ACCOUNT_OWNER -> if (target == null) {
                severity = Severity.MEDIUM
                code = "SET_ACCOUNT_OWNER"
                title = "Account ownership given up"
                sentence = "This permanently gives up ownership of " +
                    "${describer.label(owned)}. Nobody — including you — will control it again."
            } else {
                severity = Severity.CRITICAL
                code = "SET_ACCOUNT_OWNER"
                title = "Account ownership handed over"
                sentence = "This transfers ownership of ${describer.label(owned)} to $who$clause. " +
                    "Everything in that account becomes theirs to move."
            }

            AuthorityType.CLOSE_ACCOUNT -> if (target == null) {
                severity = Severity.LOW
                code = "SET_AUTHORITY_RENOUNCED"
                title = "Close authority renounced"
                sentence = "This gives up the close authority over ${describer.short(owned)}."
            } else {
                severity = Severity.HIGH
                code = "SET_CLOSE_AUTHORITY"
                title = "Close authority handed over"
                sentence = "This lets $who close ${describer.label(owned)} and take its rent at " +
                    "any time."
            }

            null -> {
                severity = Severity.MEDIUM
                code = "SET_UNKNOWN_AUTHORITY"
                title = "Unknown authority change"
                sentence = "This changes authority type #${kind.authorityTypeTag} over " +
                    "${describer.short(owned)} to $who. We do not recognise that authority type."
            }
        }

        add(
            severity = severity,
            code = code,
            title = title,
            plainEnglish = sentence,
            instructionIndex = instruction.index,
            addresses = listOfNotNull(owned, target),
            evidence = mapOf(
                "authorityType" to (type?.label ?: "unknown(${kind.authorityTypeTag})"),
                "newAuthority" to (target ?: "none (renounced)"),
                "account" to (owned ?: "unknown"),
            ),
        )
    }

    private fun checkCloseAccount(instruction: DecodedInstruction) {
        val account = instruction.accounts.getOrNull(0)
        val destination = instruction.accounts.getOrNull(1)
        val user = context.userAddress
        val foreign = user != null && destination != user
        val source = context.sourceTokenAccount
            ?.takeIf { it.address == null || it.address == account }
        val leftover = source?.amount ?: 0L
        val mint = source?.mint
        val holdingNote = if (leftover > 0L) {
            " It also destroys the ${describer.tokenAmount(leftover, mint)} still sitting in the " +
                "account."
        } else ""

        add(
            severity = if (foreign) Severity.HIGH else Severity.MEDIUM,
            code = if (foreign) "CLOSE_ACCOUNT_TO_FOREIGN" else "CLOSE_ACCOUNT",
            title = if (foreign) "Account closed to someone else's wallet"
            else "Token account closed",
            plainEnglish = if (foreign) {
                "This closes ${describer.label(account)} and sends the rent (about 0.00204 SOL) to " +
                    "${describer.short(destination)}${
                        describer.counterpartyClause(destination)
                    } — not back to your wallet.$holdingNote"
            } else {
                "This closes ${describer.label(account)} and returns its rent (about 0.00204 SOL) " +
                    "to ${describer.short(destination)}.$holdingNote"
            },
            instructionIndex = instruction.index,
            addresses = listOfNotNull(account, destination),
            evidence = mapOf(
                "account" to (account ?: "unknown"),
                "rentDestination" to (destination ?: "unknown"),
                "remainingTokens" to leftover.toString(),
            ),
        )
    }

    private fun checkExtensionInstruction(
        kind: InstructionKind.Token2022Extension,
        instruction: DecodedInstruction,
    ) {
        val mint = instruction.accounts.getOrNull(0)
        when (kind.extensionTag) {
            35 -> add(
                severity = Severity.CRITICAL,
                code = "TOKEN2022_INIT_PERMANENT_DELEGATE",
                title = "Permanent delegate installed",
                plainEnglish = "This gives ${describer.short(kind.permanentDelegate)} a permanent " +
                    "delegate over ${describer.short(mint)}: from now on they can move or burn " +
                    "every holder's tokens, and the permission can never be revoked.",
                instructionIndex = instruction.index,
                addresses = listOfNotNull(mint, kind.permanentDelegate),
                evidence = mapOf("permanentDelegate" to (kind.permanentDelegate ?: "unset")),
            )

            36 -> if (kind.subInstruction == 0) {
                add(
                    severity = Severity.HIGH,
                    code = "TOKEN2022_INIT_TRANSFER_HOOK",
                    title = "Transfer hook installed",
                    plainEnglish = "This installs ${describer.short(kind.transferHookProgram)} as " +
                        "a transfer hook on ${describer.short(mint)}. Every transfer of this token " +
                        "will call that program, which can add rules or block your transfer.",
                    instructionIndex = instruction.index,
                    addresses = listOfNotNull(mint, kind.transferHookProgram),
                    evidence = mapOf("transferHookProgram" to (kind.transferHookProgram ?: "unset")),
                )
            }

            28 -> if (kind.defaultAccountState == DefaultAccountState.FROZEN) {
                add(
                    severity = Severity.HIGH,
                    code = "TOKEN2022_DEFAULT_STATE_FROZEN",
                    title = "New accounts start frozen",
                    plainEnglish = "This makes every new account for ${describer.short(mint)} start " +
                        "out frozen. Whoever holds the freeze authority decides whether you can " +
                        "ever move the tokens you buy.",
                    instructionIndex = instruction.index,
                    addresses = listOfNotNull(mint),
                    evidence = mapOf("defaultAccountState" to "frozen"),
                )
            }

            26 -> when (kind.subInstruction) {
                0, 6 -> if ((kind.transferFeeBasisPoints ?: 0) > 0) {
                    add(
                        severity = Severity.MEDIUM,
                        code = "TOKEN2022_SET_TRANSFER_FEE",
                        title = "Transfer fee configured",
                        plainEnglish = "This sets a " +
                            "${basisPointsToPercent(kind.transferFeeBasisPoints ?: 0)} transfer fee on " +
                            "${describer.short(mint)}, capped at " +
                            "${describer.tokenAmount(kind.transferFeeMaximum ?: 0L, mint)} per " +
                            "transfer. It is taken out of what you receive.",
                        instructionIndex = instruction.index,
                        addresses = listOfNotNull(mint),
                        evidence = mapOf(
                            "basisPoints" to (kind.transferFeeBasisPoints ?: 0).toString(),
                            "maximumFee" to (kind.transferFeeMaximum ?: 0L).toString(),
                        ),
                    )
                }

                1 -> add(
                    severity = Severity.MEDIUM,
                    code = "TOKEN2022_TRANSFER_FEE_CHARGED",
                    title = "Transfer fee withheld",
                    plainEnglish = "This transfer carries a fee of " +
                        "${describer.tokenAmount(kind.fee ?: 0L, mint)} on " +
                        "${describer.tokenAmount(kind.amount ?: 0L, mint)} — the fee is taken out " +
                        "of the amount that arrives.",
                    instructionIndex = instruction.index,
                    addresses = listOfNotNull(mint),
                    evidence = mapOf(
                        "amount" to (kind.amount ?: 0L).toString(),
                        "fee" to (kind.fee ?: 0L).toString(),
                    ),
                )
            }
        }
    }

    // -------------------------------------------------- account-level context

    private fun checkMintExtensions() {
        if (context.mintExtensions.isEmpty()) return
        val touched = tx.accountKeys.filter { context.mintExtensions.containsKey(it) }
        for (mint in touched) {
            val extensions = context.mintExtensions[mint] ?: continue
            val symbol = describer.tokenUnit(mint)

            extensions.permanentDelegate?.let { delegate ->
                add(
                    severity = Severity.CRITICAL,
                    code = "TOKEN2022_PERMANENT_DELEGATE",
                    title = "Token has a permanent delegate",
                    plainEnglish = "$symbol is a Token-2022 token with a permanent delegate: " +
                        "${describer.short(delegate)} can move or burn tokens out of any holder's " +
                        "account at any time, and that permission can never be taken away.",
                    addresses = listOf(mint, delegate),
                    evidence = mapOf("mint" to mint, "permanentDelegate" to delegate),
                )
            }

            extensions.transferHookProgram?.let { hook ->
                add(
                    severity = Severity.HIGH,
                    code = "TOKEN2022_TRANSFER_HOOK",
                    title = "Token routes transfers through a hook program",
                    plainEnglish = "$symbol calls ${describer.short(hook)} on every single " +
                        "transfer. That program can add rules, charge extra, or refuse to let you " +
                        "move your tokens at all.",
                    addresses = listOf(mint, hook),
                    evidence = mapOf("mint" to mint, "transferHookProgram" to hook),
                )
            }

            if (extensions.freezesNewAccounts) {
                add(
                    severity = Severity.HIGH,
                    code = "TOKEN2022_DEFAULT_STATE_FROZEN",
                    title = "New accounts start frozen",
                    plainEnglish = "$symbol is configured so that every new holder account starts " +
                        "frozen. Whoever holds the freeze authority decides whether you can ever " +
                        "move what you buy.",
                    addresses = listOf(mint),
                    evidence = mapOf("mint" to mint, "defaultAccountState" to "frozen"),
                )
            }

            val bps = extensions.transferFeeBasisPoints ?: 0
            if (bps > 0) {
                add(
                    severity = Severity.MEDIUM,
                    code = "TOKEN2022_TRANSFER_FEE",
                    title = "Token charges a transfer fee",
                    plainEnglish = "$symbol charges a ${basisPointsToPercent(bps)} transfer fee on " +
                        "every move, capped at " +
                        "${describer.tokenAmount(extensions.transferFeeMaximum ?: 0L, mint)}.",
                    addresses = listOf(mint),
                    evidence = mapOf(
                        "mint" to mint,
                        "basisPoints" to bps.toString(),
                        "maximumFee" to (extensions.transferFeeMaximum ?: 0L).toString(),
                    ),
                )
            }

            if (extensions.nonTransferable) {
                add(
                    severity = Severity.MEDIUM,
                    code = "TOKEN2022_NON_TRANSFERABLE",
                    title = "Token cannot be transferred",
                    plainEnglish = "$symbol is non-transferable: once it lands in your account it " +
                        "can never be sent anywhere else.",
                    addresses = listOf(mint),
                    evidence = mapOf("mint" to mint),
                )
            }

            extensions.mintCloseAuthority?.let { authority ->
                add(
                    severity = Severity.LOW,
                    code = "TOKEN2022_MINT_CLOSE_AUTHORITY",
                    title = "Mint can be closed",
                    plainEnglish = "The mint of $symbol can be closed by " +
                        "${describer.short(authority)}, which can affect the token's metadata and " +
                        "remaining supply.",
                    addresses = listOf(mint, authority),
                    evidence = mapOf("mint" to mint, "mintCloseAuthority" to authority),
                )
            }
        }
    }

    private fun checkSourceAccount() {
        val account = context.sourceTokenAccount ?: return
        val address = account.address
        if (address != null && tx.accountKeys.isNotEmpty() && !tx.accountKeys.contains(address)) return

        account.delegate?.let { delegate ->
            add(
                severity = Severity.MEDIUM,
                code = "ACCOUNT_HAS_ACTIVE_DELEGATE",
                title = "An active delegate already exists",
                plainEnglish = "Your token account already has a delegate: " +
                    "${describer.short(delegate)} can move up to " +
                    "${describer.tokenAmount(account.delegatedAmount, account.mint)} out of it " +
                    "right now, without your signature.",
                addresses = listOfNotNull(delegate),
                evidence = mapOf(
                    "delegate" to delegate,
                    "delegatedAmount" to account.delegatedAmount.toString(),
                ),
            )
        }

        if (account.isFrozen) {
            add(
                severity = Severity.HIGH,
                code = "SOURCE_ACCOUNT_FROZEN",
                title = "Your token account is frozen",
                plainEnglish = "The account this transaction uses is frozen, so the transfer will " +
                    "fail — and only the token's freeze authority can unfreeze it.",
                evidence = mapOf("state" to "frozen"),
            )
        }
    }

    /** Unlimited approval handed to an unverified program is the textbook drainer shape. */
    private fun checkDrainerComposite() {
        val unlimited = findings.any { it.code == "UNLIMITED_TOKEN_APPROVAL" }
        val unknown = findings.any { it.code == "UNKNOWN_PROGRAM" }
        if (!unlimited || !unknown) return
        val delegate = findings.first { it.code == "UNLIMITED_TOKEN_APPROVAL" }
            .evidence["delegate"] ?: "an unknown address"
        add(
            severity = Severity.CRITICAL,
            code = "DRAINER_PATTERN",
            title = "Wallet-drainer pattern",
            plainEnglish = "Unlimited approval for ${describer.short(delegate)} combined with an " +
                "unrecognised program is the exact shape of a wallet drainer: the approval lets " +
                "them empty your token account later, when nobody is watching.",
            instructionIndex = findings.first { it.code == "UNLIMITED_TOKEN_APPROVAL" }
                .instructionIndex,
            addresses = listOf(delegate),
            evidence = mapOf("delegate" to delegate),
        )
    }

    // ------------------------------------------------------------------ result

    private fun build(): Verdict {
        if (findings.isEmpty()) {
            add(
                severity = Severity.INFO,
                code = "NO_RED_FLAGS",
                title = "Nothing dangerous found",
                plainEnglish = "We decoded every instruction and found nothing here that can move " +
                    "your funds without another signature from you.",
            )
        }

        val score = findings.sumOf { it.severity.weight }.coerceAtMost(100)
        val level = when {
            findings.any { it.severity == Severity.CRITICAL } -> VerdictLevel.DANGER
            score >= 70 -> VerdictLevel.DANGER
            score >= 20 -> VerdictLevel.CAUTION
            else -> VerdictLevel.SAFE
        }

        val sorted = findings.sortedWith(
            compareByDescending<RiskFinding> { it.severity.rank }
                .thenBy { it.instructionIndex ?: Int.MAX_VALUE }
                .thenBy { it.code }
        )

        val headline = when (level) {
            VerdictLevel.DANGER -> "Danger: ${sorted.first().title}"
            VerdictLevel.CAUTION -> "Caution: ${sorted.first().title}"
            VerdictLevel.SAFE -> "No red flags found"
        }

        return Verdict(level = level, score = score, findings = sorted, headline = headline)
    }

    private fun basisPointsToPercent(basisPoints: Int): String {
        val percent = basisPoints / 100.0
        return if (percent == percent.toLong().toDouble()) "${percent.toLong()}%"
        else "${Math.round(percent * 100.0) / 100.0}%"
    }

    private fun plural(count: Int): String = if (count == 1) "" else "s"
}

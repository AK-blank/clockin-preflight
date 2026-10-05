package com.clockin.preflight.mwa

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import android.util.Log
import com.solana.mobilewalletadapter.clientlib.LocalAdapterOperations
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.associationDetails
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationIntentCreator
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import com.solana.mobilewalletadapter.clientlib.scenario.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** The steps the wallet hand-off goes through; rendered live as a timeline in [WalletPanel]. */
enum class WalletStage(val label: String) {
    LOOK_FOR_WALLET("Look for an MWA wallet"),
    LAUNCH_WALLET("Hand off to the wallet"),
    CONNECT("Open the encrypted session"),
    AUTHORIZE("Authorize this app"),
    PRE_FLIGHT("Pre-flight the payload"),
    SIGN("Request the signature"),
    VERIFY("Verify the signature on-device"),
    DONE("Complete"),
}

/** Terminal state of one wallet hand-off. Plain data: the UI never sees an MWA type. */
sealed interface WalletOutcome {

    val totalMs: Long

    class Signed(
        val walletLabel: String,
        val walletPubkey: ByteArray,
        val walletPubkeyBase58: String,
        val authTokenLength: Int,
        val sessionVersion: String?,
        val cluster: String,
        val payload: ByteArray,
        val signedPayload: ByteArray,
        val messageBytes: Int,
        val signature: ByteArray,
        val signatureBase58: String,
        val signatureHex: String,
        val signatureVerified: Boolean,
        val signatureSlotMatches: Boolean,
        val authorizeMs: Long,
        val signMs: Long,
        override val totalMs: Long,
    ) : WalletOutcome

    class Failed(
        val stage: WalletStage,
        val message: String,
        val detail: String,
        override val totalMs: Long,
    ) : WalletOutcome

    class NoWallet(
        val message: String,
        override val totalMs: Long,
    ) : WalletOutcome
}

/**
 * The dApp half of a Mobile Wallet Adapter session, inside the app the judges install.
 *
 * This is a deliberate, line-for-line mirror of what
 * `MobileWalletAdapter.associate()` does in `mobile-wallet-adapter-clientlib-ktx:2.0.3`
 * (`tools/mwa/src/ktx/.../MobileWalletAdapter.kt`):
 *
 * ```
 * scenario = LocalAssociationScenario(timeout)
 * details  = scenario.associationDetails(walletUriBase)
 * intent   = LocalAssociationIntentCreator.createAssociationIntent(details.uriPrefix, details.port, details.session)
 *            -> launch the association intent  (the wallet starts serving ws://127.0.0.1:<port>)
 * client   = scenario.start().get(10s)                  // dApp dials in, ECDH + AES-GCM handshake
 * ops      = LocalAdapterOperations(Dispatchers.IO, client)
 * ops.authorize(identityUri, iconUri, identityName, chain, …)   // the transact{} wrapper does this
 * ops.signTransactions(arrayOf(payload))
 * … finally scenario.close()
 * ```
 *
 * **Why not the `ActivityResultSender` wrapper?** `ActivityResultSender`'s constructor calls
 * `rootActivity.registerForActivityResult(...)`, and `androidx.activity.result.ActivityResultRegistry
 * .register` throws `IllegalStateException("… must call register before they are STARTED")` when the
 * activity has reached STARTED (verified in the 1.9.2 bytecode). The harness could build a sender
 * because it does so in `onCreate`; a Compose panel only runs after `onResume`, so the wrapper is
 * unusable from this call site. The sender is used by the wrapper for exactly one thing — launching
 * the association intent — so this mirror launches it with `startActivity` instead. Everything after
 * that (scenario lifecycle, session properties, `LocalAdapterOperations`, JSON-RPC operations) is
 * identical, and the wallet's result is never read by the wrapper either.
 */
class WalletSigner(private val activity: Activity) {

    /**
     * Connect to a wallet, authorize, build the payload from the *authorized* key, hand it to
     * [onPayloadReady] (the caller pre-flights it there — verdict before signature), ask the wallet
     * to sign it, then verify the returned signature locally.
     */
    suspend fun connectAndSign(
        cluster: String = Solana.Devnet.fullName,
        lamports: Long = DEMO_LAMPORTS,
        onStage: (WalletStage) -> Unit = {},
        onPayloadReady: suspend (ByteArray) -> Unit = {},
    ): WalletOutcome {
        val startedAt = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - startedAt

        var currentStage = WalletStage.LOOK_FOR_WALLET
        fun advanceTo(next: WalletStage) {
            currentStage = next
            onStage(next)
        }

        advanceTo(WalletStage.LOOK_FOR_WALLET)
        if (!LocalAssociationIntentCreator.isWalletEndpointAvailable(activity.packageManager)) {
            return WalletOutcome.NoWallet(
                "No Mobile Wallet Adapter wallet is installed on this device. Install one " +
                    "(Phantom, Solflare, Seed Vault…) and retry.",
                elapsed(),
            )
        }

        var scenario: LocalAssociationScenario? = null
        return try {
            val local = LocalAssociationScenario(Scenario.DEFAULT_CLIENT_TIMEOUT_MS)
            scenario = local
            val details = local.associationDetails()
            val associationIntent = LocalAssociationIntentCreator.createAssociationIntent(
                details.uriPrefix,
                details.port,
                details.session,
            )

            advanceTo(WalletStage.LAUNCH_WALLET)
            log("association intent port=${details.port} uri=${associationIntent.data}")
            activity.startActivity(associationIntent)

            advanceTo(WalletStage.CONNECT)
            val client = withContext(Dispatchers.IO) {
                @Suppress("BlockingMethodInNonBlockingContext")
                local.start().get(SCENARIO_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            val sessionVersion = runCatching {
                local.session.sessionProperties.protocolVersion.toString()
            }.getOrNull()
            val operations = LocalAdapterOperations(Dispatchers.IO, client)

            advanceTo(WalletStage.AUTHORIZE)
            val authorizeStartedAt = System.currentTimeMillis()
            val authorization: MobileWalletAdapterClient.AuthorizationResult =
                withContext(Dispatchers.IO) {
                    operations.authorize(
                        IDENTITY_URI,
                        ICON_URI,
                        IDENTITY_NAME,
                        cluster,
                        null, // no cached auth token: always a fresh authorize in the demo
                        null,
                        null,
                        null,
                    )
                }
            val authorizeMs = System.currentTimeMillis() - authorizeStartedAt
            val account = authorization.accounts.firstOrNull()
                ?: throw IllegalStateException("The wallet authorized no accounts.")
            log(
                "authorize=OK +${authorizeMs}ms accounts=${authorization.accounts.size} " +
                    "label=${account.accountLabel} pubkey=${LegacyTransaction.base58(account.publicKey)}",
            )

            advanceTo(WalletStage.PRE_FLIGHT)
            val payload = LegacyTransaction.transfer(
                from = account.publicKey,
                to = DEMO_RECIPIENT,
                lamports = lamports,
            )
            log("payload built: ${payload.size} bytes, signer=${LegacyTransaction.base58(account.publicKey)}")
            onPayloadReady(payload)

            advanceTo(WalletStage.SIGN)
            val signStartedAt = System.currentTimeMillis()
            val signedPayloads = withContext(Dispatchers.IO) {
                // Deliberately sign-only. MWA 2.0 deprecates signTransactions in favour of
                // signAndSendTransactions, but a pre-flight guard must NOT broadcast: the whole point
                // is to let the user read the verdict and walk away. The payload also carries a
                // placeholder blockhash, so it is not a valid broadcast candidate by design.
                @Suppress("DEPRECATION")
                operations.signTransactions(arrayOf(payload)).signedPayloads
            }
            val signMs = System.currentTimeMillis() - signStartedAt
            val signedPayload = signedPayloads.firstOrNull()
                ?: throw IllegalStateException("The wallet returned no signed payload.")

            advanceTo(WalletStage.VERIFY)
            val message = LegacyTransaction.message(payload)
            val signature = LegacyTransaction.signature(signedPayload)
            val verified = withContext(Dispatchers.Default) {
                Ed25519.verify(account.publicKey, message, signature)
            }
            val slotMatches = LegacyTransaction.signatureSlotMatches(payload, signedPayload)
            log(
                "sign_transactions=OK +${signMs}ms request=${payload.size}B response=${signedPayload.size}B " +
                    "message=${message.size}B signature=${signature.size}B " +
                    "verified=$verified slotMatches=$slotMatches",
            )

            advanceTo(WalletStage.DONE)
            WalletOutcome.Signed(
                walletLabel = account.accountLabel ?: "Wallet",
                walletPubkey = account.publicKey,
                walletPubkeyBase58 = LegacyTransaction.base58(account.publicKey),
                authTokenLength = authorization.authToken.length,
                sessionVersion = sessionVersion,
                cluster = cluster,
                payload = payload,
                signedPayload = signedPayload,
                messageBytes = message.size,
                signature = signature,
                signatureBase58 = LegacyTransaction.base58(signature),
                signatureHex = LegacyTransaction.hex(signature),
                signatureVerified = verified,
                signatureSlotMatches = slotMatches,
                authorizeMs = authorizeMs,
                signMs = signMs,
                totalMs = elapsed(),
            )
        } catch (e: ActivityNotFoundException) {
            WalletOutcome.NoWallet(
                "No app on this device answers the Mobile Wallet Adapter association intent.",
                elapsed(),
            )
        } catch (e: Exception) {
            // Gotcha (b) in tools/MWA-RUNTIME-REPORT.md: exceptions the wrapper does not catch (e.g.
            // an absolute iconUri) escape and would otherwise kill the host app. Surface them as state.
            val detail = describe(e)
            log("FAILED at stage=$currentStage: $detail")
            WalletOutcome.Failed(
                stage = currentStage,
                message = friendlyMessage(e),
                detail = detail,
                totalMs = elapsed(),
            )
        } finally {
            try {
                @Suppress("BlockingMethodInNonBlockingContext")
                scenario?.close()?.get(SCENARIO_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                log("scenario close failed (ignored): ${e.message}")
            }
        }
    }

    /** Human-readable chain of causes — the JSON-RPC error code often lives one level down. */
    private fun describe(e: Throwable): String {
        val chain = mutableListOf<String>()
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 5) {
            chain += "${current::class.java.name}: ${current.message}"
            current = current.cause
            depth++
        }
        return chain.joinToString(" <- ")
    }

    private fun friendlyMessage(e: Exception): String {
        val text = describe(e)
        return when {
            text.contains("-32601") || text.contains("not available") ->
                "The wallet answered but does not serve the optional sign_transactions feature " +
                    "(-32601). That is a wallet capability, not an app error — use a wallet that " +
                    "advertises signing. Detail: $text"
            text.contains("Unable to connect to websocket server") ->
                "The wallet never opened its localhost websocket endpoint (association timed out). " +
                    "Detail: $text"
            text.contains("iconRelativeUri") ->
                "Connection identity rejected: iconUri must be a relative URI. Detail: $text"
            text.contains("InsecureWalletEndpointUri") ->
                "The wallet returned a non-HTTPS wallet URI base, which MWA forbids. Detail: $text"
            else -> "Wallet hand-off failed. Detail: $text"
        }
    }

    private fun log(message: String) = Log.i(TAG, message)

    companion object {
        const val TAG = "CLOCKIN-MWA-APP"

        /** Relative on purpose: gotcha (a) — an absolute iconUri throws IllegalArgumentException. */
        val IDENTITY_URI: Uri = Uri.parse("https://clockin.local")
        val ICON_URI: Uri = Uri.parse("icon.png")
        const val IDENTITY_NAME: String = "CLOCK IN Pre-Flight"

        const val DEMO_LAMPORTS: Long = 1_000_000L

        /** Deterministic stand-in recipient, so the signed payload is reproducible between takes. */
        val DEMO_RECIPIENT: ByteArray = ByteArray(32) { i -> ((i * 7 + 11) and 0xff).toByte() }

        private const val SCENARIO_CONNECT_TIMEOUT_MS = 10_000L
        private const val SCENARIO_CLOSE_TIMEOUT_MS = 10_000L
    }
}

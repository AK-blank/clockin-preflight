package com.clockin.mockwallet

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import com.solana.mobilewalletadapter.common.ProtocolContract
import com.solana.mobilewalletadapter.walletlib.association.AssociationUri
import com.solana.mobilewalletadapter.walletlib.association.LocalAssociationUri
import com.solana.mobilewalletadapter.walletlib.authorization.AuthIssuerConfig
import com.solana.mobilewalletadapter.walletlib.protocol.MobileWalletAdapterConfig
import com.solana.mobilewalletadapter.walletlib.scenario.AuthorizeRequest
import com.solana.mobilewalletadapter.walletlib.scenario.AuthorizedAccount
import com.solana.mobilewalletadapter.walletlib.scenario.DeauthorizedEvent
import com.solana.mobilewalletadapter.walletlib.scenario.LocalScenario
import com.solana.mobilewalletadapter.walletlib.scenario.ReauthorizeRequest
import com.solana.mobilewalletadapter.walletlib.scenario.Scenario
import com.solana.mobilewalletadapter.walletlib.scenario.SignAndSendTransactionsRequest
import com.solana.mobilewalletadapter.walletlib.scenario.SignMessagesRequest
import com.solana.mobilewalletadapter.walletlib.scenario.SignTransactionsRequest
import org.bouncycastle.crypto.AsymmetricCipherKeyPair

/**
 * A minimal but REAL Mobile Wallet Adapter wallet.
 *
 * It is launched by the dApp's association intent (`solana-wallet://...v1/associate/local?port=N`),
 * starts the localhost websocket server that the dApp dials into, completes the ECDH/AES-GCM
 * session handshake (all handled by walletlib), then answers `authorize` and `sign_transactions`.
 *
 * The only things "fake" about it are the key material and the absence of a user-consent UI.
 * The transport, session encryption, JSON-RPC framing and payload encoding are the official
 * walletlib 2.0.3 implementations — the same code a production wallet runs.
 */
class MockWalletActivity : Activity(), LocalScenario.Callbacks {

    private var scenario: Scenario? = null
    private lateinit var keyPair: AsymmetricCipherKeyPair
    private var authorized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val associationUri = intent?.data
        Log.i(TAG, "onCreate: association uri = $associationUri")
        if (associationUri == null) {
            Log.e(TAG, "no association URI in intent; nothing to do")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        keyPair = Ed25519.newKeyPair()
        Log.i(TAG, "wallet pubkey (base64) = ${Base64.encodeToString(rawPublicKey(), Base64.NO_WRAP)}")

        try {
            // AssociationUri.parse returns a LocalAssociationUri for `...v1/associate/local`,
            // which validates that `port` is present and inside 49152..65535.
            val uri: AssociationUri = AssociationUri.parse(associationUri)
                ?: throw IllegalArgumentException("unsupported association URI: $associationUri")
            val local = uri as? LocalAssociationUri
            Log.i(TAG, "parsed association: local=${local != null} port=${local?.port} " +
                    "associationPubkey=${uri.associationPublicKey?.size}B " +
                    "protocols=${uri.associationProtocolVersions}")

            val config = MobileWalletAdapterConfig(
                10,                       // maxTransactionsPerSigningRequest
                10,                       // maxMessagesPerSigningRequest
                arrayOf<Any>("legacy"),   // supportedTransactionVersions
                0L,                       // noConnectionWarningTimeoutMs
                // CRITICAL: in MWA 2.0 the wallet must ADVERTISE the optional methods it serves.
                // MobileWalletAdapterServer only dispatches sign_transactions when this list
                // contains FEATURE_ID_SIGN_TRANSACTIONS; otherwise the dApp receives
                // JSON-RPC "-32601: method 'sign_transactions' not available".
                arrayOf(
                    ProtocolContract.FEATURE_ID_SIGN_TRANSACTIONS,
                    ProtocolContract.FEATURE_ID_SIGN_MESSAGES,
                    ProtocolContract.FEATURE_ID_SIGN_AND_SEND_TRANSACTIONS
                )
            )

            val s = uri.createScenario(
                this,
                config,
                AuthIssuerConfig("clockin-mock-wallet"),
                this
            )
            scenario = s
            Log.i(TAG, "starting wallet scenario (localhost websocket server)...")
            s.start()
        } catch (e: Exception) {
            Log.e(TAG, "failed to start wallet scenario", e)
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    /** Raw 32-byte Solana public key. */
    private fun rawPublicKey(): ByteArray = Ed25519.publicKeyRaw(keyPair)

    /** Ed25519-sign raw bytes with the wallet key. */
    private fun sign(payload: ByteArray): ByteArray = Ed25519.sign(keyPair, payload)

    /**
     * Sign a serialized legacy Solana transaction the way a real wallet does: sign the MESSAGE
     * (the bytes after the signature slots) and write the 64-byte signature into slot 0.
     *
     * Layout: compact-u16 signature count | 64 bytes per signature | message
     * so the message offset is derivable from the leading count without parsing the header.
     */
    private fun signTransaction(tx: ByteArray): ByteArray {
        var offset = 0
        var count = 0
        var shift = 0
        while (true) {
            val b = tx[offset++].toInt() and 0xff
            count = count or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        check(count >= 1) { "transaction declares $count signatures" }
        val msgStart = offset + 64 * count
        val message = tx.copyOfRange(msgStart, tx.size)
        val signature = sign(message)
        val out = tx.copyOf()
        System.arraycopy(signature, 0, out, offset, signature.size)
        Log.i(TAG, "  tx sigCount=$count messageOffset=$msgStart messageLen=${message.size}")
        return out
    }

    // ---------------------------------------------------------------- Scenario.Callbacks

    override fun onScenarioReady() {
        Log.i(TAG, "callback: onScenarioReady")
    }

    override fun onScenarioServingClients() {
        Log.i(TAG, "callback: onScenarioServingClients (dApp connected)")
    }

    override fun onScenarioServingComplete() {
        Log.i(TAG, "callback: onScenarioServingComplete -> dismissing wallet UI (as a real wallet does)")
        // A real wallet returns to the dApp once it has answered the signing request. Give the
        // response a moment to flush, then finish so the dApp (and its result screen) is visible.
        // NOTE: onScenarioComplete() does NOT fire on this path because the dApp tears the
        // scenario down first, so the finish() has to happen here.
        window.decorView.postDelayed({
            setResult(RESULT_OK, Intent().putExtra("mockwallet", "session-complete"))
            finish()
        }, 600L)
    }

    override fun onScenarioComplete() {
        Log.i(TAG, "callback: onScenarioComplete -> returning RESULT_OK to dApp")
        setResult(RESULT_OK, Intent().putExtra("mockwallet", "session-complete"))
        finish()
    }

    override fun onScenarioError() {
        Log.e(TAG, "callback: onScenarioError")
    }

    override fun onScenarioTeardownComplete() {
        Log.i(TAG, "callback: onScenarioTeardownComplete")
    }

    override fun onLowPowerAndNoConnection() {
        Log.w(TAG, "callback: onLowPowerAndNoConnection")
    }

    override fun onAuthorizeRequest(request: AuthorizeRequest) {
        Log.i(TAG, "callback: onAuthorizeRequest identity=${request.identityName} " +
                "chain=${request.chain} uri=${request.identityUri}")
        authorized = true
        val account = AuthorizedAccount(
            rawPublicKey(),
            "Mock Wallet",                                     // accountLabel
            Uri.parse("https://clockin.local/icon.png"),        // icon
            arrayOf("solana:devnet"),                           // chains
            arrayOf<String>()                                   // features
        )
        // walletUriBase is null on purpose: a non-https value makes the reference client throw
        // MobileWalletAdapterClient.InsecureWalletEndpointUriException.
        request.completeWithAuthorize(arrayOf(account), null, null, null)
        Log.i(TAG, "authorize completed with ${rawPublicKey().size}-byte pubkey")
    }

    override fun onReauthorizeRequest(request: ReauthorizeRequest) {
        Log.i(TAG, "callback: onReauthorizeRequest -> granting")
        request.completeWithReauthorize()
    }

    override fun onSignTransactionsRequest(request: SignTransactionsRequest) {
        if (!authorized) {
            Log.w(TAG, "sign request before authorize -> declining")
            request.completeWithDecline()
            return
        }
        val payloads = request.payloads
        Log.i(TAG, "callback: onSignTransactionsRequest count=${payloads.size} " +
                "sizes=${payloads.joinToString { it.size.toString() }}")
        val signed = Array(payloads.size) { i ->
            signTransaction(payloads[i]).also { out ->
                Log.i(TAG, "signed payload[$i]: ${payloads[i].size}B tx -> ${out.size}B signed tx " +
                        "(ed25519 over message bytes, matching a real wallet)")
            }
        }
        request.completeWithSignedPayloads(signed)
    }

    override fun onSignMessagesRequest(request: SignMessagesRequest) {
        Log.i(TAG, "callback: onSignMessagesRequest count=${request.payloads.size}")
        request.completeWithSignedPayloads(Array(request.payloads.size) { sign(request.payloads[it]) })
    }

    override fun onSignAndSendTransactionsRequest(request: SignAndSendTransactionsRequest) {
        // We are offline in the harness: do not broadcast.
        Log.w(TAG, "callback: onSignAndSendTransactionsRequest -> declining (harness does not broadcast)")
        request.completeWithDecline()
    }

    override fun onDeauthorizedEvent(event: DeauthorizedEvent) {
        Log.i(TAG, "callback: onDeauthorizedEvent -> completing")
        authorized = false
        event.complete()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: closing scenario")
        scenario?.close()
        scenario = null
    }

    companion object {
        const val TAG = "CLOCKIN-MWA-WALLET"
    }
}

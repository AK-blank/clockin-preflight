package com.clockin.mwadriver

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.successPayload
import kotlinx.coroutines.launch
import java.io.File

/**
 * dApp side of the MWA proof. Calls exactly the API the CLOCK IN app will call:
 *
 *     MobileWalletAdapter(ConnectionIdentity(identityUri, iconUri, identityName))
 *         .transact(ActivityResultSender(activity)) { signTransactions(payloads) }
 *
 * The default AssociationScenarioProvider makes this a LOCAL association:
 * the driver listens on ws://127.0.0.1:<port>/solana-wallet and the wallet dials in.
 *
 * Result is logged under TAG and also written to
 * /sdcard/Android/data/com.clockin.mwadriver/files/mwa-result.json for pullable evidence.
 */
class DriverActivity : ComponentActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 13f
            setPadding(32, 48, 32, 32)
        }
        setContentView(ScrollView(this).apply { addView(status) })
        render("MWA driver started.\nStarting association…")
        lifecycleScope.launch { runSession() }
    }

    private fun render(text: String) {
        status.text = text
        Log.i(TAG, text)
    }

    private suspend fun runSession() {
        val lines = mutableListOf<String>()
        val t0 = System.currentTimeMillis()

        val mwa = MobileWalletAdapter(
            connectionIdentity = ConnectionIdentity(
                identityUri = Uri.parse("https://clockin.local"),
                // MUST be RELATIVE. An absolute icon URI makes
                // MobileWalletAdapterClient.authorize() throw
                // IllegalArgumentException("If non-null, iconRelativeUri must be a relative Uri"),
                // which MobileWalletAdapter.associate() does NOT catch -> the app crashes.
                iconUri = Uri.parse("icon.png"),
                identityName = "CLOCK IN Pre-Flight"
            )
        )
        val sender = ActivityResultSender(this)

        val result = try {
            mwa.transact(sender) { authResult ->
            val tAuthorized = System.currentTimeMillis()
            val pubkey = authResult.accounts[0].publicKey
            lines += "STAGE authorize=OK  +${tAuthorized - t0}ms"
            lines += "  authToken=${authResult.authToken.take(16)}… (${authResult.authToken.length} chars)"
            lines += "  walletUriBase=${authResult.walletUriBase}"
            lines += "  accounts=${authResult.accounts.size}"
            lines += "  account[0].publicKey=${b64(pubkey)} (${pubkey.size} bytes)"
            lines += "  account[0].label=${authResult.accounts[0].accountLabel}"
            lines += "  account[0].chains=${authResult.accounts[0].chains?.joinToString()}"

            val tBeforeSign = System.currentTimeMillis()
            val signResult = signTransactions(arrayOf(TX_LEGACY))
            val tAfterSign = System.currentTimeMillis()
            lines += "STAGE sign_transactions=OK  +${tAfterSign - tBeforeSign}ms"

            val signedTx = signResult.signedPayloads[0]
            lines += "  request payload = ${TX_LEGACY.size} bytes (legacy Solana tx, 1 sig slot)"
            lines += "  response payload = ${signedTx.size} bytes"

            val msgOffset = messageOffset(TX_LEGACY)
            val message = TX_LEGACY.copyOfRange(msgOffset, TX_LEGACY.size)
            val signature = signedTx.copyOfRange(msgOffset - 64, msgOffset)
            // Rebuild "TX_LEGACY with signature slot 0 filled in" and compare to what came back.
            val restored = TX_LEGACY.copyOf()
            System.arraycopy(signature, 0, restored, msgOffset - 64, signature.size)

            lines += "  message = ${message.size} bytes, messageOffset=$msgOffset"
            lines += "  signature = ${signature.size} bytes: ${hex(signature)}"
            lines += "  signature slot wrote back byte-identical to declared tx = " +
                    "${restored.contentEquals(signedTx)}"

            val verified = verify(pubkey, message, signature)
            lines += "STAGE verify_ed25519=${if (verified) "PASS" else "FAIL"}"

            SessionReport(
                authorized = true,
                pubkeyB64 = b64(pubkey),
                authToken = authResult.authToken,
                txBytes = TX_LEGACY.size,
                messageBytes = message.size,
                signatureHex = hex(signature),
                signatureVerified = verified,
                signatureSlotIdentical = restored.contentEquals(signedTx),
                authorizeMs = tAuthorized - t0,
                signMs = tAfterSign - tBeforeSign,
                totalMs = System.currentTimeMillis() - t0
            )
            } // closes the transact { } trailing lambda
        } catch (e: Exception) { // closes the try
            // MobileWalletAdapter.associate() only catches a fixed set of exception types; anything
            // else (e.g. IllegalArgumentException from a bad ConnectionIdentity) escapes and would
            // crash the host app. Log it instead so the harness always produces a verdict.
            lines += "RESULT MWA_SESSION=THREW ${e::class.java.name}: ${e.message}"
            e.stackTrace.take(8).forEach { lines += "    at $it" }
            TransactionResult.Failure<SessionReport>(e.message ?: e::class.java.name, e)
        }

        val report = when (result) {
            is TransactionResult.Success -> {
                lines += "RESULT MWA_SESSION=PASS  total=${result.payload.totalMs}ms"
                result.authResult.let { lines += "  (auth result re-accessible: ${it.authToken.length} char token)" }
                result.successPayload
            }
            is TransactionResult.Failure -> {
                lines += "RESULT MWA_SESSION=FAIL message=${result.message}"
                lines += "  exception=${result.e::class.java.name}: ${result.e.message}"
                result.e.stackTrace.take(6).forEach { lines += "    at $it" }
                null
            }
            is TransactionResult.NoWalletFound -> {
                lines += "RESULT MWA_SESSION=NO_WALLET message=${result.message}"
                null
            }
        }

        val verdict = if (report != null && report.signatureVerified && report.signatureSlotIdentical)
            "PASS" else "FAIL"
        lines += "VERDICT $verdict"
        val text = lines.joinToString("\n")
        render(text)

        writeEvidence(verdict, text)
    }

    private fun writeEvidence(verdict: String, body: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            File(dir, "mwa-result.json").writeText(
                """
                {
                  "verdict": "$verdict",
                  "tag": "$TAG",
                  "log": ${quote(body)}
                }
                """.trimIndent()
            )
            Log.i(TAG, "evidence written to ${File(dir, "mwa-result.json").absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "could not write evidence file", e)
        }
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n") + "\""

    /** compact-u16 signature count at offset 0 gives the message offset. */
    private fun messageOffset(tx: ByteArray): Int {
        var offset = 0
        var count = 0
        var shift = 0
        while (true) {
            val b = tx[offset++].toInt() and 0xff
            count = count or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return offset + 64 * count
    }

    /** Rebuild an X.509 SubjectPublicKeyInfo from the raw 32-byte Solana key and verify. */
    private fun verify(rawPubkey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        Ed25519.verify(rawPubkey, message, signature)
    } catch (e: Exception) {
        Log.e(TAG, "verification threw", e)
        false
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    data class SessionReport(
        val authorized: Boolean,
        val pubkeyB64: String,
        val authToken: String,
        val txBytes: Int,
        val messageBytes: Int,
        val signatureHex: String,
        val signatureVerified: Boolean,
        val signatureSlotIdentical: Boolean,
        val authorizeMs: Long,
        val signMs: Long,
        val totalMs: Long,
    )

    companion object {
        const val TAG = "CLOCKIN-MWA-DRIVER"

        /**
         * A structurally valid legacy Solana transaction: 1 signature slot, 3 account keys
         * (signer, recipient, System Program), recent blockhash, one System Transfer of
         * 1_000_000 lamports. 215 bytes. Generated and checked offline.
         */
        val TX_LEGACY: ByteArray = Base64.decode(
            "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAABAAEDAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQF+jAiHYL/eHd3PMsF/" +
            "IJuCQu5SqvEx+s2I0OosbQsG8gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAYIdzxmst" +
            "RPoB9ENlALbJVspm83mhB3IMvU+HkoEliNsBAgIAAQwCAAAAQEIPAAAAAAA=",
            Base64.DEFAULT
        )
    }
}

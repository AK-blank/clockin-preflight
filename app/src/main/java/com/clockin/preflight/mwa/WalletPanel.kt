package com.clockin.preflight.mwa

import android.app.Activity
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clockin.preflight.ui.PreflightController
import com.clockin.preflight.ui.Theme
import com.clockin.preflight.ui.VerdictCard
import com.clockin.preflight.ui.VerdictView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Wallet tab: a real Mobile Wallet Adapter session, driven by this app.
 *
 * Order matters and is the product: the app **authorizes** a wallet, builds the payload from the
 * authorized key, runs the on-device pre-flight engine over the exact bytes, and only then asks the
 * wallet to sign — so the verdict is on screen before the signature exists. The returned signature
 * is verified locally (Ed25519) instead of being taken on faith.
 */
@Composable
fun WalletPanel() {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<WalletStage?>(null) }
    var reached by remember { mutableStateOf(listOf<WalletStage>()) }
    var outcome by remember { mutableStateOf<WalletOutcome?>(null) }
    var verdict by remember { mutableStateOf<VerdictView?>(null) }
    var verdictNote by remember { mutableStateOf<String?>(null) }
    var payloadBase64 by remember { mutableStateOf<String?>(null) }

    SectionCard(
        title = "Wallet hand-off — Mobile Wallet Adapter",
        body = "This app speaks MWA directly: it hands the wallet an association intent, dials the " +
            "wallet's localhost endpoint, opens the encrypted session, gets authorized — and then " +
            "pre-flights the payload before asking for a signature. Nothing here is simulated: the " +
            "signature below comes back over a real MWA session and is verified on-device.",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Payload: System transfer · 1,000,000 lamports (0.001 SOL) · sign-only",
                fontSize = 12.sp,
                color = Theme.Muted,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Signer is the wallet's own authorized key, so the transaction is built after " +
                    "authorize and pre-flighted before the wallet is asked to sign it.",
                fontSize = 12.sp,
                color = Theme.Muted,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            PanelButton(
                label = when {
                    busy -> "Wallet hand-off in progress…"
                    outcome is WalletOutcome.Signed -> "Run it again"
                    else -> "Connect wallet & request signature"
                },
                enabled = !busy && activity != null,
            ) {
                if (activity == null) return@PanelButton
                busy = true
                outcome = null
                verdict = null
                verdictNote = null
                payloadBase64 = null
                reached = emptyList()
                stage = null
                scope.launch {
                    val result = WalletSigner(activity).connectAndSign(
                        onStage = { next ->
                            stage = next
                            if (next != WalletStage.DONE && !reached.contains(next)) {
                                reached = reached + next
                            }
                        },
                        onPayloadReady = { payload ->
                            val base64 = Base64.encodeToString(payload, Base64.NO_WRAP)
                            payloadBase64 = base64
                            withContext(Dispatchers.Default) { PreflightController.analyze(base64) }
                                .onSuccess { verdict = it }
                                .onFailure { verdictNote = it.message ?: "Pre-flight failed." }
                        },
                    )
                    outcome = result
                    busy = false
                }
            }
            if (busy) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stage?.label ?: "Starting…",
                        fontSize = 12.sp,
                        color = Theme.Muted,
                    )
                }
            }
        }
    }

    if (reached.isNotEmpty() || outcome != null) {
        StageTimeline(reached = reached, active = if (busy) stage else null, done = outcome != null)
    }

    verdict?.let { VerdictCard(it) }
    verdictNote?.let { NoteCard("Pre-flight unavailable: $it", Theme.Caution) }

    when (val result = outcome) {
        is WalletOutcome.Signed -> SignedCard(result)
        is WalletOutcome.Failed -> NoteCard(
            "${result.message}\n\nFailed at stage: ${result.stage.label}\n${result.detail}",
            Theme.Danger,
        )
        is WalletOutcome.NoWallet -> NoteCard(result.message, Theme.Caution)
        null -> Unit
    }

    payloadBase64?.let { base64 ->
        Text(
            text = "payload sent to the wallet · ${base64.length} chars base64 · " +
                "${result_signatureHint(outcome)}",
            fontSize = 11.sp,
            color = Theme.Muted,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun result_signatureHint(outcome: WalletOutcome?): String = when (outcome) {
    is WalletOutcome.Signed -> if (outcome.signatureVerified) "signature VERIFIED" else "signature NOT verified"
    else -> "awaiting signature"
}

@Composable
private fun SignedCard(signed: WalletOutcome.Signed) {
    val accent = if (signed.signatureVerified) Theme.Safe else Theme.Danger
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(accent)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = if (signed.signatureVerified) "SIGNED — SIGNATURE VERIFIED" else "SIGNED — VERIFICATION FAILED",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = "${signed.walletLabel} · ${signed.cluster} · ${signed.totalMs} ms",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                )
            }
            Column(modifier = Modifier.padding(18.dp)) {
                Field("wallet", signed.walletLabel)
                Field("wallet public key", chunked(signed.walletPubkeyBase58))
                Field("auth token", "${signed.authTokenLength} chars · session v${signed.sessionVersion ?: "?"}")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "RETURNED SIGNATURE (64 bytes, base58)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Theme.Ink,
                )
                Text(
                    text = chunked(signed.signatureBase58),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Theme.Ink,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "hex",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Theme.Ink,
                )
                Text(
                    text = chunked(signed.signatureHex, 64),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Theme.Muted,
                    lineHeight = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                Field("signed bytes", "${signed.signedPayload.size} B response · ${signed.messageBytes} B message")
                Field("slot byte-identical", if (signed.signatureSlotMatches) "yes" else "no")
                Field(
                    "ed25519 over the message",
                    if (signed.signatureVerified) "verified on-device (BouncyCastle)" else "FAILED",
                )
                Field("timing", "authorize ${signed.authorizeMs} ms · sign ${signed.signMs} ms · session ${signed.totalMs} ms")
            }
        }
    }
}

@Composable
private fun StageTimeline(reached: List<WalletStage>, active: WalletStage?, done: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "MWA session timeline",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Theme.Ink,
            )
            Spacer(Modifier.height(8.dp))
            WalletStage.entries.filter { it != WalletStage.DONE }.forEach { entry ->
                val complete = done || entry in reached && entry != active
                val isActive = !done && entry == active
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                when {
                                    complete -> Theme.Safe
                                    isActive -> Theme.Ink
                                    else -> Color(0xFFD9DEE5)
                                },
                                RoundedCornerShape(4.dp),
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = entry.label,
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (complete || isActive) Theme.Ink else Theme.Muted,
                    )
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = label.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Theme.Muted,
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = Theme.Ink,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun NoteCard(message: String, accent: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(width = 6.dp, height = 18.dp)
                    .background(accent, RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(text = message, fontSize = 12.sp, color = Theme.Ink, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun SectionCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Theme.Ink)
            Spacer(Modifier.height(4.dp))
            Text(body, fontSize = 12.sp, color = Theme.Muted, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun PanelButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (enabled) Theme.Ink else Theme.Muted, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Wrap a long base58/hex string onto fixed-width lines so it is readable on a phone screen. */
private fun chunked(value: String, width: Int = 44): String =
    value.chunked(width).joinToString("\n")

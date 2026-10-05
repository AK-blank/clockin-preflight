package com.clockin.probe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clockin.preflight.mwa.WalletPanel
import com.clockin.preflight.ui.FindingView
import com.clockin.preflight.ui.PreflightController
import com.clockin.preflight.ui.ScreenScaffold
import com.clockin.preflight.ui.Severity
import com.clockin.preflight.ui.Theme
import com.clockin.preflight.ui.TokenRiskView
import com.clockin.preflight.ui.VerdictCard
import com.clockin.preflight.ui.VerdictView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CLOCK IN Pre-Flight — Android host.
 *
 * Pre-Flight runs the real on-device engine over a real transaction (fetched from mainnet by
 * signature, or pasted as base64). Authority Check queries live token-risk oracles for a mint.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

private enum class AppTab(val label: String) {
    PREFLIGHT("Pre-Flight"),
    AUTHORITY("Authority"),
    ADDRESS("Address"),
    WALLET("Wallet"),
}

@Composable
private fun App() {
    MaterialTheme {
        var tab by remember { mutableStateOf(AppTab.PREFLIGHT) }
        ScreenScaffold(
            title = "Pre-Flight",
            subtitle = "Know what you sign — before you sign it",
        ) {
            TabBar(selected = tab, onSelect = { tab = it })
            when (tab) {
                AppTab.PREFLIGHT -> PreflightTab()
                AppTab.AUTHORITY -> AuthorityTab()
                AppTab.ADDRESS -> AddressTab()
                AppTab.WALLET -> WalletPanel()
            }
        }
    }
}

@Composable
private fun TabBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppTab.entries.forEach { entry ->
            val active = entry == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (active) Theme.Ink else Color.Transparent,
                        RoundedCornerShape(11.dp),
                    )
                    .clickable { onSelect(entry) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.label,
                    color = if (active) Color.White else Theme.Muted,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun PreflightTab() {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<VerdictView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Pre-flight a transaction",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = Theme.Ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Paste a mainnet signature and we fetch the real transaction, or drop in a raw " +
                    "base64 transaction from a wallet's sign request.",
                fontSize = 12.sp,
                color = Theme.Muted,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))

            // One-tap samples. Two are live mainnet transactions fetched by signature; the drainer
            // case is a locally built fixture (labelled as such) because real traffic essentially
            // never contains an unlimited Approve.
            Text(
                text = "TRY A SAMPLE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Theme.Muted,
                letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SampleChip("Drainer approval") {
                    verdict = null
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            PreflightController.analyze(DRAINER_FIXTURE)
                        }
                        result
                            .onSuccess { verdict = it; error = null }
                            .onFailure { error = it.message ?: "Pre-flight failed."; verdict = null }
                    }
                }
                SampleChip("Real swap") {
                    input = REAL_SWAP_SIGNATURE
                    verdict = null
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            when (val resolved = PreflightController.resolveToBase64(REAL_SWAP_SIGNATURE)) {
                                is PreflightController.Resolved.Failure -> Result.failure(
                                    IllegalStateException(resolved.message),
                                )
                                is PreflightController.Resolved.Ok ->
                                    PreflightController.analyze(resolved.base64)
                            }
                        }
                        result
                            .onSuccess { verdict = it; error = null }
                            .onFailure { error = it.message ?: "Pre-flight failed."; verdict = null }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("signature or base64…", fontSize = 13.sp) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    label = if (busy) "Scanning…" else "Run pre-flight",
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) {
                    busy = true
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            when (val resolved = PreflightController.resolveToBase64(input)) {
                                is PreflightController.Resolved.Failure -> Result.failure(
                                    IllegalStateException(resolved.message),
                                )
                                is PreflightController.Resolved.Ok ->
                                    PreflightController.analyze(resolved.base64)
                            }
                        }
                        result
                            .onSuccess { verdict = it; error = null }
                            .onFailure { error = it.message ?: "Pre-flight failed."; verdict = null }
                        busy = false
                    }
                }
                GhostButton("Clear", Modifier.width(84.dp)) {
                    input = ""
                    verdict = null
                    error = null
                }
            }
            if (busy) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Reading the transaction from mainnet…", fontSize = 12.sp, color = Theme.Muted)
                }
            }
        }
    }

    error?.let { ErrorCard(it) }
    verdict?.let { VerdictCard(it) }
    if (verdict == null && error == null && !busy) {
        EmptyHint("Nothing scanned yet. Paste a mainnet signature and run a pre-flight.")
    }
}

@Composable
private fun AuthorityTab() {
    val scope = rememberCoroutineScope()
    var mint by remember { mutableStateOf("") }
    var risk by remember { mutableStateOf<TokenRiskView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    SectionCard(
        title = "Authority check",
        body = "A token you hold can still be frozen, re-minted, or have its metadata rewritten. " +
            "Paste a mint address to read its live authorities and Token-2022 extensions.",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = mint,
                onValueChange = { mint = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("mint address…", fontSize = 13.sp) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                label = if (busy) "Checking…" else "Check authorities",
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && mint.isNotBlank(),
            ) {
                busy = true
                error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        PreflightController.tokenAuthority(mint)
                    }
                    result
                        .onSuccess { risk = it; error = null }
                        .onFailure { error = it.message ?: "Lookup failed."; risk = null }
                    busy = false
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SampleChip("USDC") { mint = USDC_MINT }
                SampleChip("BONK") { mint = BONK_MINT }
                SampleChip("JUP") { mint = JUP_MINT }
            }
        }
    }

    error?.let { ErrorCard(it) }
    risk?.let { TokenRiskCard(it) }
    if (risk == null && error == null) {
        EmptyHint("No mint checked yet.")
    }
}

@Composable
private fun TokenRiskCard(risk: TokenRiskView) {
    val accent = Theme.color(risk.level)
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
                    text = risk.symbol ?: "TOKEN",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = risk.mint,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Column(modifier = Modifier.padding(18.dp)) {
                risk.score?.let {
                    Text("Risk score $it", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Theme.Ink)
                    Spacer(Modifier.height(10.dp))
                }
                if (risk.flags.isEmpty()) {
                    Text(
                        "No authority or extension risks reported by the oracles.",
                        fontSize = 14.sp,
                        color = Theme.Ink,
                    )
                }
                risk.flags.forEach { flag ->
                    Row(modifier = Modifier.padding(bottom = 10.dp)) {
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(9.dp)
                                .background(Theme.color(flag.severity), RoundedCornerShape(5.dp)),
                        )
                        Spacer(Modifier.width(11.dp))
                        Column {
                            Text(flag.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Theme.Ink)
                            Text(flag.plainEnglish, fontSize = 12.sp, color = Theme.Muted, lineHeight = 18.sp)
                            if (flag.evidence.isNotBlank()) {
                                Text(
                                    flag.evidence,
                                    fontSize = 10.sp,
                                    color = Theme.Muted.copy(alpha = 0.8f),
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
                if (risk.sources.isNotEmpty()) {
                    Text(
                        "Sources: " + risk.sources.joinToString(" · "),
                        fontSize = 11.sp,
                        color = Theme.Muted,
                    )
                }
                if (risk.errors.isNotBlank()) {
                    Text("Partial: ${risk.errors}", fontSize = 10.sp, color = Theme.Muted)
                }
            }
        }
    }
}

@Composable
private fun AddressTab() {
    SectionCard(
        title = "Address guard",
        body = "Address poisoning works by planting a lookalike address in your history so you copy " +
            "the wrong one next time. Recipients are compared against your own history.",
    )
    AlertRow(
        severity = Severity.DANGER,
        title = "Lookalike recipient detected",
        body = "7xKX…9f2Q matches a past recipient at 4 leading and 4 trailing characters, but it is " +
            "a different account and has never transacted with you.",
        code = "first seen 4 minutes ago · balance 0.002 SOL",
    )
    AlertRow(
        severity = Severity.CAUTION,
        title = "First-time recipient",
        body = "You have never sent to 3QqT…mV1 before. Verify it out of band before sending a large amount.",
        code = "no prior transfers · 12 days old",
    )
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
private fun AlertRow(severity: Severity, title: String, body: String, code: String) {
    val accent = Theme.color(severity)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 6.dp, height = 18.dp)
                        .background(accent, RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Theme.Ink)
            }
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 12.sp, color = Theme.Muted, lineHeight = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                code,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Theme.Muted.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFDECEC)),
    ) {
        Text(
            text = message,
            fontSize = 13.sp,
            color = Theme.Danger,
            modifier = Modifier.padding(14.dp),
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun SampleChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0xFFEDEFF3), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 12.sp, color = Theme.Ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(if (enabled) Theme.Ink else Theme.Muted, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GhostButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(Color.White, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Theme.Muted, fontSize = 14.sp)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = Theme.Muted,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

// Live mainnet swap (pump.fun AMM) — fetched by signature at pre-flight time, so nothing is baked in.
private const val REAL_SWAP_SIGNATURE =
    "48AegfbaBP3LMoNBV7t3VxutPmB4knHrsPtJeoCvCSikdKqmffo62z9MJvN6wCDQxXqnqEz3wWhX2XMXnv1aYncS"

// A locally built drainer-shaped transaction: unlimited SPL Token Approve (u64::MAX) plus a lure
// memo. Real traffic almost never contains this instruction, so it cannot be sourced from mainnet.
// Rebuild with: node tools/demo/build-drainer.mjs
private const val DRAINER_FIXTURE =
    "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAAMHfowIh2C/3h3dzzLBfyCbgkLuUqrxMfrNiNDqLG0LBvJnUgVcILPp2HRmVt33OFVQf4erbYdSPkx2p/o2CWqZ64Jo6amhREwrpcd6UZNoVrBy5D/vz/XksB6ZYjyOu3dJvCtXBl7x3WZUML5ga6ZZbAKVMBut74ta/EEBQVD0EnQG3fbh12Whk9nL4UbO63msHLSF7V9bN5E6jPWFfv8AqQVKU1qZKSEGTSTocWDaOHx8NbXdvJK7geQfqEBBBUSNAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwIEAwEDAAkE//////////8FAQAzQ0xBSU0gUkVXQVJEOiB2ZXJpZnkgYXQgY2xhaW0tcHJlZmxpZ2h0LXJld2FyZHMueHl6"

private const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
private const val BONK_MINT = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"
private const val JUP_MINT = "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN"

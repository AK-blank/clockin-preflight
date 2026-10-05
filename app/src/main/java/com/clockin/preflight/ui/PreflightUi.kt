package com.clockin.preflight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Presentation-only view model. The UI never touches engine internals: it renders plain data so the
 * risk engine and the data layer can land concurrently without coupling to this file.
 */
data class FindingView(
    val severity: Severity,
    val title: String,
    val plainEnglish: String,
    val evidence: String = "",
)

enum class Severity { INFO, CAUTION, DANGER }

data class VerdictView(
    val headline: String,
    val level: Severity,
    val score: Int,
    val summary: String,
    val findings: List<FindingView>,
    val sources: List<String> = emptyList(),
    val decodeFailed: Boolean = false,
)

/** Live token-level risk for one mint, merged from the reachable oracles. */
data class TokenRiskView(
    val mint: String,
    val symbol: String?,
    val score: Int?,
    val level: Severity,
    val flags: List<FindingView>,
    val sources: List<String> = emptyList(),
    val errors: String = "",
)

object Theme {
    val Danger = Color(0xFFD64545)
    val Caution = Color(0xFFE0A800)
    val Safe = Color(0xFF2E9E5B)
    val Ink = Color(0xFF10151C)
    val Muted = Color(0xFF6B7785)
    val Surface = Color(0xFFF6F7F9)

    fun color(severity: Severity): Color = when (severity) {
        Severity.DANGER -> Danger
        Severity.CAUTION -> Caution
        Severity.INFO -> Safe
    }

    fun label(severity: Severity): String = when (severity) {
        Severity.DANGER -> "STOP"
        Severity.CAUTION -> "CAUTION"
        Severity.INFO -> "CLEAR"
    }
}

@Composable
fun VerdictCard(verdict: VerdictView, modifier: Modifier = Modifier) {
    val accent = Theme.color(verdict.level)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            // Verdict banner — the single glanceable answer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(accent)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    text = Theme.label(verdict.level),
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = verdict.headline,
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Column(modifier = Modifier.padding(20.dp)) {
                ScoreRow(score = verdict.score, accent = accent)
                Spacer(Modifier.height(14.dp))
                Text(
                    text = verdict.summary,
                    fontSize = 16.sp,
                    color = Theme.Ink,
                    lineHeight = 23.sp,
                )

                if (verdict.findings.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = "WHAT WE FOUND",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Theme.Muted,
                        letterSpacing = 1.2.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    verdict.findings.forEach { finding ->
                        FindingRow(finding)
                        Spacer(Modifier.height(10.dp))
                    }
                }

                if (verdict.sources.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Signal sources: " + verdict.sources.joinToString(" · "),
                        fontSize = 11.sp,
                        color = Theme.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreRow(score: Int, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 6.dp, height = 34.dp)
                .background(accent, RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Risk $score",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Theme.Ink,
        )
        Spacer(Modifier.width(6.dp))
        Text(text = "/100", fontSize = 13.sp, color = Theme.Muted)
    }
}

@Composable
private fun FindingRow(finding: FindingView) {
    val accent = Theme.color(finding.severity)
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(9.dp)
                .background(accent, RoundedCornerShape(5.dp)),
        )
        Spacer(Modifier.width(11.dp))
        Column {
            Text(
                text = finding.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Theme.Ink,
            )
            Text(
                text = finding.plainEnglish,
                fontSize = 13.sp,
                color = Theme.Muted,
                lineHeight = 19.sp,
            )
            if (finding.evidence.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = finding.evidence,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Theme.Muted.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Theme.Surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Theme.Ink)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}

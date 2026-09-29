package com.schindler.k2m

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Reference-palette slot 1 (blue), stepped per mode; both validated >= 3:1 against Material 3's surfaces.
private val ACCENT_LIGHT = Color(0xFF2A78D6)
private val ACCENT_DARK = Color(0xFF3987E5)
private val HAIRLINE_LIGHT = Color(0xFFE1E0D9)
private val HAIRLINE_DARK = Color(0xFF2C2C2A)

/**
 * Stat tile for the live transfer rate: the current rate as the headline, average/peak/total beside it, and a
 * one-minute sparkline (one series, so no legend; the label names it). Drag across the line to read any moment.
 */
@Composable
fun TransferTile(stats: TransferStats, bytesLeft: Long? = null, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val accent = if (dark) ACCENT_DARK else ACCENT_LIGHT
    val hairline = if (dark) HAIRLINE_DARK else HAIRLINE_LIGHT
    val surface = MaterialTheme.colorScheme.surface
    var scrub by remember { mutableStateOf<Int?>(null) }
    val history = stats.history
    val selected = scrub?.takeIf { it in history.indices }
    val headline = selected?.let { history[it] } ?: stats.bytesPerSec
    val ago = selected?.let { (history.size - 1 - it) * RateMeter.SAMPLE_MS / 1000.0 }

    Column(modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics {
        contentDescription = "Transfer rate ${formatRate(stats.bytesPerSec)}, average ${formatRate(stats.average)}, " +
            "peak ${formatRate(stats.peak)}, ${formatBytes(stats.totalBytes)} transferred"
    }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(if (ago == null) "Transfer rate" else "Transfer rate, %.1f s ago".format(ago),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatRate(headline), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("avg ${formatRate(stats.average)} · peak ${formatRate(stats.peak)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val recent = stats.history.takeLast(20).average().takeIf { it > 0 } ?: stats.average
                Text("${formatBytes(stats.totalBytes)} done" + (bytesLeft?.takeIf { it > 0 }?.let {
                    " · ${formatBytes(it)} left · ~${formatDuration((it / recent).toLong())}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(44.dp).padding(top = 4.dp)
                .pointerInput(history.size) {
                    fun pick(x: Float) = if (history.size < 2) null else
                        (x / size.width * (history.size - 1)).toInt().coerceIn(0, history.size - 1)
                    detectTapGestures(onPress = { scrub = pick(it.x); tryAwaitRelease(); scrub = null })
                }
                .pointerInput(history.size) {
                    fun pick(x: Float) = if (history.size < 2) null else
                        (x / size.width * (history.size - 1)).toInt().coerceIn(0, history.size - 1)
                    detectDragGestures(onDragEnd = { scrub = null }, onDragCancel = { scrub = null }) { change, _ ->
                        scrub = pick(change.position.x)
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val dotR = 4.dp.toPx()                       // 8 dp marker
            val top = dotR + 2.dp.toPx()                 // room for the marker and its ring
            val base = h - 1.dp.toPx()
            // Recessive baseline only: a sparkline needs no grid or axis.
            drawLine(hairline, Offset(0f, base), Offset(w, base), strokeWidth = 1.dp.toPx())
            if (history.size < 2) return@Canvas
            val max = maxOf(stats.peak, 1.0) * 1.1        // headroom so the peak isn't flush with the top
            fun x(i: Int) = i * (w - dotR * 2) / (history.size - 1) + dotR
            fun y(v: Double) = (base - (v / max) * (base - top)).toFloat()
            val line = Path().apply {
                moveTo(x(0), y(history[0]))
                for (i in 1 until history.size) lineTo(x(i), y(history[i]))
            }
            val area = Path().apply {
                addPath(line)
                lineTo(x(history.size - 1), base)
                lineTo(x(0), base)
                close()
            }
            drawPath(area, accent.copy(alpha = 0.10f))    // a wash, never a solid block
            drawPath(line, accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val i = selected ?: (history.size - 1)
            if (selected != null) drawLine(hairline, Offset(x(i), top), Offset(x(i), base), strokeWidth = 1.dp.toPx())
            // Marker with a surface ring, so it stays distinct where it sits on the line.
            drawCircle(surface, radius = dotR + 2.dp.toPx(), center = Offset(x(i), y(history[i])))
            drawCircle(accent, radius = dotR, center = Offset(x(i), y(history[i])))
        }
    }
}

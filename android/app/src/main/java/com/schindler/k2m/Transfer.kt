package com.schindler.k2m

import java.util.concurrent.atomic.AtomicLong

/** The live transfer rate shown in the app: current, history for the sparkline, totals. */
data class TransferStats(
    val bytesPerSec: Double,
    /** Bytes per second per sample, oldest first ([RateMeter.SAMPLE_MS] apart, up to a minute). */
    val history: List<Double>,
    val totalBytes: Long,
    val average: Double,
    val peak: Double,
)

/**
 * Counts bytes moved by uploads, downloads and X4 copies. [add] is cheap and thread-safe (called per
 * 64 KB chunk or per file); [tick], run by [SyncService] every [SAMPLE_MS] while it works, turns the
 * count into a rate and publishes it on [TaskBus.transfer].
 */
object RateMeter {
    const val SAMPLE_MS = 500L
    private const val SAMPLES = 120  // one minute
    private val pending = AtomicLong(0)
    private val history = ArrayDeque<Double>()
    private var total = 0L
    private var activeSamples = 0

    fun add(bytes: Long) { if (bytes > 0) pending.addAndGet(bytes) }

    @Synchronized fun reset() {
        pending.set(0); history.clear(); total = 0; activeSamples = 0
        TaskBus.transfer.value = null
    }

    @Synchronized fun tick() {
        val bytes = pending.getAndSet(0)
        total += bytes
        if (total == 0L) return  // nothing moved yet: no tile
        val rate = bytes * 1000.0 / SAMPLE_MS
        history.addLast(rate)
        while (history.size > SAMPLES) history.removeFirst()
        activeSamples++
        // The headline number is smoothed over the last two seconds: per-half-second counts jump with
        // file boundaries, which reads as noise.
        val recent = history.takeLast(4)
        TaskBus.transfer.value = TransferStats(
            bytesPerSec = recent.average(),
            history = history.toList(),
            totalBytes = total,
            average = total * 1000.0 / (activeSamples * SAMPLE_MS),
            peak = history.maxOrNull() ?: 0.0,
        )
    }
}

/** "2.4 MB/s", "640 KB/s". */
fun formatRate(bytesPerSec: Double): String =
    if (bytesPerSec >= 1_000_000) "%.1f MB/s".format(bytesPerSec / 1_000_000) else "%.0f KB/s".format(bytesPerSec / 1_000)

fun formatBytes(bytes: Long): String =
    if (bytes >= 1_000_000_000) "%.2f GB".format(bytes / 1e9) else if (bytes >= 1_000_000) "%.1f MB".format(bytes / 1e6)
    else "%.0f KB".format(bytes / 1e3)

/** "~45 s", "~12 min", "~1 h 20 min". */
fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${maxOf(seconds, 1)} s"
    seconds < 3600 -> "${(seconds + 30) / 60} min"
    else -> "${seconds / 3600} h ${(seconds % 3600 + 30) / 60} min"
}

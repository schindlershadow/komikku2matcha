package com.schindler.k2m

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.concurrent.atomic.AtomicInteger

/** Notification channels and the "finished" notification, shared by [SyncService] and auto-sync. */
object Notify {
    const val CH_PROGRESS = "progress"
    const val CH_RESULTS = "results"
    private val nextId = AtomicInteger((System.currentTimeMillis() % 100_000).toInt() + 100)

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_RESULTS, "Finished jobs", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun progress(ctx: Context, text: String, cur: Int, max: Int, cancellable: Boolean = false): Notification =
        Notification.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle("Komikku → Matcha").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp(ctx))
            .setProgress(max, cur, max == 0)
            .apply {
                if (cancellable) addAction(Notification.Action.Builder(null, "Cancel", PendingIntent.getService(ctx, 1,
                    Intent(ctx, SyncService::class.java).setAction(SyncService.ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)).build())
            }
            .build()

    fun result(ctx: Context, title: String, text: String, error: Boolean = false) {
        createChannels(ctx)
        ctx.getSystemService(NotificationManager::class.java).notify(nextId.incrementAndGet(),
            Notification.Builder(ctx, CH_RESULTS)
                .setSmallIcon(R.drawable.ic_notify).setContentTitle(title)
                .setContentText(text.lineSequence().first())
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(openApp(ctx))
                .apply { if (error) setColor(0xFFC62828.toInt()) }
                .build())
    }

    /** A notification that opens [intent] when tapped (e.g. the installer's confirmation screen). */
    fun action(ctx: Context, title: String, text: String, intent: Intent) {
        createChannels(ctx)
        ctx.getSystemService(NotificationManager::class.java).notify(nextId.incrementAndGet(),
            Notification.Builder(ctx, CH_RESULTS).setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(ctx, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build())
    }

    /** Title and text for a finished server job. */
    fun jobResult(what: String, j: JobInfo): Triple<String, String, Boolean> {
        val count = j.outcomes.groupingBy { it.status }.eachCount()
        val summary = listOf("converted", "pushed", "skipped", "failed")
            .mapNotNull { s -> count[s]?.let { "$it $s" } }.joinToString(", ").ifEmpty { j.status }
        val details = (j.outcomes.filter { it.status == "failed" }.map { "✗ ${it.label}: ${it.detail}" } +
            j.outcomes.filter { it.warning.isNotEmpty() }.map { "⚠ ${it.label}: ${it.warning}" } +
            listOfNotNull(j.error)).joinToString("\n")
        return Triple("$what ${if (j.status == "done") "finished" else j.status.replace('_', ' ')}",
            summary + if (details.isNotEmpty()) "\n$details" else "", j.status != "done")
    }
}

/** What a sync of [titles] has to do: upload new/re-downloaded chapters, convert everything not converted. */
fun syncPlan(titles: List<TitleRow>): Task.Sync? = chapterSyncPlan(titles.flatMap { it.chapters })

/** As above, for a hand-picked set of chapters (e.g. checkboxes in the Library list) rather than whole titles.
 *  A separate name, not an overload: List<ChapterRow> and List<TitleRow> erase to the same JVM signature. */
fun chapterSyncPlan(rows: List<ChapterRow>): Task.Sync? {
    val wanted = rows.filter { !it.ignored }
    val uploads = wanted.filter { it.state == "new" || it.state == "changed" }.mapNotNull { it.phone }
    val convert = wanted.filter { it.state == "pending" }.map { it.serverKey }
    return if (uploads.isEmpty() && convert.isEmpty()) null else Task.Sync(uploads, convert)
}

/** Upload the plan's chapters and start converting; returns the server job (null if nothing to convert). */
suspend fun uploadAndStart(ctx: Context, api: Api, task: Task.Sync, show: (String, Int, Int) -> Unit): JobInfo? {
    val keys = task.convert.toMutableList()
    task.uploads.forEachIndexed { i, ch ->
        val label = "Uploading ${i + 1}/${task.uploads.size}: ${ch.file}"
        var last = 0L
        api.upload(ctx.contentResolver, Uri.parse(ch.uri), ch.size, ch.title, ch.file) { sent ->
            RateMeter.add(sent - last)
            last = sent
            show(label, (sent * 100 / maxOf(ch.size, 1)).toInt(), 100)
        }
        keys += "${ch.title}/${ch.file}"
    }
    if (keys.isEmpty()) return null
    show("Starting conversion…", 0, 0)
    return api.startJob(keys.distinct())
}

package com.schindler.k2m

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Automatic sync: every few hours, scan Komikku's folder, upload what's new and start converting it.
 *
 * With "home network only" the server is only tried at its home (LAN) URL, so away from home the run
 * ends quietly without touching mobile data. The conversion itself is followed by [JobWatchWorker],
 * a short check every couple of minutes, rather than by keeping this worker alive for its whole
 * length: Android limits long-running background work, and a check costs almost nothing.
 */
object AutoSync {
    private const val PERIODIC = "autosync"

    fun schedule(ctx: Context) {
        val prefs = Prefs(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (!prefs.autoSync) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (prefs.autoSyncHomeOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(prefs.autoSyncCharging)
            .setRequiresBatteryNotLow(true)
            .build()
        val req = PeriodicWorkRequestBuilder<AutoSyncWorker>(prefs.autoSyncHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Run one auto-sync now (the "Sync now" button), with the same rules. */
    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("autosync-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoSyncWorker>()
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES).build())
    }

    fun watchJob(ctx: Context, jobId: String, homeOnly: Boolean) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("watch-$jobId", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<JobWatchWorker>()
                .setInitialDelay(2, TimeUnit.MINUTES)
                .setInputData(workDataOf("job" to jobId, "homeOnly" to homeOnly))
                .build())
    }

    fun stamp(prefs: Prefs, what: String) {
        prefs.autoSyncLast = "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())}: $what"
    }
}

class AutoSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val tree = prefs.komikkuTree
        if (tree == null || prefs.token.isBlank()) return Result.success()
        val api = Api(if (prefs.autoSyncHomeOnly) prefs.homeOnly() else prefs)
        try {
            api.baseUrl()
        } catch (e: ApiException) {
            AutoSync.stamp(prefs, if (prefs.autoSyncHomeOnly) "skipped, not on the home network" else "skipped, server not reachable")
            return Result.success()
        }
        checkForUpdate(api, prefs)
        return try {
            val phone = KomikkuScanner.scan(applicationContext, Uri.parse(tree))
            val (server, ignored) = api.libraryWithIgnored()
            val plan = syncPlan(mergeLibrary(phone, server, ignored))
            if (plan == null) {
                AutoSync.stamp(prefs, "nothing new")
                return Result.success()
            }
            // Best effort: a foreground notification lets uploads outlive the usual 10-minute limit.
            // Android 12+ may refuse it from the background; then we just carry on, and anything not
            // uploaded in time goes up on the next run.
            runCatching { setForeground(foreground("Auto-sync: checking…", 0, 0)) }
            val job = uploadAndStart(applicationContext, api, plan) { text, cur, max ->
                runCatching { setForegroundAsync(foreground("Auto-sync: $text", cur, max)) }
            }
            if (job != null) {
                AutoSync.watchJob(applicationContext, job.id, prefs.autoSyncHomeOnly)
                AutoSync.stamp(prefs, "uploaded ${plan.uploads.size}, converting ${plan.uploads.size + plan.convert.size}")
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Android stopped the run (WiFi dropped, background time up): not an error. Anything already uploaded
            // stays on the server and isn't uploaded again; the rest goes next time.
            AutoSync.stamp(prefs, "interrupted; continues next run")
            throw e
        } catch (e: Exception) {
            // Network trouble (the phone left WiFi, the server restarted): try again with backoff, and only
            // bother the user if it keeps failing.
            AutoSync.stamp(prefs, "failed (attempt ${runAttemptCount + 1}): ${e.message}")
            if (runAttemptCount < 2) return Result.retry()
            Notify.result(applicationContext, "Auto-sync keeps failing",
                "${e.message ?: e.javaClass.simpleName}\nIt will try again at the next scheduled run.", error = true)
            Result.success()
        }
    }

    /** A newer app on the server: install it (Android 12+ can do that unattended once this app installed
     *  itself), or at least say so. */
    private suspend fun checkForUpdate(api: Api, prefs: Prefs) {
        val info = runCatching { Updater.check(applicationContext, api) }.getOrNull() ?: return
        if (prefs.autoUpdate && Updater.canInstall(applicationContext)) {
            runCatching { Updater.install(applicationContext, api, info) }
        } else {
            Notify.action(applicationContext, "Update ${info.versionName} available", "Open the app to install it",
                android.content.Intent(applicationContext, MainActivity::class.java))
        }
    }

    private fun foreground(text: String, cur: Int, max: Int): ForegroundInfo {
        Notify.createChannels(applicationContext)
        val n = Notify.progress(applicationContext, text, cur, max)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object { private const val NOTIFICATION_ID = 2 }
}

/** Checks a server job once; reschedules itself while it runs, notifies when it's done. */
class JobWatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("job") ?: return Result.success()
        val homeOnly = inputData.getBoolean("homeOnly", true)
        val prefs = Prefs(applicationContext)
        val tries = inputData.getInt("tries", 0)
        val job = try {
            Api(if (homeOnly) prefs.homeOnly() else prefs).job(id)
        } catch (e: ApiException) {
            // Out of reach (left home?): keep checking for about a day, then give up quietly.
            if (tries < 720) reschedule(id, homeOnly, tries + 1)
            return Result.success()
        }
        if (job.active) {
            reschedule(id, homeOnly, 0)
        } else {
            val (title, text, error) = Notify.jobResult("Auto-sync conversion", job)
            Notify.result(applicationContext, title, text, error)
            AutoSync.stamp(prefs, title.lowercase().replaceFirstChar { it.uppercase() })
        }
        return Result.success()
    }

    private fun reschedule(id: String, homeOnly: Boolean, tries: Int) {
        // A plain enqueue: replacing this worker's own unique name would cancel it while it runs.
        WorkManager.getInstance(applicationContext).enqueue(
            OneTimeWorkRequestBuilder<JobWatchWorker>()
                .setInitialDelay(2, TimeUnit.MINUTES)
                .setInputData(workDataOf("job" to id, "homeOnly" to homeOnly, "tries" to tries))
                .build())
    }
}

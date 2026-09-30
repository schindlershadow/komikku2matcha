package com.schindler.k2m

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Work the service runs, in order. */
sealed class Task {
    /** Upload chapters that are new/changed on the phone, then convert [convert] (server keys) plus the uploads. */
    data class Sync(val uploads: List<PhoneChapter>, val convert: List<String>) : Task()
    data class Watch(val jobId: String, val what: String) : Task()
    /** [dests]: server book path → where it already is on the X4 (to replace in place); default /<path>. */
    data class PhonePush(val device: String, val books: List<String>, val replace: Boolean,
                         val dests: Map<String, String> = emptyMap(), val titles: Map<String, String> = emptyMap()) : Task()
    /**
     * Fix broken chapters: re-convert [reconvert] (source chapter keys) on the server, then, with a
     * [device], replace [books] on the X4 (in place, per [dests]) from the phone.
     */
    data class Repair(val reconvert: List<String>, val books: List<String>, val device: String?,
                      val dests: Map<String, String>, val titles: Map<String, String>,
                      val deleteOnX4: List<String> = emptyList(),
                      /** Chapters for a normal (not from-scratch) run: other screen, no cover page, cover redraw. */
                      val convert: List<String> = emptyList(),
                      /** Title folders to look series art up for first. */
                      val fetchArt: List<String> = emptyList(),
                      /** Of [books], those replaced whole on the X4; the rest update in place (e.g. just the cover). */
                      val replaceWhole: Set<String> = emptySet()) : Task()
    /** Download books onto the phone, to send to the X4 later without internet. */
    data class SaveOffline(val books: List<String>) : Task()
    data class ServerPush(val device: String, val books: List<String>, val replace: Boolean,
                          val dests: Map<String, String> = emptyMap()) : Task()
    /** Copy already-read EPUB files straight to [device] (or the SD card): no server, no conversion.
     *  The firmware discovers any .epub anywhere on the card, so these just land under /books/[folder]
     *  ([folder] blank for loose files). A shared folder is what makes the firmware group them into
     *  one shelf (CoverLibraryActivity::loadShelves groups books by their containing folder). */
    data class SendEpub(val device: String, val files: List<Pair<String, ByteArray>>, val folder: String = "") : Task()
    /** Convert chapters right on the phone (see [PhoneManga]) into the local book cache -- the
     *  no-server fallback, not a replacement for a real Sync. Needs no X4/card connectivity at all;
     *  send the result over with a normal [PhonePush] whenever one's reachable. */
    data class PhoneConvert(val chapters: List<PhoneChapter>) : Task()
}

/** In-process hand-off between the UI and [SyncService]. */
object TaskBus {
    val queue = ConcurrentLinkedQueue<Task>()
    /** Bytes still to download/send in the current transfer (for "MB left" and the estimate); null otherwise. */
    val bytesLeft = MutableStateFlow<Long?>(null)
    /** What the service is doing right now, for the UI; null when idle. */
    val status = MutableStateFlow<String?>(null)
    /** Live transfer rate while bytes are moving (null otherwise). */
    val transfer = MutableStateFlow<TransferStats?>(null)
    /** Fires when a task finishes, so screens can refresh. */
    val finished = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    fun enqueue(ctx: Context, task: Task) {
        queue += task
        ctx.startForegroundService(Intent(ctx, SyncService::class.java))
    }

    /** Stop the running task and drop queued ones (a server job being watched is cancelled too). */
    fun cancel(ctx: Context) {
        queue.clear()
        ctx.startService(Intent(ctx, SyncService::class.java).setAction(SyncService.ACTION_CANCEL))
    }
}

class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var running = false
    private var lastStartId = 0
    @Volatile private var currentTask: kotlinx.coroutines.Job? = null
    /** The server job being watched, so Cancel can stop it on the server as well. */
    @Volatile private var serverJob: String? = null
    private lateinit var api: Api
    private lateinit var device: DeviceClient
    private lateinit var cache: BookCache
    private lateinit var pushedStore: PushedStore
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        api = Api(Prefs(this))
        device = DeviceClient(this)
        cache = BookCache(java.io.File(filesDir, "books"))
        pushedStore = PushedStore(java.io.File(filesDir, "x4-pushed"))
        nm = getSystemService(NotificationManager::class.java)
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            TaskBus.queue.clear()
            currentTask?.cancel()
            serverJob?.let { id -> scope.launch { runCatching { api.cancel(id) } } }
            if (!running) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val n = progress("Starting…", 0, 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID_PROGRESS, n)
        synchronized(lock) {
            lastStartId = startId
            if (!running) {
                running = true
                scope.launch { drain() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        TaskBus.status.value = null
        super.onDestroy()
    }

    private suspend fun drain() {
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "k2m:sync")
        @Suppress("DEPRECATION")
        val wifi = (applicationContext.getSystemService(WifiManager::class.java))
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "k2m:sync")
        wake.acquire(6 * 60 * 60 * 1000L)
        wifi.acquire()
        RateMeter.reset()
        val ticker = scope.launch { while (true) { delay(RateMeter.SAMPLE_MS); RateMeter.tick() } }
        try {
            while (true) {
                val task = TaskBus.queue.poll() ?: synchronized(lock) {
                    // Nothing left: stop, unless a start arrived meanwhile (stopSelfResult checks that).
                    if (TaskBus.queue.isEmpty()) {
                        running = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(lastStartId)
                        null
                    } else TaskBus.queue.poll()
                } ?: break
                val job = scope.launch {
                    try {
                        run(task)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        result("Cancelled", "Stopped by you." + (cancelNote ?: ""))
                    } catch (e: Exception) {
                        result("Failed", e.message ?: e.javaClass.simpleName, error = true)
                    }
                }
                currentTask = job
                cancelNote = null
                job.join()
                currentTask = null
                TaskBus.finished.tryEmit(Unit)
            }
        } finally {
            ticker.cancel()
            RateMeter.reset()
            TaskBus.status.value = null
            if (wifi.isHeld) wifi.release()
            if (wake.isHeld) wake.release()
        }
    }

    private suspend fun run(task: Task) {
        when (task) {
            is Task.Sync -> {
                val job = uploadAndStart(this, api, task, ::show)
                    ?: return result("Nothing to convert", "Everything is already converted")
                watch(job.id, "Conversion")
            }
            is Task.Watch -> watch(task.jobId, task.what)
            is Task.ServerPush -> {
                show("Looking for the X4…", 0, 0)
                val ip = resolveX4(task.device) ?: return result("Can't find the X4",
                    "Start File Transfer on the X4; it must be on your home WiFi to send from the server.", error = true)
                show("Asking the server to send ${task.books.size} book(s)…", 0, 0)
                val job = api.serverPush(ip, task.books, task.replace, task.dests, sleep = Prefs(this).sleepSend)
                watch(job.id, "Send to X4 (from server)", task.dests)
            }
            is Task.PhonePush -> phonePush(task)
            is Task.SendEpub -> sendEpub(task)
            is Task.PhoneConvert -> phoneConvert(task)
            is Task.Repair -> {
                if (task.fetchArt.isNotEmpty()) {
                    show("Looking for series art…", 0, 0)
                    runCatching { api.fetchMissingCovers(task.fetchArt) }
                }
                if (task.reconvert.isNotEmpty()) {
                    show("Re-converting ${task.reconvert.size} chapter(s)…", 0, 0)
                    watch(api.startJob(task.reconvert, force = true).id, "Re-conversion")
                }
                if (task.convert.isNotEmpty()) {
                    show("Updating ${task.convert.size} chapter(s)…", 0, 0)
                    watch(api.startJob(task.convert).id, "Update")
                }
                // Changed books must be fetched again, not sent from an old saved copy.
                if (task.reconvert.isNotEmpty() || task.convert.isNotEmpty()) task.books.forEach { cache.remove(it) }
                val ops = if (task.device != null) resolveTarget(task.device) else null
                if (task.device != null && ops == null) return result(if (task.device == CARD) "Can't open the SD card" else "Can't find the X4",
                    "The chapters were re-converted; send them once the X4 (or its card) is reachable.", error = true)
                // The address/card found now is what the sends below use.
                val dev = if (task.device == CARD) CARD else Prefs(this).lastX4
                if (ops != null && task.deleteOnX4.isNotEmpty()) {
                    show("Deleting ${task.deleteOnX4.size} leftover folder(s) from ${ops.storage.label}…", 0, 0)
                    val gone = ops.deleteBooks(task.deleteOnX4) { }
                    DeviceScanStore(Prefs(this)).forget(gone)
                    if (task.books.isEmpty()) result("X4 cleaned up", "Deleted ${gone.size} incomplete folder(s)")
                }
                val (whole, inPlace) = task.books.partition { it in task.replaceWhole }
                if (ops != null && whole.isNotEmpty()) phonePush(Task.PhonePush(dev, whole, replace = true, task.dests, task.titles))
                if (ops != null && inPlace.isNotEmpty()) phonePush(Task.PhonePush(dev, inPlace, replace = false, task.dests, task.titles))
            }
            is Task.SaveOffline -> {
                val (ready, failed) = saveBooks(task.books)
                result(if (failed.isEmpty()) "Saved on the phone" else "Saving had errors",
                    "${ready.size} book(s) ready to send to the X4 without internet" +
                        if (failed.isNotEmpty()) "\n" + failed.joinToString("\n") else "", error = failed.isNotEmpty())
            }
        }
    }

    /** Poll a server job until it ends; network hiccups (e.g. leaving home WiFi) are retried for a while. */
    private suspend fun watch(id: String, what: String, dests: Map<String, String> = emptyMap()) {
        serverJob = id
        try { watchLoop(id, what, dests) } finally { serverJob = null }
    }

    private suspend fun watchLoop(id: String, what: String, dests: Map<String, String>) {
        var failures = 0
        while (true) {
            val j = try {
                api.job(id).also { failures = 0 }
            } catch (e: ApiException) {
                if (++failures > 100) throw e  // ~10 minutes without the server
                show("$what: waiting for the server…", 0, 0)
                delay(6000)
                continue
            }
            if (!j.active) {
                if (j.kind == "push") DeviceScanStore(Prefs(this)).markOnDevice(
                    j.outcomes.filter { it.status == "pushed" || it.status == "skipped" }
                        .associate { it.label to (dests[it.label] ?: it.label) }, api)
                val (title, text, error) = Notify.jobResult(what, j)
                return result(title, text, error)
            }
            val total = j.total ?: 0
            val current = j.current?.let { c -> ": ${shortName(c)}" + (j.currentPages?.let { " ($it pages)" } ?: "") } ?: ""
            val pages = j.pagesTotal?.takeIf { it > 0 }?.let { " · ${j.pagesDone}/$it pages" } ?: ""
            val eta = j.etaSeconds?.let { " · ~${formatDuration(it)} left" } ?: ""
            // A push reports per-file bytes: within a single book (done/total stuck at 0/1) the bar would
            // otherwise sit still the whole time, so drive it from bytes instead when the server has them.
            // Scaled to a fixed 0..1000 range (not the raw byte counts) so a multi-GB total can't overflow Int.
            val bytesTotal = j.bytesTotal?.takeIf { it > 0 }
            val bytes = bytesTotal?.let { " · ${formatBytes(j.bytesDone)}/${formatBytes(it)}" } ?: ""
            val (cur, max) = if (bytesTotal != null)
                ((j.bytesDone.coerceIn(0, bytesTotal) * 1000 / bytesTotal).toInt()) to 1000
            else j.done to total
            show("$what ${j.done}/${if (total > 0) total else "?"}$current$pages$bytes$eta", cur, max)
            delay(3000)
        }
    }

    /**
     * Make sure each book is saved on the phone (downloading what's missing, over whatever network
     * reaches the server: mobile data while the phone is on the X4's hotspot). Books saved earlier are
     * used as they are, so this also works with no internet at all.
     */
    private suspend fun saveBooks(books: List<String>): Pair<Map<String, List<Pair<String, Long>>>, List<String>> {
        val ready = linkedMapOf<String, List<Pair<String, Long>>>()
        val failed = mutableListOf<String>()
        books.forEachIndexed { i, path ->
            val name = path.substringAfterLast('/')
            cache.saved(path)?.let { ready[path] = it; return@forEachIndexed }
            try {
                ready[path] = cache.save(api, path) { n, total -> show("Downloading ${i + 1}/${books.size}: $name", n, total) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += "✗ $name: not saved on the phone (${e.message})"
            }
        }
        if (Prefs(this).sleepSend) {
            for (folder in books.map { it.split('/').getOrNull(1) }.filterNotNull().distinct()) {
                runCatching { SleepSync.fetch(api, sleepCache, folder) }
            }
        }
        return ready to failed
    }

    private suspend fun phonePush(t: Task.PhonePush) {
        show(if (t.device == CARD) "Opening the SD card…" else "Looking for the X4…", 0, 0)
        val ops = resolveTarget(t.device)
        if (ops == null) {
            // Can't reach it: save everything on the phone, so the next Send needs no internet at all.
            val (ready, failed) = saveBooks(t.books)
            return result(if (t.device == CARD) "Can't open the SD card" else "Can't find the X4",
                (if (t.device == CARD) "Plug the X4's SD card into the phone (or pick it again in Settings)."
                 else "Start File Transfer on the X4 and connect the phone to its WiFi (\"CrossPoint-Reader\") or the same WiFi.") +
                "\n${ready.size} book(s) are saved on the phone; tap Send again once connected." +
                if (failed.isNotEmpty()) "\n" + failed.joinToString("\n") else "", error = true)
        }

        // 1. Compare each chapter with the X4 first: one already there only needs its changed files, so only
        //    those are downloaded and sent, and the totals (for "MB left" and the time estimate) are exact.
        class Item(val path: String, val dest: String, val files: List<Pair<String, Long>>, val shas: Map<String, String>,
                   val fetch: Set<String>, val isNew: Boolean)
        val items = mutableListOf<Item>()
        val failed = mutableListOf<String>()
        var skipped = 0
        for ((i, path) in t.books.withIndex()) {
            val name = path.substringAfterLast('/')
            show("Comparing with ${ops.storage.label} ${i + 1}/${t.books.size}: ${shortName(path)}", i, t.books.size)
            val dest = "/" + (t.dests[path] ?: path).trim('/')
            try {
                val saved = cache.saved(path)
                val (files, shas) = if (saved != null) saved to cache.shas(path) else api.manifestWithShas(path)
                val plan = if (t.replace) null else ops.plan(dest, files, shas, pushedStore.load(dest))
                when {
                    plan == null -> items += Item(path, dest, files, shas, files.map { it.first }.toSet(), isNew = !t.replace)
                    plan.empty -> { skipped++; pushedStore.save(dest, shas); cache.remove(path) }
                    else -> items += Item(path, dest, files, shas, plan.toFetch(), isNew = false)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: DeviceUnreachable) {
                return result("Lost the X4", "${e.message}\nNothing was changed on the X4 yet; tap Send again.", error = true)
            } catch (e: Exception) {
                failed += "✗ $name: ${e.message}"
            }
        }
        fun size(it: Item, n: String) = it.files.firstOrNull { f -> f.first == n }?.second ?: 0L
        val toDownload = items.sumOf { it -> it.fetch.sumOf { n -> if (cache.has(it.path, n, size(it, n))) 0L else size(it, n) } }
        val toSend = items.sumOf { it -> it.fetch.sumOf { n -> size(it, n) } }
        var left = toDownload + toSend
        TaskBus.bytesLeft.value = left

        // 2. Download just that, then 3. send it. A chapter that made it onto the X4 is dropped from the phone.
        var pushed = 0; var updated = 0; var deltaBytes = 0L; var deltaFiles = 0
        try {
            for ((i, it) in items.withIndex()) {
                val label = "${i + 1}/${items.size} · ${shortName(it.path)}"
                try {
                    cache.fetch(api, it.path, it.fetch, it.files) { n ->
                        left -= size(it, n); TaskBus.bytesLeft.value = left
                        show("Downloading $label · $n · ${formatBytes(left)} left${eta(left)}", 0, 0)
                    }
                    var sent = 0
                    val r = try {
                        ops.push(it.dest, it.files, t.replace, fetch = { n -> cache.bytes(it.path, n) },
                            onBytes = { b -> RateMeter.add(b); left -= b; TaskBus.bytesLeft.value = left },
                            shas = it.shas, known = pushedStore.load(it.dest)) { k ->
                            sent = k
                            show("Sending $label · file ${minOf(k, it.fetch.size)} of ${it.fetch.size} · ${formatBytes(left)} left${eta(left)}",
                                minOf(k, it.fetch.size), it.fetch.size)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // A new chapter cut off halfway is removed (it has no panels.idx, so the X4 wouldn't list it,
                        // but it takes space). An update is left hidden instead: deleting would lose the older copy,
                        // and Fix problems / the next Send completes it.
                        if (it.isNew) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            runCatching { ops.deleteFolder(it.dest) }
                        }
                        cancelNote = " $pushed sent, $updated updated before that." +
                            if (it.isNew) " The chapter being copied was removed from the X4." else
                            " The chapter being updated is hidden on the X4 until Fix problems or the next Send finishes it."
                        throw e
                    }
                    when (r) {
                        "skipped" -> skipped++
                        "updated" -> { updated++; ops.lastDelta?.let { d -> deltaBytes += d.bytes; deltaFiles += d.sent } }
                        else -> pushed++
                    }
                    DeviceScanStore(Prefs(this)).markOnDevice(it.dest.trimStart('/'), bookSig(it.files), t.titles[it.path])
                    pushedStore.save(it.dest, it.shas)
                    cache.remove(it.path)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: DeviceUnreachable) {
                    failed += "✗ ${shortName(it.path)}: ${e.message}"
                    failed += "Stopped: ${items.size - i - 1} more book(s) not sent; tap Send again to continue."
                    break
                } catch (e: Exception) {
                    failed += "✗ ${shortName(it.path)}: ${e.message}"
                }
            }
        } finally {
            TaskBus.bytesLeft.value = null
        }
        var sleepSent = 0
        if (Prefs(this).sleepSend && (pushed + updated + skipped) > 0 && !failed.any { it.startsWith("Stopped") }) {
            show("Sending sleep screens…", 0, 0)
            try {
                sleepSent = SleepSync.send(ops, api, sleepCache, pushedStore, t.books.mapNotNull { it.split('/').getOrNull(1) })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += "✗ sleep screens: ${e.message}"
            }
        }
        val summary = listOfNotNull(
            "$pushed sent".takeIf { pushed > 0 },
            "$sleepSent sleep screen(s) sent".takeIf { sleepSent > 0 },
            "$updated updated with only their changed files ($deltaFiles files, ${formatBytes(deltaBytes)})".takeIf { updated > 0 },
            "$skipped already on the X4".takeIf { skipped > 0 },
            "${failed.count { it.startsWith("✗") }} failed".takeIf { failed.isNotEmpty() }).joinToString(", ")
        result("Send to X4 ${if (failed.isEmpty()) "finished" else "had errors"}",
            summary + if (failed.isNotEmpty()) "\n" + failed.joinToString("\n") else "", error = failed.isNotEmpty())
    }

    /** Copy EPUB files straight onto [t.device] (or the SD card) under /books/[t.folder], no server
     *  involved: the firmware's library scan picks up any .epub anywhere on the card (no fixed folder
     *  it expects) and groups books into a shelf by their containing folder. */
    private suspend fun sendEpub(t: Task.SendEpub) {
        show(if (t.device == CARD) "Opening the SD card…" else "Looking for the X4…", 0, 0)
        val ops = resolveTarget(t.device)
        if (ops == null) return result(if (t.device == CARD) "Can't open the SD card" else "Can't find the X4",
            (if (t.device == CARD) "Plug the X4's SD card into the phone (or pick it again in Settings)."
             else "Start File Transfer on the X4 and connect the phone to its WiFi (\"CrossPoint-Reader\") or the same WiFi.") +
            "\nTap Send again once connected.", error = true)
        val dest = if (t.folder.isBlank()) "/books" else "/books/${t.folder}"
        ops.storage.mkdirs(dest)
        var sent = 0
        val failed = mutableListOf<String>()
        for ((i, file) in t.files.withIndex()) {
            val (name, data) = file
            show("Sending EPUB ${i + 1}/${t.files.size}: $name", i, t.files.size)
            try {
                ops.storage.write("$dest/$name", data)
                sent++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: DeviceUnreachable) {
                failed += "✗ $name: ${e.message}"
                failed += "Stopped: ${t.files.size - i - 1} more file(s) not sent; tap Send again to continue."
                break
            } catch (e: Exception) {
                failed += "✗ $name: ${e.message}"
            }
        }
        result("Send EPUB ${if (failed.isEmpty()) "finished" else "had errors"}",
            "$sent sent" + if (failed.isNotEmpty()) "\n" + failed.joinToString("\n") else "", error = failed.isNotEmpty())
    }

    /** Convert chapters on the phone itself (see [PhoneManga]) into the local book cache: no X4/card
     *  connectivity needed at all, unlike sending. The result sends later with a normal PhonePush
     *  (cache.saved() finds it and skips the server, same as anything saved offline for later). */
    private suspend fun phoneConvert(t: Task.PhoneConvert) {
        val (tw, th) = if (Prefs(this).device == "x3") 528 to 792 else 480 to 800
        var done = 0
        val failed = mutableListOf<String>()
        for ((i, ch) in t.chapters.withIndex()) {
            val label = "${i + 1}/${t.chapters.size} · ${ch.title}/${ch.file}"
            show("Converting on phone $label", i, t.chapters.size)
            val path = "manga/${safePathSegment(ch.title)}/${safePathSegment(ch.file.removeSuffix(".cbz"))}"
            try {
                val files = PhoneManga.convert(this, android.net.Uri.parse(ch.uri), "", ch.title, "", tw, th, cache.localStorage(path)) { page, _ ->
                    show("Converting on phone $label · page $page", i, t.chapters.size)
                }
                cache.markConverted(path, files)
                done++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += "✗ ${ch.title}/${ch.file}: ${e.message}"
            }
        }
        result("Convert on phone ${if (failed.isEmpty()) "finished" else "had errors"}",
            "$done converted, saved on the phone; send to the X4 anytime from Books" +
                if (failed.isNotEmpty()) "\n" + failed.joinToString("\n") else "", error = failed.isNotEmpty())
    }

    /** A path segment safe for the card: WebDAV refuses one starting with '.', so does SAF's own
     *  folder-creation contract in practice; strip any stray '/' a title/filename might carry too. */
    private fun safePathSegment(s: String): String {
        val n = s.replace('/', '_').trim()
        return (if (n.startsWith(".")) "_$n" else n).ifEmpty { "_" }
    }

    /** "Chapter 77.1" with its series: "KUMO DESU GA…/Chapter 77.1". */
    private fun shortName(path: String): String {
        val parts = path.split('/')
        val title = parts.getOrElse(parts.size - 2) { "" }
        return (if (title.length > 18) title.take(17) + "…" else title) + "/" + parts.last()
    }

    /** " · ~2 min left" from the bytes left and the recent transfer rate; empty until there's a rate. */
    private fun eta(bytesLeft: Long): String {
        val rate = TaskBus.transfer.value?.let { s -> s.history.takeLast(20).average().takeIf { it > 0 } ?: s.average } ?: return ""
        return if (rate <= 0) "" else " · ~${formatDuration((bytesLeft / rate).toLong())} left"
    }

    private var lastShown = 0L
    private var lastText = ""

    /**
     * Update the progress notification and the in-app status line, at most ~3 times a second: uploads
     * report every 64 KB, and posting a notification that often makes the whole phone stutter.
     */
    private fun show(text: String, cur: Int, max: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastShown < 300 && cur != max && lastText.isNotEmpty()) return
        lastShown = now
        lastText = text
        TaskBus.status.value = text
        nm.notify(ID_PROGRESS, progress(text, cur, max))
    }

    @Volatile private var cancelNote: String? = null
    private val sleepCache get() = java.io.File(filesDir, "sleep")

    /** Book operations for [device]: the SD card plugged into the phone ([CARD]), or the X4 over WiFi. Null if
     *  it isn't reachable. */
    private suspend fun resolveTarget(device: String): BookOps? {
        if (device == CARD) {
            val tree = Prefs(this).cardTree ?: return null
            val storage = SafStorage(this, android.net.Uri.parse(tree))
            return if (storage.available()) BookOps(storage) else null
        }
        return resolveX4(device)?.let { this.device.ops(it) }
    }

    /** The X4's address: [requested] if it answers, else found automatically (see DeviceClient.find). */
    private suspend fun resolveX4(requested: String): String? {
        val prefs = Prefs(this)
        return device.find(requested.ifBlank { prefs.deviceIp }, prefs.lastX4) { show(it, 0, 0) }?.also { prefs.lastX4 = it }
    }

    private fun progress(text: String, cur: Int, max: Int): Notification = Notify.progress(this, text, cur, max, cancellable = true)

    private fun result(title: String, text: String, error: Boolean = false) = Notify.result(this, title, text, error)

    companion object {
        const val ID_PROGRESS = 1
        const val ACTION_CANCEL = "com.schindler.k2m.CANCEL"
        fun createChannels(ctx: Context) = Notify.createChannels(ctx)
    }
}

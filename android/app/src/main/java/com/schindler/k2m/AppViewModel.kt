package com.schindler.k2m

import android.app.Application
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Task/device value meaning "the X4's SD card, plugged into this phone". */
const val CARD = "card"

/** Firmware settings key (SettingsList.h) for "Reversed page turn (Vertical & Manga)". */
private const val REVERSE_TURN = "reversePageTurn"

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val api = Api(prefs)
    private val deviceClient = DeviceClient(app)
    private val cache = BookCache(java.io.File(app.filesDir, "books"))
    private val scanStore = DeviceScanStore(prefs)

    /** What's on the X4, from the last "Check X4" (updated by sends since). */
    var deviceLib by mutableStateOf<DeviceLibrary?>(scanStore.load()); private set
    /** Server book path → its folder on the X4 (see [DeviceLibrary.match]); recomputed with books or the scan. */
    var x4Match by mutableStateOf<Map<String, String>>(emptyMap()); private set

    /** Server book path → its incomplete folder on the X4 (no panels.idx, so not listed there). */
    var x4Incomplete by mutableStateOf<Map<String, String>>(emptyMap()); private set

    private fun rematch() {
        x4Match = deviceLib?.match(books) ?: emptyMap()
        x4Incomplete = deviceLib?.matchIncomplete(books.filter { it.path !in x4Match }) ?: emptyMap()
    }

    /** "current", "older" (a different conversion is on the X4) or null (not on the X4). */
    fun x4State(b: Book): String? {
        val dev = x4Match[b.path] ?: return null
        return if (b.sig.isNotEmpty() && deviceLib?.manga?.get(dev) == b.sig) "current" else "older"
    }

    /** X4 manga folders that match no book on the server. */
    fun onlyOnX4(): List<String> = deviceLib?.manga?.keys?.filter { it !in x4Match.values.toSet() }?.sortedWith(NaturalOrder).orEmpty()

    var library by mutableStateOf<List<TitleRow>>(emptyList()); private set
    var jobs by mutableStateOf<List<JobInfo>>(emptyList()); private set
    var books by mutableStateOf<List<Book>>(emptyList()); private set
    /** [books] grouped by title folder, computed when the list arrives rather than on every frame. */
    var booksByTitle by mutableStateOf<List<Pair<String, List<Book>>>>(emptyList()); private set
    /** Full job records (with per-chapter outcomes) for the jobs the user expanded. */
    var jobDetails by mutableStateOf<Map<String, JobInfo>>(emptyMap()); private set
    var devices by mutableStateOf<List<Device>>(emptyList()); private set
    /** Books saved on the phone for sending without internet. */
    var saved by mutableStateOf<Set<String>>(emptySet()); private set
    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)
    var serverUrl by mutableStateOf<String?>(null); private set

    init {
        viewModelScope.launch { TaskBus.finished.collect { refreshSaved(); deviceLib = scanStore.load(); rematch(); refreshAll() } }
        refreshSaved()
    }

    private fun launchBusy(label: String, block: suspend () -> Unit) = viewModelScope.launch {
        busy = label
        try { block() } catch (e: Exception) { message = e.message ?: e.javaClass.simpleName } finally { busy = null }
    }

    fun refreshAll() = launchBusy("Refreshing…") {
        val phone = scanPhone()
        try {
            library = coroutineScope {
                val server = async { api.libraryWithIgnored() }
                val (titles, ig) = server.await()
                ignored = ig
                mergeLibrary(phone, titles, ig)
            }
            runCatching { syncKomikkuCovers() }
            if (komikkuRead.isEmpty()) refreshKomikkuRead()
            books = api.books()
            jobs = api.jobs()
            serverUrl = api.currentBase
            if (!updateChecked) {
                updateChecked = true
                update = runCatching { Updater.check(getApplication(), api) }.getOrNull()
                if (update != null && prefs.autoUpdate && Updater.canInstall(getApplication())) installUpdate()
            }
        } catch (e: ApiException) {
            // The phone-conversion fallback exists for exactly this: don't leave the chapter list
            // empty (it otherwise only ever comes from a server call) when that's the whole point of
            // being able to work without one -- list what the phone itself has instead.
            library = mergeLibrary(phone, emptyList(), ignored)
            serverUrl = null
            if (!prefs.phoneConvertFallback) throw e
        } finally {
            addPhoneConvertedBooks()
            rematch()
        }
    }

    /** Books converted on the phone (see [PhoneManga]) that the server doesn't know about yet, folded
     *  into [books]/[booksByTitle] so they show up in the normal Books list like anything else --
     *  sending one just needs "From this phone to the X4", not the server, same as any book already
     *  saved offline. Runs whether or not the server call above succeeded. */
    private fun addPhoneConvertedBooks() {
        val known = books.map { it.path }.toSet()
        val extra = runCatching { cache.savedPaths() }.getOrDefault(emptySet()).filter { it !in known }.mapNotNull { path ->
            val files = cache.saved(path) ?: return@mapNotNull null
            Book(path, path.split('/').getOrNull(1) ?: path, files.size, files.sumOf { it.second })
        }
        phoneOnly = extra.map { it.path }.toSet()
        if (extra.isNotEmpty()) books = books + extra
        booksByTitle = books.groupBy { it.titleFolder }.toList()
    }

    /** Books that exist only in the phone's cache; the server has never heard of them. */
    private var phoneOnly: Set<String> = emptySet()

    fun refreshSaved() { saved = runCatching { cache.savedPaths() }.getOrDefault(emptySet()) }

    fun saveOffline(bookPaths: List<String>) {
        TaskBus.enqueue(getApplication(), Task.SaveOffline(bookPaths))
        message = "Saving ${bookPaths.size} book(s) on the phone…"
    }

    /** Shelf names already on the X4 (or SD card) under /books/, for the Send EPUB dialog's picker. */
    var epubShelves by mutableStateOf<List<String>>(emptyList())

    /** Book operations for whatever [device] the Send EPUB dialog currently has entered (not necessarily
     *  the saved one yet), unlike [target] which always resolves the saved X4/card choice. */
    private suspend fun resolveEpubTarget(device: String): BookOps =
        if (device == CARD) card()
        else deviceClient.ops(deviceClient.find(device, prefs.lastX4) { busy = it }?.also { prefs.lastX4 = it }
            ?: throw DeviceUnreachable("X4 not found. Start File Transfer on it, and connect the phone to its WiFi " +
                "(\"CrossPoint-Reader\") or put both on the same WiFi."))

    /** Refresh the shelf list for the Send EPUB dialog. Not reachable yet is not an error here -- the
     *  picker just comes up empty and the user can still type a new shelf name or send with none. */
    fun loadShelves(device: String) = launchBusy("Looking for shelves…") {
        epubShelves = runCatching { resolveEpubTarget(device).storage.list("/books")?.filter { it.dir }?.map { it.name } }
            .getOrNull()?.sorted() ?: emptyList()
    }

    /** Copy picked .epub files straight to the X4 (or its card): no conversion, no server round-trip.
     *  [shelf] is an existing or new folder name under /books/ to group them into, or blank for none. */
    fun sendEpub(uris: List<Uri>, device: String, shelf: String) = launchBusy("Reading EPUB file(s)…") {
        val ctx = getApplication<Application>()
        val files = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                val name = epubFileName(ctx, uri) ?: return@mapNotNull null
                val data = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                    ?: return@mapNotNull null
                name to data
            }
        }
        if (files.isEmpty()) { message = "Couldn't read the selected file(s)"; return@launchBusy }
        prefs.deviceIp = device
        TaskBus.enqueue(ctx, Task.SendEpub(device, files, sanitizeShelf(shelf)))
        message = "Sending ${files.size} EPUB(s) to the X4…"
    }

    /** A shelf name typed or picked in the dialog, safe to put right after "/books/" -- unlike
     *  [safeFolderName], blank is kept as "no shelf" rather than defaulted to something. */
    private fun sanitizeShelf(name: String): String {
        val n = name.substringAfterLast('/').trim()
        return if (n.startsWith(".")) "" else n
    }

    /** Copy every .epub directly inside a picked folder to the X4 (or its card), grouped under the
     *  folder's own name so the firmware's Shelves view shows them together (one folder = one shelf). */
    fun sendEpubFolder(tree: Uri, device: String) = launchBusy("Reading EPUB files…") {
        val ctx = getApplication<Application>()
        val (folderName, found) = EpubFolderScanner.scan(ctx, tree)
        val files = withContext(Dispatchers.IO) {
            found.mapNotNull { f ->
                val data = runCatching { ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } }.getOrNull()
                    ?: return@mapNotNull null
                f.name to data
            }
        }
        if (files.isEmpty()) { message = "No EPUB files found in that folder"; return@launchBusy }
        prefs.deviceIp = device
        TaskBus.enqueue(ctx, Task.SendEpub(device, files, safeFolderName(folderName)))
        message = "Sending ${files.size} EPUB(s) to the X4…"
    }

    /** A folder name safe to put right after "/books/" on the card. */
    private fun safeFolderName(name: String): String {
        val n = name.substringAfterLast('/').trim()
        return if (n.isEmpty() || n.startsWith(".")) "Books" else n
    }

    /** A safe "<name>.epub" to write on the card for a picked Uri (its real filename via the content
     *  resolver, not the opaque last path segment a content:// Uri usually has), or null if unreadable. */
    private fun epubFileName(ctx: android.content.Context, uri: Uri): String? {
        var name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: return null
        name = name.substringAfterLast('/').trim()
        if (name.isEmpty() || name.startsWith(".")) return null
        if (!name.endsWith(".epub", ignoreCase = true)) name += ".epub"
        return name
    }

    fun refreshJobs() = viewModelScope.launch {
        runCatching {
            jobs = api.jobs()
            // Keep expanded jobs' details current while they run.
            val open = jobDetails.keys
            jobDetails = jobs.filter { it.id in open }.associate { it.id to (if (it.active || jobDetails[it.id]?.active == true) api.job(it.id) else jobDetails[it.id]!!) }
        }
    }

    /** The job list leaves out per-chapter outcomes; fetch them when a job is expanded. */
    fun loadJob(id: String) = viewModelScope.launch { runCatching { jobDetails = jobDetails + (id to api.job(id)) } }
    fun closeJob(id: String) { jobDetails = jobDetails - id }

    /** The log text shown by [viewLog], keyed by job id; absent while loading. */
    var jobLogs by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var viewingLog by mutableStateOf<String?>(null)

    fun viewLog(id: String) {
        viewingLog = id
        if (id !in jobLogs) viewModelScope.launch {
            jobLogs = jobLogs + (id to runCatching { api.jobLog(id) }.getOrDefault("Couldn't load the log."))
        }
    }
    fun closeLog() { viewingLog = null }

    fun clearFinishedJobs() = launchBusy("Clearing finished jobs…") {
        api.clearFinishedJobs()
        jobDetails = emptyMap()
        jobs = api.jobs()
    }

    /** Convert chapters right on the phone (see [PhoneManga]) into the local cache: the fallback for
     *  when there's no server, not a substitute for a real Sync (no OCR, search, covers, or delta
     *  sync). Needs no X4/card connectivity -- sending the result over later does, whenever it's
     *  convenient, through the normal Books list and Send flow (it shows up there like any other
     *  book once [refreshAll] folds it in -- see [addPhoneConvertedBooks]). [AppViewModel]'s caller
     *  is expected to have already shown the Settings warning. */
    fun convertOnPhone(chapters: List<PhoneChapter>) {
        if (chapters.isEmpty()) return
        TaskBus.enqueue(getApplication(), Task.PhoneConvert(chapters))
        message = "Converting ${chapters.size} chapter(s) on this phone…"
    }

    private suspend fun scanPhone(): List<PhoneChapter> {
        val tree = prefs.komikkuTree ?: return emptyList()
        return KomikkuScanner.scan(getApplication(), Uri.parse(tree))
    }

    /** Upload what's new or changed on the phone, and convert everything not yet converted. */
    fun sync(titles: List<TitleRow> = library) {
        val plan = syncPlan(titles) ?: run { message = "Everything is already converted"; return }
        TaskBus.enqueue(getApplication(), plan)
        message = "Queued: ${plan.uploads.size} upload(s), ${plan.uploads.size + plan.convert.size} chapter(s) to convert"
    }

    /** As [sync], for a hand-picked set of chapters rather than everything under some title(s). */
    fun syncChapters(rows: List<ChapterRow>) {
        val plan = chapterSyncPlan(rows) ?: run { message = "Everything selected is already converted"; return }
        TaskBus.enqueue(getApplication(), plan)
        message = "Queued: ${plan.uploads.size} upload(s), ${plan.uploads.size + plan.convert.size} chapter(s) to convert"
    }

    /** Read the X4's SD card (File Transfer must be running; phone on the X4's WiFi or the same WiFi). */
    fun checkX4() = launchBusy("Looking for the X4…") {
        val ops = target()
        val lib = ops.scanLibrary { folder -> busy = "Checking $where: $folder" }
        scanStore.save(lib)
        deviceLib = lib
        rematch()
        val current = books.count { x4State(it) == "current" }
        val older = books.count { x4State(it) == "older" }
        message = "X4: ${lib.manga.size} manga chapter(s): $current up to date, $older older version, ${onlyOnX4().size} not from this server; " +
            "${lib.otherBooks.size} other book(s)" +
            if (lib.incomplete.isNotEmpty()) ". ${lib.incomplete.size} incomplete chapter(s) the X4 doesn't list: use Fix problems" else ""
    }

    /** Reading progress of every manga on the X4, for the clean-up dialog; null when it's closed. */
    var cleanup by mutableStateOf<Map<String, ReadingProgress?>?>(null)

    /** Re-read the X4's library, then each manga's reading progress. */
    fun findRead() = launchBusy("Looking for the X4…") {
        val ops = target()
        val lib = ops.scanLibrary { folder -> busy = "Checking $where: $folder" }
        scanStore.save(lib)
        deviceLib = lib
        rematch()
        val paths = lib.manga.keys.sortedWith(NaturalOrder)
        cleanup = ops.readingProgress(paths) { n -> busy = "Checking reading progress $n/${paths.size}…" }
    }

    fun deleteFromX4(paths: List<String>) = launchBusy("Deleting from the X4…") {
        val ops = target()
        cleanup = null
        val deleted = ops.deleteBooks(paths) { n -> busy = "Deleting $n/${paths.size} from $where…" }
        scanStore.forget(deleted)
        deviceLib = scanStore.load()
        rematch()
        message = "Deleted ${deleted.size} book(s) from the X4" + if (deleted.size < paths.size) " (${paths.size - deleted.size} failed)" else ""
    }

    /** Problems found by "Fix problems…": book path → what's wrong; null when the dialog is closed. */
    var problems by mutableStateOf<Map<String, List<String>>?>(null)
    private var problemSources: Map<String, String?> = emptyMap()
    private var problemFixes: Map<String, String> = emptyMap()
    private var problemFolders: Map<String, String> = emptyMap()
    var problemsOnX4 by mutableStateOf(false); private set
    private var problemX4 = ""

    /** The X4's address, found automatically unless one is set in Settings; remembered for next time. */
    /**
     * Where book operations go: the X4 over WiFi, or its SD card plugged into this phone (Settings → The X4's card).
     */
    private suspend fun target(): BookOps = if (prefs.target == "card") card() else deviceClient.ops(x4())

    private suspend fun card(): BookOps {
        val tree = prefs.cardTree ?: throw DeviceUnreachable("Pick the SD card in Settings first (The X4's card → SD card in this phone)")
        val storage = SafStorage(getApplication(), Uri.parse(tree))
        if (!storage.available()) throw DeviceUnreachable("The SD card isn't available: plug it into the phone, or pick it again in Settings")
        return BookOps(storage)
    }

    private val where get() = if (prefs.target == "card") "the SD card" else "the X4"

    var target by mutableStateOf(prefs.target); private set
    var cardName by mutableStateOf(prefs.cardTree?.let { Uri.decode(it).substringAfterLast(':').ifEmpty { "SD card" } }); private set

    fun setTargetTo(t: String) { prefs.target = t; target = t }

    /** The SD card's root from the system picker: kept with read and write access, then checked for X4 files. */
    fun setCard(uri: Uri) = launchBusy("Checking the SD card…") {
        val app = getApplication<Application>()
        app.contentResolver.takePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.cardTree = uri.toString()
        cardName = Uri.decode(uri.toString()).substringAfterLast(':').ifEmpty { "SD card" }
        setTargetTo("card")
        val root = SafStorage(app, uri).list("/").orEmpty()
        message = if (root.any { it.name.equals(".crosspoint", true) || it.name.equals("manga", true) })
            "SD card ready: sends and X4 tools now use it directly"
        else "This folder has no X4 files (.crosspoint, manga) yet: make sure you picked the root of the X4's SD card"
    }

    private suspend fun x4(): String =
        deviceClient.find(prefs.deviceIp, prefs.lastX4) { busy = it }?.also { prefs.lastX4 = it }
            ?: throw DeviceUnreachable("X4 not found. Start File Transfer on it, and connect the phone to its WiFi " +
                "(\"CrossPoint-Reader\") or put both on the same WiFi.")

    /** The server's check of every book, plus a file-by-file comparison of X4 copies that look up to date. */
    fun findProblems() = launchBusy("Checking the books on the server…") {
        val found = linkedMapOf<String, MutableList<String>>()
        val server = api.checkBooks()
        server.forEach { found.getOrPut(it.path) { mutableListOf() } += it.issues }
        problemSources = server.associate { it.path to it.chapter }
        problemFixes = server.associate { it.path to (it.fix ?: "reconvert") }
        problemFolders = server.mapNotNull { p -> p.folder?.let { p.path to it } }.toMap()
        busy = "Looking for the X4…"
        val ops = runCatching { target() }.getOrNull()
        problemsOnX4 = ops != null
        problemX4 = if (prefs.target == "card") CARD else (prefs.lastX4)
        if (ops != null) {
            // Re-read the X4 so incomplete folders and versions are current.
            val lib = ops.scanLibrary { folder -> busy = "Checking $where: $folder" }
            scanStore.save(lib)
            deviceLib = lib
            rematch()
            x4Incomplete.forEach { (book, dev) ->
                found.getOrPut(book) { mutableListOf() } += "on the X4: incomplete copy in $dev (no panels.idx), so the X4 " +
                    "doesn't list it; Fix sends it again"
            }
            (lib.incomplete - x4Incomplete.values.toSet()).forEach { dev ->
                found["x4:$dev"] = mutableListOf("incomplete chapter folder on the X4 that matches no book on the server; Fix deletes it")
            }
            val current = books.filter { x4State(it) == "current" }
            current.forEachIndexed { i, b ->
                busy = "Comparing with the X4 ${i + 1}/${current.size}…"
                val diff = runCatching { ops.compareBook(x4Match[b.path]!!, api.manifest(b.path)) }.getOrElse { listOf("couldn't compare: ${it.message}") }
                if (diff.isNotEmpty()) found.getOrPut(b.path) { mutableListOf() } += diff.map { "on the X4: $it" }
            }
        }
        problems = found
    }

    /**
     * Re-convert the chosen books whose server copy has problems (from their CBZ), then replace the chosen
     * books on the X4 (if it was reachable) in place, clearing their cached page images.
     */
    fun fixProblems(paths: List<String>, ip: String = problemX4) {
        // Bad pages: convert again from scratch. Other screen / no cover page: a normal conversion. Cover without
        // art: look the art up, then a normal run redraws just the first page.
        val reconvert = paths.filter { problemFixes[it] == "reconvert" }.mapNotNull { problemSources[it] }.distinct()
        val convert = paths.filter { problemFixes[it] == "convert" || problemFixes[it] == "cover" }.mapNotNull { problemSources[it] }.distinct()
        val fetchArt = paths.filter { problemFixes[it] == "cover" }.mapNotNull { problemFolders[it] }.distinct()
        // Every X4 copy is then updated by a delta sync: only files that are missing, damaged (wrong size) or
        // changed go over, and pages the chapter no longer has are removed.
        // Only chapters already on the X4 (complete or not) are sent; leftover folders matching nothing are deleted.
        val where = x4Match + x4Incomplete
        val onX4 = paths.filter { problemsOnX4 && where[it] != null }
        val delete = paths.filter { it.startsWith("x4:") }.map { it.removePrefix("x4:") }
        TaskBus.enqueue(getApplication(), Task.Repair(reconvert, onX4, if (problemsOnX4) ip else null,
            onX4.associateWith { where[it]!! }, books.filter { it.path in onX4 }.associate { it.path to it.title }, delete,
            convert = convert, fetchArt = fetchArt))
        problems = null
        message = "Fixing ${paths.size} chapter(s)" + if (problemsOnX4) "" else " on the server; send them to the X4 afterwards"
    }

    // ── Series covers ──────────────────────────────────────────────

    /** Loaded cover thumbnails by title folder (null = the server has none). */
    var covers by mutableStateOf<Map<String, androidx.compose.ui.graphics.ImageBitmap?>>(emptyMap()); private set
    private val coverLoads = mutableSetOf<String>()

    fun loadCover(folder: String, force: Boolean = false) {
        if (!force && !coverLoads.add(folder)) return
        coverLoads += folder
        viewModelScope.launch {
            val bytes = runCatching { api.cover(folder, 240) }.getOrNull()
            val bmp = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            covers = covers + (folder to bmp)
        }
    }

    /** Re-draw the first page of every converted chapter of [title] with the current art (no re-conversion). */
    private fun refreshCoverPages(title: TitleRow) {
        val keys = title.chapters.filter { it.server != null }.map { it.serverKey }
        if (keys.isNotEmpty() && prefs.coverPage) TaskBus.enqueue(getApplication(), Task.Sync(emptyList(), keys))
    }

    fun changeCover(title: TitleRow, image: android.net.Uri) = launchBusy("Uploading the cover…") {
        val folder = title.folder ?: return@launchBusy
        val bytes = getApplication<Application>().contentResolver.openInputStream(image)!!.use { it.readBytes() }
        api.setCover(folder, bytes)
        loadCover(folder, force = true)
        refreshCoverPages(title)
        message = "Cover changed; updating the chapters' cover pages (send them to the X4 afterwards)"
    }

    fun resetCover(title: TitleRow, refetch: Boolean) = launchBusy(if (refetch) "Searching AniList…" else "Resetting the cover…") {
        val folder = title.folder ?: return@launchBusy
        val found = api.resetCover(folder, refetch)
        loadCover(folder, force = true)
        refreshCoverPages(title)
        message = if (found) "Cover updated from AniList" else "AniList has no cover for this title; the chapter's first page is used"
    }

    // ── Deleting / ignoring ─────────────────────────────────────

    var ignored by mutableStateOf(Ignored()); private set

    /**
     * Delete converted chapters. From the server (a queued job, so it never collides with a conversion) and/or
     * from the X4. [ignore] also drops the uploaded CBZs and keeps them from syncing again; [ignoreSeries]
     * does that for the whole series, future chapters included.
     */
    fun deleteBooks(paths: List<String>, fromServer: Boolean, fromX4: Boolean, ignore: Boolean, ignoreSeries: Boolean) =
        launchBusy("Deleting…") {
            val parts = mutableListOf<String>()
            if (fromX4) {
                val ops = target()
                val onX4 = paths.mapNotNull { x4Match[it] ?: x4Incomplete[it] }
                val gone = ops.deleteBooks(onX4) { n -> busy = "Deleting from $where: $n/${onX4.size}…" }
                scanStore.forget(gone); deviceLib = scanStore.load(); rematch()
                parts += "${gone.size} from the X4"
            }
            if (fromServer) {
                val local = paths.filter { it in phoneOnly }
                val onServer = paths - local.toSet()
                if (local.isNotEmpty()) {
                    withContext(Dispatchers.IO) { local.forEach { cache.remove(it) } }
                    refreshSaved(); books = books.filter { it.path !in local }
                    booksByTitle = books.groupBy { it.titleFolder }.toList()
                    parts += "${local.size} from the phone"
                }
                if (onServer.isNotEmpty()) {
                    val titleDirs = if (ignoreSeries) library.filter { t -> t.chapters.any { c -> c.server?.book in onServer } }
                        .mapNotNull { t -> t.chapters.firstNotNullOfOrNull { it.serverDir } } else emptyList()
                    val job = api.deleteBooks(onServer, ignore || ignoreSeries, titleDirs)
                    TaskBus.enqueue(getApplication(), Task.Watch(job.id, "Delete"))
                    parts += "${onServer.size} from the server" + if (ignore || ignoreSeries) " (won't sync again)" else ""
                }
            }
            message = "Deleting " + parts.joinToString(" and ")
        }

    fun unignore(title: TitleRow) = launchBusy("Updating…") {
        val dir = title.chapters.firstNotNullOfOrNull { it.serverDir } ?: title.title
        api.unignore(listOf(dir) + ignored.titles.filter { it.substringAfterLast('/') == title.title },
            ignored.chapters.filter { it.substringBeforeLast('/').substringAfterLast('/') == title.title })
        message = "${title.title} will sync again"
        refreshAll()
    }

    // ── Cover picker ──────────────────────────────────────────────

    /** The series whose cover picker is open, and its choices (null while loading). */
    var coverPicker by mutableStateOf<TitleRow?>(null)
    var coverChoices by mutableStateOf<List<CoverChoice>?>(null); private set
    var coverChoiceImages by mutableStateOf<Map<String, androidx.compose.ui.graphics.ImageBitmap?>>(emptyMap()); private set

    fun openCoverPicker(title: TitleRow) {
        val folder = title.folder ?: return
        coverPicker = title; coverChoices = null; coverChoiceImages = emptyMap()
        viewModelScope.launch {
            val choices = runCatching { api.coverChoices(folder) }.getOrElse { message = it.message; emptyList() }
            coverChoices = choices
            choices.forEach { c ->
                launch {
                    val bytes = api.coverChoiceImage(folder, c.id, 300)
                    coverChoiceImages = coverChoiceImages + (c.id to bytes?.let {
                        android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() })
                }
            }
        }
    }

    fun chooseCover(title: TitleRow, choice: CoverChoice) = launchBusy("Setting the cover…") {
        val folder = title.folder ?: return@launchBusy
        api.chooseCover(folder, choice.id)
        coverPicker = null
        loadCover(folder, force = true)
        refreshCoverPages(title)
        message = "Cover set; updating the chapters' cover pages (send them to the X4 afterwards)"
    }

    /** A series row for a title folder (the Books tab only knows folders). */
    fun titleForFolder(folder: String): TitleRow? = library.firstOrNull { it.folder == folder }

    // ── Read status between the X4 and Komikku ────────────────────

    /** A chapter whose read status differs: its server book, where it is on the X4, and its Komikku chapter. */
    data class ReadItem(val book: String, val device: String, val manga: BackupMangaRef, val chapter: BackupChapterRef) {
        val label get() = book.removePrefix("manga/")
    }
    data class ReadSync(val toKomikku: List<ReadItem>, val toX4: List<ReadItem>, val unmatched: Int, val backupDate: Long)

    var readSync by mutableStateOf<ReadSync?>(null)
    private var readBackup: KomikkuBackupFile? = null
    /** Server book paths Komikku has marked read (from its newest backup), for "skip chapters already read". */
    var komikkuRead by mutableStateOf<Set<String>>(emptySet()); private set

    private suspend fun loadBackup(): Pair<KomikkuBackupFile, Long> {
        val tree = prefs.komikkuTree ?: throw IllegalStateException("Pick Komikku's main folder in Settings first")
        val (uri, modified) = KomikkuBackup.latest(getApplication(), Uri.parse(tree))
            ?: throw IllegalStateException("No Komikku backup found: pick Komikku's main folder in Settings, and turn on " +
                "automatic backups in Komikku (or create one: More → Backup and restore)")
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        return KomikkuBackupFile.read(bytes) to modified
    }

    /** Server book path → its chapter in the Komikku backup (title matched ignoring punctuation). */
    private fun matchBooks(backup: KomikkuBackupFile): Map<String, Pair<BackupMangaRef, BackupChapterRef>> {
        val byTitle = backup.mangas.associateBy { DeviceLibrary.norm(it.title) }
        val out = mutableMapOf<String, Pair<BackupMangaRef, BackupChapterRef>>()
        for (t in library) {
            val m = byTitle[DeviceLibrary.norm(t.title)] ?: t.folder?.let { byTitle[DeviceLibrary.norm(it)] } ?: continue
            for (c in t.chapters) {
                val book = c.server?.book ?: continue
                matchBackupChapter(c.file, m.chapters)?.let { out[book] = m to it }
            }
        }
        return out
    }

    fun refreshKomikkuRead() = viewModelScope.launch {
        runCatching {
            val (backup, _) = loadBackup()
            komikkuRead = matchBooks(backup).filterValues { it.second.read }.keys
        }
    }

    /** Compare what's read on the X4 with what's read in Komikku. */
    fun planReadSync() = launchBusy("Reading Komikku's backup…") {
        val (backup, date) = loadBackup()
        readBackup = backup
        val matched = matchBooks(backup)
        komikkuRead = matched.filterValues { it.second.read }.keys
        val ops = target()
        val lib = ops.scanLibrary { busy = "Checking $where: $it" }
        scanStore.save(lib); deviceLib = lib; rematch()
        val onX4 = x4Match.filterKeys { it in matched }
        val progress = ops.readingProgress(onX4.values.toList()) { n -> busy = "Reading progress on $where $n/${onX4.size}…" }
        val readOnX4 = onX4.filterValues { progress[it]?.read == true }.keys
        val toKomikku = readOnX4.mapNotNull { b -> matched[b]?.takeIf { !it.second.read }?.let { ReadItem(b, x4Match[b]!!, it.first, it.second) } }
        val toX4 = onX4.keys.filter { it !in readOnX4 }.mapNotNull { b -> matched[b]?.takeIf { it.second.read }?.let { ReadItem(b, x4Match[b]!!, it.first, it.second) } }
        val unmatched = x4Match.keys.count { it !in matched }
        readSync = ReadSync(toKomikku.sortedWith(compareBy(NaturalOrder) { it.book }), toX4.sortedWith(compareBy(NaturalOrder) { it.book }), unmatched, date)
    }

    /**
     * Mark [toX4] read on the X4 directly, and save a backup marking [toKomikku] read for Komikku to restore
     * (Komikku can't be written to by other apps; its restore only ever adds "read").
     */
    fun applyReadSync(toKomikku: List<ReadItem>, toX4: List<ReadItem>) = launchBusy("Updating read status…") {
        readSync = null
        val parts = mutableListOf<String>()
        if (toX4.isNotEmpty()) {
            val ops = target()
            toX4.forEachIndexed { i, it ->
                busy = "Marking read on $where ${i + 1}/${toX4.size}: ${it.label}"
                ops.markRead(it.device)
            }
            parts += "${toX4.size} marked read on the X4"
        }
        if (toKomikku.isNotEmpty()) {
            val backup = readBackup ?: loadBackup().first
            val file = backup.buildMarkRead(toKomikku.groupBy { it.manga }.mapValues { (_, v) -> v.map { it.chapter.url }.toSet() })
            val name = "Komikku2Matcha-read-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".tachibk"
            saveToDownloads(name, file)
            parts += "a backup marking ${toKomikku.size} read in Komikku was saved to Downloads/$name: in Komikku, " +
                "More → Settings → Data and storage → Restore backup, and pick it"
            openKomikku()
        }
        message = parts.joinToString("; ").replaceFirstChar { it.uppercase() }
    }

    private fun saveToDownloads(name: String, data: ByteArray) {
        val app = getApplication<Application>()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            }
            val uri = app.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            app.contentResolver.openOutputStream(uri)!!.use { it.write(data) }
        } else {
            java.io.File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), name).writeBytes(data)
        }
    }

    private fun openKomikku() {
        val app = getApplication<Application>()
        val launch = listOf("app.komikku", "app.komikku.beta", "app.komikku.foss", "app.komikku.dev")
            .firstNotNullOfOrNull { app.packageManager.getLaunchIntentForPackage(it) } ?: return
        runCatching { app.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ── X4 settings ─────────────────────────────────────────────

    /** The X4's "Reversed page turn (Vertical & Manga)": left button forward in manga; null until read. */
    var x4ReverseTurn by mutableStateOf<Boolean?>(null); private set

    fun readX4Settings() = launchBusy("Reading the X4's settings…") {
        val ip = x4()
        x4ReverseTurn = deviceClient.getSetting(ip, REVERSE_TURN)?.let { it != 0 }
        if (x4ReverseTurn == null) message = "This X4 firmware has no reversed page turn setting"
    }

    fun setReverseTurn(on: Boolean) = launchBusy("Changing the X4's page turn…") {
        val ip = x4()
        deviceClient.setSetting(ip, REVERSE_TURN, if (on) 1 else 0)
        x4ReverseTurn = deviceClient.getSetting(ip, REVERSE_TURN)?.let { it != 0 }   // read back: what the X4 kept
        message = if (x4ReverseTurn == on) (if (on) "Manga: the left button now turns forward" else "Manga: the right button turns forward again")
                  else "The X4 didn't keep the change"
    }

    // ── Covers from Komikku ───────────────────────────────────────

    /** Whether the granted folder holds a Komikku backup (for the Settings hint). */
    var komikkuBackup by mutableStateOf<Boolean?>(null); private set

    /**
     * Send the server the covers Komikku shows, from its newest backup, when that backup is new. Titles are
     * matched ignoring punctuation (Komikku's folder names replace characters like "?" with "_").
     */
    private suspend fun syncKomikkuCovers() {
        val tree = prefs.komikkuTree ?: return
        val (uri, modified) = KomikkuBackup.latest(getApplication(), Uri.parse(tree)) ?: run { komikkuBackup = false; return }
        komikkuBackup = true
        val key = "$uri@$modified"
        if (key == prefs.komikkuBackupSeen) return
        val entries = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { KomikkuBackup.parse(it.readBytes()) }
        val byTitle = entries.associateBy { DeviceLibrary.norm(it.title) }
        val wanted = library.mapNotNull { t -> t.folder?.let { f -> (byTitle[DeviceLibrary.norm(t.title)] ?: byTitle[DeviceLibrary.norm(f)])?.let { f to it.coverUrl } } }.toMap()
        if (wanted.isNotEmpty()) {
            val saved = api.komikkuCovers(wanted)
            saved.filterValues { it }.keys.forEach { loadCover(it, force = true) }
            if (saved.values.any { it }) message = "Covers from Komikku: ${saved.values.count { it }} series" +
                if (prefs.coverPage) " (Fix problems or Sync updates the cover pages)" else ""
        }
        prefs.komikkuBackupSeen = key
    }

    // ── App updates ─────────────────────────────────────────────

    var update by mutableStateOf<UpdateInfo?>(null); private set
    private var updateChecked = false

    fun checkUpdate() = launchBusy("Checking for updates…") {
        update = Updater.check(getApplication(), api)
        if (update == null) message = "Up to date (${Updater.installedName(getApplication())})"
    }

    fun installUpdate() {
        val info = update ?: return
        val app = getApplication<Application>()
        if (!Updater.canInstall(app)) {
            message = "Allow \"Install unknown apps\" for Komikku → Matcha, then tap Install again"
            Updater.openInstallPermission(app)
            return
        }
        launchBusy("Downloading update ${info.versionName}…") {
            Updater.install(app, api, info) { done, total -> if (total > 0) busy = "Downloading update ${info.versionName}: ${done * 100 / total}%" }
            message = "Installing ${info.versionName}…"
        }
    }

    /** Switching screen or cover page makes every chapter "not converted"; Sync converts them again. */
    fun setDevice(device: String) { prefs.device = device; refreshAll(); message = "Next Sync converts the chapters for the ${device.uppercase()}" }
    fun setCoverPage(on: Boolean) { prefs.coverPage = on; refreshAll(); message = "Next Sync converts the chapters ${if (on) "with" else "without"} a cover page" }

    fun setAutoSync(enabled: Boolean = prefs.autoSync, hours: Int = prefs.autoSyncHours,
                    homeOnly: Boolean = prefs.autoSyncHomeOnly, charging: Boolean = prefs.autoSyncCharging) {
        prefs.autoSync = enabled; prefs.autoSyncHours = hours; prefs.autoSyncHomeOnly = homeOnly; prefs.autoSyncCharging = charging
        AutoSync.schedule(getApplication())
    }

    fun cancelTask() {
        TaskBus.cancel(getApplication())
        message = "Cancelling…"
    }

    fun autoSyncNow() {
        AutoSync.runNow(getApplication())
        message = "Auto-sync started (result appears under Settings and as a notification)"
    }

    fun cancel(job: JobInfo) = launchBusy("Cancelling…") { api.cancel(job.id); jobs = api.jobs() }

    fun watch(job: JobInfo) {
        TaskBus.enqueue(getApplication(), Task.Watch(job.id, if (job.kind == "push") "Send to X4" else "Conversion"))
    }

    fun discover() = launchBusy("Looking for the X4…") {
        val phone = runCatching { deviceClient.discover() }.getOrDefault(emptyList())
        val server = runCatching { api.discover() }.getOrDefault(emptyList())
        devices = (phone + server).distinctBy { it.ip }
        if (devices.isEmpty()) message = "No X4 found. Is it in File Transfer mode, on the same WiFi?"
    }

    /** [device]: an X4 address (blank = find it), or [CARD] for the SD card plugged into this phone. */
    fun send(allPaths: List<String>, device: String, viaServer: Boolean, replace: Boolean, skipRead: Boolean = false) {
        val bookPaths = if (skipRead) allPaths.filter { it !in komikkuRead } else allPaths
        if (bookPaths.isEmpty()) { message = "Everything selected is already read in Komikku"; return }
        prefs.deviceIp = device
        // Books already on the X4 under another folder are replaced where they are, not duplicated.
        val dests = bookPaths.mapNotNull { p -> x4Match[p]?.let { p to it } }.toMap()
        val titles = books.filter { it.path in bookPaths }.associate { it.path to it.title }
        TaskBus.enqueue(getApplication(),
            if (viaServer) Task.ServerPush(device, bookPaths, replace, dests)
            else Task.PhonePush(device, bookPaths, replace, dests, titles))
        message = "Sending ${bookPaths.size} book(s) to the X4…"
    }

    /**
     * Download to Downloads/Matcha via the system DownloadManager (it shows its own progress and
     * "complete" notifications). A fully selected title becomes one zip; otherwise one per chapter.
     * Every zip unpacks to manga/<title>/<chapter>/, ready for the SD card root.
     */
    fun download(selected: Set<String>) = launchBusy("Starting downloads…") {
        val dm = getApplication<Application>().getSystemService(DownloadManager::class.java)
        val byTitle = books.groupBy { it.titleFolder }
        val targets = mutableListOf<String>()
        for ((title, bs) in byTitle) {
            val chosen = bs.filter { it.path in selected }
            if (chosen.isEmpty()) continue
            if (chosen.size == bs.size && bs.size > 1) targets += "manga/$title" else targets += chosen.map { it.path }
        }
        for (path in targets) {
            val name = path.split('/').drop(1).joinToString(" - ").replace(Regex("[\\\\/:*?\"<>|]"), "_")
            dm.enqueue(DownloadManager.Request(Uri.parse(api.zipUrl(path)))
                .addRequestHeader("Authorization", api.authHeader)
                .setTitle(name).setDescription("Matcha Reader book")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Matcha/$name.zip"))
        }
        message = "Downloading ${targets.size} zip(s) to Downloads/Matcha"
    }

    fun testConnection() = launchBusy("Connecting…") {
        api.reset()
        val url = api.baseUrl()
        api.library()  // also checks the token
        serverUrl = url
        message = "Connected via $url"
    }
}

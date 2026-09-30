package com.schindler.k2m

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** What [Api] needs to know; [Prefs] on the phone, a plain object in tests. */
interface ServerSettings {
    val homeUrl: String
    val remoteUrl: String
    val token: String
    /** Screen the server converts for: "x4" (X4, X4 Pro: 480×800) or "x3" (528×792). */
    val device: String get() = "x4"
    /** Add a cover page (series art + large chapter number) as each chapter's first page. */
    val coverPage: Boolean get() = true
    /** Have the server draw a sleep screen from the series art when it converts a series. */
    val sleepGenerate: Boolean get() = false
}

/** App settings. Stored in app-private preferences, which other apps can't read. */
class Prefs(ctx: Context) : ServerSettings {
    private val p = ctx.getSharedPreferences("k2m", Context.MODE_PRIVATE)
    override var homeUrl: String
        get() = p.getString("homeUrl", "")!!
        set(v) = p.edit().putString("homeUrl", v.trim().trimEnd('/')).apply()
    override var remoteUrl: String
        get() = p.getString("remoteUrl", "")!!
        set(v) = p.edit().putString("remoteUrl", v.trim().trimEnd('/')).apply()
    override var token: String
        get() = p.getString("token", "")!!
        set(v) = p.edit().putString("token", v.trim()).apply()
    var komikkuTree: String?
        get() = p.getString("komikkuTree", null)
        set(v) = p.edit().putString("komikkuTree", v).apply()
    override var device: String
        get() = p.getString("device", "x4")!!
        set(v) = p.edit().putString("device", v).apply()
    override var coverPage: Boolean
        get() = p.getBoolean("coverPage", true)
        set(v) = p.edit().putBoolean("coverPage", v).apply()
    override var sleepGenerate: Boolean
        get() = p.getBoolean("sleepGenerate", false)
        set(v) = p.edit().putBoolean("sleepGenerate", v).apply()
    /** Send a series' sleep screen to the X4's /sleep folder along with its books. */
    var sleepSend: Boolean
        get() = p.getBoolean("sleepSend", false)
        set(v) = p.edit().putBoolean("sleepSend", v).apply()
    /** The last address the X4 answered at (auto-discovery tries it first). */
    var lastX4: String
        get() = p.getString("lastX4", "")!!
        set(v) = p.edit().putString("lastX4", v).apply()

    /** Install app updates from the server without asking first (Android may still show its confirmation). */
    var autoUpdate: Boolean
        get() = p.getBoolean("autoUpdate", true)
        set(v) = p.edit().putBoolean("autoUpdate", v).apply()
    /** Allow converting chapters right on the phone (no OCR/search/covers/delta sync) when there's no
     *  server to do the real conversion -- off by default; the Settings toggle warns before turning it on. */
    var phoneConvertFallback: Boolean
        get() = p.getBoolean("phoneConvertFallback", false)
        set(v) = p.edit().putBoolean("phoneConvertFallback", v).apply()
    /** "<uri>@<modified>" of the last Komikku backup whose covers were sent to the server. */
    var komikkuBackupSeen: String
        get() = p.getString("komikkuBackupSeen", "")!!
        set(v) = p.edit().putString("komikkuBackupSeen", v).apply()

    /** Where the X4's card is for sending and the X4 tools: "wifi" (in the X4) or "card" (plugged into this phone). */
    var target: String
        get() = p.getString("target", "wifi")!!
        set(v) = p.edit().putString("target", v).apply()
    /** The SD card's root, granted through the system folder picker. */
    var cardTree: String?
        get() = p.getString("cardTree", null)
        set(v) = p.edit().putString("cardTree", v).apply()

    /** Manual X4 address; blank means find it automatically. */
    var deviceIp: String
        get() = p.getString("deviceIp", "")!!
        set(v) = p.edit().putString("deviceIp", v.trim()).apply()
    /** Last X4 library scan, as JSON (see [DeviceScanStore]). */
    var deviceScan: String?
        get() = p.getString("deviceScan", null)
        set(v) = p.edit().putString("deviceScan", v).apply()

    var autoSync: Boolean
        get() = p.getBoolean("autoSync", false)
        set(v) = p.edit().putBoolean("autoSync", v).apply()
    var autoSyncHours: Int
        get() = p.getInt("autoSyncHours", 6)
        set(v) = p.edit().putInt("autoSyncHours", v).apply()
    /** Only sync when the server answers at the home (LAN) URL; never over the remote URL. */
    var autoSyncHomeOnly: Boolean
        get() = p.getBoolean("autoSyncHomeOnly", true)
        set(v) = p.edit().putBoolean("autoSyncHomeOnly", v).apply()
    var autoSyncCharging: Boolean
        get() = p.getBoolean("autoSyncCharging", false)
        set(v) = p.edit().putBoolean("autoSyncCharging", v).apply()
    var autoSyncLast: String
        get() = p.getString("autoSyncLast", "")!!
        set(v) = p.edit().putString("autoSyncLast", v).apply()

    /** These settings with the remote URL blanked out, for home-network-only work. */
    fun homeOnly(): ServerSettings = object : ServerSettings {
        override val homeUrl = this@Prefs.homeUrl
        override val remoteUrl = ""
        override val token = this@Prefs.token
        override val device = this@Prefs.device
        override val coverPage = this@Prefs.coverPage
        override val sleepGenerate = this@Prefs.sleepGenerate
    }
}

class ApiException(msg: String) : IOException(msg)

data class ServerChapter(val file: String, val size: Long, val book: String, val status: String, val textBlocks: Int?)
data class ServerTitle(val dir: String, val folder: String, val chapters: List<ServerChapter>, val cover: Boolean = false,
                       val ignored: Boolean = false)
/** Chapters ("<title dir>/<file>") and whole series (title dirs) the user deleted and doesn't want synced again. */
data class Ignored(val titles: Set<String> = emptySet(), val chapters: Set<String> = emptySet())
data class CoverChoice(val id: String, val source: String, val label: String)
data class Outcome(val label: String, val status: String, val detail: String, val warning: String)
data class JobInfo(
    val id: String, val kind: String, val status: String, val total: Int?, val done: Int,
    val current: String?, val error: String?, val created: Double, val outcomes: List<Outcome>,
    /** Conversion estimate from the server (null until it has one), and page progress. */
    val etaSeconds: Long? = null, val pagesTotal: Int? = null, val pagesDone: Int = 0,
    val currentPages: Int? = null, val toConvert: Int? = null, val note: String? = null,
    /** A server-side push's byte progress across the whole job (an upper bound: a delta sync may send less
     *  than a book's full size), so the bar still moves within a single book. */
    val bytesTotal: Long? = null, val bytesDone: Long = 0,
    /** Title folders the job is about (converted / sent / deleted). */
    val titles: List<String> = emptyList(),
) {
    val active get() = status == "queued" || status == "running"
}
data class Book(val path: String, val title: String, val files: Int, val bytes: Long, val dat: Long = -1, val sig: String = "") {
    /** "manga/<title folder>/<chapter folder>" → the title folder, for grouping. */
    val titleFolder get() = path.split('/').getOrElse(1) { path }
    val name get() = path.substringAfterLast('/')
}
/** A sleep screen on the server: one per series folder ([name]); [custom] is the user's own image. */
data class SleepItem(val name: String, val series: String, val bytes: Long, val mtime: Double, val custom: Boolean)
data class Device(val ip: String, val hostname: String, val via: String)
/** [fix]: "reconvert" (bad pages: convert again from scratch), "convert" (other screen / no cover page), or
 *  "cover" (redraw the cover page with series art); [folder] is the title folder, for cover art. */
data class BookProblem(val path: String, val issues: List<String>, val chapter: String?, val fix: String? = null, val folder: String? = null)

/** k2m_server.py's book_sig: the version fingerprint of a book from its top-level files' names and sizes. */
fun bookSig(files: List<Pair<String, Long>>): String {
    val top = files.filter { '/' !in it.first }.map { "${it.first}:${it.second}" }.sorted().joinToString("\n")
    return java.security.MessageDigest.getInstance("SHA-1").digest(top.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}

/**
 * Client for k2m_server.py. Tries the home (LAN) URL first with a short timeout, then the remote
 * HTTPS one, and remembers which answered until [reset].
 */
class Api(private val prefs: ServerSettings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS).build()
    private val probe = http.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    @Volatile private var base: String? = null
    val currentBase get() = base

    fun reset() { base = null }

    suspend fun baseUrl(): String = base ?: withContext(Dispatchers.IO) {
        for (url in listOf(prefs.homeUrl, prefs.remoteUrl).filter { it.isNotBlank() }) {
            try {
                probe.newCall(Request.Builder().url("$url/api/health").build()).execute().use {
                    if (it.isSuccessful && it.body!!.string().contains("\"ok\"")) { base = url; return@withContext url }
                }
            } catch (_: Exception) { }
        }
        throw ApiException("Server not reachable at the home or remote URL")
    }

    private fun req(url: String) = Request.Builder().url(url).header("Authorization", "Bearer ${prefs.token}")

    private suspend fun call(path: String, build: (Request.Builder) -> Request.Builder = { it }): String =
        withContext(Dispatchers.IO) {
            val url = baseUrl() + path
            try {
                http.newCall(build(req(url)).build()).execute().use { r ->
                    val body = r.body!!.string()
                    if (!r.isSuccessful) {
                        val msg = runCatching { JSONObject(body).getString("error") }.getOrDefault(body.take(200))
                        throw ApiException(if (r.code == 401) "Wrong token (check Settings)" else "HTTP ${r.code}: $msg")
                    }
                    body
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                reset()  // the network may have changed (home ↔ away); probe again next time
                throw ApiException("Network error: ${e.message}")
            }
        }

    private suspend fun getJson(path: String) = JSONObject(call(path))
    private suspend fun postJson(path: String, body: JSONObject) =
        JSONObject(call(path) { it.post(body.toString().toRequestBody(JSON)) })

    /** Titles and chapters; a chapter made for another screen or cover setting counts as not converted. */
    suspend fun library(): List<ServerTitle> = libraryWithIgnored().first

    suspend fun libraryWithIgnored(): Pair<List<ServerTitle>, Ignored> {
        val j = getJson("/api/library?device=${prefs.device}&cover_page=${if (prefs.coverPage) 1 else 0}")
        val titles = j.getJSONArray("titles").map { t ->
            ServerTitle(t.getString("dir"), t.getString("folder"), t.getJSONArray("chapters").map { c ->
                ServerChapter(c.getString("file"), c.getLong("size"), c.getString("book"), c.getString("status"),
                    if (c.isNull("text_blocks")) null else c.getInt("text_blocks"))
            }, t.optBoolean("cover"), t.optBoolean("ignored"))
        }
        val ig = j.optJSONObject("ignored")
        fun set(k: String) = ig?.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        return titles to Ignored(set("titles"), set("chapters"))
    }

    /** Delete converted books on the server; [ignore] also drops their CBZs and keeps them from syncing again,
     *  [ignoreTitles] (title dirs) does that for whole series, future chapters included. Runs as a job. */
    suspend fun deleteBooks(books: List<String>, ignore: Boolean, ignoreTitles: List<String>): JobInfo =
        job(postJson("/api/books/delete", JSONObject().put("books", JSONArray(books)).put("ignore", ignore)
            .put("ignore_titles", JSONArray(ignoreTitles))))

    suspend fun unignore(titles: List<String>, chapters: List<String>) {
        postJson("/api/ignored/remove", JSONObject().put("titles", JSONArray(titles)).put("chapters", JSONArray(chapters)))
    }

    suspend fun coverChoices(folder: String): List<CoverChoice> =
        getJson("/api/covers/candidates?title=${enc(folder)}").getJSONArray("candidates").map {
            CoverChoice(it.getString("id"), it.getString("source"), it.getString("label"))
        }

    suspend fun coverChoiceImage(folder: String, id: String, width: Int): ByteArray? = withContext(Dispatchers.IO) {
        val url = baseUrl() + "/api/covers/candidate?title=${enc(folder)}&id=${enc(id)}&w=$width"
        runCatching { http.newCall(req(url).build()).execute().use { r -> if (r.isSuccessful) r.body!!.bytes() else null } }.getOrNull()
    }

    suspend fun chooseCover(folder: String, id: String) {
        postJson("/api/covers/choose", JSONObject().put("title", folder).put("id", id))
    }

    /** A series' cover art, scaled to [width] px wide; null if the server has none. */
    suspend fun cover(folder: String, width: Int): ByteArray? = withContext(Dispatchers.IO) {
        val url = baseUrl() + "/api/covers?title=${enc(folder)}&w=$width"
        runCatching { http.newCall(req(url).build()).execute().use { r -> if (r.isSuccessful) r.body!!.bytes() else null } }.getOrNull()
    }

    suspend fun setCover(folder: String, image: ByteArray) {
        call("/api/covers?title=${enc(folder)}") { it.put(image.toRequestBody("image/jpeg".toMediaType())) }
    }

    /** Drop the user's own cover; with [refetch], also look it up on AniList again. Returns whether art exists. */
    suspend fun resetCover(folder: String, refetch: Boolean): Boolean =
        postJson(if (refetch) "/api/covers/refetch" else "/api/covers/reset", JSONObject().put("title", folder)).optBoolean("found", true)

    suspend fun upload(cr: ContentResolver, uri: Uri, size: Long, title: String, file: String,
                       onProgress: (Long) -> Unit) = upload({ cr.openInputStream(uri)!! }, size, title, file, onProgress)

    suspend fun upload(open: () -> InputStream, size: Long, title: String, file: String, onProgress: (Long) -> Unit) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
            }
        }
        call("/api/upload?title=${enc(title)}&file=${enc(file)}") { it.put(body) }
    }

    suspend fun startJob(chapters: List<String>, force: Boolean = false): JobInfo =
        job(postJson("/api/jobs", JSONObject().put("chapters", JSONArray(chapters)).put("force", force)
            .put("device", prefs.device).put("cover_page", prefs.coverPage).put("sleep", prefs.sleepGenerate)))

    suspend fun job(id: String): JobInfo = job(getJson("/api/jobs/$id"))
    suspend fun jobs(): List<JobInfo> = getJson("/api/jobs").getJSONArray("jobs").map { job(it) }
    suspend fun cancel(id: String) { postJson("/api/jobs/$id/cancel", JSONObject()) }
    /** The conversion tool's own log for a job, or a summary of its outcomes for a push/delete (no log file). */
    suspend fun jobLog(id: String): String = getJson("/api/jobs/$id/log").optString("text")
    /** Drops every job that isn't queued or running; returns how many were removed. */
    suspend fun clearFinishedJobs(): Int = postJson("/api/jobs/clear", JSONObject()).optInt("removed")

    suspend fun books(): List<Book> = getJson("/api/books").getJSONArray("books").map {
        Book(it.getString("path"), it.getString("title"), it.getInt("files"), it.getLong("bytes"), it.optLong("dat", -1),
            it.optString("sig"))
    }

    /** Books the server found problems in, with the source chapter to re-convert each from. */
    suspend fun checkBooks(): List<BookProblem> =
        getJson("/api/books/check?device=${prefs.device}&cover_page=${if (prefs.coverPage) 1 else 0}").getJSONArray("problems").map {
            fun opt(k: String) = it.optString(k).takeIf { v -> !it.isNull(k) && v.isNotEmpty() }
            BookProblem(it.getString("path"), it.getJSONArray("issues").let { a -> (0 until a.length()).map { i -> a.getString(i) } },
                opt("chapter"), opt("fix"), opt("folder"))
        }

    /** Hand the server the covers Komikku shows ({title folder: URL}); returns folder → saved. */
    suspend fun komikkuCovers(covers: Map<String, String>): Map<String, Boolean> {
        val saved = postJson("/api/covers/komikku", JSONObject().put("covers", JSONObject(covers))).getJSONObject("saved")
        return saved.keys().asSequence().associateWith { saved.getBoolean(it) }
    }

    /** Look series art up again for [folders] that have none; returns folder → found. */
    suspend fun fetchMissingCovers(folders: List<String>): Map<String, Boolean> {
        val found = postJson("/api/covers/fetch-missing", JSONObject().put("titles", JSONArray(folders))).getJSONObject("found")
        return found.keys().asSequence().associateWith { found.getBoolean(it) }
    }

    suspend fun manifest(path: String): List<Pair<String, Long>> = manifestWithShas(path).first

    /** A book's files (panels.idx last) and each one's content hash. */
    suspend fun manifestWithShas(path: String): Pair<List<Pair<String, Long>>, Map<String, String>> {
        val files = getJson("/api/books/manifest?path=${enc(path)}").getJSONArray("files")
        val list = files.map { it.getString("name") to it.getLong("size") }
        val shas = files.map { it.getString("name") to it.optString("sha1") }.filter { it.second.isNotEmpty() }.toMap()
        return list to shas
    }

    suspend fun fileBytes(path: String, name: String): ByteArray = withContext(Dispatchers.IO) {
        val url = baseUrl() + "/api/books/file?path=${enc(path)}&name=${enc(name)}"
        http.newCall(req(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("HTTP ${r.code} fetching $name")
            r.body!!.bytes()
        }
    }

    suspend fun zipUrl(path: String) = baseUrl() + "/api/books/zip?path=${enc(path)}"
    val authHeader get() = "Bearer ${prefs.token}"

    suspend fun discover(): List<Device> = getJson("/api/device/discover").getJSONArray("devices").map {
        Device(it.getString("ip"), it.getString("hostname"), "server")
    }

    suspend fun sleepList(): List<SleepItem> = getJson("/api/sleep").getJSONArray("items").map {
        SleepItem(it.getString("name"), it.optString("series", it.getString("name")), it.getLong("bytes"), it.optDouble("mtime"), it.optBoolean("custom"))
    }

    /** A JPEG preview of a sleep screen, [width] px wide; null if there isn't one. */
    suspend fun sleepImage(name: String, width: Int): ByteArray? = withContext(Dispatchers.IO) {
        val url = baseUrl() + "/api/sleep/image?name=${enc(name)}&w=$width"
        runCatching { http.newCall(req(url).build()).execute().use { r -> if (r.isSuccessful) r.body!!.bytes() else null } }.getOrNull()
    }

    /** The sleep screen's BMP, as it goes on the X4; null if the server has none (or can't be reached). */
    suspend fun sleepFile(name: String): ByteArray? = withContext(Dispatchers.IO) {
        val url = baseUrl() + "/api/sleep/file?name=${enc(name)}"
        runCatching { http.newCall(req(url).build()).execute().use { r -> if (r.isSuccessful) r.body!!.bytes() else null } }.getOrNull()
    }

    /** Draw sleep screens from series art for [folders] (null = every series); returns folder → result. */
    suspend fun sleepGenerate(folders: List<String>?, force: Boolean = false): Map<String, String> {
        val body = JSONObject().put("device", prefs.device).put("force", force)
        if (folders == null) body.put("all", true) else body.put("titles", JSONArray(folders))
        val r = postJson("/api/sleep/generate", body).getJSONObject("results")
        return r.keys().asSequence().associateWith { r.getString(it) }
    }

    suspend fun setSleep(name: String, image: ByteArray) {
        call("/api/sleep?name=${enc(name)}&device=${prefs.device}") { it.put(image.toRequestBody("image/jpeg".toMediaType())) }
    }

    suspend fun deleteSleep(names: List<String>) {
        postJson("/api/sleep/delete", JSONObject().put("names", JSONArray(names)))
    }

    suspend fun serverPush(device: String, books: List<String>, replace: Boolean, dests: Map<String, String> = emptyMap(),
                           sleep: Boolean = false): JobInfo =
        job(postJson("/api/device/push", JSONObject().put("device", device).put("books", JSONArray(books)).put("replace", replace)
            .put("sleep", sleep)
            .put("dests", JSONObject(dests.mapValues { "/" + it.value.trim('/') }))))

    private fun job(j: JSONObject) = JobInfo(
        j.getString("id"), j.getString("kind"), j.getString("status"),
        if (j.isNull("total")) null else j.getInt("total"), j.optInt("done"),
        j.optString("current").takeIf { !j.isNull("current") && it.isNotEmpty() },
        j.optString("error").takeIf { !j.isNull("error") && it.isNotEmpty() },
        j.optDouble("created"),
        (j.optJSONArray("outcomes") ?: JSONArray()).map {
            Outcome(it.optString("label"), it.optString("status"), it.optString("detail"), it.optString("warning"))
        },
        etaSeconds = if (j.isNull("eta_seconds")) null else j.optLong("eta_seconds", -1).takeIf { it >= 0 },
        pagesTotal = if (j.isNull("pages_total")) null else j.optInt("pages_total", -1).takeIf { it >= 0 },
        pagesDone = j.optInt("pages_done"),
        currentPages = if (j.isNull("current_pages")) null else j.optInt("current_pages", -1).takeIf { it > 0 },
        toConvert = if (j.isNull("to_convert")) null else j.optInt("to_convert", -1).takeIf { it >= 0 },
        note = j.optString("note").takeIf { !j.isNull("note") && it.isNotEmpty() },
        bytesTotal = if (j.isNull("bytes_total")) null else j.optLong("bytes_total", -1).takeIf { it >= 0 },
        bytesDone = j.optLong("bytes_done"),
        titles = (j.optJSONArray("titles") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } },
    )

    companion object {
        private val JSON = "application/json".toMediaType()
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }

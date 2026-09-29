package com.schindler.k2m

import org.json.JSONArray
import org.json.JSONObject

/**
 * What's on the X4's SD card, from the last scan (plus what the app has sent since).
 * [manga] maps a book folder on the X4 ("Manga/<title>/<chapter>", no leading slash, as it's spelled there)
 * to its version fingerprint ([bookSig] of its files), compared with the server's; [titles] holds each one's
 * meta.bin title;
 * [otherBooks] are EPUB/XTC/TXT files.
 */
data class DeviceLibrary(val manga: Map<String, String>, val otherBooks: List<String>, val scannedAt: Long,
                         val titles: Map<String, String> = emptyMap(),
                         /** Chapter folders without panels.idx, which the X4 doesn't list (an interrupted copy). */
                         val incomplete: List<String> = emptyList()) {

    /** Server book path → its incomplete folder on the X4, matched the same way as complete ones. */
    fun matchIncomplete(books: List<Book>): Map<String, String> =
        DeviceLibrary(incomplete.associateWith { "" }, emptyList(), scannedAt, titles).match(books)


    /**
     * Server book path → where that book is on the X4. Books are recognised even when they were copied by
     * hand under other folder names: first the same path ignoring case (the SD card's FAT/exFAT ignores
     * case), then the same meta.bin title, then the same chapter folder in a title folder whose name
     * contains the other's. Each X4 folder matches at most one book.
     */
    fun match(books: List<Book>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val free = manga.keys.toMutableSet()
        fun take(book: Book, dev: String) { out[book.path] = dev; free -= dev }
        val byLower = manga.keys.associateBy { it.lowercase() }
        for (b in books) byLower[b.path.lowercase()]?.takeIf { it in free }?.let { take(b, it) }
        val byTitle = free.mapNotNull { d -> titles[d]?.let { norm(it) to d } }.toMap()
        for (b in books) if (b.path !in out) byTitle[norm(b.title)]?.takeIf { it in free }?.let { take(b, it) }
        for (b in books) if (b.path !in out) {
            val (bt, bc) = titleAndChapter(b.path)
            free.firstOrNull { d ->
                val (dt, dc) = titleAndChapter(d)
                // 6+ characters, so a short title folder ("A") can't claim another one's chapters ("AB").
                dc.equals(bc, ignoreCase = true) && minOf(dt.length, bt.length) >= 6 && (bt.contains(dt) || dt.contains(bt))
            }?.let { take(b, it) }
        }
        return out
    }

    private fun titleAndChapter(path: String): Pair<String, String> {
        val parts = path.trim('/').split('/')
        return norm(parts.getOrElse(parts.size - 2) { "" }) to parts.last()
    }

    companion object {
        /** Lowercase letters and digits only, numbers without leading zeros: "Chapter 03" ~ "chapter 3". */
        fun norm(s: String) = Regex("\\d+").replace(s.lowercase()) { it.value.trimStart('0').ifEmpty { "0" } }
            .filter { it.isLetterOrDigit() }
    }
}

/** Keeps the last [DeviceLibrary] in the app's preferences. */
class DeviceScanStore(private val prefs: Prefs) {
    fun load(): DeviceLibrary? = prefs.deviceScan?.let { runCatching {
        val o = JSONObject(it)
        if (o.optInt("v") != 2) return null  // v1 kept panels.dat sizes, which missed page-only changes: re-check
        val m = o.getJSONObject("manga")
        val t = o.optJSONObject("titles") ?: JSONObject()
        val inc = o.optJSONArray("incomplete") ?: JSONArray()
        DeviceLibrary(m.keys().asSequence().associateWith { k -> m.getString(k) },
            o.getJSONArray("other").let { a -> (0 until a.length()).map { i -> a.getString(i) } }, o.getLong("at"),
            t.keys().asSequence().associateWith { k -> t.getString(k) }, (0 until inc.length()).map { inc.getString(it) })
    }.getOrNull() }

    fun save(lib: DeviceLibrary) {
        prefs.deviceScan = JSONObject().put("manga", JSONObject(lib.manga)).put("other", JSONArray(lib.otherBooks))
            .put("at", lib.scannedAt).put("titles", JSONObject(lib.titles)).put("incomplete", JSONArray(lib.incomplete))
            .put("v", 2).toString()
    }

    @Synchronized fun forget(paths: Collection<String>) {
        val lib = load() ?: return
        save(lib.copy(manga = lib.manga - paths.toSet(), titles = lib.titles - paths.toSet(), incomplete = lib.incomplete - paths.toSet()))
    }

    @Synchronized fun markOnDevice(path: String, sig: String, title: String? = null) {
        val lib = load() ?: DeviceLibrary(emptyMap(), emptyList(), 0)
        save(lib.copy(manga = lib.manga + (path to sig), incomplete = lib.incomplete.filter { !it.equals(path, ignoreCase = true) }, titles = if (title != null) lib.titles + (path to title) else lib.titles))
    }

    /** After a server-side push: [sent] maps server book path → X4 path; sizes and titles from the server's list. */
    suspend fun markOnDevice(sent: Map<String, String>, api: Api) {
        if (sent.isEmpty()) return
        val books = runCatching { api.books().associateBy { it.path } }.getOrDefault(emptyMap())
        sent.forEach { (path, dev) -> markOnDevice(dev, books[path]?.sig ?: "", books[path]?.title) }
    }
}

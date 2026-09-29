package com.schindler.k2m

import java.io.IOException

/** What a delta sync sent: [sent] of [total] files ([bytes]), and how many stray files it removed. */
data class Delta(val sent: Int, val total: Int, val bytes: Long, val removed: Int)

/** What a delta sync of a book already on the card would send and delete. */
data class DeltaPlan(val need: List<String>, val stray: List<String>) {
    val empty get() = need.isEmpty() && stray.isEmpty()
    /** Files the phone needs to carry it out: what's sent, plus panels.idx which is re-put last. */
    fun toFetch(): Set<String> = if (empty) emptySet() else
        need.toSet() + if (need.all { it in BookOps.INCREMENTAL_FILES } && stray.isEmpty()) emptySet() else setOf("panels.idx")
}

/**
 * Everything the app does with books on the X4's card, for any [X4Storage] (WiFi or the card plugged into
 * the phone). The firmware's rules this relies on:
 *  - a folder is a manga book once it holds panels.idx, so panels.idx is always written last
 *  - reading position and render caches live in /.crosspoint/manga_<std::hash of the book path>
 *  - "finished" = on the last page; "mark as read" writes the page count to progress.bin
 */
class BookOps(val storage: X4Storage) {
    /** Set by [push] after a delta sync (null after a whole copy). */
    @Volatile var lastDelta: Delta? = null

    private suspend fun sizes(path: String): Map<String, Long>? =
        storage.list(path)?.filter { !it.dir }?.associate { it.name to it.size }

    // ── Library ──────────────────────────────────────────────────

    /**
     * Walk the card and list what's on it. A folder holding panels.idx is a manga book and isn't descended into
     * (its pages and crops are hundreds of files); one with pages but no panels.idx is an incomplete copy.
     */
    suspend fun scanLibrary(onFolder: (String) -> Unit): DeviceLibrary {
        val manga = linkedMapOf<String, String>()
        val titles = linkedMapOf<String, String>()
        val other = mutableListOf<String>()
        val incomplete = mutableListOf<String>()
        val queue = ArrayDeque(listOf("/" to 0))
        var folders = 0
        while (queue.isNotEmpty() && folders < MAX_SCAN_FOLDERS) {
            val (path, depth) = queue.removeFirst()
            folders++
            onFolder(path)
            val entries = storage.list(path).orEmpty().filter { !it.name.startsWith(".") }
            val files = entries.filter { !it.dir }
            val here = path.trim('/')
            if (files.any { it.name == "panels.idx" }) {
                manga[here] = bookSig(files.map { it.name to it.size })
                if (files.any { it.name == "meta.bin" }) metaTitle(storage.read("${path.trimEnd('/')}/meta.bin"))?.let { titles[here] = it }
                continue
            }
            if (files.any { PAGE_FILE.matches(it.name) || it.name == "panels.dat" } ||
                (entries.any { it.dir && it.name == "panels" } && files.any { it.name == "meta.bin" })) {
                incomplete += here
                if (files.any { it.name == "meta.bin" }) metaTitle(storage.read("${path.trimEnd('/')}/meta.bin"))?.let { titles[here] = it }
                continue
            }
            files.filter { it.name.substringAfterLast('.', "").lowercase() in BOOK_EXTENSIONS }
                .forEach { other += (path.trimEnd('/') + "/" + it.name).trimStart('/') }
            if (depth < MAX_SCAN_DEPTH) entries.filter { it.dir }.forEach { queue += (path.trimEnd('/') + "/" + it.name) to depth + 1 }
        }
        return DeviceLibrary(manga, other, System.currentTimeMillis(), titles, incomplete)
    }

    // ── Sending ─────────────────────────────────────────────────

    /** The delta for a book already on the card, or null if it isn't there. */
    suspend fun plan(dest: String, files: List<Pair<String, Long>>, shas: Map<String, String>, known: Map<String, String>): DeltaPlan? {
        val have = sizes(dest) ?: return null
        val (need, stray) = diff(dest, have, files, shas, known)
        return DeltaPlan(need, stray)
    }

    private suspend fun diff(dest: String, have: Map<String, Long>, files: List<Pair<String, Long>>,
                             shas: Map<String, String>, known: Map<String, String>): Pair<List<String>, List<String>> {
        val all = HashMap(have)
        if (files.any { it.first.startsWith("panels/") })
            sizes("$dest/panels")?.forEach { (n, s) -> all["panels/$n"] = s }
        val need = files.filter { (n, s) -> all[n] != s || (known[n] != null && shas[n] != null && known[n] != shas[n]) }.map { it.first }
        val wanted = files.map { it.first }.toSet()
        return need to all.keys.filter { it !in wanted && BOOK_FILE.matches(it) }
    }

    /**
     * Copy a book to [dest]. A new book (or [replace]) is copied whole, panels.idx last. One already there gets a
     * delta sync: only missing, differently sized, or changed files ([shas] vs [known], what was sent before) go
     * over; book files the new copy doesn't have are deleted; when pages change the book is hidden (panels.idx
     * removed) until it's consistent again. Returns "pushed", "updated" or "skipped".
     */
    suspend fun push(dest: String, files: List<Pair<String, Long>>, replace: Boolean, fetch: suspend (String) -> ByteArray,
                     onBytes: (Long) -> Unit = {}, shas: Map<String, String> = emptyMap(), known: Map<String, String> = emptyMap(),
                     onFile: (Int) -> Unit): String {
        val have = sizes(dest)
        if (have != null && !replace) {
            val (need, stray) = diff(dest, have, files, shas, known)
            lastDelta = Delta(need.size, files.size, files.filter { it.first in need }.sumOf { it.second }, stray.size)
            if (need.isEmpty() && stray.isEmpty()) return "skipped"
            val coverOnly = stray.isEmpty() && need.all { it in INCREMENTAL_FILES }
            if (!coverOnly) {
                storage.delete("$dest/panels.idx")
                if (need.any { it.startsWith("panels/") }) storage.mkdirs("$dest/panels")
            }
            need.filter { coverOnly || it != "panels.idx" }.forEachIndexed { i, n ->
                val data = fetch(n)
                storage.write("$dest/$n", data)
                onBytes(data.size.toLong()); onFile(i + 1)
            }
            if (!coverOnly) {
                stray.forEach { storage.delete("$dest/$it") }
                val idx = fetch("panels.idx")
                storage.write("$dest/panels.idx", idx)
                onBytes(idx.size.toLong())
            }
            clearRenderCache(dest)
            onFile(files.size)
            return "updated"
        }
        lastDelta = null
        if (have != null) storage.delete(dest)
        storage.mkdirs(dest)
        val made = mutableSetOf<String>()
        files.forEachIndexed { i, (name, _) ->
            val sub = name.substringBeforeLast('/', "")
            if (sub.isNotEmpty() && made.add(sub)) storage.mkdirs("$dest/$sub")
            val data = fetch(name)
            storage.write("$dest/$name", data)
            onBytes(data.size.toLong()); onFile(i + 1)
        }
        // A replaced book keeps its render cache (page_N.2bp is keyed by page number and size), which could show
        // the old version's pixels; drop it, keeping the reading position.
        if (have != null) clearRenderCache(dest)
        return "pushed"
    }

    /** Remove a manga's cached page/panel renders and thumbnails, keeping progress.bin. */
    suspend fun clearRenderCache(bookPath: String) {
        val dir = mangaProgressFile("/" + bookPath.trim('/')).substringBeforeLast('/')
        storage.list(dir)?.forEach { if (it.name != "progress.bin") storage.delete("$dir/${it.name}") }
    }

    // ── Reading state ───────────────────────────────────────────

    /** How far each book (paths like "manga/T/Ch 1") has been read; null for one never opened. */
    suspend fun readingProgress(books: List<String>, onBook: (Int) -> Unit): Map<String, ReadingProgress?> {
        val out = linkedMapOf<String, ReadingProgress?>()
        books.forEachIndexed { i, book ->
            onBook(i + 1)
            val path = "/" + book.trim('/')
            val prog = storage.read(mangaProgressFile(path)) ?: storage.read(mangaProgressFile("$path/"))
            out[book] = if (prog == null || prog.size < 4) null else {
                val idx = storage.read("$path/panels.idx")
                ReadingProgress(le32(prog, 0), if (idx != null && idx.size >= 8) le32(idx, 4) else 0)
            }
        }
        return out
    }

    /** Mark a book finished like the X4's own "mark as read": progress.bin holding the page count. */
    suspend fun markRead(bookPath: String) {
        val path = "/" + bookPath.trim('/')
        val idx = storage.read("$path/panels.idx") ?: throw IOException("$path has no panels.idx")
        val pages = le32(idx, 4)
        val file = mangaProgressFile(path)
        storage.mkdirs(file.substringBeforeLast('/'))
        storage.write(file, ByteArray(4) { ((pages shr (8 * it)) and 0xff).toByte() })
    }

    // ── Checking and deleting ───────────────────────────────────

    /** A book's copy on the card vs the server's file list: missing, wrong-size and stray page files. */
    suspend fun compareBook(devicePath: String, files: List<Pair<String, Long>>): List<String> {
        val dev = "/" + devicePath.trim('/')
        val have = mutableMapOf<String, Long>()
        sizes(dev)?.let { have.putAll(it) }
        if (files.any { it.first.startsWith("panels/") }) sizes("$dev/panels")?.forEach { (n, s) -> have["panels/$n"] = s }
        val want = files.toMap()
        val missing = want.keys.filter { it !in have }
        val wrong = want.filter { (n, s) -> have[n] != null && have[n] != s }.keys
        val extra = have.keys.filter { it !in want && it.startsWith("page_") }
        return listOfNotNull(
            "${missing.size} file(s) missing on ${storage.label} (${missing.take(3).joinToString()})".takeIf { missing.isNotEmpty() },
            "${wrong.size} file(s) damaged (wrong size: ${wrong.take(3).joinToString()})".takeIf { wrong.isNotEmpty() },
            "stray page file(s) ${extra.take(3).joinToString()} (shift the page order)".takeIf { extra.isNotEmpty() },
        )
    }

    /** Delete books and their reading state / render caches, then any title folder left empty. */
    suspend fun deleteBooks(books: List<String>, onBook: (Int) -> Unit): List<String> {
        val deleted = mutableListOf<String>()
        books.forEachIndexed { i, book ->
            onBook(i + 1)
            val path = "/" + book.trim('/')
            storage.delete(path)
            deleted += book
            for (variant in listOf(path, "$path/")) storage.delete(mangaProgressFile(variant).substringBeforeLast('/'))
        }
        for (parent in deleted.map { it.trim('/').substringBeforeLast('/', "") }.filter { it.count { ch -> ch == '/' } >= 1 }.distinct()) {
            if (storage.list("/$parent")?.isEmpty() == true) storage.delete("/$parent")
        }
        return deleted
    }

    suspend fun deleteFolder(path: String) = storage.delete(path)

    companion object {
        const val MAX_SCAN_DEPTH = 6
        const val MAX_SCAN_FOLDERS = 3000
        private val BOOK_EXTENSIONS = setOf("epub", "xtc", "xtch", "txt", "md")
        private val PAGE_FILE = Regex("page_\\d+\\.(jpg|jpeg|png|bmp)", RegexOption.IGNORE_CASE)
        /** Files that can change without the rest of a book changing (new cover page, retitle). */
        val INCREMENTAL_FILES = setOf("page_0000.jpg", "meta.bin", "toc.idx")
        /** Files that belong to a converted book (k2m_server.BOOK_FILES); only these are ever deleted as strays. */
        private val BOOK_FILE = Regex("^(page_\\d+\\.(jpg|jpeg|png|bmp)|panels/p\\d+_\\d+\\.(jpg|bmp)|panels\\.(idx|dat)|meta\\.bin|toc\\.idx)$")

        fun le32(b: ByteArray, at: Int): Long = (0..3).fold(0L) { acc, j -> acc or ((b[at + j].toLong() and 0xff) shl (8 * j)) }

        /** Title from a converter-written meta.bin: u32 version, u16 title length, u16 author length, title. */
        fun metaTitle(b: ByteArray?): String? {
            if (b == null || b.size < 8) return null
            val len = (b[4].toInt() and 0xff) or ((b[5].toInt() and 0xff) shl 8)
            return if (b.size < 8 + len) null else String(b, 8, len, Charsets.UTF_8)
        }
    }
}

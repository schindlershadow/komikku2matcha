package com.schindler.k2m

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** A manga in a Komikku library backup: its title and the cover URL Komikku shows. */
data class BackupManga(val title: String, val coverUrl: String)

/**
 * Reads Komikku's (Mihon's) backups for cover URLs. Komikku doesn't save covers next to its downloads, but
 * its automatic backups (<Komikku folder>/autobackup/<date>.tachibk) list every library manga with its
 * thumbnail URL. A .tachibk is a gzip'd protobuf: Backup { repeated BackupManga backupManga = 1 },
 * BackupManga { string title = 3; string thumbnailUrl = 9 } (field numbers from Komikku's
 * data/backup/models). Only those two fields are read; everything else is skipped by wire type.
 */
object KomikkuBackup {
    fun parse(gz: ByteArray): List<BackupManga> {
        val bytes = GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
        val out = mutableListOf<BackupManga>()
        forEachField(bytes, 0, bytes.size) { field, start, end ->
            if (field == 1 && start >= 0) {
                var title = ""
                var cover = ""
                forEachField(bytes, start, end) { f, s, e ->
                    if (s >= 0 && (f == 3 || f == 9)) {
                        val v = String(bytes, s, e - s, Charsets.UTF_8)
                        if (f == 3) title = v else cover = v
                    }
                }
                if (title.isNotEmpty() && cover.startsWith("http")) out += BackupManga(title, cover)
            }
        }
        return out
    }

    /** Calls [onField] for each field in bytes[from, to): (number, start, end) of length-delimited
     *  values; start = -1 for other wire types (varint, fixed32/64), which are skipped. */
    private fun forEachField(b: ByteArray, from: Int, to: Int, onField: (Int, Int, Int) -> Unit) {
        var i = from
        fun varint(): Long {
            var shift = 0; var result = 0L
            while (true) {
                if (i >= to) throw IllegalArgumentException("truncated varint")
                val x = b[i++].toInt() and 0xff
                result = result or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalArgumentException("bad varint")
            }
        }
        while (i < to) {
            val key = varint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> { varint(); onField(field, -1, -1) }
                1 -> { i += 8; onField(field, -1, -1) }
                2 -> {
                    val len = varint().toInt()
                    if (len < 0 || i + len > to) throw IllegalArgumentException("bad length")
                    onField(field, i, i + len); i += len
                }
                5 -> { i += 4; onField(field, -1, -1) }
                else -> throw IllegalArgumentException("unsupported wire type ${key and 7}")
            }
        }
    }

    /**
     * The newest .tachibk in the granted Komikku folder: its autobackup/ folder, or the folder itself.
     * Returns (uri, last-modified) so the caller can skip a backup it already read.
     */
    suspend fun latest(ctx: Context, tree: Uri): Pair<Uri, Long>? = withContext(Dispatchers.IO) {
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED)
        var best: Pair<Uri, Long>? = null
        fun look(docId: String, depth: Int) {
            ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == Document.MIME_TYPE_DIR) {
                        if (depth == 0 && name.equals("autobackup", ignoreCase = true)) look(c.getString(0), 1)
                    } else if (name.endsWith(".tachibk", ignoreCase = true)) {
                        val modified = c.getLong(3)
                        if (best == null || modified > best!!.second)
                            best = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)) to modified
                    }
                }
            }
        }
        look(DocumentsContract.getTreeDocumentId(tree), 0)
        best
    }
}

/** A chapter in a Komikku backup, located inside its manga message. */
data class BackupChapterRef(val url: String, val name: String, val scanlator: String, val read: Boolean) {
    /** The suffix Komikku adds to a download's file name: "_" + md5(url).take(6). */
    val urlHash: String get() = java.security.MessageDigest.getInstance("MD5").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(6)
}

/** A manga in a Komikku backup: its title, chapters, and where its message sits in the raw backup. */
data class BackupMangaRef(val title: String, val chapters: List<BackupChapterRef>, val start: Int, val end: Int)

/**
 * A whole Komikku backup, kept raw so a "mark as read" backup can copy everything verbatim except the read flags.
 * Backup: 1 = manga (repeated), 2 = categories, 101 = sources. BackupManga: 3 = title, 16 = chapters.
 * BackupChapter: 1 = url, 2 = name, 3 = scanlator, 4 = read (field numbers from Komikku's backup models).
 */
class KomikkuBackupFile(private val raw: ByteArray) {
    val mangas = mutableListOf<BackupMangaRef>()
    private val keep = mutableListOf<Pair<Int, IntRange>>()   // (field, byte range incl. key) of categories/sources

    init {
        Proto.fields(raw, 0, raw.size) { f, keyStart, start, end ->
            when (f) {
                1 -> if (start >= 0) mangas += parseManga(start, end)
                2, 101 -> keep += f to (keyStart until end)
            }
        }
    }

    private fun parseManga(from: Int, to: Int): BackupMangaRef {
        var title = ""
        val chapters = mutableListOf<BackupChapterRef>()
        Proto.fields(raw, from, to) { f, _, s, e ->
            if (f == 3 && s >= 0) title = String(raw, s, e - s, Charsets.UTF_8)
            if (f == 16 && s >= 0) {
                var url = ""; var name = ""; var scan = ""; var read = false
                Proto.fields(raw, s, e) { cf, _, cs, ce ->
                    when (cf) {
                        1 -> url = String(raw, cs, ce - cs, Charsets.UTF_8)
                        2 -> name = String(raw, cs, ce - cs, Charsets.UTF_8)
                        3 -> scan = String(raw, cs, ce - cs, Charsets.UTF_8)
                        4 -> read = Proto.lastVarint != 0L
                    }
                }
                chapters += BackupChapterRef(url, name, scan, read)
            }
        }
        return BackupMangaRef(title, chapters, from, to)
    }

    /**
     * A gzip'd backup holding only the manga in [markRead] (title → chapter URLs to mark read), copied verbatim
     * but with those chapters' read flag set, plus the original categories and sources. Restoring it in Komikku
     * only ever adds "read": its restore keeps a chapter read if either side says so.
     */
    fun buildMarkRead(markRead: Map<BackupMangaRef, Set<String>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((m, urls) in markRead) {
            val manga = java.io.ByteArrayOutputStream()
            Proto.fields(raw, m.start, m.end) { f, ks, s, e ->
                if (f == 16 && s >= 0) {
                    var url = ""
                    Proto.fields(raw, s, e) { cf, _, cs, ce -> if (cf == 1) url = String(raw, cs, ce - cs, Charsets.UTF_8) }
                    if (url in urls) {
                        val ch = java.io.ByteArrayOutputStream()
                        Proto.fields(raw, s, e) { cf, cks, _, ce -> if (cf != 4) ch.write(raw, cks, ce - cks) }
                        ch.write(Proto.key(4, 0)); ch.write(Proto.varint(1))           // read = true
                        manga.write(Proto.lengthDelimited(16, ch.toByteArray()))
                        return@fields
                    }
                }
                manga.write(raw, ks, e - ks)
            }
            out.write(Proto.lengthDelimited(1, manga.toByteArray()))
        }
        keep.forEach { (_, r) -> out.write(raw, r.first, r.last - r.first + 1) }
        return java.io.ByteArrayOutputStream().also { b -> java.util.zip.GZIPOutputStream(b).use { it.write(out.toByteArray()) } }.toByteArray()
    }

    companion object {
        fun read(gz: ByteArray) = KomikkuBackupFile(java.util.zip.GZIPInputStream(gz.inputStream()).use { it.readBytes() })
    }
}

/** Minimal protobuf wire-format helpers. */
object Proto {
    /** The last varint value read by [fields] (valid inside the callback for a varint field). */
    var lastVarint = 0L

    /** For each field in b[from, to): (number, key start, value start or -1, value end). For length-delimited
     *  fields the value range is the payload; for others value start is -1 and end is the end of the field. */
    fun fields(b: ByteArray, from: Int, to: Int, on: (Int, Int, Int, Int) -> Unit) {
        var i = from
        fun varint(): Long {
            var shift = 0; var r = 0L
            while (true) {
                require(i < to) { "truncated varint" }
                val x = b[i++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7; require(shift <= 63) { "bad varint" }
            }
        }
        while (i < to) {
            val ks = i
            val key = varint()
            val f = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> { lastVarint = varint(); on(f, ks, -1, i) }
                1 -> { i += 8; on(f, ks, -1, i) }
                2 -> { val n = varint().toInt(); require(n >= 0 && i + n <= to) { "bad length" }; on(f, ks, i, i + n); i += n }
                5 -> { i += 4; on(f, ks, -1, i) }
                else -> throw IllegalArgumentException("unsupported wire type ${key and 7}")
            }
        }
    }

    fun varint(v: Long): ByteArray {
        val o = java.io.ByteArrayOutputStream(); var x = v
        while (true) { if (x and 0x7fL.inv() == 0L) { o.write(x.toInt()); return o.toByteArray() }; o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
    }
    fun key(field: Int, wire: Int) = varint((field.toLong() shl 3) or wire.toLong())
    fun lengthDelimited(field: Int, payload: ByteArray) = key(field, 2) + varint(payload.size.toLong()) + payload
}

/**
 * Finds the Komikku chapter a downloaded CBZ came from. Komikku names downloads
 * "<scanlator>_<chapter name>" (made filename-safe), plus "_" + md5(url).take(6) when "include chapter URL
 * hash" is on (DownloadProvider.getChapterDirName). The hash is exact; without it, names are compared
 * ignoring punctuation.
 */
fun matchBackupChapter(cbzName: String, chapters: List<BackupChapterRef>): BackupChapterRef? {
    val stem = cbzName.removeSuffix(".cbz").removeSuffix(".CBZ")
    Regex("_([0-9a-f]{6})$").find(stem)?.let { m -> chapters.firstOrNull { it.urlHash == m.groupValues[1] }?.let { return it } }
    val bare = DeviceLibrary.norm(stem.replace(Regex("_[0-9a-f]{6}$"), ""))
    return chapters.firstOrNull { DeviceLibrary.norm(if (it.scanlator.isNotBlank()) "${it.scanlator}_${it.name}" else it.name) == bare }
        ?: chapters.firstOrNull { DeviceLibrary.norm(it.name) == bare }
}

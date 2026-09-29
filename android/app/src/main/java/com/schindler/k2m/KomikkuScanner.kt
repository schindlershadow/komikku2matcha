package com.schindler.k2m

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A chapter CBZ in Komikku's download folder on the phone. [title] is its parent folder's name. */
/** [uri] is the SAF document URI as a string (parse with Uri.parse). */
data class PhoneChapter(val title: String, val file: String, val size: Long, val uri: String)

/**
 * Finds every .cbz under the folder the user granted (Komikku's downloads, laid out as
 * <source>/<title>/<chapter>.cbz). Queries DocumentsContract directly: DocumentFile does one
 * query per property per file, which is painfully slow over a big library.
 */
object KomikkuScanner {
    suspend fun scan(ctx: Context, tree: Uri): List<PhoneChapter> = withContext(Dispatchers.IO) {
        val out = mutableListOf<PhoneChapter>()
        fun walk(docId: String, name: String, depth: Int) {
            if (depth > 6) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val n = c.getString(1) ?: continue
                    if (n.startsWith(".")) continue
                    if (c.getString(2) == Document.MIME_TYPE_DIR) {
                        if (!n.endsWith("_tmp")) walk(id, n, depth + 1)  // Komikku's in-progress downloads
                    } else if (n.endsWith(".cbz", ignoreCase = true) && depth > 0) {
                        out += PhoneChapter(name, n, c.getLong(3), DocumentsContract.buildDocumentUriUsingTree(tree, id).toString())
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        out
    }
}

/** One chapter as the Library screen shows it: what the phone has, what the server has. */
data class ChapterRow(val title: String, val file: String, val phone: PhoneChapter?, val server: ServerChapter?, val serverDir: String?,
                      /** Deleted by the user and not to be synced again. */
                      val ignored: Boolean = false) {
    /** Computed once: lists read it on every frame. */
    val state: String = if (ignored) "ignored" else when {
        phone != null && server == null -> "new"
        phone != null && server != null && phone.size != server.size -> "changed"
        server?.status == "converted" -> "converted"
        else -> "pending"
    }
    /** How the server's --only/--exact names this chapter: "<title dir>/<file>". */
    val serverKey get() = "${serverDir ?: title}/$file"
    /** A prose novel, not manga, by its title/filename (raw-scanlation naming convention tags these with
     *  "小説"/"ノベル"). The converter treats every source as comic pages, so a novel comes out with no real
     *  panels to zoom into -- readable, but cramped on the device's screen. See detect_panels() in
     *  matcha-reader's convert_manga.py for the actual page-image-vs-panel mismatch this warns about. */
    val looksLikeNovel: Boolean get() = NOVEL_MARKERS.any { it in title || it in file }

    companion object {
        private val NOVEL_MARKERS = listOf("小説", "ノベル")
    }
}

/** [folder] is the server's title folder (covers are stored by it); null if the server doesn't have the title yet. */
data class TitleRow(val title: String, val chapters: List<ChapterRow>, val folder: String? = null, val hasCover: Boolean = false) {
    val counts: Map<String, Int> = chapters.groupingBy { it.state }.eachCount()
}

/** Pair phone chapters with server chapters by (title folder name, file name). */
fun mergeLibrary(phone: List<PhoneChapter>, server: List<ServerTitle>, ignored: Ignored = Ignored()): List<TitleRow> {
    val rows = linkedMapOf<Pair<String, String>, ChapterRow>()
    for (t in server) {
        val name = t.dir.substringAfterLast('/')
        for (c in t.chapters) rows[name to c.file] = ChapterRow(name, c.file, null, c, t.dir)
    }
    for (p in phone) {
        val k = p.title to p.file
        rows[k] = rows[k]?.copy(phone = p) ?: ChapterRow(p.title, p.file, p, null, null)
    }
    // Deleted-and-ignored: a whole series (by title dir name) or single chapters ("<dir>/<file>").
    val ignoredTitles = ignored.titles.map { it.substringAfterLast('/') }.toSet()
    val ignoredChapters = ignored.chapters.map { it.substringBeforeLast('/').substringAfterLast('/') + "/" + it.substringAfterLast('/') }.toSet()
    for ((k, row) in rows.toList()) {
        if (row.title in ignoredTitles || "${row.title}/${row.file}" in ignoredChapters) rows[k] = row.copy(ignored = true)
    }
    val serverByName = server.associateBy { it.dir.substringAfterLast('/') }
    return rows.values.groupBy { it.title }
        .map { (t, cs) -> TitleRow(t, cs.sortedWith(compareBy(NaturalOrder) { it.file }), serverByName[t]?.folder, serverByName[t]?.cover == true) }
        .sortedWith(compareBy(NaturalOrder) { it.title })
}

/** "Chapter 2" before "Chapter 10", like the converter and the device. */
object NaturalOrder : Comparator<String> {
    private val re = Regex("\\d+|\\D+")
    override fun compare(a: String, b: String): Int {
        val x = re.findAll(a.lowercase()).map { it.value }.toList()
        val y = re.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val (p, q) = x[i] to y[i]
            val c = if (p[0].isDigit() && q[0].isDigit())
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
            else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}

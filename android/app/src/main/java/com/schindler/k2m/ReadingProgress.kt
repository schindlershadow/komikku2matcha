package com.schindler.k2m

/**
 * Reading progress as the X4 stores it. The firmware keeps a manga's position in
 * /.crosspoint/manga_<std::hash of the book folder path>/progress.bin: the current page as a
 * little-endian uint32 (the reader caps it at pageCount-1; "mark as read" writes pageCount).
 * It treats a manga as finished once that page is the last one (MangaReaderActivity: currentPage >=
 * pageCount - 1), and so does [read].
 */
data class ReadingProgress(val page: Long, val pages: Long) {
    val read get() = pages > 0 && page >= pages - 1
    val percent get() = if (pages <= 0) 0 else minOf(100, ((page + 1) * 100 / pages).toInt())
}

/**
 * std::hash<std::string> as the X4's firmware computes it: libstdc++'s _Hash_bytes for a 32-bit
 * size_t, i.e. MurmurHash2 with seed 0xc70f6907. Checked against g++ on a 32-bit target; the result
 * is printed unsigned (std::to_string / %zu).
 */
fun firmwareStringHash(s: String): Long {
    val data = s.toByteArray(Charsets.UTF_8)
    val m = 0x5bd1e995
    var len = data.size
    var h = 0xc70f6907.toInt() xor len
    var i = 0
    fun b(j: Int) = data[j].toInt() and 0xff
    while (len >= 4) {
        var k = b(i) or (b(i + 1) shl 8) or (b(i + 2) shl 16) or (b(i + 3) shl 24)
        k *= m; k = k xor (k ushr 24); k *= m
        h *= m; h = h xor k
        i += 4; len -= 4
    }
    if (len == 3) h = h xor (b(i + 2) shl 16)
    if (len >= 2) h = h xor (b(i + 1) shl 8)
    if (len >= 1) { h = h xor b(i); h *= m }
    h = h xor (h ushr 13); h *= m; h = h xor (h ushr 15)
    return h.toLong() and 0xffffffffL
}

/** Where the X4 keeps a manga book's progress; [bookPath] as the library opens it, e.g. "/manga/T/Ch 1". */
fun mangaProgressFile(bookPath: String) = "/.crosspoint/manga_${firmwareStringHash(bookPath)}/progress.bin"

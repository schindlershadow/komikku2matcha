package com.schindler.k2m

import java.io.ByteArrayOutputStream

/**
 * panels.idx/panels.dat/meta.bin, byte for byte compatible with matcha-reader's convert_manga.py
 * (struct formats there: IDX_HEADER/IDX_RECORD/PANEL_BOX/CROP_BOX/TEXT_BLOCK/LINE_HEADER/LINE_BOX,
 * FORMAT_VERSION=3, META_HEADER/META_LANGUAGE_TRAILER, META_FORMAT_VERSION=1). Phone-side conversion
 * never runs OCR, so every panel it writes has zero text blocks and an empty translation string --
 * a v3 reader (the device, or local_ocr.py's own read_book()) handles that exactly like any panel
 * whose OCR pass simply found nothing.
 *
 * All multi-byte fields are little-endian, matching Python's "<..." struct formats.
 */
object PanelBinary {
    private const val FORMAT_VERSION = 3
    private const val META_FORMAT_VERSION = 1

    private fun u16(n: Int) = n.coerceIn(0, 0xFFFF)

    private fun ByteArrayOutputStream.u8(n: Int) = write(n and 0xFF)
    private fun ByteArrayOutputStream.u16le(n: Int) {
        val v = u16(n)
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
    private fun ByteArrayOutputStream.u32le(n: Int) {
        write(n and 0xFF); write((n shr 8) and 0xFF); write((n shr 16) and 0xFF); write((n shr 24) and 0xFF)
    }

    /** One page's panel boxes ([x1,y1,x2,y2] each, page-pixel space) -> the bytes panels.dat stores
     *  for that page. No text, no translation, and the crop box always equals the panel box (no
     *  separate OCR-bubble growth to do without OCR). */
    fun encodePage(panels: List<IntArray>): ByteArray {
        val truncated = panels.take(255)
        val out = ByteArrayOutputStream()
        out.u8(truncated.size)   // panel count
        out.u8(0)                // pad
        for (p in truncated) {
            val x1 = maxOf(0, p[0]); val y1 = maxOf(0, p[1])
            val w = maxOf(0, p[2] - p[0]); val h = maxOf(0, p[3] - p[1])
            out.u16le(x1); out.u16le(y1); out.u16le(w); out.u16le(h)
            out.u8(0)    // text block count
            out.u8(0)    // pad
            out.u16le(0) // translation length (no translation bytes follow)
            // crop box == panel box
            out.u16le(x1); out.u16le(y1); out.u16le(w); out.u16le(h)
        }
        return out.toByteArray()
    }

    /** One page's (byte offset into panels.dat, chunk length, page width, page height). */
    data class PageRecord(val offset: Int, val length: Int, val width: Int, val height: Int)

    /** panels.idx covering [records] in page order (panels.dat is just the chunks concatenated). */
    fun buildIndex(records: List<PageRecord>): ByteArray {
        val out = ByteArrayOutputStream()
        out.u32le(FORMAT_VERSION)
        out.u32le(records.size)
        for (r in records) {
            out.u32le(r.offset); out.u32le(r.length)
            out.u16le(r.width); out.u16le(r.height)
        }
        return out.toByteArray()
    }

    /** meta.bin: title/author/language for the CrossPoint library, or null when there's nothing to say
     *  (matching write_meta's own early return -- an absent meta.bin just falls back to the filename). */
    fun buildMeta(title: String, author: String, language: String = ""): ByteArray? {
        if (title.isEmpty() && author.isEmpty() && language.isEmpty()) return null
        val titleBytes = title.toByteArray(Charsets.UTF_8).take(0xFFFF).toByteArray()
        val authorBytes = author.toByteArray(Charsets.UTF_8).take(0xFFFF).toByteArray()
        val languageBytes = language.toByteArray(Charsets.UTF_8).take(0xFFFF).toByteArray()
        val out = ByteArrayOutputStream()
        out.u32le(META_FORMAT_VERSION)
        out.u16le(titleBytes.size)
        out.u16le(authorBytes.size)
        out.write(titleBytes)
        out.write(authorBytes)
        if (languageBytes.isNotEmpty()) {
            out.u16le(languageBytes.size)
            out.write(languageBytes)
        }
        return out.toByteArray()
    }
}

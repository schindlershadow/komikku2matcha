package com.schindler.k2m

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Minimal on-device manga conversion: no panel-detection model, no OCR, no translation, no covers,
 * no delta sync -- just resize-to-device-size plus [GridPanelDetector]'s whitespace-gutter split, the
 * same fallback the server itself uses when its YOLO model can't load. This exists so a chapter can
 * still reach the X4 with *something* better than nothing when there's no server reachable at all;
 * it is not feature parity, and [AppViewModel] warns the user before using it (see Settings).
 *
 * Spools the CBZ to a temp file and reads pages one at a time, so a long book doesn't balloon
 * memory the way reading every page's bytes up front would. Panels are cropped from the
 * already-downscaled page (not the original full resolution the server crops from), trading a little
 * panel-zoom sharpness for not having to hold a possibly-huge source scan and a resized copy at once.
 */
object PhoneManga {
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp")
    private const val JPEG_QUALITY = 85

    /** Convert [cbz] onto [storage] at [dest] ("" for a storage already rooted at the book's own
     *  folder, e.g. [BookCache.localStorage] -- "/manga/<title>/<chapter>" for one rooted at a card's
     *  root, e.g. pushing straight to the X4). Reports (page, totalSoFarKnown) as it goes -- the CBZ's
     *  total page count isn't known until the last entry streams past, so [onPage]'s total grows a few
     *  times early on then holds steady. Returns every file written, as bare names relative to the
     *  book's own folder (not prefixed by [dest]), for the caller to record as a manifest. */
    suspend fun convert(ctx: Context, cbz: Uri, dest: String, title: String, author: String,
                        targetW: Int, targetH: Int, storage: X4Storage, onPage: (Int, Int) -> Unit): List<Pair<String, Long>> {
        return withContext(Dispatchers.IO) {
            storage.mkdirs(dest)
            var madePanelsDir = false
            val records = mutableListOf<PanelBinary.PageRecord>()
            val dat = ByteArrayOutputStream()
            val written = mutableListOf<Pair<String, Long>>()
            suspend fun put(name: String, data: ByteArray) {
                storage.write(if (dest.isEmpty()) "/$name" else "$dest/$name", data)
                written += name to data.size.toLong()
            }
            var page = 0
            val skipped = mutableListOf<String>()
            // ZipInputStream can't stream STORED entries that use a data descriptor (Komikku writes
            // those), so spool to a temp file and read via the central directory instead.
            val tmp = java.io.File.createTempFile("k2m", ".cbz", ctx.cacheDir)
            try {
                (ctx.contentResolver.openInputStream(cbz) ?: throw java.io.IOException("Could not open $cbz")).use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                java.util.zip.ZipFile(tmp).use { zip ->
                    val entries = zip.entries().asSequence().filter { !it.isDirectory }
                        .sortedWith { x, y -> naturalCompare(x.name, y.name) }
                    for (entry in entries) {
                        val name = entry.name.substringAfterLast('/')
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in IMAGE_EXTS) {
                            val raw = zip.getInputStream(entry).use { it.readBytes() }
                            val src = decode(raw, targetW, targetH)
                            if (src == null) skipped += name
                            else {
                                val (fw, fh) = DeviceFit.size(src.width, src.height, targetW, targetH)
                                val fitted = if (fw == src.width && fh == src.height) src
                                    else Bitmap.createScaledBitmap(src, fw, fh, true)
                                if (fitted !== src) src.recycle()

                                val panels = GridPanelDetector.detect(grayscale(fitted), fw, fh)
                                for ((pi, p) in panels.withIndex()) {
                                    if (DeviceFit.isFullPagePanel(p, fw, fh)) continue
                                    if (!madePanelsDir) { storage.mkdirs(if (dest.isEmpty()) "/panels" else "$dest/panels"); madePanelsDir = true }
                                    val crop = Bitmap.createBitmap(fitted, p[0], p[1], p[2] - p[0], p[3] - p[1])
                                    put("panels/p${page}_$pi.jpg", jpeg(crop))
                                    crop.recycle()
                                }
                                put("page_%04d.jpg".format(page), jpeg(fitted))
                                val chunk = PanelBinary.encodePage(panels)
                                records += PanelBinary.PageRecord(dat.size(), chunk.size, fw, fh)
                                dat.write(chunk)
                                fitted.recycle()
                                page++
                                onPage(page, page)
                            }
                        }
                    }
                }
            } finally { tmp.delete() }
            if (page == 0) throw java.io.IOException(
                if (skipped.isEmpty()) "No images found in the archive"
                else "None of ${skipped.size} images could be decoded (${skipped.take(3).joinToString()})")
            // panels.idx last: an interrupted conversion never leaves a folder that looks complete.
            put("panels.dat", dat.toByteArray())
            PanelBinary.buildMeta(title, author)?.let { put("meta.bin", it) }
            put("panels.idx", PanelBinary.buildIndex(records))
            written
        }
    }

    /** "2.jpg" before "10.jpg"; zip central-directory order is arbitrary. */
    internal fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()).takeIf { it != 0 } ?: x.length.compareTo(y.length)
                    else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size.compareTo(rb.size)
    }

    /** Decode subsampled so a huge scan never needs its full-resolution bitmap in memory. */
    private fun decode(raw: ByteArray, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2
        return try {
            BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) { null }
    }

    private fun grayscale(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }
        return out
    }

    private fun jpeg(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }
}

package com.schindler.k2m

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises the real Android Bitmap/zip code in [PhoneManga] -- a plain JVM unit test would silently
 *  no-op every BitmapFactory/Bitmap call (isReturnDefaultValues in build.gradle.kts), which would hide
 *  a real bug rather than catch it. Robolectric gives real (native-backed) Bitmap decode/compress. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneMangaTest {
    private fun jpegBytes(w: Int, h: Int, color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** A CBZ with [n] solid-color pages, written to a real temp file (read back via a file:// Uri,
     *  the same way a picked Komikku chapter's content:// Uri ultimately resolves to bytes). */
    private fun makeCbz(dir: File, n: Int): Uri {
        val f = File(dir, "test.cbz")
        ZipOutputStream(f.outputStream()).use { zip ->
            repeat(n) { i ->
                zip.putNextEntry(ZipEntry("%03d.jpg".format(i)))
                zip.write(jpegBytes(600, 900, if (i % 2 == 0) Color.WHITE else Color.BLACK))
                zip.closeEntry()
            }
        }
        println("makeCbz wrote ${f.length()} bytes to ${f.absolutePath}")
        return Uri.fromFile(f)
    }

    @Test fun convertProducesRealPages() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val tmp = File.createTempFile("cbz", "").also { it.delete(); it.mkdirs() }
        val cbz = makeCbz(tmp, 3)
        val destDir = File(tmp, "out")
        val storage = FileStorage(destDir)

        val progressCalls = mutableListOf<Pair<Int, Int>>()
        val files = PhoneManga.convert(ctx, cbz, "", "Test Title", "", 480, 800, storage) { p, t -> progressCalls += p to t }

        println("written files: " + files.joinToString { "${it.first}=${it.second}b" })
        println("progress calls: $progressCalls")

        assertTrue("expected some pages processed", progressCalls.isNotEmpty())
        assertTrue("expected files written", files.isNotEmpty())
        val pageFiles = files.filter { it.first.startsWith("page_") }
        assertEquals(3, pageFiles.size)
        for ((name, size) in pageFiles) {
            assertTrue("$name should be non-empty, was $size bytes", size > 0)
            val onDisk = File(destDir, name)
            assertTrue("$name should exist on disk", onDisk.isFile)
            assertEquals("$name recorded size should match actual file size", size, onDisk.length())
        }
        val idx = files.firstOrNull { it.first == "panels.idx" }
        assertTrue("panels.idx should be written and non-empty", idx != null && idx.second > 0)
    }

    @Test fun archiveWithoutImagesFailsInsteadOfWritingEmptyBook() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val tmp = File.createTempFile("cbz", "").also { it.delete(); it.mkdirs() }
        val f = File(tmp, "bad.cbz")
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ComicInfo.xml")); zip.write(ByteArray(100) { 7 }); zip.closeEntry()
        }
        val err = runCatching {
            runBlocking { PhoneManga.convert(ctx, Uri.fromFile(f), "", "T", "", 480, 800, FileStorage(File(tmp, "out"))) { _, _ -> } }
        }.exceptionOrNull()
        assertTrue("expected an IOException, got $err", err is java.io.IOException)
    }

    /** Zip with STORED entries that use a data descriptor (flag bit 3) -- what Komikku writes, and
     *  what ZipInputStream cannot read -- deliberately out of page order. */
    private fun storedWithDescriptorZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        fun le(v: Long, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xFF).toByte() }
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        for ((name, data) in entries) {
            val off = out.size().toLong()
            val crc = java.util.zip.CRC32().also { it.update(data) }.value
            val nm = name.toByteArray()
            out.write(le(0x04034b50, 4)); out.write(le(20, 2)); out.write(le(8, 2)); out.write(le(0, 2))
            out.write(le(0, 4)); out.write(le(0, 4)); out.write(le(0, 4)); out.write(le(0, 4))
            out.write(le(nm.size.toLong(), 2)); out.write(le(0, 2)); out.write(nm); out.write(data)
            out.write(le(0x08074b50, 4)); out.write(le(crc, 4)); out.write(le(data.size.toLong(), 4)); out.write(le(data.size.toLong(), 4))
            central.write(le(0x02014b50, 4)); central.write(le(20, 2)); central.write(le(20, 2)); central.write(le(8, 2)); central.write(le(0, 2))
            central.write(le(0, 4)); central.write(le(crc, 4)); central.write(le(data.size.toLong(), 4)); central.write(le(data.size.toLong(), 4))
            central.write(le(nm.size.toLong(), 2)); central.write(le(0, 2)); central.write(le(0, 2)); central.write(le(0, 2)); central.write(le(0, 2))
            central.write(le(0, 4)); central.write(le(off, 4)); central.write(nm)
        }
        val cdOff = out.size().toLong()
        out.write(central.toByteArray())
        out.write(le(0x06054b50, 4)); out.write(le(0, 2)); out.write(le(0, 2))
        out.write(le(entries.size.toLong(), 2)); out.write(le(entries.size.toLong(), 2))
        out.write(le(central.size().toLong(), 4)); out.write(le(cdOff, 4)); out.write(le(0, 2))
        return out.toByteArray()
    }

    @Test fun storedDataDescriptorArchiveConvertsAllPagesInOrder() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val tmp = File.createTempFile("cbz", "").also { it.delete(); it.mkdirs() }
        val f = File(tmp, "komikku.cbz")
        f.writeBytes(storedWithDescriptorZip(listOf(
            "010.jpg" to jpegBytes(600, 900, Color.WHITE),
            "002.jpg" to jpegBytes(600, 900, Color.BLACK),
            "001.jpg" to jpegBytes(600, 900, Color.WHITE))))
        val files = PhoneManga.convert(ctx, Uri.fromFile(f), "", "T", "", 480, 800, FileStorage(File(tmp, "out"))) { _, _ -> }
        assertEquals(3, files.count { it.first.startsWith("page_") })
    }

    @Test fun naturalOrderSortsNumbersNumerically() {
        val sorted = listOf("10.jpg", "2.jpg", "1.jpg").sortedWith { a, b -> PhoneManga.naturalCompare(a, b) }
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), sorted)
    }
}

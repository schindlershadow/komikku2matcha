package com.schindler.k2m

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** [PhoneManga] on a real Android runtime (real BitmapFactory/ContentResolver), unlike the Robolectric
 *  test whose shadow decoder accepts garbage bytes. */
@RunWith(AndroidJUnit4::class)
class PhoneMangaDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tmp() = File(ctx.cacheDir, "k2mtest${System.nanoTime()}").also { it.mkdirs() }

    private fun image(format: Bitmap.CompressFormat, color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return ByteArrayOutputStream().also { bmp.compress(format, 90, it) }.toByteArray()
    }

    private fun cbz(dir: File, entries: List<Pair<String, ByteArray>>): Uri {
        val f = File(dir, "t.cbz")
        ZipOutputStream(f.outputStream()).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return Uri.fromFile(f)
    }

    private fun convert(dir: File, uri: Uri) = runBlocking {
        PhoneManga.convert(ctx, uri, "", "T", "", 480, 800, FileStorage(File(dir, "out"))) { _, _ -> }
    }

    @Test fun mixedFormatPagesConvert() {
        val d = tmp()
        val files = convert(d, cbz(d, listOf(
            "001.jpg" to image(Bitmap.CompressFormat.JPEG, Color.WHITE),
            "002.png" to image(Bitmap.CompressFormat.PNG, Color.BLACK),
            "003.webp" to image(Bitmap.CompressFormat.WEBP_LOSSY, Color.GRAY),
            "ComicInfo.xml" to "<x/>".toByteArray())))
        assertEquals(3, files.count { it.first.startsWith("page_") })
        assertTrue(files.all { it.second > 0 && File(d, "out/${it.first}").length() == it.second })
        assertTrue(files.any { it.first == "panels.idx" })
    }

    @Test fun undecodableImagesFail() {
        val d = tmp()
        val err = runCatching { convert(d, cbz(d, listOf("001.jpg" to ByteArray(100) { 7 }))) }.exceptionOrNull()
        assertTrue("got $err", err is java.io.IOException)
    }

    @Test fun missingArchiveFails() {
        val d = tmp()
        val err = runCatching { convert(d, Uri.fromFile(File(d, "nope.cbz"))) }.exceptionOrNull()
        assertTrue("got $err", err != null)
    }

    /** Optional: push a real Komikku CBZ to /data/local/tmp/k2m_real.cbz to run it through the device. */
    @Test fun realArchiveIfPresent() {
        val real = File("/data/local/tmp/k2m_real.cbz")
        org.junit.Assume.assumeTrue(real.canRead())
        val d = tmp()
        val files = convert(d, Uri.fromFile(real))
        assertTrue("pages: ${files.count { it.first.startsWith("page_") }}", files.count { it.first.startsWith("page_") } > 1)
    }
}

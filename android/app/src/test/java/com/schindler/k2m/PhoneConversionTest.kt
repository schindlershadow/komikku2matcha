package com.schindler.k2m

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic for phone-side conversion: no android.graphics.Bitmap needed, so these run on the plain JVM. */
class GridPanelDetectorTest {
    /** A page split into a 2x2 grid by white gutters, everything else black. */
    private fun gridPage(width: Int, height: Int, gutterX: Int, gutterY: Int, gutterThickness: Int): ByteArray {
        val gray = ByteArray(width * height) { 20 } // "ink"
        for (y in 0 until height) for (x in 0 until width) {
            val inGutterX = x in gutterX until gutterX + gutterThickness
            val inGutterY = y in gutterY until gutterY + gutterThickness
            if (inGutterX || inGutterY) gray[y * width + x] = 250.toByte() // "white paper"
        }
        return gray
    }

    @Test fun findsFourPanelGrid() {
        val w = 400; val h = 400
        val gray = gridPage(w, h, gutterX = 198, gutterY = 198, gutterThickness = 12)
        val panels = GridPanelDetector.detect(gray, w, h)
        assertEquals(4, panels.size)
        // Every panel should be a genuine quadrant-ish box, not the whole page.
        for (p in panels) assertTrue(p[2] - p[0] < w && p[3] - p[1] < h)
    }

    @Test fun fallsBackToWholePageWithNoGutters() {
        val w = 300; val h = 300
        val gray = ByteArray(w * h) { 20 } // solid "ink", no white anywhere
        val panels = GridPanelDetector.detect(gray, w, h)
        assertEquals(1, panels.size)
        assertEquals(listOf(0, 0, w, h), panels[0].toList())
    }

    @Test fun degenerateSizeDoesNotCrash() {
        assertEquals(1, GridPanelDetector.detect(ByteArray(0), 0, 0).size)
    }
}

class DeviceFitTest {
    @Test fun downscalesPortraitToFit() {
        val (w, h) = DeviceFit.size(2000, 3000, 480, 800)
        assertTrue(w <= 480 && h <= 800)
        assertEquals(480, w) // width is the binding constraint here
    }

    @Test fun neverUpscales() {
        assertEquals(100 to 150, DeviceFit.size(100, 150, 480, 800))
    }

    @Test fun landscapeFitsAgainstSwappedBox() {
        // A 3000x2000 landscape page against an 480x800 portrait screen fits against 800x480.
        val (w, h) = DeviceFit.size(3000, 2000, 480, 800)
        assertTrue(w <= 800 && h <= 480)
    }

    @Test fun fullPagePanelDetection() {
        assertTrue(DeviceFit.isFullPagePanel(intArrayOf(0, 0, 480, 800), 480, 800))
        assertTrue(DeviceFit.isFullPagePanel(intArrayOf(2, 3, 479, 799), 480, 800)) // near-full within threshold
        assertTrue(!DeviceFit.isFullPagePanel(intArrayOf(0, 0, 240, 400), 480, 800))
    }
}

class PanelBinaryTest {
    /** Mirrors convert_manga.py's struct reads (see local_ocr.py's read_book()) closely enough to
     *  catch a field-order or endianness mistake without needing the Python side to test against. */
    private class LeReader(private val b: ByteArray) {
        var pos = 0
        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun u16(): Int { val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8); pos += 2; return v }
        fun u32(): Int {
            val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
                ((b[pos + 2].toInt() and 0xFF) shl 16) or ((b[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4; return v
        }
        fun bytes(n: Int): ByteArray { val out = b.copyOfRange(pos, pos + n); pos += n; return out }
    }

    @Test fun encodePageRoundTrips() {
        val panels = listOf(intArrayOf(0, 0, 100, 50), intArrayOf(100, 0, 200, 50))
        val data = PanelBinary.encodePage(panels)
        val r = LeReader(data)
        assertEquals(2, r.u8())   // panel count
        assertEquals(0, r.u8())  // pad
        for (p in panels) {
            assertEquals(p[0], r.u16()); assertEquals(p[1], r.u16())
            assertEquals(p[2] - p[0], r.u16()); assertEquals(p[3] - p[1], r.u16())
            assertEquals(0, r.u8())   // text block count
            assertEquals(0, r.u8())  // pad
            assertEquals(0, r.u16()) // translation length
            // crop box, identical to the panel box
            assertEquals(p[0], r.u16()); assertEquals(p[1], r.u16())
            assertEquals(p[2] - p[0], r.u16()); assertEquals(p[3] - p[1], r.u16())
        }
        assertEquals(data.size, r.pos)
    }

    @Test fun buildIndexRoundTrips() {
        val records = listOf(PanelBinary.PageRecord(0, 40, 480, 800), PanelBinary.PageRecord(40, 55, 480, 800))
        val idx = PanelBinary.buildIndex(records)
        val r = LeReader(idx)
        assertEquals(3, r.u32())            // version
        assertEquals(2, r.u32())            // page count
        for (rec in records) {
            assertEquals(rec.offset, r.u32()); assertEquals(rec.length, r.u32())
            assertEquals(rec.width, r.u16()); assertEquals(rec.height, r.u16())
        }
        assertEquals(idx.size, r.pos)
    }

    @Test fun metaRoundTrips() {
        val data = PanelBinary.buildMeta("Title", "Author", "ja")!!
        val r = LeReader(data)
        assertEquals(1, r.u32())
        val titleLen = r.u16(); val authorLen = r.u16()
        assertEquals("Title", String(r.bytes(titleLen), Charsets.UTF_8))
        assertEquals("Author", String(r.bytes(authorLen), Charsets.UTF_8))
        val langLen = r.u16()
        assertEquals("ja", String(r.bytes(langLen), Charsets.UTF_8))
        assertEquals(data.size, r.pos)
    }

    @Test fun metaOmittedWhenEmpty() {
        assertNull(PanelBinary.buildMeta("", "", ""))
    }

    @Test fun metaWithoutLanguageHasNoTrailer() {
        val data = PanelBinary.buildMeta("T", "A")!!
        val r = LeReader(data)
        r.u32(); val titleLen = r.u16(); val authorLen = r.u16()
        r.bytes(titleLen); r.bytes(authorLen)
        assertEquals(data.size, r.pos) // nothing left to read: no language trailer was written
    }
}

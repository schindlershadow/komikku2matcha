package com.schindler.k2m

/**
 * Ports matcha-reader's convert_manga.py `_detect_panels_grid` / `_merge_small_gaps`: splits a page
 * into panels by finding solid white gutter bands (rows), then columns within each band, with no ML
 * model at all -- this is the fallback the server itself uses when its YOLO panel model can't load.
 * The phone-side conversion path uses this unconditionally (no model to carry on a phone), so a
 * source with real panel borders or clean paragraph gutters gets a real split; busy full-bleed art
 * or borderless pages degrade to one panel covering the page, same as the server's own fallback.
 *
 * Works on plain luma samples (0-255), not android.graphics.Bitmap, so the algorithm itself is
 * testable on the plain JVM without an Android device/emulator.
 */
object GridPanelDetector {
    private const val THRESHOLD = 215
    private const val PURITY = 0.95

    /** A panel box as [x1, y1, x2, y2]. */
    fun detect(gray: ByteArray, width: Int, height: Int): List<IntArray> {
        if (width <= 0 || height <= 0) return listOf(intArrayOf(0, 0, maxOf(0, width), maxOf(0, height)))
        fun px(x: Int, y: Int): Int = gray[y * width + x].toInt() and 0xFF

        val minGutter = maxOf(6, (height * 0.013).toInt())
        val minBandH = maxOf((height * 0.05).toInt(), 60)
        val minBandW = maxOf((width * 0.06).toInt(), 60)

        fun isWhiteRow(y: Int): Boolean {
            var white = 0
            var x = 0
            while (x < width) { if (px(x, y) > THRESHOLD) white++; x += 2 }
            return white > (width / 2) * PURITY
        }

        val hSplits = mutableListOf(0)
        var inGutter = false
        var gutterStart = 0
        for (y in 0 until height) {
            val whiteRow = isWhiteRow(y)
            if (whiteRow && !inGutter) {
                inGutter = true; gutterStart = y
            } else if (!whiteRow && inGutter) {
                if (y - gutterStart >= minGutter) hSplits += (gutterStart + y) / 2
                inGutter = false
            }
        }
        hSplits += height
        val hBands = mergeSmallGaps(hSplits, minBandH)

        val panels = mutableListOf<IntArray>()
        for (bandIdx in 0 until hBands.size - 1) {
            val y1 = hBands[bandIdx]
            val y2 = hBands[bandIdx + 1]

            fun isWhiteCol(x: Int): Boolean {
                var white = 0
                var y = y1
                while (y < y2) { if (px(x, y) > THRESHOLD) white++; y += 2 }
                return white > ((y2 - y1) / 2) * PURITY
            }

            val vSplits = mutableListOf(0)
            var inG = false
            var gStart = 0
            for (x in 0 until width) {
                val whiteCol = isWhiteCol(x)
                if (whiteCol && !inG) {
                    inG = true; gStart = x
                } else if (!whiteCol && inG) {
                    if (x - gStart >= minGutter) vSplits += (gStart + x) / 2
                    inG = false
                }
            }
            vSplits += width
            val vCols = mergeSmallGaps(vSplits, minBandW)
            for (colIdx in 0 until vCols.size - 1) panels += intArrayOf(vCols[colIdx], y1, vCols[colIdx + 1], y2)
        }
        return panels.ifEmpty { listOf(intArrayOf(0, 0, width, height)) }
    }

    /** Collapse boundary points that would create a too-small segment; the final edge is always kept. */
    private fun mergeSmallGaps(splits: List<Int>, minSize: Int): List<Int> {
        if (splits.size <= 2) return splits
        val merged = mutableListOf(splits[0])
        for (s in splits.drop(1)) {
            if (s - merged.last() < minSize) continue
            merged += s
        }
        if (merged.last() != splits.last()) merged[merged.lastIndex] = splits.last()
        return merged
    }
}

package com.schindler.k2m

/** Ports convert_manga.py's fit_to_device: downscale to fit the device screen box, never upscale,
 *  never change aspect. A landscape source is fitted against the swapped (rotated) target box, since
 *  the firmware rotates a page/panel whose aspect doesn't match the screen so it fills the display. */
object DeviceFit {
    fun size(w: Int, h: Int, targetW: Int, targetH: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return w to h
        var tw = targetW
        var th = targetH
        if (w > h) { val t = tw; tw = th; th = t }
        val scale = minOf(tw.toDouble() / w, th.toDouble() / h)
        if (scale >= 1.0) return w to h
        return maxOf(1, Math.round(w * scale).toInt()) to maxOf(1, Math.round(h * scale).toInt())
    }

    /** True when a panel covers almost the entire page (>=95% each dimension): a pre-cropped panel
     *  image would be essentially identical to the full page, so no crop file is worth writing. */
    fun isFullPagePanel(panel: IntArray, pageW: Int, pageH: Int, threshold: Double = 0.95): Boolean {
        val w = maxOf(1, panel[2] - panel[0])
        val h = maxOf(1, panel[3] - panel[1])
        return w.toDouble() / maxOf(1, pageW) >= threshold && h.toDouble() / maxOf(1, pageH) >= threshold
    }
}

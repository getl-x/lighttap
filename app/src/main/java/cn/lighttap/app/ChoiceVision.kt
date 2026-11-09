package cn.lighttap.app

/** Cropped pixels live only for the duration of a check; coordinates come from exact text nodes. */
data class ChoiceFrame(val left: Int, val top: Int, val width: Int, val height: Int, val pixels: IntArray, var classMask: ByteArray? = null) {
    fun contains(box: UiBox) = box.left >= left && box.top >= top && box.right <= left + width && box.bottom <= top + height
    fun pixel(x: Int, y: Int): Int = pixels[(y - top) * width + x - left]
    fun maskedPixel(x: Int, y: Int): Int {
        val mask = classMask ?: return pixel(x, y)
        return when (mask[(y-top)*width+x-left].toInt()) { 1 -> 0x1989fa; 2 -> 0xdddddd; 4 -> 0xffffff; 8 -> 0x1d1f24; else -> 0x00ff00 }
    }
    fun erase() { pixels.fill(0); classMask?.fill(0); classMask = null }
}
enum class ChoiceResult { NORMAL, ABNORMAL, UNKNOWN }
object ChoiceVision {
    private fun close(color: Int, red: Int, green: Int, blue: Int, tolerance: Int = 12): Boolean =
        kotlin.math.abs((color shr 16 and 255) - red) <= tolerance && kotlin.math.abs((color shr 8 and 255) - green) <= tolerance && kotlin.math.abs((color and 255) - blue) <= tolerance
    private fun blue(color: Int) = close(color, 25, 137, 250)
    private fun gray(color: Int) = close(color, 221, 221, 221, 8)
    private fun white(color: Int) = close(color, 255, 255, 255, 5)
    private fun dark(color: Int): Boolean {
        val channels = intArrayOf(color shr 16 and 255, color shr 8 and 255, color and 255)
        return channels.max() <= 105 && channels.max() - channels.min() <= 35
    }
    fun classify(frame: ChoiceFrame, normal: UiBox, abnormal: UiBox): ChoiceResult {
        if (!frame.contains(normal) || !frame.contains(abnormal)) return ChoiceResult.UNKNOWN
        val w = normal.right - normal.left; val h = normal.bottom - normal.top
        val aw = abnormal.right - abnormal.left; val ah = abnormal.bottom - abnormal.top
        if (w < 30 || h < 18 || aw < 30 || ah < 18) return ChoiceResult.UNKNOWN
        // Label-bound controls can grow to different sizes or wrap vertically
        // under font/display scaling. Require a clear row or column, not equal
        // pixel dimensions. The shared border is excluded from each score.
        val row = normal.left < abnormal.left && normal.right <= abnormal.left + minOf(w, aw)/10 &&
            minOf(normal.bottom, abnormal.bottom) - maxOf(normal.top, abnormal.top) >= minOf(h, ah)/2
        val column = normal.top < abnormal.top && normal.bottom <= abnormal.top + minOf(h, ah)/10 &&
            minOf(normal.right, abnormal.right) - maxOf(normal.left, abnormal.left) >= minOf(w, aw)/2
        if (!row && !column) return ChoiceResult.UNKNOWN
        fun edgeScore(box: UiBox, first: Boolean, color: (Int) -> Boolean): Double {
            val width = box.right-box.left; val height = box.bottom-box.top
            val band = (height / 10).coerceIn(2, 5)
            var total = 0; var matched = 0
            fun found(points: List<Pair<Int, Int>>) { total++; if (points.any { (x,y) -> x >= frame.left && x < frame.left+frame.width && y >= frame.top && y < frame.top+frame.height && color(frame.maskedPixel(x,y)) }) matched++ }
            for (x in box.left+width/5 until box.right-width/5) {
                if (row || first) found((-1..band).map { d -> x to box.top+d })
                if (row || !first) found((-band..1).map { d -> x to box.bottom-1+d })
            }
            for (y in box.top+height/5 until box.bottom-height/5) {
                if (column || first) found((-1..band).map { d -> box.left+d to y })
                if (column || !first) found((-band..1).map { d -> box.right-1+d to y })
            }
            return if (total == 0) 0.0 else matched.toDouble()/total
        }
        fun interior(box: UiBox, predicate: (Int) -> Boolean): Double {
            val margin = ((box.bottom-box.top)/10).coerceIn(3,6)
            var count=0; var matched=0
            for (y in box.top+margin until box.bottom-margin) for (x in box.left+margin until box.right-margin) { count++; if (predicate(frame.maskedPixel(x,y))) matched++ }
            return if (count==0) 0.0 else matched.toDouble()/count
        }
        if (interior(normal, ::white) < 0.65 || interior(abnormal, ::white) < 0.65) return ChoiceResult.UNKNOWN
        val nb = edgeScore(normal, true, ::blue); val ng = edgeScore(normal, true, ::gray)
        val ab = edgeScore(abnormal, false, ::blue); val ag = edgeScore(abnormal, false, ::gray)
        val normalChosen = nb >= 0.90 && ng < 0.15 && ag >= 0.90 && ab < 0.15 && interior(normal, ::blue) >= 0.008 && interior(abnormal, ::blue) <= 0.003 && interior(abnormal, ::dark) >= 0.008
        val abnormalChosen = ab >= 0.90 && ag < 0.15 && ng >= 0.90 && nb < 0.15 && interior(abnormal, ::blue) >= 0.008 && interior(normal, ::blue) <= 0.003 && interior(normal, ::dark) >= 0.008
        return when { normalChosen && !abnormalChosen -> ChoiceResult.NORMAL; abnormalChosen && !normalChosen -> ChoiceResult.ABNORMAL; else -> ChoiceResult.UNKNOWN }
    }
}

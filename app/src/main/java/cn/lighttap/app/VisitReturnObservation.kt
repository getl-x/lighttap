package cn.lighttap.app

enum class ReturnVisual { CLEAR, BLOCKED, UNKNOWN }
data class ReturnFrame(val state: ReturnVisual, val capturedAt: Long)
fun interface VisitReturnVision {
    suspend fun inspect(tree: UiTree): ReturnFrame
    suspend fun prepare() = Unit
}
data class ReturnConfirmation(val anchorMs: Long, val method: String)

/** One submission owns one observer. A hint can speed confirmation, but never
 * overrides a loading/unknown frame or the record-removal requirement. */
class VisitReturnObservation(private val clickedAt: Long?) {
    private var firstNormal: Long? = null
    private var signature: String? = null
    private var previousObservation = clickedAt
    private var fullyObserved = clickedAt != null
    private var sawLoading = false

    fun observe(at: Long, normal: Boolean, loading: Boolean, listSignature: String?, successHint: Boolean): ReturnConfirmation? {
        previousObservation?.let {
            if (at - it > 1_500 || at < it) fullyObserved = false
            // A real capture may take ~1.5s on this phone. Allow consecutive
            // slow reads, but discard a candidate after a pause/missing sample.
            if (at - it > 3_000 || at < it) {
                firstNormal = null; signature = null
            }
        }
        previousObservation = at
        if (loading) sawLoading = true
        if (!normal) {
            firstNormal = null; signature = null
            // Missing/partial pages cannot prove an uninterrupted normal return.
            if (!loading) fullyObserved = false
            return null
        }
        if (firstNormal == null || signature != listSignature) {
            firstNormal = at; signature = listSignature
            if (successHint) return confirmation(true)
            return null
        }
        if (successHint || at - firstNormal!! >= 900) return confirmation(successHint)
        return null
    }
    private fun confirmation(successHint: Boolean): ReturnConfirmation {
        val useClick = successHint && clickedAt != null && fullyObserved && !sawLoading
        return ReturnConfirmation(if (useClick) clickedAt!! else firstNormal!!,
            if (useClick) "成功提示与正常列表" else "正常列表确认")
    }
}

/** Conservative, target-page-specific white-surface check. Each horizontal band
 * must be predominantly bright: a white dialog on a dimmed list cannot pass.
 * Text/blue buttons and a small dark success toast are allowed. */
fun classifyReturnPixels(pixels: IntArray, width: Int, height: Int): ReturnVisual {
    if (width < 12 || height < 12 || pixels.size != width * height) return ReturnVisual.UNKNOWN
    var dimBands = 0
    var unknownBands = 0
    for (band in 0..2) {
        var bright = 0; var dim = 0; var total = 0
        for (y in band * height / 3 until (band + 1) * height / 3) for (x in 0 until width) {
            val color = pixels[y * width + x]
            val r = (color ushr 16) and 255; val g = (color ushr 8) and 255; val b = color and 255
            val high = maxOf(r, g, b); val low = minOf(r, g, b)
            if (low >= 238 && high - low <= 20) bright++
            if (high in 45..227 && high - low <= 25) dim++
            total++
        }
        if (bright.toDouble() / total < .65) {
            if (dim.toDouble() / total >= .55) dimBands++ else unknownBands++
        }
    }
    return when {
        dimBands > 0 -> ReturnVisual.BLOCKED
        unknownBands > 0 -> ReturnVisual.UNKNOWN
        else -> ReturnVisual.CLEAR
    }
}

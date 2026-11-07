package cn.lighttap.app

/** Keep only a timestamp, never business text. Old/foreign notification text
 * cannot authorize a submission; hints are scoped by target and dispatch time. */
class VisitSuccessSignals {
    @Volatile private var latestAt = -1L
    fun observe(packageName: String, target: String, texts: List<String>, at: Long) {
        if (packageName == target && texts.any { isVisitSuccess(it) }) latestAt = at
    }
    fun since(clickedAt: Long?, now: Long): Boolean = clickedAt != null && latestAt >= clickedAt && latestAt <= now && now - latestAt <= 120_000
}
fun isVisitSuccess(text: String) = canonicalLabel(text).trim('!', '！', '。') in setOf("新增走访成功", "走访成功", "新增走访成功提示")

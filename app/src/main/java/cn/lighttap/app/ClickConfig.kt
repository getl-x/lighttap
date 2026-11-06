package cn.lighttap.app

enum class ClickMode(val title: String) { SINGLE("单点"), SEQUENCE("顺序"), SIMULTANEOUS("同时") }
enum class StopMode(val title: String) { MANUAL("手动停止"), ROUNDS("指定轮数"), DURATION("运行时长") }
data class ScreenSpec(val width: Int, val height: Int, val densityDpi: Int, val rotation: Int)
const val MAX_ACTIONS = 100
enum class ActionType(val title: String) { TAP("点击"), DOUBLE_TAP("双击"), LONG_PRESS("长按"), SWIPE("滑动"), WAIT("等待") }
data class ClickPoint(
    val id: Long, val x: Float, val y: Float, val intervalMs: Long = 100,
    val type: ActionType = ActionType.TAP, val durationMs: Long = 50,
    val endX: Float = x, val endY: Float = y,
    val repeatCount: Int = 1, val enabled: Boolean = true, val label: String = "",
) {
    val gestureDuration: Long get() = when (type) { ActionType.LONG_PRESS -> durationMs.coerceAtLeast(500); ActionType.DOUBLE_TAP -> durationMs * 2 + 100; else -> durationMs }
}
data class ClickConfig(
    val mode: ClickMode = ClickMode.SINGLE,
    val points: List<ClickPoint> = emptyList(),
    val groupIntervalMs: Long = 100,
    val stopMode: StopMode = StopMode.MANUAL,
    val roundLimit: Long = 10,
    val durationMs: Long = 60_000,
    val screen: ScreenSpec? = null,
    val startDelayMs: Long = 0,
    val roundDelayMs: Long = 0,
    val keepScreenOn: Boolean = false,
    val volumeKeys: Boolean = false,
    val overlayOpacity: Int = 100,
    val markerSizeDp: Int = 44,
)
data class PanelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    fun intersects(point: ClickPoint): Boolean {
        if (point.type == ActionType.WAIT) return false
        if (contains(point.x, point.y)) return true
        if (point.type != ActionType.SWIPE) return false
        // Clip the swipe line against the rectangle, including its boundary.
        var low = 0f; var high = 1f
        val dx = point.endX - point.x; val dy = point.endY - point.y
        for ((p, q) in listOf(-dx to point.x - left, dx to right - point.x, -dy to point.y - top, dy to bottom - point.y)) {
            if (p == 0f) { if (q < 0) return false }
            else if (p < 0) low = maxOf(low, q / p) else high = minOf(high, q / p)
            if (low > high) return false
        }
        return true
    }
}
fun ClickConfig.activePoints(): List<ClickPoint> = points.filter { it.enabled }.let { if (mode == ClickMode.SINGLE) it.take(1) else it }
fun ClickConfig.structureError(): String? = when {
    points.size > MAX_ACTIONS -> "最多支持 $MAX_ACTIONS 个动作"
    points.map { it.id }.distinct().size != points.size -> "动作编号重复"
    points.any { !it.x.isFinite() || !it.y.isFinite() || !it.endX.isFinite() || !it.endY.isFinite() } -> "坐标无效"
    points.any { it.intervalMs !in 10..60_000 } || groupIntervalMs !in 10..60_000 -> "间隔须为 10～60,000 毫秒"
    points.any { it.durationMs !in 1..10_000 || it.repeatCount !in 1..10_000 || it.label.length > 40 } -> "动作时长须为 1～10,000 毫秒，重复次数须为 1～10,000"
    roundLimit !in 1..1_000_000 -> "轮数须为 1～1,000,000"
    durationMs !in 1_000..86_400_000 -> "运行时长须为 1 秒～24 小时"
    startDelayMs !in 0..86_400_000 || roundDelayMs !in 0..86_400_000 -> "启动和轮间等待须为 0～24 小时"
    overlayOpacity !in 35..100 || markerSizeDp !in 28..72 -> "悬浮外观参数无效"
    screen?.let { it.width <= 0 || it.height <= 0 || it.densityDpi <= 0 || it.rotation !in 0..3 } == true -> "屏幕参数无效"
    else -> null
}
fun ClickConfig.validationError(screenNow: ScreenSpec, maxStrokes: Int, panel: PanelBounds?): String? = when {
    structureError() != null -> structureError()
    activePoints().isEmpty() -> "请先添加并启用动作"
    screen != screenNow -> "屏幕参数已变化，请检查点位后点击「确认当前点位」"
    activePoints().any { p -> p.type != ActionType.WAIT && (p.x < 0 || p.y < 0 || p.x >= screenNow.width || p.y >= screenNow.height || (p.type == ActionType.SWIPE && (p.endX < 0 || p.endY < 0 || p.endX >= screenNow.width || p.endY >= screenNow.height))) } -> "存在越界点位，请拖动调整或删除"
    mode == ClickMode.SIMULTANEOUS && activePoints().size > maxStrokes -> "此设备同时模式最多支持 $maxStrokes 个点"
    mode == ClickMode.SIMULTANEOUS && activePoints().any { it.type !in listOf(ActionType.TAP, ActionType.LONG_PRESS) || it.repeatCount != 1 } -> "同时模式支持点击或长按，单步重复须设为 1；其他动作请使用顺序模式"
    activePoints().any { panel?.intersects(it) == true } -> "动作路径被控制条遮挡，请移动控制条"
    else -> null
}

package cn.lighttap.app

enum class ClickMode(val title: String) { SINGLE("单点"), SEQUENCE("顺序"), SIMULTANEOUS("同时") }
enum class StopMode(val title: String) { MANUAL("手动停止"), ROUNDS("指定轮数"), DURATION("运行时长") }
data class ScreenSpec(val width: Int, val height: Int, val densityDpi: Int, val rotation: Int)
data class ClickPoint(val id: Long, val x: Float, val y: Float, val intervalMs: Long = 100)
data class ClickConfig(
    val mode: ClickMode = ClickMode.SINGLE,
    val points: List<ClickPoint> = emptyList(),
    val groupIntervalMs: Long = 100,
    val stopMode: StopMode = StopMode.MANUAL,
    val roundLimit: Long = 10,
    val durationMs: Long = 60_000,
    val screen: ScreenSpec? = null,
)
data class PanelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}
fun ClickConfig.activePoints(): List<ClickPoint> = if (mode == ClickMode.SINGLE) points.take(1) else points
fun ClickConfig.validationError(screenNow: ScreenSpec, maxStrokes: Int, panel: PanelBounds?): String? = when {
    points.isEmpty() -> "请先添加点击点"
    points.size > 10 -> "最多支持 10 个点"
    points.map { it.id }.distinct().size != points.size -> "点位编号重复，请清空后重新添加"
    points.any { it.intervalMs !in 10..60_000 } || groupIntervalMs !in 10..60_000 -> "间隔须为 10～60,000 毫秒"
    roundLimit !in 1..1_000_000 -> "轮数须为 1～1,000,000"
    durationMs !in 1_000..86_400_000 -> "运行时长须为 1 秒～24 小时"
    screen != screenNow -> "屏幕参数已变化，请检查点位后点击「确认当前点位」"
    points.any { !it.x.isFinite() || !it.y.isFinite() || it.x < 0 || it.y < 0 || it.x >= screenNow.width || it.y >= screenNow.height } -> "存在越界点位，请拖动调整或删除"
    mode == ClickMode.SIMULTANEOUS && points.size > maxStrokes -> "此设备同时模式最多支持 $maxStrokes 个点"
    activePoints().any { panel?.contains(it.x, it.y) == true } -> "点击点被控制条遮挡，请移动控制条"
    else -> null
}
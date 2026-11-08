package cn.lighttap.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

@SuppressLint("SetTextI18n", "RtlHardcoded")
class PrivateOverlay(private val service: ClickAccessibilityService, private val controller: PrivateController) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    private var action: Button? = null
    private var probe: Button? = null
    private var renderedMode: VisitExecution? = null
    private fun dp(n: Int) = (n * service.resources.displayMetrics.density).roundToInt()
    fun render(state: VisitState, mode: VisitExecution) {
        if (renderedMode != mode) clear()
        if (view == null) build(mode)
        val wait = if (state.step == VisitStep.SUBMIT && state.remainingWaitMs > 0 && state.active) "距允许提交 ${countdown(state.remainingWaitMs)}\n" else ""
        status?.text = if (mode == VisitExecution.FORMAL) "${state.rounds} 轮 · ${state.step.title}\n$wait${state.message}"
            else "逐步测试 · ${state.rounds} 轮\n$wait${state.message}\n已执行：${state.lastStep}\n结果：${state.result}\n下一步：${state.step.title}\n预期：${state.step.expected}"
        status?.setTextColor(when (state.status) {
            VisitStatus.ERROR -> LightTapColors.OVERLAY_ERROR
            VisitStatus.WAITING, VisitStatus.PAUSED, VisitStatus.CHECKPOINT -> LightTapColors.OVERLAY_WARNING
            VisitStatus.RUNNING, VisitStatus.COMPLETED -> LightTapColors.OVERLAY_SUCCESS
            else -> Color.WHITE
        })
        action?.text = when {
            state.busy -> "暂停"
            state.status == VisitStatus.FINISH_CONFIRM -> "确认并结束测试"
            state.active && mode == VisitExecution.STEP_TEST -> state.nextLabel
            state.active -> "继续"
            else -> "开始"
        }
        probe?.isEnabled = !state.active
    }
    private fun build(mode: VisitExecution) {
        renderedMode = mode
        val compact = mode == VisitExecution.FORMAL
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { setColor(LightTapColors.OVERLAY); cornerRadius = dp(18).toFloat() }
            elevation = dp(10).toFloat()
        }
        val title = TextView(service).apply { text = if (compact) "轻点 · 正式运行 · 拖动移动" else "轻点 · 拖动此处移动"; textSize = 12f; setTextColor(Color.rgb(200, 192, 255)); setPadding(0, dp(6), 0, dp(if (compact) 4 else 10)) }
        layout.addView(title)
        status = TextView(service).apply { textSize = 12f; maxLines = if (compact) 4 else 12; setPadding(0, 0, 0, dp(6)); layout.addView(this) }
        fun row(vararg buttons: Button) {
            layout.addView(LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                buttons.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }) }
            })
        }
        action = button("开始", primary = true) { controller.toggle() }
        if (compact) row(action!!, button("停止", destructive = true) { controller.stop() })
        else {
            row(action!!)
            row(button("停止", destructive = true) { controller.stop() }, button("查看") { controller.openPanel() })
        }
        probe = button(if (compact) "检查" else "只读检查") { controller.probe() }
        if (compact) row(probe!!, button("查看") { controller.openPanel() }, button("隐藏") { controller.stopAndHide() })
        else row(probe!!, button("隐藏") { controller.stopAndHide() })
        val params = WindowManager.LayoutParams(dp(254), -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = dp(8); y = dp(if (compact) 40 else 120) }
        view = layout; lp = params
        draggable(title, layout, params)
        wm.addView(layout, params)
    }
    private fun button(label: String, primary: Boolean = false, destructive: Boolean = false, run: () -> Unit) = Button(service).apply {
        text = label; textSize = 12f; isAllCaps = false
        applyLightTapStyle(primary, destructive)
        setOnClickListener { run() }
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun draggable(handle: View, window: View, params: WindowManager.LayoutParams) {
        var x = 0f; var y = 0f; var initialX = 0; var initialY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x = event.rawX; y = event.rawY; initialX = params.x; initialY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    val screen = service.appModel.screen.value
                    params.x = (initialX + event.rawX - x).roundToInt().coerceIn(0, ((screen?.width ?: 720) - window.width).coerceAtLeast(0))
                    params.y = (initialY + event.rawY - y).roundToInt().coerceIn(dp(28), ((screen?.height ?: 1604) - window.height).coerceAtLeast(dp(28)))
                    wm.updateViewLayout(window, params); true
                }
                MotionEvent.ACTION_UP -> { handle.performClick(); true }
                else -> true
            }
        }
    }
    fun bounds(): UiBox? {
        val window = view ?: return null
        val position = IntArray(2); window.getLocationOnScreen(position)
        return UiBox(position[0], position[1], position[0] + window.width, position[1] + window.height)
    }
    fun clear() { view?.let { runCatching { wm.removeViewImmediate(it) } }; view = null; lp = null; status = null; action = null; probe = null; renderedMode = null }
}

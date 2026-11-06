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
    private fun dp(n: Int) = (n * service.resources.displayMetrics.density).roundToInt()
    fun render(state: VisitState, mode: VisitExecution) {
        if (view == null) build()
        val wait = if (state.step == VisitStep.GAP && state.active) "距下一轮 ${countdown(state.remainingWaitMs)}\n" else ""
        status?.text = "${if (mode == VisitExecution.STEP_TEST) "逐步测试" else "正式运行"} · ${state.rounds} 轮\n$wait${state.message}\n已执行：${state.lastStep}\n结果：${state.result}\n下一步：${state.step.title}\n预期：${state.step.expected}"
        status?.setTextColor(if (state.status == VisitStatus.ERROR) Color.rgb(255, 190, 170) else Color.WHITE)
        action?.text = when {
            state.busy -> "暂停"
            state.status == VisitStatus.FINISH_CONFIRM -> "确认并结束测试"
            state.active && mode == VisitExecution.STEP_TEST -> state.nextLabel
            state.active -> "继续（重新核验）"
            else -> "开始"
        }
        probe?.isEnabled = !state.active
    }
    private fun build() {
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { setColor(Color.rgb(28, 29, 43)); cornerRadius = dp(16).toFloat() }
            elevation = dp(10).toFloat()
        }
        val title = TextView(service).apply { text = "轻点 · 拖动此处移动"; textSize = 12f; setTextColor(Color.rgb(200, 192, 255)); setPadding(0, dp(6), 0, dp(10)) }
        layout.addView(title)
        status = TextView(service).apply { textSize = 11f; maxLines = 11; layout.addView(this) }
        action = button("开始") { controller.toggle() }.also { layout.addView(it, LinearLayout.LayoutParams(-1, dp(44))) }
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("停止") { controller.stop() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        row.addView(button("查看") { controller.openPanel() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        layout.addView(row)
        val bottom = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        probe = button("只读检查") { controller.probe() }.also { bottom.addView(it, LinearLayout.LayoutParams(0, dp(42), 1f)) }
        bottom.addView(button("隐藏") { controller.stopAndHide() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        layout.addView(bottom)
        val params = WindowManager.LayoutParams(dp(254), -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = dp(8); y = dp(120) }
        view = layout; lp = params
        draggable(title, layout, params)
        wm.addView(layout, params)
    }
    private fun button(label: String, run: () -> Unit) = Button(service).apply {
        text = label; textSize = 11f; isAllCaps = false; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
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
    fun clear() { view?.let { runCatching { wm.removeViewImmediate(it) } }; view = null; lp = null; status = null; action = null; probe = null }
}
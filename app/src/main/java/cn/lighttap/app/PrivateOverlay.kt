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
import android.widget.ScrollView
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@SuppressLint("SetTextI18n", "RtlHardcoded")
class PrivateOverlay(private val service: ClickAccessibilityService, private val controller: PrivateController) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    private var action: Button? = null
    private var probe: Button? = null
    private var renderedMode: VisitExecution? = null
    private var renderedCompact: Boolean? = null
    private var suppressed = false
    private var probing = false
    private var layoutChangedAt = 0L
    private fun dp(n: Int) = (n * service.resources.displayMetrics.density).roundToInt()
    fun render(state: VisitState, mode: VisitExecution) {
        val compact = mode == VisitExecution.FORMAL || state.busy || probing
        if (renderedMode != mode || renderedCompact != compact) clear()
        if (view == null) build(mode, compact)
        val wait = if (state.step == VisitStep.SUBMIT && state.remainingWaitMs > 0 && state.active) "距允许提交 ${countdown(state.remainingWaitMs)}\n" else ""
        status?.text = if (compact) "${controller.recognitionTitle} · ${state.rounds} 轮 · ${state.step.title}\n$wait${state.message}"
            else "${controller.recognitionTitle} · 逐步测试 · ${state.rounds} 轮\n$wait${state.message}\n已执行：${state.lastStep}\n结果：${state.result}\n下一步：${state.step.title}\n预期：${state.step.expected}"
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
    suspend fun prepare(state: VisitState, mode: VisitExecution) {
        render(state, mode)
        val remaining = 150 - (SystemClock.uptimeMillis() - layoutChangedAt)
        if (remaining > 0) delay(remaining)
    }
    fun setProbing(active: Boolean) { probing = active }
    fun suppress(active: Boolean) {
        suppressed = active
        val window = view ?: return
        val params = lp ?: return
        params.flags = if (active) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        window.visibility = if (active) View.INVISIBLE else View.VISIBLE
        wm.updateViewLayout(window, params)
    }
    private fun build(mode: VisitExecution, compact: Boolean) {
        renderedMode = mode
        renderedCompact = compact
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { setColor(LightTapColors.OVERLAY); cornerRadius = dp(18).toFloat() }
            elevation = dp(10).toFloat()
        }
        val title = TextView(service).apply { text = if (compact) "轻点 · ${if (mode == VisitExecution.FORMAL) "正式运行" else "逐步测试"} · 拖动移动" else "轻点 · 拖动此处移动"; textSize = 12f; maxLines = 2; setTextColor(Color.rgb(200, 192, 255)); setPadding(0, dp(6), 0, dp(if (compact) 4 else 10)) }
        layout.addView(title)
        status = TextView(service).apply { textSize = 12f; maxLines = if (compact) 3 else 12; setPadding(0, 0, 0, dp(6)); layout.addView(this) }
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
        if (!compact || mode == VisitExecution.FORMAL) {
            probe = button(if (compact) "检查" else "只读检查") { controller.probe() }
            if (compact) row(probe!!, button("查看") { controller.openPanel() }, button("隐藏") { controller.stopAndHide() })
            else row(probe!!, button("隐藏") { controller.stopAndHide() })
        }
        val screen = service.appModel.screen.value
        val width = minOf(dp(254), (screen?.width ?: service.resources.displayMetrics.widthPixels) - dp(16)).coerceAtLeast(1)
        val height = (screen?.height ?: service.resources.displayMetrics.heightPixels) - dp(80)
        val window = object : ScrollView(service) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height.coerceAtLeast(1), MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport = false; addView(layout); visibility = if (suppressed) View.INVISIBLE else View.VISIBLE }
        val params = WindowManager.LayoutParams(width, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (suppressed) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0), PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.LEFT; x = dp(8); y = dp(28) }
        view = window; lp = params
        draggable(title, window, params)
        wm.addView(window, params)
        layoutChangedAt = SystemClock.uptimeMillis()
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
        if (suppressed || window.visibility != View.VISIBLE) return null
        val position = IntArray(2); window.getLocationOnScreen(position)
        return UiBox(position[0], position[1], position[0] + window.width, position[1] + window.height)
    }
    fun clear() { view?.let { runCatching { wm.removeViewImmediate(it) } }; view = null; lp = null; status = null; action = null; probe = null; renderedMode = null; renderedCompact = null }
}

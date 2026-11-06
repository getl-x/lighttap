package cn.lighttap.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native overlay views keep accessibility windows independent of activity lifecycle. */
class OverlayController(private val service: ClickAccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var statusText: TextView? = null
    private var runButton: Button? = null
    private var addButton: Button? = null
    private var lastConfig: ClickConfig? = null
    private var editing: Boolean? = null
    private var collapsed = false
    private val markers = mutableListOf<View>()
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).roundToInt()
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    // Raw display coordinates deliberately keep a physical left origin in every locale.
    @SuppressLint("RtlHardcoded")
    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        android.graphics.PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    fun render(config: ClickConfig, state: RuntimeState, visible: Boolean, settingsVisible: Boolean = service.appModel.settingsVisible.value) {
        if (!visible) { clear(); return }
        if (panel == null) buildPanel()
        statusText?.text = service.getString(R.string.overlay_status, state.rounds, state.elapsedMs / 1000, state.message)
        runButton?.text = if (collapsed) when (state.status) { RunStatus.RUNNING -> "暂停"; RunStatus.PAUSED -> "继续"; else -> "开始" }
            else when (state.status) { RunStatus.RUNNING -> "Ⅱ 暂停"; RunStatus.PAUSED -> "▶ 继续"; else -> "▶ 开始" }
        addButton?.isEnabled = state.status != RunStatus.RUNNING && config.points.size < 10
        val canEdit = state.status != RunStatus.RUNNING && !settingsVisible
        if (lastConfig != config || editing != canEdit) {
            hideMarkers()
            if (canEdit) config.points.forEachIndexed { index, point -> addMarker(index, point) }
            lastConfig = config; editing = canEdit
        }
    }
    private fun buildPanel() {
        val oldX = panelParams?.x ?: dp(12)
        val oldY = panelParams?.y ?: dp(80)
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = shape(Color.rgb(28, 29, 43), 18)
            elevation = dp(12).toFloat()
        }
        val title = TextView(service).apply {
            text = if (collapsed) "轻点 ↕" else "● 轻点   按住此处移动"
            setTextColor(Color.rgb(193, 186, 255)); textSize = if (collapsed) 11f else 13f
            setPadding(dp(4), dp(8), dp(4), dp(8)); setTypeface(null, Typeface.BOLD)
        }
        layout.addView(title)
        statusText = if (!collapsed) TextView(service).apply {
            textSize = 11f; setTextColor(Color.WHITE); maxLines = 4; setPadding(dp(4), 0, dp(4), dp(4)); layout.addView(this)
        } else null
        fun row(vararg views: View) { layout.addView(LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; views.forEach { addView(it, LinearLayout.LayoutParams(0, dp(46), 1f)) } }) }
        runButton = button("开始") { service.toggleRun() }
        if (collapsed) {
            layout.addView(runButton, LinearLayout.LayoutParams(-1, dp(46)))
            layout.addView(button("停止") { service.stopRun() }, LinearLayout.LayoutParams(-1, dp(46)))
            layout.addView(button("展开") { toggleCollapsed() }, LinearLayout.LayoutParams(-1, dp(42)))
        } else {
            row(runButton!!, button("■ 停止") { service.stopRun() })
            addButton = button("＋ 点") { service.addPoint() }
            row(addButton!!, button("设置") { service.openSettings() })
            row(button("收起") { toggleCollapsed() }, button("隐藏") { service.hideOverlay() })
        }
        val lp = params(dp(if (collapsed) 76 else 220), WindowManager.LayoutParams.WRAP_CONTENT).apply { x = oldX; y = oldY }
        panel = layout; panelParams = lp
        draggable(title, layout, lp)
        wm.addView(layout, lp)
        layout.post { clampPanel() }
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun button(label: String, action: () -> Unit) = Button(service).apply {
        text = label; textSize = 12f; isAllCaps = false
        setTextColor(Color.WHITE); minWidth = 0; minimumWidth = 0
        setPadding(0, 0, 0, 0)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(67, 62, 116))
        var activatedOnDown = false
        setOnClickListener { if (activatedOnDown) activatedOnDown = false else action() }
        setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (view === runButton && service.appModel.runtime.value.status == RunStatus.RUNNING) {
                    activatedOnDown = true
                    service.pauseRun()
                } else if (label == "停止" || label == "■ 停止") {
                    activatedOnDown = true
                    action()
                }
            } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) activatedOnDown = false
            false
        }
    }
    private fun toggleCollapsed() {
        panel?.let { runCatching { wm.removeViewImmediate(it) } }
        panel = null; collapsed = !collapsed
        buildPanel()
        render(service.appModel.config.value, service.appModel.runtime.value, true)
    }
    fun resetPosition() {
        panelParams?.apply { x = dp(12); y = dp(80) }
        panel?.let { view -> panelParams?.let { wm.updateViewLayout(view, it) }; view.post { clampPanel() } }
        hideMarkers(); lastConfig = null; editing = null
    }
    private fun clampPanel() {
        val screen = service.appModel.screen.value ?: return
        val view = panel ?: return
        val lp = panelParams ?: return
        lp.x = lp.x.coerceIn(0, (screen.width - view.width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(dp(24), (screen.height - view.height).coerceAtLeast(dp(24)))
        runCatching { wm.updateViewLayout(view, lp) }
    }
    fun bounds(): PanelBounds? {
        val view = panel ?: return null
        val position = IntArray(2); view.getLocationOnScreen(position)
        val width = if (view.width > 0) view.width else panelParams?.width ?: dp(220)
        val height = if (view.height > 0) view.height else dp(260)
        return PanelBounds(position[0], position[1], position[0] + width, position[1] + height)
    }
    private fun addMarker(index: Int, point: ClickPoint) {
        val size = dp(44)
        val marker = TextView(service).apply {
            text = service.getString(R.string.marker_number, index + 1); textSize = 18f; gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            contentDescription = "点击点 ${index + 1}，长按删除，拖动调整"
            background = shape(Color.argb(225, 93, 84, 217), 22).apply { setStroke(dp(2), Color.WHITE) }
            elevation = dp(5).toFloat()
            setOnLongClickListener { service.appModel.edit { c -> c.copy(points = c.points.filterNot { it.id == point.id }) }; true }
        }
        val lp = params(size, size).apply { x = point.x.roundToInt() - size / 2; y = point.y.roundToInt() - size / 2 }
        draggable(marker, marker, lp) { service.movePoint(point.id, it.first, it.second) }
        markers += marker
        wm.addView(marker, lp)
        marker.post {
            if (!marker.isAttachedToWindow) return@post
            val location = IntArray(2); marker.getLocationOnScreen(location)
            val dx = point.x.roundToInt() - (location[0] + size / 2)
            val dy = point.y.roundToInt() - (location[1] + size / 2)
            if (dx != 0 || dy != 0) { lp.x += dx; lp.y += dy; runCatching { wm.updateViewLayout(marker, lp) } }
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun draggable(handle: View, view: View, lp: WindowManager.LayoutParams, moved: ((Pair<Float, Float>) -> Unit)? = null) {
        var startX = 0f; var startY = 0f; var oldX = 0; var oldY = 0; var dragged = false
        var longPress: Runnable? = null
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY; oldX = lp.x; oldY = lp.y; dragged = false
                    if (moved != null) { longPress = Runnable { if (!dragged) handle.performLongClick() }; handle.postDelayed(longPress!!, 650) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX; val dy = event.rawY - startY
                    if (abs(dx) + abs(dy) > dp(6)) { dragged = true; longPress?.let { handle.removeCallbacks(it) } }
                    if (dragged) {
                        lp.x = oldX + dx.roundToInt(); lp.y = oldY + dy.roundToInt()
                        runCatching { wm.updateViewLayout(view, lp) }
                        if (moved == null) clampPanel()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPress?.let { handle.removeCallbacks(it) }
                    if (dragged && moved != null && view.isAttachedToWindow) {
                        val location = IntArray(2); view.getLocationOnScreen(location)
                        moved((location[0] + view.width / 2f) to (location[1] + view.height / 2f))
                    } else if (!dragged) handle.performClick()
                    true
                }
                else -> false
            }
        }
    }
    fun hideMarkers() { markers.forEach { runCatching { wm.removeViewImmediate(it) } }; markers.clear(); editing = false }
    fun clear() {
        hideMarkers()
        panel?.let { runCatching { wm.removeViewImmediate(it) } }
        panel = null; statusText = null; runButton = null; addButton = null
        lastConfig = null; editing = null
    }
}
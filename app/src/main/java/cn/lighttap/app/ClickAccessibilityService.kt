package cn.lighttap.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class ClickAccessibilityService : AccessibilityService() {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: ClickEngine
    private lateinit var overlay: OverlayController
    private var connected = false
    private val model get() = appModel
    private val displayManager get() = getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) { if (displayId == 0) interrupt("显示屏已断开") }
        override fun onDisplayChanged(displayId: Int) { if (displayId == 0 && connected) updateScreen() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF || intent?.action == Intent.ACTION_SHUTDOWN) interrupt("屏幕已关闭，任务已停止")
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        if (connected) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        engine = ClickEngine(scope, AndroidTapDispatcher(this) { points ->
            val panel = overlay.bounds()
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                getSystemService(PowerManager::class.java).isInteractive &&
                points.none { panel?.contains(it.x, it.y) == true }
        })
        overlay = OverlayController(this)
        connected = true
        model.service.value = this
        updateScreen()
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SHUTDOWN) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        displayManager.registerDisplayListener(displayListener, null)
        scope.launch { engine.state.collect { model.runtime.value = it } }
        scope.launch { combine(model.config, engine.state, model.overlayVisible, model.settingsVisible) { config, runtime, visible, settings -> overlay.render(config, runtime, visible, settings) }.collect {} }
    }
    @Suppress("DEPRECATION")
    private fun readScreen(): ScreenSpec {
        val wm = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        val bounds = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds else null
        return ScreenSpec(bounds?.width() ?: metrics.widthPixels, bounds?.height() ?: metrics.heightPixels, resources.configuration.densityDpi, wm.defaultDisplay.rotation)
    }
    private fun updateScreen() {
        val screen = readScreen()
        val previous = model.screen.value
        if (previous != null && previous != screen) {
            interrupt("屏幕参数已变化，请检查并重新确认点位")
            overlay.resetPosition()
        }
        model.screen.value = screen
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); if (connected) updateScreen() }
    fun showOverlay() {
        if (!model.loaded.value) { notice("正在读取配置，请稍后"); return }
        model.overlayVisible.value = true
        overlay.render(model.config.value, engine.state.value, true)
    }
    fun hideOverlay() { engine.stop(); model.overlayVisible.value = false; overlay.clear() }
    fun toggleRun() {
        if (engine.state.value.status == RunStatus.RUNNING) { engine.pause(); return }
        if (model.settingsVisible.value) { notice("请先切换到目标应用或点击测试页，再通过控制条开始"); return }
        showOverlay()
        updateScreen()
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (locked || !getSystemService(PowerManager::class.java).isInteractive) { notice("请解锁并点亮屏幕后开始"); return }
        val error = model.config.value.validationError(model.screen.value!!, GestureDescription.getMaxStrokeCount(), overlay.bounds())
        if (error != null) { notice(error); return }
        overlay.hideMarkers()
        if (engine.state.value.status == RunStatus.PAUSED) engine.resume() else engine.start(model.config.value)
    }
    fun pauseRun() = engine.pause()
    fun stopRun() = engine.stop()
    fun planEdited(config: ClickConfig) { if (connected) engine.configure(config) }
    fun confirmPoints() {
        val current = model.screen.value ?: return
        val candidate = model.config.value.copy(screen = current)
        val error = candidate.validationError(current, GestureDescription.getMaxStrokeCount(), null)
        if (error != null) { notice(error); return }
        model.edit { candidate }
        notice("已确认当前屏幕上的点位")
    }
    fun addPoint() {
        if (model.runtime.value.status == RunStatus.RUNNING) { notice("请先暂停"); return }
        val current = model.screen.value ?: return
        if (model.config.value.points.size >= 10) { notice("最多支持 10 个点"); return }
        val existing = model.config.value.points
        val n = existing.size
        val panel = overlay.bounds()
        val candidates = (0..8).map { i -> (current.width * (0.35f + (i % 3) * 0.15f)) to (current.height * (0.4f + (i / 3) * 0.12f)) }
        val (x, y) = candidates.firstOrNull { (x, y) -> panel?.contains(x, y) != true && existing.none { kotlin.math.abs(it.x - x) < 20 && kotlin.math.abs(it.y - y) < 20 } } ?: (current.width * 0.5f to current.height * 0.8f)
        model.edit { it.copy(points = it.points + ClickPoint((it.points.maxOfOrNull { p -> p.id } ?: 0) + 1, x + if (n >= 9) 40 else 0, y), screen = it.screen ?: current) }
    }
    fun movePoint(id: Long, x: Float, y: Float) {
        val screen = model.screen.value ?: return
        model.edit { config -> config.copy(points = config.points.map { if (it.id == id) it.copy(x = x.coerceIn(0f, (screen.width - 1).toFloat()), y = y.coerceIn(0f, (screen.height - 1).toFloat())) else it }) }
    }
    fun openSettings() { engine.pause(); startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    fun notice(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun interrupt(message: String) { if (connected) engine.fail(message) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { interrupt("无障碍服务被中断，任务已停止") }
    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    private fun disconnect() {
        if (!connected) return
        engine.fail("点击服务已断开，请重新开启")
        model.runtime.value = engine.state.value
        connected = false
        model.service.value = null
        model.overlayVisible.value = false
        overlay.clear()
        runCatching { unregisterReceiver(receiver) }
        displayManager.unregisterDisplayListener(displayListener)
        scope.cancel()
    }
}
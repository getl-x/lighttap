package cn.lighttap.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDateTime

class PrivateModel(private val context: Context) {
    val session = PrivateSession()
    private val preferences = context.getSharedPreferences("local_options", Context.MODE_PRIVATE)
    internal val weekdayReminder = WeekdayRunReminder(
        { preferences.getString("last_weekday_reminder_${it.name}", null) },
        { slot, date -> preferences.edit().putString("last_weekday_reminder_${slot.name}", date).commit() },
    )
    private var waitMigrationFailed = false
    private fun restoredWait(): String {
        val saved = preferences.getString("wait", null)
        val updated = restoredSubmissionInterval(saved)
        // Existing values below the rule are raised; longer user values survive.
        if (saved != updated) waitMigrationFailed = !preferences.edit().putString("wait", updated).commit()
        return updated
    }
    val settings = MutableStateFlow(VisitSettings(
        targetPackage = TARGET,
        mode = runCatching { VisitRunMode.valueOf(preferences.getString("mode", "ONCE")!!) }.getOrDefault(VisitRunMode.ONCE),
        countText = preferences.getString("count", "1") ?: "1",
        waitText = restoredWait(),
    ))
    val state = MutableStateFlow(VisitState())
    val probeResult = MutableStateFlow("尚未进行只读检查")
    val verified = MutableStateFlow(preferences.getBoolean("test_confirmed", false))
    val storageError = MutableStateFlow<String?>(if (waitMigrationFailed) "等待设置保存失败，请重新保存后开始" else null)
    val saving = MutableStateFlow(false)
    val history = MutableStateFlow<List<VisitHistoryEntry>>(emptyList())
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsQueue = Channel<VisitSettings>(Channel.CONFLATED)
    init {
        settingsScope.launch {
            for (updated in settingsQueue) {
                val saved = withContext(Dispatchers.IO) {
                    preferences.edit().putString("mode", updated.mode.name).putString("count", updated.countText).putString("wait", updated.waitText).commit()
                }
                if (updated == settings.value) {
                    storageError.value = if (saved) null else "设置保存失败，请检查存储空间；禁止开始"
                    saving.value = false
                }
            }
        }
    }
    internal fun record(previous: VisitState, current: VisitState) {
        // No tree, field values, record identity or screenshots enter this bounded in-memory log.
        if (previous.status == current.status && previous.lastStep == current.lastStep && previous.rounds == current.rounds) return
        history.value = (history.value + VisitHistoryEntry(System.currentTimeMillis(), current.rounds, current.step.title, current.message)).takeLast(30)
    }
    var controller: PrivateController? = null
        private set
    fun edit(change: (VisitSettings) -> VisitSettings) {
        if (state.value.active) return
        val updated = change(settings.value)
        settings.value = updated
        saving.value = true
        settingsQueue.trySend(updated)
    }
    fun connect(service: ClickAccessibilityService) {
        controller?.disconnect()
        controller = PrivateController(service, this)
    }
    fun disconnect() { controller?.disconnect(); controller = null }
    fun clearTask() { controller?.stopAndHide(); session.lock() }
    fun confirmTest() {
        val saved = preferences.edit().putBoolean("test_confirmed", true).commit()
        verified.value = saved
        if (!saved) storageError.value = "测试确认未能保存"
    }
    companion object { const val TARGET = "cn.dreamdt.axchat.mztimsc" }
}
data class VisitHistoryEntry(val timeMs: Long, val rounds: Long, val step: String, val message: String)
class PrivateController(private val service: ClickAccessibilityService, private val model: PrivateModel) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overlay = PrivateOverlay(service, this)
    private val workReminder = WorkReminderDisplay(service)
    private val vibration = AndroidRunVibration(service)
    private val bridge = AndroidUiBridge(service, PrivateModel.TARGET) { overlay.bounds() }
    private val driver = AndroidVisitDriver(service, bridge, AndroidChoiceVision(service))
    private val runner = VisitRunner(scope, driver, { SystemClock.elapsedRealtime() }, { notifyPause() }, { model.confirmTest() })
    private var mode = VisitExecution.STEP_TEST
    private var shown = false
    private val probeCheck = ReadOnlyProbe(scope, { message ->
        model.probeResult.value = message
        if (shown) overlay.render(runner.state.value.copy(message = message), mode)
    }, { service.notice(it) }, { driver.probe() })
    init {
        scope.launchVisitRunReminder(runner.state, remind = { vibration.remind() }, cancelReminder = { vibration.cancel() })
        scope.launch { runner.state.collect {
            model.record(model.state.value, it)
            model.state.value = it
            if (shown) overlay.render(it, mode)
        } }
        scope.launch {
            while (isActive) {
                model.weekdayReminder.claim(LocalDateTime.now(), runner.state.value)?.let { event ->
                    showWorkReminder(event)
                }
                val taskId = model.session.taskId
                if (model.session.unlocked.value && taskId != null) {
                    val tasks = service.getSystemService(android.app.ActivityManager::class.java).appTasks
                    if (tasks.none { if (Build.VERSION.SDK_INT >= 29) it.taskInfo.taskId == taskId else @Suppress("DEPRECATION") (it.taskInfo.id == taskId) }) model.clearTask()
                }
                delay(500)
            }
        }
    }
    private fun authorized(): Boolean {
        if (!model.session.unlocked.value) { service.notice("请先打开对应页面"); return false }
        return true
    }
    fun arm(execution: VisitExecution) {
        if (!authorized()) return
        if (runner.state.value.active) { service.notice("请先停止当前任务"); return }
        if (service.appModel.runtime.value.status in setOf(RunStatus.RUNNING, RunStatus.PAUSED)) { service.notice("请先停止普通连点任务"); return }
        if (execution == VisitExecution.FORMAL && !model.verified.value) { service.notice("请先完成一次逐步测试并确认结果"); return }
        if (Build.VERSION.SDK_INT < 30) { service.notice("专用功能需要 Android 11 及以上的画面核验能力"); return }
        if (model.saving.value) { service.notice("设置正在保存，请稍后开始"); return }
        val error = model.settings.value.error() ?: model.storageError.value
        if (error != null) { service.notice(error); return }
        if (!runCatching { service.packageManager.getApplicationInfo(PrivateModel.TARGET, 0) }.isSuccess) { service.notice("未发现已绑定的目标应用"); return }
        mode = execution; service.hideOverlay(); shown = true
        overlay.render(runner.state.value, mode)
        service.notice("请切换到目标待办页，再通过控制条开始或只读检查")
    }
    fun toggle() {
        if (!authorized()) return
        if (probeCheck.active) return
        val state = runner.state.value
        when {
            state.busy -> runner.pause()
            state.status == VisitStatus.FINISH_CONFIRM -> runner.finishTest()
            state.active -> runner.resume()
            else -> {
                if (service.appModel.runtime.value.status in setOf(RunStatus.RUNNING, RunStatus.PAUSED)) { service.notice("请先停止普通连点"); return }
                if (mode == VisitExecution.FORMAL && !model.verified.value) { service.notice("请先完成逐步测试"); return }
                if (model.storageError.value != null) { service.notice("设置保存失败，禁止开始"); return }
                if (model.saving.value) { service.notice("设置正在保存，请稍后开始"); return }
                runner.start(model.settings.value, mode)
            }
        }
    }
    fun probe() {
        if (!authorized() || runner.state.value.active) return
        probeCheck.start()
    }
    fun pause(reason: String, error: Boolean = false) { if (runner.state.value.busy) runner.pause(reason, error) }
    fun stop() { runner.stop(); probeCheck.stop() }
    fun stopAndHide() { stop(); shown = false; overlay.clear() }
    fun dismissWorkReminder() = workReminder.dismiss()
    fun workReminderBounds() = workReminder.bounds()
    internal fun showWorkReminder(event: WorkReminderEvent) {
        workReminder.show(event)
        vibration.remindBeforeBreak()
    }
    fun openPanel() { pause("已返回设置页，请查看后手动继续"); service.openSettings() }
    fun foregroundChanged(packageName: String) {
        if (runner.state.value.busy && packageName.isNotBlank() && packageName != PrivateModel.TARGET && packageName != service.packageName) {
            val inputMethod = service.getSystemService(android.view.inputmethod.InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
            if (!inputMethod) pause("前台应用变化，已暂停，请查看", error = true)
        }
    }
    fun disconnect() { stopAndHide(); workReminder.detach(); scope.cancel() }
    private fun notifyPause() {
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("operation_status", "任务状态", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && service.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(service, 21, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify(21, NotificationCompat.Builder(service, "operation_status").setSmallIcon(R.drawable.ic_launcher).setContentTitle("轻点任务已暂停").setContentText("请打开应用查看并处理").setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        } catch (_: SecurityException) { /* The persistent overlay remains the primary alert. */ }
    }
}

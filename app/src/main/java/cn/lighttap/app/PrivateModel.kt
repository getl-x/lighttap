package cn.lighttap.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDateTime
import java.io.File
import org.json.JSONObject

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
        recognition = runCatching { VisitRecognition.valueOf(preferences.getString("recognition", "CONTROLS")!!) }.getOrDefault(VisitRecognition.CONTROLS),
    ))
    val state = MutableStateFlow(VisitState())
    val probeResult = MutableStateFlow("尚未进行只读检查")
    val verified = MutableStateFlow(preferences.getBoolean(settings.value.recognition.confirmationKey, false))
    val storageError = MutableStateFlow<String?>(if (waitMigrationFailed) "等待设置保存失败，请重新保存后开始" else null)
    val saving = MutableStateFlow(false)
    val history = MutableStateFlow<List<VisitHistoryEntry>>(emptyList())
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val debugLog = VisitDebugLog(File(context.noBackupFilesDir, "visit_debug"), settingsScope,
        elapsedTime = { SystemClock.elapsedRealtime() })
    internal val debugExports = VisitDebugExportStore(File(context.noBackupFilesDir, "visit_debug_exports"))
    val debugExportBusy = MutableStateFlow(false)
    val debugExportMessage = MutableStateFlow<String?>(null)
    private val settingsQueue = Channel<VisitSettings>(Channel.CONFLATED)
    init {
        settingsScope.launch {
            for (updated in settingsQueue) {
                val saved = withContext(Dispatchers.IO) {
                    preferences.edit().putString("mode", updated.mode.name).putString("count", updated.countText).putString("wait", updated.waitText)
                        .putString("recognition", updated.recognition.name).commit()
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
    @Suppress("DEPRECATION")
    internal suspend fun prepareDebugExport(): String {
        val current = state.value
        val screen = context.appModel.screen.value
        val targetInfo = runCatching { context.packageManager.getPackageInfo(TARGET, 0) }.getOrNull()
        val environment = JSONObject().put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("androidApi", Build.VERSION.SDK_INT).put("androidVersion", Build.VERSION.RELEASE)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("fontScale", context.resources.configuration.fontScale.toDouble())
            .put("displayDensityDpi", context.resources.displayMetrics.densityDpi)
            .put("targetVersion", targetInfo?.versionName ?: JSONObject.NULL)
            .put("recognitionMode", settings.value.recognition.name)
            .put("serviceConnected", controller != null).put("status", current.status.name).put("step", current.step.name)
            .put("execution", current.execution.name).put("rounds", current.rounds).put("pendingSubmission", current.pendingSubmission)
            .put("reason", safeVisitDebugReason(current.message)).put("intervalMs", settings.value.waitText.toLongOrNull()?.times(1_000) ?: JSONObject.NULL)
        screen?.let { environment.put("screen", JSONObject().put("width", it.width).put("height", it.height)
            .put("densityDpi", it.densityDpi).put("rotation", it.rotation)) }
        val data = debugLog.snapshot(environment)
        return withContext(Dispatchers.IO) { debugExports.prepare(data) }
    }
    internal fun finishDebugExport(id: String, uri: Uri?) {
        if (debugExportBusy.value) return
        debugExportBusy.value = true
        settingsScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (uri != null) {
                        val bytes = debugExports.read(id)
                        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("No output stream")
                        output.use { it.write(bytes); it.flush() }
                    }
                }
                debugExportMessage.value = if (uri == null) "已取消调试日志导出" else "调试日志已导出，可将此文件发来排查"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { debugExportMessage.value = "调试日志导出失败，请重新导出并检查保存位置" }
            finally {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { debugExports.discard(id) } }
                debugExportBusy.value = false
            }
        }
    }
    var controller: PrivateController? = null
        private set
    fun edit(change: (VisitSettings) -> VisitSettings) {
        if (state.value.active || controller?.probing == true) return
        val updated = change(settings.value)
        settings.value = updated
        verified.value = preferences.getBoolean(updated.recognition.confirmationKey, false)
        saving.value = true
        settingsQueue.trySend(updated)
        if (updated.recognition == VisitRecognition.IMAGE) controller?.warmImageRecognition()
    }
    fun connect(service: ClickAccessibilityService) {
        controller?.disconnect()
        controller = PrivateController(service, this)
    }
    fun disconnect() { controller?.disconnect(); controller = null }
    fun clearTask() { controller?.stopAndHide(); session.lock() }
    fun confirmTest() {
        val saved = preferences.edit().putBoolean(settings.value.recognition.confirmationKey, true).commit()
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
    private val vibration = service.appModel.vibration
    private val surface = VisitSurfaceGuard(conceal = { areas ->
        withContext(Dispatchers.Main.immediate) {
            val covered = areas.any { area -> overlay.bounds()?.intersects(area) == true || workReminder.bounds()?.intersects(area) == true }
            if (covered) {
                overlay.suppress(true); workReminder.suppress(true)
                model.debugLog.record(VisitDebugEvent(VisitDebugKind.OVERLAY_AVOID, ok = true))
            }
            covered
        }
    }, restore = { withContext(Dispatchers.Main.immediate) { overlay.suppress(false); workReminder.suppress(false) } })
    private val nativeBridge = AndroidUiBridge(service, PrivateModel.TARGET) { overlay.bounds() }
    private val imageBridge = AndroidImageVisitUiAccess(service, nativeBridge, surface, model.debugLog)
    private val bridge = DiagnosedVisitUiAccess(SelectedVisitUiAccess({ model.settings.value.recognition },
        SurfaceVisitUiAccess(nativeBridge, surface), imageBridge), model.debugLog, { SystemClock.elapsedRealtime() })
    private val driver: AndroidVisitDriver = AndroidVisitDriver(service, bridge, AndroidChoiceVision(service, surface), model.debugLog,
        surface, { if (shown) overlay.prepare(runner.state.value, mode) })
    private val runner: VisitRunner = VisitRunner(scope, driver, { SystemClock.elapsedRealtime() }, { notifyPause() }, { model.confirmTest() },
        onCompleted = { vibration.complete(service.appModel.completionVibration.mode.value) }, diagnostics = model.debugLog)
    private var mode = VisitExecution.STEP_TEST
    private var shown = false
    private var warming: Job? = null
    fun warmImageRecognition() {
        if (model.settings.value.recognition != VisitRecognition.IMAGE || warming?.isActive == true) return
        warming = scope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                imageBridge.warmUp()
                model.debugLog.record(VisitDebugEvent(VisitDebugKind.MODEL_WARMUP,
                    durationMs = SystemClock.elapsedRealtime() - started, ok = true))
            } catch (error: CancellationException) { throw error }
            catch (error: VisitMismatch) {
                model.debugLog.record(VisitDebugEvent(VisitDebugKind.MODEL_WARMUP,
                    durationMs = SystemClock.elapsedRealtime() - started, ok = false, reason = error.reason))
            }
        }
    }
    private val probeCheck = ReadOnlyProbe(scope, { message ->
        overlay.setProbing(message == "正在只读检查，未执行操作")
        model.probeResult.value = message
        model.debugLog.record(VisitDebugEvent(VisitDebugKind.PROBE, reason = message))
        if (shown) overlay.render(runner.state.value.copy(message = message), mode)
    }, { service.notice(it) }, timeoutMs = { if (model.settings.value.recognition == VisitRecognition.IMAGE) 20_000L else 5_000L }, read = {
        val result = driver.probe()
        if (model.settings.value.recognition == VisitRecognition.IMAGE) {
            imageBridge.probe()
            "本机识字与按钮定位可用；未填写、未点击、未提交"
        } else result
    })
    val probing get() = probeCheck.active
    val recognitionTitle get() = if (model.settings.value.recognition == VisitRecognition.IMAGE) "图像备用" else "控件识别"
    init {
        scope.launchVisitRunReminder(runner.state, remind = { vibration.remind() }, cancelReminder = { vibration.cancelRunReminder() })
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
        warmImageRecognition()
        if (probeCheck.active) { service.notice("只读检查进行中，请稍后开始"); return }
        if (runner.state.value.active) { service.notice("请先停止当前任务"); return }
        if (service.appModel.runtime.value.status in setOf(RunStatus.RUNNING, RunStatus.PAUSED)) { service.notice("请先停止普通连点任务"); return }
        if (execution == VisitExecution.FORMAL && !model.verified.value) { service.notice("请先完成一次逐步测试并确认结果"); return }
        if (Build.VERSION.SDK_INT < 30) { service.notice("专用功能需要 Android 11 及以上的画面核验能力"); return }
        if (model.saving.value) { service.notice("设置正在保存，请稍后开始"); return }
        val error = model.settings.value.error() ?: model.storageError.value
        if (error != null) { service.notice(error); return }
        if (!runCatching { service.packageManager.getApplicationInfo(PrivateModel.TARGET, 0) }.isSuccess) { service.notice("未发现已绑定的目标应用"); return }
        mode = execution; service.hideOverlay(); shown = true
        model.debugLog.record(VisitDebugEvent(VisitDebugKind.ARM, execution = mode, pending = driver.pendingSubmission, recognition = model.settings.value.recognition))
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
    fun disconnect() {
        stopAndHide(); workReminder.detach()
        scope.launch(NonCancellable + Dispatchers.IO) { imageBridge.close() }
        scope.cancel()
    }
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

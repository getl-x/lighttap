package cn.lighttap.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PrivatePanel(model: AppModel) {
    val local = model.privateModel
    val settings by local.settings.collectAsStateWithLifecycle()
    val state by local.state.collectAsStateWithLifecycle()
    val service by model.service.collectAsStateWithLifecycle()
    val screen by model.screen.collectAsStateWithLifecycle()
    val verified by local.verified.collectAsStateWithLifecycle()
    val probe by local.probeResult.collectAsStateWithLifecycle()
    val storageError by local.storageError.collectAsStateWithLifecycle()
    val saving by local.saving.collectAsStateWithLifecycle()
    val history by local.history.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val targetInstalled = remember(revision) { runCatching { context.packageManager.getApplicationInfo(PrivateModel.TARGET, 0) }.isSuccess }
    val notificationsAllowed = remember(revision) { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    val supported = Build.VERSION.SDK_INT >= 30
    val canArm = service != null && targetInstalled && screen != null && supported && !state.active && !saving && settings.error() == null && storageError == null
    var details by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val statusColor = when (state.status) {
        VisitStatus.ERROR -> MaterialTheme.colorScheme.error
        VisitStatus.WAITING, VisitStatus.PAUSED, VisitStatus.CHECKPOINT -> Color(LightTapColors.WARNING)
        VisitStatus.RUNNING, VisitStatus.COMPLETED -> Color(LightTapColors.SUCCESS)
        else -> MaterialTheme.colorScheme.primary
    }
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("专用功能", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("走访填报 · 蒙政通", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToolCard("当前任务 · ${state.rounds} 轮成功") {
                Text(state.message, color = statusColor, style = MaterialTheme.typography.titleMedium)
                if (state.pendingSubmission) Text("等待结果确认：只核对上次结果，不会再次提交。", color = MaterialTheme.colorScheme.error)
                if (state.step == VisitStep.SUBMIT && state.remainingWaitMs > 0 && state.active) Text("提交间隔剩余 ${countdown(state.remainingWaitMs)}", color = statusColor, style = MaterialTheme.typography.headlineSmall)
                if (state.active) {
                    Text("下一步：${state.step.title}", style = MaterialTheme.typography.bodyMedium)
                    Text("请回到目标应用，通过悬浮控制条继续或暂停。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { local.controller?.stop() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("停止任务") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { local.controller?.arm(VisitExecution.STEP_TEST) }, enabled = canArm, modifier = Modifier.weight(1f)) { Text("逐步测试") }
                        Button(onClick = { local.controller?.arm(VisitExecution.FORMAL) }, enabled = canArm && verified, modifier = Modifier.weight(1f)) { Text("正式运行") }
                    }
                    Text("选择模式后，切到目标待办页，再通过控制条开始。测试每步暂停，提交前需人工放行。", style = MaterialTheme.typography.bodySmall)
                }
                storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (saving) Text("正在保存设置…", style = MaterialTheme.typography.bodySmall)
            }
            ToolCard("开始前检查") {
                ReadinessRow("点击服务", service != null)
                ReadinessRow("目标应用已安装", targetInstalled)
                ReadinessRow("屏幕参数可读", screen != null)
                ReadinessRow("画面核验能力 · Android 11 及以上", supported)
                Text(if (verified) "逐步测试已确认，可使用正式模式" else "尚未确认逐步测试，正式模式暂不可用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("目标页面与控件会在每一步执行前重新核验。", style = MaterialTheme.typography.bodySmall)
                if (service == null) OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("开启点击服务") }
            }
            ToolCard("运行参数") {
                VisitRunMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = settings.mode == mode, enabled = !state.active, role = Role.RadioButton, onClick = { local.edit { it.copy(mode = mode) } }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = settings.mode == mode, onClick = null, enabled = !state.active)
                        Text(when (mode) { VisitRunMode.ONCE -> "执行一次"; VisitRunMode.COUNT -> "指定轮数"; VisitRunMode.CONTINUOUS -> "持续运行，直到手动停止" }, Modifier.padding(start = 8.dp))
                    }
                }
                if (settings.mode == VisitRunMode.COUNT) OutlinedTextField(value = settings.countText, onValueChange = { value -> local.edit { it.copy(countText = value) } }, label = { Text("成功轮数") }, enabled = !state.active, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = settings.waitText, onValueChange = { value -> local.edit { it.copy(waitText = value) } }, label = { Text("两次提交最小间隔（秒）") }, enabled = !state.active, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), supportingText = { Text("默认 61 秒，含 1 秒余量；范围 61～86400。填写和核验计入间隔，提交前只补足剩余时间。") }, modifier = Modifier.fillMaxWidth(), isError = settings.error() != null)
                settings.error()?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { details = !details }) { Text(if (details) "收起运行说明" else "查看计时与核验规则") }
                if (details) {
                    Text("通常从上次有效提交点击计时；若提交后出现加载，则从加载结束、正常待办页恢复并确认成功后的首个有效时刻计时。加载中的表单或灰色遮罩都不启动间隔；未确认结果前禁止再次提交。暂停期间间隔继续计时。", style = MaterialTheme.typography.bodySmall)
                    Text("状态画面只在内存中核验，不保存、不上传。界面必须完整可读并严格匹配，任何不确定都会暂停。", style = MaterialTheme.typography.bodySmall)
                    Text("检查地址：松州园小区\n走访描述：无异常\n已有其他内容或状态异常时停止，不覆盖。", style = MaterialTheme.typography.bodySmall)
                }
            }
            CompletionVibrationSettingsCard(model)
            ToolCard("任务提醒") {
                Text("正式运行每 5 分钟短震两下；暂停或结束后停止，继续后重新计时。", style = MaterialTheme.typography.bodySmall)
                Text("工作日 11:15、17:00 正在正式运行时，震动三下并显示顶部提醒与无声通知。划掉任一提醒可同步关闭，任务仍会继续。", style = MaterialTheme.typography.bodySmall)
                Text(if (notificationsAllowed) "系统通知已允许" else "系统通知未允许，顶部提醒仍会显示", color = if (notificationsAllowed) Color(LightTapColors.SUCCESS) else Color(LightTapColors.WARNING))
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("管理提醒通知") }
            }
            ToolCard("检查与运行记录") {
                Text("只读检查：$probe", style = MaterialTheme.typography.bodySmall)
                Text("已执行：${state.lastStep}\n本步结果：${state.result.ifBlank { "尚无" }}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "收起近期记录" else "近期记录 · ${history.size}") }
                if (showHistory) {
                    Text("保留本次应用运行的最近 30 条状态，不含表单内容或截图。", style = MaterialTheme.typography.bodySmall)
                    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                    history.asReversed().forEach { event ->
                        HorizontalDivider()
                        Text("${formatter.format(Date(event.timeMs))} · ${event.rounds} 轮 · ${event.step}\n${event.message}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable private fun ReadinessRow(label: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(if (ready) "就绪" else "未就绪", color = if (ready) Color(LightTapColors.SUCCESS) else Color(LightTapColors.WARNING), style = MaterialTheme.typography.bodySmall)
    }
}

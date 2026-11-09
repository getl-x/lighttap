package cn.lighttap.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Ink = Color(LightTapColors.INK)
private val Purple = Color(LightTapColors.PRIMARY)
private val Muted = Color(LightTapColors.MUTED)
private val Canvas = Color(LightTapColors.CANVAS)
@Composable fun LightTapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Purple, secondary = Color(LightTapColors.SUCCESS), error = Color(LightTapColors.ERROR), background = Canvas, surface = Color.White, onBackground = Ink, onSurface = Ink, onSurfaceVariant = Muted), content = content)
}
class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        appModel.settingsVisible.value = true
        appModel.service.value?.pauseRun()
        appModel.privateModel.controller?.pause("已返回设置页，请查看后手动继续")
    }
    override fun onStop() {
        appModel.settingsVisible.value = false
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLightTapEdgeToEdge()
        appModel.privateModel.session.attachTask(taskId, savedInstanceState != null)
        if (!appModel.privateModel.session.unlocked.value) appModel.privateModel.controller?.stopAndHide()
        setContent { LightTapTheme {
            val unlocked by appModel.privateModel.session.unlocked.collectAsStateWithLifecycle()
            var prompt by rememberSaveable { mutableStateOf(false) }
            var password by remember { mutableStateOf("") }
            var verifying by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val taps = remember { EntryTapCounter { android.os.SystemClock.elapsedRealtime() } }
            BackHandler(enabled = unlocked) { moveTaskToBack(true) }
            if (unlocked) PrivatePanel(appModel) else MainScreen(appModel,
                { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                { startActivity(Intent(this, TouchTestActivity::class.java)) },
                { if (taps.tap()) { prompt = true; error = false } })
            if (prompt && !unlocked) AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text("访问验证") },
                text = { OutlinedTextField(value = password, onValueChange = { password = it.take(32); error = false }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), enabled = !verifying, singleLine = true, label = { Text("密码") }, isError = error, supportingText = { if (error) Text("验证未通过") }) },
                confirmButton = { TextButton(enabled = !verifying && password.isNotEmpty(), onClick = {
                    verifying = true
                    val candidate = password.toCharArray(); password = ""
                    scope.launch {
                        val accepted = withContext(Dispatchers.Default) { runCatching { AccessVerifier.verify(candidate, BuildConfig.ACCESS_SALT, BuildConfig.ACCESS_DIGEST) }.getOrDefault(false) }
                        verifying = false
                        if (accepted) { appModel.privateModel.session.unlock(); prompt = false } else error = true
                    }
                }) { Text(if (verifying) "验证中" else "确认") } },
                dismissButton = { TextButton(enabled = !verifying, onClick = { prompt = false; password = ""; error = false }) { Text("关闭") } },
            )
        } }
    }
}
private data class NumericEdit(val title: String, val value: Long, val min: Long, val max: Long, val unit: String, val apply: (Long) -> Unit)
@Composable
private fun MainScreen(model: AppModel, openAccessibility: () -> Unit, openTest: () -> Unit, titleTap: () -> Unit) {
    val config by model.config.collectAsStateWithLifecycle()
    val runtime by model.runtime.collectAsStateWithLifecycle()
    val service by model.service.collectAsStateWithLifecycle()
    val screen by model.screen.collectAsStateWithLifecycle()
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val storageWritable by model.storageWritable.collectAsStateWithLifecycle()
    val storageMessage by model.storageMessage.collectAsStateWithLifecycle()
    val visible by model.overlayVisible.collectAsStateWithLifecycle()
    val editable = loaded && storageWritable && runtime.status != RunStatus.RUNNING
    var numberEdit by remember { mutableStateOf<NumericEdit?>(null) }
    var explainService by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    val mismatch = config.points.isNotEmpty() && screen != null && config.screen != screen
    Scaffold(containerColor = Canvas) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(Purple, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text("◎", fontSize = 30.sp, color = Color.White) }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("轻点", modifier = Modifier.clickable(onClick = titleTap), fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("让重复操作，简单一点", fontSize = 12.sp, color = Muted)
                }
                Surface(color = Color(0xFFEAE7FF), shape = RoundedCornerShape(20.dp)) { Text("v${BuildConfig.VERSION_NAME}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Purple, fontSize = 12.sp) }
            }
            Surface(color = Purple, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(if (service != null) 18.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (service != null) "点击服务已就绪" else "从一个点击点开始。", color = Color.White, fontSize = if (service != null) 18.sp else 24.sp, fontWeight = FontWeight.Bold)
                    Text(if (service != null) "切换到目标应用，用悬浮控制条添加点位并开始。" else "开启点击服务后，即可在其他应用上设置点位。", color = Color(0xFFE4E0FF), fontSize = 13.sp, lineHeight = 21.sp)
                    Button(onClick = { if (service == null) explainService = true else service?.showOverlay() }, enabled = loaded, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Purple)) {
                        Text(when { !loaded -> "正在读取配置…"; service == null -> "开启点击服务"; visible -> "控制条已显示"; else -> "显示悬浮控制条" }, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Section("运行状态", if (service == null) "服务未开启" else "服务已连接") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("完成轮数", "${runtime.rounds}")
                    Metric("有效运行", "${runtime.elapsedMs / 1000} 秒")
                    Metric("当前状态", when (runtime.status) { RunStatus.IDLE -> "待机"; RunStatus.RUNNING -> "运行中"; RunStatus.PAUSED -> "已暂停"; RunStatus.ERROR -> "已停止" })
                }
                Text(runtime.message, fontSize = 12.sp, color = if (runtime.status == RunStatus.ERROR) MaterialTheme.colorScheme.error else Muted)
                if (runtime.status == RunStatus.RUNNING || runtime.status == RunStatus.PAUSED) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { if (runtime.status == RunStatus.RUNNING) service?.pauseRun() else { service?.showOverlay(); service?.notice("请切换到目标应用，再通过悬浮控制条继续") } }, modifier = Modifier.weight(1f)) { Text(if (runtime.status == RunStatus.RUNNING) "暂停" else "显示控制条") }
                        OutlinedButton(onClick = { service?.stopRun() }, modifier = Modifier.weight(1f)) { Text("停止") }
                    }
                }
            }
            storageMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            if (mismatch) {
                Surface(color = Color(0xFFFFF1DA), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("请重新确认点位", fontWeight = FontWeight.Bold)
                        Text("屏幕方向、分辨率或显示大小与保存时不同。请显示控制条，检查并拖动点位后再确认。", fontSize = 12.sp)
                        OutlinedButton(onClick = { service?.confirmPoints() }, enabled = editable && service != null) { Text("确认当前点位") }
                    }
                }
            }
            ProfileSettings(model, editable)
            Section("点击方式", "01") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClickMode.entries.forEach { mode -> FilterChip(selected = config.mode == mode, enabled = editable, onClick = { model.edit { it.copy(mode = mode) } }, label = { Text(mode.title) }, modifier = Modifier.weight(1f)) }
                }
                Text(when (config.mode) { ClickMode.SINGLE -> "重复执行第一个启用的动作，其他动作保留。"; ClickMode.SEQUENCE -> "按编号执行所有启用动作，各步重复完成后算一轮。"; ClickMode.SIMULTANEOUS -> "每轮同时执行启用的点击或长按；单步重复须为 1，数量受系统上限限制。" }, color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                if (config.mode == ClickMode.SIMULTANEOUS) {
                    SettingRow("每轮等待", "${config.groupIntervalMs} 毫秒", editable) { numberEdit = NumericEdit("同时模式每轮等待", config.groupIntervalMs, 10, 60_000, "毫秒") { n -> model.edit { it.copy(groupIntervalMs = n) } } }
                }
            }
            ActionSettings(model, editable) { clearConfirm = true }
            AdvancedSettings(model, editable)
            CompletionVibrationSettingsCard(model)
            Section("何时停止", "02") {
                Column {
                    StopMode.entries.forEach { mode ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = config.stopMode == mode, enabled = editable, role = Role.RadioButton, onClick = { model.edit { it.copy(stopMode = mode) } }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = config.stopMode == mode, enabled = editable, onClick = null)
                            Text(mode.title, fontSize = 14.sp)
                        }
                    }
                }
                when (config.stopMode) {
                    StopMode.ROUNDS -> SettingRow("执行轮数", "${config.roundLimit} 轮", editable) { numberEdit = NumericEdit("执行轮数", config.roundLimit, 1, 1_000_000, "轮") { n -> model.edit { it.copy(roundLimit = n) } } }
                    StopMode.DURATION -> SettingRow("有效运行时长", "${config.durationMs / 1000} 秒", editable) { numberEdit = NumericEdit("运行时长（暂停不计时）", config.durationMs / 1000, 1, 86_400, "秒") { n -> model.edit { it.copy(durationMs = n * 1000) } } }
                    StopMode.MANUAL -> Text("持续循环，直到你通过控制条暂停或停止。", fontSize = 12.sp, color = Muted)
                }
                Text("默认按住 50 毫秒，可逐步调整。动作后等待从手势结束开始计算，实际速度受设备与目标应用影响。", fontSize = 11.sp, color = Muted, lineHeight = 18.sp)
            }
            OutlinedButton(onClick = openTest, modifier = Modifier.fillMaxWidth()) { Text("打开点击测试页") }
            Text("完全离线 · 数据在本机处理\n锁屏、旋转或服务断开时自动停止。恢复后需手动开始。", Modifier.align(Alignment.CenterHorizontally), fontSize = 11.sp, color = Muted, lineHeight = 19.sp)
        }
    }
    numberEdit?.let { edit ->
        NumberDialog(edit.title, edit.value, edit.min, edit.max, edit.unit, editable, { numberEdit = null }, edit.apply)
    }
    if (explainService) AlertDialog(onDismissRequest = { explainService = false }, title = { Text("开启点击服务") }, text = { Text("轻点使用无障碍服务，在你选择的位置模拟点击并显示悬浮控制条。\n\n仅按你启用的操作读取必要的界面控件；不录屏，不联网。请在系统设置中找到「轻点 · 点击服务」并手动开启。\n\n如果系统提示受限制，请在轻点的应用信息页中查看「允许受限制的设置」。") }, confirmButton = { TextButton(onClick = { explainService = false; openAccessibility() }) { Text("前往系统设置") } }, dismissButton = { TextButton(onClick = { explainService = false }) { Text("取消") } })
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("清空全部点位？") }, text = { Text("此操作会删除当前 ${config.points.size} 个点位。") }, confirmButton = { TextButton(enabled = editable, onClick = { model.edit { it.copy(points = emptyList(), screen = model.screen.value) }; clearConfirm = false }) { Text("清空") } }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } })
}
@Composable private fun Section(title: String, aside: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp); Text(aside, color = Muted, fontSize = 12.sp) }
            content()
        }
    }
}
@Composable private fun Metric(label: String, value: String) { Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(label, color = Muted, fontSize = 11.sp); Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp) } }
@Composable private fun SettingRow(title: String, value: String, enabled: Boolean, edit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f)); TextButton(onClick = edit, enabled = enabled) { Text("$value  ›") } }
}

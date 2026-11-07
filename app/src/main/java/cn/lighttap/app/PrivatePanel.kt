package cn.lighttap.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PrivatePanel(model: AppModel) {
    val local = model.privateModel
    val settings by local.settings.collectAsStateWithLifecycle()
    val state by local.state.collectAsStateWithLifecycle()
    val service by model.service.collectAsStateWithLifecycle()
    val verified by local.verified.collectAsStateWithLifecycle()
    val probe by local.probeResult.collectAsStateWithLifecycle()
    val storageError by local.storageError.collectAsStateWithLifecycle()
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val context = androidx.compose.ui.platform.LocalContext.current
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("专用功能", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("走访填报", style = MaterialTheme.typography.titleLarge)
            Text("目标：蒙政通。界面必须能完整读取并严格匹配；任何不确定都会暂停。", style = MaterialTheme.typography.bodySmall)
            Text(if (verified) "已完成并确认一次逐步测试" else "尚未完成现场测试。请先逐步验证；正式模式暂不可用。", color = MaterialTheme.colorScheme.primary)
            Text("运行方式", fontWeight = FontWeight.Bold)
            VisitRunMode.entries.forEach { mode ->
                Row {
                    RadioButton(selected = settings.mode == mode, onClick = { local.edit { it.copy(mode = mode) } }, enabled = !state.active)
                    Text(when (mode) { VisitRunMode.ONCE -> "执行一次"; VisitRunMode.COUNT -> "指定轮数"; VisitRunMode.CONTINUOUS -> "持续运行，直到手动停止" }, Modifier.padding(top = 12.dp))
                }
            }
            if (settings.mode == VisitRunMode.COUNT) OutlinedTextField(value = settings.countText, onValueChange = { value -> local.edit { it.copy(countText = value) } }, label = { Text("成功轮数") }, enabled = !state.active, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = settings.waitText, onValueChange = { value -> local.edit { it.copy(waitText = value) } }, label = { Text("两次提交最小间隔（秒）") }, enabled = !state.active, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), supportingText = { Text("默认 61 秒，含 1 秒余量；范围 61～86400。从上次提交确认成功计时，期间先填写下一条，提交前只补足剩余间隔；暂停期间也计时。") }, modifier = Modifier.fillMaxWidth(), isError = settings.waitText.toLongOrNull() !in 61L..86_400L || !settings.waitText.matches(Regex("[0-9]{1,5}")))
            settings.error()?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("状态视觉校验自动在内存中完成，不保存图片、不上传。", style = MaterialTheme.typography.bodySmall)
            Text("正式运行中每 5 分钟短震两下，提醒任务仍在继续；暂停或结束后停止提醒，继续运行后重新计时。", style = MaterialTheme.typography.bodySmall)
            Text("周一至周五 11:15 和 17:00，任务正在正式运行时，较强震动三下，并显示常驻顶部提醒条及无声音系统通知。左右划掉提醒条或划掉系统通知可同步关闭；每个时段每天一次，按手机本地时间判断。任务继续运行，请及时停止。", style = MaterialTheme.typography.bodySmall)
            Text("检查地址：松州园小区\n走访描述：无异常\n已有其他内容或状态异常时停止，不覆盖。", style = MaterialTheme.typography.bodySmall)
            if (service == null) Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text("前往系统设置开启点击服务") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { local.controller?.arm(VisitExecution.STEP_TEST) }, enabled = service != null && !state.active && settings.error() == null && storageError == null, modifier = Modifier.weight(1f)) { Text("逐步测试") }
                Button(onClick = { local.controller?.arm(VisitExecution.FORMAL) }, enabled = service != null && verified && !state.active && settings.error() == null && storageError == null, modifier = Modifier.weight(1f)) { Text("正式运行") }
            }
            Text("先选择模式显示控制条，再切到目标待办页，通过控制条开始。测试模式每步暂停，一次继续只放行一步，提交前需人工放行。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("只读检查：$probe", style = MaterialTheme.typography.bodySmall)
            Text("当前状态：${state.message}")
            Text("成功轮数：${state.rounds}\n已执行：${state.lastStep}\n本步结果：${state.result}\n下一步：${state.step.title}\n预期：${state.step.expected}", style = MaterialTheme.typography.bodySmall)
            if (state.step == VisitStep.SUBMIT && state.remainingWaitMs > 0 && state.active) Text("距允许提交 ${countdown(state.remainingWaitMs)}", style = MaterialTheme.typography.headlineSmall)
            if (state.pendingSubmission) Text("存在未确认提交。程序只会核对结果，不会再次提交；请查看目标应用。", color = MaterialTheme.colorScheme.error)
            if (state.active) {
                Text("请回到目标应用，通过悬浮控制条继续或暂停。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { local.controller?.stop() }, modifier = Modifier.fillMaxWidth()) { Text("停止任务") }
            }
            if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { notification.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许任务提醒通知") }
            Text("页面保留在当前任务中；返回键进入后台。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

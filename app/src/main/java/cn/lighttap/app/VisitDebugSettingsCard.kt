package cn.lighttap.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VisitDebugSettingsCard(model: PrivateModel) {
    val scope = rememberCoroutineScope()
    val writing by model.debugExportBusy.collectAsStateWithLifecycle()
    val message by model.debugExportMessage.collectAsStateWithLifecycle()
    val storageError by model.debugLog.storageError.collectAsStateWithLifecycle()
    var preparing by remember { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportId
        exportId = null
        if (id != null) model.finishDebugExport(id, uri)
        else if (uri != null) model.debugExportMessage.value = "调试导出内容已失效，请重新导出"
    }
    ToolCard("调试日志") {
        Text("自动保留最近约 1 MB 的专用任务诊断，重启应用后仍可导出。", style = MaterialTheme.typography.bodySmall)
        Text("包含步骤、耗时、页面状态和失败原因；不含业务姓名、证件号、地址、填写内容或截图。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !preparing && !writing && exportId == null, modifier = Modifier.fillMaxWidth(), onClick = {
            preparing = true
            model.debugExportMessage.value = null
            scope.launch {
                try {
                    val id = model.prepareDebugExport()
                    exportId = id
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                    try { exporter.launch("lighttap-custom-debug-${BuildConfig.VERSION_NAME}-$stamp.json") }
                    catch (e: Exception) {
                        exportId = null
                        withContext(Dispatchers.IO) { model.debugExports.discard(id) }
                        throw e
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { model.debugExportMessage.value = "无法准备调试日志，请检查存储空间后重试" }
                finally { preparing = false }
            }
        }) { Text(if (preparing || writing) "正在导出…" else "导出调试日志") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        storageError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

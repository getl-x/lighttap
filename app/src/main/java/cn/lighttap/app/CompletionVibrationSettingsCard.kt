package cn.lighttap.app

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun CompletionVibrationSettingsCard(model: AppModel) {
    val mode by model.completionVibration.mode.collectAsStateWithLifecycle()
    val runtime by model.runtime.collectAsStateWithLifecycle()
    val visit by model.privateModel.state.collectAsStateWithLifecycle()
    val editable = runtime.status !in setOf(RunStatus.RUNNING, RunStatus.PAUSED) && !visit.active
    val context = LocalContext.current
    var choosing by rememberSaveable { mutableStateOf(false) }
    fun preview(option: CompletionVibration) {
        if (!model.vibration.complete(option)) Toast.makeText(context, "设备不支持震动或系统未允许震动", Toast.LENGTH_SHORT).show()
    }
    ToolCard("完成震动") {
        Text("任务正常完成后提醒，暂停、手动停止或异常中断时不提醒。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("震动模式", Modifier.weight(1f))
            TextButton(enabled = editable, onClick = { choosing = true }) { Text("${mode.title} ›") }
        }
        OutlinedButton(enabled = editable && mode != CompletionVibration.OFF, onClick = { preview(mode) }) { Text("试震") }
        Text("此设置在所有任务中通用，自动保存；震动效果受设备和系统设置影响。", style = MaterialTheme.typography.bodySmall)
    }
    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text("完成震动模式") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CompletionVibration.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(
                            selected = mode == option, enabled = editable, role = Role.RadioButton,
                            onClick = { model.completionVibration.select(option) },
                        ), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == option, onClick = null, enabled = editable)
                        Column(Modifier.padding(start = 8.dp).weight(1f)) {
                            Text(option.title)
                            Text(option.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(enabled = editable && mode != CompletionVibration.OFF, onClick = { preview(mode) }) { Text("试震当前模式") }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text("完成") } },
    )
}

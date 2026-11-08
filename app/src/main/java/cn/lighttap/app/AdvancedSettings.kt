package cn.lighttap.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun ToolCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp); content()
        }
    }
}
@Composable fun ProfileSettings(model: AppModel, editable: Boolean) {
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val saving by model.saving.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nameDialog by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val exports = remember { ProfileExportStore(java.io.File(context.noBackupFilesDir, "profile_exports")) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    fun notice(text: String) { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportId
        if (uri != null) scope.launch {
            working = true
            try {
                withContext(Dispatchers.IO) {
                    val data = exports.read(id ?: error("导出内容已失效，请重新导出"))
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data); it.flush() } ?: error("无法打开目标文件")
                    exports.discard(id)
                }
                notice("方案已导出")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { notice("导出失败，请重新导出并检查文件位置和空间") }
            finally { working = false; exportId = null }
        } else {
            exportId = null
            if (id != null) scope.launch(Dispatchers.IO) { exports.discard(id) }
        }
    }
    fun export(profilesToExport: List<ClickProfile>, filename: String) {
        scope.launch {
            working = true
            try {
                exportId = withContext(Dispatchers.IO) { exports.prepare(profilesToExport) }
                exporter.launch(filename)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { exportId = null; notice("无法准备导出，请检查存储空间") }
            finally { working = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            working = true
            runCatching {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        val buffer = ByteArray(2_000_001); var length = 0
                        while (length < buffer.size) { val n = it.read(buffer, length, buffer.size - length); if (n < 0) break; length += n }
                        val bytes = buffer.copyOf(length)
                        require(bytes.size <= 2_000_000) { "文件过大，最大 2 MB" }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("无法读取文件")
                }
                model.importProfiles(raw)
            }.onSuccess { notice("已导入 $it 套方案；载入后请检查并确认点位") }
                .onFailure { notice(it.message ?: "方案导入失败") }
            working = false
        }
    }
    ToolCard("我的方案 · ${profiles.size}/30") {
        Text("当前编辑自动保存；命名方案保存独立快照。载入、覆盖和删除前会再次确认。", style = MaterialTheme.typography.bodySmall)
        if (saving) Text("正在保存到本机…", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = editable && !working && profiles.size < 30, onClick = { renameId = null; name = ""; nameDialog = true }) { Text("另存方案") }
            OutlinedButton(enabled = editable && !working, onClick = { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text("导入") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = editable && !working, onClick = {
                export(listOf(ClickProfile("current", "当前方案", model.config.value)), "轻点-当前方案.json")
            }) { Text("导出当前") }
            TextButton(enabled = editable && !working && profiles.isNotEmpty(), onClick = {
                export(profiles, "轻点-方案备份.json")
            }) { Text("导出方案库") }
        }
        profiles.forEach { p ->
            HorizontalDivider()
            Text(p.name, fontWeight = FontWeight.Medium)
            Text("${p.config.mode.title} · ${p.config.points.size} 个动作", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(enabled = editable && !working, onClick = { confirm = "载入「${p.name}」并替换当前配置？任务进度将清零，可用撤销恢复配置。" to { model.loadProfile(p) } }) { Text("载入") }
                TextButton(enabled = editable && !working, onClick = { confirm = "用当前配置覆盖「${p.name}」？" to { model.saveProfile(p.name, p.id) } }) { Text("覆盖") }
                TextButton(enabled = editable && !working, onClick = { renameId = p.id; name = p.name; nameDialog = true }) { Text("改名") }
                TextButton(enabled = editable && !working, onClick = { confirm = "删除「${p.name}」？" to { model.deleteProfile(p.id) } }) { Text("删除") }
            }
        }
    }
    if (nameDialog) AlertDialog(onDismissRequest = { nameDialog = false }, title = { Text(if (renameId == null) "保存为新方案" else "修改方案名称") }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("名称（最多 40 字）") })
    }, confirmButton = { TextButton(enabled = editable && name.trim().isNotEmpty(), onClick = {
        if (renameId == null) model.saveProfile(name) else model.renameProfile(renameId!!, name)
        nameDialog = false
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = { nameDialog = false }) { Text("取消") } })
    confirm?.let { (text, action) -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("确认操作") }, text = { Text(text) }, confirmButton = { TextButton(enabled = editable, onClick = { action(); confirm = null }) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}

@Composable fun ActionSettings(model: AppModel, editable: Boolean, clear: () -> Unit) {
    val config by model.config.collectAsStateWithLifecycle()
    val canUndo by model.canUndo.collectAsStateWithLifecycle()
    val service by model.service.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ClickPoint?>(null) }
    ToolCard("动作列表 · ${config.points.size}/$MAX_ACTIONS") {
        Text("单点执行首个启用动作；顺序模式依次编排。滑动的起点和终点均可拖动。", style = MaterialTheme.typography.bodySmall)
        if (config.points.isEmpty()) Text("显示控制条，在目标应用中添加点位，再编辑动作类型。", style = MaterialTheme.typography.bodySmall)
        config.points.forEachIndexed { index, p ->
            if (index > 0) HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = p.enabled, enabled = editable, onCheckedChange = { value -> model.edit { c -> c.copy(points = c.points.map { if (it.id == p.id) it.copy(enabled = value) else it }) } })
                Column(Modifier.weight(1f)) {
                    Text("${index + 1}. ${p.label.ifBlank { p.type.title }}", fontWeight = FontWeight.Medium)
                    Text("${p.type.title} · ${p.repeatCount} 次 · 后等 ${p.intervalMs} ms", style = MaterialTheme.typography.bodySmall)
                    if (p.type != ActionType.WAIT) Text("(${p.x.toInt()}, ${p.y.toInt()})" + if (p.type == ActionType.SWIPE) " → (${p.endX.toInt()}, ${p.endY.toInt()})" else "", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = editable, onClick = { editing = p }) { Text("编辑") }
            }
            Row {
                TextButton(enabled = editable && index > 0, onClick = { model.edit { c -> c.copy(points = c.points.toMutableList().apply { add(index - 1, removeAt(index)) }) } }) { Text("上移") }
                TextButton(enabled = editable && index < config.points.lastIndex, onClick = { model.edit { c -> c.copy(points = c.points.toMutableList().apply { add(index + 1, removeAt(index)) }) } }) { Text("下移") }
                TextButton(enabled = editable && config.points.size < MAX_ACTIONS, onClick = { model.edit { c -> c.copy(points = c.points.toMutableList().apply { add(index + 1, p.copy(id = (c.points.maxOfOrNull { it.id } ?: 0) + 1)) }) } }) { Text("复制") }
                TextButton(enabled = editable, onClick = { model.edit { c -> c.copy(points = c.points.filterNot { it.id == p.id }) } }) { Text("删除") }
            }
        }
        Row {
            OutlinedButton(enabled = editable && service != null && config.points.size < MAX_ACTIONS, onClick = { service?.let { it.showOverlay(); it.addPoint() } }) { Text("添加点位") }
            TextButton(enabled = editable && canUndo, onClick = { model.undo() }) { Text("撤销") }
            TextButton(enabled = editable && config.points.isNotEmpty(), onClick = clear) { Text("清空") }
        }
        var batch by remember { mutableStateOf(false) }
        TextButton(enabled = editable && config.points.isNotEmpty(), onClick = { batch = true }) { Text("统一设置动作后等待") }
        if (batch) NumberDialog("全部动作后等待", 100, 10, 60_000, "毫秒", editable, { batch = false }) { n -> model.edit { c -> c.copy(points = c.points.map { it.copy(intervalMs = n) }) } }
    }
    editing?.let { point -> ActionDialog(point, model.screen.value ?: config.screen, editable, { editing = null }) { updated ->
        model.edit { c -> c.copy(points = c.points.map { if (it.id == updated.id) updated else it }) }
    } }
}

@Composable fun AdvancedSettings(model: AppModel, editable: Boolean) {
    val config by model.config.collectAsStateWithLifecycle()
    ToolCard("时序与控制") {
        NumberRow("启动倒计时", config.startDelayMs / 1000, 0, 86_400, "秒", editable) { n -> model.edit { it.copy(startDelayMs = n * 1000) } }
        NumberRow("每轮额外等待", config.roundDelayMs / 1000, 0, 86_400, "秒", editable) { n -> model.edit { it.copy(roundDelayMs = n * 1000) } }
        ToggleRow("运行时保持亮屏", "仅在普通任务运行时生效，暂停或停止后恢复系统设置。", config.keepScreenOn, editable) { v -> model.edit { it.copy(keepScreenOn = v) } }
        ToggleRow("音量键控制", "控制条显示时：音量上开始/暂停，音量下停止；可能需重新开启点击服务。", config.volumeKeys, editable) { v -> model.edit { it.copy(volumeKeys = v) } }
        NumberRow("悬浮透明度", config.overlayOpacity.toLong(), 35, 100, "%", editable) { n -> model.edit { it.copy(overlayOpacity = n.toInt()) } }
        NumberRow("点位标记大小", config.markerSizeDp.toLong(), 28, 72, "dp", editable) { n -> model.edit { it.copy(markerSizeDp = n.toInt()) } }
        Text("暂停会冻结等待；有效运行时长包含启动及轮间等待。停止后不再派发新动作，已经发出的手势会自然结束（最长约 20 秒）。", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun ToggleRow(title: String, hint: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title); Text(hint, style = MaterialTheme.typography.bodySmall) }; Switch(checked = checked, enabled = enabled, onCheckedChange = change) }
}
@Composable private fun NumberRow(title: String, value: Long, min: Long, max: Long, unit: String, enabled: Boolean, save: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); TextButton(enabled = enabled, onClick = { open = true }) { Text("$value $unit ›") } }
    if (open) NumberDialog(title, value, min, max, unit, enabled, { open = false }, save)
}
@Composable internal fun NumberDialog(title: String, value: Long, min: Long, max: Long, unit: String, enabled: Boolean, close: () -> Unit, save: (Long) -> Unit) {
    var input by remember { mutableStateOf(value.toString()) }
    val n = input.toLongOrNull(); val valid = n != null && n in min..max
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DigitField(input, { input = it }, "$unit（$min～$max）", Modifier.fillMaxWidth())
            if (!valid) Text("请输入 $min～$max 的整数", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (unit == "毫秒") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100L, 500L, 1_000L).filter { it in min..max }.forEach { preset ->
                    SuggestionChip(onClick = { input = preset.toString() }, label = { Text(if (preset == 1_000L) "1 秒" else "$preset ms") })
                }
            }
        }
    }, confirmButton = { TextButton(enabled = enabled && valid, onClick = { save(n!!); close() }) { Text("保存") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}
@Composable private fun DigitField(value: String, change: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value = value, onValueChange = { if (it.length <= 9 && it.all(Char::isDigit)) change(it) }, label = { Text(label, fontSize = 12.sp) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = modifier)
}
@Composable private fun ActionDialog(point: ClickPoint, screen: ScreenSpec?, editable: Boolean, close: () -> Unit, save: (ClickPoint) -> Unit) {
    var type by remember { mutableStateOf(point.type) }
    var label by remember { mutableStateOf(point.label) }
    var x by remember { mutableStateOf(point.x.toInt().toString()) }; var y by remember { mutableStateOf(point.y.toInt().toString()) }
    var ex by remember { mutableStateOf(point.endX.toInt().toString()) }; var ey by remember { mutableStateOf(point.endY.toInt().toString()) }
    var duration by remember { mutableStateOf(point.durationMs.toString()) }
    var repeat by remember { mutableStateOf(point.repeatCount.toString()) }; var gap by remember { mutableStateOf(point.intervalMs.toString()) }
    val px = x.toFloatOrNull(); val py = y.toFloatOrNull(); val qx = ex.toFloatOrNull(); val qy = ey.toFloatOrNull()
    val ms = duration.toLongOrNull(); val count = repeat.toIntOrNull(); val wait = gap.toLongOrNull()
    fun inScreen(a: Float?, b: Float?) = a != null && b != null && a >= 0 && b >= 0 && a < (screen?.width ?: 100_000) && b < (screen?.height ?: 100_000)
    val valid = (type == ActionType.WAIT || inScreen(px, py)) && (type != ActionType.SWIPE || inScreen(qx, qy)) && ms != null && ms in (if (type == ActionType.LONG_PRESS) 500L else 1L)..10_000L && count != null && count in 1..10_000 && wait != null && wait in 10..60_000
    AlertDialog(onDismissRequest = close, title = { Text("编辑动作") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionType.entries.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { option -> FilterChip(selected = type == option, onClick = {
                type = option; duration = when (option) { ActionType.LONG_PRESS -> "800"; ActionType.SWIPE -> "400"; ActionType.WAIT -> "1000"; else -> "50" }
            }, label = { Text(option.title) }) } } }
            OutlinedTextField(value = label, onValueChange = { label = it.take(40) }, label = { Text("动作备注（可选）") }, singleLine = true)
            if (type != ActionType.WAIT) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DigitField(x, { x = it }, "起点 X", Modifier.weight(1f)); DigitField(y, { y = it }, "起点 Y", Modifier.weight(1f)) }
            if (type == ActionType.SWIPE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DigitField(ex, { ex = it }, "终点 X", Modifier.weight(1f)); DigitField(ey, { ey = it }, "终点 Y", Modifier.weight(1f)) }
            DigitField(duration, { duration = it }, if (type == ActionType.WAIT) "等待时长 ms（1～10000）" else "按住/滑动时长 ms（${if (type == ActionType.LONG_PRESS) 500 else 1}～10000）")
            DigitField(repeat, { repeat = it }, "本步重复次数（1～10000）")
            DigitField(gap, { gap = it }, "每次动作后等待 ms（10～60000）")
            if (type == ActionType.DOUBLE_TAP) Text("两次按住间隔固定 100 ms；双击算一次动作。", style = MaterialTheme.typography.bodySmall)
            if (!valid) Text("请填写范围内的整数；坐标须位于当前屏幕内。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = editable && valid, onClick = {
        save(point.copy(type = type, label = label, x = px ?: point.x, y = py ?: point.y, endX = qx ?: point.endX, endY = qy ?: point.endY, durationMs = ms!!, repeatCount = count!!, intervalMs = wait!!)); close()
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}

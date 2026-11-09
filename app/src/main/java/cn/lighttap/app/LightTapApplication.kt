package cn.lighttap.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

private val Context.settings by preferencesDataStore("click_settings")
class LightTapApplication : Application() {
    lateinit var model: AppModel
        private set
    override fun onCreate() { super.onCreate(); model = AppModel(this) }
}
val Context.appModel: AppModel get() = (applicationContext as LightTapApplication).model

class AppModel(context: Context) {
    val completionVibration = CompletionVibrationSettings(context)
    internal val vibration = AndroidRunVibration(context)
    val privateModel = PrivateModel(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val storage = SettingsStorage(context.settings, BuildConfig.VERSION_CODE)
    private val saveQueue = Channel<Pair<ClickConfig, List<ClickProfile>>>(Channel.CONFLATED)
    private val mutableProfiles = MutableStateFlow<List<ClickProfile>>(emptyList())
    val profiles: StateFlow<List<ClickProfile>> = mutableProfiles
    private val undoHistory = ArrayDeque<ClickConfig>()
    val canUndo = MutableStateFlow(false)
    private val mutableConfig = MutableStateFlow(ClickConfig())
    val config: StateFlow<ClickConfig> = mutableConfig
    val loaded = MutableStateFlow(false)
    val storageWritable = MutableStateFlow(false)
    val storageMessage = MutableStateFlow<String?>(null)
    val saving = MutableStateFlow(false)
    private fun canEdit() = loaded.value && storageWritable.value && runtime.value.status != RunStatus.RUNNING
    private fun queueSave() { if (storageWritable.value) { saving.value = true; saveQueue.trySend(config.value to profiles.value) } }
    val runtime = MutableStateFlow(RuntimeState(message = "请先开启点击服务"))
    val service = MutableStateFlow<ClickAccessibilityService?>(null)
    val overlayVisible = MutableStateFlow(false)
    val settingsVisible = MutableStateFlow(false)
    val screen = MutableStateFlow<ScreenSpec?>(null)
    init {
        scope.launch {
            try {
                val restored = storage.load()
                mutableConfig.value = restored.config
                mutableProfiles.value = restored.profiles
                storageMessage.value = restored.message
                // A failed upgrade transaction must never enable later writes.
                storageWritable.value = restored.writable
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                storageMessage.value = "配置读取或升级备份失败，原数据已保留并暂停编辑保存；请检查存储空间后重开应用"
            } finally { loaded.value = true }
            for ((plan, library) in saveQueue) {
                try {
                    storage.save(plan, library)
                    storageMessage.value = null
                    if (plan == config.value && library == profiles.value) saving.value = false
                } catch (_: Exception) { saving.value = false; storageMessage.value = "配置保存失败，请检查存储空间；当前设置仍可使用" }
            }
        }
    }
    fun edit(change: (ClickConfig) -> ClickConfig): Boolean {
        if (!canEdit()) return false
        val updated = change(config.value)
        if (updated == config.value) return true
        if (updated.structureError() != null) { storageMessage.value = updated.structureError(); return false }
        undoHistory.addLast(config.value)
        if (undoHistory.size > 20) undoHistory.removeFirst()
        canUndo.value = true
        service.value?.planEdited(updated)
        mutableConfig.value = updated
        queueSave()
        return true
    }
    fun undo() {
        if (!canEdit() || undoHistory.isEmpty()) return
        val previous = undoHistory.removeLast()
        service.value?.planEdited(previous); mutableConfig.value = previous
        canUndo.value = undoHistory.isNotEmpty(); queueSave()
    }
    fun saveProfile(name: String, replaceId: String? = null): Boolean {
        if (!canEdit()) return false
        val title = name.trim()
        if (title.isEmpty() || title.length > 40) return false
        if (replaceId == null && profiles.value.size >= 30) { storageMessage.value = "最多保存 30 套方案"; return false }
        val profile = ClickProfile(replaceId ?: java.util.UUID.randomUUID().toString(), title, config.value)
        mutableProfiles.value = if (replaceId == null) profiles.value + profile else profiles.value.map { if (it.id == replaceId) profile else it }
        queueSave(); return true
    }
    fun renameProfile(id: String, name: String) {
        if (!canEdit() || name.trim().isEmpty() || name.trim().length > 40) return
        mutableProfiles.value = profiles.value.map { if (it.id == id) it.copy(name = name.trim()) else it }
        queueSave()
    }
    fun deleteProfile(id: String) {
        if (!canEdit()) return
        mutableProfiles.value = profiles.value.filterNot { it.id == id }
        queueSave()
    }
    fun loadProfile(profile: ClickProfile): Boolean {
        if (!canEdit()) return false
        service.value?.stopRun()
        return edit { profile.config }
    }
    fun importProfiles(raw: String): Int {
        check(storageWritable.value) { "原配置已保护，暂停导入以避免覆盖" }
        check(loaded.value && runtime.value.status != RunStatus.RUNNING) { "请先停止运行" }
        val imported = ProfileCodec.decode(raw)
        require(imported.isNotEmpty()) { "文件中没有方案" }
        require(profiles.value.size + imported.size <= 30) { "导入后超过 30 套，请先删除不需要的方案" }
        mutableProfiles.value = profiles.value + imported.map { it.copy(id = java.util.UUID.randomUUID().toString(), config = it.config.copy(screen = null)) }
        queueSave()
        return imported.size
    }
}

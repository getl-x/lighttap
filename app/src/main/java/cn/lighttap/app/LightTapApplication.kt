package cn.lighttap.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dataStore = context.settings
    private val key = stringPreferencesKey("last_config")
    private val saveQueue = Channel<ClickConfig>(Channel.CONFLATED)
    private val mutableConfig = MutableStateFlow(ClickConfig())
    val config: StateFlow<ClickConfig> = mutableConfig
    val loaded = MutableStateFlow(false)
    val storageMessage = MutableStateFlow<String?>(null)
    val runtime = MutableStateFlow(RuntimeState(message = "请先开启点击服务"))
    val service = MutableStateFlow<ClickAccessibilityService?>(null)
    val overlayVisible = MutableStateFlow(false)
    val settingsVisible = MutableStateFlow(false)
    val screen = MutableStateFlow<ScreenSpec?>(null)
    init {
        scope.launch {
            try {
                val raw = dataStore.data.first()[key]
                if (raw != null) mutableConfig.value = withContext(Dispatchers.Default) { ConfigCodec.decode(raw) }
            } catch (_: Exception) {
                storageMessage.value = "无法读取上次配置，当前使用空白配置；修改后将重新保存"
            } finally { loaded.value = true }
            for (plan in saveQueue) {
                try { dataStore.edit { it[key] = ConfigCodec.encode(plan) }; storageMessage.value = null }
                catch (_: Exception) { storageMessage.value = "配置保存失败，请检查存储空间；当前设置仍可使用" }
            }
        }
    }
    fun edit(change: (ClickConfig) -> ClickConfig): Boolean {
        if (!loaded.value || runtime.value.status == RunStatus.RUNNING) return false
        val updated = change(config.value)
        if (updated == config.value) return true
        service.value?.planEdited(updated)
        mutableConfig.value = updated
        saveQueue.trySend(updated)
        return true
    }
}
package cn.lighttap.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Owns the write gate so UI/controller changes cannot accidentally save defaults
 * after a failed read or a failed backup/migration transaction. */
internal class SettingsStorage(private val store: DataStore<Preferences>, private val appVersion: Int) {
    private var writable = false
    suspend fun load(): SettingsRestore {
        writable = false
        val stored = store.data.first()
        val restored = withContext(Dispatchers.Default) {
            SettingsUpgrade.prepare(SettingsUpgrade.readKeys.mapNotNull { name ->
                stored[stringPreferencesKey(name)]?.let { name to it }
            }.toMap(), appVersion)
        }
        if (restored.updates.isNotEmpty()) store.edit { values ->
            restored.updates.forEach { (name, raw) ->
                val key = stringPreferencesKey(name)
                if (raw == null) values.remove(key) else values[key] = raw
            }
        }
        writable = restored.writable
        return restored
    }
    suspend fun save(config: ClickConfig, profiles: List<ClickProfile>) {
        check(writable) { "原配置已保护，暂停保存以避免覆盖" }
        val rawConfig = ConfigCodec.encode(config)
        val rawProfiles = ProfileCodec.encode(profiles)
        store.edit {
            it[stringPreferencesKey(SettingsUpgrade.CONFIG)] = rawConfig
            it[stringPreferencesKey(SettingsUpgrade.PROFILES)] = rawProfiles
        }
    }
}

/** A proposed atomic upgrade. No caller may write updates until BOTH documents
 * decode. Original JSON survives normalization, including legacy/unknown fields. */
internal data class SettingsRestore(
    val config: ClickConfig,
    val profiles: List<ClickProfile>,
    val writable: Boolean,
    val message: String? = null,
    val updates: Map<String, String?> = emptyMap(),
)

internal object SettingsUpgrade {
    const val CONFIG = "last_config"
    const val PROFILES = "profiles"
    const val APP_VERSION = "settings_app_version"
    const val BACKUP_CONFIG = "upgrade_backup_config"
    const val BACKUP_PROFILES = "upgrade_backup_profiles"
    const val BACKUP_VERSION = "upgrade_backup_version"
    val readKeys = listOf(CONFIG, PROFILES, APP_VERSION)

    fun prepare(stored: Map<String, String>, appVersion: Int): SettingsRestore {
        val config = runCatching { stored[CONFIG]?.let(ConfigCodec::decode) ?: ClickConfig() }
        val profiles = runCatching { stored[PROFILES]?.let(ProfileCodec::decode) ?: emptyList() }
        val previousVersion = stored[APP_VERSION]?.toIntOrNull()
        val versionInvalid = APP_VERSION in stored && (previousVersion == null || previousVersion < 1 || previousVersion > appVersion)
        if (config.isFailure || profiles.isFailure || versionInvalid) {
            return SettingsRestore(config.getOrDefault(ClickConfig()), profiles.getOrDefault(emptyList()), false,
                "旧配置或方案暂不能兼容，原数据已保留并暂停编辑保存；请使用兼容版本处理")
        }
        val restored = SettingsRestore(config.getOrThrow(), profiles.getOrThrow(), true)
        if (previousVersion == appVersion) return restored
        return restored.copy(updates = linkedMapOf(
            // The backup and migrated documents must share ONE DataStore edit.
            BACKUP_CONFIG to stored[CONFIG], BACKUP_PROFILES to stored[PROFILES],
            BACKUP_VERSION to stored[APP_VERSION].orEmpty(),
            CONFIG to ConfigCodec.encode(restored.config), PROFILES to ProfileCodec.encode(restored.profiles),
            APP_VERSION to appVersion.toString(),
        ))
    }
}

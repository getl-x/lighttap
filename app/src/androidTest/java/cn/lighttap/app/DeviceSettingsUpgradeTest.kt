package cn.lighttap.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated file only; does not alter the user's real settings or run a task. */
@RunWith(AndroidJUnit4::class)
class DeviceSettingsUpgradeTest {
    @Test fun legacyUpgradeSurvivesStoreReopeningAndFurtherSaves() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.cacheDir, "upgrade-test-${System.nanoTime()}").apply { check(mkdirs()) }
        val file = directory.resolve("settings.preferences_pb")
        val configKey = stringPreferencesKey(SettingsUpgrade.CONFIG)
        val profilesKey = stringPreferencesKey(SettingsUpgrade.PROFILES)
        val legacy = """{"version":1,"mode":"SINGLE","groupIntervalMs":100,"stopMode":"MANUAL","roundLimit":10,"durationMs":60000,"points":[{"id":1,"x":500,"y":1000,"intervalMs":100}]}"""
        val library = """{"format":"lighttap-profiles","version":1,"profiles":[{"id":"keep-id","name":"旧方案","config":$legacy}]}"""
        suspend fun <T> withStore(block: suspend (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> T): T {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try { return block(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })) }
            finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
        }
        try {
            withStore { store ->
                store.edit { it[configKey] = legacy; it[profilesKey] = library }
                val storage = SettingsStorage(store, BuildConfig.VERSION_CODE)
                val restored = storage.load()
                assertTrue(restored.writable)
                storage.save(restored.config.copy(volumeKeys = true), restored.profiles)
            }
            withStore { store ->
                val restored = SettingsStorage(store, BuildConfig.VERSION_CODE).load()
                assertTrue(restored.updates.isEmpty())
                assertTrue(restored.config.volumeKeys)
                assertEquals("keep-id", restored.profiles.single().id)
                val saved = store.data.first()
                assertEquals(legacy, saved[stringPreferencesKey(SettingsUpgrade.BACKUP_CONFIG)])
                assertEquals(library, saved[stringPreferencesKey(SettingsUpgrade.BACKUP_PROFILES)])
            }
        } finally {
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}

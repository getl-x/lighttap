package cn.lighttap.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SettingsStorageTest {
    private val legacy = """{"version":1,"mode":"SINGLE","groupIntervalMs":100,"stopMode":"MANUAL","roundLimit":10,"durationMs":60000,"points":[{"id":1,"x":500,"y":1000,"intervalMs":100}]}"""
    private val library = """{"format":"lighttap-profiles","version":1,"profiles":[{"id":"keep-id","name":"旧方案","config":$legacy}]}"""
    private val configKey = stringPreferencesKey(SettingsUpgrade.CONFIG)
    private val profilesKey = stringPreferencesKey(SettingsUpgrade.PROFILES)

    @Test fun atomicUpgradeAndSubsequentSavePreserveOriginalSnapshotAndUnrelatedKeys() = runBlocking {
        val unrelated = stringPreferencesKey("unrelated")
        val contents = MutableStateFlow(preferencesOf(configKey to legacy, profilesKey to library, unrelated to "keep"))
        var transactions = 0
        val store = object : DataStore<Preferences> {
            override val data = contents.asStateFlow()
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                transactions++
                val updated = transform(contents.value)
                contents.value = updated
                return updated
            }
        }
        val storage = SettingsStorage(store, 18)
        val restored = storage.load()
        assertTrue(restored.writable)
        assertEquals(1, transactions)
        val changed = restored.config.copy(volumeKeys = true)
        storage.save(changed, restored.profiles)
        val saved = store.data.first()
        assertEquals(changed, ConfigCodec.decode(saved[configKey]!!))
        assertEquals("keep-id", ProfileCodec.decode(saved[profilesKey]!!).single().id)
        assertEquals(legacy, saved[stringPreferencesKey(SettingsUpgrade.BACKUP_CONFIG)])
        assertEquals(library, saved[stringPreferencesKey(SettingsUpgrade.BACKUP_PROFILES)])
        assertEquals("keep", saved[unrelated])
        assertTrue(SettingsStorage(store, 18).load().updates.isEmpty())
        assertEquals(2, transactions)
    }
    @Test fun unreadableLibraryPreventsAllWritesEvenWhenCurrentConfigurationLoaded() = runBlocking {
        val original = preferencesOf(configKey to legacy, profilesKey to "{broken}")
        var writes = 0
        val store = object : DataStore<Preferences> {
            override val data = flowOf(original)
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                writes++; return transform(original)
            }
        }
        val storage = SettingsStorage(store, 18)
        assertFalse(storage.load().writable)
        try { storage.save(ClickConfig(), emptyList()); fail("Protected storage accepted defaults") } catch (_: IllegalStateException) { }
        assertEquals(0, writes)
        assertEquals("{broken}", store.data.first()[profilesKey])
    }
    @Test fun failedAtomicUpgradeDoesNotEnableSubsequentSaves() = runBlocking {
        val original = preferencesOf(configKey to legacy, profilesKey to library)
        var transactions = 0
        val store = object : DataStore<Preferences> {
            override val data = flowOf(original)
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                transactions++
                val proposed = transform(original)
                // Both backups exist in the very same proposed transaction.
                assertEquals(legacy, proposed[stringPreferencesKey(SettingsUpgrade.BACKUP_CONFIG)])
                assertEquals(library, proposed[stringPreferencesKey(SettingsUpgrade.BACKUP_PROFILES)])
                throw IOException("Controlled write failure")
            }
        }
        val storage = SettingsStorage(store, 18)
        try { storage.load(); fail("Expected failed transaction") } catch (_: IOException) { }
        try { storage.save(ClickConfig(), emptyList()); fail("Save followed failed upgrade") } catch (_: IllegalStateException) { }
        assertEquals(1, transactions)
        assertEquals(original, store.data.first())
    }
}

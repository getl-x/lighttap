package cn.lighttap.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsUpgradeTest {
    private val legacy = """{"version":1,"mode":"SINGLE","groupIntervalMs":100,"stopMode":"MANUAL","roundLimit":10,"durationMs":60000,"legacyNote":"keep original","points":[{"id":1,"x":500,"y":1000,"intervalMs":100}]}"""
    private val legacyLibrary = """{"format":"lighttap-profiles","version":1,"profiles":[{"id":"saved-id","name":"原有方案","config":$legacy}]}"""
    private val saved = mapOf(SettingsUpgrade.CONFIG to legacy, SettingsUpgrade.PROFILES to legacyLibrary)
    private fun apply(stored: Map<String, String>, upgrade: SettingsRestore) = stored.toMutableMap().apply {
        upgrade.updates.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
    }

    @Test fun oldestSupportedDataMigratesWithVerbatimBackupsAndStableProfileIds() {
        val upgrade = SettingsUpgrade.prepare(saved, 18)
        assertTrue(upgrade.writable)
        val migrated = apply(saved, upgrade)
        assertEquals(legacy, migrated[SettingsUpgrade.BACKUP_CONFIG])
        assertEquals(legacyLibrary, migrated[SettingsUpgrade.BACKUP_PROFILES])
        assertEquals(2, JSONObject(migrated.getValue(SettingsUpgrade.CONFIG)).getInt("version"))
        val restored = SettingsUpgrade.prepare(migrated, 18)
        assertEquals(ClickPoint(1, 500f, 1000f), restored.config.points.single())
        assertEquals("saved-id", restored.profiles.single().id)
        assertEquals("原有方案", restored.profiles.single().name)
        assertEquals(restored.config, restored.profiles.single().config)
        assertTrue(restored.updates.isEmpty())
    }
    @Test fun currentSettingsSurviveNextUpgradeAndBackupIsNotRepeatedOnEveryLaunch() {
        val config = ClickConfig(points = listOf(ClickPoint(7, 100f, 200f, label = "保留", repeatCount = 3)),
            startDelayMs = 2000, keepScreenOn = true, volumeKeys = true, overlayOpacity = 60)
        val current = mapOf(SettingsUpgrade.CONFIG to ConfigCodec.encode(config),
            SettingsUpgrade.PROFILES to ProfileCodec.encode(listOf(ClickProfile("original", "原方案", config))), SettingsUpgrade.APP_VERSION to "17")
        val upgraded = apply(current, SettingsUpgrade.prepare(current, 18))
        assertEquals(config, ConfigCodec.decode(upgraded.getValue(SettingsUpgrade.CONFIG)))
        assertEquals("17", upgraded[SettingsUpgrade.BACKUP_VERSION])
        val edited = upgraded + (SettingsUpgrade.CONFIG to ConfigCodec.encode(config.copy(volumeKeys = false)))
        assertTrue(SettingsUpgrade.prepare(edited, 18).updates.isEmpty())
        assertEquals(current[SettingsUpgrade.CONFIG], edited[SettingsUpgrade.BACKUP_CONFIG])
    }
    @Test fun failedLibraryReadCannotReplaceOriginalWithAnEmptyLibrary() {
        val damaged = saved + (SettingsUpgrade.PROFILES to "{broken}")
        val restore = SettingsUpgrade.prepare(damaged, 18)
        assertFalse(restore.writable)
        assertFalse(restore.config.points.isEmpty())
        assertNotNull(restore.message)
        assertEquals(damaged, apply(damaged, restore))
    }
    @Test fun failedConfigReadKeepsHealthyProfilesAndEveryOriginalByte() {
        val damaged = saved + (SettingsUpgrade.CONFIG to "{broken}")
        val restore = SettingsUpgrade.prepare(damaged, 18)
        assertFalse(restore.writable)
        assertEquals("saved-id", restore.profiles.single().id)
        assertEquals(damaged, apply(damaged, restore))
    }
    @Test fun unknownSchemaOrAppDowngradeCannotOverwriteNewerData() {
        val futureConfig = saved + (SettingsUpgrade.CONFIG to legacy.replace("\"version\":1", "\"version\":999"))
        val futureLibrary = saved + (SettingsUpgrade.PROFILES to legacyLibrary.replaceFirst("\"version\":1", "\"version\":999"))
        for (data in listOf(futureConfig, futureLibrary, saved + (SettingsUpgrade.APP_VERSION to "19"), saved + (SettingsUpgrade.APP_VERSION to "invalid"))) {
            val restore = SettingsUpgrade.prepare(data, 18)
            assertFalse(restore.writable)
            assertEquals(data, apply(data, restore))
        }
    }
    @Test fun firstInstallDoesNotInventUserDataOrOldBackups() {
        val restore = SettingsUpgrade.prepare(emptyMap(), 18)
        assertTrue(restore.writable)
        assertEquals(ClickConfig(), restore.config)
        assertTrue(restore.profiles.isEmpty())
        val stored = apply(emptyMap(), restore)
        assertFalse(SettingsUpgrade.BACKUP_CONFIG in stored)
        assertFalse(SettingsUpgrade.BACKUP_PROFILES in stored)
        assertTrue(SettingsUpgrade.prepare(stored, 18).updates.isEmpty())
    }
}

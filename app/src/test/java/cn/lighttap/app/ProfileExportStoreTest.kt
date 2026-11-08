package cn.lighttap.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileExportStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun recoveredReferenceExportsOriginalSnapshotAfterStoreRecreation() {
        val directory = temporary.newFolder("exports")
        val profiles = listOf(ClickProfile("current", "旋转前的方案", ClickConfig()))
        val id = ProfileExportStore(directory).prepare(profiles)
        val bytes = ProfileExportStore(directory).read(id)
        assertTrue(bytes.isNotEmpty())
        assertEquals(ProfileCodec.encode(profiles), bytes.toString(Charsets.UTF_8))
        assertEquals("旋转前的方案", ProfileCodec.decode(bytes.toString(Charsets.UTF_8)).single().name)
    }
    @Test fun missingEmptyAndCorruptSnapshotsCannotBeReadAsSuccessfulExports() {
        val directory = temporary.newFolder("exports")
        val store = ProfileExportStore(directory)
        val id = store.prepare(listOf(ClickProfile("current", "当前", ClickConfig())))
        for (data in listOf("", "not json")) {
            File(directory, id).writeText(data)
            assertThrows(Exception::class.java) { store.read(id) }
        }
        store.discard(id)
        assertThrows(Exception::class.java) { store.read(id) }
        assertThrows(IllegalArgumentException::class.java) { store.read("../outside") }
    }
}

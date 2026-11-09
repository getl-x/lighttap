package cn.lighttap.app

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class VisitDebugLogTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun environment() = JSONObject().put("appVersion", "fixture")

    @Test fun rotationBoundsDiskAndRestoresEventsAcrossLoggerRecreation() = runTest {
        val dir = temporary.newFolder("log")
        val logger = VisitDebugLog(dir, backgroundScope, { 123L }, { 456L }, maxFileBytes = 640)
        repeat(80) { logger.record(VisitDebugEvent(VisitDebugKind.CAPTURE, nodes = it, durationMs = 20)) }
        val first = JSONObject(logger.snapshot(environment()).toString(Charsets.UTF_8))
        val events = first.getJSONArray("events")
        assertEquals(0L, first.getLong("droppedThisProcess"))
        assertTrue(events.length() in 2..8)
        assertEquals(80L, events.getJSONObject(events.length() - 1).getLong("seq"))
        assertTrue(dir.listFiles()!!.all { it.length() <= 640 })
        val restored = VisitDebugLog(dir, backgroundScope)
        val second = JSONObject(restored.snapshot(environment()).toString(Charsets.UTF_8))
        assertEquals(events.toString(), second.getJSONArray("events").toString())
    }

    @Test fun unknownExceptionTextAndBusinessValuesNeverReachExport() = runTest {
        val logger = VisitDebugLog(temporary.newFolder("privacy"), backgroundScope)
        logger.record(VisitDebugEvent(VisitDebugKind.ERROR, reason = "private-person private-address token=private-token"))
        logger.record(VisitDebugEvent(VisitDebugKind.ERROR, reason = "无法确认当前选中待办页签"))
        val raw = logger.snapshot(environment()).toString(Charsets.UTF_8)
        assertFalse(raw.contains("private-person")); assertFalse(raw.contains("private-address")); assertFalse(raw.contains("private-token"))
        assertTrue(raw.contains("未分类原因（原文未记录）")); assertTrue(raw.contains("无法确认当前选中待办页签"))
        assertEquals("未分类原因（原文未记录）", safeVisitDebugReason("字段标签缺失或不唯一：private-name"))
        assertEquals("提交后画面暂不可读取（系统码 3）", safeVisitDebugReason("提交后画面暂不可读取（系统码 3）"))
    }

    @Test fun partialLastLineIsSkippedAndStorageFailureDoesNotThrowIntoRunner() = runTest {
        val dir = temporary.newFolder("partial")
        val logger = VisitDebugLog(dir, backgroundScope)
        logger.record(VisitDebugEvent(VisitDebugKind.STOP))
        logger.snapshot(environment()) // barrier before simulating interrupted last write
        File(dir, "current.jsonl").appendText("{incomplete")
        logger.record(VisitDebugEvent(VisitDebugKind.RESUME))
        val recovered = JSONObject(logger.snapshot(environment()).toString(Charsets.UTF_8))
        assertEquals(1, recovered.getInt("unreadableLines"))
        assertEquals(2, recovered.getJSONArray("events").length())
        val notDirectory = temporary.newFile("unwritable")
        val failed = VisitDebugLog(notDirectory, backgroundScope)
        failed.record(VisitDebugEvent(VisitDebugKind.ERROR))
        val exported = JSONObject(failed.snapshot(environment()).toString(Charsets.UTF_8))
        assertEquals(1L, exported.getLong("droppedThisProcess"))
        assertNotNull(failed.storageError.value)
        assertEquals(0, exported.getJSONArray("events").length())
    }

    @Test fun preparedExportKeepsOriginalBytesAfterStoreRecreationAndNewEvents() = runTest {
        val logger = VisitDebugLog(temporary.newFolder("source"), backgroundScope)
        val snapshots = temporary.newFolder("exports")
        logger.record(VisitDebugEvent(VisitDebugKind.START))
        val original = logger.snapshot(environment())
        val id = VisitDebugExportStore(snapshots).prepare(original)
        logger.record(VisitDebugEvent(VisitDebugKind.ERROR, reason = "顶部记录变化，禁止点击"))
        logger.snapshot(environment())
        val restored = VisitDebugExportStore(snapshots)
        assertArrayEquals(original, restored.read(id))
        for (content in listOf("", "not-json", "{}")) {
            File(snapshots, id).writeText(content)
            assertThrows(Exception::class.java) { restored.read(id) }
        }
        assertThrows(IllegalArgumentException::class.java) { restored.read("../outside") }
        restored.discard(id)
        assertThrows(Exception::class.java) { restored.read(id) }
    }
}

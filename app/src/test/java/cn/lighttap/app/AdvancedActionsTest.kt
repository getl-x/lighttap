package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedActionsTest {
    private val point = ClickPoint(1, 500f, 1000f)
    private val screen = ScreenSpec(1080, 2400, 440, 0)
    @Test fun allActionsAndOptionsRoundTrip() {
        val plan = ClickConfig(mode = ClickMode.SEQUENCE, points = ActionType.entries.mapIndexed { i, t -> point.copy(id = i.toLong(), type = t, durationMs = 800, endX = 800f, endY = 1800f, repeatCount = 3, label = "动作$i", enabled = i % 2 == 0) }, screen = screen, startDelayMs = 3000, roundDelayMs = 5000, keepScreenOn = true, volumeKeys = true, overlayOpacity = 65, markerSizeDp = 60)
        assertEquals(plan, ConfigCodec.decode(ConfigCodec.encode(plan)))
        val profiles = listOf(ClickProfile("one", "日常", plan), ClickProfile("two", "测试", ClickConfig()))
        assertEquals(profiles, ProfileCodec.decode(ProfileCodec.encode(profiles)))
    }
    @Test fun legacyConfigurationLoadsAsOrdinaryTap() {
        val raw = """{"version":1,"mode":"SINGLE","groupIntervalMs":100,"stopMode":"MANUAL","roundLimit":10,"durationMs":60000,"points":[{"id":1,"x":500,"y":1000,"intervalMs":100}]}"""
        val loaded = ConfigCodec.decode(raw)
        assertEquals(point, loaded.points.single()); assertEquals(0L, loaded.startDelayMs); assertFalse(loaded.volumeKeys)
    }
    @Test fun invalidActionsAndUntrustedFilesAreRejected() {
        for (p in listOf(point.copy(durationMs = 0), point.copy(repeatCount = 0), point.copy(endX = Float.NaN), point.copy(label = "a".repeat(41)))) assertNotNull(ClickConfig(points = listOf(p)).structureError())
        val encoded = ProfileCodec.encode(listOf(ClickProfile("a", "测试", ClickConfig())))
        assertThrows(Exception::class.java) { ProfileCodec.decode(encoded.replace("lighttap-profiles", "other")) }
        assertThrows(Exception::class.java) { ProfileCodec.decode("x".repeat(2_000_001)) }
        assertThrows(Exception::class.java) { ProfileCodec.decode(encoded.replace("\"profiles\":[", "\"profiles\":[null,")) }
    }
    @Test fun swipeCrossingPanelAndInvalidEndpointAreRejected() {
        val swipe = point.copy(type = ActionType.SWIPE, x = 100f, y = 1000f, endX = 1000f, endY = 1000f)
        assertNotNull(ClickConfig(points = listOf(swipe), screen = screen).validationError(screen, 10, PanelBounds(400, 900, 600, 1100)))
        assertNotNull(ClickConfig(points = listOf(swipe.copy(endX = 1080f)), screen = screen).validationError(screen, 10, null))
        assertNull(ClickConfig(points = listOf(swipe), screen = screen).validationError(screen, 10, PanelBounds(400, 100, 600, 300)))
    }
    @Test fun disabledActionsAreSkippedAndSimultaneousRulesAreExplicit() {
        val plan = ClickConfig(points = listOf(point.copy(enabled = false), point.copy(id = 2)), screen = screen)
        assertEquals(listOf(2L), plan.activePoints().map { it.id })
        assertNotNull(plan.copy(points = plan.points.map { it.copy(enabled = false) }).validationError(screen, 10, null))
        assertNotNull(plan.copy(mode = ClickMode.SIMULTANEOUS, points = listOf(point.copy(type = ActionType.DOUBLE_TAP))).validationError(screen, 10, null))
    }
    @Test fun repeatsAndWaitActionsCountOnlyCompleteRounds() = runTest {
        val calls = mutableListOf<Long>()
        val engine = ClickEngine(backgroundScope, TapDispatcher { p -> calls += p.single().id; delay(50); true }, { testScheduler.currentTime })
        engine.start(ClickConfig(mode = ClickMode.SEQUENCE, points = listOf(point.copy(repeatCount = 3), point.copy(id = 2, type = ActionType.WAIT, durationMs = 1000), point.copy(id = 3, enabled = false)), stopMode = StopMode.ROUNDS, roundLimit = 1))
        advanceTimeBy(500); runCurrent(); assertEquals(listOf(1L, 1L, 1L), calls); assertEquals(0L, engine.state.value.rounds)
        advanceTimeBy(1000); runCurrent(); assertEquals(1L, engine.state.value.rounds); assertEquals(RunStatus.IDLE, engine.state.value.status)
    }
    @Test fun startupAndRoundWaitFreezeOnPauseAndFinalRoundHasNoWait() = runTest {
        val times = mutableListOf<Long>()
        val engine = ClickEngine(backgroundScope, TapDispatcher { times += testScheduler.currentTime; delay(50); true }, { testScheduler.currentTime })
        engine.start(ClickConfig(points = listOf(point), startDelayMs = 1000, roundDelayMs = 2000, stopMode = StopMode.ROUNDS, roundLimit = 2))
        advanceTimeBy(400); runCurrent(); engine.pause(); val remaining = engine.state.value.waitRemainingMs
        advanceTimeBy(10_000); runCurrent(); assertTrue(times.isEmpty()); assertEquals(remaining, engine.state.value.waitRemainingMs)
        engine.resume(); advanceTimeBy(700); runCurrent(); assertEquals(listOf(11_000L), times)
        engine.pause(); advanceTimeBy(1000); runCurrent(); engine.resume(); advanceTimeBy(2200); runCurrent()
        assertEquals(listOf(11_000L, 14_150L), times); assertEquals(RunStatus.IDLE, engine.state.value.status); assertEquals(2L, engine.state.value.rounds)
    }
    @Test fun stopDuringCountdownNeverDispatchesAndRestartStartsFresh() = runTest {
        var count = 0
        val engine = ClickEngine(backgroundScope, TapDispatcher { count++; true }, { testScheduler.currentTime })
        val plan = ClickConfig(points = listOf(point), startDelayMs = 1000, stopMode = StopMode.ROUNDS, roundLimit = 1)
        engine.start(plan); advanceTimeBy(500); runCurrent(); engine.stop(); advanceTimeBy(5000); runCurrent(); assertEquals(0, count)
        engine.start(plan); advanceTimeBy(999); runCurrent(); assertEquals(0, count)
        advanceTimeBy(2); runCurrent(); assertEquals(1, count)
    }
}

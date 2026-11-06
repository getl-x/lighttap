package cn.lighttap.app

import org.junit.Assert.*
import org.junit.Test

class ConfigTest {
    private val screen = ScreenSpec(1080, 2400, 440, 0)
    private val config = ClickConfig(points = listOf(ClickPoint(1, 500f, 1000f)), screen = screen)
    @Test fun configurationRoundTripPreservesAllFields() {
        val plan = config.copy(mode = ClickMode.SIMULTANEOUS, points = config.points + ClickPoint(2, 700f, 1500f, 450), stopMode = StopMode.DURATION, durationMs = 12_000, groupIntervalMs = 80)
        assertEquals(plan, ConfigCodec.decode(ConfigCodec.encode(plan)))
    }
    @Test fun corruptConfigurationIsRejected() { assertThrows(Exception::class.java) { ConfigCodec.decode("{broken}") } }
    @Test fun emptyAndOutOfBoundsPointsAreRejected() {
        assertNotNull(config.copy(points = emptyList()).validationError(screen, 10, null))
        assertNotNull(config.copy(points = listOf(ClickPoint(1, 1080f, 20f))).validationError(screen, 10, null))
        assertNotNull(config.copy(points = listOf(ClickPoint(1, Float.NaN, 20f))).validationError(screen, 10, null))
    }
    @Test fun displayChangesRequireConfirmation() {
        assertNotNull(config.validationError(screen.copy(rotation = 1), 10, null))
        assertNotNull(config.validationError(screen.copy(densityDpi = 400), 10, null))
        assertNull(config.validationError(screen, 10, null))
    }
    @Test fun panelOverlapAndStrokeLimitsAreRejected() {
        assertNotNull(config.validationError(screen, 10, PanelBounds(400, 900, 600, 1100)))
        val plan = config.copy(mode = ClickMode.SIMULTANEOUS, points = config.points + ClickPoint(2, 600f, 1000f))
        assertNotNull(plan.validationError(screen, 1, null))
    }
    @Test fun pointLimitAndInvalidIntervalsAreRejected() {
        assertNotNull(config.copy(points = (1..101).map { ClickPoint(it.toLong(), 500f, 1000f) }).validationError(screen, 20, null))
        assertNotNull(config.copy(groupIntervalMs = 9).validationError(screen, 10, null))
        assertNotNull(config.copy(points = listOf(ClickPoint(1, 500f, 1000f, 60_001))).validationError(screen, 10, null))
    }
}
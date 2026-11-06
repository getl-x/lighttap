package cn.lighttap.app

import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class DeviceActionsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val model get() = context.appModel
    private fun waitUntil(message: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }
    private fun main(action: () -> Unit) = inst.runOnMainSync(action)
    private fun prepare(): Pair<Activity, View> {
        val automation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        if (model.service.value == null) {
            // Instrumentation restarts the target process. Refresh only the service
            // that the user already enabled, preserving every other enabled service.
            fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText().trim() }
            val enabled = shell("settings get secure enabled_accessibility_services")
            require(enabled.matches(Regex("[A-Za-z0-9_.$/:]+")))
            val entries = enabled.split(':')
            require(entries.any { it.startsWith("cn.lighttap.app/") }) { "User must enable the service first" }
            val others = entries.filterNot { it.startsWith("cn.lighttap.app/") }.joinToString(":")
            try {
                shell(if (others.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $others")
                SystemClock.sleep(200)
            } finally { shell("settings put secure enabled_accessibility_services $enabled") }
            assertEquals(enabled.split(':').toSet(), shell("settings get secure enabled_accessibility_services").split(':').toSet())
        }
        waitUntil("Service must be enabled") { model.service.value != null && model.loaded.value }
        automation.executeShellCommand("am start -n cn.lighttap.app/.MainActivity").use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText() }
        inst.waitForIdleSync(); SystemClock.sleep(500)
        val activity = inst.startActivitySync(Intent(context, TouchTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        inst.waitForIdleSync(); SystemClock.sleep(300)
        fun find(v: View): View? {
            if (v.contentDescription?.toString() == "多点触摸测试区域") return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        var canvas: View? = null
        main { canvas = find(activity.window.decorView) }
        return activity to (canvas ?: error("Touch test canvas not found"))
    }
    @Test fun realActionsProduceExpectedTouchesAndTimings() {
        val (activity, canvas) = prepare()
        val original = model.config.value
        data class Sample(val action: Int, val time: Long, val x: Float, val y: Float)
        val samples = Collections.synchronizedList(mutableListOf<Sample>())
        val pos = IntArray(2)
        main { canvas.getLocationOnScreen(pos); canvas.setOnTouchListener { _, e -> samples += Sample(e.actionMasked, e.eventTime, e.rawX, e.rawY); false } }
        val x = pos[0] + canvas.width * .3f; val y = pos[1] + canvas.height * .55f
        val endX = pos[0] + canvas.width * .75f; val endY = y + 100
        try {
            main {
                model.service.value!!.stopRun()
                assertTrue(model.edit { ClickConfig(mode = ClickMode.SEQUENCE, points = listOf(
                    ClickPoint(1, x, y, repeatCount = 2),
                    ClickPoint(2, x, y, type = ActionType.DOUBLE_TAP),
                    ClickPoint(3, x, y, type = ActionType.LONG_PRESS, durationMs = 800),
                    ClickPoint(4, x, y, type = ActionType.SWIPE, durationMs = 400, endX = endX, endY = endY),
                    ClickPoint(5, x, y, type = ActionType.WAIT, durationMs = 500),
                    ClickPoint(6, x, y, enabled = false)), stopMode = StopMode.ROUNDS, roundLimit = 1, screen = model.screen.value, startDelayMs = 300, keepScreenOn = true) })
                model.service.value!!.showOverlay()
            }
            inst.waitForIdleSync(); SystemClock.sleep(300)
            main { model.service.value!!.toggleRun() }
            SystemClock.sleep(150); assertTrue(samples.isEmpty())
            waitUntil("Sequence must finish exactly one round") { model.runtime.value.status == RunStatus.IDLE && model.runtime.value.rounds == 1L }
            val all = synchronized(samples) { samples.toList() }
            val downs = all.filter { it.action == MotionEvent.ACTION_DOWN }; val ups = all.filter { it.action == MotionEvent.ACTION_UP }
            assertEquals("2 taps + double + long + swipe", 6, downs.size); assertEquals(6, ups.size)
            assertTrue("Long press must hold >= 700 ms", ups[4].time - downs[4].time >= 700)
            assertTrue("Double tap spacing", downs[3].time - ups[2].time in 70..200)
            assertTrue("Swipe must produce movement", all.any { it.action == MotionEvent.ACTION_MOVE })
            assertEquals(endX, ups.last().x, 12f); assertEquals(endY, ups.last().y, 12f)
            SystemClock.sleep(500); assertEquals(6, samples.count { it.action == MotionEvent.ACTION_DOWN })
        } finally {
            main { model.service.value?.hideOverlay(); model.edit { original }; canvas.setOnTouchListener(null); activity.finish() }
        }
    }
    @Test fun keyHandlerPausesResumesAndStopsWithoutAnotherGesture() {
        val (activity, canvas) = prepare(); val original = model.config.value
        val samples = Collections.synchronizedList(mutableListOf<Int>())
        val pos = IntArray(2); main { canvas.getLocationOnScreen(pos); canvas.setOnTouchListener { _, e -> samples += e.actionMasked; false } }
        try {
            main {
                model.service.value!!.stopRun()
                model.edit { ClickConfig(points = listOf(ClickPoint(1, pos[0] + canvas.width * .5f, pos[1] + canvas.height * .7f, 300)), screen = model.screen.value, volumeKeys = true, startDelayMs = 1000) }
                model.service.value!!.showOverlay()
            }
            inst.waitForIdleSync(); SystemClock.sleep(500)
            fun key(code: Int) {
                val time = SystemClock.uptimeMillis()
                // Injected events bypass Android's hardware input filter. Exercise
                // the actual callback here; physical-key delivery is checked separately.
                main {
                    val callback = ClickAccessibilityService::class.java.getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
                    assertEquals(true, callback.invoke(model.service.value!!, KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0)))
                    assertEquals(true, callback.invoke(model.service.value!!, KeyEvent(time, time + 30, KeyEvent.ACTION_UP, code, 0)))
                }
                SystemClock.sleep(100)
            }
            key(KeyEvent.KEYCODE_VOLUME_UP); waitUntil("Volume up must start") { model.runtime.value.status == RunStatus.RUNNING }
            key(KeyEvent.KEYCODE_VOLUME_UP); waitUntil("Volume up must pause") { model.runtime.value.status == RunStatus.PAUSED }
            SystemClock.sleep(1200); assertTrue(samples.isEmpty())
            key(KeyEvent.KEYCODE_VOLUME_UP); waitUntil("Volume up must resume") { model.runtime.value.status == RunStatus.RUNNING }
            waitUntil("Resumed task must click") { samples.contains(MotionEvent.ACTION_DOWN) }
            key(KeyEvent.KEYCODE_VOLUME_DOWN); waitUntil("Volume down must stop") { model.runtime.value.status == RunStatus.IDLE }
            val count = samples.count { it == MotionEvent.ACTION_DOWN }; SystemClock.sleep(1000)
            assertEquals(count, samples.count { it == MotionEvent.ACTION_DOWN }); assertEquals(0L, model.runtime.value.rounds)
        } finally { main { model.service.value?.hideOverlay(); model.edit { original }; canvas.setOnTouchListener(null); activity.finish() } }
    }
    @Test fun profilesImportUndoAndStorageRestoreWorkOnDevice() {
        inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        waitUntil("Configuration must load") { model.loaded.value }
        val original = model.config.value
        val originalIds = model.profiles.value.map { it.id }.toSet()
        try {
            main {
                model.service.value?.stopRun()
                assertTrue(model.edit { it.copy(startDelayMs = 3000, points = listOf(ClickPoint(1, 400f, 1000f, type = ActionType.SWIPE, endX = 600f, endY = 1200f))) })
                assertTrue(model.saveProfile("设备验证"))
                val profile = model.profiles.value.last()
                model.edit { it.copy(startDelayMs = 6000) }; model.undo(); assertEquals(3000L, model.config.value.startDelayMs)
                model.edit { it.copy(startDelayMs = 9000) }; assertTrue(model.loadProfile(profile)); assertEquals(3000L, model.config.value.startDelayMs)
                assertEquals(1, model.importProfiles(ProfileCodec.encode(listOf(profile))))
                assertNull(model.profiles.value.last().config.screen)
            }
            waitUntil("Changes must finish saving") { !model.saving.value && model.storageMessage.value == null }
            var reloaded: AppModel? = null
            main { reloaded = AppModel(context) }
            waitUntil("Persisted library must be restored") { reloaded!!.loaded.value && reloaded!!.profiles.value.count { it.name == "设备验证" } == 2 }
            assertEquals(3000L, reloaded!!.config.value.startDelayMs)
        } finally {
            main { model.profiles.value.filter { it.id !in originalIds }.forEach { model.deleteProfile(it.id) }; model.edit { original } }
            SystemClock.sleep(300)
        }
    }
    @Test fun simultaneousTwoFingerInputStillWorks() {
        val (activity, canvas) = prepare(); val original = model.config.value
        var maximum = 0; var groups = 0
        val pos = IntArray(2)
        main { canvas.getLocationOnScreen(pos); canvas.setOnTouchListener { _, e -> maximum = maxOf(maximum, e.pointerCount); if (e.actionMasked == MotionEvent.ACTION_DOWN) groups++; false } }
        try {
            main {
                model.service.value!!.stopRun()
                model.edit { ClickConfig(mode = ClickMode.SIMULTANEOUS, points = listOf(ClickPoint(1, pos[0] + canvas.width * .3f, pos[1] + canvas.height * .7f), ClickPoint(2, pos[0] + canvas.width * .7f, pos[1] + canvas.height * .7f)), screen = model.screen.value, stopMode = StopMode.ROUNDS, roundLimit = 2) }
                model.service.value!!.showOverlay()
            }
            inst.waitForIdleSync(); SystemClock.sleep(200)
            main { model.service.value!!.toggleRun() }
            waitUntil("Two simultaneous rounds must complete") { model.runtime.value.status == RunStatus.IDLE && model.runtime.value.rounds == 2L }
            inst.waitForIdleSync(); SystemClock.sleep(200)
            main { assertEquals("Maximum simultaneous pointers", 2, maximum); assertEquals("Delivered gesture groups", 2, groups) }
        } finally { main { model.service.value?.hideOverlay(); model.edit { original }; canvas.setOnTouchListener(null); activity.finish() } }
    }
}

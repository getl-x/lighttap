package cn.lighttap.app

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceLifecycleTest {
    @Test fun privateRouteSurvivesBackgroundAndRecreationButNotNewTask() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = inst.targetContext
        val model = context.appModel
        val activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        inst.waitForIdleSync()
        assertFalse(model.privateModel.session.unlocked.value)
        inst.runOnMainSync { model.privateModel.session.unlock() }
        inst.waitForIdleSync(); SystemClock.sleep(400)
        assertTrue(model.privateModel.session.unlocked.value)
        inst.runOnMainSync { activity.recreate() }
        inst.waitForIdleSync(); SystemClock.sleep(500)
        assertTrue(model.privateModel.session.unlocked.value)
        var resumed: Activity? = null
        inst.runOnMainSync { resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is MainActivity } }
        val recreated = resumed ?: throw AssertionError("Activity recreation did not resume")
        inst.runOnMainSync { recreated.moveTaskToBack(true) }
        SystemClock.sleep(300)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        inst.waitForIdleSync(); SystemClock.sleep(400)
        assertTrue("Background return must preserve the route", model.privateModel.session.unlocked.value)
        inst.runOnMainSync { resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is MainActivity }; resumed?.finishAndRemoveTask() }
        SystemClock.sleep(500)
        val fresh = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        inst.waitForIdleSync()
        assertFalse("New task must return to normal home", model.privateModel.session.unlocked.value)
        inst.runOnMainSync { fresh.finishAndRemoveTask() }
    }
    @Test fun waitSettingPersistsWithoutAlteringOrdinaryClickConfiguration() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val model = context.appModel
        val original = model.privateModel.settings.value
        val ordinary = ConfigCodec.encode(model.config.value)
        try {
            inst.runOnMainSync { model.privateModel.edit { it.copy(waitText = "83", mode = VisitRunMode.COUNT, countText = "3") } }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (model.privateModel.saving.value && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            assertFalse("Private settings must finish saving", model.privateModel.saving.value)
            val loaded = PrivateModel(context).settings.value
            assertEquals("83", loaded.waitText); assertEquals("3", loaded.countText); assertEquals(VisitRunMode.COUNT, loaded.mode)
            assertEquals(ordinary, ConfigCodec.encode(model.config.value))
        } finally {
            inst.runOnMainSync { model.privateModel.edit { original } }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (model.privateModel.saving.value && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        }
    }
}

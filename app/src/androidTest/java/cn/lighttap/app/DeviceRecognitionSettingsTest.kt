package cn.lighttap.app

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises only LightTap settings. Restores approvals instead of granting the
 * user's real image mode approval from a generated-page test. */
@RunWith(AndroidJUnit4::class)
class DeviceRecognitionSettingsTest {
    @Suppress("DEPRECATION")
    @Test fun imageChoicePersistsAndUsesItsOwnTestApproval() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val automation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = inst.targetContext
        val model = context.appModel
        val local = model.privateModel
        fun main(block: () -> Unit) = inst.runOnMainSync(block)
        fun waitFor(label: String, ready: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 10_000
            var complete = ready()
            while (!complete && SystemClock.elapsedRealtime() < end) { SystemClock.sleep(30); complete = ready() }
            assertTrue(label, complete)
        }
        waitFor("Settings load") { model.loaded.value }
        val prefs = context.getSharedPreferences("local_options", Context.MODE_PRIVATE)
        val keys = listOf("recognition", VisitRecognition.CONTROLS.confirmationKey, VisitRecognition.IMAGE.confirmationKey)
        val originalValues = prefs.all.filterKeys { it in keys }
        val original = local.settings.value
        val originalVerified = local.verified.value
        val originalState = local.state.value
        var activity: android.app.Activity? = null
        try {
            check(!local.state.value.active)
            check(prefs.edit().putBoolean(VisitRecognition.CONTROLS.confirmationKey, true)
                .putBoolean(VisitRecognition.IMAGE.confirmationKey, false).commit())
            main { local.edit { it.copy(recognition = VisitRecognition.CONTROLS) } }
            waitFor("Control settings saved") { !local.saving.value }
            assertTrue(local.verified.value)
            automation.executeShellCommand("am start -n cn.lighttap.app/.MainActivity").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
            }
            waitFor("Main page resumes") {
                main { activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull { it is MainActivity } }
                activity != null
            }
            main { local.session.unlock() }
            inst.waitForIdleSync(); SystemClock.sleep(400)
            fun clickImageChoice(): Boolean {
                val root = automation.rootInActiveWindow ?: return false
                try {
                    if (root.packageName?.toString() != context.packageName) return false
                    fun visit(label: AccessibilityNodeInfo): Boolean {
                        if (label.text?.toString() == VisitRecognition.IMAGE.title && label.isVisibleToUser) {
                            var node: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(label)
                            repeat(6) {
                                val current = node ?: return@repeat
                                if (current.isClickable && current.isEnabled) {
                                    val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    current.recycle(); node = null
                                    return result
                                }
                                val parent = current.parent; current.recycle(); node = parent
                            }
                            node?.recycle()
                        }
                        for (i in 0 until label.childCount) label.getChild(i)?.let {
                            try { if (visit(it)) return true } finally { it.recycle() }
                        }
                        return false
                    }
                    return visit(root)
                } finally { root.recycle() }
            }
            waitFor("Image choice is usable in the actual settings UI") { clickImageChoice() }
            waitFor("Image settings saved") { local.settings.value.recognition == VisitRecognition.IMAGE && !local.saving.value }
            assertFalse("Original control approval cannot approve image mode", local.verified.value)
            main { local.confirmTest() }
            assertTrue(local.verified.value)
            val reloaded = PrivateModel(context)
            assertEquals(VisitRecognition.IMAGE, reloaded.settings.value.recognition)
            assertTrue(reloaded.verified.value)
            main {
                local.state.value = VisitState(status = VisitStatus.PAUSED)
                local.edit { it.copy(recognition = VisitRecognition.CONTROLS) }
                assertEquals(VisitRecognition.IMAGE, local.settings.value.recognition)
                local.state.value = originalState
            }
        } finally {
            main { local.state.value = originalState; local.edit { original }; local.session.lock(); activity?.finish() }
            waitFor("Original settings saved") { !local.saving.value }
            val editor = prefs.edit()
            keys.forEach { key ->
                when (val value = originalValues[key]) {
                    null -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> error("Unexpected setting type")
                }
            }
            check(editor.commit())
            main { local.verified.value = originalVerified }
        }
    }
}

package cn.lighttap.app

import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** UI regression. No target-app actions and no VisitRunner start/toggle calls. */
@RunWith(AndroidJUnit4::class)
class DeviceUiRegressionTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val automation get() = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context get() = inst.targetContext
    private val model get() = context.appModel
    private fun main(action: () -> Unit) = inst.runOnMainSync(action)
    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue(label, condition())
    }
    private fun snapshot(name: String) {
        inst.waitForIdleSync(); SystemClock.sleep(500)
        val bitmap = automation.takeScreenshot() ?: error("No screenshot")
        try {
            val folder = File(context.getExternalFilesDir(null), "verification-${BuildConfig.VERSION_NAME}").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun clickOwnText(text: String): Boolean {
        val root = automation.rootInActiveWindow ?: return false
        try {
            check(root.packageName?.toString() == context.packageName) { "Only LightTap UI may be operated" }
            fun visit(node: AccessibilityNodeInfo): Boolean {
                if (node.text?.toString() == text && node.isVisibleToUser) {
                    var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                    repeat(6) {
                        val entry = current ?: return@repeat
                        if (entry.isClickable) {
                            val clicked = entry.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            entry.recycle(); current = null
                            return clicked
                        }
                        val parent = entry.parent; entry.recycle(); current = parent
                    }
                    current?.recycle()
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let {
                    try { if (visit(it)) return true } finally { it.recycle() }
                }
                return false
            }
            return visit(root)
        } finally { root.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun scrollOwnPage() {
        val root = automation.rootInActiveWindow ?: error("Missing app root")
        try {
            check(root.packageName?.toString() == context.packageName)
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for (i in 0 until node.childCount) node.getChild(i)?.let {
                    try { if (scroll(it)) return true } finally { it.recycle() }
                }
                return false
            }
            scroll(root)
        } finally { root.recycle() }
        SystemClock.sleep(300)
    }
    @Test fun captureSettingsAndOverlaysWithoutBusinessActions() {
        automation
        waitFor("Config loads") { model.loaded.value }
        if (model.service.value == null) {
            reconnectDeviceClickService(inst)
        }
        waitFor("Service reconnects") { model.service.value != null }
        val original = model.config.value
        val initialFont = shell("settings get system font_scale")
        val initialNight = shell("cmd uimode night").substringAfter(":").trim()
        require(initialNight in setOf("no", "yes", "auto", "custom"))
        val initialIds = model.profiles.value.map { it.id }.toSet()
        var activity: Activity? = null
        var privateOverlay: PrivateOverlay? = null
        try {
            shell("am start -W -f 0x30000000 -n cn.lighttap.app/.MainActivity")
            waitFor("Main page resumes") {
                main { activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull { it is MainActivity } }
                activity != null
            }
            main {
                model.service.value!!.stopRun()
                assertTrue(model.edit { ClickConfig(mode = ClickMode.SEQUENCE, points = listOf(
                    ClickPoint(1, 350f, 600f, label = "测试点击"),
                    ClickPoint(2, 350f, 700f, type = ActionType.SWIPE, endX = 500f, endY = 1000f),
                    ClickPoint(3, 350f, 800f, type = ActionType.WAIT, durationMs = 3000)), screen = model.screen.value) })
                model.saveProfile("界面检查临时方案")
            }
            snapshot("home-populated")
            shell("cmd uimode night yes")
            SystemClock.sleep(900)
            main {
                val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first { it is MainActivity }
                val bars = androidx.core.view.WindowInsetsControllerCompat(resumed.window, resumed.window.decorView)
                assertTrue("Light app must keep dark status icons under dark system mode", bars.isAppearanceLightStatusBars)
                assertTrue("Navigation icons follow the light app theme", bars.isAppearanceLightNavigationBars)
            }
            snapshot("home-system-dark")
            shell("cmd uimode night $initialNight")
            SystemClock.sleep(900)
            var opened = false
            repeat(6) { index -> if (!opened) { opened = clickOwnText("编辑"); if (!opened) { scrollOwnPage(); snapshot("main-scroll-$index") } } }
            assertTrue("Action editor opens", opened)
            snapshot("action-editor")
            assertTrue(clickOwnText("取消"))
            snapshot("action-list")
            main { model.service.value!!.showOverlay() }
            snapshot("ordinary-overlay")
            main { model.service.value!!.hideOverlay(); model.privateModel.session.unlock() }
            snapshot("private-panel")
            main {
                privateOverlay = PrivateOverlay(model.service.value!!, model.privateModel.controller!!)
                privateOverlay!!.render(VisitState(status = VisitStatus.WAITING, execution = VisitExecution.FORMAL,
                    rounds = 12, step = VisitStep.SUBMIT, remainingWaitMs = 32_000,
                    message = "填写完成，等待提交间隔"), VisitExecution.FORMAL)
            }
            snapshot("formal-overlay-synthetic")
            main {
                val field = PrivateOverlay::class.java.getDeclaredField("view").apply { isAccessible = true }
                val window = field.get(privateOverlay) as android.widget.ScrollView
                val layout = window.getChildAt(0) as LinearLayout
                val row = layout.getChildAt(2) as LinearLayout
                val button = row.getChildAt(0) as Button
                val density = context.resources.displayMetrics.density
                assertTrue("Primary touch width >= 48dp", button.width / density >= 48f)
                assertTrue("Primary touch height >= 48dp", button.height / density >= 48f)
                inst.sendStatus(2, Bundle().apply {
                    putFloat("formal_button_width_dp", button.width / density)
                    putFloat("formal_button_height_dp", button.height / density)
                })
                privateOverlay!!.clear()
            }
            shell("settings put system font_scale 1.5")
            SystemClock.sleep(900)
            snapshot("private-panel-font150")
            main {
                privateOverlay!!.render(VisitState(status = VisitStatus.WAITING, execution = VisitExecution.FORMAL,
                    rounds = 12, step = VisitStep.SUBMIT, remainingWaitMs = 32_000,
                    message = "填写完成，等待提交间隔"), VisitExecution.FORMAL)
            }
            snapshot("formal-overlay-font150-synthetic")
            inst.sendStatus(2, Bundle().apply { putBoolean("business_driver_started", false) })
        } finally {
            main {
                privateOverlay?.clear()
                model.service.value?.hideOverlay()
                model.profiles.value.filter { it.id !in initialIds }.forEach { model.deleteProfile(it.id) }
                model.edit { original }
                model.privateModel.session.lock()
                activity?.finishAndRemoveTask()
            }
            shell("settings put system font_scale $initialFont")
            shell("cmd uimode night $initialNight")
            waitFor("Original configuration saved") { !model.saving.value }
        }
    }
}

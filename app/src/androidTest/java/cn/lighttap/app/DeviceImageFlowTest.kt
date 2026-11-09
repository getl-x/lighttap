package cn.lighttap.app

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the production OCR/gesture/driver pipeline ONLY in LightTap's own
 * temporary, generated fixture. The fence is in memory, never the user's fence. */
@RunWith(AndroidJUnit4::class)
class DeviceImageFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private fun main(block: () -> Unit) = inst.runOnMainSync(block)
    private fun waitFor(label: String, timeout: Long = 15_000, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!ready() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue(label, ready())
    }
    private fun service(): ClickAccessibilityService {
        val automation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        if (context.appModel.service.value == null) {
            reconnectDeviceClickService(inst)
        }
        waitFor("Click service reconnects") { context.appModel.service.value != null && context.appModel.loaded.value }
        return context.appModel.service.value!!
    }

    @Test fun imageNavigationInputAndSingleSubmitCompleteOnGeneratedOwnPage() {
        val service = service()
        inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand("am start -n cn.lighttap.app/.MainActivity").use {
                ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
            }
        inst.waitForIdleSync(); SystemClock.sleep(500)
        val activity = inst.startActivitySync(Intent(context, TouchTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val events = mutableListOf<VisitDebugEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val native = AndroidUiBridge(service, context.packageName) { null }
        val images = AndroidImageVisitUiAccess(service, native, VisitSurfaceGuard(), VisitDiagnostics { events += it })
        val fence = object : VisitSubmissionFence {
            override var hash: String? = null
            override var target: String? = null
            override fun mark(hash: String, target: String): Boolean { this.hash = hash; this.target = target; return true }
            override fun clear(): Boolean { hash = null; target = null; return true }
        }
        val driver = AndroidVisitDriver(images, AndroidChoiceVision(service), fence,
            AndroidReturnVision(service), { SystemClock.elapsedRealtime() })
        val runner = VisitRunner(scope, driver, { SystemClock.elapsedRealtime() })
        var navigationClicks = 0; var submissions = 0
        var address: EditText? = null; var description: EditText? = null
        val oldConfig = context.appModel.config.value
        val oldPrivate = context.appModel.privateModel.settings.value

        fun page(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE); setPadding(dp(16), dp(45), dp(16), dp(30))
            activity.setContentView(this)
        }
        fun LinearLayout.label(text: String, selected: Boolean = false) = TextView(activity).apply {
            this.text = text; textSize = 18f; setTextColor(Color.BLACK); isSelected = selected
            setPadding(dp(4), dp(4), dp(4), dp(4)); addView(this)
        }
        fun LinearLayout.button(text: String, color: Int = Color.rgb(25, 137, 250), click: () -> Unit): Button = Button(activity).apply {
            this.text = text; textSize = 18f; isAllCaps = false; setTextColor(color)
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1).coerceAtLeast(1), color) }
            addView(this, LinearLayout.LayoutParams(dp(160), dp(54)).apply { setMargins(dp(3), dp(8), dp(3), dp(8)) })
            setOnClickListener { click() }
        }
        fun tabs(layout: LinearLayout) {
            layout.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                label("待办", selected = true).layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
                label("已完成").layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            })
        }
        fun identity(layout: LinearLayout, name: String, value: String) {
            layout.addView(LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; label(name); label(value) })
        }
        fun field(layout: LinearLayout, name: String): EditText {
            val section = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; layout.addView(this); label(name) }
            return EditText(activity).apply {
                setTextColor(Color.BLACK); textSize = 18f; setSingleLine(true)
                section.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }
        fun emptyList() { val layout = page(); tabs(layout); layout.label("暂无待办") }
        fun form() {
            val layout = page(); layout.label("新增"); layout.label("走访记录")
            identity(layout, "姓名", "样例甲"); identity(layout, "居住地址", "测试地址")
            address = field(layout, "检查地址"); description = field(layout, "走访描述")
            layout.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                button("正常") {}.isSelected = true
                button("异常", Color.rgb(221, 221, 221)) {}.setTextColor(Color.rgb(29, 31, 36))
            })
            layout.button("提交") {
                assertEquals(VisitContract.ADDRESS, address!!.text.toString())
                assertEquals(VisitContract.DESCRIPTION, description!!.text.toString())
                submissions++; emptyList()
            }
        }
        try {
            main {
                service.hideOverlay()
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                val layout = page(); tabs(layout)
                layout.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    label("样例甲"); label("身份证：示例"); label("居住地址：测试地址"); label("0/1")
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        button("走访记录") {}
                        button("走访") { navigationClicks++; form() }
                    })
                })
            }
            inst.waitForIdleSync(); SystemClock.sleep(500)
            main { runner.start(VisitSettings(targetPackage = context.packageName, recognition = VisitRecognition.IMAGE), VisitExecution.FORMAL) }
            waitFor("Generated image task finishes", 60_000) { runner.state.value.status in setOf(VisitStatus.COMPLETED, VisitStatus.ERROR) }
            inst.sendStatus(2, Bundle().apply {
                putString("image_final_step", runner.state.value.step.name)
                putInt("image_navigation_clicks", navigationClicks); putInt("image_submit_clicks", submissions)
                putString("image_checks", events.joinToString(";") { "${it.kind}:${it.navigation}:${it.normal}:${it.visual}:${it.ok}" })
            })
            if (runner.state.value.status == VisitStatus.ERROR) {
                val tree = runBlocking { native.capture() }
                if (tree.has("新增")) {
                    val contract = VisitContract(context.packageName)
                    inst.sendStatus(2, Bundle().apply {
                        putString("fixture_normal_bounds", contract.choiceButton(tree, "正常").bounds.toString())
                        putString("fixture_abnormal_bounds", contract.choiceButton(tree, "异常").bounds.toString())
                    })
                }
                val bitmap = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
                if (bitmap != null) try {
                    val file = java.io.File(context.getExternalFilesDir(null), "verification-1.2.15/image-fixture-error.png").apply { parentFile!!.mkdirs() }
                    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
            assertEquals("Production image flow: ${runner.state.value.message}", VisitStatus.COMPLETED, runner.state.value.status)
            assertEquals(1L, runner.state.value.rounds)
            assertEquals(1, navigationClicks); assertEquals(1, submissions)
            assertFalse(driver.pendingSubmission)
            assertEquals(oldConfig, context.appModel.config.value)
            assertEquals(oldPrivate, context.appModel.privateModel.settings.value)
            inst.sendStatus(2, Bundle().apply {
                putInt("image_navigation_clicks", navigationClicks); putInt("image_submit_clicks", submissions)
                putString("image_recognition_ms", events.filter { it.kind == VisitDebugKind.IMAGE_CHECK }.mapNotNull { it.durationMs }.joinToString(","))
                putBoolean("business_app_operated", false)
            })
        } finally {
            main { runner.stop(); activity.finish() }
            scope.cancel()
            runBlocking { images.close() }
        }
    }
}

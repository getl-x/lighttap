package cn.lighttap.app

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceProbeTest {
    @Test fun readOnlyTargetCapabilities() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val model = instrument.targetContext.appModel
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (model.service.value == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        var tree: UiTree? = null
        var problem: String? = null
        instrument.runOnMainSync {
            val service = model.service.value
            if (service == null) problem = "service_unavailable"
            else try { tree = AndroidUiBridge(service, PrivateModel.TARGET) { null }.capture() }
            catch (_: Exception) { problem = "target_unavailable" }
        }
        val captured = tree
        val summary = Bundle().apply {
            putString("probe", "read_only_no_actions")
            putString("status", problem ?: "captured")
            putInt("nodes", captured?.nodes?.size ?: 0)
            putInt("visit_buttons", captured?.exact("走访", true)?.size ?: 0)
            putInt("history_buttons", captured?.exact("走访记录", true)?.size ?: 0)
            putInt("editable_inputs", captured?.nodes?.count { it.editable } ?: 0)
        }
        instrument.sendStatus(2, summary)
        // Do not claim target compatibility: this test checks that a read-only capture is possible.
        assertNotNull("Accessibility service must be enabled", model.service.value)
        assertNotNull("Target app must remain foreground", captured)
    }
}
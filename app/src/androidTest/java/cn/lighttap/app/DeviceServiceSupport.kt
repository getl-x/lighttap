package cn.lighttap.app

import android.app.Instrumentation
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

/** Instrumentation restarts the target process. Restore only the click service
 * known to be enabled before testing, preserving every other enabled service. */
internal fun reconnectDeviceClickService(inst: Instrumentation) {
    val automation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    fun shell(command: String) = automation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    var enabled = shell("settings get secure enabled_accessibility_services")
    val component = "cn.lighttap.app/cn.lighttap.app.ClickAccessibilityService"
    if (!enabled.split(':').any { it.startsWith("cn.lighttap.app/") }) {
        check(InstrumentationRegistry.getArguments().getString("restoreClickService") == "true") { "User must enable the service first" }
        enabled = (enabled.split(':').filter { it.isNotBlank() && it != "null" } + component).distinct().joinToString(":")
    }
    require(enabled.matches(Regex("[A-Za-z0-9_.$/:]+")))
    val others = enabled.split(':').filterNot { it.startsWith("cn.lighttap.app/") }.joinToString(":")
    try {
        shell(if (others.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $others")
        SystemClock.sleep(250)
    } finally { shell("settings put secure enabled_accessibility_services $enabled") }
}

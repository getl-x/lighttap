package cn.lighttap.app

import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Live business actions require an explicit instrumentation argument; never run in CI. */
@RunWith(AndroidJUnit4::class)
class DeviceVisitTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun service(): ClickAccessibilityService {
        val automation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val model = inst.targetContext.appModel
        fun shell(command: String) = automation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
        }
        if (model.service.value == null) {
            val enabled = shell("settings get secure enabled_accessibility_services")
            require(enabled.matches(Regex("[A-Za-z0-9_.$/:]+")))
            require(enabled.split(':').any { it.startsWith("cn.lighttap.app/") }) { "Enable LightTap first" }
            val others = enabled.split(':').filterNot { it.startsWith("cn.lighttap.app/") }.joinToString(":")
            try {
                shell(if (others.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $others")
                SystemClock.sleep(200)
            } finally { shell("settings put secure enabled_accessibility_services $enabled") }
        }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (model.service.value == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        return model.service.value ?: error("Service did not reconnect")
    }
    @Test fun readOnlyCaptureBenchmark() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("visitProbe") == "true")
        val service = service()
        val bridge = AndroidUiBridge(service, PrivateModel.TARGET) { null }
        repeat(3) { index ->
            val start = SystemClock.elapsedRealtime()
            val tree = bridge.capture()
            val elapsed = SystemClock.elapsedRealtime() - start
            inst.sendStatus(2, Bundle().apply { putInt("nodes", tree.nodes.size); putLong("capture_ms", elapsed) })
            val parseStart = SystemClock.elapsedRealtime()
            VisitContract(PrivateModel.TARGET).top(tree)
            inst.sendStatus(2, Bundle().apply {
                putInt("sample", index); putInt("nodes", tree.nodes.size)
                putLong("capture_ms", elapsed)
                putLong("parse_ms", SystemClock.elapsedRealtime() - parseStart)
            })
        }
    }

    @Test fun runningReminderVibratesWithoutBusinessActions() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("visitVibration") == "true")
        val service = service()
        val vibration = AndroidRunVibration(service)
        val state = kotlinx.coroutines.flow.MutableStateFlow(VisitState(status = VisitStatus.WAITING, execution = VisitExecution.FORMAL))
        val calls = AtomicInteger()
        val first = CompletableDeferred<Boolean>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // Accelerate only this test's independent observer; production is 5min.
        val job = scope.launchVisitRunReminder(state, intervalMs = 1_000, remind = {
            calls.incrementAndGet(); first.complete(vibration.remind())
        }, cancelReminder = { vibration.cancel() })
        try {
            assertEquals(300_000L, VISIT_REMINDER_INTERVAL_MS)
            assertTrue(withTimeout(5_000) { first.await() })
            assertEquals(VisitStatus.WAITING, state.value.status)
            // Let the finite double pulse complete before checking cancellation.
            delay(650)
            withContext(Dispatchers.Main) { state.value = state.value.copy(status = VisitStatus.PAUSED) }
            delay(1_500)
            assertEquals(1, calls.get())
            inst.sendStatus(2, Bundle().apply {
                putBoolean("vibration_requested", true); putInt("reminder_count", calls.get())
                putBoolean("no_repeat_after_pause", true)
            })
        } finally { job.cancelAndJoin(); scope.cancel(); vibration.cancel() }
    }

    @Test fun workRemindersPersistAndBothSwipePathsDismiss() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("visitReminderDisplay") == "true")
        val service = service()
        val controller = withContext(Dispatchers.Main) { inst.targetContext.appModel.privateModel.controller!! }
        val automation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val manager = service.getSystemService(android.app.NotificationManager::class.java)
        val bridge = AndroidUiBridge(service, PrivateModel.TARGET) { null }
        val contract = VisitContract(PrivateModel.TARGET)
        val original = contract.listSignature(bridge.capture())
        val pending = service.getSharedPreferences("operation_fence", 0).contains("pending_hash")
        var drawerOpen = false
        fun swipe(x1: Float, y: Float, x2: Float) {
            val down = SystemClock.uptimeMillis()
            fun input(action: Int, x: Float) {
                val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            input(android.view.MotionEvent.ACTION_DOWN, x1)
            for (step in 1..12) { SystemClock.sleep(12); input(android.view.MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * step / 12) }
            input(android.view.MotionEvent.ACTION_UP, x2)
        }
        try {
            withContext(Dispatchers.Main) { controller.showWorkReminder(WorkReminderEvent(java.time.LocalDate.of(2026, 11, 9), WorkReminderSlot.MORNING)) }
            delay(12_000)
            val bounds = withContext(Dispatchers.Main) { controller.workReminderBounds() } ?: error("Reminder auto-disappeared")
            val notification = manager.activeNotifications.single { it.id == WorkReminderDisplay.ID }.notification
            // ColorOS may add its own multi-day notification cleanup timeout.
            assertTrue(notification.timeoutAfter == 0L || notification.timeoutAfter >= 86_400_000L)
            assertEquals(0, notification.flags and android.app.Notification.FLAG_ONGOING_EVENT)
            val channel = manager.getNotificationChannel(WorkReminderDisplay.CHANNEL)
            assertNull(channel.sound); assertFalse(channel.shouldVibrate())
            assertEquals(ReturnVisual.CLEAR, AndroidReturnVision(service).inspect(bridge.capture()).state)
            val screenshot = automation.takeScreenshot() ?: error("No preview")
            val crop = android.graphics.Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top)
            try {
                java.io.File(inst.targetContext.getExternalFilesDir(null), "work-reminder-preview.png").outputStream().use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { crop.recycle(); screenshot.recycle() }
            swipe((bounds.left + bounds.right) / 2f, bounds.top + (bounds.bottom - bounds.top) * .8f, bounds.right.toFloat() - 4)
            delay(500)
            assertNull(withContext(Dispatchers.Main) { controller.workReminderBounds() })
            assertFalse(manager.activeNotifications.any { it.id == WorkReminderDisplay.ID })
            inst.sendStatus(2, Bundle().apply {
                putBoolean("top_card_persisted_12s", true); putBoolean("card_swipe_cleared_both", true)
                putLong("system_notification_timeout_ms", notification.timeoutAfter)
            })

            withContext(Dispatchers.Main) { controller.showWorkReminder(WorkReminderEvent(java.time.LocalDate.of(2026, 11, 9), WorkReminderSlot.AFTERNOON)) }
            delay(500)
            assertTrue(service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
            drawerOpen = true
            delay(1_000)
            var target: android.graphics.Rect? = null
            @Suppress("DEPRECATION")
            fun find(node: AccessibilityNodeInfo) {
                if (node.isVisibleToUser && node.text?.toString() == "17:00 下班前提醒") target = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                for (index in 0 until node.childCount) node.getChild(index)?.let { child -> try { find(child) } finally { child.recycle() } }
            }
            @Suppress("DEPRECATION")
            automation.rootInActiveWindow.let { root -> requireNotNull(root); try { find(root) } finally { root.recycle() } }
            val row = target ?: error("Reminder not visible in notification drawer")
            val screenWidth = service.resources.displayMetrics.widthPixels.toFloat()
            inst.sendStatus(2, Bundle().apply { putString("system_notification_text_bounds", row.toShortString()) })
            swipe(screenWidth * .3f, row.exactCenterY(), screenWidth * .98f)
            delay(700)
            assertFalse(manager.activeNotifications.any { it.id == WorkReminderDisplay.ID })
            assertNull(withContext(Dispatchers.Main) { controller.workReminderBounds() })
            assertTrue(service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            drawerOpen = false
            delay(500)
            assertEquals(original, contract.listSignature(bridge.capture()))
            assertEquals(pending, service.getSharedPreferences("operation_fence", 0).contains("pending_hash"))
            inst.sendStatus(2, Bundle().apply { putBoolean("system_swipe_cleared_both", true); putBoolean("no_business_actions", true) })
        } finally {
            withContext(Dispatchers.Main) { controller.dismissWorkReminder() }
            if (drawerOpen) service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    @Test fun diagnoseReadOnlyStructure() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("visitProbe") == "true")
        val service = service()
        val tree = AndroidUiBridge(service, PrivateModel.TARGET) { null }.capture()
        val known = setOf("待办", "已完成", "走访", "走访记录", "身份证", "居住地址", "姓名", "0/1", "新增", "检查地址", "走访描述", "正常", "异常", "提交")
        inst.sendStatus(2, Bundle().apply {
            putBoolean("pending", service.getSharedPreferences("operation_fence", 0).contains("pending_hash"))
            putString("structure", tree.nodes.joinToString("\n") { n ->
                val text = canonicalLabel(n.text).removeSuffix("：").removeSuffix(":")
                val label = if (text in known) text else if (n.text.isNotEmpty()) "[text:${n.text.length}]" else ""
                "${n.path} ${n.className.substringAfterLast('.')} $label ${n.bounds} v=${n.visible} click=${n.clickable} sel=${n.selected} edit=${n.editable}"
            })
        })
    }

    @Test fun normalReturnCaptureBenchmark() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("visitProbe") == "true")
        val service = service()
        val bridge = AndroidUiBridge(service, PrivateModel.TARGET) { null }
        val vision = AndroidReturnVision(service)
        repeat(3) {
            val start = SystemClock.elapsedRealtime()
            val tree = bridge.capture()
            val treeReadAt = SystemClock.elapsedRealtime()
            VisitContract(PrivateModel.TARGET).listSignature(tree)
            val frame = vision.inspect(tree)
            inst.sendStatus(2, Bundle().apply {
                putString("return_visual", frame.state.name)
                putLong("capture_and_visual_ms", SystemClock.elapsedRealtime() - start)
                putLong("tree_ms", treeReadAt - start)
                putLong("visual_ms", SystemClock.elapsedRealtime() - treeReadAt)
            })
            assertEquals("Normal live list must pass visual check", ReturnVisual.CLEAR, frame.state)
            delay(1_000)
        }
    }

    @Test fun simulatedLoadingMaskIsBlocked() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("visitProbe") == "true")
        val service = service()
        val tree = AndroidUiBridge(service, PrivateModel.TARGET) { null }.capture()
        VisitContract(PrivateModel.TARGET).listSignature(tree)
        val manager = service.getSystemService(android.view.WindowManager::class.java)
        val mask = android.widget.FrameLayout(service).apply { setBackgroundColor(0x88000000.toInt()) }
        val dialog = android.view.View(service).apply { setBackgroundColor(android.graphics.Color.WHITE) }
        mask.addView(dialog, android.widget.FrameLayout.LayoutParams(360, 160, android.view.Gravity.CENTER))
        val layout = android.view.WindowManager.LayoutParams(-1, -1,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT)
        withContext(Dispatchers.Main) { manager.addView(mask, layout) }
        try {
            delay(300)
            val frame = AndroidReturnVision(service).inspect(tree)
            inst.sendStatus(2, Bundle().apply { putString("simulated_mask_visual", frame.state.name) })
            assertEquals(ReturnVisual.BLOCKED, frame.state)
        } finally { withContext(Dispatchers.Main) { manager.removeView(mask) } }
    }

    @Test fun formalOverlayAllowsNormalReturnCheck() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("visitProbe") == "true")
        val service = service()
        val model = inst.targetContext.appModel.privateModel
        val verified = model.verified.value
        val unlocked = model.session.unlocked.value
        try {
            withContext(Dispatchers.Main) {
                model.session.unlock(); model.verified.value = true
                model.controller!!.arm(VisitExecution.FORMAL)
            }
            delay(500)
            val tree = AndroidUiBridge(service, PrivateModel.TARGET) { null }.capture()
            VisitContract(PrivateModel.TARGET).listSignature(tree)
            val frame = AndroidReturnVision(service).inspect(tree)
            inst.sendStatus(2, Bundle().apply { putString("formal_overlay_visual", frame.state.name) })
            assertEquals(ReturnVisual.CLEAR, frame.state)
        } finally {
            withContext(Dispatchers.Main) {
                model.controller!!.stopAndHide(); model.verified.value = verified
                if (!unlocked) model.session.lock()
            }
        }
    }

    @Test fun recoverPendingReadOnly() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("visitRecover") == "true")
        val service = service()
        val driver = AndroidVisitDriver(service, AndroidUiBridge(service, PrivateModel.TARGET) { null }, AndroidChoiceVision(service))
        assertTrue(driver.pendingSubmission)
        driver.begin(); driver.validate(VisitStep.WAIT_LIST)
        withTimeout(30_000) { driver.execute(VisitStep.WAIT_LIST) }
        assertFalse(driver.pendingSubmission)
    }

    @Test fun liveFormalRound() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val submit = arguments.getString("visitSubmit") == "true"
        val rounds = (arguments.getString("visitRounds") ?: "1").toInt().also { require(it in 1..3) }
        assumeTrue(submit || arguments.getString("visitDryRun") == "true")
        val service = service()
        val deviceBridge = AndroidUiBridge(service, PrivateModel.TARGET) { null }
        val submitStarts = mutableListOf<Long>()
        val submitEnds = mutableListOf<Long>()
        val submitDispatches = mutableListOf<Long>()
        val confirmations = mutableListOf<Long>()
        val intervalAnchors = mutableListOf<Long>()
        val bridge = object : VisitUiAccess by deviceBridge {
            override suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String?) {
                val submitting = action == AccessibilityNodeInfo.ACTION_CLICK && canonicalLabel(node.text) == "提交"
                if (submitting) {
                    val time = SystemClock.elapsedRealtime()
                    submitStarts += time
                    inst.sendStatus(2, Bundle().apply { putLong("submit_started_ms", time) })
                    if (submitEnds.isNotEmpty()) assertTrue("Next attempt must begin >60s after prior click completed", time - submitEnds.last() > 60_000)
                }
                deviceBridge.perform(tree, node, action, value)
                if (submitting) {
                    val time = SystemClock.elapsedRealtime()
                    submitEnds += time
                    val dispatchedAt = deviceBridge.lastClickDispatchMs ?: error("No accepted submit gesture")
                    submitDispatches += dispatchedAt
                    inst.sendStatus(2, Bundle().apply {
                        putLong("submit_completed_ms", time)
                        putLong("submit_dispatched_ms", dispatchedAt)
                        confirmations.lastOrNull()?.let { putLong("confirmation_to_next_click_ms", dispatchedAt - it) }
                        intervalAnchors.lastOrNull()?.let { putLong("anchor_to_next_click_ms", dispatchedAt - it) }
                        submitDispatches.dropLast(1).lastOrNull()?.let { putLong("click_to_next_click_ms", dispatchedAt - it) }
                    })
                    intervalAnchors.lastOrNull()?.let { assertTrue("Interval anchor to next click must be at least 61s", dispatchedAt - it >= MIN_SUBMISSION_INTERVAL_MS) }
                }
            }
        }
        val actual = AndroidVisitDriver(service, bridge, AndroidChoiceVision(service))
        assertFalse("Resolve any previous pending submission first", actual.pendingSubmission)
        var submissions = 0
        val driver = object : VisitDriver by actual {
            override suspend fun execute(step: VisitStep): VisitEffect {
                if (step == VisitStep.SUBMIT && !submit) throw VisitMismatch("dry_run_verified_before_submit")
                if (step == VisitStep.SUBMIT) submissions++
                val start = SystemClock.elapsedRealtime()
                inst.sendStatus(2, Bundle().apply { putString("step_started", step.name) })
                return actual.execute(step).also {
                    if (step == VisitStep.WAIT_LIST) {
                        val time = SystemClock.elapsedRealtime()
                        confirmations += time
                        val confirmation = actual.lastConfirmation ?: error("No interval anchor")
                        intervalAnchors += confirmation.anchorMs
                        inst.sendStatus(2, Bundle().apply {
                            putLong("submit_confirmed_ms", time)
                            putLong("interval_anchor_ms", confirmation.anchorMs)
                            putString("confirmation_method", confirmation.method)
                            putInt("return_checks", actual.returnChecks)
                            putLong("confirmation_after_anchor_ms", time - confirmation.anchorMs)
                        })
                    }
                    inst.sendStatus(2, Bundle().apply {
                        putString("step_done", step.name)
                        putLong("elapsed_ms", SystemClock.elapsedRealtime() - start)
                    })
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val runner = VisitRunner(scope, driver, { SystemClock.elapsedRealtime() })
        val start = SystemClock.elapsedRealtime()
        try {
            withContext(Dispatchers.Main) { runner.start(VisitSettings(targetPackage = PrivateModel.TARGET, mode = VisitRunMode.COUNT, countText = rounds.toString()), VisitExecution.FORMAL) }
            val result = withTimeout(rounds * 90_000L + 30_000) { runner.state.first { it.status == VisitStatus.ERROR || it.status == VisitStatus.COMPLETED } }
            inst.sendStatus(2, Bundle().apply {
                putString("visit_status", result.status.name); putString("message", result.message)
                putString("step", result.step.name); putLong("rounds", result.rounds)
                putLong("total_ms", SystemClock.elapsedRealtime() - start); putInt("submissions", submissions)
            })
            if (submit) {
                assertEquals(result.message, VisitStatus.COMPLETED, result.status)
                assertEquals(rounds.toLong(), result.rounds); assertEquals(rounds, submissions)
                assertEquals(rounds, submitEnds.size)
                assertEquals(rounds, submitDispatches.size)
                assertEquals(rounds, confirmations.size)
                val restored = AndroidVisitDriver(service, deviceBridge, AndroidChoiceVision(service))
                assertTrue("Cooldown must survive driver recreation", restored.submissionWaitMs(MIN_SUBMISSION_INTERVAL_MS) > 0)
                assertFalse(actual.pendingSubmission)
            } else {
                assertEquals("dry_run_verified_before_submit", result.message)
                assertEquals(0, submissions)
            }
        } finally {
            withContext(Dispatchers.Main) { runner.stop() }
            scope.cancel()
        }
    }

    @Test fun formalOverlayAndPauseBeforeSubmit() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("visitOverlayDryRun") == "true")
        service()
        val model = inst.targetContext.appModel.privateModel
        val saved = model.settings.value
        val verified = model.verified.value
        val activity = inst.startActivitySync(Intent(inst.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        inst.waitForIdleSync()
        val heartbeats = AtomicInteger()
        val ticker = CoroutineScope(Dispatchers.Main).launch { while (isActive) { heartbeats.incrementAndGet(); delay(20) } }
        try {
            withContext(Dispatchers.Main) {
                model.session.unlock()
                model.edit { it.copy(mode = VisitRunMode.ONCE, waitText = DEFAULT_SUBMISSION_INTERVAL_SECONDS) }
                // Test-only authorization; never persist a fabricated test confirmation.
                model.verified.value = true
                model.controller!!.arm(VisitExecution.FORMAL)
                activity.moveTaskToBack(true)
            }
            delay(500)
            withContext(Dispatchers.Main) { model.controller!!.toggle() }
            val result = withTimeout(60_000) { model.state.first { it.status == VisitStatus.ERROR || (it.step == VisitStep.VERIFY_FORM && it.busy) } }
            withContext(Dispatchers.Main) { model.controller!!.pause("设备检查已暂停在提交前") }
            inst.sendStatus(2, Bundle().apply {
                putString("overlay_result", result.status.name); putString("step", result.step.name)
                putString("message", result.message); putInt("main_heartbeats", heartbeats.get())
            })
            assertEquals(result.message, VisitStep.VERIFY_FORM, result.step)
            assertFalse("Dry run must not submit", model.state.value.pendingSubmission)
            assertTrue("UI must remain responsive while reading", heartbeats.get() > 20)
            // Give an attached ADB session a chance to inspect the actual overlay.
            delay(5_000)
        } finally {
            ticker.cancel()
            withContext(Dispatchers.Main) { model.controller!!.stopAndHide() }
            withTimeout(5_000) { model.state.first { !it.active } }
            withContext(Dispatchers.Main) { model.edit { saved }; model.verified.value = verified; model.session.lock() }
        }
    }
}

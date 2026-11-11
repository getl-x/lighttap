package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The same action graph is used for both modes; only the checkpoint gate differs. */
class VisitRunner(
    private val scope: CoroutineScope,
    private val driver: VisitDriver,
    private val now: () -> Long,
    private val onError: (String) -> Unit = {},
    private val onTestConfirmed: () -> Unit = {},
    private val onCompleted: () -> Unit = {},
    private val diagnostics: VisitDiagnostics = VisitDiagnostics.NONE,
) {
    private val mutable = MutableStateFlow(VisitState())
    val state: StateFlow<VisitState> = mutable
    private var settings = VisitSettings()
    private var worker: Job? = null
    private var generation = 0L
    private var lastSubmissionAt: Long? = null
    private var stepBudget = 30_000L
    private var stepSince: Long? = null
    private var initialized = false

    fun start(plan: VisitSettings, execution: VisitExecution) {
        if (state.value.active || worker?.isActive == true) return
        val problem = plan.error()
        if (problem != null) { mutable.value = VisitState(message = problem); onError(problem); return }
        settings = plan
        initialized = false; stepBudget = 30_000; stepSince = null
        mutable.value = VisitState(status = VisitStatus.RUNNING, execution = execution, message = "正在检查")
        debug(VisitDebugKind.START, waitMs = plan.waitMs)
        launchWorker()
    }
    fun resume() {
        if (state.value.status !in setOf(VisitStatus.CHECKPOINT, VisitStatus.PAUSED, VisitStatus.ERROR) || worker?.isActive == true) return
        if (stepBudget <= 0) stepBudget = 30_000 // Explicit user retry, never an automatic retry.
        mutable.value = state.value.copy(status = VisitStatus.RUNNING, message = "继续读取当前控件")
        debug(VisitDebugKind.RESUME)
        launchWorker()
    }
    private fun launchWorker() {
        val token = ++generation
        worker = scope.launch {
            try {
                if (!initialized) {
                    driver.begin()
                    initialized = true
                    if (driver.pendingSubmission) mutable.value = state.value.copy(step = VisitStep.WAIT_LIST, pendingSubmission = true, message = "仅核对上次提交结果，不会再次提交")
                }
                while (isActive && token == generation) {
                    val step = state.value.step
                    if (step == VisitStep.GAP) {
                        debug(VisitDebugKind.STEP_START)
                        withTimeout(stepBudget.coerceAtLeast(1)) { driver.validate(VisitStep.GAP) }
                        if (token != generation) return@launch
                        debug(VisitDebugKind.STEP_DONE)
                        mutable.value = state.value.copy(step = VisitStep.FIND_LIST, status = VisitStatus.RUNNING, remainingWaitMs = 0)
                        stepBudget = 30_000
                        continue
                    }
                    if (step == VisitStep.SUBMIT) {
                        val lead = if (state.value.execution == VisitExecution.FORMAL) driver.submissionPreparationLeadMs else 0
                        if (lead > 0) {
                            awaitSubmissionSlot(token, lead)
                            ensureActive()
                            if (token != generation) return@launch
                            mutable.value = state.value.copy(message = "正在准备提交", pendingSubmission = driver.pendingSubmission)
                            stepSince = now()
                            runDriverStep { retryUndispatched { driver.prepareSubmission() } }
                            debug(VisitDebugKind.SUBMIT_PREPARED, durationMs = stepSince?.let { now() - it })
                            chargeStep()
                        }
                        awaitSubmissionSlot(token)
                    }
                    ensureActive()
                    if (token != generation) return@launch
                    mutable.value = state.value.copy(message = "正在${step.title}", pendingSubmission = driver.pendingSubmission)
                    debug(VisitDebugKind.STEP_START)
                    if (step == VisitStep.WAIT_LIST && stepBudget == 30_000L) stepBudget = 120_000
                    stepSince = now()
                    val effect = runDriverStep {
                        driver.validate(step)
                        ensureActive()
                        try { retryUndispatched { driver.execute(step) } }
                        finally { if (step == VisitStep.SUBMIT && !driver.managesSubmissionTiming) lastSubmissionAt = now() }
                    }
                    debug(VisitDebugKind.STEP_DONE, durationMs = stepSince?.let { now() - it })
                    chargeStep()
                    ensureActive()
                    if (token != generation) return@launch
                    if (effect.emptyList) {
                        if (step != VisitStep.FIND_LIST || driver.pendingSubmission) throw VisitMismatch("空列表结束条件不匹配")
                        mutable.value = state.value.copy(status = VisitStatus.COMPLETED, lastStep = step.title, result = effect.message,
                            message = "待办已处理完", pendingSubmission = false, remainingWaitMs = 0)
                        driver.release()
                        notifyCompleted()
                        return@launch
                    }
                    val next = nextStep(step, effect.repeat)
                    var rounds = state.value.rounds
                    if (step == VisitStep.WAIT_LIST && !effect.repeat) rounds++
                    val limit = settings.limit(state.value.execution)
                    val finished = step == VisitStep.WAIT_LIST && !effect.repeat && limit != null && rounds >= limit
                    mutable.value = state.value.copy(
                        rounds = rounds, lastStep = step.title, result = effect.message,
                        pendingSubmission = driver.pendingSubmission,
                        step = next, message = "本步完成：${step.title}",
                    )
                    if (finished) {
                        mutable.value = state.value.copy(
                            status = if (state.value.execution == VisitExecution.STEP_TEST) VisitStatus.FINISH_CONFIRM else VisitStatus.COMPLETED,
                            message = if (state.value.execution == VisitExecution.STEP_TEST) "本轮测试完成，点击确认结束" else "已完成 $rounds 轮",
                        )
                        if (state.value.status == VisitStatus.COMPLETED) notifyCompleted()
                        return@launch
                    }
                    stepBudget = 30_000
                    if (state.value.execution == VisitExecution.STEP_TEST) {
                        mutable.value = state.value.copy(status = VisitStatus.CHECKPOINT, message = "本步完成，点击继续")
                        return@launch
                    }
                    // Yield between steps, so pause/stop and foreground changes can be processed.
                    yield()
                }
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException && token == generation) fail(if (state.value.step == VisitStep.WAIT_LIST) "提交结果等待超时，请查看；保留保护标记，不会重复提交" else "页面转换或核验超过 30 秒，请查看")
                else if (token == generation && scope.isActive) fail("当前步骤被取消，已暂停；请查看后继续")
                else throw cancelled
            } catch (mismatch: VisitMismatch) {
                if (token == generation) fail(mismatch.reason)
            } catch (_: Exception) {
                if (token == generation) fail("当前步骤发生异常，已暂停；请查看页面")
            } finally {
                if (token == generation) chargeStep()
            }
        }
    }
    private fun nextStep(step: VisitStep, repeat: Boolean): VisitStep = if (repeat) step else when (step) {
        VisitStep.FIND_LIST -> VisitStep.OPEN_FORM
        VisitStep.OPEN_FORM -> VisitStep.WAIT_FORM
        VisitStep.WAIT_FORM -> VisitStep.FILL_ADDRESS
        VisitStep.FILL_ADDRESS -> VisitStep.LOCATE_DESCRIPTION
        VisitStep.LOCATE_DESCRIPTION -> VisitStep.FILL_DESCRIPTION
        VisitStep.FILL_DESCRIPTION -> VisitStep.VERIFY_FORM
        VisitStep.VERIFY_FORM -> VisitStep.SUBMIT
        VisitStep.SUBMIT -> VisitStep.WAIT_LIST
        VisitStep.WAIT_LIST -> VisitStep.GAP
        VisitStep.GAP -> VisitStep.FIND_LIST
    }
    private fun remainingSubmissionWait(): Long {
        val local = lastSubmissionAt?.let { (settings.waitMs - (now() - it).coerceAtLeast(0)).coerceAtLeast(0) } ?: 0
        return maxOf(local, driver.submissionWaitMs(settings.waitMs))
    }
    private suspend fun awaitSubmissionSlot(token: Long, stopAtMs: Long = 0) {
        var waiting = false
        while (token == generation && currentCoroutineContext().isActive) {
            val left = remainingSubmissionWait()
            if (left <= stopAtMs) break
            mutable.value = state.value.copy(status = VisitStatus.WAITING, remainingWaitMs = left, message = "填写完成，等待提交间隔")
            if (!waiting) { debug(VisitDebugKind.WAIT, waitMs = left); waiting = true }
            delay(minOf(1_000, left - stopAtMs))
        }
        currentCoroutineContext().ensureActive()
        if (token == generation) {
            mutable.value = state.value.copy(status = VisitStatus.RUNNING, remainingWaitMs = 0)
            if (waiting) debug(VisitDebugKind.WAIT, waitMs = 0)
        }
    }
    private fun chargeStep() { stepSince?.let { stepBudget = (stepBudget - (now() - it).coerceAtLeast(0)).coerceAtLeast(0) }; stepSince = null }
    private suspend fun <T> runDriverStep(operation: suspend () -> T): T = coroutineScope {
        val start = now()
        val progress = launch {
            while (isActive) {
                driver.waitingMessage?.let { message ->
                    mutable.value = state.value.copy(message = "$message\n已等待 ${(now() - start).coerceAtLeast(0) / 1_000} 秒，自动继续中")
                }
                delay(1_000)
            }
        }
        try {
            if (driver.waitsForControls) operation() else withTimeout(stepBudget.coerceAtLeast(1)) { operation() }
        } finally { progress.cancel() }
    }
    fun pause(reason: String = "用户暂停", error: Boolean = false) {
        if (!state.value.active || state.value.status == VisitStatus.FINISH_CONFIRM) return
        chargeStep(); generation++; worker?.cancel(); worker = null
        driver.invalidatePrepared()
        mutable.value = state.value.copy(status = if (error) VisitStatus.ERROR else VisitStatus.PAUSED, message = reason, pendingSubmission = driver.pendingSubmission,
            step = driver.recoveryStep ?: state.value.step)
        debug(if (error) VisitDebugKind.ERROR else VisitDebugKind.PAUSE, reason = reason)
        if (error) onError(reason)
    }
    private fun fail(reason: String) {
        chargeStep()
        driver.invalidatePrepared()
        mutable.value = state.value.copy(status = VisitStatus.ERROR, message = reason, pendingSubmission = driver.pendingSubmission,
            step = driver.recoveryStep ?: state.value.step)
        debug(VisitDebugKind.ERROR, reason = reason)
        onError(reason)
    }
    fun finishTest() {
        if (state.value.status != VisitStatus.FINISH_CONFIRM) return
        onTestConfirmed()
        mutable.value = state.value.copy(status = VisitStatus.COMPLETED, message = "本轮测试已确认结束")
        driver.release()
        notifyCompleted()
    }
    private suspend fun <T> retryUndispatched(operation: suspend () -> T): T {
        var rechecks = 0
        while (true) {
            try { return operation() } catch (error: VisitReadPending) {
                if (!driver.waitsForControls) throw error
                mutable.value = state.value.copy(message = "${error.reason}；恢复后自动继续")
                delay(300)
            } catch (error: VisitPreActionMismatch) {
                // This type guarantees that nothing was sent. Reacquire the
                // current page, at most twice, within the existing step budget.
                if (driver.pendingSubmission) throw error
                if (!driver.waitsForControls && (rechecks++ >= 2 || error.reason !in PRE_ACTION_REFRESH)) throw error
                if (driver.waitsForControls && error.reason !in PRE_ACTION_REFRESH && error.reason !in DIRECT_WAIT_REASONS) throw error
                if (driver.waitsForControls) mutable.value = state.value.copy(message = "等待目标控件恢复，自动继续中")
                debug(VisitDebugKind.RECHECK, reason = error.reason)
                driver.invalidatePrepared()
                delay(150)
            }
        }
    }
    private fun notifyCompleted() { debug(VisitDebugKind.COMPLETE); runCatching { onCompleted() } }
    private fun debug(kind: VisitDebugKind, reason: String? = null, durationMs: Long? = null, waitMs: Long? = null) {
        val state = state.value
        diagnostics.record(VisitDebugEvent(kind, step = state.step, status = state.status, execution = state.execution,
            runMode = settings.mode, limit = settings.limit(state.execution), rounds = state.rounds,
            pending = driver.pendingSubmission, durationMs = durationMs, waitMs = waitMs, reason = reason))
    }
    fun stop() {
        if (state.value.active) debug(VisitDebugKind.STOP)
        generation++; worker?.cancel(); worker = null; stepSince = null; initialized = false
        driver.release()
        mutable.value = VisitState(message = if (driver.pendingSubmission) "已停止；存在未确认提交，下次仅核对结果" else "已停止", pendingSubmission = driver.pendingSubmission)
    }
    suspend fun clearRuntime(): Boolean {
        val previousWorker = worker
        stop()
        previousWorker?.join()
        val cleared = driver.clearRuntime()
        initialized = false; stepBudget = 30_000; stepSince = null
        mutable.value = VisitState(message = if (cleared) "运行缓存已清理，回到待办列表后可重新开始" else "运行状态清理失败，请重试",
            pendingSubmission = driver.pendingSubmission)
        return cleared
    }
}

private val PRE_ACTION_REFRESH = setOf("执行前页面发生变化，已暂停", "执行前界面不可读", "目标控件已失效",
    "执行前控件内容已变化", "执行前控件位置或状态已变化", "图像采样已过期，请重新识别",
    "图像识别期间页面结构变化，已暂停", "图像识别期间按钮移动，已暂停",
    "图像识别期间目标窗口变化，已暂停")
private val DIRECT_WAIT_REASONS = setOf("等待目标应用页面恢复", "等待目标控件恢复", "系统未接受走访控件点击，请查看后继续",
    "图像按钮识别不唯一或置信度不足，已暂停", "当前画面无法用于图像识别，已暂停")

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
) {
    private val mutable = MutableStateFlow(VisitState())
    val state: StateFlow<VisitState> = mutable
    private var settings = VisitSettings()
    private var worker: Job? = null
    private var generation = 0L
    private var gapLeft = 0L
    private var gapSince: Long? = null
    private var stepBudget = 30_000L
    private var stepSince: Long? = null
    private var initialized = false

    fun start(plan: VisitSettings, execution: VisitExecution) {
        if (state.value.active || worker?.isActive == true) return
        val problem = plan.error()
        if (problem != null) { mutable.value = VisitState(message = problem); onError(problem); return }
        settings = plan
        initialized = false; gapLeft = 0; gapSince = null; stepBudget = 30_000; stepSince = null
        mutable.value = VisitState(status = VisitStatus.RUNNING, execution = execution, message = "正在检查")
        launchWorker()
    }
    fun resume() {
        if (state.value.status !in setOf(VisitStatus.CHECKPOINT, VisitStatus.PAUSED, VisitStatus.ERROR) || worker?.isActive == true) return
        if (stepBudget <= 0) stepBudget = 30_000 // Explicit user retry, never an automatic retry.
        mutable.value = state.value.copy(status = VisitStatus.RUNNING, message = "重新核验当前页面")
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
                        runGap(token)
                        if (token != generation) return@launch
                        mutable.value = state.value.copy(step = VisitStep.FIND_LIST, status = VisitStatus.RUNNING, remainingWaitMs = 0)
                        stepBudget = 30_000
                        continue
                    }
                    stepSince = now()
                    val effect = withTimeout(stepBudget.coerceAtLeast(1)) {
                        driver.validate(step)
                        ensureActive()
                        driver.execute(step)
                    }
                    chargeStep()
                    ensureActive()
                    if (token != generation) return@launch
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
                            message = if (state.value.execution == VisitExecution.STEP_TEST) "本轮测试完成，请核验并结束测试" else "已完成 $rounds 轮",
                        )
                        return@launch
                    }
                    stepBudget = 30_000
                    if (next == VisitStep.GAP) gapLeft = settings.waitMs
                    if (state.value.execution == VisitExecution.STEP_TEST) {
                        mutable.value = state.value.copy(status = VisitStatus.CHECKPOINT, message = "已自动暂停，请核验本步结果")
                        return@launch
                    }
                    // Yield between steps, so pause/stop and foreground changes can be processed.
                    yield()
                }
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException && token == generation) fail("页面转换或核验超过 30 秒，请查看")
                else throw cancelled
            } catch (mismatch: VisitMismatch) {
                if (token == generation) fail(mismatch.reason)
            } catch (_: Exception) {
                if (token == generation) fail("当前步骤发生异常，已暂停；请查看页面")
            } finally {
                if (token == generation) { chargeStep(); freezeGap() }
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
    private suspend fun runGap(token: Long) {
        driver.validate(VisitStep.GAP)
        gapSince = now()
        mutable.value = state.value.copy(status = VisitStatus.WAITING, remainingWaitMs = gapLeft, message = "本轮已完成，等待下一轮")
        var lastCheck = now()
        while (token == generation && currentCoroutineContext().isActive) {
            val left = currentGap()
            mutable.value = state.value.copy(remainingWaitMs = left)
            if (left <= 0) break
            if (now() - lastCheck >= 500) { driver.validate(VisitStep.GAP); lastCheck = now() }
            delay(minOf(100, left))
        }
        freezeGap()
        driver.validate(VisitStep.GAP)
    }
    private fun currentGap() = (gapLeft - (gapSince?.let { (now() - it).coerceAtLeast(0) } ?: 0)).coerceAtLeast(0)
    private fun freezeGap() { gapLeft = currentGap(); gapSince = null }
    private fun chargeStep() { stepSince?.let { stepBudget = (stepBudget - (now() - it).coerceAtLeast(0)).coerceAtLeast(0) }; stepSince = null }
    fun pause(reason: String = "用户暂停", error: Boolean = false) {
        if (!state.value.active || state.value.status == VisitStatus.FINISH_CONFIRM) return
        freezeGap(); chargeStep(); generation++; worker?.cancel(); worker = null
        mutable.value = state.value.copy(status = if (error) VisitStatus.ERROR else VisitStatus.PAUSED, remainingWaitMs = gapLeft, message = reason, pendingSubmission = driver.pendingSubmission,
            step = driver.recoveryStep ?: state.value.step)
        if (error) onError(reason)
    }
    private fun fail(reason: String) {
        freezeGap(); chargeStep()
        mutable.value = state.value.copy(status = VisitStatus.ERROR, remainingWaitMs = gapLeft, message = reason, pendingSubmission = driver.pendingSubmission,
            step = driver.recoveryStep ?: state.value.step)
        onError(reason)
    }
    fun finishTest() {
        if (state.value.status != VisitStatus.FINISH_CONFIRM) return
        onTestConfirmed()
        mutable.value = state.value.copy(status = VisitStatus.COMPLETED, message = "本轮测试已确认结束")
        driver.release()
    }
    fun stop() {
        generation++; worker?.cancel(); worker = null; gapSince = null; gapLeft = 0; stepSince = null; initialized = false
        driver.release()
        mutable.value = VisitState(message = if (driver.pendingSubmission) "已停止；存在未确认提交，下次仅核对结果" else "已停止", pendingSubmission = driver.pendingSubmission)
    }
}
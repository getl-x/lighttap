package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

enum class RunStatus { IDLE, RUNNING, PAUSED, ERROR }
data class RuntimeState(
    val status: RunStatus = RunStatus.IDLE,
    val rounds: Long = 0,
    val elapsedMs: Long = 0,
    val nextIndex: Int = 0,
    val nextRepeat: Int = 0,
    val waitRemainingMs: Long = 0,
    val message: String = "准备就绪",
)
fun interface TapDispatcher { suspend fun tap(points: List<ClickPoint>): Boolean }

/** All commands run on the owning scope's dispatcher (Main on Android). */
class ClickEngine(
    private val scope: CoroutineScope,
    private val dispatcher: TapDispatcher,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onCompleted: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = mutableState
    private var config = ClickConfig()
    private var worker: Job? = null
    private var ticker: Job? = null
    private var generation = 0L
    private var revision = 0L
    private val wake = MutableStateFlow(0L)
    private var waitTarget: Long? = null
    private var accumulatedMs = 0L
    private var activeSince: Long? = null
    private fun elapsed() = accumulatedMs + (activeSince?.let { (now() - it).coerceAtLeast(0) } ?: 0)
    private fun freeze() { accumulatedMs = elapsed(); activeSince = null }

    fun start(plan: ClickConfig) {
        if (state.value.status == RunStatus.RUNNING || state.value.status == RunStatus.PAUSED) return
        require(plan.activePoints().isNotEmpty())
        config = plan
        accumulatedMs = 0
        activeSince = now()
        val token = ++generation
        mutableState.value = RuntimeState(status = RunStatus.RUNNING, message = "正在点击")
        ticker = scope.launch {
            while (isActive && token == generation) {
                awaitRunning()
                refreshTime()
                if (state.value.status == RunStatus.RUNNING && config.stopMode == StopMode.DURATION && elapsed() >= config.durationMs) {
                    finish("已达到运行时长")
                    break
                }
                val untilSecond = 1_000 - elapsed() % 1_000
                val untilEnd = if (config.stopMode == StopMode.DURATION) (config.durationMs - elapsed()).coerceAtLeast(1) else Long.MAX_VALUE
                waitOrWake(minOf(untilSecond, untilEnd))
            }
        }
        worker = scope.launch {
            try {
                waitActive(config.startDelayMs, revision, "启动倒计时")
                while (isActive && token == generation) {
                    awaitRunning()
                    val planNow = config
                    if (planNow.stopMode == StopMode.DURATION && elapsed() >= planNow.durationMs) {
                        finish("已达到运行时长")
                        break
                    }
                    if (planNow.stopMode == StopMode.ROUNDS && state.value.rounds >= planNow.roundLimit) {
                        finish("已完成 ${state.value.rounds} 轮")
                        break
                    }
                    val points = planNow.activePoints()
                    if (points.isEmpty()) { fail("没有启用的动作，请修改后重新开始"); break }
                    val index = state.value.nextIndex.coerceIn(0, points.lastIndex)
                    val version = revision
                    val taps = if (planNow.mode == ClickMode.SIMULTANEOUS) points else listOf(points[index])
                    mutableState.value = state.value.copy(message = "执行 ${index + 1}/${points.size}：${points[index].type.title}", waitRemainingMs = 0)
                    val success = if (taps.singleOrNull()?.type == ActionType.WAIT) {
                        waitActive(taps[0].durationMs, version, "等待动作"); true
                    } else dispatcher.tap(taps)
                    if (!success) {
                        // Allow an overlay pause/stop touch to reach Main before treating
                        // a cancelled gesture as an error. Never queue another gesture here.
                        delay(80)
                        if (token == generation && state.value.status == RunStatus.PAUSED) {
                            awaitRunning()
                            continue
                        }
                        if (token == generation) fail("点击未完成，请检查目标应用和控制条后重新开始")
                        break
                    }
                    ensureActive()
                    if (token != generation) break
                    // Editing while paused starts a fresh sequence with the edited points.
                    if (version != revision) continue
                    val repeated = state.value.nextRepeat + 1
                    val stepComplete = planNow.mode == ClickMode.SIMULTANEOUS || repeated >= points[index].repeatCount
                    val roundComplete = stepComplete && (planNow.mode != ClickMode.SEQUENCE || index == points.lastIndex)
                    val rounds = state.value.rounds + if (roundComplete) 1 else 0
                    mutableState.value = state.value.copy(
                        rounds = rounds, nextIndex = if (roundComplete) 0 else if (stepComplete) index + 1 else index,
                        nextRepeat = if (stepComplete) 0 else repeated, elapsedMs = elapsed(),
                    )
                    if (planNow.stopMode == StopMode.ROUNDS && rounds >= planNow.roundLimit) {
                        finish("已完成 $rounds 轮")
                        break
                    }
                    val gap = if (planNow.mode == ClickMode.SIMULTANEOUS) planNow.groupIntervalMs else points[index].intervalMs
                    waitActive(gap, version)
                    if (roundComplete && planNow.roundDelayMs > 0) waitActive(planNow.roundDelayMs, version, "下一轮前等待")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) fail("点击服务异常，请检查权限后重新开始")
            }
        }
    }
    private suspend fun awaitRunning() { state.first { it.status == RunStatus.RUNNING } }
    private suspend fun waitOrWake(ms: Long) {
        val signal = wake.value
        withTimeoutOrNull(ms) { wake.first { it != signal } }
    }
    private suspend fun waitActive(ms: Long, version: Long, label: String? = null) {
        val target = elapsed() + ms
        waitTarget = target
        try {
            while (currentCoroutineContext().isActive && version == revision) {
                awaitRunning()
                if (version != revision) return
                val remaining = target - elapsed()
                if (remaining <= 0) { mutableState.value = state.value.copy(waitRemainingMs = 0); return }
                if (label != null) mutableState.value = state.value.copy(waitRemainingMs = remaining, message = "$label ${(remaining + 999) / 1000} 秒")
                // Action deadlines stay exact; whole-second labels only wake when their text changes.
                waitOrWake(if (label == null) remaining else (remaining - 1) % 1_000 + 1)
            }
        } finally { waitTarget = null }
    }
    private fun refreshTime() { if (elapsed() - state.value.elapsedMs >= 100) mutableState.value = state.value.copy(elapsedMs = elapsed()) }
    fun pause() {
        if (state.value.status != RunStatus.RUNNING) return
        freeze()
        mutableState.value = state.value.copy(status = RunStatus.PAUSED, elapsedMs = accumulatedMs,
            waitRemainingMs = waitTarget?.let { (it - accumulatedMs).coerceAtLeast(0) } ?: 0, message = "已暂停，可调整点位")
        wake.value++
    }
    fun resume() {
        if (state.value.status != RunStatus.PAUSED) return
        activeSince = now()
        mutableState.value = state.value.copy(status = RunStatus.RUNNING, message = "正在点击")
        wake.value++
    }
    fun configure(plan: ClickConfig) {
        if (state.value.status == RunStatus.RUNNING || config == plan) return
        config = plan
        revision++
        wake.value++
        mutableState.value = state.value.copy(nextIndex = 0, nextRepeat = 0, waitRemainingMs = 0)
    }
    fun stop(message: String = "已停止") { end(RunStatus.IDLE, message, reset = true) }
    fun fail(message: String) { end(RunStatus.ERROR, message, reset = false) }
    private fun finish(message: String) {
        if (state.value.status !in setOf(RunStatus.RUNNING, RunStatus.PAUSED)) return
        end(RunStatus.IDLE, message, reset = false)
        runCatching { onCompleted() }
    }
    private fun end(status: RunStatus, message: String, reset: Boolean) {
        freeze()
        generation++
        worker?.cancel(); ticker?.cancel()
        worker = null; ticker = null
        mutableState.value = if (reset) RuntimeState(message = message) else state.value.copy(status = status, elapsedMs = accumulatedMs, message = message)
    }
}

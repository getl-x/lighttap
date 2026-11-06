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
    val message: String = "准备就绪",
)
fun interface TapDispatcher { suspend fun tap(points: List<ClickPoint>): Boolean }

/** All commands run on the owning scope's dispatcher (Main on Android). */
class ClickEngine(
    private val scope: CoroutineScope,
    private val dispatcher: TapDispatcher,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutableState = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = mutableState
    private var config = ClickConfig()
    private var worker: Job? = null
    private var ticker: Job? = null
    private var generation = 0L
    private var revision = 0L
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
                refreshTime()
                if (state.value.status == RunStatus.RUNNING && config.stopMode == StopMode.DURATION && elapsed() >= config.durationMs) {
                    finish("已达到运行时长")
                    break
                }
                delay(20)
            }
        }
        worker = scope.launch {
            try {
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
                    val index = state.value.nextIndex.coerceIn(0, points.lastIndex)
                    val version = revision
                    val taps = if (planNow.mode == ClickMode.SIMULTANEOUS) points else listOf(points[index])
                    if (!dispatcher.tap(taps)) {
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
                    val roundComplete = planNow.mode != ClickMode.SEQUENCE || index == points.lastIndex
                    val rounds = state.value.rounds + if (roundComplete) 1 else 0
                    mutableState.value = state.value.copy(
                        rounds = rounds, nextIndex = if (roundComplete) 0 else index + 1, elapsedMs = elapsed(),
                    )
                    if (planNow.stopMode == StopMode.ROUNDS && rounds >= planNow.roundLimit) {
                        finish("已完成 $rounds 轮")
                        break
                    }
                    val gap = if (planNow.mode == ClickMode.SIMULTANEOUS) planNow.groupIntervalMs else points[index].intervalMs
                    waitActive(gap, version)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) fail("点击服务异常，请检查权限后重新开始")
            }
        }
    }
    private suspend fun awaitRunning() { state.first { it.status == RunStatus.RUNNING } }
    private suspend fun waitActive(ms: Long, version: Long) {
        val target = elapsed() + ms
        while (currentCoroutineContext().isActive && version == revision) {
            awaitRunning()
            val remaining = target - elapsed()
            if (remaining <= 0) return
            delay(minOf(remaining, 20))
        }
    }
    private fun refreshTime() { if (elapsed() - state.value.elapsedMs >= 100) mutableState.value = state.value.copy(elapsedMs = elapsed()) }
    fun pause() {
        if (state.value.status != RunStatus.RUNNING) return
        freeze()
        mutableState.value = state.value.copy(status = RunStatus.PAUSED, elapsedMs = accumulatedMs, message = "已暂停，可调整点位")
    }
    fun resume() {
        if (state.value.status != RunStatus.PAUSED) return
        activeSince = now()
        mutableState.value = state.value.copy(status = RunStatus.RUNNING, message = "正在点击")
    }
    fun configure(plan: ClickConfig) {
        if (state.value.status == RunStatus.RUNNING || config == plan) return
        config = plan
        revision++
        mutableState.value = state.value.copy(nextIndex = 0)
    }
    fun stop(message: String = "已停止") { end(RunStatus.IDLE, message, reset = true) }
    fun fail(message: String) { end(RunStatus.ERROR, message, reset = false) }
    private fun finish(message: String) { end(RunStatus.IDLE, message, reset = false) }
    private fun end(status: RunStatus, message: String, reset: Boolean) {
        freeze()
        generation++
        worker?.cancel(); ticker?.cancel()
        worker = null; ticker = null
        mutableState.value = if (reset) RuntimeState(message = message) else state.value.copy(status = status, elapsedMs = accumulatedMs, message = message)
    }
}
package cn.lighttap.app

import kotlinx.coroutines.*

/** Main-thread owner of a read-only check. A cancelled check cannot publish into its replacement. */
internal class ReadOnlyProbe(
    private val scope: CoroutineScope,
    private val publish: (String) -> Unit,
    private val finished: (String) -> Unit,
    private val read: suspend () -> String,
) {
    private var generation = 0L
    private var job: Job? = null
    val active get() = job?.isActive == true

    fun start() {
        if (active || !scope.isActive) return
        val id = ++generation
        publish("正在只读检查，未执行操作")
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    withTimeout(5_000) { read() }
                } catch (_: TimeoutCancellationException) {
                    "只读检查超时，请待页面稳定后重试；未执行操作"
                } catch (e: VisitMismatch) {
                    e.reason
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    "当前界面无法可靠读取，未执行操作"
                }
                ensureActive()
                if (id == generation) { publish(result); finished(result) }
            } finally {
                if (id == generation) {
                    if (!isActive) publish("只读检查已取消，未执行操作")
                    job = null
                }
            }
        }
        job = next
        next.start()
    }

    fun stop() {
        val previous = job ?: return
        generation++
        job = null
        previous.cancel()
        publish("只读检查已取消，未执行操作")
    }
}

package cn.lighttap.app

enum class CompletionVibration(val title: String, val description: String, val timings: List<Long>) {
    OFF("关闭", "完成后不震动", emptyList()),
    SHORT("短震一下", "轻快提示", listOf(0, 180)),
    DOUBLE("短震两下", "两次短震，默认模式", listOf(0, 180, 140, 180)),
    TRIPLE("短震三下", "三次短震，更易察觉", listOf(0, 180, 140, 180, 140, 180)),
    LONG("长震一下", "持续震动 0.8 秒", listOf(0, 800));

    companion object {
        fun restored(value: String?): CompletionVibration = entries.firstOrNull { it.name == value } ?: DOUBLE
    }
}

/** One owner for all vibration sources. Ending a running reminder must never
 * cancel the completion vibration started in the same dispatcher turn. */
internal class RunVibration(
    private val now: () -> Long,
    private val play: (LongArray) -> Boolean,
    private val cancelDevice: () -> Unit,
) {
    private var priorityUntil = 0L
    private var completionUntil = 0L

    fun remind(): Boolean = if (now() < priorityUntil) false
        else play(longArrayOf(0, 180, 140, 180))

    fun remindBeforeBreak(): Boolean {
        if (now() < completionUntil) return false
        val pattern = longArrayOf(0, 450, 220, 450, 220, 450)
        if (!play(pattern)) return false
        priorityUntil = now() + pattern.sum()
        return true
    }

    fun complete(mode: CompletionVibration): Boolean {
        if (mode == CompletionVibration.OFF) return false
        if (!play(mode.timings.toLongArray())) return false
        completionUntil = now() + mode.timings.sum()
        priorityUntil = completionUntil
        return true
    }

    fun cancelRunReminder() {
        if (now() >= completionUntil) cancel()
    }

    fun cancel() {
        priorityUntil = 0
        completionUntil = 0
        cancelDevice()
    }
}

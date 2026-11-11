package cn.lighttap.app

enum class VisitRunMode { ONCE, COUNT, CONTINUOUS }
enum class VisitExecution { FORMAL, STEP_TEST }
enum class VisitRecognition(val title: String, val confirmationKey: String) {
    CONTROLS("控件识别（原方式）", "test_confirmed"), IMAGE("图像识别（备用）", "image_test_confirmed")
}
data class VisitSettings(val targetPackage: String = "", val mode: VisitRunMode = VisitRunMode.ONCE, val countText: String = "1", val waitText: String = DEFAULT_SUBMISSION_INTERVAL_SECONDS,
                         val recognition: VisitRecognition = VisitRecognition.CONTROLS) {
    fun error(): String? = when {
        targetPackage.isBlank() -> "请先绑定目标应用"
        !waitText.matches(Regex("[0-9]{1,5}")) || waitText.toLongOrNull() !in 61L..86_400L -> "提交间隔须为 61～86400 的整数秒"
        mode == VisitRunMode.COUNT && (!countText.matches(Regex("[0-9]{1,7}")) || countText.toLongOrNull() !in 1L..1_000_000L) -> "轮数须为 1～1000000 的整数"
        else -> null
    }
    val waitMs get() = waitText.toLong() * 1000
    fun limit(execution: VisitExecution): Long? = if (execution == VisitExecution.STEP_TEST || mode == VisitRunMode.ONCE) 1 else if (mode == VisitRunMode.COUNT) countText.toLong() else null
}
enum class VisitStep(val title: String, val expected: String) {
    FIND_LIST("识别待办列表", "列表稳定，顶部记录和走访按钮唯一明确"),
    OPEN_FORM("点击走访", "仅触发顶部记录的走访按钮一次"),
    WAIT_FORM("核验新增表单", "表单与所选记录一致"),
    FILL_ADDRESS("填写检查地址", "地址写入并回读一致"),
    LOCATE_DESCRIPTION("定位走访描述", "描述输入框可见，每次滚动独立确认"),
    FILL_DESCRIPTION("填写走访描述", "描述写入并回读一致"),
    VERIFY_FORM("提交前核验", "记录、两字段和正常状态均已核验"),
    SUBMIT("提交一次", "提交仅派发一次，结果待确认"),
    WAIT_LIST("核验返回列表", "表单退出，原顶部记录移除，列表稳定"),
    GAP("开始下一轮", "重新核验列表后准备下一条"),
}
enum class VisitStatus { IDLE, RUNNING, WAITING, CHECKPOINT, PAUSED, ERROR, FINISH_CONFIRM, COMPLETED }
data class VisitState(
    val status: VisitStatus = VisitStatus.IDLE,
    val execution: VisitExecution = VisitExecution.STEP_TEST,
    val step: VisitStep = VisitStep.FIND_LIST,
    val rounds: Long = 0,
    val remainingWaitMs: Long = 0,
    val lastStep: String = "尚未开始",
    val result: String = "",
    val message: String = "先完成只读检查，再逐步测试",
    val pendingSubmission: Boolean = false,
) {
    val active get() = status in setOf(VisitStatus.RUNNING, VisitStatus.WAITING, VisitStatus.CHECKPOINT, VisitStatus.PAUSED, VisitStatus.ERROR, VisitStatus.FINISH_CONFIRM)
    val busy get() = status == VisitStatus.RUNNING || status == VisitStatus.WAITING
    val nextLabel get() = if (step == VisitStep.SUBMIT) "继续：提交一次" else "继续下一步"
}
open class VisitMismatch(val reason: String) : Exception(reason)
/** A read was interrupted by WebView/window publication; no action has been sent. */
class VisitReadPending(reason: String) : VisitMismatch(reason)
class VisitNavigationRejected : VisitMismatch("系统未接受走访控件点击，请查看后继续")
/** Validation rejected the operation before any input was dispatched. */
class VisitPreActionMismatch(reason: String) : VisitMismatch(reason)
/** Only an inconclusive pixel classification can be re-observed; explicit abnormal states cannot. */
class VisitChoiceUncertain : VisitMismatch("选中样式不匹配或被遮挡，视觉核验不确定，已暂停")
data class VisitEffect(val repeat: Boolean = false, val message: String = "已核验", val emptyList: Boolean = false)
interface VisitDriver {
    val pendingSubmission: Boolean
    val recoveryStep: VisitStep? get() = if (pendingSubmission) VisitStep.WAIT_LIST else null
    fun submissionWaitMs(intervalMs: Long): Long = 0
    val managesSubmissionTiming: Boolean get() = false
    val submissionPreparationLeadMs: Long get() = 0
    suspend fun prepareSubmission() = Unit
    fun invalidatePrepared() = Unit
    suspend fun begin()
    suspend fun validate(step: VisitStep)
    suspend fun execute(step: VisitStep): VisitEffect
    suspend fun clearRuntime(): Boolean = true
    fun release()
}
fun countdown(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 999) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

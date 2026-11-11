package cn.lighttap.app

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

interface VisitSubmissionFence {
    val hash: String?
    val target: String?
    fun mark(hash: String, target: String): Boolean
    fun clear(): Boolean
    fun submissionWaitMs(intervalMs: Long): Long = 0
    fun recordSubmission(): Boolean = true
    val pendingClickMs: Long? get() = null
    val pendingAnchorMs: Long? get() = null
    fun recordClick(at: Long): Boolean = recordSubmission()
    fun confirmSubmission(at: Long): Boolean = recordSubmission()
}
// KTX edit returns Unit; these writes must return commit's durability result before any submission.
@android.annotation.SuppressLint("UseKtx")
internal class AndroidSubmissionFence(context: Context,
    private val store: SharedPreferences = context.getSharedPreferences("operation_fence", Context.MODE_PRIVATE),
) : VisitSubmissionFence {
    private val resolver = context.contentResolver
    private fun bootCount() = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, 0)
    // Older builds did not persist timing. Start one conservative cooldown on
    // upgrade, rather than trusting an unknown recent submission history.
    init { if (!store.contains("last_submit_elapsed")) recordSubmission() }
    override fun submissionWaitMs(intervalMs: Long): Long {
        if (!store.contains("last_submit_elapsed")) throw VisitMismatch("提交间隔记录无法保存，禁止提交")
        return submissionWaitMs(SubmissionStamp(store.getLong("last_submit_elapsed", 0), store.getInt("last_submit_boot", -1)), SystemClock.elapsedRealtime(), bootCount(), intervalMs)
    }
    override fun recordSubmission() = store.edit().putLong("last_submit_elapsed", SystemClock.elapsedRealtime()).putInt("last_submit_boot", bootCount()).commit()
    override val pendingClickMs get() = if (store.getInt("pending_click_boot", -1) == bootCount() && store.contains("pending_click_ms")) store.getLong("pending_click_ms", 0) else null
    override val pendingAnchorMs get() = if (store.getInt("pending_anchor_boot", -1) == bootCount() && store.contains("pending_anchor_ms")) store.getLong("pending_anchor_ms", 0) else null
    override fun recordClick(at: Long) = store.edit().putLong("pending_click_ms", at).putInt("pending_click_boot", bootCount())
        .putLong("last_submit_elapsed", at).putInt("last_submit_boot", bootCount()).commit()
    override fun confirmSubmission(at: Long): Boolean {
        val anchor = pendingAnchorMs ?: at
        return store.edit().putLong("pending_anchor_ms", anchor).putInt("pending_anchor_boot", bootCount())
            .putLong("last_submit_elapsed", anchor).putInt("last_submit_boot", bootCount()).commit()
    }
    override val hash get() = store.getString("pending_hash", null)
    override val target get() = store.getString("pending_package", null)
    override fun mark(hash: String, target: String) = store.edit().putString("pending_hash", hash).putString("pending_package", target)
        .remove("pending_click_ms").remove("pending_click_boot").remove("pending_anchor_ms").remove("pending_anchor_boot")
        // Pending identity itself blocks resubmission after process death. Only
        // a dispatched/uncertain click changes timing; a proven rejection must
        // not invent another 61-second cooldown.
        .commit()
    override fun clear(): Boolean {
        val keys = setOf("pending_hash", "pending_package", "pending_click_ms", "pending_click_boot", "pending_anchor_ms", "pending_anchor_boot")
        val previous = store.all.filterKeys { it in keys }
        val editor = store.edit()
        keys.forEach { editor.remove(it) }
        val saved = editor.commit()
        if (!saved) {
            // SharedPreferences changes its in-memory map even if disk commit
            // returns false. Restore the marker there too, so the live runner
            // cannot mistake failed persistence for a successfully cleared fence.
            val restore = store.edit()
            previous.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Long -> restore.putLong(key, value)
                is Int -> restore.putInt(key, value)
            } }
            restore.commit()
        }
        return saved
    }
}

/** Direct control execution. Missing controls wait automatically; an already
 * dispatched submission is never repeated while its return page is pending. */
class AndroidVisitDriver internal constructor(
    private val bridge: VisitUiAccess, private val fence: VisitSubmissionFence, private val now: () -> Long,
    private val diagnostics: VisitDiagnostics = VisitDiagnostics.NONE,
    private val prepareUi: suspend () -> Unit = {},
) : VisitDriver {
    constructor(service: ClickAccessibilityService, bridge: VisitUiAccess, diagnostics: VisitDiagnostics = VisitDiagnostics.NONE,
                prepareUi: suspend () -> Unit = {}) : this(bridge, AndroidSubmissionFence(service), { SystemClock.elapsedRealtime() }, diagnostics, prepareUi)
    override val pendingSubmission get() = fence.hash != null
    override val recoveryStep get() = if (pendingSubmission) VisitStep.WAIT_LIST else null
    override val managesSubmissionTiming = true
    override val waitsForControls = true
    override var waitingMessage: String? = null
        private set
    private var prepared: Pair<UiTree, UiNode>? = null
    var returnChecks = 0
        private set
    var lastConfirmation: ReturnConfirmation? = null
        private set
    override val submissionPreparationLeadMs get() = 1_000L
    override fun submissionWaitMs(intervalMs: Long) = fence.submissionWaitMs(intervalMs)
    override suspend fun begin() { invalidatePrepared(); returnChecks = 0; lastConfirmation = null }
    override fun invalidatePrepared() { prepared = null; waitingMessage = null; bridge.discardSubmit() }
    override fun release() = invalidatePrepared()
    override suspend fun clearRuntime(): Boolean {
        invalidatePrepared(); returnChecks = 0; lastConfirmation = null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fence.clear() }
    }
    override suspend fun validate(step: VisitStep) = Unit

    private suspend fun read(): UiTree? = try {
        kotlinx.coroutines.withTimeoutOrNull(5_000) { bridge.capture() }?.takeIf { it.packageName == bridge.target }
    } catch (_: VisitReadPending) { null }

    private suspend fun <T : Any> awaitControl(message: String, locate: (UiTree) -> T?): Pair<UiTree, T> {
        waitingMessage = message
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val tree = read()
            val control = tree?.let(locate)
            if (tree != null && control != null) { waitingMessage = null; return tree to control }
            delay(300)
        }
    }
    private suspend fun button(label: String) = awaitControl("等待${label}按钮；通知或弹窗结束后自动继续") { DirectVisitControls.button(it, label) }
    private suspend fun input(label: String) = awaitControl("等待${label}输入框；页面恢复后自动继续") { DirectVisitControls.field(it, label) }

    override suspend fun prepareSubmission() {
        invalidatePrepared()
        if (pendingSubmission) return
        prepareUi()
        val (tree, node) = button("提交")
        bridge.prepareSubmit(tree, node)
        prepared = tree to node
    }
    override suspend fun execute(step: VisitStep): VisitEffect {
        prepareUi()
        return when (step) {
            VisitStep.FIND_LIST -> {
                val (_, empty) = awaitControl("等待待办页和走访按钮；页面恢复后自动继续") { tree ->
                    if (!DirectVisitControls.pendingPage(tree)) null
                    else if (DirectVisitControls.button(tree, "走访") != null) false
                    else if (DirectVisitControls.empty(tree)) true else null
                }
                VisitEffect(message = if (empty) "待办已处理完" else "已读取走访按钮", emptyList = empty)
            }
            VisitStep.OPEN_FORM -> {
                val (tree, node) = button("走访")
                bridge.openVisit(tree, node)
                VisitEffect(message = "已点击走访")
            }
            VisitStep.WAIT_FORM -> {
                input("检查地址")
                VisitEffect(message = "已读取表单输入框")
            }
            VisitStep.FILL_ADDRESS -> fill("检查地址", VisitContract.ADDRESS)
            VisitStep.LOCATE_DESCRIPTION -> {
                val (tree, node) = input("走访描述")
                val screen = tree.nodes.firstOrNull()?.bounds
                val visible = node.visible && node.bounds.nonEmpty && (screen == null ||
                    node.bounds.top >= screen.top && node.bounds.bottom <= screen.bottom)
                if (visible) VisitEffect(message = "已读取走访描述输入框") else {
                    val scroll = tree.ancestors(node).firstOrNull { it.scrollable && it.visible && it.enabled }
                        ?: tree.nodes.firstOrNull { it.scrollable && it.visible && it.enabled }
                        ?: throw VisitReadPending("等待可滚动页面")
                    bridge.perform(tree, scroll, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    VisitEffect(repeat = true, message = "已滚动一次，继续定位输入框")
                }
            }
            VisitStep.FILL_DESCRIPTION -> fill("走访描述", VisitContract.DESCRIPTION)
            VisitStep.VERIFY_FORM -> { button("提交"); VisitEffect(message = "已读取提交按钮") }
            VisitStep.SUBMIT -> submit()
            VisitStep.WAIT_LIST -> {
                if (!pendingSubmission) throw VisitMismatch("缺少提交保护标记")
                if (fence.target != bridge.target) throw VisitMismatch("未确认提交属于其他目标应用")
                awaitControl("等待返回待办列表；不会重复提交") { tree ->
                    returnChecks++
                    val ready = DirectVisitControls.pendingPage(tree)
                    diagnostics.record(VisitDebugEvent(VisitDebugKind.RETURN_CHECK, step = step, attempt = returnChecks,
                        pending = true, normal = ready, ok = ready, reason = if (ready) null else "等待返回待办列表；不会重复提交"))
                    if (ready) true else null
                }
                val at = fence.pendingAnchorMs ?: now()
                if (!fence.confirmSubmission(at)) throw VisitMismatch("提交确认计时保存失败，保留保护标记")
                if (!fence.clear()) throw VisitMismatch("提交已核对，但保护标记未能清除；保持暂停")
                lastConfirmation = ReturnConfirmation(at, "已读取待办列表")
                diagnostics.record(VisitDebugEvent(VisitDebugKind.RETURN_CONFIRMED, step = step, pending = false, ok = true))
                VisitEffect(message = "已返回待办列表")
            }
            VisitStep.GAP -> VisitEffect()
        }
    }
    private suspend fun fill(label: String, value: String): VisitEffect {
        val (tree, node) = input(label)
        if (VisitContract(bridge.target).value(node) != value) bridge.perform(tree, node, AccessibilityNodeInfo.ACTION_SET_TEXT, value)
        return VisitEffect(message = "已填写$label")
    }
    private suspend fun submit(): VisitEffect {
        if (pendingSubmission) throw VisitMismatch("已有未确认提交，禁止再次提交")
        if (submissionWaitMs(MIN_SUBMISSION_INTERVAL_MS) > 0) throw VisitMismatch("距上次提交不足 61 秒，请等待后继续")
        val plan = prepared ?: run { prepareSubmission(); prepared!! }
        prepared = null
        if (!fence.mark(java.util.UUID.randomUUID().toString(), bridge.target)) throw VisitMismatch("无法保存提交保护标记，禁止提交")
        val start = now()
        var notSent = false
        try { bridge.perform(plan.first, plan.second, AccessibilityNodeInfo.ACTION_CLICK) }
        catch (error: VisitPreActionMismatch) {
            if (bridge.lastClickDispatchMs?.let { it >= start } == true) throw error
            notSent = true
            if (!fence.clear()) throw VisitMismatch("本次提交未派发，但保护标记清除失败；保持暂停")
            throw error
        } finally {
            if (!notSent) {
                val clicked = bridge.lastClickDispatchMs?.takeIf { it >= start }
                if (clicked != null) {
                    if (!fence.recordClick(clicked)) throw VisitMismatch("提交点击时间保存失败；保留保护标记并暂停")
                } else if (!fence.recordSubmission()) throw VisitMismatch("提交间隔记录保存失败；保留保护标记并暂停")
            }
        }
        return VisitEffect(message = "已提交一次，等待返回待办页")
    }
    suspend fun probe(): String {
        val tree = read() ?: return "等待目标应用页面恢复"
        return if (DirectVisitControls.field(tree, "检查地址") != null) "已读取表单输入框；未填写、未提交"
        else if (DirectVisitControls.button(tree, "走访") != null) "已读取走访按钮；未点击"
        else "等待目标控件显示"
    }
}

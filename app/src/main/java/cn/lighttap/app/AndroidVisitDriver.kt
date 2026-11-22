package cn.lighttap.app

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal const val VISIT_BUTTONS_MISSING = "未识别到同一行的走访记录和走访按钮"
private const val PENDING_LIST_POLL_MS = 1_000L
private const val PENDING_EMPTY_CONFIRM_MS = 5_000L
private const val PENDING_LIST_RECHECK_MS = 12_000L

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

class AndroidVisitDriver internal constructor(
    private val bridge: VisitUiAccess, private val vision: VisitChoiceVerifier, private val fence: VisitSubmissionFence,
    private val now: () -> Long,
    private val diagnostics: VisitDiagnostics = VisitDiagnostics.NONE,
    private val prepareUi: suspend () -> Unit = {},
) : VisitDriver {
    constructor(service: ClickAccessibilityService, bridge: VisitUiAccess, vision: VisitChoiceVerifier, diagnostics: VisitDiagnostics = VisitDiagnostics.NONE,
                prepareUi: suspend () -> Unit = {}) : this(
        bridge, vision, AndroidSubmissionFence(service), { SystemClock.elapsedRealtime() }, diagnostics, prepareUi)
    private val contract = VisitContract(bridge.target)
    private var formIdentity: VisitFormEvidence? = null
    private var lastReturnedRecord: String? = null
    private var returnedToList = false
    private var scrolls = 0
    private var opening = false
    private var openingAt: Long? = null
    private var retryOpening = false
    private var preparedSubmission: Pair<UiTree, Long>? = null
    private var preparationMs = 1_250L
    var lastConfirmation: ReturnConfirmation? = null
        private set
    var returnChecks = 0
        private set
    private val observations = VisitObservation(now, diagnostics, ::sameVisitPage) { bridge.capture() }
    override val recoveryStep: VisitStep? get() = when { pendingSubmission -> VisitStep.WAIT_LIST; retryOpening -> VisitStep.OPEN_FORM; opening -> VisitStep.WAIT_FORM; else -> null }
    override val pendingSubmission: Boolean get() = fence.hash != null
    override val managesSubmissionTiming = true
    override fun submissionWaitMs(intervalMs: Long) = fence.submissionWaitMs(intervalMs)
    override suspend fun begin() { release(); lastConfirmation = null; returnChecks = 0 }
    private suspend fun stable(initial: UiTree? = null): UiTree = observations.stable(initial)
    override val submissionPreparationLeadMs get() = (preparationMs + 250).coerceIn(500, 8_000)
    override fun invalidatePrepared() {
        preparedSubmission = null; bridge.discardSubmit()
    }
    override suspend fun prepareSubmission() {
        invalidatePrepared()
        if (pendingSubmission) throw VisitMismatch("已有未确认提交，禁止再次提交")
        val started = now()
        prepareUi()
        val tree = checkedForm()
        requireNewForm()
        contract.verifyValues(tree)
        val checkedAt = now()
        bridge.prepareSubmit(tree, contract.submit(tree))
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        preparedSubmission = tree to checkedAt
        preparationMs = (now() - started).coerceAtLeast(0)
    }
    private suspend fun submissionTree(): UiTree {
        val prepared = preparedSubmission
        if (prepared != null) {
            val fresh = observations.read()
            val age = now() - prepared.second
            if (age in 0..3_000 && sameVisitPage(prepared.first, fresh)) {
                checkForm(fresh)
                contract.verifyValues(fresh)
                if (bridge.submissionPrepared(fresh, contract.submit(fresh))) return fresh
            }
        }
        // Changed/expired evidence is never used to save time at the deadline.
        prepareSubmission()
        return preparedSubmission!!.first
    }
    override suspend fun validate(step: VisitStep) {
        // execute() obtains and validates a fresh snapshot before every action.
        // Reading here as well doubled traversals and inspected half-published
        // navigation pages before their dedicated transition wait could run.
        when (step) {
            VisitStep.WAIT_LIST -> {
                if (!pendingSubmission) throw VisitMismatch("缺少提交结果核验标记，不能计数")
            }
            VisitStep.GAP -> {
                if (pendingSubmission) throw VisitMismatch("仍有未确认提交，禁止开始下一轮")
                if (!returnedToList) throw VisitMismatch("缺少上一轮结果确认，不能开始下一轮")
            }
            else -> Unit
        }
    }
    override suspend fun execute(step: VisitStep): VisitEffect {
        prepareUi()
        return when (step) {
        VisitStep.FIND_LIST -> {
            retryOpening = false
            if (pendingSubmission) throw VisitMismatch("仍有未确认提交，禁止开始下一轮")
            val empty = pendingListEmpty(observations.read())
            diagnostics.record(VisitDebugEvent(VisitDebugKind.LIST, step = step, records = if (empty) 0 else null, ok = true))
            formIdentity = null
            scrolls = 0
            VisitEffect(message = if (empty) "已复核，待办暂未显示记录" else "已识别走访记录和走访按钮", emptyList = empty)
        }
        VisitStep.OPEN_FORM -> {
            val tree = observations.read()
            val button = contract.visitButton(tree)
            val image = if (button == null) bridge.findVisitImage(tree) else null
            if (button == null && image == null) throw VisitMismatch(VISIT_BUTTONS_MISSING)
            formIdentity = null
            scrolls = 0
            retryOpening = false; opening = true; openingAt = now()
            try {
                if (button != null) openVisitControl(tree, button)
                else bridge.openVisitImage(tree, checkNotNull(image))
            } catch (e: VisitMismatch) {
                if (e is VisitNavigationRejected || e is VisitPreActionMismatch) { opening = false; retryOpening = true }
                throw e
            }
            VisitEffect(message = "走访已触发一次；下一步核验表单")
        }
        VisitStep.WAIT_FORM -> {
            val form = waitForForm()
            if (formIdentity == null) formIdentity = contract.formEvidence(form)
            verifyNormal(form)
            requireNewForm()
            opening = false
            openingAt = null
            retryOpening = false
            VisitEffect(message = "已核验当前表单、字段和正常状态")
        }
        VisitStep.FILL_ADDRESS -> fill("检查地址", VisitContract.ADDRESS)
        VisitStep.LOCATE_DESCRIPTION -> {
            val tree = checkedForm()
            val field = contract.field(tree, "走访描述")
            if (fullyVisible(tree, field)) VisitEffect(message = "描述输入框已可见，尚未填写")
            else {
                if (scrolls >= 8) {
                    scrolls = 0
                    throw VisitMismatch("多次滚动后仍未定位描述框，停止适配")
                }
                val scroll = tree.ancestors(field).firstOrNull { it.scrollable && it.enabled && it.visible }
                    ?: tree.nodes.singleOrNull {
                        field.bounds.nonEmpty && it.scrollable && it.enabled && it.visible && it.bounds.intersects(field.bounds)
                    }
                    ?: throw VisitMismatch("找不到与描述框关联的可滚动控件")
                bridge.perform(tree, scroll, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                scrolls++
                VisitEffect(repeat = true, message = "仅滚动一次；请核验，下次继续重新定位")
            }
        }
        VisitStep.FILL_DESCRIPTION -> fill("走访描述", VisitContract.DESCRIPTION)
        VisitStep.VERIFY_FORM -> {
            val tree = checkedForm(); contract.verifyValues(tree); contract.submit(tree)
            VisitEffect(message = "记录、两字段及正常状态均一致；尚未提交")
        }
        VisitStep.SUBMIT -> {
            if (submissionWaitMs(MIN_SUBMISSION_INTERVAL_MS) > 0) throw VisitMismatch("距上次提交不足 61 秒，请等待后继续")
            val tree = submissionTree(); contract.verifyValues(tree)
            if (pendingSubmission) throw VisitMismatch("已有未确认提交，禁止再次提交")
            val button = contract.submit(tree)
            requireNewForm()
            val identity = formIdentity?.pendingKey ?: throw VisitMismatch("缺少记录核验信息")
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            // Recheck the fence immediately before marking: no preparation or
            // waiting path is allowed to move the submission deadline earlier.
            if (submissionWaitMs(MIN_SUBMISSION_INTERVAL_MS) > 0) throw VisitMismatch("距上次提交不足 61 秒，请等待后继续")
            preparedSubmission = null
            // Persist a non-reversible record digest before dispatch. Survives stop/process death.
            if (!fence.mark(identity, bridge.target)) throw VisitMismatch("无法保存提交保护标记，禁止提交")
            lastConfirmation = null; returnChecks = 0; returnedToList = false
            val attemptStart = now()
            var provenUndispatched = false
            try { bridge.perform(tree, button, AccessibilityNodeInfo.ACTION_CLICK) }
            catch (error: VisitPreActionMismatch) {
                // Only this explicit bridge guarantee permits rollback. A lost
                // callback, rejected/uncertain gesture or cancellation retains
                // its fence and is observed without another submit.
                if (bridge.lastClickDispatchMs?.let { it >= attemptStart } == true) throw error
                provenUndispatched = true
                if (fence.hash != identity || fence.target != bridge.target || fence.pendingClickMs != null || !fence.clear())
                    throw VisitMismatch("本次提交未派发，但保护标记清除失败；保持暂停")
                throw error
            }
            finally {
                if (!provenUndispatched) {
                    val clicked = bridge.lastClickDispatchMs?.takeIf { it >= attemptStart }
                    if (clicked != null) {
                        if (!fence.recordClick(clicked)) throw VisitMismatch("提交点击时间保存失败；保留保护标记并暂停")
                    } else if (!fence.recordSubmission()) throw VisitMismatch("提交间隔记录保存失败；保留保护标记并暂停")
                }
            }
            VisitEffect(message = "提交已触发一次，结果待确认；不会重复提交")
        }
        VisitStep.WAIT_LIST -> {
            val at = waitForList()
            val returnedRecord = fence.hash?.takeIf { it.startsWith("record:") }
            if (!fence.confirmSubmission(at)) throw VisitMismatch("提交确认计时保存失败，保留保护标记")
            if (!fence.clear()) throw VisitMismatch("提交已核对，但保护标记未能清除；保持暂停")
            lastConfirmation = ReturnConfirmation(at, "已返回待办页")
            lastReturnedRecord = returnedRecord
            returnedToList = true
            formIdentity = null
            diagnostics.record(VisitDebugEvent(VisitDebugKind.RETURN_CONFIRMED, step = step, pending = false, ok = true))
            VisitEffect(message = "已返回待办页")
        }
        VisitStep.GAP -> VisitEffect()
        }
    }
    private suspend fun verifyNormal(tree: UiTree): UiTree {
        var current = tree
        repeat(3) { attempt ->
            checkFormIdentity(current)
            val state = contract.normalState(current)
            diagnostics.record(VisitDebugEvent(VisitDebugKind.CHOICE_CHECK, choice = state, attempt = attempt + 1))
            when (state) {
                ChoiceResult.ABNORMAL -> throw VisitMismatch("户籍状态异常，禁止继续")
                ChoiceResult.NORMAL -> { checkForm(current); return current }
                ChoiceResult.UNKNOWN -> Unit
            }
            val normal = contract.choiceButton(current, "正常")
            val abnormal = contract.choiceButton(current, "异常")
            try {
                vision.requireNormal(normal, abnormal)
            } catch (e: VisitChoiceUncertain) {
                diagnostics.record(VisitDebugEvent(VisitDebugKind.CHOICE_CHECK, choice = ChoiceResult.UNKNOWN, attempt = attempt + 1, reason = e.reason))
                if (attempt == 2) throw e
                delay(350)
                current = stable()
                return@repeat
            }
            diagnostics.record(VisitDebugEvent(VisitDebugKind.CHOICE_CHECK, choice = ChoiceResult.NORMAL, attempt = attempt + 1, ok = true))
            val observed = observations.read()
            checkForm(observed)
            // Ignore unrelated WebView changes, but never reuse a visual result
            // if the window or either choice control has moved/changed.
            if (observed.windowId == current.windowId && contract.choiceButton(observed, "正常") == normal && contract.choiceButton(observed, "异常") == abnormal) return observed
            current = stable()
        }
        throw VisitMismatch("状态控件持续变化，请查看后继续")
    }
    private suspend fun checkedForm(): UiTree {
        val tree = stable()
        return verifyNormal(tree)
    }
    private fun checkFormIdentity(tree: UiTree) {
        val expected = formIdentity ?: run { contract.formIdentity(tree, null); return }
        val current = contract.formEvidence(tree)
        if (!expected.matches(current)) throw VisitMismatch("运行期间表单记录已变化，已暂停")
        if (expected.addressHash == null && current.addressHash != null) formIdentity = current
    }
    private fun requireNewForm() {
        val current = formIdentity?.recordKey ?: return
        if (current == lastReturnedRecord) throw VisitMismatch("仍是上一条走访记录，请返回待办刷新后继续")
    }
    private fun checkForm(tree: UiTree) {
        checkFormIdentity(tree)
        contract.form(tree, null, visualFallback = true)
        contract.allowedFields(tree)
    }
    private fun fullyVisible(tree: UiTree, field: UiNode): Boolean {
        if (!field.visible || !field.bounds.nonEmpty) return false
        val box = field.bounds
        return tree.ancestors(field).drop(1).filter { it.visible && it.bounds.nonEmpty }.all { parent ->
            box.left >= parent.bounds.left && box.top >= parent.bounds.top && box.right <= parent.bounds.right && box.bottom <= parent.bounds.bottom
        }
    }
    private suspend fun fill(label: String, value: String): VisitEffect {
        val tree = checkedForm()
        val field = contract.field(tree, label)
        if (!fullyVisible(tree, field)) throw VisitMismatch("输入框未完整显示，已暂停且未输入")
        val before = contract.value(field)
        if (before.isNotEmpty() && before != value) throw VisitMismatch("已有字段内容冲突，禁止覆盖")
        if (before != value) bridge.perform(tree, field, AccessibilityNodeInfo.ACTION_SET_TEXT, value)
        // ACTION_SET_TEXT can acknowledge before the WebView publishes its updated node.
        // Observe only; never dispatch the write again or accept a different nonempty value.
        var remainingChecks = 12
        var updated: UiTree
        while (true) {
            val observed = observations.read(); checkForm(observed)
            val actual = contract.fieldValue(observed, label)
            if (actual == value) { updated = observed; break }
            if (actual.isNotEmpty() || --remainingChecks <= 0) throw VisitMismatch("填写后回读不一致，禁止继续")
            delay(100)
        }
        val after = stable(updated); checkForm(after)
        if (contract.fieldValue(after, label) != value) throw VisitMismatch("填写后回读不一致，禁止继续")
        return VisitEffect(message = "本字段填写并回读通过；未执行其他字段操作")
    }
    private suspend fun hasVisitButtons(tree: UiTree): Boolean =
        contract.visitButton(tree) != null || bridge.findVisitImage(tree) != null

    private suspend fun pendingListEmpty(initial: UiTree): Boolean {
        // A button pair or image can be visible before WebView exposes the tabs.
        if (!contract.pendingListSelected(initial)) {
            if (hasVisitButtons(initial)) return false
            throw VisitMismatch(VISIT_BUTTONS_MISSING)
        }
        val started = now()
        var tree: UiTree? = initial
        var emptySince: Pair<Int, Long>? = null
        var attempts = 0
        // A WebView can publish its empty placeholder before the pending rows.
        while (now() - started < PENDING_LIST_RECHECK_MS) {
            attempts++
            val page = tree
            if (page != null && contract.emptyPendingList(page)) {
                val at = now()
                val since = emptySince?.takeIf { it.first == page.windowId }?.second ?: at
                emptySince = page.windowId to since
                if (at - since >= PENDING_EMPTY_CONFIRM_MS) return bridge.findVisitImage(page) == null
                diagnostics.record(VisitDebugEvent(VisitDebugKind.LIST, step = VisitStep.FIND_LIST,
                    attempt = attempts, durationMs = at - since, waitMs = PENDING_EMPTY_CONFIRM_MS, ok = false,
                    reason = "检测到待办空状态，等待复核"))
            } else {
                emptySince = null
                if (page != null && contract.pendingListSelected(page) && !contract.loading(page) && hasVisitButtons(page)) return false
            }
            delay(PENDING_LIST_POLL_MS)
            tree = try { bridge.capture() } catch (_: VisitReadPending) { null }
        }
        throw VisitMismatch("待办列表仍在刷新或无法确认，请刷新后继续")
    }

    private suspend fun openVisitControl(tree: UiTree, button: UiNode) {
        try { bridge.openVisit(tree, button) }
        catch (rejected: VisitNavigationRejected) {
            // The system explicitly rejected the first click.
            opening = false
            retryOpening = true
            val fresh = observations.read()
            if (fresh.packageName != tree.packageName || fresh.windowId != tree.windowId || fresh.has("新增")) throw rejected
            val image = bridge.findVisitImage(fresh) ?: throw rejected
            opening = true
            retryOpening = false
            openingAt = now()
            bridge.openVisitImage(fresh, image)
        }
    }

    private suspend fun waitForList(): Long {
        if (fence.hash == null) throw VisitMismatch("缺少提交保护标记")
        if (fence.target != bridge.target) throw VisitMismatch("未确认提交属于其他目标应用")
        while (true) {
            val started = now()
            returnChecks++
            val tree = try { bridge.capture() } catch (_: VisitReadPending) { null }
            // The empty placeholder can establish that navigation returned to pending.
            // FIND_LIST independently checks whether the rows have finished publishing.
            val ready = tree != null && !contract.loading(tree) && (contract.emptyPendingList(tree) || hasVisitButtons(tree))
            diagnostics.record(VisitDebugEvent(VisitDebugKind.RETURN_CHECK, step = VisitStep.WAIT_LIST, attempt = returnChecks,
                pending = true, durationMs = now() - started, normal = ready, ok = ready,
                reason = if (ready) null else VISIT_BUTTONS_MISSING))
            if (ready) return fence.pendingAnchorMs ?: now()
            delay((1_000 - (now() - started)).coerceAtLeast(80))
        }
    }

    private suspend fun waitForForm(): UiTree {
        val waitStarted = openingAt ?: now().also { openingAt = it }
        while (true) {
            val tree = observations.read()
            val form = tree.has("新增")
            val loading = contract.loading(tree)
            diagnostics.record(VisitDebugEvent(VisitDebugKind.NAVIGATION_CHECK, step = VisitStep.WAIT_FORM,
                durationMs = now() - waitStarted, form = form, loading = loading))
            if (form && !loading) {
                val ready = stable(tree)
                if (formPublished(ready)) return ready
            } else if (!loading && now() - waitStarted >= 5_000 && hasVisitButtons(tree)) {
                opening = false
                retryOpening = true
                throw VisitMismatch("走访点击后仍在待办页，未打开表单；继续将重新识别并点击走访")
            }
            delay(100)
        }
    }

    private fun formPublished(tree: UiTree): Boolean = try {
        tree.has("新增") && tree.has("走访记录") && !contract.loading(tree) &&
            tree.has("正常") && tree.has("异常")
    } catch (_: VisitMismatch) { false }
    suspend fun probe(): String {
        prepareUi()
        val tree = observations.read()
        if (tree.has("新增")) { verifyNormal(stable(tree)); return "表单控件可读；未填写、未提交" }
        if (pendingListEmpty(tree)) return "待办为空；未点击"
        return "走访记录和走访按钮可读；未点击"
    }
    override fun release() { invalidatePrepared(); formIdentity = null; lastReturnedRecord = null; returnedToList = false; scrolls = 0; opening = false; openingAt = null; retryOpening = false }
    override suspend fun clearRuntime(): Boolean {
        release(); lastConfirmation = null; returnChecks = 0
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fence.clear() }
    }
}

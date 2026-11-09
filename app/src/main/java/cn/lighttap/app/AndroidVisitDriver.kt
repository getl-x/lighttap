package cn.lighttap.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

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
private class AndroidSubmissionFence(context: Context) : VisitSubmissionFence {
    private val store = context.getSharedPreferences("operation_fence", Context.MODE_PRIVATE)
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
        .putLong("last_submit_elapsed", SystemClock.elapsedRealtime()).putInt("last_submit_boot", bootCount()).commit()
    override fun clear() = store.edit().remove("pending_hash").remove("pending_package")
        .remove("pending_click_ms").remove("pending_click_boot").remove("pending_anchor_ms").remove("pending_anchor_boot").commit()
}

class AndroidVisitDriver internal constructor(
    private val bridge: VisitUiAccess, private val vision: VisitChoiceVerifier, private val fence: VisitSubmissionFence,
    private val returnVision: VisitReturnVision, private val now: () -> Long,
    private val successHint: (Long?, Long) -> Boolean = { _, _ -> false },
    private val diagnostics: VisitDiagnostics = VisitDiagnostics.NONE,
) : VisitDriver {
    constructor(service: ClickAccessibilityService, bridge: VisitUiAccess, vision: VisitChoiceVerifier, diagnostics: VisitDiagnostics = VisitDiagnostics.NONE) : this(
        bridge, vision, AndroidSubmissionFence(service), AndroidReturnVision(service), { SystemClock.elapsedRealtime() },
        { clicked, time -> service.visitSuccessSignals.since(clicked, time) }, diagnostics)
    private val contract = VisitContract(bridge.target)
    private var selected: VisitRecord? = null
    private var confirmedList: String? = null
    private var confirmedCount: Int? = null
    private var confirmedTop: String? = null
    private var scrolls = 0
    private var opening = false
    private var returnObservation: VisitReturnObservation? = null
    var lastConfirmation: ReturnConfirmation? = null
        private set
    var returnChecks = 0
        private set
    private val observations = VisitObservation(now, diagnostics) { bridge.capture() }
    override val recoveryStep: VisitStep? get() = when { pendingSubmission -> VisitStep.WAIT_LIST; opening -> VisitStep.WAIT_FORM; else -> null }
    override val pendingSubmission: Boolean get() = fence.hash != null
    override val managesSubmissionTiming = true
    override fun submissionWaitMs(intervalMs: Long) = fence.submissionWaitMs(intervalMs)
    private fun pendingHash() = fence.hash
    override suspend fun begin() { selected = null; confirmedList = null; confirmedCount = null; confirmedTop = null; scrolls = 0; opening = false; returnObservation = null }
    private suspend fun stable(): UiTree = observations.stable()
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
                if (confirmedList == null) throw VisitMismatch("缺少上一轮结果确认，不能开始下一轮")
                // The confirmed list belongs to the completed submission. The
                // next FIND_LIST selects from a fresh validated page, and OPEN_FORM
                // still rejects any change to that newly selected record.
            }
            else -> Unit
        }
    }
    override suspend fun execute(step: VisitStep): VisitEffect = when (step) {
        VisitStep.FIND_LIST -> {
            val tree = stable()
            val records = contract.records(tree)
            val signature = if (records.isEmpty()) "empty" else records.joinToString("|") { it.fingerprint }
            diagnostics.record(VisitDebugEvent(VisitDebugKind.LIST, step = step, records = records.size,
                previousRecords = confirmedCount, listChanged = confirmedList?.let { it != signature },
                topUnchanged = confirmedList?.let { confirmedTop == records.firstOrNull()?.fingerprint }, pending = pendingSubmission))
            if (records.isEmpty()) {
                if (pendingSubmission) throw VisitMismatch("仍有未确认提交，不能以空列表结束")
                val frame = returnVision.inspect(tree)
                diagnostics.record(VisitDebugEvent(VisitDebugKind.RETURN_CHECK, step = step, records = 0, visual = frame.state))
                if (frame.state != ReturnVisual.CLEAR || contract.records(stable()).isNotEmpty()) throw VisitMismatch("空列表画面尚未稳定，请查看后继续")
                VisitEffect(message = "待办已处理完", emptyList = true)
            } else {
                selected = records.first()
                if (selected?.fingerprint == pendingHash()) throw VisitMismatch("本条存在未确认提交，禁止再次处理")
                scrolls = 0
                VisitEffect(message = "顶部记录与按钮已唯一识别；尚未点击")
            }
        }
        VisitStep.OPEN_FORM -> {
            val tree = stable()
            val top = contract.top(tree)
            if (top.fingerprint != selected?.fingerprint) throw VisitMismatch("顶部记录变化，禁止点击")
            opening = true
            bridge.perform(tree, tree.node(top.actionPath), AccessibilityNodeInfo.ACTION_CLICK)
            VisitEffect(message = "走访已触发一次；下一步核验表单")
        }
        VisitStep.WAIT_FORM -> {
            val form = waitTransition(form = true)
            verifyNormal(form)
            opening = false
            VisitEffect(message = "表单与所选记录、字段及正常状态核验通过")
        }
        VisitStep.FILL_ADDRESS -> fill("检查地址", VisitContract.ADDRESS)
        VisitStep.LOCATE_DESCRIPTION -> {
            val tree = checkedForm()
            val field = contract.field(tree, "走访描述")
            if (fullyVisible(tree, field)) VisitEffect(message = "描述输入框已可见，尚未填写")
            else {
                if (scrolls >= 8) throw VisitMismatch("多次滚动后仍未定位描述框，停止适配")
                val scroll = tree.ancestors(field).firstOrNull { it.scrollable && it.enabled && it.visible }
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
            val tree = checkedForm(); contract.verifyValues(tree)
            if (pendingSubmission) throw VisitMismatch("已有未确认提交，禁止再次提交")
            val button = contract.submit(tree)
            val record = selected ?: throw VisitMismatch("缺少记录核验信息")
            // Persist a non-reversible record digest before dispatch. Survives stop/process death.
            if (!fence.mark(record.fingerprint, bridge.target)) throw VisitMismatch("无法保存提交保护标记，禁止提交")
            lastConfirmation = null; returnObservation = null; returnChecks = 0
            val attemptStart = now()
            try { bridge.perform(tree, button, AccessibilityNodeInfo.ACTION_CLICK) }
            finally {
                val clicked = bridge.lastClickDispatchMs?.takeIf { it >= attemptStart }
                if (clicked != null) {
                    if (!fence.recordClick(clicked)) throw VisitMismatch("提交点击时间保存失败；保留保护标记并暂停")
                } else if (!fence.recordSubmission()) throw VisitMismatch("提交间隔记录保存失败；保留保护标记并暂停")
            }
            VisitEffect(message = "提交已触发一次，结果待确认；不会重复提交")
        }
        VisitStep.WAIT_LIST -> {
            val tree = waitForSubmission()
            val records = contract.records(tree)
            val old = pendingHash() ?: throw VisitMismatch("缺少提交保护标记")
            if (fence.target != bridge.target) throw VisitMismatch("未确认提交属于其他目标应用")
            if (records.any { it.fingerprint == old }) throw VisitMismatch("原记录仍在列表，提交结果不明确")
            confirmedList = contract.listSignature(tree)
            confirmedCount = records.size
            confirmedTop = records.firstOrNull()?.fingerprint
            val confirmation = lastConfirmation ?: throw VisitMismatch("缺少提交后正常页面核验")
            if (!fence.confirmSubmission(confirmation.anchorMs)) throw VisitMismatch("提交确认计时保存失败，保留保护标记")
            if (!fence.clear()) throw VisitMismatch("提交已核对，但保护标记未能清除；保持暂停")
            selected = null
            diagnostics.record(VisitDebugEvent(VisitDebugKind.RETURN_CONFIRMED, step = step, records = records.size,
                pending = pendingSubmission, durationMs = (now() - confirmation.anchorMs).coerceAtLeast(0), ok = true))
            VisitEffect(message = "${confirmation.method}，原记录已移除；间隔已从首次有效时刻计算")
        }
        VisitStep.GAP -> VisitEffect()
    }
    private suspend fun verifyNormal(tree: UiTree): UiTree {
        var current = tree
        repeat(3) { attempt ->
            contract.formIdentity(current, selected)
            val state = contract.normalState(current)
            diagnostics.record(VisitDebugEvent(VisitDebugKind.CHOICE_CHECK, choice = state, attempt = attempt + 1))
            when (state) {
                ChoiceResult.ABNORMAL -> throw VisitMismatch("户籍状态异常，禁止继续")
                ChoiceResult.NORMAL -> { checkFormFields(current); return current }
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
            checkFormFields(observed)
            // Ignore unrelated WebView changes, but never reuse a visual result
            // if the window or either choice control has moved/changed.
            if (observed.windowId == current.windowId && contract.choiceButton(observed, "正常") == normal && contract.choiceButton(observed, "异常") == abnormal) return observed
            current = stable()
        }
        throw VisitMismatch("状态控件持续变化，请查看后继续")
    }
    private suspend fun checkedForm(): UiTree {
        val tree = stable()
        contract.formIdentity(tree, selected ?: throw VisitMismatch("缺少本轮记录，不能操作"))
        return verifyNormal(tree)
    }
    private fun checkFormFields(tree: UiTree) {
        contract.form(tree, selected, visualFallback = true)
        contract.allowedFields(tree)
    }
    private fun checkForm(tree: UiTree) {
        contract.form(tree, selected ?: throw VisitMismatch("缺少本轮记录，不能操作"), visualFallback = true)
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
        while (true) {
            val observed = observations.read(); checkForm(observed)
            val actual = contract.fieldValue(observed, label)
            if (actual == value) break
            if (actual.isNotEmpty() || --remainingChecks <= 0) throw VisitMismatch("填写后回读不一致，禁止继续")
            delay(100)
        }
        val after = stable(); checkForm(after)
        if (contract.fieldValue(after, label) != value) throw VisitMismatch("填写后回读不一致，禁止继续")
        return VisitEffect(message = "本字段填写并回读通过；未执行其他字段操作")
    }
    private suspend fun waitForSubmission(): UiTree {
        val old = pendingHash() ?: throw VisitMismatch("缺少提交保护标记")
        if (fence.target != bridge.target) throw VisitMismatch("未确认提交属于其他目标应用")
        val observer = returnObservation ?: VisitReturnObservation(fence.pendingClickMs).also { returnObservation = it }
        while (true) {
            val started = now()
            returnChecks++
            val tree = try { bridge.capture() } catch (e: VisitReadPending) {
                diagnostics.record(VisitDebugEvent(VisitDebugKind.RETURN_CHECK, step = VisitStep.WAIT_LIST,
                    attempt = returnChecks, pending = true, durationMs = now() - started, ok = false, reason = e.reason))
                observer.observe(now(), false, false, null, false)
                delay((1_000 - (now() - started)).coerceAtLeast(80)); continue
            }
            contract.target(tree)
            var loading = contract.loading(tree)
            var signature: String? = null
            var normal = false
            var observedAt = now()
            var recordCount: Int? = null
            var oldPresent: Boolean? = null
            var visual: ReturnVisual? = null
            var reason: String? = null
            if (!loading && tree.has("待办") && !tree.has("新增")) {
                val records = try { contract.records(tree) } catch (e: VisitMismatch) { reason = e.reason; null }
                if (records != null) {
                    recordCount = records.size
                    oldPresent = records.any { it.fingerprint == old }
                    val frame = try { returnVision.inspect(tree) } catch (e: VisitReadPending) { reason = e.reason; ReturnFrame(ReturnVisual.UNKNOWN, now()) }
                    visual = frame.state
                    observedAt = frame.capturedAt
                    loading = frame.state == ReturnVisual.BLOCKED
                    normal = frame.state == ReturnVisual.CLEAR && !oldPresent
                    if (normal) signature = contract.listSignature(tree)
                }
            } else if (!loading && tree.has("新增")) {
                // Known submitting form is allowed while awaiting a response.
                contract.form(tree, selected, visualFallback = true)
                if (contract.formFingerprint(tree) != old) throw VisitMismatch("待核验提交与当前表单记录不一致")
                contract.verifyValues(tree)
            } else if (!loading && !tree.has("待办")) {
                // A short partially published page is only observed; the outer
                // result deadline bounds it. Never dispatch an action here.
                normal = false
            }
            val hint = successHint(fence.pendingClickMs, now()) || tree.nodes.any { it.visible && isVisitSuccess(it.text) }
            diagnostics.record(VisitDebugEvent(VisitDebugKind.RETURN_CHECK, step = VisitStep.WAIT_LIST, attempt = returnChecks,
                pending = true, durationMs = now() - started, records = recordCount, oldPresent = oldPresent,
                visual = visual, loading = loading, normal = normal, successHint = hint, reason = reason))
            val confirmed = observer.observe(observedAt, normal, loading, signature, hint)
            if (confirmed != null) {
                lastConfirmation = confirmed.copy(anchorMs = fence.pendingAnchorMs ?: confirmed.anchorMs)
                return tree
            }
            // Start-to-start cadence, no overlapping captures and no extra
            // one-second delay added after a slow read.
            delay((1_000 - (now() - started)).coerceAtLeast(80))
        }
    }
    private suspend fun waitTransition(form: Boolean): UiTree {
        while (true) {
            val tree = observations.read()
            if (!contract.loading(tree)) {
                if (form && tree.has("新增")) {
                    val ready = stable()
                    // Wait for the state controls as well as the title. Check the
                    // state before normal-only inputs: abnormal can change their layout.
                    if (formPublished(ready)) {
                        contract.formIdentity(ready, selected ?: throw VisitMismatch("缺少本轮记录，不能操作"))
                        return ready
                    }
                }
                if (!form && tree.has("待办") && !tree.has("新增")) {
                    val ready = stable()
                    val records = contract.records(ready)
                    val old = pendingHash() ?: throw VisitMismatch("缺少提交保护标记")
                    // Navigation can finish before the server refresh removes
                    // the old row. Observe it; never submit again or count early.
                    if (records.none { it.fingerprint == old }) return ready
                    delay(100)
                    continue
                }
                // Only the known source page is permitted while waiting; arbitrary dialogs fail.
                if (form && !tree.has("新增")) {
                    val top = contract.top(tree)
                    if (top.fingerprint != selected?.fingerprint) throw VisitMismatch("等待期间记录不一致")
                } else if (!form) {
                    contract.form(tree, selected, visualFallback = true)
                    if (contract.formFingerprint(tree) != pendingHash()) throw VisitMismatch("待核验提交与当前表单记录不一致")
                    contract.verifyValues(tree)
                }
            }
            delay(100)
        }
    }
    private fun formPublished(tree: UiTree): Boolean = try {
        tree.has("新增") && tree.has("走访记录") && !contract.loading(tree) &&
            tree.has("正常") && tree.has("异常")
    } catch (_: VisitMismatch) { false }
    suspend fun probe(): String {
        val tree = stable()
        if (tree.has("新增")) { contract.formIdentity(tree, null); verifyNormal(tree); return "表单控件可读；未填写、未提交" }
        val top = contract.top(tree)
        if (top.fingerprint.isEmpty()) throw VisitMismatch("记录核对失败")
        return "列表、顶部记录和走访按钮可读；未点击"
    }
    override fun release() { selected = null; confirmedList = null; confirmedCount = null; confirmedTop = null; scrolls = 0; opening = false }
}

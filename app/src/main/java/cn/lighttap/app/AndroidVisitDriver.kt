package cn.lighttap.app

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

class AndroidVisitDriver(context: Context, private val bridge: AndroidUiBridge, private val vision: AndroidChoiceVision) : VisitDriver {
    private val contract = VisitContract(bridge.target)
    private val fence = context.getSharedPreferences("operation_fence", Context.MODE_PRIVATE)
    private var selected: VisitRecord? = null
    private var confirmedList: String? = null
    private var scrolls = 0
    private var opening = false
    override val recoveryStep: VisitStep? get() = when { pendingSubmission -> VisitStep.WAIT_LIST; opening -> VisitStep.WAIT_FORM; else -> null }
    override val pendingSubmission: Boolean get() = fence.getString("pending_hash", null) != null
    private fun pendingHash() = fence.getString("pending_hash", null)
    override suspend fun begin() { selected = null; confirmedList = null; scrolls = 0; opening = false }
    private suspend fun stable(): UiTree {
        val first = bridge.capture()
        delay(250)
        val second = bridge.capture()
        if (first != second) throw VisitMismatch("页面尚未稳定，请核实后继续")
        return second
    }
    override suspend fun validate(step: VisitStep) {
        val tree = bridge.capture()
        contract.target(tree)
        when (step) {
            VisitStep.FIND_LIST -> contract.records(tree)
            VisitStep.OPEN_FORM -> {
                val top = contract.top(tree)
                if (selected?.fingerprint != top.fingerprint) throw VisitMismatch("顶部记录已变化，禁止点击")
            }
            VisitStep.WAIT_FORM -> {
                if (!contract.loading(tree) && !tree.has("新增")) {
                    val top = contract.top(tree)
                    if (top.fingerprint != selected?.fingerprint) throw VisitMismatch("等待表单时列表记录变化")
                }
            }
            VisitStep.WAIT_LIST -> {
                if (!pendingSubmission) throw VisitMismatch("缺少提交结果核验标记，不能计数")
                if (tree.has("新增") && !contract.loading(tree) && contract.formFingerprint(tree) != pendingHash()) throw VisitMismatch("待核验提交与当前表单记录不一致")
            }
            VisitStep.GAP -> {
                val signature = contract.listSignature(tree)
                if (confirmedList == null || signature != confirmedList) throw VisitMismatch("等待期间列表发生变化，请查看后继续")
            }
            else -> {
                contract.form(tree, selected ?: throw VisitMismatch("缺少本轮记录，不能填写"), visualFallback = true)
                contract.allowedFields(tree)
            }
        }
    }
    override suspend fun execute(step: VisitStep): VisitEffect = when (step) {
        VisitStep.FIND_LIST -> {
            val tree = stable()
            selected = contract.top(tree)
            if (selected?.fingerprint == pendingHash()) throw VisitMismatch("本条存在未确认提交，禁止再次处理")
            scrolls = 0
            VisitEffect(message = "顶部记录与按钮已唯一识别；尚未点击")
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
            val tree = stable(); checkForm(tree)
            val field = contract.field(tree, "走访描述")
            if (fullyVisible(tree, field)) VisitEffect(message = "描述输入框已可见，尚未填写")
            else {
                if (scrolls >= 8) throw VisitMismatch("多次滚动后仍未定位描述框，停止适配")
                val scroll = tree.ancestors(field).firstOrNull { it.scrollable && it.enabled && it.visible }
                    ?: throw VisitMismatch("找不到与描述框关联的可滚动控件")
                verifyNormal(tree)
                bridge.perform(tree, scroll, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                scrolls++
                VisitEffect(repeat = true, message = "仅滚动一次；请核验，下次继续重新定位")
            }
        }
        VisitStep.FILL_DESCRIPTION -> fill("走访描述", VisitContract.DESCRIPTION)
        VisitStep.VERIFY_FORM -> {
            val tree = stable(); checkForm(tree); verifyNormal(tree); contract.verifyValues(tree); contract.submit(tree)
            VisitEffect(message = "记录、两字段及正常状态均一致；尚未提交")
        }
        VisitStep.SUBMIT -> {
            val tree = stable(); checkForm(tree); verifyNormal(tree); contract.verifyValues(tree)
            if (pendingSubmission) throw VisitMismatch("已有未确认提交，禁止再次提交")
            val button = contract.submit(tree)
            val record = selected ?: throw VisitMismatch("缺少记录核验信息")
            // Persist a non-reversible record digest before dispatch. Survives stop/process death.
            if (!fence.edit().putString("pending_hash", record.fingerprint).putString("pending_package", bridge.target).commit()) throw VisitMismatch("无法保存提交保护标记，禁止提交")
            bridge.perform(tree, button, AccessibilityNodeInfo.ACTION_CLICK)
            VisitEffect(message = "提交已触发一次，结果待确认；不会重复提交")
        }
        VisitStep.WAIT_LIST -> {
            val tree = waitTransition(form = false)
            val records = contract.records(tree)
            val old = pendingHash() ?: throw VisitMismatch("缺少提交保护标记")
            if (fence.getString("pending_package", null) != bridge.target) throw VisitMismatch("未确认提交属于其他目标应用")
            if (records.any { it.fingerprint == old }) throw VisitMismatch("原记录仍在列表，提交结果不明确")
            confirmedList = contract.listSignature(tree)
            if (!fence.edit().remove("pending_hash").remove("pending_package").commit()) throw VisitMismatch("提交已核对，但保护标记未能清除；保持暂停")
            selected = null
            VisitEffect(message = "原记录已移除，列表稳定；本轮确认完成")
        }
        VisitStep.GAP -> VisitEffect()
    }
    private suspend fun verifyNormal(tree: UiTree) {
        if (contract.normalState(tree) == ChoiceResult.ABNORMAL) throw VisitMismatch("户籍状态异常，禁止继续")
        vision.requireNormal(contract.choiceButton(tree, "正常"), contract.choiceButton(tree, "异常"))
        if (bridge.capture() != tree) throw VisitMismatch("状态核验期间页面变化，已暂停")
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
        val tree = stable(); checkForm(tree); verifyNormal(tree)
        val field = contract.field(tree, label)
        if (!fullyVisible(tree, field)) throw VisitMismatch("输入框未完整显示，已暂停且未输入")
        val before = contract.value(field)
        if (before.isNotEmpty() && before != value) throw VisitMismatch("已有字段内容冲突，禁止覆盖")
        if (before != value) bridge.perform(tree, field, AccessibilityNodeInfo.ACTION_SET_TEXT, value)
        // ACTION_SET_TEXT can acknowledge before the WebView publishes its updated node.
        // Observe only; never dispatch the write again or accept a different nonempty value.
        var remainingChecks = 12
        while (true) {
            val observed = bridge.capture(); checkForm(observed)
            val actual = contract.fieldValue(observed, label)
            if (actual == value) break
            if (actual.isNotEmpty() || --remainingChecks <= 0) throw VisitMismatch("填写后回读不一致，禁止继续")
            delay(200)
        }
        val after = stable(); checkForm(after)
        if (contract.fieldValue(after, label) != value) throw VisitMismatch("填写后回读不一致，禁止继续")
        return VisitEffect(message = "本字段填写并回读通过；未执行其他字段操作")
    }
    private suspend fun waitTransition(form: Boolean): UiTree {
        while (true) {
            val tree = bridge.capture()
            if (!contract.loading(tree)) {
                if (form && tree.has("新增")) { val ready = stable(); checkForm(ready); return ready }
                if (!form && tree.has("待办") && !tree.has("新增")) {
                    val ready = stable(); contract.records(ready); return ready
                }
                // Only the known source page is permitted while waiting; arbitrary dialogs fail.
                if (form) {
                    val top = contract.top(tree)
                    if (top.fingerprint != selected?.fingerprint) throw VisitMismatch("等待期间记录不一致")
                } else {
                    contract.form(tree, selected, visualFallback = true)
                    contract.verifyValues(tree)
                }
            }
            delay(250)
        }
    }
    suspend fun probe(): String {
        val tree = stable()
        if (tree.has("新增")) { contract.form(tree, null, visualFallback = true); verifyNormal(tree); contract.allowedFields(tree); return "表单控件可读；未填写、未提交" }
        val top = contract.top(tree)
        if (top.fingerprint.isEmpty()) throw VisitMismatch("记录核对失败")
        return "列表、顶部记录和走访按钮可读；未点击"
    }
    override fun release() { selected = null; confirmedList = null; scrolls = 0; opening = false }
}
package cn.lighttap.app

import org.json.JSONObject

fun interface VisitDiagnostics {
    fun record(event: VisitDebugEvent)
    companion object { val NONE = VisitDiagnostics { } }
}

enum class VisitDebugKind {
    ARM, START, RESUME, PAUSE, STOP, STEP_START, STEP_DONE, WAIT, ERROR, COMPLETE, PROBE,
    CAPTURE, STABILITY, RECHECK, OVERLAY_AVOID, ACTION, NAVIGATION_CHECK, LIST, RETURN_CHECK, RETURN_CONFIRMED, CHOICE_CHECK, IMAGE_CHECK, MODEL_WARMUP, SUBMIT_PREPARED,
}
enum class VisitDebugAction { CLICK, FILL, SCROLL, OTHER }

/** Only counts, enums and boolean comparisons cross the diagnostics boundary.
 * No tree, node, input value, record fingerprint, exception or stack trace is retained. */
data class VisitDebugEvent(
    val kind: VisitDebugKind,
    val step: VisitStep? = null,
    val status: VisitStatus? = null,
    val execution: VisitExecution? = null,
    val runMode: VisitRunMode? = null,
    val limit: Long? = null,
    val rounds: Long? = null,
    val pending: Boolean? = null,
    val durationMs: Long? = null,
    val waitMs: Long? = null,
    val nodes: Int? = null,
    val window: Int? = null,
    val targetMatches: Boolean? = null,
    val pendingTabs: Int? = null,
    val pendingSelected: Boolean? = null,
    val form: Boolean? = null,
    val loading: Boolean? = null,
    val records: Int? = null,
    val previousRecords: Int? = null,
    val listChanged: Boolean? = null,
    val topUnchanged: Boolean? = null,
    val oldPresent: Boolean? = null,
    val visual: ReturnVisual? = null,
    val choice: ChoiceResult? = null,
    val normal: Boolean? = null,
    val successHint: Boolean? = null,
    val attempt: Int? = null,
    val action: VisitDebugAction? = null,
    val submit: Boolean? = null,
    val navigation: Boolean? = null,
    val ok: Boolean? = null,
    val reason: String? = null,
    val delta: VisitSnapshotDelta? = null,
    val recognition: VisitRecognition? = null,
) {
    internal fun json(): JSONObject = JSONObject().apply {
        put("kind", kind.name)
        step?.let { put("step", it.name) }; status?.let { put("status", it.name) }
        execution?.let { put("execution", it.name) }; runMode?.let { put("runMode", it.name) }
        limit?.let { put("limit", it) }; rounds?.let { put("rounds", it) }
        pending?.let { put("pendingSubmission", it) }; durationMs?.let { put("durationMs", it.coerceAtLeast(0)) }
        waitMs?.let { put("waitMs", it.coerceAtLeast(0)) }; nodes?.let { put("nodes", it) }
        window?.let { put("window", it) }; targetMatches?.let { put("targetMatches", it) }
        pendingTabs?.let { put("pendingTabs", it) }; pendingSelected?.let { put("pendingSelected", it) }
        form?.let { put("form", it) }; loading?.let { put("loading", it) }; records?.let { put("records", it) }
        previousRecords?.let { put("previousRecords", it) }; listChanged?.let { put("listChanged", it) }
        topUnchanged?.let { put("topUnchanged", it) }; oldPresent?.let { put("oldPresent", it) }
        visual?.let { put("visual", it.name) }; choice?.let { put("choice", it.name) }
        normal?.let { put("normal", it) }; successHint?.let { put("successHint", it) }
        attempt?.let { put("attempt", it) }; action?.let { put("action", it.name) }
        submit?.let { put("submit", it) }; ok?.let { put("ok", it) }
        navigation?.let { put("navigation", it) }
        recognition?.let { put("recognitionMode", it.name) }
        reason?.let { put("reason", safeVisitDebugReason(it)) }
        delta?.let { put("delta", JSONObject().put("added", it.added).put("removed", it.removed)
            .put("text", it.text).put("description", it.description).put("geometry", it.geometry)
            .put("controls", it.controls).put("structure", it.structure).put("windowChanged", it.windowChanged)) }
    }
}

/** Allow only application-owned messages. Future/raw platform errors fall back
 * to a fixed label rather than accidentally exporting business text. */
internal fun safeVisitDebugReason(reason: String): String = when {
    reason.contains("\n已等待 ") -> safeVisitDebugReason(reason.substringBefore("\n已等待 "))
    reason in DEBUG_REASONS -> reason
    listOf("检查地址", "走访描述", "姓名", "身份证", "居住地址").any { label ->
        reason == "字段标签缺失或不唯一：$label" || reason == "不能唯一关联输入框：$label"
            || reason == "记录字段有歧义：$label" || reason == "缺少或无法唯一确定记录核对字段：$label"
            || reason == "无法确定记录核对字段：$label"
    } -> reason
    Regex("(提交后画面暂不可读取|本机视觉核验不可用，已暂停)（系统码 [0-9]{1,3}）").matches(reason) -> reason
    else -> "未分类原因（原文未记录）"
}

private val DEBUG_REASONS = setOf(
    "等待目标应用页面恢复", "等待目标控件恢复", "等待目标控件显示", "等待可滚动页面",
    "等待待办页和走访按钮；页面恢复后自动继续", "等待返回待办列表；不会重复提交",
    "等待走访按钮；通知或弹窗结束后自动继续", "等待提交按钮；通知或弹窗结束后自动继续",
    "等待检查地址输入框；页面恢复后自动继续", "等待走访描述输入框；页面恢复后自动继续",
    "已读取表单输入框；未填写、未提交", "已读取走访按钮；未点击", "已读取当前按钮；未点击",
    "图像目标控件不完整可见，已暂停", "图像按钮识别不唯一或置信度不足，已暂停", "图像采样已过期，请重新识别",
    "图像定位与当前按钮不一致，已暂停", "图像页面状态无法确认，已暂停", "图像识别期间目标窗口变化，已暂停",
    "图像识别期间页面结构变化，已暂停",
    "图像识别期间按钮移动，已暂停", "图像识别服务已关闭，请重新开启", "本地识字库与此设备不兼容，已暂停",
    "本地识字模型加载失败，已暂停", "本机图像识字失败，已暂停", "图像备用方式需要 Android 11 及以上",
    "当前画面无法用于图像识别，已暂停", "图像提交准备已失效，请重新核验", "本机识字与按钮定位可用；未填写、未点击、未提交",
    "用户暂停", "已返回设置页，请查看后手动继续", "前台应用变化，已暂停，请查看",
    "屏幕已关闭，任务已停止", "屏幕参数已变化，请检查并重新确认点位", "无障碍服务被中断，任务已停止",
    "当前界面不可读取", "当前窗口快照已失效", "当前前台不是已绑定的目标应用", "界面结构正在变化",
    "界面结构过于复杂，无法完整核验", "屏幕锁定或关闭，任务已暂停",
    "页面持续变化或暂不可读取，请查看后继续", "当前界面持续不可读取，请查看后继续",
    "执行前页面发生变化，已暂停", "目标控件不可操作", "悬浮内容遮挡目标控件，请移开或收起后继续",
    "执行前界面不可读", "执行前目标窗口已变化", "目标控件已失效", "执行前控件内容已变化",
    "执行前控件位置或状态已变化", "本次点击未确认完成，已暂停且不会自动重试", "系统未确认本次控件操作，已暂停",
    "系统未接受走访控件点击，请查看后继续", "走访点击后仍在待办页，未打开表单；继续将重新识别并点击走访",
    "无法读取当前界面", "页面控件已变化", "未识别到唯一待办页签", "列表页结构不匹配",
    "无法确认当前选中待办页签", "列表仍在加载，请稍后由用户继续", "未读到走访控件，无法可靠识别网页内容",
    "走访按钮与记录按钮不能唯一关联", "列表顺序结构不能确定", "平铺记录边界有歧义",
    "记录完成状态不是明确的 0/1", "卡片核对结构缺失", "平铺卡片姓名不唯一", "平铺卡片姓名位置不匹配",
    "无法唯一读取记录姓名", "列表记录存在歧义，不能选择", "不能确定最上方记录", "暂无待办，请用户查看",
    "记录字段有歧义", "缺少或无法唯一确定记录核对字段", "无法确定记录核对字段",
    "缺少提交结果核验标记，不能计数", "仍有未确认提交，禁止开始下一轮", "缺少上一轮结果确认，不能开始下一轮",
    "仍有未确认提交，不能以空列表结束", "空列表画面尚未稳定，请查看后继续", "本条存在未确认提交，禁止再次处理",
    "顶部记录变化，禁止点击", "缺少本轮记录，不能操作", "缺少本轮记录，不能继续", "缺少本轮记录，不能选择",
    "表单仍在加载", "不是预期的新增表单", "表单记录与所选卡片不一致", "状态控件持续变化，请查看后继续",
    "户籍状态异常，禁止继续", "户籍状态明确异常，已暂停", "户籍状态异常或无法确认选中正常",
    "无法唯一识别正常选项", "户籍状态结构不匹配", "状态选中标记矛盾，已暂停", "状态控件缺失或不唯一",
    "选中样式不匹配或被遮挡，视觉核验不确定，已暂停", "视觉核验发现异常被选中，禁止继续",
    "此设备不支持本机视觉核验，已暂停", "正常/异常按钮不完整可见，不能视觉核验",
    "系统图像无法用于核验，已暂停", "本地视觉库与此设备不兼容，已暂停", "本地视觉校验失败，已暂停",
    "此设备不支持提交后画面核验，已暂停", "画面非正常白底", "原记录仍在列表，提交结果不明确",
    "多次滚动后仍未定位描述框，停止适配", "找不到与描述框关联的可滚动控件", "输入框未完整显示，已暂停且未输入",
    "已有字段内容冲突，禁止覆盖", "已有字段内容冲突，已暂停且未覆盖", "填写后回读不一致，禁止继续",
    "两字段回读校验不一致，禁止提交", "按钮不可见", "找不到明确可点击的按钮", "提交按钮缺失或不唯一",
    "距上次提交不足 61 秒，请等待后继续", "已有未确认提交，禁止再次提交", "缺少记录核验信息",
    "无法保存提交保护标记，禁止提交", "提交点击时间保存失败；保留保护标记并暂停",
    "本次提交未派发，但保护标记清除失败；保持暂停",
    "本机截图等待超时，请查看后继续",
    "当前步骤被取消，已暂停；请查看后继续",
    "提交间隔记录保存失败；保留保护标记并暂停", "提交间隔记录无法保存，禁止提交",
    "缺少提交保护标记", "未确认提交属于其他目标应用", "缺少提交后正常页面核验",
    "提交确认计时保存失败，保留保护标记", "提交已核对，但保护标记未能清除；保持暂停",
    "待核验提交与当前表单记录不一致", "等待期间记录不一致", "记录核对失败", "空列表结束条件不匹配",
    "提交结果等待超时，请查看；保留保护标记，不会重复提交", "页面转换或核验超过 30 秒，请查看",
    "当前步骤发生异常，已暂停；请查看页面", "操作已取消",
    "正在只读检查，未执行操作", "只读检查已取消，未执行操作", "只读检查超时，请待页面稳定后重试；未执行操作",
    "当前界面无法可靠读取，未执行操作", "表单控件可读；未填写、未提交", "列表、顶部记录和走访按钮可读；未点击",
)

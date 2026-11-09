package cn.lighttap.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.floor

/** Lazily loaded, bundled CPU models. Screenshots, OCR strings and pixel buffers
 * are short lived; there are no image files, network calls or text logs. */
internal class AndroidImageRecognition(private val service: AccessibilityService) {
    private val mutex = Mutex()
    private var engine: PaddleOCR? = null
    private var closed = false

    private suspend fun ensureEngine(): PaddleOCR {
        if (closed) throw VisitMismatch("图像识别服务已关闭，请重新开启")
        return engine ?: try {
            withContext(Dispatchers.IO) {
                System.loadLibrary("opencv_java4")
                // Own the initialized instance even if cancellation arrives
                // during the dispatcher hop; close() can then release it.
                withContext(NonCancellable) {
                    PaddleOCR.create(service, PaddleOCRConfig(detLimitSideLen = 1280, detLimitType = "max",
                        detMaxSideLimit = 1280, recBatchSize = 4), EngineConfig(numThreads = 2)).also { engine = it }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: LinkageError) { throw VisitMismatch("本地识字库与此设备不兼容，已暂停") }
        catch (_: Exception) { throw VisitMismatch("本地识字模型加载失败，已暂停") }
    }

    /** Loads bundled files only. Does not capture a screen or dispatch input. */
    suspend fun warmUp() = mutex.withLock { ensureEngine(); Unit }

    suspend fun read(tree: UiTree, button: UiNode, navigation: Boolean): ImageClickEvidence = mutex.withLock {
        val ocr = ensureEngine()
        currentCoroutineContext().ensureActive()
        val (bitmap, capturedAt) = screenshot()
        val pixels = IntArray(bitmap.width * bitmap.height)
        try {
            val region = imageOcrRegion(button.bounds, bitmap.width, bitmap.height)
            val crop = Bitmap.createBitmap(bitmap, region.left, region.top, region.right - region.left, region.bottom - region.top)
            val result = try { ocr.recognize(crop) }
                catch (error: CancellationException) { throw error }
                catch (_: LinkageError) { throw VisitMismatch("本地识字库与此设备不兼容，已暂停") }
                catch (_: Exception) { throw VisitMismatch("本机图像识字失败，已暂停") }
                finally { if (crop !== bitmap) { if (crop.isMutable) crop.eraseColor(android.graphics.Color.TRANSPARENT); crop.recycle() } }
            currentCoroutineContext().ensureActive()
            val lines = result.results.map { item ->
                ImageText(item.text, item.confidence, UiBox(region.left + floor(item.box.points.minOf { it.x }).toInt(),
                    region.top + floor(item.box.points.minOf { it.y }).toInt(), region.left + ceil(item.box.points.maxOf { it.x }).toInt(),
                    region.top + ceil(item.box.points.maxOf { it.y }).toInt()))
            }
            val box = ImageClickLocator.locate(lines, if (navigation) "走访" else "提交", button.bounds, bitmap.width, bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val listClear = if (navigation) clearList(tree, pixels, bitmap.width, bitmap.height) else false
            val normal = if (!navigation) {
                val contract = VisitContract(tree.packageName)
                // Preserve the existing state contract: an explicit selected
                // normal node is authoritative; pixels are required only when
                // the page does not expose a selection state. An explicit
                // abnormal state can never be overridden by an image result.
                when (contract.normalState(tree)) {
                    ChoiceResult.NORMAL -> true
                    ChoiceResult.ABNORMAL -> false
                    ChoiceResult.UNKNOWN -> {
                        val frame = ChoiceFrame(0, 0, bitmap.width, bitmap.height, pixels)
                        OpenCvChoiceBackend.classify(frame, contract.choiceButton(tree, "正常").bounds,
                            contract.choiceButton(tree, "异常").bounds) == ChoiceResult.NORMAL
                    }
                }
            } else false
            ImageClickEvidence(box, capturedAt, bitmap.width, bitmap.height, listClear, normal)
        } finally {
            pixels.fill(0)
            bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
            bitmap.recycle()
        }
    }

    private fun clearList(tree: UiTree, pixels: IntArray, width: Int, height: Int): Boolean {
        val tab = tree.exact("待办", true).singleOrNull() ?: return false
        val left = (width * .04).toInt(); val right = (width * .96).toInt()
        val top = maxOf(tab.bounds.bottom + 8, (height * .26).toInt()); val bottom = (height * .90).toInt()
        if (left >= right || top >= bottom) return false
        val sample = IntArray(72 * 96)
        try {
            for (y in 0 until 96) for (x in 0 until 72) {
                val sx = left + x * (right - left) / 72
                val sy = top + y * (bottom - top) / 96
                sample[y * 72 + x] = pixels[sy * width + sx]
            }
            return classifyReturnPixels(sample, 72, 96) == ReturnVisual.CLEAR
        } finally { sample.fill(0) }
    }

    private suspend fun screenshot(): Pair<Bitmap, Long> = withTimeout(2_500) { VisitScreenshots.capture(service) {
        if (Build.VERSION.SDK_INT < 30) throw VisitMismatch("图像备用方式需要 Android 11 及以上")
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(Display.DEFAULT_DISPLAY, Dispatchers.Default.asExecutor(), object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    var hardware: Bitmap? = null
                    var software: Bitmap? = null
                    try {
                        if (!continuation.isActive) return
                        hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("No bitmap")
                        software = screenshotSrgb(hardware!!)
                        val owned = software!!
                        val age = (SystemClock.uptimeMillis() - result.timestamp).coerceAtLeast(0)
                        val at = SystemClock.elapsedRealtime() - age
                        // resume's cancellation handler owns the bitmap when
                        // cancellation wins delivery after the callback returns.
                        continuation.resume(owned to at, onCancellation = { _, value, _ ->
                            value.first.eraseColor(android.graphics.Color.TRANSPARENT); value.first.recycle()
                        })
                        software = null
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(VisitMismatch("当前画面无法用于图像识别，已暂停")))
                    } finally {
                        software?.eraseColor(android.graphics.Color.TRANSPARENT); software?.recycle()
                        hardware?.recycle(); buffer.close()
                    }
                }
                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(VisitMismatch("当前画面无法用于图像识别，已暂停")))
                }
            })
        }
    } }

    suspend fun close() = mutex.withLock { closed = true; engine?.release(); engine = null }
}

internal class AndroidImageVisitUiAccess(
    private val service: AccessibilityService, private val native: VisitUiAccess,
    private val surface: VisitSurfaceGuard, private val diagnostics: VisitDiagnostics,
) : VisitUiAccess {
    override val target get() = native.target
    override var lastClickDispatchMs: Long? = null
        private set
    private val ocr = AndroidImageRecognition(service)
    private var prepared: PreparedImageClick? = null
    private val clickGuard = ImageClickGuard(target)
    private val flow = ImageClickFlow(native, { tree, node, navigation ->
        val start = SystemClock.elapsedRealtime()
        try {
            ocr.read(tree, node, navigation).also { evidence -> diagnostics.record(VisitDebugEvent(VisitDebugKind.IMAGE_CHECK,
                durationMs = SystemClock.elapsedRealtime() - start, navigation = navigation, normal = evidence.normal,
                visual = if (navigation) { if (evidence.clearList) ReturnVisual.CLEAR else ReturnVisual.UNKNOWN } else null, ok = true)) }
        } catch (error: VisitMismatch) {
            diagnostics.record(VisitDebugEvent(VisitDebugKind.IMAGE_CHECK, durationMs = SystemClock.elapsedRealtime() - start,
                navigation = navigation, ok = false, reason = error.reason))
            throw error
        }
    }, { tree, box -> tap(tree, box) }, { SystemClock.elapsedRealtime() })
    override suspend fun capture() = native.capture()
    override fun discardSubmit() { prepared = null }
    override fun submissionPrepared(tree: UiTree, node: UiNode): Boolean {
        val plan = prepared ?: return false
        if (plan.consumed) return false
        return try {
            val current = clickGuard.reacquire(plan.request, plan.requestedNode, tree, false)
            ImageClickLocator.validate(plan.evidence, current.bounds, false, SystemClock.elapsedRealtime())
            current == node
        } catch (_: VisitMismatch) { false }
    }
    override suspend fun prepareSubmit(tree: UiTree, node: UiNode) {
        prepared = null
        val screen = tree.nodes.firstOrNull()?.bounds ?: throw VisitPreActionMismatch("无法读取当前界面")
        prepared = surface.clear(listOf(screen)) { flow.prepare(tree, node, false) }
    }
    override suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String?) {
        if (action == AccessibilityNodeInfo.ACTION_CLICK) {
            val plan = prepared
            prepared = null
            lastClickDispatchMs = null
            if (plan == null || clickGuard.reacquire(plan.request, plan.requestedNode, tree, false) != node)
                throw VisitPreActionMismatch("图像提交准备已失效，请重新核验")
            val screen = tree.nodes.first().bounds
            surface.clear(listOf(screen)) { flow.dispatch(plan) }
        }
        else surface.clear(listOf(node.bounds)) { native.perform(tree, node, action, value) }
    }
    override suspend fun openVisit(tree: UiTree, node: UiNode) = click(tree, node, true)
    private suspend fun click(tree: UiTree, node: UiNode, navigation: Boolean) {
        lastClickDispatchMs = null
        val screen = tree.nodes.firstOrNull()?.bounds ?: throw VisitPreActionMismatch("无法读取当前界面")
        surface.clear(listOf(screen)) { flow.click(tree, node, navigation) }
    }
    suspend fun probe() {
        val tree = native.capture()
        val contract = VisitContract(target)
        val navigation = !tree.has("新增")
        val node = if (navigation) tree.node(contract.top(tree).actionPath) else contract.submit(tree)
        val screen = tree.nodes.firstOrNull()?.bounds ?: throw VisitMismatch("无法读取当前界面")
        surface.clear(listOf(screen)) { ocr.read(tree, node, navigation) }
    }
    private suspend fun tap(tree: UiTree, box: UiBox) {
        if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !service.getSystemService(PowerManager::class.java).isInteractive) throw VisitPreActionMismatch("屏幕锁定或关闭，任务已暂停")
        @Suppress("DEPRECATION")
        val root = service.rootInActiveWindow ?: throw VisitPreActionMismatch("执行前界面不可读")
        try {
            if (root.packageName?.toString() != target || root.windowId != tree.windowId)
                throw VisitPreActionMismatch("执行前目标窗口已变化")
        } finally { @Suppress("DEPRECATION") root.recycle() }
        currentCoroutineContext().ensureActive()
        val complete = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { continuation ->
                val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(Path().apply {
                    moveTo((box.left + box.right) / 2f, (box.top + box.bottom) / 2f)
                }, 0, 50)).build()
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
                }
                val at = SystemClock.elapsedRealtime()
                val accepted = service.dispatchGesture(gesture, callback, null)
                lastClickDispatchMs = if (accepted) at else null
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
        } ?: false
        if (!complete) throw VisitMismatch("本次点击未确认完成，已暂停且不会自动重试")
    }
    suspend fun close() { prepared = null; ocr.close() }
    suspend fun warmUp() = ocr.warmUp()
}

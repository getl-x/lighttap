package cn.lighttap.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class TouchTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLightTapEdgeToEdge()
        setContent { LightTapTheme {
            var groups by remember { mutableIntStateOf(0) }
            var maxPointers by remember { mutableIntStateOf(0) }
            var last by remember { mutableStateOf("等待点击") }
            var reset by remember { mutableIntStateOf(0) }
            Scaffold { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("点击测试", style = MaterialTheme.typography.headlineMedium)
                Text("手势组 $groups  ·  最大同时触点 $maxPointers")
                Text(last, style = MaterialTheme.typography.bodySmall)
                Text("在下方区域放置点位。一次按下到全部释放算一组；同时模式可观察最大触点数。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { appModel.service.value?.showOverlay() }) { Text("显示控制条") }
                    OutlinedButton(onClick = { groups = 0; maxPointers = 0; last = "等待点击"; reset++ }) { Text("重置统计") }
                    TextButton(onClick = { finish() }) { Text("返回") }
                }
                key(reset) { AndroidView(factory = { TouchCanvas(it) { newGroup, pointers, x, y -> if (newGroup) groups++; maxPointers = maxOf(maxPointers, pointers); last = "最近触点 X ${x.toInt()} · Y ${y.toInt()} · $pointers 指" } }, modifier = Modifier.fillMaxWidth().weight(1f)) }
            } }
        } }
    }
}
// Constructed only in code; no XML inflation constructor is needed.
@SuppressLint("ViewConstructor")
private class TouchCanvas(context: Context, val report: (Boolean, Int, Float, Float) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dots = ArrayDeque<Pair<Float, Float>>()
    init { setBackgroundColor(Color.rgb(237, 235, 250)); contentDescription = "多点触摸测试区域" }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            val index = event.actionIndex
            val location = IntArray(2); getLocationOnScreen(location)
            report(event.actionMasked == MotionEvent.ACTION_DOWN, event.pointerCount, event.getX(index) + location[0], event.getY(index) + location[1])
            for (i in 0 until event.pointerCount) { dots.addLast(event.getX(i) to event.getY(i)); if (dots.size > 60) dots.removeFirst() }
            invalidate()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Color.rgb(213, 209, 239); paint.strokeWidth = resources.displayMetrics.density
        val grid = 48 * resources.displayMetrics.density
        var x = 0f; while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), paint); x += grid }
        var y = 0f; while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, paint); y += grid }
        paint.color = Color.rgb(98, 85, 217)
        dots.forEach { (px, py) -> canvas.drawCircle(px, py, 12 * resources.displayMetrics.density, paint) }
    }
}

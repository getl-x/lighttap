package cn.lighttap.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** The custom top card has no timeout; the ordinary system notification is
 * dismissible and has no sound or app-set expiry. Either dismissal clears both. */
@SuppressLint("SetTextI18n", "RtlHardcoded")
internal class WorkReminderDisplay(private val service: ClickAccessibilityService) {
    private val manager = service.getSystemService(NotificationManager::class.java)
    private val wm = service.getSystemService(WindowManager::class.java)
    private val store = service.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private var card: LinearLayout? = null
    private var suppressed = false
    fun suppress(active: Boolean) {
        suppressed = active
        val window = card ?: return
        val params = window.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = if (active) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        window.visibility = if (active) View.INVISIBLE else View.VISIBLE
        wm.updateViewLayout(window, params)
    }
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).roundToInt()

    init {
        val event = runCatching {
            WorkReminderEvent(LocalDate.parse(store.getString("date", null)), WorkReminderSlot.valueOf(store.getString("slot", null)!!))
        }.getOrNull()
        if (event != null) show(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(event: WorkReminderEvent) {
        store.edit { putString("date", event.date.toString()); putString("slot", event.slot.name) }
        val channel = NotificationChannel(CHANNEL, "下班前提醒（无声音）", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null); enableVibration(false)
            description = "工作日 11:15、17:00 任务运行中提醒；手动划掉后清除"
        }
        manager.createNotificationChannel(channel)
        val dismiss = PendingIntent.getBroadcast(service, 92, Intent(service, WorkReminderDismissReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT < 33 || service.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            try {
                manager.notify(ID, NotificationCompat.Builder(service, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("${event.slot.label} 下班前提醒")
                    .setContentText("到点时任务仍在运行，请确认下班前已停止自动任务。")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("到点时任务仍在运行，请确认下班前已停止自动任务。划掉通知可同时收起顶部提醒条。"))
                    .setDeleteIntent(dismiss).addAction(0, "知道了", dismiss)
                    .setAutoCancel(false).setOngoing(false).setOnlyAlertOnce(true).setSilent(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            } catch (_: SecurityException) { /* The visible card still works. */ }
        }
        detach()
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(10), dp(10))
            background = GradientDrawable().apply { setColor(Color.rgb(255, 247, 246)); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.rgb(200, 55, 65)) }
            elevation = dp(8).toFloat()
        }
        val row = LinearLayout(service)
        row.addView(TextView(service).apply { text = "轻点 · ${event.slot.label} 下班前提醒"; textSize = 12f; setTextColor(Color.rgb(160, 28, 40)); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(TextView(service).apply {
            text = "×"; textSize = 22f; gravity = Gravity.CENTER; contentDescription = "关闭下班前提醒"
            setTextColor(Color.DKGRAY); setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        layout.addView(row)
        layout.addView(TextView(service).apply { text = "到点时仍在运行，请留意停止"; textSize = 14f; setTextColor(Color.rgb(45, 35, 40)) })
        var startX = 0f; var startY = 0f
        layout.setOnTouchListener { view, motion ->
            when (motion.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = motion.rawX; startY = motion.rawY; true }
                MotionEvent.ACTION_MOVE -> { view.translationX = motion.rawX - startX; true }
                MotionEvent.ACTION_UP -> {
                    if (abs(motion.rawX - startX) >= dp(56) || startY - motion.rawY >= dp(32)) dismiss()
                    else { view.translationX = 0f; view.performClick() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { view.translationX = 0f; true }
                else -> true
            }
        }
        val params = WindowManager.LayoutParams(minOf(dp(340), service.resources.displayMetrics.widthPixels - dp(20)), -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (suppressed) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(28) }
        card = layout
        layout.visibility = if (suppressed) View.INVISIBLE else View.VISIBLE
        wm.addView(layout, params)
    }

    fun bounds(): UiBox? {
        val view = card ?: return null
        if (suppressed || view.visibility != View.VISIBLE) return null
        val point = IntArray(2); view.getLocationOnScreen(point)
        return UiBox(point[0], point[1], point[0] + view.width, point[1] + view.height)
    }
    fun dismiss() { clearStored(service); detach() }
    fun detach() { card?.let { runCatching { wm.removeViewImmediate(it) } }; card = null }

    companion object {
        const val ID = 92
        const val CHANNEL = "workday_reminder_silent"
        private const val STORE = "workday_reminder_display"
        fun clearStored(context: Context) {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit { clear() }
            context.getSystemService(NotificationManager::class.java).cancel(ID)
        }
    }
}

class WorkReminderDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val controller = context.appModel.privateModel.controller
        if (controller != null) controller.dismissWorkReminder() else WorkReminderDisplay.clearStored(context)
    }
}

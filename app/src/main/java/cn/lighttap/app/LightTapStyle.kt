package cn.lighttap.app

import android.content.res.ColorStateList
import android.graphics.Color as NativeColor
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

object LightTapColors {
    const val INK = 0xFF252339.toInt()
    const val PRIMARY = 0xFF6255D9.toInt()
    const val MUTED = 0xFF68677A.toInt()
    const val CANVAS = 0xFFF5F6FA.toInt()
    const val SUCCESS = 0xFF23664D.toInt()
    const val WARNING = 0xFF8A5A00.toInt()
    const val ERROR = 0xFFB42318.toInt()
    const val OVERLAY = 0xFF1C1D2B.toInt()
    const val OVERLAY_SECONDARY = 0xFF433E74.toInt()
    const val OVERLAY_SUCCESS = 0xFFAFE3C6.toInt()
    const val OVERLAY_WARNING = 0xFFFFD991.toInt()
    const val OVERLAY_ERROR = 0xFFFFBEAA.toInt()
}

/** The app currently has one light theme, including when Android uses dark mode. */
fun ComponentActivity.enableLightTapEdgeToEdge() = enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.light(NativeColor.TRANSPARENT, NativeColor.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.light(NativeColor.TRANSPARENT, 0xFF1C1D2B.toInt()),
)

internal fun Button.applyLightTapStyle(primary: Boolean = false, destructive: Boolean = false) {
    val color = when { destructive -> LightTapColors.ERROR; primary -> LightTapColors.PRIMARY; else -> LightTapColors.OVERLAY_SECONDARY }
    val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
    val fill = ColorStateList(states, intArrayOf(0xFF454355.toInt(), color))
    backgroundTintList = null
    background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), GradientDrawable().apply {
        cornerRadius = 10 * resources.displayMetrics.density
        setColor(fill)
    }, null)
    setTextColor(ColorStateList(states, intArrayOf(0xFFC6C3D5.toInt(), NativeColor.WHITE)))
    minWidth = 0; minimumWidth = 0
    minHeight = kotlin.math.ceil(48 * resources.displayMetrics.density).toInt()
    minimumHeight = minHeight
    setPadding(4, 0, 4, 0)
}

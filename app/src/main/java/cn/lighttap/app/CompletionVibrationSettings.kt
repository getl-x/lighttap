package cn.lighttap.app

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-wide preference, independent of task parameters and imported profiles. */
class CompletionVibrationSettings(context: Context) {
    private val preferences = context.getSharedPreferences("completion_reminder", Context.MODE_PRIVATE)
    private val mutableMode = MutableStateFlow(CompletionVibration.restored(preferences.getString("mode", null)))
    val mode: StateFlow<CompletionVibration> = mutableMode

    fun select(mode: CompletionVibration) {
        preferences.edit { putString("mode", mode.name) }
        mutableMode.value = mode
    }
}

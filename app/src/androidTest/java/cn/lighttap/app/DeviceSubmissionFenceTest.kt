package cn.lighttap.app

import android.os.SystemClock
import android.content.SharedPreferences
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A separate preference file; never reads or clears the user's pending marker. */
@RunWith(AndroidJUnit4::class)
class DeviceSubmissionFenceTest {
    @Test fun failedClearRestoresTheMarkerEvenAfterThePreferenceMapWasChanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "verification-fence-failure-${System.nanoTime()}"
        val store = context.getSharedPreferences(name, 0)
        try {
            val initial = AndroidSubmissionFence(context, store)
            assertTrue(initial.mark("generated-pending", "fixture"))
            val at = SystemClock.elapsedRealtime()
            assertTrue(initial.recordClick(at))
            val faulty = object : SharedPreferences by store {
                override fun edit(): SharedPreferences.Editor {
                    val actual = store.edit()
                    return object : SharedPreferences.Editor by actual {
                        override fun commit(): Boolean { actual.commit(); return false }
                    }
                }
            }
            val fence = AndroidSubmissionFence(context, faulty)
            assertFalse(fence.clear())
            assertEquals("generated-pending", fence.hash)
            assertEquals(at, fence.pendingClickMs)
            assertEquals("generated-pending", AndroidSubmissionFence(context, store).hash)
            assertTrue(fence.submissionWaitMs(61_000) > 60_000)
        } finally { assertTrue(context.deleteSharedPreferences(name)) }
    }

    @Test fun pendingIdentitySurvivesReloadAndProvenAbortPreservesTheRealCooldown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "verification-fence-${System.nanoTime()}"
        val store = context.getSharedPreferences(name, 0)
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
        try {
            val old = SystemClock.elapsedRealtime() - 62_000
            check(store.edit().putLong("last_submit_elapsed", old).putInt("last_submit_boot", boot).commit())
            val fence = AndroidSubmissionFence(context, store)
            assertTrue(fence.mark("generated-record", "fixture"))
            assertEquals(old, store.getLong("last_submit_elapsed", -1))
            val reloaded = AndroidSubmissionFence(context, store)
            assertEquals("generated-record", reloaded.hash)
            assertTrue(reloaded.clear())
            assertEquals(0L, reloaded.submissionWaitMs(61_000))

            val click = SystemClock.elapsedRealtime()
            assertTrue(reloaded.recordClick(click))
            assertTrue(reloaded.mark("next-generated-record", "fixture"))
            assertTrue(reloaded.clear())
            assertEquals(click, store.getLong("last_submit_elapsed", -1))
            assertTrue(reloaded.submissionWaitMs(61_000) > 60_000)
        } finally { assertTrue(context.deleteSharedPreferences(name)) }
    }
}

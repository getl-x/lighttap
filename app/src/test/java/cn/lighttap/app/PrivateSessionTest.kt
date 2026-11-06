package cn.lighttap.app

import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.*
import org.junit.Test

class PrivateSessionTest {
    @Test fun sessionSurvivesRecreationButNotNewTask() {
        val s = PrivateSession(); s.attachTask(1, false); s.unlock(); s.attachTask(1, true)
        assertTrue(s.unlocked.value); s.attachTask(2, false); assertFalse(s.unlocked.value)
    }
    @Test fun newInstanceDoesNotRestoreUnlockedState() {
        val s = PrivateSession(); s.attachTask(1, true); assertFalse(s.unlocked.value)
    }
    @Test fun taskRemovalClearsAccess() {
        val s = PrivateSession(); s.attachTask(1, false); s.unlock(); s.lock(); assertFalse(s.unlocked.value); assertNull(s.taskId)
    }
    @Test fun sevenTapsMustBeInsideThreeSeconds() {
        var time = 0L; val taps = EntryTapCounter { time }
        repeat(6) { assertFalse(taps.tap()) }; assertTrue(taps.tap()); assertFalse(taps.tap())
        time = 3_001; assertFalse(taps.tap()); repeat(5) { assertFalse(taps.tap()) }; assertTrue(taps.tap())
    }
    @Test fun passwordCheckUsesSaltedDigestAndClearsCandidate() {
        val salt = ByteArray(24) { it.toByte() }; val password = "fixture-only-passphrase".toCharArray()
        val spec = PBEKeySpec(password, salt, 120_000, 256)
        val digest = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        assertTrue(AccessVerifier.verify(password, Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(digest)))
        assertTrue(password.all { it == '\u0000' })
        assertFalse(AccessVerifier.verify("wrong".toCharArray(), Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(digest)))
    }
}
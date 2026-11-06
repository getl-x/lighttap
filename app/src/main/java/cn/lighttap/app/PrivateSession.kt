package cn.lighttap.app

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.flow.MutableStateFlow

class PrivateSession {
    val unlocked = MutableStateFlow(false)
    var taskId: Int? = null
        private set
    fun attachTask(id: Int, recreation: Boolean) {
        if (!recreation || taskId != id) lock()
        taskId = id
    }
    fun unlock() { unlocked.value = true }
    fun lock() { unlocked.value = false; taskId = null }
}
object AccessVerifier {
    fun verify(candidate: CharArray, salt64: String, digest64: String): Boolean {
        val spec = PBEKeySpec(candidate, Base64.getDecoder().decode(salt64), 120_000, 256)
        return try {
            val result = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            MessageDigest.isEqual(result, Base64.getDecoder().decode(digest64))
        } finally { spec.clearPassword(); candidate.fill('\u0000') }
    }
}
class EntryTapCounter(private val now: () -> Long) {
    private var start = 0L
    private var count = 0
    fun tap(): Boolean {
        val time = now()
        if (count == 0 || time - start > 3_000 || time < start) { start = time; count = 0 }
        count++
        return if (count == 7) { count = 0; true } else false
    }
}
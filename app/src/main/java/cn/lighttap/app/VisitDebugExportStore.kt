package cn.lighttap.app

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONObject

/** Persist the exact export before opening the system picker, so recreation
 * cannot substitute a newer log or a different export's content. */
class VisitDebugExportStore(private val directory: File) {
    fun prepare(data: ByteArray): String {
        validate(data)
        check(directory.isDirectory || directory.mkdirs()) { "无法准备调试导出文件" }
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { ID.matches(it.name) && it.lastModified() < cutoff }?.forEach { it.delete() }
        val id = UUID.randomUUID().toString()
        val file = file(id)
        try { FileOutputStream(file).use { it.write(data); it.fd.sync() } }
        catch (e: Exception) { file.delete(); throw e }
        return id
    }
    fun read(id: String): ByteArray {
        val file = file(id)
        require(file.isFile && file.length() in 1..MAX_BYTES) { "调试导出已失效，请重新导出" }
        return file.readBytes().also { validate(it) }
    }
    fun discard(id: String) { file(id).delete() }
    private fun file(id: String): File { require(ID.matches(id)) { "无效的调试导出任务" }; return File(directory, id) }
    private fun validate(data: ByteArray) {
        require(data.size in 1..MAX_BYTES) { "调试导出内容无效" }
        val root = JSONObject(data.toString(Charsets.UTF_8))
        require(root.getString("format") == VisitDebugLog.FORMAT && root.getInt("schema") == 1)
        root.getJSONObject("environment"); root.getJSONArray("events")
    }
    private companion object {
        const val MAX_BYTES = 4_000_000
        val ID = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    }
}

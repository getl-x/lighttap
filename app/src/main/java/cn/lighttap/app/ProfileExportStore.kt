package cn.lighttap.app

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Only the small opaque ID enters saved instance state; the snapshot survives process recreation. */
class ProfileExportStore(private val directory: File) {
    fun prepare(profiles: List<ClickProfile>): String {
        val data = ProfileCodec.encode(profiles).toByteArray(Charsets.UTF_8)
        require(data.isNotEmpty() && data.size <= 2_000_000) { "方案导出内容过大" }
        check(directory.isDirectory || directory.mkdirs()) { "无法准备导出文件" }
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.name.matches(ID) && it.lastModified() < cutoff }?.forEach { it.delete() }
        val id = UUID.randomUUID().toString()
        val file = snapshot(id)
        try {
            FileOutputStream(file).use { it.write(data); it.fd.sync() }
        } catch (e: Exception) { file.delete(); throw e }
        return id
    }

    fun read(id: String): ByteArray {
        val file = snapshot(id)
        require(file.isFile && file.length() in 1..2_000_000) { "导出内容已失效，请重新导出" }
        val bytes = file.readBytes()
        require(bytes.size in 1..2_000_000) { "导出内容已失效，请重新导出" }
        // Validate before opening/truncating the destination, never report an empty export as success.
        ProfileCodec.decode(bytes.toString(Charsets.UTF_8))
        return bytes
    }

    fun discard(id: String) { snapshot(id).delete() }
    private fun snapshot(id: String): File {
        require(ID.matches(id)) { "无效的导出任务" }
        return File(directory, id)
    }
    private companion object { val ID = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") }
}

package cn.lighttap.app

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One IO owner serializes append/rotation/export. A full diagnostic queue drops
 * diagnostics, never blocks gestures or changes the automation outcome. */
class VisitDebugLog(
    private val directory: File,
    scope: CoroutineScope,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val elapsedTime: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxFileBytes: Int = 512 * 1024,
) : VisitDiagnostics {
    val storageError = MutableStateFlow<String?>(null)
    private val process = UUID.randomUUID().toString()
    private val dropped = AtomicLong()
    private val sequence = AtomicLong()
    private val commands = Channel<Command>(256)
    private sealed interface Command {
        data class Append(val line: String, val durable: Boolean) : Command
        data class Export(val environment: JSONObject, val reply: CompletableDeferred<ByteArray>) : Command
    }
    private val worker = scope.launch(Dispatchers.IO) {
        for (command in commands) when (command) {
            is Command.Append -> try {
                append(command.line, command.durable)
            } catch (_: Exception) {
                dropped.incrementAndGet()
                storageError.value = "部分调试日志未能保存，请检查存储空间"
            }
            is Command.Export -> try { command.reply.complete(export(command.environment)) }
            catch (e: Exception) { command.reply.completeExceptionally(e) }
        }
    }.also { job -> job.invokeOnCompletion { commands.cancel() } }

    override fun record(event: VisitDebugEvent) {
        try {
            // Sanitize before enqueueing: raw reason text never enters the queue or disk.
            val line = event.json().put("timeMs", wallTime()).put("elapsedMs", elapsedTime())
                .put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
                .put("process", process).put("seq", sequence.incrementAndGet()).toString()
            if (!commands.trySend(Command.Append(line, event.kind in setOf(VisitDebugKind.ERROR, VisitDebugKind.PAUSE, VisitDebugKind.STOP))).isSuccess) dropped.incrementAndGet()
        } catch (_: Exception) { dropped.incrementAndGet() }
    }

    suspend fun snapshot(environment: JSONObject): ByteArray {
        check(worker.isActive) { "调试日志服务已停止，请重新打开应用" }
        val reply = CompletableDeferred<ByteArray>()
        val stopped = worker.invokeOnCompletion { reply.completeExceptionally(IllegalStateException("调试日志服务已停止，请重新打开应用")) }
        try {
            commands.send(Command.Export(environment, reply))
            return reply.await()
        } finally { stopped.dispose(); reply.cancel() }
    }

    private fun append(line: String, durable: Boolean) {
        check(directory.isDirectory || directory.mkdirs()) { "无法保存调试日志" }
        val current = File(directory, "current.jsonl")
        // A killed process can leave a partial final line. Separate the next
        // complete event so one damaged line cannot swallow future events.
        val separator = if (current.isFile && current.length() > 0) RandomAccessFile(current, "r").use {
            it.seek(it.length() - 1); if (it.read() == 10) "" else "\n"
        } else ""
        val bytes = (separator + line + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxFileBytes)
        if (current.length() + bytes.size > maxFileBytes) {
            val previous = File(directory, "previous.jsonl")
            check(!previous.exists() || previous.delete())
            check(!current.exists() || current.renameTo(previous))
        }
        FileOutputStream(current, true).use { stream -> stream.write(bytes); if (durable) stream.fd.sync() }
    }

    private fun export(environment: JSONObject): ByteArray {
        val events = JSONArray()
        var unreadable = 0
        for (name in listOf("previous.jsonl", "current.jsonl")) {
            val file = File(directory, name)
            if (!file.isFile) continue
            if (file.length() > maxFileBytes) { unreadable++; continue }
            file.forEachLine(Charsets.UTF_8) { line ->
                try { events.put(JSONObject(line)) } catch (_: Exception) { unreadable++ }
            }
        }
        return JSONObject().put("format", FORMAT).put("schema", 1).put("exportedAtMs", wallTime())
            .put("maxRetainedBytes", maxFileBytes * 2)
            .put("environment", environment).put("droppedThisProcess", dropped.get())
            .put("unreadableLines", unreadable).put("storageWarning", storageError.value ?: JSONObject.NULL)
            .put("privacy", "No business identities, field contents, record hashes, screenshots, credentials or device identifiers.")
            .put("events", events).toString(2).toByteArray(Charsets.UTF_8)
    }
    companion object { const val FORMAT = "lighttap-custom-debug" }
}

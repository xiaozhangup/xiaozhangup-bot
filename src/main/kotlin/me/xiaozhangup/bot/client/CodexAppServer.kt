package me.xiaozhangup.bot.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class CodexAppServer(
    private val workingDirectory: File,
    private val onNotification: (JsonObject) -> Unit,
    private val onStopped: () -> Unit
) : AutoCloseable {
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val writeLock = Any()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var closing = false

    @Volatile
    private var initialized = false

    fun request(method: String, params: JsonObject): JsonObject {
        ensureStarted()
        return sendRequest(method, params)
    }

    fun respond(id: JsonElement, result: JsonObject) {
        send(buildJsonObject {
            put("id", id)
            put("result", result)
        })
    }

    @Synchronized
    private fun ensureStarted() {
        if (process?.isAlive == true) return
        check(!closing) { "Codex App Server 已关闭" }

        val started = ProcessBuilder(codexAppServerCommand())
            .directory(workingDirectory)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        process = started
        writer = started.outputWriter(UTF_8)
        startReader(started)

        try {
            sendRequest(
                "initialize",
                buildJsonObject {
                    put("clientInfo", buildJsonObject {
                        put("name", "xiaozhangup_bot")
                        put("title", "xiaozhangup-bot")
                        put("version", "0.1.0")
                    })
                    put("capabilities", buildJsonObject { put("experimentalApi", true) })
                }
            )
            send(buildJsonObject {
                put("method", "initialized")
                put("params", buildJsonObject {})
            })
            initialized = true
        } catch (e: Exception) {
            runCatching { writer?.close() }
            started.destroyForcibly()
            throw e
        }
    }

    private fun sendRequest(method: String, params: JsonObject): JsonObject {
        val id = ids.incrementAndGet()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        try {
            send(buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            })
            val response = future.get(30, TimeUnit.SECONDS)
            response["error"]?.jsonObject?.let { error ->
                throw CodexRpcException(error.string("message") ?: "Codex App Server 请求失败", error)
            }
            return response["result"]?.jsonObject ?: buildJsonObject {}
        } finally {
            pending.remove(id)
        }
    }

    private fun send(message: JsonObject) {
        synchronized(writeLock) {
            val output = writer ?: throw IllegalStateException("Codex App Server 未启动")
            output.write(message.toString())
            output.newLine()
            output.flush()
        }
    }

    private fun startReader(started: Process) {
        Thread {
            try {
                started.inputStream.bufferedReader(UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                            ?: return@forEach
                        val id = message["id"]?.jsonPrimitive?.content?.toLongOrNull()
                        if (id != null && ("result" in message || "error" in message)) {
                            pending[id]?.complete(message)
                        } else {
                            runCatching { onNotification(message) }
                        }
                    }
                }
            } finally {
                val error = IllegalStateException("Codex App Server 已停止")
                pending.values.forEach { it.completeExceptionally(error) }
                pending.clear()
                writer = null
                process = null
                if (initialized && !closing) onStopped()
                initialized = false
            }
        }.apply {
            name = "codex-app-server"
            isDaemon = true
            start()
        }
    }

    override fun close() {
        closing = true
        val running = process ?: return
        runCatching { writer?.close() }
        if (!running.waitFor(2, TimeUnit.SECONDS)) {
            val descendants = running.descendants().use { it.iterator().asSequence().toList() }
            running.destroyForcibly()
            descendants.forEach { it.destroyForcibly() }
        }
    }
}

internal fun codexAppServerCommand() = listOf(
    "codex",
    "--dangerously-bypass-hook-trust",
    "app-server",
    "--stdio",
    "-c",
    "model=\"gpt-5.6-luna\"",
    "-c",
    "model_reasoning_effort=\"max\"",
    "-c",
    "features.memories=false"
)

internal class CodexRpcException(
    message: String,
    val error: JsonObject
) : RuntimeException(message) {
    fun isNonSteerable(): Boolean = error.toString().contains("activeTurnNotSteerable")

    fun isNoActiveTurn(): Boolean = message?.contains("no active turn", ignoreCase = true) == true
}

internal fun JsonObject.string(key: String): String? =
    runCatching { get(key)?.jsonPrimitive?.content }.getOrNull()

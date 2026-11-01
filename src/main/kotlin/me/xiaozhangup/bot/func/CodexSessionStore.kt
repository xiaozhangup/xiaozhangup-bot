package me.xiaozhangup.bot.func

import kotlinx.serialization.json.*
import me.xiaozhangup.bot.util.dataFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Empty IDs record an explicit /x new across restarts. */
internal class CodexSessionStore {
    private val file by lazy { File(dataFolder("codex_sessions"), "current.json") }
    private val current by lazy {
        if (file.exists()) Json.parseToJsonElement(file.readText()).jsonObject.toMutableMap()
        else mutableMapOf<String, JsonElement>()
    }

    @Synchronized
    fun get(instanceId: String): String? = current[instanceId]?.jsonPrimitive?.content

    @Synchronized
    fun set(instanceId: String, threadId: String) {
        val updated = current.toMutableMap().apply { put(instanceId, JsonPrimitive(threadId)) }
        val temporary = Files.createTempFile(file.parentFile.toPath(), "current-", ".json")
        try {
            Files.writeString(temporary, JsonObject(updated).toString())
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        current.clear()
        current.putAll(updated)
    }
}

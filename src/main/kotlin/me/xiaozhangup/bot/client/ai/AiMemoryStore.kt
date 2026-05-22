package me.xiaozhangup.bot.client.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.xiaozhangup.bot.util.dataFolder
import java.io.File
import java.nio.charset.StandardCharsets

object AiMemoryStore {

    @Serializable
    data class MemoryItem(val id: Int, val content: String)

    @Serializable
    private data class State(
        val nextId: Int = 1,
        val items: MutableList<MemoryItem> = mutableListOf()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val file: File by lazy { File(dataFolder("ai_memory"), "memory.json") }
    private var state: State = State()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        if (file.exists()) {
            state = runCatching {
                json.decodeFromString(State.serializer(), file.readText(StandardCharsets.UTF_8))
            }.getOrDefault(State())
        }
        loaded = true
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(State.serializer(), state), StandardCharsets.UTF_8)
    }

    @Synchronized
    fun add(content: String): MemoryItem {
        ensureLoaded()
        val item = MemoryItem(state.nextId, content)
        state = state.copy(nextId = state.nextId + 1, items = state.items.apply { add(item) })
        save()
        return item
    }

    @Synchronized
    fun remove(id: Int): MemoryItem? {
        ensureLoaded()
        val idx = state.items.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val removed = state.items.removeAt(idx)
        save()
        return removed
    }

    @Synchronized
    fun list(): List<MemoryItem> {
        ensureLoaded()
        return state.items.toList()
    }

    @Synchronized
    fun asPromptSuffix(): String {
        ensureLoaded()
        if (state.items.isEmpty()) return ""
        return buildString {
            append("\n\n### 用户附加要求（如果与当前任务相关，则必须遵守）\n")
            state.items.forEachIndexed { i, m ->
                append(i + 1).append(". ").append(m.content).append('\n')
            }
        }
    }
}

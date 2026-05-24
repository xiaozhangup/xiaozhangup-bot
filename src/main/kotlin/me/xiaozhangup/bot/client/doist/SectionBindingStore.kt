package me.xiaozhangup.bot.client.doist

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets

class SectionBindingStore(dataFolder: File) {

    private val file = File(dataFolder, "section_binding.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    @Serializable
    private data class State(val groups: MutableMap<String, String> = mutableMapOf())

    private val state: State = load()

    private fun load(): State {
        if (!file.exists()) return State()
        return try {
            json.decodeFromString(State.serializer(), file.readText(StandardCharsets.UTF_8))
        } catch (_: Exception) {
            State()
        }
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(State.serializer(), state), StandardCharsets.UTF_8)
    }

    @Synchronized
    fun get(sourceId: String): String? = state.groups[sourceId]

    @Synchronized
    fun set(sourceId: String, sectionId: String) {
        state.groups[sourceId] = sectionId
        save()
    }

    @Synchronized
    fun remove(sourceId: String): Boolean {
        val removed = state.groups.remove(sourceId) != null
        if (removed) save()
        return removed
    }
}

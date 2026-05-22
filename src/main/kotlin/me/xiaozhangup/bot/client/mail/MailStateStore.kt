package me.xiaozhangup.bot.client.mail

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets

class MailStateStore(dataFolder: File) {

    private val file = File(dataFolder, "state.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    @Serializable
    private data class State(val lastRun: MutableMap<String, Long> = mutableMapOf())

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
    fun getLastRun(mailboxId: String, slot: String): Long? {
        return state.lastRun["${mailboxId}_$slot"]
    }

    @Synchronized
    fun setLastRun(mailboxId: String, slot: String, ts: Long) {
        state.lastRun["${mailboxId}_$slot"] = ts
        save()
    }
}

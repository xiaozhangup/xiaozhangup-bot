package me.xiaozhangup.bot.person

import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.util.dataFolder
import me.xiaozhangup.bot.util.info
import me.xiaozhangup.bot.util.warning
import java.io.File
import java.util.Properties

data class PersonTarget(val type: Message.Type, val id: String)

data class PersonProfile(
    val name: String,
    val target: PersonTarget,
    val style: String,
    val historySize: Int,
    val debounceMs: Long,
    val groupMaxWaitMs: Long,
    val baseDelayMs: Long,
    val charDelayMs: Long,
    val maxDelayMs: Long
)

object PersonProfileLoader {
    fun load(common: Properties): List<PersonProfile> {
        val root = dataFolder("person")
        PersonPromptLoader.ensureDefault(root)
        val folder = File(root, "profiles").apply { mkdirs() }
        val example = File(folder, "private.properties.example")
        if (!example.exists()) {
            PersonProfileLoader::class.java.getResourceAsStream("/person/profile.properties.example")!!.use {
                input -> example.outputStream().use { input.copyTo(it) }
            }
        }
        // 仅首次升级时迁移真实存在的旧白名单；删除配置后不会再次恢复。
        val migrated = File(root, ".profiles-migrated")
        if (!migrated.exists()) {
            if (folder.listFiles()!!.none { it.extension == "properties" }) migrateLegacy(common, folder)
            migrated.writeText("1\n")
        }
        val profiles = folder.listFiles()!!.filter { it.isFile && it.extension == "properties" }
            .sortedBy { it.name }.mapNotNull { file ->
                try {
                    read(file, root)
                } catch (e: Exception) {
                    warning("[PersonChat] 配置 ${file.name} 未加载：${e.message}")
                    null
                }
            }
        return profiles.groupBy { it.target }.flatMap { (target, matches) ->
            if (matches.size > 1) {
                warning("[PersonChat] ${target.type}:${target.id} 重复配置，均未启用：${matches.joinToString { it.name }}")
                emptyList()
            } else matches
        }
    }

    private fun read(file: File, root: File): PersonProfile? {
        val p = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
        if (!p.getProperty("enabled", "false").trim().toBooleanStrict()) return null
        val type = when (p.getProperty("target.type")?.trim()) {
            "private" -> Message.Type.USER
            "group" -> Message.Type.GROUP
            else -> error("target.type 必须为 private 或 group")
        }
        val id = p.getProperty("target.id", "").trim()
        val number = id.toLongOrNull()
        require(number != null && number > 0) { "target.id 必须为有效的 QQ 号或群号" }
        val path = p.getProperty("prompt.file", "").trim()
        require(path.isNotEmpty()) { "缺少 prompt.file" }
        val promptFile = File(path).let { if (it.isAbsolute) it else File(root, path) }
        val style = promptFile.readText(Charsets.UTF_8)
        require(style.isNotBlank()) { "风格文档为空：$promptFile" }
        val debounce = p.number("debounce.ms", 2500, 0..300_000L)
        val maxWait = p.number("group.max.wait.ms", 10_000, 1..300_000L)
        require(type != Message.Type.GROUP || maxWait >= debounce) { "group.max.wait.ms 不能小于 debounce.ms" }
        val baseDelay = p.number("send.base.delay.ms", 600, 0..300_000L)
        val maxDelay = p.number("send.max.delay.ms", 8000, 0..300_000L)
        require(maxDelay >= baseDelay) { "send.max.delay.ms 不能小于 send.base.delay.ms" }
        return PersonProfile(
            file.nameWithoutExtension, PersonTarget(type, number.toString()), style,
            p.number("history.size", 80, 1..10_000L).toInt(), debounce, maxWait, baseDelay,
            p.number("send.char.delay.ms", 180, 0..300_000L), maxDelay
        )
    }

    private fun Properties.number(key: String, default: Long, range: LongRange): Long {
        val value = getProperty(key)?.trim()?.toLong() ?: default
        require(value in range) { "$key 必须在 ${range.first}..${range.last} 之间" }
        return value
    }

    private fun migrateLegacy(common: Properties, folder: File) {
        for ((key, type) in listOf("enabled.groups" to "group", "enabled.users" to "private")) {
            for (id in common.getProperty(key, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
                require(id.toLongOrNull()?.let { it > 0 } == true) { "旧配置 $key 包含无效目标" }
                val p = Properties().apply {
                    setProperty("enabled", "true")
                    setProperty("target.type", type)
                    setProperty("target.id", id)
                    setProperty("prompt.file", "my-chat-style-system-prompt.md")
                    setProperty("history.size", common.getProperty("history.size", "80"))
                    val wait = (common.getProperty("debounce.seconds", "10").toLong() * 1000).coerceAtLeast(0)
                    setProperty("debounce.ms", wait.toString())
                    setProperty("group.max.wait.ms", maxOf(10_000, wait).toString())
                    setProperty("send.char.delay.ms", common.getProperty("char.delay.ms", "180"))
                }
                File(folder, "legacy-$type-$id.properties").writer(Charsets.UTF_8).use {
                    p.store(it, "Migrated from person.properties")
                }
                info("[PersonChat] 已迁移旧会话：$type:$id")
            }
        }
    }
}

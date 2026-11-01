package me.xiaozhangup.bot.func

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.xiaozhangup.bot.client.doist.TodoistClient
import me.xiaozhangup.bot.client.doist.UpdateTaskRequest
import me.xiaozhangup.bot.client.mail.ImapClient
import me.xiaozhangup.bot.client.mail.MailboxConfig
import me.xiaozhangup.bot.util.properties
import java.time.Instant

/** Personal service credentials stay in the host bot. */
internal class CodexPersonalTools {
    private val todoist by lazy {
        val token = properties("doist_task").getProperty("doist.token").orEmpty()
        require(token.isNotBlank()) { "未配置 Todoist token" }
        TodoistClient(token)
    }
    private val mailboxes by lazy {
        val cfg = properties("mail_summary")
        cfg.stringPropertyNames().mapNotNull { Regex("mailbox\\.(\\d+)\\.host").matchEntire(it)?.groupValues?.get(1) }
            .sortedBy(String::toInt).map { id ->
                fun value(key: String) = cfg.getProperty("mailbox.$id.$key").orEmpty()
                require(listOf("host", "user", "password").all { value(it).isNotBlank() }) { "邮箱 $id 配置不完整" }
                MailboxConfig(id, value("host").trim(), value("port").toIntOrNull() ?: 993,
                    value("user").trim(), value("password"), value("label").ifBlank { "邮箱 $id" },
                    value("ssl").toBooleanStrictOrNull() ?: true)
            }
    }

    fun call(name: String, args: JsonObject): String = when (name) {
        "todoist" -> todoist(args)
        "read_mail" -> readMail(args).toString()
        else -> error("不支持的个人工具：$name")
    }

    private fun todoist(args: JsonObject): String {
        val action = args.requiredText("action")
        val priority = args["priority"]?.jsonPrimitive?.int.also {
            require(it == null || it in 1..4) { "priority 必须为 1 到 4" }
        }
        return when (action) {
            "list_projects" -> Json.encodeToString(todoist.getProjects())
            "list_sections" -> Json.encodeToString(todoist.getSections(args.text("project_id")))
            "list_tasks" -> {
                val tasks = todoist.getTasks(args.text("project_id"), args.text("section_id"), args.text("label"))
                val offset = args.integer("offset", 0, 0..Int.MAX_VALUE)
                val limit = args.integer("limit", 50, 1..100)
                buildJsonObject {
                    put("tasks", Json.encodeToJsonElement(tasks.drop(offset).take(limit)))
                    put("total", tasks.size)
                    if (offset.toLong() + limit < tasks.size) put("next_offset", offset + limit)
                }.toString()
            }
            "get_task" -> Json.encodeToString(todoist.getTask(args.requiredText("task_id")))
            "create_task" -> Json.encodeToString(todoist.createTask(
                content = args.requiredText("content"), projectId = args.text("project_id"), sectionId = args.text("section_id"),
                description = args.text("description"), priority = priority, dueString = args.text("due_string"),
                dueDate = args.text("due_date"), dueLang = args.text("due_lang")
            ))
            "update_task" -> {
                require(listOf("content", "description", "priority", "due_string", "due_date", "section_id").any(args::containsKey)) {
                    "更新任务至少提供一个修改字段"
                }
                Json.encodeToString(todoist.updateTask(args.requiredText("task_id"), UpdateTaskRequest(
                    content = args.text("content"), description = args.text("description"), priority = priority,
                    dueString = args.text("due_string"), dueDate = args.text("due_date"), sectionId = args.text("section_id")
                )))
            }
            "complete_task", "reopen_task", "delete_task" -> {
                val id = args.requiredText("task_id")
                val success = when (action) {
                    "complete_task" -> todoist.closeTask(id)
                    "reopen_task" -> todoist.reopenTask(id)
                    else -> todoist.deleteTask(id)
                }
                check(success) { "Todoist 未确认操作成功：$action；请查询任务状态后再决定下一步" }
                buildJsonObject { put("action", action); put("task_id", id); put("success", true) }.toString()
            }
            else -> error("不支持的 Todoist 操作：$action")
        }
    }

    private fun readMail(args: JsonObject): JsonObject = when (args.requiredText("action")) {
        "list_mailboxes" -> buildJsonObject {
            put("mailboxes", buildJsonArray { mailboxes.forEach { box -> add(buildJsonObject {
                put("id", box.id); put("label", box.label)
            }) } })
        }
        "list_messages" -> {
            val id = args.requiredText("mailbox_id")
            val mailbox = mailboxes.firstOrNull { it.id == id } ?: error("邮箱不存在：$id")
            val until = args.text("until")?.let(Instant::parse) ?: Instant.now()
            val since = args.text("since")?.let(Instant::parse) ?: until.minusSeconds(24 * 60 * 60)
            require(since < until) { "since 必须早于 until" }
            val maxChars = args.integer("body_max_chars", 12000, 1..50000)
            val offset = args.integer("offset", 0, 0..Int.MAX_VALUE)
            val limit = args.integer("limit", 20, 1..100)
            val messages = ImapClient(mailbox).fetchSince(since.toEpochMilli(), until.toEpochMilli(), maxChars)
                .sortedByDescending { it.receivedAt }
            buildJsonObject {
                put("mailbox_id", id); put("label", mailbox.label)
                put("since", since.toString()); put("until", until.toString()); put("total", messages.size)
                put("body_max_chars", maxChars)
                put("messages", buildJsonArray { messages.drop(offset).take(limit).forEach { mail -> add(buildJsonObject {
                    put("from", mail.from); put("subject", mail.subject); put("body", mail.body)
                    put("received_at", Instant.ofEpochMilli(mail.receivedAt).toString())
                    put("body_truncated", mail.body.length > maxChars)
                }) } })
                if (offset.toLong() + limit < messages.size) put("next_offset", offset + limit)
            }
        }
        else -> error("不支持的邮件操作")
    }
}

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.requiredText(key: String): String = text(key)?.takeIf(String::isNotBlank)
    ?: throw IllegalArgumentException("缺少参数：$key")
private fun JsonObject.integer(key: String, default: Int, range: IntRange): Int =
    (this[key]?.jsonPrimitive?.int ?: default).also { require(it in range) { "$key 超出允许范围" } }

internal fun personalToolDefinitions(): List<JsonObject> = listOf(
    personalTool("todoist", "管理用户的 Todoist 待办。先查询项目、板块和任务获取真实 ID；创建、修改、完成、重新打开或删除任务按用户要求执行。", buildJsonObject {
        put("action", enumField("操作", listOf("list_projects", "list_sections", "list_tasks", "get_task", "create_task", "update_task", "complete_task", "reopen_task", "delete_task")))
        for ((name, description) in mapOf(
            "project_id" to "项目 ID", "section_id" to "板块 ID", "task_id" to "任务 ID",
            "content" to "任务标题，create_task 必填", "description" to "任务说明", "label" to "筛选任务标签",
            "due_string" to "自然语言截止时间，例如明天下午三点", "due_date" to "截止日期 YYYY-MM-DD",
            "due_lang" to "自然语言日期的语言，例如 zh")) put(name, stringField(description))
        put("priority", integerField("优先级：1 普通，4 最高", 1, 4))
        put("offset", integerField("分页偏移，默认 0", 0, Int.MAX_VALUE))
        put("limit", integerField("每页任务数，默认 50", 1, 100))
    }),
    personalTool("read_mail", "只读查询用户邮箱。先 list_mailboxes 获取邮箱 ID，再 list_messages 读取收件箱邮件，返回发件人、主题、接收时间和正文。默认近 24 小时，按接收时间倒序；不改变已读状态，不发送邮件，不读取附件。翻页沿用返回的 since、until。", buildJsonObject {
        put("action", enumField("操作", listOf("list_mailboxes", "list_messages")))
        put("mailbox_id", stringField("邮箱 ID，list_messages 必填"))
        put("since", stringField("起始时间（含），ISO 8601，必须带时区，例如 2026-10-31T00:00:00+08:00"))
        put("until", stringField("结束时间（不含），ISO 8601，必须带时区，默认现在"))
        put("body_max_chars", integerField("每封正文截断长度，默认 12000 字符", 1, 50000))
        put("offset", integerField("分页偏移，默认 0", 0, Int.MAX_VALUE))
        put("limit", integerField("每页邮件数，默认 20", 1, 100))
    })
)

private fun personalTool(name: String, description: String, fields: JsonObject) = buildJsonObject {
    put("type", "function"); put("name", name); put("description", description)
    put("inputSchema", buildJsonObject {
        put("type", "object"); put("properties", fields)
        put("required", buildJsonArray { add("action") }); put("additionalProperties", false)
    })
}
private fun stringField(description: String) = buildJsonObject { put("type", "string"); put("description", description) }
private fun enumField(description: String, options: List<String>) = buildJsonObject {
    put("type", "string"); put("description", description); put("enum", buildJsonArray { options.forEach { add(it) } })
}
private fun integerField(description: String, min: Int, max: Int) = buildJsonObject {
    put("type", "integer"); put("description", description); put("minimum", min); put("maximum", max)
}

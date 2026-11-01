package me.xiaozhangup.bot.func

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.xiaozhangup.bot.client.CodexAppServer
import me.xiaozhangup.bot.client.CodexRpcException
import me.xiaozhangup.bot.client.GroupContainer
import me.xiaozhangup.bot.client.CodexHostAuth
import me.xiaozhangup.bot.client.string
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.Group
import me.xiaozhangup.bot.port.GroupFile
import me.xiaozhangup.bot.ove.OverUser
import me.xiaozhangup.bot.port.Source
import me.xiaozhangup.bot.port.msg.MessageComponent
import me.xiaozhangup.bot.port.msg.obj.AtComponent
import me.xiaozhangup.bot.port.msg.obj.ImageComponent
import me.xiaozhangup.bot.port.msg.obj.QuoteComponent
import me.xiaozhangup.bot.port.msg.obj.StringComponent
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.properties
import me.xiaozhangup.bot.util.submit
import java.io.File
import java.net.URL
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class CodexCommand : EventUnit(
    "codex_command",
    "Codex 任务命令",
    2
), AutoCloseable {
    private val config by lazy { properties("codex") }
    private val enabledGroups by lazy {
        config.getProperty("enabled.groups")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
            ?: emptySet()
    }
    private val enabledUsers by lazy {
        config.getProperty("enabled.users")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
            ?: emptySet()
    }

    private fun chatKey(source: Source) = "${if (source is Group) "group" else "private"}-${source.id}"

    private fun instanceId(source: Source): String {
        val type = if (source is Group) "group" else "private"
        return config.getProperty("session.$type.${source.id}")?.trim()
            ?: if (source is Group) source.id else chatKey(source)
    }

    override fun interceptFriendMessage(message: Message): Boolean {
        if (message.source.id !in enabledUsers) return false
        val raw = message.getMessage().trim()
        if (raw.startsWith("/") && !Regex("^/(?:x|codex)\\s+(?:new|resume)(?:\\s|$)").containsMatchIn(raw)) return false
        handleMessage(message, privateChat = true)
        return true
    }

    private val sessionStore by lazy { CodexSessionStore() }
    private val historyChoices = ConcurrentHashMap<String, List<JsonObject>>()
    private val personalTools by lazy { CodexPersonalTools() }
    private val toolDefinitions by lazy {
        (listOf(qqFileTool(), qqGroupFileListTool(), qqGroupFileDownloadTool()) + personalToolDefinitions())
            .associateBy { it.getValue("name").jsonPrimitive.content }
    }

    private fun allowedTools(instanceId: String): Set<String> {
        val names = config.getProperty("tools.$instanceId", config.getProperty("tools.default",
            "send_qq_file,list_qq_group_files,download_qq_group_file"))
            .split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        require(names.all(toolDefinitions::containsKey)) { "会话组 $instanceId 配置了未知工具：${names - toolDefinitions.keys}" }
        return names
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val threadInstances = ConcurrentHashMap<String, String>()
    private val instanceSources = ConcurrentHashMap<String, Source>()
    private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<CodexInput>>()
    private val workers = ConcurrentHashMap.newKeySet<String>()
    private val cliBusyInstances = ConcurrentHashMap.newKeySet<String>()
    // 按聊天分别记录消息 ID，群号和 QQ 号使用不同前缀。
    private val sentCodexMessageIds = ConcurrentHashMap<String, MutableSet<Int>>()

    private val containers = ConcurrentHashMap<String, GroupContainer>()
    private val servers = ConcurrentHashMap<String, CodexAppServer>()
    private val hostAuth by lazy {
        CodexHostAuth(File(config.getProperty("auth.home", File(System.getProperty("user.home"), ".codex").path)), runtimeDirectory())
    }

    override fun onGroupMessage(message: Message) {
        if (message.source.id !in enabledGroups) return

        handleMessage(message, privateChat = false)
    }

    private fun handleMessage(message: Message, privateChat: Boolean) {
        val quotes = message.component.filterIsInstance<QuoteComponent>()
        val raw = message.component.filterIsInstance<StringComponent>().joinToString("") { it.context }.trim()
        when {
            raw == "/codex" || raw == "/x" -> if (message.component.all { it is StringComponent }) {
                message.addReply("用法：/codex <任务> 或 /x <任务>；/x new 开启新会话，/x resume 查看历史")
            } else {
                handleCodex(message, "")
            }
            Regex("^/(?:codex|x)\\s").containsMatchIn(raw) -> handleCodex(message, raw.replaceFirst(Regex("^/\\S+\\s+"), "").trim())
            raw == "/cli" -> message.addReply("用法：/cli <bash 命令>")
            raw.startsWith("/cli ") -> handleCli(message, raw.removePrefix("/cli").trim())
            // 引用了本功能(Codex)发出的消息：即使没有 /x 前缀，也当作 /x 处理
            privateChat -> handleCodex(message, "", quoteTriggered = true)
            else -> if (isQuoteToCodexMessage(quotes, sentCodexMessageIds[chatKey(message.source)]) && !raw.startsWith("/")) {
                handleCodex(message, raw, quoteTriggered = true)
            }
        }
    }

    private fun handleCodex(message: Message, content: String, quoteTriggered: Boolean = false) {
        if (content == "resume" || content.startsWith("resume ")) {
            val number = content.removePrefix("resume").trim()
            val selected = if (number.isEmpty()) null else {
                val index = number.toIntOrNull()
                val choices = historyChoices[chatKey(message.source)]
                if (index == null || index < 1 || choices == null || index > choices.size) {
                    message.addReply("请先用 /x resume 查看历史，再用 /x resume <编号> 切换")
                    return
                }
                choices[index - 1].getValue("id").jsonPrimitive.content
            }
            enqueue(instanceId(message.source), Resume(message.source, selected))
            return
        }
        if (content.startsWith("new ")) {
            message.addReply("new 后不跟任务，请在下一条 /codex 或 /x 消息中发送任务")
            return
        }
        val instanceId = instanceId(message.source)
        if (content != "new") instanceSources[instanceId] = message.source
        enqueue(
            instanceId,
            if (content == "new") Reset(message.source)
            else Prompt(message, quoteTriggered)
        )
    }

    private fun handleCli(message: Message, command: String) {
        val instanceId = instanceId(message.source)
        if (!cliBusyInstances.add(instanceId)) {
            message.addReply("上一条 /cli 命令仍在执行")
            return
        }
        submit {
            try {
                val result = container(instanceId).run(listOf("/bin/bash", "-c", command), 10)
                val output = result.output.trimEnd()
                val reply = when {
                    result.timedOut -> "命令执行超时${output.takeIf(String::isNotEmpty)?.let { "\n$it" } ?: "，暂无输出"}"
                    result.code != 0 -> "命令退出码 ${result.code}${output.takeIf(String::isNotEmpty)?.let { "\n$it" } ?: ""}"
                    output.isEmpty() -> "命令执行完成，无输出"
                    else -> output
                }
                reply.chunked(4000).forEach { sendToSource(message.source, it) }
            } catch (e: Exception) {
                sendToSource(message.source, "命令启动失败：${e.message ?: "未知错误"}")
            } finally {
                cliBusyInstances.remove(instanceId)
            }
        }
    }

    private fun enqueue(instanceId: String, input: CodexInput) {
        queues.computeIfAbsent(instanceId) { ConcurrentLinkedQueue() }.add(input)
        if (workers.add(instanceId)) submit { drain(instanceId) }
    }

    private suspend fun drain(instanceId: String) {
        val queue = queues[instanceId] ?: return
        while (true) {
            while (true) {
                val input = queue.poll() ?: break
                try {
                    when (input) {
                        is Prompt -> deliver(instanceId, input)
                        is Reset -> reset(instanceId, input.source)
                        is Resume -> resume(instanceId, input)
                    }
                } catch (e: Exception) {
                    input.source.sendMessage("Codex 执行失败：${e.message ?: "未知错误"}")
                }
            }
            workers.remove(instanceId)
            if (queue.isEmpty() || !workers.add(instanceId)) return
        }
    }

    private fun reset(instanceId: String, source: Source) {
        val session = sessions[instanceId]
        if (session?.activeTurnId != null) {
            session.deferred.add(Reset(source))
            return
        }
        sessionStore.set(instanceId, "")
        sessions.remove(instanceId)?.let { threadInstances.remove(it.threadId) }
        source.sendMessage("共享会话已重置，下一条消息将开启新会话")
    }

    private suspend fun deliver(instanceId: String, prompt: Prompt) {
        val session = sessions[instanceId] ?: restoreSession(instanceId).also { sessions[instanceId] = it }
        if (prompt.input == null) {
            prompt.input = CodexMessageInput(prompt.message, container(instanceId)).prepare(prompt.quoteTriggered)
        }
        val activeTurnId = session.activeTurnId
        if (activeTurnId == null) {
            startTurn(session, prompt)
            return
        }

        try {
            steer(session, activeTurnId, prompt)
        } catch (e: CodexRpcException) {
            val latestTurnId = session.activeTurnId
            when {
                e.isNonSteerable() -> session.deferred.add(prompt)
                e.isNoActiveTurn() -> {
                    session.activeTurnId = null
                    startTurn(session, prompt)
                }
                latestTurnId == null -> startTurn(session, prompt)
                latestTurnId != activeTurnId -> steer(session, latestTurnId, prompt)
                else -> throw e
            }
        }
    }

    private fun recentThreads(instanceId: String): List<JsonObject> = server(instanceId).request("thread/list", buildJsonObject {
        put("limit", 10)
        put("sortKey", "updated_at")
        put("sortDirection", "desc")
        put("cwd", "/workspace")
        put("sourceKinds", buildJsonArray { add(JsonPrimitive("vscode")); add(JsonPrimitive("appServer")) })
    })["data"]?.jsonArray.orEmpty().map { it.jsonObject }

    private fun restoreSession(instanceId: String): Session {
        val saved = sessionStore.get(instanceId)
        val id = saved ?: recentThreads(instanceId).firstOrNull()?.string("id")
        return if (id.isNullOrEmpty()) createSession(instanceId) else resumeSession(instanceId, id)
    }

    private fun resumeSession(instanceId: String, id: String): Session {
        val result = server(instanceId).request("thread/resume", buildJsonObject {
            put("threadId", id)
            put("model", MODEL)
            put("cwd", "/workspace")
            put("approvalPolicy", "never")
            put("sandbox", "danger-full-access")
        })
        return rememberSession(instanceId, result)
    }

    private fun resume(instanceId: String, request: Resume) {
        if (request.threadId == null) {
            val choices = recentThreads(instanceId)
            historyChoices[chatKey(request.source)] = choices
            if (choices.isEmpty()) {
                request.source.sendMessage("当前会话组没有历史会话")
                return
            }
            val active = sessions[instanceId]?.threadId ?: sessionStore.get(instanceId) ?: choices.firstOrNull()?.string("id")
            val dates = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))
            request.source.sendMessage(choices.mapIndexed { i, thread ->
                val updated = thread["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                val title = codexHistoryTitle(thread)
                "${i + 1}. ${if (thread.string("id") == active) "[当前] " else ""}${dates.format(Instant.ofEpochSecond(updated))} $title"
            }.joinToString("\n", postfix = "\n\n/x resume <编号> 切换"))
            return
        }
        val current = sessions[instanceId]
        if (current?.activeTurnId != null) {
            current.deferred.add(request)
            request.source.sendMessage("当前任务完成后切换会话")
            return
        }
        val restored = resumeSession(instanceId, request.threadId)
        current?.let { threadInstances.remove(it.threadId) }
        threadInstances[restored.threadId] = instanceId
        sessions[instanceId] = restored
        instanceSources[instanceId] = request.source
        request.source.sendMessage("已切换会话，后续消息将继续该会话")
    }

    private fun createSession(instanceId: String): Session {
        val result = server(instanceId).request("thread/start", buildJsonObject {
            put("model", MODEL)
            put("cwd", "/workspace")
            put("approvalPolicy", "never")
            put("sandbox", "danger-full-access")
            put("serviceName", "xiaozhangup-bot")
            put("dynamicTools", buildJsonArray {
                allowedTools(instanceId).forEach { add(toolDefinitions.getValue(it)) }
            })
        })
        return rememberSession(instanceId, result)
    }

    private fun rememberSession(instanceId: String, result: JsonObject): Session {
        verifyInstructionSource(result, File("/workspace/AGENTS.md"))
        val threadId = result["thread"]?.jsonObject?.string("id")
            ?: throw IllegalStateException("Codex App Server 未返回 thread id")
        sessionStore.set(instanceId, threadId)
        threadInstances[threadId] = instanceId
        return Session(instanceId, threadId)
    }

    private fun startTurn(session: Session, prompt: Prompt) {
        try {
            val result = server(session.instanceId).request("turn/start", buildJsonObject {
                put("threadId", session.threadId)
                put("input", prompt.input!!)
                put("cwd", "/workspace")
                put("model", MODEL)
                put("effort", EFFORT)
                put("approvalPolicy", "never")
                put("sandboxPolicy", buildJsonObject { put("type", "dangerFullAccess") })
            })
            session.activeTurnId = result["turn"]?.jsonObject?.string("id")
                ?: throw IllegalStateException("Codex App Server 未返回 turn id")
            if (prompt.source is Group) prompt.message.addReaction(Reaction.SPARK)
        } catch (e: Exception) {
            if (e is CodexRpcException && e.isNonSteerable()) session.deferred.add(prompt) else throw e
        }
    }

    private fun steer(session: Session, turnId: String, prompt: Prompt) {
        server(session.instanceId).request("turn/steer", buildJsonObject {
            put("threadId", session.threadId)
            put("expectedTurnId", turnId)
            put("input", prompt.input!!)
        })
        if (prompt.source is Group) prompt.message.addReaction(Reaction.SPARK)
    }

    private fun handleNotification(boundInstanceId: String, message: JsonObject) {
        if (message.string("method") == "account/chatgptAuthTokens/refresh") {
            val requestId = message["id"] ?: return
            val responder = servers[boundInstanceId] ?: return
            submit {
                runCatching { hostAuth.tokens(forceRefresh = true) }
                    .onSuccess { responder.respond(requestId, it) }
                    .onFailure { responder.respondError(requestId, "宿主登录凭据刷新失败") }
            }
            return
        }
        if (message.string("method") == "item/tool/call") {
            handleToolCall(boundInstanceId, message)
            return
        }
        val params = message["params"]?.jsonObject ?: return
        val threadId = params.string("threadId") ?: return
        val instanceId = threadInstances[threadId]?.takeIf { it == boundInstanceId } ?: return
        val session = sessions[instanceId]
        when (message.string("method")) {
            "turn/started" -> if (session?.threadId == threadId) {
                session.activeTurnId = params["turn"]?.jsonObject?.string("id")
            }

            "item/completed" -> appServerAgentMessage(message)?.let { text ->
                val content = stripMarkdown(text).trim()
                if (content.isNotBlank()) instanceSources[instanceId]?.let { sendToSource(it, content) }
            }

            "error" -> params["error"]?.jsonObject?.string("message")?.let { error ->
                instanceSources[instanceId]?.let { sendToSource(it, "Codex 执行失败：${stripMarkdown(error)}") }
            }

            "turn/completed" -> {
                val turnId = params["turn"]?.jsonObject?.string("id")
                if (session?.threadId != threadId) {
                    threadInstances.remove(threadId)
                    return
                }
                if (session.activeTurnId == turnId) session.activeTurnId = null
                while (true) enqueue(instanceId, session.deferred.poll() ?: break)
            }
        }
    }

    /**
     * 将回复发送到目标聊天并记录消息 ID。
     */
    private fun sendToSource(source: Source, text: String) {
        val ids = source.sendMessageWithIds(text)
        if (ids.isNotEmpty()) {
            val set = sentCodexMessageIds.computeIfAbsent(chatKey(source)) { ConcurrentHashMap.newKeySet() }
            set.addAll(ids)
            if (set.size > MAX_RECORDED_MESSAGE_IDS) set.clear()
        }
    }

    private fun handleToolCall(boundInstanceId: String, message: JsonObject) {
        val requestId = message["id"] ?: return
        val params = message["params"]?.jsonObject ?: return
        val responder = servers[boundInstanceId] ?: return
        val source = instanceSources[boundInstanceId]
        submit {
            val result = runCatching {
                val instanceId = params.string("threadId")?.let(threadInstances::get)?.takeIf { it == boundInstanceId }
                    ?: throw IllegalStateException("找不到当前 Codex 会话")
                requireNotNull(source) { "找不到当前 QQ 聊天" }
                val tool = params.string("tool") ?: error("缺少工具名")
                require(tool in allowedTools(instanceId)) { "当前会话组未启用工具：$tool" }
                val arguments = params["arguments"]?.jsonObject
                when (tool) {
                    "todoist", "read_mail" -> personalTools.call(tool, requireNotNull(arguments) { "缺少工具参数" })
                    SEND_QQ_FILE -> {
                        val path = arguments?.string("path") ?: throw IllegalArgumentException("缺少文件路径")
                        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
                        require(fileName.isNotBlank() && fileName != "." && fileName != "..") { "无效文件名" }
                        val exported = container(instanceId).exportFile(path)
                        val tempDirectory = Files.createTempDirectory("qbot-send-").toFile()
                        try {
                            val file = File(tempDirectory, fileName)
                            Files.move(exported.toPath(), file.toPath())
                            when (source) {
                                is Group -> source.sendFile(file)
                                is OverUser -> source.sendFile(file)
                                else -> error("当前聊天不支持发送文件")
                            }
                            "已发送文件：$fileName"
                        } finally {
                            exported.delete()
                            tempDirectory.deleteRecursively()
                        }
                    }

                    LIST_QQ_GROUP_FILES -> formatGroupFiles(requireGroup(source).listFiles())
                    DOWNLOAD_QQ_GROUP_FILE -> {
                        val fileName = arguments?.string("file_name") ?: throw IllegalArgumentException("缺少群文件名")
                        val directory = arguments.string("directory") ?: throw IllegalArgumentException("缺少下载目录")
                        val file = downloadGroupFile(requireGroup(source), instanceId, fileName, directory)
                        "已下载群文件：$fileName\n保存到：$file"
                    }

                    else -> throw IllegalArgumentException("不支持的动态工具：${params.string("tool")}")
                }
            }
            runCatching {
                responder.respond(
                    requestId,
                    dynamicToolResult(result.isSuccess, result.getOrElse { "工具执行失败：${it.message ?: "未知错误"}" })
                )
            }
        }
    }

    private fun requireGroup(source: Source): Group = source as? Group
        ?: throw IllegalStateException("群文件列表和下载仅支持群聊，请从对应群聊发起任务")

    private suspend fun downloadGroupFile(group: Group, instanceId: String, fileName: String, directoryPath: String): String {
        val remoteFile = selectGroupFile(group.listFiles(), fileName)
        val targetName = remoteFile.name.substringAfterLast('/').substringAfterLast('\\')
        require(targetName.isNotBlank() && targetName != "." && targetName != "..") { "群文件名无效" }
        val connection = URL(group.getFileUrl(remoteFile.id)).openConnection().apply {
            connectTimeout = GROUP_FILE_TIMEOUT_MS
            readTimeout = GROUP_FILE_TIMEOUT_MS
        }
        val temporary = File.createTempFile("qbot-download-", ".part")
        try {
            connection.getInputStream().use { input -> temporary.outputStream().use(input::copyTo) }
            return container(instanceId).importFile(temporary, "${directoryPath.trimEnd('/')}/$targetName")
        } finally { temporary.delete() }
    }

    private fun container(instanceId: String) = containers.computeIfAbsent(instanceId) { GroupContainer(it, config) }

    private fun server(instanceId: String): CodexAppServer = servers.computeIfAbsent(instanceId) {
        CodexAppServer(
            workingDirectory = runtimeDirectory(),
            onNotification = { message -> handleNotification(instanceId, message) },
            onStopped = {
                sessions.remove(instanceId)
                threadInstances.entries.removeIf { it.value == instanceId }
                instanceSources[instanceId]?.let { source ->
                    sendToSource(source, "Codex App Server 已停止，下一条任务将恢复上次会话")
                }
            },
            command = container(instanceId)::appServerCommand,
            onReady = { server ->
                server.request("account/login/start", buildJsonObject {
                    put("type", "chatgptAuthTokens")
                    hostAuth.tokens().forEach { (key, value) -> put(key, value) }
                })
            }
        )
    }

    private fun runtimeDirectory(): File = File(requireNotNull(config.getProperty("groups.directory")), "_runtime").also {
        require(it.isDirectory) { "容器公共目录不存在：$it" }
    }

    override fun close() {
        servers.values.forEach { runCatching { it.close() } }
        containers.values.forEach { runCatching { it.close() } }
    }

    private data class Session(
        val instanceId: String,
        val threadId: String,
        @Volatile var activeTurnId: String? = null,
        val deferred: ConcurrentLinkedQueue<CodexInput> = ConcurrentLinkedQueue()
    )

    private sealed interface CodexInput {
        val source: Source
    }

    private data class Prompt(val message: Message, val quoteTriggered: Boolean, var input: JsonArray? = null) : CodexInput {
        override val source = message.source
    }

    private data class Reset(override val source: Source) : CodexInput
    private data class Resume(override val source: Source, val threadId: String?) : CodexInput

    private companion object {
        const val MODEL = "gpt-6.1-terra"
        const val EFFORT = "xhigh"
        const val GROUP_FILE_TIMEOUT_MS = 30_000
        const val MAX_RECORDED_MESSAGE_IDS = 10_000
        const val SEND_QQ_FILE = "send_qq_file"
        const val LIST_QQ_GROUP_FILES = "list_qq_group_files"
        const val DOWNLOAD_QQ_GROUP_FILE = "download_qq_group_file"
    }
}

private val historyMetadata = Regex(
    """\[(?:当前聊天[：:]|当前发言者[：:]|引用消息(?: ID=|[：:])|发送者=|/?合并转发|/引用消息|图片\d*[：:]|画面时间[：:]|原始语音文件[：:]|视频文件[：:]|GIF 文件[：:]|附件[：:])[^\]]*(?:\]|$)"""
)

internal fun codexHistoryTitle(thread: JsonObject): String {
    val raw = thread.string("name")?.takeIf(String::isNotBlank) ?: thread.string("preview").orEmpty()
    val content = raw.replace(historyMetadata, " ")
        .replace(Regex("""https?://[^\s]+""")) { it.value.substringAfter("://").substringBefore('/') }
        .replace(Regex("""/workspace/\S*"""), " ")
        .replace(Regex("""\s+"""), " ").trim()
    if (content.isEmpty()) return when {
        raw.contains("[图片") -> "图片会话"
        raw.contains("[附件") -> "附件会话"
        else -> "无文字预览"
    }
    return if (content.length > 36) content.take(36) + "…" else content
}

internal fun verifyInstructionSource(result: JsonObject, expected: File?) {
    if (expected == null) return
    val expectedPath = expected.canonicalPath
    val sources = result["instructionSources"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
    require(sources.any { File(it).canonicalPath == expectedPath }) {
        "Codex 未自动加载 AGENTS.md：$expectedPath；已加载：${sources.joinToString().ifEmpty { "无" }}"
    }
}

internal fun qqFileTool() = buildJsonObject {
    put("type", "function")
    put("name", "send_qq_file")
    put("description", "将 /workspace 内已存在的文件发送到当前 QQ 聊天（群聊或私聊）。使用路径中转上传，不通过 WebSocket 传输文件内容；导出和上传分别最多等待 5 分钟。仅在用户明确要求接收文件时调用。")
    put("inputSchema", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "相对 /workspace 或位于其中的绝对文件路径")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("path")) })
        put("additionalProperties", false)
    })
}

internal fun qqGroupFileListTool() = buildJsonObject {
    put("type", "function")
    put("name", "list_qq_group_files")
    put("description", "列出当前 QQ 群的群文件，返回文件名和上传日期。")
    put("inputSchema", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
        put("additionalProperties", false)
    })
}

internal fun qqGroupFileDownloadTool() = buildJsonObject {
    put("type", "function")
    put("name", "download_qq_group_file")
    put("description", "把当前 QQ 群的指定群文件下载到 /workspace 内的目录。存在同名文件时先调用 list_qq_group_files，并传入列表中的完整名称。")
    put("inputSchema", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("file_name", buildJsonObject {
                put("type", "string")
                put("description", "群文件名，文件夹内文件使用列表返回的 文件夹/文件名")
            })
            put("directory", buildJsonObject {
                put("type", "string")
                put("description", "相对 /workspace 或位于其中的绝对目录；目录不存在时自动创建")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("file_name"))
            add(JsonPrimitive("directory"))
        })
        put("additionalProperties", false)
    })
}

internal fun dynamicToolResult(success: Boolean, text: String) = buildJsonObject {
    put("success", success)
    put("contentItems", buildJsonArray {
        add(buildJsonObject {
            put("type", "inputText")
            put("text", text)
        })
    })
}

internal fun resolveShareableFile(workingDirectory: File, path: String): File {
    val root = workingDirectory.canonicalFile
    val file = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalFile
    require(file.toPath().startsWith(root.toPath())) { "只能发送 /workspace 内的文件" }
    require(file.isFile && file.canRead()) { "文件不存在或不可读：$file" }
    return file
}

internal fun resolveDownloadDirectory(workingDirectory: File, path: String): File {
    val root = workingDirectory.canonicalFile
    val directory = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalFile
    require(directory.toPath().startsWith(root.toPath())) { "只能下载到 /workspace 内" }
    require(directory.isDirectory || directory.mkdirs()) { "无法创建下载目录：${directory.absolutePath}" }
    require(directory.canWrite()) { "下载目录不可写：${directory.absolutePath}" }
    return directory
}

internal fun selectGroupFile(files: List<GroupFile>, name: String): GroupFile {
    val requested = name.trim()
    val exact = files.filter { it.name == requested }
    val matches = exact.ifEmpty { files.filter { it.name.substringAfterLast('/') == requested } }
    require(matches.isNotEmpty()) { "群文件不存在：$requested" }
    require(matches.size == 1) { "存在多个同名群文件，请使用列表中的完整名称：$requested" }
    return matches.single()
}

private val groupFileDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())

internal fun formatGroupFiles(files: List<GroupFile>): String {
    if (files.isEmpty()) return "当前群没有群文件"
    return files.sortedByDescending(GroupFile::uploadTime).joinToString("\n") {
        val date = it.uploadTime.takeIf { time -> time > 0 }?.let { time ->
            groupFileDateFormat.format(Instant.ofEpochSecond(time))
        } ?: "未知"
        "${it.name}：$date"
    }
}

/**
 * 判断消息是否引用了本功能(Codex)发送过的消息：被引用消息的 ID 命中已发送记录即视为引用。
 */
internal fun isQuoteToCodexMessage(quotes: List<QuoteComponent>, sentIds: Collection<Int>?): Boolean =
    quotes.any { quote -> sentIds?.any { it in quote.sourceIds } == true }

/**
 * 把引用回复内容和用户输入组装成发给 Codex 的 prompt。
 * 引用内容用"引用消息："标记，与用户自己的输入区分开。
 */
internal fun buildPrompt(quotes: List<String>, text: String): String {
    val parts = buildList {
        quotes.filter(String::isNotBlank).forEach { add("引用消息：$it") }
        if (text.isNotBlank()) add(text)
    }
    return parts.joinToString("\n\n")
}

internal fun codexImageUrls(components: List<MessageComponent>): List<String> =
    components.flatMap { component ->
        when (component) {
            is ImageComponent -> listOf(component.context)
            is QuoteComponent -> component.components.filterIsInstance<ImageComponent>().map { it.context }
            else -> emptyList()
        }
    }.filter(String::isNotBlank)

internal fun codexText(components: List<MessageComponent>, removeFirstAt: Boolean = false): String {
    val firstAt = if (removeFirstAt) components.indexOfFirst { it is AtComponent } else -1
    return components.filterIndexed { index, component ->
        index != firstAt && component !is ImageComponent && component !is QuoteComponent
    }.joinToString("") { it.asString() }.trim()
}

internal fun codexInput(text: String, images: List<String>) = buildJsonArray {
    if (text.isNotBlank()) add(buildJsonObject {
        put("type", "text")
        put("text", text)
    })
    images.forEach { url ->
        add(buildJsonObject {
            put("type", "localImage")
            put("path", url)
        })
    }
}

internal fun appServerAgentMessage(message: JsonObject): String? {
    if (message.string("method") != "item/completed") return null
    val item = message["params"]?.jsonObject?.get("item")?.jsonObject ?: return null
    return item.takeIf { it.string("type") == "agentMessage" }?.string("text")
}

internal data class CliResult(val output: String, val timedOut: Boolean, val exitCode: Int)

internal fun runCli(command: String, workingDirectory: File, timeoutSeconds: Long = 10): CliResult {
    val process = ProcessBuilder("bash", "-c", command)
        .directory(workingDirectory)
        .redirectErrorStream(true)
        .start()
    val output = StringBuilder()
    val reader = Thread {
        runCatching {
            process.inputStream.bufferedReader(UTF_8).use { input ->
                val buffer = CharArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    synchronized(output) { output.append(buffer, 0, count) }
                }
            }
        }
    }.apply {
        name = "cli-command-output"
        isDaemon = true
        start()
    }

    val timedOut = !process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (timedOut) {
        val descendants = process.descendants().use { it.iterator().asSequence().toList() }
        process.destroyForcibly()
        descendants.forEach { it.destroyForcibly() }
        process.waitFor()
    }
    reader.join(1000)
    return CliResult(synchronized(output) { output.toString() }, timedOut, process.exitValue())
}

private val markdownTableSeparator = Regex("^:?-+:?$")
private val matchingBrackets = mapOf('<' to '>', '[' to ']', '{' to '}', '(' to ')')

private fun unwrapLinkTarget(target: String): String {
    var result = target.trim()
    while (result.length >= 2 && matchingBrackets[result.first()] == result.last()) {
        result = result.substring(1, result.lastIndex).trim()
    }
    return result
}

private fun stripMarkdownLinks(text: String, targets: MutableList<String>): String {
    val result = StringBuilder(text.length)
    var cursor = 0
    while (cursor < text.length) {
        val labelStart = when {
            text[cursor] == '[' -> cursor
            text[cursor] == '!' && text.getOrNull(cursor + 1) == '[' -> cursor + 1
            else -> {
                result.append(text[cursor++])
                continue
            }
        }
        val labelEnd = text.indexOf(']', labelStart + 1)
        if (labelEnd < 0 || text.getOrNull(labelEnd + 1) != '(') {
            result.append(text[cursor++])
            continue
        }

        var targetEnd = labelEnd + 2
        var depth = 1
        var escaped = false
        while (targetEnd < text.length && depth > 0) {
            val character = text[targetEnd]
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '(' -> depth++
                character == ')' -> depth--
            }
            if (depth > 0) targetEnd++
        }
        if (depth != 0) {
            result.append(text[cursor++])
            continue
        }

        targets += unwrapLinkTarget(text.substring(labelEnd + 2, targetEnd))
        result.append(text, labelStart + 1, labelEnd)
            .append('：')
            .append("\u0000${targets.lastIndex}\u0000")
        cursor = targetEnd + 1
    }
    return result.toString()
}

private fun splitMarkdownTableRow(line: String): List<String> {
    var text = line.trim()
    if (text.startsWith("|")) text = text.drop(1)
    if (text.endsWith("|") && !text.endsWith("\\|")) text = text.dropLast(1)

    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var escaped = false
    for (character in text) {
        when {
            escaped -> {
                if (character == '|') cell.append('|') else cell.append('\\').append(character)
                escaped = false
            }

            character == '\\' -> escaped = true
            character == '|' -> {
                cells += cell.toString().trim()
                cell.clear()
            }

            else -> cell.append(character)
        }
    }
    if (escaped) cell.append('\\')
    cells += cell.toString().trim()
    return cells
}

private fun isMarkdownTable(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    val headers = splitMarkdownTableRow(lines[index])
    val separators = splitMarkdownTableRow(lines[index + 1])
    return headers.size >= 2 && headers.size == separators.size && separators.all {
        markdownTableSeparator.matches(it.replace(" ", ""))
    }
}

private fun stripMarkdownTables(text: String): String {
    val lines = text.lines()
    val result = mutableListOf<String>()
    var index = 0

    while (index < lines.size) {
        if (!isMarkdownTable(lines, index)) {
            result += lines[index]
            index++
            continue
        }

        val headers = splitMarkdownTableRow(lines[index]).mapIndexed { column, value ->
            value.trim().ifEmpty { "第${column + 1}列" }
        }
        index += 2
        var rowCount = 0

        while (index < lines.size && lines[index].isNotBlank() && '|' in lines[index]) {
            val row = splitMarkdownTableRow(lines[index])
            if (row.size != headers.size) break
            result += "• " + headers.indices.joinToString("；") { column ->
                "${headers[column]}：${row[column]}"
            }
            rowCount++
            index++
        }

        if (rowCount == 0) result += "（表格无数据）"
    }

    return result.joinToString("\n")
}

internal fun stripMarkdown(text: String): String {
    val targets = mutableListOf<String>()
    var result = stripMarkdownLinks(stripMarkdownTables(text), targets)
        .replace(Regex("(?m)^\\s*```[^\\r\\n]*$"), "")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        .replace(Regex("(?m)^\\s{0,3}>\\s?"), "")
        .replace(Regex("(?m)^\\s*[-+*]\\s+"), "")
        .replace(Regex("`+([^`]+)`+"), "$1")
        .replace("**", "")
        .replace("__", "")
        .replace("~~", "")
        .replace(Regex("(?<!\\w)[*_]|[*_](?!\\w)"), "")
        .replace(Regex("(?m)^\\s*([-*_])(?:\\s*\\1){2,}\\s*$"), "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
    targets.forEachIndexed { index, target -> result = result.replace("\u0000$index\u0000", target) }
    return result
}

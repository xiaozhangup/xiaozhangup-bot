package me.xiaozhangup.bot.func

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.xiaozhangup.bot.client.CodexAppServer
import me.xiaozhangup.bot.client.CodexRpcException
import me.xiaozhangup.bot.client.string
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.Reaction
import me.xiaozhangup.bot.port.Group
import me.xiaozhangup.bot.port.GroupFile
import me.xiaozhangup.bot.port.Source
import me.xiaozhangup.bot.port.msg.obj.ImageComponent
import me.xiaozhangup.bot.port.msg.obj.QuoteComponent
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.properties
import me.xiaozhangup.bot.util.submit
import java.io.File
import java.net.URL
import java.net.URLConnection
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
    private val sessions = ConcurrentHashMap<String, Session>()
    private val threadGroups = ConcurrentHashMap<String, String>()
    private val groupSources = ConcurrentHashMap<String, Source>()
    private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<CodexInput>>()
    private val workers = ConcurrentHashMap.newKeySet<String>()
    private val cliBusyGroups = ConcurrentHashMap.newKeySet<String>()
    private val temporaryImages = ConcurrentHashMap<String, ConcurrentLinkedQueue<File>>()
    // 记录本功能发到各群的消息 ID，用于精确识别"引用回复了 Codex 消息"
    private val sentCodexMessageIds = ConcurrentHashMap<String, MutableSet<Int>>()

    @Volatile
    private var appServer: CodexAppServer? = null

    override fun onGroupMessage(message: Message) {
        if (message.source.id !in enabledGroups) return

        val images = message.component.filterIsInstance<ImageComponent>().map { it.context }.filter(String::isNotBlank)
        val quotes = message.component.filterIsInstance<QuoteComponent>().filter { it.context.isNotBlank() }
        val raw = message.component
            .filterNot { it is ImageComponent || it is QuoteComponent }
            .joinToString("") { it.asString() }.trim()
        when {
            raw == "/codex" || raw == "/x" -> if (images.isEmpty() && quotes.isEmpty()) {
                message.addReply("用法：/codex <任务> 或 /x <任务>；新会话使用 /codex new 或 /x new")
            } else {
                handleCodex(message, "", images, quotes)
            }
            raw.startsWith("/codex ") || raw.startsWith("/x ") -> handleCodex(message, raw.substringAfter(' ').trim(), images, quotes)
            raw == "/cli" -> message.addReply("用法：/cli <bash 命令>")
            raw.startsWith("/cli ") -> handleCli(message, raw.removePrefix("/cli").trim())
            // 引用了本功能(Codex)发出的消息：即使没有 /x 前缀，也当作 /x 处理
            else -> if (isQuoteToCodexMessage(quotes, sentCodexMessageIds[message.source.id]) && !raw.startsWith("/")) {
                handleCodex(message, raw, images, quotes)
            }
        }
    }

    private fun handleCodex(message: Message, content: String, images: List<String>, quotes: List<QuoteComponent>) {
        if (content.startsWith("new ")) {
            message.addReply("new 后不跟任务，请在下一条 /codex 或 /x 消息中发送任务")
            return
        }
        enqueue(
            message.source.id,
            if (content == "new") Reset(message.source)
            else Prompt(buildPrompt(quotes.map { it.context }, content), images, message)
        )
    }

    private fun handleCli(message: Message, command: String) {
        if (!cliBusyGroups.add(message.source.id)) {
            message.addReply("上一条 /cli 命令仍在执行")
            return
        }
        submit {
            try {
                val result = runCli(command, workingDirectory())
                val output = result.output.trimEnd()
                val reply = when {
                    result.timedOut -> "命令执行超时${output.takeIf(String::isNotEmpty)?.let { "\n$it" } ?: "，暂无输出"}"
                    result.exitCode != 0 -> "命令退出码 ${result.exitCode}${output.takeIf(String::isNotEmpty)?.let { "\n$it" } ?: ""}"
                    output.isEmpty() -> "命令执行完成，无输出"
                    else -> output
                }
                reply.chunked(4000).forEach { sendToGroup(message.source, message.source.id, it) }
            } catch (e: Exception) {
                sendToGroup(message.source, message.source.id, "命令启动失败：${e.message ?: "未知错误"}")
            } finally {
                cliBusyGroups.remove(message.source.id)
            }
        }
    }

    private fun enqueue(groupId: String, input: CodexInput) {
        queues.computeIfAbsent(groupId) { ConcurrentLinkedQueue() }.add(input)
        if (workers.add(groupId)) submit { drain(groupId) }
    }

    private fun drain(groupId: String) {
        val queue = queues[groupId] ?: return
        while (true) {
            while (true) {
                val input = queue.poll() ?: break
                try {
                    when (input) {
                        is Prompt -> deliver(groupId, input)
                        is Reset -> reset(groupId, input.source)
                    }
                } catch (e: Exception) {
                    input.source.sendMessage("Codex 执行失败：${e.message ?: "未知错误"}")
                }
            }
            workers.remove(groupId)
            if (queue.isEmpty() || !workers.add(groupId)) return
        }
    }

    private fun reset(groupId: String, source: Source) {
        sessions.remove(groupId)?.takeIf { it.activeTurnId == null }?.let {
            threadGroups.remove(it.threadId)
        }
        source.sendMessage("下一条 /codex 消息将进入新会话")
    }

    private fun deliver(groupId: String, prompt: Prompt) {
        groupSources[groupId] = prompt.source
        val session = sessions[groupId] ?: createSession(groupId).also { sessions[groupId] = it }
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

    private fun createSession(groupId: String): Session {
        val agentFile = config.getProperty("agent.path")?.trim()?.takeIf(String::isNotEmpty)?.let(::File)
        agentFile?.let { require(it.isFile) { "AGENTS.md 不存在：${it.absolutePath}" } }
        val result = server().request("thread/start", buildJsonObject {
            put("model", MODEL)
            put("cwd", workingDirectory().absolutePath)
            put("approvalPolicy", "never")
            put("sandbox", "danger-full-access")
            put("serviceName", "xiaozhangup-bot")
            put("dynamicTools", buildJsonArray {
                add(qqFileTool())
                add(qqGroupFileListTool())
                add(qqGroupFileDownloadTool())
            })
        })
        verifyInstructionSource(result, agentFile)
        val threadId = result["thread"]?.jsonObject?.string("id")
            ?: throw IllegalStateException("Codex App Server 未返回 thread id")
        threadGroups[threadId] = groupId
        return Session(threadId)
    }

    private fun startTurn(session: Session, prompt: Prompt) {
        val images = downloadImages(prompt.images)
        keepImages(session.threadId, images)
        try {
            val result = server().request("turn/start", buildJsonObject {
                put("threadId", session.threadId)
                put("input", codexInput(prompt.text, images.map(File::getAbsolutePath)))
                put("cwd", workingDirectory().absolutePath)
                put("model", MODEL)
                put("effort", EFFORT)
                put("approvalPolicy", "never")
                put("sandboxPolicy", buildJsonObject { put("type", "dangerFullAccess") })
            })
            session.activeTurnId = result["turn"]?.jsonObject?.string("id")
                ?: throw IllegalStateException("Codex App Server 未返回 turn id")
            prompt.message.addReaction(Reaction.SPARK)
        } catch (e: Exception) {
            discardImages(session.threadId, images)
            if (e is CodexRpcException && e.isNonSteerable()) session.deferred.add(prompt) else throw e
        }
    }

    private fun steer(session: Session, turnId: String, prompt: Prompt) {
        val images = downloadImages(prompt.images)
        keepImages(session.threadId, images)
        try {
            server().request("turn/steer", buildJsonObject {
                put("threadId", session.threadId)
                put("expectedTurnId", turnId)
                put("input", codexInput(prompt.text, images.map(File::getAbsolutePath)))
            })
            prompt.message.addReaction(Reaction.SPARK)
        } catch (e: Exception) {
            discardImages(session.threadId, images)
            throw e
        }
    }

    private fun handleNotification(message: JsonObject) {
        if (message.string("method") == "item/tool/call") {
            handleToolCall(message)
            return
        }
        val params = message["params"]?.jsonObject ?: return
        val threadId = params.string("threadId") ?: return
        val groupId = threadGroups[threadId] ?: return
        val session = sessions[groupId]
        when (message.string("method")) {
            "turn/started" -> if (session?.threadId == threadId) {
                session.activeTurnId = params["turn"]?.jsonObject?.string("id")
            }

            "item/completed" -> appServerAgentMessage(message)?.let { text ->
                val content = stripMarkdown(text).trim()
                if (content.isNotBlank()) groupSources[groupId]?.let { sendToGroup(it, groupId, content) }
            }

            "error" -> params["error"]?.jsonObject?.string("message")?.let { error ->
                groupSources[groupId]?.let { sendToGroup(it, groupId, "Codex 执行失败：${stripMarkdown(error)}") }
            }

            "turn/completed" -> {
                deleteImages(threadId)
                val turnId = params["turn"]?.jsonObject?.string("id")
                if (session?.threadId != threadId) {
                    threadGroups.remove(threadId)
                    return
                }
                if (session.activeTurnId == turnId) session.activeTurnId = null
                while (true) enqueue(groupId, session.deferred.poll() ?: break)
            }
        }
    }

    /**
     * 把消息发到群里，并记录发送成功后分配的消息 ID，用于后续识别"引用回复了 Codex 消息"。
     */
    private fun sendToGroup(source: Source, groupId: String, text: String) {
        val ids = source.sendMessageWithIds(text)
        if (ids.isNotEmpty()) {
            val set = sentCodexMessageIds.computeIfAbsent(groupId) { ConcurrentHashMap.newKeySet() }
            set.addAll(ids)
            if (set.size > MAX_RECORDED_MESSAGE_IDS) set.clear()
        }
    }

    private fun handleToolCall(message: JsonObject) {
        val requestId = message["id"] ?: return
        val params = message["params"]?.jsonObject ?: return
        val responder = appServer ?: return
        submit {
            val result = runCatching {
                val groupId = params.string("threadId")?.let(threadGroups::get)
                    ?: throw IllegalStateException("找不到当前 QQ 群")
                val group = groupSources[groupId] as? Group
                    ?: throw IllegalStateException("当前会话不支持发送群文件")
                val arguments = params["arguments"]?.jsonObject
                when (params.string("tool")) {
                    SEND_QQ_FILE -> {
                        val path = arguments?.string("path") ?: throw IllegalArgumentException("缺少文件路径")
                        val file = shareableFile(path)
                        group.sendFile(file)
                        "已发送文件：${file.name}"
                    }

                    LIST_QQ_GROUP_FILES -> formatGroupFiles(group.listFiles())
                    DOWNLOAD_QQ_GROUP_FILE -> {
                        val fileName = arguments?.string("file_name") ?: throw IllegalArgumentException("缺少群文件名")
                        val directory = arguments.string("directory") ?: throw IllegalArgumentException("缺少下载目录")
                        val file = downloadGroupFile(group, fileName, directory)
                        "已下载群文件：$fileName\n保存到：${file.absolutePath}"
                    }

                    else -> throw IllegalArgumentException("不支持的动态工具：${params.string("tool")}")
                }
            }
            runCatching {
                responder.respond(
                    requestId,
                    dynamicToolResult(result.isSuccess, result.getOrElse { "群文件工具失败：${it.message ?: "未知错误"}" })
                )
            }
        }
    }

    private suspend fun downloadGroupFile(group: Group, fileName: String, directoryPath: String): File {
        val remoteFile = selectGroupFile(group.listFiles(), fileName)
        val directory = resolveDownloadDirectory(workingDirectory(), directoryPath)
        val target = File(directory, remoteFile.name.substringAfterLast('/')).canonicalFile
        require(target.parentFile == directory && !target.exists()) { "目标文件已存在或文件名无效：${target.absolutePath}" }

        val connection = URL(group.getFileUrl(remoteFile.id)).openConnection().apply {
            connectTimeout = GROUP_FILE_TIMEOUT_MS
            readTimeout = GROUP_FILE_TIMEOUT_MS
        }
        val temporary = File.createTempFile(".${target.name}.", ".part", directory)
        try {
            connection.getInputStream().use { input -> temporary.outputStream().use(input::copyTo) }
            Files.move(temporary.toPath(), target.toPath())
            return target
        } finally {
            temporary.delete()
        }
    }

    private fun shareableFile(path: String): File {
        return resolveShareableFile(workingDirectory(), path)
    }

    @Synchronized
    private fun server(): CodexAppServer {
        return appServer ?: CodexAppServer(workingDirectory(), ::handleNotification) {
            deleteAllImages()
            sessions.clear()
            threadGroups.clear()
            groupSources.forEach { (groupId, source) ->
                sendToGroup(source, groupId, "Codex App Server 已停止，下一条任务将创建新会话")
            }
        }.also { appServer = it }
    }

    private fun workingDirectory(): File {
        val directory = config.getProperty("working.directory")?.trim()?.takeIf(String::isNotEmpty)?.let(::File)
            ?: throw IllegalStateException("未配置 codex.properties 中的 working.directory")
        require(directory.isDirectory) { "启动目录不存在：${directory.absolutePath}" }
        return directory
    }

    override fun close() {
        appServer?.close()
        deleteAllImages()
    }

    private fun downloadImages(urls: List<String>): List<File> {
        val files = mutableListOf<File>()
        try {
            urls.forEach { files += downloadImage(it) }
            return files
        } catch (e: Exception) {
            files.forEach { it.delete() }
            throw e
        }
    }

    private fun downloadImage(url: String): File {
        val connection = URL(url).openConnection().apply {
            connectTimeout = IMAGE_TIMEOUT_MS
            readTimeout = IMAGE_TIMEOUT_MS
        }
        val bytes = connection.getInputStream().use { it.readNBytes(MAX_IMAGE_BYTES + 1) }
        require(bytes.size <= MAX_IMAGE_BYTES) { "QQ 图片超过 20 MiB" }
        val contentType = connection.contentType?.substringBefore(';')?.lowercase()?.takeIf { it.startsWith("image/") }
            ?: URLConnection.guessContentTypeFromStream(bytes.inputStream())
        val suffix = when (contentType) {
            "image/jpeg", "image/jpg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            else -> throw IllegalArgumentException("无法识别 QQ 图片格式")
        }
        val file = File.createTempFile("codex_qq_", suffix)
        try {
            file.writeBytes(bytes)
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    private fun keepImages(threadId: String, images: List<File>) {
        if (images.isNotEmpty()) temporaryImages.computeIfAbsent(threadId) { ConcurrentLinkedQueue() }.addAll(images)
    }

    private fun discardImages(threadId: String, images: List<File>) {
        val tracked = temporaryImages[threadId]
        images.forEach {
            tracked?.remove(it)
            it.delete()
        }
        if (tracked?.isEmpty() == true) temporaryImages.remove(threadId, tracked)
    }

    private fun deleteImages(threadId: String) {
        temporaryImages.remove(threadId)?.forEach { it.delete() }
    }

    private fun deleteAllImages() {
        temporaryImages.keys.toList().forEach(::deleteImages)
    }

    private data class Session(
        val threadId: String,
        @Volatile var activeTurnId: String? = null,
        val deferred: ConcurrentLinkedQueue<Prompt> = ConcurrentLinkedQueue()
    )

    private sealed interface CodexInput {
        val source: Source
    }

    private data class Prompt(val text: String, val images: List<String>, val message: Message) : CodexInput {
        override val source = message.source
    }

    private data class Reset(override val source: Source) : CodexInput

    private companion object {
        const val MODEL = "gpt-5.6-luna"
        const val EFFORT = "max"
        const val IMAGE_TIMEOUT_MS = 10_000
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val GROUP_FILE_TIMEOUT_MS = 30_000
        const val MAX_RECORDED_MESSAGE_IDS = 10_000
        const val SEND_QQ_FILE = "send_qq_file"
        const val LIST_QQ_GROUP_FILES = "list_qq_group_files"
        const val DOWNLOAD_QQ_GROUP_FILE = "download_qq_group_file"
    }
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
    put("description", "将 working.directory 内已存在的文件发送到当前 QQ 群。仅在用户明确要求接收文件时调用。")
    put("inputSchema", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "相对 working.directory 或位于其中的绝对文件路径")
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
    put("description", "把当前 QQ 群的指定群文件下载到 working.directory 内的目录。存在同名文件时先调用 list_qq_group_files，并传入列表中的完整名称。")
    put("inputSchema", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("file_name", buildJsonObject {
                put("type", "string")
                put("description", "群文件名，文件夹内文件使用列表返回的 文件夹/文件名")
            })
            put("directory", buildJsonObject {
                put("type", "string")
                put("description", "相对 working.directory 或位于其中的绝对目录；目录不存在时自动创建")
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
    require(file.toPath().startsWith(root.toPath())) { "只能发送 working.directory 内的文件" }
    require(file.isFile && file.canRead()) { "文件不存在或不可读：${file.absolutePath}" }
    return file
}

internal fun resolveDownloadDirectory(workingDirectory: File, path: String): File {
    val root = workingDirectory.canonicalFile
    val directory = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalFile
    require(directory.toPath().startsWith(root.toPath())) { "只能下载到 working.directory 内" }
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
        quotes.forEach { add("引用消息：$it") }
        if (text.isNotBlank()) add(text)
    }
    return parts.joinToString("\n\n")
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

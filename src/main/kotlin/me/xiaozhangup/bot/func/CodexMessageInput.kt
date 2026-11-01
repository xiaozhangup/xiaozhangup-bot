package me.xiaozhangup.bot.func

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.xiaozhangup.bot.ove.OverGroup
import me.xiaozhangup.bot.ove.OverUser
import me.xiaozhangup.bot.port.Message
import me.xiaozhangup.bot.port.msg.MessageComponent
import me.xiaozhangup.bot.port.msg.obj.*
import top.mrxiaom.overflow.action.ActionContext
import top.mrxiaom.overflow.contact.RemoteBot
import me.xiaozhangup.bot.client.GroupContainer

/** Read platform segments before the generic message adapter loses cards and media metadata. */
internal class CodexMessageInput(private val message: Message, container: GroupContainer) {
    private val group = message.source as? OverGroup
    private val botContact = when (val source = message.source) {
        is OverGroup -> source.oveGroup.bot
        is OverUser -> source.oveUser.bot
        else -> error("不支持的 QQ 聊天类型")
    }
    private val bot = botContact as RemoteBot
    private val media = CodexMessageMedia(container,
        "${if (group != null) "group" else "private"}-${message.source.id}", message.id)
    private val input = mutableListOf<JsonElement>()
    private val text = StringBuilder()
    private val activeReferences = mutableSetOf<String>()
    private var segmentCount = 0
    private var imageCount = 0

    suspend fun prepare(quoteTriggered: Boolean): JsonArray {
        val original = try {
            readMessage(message.id.toString())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            text.append("[当前消息回查失败，使用事件内容；部分卡片和媒体信息可能不完整]\n")
            obj("message" to eventSegments(message.component))
        }
        text.append("[当前聊天：${if (group != null) "群聊" else "私聊"} ${message.source.name}，ID=${message.source.id}]\n")
        text.append("[当前发言者：${message.getSender()?.name.orEmpty()}，QQ=${message.getSender()?.id.orEmpty()}]\n")
        val parts = segments(original["message"])
        var removedCommand = quoteTriggered
        var removedAt = !quoteTriggered
        val cleaned = parts.mapNotNull { part ->
            val type = part.value("type")
            val data = part.getValue("data").jsonObject
            when {
                !removedCommand && type == "text" && COMMAND.containsMatchIn(data.value("text").orEmpty()) -> {
                    removedCommand = true
                    obj("type" to "text", "data" to obj("text" to data.value("text")!!.replaceFirst(COMMAND, "")))
                }
                !removedAt && type == "at" && data.value("qq") == botContact.id.toString() -> {
                    removedAt = true
                    null
                }
                else -> part
            }
        }
        activeReferences.add("message:${message.id}")
        render(cleaned, 0, message.id.toString())
        flushText()
        return JsonArray(input)
    }

    private suspend fun action(name: String, params: JsonObject): JsonObject {
        val response = try {
            withTimeout(30_000) {
                bot.executeAction(ActionContext.build(name) { throwExceptions(true) }, params.toString())
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("$name 查询超时", e)
        }
        val envelope = Json.parseToJsonElement(response).jsonObject
        check(envelope.value("status") == "ok" && envelope["retcode"]?.jsonPrimitive?.intOrNull == 0) {
            "$name：${envelope.value("message") ?: envelope.value("wording") ?: "平台未确认成功"}"
        }
        return envelope.getValue("data").jsonObject
    }

    private suspend fun readMessage(id: String): JsonObject {
        val result = action("get_msg", obj("message_id" to id))
        if (group != null) {
            require(result.value("group_id") == group.id) { "引用消息不属于当前群" }
        } else {
            val senderId = result["sender"]?.jsonObject?.value("user_id") ?: result.value("user_id")
            require(result.value("group_id") in setOf(null, "0") &&
                (senderId == message.source.id ||
                    (senderId == botContact.id.toString() && result.value("target_id") == message.source.id))) {
                "引用消息不属于当前私聊"
            }
        }
        return result
    }

    private suspend fun render(parts: List<JsonObject>, depth: Int, messageId: String?) {
        if (depth > 8) {
            text.append("[嵌套超过 8 层，后续内容未读取]\n")
            return
        }
        for (part in parts) {
            if (++segmentCount > 500) {
                text.append("[消息段超过 500 个，后续内容未读取]\n")
                return
            }
            val type = part.value("type") ?: "unknown"
            val data = part["data"]?.jsonObject ?: obj()
            try {
                when (type) {
                    "text" -> text.append(data.value("text"))
                    "at" -> {
                        val id = data.value("qq").orEmpty()
                        text.append(if (id == "all") "@全体成员" else "@${group?.getMemberName(id) ?: id}(QQ=$id)")
                    }
                    "reply" -> reference("message:${data.value("id")}") {
                        val original = readMessage(requireNotNull(data.value("id")))
                        text.append("\n[引用消息 ID=${data.value("id")} ${sender(original)}]\n")
                        render(segments(original["message"]), depth + 1, data.value("id"))
                        text.append("\n[/引用消息]\n")
                    }
                    "forward" -> {
                        val embedded = data["content"] as? JsonArray
                        if (embedded != null && embedded.isNotEmpty()) renderNodes(embedded, depth + 1)
                        else reference("forward:${data.value("id")}") {
                            val result = action("get_forward_msg", obj("message_id" to requireNotNull(data.value("id"))))
                            renderNodes(result.getValue("messages").jsonArray, depth + 1)
                        }
                    }
                    "node" -> renderNode(data, depth + 1)
                    "image" -> addImage(media.save(resolveMedia(type, data), "image", 20 * MIB))
                    "record" -> {
                        // The configured model is text/image only. QQ provides speech transcription.
                        val transcript = try {
                            requireNotNull(messageId) { "转发节点没有可回查的消息 ID" }
                            action("fetch_ptt_text", obj("message_id" to messageId)).value("text")
                                ?: error("平台未返回语音文字")
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            "[语音转文字失败：${e.message}]"
                        }
                        text.append("\n[语音转文字] $transcript\n")
                        val file = media.save(resolveMedia(type, data), "voice.wav", 100 * MIB)
                        text.append("[原始语音文件：${file}；当前模型未直接收听音频]\n")
                    }
                    "video" -> {
                        val file = media.save(resolveMedia(type, data), "video.mp4", 100 * MIB)
                        text.append("\n[视频文件：${file}；以下为均匀抽样画面，未识别音轨]\n")
                        addFrames(file)
                    }
                    "file" -> {
                        val file = media.save(resolveMedia(type, data), data.value("name") ?: "attachment", 100 * MIB)
                        text.append("\n[附件：${data.value("name") ?: file.substringAfterLast('/')}，已下载到 ${file}；请按任务需要读取该文件]\n")
                    }
                    "json", "xml" -> {
                        val payload = data.value("data") ?: data.toString()
                        text.append("\n[$type 卡片原文]\n$payload\n[/$type 卡片原文]\n")
                        if (type == "json") {
                            val card = runCatching { Json.parseToJsonElement(payload) }.getOrNull()
                            cardImages(card).distinct().forEach { url ->
                                try { addImage(media.save(obj("url" to url), "card-image", 20 * MIB)) }
                                catch (e: Exception) { text.append("[卡片预览图读取失败：${e.message}]\n") }
                            }
                        }
                    }
                    "face" -> text.append("[QQ表情：${data.value("name") ?: data.value("text") ?: "ID=${data.value("id")}"}]")
                    // Share/music/location/contact/dice/rps/poke retain all platform fields.
                    else -> text.append("\n[$type 消息：$data]\n")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                text.append("\n[$type 内容读取失败：${e.message}]\n")
            }
        }
    }

    private suspend fun reference(key: String, block: suspend () -> Unit) {
        if (!activeReferences.add(key)) {
            text.append("[循环引用 $key，未重复展开]\n")
            return
        }
        try { block() } finally { activeReferences.remove(key) }
    }

    private suspend fun renderNodes(nodes: JsonArray, depth: Int) {
        text.append("\n[合并转发]\n")
        for (node in nodes) {
            if (segmentCount >= 500) {
                text.append("[消息段上限已到，剩余转发节点未读取]\n")
                break
            }
            val value = node.jsonObject
            renderNode(if (value.value("type") == "node") value.getValue("data").jsonObject else value, depth)
        }
        text.append("\n[/合并转发]\n")
    }

    private suspend fun renderNode(node: JsonObject, depth: Int) {
        text.append("\n[${sender(node)} 时间=${node.value("time").orEmpty()}]\n")
        val content = node["message"] ?: node["content"]
        if (content != null) render(segments(content), depth, node.value("message_id"))
        else reference("message:${node.value("id")}") {
            val original = readMessage(requireNotNull(node.value("id")))
            render(segments(original["message"]), depth, node.value("id"))
        }
    }

    private suspend fun resolveMedia(type: String, data: JsonObject): JsonObject {
        if (type == "record") return action("get_record", obj("file" to requireNotNull(data.value("file")), "out_format" to "wav"))
        if (!data.value("url").isNullOrBlank()) return data
        return when (type) {
            "image" -> action("get_image", obj("file" to requireNotNull(data.value("file"))))
            "video" -> action("get_file", obj("file" to requireNotNull(data.value("file"))))
            else -> if (group == null) action("get_private_file_url", obj(
                "user_id" to message.source.id,
                "file_id" to requireNotNull(data.value("file_id") ?: data.value("file"))))
            else action("get_group_file_url", obj("group_id" to group.id,
                "file_id" to requireNotNull(data.value("file_id") ?: data.value("file")),
                "busid" to (data.value("busid") ?: "0")))
        }
    }

    private fun addImage(file: String) {
        if (file.endsWith(".gif")) {
            text.append("\n[GIF 文件：${file}；以下为动画抽样帧]\n")
            addFrames(file)
        } else addStill(file)
    }

    private fun addFrames(file: String) {
        media.frames(file).forEach { (seconds, frame) ->
            text.append("[画面时间：${"%.2f".format(java.util.Locale.ROOT, seconds)} 秒]")
            addStill(frame)
        }
    }

    private fun addStill(file: String) {
        if (imageCount >= 32) {
            text.append("[图片输入已达 32 张，其余图片保存在 ${file}，可按需读取]\n")
            return
        }
        text.append("[图片${++imageCount}：${file}]\n")
        flushText()
        input += obj("type" to "localImage", "path" to file)
    }

    private fun flushText() {
        if (text.isNotBlank()) input += obj("type" to "text", "text" to text.toString())
        text.setLength(0)
    }

    private fun sender(node: JsonObject): String {
        val source = node["sender"] as? JsonObject ?: node
        return "发送者=${source.value("card")?.takeIf(String::isNotBlank) ?: source.value("nickname").orEmpty()}，QQ=${source.value("user_id") ?: source.value("uin").orEmpty()}"
    }

    private fun cardImages(value: JsonElement?): List<String> = when (value) {
        is JsonObject -> value.flatMap { (key, child) ->
            if (key.lowercase() in setOf("preview", "cover", "image", "pic", "thumb") && child is JsonPrimitive) {
                listOf(child.content).filter { it.startsWith("https://") || it.startsWith("http://") }
            } else cardImages(child)
        }
        is JsonArray -> value.flatMap(::cardImages)
        else -> emptyList()
    }

    private fun eventSegments(components: List<MessageComponent>): JsonArray = JsonArray(components.map { component ->
        when (component) {
            is ImageComponent -> obj("type" to "image", "data" to obj("url" to component.context))
            is AtComponent -> obj("type" to "at", "data" to obj("qq" to component.context))
            is QuoteComponent -> obj("type" to "reply", "data" to obj("id" to component.sourceIds.firstOrNull()?.toString()))
            is ContainerComponent -> obj("type" to "forward", "data" to obj("content" to JsonArray(component.elements.map {
                obj("nickname" to it.source.name, "user_id" to it.source.id, "content" to eventSegments(it.component))
            })))
            else -> obj("type" to "text", "data" to obj("text" to component.asString()))
        }
    })

    companion object {
        private const val MIB = 1024 * 1024
        private val COMMAND = Regex("^\\s*/(?:codex|x)(?:\\s+|$)")
    }
}

/** OneBot can return either an array or CQ-encoded text, depending on its connection configuration. */
private fun segments(value: JsonElement?): List<JsonObject> {
    if (value is JsonArray) return value.map { it.jsonObject }
    val raw = (value as? JsonPrimitive)?.content ?: error("平台未返回消息内容")
    val parts = mutableListOf<JsonObject>()
    var offset = 0
    Regex("\\[CQ:([a-zA-Z0-9_]+)((?:,[^\\]]*)?)\\]").findAll(raw).forEach { match ->
        if (match.range.first > offset) parts += obj("type" to "text", "data" to obj("text" to cqDecode(raw.substring(offset, match.range.first))))
        val data = match.groupValues[2].removePrefix(",").split(',').filter { '=' in it }.associate {
            it.substringBefore('=') to JsonPrimitive(cqDecode(it.substringAfter('=')))
        }
        parts += obj("type" to match.groupValues[1], "data" to JsonObject(data))
        offset = match.range.last + 1
    }
    if (offset < raw.length) parts += obj("type" to "text", "data" to obj("text" to cqDecode(raw.substring(offset))))
    return parts
}

private fun cqDecode(value: String) = value.replace("&#91;", "[").replace("&#93;", "]").replace("&#44;", ",").replace("&amp;", "&")
internal fun JsonObject.value(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) -> put(key, when (value) {
        null -> JsonNull
        is JsonElement -> value
        else -> JsonPrimitive(value.toString())
    }) }
}

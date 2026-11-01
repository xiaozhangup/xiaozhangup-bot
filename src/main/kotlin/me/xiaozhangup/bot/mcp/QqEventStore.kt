package me.xiaozhangup.bot.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import net.mamoe.mirai.Bot
import net.mamoe.mirai.event.EventChannel
import net.mamoe.mirai.event.Event
import net.mamoe.mirai.event.events.*
import top.mrxiaom.overflow.event.MessageReactionEvent
import java.time.Instant
import java.util.UUID

internal class QqEventStore(private val capacity: Int, private val botProvider: () -> Bot?, private val timeoutMillis: Long) {
    private val epoch = UUID.randomUUID().toString()
    private val startedAt = Instant.now().toString()
    private var sequence = 0L
    private val events = ArrayDeque<JsonObject>()
    private val origins = LinkedHashMap<String, QqTarget>()
    private val requests = LinkedHashMap<String, Request>()
    private val lock = Any()
    var onChange: (Set<String>) -> Unit = {}

    private class Request(var data: JsonObject, val handler: suspend (JsonObject) -> Unit) {
        val mutex = Mutex()
    }

    fun remember(messageId: String, target: QqTarget) = synchronized(lock) {
        origins[messageId] = target
        while (origins.size > capacity) origins.remove(origins.keys.first())
    }

    fun origin(messageId: String): QqTarget? = synchronized(lock) { origins[messageId] }

    fun append(type: String, target: QqTarget?, data: JsonObject) {
        synchronized(lock) {
            sequence++
            events.addLast(obj("event_id" to "$epoch:$sequence", "type" to type, "time" to Instant.now().toString(), "target" to target?.json(), "data" to data))
            while (events.size > capacity) events.removeFirst()
        }
        onChange(buildSet {
            add("qq://events/recent")
            if (target != null) {
                add("qq://conversations/${target.type}/${target.id}/recent")
                if (target.type == "group") add("qq://groups/${target.id}/info")
            }
            if (type == "bot_status") add("qq://bot/status")
        })
    }

    suspend fun poll(args: JsonObject): JsonObject {
        val cursor = args.optionalString("cursor")
        val after = if (cursor == null) null else {
            val parts = cursor.split(':')
            if (parts.size != 2 || parts[0] != epoch) throw QqException("EVENT_CURSOR_EXPIRED", "事件游标已失效，请重新读取；服务重启会清空事件窗口")
            parts[1].toLongOrNull() ?: invalid("事件游标格式无效")
        }
        val types = args["types"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
        val target = args["target"]?.let(QqTarget::parse)
        val deadline = System.nanoTime() + args.int("wait_seconds", 0) * 1_000_000_000L
        do {
            val result = synchronized(lock) {
                val oldest = events.firstOrNull()?.string("event_id")?.substringAfterLast(':')?.toLong() ?: sequence + 1
                if (after != null && (after < oldest - 1 || after > sequence)) throw QqException("EVENT_CURSOR_EXPIRED", "事件游标不在当前保留窗口内，请重新读取")
                val candidates = events.filter { after == null || it.string("event_id").substringAfterLast(':').toLong() > after }
                val matches = candidates.filter { event ->
                    (types == null || event.string("type") in types) && (target == null || event["target"] == target.json())
                }.take(args.int("limit", 50))
                val next = if (matches.size == args.int("limit", 50)) matches.last().string("event_id") else "$epoch:$sequence"
                obj("items" to matches, "next_cursor" to next, "recorded_since" to startedAt,
                    "oldest_cursor" to "$epoch:${oldest - 1}")
            }
            if (result["items"]!!.jsonArray.isNotEmpty() || System.nanoTime() >= deadline) return result
            delay(100)
        } while (true)
    }

    fun recent(): JsonObject = synchronized(lock) {
        obj("items" to events.toList().takeLast(100), "next_cursor" to "$epoch:$sequence", "recorded_since" to startedAt)
    }

    private fun request(type: String, target: QqTarget?, data: JsonObject, handler: suspend (JsonObject) -> Unit) {
        val id = UUID.randomUUID().toString()
        val record = JsonObject(data + obj("request_id" to id, "type" to type, "status" to "pending", "time" to Instant.now().toString()))
        synchronized(lock) {
            requests[id] = Request(record, handler)
            while (requests.size > capacity) requests.remove(requests.keys.first())
        }
        append("request", target, record)
    }

    fun listRequests(args: JsonObject): JsonObject = synchronized(lock) {
        val type = args.optionalString("type")
        val group = args.optionalString("group_id")
        val items = requests.values.map { it.data }.filter {
            (type == null || it.optionalString("type") == type) && (group == null || it.optionalString("group_id") == group)
        }
        JsonObject(page(items, args, "requests:$type:$group") + obj("recorded_since" to startedAt, "source" to "received_events"))
    }

    suspend fun handleRequest(args: JsonObject, type: String): JsonObject {
        val request = synchronized(lock) { requests[args.string("request_id")] }
            ?: throw QqException("REQUEST_NOT_FOUND", "申请未记录或已移出保留窗口")
        return request.mutex.withLock {
            if (request.data.string("type") != type) invalid("申请类型与工具不符")
            if (request.data.string("status") != "pending") throw QqException("REQUEST_ALREADY_HANDLED", "申请状态为 ${request.data.string("status")}")
            try {
                kotlinx.coroutines.withTimeout(timeoutMillis) { request.handler(args) }
                synchronized(lock) { request.data = JsonObject(request.data + obj("status" to if (args.bool("approve")) "approved" else "rejected")) }
            } catch (_: Exception) {
                synchronized(lock) { request.data = JsonObject(request.data + obj("status" to "unknown")) }
                throw QqException("OUTCOME_UNKNOWN", "申请处理未取得有效回执，请先核实结果")
            }
            append("request_updated", request.data.optionalString("group_id")?.let { QqTarget("group", it) }, request.data)
            request.data
        }
    }

    fun subscribe(channel: EventChannel<Event>) {
        fun isCurrent(bot: Bot) = botProvider()?.id == bot.id
        channel.subscribeAlways<GroupMessageEvent> {
            if (isCurrent(bot)) recordMessage(QqTarget("group", group.id.toString()), source.ids.firstOrNull(), sender.id, sender.nick, message.contentToString())
        }
        channel.subscribeAlways<FriendMessageEvent> {
            if (isCurrent(bot)) recordMessage(QqTarget("private", friend.id.toString()), source.ids.firstOrNull(), sender.id, sender.nick, message.contentToString())
        }
        channel.subscribeAlways<MessageReactionEvent> {
            if (isCurrent(bot)) append("message_reaction", QqTarget("group", group.id.toString()), obj("message_id" to messageId.toString(), "user_id" to (operator?.id ?: bot.id).toString(), "emoji_id" to reaction, "enabled" to operation))
        }
        channel.subscribeAlways<MessageRecallEvent.GroupRecall> {
            if (isCurrent(bot)) append("message_recall", QqTarget("group", group.id.toString()), obj("message_ids" to messageIds.map(Int::toString), "user_id" to authorId.toString()))
        }
        channel.subscribeAlways<MessageRecallEvent.FriendRecall> {
            if (isCurrent(bot)) append("message_recall", QqTarget("private", operatorId.toString()), obj("message_ids" to messageIds.map(Int::toString), "user_id" to authorId.toString()))
        }
        channel.subscribeAlways<MemberJoinEvent> {
            if (isCurrent(bot)) append("member_join", QqTarget("group", group.id.toString()), obj("user_id" to member.id.toString()))
        }
        channel.subscribeAlways<MemberLeaveEvent> {
            if (isCurrent(bot)) append("member_leave", QqTarget("group", group.id.toString()), obj("user_id" to member.id.toString()))
        }
        channel.subscribeAlways<BotOnlineEvent> { if (isCurrent(bot)) append("bot_status", null, obj("online" to true)) }
        channel.subscribeAlways<BotOfflineEvent> { if (isCurrent(bot)) append("bot_status", null, obj("online" to false)) }
        channel.subscribeAlways<NewFriendRequestEvent> {
            if (isCurrent(bot)) {
                val event = this
                request("friend", QqTarget("private", fromId.toString()), obj("user_id" to fromId.toString(), "nickname" to fromNick, "comment" to message)) {
                    if (it.bool("approve")) event.accept() else event.reject()
                }
            }
        }
        channel.subscribeAlways<MemberJoinRequestEvent> {
            if (isCurrent(bot)) {
                val event = this
                request("group", QqTarget("group", groupId.toString()), obj("group_id" to groupId.toString(), "user_id" to fromId.toString(), "nickname" to fromNick, "comment" to message, "sub_type" to "add")) {
                    if (it.bool("approve")) event.accept() else event.reject(message = it.optionalString("reason") ?: "")
                }
            }
        }
        channel.subscribeAlways<BotInvitedJoinGroupRequestEvent> {
            if (isCurrent(bot)) {
                val event = this
                request("group", QqTarget("group", groupId.toString()), obj("group_id" to groupId.toString(), "user_id" to invitorId.toString(), "nickname" to invitorNick, "sub_type" to "invite")) {
                    if (it.bool("approve")) event.accept() else event.ignore()
                }
            }
        }
    }

    private fun recordMessage(target: QqTarget, id: Int?, senderId: Long, nickname: String, text: String) {
        if (id != null) remember(id.toString(), target)
        append("message", target, obj("message_id" to id?.toString(), "sender" to obj("user_id" to senderId.toString(), "nickname" to nickname), "content_text" to text))
    }
}

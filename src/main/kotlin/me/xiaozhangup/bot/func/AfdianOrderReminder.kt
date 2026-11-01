package me.xiaozhangup.bot.func

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.xiaozhangup.bot.PluginMain
import me.xiaozhangup.bot.client.afdian.AfdianClient
import me.xiaozhangup.bot.client.afdian.AfdianOrder
import me.xiaozhangup.bot.port.msg.obj.StringComponent
import me.xiaozhangup.bot.util.dataFolder
import me.xiaozhangup.bot.util.getGroup
import me.xiaozhangup.bot.util.info
import me.xiaozhangup.bot.util.properties
import me.xiaozhangup.bot.util.warning
import net.mamoe.mirai.Bot
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AfdianOrderReminder {
    private var job: Job? = null

    fun start() {
        val config = properties("afdian_order")
        if (!config.getProperty("enabled", "false").toBoolean()) return
        val userId = config.getProperty("user.id", "").trim()
        val token = config.getProperty("api.token", "").trim()
        val groupId = config.getProperty("notification.group", "").trim()
        val interval = config.getProperty("poll.seconds", "60").toLongOrNull()
        if (userId.isBlank() || token.isBlank() || groupId.toLongOrNull()?.let { it > 0 } != true ||
            interval == null || interval < 10
        ) {
            warning("[Afdian] 配置无效，请检查 user.id、api.token、notification.group 和 poll.seconds（至少 10 秒）")
            return
        }
        val client = AfdianClient(userId, token)
        val file = File(dataFolder("afdian_order"), "$userId-$groupId.json")
        job = PluginMain.launch(Dispatchers.IO) {
            // 状态损坏时停止该模块，不能把已有记录当成空记录覆盖。
            val seen = if (file.exists()) {
                Json.decodeFromString(OrderState.serializer(), file.readText()).orderIds.toMutableSet()
            } else null
            var notified = seen
            info("[Afdian] 已启动，目标群 $groupId，每 $interval 秒检查订单")
            while (isActive) {
                try {
                    val orders = linkedMapOf<String, AfdianOrder>()
                    var page = 1
                    do {
                        val result = client.orders(page)
                        result.list.filter { it.status == 2 }.forEach { orders[it.out_trade_no] = it }
                        page++
                        if (page > result.total_page) break
                        delay(200)
                    } while (isActive)
                    if (!isActive) break
                    val known = notified
                    if (known == null) {
                        val baseline = orders.keys.toMutableSet()
                        save(file, baseline)
                        notified = baseline
                        info("[Afdian] 已登记 ${baseline.size} 条历史成交订单，不补发历史提醒")
                    } else {
                        val pending = orders.values.filter { it.out_trade_no !in known }.reversed()
                        if (pending.isNotEmpty() && Bot.instances.any { it.isOnline }) {
                            val target = checkNotNull(getGroup(groupId)) { "目标群 $groupId 不存在" }
                            for (order in pending) {
                                target.sendMessageConfirmed(StringComponent(order.notification()))
                                // 收到平台成功回执之后才记账；先持久化再更新内存。
                                val updated = known + order.out_trade_no
                                save(file, updated)
                                known.add(order.out_trade_no)
                                info("[Afdian] 新成交订单提醒已发送至群 $groupId")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warning("[Afdian] 本轮检查或发送失败，下轮继续：${e.message}")
                }
                delay(interval * 1000)
            }
        }
    }

    fun close() {
        job?.cancel()
    }

    private fun save(file: File, ids: Set<String>) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(Json.encodeToString(OrderState.serializer(), OrderState(ids.toList())))
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Serializable
    private data class OrderState(val orderIds: List<String>)
}

package me.xiaozhangup.bot.client.afdian

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

class AfdianClient(private val userId: String, private val token: String) {
    private val json = Json { ignoreUnknownKeys = true }

    fun orders(page: Int): AfdianOrderPage {
        val params = buildJsonObject { put("page", page) }.toString()
        val ts = Instant.now().epochSecond
        val sign = MessageDigest.getInstance("MD5")
            .digest("${token}params${params}ts${ts}user_id${userId}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val body = buildJsonObject {
            put("user_id", userId)
            put("params", params)
            put("ts", ts)
            put("sign", sign)
        }.toString()
        val connection = URL("https://afdian.com/api/open/query-order").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode == 200) { "爱发电 HTTP ${connection.responseCode}" }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                json.decodeFromString(AfdianResponse.serializer(), it.readText())
            }
            // 错误响应可能含签名调试信息，不把原始响应写入日志。
            check(response.ec == 200) { "爱发电 API 错误码 ${response.ec}" }
            return json.decodeFromJsonElement(AfdianOrderPage.serializer(),
                requireNotNull(response.data) { "爱发电订单响应缺少 data" })
        } finally {
            connection.disconnect()
        }
    }
}

@Serializable
private data class AfdianResponse(val ec: Int, val data: JsonElement? = null)

@Serializable
data class AfdianOrderPage(val list: List<AfdianOrder>, val total_page: Int)

@Serializable
data class AfdianOrder(
    val out_trade_no: String,
    val status: Int,
    val total_amount: String,
    val user_name: String = "",
    val plan_title: String = "",
    val sku_detail: List<AfdianSku> = emptyList()
) {
    fun notification(): String = buildString {
        appendLine("爱发电有新的成交订单！")
        if (user_name.isNotBlank()) appendLine("赞助者：$user_name")
        if (plan_title.isNotBlank()) appendLine("方案：$plan_title")
        sku_detail.forEach { appendLine("商品：${it.name} × ${it.count}") }
        appendLine("实付金额：¥$total_amount")
        append("订单号：$out_trade_no")
    }
}

@Serializable
data class AfdianSku(val name: String, val count: Int)

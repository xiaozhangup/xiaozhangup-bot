package me.xiaozhangup.bot.client.ow

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class OpenWeatherClient(
    private val apiKey: String,
    private val units: String = "metric", // 默认使用公制单位（摄氏度）
    private val lang: String = "zh_cn"   // 默认使用简体中文
) {

    // 配置 Json 解析器，忽略 API 返回的未知字段
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * 获取指定城市的天气
     *
     * @param city 城市名称 (例如 "Beijing")
     * @return WeatherResponse 对象
     */
    fun getWeather(city: String): WeatherResponse {
        // 构建 URL，默认使用公制单位(摄氏度)和简体中文
        val urlString = "https://api.openweathermap.org/data/2.5/weather?q=$city&appid=$apiKey&units=$units&lang=$lang"
        val url = URL(urlString)

        // 打开连接
        val connection = url.openConnection() as HttpURLConnection

        try {
            // 设置请求方法和超时时间
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000 // 10秒连接超时
            connection.readTimeout = 10000    // 10秒读取超时

            // 获取响应码
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                // 如果出错，尝试读取错误流以便调试
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IOException("HTTP Request Failed. Code: $responseCode, Error: $errorStream")
            }

            // 读取响应内容
            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }

            // 解析 JSON 并返回对象
            return jsonParser.decodeFromString<WeatherResponse>(responseBody)

        } finally {
            // 断开连接
            connection.disconnect()
        }
    }
}
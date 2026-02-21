package me.xiaozhangup.bot.client

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object WebClient {

    fun fetchUrl(url: String, method: String = "GET"): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10000 // 10秒连接超时
            connection.readTimeout = 10000    // 10秒读取超时

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP Request Failed. Code: $responseCode")
            }

            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

}
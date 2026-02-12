package me.xiaozhangup.bot.client.peapix

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class PeapixClient {

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun getBingImage(country: String = "cn", number: Int): List<PeapixImage> {
        return fetchImages(URL("https://peapix.com/bing/feed?country=$country&n=$number"))
    }

    fun getSpotlightImage(number: Int): List<PeapixImage> {
        return fetchImages(URL("https://peapix.com/spotlight/feed?&n=$number"))
    }

    private fun fetchImages(url: URL): List<PeapixImage> {
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IOException("HTTP Request Failed. Code: $responseCode, Error: $errorStream")
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            return jsonParser.decodeFromString<List<PeapixImage>>(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    @Serializable
    data class PeapixImage(
        val title: String,
        val copyright: String,
        val fullUrl: String,
        val thumbUrl: String,
        val imageUrl: String,
        val pageUrl: String
    )
}
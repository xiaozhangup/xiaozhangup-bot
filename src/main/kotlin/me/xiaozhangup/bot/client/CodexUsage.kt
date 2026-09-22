package me.xiaozhangup.bot.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.io.path.Path

object CodexUsage {
    private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Serializable
    private data class CodexAuth(
        val tokens: Tokens
    ) {
        @Serializable
        data class Tokens(
            @SerialName("access_token")
            val accessToken: String,

            @SerialName("account_id")
            val accountId: String
        )
    }

    @Serializable
    data class CodexUsage(
        @SerialName("plan_type")
        val planType: String? = null,

        @SerialName("rate_limit")
        val rateLimit: RateLimit? = null,

        val credits: Credits? = null
    ) {
        @Serializable
        data class RateLimit(
            @SerialName("primary_window")
            val primaryWindow: Window? = null,

            @SerialName("secondary_window")
            val secondaryWindow: Window? = null
        )

        @Serializable
        data class Window(
            @SerialName("used_percent")
            val usedPercent: Double? = null,

            @SerialName("reset_at")
            val resetAt: Long? = null,

            @SerialName("limit_window_seconds")
            val limitWindowSeconds: Long? = null
        ) {
            val remainingPercent: Double?
                get() = usedPercent?.let { (100.0 - it).coerceIn(0.0, 100.0) }
        }

        @Serializable
        data class Credits(
            @SerialName("has_credits")
            val hasCredits: Boolean? = null,

            val unlimited: Boolean? = null,

            val balance: String? = null
        )
    }

    fun fetchCodexUsage(): CodexUsage {
        val codexHome = System.getenv("CODEX_HOME")
            ?.let(::Path)
            ?: Path(System.getProperty("user.home"), ".codex")

        val authPath = codexHome.resolve("auth.json")

        require(Files.exists(authPath)) {
            "Codex auth 文件不存在: $authPath"
        }

        val auth = json.decodeFromString<CodexAuth>(
            Files.readString(authPath)
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create(USAGE_URL))
            .header("Authorization", "Bearer ${auth.tokens.accessToken}")
            .header("ChatGPT-Account-Id", auth.tokens.accountId)
            .header("Accept", "application/json")
            .header("User-Agent", "codex-cli")
            .header("OpenAI-Beta", "codex-1")
            .header("originator", "codex_cli_rs")
            .GET()
            .build()

        val response = HttpClient.newHttpClient().send(
            request,
            HttpResponse.BodyHandlers.ofString()
        )

        check(response.statusCode() in 200..299) {
            "Codex usage 请求失败: HTTP ${response.statusCode()}\n${response.body()}"
        }

        return json.decodeFromString(response.body())
    }
}
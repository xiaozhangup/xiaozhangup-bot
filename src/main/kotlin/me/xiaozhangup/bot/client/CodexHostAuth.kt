package me.xiaozhangup.bot.client

import kotlinx.serialization.json.*
import java.io.File
import java.util.Base64

/** Refresh credentials only on the host; group containers receive external access tokens over stdio. */
internal class CodexHostAuth(private val home: File, private val workingDirectory: File) {
    @Synchronized
    fun tokens(forceRefresh: Boolean = false): JsonObject {
        var tokens = readTokens()
        val payload = tokens.getValue("access_token").jsonPrimitive.content.split('.')[1]
        val expiry = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload))).jsonObject["exp"]?.jsonPrimitive?.longOrNull
        if (forceRefresh || expiry == null || expiry <= System.currentTimeMillis() / 1000 + 120) {
            CodexAppServer(workingDirectory, {}, {}, environment = mapOf("CODEX_HOME" to home.absolutePath)).use {
                it.request("account/read", buildJsonObject { put("refreshToken", true) })
            }
            tokens = readTokens()
        }
        return buildJsonObject {
            put("accessToken", tokens.getValue("access_token"))
            put("chatgptAccountId", tokens.getValue("account_id"))
        }
    }

    private fun readTokens() = Json.parseToJsonElement(File(home, "auth.json").readText()).jsonObject.getValue("tokens").jsonObject
}

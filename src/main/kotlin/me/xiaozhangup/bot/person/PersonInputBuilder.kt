package me.xiaozhangup.bot.person

import me.xiaozhangup.bot.client.ai.Message

object PersonInputBuilder {

    fun build(lines: List<ChatLine>, botId: String, isGroup: Boolean): List<Message> {
        val result = mutableListOf<Message>()
        val userBuffer = StringBuilder()

        fun flushUser() {
            if (userBuffer.isNotEmpty()) {
                result.add(Message("user", userBuffer.toString().trimEnd()))
                userBuffer.clear()
            }
        }

        lines.forEach { line ->
            if (line.senderId == botId) {
                flushUser()
                result.add(Message("assistant", line.text))
            } else {
                if (userBuffer.isNotEmpty()) userBuffer.append('\n')
                if (isGroup) {
                    userBuffer.append("[").append(line.senderName).append("]: ").append(line.text)
                } else {
                    userBuffer.append(line.text)
                }
            }
        }
        flushUser()

        return result
    }
}

package me.xiaozhangup.bot.person

import me.xiaozhangup.bot.util.dataFolder
import java.io.File

object PersonPromptLoader {

    private const val RESOURCE_PATH = "/person/my-chat-style-system-prompt.md"
    private const val FILE_NAME = "my-chat-style-system-prompt.md"

    private val cached: String by lazy {
        val folder = dataFolder("person")
        val file = File(folder, FILE_NAME)
        if (!file.exists()) {
            val stream = PersonPromptLoader::class.java.getResourceAsStream(RESOURCE_PATH)
                ?: throw IllegalStateException("Resource $RESOURCE_PATH not found")
            stream.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        file.readText(Charsets.UTF_8)
    }

    fun prompt(): String = cached
}

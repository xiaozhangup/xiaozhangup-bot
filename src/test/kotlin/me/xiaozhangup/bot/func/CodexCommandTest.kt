package me.xiaozhangup.bot.func

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.xiaozhangup.bot.client.codexAppServerCommand
import me.xiaozhangup.bot.ove.groupFileTimestamp
import me.xiaozhangup.bot.port.GroupFile
import me.xiaozhangup.bot.port.msg.obj.QuoteComponent
import me.xiaozhangup.bot.port.msg.obj.StringComponent
import me.xiaozhangup.bot.util.asMessage
import me.xiaozhangup.bot.util.quoteContent
import net.mamoe.mirai.message.data.MessageSourceBuilder
import net.mamoe.mirai.message.data.MessageSourceKind
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.QuoteReply
import net.mamoe.mirai.message.data.buildMessageChain
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexCommandTest {
    @Test
    fun usesFormalProjectInstructionsAndDisablesSystemMemories() {
        val command = codexAppServerCommand()
        assertTrue("--dangerously-bypass-hook-trust" in command)
        assertTrue("features.memories=false" in command)

        val agents = Files.createTempFile("AGENTS", ".md").toFile()
        val result = buildJsonObject {
            put("instructionSources", kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive(agents.absolutePath))
            })
        }
        verifyInstructionSource(result, agents)
        assertFailsWith<IllegalArgumentException> { verifyInstructionSource(buildJsonObject {}, agents) }
        agents.delete()
    }

    @Test
    fun declaresQqFileToolAndResponse() {
        val tool = qqFileTool()
        val response = dynamicToolResult(true, "已发送文件：report.txt")

        assertEquals("send_qq_file", tool["name"]?.jsonPrimitive?.content)
        assertEquals("path", tool["inputSchema"]?.jsonObject?.get("required")?.jsonArray?.single()?.jsonPrimitive?.content)
        assertTrue(response["success"]?.jsonPrimitive?.content?.toBoolean() == true)
        assertEquals("已发送文件：report.txt", response["contentItems"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun declaresQqGroupFileTools() {
        val list = qqGroupFileListTool()
        val download = qqGroupFileDownloadTool()

        assertEquals("list_qq_group_files", list["name"]?.jsonPrimitive?.content)
        assertEquals("download_qq_group_file", download["name"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("file_name", "directory"),
            download["inputSchema"]?.jsonObject?.get("required")?.jsonArray?.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun selectsGroupFileAndRestrictsDownloadDirectory() {
        val files = listOf(
            GroupFile("1", "report.txt", 1),
            GroupFile("2", "archive/report.txt", 2)
        )
        assertEquals("2", selectGroupFile(files, "archive/report.txt").id)
        assertFailsWith<IllegalArgumentException> { selectGroupFile(files, "missing.txt") }

        val root = Files.createTempDirectory("qq-download-test").toFile()
        val directory = resolveDownloadDirectory(root, "downloads")
        assertTrue(directory.isDirectory)
        assertFailsWith<IllegalArgumentException> { resolveDownloadDirectory(root, "../outside") }
        directory.delete()
        root.delete()
    }

    @Test
    fun fallsBackToGroupFileModifyTime() {
        val file = buildJsonObject {
            put("upload_time", 0)
            put("modify_time", 1_785_939_394)
        }

        assertEquals(1_785_939_394L, groupFileTimestamp(file))
    }

    @Test
    fun onlySharesFilesInsideWorkingDirectory() {
        val directory = Files.createTempDirectory("qq-file-test")
        val inside = Files.createFile(directory.resolve("report.txt")).toFile()
        val outside = Files.createTempFile("qq-file-outside", ".txt").toFile()

        assertEquals(inside.canonicalFile, resolveShareableFile(directory.toFile(), "report.txt"))
        assertFailsWith<IllegalArgumentException> { resolveShareableFile(directory.toFile(), outside.absolutePath) }

        inside.delete()
        outside.delete()
        directory.toFile().delete()
    }

    @Test
    fun buildsCodexLocalImageInput() {
        val input = codexInput("检查截图", listOf("/tmp/screenshot.png"))

        assertEquals("检查截图", input[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("localImage", input[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("/tmp/screenshot.png", input[1].jsonObject["path"]?.jsonPrimitive?.content)
    }

    @Test
    fun parsesStreamingMessagesAsPlainText() {
        val event = Json.parseToJsonElement("""{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"type":"agentMessage","text":"# 结果\n- **完成** [文件](https://example.com)\n```kotlin\nval x = 1\n```"}}}""").jsonObject

        assertEquals("结果\n完成 文件：https://example.com\n\nval x = 1", stripMarkdown(appServerAgentMessage(event).orEmpty()))
    }

    @Test
    fun unwrapsMarkdownLinkTargets() {
        assertEquals(
            "文件：/tmp/report.md；网页：https://example.com/a_(b)；图片：https://example.com/a.png",
            stripMarkdown(
                "[文件]({[<(/tmp/report.md)>]})；[网页](<<https://example.com/a_(b)>>)；![图片](<[https://example.com/a.png]>)"
            )
        )
    }

    @Test
    fun convertsMarkdownTablesToReadablePlainText() {
        val markdown = """
            查到你说的是 Tibo（@thsottiaux）。

            | 时间 | 内容 | 链接 |
            | :--- | :--- | :--- |
            | 约 20 分钟前 | **欢迎加入** Better \| Cyber 团队 | [原帖](https://example.com) |
            | 约 2 天前 | Codex 的下一阶段形态 | 原帖、镜像 |

            整体看，他最近主要在聊 Codex。
        """.trimIndent()

        assertEquals(
            """
            查到你说的是 Tibo（@thsottiaux）。

            • 时间：约 20 分钟前；内容：欢迎加入 Better | Cyber 团队；链接：原帖：https://example.com
            • 时间：约 2 天前；内容：Codex 的下一阶段形态；链接：原帖、镜像

            整体看，他最近主要在聊 Codex。""".trimIndent(),
            stripMarkdown(markdown)
        )
    }

    @Test
    fun interruptsCliAndKeepsPartialOutput() {
        val directory = Files.createTempDirectory("cli-command-test").toFile()
        val result = runCli("printf partial; sleep 2", directory, 1)

        assertTrue(result.timedOut)
        assertEquals("partial", result.output)
        directory.delete()
    }

    @Test
    fun quoteComponentIsMetadataAndHiddenFromTextJoin() {
        val quote = QuoteComponent("被引用的内容", listOf(1, 2, 3))

        assertEquals("被引用的内容", quote.context)
        assertEquals(listOf(1, 2, 3), quote.sourceIds)
        assertEquals("", quote.asString())
        assertEquals("我的回复", (listOf(quote, StringComponent("我的回复"))).joinToString("") { it.asString() })
    }

    @Test
    fun detectsQuoteToCodexMessageByMessageId() {
        val codexQuote = QuoteComponent("Codex 发的消息", listOf(10, 11))
        val otherQuote = QuoteComponent("别人的消息", listOf(20))

        assertTrue(isQuoteToCodexMessage(listOf(codexQuote), setOf(10, 11)))
        assertTrue(isQuoteToCodexMessage(listOf(otherQuote, codexQuote), setOf(11)))
        assertTrue(!isQuoteToCodexMessage(listOf(otherQuote), setOf(10, 11)))
        assertTrue(!isQuoteToCodexMessage(emptyList(), setOf(10, 11)))
        assertTrue(!isQuoteToCodexMessage(listOf(codexQuote), null))
        assertTrue(!isQuoteToCodexMessage(listOf(QuoteComponent("无 ID 的引用")), setOf(10, 11)))
    }

    @Test
    fun buildsPromptWithQuotedMessages() {
        assertEquals("", buildPrompt(emptyList(), ""))
        assertEquals("处理这个", buildPrompt(emptyList(), "处理这个"))
        assertEquals("引用消息：前一条消息", buildPrompt(listOf("前一条消息"), ""))
        assertEquals(
            "引用消息：第一条\n\n引用消息：第二条\n\n处理这个",
            buildPrompt(listOf("第一条", "第二条"), "处理这个")
        )
    }

    @Test
    fun extractsQuotedContentFromQuoteReply() {
        val source = MessageSourceBuilder()
            .id(1, 2, 3)
            .internalId(4)
            .time(5)
            .sender(123456L)
            .target(654321L)
            .messages { +PlainText("被引用的内容") }
            .build(botId = 10001L, kind = MessageSourceKind.GROUP)

        assertEquals("被引用的内容", quoteContent(source))
        assertNull(quoteContent(MessageSourceBuilder().build(botId = 10001L, kind = MessageSourceKind.GROUP)))
    }

    @Test
    fun convertsQuoteReplyToQuoteComponentWithoutPollutingText() = runBlocking {
        val source = MessageSourceBuilder()
            .id(1, 2, 3)
            .internalId(4)
            .time(5)
            .sender(123456L)
            .target(654321L)
            .messages { +PlainText("被引用的内容") }
            .build(botId = 10001L, kind = MessageSourceKind.GROUP)
        val chain = buildMessageChain {
            +QuoteReply(source)
            +PlainText("我的回复")
        }

        val components = asMessage(chain)
        assertEquals(2, components.size)
        val quote = assertIs<QuoteComponent>(components[0])
        assertEquals("被引用的内容", quote.context)
        assertEquals(listOf(1, 2, 3), quote.sourceIds)
        assertEquals("我的回复", components.joinToString("") { it.asString() })
    }
}

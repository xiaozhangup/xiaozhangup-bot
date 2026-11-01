package me.xiaozhangup.bot.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.Properties
import java.util.concurrent.TimeUnit

/** One rootless container and persistent filesystem per configured Codex instance. */
internal class GroupContainer(val groupId: String, private val config: Properties) : AutoCloseable {
    private val root = File(requireNotNull(config.getProperty("groups.directory")) { "未配置 groups.directory" }).canonicalFile
    private val directory = File(root, groupId)
    val workspace = File(directory, "workspace")
    private val name = "qbot-$groupId"
    private val engine = config.getProperty("podman.path", "/usr/bin/podman")
    private val image = config.getProperty("container.image", "localhost/qbot-codex:0.163.0-1")
    private val memory = config.getProperty("container.memory.$groupId", config.getProperty("container.memory", "3g"))
    @Volatile private var started = false
    @Volatile private var closing = false

    init { require(groupId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "无效 Codex 实例名，仅支持字母、数字、下划线和连字符，最长 64 字符" } }

    @Synchronized
    fun start() {
        check(!closing) { "群执行环境正在关闭" }
        if (started && control("inspect", "--format", "{{.State.Running}}", name).output.trim() == "true") return
        require(root.isDirectory) { "群目录根路径不存在：$root" }
        for (folder in listOf(directory, workspace, File(directory, "home"), File(directory, "codex"))) {
            require(folder.canonicalFile.toPath().startsWith(root.toPath())) { "群目录不能指向根目录之外" }
            Files.createDirectories(folder.toPath())
        }
        val exists = control("container", "exists", name)
        check(exists.code in 0..1) { "Podman 查询失败：${exists.output}" }
        if (exists.code == 0) {
            val inspected = checked("inspect", name).let { Json.parseToJsonElement(it).jsonArray.single().jsonObject }
            val labels = inspected.getValue("Config").jsonObject.getValue("Labels").jsonObject
            require(labels.string("qbot.root") == directory.absolutePath && labels.string("qbot.spec") == "1") {
                "容器 $name 不属于当前配置，请先检查容器归属"
            }
            checked("start", name)
        } else {
            val info = Json.parseToJsonElement(checked("info", "--format", "json")).jsonObject
            require(info.getValue("host").jsonObject.getValue("security").jsonObject["rootless"]?.jsonPrimitive?.content == "true") {
                "群容器必须使用 Rootless Podman"
            }
            val addresses = Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>().map { it.hostAddress }.filterNot { it.startsWith("127.") }.distinct()
            checked("run", "-d", "--pull=never", "--name", name,
                "--label", "qbot.root=${directory.absolutePath}", "--label", "qbot.spec=1",
                "--userns=keep-id:uid=1000,gid=1000", "--user=0:0",
                "--read-only", "--read-only-tmpfs=false", "--http-proxy=false",
                "--cap-drop=ALL", "--cap-add=NET_ADMIN,SETUID,SETGID,SETPCAP", "--security-opt=no-new-privileges",
                "--network=pasta:--ipv4-only,--no-map-gw,-t,none,-u,none,-T,none,-U,none", "--no-hosts",
                "--cpus=${config.getProperty("container.cpus", "2")}",
                "--memory=$memory", "--memory-swap=$memory",
                "--pids-limit=${config.getProperty("container.pids", "256")}", "--log-opt=max-size=10mb",
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=256m", "--tmpfs", "/run:rw,nosuid,nodev,size=16m",
                "--env", "QBOT_HOST_IPV4=${addresses.joinToString(" ")}",
                "--volume", "${workspace.absolutePath}:/workspace:Z",
                "--volume", "${File(directory, "home").absolutePath}:/home/runner:Z",
                "--volume", "${File(directory, "codex").absolutePath}:/codex:Z", image)
        }
        val ready = execute(execPrefix() + listOf("/usr/bin/timeout", "10", "/bin/sh", "-c", "until test -f /run/qbot-ready; do sleep 0.1; done"))
        check(ready.code == 0) { "群容器启动失败：${ready.output}" }
        started = true
    }

    fun appServerCommand(): List<String> {
        start()
        return execPrefix(interactive = true) + listOf("/usr/local/bin/codex") + codexAppServerCommand().drop(1)
    }

    fun run(arguments: List<String>, timeoutSeconds: Long = 60): ContainerResult {
        start()
        return execute(execPrefix() + listOf("/usr/bin/timeout", "--signal=TERM", "--kill-after=2s", "${timeoutSeconds}s") + arguments,
            timeoutSeconds = timeoutSeconds + 5)
    }

    fun importFile(source: File, destination: String): String {
        start()
        val result = execute(execPrefix(interactive = true) + listOf("/usr/bin/timeout", "--kill-after=2s", "115s", "/usr/local/bin/qbot-files", "put", destination), input = source, timeoutSeconds = 120)
        check(result.code == 0) { "写入群容器失败：${result.output}" }
        return result.output.trim()
    }

    fun exportFile(path: String): File {
        start()
        val temp = File.createTempFile("qbot-send-", ".bin")
        Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------"))
        try {
            val result = execute(execPrefix() + listOf("/usr/bin/timeout", "--kill-after=2s", "300s", "/usr/local/bin/qbot-files", "get", path), output = temp, timeoutSeconds = 305)
            check(result.code == 0) { "读取群容器文件失败：${result.output}" }
            return temp
        } catch (e: Exception) { temp.delete(); throw e }
    }

    private fun execPrefix(interactive: Boolean = false) = listOf(engine, "exec") +
        (if (interactive) listOf("-i") else emptyList()) + listOf("--user", "1000:1000", "--workdir", "/workspace", name)

    private fun control(vararg arguments: String) = execute(listOf(engine) + arguments)
    private fun checked(vararg arguments: String): String = control(*arguments).let {
        check(it.code == 0) { "Podman ${arguments.first()} 失败：${it.output}" }
        it.output
    }

    @Synchronized
    override fun close() {
        closing = true
        if (started) checked("stop", "--time", "5", name)
        started = false
    }
}

internal data class ContainerResult(val output: String, val code: Int, val timedOut: Boolean)

private fun execute(command: List<String>, input: File? = null, output: File? = null, timeoutSeconds: Long = 60): ContainerResult {
    val builder = ProcessBuilder(command)
    if (input == null) builder.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))) else builder.redirectInput(input)
    if (output == null) builder.redirectErrorStream(true) else builder.redirectOutput(output)
    val process = builder.start()
    val collected = StringBuilder()
    var truncated = false
    val reader = Thread {
        val stream = if (output == null) process.inputStream else process.errorStream
        stream.bufferedReader().use { source ->
            val buffer = CharArray(4096)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                synchronized(collected) {
                    val remaining = 1024 * 1024 - collected.length
                    collected.append(buffer, 0, minOf(count, remaining))
                    if (count > remaining) truncated = true
                }
            }
        }
    }.apply { isDaemon = true; name = "qbot-container-output"; start() }
    val timedOut = !process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (timedOut) process.destroyForcibly().waitFor()
    reader.join(1000)
    val code = process.exitValue()
    val text = synchronized(collected) { collected.toString() + if (truncated) "\n[输出超过 1 MiB，已截断]" else "" }
    return ContainerResult(text, code, timedOut || code == 124 || code == 137)
}

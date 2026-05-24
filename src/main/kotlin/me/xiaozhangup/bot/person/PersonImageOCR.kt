package me.xiaozhangup.bot.person

import me.xiaozhangup.bot.util.dataFolder
import me.xiaozhangup.bot.util.properties
import me.xiaozhangup.bot.util.warning
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

object PersonImageOCR {

    private const val SCRIPT_RESOURCE = "/person/ocr.py"
    private const val SCRIPT_NAME = "ocr.py"
    private const val DOWNLOAD_TIMEOUT_MS = 10_000
    private const val CACHE_CAPACITY = 256

    private val config by lazy { properties("person") }

    private val enabled: Boolean by lazy {
        config.getProperty("ocr.enabled")?.trim()?.lowercase()?.let {
            it == "true" || it == "1" || it == "yes"
        } ?: true
    }

    private val pythonCmd: String by lazy {
        config.getProperty("ocr.python")?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv("PYTHON")
            ?: "python3"
    }

    private val timeoutSeconds: Long by lazy {
        config.getProperty("ocr.timeout.seconds")?.toLongOrNull()?.takeIf { it > 0 } ?: 60L
    }

    private val cache: MutableMap<String, String> = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            return size > CACHE_CAPACITY
        }
    }
    private val cacheLock = Any()

    private val scriptFile: File by lazy {
        val folder = dataFolder("person")
        val file = File(folder, SCRIPT_NAME)
        val scriptBytes = PersonImageOCR::class.java.getResourceAsStream(SCRIPT_RESOURCE)?.use { it.readBytes() }
            ?: throw IllegalStateException("Resource $SCRIPT_RESOURCE not found")
        if (!file.exists() || !file.readBytes().contentEquals(scriptBytes)) {
            file.writeBytes(scriptBytes)
        }
        file
    }

    fun recognize(url: String): String {
        if (!enabled) return ""
        if (url.isBlank()) return ""

        synchronized(cacheLock) { cache[url] }?.let { return it }

        val text = try {
            val tmp = downloadImage(url)
            try {
                runOcr(tmp)
            } finally {
                tmp.delete()
            }
        } catch (e: Exception) {
            warning("[PersonImageOCR] OCR 失败: ${e.message}")
            ""
        }

        synchronized(cacheLock) { cache[url] = text }
        return text
    }

    private fun downloadImage(url: String): File {
        val conn = URL(url).openConnection()
        conn.connectTimeout = DOWNLOAD_TIMEOUT_MS
        conn.readTimeout = DOWNLOAD_TIMEOUT_MS
        val tmp = File.createTempFile("person_ocr_", ".img")
        conn.getInputStream().use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        return tmp
    }

    private fun runOcr(image: File): String {
        val process = ProcessBuilder(pythonCmd, scriptFile.absolutePath, image.absolutePath)
            .redirectErrorStream(false)
            .start()

        val stderrBuffer = StringBuilder()
        val stderrThread = Thread {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        synchronized(stderrBuffer) { stderrBuffer.appendLine(line) }
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("OCR 进程超时(${timeoutSeconds}s)")
        }

        val stdout = process.inputStream.bufferedReader().use { it.readText() }.trim()
        stderrThread.join(1000)

        if (process.exitValue() != 0) {
            throw RuntimeException("OCR 进程退出码 ${process.exitValue()}: $stderrBuffer")
        }
        return stdout
    }
}

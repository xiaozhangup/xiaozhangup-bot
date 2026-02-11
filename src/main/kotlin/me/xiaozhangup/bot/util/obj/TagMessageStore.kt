package me.xiaozhangup.bot.util.obj

import me.xiaozhangup.bot.util.extractTags
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class TagMessageStore(dir: File) {

    private val dataFile = File(dir, "data.bin")
    private val indexFile = File(dir, "index.bin")

    private val index = ConcurrentHashMap<String, MutableList<Long>>()
    private val dataRaf = RandomAccessFile(dataFile, "rw")
    private val indexRaf = RandomAccessFile(indexFile, "rw")

    init {
        if (!dir.exists()) dir.mkdirs()
        loadIndex()
    }

    fun insert(text: String) {
        val tags = extractTags(text)
        if (tags.isEmpty()) return

        val offset = dataRaf.length()
        dataRaf.seek(offset)

        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        dataRaf.writeInt(bytes.size)
        dataRaf.write(bytes)

        for (tag in tags) {
            index.computeIfAbsent(tag) { mutableListOf() }.add(offset)
            writeIndexRecord(tag, offset)
        }
    }

    fun get(vararg tags: String): List<String> {
        if (tags.isEmpty()) return emptyList()

        val offsetSets: List<Set<Long>> = tags
            .mapNotNull { index[it]?.toSet() }
            .ifEmpty { return emptyList() }

        val commonOffsets: Set<Long> =
            offsetSets.reduce { a, b -> a intersect b }

        val result = ArrayList<String>(commonOffsets.size)
        for (offset in commonOffsets.sorted()) {
            dataRaf.seek(offset)
            val len = dataRaf.readInt()
            val bytes = ByteArray(len)
            dataRaf.readFully(bytes)
            result.add(String(bytes, StandardCharsets.UTF_8))
        }
        return result
    }

    fun close() {
        dataRaf.close()
        indexRaf.close()
    }

    private fun writeIndexRecord(tag: String, offset: Long) {
        val tagBytes = tag.toByteArray(StandardCharsets.UTF_8)
        indexRaf.seek(indexRaf.length())
        indexRaf.writeInt(tagBytes.size)
        indexRaf.write(tagBytes)
        indexRaf.writeLong(offset)
    }

    private fun loadIndex() {
        if (!indexFile.exists()) return

        indexRaf.seek(0)
        while (indexRaf.filePointer < indexRaf.length()) {
            val tagLen = indexRaf.readInt()
            val tagBytes = ByteArray(tagLen)
            indexRaf.readFully(tagBytes)
            val tag = String(tagBytes, StandardCharsets.UTF_8)
            val offset = indexRaf.readLong()
            index.computeIfAbsent(tag) { mutableListOf() }.add(offset)
        }
    }
}

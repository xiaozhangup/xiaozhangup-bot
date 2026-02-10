package me.xiaozhangup.bot.util.obj

import java.io.*

class TextSimilarityStore(
    private val maxSize: Int,
    private val file: File? = null
) {

    private val history =
        object : LinkedHashMap<String, Set<String>>(16, 0.75f, false) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Set<String>>?
            ): Boolean {
                return size > maxSize
            }
        }

    init {
        if (file != null && file.exists()) {
            loadFromFile()
        }
    }

    @Synchronized
    fun insert(text: String): Double {
        // 完全命中
        if (history.containsKey(text)) {
            appendToFile(text)
            return 1.0
        }

        val tokens = tokenize(text)

        var maxSimilarity = 0.0
        for ((_, oldTokens) in history) {
            val sim = jaccard(tokens, oldTokens)
            if (sim > maxSimilarity) {
                maxSimilarity = sim
            }
        }

        history[text] = tokens
        appendToFile(text)

        return maxSimilarity
    }

    private fun appendToFile(text: String) {
        file ?: return
        DataOutputStream(
            BufferedOutputStream(FileOutputStream(file, true))
        ).use { out ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
    }

    private fun loadFromFile() {
        DataInputStream(
            BufferedInputStream(FileInputStream(file))
        ).use { input ->
            while (true) {
                try {
                    val len = input.readInt()
                    val bytes = ByteArray(len)
                    input.readFully(bytes)
                    val text = String(bytes, Charsets.UTF_8)
                    history[text] = tokenize(text)
                } catch (_: EOFException) {
                    break
                }
            }
        }
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    private fun tokenize(text: String): Set<String> {
        return text
            .lowercase()
            .split(Regex("\\W+"))
            .filter { it.isNotBlank() }
            .toSet()
    }
}


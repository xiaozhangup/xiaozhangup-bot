package me.xiaozhangup.bot.util.obj

class FixedSizeMap<K, V>(
    private val capacity: Int
) : LinkedHashMap<K, V>(capacity, 0.75f, false) {

    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
        return size > capacity
    }
}

package me.xiaozhangup.bot.util.obj

class FixedSizeList<E>(
    private val capacity: Int
) : Iterable<E> {

    private val data = ArrayList<E>(capacity)
    private var head = 0

    val size: Int
        get() = data.size

    fun add(element: E) {
        if (data.size < capacity) {
            data.add(element)
        } else {
            data[head] = element
            head = (head + 1) % capacity
        }
    }

    operator fun get(index: Int): E {
        if (index !in 0 until size) {
            throw IndexOutOfBoundsException("Index $index out of bounds, size=$size")
        }
        return data[(head + index) % capacity]
    }

    fun clear() {
        data.clear()
        head = 0
    }

    override fun iterator(): Iterator<E> =
        (0 until size).map { this[it] }.iterator()
}


package com.siprikorea.hexeditor.core

/**
 * Random-access, read-only bytes. Implementations may be backed by memory, a file on disk
 * (read on demand, so huge files open instantly) or a JS-side buffer.
 *
 * [read] must be safe to call from several threads at once; it holds no cursor state.
 */
interface ByteSource {
    val size: Long

    /** Copies [length] bytes starting at [position] into [dst]; the range must be in bounds. */
    fun read(position: Long, dst: ByteArray, dstOffset: Int, length: Int)

    fun close() {}
}

class ArrayByteSource(internal val data: ByteArray) : ByteSource {
    override val size: Long get() = data.size.toLong()

    override fun read(position: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        val p = position.toInt()
        data.copyInto(dst, dstOffset, p, p + length)
    }
}

/** Reads a whole source into memory; only for things known to be small (e.g. template files). */
fun ByteSource.readAll(limit: Int = 64 * 1024 * 1024): ByteArray {
    require(size <= limit) { "File too large (${size} bytes)" }
    return ByteArray(size.toInt()).also { read(0, it, 0, it.size) }
}

/**
 * Single-threaded LRU page cache over a [ByteSource]. Each consumer (hex view, template run,
 * search) uses its own instance so no locking is needed.
 */
class CachedReader(
    private val source: ByteSource,
    private val pageSize: Int = 64 * 1024,
    private val maxPages: Int = 32,
) {
    val size: Long = source.size

    // Small LRU: most accesses hit the last page, so check that before the map.
    private val pages = LinkedHashMap<Long, ByteArray>()
    private var lastIndex = -1L
    private var lastPage: ByteArray = EMPTY

    private fun page(index: Long): ByteArray {
        if (index == lastIndex) return lastPage
        var p = pages.remove(index)
        if (p == null) {
            val start = index * pageSize
            val len = minOf(pageSize.toLong(), size - start).toInt()
            p = ByteArray(len)
            source.read(start, p, 0, len)
            if (pages.size >= maxPages) pages.remove(pages.keys.first())
        }
        pages[index] = p // re-insert to mark as most recently used
        lastIndex = index
        lastPage = p
        return p
    }

    /** Unsigned byte at [pos]. */
    fun byte(pos: Long): Int {
        if (pos < 0 || pos >= size) throw IndexOutOfBoundsException("offset $pos, size $size")
        return page(pos / pageSize)[(pos % pageSize).toInt()].toInt() and 0xFF
    }

    fun read(pos: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        var p = pos
        var o = dstOffset
        var remaining = length
        while (remaining > 0) {
            val page = page(p / pageSize)
            val inPage = (p % pageSize).toInt()
            val n = minOf(remaining, page.size - inPage)
            page.copyInto(dst, o, inPage, inPage + n)
            p += n
            o += n
            remaining -= n
        }
    }

    /** Unsigned integer of [n] bytes (1..8) at [pos]. */
    fun readRaw(pos: Long, n: Int, littleEndian: Boolean): Long {
        val inPage = (pos % pageSize).toInt()
        val page = page(pos / pageSize)
        var v = 0L
        if (inPage + n <= page.size) {
            // Fast path: the value lies within one page.
            if (littleEndian) {
                for (i in n - 1 downTo 0) v = (v shl 8) or (page[inPage + i].toLong() and 0xFF)
            } else {
                for (i in 0 until n) v = (v shl 8) or (page[inPage + i].toLong() and 0xFF)
            }
            return v
        }
        if (littleEndian) {
            for (i in n - 1 downTo 0) v = (v shl 8) or byte(pos + i).toLong()
        } else {
            for (i in 0 until n) v = (v shl 8) or byte(pos + i).toLong()
        }
        return v
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

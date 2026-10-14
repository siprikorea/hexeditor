package com.siprikorea.hexeditor.core

/** Sign-extends the low [size] bytes of [raw]. */
fun signExtend(raw: Long, size: Int): Long {
    if (size >= 8) return raw
    val shift = 64 - size * 8
    return (raw shl shift) shr shift
}

private val HEX = "0123456789ABCDEF"

fun Int.toHex(digits: Int): String {
    val sb = StringBuilder(digits)
    for (i in digits - 1 downTo 0) sb.append(HEX[(this ushr (i * 4)) and 0xF])
    return sb.toString()
}

fun Long.toHex(digits: Int = 16): String {
    val sb = StringBuilder(digits)
    for (i in digits - 1 downTo 0) sb.append(HEX[((this ushr (i * 4)) and 0xF).toInt()])
    return sb.toString()
}

fun Byte.toHex(): String = (toInt() and 0xFF).toHex(2)

fun isPrintableAscii(b: Int): Boolean = b in 0x20..0x7E

/** Parses "1F 2a ff" / "1F2AFF" into bytes; returns null on malformed input. */
fun parseHexString(text: String): ByteArray? {
    val clean = text.filter { !it.isWhitespace() }.removePrefix("0x").removePrefix("0X")
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    if (!clean.all { it.digitToIntOrNull(16) != null }) return null
    return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/** Parses an offset written as decimal, `0x..` hex or `..h` hex. */
fun parseOffset(text: String): Long? {
    val t = text.trim()
    return when {
        t.startsWith("0x", ignoreCase = true) -> t.substring(2).toLongOrNull(16)
        t.endsWith("h", ignoreCase = true) -> t.dropLast(1).toLongOrNull(16)
        else -> t.toLongOrNull()
    }
}

private const val SEARCH_BLOCK = 1 shl 20

/**
 * Finds [pattern] starting at [from] (inclusive), wrapping around the end of the data.
 * Reads in 1 MiB blocks so it works on files of any size, and suspends between blocks so the
 * search can be cancelled and doesn't freeze single-threaded (web) UIs. Returns -1 if not found.
 */
suspend fun ByteSource.find(pattern: ByteArray, from: Long, forward: Boolean = true): Long {
    if (pattern.isEmpty() || pattern.size > size) return -1
    val last = size - pattern.size
    return if (forward) {
        val start = from.coerceIn(0, last + 1)
        scanForward(pattern, start, last).takeIf { it >= 0 } ?: scanForward(pattern, 0, minOf(start - 1, last))
    } else {
        val start = from.coerceIn(-1, last)
        scanBackward(pattern, 0, start).takeIf { it >= 0 } ?: scanBackward(pattern, start + 1, last)
    }
}

/** First match whose start lies in [lo, hi]. */
private suspend fun ByteSource.scanForward(pattern: ByteArray, lo: Long, hi: Long): Long {
    if (lo > hi) return -1
    val m = pattern.size
    val buf = ByteArray(SEARCH_BLOCK + m - 1)
    var blockStart = lo
    while (blockStart <= hi) {
        val starts = minOf(SEARCH_BLOCK.toLong(), hi - blockStart + 1).toInt()
        read(blockStart, buf, 0, starts + m - 1)
        val i = indexIn(buf, starts, pattern)
        if (i >= 0) return blockStart + i
        blockStart += starts
        kotlinx.coroutines.yield()
    }
    return -1
}

/** Last match whose start lies in [lo, hi]. */
private suspend fun ByteSource.scanBackward(pattern: ByteArray, lo: Long, hi: Long): Long {
    if (lo > hi) return -1
    val m = pattern.size
    val buf = ByteArray(SEARCH_BLOCK + m - 1)
    var blockEnd = hi // inclusive
    while (blockEnd >= lo) {
        val starts = minOf(SEARCH_BLOCK.toLong(), blockEnd - lo + 1).toInt()
        val blockStart = blockEnd - starts + 1
        read(blockStart, buf, 0, starts + m - 1)
        val i = lastIndexIn(buf, starts, pattern)
        if (i >= 0) return blockStart + i
        blockEnd = blockStart - 1
        kotlinx.coroutines.yield()
    }
    return -1
}

private fun indexIn(buf: ByteArray, starts: Int, pattern: ByteArray): Int {
    val first = pattern[0]
    outer@ for (i in 0 until starts) {
        if (buf[i] != first) continue
        for (j in 1 until pattern.size) if (buf[i + j] != pattern[j]) continue@outer
        return i
    }
    return -1
}

private fun lastIndexIn(buf: ByteArray, starts: Int, pattern: ByteArray): Int {
    val first = pattern[0]
    outer@ for (i in starts - 1 downTo 0) {
        if (buf[i] != first) continue
        for (j in 1 until pattern.size) if (buf[i + j] != pattern[j]) continue@outer
        return i
    }
    return -1
}

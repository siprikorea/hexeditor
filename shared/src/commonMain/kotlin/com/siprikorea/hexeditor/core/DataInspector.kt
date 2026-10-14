package com.siprikorea.hexeditor.core

data class InspectorRow(val type: String, val value: String)

/** Interprets the bytes at [offset] as every common primitive type, like 010's Inspector panel. */
fun inspect(doc: HexDocument, offset: Long, littleEndian: Boolean): List<InspectorRow> {
    val rows = mutableListOf<InspectorRow>()
    val avail = doc.size - offset
    if (offset < 0 || avail <= 0) return rows

    fun int(name: String, size: Int, signed: Boolean) {
        val text = if (avail < size) "-" else {
            val raw = doc.readRaw(offset, size, littleEndian)
            when {
                signed -> signExtend(raw, size).toString()
                size == 8 -> raw.toULong().toString()
                else -> raw.toString()
            }
        }
        rows += InspectorRow(name, text)
    }

    val b = doc.getUnsigned(offset)
    rows += InspectorRow("Binary", b.toString(2).padStart(8, '0'))
    int("int8", 1, true)
    int("uint8", 1, false)
    int("int16", 2, true)
    int("uint16", 2, false)
    int("int32", 4, true)
    int("uint32", 4, false)
    int("int64", 8, true)
    int("uint64", 8, false)
    rows += InspectorRow(
        "float",
        if (avail < 4) "-" else Float.fromBits(doc.readRaw(offset, 4, littleEndian).toInt()).toString()
    )
    rows += InspectorRow(
        "double",
        if (avail < 8) "-" else Double.fromBits(doc.readRaw(offset, 8, littleEndian)).toString()
    )
    rows += InspectorRow("char", if (isPrintableAscii(b)) "'${b.toChar()}'" else "-")
    rows += InspectorRow(
        "UTF-16",
        if (avail < 2) "-" else doc.readRaw(offset, 2, littleEndian).toInt().toChar().let {
            if (it.isISOControl() || it.isSurrogate()) "-" else "'$it'"
        }
    )
    rows += InspectorRow(
        "DOS time",
        if (avail < 2) "-" else dosTime(doc.readRaw(offset, 2, littleEndian).toInt())
    )
    rows += InspectorRow(
        "DOS date",
        if (avail < 2) "-" else dosDate(doc.readRaw(offset, 2, littleEndian).toInt())
    )
    rows += InspectorRow(
        "time_t",
        if (avail < 4) "-" else unixTime(doc.readRaw(offset, 4, littleEndian))
    )
    return rows
}

internal fun dosTime(v: Int): String {
    val h = (v shr 11) and 0x1F
    val m = (v shr 5) and 0x3F
    val s = (v and 0x1F) * 2
    return "${h.pad2()}:${m.pad2()}:${s.pad2()}"
}

internal fun dosDate(v: Int): String {
    val y = 1980 + ((v shr 9) and 0x7F)
    val m = (v shr 5) and 0x0F
    val d = v and 0x1F
    return "$y-${m.pad2()}-${d.pad2()}"
}

/** Formats seconds since the Unix epoch as UTC without relying on platform date APIs. */
internal fun unixTime(seconds: Long): String {
    val days = Math.floorDiv(seconds, 86400L)
    val secOfDay = Math.floorMod(seconds, 86400L)
    // Civil-from-days algorithm (Howard Hinnant).
    val z = days + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    val hh = secOfDay / 3600
    val mm = (secOfDay / 60) % 60
    val ss = secOfDay % 60
    return "$y-${m.toInt().pad2()}-${d.toInt().pad2()} ${hh.toInt().pad2()}:${mm.toInt().pad2()}:${ss.toInt().pad2()}"
}

private fun Int.pad2() = toString().padStart(2, '0')

private object Math {
    fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a xor b) < 0)) q - 1 else q
    }

    fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b
}

package com.siprikorea.hexeditor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.siprikorea.hexeditor.core.ArrayByteSource
import com.siprikorea.hexeditor.core.HexDocument
import com.siprikorea.hexeditor.core.find
import com.siprikorea.hexeditor.core.parseHexString
import com.siprikorea.hexeditor.core.parseOffset
import com.siprikorea.hexeditor.core.toHex
import com.siprikorea.hexeditor.platform.PickedFile
import com.siprikorea.hexeditor.platform.SavedFile
import com.siprikorea.hexeditor.template.Interpreter
import com.siprikorea.hexeditor.template.NodeKind
import com.siprikorea.hexeditor.template.TemplateNode
import com.siprikorea.hexeditor.template.TemplateResult
import com.siprikorea.hexeditor.template.Templates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

enum class Pane { HEX, ASCII }

/** A leaf variable's byte range; [color] indexes [HexColors.fieldBackgrounds]. */
class Highlight(val start: Long, val end: Long, val color: Int, val node: TemplateNode)

class EditorState {
    val doc = HexDocument()

    /** Mirrors [HexDocument.version] so Compose recomposes after edits. */
    var version by mutableStateOf(0L)
        private set

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var isModified by mutableStateOf(false)
        private set

    var fileName by mutableStateOf("untitled.bin")
        private set
    var filePath by mutableStateOf<String?>(null)
        private set

    var cursor by mutableStateOf(0L)
        private set
    /** Other end of the selection; null when nothing is selected. */
    var anchor by mutableStateOf<Long?>(null)
        private set
    /** 0 while editing the high nibble of the cursor byte, 1 for the low nibble. */
    var nibble by mutableStateOf(0)
        private set
    var pane by mutableStateOf(Pane.HEX)
    var insertMode by mutableStateOf(false)
    var littleEndian by mutableStateOf(true)
    var message by mutableStateOf("")
    /** True while a search or template run is in progress. */
    var busy by mutableStateOf(false)
        private set

    // Scrolling is owned here so goto/find can reveal the cursor.
    val bytesPerRow = 16
    var topRow by mutableStateOf(0L)
    var visibleRows = 1

    // ---- template
    var templateName by mutableStateOf("Example")
    var templateSource by mutableStateOf(Templates.byName("Example").source)
    var templateResult by mutableStateOf<TemplateResult?>(null)
        private set
    /** Leaf variables sorted by start offset: drives hex colouring and cursor -> node lookup. */
    var highlights by mutableStateOf<List<Highlight>>(emptyList())
        private set
    var autoRun by mutableStateOf(true)
    /** Template node last clicked in the results tree. */
    var selectedNode by mutableStateOf<TemplateNode?>(null)

    val size: Long get() = doc.size
    val rowCount: Long get() = doc.size / bytesPerRow + 1
    val selectionStart: Long get() = anchor?.let { minOf(it, cursor) } ?: cursor
    /** Inclusive end of the selection. */
    val selectionEnd: Long get() = anchor?.let { maxOf(it, cursor) } ?: cursor
    val hasSelection: Boolean get() = anchor != null && anchor != cursor
    val selectionLength: Long get() = if (hasSelection) selectionEnd - selectionStart + 1 else 0
    val title: String get() = (if (isModified) "*" else "") + fileName

    // ---------------------------------------------------------------- files

    fun load(file: PickedFile) {
        doc.replaceAll(file.source)
        fileName = file.name
        filePath = file.path
        anchor = null
        cursor = 0
        nibble = 0
        topRow = 0
        selectedNode = null
        templateResult = null
        highlights = emptyList()
        Templates.forFileName(file.name)?.let {
            templateName = it.name
            templateSource = it.source
        }
        message = "Opened ${file.name} (${formatSize(file.source.size)})"
        changed()
    }

    fun newFile() {
        load(PickedFile("untitled.bin", ArrayByteSource(ByteArray(0)), null))
        message = "New file"
    }

    fun saved(file: SavedFile) {
        fileName = file.name
        filePath = file.path
        // Reading from the saved file keeps the piece table small after many edits.
        file.reopened?.let(doc::rebase) ?: doc.markSaved()
        message = "Saved ${file.name}"
        changed()
    }

    // ---------------------------------------------------------------- navigation

    fun moveTo(offset: Long, extend: Boolean = false, pane: Pane? = null) {
        val target = offset.coerceIn(0, doc.size)
        if (extend) {
            if (anchor == null) anchor = cursor
        } else {
            anchor = null
        }
        cursor = target
        nibble = 0
        pane?.let { this.pane = it }
        ensureVisible(target)
    }

    fun moveBy(delta: Long, extend: Boolean) = moveTo(cursor + delta, extend)

    fun select(start: Long, endInclusive: Long) {
        if (doc.size == 0L) return
        anchor = start.coerceIn(0, doc.size - 1)
        cursor = endInclusive.coerceIn(0, doc.size - 1)
        nibble = 0
        ensureVisible(cursor)
        ensureVisible(start)
    }

    fun selectAll() {
        if (doc.size > 0) select(0, doc.size - 1)
    }

    fun ensureVisible(offset: Long) {
        val row = offset / bytesPerRow
        if (row < topRow) topRow = row
        else if (row >= topRow + visibleRows) topRow = row - visibleRows + 1
        clampScroll()
    }

    fun scrollRows(rows: Long) {
        topRow += rows
        clampScroll()
    }

    fun clampScroll() {
        topRow = topRow.coerceIn(0, maxOf(0, rowCount - visibleRows))
    }

    fun goTo(text: String) {
        val offset = parseOffset(text)
        if (offset == null || offset < 0 || offset > doc.size) {
            message = "Invalid offset: $text"
            return
        }
        moveTo(offset)
        // Put the target near the top, like a "jump".
        topRow = offset / bytesPerRow
        clampScroll()
        message = "Jumped to 0x${offset.toHex(offsetDigits)}"
    }

    /** Searches a snapshot in the background; edits made meanwhile don't disturb it. */
    suspend fun find(text: String, hex: Boolean, forward: Boolean) {
        val pattern = if (hex) parseHexString(text) else text.encodeToByteArray()
        if (pattern == null || pattern.isEmpty()) {
            message = if (hex) "Invalid hex pattern" else "Nothing to find"
            return
        }
        val from = if (forward) {
            if (hasSelection) selectionStart + 1 else cursor
        } else {
            selectionStart - 1
        }
        val snapshot = doc.snapshot()
        busy = true
        message = "Searching…"
        val mark = TimeSource.Monotonic.markNow()
        val found = try {
            withContext(Dispatchers.Default) { snapshot.find(pattern, from, forward) }
        } finally {
            busy = false
        }
        if (found < 0) {
            message = "Not found"
            return
        }
        select(found, found + pattern.size - 1)
        message = "Found at 0x${found.toHex(offsetDigits)} (${mark.elapsedNow().inWholeMilliseconds} ms)"
    }

    /** Number of hex digits needed to show any offset in this file (at least 8). */
    val offsetDigits: Int get() = maxOf(8, (64 - doc.size.countLeadingZeroBits() + 3) / 4)

    // ---------------------------------------------------------------- editing

    fun typeHexDigit(digit: Int) {
        if (pane != Pane.HEX) return
        prepareTyping()
        val atEnd = cursor >= doc.size
        if (nibble == 0) {
            if (insertMode || atEnd) {
                doc.insert(cursor, byteArrayOf((digit shl 4).toByte()))
            } else {
                val b = doc.getUnsigned(cursor)
                doc.overwrite(cursor, byteArrayOf(((digit shl 4) or (b and 0x0F)).toByte()))
            }
            nibble = 1
        } else {
            val b = doc.getUnsigned(cursor)
            doc.overwrite(cursor, byteArrayOf(((b and 0xF0) or digit).toByte()))
            nibble = 0
            cursor++
        }
        anchor = null
        changed()
        ensureVisible(cursor)
    }

    fun typeChar(c: Char) {
        if (pane != Pane.ASCII || c.code > 0xFF) return
        prepareTyping()
        val b = byteArrayOf(c.code.toByte())
        if (insertMode || cursor >= doc.size) doc.insert(cursor, b) else doc.overwrite(cursor, b)
        cursor++
        nibble = 0
        changed()
        ensureVisible(cursor)
    }

    fun deleteForward() {
        if (!deleteSelectionIfAny()) {
            if (cursor >= doc.size) return
            doc.delete(cursor, 1)
        }
        nibble = 0
        changed()
    }

    fun backspace() {
        if (deleteSelectionIfAny()) {
            changed()
            return
        }
        if (cursor == 0L) return
        if (insertMode) {
            doc.delete(cursor - 1, 1)
            changed()
        }
        moveTo(cursor - 1)
    }

    fun pasteHex(text: String): Boolean {
        val bytes = parseHexString(text) ?: return false
        deleteSelectionIfAny()
        if (insertMode) doc.insert(cursor, bytes) else doc.overwrite(cursor, bytes)
        moveTo(cursor + bytes.size)
        changed()
        return true
    }

    /** Selected bytes as hex text; capped so copying a huge selection can't exhaust memory. */
    fun selectedBytesAsHex(): String {
        if (doc.size == 0L) return ""
        val start = selectionStart.coerceAtMost(doc.size - 1)
        val len = if (hasSelection) selectionLength else 1
        val n = minOf(len, MAX_COPY.toLong()).toInt()
        if (len > n) message = "Selection is larger than ${formatSize(MAX_COPY.toLong())}; copied the first part only"
        val bytes = doc.slice(start, n)
        val sb = StringBuilder(n * 3)
        for (i in bytes.indices) {
            if (i > 0) sb.append(' ')
            sb.append((bytes[i].toInt() and 0xFF).toHex(2))
        }
        return sb.toString()
    }

    fun undo() {
        val at = doc.undo() ?: return
        moveTo(at)
        changed()
    }

    fun redo() {
        val at = doc.redo() ?: return
        moveTo(at)
        changed()
    }

    /** Typing replaces the selection in insert mode and starts overwriting at it otherwise. */
    private fun prepareTyping() {
        if (!hasSelection) {
            anchor = null
            return
        }
        if (insertMode) deleteSelectionIfAny() else {
            cursor = selectionStart
            anchor = null
        }
        nibble = 0
    }

    private fun deleteSelectionIfAny(): Boolean {
        if (!hasSelection) {
            anchor = null
            return false
        }
        val start = selectionStart
        doc.delete(start, selectionLength)
        anchor = null
        cursor = start
        return true
    }

    private fun changed() {
        cursor = cursor.coerceIn(0, doc.size)
        version = doc.version
        canUndo = doc.canUndo
        canRedo = doc.canRedo
        isModified = doc.isModified
        clampScroll()
    }

    // ---------------------------------------------------------------- templates

    fun loadTemplate(name: String, source: String) {
        templateName = name
        templateSource = source
    }

    /**
     * Runs the template on a snapshot in the background. The snapshot shares the document's
     * storage (no copy), and cancelling the calling coroutine stops the interpreter.
     */
    suspend fun runTemplate() {
        val snapshot = doc.snapshot()
        val source = templateSource
        busy = true
        val mark = TimeSource.Monotonic.markNow()
        val (result, lights) = try {
            withContext(Dispatchers.Default) {
                val ctx = currentCoroutineContext()
                val r = Interpreter.run(source, snapshot) { ctx.ensureActive() }
                r to buildHighlights(r.root)
            }
        } finally {
            busy = false
        }
        templateResult = result
        highlights = lights
        // Keep the selection pointing at the "same" node across re-runs when possible.
        selectedNode = selectedNode?.let { old -> lights.firstOrNull { it.node.offset == old.offset && it.node.name == old.name }?.node }
        val ms = mark.elapsedNow().inWholeMilliseconds
        message = result.error?.let { "Template error (line ${it.line}): ${it.message}" }
            ?: "Template ran OK: ${lights.size} fields in $ms ms"
    }

    fun clearTemplateResult() {
        templateResult = null
        highlights = emptyList()
        selectedNode = null
    }

    fun selectNode(node: TemplateNode) {
        selectedNode = node
        if (node.size > 0) select(node.offset, node.end - 1) else moveTo(node.offset)
    }

    /** Leaf variable covering [offset], found by binary search over [highlights]. */
    fun nodeAt(offset: Long): TemplateNode? {
        val i = highlights.indexAt(offset)
        return if (i >= 0) highlights[i].node else null
    }

    private fun buildHighlights(root: TemplateNode): List<Highlight> {
        val out = ArrayList<Highlight>()
        fun visit(n: TemplateNode) {
            for (c in n.children) {
                val leaf = c.kind != NodeKind.STRUCT && (c.kind != NodeKind.ARRAY || c.elementType != null)
                if (leaf) {
                    if (c.size > 0) out += Highlight(c.offset, c.end, out.size % HexColors.fieldBackgrounds.size, c)
                } else {
                    visit(c)
                }
            }
        }
        visit(root)
        // Already in order unless the template seeks backwards.
        if ((1 until out.size).any { out[it - 1].start > out[it].start }) out.sortBy { it.start }
        return out
    }

    companion object {
        private const val MAX_COPY = 1 shl 20
    }
}

/** Index of the highlight covering [offset], or -1 (binary search; [this] is sorted by start). */
fun List<Highlight>.indexAt(offset: Long): Int {
    var lo = 0
    var hi = size - 1
    var candidate = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].start <= offset) {
            candidate = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    // Ranges may overlap (unions, FSeek); walk back a little to find one that covers the offset.
    var i = candidate
    var tries = 0
    while (i >= 0 && tries < 8) {
        if (offset < this[i].end) return i
        i--
        tries++
    }
    return -1
}

fun List<Highlight>.colorAt(offset: Long): Int {
    val i = indexAt(offset)
    return if (i >= 0) this[i].color else -1
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "${(bytes * 10 / (1L shl 30)) / 10.0} GB"
    bytes >= 1L shl 20 -> "${(bytes * 10 / (1L shl 20)) / 10.0} MB"
    bytes >= 1L shl 10 -> "${(bytes * 10 / (1L shl 10)) / 10.0} KB"
    else -> "$bytes bytes"
}

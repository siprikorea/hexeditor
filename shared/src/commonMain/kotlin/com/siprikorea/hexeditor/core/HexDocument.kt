package com.siprikorea.hexeditor.core

/** A run of bytes taken from [source]; the document is the concatenation of its pieces. */
class Piece(val source: ByteSource, val start: Long, val length: Long)

/**
 * Append-only buffer holding every byte typed or pasted. Existing bytes are never modified,
 * so snapshots taken on another thread stay valid while the UI keeps editing.
 */
private class AddBuffer : ByteSource {
    @kotlin.concurrent.Volatile
    private var data = ByteArray(4096)
    private var length = 0

    override val size: Long get() = length.toLong()

    fun append(bytes: ByteArray): Long {
        val start = length
        if (start + bytes.size > data.size) {
            var cap = data.size
            while (cap < start + bytes.size) cap *= 2
            data = data.copyOf(cap)
        }
        bytes.copyInto(data, start)
        length += bytes.size
        return start.toLong()
    }

    override fun read(position: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        val p = position.toInt()
        data.copyInto(dst, dstOffset, p, p + length)
    }
}

/** An immutable view of the document at one version; cheap to create and safe to read from any thread. */
class DocSnapshot internal constructor(val pieces: List<Piece>, private val starts: LongArray) : ByteSource {
    override val size: Long = if (pieces.isEmpty()) 0 else starts[pieces.size - 1] + pieces.last().length

    override fun read(position: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        if (length == 0) return
        var i = pieceIndex(starts, pieces.size, position)
        var pos = position
        var o = dstOffset
        var remaining = length
        while (remaining > 0) {
            val p = pieces[i]
            val inPiece = pos - starts[i]
            val n = minOf(remaining.toLong(), p.length - inPiece).toInt()
            p.source.read(p.start + inPiece, dst, o, n)
            pos += n
            o += n
            remaining -= n
            i++
        }
    }
}

/** Index of the piece containing [pos] (binary search over piece start offsets). */
private fun pieceIndex(starts: LongArray, count: Int, pos: Long): Int {
    var lo = 0
    var hi = count - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (starts[mid] <= pos) lo = mid else hi = mid - 1
    }
    return lo
}

/**
 * Editable document implemented as a piece table.
 *
 * The original file is never loaded or copied: it is referenced by pieces and read on demand,
 * so opening, inserting into and deleting from multi-gigabyte files is cheap. Each edit only
 * replaces a few pieces, and undo/redo swaps them back.
 */
class HexDocument(initial: ByteSource = ArrayByteSource(ByteArray(0))) {

    private val add = AddBuffer()
    private val pieces = ArrayList<Piece>()
    private var starts = LongArray(16)

    /** Sources owned by this document (opened files), closed when another file is loaded. */
    private val owned = mutableListOf<ByteSource>()

    var size: Long = 0
        private set

    /** Incremented on every change; UI observes this to know when to redraw/re-parse. */
    var version: Long = 0
        private set

    /** Undo-stack depth at the last save/load; null once that state is no longer reachable. */
    private var savedDepth: Int? = 0

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    // A transparent rebase entry is only ever pushed on top of a real edit, so emptiness suffices.
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val isModified: Boolean get() = savedDepth != undoStack.size
    val pieceCount: Int get() = pieces.size

    private var snapshotCache: DocSnapshot? = null
    private var readerCache: CachedReader? = null

    init {
        setSource(initial)
    }

    // ---------------------------------------------------------------- reading

    /** Immutable view of the current contents, e.g. for running a template in the background. */
    fun snapshot(): DocSnapshot =
        snapshotCache ?: DocSnapshot(ArrayList(pieces), starts.copyOf(pieces.size)).also { snapshotCache = it }

    /** Page-cached reader for the UI thread; recreated after each edit. */
    private fun reader(): CachedReader = readerCache ?: CachedReader(snapshot()).also { readerCache = it }

    fun getUnsigned(index: Long): Int = reader().byte(index)

    fun readRaw(offset: Long, n: Int, littleEndian: Boolean): Long = reader().readRaw(offset, n, littleEndian)

    fun slice(start: Long, length: Int): ByteArray {
        val s = start.coerceIn(0, size)
        val n = minOf(length.toLong(), size - s).toInt()
        return ByteArray(n).also { reader().read(s, it, 0, n) }
    }

    // ---------------------------------------------------------------- editing

    fun overwrite(offset: Long, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        require(offset in 0..size) { "offset out of range" }
        // Writing past the end grows the file (like typing at EOF in overwrite mode).
        replace(offset, minOf(bytes.size.toLong(), size - offset), bytes)
    }

    fun insert(offset: Long, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        require(offset in 0..size) { "offset out of range" }
        replace(offset, 0, bytes)
    }

    fun delete(offset: Long, length: Long) {
        if (length <= 0 || offset >= size) return
        replace(offset, minOf(length, size - offset), ByteArray(0))
    }

    /** Returns the offset the cursor should move to, or null when nothing to undo. */
    fun undo(): Long? {
        if (undoStack.isEmpty()) return null
        var edit: Edit
        do {
            edit = undoStack.removeLast()
            swap(edit.index, edit.inserted.size, edit.removed)
            redoStack.addLast(edit)
        } while (edit.transparent)
        changed()
        return edit.pos
    }

    fun redo(): Long? {
        val edit = redoStack.removeLastOrNull() ?: return null
        swap(edit.index, edit.removed.size, edit.inserted)
        undoStack.addLast(edit)
        // Re-apply a rebase that followed this edit so the piece list matches later history.
        while (redoStack.lastOrNull()?.transparent == true) {
            val r = redoStack.removeLast()
            swap(r.index, r.removed.size, r.inserted)
            undoStack.addLast(r)
        }
        changed()
        return edit.pos + edit.insertedLength
    }

    /** Replaces the whole document (e.g. after opening a file) and clears the history. */
    fun replaceAll(source: ByteSource) {
        owned.forEach { it.close() }
        owned.clear()
        owned += source
        undoStack.clear()
        redoStack.clear()
        setSource(source)
        markSaved()
        changed()
    }

    /**
     * After saving, point the document at the saved file so later reads don't depend on the edit
     * history. Undo still works because old pieces keep referencing the previous sources.
     */
    fun rebase(saved: ByteSource) {
        require(saved.size == size) { "saved size mismatch" }
        owned += saved
        // Recorded as a transparent edit (same bytes, different pieces) so that undo entries
        // recorded before it still find the piece layout they expect.
        val old = ArrayList(pieces)
        setSource(saved)
        if (undoStack.isNotEmpty()) undoStack.addLast(Edit(0, old, ArrayList(pieces), 0, 0, transparent = true))
        markSaved()
        changed()
    }

    fun markSaved() {
        savedDepth = undoStack.size
    }

    private fun setSource(source: ByteSource) {
        pieces.clear()
        if (source.size > 0) pieces += Piece(source, 0, source.size)
        recomputeStarts(0)
    }

    /** Replaces [removeLen] bytes at [pos] with [bytes], recording one undoable edit. */
    private fun replace(pos: Long, removeLen: Long, bytes: ByteArray) {
        val addStart = if (bytes.isEmpty()) -1L else add.append(bytes)
        val end = pos + removeLen

        // Range of pieces [first, last] touched by the edit.
        var first = if (pos >= size) pieces.size else pieceIndex(starts, pieces.size, pos)
        val last = if (removeLen == 0L) first.coerceAtMost(pieces.size - 1) else pieceIndex(starts, pieces.size, end - 1)
        val newPieces = ArrayList<Piece>(3)

        var mergedPrev = false
        if (first < pieces.size) {
            val p = pieces[first]
            val keep = pos - starts[first]
            if (keep > 0) newPieces += Piece(p.source, p.start, keep)
        }
        if (addStart >= 0) {
            // Typing sequentially extends the previous add-buffer piece instead of adding a new one.
            val prev = if (newPieces.isEmpty() && first > 0) pieces[first - 1] else null
            if (prev != null && prev.source === add && prev.start + prev.length == addStart) {
                newPieces += Piece(add, prev.start, prev.length + bytes.size)
                mergedPrev = true
            } else {
                newPieces += Piece(add, addStart, bytes.size.toLong())
            }
        }
        if (last >= first && last < pieces.size) {
            val p = pieces[last]
            val pieceEnd = starts[last] + p.length
            if (pieceEnd > end) {
                val skip = end - starts[last]
                newPieces += Piece(p.source, p.start + skip, p.length - skip)
            }
        }

        val removedCount = if (first < pieces.size) last - first + 1 else 0
        if (mergedPrev) first--
        val removed = ArrayList(pieces.subList(first, first + removedCount + if (mergedPrev) 1 else 0))

        val depth = savedDepth
        if (depth != null && depth > undoStack.size) savedDepth = null
        swap(first, removed.size, newPieces)
        undoStack.addLast(Edit(first, removed, newPieces, pos, bytes.size.toLong()))
        redoStack.clear()
        changed()
    }

    /** Replaces [count] pieces at [index] with [replacement]. */
    private fun swap(index: Int, count: Int, replacement: List<Piece>) {
        val window = pieces.subList(index, index + count)
        window.clear()
        window.addAll(replacement)
        recomputeStarts(index)
    }

    private fun recomputeStarts(from: Int) {
        if (starts.size < pieces.size + 1) starts = starts.copyOf(maxOf(pieces.size + 1, starts.size * 2))
        var s = if (from == 0) 0L else starts[from - 1] + pieces[from - 1].length
        for (i in from until pieces.size) {
            starts[i] = s
            s += pieces[i].length
        }
        size = s
    }

    private fun changed() {
        version++
        snapshotCache = null
        readerCache = null
    }

    private class Edit(
        val index: Int,
        val removed: List<Piece>,
        val inserted: List<Piece>,
        val pos: Long,
        val insertedLength: Long,
        /** A rebase: content is unchanged, undo/redo step through it automatically. */
        val transparent: Boolean = false,
    )
}

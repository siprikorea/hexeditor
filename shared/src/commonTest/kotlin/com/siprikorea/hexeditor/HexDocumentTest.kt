package com.siprikorea.hexeditor

import com.siprikorea.hexeditor.core.ArrayByteSource
import com.siprikorea.hexeditor.core.ByteSource
import com.siprikorea.hexeditor.core.CachedReader
import com.siprikorea.hexeditor.core.HexDocument
import com.siprikorea.hexeditor.core.find
import com.siprikorea.hexeditor.core.parseHexString
import com.siprikorea.hexeditor.core.parseOffset
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A huge virtual file whose byte at `p` is `p % 251`; nothing is allocated. */
class SyntheticSource(override val size: Long) : ByteSource {
    var reads = 0
    override fun read(position: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        reads++
        for (i in 0 until length) dst[dstOffset + i] = ((position + i) % 251).toByte()
    }
}

class HexDocumentTest {

    private fun doc(vararg b: Int) = HexDocument(ArrayByteSource(ByteArray(b.size) { b[it].toByte() }))

    private fun HexDocument.all(): ByteArray = slice(0, size.toInt())

    @Test
    fun overwriteInsertDeleteWithUndoRedo() {
        val d = doc(1, 2, 3, 4)
        d.overwrite(1, byteArrayOf(9))
        d.insert(0, byteArrayOf(7, 7))
        d.delete(3, 2)
        assertContentEquals(byteArrayOf(7, 7, 1, 4), d.all())

        d.undo(); assertContentEquals(byteArrayOf(7, 7, 1, 9, 3, 4), d.all())
        d.undo(); assertContentEquals(byteArrayOf(1, 9, 3, 4), d.all())
        d.undo(); assertContentEquals(byteArrayOf(1, 2, 3, 4), d.all())
        assertFalse(d.canUndo)
        d.redo(); d.redo(); d.redo()
        assertContentEquals(byteArrayOf(7, 7, 1, 4), d.all())
    }

    @Test
    fun overwriteAtEndGrowsFileAndUndoesAtomically() {
        val d = doc(1, 2)
        d.overwrite(1, byteArrayOf(5, 6, 7))
        assertContentEquals(byteArrayOf(1, 5, 6, 7), d.all())
        d.undo()
        assertContentEquals(byteArrayOf(1, 2), d.all())
    }

    @Test
    fun modifiedFlagFollowsUndoToSavedState() {
        val d = doc(1, 2)
        assertFalse(d.isModified)
        d.overwrite(0, byteArrayOf(3))
        assertTrue(d.isModified)
        d.undo()
        assertFalse(d.isModified)
        d.redo()
        d.markSaved()
        d.undo()
        d.overwrite(1, byteArrayOf(4)) // saved state is now unreachable
        d.undo()
        assertTrue(d.isModified)
    }

    @Test
    fun sequentialTypingCoalescesPieces() {
        val d = HexDocument(SyntheticSource(1000))
        for (i in 0 until 200) d.overwrite(100L + i, byteArrayOf(0x55))
        // original-left, one merged add piece, original-right
        assertEquals(3, d.pieceCount)
        for (i in 0 until 200) d.insert(500L + i, byteArrayOf(0x66))
        assertEquals(5, d.pieceCount)
        assertEquals(1200, d.size)
        // Undo still works one keystroke at a time.
        d.undo()
        assertEquals(1199, d.size)
    }

    @Test
    fun randomEditsMatchReferenceModel() {
        val rnd = Random(42)
        val initial = ByteArray(5000) { rnd.nextInt().toByte() }
        val d = HexDocument(ArrayByteSource(initial))
        val model = initial.toMutableList()
        repeat(2000) {
            val pos = rnd.nextInt(model.size + 1)
            when (rnd.nextInt(3)) {
                0 -> {
                    val b = ByteArray(rnd.nextInt(1, 20)) { rnd.nextInt().toByte() }
                    d.insert(pos.toLong(), b)
                    model.addAll(pos, b.toList())
                }
                1 -> {
                    val n = rnd.nextInt(1, 30)
                    d.delete(pos.toLong(), n.toLong())
                    repeat(minOf(n, model.size - pos)) { model.removeAt(pos) }
                }
                else -> {
                    val b = ByteArray(rnd.nextInt(1, 10)) { rnd.nextInt().toByte() }
                    d.overwrite(pos.toLong(), b)
                    for (i in b.indices) if (pos + i < model.size) model[pos + i] = b[i] else model.add(b[i])
                }
            }
        }
        assertContentEquals(model.toByteArray(), d.all())
        // Snapshots read the same bytes, from any offset, across piece boundaries.
        val snap = d.snapshot()
        val buf = ByteArray(model.size)
        snap.read(0, buf, 0, buf.size)
        assertContentEquals(model.toByteArray(), buf)
        // Undo everything back to the original.
        while (d.canUndo) d.undo()
        assertContentEquals(initial, d.all())
    }

    @Test
    fun snapshotIsUnaffectedByLaterEdits() {
        val d = doc(1, 2, 3)
        val snap = d.snapshot()
        d.overwrite(0, byteArrayOf(9))
        d.insert(1, ByteArray(10000)) // forces the add buffer to grow
        val buf = ByteArray(3)
        snap.read(0, buf, 0, 3)
        assertContentEquals(byteArrayOf(1, 2, 3), buf)
    }

    @Test
    fun multiGigabyteFilesUseLongOffsets() {
        val big = 6L shl 30 // 6 GiB, never materialised
        val d = HexDocument(SyntheticSource(big))
        val far = big - 10
        assertEquals((far % 251).toInt(), d.getUnsigned(far))
        d.insert(5L shl 30, byteArrayOf(1, 2, 3))
        d.delete(1, 1L shl 31)
        assertEquals(big + 3 - (1L shl 31), d.size)
        assertEquals(((big - 1) % 251).toInt(), d.getUnsigned(d.size - 1))
    }

    @Test
    fun pageCacheAvoidsRereading() {
        val src = SyntheticSource(1 shl 20)
        val r = CachedReader(src)
        for (i in 0 until 65536L) r.byte(i)
        assertEquals(1, src.reads)
        assertEquals(0x0201L, CachedReader(ArrayByteSource(byteArrayOf(1, 2))).readRaw(0, 2, littleEndian = true))
    }

    @Test
    fun findWrapsAroundAndCrossesBlocks() = runTest {
        val d = doc(0xAA, 0xBB, 0, 0xAA, 0xBB)
        val p = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val s = d.snapshot()
        assertEquals(3, s.find(p, 1))
        assertEquals(0, s.find(p, 4))
        assertEquals(3, s.find(p, 4, forward = false))
        assertEquals(-1, s.find(byteArrayOf(1), 0))

        // A match straddling the 1 MiB search block boundary.
        val bytes = ByteArray(3 shl 20)
        val at = (1 shl 20) - 2
        byteArrayOf(1, 2, 3, 4).copyInto(bytes, at)
        val src = ArrayByteSource(bytes)
        assertEquals(at.toLong(), src.find(byteArrayOf(1, 2, 3, 4), 0))
        assertEquals(at.toLong(), src.find(byteArrayOf(1, 2, 3, 4), bytes.size.toLong() - 1, forward = false))
    }

    @Test
    fun parsing() {
        assertContentEquals(byteArrayOf(0x1F, 0x2A, -1), parseHexString("1f 2A ff"))
        assertNull(parseHexString("abc"))
        assertEquals(255, parseOffset("0xff"))
        assertEquals(255, parseOffset("FFh"))
        assertEquals(42, parseOffset("42"))
        assertEquals(0x1_0000_0000, parseOffset("0x100000000"))
    }
}

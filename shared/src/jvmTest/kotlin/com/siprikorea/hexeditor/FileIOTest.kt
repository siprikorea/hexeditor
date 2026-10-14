package com.siprikorea.hexeditor

import com.siprikorea.hexeditor.core.HexDocument
import com.siprikorea.hexeditor.platform.openFile
import com.siprikorea.hexeditor.platform.saveFile
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class FileIOTest {

    @Test
    fun saveOverOpenFileThenUndo() = runBlocking {
        val file = File.createTempFile("hexeditor", ".bin")
        try {
            val original = Random(1).nextBytes(3 shl 20)
            file.writeBytes(original)

            val picked = openFile(file)
            val doc = HexDocument(picked.source)
            doc.overwrite(10, byteArrayOf(1, 2, 3))
            doc.insert(2_000_000, ByteArray(5000) { 7 })
            doc.delete(100, 50)
            val expected = doc.slice(0, doc.size.toInt())

            // Saving to the file that is still being read must not corrupt it.
            val saved = saveFile(doc.snapshot().pieces, file.name, file.absolutePath)!!
            assertContentEquals(expected, file.readBytes())
            doc.rebase(saved.reopened!!)
            assertFalse(doc.isModified)
            assertContentEquals(expected, doc.slice(0, doc.size.toInt()))

            // Undo past the save still reaches the original bytes (old handle keeps the old inode).
            while (doc.canUndo) doc.undo()
            assertContentEquals(original, doc.slice(0, doc.size.toInt()))
            doc.replaceAll(saved.reopened)
        } finally {
            file.delete()
        }
    }
}

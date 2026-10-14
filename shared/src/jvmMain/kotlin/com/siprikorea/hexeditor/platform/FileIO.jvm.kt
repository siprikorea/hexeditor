package com.siprikorea.hexeditor.platform

import com.siprikorea.hexeditor.core.ByteSource
import com.siprikorea.hexeditor.core.Piece
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Reads a file on demand with positional reads; FileChannel positional reads are thread-safe. */
class FileByteSource(file: File) : ByteSource {
    internal val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
    override val size: Long = channel.size()

    override fun read(position: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        val buf = ByteBuffer.wrap(dst, dstOffset, length)
        var p = position
        while (buf.hasRemaining()) {
            val n = channel.read(buf, p)
            if (n < 0) throw java.io.EOFException("Unexpected end of file at $p")
            p += n
        }
    }

    override fun close() = channel.close()
}

actual suspend fun openFileDialog(): PickedFile? {
    val dialog = FileDialog(null as Frame?, "Open", FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return openFile(File(dialog.directory, name))
}

actual suspend fun saveFile(pieces: List<Piece>, suggestedName: String, path: String?): SavedFile? {
    val target = path?.let(::File) ?: run {
        val dialog = FileDialog(null as Frame?, "Save As", FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val name = dialog.file ?: return null
        File(dialog.directory, name)
    }
    withContext(Dispatchers.IO) { writeAtomically(pieces, target) }
    return SavedFile(target.name, target.absolutePath, FileByteSource(target))
}

fun openFile(file: File): PickedFile = PickedFile(file.name, FileByteSource(file), file.absolutePath)

/**
 * Writes to a temporary file next to [target] and then moves it into place. The original file may
 * itself be one of the sources being read, so it must not be overwritten while writing; open
 * handles keep seeing the old contents after the rename, which keeps undo history valid.
 */
private fun writeAtomically(pieces: List<Piece>, target: File) {
    val dir = target.absoluteFile.parentFile
    val tmp = File.createTempFile(".${target.name}.", ".tmp", dir)
    try {
        FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
            val buf = ByteArray(1 shl 20)
            for (p in pieces) {
                val src = p.source
                if (src is FileByteSource) {
                    // Kernel-side copy for unmodified ranges of the original file.
                    var done = 0L
                    while (done < p.length) {
                        done += src.channel.transferTo(p.start + done, p.length - done, out)
                    }
                } else {
                    var done = 0L
                    while (done < p.length) {
                        val n = minOf(buf.size.toLong(), p.length - done).toInt()
                        src.read(p.start + done, buf, 0, n)
                        val bb = ByteBuffer.wrap(buf, 0, n)
                        while (bb.hasRemaining()) out.write(bb)
                        done += n
                    }
                }
            }
            out.force(false)
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        tmp.delete()
    }
}

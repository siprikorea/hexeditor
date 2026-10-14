package com.siprikorea.hexeditor.platform

import com.siprikorea.hexeditor.core.ArrayByteSource
import com.siprikorea.hexeditor.core.ByteSource
import com.siprikorea.hexeditor.core.Piece

/**
 * A file chosen by the user. Its bytes are read on demand through [source], so opening is
 * instant regardless of size. [path] is null on the web, where files can't be written in place.
 */
class PickedFile(val name: String, val source: ByteSource, val path: String?)

/** Result of a save. [reopened] reads the saved file back (desktop), letting the document rebase onto it. */
class SavedFile(val name: String, val path: String?, val reopened: ByteSource?)

/** Shows an open-file dialog; null when cancelled. */
expect suspend fun openFileDialog(): PickedFile?

/**
 * Streams [pieces] to [path] when given, otherwise asks the user where to save (on the web the
 * file is downloaded). Returns null when cancelled.
 */
expect suspend fun saveFile(pieces: List<Piece>, suggestedName: String, path: String?): SavedFile?

suspend fun saveBytes(bytes: ByteArray, suggestedName: String): SavedFile? =
    saveFile(listOf(Piece(ArrayByteSource(bytes), 0, bytes.size.toLong())), suggestedName, null)

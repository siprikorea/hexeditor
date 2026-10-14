package com.siprikorea.hexeditor.platform

import com.siprikorea.hexeditor.core.ArrayByteSource
import com.siprikorea.hexeditor.core.Piece
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

actual suspend fun openFileDialog(): PickedFile? = suspendCoroutine { cont ->
    pickFile { name, bytes ->
        // Kotlin/JS represents ByteArray as Int8Array, so the file buffer is used without copying.
        cont.resume(if (name == null) null else PickedFile(name, ArrayByteSource(bytes.unsafeCast<ByteArray>()), null))
    }
}

actual suspend fun saveFile(pieces: List<Piece>, suggestedName: String, path: String?): SavedFile? {
    // A Blob can be assembled from views of the existing buffers, so nothing is copied here.
    val parts = js("[]")
    for (p in pieces) {
        val src = p.source
        if (src is ArrayByteSource) {
            val data: dynamic = src.data
            parts.push(data.subarray(p.start.toDouble(), (p.start + p.length).toDouble()))
        } else {
            val chunk = ByteArray(p.length.toInt())
            src.read(p.start, chunk, 0, chunk.size)
            parts.push(chunk)
        }
    }
    download(suggestedName, parts)
    return SavedFile(suggestedName, null, null)
}

@Suppress("UNUSED_PARAMETER")
private fun pickFile(onDone: (String?, dynamic) -> Unit) {
    js(
        """
        var input = document.createElement('input');
        input.type = 'file';
        input.onchange = function () {
            var f = input.files[0];
            if (!f) { onDone(null, null); return; }
            f.arrayBuffer().then(function (buf) { onDone(f.name, new Int8Array(buf)); });
        };
        input.oncancel = function () { onDone(null, null); };
        input.click();
        """
    )
}

@Suppress("UNUSED_PARAMETER")
private fun download(name: String, parts: dynamic) {
    js(
        """
        var blob = new Blob(parts, { type: 'application/octet-stream' });
        var a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = name;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
        """
    )
}

package com.siprikorea.hexeditor.platform

import com.siprikorea.hexeditor.core.ArrayByteSource
import com.siprikorea.hexeditor.core.Piece
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

actual suspend fun openFileDialog(): PickedFile? = suspendCoroutine { cont ->
    pickFile { name, bytes ->
        // Kotlin/JS represents ByteArray as Int8Array, so the file buffer is used without copying.
        cont.resume(if (name == null) null else PickedFile(name, ArrayByteSource(bytes.unsafeCast<ByteArray>()), null))
    }
}

actual fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onDrop: (PickedFile) -> Unit): Modifier = composed {
    val hover by rememberUpdatedState(onHover)
    val drop by rememberUpdatedState(onDrop)
    DisposableEffect(Unit) {
        val dispose = installDrop({ hover(it) }) { name, bytes ->
            drop(PickedFile(name, ArrayByteSource(bytes.unsafeCast<ByteArray>()), null))
        }
        onDispose { dispose() }
    }
    this
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
        // Attached to the document: a detached input can be garbage-collected while the dialog
        // is open, and then its change event never fires.
        var input = document.createElement('input');
        input.type = 'file';
        input.style.display = 'none';
        document.body.appendChild(input);
        var finish = function (name, bytes) { input.remove(); onDone(name, bytes); };
        input.onchange = function () {
            var f = input.files[0];
            if (!f) { finish(null, null); return; }
            f.arrayBuffer().then(function (buf) { finish(f.name, new Int8Array(buf)); },
                                 function () { finish(null, null); });
        };
        input.oncancel = function () { finish(null, null); };
        input.click();
        """
    )
}

/** Listens for files dropped on the window; returns a function that removes the listeners. */
@Suppress("UNUSED_PARAMETER")
private fun installDrop(onHover: (Boolean) -> Unit, onDone: (String, dynamic) -> Unit): () -> Unit = js(
    """
    (function () {
        var depth = 0;
        var hasFiles = function (e) { return e.dataTransfer && Array.from(e.dataTransfer.types).indexOf('Files') >= 0; };
        var enter = function (e) { if (!hasFiles(e)) return; e.preventDefault(); if (depth++ === 0) onHover(true); };
        var over = function (e) { if (!hasFiles(e)) return; e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; };
        var leave = function (e) { if (!hasFiles(e)) return; if (--depth <= 0) { depth = 0; onHover(false); } };
        var drop = function (e) {
            if (!hasFiles(e)) return;
            e.preventDefault();
            depth = 0;
            onHover(false);
            var f = e.dataTransfer.files[0];
            if (f) f.arrayBuffer().then(function (buf) { onDone(f.name, new Int8Array(buf)); });
        };
        window.addEventListener('dragenter', enter);
        window.addEventListener('dragover', over);
        window.addEventListener('dragleave', leave);
        window.addEventListener('drop', drop);
        return function () {
            window.removeEventListener('dragenter', enter);
            window.removeEventListener('dragover', over);
            window.removeEventListener('dragleave', leave);
            window.removeEventListener('drop', drop);
        };
    })()
    """
)

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

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.siprikorea.hexeditor.platform

import com.siprikorea.hexeditor.core.ByteSource
import com.siprikorea.hexeditor.core.Piece
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// Kotlin/Wasm can't share memory with JS typed arrays, so opened files stay on the JS side
// (no copy into the Wasm heap) and are read on demand in chunks. Chunks cross the boundary as
// Latin-1 strings (one char per byte), which is far cheaper than one interop call per byte.

/** A file buffer held in JS, identified by [id]. */
private class JsByteSource(val id: Int, override val size: Long) : ByteSource {
    override fun read(position: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        val s = jsReadLatin1(id, position.toDouble(), length)
        for (i in 0 until length) dst[dstOffset + i] = s[i].code.toByte()
    }

    override fun close() = jsRelease(id)
}

actual suspend fun openFileDialog(): PickedFile? = suspendCoroutine { cont ->
    jsInstallRegistry()
    jsPickFile { name, id, size ->
        cont.resume(if (name == null) null else PickedFile(name, JsByteSource(id, size.toLong()), null))
    }
}

actual fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onDrop: (PickedFile) -> Unit): Modifier = composed {
    val hover by rememberUpdatedState(onHover)
    val drop by rememberUpdatedState(onDrop)
    DisposableEffect(Unit) {
        jsInstallRegistry()
        jsInstallDrop({ hover(it) }) { name, id, size -> drop(PickedFile(name, JsByteSource(id, size.toLong()), null)) }
        onDispose { jsUninstallDrop() }
    }
    this
}

actual suspend fun saveFile(pieces: List<Piece>, suggestedName: String, path: String?): SavedFile? {
    jsPartsBegin()
    val buf = ByteArray(1 shl 16)
    val chars = CharArray(buf.size)
    for (p in pieces) {
        val src = p.source
        if (src is JsByteSource) {
            // Unmodified ranges of the opened file are passed as zero-copy views.
            jsPartsAddSlice(src.id, p.start.toDouble(), (p.start + p.length).toDouble())
            continue
        }
        var done = 0L
        while (done < p.length) {
            val n = minOf(buf.size.toLong(), p.length - done).toInt()
            src.read(p.start + done, buf, 0, n)
            for (i in 0 until n) chars[i] = (buf[i].toInt() and 0xFF).toChar()
            jsPartsAddLatin1(chars.concatToString(0, n))
            done += n
        }
    }
    jsPartsDownload(suggestedName)
    return SavedFile(suggestedName, null, null)
}

// The input must be attached to the document: a detached one can be garbage-collected while the
// dialog is open, and then its change event never fires.
private fun jsPickFile(onDone: (String?, Int, Double) -> Unit): Unit = js(
    """{
    const input = document.createElement('input');
    input.type = 'file';
    input.style.display = 'none';
    document.body.appendChild(input);
    const finish = (name, id, size) => { input.remove(); onDone(name, id, size); };
    input.onchange = () => {
        const f = input.files[0];
        if (!f) { finish(null, 0, 0); return; }
        f.arrayBuffer().then(buf => finish(f.name, globalThis.__hexRegister(buf), buf.byteLength),
                             () => finish(null, 0, 0));
    };
    input.oncancel = () => finish(null, 0, 0);
    input.click();
}"""
)

/** Registers dropped files' buffers on the JS side, like [jsPickFile]. */
private fun jsInstallDrop(onHover: (Boolean) -> Unit, onDone: (String, Int, Double) -> Unit): Unit = js(
    """{
    const d = globalThis.__hexDrop = { depth: 0 };
    const hasFiles = e => e.dataTransfer && Array.from(e.dataTransfer.types).includes('Files');
    d.enter = e => { if (!hasFiles(e)) return; e.preventDefault(); if (d.depth++ === 0) onHover(true); };
    d.over = e => { if (!hasFiles(e)) return; e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; };
    d.leave = e => { if (!hasFiles(e)) return; if (--d.depth <= 0) { d.depth = 0; onHover(false); } };
    d.drop = e => {
        if (!hasFiles(e)) return;
        e.preventDefault();
        d.depth = 0;
        onHover(false);
        const f = e.dataTransfer.files[0];
        if (f) f.arrayBuffer().then(buf => onDone(f.name, globalThis.__hexRegister(buf), buf.byteLength));
    };
    window.addEventListener('dragenter', d.enter);
    window.addEventListener('dragover', d.over);
    window.addEventListener('dragleave', d.leave);
    window.addEventListener('drop', d.drop);
}"""
)

private fun jsUninstallDrop(): Unit = js(
    """{
    const d = globalThis.__hexDrop;
    if (!d) return;
    window.removeEventListener('dragenter', d.enter);
    window.removeEventListener('dragover', d.over);
    window.removeEventListener('dragleave', d.leave);
    window.removeEventListener('drop', d.drop);
    globalThis.__hexDrop = null;
}"""
)

private fun jsInstallRegistry(): Unit = js(
    """{
    if (globalThis.__hexRegister) return;
    globalThis.__hexFiles = { next: 1, map: new Map() };
    globalThis.__hexRegister = buf => {
        const reg = globalThis.__hexFiles;
        const id = reg.next++;
        reg.map.set(id, new Uint8Array(buf));
        return id;
    };
}"""
)

private fun jsReadLatin1(id: Int, pos: Double, len: Int): String = js(
    """{
    const a = globalThis.__hexFiles.map.get(id);
    let s = '';
    for (let i = 0; i < len; i += 8192) {
        s += String.fromCharCode.apply(null, a.subarray(pos + i, pos + Math.min(len, i + 8192)));
    }
    return s;
}"""
)

private fun jsRelease(id: Int): Unit = js("{ globalThis.__hexFiles && globalThis.__hexFiles.map.delete(id); }")

private fun jsPartsBegin(): Unit = js("{ globalThis.__hexParts = []; }")

private fun jsPartsAddSlice(id: Int, start: Double, end: Double): Unit =
    js("{ globalThis.__hexParts.push(globalThis.__hexFiles.map.get(id).subarray(start, end)); }")

private fun jsPartsAddLatin1(s: String): Unit = js(
    """{
    const a = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) a[i] = s.charCodeAt(i);
    globalThis.__hexParts.push(a);
}"""
)

private fun jsPartsDownload(name: String): Unit = js(
    """{
    const blob = new Blob(globalThis.__hexParts, { type: 'application/octet-stream' });
    globalThis.__hexParts = null;
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}"""
)

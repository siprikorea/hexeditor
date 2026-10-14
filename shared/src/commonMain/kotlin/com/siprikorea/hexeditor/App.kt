package com.siprikorea.hexeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.siprikorea.hexeditor.core.toHex
import com.siprikorea.hexeditor.platform.PickedFile
import com.siprikorea.hexeditor.platform.openFileDialog
import com.siprikorea.hexeditor.platform.saveBytes
import com.siprikorea.hexeditor.platform.saveFile
import com.siprikorea.hexeditor.core.readAll
import com.siprikorea.hexeditor.ui.formatSize
import kotlinx.coroutines.Job
import com.siprikorea.hexeditor.ui.EditorState
import com.siprikorea.hexeditor.ui.HexColors
import com.siprikorea.hexeditor.ui.HexTheme
import com.siprikorea.hexeditor.ui.HexView
import com.siprikorea.hexeditor.ui.InputField
import com.siprikorea.hexeditor.ui.InspectorPanel
import com.siprikorea.hexeditor.ui.MonoStyle
import com.siprikorea.hexeditor.ui.Splitter
import com.siprikorea.hexeditor.ui.TemplateEditor
import com.siprikorea.hexeditor.ui.TemplateResults
import com.siprikorea.hexeditor.ui.ToolButton
import com.siprikorea.hexeditor.ui.UiStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hex editor with an 010 Editor-style binary template engine.
 *
 * @param initialFile file to open at start-up (e.g. from the command line).
 * @param onTitleChange lets the host window show the file name and modified state.
 */
@Composable
fun App(initialFile: PickedFile? = null, onTitleChange: (String) -> Unit = {}) {
    val state = remember { EditorState().apply { initialFile?.let(::load) } }
    val scope = rememberCoroutineScope()
    val hexFocus = remember { FocusRequester() }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current

    var gotoText by remember { mutableStateOf("") }
    var findText by remember { mutableStateOf("") }
    var findHex by remember { mutableStateOf(false) }
    var rightWidth by remember { mutableStateOf(300.dp) }
    var bottomHeight by remember { mutableStateOf(320.dp) }
    var editorWidth by remember { mutableStateOf(520.dp) }

    LaunchedEffect(state.title) { onTitleChange("${state.title} - Hex Editor") }

    // Re-run the template shortly after the data or the template changes.
    // At most one template run and one search at a time; starting another cancels the previous.
    var templateJob by remember { mutableStateOf<Job?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun runTemplate() {
        templateJob?.cancel()
        templateJob = scope.launch { state.runTemplate() }
    }

    fun find(forward: Boolean) {
        searchJob?.cancel()
        searchJob = scope.launch { state.find(findText, findHex, forward) }
    }

    // Re-run the template shortly after the data or the template changes.
    LaunchedEffect(state.version, state.templateSource, state.autoRun) {
        if (!state.autoRun) return@LaunchedEffect
        // An empty file can't match any template; don't greet the user with a parse error.
        if (state.size == 0L) {
            templateJob?.cancel()
            state.clearTemplateResult()
            return@LaunchedEffect
        }
        delay(300)
        runTemplate()
    }

    fun open() = scope.launch {
        openFileDialog()?.let(state::load)
        hexFocus.requestFocus()
    }

    fun save(saveAs: Boolean) = scope.launch {
        val path = if (saveAs) null else state.filePath
        state.message = "Saving…"
        runCatching { saveFile(state.doc.snapshot().pieces, state.fileName, path) }
            .onSuccess { r -> if (r != null) state.saved(r) else state.message = "" }
            .onFailure { state.message = "Save failed: ${it.message}" }
    }

    fun copy() {
        clipboard.setText(AnnotatedString(state.selectedBytesAsHex()))
        state.message = "Copied ${maxOf(1, state.selectionLength)} byte(s) as hex"
    }

    fun paste() {
        val text = clipboard.getText()?.text.orEmpty()
        if (!state.pasteHex(text)) state.message = "Clipboard does not contain hex bytes"
    }

    HexTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(HexColors.background)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val ctrl = e.isCtrlPressed || e.isMetaPressed
                    when {
                        ctrl && e.key == Key.O -> open()
                        ctrl && e.key == Key.S -> save(saveAs = e.isShiftPressed)
                        ctrl && e.key == Key.N -> state.newFile()
                        e.key == Key.F5 -> runTemplate()
                        e.key == Key.F3 -> find(forward = !e.isShiftPressed)
                        e.key == Key.Escape && (searchJob?.isActive == true || templateJob?.isActive == true) -> {
                            searchJob?.cancel()
                            templateJob?.cancel()
                            state.message = "Cancelled"
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
        ) {
            // ---- toolbar
            Row(
                Modifier.fillMaxWidth().background(HexColors.panel).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton("New", { state.newFile() })
                ToolButton("Open", { open() })
                ToolButton("Save", { save(saveAs = false) })
                ToolButton("Save As", { save(saveAs = true) })
                Spacer(Modifier.width(10.dp))
                ToolButton("Undo", { state.undo() }, enabled = state.canUndo)
                ToolButton("Redo", { state.redo() }, enabled = state.canRedo)
                ToolButton(if (state.insertMode) "INS" else "OVR", { state.insertMode = !state.insertMode }, active = state.insertMode)
                Spacer(Modifier.width(10.dp))
                InputField(gotoText, { gotoText = it }, "Go to offset", { state.goTo(gotoText); hexFocus.requestFocus() }, Modifier.width(130.dp))
                Spacer(Modifier.width(10.dp))
                InputField(findText, { findText = it }, if (findHex) "Find hex: 50 4B" else "Find text", { shift -> find(forward = !shift) }, Modifier.width(180.dp))
                ToolButton("Hex", { findHex = !findHex }, active = findHex)
                ToolButton("Prev", { find(forward = false) })
                ToolButton("Next", { find(forward = true) })
            }

            // ---- hex view + inspector
            Row(Modifier.weight(1f).fillMaxWidth()) {
                HexView(state, hexFocus, ::copy, ::paste, Modifier.weight(1f).fillMaxSize())
                Splitter(vertical = true) { rightWidth = (rightWidth - it).coerceIn(180.dp, 600.dp) }
                InspectorPanel(state, Modifier.width(rightWidth).fillMaxSize())
            }

            Splitter(vertical = false) { bottomHeight = (bottomHeight - it).coerceIn(120.dp, 900.dp) }

            // ---- template editor + results
            Row(Modifier.height(bottomHeight).fillMaxWidth()) {
                TemplateEditor(
                    state,
                    onRun = { runTemplate() },
                    onOpenTemplate = {
                        scope.launch {
                            openFileDialog()?.let { f ->
                                runCatching { f.source.readAll(4 shl 20).decodeToString() }
                                    .onSuccess { state.loadTemplate(f.name, it) }
                                    .onFailure { state.message = "Cannot open template: ${it.message}" }
                                f.source.close()
                            }
                        }
                    },
                    onSaveTemplate = {
                        scope.launch {
                            val name = state.templateName.let { if (it.endsWith(".bt")) it else "$it.bt" }
                            saveBytes(state.templateSource.encodeToByteArray(), name)
                        }
                    },
                    modifier = Modifier.width(editorWidth).fillMaxSize(),
                )
                Splitter(vertical = true) { editorWidth = (editorWidth + it).coerceIn(240.dp, 1400.dp) }
                TemplateResults(state, Modifier.weight(1f).fillMaxSize())
            }

            StatusBar(state)
        }
    }
}

@Composable
private fun StatusBar(state: EditorState) {
    Row(
        Modifier.fillMaxWidth().background(HexColors.panel).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val sel = if (state.hasSelection) {
            "  Sel: 0x${state.selectionStart.toHex(state.offsetDigits)}-0x${state.selectionEnd.toHex(state.offsetDigits)} (${state.selectionLength} bytes)"
        } else ""
        Text(
            "${state.title}   Offset: 0x${state.cursor.toHex(state.offsetDigits)} (${state.cursor})$sel   Size: ${formatSize(state.doc.size)}",
            style = MonoStyle.copy(color = if (state.isModified) HexColors.modified else HexColors.text),
        )
        Box(Modifier.weight(1f))
        Text(
            (if (state.busy) "[working] " else "") + state.message,
            style = UiStyle.copy(color = HexColors.dim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
        Text(
            "   ${if (state.insertMode) "INS" else "OVR"}  ${if (state.littleEndian) "LE" else "BE"}",
            style = MonoStyle.copy(color = HexColors.dim),
        )
    }
}

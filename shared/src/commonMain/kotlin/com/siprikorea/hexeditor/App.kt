package com.siprikorea.hexeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.unit.sp
import com.siprikorea.hexeditor.core.readAll
import com.siprikorea.hexeditor.core.toHex
import com.siprikorea.hexeditor.platform.PickedFile
import com.siprikorea.hexeditor.platform.fileDropTarget
import com.siprikorea.hexeditor.platform.openFileDialog
import com.siprikorea.hexeditor.platform.saveBytes
import com.siprikorea.hexeditor.platform.saveFile
import com.siprikorea.hexeditor.ui.AppMark
import com.siprikorea.hexeditor.ui.ButtonStyle
import com.siprikorea.hexeditor.ui.Chip
import com.siprikorea.hexeditor.ui.EditorState
import com.siprikorea.hexeditor.ui.HexColors
import com.siprikorea.hexeditor.ui.HexTheme
import com.siprikorea.hexeditor.ui.HexView
import com.siprikorea.hexeditor.ui.IconButton
import com.siprikorea.hexeditor.ui.InputField
import com.siprikorea.hexeditor.ui.InspectorPanel
import com.siprikorea.hexeditor.ui.Menu
import com.siprikorea.hexeditor.ui.MonoStyle
import com.siprikorea.hexeditor.ui.MoreIcon
import com.siprikorea.hexeditor.ui.Radius
import com.siprikorea.hexeditor.ui.Segmented
import com.siprikorea.hexeditor.ui.Sheet
import com.siprikorea.hexeditor.ui.Splitter
import com.siprikorea.hexeditor.ui.TemplateEditor
import com.siprikorea.hexeditor.ui.TemplateResults
import com.siprikorea.hexeditor.ui.ToolButton
import com.siprikorea.hexeditor.ui.UiSmall
import com.siprikorea.hexeditor.ui.UiStrong
import com.siprikorea.hexeditor.ui.formatSize
import kotlinx.coroutines.Job
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
    var rightWidth by remember { mutableStateOf(380.dp) }
    var bottomHeight by remember { mutableStateOf(300.dp) }
    var showEditor by remember { mutableStateOf(false) }
    var sideTab by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var dropHover by remember { mutableStateOf(false) }
    val findFocus = remember { FocusRequester() }
    val gotoFocus = remember { FocusRequester() }

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
    LaunchedEffect(state.version, state.templateSource, state.autoRun, state.templateArmed) {
        if (!state.autoRun) return@LaunchedEffect
        // An empty file can't match any template, and an unarmed template wasn't meant for this
        // file; don't greet the user with a parse error.
        if (state.size == 0L || !state.templateArmed) {
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
        Box(
            Modifier
                .fillMaxSize()
                .background(HexColors.ground)
                .fileDropTarget(onHover = { dropHover = it }) { f ->
                    state.load(f)
                    hexFocus.requestFocus()
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val ctrl = e.isCtrlPressed || e.isMetaPressed
                    when {
                        ctrl && e.key == Key.O -> open()
                        ctrl && e.key == Key.S -> save(saveAs = e.isShiftPressed)
                        ctrl && e.key == Key.N -> state.newFile()
                        ctrl && e.key == Key.F -> findFocus.requestFocus()
                        ctrl && e.key == Key.G -> gotoFocus.requestFocus()
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
            Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                // ---- top bar: the file, search and the few actions that matter
                Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppMark()
                    Spacer(Modifier.width(10.dp))
                    Text(state.fileName, style = UiStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
                    if (state.isModified) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(7.dp).background(HexColors.modified, CircleShape))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(formatSize(state.doc.size), style = UiSmall.copy(color = HexColors.dim), maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    InputField(
                        findText, { findText = it },
                        if (findHex) "Find hex bytes" else "Find text",
                        { shift -> find(forward = !shift) },
                        Modifier.width(240.dp).focusRequester(findFocus),
                    ) {
                        Chip("HEX", findHex, { findHex = !findHex })
                    }
                    Spacer(Modifier.width(8.dp))
                    InputField(
                        gotoText, { gotoText = it }, "Go to offset",
                        { state.goTo(gotoText); hexFocus.requestFocus() },
                        Modifier.width(140.dp).focusRequester(gotoFocus),
                    )
                    Spacer(Modifier.width(12.dp))
                    ToolButton("Open", { open() }, style = ButtonStyle.PRIMARY)
                    Spacer(Modifier.width(4.dp))
                    ToolButton("Save", { save(saveAs = false) }, enabled = state.isModified || state.filePath == null)
                    Box {
                        IconButton({ menuOpen = true }) { MoreIcon(it) }
                        Menu(menuOpen, { menuOpen = false }, listOf(
                            "New" to { state.newFile() },
                            "Save As…" to { save(saveAs = true) },
                            "Undo" to { state.undo() },
                            "Redo" to { state.redo() },
                            (if (showEditor) "Hide template editor" else "Edit template") to { showEditor = !showEditor },
                        ))
                    }
                }

                // ---- hex view + side panel
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Sheet(Modifier.weight(1f).fillMaxHeight()) {
                        HexView(state, hexFocus, ::copy, ::paste, Modifier.fillMaxSize().padding(top = 4.dp))
                        if (state.size == 0L) EmptyState(::open, Modifier.align(Alignment.Center))
                    }
                    Splitter(vertical = true) { rightWidth = (rightWidth - it).coerceIn(260.dp, 720.dp) }
                    Sheet(Modifier.width(rightWidth).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                                Segmented(listOf("Structure", "Inspector"), sideTab, { sideTab = it })
                            }
                            if (sideTab == 0) {
                                TemplateResults(state, ::runTemplate, { showEditor = !showEditor }, showEditor, Modifier.weight(1f))
                            } else {
                                InspectorPanel(state, Modifier.weight(1f))
                            }
                        }
                    }
                }

                // ---- template editor, only while writing a template
                if (showEditor) {
                    Splitter(vertical = false) { bottomHeight = (bottomHeight - it).coerceIn(140.dp, 900.dp) }
                    Sheet(Modifier.height(bottomHeight).fillMaxWidth()) {
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
                            onClose = { showEditor = false },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                StatusBar(state)
            }

            if (dropHover) DropOverlay()
        }
    }
}

/** Shown in the hex sheet while there is nothing to edit. */
@Composable
private fun EmptyState(onOpen: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppMark(Modifier.size(44.dp))
        Spacer(Modifier.height(16.dp))
        Text("Drop a file here to open it", style = UiStrong.copy(fontSize = 15.sp))
        Spacer(Modifier.height(6.dp))
        Text("Nothing leaves your device. Large files open instantly.", style = UiSmall)
        Spacer(Modifier.height(16.dp))
        ToolButton("Choose file", onOpen, style = ButtonStyle.PRIMARY)
    }
}

/** Covers the window while a file is dragged over it. */
@Composable
private fun DropOverlay() {
    val shape = RoundedCornerShape(Radius.sheet)
    Box(
        Modifier.fillMaxSize().background(HexColors.ground.copy(alpha = 0.75f)).padding(16.dp)
            .border(2.dp, HexColors.accent, shape).background(HexColors.accentSubtle, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text("Drop to open", style = UiStrong.copy(fontSize = 18.sp))
    }
}

@Composable
private fun StatusBar(state: EditorState) {
    Row(
        Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val sel = if (state.hasSelection) {
            "   ${state.selectionLength} bytes selected (0x${state.selectionStart.toHex(state.offsetDigits)}–0x${state.selectionEnd.toHex(state.offsetDigits)})"
        } else ""
        Text(
            "0x${state.cursor.toHex(state.offsetDigits)}  ·  ${state.cursor}$sel",
            style = MonoStyle.copy(fontSize = UiSmall.fontSize, color = HexColors.textSecondary),
            maxLines = 1,
        )
        Text(
            (if (state.busy) "Working…  " else "") + state.message,
            style = UiSmall.copy(color = HexColors.dim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        Chip(if (state.insertMode) "INS" else "OVR", state.insertMode, { state.insertMode = !state.insertMode })
        Spacer(Modifier.width(4.dp))
        Chip(if (state.littleEndian) "LE" else "BE", false, { state.littleEndian = !state.littleEndian })
    }
}

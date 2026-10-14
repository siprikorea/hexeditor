package com.siprikorea.hexeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.siprikorea.hexeditor.core.CachedReader
import com.siprikorea.hexeditor.core.toHex
import com.siprikorea.hexeditor.template.NodeKind
import com.siprikorea.hexeditor.template.TemplateNode
import com.siprikorea.hexeditor.template.Templates
import com.siprikorea.hexeditor.template.elementNode

/** The template source editor, shown in its own sheet only while the user is writing a template. */
@Composable
fun TemplateEditor(
    state: EditorState,
    onRun: () -> Unit,
    onOpenTemplate: () -> Unit,
    onSaveTemplate: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        PanelHeader("Template · ${state.templateName}") {
            ToolButton("Open .bt", onOpenTemplate)
            ToolButton("Save .bt", onSaveTemplate)
            ToolButton("Auto-run", { state.autoRun = !state.autoRun }, active = state.autoRun)
            Spacer(Modifier.width(4.dp))
            ToolButton("Run", onRun, style = ButtonStyle.PRIMARY)
            Spacer(Modifier.width(4.dp))
            ToolButton("Close", onClose)
        }
        CodeEditor(
            state.templateSource,
            state::editTemplate,
            errorLine = state.templateResult?.error?.line,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        val result = state.templateResult
        if (result != null && (result.error != null || result.log.isNotEmpty())) {
            SelectionContainer(Modifier.fillMaxWidth().heightIn(max = 96.dp).background(HexColors.ground)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    result.error?.let { Text("Line ${it.line}: ${it.message}", style = MonoStyle.copy(color = HexColors.error)) }
                    if (result.log.isNotEmpty()) Text(result.log.trimEnd(), style = MonoStyle)
                }
            }
        }
    }
}

@Composable
private fun CodeEditor(text: String, onChange: (String) -> Unit, errorLine: Int?, modifier: Modifier) {
    val vScroll = rememberScrollState()
    val lineCount = text.count { it == '\n' } + 1
    Row(modifier.verticalScroll(vScroll)) {
        // Line-number gutter scrolls together with the text because both live in the same scroller.
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
            for (i in 1..lineCount) {
                Text(
                    i.toString().padStart(3),
                    style = MonoStyle.copy(color = if (i == errorLine) HexColors.error else HexColors.zeroByte),
                )
            }
        }
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            BasicTextField(
                value = text,
                onValueChange = onChange,
                textStyle = MonoStyle,
                cursorBrush = SolidColor(HexColors.text),
                modifier = Modifier.widthIn(min = 600.dp).padding(6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- results tree

/** A tree line; rows with a null [node] are "show more" placeholders for the list at [morePath]. */
private class TreeRow(
    val node: TemplateNode?,
    val depth: Int,
    val path: String,
    val label: String = "",
    val morePath: String? = null,
)

/** Children are listed in pages so expanding a million-element array stays instant. */
private const val PAGE = 10_000

private fun flatten(root: TemplateNode, expanded: Set<String>, limits: Map<String, Int>, reader: CachedReader): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    fun more(listPath: String, depth: Int, shown: Int, total: Int) {
        if (total > shown) {
            out += TreeRow(
                null, depth, "$listPath…",
                "${total - shown} more · show ${minOf(PAGE, total - shown)}",
                morePath = listPath,
            )
        }
    }
    fun add(n: TemplateNode, depth: Int, path: String) {
        out += TreeRow(n, depth, path)
        if (path !in expanded) return
        val shown = minOf(n.childCount, limits[path] ?: PAGE)
        if (n.elementType != null) {
            for (i in 0 until shown) out += TreeRow(elementNode(n, i, reader), depth + 1, "$path[$i]")
        } else {
            for (i in 0 until shown) {
                val c = n.children[i]
                add(c, depth + 1, "$path/${c.name}")
            }
        }
        more(path, depth + 1, shown, n.childCount)
    }
    val top = minOf(root.children.size, limits[""] ?: PAGE)
    for (i in 0 until top) add(root.children[i], 0, root.children[i].name)
    more("", 0, top, root.children.size)
    return out
}

@Composable
fun TemplateResults(state: EditorState, onRun: () -> Unit, onEdit: () -> Unit, editing: Boolean, modifier: Modifier = Modifier) {
    val result = state.templateResult
    val expanded = remember { mutableStateOf(setOf<String>()) }
    val limits = remember { mutableStateOf(mapOf<String, Int>()) }
    val reader = remember(result) { result?.let { CachedReader(it.data) } }
    val rows = remember(result, expanded.value, limits.value) {
        if (result == null || reader == null) emptyList() else flatten(result.root, expanded.value, limits.value, reader)
    }
    val cursorNode = remember(result, state.cursor, state.version) { state.nodeAt(state.cursor) }
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Template picker: the current template's name opens the list of built-in ones.
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(Radius.control)).clickable { menuOpen = true }
                        .height(28.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(state.templateName, style = UiStrong, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Chevron(expanded = true)
                }
                Menu(menuOpen, { menuOpen = false }, Templates.all.map { t ->
                    val ext = if (t.extensions.isEmpty()) "" else "   .${t.extensions.joinToString(" .")}"
                    (t.name + ext) to { state.loadTemplate(t.name, t.source) }
                })
            }
            Spacer(Modifier.weight(1f))
            ToolButton("Edit", onEdit, active = editing)
            ToolButton("Run", onRun)
        }
        // One status line: what the last run produced.
        val status = when {
            result == null && state.size == 0L -> "" to HexColors.dim
            result == null && !state.templateArmed -> "No template for this file type. Pick one above." to HexColors.dim
            result == null -> "Not run yet" to HexColors.dim
            result.error != null -> "Error on line ${result.error.line}: ${result.error.message}" to HexColors.error
            else -> "${state.highlights.size} fields" to HexColors.dim
        }
        Text(
            status.first, style = UiSmall.copy(color = status.second), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 6.dp)
                .clickable(enabled = result?.error != null && !editing, onClick = onEdit),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
            Text("Name", style = UiSmall.copy(color = HexColors.dim), modifier = Modifier.weight(0.5f))
            Text("Value", style = UiSmall.copy(color = HexColors.dim), modifier = Modifier.weight(0.5f))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val empty = when {
                result != null -> "The template produced no fields."
                state.size == 0L -> "Open a file to see its structure."
                else -> null
            }
            if (rows.isEmpty() && empty != null) {
                Text(
                    empty,
                    style = UiStyle.copy(color = HexColors.dim),
                    modifier = Modifier.padding(14.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 6.dp), state = listState) {
                itemsIndexed(rows, key = { _, r -> r.path }) { _, row ->
                    val node = row.node
                    if (node == null) {
                        val listPath = row.morePath
                        Text(
                            row.label,
                            style = UiSmall.copy(color = HexColors.offset),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = listPath != null) {
                                    if (listPath != null) {
                                        limits.value += listPath to (limits.value[listPath] ?: PAGE) + PAGE
                                    }
                                }
                                .padding(start = (24 + row.depth * 14).dp, top = 4.dp, bottom = 4.dp),
                        )
                        return@itemsIndexed
                    }
                    val selected = state.selectedNode?.let { it.offset == node.offset && it.name == node.name } == true
                    val underCursor = cursorNode?.let { it === node || (it.offset == node.offset && it.name == node.name) } == true
                    val expandable = node.childCount > 0
                    val isOpen = row.path in expanded.value
                    fun toggle() {
                        expanded.value = if (isOpen) expanded.value - row.path else expanded.value + row.path
                    }
                    val shape = RoundedCornerShape(Radius.control)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .clip(shape)
                            .background(
                                when {
                                    selected -> HexColors.treeSelected
                                    underCursor -> HexColors.treeCursor
                                    else -> HexColors.background
                                },
                                shape,
                            )
                            .clickable { state.selectNode(node) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(Modifier.weight(0.5f), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width((row.depth * 14).dp))
                            Box(
                                Modifier.width(16.dp).clickable(enabled = expandable, onClick = ::toggle),
                                contentAlignment = Alignment.Center,
                            ) { if (expandable) Chevron(isOpen) }
                            Spacer(Modifier.width(4.dp))
                            Cell(node.name, Modifier.weight(1f), color = if (node.kind == NodeKind.STRUCT) HexColors.offset else HexColors.text)
                        }
                        Cell(node.display, Modifier.weight(0.5f), HexColors.textSecondary)
                    }
                }
            }
        }
        state.selectedNode?.let { NodeDetails(it) }
    }
}

/** Type, position and comment of the selected field, which the two-column tree leaves out. */
@Composable
private fun NodeDetails(node: TemplateNode) {
    Column(Modifier.fillMaxWidth().background(HexColors.ground.copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        val type = if (node.isBitfield) "${node.typeName} : ${node.bitWidth}" else node.typeName
        Text("${node.name}  ·  $type", style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val start = "0x" + node.offset.toHex(8).trimStart('0').padStart(2, '0')
        val where = if (node.isBitfield) "$start bit ${node.bitOffset}, ${node.bitWidth} bit(s)" else "$start, ${node.size} byte(s)"
        Text(where, style = UiSmall, maxLines = 1)
        node.comment?.takeIf { it.isNotEmpty() }?.let { Text(it, style = UiSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Cell(text: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color = HexColors.text) {
    Text(
        text,
        style = MonoStyle.copy(color = color),
        modifier = modifier.padding(end = 6.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

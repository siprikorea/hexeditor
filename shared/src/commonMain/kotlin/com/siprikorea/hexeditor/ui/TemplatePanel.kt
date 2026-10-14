package com.siprikorea.hexeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.siprikorea.hexeditor.core.CachedReader
import com.siprikorea.hexeditor.core.toHex
import com.siprikorea.hexeditor.template.NodeKind
import com.siprikorea.hexeditor.template.TemplateNode
import com.siprikorea.hexeditor.template.Templates
import com.siprikorea.hexeditor.template.elementNode

@Composable
fun TemplateEditor(
    state: EditorState,
    onRun: () -> Unit,
    onOpenTemplate: () -> Unit,
    onSaveTemplate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier.background(HexColors.panelAlt)) {
        PanelHeader("Template: ${state.templateName}") {
            Box {
                ToolButton("Built-in ▾", { menuOpen = true })
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    for (t in Templates.all) {
                        DropdownMenuItem(
                            text = { Text(t.name + if (t.extensions.isEmpty()) "" else "  (.${t.extensions.joinToString(", .")})") },
                            onClick = {
                                menuOpen = false
                                state.loadTemplate(t.name, t.source)
                            },
                        )
                    }
                }
            }
            ToolButton("Open .bt", onOpenTemplate)
            ToolButton("Save .bt", onSaveTemplate)
            ToolButton("Auto", { state.autoRun = !state.autoRun }, active = state.autoRun)
            ToolButton("▶ Run", onRun)
        }
        CodeEditor(
            state.templateSource,
            { state.templateSource = it },
            errorLine = state.templateResult?.error?.line,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        TemplateOutput(state, Modifier.fillMaxWidth().height(72.dp))
    }
}

@Composable
private fun CodeEditor(text: String, onChange: (String) -> Unit, errorLine: Int?, modifier: Modifier) {
    val vScroll = rememberScrollState()
    val lineCount = text.count { it == '\n' } + 1
    Row(modifier.background(HexColors.background).verticalScroll(vScroll)) {
        // Line-number gutter scrolls together with the text because both live in the same scroller.
        Column(Modifier.background(HexColors.panelAlt).padding(horizontal = 6.dp, vertical = 6.dp)) {
            for (i in 1..lineCount) {
                Text(
                    i.toString().padStart(3),
                    style = MonoStyle.copy(color = if (i == errorLine) HexColors.error else HexColors.dim),
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

@Composable
private fun TemplateOutput(state: EditorState, modifier: Modifier) {
    val result = state.templateResult
    SelectionContainer(modifier.background(HexColors.panel)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(6.dp)) {
            when {
                result == null -> Text("Run a template to parse the file into structures.", style = UiStyle.copy(color = HexColors.dim))
                result.error != null -> Text(
                    "Line ${result.error.line}: ${result.error.message}",
                    style = MonoStyle.copy(color = HexColors.error),
                )
                else -> {
                    val count = remember(result) { countNodes(result.root) }
                    Text("✓ Parsed $count variables", style = MonoStyle.copy(color = HexColors.ok))
                }
            }
            if (result != null && result.log.isNotEmpty()) Text(result.log.trimEnd(), style = MonoStyle)
        }
    }
}

private fun countNodes(n: TemplateNode): Long = n.children.sumOf { 1L + countNodes(it) }

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
                "… ${total - shown} more — click to show ${minOf(PAGE, total - shown)} more",
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
fun TemplateResults(state: EditorState, modifier: Modifier = Modifier) {
    val result = state.templateResult
    val expanded = remember { mutableStateOf(setOf<String>()) }
    val limits = remember { mutableStateOf(mapOf<String, Int>()) }
    val reader = remember(result) { result?.let { CachedReader(it.data) } }
    val rows = remember(result, expanded.value, limits.value) {
        if (result == null || reader == null) emptyList() else flatten(result.root, expanded.value, limits.value, reader)
    }
    val cursorNode = remember(result, state.cursor, state.version) { state.nodeAt(state.cursor) }
    val listState = rememberLazyListState()

    Column(modifier.background(HexColors.panelAlt)) {
        PanelHeader("Template Results") {
            ToolButton("Collapse all", { expanded.value = emptySet() })
            ToolButton("Expand top", {
                expanded.value = result?.root?.children?.map { it.name }?.toSet() ?: emptySet()
            })
        }
        Row(Modifier.fillMaxWidth().background(HexColors.panel).padding(horizontal = 6.dp, vertical = 3.dp)) {
            HeaderCell("Name", Modifier.weight(0.28f))
            HeaderCell("Value", Modifier.weight(0.32f))
            HeaderCell("Start", Modifier.width(84.dp))
            HeaderCell("Size", Modifier.width(64.dp))
            HeaderCell("Type", Modifier.weight(0.18f))
            HeaderCell("Comment", Modifier.weight(0.22f))
        }
        if (rows.isEmpty()) {
            Text(
                if (result == null) "No results yet." else "Template produced no variables.",
                style = UiStyle.copy(color = HexColors.dim),
                modifier = Modifier.padding(8.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(rows, key = { _, r -> r.path }) { _, row ->
                val node = row.node
                if (node == null) {
                    val listPath = row.morePath
                    Text(
                        row.label,
                        style = MonoStyle.copy(color = HexColors.offset),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = listPath != null) {
                                if (listPath != null) {
                                    limits.value += listPath to (limits.value[listPath] ?: PAGE) + PAGE
                                }
                            }
                            .padding(start = (8 + row.depth * 14).dp, top = 2.dp, bottom = 2.dp),
                    )
                    return@itemsIndexed
                }
                val selected = state.selectedNode?.let { it.offset == node.offset && it.name == node.name } == true
                val underCursor = cursorNode?.let { it === node || (it.offset == node.offset && it.name == node.name) } == true
                val expandable = node.childCount > 0
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                selected -> HexColors.treeSelected
                                underCursor -> HexColors.treeCursor
                                else -> HexColors.panelAlt
                            }
                        )
                        .clickable { state.selectNode(node) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(Modifier.weight(0.28f)) {
                        Box(Modifier.width((row.depth * 14).dp))
                        Text(
                            if (!expandable) "  " else if (row.path in expanded.value) "▾ " else "▸ ",
                            style = MonoStyle.copy(color = HexColors.dim),
                            modifier = Modifier.clickable(enabled = expandable) {
                                expanded.value = if (row.path in expanded.value) expanded.value - row.path else expanded.value + row.path
                            },
                        )
                        Cell(node.name, Modifier.weight(1f), color = if (node.kind == NodeKind.STRUCT) HexColors.offset else HexColors.text)
                    }
                    Cell(node.display, Modifier.weight(0.32f))
                    val start = "0x" + node.offset.toHex(8).trimStart('0').padStart(2, '0')
                    Cell(if (node.isBitfield) "$start:${node.bitOffset}" else start, Modifier.width(84.dp), HexColors.dim)
                    Cell(if (node.isBitfield) "${node.bitWidth} bit" else node.size.toString(), Modifier.width(64.dp), HexColors.dim)
                    Cell(if (node.isBitfield) "${node.typeName} : ${node.bitWidth}" else node.typeName, Modifier.weight(0.18f), HexColors.dim)
                    Cell(node.comment.orEmpty(), Modifier.weight(0.22f), HexColors.dim)
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text, style = UiStyle.copy(color = HexColors.dim), modifier = modifier, maxLines = 1)
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

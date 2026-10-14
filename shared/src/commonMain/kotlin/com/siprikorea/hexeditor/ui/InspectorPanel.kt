package com.siprikorea.hexeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.siprikorea.hexeditor.core.inspect
import com.siprikorea.hexeditor.core.toHex

@Composable
fun InspectorPanel(state: EditorState, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val v = state.version
    val rows = inspect(state.doc, state.cursor, state.littleEndian)
    Column(modifier.background(HexColors.panelAlt)) {
        PanelHeader("Inspector  @ 0x${state.cursor.toHex(state.offsetDigits)}") {
            ToolButton("LE", { state.littleEndian = true }, active = state.littleEndian)
            ToolButton("BE", { state.littleEndian = false }, active = !state.littleEndian)
        }
        SelectionContainer {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                if (rows.isEmpty()) {
                    Text("(end of file)", style = UiStyle.copy(color = HexColors.dim), modifier = Modifier.padding(8.dp))
                }
                for (r in rows) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(r.type, style = MonoStyle.copy(color = HexColors.dim), modifier = Modifier.width(80.dp))
                        Text(r.value, style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

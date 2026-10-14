package com.siprikorea.hexeditor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.siprikorea.hexeditor.core.inspect
import com.siprikorea.hexeditor.core.toHex

/** The value under the cursor read as every primitive type. */
@Composable
fun InspectorPanel(state: EditorState, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val v = state.version
    val rows = inspect(state.doc, state.cursor, state.littleEndian)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("at 0x${state.cursor.toHex(state.offsetDigits)}", style = MonoStyle.copy(color = HexColors.textSecondary))
            Spacer(Modifier.weight(1f))
            Segmented(listOf("LE", "BE"), if (state.littleEndian) 0 else 1, { state.littleEndian = it == 0 })
        }
        SelectionContainer {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                if (rows.isEmpty()) {
                    Text("End of file", style = UiStyle.copy(color = HexColors.dim), modifier = Modifier.padding(12.dp))
                }
                for (r in rows) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
                        Text(r.type, style = UiSmall.copy(color = HexColors.dim), modifier = Modifier.width(76.dp))
                        Text(r.value, style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

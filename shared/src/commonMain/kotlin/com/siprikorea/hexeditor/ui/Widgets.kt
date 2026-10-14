package com.siprikorea.hexeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val Shape = RoundedCornerShape(4.dp)

@Composable
fun ToolButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .padding(horizontal = 2.dp)
            .background(if (active) HexColors.accent else HexColors.panelAlt, Shape)
            .border(1.dp, HexColors.border, Shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = UiStyle.copy(color = if (enabled) HexColors.text else HexColors.dim))
    }
}

/** Compact single-line input; Enter calls [onSubmit] (with shift state). */
@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSubmit: (shift: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MonoStyle,
        cursorBrush = SolidColor(HexColors.text),
        modifier = modifier
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                    onSubmit(it.isShiftPressed)
                    true
                } else false
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .background(HexColors.background, Shape)
                    .border(1.dp, HexColors.border, Shape)
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                if (value.isEmpty()) Text(placeholder, style = MonoStyle.copy(color = HexColors.dim))
                inner()
            }
        },
    )
}

@Composable
fun PanelHeader(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .background(HexColors.panel)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = UiStyle.copy(color = HexColors.dim), modifier = Modifier.weight(1f))
        content()
    }
}

/** A thin draggable divider; [onDrag] receives the delta in dp. */
@Composable
fun Splitter(vertical: Boolean, onDrag: (Dp) -> Unit) {
    val density = LocalDensity.current
    val state = rememberDraggableState { px -> onDrag(with(density) { px.toDp() }) }
    Box(
        (if (vertical) Modifier.width(5.dp).fillMaxHeight() else Modifier.height(5.dp).fillMaxWidth())
            .background(HexColors.border)
            .draggable(state, if (vertical) Orientation.Horizontal else Orientation.Vertical)
    )
}

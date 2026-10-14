package com.siprikorea.hexeditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

private val ControlShape = RoundedCornerShape(Radius.control)

/**
 * A rounded, bordered sheet on the window's ground — every panel is shown in one. The content is
 * clipped to the shape and the border drawn over it, so a child that paints to its edges (the hex
 * grid, a list) is cut to the same corner instead of squaring it off.
 */
@Composable
fun Sheet(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(Radius.sheet)
    Box(modifier.clip(shape).background(HexColors.background, shape)) {
        content()
        Box(Modifier.matchParentSize().border(1.dp, HexColors.border, shape))
    }
}

enum class ButtonStyle { GHOST, PRIMARY, SUBTLE }

/** A 28dp text button. Ghost buttons only show a background on hover or while [active]. */
@Composable
fun ToolButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    style: ButtonStyle = ButtonStyle.GHOST,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        style == ButtonStyle.PRIMARY -> if (hovered && enabled) HexColors.accentHover else HexColors.accent
        active -> HexColors.accentSubtle
        hovered && enabled -> HexColors.hover
        style == ButtonStyle.SUBTLE -> HexColors.panel
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> HexColors.dim
        style == ButtonStyle.PRIMARY -> Color.White
        active -> HexColors.text
        else -> HexColors.textSecondary.takeUnless { hovered } ?: HexColors.text
    }
    Box(
        modifier
            .height(28.dp)
            .clip(ControlShape)
            .background(bg, ControlShape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = UiStyle.copy(color = fg), maxLines = 1)
    }
}

/** A square ghost button holding a drawn icon. */
@Composable
fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, icon: @Composable (Color) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .size(28.dp)
            .clip(ControlShape)
            .background(if (hovered) HexColors.hover else Color.Transparent, ControlShape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon(if (hovered) HexColors.text else HexColors.textSecondary)
    }
}

/** Tabs drawn as a pill-shaped segmented control. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(ControlShape).background(HexColors.ground, ControlShape).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val shape = RoundedCornerShape(Radius.control - 2.dp)
            Box(
                Modifier
                    .height(24.dp)
                    .clip(shape)
                    .background(if (on) HexColors.panel else Color.Transparent, shape)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = UiStyle.copy(color = if (on) HexColors.text else HexColors.dim))
            }
        }
    }
}

/** Single-line input; Enter calls [onSubmit] (with shift state). [trailing] sits inside the field. */
@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSubmit: (shift: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MonoStyle,
        cursorBrush = SolidColor(HexColors.text),
        interactionSource = interaction,
        modifier = modifier
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                    onSubmit(it.isShiftPressed)
                    true
                } else false
            },
        decorationBox = { inner ->
            Row(
                Modifier
                    .height(28.dp)
                    .background(HexColors.ground, ControlShape)
                    .border(1.dp, if (focused) HexColors.accent else HexColors.border, ControlShape)
                    .padding(start = 10.dp, end = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = UiStyle.copy(color = HexColors.dim), maxLines = 1)
                    inner()
                }
                trailing()
            }
        },
    )
}

/** A small toggle chip, used inside fields and the status bar. */
@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .height(20.dp)
            .clip(shape)
            .background(if (on) HexColors.accentSubtle else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MonoStyle.copy(fontSize = UiSmall.fontSize, color = if (on) HexColors.text else HexColors.dim))
    }
}

/** The title row at the top of a sheet. */
@Composable
fun PanelHeader(title: String, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (title.isNotEmpty()) Text(title, style = UiStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.weight(1f))
        content()
    }
}

/** A popup menu styled like the sheets. */
@Composable
fun Menu(expanded: Boolean, onDismiss: () -> Unit, items: List<Pair<String, () -> Unit>>) {
    DropdownMenu(
        expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(0.dp, 4.dp),
        shape = RoundedCornerShape(Radius.menu),
        containerColor = HexColors.overlay,
        border = androidx.compose.foundation.BorderStroke(1.dp, HexColors.borderStrong),
    ) {
        for ((label, action) in items) {
            DropdownMenuItem(
                text = { Text(label, style = UiStyle) },
                onClick = { onDismiss(); action() },
                modifier = Modifier.height(32.dp),
            )
        }
    }
}

/** A chevron pointing down (or right when [expanded] is false). */
@Composable
fun Chevron(expanded: Boolean, color: Color = HexColors.dim, modifier: Modifier = Modifier) {
    Canvas(modifier.size(12.dp)) {
        val w = size.width
        val p = Path()
        if (expanded) {
            p.moveTo(w * 0.25f, w * 0.38f); p.lineTo(w * 0.5f, w * 0.63f); p.lineTo(w * 0.75f, w * 0.38f)
        } else {
            p.moveTo(w * 0.38f, w * 0.25f); p.lineTo(w * 0.63f, w * 0.5f); p.lineTo(w * 0.38f, w * 0.75f)
        }
        drawPath(p, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Three horizontal dots. */
@Composable
fun MoreIcon(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val r = 1.5.dp.toPx()
        for (i in -1..1) drawCircle(color, r, Offset(size.width / 2 + i * 5.dp.toPx(), size.height / 2))
    }
}

/** The app's mark: a few byte cells with one under the cursor. Also the basis of the app icon. */
@Composable
fun AppMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val cell = size.width / 4.4f
        val gap = (size.width - cell * 3) / 4
        val r = androidx.compose.ui.geometry.CornerRadius(cell * 0.28f)
        for (row in 0 until 3) for (col in 0 until 3) {
            val color = when {
                row == 1 && col == 1 -> HexColors.cursor
                row == 0 -> HexColors.accent
                else -> HexColors.borderStrong
            }
            drawRoundRect(
                color,
                Offset(gap + col * (cell + gap), gap + row * (cell + gap)),
                androidx.compose.ui.geometry.Size(cell, cell),
                r,
            )
        }
    }
}

/** An invisible gap between sheets that can be dragged to resize them; [onDrag] gets the delta in dp. */
@Composable
fun Splitter(vertical: Boolean, onDrag: (Dp) -> Unit) {
    val density = LocalDensity.current
    val state = rememberDraggableState { px -> onDrag(with(density) { px.toDp() }) }
    Box(
        (if (vertical) Modifier.width(8.dp).fillMaxHeight() else Modifier.height(8.dp).fillMaxWidth())
            .draggable(state, if (vertical) Orientation.Horizontal else Orientation.Vertical)
    )
}

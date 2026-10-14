package com.siprikorea.hexeditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.siprikorea.hexeditor.core.isPrintableAscii
import com.siprikorea.hexeditor.core.toHex
import kotlin.math.floor

/** Pre-measured glyphs: drawing cached layouts is much cheaper than measuring text per frame. */
private class Glyphs(measurer: TextMeasurer, style: TextStyle) {
    val hexByte = Array(256) { measurer.measure(it.toHex(2), style) }
    val ascii = Array(256) { measurer.measure(if (isPrintableAscii(it)) it.toChar().toString() else ".", style) }
    val digit = Array(16) { measurer.measure(it.toHex(1), style) }
    val charW = measurer.measure("0", style).size.width.toFloat()
    val rowH = floor(hexByte[0].size.height * 1.3f)
}

/** Pixel layout of one row: offset column, 16 hex cells, then the ASCII column. */
/** [offsetDigits] grows beyond 8 for files over 4 GB. */
private class Geometry(val g: Glyphs, val bytesPerRow: Int, val offsetDigits: Int) {
    val charW = g.charW
    val rowH = g.rowH
    val headerH = rowH + 4f
    val pad = charW
    val hexStart = pad + (offsetDigits + 2) * charW
    val cellW = 3 * charW
    val asciiStart = hexX(bytesPerRow) + 2 * charW

    fun hexX(col: Int) = hexStart + col * cellW + if (col >= bytesPerRow / 2) charW else 0f
    fun asciiX(col: Int) = asciiStart + col * charW
    fun rowY(visibleIndex: Int) = headerH + visibleIndex * rowH
    val totalWidth get() = asciiX(bytesPerRow) + pad
}

@Composable
fun HexView(
    state: EditorState,
    focusRequester: FocusRequester,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    // Keyed on the style too: on the web the font loads asynchronously and the glyphs must be re-measured.
    val mono = MonoStyle
    val glyphs = remember(measurer, mono) { Glyphs(measurer, mono) }
    val geo = remember(glyphs, state.offsetDigits) { Geometry(glyphs, state.bytesPerRow, state.offsetDigits) }

    val wheelAccumulator = remember { floatArrayOf(0f) }
    val scrollState = rememberScrollableState { delta ->
        wheelAccumulator[0] -= delta
        val rows = (wheelAccumulator[0] / geo.rowH).toInt()
        if (rows != 0) {
            wheelAccumulator[0] -= rows * geo.rowH
            state.scrollRows(rows.toLong())
        }
        delta
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(modifier) {
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(HexColors.background)
                .onSizeChanged { size ->
                    state.visibleRows = maxOf(1, ((size.height - geo.headerH) / geo.rowH).toInt())
                    state.clampScroll()
                }
                .onKeyEvent { handleKey(it, state, onCopy, onPaste) }
                .focusRequester(focusRequester)
                .focusable()
                .scrollable(scrollState, Orientation.Vertical)
                .pointerInput(geo) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        focusRequester.requestFocus()
                        val shift = currentEvent.keyboardModifiers.isShiftPressed
                        val (start, pane) = hitTest(down.position, geo, state)
                        state.moveTo(start, extend = shift, pane = pane)
                        down.consume()
                        // Drag to select.
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val (hit, _) = hitTest(change.position, geo, state)
                            if (hit != state.cursor) {
                                val anchor = state.anchor ?: start
                                state.select(anchor, hit.coerceAtMost(state.doc.size - 1))
                            }
                            change.consume()
                        }
                    }
                }
        ) {
            drawHex(state, geo)
        }
        Scrollbar(state, Modifier.width(12.dp).fillMaxHeight())
    }
}

private fun hitTest(p: Offset, geo: Geometry, state: EditorState): Pair<Long, Pane> {
    val row = state.topRow + floor((p.y - geo.headerH) / geo.rowH).toInt().coerceAtLeast(0)
    val pane: Pane
    val col: Int
    if (p.x >= geo.asciiStart - geo.charW) {
        pane = Pane.ASCII
        col = floor((p.x - geo.asciiStart) / geo.charW).toInt()
    } else {
        pane = Pane.HEX
        col = (0 until state.bytesPerRow).firstOrNull { p.x < geo.hexX(it) + geo.cellW - geo.charW / 2 }
            ?: (state.bytesPerRow - 1)
    }
    val offset = row * state.bytesPerRow + col.coerceIn(0, state.bytesPerRow - 1)
    return offset.coerceIn(0, state.doc.size) to pane
}

private fun DrawScope.drawHex(state: EditorState, geo: Geometry) {
    val g = geo.g
    val doc = state.doc
    @Suppress("UNUSED_VARIABLE") val v = state.version // redraw on edits
    val bpr = state.bytesPerRow
    val highlights = state.highlights
    val selNode = state.selectedNode
    val selStart = state.selectionStart
    val selEnd = state.selectionEnd
    val hasSel = state.hasSelection
    val cursor = state.cursor
    val textY = (geo.rowH - g.hexByte[0].size.height) / 2

    // Header: column numbers.
    drawRect(HexColors.panelAlt, Offset.Zero, Size(size.width, geo.headerH))
    for (col in 0 until bpr) {
        drawText(g.hexByte[col], HexColors.dim, Offset(geo.hexX(col), textY))
        drawText(g.digit[col % 16], HexColors.dim, Offset(geo.asciiX(col), textY))
    }
    drawLine(HexColors.border, Offset(0f, geo.headerH - 1), Offset(size.width, geo.headerH - 1))
    val sepX1 = geo.hexStart - geo.charW
    val sepX2 = geo.asciiStart - geo.charW
    drawLine(HexColors.border, Offset(sepX1, 0f), Offset(sepX1, size.height))
    drawLine(HexColors.border, Offset(sepX2, 0f), Offset(sepX2, size.height))

    val rows = ((size.height - geo.headerH) / geo.rowH).toInt() + 1
    for (i in 0 until rows) {
        val row = state.topRow + i
        val rowStart = row * bpr
        if (rowStart > doc.size) break
        val y = geo.rowY(i)
        if (y > size.height) break

        // Offset column.
        val digits = geo.offsetDigits
        for (k in 0 until digits) {
            val nib = ((rowStart ushr ((digits - 1 - k) * 4)) and 0xF).toInt()
            drawText(g.digit[nib], HexColors.offset, Offset(geo.pad + k * geo.charW, y + textY))
        }

        for (col in 0 until bpr) {
            val off = rowStart + col
            if (off > doc.size) break
            val hx = geo.hexX(col) - geo.charW / 2
            val ax = geo.asciiX(col)
            val cellH = geo.rowH

            if (off < doc.size) {
                val bg: Color? = when {
                    hasSel && off in selStart..selEnd -> HexColors.selection
                    else -> {
                        val c = highlights.colorAt(off)
                        if (c >= 0) HexColors.fieldBackgrounds[c] else null
                    }
                }
                if (bg != null) {
                    // Extend the hex background into the gap so ranges look continuous.
                    val w = if (col == bpr / 2 - 1) geo.cellW + geo.charW else geo.cellW
                    drawRect(bg, Offset(hx, y), Size(if (col == bpr - 1) geo.cellW else w, cellH))
                    drawRect(bg, Offset(ax, y), Size(geo.charW, cellH))
                }
                if (selNode != null && off >= selNode.offset && off < selNode.end && !hasSel) {
                    drawRect(HexColors.selection, Offset(hx, y), Size(geo.cellW, cellH))
                    drawRect(HexColors.selection, Offset(ax, y), Size(geo.charW, cellH))
                }
                val b = doc.getUnsigned(off)
                val color = if (b == 0) HexColors.zeroByte else HexColors.text
                drawText(g.hexByte[b], color, Offset(geo.hexX(col), y + textY))
                drawText(g.ascii[b], if (isPrintableAscii(b)) HexColors.text else HexColors.zeroByte, Offset(ax, y + textY))
            }

            if (off == cursor) {
                val hexCaretX = geo.hexX(col) + if (state.pane == Pane.HEX) state.nibble * geo.charW else 0f
                val hexW = if (state.pane == Pane.HEX) geo.charW else geo.charW * 2
                if (state.pane == Pane.HEX) {
                    drawRect(HexColors.cursor, Offset(hexCaretX, y + 1), Size(hexW, cellH - 2), style = Stroke(1.5f))
                    drawRect(HexColors.cursorPane, Offset(ax, y), Size(geo.charW, cellH))
                } else {
                    drawRect(HexColors.cursorPane, Offset(geo.hexX(col), y), Size(geo.charW * 2, cellH))
                    drawRect(HexColors.cursor, Offset(ax, y + 1), Size(geo.charW, cellH - 2), style = Stroke(1.5f))
                }
                if (state.insertMode) {
                    // Insert mode shows a bar caret in the active pane.
                    val barX = if (state.pane == Pane.HEX) geo.hexX(col) - 1 else ax - 1
                    drawRect(HexColors.cursor, Offset(barX, y), Size(2f, cellH))
                }
            }
        }
    }
}

@Composable
private fun Scrollbar(state: EditorState, modifier: Modifier) {
    Box(modifier.background(HexColors.panelAlt)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun jump(y: Float) {
                        // Double precision: a multi-GB file has tens of millions of rows.
                        val maxTop = maxOf(0L, state.rowCount - state.visibleRows)
                        val frac = (y.toDouble() / size.height).coerceIn(0.0, 1.0)
                        state.topRow = (frac * maxTop).toLong()
                        state.clampScroll()
                    }
                    jump(down.position.y)
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        jump(change.position.y)
                        change.consume()
                    }
                }
            }
        ) {
            val total = maxOf(1L, state.rowCount)
            val visible = state.visibleRows.toLong().coerceAtMost(total)
            if (visible >= total) return@Canvas
            val thumbH = maxOf(24f, (size.height.toDouble() * visible / total).toFloat())
            val maxTop = maxOf(1L, total - visible)
            val thumbY = ((size.height - thumbH) * (state.topRow.toDouble() / maxTop)).toFloat()
            drawRoundRectCompat(Offset(2f, thumbY), Size(size.width - 4f, thumbH))
        }
    }
}

private fun DrawScope.drawRoundRectCompat(topLeft: Offset, size: Size) {
    drawRoundRect(
        HexColors.border,
        topLeft,
        size,
        androidx.compose.ui.geometry.CornerRadius(size.width / 2, size.width / 2),
    )
}

private fun handleKey(e: KeyEvent, state: EditorState, onCopy: () -> Unit, onPaste: () -> Unit): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    val shift = e.isShiftPressed
    val bpr = state.bytesPerRow
    val page = bpr.toLong() * maxOf(1, state.visibleRows - 1)
    when (e.key) {
        Key.DirectionLeft -> state.moveBy(-1, shift)
        Key.DirectionRight -> state.moveBy(1, shift)
        Key.DirectionUp -> state.moveBy(-bpr.toLong(), shift)
        Key.DirectionDown -> state.moveBy(bpr.toLong(), shift)
        Key.PageUp -> { state.scrollRows(-(state.visibleRows - 1L)); state.moveBy(-page, shift) }
        Key.PageDown -> { state.scrollRows(state.visibleRows - 1L); state.moveBy(page, shift) }
        Key.MoveHome -> state.moveTo(if (ctrl) 0 else state.cursor - state.cursor % bpr, shift)
        Key.MoveEnd -> state.moveTo(
            if (ctrl) state.doc.size else minOf(state.doc.size, state.cursor - state.cursor % bpr + bpr - 1), shift
        )
        Key.Tab -> state.pane = if (state.pane == Pane.HEX) Pane.ASCII else Pane.HEX
        Key.Insert -> state.insertMode = !state.insertMode
        Key.Delete -> state.deleteForward()
        Key.Backspace -> state.backspace()
        else -> {
            if (ctrl) {
                when (e.key) {
                    Key.Z -> if (shift) state.redo() else state.undo()
                    Key.Y -> state.redo()
                    Key.A -> state.selectAll()
                    Key.C -> onCopy()
                    Key.V -> onPaste()
                    else -> return false
                }
                return true
            }
            val ch = typedChar(e) ?: return false
            when (state.pane) {
                Pane.HEX -> state.typeHexDigit(ch.digitToIntOrNull(16) ?: return false)
                Pane.ASCII -> if (isPrintableAscii(ch.code)) state.typeChar(ch) else return false
            }
        }
    }
    return true
}

private fun typedChar(e: KeyEvent): Char? {
    val cp = e.utf16CodePoint
    if (cp in 0x20..0x7E) return cp.toChar()
    // Fallback for platforms that don't report a code point on key-down.
    return when (e.key) {
        Key.Zero, Key.NumPad0 -> '0'
        Key.One, Key.NumPad1 -> '1'
        Key.Two, Key.NumPad2 -> '2'
        Key.Three, Key.NumPad3 -> '3'
        Key.Four, Key.NumPad4 -> '4'
        Key.Five, Key.NumPad5 -> '5'
        Key.Six, Key.NumPad6 -> '6'
        Key.Seven, Key.NumPad7 -> '7'
        Key.Eight, Key.NumPad8 -> '8'
        Key.Nine, Key.NumPad9 -> '9'
        Key.A -> 'a'
        Key.B -> 'b'
        Key.C -> 'c'
        Key.D -> 'd'
        Key.E -> 'e'
        Key.F -> 'f'
        else -> null
    }
}

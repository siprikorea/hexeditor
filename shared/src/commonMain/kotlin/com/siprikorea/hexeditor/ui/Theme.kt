package com.siprikorea.hexeditor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

object HexColors {
    val background = Color(0xFF1E1F22)
    val panel = Color(0xFF2B2D30)
    val panelAlt = Color(0xFF26282B)
    val border = Color(0xFF3C3F41)
    val text = Color(0xFFDFE1E5)
    val dim = Color(0xFF868A91)
    val accent = Color(0xFF3574F0)
    val offset = Color(0xFF6F9FD8)
    val zeroByte = Color(0xFF6B6F76)
    val selection = Color(0xFF214283)
    val cursor = Color(0xFFE0A030)
    val cursorPane = Color(0x55E0A030)
    val modified = Color(0xFFE06C75)
    val error = Color(0xFFE06C75)
    val ok = Color(0xFF98C379)
    val treeSelected = Color(0xFF2E436E)
    val treeCursor = Color(0xFF33363B)

    /** Alternating backgrounds for template fields in the hex view (and tree swatches). */
    val fieldBackgrounds = listOf(
        Color(0xFF3B3A24),
        Color(0xFF24392F),
        Color(0xFF263449),
        Color(0xFF3D2A3D),
        Color(0xFF45322A),
        Color(0xFF233D3F),
    )
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = HexColors.text)
val UiStyle = TextStyle(fontSize = 13.sp, color = HexColors.text)

@Composable
fun HexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HexColors.accent,
            background = HexColors.background,
            surface = HexColors.panel,
            onSurface = HexColors.text,
            onBackground = HexColors.text,
        ),
        content = content,
    )
}

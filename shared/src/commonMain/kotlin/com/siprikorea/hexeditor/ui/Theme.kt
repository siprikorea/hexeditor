package com.siprikorea.hexeditor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hexeditor.shared.generated.resources.Res
import hexeditor.shared.generated.resources.jetbrains_mono_regular
import org.jetbrains.compose.resources.Font

/**
 * The palette follows Flow's dark theme: a dark ground with rounded, bordered sheets on it.
 * Names describe a role, not a shade.
 */
object HexColors {
    /** The window's ground, around and between the sheets. */
    val ground = Color(0xFF14161B)
    /** Sheets (the rounded panels) and the hex grid itself. */
    val background = Color(0xFF1C1F24)
    val panelAlt = background
    /** Controls sitting on a sheet: inputs, buttons, menus. */
    val panel = Color(0xFF272B33)
    val overlay = Color(0xFF2E333C)
    val hover = Color(0xFF2A2F38)
    val border = Color(0xFF2A2F37)
    val borderStrong = Color(0xFF3A4150)
    val text = Color(0xFFDFE1E5)
    val textSecondary = Color(0xFF9DA0A8)
    val dim = Color(0xFF6F737A)
    val accent = Color(0xFF3574F0)
    val accentHover = Color(0xFF4680F5)
    val accentSubtle = Color(0x2E3574F0)
    val offset = Color(0xFF6F9FD8)
    val zeroByte = Color(0xFF5A5F69)
    val selection = Color(0xFF214283)
    val cursor = Color(0xFFE0A030)
    val cursorPane = Color(0x55E0A030)
    val modified = Color(0xFFE0A030)
    val error = Color(0xFFDB5C5C)
    val ok = Color(0xFF59D6A4)
    val treeSelected = Color(0x403574F0)
    val treeCursor = Color(0xFF262A31)

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

/**
 * Monospaced text uses bundled JetBrains Mono: the web has no system monospace font, and
 * bundling it makes every platform look the same. Ligatures are off so code reads literally.
 */
val MonoStyle: TextStyle
    @Composable get() = TextStyle(
        fontFamily = FontFamily(Font(Res.font.jetbrains_mono_regular)),
        fontSize = 13.sp,
        color = HexColors.text,
        fontFeatureSettings = "liga 0, calt 0",
    )

val UiStyle = TextStyle(fontSize = 13.sp, color = HexColors.text)
val UiStrong = UiStyle.copy(fontWeight = FontWeight.SemiBold)
val UiSmall = TextStyle(fontSize = 12.sp, color = HexColors.textSecondary)

/** Corner radii, as in Flow: controls, menus and the sheets the panels sit in. */
object Radius {
    val control = 6.dp
    val menu = 8.dp
    val sheet = 10.dp
}

@Composable
fun HexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HexColors.accent,
            background = HexColors.ground,
            surface = HexColors.overlay,
            surfaceContainer = HexColors.overlay,
            onSurface = HexColors.text,
            onBackground = HexColors.text,
        ),
        content = content,
    )
}

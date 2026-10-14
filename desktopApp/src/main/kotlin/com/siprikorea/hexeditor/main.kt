package com.siprikorea.hexeditor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.siprikorea.hexeditor.platform.openFile
import java.awt.Taskbar
import java.io.File
import javax.imageio.ImageIO

fun main(args: Array<String>) = application {
    val initialFile = remember { args.firstOrNull()?.let(::File)?.takeIf { it.isFile }?.let(::openFile) }
    var title by remember { mutableStateOf("Hex Editor") }
    val icon = remember {
        // The Dock shows the Java icon unless told otherwise when not run from a packaged app.
        runCatching {
            Taskbar.getTaskbar().takeIf { it.isSupported(Taskbar.Feature.ICON_IMAGE) }
                ?.iconImage = ImageIO.read(object {}.javaClass.getResource("/icon.png"))
        }
        BitmapPainter(object {}.javaClass.getResourceAsStream("/icon.png")!!.use(::loadImageBitmap))
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = title,
        icon = icon,
        state = rememberWindowState(width = 1400.dp, height = 950.dp),
    ) {
        App(initialFile, onTitleChange = { title = it })
    }
}

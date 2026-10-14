package com.siprikorea.hexeditor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.siprikorea.hexeditor.platform.openFile
import java.io.File

fun main(args: Array<String>) = application {
    val initialFile = remember { args.firstOrNull()?.let(::File)?.takeIf { it.isFile }?.let(::openFile) }
    var title by remember { mutableStateOf("Hex Editor") }
    Window(
        onCloseRequest = ::exitApplication,
        title = title,
        state = rememberWindowState(width = 1400.dp, height = 950.dp),
    ) {
        App(initialFile, onTitleChange = { title = it })
    }
}

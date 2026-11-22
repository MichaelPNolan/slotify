package com.slotify.app.platform

import com.slotify.app.library.AUDIO_EXTENSIONS
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities

/**
 * Desktop native picker (AWT FileDialog). Blocks the calling thread until
 * the user chooses a file or cancels. Filename filter matches Slotify's
 * audio extension whitelist.
 */
actual fun pickAudioFile(initialDir: String?): String? {
    var chosen: String? = null
    val runnable = Runnable {
        val dialog = FileDialog(null as Frame?, "Link audio file", FileDialog.LOAD)
        dialog.isMultipleMode = false
        initialDir?.takeIf { File(it).isDirectory }?.let { dialog.directory = it }
        dialog.setFilenameFilter { _, name ->
            name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
        }
        dialog.isVisible = true
        val file = dialog.file ?: return@Runnable
        val dir = dialog.directory ?: return@Runnable
        chosen = File(dir, file).absolutePath
    }
    if (SwingUtilities.isEventDispatchThread()) {
        runnable.run()
    } else {
        SwingUtilities.invokeAndWait(runnable)
    }
    return chosen
}

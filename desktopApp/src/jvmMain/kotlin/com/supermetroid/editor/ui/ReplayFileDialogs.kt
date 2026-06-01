package com.supermetroid.editor.ui

import com.supermetroid.editor.emulator.AttemptReplayBundleFiles
import java.awt.FileDialog
import java.awt.Frame

object ReplayFileDialogs {
    fun openReplayBundle(): String? {
        val dialog = FileDialog(null as Frame?, "Open Replay Bundle", FileDialog.LOAD)
        dialog.file = "*.${AttemptReplayBundleFiles.EXTENSION}"
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return java.io.File(directory, file).absolutePath
    }

    fun saveReplayBundle(suggestedName: String): String? {
        val dialog = FileDialog(null as Frame?, "Export Shareable Replay", FileDialog.SAVE)
        dialog.file = if (suggestedName.endsWith(".${AttemptReplayBundleFiles.EXTENSION}")) {
            suggestedName
        } else {
            "$suggestedName.${AttemptReplayBundleFiles.EXTENSION}"
        }
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val file = dialog.file ?: return null
        val path = java.io.File(directory, file).absolutePath
        return if (path.endsWith(".${AttemptReplayBundleFiles.EXTENSION}")) {
            path
        } else {
            "$path.${AttemptReplayBundleFiles.EXTENSION}"
        }
    }
}

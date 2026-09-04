package com.supermetroid.editor.emulator

import java.io.File

/** Desktop-only capabilities shared by the embedded libretro and lsnes adapters. */
interface FrameProvidingBackend {
    val frameHolder: FrameHolder
}

interface StateDirectoryBackend {
    fun setStateDir(dir: File)
}

interface AudioControllableBackend {
    val audioHasHeadroom: Boolean
    var audioMuted: Boolean
    var audioVolume: Float
}

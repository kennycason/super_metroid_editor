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

/** Headless extra that can free-run at NTSC 60 instead of pause/step RPC. */
interface RealtimePlayBackend {
    val realtimeActive: Boolean
    suspend fun startRealtime(applyButtons: Boolean)
    suspend fun stopRealtime()
    fun writeRealtimeButtons(buttons: List<Int>, applyButtons: Boolean, speed: Int = 1)
    fun pollRealtime(): StepResult?
    suspend fun seekToFrame(frame: Int)
}

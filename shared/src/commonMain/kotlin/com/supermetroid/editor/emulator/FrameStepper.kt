package com.supermetroid.editor.emulator

interface FrameStepper {
    val emulatorCore: String

    fun saveState(): ByteArray
    fun loadState(state: ByteArray)
    fun runOneFrame(inputBits: Int): FrameRecord
    fun readSystemRam(offset: Int, length: Int): ByteArray
    fun resetFrameCounter(firstFrameNumber: Long = 0)
}

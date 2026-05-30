package com.supermetroid.editor.emulator

import java.io.File

data class CapturedAttemptLog(
    val log: AttemptLog,
    val initialState: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedAttemptLog) return false
        return log == other.log && initialState.contentEquals(other.initialState)
    }

    override fun hashCode(): Int {
        var result = log.hashCode()
        result = 31 * result + initialState.contentHashCode()
        return result
    }
}

class AttemptLogRecorder(
    private val stepper: FrameStepper,
) {
    /**
     * Records from the stepper's currently loaded game and savestate.
     *
     * This hashes [romFile] for later verification but does not load it; callers
     * must load the same ROM into [stepper] before calling this method.
     */
    fun record(
        romFile: File,
        inputBitsByFrame: Iterable<Int>,
        firstFrameNumber: Long = 0,
    ): CapturedAttemptLog {
        require(romFile.isFile) { "ROM file does not exist: ${romFile.absolutePath}" }
        return record(
            romBytes = romFile.readBytes(),
            inputBitsByFrame = inputBitsByFrame,
            firstFrameNumber = firstFrameNumber,
        )
    }

    /**
     * Records from the stepper's currently loaded game and savestate.
     *
     * This hashes [romBytes] for later verification but does not load them;
     * callers must load the same ROM into [stepper] before calling this method.
     */
    fun record(
        romBytes: ByteArray,
        inputBitsByFrame: Iterable<Int>,
        firstFrameNumber: Long = 0,
    ): CapturedAttemptLog {
        require(firstFrameNumber >= 0) { "firstFrameNumber must be non-negative" }

        val initialState = stepper.saveState()
        val frames = mutableListOf<FrameRecord>()
        stepper.resetFrameCounter(firstFrameNumber)
        for (inputBits in inputBitsByFrame) {
            frames += stepper.runOneFrame(inputBits)
        }

        val log = AttemptLog(
            emulatorCore = stepper.emulatorCore,
            romHash = Sha256.hex(romBytes),
            initialStateHash = Sha256.hex(initialState),
            frames = frames,
        )
        return CapturedAttemptLog(
            log = log,
            initialState = initialState.copyOf(),
        )
    }
}

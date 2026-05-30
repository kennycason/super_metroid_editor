package com.supermetroid.editor.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttemptLogRecorderTest {
    @Test
    fun `recorder stores core hash initial state and typed frames`() {
        val initialState = byteArrayOf(4, 5, 6)
        val stepper = CountingFrameStepper(initialState)
        val romBytes = byteArrayOf(1, 2, 3)

        val captured = AttemptLogRecorder(stepper).record(
            romBytes = romBytes,
            inputBitsByFrame = listOf(0, SnesInputBits.fromPressed(SnesButton.RIGHT)),
            firstFrameNumber = 7,
        )

        assertEquals("fake-core", captured.log.emulatorCore)
        assertEquals(Sha256.hex(romBytes), captured.log.romHash)
        assertEquals(Sha256.hex(initialState), captured.log.initialStateHash)
        assertTrue(initialState.contentEquals(captured.initialState))
        assertEquals(listOf(7L, 8L), captured.log.frames.map { it.frameNumber })
        assertEquals(99, captured.log.frames.first().frameState.health)
    }

    private class CountingFrameStepper(
        private val state: ByteArray,
    ) : FrameStepper {
        override val emulatorCore: String = "fake-core"
        private var nextFrameNumber: Long = 0

        override fun saveState(): ByteArray = state.copyOf()

        override fun loadState(state: ByteArray) {
            require(this.state.contentEquals(state)) { "Unexpected state loaded" }
        }

        override fun runOneFrame(inputBits: Int): FrameRecord {
            val wram = ByteArray(SuperMetroidWram.minimumFrameRecordBytes)
            wram.writeWord(SuperMetroidWram.HEALTH, 99)
            return FrameRecord(
                frameNumber = nextFrameNumber++,
                inputBits = SnesInputBits.normalize(inputBits),
                systemRamHash = Sha256.hex(wram),
                frameState = SuperMetroidWram.frameState(wram),
            )
        }

        override fun readSystemRam(offset: Int, length: Int): ByteArray = ByteArray(length)

        override fun resetFrameCounter(firstFrameNumber: Long) {
            require(firstFrameNumber >= 0) { "firstFrameNumber must be non-negative" }
            nextFrameNumber = firstFrameNumber
        }

        private fun ByteArray.writeWord(offset: Int, value: Int) {
            this[offset] = (value and 0xFF).toByte()
            this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }
    }
}

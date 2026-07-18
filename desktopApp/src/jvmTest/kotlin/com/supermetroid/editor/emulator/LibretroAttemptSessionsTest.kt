package com.supermetroid.editor.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LibretroAttemptSessionsTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `recording session writes start and end savestates`() {
        val stepper = CountingFrameStepper(byteArrayOf(1, 2, 3))
        val session = AttemptRecordingSession(recordingsDirProvider = { tempDir })

        session.start(stepper, romHash = "rom-hash", nextFrameNumber = 5)
        session.recordFrame(stepper, SnesInputBits.fromPressed(SnesButton.RIGHT))
        session.recordFrame(stepper, 0)

        val stopResult = session.stop(stepper)
        assertEquals(2, stopResult.log.frameCount)
        assertEquals(listOf(5L, 6L), stopResult.log.frames.map { it.frameNumber })
        assertEquals("rom-hash", stopResult.log.romHash)
        assertTrue(stopResult.logFile.isFile)
        assertTrue(AttemptReplayBundle.companionStateFile(stopResult.logFile).isFile)
        val finalStateFile = AttemptReplayBundle.companionFinalStateFile(stopResult.logFile)
        assertTrue(finalStateFile.isFile)
        assertNotEquals(
            AttemptReplayBundle.companionStateFile(stopResult.logFile).readBytes().toList(),
            finalStateFile.readBytes().toList(),
        )
        assertEquals(stopResult.log.finalStateHash, Sha256.hex(finalStateFile.readBytes()))
        assertFalse(session.isActive)
    }

    @Test
    fun `replay session runs recorded frames through stepper`() {
        val stepper = CountingFrameStepper(byteArrayOf(9, 9, 9))
        val frames = listOf(
            FrameRecord(
                frameNumber = 10,
                inputBits = SnesInputBits.fromPressed(SnesButton.A),
                systemRamHash = "hash-a",
                frameState = SuperMetroidFrameState(),
            ),
            FrameRecord(
                frameNumber = 11,
                inputBits = 0,
                systemRamHash = "hash-b",
                frameState = SuperMetroidFrameState(),
            ),
        )
        val replay = AttemptReplaySession(frames = frames, title = "test replay")
        replay.primeStepper(stepper)

        assertEquals(1, replay.runSteps(stepper, count = 1))
        assertEquals(1, replay.index)
        assertEquals(1, replay.runSteps(stepper, count = 1))
        assertTrue(replay.finished)
        assertEquals(0, replay.runSteps(stepper, count = 1))
    }

    private class CountingFrameStepper(
        initialState: ByteArray,
    ) : FrameStepper {
        override val emulatorCore: String = "fake-core"
        private var liveState: ByteArray = initialState.copyOf()
        private var nextFrameNumber: Long = 0

        override fun saveState(): ByteArray = liveState.copyOf()

        override fun loadState(state: ByteArray) {
            liveState = state.copyOf()
        }

        override fun runOneFrame(inputBits: Int): FrameRecord {
            liveState = liveState.copyOf()
            liveState[0] = (liveState[0].toInt() + 1).toByte()
            val wram = ByteArray(SuperMetroidWram.minimumFrameRecordBytes)
            wram.writeWord(SuperMetroidWram.HEALTH, 50)
            return FrameRecord(
                frameNumber = nextFrameNumber++,
                inputBits = SnesInputBits.normalize(inputBits),
                systemRamHash = Sha256.hex(wram),
                frameState = SuperMetroidWram.frameState(wram),
            )
        }

        override fun readSystemRam(offset: Int, length: Int): ByteArray = ByteArray(length)

        override fun resetFrameCounter(firstFrameNumber: Long) {
            nextFrameNumber = firstFrameNumber
        }

        private fun ByteArray.writeWord(offset: Int, value: Int) {
            this[offset] = (value and 0xFF).toByte()
            this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }
    }
}

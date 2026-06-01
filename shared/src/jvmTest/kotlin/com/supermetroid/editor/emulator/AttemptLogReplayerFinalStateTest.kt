package com.supermetroid.editor.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttemptLogReplayerFinalStateTest {
    @Test
    fun `replayer verifies final savestate hash when present`() {
        val initialState = byteArrayOf(1, 2, 3)
        val finalState = byteArrayOf(1, 2, 4)
        val stepper = MutableStateStepper(initialState)
        val log = AttemptLog(
            emulatorCore = "fake-core",
            romHash = "rom",
            initialStateHash = Sha256.hex(initialState),
            finalStateHash = Sha256.hex(finalState),
            frames = listOf(
                FrameRecord(
                    frameNumber = 0,
                    inputBits = 0,
                    systemRamHash = "hash-0",
                    frameState = SuperMetroidFrameState(),
                ),
            ),
        )
        stepper.expectedFrames += log.frames.single()
        stepper.replayFinalState = finalState

        val result = AttemptLogReplayer(stepper).replayAndVerify(
            log = log,
            initialState = initialState,
            actualRomHash = "rom",
        )

        assertTrue(result.matched, "Replay mismatch: ${result.failure}")
    }

    @Test
    fun `replayer fails when final savestate hash drifts`() {
        val initialState = byteArrayOf(1, 2, 3)
        val recordedFinalState = byteArrayOf(1, 2, 4)
        val stepper = MutableStateStepper(initialState)
        stepper.replayFinalState = byteArrayOf(1, 2, 5)
        val log = AttemptLog(
            emulatorCore = "fake-core",
            romHash = "rom",
            initialStateHash = Sha256.hex(initialState),
            finalStateHash = Sha256.hex(recordedFinalState),
            frames = listOf(
                FrameRecord(
                    frameNumber = 0,
                    inputBits = 0,
                    systemRamHash = "hash-0",
                    frameState = SuperMetroidFrameState(),
                ),
            ),
        )
        stepper.expectedFrames += log.frames.single()

        val result = AttemptLogReplayer(stepper).replayAndVerify(
            log = log,
            initialState = initialState,
            actualRomHash = "rom",
        )

        assertFalse(result.matched)
        assertEquals(ReplayFailureKind.FINAL_SAVESTATE_HASH_MISMATCH, result.failure?.kind)
    }

    private class MutableStateStepper(
        initialState: ByteArray,
    ) : FrameStepper {
        override val emulatorCore: String = "fake-core"
        private var liveState: ByteArray = initialState.copyOf()
        val expectedFrames = mutableListOf<FrameRecord>()
        var replayFinalState: ByteArray? = null
        private var nextFrameNumber: Long = 0

        override fun saveState(): ByteArray = replayFinalState?.copyOf() ?: liveState.copyOf()

        override fun loadState(state: ByteArray) {
            liveState = state.copyOf()
        }

        override fun runOneFrame(inputBits: Int): FrameRecord {
            val expected = expectedFrames[nextFrameNumber.toInt()]
            liveState = liveState.copyOf()
            liveState[0] = (liveState[0].toInt() + 1).toByte()
            return expected.copy(
                frameNumber = nextFrameNumber++,
                inputBits = SnesInputBits.normalize(inputBits),
            )
        }

        override fun readSystemRam(offset: Int, length: Int): ByteArray = ByteArray(length)

        override fun resetFrameCounter(firstFrameNumber: Long) {
            nextFrameNumber = firstFrameNumber
        }
    }
}

package com.supermetroid.editor.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttemptLogReplayerTest {
    @Test
    fun `replayer accepts matching attempt log`() {
        val romBytes = byteArrayOf(1, 2, 3)
        val initialState = byteArrayOf(4, 5, 6)
        val frames = listOf(
            frame(0, inputBits = 0, systemRamHash = "ram-0"),
            frame(1, inputBits = SnesInputBits.fromPressed(SnesButton.RIGHT), systemRamHash = "ram-1"),
        )
        val stepper = ScriptedFrameStepper(initialState, frames)

        val result = AttemptLogReplayer(stepper).replayAndVerify(
            log = log(romBytes, initialState, frames),
            initialState = initialState,
            romBytes = romBytes,
        )

        assertTrue(result.matched, "Expected replay to match: ${result.failure}")
        assertEquals(2, result.checkedFrames)
        assertEquals(frames.last(), result.expectedFinalFrame)
        assertEquals(frames.last(), result.actualFinalFrame)
    }

    @Test
    fun `replayer rejects logs with mismatched rom hash`() {
        val initialState = byteArrayOf(4, 5, 6)
        val stepper = ScriptedFrameStepper(initialState, emptyList())

        val result = AttemptLogReplayer(stepper).replayAndVerify(
            log = log(byteArrayOf(1, 2, 3), initialState, emptyList()),
            initialState = initialState,
            romBytes = byteArrayOf(9, 9, 9),
        )

        assertFalse(result.matched)
        assertEquals(ReplayFailureKind.ROM_HASH_MISMATCH, result.failure?.kind)
        assertEquals(0, result.checkedFrames)
    }

    @Test
    fun `replayer rejects logs with mismatched emulator core`() {
        val romBytes = byteArrayOf(1, 2, 3)
        val initialState = byteArrayOf(4, 5, 6)
        val stepper = ScriptedFrameStepper(
            state = initialState,
            actualFrames = emptyList(),
            emulatorCore = "different-core",
        )

        val result = AttemptLogReplayer(stepper).replayAndVerify(
            log = log(romBytes, initialState, emptyList()),
            initialState = initialState,
            romBytes = romBytes,
        )

        assertFalse(result.matched)
        assertEquals(ReplayFailureKind.EMULATOR_CORE_MISMATCH, result.failure?.kind)
        assertEquals(0, result.checkedFrames)
    }

    @Test
    fun `replayer rejects logs with mismatched initial savestate hash`() {
        val romBytes = byteArrayOf(1, 2, 3)
        val initialState = byteArrayOf(4, 5, 6)
        val stepper = ScriptedFrameStepper(initialState, emptyList())

        val result = AttemptLogReplayer(stepper).replayAndVerify(
            log = log(romBytes, initialState, emptyList()),
            initialState = byteArrayOf(7, 8, 9),
            romBytes = romBytes,
        )

        assertFalse(result.matched)
        assertEquals(ReplayFailureKind.INITIAL_STATE_HASH_MISMATCH, result.failure?.kind)
        assertEquals(0, result.checkedFrames)
    }

    @Test
    fun `replayer detects stepper failure`() {
        val romBytes = byteArrayOf(1, 2, 3)
        val initialState = byteArrayOf(4, 5, 6)
        val logFrames = listOf(
            frame(0, inputBits = 0, systemRamHash = "ram-0"),
            frame(1, inputBits = 0, systemRamHash = "ram-1"),
        )
        val stepper = ScriptedFrameStepper(initialState, actualFrames = logFrames.take(1))

        val result = AttemptLogReplayer(stepper).replayAndVerify(
            log = log(romBytes, initialState, logFrames),
            initialState = initialState,
            romBytes = romBytes,
        )

        assertFalse(result.matched)
        assertEquals(ReplayFailureKind.REPLAY_STEP_FAILED, result.failure?.kind)
        assertEquals(1, result.checkedFrames)
    }

    @Test
    fun `replayer detects skipped frame numbers before replaying`() {
        val romBytes = byteArrayOf(1, 2, 3)
        val initialState = byteArrayOf(4, 5, 6)
        val frames = listOf(
            frame(0, inputBits = 0, systemRamHash = "ram-0"),
            frame(2, inputBits = 0, systemRamHash = "ram-2"),
        )
        val stepper = ScriptedFrameStepper(initialState, frames)

        val result = AttemptLogReplayer(stepper).replayAndVerify(
            log = log(romBytes, initialState, frames),
            initialState = initialState,
            romBytes = romBytes,
        )

        assertFalse(result.matched)
        assertEquals(ReplayFailureKind.FRAME_SEQUENCE_MISMATCH, result.failure?.kind)
        assertEquals(0, stepper.runCount)
    }

    @Test
    fun `replayer detects final-state mismatch`() {
        val romBytes = byteArrayOf(1, 2, 3)
        val initialState = byteArrayOf(4, 5, 6)
        val expectedFrames = listOf(
            frame(0, inputBits = 0, systemRamHash = "ram-0"),
            frame(1, inputBits = 0, systemRamHash = "expected-final", frameState = SuperMetroidFrameState(health = 99)),
        )
        val actualFrames = listOf(
            expectedFrames[0],
            frame(1, inputBits = 0, systemRamHash = "actual-final", frameState = SuperMetroidFrameState(health = 98)),
        )
        val stepper = ScriptedFrameStepper(initialState, actualFrames)

        val result = AttemptLogReplayer(stepper).replayAndVerify(
            log = log(romBytes, initialState, expectedFrames),
            initialState = initialState,
            romBytes = romBytes,
        )

        assertFalse(result.matched)
        assertEquals(ReplayFailureKind.FINAL_STATE_MISMATCH, result.failure?.kind)
        assertEquals(2, result.checkedFrames)
        assertEquals(expectedFrames.last(), result.expectedFinalFrame)
        assertEquals(actualFrames.last(), result.actualFinalFrame)
        assertTrue(result.failure?.message.orEmpty().contains("frameState.health"))
    }

    private fun log(
        romBytes: ByteArray,
        initialState: ByteArray,
        frames: List<FrameRecord>,
        emulatorCore: String = "fake-core",
    ): AttemptLog = AttemptLog(
        emulatorCore = emulatorCore,
        romHash = Sha256.hex(romBytes),
        initialStateHash = Sha256.hex(initialState),
        frames = frames,
    )

    private fun frame(
        frameNumber: Long,
        inputBits: Int,
        systemRamHash: String,
        frameState: SuperMetroidFrameState = SuperMetroidFrameState(),
    ): FrameRecord = FrameRecord(
        frameNumber = frameNumber,
        inputBits = SnesInputBits.normalize(inputBits),
        systemRamHash = systemRamHash,
        frameState = frameState,
    )

    private class ScriptedFrameStepper(
        private val state: ByteArray,
        private val actualFrames: List<FrameRecord>,
        override val emulatorCore: String = "fake-core",
    ) : FrameStepper {
        var runCount: Int = 0
            private set

        private var nextFrameIndex: Int = 0

        override fun saveState(): ByteArray = state.copyOf()

        override fun loadState(state: ByteArray) {
            require(this.state.contentEquals(state)) { "Unexpected state loaded" }
            nextFrameIndex = 0
            runCount = 0
        }

        override fun runOneFrame(inputBits: Int): FrameRecord {
            if (nextFrameIndex >= actualFrames.size) {
                error("No scripted frame at index $nextFrameIndex")
            }
            val frame = actualFrames[nextFrameIndex++]
            runCount++
            return frame.copy(inputBits = SnesInputBits.normalize(inputBits))
        }

        override fun readSystemRam(offset: Int, length: Int): ByteArray = ByteArray(length)

        override fun resetFrameCounter(firstFrameNumber: Long) {
            require(firstFrameNumber >= 0) { "firstFrameNumber must be non-negative" }
        }
    }
}

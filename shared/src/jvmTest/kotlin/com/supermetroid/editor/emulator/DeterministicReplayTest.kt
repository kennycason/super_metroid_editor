package com.supermetroid.editor.emulator

import com.supermetroid.editor.libretro.LibretroCore
import com.supermetroid.editor.libretro.LibretroCoreDiscovery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class DeterministicReplayTest {
    @Test
    fun `libretro recorder replays 600 deterministic frames from the same savestate`() {
        val romFile = envFileOrSkip("SMEDIT_TEST_ROM")
        val stateFile = envFileOrSkip("SMEDIT_TEST_STATE")
        val corePath = LibretroCoreDiscovery.findCore()
        assumeTrue(
            corePath != null,
            "No SNES libretro core available. Set SMEDIT_LIBRETRO_CORE or install snes9x_libretro.",
        )

        val core = LibretroCore(corePath!!)
        var initialized = false
        try {
            core.init()
            initialized = true
            assertTrue(core.loadGame(romFile.absolutePath), "Failed to load ROM: ${romFile.absolutePath}")
            core.unserializeState(stateFile.readBytes())

            val stepper = LibretroEmulatorFrameStepper(core)
            val recorder = AttemptLogRecorder(stepper)
            val replayer = AttemptLogReplayer(stepper)
            val inputLog = deterministicInputSequence(frameCount = 600)

            val captured = recorder.record(
                romFile = romFile,
                inputBitsByFrame = inputLog,
                firstFrameNumber = 0,
            )
            val result = replayer.replayAndVerify(
                log = captured.log,
                initialState = captured.initialState,
                romBytes = romFile.readBytes(),
            )

            assertEquals(600, captured.log.frameCount)
            assertEquals((0L until 600L).toList(), captured.log.frames.map { it.frameNumber })
            assertEquals(600, result.checkedFrames)
            assertTrue(result.matched, "Replay mismatch: ${result.failure}")
            assertFalse(captured.log.romHash.isBlank())
            assertFalse(captured.log.initialStateHash.isBlank())
            assertNotEquals(captured.log.romHash, captured.log.initialStateHash)
            assertEquals(result.expectedFinalFrame?.systemRamHash, result.actualFinalFrame?.systemRamHash)
            assertEquals(result.expectedFinalFrame?.frameState, result.actualFinalFrame?.frameState)
        } finally {
            if (initialized) {
                core.close()
            }
        }
    }

    private fun deterministicInputSequence(frameCount: Int): List<Int> = List(frameCount) { frame ->
        when (frame) {
            in 60 until 120 -> SnesInputBits.fromPressed(SnesButton.RIGHT)
            in 120 until 180 -> SnesInputBits.fromPressed(SnesButton.RIGHT, SnesButton.A)
            else -> 0
        }
    }

    private fun envFileOrSkip(name: String): File {
        val value = System.getenv(name)
        assumeTrue(!value.isNullOrBlank(), "$name is not set; skipping libretro determinism test.")
        val file = File(value!!)
        assumeTrue(file.isFile, "$name does not point to a readable file: ${file.absolutePath}")
        return file.absoluteFile
    }
}

package com.supermetroid.editor.emulator

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class AttemptReplayBundleTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `bundle round trips attempt log and boundary savestates`() {
        val initialState = byteArrayOf(9, 8, 7, 6)
        val finalState = byteArrayOf(9, 8, 7, 5)
        val log = AttemptLog(
            emulatorCore = "Snes9x 1.63 5a40cd55",
            romHash = Sha256.hex(byteArrayOf(1, 2, 3)),
            initialStateHash = Sha256.hex(initialState),
            finalStateHash = Sha256.hex(finalState),
            frames = listOf(
                FrameRecord(
                    frameNumber = 0,
                    inputBits = SnesInputBits.fromPressed(SnesButton.RIGHT),
                    systemRamHash = "frame-0",
                    frameState = SuperMetroidFrameState(roomId = 0x92FD),
                ),
            ),
        )
        val bundleFile = File(tempDir, "test.smreplay")
        val manifest = AttemptReplayBundle.buildManifest(
            log = log,
            title = "Parlor test",
            description = "Easy segment",
        )

        AttemptReplayBundle.write(bundleFile, log, initialState, manifest, finalState)
        val loaded = AttemptReplayBundle.read(bundleFile)

        assertEquals("Parlor test", loaded.title)
        assertEquals(log, loaded.log)
        assertTrue(loaded.initialState.contentEquals(initialState))
        assertNotNull(loaded.finalState)
        assertTrue(loaded.finalState!!.contentEquals(finalState))
        assertEquals(listOf("0x92FD"), loaded.manifest.roomIds)

        ZipFile(bundleFile).use { zip ->
            assertTrue(zip.getEntry(AttemptReplayBundleFiles.MANIFEST) != null)
            assertTrue(zip.getEntry(AttemptReplayBundleFiles.ATTEMPT) != null)
            assertTrue(zip.getEntry(AttemptReplayBundleFiles.STATE) != null)
            assertTrue(zip.getEntry(AttemptReplayBundleFiles.FINAL_STATE) != null)
        }
    }

    @Test
    fun `loose json recording loads with companion savestate`() {
        val initialState = byteArrayOf(4, 5, 6, 7)
        val log = AttemptLog(
            emulatorCore = "Snes9x 1.63",
            romHash = "rom",
            initialStateHash = Sha256.hex(initialState),
            frames = listOf(
                FrameRecord(
                    frameNumber = 10,
                    inputBits = 0,
                    systemRamHash = "hash",
                ),
            ),
        )
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        val logFile = File(tempDir, "attempt-test.json")
        logFile.writeText(json.encodeToString(log))
        File(tempDir, "attempt-test.state").writeBytes(initialState)

        val loaded = AttemptReplayBundle.read(logFile)

        assertEquals(log, loaded.log)
        assertTrue(loaded.initialState.contentEquals(initialState))
    }

    @Test
    fun `normalized emulator cores treat git hash suffix drift as compatible`() {
        assertTrue(emulatorCoresCompatible("Snes9x 1.63 5a40cd55", "Snes9x 1.63 5a40cd5"))
    }
}

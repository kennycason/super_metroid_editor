package com.supermetroid.editor.emulator

import com.supermetroid.editor.rom.TestRomHelper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class LsnesSniq100LsmvTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `sniq 100 percent lsmv fixture exists with locked sha256`() {
        val fromClasspath = javaClass.classLoader.getResourceAsStream(FIXTURE_RESOURCE)
        assertNotNull(fromClasspath) { "classpath resource $FIXTURE_RESOURCE is missing" }
        val classpathBytes = fromClasspath!!.use { it.readBytes() }
        assertEquals(EXPECTED_LSMV_SHA256, sha256Hex(classpathBytes))

        val fixture = fixtureFile()
        assertTrue(fixture.isFile) { "Sniq LSMV fixture file not found" }
        assertEquals(EXPECTED_LSMV_SHA256, sha256Hex(fixture.readBytes()))
    }

    @Test
    fun `worker hello startSession boots with sniq movie argv`() = runBlocking {
        val worker = LsnesDiscovery.findWorker()
        assumeTrue(worker != null, "smedit-lsnes-worker not built")
        val rom = TestRomHelper.romFile()
        assumeTrue(rom != null && rom.isFile, "Test ROM not found")

        val movie = fixtureFile()
        var recordedCommand: List<String> = emptyList()
        val backend = LsnesBackend(
            executableOverride = File(worker!!),
            stageRootOverride = File(tempDir, "sessions"),
            timeoutMillis = 20_000L,
            processStarter = { builder ->
                recordedCommand = builder.command().toList()
                builder.start()
            },
        )
        try {
            backend.connect()
            val started = backend.startSession(
                SessionConfig(
                    romPath = rom!!.absolutePath,
                    moviePath = movie.absolutePath,
                ),
            )
            assertTrue(started.session.frameCounter >= 0)
            val snapshot = backend.snapshot()
            val roomId: Int = requireNotNull(snapshot.roomId) { "roomId should be an Int at boot, was null" }
            assertTrue(roomId >= 0)

            assertTrue(recordedCommand.any { it.startsWith("--rom-a=") }) {
                "expected --rom-a= in worker argv: $recordedCommand"
            }
            val positional = recordedCommand.drop(1).filter { !it.startsWith("-") }
            assertTrue(positional.any { it.endsWith(".lsmv") }) {
                "expected positional .lsmv in worker argv: $recordedCommand"
            }
            backend.closeSession()
            Unit
        } finally {
            backend.close()
        }
    }

    @Test
    @Tag("lsnes-integration")
    @EnabledIfEnvironmentVariable(named = "SMEDIT_LSNES_IT", matches = "1")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    fun `sniq movie reaches Ceres elevator and Landing Site markers`() = runBlocking {
        val worker = LsnesDiscovery.findWorker()
        assumeTrue(worker != null, "smedit-lsnes-worker not built")
        val rom = TestRomHelper.romFile()
        assumeTrue(rom != null && rom.isFile, "Test ROM not found")
        assumeTrue(sha256Hex(rom!!.readBytes()) == EXPECTED_ROM_SHA256) {
            "ROM SHA256 is not the TASVideos Super Metroid dump; skipping movie replay"
        }

        val movie = fixtureFile()
        val extraLuaMarker = File(tempDir, "extra-lua-ran.txt")
        val extraLua = File(tempDir, "extra.lua").apply {
            val markerPath = extraLuaMarker.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
            writeText(
                """
                local marker = assert(io.open("$markerPath", "w"))
                marker:write("loaded")
                marker:close()
                callback.register("frame_emulated", function() end)
                """.trimIndent(),
            )
        }
        var recordedCommand: List<String> = emptyList()
        val backend = LsnesBackend(
            executableOverride = File(worker!!),
            stageRootOverride = File(tempDir, "sessions-it"),
            timeoutMillis = 30_000L,
            processStarter = { builder ->
                recordedCommand = builder.command().toList()
                builder.start()
            },
        )
        try {
            backend.connect()
            val started = backend.startSession(
                SessionConfig(
                    romPath = rom.absolutePath,
                    moviePath = movie.absolutePath,
                    luaScriptPaths = listOf(extraLua.absolutePath),
                ),
            )
            assertTrue(recordedCommand.any { it.startsWith("--rom-a=") }) { "expected --rom-a=: $recordedCommand" }
            assertTrue(recordedCommand.drop(1).any { !it.startsWith("-") && it.endsWith(".lsmv") }) {
                "expected positional .lsmv: $recordedCommand"
            }
            assertEquals("loaded", extraLuaMarker.readText())
            assertNotNull(backend.frameHolder.latestFrame) { "headless worker did not return a frame PNG" }

            var frame = started.session.frameCounter
            suspend fun advanceTo(targetFrame: Int) {
                val remaining = targetFrame - frame
                assertTrue(remaining > 0) { "cannot advance from frame $frame to $targetFrame" }
                val result = backend.step(
                    EmulatorInput(applyButtons = false, includeFrame = false, repeat = remaining),
                )
                frame = result.session.frameCounter
                assertEquals(targetFrame, frame)
            }

            advanceTo(CERES_ELEVATOR_FRAME)
            val ceresElevator = backend.snapshot()
            assertEquals(CERES_ELEVATOR_FRAME, ceresElevator.frameCounter) {
                "worker advanced after acknowledging frame $CERES_ELEVATOR_FRAME"
            }
            assertEquals(CERES_ELEVATOR_ROOM, ceresElevator.roomId) {
                "expected Ceres elevator 0x${CERES_ELEVATOR_ROOM.toString(16).uppercase()} at frame $frame, got roomId=${ceresElevator.roomId}"
            }

            advanceTo(LANDING_SITE_FRAME)
            val landingSite = backend.snapshot()
            assertEquals(LANDING_SITE_FRAME, landingSite.frameCounter) {
                "worker advanced after acknowledging frame $LANDING_SITE_FRAME"
            }
            assertEquals(LANDING_SITE_ROOM, landingSite.roomId) {
                "expected Landing Site 0x${LANDING_SITE_ROOM.toString(16).uppercase()} at frame $frame, got roomId=${landingSite.roomId}"
            }
        } finally {
            backend.close()
        }
    }

    private fun fixtureFile(): File {
        val resource = javaClass.classLoader.getResource(FIXTURE_RESOURCE)
        if (resource != null && resource.protocol == "file") {
            return File(URI(resource.toString()))
        }
        val candidates = listOf(
            File("src/jvmMain/resources/$FIXTURE_RESOURCE"),
            File("desktopApp/src/jvmMain/resources/$FIXTURE_RESOURCE"),
            File("src/jvmTest/resources/$FIXTURE_RESOURCE"),
            File("desktopApp/src/jvmTest/resources/$FIXTURE_RESOURCE"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Sniq LSMV fixture not found as $FIXTURE_RESOURCE")
    }

    companion object {
        private const val FIXTURE_RESOURCE = "lsnes/sniq_100_4010M.lsmv"
        private const val EXPECTED_LSMV_SHA256 =
            "1bd065d89b70c16efb6f9276e82b1c07fd57ec40095f030cc6efe6663458929c"
        private const val EXPECTED_ROM_SHA256 =
            "12b77c4bc9c1832cee8881244659065ee1d84c70c3d29e6eaf92e6798cc2ca72"
        private const val CERES_ELEVATOR_FRAME = 8319
        private const val CERES_ELEVATOR_ROOM = 0xDF45
        private const val LANDING_SITE_FRAME = 15198
        private const val LANDING_SITE_ROOM = 0x91F8

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
    }
}

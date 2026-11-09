package com.supermetroid.editor.ui

import com.supermetroid.editor.data.IntroScene
import com.supermetroid.editor.data.IntroImageSource
import com.supermetroid.editor.data.IntroSceneLayout
import com.supermetroid.editor.data.IntroPortraitAnimation
import com.supermetroid.editor.data.IntroTextReveal
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.asm.AsmSourcePatchMaterializer
import com.supermetroid.editor.libretro.LibretroCore
import com.supermetroid.editor.libretro.LibretroCoreDiscovery
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomFreeSpaceAllocator
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlan
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class IntroSequenceRomPatchTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `native six beat template compiles to ten runtime phases with animation`() {
        val original = TestRomHelper.requireRomBytes()
        val rom = original.copyOf(RomConstants.EXPANDED_ROM_SIZE).also {
            it.fill(0xFF.toByte(), original.size, it.size)
        }
        val parser = RomParser(rom)
        val plan = RomWritePlan(rom)
        val allocator = RomFreeSpaceAllocator(plan.romData, parser::snesToPc, parser::pcToSnes, guardBytes = 2)
        val scenes = (0 until 6).map { index ->
            val vignette = com.supermetroid.editor.rom.IntroRomImageResolver.defaultVignetteForScene(index)
            IntroScene(
                name = "Story beat ${index + 1}",
                text = "NATIVE INTRO PAGE ${index + 1}",
                imageSource = IntroImageSource.ROM_SAMUS_PORTRAIT,
                layout = IntroSceneLayout.TEXT_OVER_IMAGE,
                portraitAnimation = if (index == 5) IntroPortraitAnimation.DEADPAN else IntroPortraitAnimation.NATIVE_BLINK,
                textReveal = IntroTextReveal.NATIVE_TYPEWRITER,
                vignetteSource = vignette,
                vignetteFrame = com.supermetroid.editor.rom.IntroRomImageResolver.defaultFrame(vignette),
                vignettePanStartX = if (index == 2) 32 else 0,
                vignettePanStartY = if (index == 3) -24 else 8,
            )
        }

        val result = IntroSequenceRomPatch.install(scenes, plan, allocator, parser::snesToPc, parser)

        assertEquals(10, result.sceneCount)
        assertTrue(result.tileCount > 10)
        assertTrue(result.payloadBytes < RomConstants.EXPANDED_ROM_SIZE - RomConstants.ROM_SIZE)
        assertEquals(0x22, plan.finalRom()[parser.snesToPc(0x8BA66F)].toInt() and 0xFF)
    }

    @Test
    fun `runtime and scene assets install atomically in expanded banks`() {
        val rom = ByteArray(RomConstants.EXPANDED_ROM_SIZE)
        rom.fill(0xFF.toByte(), RomConstants.ROM_SIZE, rom.size)
        val parser = RomParser(rom)
        val hook = parser.snesToPc(0x8BA66F)
        byteArrayOf(0xC2.toByte(), 0x30, 0x20, 0x6A, 0xA8.toByte()).copyInto(rom, hook)
        val plan = RomWritePlan(rom)
        val workingParser = RomParser(plan.romData)
        val allocator = RomFreeSpaceAllocator(
            romData = plan.romData,
            snesToPc = workingParser::snesToPc,
            pcToSnes = workingParser::pcToSnes,
            guardBytes = 2,
        )

        val result = IntroSequenceRomPatch.install(
            scenes = (1..6).map { IntroScene(name = "Scene $it", text = "HELLO FROM SCENE $it.") },
            writePlan = plan,
            freeSpaceAllocator = allocator,
            snesToPc = workingParser::snesToPc,
        )

        assertEquals(6, result.sceneCount)
        assertTrue(result.runtimeAddress ushr 16 in 0xE0..0xFF)
        val final = plan.finalRom()
        assertEquals(0x22, final[hook].toInt() and 255)
        assertEquals(result.runtimeAddress and 255, final[hook + 1].toInt() and 255)
        assertEquals(0x60, final[hook + 4].toInt() and 255)
        val writes = plan.report().writes.filter { it.owner == "patch:config_intro_sequence" }
        assertEquals(7, writes.count { it.kind == RomWriteKind.ALLOCATION })
        assertEquals(1, writes.count { it.kind == RomWriteKind.HOOK })
        assertTrue(writes.all { it.offset >= 0 && it.endInclusive < RomConstants.EXPANDED_ROM_SIZE })
        val materialized = AsmSourcePatchMaterializer().materialize(
            plan.report(),
            setOf("patch:config_intro_sequence"),
            RomConstants.EXPANDED_ROM_SIZE,
        )
        assertEquals(0, materialized.skippedWrites)
        assertEquals(8, materialized.claims.size)
        assertTrue(materialized.source.contains("Patches > Intro Sequence Manager"))
    }

    @Test
    fun `exported runtime boots and advances managed scenes in Snes9x`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val rom = requireNotNull(original)
        val corePath = LibretroCoreDiscovery.findCore()
        assumeTrue(corePath != null, "Snes9x libretro core not found")
        val input = File(tempDir, "intro-runtime-input.smc").apply { writeBytes(rom) }
        val parser = RomParser(rom)
        val project = SmEditProject(romPath = input.absolutePath).also { project ->
            project.patches += INTRO_SEQUENCE_PATCH.copy(enabled = true)
            project.introSequence.scenes.clear()
            project.introSequence.scenes += IntroScene(
                name = "First",
                imageSource = IntroImageSource.ROM_MOTHER_BRAIN,
                layout = IntroSceneLayout.IMAGE_ONLY,
            )
            project.introSequence.scenes += IntroScene(name = "Second", text = "SECOND MANAGED SCENE")
        }
        val output = File(tempDir, "intro-runtime-output.smc").apply {
            writeBytes(requireNotNull(RomExporter(project, parser).build()).resultRom)
        }

        val core = LibretroCore(corePath!!)
        core.setSaveDirectory(File(tempDir, "saves").absolutePath)
        core.init()
        try {
            assertTrue(core.loadGame(output.absolutePath))
            var enteredManagedIntro = false
            for (frame in 0 until 24_000) {
                val inputState = MutableList(12) { 0 }
                if (frame > 180 && frame % 30 == 0) {
                    inputState[3] = 1 // Start: leave title/menu screens.
                    inputState[8] = 1 // A: accept the highlighted empty save slot.
                }
                core.setInput(0, inputState)
                core.run()
                if (frame % 5 == 0 && wramWord(core, 0x1A4D) in 1..3) {
                    enteredManagedIntro = true
                    break
                }
            }
            assertTrue(enteredManagedIntro, "Managed intro runtime was not reached after booting a new game")

            // Let the first scene finish fading in, then press A for one frame.
            var firstSceneVisible = false
            repeat(120) {
                core.setInput(0, List(12) { 0 })
                core.run()
                if (wramWord(core, 0x1A4D) == 2) firstSceneVisible = true
            }
            assertTrue(firstSceneVisible, "The first managed intro scene did not finish fading in")
            val frame = core.captureRuntimeSnapshot(includeFrame = true, includeWram = false).frame
            assertNotNull(frame)
            assertTrue(requireNotNull(frame).pixels.any { it and 0x00FF_FFFF != 0 }, "The managed intro frame remained blank")
            val advance = MutableList(12) { 0 }.also { it[8] = 1 }
            core.setInput(0, advance)
            core.run()
            core.setInput(0, List(12) { 0 })

            var reachedSecondScene = false
            repeat(180) {
                core.run()
                if (wramWord(core, 0x1A4F) == 1 && wramWord(core, 0x1A4D) in 1..2) {
                    reachedSecondScene = true
                }
            }
            assertTrue(reachedSecondScene, "Button advance did not load the second managed intro scene")
        } finally {
            core.close()
        }
    }

    private fun wramWord(core: LibretroCore, address: Int): Int {
        val bytes = core.readWram(address, 2)
        return (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
    }
}

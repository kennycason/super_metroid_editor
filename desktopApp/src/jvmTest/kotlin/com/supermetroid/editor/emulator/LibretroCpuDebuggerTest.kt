package com.supermetroid.editor.emulator

import com.supermetroid.editor.libretro.LibretroCore
import com.supermetroid.editor.libretro.LibretroCoreDiscovery
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LibretroCpuDebuggerTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `bundled core exposes deterministic instruction control and cpu state`() {
        val corePath = LibretroCoreDiscovery.findCore()
        assumeTrue(corePath != null, "Bundled Snes9x core is unavailable")
        val rom = File(tempDir, "debugger.sfc").apply { writeBytes(TestRomHelper.requireRomBytes()) }
        val core = LibretroCore(requireNotNull(corePath))
        core.init()
        try {
            assertEquals(1, core.cpuDebugApiVersion())
            assertTrue(core.supportsCpuDebugging())
            assertTrue(core.loadGame(rom.absolutePath))

            val initial = core.readCpuDebugState()
            assertTrue(initial.pc in 0..0xFFFFFF)
            assertTrue(initial.memoryWidth in setOf(8, 16))
            assertTrue(initial.indexWidth in setOf(8, 16))

            val saved = core.serializeState()
            val firstStep = core.stepInstruction()
            assertEquals(DebugHaltReason.STEP, firstStep.haltReason)
            assertEquals(1, firstStep.cpu.instructions)
            assertFalse(firstStep.cpu.halted)

            core.unserializeState(saved)
            val restored = core.readCpuDebugState()
            assertEquals(initial.pc, restored.pc)
            assertEquals(initial.a, restored.a)
            assertEquals(initial.x, restored.x)
            assertEquals(initial.y, restored.y)
            assertEquals(initial.s, restored.s)
            val repeatedStep = core.stepInstruction()
            assertEquals(firstStep.cpu.pc, repeatedStep.cpu.pc)
            assertEquals(firstStep.cpu.a, repeatedStep.cpu.a)
            assertEquals(firstStep.cpu.p, repeatedStep.cpu.p)

            val alreadyAtTarget = core.runUntil(
                addresses = listOf(repeatedStep.cpu.pc),
                instructionLimit = 100,
                frameLimit = 1,
            )
            assertEquals(DebugHaltReason.BREAKPOINT, alreadyAtTarget.haltReason)
            assertEquals(0, alreadyAtTarget.cpu.instructions)
            assertEquals(repeatedStep.cpu.pc, alreadyAtTarget.cpu.pc)

            val boundedRun = core.runUntil(
                addresses = emptyList(),
                instructionLimit = 32,
                frameLimit = 1,
            )
            assertEquals(DebugHaltReason.INSTRUCTION_LIMIT, boundedRun.haltReason)
            assertEquals(32, boundedRun.cpu.instructions)
            assertNotEquals(alreadyAtTarget.cpu.pc, boundedRun.cpu.pc)

            val frameBoundedRun = core.runUntil(
                addresses = emptyList(),
                instructionLimit = 10_000_000,
                frameLimit = 1,
            )
            assertEquals(DebugHaltReason.FRAME_LIMIT, frameBoundedRun.haltReason)
            assertTrue(frameBoundedRun.cpu.instructions > 0)

            val beforeRead = core.readCpuDebugState()
            val wram = core.readBus(0x7E0000, 16)
            assertEquals(16, wram.size)
            assertArrayEquals(wram, core.readBus(0x7E0000, 16))
            assertThrows(IllegalStateException::class.java) {
                core.readBus(0x002100, 1)
            }
            val afterRead = core.readCpuDebugState()
            assertEquals(beforeRead.pc, afterRead.pc)
            assertEquals(beforeRead.cycles, afterRead.cycles, "Paused memory inspection must not advance CPU timing")
        } finally {
            core.close()
        }
    }
}

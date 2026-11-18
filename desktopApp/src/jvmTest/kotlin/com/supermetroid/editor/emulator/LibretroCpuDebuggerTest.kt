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
        val rom = File(tempDir, "debugger.sfc").apply {
            writeBytes(debuggerFixtureRom(TestRomHelper.requireRomBytes()))
        }
        val core = LibretroCore(requireNotNull(corePath))
        core.init()
        try {
            assertEquals(2, core.cpuDebugApiVersion())
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

            core.unserializeState(saved)
            var callState: ByteArray? = null
            var callPc = 0
            var callLength = 0
            repeat(64) {
                if (callState == null) {
                    val cpu = core.readCpuDebugState()
                    val opcode = core.readBus(cpu.pc, 1).single().toInt() and 0xFF
                    val length = when (opcode) {
                        0x20, 0xFC -> 3 // JSR abs / JSR (abs,X)
                        0x22 -> 4 // JSL long
                        else -> 0
                    }
                    if (length > 0) {
                        callState = core.serializeState()
                        callPc = cpu.pc
                        callLength = length
                    } else {
                        core.stepInstruction()
                    }
                }
            }
            val beforeCall = requireNotNull(callState) { "No JSR/JSL reached near the reset entry point" }
            val fallthrough = (callPc and 0xFF0000) or ((callPc + callLength) and 0xFFFF)
            val callee = (callPc and 0xFF0000) or 0x8010

            val breakpointDuringOver = core.stepOver(
                addresses = listOf(callPc, callee),
                instructionLimit = 2_000_000,
                frameLimit = 120,
            )
            assertEquals(DebugHaltReason.BREAKPOINT, breakpointDuringOver.haltReason)
            assertEquals(callee, breakpointDuringOver.cpu.pc, "only the initial PC breakpoint is skipped")

            core.unserializeState(beforeCall)
            val over = core.stepOver(emptyList(), instructionLimit = 2_000_000, frameLimit = 120)
            assertEquals(DebugHaltReason.STEP_OVER, over.haltReason)
            assertEquals(fallthrough, over.cpu.pc)

            core.unserializeState(beforeCall)
            val into = core.stepInstruction()
            assertEquals(DebugHaltReason.STEP, into.haltReason)
            assertNotEquals(fallthrough, into.cpu.pc)
            val out = core.stepOut(emptyList(), instructionLimit = 2_000_000, frameLimit = 120)
            assertEquals(DebugHaltReason.STEP_OUT, out.haltReason)
            assertEquals(fallthrough, out.cpu.pc)
        } finally {
            core.close()
        }
    }

    /** Deterministic LoROM reset program with one nested JSR. The real core
     * executes it; only the test ROM's reset vectors and first bytes differ. */
    private fun debuggerFixtureRom(vanilla: ByteArray): ByteArray = vanilla.copyOf().also { rom ->
        val program = byteArrayOf(
            0x78,                         // SEI
            0x18,                         // CLC
            0xFB.toByte(),                // XCE (native mode)
            0xC2.toByte(), 0x30,          // REP #$30 (16-bit A/X)
            0x20, 0x10, 0x80.toByte(),    // JSR $8010
            0xEA.toByte(),                // fallthrough: NOP
            0x80.toByte(), 0xFE.toByte(), // BRA *
        )
        program.copyInto(rom, destinationOffset = 0)
        byteArrayOf(
            0xA9.toByte(), 0x34, 0x12,    // $8010: LDA #$1234
            0x20, 0x20, 0x80.toByte(),    // JSR $8020
            0x60,                         // RTS
        ).copyInto(rom, destinationOffset = 0x10)
        byteArrayOf(
            0xA2.toByte(), 0x78, 0x56,    // $8020: LDX #$5678
            0x60,                         // RTS
        ).copyInto(rom, destinationOffset = 0x20)
        rom[0x30] = 0x40 // $8030: RTI for every interrupt vector
        for (vector in listOf(0x7FE4, 0x7FE6, 0x7FE8, 0x7FEA, 0x7FEE, 0x7FF4, 0x7FFA, 0x7FFE)) {
            rom[vector] = 0x30
            rom[vector + 1] = 0x80.toByte()
        }
        rom[0x7FFC] = 0x00 // reset -> $8000
        rom[0x7FFD] = 0x80.toByte()
    }
}

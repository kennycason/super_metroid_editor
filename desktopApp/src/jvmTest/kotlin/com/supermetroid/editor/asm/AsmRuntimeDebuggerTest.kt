package com.supermetroid.editor.asm

import com.supermetroid.editor.emulator.CpuDebugState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsmRuntimeDebuggerTest {
    private val symbols = AsmCompiledSymbols(
        labels = mapOf(
            "NativeRoutine" to 0x81A080,
            "NativeReturn" to 0x81A0AE,
            "SMEDIT_Helper" to 0xE08000,
            "SMEDIT_Helper_Return" to 0xE08010,
        ),
        lineAddressesByFile = mapOf(
            "bank_81.asm" to intArrayOf(0x81A0AE, 42),
            "__smedit_generated_managed_code.asm" to intArrayOf(0xE08010, 8),
        ),
        emittedLocations = listOf(
            AsmCompiledLocation("bank_81.asm", 42, 0x81A0AE),
            AsmCompiledLocation("__smedit_generated_managed_code.asm", 8, 0xE08010),
        ),
    )

    @Test
    fun `maps exact pc and symbol backed return candidates without inventing frames`() {
        val stack = byteArrayOf(
            0x0F, 0x80.toByte(), 0xE0.toByte(), // RTL return becomes $E08010
            0x00,
            0xAD.toByte(), 0xA0.toByte(), // RTS return in PB $81 becomes $81A0AE
            0x55,
        )
        val inspection = AsmRuntimeDebugResolver.resolve(
            cpu = cpu(pc = 0x81A0AE),
            symbols = symbols,
            stackAddress = 0x01F0,
            stackBytes = stack,
            mappingStale = false,
            sourceLine = { file, line -> "$file:$line" },
            isGenerated = { it.startsWith("__smedit_") },
        )

        assertEquals("bank_81.asm", inspection.currentSource?.fileId)
        assertEquals(42, inspection.currentSource?.lineIndex)
        assertEquals("NativeReturn", inspection.currentSymbol?.first)
        assertFalse(inspection.mappingStale)
        assertNull(inspection.executionConcern)
        assertTrue(inspection.stackCandidates.any {
            it.longReturn && it.returnAddress == 0xE08010 && it.source?.generated == true
        })
        assertTrue(inspection.stackCandidates.any {
            !it.longReturn && it.returnAddress == 0x81A0AE && it.source?.generated == false
        })
    }

    @Test
    fun `reports stale unmapped and stopped execution explicitly`() {
        val inspection = AsmRuntimeDebugResolver.resolve(
            cpu = cpu(pc = 0x7E1234, halted = true),
            symbols = symbols,
            stackAddress = 0x0100,
            stackBytes = ByteArray(8),
            mappingStale = true,
            sourceLine = { _, _ -> null },
            isGenerated = { false },
        )

        assertNull(inspection.currentSource)
        assertTrue(inspection.mappingStale)
        assertTrue(requireNotNull(inspection.executionConcern).contains("stopped"))
        assertTrue(inspection.stackCandidates.isEmpty())
    }

    @Test
    fun `runtime source mapping requires the exact ROM build identity`() {
        assertTrue(isAsmRuntimeMappingFresh("build-2", "build-2", reportSucceeded = true, sourceBuildRequired = false))
        assertFalse(isAsmRuntimeMappingFresh("build-1", "build-2", reportSucceeded = true, sourceBuildRequired = false))
        assertFalse(isAsmRuntimeMappingFresh(null, "build-2", reportSucceeded = true, sourceBuildRequired = false))
        assertFalse(isAsmRuntimeMappingFresh("build-2", "build-2", reportSucceeded = true, sourceBuildRequired = true))
        assertFalse(isAsmRuntimeMappingFresh("build-2", "build-2", reportSucceeded = false, sourceBuildRequired = false))
    }

    private fun cpu(pc: Int, halted: Boolean = false) = CpuDebugState(
        pc = pc,
        a = 0x1234,
        x = 0x56,
        y = 0x78,
        d = 0,
        db = 0x7E,
        pb = pc ushr 16,
        s = 0x1EF,
        p = 0x34,
        memoryWidth = 8,
        indexWidth = 8,
        emulation = false,
        waitingForInterrupt = false,
        halted = halted,
        cycles = 100,
        verticalCounter = 20,
        coreFrame = 5,
        instructions = 1,
    )
}

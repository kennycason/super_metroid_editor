package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomConstants
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AsmVerifiedFreeSpaceCatalogTest {
    @TempDir
    lateinit var temporary: File

    @Test
    fun `catalog verifies documented fill and treats already-expanded banks as managed space`() {
        val source = File(temporary, "src").apply { mkdirs() }
        File(source, "bank_80.asm").writeText(
            """
            Freespace_Bank80_8100:
            ; ${'$'}10 zero bytes
            Freespace_Bank80_8200:
            ; ${'$'}20 bytes
            """.trimIndent(),
        )
        val rom = ByteArray(RomConstants.EXPANDED_ROM_SIZE) { -1 }
        rom.fill(0, 0x100, 0x110)

        val ranges = AsmVerifiedFreeSpaceCatalog.load(
            temporary, rom, RomConstants.EXPANDED_ROM_SIZE, 0xFF,
        )

        assertEquals(0, ranges.single { it.pcStart == 0x100 }.fillByte)
        assertEquals(0x20, ranges.single { it.pcStart == 0x200 }.length)
        assertEquals(32, ranges.count { it.label.startsWith("Expanded bank") })
        assertTrue(ranges.any { it.bank == 0xE0 && it.pcStart == RomConstants.ROM_SIZE })
    }

    @Test
    fun `catalog safely clamps an overstated declaration to its erased prefix`() {
        val source = File(temporary, "src").apply { mkdirs() }
        File(source, "bank_80.asm").writeText("Freespace_Bank80_8100:\n; ${'$'}10 bytes\n")
        val rom = ByteArray(RomConstants.ROM_SIZE) { -1 }.also { it[0x108] = 0 }

        val range = AsmVerifiedFreeSpaceCatalog.load(
            temporary, rom, RomConstants.ROM_SIZE, 0xFF,
        ).single()
        assertEquals(8, range.length)
    }

    @Test
    fun `catalog rejects a declaration that does not begin in erased space`() {
        val source = File(temporary, "src").apply { mkdirs() }
        File(source, "bank_80.asm").writeText("Freespace_Bank80_8100:\n; ${'$'}10 bytes\n")
        val rom = ByteArray(RomConstants.ROM_SIZE) { -1 }.also { it[0x100] = 0x24 }

        assertFailsWith<IllegalArgumentException> {
            AsmVerifiedFreeSpaceCatalog.load(temporary, rom, RomConstants.ROM_SIZE, 0xFF)
        }
    }
}

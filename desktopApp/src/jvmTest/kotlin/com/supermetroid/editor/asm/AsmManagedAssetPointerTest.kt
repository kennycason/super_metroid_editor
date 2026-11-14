package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomAllocation
import com.supermetroid.editor.rom.CinematicMapAssets
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.TestRomHelper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AsmManagedAssetPointerTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `managed pointer validation checks table and split immediate consumers`() {
        val sourceRoot = File(temporary, "src").apply { mkdirs() }
        val tableText = """
            Tileset_Table_0_Test:
                dl Managed_Test
        """.trimIndent() + "\n"
        val codeText = """
            LoadManagedTest:
                LDA.W #Managed_Test>>8&${'$'}FF00
                LDA.W #Managed_Test
        """.trimIndent() + "\n"
        File(sourceRoot, "bank_8F.asm").writeText(tableText)
        File(sourceRoot, "bank_82.asm").writeText(codeText)
        val sources = mapOf("bank_8F.asm" to tableText, "bank_82.asm" to codeText)
        val bindings = requireNotNull(AsmManagedAssets.pointerBindings(sources, "Managed_Test"))
        assertTrue(bindings.map(AsmManagedPointerBinding::kind).toSet() == AsmManagedPointerKind.entries.toSet())

        val destination = 0xE08000
        val tableAddress = 0x8F9000
        val bankImmediateAddress = 0x82E000
        val lowImmediateAddress = bankImmediateAddress + 3
        val rom = ByteArray(RomConstants.EXPANDED_ROM_SIZE)
        fun write(address: Int, vararg bytes: Int) {
            val pc = requireNotNull(snesLoRomToPc(address))
            bytes.forEachIndexed { index, value -> rom[pc + index] = value.toByte() }
        }
        write(tableAddress, destination, destination ushr 8, destination ushr 16)
        write(bankImmediateAddress, 0xA9, 0x00, 0xE0)
        write(lowImmediateAddress, 0xA9, 0x00, 0x80)
        val record = AsmManagedAssetRecord(
            id = "managed-test",
            kind = "test",
            sourceFileId = "bank_B9.asm",
            sourceLabel = "Managed_Test",
            originalAssetPath = "Managed_Test.bin",
            managedAssetPath = "smedit-managed/managed-test.bin",
            originalPcOffset = 0x100,
            originalLength = 4,
            originalDeclaration = "Managed_Test:\nincbin \"../data/Managed_Test.bin\"",
            pointerBindings = bindings,
        )
        val placement = AsmManagedAssetPlacement(
            record,
            RomAllocation(0xE0, requireNotNull(snesLoRomToPc(destination)), destination, 4, "Managed_Test"),
            ByteArray(4),
        )
        val symbols = AsmCompiledSymbols(
            labels = mapOf("Tileset_Table_0_Test" to tableAddress),
            lineAddressesByFile = mapOf(
                "bank_82.asm" to intArrayOf(bankImmediateAddress, 1, lowImmediateAddress, 2),
            ),
        )

        AsmManagedAssets.validatePointerBindings(listOf(placement), temporary, rom, symbols)
        rom[requireNotNull(snesLoRomToPc(tableAddress))] = 1
        assertFailsWith<IllegalArgumentException> {
            AsmManagedAssets.validatePointerBindings(listOf(placement), temporary, rom, symbols)
        }
    }

    @Test
    fun `one parsed pointer index preserves independent supported and rejected labels`() {
        val sources = mapOf(
            "bank_80.asm" to """
                PointerTable:
                    dl Asset_A
                    dl Asset_B

                LoadAssets:
                    LDA.W #Asset_A
                    LDA.W #Asset_B>>8&${'$'}FF00
                    JSL Asset_B
                    db "Asset_A"
            """.trimIndent() + "\n",
        )

        val indexed = AsmManagedAssets.pointerBindings(sources, setOf("Asset_A", "Asset_B", "Missing"))

        assertEquals(AsmManagedAssets.pointerBindings(sources, "Asset_A"), indexed["Asset_A"])
        assertEquals(null, indexed["Asset_B"], "an unsupported executable consumer must fail only its label")
        assertEquals(null, indexed["Missing"])
        assertEquals(
            setOf(AsmManagedPointerKind.LONG24, AsmManagedPointerKind.IMMEDIATE_LOW16),
            requireNotNull(indexed["Asset_A"]).mapTo(linkedSetOf(), AsmManagedPointerBinding::kind),
        )
    }

    @Test
    fun `native cinematic map consumers have complete supported relocation shapes`() {
        val sources = readAsmSourceTree(File(TestRomHelper.requireDisassemblyDir(), "src"))
        val expectedOccurrences = mapOf("96FE69" to 3)

        CinematicMapAssets.SUPPORTED.forEach { map ->
            val bindings = assertNotNull(
                AsmManagedAssets.pointerBindings(sources, map.sourceLabel),
                map.sourceLabel,
            )
            val expected = expectedOccurrences[map.key] ?: 1
            assertEquals(
                setOf(AsmManagedPointerKind.IMMEDIATE_BANK16, AsmManagedPointerKind.IMMEDIATE_LOW16),
                bindings.mapTo(linkedSetOf(), AsmManagedPointerBinding::kind),
                map.sourceLabel,
            )
            assertEquals(expected, bindings.single { it.kind == AsmManagedPointerKind.IMMEDIATE_BANK16 }.occurrences)
            assertEquals(expected, bindings.single { it.kind == AsmManagedPointerKind.IMMEDIATE_LOW16 }.occurrences)
        }
    }
}

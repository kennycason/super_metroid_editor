package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomVerifiedFreeRange
import com.supermetroid.editor.ui.asmCheckedHookContextMenuItems
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AsmCheckedHookTest {
    @TempDir
    lateinit var temporary: File

    @Test
    fun `checked hook context action is available only for native project source`() {
        var invoked = false
        assertEquals(emptyList(), asmCheckedHookContextMenuItems(false) { invoked = true })
        val action = asmCheckedHookContextMenuItems(true) { invoked = true }.single()
        assertEquals("Create Checked Hook…", action.label)
        action.onClick()
        assertTrue(invoked)
    }

    @Test
    fun `planner selects complete instructions and prefers expanded verified space`() {
        val rom = ByteArray(0x400000)
        val native = byteArrayOf(0xAD.toByte(), 0x34, 0x12, 0xC9.toByte(), 0x01, 0x00)
        native.copyInto(rom, 0)
        val symbols = AsmCompiledSymbols(
            labels = emptyMap(),
            lineAddressesByFile = mapOf("bank_80.asm" to intArrayOf(0x808000, 0, 0x808003, 1)),
        )
        val plan = AsmRomSpacePlan(
            romBodySize = rom.size,
            banks = listOf(
                AsmRomSpaceBank(0x90, 0x80000, 0x8000, 0x100, 0x100, 0xFF, emptyList(), emptyList(),
                    rangesAfter = listOf(AsmRomSpaceRange(0x87F00, 0x100, 0xFF, "native fragment"))),
                AsmRomSpaceBank(0xE0, 0x300000, 0x8000, 0x7FFE, 0x7FFE, 0xFF, emptyList(), emptyList(),
                    rangesAfter = listOf(AsmRomSpaceRange(0x300002, 0x7FFE, 0xFF, "expanded bank E0"))),
            ),
        )

        val candidate = AsmCheckedHookPlanner.prepare(
            fileId = "bank_80.asm",
            lineIndex = 0,
            text = "LDA.W ${'$'}1234\nCMP.W #${'$'}0001\nNOP",
            symbols = symbols,
            romParser = RomParser(rom),
            spacePlan = plan,
        )

        assertEquals(0x808000, candidate.hookAddress)
        assertEquals(0x808006, candidate.continuationAddress)
        assertEquals(0xE08002, candidate.helperAddress)
        assertContentEquals(native, candidate.nativeBytes)
        assertEquals(listOf("LDA.W ${'$'}1234", "CMP.W #${'$'}0001"), candidate.displacedInstructions)
        val source = candidate.renderModule("energy_guard")
        assertContains(source, "JML.L SMEDIT_EnergyGuard_808000_Helper")
        assertEquals(2, source.lineSequence().count { it.trim() == "NOP" })
        assertEquals(1, AsmCheckedHookContract.parseAll(source).size)
        assertFailsWith<IllegalArgumentException> {
            AsmCheckedHookContract.parseAll(source.lineSequence().filterNot { "@smedit-checked-hook-v1" in it }.joinToString("\n"))
        }
    }

    @Test
    fun `planner rejects unsafe control flow and interior labels`() {
        val parser = RomParser(ByteArray(0x400000))
        val space = AsmRomSpacePlan(0x400000, listOf(
            AsmRomSpaceBank(0xE0, 0x300000, 0x8000, 100, 100, 0xFF, emptyList(), emptyList(),
                rangesAfter = listOf(AsmRomSpaceRange(0x300002, 100, 0xFF, "expanded"))),
        ))
        assertFailsWith<IllegalArgumentException> {
            AsmCheckedHookPlanner.prepare(
                "bank_80.asm", 0, "BRA .later\nNOP\n.later:\nNOP",
                AsmCompiledSymbols(emptyMap(), mapOf("bank_80.asm" to intArrayOf(0x808000, 0, 0x808002, 1))),
                parser, space,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AsmCheckedHookPlanner.prepare(
                "bank_80.asm", 0, "LDA.W ${'$'}1234\n.middle:\nNOP",
                AsmCompiledSymbols(emptyMap(), mapOf("bank_80.asm" to intArrayOf(0x808000, 0, 0x808003, 2))),
                parser, space,
            )
        }
    }

    @Test
    fun `validator grants complete ownership only to an exact checked detour`() {
        val hook = 0x808000
        val helper = 0xE08002
        val native = byteArrayOf(0xAD.toByte(), 0x34, 0x12, 0xEA.toByte())
        val candidate = AsmCheckedHookCandidate(
            "bank_80.asm", 0, hook, hook + native.size, helper, helper + 0x100,
            native, listOf("LDA.W ${'$'}1234", "NOP"),
        )
        val contract = AsmCheckedHookContract.parseAll(candidate.renderModule("guard")).single()
        val immutable = ByteArray(0x400000) { -1 }
        val baseline = immutable.copyOf()
        val isolated = immutable.copyOf()
        val hookPc = checkNotNull(snesLoRomToPc(hook))
        val helperPc = checkNotNull(snesLoRomToPc(helper))
        native.copyInto(immutable, hookPc)
        native.copyInto(baseline, hookPc)
        byteArrayOf(0x5C, 0x02, 0x80.toByte(), 0xE0.toByte()).copyInto(isolated, hookPc)
        native.copyInto(isolated, helperPc)
        isolated[helperPc + native.size] = 0xEA.toByte()
        byteArrayOf(0x5C, 0x04, 0x80.toByte(), 0x80.toByte()).copyInto(isolated, helperPc + native.size + 1)
        val helperEnd = helper + native.size + 5
        val symbols = """
            [labels]
            80:8000 SMEDIT_Guard_808000_Hook
            E0:8002 SMEDIT_Guard_808000_Helper
            E0:${(helperEnd and 0xFFFF).toString(16).padStart(4, '0')} SMEDIT_Guard_808000_Helper_End
        """.trimIndent().toByteArray()
        val changed = listOf(
            AsmSourceOwnedRange(hookPc, native.size, "project/guard.asm"),
            AsmSourceOwnedRange(helperPc, native.size + 5, "project/guard.asm"),
        )

        val ownership = AsmCheckedHookValidator.validate(
            "project/guard.asm", listOf(contract), immutable, baseline, isolated, symbols,
            listOf(RomVerifiedFreeRange(0xE0, helperPc, helperPc + 0x100, 0xFF, "expanded")), changed,
        )
        assertEquals(changed.map { it.pcOffset to it.length }, ownership.map { it.pcOffset to it.length })

        assertFailsWith<IllegalArgumentException> {
            AsmCheckedHookValidator.validate(
                "project/guard.asm", listOf(contract), immutable, baseline, isolated, symbols,
                listOf(RomVerifiedFreeRange(0xE0, helperPc, helperPc + 0x100, 0xFF, "expanded")),
                changed + AsmSourceOwnedRange(0x100, 1, "project/guard.asm"),
            )
        }
    }

    @Test
    fun `generated checked hook compiles and owns its complete declared spans`() {
        AsmProjectCompiler.clearBuildCacheForTests()
        val fixture = AsmProjectTestFixture.create(temporary, AsmSourceAugmentation.NONE)
        val repository = AsmProjectWorkspaceRepository(starterModules = emptyList(), augmentation = AsmSourceAugmentation.NONE)
        val parser = RomParser(fixture.rom)
        val hook = 0x90EA7F
        val native = ByteArray(6) { parser.readByteAt(parser.snesToPc(hook) + it).toByte() }
        val candidate = AsmCheckedHookCandidate(
            sourceFileId = "bank_90.asm",
            sourceLineIndex = 0,
            hookAddress = hook,
            continuationAddress = hook + 6,
            helperAddress = 0xE08002,
            helperLimitAddress = 0xE08402,
            nativeBytes = native,
            displacedInstructions = listOf("LDA.W Energy", "CMP.W #${'$'}001F"),
        )
        val (_, fileId) = repository.createModule(
            fixture.projectFile.path,
            "generated_checked_hook",
            initialSource = candidate.renderModule("generated_checked_hook"),
        )

        val compiled = AsmProjectCompiler(repository).compile(
            fixture.projectFile.path,
            fixture.rom,
            outputRomSize = RomConstants.EXPANDED_ROM_SIZE,
        )
        val claims = compiled.sourceOwnedRanges.filter { it.sourceFileId == fileId }
        assertEquals(2, claims.size)
        assertEquals(native.size, claims.single { it.pcOffset == checkNotNull(snesLoRomToPc(hook)) }.length)
        assertTrue(claims.any { it.pcOffset == checkNotNull(snesLoRomToPc(0xE08002)) })
        AsmProjectCompiler.clearBuildCacheForTests()
    }
}

package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomVerifiedFreeRange
import com.supermetroid.editor.rom.RomSegmentKind
import com.supermetroid.editor.ui.asmCheckedHookContextMenuItems
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
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
        val contract = AsmCheckedHookContract.parseAll(source).single()
        assertTrue(contract.managed)
        assertEquals((0x80..0xFF).toList(), contract.managedBanks)
        assertFailsWith<IllegalArgumentException> {
            AsmCheckedHookContract.parseAll(source.lineSequence().filterNot { "@smedit-checked-hook-v2" in it }.joinToString("\n"))
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
        assertEquals(1, claims.size)
        assertEquals(native.size, claims.single { it.pcOffset == checkNotNull(snesLoRomToPc(hook)) }.length)
        val helper = compiled.segmentPlacements.single { it.request.kind == RomSegmentKind.HOOK_HELPER }
        assertEquals("asm-module:generated_checked_hook", helper.request.owner)
        assertTrue(helper.allocation.snesAddress in 0xE08000..0xE0FFFF)
        assertTrue(compiled.sourceOwnedRanges.none {
            helper.allocation.pcOffset < it.pcOffset + it.length &&
                it.pcOffset < helper.allocation.pcOffset + helper.allocation.size
        })
        AsmProjectCompiler.clearBuildCacheForTests()
    }

    @Test
    fun `managed helpers grow repack and rebuild from intent instead of persisted seeds`() {
        AsmProjectCompiler.clearBuildCacheForTests()
        val rom = ByteArray(RomConstants.ROM_SIZE) { 0xFF.toByte() }
        val firstHook = 0x808000
        val secondHook = 0x808010
        val firstNative = byteArrayOf(0xAD.toByte(), 0x34, 0x12, 0xEA.toByte())
        val secondNative = byteArrayOf(0x8D.toByte(), 0x34, 0x12, 0xEA.toByte())
        firstNative.copyInto(rom, checkNotNull(snesLoRomToPc(firstHook)))
        secondNative.copyInto(rom, checkNotNull(snesLoRomToPc(secondHook)))
        val fixture = AsmProjectTestFixture.synthetic(temporary, rom)
        val repository = AsmProjectWorkspaceRepository(
            starterModules = emptyList(),
            augmentation = AsmSourceAugmentation.NONE,
        )
        val bank8F = File(fixture.workspace.workingDirectory, "src/$PLM_SOURCE_FILE").readText()
        repository.saveSource(
            fixture.projectFile.path,
            PLM_SOURCE_FILE,
            bank8F + """

macro SmeditSelectors_91F8_Condition_74657374()
    ; smedit:managed-predicate-v1
    clc
    rts
endmacro
""",
            bank8F,
        )
        val first = AsmCheckedHookCandidate(
            "bank_80.asm", 0, firstHook, firstHook + firstNative.size, 0xE08002, 0xE08402,
            firstNative, listOf("LDA.W ${'$'}1234", "NOP"),
        ).renderModule("first_helper")
        val second = AsmCheckedHookCandidate(
            "bank_80.asm", 0, secondHook, secondHook + secondNative.size, 0xE08002, 0xE08402,
            secondNative, listOf("STA.W ${'$'}1234", "NOP"),
        ).renderModule("second_helper")
        val initialSource = first + "\n\n" + second
        val (_, fileId) = repository.createModule(
            fixture.projectFile.path,
            "managed_helpers",
            initialSource = initialSource,
        )
        val compiler = AsmProjectCompiler(repository)

        val initial = compiler.compile(
            fixture.projectFile.path,
            fixture.rom,
            outputRomSize = RomConstants.EXPANDED_ROM_SIZE,
        )
        val initialHelpers = initial.segmentPlacements
            .filter { it.request.kind == RomSegmentKind.HOOK_HELPER }
            .associateBy { it.request.label }
        assertEquals(2, initialHelpers.size)
        assertEquals(1, initial.segmentPlacements.count { it.request.kind == RomSegmentKind.MANAGED_CODE })
        val firstLabel = "Checked-hook helper · SMEDIT_FirstHelper_808000_Helper"
        val secondLabel = "Checked-hook helper · SMEDIT_SecondHelper_808010_Helper"
        val initialFirst = initialHelpers.getValue(firstLabel)
        val initialSecond = initialHelpers.getValue(secondLabel)
        assertTrue(initialFirst.allocation.pcOffset + initialFirst.allocation.size <= initialSecond.allocation.pcOffset)

        val addedCode = List(32) { "    NOP" }.joinToString("\n")
        val grownSource = initialSource
            .replaceFirst(
                "    ; Add custom code here. Keep the final JML and boundary labels intact.",
                "    ; Add custom code here. Keep the final JML and boundary labels intact.\n$addedCode",
            )
            // These are only creation-time probe hints. A clean build must not
            // treat either persisted address as the helper's placement.
            .replaceFirst("seed=${'$'}E08002 probe_limit=${'$'}E08402", "seed=${'$'}FF9000 probe_limit=${'$'}FF9400")
            .replaceFirst("org ${'$'}E08002", "org ${'$'}FF9000")
            .replaceFirst("warnpc ${'$'}E08402", "warnpc ${'$'}FF9400")
        repository.saveSource(fixture.projectFile.path, fileId, grownSource, initialSource)
        AsmProjectCompiler.clearBuildCacheForTests()

        val grown = compiler.compile(
            fixture.projectFile.path,
            fixture.rom,
            outputRomSize = RomConstants.EXPANDED_ROM_SIZE,
        )
        val grownHelpers = grown.segmentPlacements
            .filter { it.request.kind == RomSegmentKind.HOOK_HELPER }
            .associateBy { it.request.label }
        val grownFirst = grownHelpers.getValue(firstLabel)
        val grownSecond = grownHelpers.getValue(secondLabel)
        assertEquals(initialFirst.request.size + 32, grownFirst.request.size)
        assertEquals(initialFirst.allocation.snesAddress, grownFirst.allocation.snesAddress)
        assertNotEquals(0xFF9000, grownFirst.allocation.snesAddress)
        assertNotEquals(initialSecond.allocation.snesAddress, grownSecond.allocation.snesAddress)
        assertTrue(grownFirst.allocation.pcOffset + grownFirst.allocation.size <= grownSecond.allocation.pcOffset)

        AsmProjectCompiler.clearBuildCacheForTests()
        val cold = compiler.compile(
            fixture.projectFile.path,
            fixture.rom,
            outputRomSize = RomConstants.EXPANDED_ROM_SIZE,
        )
        val coldHelpers = cold.segmentPlacements
            .filter { it.request.kind == RomSegmentKind.HOOK_HELPER }
            .associateBy { it.request.label }
        assertEquals(
            grownHelpers.mapValues { (_, placement) -> placement.request.size to placement.allocation.snesAddress },
            coldHelpers.mapValues { (_, placement) -> placement.request.size to placement.allocation.snesAddress },
        )
        assertContentEquals(grown.romBytes, cold.romBytes)
        AsmProjectCompiler.clearBuildCacheForTests()
    }
}

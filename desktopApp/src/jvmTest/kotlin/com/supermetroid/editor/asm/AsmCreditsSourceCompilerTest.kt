package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectCreditsGroup
import com.supermetroid.editor.rom.CreditsData
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AsmCreditsSourceCompilerTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `managed credits survive audited build and cache-free cold build`() {
        val fixture = fixture("cold")
        val parser = RomParser(fixture.rom)
        val groups = listOf(
            ProjectCreditsGroup("SMEDIT TEAM", mutableListOf("SAMUS ARAN", "BABY METROID")),
        )
        val repository = AsmProjectWorkspaceRepository()
        repository.createModule(
            fixture.projectFile.path,
            AsmCreditsSourceModule.FILE.removePrefix(ASM_PROJECT_MODULE_PREFIX),
            initialSource = AsmCreditsSourceModule.render(parser, groups),
        )

        val compiled = AsmProjectCompiler(repository).compile(fixture.projectFile.path, fixture.rom)
        val parsed = assertNotNull(CreditsData.parseCredits(RomParser(compiled.romBytes)))
        assertEquals("SMEDIT TEAM", parsed.sections.last().header)
        assertEquals(listOf("SAMUS ARAN", "BABY METROID"), parsed.sections.last().names)
        assertEquals(AsmCreditsSourceModule.INST_LIST_SNES, parsed.instListSnesAddr)
        assertTrue(compiled.sourceOwnedRanges.any { it.sourceFileId == AsmCreditsSourceModule.FILE })

        AsmProjectCompiler.clearBuildCacheForTests()
        val cold = AsmProjectCompiler(AsmProjectWorkspaceRepository()).compile(
            fixture.projectFile.path,
            fixture.rom,
        )
        assertContentEquals(compiled.romBytes, cold.romBytes)
    }

    @Test
    fun `another module cannot collide with credits tilemap ownership`() {
        val fixture = fixture("collision")
        val parser = RomParser(fixture.rom)
        val repository = AsmProjectWorkspaceRepository()
        val groups = listOf(ProjectCreditsGroup("TEAM", mutableListOf("SAMUS")))
        val export = requireNotNull(CreditsData.buildCreditsExport(
            parser,
            groups.map { CreditsData.CreditGroup(it.header, it.names) },
            AsmCreditsSourceModule.INST_LIST_SNES,
        ))
        repository.createModule(
            fixture.projectFile.path,
            AsmCreditsSourceModule.FILE.removePrefix(ASM_PROJECT_MODULE_PREFIX),
            initialSource = AsmCreditsSourceModule.render(
                parser,
                groups,
            ),
        )
        val tilemapPc = parser.snesToPc(CreditsData.CREDITS_TILEMAP_SNES)
        val changedOffset = export.tilemapBytes.indices.first { offset ->
            fixture.rom[tilemapPc + offset] != export.tilemapBytes[offset]
        }
        val conflictingByte = (export.tilemapBytes[changedOffset].toInt() xor 0xFF) and 0xFF
        repository.createModule(
            fixture.projectFile.path,
            "credits_collision.asm",
            initialSource = "org \$${(CreditsData.CREDITS_TILEMAP_SNES + changedOffset).toString(16).uppercase()}\n" +
                "db \$${conflictingByte.toString(16).uppercase().padStart(2, '0')}\n",
        )

        val failure = assertFailsWith<AsmCompilationException> {
            AsmProjectCompiler(repository).compile(fixture.projectFile.path, fixture.rom)
        }
        assertTrue(
            failure.message.orEmpty().contains("overlap", ignoreCase = true) ||
                failure.message.orEmpty().contains("changes bytes", ignoreCase = true) ||
                failure.message.orEmpty().contains("both write", ignoreCase = true),
            failure.message,
        )
    }

    private fun fixture(name: String): AsmProjectTestFixture {
        val rom = TestRomHelper.requireRomBytes().copyOf()
        listOf(0x83F000, 0x8FF000, 0xC0F000).forEach { address ->
            val start = requireNotNull(snesLoRomToPc(address))
            rom.fill(0xFF.toByte(), start, start + 0x1000)
        }
        return AsmProjectTestFixture.synthetic(
            File(temporary, name).apply { mkdirs() },
            rom,
        )
    }
}

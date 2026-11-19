package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.TextCategory
import com.supermetroid.editor.rom.TextData
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmIntroStoryTextBridgeTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `real source resolves all English pages and visual edit changes glyphs only`() {
        val fixture = AsmProjectTestFixture.create(File(temporary, "resolve"))
        val entries = TextData.readAllText(fixture.rom).filter { it.category == TextCategory.INTRO_STORY }.take(6)
        val bridge = AsmIntroStoryTextBridge()
        val accesses = bridge.accessAll(fixture.projectFile.path, entries)

        assertEquals(6, accesses.size)
        entries.forEach { entry ->
            val access = assertIs<AsmIntroStoryTextAccess.Editable>(accesses.getValue(entry.id))
            assertEquals(entry.text, access.text)
            assertContentEquals(entry.rawBytes, access.rawData)
            assertFalse(access.modified)
            assertEquals(entry.maxLength, access.lineCapacities.sum())
        }

        val page = entries.last()
        val before = assertIs<AsmIntroStoryTextAccess.Editable>(accesses.getValue(page.id))
        val updatedText = "CERES STATION WAS UNDER\nDANGER!!"
        val after = bridge.save(fixture.projectFile.path, page, updatedText, before.snapshot())

        assertEquals(updatedText, after.text)
        assertTrue(after.modified)
        assertTrue(after.sourceText.contains("; DANGER!!"))
        assertTrue(after.sourceText.contains("IndirectInstructions_IntroText_D"))
        val changedOffsets = before.rawData.indices.filter { before.rawData[it] != after.rawData[it] }
        assertTrue(changedOffsets.isNotEmpty())
        assertTrue(changedOffsets.all { it % TextData.GREEN_TEXT_RECORD_SIZE in 4..5 })
        assertEquals(
            controlBytes(before.rawData),
            controlBytes(after.rawData),
            "Timing, positions, and page controls must remain byte-identical",
        )
        val sourceBackedPage = page.copy(text = after.text, rawBytes = after.rawData.copyOf())
        assertIs<AsmIntroStoryTextAccess.Editable>(
            bridge.accessAll(fixture.projectFile.path, listOf(sourceBackedPage)).getValue(page.id),
        )
        val glyphOffset = TextData.GREEN_TEXT_RECORD_SIZE + 4
        val unrelatedBytes = after.rawData.copyOf().also {
            it[glyphOffset] = (it[glyphOffset].toInt() xor 0x01).toByte()
        }
        assertIs<AsmIntroStoryTextAccess.AsmOnly>(
            bridge.accessAll(fixture.projectFile.path, listOf(page.copy(rawBytes = unrelatedBytes))).getValue(page.id),
        )

        val restored = bridge.restore(fixture.projectFile.path, page, after.snapshot())
        assertEquals(page.text, restored.text)
        assertContentEquals(page.rawBytes, restored.rawData)
        assertFalse(restored.modified)
    }

    @Test
    fun `line geometry capacity and source conflicts fail closed`() {
        val fixture = AsmProjectTestFixture.create(File(temporary, "guards"))
        val page = TextData.readAllText(fixture.rom)
            .first { it.category == TextCategory.INTRO_STORY && it.id == "intro_5" }
        val repository = AsmProjectWorkspaceRepository()
        val bridge = AsmIntroStoryTextBridge(repository)
        val access = assertIs<AsmIntroStoryTextAccess.Editable>(
            bridge.accessAll(fixture.projectFile.path, listOf(page)).getValue(page.id),
        )

        val directSource = access.sourceText.lineSequence().joinToString("\n") { line ->
            if (";8CD51D;" in line) line.replace("IntroText_C", "IntroText_D") else line
        }
        repository.persistSource(fixture.projectFile.path, access.sourceFileId, directSource, access.sourceText)
        val direct = assertIs<AsmIntroStoryTextAccess.Editable>(
            bridge.accessAll(fixture.projectFile.path, listOf(page)).getValue(page.id),
        )
        assertTrue(direct.text.startsWith("DERES STATION"), "The symbolic glyph is authoritative even before comments are refreshed")
        repository.persistSource(fixture.projectFile.path, access.sourceFileId, access.sourceText, directSource)

        assertFailsWith<IllegalArgumentException> {
            bridge.save(fixture.projectFile.path, page, "ONE LINE", access.snapshot())
        }
        assertFailsWith<IllegalArgumentException> {
            bridge.save(fixture.projectFile.path, page, "CERES STATION WAS UNDER\nTHIS LINE IS TOO LONG", access.snapshot())
        }

        val pageStart = access.sourceText.indexOf("${access.sourceLabel}:")
        val timingStart = access.sourceText.indexOf("dw \$0005*!FPS", startIndex = pageStart)
        assertTrue(pageStart >= 0 && timingStart >= pageStart)
        val custom = access.sourceText.replaceRange(
            timingStart,
            timingStart + "dw \$0005*!FPS".length,
            "dw CUSTOM_INTRO_DELAY",
        )
        repository.persistSource(fixture.projectFile.path, access.sourceFileId, custom, access.sourceText)
        val asmOnly = assertIs<AsmIntroStoryTextAccess.AsmOnly>(
            bridge.accessAll(fixture.projectFile.path, listOf(page)).getValue(page.id),
        )
        assertTrue(asmOnly.reason.contains("timing"))

        repository.persistSource(fixture.projectFile.path, access.sourceFileId, access.sourceText, custom)
        val newer = access.sourceText + "\n; newer durable edit\n"
        repository.persistSource(fixture.projectFile.path, access.sourceFileId, newer, access.sourceText)
        assertFailsWith<IllegalArgumentException> {
            bridge.save(fixture.projectFile.path, page, "CERES STATION WAS UNDER\nDANGER!!", access.snapshot())
        }
    }

    private fun controlBytes(bytes: ByteArray): List<Int> = bytes.indices
        .filter { it % TextData.GREEN_TEXT_RECORD_SIZE !in 4..5 }
        .map { bytes[it].toInt() and 0xFF }
}

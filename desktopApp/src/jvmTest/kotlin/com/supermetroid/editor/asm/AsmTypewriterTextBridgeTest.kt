package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TextCategory
import com.supermetroid.editor.rom.TextData
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmTypewriterTextBridgeTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `native typewriter streams save reopen and restore controls`() {
        val fixture = AsmProjectTestFixture.create(File(temporary, "fixed"))
        val parser = RomParser(fixture.rom)
        val entries = TextData.readAllText(parser.getRomData()).filter { it.category == TextCategory.ESCAPE_TEXT }
        val bridge = AsmTypewriterTextBridge()
        val accesses = bridge.accessAll(fixture.projectFile.path, entries)

        assertTrue(entries.size == 2)
        entries.forEach { entry ->
            val access = assertIs<AsmTypewriterTextAccess.Editable>(accesses.getValue(entry.id))
            assertContentEquals(entry.rawBytes, access.rawData, entry.label)
            assertTrue(entry.text == access.text, entry.label)
            assertTrue(access.relocation != null, entry.label)
        }

        val entry = entries.first { it.id == "ceres_escape" }
        val opened = assertIs<AsmTypewriterTextAccess.Editable>(accesses.getValue(entry.id))
        val originalControls = requireNotNull(TextData.decodeTypewriterStream(opened.rawData))
        val saved = bridge.save(
            fixture.projectFile.path,
            entry,
            "ALERT\nEVACUATE\nNOW!",
            opened.snapshot(),
        )
        val savedControls = requireNotNull(TextData.decodeTypewriterStream(saved.rawData))
        assertTrue(saved.modified)
        assertTrue(saved.text == "ALERT\nEVACUATE\nNOW!")
        assertTrue(originalControls.timerReset == savedControls.timerReset)
        assertTrue(originalControls.lines.map { it.vramAddress } == savedControls.lines.map { it.vramAddress })
        assertTrue(originalControls.lines.map { it.text.length } == savedControls.lines.map { it.text.length })

        val reopened = assertIs<AsmTypewriterTextAccess.Editable>(
            AsmTypewriterTextBridge().accessAll(fixture.projectFile.path, listOf(entry)).getValue(entry.id),
        )
        assertContentEquals(saved.rawData, reopened.rawData)
        val restored = bridge.restore(fixture.projectFile.path, entry, saved.snapshot())
        assertFalse(restored.modified)
        assertContentEquals(entry.rawBytes, restored.rawData)
    }

    @Test
    fun `longer typewriter stream becomes same-bank managed data`() {
        val fixture = AsmProjectTestFixture.create(File(temporary, "relocated"))
        val entry = TextData.readAllText(fixture.rom).first { it.id == "tourian_escape" }
        val bridge = AsmTypewriterTextBridge()
        val opened = assertIs<AsmTypewriterTextAccess.Editable>(
            bridge.accessAll(fixture.projectFile.path, listOf(entry)).getValue(entry.id),
        )
        val text = "TIME BOMB HAS BEEN SET!\nESCAPE FROM ZEBES IMMEDIATELY!"
        val grown = TextData.encodeTypewriterText(text, opened.rawData, preserveLineSlots = false)
        assertTrue(grown.size > opened.capacity)
        assertFailsWith<AsmAssetRelocationRequiredException> {
            bridge.save(fixture.projectFile.path, entry, text, opened.snapshot())
        }

        val relocated = bridge.relocate(fixture.projectFile.path, entry, grown, opened.snapshot())

        assertTrue(relocated.managedAssetId != null)
        assertTrue(relocated.text == text)
        val record = AsmProjectWorkspaceRepository().managedAssets(fixture.projectFile.path).single()
        assertTrue(record.inlineSource)
        assertTrue(record.kind == "typewriter-text")
        assertTrue(record.allowedBanks == listOf(0xA6))
        assertTrue(record.pointerBindings.all { it.kind == AsmManagedPointerKind.IMMEDIATE_LOW16 })
        assertTrue(AsmManagedAssets.markerRegex(record).containsMatchIn(relocated.sourceText))

        val restored = bridge.restore(fixture.projectFile.path, entry, relocated.snapshot())
        assertFalse(restored.modified)
        assertContentEquals(entry.rawBytes, restored.rawData)
    }

    @Test
    fun `custom typewriter controls fail closed`() {
        val fixture = AsmProjectTestFixture.create(File(temporary, "custom"))
        val entry = TextData.readAllText(fixture.rom).first { it.id == "ceres_escape" }
        val bridge = AsmTypewriterTextBridge()
        val opened = assertIs<AsmTypewriterTextAccess.Editable>(
            bridge.accessAll(fixture.projectFile.path, listOf(entry)).getValue(entry.id),
        )
        val custom = opened.sourceText.replaceFirst("dw \$0001,\$0002", "dw \$0001,CustomTimer")
        assertTrue(custom != opened.sourceText)
        AsmProjectWorkspaceRepository().persistSource(
            fixture.projectFile.path,
            opened.sourceFileId,
            custom,
            opened.sourceText,
        )

        assertIs<AsmTypewriterTextAccess.AsmOnly>(
            bridge.accessAll(fixture.projectFile.path, listOf(entry)).getValue(entry.id),
        )
    }
}

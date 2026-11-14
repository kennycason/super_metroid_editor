package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.TestRomHelper
import com.supermetroid.editor.rom.TextCategory
import com.supermetroid.editor.rom.TextData
import com.supermetroid.editor.rom.TextEntry
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmTextSourceBridgeTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `fixed tilemap field saves reopens and restores only its literal words`() {
        val fixture = fixture()
        val opened = assertIs<AsmTextSourceAccess.Editable>(
            fixture.bridge.accessAll(fixture.project.path, listOf(fixture.entry)).getValue(fixture.entry.id),
        )
        assertFalse(opened.modified)
        assertTrue(opened.text == "ABC")

        val saved = fixture.bridge.save(
            fixture.project.path,
            fixture.entry,
            "XYZ",
            opened.sourceText,
        )
        assertTrue(saved.modified)
        assertTrue(saved.text == "XYZ")
        val source = File(fixture.workspace.workingDirectory, "src/bank_85.asm").readText()
        assertTrue("\$3CF7,\$3CF8,\$3CF9" in source)
        assertTrue(source.contains("dw \$1111,\$2222,"), "Words outside the visual field must survive")

        assertFailsWith<IllegalArgumentException> {
            fixture.bridge.save(fixture.project.path, fixture.entry, "ABC", opened.sourceText)
        }
        val reopened = AsmTextSourceBridge(fixture.repository).accessAll(
            fixture.project.path,
            listOf(fixture.entry),
        ).getValue(fixture.entry.id)
        assertTrue(assertIs<AsmTextSourceAccess.Editable>(reopened).text == "XYZ")

        val restored = fixture.bridge.restore(
            fixture.project.path,
            fixture.entry,
            saved.sourceText,
        )
        assertFalse(restored.modified)
        assertTrue(restored.text == "ABC")
    }

    @Test
    fun `custom fixed text expression fails closed`() {
        val fixture = fixture()
        val source = File(fixture.workspace.workingDirectory, "src/bank_85.asm")
        val custom = source.readText().replace("\$3CE1", "SomeGlyph")
        source.writeText(custom)
        File(fixture.workspace.root, "overrides/src/bank_85.asm").apply {
            parentFile.mkdirs()
            writeText(custom)
        }

        val access = fixture.bridge.accessAll(fixture.project.path, listOf(fixture.entry)).getValue(fixture.entry.id)

        assertIs<AsmTextSourceAccess.AsmOnly>(access)
    }

    @Test
    fun `native fixed text catalog resolves every current visual field`() {
        val rom = TestRomHelper.requireRomBytes()
        val root = TestRomHelper.requireDisassemblyDir()
        val reference = AsmReferenceWorkspace(
            root,
            AsmReferenceMetadata(
                AsmReferenceContract.REPOSITORY_URL,
                AsmReferenceContract.COMMIT,
                "native-text-fixture",
                "SM.sfc",
                AsmVerifiedCache.hash(rom),
                rom.size,
                0,
            ),
            AsmSourceParser().parse(File(root, "src"), File(root, "data"), emptyList()),
        )
        val project = File(temporary, "native-project/Native.smedit").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        val repository = AsmProjectWorkspaceRepository(
            assetRanges = emptyList(),
            starterModules = emptyList(),
            augmentation = AsmSourceAugmentation.NONE,
        )
        repository.initialize(project.path, reference)
        val entries = TextData.readAllText(rom).filter {
            it.category in setOf(TextCategory.AREA_NAME, TextCategory.UI_MESSAGE, TextCategory.ITEM_NAME)
        }

        val accesses = AsmTextSourceBridge(repository).accessAll(project.path, entries)

        assertTrue(entries.size == 35)
        entries.forEach { entry ->
            val access = assertIs<AsmTextSourceAccess.Editable>(accesses.getValue(entry.id), entry.label)
            assertContentEquals(entry.rawBytes, access.rawData, entry.label)
            assertTrue(entry.text == access.text, entry.label)
        }
    }

    private data class Fixture(
        val project: File,
        val workspace: AsmProjectWorkspace,
        val repository: AsmProjectWorkspaceRepository,
        val bridge: AsmTextSourceBridge,
        val entry: TextEntry,
    )

    private fun fixture(): Fixture {
        val root = File(temporary, "reference-${temporary.listFiles().orEmpty().size}")
        val source = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(source, "main.asm").writeText("incsrc bank_85.asm\n")
        val words = listOf(0x1111, 0x2222, 0x3CE0, 0x3CE1, 0x3CE2, 0x3C4E, 0x3C4E, 0x3C4E, 0x3333)
        File(source, "bank_85.asm").writeText(
            "    dw ${words.joinToString(",") { "\$${it.toString(16).uppercase().padStart(4, '0')}" }} ;858000;\n",
        )
        val reference = AsmReferenceWorkspace(
            root,
            AsmReferenceMetadata(
                AsmReferenceContract.REPOSITORY_URL,
                AsmReferenceContract.COMMIT,
                "fixture",
                "fixture.sfc",
                "ab".repeat(32),
                0x300000,
                0,
            ),
            AsmSourceParser().parse(source, data, emptyList()),
        )
        val project = File(temporary, "project-${temporary.listFiles().orEmpty().size}/Test.smedit").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        val repository = AsmProjectWorkspaceRepository(
            assetRanges = emptyList(),
            starterModules = emptyList(),
            augmentation = AsmSourceAugmentation.NONE,
        )
        val workspace = repository.initialize(project.path, reference)
        val raw = ByteArray(12)
        words.subList(2, 8).forEachIndexed { index, word ->
            raw[index * 2] = word.toByte()
            raw[index * 2 + 1] = (word ushr 8).toByte()
        }
        val entry = TextEntry(
            id = "item_test",
            label = "Test item",
            category = TextCategory.ITEM_NAME,
            pcOffset = 0x28004,
            snesAddress = 0x858004,
            maxLength = 6,
            text = "ABC",
            rawBytes = raw,
        )
        return Fixture(project, workspace, repository, AsmTextSourceBridge(repository), entry)
    }
}

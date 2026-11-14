package com.supermetroid.editor.asm

import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmNativeSpritePaletteBridgeTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `literal palette row saves reopens and restores without rewriting surrounding source`() {
        val fixture = fixture()
        val opened = assertIs<AsmNativeSpritePaletteAccess.Editable>(
            fixture.bridge.access(fixture.project.path, ADDRESS),
        )
        assertFalse(opened.modified)
        assertContentEquals(fixture.raw, opened.rawPalette)

        val edited = opened.rawPalette.copyOf().also {
            it[2] = 0x34
            it[3] = 0x12
        }
        val saved = fixture.bridge.save(fixture.project.path, ADDRESS, edited, opened.sourceText)
        assertTrue(saved.modified)
        assertContentEquals(edited, saved.rawPalette)
        val source = File(fixture.workspace.workingDirectory, "src/bank_8C.asm").readText()
        assertTrue(source.contains("dw \$0000, \$1234, \$0002"))
        assertTrue(source.contains("Unrelated_Routine:"))

        val reopened = AsmNativeSpritePaletteBridge(
            AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList()),
        ).access(fixture.project.path, ADDRESS)
        assertContentEquals(edited, assertIs<AsmNativeSpritePaletteAccess.Editable>(reopened).rawPalette)

        val restored = fixture.bridge.restore(fixture.project.path, ADDRESS, saved.sourceText)
        assertFalse(restored.modified)
        assertContentEquals(fixture.raw, restored.rawPalette)
        assertTrue(requireNotNull(fixture.repository.load(fixture.project.path)).modifiedFileIds.isEmpty())
    }

    @Test
    fun `palette lens fails closed for stale saves and custom expressions`() {
        val fixture = fixture()
        val opened = assertIs<AsmNativeSpritePaletteAccess.Editable>(
            fixture.bridge.access(fixture.project.path, ADDRESS),
        )
        val saved = fixture.bridge.save(
            fixture.project.path,
            ADDRESS,
            opened.rawPalette.copyOf().also { it[4] = 0x55 },
            opened.sourceText,
        )
        assertFailsWith<IllegalArgumentException> {
            fixture.bridge.save(fixture.project.path, ADDRESS, opened.rawPalette, opened.sourceText)
        }

        val sourceFile = File(fixture.workspace.workingDirectory, "src/bank_8C.asm")
        val custom = sourceFile.readText().replace("\$0003", "PaletteExpression")
        sourceFile.writeText(custom)
        File(fixture.workspace.root, "overrides/src/bank_8C.asm").apply {
            parentFile.mkdirs()
            writeText(custom)
        }
        val rejected = assertIs<AsmNativeSpritePaletteAccess.AsmOnly>(
            fixture.bridge.access(fixture.project.path, ADDRESS),
        )
        assertTrue(rejected.reason.contains("16-colour literal"))
        assertTrue(saved.modified)
    }

    @Test
    fun `palette lens fails closed when another directive emits inside the row`() {
        val fixture = fixture()
        val sourceFile = File(fixture.workspace.workingDirectory, "src/bank_8C.asm")
        val custom = sourceFile.readText().replace(
            ";8CE1E9;\n",
            ";8CE1E9;\n    db \$00\n",
        )
        sourceFile.writeText(custom)
        File(fixture.workspace.root, "overrides/src/bank_8C.asm").apply {
            parentFile.mkdirs()
            writeText(custom)
        }

        val rejected = assertIs<AsmNativeSpritePaletteAccess.AsmOnly>(
            fixture.bridge.access(fixture.project.path, ADDRESS),
        )

        assertTrue(rejected.reason.contains("16-colour literal"))
    }

    private data class Fixture(
        val project: File,
        val workspace: AsmProjectWorkspace,
        val repository: AsmProjectWorkspaceRepository,
        val bridge: AsmNativeSpritePaletteBridge,
        val raw: ByteArray,
    )

    private fun fixture(): Fixture {
        val root = File(temporary, "reference-${temporary.listFiles().orEmpty().size}")
        val source = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(source, "main.asm").writeText("incsrc bank_8C.asm\n")
        val words = (0 until 16).map { "\$${it.toString(16).uppercase().padStart(4, '0')}" }
        File(source, "bank_8C.asm").writeText(
            """
            Palette_Test:
                dw ${words.take(3).joinToString(", ")} ;8CE1E9;

            Palette_Test_middle:
                dw ${words.drop(3).take(5).joinToString(",")} ;8CE1EF;
            ; A label is allowed between contiguous literal chunks.
            Palette_Test_tail:
                dw ${words.drop(8).joinToString(", ")} ;8CE1F9;

            Unrelated_Routine:
                RTL ;8CE209;
            """.trimIndent() + "\n",
        )
        val metadata = AsmReferenceMetadata(
            AsmReferenceContract.REPOSITORY_URL,
            AsmReferenceContract.COMMIT,
            "fixture",
            "fixture.sfc",
            "ab".repeat(32),
            0x300000,
            0,
        )
        val reference = AsmReferenceWorkspace(root, metadata, AsmSourceParser().parse(source, data, emptyList()))
        val project = File(temporary, "project-${temporary.listFiles().orEmpty().size}/Test.smedit").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val workspace = repository.initialize(project.path, reference)
        val raw = ByteArray(32)
        for (index in 0 until 16) {
            raw[index * 2] = index.toByte()
        }
        return Fixture(project, workspace, repository, AsmNativeSpritePaletteBridge(repository), raw)
    }

    private companion object {
        const val ADDRESS = 0x8CE1E9
    }
}

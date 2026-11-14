package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.TextData
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmIntroStoryTextCompilerTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `intro glyph edit survives cold source build and restore`() {
        val fixture = AsmProjectTestFixture.create(temporary)
        val entry = TextData.readAllText(fixture.rom).first { it.id == "intro_5" }
        val repository = AsmProjectWorkspaceRepository()
        val bridge = AsmIntroStoryTextBridge(repository)
        val opened = assertIs<AsmIntroStoryTextAccess.Editable>(
            bridge.accessAll(fixture.projectFile.path, listOf(entry)).getValue(entry.id),
        )
        val saved = bridge.save(
            fixture.projectFile.path,
            entry,
            "CERES STATION WAS UNDER\nDANGER!!",
            opened.snapshot(),
        )

        val compiled = AsmProjectCompiler(repository).compile(fixture.projectFile.path, fixture.rom)
        assertContentEquals(
            saved.rawData,
            compiled.romBytes.copyOfRange(entry.pcOffset, entry.pcOffset + saved.rawData.size),
        )

        AsmProjectCompiler.clearBuildCacheForTests()
        val cold = AsmProjectCompiler(AsmProjectWorkspaceRepository()).compile(
            fixture.projectFile.path,
            fixture.rom,
        )
        assertContentEquals(compiled.romBytes, cold.romBytes)

        val restored = bridge.restore(fixture.projectFile.path, entry, saved.snapshot())
        assertFalse(restored.modified)
        val native = AsmProjectCompiler(repository).compile(fixture.projectFile.path, fixture.rom)
        assertContentEquals(
            entry.rawBytes,
            native.romBytes.copyOfRange(entry.pcOffset, entry.pcOffset + entry.rawBytes.size),
        )
    }
}

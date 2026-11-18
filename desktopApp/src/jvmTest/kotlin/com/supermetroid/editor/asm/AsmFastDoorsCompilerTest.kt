package com.supermetroid.editor.asm

import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class AsmFastDoorsCompilerTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `curated Fast Doors module survives a cache-free headered build with checked ownership`() {
        val fixture = AsmProjectTestFixture.create(directory)
        val project = SmEditProject(fixture.romFile.path).apply {
            asmWorkspace.enabled = true
            asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
            patches += PatchRepository.loadBundledPatches()
                .single { it.id == AsmFastDoorsModule.PATCH_ID }
                .copy(enabled = true)
        }
        AsmFastDoorsModule.prepare(project, fixture.projectFile.path)

        val repository = AsmProjectWorkspaceRepository()
        val workspace = requireNotNull(repository.loadForBuild(fixture.projectFile.path))
        val module = File(workspace.workingDirectory, "src/${AsmFastDoorsModule.FILE}")
        assertTrue(module.isFile)
        val authored = module.readText()

        try {
            AsmProjectCompiler.clearBuildCacheForTests()
            val first = AsmFastDoorsModule.protect(
                project,
                workspace,
                AsmProjectCompiler(repository).compile(fixture.projectFile.path, fixture.rom),
            )
            assertContains(first.curatedPatchIds, AsmFastDoorsModule.PATCH_ID)
            assertContentEquals(authored.toByteArray(), module.readBytes())

            AsmProjectCompiler.clearBuildCacheForTests()
            val header = ByteArray(512) { 0x5A }
            val cold = AsmFastDoorsModule.protect(
                project,
                workspace,
                AsmProjectCompiler(repository).compile(fixture.projectFile.path, header + fixture.rom),
            )
            assertContentEquals(first.romBytes, cold.romBytes.copyOfRange(header.size, cold.romBytes.size))
            assertContentEquals(header, cold.romBytes.copyOfRange(0, header.size))
            assertContentEquals(authored.toByteArray(), module.readBytes())
        } finally {
            AsmProjectCompiler.clearBuildCacheForTests()
        }
    }
}

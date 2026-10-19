package com.supermetroid.editor.asm

import java.io.File
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Public CI: actual packaged assembler, no game assets or copyrighted ROM required. */
@Tag("asm-toolchain")
class AsmBaselineUpdateSmokeTest {
    @TempDir lateinit var temporary: File

    @Test fun `baseline update rebinds a custom module and builds the reapplied edit with the packaged assembler`() {
        val resources = requireNotNull(System.getProperty("smedit.testPackagedResourcesDir"))
        val toolchain = AsmToolchain(File(temporary, "home"), emptyMap(), mapOf(
            "compose.application.resources.dir" to resources, "os.name" to System.getProperty("os.name"),
            "os.arch" to System.getProperty("os.arch"), "user.dir" to temporary.path))
        val referenceRoot = File(temporary, "reference")
        val src = File(referenceRoot, "src").apply { mkdirs() }
        val data = File(referenceRoot, "data").apply { mkdirs() }
        File(src, "main.asm").writeText("lorom\nincsrc bank_80.asm\nincsrc bank_8F.asm\n")
        File(src, "bank_80.asm").writeText("org \$808000\nEntry:\n RTL\nValue:\n dw \$000F\n")
        File(src, "bank_8F.asm").writeText("; No game data.\n")
        val rom = ByteArray(0x300000) { 0xFF.toByte() }.apply { this[0] = 0x6B; this[1] = 15; this[2] = 0 }
        val reference = AsmReferenceWorkspace(referenceRoot, AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL,
            AsmReferenceContract.COMMIT, "synthetic", "inert.sfc", AsmVerifiedCache.hash(rom), rom.size, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val old = AsmSourceAugmentation("old", renames = listOf(AsmSourceRename("Entry", "OldEntry")))
        val updated = AsmSourceAugmentation("updated", renames = listOf(AsmSourceRename("Entry", "NewEntry")),
            documentation = listOf(AsmSourceDocumentation("value", "bank_80.asm", "Value", "dw \$000F", listOf("A synthetic word."))))
        val repo = AsmProjectWorkspaceRepository(emptyList(), emptyList(), old)
        val project = File(temporary, "hack/Test.smedit").apply { parentFile.mkdirs(); writeText("{}") }
        val workspace = repo.initialize(project.path, reference)
        repo.persistSource(project.path, "bank_80.asm", File(workspace.workingDirectory, "src/bank_80.asm").readText().replace("\$000F", "\$002A"))
        repo.createModule(project.path, "objectives", initialSource = "org \$808300\nObjectives:\n JSL OldEntry\n RTL\n; Keep my notes\n")
        repo.createModule(project.path, "disabled", initialSource = "This is deliberately not assembly.\n")
        repo.setModuleEnabled(project.path, "project/disabled.asm", false)
        val originalBuild = AsmProjectCompiler(repo, toolchain).compile(project.path, rom)
        var validated = false
        val updater = AsmBaselineUpdateRepository(repo, updated, emptyList(), emptyList(), validateCandidate = { path, bytes ->
            // Synthetic fixtures do not carry real SNES checksum metadata; still run the exact compiler.
            val candidate = AsmProjectCompiler(repo, toolchain).compile(path, bytes)
            assertContentEquals(originalBuild.romBytes, candidate.romBytes)
            validated = true
        })
        val plan = updater.prepare(project.path, reference)
        assertEquals(0, plan.conflictCount)
        val result = updater.apply(plan, emptyMap(), rom)
        assertTrue(validated)
        assertEquals("updated", result.workspace.baselineRevision)
        assertEquals(setOf("project/disabled.asm"), result.workspace.disabledProjectModuleFileIds)
        val module = File(result.workspace.root, "modules/objectives.asm").readText()
        assertContains(module, "JSL NewEntry")
        assertContains(module, "; Keep my notes")
        val cold = AsmProjectCompiler(repo, toolchain).compile(project.path, ByteArray(512) { 0x55 } + rom)
        assertContentEquals(originalBuild.romBytes, cold.romBytes.copyOfRange(512, cold.romBytes.size))
        assertEquals(42, cold.romBytes[513].toInt() and 255)
        assertEquals("old", AsmSourceBaseline.load(File(result.backupDirectory, "asm"))?.augmentationRevision)
    }
}

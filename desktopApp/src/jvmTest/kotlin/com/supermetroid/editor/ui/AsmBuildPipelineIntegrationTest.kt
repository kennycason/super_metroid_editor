package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.asm.AsmPatchBackendRegistry
import com.supermetroid.editor.asm.AsmDiagnosticSeverity
import com.supermetroid.editor.asm.AsmSourceLinter
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import com.supermetroid.editor.rom.RomWriteKind
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AsmBuildPipelineIntegrationTest {
    @Test
    fun `ASM source base enters the same owned export transaction when local fixtures exist`() {
        ProjectFileService.clearAsmBuildCachesForTests()
        val projectFile = findWorkspaceFile(
            "projects/Super Metroid Sandbox/Super Metroid Sandbox.smedit"
        ) ?: return
        val rom = File(projectFile.parentFile, "Super Metroid Sandbox.smc").takeIf(File::isFile) ?: return
        val source = File(projectFile.parentFile, "Super Metroid Sandbox_smedit/asm/workspace/src/bank_90.asm")
        if (!source.isFile) return
        // The optional Sandbox is a mutable developer project. An intentional
        // syntax/literal error used to test the editor is not a valid pipeline
        // fixture and must not make unrelated repository tests fail.
        if (AsmSourceLinter.lintTree(source.parentFile).any { it.severity == AsmDiagnosticSeverity.ERROR }) return
        val parser = RomParser(rom.readBytes())
        val project = ProjectFileService.snapshotProject(ProjectFileService.loadProject(projectFile)).also {
            it.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
            val physics = it.patches.single { patch -> patch.configType == "samus_physics" }
            physics.enabled = true
            physics.configData = mutableMapOf("gravity" to 0x2C)
            val graphics = TileGraphics(parser)
            assertTrue(graphics.loadTileset(0))
            val replacement = requireNotNull(graphics.getRawVarGfx()).copyOf()
            replacement[0] = (replacement[0].toInt() xor 1).toByte()
            it.customGfx.varGfx["0"] = Base64.getEncoder().encodeToString(replacement)
        }
        val logs = mutableListOf<String>()

        val result = ProjectFileService.buildRom(
            project = project,
            romParser = parser,
            onLog = logs::add,
            onStatus = logs::add,
            projectFilePath = projectFile.absolutePath,
        )

        assertNotNull(result, logs.joinToString("\n"))
        val asmWrites = result.writeReport.writes.filter { it.kind == RomWriteKind.ASM_SOURCE }
        assertTrue(asmWrites.isNotEmpty())
        assertTrue(asmWrites.all { it.owner == "asm-source:project" })
        assertTrue(result.sourcePatched >= 2)
        val generatedAssetWrites = result.writeReport.writes.filter { it.kind == RomWriteKind.ASM_ASSET }
        assertTrue(generatedAssetWrites.isNotEmpty(), logs.joinToString("\n"))
        assertTrue(result.sourceAssetPatched > 0)
        val asmPatchWrites = result.writeReport.writes.filter { it.kind == RomWriteKind.ASM_PATCH }
        assertTrue(asmPatchWrites.any { it.owner == "patch:config_samus_physics" }, logs.joinToString("\n"))
        assertTrue(result.writeReport.writes.none {
            it.owner == "patch:config_samus_physics" && it.kind == RomWriteKind.CONFIG
        })
        assertTrue(result.sourcePatchPatched > 0)
        assertEquals(0x2C, result.resultRom[result.headerSize + 0x081EA2].toInt() and 0xFF)
        assertEquals(0x06, result.resultRom[result.headerSize + 0x081EB9].toInt() and 0xFF)
        assertEquals(0x00, result.resultRom[result.headerSize + 0x081EC0].toInt() and 0xFF)
        assertTrue(logs.any { it.contains("[ASM-BUILD] Source base ready") })
        assertTrue(logs.any { it.contains("Reusing immutable ASM snapshot") })

        val cachedLogs = mutableListOf<String>()
        val cached = ProjectFileService.buildRom(
            project = project,
            romParser = parser,
            onLog = cachedLogs::add,
            onStatus = cachedLogs::add,
            projectFilePath = projectFile.absolutePath,
        )
        assertNotNull(cached, cachedLogs.joinToString("\n"))
        assertContentEquals(result.resultRom, cached.resultRom)
        assertTrue(cachedLogs.any { it.contains("unchanged — reusing prepared source base") }, cachedLogs.joinToString("\n"))
        assertTrue(cachedLogs.none { it.contains("Compiling immutable ASM snapshot") })
        assertTrue(cachedLogs.none { it.contains("Compiling project ASM source") })

        val romOnlyPatch = project.patches.firstOrNull { patch ->
            patch.enabled && AsmPatchBackendRegistry.supportFor(patch).asm == null
        }
        if (romOnlyPatch != null) {
            romOnlyPatch.enabled = false
            val romPatchLogs = mutableListOf<String>()
            val romPatchBuild = ProjectFileService.buildRom(
                project = project,
                romParser = parser,
                onLog = romPatchLogs::add,
                onStatus = romPatchLogs::add,
                projectFilePath = projectFile.absolutePath,
            )
            assertNotNull(romPatchBuild, romPatchLogs.joinToString("\n"))
            assertTrue(
                romPatchLogs.any { it.contains("unchanged — reusing compiled base") },
                romPatchLogs.joinToString("\n"),
            )
            assertTrue(romPatchLogs.none { it.contains("Compiling immutable ASM snapshot") })
            assertTrue(romPatchLogs.none { it.contains("Compiling project ASM source") })
        }
    }

    private fun findWorkspaceFile(relativePath: String): File? {
        var cursor: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val candidate = cursor?.resolve(relativePath)
            if (candidate?.isFile == true) return candidate
            cursor = cursor?.parentFile
        }
        return null
    }
}

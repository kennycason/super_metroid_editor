package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomWriteKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AsmBuildPipelineIntegrationTest {
    @Test
    fun `ASM source base enters the same owned export transaction when local fixtures exist`() {
        val projectFile = findWorkspaceFile(
            "projects/Super Metroid Sandbox/Super Metroid Sandbox.smedit"
        ) ?: return
        val rom = File(projectFile.parentFile, "Super Metroid Sandbox.smc").takeIf(File::isFile) ?: return
        val source = File(projectFile.parentFile, "Super Metroid Sandbox_smedit/asm/workspace/src/bank_90.asm")
        if (!source.isFile) return
        val project = ProjectFileService.snapshotProject(ProjectFileService.loadProject(projectFile)).also {
            it.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
        val logs = mutableListOf<String>()

        val result = ProjectFileService.buildRom(
            project = project,
            romParser = RomParser(rom.readBytes()),
            onLog = logs::add,
            onStatus = logs::add,
            projectFilePath = projectFile.absolutePath,
        )

        assertNotNull(result, logs.joinToString("\n"))
        val asmWrites = result.writeReport.writes.filter { it.kind == RomWriteKind.ASM_SOURCE }
        assertTrue(asmWrites.isNotEmpty())
        assertTrue(asmWrites.all { it.owner == "asm-source:project" })
        assertTrue(result.sourcePatched >= 2)
        assertEquals(0x06, result.resultRom[result.headerSize + 0x081EB9].toInt() and 0xFF)
        assertEquals(0x00, result.resultRom[result.headerSize + 0x081EC0].toInt() and 0xFF)
        assertTrue(logs.any { it.contains("[ASM-BUILD] Source base ready") })
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

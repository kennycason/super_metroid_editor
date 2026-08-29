package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RomWritePlanIntegrationTest {
    @Test
    fun `spike olympics project exports through owned transactional plan`() {
        val projectFile = findWorkspaceFile(
            "projects/Super Metroid Spike Olympics I/Super Metroid Spike Olympics I.smedit"
        ) ?: return
        val baseRom = File(projectFile.parentFile, "Super Metroid Spike Olympics I.smc")
        if (!baseRom.isFile) return

        val tempDir = Files.createTempDirectory("smedit-spike-write-plan").toFile()
        try {
            val tempRom = File(tempDir, "spike.smc")
            baseRom.copyTo(tempRom)
            val project = ProjectFileService.loadProject(projectFile).copy(romPath = tempRom.absolutePath)
            val logs = mutableListOf<String>()

            val output = ProjectFileService.exportToRom(
                project = project,
                romParser = RomParser(tempRom.readBytes()),
                onLog = logs::add,
                onStatus = logs::add,
            )

            assertNotNull(output, logs.joinToString("\n"))
            assertTrue(File(output).isFile)
            assertTrue(logs.any { it.contains("[ROM-PLAN] Validated") })
            assertTrue(logs.any { it.contains("patch:bundled_spider_ball") })
            assertTrue(logs.any { it.contains("patch:config_room_name_pause_map") })
            assertTrue(logs.none { it.contains("failed safely", ignoreCase = true) })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun findWorkspaceFile(relativePath: String): File? {
        var cursor: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            val candidate = cursor?.resolve(relativePath)
            if (candidate?.isFile == true) return candidate
            cursor = cursor?.parentFile
        }
        return null
    }
}

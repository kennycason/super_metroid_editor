package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProjectFileServiceTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `saving an upgraded project keeps one untouched legacy backup`() {
        val projectFile = File(tempDir, "test.smedit")
        val legacyText = """{"romPath":"base.smc","rooms":{}}"""
        projectFile.writeText(legacyText)
        val project = ProjectFileService.loadProject(projectFile)
        project.projectFormatVersion = SmEditProject.CURRENT_PROJECT_FORMAT_VERSION

        assertTrue(
            ProjectFileService.saveProject(project, projectFile.absolutePath, null, false) {},
        )
        val backup = File(tempDir, "test.smedit.format1.backup")
        assertTrue(backup.isFile)
        assertEquals(legacyText, backup.readText())
        assertEquals(
            SmEditProject.CURRENT_PROJECT_FORMAT_VERSION,
            ProjectFileService.loadProject(projectFile).projectFormatVersion,
        )

        backup.writeText("do not overwrite")
        assertTrue(
            ProjectFileService.saveProject(project, projectFile.absolutePath, null, false) {},
        )
        assertEquals("do not overwrite", backup.readText())
    }
}


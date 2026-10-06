package com.supermetroid.editor.ui

import com.supermetroid.editor.data.CommunitySamusSpriteSource
import com.supermetroid.editor.data.CommunitySamusInjectionArtifact
import com.supermetroid.editor.data.PatchSortOrder
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
    fun `project file preserves patch browser settings`() {
        val projectFile = File(tempDir, "patch-browser.smedit")
        val project = SmEditProject(romPath = "base.smc").also {
            it.generalSettings.patchBrowser.sortOrder = PatchSortOrder.NAME_DESCENDING
            it.generalSettings.patchBrowser.favoritesFirst = false
            it.generalSettings.patchBrowser.favoritePatchIds += "config_environmental_damage"
        }

        assertTrue(ProjectFileService.saveProject(project, projectFile.absolutePath, null, false) {})
        val reopened = ProjectFileService.loadProject(projectFile)

        assertEquals(PatchSortOrder.NAME_DESCENDING, reopened.generalSettings.patchBrowser.sortOrder)
        assertEquals(false, reopened.generalSettings.patchBrowser.favoritesFirst)
        assertEquals(
            listOf("config_environmental_damage"),
            reopened.generalSettings.patchBrowser.favoritePatchIds,
        )
    }

    @Test
    fun `saving an upgraded project keeps one untouched pre-upgrade backup`() {
        val projectFile = File(tempDir, "test.smedit")
        val markerlessText = """{"romPath":"base.smc","rooms":{}}"""
        projectFile.writeText(markerlessText)
        val project = ProjectFileService.loadProject(projectFile)
        assertEquals(SmEditProject.CURRENT_PROJECT_FORMAT_VERSION, project.projectFormatVersion)

        assertTrue(
            ProjectFileService.saveProject(project, projectFile.absolutePath, null, false) {},
        )
        val backup = File(tempDir, "test.smedit.before-schema-upgrade.backup")
        assertTrue(backup.isFile)
        assertEquals(markerlessText, backup.readText())
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

    @Test
    fun `project file preserves portable community Samus source metadata and bytes`() {
        val projectFile = File(tempDir, "community-samus.smedit")
        val source = CommunitySamusSpriteSource(
            formatId = "spritesomething-f3428d26",
            pngBase64 = "iVBORw0KGgo=",
            sha256 = "ab".repeat(32),
            sourceName = "samus_test.png",
            displayName = "Test Samus",
            authors = listOf("Sprite Artist"),
            category = "Custom",
            catalogName = "samus_test",
            catalogVersion = 3,
            catalogRevision = "12".repeat(20),
            sourceUrl = "https://example.invalid/samus_test.png",
            injectionArtifact = CommunitySamusInjectionArtifact(
                formatId = CommunitySamusInjectionArtifact.MAP_RANDOMIZER_IPS_V1,
                ipsBase64 = "UEFUQ0hFT0Y=",
                sha256 = "cd".repeat(32),
                baseRomSha256 = "ef".repeat(32),
                baseRomSize = 0x300000,
                outputRomSize = 0x400000,
                providerRevision = "34".repeat(20),
                sourceSheetSha256 = "ab".repeat(32),
                sourceUrl = "https://example.invalid/samus_test.ips",
            ),
        )
        val project = SmEditProject(romPath = "base.smc").also {
            it.customGfx.samusCommunitySource = source
        }

        assertTrue(ProjectFileService.saveProject(project, projectFile.absolutePath, null, false) {})
        val reopened = ProjectFileService.loadProject(projectFile)

        assertEquals(source, reopened.customGfx.samusCommunitySource)
        assertEquals(SmEditProject.CURRENT_PROJECT_FORMAT_VERSION, reopened.projectFormatVersion)
    }
}

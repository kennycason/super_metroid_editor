package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectCreditsGroup
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsmCreditsSourceBridgeTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `visual edits install one source owner and share ASM undo redo`() = runBlocking {
        val fixture = fixture("visual")
        val project = project(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        var problem: String? = null
        var changes = 0
        val bridge = AsmCreditsSourceBridge(
            workspace,
            RomParser(fixture.rom),
            { project },
            { changes++ },
            { problem = it },
        )
        workspace.sourceTextObserver = bridge::sourceChanged

        val after = listOf(ProjectCreditsGroup("SMEDIT TEAM", mutableListOf("SAMUS ARAN", "BABY METROID")))
        val edit = bridge.applyVisualEdit(emptyList(), after)
        assertTrue(bridge.installed)
        assertEquals(after, project.creditsGroups)
        assertEquals(AsmCreditsSourceModule.VERSION, project.asmWorkspace.creditsSourceVersion)
        assertContainsDeclarations(workspace.projectSourceText(AsmCreditsSourceModule.FILE))
        assertNull(problem)

        bridge.undo(edit.afterSource, edit.sourceChange)
        assertTrue(project.creditsGroups.isEmpty())
        bridge.redo(edit.beforeSource, edit.sourceChange)
        assertEquals(after, project.creditsGroups)
        assertTrue(changes >= 3)

        AsmProjectWorkspaceRepository().persistSource(
            fixture.projectFile.path,
            AsmCreditsSourceModule.FILE,
            workspace.projectSourceText(AsmCreditsSourceModule.FILE),
        )
        val reopened = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val persisted = AsmCreditsSourceModule.parse(
            reopened.projectSourceText(AsmCreditsSourceModule.FILE),
            RomParser(fixture.rom),
        )
        assertEquals(after, persisted)
    }

    @Test
    fun `invalid declaration draft keeps last valid visual model and blocks save normalization`() = runBlocking {
        val fixture = fixture("invalid")
        val project = project(fixture)
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        var problem: String? = null
        val bridge = AsmCreditsSourceBridge(
            workspace,
            RomParser(fixture.rom),
            { project },
            {},
            { problem = it },
        )
        workspace.sourceTextObserver = bridge::sourceChanged
        val groups = listOf(ProjectCreditsGroup("TEAM", mutableListOf("SAMUS")))
        bridge.applyVisualEdit(emptyList(), groups)
        val valid = workspace.projectSourceText(AsmCreditsSourceModule.FILE)
        val invalid = valid.replace("; CREDIT NAME: SAMUS", "; CREDIT NAME: SAMUS 123")

        assertTrue(workspace.updateSourceEditText(AsmCreditsSourceModule.FILE, invalid))
        assertEquals(groups, project.creditsGroups, "An invalid draft must not replace the visual projection")
        assertNotNull(problem)
        assertFailsWith<IllegalStateException> {
            bridge.prepare(AsmCreditsSourceModule.FILE, invalid)()
        }
        assertEquals(invalid, workspace.projectSourceText(AsmCreditsSourceModule.FILE))
        assertTrue(workspace.undoSourceEdit(AsmCreditsSourceModule.FILE))
        assertEquals(groups, project.creditsGroups)
        assertNull(problem)
    }

    @Test
    fun `persisted source ownership rejects missing stale or mismatched modules`() {
        val fixture = fixture("persisted")
        val project = project(fixture).apply {
            asmWorkspace.creditsSourceVersion = AsmCreditsSourceModule.VERSION
            creditsGroups += ProjectCreditsGroup("TEAM", mutableListOf("SAMUS"))
        }
        val parser = RomParser(fixture.rom)
        assertFailsWith<IllegalArgumentException> {
            AsmCreditsSourceModule.validateForBuild(project, fixture.projectFile.path, parser)
        }

        val repository = AsmProjectWorkspaceRepository()
        repository.createModule(
            fixture.projectFile.path,
            AsmCreditsSourceModule.FILE.removePrefix(ASM_PROJECT_MODULE_PREFIX),
            initialSource = AsmCreditsSourceModule.render(parser, project.creditsGroups),
        )
        AsmCreditsSourceModule.validateForBuild(project, fixture.projectFile.path, parser)
        project.creditsGroups.single().header = "OTHER TEAM"
        assertFailsWith<IllegalArgumentException> {
            AsmCreditsSourceModule.validateForBuild(project, fixture.projectFile.path, parser)
        }
    }

    private fun project(fixture: AsmProjectTestFixture) = SmEditProject(fixture.romFile.path).apply {
        asmWorkspace.enabled = true
        asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
    }

    private fun fixture(name: String) = AsmProjectTestFixture.synthetic(
        File(temporary, name).apply { mkdirs() },
        TestRomHelper.requireRomBytes(),
    )

    private fun assertContainsDeclarations(text: String) {
        assertTrue("; CREDIT GROUP: SMEDIT TEAM" in text)
        assertTrue("; CREDIT NAME: SAMUS ARAN" in text)
        assertTrue("org \$97EEFF" in text)
        assertTrue("org \$8CF3E9" in text)
        assertTrue("org \$8BF6FC" in text)
    }
}

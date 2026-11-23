package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmCreditsSourceModule
import com.supermetroid.editor.asm.AsmPlmSourceBridge
import com.supermetroid.editor.asm.AsmPlmSourceCatalog
import com.supermetroid.editor.asm.AsmProjectTestFixture
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.CreditsData
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreditsEditorStateTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `visual credits undo redo restores model and managed ASM source together`() = runBlocking {
        val fixture = AsmProjectTestFixture.synthetic(
            File(temporary, "editor").apply { mkdirs() },
            TestRomHelper.requireRomBytes(),
        )
        val parser = RomParser(fixture.rom)
        val project = SmEditProject(fixture.romFile.path).apply {
            asmWorkspace.enabled = true
            asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
        val editor = EditorState().apply {
            testMode = true
            initForProject(fixture.projectFile, project)
        }
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val bridge = AsmPlmSourceBridge(
            AsmPlmSourceCatalog.load(fixture.workspace.originalDirectory, parser),
            workspace,
            registerSaveParticipant = false,
        ) { project }
        editor.asmPlmSourceBridge = bridge
        workspace.sourceSaveParticipant = bridge
        workspace.sourceTextObserver = bridge.credits::sourceChanged
        val freeRows = CreditsData.parseCredits(parser)!!.freeRowIndices.size

        assertTrue(editor.addCreditsGroup(freeRows), editor.statusMessage)
        val authored = workspace.projectSourceText(AsmCreditsSourceModule.FILE)
        assertEquals("NEW SECTION", project.creditsGroups.single().header)
        assertTrue("; CREDIT NAME: NAME HERE" in authored)
        assertEquals(1, editor.undoStack.size)

        assertTrue(editor.undo(), editor.statusMessage)
        assertTrue(project.creditsGroups.isEmpty())
        assertFalse("; CREDIT GROUP:" in workspace.projectSourceText(AsmCreditsSourceModule.FILE))
        assertTrue(editor.redo(), editor.statusMessage)
        assertEquals("NEW SECTION", project.creditsGroups.single().header)
        assertEquals(authored, workspace.projectSourceText(AsmCreditsSourceModule.FILE))

        val newer = authored.replace("; CREDIT NAME: NAME HERE", "; CREDIT NAME: SAMUS")
        assertTrue(workspace.updateSourceEditText(AsmCreditsSourceModule.FILE, newer))
        assertEquals("SAMUS", project.creditsGroups.single().names.single())
        assertFalse(editor.undo(), "A newer ASM edit must block an older visual source undo")
        assertTrue(editor.statusMessage.contains("newer edit", ignoreCase = true))
        assertEquals(newer, workspace.projectSourceText(AsmCreditsSourceModule.FILE))
    }
}

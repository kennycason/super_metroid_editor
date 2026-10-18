package com.supermetroid.editor.asm

import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.PatchWrite
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmFastDoorsModuleTest {
    @TempDir lateinit var directory: File

    @Test fun `only the audited bundled patch selects a curated backend`() {
        val patch = PatchRepository.loadBundledPatches().single { it.id == AsmFastDoorsModule.PATCH_ID }
        AsmFastDoorsModule.requireBundled(patch)
        assertEquals(PatchImplementationBackend.ASM_CURATED_MODULE, AsmPatchBackendRegistry.supportFor(patch).asm)
        assertTrue(AsmPatchBackendRegistry.sourceOwners(listOf(patch.apply { enabled = true })).isEmpty())
        patch.writes += PatchWrite(0x200, listOf(0))
        assertFailsWith<IllegalArgumentException> { AsmFastDoorsModule.requireBundled(patch) }
        assertNull(AsmPatchBackendRegistry.supportFor(patch.copy(id = "imported_fast_doors")).asm)
    }

    @Test fun `gate edits preserve the entire custom routine and comments`() {
        val custom = AsmFastDoorsModule.template(false).replace("LDA.W #\$0002", "LDA.W #\$0003 ; custom")
        assertFalse(AsmFastDoorsModule.enabled(custom))
        val active = AsmFastDoorsModule.withEnabled(custom, true)
        assertTrue(AsmFastDoorsModule.enabled(active))
        assertEquals(custom, AsmFastDoorsModule.withEnabled(active, false))
        val spaced = custom.replace("!SMEDIT_FastDoorsEnabled = 0", "  !SMEDIT_FastDoorsEnabled\t=\t0 ; note")
        assertFalse(AsmFastDoorsModule.enabled(spaced))
        assertTrue(AsmFastDoorsModule.withEnabled(spaced, true).contains("\t1 ; note"))
    }

    @Test fun `invalid or bypassed gate drafts are never interpreted as a safe Disable`() {
        val valid = AsmFastDoorsModule.template(false)
        for (text in listOf(valid.replace(" = 0", " = 2"), valid.replace(" = 0", " = 1+0"),
            valid + "\norg \$80D100\ndb \$60\n", valid.replace("\nendif", "\nelse\ndb \$60\nendif"),
            valid.replace("\nendif", "\nendif\norg \$80D100\ndb \$60\nif 0\nendif"),
            valid.replace("\nif !", "\n!SMEDIT_FastDoorsEnabled = 1\nif !"))) {
            assertFailsWith<IllegalArgumentException>(text) { AsmFastDoorsModule.enabled(text) }
        }
    }

    @Test fun `enable disable and raw flag edits share source Undo without replacing authored code`() = runBlocking {
        val fixture = AsmProjectTestFixture.synthetic(directory, ByteArray(0x300000))
        val project = SmEditProject(fixture.romFile.path)
        val patch = PatchRepository.loadBundledPatches().single { it.id == AsmFastDoorsModule.PATCH_ID }
        project.patches += patch
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        var changes = 0
        val bridge = AsmFastDoorsBridge(workspace, { project }) { changes++ }
        workspace.sourceEditorInterceptor = bridge::intercept
        assertFalse(bridge.installed)
        bridge.setEnabled(true)
        val file = AsmFastDoorsModule.FILE
        assertTrue(patch.enabled); assertTrue(bridge.installed)
        assertTrue(AsmFastDoorsModule.SOURCE_KEY in project.asmWorkspace.patchSourceKeys)
        assertTrue(workspace.undoSourceEdit(file)); assertFalse(patch.enabled)
        assertFalse(AsmFastDoorsModule.SOURCE_KEY in project.asmWorkspace.patchSourceKeys)
        assertFalse(AsmFastDoorsModule.enabled(workspace.projectSourceText(file)))
        assertTrue(workspace.redoSourceEdit(file)); assertTrue(patch.enabled)
        val custom = workspace.projectSourceText(file).replace("LDA.W #\$0002", "LDA.W #\$0003 ; custom")
        assertTrue(workspace.updateSourceEditText(file, custom))
        bridge.setEnabled(false)
        assertFalse(patch.enabled); assertContains(workspace.projectSourceText(file), "#\$0003 ; custom")
        assertTrue(workspace.undoSourceEdit(file)); assertTrue(patch.enabled)
        bridge.setEnabled(false)
        assertTrue(workspace.saveAllSources(), workspace.error)
        val reopened = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val reloaded = AsmFastDoorsBridge(reopened, { project }) {}
        reloaded.setEnabled(true)
        assertContains(reopened.projectSourceText(file), "#\$0003 ; custom")
        val before = reopened.projectSourceText(file)
        reopened.sourceEditorInterceptor = reloaded::intercept
        assertTrue(reopened.updateSourceEditText(file, AsmFastDoorsModule.withEnabled(before, false)))
        assertFalse(patch.enabled)
        assertTrue(reopened.undoSourceEdit(file)); assertTrue(patch.enabled)
        assertTrue(changes >= 6)
    }

    @Test fun `legacy enabled entries can be disabled without creating or discarding any source`() = runBlocking {
        val fixture = AsmProjectTestFixture.synthetic(directory, ByteArray(0x300000))
        val project = SmEditProject(fixture.romFile.path)
        val patch = PatchRepository.loadBundledPatches().single { it.id == AsmFastDoorsModule.PATCH_ID }.apply { enabled = true }
        project.patches += patch
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val bridge = AsmFastDoorsBridge(workspace, { project }) {}
        bridge.setEnabled(false)
        assertFalse(patch.enabled); assertFalse(bridge.installed)
        assertTrue(workspace.undoSourceEdit("main.asm")); assertTrue(patch.enabled)
        bridge.reconcile()
        assertTrue(bridge.installed); assertTrue(AsmFastDoorsModule.SOURCE_KEY in project.asmWorkspace.patchSourceKeys)
    }

    @Test fun `module browser toggles use the same literal gate and invalid drafts stay editable`() = runBlocking {
        val fixture = AsmProjectTestFixture.synthetic(directory, ByteArray(0x300000))
        val project = SmEditProject(fixture.romFile.path)
        val patch = PatchRepository.loadBundledPatches().single { it.id == AsmFastDoorsModule.PATCH_ID }
        project.patches += patch
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        val bridge = AsmFastDoorsBridge(workspace, { project }) {}
        val file = AsmFastDoorsModule.FILE
        workspace.sourceEditorInterceptor = bridge::intercept
        workspace.codePatchModuleToggle = { id, enabled -> assertEquals(file, id); bridge.setEnabled(enabled) }
        bridge.setEnabled(true)
        assertTrue(workspace.isProjectModuleEnabled(file))
        assertTrue(workspace.setProjectModuleEnabled(file, false))
        assertFalse(patch.enabled); assertFalse(workspace.isProjectModuleEnabled(file))
        assertFalse(file in workspace.disabledProjectModuleFileIds)
        assertTrue(workspace.undoSourceEdit(file)); assertTrue(patch.enabled)
        val invalid = workspace.projectSourceText(file).replace("Enabled = 1", "Enabled = ")
        assertTrue(workspace.updateSourceEditText(file, invalid))
        assertTrue(patch.enabled, "Incomplete drafts retain the last good GUI flag")
        assertFailsWith<IllegalArgumentException> { bridge.setEnabled(false) }
        assertEquals(invalid, workspace.projectSourceText(file))
        assertTrue(workspace.undoSourceEdit(file))
        assertFalse(workspace.updateProjectModuleSettings(file, "renamed", ""))
        assertFalse(workspace.deleteProjectModule(file))
        assertTrue(bridge.installed)
    }

    @Test fun `a pre-existing ordinary module is never converted by its filename`() = runBlocking {
        val fixture = AsmProjectTestFixture.synthetic(directory, ByteArray(0x300000))
        val project = SmEditProject(fixture.romFile.path)
        val patch = PatchRepository.loadBundledPatches().single { it.id == AsmFastDoorsModule.PATCH_ID }
        project.patches += patch
        val workspace = AsmWorkspaceState().apply { bindProject(fixture.projectFile.path, true) }
        assertTrue(workspace.createProjectModule("fast_doors"))
        val file = AsmFastDoorsModule.FILE
        val custom = "; Existing handwritten code\norg \$80D100\ndb \$60\n"
        assertTrue(workspace.updateSourceEditText(file, custom)); assertTrue(workspace.saveAllSources())
        val bridge = AsmFastDoorsBridge(workspace, { project }) {}
        workspace.sourceEditorInterceptor = bridge::intercept
        bridge.reconcile(); AsmFastDoorsModule.prepare(project, fixture.projectFile.path)
        assertTrue(project.asmWorkspace.patchSourceKeys.isEmpty())
        assertNull(bridge.intercept(file, custom, custom + "; note\n"))
        assertFailsWith<IllegalArgumentException> { bridge.setEnabled(true) }
        assertEquals(custom, workspace.projectSourceText(file))
        assertTrue(workspace.renameProjectModule(file, "legacy_fast_doors"), workspace.error)
        bridge.setEnabled(true)
        assertTrue(patch.enabled)
        assertContains(workspace.projectSourceText("project/legacy_fast_doors.asm"), custom)
    }
}

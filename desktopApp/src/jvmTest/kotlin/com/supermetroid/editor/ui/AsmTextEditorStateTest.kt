package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmProjectTestFixture
import com.supermetroid.editor.asm.AsmProjectWorkspaceRepository
import com.supermetroid.editor.asm.AsmTextSourceAccess
import com.supermetroid.editor.asm.AsmTextSourceBridge
import com.supermetroid.editor.asm.AsmTypewriterTextAccess
import com.supermetroid.editor.asm.AsmTypewriterTextBridge
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.asm.snapshot
import com.supermetroid.editor.asm.AsmIntroStoryTextAccess
import com.supermetroid.editor.asm.AsmIntroStoryTextBridge
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TextCategory
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmTextEditorStateTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `fixed text refreshes guards drafts retires legacy override and supports history restore reopen`() {
        val fixture = AsmProjectTestFixture.create(File(temporary, "fixture"))
        val parser = RomParser(fixture.rom)
        val project = SmEditProject(romPath = fixture.romFile.path).apply {
            asmWorkspace.enabled = true
            asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
        val repository = AsmProjectWorkspaceRepository()
        val workspace = AsmWorkspaceState(projectRepository = repository)
        runBlocking { workspace.bindProject(fixture.projectFile.path, enabled = true, progressive = true) }
        val state = EditorState().apply {
            initForProject(fixture.projectFile, project)
            attachAsmWorkspaceState(workspace)
        }
        val native = state.textEntries(parser).first { it.category == TextCategory.AREA_NAME }
        val bridge = AsmTextSourceBridge(repository)
        val access = assertIs<AsmTextSourceAccess.Editable>(
            bridge.accessAll(fixture.projectFile.path, listOf(native)).getValue(native.id),
        )

        val directSaved = bridge.save(fixture.projectFile.path, native, "ZEBES", access.sourceText)
        assertTrue(workspace.refreshPersistedSourceFiles(setOf(directSaved.sourceFileId)))
        assertTrue(state.textEntries(parser).first { it.id == native.id }.text == "ZEBES")
        val directRestored = bridge.restore(fixture.projectFile.path, native, directSaved.sourceText)
        assertTrue(workspace.refreshPersistedSourceFiles(setOf(directRestored.sourceFileId)))
        assertTrue(state.textEntries(parser).first { it.id == native.id }.text == native.text)

        project.textEdits[native.id] = "STALE"
        val draft = access.sourceText + "\n; unsaved text draft\n"
        workspace.replaceProjectSource(access.sourceFileId, access.sourceText, draft)
        assertFalse(state.updateTextEntry(native, "ZEBES"))
        assertTrue(state.textEntries(parser).first { it.id == native.id }.text == native.text)
        workspace.replaceProjectSource(access.sourceFileId, draft, access.sourceText)

        assertTrue(state.updateTextEntry(native, "ZEBES"))
        assertNull(project.textEdits[native.id], "The first source save retires the stale JSON owner")
        assertTrue(state.textEntries(parser).first { it.id == native.id }.text == "ZEBES")
        assertTrue(state.textEntryEditingStatus(state.textEntries(parser).first { it.id == native.id }).modified)

        assertTrue(state.undo())
        assertTrue(state.textEntries(parser).first { it.id == native.id }.text == native.text)
        assertTrue(state.redo())
        assertTrue(state.textEntries(parser).first { it.id == native.id }.text == "ZEBES")

        assertTrue(ProjectFileService.saveProject(project, fixture.projectFile.path, parser, false) {})
        val reopenedProject = ProjectFileService.loadProject(fixture.projectFile)
        val reopenedWorkspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository())
        runBlocking { reopenedWorkspace.bindProject(fixture.projectFile.path, enabled = true, progressive = true) }
        val reopened = EditorState().apply {
            initForProject(fixture.projectFile, reopenedProject)
            attachAsmWorkspaceState(reopenedWorkspace)
        }
        assertTrue(reopened.textEntries(parser).first { it.id == native.id }.text == "ZEBES")
        assertTrue(reopened.resetTextEntry(reopened.textEntries(parser).first { it.id == native.id }))
        assertTrue(reopened.textEntries(parser).first { it.id == native.id }.text == native.text)
    }

    @Test
    fun `custom fixed text source is read only in the visual editor`() {
        val fixture = AsmProjectTestFixture.create(File(temporary, "custom"))
        val parser = RomParser(fixture.rom)
        val project = SmEditProject(romPath = fixture.romFile.path).apply {
            asmWorkspace.enabled = true
            asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
        val repository = AsmProjectWorkspaceRepository()
        val native = parser.run { com.supermetroid.editor.rom.TextData.readAllText(getRomData()) }
            .first { it.category == TextCategory.AREA_NAME }
        val access = assertIs<AsmTextSourceAccess.Editable>(
            AsmTextSourceBridge(repository).accessAll(fixture.projectFile.path, listOf(native)).getValue(native.id),
        )
        val addressComment = ";${native.snesAddress.toString(16).uppercase().padStart(6, '0')};"
        val customSource = access.sourceText.lineSequence().joinToString("\n") { line ->
            if (addressComment in line) line.replaceFirst(Regex("\\$[0-9A-Fa-f]{4}"), "CustomGlyph") else line
        }
        assertTrue(customSource != access.sourceText)
        repository.persistSource(
            fixture.projectFile.path,
            access.sourceFileId,
            customSource,
            access.sourceText,
        )
        val state = EditorState().apply { initForProject(fixture.projectFile, project) }
        val visible = state.textEntries(parser).first { it.id == native.id }
        val status = state.textEntryEditingStatus(visible)

        assertTrue(status.sourceManaged)
        assertFalse(status.editable)
        assertTrue(status.message.orEmpty().contains("ASM"))
        assertFalse(state.updateTextEntry(visible, "ZEBES"))
    }

    @Test
    fun `typewriter text guards drafts relocates in bank A6 and has reversible history`() {
        val fixture = AsmProjectTestFixture.create(File(temporary, "typewriter"))
        val parser = RomParser(fixture.rom)
        val project = SmEditProject(romPath = fixture.romFile.path).apply {
            asmWorkspace.enabled = true
            asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
        val repository = AsmProjectWorkspaceRepository()
        val workspace = AsmWorkspaceState(projectRepository = repository)
        runBlocking { workspace.bindProject(fixture.projectFile.path, enabled = true, progressive = true) }
        val state = EditorState().apply {
            initForProject(fixture.projectFile, project)
            attachAsmWorkspaceState(workspace)
        }
        val entry = state.textEntries(parser).first { it.id == "tourian_escape" }
        val bridge = AsmTypewriterTextBridge(repository)
        val access = assertIs<AsmTypewriterTextAccess.Editable>(
            bridge.accessAll(fixture.projectFile.path, listOf(entry)).getValue(entry.id),
        )
        val directSaved = bridge.save(
            fixture.projectFile.path,
            entry,
            "DIRECT\nSOURCE!",
            access.snapshot(),
        )
        assertTrue(workspace.refreshPersistedSourceFiles(setOf(directSaved.sourceFileId)))
        assertTrue(state.textEntries(parser).first { it.id == entry.id }.text == "DIRECT\nSOURCE!")
        val directRestored = bridge.restore(fixture.projectFile.path, entry, directSaved.snapshot())
        assertTrue(workspace.refreshPersistedSourceFiles(setOf(directRestored.sourceFileId)))
        assertTrue(state.textEntries(parser).first { it.id == entry.id }.text == entry.text)
        val draft = access.sourceText + "\n; unsaved typewriter draft\n"
        workspace.replaceProjectSource(access.sourceFileId, access.sourceText, draft)
        assertFalse(state.updateTextEntry(entry, "ALERT!\nRUN!"))
        workspace.replaceProjectSource(access.sourceFileId, draft, access.sourceText)

        assertTrue(state.updateTextEntry(entry, "ALERT!\nRUN!"))
        assertTrue(state.textEntries(parser).first { it.id == entry.id }.text == "ALERT!\nRUN!")
        assertTrue(state.undo())
        assertTrue(state.textEntries(parser).first { it.id == entry.id }.text == entry.text)
        assertTrue(state.redo())

        val grownText = "TIME BOMB HAS BEEN SET!\nESCAPE FROM ZEBES IMMEDIATELY!"
        assertFalse(state.updateTextEntry(state.textEntries(parser).first { it.id == entry.id }, grownText))
        assertIs<TilesetAssetRelocationProposal.TypewriterText>(state.pendingTilesetAssetRelocation)
        assertTrue(state.confirmTilesetAssetRelocation())
        assertTrue(state.textEntries(parser).first { it.id == entry.id }.text == grownText)
        assertTrue(repository.managedAssets(fixture.projectFile.path).single().allowedBanks == listOf(0xA6))
        assertTrue(workspace.projectAssetChangesSnapshot().single().managed)

        assertTrue(state.undo())
        assertTrue(repository.managedAssets(fixture.projectFile.path).isEmpty())
        assertTrue(state.textEntries(parser).first { it.id == entry.id }.text == "ALERT!\nRUN!")
        assertTrue(state.redo())
        assertTrue(repository.managedAssets(fixture.projectFile.path).isNotEmpty())
        assertTrue(state.resetTextEntry(state.textEntries(parser).first { it.id == entry.id }))
        assertTrue(repository.managedAssets(fixture.projectFile.path).isEmpty())
        assertTrue(state.textEntries(parser).first { it.id == entry.id }.text == entry.text)
    }

    @Test
    fun `intro page refreshes live guards drafts retires override and survives history reopen restore`() {
        val fixture = AsmProjectTestFixture.create(File(temporary, "intro-story"))
        val parser = RomParser(fixture.rom)
        val project = SmEditProject(romPath = fixture.romFile.path).apply {
            asmWorkspace.enabled = true
            asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
        val repository = AsmProjectWorkspaceRepository()
        val workspace = AsmWorkspaceState(projectRepository = repository)
        runBlocking { workspace.bindProject(fixture.projectFile.path, enabled = true, progressive = true) }
        val state = EditorState().apply {
            initForProject(fixture.projectFile, project)
            attachAsmWorkspaceState(workspace)
        }
        val native = state.textEntries(parser).first { it.id == "intro_5" }
        val bridge = AsmIntroStoryTextBridge(repository)
        val access = assertIs<AsmIntroStoryTextAccess.Editable>(
            bridge.accessAll(fixture.projectFile.path, listOf(native)).getValue(native.id),
        )
        val directText = "CERES STATION WAS UNDER\nSOURCE!!"
        val direct = bridge.save(fixture.projectFile.path, native, directText, access.snapshot())
        assertTrue(workspace.refreshPersistedSourceFiles(setOf(direct.sourceFileId)))
        assertTrue(state.textEntries(parser).first { it.id == native.id }.text == directText)
        val directRestored = bridge.restore(fixture.projectFile.path, native, direct.snapshot())
        assertTrue(workspace.refreshPersistedSourceFiles(setOf(directRestored.sourceFileId)))
        assertTrue(state.textEntries(parser).first { it.id == native.id }.text == native.text)

        val draft = access.sourceText + "\n; unsaved intro draft\n"
        workspace.replaceProjectSource(access.sourceFileId, access.sourceText, draft)
        assertFalse(state.updateTextEntry(native, "CERES STATION WAS UNDER\nDANGER!!"))
        workspace.replaceProjectSource(access.sourceFileId, draft, access.sourceText)

        project.textEdits[native.id] = "STALE"
        assertTrue(state.updateTextEntry(native, "CERES STATION WAS UNDER\nDANGER!!"))
        assertNull(project.textEdits[native.id])
        assertTrue(state.textEntries(parser).first { it.id == native.id }.text == "CERES STATION WAS UNDER\nDANGER!!")
        assertTrue(state.undo())
        assertTrue(state.textEntries(parser).first { it.id == native.id }.text == native.text)
        assertTrue(state.redo())

        assertTrue(ProjectFileService.saveProject(project, fixture.projectFile.path, parser, false) {})
        val reopenedProject = ProjectFileService.loadProject(fixture.projectFile)
        val reopenedWorkspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository())
        runBlocking { reopenedWorkspace.bindProject(fixture.projectFile.path, enabled = true, progressive = true) }
        val reopened = EditorState().apply {
            initForProject(fixture.projectFile, reopenedProject)
            attachAsmWorkspaceState(reopenedWorkspace)
        }
        assertTrue(reopened.textEntries(parser).first { it.id == native.id }.text == "CERES STATION WAS UNDER\nDANGER!!")
        assertTrue(reopened.resetTextEntry(reopened.textEntries(parser).first { it.id == native.id }))
        assertTrue(reopened.textEntries(parser).first { it.id == native.id }.text == native.text)
    }
}

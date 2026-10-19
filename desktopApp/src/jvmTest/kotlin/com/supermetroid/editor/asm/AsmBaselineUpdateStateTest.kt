package com.supermetroid.editor.asm

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmBaselineUpdateStateTest {
    @TempDir lateinit var temporary: File
    private val rom = ByteArray(0x8000)
    private val source = "Root:\n dw \$0001\n RTL\n"
    private suspend fun state(): AsmWorkspaceState {
        val referenceRoot = File(temporary, "reference")
        val src = File(referenceRoot, "src").apply { mkdirs() }
        val data = File(referenceRoot, "data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc bank_80.asm\n")
        File(src, "bank_80.asm").writeText(source)
        val reference = AsmReferenceWorkspace(referenceRoot, AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL,
            AsmReferenceContract.COMMIT, "fixture", "fixture.sfc", AsmVerifiedCache.hash(rom), rom.size, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val repo = AsmProjectWorkspaceRepository(emptyList(), emptyList(), AsmSourceAugmentation("one"))
        val project = File(temporary, "hack/Test.smedit").apply { parentFile.mkdirs(); writeText("{}") }
        repo.initialize(project.path, reference)
        val recipe = AsmSourceAugmentation("two", documentation = listOf(
            AsmSourceDocumentation("root", "bank_80.asm", "Root", "dw \$0001", listOf("New explanation."))))
        val updater = AsmBaselineUpdateRepository(repo, recipe, emptyList(), emptyList(), { _, _ -> })
        return AsmWorkspaceState(projectRepository = repo, installedReferenceLoader = { reference }, baselineUpdater = updater).also {
            it.loadInstalled()
            it.bindProject(project.path, true)
            it.openSource("bank_80.asm", 1)
        }
    }

    @Test fun `unsaved buffers and edits made after preview cannot be discarded by an update`() = runBlocking {
        val state = state()
        assertTrue(state.updateSourceEditText("bank_80.asm", source + "; unsaved\n"))
        assertFalse(state.previewBaselineUpdate())
        assertContains(state.error.orEmpty(), "Save all")
        state.discardSourceBuffer("bank_80.asm")
        assertTrue(state.previewBaselineUpdate(), state.error)
        assertTrue(state.updateSourceEditText("bank_80.asm", source + "; after preview\n"))
        assertFalse(state.applyBaselineUpdate(emptyMap(), rom))
        assertEquals("one", state.projectWorkspace?.baselineRevision)
        assertContains(state.projectSourceText("bank_80.asm"), "; after preview")
        state.cancelBaselineUpdate()
        assertNull(state.baselineUpdatePlan)
    }

    @Test fun `successful update refreshes source identity buffers navigation and bridge attachment`() = runBlocking {
        val state = state()
        val before = assertNotNull(state.projectWorkspace).baselineId
        assertTrue(state.updateSourceEditText("bank_80.asm", source.replace("\$0001", "\$0009")))
        assertTrue(state.saveAllSources())
        state.awaitSourceIndexing()
        assertTrue(state.previewBaselineUpdate(), state.error)
        assertTrue(state.applyBaselineUpdate(emptyMap(), rom), state.error)
        assertNotEquals(before, state.projectWorkspace?.baselineId, "The UI bridge effect must observe a changed baseline identity")
        assertEquals("two", state.projectWorkspace?.baselineRevision)
        assertEquals("; New explanation.\n" + source.replace("\$0001", "\$0009"), state.projectSourceText("bank_80.asm"))
        assertEquals(0, state.selectedLineIndex)
        assertFalse(state.canUndoSource("bank_80.asm"), "An old undo transaction must not replace the newly merged source")
        assertNull(state.baselineUpdatePlan)
        assertContains(state.baselineUpdateMessage.orEmpty(), "asm-backups")
    }

    @Test fun `switching projects discards a pending review rather than applying it to another project`() = runBlocking {
        val state = state()
        assertTrue(state.previewBaselineUpdate(), state.error)
        val plan = assertNotNull(state.baselineUpdatePlan)
        state.bindProject("", false)
        assertNull(state.baselineUpdatePlan)
        assertFalse(plan.staging.exists())
        assertFalse(state.applyBaselineUpdate(emptyMap(), rom))
    }
}

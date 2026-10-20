package com.supermetroid.editor.asm

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmBaselineUpdateStateTest {
    @TempDir lateinit var temporary: File
    private val rom = ByteArray(0x8000)
    private val source = "Root:\n dw \$0001\n RTL\n"
    private suspend fun state(
        backupLoader: (suspend (String) -> AsmBaselineBackupList)? = null,
        folderOpener: (File) -> Unit = {},
    ): AsmWorkspaceState {
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
        val backups = AsmBaselineBackupRepository(repo)
        return AsmWorkspaceState(projectRepository = repo, installedReferenceLoader = { reference }, baselineUpdater = updater,
            backupRepository = backups, backupListLoader = backupLoader ?: { backups.list(it) }, backupFolderOpener = folderOpener).also {
            it.loadInstalled()
            it.bindProject(project.path, true)
            it.openSource("bank_80.asm", 1)
        }
    }

    @Test fun `backup discovery and folder access do not change edits navigation or source ownership`() = runBlocking {
        var opened: File? = null
        val state = state(folderOpener = { opened = it })
        assertTrue(state.previewBaselineUpdate())
        assertTrue(state.applyBaselineUpdate(emptyMap(), rom))
        val source = state.projectSourceText("bank_80.asm")
        val edited = "$source; unsaved note\n"
        assertTrue(state.updateSourceEditText("bank_80.asm", edited))
        val navigation = state.navigationSerial
        val revision = state.sourceBufferRevision
        state.showBaselineBackups()
        assertTrue(state.baselineBackupsVisible)
        val selected = assertNotNull(state.baselineBackups).entries.single()
        assertTrue(state.revealBaselineBackup(selected))
        assertEquals(selected.id, opened?.name)
        assertEquals(navigation, state.navigationSerial)
        assertEquals(revision, state.sourceBufferRevision)
        assertEquals(edited, state.projectSourceText("bank_80.asm"))
        assertTrue(state.hasUnsavedSourceChanges("bank_80.asm"))
        assertTrue(state.deleteBaselineBackup(selected), state.baselineBackupsError)
        assertTrue(assertNotNull(state.baselineBackups).entries.isEmpty())
        assertEquals(edited, state.projectSourceText("bank_80.asm"))
        assertContains(state.baselineBackupsMessage.orEmpty(), "Active project source was not changed")
        state.closeBaselineBackups()
        assertFalse(state.baselineBackupsVisible)
        assertNull(state.baselineBackups)
    }

    @Test fun `backup failures stay in the dialog and stale deletion refreshes its confirmation data`() = runBlocking {
        val state = state(folderOpener = { error("Folder access unavailable") })
        assertTrue(state.previewBaselineUpdate())
        assertTrue(state.applyBaselineUpdate(emptyMap(), rom))
        state.showBaselineBackups()
        val selected = assertNotNull(state.baselineBackups).entries.single()
        assertFalse(state.revealBaselineBackup(selected))
        assertEquals("Folder access unavailable", state.baselineBackupsError)
        assertNull(state.error)
        val backup = File(temporary, "hack/asm-backups/${selected.id}")
        File(backup, "notes.txt").writeText("new notes after review")
        assertFalse(state.deleteBaselineBackup(selected))
        assertContains(state.baselineBackupsError.orEmpty(), "changed")
        assertNotEquals(selected.fingerprint, assertNotNull(state.baselineBackups).entries.single().fingerprint)
        assertTrue(backup.isDirectory)
        assertNull(state.error)
    }

    @Test fun `a late backup listing cannot revive a closed project or contaminate its next binding`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val state = state(backupLoader = { started.complete(Unit); release.await(); AsmBaselineBackupList(emptyList(), "old project") })
        val loading = launch { state.showBaselineBackups() }
        try {
            started.await()
            assertTrue(state.baselineBackupsBusy)
            state.bindProject("", false)
            release.complete(Unit)
            loading.join()
            assertFalse(state.baselineBackupsVisible)
            assertFalse(state.baselineBackupsBusy)
            assertNull(state.baselineBackups)
            assertNull(state.baselineBackupsError)
        } finally { release.complete(Unit); loading.join() }
    }

    @Test fun `cancelled backup discovery releases dialog controls without an error`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val state = state(backupLoader = { started.complete(Unit); release.await(); AsmBaselineBackupList(emptyList()) })
        val loading = launch { state.showBaselineBackups() }
        started.await()
        loading.cancelAndJoin()
        assertFalse(state.baselineBackupsBusy)
        assertNull(state.baselineBackupsError)
        state.closeBaselineBackups()
        assertFalse(state.baselineBackupsVisible)
    }

    @Test fun `rebinding the same project rejects an old listing without leaving controls busy`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val state = state(backupLoader = { started.complete(Unit); release.await(); AsmBaselineBackupList(emptyList(), "old binding") })
        val loading = launch { state.showBaselineBackups() }
        try {
            started.await()
            state.bindProject(File(temporary, "hack/Test.smedit").path, true)
            release.complete(Unit)
            loading.join()
            assertFalse(state.baselineBackupsBusy)
            assertNull(state.baselineBackups)
            state.closeBaselineBackups()
            assertFalse(state.baselineBackupsVisible)
        } finally { release.complete(Unit); loading.join() }
    }

    @Test fun `the backup dialog can close while a slow read finishes without reviving itself`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val state = state(backupLoader = { started.complete(Unit); release.await(); AsmBaselineBackupList(emptyList()) })
        val loading = launch { state.showBaselineBackups() }
        try {
            started.await()
            state.closeBaselineBackups()
            assertFalse(state.baselineBackupsVisible)
            release.complete(Unit)
            loading.join()
            assertNull(state.baselineBackups)
            assertFalse(state.baselineBackupsBusy)
        } finally { release.complete(Unit); loading.join() }
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

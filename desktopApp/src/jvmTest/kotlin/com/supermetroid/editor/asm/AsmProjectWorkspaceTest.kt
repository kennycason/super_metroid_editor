package com.supermetroid.editor.asm

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import com.supermetroid.editor.ui.asmOpenEditableReference
import com.supermetroid.editor.ui.asmEditableReferenceIsNavigable

class AsmProjectWorkspaceTest {
    @TempDir
    lateinit var tempDirectory: File

    private fun roomReference(): AsmReferenceWorkspace = fakeReference(emptyList()).also { reference ->
        File(reference.root, "src/bank_8F.asm").writeText("org ${'$'}8F8000\nTestObjects:\n    dw ${'$'}0000\n")
        File(reference.root, "src/bank_83.asm").writeText("org ${'$'}838000\nTestFx:\n    dw ${'$'}FFFF\n")
        File(reference.root, "src/macros.asm").writeText("; macros\n")
    }

    @Test fun `include definition navigation uses live operands and preserves project source buffers`() = runBlocking {
        val reference = roomReference()
        File(reference.root, "src/bank_82.asm").writeText("; Original bank 82\n")
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        val file = File(tempDirectory, "Include.smedit").apply { writeText("{}") }
        repository.initialize(file.path, reference)
        val state = AsmWorkspaceState(projectRepository = repository)
        state.bindProject(file.path, true)
        val bank = state.projectSourceText("bank_82.asm")
        state.replaceProjectSource("bank_82.asm", bank, "$bank; unsaved project edit\n")
        for (line in listOf("incsrc bank_82.asm", "incsrc \"bank_82.asm\" ; engine")) {
            state.openSource("main.asm")
            val page = "; unsaved page\n$line"
            val offset = page.indexOf("bank_82") + 4
            assertTrue(asmEditableReferenceIsNavigable(state.workspace?.index, "main.asm", page, offset, 500))
            assertTrue(asmOpenEditableReference(state, state.workspace?.index, "main.asm", page, offset, 500))
            assertEquals("bank_82.asm", state.selectedFileId)
            assertEquals(0, state.selectedLineIndex)
            assertEquals(AsmWorkspaceKind.PROJECT, state.workspaceKind)
            assertEquals("$bank; unsaved project edit\n", state.sourceEditText("bank_82.asm"))
        }
        val missing = "incsrc missing.asm"
        assertFalse(asmOpenEditableReference(state, state.workspace?.index, "main.asm", missing, missing.indexOf("missing"), 0))
        assertEquals("bank_82.asm", state.selectedFileId)
    }

    @Test fun `compound source undo is atomic from either bank and refuses newer edits`() = runBlocking {
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        val file = File(tempDirectory, "Compound.smedit").apply { writeText("{}") }
        repository.initialize(file.path, roomReference())
        val state = AsmWorkspaceState(projectRepository = repository)
        state.bindProject(file.path, true)
        val before = listOf(PLM_SOURCE_FILE, FX_SOURCE_FILE).associateWith { state.projectSourceText(it) }
        var model = 1
        var notifications = 0
        val replacements = before.mapValues { (_, text) -> text to "$text; compound\n" }
        state.replaceProjectSources(replacements,
            beforeUndo = { check(model == 1); model = 0 }, beforeRedo = { check(model == 0); model = 1 },
            afterUndo = { assertEquals(before, before.mapValues { state.projectSourceText(it.key) }); notifications++ },
            afterRedo = { assertTrue(before.keys.all { state.projectSourceText(it).endsWith("; compound\n") }); notifications++ })
        val fx = state.projectSourceText(FX_SOURCE_FILE)
        state.replaceProjectSource(FX_SOURCE_FILE, fx, "$fx; newer\n")
        assertFalse(state.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(1, model)
        assertEquals(replacements.getValue(PLM_SOURCE_FILE).second, state.projectSourceText(PLM_SOURCE_FILE))
        assertEquals(0, notifications)
        assertTrue(state.undoSourceEdit(FX_SOURCE_FILE))
        assertTrue(state.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(0, model)
        assertEquals(before, before.mapValues { state.projectSourceText(it.key) })
        assertTrue(state.redoSourceEdit(FX_SOURCE_FILE))
        assertEquals(1, model)
        assertEquals(2, notifications)
        assertTrue(state.undoSourceEdit(FX_SOURCE_FILE))
        state.replaceProjectSource(PLM_SOURCE_FILE, before.getValue(PLM_SOURCE_FILE), before.getValue(PLM_SOURCE_FILE) + "; branched\n")
        assertFalse(state.redoSourceEdit(FX_SOURCE_FILE), "A discarded member redo must never partially restore another bank")
        assertEquals(0, model)
        assertEquals(before.getValue(FX_SOURCE_FILE), state.projectSourceText(FX_SOURCE_FILE))
    }

    @Test fun `compound source validation and callback failures leave every document and history unchanged`() = runBlocking {
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        val file = File(tempDirectory, "Guarded.smedit").apply { writeText("{}") }
        repository.initialize(file.path, roomReference())
        val state = AsmWorkspaceState(projectRepository = repository)
        state.bindProject(file.path, true)
        val plms = state.projectSourceText(PLM_SOURCE_FILE)
        val fx = state.projectSourceText(FX_SOURCE_FILE)
        assertThrows<IllegalStateException> {
            state.replaceProjectSources(mapOf(PLM_SOURCE_FILE to (plms to "lost"), FX_SOURCE_FILE to ("stale" to "lost")))
        }
        assertEquals(plms, state.projectSourceText(PLM_SOURCE_FILE))
        assertFalse(state.canUndoSource(PLM_SOURCE_FILE))
        state.replaceProjectSources(mapOf(PLM_SOURCE_FILE to (plms to plms), FX_SOURCE_FILE to (fx to "$fx; changed\n")),
            beforeUndo = { error("Newer room metadata must be undone first") })
        val head = state.sourceUndoHead(PLM_SOURCE_FILE)
        assertFalse(state.undoSourceEdit(PLM_SOURCE_FILE))
        assertSame(head, state.sourceUndoHead(PLM_SOURCE_FILE))
        assertEquals("$fx; changed\n", state.projectSourceText(FX_SOURCE_FILE))
        assertFalse(state.canRedoSource(FX_SOURCE_FILE))
    }

    @Test fun `late global reference loading does not lock project edits or replace the project view`() = runBlocking {
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val file = File(tempDirectory, "Global Reference.smedit").apply { writeText("{}") }
        val reference = roomReference()
        repository.initialize(file.path, reference)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val state = AsmWorkspaceState(projectRepository = repository, installedReferenceLoader = {
            started.complete(Unit); release.await(); reference
        })
        val global = launch { state.loadInstalled() }
        try {
            withTimeout(5_000) { started.await() }
            state.bindProject(file.path, true, progressive = true)
            assertTrue(state.referenceLoading)
            assertFalse(state.busy)
            val original = state.projectSourceText(PLM_SOURCE_FILE)
            state.replaceProjectSource(PLM_SOURCE_FILE, original, original + "; fast edit\n")
            release.complete(Unit)
            global.join()
            state.awaitSourceIndexing()
            assertEquals(AsmWorkspaceKind.PROJECT, state.workspaceKind)
            assertEquals(original + "; fast edit\n", state.projectSourceText(PLM_SOURCE_FILE))
            assertTrue(state.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        } finally { release.complete(Unit); global.join(); state.awaitSourceIndexing() }
    }

    @Test fun `external authored changes and missing generated sources reconcile with recoverable old mirrors`() {
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val file = File(tempDirectory, "External.smedit").apply { writeText("{}") }
        val initial = repository.initialize(file.path, roomReference())
        val source = File(initial.workingDirectory, "src/$PLM_SOURCE_FILE")
        val original = source.readText()
        val saved = "$original; previously saved\n"
        repository.persistSource(file.path, PLM_SOURCE_FILE, saved, original)
        val authored = File(initial.root, "overrides/src/$PLM_SOURCE_FILE")
        val external = "$original; external canonical edit\n"
        authored.writeText(external)
        repository.loadRoomWorkspace(file.path)
        assertEquals(external, source.readText())
        assertEquals(external, authored.readText())
        val recovery = File(initial.workingDirectory.parentFile, "recovery").walkTopDown().filter { it.isFile }.toList()
        assertTrue(recovery.any { it.readText() == saved })
        source.delete() // A missing generated mirror is recoverable from the authored file.
        assertNotNull(repository.loadRoomWorkspace(file.path))
        assertEquals(external, source.readText())
        assertEquals(original, File(initial.originalDirectory, "src/$PLM_SOURCE_FILE").readText())
    }

    @Test fun `background mirror reconciliation cannot interleave the two copies of a source save`() = runBlocking {
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val file = File(tempDirectory, "Concurrent.smedit").apply { writeText("{}") }
        val initial = repository.initialize(file.path, roomReference())
        val source = File(initial.workingDirectory, "src/$PLM_SOURCE_FILE")
        val original = source.readText()
        val indexing = launch(Dispatchers.IO) { repeat(12) { repository.loadRoomWorkspace(file.path) } }
        val saving = launch(Dispatchers.IO) {
            var previous = original
            repeat(12) { iteration ->
                val next = "$original; saved $iteration\n"
                repository.persistSource(file.path, PLM_SOURCE_FILE, next, previous)
                previous = next
            }
        }
        indexing.join(); saving.join()
        assertEquals("$original; saved 11\n", source.readText())
        assertEquals(source.readText(), File(initial.root, "overrides/src/$PLM_SOURCE_FILE").readText())
        assertFalse(File(initial.workingDirectory.parentFile, "recovery").exists(), "Indexing must not observe half a save")
    }

    @Test fun `progressive startup exposes editable room source before the full index and keeps newer typing`() = runBlocking {
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val file = File(tempDirectory, "Progressive.smedit").apply { writeText("{}") }
        repository.initialize(file.path, roomReference())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val state = AsmWorkspaceState(projectRepository = repository, sourceIndexLoader = { path ->
            started.complete(Unit); release.await(); repository.load(path)
        })
        try {
            state.bindProject(file.path, true, progressive = true)
            assertEquals(false, state.projectWorkspace?.indexComplete)
            assertNotNull(state.workspace?.index?.file(PLM_SOURCE_FILE))
            assertNotNull(state.workspace?.index?.file(FX_SOURCE_FILE))
            assertTrue(state.projectSourceText(FX_SOURCE_FILE).contains("TestFx:"), "Room FX must never adopt an empty bank while the full IDE index is pending")
            assertEquals(null, state.workspace?.index?.file("bank_80.asm"))
            assertThrows<IllegalArgumentException> { state.projectSourceText("bank_80.asm") }
            assertTrue(state.sourceIndexingInProgress)
            assertFalse(state.busy)
            withTimeout(5_000) { started.await() }
            state.openSource(PLM_SOURCE_FILE, 2)
            val changed = state.projectSourceText(PLM_SOURCE_FILE) + "; newer typing\n"
            state.updateSourceEditText(PLM_SOURCE_FILE, changed)
            val fxBefore = state.projectSourceText(FX_SOURCE_FILE)
            state.replaceProjectSource(FX_SOURCE_FILE, fxBefore, fxBefore + "; newer FX\n")
            val caret = state.selectedLineIndex to state.selectedColumnIndex
            assertTrue(state.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
            assertTrue(state.saveAllSources(), state.error)
            assertEquals(fxBefore + "; newer FX\n", File(state.projectWorkspace!!.workingDirectory, "src/$FX_SOURCE_FILE").readText())
            release.complete(Unit)
            state.awaitSourceIndexing()
            assertEquals(true, state.projectWorkspace?.indexComplete)
            assertNotNull(state.workspace?.index?.file("bank_80.asm"))
            assertEquals(changed, state.projectSourceText(PLM_SOURCE_FILE))
            assertEquals(fxBefore + "; newer FX\n", state.projectSourceText(FX_SOURCE_FILE))
            assertFalse(state.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
            assertFalse(state.hasUnsavedSourceChanges(FX_SOURCE_FILE))
            assertEquals(caret, state.selectedLineIndex to state.selectedColumnIndex)
        } finally { release.complete(Unit); state.awaitSourceIndexing() }
    }

    @Test fun `a failed full index retains the room workspace and can be retried without editing source`() = runBlocking {
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val file = File(tempDirectory, "Retry.smedit").apply { writeText("{}") }
        repository.initialize(file.path, roomReference())
        var fail = true
        val state = AsmWorkspaceState(projectRepository = repository, sourceIndexLoader = { path ->
            if (fail) error("broken index fixture") else repository.load(path)
        })
        state.bindProject(file.path, true, progressive = true)
        state.awaitSourceIndexing()
        assertEquals("broken index fixture", state.sourceIndexFailure)
        assertEquals(false, state.projectWorkspace?.indexComplete)
        assertNotNull(state.workspace?.index?.file(PLM_SOURCE_FILE))
        fail = false
        state.rebuildSourceIndex()
        state.awaitSourceIndexing()
        assertEquals(true, state.projectWorkspace?.indexComplete)
        assertEquals(null, state.sourceIndexFailure)
        assertEquals(null, state.error)
        assertTrue(state.unsavedSourceFileIds.isEmpty())
    }

    @Test fun `optional navigation snapshots wait for all dependencies and preserve live unsaved source`() = runBlocking {
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        val file = File(tempDirectory, "Navigation.smedit").apply { writeText("{}") }
        val reference = roomReference()
        File(reference.root, "src/empty.asm").writeText("")
        repository.initialize(file.path, reference)
        val release = CompletableDeferred<Unit>()
        val state = AsmWorkspaceState(projectRepository = repository, sourceIndexLoader = { path ->
            release.await(); repository.load(path)
        })
        assertNull(state.projectSourceTextsForNavigation(listOf(PLM_SOURCE_FILE)))
        try {
            state.bindProject(file.path, true, progressive = true)
            val before = state.projectSourceText(PLM_SOURCE_FILE)
            val edited = "$before; unsaved navigation fixture\n"
            state.replaceProjectSource(PLM_SOURCE_FILE, before, edited)
            val revision = state.sourceBufferRevision
            assertEquals(edited, state.projectSourceTextsForNavigation(listOf(PLM_SOURCE_FILE))?.get(PLM_SOURCE_FILE))
            assertNull(state.projectSourceTextsForNavigation(listOf(PLM_SOURCE_FILE, "bank_80.asm")))
            assertThrows<IllegalArgumentException> { state.projectSourceText("bank_80.asm") }
            assertEquals(revision, state.sourceBufferRevision)
            assertEquals(setOf(PLM_SOURCE_FILE), state.unsavedSourceFileIds.toSet())
            release.complete(Unit)
            state.awaitSourceIndexing()
            val loaded = state.projectSourceTextsForNavigation(listOf(PLM_SOURCE_FILE, "bank_80.asm", "empty.asm"))
            assertNotNull(loaded)
            assertEquals(edited, loaded!![PLM_SOURCE_FILE])
            assertTrue(loaded.getValue("bank_80.asm").contains("Boot:"))
            assertEquals("", loaded["empty.asm"], "An actual empty file is available, not a missing dependency")
            assertNull(state.projectSourceTextsForNavigation(listOf("missing.asm")))
            assertEquals(setOf(PLM_SOURCE_FILE), state.unsavedSourceFileIds.toSet())
        } finally { release.complete(Unit); state.awaitSourceIndexing() }
    }

    @Test fun `optional navigation tolerates a missing indexed file without weakening semantic reads`() = runBlocking {
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        val file = File(tempDirectory, "Missing source.smedit").apply { writeText("{}") }
        repository.initialize(file.path, roomReference())
        val state = AsmWorkspaceState(projectRepository = repository)
        state.bindProject(file.path, true)
        val source = state.projectWorkspace!!.referenceWorkspace.index.file("bank_80.asm")!!.file
        assertTrue(source.delete(), "Remove only this temporary fixture's uncached source")
        assertNull(state.projectSourceTextsForNavigation(listOf("bank_80.asm")))
        assertThrows<java.io.IOException> { state.projectSourceText("bank_80.asm") }
        assertTrue(state.unsavedSourceFileIds.isEmpty())
        assertFalse(File(state.projectWorkspace!!.root, "overrides/src/bank_80.asm").exists())
    }

    @Test fun `every patch navigation provider tolerates unavailable banks during startup`() = runBlocking {
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        val file = File(tempDirectory, "Patch navigation.smedit").apply { writeText("{}") }
        repository.initialize(file.path, roomReference())
        val release = CompletableDeferred<Unit>()
        val state = AsmWorkspaceState(projectRepository = repository, sourceIndexLoader = { path ->
            release.await(); repository.load(path)
        })
        val owner = com.supermetroid.editor.data.SmEditProject("fixture.sfc")
        val project = { owner }
        val changed: () -> Unit = { error("Navigation must not publish changes") }
        val enemy = AsmEnemyTuningBridge(AsmEnemyTuningSource("", ""), state, project, changed)
        val boss = AsmBossStatsBridge(AsmBossStatsSource(""), state, project, changed)
        val beam = AsmBeamDamageBridge(AsmBeamDamageSource(""), state, project, changed)
        val environment = AsmEnvironmentalDamageBridge(AsmEnvironmentalDamageSource(
            ENVIRONMENT_SOURCE_FILES.associateWith { "" }), state, project, changed)
        val data = AsmDataPatchBridge(AsmDataPatchSource(mapOf("bank_80.asm" to "")), state, project, changed)
        val cases: List<Pair<String, (String, Int) -> List<AsmEditorLink>>> = listOf(
            ENEMY_HEADER_SOURCE_FILE to enemy::editorLinks,
            ENEMY_DROP_SOURCE_FILE to enemy::editorLinks,
            ENEMY_HEADER_SOURCE_FILE to boss::editorLinks,
            BEAM_DAMAGE_SOURCE_FILE to beam::editorLinks,
            ENVIRONMENT_HEAT_FILE to environment::editorLinks,
            ENVIRONMENT_LIQUID_FILE to environment::editorLinks,
            "bank_80.asm" to data::editorLinks,
        )
        try {
            state.bindProject(file.path, true, progressive = true)
            val revision = state.sourceBufferRevision
            for ((bank, links) in cases) {
                assertTrue(links(bank, 0).isEmpty(), bank)
                assertTrue(links(PLM_SOURCE_FILE, 0).isEmpty(), "Unrelated room bank: $bank")
            }
            assertEquals(revision, state.sourceBufferRevision)
            assertTrue(state.unsavedSourceFileIds.isEmpty())
        } finally { release.complete(Unit); state.awaitSourceIndexing() }
    }

    @Test fun `switching projects rejects a late non-cooperative startup index`() = runBlocking {
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val a = File(tempDirectory, "A/A.smedit").apply { parentFile.mkdirs(); writeText("{}") }
        val b = File(tempDirectory, "B/B.smedit").apply { parentFile.mkdirs(); writeText("{}") }
        val reference = roomReference()
        repository.initialize(a.path, reference)
        repository.initialize(b.path, reference)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val state = AsmWorkspaceState(projectRepository = repository, sourceIndexLoader = { path ->
            val snapshot = repository.load(path)
            if (path == a.path) withContext(NonCancellable) {
                started.complete(Unit); release.await(); finished.complete(Unit)
            }
            snapshot
        })
        try {
            state.bindProject(a.path, true, progressive = true)
            withTimeout(5_000) { started.await() }
            state.bindProject(b.path, true, progressive = true)
            state.awaitSourceIndexing()
            val selected = state.projectWorkspace
            assertEquals(File(b.parentFile, "asm"), selected?.root)
            release.complete(Unit)
            withTimeout(5_000) { finished.await() }
            assertSame(selected, state.projectWorkspace)
            assertFalse(state.sourceIndexingInProgress)
        } finally { release.complete(Unit) }
    }

    @Test
    fun `save persists immediately while indexing waits and keeps later typing and caret intact`() = runBlocking {
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val file = File(tempDirectory, "Fast Save.smedit").apply { writeText("{}") }
        val created = repository.initialize(file.path, fakeReference(emptyList()))
        val indexingStarted = CompletableDeferred<Unit>()
        val releaseIndex = CompletableDeferred<Unit>()
        val state = AsmWorkspaceState(projectRepository = repository, sourceIndexLoader = { path ->
            indexingStarted.complete(Unit)
            releaseIndex.await()
            repository.load(path)
        })
        try {
            state.bindProject(file.path, true)
            state.openSource("bank_80.asm", 3, columnIndex = 4)
            val saved = state.sourceEditText("bank_80.asm") + "\nSavedLabel:\n    RTS\n"
            state.updateSourceEditText("bank_80.asm", saved)
            assertTrue(state.saveSource("bank_80.asm"))
            assertEquals(saved, File(created.workingDirectory, "src/bank_80.asm").readText())
            assertEquals(saved, File(created.root, "overrides/src/bank_80.asm").readText())
            assertFalse(state.busy)
            assertFalse(state.hasUnsavedSourceChanges("bank_80.asm"))
            assertTrue(state.sourceIndexingInProgress)
            assertTrue(state.sourceBuildRequired)
            withTimeout(5_000) { indexingStarted.await() }
            val typed = "$saved; newer unsaved typing\n"
            state.updateSourceEditText("bank_80.asm", typed)
            val caret = state.selectedLineIndex to state.selectedColumnIndex
            releaseIndex.complete(Unit)
            state.awaitSourceIndexing()
            assertEquals(typed, state.sourceEditText("bank_80.asm"))
            assertTrue(state.hasUnsavedSourceChanges("bank_80.asm"))
            assertEquals(caret, state.selectedLineIndex to state.selectedColumnIndex)
            assertFalse(state.sourceIndexingInProgress)
            assertEquals("SavedLabel", state.workspace?.index?.labels?.firstOrNull { it.name == "SavedLabel" }?.name)
        } finally {
            releaseIndex.complete(Unit)
            state.awaitSourceIndexing()
        }
    }

    @Test
    fun `an older non-cooperative index job cannot replace the index from a newer save`() = runBlocking {
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val file = File(tempDirectory, "Index Generations.smedit").apply { writeText("{}") }
        repository.initialize(file.path, fakeReference(emptyList()))
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        var count = 0
        val state = AsmWorkspaceState(projectRepository = repository, sourceIndexLoader = { path ->
            val snapshot = repository.load(path)
            if (++count == 1) withContext(NonCancellable) {
                oldStarted.complete(Unit)
                releaseOld.await()
                oldFinished.complete(Unit)
            }
            snapshot
        })
        try {
            state.bindProject(file.path, true)
            val initial = state.projectSourceText("bank_80.asm")
            state.updateSourceEditText("bank_80.asm", "$initial\nOldLabel:\n    RTS\n")
            assertTrue(state.saveSource("bank_80.asm"))
            withTimeout(5_000) { oldStarted.await() }
            val newest = "$initial\nNewLabel:\n    RTS\n"
            state.updateSourceEditText("bank_80.asm", newest)
            assertTrue(state.saveSource("bank_80.asm"))
            state.awaitSourceIndexing()
            releaseOld.complete(Unit)
            withTimeout(5_000) { oldFinished.await() }
            assertEquals(newest.trimEnd(), state.workspace?.index?.file("bank_80.asm")?.lines?.joinToString("\n")?.trimEnd())
            assertEquals(newest, state.sourceEditText("bank_80.asm"))
        } finally { releaseOld.complete(Unit) }
    }

    @Test
    fun `background source validation cannot replace a newer completed build report`() = runBlocking {
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val file = File(tempDirectory, "Build During Index.smedit").apply { writeText("{}") }
        repository.initialize(file.path, fakeReference(emptyList()))
        val artifacts = AsmBuildArtifactRepository(repository, sourceFingerprintProvider = { "fixture" })
        val release = CompletableDeferred<Unit>()
        val state = AsmWorkspaceState(projectRepository = repository, buildArtifactRepository = artifacts,
            sourceIndexLoader = { path -> release.await(); repository.load(path) })
        try {
            state.bindProject(file.path, true)
            state.updateSourceEditText("bank_80.asm", state.projectSourceText("bank_80.asm") + "\n; saved change\n")
            assertTrue(state.saveSource("bank_80.asm"))
            artifacts.publishSuccess(file.path, ByteArray(0), listOf("newer build succeeded"), "fixture")
            state.refreshBuildReport()
            val built = state.buildReport
            assertEquals(true, built?.succeeded)
            release.complete(Unit)
            state.awaitSourceIndexing()
            assertEquals(built, state.buildReport)
        } finally { release.complete(Unit); state.awaitSourceIndexing() }
    }

    @Test
    fun `source text changes round trip insert delete and replacement deltas`() {
        listOf(
            "abcde" to "abXYcde",
            "abcde" to "abde",
            "abcde" to "abXYde",
        ).forEach { (before, after) ->
            val change = requireNotNull(AsmSourceTextChange.between(before, after))
            assertEquals(after, change.redo(before))
            assertEquals(before, change.undo(after))
        }
    }

    @Test
    fun `failed build opens editable source from another ASM view with its located error`() = runBlocking {
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val file = File(tempDirectory, "Build Failure.smedit").apply { writeText("{}") }
        repository.initialize(file.path, fakeReference(emptyList()))
        val state = AsmWorkspaceState(projectRepository = repository)
        state.bindProject(file.path, true)
        state.showInstruction("LDA")

        assertFalse(state.validateProjectBuild { error("bank_80.asm:3: Unsupported source record") })
        val failure = requireNotNull(state.buildReport)
        state.showBuildFailure()
        assertEquals(AsmWorkspaceKind.PROJECT, state.workspaceKind)
        assertEquals(AsmBrowserMode.SOURCE, state.browserMode)
        assertEquals("bank_80.asm", state.selectedFileId)
        assertEquals(2, state.selectedLineIndex)
        assertEquals(failure, state.buildReport, "Opening the error must retain the failed report")

        state.updateSourceEditText("bank_80.asm", state.projectSourceText("bank_80.asm") + "\n; changed")
        state.showInstruction("STA")
        assertFalse(state.validateProjectBuild { error("Patch writes overlap") })
        state.showBuildFailure()
        assertEquals(AsmBrowserMode.SOURCE, state.browserMode)
        assertEquals("Patch writes overlap", state.displayedDiagnostics.single().message)
        assertEquals(null, state.displayedDiagnostics.single().fileId, "A general patch failure has no invented source line")
    }

    @Test
    fun `project workspace is isolated persistent and restorable from its offline snapshot`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "My Hack.smedit").apply { writeText("{}") }
        val referenceSource = File(reference.root, "src/bank_80.asm")
        val originalText = referenceSource.readText()

        val created = repository.initialize(projectFile.absolutePath, reference)

        assertTrue(File(created.originalDirectory, "src/bank_80.asm").isFile)
        assertTrue(File(created.workingDirectory, "src/bank_80.asm").isFile)
        assertEquals(File(tempDirectory, "asm"), created.root)
        assertEquals(File(tempDirectory, "smedit/asm/workspace"), created.workingDirectory)
        assertEquals(emptySet<String>(), created.modifiedFileIds)

        val editedText = originalText.replace("RTS", "NOP\n    RTS")
        val saved = repository.saveSource(projectFile.absolutePath, "bank_80.asm", editedText)

        assertEquals(originalText, referenceSource.readText(), "project edits must never mutate the global reference")
        assertEquals(editedText, File(saved.workingDirectory, "src/bank_80.asm").readText())
        assertEquals(editedText, File(saved.root, "overrides/src/bank_80.asm").readText())
        assertEquals("*\n", File(tempDirectory, "smedit/.gitignore").readText())
        assertEquals(setOf("bank_80.asm"), saved.modifiedFileIds)
        assertEquals(setOf("bank_80.asm"), repository.sourceOverrideFileIds(projectFile.absolutePath))
        val reopened = requireNotNull(repository.load(projectFile.absolutePath))
        assertNotNull(reopened)
        assertEquals(editedText, reopened.referenceWorkspace.index.file("bank_80.asm")?.file?.readText())

        // The immutable project snapshot, not the global cache, owns restore.
        referenceSource.writeText("Global reference changed after project creation")
        val restored = repository.restoreSource(projectFile.absolutePath, "bank_80.asm")
        assertEquals(originalText, File(restored.workingDirectory, "src/bank_80.asm").readText())
        assertFalse(File(restored.root, "overrides/src/bank_80.asm").exists())
        assertEquals(emptySet<String>(), restored.modifiedFileIds)
        assertEquals(emptySet<String>(), repository.sourceOverrideFileIds(projectFile.absolutePath))
    }

    @Test
    fun `source save refuses an externally changed authored override before writing either copy`() {
        val reference = fakeReference(emptyList())
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val file = File(tempDirectory, "External.smedit").apply { writeText("{}") }
        val workspace = repository.initialize(file.path, reference)
        val target = File(workspace.workingDirectory, "src/bank_80.asm")
        val original = target.readText()
        val authored = File(workspace.root, "overrides/src/bank_80.asm").apply { parentFile.mkdirs() }
        authored.writeText("; external source\n$original")
        assertThrows<IllegalArgumentException> {
            repository.saveSource(file.path, "bank_80.asm", "$original\n; GUI change", expectedText = original)
        }
        assertEquals(original, target.readText())
        assertEquals("; external source\n$original", authored.readText())
    }

    @Test
    fun `source writes cannot escape the managed project tree`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Safe.smedit").apply { writeText("{}") }
        repository.initialize(projectFile.absolutePath, reference)

        assertThrows<IllegalArgumentException> {
            repository.saveSource(projectFile.absolutePath, "../escaped.asm", "nope")
        }
        assertFalse(File(tempDirectory, "smedit/asm/workspace/escaped.asm").exists())
    }

    @Test
    fun `failed initialization does not activate a partial workspace`() {
        val range = AsmAssetRange("Tiles_Missing.bin", 0x10, 4)
        val reference = fakeReference(emptyList())
        val repository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Broken.smedit").apply { writeText("{}") }

        assertThrows<IllegalArgumentException> {
            repository.initialize(projectFile.absolutePath, reference)
        }
        assertFalse(File(tempDirectory, "smedit/asm").exists())
        assertTrue(File(tempDirectory, "smedit").listFiles().orEmpty().none { it.name.startsWith(".asm.installing-") })
    }

    @Test
    fun `new workspaces include the objectives reference module disabled`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range))
        val projectFile = File(tempDirectory, "Starter.smedit").apply { writeText("{}") }

        val workspace = repository.initialize(projectFile.absolutePath, reference)
        val fileId = "project/objectives_pause_screen.asm"

        assertEquals(listOf(fileId), workspace.projectModuleFileIds)
        assertEquals(setOf(fileId), workspace.disabledProjectModuleFileIds)
        assertTrue(File(workspace.root, "modules/objectives_pause_screen.asm").readText().contains("Objectives_MainPauseRoutine"))
        assertEquals(emptySet<String>(), repository.sourceOverrideFileIds(projectFile.absolutePath))
    }

    @Test
    fun `repair preserves an incomplete prior sidecar as a recoverable backup`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Repair.smedit").apply { writeText("{}") }
        val incomplete = File(tempDirectory, "smedit/asm").apply { mkdirs() }
        File(incomplete, "user-notes.txt").writeText("preserve me")

        val repaired = repository.initialize(projectFile.absolutePath, reference)

        assertTrue(File(repaired.workingDirectory, "src/main.asm").isFile)
        val backup = File(tempDirectory, "smedit").listFiles().orEmpty()
            .single { it.name.startsWith(".asm.incomplete-backup-") }
        assertEquals("preserve me", File(backup, "user-notes.txt").readText())
    }

    @Test
    fun `recreating a local tree reapplies trackable source overrides`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Portable.smedit").apply { writeText("{}") }
        val root = File(tempDirectory, "asm")
        File(root, "overrides/src").mkdirs()
        val overrideText = "org ${'$'}808000\nBoot:\n    NOP\n    RTS\n"
        File(root, "overrides/src/bank_80.asm").writeText(overrideText)

        val recreated = repository.initialize(projectFile.absolutePath, reference)

        assertEquals(overrideText, File(recreated.workingDirectory, "src/bank_80.asm").readText())
        assertEquals(setOf("bank_80.asm"), recreated.modifiedFileIds)
        assertEquals(overrideText, File(recreated.root, "overrides/src/bank_80.asm").readText())
    }

    @Test
    fun `project modules are portable ordered editable and separate from bank overrides`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Modules.smedit").apply { writeText("{}") }
        repository.initialize(projectFile.absolutePath, reference)

        val (withFirst, firstId) = repository.createModule(projectFile.absolutePath, "first hook")
        val (withSecond, secondId) = repository.createModule(projectFile.absolutePath, "second_hook.asm")

        assertEquals("project/first_hook.asm", firstId)
        assertEquals("project/second_hook.asm", secondId)
        assertEquals(listOf(firstId), withFirst.projectModuleFileIds)
        assertEquals(listOf(firstId, secondId), withSecond.projectModuleFileIds)
        assertTrue(File(withSecond.root, "modules/first_hook.asm").isFile)
        assertTrue(File(withSecond.workingDirectory, "src/project/first_hook.asm").isFile)
        assertFalse(File(withSecond.root, "overrides/src/project/first_hook.asm").exists())

        val moduleText = "; first module\norg ${'$'}808000\nProjectHook:\n    NOP\n"
        val saved = repository.saveSource(projectFile.absolutePath, firstId, moduleText)
        assertEquals(moduleText, File(saved.root, "modules/first_hook.asm").readText())
        assertEquals(moduleText, File(saved.workingDirectory, "src/project/first_hook.asm").readText())
        assertEquals(setOf(firstId, secondId), repository.sourceOverrideFileIds(projectFile.absolutePath))

        val disabled = repository.setModuleEnabled(projectFile.absolutePath, firstId, enabled = false)
        assertEquals(setOf(firstId), disabled.disabledProjectModuleFileIds)
        assertEquals("first_hook.asm\n", File(disabled.root, "modules/.disabled").readText())
        assertEquals("first_hook.asm\n", File(disabled.workingDirectory, "src/project/.disabled").readText())
        assertEquals(setOf(secondId), repository.sourceOverrideFileIds(projectFile.absolutePath))
        assertEquals(setOf(firstId), requireNotNull(repository.load(projectFile.absolutePath)).disabledProjectModuleFileIds)

        val reordered = repository.moveModule(projectFile.absolutePath, secondId, -1)
        assertEquals(listOf(secondId, firstId), reordered.projectModuleFileIds)
        assertEquals("second_hook.asm\nfirst_hook.asm\n", File(reordered.root, "modules/.order").readText())

        val (renamed, renamedId) = repository.renameModule(projectFile.absolutePath, secondId, "setup")
        assertEquals("project/setup.asm", renamedId)
        assertEquals(listOf(renamedId, firstId), renamed.projectModuleFileIds)
        assertFalse(File(renamed.root, "modules/second_hook.asm").exists())
        assertTrue(File(renamed.root, "modules/setup.asm").isFile)

        val deleted = repository.deleteModule(projectFile.absolutePath, firstId)
        assertEquals(listOf(renamedId), deleted.projectModuleFileIds)
        assertEquals(emptySet<String>(), deleted.disabledProjectModuleFileIds)
        assertFalse(File(deleted.root, "modules/.disabled").exists())
        assertEquals(listOf(renamedId), requireNotNull(repository.load(projectFile.absolutePath)).projectModuleFileIds)
        assertEquals(setOf(renamedId), repository.sourceOverrideFileIds(projectFile.absolutePath))
    }

    @Test
    fun `cache repair preserves authored overrides modules order and disabled state`() {
        val reference = fakeReference(emptyList())
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Repair authored.smedit").apply { writeText("{}") }
        val initial = repository.initialize(projectFile.path, reference)
        val (created, moduleId) = repository.createModule(projectFile.path, "keep_me")
        repository.setModuleEnabled(projectFile.path, moduleId, enabled = false)
        val override = File(initial.originalDirectory, "src/bank_80.asm").readText().replace("RTS", "NOP\n    RTS")
        repository.saveSource(projectFile.path, "bank_80.asm", override)
        val authored = created.root.walkTopDown().filter(File::isFile)
            .associate { it.relativeTo(created.root).path to it.readBytes().toList() }
        // Simulate moving only the portable project inputs to another machine.
        check(requireNotNull(repository.cacheFor(projectFile.path)).deleteRecursively())
        val repaired = repository.initialize(projectFile.path, reference)
        assertEquals(authored, repaired.root.walkTopDown().filter(File::isFile)
            .associate { it.relativeTo(repaired.root).path to it.readBytes().toList() })
        assertEquals(override, File(repaired.workingDirectory, "src/bank_80.asm").readText())
        assertEquals(setOf(moduleId), repaired.disabledProjectModuleFileIds)
    }

    @Test
    fun `generated module mirror never resurrects a removed authored module`() {
        val reference = fakeReference(emptyList())
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val projectFile = File(tempDirectory, "No resurrection.smedit").apply { writeText("{}") }
        repository.initialize(projectFile.path, reference)
        val (workspace, _) = repository.createModule(projectFile.path, "temporary_hook")
        val mirror = File(workspace.workingDirectory, "src/project/temporary_hook.asm")
        assertTrue(mirror.exists())
        check(File(workspace.root, "modules").deleteRecursively())
        val loaded = requireNotNull(repository.load(projectFile.path))
        assertEquals(emptyList<String>(), loaded.projectModuleFileIds)
        assertFalse(mirror.exists())
        assertFalse(File(workspace.root, "modules/temporary_hook.asm").exists())
    }

    @Test
    fun `workspace state tracks unsaved saved and restored project source`() = runBlocking {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val projectRepository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "State.smedit").apply { writeText("{}") }
        projectRepository.initialize(projectFile.absolutePath, reference)
        val buildArtifacts = AsmBuildArtifactRepository(projectRepository)
        buildArtifacts.publishSuccess(
            projectFilePath = projectFile.absolutePath,
            symbols = ByteArray(0),
            output = listOf("Build succeeded"),
            assemblerVersion = "fixture",
        )
        val state = AsmWorkspaceState(
            repository = AsmReferenceRepository(
                referenceRoot = File(tempDirectory, "unused-global-cache"),
                fetchBytes = { error("network should not be used") },
                assetRanges = listOf(range),
            ),
            projectRepository = projectRepository,
            buildArtifactRepository = buildArtifacts,
        )

        state.bindProject(projectFile.absolutePath, enabled = true)
        state.openSource("bank_80.asm", lineIndex = 3, columnIndex = 4)
        assertEquals(
            AsmWorkspaceKind.PROJECT,
            (state.locationSnapshot() as AsmWorkspaceLocation.Source).workspaceKind,
        )
        assertEquals(4, (state.locationSnapshot() as AsmWorkspaceLocation.Source).columnIndex)
        assertTrue(state.isProjectSourceEditable)
        assertFalse(state.sourceBuildRequired)
        val bufferRevision = state.sourceBufferRevision
        val inspected = AsmRoomSourceInspection(0x9879, "Layout ASM", "dw \$0200\n", stateId = "state-1", fileId = "bank_80.asm", startEditing = {})
        state.openRoomSourceInspection(inspected)
        assertFalse(state.isProjectSourceEditable)
        assertFalse(state.sourceBuildRequired, "Inspection must retain a successful build without writing source")
        assertFalse(state.hasProjectSourceChanges)
        assertEquals(bufferRevision, state.sourceBufferRevision)
        assertEquals(inspected, (state.locationSnapshot() as AsmWorkspaceLocation.RoomSourceInspection).snapshot)
        state.updateSourceEditText("bank_80.asm", "must not replace the real bank")
        assertFalse(state.sourceBuildRequired)
        state.openSource("bank_80.asm", lineIndex = 3)
        assertNull(state.roomSourceInspection)
        val original = state.sourceEditText("bank_80.asm")
        state.updateSourceEditText("bank_80.asm", "$original\n; project edit")

        assertTrue(state.hasUnsavedSourceChanges("bank_80.asm"))
        assertTrue(state.sourceBuildRequired)
        assertTrue(state.hasProjectSourceChanges)
        assertEquals(setOf("bank_80.asm"), state.unsavedSourceFileIds)
        state.discardSourceBuffer("bank_80.asm")
        assertFalse(state.sourceBuildRequired, "reverting an unsaved edit retains the matching successful build")
        state.updateSourceEditText("bank_80.asm", "$original\n; project edit")
        assertTrue(state.saveSource("bank_80.asm"))
        assertFalse(state.hasUnsavedSourceChanges("bank_80.asm"))
        assertTrue(state.sourceBuildRequired, "newly saved source requires an explicit build")
        assertEquals(setOf("bank_80.asm"), state.projectModifiedFileIds)

        assertTrue(state.restoreOriginalSource("bank_80.asm"))
        assertEquals(original, state.sourceEditText("bank_80.asm"))
        assertEquals(emptySet<String>(), state.projectModifiedFileIds)
        assertFalse(state.hasProjectSourceChanges)
    }

    @Test
    fun `project source undo and redo operate on the shared document buffer`() = runBlocking {
        val reference = fakeReference(emptyList())
        val projectRepository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Undo.smedit").apply { writeText("{}") }
        projectRepository.initialize(projectFile.absolutePath, reference)
        val state = AsmWorkspaceState(
            repository = AsmReferenceRepository(
                referenceRoot = File(tempDirectory, "unused-undo-cache"),
                fetchBytes = { error("network should not be used") },
                assetRanges = emptyList(),
            ),
            projectRepository = projectRepository,
        )
        state.bindProject(projectFile.absolutePath, enabled = true)
        state.openSource("bank_80.asm")
        val primaryPane = state.activeSourcePaneId
        state.openSourceInSplit("bank_80.asm", lineIndex = 2)
        val original = state.sourceEditText("bank_80.asm")

        state.updateSourceEditText("bank_80.asm", "$original; first\n")
        state.activateSourcePane(primaryPane)
        assertEquals(
            "$original; first\n",
            state.sourceEditText(requireNotNull(state.sourcePane(primaryPane)?.fileId)),
            "both panes must address the same source document",
        )
        state.updateSourceEditText("bank_80.asm", "$original; first\n; second\n")
        assertTrue(state.canUndoSource("bank_80.asm"))
        assertTrue(state.undoSourceEdit("bank_80.asm"))
        assertEquals("$original; first\n", state.sourceEditText("bank_80.asm"))
        assertTrue(state.undoSourceEdit("bank_80.asm"))
        assertEquals(original, state.sourceEditText("bank_80.asm"))
        assertFalse(state.hasUnsavedSourceChanges("bank_80.asm"))

        assertTrue(state.redoSourceEdit("bank_80.asm"))
        assertEquals("$original; first\n", state.sourceEditText("bank_80.asm"))
        assertTrue(state.hasUnsavedSourceChanges("bank_80.asm"))
    }

    @Test
    fun `workspace state manages project modules as saved build inputs`() = runBlocking {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val projectRepository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "ModuleState.smedit").apply { writeText("{}") }
        projectRepository.initialize(projectFile.absolutePath, reference)
        val state = AsmWorkspaceState(
            repository = AsmReferenceRepository(
                referenceRoot = File(tempDirectory, "unused-module-cache"),
                fetchBytes = { error("network should not be used") },
                assetRanges = listOf(range),
            ),
            projectRepository = projectRepository,
        )
        state.bindProject(projectFile.absolutePath, enabled = true)

        assertTrue(state.createProjectModule("door hook", "Hooks a custom Landing Site door."))
        val fileId = "project/door_hook.asm"
        assertEquals(listOf(fileId), state.projectModuleFileIds)
        assertEquals(fileId, state.selectedFileId)
        assertTrue(state.isProjectModule(fileId))
        assertTrue(state.isProjectModuleEnabled(fileId))
        assertTrue(state.hasProjectSourceChanges)
        assertTrue(state.sourceBuildRequired)
        assertEquals("Hooks a custom Landing Site door.", state.workspace?.index?.file(fileId)?.description)
        assertEquals(
            "Hooks a custom Landing Site door.",
            projectModuleDescription(state.sourceEditText(fileId)),
        )

        assertTrue(state.setProjectModuleEnabled(fileId, enabled = false))
        assertFalse(state.isProjectModuleEnabled(fileId))
        assertEquals(setOf(fileId), state.disabledProjectModuleFileIds)
        assertFalse(state.hasProjectSourceChanges)
        assertTrue(state.setProjectModuleEnabled(fileId, enabled = true))
        assertTrue(state.isProjectModuleEnabled(fileId))

        val original = state.sourceEditText(fileId)
        state.updateSourceEditText(fileId, "$original\nDoorHook:\n    RTS\n")
        assertTrue(state.hasUnsavedSourceChanges(fileId))
        assertTrue(state.saveSource(fileId))
        assertFalse(state.hasUnsavedSourceChanges(fileId))
        assertTrue(state.setProjectModuleEnabled(fileId, enabled = false))

        assertTrue(
            state.updateProjectModuleSettings(
                fileId,
                "door_runtime",
                "Runtime door hooks\nfor the custom room.",
            ),
        )
        val renamed = "project/door_runtime.asm"
        assertEquals(listOf(renamed), state.projectModuleFileIds)
        assertEquals(renamed, state.selectedFileId)
        assertFalse(state.isProjectModuleEnabled(renamed), "settings must preserve the ON/OFF manifest")
        assertEquals("Runtime door hooks for the custom room.", state.workspace?.index?.file(renamed)?.description)
        val renamedText = state.sourceEditText(renamed)
        assertTrue(renamedText.contains("; Project module: door runtime"))
        assertTrue(renamedText.contains("; SMEDIT description: Runtime door hooks for the custom room."))
        assertTrue(renamedText.contains("DoorHook:\n    RTS"), "settings must preserve authored source")
        assertTrue(state.deleteProjectModule(renamed))
        assertEquals(emptyList<String>(), state.projectModuleFileIds)
        assertFalse(state.hasProjectSourceChanges)
    }

    @Test
    fun `reviewed project replacement stages buffers without writing source`() = runBlocking {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val projectRepository = AsmProjectWorkspaceRepository(listOf(range), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Replace.smedit").apply { writeText("{}") }
        val project = projectRepository.initialize(projectFile.absolutePath, reference)
        val state = AsmWorkspaceState(
            repository = AsmReferenceRepository(
                referenceRoot = File(tempDirectory, "unused-global-cache"),
                fetchBytes = { error("network should not be used") },
                assetRanges = listOf(range),
            ),
            projectRepository = projectRepository,
        )
        state.bindProject(projectFile.absolutePath, enabled = true)
        val diskSource = File(project.workingDirectory, "src/bank_80.asm")
        val original = diskSource.readText()
        val preview = state.previewProjectSourceReplacement("RTS", "NOP", caseSensitive = true)

        assertEquals(1, preview.occurrenceCount)
        assertEquals(1, state.stageProjectSourceReplacement(preview))
        assertEquals(original, diskSource.readText(), "staging must not write the working tree")
        assertEquals(setOf("bank_80.asm"), state.unsavedSourceFileIds)
        assertTrue(state.sourceEditText("bank_80.asm").contains("NOP"))
        assertEquals(emptySet<String>(), state.projectModifiedFileIds)
    }

    @Test
    fun `generated room navigation is read only and preserves source history and buffers`() = runBlocking {
        val reference = fakeReference(emptyList())
        val repository = AsmProjectWorkspaceRepository(emptyList(), starterModules = emptyList())
        val projectFile = File(tempDirectory, "Generated.smedit").apply { writeText("{}") }
        repository.initialize(projectFile.path, reference)
        val artifacts = AsmBuildArtifactRepository(repository)
        artifacts.publishSuccess(
            projectFile.path, ByteArray(0), emptyList(), "fixture",
            "; Generated room\nSmeditRoom_91F8_State0_Plms:\n    dw ${'$'}0000\n",
        )
        artifacts.publishRoomData(projectFile.path,
            "${AsmRoomDataSnapshot.MARKER}\nSmeditRoom_91F8_State0_Plms:\n    dw ${'$'}0000\n")
        val state = AsmWorkspaceState(projectRepository = repository, buildArtifactRepository = artifacts)
        state.bindProject(projectFile.path, enabled = true)
        state.openSource("bank_80.asm", 3)
        val sourceLocation = state.locationSnapshot()
        val original = state.sourceEditText("bank_80.asm")
        state.updateSourceEditText("bank_80.asm", "$original\n; unsaved bank work")
        state.openGeneratedRooms(1)
        assertTrue(state.roomDataExpanded)
        assertTrue(state.expandedRoomDataGroups[roomDataGroupKey(0x91F8, false)] == true)
        state.toggleRoomDataExpanded()
        assertFalse(state.roomDataExpanded, "selection must not force a collapsed tree open on every recomposition")
        assertEquals(AsmBrowserMode.GENERATED_ROOMS, state.browserMode)
        assertFalse(state.isProjectSourceEditable)
        assertEquals(AsmWorkspaceLocation.GeneratedRooms(1), state.locationSnapshot())
        assertTrue(state.workspace?.index?.file(AsmGeneratedRoomSource.FILE_NAME) == null)
        state.updateSourceEditText(AsmGeneratedRoomSource.FILE_NAME, "must not become an editable buffer")
        assertEquals(setOf("bank_80.asm"), state.unsavedSourceFileIds)
        state.goBack()
        assertEquals(sourceLocation, state.locationSnapshot())
        assertTrue(state.isProjectSourceEditable)
        assertTrue(state.sourceEditText("bank_80.asm").endsWith("; unsaved bank work"))
        state.goForward()
        assertEquals(AsmWorkspaceLocation.GeneratedRooms(1), state.locationSnapshot())
        state.openGeneratedRooms(2, buildWrites = true)
        assertTrue(state.showRoomBuildWrites)
        assertEquals(AsmWorkspaceLocation.GeneratedRooms(2, true), state.locationSnapshot())
        state.goBack()
        assertFalse(state.showRoomBuildWrites)
        assertEquals(AsmWorkspaceLocation.GeneratedRooms(1), state.locationSnapshot())
        state.openGeneratedRooms(Int.MAX_VALUE)
        assertEquals(state.generatedRoomSource?.lines?.lastIndex, state.generatedRoomLineIndex)
        state.bindProject(File(tempDirectory, "Other.smedit").path, enabled = false)
        assertTrue(state.generatedRoomSource == null, "snapshots must not leak between projects")
        assertTrue(state.generatedRoomInput == null)
        assertFalse(state.roomDataExpanded)
        assertEquals(AsmBrowserMode.SOURCE, state.browserMode)
    }

    private fun fakeReference(ranges: List<AsmAssetRange>): AsmReferenceWorkspace {
        val root = File(tempDirectory, "reference-${ranges.size}").apply { mkdirs() }
        val source = File(root, "src").apply { mkdirs() }
        File(source, "main.asm").writeText("incsrc bank_80.asm ; Engine\n")
        File(source, "bank_80.asm").writeText(
            """
            ; Engine
            org ${'$'}808000
            Boot:
                RTS
            """.trimIndent(),
        )
        val data = File(root, "data").apply { mkdirs() }
        ranges.forEach { range ->
            File(data, range.path).apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(range.length) { it.toByte() })
            }
        }
        val metadata = AsmReferenceMetadata(
            repositoryUrl = AsmReferenceContract.REPOSITORY_URL,
            commit = AsmReferenceContract.COMMIT,
            installedAt = "2026-10-08T00:00:00Z",
            romName = "fixture.sfc",
            romSha256 = "ab".repeat(32),
            romSize = 0x300000,
            assetCount = ranges.size,
        )
        return AsmReferenceWorkspace(
            root = root,
            metadata = metadata,
            index = AsmSourceParser().parse(source, data, ranges),
        )
    }
}

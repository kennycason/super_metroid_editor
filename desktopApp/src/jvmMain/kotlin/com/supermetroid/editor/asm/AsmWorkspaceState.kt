package com.supermetroid.editor.asm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

internal enum class AsmBrowserMode { SOURCE, ASSETS, LIBRARY, ROM }

internal sealed interface AsmWorkspaceLocation {
    data class Source(
        val fileId: String,
        val lineIndex: Int,
        val referenceSymbol: AsmSymbolId? = null,
        val workspaceKind: AsmWorkspaceKind = AsmWorkspaceKind.REFERENCE,
        val columnIndex: Int = 0,
    ) : AsmWorkspaceLocation
    data class Asset(
        val path: String,
        val workspaceKind: AsmWorkspaceKind = AsmWorkspaceKind.REFERENCE,
    ) : AsmWorkspaceLocation
    data class Library(val pageId: String, val instructionToken: String?) : AsmWorkspaceLocation
    data class Rom(val view: AsmRomPreviewView) : AsmWorkspaceLocation
}

internal class AsmWorkspaceState(
    private val repository: AsmReferenceRepository = AsmReferenceRepository(),
    private val projectRepository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
    private val buildArtifactRepository: AsmBuildArtifactRepository = AsmBuildArtifactRepository(projectRepository),
) {
    var workspace by mutableStateOf<AsmReferenceWorkspace?>(null)
        private set
    var workspaceKind by mutableStateOf(AsmWorkspaceKind.REFERENCE)
        private set
    var projectWorkspace by mutableStateOf<AsmProjectWorkspace?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var currentRomSha256 by mutableStateOf<String?>(null)
        private set
    var browserMode by mutableStateOf(AsmBrowserMode.SOURCE)
        private set
    var query by mutableStateOf("")
    var selectedFileId by mutableStateOf<String?>(null)
        private set
    var expandedSourceFileId by mutableStateOf<String?>(null)
        private set
    var selectedLineIndex by mutableStateOf(0)
        private set
    var selectedColumnIndex by mutableStateOf(0)
        private set
    var activeReferenceSymbol by mutableStateOf<AsmSymbolId?>(null)
        private set
    var selectedAssetPath by mutableStateOf<String?>(null)
        private set
    var selectedAssetCategory by mutableStateOf<String?>(null)
    var selectedInstruction by mutableStateOf<String?>(null)
        private set
    var selectedInstructionToken by mutableStateOf<String?>(null)
        private set
    var selectedLibraryPageId by mutableStateOf(AsmLibrary.guides.first().id)
        private set
    var navigationSerial by mutableStateOf(0L)
        private set
    var searchFocusSerial by mutableStateOf(0L)
        private set
    var romPreview by mutableStateOf<AsmRomPreview?>(null)
        private set
    var romPreviewView by mutableStateOf(AsmRomPreviewView.DIFF)
        private set
    var selectedRomDiffIndex by mutableStateOf(0)
        private set
    var previewBusy by mutableStateOf(false)
        private set
    var editSessionSerial by mutableStateOf(0L)
        private set
    var sourceBufferRevision by mutableStateOf(0L)
        private set
    var buildReport by mutableStateOf<AsmBuildReport?>(null)
        private set

    private val backStack = mutableListOf<AsmWorkspaceLocation>()
    private val forwardStack = mutableListOf<AsmWorkspaceLocation>()
    private var pendingSnesAddress: Int? = null
    private var referenceWorkspace: AsmReferenceWorkspace? = null
    private var boundProjectFilePath: String = ""
    private var boundProjectEnabled: Boolean = false
    private val sourceBuffers = mutableStateMapOf<String, String>()
    private val savedSourceTexts = mutableMapOf<String, String>()
    private val dirtySourceFileIds = mutableStateMapOf<String, Unit>()

    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()
    val assetsMatchCurrentRom: Boolean
        get() = currentRomSha256 != null && currentRomSha256 == workspace?.metadata?.romSha256
    val referenceMetadata: AsmReferenceMetadata? get() = referenceWorkspace?.metadata
    val referenceAssetsMatchCurrentRom: Boolean
        get() = currentRomSha256 != null && currentRomSha256 == referenceWorkspace?.metadata?.romSha256
    val hasReferenceWorkspace: Boolean get() = referenceWorkspace != null
    val hasProjectWorkspace: Boolean get() = projectWorkspace != null
    val projectModifiedFileIds: Set<String> get() = projectWorkspace?.modifiedFileIds.orEmpty()
    val projectModuleFileIds: List<String> get() = projectWorkspace?.projectModuleFileIds.orEmpty()
    val disabledProjectModuleFileIds: Set<String>
        get() = projectWorkspace?.disabledProjectModuleFileIds.orEmpty()
    val unsavedSourceFileIds: Set<String>
        get() = dirtySourceFileIds.keys.toSet()
    private val activeUnsavedSourceFileIds: Set<String>
        get() = dirtySourceFileIds.keys.filterNotTo(linkedSetOf()) {
            it in disabledProjectModuleFileIds
        }
    val hasProjectSourceChanges: Boolean
        get() = projectModifiedFileIds.isNotEmpty() || activeUnsavedSourceFileIds.isNotEmpty()
    val isProjectSourceEditable: Boolean
        get() = workspaceKind == AsmWorkspaceKind.PROJECT &&
            browserMode == AsmBrowserMode.SOURCE &&
            selectedFileId != null
    val sourceBuildRequired: Boolean
        get() = workspaceKind == AsmWorkspaceKind.PROJECT &&
            (activeUnsavedSourceFileIds.isNotEmpty() || buildReport?.succeeded != true)

    fun isProjectModule(fileId: String?): Boolean = fileId != null && fileId in projectModuleFileIds

    fun isProjectModuleEnabled(fileId: String?): Boolean =
        isProjectModule(fileId) && fileId !in disabledProjectModuleFileIds

    suspend fun loadInstalled() {
        if (busy) return
        busy = true
        progress = "Opening ASM reference…"
        error = null
        try {
            referenceWorkspace = withContext(Dispatchers.IO) { repository.loadInstalled() }
            workspace = referenceWorkspace
            workspaceKind = AsmWorkspaceKind.REFERENCE
            if (boundProjectEnabled) loadBoundProjectWorkspace()
            ensureSelection()
        } catch (problem: Exception) {
            error = problem.message ?: "Could not open the ASM reference"
        } finally {
            busy = false
            progress = null
        }
    }

    suspend fun observeRom(romBytes: ByteArray?) {
        val observedHash = if (romBytes == null) null else withContext(Dispatchers.Default) {
            repository.romSha256(romBytes)
        }
        if (currentRomSha256 != observedHash) clearRomPreview()
        currentRomSha256 = observedHash
    }

    suspend fun download(romBytes: ByteArray, romName: String) {
        runReferenceOperation { update -> repository.installOrRefresh(romBytes, romName, update) }
    }

    suspend fun refreshAssets(romBytes: ByteArray, romName: String) {
        runReferenceOperation { update -> repository.refreshAssets(romBytes, romName, update) }
    }

    suspend fun bindProject(projectFilePath: String, enabled: Boolean) {
        val changedProject = boundProjectFilePath != projectFilePath
        boundProjectFilePath = projectFilePath
        boundProjectEnabled = enabled
        if (changedProject) clearEditSessions()
        if (!enabled || projectFilePath.isBlank()) {
            projectWorkspace = null
            buildReport = null
            if (workspaceKind == AsmWorkspaceKind.PROJECT) showReferenceWorkspace()
            return
        }
        loadBoundProjectWorkspace()
        refreshBuildReport()
    }

    suspend fun refreshBuildReport() {
        val projectFilePath = boundProjectFilePath
        buildReport = if (!boundProjectEnabled || projectFilePath.isBlank()) null else {
            withContext(Dispatchers.IO) {
                val sourceRoot = projectWorkspace?.workingDirectory?.let { java.io.File(it, "src") }
                val validation = sourceRoot?.let {
                    AsmSourceLinter.lintTree(it, disabledProjectModuleFileIds)
                }.orEmpty()
                AsmSourceLinter.report(validation) ?: buildArtifactRepository.load(projectFilePath)
            }
        }
    }

    suspend fun enableProjectWorkspace(projectFilePath: String): Boolean {
        val reference = referenceWorkspace ?: run {
            error = "Download the ASM reference before enabling project ASM"
            return false
        }
        if (busy) return false
        busy = true
        error = null
        return try {
            val result = withContext(Dispatchers.IO) {
                projectRepository.initialize(projectFilePath, reference) { message -> progress = message }
            }
            boundProjectFilePath = projectFilePath
            boundProjectEnabled = true
            projectWorkspace = result
            activateProjectWorkspace(result)
            buildReport = null
            true
        } catch (problem: Exception) {
            error = problem.message ?: "Could not create the project ASM workspace"
            false
        } finally {
            busy = false
            progress = null
        }
    }

    fun showReferenceWorkspace() {
        val reference = referenceWorkspace ?: return
        workspace = reference
        workspaceKind = AsmWorkspaceKind.REFERENCE
        ensureSelection()
        navigationSerial++
    }

    fun showProjectWorkspace() {
        projectWorkspace?.let(::activateProjectWorkspace)
    }

    fun dismissError() {
        error = null
    }

    private fun clearRomPreview() {
        romPreview = null
        selectedRomDiffIndex = 0
        romPreviewView = AsmRomPreviewView.DIFF
    }

    fun requestSearchFocus() {
        searchFocusSerial++
    }

    fun showSourceBrowser() {
        browserMode = AsmBrowserMode.SOURCE
        query = ""
        selectedAssetPath = null
        navigationSerial++
    }

    fun showAssetBrowser() {
        browserMode = AsmBrowserMode.ASSETS
        query = ""
        if (selectedAssetPath == null) {
            selectedAssetPath = workspace?.index?.assets?.firstOrNull()?.range?.path
        }
        navigationSerial++
    }

    fun showLibraryBrowser() {
        browserMode = AsmBrowserMode.LIBRARY
        query = ""
        selectedAssetPath = null
        navigationSerial++
    }

    fun showRomBrowser() {
        browserMode = AsmBrowserMode.ROM
        query = ""
        selectedAssetPath = null
        navigationSerial++
    }

    fun selectRomPreviewView(view: AsmRomPreviewView, addToHistory: Boolean = true) {
        if (addToHistory) rememberCurrentLocation()
        browserMode = AsmBrowserMode.ROM
        romPreviewView = view
        query = ""
        navigationSerial++
        if (addToHistory) forwardStack.clear()
    }

    fun selectRomDiff(index: Int) {
        val ranges = romPreview?.diffRanges.orEmpty()
        if (ranges.isEmpty()) return
        selectedRomDiffIndex = index.coerceIn(ranges.indices)
        romPreviewView = AsmRomPreviewView.DIFF
        navigationSerial++
    }

    suspend fun buildRomPreview(builder: () -> AsmRomPreview?) {
        if (previewBusy) return
        previewBusy = true
        error = null
        try {
            val preview = withContext(Dispatchers.Default) { builder() }
            refreshBuildReport()
            if (preview == null) {
                error = "SMEDIT Result could not be built. No ROM was written; check the export status or log for the blocker."
            } else {
                romPreview = preview
                selectedRomDiffIndex = 0
                romPreviewView = AsmRomPreviewView.DIFF
                browserMode = AsmBrowserMode.ROM
                navigationSerial++
            }
        } catch (problem: Exception) {
            error = problem.message ?: "SMEDIT Result could not be built"
        } finally {
            previewBusy = false
        }
    }

    /** Runs the same non-writing validated build as ROM comparison while
     * keeping the user in Source. */
    suspend fun validateProjectBuild(builder: () -> AsmRomPreview?): Boolean {
        if (previewBusy) return false
        previewBusy = true
        error = null
        return try {
            val result = withContext(Dispatchers.Default) { builder() }
            refreshBuildReport()
            if (result == null) {
                retainOrCreateBuildFailure("ROM build did not complete. No ROM was written.")
                false
            } else {
                true
            }
        } catch (problem: Exception) {
            refreshBuildReport()
            retainOrCreateBuildFailure(problem.message ?: "ROM build did not complete")
            false
        } finally {
            previewBusy = false
        }
    }

    /** Compilation/export failures belong to the source diagnostics drawer,
     * not the workspace setup controls in the sidebar. A source/Asar report is
     * more specific, so retain it when one already exists. */
    private fun retainOrCreateBuildFailure(message: String) {
        val existing = buildReport
        if (existing != null && !existing.succeeded) return
        val diagnostic = AsmBuildDiagnostic(
            severity = AsmDiagnosticSeverity.ERROR,
            message = message,
        )
        buildReport = AsmBuildReport(
            succeeded = false,
            generatedAt = Instant.now().toString(),
            assemblerVersion = existing?.assemblerVersion.orEmpty(),
            output = listOf(message),
            diagnostics = listOf(diagnostic),
            symbols = existing?.symbols ?: AsmCompiledSymbols.EMPTY,
        )
    }

    fun openSource(
        fileId: String,
        lineIndex: Int = 0,
        addToHistory: Boolean = true,
        preserveReferences: Boolean = false,
        columnIndex: Int = 0,
    ) {
        val source = workspace?.index?.file(fileId) ?: return
        if (addToHistory) rememberCurrentLocation()
        if (!preserveReferences) activeReferenceSymbol = null
        browserMode = AsmBrowserMode.SOURCE
        selectedFileId = source.id
        expandedSourceFileId = source.id
        val currentLines = if (workspaceKind == AsmWorkspaceKind.PROJECT) {
            sourceEditText(source.id).lineSequence().toList()
        } else source.lines
        selectedLineIndex = lineIndex.coerceIn(0, (currentLines.size - 1).coerceAtLeast(0))
        val selectedLine = currentLines.getOrNull(selectedLineIndex).orEmpty()
        selectedColumnIndex = columnIndex.coerceIn(0, selectedLine.length)
        selectedAssetPath = null
        selectedInstruction = null
        selectedInstructionToken = null
        navigationSerial++
        if (addToHistory) forwardStack.clear()
    }

    fun sourceEditText(fileId: String): String = sourceBuffers[fileId]
        ?: workspace?.index?.file(fileId)?.file?.readText().orEmpty()

    fun updateSourceEditText(fileId: String, text: String) {
        if (workspaceKind != AsmWorkspaceKind.PROJECT || selectedFileId != fileId) return
        if (sourceBuffers[fileId] == null) {
            val diskText = workspace?.index?.file(fileId)?.file?.readText() ?: return
            savedSourceTexts[fileId] = diskText
        }
        // A successful report still describes the saved source while this edit
        // is only in memory; sourceBuildRequired adds the star via dirty buffers.
        // Failed diagnostics, however, may point at text the user just fixed.
        if (
            sourceBuffers[fileId] != text &&
            fileId !in disabledProjectModuleFileIds &&
            buildReport?.succeeded != true
        ) {
            buildReport = null
        }
        sourceBuffers[fileId] = text
        val saved = savedSourceTexts[fileId]
        if (saved != null && text != saved) dirtySourceFileIds[fileId] = Unit
        else dirtySourceFileIds.remove(fileId)
        sourceBufferRevision++
    }

    fun sourceTexts(): List<AsmSourceText> = workspace?.index?.files.orEmpty().map { source ->
        AsmSourceText(source.id, source.displayName, sourceEditText(source.id))
    }

    fun previewProjectSourceReplacement(
        query: String,
        replacement: String,
        caseSensitive: Boolean,
        limit: Int = Int.MAX_VALUE,
    ): AsmSourceReplacementPreview = previewAsmSourceReplacement(
        sources = sourceTexts(),
        query = query,
        replacement = replacement,
        caseSensitive = caseSensitive,
        limit = limit,
    )

    /** Applies a reviewed multi-file replacement to in-memory edit buffers.
     * Saving/building remains explicit, so this operation is reversible per
     * file and can never partially write a project source tree. */
    fun stageProjectSourceReplacement(preview: AsmSourceReplacementPreview): Int {
        if (workspaceKind != AsmWorkspaceKind.PROJECT || preview.query.isEmpty()) return 0
        val matchingFileIds = preview.matches.mapTo(linkedSetOf(), AsmSourceTextMatch::fileId)
        var replacementCount = 0
        var affectsBuild = false
        matchingFileIds.forEach { fileId ->
            val source = workspace?.index?.file(fileId) ?: return@forEach
            val current = sourceEditText(fileId)
            val (updated, count) = replaceAsmSourceText(
                text = current,
                query = preview.query,
                replacement = preview.replacement,
                caseSensitive = preview.caseSensitive,
            )
            if (count > 0) {
                savedSourceTexts.putIfAbsent(fileId, source.file.readText())
                sourceBuffers[fileId] = updated
                if (updated != savedSourceTexts[fileId]) dirtySourceFileIds[fileId] = Unit
                else dirtySourceFileIds.remove(fileId)
                replacementCount += count
                if (fileId !in disabledProjectModuleFileIds) affectsBuild = true
            }
        }
        if (replacementCount > 0) {
            if (affectsBuild && buildReport?.succeeded != true) buildReport = null
            sourceBufferRevision++
            editSessionSerial++
        }
        return replacementCount
    }

    fun hasUnsavedSourceChanges(fileId: String): Boolean = fileId in dirtySourceFileIds

    fun discardSourceBuffer(fileId: String? = selectedFileId) {
        val selectedId = fileId ?: return
        val source = workspace?.index?.file(selectedId) ?: return
        val diskText = source.file.readText()
        sourceBuffers[selectedId] = diskText
        savedSourceTexts[selectedId] = diskText
        dirtySourceFileIds.remove(selectedId)
        editSessionSerial++
        sourceBufferRevision++
    }

    suspend fun saveSource(fileId: String? = selectedFileId): Boolean {
        if (workspaceKind != AsmWorkspaceKind.PROJECT || boundProjectFilePath.isBlank()) return false
        val selectedId = fileId ?: return false
        val text = sourceBuffers[selectedId] ?: return true
        if (busy) return false
        val affectsBuild = !isProjectModule(selectedId) || isProjectModuleEnabled(selectedId)
        val previousBuildReport = buildReport
        busy = true
        error = null
        return try {
            progress = "Saving $selectedId…"
            val result = withContext(Dispatchers.IO) {
                projectRepository.saveSource(boundProjectFilePath, selectedId, text)
            }
            projectWorkspace = result
            workspace = result.referenceWorkspace
            buildReport = if (affectsBuild) {
                withContext(Dispatchers.IO) {
                    AsmSourceLinter.report(
                        AsmSourceLinter.lintTree(
                            java.io.File(result.workingDirectory, "src"),
                            result.disabledProjectModuleFileIds,
                        ),
                    )
                }
            } else {
                previousBuildReport
            }
            savedSourceTexts[selectedId] = text
            dirtySourceFileIds.remove(selectedId)
            sourceBufferRevision++
            selectedLineIndex = selectedLineIndex.coerceIn(
                0,
                (workspace?.index?.file(selectedId)?.lines?.lastIndex ?: 0).coerceAtLeast(0),
            )
            selectedColumnIndex = selectedColumnIndex.coerceIn(
                0,
                workspace?.index?.file(selectedId)?.lines?.getOrNull(selectedLineIndex)?.length ?: 0,
            )
            activeReferenceSymbol = null
            navigationSerial++
            true
        } catch (problem: Exception) {
            error = problem.message ?: "Could not save $selectedId"
            false
        } finally {
            busy = false
            progress = null
        }
    }

    suspend fun saveAllSources(): Boolean {
        val pending = unsavedSourceFileIds.toList()
        for (fileId in pending) {
            if (!saveSource(fileId)) return false
        }
        return true
    }

    suspend fun restoreOriginalSource(fileId: String? = selectedFileId): Boolean {
        if (workspaceKind != AsmWorkspaceKind.PROJECT || boundProjectFilePath.isBlank()) return false
        val selectedId = fileId ?: return false
        if (isProjectModule(selectedId)) {
            error = "Project modules have no immutable original; delete the module instead"
            return false
        }
        if (busy) return false
        busy = true
        error = null
        return try {
            progress = "Restoring $selectedId…"
            val result = withContext(Dispatchers.IO) {
                projectRepository.restoreSource(boundProjectFilePath, selectedId)
            }
            projectWorkspace = result
            workspace = result.referenceWorkspace
            buildReport = null
            val restored = workspace?.index?.file(selectedId)?.file?.readText().orEmpty()
            sourceBuffers[selectedId] = restored
            savedSourceTexts[selectedId] = restored
            dirtySourceFileIds.remove(selectedId)
            activeReferenceSymbol = null
            editSessionSerial++
            sourceBufferRevision++
            navigationSerial++
            true
        } catch (problem: Exception) {
            error = problem.message ?: "Could not restore $selectedId"
            false
        } finally {
            busy = false
            progress = null
        }
    }

    suspend fun createProjectModule(name: String): Boolean {
        if (workspaceKind != AsmWorkspaceKind.PROJECT || boundProjectFilePath.isBlank() || busy) return false
        busy = true
        error = null
        return try {
            progress = "Creating project module…"
            val (result, fileId) = withContext(Dispatchers.IO) {
                projectRepository.createModule(boundProjectFilePath, name)
            }
            adoptProjectWorkspace(result)
            buildReport = null
            openSource(fileId, addToHistory = false)
            true
        } catch (problem: Exception) {
            error = problem.message ?: "Could not create the project module"
            false
        } finally {
            busy = false
            progress = null
        }
    }

    suspend fun renameProjectModule(fileId: String, name: String): Boolean {
        if (!isProjectModule(fileId) || boundProjectFilePath.isBlank() || busy) return false
        if (hasUnsavedSourceChanges(fileId)) {
            error = "Save or revert this module before renaming it"
            return false
        }
        busy = true
        error = null
        return try {
            progress = "Renaming project module…"
            val (result, newFileId) = withContext(Dispatchers.IO) {
                projectRepository.renameModule(boundProjectFilePath, fileId, name)
            }
            val buffered = sourceBuffers.remove(fileId)
            val saved = savedSourceTexts.remove(fileId)
            dirtySourceFileIds.remove(fileId)
            if (buffered != null) sourceBuffers[newFileId] = buffered
            if (saved != null) savedSourceTexts[newFileId] = saved
            adoptProjectWorkspace(result)
            buildReport = null
            openSource(newFileId, addToHistory = false)
            true
        } catch (problem: Exception) {
            error = problem.message ?: "Could not rename the project module"
            false
        } finally {
            busy = false
            progress = null
        }
    }

    suspend fun deleteProjectModule(fileId: String): Boolean {
        if (!isProjectModule(fileId) || boundProjectFilePath.isBlank() || busy) return false
        busy = true
        error = null
        return try {
            progress = "Deleting project module…"
            val oldOrder = projectModuleFileIds
            val oldIndex = oldOrder.indexOf(fileId).coerceAtLeast(0)
            val result = withContext(Dispatchers.IO) {
                projectRepository.deleteModule(boundProjectFilePath, fileId)
            }
            sourceBuffers.remove(fileId)
            savedSourceTexts.remove(fileId)
            dirtySourceFileIds.remove(fileId)
            adoptProjectWorkspace(result)
            buildReport = null
            val next = result.projectModuleFileIds.getOrNull(oldIndex.coerceAtMost(result.projectModuleFileIds.lastIndex))
                ?: result.referenceWorkspace.index.files.firstOrNull(AsmSourceFile::isBank)?.id
            next?.let { openSource(it, addToHistory = false) }
            true
        } catch (problem: Exception) {
            error = problem.message ?: "Could not delete the project module"
            false
        } finally {
            busy = false
            progress = null
        }
    }

    suspend fun moveProjectModule(fileId: String, offset: Int): Boolean {
        if (!isProjectModule(fileId) || boundProjectFilePath.isBlank() || busy) return false
        if (hasUnsavedSourceChanges(fileId)) {
            error = "Save or revert this module before changing its build order"
            return false
        }
        busy = true
        error = null
        return try {
            val result = withContext(Dispatchers.IO) {
                projectRepository.moveModule(boundProjectFilePath, fileId, offset)
            }
            adoptProjectWorkspace(result)
            buildReport = null
            true
        } catch (problem: Exception) {
            error = problem.message ?: "Could not reorder the project module"
            false
        } finally {
            busy = false
            progress = null
        }
    }

    suspend fun setProjectModuleEnabled(fileId: String, enabled: Boolean): Boolean {
        if (!isProjectModule(fileId) || boundProjectFilePath.isBlank() || busy) return false
        if (hasUnsavedSourceChanges(fileId)) {
            error = "Save or revert this module before ${if (enabled) "enabling" else "disabling"} it"
            return false
        }
        if (isProjectModuleEnabled(fileId) == enabled) return true
        busy = true
        error = null
        return try {
            progress = if (enabled) "Enabling project module…" else "Disabling project module…"
            val result = withContext(Dispatchers.IO) {
                projectRepository.setModuleEnabled(boundProjectFilePath, fileId, enabled)
            }
            adoptProjectWorkspace(result)
            buildReport = null
            true
        } catch (problem: Exception) {
            error = problem.message ?: "Could not ${if (enabled) "enable" else "disable"} the project module"
            false
        } finally {
            busy = false
            progress = null
        }
    }

    fun toggleSourceChapter(fileId: String) {
        val source = workspace?.index?.file(fileId)?.takeIf(AsmSourceFile::isBank) ?: return
        if (selectedFileId != source.id || browserMode != AsmBrowserMode.SOURCE) {
            openSource(source.id)
            return
        }
        expandedSourceFileId = if (expandedSourceFileId == source.id) null else source.id
    }

    fun openAsset(path: String, addToHistory: Boolean = true) {
        val asset = workspace?.index?.asset(path) ?: return
        if (addToHistory) rememberCurrentLocation()
        browserMode = AsmBrowserMode.ASSETS
        selectedAssetPath = asset.range.path
        selectedAssetCategory = asset.category
        selectedInstruction = null
        selectedInstructionToken = null
        navigationSerial++
        if (addToHistory) forwardStack.clear()
    }

    fun openAssetReference(reference: String) {
        workspace?.index?.resolveAsset(reference)?.let { openAsset(it.range.path) }
    }

    fun openAddress(snesAddress: Int) {
        val pcOffset = snesLoRomToPc(snesAddress)
        if (pcOffset == null) {
            error = "${formatSnesAddress(snesAddress)} is not a mapped LoROM address."
            return
        }
        val index = workspace?.index
        if (index == null) {
            pendingSnesAddress = snesAddress
            return
        }
        pendingSnesAddress = null
        val resolution = index.addressAtlas.resolve(AsmAddressQuery(snesAddress, AsmAddressSpace.SNES))
        val exactSource = resolution.exactSourceAnchors.firstOrNull()
        when {
            exactSource != null -> openSource(exactSource.fileId, exactSource.lineIndex)
            resolution.containingAsset != null -> openAsset(resolution.containingAsset.range.path)
            resolution.nearestSourceAnchor != null -> {
                val nearest = resolution.nearestSourceAnchor
                openSource(nearest.fileId, nearest.lineIndex)
            }
            else -> error = "No indexed source or asset owns ${formatSnesAddress(snesAddress)} (${formatPcOffset(pcOffset)})."
        }
    }

    fun openLabel(token: String) {
        val index = workspace?.index ?: return
        val fileId = selectedFileId ?: return
        index.resolveLabel(fileId, selectedLineIndex, token)?.let { label ->
            openSource(label.fileId, label.lineIndex)
            activeReferenceSymbol = label.symbolId
        }
    }

    fun openLabel(fileId: String, lineIndex: Int, token: String) {
        workspace?.index?.resolveLabel(fileId, lineIndex, token)
            ?.let { label ->
                openSource(label.fileId, label.lineIndex)
                activeReferenceSymbol = label.symbolId
            }
    }

    fun showReferences(fileId: String, lineIndex: Int, token: String) {
        val index = workspace?.index ?: return
        val label = index.definition(fileId, lineIndex, token) ?: return
        selectedFileId = fileId
        selectedLineIndex = lineIndex
        selectedColumnIndex = 0
        activeReferenceSymbol = label.symbolId
        navigationSerial++
    }

    fun selectSourceLine(lineIndex: Int) {
        selectSourcePosition(lineIndex, 0)
    }

    fun selectSourcePosition(lineIndex: Int, columnIndex: Int) {
        if (workspace?.index?.file(selectedFileId) == null) return
        // Editable callers already provide a position from TextFieldValue.
        // Re-splitting an 18k-line bank merely to revalidate that caret on
        // every keystroke made ordinary typing visibly lag.
        selectedLineIndex = lineIndex.coerceAtLeast(0)
        selectedColumnIndex = columnIndex.coerceAtLeast(0)
        activeReferenceSymbol = null
    }

    fun openReference(label: AsmLabel, usage: AsmLabelUsage) {
        activeReferenceSymbol = label.symbolId
        openSource(
            fileId = usage.fileId,
            lineIndex = usage.lineIndex,
            preserveReferences = true,
        )
    }

    fun showInstruction(mnemonic: String) {
        val token = mnemonic.uppercase()
        val normalized = token.substringBefore('.')
        if (AsmInstructionReference.find(normalized) == null) return
        rememberCurrentLocation()
        browserMode = AsmBrowserMode.LIBRARY
        query = ""
        selectedInstruction = normalized
        selectedInstructionToken = token
        selectedLibraryPageId = AsmLibrary.instructionPageId(normalized)
        selectedAssetPath = null
        navigationSerial++
        forwardStack.clear()
    }

    fun openLibraryGuide(pageId: String, addToHistory: Boolean = true) {
        val guide = AsmLibrary.guide(pageId) ?: return
        if (addToHistory) rememberCurrentLocation()
        browserMode = AsmBrowserMode.LIBRARY
        query = ""
        selectedInstruction = null
        selectedInstructionToken = null
        selectedLibraryPageId = guide.id
        selectedAssetPath = null
        navigationSerial++
        if (addToHistory) forwardStack.clear()
    }

    fun openLibraryInstruction(mnemonic: String, addToHistory: Boolean = true) {
        val token = mnemonic.uppercase()
        val instruction = AsmInstructionReference.find(token.substringBefore('.')) ?: return
        if (addToHistory) rememberCurrentLocation()
        browserMode = AsmBrowserMode.LIBRARY
        query = ""
        selectedInstruction = instruction.mnemonic
        selectedInstructionToken = token
        selectedLibraryPageId = AsmLibrary.instructionPageId(instruction.mnemonic)
        selectedAssetPath = null
        navigationSerial++
        if (addToHistory) forwardStack.clear()
    }

    fun goBack() {
        val target = backStack.removeLastOrNull() ?: return
        locationSnapshot()?.let(forwardStack::add)
        restoreLocation(target)
    }

    fun goForward() {
        val target = forwardStack.removeLastOrNull() ?: return
        locationSnapshot()?.let(backStack::add)
        restoreLocation(target)
    }

    private suspend fun runReferenceOperation(operation: ((String) -> Unit) -> AsmReferenceWorkspace) {
        if (busy) return
        busy = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                operation { message -> progress = message }
            }
            referenceWorkspace = result
            if (workspaceKind == AsmWorkspaceKind.REFERENCE) workspace = result
            ensureSelection()
        } catch (problem: Exception) {
            error = problem.message ?: "ASM operation failed"
        } finally {
            busy = false
            progress = null
        }
    }

    private suspend fun loadBoundProjectWorkspace() {
        if (boundProjectFilePath.isBlank()) return
        val result = withContext(Dispatchers.IO) { projectRepository.load(boundProjectFilePath) }
        if (result == null) {
            projectWorkspace = null
            if (workspaceKind == AsmWorkspaceKind.PROJECT) showReferenceWorkspace()
            error = "This project has ASM enabled, but its sidecar workspace is missing or incomplete. Recreate it from the pinned reference."
        } else {
            projectWorkspace = result
            activateProjectWorkspace(result)
        }
    }

    private fun activateProjectWorkspace(project: AsmProjectWorkspace) {
        workspace = project.referenceWorkspace
        workspaceKind = AsmWorkspaceKind.PROJECT
        ensureSelection()
        navigationSerial++
    }

    private fun adoptProjectWorkspace(project: AsmProjectWorkspace) {
        projectWorkspace = project
        workspace = project.referenceWorkspace
        workspaceKind = AsmWorkspaceKind.PROJECT
        sourceBufferRevision++
        editSessionSerial++
        navigationSerial++
    }

    private fun clearEditSessions() {
        sourceBuffers.clear()
        savedSourceTexts.clear()
        dirtySourceFileIds.clear()
        editSessionSerial++
        sourceBufferRevision++
    }

    private fun ensureSelection() {
        val index = workspace?.index ?: return
        if (index.file(selectedFileId) == null) {
            selectedFileId = index.files.firstOrNull { it.id == "bank_80.asm" }?.id
                ?: index.files.firstOrNull()?.id
            selectedLineIndex = 0
            selectedColumnIndex = 0
        }
        if (expandedSourceFileId == null) {
            expandedSourceFileId = selectedFileId?.takeIf { index.file(it)?.isBank == true }
        }
        if (selectedAssetPath != null && index.asset(selectedAssetPath) == null) selectedAssetPath = null
        pendingSnesAddress?.let(::openAddress)
    }

    internal fun locationSnapshot(): AsmWorkspaceLocation? = when (browserMode) {
        AsmBrowserMode.SOURCE -> selectedFileId?.let {
            AsmWorkspaceLocation.Source(
                fileId = it,
                lineIndex = selectedLineIndex,
                referenceSymbol = activeReferenceSymbol,
                workspaceKind = workspaceKind,
                columnIndex = selectedColumnIndex,
            )
        }
        AsmBrowserMode.ASSETS -> selectedAssetPath?.let { AsmWorkspaceLocation.Asset(it, workspaceKind) }
        AsmBrowserMode.LIBRARY -> AsmWorkspaceLocation.Library(selectedLibraryPageId, selectedInstructionToken)
        AsmBrowserMode.ROM -> AsmWorkspaceLocation.Rom(romPreviewView)
    }

    private fun rememberCurrentLocation() {
        val current = locationSnapshot() ?: return
        if (backStack.lastOrNull() != current) backStack += current
        if (backStack.size > MAX_HISTORY) backStack.removeAt(0)
    }

    internal fun restoreLocation(location: AsmWorkspaceLocation) {
        when (location) {
            is AsmWorkspaceLocation.Source -> {
                restoreWorkspaceKind(location.workspaceKind)
                activeReferenceSymbol = location.referenceSymbol
                openSource(
                    location.fileId,
                    location.lineIndex,
                    addToHistory = false,
                    preserveReferences = true,
                    columnIndex = location.columnIndex,
                )
            }
            is AsmWorkspaceLocation.Asset -> {
                restoreWorkspaceKind(location.workspaceKind)
                openAsset(location.path, addToHistory = false)
            }
            is AsmWorkspaceLocation.Library -> {
                val mnemonic = AsmLibrary.mnemonicFromPageId(location.pageId)
                if (mnemonic != null) openLibraryInstruction(location.instructionToken ?: mnemonic, addToHistory = false)
                else openLibraryGuide(location.pageId, addToHistory = false)
            }
            is AsmWorkspaceLocation.Rom -> selectRomPreviewView(location.view, addToHistory = false)
        }
    }

    private fun restoreWorkspaceKind(kind: AsmWorkspaceKind) {
        when (kind) {
            AsmWorkspaceKind.REFERENCE -> referenceWorkspace?.let {
                workspace = it
                workspaceKind = AsmWorkspaceKind.REFERENCE
            }
            AsmWorkspaceKind.PROJECT -> projectWorkspace?.let {
                workspace = it.referenceWorkspace
                workspaceKind = AsmWorkspaceKind.PROJECT
            }
        }
        ensureSelection()
    }

    companion object {
        private const val MAX_HISTORY = 100
    }
}

package com.supermetroid.editor.asm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.emulator.CpuDebugState
import com.supermetroid.editor.rom.RomParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

internal enum class AsmBrowserMode { SOURCE, GENERATED_ROOMS, PLM_PLACEMENT, ASSETS, LIBRARY, ROM }

internal data class AsmSourceBankBudget(
    val bank: Int,
    val capacityBytes: Int = 0x8000,
    val allocatableContiguousBytes: Int? = null,
    val spaceIsCurrent: Boolean = false,
)

internal data class AsmRoomSourceInspection(val roomId: Int, val title: String, val text: String, val stateId: String? = null,
                                          val fileId: String = PLM_SOURCE_FILE,
                                          val automaticEdit: AsmRoomSourceAutomaticEdit? = null,
                                          val startEditing: () -> Unit)

/** An editor-only overlay. Save, Build, project Replace and semantic writers still see the real bank;
 * sidebar Find may inspect this visible text without adopting it. */
internal data class AsmRoomSourceAutomaticEdit(
    val text: String,
    val lineIndex: Int,
    val apply: (String) -> Boolean,
    val reopen: () -> Unit,
    val redoHead: AsmSourceTextChange? = null,
)

/** Capture on the caller thread; perform bounded source normalization away from the UI. */
internal interface AsmSourceSaveParticipant {
    val sourceFileIds: Set<String>
    fun prepare(fileId: String, text: String): () -> String
    fun afterSaved(fileId: String, text: String): (() -> Unit)? = null
}

internal sealed interface AsmWorkspaceLocation {
    data class Source(
        val fileId: String,
        val lineIndex: Int,
        val referenceSymbol: AsmSymbolId? = null,
        val referenceMacro: AsmMacroId? = null,
        val workspaceKind: AsmWorkspaceKind = AsmWorkspaceKind.REFERENCE,
        val columnIndex: Int = 0,
    ) : AsmWorkspaceLocation
    data class Asset(
        val path: String,
        val workspaceKind: AsmWorkspaceKind = AsmWorkspaceKind.REFERENCE,
    ) : AsmWorkspaceLocation
    data class Library(
        val pageId: String,
        val instructionToken: String?,
        val directiveToken: String? = null,
    ) : AsmWorkspaceLocation
    data class Rom(val view: AsmRomPreviewView) : AsmWorkspaceLocation
    data class GeneratedRooms(val lineIndex: Int, val buildWrites: Boolean = false) : AsmWorkspaceLocation
    data class PlmPlacement(val snapshot: AsmPlmPlacementSnapshot) : AsmWorkspaceLocation
    data class RoomSourceInspection(val snapshot: AsmRoomSourceInspection) : AsmWorkspaceLocation
}

internal sealed interface AsmReferenceExpansionTarget {
    data class Label(val id: AsmSymbolId) : AsmReferenceExpansionTarget
    data class Macro(val id: AsmMacroId) : AsmReferenceExpansionTarget
}

/** A source editor view owns navigation/caret state, while the underlying text
 * remains shared by file. Keeping those responsibilities separate lets the
 * same bank appear in both columns without creating competing edit buffers. */
internal data class AsmSourcePaneState(
    val id: Long,
    val fileId: String? = null,
    val lineIndex: Int = 0,
    val columnIndex: Int = 0,
    val navigationSerial: Long = 0L,
    val referenceSymbol: AsmSymbolId? = null,
    val referenceMacro: AsmMacroId? = null,
)

/** Identity shared by every document in a compound edit. Notifications run only after commit. */
internal class AsmSourceEditGroup(
    val fileIds: Set<String>,
    val beforeUndo: (() -> Unit)?,
    val beforeRedo: (() -> Unit)?,
    val afterUndo: (() -> Unit)?,
    val afterRedo: (() -> Unit)?,
)

internal data class AsmSourceTextChange(
    val offset: Int,
    val removed: String,
    val inserted: String,
    /** Ownership transitions and compound layout/source edits undo atomically with their text. */
    val beforeUndo: (() -> Unit)? = null,
    val beforeRedo: (() -> Unit)? = null,
    val group: AsmSourceEditGroup? = null,
) {
    fun undo(text: String): String? = replaceExpected(text, inserted, removed)
    fun redo(text: String): String? = replaceExpected(text, removed, inserted)

    private fun replaceExpected(text: String, expected: String, replacement: String): String? {
        val end = offset + expected.length
        if (offset !in 0..text.length || end > text.length || text.substring(offset, end) != expected) return null
        return text.replaceRange(offset, end, replacement)
    }

    companion object {
        fun between(before: String, after: String): AsmSourceTextChange? {
            if (before == after) return null
            var prefix = 0
            val sharedLength = minOf(before.length, after.length)
            while (prefix < sharedLength && before[prefix] == after[prefix]) prefix++
            var suffix = 0
            val remainingBefore = before.length - prefix
            val remainingAfter = after.length - prefix
            while (
                suffix < remainingBefore && suffix < remainingAfter &&
                before[before.lastIndex - suffix] == after[after.lastIndex - suffix]
            ) suffix++
            return AsmSourceTextChange(
                offset = prefix,
                removed = before.substring(prefix, before.length - suffix),
                inserted = after.substring(prefix, after.length - suffix),
            )
        }
    }
}

/** Immutable review snapshot of the three source layers visible to a project.
 * Reading it must not populate editor buffers or change source ownership. */
internal data class AsmProjectSourceChange(
    val fileId: String,
    val baselineText: String,
    val savedText: String,
    val workingText: String,
    val projectModule: Boolean,
    val enabled: Boolean,
    val semantic: AsmSemanticChangeSummary = AsmSemanticChanges.source(
        fileId, baselineText, workingText, projectModule,
    ),
    val restoreRequest: AsmProjectRestoreRequest? = null,
    val restoreBlockedReason: String? = null,
    val allocations: List<AsmProjectAllocationSummary> = emptyList(),
) {
    val savedBankOverride: Boolean get() = !projectModule && baselineText != savedText
    val unsavedBuffer: Boolean get() = savedText != workingText
}

internal data class AsmProjectAssetChange(
    val assetPath: String,
    val size: Int,
    val changedBytes: Int,
    val firstChangedOffsets: List<Int>,
    val managed: Boolean = false,
    val originalAssetPath: String? = null,
    val originalCapacity: Int? = null,
    val sourceLabel: String? = null,
    val compiledAddress: Int? = null,
    val inlineSource: Boolean = false,
    val semantic: AsmSemanticChangeSummary = AsmSemanticChanges.asset(assetPath),
    val restoreRequest: AsmProjectRestoreRequest? = null,
    val restoreBlockedReason: String? = null,
    val allocations: List<AsmProjectAllocationSummary> = emptyList(),
)

internal class AsmWorkspaceState(
    private val repository: AsmReferenceRepository = AsmReferenceRepository(),
    private val projectRepository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
    private val buildArtifactRepository: AsmBuildArtifactRepository = AsmBuildArtifactRepository(projectRepository),
    private val sourceIndexLoader: suspend (String) -> AsmProjectWorkspace? = { projectRepository.load(it) },
    private val installedReferenceLoader: suspend () -> AsmReferenceWorkspace? = { repository.loadInstalled() },
    private val baselineUpdater: AsmBaselineUpdateRepository = AsmBaselineUpdateRepository(projectRepository),
    private val backupRepository: AsmBaselineBackupRepository = AsmBaselineBackupRepository(projectRepository),
    private val backupListLoader: suspend (String) -> AsmBaselineBackupList = { backupRepository.list(it) },
    private val backupFolderOpener: (java.io.File) -> Unit = { folder ->
        check(java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
            "This desktop cannot open folders. Backup folder: ${folder.path}"
        }
        java.awt.Desktop.getDesktop().open(folder)
    },
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
    var baselineUpdatePlan by mutableStateOf<AsmBaselineUpdatePlan?>(null)
        private set
    var baselineUpdateMessage by mutableStateOf<String?>(null)
        private set
    var baselineBackupsVisible by mutableStateOf(false)
        private set
    var baselineBackups by mutableStateOf<AsmBaselineBackupList?>(null)
        private set
    var baselineBackupsBusy by mutableStateOf(false)
        private set
    var baselineBackupsDeleting by mutableStateOf(false)
        private set
    var baselineBackupsError by mutableStateOf<String?>(null)
        private set
    var baselineBackupsMessage by mutableStateOf<String?>(null)
        private set
    private var backupRequestGeneration = 0L
    private var baselinePreviewBufferRevision = -1L
    var currentRomSha256 by mutableStateOf<String?>(null)
        private set
    var browserMode by mutableStateOf(AsmBrowserMode.SOURCE)
        private set
    var sourceGutterMode by mutableStateOf(AsmSourceGutterMode.LINES)
    var plmPlacementSnapshot by mutableStateOf<AsmPlmPlacementSnapshot?>(null)
        private set
    /** One sidebar search shared across browser modes, independent of navigation history. */
    var query by mutableStateOf("")
    private var selectedFileIdValue by mutableStateOf<String?>(null)
    var selectedFileId: String?
        get() = selectedFileIdValue
        private set(value) {
            selectedFileIdValue = value
            updateActiveSourcePane { it.copy(fileId = value) }
        }
    var expandedSourceFileId by mutableStateOf<String?>(null)
        private set
    private var selectedLineIndexValue by mutableStateOf(0)
    var selectedLineIndex: Int
        get() = selectedLineIndexValue
        private set(value) {
            selectedLineIndexValue = value
            updateActiveSourcePane { it.copy(lineIndex = value) }
        }
    private var selectedColumnIndexValue by mutableStateOf(0)
    var selectedColumnIndex: Int
        get() = selectedColumnIndexValue
        private set(value) {
            selectedColumnIndexValue = value
            updateActiveSourcePane { it.copy(columnIndex = value) }
        }
    private var activeReferenceSymbolValue by mutableStateOf<AsmSymbolId?>(null)
    var activeReferenceSymbol: AsmSymbolId?
        get() = activeReferenceSymbolValue
        private set(value) {
            activeReferenceSymbolValue = value
            updateActiveSourcePane { it.copy(referenceSymbol = value) }
        }
    private var activeReferenceMacroValue by mutableStateOf<AsmMacroId?>(null)
    var activeReferenceMacro: AsmMacroId?
        get() = activeReferenceMacroValue
        private set(value) {
            activeReferenceMacroValue = value
            updateActiveSourcePane { it.copy(referenceMacro = value) }
        }
    var referenceExpansionTarget by mutableStateOf<AsmReferenceExpansionTarget?>(null)
        private set
    var referenceExpansionSerial by mutableStateOf(0L)
        private set
    var selectedAssetPath by mutableStateOf<String?>(null)
        private set
    var selectedAssetCategory by mutableStateOf<String?>(null)
    var selectedInstruction by mutableStateOf<String?>(null)
        private set
    var selectedInstructionToken by mutableStateOf<String?>(null)
        private set
    var selectedLibraryDirective by mutableStateOf<String?>(null)
        private set
    var selectedLibraryPageId by mutableStateOf(AsmLibrary.guides.first().id)
        private set
    private var navigationSerialValue by mutableStateOf(0L)
    var navigationSerial: Long
        get() = navigationSerialValue
        private set(value) {
            navigationSerialValue = value
            updateActiveSourcePane { it.copy(navigationSerial = value) }
        }
    var searchFocusSerial by mutableStateOf(0L)
        private set
    var romPreview by mutableStateOf<AsmRomPreview?>(null)
        private set
    var romSpacePlan by mutableStateOf<AsmRomSpacePlan?>(null)
        private set
    var requestedRomSpaceBank by mutableStateOf<Int?>(null)
        private set
    var romPreviewView by mutableStateOf(AsmRomPreviewView.DIFF)
        private set
    var selectedRomDiffIndex by mutableStateOf(0)
        private set
    private var currentProjectRevision by mutableStateOf(0L)
    val compiledOutputFreshnessRevision: Long get() = currentProjectRevision
    private var romPreviewProjectRevision: Long? = null
    private var romPreviewBuildGeneratedAt: String? = null
    private var romPreviewSymbols: AsmCompiledSymbols? = null
    var previewBusy by mutableStateOf(false)
        private set
    var editSessionSerial by mutableStateOf(0L)
        private set
    var sourceBufferRevision by mutableStateOf(0L)
        private set
    var buildReport by mutableStateOf<AsmBuildReport?>(null)
        private set
    var projectChangesRequestSerial by mutableStateOf(0L)
        private set
    var roomDataSnapshot by mutableStateOf<AsmGeneratedRoomSource?>(null)
        private set
    var generatedRoomInput by mutableStateOf<AsmGeneratedRoomSource?>(null)
        private set
    var showRoomBuildWrites by mutableStateOf(false)
        private set
    val generatedRoomSource: AsmGeneratedRoomSource?
        get() = if (showRoomBuildWrites) generatedRoomInput else roomDataSnapshot
    var roomDataExpanded by mutableStateOf(false)
        private set
    val expandedRoomDataGroups = mutableStateMapOf<String, Boolean>()

    fun toggleRoomDataExpanded() { roomDataExpanded = !roomDataExpanded }
    fun toggleRoomDataGroup(key: String) { expandedRoomDataGroups[key] = expandedRoomDataGroups[key] != true }
    var generatedRoomLineIndex by mutableStateOf(0)
        private set
    var generatedRoomNavigationSerial by mutableStateOf(0L)
        private set
    var liveDiagnostics by mutableStateOf<List<AsmBuildDiagnostic>>(emptyList())
        private set
    private var liveDiagnosticFileId: String? = null
    val displayedDiagnostics: List<AsmBuildDiagnostic>
        get() {
            val liveFile = liveDiagnosticFileId
            val liveTextSupersedesBuiltText = liveFile != null && liveFile in dirtySourceFileIds
            val built = buildReport?.diagnostics.orEmpty().filterNot { diagnostic ->
                liveTextSupersedesBuiltText && diagnostic.fileId == liveFile
            }
            return (built + liveDiagnostics).distinct()
        }

    var sourcePanes by mutableStateOf(listOf(AsmSourcePaneState(PRIMARY_SOURCE_PANE_ID)))
        private set
    var activeSourcePaneId by mutableStateOf(PRIMARY_SOURCE_PANE_ID)
        private set
    private val backStacks = mutableMapOf<Long, MutableList<AsmWorkspaceLocation>>()
    private val forwardStacks = mutableMapOf<Long, MutableList<AsmWorkspaceLocation>>()
    private val backStack: MutableList<AsmWorkspaceLocation>
        get() = backStacks.getOrPut(activeSourcePaneId, ::mutableListOf)
    private val forwardStack: MutableList<AsmWorkspaceLocation>
        get() = forwardStacks.getOrPut(activeSourcePaneId, ::mutableListOf)
    private var pendingSnesAddress: Int? = null
    private var referenceWorkspace: AsmReferenceWorkspace? = null
    var referenceLoading by mutableStateOf(false)
        private set
    var referenceDiscoveryComplete by mutableStateOf(false)
        private set
    private var referenceLoadGeneration = 0L
    private var boundProjectFilePath: String = ""
    private var boundProjectEnabled: Boolean = false
    private var progressiveProjectLoading = false
    private var projectBindingGeneration = 0L
    private val sourceBuffers = mutableStateMapOf<String, String>()
    private val savedSourceTexts = mutableMapOf<String, String>()
    private val dirtySourceFileIds = mutableStateMapOf<String, Unit>()
    private val sourceUndoStacks = mutableMapOf<String, MutableList<AsmSourceTextChange>>()
    private val sourceRedoStacks = mutableMapOf<String, MutableList<AsmSourceTextChange>>()
    var sourceSaveParticipant: AsmSourceSaveParticipant? = null
    var sourceEditorInterceptor: ((String, String, String) -> Boolean?)? = null
    var sourceEditorLinks by mutableStateOf<((String, Int) -> List<AsmEditorLink>)?>(null)
    var roomSourceInspection by mutableStateOf<AsmRoomSourceInspection?>(null)
        private set
    var sourceIndexingInProgress by mutableStateOf(false)
        private set
    var sourceIndexingStatus by mutableStateOf("")
        private set
    var sourceIndexFailure by mutableStateOf<String?>(null)
        private set
    private var sourceIndexingJob: Job? = null
    private var sourceIndexingGeneration = 0L
    private val sourceIndexPendingFiles = linkedSetOf<String>()
    private val savedSourceDiagnostics = mutableMapOf<String, List<AsmBuildDiagnostic>>()

    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()
    val assetsMatchCurrentRom: Boolean
        get() = currentRomSha256 != null && currentRomSha256 == workspace?.metadata?.romSha256
    val referenceMetadata: AsmReferenceMetadata? get() = referenceWorkspace?.metadata
    val referenceAssetsMatchCurrentRom: Boolean
        get() = currentRomSha256 != null && currentRomSha256 == referenceWorkspace?.metadata?.romSha256
    val hasReferenceWorkspace: Boolean get() = referenceWorkspace != null
    val hasProjectWorkspace: Boolean get() = projectWorkspace != null
    val projectAsmUpdateAvailable: Boolean
        get() = projectWorkspace?.baselineRevision?.let { it != baselineUpdater.targetRevision } == true
    val projectModifiedFileIds: Set<String>
        get() = if (boundProjectEnabled && boundProjectFilePath.isNotBlank()) {
            projectRepository.sourceOverrideFileIds(boundProjectFilePath) - projectModuleFileIds.toSet()
        } else emptySet()
    val projectModuleFileIds: List<String> get() = projectWorkspace?.projectModuleFileIds.orEmpty()
    private val activeProjectModuleFileIds: Set<String>
        get() = if (boundProjectEnabled && boundProjectFilePath.isNotBlank()) {
            projectRepository.sourceOverrideFileIds(boundProjectFilePath).intersect(projectModuleFileIds.toSet())
        } else emptySet()
    val disabledProjectModuleFileIds: Set<String>
        get() = projectWorkspace?.disabledProjectModuleFileIds.orEmpty()
    val unsavedSourceFileIds: Set<String>
        get() = dirtySourceFileIds.keys.toSet()
    private val activeUnsavedSourceFileIds: Set<String>
        get() = dirtySourceFileIds.keys.filterNotTo(linkedSetOf()) {
            it in disabledProjectModuleFileIds
        }
    val projectModifiedAssetPaths: Set<String>
        get() = if (boundProjectEnabled && boundProjectFilePath.isNotBlank()) {
            projectRepository.assetOverridePaths(boundProjectFilePath) +
                projectRepository.managedAssets(boundProjectFilePath).map(AsmManagedAssetRecord::managedAssetPath)
        } else emptySet()
    val hasProjectSourceChanges: Boolean
        get() = projectModifiedFileIds.isNotEmpty() || activeProjectModuleFileIds.isNotEmpty() ||
            activeUnsavedSourceFileIds.isNotEmpty() ||
            projectModifiedAssetPaths.isNotEmpty()
    val isProjectSourceEditable: Boolean
        get() = workspaceKind == AsmWorkspaceKind.PROJECT &&
            (roomSourceInspection == null || roomSourceInspection?.automaticEdit != null) &&
            browserMode == AsmBrowserMode.SOURCE &&
            selectedFileId != null
    val sourceBuildRequired: Boolean
        get() = workspaceKind == AsmWorkspaceKind.PROJECT &&
            (activeUnsavedSourceFileIds.isNotEmpty() || buildReport?.succeeded != true)
    val sourceStatusSummary: String
        get() {
            roomSourceInspection?.takeIf { it.fileId == selectedFileId }?.let {
                return if (it.automaticEdit != null) "Room source · editable · project changes on first edit"
                else "Room source · read-only preview · not adopted"
            }
            if (browserMode == AsmBrowserMode.GENERATED_ROOMS) {
                return if (showRoomBuildWrites) "Room Data · build writes · last compilation" else "Room Data · last successful build"
            }
            if (browserMode != AsmBrowserMode.SOURCE) return ""
            val index = workspace?.index ?: return ""
            val fileId = selectedFileId ?: return ""
            val source = index.file(fileId) ?: return ""
            val compiledAddress = if (workspaceKind == AsmWorkspaceKind.PROJECT) {
                buildReport?.takeIf { it.succeeded }?.symbols?.addressAt(fileId, selectedLineIndex)
            } else null
            val indexedAddress = index.addressAtlas.exactAt(fileId, selectedLineIndex).firstOrNull()?.snesAddress
            val contextualAddress = index.addressAtlas.contextAt(fileId, selectedLineIndex)
            val exactAddress = compiledAddress ?: indexedAddress
            val displayedAddress = exactAddress ?: contextualAddress?.snesAddress
            val pcOffset = exactAddress?.let(::snesLoRomToPc) ?: contextualAddress?.pcOffset
            val lineCount = if (workspaceKind == AsmWorkspaceKind.PROJECT) {
                sourceEditText(fileId).lineSequence().count()
            } else {
                source.lines.size
            }
            return buildList {
                if (displayedAddress != null && pcOffset != null) {
                    add(
                        "${if (exactAddress == null) "Near " else ""}${formatSnesAddress(displayedAddress)} · " +
                            formatPcOffset(pcOffset),
                    )
                }
                add("$lineCount lines")
                if (workspaceKind == AsmWorkspaceKind.PROJECT) {
                    add(
                        when {
                            liveDiagnostics.any { it.severity == AsmDiagnosticSeverity.ERROR } -> "source error"
                            liveDiagnostics.any { it.severity == AsmDiagnosticSeverity.WARNING } -> "source warning"
                            buildReport?.validationOnly == true && buildReport?.diagnostics?.any {
                                it.severity == AsmDiagnosticSeverity.ERROR
                            } == true -> "source error"
                            buildReport?.validationOnly == true && buildReport?.diagnostics?.any {
                                it.severity == AsmDiagnosticSeverity.WARNING
                            } == true -> "source warning"
                            buildReport != null && buildReport?.succeeded != true -> "build failed"
                            sourceBuildRequired -> "build needed"
                            buildReport?.succeeded == true -> "build ✓"
                            else -> ""
                        },
                    )
                }
            }.filter(String::isNotBlank).joinToString(" · ")
        }

    /** Resolves the bank containing the source caret. A successful current
     * project build wins because edits can move code; otherwise the immutable
     * reference atlas supplies safe source context. Space is deliberately the
     * allocator's one contiguous trailing range, never a sum of apparent holes. */
    fun sourceBankBudget(fileId: String, lineIndex: Int): AsmSourceBankBudget? {
        val index = workspace?.index ?: return null
        if (index.file(fileId) == null) return null
        val compiledIsCurrent = workspaceKind == AsmWorkspaceKind.PROJECT &&
            !sourceBuildRequired && buildReport?.succeeded == true
        val compiledAddress = if (compiledIsCurrent) {
            buildReport?.symbols?.addressAtOrBefore(fileId, lineIndex)
        } else null
        val referenceAddress = index.addressAtlas.exactAt(fileId, lineIndex).firstOrNull()?.snesAddress
            ?: index.addressAtlas.contextAt(fileId, lineIndex)?.snesAddress
        val address = compiledAddress ?: referenceAddress ?: return null
        val bank = address ushr 16 and 0xFF
        val plannedBank = romSpacePlan?.banks?.firstOrNull { it.bank == bank }
        return AsmSourceBankBudget(
            bank = bank,
            capacityBytes = plannedBank?.capacityBytes ?: LOROM_BANK_CAPACITY_BYTES,
            allocatableContiguousBytes = plannedBank?.largestAfter,
            spaceIsCurrent = plannedBank != null && !sourceBuildRequired,
        )
    }

    fun compiledOutputInspection(
        fileId: String,
        lineIndex: Int,
        index: AsmReferenceIndex? = workspace?.index,
    ): AsmCompiledOutputInspection {
        val resolvedIndex = index ?: return AsmCompiledOutputInspection(
            fileId, lineIndex, "", AsmCompiledOutputStatus.BUILD_REQUIRED, emptyList(), emptyList(),
            emptyList(), null, null, emptyList(), emptyList(), emptyList(),
        )
        val report = buildReport
        val capturedSymbols = romPreviewSymbols
            ?: report?.takeIf { it.succeeded }?.symbols
            ?: AsmCompiledSymbols.EMPTY
        val preview = romPreview
        val stale = preview != null && (
            sourceBuildRequired ||
                romPreviewProjectRevision != currentProjectRevision ||
                romPreviewBuildGeneratedAt == null ||
                report?.takeIf { it.succeeded }?.generatedAt != romPreviewBuildGeneratedAt
            )
        return AsmCompiledOutputResolver.resolve(
            fileId = fileId,
            lineIndex = lineIndex,
            sourceText = sourceEditorText(fileId),
            index = resolvedIndex,
            symbols = capturedSymbols,
            preview = preview,
            spacePlan = romSpacePlan,
            stale = stale,
            diagnostics = displayedDiagnostics,
        )
    }

    fun runtimeDebugInspection(
        cpu: CpuDebugState,
        stackAddress: Int,
        stackBytes: ByteArray,
        sessionBuildIdentity: String?,
    ): AsmRuntimeInspection {
        val report = buildReport
        val mappingFresh = runtimeMappingFresh(sessionBuildIdentity)
        val symbols = report?.takeIf { it.succeeded && mappingFresh }?.symbols ?: AsmCompiledSymbols.EMPTY
        return AsmRuntimeDebugResolver.resolve(
            cpu = cpu,
            symbols = symbols,
            stackAddress = stackAddress,
            stackBytes = stackBytes,
            mappingStale = !mappingFresh,
            sourceLine = { fileId, lineIndex -> runtimeSourceLine(fileId, lineIndex) },
            isGenerated = { fileId -> generatedAsmAuthority(fileId) != null || workspace?.index?.file(fileId) == null },
        )
    }

    fun runtimeSourceAt(address: Int, sessionBuildIdentity: String?): AsmRuntimeSourceLocation? {
        if (!runtimeMappingFresh(sessionBuildIdentity)) return null
        val report = buildReport?.takeIf { it.succeeded } ?: return null
        val mapped = report.symbols.locationsAt(address).firstOrNull() ?: return null
        return AsmRuntimeSourceLocation(
            fileId = mapped.fileId,
            lineIndex = mapped.lineIndex,
            sourceLine = runtimeSourceLine(mapped.fileId, mapped.lineIndex),
            address = address,
            symbols = report.symbols.labelsAt(address),
            generated = generatedAsmAuthority(mapped.fileId) != null || workspace?.index?.file(mapped.fileId) == null,
        )
    }

    fun runtimeMappingFresh(sessionBuildIdentity: String?): Boolean {
        val report = buildReport
        return isAsmRuntimeMappingFresh(
            sessionBuildIdentity = sessionBuildIdentity,
            reportBuildIdentity = report?.generatedAt,
            reportSucceeded = report?.succeeded == true,
            sourceBuildRequired = sourceBuildRequired,
        )
    }

    suspend fun refreshSuccessfulBuildIdentity(): String? {
        refreshBuildReport(validateSources = false)
        return buildReport?.takeIf { it.succeeded }?.generatedAt
    }

    fun openRuntimeSource(location: AsmRuntimeSourceLocation): Boolean {
        if (location.generated || workspace?.index?.file(location.fileId) == null) return false
        openSource(location.fileId, location.lineIndex)
        return true
    }

    private fun runtimeSourceLine(fileId: String, lineIndex: Int): String? {
        if (workspace?.index?.file(fileId) != null) {
            return sourceEditorText(fileId).lineSequence().drop(lineIndex).firstOrNull()
        }
        val generatedId = fileId.substringAfterLast('/')
        return buildReport?.generatedSources?.get(fileId)?.getOrNull(lineIndex)
            ?: buildReport?.generatedSources?.get(generatedId)?.getOrNull(lineIndex)
    }

    fun diagnosticPresentation(diagnostic: AsmBuildDiagnostic): AsmDiagnosticPresentation {
        val fileId = diagnostic.fileId
        val lineIndex = diagnostic.lineIndex
        if (fileId == null || lineIndex == null) {
            return AsmDiagnosticPresentation(null, null, generated = false, authority = null, navigable = false)
        }
        val generatedAuthority = generatedAsmAuthority(fileId)
        if (generatedAuthority != null) {
            val generatedId = fileId.substringAfterLast('/')
            val sourceLine = buildReport?.generatedSources?.get(generatedId)?.getOrNull(lineIndex)
                ?: if (generatedId == "__smedit_generated_rooms.asm") {
                    generatedRoomInput?.lines?.getOrNull(lineIndex)
                } else null
            return AsmDiagnosticPresentation(
                location = "$generatedId:${lineIndex + 1}",
                sourceLine = sourceLine,
                generated = true,
                authority = generatedAuthority,
                navigable = generatedId == "__smedit_generated_rooms.asm" && generatedRoomInput != null,
            )
        }
        val authored = workspace?.index?.file(fileId) != null
        return AsmDiagnosticPresentation(
            location = "$fileId:${lineIndex + 1}",
            sourceLine = if (authored) sourceEditorText(fileId).lineSequence().drop(lineIndex).firstOrNull() else null,
            generated = false,
            authority = null,
            navigable = authored,
        )
    }

    fun openDiagnostic(diagnostic: AsmBuildDiagnostic): Boolean {
        val fileId = diagnostic.fileId ?: return false
        val lineIndex = diagnostic.lineIndex ?: return false
        return when {
            workspace?.index?.file(fileId) != null -> {
                openSource(fileId, lineIndex)
                true
            }
            fileId.substringAfterLast('/') == "__smedit_generated_rooms.asm" && generatedRoomInput != null -> {
                openGeneratedRooms(lineIndex, buildWrites = true)
                true
            }
            else -> false
        }
    }

    val hasSourceSplit: Boolean get() = sourcePanes.size > 1

    fun sourcePane(id: Long): AsmSourcePaneState? = sourcePanes.firstOrNull { it.id == id }

    fun activateSourcePane(id: Long) {
        val pane = sourcePane(id) ?: return
        if (activeSourcePaneId == id) return
        activeSourcePaneId = id
        selectedFileIdValue = pane.fileId
        selectedLineIndexValue = pane.lineIndex
        selectedColumnIndexValue = pane.columnIndex
        navigationSerialValue = pane.navigationSerial
        activeReferenceSymbolValue = pane.referenceSymbol
        activeReferenceMacroValue = pane.referenceMacro
        browserMode = AsmBrowserMode.SOURCE
        selectedAssetPath = null
    }

    /** Opens a source location in the other column. V1 deliberately supports
     * two columns rather than an unbounded split tree. */
    fun openSourceInSplit(fileId: String, lineIndex: Int = 0, columnIndex: Int = 0) {
        if (isProjectModule(fileId) || workspace?.index?.file(fileId) == null) return
        val current = sourcePane(activeSourcePaneId) ?: AsmSourcePaneState(PRIMARY_SOURCE_PANE_ID)
        val target = if (sourcePanes.size < MAX_SOURCE_PANES) {
            current.copy(id = nextSourcePaneId()).also { created ->
                sourcePanes = sourcePanes + created
            }
        } else {
            sourcePanes.last()
        }
        activateSourcePane(target.id)
        openSource(fileId, lineIndex, addToHistory = false, columnIndex = columnIndex)
        backStack.clear()
        forwardStack.clear()
    }

    fun splitActiveSource() {
        val fileId = selectedFileId ?: return
        if (isProjectModule(fileId)) return
        openSourceInSplit(fileId, selectedLineIndex, selectedColumnIndex)
    }

    fun closeSourcePane(id: Long = activeSourcePaneId) {
        if (!hasSourceSplit || sourcePane(id) == null) return
        sourcePanes = sourcePanes.filterNot { it.id == id }
        backStacks.remove(id)
        forwardStacks.remove(id)
        if (activeSourcePaneId == id || sourcePane(activeSourcePaneId) == null) {
            activateSourcePane(sourcePanes.first().id)
        }
    }

    private fun nextSourcePaneId(): Long =
        (sourcePanes.maxOfOrNull(AsmSourcePaneState::id) ?: PRIMARY_SOURCE_PANE_ID) + 1L

    private fun updateActiveSourcePane(transform: (AsmSourcePaneState) -> AsmSourcePaneState) {
        val index = sourcePanes.indexOfFirst { it.id == activeSourcePaneId }
        if (index < 0) return
        val updated = transform(sourcePanes[index])
        if (updated == sourcePanes[index]) return
        sourcePanes = sourcePanes.toMutableList().also { it[index] = updated }
    }

    fun isProjectModule(fileId: String?): Boolean = fileId != null && fileId in projectModuleFileIds

    fun isProjectModuleEnabled(fileId: String?): Boolean =
        isProjectModule(fileId) && fileId !in disabledProjectModuleFileIds &&
            (fileId != AsmFastDoorsModule.FILE || !AsmFastDoorsModule.hasGate(sourceEditText(fileId)) ||
                runCatching { AsmFastDoorsModule.enabled(sourceEditText(fileId)) }.getOrDefault(true))

    internal var codePatchModuleToggle: ((String, Boolean) -> Unit)? = null

    suspend fun loadInstalled() {
        if (referenceLoading) return
        referenceLoading = true
        val generation = ++referenceLoadGeneration
        try {
            val installed = withContext(Dispatchers.IO) { installedReferenceLoader() }
            if (generation != referenceLoadGeneration) return
            referenceWorkspace = installed
            // A late global-reference load may not reset a project already opened by the other effect.
            if (!boundProjectEnabled) {
                workspace = referenceWorkspace
                workspaceKind = AsmWorkspaceKind.REFERENCE
            } else if (projectWorkspace == null) loadBoundProjectWorkspace()
            if (!boundProjectEnabled) ensureSelection()
            referenceDiscoveryComplete = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) {
            if (generation == referenceLoadGeneration) {
                error = problem.message ?: "Could not open the ASM reference"
                referenceDiscoveryComplete = true
            }
        } finally {
            referenceLoading = false
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

    suspend fun bindProject(projectFilePath: String, enabled: Boolean, progressive: Boolean = false) {
        val changedProject = boundProjectFilePath != projectFilePath
        if (changedProject || !enabled) resetBaselineBackups()
        if (changedProject) {
            val draft = baselineUpdatePlan
            baselineUpdatePlan = null
            baselineUpdateMessage = null
            if (!busy && draft != null) withContext(Dispatchers.IO) { baselineUpdater.discard(draft) }
        }
        boundProjectFilePath = projectFilePath
        boundProjectEnabled = enabled
        progressiveProjectLoading = progressive
        projectBindingGeneration++
        if (changedProject) {
            clearEditSessions()
            projectWorkspace = null
            plmPlacementSnapshot = null
            // A captured placement belongs to one project; it cannot be reinterpreted in another.
            (backStacks.values + forwardStacks.values).forEach { stack ->
                stack.removeAll { it is AsmWorkspaceLocation.PlmPlacement }
            }
            if (browserMode == AsmBrowserMode.PLM_PLACEMENT) showSourceBrowser()
            roomDataExpanded = false
            expandedRoomDataGroups.clear()
            showRoomBuildWrites = false
        }
        if (!enabled || projectFilePath.isBlank()) {
            clearEditSessions()
            projectWorkspace = null
            buildReport = null
            roomDataSnapshot = null
            generatedRoomInput = null
            if (browserMode == AsmBrowserMode.GENERATED_ROOMS) showSourceBrowser()
            if (workspaceKind == AsmWorkspaceKind.PROJECT) showReferenceWorkspace()
            return
        }
        loadBoundProjectWorkspace()
        refreshBuildReport(validateSources = !progressive)
    }

    suspend fun refreshBuildReport(validateSources: Boolean = true) {
        val projectFilePath = boundProjectFilePath
        if (!boundProjectEnabled || projectFilePath.isBlank()) {
            generatedRoomInput = null
            roomDataSnapshot = null
            buildReport = null
            return
        }
        val generation = projectBindingGeneration
        val revision = sourceBufferRevision
        val previousReport = buildReport
        val sourceRoot = projectWorkspace?.workingDirectory?.let { java.io.File(it, "src") }
        val disabled = disabledProjectModuleFileIds
        val result = withContext(Dispatchers.IO) {
                val validation = sourceRoot?.takeIf { validateSources }?.let {
                    AsmSourceLinter.lintTree(it, disabled)
                }.orEmpty()
                Triple(buildArtifactRepository.loadGeneratedRooms(projectFilePath),
                    buildArtifactRepository.loadRoomData(projectFilePath),
                    AsmSourceLinter.report(validation) ?: buildArtifactRepository.load(projectFilePath))
        }
        if (projectFilePath != boundProjectFilePath || generation != projectBindingGeneration || !boundProjectEnabled) return
        generatedRoomInput = result.first
        roomDataSnapshot = result.second
        if (sourceBufferRevision == revision && buildReport === previousReport) buildReport = result.third
    }

    suspend fun enableProjectWorkspace(projectFilePath: String, romBytes: ByteArray? = null): Boolean {
        val reference = referenceWorkspace ?: run {
            error = "Download the ASM reference before enabling project ASM"
            return false
        }
        if (busy) return false
        busy = true
        error = null
        val requireExistingBaseline = boundProjectEnabled && boundProjectFilePath == projectFilePath
        return try {
            val result = withContext(Dispatchers.IO) {
                projectRepository.initializeFromRom(projectFilePath, reference, romBytes,
                    requireExistingBaseline = requireExistingBaseline) { message -> progress = message }
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

    suspend fun previewBaselineUpdate(): Boolean {
        if (busy || projectWorkspace == null || boundProjectFilePath.isBlank()) return false
        if (!projectAsmUpdateAvailable) {
            error = null
            baselineUpdateMessage = "ASM version ${baselineUpdater.targetRevision} is current."
            return false
        }
        if (unsavedSourceFileIds.isNotEmpty()) {
            error = "Save the open ASM file before updating the ASM version."
            return false
        }
        val reference = referenceWorkspace ?: run { error = "Download the current ASM reference before updating."; return false }
        val path = boundProjectFilePath
        val generation = projectBindingGeneration
        busy = true
        error = null
        progress = "Comparing the current ASM version, project edits and updated version…"
        return try {
            val plan = withContext(Dispatchers.IO) { baselineUpdater.prepare(path, reference) }
            if (path != boundProjectFilePath || generation != projectBindingGeneration) {
                withContext(Dispatchers.IO) { baselineUpdater.discard(plan) }
                false
            } else {
                baselineUpdatePlan?.let { previous -> withContext(Dispatchers.IO) { baselineUpdater.discard(previous) } }
                baselineUpdatePlan = plan
                baselinePreviewBufferRevision = sourceBufferRevision
                baselineUpdateMessage = null
                true
            }
        } catch (problem: Exception) {
            error = problem.message ?: "Could not prepare the ASM version update"
            false
        } finally { busy = false; progress = null }
    }

    suspend fun cancelBaselineUpdate() {
        if (busy) return
        val plan = baselineUpdatePlan ?: return
        baselineUpdatePlan = null
        withContext(Dispatchers.IO) { baselineUpdater.discard(plan) }
    }

    suspend fun applyBaselineUpdate(resolutions: Map<String, Map<Int, String>>, romBytes: ByteArray): Boolean {
        val plan = baselineUpdatePlan ?: return false
        if (busy) return false
        if (plan.projectFilePath != boundProjectFilePath || sourceBufferRevision != baselinePreviewBufferRevision || unsavedSourceFileIds.isNotEmpty()) {
            error = "Source changed after the update preview. Cancel and preview the update again."
            return false
        }
        busy = true
        error = null
        cancelSourceIndexing()
        val generation = projectBindingGeneration
        return try {
            val result = withContext(Dispatchers.IO) { baselineUpdater.apply(plan, resolutions, romBytes) { progress = it } }
            if (plan.projectFilePath != boundProjectFilePath || generation != projectBindingGeneration) return false
            baselineUpdatePlan = null
            // Do not retain offsets, undo transactions or source buffers from the previous baseline.
            clearEditSessions()
            sourcePanes = sourcePanes.map { it.copy(lineIndex = 0, columnIndex = 0, referenceSymbol = null, referenceMacro = null, navigationSerial = it.navigationSerial + 1) }
            selectedLineIndexValue = 0
            selectedColumnIndexValue = 0
            projectWorkspace = result.workspace
            activateProjectWorkspace(result.workspace)
            backStacks.values.forEach { it.clear() }
            forwardStacks.values.forEach { it.clear() }
            buildReport = null
            generatedRoomInput = null
            roomDataSnapshot = null
            clearRomPreview()
            baselineUpdateMessage = "ASM build validation passed. Updated to ${plan.nextRevision}. Previous ASM saved in ${result.backupDirectory.path}"
            true
        } catch (problem: Exception) {
            if (plan.consumed) baselineUpdatePlan = null
            error = problem.message ?: "The ASM version update could not be validated. The current project was not replaced."
            false
        } finally { busy = false; progress = null }
    }

    private fun resetBaselineBackups() {
        backupRequestGeneration++
        baselineBackupsVisible = false
        baselineBackups = null
        baselineBackupsBusy = false
        baselineBackupsDeleting = false
        baselineBackupsError = null
        baselineBackupsMessage = null
    }

    fun closeBaselineBackups() { if (!baselineBackupsDeleting) resetBaselineBackups() }

    suspend fun showBaselineBackups() {
        if (busy || boundProjectFilePath.isBlank() || projectWorkspace == null) return
        baselineBackupsVisible = true
        refreshBaselineBackups()
    }

    private data class BackupOperationResult(
        val listing: AsmBaselineBackupList? = null, val message: String? = null, val problem: String? = null,
    )

    suspend fun refreshBaselineBackups(): Boolean = backupOperation { path ->
        BackupOperationResult(listing = withContext(Dispatchers.IO) { backupListLoader(path) })
    }

    suspend fun deleteBaselineBackup(selected: AsmBaselineBackup): Boolean = backupOperation(deleting = true) { path ->
        var failure: Exception? = null
        try { withContext(Dispatchers.IO) { backupRepository.delete(path, selected) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { failure = problem }
        // Also refresh after partial removal/failed guards; preserve the deletion error if refresh fails.
        val listing = try { withContext(Dispatchers.IO) { backupListLoader(path) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { if (failure == null) failure = problem; null }
        BackupOperationResult(listing,
            if (failure == null) "Previous ASM version deleted. Active project source was not changed." else null,
            failure?.message)
    }

    suspend fun revealBaselineBackup(selected: AsmBaselineBackup): Boolean = backupOperation { path ->
        withContext(Dispatchers.IO) { backupFolderOpener(backupRepository.folder(path, selected)) }
        BackupOperationResult()
    }

    private suspend fun backupOperation(deleting: Boolean = false, action: suspend (String) -> BackupOperationResult): Boolean {
        if (busy || baselineBackupsBusy || !baselineBackupsVisible || boundProjectFilePath.isBlank()) return false
        val path = boundProjectFilePath
        val binding = projectBindingGeneration
        val request = ++backupRequestGeneration
        baselineBackupsBusy = true
        baselineBackupsDeleting = deleting
        baselineBackupsError = null
        return try {
            val result = action(path)
            if (path != boundProjectFilePath || binding != projectBindingGeneration || request != backupRequestGeneration) false
            else {
                result.listing?.let { baselineBackups = it }
                baselineBackupsMessage = result.message
                baselineBackupsError = result.problem
                result.problem == null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            if (path == boundProjectFilePath && binding == projectBindingGeneration && request == backupRequestGeneration)
                baselineBackupsError = problem.message ?: "Could not manage previous ASM versions."
            false
        } finally {
            if (request == backupRequestGeneration) {
                baselineBackupsBusy = false
                baselineBackupsDeleting = false
            }
        }
    }

    fun showReferenceWorkspace() {
        roomSourceInspection = null
        val reference = referenceWorkspace ?: return
        workspace = reference
        workspaceKind = AsmWorkspaceKind.REFERENCE
        if (browserMode == AsmBrowserMode.GENERATED_ROOMS) showSourceBrowser()
        ensureSelection()
        navigationSerial++
    }

    fun showProjectWorkspace() {
        projectWorkspace?.let(::activateProjectWorkspace)
    }

    fun dismissError() {
        error = null
    }

    fun observeProjectRevision(revision: Long) {
        currentProjectRevision = revision
    }

    fun requestProjectChanges() {
        projectChangesRequestSerial++
    }

    private fun clearRomPreview() {
        romPreview = null
        romSpacePlan = null
        romPreviewProjectRevision = null
        romPreviewBuildGeneratedAt = null
        romPreviewSymbols = null
        requestedRomSpaceBank = null
        selectedRomDiffIndex = 0
        romPreviewView = AsmRomPreviewView.DIFF
    }

    fun requestSearchFocus() {
        searchFocusSerial++
    }

    fun showSourceBrowser() {
        roomSourceInspection = null
        browserMode = AsmBrowserMode.SOURCE
        selectedAssetPath = null
        navigationSerial++
    }

    fun openPlmPlacement(snapshot: AsmPlmPlacementSnapshot, addToHistory: Boolean = true) {
        if (addToHistory) rememberCurrentLocation()
        roomSourceInspection = null
        plmPlacementSnapshot = snapshot
        browserMode = AsmBrowserMode.PLM_PLACEMENT
        if (addToHistory) forwardStack.clear()
    }

    fun openGeneratedRooms(lineIndex: Int = 0, addToHistory: Boolean = true, buildWrites: Boolean = showRoomBuildWrites) {
        if (!hasProjectWorkspace) return
        if (addToHistory) rememberCurrentLocation()
        roomSourceInspection = null
        restoreWorkspaceKind(AsmWorkspaceKind.PROJECT)
        browserMode = AsmBrowserMode.GENERATED_ROOMS
        showRoomBuildWrites = buildWrites
        roomDataExpanded = true
        if (buildWrites) expandedRoomDataGroups["build-writes"] = true
        generatedRoomLineIndex = lineIndex.coerceIn(0, (generatedRoomSource?.lines?.lastIndex ?: 0).coerceAtLeast(0))
        generatedRoomSource?.sectionAt(generatedRoomLineIndex)?.let { section ->
            expandedRoomDataGroups[roomDataGroupKey(section.roomId, buildWrites)] = true
        }
        generatedRoomNavigationSerial++
        if (addToHistory) forwardStack.clear()
    }

    fun showAssetBrowser() {
        roomSourceInspection = null
        browserMode = AsmBrowserMode.ASSETS
        if (selectedAssetPath == null) {
            selectedAssetPath = workspace?.index?.assets?.firstOrNull()?.range?.path
        }
        navigationSerial++
    }

    fun showLibraryBrowser() {
        roomSourceInspection = null
        browserMode = AsmBrowserMode.LIBRARY
        selectedAssetPath = null
        navigationSerial++
    }

    fun showRomBrowser() {
        roomSourceInspection = null
        browserMode = AsmBrowserMode.ROM
        selectedAssetPath = null
        navigationSerial++
    }

    fun selectRomPreviewView(view: AsmRomPreviewView, addToHistory: Boolean = true) {
        if (addToHistory) rememberCurrentLocation()
        roomSourceInspection = null
        browserMode = AsmBrowserMode.ROM
        romPreviewView = view
        requestedRomSpaceBank = null
        navigationSerial++
        if (addToHistory) forwardStack.clear()
    }

    fun openRomSpaceBank(bank: Int, addToHistory: Boolean = true) {
        selectRomPreviewView(AsmRomPreviewView.SPACE, addToHistory)
        requestedRomSpaceBank = bank and 0xFF
    }

    fun selectRomDiff(index: Int) {
        val ranges = romPreview?.diffRanges.orEmpty()
        if (ranges.isEmpty()) return
        selectedRomDiffIndex = index.coerceIn(ranges.indices)
        romPreviewView = AsmRomPreviewView.DIFF
        navigationSerial++
    }

    fun openRomComparison(pcOffset: Int?) {
        val ranges = romPreview?.diffRanges.orEmpty()
        val index = pcOffset?.let { pc -> ranges.indexOfFirst { pc in it.pcOffset..it.endInclusive } } ?: -1
        if (index >= 0) {
            selectRomPreviewView(AsmRomPreviewView.DIFF)
            selectRomDiff(index)
        } else {
            selectRomPreviewView(AsmRomPreviewView.DIFF)
        }
    }

    suspend fun buildRomPreview(builder: () -> AsmRomPreviewBuild) {
        if (previewBusy) return
        val requestedView = romPreviewView
        val requestedProjectRevision = currentProjectRevision
        previewBusy = true
        error = null
        try {
            val attempt = withContext(Dispatchers.Default) { builder() }
            refreshBuildReport()
            romSpacePlan = attempt.spacePlan
            if (attempt.preview == null) {
                romPreview = null
                romPreviewProjectRevision = null
                romPreviewBuildGeneratedAt = null
                romPreviewSymbols = null
                error = attempt.error ?: "SMEDIT Result could not be built. No ROM was written."
                if (requestedView == AsmRomPreviewView.SPACE) {
                    browserMode = AsmBrowserMode.ROM
                    romPreviewView = requestedView
                    navigationSerial++
                }
            } else {
                romPreview = attempt.preview
                romPreviewProjectRevision = requestedProjectRevision
                romPreviewBuildGeneratedAt = buildReport?.takeIf { it.succeeded }?.generatedAt
                romPreviewSymbols = buildReport?.takeIf { it.succeeded }?.symbols
                selectedRomDiffIndex = 0
                romPreviewView = if (requestedView == AsmRomPreviewView.SPACE) requestedView else AsmRomPreviewView.DIFF
                browserMode = AsmBrowserMode.ROM
                navigationSerial++
            }
        } catch (problem: Exception) {
            romPreview = null
            romSpacePlan = null
            romPreviewProjectRevision = null
            romPreviewBuildGeneratedAt = null
            romPreviewSymbols = null
            error = problem.message ?: "SMEDIT Result could not be built"
        } finally {
            previewBusy = false
        }
    }

    /** Runs the same non-writing validated build as ROM comparison while
     * keeping the user in Source. */
    suspend fun validateProjectBuild(builder: () -> AsmRomPreviewBuild): Boolean {
        if (previewBusy) return false
        val requestedProjectRevision = currentProjectRevision
        previewBusy = true
        error = null
        return try {
            val result = withContext(Dispatchers.Default) { builder() }
            refreshBuildReport()
            romSpacePlan = result.spacePlan
            if (result.preview == null) {
                retainOrCreateBuildFailure(result.error ?: "ROM build did not complete. No ROM was written.")
                false
            } else {
                romPreview = result.preview
                romPreviewProjectRevision = requestedProjectRevision
                romPreviewBuildGeneratedAt = buildReport?.takeIf { it.succeeded }?.generatedAt
                romPreviewSymbols = buildReport?.takeIf { it.succeeded }?.symbols
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
        val diagnostic = AsmBuildOutputParser.pipelineDiagnostic(message)
        buildReport = AsmBuildReport(
            succeeded = false,
            generatedAt = Instant.now().toString(),
            assemblerVersion = existing?.assemblerVersion.orEmpty(),
            output = listOf(message),
            diagnostics = listOf(diagnostic),
            symbols = existing?.symbols ?: AsmCompiledSymbols.EMPTY,
        )
    }

    /** Bring the build drawer into view and reveal the first located error in editable source. */
    fun showBuildFailure() {
        showProjectWorkspace()
        showSourceBrowser()
        displayedDiagnostics.firstOrNull {
            it.severity == AsmDiagnosticSeverity.ERROR && diagnosticPresentation(it).navigable
        }?.let(::openDiagnostic)
    }

    fun openSource(
        fileId: String,
        lineIndex: Int = 0,
        addToHistory: Boolean = true,
        preserveReferences: Boolean = false,
        columnIndex: Int = 0,
    ) {
        val source = workspace?.index?.file(fileId) ?: return
        if (selectedFileId != source.id) {
            liveDiagnosticFileId = null
            liveDiagnostics = emptyList()
        }
        if (addToHistory) rememberCurrentLocation()
        roomSourceInspection = null
        if (!preserveReferences) {
            activeReferenceSymbol = null
            activeReferenceMacro = null
            referenceExpansionTarget = null
        }
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
        selectedLibraryDirective = null
        navigationSerial++
        if (addToHistory) forwardStack.clear()
    }

    fun openRoomSourceInspection(snapshot: AsmRoomSourceInspection, addToHistory: Boolean = true) {
        val project = projectWorkspace ?: return
        if (project.referenceWorkspace.index.file(snapshot.fileId) == null) return
        if (addToHistory) rememberCurrentLocation()
        activateProjectWorkspace(project)
        openSource(snapshot.fileId, addToHistory = false)
        roomSourceInspection = snapshot
        snapshot.automaticEdit?.let { selectSourcePosition(it.lineIndex, 0) }
        if (addToHistory) forwardStack.clear()
    }

    fun sourceEditorText(fileId: String): String = roomSourceInspection
        ?.takeIf { it.fileId == fileId }?.automaticEdit?.text ?: sourceEditText(fileId)

    fun finishRoomSourceInspection() { roomSourceInspection = null }

    fun reportSourceEditError(message: String) { error = message }

    fun selectSourceEditorMatch(fileId: String, lineIndex: Int, columnIndex: Int) {
        if (roomSourceInspection?.fileId == fileId && roomSourceInspection?.automaticEdit != null) {
            selectSourcePosition(lineIndex, columnIndex)
            navigationSerial++
        } else openSource(fileId, lineIndex, addToHistory = false, columnIndex = columnIndex)
    }

    /** Sidebar results may point into the safe room-source overlay. Keep that
     * overlay alive when it owns the result; ordinary results retain normal
     * source navigation and history. */
    fun openSourceSearchResult(fileId: String, lineIndex: Int, columnIndex: Int) {
        if (roomSourceInspection?.fileId == fileId && roomSourceInspection?.automaticEdit != null) {
            selectSourceEditorMatch(fileId, lineIndex, columnIndex)
        } else {
            openSource(fileId, lineIndex, columnIndex = columnIndex)
        }
    }

    fun sourceEditText(fileId: String): String = sourceBuffers[fileId]
        ?: workspace?.index?.file(fileId)?.file?.readText().orEmpty()

    /** Captures Project ASM changes without adopting files into the live editor.
     * Project modules have no immutable-bank counterpart and therefore compare
     * against an empty baseline as whole-file additions. */
    fun projectSourceChangesSnapshot(semanticIndex: AsmSemanticIndex? = null): List<AsmProjectSourceChange> {
        val project = projectWorkspace ?: return emptyList()
        val currentPlacements = projectChangePlacements()
        val moduleIds = project.projectModuleFileIds.toSet()
        val managedBySource = projectRepository.managedAssets(boundProjectFilePath).groupBy(AsmManagedAssetRecord::sourceFileId)
        val fileIds = linkedSetOf<String>().apply {
            addAll(projectModifiedFileIds)
            addAll(project.projectModuleFileIds)
            addAll(unsavedSourceFileIds)
        }
        return fileIds.map { fileId ->
            val projectModule = fileId in moduleIds
            val savedFile = File(project.workingDirectory, "src/$fileId")
            require(savedFile.isFile) { "Project ASM source is unavailable: $fileId" }
            val savedText = savedFile.readText()
            val baselineText = if (projectModule) {
                ""
            } else {
                val baselineFile = File(project.originalDirectory, "src/$fileId")
                require(baselineFile.isFile) { "Project ASM baseline source is unavailable: $fileId" }
                baselineFile.readText()
            }
            val workingText = sourceBuffers[fileId] ?: savedText
            val links = projectSourceChangeLinks(project, fileId, baselineText, workingText, semanticIndex)
            val managed = managedBySource[fileId].orEmpty()
            val unsaved = savedText != workingText
            val restoreBlockedReason = when {
                unsaved -> "Save or revert the open draft before restoring this complete file."
                projectModule && fileId == AsmFastDoorsModule.FILE && AsmFastDoorsModule.hasGate(savedText) ->
                    "Fast Doors retains custom module code; turn it off through its patch toggle instead."
                managed.isNotEmpty() -> "Restore ${managed.size} managed asset transaction${if (managed.size == 1) "" else "s"} first; its source marker and manifest must stay synchronized."
                projectModule -> null
                baselineText == savedText -> "Only an unsaved draft differs; use the source editor's Revert action."
                else -> null
            }
            val moduleOwner = fileId.takeIf { projectModule }
                ?.let { "asm-module:${it.removePrefix(ASM_PROJECT_MODULE_PREFIX).removeSuffix(".asm")}" }
            val allocations = currentPlacements.filter { placement ->
                placement.segmentId?.startsWith("checked-hook-helper:$fileId:") == true ||
                    moduleOwner != null && placement.owner == moduleOwner
            }.map(::projectAllocationSummary)
            AsmProjectSourceChange(
                fileId = fileId,
                baselineText = baselineText,
                savedText = savedText,
                workingText = workingText,
                projectModule = projectModule,
                enabled = !projectModule || fileId !in project.disabledProjectModuleFileIds,
                semantic = AsmSemanticChanges.source(
                    fileId,
                    baselineText,
                    workingText,
                    projectModule,
                    links,
                    origin = when {
                        managed.isNotEmpty() -> AsmSemanticChangeOrigin.CONTAINS_MANAGED_RELOCATION
                        projectModule -> AsmSemanticChangeOrigin.PROJECT_MODULE
                        else -> AsmSemanticChangeOrigin.AUTHORED_SOURCE
                    },
                ),
                restoreRequest = if (restoreBlockedReason != null) null else if (projectModule) {
                    AsmProjectRestoreRequest.ProjectModule(
                        fileId = fileId,
                        expectedText = savedText,
                        expectedModuleOrder = project.projectModuleFileIds.map { it.removePrefix(ASM_PROJECT_MODULE_PREFIX) },
                        expectedDisabledModules = project.disabledProjectModuleFileIds
                            .mapTo(linkedSetOf()) { it.removePrefix(ASM_PROJECT_MODULE_PREFIX) },
                        projectFilePath = boundProjectFilePath,
                    )
                } else {
                    AsmProjectRestoreRequest.SourceFile(fileId, savedText, baselineText, boundProjectFilePath)
                },
                restoreBlockedReason = restoreBlockedReason,
                allocations = allocations,
            )
        }.sortedWith(compareBy<AsmProjectSourceChange> { it.projectModule }.thenBy { it.fileId })
    }

    fun projectAssetChangesSnapshot(semanticIndex: AsmSemanticIndex? = null): List<AsmProjectAssetChange> {
        val project = projectWorkspace ?: return emptyList()
        val currentPlacements = projectChangePlacements()
        val managedByPath = projectRepository.managedAssets(boundProjectFilePath)
            .associateBy(AsmManagedAssetRecord::managedAssetPath)
        return projectModifiedAssetPaths.map { assetPath ->
            val managed = managedByPath[assetPath]
            if (managed != null) {
                val current = AsmManagedAssets.assetFile(project.root, managed)
                val before = if (managed.inlineSource) managed.inlineOriginalBytes() else {
                    val baseline = File(project.originalDirectory, "data/${managed.originalAssetPath}")
                    require(baseline.isFile) { "Managed project ASM baseline asset is unavailable: $assetPath" }
                    baseline.readBytes()
                }
                require(current.isFile) {
                    "Managed project ASM data asset is unavailable: $assetPath"
                }
                val after = current.readBytes()
                val overlap = minOf(before.size, after.size)
                val changed = (0 until overlap).filter { before[it] != after[it] }
                val sourceFile = File(project.workingDirectory, "src/${managed.sourceFileId}")
                require(sourceFile.isFile) { "Managed project ASM source is unavailable: ${managed.sourceFileId}" }
                val sourceText = sourceFile.readText()
                val dirtySource = sourceBuffers[managed.sourceFileId]?.takeIf { it != sourceText }
                val links = pcToSnesLoRom(managed.originalPcOffset)?.let { semanticIndex?.linksFor(it, managed.originalAssetPath) }.orEmpty()
                val allocations = currentPlacements
                    .filter { it.segmentId == "asm-managed-asset:${managed.id}" || it.owner == "asm-managed-asset:${managed.id}" }
                    .map(::projectAllocationSummary)
                return@map AsmProjectAssetChange(
                    assetPath = assetPath,
                    size = after.size,
                    changedBytes = changed.size + kotlin.math.abs(after.size - before.size),
                    firstChangedOffsets = changed.take(32),
                    managed = true,
                    originalAssetPath = managed.originalAssetPath,
                    originalCapacity = managed.originalLength,
                    sourceLabel = managed.sourceLabel,
                    compiledAddress = buildReport?.takeIf { currentPlacements.isNotEmpty() }
                        ?.symbols?.labels?.get(managed.sourceLabel),
                    inlineSource = managed.inlineSource,
                    semantic = AsmSemanticChanges.asset(assetPath, links, managed),
                    restoreRequest = dirtySource?.let { null } ?: AsmProjectRestoreRequest.ManagedAsset(
                        managedAssetId = managed.id,
                        sourceFileId = managed.sourceFileId,
                        expectedBytes = after.copyOf(),
                        expectedSourceText = sourceText,
                        sourceLabel = managed.sourceLabel,
                        projectFilePath = boundProjectFilePath,
                    ),
                    restoreBlockedReason = dirtySource?.let {
                        "Save or revert the open ${managed.sourceFileId} draft before restoring this managed transaction."
                    },
                    allocations = allocations,
                )
            }
            val baseline = File(project.originalDirectory, "data/$assetPath")
            val current = File(project.workingDirectory, "data/$assetPath")
            require(baseline.isFile && current.isFile && baseline.length() == current.length()) {
                "Project ASM data asset is unavailable: $assetPath"
            }
            val before = baseline.readBytes()
            val after = current.readBytes()
            val changed = before.indices.filter { before[it] != after[it] }
            val range = project.referenceWorkspace.index.asset(assetPath)?.range
            val links = range?.let { semanticIndex?.linksFor(it.snesAddress, assetPath) }.orEmpty()
            AsmProjectAssetChange(
                assetPath = assetPath,
                size = after.size,
                changedBytes = changed.size,
                firstChangedOffsets = changed.take(32),
                semantic = AsmSemanticChanges.asset(assetPath, links),
                restoreRequest = AsmProjectRestoreRequest.DirectAsset(
                    assetPath, after.copyOf(), before.copyOf(), boundProjectFilePath,
                ),
            )
        }.sortedBy(AsmProjectAssetChange::assetPath)
    }

    private fun projectAllocationSummary(placement: AsmRomSpacePlacement) = AsmProjectAllocationSummary(
        label = placement.label,
        snesAddress = placement.snesAddress,
        size = placement.size,
        kind = placement.segmentKind ?: "managed allocation",
        constraint = placement.constraint,
    )

    private fun projectChangePlacements(): List<AsmRomSpacePlacement> {
        val report = buildReport?.takeIf { it.succeeded } ?: return emptyList()
        if (sourceBuildRequired || romPreviewProjectRevision != currentProjectRevision ||
            romPreviewBuildGeneratedAt != report.generatedAt) return emptyList()
        return romSpacePlan?.placements.orEmpty()
    }

    private fun projectSourceChangeLinks(
        project: AsmProjectWorkspace,
        fileId: String,
        baselineText: String,
        workingText: String,
        semanticIndex: AsmSemanticIndex?,
    ): List<AsmEditorLink> {
        val source = project.referenceWorkspace.index.file(fileId) ?: return emptyList()
        val lines = workingText.lines()
        val atlas = source.lines.indices.mapNotNull { line ->
            project.referenceWorkspace.index.addressAtlas.exactAt(fileId, line).firstOrNull()?.let { line to it.snesAddress }
        }.toMap()
        val compiled = buildReport?.takeIf { it.succeeded && !sourceBuildRequired }
            ?.symbols?.addressesFor(fileId).orEmpty()
        val resolver = AsmVisualEditorResolver(
            source = source,
            lines = lines,
            semanticIndex = semanticIndex,
            indexedAddresses = atlas + compiled,
            liveLinks = { line -> sourceEditorLinks?.invoke(fileId, line).orEmpty() },
        )
        // Removed room macros still carry a stable semantic identity. Resolve
        // them without current live/address mappings so deleted source cannot
        // inherit a shifted current-ROM owner.
        val baselineLines = baselineText.lines()
        val baselineResolver = AsmVisualEditorResolver(
            source = source.copy(lines = baselineLines),
            lines = baselineLines,
            semanticIndex = null,
            indexedAddresses = emptyMap(),
        )
        return AsmThreeWayMerge.compare(baselineText, workingText).lines.asSequence()
            .flatMap { line -> when (line.kind) {
                AsmDiffLineKind.ADDED -> line.newLine?.minus(1)?.let(resolver::linksAt).orEmpty().asSequence()
                AsmDiffLineKind.REMOVED -> line.oldLine?.minus(1)?.let(baselineResolver::linksAt).orEmpty().asSequence()
                else -> emptySequence()
            } }
            .distinctBy(AsmEditorLink::target)
            .toList()
    }

    /** Restores exactly one ownership transaction captured by Project ASM Changes.
     * Every repository operation rechecks disk state; open buffers are checked
     * here so a stale review can never erase a newer editor draft. */
    suspend fun restoreProjectChange(request: AsmProjectRestoreRequest): Boolean {
        if (workspaceKind != AsmWorkspaceKind.PROJECT || boundProjectFilePath.isBlank() || busy) return false
        if (request.projectFilePath != null && request.projectFilePath != boundProjectFilePath) {
            error = "This Project ASM Changes review belongs to another project. Reopen it before restoring."
            return false
        }
        val guardedSource = when (request) {
            is AsmProjectRestoreRequest.SourceFile -> request.fileId to request.expectedText
            is AsmProjectRestoreRequest.ProjectModule -> request.fileId to request.expectedText
            is AsmProjectRestoreRequest.ManagedAsset -> request.sourceFileId to request.expectedSourceText
            is AsmProjectRestoreRequest.DirectAsset -> null
        }
        guardedSource?.let { (fileId, expected) ->
            val open = sourceBuffers[fileId]
            if (open != null && open != expected) {
                error = "$fileId has a newer open draft. Save or revert it, then reopen Project ASM Changes."
                return false
            }
        }
        cancelSourceIndexing()
        busy = true
        error = null
        return try {
            progress = "Restoring ${request.label}…"
            val result = withContext(Dispatchers.IO) {
                when (request) {
                    is AsmProjectRestoreRequest.SourceFile ->
                        projectRepository.restoreSource(
                            boundProjectFilePath,
                            request.fileId,
                            request.expectedText,
                            request.expectedBaselineText,
                        )
                    is AsmProjectRestoreRequest.ProjectModule ->
                        projectRepository.deleteModule(
                            boundProjectFilePath,
                            request.fileId,
                            request.expectedText,
                            request.expectedModuleOrder,
                            request.expectedDisabledModules,
                        )
                    is AsmProjectRestoreRequest.DirectAsset -> {
                        projectRepository.restoreAsset(
                            boundProjectFilePath,
                            request.assetPath,
                            request.expectedBytes,
                            request.expectedBaselineBytes,
                        )
                        requireNotNull(projectRepository.load(boundProjectFilePath)) {
                            "Project ASM workspace did not validate after restoring ${request.assetPath}"
                        }
                    }
                    is AsmProjectRestoreRequest.ManagedAsset -> {
                        projectRepository.restoreManagedAsset(
                            boundProjectFilePath,
                            request.managedAssetId,
                            request.expectedBytes,
                            expectedSourceText = request.expectedSourceText,
                        )
                        requireNotNull(projectRepository.load(boundProjectFilePath)) {
                            "Project ASM workspace did not validate after restoring ${request.sourceLabel}"
                        }
                    }
                }
            }
            val affectedSource = when (request) {
                is AsmProjectRestoreRequest.SourceFile -> request.fileId
                is AsmProjectRestoreRequest.ProjectModule -> request.fileId
                is AsmProjectRestoreRequest.ManagedAsset -> request.sourceFileId
                is AsmProjectRestoreRequest.DirectAsset -> null
            }
            if (request is AsmProjectRestoreRequest.ProjectModule) {
                sourceBuffers.remove(request.fileId)
                savedSourceTexts.remove(request.fileId)
                dirtySourceFileIds.remove(request.fileId)
                sourceUndoStacks.remove(request.fileId)
                sourceRedoStacks.remove(request.fileId)
            } else affectedSource?.let { fileId ->
                val restored = result.referenceWorkspace.index.file(fileId)?.file?.readText()
                    ?: error("Restored source is unavailable: $fileId")
                if (fileId in sourceBuffers) sourceBuffers[fileId] = restored
                savedSourceTexts[fileId] = restored
                dirtySourceFileIds.remove(fileId)
                sourceUndoStacks.remove(fileId)
                sourceRedoStacks.remove(fileId)
            }
            adoptProjectWorkspace(result)
            buildReport = null
            generatedRoomInput = null
            roomDataSnapshot = null
            clearRomPreview()
            ensureSelection()
            true
        } catch (problem: Exception) {
            error = problem.message ?: "Could not restore ${request.label}"
            false
        } finally {
            busy = false
            progress = null
        }
    }

    /** Semantic editors share the very same document/undo stack, independent of which browser tab is visible. */
    fun projectSourceText(fileId: String): String {
        sourceBuffers[fileId]?.let { return it }
        val active = projectWorkspace ?: return ""
        // A partial/missing index entry is not an empty document. Never let a
        // semantic writer replace a bank it has not actually loaded.
        val source = requireNotNull(active.referenceWorkspace.index.file(fileId)) {
            "ASM source $fileId is unavailable. Wait for indexing or repair the project source."
        }
        val text = projectRepository.withBaselineUpdateLock(boundProjectFilePath) {
            source.file.readText()
        }
        savedSourceTexts[fileId] = text
        sourceBuffers[fileId] = text
        return text
    }

    /**
     * Optional navigation must tolerate the room-only startup index and missing source files.
     * Return no snapshot until every dependency is available; never substitute baseline or empty
     * text. Semantic reads/writes still use [projectSourceText] and report unavailable source.
     */
    fun projectSourceTextsForNavigation(fileIds: Collection<String>): Map<String, String>? {
        val active = projectWorkspace ?: return null
        if (fileIds.any { it !in sourceBuffers && active.referenceWorkspace.index.file(it) == null }) return null
        return try {
            fileIds.associateWith(::projectSourceText)
        } catch (_: java.io.IOException) {
            null
        }
    }

    fun replaceProjectSource(fileId: String, expected: String, replacement: String,
                             beforeUndo: (() -> Unit)? = null, beforeRedo: (() -> Unit)? = null) {
        check(!busy && projectWorkspace != null) { "ASM workspace is busy or unavailable." }
        check(projectSourceText(fileId) == expected) { "Source changed while this edit was open. Reopen the object and try again." }
        if (expected == replacement) return
        recordSourceUndo(fileId, expected, replacement, AsmSourceTextChange.between(expected, replacement)
            ?.copy(beforeUndo = beforeUndo, beforeRedo = beforeRedo))
        applySourceEditText(fileId, replacement)
        editSessionSerial++
    }

    /** Validate every document first, then publish one history boundary across all of them.
     * Unchanged participants still receive an anchor: metadata-only edits must remain undoable,
     * and a newer edit in any participating bank must block a partial compound undo. */
    fun replaceProjectSources(
        replacements: Map<String, Pair<String, String>>,
        beforeUndo: (() -> Unit)? = null,
        beforeRedo: (() -> Unit)? = null,
        afterUndo: (() -> Unit)? = null,
        afterRedo: (() -> Unit)? = null,
        beforeApply: (() -> Unit)? = null,
        afterApply: (() -> Unit)? = null,
    ) {
        check(!busy && projectWorkspace != null) { "ASM workspace is busy or unavailable." }
        require(replacements.isNotEmpty()) { "A source transaction needs at least one document." }
        for ((id, texts) in replacements) check(projectSourceText(id) == texts.first) {
            "Source $id changed while this edit was open. Try again."
        }
        val group = AsmSourceEditGroup(replacements.keys.toSet(), beforeUndo, beforeRedo, afterUndo, afterRedo)
        beforeApply?.invoke()
        for ((id, texts) in replacements) {
            val change = (AsmSourceTextChange.between(texts.first, texts.second) ?: AsmSourceTextChange(0, "", ""))
                .copy(group = group)
            recordSourceUndo(id, texts.first, texts.second, change)
            applySourceEditText(id, texts.second)
        }
        editSessionSerial++
        notifySourceTransition(afterApply)
    }

    fun planSymbolRename(
        fileId: String,
        lineIndex: Int,
        token: String,
        requestedName: String,
    ): AsmSymbolRenamePlan? {
        error = null
        return try {
            check(workspaceKind == AsmWorkspaceKind.PROJECT && projectWorkspace?.indexComplete == true) {
                "Rename Symbol is available after the complete Project ASM index finishes loading."
            }
            val index = checkNotNull(workspace?.index) { "Project ASM index is unavailable." }
            val texts = sourceTexts().associate { it.fileId to it.text }
            AsmSymbolRefactor.planRename(index, texts, fileId, lineIndex, token, requestedName)
        } catch (problem: Exception) {
            error = problem.message ?: "This symbol cannot be renamed safely."
            null
        }
    }

    /** Re-plan against the live index immediately before committing. This turns
     * the preview into a guarded transaction rather than stale UI authority. */
    fun applySymbolRename(plan: AsmSymbolRenamePlan): Boolean {
        val current = planSymbolRename(
            plan.targetFileId,
            plan.targetLineIndex,
            plan.oldName,
            plan.newName,
        ) ?: return false
        if (current.replacements != plan.replacements || current.occurrences != plan.occurrences) {
            error = "ASM references changed after the rename preview. Review the rename again; no source was changed."
            return false
        }
        return try {
            replaceProjectSources(plan.replacements)
            openSource(plan.targetFileId, plan.targetLineIndex, addToHistory = false)
            true
        } catch (problem: Exception) {
            error = problem.message ?: "Could not apply the symbol rename safely."
            false
        }
    }

    fun updateSourceEditText(fileId: String, text: String, undoChange: AsmSourceTextChange? = null): Boolean {
        roomSourceInspection?.let { inspection ->
            val automatic = inspection.automaticEdit ?: return false
            if (fileId == inspection.fileId) {
                if (text == automatic.text) return true
                return automatic.apply(text)
            }
        }
        if (workspaceKind != AsmWorkspaceKind.PROJECT || workspace?.index?.file(fileId) == null) return false
        val previous = sourceEditText(fileId)
        if (previous == text) return true
        sourceEditorInterceptor?.invoke(fileId, previous, text)?.let { return it }
        if (sourceBuffers[fileId] == null) {
            val diskText = workspace?.index?.file(fileId)?.file?.readText() ?: return false
            savedSourceTexts[fileId] = diskText
        }
        recordSourceUndo(fileId, previous, text, undoChange)
        applySourceEditText(fileId, text)
        return true
    }

    private fun applySourceEditText(fileId: String, text: String) {
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

    private fun recordSourceUndo(
        fileId: String,
        previous: String,
        updated: String,
        preferredChange: AsmSourceTextChange? = null,
    ) {
        val change = preferredChange ?: AsmSourceTextChange.between(previous, updated) ?: return
        val undo = sourceUndoStacks.getOrPut(fileId, ::mutableListOf)
        undo += change
        if (undo.size > MAX_SOURCE_UNDO_STEPS) undo.removeAt(0)
        sourceRedoStacks.remove(fileId)
    }

    private fun inspectionBlocksSource(fileId: String?): Boolean = roomSourceInspection?.let {
        it.automaticEdit == null || it.fileId == fileId
    } == true

    fun canUndoSource(fileId: String): Boolean = !inspectionBlocksSource(fileId) && sourceUndoStacks[fileId]?.isNotEmpty() == true

    fun canRedoSource(fileId: String): Boolean = if (inspectionBlocksSource(fileId)) {
        roomSourceInspection?.automaticEdit?.redoHead?.let { it === sourceRedoHead(fileId) } == true
    } else sourceRedoStacks[fileId]?.isNotEmpty() == true

    /** Text equality is insufficient: an edit can be changed away and back in a newer undo step. */
    internal fun sourceUndoHead(fileId: String): AsmSourceTextChange? = sourceUndoStacks[fileId]?.lastOrNull()
    internal fun sourceRedoHead(fileId: String): AsmSourceTextChange? = sourceRedoStacks[fileId]?.lastOrNull()

    fun undoSourceEdit(fileId: String? = selectedFileId, fromRoomEditor: Boolean = false): Boolean {
        if (inspectionBlocksSource(fileId) && !fromRoomEditor) return false
        val selectedId = fileId ?: return false
        val undo = sourceUndoStacks[selectedId] ?: return false
        val change = undo.lastOrNull() ?: return false
        change.group?.let { return moveSourceGroup(it, undoing = true) }
        val current = sourceEditText(selectedId)
        val restored = change.undo(current) ?: return false
        try { change.beforeUndo?.invoke() } catch (problem: Exception) {
            error = problem.message ?: "Cannot undo this source/model transition safely."
            return false
        }
        undo.removeAt(undo.lastIndex)
        sourceRedoStacks.getOrPut(selectedId, ::mutableListOf).add(change)
        applySourceEditText(selectedId, restored)
        editSessionSerial++
        return true
    }

    fun redoSourceEdit(fileId: String? = selectedFileId, fromRoomEditor: Boolean = false): Boolean {
        if (inspectionBlocksSource(fileId) && !fromRoomEditor) {
            val draft = roomSourceInspection?.automaticEdit ?: return false
            if (fileId != roomSourceInspection?.fileId || draft.redoHead == null || sourceRedoHead(fileId ?: return false) !== draft.redoHead) return false
        }
        val selectedId = fileId ?: return false
        val redo = sourceRedoStacks[selectedId] ?: return false
        val change = redo.lastOrNull() ?: return false
        change.group?.let { return moveSourceGroup(it, undoing = false) }
        val current = sourceEditText(selectedId)
        val restored = change.redo(current) ?: return false
        try { change.beforeRedo?.invoke() } catch (problem: Exception) {
            error = problem.message ?: "Cannot redo this source/model transition safely."
            return false
        }
        redo.removeAt(redo.lastIndex)
        sourceUndoStacks.getOrPut(selectedId, ::mutableListOf).add(change)
        applySourceEditText(selectedId, restored)
        editSessionSerial++
        return true
    }

    private fun moveSourceGroup(group: AsmSourceEditGroup, undoing: Boolean): Boolean {
        val from = if (undoing) sourceUndoStacks else sourceRedoStacks
        val to = if (undoing) sourceRedoStacks else sourceUndoStacks
        val staged = linkedMapOf<String, Pair<AsmSourceTextChange, String>>()
        try {
            for (id in group.fileIds) {
                val change = requireNotNull(from[id]?.lastOrNull()) { "Source history is unavailable in $id; no part of this transaction was changed." }
                require(change.group === group) {
                    "Source history changed in $id; ${if (undoing) "undo" else "redo"} newer edits there first. No part of this transaction was changed."
                }
                val text = sourceEditText(id)
                val restored = if (undoing) change.undo(text) else change.redo(text)
                requireNotNull(restored) { "Source $id changed; cannot restore this transaction safely." }
                staged[id] = change to restored
            }
            (if (undoing) group.beforeUndo else group.beforeRedo)?.invoke()
        } catch (problem: Exception) {
            error = problem.message ?: "Cannot restore this source/model transaction safely."
            return false
        }
        for ((id, entry) in staged) {
            from.getValue(id).removeAt(from.getValue(id).lastIndex)
            to.getOrPut(id, ::mutableListOf).add(entry.first)
            applySourceEditText(id, entry.second)
        }
        editSessionSerial++
        // Preview notifications cannot roll back an already committed source/model transaction.
        notifySourceTransition(if (undoing) group.afterUndo else group.afterRedo)
        return true
    }

    private fun notifySourceTransition(notification: (() -> Unit)?) {
        try { notification?.invoke() } catch (problem: Exception) {
            error = "Source changed, but preview refresh failed: ${problem.message}"
        }
    }

    fun publishLiveDiagnostics(
        fileId: String,
        revision: Long,
        diagnostics: List<AsmBuildDiagnostic>,
    ) {
        if (
            workspaceKind != AsmWorkspaceKind.PROJECT ||
            selectedFileId != fileId ||
            sourceBufferRevision != revision
        ) return
        liveDiagnosticFileId = fileId
        liveDiagnostics = diagnostics
    }

    fun sourceTexts(): List<AsmSourceText> = workspace?.index?.files.orEmpty().map { source ->
        AsmSourceText(source.id, source.displayName, sourceEditText(source.id))
    }

    /** Immutable analysis input without synchronously rereading every source
     * file on each editor keystroke. Indexed lines are the saved fallback;
     * already-open buffers and the active draft remain authoritative. */
    fun sourceAnalysisTexts(activeFileId: String, activeText: String): Map<String, String> =
        workspace?.index?.files.orEmpty().associate { source ->
            source.id to when (source.id) {
                activeFileId -> activeText
                else -> sourceBuffers[source.id] ?: source.lines.joinToString("\n")
            }
        }

    /** Sidebar Find follows what the editor is currently showing. A room-source
     * overlay is deliberately excluded from Save, Build and Replace until the
     * first edit adopts it, but it must not be invisible to sidebar Find. */
    fun searchSourceTexts(): List<AsmSourceText> {
        val preview = roomSourceInspection?.automaticEdit
        val previewFileId = roomSourceInspection?.fileId
        return sourceTexts().map { source ->
            if (preview != null && source.fileId == previewFileId) {
                source.copy(
                    displayName = "${source.displayName} · room source preview",
                    text = preview.text,
                )
            } else source
        }
    }

    /** Include unsaved text and disabled modules, independent of the visible browser scope. */
    fun projectSourceTextsExcept(fileId: String): Sequence<String> =
        projectSourceReadersExcept(fileId).asSequence().map { it() }

    fun projectSourceReadersExcept(fileId: String): List<() -> String> = projectSourceReadersExcept(setOf(fileId))

    fun projectSourceReadersExcept(fileIds: Set<String>): List<() -> String> {
        val project = projectWorkspace ?: return emptyList()
        val originals = java.io.File(project.originalDirectory, "src")
        val sources = originals.walkTopDown().filter { it.isFile && it.extension.equals("asm", true) }
            .associate { it.relativeTo(originals).invariantSeparatorsPath to it }.toMutableMap()
        project.projectModuleFileIds.forEach { id ->
            sources[id] = java.io.File(project.root, "modules/${id.removePrefix(ASM_PROJECT_MODULE_PREFIX)}")
        }
        return sources.filterKeys { it !in fileIds }.map { (id, original) ->
            // This cannot depend on the IDE index: it may be partial or still rebuilding.
            // Unchanged buffers also cannot hide a newer external authored edit.
            val buffered = sourceBuffers[id]?.takeIf { id in dirtySourceFileIds }
            val override = java.io.File(project.root, "overrides/src/$id")
            val reader: () -> String = { buffered ?: (if (override.isFile) override else original).readText() }
            reader
        }
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
                recordSourceUndo(fileId, current, updated)
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

    /** A reciprocal asset transaction may durably replace one declaration
     * without going through the text editor. Refresh any already-open clean
     * buffer so the ASM and Visual Editor views cannot diverge. */
    fun refreshPersistedSourceFiles(fileIds: Set<String>): Boolean {
        if (fileIds.isEmpty()) return true
        if (fileIds.any(dirtySourceFileIds::containsKey)) return false
        var changed = false
        fileIds.forEach { fileId ->
            val diskText = workspace?.index?.file(fileId)?.file?.takeIf(File::isFile)?.readText()
                ?: return@forEach
            if (sourceBuffers[fileId] != null && sourceBuffers[fileId] != diskText) {
                sourceBuffers[fileId] = diskText
                changed = true
            }
            if (savedSourceTexts[fileId] != diskText) {
                savedSourceTexts[fileId] = diskText
                changed = true
            }
            dirtySourceFileIds.remove(fileId)
        }
        if (changed) {
            buildReport = null
            sourceBufferRevision++
            editSessionSerial++
        }
        return true
    }

    fun discardSourceBuffer(fileId: String? = selectedFileId) {
        val selectedId = fileId ?: return
        val source = workspace?.index?.file(selectedId) ?: return
        val diskText = source.file.readText()
        val current = sourceEditText(selectedId)
        if (current != diskText) recordSourceUndo(selectedId, current, diskText)
        sourceBuffers[selectedId] = diskText
        savedSourceTexts[selectedId] = diskText
        dirtySourceFileIds.remove(selectedId)
        editSessionSerial++
        sourceBufferRevision++
    }

    suspend fun saveSource(fileId: String? = selectedFileId): Boolean {
        if (projectWorkspace == null || boundProjectFilePath.isBlank()) return false
        val projectPath = boundProjectFilePath
        val session = sourceIndexingGeneration
        val selectedId = fileId ?: return false
        val text = sourceBuffers[selectedId] ?: projectSourceText(selectedId)
        val expectedText = savedSourceTexts[selectedId]
        if (busy) return false
        val revision = sourceBufferRevision
        val normalize = sourceSaveParticipant?.prepare(selectedId, text) ?: { text }
        val affectsBuild = !isProjectModule(selectedId) || isProjectModuleEnabled(selectedId)
        busy = true
        error = null
        return try {
            progress = "Saving $selectedId…"
            val normalized = withContext(Dispatchers.Default) { normalize() }
            if (projectPath != boundProjectFilePath || session != sourceIndexingGeneration) return false
            // New edits may add external helper consumers. Defer cleanup rather than using stale metadata.
            val savedText = if (revision == sourceBufferRevision) normalized else text
            if (savedText == text && !hasUnsavedSourceChanges(selectedId)) return true
            val result = withContext(Dispatchers.IO) {
                projectRepository.persistSource(projectPath, selectedId, savedText, expectedText)
            }
            if (projectPath != boundProjectFilePath || session != sourceIndexingGeneration) return false
            projectWorkspace = projectWorkspace?.let { previous ->
                previous.copy(modifiedFileIds = previous.modifiedFileIds.toMutableSet().also {
                    if (result.modified && affectsBuild) it.add(selectedId) else it.remove(selectedId)
                })
            }
            if (affectsBuild) buildReport = null
            if (sourceBuffers[selectedId] == text && savedText != text) {
                recordSourceUndo(selectedId, text, savedText)
                applySourceEditText(selectedId, savedText)
                val lines = savedText.lines()
                sourcePanes = sourcePanes.map { pane ->
                    if (pane.fileId != selectedId) pane else {
                        val row = pane.lineIndex.coerceIn(0, lines.lastIndex)
                        pane.copy(lineIndex = row, columnIndex = pane.columnIndex.coerceIn(0, lines[row].length))
                    }
                }
                sourcePane(activeSourcePaneId)?.let { pane ->
                    selectedLineIndexValue = pane.lineIndex
                    selectedColumnIndexValue = pane.columnIndex
                }
                editSessionSerial++
            }
            savedSourceTexts[selectedId] = savedText
            if (sourceBuffers[selectedId] == savedText) dirtySourceFileIds.remove(selectedId)
            else dirtySourceFileIds[selectedId] = Unit
            sourceSaveParticipant?.afterSaved(selectedId, savedText)?.let { publish ->
                CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) { runCatching { publish() } }
            }
            sourceBufferRevision++
            sourceIndexPendingFiles += selectedId
            scheduleSourceIndexing()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            error = problem.message ?: "Could not save $selectedId"
            false
        } finally {
            busy = false
            progress = null
        }
    }

    suspend fun saveAllSources(): Boolean {
        val pending = (unsavedSourceFileIds + sourceSaveParticipant?.sourceFileIds.orEmpty()).toList()
        for (fileId in pending) {
            if (!saveSource(fileId)) return false
        }
        return true
    }

    /** Saving returns after persistence. Index jobs coalesce and may not replace newer buffers/reports. */
    private suspend fun scheduleSourceIndexing(startup: Boolean = false) {
        sourceIndexingJob?.cancel()
        val generation = ++sourceIndexingGeneration
        val projectPath = boundProjectFilePath
        sourceIndexingInProgress = true
        sourceIndexingStatus = "Indexing ASM…"
        sourceIndexFailure = null
        if (error?.startsWith("ASM index update failed:") == true) error = null
        sourceIndexingJob = CoroutineScope(currentCoroutineContext()).launch {
            try {
                delay(75) // A multi-bank save needs one index rebuild, not one per file.
                val indexed = withContext(Dispatchers.IO) { sourceIndexLoader(projectPath) }
                    ?: error("Saved ASM could not be indexed")
                if (generation != sourceIndexingGeneration || projectPath != boundProjectFilePath) return@launch
                projectWorkspace = indexed
                if (workspaceKind == AsmWorkspaceKind.PROJECT) workspace = indexed.referenceWorkspace
                val files = if (startup) indexed.referenceWorkspace.index.files.map { it.id } else sourceIndexPendingFiles.toList()
                val revision = sourceBufferRevision
                sourceIndexingStatus = "Checking ASM…"
                val diagnostics = withContext(Dispatchers.Default) {
                    val sourceTexts = indexed.referenceWorkspace.index.files.associate { source ->
                        source.id to source.file.readText()
                    }
                    val context = AsmEditorAnalysisContext(
                        indexed.referenceWorkspace.index,
                        sourceTexts,
                        indexed.disabledProjectModuleFileIds,
                    )
                    files.associateWith { fileId ->
                        val source = indexed.referenceWorkspace.index.file(fileId)
                        if (source == null || fileId in indexed.disabledProjectModuleFileIds) emptyList()
                        else AsmSourceLinter.lintText(
                            fileId,
                            sourceTexts.getValue(fileId),
                            indexed.referenceWorkspace.index.macros,
                            context,
                        )
                    }
                }
                if (generation != sourceIndexingGeneration || projectPath != boundProjectFilePath) return@launch
                savedSourceDiagnostics.putAll(diagnostics)
                if (revision == sourceBufferRevision && (buildReport == null || buildReport?.validationOnly == true)) {
                    buildReport = AsmSourceLinter.report(savedSourceDiagnostics.filterKeys {
                        it !in dirtySourceFileIds && it !in indexed.disabledProjectModuleFileIds
                    }.values.flatten())
                }
                sourceIndexPendingFiles.removeAll(files.toSet())
                // Recompute completion/navigation against the new index without moving any caret.
                sourceBufferRevision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                if (generation == sourceIndexingGeneration && projectPath == boundProjectFilePath) {
                    sourceIndexFailure = problem.message ?: "Could not update the source index"
                    error = "ASM index update failed: $sourceIndexFailure"
                }
            } finally {
                if (generation == sourceIndexingGeneration) {
                    sourceIndexingInProgress = false
                    sourceIndexingStatus = ""
                }
            }
        }
    }

    suspend fun awaitSourceIndexing() { sourceIndexingJob?.join() }

    suspend fun rebuildSourceIndex() {
        if (projectWorkspace != null && boundProjectEnabled) scheduleSourceIndexing(startup = true)
    }

    private fun cancelSourceIndexing() {
        sourceIndexingGeneration++
        sourceIndexingJob?.cancel()
        sourceIndexingJob = null
        sourceIndexingInProgress = false
        sourceIndexingStatus = ""
        sourceIndexFailure = null
        sourceIndexPendingFiles.clear()
        savedSourceDiagnostics.clear()
    }

    suspend fun restoreOriginalSource(fileId: String? = selectedFileId): Boolean {
        if (workspaceKind != AsmWorkspaceKind.PROJECT || boundProjectFilePath.isBlank()) return false
        val selectedId = fileId ?: return false
        if (isProjectModule(selectedId)) {
            error = "Project modules have no immutable original; delete the module instead"
            return false
        }
        if (busy) return false
        cancelSourceIndexing()
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
            sourceUndoStacks.remove(selectedId)
            sourceRedoStacks.remove(selectedId)
            activeReferenceSymbol = null
            activeReferenceMacro = null
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

    suspend fun createProjectModule(name: String, description: String = ""): Boolean {
        if (workspaceKind != AsmWorkspaceKind.PROJECT || boundProjectFilePath.isBlank() || busy) return false
        busy = true
        error = null
        return try {
            progress = "Creating project module…"
            val (result, fileId) = withContext(Dispatchers.IO) {
                projectRepository.createModule(boundProjectFilePath, name, description)
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

    fun checkedHookCreationAvailable(fileId: String): Boolean =
        workspaceKind == AsmWorkspaceKind.PROJECT && !isProjectModule(fileId) &&
            referenceAssetsMatchCurrentRom && !sourceBuildRequired &&
            buildReport?.succeeded == true && romSpacePlan != null

    /** Resolves the clicked instruction against the exact successful build and
     * the allocator's current verified fragments. Any ambiguity is surfaced in
     * the normal ASM error strip instead of generating optimistic source. */
    fun prepareCheckedHookCandidate(
        fileId: String,
        lineIndex: Int,
        romParser: RomParser,
    ): AsmCheckedHookCandidate? {
        if (!checkedHookCreationAvailable(fileId)) {
            error = when {
                workspaceKind != AsmWorkspaceKind.PROJECT || isProjectModule(fileId) ->
                    "Create checked hooks from native source in the project ASM workspace."
                !referenceAssetsMatchCurrentRom ->
                    "The loaded ROM does not match this ASM baseline. Reopen the project's base ROM before creating a hook."
                sourceBuildRequired || buildReport?.succeeded != true ->
                    "Build the current project before creating a checked hook."
                romSpacePlan == null ->
                    "Refresh the Space Planner before creating a checked hook."
                else -> "This source is not ready for checked-hook creation."
            }
            return null
        }
        return try {
            error = null
            AsmCheckedHookPlanner.prepare(
                fileId = fileId,
                lineIndex = lineIndex,
                text = sourceEditText(fileId),
                symbols = checkNotNull(buildReport).symbols,
                romParser = romParser,
                spacePlan = checkNotNull(romSpacePlan),
            )
        } catch (problem: Exception) {
            error = problem.message ?: "This instruction cannot be turned into a checked hook."
            null
        }
    }

    suspend fun createCheckedHookModule(candidate: AsmCheckedHookCandidate, name: String): Boolean {
        if (workspaceKind != AsmWorkspaceKind.PROJECT || boundProjectFilePath.isBlank() || busy) return false
        busy = true
        error = null
        return try {
            progress = "Creating checked hook…"
            val source = candidate.renderModule(name)
            val (result, fileId) = withContext(Dispatchers.IO) {
                projectRepository.createModule(
                    projectFilePath = boundProjectFilePath,
                    requestedName = name,
                    description = "Checked hook at ${formatSnesAddress(candidate.hookAddress)}",
                    initialSource = source,
                )
            }
            adoptProjectWorkspace(result)
            buildReport = null
            romPreview = null
            romSpacePlan = null
            openSource(fileId, addToHistory = false)
            true
        } catch (problem: Exception) {
            error = problem.message ?: "Could not create the checked hook"
            false
        } finally {
            busy = false
            progress = null
        }
    }

    /** Install only on first Enable; subsequent toggles edit the existing document, never regenerate it. */
    internal fun ensureFastDoorsModule(enabled: Boolean) {
        check(!busy && projectWorkspace != null && boundProjectFilePath.isNotBlank()) { "Wait for the project ASM workspace." }
        if (AsmFastDoorsModule.FILE in projectModuleFileIds) return
        val (result, _) = projectRepository.createModule(boundProjectFilePath,
            AsmFastDoorsModule.FILE.removePrefix(ASM_PROJECT_MODULE_PREFIX),
            initialSource = AsmFastDoorsModule.template(enabled, AsmSourceBindings.load(checkNotNull(projectWorkspace).originalDirectory)))
        adoptProjectWorkspace(result)
        buildReport = null
    }

    suspend fun renameProjectModule(fileId: String, name: String): Boolean =
        updateProjectModuleSettings(
            fileId = fileId,
            name = name,
            description = workspace?.index?.file(fileId)?.description.orEmpty(),
        )

    suspend fun updateProjectModuleSettings(
        fileId: String,
        name: String,
        description: String,
    ): Boolean {
        if (!isProjectModule(fileId) || boundProjectFilePath.isBlank() || busy) return false
        if (fileId == AsmFastDoorsModule.FILE && AsmFastDoorsModule.hasGate(sourceEditText(fileId)) && name.removeSuffix(".asm") != "fast_doors") {
            error = "Fast Doors uses a managed module name. Keep fast_doors.asm so its patch toggle and checked hooks remain connected."
            return false
        }
        if (hasUnsavedSourceChanges(fileId)) {
            error = "Save or revert this module before changing its settings"
            return false
        }
        busy = true
        error = null
        return try {
            progress = "Updating project module…"
            val (result, newFileId) = withContext(Dispatchers.IO) {
                projectRepository.updateModuleSettings(boundProjectFilePath, fileId, name, description)
            }
            sourceBuffers.remove(fileId)
            savedSourceTexts.remove(fileId)
            dirtySourceFileIds.remove(fileId)
            val updatedText = result.referenceWorkspace.index.file(newFileId)?.file?.readText().orEmpty()
            sourceBuffers[newFileId] = updatedText
            savedSourceTexts[newFileId] = updatedText
            adoptProjectWorkspace(result)
            buildReport = null
            openSource(newFileId, addToHistory = false)
            true
        } catch (problem: Exception) {
            error = problem.message ?: "Could not update the project module"
            false
        } finally {
            busy = false
            progress = null
        }
    }

    suspend fun deleteProjectModule(fileId: String): Boolean {
        if (!isProjectModule(fileId) || boundProjectFilePath.isBlank() || busy) return false
        if (fileId == AsmFastDoorsModule.FILE && AsmFastDoorsModule.hasGate(sourceEditText(fileId))) {
            error = "Fast Doors retains its authored module to preserve custom code. Turn it off through its patch toggle instead."
            return false
        }
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
        if (fileId == AsmFastDoorsModule.FILE && AsmFastDoorsModule.hasGate(sourceEditText(fileId)) && fileId !in disabledProjectModuleFileIds) {
            return try {
                error = null
                requireNotNull(codePatchModuleToggle) { "Open Patches > Fast Doors after source indexing to toggle this module." }(fileId, enabled)
                true
            } catch (problem: Exception) {
                error = problem.message
                false
            }
        }
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
        roomSourceInspection = null
        browserMode = AsmBrowserMode.ASSETS
        selectedAssetPath = asset.range.path
        selectedAssetCategory = asset.category
        selectedInstruction = null
        selectedInstructionToken = null
        selectedLibraryDirective = null
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
            activeReferenceMacro = null
        }
    }

    /** Generated entry symbols navigate to their current authored body, even before Save/Build. */
    private fun managedReference(token: String): Pair<String, Int>? {
        if (workspaceKind != AsmWorkspaceKind.PROJECT || projectWorkspace == null) return null
        val door = Regex("(SmeditDoors_(?:[0-9A-F]{4}|Project_[0-9A-F]+)_Door_[0-9]+)_Entry").matchEntire(token)?.groupValues?.get(1)
        val macro = door ?: managedBank8FMacroName(token) ?: return null
        val file = if (door == null) PLM_SOURCE_FILE else DOOR_SOURCE_FILE
        val text = projectSourceText(file)
        val match = Regex("(?m)^[ \\t]*macro[ \\t]+${Regex.escape(macro)}\\(\\)").findAll(text).singleOrNull() ?: return null
        return file to text.take(match.range.first).count { it == '\n' }
    }

    internal fun managedCodeLine(token: String): Int? = managedReference(token)?.second

    internal fun openManagedCodeReference(token: String): Boolean {
        val (file, line) = managedReference(token) ?: return false
        openSource(file, line)
        return true
    }

    fun openLabel(fileId: String, lineIndex: Int, token: String) {
        workspace?.index?.resolveLabel(fileId, lineIndex, token)
            ?.let { label ->
                openSource(label.fileId, label.lineIndex)
                activeReferenceSymbol = label.symbolId
                activeReferenceMacro = null
            }
    }

    fun showReferences(fileId: String, lineIndex: Int, token: String) {
        val index = workspace?.index ?: return
        val label = index.definition(fileId, lineIndex, token) ?: return
        selectedFileId = fileId
        selectedLineIndex = lineIndex
        selectedColumnIndex = 0
        activeReferenceSymbol = label.symbolId
        activeReferenceMacro = null
        navigationSerial++
    }

    /** Shows a symbol's usage list in-place. Unlike definition navigation,
     * this deliberately keeps the current source/caret visible. */
    fun findLabelUsages(fileId: String, lineIndex: Int, token: String): Boolean {
        val index = workspace?.index ?: return false
        val label = index.definition(fileId, lineIndex, token)
            ?: index.resolveLabel(fileId, lineIndex, token)
            ?: return false
        selectedFileId = fileId
        selectedLineIndex = lineIndex.coerceAtLeast(0)
        activeReferenceSymbol = label.symbolId
        activeReferenceMacro = null
        referenceExpansionTarget = AsmReferenceExpansionTarget.Label(label.symbolId)
        referenceExpansionSerial++
        return true
    }

    fun openMacro(macro: AsmMacro) {
        openSource(macro.fileId, macro.lineIndex)
        activeReferenceSymbol = null
        activeReferenceMacro = macro.id
    }

    fun showMacroReferences(macro: AsmMacro) {
        selectedFileId = macro.fileId
        selectedLineIndex = macro.lineIndex
        selectedColumnIndex = 0
        activeReferenceSymbol = null
        activeReferenceMacro = macro.id
        navigationSerial++
    }

    /** Shows a macro's usage list in-place and expands it immediately. */
    fun findMacroUsages(macro: AsmMacro, fileId: String, lineIndex: Int): Boolean {
        selectedFileId = fileId
        selectedLineIndex = lineIndex.coerceAtLeast(0)
        activeReferenceSymbol = null
        activeReferenceMacro = macro.id
        referenceExpansionTarget = AsmReferenceExpansionTarget.Macro(macro.id)
        referenceExpansionSerial++
        return true
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
        activeReferenceMacro = null
        referenceExpansionTarget = null
    }

    fun openReference(label: AsmLabel, usage: AsmLabelUsage) {
        activeReferenceSymbol = label.symbolId
        activeReferenceMacro = null
        openSource(
            fileId = usage.fileId,
            lineIndex = usage.lineIndex,
            preserveReferences = true,
        )
    }

    fun openMacroReference(macro: AsmMacro, usage: AsmMacroUsage) {
        activeReferenceSymbol = null
        activeReferenceMacro = macro.id
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
        selectedInstruction = normalized
        selectedInstructionToken = token
        selectedLibraryDirective = null
        selectedLibraryPageId = AsmLibrary.instructionPageId(normalized)
        selectedAssetPath = null
        navigationSerial++
        forwardStack.clear()
    }

    fun openLibraryGuide(pageId: String, addToHistory: Boolean = true) {
        val guide = AsmLibrary.guide(pageId) ?: return
        if (addToHistory) rememberCurrentLocation()
        roomSourceInspection = null
        browserMode = AsmBrowserMode.LIBRARY
        selectedInstruction = null
        selectedInstructionToken = null
        selectedLibraryDirective = null
        selectedLibraryPageId = guide.id
        selectedAssetPath = null
        navigationSerial++
        if (addToHistory) forwardStack.clear()
    }

    fun openLibraryInstruction(mnemonic: String, addToHistory: Boolean = true) {
        val token = mnemonic.uppercase()
        val instruction = AsmInstructionReference.find(token.substringBefore('.')) ?: return
        if (addToHistory) rememberCurrentLocation()
        roomSourceInspection = null
        browserMode = AsmBrowserMode.LIBRARY
        selectedInstruction = instruction.mnemonic
        selectedInstructionToken = token
        selectedLibraryDirective = null
        selectedLibraryPageId = AsmLibrary.instructionPageId(instruction.mnemonic)
        selectedAssetPath = null
        navigationSerial++
        if (addToHistory) forwardStack.clear()
    }

    fun openLibraryDirective(directive: String, addToHistory: Boolean = true) {
        val info = AsmLibrary.directive(directive) ?: return
        if (addToHistory) rememberCurrentLocation()
        roomSourceInspection = null
        browserMode = AsmBrowserMode.LIBRARY
        selectedInstruction = null
        selectedInstructionToken = null
        selectedLibraryDirective = info.name
        selectedLibraryPageId = "asar-directives"
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
        referenceLoadGeneration++
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
        val path = boundProjectFilePath
        val generation = projectBindingGeneration
        val progressive = progressiveProjectLoading
        val result = try {
            withContext(Dispatchers.IO) {
                if (progressive) projectRepository.loadRoomWorkspace(path) else projectRepository.load(path)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) {
            if (path == boundProjectFilePath && generation == projectBindingGeneration) error = "Cannot open project source: ${problem.message}"
            return
        }
        if (path != boundProjectFilePath || generation != projectBindingGeneration || !boundProjectEnabled) return
        if (result == null) {
            projectWorkspace = null
            if (workspaceKind == AsmWorkspaceKind.PROJECT) showReferenceWorkspace()
            error = "This project has ASM enabled, but its sidecar workspace is missing or incomplete. Recreate it from the pinned reference."
        } else {
            projectWorkspace = result
            activateProjectWorkspace(result)
            if (progressive) scheduleSourceIndexing(startup = true)
        }
    }

    private fun activateProjectWorkspace(project: AsmProjectWorkspace) {
        workspace = project.referenceWorkspace
        workspaceKind = AsmWorkspaceKind.PROJECT
        ensureSelection()
        navigationSerial++
    }

    private fun adoptProjectWorkspace(project: AsmProjectWorkspace) {
        cancelSourceIndexing()
        projectWorkspace = project
        workspace = project.referenceWorkspace
        workspaceKind = AsmWorkspaceKind.PROJECT
        sourceBufferRevision++
        editSessionSerial++
        navigationSerial++
    }

    private fun clearEditSessions() {
        codePatchModuleToggle = null
        roomSourceInspection = null
        cancelSourceIndexing()
        sourceSaveParticipant = null
        sourceEditorInterceptor = null
        sourceEditorLinks = null
        sourceBuffers.clear()
        savedSourceTexts.clear()
        dirtySourceFileIds.clear()
        sourceUndoStacks.clear()
        sourceRedoStacks.clear()
        liveDiagnosticFileId = null
        liveDiagnostics = emptyList()
        editSessionSerial++
        sourceBufferRevision++
    }

    private fun ensureSelection() {
        val index = workspace?.index ?: return
        val validPanes = sourcePanes.mapNotNull { pane ->
            pane.fileId?.takeIf { index.file(it) != null }?.let { pane }
        }
        if (validPanes.isNotEmpty() && validPanes.size != sourcePanes.size) {
            sourcePanes = validPanes.take(MAX_SOURCE_PANES)
            if (sourcePane(activeSourcePaneId) == null) activeSourcePaneId = sourcePanes.first().id
            val active = sourcePane(activeSourcePaneId) ?: sourcePanes.first()
            selectedFileIdValue = active.fileId
            selectedLineIndexValue = active.lineIndex
            selectedColumnIndexValue = active.columnIndex
            navigationSerialValue = active.navigationSerial
            activeReferenceSymbolValue = active.referenceSymbol
            activeReferenceMacroValue = active.referenceMacro
        }
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

    internal fun locationSnapshot(): AsmWorkspaceLocation? = roomSourceInspection?.takeIf { it.fileId == selectedFileId }
        ?.let(AsmWorkspaceLocation::RoomSourceInspection) ?: when (browserMode) {
        AsmBrowserMode.SOURCE -> selectedFileId?.let {
            AsmWorkspaceLocation.Source(
                fileId = it,
                lineIndex = selectedLineIndex,
                referenceSymbol = activeReferenceSymbol,
                referenceMacro = activeReferenceMacro,
                workspaceKind = workspaceKind,
                columnIndex = selectedColumnIndex,
            )
        }
        AsmBrowserMode.ASSETS -> selectedAssetPath?.let { AsmWorkspaceLocation.Asset(it, workspaceKind) }
        AsmBrowserMode.LIBRARY -> AsmWorkspaceLocation.Library(
            selectedLibraryPageId, selectedInstructionToken, selectedLibraryDirective,
        )
        AsmBrowserMode.ROM -> AsmWorkspaceLocation.Rom(romPreviewView)
        AsmBrowserMode.GENERATED_ROOMS -> AsmWorkspaceLocation.GeneratedRooms(generatedRoomLineIndex, showRoomBuildWrites)
        AsmBrowserMode.PLM_PLACEMENT -> plmPlacementSnapshot?.let(AsmWorkspaceLocation::PlmPlacement)
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
                activeReferenceMacro = location.referenceMacro
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
                else if (location.directiveToken != null) openLibraryDirective(location.directiveToken, addToHistory = false)
                else openLibraryGuide(location.pageId, addToHistory = false)
            }
            is AsmWorkspaceLocation.Rom -> selectRomPreviewView(location.view, addToHistory = false)
            is AsmWorkspaceLocation.GeneratedRooms -> openGeneratedRooms(location.lineIndex, addToHistory = false, buildWrites = location.buildWrites)
            is AsmWorkspaceLocation.PlmPlacement -> openPlmPlacement(location.snapshot, addToHistory = false)
            is AsmWorkspaceLocation.RoomSourceInspection -> location.snapshot.automaticEdit?.reopen?.invoke()
                ?: openRoomSourceInspection(location.snapshot, addToHistory = false)
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
        private const val MAX_SOURCE_PANES = 2
        private const val MAX_SOURCE_UNDO_STEPS = 250
        private const val PRIMARY_SOURCE_PANE_ID = 0L
        private const val LOROM_BANK_CAPACITY_BYTES = 0x8000
    }
}

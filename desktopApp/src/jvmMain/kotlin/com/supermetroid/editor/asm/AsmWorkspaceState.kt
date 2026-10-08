package com.supermetroid.editor.asm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class AsmBrowserMode { SOURCE, ASSETS, LIBRARY, ROM }

internal sealed interface AsmWorkspaceLocation {
    data class Source(val fileId: String, val lineIndex: Int) : AsmWorkspaceLocation
    data class Asset(val path: String) : AsmWorkspaceLocation
    data class Library(val pageId: String, val instructionToken: String?) : AsmWorkspaceLocation
    data class Rom(val view: AsmRomPreviewView) : AsmWorkspaceLocation
}

internal class AsmWorkspaceState(
    private val repository: AsmReferenceRepository = AsmReferenceRepository(),
) {
    var workspace by mutableStateOf<AsmReferenceWorkspace?>(null)
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

    private val backStack = mutableListOf<AsmWorkspaceLocation>()
    private val forwardStack = mutableListOf<AsmWorkspaceLocation>()
    private var pendingSnesAddress: Int? = null

    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()
    val assetsMatchCurrentRom: Boolean
        get() = currentRomSha256 != null && currentRomSha256 == workspace?.metadata?.romSha256

    suspend fun loadInstalled() {
        if (busy) return
        busy = true
        progress = "Opening ASM reference…"
        error = null
        try {
            workspace = withContext(Dispatchers.IO) { repository.loadInstalled() }
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
        runOperation { update -> repository.installOrRefresh(romBytes, romName, update) }
    }

    suspend fun refreshAssets(romBytes: ByteArray, romName: String) {
        runOperation { update -> repository.refreshAssets(romBytes, romName, update) }
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

    fun openSource(fileId: String, lineIndex: Int = 0, addToHistory: Boolean = true) {
        val source = workspace?.index?.file(fileId) ?: return
        if (addToHistory) rememberCurrentLocation()
        browserMode = AsmBrowserMode.SOURCE
        selectedFileId = source.id
        expandedSourceFileId = source.id
        selectedLineIndex = lineIndex.coerceIn(0, (source.lines.size - 1).coerceAtLeast(0))
        selectedAssetPath = null
        selectedInstruction = null
        selectedInstructionToken = null
        navigationSerial++
        if (addToHistory) forwardStack.clear()
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
        index.resolveLabel(fileId, selectedLineIndex, token)?.let { openSource(it.fileId, it.lineIndex) }
    }

    fun openLabel(fileId: String, lineIndex: Int, token: String) {
        workspace?.index?.resolveLabel(fileId, lineIndex, token)
            ?.let { openSource(it.fileId, it.lineIndex) }
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

    private suspend fun runOperation(operation: ((String) -> Unit) -> AsmReferenceWorkspace) {
        if (busy) return
        busy = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                operation { message -> progress = message }
            }
            workspace = result
            ensureSelection()
        } catch (problem: Exception) {
            error = problem.message ?: "ASM operation failed"
        } finally {
            busy = false
            progress = null
        }
    }

    private fun ensureSelection() {
        val index = workspace?.index ?: return
        if (index.file(selectedFileId) == null) {
            selectedFileId = index.files.firstOrNull { it.id == "bank_80.asm" }?.id
                ?: index.files.firstOrNull()?.id
            selectedLineIndex = 0
        }
        if (expandedSourceFileId == null) {
            expandedSourceFileId = selectedFileId?.takeIf { index.file(it)?.isBank == true }
        }
        if (selectedAssetPath != null && index.asset(selectedAssetPath) == null) selectedAssetPath = null
        pendingSnesAddress?.let(::openAddress)
    }

    internal fun locationSnapshot(): AsmWorkspaceLocation? = when (browserMode) {
        AsmBrowserMode.SOURCE -> selectedFileId?.let { AsmWorkspaceLocation.Source(it, selectedLineIndex) }
        AsmBrowserMode.ASSETS -> selectedAssetPath?.let(AsmWorkspaceLocation::Asset)
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
            is AsmWorkspaceLocation.Source -> openSource(location.fileId, location.lineIndex, addToHistory = false)
            is AsmWorkspaceLocation.Asset -> openAsset(location.path, addToHistory = false)
            is AsmWorkspaceLocation.Library -> {
                val mnemonic = AsmLibrary.mnemonicFromPageId(location.pageId)
                if (mnemonic != null) openLibraryInstruction(location.instructionToken ?: mnemonic, addToHistory = false)
                else openLibraryGuide(location.pageId, addToHistory = false)
            }
            is AsmWorkspaceLocation.Rom -> selectRomPreviewView(location.view, addToHistory = false)
        }
    }

    companion object {
        private const val MAX_HISTORY = 100
    }
}

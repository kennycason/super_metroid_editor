package com.supermetroid.editor.asm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class AsmBrowserMode { SOURCE, ASSETS, LIBRARY }

private sealed interface AsmLocation {
    data class Source(val fileId: String, val lineIndex: Int) : AsmLocation
    data class Asset(val path: String) : AsmLocation
    data class Library(val pageId: String, val instructionToken: String?) : AsmLocation
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

    private val backStack = mutableListOf<AsmLocation>()
    private val forwardStack = mutableListOf<AsmLocation>()

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
        currentRomSha256 = if (romBytes == null) null else withContext(Dispatchers.Default) {
            repository.romSha256(romBytes)
        }
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
        currentLocation()?.let(forwardStack::add)
        restore(target)
    }

    fun goForward() {
        val target = forwardStack.removeLastOrNull() ?: return
        currentLocation()?.let(backStack::add)
        restore(target)
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
    }

    private fun currentLocation(): AsmLocation? = when (browserMode) {
        AsmBrowserMode.SOURCE -> selectedFileId?.let { AsmLocation.Source(it, selectedLineIndex) }
        AsmBrowserMode.ASSETS -> selectedAssetPath?.let(AsmLocation::Asset)
        AsmBrowserMode.LIBRARY -> AsmLocation.Library(selectedLibraryPageId, selectedInstructionToken)
    }

    private fun rememberCurrentLocation() {
        val current = currentLocation() ?: return
        if (backStack.lastOrNull() != current) backStack += current
        if (backStack.size > MAX_HISTORY) backStack.removeAt(0)
    }

    private fun restore(location: AsmLocation) {
        when (location) {
            is AsmLocation.Source -> openSource(location.fileId, location.lineIndex, addToHistory = false)
            is AsmLocation.Asset -> openAsset(location.path, addToHistory = false)
            is AsmLocation.Library -> {
                val mnemonic = AsmLibrary.mnemonicFromPageId(location.pageId)
                if (mnemonic != null) openLibraryInstruction(location.instructionToken ?: mnemonic, addToHistory = false)
                else openLibraryGuide(location.pageId, addToHistory = false)
            }
        }
    }

    companion object {
        private const val MAX_HISTORY = 100
    }
}

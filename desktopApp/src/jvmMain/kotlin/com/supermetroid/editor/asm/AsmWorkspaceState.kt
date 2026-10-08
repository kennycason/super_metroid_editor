package com.supermetroid.editor.asm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class AsmBrowserMode { SOURCE, ASSETS }

private sealed interface AsmLocation {
    data class Source(val fileId: String, val lineIndex: Int) : AsmLocation
    data class Asset(val path: String) : AsmLocation
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
    var selectedLineIndex by mutableStateOf(0)
        private set
    var selectedAssetPath by mutableStateOf<String?>(null)
        private set
    var selectedAssetCategory by mutableStateOf<String?>(null)
    var selectedInstruction by mutableStateOf<String?>(null)
        private set
    var navigationSerial by mutableStateOf(0L)
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

    fun openSource(fileId: String, lineIndex: Int = 0, addToHistory: Boolean = true) {
        val source = workspace?.index?.file(fileId) ?: return
        if (addToHistory) rememberCurrentLocation()
        browserMode = AsmBrowserMode.SOURCE
        selectedFileId = source.id
        selectedLineIndex = lineIndex.coerceIn(0, (source.lines.size - 1).coerceAtLeast(0))
        selectedAssetPath = null
        selectedInstruction = null
        navigationSerial++
        if (addToHistory) forwardStack.clear()
    }

    fun openAsset(path: String, addToHistory: Boolean = true) {
        val asset = workspace?.index?.asset(path) ?: return
        if (addToHistory) rememberCurrentLocation()
        browserMode = AsmBrowserMode.ASSETS
        selectedAssetPath = asset.range.path
        selectedAssetCategory = asset.category
        selectedInstruction = null
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
        selectedInstruction = mnemonic.substringBefore('.').uppercase()
    }

    fun closeInstruction() {
        selectedInstruction = null
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
        if (selectedAssetPath != null && index.asset(selectedAssetPath) == null) selectedAssetPath = null
    }

    private fun currentLocation(): AsmLocation? = selectedAssetPath?.let(AsmLocation::Asset)
        ?: selectedFileId?.let { AsmLocation.Source(it, selectedLineIndex) }

    private fun rememberCurrentLocation() {
        val current = currentLocation() ?: return
        if (backStack.lastOrNull() != current) backStack += current
        if (backStack.size > MAX_HISTORY) backStack.removeAt(0)
    }

    private fun restore(location: AsmLocation) {
        when (location) {
            is AsmLocation.Source -> openSource(location.fileId, location.lineIndex, addToHistory = false)
            is AsmLocation.Asset -> openAsset(location.path, addToHistory = false)
        }
    }

    companion object {
        private const val MAX_HISTORY = 100
    }
}

internal data class AsmInstructionInfo(
    val mnemonic: String,
    val name: String,
    val summary: String,
    val flags: String = "—",
)

/** Compact embedded 65C816 reference used by clickable source mnemonics. */
internal object AsmInstructionReference {
    private fun i(mnemonic: String, name: String, summary: String, flags: String = "—") =
        AsmInstructionInfo(mnemonic, name, summary, flags)

    val instructions: Map<String, AsmInstructionInfo> = listOf(
        i("ADC", "Add with carry", "Add memory and carry to the accumulator.", "N V Z C"),
        i("AND", "Logical AND", "AND memory with the accumulator.", "N Z"),
        i("ASL", "Arithmetic shift left", "Shift left; bit 7 enters carry and zero enters bit 0.", "N Z C"),
        i("BCC", "Branch if carry clear", "Branch when C = 0."), i("BCS", "Branch if carry set", "Branch when C = 1."),
        i("BEQ", "Branch if equal", "Branch when Z = 1."), i("BMI", "Branch if minus", "Branch when N = 1."),
        i("BNE", "Branch if not equal", "Branch when Z = 0."), i("BPL", "Branch if plus", "Branch when N = 0."),
        i("BRA", "Branch always", "Unconditional 8-bit relative branch."),
        i("BRK", "Software break", "Enter the software interrupt handler.", "I D"),
        i("BRL", "Branch always long", "Unconditional 16-bit relative branch."),
        i("BVC", "Branch if overflow clear", "Branch when V = 0."), i("BVS", "Branch if overflow set", "Branch when V = 1."),
        i("BIT", "Bit test", "Test accumulator bits against memory.", "N V Z"),
        i("CLC", "Clear carry", "Set C = 0.", "C"), i("CLD", "Clear decimal", "Set D = 0.", "D"),
        i("CLI", "Clear interrupt disable", "Set I = 0.", "I"), i("CLV", "Clear overflow", "Set V = 0.", "V"),
        i("CMP", "Compare accumulator", "Subtract for flags without storing a result.", "N Z C"),
        i("COP", "Coprocessor interrupt", "Enter the COP software interrupt handler.", "I D"),
        i("CPX", "Compare X", "Compare memory with X.", "N Z C"), i("CPY", "Compare Y", "Compare memory with Y.", "N Z C"),
        i("DEC", "Decrement", "Subtract one from memory or accumulator.", "N Z"),
        i("DEX", "Decrement X", "Subtract one from X.", "N Z"), i("DEY", "Decrement Y", "Subtract one from Y.", "N Z"),
        i("EOR", "Exclusive OR", "XOR memory with the accumulator.", "N Z"),
        i("INC", "Increment", "Add one to memory or accumulator.", "N Z"),
        i("INX", "Increment X", "Add one to X.", "N Z"), i("INY", "Increment Y", "Add one to Y.", "N Z"),
        i("JML", "Jump long", "Jump to a 24-bit address."), i("JMP", "Jump", "Jump to the target address."),
        i("JSL", "Jump to subroutine long", "Call a subroutine with a 24-bit return address."),
        i("JSR", "Jump to subroutine", "Call a subroutine in the current program bank."),
        i("LDA", "Load accumulator", "Load memory into the accumulator.", "N Z"),
        i("LDX", "Load X", "Load memory into X.", "N Z"), i("LDY", "Load Y", "Load memory into Y.", "N Z"),
        i("LSR", "Logical shift right", "Shift right; bit 0 enters carry and zero enters the top bit.", "N Z C"),
        i("MVN", "Block move next", "Move bytes while incrementing X and Y."),
        i("MVP", "Block move previous", "Move bytes while decrementing X and Y."),
        i("NOP", "No operation", "Consume time without changing machine state."),
        i("ORA", "Logical OR", "OR memory with the accumulator.", "N Z"),
        i("PEA", "Push effective address", "Push a 16-bit immediate value."),
        i("PEI", "Push effective indirect", "Push the 16-bit value addressed through direct page."),
        i("PER", "Push effective relative", "Push the effective 16-bit relative address."),
        i("PHA", "Push accumulator", "Push accumulator."), i("PHB", "Push data bank", "Push data-bank register."),
        i("PHD", "Push direct page", "Push direct-page register."), i("PHK", "Push program bank", "Push program-bank register."),
        i("PHP", "Push processor status", "Push processor status."), i("PHX", "Push X", "Push X."), i("PHY", "Push Y", "Push Y."),
        i("PLA", "Pull accumulator", "Pull accumulator.", "N Z"), i("PLB", "Pull data bank", "Pull data-bank register.", "N Z"),
        i("PLD", "Pull direct page", "Pull direct-page register.", "N Z"), i("PLP", "Pull processor status", "Pull processor status."),
        i("PLX", "Pull X", "Pull X.", "N Z"), i("PLY", "Pull Y", "Pull Y.", "N Z"),
        i("REP", "Reset status bits", "Clear selected processor-status bits."),
        i("ROL", "Rotate left", "Rotate left through carry.", "N Z C"), i("ROR", "Rotate right", "Rotate right through carry.", "N Z C"),
        i("RTI", "Return from interrupt", "Restore status and return from an interrupt."),
        i("RTL", "Return long", "Return from JSL."), i("RTS", "Return", "Return from JSR."),
        i("SBC", "Subtract with borrow", "Subtract memory and inverse carry from accumulator.", "N V Z C"),
        i("SEC", "Set carry", "Set C = 1.", "C"), i("SED", "Set decimal", "Set D = 1.", "D"),
        i("SEI", "Set interrupt disable", "Set I = 1.", "I"), i("SEP", "Set status bits", "Set selected processor-status bits."),
        i("STA", "Store accumulator", "Store accumulator to memory."), i("STP", "Stop processor", "Stop the processor until reset."),
        i("STX", "Store X", "Store X to memory."), i("STY", "Store Y", "Store Y to memory."), i("STZ", "Store zero", "Store zero to memory."),
        i("TAX", "Transfer accumulator to X", "Copy accumulator to X.", "N Z"),
        i("TAY", "Transfer accumulator to Y", "Copy accumulator to Y.", "N Z"),
        i("TCD", "Transfer accumulator to direct page", "Copy 16-bit accumulator to direct page.", "N Z"),
        i("TCS", "Transfer accumulator to stack", "Copy 16-bit accumulator to stack pointer."),
        i("TDC", "Transfer direct page to accumulator", "Copy direct page to accumulator.", "N Z"),
        i("TRB", "Test and reset bits", "Test accumulator bits, then clear them in memory.", "Z"),
        i("TSB", "Test and set bits", "Test accumulator bits, then set them in memory.", "Z"),
        i("TSC", "Transfer stack to accumulator", "Copy stack pointer to accumulator.", "N Z"),
        i("TSX", "Transfer stack to X", "Copy stack pointer to X.", "N Z"),
        i("TXA", "Transfer X to accumulator", "Copy X to accumulator.", "N Z"),
        i("TXS", "Transfer X to stack", "Copy X to stack pointer."), i("TXY", "Transfer X to Y", "Copy X to Y.", "N Z"),
        i("TYA", "Transfer Y to accumulator", "Copy Y to accumulator.", "N Z"), i("TYX", "Transfer Y to X", "Copy Y to X.", "N Z"),
        i("WAI", "Wait for interrupt", "Pause execution until an interrupt."),
        i("WDM", "Reserved", "Reserved two-byte instruction."), i("XBA", "Exchange accumulator bytes", "Swap accumulator high and low bytes.", "N Z"),
        i("XCE", "Exchange carry and emulation", "Swap carry with the emulation-mode flag.", "C"),
    ).associateBy { it.mnemonic }

    fun find(mnemonic: String?): AsmInstructionInfo? = mnemonic?.let { instructions[it.uppercase()] }
}

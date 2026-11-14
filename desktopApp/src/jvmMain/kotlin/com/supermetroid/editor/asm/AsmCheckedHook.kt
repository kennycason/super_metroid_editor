package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomAllocation
import com.supermetroid.editor.rom.RomSegmentKind
import com.supermetroid.editor.rom.RomSegmentPlacement
import com.supermetroid.editor.rom.RomSegmentRequest
import com.supermetroid.editor.rom.RomVerifiedFreeRange

/** A deliberately finite 65816 width table used only where source must be
 * moved byte-for-byte. Unknown or mode-dependent spelling fails closed. */
internal object AsmInstructionWidths {
    private val widths = buildMap<String, Int> {
        for (op in "PHP PLP PHB PLB PHK PHD PLD PHA PLA PHX PLX PHY PLY CLC SEC CLI SEI CLV CLD SED NOP STP WAI TAX TXA TAY TYA TXY TYX TSX TXS TCD TDC TCS TSC XBA XCE INX DEX INY DEY INA DEA RTS RTL RTI".split(' ')) put(op, 1)
        for (op in "REP SEP BEQ BNE BPL BMI BCC BCS BVC BVS BRA".split(' ')) put(op, 2)
        put("PEI", 2)
        for (op in "BRL PER PEA MVN MVP".split(' ')) put(op, 3)
        for (op in "LDA LDX LDY STA STX STY STZ CMP CPX CPY BIT TRB TSB AND ORA EOR ADC SBC INC DEC ASL LSR ROL ROR JSR JMP".split(' ')) put("$op.W", 3)
        for (op in "LDA LDX LDY STA STX STY STZ CMP CPX CPY BIT AND ORA EOR ADC SBC INC DEC ASL LSR ROL ROR".split(' ')) put("$op.B", 2)
        for (op in "LDA STA CMP AND ORA EOR ADC SBC JSL JML".split(' ')) put("$op.L", 4)
    }
    private val oneByteAccumulator = setOf("INC", "DEC", "ASL", "LSR", "ROL", "ROR")

    fun width(code: String): Int? {
        val normalized = code.trim().replace(Regex("[ \\t]+"), " ")
        val op = normalized.takeWhile { !it.isWhitespace() }.uppercase()
        widths[op]?.let { return it }
        if (op in oneByteAccumulator && normalized.substringAfter(' ', "").uppercase() in setOf("", "A")) return 1
        return null
    }
}

internal data class AsmCheckedHookCandidate(
    val sourceFileId: String,
    val sourceLineIndex: Int,
    val hookAddress: Int,
    val continuationAddress: Int,
    val helperAddress: Int,
    val helperLimitAddress: Int,
    val nativeBytes: ByteArray,
    val displacedInstructions: List<String>,
) {
    val displacedByteCount: Int get() = nativeBytes.size

    fun renderModule(requestedName: String): String {
        val stem = requestedName.removeSuffix(".asm")
            .replace(Regex("[^A-Za-z0-9_]+"), "_")
            .trim('_')
            .ifBlank { "CustomHook" }
        val symbolStem = "SMEDIT_" + stem.split('_').filter(String::isNotBlank)
            .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } } +
            "_" + hookAddress.toString(16).uppercase().padStart(6, '0')
        val hookLabel = "${symbolStem}_Hook"
        val helperLabel = "${symbolStem}_Helper"
        val helperEndLabel = "${symbolStem}_Helper_End"
        val nativeHex = nativeBytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        val padding = displacedByteCount - 4
        return buildString {
            append("; Project module: ").append(stem.replace('_', ' ')).append('\n')
            append("; SMEDIT description: Checked hook at ").append(formatSnesAddress(hookAddress)).append('\n')
            append("; @smedit-checked-hook-v2")
            append(" hook=").append(patchHex(hookAddress, 3))
            append(" continuation=").append(patchHex(continuationAddress, 3))
            append(" seed=").append(patchHex(helperAddress, 3))
            append(" probe_limit=").append(patchHex(helperLimitAddress, 3))
            append(" helper_banks=80-FF")
            append(" helper_alignment=1")
            append(" native=").append(nativeHex)
            append(" hook_label=").append(hookLabel)
            append(" helper_label=").append(helperLabel)
            append(" helper_end_label=").append(helperEndLabel).append('\n')
            append("; Generated from ").append(sourceFileId).append(':').append(sourceLineIndex + 1).append(".\n")
            append("; SMEDIT verifies the native bytes, detour, displaced instructions, helper range,\n")
            append("; continuation jump, and every byte this module emits on each build.\n\n")
            append("; The helper org/warnpc are creation-time preview hints. Each clean build measures\n")
            append("; the helper and substitutes its exact managed placement in temporary build source.\n\n")
            append("org ").append(patchHex(hookAddress, 3)).append('\n')
            append(hookLabel).append(":\n")
            append("    JML.L ").append(helperLabel).append('\n')
            repeat(padding) { append("    NOP\n") }
            append("warnpc ").append(patchHex(continuationAddress, 3)).append("\n\n")
            append("org ").append(patchHex(helperAddress, 3)).append('\n')
            append(helperLabel).append(":\n")
            displacedInstructions.forEach { append("    ").append(it).append('\n') }
            append("\n    ; Add custom code here. Keep the final JML and boundary labels intact.\n")
            append("    JML.L ").append(patchHex(continuationAddress, 3)).append('\n')
            append(helperEndLabel).append(":\n")
            append("warnpc ").append(patchHex(helperLimitAddress, 3)).append('\n')
        }
    }
}

internal object AsmCheckedHookPlanner {
    private val unsafeControlFlow = setOf(
        "BCC", "BCS", "BEQ", "BMI", "BNE", "BPL", "BRA", "BRL", "BVC", "BVS",
        "JMP", "JMP.W", "JML", "JML.L", "JSR", "JSR.W", "JSL", "JSL.L",
        "RTI", "RTL", "RTS", "BRK", "COP", "PER", "WDM",
    )
    private val labelDeclaration = Regex("^[ \\t]*(?:[A-Za-z_][A-Za-z0-9_]*|\\.[A-Za-z_][A-Za-z0-9_]*|[+-]+):")
    private val localOrAnonymousReference = Regex("(?:^|[^A-Za-z0-9_])(?:\\.[A-Za-z_][A-Za-z0-9_]*|[+-]+)(?:$|[^A-Za-z0-9_])")

    fun prepare(
        fileId: String,
        lineIndex: Int,
        text: String,
        symbols: AsmCompiledSymbols,
        romParser: RomParser,
        spacePlan: AsmRomSpacePlan,
    ): AsmCheckedHookCandidate {
        require(!fileId.startsWith(ASM_PROJECT_MODULE_PREFIX)) { "Create a hook from native bank source, not another module." }
        val lines = text.lines()
        require(lineIndex in lines.indices) { "Select a native instruction first." }
        val addresses = symbols.addressesFor(fileId)
        var sourceLine = lineIndex
        var expectedAddress = addresses[sourceLine] ?: error("Select a compiled instruction line. Labels and comments cannot be hooked directly.")
        val hookAddress = expectedAddress
        val instructions = mutableListOf<String>()
        var byteCount = 0
        while (byteCount < 4) {
            val raw = lines.getOrNull(sourceLine) ?: error("The hook needs a complete instruction boundary before the file ends.")
            val codeWithLabel = asmCodeBeforeComment(raw).trim()
            val declaration = labelDeclaration.find(codeWithLabel)
            require(sourceLine == lineIndex || declaration == null) {
                "A label begins inside the required detour span. Start from a different instruction or move the label explicitly."
            }
            val code = declaration?.let { codeWithLabel.substring(it.range.last + 1).trim() } ?: codeWithLabel
            require(code.isNotBlank() && ':' !in code) { "Select a plain 65816 instruction line." }
            val op = code.takeWhile { !it.isWhitespace() }.uppercase()
            require(op !in unsafeControlFlow) { "$op changes control flow and cannot be displaced automatically." }
            require(!localOrAnonymousReference.containsMatchIn(code.substringAfter(' ', ""))) {
                "Local and anonymous targets cannot be moved safely into a helper."
            }
            val width = requireNotNull(AsmInstructionWidths.width(code)) {
                "SMEDIT cannot prove the width of '$code'. Spell the operand size explicitly or create this hook manually."
            }
            require(addresses[sourceLine] == expectedAddress) { "The selected instructions are not contiguous in the compiled build." }
            instructions += code
            byteCount += width
            expectedAddress += width
            if (byteCount < 4) {
                val next = (sourceLine + 1 until lines.size).firstOrNull { nextLine ->
                    val sourceCode = asmCodeBeforeComment(lines[nextLine]).trim()
                    sourceCode.isNotBlank() || addresses[nextLine] != null
                } ?: error("The hook needs at least four complete instruction bytes.")
                val intervening = lines.subList(sourceLine + 1, next + 1)
                require(intervening.none { labelDeclaration.containsMatchIn(asmCodeBeforeComment(it).trim()) }) {
                    "A label begins inside the required detour span. Start from a different instruction or move the label explicitly."
                }
                sourceLine = next
            }
        }
        require(hookAddress ushr 16 == (expectedAddress - 1) ushr 16) {
            "The detour span crosses a bank boundary and cannot be generated safely."
        }
        val candidates = spacePlan.banks.flatMap { bank -> bank.rangesAfter.map { bank to it } }
            .filter { (_, range) -> range.length >= byteCount + 4 + 16 }
            .sortedWith(compareByDescending<Pair<AsmRomSpaceBank, AsmRomSpaceRange>> { it.first.bank >= 0xE0 }
                .thenByDescending { it.second.length })
        val (helperBank, helperRange) = candidates.firstOrNull()
            ?: error("No verified free-space block can hold this checked hook helper. Open Space Planner for details.")
        val helperAddress = requireNotNull(pcToSnesLoRom(helperRange.pcStart)) { "The selected helper range is not LoROM-addressable." }
        require(helperAddress ushr 16 and 0xFF == helperBank.bank) { "The Space Planner helper bank is inconsistent." }
        val limitPc = minOf(helperRange.endExclusive, helperRange.pcStart + 0x400)
        val helperLimit = helperAddress + (limitPc - helperRange.pcStart)
        val native = ByteArray(byteCount) { offset -> romParser.readByteAt(romParser.snesToPc(hookAddress) + offset).toByte() }
        return AsmCheckedHookCandidate(
            sourceFileId = fileId,
            sourceLineIndex = lineIndex,
            hookAddress = hookAddress,
            continuationAddress = expectedAddress,
            helperAddress = helperAddress,
            helperLimitAddress = helperLimit,
            nativeBytes = native,
            displacedInstructions = instructions,
        )
    }
}

internal data class AsmCheckedHookContract(
    val hookAddress: Int,
    val continuationAddress: Int,
    val helperAddress: Int,
    val helperLimitAddress: Int,
    val nativeBytes: ByteArray,
    val hookLabel: String,
    val helperLabel: String,
    val helperEndLabel: String,
    val managedBanks: List<Int> = emptyList(),
    val helperAlignment: Int = 1,
) {
    val managed: Boolean get() = managedBanks.isNotEmpty()
    fun ownership(
        fileId: String,
        helperEndAddress: Int,
        actualHelperAddress: Int = helperAddress,
    ): List<AsmSourceOwnedRange> = listOf(
        AsmSourceOwnedRange(checkNotNull(snesLoRomToPc(hookAddress)), nativeBytes.size, fileId),
        AsmSourceOwnedRange(
            checkNotNull(snesLoRomToPc(actualHelperAddress)),
            helperEndAddress - actualHelperAddress,
            fileId,
        ),
    )

    companion object {
        private const val MARKER_V1 = "@smedit-checked-hook-v1"
        private const val MARKER_V2 = "@smedit-checked-hook-v2"
        private val field = Regex("([a-z_]+)=([^ \\t]+)")
        private val label = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun parseAll(text: String): List<AsmCheckedHookContract> {
            val markerLines = text.lineSequence().filter { MARKER_V1 in it || MARKER_V2 in it }.toList()
            require(markerLines.isNotEmpty() || "; SMEDIT description: Checked hook at " !in text) {
                "Checked-hook safety metadata is missing. Recreate the hook or restore its @smedit-checked-hook line."
            }
            return markerLines.map { line ->
                val version = if (MARKER_V2 in line) 2 else 1
                val marker = if (version == 2) MARKER_V2 else MARKER_V1
                require(line.trim().startsWith("; $marker ")) { "Checked-hook metadata must be one comment line beginning '; $marker'." }
                val fields = field.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                val expected = if (version == 2) {
                    setOf("hook", "continuation", "seed", "probe_limit", "helper_banks", "helper_alignment", "native", "hook_label", "helper_label", "helper_end_label")
                } else {
                    setOf("hook", "continuation", "helper", "limit", "native", "hook_label", "helper_label", "helper_end_label")
                }
                require(fields.keys == expected) { "Checked-hook metadata fields changed. Recreate the hook or restore its metadata line." }
                fun address(name: String): Int = fields.getValue(name).removePrefix("$").toIntOrNull(16)
                    ?.takeIf { it in 0..0xFFFFFF && (name == "limit" || snesLoRomToPc(it) != null) }
                    ?: error("Checked-hook $name is not a valid LoROM address.")
                val nativeHex = fields.getValue("native")
                require(nativeHex.length >= 8 && nativeHex.length % 2 == 0 && nativeHex.all { it.digitToIntOrNull(16) != null }) {
                    "Checked-hook native bytes must contain at least four complete hex bytes."
                }
                fun checkedLabel(name: String) = fields.getValue(name).also {
                    require(label.matches(it)) { "Checked-hook $name is not a safe global label." }
                }
                // v1 was generated only by SMEDIT and always used a long JML
                // detour, so its former helper/limit fields are safe placement
                // seeds. Treat them as managed intent instead of preserving a
                // stale fixed address during the beta-format migration.
                val managedBanks = if (version == 2) parseBanks(fields.getValue("helper_banks"))
                    else (0x80..0xFF).toList()
                val alignment = if (version == 2) fields.getValue("helper_alignment").toIntOrNull()
                    ?.takeIf { it in 1..0x8000 } ?: error("Checked-hook helper_alignment is invalid.") else 1
                AsmCheckedHookContract(
                    hookAddress = address("hook"),
                    continuationAddress = address("continuation"),
                    helperAddress = address(if (version == 2) "seed" else "helper"),
                    helperLimitAddress = address(if (version == 2) "probe_limit" else "limit"),
                    nativeBytes = nativeHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                    hookLabel = checkedLabel("hook_label"),
                    helperLabel = checkedLabel("helper_label"),
                    helperEndLabel = checkedLabel("helper_end_label"),
                    managedBanks = managedBanks,
                    helperAlignment = alignment,
                ).also { contract ->
                    require(contract.continuationAddress == contract.hookAddress + contract.nativeBytes.size) {
                        "Checked-hook continuation must immediately follow the displaced native bytes."
                    }
                    require(contract.helperAddress < contract.helperLimitAddress &&
                        contract.helperAddress ushr 16 == (contract.helperLimitAddress - 1) ushr 16) {
                        "Checked-hook helper limit must follow the helper in one bank."
                    }
                }
            }
        }

        private fun parseBanks(value: String): List<Int> = buildList {
            value.split(',').forEach { token ->
                val bounds = token.split('-')
                require(bounds.size in 1..2 && bounds.all { it.length == 2 && it.all { c -> c.digitToIntOrNull(16) != null } }) {
                    "Checked-hook helper_banks must use hex banks or inclusive ranges."
                }
                val first = bounds.first().toInt(16)
                val last = bounds.last().toInt(16)
                require(first in 0x80..0xFF && last in first..0xFF)
                addAll(first..last)
            }
        }.distinct()
    }
}

internal data class AsmManagedHookHelperOverride(val address: Int, val limitAddress: Int)

/** Build-only rewrite. Authored modules retain semantic helper bodies while
 * the checked allocator supplies each compile's address and exact boundary. */
internal fun applyManagedHookHelperOverrides(
    source: String,
    overrides: Map<String, AsmManagedHookHelperOverride>,
): String {
    if (overrides.isEmpty()) return source
    val contracts = AsmCheckedHookContract.parseAll(source).filter(AsmCheckedHookContract::managed)
    if (contracts.isEmpty()) return source
    val lines = source.lines().toMutableList()
    fun replaceStatement(index: Int, statement: String) {
        val raw = lines[index]
        val indent = raw.takeWhile { it == ' ' || it == '\t' }
        val comment = raw.indexOf(';').takeIf { it >= 0 }?.let { raw.substring(it) }.orEmpty()
        lines[index] = indent + statement + if (comment.isBlank()) "" else " $comment"
    }
    contracts.forEach { contract ->
        val override = requireNotNull(overrides[contract.helperLabel]) {
            "Missing managed placement for ${contract.helperLabel}."
        }
        val helperLine = lines.indexOfFirst { asmExecutableCode(it).trim() == "${contract.helperLabel}:" }
        require(helperLine >= 0) { "Missing checked-hook helper label ${contract.helperLabel}." }
        val orgLine = (helperLine - 1 downTo 0).firstOrNull { asmExecutableCode(lines[it]).isNotBlank() }
            ?: error("${contract.helperLabel} has no placement directive.")
        require(asmExecutableCode(lines[orgLine]).trim().startsWith("org ", true)) {
            "${contract.helperLabel} must immediately follow its managed org directive."
        }
        val endLine = lines.indices.firstOrNull { index ->
            index > helperLine && asmExecutableCode(lines[index]).trim() == "${contract.helperEndLabel}:"
        } ?: -1
        require(endLine > helperLine) { "Missing checked-hook helper boundary ${contract.helperEndLabel}." }
        val warnLine = (endLine + 1 until lines.size).firstOrNull { asmExecutableCode(lines[it]).isNotBlank() }
            ?: error("${contract.helperEndLabel} has no checked boundary.")
        require(asmExecutableCode(lines[warnLine]).trim().startsWith("warnpc ", true)) {
            "${contract.helperEndLabel} must be followed by warnpc."
        }
        replaceStatement(orgLine, "org ${patchHex(override.address, 3)}")
        replaceStatement(warnLine, "warnpc ${patchHex(override.limitAddress, 3)}")
    }
    return lines.joinToString("\n")
}

internal object AsmCheckedHookValidator {
    fun segmentPlacements(
        fileId: String,
        contracts: List<AsmCheckedHookContract>,
        symbolsBytes: ByteArray,
        verifiedFreeRanges: List<RomVerifiedFreeRange>,
    ): List<RomSegmentPlacement> {
        if (contracts.isEmpty()) return emptyList()
        val labels = AsmWlaSymbolParser.parse(symbolsBytes.toString(Charsets.UTF_8)).labels
        return contracts.map { contract ->
            val helper = requireNotNull(labels[contract.helperLabel]) {
                "$fileId: missing checked-hook helper label ${contract.helperLabel}."
            }
            val helperEnd = requireNotNull(labels[contract.helperEndLabel]) {
                "$fileId: missing checked-hook boundary ${contract.helperEndLabel}."
            }
            val pc = requireNotNull(snesLoRomToPc(helper)) { "$fileId: helper is not LoROM-addressable." }
            val range = requireNotNull(verifiedFreeRanges.singleOrNull { verified ->
                verified.bank == helper ushr 16 && pc >= verified.pcStart &&
                    pc + helperEnd - helper <= verified.pcEndExclusive
            }) { "$fileId: checked-hook helper is outside verified free space." }
            val owner = if (fileId.startsWith(ASM_PROJECT_MODULE_PREFIX)) {
                "asm-module:${fileId.removePrefix(ASM_PROJECT_MODULE_PREFIX).removeSuffix(".asm")}"
            } else {
                "asm-source:project"
            }
            val request = RomSegmentRequest(
                id = "checked-hook-helper:$fileId:${contract.helperLabel}",
                owner = owner,
                kind = RomSegmentKind.HOOK_HELPER,
                label = "Checked-hook helper · ${contract.helperLabel}",
                size = helperEnd - helper,
                alignment = contract.helperAlignment,
                allowedBanks = contract.managedBanks.ifEmpty { listOf(helper ushr 16 and 0xFF) },
                constraint = "The native JML detour and exact continuation bind this helper symbolically",
            )
            RomSegmentPlacement(
                request,
                RomAllocation(
                    bank = helper ushr 16 and 0xFF,
                    pcOffset = pc,
                    snesAddress = helper,
                    size = request.size,
                    label = request.label,
                    fillByte = range.fillByte,
                    request = request,
                ),
            )
        }
    }

    fun validate(
        fileId: String,
        contracts: List<AsmCheckedHookContract>,
        immutable: ByteArray,
        moduleFree: ByteArray,
        isolated: ByteArray,
        symbolsBytes: ByteArray,
        verifiedFreeRanges: List<RomVerifiedFreeRange>,
        changedClaims: List<AsmSourceOwnedRange>,
    ): List<AsmSourceOwnedRange> {
        if (contracts.isEmpty()) return changedClaims
        val labels = AsmWlaSymbolParser.parse(symbolsBytes.toString(Charsets.UTF_8)).labels
        val ownership = contracts.flatMap { contract ->
            fun symbol(name: String) = requireNotNull(labels[name]) { "$fileId: missing checked-hook boundary label $name." }
            val hook = symbol(contract.hookLabel)
            val helper = symbol(contract.helperLabel)
            val helperEnd = symbol(contract.helperEndLabel)
            require(hook == contract.hookAddress &&
                (contract.managed && (helper ushr 16 and 0xFF) in contract.managedBanks ||
                    !contract.managed && helper == contract.helperAddress)) {
                "$fileId: checked-hook native boundary or managed helper bank is invalid."
            }
            require(helperEnd > helper && (contract.managed || helperEnd <= contract.helperLimitAddress)) {
                "$fileId: checked-hook helper exceeds its verified limit."
            }
            val hookPc = checkNotNull(snesLoRomToPc(hook))
            val helperPc = checkNotNull(snesLoRomToPc(helper))
            val helperEndPc = checkNotNull(snesLoRomToPc(helperEnd))
            require(immutable.copyOfRange(hookPc, hookPc + contract.nativeBytes.size).contentEquals(contract.nativeBytes) &&
                moduleFree.copyOfRange(hookPc, hookPc + contract.nativeBytes.size).contentEquals(contract.nativeBytes)) {
                "$fileId: native hook bytes changed. Restore the source instruction or recreate the hook."
            }
            val expectedHook = byteArrayOf(
                0x5C, (helper and 0xFF).toByte(), (helper ushr 8 and 0xFF).toByte(), (helper ushr 16 and 0xFF).toByte(),
            ) + ByteArray(contract.nativeBytes.size - 4) { 0xEA.toByte() }
            require(isolated.copyOfRange(hookPc, hookPc + expectedHook.size).contentEquals(expectedHook)) {
                "$fileId: checked-hook detour must remain JML to its helper with NOP padding."
            }
            require(isolated.copyOfRange(helperPc, helperPc + contract.nativeBytes.size).contentEquals(contract.nativeBytes)) {
                "$fileId: displaced instructions no longer reproduce the native bytes."
            }
            val expectedTail = byteArrayOf(
                0x5C, (contract.continuationAddress and 0xFF).toByte(),
                (contract.continuationAddress ushr 8 and 0xFF).toByte(),
                (contract.continuationAddress ushr 16 and 0xFF).toByte(),
            )
            require(helperEndPc - helperPc >= contract.nativeBytes.size + 4 &&
                isolated.copyOfRange(helperEndPc - 4, helperEndPc).contentEquals(expectedTail)) {
                "$fileId: checked-hook helper must end with JML to its exact continuation."
            }
            require(verifiedFreeRanges.any { range ->
                helperPc >= range.pcStart && helperEndPc <= range.pcEndExclusive
            }) { "$fileId: checked-hook helper is outside verified free space." }
            contract.ownership(fileId, helperEnd, helper)
        }
        require(ownership.indices.all { first -> ownership.indices.drop(first + 1).none { second ->
            val a = ownership[first]; val b = ownership[second]
            a.pcOffset < b.pcOffset + b.length && b.pcOffset < a.pcOffset + a.length
        } }) { "$fileId: checked-hook spans overlap each other." }
        val sortedOwnership = ownership.sortedBy(AsmSourceOwnedRange::pcOffset)
        fun fullyOwned(changed: AsmSourceOwnedRange): Boolean {
            var cursor = changed.pcOffset
            val end = changed.pcOffset + changed.length
            for (allowed in sortedOwnership) {
                val allowedEnd = allowed.pcOffset + allowed.length
                if (allowedEnd <= cursor) continue
                if (allowed.pcOffset > cursor) return false
                cursor = maxOf(cursor, allowedEnd)
                if (cursor >= end) return true
            }
            return cursor >= end
        }
        // changedRanges intentionally coalesces adjacent bytes. Two helpers can
        // therefore produce one changed claim even though their typed segment
        // identities must remain separate.
        val unexpected = changedClaims.firstOrNull { changed -> !fullyOwned(changed) }
        require(unexpected == null) {
            val changed = checkNotNull(unexpected)
            val address = pcToSnesLoRom(changed.pcOffset)?.let(::formatSnesAddress)
                ?: formatPcOffset(changed.pcOffset)
            val allowed = ownership.joinToString { range ->
                val start = pcToSnesLoRom(range.pcOffset)?.let(::formatSnesAddress)
                    ?: formatPcOffset(range.pcOffset)
                "$start +${range.length}"
            }
            "$fileId emits ${changed.length} byte(s) at $address outside its declared checked-hook spans ($allowed)."
        }
        return ownership
    }
}

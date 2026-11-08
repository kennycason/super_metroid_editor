package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomParser
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
            append("; @smedit-checked-hook-v1")
            append(" hook=").append(patchHex(hookAddress, 3))
            append(" continuation=").append(patchHex(continuationAddress, 3))
            append(" helper=").append(patchHex(helperAddress, 3))
            append(" limit=").append(patchHex(helperLimitAddress, 3))
            append(" native=").append(nativeHex)
            append(" hook_label=").append(hookLabel)
            append(" helper_label=").append(helperLabel)
            append(" helper_end_label=").append(helperEndLabel).append('\n')
            append("; Generated from ").append(sourceFileId).append(':').append(sourceLineIndex + 1).append(".\n")
            append("; SMEDIT verifies the native bytes, detour, displaced instructions, helper range,\n")
            append("; continuation jump, and every byte this module emits on each build.\n\n")
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
) {
    fun ownership(fileId: String, helperEndAddress: Int): List<AsmSourceOwnedRange> = listOf(
        AsmSourceOwnedRange(checkNotNull(snesLoRomToPc(hookAddress)), nativeBytes.size, fileId),
        AsmSourceOwnedRange(checkNotNull(snesLoRomToPc(helperAddress)), helperEndAddress - helperAddress, fileId),
    )

    companion object {
        private const val MARKER = "@smedit-checked-hook-v1"
        private val field = Regex("([a-z_]+)=([^ \\t]+)")
        private val label = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun parseAll(text: String): List<AsmCheckedHookContract> {
            val markerLines = text.lineSequence().filter { MARKER in it }.toList()
            require(markerLines.isNotEmpty() || "; SMEDIT description: Checked hook at " !in text) {
                "Checked-hook safety metadata is missing. Recreate the hook or restore its @smedit-checked-hook-v1 line."
            }
            return markerLines.map { line ->
                require(line.trim().startsWith("; $MARKER ")) { "Checked-hook metadata must be one comment line beginning '; $MARKER'." }
                val fields = field.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                val expected = setOf("hook", "continuation", "helper", "limit", "native", "hook_label", "helper_label", "helper_end_label")
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
                AsmCheckedHookContract(
                    hookAddress = address("hook"),
                    continuationAddress = address("continuation"),
                    helperAddress = address("helper"),
                    helperLimitAddress = address("limit"),
                    nativeBytes = nativeHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                    hookLabel = checkedLabel("hook_label"),
                    helperLabel = checkedLabel("helper_label"),
                    helperEndLabel = checkedLabel("helper_end_label"),
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
    }
}

internal object AsmCheckedHookValidator {
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
            require(hook == contract.hookAddress && helper == contract.helperAddress) {
                "$fileId: checked-hook boundary labels moved."
            }
            require(helperEnd > helper && helperEnd <= contract.helperLimitAddress) {
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
            contract.ownership(fileId, helperEnd)
        }
        require(ownership.indices.all { first -> ownership.indices.drop(first + 1).none { second ->
            val a = ownership[first]; val b = ownership[second]
            a.pcOffset < b.pcOffset + b.length && b.pcOffset < a.pcOffset + a.length
        } }) { "$fileId: checked-hook spans overlap each other." }
        require(changedClaims.all { changed -> ownership.any { allowed ->
            changed.pcOffset >= allowed.pcOffset && changed.pcOffset + changed.length <= allowed.pcOffset + allowed.length
        } }) { "$fileId emits bytes outside its declared checked-hook spans." }
        return ownership
    }
}

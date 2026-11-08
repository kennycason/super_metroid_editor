package com.supermetroid.editor.asm

import java.io.File

/**
 * Safety contract for the bundled hook tutorial. This remains deliberately
 * narrower than a generic hook framework: every native address, continuation,
 * placement boundary, and emitted span belongs to this one reviewed example.
 */
internal object AsmCheckedHookDemo {
    const val FILE = "project/checked_hook_demo.asm"
    private const val FILE_NAME = "checked_hook_demo.asm"
    private const val FLAG = "!SMEDIT_CheckedHookDemoEnabled"
    private const val NATIVE_FILE = "bank_90.asm"
    private const val NATIVE_ROOT = "LowEnergyCheck"
    private const val HOOK_LABEL = "SMEDIT_CheckedHookDemo_Hook"
    private const val HELPER_LABEL = "SMEDIT_CheckedHookDemo_Threshold"
    private const val HELPER_END_LABEL = "SMEDIT_CheckedHookDemo_Threshold_End"
    private const val FREE_SPACE_LABEL = "Freespace_Bank90_F63A"
    private val assignment = Regex(
        "(?m)^[ \\t]*${Regex.escape(FLAG)}[ \\t]*=[ \\t]*([01])[ \\t]*(?:;[^\\r\\n]*)?\\r?$",
    )

    fun hasGate(text: String): Boolean = FLAG in text

    /** A literal, outermost gate makes the safety baseline deterministic. */
    fun enabled(text: String): Boolean {
        val match = requireNotNull(assignment.findAll(text).toList().singleOrNull()) {
            "Checked Hook Demo needs exactly one literal $FLAG = 0 or 1."
        }
        val code = text.lineSequence()
            .map { asmCodeBeforeComment(it).trim() }
            .filter(String::isNotBlank)
            .toList()
        require(
            code.firstOrNull()?.replace(Regex("[ \\t]"), "") == "$FLAG=${match.groupValues[1]}" &&
                code.getOrNull(1)?.replace(Regex("[ \\t]+"), " ") == "if $FLAG",
        ) {
            "Checked Hook Demo must begin with its literal flag and conditional assembly gate."
        }
        var depth = 0
        for ((index, line) in code.drop(1).withIndex()) {
            require(':' !in line.dropLast(1)) {
                "Checked Hook Demo requires one assembly statement per line."
            }
            when (line.takeWhile { !it.isWhitespace() }.lowercase()) {
                "if" -> depth++
                "endif" -> {
                    depth--
                    require(depth > 0 || index == code.size - 2) {
                        "Checked Hook Demo code escaped its on/off gate."
                    }
                }
                "else", "elseif" -> require(depth > 1) {
                    "Checked Hook Demo cannot have an outer else branch."
                }
                else -> require(depth > 0 && !line.startsWith(FLAG)) {
                    "Checked Hook Demo code escaped or redefined its on/off gate."
                }
            }
            require(depth >= 0) { "Unbalanced Checked Hook Demo conditional assembly." }
        }
        require(depth == 0 && code.lastOrNull() == "endif") {
            "Checked Hook Demo needs a complete conditional assembly gate."
        }
        return match.groupValues[1] == "1"
    }

    fun withEnabled(text: String, enabled: Boolean): String {
        enabled(text)
        val value = assignment.find(text)!!.groups[1]!!
        return text.replaceRange(value.range, if (enabled) "1" else "0")
    }

    fun active(workspace: AsmProjectBuildWorkspace): Boolean {
        val source = File(workspace.workingDirectory, "src/$FILE")
        if (!source.isFile || FILE in workspace.disabledProjectModuleFileIds) return false
        val text = source.readText()
        // A pre-existing user module with the same filename is ordinary authored
        // source. Only the bundled literal gate opts into this checked contract.
        if (!hasGate(text)) return false
        return enabled(text)
    }

    private fun validateNative(workspace: AsmProjectBuildWorkspace) {
        val original = File(workspace.originalDirectory, "src/$NATIVE_FILE").readText()
        val current = File(workspace.workingDirectory, "src/$NATIVE_FILE").readText()
        val originals = mapOf(NATIVE_FILE to original)
        val currentTexts = mapOf(NATIVE_FILE to current)
        val catalog = AsmDataPatchSource(originals, AsmSourceBindings.load(workspace.originalDirectory).names)
        require(
            catalog.instructionBlock(NATIVE_FILE, NATIVE_ROOT, currentTexts) ==
                catalog.instructionBlock(NATIVE_FILE, NATIVE_ROOT, originals),
        ) {
            "Checked Hook Demo: LowEnergyCheck changed in bank_90.asm. Restore that native routine or disable the demo."
        }
    }

    private fun requireLastEnabledModule(workspace: AsmProjectBuildWorkspace) {
        val projectDirectory = File(workspace.workingDirectory, "src/project")
        val order = File(projectDirectory, ASM_PROJECT_MODULE_ORDER_FILE)
            .readLines()
            .map(String::trim)
            .filter(String::isNotEmpty)
        val enabled = order.filterNot { ASM_PROJECT_MODULE_PREFIX + it in workspace.disabledProjectModuleFileIds }
        require(enabled.lastOrNull() == FILE_NAME) {
            "Checked Hook Demo must be the last enabled Project Module so later source cannot overwrite its checked spans."
        }
    }

    /**
     * Proves the compiled detour against a second build with only this demo
     * muted. The returned ownership claims include unchanged bytes too, so the
     * normal export planner can reject later GUI writes anywhere in the hook.
     */
    fun protect(
        workspace: AsmProjectBuildWorkspace,
        compiled: AsmCompiledBuildBase,
    ): AsmCompiledBuildBase {
        validateNative(workspace)
        requireLastEnabledModule(workspace)
        val baseline = requireNotNull(compiled.checkedHookDemoBaselineRomBody) {
            "Checked Hook Demo has no source-only safety baseline."
        }
        val labels = AsmSourceBindings.load(workspace.originalDirectory).names
            .canonicalLabels(AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels)
        fun symbol(name: String): Int = requireNotNull(labels[name]) {
            "Checked Hook Demo: missing compiled symbol $name."
        }
        fun pc(address: Int): Int = requireNotNull(snesLoRomToPc(address)) {
            "Checked Hook Demo: invalid ROM address ${patchHex(address, 3)}."
        }
        fun overlaps(aStart: Int, aLength: Int, bStart: Int, bLength: Int): Boolean =
            aStart < bStart + bLength && bStart < aStart + aLength

        val native = symbol(NATIVE_ROOT)
        val lowEnergy = symbol("LowEnergyCheck_lowEnergy")
        val energy = symbol("Energy")
        val hook = symbol(HOOK_LABEL)
        val helper = symbol(HELPER_LABEL)
        val helperEnd = symbol(HELPER_END_LABEL)
        val freeSpace = symbol(FREE_SPACE_LABEL)
        require(native == 0x90EA7F && lowEnergy == 0x90EA98) {
            "Checked Hook Demo: native entry or low-energy continuation moved."
        }
        require(hook == native) {
            "Checked Hook Demo: the hook label must remain at LowEnergyCheck."
        }
        require(
            helper ushr 16 == 0x90 && helper >= freeSpace && helperEnd > helper && helperEnd <= 0x918000,
        ) {
            "Checked Hook Demo: helper code must fit the verified trailing free space in bank 90."
        }

        val headerSize = compiled.romBytes.size - baseline.size
        require(headerSize == 0 || headerSize == 0x200) {
            "Checked Hook Demo: unexpected ROM header size."
        }
        fun compiledBytes(address: Int, length: Int): List<Int> {
            val start = headerSize + pc(address)
            require(start >= headerSize && start + length <= compiled.romBytes.size) {
                "Checked Hook Demo: compiled span is outside the ROM."
            }
            return compiled.romBytes.copyOfRange(start, start + length).map { it.toInt() and 0xFF }
        }
        val expectedHook = listOf(0x4C, helper and 0xFF, helper ushr 8 and 0xFF)
        require(compiledBytes(hook, expectedHook.size) == expectedHook) {
            "Checked Hook Demo: the native entry must contain JMP.W $HELPER_LABEL."
        }

        fun word(value: Int): List<Int> = listOf(value and 0xFF, value ushr 8 and 0xFF)
        val expectedHelperLength = 14
        require(helperEnd == helper + expectedHelperLength) {
            "Checked Hook Demo: helper structure changed. This checked tutorial permits threshold-value edits only."
        }
        val helperBytes = compiledBytes(helper, expectedHelperLength)
        require(
            helperBytes.slice(0..2) == listOf(0xAD) + word(energy) &&
                helperBytes[3] == 0xC9 &&
                helperBytes.slice(6..7) == listOf(0x30, 0x03) &&
                helperBytes.slice(8..10) == listOf(0x4C) + word(native + 0x08) &&
                helperBytes.slice(11..13) == listOf(0x4C) + word(lowEnergy),
        ) {
            "Checked Hook Demo: displaced load, decision branch, or native continuation changed."
        }

        val hookPc = pc(hook)
        val helperPc = pc(helper)
        val helperLength = helperEnd - helper
        val expectedNative = listOf(0xAD, energy and 0xFF, energy ushr 8 and 0xFF)
        require(baseline.copyOfRange(hookPc, hookPc + expectedNative.size).map { it.toInt() and 0xFF } == expectedNative) {
            "Checked Hook Demo: another module changes the LDA.W Energy hook instruction."
        }
        require((helperPc until helperPc + helperLength).all { (compiled.referenceRomBody[it].toInt() and 0xFF) == 0xFF }) {
            "Checked Hook Demo: helper placement overlaps original game data."
        }
        require((helperPc until helperPc + helperLength).all { (baseline[it].toInt() and 0xFF) == 0xFF }) {
            "Checked Hook Demo: helper placement overlaps another authored source allocation."
        }

        val ranges = listOf(
            AsmSourceOwnedRange(hookPc, expectedHook.size, FILE),
            AsmSourceOwnedRange(helperPc, helperLength, FILE),
        )
        require(!overlaps(ranges[0].pcOffset, ranges[0].length, ranges[1].pcOffset, ranges[1].length)) {
            "Checked Hook Demo hook and helper spans overlap."
        }
        val generatedClaims = compiled.generatedAssetClaims.map { Triple(it.pcOffset, it.length, it.label) } +
            compiled.generatedRoomClaims.map { Triple(it.pcOffset, it.length, it.label) } +
            compiled.generatedPatchClaims.map { Triple(it.pcOffset, it.length, it.label) }
        require(generatedClaims.none { claim ->
            ranges.any { range -> overlaps(claim.first, claim.second, range.pcOffset, range.length) }
        }) {
            "Checked Hook Demo overlaps editor-generated source data. Relocate one owner before building."
        }

        for (at in baseline.indices) {
            require(
                at in 0x7FDC..0x7FDF ||
                    baseline[at] == compiled.romBytes[headerSize + at] ||
                    ranges.any { at >= it.pcOffset && at < it.pcOffset + it.length },
            ) {
                "Checked Hook Demo emits bytes outside its checked hook/helper spans. Keep custom code between its boundary labels."
            }
        }

        val retained = compiled.sourceOwnedRanges.flatMap { old ->
            var pieces = listOf(old)
            for (range in ranges) {
                pieces = pieces.flatMap { part ->
                    val start = maxOf(part.pcOffset, range.pcOffset)
                    val end = minOf(part.pcOffset + part.length, range.pcOffset + range.length)
                    if (start >= end) {
                        listOf(part)
                    } else {
                        listOfNotNull(
                            part.copy(length = start - part.pcOffset).takeIf { it.length > 0 },
                            part.copy(
                                pcOffset = end,
                                length = part.pcOffset + part.length - end,
                            ).takeIf { it.length > 0 },
                        )
                    }
                }
            }
            pieces
        }
        return compiled.copy(sourceOwnedRanges = retained + ranges)
    }
}

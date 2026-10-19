package com.supermetroid.editor.asm

import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.data.loadResource
import java.io.File

/** One audited code patch, not a generic IPS-to-source converter. */
internal object AsmFastDoorsModule {
    const val PATCH_ID = "bundled_fast_doors"
    const val FILE = "project/fast_doors.asm"
    const val SOURCE_KEY = "code_patch:fast_doors"
    private const val FLAG = "!SMEDIT_FastDoorsEnabled"
    private val assignment = Regex("(?m)^[ \\t]*${Regex.escape(FLAG)}[ \\t]*=[ \\t]*([01])[ \\t]*(?:;[^\\r\\n]*)?\\r?$")
    private val bundled by lazy {
        PatchRepository.parseIps(requireNotNull(loadResource("patches/fast_doors.ips")).use { it.readBytes() })
    }
    private val source by lazy {
        requireNotNull(loadResource("asm-templates/fast_doors.asm")).bufferedReader().use { it.readText() }
    }
    fun template(enabled: Boolean, bindings: AsmSourceBindings = AsmSourceBindings()) =
        bindings.template(source.replace("$FLAG = 0", "$FLAG = ${if (enabled) 1 else 0}"))
    fun hasGate(text: String) = FLAG in text
    fun enabled(text: String): Boolean {
        val match = requireNotNull(assignment.findAll(text).toList().singleOrNull()) {
            "Fast Doors needs exactly one literal $FLAG = 0 or 1."
        }
        // Only the flag may precede the outer gate; nothing may escape it. In particular,
        // an outer else must never turn Disable into a different active code patch.
        val code = text.lineSequence().map { asmCodeBeforeComment(it).trim() }.filter(String::isNotBlank).toList()
        require(code.first().replace(Regex("[ \\t]"), "") == "$FLAG=${match.groupValues[1]}" &&
            code.getOrNull(1)?.replace(Regex("[ \\t]+"), " ") == "if $FLAG") {
            "Fast Doors must begin with its literal flag and conditional assembly gate."
        }
        var depth = 0
        for ((index, line) in code.drop(1).withIndex()) {
            require(':' !in line.dropLast(1)) { "Fast Doors requires one assembly statement per line." }
            val op = line.takeWhile { !it.isWhitespace() }.lowercase()
            when (op) {
                "if" -> depth++
                "endif" -> { depth--; require(depth > 0 || index == code.size - 2) { "Fast Doors code escaped its on/off gate." } }
                "else", "elseif" -> require(depth > 1) { "Fast Doors cannot have an outer else branch." }
                else -> require(depth > 0 && !line.startsWith(FLAG)) { "Fast Doors code escaped or redefined its on/off gate." }
            }
            require(depth >= 0) { "Unbalanced Fast Doors conditional assembly." }
        }
        require(depth == 0 && code.last() == "endif") { "Fast Doors needs a complete conditional assembly gate." }
        return match.groupValues[1] == "1"
    }
    fun withEnabled(text: String, enabled: Boolean): String {
        enabled(text)
        val value = assignment.find(text)!!.groups[1]!!
        return text.replaceRange(value.range, if (enabled) "1" else "0")
    }
    fun requireBundled(patch: SmPatch) {
        require(patch.id == PATCH_ID && patch.configType == null && patch.configValue == null && patch.configData.orEmpty().isEmpty() &&
            patch.writes.map { it.offset to it.bytes } == bundled.map { it.offset to it.bytes }) {
            "Fast Doors has customized ROM records. Restore the bundled records before selecting its ASM module; no edits were discarded."
        }
    }

    /** Existing enabled ROM projects migrate once; authored source is never regenerated. */
    fun prepare(project: SmEditProject, projectFilePath: String) {
        val patches = project.patches.filter { it.id == PATCH_ID }
        require(patches.size <= 1) { "Duplicate Fast Doors patches cannot share a source module." }
        val repository = AsmProjectWorkspaceRepository()
        val workspace = requireNotNull(repository.loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable." }
        val file = File(workspace.workingDirectory, "src/$FILE")
        val patch = patches.singleOrNull()
        if (file.isFile && !hasGate(file.readText())) {
            require(patch?.enabled != true && SOURCE_KEY !in project.asmWorkspace.patchSourceKeys) {
                "Fast Doors found an existing unmanaged fast_doors.asm. Rename that module or restore the managed gate; no source was overwritten."
            }
            return // A pre-existing ordinary module is not automatically converted by its filename.
        }
        require(file.isFile || SOURCE_KEY !in project.asmWorkspace.patchSourceKeys) {
            "Authored Fast Doors source is missing. Restore fast_doors.asm; custom code was not regenerated."
        }
        if (!file.isFile && patch?.enabled == true) {
            requireBundled(patch)
            repository.createModule(projectFilePath, "fast_doors.asm", initialSource = template(true, AsmSourceBindings.load(workspace.originalDirectory)))
            project.asmWorkspace.patchSourceKeys += SOURCE_KEY
        } else if (file.isFile) {
            require(FILE !in workspace.disabledProjectModuleFileIds) { "Toggle Fast Doors in Patches, not the module browser. Its module must remain included." }
            require(patch != null) { "Fast Doors source has no matching patch. Restore its patch entry before building." }
            requireBundled(patch)
            require(enabled(file.readText()) == patch.enabled) { "Fast Doors source and Enable disagree. Open its patch editor to synchronize before building." }
        }
    }

    private val roots = linkedMapOf("DoorTransitionScrolling" to "bank_80.asm",
        "DoorTransitionFunction_ScrollScreenToAlignment" to "bank_82.asm")

    fun validateNative(workspace: AsmProjectBuildWorkspace) {
        val originals = roots.values.distinct().associateWith { File(workspace.originalDirectory, "src/$it").readText() }
        val current = originals.keys.associateWith { File(workspace.workingDirectory, "src/$it").readText() }
        val catalog = AsmDataPatchSource(originals, AsmSourceBindings.load(workspace.originalDirectory).names)
        for ((root, file) in roots) require(catalog.instructionBlock(file, root, current) == catalog.instructionBlock(file, root, originals)) {
            "Fast Doors: $root changed. Restore the native hook routine or keep this patch disabled; no fixed-offset fallback was applied."
        }
    }

    /** Guard compiled operands as well as source shapes. Claims include unchanged bytes. */
    fun protect(project: SmEditProject, workspace: AsmProjectBuildWorkspace, compiled: AsmCompiledBuildBase): AsmCompiledBuildBase {
        val patch = project.patches.singleOrNull { it.id == PATCH_ID } ?: return compiled
        if (!patch.enabled) return compiled
        validateNative(workspace)
        val labels = AsmSourceBindings.load(workspace.originalDirectory).names
            .canonicalLabels(AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels)
        fun symbol(name: String) = requireNotNull(labels[name]) { "Fast Doors: missing compiled symbol $name." }
        fun pc(address: Int) = ((address ushr 16 and 0x7F) * 0x8000) + (address and 0x7FFF)
        val header = compiled.romBytes.size % 0x8000
        fun word(value: Int) = listOf(value and 255, value ushr 8 and 255)
        fun instruction(op: Int, value: Int) = listOf(op) + word(value)
        fun branch(op: Int, from: Int, target: Int) = listOf(op, (target - from - 2) and 255)
        val scroll = symbol("DoorTransitionScrolling")
        val alignment = symbol("DoorTransitionFunction_ScrollScreenToAlignment")
        val twice = symbol("SMEDIT_FastDoors_ScrollTwice")
        val align = symbol("SMEDIT_FastDoors_Align")
        require(scroll ushr 16 == 0x80 && alignment ushr 16 == 0x82) { "Fast Doors native hook routines must remain in banks 80/82." }
        val expectedScroll = listOf(0x08, 0x8B, 0x4B, 0xAB, 0xC2, 0x30) +
            instruction(0xAD, symbol("DoorDirection")) + listOf(0x29, 3, 0, 0x0A, 0xAA) + instruction(0x20, twice) +
            branch(0x90, scroll + 0x11, symbol("DoorTransitionScrolling_return")) +
            instruction(0xAD, symbol("DoorDestinationXPosition")) + instruction(0x8D, symbol("Layer1XPosition")) +
            instruction(0xAD, symbol("DoorDestinationYPosition")) + instruction(0x8D, symbol("Layer1YPosition")) +
            listOf(0xA9, 0, 0x80) + instruction(0x0C, symbol("DoorTransitionFinishScrolling")) + listOf(0xAB, 0x28, 0x6B) +
            listOf("Right", "Left", "Down", "Up").flatMap { word(symbol("DoorTransitionScrolling_$it")) }
        fun alignHook(index: Int) = listOf(0xEA, 0x08, 0xA2, index, 0, 0x28, 0x22) + word(align) + listOf(align ushr 16)
        val root = "DoorTransitionFunction_ScrollScreenToAlignment"
        val updateBackground = symbol("Calc_Layer2Position_BGScrolls_UpdateBGGraphics_WhenScrolling")
        val updateBackgroundCall = listOf(0x22) + word(updateBackground) + listOf(updateBackground ushr 16)
        val expectedAlignment = listOf(0xF4, 0, 0x8F, 0xAB, 0xAB) + instruction(0xAD, symbol("DoorDirection")) + listOf(0x89, 2, 0) +
            branch(0xD0, alignment + 0x0B, symbol(root + "_vertical")) + instruction(0xAD, symbol("Layer1YPosition") - 1) +
            listOf(0x89, 0, 0xFF) + branch(0xF0, alignment + 0x13, symbol(root + "_done")) + alignHook(4) +
            branch(0x80, alignment + 0x1F, symbol(root + "_return")) + instruction(0xAD, symbol("Layer1XPosition") - 1) +
            listOf(0x89, 0, 0xFF) + branch(0xF0, alignment + 0x27, symbol(root + "_done")) + alignHook(0) +
            updateBackgroundCall + listOf(0x60) + updateBackgroundCall +
            instruction(0xA9, symbol("DoorTransitionFunction_FixDoorsMovingUp")) + instruction(0x8D, symbol("DoorTransitionFunction")) + listOf(0x60)
        fun check(address: Int, bytes: List<Int>) {
            val start = pc(address) + header
            require(start >= header && start + bytes.size <= compiled.romBytes.size && bytes.indices.all { (compiled.romBytes[start + it].toInt() and 255) == bytes[it] }) {
                "Fast Doors: compiled hook/consumer bindings changed at ${patchHex(address, 3)}. Restore the module's checked hooks."
            }
        }
        require(symbol("SMEDIT_FastDoors_ScrollHook") == scroll + 0x0E && symbol("SMEDIT_FastDoors_AlignYHook") == alignment + 0x15 &&
            symbol("SMEDIT_FastDoors_AlignXHook") == alignment + 0x29 && symbol("DoorTransitionScrolling_pointers") == scroll + 0x28) {
            "Fast Doors: hook labels or pointer table moved independently of their native instructions."
        }
        require((scroll and 65535) in 0x8000..(0x10000 - expectedScroll.size) &&
            (alignment and 65535) in 0x8000..(0x10000 - expectedAlignment.size) &&
            symbol("DoorTransitionScrolling_return") == scroll + 0x25 &&
            mapOf("vertical" to 0x21, "scrollUp" to 0x1C, "scrollRight" to 0x30, "return" to 0x33, "done" to 0x38)
                .all { (local, offset) -> symbol(root + "_" + local) == alignment + offset } &&
            listOf("Right", "Left", "Down", "Up").all { symbol("DoorTransitionScrolling_$it") ushr 16 == 0x80 } &&
            symbol("Layer1YPosition") == symbol("Layer1XPosition") + 4) {
            "Fast Doors: native branch targets, scrolling pointer banks, or X/Y RAM layout changed."
        }
        check(scroll, expectedScroll)
        check(alignment, expectedAlignment)
        val baseline = requireNotNull(compiled.curatedBaselineRomBody) { "Fast Doors has no source-only safety baseline." }
        val nativeScroll = expectedScroll.toMutableList().apply {
            val native = instruction(0xFC, symbol("DoorTransitionScrolling_pointers"))
            native.forEachIndexed { i, value -> this[0x0E + i] = value }
        }
        val nativeAlignment = expectedAlignment.toMutableList().apply {
            for ((offset, local, ram) in listOf(Triple(0x15, "scrollUp", "Layer1YPosition"), Triple(0x29, "scrollRight", "Layer1XPosition"))) {
                val target = symbol(root + "_" + local)
                val native = branch(0x30, alignment + offset, target) + instruction(0xCE, symbol(ram)) +
                    branch(0x80, alignment + offset + 5, symbol(root + "_return")) + instruction(0xEE, symbol(ram))
                native.forEachIndexed { i, value -> this[offset + i] = value }
            }
        }
        for ((address, bytes) in listOf(scroll to nativeScroll, alignment to nativeAlignment)) {
            val at = pc(address)
            require(bytes.indices.all { (baseline[at + it].toInt() and 255) == bytes[it] }) {
                "Fast Doors: another authored module changes a native hook/consumer. Resolve that source conflict before enabling."
            }
        }
        val ranges = mutableListOf(AsmSourceOwnedRange(pc(scroll), expectedScroll.size, FILE), AsmSourceOwnedRange(pc(alignment), expectedAlignment.size, FILE))
        data class Helper(val name: String, val free: String, val bank: Int, val limit: Int)
        for (helper in listOf(Helper("SMEDIT_FastDoors_ScrollTwice", "Freespace_Bank80_CD8E", 0x80, 0xFFC0),
            Helper("SMEDIT_FastDoors_Align", "Freespace_Bank82_F70F", 0x82, 0x10000))) {
            val start = symbol(helper.name); val end = symbol(helper.name + "_End"); val bankStart = helper.bank shl 16
            require(start ushr 16 == helper.bank && start >= symbol(helper.free) && end > start && end <= bankStart + helper.limit) {
                "Fast Doors: ${helper.name} must fit its native bank's verified free space."
            }
            val at = pc(start); val length = end - start
            require((at until at + length).all { (compiled.referenceRomBody[it].toInt() and 255) == 255 }) {
                "Fast Doors helper placement overlaps original game data."
            }
            require((at until at + length).all { (baseline[it].toInt() and 255) == 255 }) {
                "Fast Doors helper placement overlaps another authored source allocation. Relocate the helper to unused space."
            }
            ranges += AsmSourceOwnedRange(at, length, FILE)
        }
        require(ranges.indices.all { i -> ranges.indices.all { j -> i == j || ranges[i].pcOffset + ranges[i].length <= ranges[j].pcOffset || ranges[j].pcOffset + ranges[j].length <= ranges[i].pcOffset } }) { "Fast Doors source ranges overlap." }
        for (at in baseline.indices) require(at in 0x7FDC..0x7FDF || baseline[at] == compiled.romBytes[header + at] ||
            ranges.any { at >= it.pcOffset && at < it.pcOffset + it.length }) {
            "Fast Doors emits code outside its checked hooks/helper spans. Keep custom helper code between its start and _End labels."
        }
        // Replace diff-only claims with full-span ownership, retaining all unrelated source edits.
        val retained = compiled.sourceOwnedRanges.flatMap { old ->
            var pieces = listOf(old)
            for (range in ranges) pieces = pieces.flatMap { part ->
                val a = maxOf(part.pcOffset, range.pcOffset); val b = minOf(part.pcOffset + part.length, range.pcOffset + range.length)
                if (a >= b) listOf(part) else listOfNotNull(
                    part.copy(length = a - part.pcOffset).takeIf { it.length > 0 },
                    part.copy(pcOffset = b, length = part.pcOffset + part.length - b).takeIf { it.length > 0 })
            }
            pieces
        }
        return compiled.copy(sourceOwnedRanges = retained + ranges, curatedPatchIds = compiled.curatedPatchIds + PATCH_ID)
    }
}

/** The literal gate and the GUI flag share source Undo/Redo; disabling never rewrites the routine. */
internal class AsmFastDoorsBridge(val workspace: AsmWorkspaceState, private val project: () -> SmEditProject, private val changed: () -> Unit) {
    val installed get() = AsmFastDoorsModule.FILE in workspace.projectModuleFileIds
    private fun patch() = project().patches.single { it.id == AsmFastDoorsModule.PATCH_ID }
    fun setEnabled(enabled: Boolean) {
        require(installed || AsmFastDoorsModule.SOURCE_KEY !in project().asmWorkspace.patchSourceKeys) { "Restore the missing authored Fast Doors module; custom code was not regenerated." }
        if (!installed && !enabled) {
            if (patch().enabled) {
                val anchor = "main.asm"
                val text = workspace.projectSourceText(anchor)
                workspace.publishSourceModelChanges(mapOf(anchor to (text to text)), listOf(flagChange(false)))
            }
            return
        }
        AsmFastDoorsModule.requireBundled(patch())
        workspace.ensureFastDoorsModule(false)
        require(AsmFastDoorsModule.FILE !in workspace.disabledProjectModuleFileIds) { "Re-enable the Fast Doors module in the module browser, then use the patch toggle." }
        val before = workspace.projectSourceText(AsmFastDoorsModule.FILE)
        require(AsmFastDoorsModule.hasGate(before)) { "Rename the existing unmanaged fast_doors.asm before enabling the curated patch; no source was overwritten." }
        val after = AsmFastDoorsModule.withEnabled(before, enabled)
        if (before == after && patch().enabled == enabled && AsmFastDoorsModule.SOURCE_KEY in project().asmWorkspace.patchSourceKeys) return
        workspace.publishSourceModelChanges(mapOf(AsmFastDoorsModule.FILE to (before to after)), listOf(flagChange(enabled)))
    }
    private fun flagChange(enabled: Boolean): AsmSourceModelChange {
        val owner = project(); val patch = patch(); val before = patch.enabled
        val key = AsmFastDoorsModule.SOURCE_KEY
        val ownedBefore = key in owner.asmWorkspace.patchSourceKeys
        val ownedAfter = installed || ownedBefore
        return AsmSourceModelChange({ undo ->
            require(project() === owner && owner.patches.count { it.id == patch.id } == 1 && owner.patches.any { it === patch } && patch.enabled == if (undo) enabled else before) {
                "Fast Doors settings changed; undo newer edits first."
            }
            require((key in owner.asmWorkspace.patchSourceKeys) == if (undo) ownedAfter else ownedBefore) { "Fast Doors source ownership changed; undo newer edits first." }
        }, { undo ->
            patch.enabled = if (undo) before else enabled
            if (if (undo) ownedBefore else ownedAfter) owner.asmWorkspace.patchSourceKeys.add(key) else owner.asmWorkspace.patchSourceKeys.remove(key)
        }, changed)
    }
    fun intercept(file: String, before: String, after: String): Boolean? {
        if (file != AsmFastDoorsModule.FILE) return null
        if (!AsmFastDoorsModule.hasGate(before) && !AsmFastDoorsModule.hasGate(after) &&
            AsmFastDoorsModule.SOURCE_KEY !in project().asmWorkspace.patchSourceKeys) return null
        // Incomplete drafts remain editable; the last GUI flag remains until the literal is repaired.
        val enabled = runCatching { AsmFastDoorsModule.enabled(after) }.getOrNull() ?: patch().enabled
        if (enabled == patch().enabled && AsmFastDoorsModule.SOURCE_KEY in project().asmWorkspace.patchSourceKeys) return null
        workspace.publishSourceModelChanges(mapOf(file to (before to after)), listOf(flagChange(enabled)))
        return true
    }
    fun reconcile() {
        if (!installed) {
            require(AsmFastDoorsModule.SOURCE_KEY !in project().asmWorkspace.patchSourceKeys) { "Restore the missing authored Fast Doors module; custom code was not regenerated." }
            if (patch().enabled) {
                AsmFastDoorsModule.requireBundled(patch())
                workspace.ensureFastDoorsModule(true)
                val text = workspace.projectSourceText(AsmFastDoorsModule.FILE)
                workspace.publishSourceModelChanges(mapOf(AsmFastDoorsModule.FILE to (text to text)), listOf(flagChange(true)))
            }
            return
        }
        val text = workspace.projectSourceText(AsmFastDoorsModule.FILE)
        if (!AsmFastDoorsModule.hasGate(text) && !patch().enabled && AsmFastDoorsModule.SOURCE_KEY !in project().asmWorkspace.patchSourceKeys) return
        val enabled = AsmFastDoorsModule.enabled(text)
        if (patch().enabled != enabled || AsmFastDoorsModule.SOURCE_KEY !in project().asmWorkspace.patchSourceKeys)
            workspace.publishSourceModelChanges(mapOf(AsmFastDoorsModule.FILE to (text to text)), listOf(flagChange(enabled)))
    }
}

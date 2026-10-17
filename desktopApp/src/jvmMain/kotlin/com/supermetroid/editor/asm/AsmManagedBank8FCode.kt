package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomFreeSpaceAllocator

internal interface AsmManagedBank8FRoutine {
    val macroName: String
    val label: String
}

internal fun managedBank8FMacroName(token: String): String? =
    Regex("(SmeditArrival_(?:[0-9A-F]{4}|Project_[0-9A-F]+)_Door_[0-9]+)_Entry(?:_End)?|(SmeditSelectors_(?:[0-9A-F]{4}|Project_[0-9A-F]+)_Condition_[0-9a-f]+)_Predicate(?:_End)?")
        .matchEntire(token)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }

internal fun validateManagedBank8FBody(macroName: String, body: String, marker: String, forbiddenMarkers: Set<String>, allowAssert: Boolean = false) {
    val lines = body.lineSequence().toList()
    require(lines.count { it.trim() == marker } == 1 && lines.none { it.trim() in forbiddenMarkers }) {
        "$macroName: choose exactly one supported executable marker."
    }
    var statements = 0
    for (line in lines) {
        var code = asmCodeBeforeComment(line).trim()
        if (code.isEmpty()) continue
        if (code.matches(Regex("\\.+[A-Za-z_][A-Za-z0-9_]*:?"))) continue
        Regex("^\\.+[A-Za-z_][A-Za-z0-9_]*:[ \\t]*").find(code)?.let { code = code.substring(it.range.last + 1) }
        val match = Regex("([A-Za-z]+)(?:\\.[bwlBWL])?(?:[ \\t]+([^:\"{}]*))?").matchEntire(code)
        val operation = match?.groupValues?.get(1)?.uppercase()
        require(operation in AsmInstructionReference.instructions || operation in setOf("DB", "DW", "DL", "DD", "FILL", "FILLBYTE") || allowAssert && operation == "ASSERT") {
            "$macroName: managed bodies accept instructions, local labels and data/fill only, one per line. " +
                "Placement directives, macro calls and includes require the fixed-address ASM workflow."
        }
        if (operation in setOf("FILL", "FILLBYTE")) {
            val value = AsmPlmDocument.literal(match!!.groupValues[2].trim())
            require(value != null && value in 0..if (operation == "FILL") 1024 else 255) {
                "$macroName: $operation needs a bounded literal."
            }
        }
        statements++
    }
    require(statements > 0) { "$macroName: managed code body is empty." }
}

/** Build-only placement; no addresses or generated labels are written into authored source. */
internal object AsmManagedBank8FCode {
    fun render(routines: List<AsmManagedBank8FRoutine>, start: Int?): String = buildString {
        if (routines.isEmpty()) return@buildString
        appendLine("; SMEDIT managed bank-8F code. Generated; edit the original routine/arrival macro body.")
        if (start == null) {
            // The discovery pass assembles authored source without emitting managed code.
            // Symbols keep raw authored references linkable while we find genuinely available space.
            routines.forEach {
                appendLine("${it.label} = \$8F8000")
                appendLine("${it.label}_End = \$8F8001")
            }
        } else {
            require(start in 0x8F8000..0x8FFFFF) { "Managed code must be placed in bank \$8F." }
            appendLine("pushpc")
            appendLine("org \$${start.toString(16).uppercase()}")
            routines.forEach {
                appendLine("${it.label}:")
                appendLine("fillbyte \$FF")
                appendLine("    %${it.macroName}()")
                appendLine("${it.label}_End:")
                appendLine("assert ${it.label}_End > ${it.label}, \"Managed routine is empty\"")
                appendLine("assert ${it.label}_End-${it.label} <= 1024, \"Managed routine exceeds 1024 bytes\"")
                appendLine("warnpc \$900000")
            }
            appendLine("pullpc")
        }
    }

    fun chooseStart(reference: ByteArray, authored: ByteArray, symbols: ByteArray,
                    routines: List<AsmManagedBank8FRoutine>): Int {
        require(reference.size == authored.size)
        val labels = AsmWlaSymbolParser.parse(symbols.toString(Charsets.UTF_8)).labels
        val managedLabels = routines.map { it.label }.toSet()
        // Reserve complete manually declared code envelopes too, including their unchanged FF bytes.
        val excluded = labels.mapNotNull { (label, start) ->
            val end = labels[label + "_End"]
            if (label !in managedLabels && start in 0x8F8000..0x8FFFFF && end != null && end in (start + 1)..0x900000)
                snesLoRomToPc(start)!! until (snesLoRomToPc(start)!! + end - start) else null
        }
        val available = authored.copyOf()
        val bankStart = snesLoRomToPc(0x8F8000)!!
        val bankEnd = snesLoRomToPc(0x8FFFFF)!! + 1
        require(bankEnd <= available.size) { "ROM has no bank \$8F for managed routines." }
        for (pc in bankStart until bankEnd) if (reference[pc].toInt() and 255 != 255) available[pc] = 0
        val allocator = RomFreeSpaceAllocator(available, { snesLoRomToPc(it) ?: -1 },
            { pcToSnesLoRom(it) ?: -1 }, excludedRanges = excluded)
        return requireNotNull(allocator.reserve(1, listOf(0x8F), "Managed bank-8F code")) {
            "No verified trailing free space in bank \$8F for managed room routines. No ROM was produced."
        }.snesAddress
    }

    fun claims(routines: List<AsmManagedBank8FRoutine>, start: Int, reference: ByteArray,
               rom: ByteArray, symbols: ByteArray): List<AsmSourceOwnedRange> {
        val labels = AsmWlaSymbolParser.parse(symbols.toString(Charsets.UTF_8)).labels
        var expectedStart = start
        return routines.map { routine ->
            val address = requireNotNull(labels[routine.label]) { "Missing managed routine ${routine.label}." }
            val end = requireNotNull(labels[routine.label + "_End"]) { "Missing managed routine boundary." }
            require(address == expectedStart && end > address && end - address <= 1024 && end <= 0x900000) {
                "${routine.macroName}: managed routine escaped its bank/size/placement bounds."
            }
            val pc = snesLoRomToPc(address)!!
            val size = end - address
            require(pc + size <= reference.size && pc + size <= rom.size &&
                (pc until pc + size).all { reference[it].toInt() and 255 == 255 }) {
                "${routine.macroName}: managed placement would overwrite existing reference-ROM data."
            }
            expectedStart = end
            AsmSourceOwnedRange(pc, size, PLM_SOURCE_FILE)
        }
    }
}

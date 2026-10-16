package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.DoorScrollWrite

/** Deliberately bounded, straight-line proof, not a general ASM decompiler. */
internal data class AsmArrivalScrollProgram(
    val writes: List<DoorScrollWrite>,
    val nativeContext: String? = null,
    val delegatesToNative: Boolean = false,
    val finalAccumulator: Int? = null,
    val highByteKnown: Boolean = false,
) {
    companion object {
        fun code(body: String): List<String> = body.lineSequence().map { asmCodeBeforeComment(it).trim() }.filter { it.isNotEmpty() }.toList()

        fun parse(body: String, allowContext: Boolean = true): AsmArrivalScrollProgram? {
            val lines = code(body).toMutableList()
            var context: String? = null
            if (allowContext && lines.size >= 3) {
                val match = Regex("lda\\.w #\\((DoorASM_[A-Za-z0-9_]+)&[${'$'}]FFFF\\)").matchEntire(lines[1])
                if (match != null) {
                    context = match.groupValues[1]
                    if (lines[0].replace(" ", "") != "assert($context>>16)==\$8F" || lines[2] != "sta.b \$12") return null
                    lines.subList(0, 3).clear()
                    if (lines == listOf("jmp.w $context")) return AsmArrivalScrollProgram(emptyList(), context, true)
                }
            }
            if (lines == listOf("rts") || lines == listOf("RTS")) return AsmArrivalScrollProgram(emptyList(), context)
            // Code normalization must preserve the return flags as well as the writes.
            // Routines without the known outer PHP/PLP envelope remain opaque.
            if (lines.size < 3 || !lines.first().equals("php", true) ||
                !lines[lines.lastIndex - 1].equals("plp", true) || !lines.last().equals("rts", true)) return null
            var byteMode = false
            var value: Int? = null
            var highByteKnown = false
            val modes = mutableListOf<Boolean>()
            val writes = mutableListOf<DoorScrollWrite>()
            var ended = false
            for ((index, line) in lines.withIndex()) {
                val match = Regex("([A-Za-z]+)(?:\\.([bwlBWL]))?(?:[ \\t]+(.*))?").matchEntire(line) ?: return null
                val op = match.groupValues[1].uppercase()
                val suffix = match.groupValues[2].uppercase()
                val operand = match.groupValues[3].trim()
                when (op) {
                    "PHP" -> { if (operand.isNotEmpty() || suffix.isNotEmpty()) return null; modes.add(byteMode) }
                    "PLP" -> {
                        if (operand.isNotEmpty() || suffix.isNotEmpty() || modes.size == 1 && index != lines.lastIndex - 1) return null
                        // The outer saved flags must survive until the final PLP. Merely
                        // balancing pushes/pops allows restoring then replacing that frame.
                        byteMode = modes.removeLastOrNull() ?: return null
                    }
                    "SEP", "REP" -> {
                        if (operand != "#\$20" || suffix.isNotEmpty()) return null
                        byteMode = op == "SEP"
                    }
                    "LDA" -> {
                        if (!operand.startsWith('#') || suffix !in setOf("", if (byteMode) "B" else "W")) return null
                        val immediate = AsmPlmDocument.literal(operand.drop(1))?.takeIf { it in 0..if (byteMode) 255 else 65535 } ?: return null
                        value = if (byteMode && highByteKnown) (requireNotNull(value) and 0xFF00) or immediate else immediate
                        if (!byteMode) highByteKnown = true
                    }
                    "STA" -> {
                        if (suffix != "L") return null // Never guess DB or the addressing width.
                        val address = if (operand == "Scrolls") 0x7ECD20 else if (operand.startsWith("Scrolls+"))
                            AsmPlmDocument.literal(operand.removePrefix("Scrolls+"))?.let { 0x7ECD20 + it }
                        else AsmPlmDocument.literal(operand)
                        if (address == null || address !in 0x7ECD20..0x7ECD7F || value == null || !byteMode && !highByteKnown) return null
                        writes.add(DoorScrollWrite(value and 255, address and 255))
                        if (!byteMode) {
                            if (address == 0x7ECD7F) return null
                            writes.add(DoorScrollWrite(value ushr 8, (address and 255) + 1))
                        }
                    }
                    "RTS" -> {
                        if (operand.isNotEmpty() || suffix.isNotEmpty() || index != lines.lastIndex || modes.isNotEmpty()) return null
                        ended = true
                    }
                    else -> return null
                }
            }
            return if (ended) AsmArrivalScrollProgram(writes, context, finalAccumulator = value, highByteKnown = highByteKnown) else null
        }

        fun render(name: String, writes: List<DoorScrollWrite>, context: String?, notes: List<String> = emptyList(),
                   finalAccumulator: Int? = null, highByteKnown: Boolean = false): String = buildString {
            appendLine("macro $name()")
            appendLine("    ${AsmDoorArrivalsSource.MARKER}")
            notes.filter { it.trim() != AsmDoorArrivalsSource.MARKER }.forEach { appendLine("    $it") }
            if (context != null) {
                appendLine("    assert ($context>>16) == \$8F")
                appendLine("    lda.w #($context&\$FFFF)")
                appendLine("    sta.b \$12")
            }
            appendLine("    php")
            appendLine("    sep #\$20")
            writes.forEach {
                appendLine("    lda.b #\$${it.scrollValue.toString(16).uppercase().padStart(2, '0')}")
                appendLine("    sta.l \$7ECD${it.addressLowByte.toString(16).uppercase().padStart(2, '0')}")
            }
            // Splitting a word store across a newly moved row must not change the A
            // register returned to the engine. Restore its proven word or low byte.
            if (finalAccumulator != null) {
                if (highByteKnown) {
                    appendLine("    rep #\$20")
                    appendLine("    lda.w #\$${finalAccumulator.toString(16).uppercase().padStart(4, '0')}")
                } else appendLine("    lda.b #\$${(finalAccumulator and 255).toString(16).uppercase().padStart(2, '0')}")
            }
            appendLine("    plp")
            appendLine("    rts")
            appendLine("endmacro")
        }
    }
}

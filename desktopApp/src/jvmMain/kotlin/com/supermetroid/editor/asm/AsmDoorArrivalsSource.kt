package com.supermetroid.editor.asm

/** Arrival code stays opaque to the connection-data lens, with an explicit engine ABI. */
internal data class AsmManagedDoorArrival(override val macroName: String) : AsmManagedBank8FRoutine {
    override val label = "${macroName}_Entry"
    init { require(macroName.matches(Regex("SmeditArrival_(?:[0-9A-F]{4}|Project_[0-9A-F]+)_Door_[0-9]+"))) { "Invalid managed arrival identity." } }
    fun validateBody(body: String) = validateManagedBank8FBody(macroName, body, AsmDoorArrivalsSource.MARKER,
        setOf(AsmRoomSelectorsSource.MANAGED_PREDICATE_MARKER, AsmRoomSelectorsSource.ASM_PREDICATE_MARKER,
            AsmSelectorPredicateRecognizer.MARKER), allowAssert = true)
}

internal class AsmDoorArrivalsSource(val text: String) {
    private val definitions by lazy {
        val matches = Regex("(?m)^macro (SmeditArrival_[A-Za-z0-9_]+)\\(\\)[ \\t]*(?:;[^\\r\\n]*)?\\r?\\n([\\s\\S]*?)^endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)").findAll(text).toList()
        require(matches.map { it.groupValues[1] }.distinct().size == matches.size) { "Duplicate managed arrival declarations." }
        matches.associateBy { it.groupValues[1] }
    }
    fun routine(label: String, validateBody: Boolean = true): AsmManagedDoorArrival {
        val macro = label.removeSuffix("_Entry")
        val value = AsmManagedDoorArrival(macro)
        require(value.label == label) { "Use the managed arrival's _Entry symbol." }
        val definition = requireNotNull(definitions[macro]) { "Missing managed arrival declaration $macro." }
        if (validateBody) value.validateBody(definition.groupValues[2])
        return value
    }
    fun routines() = definitions.keys.sorted().map { routine("${it}_Entry") }
    fun line(label: String): Int {
        val macro = label.removeSuffix("_Entry")
        val start = requireNotNull(Regex("(?m)^macro ${Regex.escape(macro)}\\(\\)").find(text)) { "Missing arrival declaration $macro." }.range.first
        return text.take(start).count { it == '\n' }
    }
    companion object {
        const val MARKER = "; SMEDIT managed arrival v1"
        fun name(roomId: Int, door: Int, projectId: String? = null) = AsmRoomSourceContext.prefix("SmeditArrival", roomId, projectId) + "Door_$door"
        fun render(roomId: Int, door: Int, originalLabel: String?, projectId: String? = null): String = buildString {
            appendLine("; Arrival code enters in bank \$8F with 16-bit A/X/Y and DB=\$8F; return with RTS, balance your stack.")
            appendLine("; The editor manages placement and entry/end labels, not this code's behavior.")
            appendLine("macro ${name(roomId, door, projectId)}()")
            appendLine("    $MARKER")
            if (originalLabel == null) appendLine("    rts ; No previous arrival behavior.") else {
                appendLine("    assert ($originalLabel>>16) == \$8F")
                appendLine("    lda.w #($originalLabel&\$FFFF) ; Restore the native dispatcher's A/scratch context.")
                appendLine("    sta.b \$12")
                appendLine("    jmp.w $originalLabel ; Preserve the original behavior and caller stack.")
            }
            appendLine("endmacro")
        }
        fun targetAt(lines: List<String>, line: Int): AsmEditorTarget.Room? {
            for (index in line.coerceAtMost(lines.lastIndex) downTo 0) {
                if (lines[index].trimStart().startsWith("endmacro") && index != line) return null
                Regex("^macro SmeditArrival_([0-9A-F]{4})_Door_[0-9]+\\(\\)").find(lines[index])?.let {
                    return AsmEditorTarget.Room(it.groupValues[1].toInt(16))
                }
                if (lines[index].trimStart().startsWith("macro ")) return null
            }
            return null
        }
    }
}

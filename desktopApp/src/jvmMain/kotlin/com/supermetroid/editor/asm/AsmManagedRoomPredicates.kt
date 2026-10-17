package com.supermetroid.editor.asm

/** One inline selector macro body, with a stable symbol derived from its room/state identity. */
internal data class AsmManagedRoomPredicate(override val macroName: String) : AsmManagedBank8FRoutine {
    override val label = "${macroName}_Predicate"

    init {
        require(macroName.matches(Regex("SmeditSelectors_(?:[0-9A-F]{4}|Project_[0-9A-F]+)_Condition_[0-9a-f]+"))) {
            "Managed predicates belong in a room's condition macro, not its order or another declaration."
        }
    }

    fun validateBody(body: String) = validateManagedBank8FBody(macroName, body, AsmRoomSelectorsSource.MANAGED_PREDICATE_MARKER,
        setOf(AsmRoomSelectorsSource.ASM_PREDICATE_MARKER, AsmSelectorPredicateRecognizer.MARKER))
}

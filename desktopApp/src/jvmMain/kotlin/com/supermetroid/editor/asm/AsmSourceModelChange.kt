package com.supermetroid.editor.asm

/** A prepared ownership/config change. Check every participant before changing any model state. */
internal class AsmSourceModelChange(
    val check: (undo: Boolean) -> Unit,
    val set: (undo: Boolean) -> Unit,
    val changed: () -> Unit,
)

internal fun AsmWorkspaceState.publishSourceModelChanges(
    sources: Map<String, Pair<String, String>>, changes: List<AsmSourceModelChange>,
) {
    fun apply(undo: Boolean) {
        changes.forEach { it.check(undo) }
        changes.forEach { it.set(undo) }
    }
    fun notifyChanges() = changes.forEach { it.changed() }
    replaceProjectSources(sources,
        beforeApply = { apply(false) }, afterApply = ::notifyChanges,
        beforeUndo = { apply(true) }, afterUndo = ::notifyChanges,
        beforeRedo = { apply(false) }, afterRedo = ::notifyChanges)
}

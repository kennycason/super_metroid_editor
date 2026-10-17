package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.ui.beamDamageConfigValues

internal data class AsmBeamDamageView(val value: Int?, val problem: String? = null)

/** Per-word source authority; project metadata stores identities, never duplicate damage values. */
internal class AsmBeamDamageBridge(val catalog: AsmBeamDamageSource, val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject, private val changed: () -> Unit) {
    private val lastGood = mutableMapOf<String, Int>()
    val revision get() = workspace.sourceBufferRevision
    private fun text() = workspace.projectSourceText(BEAM_DAMAGE_SOURCE_FILE)
    private fun effectiveKeys(patch: SmPatch): Set<String> = patch.configData.orEmpty().keys.flatMap {
        if (it.endsWith("_charged")) listOf(it) else listOf(it, "${it}_charged")
    }.toSet()
    private fun authored(field: AsmBeamDamageField): Boolean {
        val baseline = runCatching { catalog.original(field.key) }.getOrNull()
        return field.key in project().asmWorkspace.beamDamageSourceKeys || baseline == null ||
            field.value != baseline.value || field.table != baseline.table
    }
    fun view(key: String): AsmBeamDamageView = try {
        val text = text()
        val field = catalog.field(key, text)
        lastGood[key] = field.value
        val aliases = catalog.keys.mapNotNull { runCatching { catalog.field(it, text) }.getOrNull() }.filter { it.span == field.span }
        val conflict = aliases.any(::authored) && project().patches.any { patch ->
            patch.enabled && patch.configType == "beam_damage" && aliases.any { it.key in effectiveKeys(patch) }
        }
        AsmBeamDamageView(field.value, if (conflict) "$key has both ASM and a patch override. Use ASM values or remove the override." else null)
    } catch (error: Exception) {
        AsmBeamDamageView(lastGood[key] ?: runCatching { catalog.original(key).value }.getOrNull(), error.message ?: "Beam source unavailable.")
    }
    fun value(patch: SmPatch, key: String): AsmBeamDamageView {
        val source = view(key)
        if (!patch.enabled || source.problem != null) return source
        return runCatching { source.copy(value = beamDamageConfigValues(patch.configData.orEmpty())[key] ?: source.value) }
            .getOrElse { source.copy(problem = it.message) }
    }
    fun line(key: String?) = if (key == null) catalog.sectionLine(text()) else catalog.line(key, text())
    fun editorLinks(file: String, line: Int): List<AsmEditorLink> {
        if (file != BEAM_DAMAGE_SOURCE_FILE) return emptyList()
        val text = text()
        return catalog.navigationKeysAt(text, line).map { key ->
            val name = com.supermetroid.editor.ui.ALL_BEAMS.single { it.key == key.removeSuffix("_charged") }.name
            AsmEditorLink("Edit $name ${if (key.endsWith("_charged")) "charged " else ""}damage",
                "Reciprocal beam damage", AsmEditorTarget.BeamDamage(key))
        }
    }
    fun edit(key: String, value: Int) = editValues(mapOf(key to value))
    fun editValues(values: Map<String, Int>) {
        if (values.isEmpty()) return
        val before = text()
        val edits = values.map { (key, value) ->
            require(value in 0..65535) { "Beam damage must fit a 16-bit word (0–65535)." }
            require(view(key).problem == null) { view(key).problem ?: "Repair this beam field first." }
            catalog.field(key, before) to value
        }.groupBy { it.first.span }
        require(edits.values.all { group -> group.map { it.second }.distinct().size == 1 }) { "Shared beam records have competing values. No changes were made." }
        if (edits.values.all { group -> group.first().let { it.first.value == it.second } } &&
            project().patches.none { patch -> patch.configType == "beam_damage" && patch.configData.orEmpty().keys.any { it.removeSuffix("_charged") in values.keys.map { key -> key.removeSuffix("_charged") } } }) return
        var after = before
        for ((field, value) in edits.values.map { it.first() }.sortedByDescending { it.first.span.first }) {
            after = after.replaceRange(field.span, literal(value))
        }
        publish(values.keys, before, after)
    }
    fun resetToOriginal() = editValues(catalog.keys.associateWith { catalog.original(it).value })
    fun useSourceValues(key: String) {
        val beam = key.removeSuffix("_charged")
        val keys = setOf(beam, "${beam}_charged")
        keys.forEach { catalog.field(it, text()) } // Repair requires real values, not fallbacks.
        publish(keys, text(), text(), useOverrides = false)
    }
    /** Opening, searching, and unchanged saves never enter this first-edit transaction. */
    fun intercept(file: String, before: String, after: String): Boolean? {
        if (file != BEAM_DAMAGE_SOURCE_FILE || before == after) return null
        val delta = AsmSourceTextChange.between(before, after) ?: return null
        val keys = catalog.keys.filter { key ->
            val old = runCatching { catalog.field(key, before) }.getOrNull() ?: return@filter false
            lastGood[key] = old.value
            val next = runCatching { catalog.field(key, after) }.getOrNull()
            if (next != null) old.value != next.value || old.table != next.table else
                listOf(old.span, old.bindingSpan).any { delta.offset <= it.last + 1 && delta.offset + delta.removed.length >= it.first }
        }.toSet()
        if (keys.isEmpty()) return null
        if (project().asmWorkspace.beamDamageSourceKeys.containsAll(keys) && project().patches.none {
                it.configType == "beam_damage" && it.configData.orEmpty().keys.any { k -> k.removeSuffix("_charged") in keys.map { key -> key.removeSuffix("_charged") } }
            }) return null
        publish(keys, before, after)
        return true
    }
    private fun literal(value: Int) = "$" + value.toString(16).uppercase().padStart(4, '0')

    private fun publish(touched: Set<String>, beforeText: String, proposed: String, useOverrides: Boolean = true) {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Beam source editing requires ASM source mode." }
        var afterText = proposed
        val keys = touched.toMutableSet()
        val retired = mutableSetOf<String>()
        // Legacy normal-only patches also mean charged=3×. When retiring one, preserve
        // the untouched half in source instead of silently discarding its effective value.
        var expanded: Boolean
        do {
            val count = keys.size
            val spans = keys.mapNotNull { runCatching { catalog.field(it, afterText).span }.getOrNull() }.toSet()
            keys.addAll(catalog.keys.filter { runCatching { catalog.field(it, afterText).span in spans }.getOrDefault(false) })
            val beams = keys.map { it.removeSuffix("_charged") }.toSet()
            val participants = owner.patches.filter { it.configType == "beam_damage" }
            val legacy = participants.flatMap { patch -> patch.configData.orEmpty().keys.filter { it in beams && "${it}_charged" !in patch.configData.orEmpty() } }.toSet()
            val migrate = legacy.flatMap { listOf(it, "${it}_charged") }.toSet() - keys
            if (useOverrides) {
                val values = participants.filter { it.enabled }.flatMap { beamDamageConfigValues(it.configData.orEmpty()).entries }
                    .filter { it.key in migrate }.groupBy { it.key }
                require(values.values.all { group -> group.map { it.value }.distinct().size == 1 }) { "Competing beam patch values; resolve them before adopting source." }
                val edits = values.map { (key, group) -> catalog.field(key, afterText) to group.first().value }.groupBy { it.first.span }
                require(edits.values.all { group -> group.map { it.second }.distinct().size == 1 }) { "Shared beam records have competing patch values; no changes were made." }
                require(edits.keys.none { span -> keys.any { runCatching { catalog.field(it, afterText).span == span }.getOrDefault(false) } }) {
                    "A legacy patch shares the edited damage record. Use ASM values to resolve ownership first."
                }
                for ((field, value) in edits.values.map { it.first() }.sortedByDescending { it.first.span.first }) afterText = afterText.replaceRange(field.span, literal(value))
            }
            keys.addAll(migrate)
            retired.addAll(keys)
            expanded = keys.size != count
        } while (expanded)
        val ownershipBefore = keys.intersect(owner.asmWorkspace.beamDamageSourceKeys)
        val participants = owner.patches.filter { it.configType == "beam_damage" && retired.any { k -> k in it.configData.orEmpty() } }
        val old = participants.map { patch -> patch to patch.configData.orEmpty().filterKeys { it in retired } }
        fun check(undo: Boolean) {
            require(project() === owner && owner.asmWorkspace.beamDamageSourceKeys.intersect(keys) == if (undo) keys else ownershipBefore) { "Beam ownership changed; undo newer edits first." }
            for ((patch, data) in old) require(owner.patches.any { it === patch } && patch.configData.orEmpty().filterKeys { it in retired } == if (undo) emptyMap() else data) { "Beam patch values changed; undo newer edits first." }
            require(owner.patches.none { patch -> patch.configType == "beam_damage" && participants.none { it === patch } && retired.any { it in effectiveKeys(patch) } }) { "A newer patch uses these beam fields; undo it first." }
        }
        fun set(undo: Boolean) {
            owner.asmWorkspace.beamDamageSourceKeys.removeAll(keys)
            owner.asmWorkspace.beamDamageSourceKeys.addAll(if (undo) ownershipBefore else keys)
            for ((patch, values) in old) {
                val data = patch.configData ?: mutableMapOf()
                retired.forEach(data::remove)
                if (undo) data.putAll(values)
                patch.configData = data.takeIf { it.isNotEmpty() }
            }
        }
        check(false)
        workspace.replaceProjectSources(mapOf(BEAM_DAMAGE_SOURCE_FILE to (beforeText to afterText)),
            beforeApply = { set(false) }, afterApply = changed,
            beforeUndo = { check(true); set(true) }, afterUndo = changed,
            beforeRedo = { check(false); set(false) }, afterRedo = changed)
    }
}

package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.EnvironmentalDamagePatch
import java.math.BigDecimal

internal data class AsmEnvironmentalDamageView(val value: Int?, val display: String, val problem: String? = null,
    val conflict: Boolean = false, val managedInAsm: Boolean = false)
internal fun environmentalEditorLink(key: String): AsmEditorLink {
    val field = EnvironmentalDamagePatch.FIELDS.single { it.key == key }
    return AsmEditorLink("Edit ${field.label} damage", "Environmental damage", AsmEditorTarget.EnvironmentalDamage(key))
}

/** A rate owns both 16.16 words. Values stay in source; only namespaced identities enter project JSON. */
internal class AsmEnvironmentalDamageBridge(val catalog: AsmEnvironmentalDamageSource, val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject, private val changed: () -> Unit) {
    private val lastGood = mutableMapOf<String, AsmEnvironmentalField>()
    val revision get() = workspace.sourceBufferRevision
    var publisher: AsmPatchSourcePublisher = { _, _, sources, changes -> workspace.publishSourceModelChanges(sources, changes) }
    fun texts() = catalog.originals.keys.associateWith(workspace::projectSourceText)
    private fun display(field: AsmEnvironmentalField): String = field.editableRate?.toString()
        ?: BigDecimal.valueOf(field.raw).multiply(BigDecimal.valueOf(60)).divide(BigDecimal.valueOf(65536)).stripTrailingZeros().toPlainString()
    fun view(key: String): AsmEnvironmentalDamageView = try {
        val texts = texts()
        val field = catalog.field(key, texts)
        lastGood[key] = field
        val shared = catalog.keys.filter { it != key }.any { other -> runCatching { catalog.field(other, texts) }.getOrNull()?.let {
            it.file == field.file && it.words.any { word -> field.words.any { selected -> selected.span == word.span } }
        } == true }
        val conflict = (shared || environmentalSourceKey(key) in project().asmWorkspace.patchSourceKeys || catalog.changed(key, texts)) &&
            project().patches.any { it.enabled && it.configType == EnvironmentalDamagePatch.CONFIG_TYPE && key in it.configData.orEmpty() }
        AsmEnvironmentalDamageView(field.editableRate, display(field), when {
            conflict -> "$key has both ASM and a patch override. Use ASM values or remove the override."
            shared -> "These hazards share source words. Edit the shared ASM directly."
            field.editableRate == null -> "Exact rate is ${display(field)} energy/sec; this control accepts whole 0–9999 rates."
            else -> null
        }, conflict, !conflict && (shared || field.editableRate == null))
    } catch (error: Exception) {
        val field = lastGood[key] ?: runCatching { catalog.original(key) }.getOrNull()
        AsmEnvironmentalDamageView(field?.editableRate, field?.let(::display) ?: "—", error.message ?: "Environmental source unavailable.")
    }
    fun value(patch: SmPatch, key: String): AsmEnvironmentalDamageView {
        val source = view(key)
        val override = patch.configData?.get(key)?.takeIf { patch.enabled } ?: return source
        if (source.problem != null) return source
        return if (override in 0..9999) source.copy(value = override, display = override.toString())
            else source.copy(problem = "Environmental damage must fit 0–9999 energy/sec.")
    }
    fun line(key: String? = null) = catalog.line(key ?: EnvironmentalDamagePatch.HEAT_KEY, texts())
    fun editorLinks(file: String, line: Int): List<AsmEditorLink> {
        if (file !in ENVIRONMENT_SOURCE_FILES) return emptyList()
        val texts = workspace.projectSourceTextsForNavigation(catalog.originals.keys) ?: return emptyList()
        return catalog.navigationKeysAt(file, line, texts).map(::environmentalEditorLink)
    }

    fun edit(key: String, value: Int) = editValues(mapOf(key to value))
    fun editValues(values: Map<String, Int>) {
        if (values.isEmpty()) return
        val before = texts()
        val owner = project()
        val changes = values.mapNotNull { (key, value) ->
            require(value in 0..9999) { "Environmental damage must fit 0–9999 energy/sec." }
            val problem = view(key).problem
            require(problem == null) { problem ?: "Repair this environmental field first." }
            val field = catalog.field(key, before)
            val fixed = EnvironmentalDamagePatch.encodeEnergyPerSecond(value)
            if (fixed == field.fixed && key !in owner.asmWorkspace.disabledPatchValues[EnvironmentalDamagePatch.CONFIG_TYPE].orEmpty() &&
                owner.patches.none { it.configType == EnvironmentalDamagePatch.CONFIG_TYPE && key in it.configData.orEmpty() }) null
            else field to fixed
        }
        if (changes.isEmpty()) return
        val replacements = changes.groupBy { it.first.file }.mapValues { (file, fields) ->
            val edits = fields.flatMap { (field, fixed) -> listOf(field.words[0].span to fixed.lowWord, field.words[1].span to fixed.highWord) }.groupBy { it.first }
            require(edits.values.all { group -> group.map { it.second }.distinct().size == 1 }) { "Shared environmental words have conflicting values. No changes were made." }
            var after = before.getValue(file)
            for ((span, group) in edits.entries.sortedByDescending { it.key.first }) {
                after = after.replaceRange(span, "$" + group.first().second.toString(16).uppercase().padStart(4, '0'))
            }
            before.getValue(file) to after
        }
        publish(changes.map { it.first.key }.toSet(), replacements)
    }
    fun resetToOriginal() = editValues(catalog.keys.associateWith { requireNotNull(catalog.original(it).editableRate) { "Original ASM rate is not a whole GUI value." } })
    fun useSourceValue(key: String) {
        val texts = texts(); val field = catalog.field(key, texts)
        publish(setOf(key), mapOf(field.file to (texts.getValue(field.file) to texts.getValue(field.file))))
    }
    fun prepareIntercept(file: String, before: String, after: String): AsmSourceModelChange? {
        if (file !in ENVIRONMENT_SOURCE_FILES || before == after) return null
        val old = texts() + (file to before); val next = old + (file to after)
        val keys = catalog.changedKeys(old, next)
        if (keys.isEmpty()) return null
        keys.forEach { key -> runCatching { catalog.field(key, old) }.getOrNull()?.let { lastGood[key] = it } }
        val owner = project()
        if (keys.all { environmentalSourceKey(it) in owner.asmWorkspace.patchSourceKeys } && owner.patches.none {
                it.configType == EnvironmentalDamagePatch.CONFIG_TYPE && keys.any { key -> key in it.configData.orEmpty() }
            }) return null
        return preparePublish(keys)
    }
    private fun publish(keys: Set<String>, sources: Map<String, Pair<String, String>>) =
        publisher(EnvironmentalDamagePatch.CONFIG_TYPE, keys, sources, listOf(preparePublish(keys)))
    private fun preparePublish(keys: Set<String>): AsmSourceModelChange {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Environmental source editing requires ASM source mode." }
        val identities = keys.map(::environmentalSourceKey).toSet()
        val ownedBefore = identities.intersect(owner.asmWorkspace.patchSourceKeys)
        val participants = owner.patches.filter { it.configType == EnvironmentalDamagePatch.CONFIG_TYPE && keys.any { key -> key in it.configData.orEmpty() } }
        val old = participants.map { patch -> patch to patch.configData.orEmpty().filterKeys { it in keys } }
        fun check(undo: Boolean) {
            require(project() === owner && owner.asmWorkspace.patchSourceKeys.intersect(identities) == if (undo) identities else ownedBefore) { "Patch source ownership changed; undo newer edits first." }
            for ((patch, values) in old) require(owner.patches.any { it === patch } && patch.configData.orEmpty().filterKeys { it in keys } == if (undo) emptyMap() else values) { "Environmental overrides changed; undo newer edits first." }
            require(owner.patches.none { patch -> patch.configType == EnvironmentalDamagePatch.CONFIG_TYPE && participants.none { it === patch } && keys.any { it in patch.configData.orEmpty() } }) { "A newer patch uses these environmental fields; undo it first." }
        }
        fun set(undo: Boolean) {
            owner.asmWorkspace.patchSourceKeys.removeAll(identities)
            owner.asmWorkspace.patchSourceKeys.addAll(if (undo) ownedBefore else identities)
            for ((patch, values) in old) {
                val data = patch.configData?.toMutableMap() ?: mutableMapOf()
                keys.forEach(data::remove)
                if (undo) data.putAll(values)
                patch.configData = data.takeIf { it.isNotEmpty() }
            }
        }
        return AsmSourceModelChange(::check, ::set, changed)
    }
}

package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.EnvironmentalDamagePatch

internal val NATIVE_PATCH_CONFIG_TYPES = setOf("beam_damage", "boss_stats", EnvironmentalDamagePatch.CONFIG_TYPE) + DATA_PATCH_TYPES
internal typealias AsmPatchSourcePublisher = (String, Set<String>, Map<String, Pair<String, String>>, List<AsmSourceModelChange>) -> Unit

internal data class AsmToggleWord(val file: String, val span: IntRange, val value: Int, val size: Int = 2)

/** Word lenses, not file snapshots: toggles must never revert adjacent code, comments, or relocation. */
internal class AsmPatchToggleFamily(
    val type: String, val keys: Set<String>, val originals: Map<String, String>,
    val words: (String, Map<String, String>) -> List<AsmToggleWord>,
    val changedKeys: (Map<String, String>, Map<String, String>) -> Set<String>,
) {
    fun original(key: String) = words(key, originals).map { it.value }
    fun values(key: String, texts: Map<String, String>) = words(key, texts).map { it.value }
}

internal fun patchToggleFamilies(beam: AsmBeamDamageSource?, boss: AsmBossStatsSource?, environment: AsmEnvironmentalDamageSource?, data: AsmDataPatchSource? = null) = listOfNotNull(
    beam?.let { catalog -> AsmPatchToggleFamily("beam_damage", catalog.keys, mapOf(BEAM_DAMAGE_SOURCE_FILE to catalog.originalText),
        { key, texts -> catalog.field(key, texts.getValue(BEAM_DAMAGE_SOURCE_FILE)).let { listOf(AsmToggleWord(BEAM_DAMAGE_SOURCE_FILE, it.span, it.value)) } },
        { before, after -> catalog.changedKeys(before.getValue(BEAM_DAMAGE_SOURCE_FILE), after.getValue(BEAM_DAMAGE_SOURCE_FILE)) }) },
    boss?.let { catalog -> AsmPatchToggleFamily("boss_stats", catalog.keys, mapOf(ENEMY_HEADER_SOURCE_FILE to catalog.originalText),
        { key, texts -> catalog.field(key, texts.getValue(ENEMY_HEADER_SOURCE_FILE)).words.map { AsmToggleWord(ENEMY_HEADER_SOURCE_FILE, it.span, it.value) } },
        { before, after -> catalog.changedKeys(before.getValue(ENEMY_HEADER_SOURCE_FILE), after.getValue(ENEMY_HEADER_SOURCE_FILE)) }) },
    environment?.let { catalog -> AsmPatchToggleFamily(EnvironmentalDamagePatch.CONFIG_TYPE, catalog.keys, catalog.originals,
        { key, texts -> catalog.field(key, texts).let { field -> field.words.map { AsmToggleWord(field.file, it.span, it.value) } } },
        catalog::changedKeys) },
) + data?.types.orEmpty().map { type ->
    val catalog = requireNotNull(data)
    val keys = catalog.definitions.values.filter { it.type == type }.map { it.key }.toSet()
    AsmPatchToggleFamily(type, keys, catalog.originals,
        { key, texts -> catalog.field(dataPatchKey(type, key), texts).words.map { AsmToggleWord(it.site.file, it.span, it.value, it.site.size) } },
        { before, after -> catalog.changedKeys(before, after, type).map { it.substringAfter(':') }.toSet() })
}

/** A reversible source transaction plus the ordinary patch flag. No build-time hidden byte overlay. */
internal class AsmPatchToggleController(
    private val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject,
    families: List<AsmPatchToggleFamily>,
    private val changed: () -> Unit,
) {
    private val families = families.associateBy { it.type }
    private fun family(type: String) = requireNotNull(families[type]) { "Wait for $type source indexing." }
    private fun texts(family: AsmPatchToggleFamily) = family.originals.keys.associateWith(workspace::projectSourceText)
    fun needsActivation(type: String) = project().patches.any { it.configType == type && !it.enabled } ||
        type in project().asmWorkspace.disabledPatchValues

    /** External source edits (including edits made while SMEDIT was closed) are real customizations too. */
    fun reconcileSourceFlags() {
        for (family in families.values) {
            if (project().patches.none { it.configType == family.type && !it.enabled }) continue
            val before = texts(family)
            val keys = family.keys.filter { key -> runCatching { family.values(key, before) != family.original(key) }.getOrDefault(false) }.toSet()
            if (keys.isNotEmpty()) {
                val file = family.originals.keys.first()
                publishEdits(mapOf(family.type to keys), mapOf(file to (before.getValue(file) to before.getValue(file))), emptyList())
            }
        }
    }

    fun touches(sources: Map<String, Pair<String, String>>): Map<String, Set<String>> = families.values.mapNotNull { family ->
        if (sources.keys.none { it in family.originals }) return@mapNotNull null
        val before = texts(family) + sources.mapValues { it.value.first }
        val after = before + sources.mapValues { it.value.second }
        family.changedKeys(before, after).takeIf { it.isNotEmpty() }?.let { family.type to it }
    }.toMap()

    fun customized(type: String): Boolean {
        val family = family(type)
        val texts = texts(family)
        return project().asmWorkspace.disabledPatchValues[type].orEmpty().isNotEmpty() || family.keys.any { key ->
            // Unreadable custom ASM is not safe to reset without acknowledgement (or to guess at).
            runCatching { family.values(key, texts) != family.original(key) }.getOrDefault(true)
        }
    }

    fun publish(type: String, keys: Set<String>, sources: Map<String, Pair<String, String>>, changes: List<AsmSourceModelChange>) =
        publishEdits(mapOf(type to keys), sources, changes)

    fun publishEdits(touches: Map<String, Set<String>>, sources: Map<String, Pair<String, String>>, changes: List<AsmSourceModelChange>) {
        val staged = sources.toMutableMap()
        val model = changes.toMutableList()
        for ((type, touched) in touches) {
            val family = family(type)
            val before = texts(family)
            val proposed = before + staged.mapValues { it.value.second }
            val saved = project().asmWorkspace.disabledPatchValues[type].orEmpty()
            val restoring = saved.filterKeys { key ->
                // An alias whose numeric word changed follows that edit too. Merely rebinding
                // another field to it must not discard this field's saved customization.
                key !in touched && runCatching { family.values(key, before) == family.values(key, proposed) }.getOrDefault(false)
            }
            val replacements = replaceWords(family, proposed, restoring, requireOriginal = true)
            replacements.forEach { (file, text) -> staged[file] = (staged[file]?.first ?: before.getValue(file)) to text }
            model += ownershipChange(type, restoring.keys, remove = false)
            model += flagChange(type, enabled = true, saved = null)
        }
        workspace.publishSourceModelChanges(staged, model)
    }

    fun setEnabled(patch: SmPatch, enabled: Boolean) {
        if (!enabled && !patch.enabled) return // Repeating Disable must never discard the saved customization.
        val type = requireNotNull(patch.configType)
        val family = family(type)
        val owner = project()
        require(owner.patches.count { it.configType == type } == 1) {
            "Several patches manage $type. Resolve the duplicate patches before toggling their shared source."
        }
        val before = texts(family)
        val saved = owner.asmWorkspace.disabledPatchValues[type]
        require(enabled || saved == null) { "Saved disabled values also exist for this enabled patch. Re-enable or edit it to reconcile them; no values were discarded." }
        val custom = if (enabled) saved.orEmpty() else family.keys.mapNotNull { key ->
            val values = family.values(key, before)
            (key to values).takeIf { values != family.original(key) }
        }.toMap()
        // Include unchanged aliases too: one shared word cannot be reset to two different originals.
        val target = if (enabled) custom else family.keys.associateWith(family::original)
        val after = replaceWords(family, before, target, requireOriginal = enabled)
        val sources = after.mapValues { (file, text) -> before.getValue(file) to text }.ifEmpty {
            // Even a flag-only source action needs an Undo anchor.
            val file = family.originals.keys.first()
            mapOf(file to (before.getValue(file) to before.getValue(file)))
        }
        workspace.publishSourceModelChanges(sources, listOf(
            ownershipChange(type, if (enabled) custom.keys else family.keys, remove = !enabled),
            flagChange(type, enabled, if (enabled) null else custom.takeIf { it.isNotEmpty() }),
        ))
    }

    private fun replaceWords(family: AsmPatchToggleFamily, texts: Map<String, String>, values: Map<String, List<Int>>,
                             requireOriginal: Boolean): Map<String, String> {
        require(family.keys.containsAll(values.keys)) { "Unknown saved ${family.type} fields." }
        val edits = values.flatMap { (key, values) ->
            val words = family.words(key, texts)
            require(values.size == words.size && words.zip(values).all { (word, value) -> value in 0..if (word.size == 1) 255 else 65535 }) { "Invalid saved $key words." }
            require(!requireOriginal || words.map { it.value } == family.original(key)) {
                "$key changed in ASM while disabled. No source was overwritten; reconcile these edits before re-enabling."
            }
            words.zip(values)
        }.groupBy { it.first.file to it.first.span }
        require(edits.values.all { group -> group.map { it.second }.distinct().size == 1 }) {
            "Shared source words have different original values. Restore independent bindings before toggling this patch."
        }
        return edits.entries.groupBy { it.key.first }.mapValues { (file, groups) ->
            var text = texts.getValue(file)
            for (group in groups.sortedByDescending { it.key.second.first }) {
                val (word, value) = group.value.first()
                if (word.value != value) text = text.replaceRange(word.span, "$" + value.toString(16).uppercase().padStart(word.size * 2, '0'))
            }
            text
        }
    }

    private fun ownershipChange(type: String, keys: Set<String>, remove: Boolean): AsmSourceModelChange {
        val owner = project()
        val owned = when (type) {
            "beam_damage" -> owner.asmWorkspace.beamDamageSourceKeys
            "boss_stats" -> owner.asmWorkspace.bossStatsSourceKeys
            else -> owner.asmWorkspace.patchSourceKeys
        }
        val identities = keys.map { if (type in setOf("beam_damage", "boss_stats")) it else dataPatchKey(type, it) }.toSet()
        val before = owned.intersect(identities)
        val after = if (remove) emptySet() else identities
        return AsmSourceModelChange({ undo ->
            require(project() === owner && owned.intersect(identities) == if (undo) after else before) { "Patch source ownership changed; undo newer edits first." }
        }, { undo -> owned.removeAll(identities); owned.addAll(if (undo) before else after) }, {})
    }

    private fun flagChange(type: String, enabled: Boolean, saved: Map<String, List<Int>>?): AsmSourceModelChange {
        val owner = project()
        val participants = owner.patches.filter { it.configType == type }
        val flags = participants.map { it.enabled }
        val before = owner.asmWorkspace.disabledPatchValues[type]
        return AsmSourceModelChange({ undo ->
            val current = owner.patches.filter { it.configType == type }
            require(project() === owner && current.size == participants.size && current.zip(participants).all { it.first === it.second } &&
                participants.withIndex().all { (index, patch) -> patch.enabled == if (undo) enabled else flags[index] } &&
                owner.asmWorkspace.disabledPatchValues[type] == if (undo) saved else before) {
                "Patch settings changed; undo newer edits first."
            }
        }, { undo ->
            participants.forEachIndexed { index, patch -> patch.enabled = if (undo) flags[index] else enabled }
            val snapshot = if (undo) before else saved
            if (snapshot == null) owner.asmWorkspace.disabledPatchValues.remove(type)
            else owner.asmWorkspace.disabledPatchValues[type] = snapshot
        }, changed)
    }
}

/** Fail closed if a disabled source customization changed outside the reciprocal editor. */
internal fun validatePatchToggleSources(project: SmEditProject, families: List<AsmPatchToggleFamily>, texts: Map<String, String>) {
    val byType = families.associateBy { it.type }
    for ((type, saved) in project.asmWorkspace.disabledPatchValues) {
        val family = requireNotNull(byType[type]) { "Disabled $type customizations require their original ASM source." }
        require(project.patches.count { it.configType == type } == 1 && project.patches.none { it.configType == type && it.enabled }) {
            "$type has saved disabled values but is enabled or its patch is missing. Reconcile it before building."
        }
        require(family.keys.containsAll(saved.keys)) { "Unknown disabled $type fields." }
        for ((key, values) in saved) {
            val words = family.words(key, family.originals)
            require(values.size == words.size && words.zip(values).all { (word, value) -> value in 0..if (word.size == 1) 255 else 65535 }) { "Invalid saved $key words." }
        }
    }
    for (family in families) {
        if (project.patches.none { it.configType == family.type && !it.enabled }) continue
        require(project.patches.count { it.configType == family.type } == 1) { "Duplicate ${family.type} patches cannot own disabled source." }
        for (key in family.keys) {
            val original = runCatching { family.original(key) }.getOrNull() ?: continue
            require(family.values(key, texts) == original) {
                "$key changed in ASM while its patch is disabled. Open its visual editor to synchronize before building."
            }
        }
    }
}

package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch

internal data class AsmEnemyTuningView(val value: Int?, val problem: String? = null)

/** Per-field ownership: values are in source, never duplicated in project tuning settings. */
internal class AsmEnemyTuningBridge(val catalog: AsmEnemyTuningSource, val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject, private val changed: () -> Unit) {
    private val lastGood = mutableMapOf<String, Int>()
    val revision get() = workspace.sourceBufferRevision
    private fun texts() = workspace.projectSourceText(ENEMY_HEADER_SOURCE_FILE) to workspace.projectSourceText(ENEMY_DROP_SOURCE_FILE)
    fun view(key: String): AsmEnemyTuningView = try {
        val (headers, drops) = texts()
        val value = catalog.field(key, headers, drops).value
        lastGood[key] = value
        val baseline = runCatching { catalog.original(key) }.getOrNull()
        val field = catalog.field(key, headers, drops)
        val authored = key in project().asmWorkspace.enemyTuningSourceKeys || baseline == null || field.value != baseline.value || field.table != baseline.table
        val conflicting = authored && project().patches.any { it.enabled && it.configType in ENEMY_TUNING_CONFIG_TYPES && key in it.configData.orEmpty() }
        AsmEnemyTuningView(value, if (conflicting) "$key has both authored ASM and a patch override. Undo/remove the patch override first." else null)
    } catch (error: Exception) {
        AsmEnemyTuningView(lastGood[key] ?: runCatching { catalog.original(key).value }.getOrNull(), error.message ?: "Enemy tuning source is unavailable.")
    }
    fun consumers(key: String) = runCatching { val (a, b) = texts(); catalog.tableConsumers(key, a, b) }.getOrDefault(emptyList())
    fun sectionLine(section: AsmEnemyTuningSection): Pair<String, Int> = section.fileId to
        catalog.sectionLine(section, workspace.projectSourceText(section.fileId))
    fun line(key: String): Pair<String, Int> {
        val (a, b) = texts()
        return catalog.line(key, a, b)
    }
    fun editorLinks(fileId: String, line: Int): List<AsmEditorLink> {
        if (fileId !in setOf(ENEMY_HEADER_SOURCE_FILE, ENEMY_DROP_SOURCE_FILE)) return emptyList()
        val (a, b) = texts()
        return catalog.navigationTargetsAt(a, b, fileId, line).map { target ->
            val enemy = com.supermetroid.editor.ui.ENEMY_DEFS.single { it.speciesId == target.speciesId }
            AsmEditorLink("Edit ${enemy.name} ${target.section.title}", "Reciprocal enemy data", target)
        }.distinctBy { it.target }
    }
    fun value(patch: SmPatch, key: String): AsmEnemyTuningView {
        val source = view(key)
        val override = patch.configData?.get(key)?.takeIf { patch.enabled }
        if (override == null) return source
        val (a, b) = texts()
        val field = runCatching { catalog.field(key, a, b) }.getOrNull()
        val baseline = runCatching { catalog.original(key) }.getOrNull()
        return if (key in project().asmWorkspace.enemyTuningSourceKeys || field != null && baseline != null &&
            (field.value != baseline.value || field.table != baseline.table))
            source.copy(problem = "$key has both ASM and a pending patch value. Undo/remove the patch override first.")
        else if (source.problem == null) AsmEnemyTuningView(override) else source
    }
    fun edit(key: String, value: Int) {
        editValues(mapOf(key to value))
    }

    /** Stage a whole action before publishing; aliases must agree, and Undo restores it once. */
    fun editValues(values: Map<String, Int>) {
        if (values.isEmpty()) return
        val owner = project()
        require(catalog.keys.containsAll(values.keys)) { "This advanced enemy field is ROM-only; edit its symbolic ASM argument directly in ASM mode." }
        val (a, b) = texts()
        val requested = values.map { (key, value) ->
            val problem = view(key).problem
            require(problem == null) { problem ?: "Repair this enemy field first." }
            val field = catalog.field(key, a, b)
            require(value in 0..if (field.table == null) 65535 else 255) { "Enemy tuning value is outside its field width." }
            field to value
        }.groupBy { (field, _) -> field.fileId to field.span }
        require(requested.values.all { requests -> requests.map { it.second }.distinct().size == 1 }) {
            "Shared enemy tables have different requested defaults. No changes were made; edit the shared bytes individually or give the species separate tables in ASM."
        }
        val related = catalog.keys.mapNotNull { key -> runCatching { catalog.field(key, a, b) }.getOrNull() }
            .groupBy { it.fileId to it.span }
        val changes = requested.filter { (location, requests) ->
            requests.first().let { (field, value) -> field.value != value } ||
                owner.patches.any { patch -> related[location].orEmpty().any { it.key in patch.configData.orEmpty() } }
        }
        if (changes.isEmpty()) return
        val sources = changes.values.map { it.first() }.groupBy { it.first.fileId }.mapValues { (file, fields) ->
            val before = if (file == ENEMY_HEADER_SOURCE_FILE) a else b
            var after = before
            for ((field, value) in fields.sortedByDescending { it.first.span.first }) {
                val literal = "$" + value.toString(16).uppercase().padStart(if (field.table == null) 4 else 2, '0')
                after = after.replaceRange(field.span, literal)
            }
            before to after
        }
        publish(changes.keys.flatMap { related[it].orEmpty().map { field -> field.key } }.toSet(), sources)
    }

    fun resetToOriginal(section: AsmEnemyTuningSection) {
        editValues(catalog.keys.mapNotNull { key -> runCatching { catalog.original(key) }.getOrNull()
            ?.takeIf { it.section == section }?.let { key to it.value } }.toMap())
    }
    /** Explicit repair for an old GUI override that competes with externally edited ASM. */
    fun useSourceValue(key: String) {
        useSourceValues(setOf(key))
    }
    fun useSourceValues(keys: Set<String>) {
        if (keys.isEmpty()) return
        val (a, b) = texts()
        val fields = keys.map { catalog.field(it, a, b) }
        val related = catalog.keys.filter { candidate -> runCatching { catalog.field(candidate, a, b) }.getOrNull()?.let {
            fields.any { field -> it.fileId == field.fileId && it.span == field.span }
        } == true }.toSet()
        publish(related, fields.map { it.fileId }.distinct().associateWith { file ->
            val text = if (file == ENEMY_HEADER_SOURCE_FILE) a else b
            text to text
        })
    }

    fun removeAdvancedOverrides() {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == com.supermetroid.editor.data.ProjectRomBuildMode.ASM_SOURCE) { "Use the active ASM source workspace first." }
        val participants = owner.patches.filter { it.configType == "enemy_stats" }.mapNotNull { patch ->
            patch.configData.orEmpty().filterKeys { it !in catalog.keys }.takeIf { it.isNotEmpty() }?.let { patch to it }
        }
        if (participants.isEmpty()) return
        fun check(undo: Boolean) {
            require(project() === owner && participants.all { (patch, values) -> owner.patches.any { it === patch } &&
                patch.configData.orEmpty().filterKeys { it !in catalog.keys } == if (undo) emptyMap() else values }) { "Advanced enemy overrides changed; undo newer edits first." }
        }
        fun set(undo: Boolean) { participants.forEach { (patch, values) ->
            val data = patch.configData ?: mutableMapOf()
            values.keys.forEach(data::remove)
            if (undo) data.putAll(values)
            patch.configData = data.takeIf { it.isNotEmpty() }
        } }
        val text = workspace.projectSourceText(ENEMY_HEADER_SOURCE_FILE)
        check(false)
        workspace.replaceProjectSources(mapOf(ENEMY_HEADER_SOURCE_FILE to (text to text)),
            beforeApply = { set(false) }, afterApply = changed,
            beforeUndo = { check(true); set(true) }, afterUndo = changed,
            beforeRedo = { check(false); set(false) }, afterRedo = changed)
    }

    /** Called only by actual text editing. Opening/searching/unchanged Save never adopts anything. */
    fun intercept(fileId: String, before: String, after: String): Boolean? {
        if (fileId !in setOf(ENEMY_HEADER_SOURCE_FILE, ENEMY_DROP_SOURCE_FILE) || before == after) return null
        val (a, b) = texts()
        val nextA = if (fileId == ENEMY_HEADER_SOURCE_FILE) after else a
        val nextB = if (fileId == ENEMY_DROP_SOURCE_FILE) after else b
        val delta = AsmSourceTextChange.between(before, after) ?: return null
        val start = delta.offset
        val end = start + delta.removed.length
        val keys = catalog.keys.filter { key ->
            val old = runCatching { catalog.field(key, a, b) }.getOrNull() ?: return@filter false
            lastGood[key] = old.value
            val next = runCatching { catalog.field(key, nextA, nextB) }.getOrNull()
            if (next != null) old.value != next.value || old.table != next.table
            else {
                val ranges = if (fileId == old.fileId) listOf(old.span) else if (fileId == ENEMY_HEADER_SOURCE_FILE) listOfNotNull(old.bindingSpan) else emptyList()
                ranges.any { start <= it.last + 1 && end >= it.first }
            }
        }.toSet()
        if (keys.isEmpty()) return null
        if (project().asmWorkspace.enemyTuningSourceKeys.containsAll(keys) && project().patches.none { patch ->
                patch.configType in ENEMY_TUNING_CONFIG_TYPES && keys.any { it in patch.configData.orEmpty() }
            }) return null // Ordinary typing keeps the editor's normal text-undo granularity.
        publish(keys, mapOf(fileId to (before to after)))
        return true
    }

    private fun publish(keys: Set<String>, sources: Map<String, Pair<String, String>>) {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == com.supermetroid.editor.data.ProjectRomBuildMode.ASM_SOURCE) { "Enemy source editing requires the active ASM source build." }
        val ownershipBefore = keys.intersect(owner.asmWorkspace.enemyTuningSourceKeys)
        val participants = owner.patches.filter { it.configType in ENEMY_TUNING_CONFIG_TYPES && keys.any { key -> key in it.configData.orEmpty() } }
        val before = participants.map { patch -> patch to keys.mapNotNull { key -> patch.configData?.get(key)?.let { key to it } }.toMap() }
        fun check(undo: Boolean) {
            require(project() === owner && owner.asmWorkspace.enemyTuningSourceKeys.intersect(keys) == if (undo) keys else ownershipBefore) { "Enemy tuning ownership changed; undo newer edits first." }
            for ((patch, values) in before) {
                require(owner.patches.any { it === patch } && keys.mapNotNull { key -> patch.configData?.get(key)?.let { key to it } }.toMap() == if (undo) emptyMap() else values) { "Enemy patch values changed; undo newer tuning edits first." }
            }
            require(owner.patches.none { patch -> patch.configType in ENEMY_TUNING_CONFIG_TYPES &&
                participants.none { it === patch } && keys.any { it in patch.configData.orEmpty() } }) {
                "A newer enemy patch uses these fields; undo it before restoring source ownership."
            }
        }
        fun set(undo: Boolean) {
            owner.asmWorkspace.enemyTuningSourceKeys.removeAll(keys)
            owner.asmWorkspace.enemyTuningSourceKeys.addAll(if (undo) ownershipBefore else keys)
            for ((patch, values) in before) {
                val data = patch.configData ?: mutableMapOf()
                keys.forEach(data::remove)
                if (undo) data.putAll(values)
                patch.configData = data.takeIf { it.isNotEmpty() }
            }
        }
        check(false)
        workspace.replaceProjectSources(sources, beforeApply = { set(false) }, afterApply = changed,
            beforeUndo = { check(true); set(true) }, afterUndo = changed,
            beforeRedo = { check(false); set(false) }, afterRedo = changed)
    }
}

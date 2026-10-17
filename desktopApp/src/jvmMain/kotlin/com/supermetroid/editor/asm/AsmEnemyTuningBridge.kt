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
        val conflicting = authored && project().patches.any { it.enabled && it.configType in setOf("enemy_stats", "enemy_drops") && key in it.configData.orEmpty() }
        AsmEnemyTuningView(value, if (conflicting) "$key has both authored ASM and a patch override. Undo/remove the patch override first." else null)
    } catch (error: Exception) {
        AsmEnemyTuningView(lastGood[key] ?: runCatching { catalog.original(key).value }.getOrNull(), error.message ?: "Enemy tuning source is unavailable.")
    }
    fun consumers(key: String) = runCatching { val (a, b) = texts(); catalog.dropConsumers(key, a, b) }.getOrDefault(emptyList())
    fun sectionLine(section: AsmEnemyTuningSection): Pair<String, Int> = section.fileId to
        catalog.sectionLine(section, workspace.projectSourceText(section.fileId))
    fun line(key: String): Pair<String, Int> {
        val (a, b) = texts()
        val field = catalog.field(key, a, b)
        val text = if (field.fileId == ENEMY_HEADER_SOURCE_FILE) a else b
        return field.fileId to text.take(field.span.first).count { it == '\n' }
    }
    fun editorLinks(fileId: String, line: Int): List<AsmEditorLink> {
        if (fileId !in setOf(ENEMY_HEADER_SOURCE_FILE, ENEMY_DROP_SOURCE_FILE)) return emptyList()
        val (a, b) = texts()
        val text = if (fileId == ENEMY_HEADER_SOURCE_FILE) a else b
        var start = 0
        repeat(line.coerceAtLeast(0)) { start = text.indexOf('\n', start).takeIf { it >= 0 }?.plus(1) ?: text.length }
        val end = text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length
        return catalog.keys.mapNotNull { key ->
            val field = runCatching { catalog.field(key, a, b) }.getOrNull() ?: return@mapNotNull null
            if (field.fileId != fileId || field.span.first !in start..end) return@mapNotNull null
            val enemy = com.supermetroid.editor.ui.ENEMY_DEFS.single { it.speciesId == field.speciesId }
            AsmEditorLink("Edit ${enemy.name} ${if (field.table == null) "stats" else "drops"}", "Reciprocal enemy data",
                AsmEditorTarget.EnemyTuning(field.speciesId, field.table != null))
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
        val owner = project()
        require(key in catalog.keys) { "This advanced enemy field is ROM-only; edit its symbolic ASM argument directly in ASM mode." }
        val (a, b) = texts()
        require(view(key).problem == null) { view(key).problem ?: "Repair this enemy field first." }
        val field = catalog.field(key, a, b)
        val related = catalog.keys.filter { candidate ->
            val other = runCatching { catalog.field(candidate, a, b) }.getOrNull()
            other?.fileId == field.fileId && other.span == field.span
        }.toSet()
        val hasLegacy = owner.patches.any { patch -> related.any { it in patch.configData.orEmpty() } }
        if (field.value == value && !hasLegacy) return
        val (afterA, afterB) = catalog.edit(key, value, a, b)
        publish(related, mapOf(field.fileId to (if (field.fileId == ENEMY_HEADER_SOURCE_FILE) a to afterA else b to afterB)))
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
                patch.configType in setOf("enemy_stats", "enemy_drops") && keys.any { it in patch.configData.orEmpty() }
            }) return null // Ordinary typing keeps the editor's normal text-undo granularity.
        publish(keys, mapOf(fileId to (before to after)))
        return true
    }

    private fun publish(keys: Set<String>, sources: Map<String, Pair<String, String>>) {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == com.supermetroid.editor.data.ProjectRomBuildMode.ASM_SOURCE) { "Enemy source editing requires the active ASM source build." }
        val ownershipBefore = keys.intersect(owner.asmWorkspace.enemyTuningSourceKeys)
        val participants = owner.patches.filter { it.configType in setOf("enemy_stats", "enemy_drops") && keys.any { key -> key in it.configData.orEmpty() } }
        val before = participants.map { patch -> patch to keys.mapNotNull { key -> patch.configData?.get(key)?.let { key to it } }.toMap() }
        fun check(undo: Boolean) {
            require(project() === owner && owner.asmWorkspace.enemyTuningSourceKeys.intersect(keys) == if (undo) keys else ownershipBefore) { "Enemy tuning ownership changed; undo newer edits first." }
            for ((patch, values) in before) {
                require(owner.patches.any { it === patch } && keys.mapNotNull { key -> patch.configData?.get(key)?.let { key to it } }.toMap() == if (undo) emptyMap() else values) { "Enemy patch values changed; undo newer tuning edits first." }
            }
            require(owner.patches.none { patch -> patch.configType in setOf("enemy_stats", "enemy_drops") &&
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

package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch

internal data class AsmBossStatsView(val value: Int?, val problem: String? = null, val conflict: Boolean = false)

internal fun bossStatsEditorLink(key: String): AsmEditorLink {
    val field = com.supermetroid.editor.ui.ALL_BOSS_FIELDS.single { it.key == key }
    val boss = com.supermetroid.editor.ui.BOSS_DEFS.single { it.fields.any { field -> field.key == key } }
    val label = if (field.label.startsWith(boss.name)) field.label else "${boss.name} ${field.label}"
    return AsmEditorLink("Edit $label", "Boss stat", AsmEditorTarget.BossStats(key))
}

/** Per-field source authority for boss HP/damage; project metadata stores identities, never duplicate values. */
internal class AsmBossStatsBridge(
    val catalog: AsmBossStatsSource, val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject, private val changed: () -> Unit,
) {
    private val lastGood = mutableMapOf<String, Int>()
    val revision get() = workspace.sourceBufferRevision
    private fun text() = workspace.projectSourceText(ENEMY_HEADER_SOURCE_FILE)

    fun view(key: String): AsmBossStatsView = try {
        val text = text()
        val field = catalog.field(key, text)
        lastGood[key] = field.value
        val conflict = (key in project().asmWorkspace.bossStatsSourceKeys || catalog.changed(key, text)) && project().patches.any { patch ->
            patch.enabled && patch.configType == "boss_stats" && key in patch.configData.orEmpty()
        }
        AsmBossStatsView(
            field.value,
            when {
                conflict -> "$key has both ASM and a patch override. Use ASM values or remove the override."
                field.sharedValue == null -> "Sub-species have different values; managed in ASM. Edit their source individually or restore matching values to use this control."
                else -> null
            }, conflict,
        )
    } catch (error: Exception) {
        AsmBossStatsView(
            lastGood[key] ?: runCatching { catalog.original(key).value }.getOrNull(),
            error.message ?: "Boss stats source unavailable.",
        )
    }

    fun value(patch: SmPatch, key: String): AsmBossStatsView {
        val source = view(key)
        if (!patch.enabled || source.problem != null) return source
        return runCatching {
            source.copy(value = patch.configData?.get(key) ?: source.value)
        }.getOrElse { source.copy(problem = it.message) }
    }

    fun line(key: String?) = if (key == null) catalog.sectionLine(text()) else catalog.line(key, text())

    fun editorLinks(file: String, line: Int): List<AsmEditorLink> {
        if (file != ENEMY_HEADER_SOURCE_FILE) return emptyList()
        val text = text()
        return catalog.navigationKeysAt(text, line).map(::bossStatsEditorLink).distinctBy { it.target }
    }

    fun edit(key: String, value: Int) = editValues(mapOf(key to value))

    fun editValues(values: Map<String, Int>) {
        if (values.isEmpty()) return
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) {
            "Boss stats source editing requires ASM source mode."
        }
        val text = text()
        val requested = values.map { (key, value) ->
            require(value in 0..65535) { "Boss stat must fit a 16-bit word (0–65535)." }
            require(view(key).problem == null) { view(key).problem ?: "Repair this boss field first." }
            catalog.field(key, text) to value
        }
        val edits = requested.flatMap { (field, value) -> field.words.map { it to value } }.groupBy { it.first.span }
        require(edits.values.all { group -> group.map { it.second }.distinct().size == 1 }) { "Shared boss fields have competing values. No changes were made." }
        val changes = edits.filter { (_, group) -> group.first().let { it.first.value != it.second } }
        val retiring = owner.patches.any { it.configType == "boss_stats" && values.keys.any { key -> key in it.configData.orEmpty() } }
        if (changes.isEmpty() && !retiring) return
        var after = text
        for ((word, value) in changes.values.map { it.first() }.sortedByDescending { it.first.span.first }) {
            val literal = "$" + value.toString(16).uppercase().padStart(4, '0')
            after = after.replaceRange(word.span, literal)
        }
        publish(values.keys, text, after)
    }

    fun resetToOriginal() = editValues(catalog.keys.associateWith { catalog.original(it).value })

    fun useSourceValues(key: String) {
        val keys = setOf(key)
        catalog.field(key, text()) // Validate the field is readable
        publish(keys, text(), text())
    }

    fun intercept(file: String, before: String, after: String): Boolean? {
        val change = prepareIntercept(file, before, after) ?: return null
        workspace.publishSourceModelChanges(mapOf(file to (before to after)), listOf(change))
        return true
    }

    fun prepareIntercept(file: String, before: String, after: String): AsmSourceModelChange? {
        if (file != ENEMY_HEADER_SOURCE_FILE || before == after) return null
        val keys = catalog.changedKeys(before, after)
        if (keys.isEmpty()) return null
        keys.forEach { key -> runCatching { catalog.field(key, before).value }.getOrNull()?.let { lastGood[key] = it } }
        if (project().asmWorkspace.bossStatsSourceKeys.containsAll(keys) && project().patches.none {
                it.configType == "boss_stats" && keys.any { k -> k in it.configData.orEmpty() }
            }) return null
        return preparePublish(keys)
    }

    private fun publish(touched: Set<String>, beforeText: String, proposed: String) {
        workspace.publishSourceModelChanges(mapOf(ENEMY_HEADER_SOURCE_FILE to (beforeText to proposed)), listOf(preparePublish(touched)))
    }

    private fun preparePublish(touched: Set<String>): AsmSourceModelChange {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) {
            "Boss stats source editing requires ASM source mode."
        }
        val keys = touched.toMutableSet()
        val ownershipBefore = keys.intersect(owner.asmWorkspace.bossStatsSourceKeys)
        val participants = owner.patches.filter { it.configType == "boss_stats" && keys.any { k -> k in it.configData.orEmpty() } }
        val old = participants.map { patch -> patch to patch.configData.orEmpty().filterKeys { it in keys } }
        fun check(undo: Boolean) {
            require(project() === owner && owner.asmWorkspace.bossStatsSourceKeys.intersect(keys) == if (undo) keys else ownershipBefore) {
                "Boss stats ownership changed; undo newer edits first."
            }
            for ((patch, data) in old) require(owner.patches.any { it === patch } &&
                patch.configData.orEmpty().filterKeys { it in keys } == if (undo) emptyMap() else data) {
                "Boss stats patch values changed; undo newer edits first."
            }
            require(owner.patches.none { patch -> patch.configType == "boss_stats" &&
                participants.none { it === patch } && keys.any { it in patch.configData.orEmpty() } }) {
                "A newer patch uses these boss fields; undo it first."
            }
        }
        fun set(undo: Boolean) {
            owner.asmWorkspace.bossStatsSourceKeys.removeAll(keys)
            owner.asmWorkspace.bossStatsSourceKeys.addAll(if (undo) ownershipBefore else keys)
            for ((patch, values) in old) {
                val data = patch.configData ?: mutableMapOf()
                keys.forEach(data::remove)
                if (undo) data.putAll(values)
                patch.configData = data.takeIf { it.isNotEmpty() }
            }
        }
        return AsmSourceModelChange(::check, ::set, changed)
    }
}

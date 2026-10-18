package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.ControllerBindings
import com.supermetroid.editor.ui.BOMB_CONFIG_TYPE
import com.supermetroid.editor.ui.BOMB_MAX_ACTIVE_KEY

internal data class DataPatchView(val value: Int?, val problem: String? = null, val conflict: Boolean = false)

/** Shared native-data transactions; each finite field codec still defines its own words and bindings. */
internal class AsmDataPatchBridge(val catalog: AsmDataPatchSource, val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject, private val changed: () -> Unit) {
    private val lastGood = mutableMapOf<String, Int>()
    val revision get() = workspace.sourceBufferRevision
    var publisher: AsmPatchSourcePublisher = { _, _, sources, changes -> workspace.publishSourceModelChanges(sources, changes) }
    fun texts() = catalog.originals.keys.associateWith(workspace::projectSourceText)
    fun view(type: String, key: String): DataPatchView {
        val id = dataPatchKey(type, key)
        return try {
            val texts = texts(); val field = catalog.field(id, texts)
            catalog.guards(field, texts) // Invalid consumer drafts lock their associated control, not the whole editor.
            val conflict = (id in project().asmWorkspace.patchSourceKeys || catalog.changed(id, texts)) && project().patches.any {
                it.enabled && it.configType == type && (key in it.configData.orEmpty() || key == "seconds" && it.configValue != null)
            }
            if (type == ControllerBindings.CONFIG_TYPE) {
                catalog.controllerInterface(texts)
                val mappings = (0..1).map { part -> ControllerBindings.slots.associate {
                    it.key to catalog.field(dataPatchKey(type, it.key), texts).words[part].value
                }.also(ControllerBindings::requireMapping) }
                require(mappings[0] == mappings[1]) { "New-game and Options reset defaults differ; controller defaults are managed in ASM. Reset to original or edit both paths together." }
            }
            val value = field.value
            if (value != null) lastGood[id] = value
            DataPatchView(value, when {
                conflict -> "This field has both ASM and a patch override. Use ASM values to choose the source."
                field.values.distinct().size > 1 -> "The native sites have independent values (${field.values.joinToString()}); managed in ASM. Reset to original or edit their source individually."
                value == null -> "The exact ASM value is outside this control's supported format/range; managed in ASM."
                else -> null
            }, conflict)
        } catch (error: Exception) {
            DataPatchView(lastGood[id] ?: runCatching { catalog.original(id).value }.getOrNull(), error.message ?: "Source unavailable.")
        }
    }
    fun value(patch: SmPatch, key: String): DataPatchView {
        val type = requireNotNull(patch.configType); val source = view(type, key)
        if (!patch.enabled || source.problem != null) return source
        return runCatching {
            val texts = texts()
            val fields = catalog.definitions.filterValues { it.type == type }.keys.associateWith { catalog.field(it, texts) }
            val words = dataPatchOverrides(patch, fields)[dataPatchKey(type, key)] ?: return source
            source.copy(value = fields.getValue(dataPatchKey(type, key)).definition.decode(words.first()))
        }.getOrElse { source.copy(problem = it.message) }
    }
    fun line(type: String, key: String? = null): Pair<String, Int> = catalog.line(
        key?.let { dataPatchKey(type, it) } ?: catalog.definitions.values.first { it.type == type }.id, texts())
    fun editorLinks(file: String, line: Int) = catalog.editorLinks(file, line, texts())

    fun editValues(type: String, requested: Map<String, Int>) {
        if (requested.isEmpty()) return
        val before = texts()
        val fields = catalog.definitions.filterValues { it.type == type }.keys.associateWith { catalog.field(it, before) }
        requested.keys.forEach { require(view(type, it).problem == null) { view(type, it).problem ?: "Repair this source first." } }
        // Re-entering the native slot limit alone is inspection, not a request to retime bombs.
        // Explicit pending count overrides still retain their coupled cooldown semantics.
        if (type == BOMB_CONFIG_TYPE && requested.keys == setOf(BOMB_MAX_ACTIVE_KEY)) {
            val id = dataPatchKey(type, BOMB_MAX_ACTIVE_KEY)
            if (requested[BOMB_MAX_ACTIVE_KEY] == fields.getValue(id).value && !pending(id) &&
                BOMB_MAX_ACTIVE_KEY !in project().asmWorkspace.disabledPatchValues[type].orEmpty()) return
        }
        val proposal = SmPatch("proposal", "proposal", configType = type, configData = requested.toMutableMap())
        val values = dataPatchOverrides(proposal, fields).filter { (id, words) ->
            fields.getValue(id).values != words || pendingDiffers(id, words) ||
                id.substringAfter(':') in project().asmWorkspace.disabledPatchValues[type].orEmpty()
        }
        if (values.isEmpty()) return
        publish(type, values, before)
    }
    fun reset(type: String) {
        val before = texts()
        val values = catalog.definitions.filterValues { it.type == type }.keys.associateWith { catalog.original(it).values }
        if (values.all { (id, words) -> catalog.field(id, before).values == words && !pendingDiffers(id, words) } &&
            project().asmWorkspace.disabledPatchValues[type].orEmpty().isEmpty()) return
        publish(type, values, before)
    }
    fun useSource(type: String, key: String) {
        val id = dataPatchKey(type, key); val before = texts()
        publish(type, mapOf(id to catalog.field(id, before).values), before)
    }
    private fun pending(id: String): Boolean {
        val def = catalog.definitions.getValue(id)
        return project().patches.any { it.configType == def.type && (def.key in it.configData.orEmpty() || def.key == "seconds" && it.configValue != null) }
    }
    private fun pendingDiffers(id: String, words: List<Int>): Boolean {
        val def = catalog.definitions.getValue(id)
        return project().patches.filter { it.configType == def.type }.any { patch ->
            listOfNotNull(patch.configData?.get(def.key), patch.configValue?.takeIf { def.key == "seconds" }).any { value ->
                runCatching { val encoded = def.encode(value); words.any { it != encoded } }.getOrDefault(true)
            }
        }
    }
    fun prepareIntercept(file: String, before: String, after: String): List<AsmSourceModelChange> {
        if (file !in catalog.originals || before == after) return emptyList()
        val texts = texts()
        val keys = catalog.changedKeys(texts + (file to before), texts + (file to after))
        return keys.groupBy { catalog.definitions.getValue(it).type }.mapNotNull { (_, ids) ->
            if (ids.all { it in project().asmWorkspace.patchSourceKeys && !pending(it) }) null else preparePublish(ids.toSet())
        }
    }
    private fun publish(type: String, values: Map<String, List<Int>>, before: Map<String, String>) {
        val edits = values.flatMap { (id, values) ->
            catalog.field(id, before).words.zip(values)
        }.groupBy { it.first.site.file to it.first.span }
        require(edits.values.all { group -> group.map { it.second }.distinct().size == 1 }) { "Shared patch words have competing values." }
        val sources = edits.entries.groupBy { it.key.first }.mapValues { (file, edits) ->
            var after = before.getValue(file)
            for (edit in edits.sortedByDescending { it.key.second.first }) {
                val (word, value) = edit.value.first(); val span = edit.key.second
                if (word.value != value) after = after.replaceRange(span, "$" + value.toString(16).uppercase().padStart(word.site.size * 2, '0'))
            }
            before.getValue(file) to after
        }
        publisher(type, values.keys.map { it.substringAfter(':') }.toSet(), sources, listOf(preparePublish(values.keys)))
    }
    private fun preparePublish(ids: Set<String>): AsmSourceModelChange {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Native patch editing requires ASM source mode." }
        val ownedBefore = owner.asmWorkspace.patchSourceKeys.intersect(ids)
        data class Config(val patch: SmPatch, val data: Map<String, Int>, val scalar: Int?, val retiresScalar: Boolean)
        val states = owner.patches.mapNotNull { patch ->
            val keys = ids.map { catalog.definitions.getValue(it) }.filter { it.type == patch.configType }.map { it.key }.toSet()
            if (keys.isEmpty()) null else Config(patch, patch.configData.orEmpty().filterKeys { it in keys }, patch.configValue, "seconds" in keys)
        }
        fun check(undo: Boolean) {
            require(project() === owner && owner.asmWorkspace.patchSourceKeys.intersect(ids) == if (undo) ids else ownedBefore) { "Patch source ownership changed; undo newer edits first." }
            for (state in states) {
                val patch = state.patch
                require(owner.patches.any { it === patch } && patch.configData.orEmpty().filterKeys { dataPatchKey(requireNotNull(patch.configType), it) in ids } ==
                    if (undo) emptyMap() else state.data) { "Patch overrides changed; undo newer edits first." }
                require(!state.retiresScalar || patch.configValue == if (undo) null else state.scalar) { "Timer override changed; undo newer edits first." }
            }
            require(owner.patches.none { patch -> states.none { it.patch === patch } && ids.any { id ->
                val def = catalog.definitions.getValue(id)
                patch.configType == def.type && (def.key in patch.configData.orEmpty() || def.key == "seconds" && patch.configValue != null)
            } }) { "A newer patch owns these fields; undo it first." }
        }
        fun set(undo: Boolean) {
            owner.asmWorkspace.patchSourceKeys.removeAll(ids); owner.asmWorkspace.patchSourceKeys.addAll(if (undo) ownedBefore else ids)
            for (state in states) {
                val data = state.patch.configData.orEmpty().filterKeys { dataPatchKey(requireNotNull(state.patch.configType), it) !in ids }.toMutableMap()
                if (undo) data.putAll(state.data)
                state.patch.configData = data.takeIf { it.isNotEmpty() }
                if (state.retiresScalar) state.patch.configValue = if (undo) state.scalar else null
            }
        }
        return AsmSourceModelChange(::check, ::set, changed)
    }
}

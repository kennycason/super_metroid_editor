package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*

internal enum class AsmSelectorProjectionStatus { CURRENT, ASM_ONLY, RECOGNIZED, STALE, UNAVAILABLE }
internal data class AsmRoomSelectorsView(val resource: SourceRoomSelectors?, val problem: String?,
                                       val conversion: AsmSelectorConversionPreview? = null) {
    val status get() = if (conversion != null) AsmSelectorProjectionStatus.RECOGNIZED
        else if (problem == null && resource?.routines?.isNotEmpty() == true) AsmSelectorProjectionStatus.ASM_ONLY
        else if (problem == null && resource != null) AsmSelectorProjectionStatus.CURRENT
        else if (resource != null) AsmSelectorProjectionStatus.STALE else AsmSelectorProjectionStatus.UNAVAILABLE
}
internal fun SourceRoomSelectors.projectInto(room: RoomEdits) {
    room.asmSelectorOrderProjection = order
    room.states.forEach { it.asmConditionProjection = conditions[it.id] }
}

internal class AsmRoomSelectorsBridge(private val catalog: AsmPlmSourceCatalog, private val workspace: AsmWorkspaceState,
                                      private val project: () -> SmEditProject, private val changed: () -> Unit) {
    private var revision: Pair<String, AsmRoomSelectorsSource>? = null
    private val lastGood = mutableMapOf<Int, SourceRoomSelectors>()
    private fun source(): AsmRoomSelectorsSource {
        val text = workspace.projectSourceText(PLM_SOURCE_FILE)
        return revision?.takeIf { it.first == text }?.second ?: AsmRoomSelectorsSource(text).also { revision = text to it }
    }
    fun isManaged(roomId: Int) = (project().rooms[project().roomKey(roomId)]?.asmStateSelectorsSourceVersion ?: 0) != 0
    fun view(roomId: Int): AsmRoomSelectorsView = try {
        val value = source().resource(project().rooms.getValue(project().roomKey(roomId)))
        validateSelectorDoorReferences(value, roomId, project(), catalog, workspace)
        lastGood[roomId] = value
        AsmRoomSelectorsView(value, null)
    } catch (error: Exception) {
        val room = project().rooms[project().roomKey(roomId)]
        val recognized = runCatching { room?.let { source().conversionPreview(it) } }
        val conversion = recognized.getOrNull()
        if (conversion != null) AsmRoomSelectorsView(conversion.selectors,
            "Handwritten predicates are recognized, but must be reviewed and converted before building or visual selector editing.", conversion)
        else {
            val ids = room?.states?.map { it.id }?.toSet()
            AsmRoomSelectorsView(lastGood[roomId]?.takeIf { it.order.toSet() == ids },
                recognized.exceptionOrNull()?.message ?: error.message ?: "Invalid selectors.")
        }
    }
    fun refreshAll() {
        project().rooms.values.forEach { room ->
            val resource = if (isManaged(room.roomId)) view(room.roomId).resource else null
            if (resource != null) resource.projectInto(room)
            else { room.asmSelectorOrderProjection = null; room.states.forEach { it.asmConditionProjection = null } }
        }
    }
    fun preview(room: RoomEdits, name: String) = AsmRoomSelectorsSource.render(room.roomId, name,
        SourceRoomSelectors(room.effectiveStates.map { it.id }, room.states.associate { it.id to it.effectiveCondition }))
    fun migrate(roomId: Int, name: String) {
        if (isManaged(roomId)) return
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE && owner.newRoomForPreviewId(roomId) == null) { "Selector source requires a native room in ASM mode." }
        val room = owner.rooms.getValue(owner.roomKey(roomId))
        require(catalog.parser.readRoomHeader(roomId)?.area in 0..6) { "The debug/unused area is not writable." }
        val beforeModel = snapshotRoomForResize(room)
        val staged = snapshotRoomForResize(room)
        val resource = SourceRoomSelectors(room.effectiveStates.map { it.id }, room.states.associate { it.id to it.effectiveCondition })
        validateSelectorDoorReferences(resource, roomId, owner, catalog, workspace)
        staged.states.forEach { it.condition = AsmRoomSelectorsSource.baseline(it); it.conditionChanged = false }
        staged.asmStateSelectorsSourceVersion = 1
        resource.projectInto(staged)
        val before = source().text
        require(!Regex("(?m)^macro ${AsmRoomSelectorsSource.prefix(roomId)}").containsMatchIn(before)) { "Existing selector declarations need review before adoption." }
        val after = before.trimEnd() + "\n\n" + AsmRoomSelectorsSource.render(roomId, name, resource)
        AsmRoomSelectorsSource(after).resource(staged)
        fun transition(expected: RoomEdits, target: RoomEdits) {
            require(project() === owner && owner.rooms[owner.roomKey(roomId)] === room && room == expected) { "Undo newer room/state changes before restoring selector ownership." }
            room.asmStateSelectorsSourceVersion = target.asmStateSelectorsSourceVersion
            room.asmSelectorOrderProjection = target.asmSelectorOrderProjection
            room.states.zip(target.states).forEach { (state, saved) ->
                state.condition = saved.condition; state.conditionChanged = saved.conditionChanged
                state.asmConditionProjection = saved.asmConditionProjection
            }
            changed()
        }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, before, after,
            beforeUndo = { transition(staged, beforeModel) }, beforeRedo = { transition(beforeModel, staged) })
        transition(beforeModel, staged)
    }
    fun editCondition(roomId: Int, id: String, condition: ProjectRoomStateCondition) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        require(view(roomId).problem == null) { view(roomId).problem ?: "Invalid selectors." }
        val current = source()
        val after = current.editCondition(room, id, condition)
        validateSelectorDoorReferences(AsmRoomSelectorsSource(after).resource(room), roomId, project(), catalog, workspace, after)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, after)
        refreshAll()
    }
    fun convert(roomId: Int, preview: AsmSelectorConversionPreview, afterTransition: (Boolean) -> Unit = {}) {
        val owner = project()
        val room = owner.rooms.getValue(owner.roomKey(roomId))
        val current = source()
        require(roomId == preview.roomId && current.text == preview.sourceText && current.conversionPreview(room) == preview) {
            "Selectors changed while the conversion preview was open. Review the new source before converting."
        }
        fun guard() = require(project() === owner && owner.rooms[owner.roomKey(roomId)] === room && room.asmStateSelectorsSourceVersion == 1) {
            "The room's selector ownership changed; undo newer room changes before this conversion."
        }
        workspace.replaceProjectSources(mapOf(PLM_SOURCE_FILE to (current.text to current.convertPredicates(room))),
            beforeUndo = { guard() }, beforeRedo = { guard() },
            afterUndo = { afterTransition(true) }, afterRedo = { afterTransition(false) })
        refreshAll()
    }
    fun move(roomId: Int, id: String, direction: Int): Boolean {
        require(direction == -1 || direction == 1)
        val room = project().rooms.getValue(project().roomKey(roomId))
        val view = view(roomId)
        require(view.problem == null) { view.problem ?: "Invalid selectors." }
        val order = requireNotNull(view.resource).order.toMutableList()
        val index = order.indexOf(id)
        val target = index + direction
        if (index !in 0 until order.lastIndex || target !in 0 until order.lastIndex) return false
        val other = order[target]; order[target] = id; order[index] = other
        val current = source()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, current.editOrder(room, order))
        refreshAll()
        return true
    }
    fun line(roomId: Int, stateId: String? = null) = source().line(roomId, stateId)
}

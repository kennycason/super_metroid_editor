package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.rom.SourceRoomLayoutResource

internal data class AsmRoomLayoutView(val resource: SourceRoomLayoutResource?, val problem: String?)

internal class AsmRoomLayoutBridge(
    private val catalog: AsmPlmSourceCatalog,
    private val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject,
    private val changed: () -> Unit,
) {
    private var revision: Pair<String, AsmRoomLayoutSource>? = null
    private val lastGood = mutableMapOf<Pair<Int, String>, SourceRoomLayoutResource>()
    private fun source(): AsmRoomLayoutSource {
        val text = workspace.projectSourceText(PLM_SOURCE_FILE)
        return revision?.takeIf { it.first == text }?.second ?: AsmRoomLayoutSource(text).also { revision = text to it }
    }
    fun isManaged(roomId: Int) = (project().rooms[project().roomKey(roomId)]?.asmLayoutSourceVersion ?: 0) != 0
    fun view(roomId: Int, stateId: String): AsmRoomLayoutView = try {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val header = requireNotNull(catalog.parser.readRoomHeader(roomId))
        val inputs = source().resources(room, (room.effectiveHeaderChange?.width ?: header.width) * 16, (room.effectiveHeaderChange?.height ?: header.height) * 16)
        val resource = inputs.getValue(stateId)
        lastGood[roomId to stateId] = resource
        AsmRoomLayoutView(resource, null)
    } catch (error: Exception) { AsmRoomLayoutView(lastGood[roomId to stateId], error.message ?: "Invalid room layout source.") }

    fun migrate(roomId: Int, roomName: String, levels: Map<String, ByteArray>) {
        if (isManaged(roomId)) return
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Layout source requires ASM mode." }
        require(owner.newRooms.isEmpty()) { "Layout source for projects with added rooms needs stable source identities first." }
        val room = owner.rooms.getValue(owner.roomKey(roomId))
        val beforeModel = snapshotRoomForResize(room)
        val staged = snapshotRoomForResize(room)
        require(staged.states.isNotEmpty() && levels.keys == staged.states.map { it.id }.toSet()) { "Layout state coverage is incomplete." }
        val header = requireNotNull(catalog.parser.readRoomHeader(roomId))
        val width = (room.effectiveHeaderChange?.width ?: header.width) * 16
        val height = (room.effectiveHeaderChange?.height ?: header.height) * 16
        val before = source().text
        require(!Regex("(?m)^macro ${AsmRoomLayoutSource.prefix(roomId)}").containsMatchIn(before)) { "Existing layout declarations need review before adoption." }
        val addition = preview(staged, roomName, levels)
        fun strip(operations: MutableList<com.supermetroid.editor.data.EditOperation>) {
            val retained = operations.map { it.copy(edits = emptyList()) }.filter { it.plmAdds.isNotEmpty() || it.plmRemoves.isNotEmpty() ||
                it.enemyAdds.isNotEmpty() || it.enemyRemoves.isNotEmpty() || it.enemyUpdates.isNotEmpty() || it.scrollEdits.isNotEmpty() ||
                it.stateDataBefore != null || it.stateDataAfter != null || it.fxBefore != null || it.fxAfter != null }
            operations.clear(); operations.addAll(retained)
        }
        strip(staged.operations); staged.states.forEach { strip(it.operations) }
        staged.levelResourceOperations.values.forEach(::strip); staged.levelResourceOperations.entries.removeAll { it.value.isEmpty() }
        staged.asmLayoutSourceVersion = 1
        val after = before.trimEnd() + "\n\n" + addition
        AsmRoomLayoutSource(after).resources(staged, width, height)
        fun apply(expected: RoomEdits, target: RoomEdits) {
            require(project() === owner && owner.rooms[owner.roomKey(roomId)] === room && room == expected) { "Undo newer room changes before adopting/restoring its layout source." }
            room.asmLayoutSourceVersion = target.asmLayoutSourceVersion
            room.operations.clear(); room.operations.addAll(target.operations)
            room.levelResourceOperations.clear(); target.levelResourceOperations.forEach { (id, ops) -> room.levelResourceOperations[id] = ops.toMutableList() }
            room.states.zip(target.states).forEach { (state, saved) -> state.operations.clear(); state.operations.addAll(saved.operations) }
            changed()
        }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, before, after,
            beforeUndo = { apply(staged, beforeModel) }, beforeRedo = { apply(beforeModel, staged) })
        apply(beforeModel, staged)
    }

    /** Pure inspection: rendering declarations must not adopt ownership or touch source buffers. */
    fun preview(room: RoomEdits, roomName: String, levels: Map<String, ByteArray>): String {
        val roomId = room.roomId
        val header = requireNotNull(catalog.parser.readRoomHeader(roomId))
        val width = (room.effectiveHeaderChange?.width ?: header.width) * 16
        val height = (room.effectiveHeaderChange?.height ?: header.height) * 16
        return buildString {
            appendLine("; SMEDIT room layout · ${roomName.replace('\n', ' ').replace('\r', ' ')} · ${width}×$height blocks")
            appendLine("; Editable decoded level asset. Build validates and LZ5-compresses it; do not invoke at a ROM org.")
            for ((levelId, states) in room.states.groupBy { it.resources.level }) {
                val data = levels.getValue(states.first().id)
                require(states.all { levels.getValue(it.id).contentEquals(data) }) { "Sharing states have different private geometry. Make their layouts unique before adoption." }
                appendLine(AsmRoomLayoutSource.render(SourceRoomLayoutResource(AsmRoomLayoutSource.dataName(roomId, levelId), width, height, data.map { it.toInt() and 255 })))
            }
            room.states.forEach { appendLine(AsmRoomLayoutSource.renderState(roomId, it.id, it.resources.level)) }
        }
    }

    fun edit(roomId: Int, stateId: String, edits: List<TileEdit>, beforeUndo: () -> Unit = {}, beforeRedo: () -> Unit = {}): Boolean {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val view = view(roomId, stateId)
        require(view.problem == null) { view.problem ?: "Invalid layout source." }
        val current = source()
        val resource = requireNotNull(view.resource)
        val after = current.edit(room, stateId, resource.width, resource.height, edits)
        if (after == current.text) return false
        val links = room.states.map { it.id to it.resources.level }
        val dimensions = room.effectiveHeaderChange
        fun validate() {
            require(project().rooms[project().roomKey(roomId)] === room && room.states.map { it.id to it.resources.level } == links && room.effectiveHeaderChange == dimensions) { "Undo newer layout sharing/dimension changes first." }
        }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, after,
            beforeUndo = { validate(); beforeUndo() }, beforeRedo = { validate(); beforeRedo() })
        return true
    }
    fun changeLink(roomId: Int, stateId: String, levelId: String, clone: Boolean) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val state = room.states.single { it.id == stateId }
        val old = state.resources.level
        val view = view(roomId, stateId)
        require(view.problem == null) { view.problem ?: "Invalid layout source." }
        val current = source()
        val after = if (clone) current.cloneLayout(room, stateId, levelId) else current.link(roomId, stateId, levelId)
        val links = room.states.map { it.id to it.resources.level }
        fun transition(undo: Boolean) {
            val expected = links.map { (id, level) -> id to if (!undo || id != stateId) level else levelId }
            require(project().rooms[project().roomKey(roomId)] === room && room.states.map { it.id to it.resources.level } == expected) { "Undo newer layout sharing changes first." }
            state.resources = state.resources.copy(level = if (undo) old else levelId)
            changed()
        }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, after, beforeUndo = { transition(true) }, beforeRedo = { transition(false) })
        transition(false)
    }
    fun cloneState(roomId: Int, templateId: String, newId: String) {
        if (!isManaged(roomId)) return
        val room = project().rooms.getValue(project().roomKey(roomId))
        val level = room.states.single { it.id == templateId }.resources.level
        val current = source()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, current.appendState(roomId, newId, level), beforeUndo = {
            require(room.states.none { it.id == newId }) { "Delete the new state before undoing its layout declaration." }
        })
    }
    fun line(roomId: Int, stateId: String): Int {
        val level = project().rooms.getValue(project().roomKey(roomId)).states.single { it.id == stateId }.resources.level
        return source().line(roomId, level)
    }
    fun hasLayout(roomId: Int, levelId: String) = source().hasLayout(roomId, levelId)
}

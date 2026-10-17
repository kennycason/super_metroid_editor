package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomStateEdits
import com.supermetroid.editor.data.RoomStateResourceLinks
import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.rom.projectRoomStateCondition
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import kotlinx.serialization.json.Json

/** One explicit identity/seed boundary for native rooms and relocatable project rooms. */
internal class AsmRoomSourceContext(val catalog: AsmPlmSourceCatalog, private val project: () -> SmEditProject) {
    var workspaceParser: (() -> RomParser)? = null
    val parser: RomParser get() = if (project().newRooms.isEmpty()) catalog.parser else
        requireNotNull(workspaceParser?.invoke()) { "Wait for the project room workspace." }
    fun identities() = AsmProjectRoomLabels.identities(project())
    fun seedScrollCommands(roomId: Int) = project().newRoomForPreviewId(roomId)?.initialState?.scrollCommands.orEmpty()
    fun prefix(family: String, roomId: Int) = prefix(family, roomId, identities()[roomId])
    fun identity(roomId: Int) = identities()[roomId]?.let { "project:$it" } ?: "native:$roomId"
    fun ownership(room: RoomEdits) = Json.encodeToString(RoomEdits.serializer(),
        if (project().newRoomForPreviewId(room.roomId) == null) room else room.copy(roomId = 0))

    /** Project-room history follows the durable owner, never a recycled preview address. */
    fun replace(workspace: AsmWorkspaceState, roomId: Int, before: RoomEdits?, after: RoomEdits,
                sources: Map<String, Pair<String, String>>, changed: () -> Unit,
                afterUndo: () -> Unit = {}, afterRedo: () -> Unit = {}) {
        val owner = project()
        val projectId = requireNotNull(owner.newRoomForPreviewId(roomId)?.id)
        // Adoption owns one family's metadata, not every unrelated field in the room.
        // This also keeps earlier histories' live room/state identities intact.
        fun versions(room: RoomEdits) = listOf(room.asmPlmSourceVersion, room.asmInitialScrollSourceVersion,
            room.asmFxSourceVersion, room.asmEnemySourceVersion, room.asmDoorSourceVersion, room.asmStateSelectorsSourceVersion)
        val family = versions(after).indices.single { versions(after)[it] != versions(before ?: RoomEdits(roomId))[it] }
        fun projectScope(input: RoomEdits?): RoomEdits {
            val room = input ?: RoomEdits(roomId)
            val scoped = RoomEdits(0, states = (room.states.takeIf { it.isNotEmpty() } ?: after.states).map { neutralState(it.id) }.toMutableList())
            copyFamily(room, scoped, family)
            return scoped
        }
        val oldValue = ownership(projectScope(before))
        val newValue = ownership(projectScope(after))
        fun key(): String {
            require(project() === owner) { "The ASM project changed." }
            val id = requireNotNull(owner.newRooms.singleOrNull { it.id == projectId }?.previewRoomId?.takeIf { it != 0 }) {
                "This project room no longer has a unique workspace identity."
            }
            return owner.roomKey(id)
        }
        fun checkCurrent(undo: Boolean) {
            require(ownership(projectScope(owner.rooms[key()])) == if (undo) newValue else oldValue) {
                "Room data changed; undo newer room edits before restoring source ownership."
            }
        }
        fun set(undo: Boolean) {
            val roomKey = key()
            val value = snapshotRoomForResize(if (undo) before ?: RoomEdits(roomKey.toInt(16)) else after)
            val live = owner.rooms[roomKey] ?: RoomEdits(roomKey.toInt(16))
            if (live.states.isEmpty()) live.states.addAll(snapshotRoomForResize(after).states.map { state ->
                state.copy(plmChanges = mutableListOf(), enemyChanges = mutableListOf(), scrollChanges = mutableListOf(),
                    customScrollCommands = mutableMapOf(), fxChange = null, doorFxChanges = mutableMapOf())
            })
            copyFamily(value, live, family)
            if (undo && before == null && !live.hasEdits) owner.rooms.remove(roomKey)
            else {
                if (undo && before?.states?.isEmpty() == true && versions(live).all { it == 0 } && !live.stateGraphChanged) live.states.clear()
                owner.rooms[roomKey] = live
            }
        }
        checkCurrent(false)
        workspace.replaceProjectSources(sources,
            beforeApply = { set(false) }, afterApply = changed,
            beforeUndo = { checkCurrent(true); set(true) }, afterUndo = { changed(); afterUndo() },
            beforeRedo = { checkCurrent(false); set(false) }, afterRedo = { changed(); afterRedo() })
    }

    private fun copyFamily(source: RoomEdits, target: RoomEdits, family: Int) {
        fun <T> MutableList<T>.replace(values: List<T>) { clear(); addAll(values) }
        when (family) {
            0 -> { target.asmPlmSourceVersion = source.asmPlmSourceVersion; target.plmChanges.replace(source.plmChanges)
                target.customScrollCommands.clear(); source.customScrollCommands.forEach { (id, values) -> target.customScrollCommands[id] = values.toMutableList() }
                target.saveStationSpawns.replace(source.saveStationSpawns) }
            1 -> { target.asmInitialScrollSourceVersion = source.asmInitialScrollSourceVersion; target.scrollChanges.replace(source.scrollChanges) }
            2 -> { target.asmFxSourceVersion = source.asmFxSourceVersion; target.fxChange = source.fxChange }
            3 -> { target.asmEnemySourceVersion = source.asmEnemySourceVersion; target.enemyChanges.replace(source.enemyChanges) }
            4 -> { target.asmDoorSourceVersion = source.asmDoorSourceVersion; target.doorChanges.replace(source.doorChanges); target.asmDoorProjection = source.asmDoorProjection }
            5 -> { target.asmStateSelectorsSourceVersion = source.asmStateSelectorsSourceVersion; target.asmSelectorOrderProjection = source.asmSelectorOrderProjection }
        }
        for (state in target.states) {
            val saved = source.states.singleOrNull { it.id == state.id } ?: neutralState(state.id)
            when (family) {
                0 -> { state.plmChanges.replace(saved.plmChanges); state.customScrollCommands.clear()
                    saved.customScrollCommands.forEach { (id, values) -> state.customScrollCommands[id] = values.toMutableList() } }
                1 -> state.scrollChanges.replace(saved.scrollChanges)
                2 -> { state.fxChange = saved.fxChange; state.doorFxChanges.clear(); state.doorFxChanges.putAll(saved.doorFxChanges) }
                3 -> state.enemyChanges.replace(saved.enemyChanges)
                5 -> { state.condition = saved.condition; state.conditionChanged = saved.conditionChanged; state.asmConditionProjection = saved.asmConditionProjection }
            }
        }
    }

    private fun neutralState(id: String) = RoomStateEdits(id,
        condition = projectRoomStateCondition(ProjectRoomStateConditionKind.NEVER),
        resources = RoomStateResourceLinks("", "", "", "", "", "", "", ""))

    companion object {
        fun prefix(family: String, roomId: Int, projectId: String? = null) = "${family}_" +
            (projectId?.let { "Project_${AsmProjectRoomLabels.encoded(it)}" } ?: roomId.toString(16).uppercase().padStart(4, '0')) + "_"
    }
}

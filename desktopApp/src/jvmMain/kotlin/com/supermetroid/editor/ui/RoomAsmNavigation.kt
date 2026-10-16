package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.*
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import kotlinx.serialization.json.Json

/** The app and contract tests use this exact room -> source route; opening a link never adopts source. */
internal class RoomAsmNavigation(
    private val editor: EditorState,
    private val workspace: AsmWorkspaceState,
    private val roomName: () -> String,
    private val navigate: (() -> Unit) -> Unit = { it() },
    private val showAsm: () -> Unit = {},
) {
    fun open(request: RoomAsmSourceRequest): Boolean = openRoom(editor.currentRoomId, roomName(), request)

    fun openArrival(doorIndex: Int, create: Boolean = false, expectedIdentity: Int? = null): Boolean {
        if (!editor.asmNavigationEnabled) return false
        return runCatching {
            val bridge = requireNotNull(editor.asmPlmSourceBridge) { "Wait for the project ASM workspace." }
            require(editor.roomSourceParser === bridge.catalog.parser) { "Wait for the current room/ASM link." }
            if (create) require(editor.createManagedDoorArrival(doorIndex, expectedIdentity)) { editor.statusMessage }
            val binding = editor.doorArrivalBindings[doorIndex]
            val entry = requireNotNull(editor.doorEntries.getOrNull(doorIndex)) { "Reopen the current connection." }
            val line = if (binding == null) null else bridge.doors.arrivalLine(editor.currentRoomId, doorIndex)
            require(line != null || entry.entryCode in 0x8000..0xFFFE) { "This door has no arrival ASM yet." }
            navigate {
                workspace.showProjectWorkspace()
                if (line != null) workspace.openSource(PLM_SOURCE_FILE, line) else workspace.openAddress(0x8F0000 or entry.entryCode)
                showAsm()
            }
            true
        }.getOrElse { editor.postStatus("Arrival navigation unavailable: ${it.message}"); false }
    }

    fun openFromAsm(link: AsmRoomSourceLink): Boolean {
        var index = link.doorIndex
        val doors = editor.asmPlmSourceBridge?.doors
        // Native Source/Assets links carry a native declaration identity, not the
        // current BTS position after a structural deletion/reorder.
        if (link.kind == RoomAsmSourceKind.DOORS && index != null && doors?.isManaged(link.roomId) == true) {
            index = doors.view(link.roomId).resource?.entries?.indexOfFirst { it.identity == link.doorIndex }
            if (index == null || index < 0) {
                editor.postStatus("This native connection was removed from the authored list. Choose a current connection; its old declaration is retained in ASM.")
                return false
            }
        }
        return openRoom(link.roomId, link.roomName, RoomAsmSourceRequest(link.kind, door = index), link.sourceStateIndex)
    }

    private fun openRoom(roomId: Int, name: String, requested: RoomAsmSourceRequest, sourceStateIndex: Int? = null,
                         refreshView: Boolean = false, redoHead: AsmSourceTextChange? = null): Boolean {
        if (!editor.asmNavigationEnabled) return false
        return runCatching {
            val bridge = requireNotNull(editor.asmPlmSourceBridge) { "Wait for the project ASM workspace." }
            val parser = bridge.catalog.parser
            require(editor.roomSourceParser == null || editor.roomSourceParser === parser) { "Wait for the ASM workspace to finish linking the current ROM." }
            val room = editor.roomSourceSnapshot(roomId)
            val isCurrentRoom = editor.currentRoomId == roomId
            val activeState = editor.currentPlmStateId().takeIf { isCurrentRoom }
                ?: room.states.firstOrNull { it.sourceStateIndex == parser.preferredPreviewStateIndex(roomId) }?.id
                ?: room.effectiveStates.last().id
            val requestedState = sourceStateIndex?.let { index ->
                requireNotNull(room.states.singleOrNull { it.sourceStateIndex == index }) { "This native state was removed; choose a current project state instead." }.id
            }
            val request = if (requestedState != null) requested.copy(stateId = requestedState) else requested
            val state = request.stateId ?: activeState
            require(room.states.any { it.id == state }) { "This state changed; reopen its current source link." }
            val actorIndex = request.enemy?.let { actor ->
                editor.workingEnemies.indexOfFirst { it === actor }.takeIf { it >= 0 }
                    ?: editor.workingEnemies.withIndex().filter { it.value == actor }.singleOrNull()?.index
                    ?: error("This actor changed; select its current placement again.")
            }
            require(request.enemy == null || (isCurrentRoom && state == activeState)) { "Select the actor's state first." }
            request.plm?.let { plm ->
                require(isCurrentRoom && state == activeState && editor.workingPlms.count { it == plm } == 1) {
                    "This object changed or has identical duplicates; select an unambiguous current placement."
                }
            }

            fun target(plm: RomParser.PlmEntry? = request.plm): AsmPlmPlacementTarget = when (request.kind) {
                RoomAsmSourceKind.LAYOUT -> AsmPlmPlacementTarget.Source(bridge.layouts.line(roomId, state))
                RoomAsmSourceKind.PROPERTIES -> AsmPlmPlacementTarget.Source(bridge.properties.line(roomId, request.stateId))
                RoomAsmSourceKind.SELECTORS -> AsmPlmPlacementTarget.Source(bridge.selectors.line(roomId, request.stateId))
                RoomAsmSourceKind.DOORS -> AsmPlmPlacementTarget.Source(bridge.doors.line(roomId, request.door ?: 0))
                RoomAsmSourceKind.INITIAL_SCROLLS -> AsmPlmPlacementTarget.Source(bridge.initialScrolls.line(roomId, state))
                RoomAsmSourceKind.FX -> AsmPlmPlacementTarget.Source(bridge.fx.line(roomId, state, request.door))
                RoomAsmSourceKind.ENEMIES, RoomAsmSourceKind.ENEMY_GRAPHICS -> AsmPlmPlacementTarget.Source(
                    bridge.enemies.line(roomId, state, actorIndex, request.kind == RoomAsmSourceKind.ENEMY_GRAPHICS))
                RoomAsmSourceKind.SAVE_SPAWN -> bridge.saveTarget(roomId, editor.activeRoomAreaForEditing(), requireNotNull(plm).param and 255)
                RoomAsmSourceKind.PLMS -> if (bridge.isManaged(roomId)) {
                    if (plm != null) bridge.managedPlacement(roomId, state, plm)
                    else {
                        val view = bridge.managedView(roomId, state)
                        require(view.problem == null) { view.problem ?: "Invalid room objects." }
                        AsmPlmPlacementTarget.Source(requireNotNull(view.document).lineIndex)
                    }
                }
                    else editor.currentPlmSourcePointer()?.let { bridge.placementTarget(roomId, editor.currentStateIndex, it, requireNotNull(plm)) }
                        ?: AsmPlmPlacementTarget.Unavailable("No verified editable ASM placement source is available for this room state.")
            }

            fun present(action: () -> Unit) {
                if (refreshView) action() else navigate { action(); showAsm() }
            }

            fun openTarget(plm: RomParser.PlmEntry? = request.plm) {
                when (val destination = target(plm)) {
                    is AsmPlmPlacementTarget.Source -> present {
                        workspace.showProjectWorkspace()
                        workspace.openSource(request.kind.fileId, destination.lineIndex, addToHistory = !refreshView, columnIndex = destination.columnIndex)
                    }
                    is AsmPlmPlacementTarget.Unavailable -> error(destination.reason)
                }
            }

            val managed = when (request.kind) {
                RoomAsmSourceKind.LAYOUT -> bridge.layouts.isManaged(roomId)
                RoomAsmSourceKind.PROPERTIES -> bridge.properties.isManaged(roomId)
                RoomAsmSourceKind.SELECTORS -> bridge.selectors.isManaged(roomId)
                RoomAsmSourceKind.DOORS -> bridge.doors.isManaged(roomId)
                RoomAsmSourceKind.INITIAL_SCROLLS -> bridge.initialScrolls.isManaged(roomId)
                RoomAsmSourceKind.FX -> bridge.fx.isManaged(roomId)
                RoomAsmSourceKind.ENEMIES, RoomAsmSourceKind.ENEMY_GRAPHICS -> bridge.enemies.isManaged(roomId)
                RoomAsmSourceKind.PLMS -> bridge.isManaged(roomId) || (request.plm != null && target() is AsmPlmPlacementTarget.Source)
                RoomAsmSourceKind.SAVE_SPAWN -> bridge.hasSaveSource(roomId, editor.activeRoomAreaForEditing(), requireNotNull(request.plm).param and 255)
            }
            if (managed) openTarget() else {
                require(editor.project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Editable room source requires ASM source mode." }
                require(editor.project.newRooms.isEmpty()) { "Room source adoption for projects with added rooms is not supported yet." }
                val owner = editor.project
                val model = Json.encodeToString(SmEditProject.serializer(), owner)
                val revision = workspace.sourceBufferRevision
                val root = workspace.projectWorkspace?.root
                val romVersion = editor.romVersion
                val originRoomId = editor.currentRoomId
                val originParser = editor.roomSourceParser
                val selectedState = editor.currentPlmStateId()
                val text = editor.inspectRoomSource(name, request, roomId)
                // Validate requested sub-records against the planned document BEFORE any ownership transition.
                when (request.kind) {
                    RoomAsmSourceKind.DOORS -> AsmDoorSource(text).line(roomId, request.door ?: 0)
                    RoomAsmSourceKind.PROPERTIES -> AsmRoomPropertiesSource(text).line(roomId, request.stateId)
                    RoomAsmSourceKind.FX -> {
                        val fx = AsmFxSource(text).resource(roomId, state)
                        require(request.door == null || fx.entries.any { it.doorSelect == request.door }) { "This incoming-door FX record changed; reopen its current link." }
                    }
                    RoomAsmSourceKind.ENEMIES, RoomAsmSourceKind.ENEMY_GRAPHICS -> AsmEnemySource(text, bridge.catalog.symbols)
                        .line(roomId, state, actorIndex, request.kind == RoomAsmSourceKind.ENEMY_GRAPHICS)
                    else -> Unit
                }
                lateinit var snapshot: AsmRoomSourceInspection
                fun unchanged(): Boolean = editor.project === owner && editor.asmNavigationEnabled && editor.asmPlmSourceBridge === bridge &&
                        editor.currentRoomId == originRoomId && editor.currentPlmStateId() == selectedState && editor.romVersion == romVersion && editor.roomSourceParser === originParser &&
                        workspace.projectWorkspace?.root == root && workspace.roomSourceInspection === snapshot &&
                        workspace.sourceBufferRevision == revision && Json.encodeToString(SmEditProject.serializer(), owner) == model &&
                        runCatching { editor.inspectRoomSource(name, request, roomId) == text }.getOrDefault(false)
                val automatic = if (request.kind !in setOf(RoomAsmSourceKind.DOORS, RoomAsmSourceKind.PROPERTIES)) null else {
                    val planned = when (request.kind) {
                        RoomAsmSourceKind.DOORS -> bridge.doors.automaticSource(room, name)
                        else -> bridge.properties.automaticSource(room, name)
                    }
                    val lineIndex = when (request.kind) {
                        RoomAsmSourceKind.DOORS -> AsmDoorSource(planned).line(roomId, request.door ?: 0)
                        else -> AsmRoomPropertiesSource(planned).line(roomId, request.stateId)
                    }
                    AsmRoomSourceAutomaticEdit(planned, lineIndex,
                        apply = { replacement ->
                            if (!unchanged()) {
                                editor.postStatus("The room or ASM project changed; reopen its ${request.kind.title} before editing. No changes were applied.")
                                workspace.reportSourceEditError(editor.statusMessage)
                                false
                            } else {
                                val afterUndo = {
                                    if (workspace.workspaceKind == AsmWorkspaceKind.PROJECT && workspace.browserMode == AsmBrowserMode.SOURCE &&
                                        workspace.selectedFileId == request.kind.fileId && workspace.roomSourceInspection == null) {
                                        val line = workspace.selectedLineIndex; val column = workspace.selectedColumnIndex
                                        openRoom(roomId, name, request, refreshView = true, redoHead = workspace.sourceRedoHead(request.kind.fileId))
                                        workspace.selectSourcePosition(line, column)
                                    }
                                }
                                val afterRedo = {
                                    if (workspace.roomSourceInspection?.roomId == roomId && workspace.roomSourceInspection?.automaticEdit != null)
                                        workspace.finishRoomSourceInspection()
                                }
                                val applied = when (request.kind) {
                                    RoomAsmSourceKind.DOORS -> editor.editInspectedDoorSource(roomId, name, planned, replacement, afterUndo, afterRedo)
                                    else -> editor.editInspectedRoomPropertiesSource(roomId, name, planned, replacement, afterUndo, afterRedo)
                                }
                                if (applied) { workspace.finishRoomSourceInspection(); workspace.dismissError() }
                                else workspace.reportSourceEditError(editor.statusMessage)
                                applied
                            }
                        },
                        reopen = { openRoom(roomId, name, request, refreshView = true,
                            redoHead = redoHead?.takeIf { workspace.sourceRedoHead(request.kind.fileId) === it }) },
                        redoHead = redoHead)
                }
                snapshot = AsmRoomSourceInspection(roomId, "$name · ${request.kind.title}", text, request.stateId ?: activeState, request.kind.fileId, automatic) {
                    if (!unchanged()) editor.postStatus("The room or ASM project changed; reopen this source preview before adopting it.")
                    else if (editor.adoptInspectedRoomSource(request, name, roomId)) runCatching {
                        val migrated = if (request.kind == RoomAsmSourceKind.PLMS && request.plm != null)
                            placementAfterObjectMigration(requireNotNull(request.plm), editor.workingPlms) ?: request.plm else request.plm
                        openTarget(migrated)
                    }.onFailure { editor.postStatus("Source navigation unavailable: ${it.message}") }
                }
                present {
                    workspace.openRoomSourceInspection(snapshot, addToHistory = !refreshView)
                }
            }
            true
        }.getOrElse { editor.postStatus("Source navigation unavailable: ${it.message}"); false }
    }
}

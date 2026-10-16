package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.AreaSaveMigrationPlan
import kotlinx.serialization.json.Json

/** Session-only identity shared by the source, canvas and minimap histories. */
internal class AsmRoomAreaMoveHistory(
    private val workspace: AsmWorkspaceState,
    private val replacements: Map<String, Pair<String, String>>,
    private val head: AsmSourceTextChange,
    private val action: String = "room move",
) {
    var beforeTransition: (() -> Unit)? = null
    var onTransition: ((Boolean) -> Unit)? = null
    fun undo(): Boolean = move(true)
    fun redo(): Boolean = move(false)
    private fun move(undo: Boolean): Boolean {
        val currentHead = if (undo) workspace.sourceUndoHead(PLM_SOURCE_FILE) else workspace.sourceRedoHead(PLM_SOURCE_FILE)
        check(currentHead === head) { "Undo newer ASM edits before restoring this $action." }
        check(replacements.all { (file, texts) -> workspace.projectSourceText(file) == if (undo) texts.second else texts.first }) {
            "The $action's source changed; undo newer source edits first."
        }
        val moved = if (undo) workspace.undoSourceEdit(PLM_SOURCE_FILE, fromRoomEditor = true)
        else workspace.redoSourceEdit(PLM_SOURCE_FILE, fromRoomEditor = true)
        check(moved) { workspace.error ?: "Undo newer source/model edits before restoring this $action." }
        return true
    }
}

internal data class AsmAreaRoomChange(val live: RoomEdits, val before: RoomEdits?, val after: RoomEdits)
internal data class AsmRoomAreaMovePlan(
    val owner: SmEditProject,
    val rooms: Map<String, AsmAreaRoomChange>,
    val replacements: Map<String, Pair<String, String>>,
    val mapsBefore: Map<String, List<MinimapTileEdit>>,
    val mapsAfter: Map<String, List<MinimapTileEdit>>,
    val stationsBefore: Map<String, List<MapStationTileEdit>>,
    val stationsAfter: Map<String, List<MapStationTileEdit>>,
    val dependenciesBefore: Map<String, String>,
    val dependenciesAfter: Map<String, String>,
    val macros: String?,
)

/** Map positions and cross-area dependencies share one source/model edit, never independently published writes. */
internal class AsmRoomAreaMove(
    private val catalog: AsmPlmSourceCatalog,
    private val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject,
    private val properties: AsmRoomPropertiesBridge,
    private val beforeObjectAdoption: (Int) -> Unit,
    private val changed: () -> Unit,
) {
    private fun encode(room: RoomEdits) = Json.encodeToString(RoomEdits.serializer(), room)

    /** Geometry, connections and slot consumers matter; unrelated tile/actor/FX edits do not. */
    internal fun dependencies(owner: SmEditProject): Map<String, String> = owner.rooms.mapNotNull { (key, room) ->
        val relevant = room.roomHeaderChange != null || room.doorChanges.isNotEmpty() || room.plmChanges.isNotEmpty() ||
            room.saveStationSpawns.isNotEmpty() || room.stateGraphChanged || room.asmRoomPropertiesSourceVersion != 0 ||
            room.asmDoorSourceVersion != 0 || room.asmPlmSourceVersion != 0 || room.states.any { it.plmChanges.isNotEmpty() }
        if (!relevant) null else key to encode(RoomEdits(room.roomId,
            states = room.states.map { RoomStateEdits(id = it.id, sourceStateIndex = it.sourceStateIndex,
                templateSourceStateIndex = it.templateSourceStateIndex, condition = it.condition,
                resources = it.resources, plmChanges = it.plmChanges.toMutableList()) }.toMutableList(),
            roomHeaderChange = room.roomHeaderChange, doorChanges = room.doorChanges.toMutableList(),
            plmChanges = room.plmChanges.toMutableList(), saveStationSpawns = room.saveStationSpawns.toMutableList(),
            stateGraphChanged = room.stateGraphChanged, asmRoomPropertiesSourceVersion = room.asmRoomPropertiesSourceVersion,
            asmDoorSourceVersion = room.asmDoorSourceVersion, asmPlmSourceVersion = room.asmPlmSourceVersion))
    }.toMap()

    fun plan(roomId: Int, header: RoomHeaderChange, doors: Map<Int, List<DoorChange>>, saves: AreaSaveMigrationPlan,
             maps: Map<String, List<MinimapTileEdit>>, stations: Map<String, List<MapStationTileEdit>>,
             isItem: (Int) -> Boolean, describe: (RomParser.PlmEntry) -> String): AsmRoomAreaMovePlan {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE && owner.newRooms.isEmpty()) {
            "Room moves require the existing-room ASM workspace; added-room source identities are not supported yet."
        }
        val staged = linkedMapOf<String, RoomEdits>()
        fun room(id: Int) = staged.getOrPut(owner.roomKey(id)) {
            snapshotRoomForResize(owner.rooms[owner.roomKey(id)] ?: RoomEdits(id)).also { it.ensureStateManifest(catalog.parser) }
        }
        fun dimensions(id: Int): Pair<Int, Int>? = catalog.parser.readRoomHeader(id)?.let { native ->
            val edits = staged[owner.roomKey(id)] ?: owner.rooms[owner.roomKey(id)]
            (edits?.roomHeaderChange?.width ?: native.width) to (edits?.roomHeaderChange?.height ?: native.height)
        }
        val before8F = workspace.projectSourceText(PLM_SOURCE_FILE)
        val before83 = if (doors.isNotEmpty()) workspace.projectSourceText(DOOR_SOURCE_FILE) else null
        var text8F = before8F
        var text83 = before83.orEmpty()
        fun append(text: String, addition: String): String {
            val newline = if (text.contains("\r\n")) "\r\n" else "\n"
            return text + newline + addition.replace("\r\n", "\n").replace("\n", newline)
        }
        val moving = room(roomId)
        if (moving.asmRoomPropertiesSourceVersion == 0) {
            require(!Regex("(?im)^[ \\t]*macro[ \\t]+${AsmRoomPropertiesSource.prefix(roomId)}").containsMatchIn(text8F)) {
                "Retained room-header declarations need review before moving this room."
            }
            text8F = append(text8F, properties.preview(moving, requireNotNull(catalog.parser.readRoomHeader(roomId)).name))
            moving.roomHeaderChange = moving.roomHeaderChange?.let { RoomHeaderChange(width = it.width, height = it.height, doorOut = it.doorOut) }
                ?.takeUnless { it == RoomHeaderChange() }
            moving.stateDataChange = null; moving.states.forEach { it.stateDataChange = null }
            moving.asmRoomPropertiesSourceVersion = 1
        }
        val propertySource = AsmRoomPropertiesSource(text8F)
        val oldProperties = propertySource.resource(moving)
        oldProperties.projectInto(moving)
        var headerApplied = false
        fun updateHeader() {
            text8F = AsmRoomPropertiesSource(text8F).editHeader(moving, oldProperties.header.copy(area = header.area, mapX = header.mapX, mapY = header.mapY))
            AsmRoomPropertiesSource(text8F).resource(moving).projectInto(moving)
            headerApplied = true
        }

        for ((id, changes) in doors) {
            val target = room(id)
            if (target.asmDoorSourceVersion == 0) {
                require(!Regex("(?im)^[ \\t]*macro[ \\t]+${AsmDoorSource.prefix(id)}").containsMatchIn(text83)) {
                    "Retained DoorDef declarations for ${id.toString(16)} need review before moving."
                }
                val native = requireNotNull(catalog.parser.readRoomHeader(id))
                val existing = target.doorChanges.associateBy { it.doorIndex }
                val nativeDoors = catalog.parser.parseDoorList(native.doorOut)
                require(existing.keys.all { it in nativeDoors.indices }) { "A GUI door override has no native connection." }
                val source = SourceRoomDoors(native.doorOut, nativeDoors.mapIndexed { index, entry ->
                    SourceRoomDoor(entry.doorDefPtr, existing[index] ?: DoorChange(index, entry.destRoomPtr, entry.bitflag,
                        entry.doorCapCode, entry.screenX, entry.screenY, entry.distFromDoor, entry.entryCode))
                })
                validateSourceDoorOwnership(id, source, catalog.parser)
                text83 = append(text83, AsmDoorSource.render(id, native.name, source))
                target.doorChanges.clear(); target.asmDoorSourceVersion = 1
            }
            for (change in changes) {
                val source = AsmDoorSource(text83, text8F, catalog.symbols)
                val current = source.resource(target, catalog.parser, ::dimensions)
                val entry = current.entries[change.doorIndex].entry()
                text83 = source.edit(target, catalog.parser, change.doorIndex, entry.copy(bitflag = change.bitflag), ::dimensions)
            }
            target.asmDoorProjection = AsmDoorSource(text83, text8F, catalog.symbols).resource(target, catalog.parser, ::dimensions).entries.map { it.change }
        }

        val macros = if (saves.sourceRecords.isEmpty()) null else workspace.projectSourceText("macros.asm")
        if (macros != null) {
            catalog.requireStandardMacro(macros)
            val external = workspace.projectSourceTextsExcept(PLM_SOURCE_FILE).flatMap { AsmRoomObjectSource.referencedHelpers(it).asSequence() }.toSet()
            if (moving.asmPlmSourceVersion == 0) {
                beforeObjectAdoption(roomId)
                val migration = AsmRoomObjectSource.migrate(text8F, moving, catalog.parser,
                    requireNotNull(catalog.parser.readRoomHeader(roomId)).name, catalog.symbols, isItem, describe, external)
                text8F = migration.text
                moving.plmChanges.clear(); moving.customScrollCommands.clear(); moving.saveStationSpawns.clear()
                moving.states.forEach { it.plmChanges.clear(); it.customScrollCommands.clear() }
                moving.asmPlmSourceVersion = 1
            }
            var objects = AsmRoomObjectSource(text8F, catalog.symbols)
            text8F = objects.remapSaveParameters(moving, saves.plmReplacements.toMap(), external)
            for (save in saves.sourceRecords) {
                validateSourceSaveOwnership(save, catalog.parser)
                text8F = AsmRoomObjectSource(text8F, catalog.symbols).withSave(save)
            }
            // A slot cannot have a JSON override and a source declaration, even under another room key.
            for (candidate in owner.rooms.values) if (candidate.saveStationSpawns.any { old -> saves.sourceRecords.any { it.area == old.area && it.slot == old.saveIndex } }) {
                room(candidate.roomId).saveStationSpawns.removeAll { old -> saves.sourceRecords.any { it.area == old.area && it.slot == old.saveIndex } }
            }
            updateHeader()
            objects = AsmRoomObjectSource(text8F, catalog.symbols)
            val inputs = objects.resources(moving)
            validateSourceSaveLinks(roomId, requireNotNull(header.area), inputs.values.flatMap { it.entries }, objects.saveResources(roomId), moving.saveStationSpawns, catalog.parser)
            val claims = owner.rooms.values.filter { it.asmPlmSourceVersion != 0 && it.roomId != roomId }
                .flatMap { objects.saveResources(it.roomId) } + objects.saveResources(roomId)
            require(claims.groupBy { it.area to it.slot }.values.all { it.distinct().size == 1 }) { "Another room already owns a migrated save slot." }
        }
        if (!headerApplied) updateHeader()

        val changes = staged.mapValues { (key, after) ->
            val original = owner.rooms[key]
            AsmAreaRoomChange(original ?: RoomEdits(after.roomId), original?.let(::snapshotRoomForResize), after)
        }
        val prospective = owner.copy(rooms = owner.rooms.toMutableMap().apply { putAll(staged) })
        val replacements = linkedMapOf(PLM_SOURCE_FILE to (before8F to text8F))
        // A same-area map move has no DoorDef dependency. Do not anchor an unrelated bank's history.
        if (before83 != null) replacements[DOOR_SOURCE_FILE] = before83 to text83
        return AsmRoomAreaMovePlan(owner, changes,
            replacements,
            owner.minimapEdits.mapValues { it.value.toList() }, maps.mapValues { it.value.toList() },
            owner.mapStationEdits.mapValues { it.value.toList() }, stations.mapValues { it.value.toList() },
            dependencies(owner), dependencies(prospective), macros)
    }

    /** Also used by Reset: one preflight for all dependent rooms and map cells. */
    internal fun requireCurrent(plan: AsmRoomAreaMovePlan, undo: Boolean, exceptRoomId: Int? = null, action: String = "room move") {
        val owner = project()
        require(owner === plan.owner && owner.newRooms.isEmpty() && owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) {
            "The ASM project or build mode changed; no part of the $action was restored."
        }
        require(dependencies(owner) == if (undo) plan.dependenciesAfter else plan.dependenciesBefore) {
            "Room geometry, door or save consumers changed; undo newer edits before restoring this $action."
        }
        require(owner.minimapEdits == if (undo) plan.mapsAfter else plan.mapsBefore) { "Undo newer map edits before restoring this $action." }
        require(owner.mapStationEdits == if (undo) plan.stationsAfter else plan.stationsBefore) { "Undo newer map-reveal edits before restoring this $action." }
        if (plan.macros != null) require(workspace.projectSourceText("macros.asm") == plan.macros) { "Restore the PLM macro definitions before this $action." }
        for ((key, change) in plan.rooms) {
            if (change.live.roomId == exceptRoomId) continue
            val expected = if (undo) change.after else change.before
            require(owner.rooms[key] === (if (undo || change.before != null) change.live else null) &&
                (expected == null || encode(change.live) == encode(expected))) { "Room data changed; undo newer room edits before this $action." }
        }
    }

    internal fun setModel(plan: AsmRoomAreaMovePlan, undo: Boolean, exceptRoomId: Int? = null) {
        fun setRoom(live: RoomEdits, target: RoomEdits) {
            if (live.states.map { it.id } != target.states.map { it.id }) {
                live.states.clear(); live.states.addAll(snapshotRoomForResize(target).states)
            }
            AsmRoomBank8FMetadata.capture(target).applyTo(live)
            live.roomHeaderChange = target.roomHeaderChange; live.stateDataChange = target.stateDataChange
            live.asmHeaderPropertiesProjection = target.asmHeaderPropertiesProjection
            live.asmDoorSourceVersion = target.asmDoorSourceVersion; live.asmDoorProjection = target.asmDoorProjection
            live.doorChanges.clear(); live.doorChanges.addAll(target.doorChanges)
            live.states.zip(target.states).forEach { (state, saved) ->
                state.stateDataChange = saved.stateDataChange; state.asmStatePropertiesProjection = saved.asmStatePropertiesProjection
            }
        }
        for ((key, change) in plan.rooms) {
            if (change.live.roomId == exceptRoomId) continue
            val target = if (undo) change.before else change.after
            if (target == null) { setRoom(change.live, RoomEdits(change.live.roomId)); plan.owner.rooms.remove(key) }
            else { setRoom(change.live, target); plan.owner.rooms[key] = change.live }
        }
        plan.owner.minimapEdits.clear()
        (if (undo) plan.mapsBefore else plan.mapsAfter).forEach { (key, value) -> plan.owner.minimapEdits[key] = value.toMutableList() }
        plan.owner.mapStationEdits.clear()
        (if (undo) plan.stationsBefore else plan.stationsAfter).forEach { (key, value) -> plan.owner.mapStationEdits[key] = value.toMutableList() }
    }

    fun apply(plan: AsmRoomAreaMovePlan, beforeTransition: () -> Unit, afterTransition: (Boolean) -> Unit): AsmRoomAreaMoveHistory {
        var history: AsmRoomAreaMoveHistory? = null
        fun checkCurrent(undo: Boolean) {
            requireCurrent(plan, undo)
            history?.beforeTransition?.invoke()
            beforeTransition()
        }
        fun notify(undo: Boolean) { changed(); afterTransition(undo); history?.onTransition?.invoke(undo) }
        checkCurrent(false)
        workspace.replaceProjectSources(plan.replacements,
            beforeApply = { setModel(plan, false) }, afterApply = { notify(false) },
            beforeUndo = { checkCurrent(true); setModel(plan, true) }, afterUndo = { notify(true) },
            beforeRedo = { checkCurrent(false); setModel(plan, false) }, afterRedo = { notify(false) })
        return AsmRoomAreaMoveHistory(workspace, plan.replacements, requireNotNull(workspace.sourceUndoHead(PLM_SOURCE_FILE))).also { history = it }
    }
}

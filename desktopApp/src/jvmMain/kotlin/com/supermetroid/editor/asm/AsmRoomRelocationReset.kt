package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.*

internal data class AsmRoomReturnPlan(val dependencies: AsmRoomAreaMovePlan, val outgoing: List<DoorChange>)

/** Reconstruct native ownership from the ROM, not from session move history. Thus a saved,
 * reopened room can return home without undoing intervening edits in unrelated rooms. */
internal class AsmRoomRelocationReset(
    private val catalog: AsmPlmSourceCatalog,
    private val workspace: AsmWorkspaceState,
    private val moves: AsmRoomAreaMove,
) {
    fun plan(owner: SmEditProject, room: RoomEdits, describe: (RomParser.PlmEntry) -> String): AsmRoomReturnPlan? {
        val parser = catalog.parser
        val text = workspace.projectSourceText(PLM_SOURCE_FILE)
        val properties = AsmRoomPropertiesSource(text)
        val doorSource by lazy { AsmDoorSource(workspace.projectSourceText(DOOR_SOURCE_FILE), text, catalog.symbols) }
        val linked = owner.rooms.values.filter { it.asmDoorSourceVersion != 0 }.flatMap { edits ->
            doorSource.resource(edits, parser) { id -> parser.readRoomHeader(id)?.let { h ->
                val change = owner.rooms[owner.roomKey(id)]?.effectiveHeaderChange
                (change?.width ?: h.width) to (change?.height ?: h.height)
            } }.entries.map { it.change.destRoomPtr }
        }
        val nativeRooms = (parser.roomCatalog.rooms.map { it.getRoomIdAsInt() } + owner.rooms.values.map { it.roomId } + linked)
            .distinct().mapNotNull { parser.readRoomHeader(it) }
        val native = requireNotNull(parser.readRoomHeader(room.roomId))
        fun effective(base: Room): Room {
            val edits = owner.rooms[owner.roomKey(base.roomId)] ?: return base
            val header = if (edits.asmRoomPropertiesSourceVersion != 0) properties.resource(edits).header else edits.effectiveHeaderChange
            return base.copy(area = header?.area ?: base.area, mapX = header?.mapX ?: base.mapX,
                mapY = header?.mapY ?: base.mapY, width = edits.roomHeaderChange?.width ?: base.width,
                height = edits.roomHeaderChange?.height ?: base.height)
        }
        val effectiveRooms = nativeRooms.map(::effective)
        val moving = effective(native)
        val relocated = moving.area != native.area || moving.mapX != native.mapX || moving.mapY != native.mapY
        fun doors(base: Room): List<DoorChange> {
            val edits = owner.rooms[owner.roomKey(base.roomId)]
            if (edits != null && edits.asmDoorSourceVersion != 0) return doorSource.resource(edits, parser) { id ->
                effectiveRooms.singleOrNull { it.roomId == id }?.let { it.width to it.height }
            }.entries.map { it.change }
            val changes = edits?.doorChanges.orEmpty().associateBy { it.doorIndex }
            return parser.parseDoorList(edits?.effectiveHeaderChange?.doorOut ?: base.doorOut).mapIndexed { index, entry ->
                changes[index] ?: DoorChange(index, entry.destRoomPtr, entry.bitflag, entry.doorCapCode,
                    entry.screenX, entry.screenY, entry.distFromDoor, entry.entryCode)
            }
        }
        fun flag(door: DoorChange, sourceArea: Int, targetArea: Int): DoorChange = door.copy(
            bitflag = if (sourceArea != targetArea) door.bitflag or 0x40 else door.bitflag and 0x40.inv())
        val incoming = effectiveRooms.filter { moving.area != native.area && it.roomId != room.roomId }.mapNotNull { source ->
            val original = requireNotNull(parser.readRoomHeader(source.roomId))
            val nativeDoors = parser.parseDoorList(original.doorOut)
            val owned = owner.rooms[owner.roomKey(source.roomId)]?.takeIf { it.asmDoorSourceVersion != 0 }
                ?.let { doorSource.resource(it, parser) { id -> effectiveRooms.singleOrNull { it.roomId == id }?.let { it.width to it.height } } }
            val changes = doors(source).filter { it.destRoomPtr == room.roomId }.mapNotNull { before ->
                val identity = owned?.entries?.getOrNull(before.doorIndex)?.identity ?: before.doorIndex
                val baseline = nativeDoors.getOrNull(identity)
                val after = if (source.area == original.area && baseline?.destRoomPtr == room.roomId)
                    before.copy(bitflag = (before.bitflag and 0x40.inv()) or (baseline.bitflag and 0x40))
                else flag(before, source.area, native.area)
                after.takeUnless { it == before }
            }
            source.roomId.takeIf { changes.isNotEmpty() }?.let { it to changes }
        }.toMap()
        // Reset restores native outgoing connections, but their *current* destination areas
        // still matter. Keep only the cross-area-bit correction, never a stale full override.
        val outgoing = parser.parseDoorList(native.doorOut).mapIndexedNotNull { index, entry ->
            val destination = effectiveRooms.singleOrNull { it.roomId == entry.destRoomPtr } ?: return@mapIndexedNotNull null
            if (destination.roomId == room.roomId || destination.area == parser.readRoomHeader(destination.roomId)?.area)
                return@mapIndexedNotNull null
            val before = DoorChange(index, entry.destRoomPtr, entry.bitflag, entry.doorCapCode,
                entry.screenX, entry.screenY, entry.distFromDoor, entry.entryCode)
            flag(before, native.area, destination.area).takeUnless { it == before }
        }
        if (!relocated && incoming.isEmpty() && outgoing.isEmpty()) return null

        val maps = owner.copy(minimapEdits = owner.minimapEdits.toMutableMap(), mapStationEdits = owner.mapStationEdits.toMutableMap())
        if (relocated) {
            require(moving.area in 0 until MinimapData.NUM_AREAS && native.area in 0 until MinimapData.NUM_AREAS) {
                "The room's area is outside the supported maps. Repair its header before resetting."
            }
            val nativeMask = roomMapOwnershipMask(native, nativeRooms.filter { it.area == native.area })
            val sourceMask = roomMapOwnershipMask(moving, effectiveRooms.filter { it.area == moving.area })
            var sourceMap = clearRoomTiles(effectiveMinimapData(parser, owner, moving.area), moving.mapX, moving.mapY,
                moving.width, moving.height, sourceMask)
            val sourceBaseline = parser.readMinimapTiles(moving.area)
            for (y in 0 until moving.height) for (x in 0 until moving.width) {
                val word = sourceBaseline.getTile(moving.mapX + x, moving.mapY + y)
                if (sourceMask[y][x] && isEmptyMinimapTile(word))
                    sourceMap = sourceMap.withTile(moving.mapX + x, moving.mapY + y, word)
            }
            val sourceReveal = clearMapStationRect(effectiveMapStationData(parser, owner, moving.area), moving.mapX, moving.mapY,
                moving.width, moving.height, sourceMask)
            persistMinimapData(parser, maps, sourceMap)
            persistMapStationData(parser, maps, sourceReveal)
            var targetMap = effectiveMinimapData(parser, maps, native.area)
            var targetReveal = effectiveMapStationData(parser, maps, native.area)
            val baselineMap = parser.readMinimapTiles(native.area)
            val baselineReveal = parser.readMapStationData(native.area)
            fun covers(candidate: Room, x: Int, y: Int) = candidate.area == native.area &&
                x in candidate.mapX until candidate.mapX + candidate.width && y in candidate.mapY until candidate.mapY + candidate.height
            for (y in 0 until native.height) for (x in 0 until native.width) {
                if (!nativeMask[y][x]) continue // Preserve vanilla nested rooms and ambiguous cells.
                val mapX = native.mapX + x; val mapY = native.mapY + y
                val occupant = effectiveRooms.firstOrNull { candidate ->
                    candidate.roomId != room.roomId && covers(candidate, mapX, mapY) &&
                        nativeRooms.singleOrNull { it.roomId == candidate.roomId }?.let { original ->
                            original.area == candidate.area && original.mapX == candidate.mapX && original.mapY == candidate.mapY &&
                                original.width == candidate.width && original.height == candidate.height
                        } != true
                }
                require(occupant == null) { "The room's original map location is occupied by ${occupant?.name}. Move that room before resetting." }
                val word = targetMap.getTile(mapX, mapY)
                require(isEmptyMinimapTile(word) || word == baselineMap.getTile(mapX, mapY)) {
                    "The room's original map cell ($mapX, $mapY) has new map art. Move or clear it before resetting."
                }
                require(!targetReveal.isRevealed(mapX, mapY) || baselineReveal.isRevealed(mapX, mapY)) {
                    "The room's original map cell ($mapX, $mapY) has new reveal data. Clear it before resetting."
                }
                targetMap = targetMap.withTile(mapX, mapY, baselineMap.getTile(mapX, mapY))
                targetReveal = targetReveal.withValue(mapX, mapY, baselineReveal.isRevealed(mapX, mapY))
            }
            persistMinimapData(parser, maps, targetMap)
            persistMapStationData(parser, maps, targetReveal)
            requireSaveConsumers(owner, room, effectiveRooms, text)
        }
        return AsmRoomReturnPlan(moves.plan(room.roomId,
            RoomHeaderChange(area = native.area, mapX = native.mapX, mapY = native.mapY), incoming, AreaSaveMigrationPlan(),
            maps.minimapEdits, maps.mapStationEdits, { RomParser.isItemPlm(it) }, describe), outgoing)
    }

    private fun requireSaveConsumers(owner: SmEditProject, room: RoomEdits, effectiveRooms: List<Room>, text: String) {
        val parser = catalog.parser
        val objects = AsmRoomObjectSource(text, catalog.symbols)
        val slots = buildSet {
            if (room.asmPlmSourceVersion != 0) objects.saveResources(room.roomId).forEach { add(it.area to it.slot) }
            room.saveStationSpawns.forEach { add(it.area to it.saveIndex) }
            for (area in 0 until MinimapData.NUM_AREAS) for (index in 0 until parser.saveEntryCount(area)) {
                if (parser.readSaveEntry(area, index)?.roomId == room.roomId) add(area to index)
            }
        }
        for (other in owner.rooms.values.filter { it.roomId != room.roomId }) {
            val claims = other.saveStationSpawns.map { it.area to it.saveIndex } +
                if (other.asmPlmSourceVersion != 0) objects.saveResources(other.roomId).map { it.area to it.slot } else emptyList()
            require(claims.none { it in slots }) { "Another room owns a save slot restored by this reset. Resolve that slot before resetting." }
        }
        for (other in effectiveRooms.filter { it.roomId != room.roomId }) {
            if (slots.none { it.first == other.area }) continue
            val edits = snapshotRoomForResize(owner.rooms[owner.roomKey(other.roomId)] ?: RoomEdits(other.roomId))
                .also { it.ensureStateManifest(parser) }
            val runtime = parser.parseRoomStatesWithData(other.roomId)
            val entries = if (edits.asmPlmSourceVersion != 0) objects.resources(edits).values.flatMap { it.entries } else
                edits.states.flatMap { state ->
                    val list = parser.parsePlmSet(runtime[requireNotNull(state.baseSourceStateIndex())].plmSetPtr).toMutableList()
                    for (change in edits.plmChanges + state.plmChanges) when (change.action) {
                        "add" -> list.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                        "remove" -> list.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y }
                    }
                    list
                }
            require(entries.none { it.id == 0xB76F && (other.area to (it.param and 255)) in slots }) {
                "${other.name} uses a save slot restored by this reset. Reassign its save station before resetting."
            }
        }
    }
}

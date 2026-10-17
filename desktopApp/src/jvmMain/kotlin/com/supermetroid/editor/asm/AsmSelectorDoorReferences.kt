package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.ProjectSourceDoorReference
import com.supermetroid.editor.rom.*

/** Validate live symbolic targets without assembler symbols or last-good door projections. */
internal fun validateSelectorDoorReferences(
    selectors: SourceRoomSelectors, targetRoomId: Int, owner: SmEditProject,
    catalog: AsmPlmSourceCatalog, workspace: AsmWorkspaceState,
    bank8F: String = workspace.projectSourceText(PLM_SOURCE_FILE),
) {
    val refs = selectors.conditions.values.flatMap { it.flattened() }.mapNotNull { it.sourceDoor }.distinct()
    validateSourceSelectorDoors(selectors, targetRoomId, liveSourceDoors(refs, owner, catalog, workspace, bank8F))
}

/** Read only referenced owners; an invalid unrelated door draft must not lock this section. */
internal fun liveSourceDoors(
    refs: Collection<ProjectSourceDoorReference>, owner: SmEditProject, catalog: AsmPlmSourceCatalog,
    workspace: AsmWorkspaceState, bank8F: String = workspace.projectSourceText(PLM_SOURCE_FILE),
    nativePointers: Collection<Int> = emptyList(),
): Map<Int, SourceRoomDoors> {
    val identities = AsmProjectRoomLabels.identities(owner)
    val ids = (refs.map { it.resolveRoomId(identities) } + owner.rooms.values.filter { room -> room.asmDoorSourceVersion != 0 &&
        catalog.parser.readRoomHeader(room.roomId)?.let { catalog.parser.parseDoorList(it.doorOut).any { door -> door.doorDefPtr in nativePointers } } == true
    }.map { it.roomId }).distinct()
    // Literal references to ordinary unowned native doors need no semantic source
    // bank. Only shared native pointers can have a global owner without room adoption.
    val sharedConsumers = if (nativePointers.none { it != 0 }) emptyMap() else nativeDoorConsumers(catalog.parser)
        .filter { (pointer, consumers) -> pointer in nativePointers && consumers.map { it.first }.distinct().size > 1 }
    if (ids.isEmpty() && sharedConsumers.isEmpty()) return emptyMap()
    val source = AsmDoorSource(workspace.projectSourceText(DOOR_SOURCE_FILE), bank8F, catalog.symbols, identities, owner.newRooms)
    val result = ids.associateWith { id ->
        val room = requireNotNull(owner.rooms[owner.roomKey(id)]) { "Referenced room has no source-owned doors." }
        require(room.asmDoorSourceVersion == 1) { "Referenced room has no source-owned doors." }
        source.resource(room, catalog.parser) { destination -> sourceRoomDimensions(owner, catalog.parser, destination) }
    }.toMutableMap()
    val shared = if (sharedConsumers.isEmpty()) emptyMap() else source.sharedResources(catalog.parser).filterKeys { it in sharedConsumers }
    if (shared.isNotEmpty()) {
        for (id in shared.keys.flatMap { sharedConsumers.getValue(it).map { pair -> pair.first } }.distinct().filter { it !in result }) {
            val header = requireNotNull(catalog.parser.readRoomHeader(id))
            result[id] = SourceRoomDoors(header.doorOut, catalog.parser.parseDoorList(header.doorOut).mapIndexed { index, door ->
                shared[door.doorDefPtr]?.let { it.copy(change = it.change.copy(doorIndex = index), identity = index) }
                    ?: SourceRoomDoor(door.doorDefPtr, com.supermetroid.editor.data.DoorChange(index, door.destRoomPtr, door.bitflag,
                        door.doorCapCode, door.screenX, door.screenY, door.distFromDoor, door.entryCode))
            })
        }
    }
    return result
}

internal fun sourceRoomDimensions(owner: SmEditProject, parser: RomParser, roomId: Int): Pair<Int, Int>? {
    val seed = owner.newRoomForPreviewId(roomId)?.header
    val native = if (seed == null) parser.readRoomHeader(roomId) else null
    val change = owner.rooms[owner.roomKey(roomId)]?.effectiveHeaderChange
    val width = change?.width ?: seed?.width ?: native?.width ?: return null
    val height = change?.height ?: seed?.height ?: native?.height ?: return null
    return width to height
}

package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.*

/** Validate live symbolic targets without assembler symbols or last-good door projections. */
internal fun validateSelectorDoorReferences(
    selectors: SourceRoomSelectors, targetRoomId: Int, owner: SmEditProject,
    catalog: AsmPlmSourceCatalog, workspace: AsmWorkspaceState,
    bank8F: String = workspace.projectSourceText(PLM_SOURCE_FILE),
) {
    val refs = selectors.conditions.values.flatMap { it.flattened() }.mapNotNull { it.sourceDoor }.distinct()
    if (refs.isEmpty()) return
    val source = AsmDoorSource(workspace.projectSourceText(DOOR_SOURCE_FILE), bank8F, catalog.symbols)
    val doors = refs.map { it.roomId }.distinct().associateWith { id ->
        val room = requireNotNull(owner.rooms[owner.roomKey(id)]) { "Referenced room has no source-owned doors." }
        require(room.asmDoorSourceVersion == 1) { "Referenced room has no source-owned doors." }
        source.resource(room, catalog.parser) { destination -> catalog.parser.readRoomHeader(destination)?.let { header ->
            val change = owner.rooms[owner.roomKey(destination)]?.effectiveHeaderChange
            (change?.width ?: header.width) to (change?.height ?: header.height)
        } }
    }
    validateSourceSelectorDoors(selectors, targetRoomId, doors)
}

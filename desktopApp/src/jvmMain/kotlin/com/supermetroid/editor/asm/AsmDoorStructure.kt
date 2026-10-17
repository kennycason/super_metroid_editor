package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*

internal data class AsmDoorStructurePlan(
    val owner: SmEditProject, val live: RoomEdits, val wasPresent: Boolean,
    val before: RoomEdits, val after: RoomEdits,
    val replacements: Map<String, Pair<String, String>>, val revision: Long,
    val externalReferences: Set<String>, val removed: SourceRoomDoor?,
    val added: SourceRoomDoor?,
)

/** Structural edits stage all layouts and door order together. No addresses are persisted or guessed. */
internal class AsmDoorStructure(
    private val catalog: AsmPlmSourceCatalog, private val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject, private val doors: AsmDoorSourceBridge,
    private val layouts: AsmRoomLayoutBridge, private val context: AsmRoomSourceContext = AsmRoomSourceContext(catalog, project), private val changed: () -> Unit,
) {
    private fun external() = workspace.projectSourceReadersExcept(setOf(PLM_SOURCE_FILE, DOOR_SOURCE_FILE))
        .flatMap { resetSourceReferences(it()) }.toSet()

    fun plan(snapshot: RoomEdits, levels: Map<String, ByteArray>, addition: RomParser.DoorEntry? = null,
             removeIndex: Int? = null, linkState: String? = null, linkEdits: List<TileEdit> = emptyList()): AsmDoorStructurePlan {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) {
            "Structural doors require ASM source mode."
        }
        require((addition == null) != (removeIndex == null)) { "Choose one structural door operation." }
        val key = owner.roomKey(snapshot.roomId)
        val live = owner.rooms[key] ?: snapshot
        val before = snapshotRoomForResize(live)
        val staged = snapshotRoomForResize(live).also { it.ensureStateManifest(context.parser) }
        val native = requireNotNull(context.parser.readRoomHeader(staged.roomId))
        val width = (staged.effectiveHeaderChange?.width ?: native.width) * 16
        val height = (staged.effectiveHeaderChange?.height ?: native.height) * 16
        val before8F = workspace.projectSourceText(PLM_SOURCE_FILE)
        val before83 = workspace.projectSourceText(DOOR_SOURCE_FILE)
        var text83 = if (staged.asmDoorSourceVersion == 0) {
            // Empty native rooms have no ordinary preview, but their first structural edit is valid.
            if (context.parser.parseDoorList(native.doorOut).isEmpty()) before83 else doors.automaticSource(staged, native.name)
        } else before83
        var text8F = before8F
        fun append(document: String, addition: String): String {
            val newline = if (document.contains("\r\n")) "\r\n" else "\n"
            return document + newline + addition.replace("\r\n", "\n").replace("\n", newline)
        }
        staged.doorChanges.clear(); staged.asmDoorSourceVersion = 1
        val source = if (context.parser.parseDoorList(native.doorOut).isEmpty() && doors.isManaged(staged.roomId).not())
            SourceRoomDoors(if (context.identities()[staged.roomId] == null) native.doorOut else 0, emptyList(), structural = true, projectRoomId = context.identities()[staged.roomId])
        else AsmDoorSource(text83, text8F, catalog.symbols, context.identities(), owner.newRooms).resource(staged, context.parser, ::dimensions)
        validateSourceDoorOwnership(staged.roomId, source, context.parser)
        require(levels.keys == staged.states.map { it.id }.toSet()) { "All room states must be readable before editing door structure." }
        if (staged.asmLayoutSourceVersion == 0) {
            require(!Regex("(?m)^macro ${AsmRoomLayoutSource.prefix(staged.roomId, context.identities()[staged.roomId])}").containsMatchIn(text8F)) {
                "Retained layout declarations need review before changing door structure."
            }
            text8F = append(text8F, layouts.preview(staged, native.name, levels))
            fun strip(operations: MutableList<EditOperation>) {
                val retained = operations.map { it.copy(edits = emptyList()) }.filter { it.plmAdds.isNotEmpty() || it.plmRemoves.isNotEmpty() ||
                    it.enemyAdds.isNotEmpty() || it.enemyRemoves.isNotEmpty() || it.enemyUpdates.isNotEmpty() || it.scrollEdits.isNotEmpty() ||
                    it.stateDataBefore != null || it.stateDataAfter != null || it.fxBefore != null || it.fxAfter != null }
                operations.clear(); operations.addAll(retained)
            }
            strip(staged.operations); staged.states.forEach { strip(it.operations) }; staged.levelResourceOperations.values.forEach(::strip)
            staged.levelResourceOperations.entries.removeAll { it.value.isEmpty() }; staged.asmLayoutSourceVersion = 1
        }
        val resources = AsmRoomLayoutSource(text8F, context.identities()).resources(staged, width, height)
        require(resources.all { (id, resource) -> resource.bytes().contentEquals(levels.getValue(id)) }) { "Room layout source changed; refresh before changing door structure." }
        val entries = source.entries.toMutableList()
        val removed = removeIndex?.let { index ->
            require(index in entries.indices) { "This connection changed; reopen it." }
            entries[index].also { requireRemovable(staged.roomId, it) }
        }
        if (removeIndex != null) {
            for ((stateId, resource) in resources.entries.distinctBy { it.value.identity }) {
                val edits = buildList {
                    val layerBytes = resource.values[0] or (resource.values[1] shl 8)
                    for (i in 0 until width * height) {
                        val word = resource.values[2 + i * 2] or (resource.values[3 + i * 2] shl 8)
                        if (word ushr 12 != 9) continue
                        val bts = resource.values[2 + layerBytes + i]
                        val index = bts and 0x7F
                        require(index != removeIndex) { "State $stateId: connection ${removeIndex + 1} is still used by doorway tile (${i % width}, ${i / width}). Erase or relink it first." }
                        if (index > removeIndex) add(TileEdit(i % width, i / width, word, word, bts, (bts and 0x80) or (index - 1)))
                    }
                }
                text8F = AsmRoomLayoutSource(text8F, context.identities()).edit(staged, stateId, width, height, edits)
            }
            entries.removeAt(removeIndex)
        } else {
            require(entries.size < 16) { "A room supports at most 16 connections." }
            val identity = AsmDoorSource(text83, projectRoomIds = context.identities(), projectRooms = owner.newRooms).nextIdentity(staged.roomId, context.parser.parseDoorList(native.doorOut).size)
            val d = requireNotNull(addition)
            val door = SourceRoomDoor(0, DoorChange(entries.size, d.destRoomPtr, d.bitflag, d.doorCapCode, d.screenX, d.screenY, d.distFromDoor, d.entryCode), identity = identity, destinationProjectId = context.identities()[d.destRoomPtr])
            entries.add(door)
            text83 = append(text83, AsmDoorSource.renderDoor(staged.roomId, door, context.identities()[staged.roomId]))
            if (linkEdits.isNotEmpty()) text8F = AsmRoomLayoutSource(text8F, context.identities()).edit(staged, requireNotNull(linkState), width, height, linkEdits)
        }
        text83 = AsmDoorSource(text83, text8F, catalog.symbols, context.identities(), owner.newRooms).withOrder(staged.roomId, entries.map { it.identity })
        val authored = AsmDoorSource(text83, text8F, catalog.symbols, context.identities(), owner.newRooms).resource(staged, context.parser, ::dimensions)
        AsmRoomLayoutSource(text8F, context.identities()).resources(staged, width, height).values.forEach { validateStructuralDoorLayout(it, authored.entries.size) }
        staged.asmDoorProjection = authored.entries.map { it.change }
        return AsmDoorStructurePlan(owner, live, owner.rooms[key] != null, before, staged,
            linkedMapOf(PLM_SOURCE_FILE to (before8F to text8F), DOOR_SOURCE_FILE to (before83 to text83)), workspace.sourceBufferRevision,
            external(), removed, if (addition != null) authored.entries.last() else null)
    }

    private fun dimensions(id: Int) = context.parser.readRoomHeader(id)?.let { native ->
        val header = project().rooms[project().roomKey(id)]?.effectiveHeaderChange
        (header?.width ?: native.width) to (header?.height ?: native.height)
    }

    /** Native pointer users retain stable addresses; removal must not silently orphan modeled behavior. */
    private fun requireRemovable(roomId: Int, door: SourceRoomDoor) {
        val pointer = door.doorDefPtr
        val entry = AsmDoorSource.entryLabel(roomId, door.identity, context.identities()[roomId])
        val list = Regex("(?m)^macro ${AsmDoorSource.listName(roomId, context.identities()[roomId])}\\(\\)[^\\r\\n]*\\r?\\n[\\s\\S]*?^endmacro[^\\r\\n]*(?:\\r?\\n|$)")
        val otherTexts = workspace.projectSourceReadersExcept(setOf(DOOR_SOURCE_FILE)).map { it() } +
            workspace.projectSourceText(DOOR_SOURCE_FILE).replace(list, "")
        require(otherTexts.none { Regex("\\b${Regex.escape(entry)}\\b").containsMatchIn(it.lineSequence().joinToString("\n") { line -> asmCodeBeforeComment(line) }) }) {
            "Connection ${door.change.doorIndex + 1} has an explicit ASM DoorDef reference. Retarget it before removing the connection."
        }
        require(project().rooms.values.none { edits -> edits.states.any { state -> state.effectiveCondition.flattened().any {
            it.sourceDoor == (context.identities()[roomId]?.let { ProjectSourceDoorReference(0, door.identity, it) } ?: ProjectSourceDoorReference(roomId, door.identity))
        } } }) { "A state condition uses this DoorDef identity. Retarget it before removing the connection." }
        if (pointer == 0) return
        fun references(c: ProjectRoomStateCondition): Boolean =
            (c.argumentKind == ProjectRoomStateConditionArgumentKind.DOOR_POINTER && c.argument == pointer) || c.children.any(::references)
        val owner = project()
        val source8F = workspace.projectSourceText(PLM_SOURCE_FILE)
        val source83 = workspace.projectSourceText(DOOR_SOURCE_FILE)
        for (id in (context.parser.roomCatalog.rooms.map { it.getRoomIdAsInt() } + owner.rooms.values.map { it.roomId }).distinct()) {
            val edits = owner.rooms[owner.roomKey(id)]
            val inspected = context.parser.inspectRoomStates(id)
            fun fromNative(c: RoomStateCondition): ProjectRoomStateCondition = projectRoomStateCondition(
                ProjectRoomStateConditionKind.valueOf(c.kind.name), c.argument, c.negated, c.children.map(::fromNative))
            val conditions = if (edits?.asmStateSelectorsSourceVersion != 0 && edits != null)
                AsmRoomSelectorsSource(source8F, context.identities()).resource(edits).conditions.values
            else if (edits?.stateGraphChanged == true) edits.states.map { it.effectiveCondition }
            else inspected.states.map { fromNative(it.condition) }
            require(conditions.none(::references)) { "Room ${id.toString(16)} has a state condition using this DoorDef. Retarget it first." }
            val fx = if (edits?.asmFxSourceVersion != 0 && edits != null) AsmFxSource(source83, context.identities()).resources(edits).values.flatMap { it.entries }
                else context.parser.parseRoomStatesWithData(id).flatMap { context.parser.parseFxEntries(it.fxPtr) }
            require(fx.none { it.doorSelect == pointer } && edits?.states.orEmpty().none { state -> state.doorFxChanges.keys.any { it.toIntOrNull(16) == pointer } }) {
                "Room ${id.toString(16)} has door-specific FX using this DoorDef. Retarget it first."
            }
            require(edits?.saveStationSpawns.orEmpty().none { !it.clearSlot && it.doorPtr == pointer }) { "A save spawn still uses this DoorDef. Retarget it first." }
            if (edits?.asmPlmSourceVersion != 0 && edits != null) require(AsmRoomObjectSource(source8F, catalog.symbols, context.identities()).saveResources(id).none { !it.cleared && it.words[1] == pointer }) {
                "An authored save spawn still uses this DoorDef. Retarget it first."
            }
        }
        for (area in 0..6) for (slot in 0 until context.parser.saveEntryCount(area)) {
            val native = context.parser.readSaveEntry(area, slot) ?: continue
            if (native.doorPtr != pointer) continue
            val authored = owner.rooms.values.filter { it.asmPlmSourceVersion != 0 }.flatMap { AsmRoomObjectSource(source8F, catalog.symbols).saveResources(it.roomId) }
                .singleOrNull { it.area == area && it.slot == slot }
            val legacy = owner.rooms.values.flatMap { it.saveStationSpawns }.singleOrNull { it.area == area && it.saveIndex == slot }
            require(authored?.let { it.cleared || it.words[1] != pointer } ?: legacy?.let { it.clearSlot || it.doorPtr != pointer } ?: false) {
                "Load station $area:$slot uses this DoorDef. Retarget the spawn before removing it."
            }
        }
    }

    fun apply(plan: AsmDoorStructurePlan, beforeTransition: () -> Unit, afterTransition: (Boolean) -> Unit): AsmRoomAreaMoveHistory {
        val key = plan.owner.roomKey(plan.live.roomId)
        var history: AsmRoomAreaMoveHistory? = null
        fun check(undo: Boolean) {
            require(project() === plan.owner && plan.owner.asmWorkspace.enabled && plan.owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Restore the existing-room ASM workspace first." }
            require(plan.owner.rooms[key] === (if (undo || plan.wasPresent) plan.live else null) && plan.live == if (undo) plan.after else plan.before) { "Undo newer room edits before restoring door structure." }
            require(external() == plan.externalReferences) { "External ASM references changed; undo those edits before restoring door structure." }
            if (undo) plan.added?.let { requireRemovable(plan.live.roomId, it) }
            if (!undo) plan.removed?.let { requireRemovable(plan.live.roomId, it) }
            history?.beforeTransition?.invoke(); beforeTransition()
        }
        fun set(undo: Boolean) {
            val target = if (undo) plan.before else plan.after
            if (plan.live.states.map { it.id } != target.states.map { it.id }) {
                plan.live.states.clear(); plan.live.states.addAll(snapshotRoomForResize(target).states)
            }
            applyRoomResizeModel(plan.live, target)
            if (undo && !plan.wasPresent) plan.owner.rooms.remove(key) else plan.owner.rooms[key] = plan.live
        }
        fun notify(undo: Boolean) { doors.refreshAll(); changed(); afterTransition(undo); history?.onTransition?.invoke(undo) }
        check(false)
        require(workspace.sourceBufferRevision == plan.revision) { "Source changed while planning door structure; retry." }
        workspace.replaceProjectSources(plan.replacements, beforeApply = { set(false) }, afterApply = { notify(false) },
            beforeUndo = { check(true); set(true) }, afterUndo = { notify(true) }, beforeRedo = { check(false); set(false) }, afterRedo = { notify(false) })
        return AsmRoomAreaMoveHistory(workspace, plan.replacements, requireNotNull(workspace.sourceUndoHead(PLM_SOURCE_FILE)), "door structure edit").also { history = it }
    }
}

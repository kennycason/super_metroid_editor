package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import kotlinx.serialization.json.Json

internal data class AsmRoomPropertiesView(val resource: SourceRoomProperties?, val problem: String?)

internal fun SourceRoomProperties.projectInto(room: RoomEdits) {
    room.asmHeaderPropertiesProjection = header
    room.states.forEach { it.asmStatePropertiesProjection = states[it.id] }
}

/** One authored buffer for header scalars and state-local ordinary metadata. */
internal class AsmRoomPropertiesBridge(
    private val catalog: AsmPlmSourceCatalog,
    private val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject,
    private val changed: () -> Unit,
) {
    private data class Revision(val text: String, val identities: Map<Int, String>)
    private var revision: Pair<Revision, AsmRoomPropertiesSource>? = null
    private val lastGood = mutableMapOf<String, SourceRoomProperties>()
    internal var projectRoomParser: (() -> RomParser)? = null
    private fun identity(roomId: Int) = project().newRoomForPreviewId(roomId)?.let { "project:${it.id}" } ?: "native:$roomId"
    private fun currentRoomId(owner: SmEditProject, nativeId: Int, projectId: String?): Int = if (projectId == null) nativeId else
        requireNotNull(owner.newRooms.singleOrNull { it.id == projectId }?.previewRoomId?.takeIf { it != 0 }) { "This project room no longer has a unique workspace identity." }
    private fun ownershipValue(room: RoomEdits, projectId: String?) = Json.encodeToString(RoomEdits.serializer(),
        if (projectId == null) room else room.copy(roomId = 0)) // Preview relocation is not a semantic edit.
    private fun ensureManifest(room: RoomEdits) {
        if (room.states.isNotEmpty()) return
        val parser = if (project().newRoomForPreviewId(room.roomId) == null) catalog.parser else
            requireNotNull(projectRoomParser?.invoke()) { "Load the project room workspace before editing its source." }
        room.ensureStateManifest(parser)
    }
    fun document(text: String) = AsmRoomPropertiesSource(text, AsmRoomPropertiesSource.projectIdentities(project()))
    private fun source(): AsmRoomPropertiesSource {
        val text = workspace.projectSourceText(PLM_SOURCE_FILE)
        val key = Revision(text, AsmRoomPropertiesSource.projectIdentities(project()))
        return revision?.takeIf { it.first == key }?.second ?: document(text).also { revision = key to it }
    }
    fun isManaged(roomId: Int) = (project().rooms[project().roomKey(roomId)]?.asmRoomPropertiesSourceVersion ?: 0) != 0
    fun view(roomId: Int): AsmRoomPropertiesView = try {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val resource = source().resource(room)
        lastGood[identity(roomId)] = resource
        AsmRoomPropertiesView(resource, null)
    } catch (error: Exception) { AsmRoomPropertiesView(lastGood[identity(roomId)], error.message ?: "Invalid room properties.") }

    /** Transient previews make existing room consumers source-aware without serializing source values. */
    fun refreshAll() {
        for (room in project().rooms.values) {
            if (room.asmRoomPropertiesSourceVersion != 0) view(room.roomId).resource?.projectInto(room)
            else {
                room.asmHeaderPropertiesProjection = null
                room.states.forEach { it.asmStatePropertiesProjection = null }
            }
        }
    }
    fun nativeHeader(roomId: Int): RoomHeaderChange {
        project().newRoomForPreviewId(roomId)?.header?.let { h ->
            return RoomHeaderChange(index = h.index, area = h.area, mapX = h.mapX, mapY = h.mapY,
                upScroller = h.upScroller, downScroller = h.downScroller, creBitflag = h.creBitflag)
        }
        val h = requireNotNull(catalog.parser.readRoomHeader(roomId))
        return RoomHeaderChange(index = h.index, area = h.area, mapX = h.mapX, mapY = h.mapY,
            upScroller = h.upScroller, downScroller = h.downScroller, creBitflag = h.creBitflag)
    }
    fun nativeState(room: RoomEdits, stateId: String): StateDataChange {
        val index = requireNotNull(room.states.single { it.id == stateId }.baseSourceStateIndex())
        project().newRoomForPreviewId(room.roomId)?.initialState?.let { state ->
            require(index == 0) { "A project-created state must retain its seed-state provenance." }
            return StateDataChange(state.tileset, state.musicData, state.musicTrack, state.bgScrolling)
        }
        val state = catalog.parser.parseRoomStatesWithData(room.roomId)[index]
        return StateDataChange(state.tileset, state.musicData, state.musicTrack, state.bgScrolling)
    }
    private fun render(room: RoomEdits, name: String, resource: SourceRoomProperties) =
        AsmRoomPropertiesSource.render(room, name, resource, project().newRoomForPreviewId(room.roomId)?.id)
    fun preview(room: RoomEdits, name: String): String = render(room, name, effectiveProperties(room))
    private fun effectiveProperties(room: RoomEdits) = SourceRoomProperties(nativeHeader(room.roomId).over(room.effectiveHeaderChange), room.states.associate {
        it.id to nativeState(room, it.id).over(room.effectiveStateDataChange).over(it.effectiveStateDataChange)
    })
    private fun StateDataChange.over(override: StateDataChange?) = copy(tileset = override?.tileset ?: tileset,
        musicData = override?.musicData ?: musicData, musicTrack = override?.musicTrack ?: musicTrack, bgScrolling = override?.bgScrolling ?: bgScrolling)
    private fun RoomHeaderChange.over(override: RoomHeaderChange?) = copy(index = override?.index ?: index,
        area = override?.area ?: area, mapX = override?.mapX ?: mapX, mapY = override?.mapY ?: mapY,
        upScroller = override?.upScroller ?: upScroller, downScroller = override?.downScroller ?: downScroller,
        creBitflag = override?.creBitflag ?: creBitflag)

    private fun adoptScalars(room: RoomEdits, resource: SourceRoomProperties) {
        room.roomHeaderChange = room.roomHeaderChange?.let { RoomHeaderChange(width = it.width, height = it.height, doorOut = it.doorOut) }
            ?.takeUnless { it == RoomHeaderChange() }
        room.stateDataChange = null; room.states.forEach { it.stateDataChange = null }
        room.asmRoomPropertiesSourceVersion = 1
        resource.projectInto(room)
    }

    /** A full editor document, but not a source/model mutation until the first actual text change. */
    fun automaticSource(room: RoomEdits, name: String): String {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) {
            "Room properties require ASM source mode."
        }
        require(!isManaged(room.roomId)) { "Room-property ownership changed; reopen its source." }
        val before = workspace.projectSourceText(PLM_SOURCE_FILE)
        require(!Regex("(?im)^[ \\t]*macro[ \\t]+${Regex.escape(document(before).roomPrefix(room.roomId))}").containsMatchIn(before)) {
            "Retained room-property declarations need review before adoption."
        }
        val resource = effectiveProperties(room)
        val staged = snapshotRoomForResize(room).also { adoptScalars(it, resource) }
        val newline = if (before.contains("\r\n")) "\r\n" else "\n"
        val separator = if (before.isEmpty() || before.endsWith(newline + newline)) "" else if (before.endsWith(newline)) newline else newline + newline
        val after = before + separator + render(room, name, resource).replace("\n", newline)
        document(after).resource(staged)
        return after
    }

    /** First typing and ownership retirement are one reversible action; invalid drafts remain editable. */
    fun editUnmanaged(roomId: Int, name: String, expected: String, replacement: String,
                      afterUndo: () -> Unit, afterRedo: () -> Unit) {
        val owner = project()
        val projectId = owner.newRoomForPreviewId(roomId)?.id
        fun key() = owner.roomKey(currentRoomId(owner, roomId, projectId))
        val existing = owner.rooms[key()]
        var live = existing ?: RoomEdits(roomId)
        val original = snapshotRoomForResize(live)
        val staged = snapshotRoomForResize(live).also(::ensureManifest)
        require(automaticSource(staged, name) == expected) { "Room-property source changed; reopen its current link before editing." }
        require(expected != replacement) { "No room-property source change to apply." }
        val resource = effectiveProperties(staged)
        adoptScalars(staged, resource)
        val before = workspace.projectSourceText(PLM_SOURCE_FILE)
        fun resolve() {
            if (projectId == null) return
            val id = currentRoomId(owner, roomId, projectId)
            live = owner.rooms[key()] ?: if (live.roomId == id) live else snapshotRoomForResize(live).copy(roomId = id)
        }
        fun checkCurrent(undo: Boolean) {
            resolve()
            require(project() === owner && owner.rooms[key()] === (if (undo || existing != null) live else null) &&
                ownershipValue(live, projectId) == ownershipValue(if (undo) staged else original, projectId)) {
                "Room data changed; undo newer edits before restoring room-property ownership."
            }
        }
        fun setRoom(undo: Boolean) {
            resolve()
            val target = if (undo) original else staged
            if (original.states.isEmpty()) { live.states.clear(); live.states.addAll(snapshotRoomForResize(target).states) }
            live.roomHeaderChange = target.roomHeaderChange; live.stateDataChange = target.stateDataChange
            live.asmRoomPropertiesSourceVersion = target.asmRoomPropertiesSourceVersion
            live.asmHeaderPropertiesProjection = target.asmHeaderPropertiesProjection
            live.states.zip(target.states).forEach { (state, saved) ->
                state.stateDataChange = saved.stateDataChange; state.asmStatePropertiesProjection = saved.asmStatePropertiesProjection
            }
            if (undo && existing == null) owner.rooms.remove(key()) else owner.rooms[key()] = live
        }
        fun notify() { refreshAll(); changed() }
        checkCurrent(false)
        workspace.replaceProjectSources(mapOf(PLM_SOURCE_FILE to (before to replacement)),
            beforeApply = { setRoom(false) }, afterApply = { lastGood[identity(roomId)] = resource; notify() },
            beforeUndo = { checkCurrent(true); setRoom(true) }, afterUndo = { notify(); afterUndo() },
            beforeRedo = { checkCurrent(false); setRoom(false) }, afterRedo = { notify(); afterRedo() })
    }

    fun migrate(roomId: Int, name: String) {
        if (isManaged(roomId)) return
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Room properties require ASM source mode." }
        val room = owner.rooms.getValue(owner.roomKey(roomId))
        val projectId = owner.newRoomForPreviewId(roomId)?.id
        ensureManifest(room)
        val beforeModel = snapshotRoomForResize(room)
        val staged = snapshotRoomForResize(room)
        val resource = effectiveProperties(room)
        adoptScalars(staged, resource)
        val current = source()
        require(!Regex("(?m)^macro ${Regex.escape(current.roomPrefix(roomId))}").containsMatchIn(current.text)) { "Existing room-property declarations need review before adoption." }
        val after = current.text.trimEnd() + "\n\n" + render(room, name, resource)
        document(after).resource(staged)
        fun transition(expected: RoomEdits, target: RoomEdits) {
            val active = requireNotNull(owner.rooms[owner.roomKey(currentRoomId(owner, roomId, projectId))]) { "Room-property ownership changed." }
            require(project() === owner && (projectId != null || active === room) &&
                ownershipValue(active, projectId) == ownershipValue(expected, projectId)) { "Undo newer room/state changes before restoring room-property ownership." }
            active.asmRoomPropertiesSourceVersion = target.asmRoomPropertiesSourceVersion
            active.roomHeaderChange = target.roomHeaderChange
            active.stateDataChange = target.stateDataChange
            active.asmHeaderPropertiesProjection = target.asmHeaderPropertiesProjection
            active.states.zip(target.states).forEach { (state, saved) ->
                state.stateDataChange = saved.stateDataChange
                state.asmStatePropertiesProjection = saved.asmStatePropertiesProjection
            }
            changed()
        }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, after,
            beforeUndo = { transition(staged, beforeModel) }, beforeRedo = { transition(beforeModel, staged) })
        transition(beforeModel, staged)
    }
    fun editHeader(roomId: Int, change: RoomHeaderChange) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val newRoom = project().newRoomForPreviewId(roomId)
        val header = if (newRoom == null) requireNotNull(catalog.parser.readRoomHeader(roomId)) else null
        val width = newRoom?.header?.width ?: header!!.width
        val height = newRoom?.header?.height ?: header!!.height
        require((change.width ?: width) == (room.roomHeaderChange?.width ?: width) &&
            (change.height ?: height) == (room.roomHeaderChange?.height ?: height) &&
            (change.doorOut ?: header?.doorOut) == (room.roomHeaderChange?.doorOut ?: header?.doorOut)) { "Use Resize or Doors to change dimensions or door-list ownership together." }
        require(view(roomId).problem == null) { view(roomId).problem ?: "Invalid room properties." }
        val current = source()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, current.editHeader(room, nativeHeader(roomId).over(change)))
        refreshAll()
    }
    fun editState(roomId: Int, stateId: String, change: StateDataChange?) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        require(view(roomId).problem == null) { view(roomId).problem ?: "Invalid room properties." }
        val current = source()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, current.editStates(room, mapOf(stateId to nativeState(room, stateId).over(change))))
        refreshAll()
    }
    fun cloneState(roomId: Int, template: String, newId: String) {
        if (!isManaged(roomId)) return
        val room = project().rooms.getValue(project().roomKey(roomId))
        val current = source()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, current.cloneState(room, template, newId), beforeUndo = {
            require(room.states.none { it.id == newId }) { "Delete the new state before undoing its property declaration." }
        })
    }
    fun line(roomId: Int, stateId: String? = null): Int = source().line(roomId, stateId)
    fun hasState(roomId: Int, stateId: String): Boolean = isManaged(roomId) && source().hasState(roomId, stateId)
}

package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.rom.SourceRoomLayoutResource
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.ensureStateManifest
import kotlinx.serialization.json.Json

internal data class AsmRoomLayoutView(val resource: SourceRoomLayoutResource?, val problem: String?)

internal class AsmRoomLayoutBridge(
    private val catalog: AsmPlmSourceCatalog,
    private val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject,
    private val changed: () -> Unit,
) {
    private data class Revision(val text: String, val identities: Map<Int, String>)
    private var revision: Pair<Revision, AsmRoomLayoutSource>? = null
    private val lastGood = mutableMapOf<Pair<String, String>, SourceRoomLayoutResource>()
    internal var projectRoomParser: (() -> RomParser)? = null
    private fun identity(roomId: Int) = project().newRoomForPreviewId(roomId)?.let { "project:${it.id}" } ?: "native:$roomId"
    fun document(text: String) = AsmRoomLayoutSource(text, AsmProjectRoomLabels.identities(project()))
    private fun source(): AsmRoomLayoutSource {
        val text = workspace.projectSourceText(PLM_SOURCE_FILE)
        val key = Revision(text, AsmProjectRoomLabels.identities(project()))
        return revision?.takeIf { it.first == key }?.second ?: document(text).also { revision = key to it }
    }
    private fun currentRoom(owner: SmEditProject, native: RoomEdits, projectId: String?): RoomEdits {
        require(project() === owner) { "The ASM project changed." }
        val id = if (projectId == null) native.roomId else requireNotNull(owner.newRooms.singleOrNull { it.id == projectId }
            ?.previewRoomId?.takeIf { it != 0 }) { "This project room no longer has a unique workspace identity." }
        val room = requireNotNull(owner.rooms[owner.roomKey(id)]) { "Room layout ownership changed." }
        require(projectId != null || room === native) { "Room layout ownership changed." }
        return room
    }
    private fun ownership(room: RoomEdits, projectId: String?) = Json.encodeToString(RoomEdits.serializer(),
        if (projectId == null) room else room.copy(roomId = 0))
    private fun dimensions(room: RoomEdits): Pair<Int, Int> {
        val seed = project().newRoomForPreviewId(room.roomId)?.header
        val header = if (seed == null) requireNotNull(catalog.parser.readRoomHeader(room.roomId)) else null
        return (room.effectiveHeaderChange?.width ?: seed?.width ?: header!!.width) * 16 to
            (room.effectiveHeaderChange?.height ?: seed?.height ?: header!!.height) * 16
    }
    fun isManaged(roomId: Int) = (project().rooms[project().roomKey(roomId)]?.asmLayoutSourceVersion ?: 0) != 0
    fun view(roomId: Int, stateId: String): AsmRoomLayoutView = try {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val (width, height) = dimensions(room)
        val inputs = source().resources(room, width, height)
        val resource = inputs.getValue(stateId)
        lastGood[identity(roomId) to stateId] = resource
        AsmRoomLayoutView(resource, null)
    } catch (error: Exception) { AsmRoomLayoutView(lastGood[identity(roomId) to stateId], error.message ?: "Invalid room layout source.") }

    private fun adoptLayouts(room: RoomEdits) {
        fun strip(operations: MutableList<com.supermetroid.editor.data.EditOperation>) {
            val retained = operations.map { it.copy(edits = emptyList()) }.filter { it.plmAdds.isNotEmpty() || it.plmRemoves.isNotEmpty() ||
                it.enemyAdds.isNotEmpty() || it.enemyRemoves.isNotEmpty() || it.enemyUpdates.isNotEmpty() || it.scrollEdits.isNotEmpty() ||
                it.stateDataBefore != null || it.stateDataAfter != null || it.fxBefore != null || it.fxAfter != null }
            operations.clear(); operations.addAll(retained)
        }
        strip(room.operations); room.states.forEach { strip(it.operations) }
        room.levelResourceOperations.values.forEach(::strip); room.levelResourceOperations.entries.removeAll { it.value.isEmpty() }
        room.asmLayoutSourceVersion = 1
    }

    private fun applyLayouts(live: RoomEdits, target: RoomEdits) {
        live.asmLayoutSourceVersion = target.asmLayoutSourceVersion
        live.operations.clear(); live.operations.addAll(target.operations)
        live.levelResourceOperations.clear(); target.levelResourceOperations.forEach { (id, ops) -> live.levelResourceOperations[id] = ops.toMutableList() }
        live.states.zip(target.states).forEach { (state, saved) -> state.operations.clear(); state.operations.addAll(saved.operations) }
    }

    /** A full editor-only bank: inspection, Save and Build still see the untouched real source. */
    fun automaticSource(room: RoomEdits, roomName: String, levels: Map<String, ByteArray>): String {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Layout source requires ASM mode." }
        require(!isManaged(room.roomId)) { "Layout ownership changed; reopen its source." }
        require(room.states.isNotEmpty() && levels.keys == room.states.map { it.id }.toSet()) { "Layout state coverage is incomplete." }
        val before = workspace.projectSourceText(PLM_SOURCE_FILE)
        require(!Regex("(?im)^[ \\t]*macro[ \\t]+${Regex.escape(document(before).roomPrefix(room.roomId))}").containsMatchIn(before)) {
            "Retained layout declarations need review before adoption."
        }
        val staged = snapshotRoomForResize(room).also(::adoptLayouts)
        val (width, height) = dimensions(room)
        val newline = if (before.contains("\r\n")) "\r\n" else "\n"
        val separator = if (before.isEmpty() || before.endsWith(newline + newline)) "" else if (before.endsWith(newline)) newline else newline + newline
        val after = before + separator + preview(room, roomName, levels).replace("\n", newline)
        document(after).resources(staged, width, height)
        return after
    }

    /** First text edit atomically adopts all state layouts, including an incomplete saveable draft. */
    fun editUnmanaged(roomId: Int, roomName: String, levels: Map<String, ByteArray>, expected: String, replacement: String,
                      afterUndo: () -> Unit, afterRedo: () -> Unit) {
        val owner = project()
        val projectId = owner.newRoomForPreviewId(roomId)?.id
        val durableIdentity = projectId?.let { "project:$it" } ?: "native:$roomId"
        fun currentId() = if (projectId == null) roomId else requireNotNull(owner.newRooms.singleOrNull { it.id == projectId }
            ?.previewRoomId?.takeIf { it != 0 }) { "This project room no longer has a unique workspace identity." }
        fun key() = owner.roomKey(currentId())
        val existing = owner.rooms[key()]
        var live = existing ?: RoomEdits(roomId)
        val original = snapshotRoomForResize(live)
        val staged = snapshotRoomForResize(live).also { room ->
            if (room.states.isEmpty()) room.ensureStateManifest(if (projectId == null) catalog.parser else
                requireNotNull(projectRoomParser?.invoke()) { "Load the project room workspace before editing its layout." })
        }
        require(automaticSource(staged, roomName, levels) == expected) { "Layout source changed; reopen its current link before editing." }
        require(expected != replacement) { "No layout source change to apply." }
        adoptLayouts(staged)
        val (width, height) = dimensions(staged)
        val initial = document(expected).resources(staged, width, height)
        val before = workspace.projectSourceText(PLM_SOURCE_FILE)
        fun resolve() {
            require(project() === owner) { "The ASM project changed." }
            if (projectId == null) return
            val id = currentId()
            live = owner.rooms[key()] ?: if (live.roomId == id) live else snapshotRoomForResize(live).copy(roomId = id)
        }
        fun checkCurrent(undo: Boolean) {
            resolve()
            require(owner.rooms[key()] === (if (undo || existing != null) live else null) &&
                ownership(live, projectId) == ownership(if (undo) staged else original, projectId)) {
                "Room data changed; undo newer edits before restoring layout ownership."
            }
        }
        fun setRoom(undo: Boolean) {
            resolve()
            val target = if (undo) original else staged
            if (original.states.isEmpty()) { live.states.clear(); live.states.addAll(snapshotRoomForResize(target).states) }
            applyLayouts(live, target)
            if (undo && existing == null) owner.rooms.remove(key()) else owner.rooms[key()] = live
        }
        fun seedPreview() { initial.forEach { (state, resource) -> lastGood[durableIdentity to state] = resource } }
        checkCurrent(false)
        workspace.replaceProjectSources(mapOf(PLM_SOURCE_FILE to (before to replacement)),
            beforeApply = { setRoom(false) }, afterApply = { seedPreview(); changed() },
            beforeUndo = { checkCurrent(true); setRoom(true) }, afterUndo = { changed(); afterUndo() },
            beforeRedo = { checkCurrent(false); setRoom(false) }, afterRedo = { seedPreview(); changed(); afterRedo() })
    }

    fun migrate(roomId: Int, roomName: String, levels: Map<String, ByteArray>) {
        if (isManaged(roomId)) return
        val owner = project()
        val room = owner.rooms.getValue(owner.roomKey(roomId))
        val projectId = owner.newRoomForPreviewId(roomId)?.id
        val beforeModel = snapshotRoomForResize(room)
        val staged = snapshotRoomForResize(room).also(::adoptLayouts)
        val before = source().text
        val after = automaticSource(room, roomName, levels)
        fun apply(expected: RoomEdits, target: RoomEdits) {
            val active = currentRoom(owner, room, projectId)
            require(ownership(active, projectId) == ownership(expected, projectId)) { "Undo newer room changes before adopting/restoring its layout source." }
            applyLayouts(active, target)
            changed()
        }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, before, after,
            beforeUndo = { apply(staged, beforeModel) }, beforeRedo = { apply(beforeModel, staged) })
        apply(beforeModel, staged)
    }

    /** Pure inspection: rendering declarations must not adopt ownership or touch source buffers. */
    fun preview(room: RoomEdits, roomName: String, levels: Map<String, ByteArray>): String {
        val roomId = room.roomId
        val projectId = project().newRoomForPreviewId(roomId)?.id
        val (width, height) = dimensions(room)
        return buildString {
            appendLine("; SMEDIT room layout · ${roomName.replace('\n', ' ').replace('\r', ' ')} · ${width}×$height blocks")
            appendLine("; Editable decoded level asset. Build validates and LZ5-compresses it; do not invoke at a ROM org.")
            for ((levelId, states) in room.states.groupBy { it.resources.level }) {
                val data = levels.getValue(states.first().id)
                require(states.all { levels.getValue(it.id).contentEquals(data) }) { "Sharing states have different private geometry. Make their layouts unique before adoption." }
                appendLine(AsmRoomLayoutSource.render(SourceRoomLayoutResource(AsmRoomLayoutSource.dataName(roomId, levelId, projectId), width, height, data.map { it.toInt() and 255 })))
            }
            room.states.forEach { appendLine(AsmRoomLayoutSource.renderState(roomId, it.id, it.resources.level, projectId)) }
        }
    }

    fun edit(roomId: Int, stateId: String, edits: List<TileEdit>, beforeUndo: () -> Unit = {}, beforeRedo: () -> Unit = {}): Boolean {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val owner = project()
        val projectId = owner.newRoomForPreviewId(roomId)?.id
        val view = view(roomId, stateId)
        require(view.problem == null) { view.problem ?: "Invalid layout source." }
        val current = source()
        val resource = requireNotNull(view.resource)
        val after = current.edit(room, stateId, resource.width, resource.height, edits)
        if (after == current.text) return false
        val links = room.states.map { it.id to it.resources.level }
        val dimensions = room.effectiveHeaderChange
        fun validate() {
            val active = currentRoom(owner, room, projectId)
            require(active.states.map { it.id to it.resources.level } == links && active.effectiveHeaderChange == dimensions) { "Undo newer layout sharing/dimension changes first." }
        }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, after,
            beforeUndo = { validate(); beforeUndo() }, beforeRedo = { validate(); beforeRedo() })
        return true
    }
    fun changeLink(roomId: Int, stateId: String, levelId: String, clone: Boolean) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val owner = project()
        val projectId = owner.newRoomForPreviewId(roomId)?.id
        val state = room.states.single { it.id == stateId }
        val old = state.resources.level
        val view = view(roomId, stateId)
        require(view.problem == null) { view.problem ?: "Invalid layout source." }
        val current = source()
        val after = if (clone) current.cloneLayout(room, stateId, levelId) else current.link(roomId, stateId, levelId)
        val links = room.states.map { it.id to it.resources.level }
        fun transition(undo: Boolean) {
            val active = currentRoom(owner, room, projectId)
            val expected = links.map { (id, level) -> id to if (!undo || id != stateId) level else levelId }
            require(active.states.map { it.id to it.resources.level } == expected) { "Undo newer layout sharing changes first." }
            val selected = active.states.single { it.id == stateId }
            selected.resources = selected.resources.copy(level = if (undo) old else levelId)
            changed()
        }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, after, beforeUndo = { transition(true) }, beforeRedo = { transition(false) })
        transition(false)
    }
    fun cloneState(roomId: Int, templateId: String, newId: String) {
        if (!isManaged(roomId)) return
        val room = project().rooms.getValue(project().roomKey(roomId))
        val owner = project()
        val projectId = owner.newRoomForPreviewId(roomId)?.id
        val level = room.states.single { it.id == templateId }.resources.level
        val current = source()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, current.appendState(roomId, newId, level), beforeUndo = {
            require(currentRoom(owner, room, projectId).states.none { it.id == newId }) { "Delete the new state before undoing its layout declaration." }
        })
    }
    fun line(roomId: Int, stateId: String): Int {
        val level = project().rooms.getValue(project().roomKey(roomId)).states.single { it.id == stateId }.resources.level
        return source().line(roomId, level)
    }
    fun hasLayout(roomId: Int, levelId: String) = source().hasLayout(roomId, levelId)
}

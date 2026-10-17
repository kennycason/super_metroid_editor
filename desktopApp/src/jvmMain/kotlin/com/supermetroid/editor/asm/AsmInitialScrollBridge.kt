package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.SourceInitialScrollResource

internal data class AsmInitialScrollView(val resource: SourceInitialScrollResource?, val problem: String?)

/** Initial grids are independently adopted; no PLM migration is required. */
internal class AsmInitialScrollBridge(
    private val catalog: AsmPlmSourceCatalog,
    private val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject,
    private val context: AsmRoomSourceContext = AsmRoomSourceContext(catalog, project),
    private val metadataChanged: () -> Unit,
) {
    private var revision: Pair<String, AsmInitialScrollSource>? = null
    private val lastGood = mutableMapOf<Pair<String, String>, SourceInitialScrollResource>()
    private var identityRevision = emptyMap<Int, String>()
    private fun source(): AsmInitialScrollSource {
        val identities = context.identities()
        if (identityRevision != identities) { revision = null; identityRevision = identities }
        val text = workspace.projectSourceText(PLM_SOURCE_FILE)
        return revision?.takeIf { it.first == text }?.second ?: AsmInitialScrollSource(text, catalog.symbols, context.identities()).also { revision = text to it }
    }
    fun isManaged(roomId: Int) = (project().rooms[project().roomKey(roomId)]?.asmInitialScrollSourceVersion ?: 0) != 0
    private fun dimensions(roomId: Int): Pair<Int, Int> {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val header = requireNotNull(context.parser.readRoomHeader(roomId))
        return (room.effectiveHeaderChange?.width ?: header.width) to (room.effectiveHeaderChange?.height ?: header.height)
    }
    fun view(roomId: Int, stateId: String): AsmInitialScrollView = try {
        val (width, height) = dimensions(roomId)
        val inputs = source().resources(project().rooms.getValue(project().roomKey(roomId)), width, height)
        val resource = inputs.getValue(stateId)
        lastGood[context.identity(roomId) to stateId] = resource
        AsmInitialScrollView(resource, null)
    } catch (error: Exception) { AsmInitialScrollView(lastGood[context.identity(roomId) to stateId], error.message ?: "Invalid initial scroll source.") }

    fun preview(room: com.supermetroid.editor.data.RoomEdits, name: String): String {
        val before = source().text
        return roomSourceAddition(before, planInitialScrollMigration(before, room, context.parser, name, catalog.symbols, context.identities()).text)
    }

    fun migrate(roomId: Int, roomName: String) {
        if (isManaged(roomId)) return
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Initial scroll source requires ASM mode." }
        val room = owner.rooms[owner.roomKey(roomId)] ?: com.supermetroid.editor.data.RoomEdits(roomId)
        val before = source().text
        val migration = planInitialScrollMigration(before, room, context.parser, roomName, catalog.symbols, context.identities())
        val staged = snapshotRoomForResize(room.copy(states = migration.states.toMutableList()))
        val after = migration.text
        val oldStates = room.states.toList()
        val oldCommon = room.scrollChanges.toList()
        val oldScoped = staged.states.associate { it.id to it.scrollChanges.toList() }
        if (owner.newRoomForPreviewId(roomId) != null) {
            staged.scrollChanges.clear(); staged.states.forEach { it.scrollChanges.clear() }
            staged.asmInitialScrollSourceVersion = 1
            context.replace(workspace, roomId, owner.rooms[owner.roomKey(roomId)]?.let(::snapshotRoomForResize), staged,
                mapOf(PLM_SOURCE_FILE to (before to after)), metadataChanged)
            return
        }
        fun adopt() {
            require(room.asmInitialScrollSourceVersion == 0 && room.states == oldStates && room.scrollChanges == oldCommon &&
                staged.states.all { it.scrollChanges == oldScoped[it.id] }) { "Scroll metadata changed; adopt the current grid again." }
            owner.rooms[owner.roomKey(roomId)] = room
            if (room.states.isEmpty()) room.states.addAll(staged.states)
            room.scrollChanges.clear()
            room.states.forEach { it.scrollChanges.clear() }
            room.asmInitialScrollSourceVersion = 1
            metadataChanged()
        }
        fun restore() {
            require(room.asmInitialScrollSourceVersion == 1 && room.states.map { it.id } == staged.states.map { it.id } &&
                room.scrollChanges.isEmpty() && room.states.all { it.scrollChanges.isEmpty() }) { "Undo newer scroll/state changes first." }
            room.asmInitialScrollSourceVersion = 0
            room.scrollChanges.addAll(oldCommon)
            room.states.forEach { it.scrollChanges.addAll(oldScoped[it.id].orEmpty()) }
            if (oldStates.isEmpty()) room.states.clear()
            metadataChanged()
        }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, before, after, ::restore, ::adopt)
        adopt()
    }
    fun edit(roomId: Int, stateId: String, index: Int, value: Int) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val (width, height) = dimensions(roomId)
        val current = source()
        val edited = current.edit(room, stateId, width, height, index, value)
        val external = workspace.projectSourceTextsExcept(PLM_SOURCE_FILE).flatMap { AsmInitialScrollSource.references(it).asSequence() }.toSet()
        val tidy = AsmInitialScrollSource(edited, catalog.symbols, context.identities()).tidy(room, external)
        val after = AsmInitialScrollSource(tidy, catalog.symbols, context.identities()).withComments(room, width, height)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, after)
    }
    fun cloneState(roomId: Int, templateId: String, newId: String) {
        if (!isManaged(roomId)) return
        val current = source()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current.text, current.cloneState(roomId, templateId, newId), beforeUndo = {
            require(project().rooms[project().roomKey(roomId)]?.states?.none { it.id == newId } == true) { "Delete the new state before undoing its scroll declaration." }
        })
    }
    fun hasState(roomId: Int, stateId: String): Boolean = isManaged(roomId) && source().hasState(roomId, stateId)
    fun line(roomId: Int, stateId: String): Int {
        return source().line(roomId, stateId)
    }
    internal fun remember(room: com.supermetroid.editor.data.RoomEdits, text: String) {
        val header = requireNotNull(context.parser.readRoomHeader(room.roomId))
        AsmInitialScrollSource(text, catalog.symbols, context.identities()).resources(room,
            room.effectiveHeaderChange?.width ?: header.width, room.effectiveHeaderChange?.height ?: header.height).forEach { (state, resource) ->
            lastGood[context.identity(room.roomId) to state] = resource
        }
    }
}

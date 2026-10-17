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
    private val metadataChanged: () -> Unit,
) {
    private var revision: Pair<String, AsmInitialScrollSource>? = null
    private val lastGood = mutableMapOf<Pair<Int, String>, SourceInitialScrollResource>()
    private fun source(): AsmInitialScrollSource {
        val text = workspace.projectSourceText(PLM_SOURCE_FILE)
        return revision?.takeIf { it.first == text }?.second ?: AsmInitialScrollSource(text, catalog.symbols).also { revision = text to it }
    }
    fun isManaged(roomId: Int) = (project().rooms[project().roomKey(roomId)]?.asmInitialScrollSourceVersion ?: 0) != 0
    private fun dimensions(roomId: Int): Pair<Int, Int> {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val header = requireNotNull(catalog.parser.readRoomHeader(roomId))
        return (room.effectiveHeaderChange?.width ?: header.width) to (room.effectiveHeaderChange?.height ?: header.height)
    }
    fun view(roomId: Int, stateId: String): AsmInitialScrollView = try {
        val (width, height) = dimensions(roomId)
        val inputs = source().resources(project().rooms.getValue(project().roomKey(roomId)), width, height)
        val resource = inputs.getValue(stateId)
        lastGood[roomId to stateId] = resource
        AsmInitialScrollView(resource, null)
    } catch (error: Exception) { AsmInitialScrollView(lastGood[roomId to stateId], error.message ?: "Invalid initial scroll source.") }

    fun preview(room: com.supermetroid.editor.data.RoomEdits, name: String): String {
        val before = source().text
        return roomSourceAddition(before, planInitialScrollMigration(before, room, catalog.parser, name, catalog.symbols).text)
    }

    fun migrate(roomId: Int, roomName: String) {
        if (isManaged(roomId)) return
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Initial scroll source requires ASM mode." }
        require(owner.newRoomForPreviewId(roomId) == null) { "Reciprocal new-room initial scroll source is not supported yet." }
        val room = owner.rooms[owner.roomKey(roomId)] ?: com.supermetroid.editor.data.RoomEdits(roomId)
        val before = source().text
        val migration = planInitialScrollMigration(before, room, catalog.parser, roomName, catalog.symbols)
        val staged = room.copy(states = migration.states.toMutableList())
        val after = migration.text
        val oldStates = room.states.toList()
        val oldCommon = room.scrollChanges.toList()
        val oldScoped = staged.states.associate { it.id to it.scrollChanges.toList() }
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
        val tidy = AsmInitialScrollSource(edited, catalog.symbols).tidy(room, external)
        val after = AsmInitialScrollSource(tidy, catalog.symbols).withComments(room, width, height)
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
}

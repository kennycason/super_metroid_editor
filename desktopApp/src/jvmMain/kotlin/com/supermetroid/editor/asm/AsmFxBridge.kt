package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*

internal data class AsmFxView(val resource: SourceFxResource?, val problem: String?)

/** FX has its own bank/document and ownership, independent of PLMs and initial scrolls. */
internal class AsmFxBridge(private val catalog: AsmPlmSourceCatalog, private val workspace: AsmWorkspaceState,
                           private val project: () -> SmEditProject, private val metadataChanged: () -> Unit) {
    private var revision: Pair<String, AsmFxSource>? = null
    private val lastGood = mutableMapOf<Pair<Int, String>, SourceFxResource>()
    fun sourceText() = workspace.projectSourceText(FX_SOURCE_FILE)
    private fun source(): AsmFxSource {
        val text = sourceText()
        return revision?.takeIf { it.first == text }?.second ?: AsmFxSource(text).also { revision = text to it }
    }
    fun validate(roomId: Int, resource: SourceFxResource) = validateSourceFxDoors(roomId, resource, catalog.parser,
        liveSourceDoors(resource.entries.mapNotNull { it.sourceDoor }, project(), catalog, workspace,
            nativePointers = resource.entries.map { it.doorSelect }))
    fun isManaged(roomId: Int) = (project().rooms[project().roomKey(roomId)]?.asmFxSourceVersion ?: 0) != 0
    fun view(roomId: Int, stateId: String): AsmFxView = try {
        val inputs = source().resources(project().rooms.getValue(project().roomKey(roomId)))
        requireDoorRoutedFxOwnership(project().rooms.getValue(project().roomKey(roomId)), catalog.parser)
        inputs.values.distinct().forEach { validate(roomId, it) }
        val resource = inputs.getValue(stateId)
        lastGood[roomId to stateId] = resource
        AsmFxView(resource, null)
    } catch (error: Exception) { AsmFxView(lastGood[roomId to stateId], error.message ?: "Invalid FX source.") }
    fun preview(room: RoomEdits, name: String): String {
        room.ensureStateManifest(catalog.parser)
        requireDoorRoutedFxOwnership(room, catalog.parser)
        val before = sourceText()
        return roomSourceAddition(before, planFxMigration(before, room, catalog.parser, name))
    }

    fun migrate(roomId: Int, roomName: String) {
        if (isManaged(roomId)) return
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "FX source requires ASM mode." }
        require(owner.newRoomForPreviewId(roomId) == null) { "Reciprocal new-room FX source is not supported yet." }
        val room = owner.rooms[owner.roomKey(roomId)] ?: RoomEdits(roomId)
        val staged = room.copy(states = room.states.toMutableList())
        staged.ensureStateManifest(catalog.parser)
        requireDoorRoutedFxOwnership(staged, catalog.parser)
        val before = sourceText()
        val after = planFxMigration(before, staged, catalog.parser, roomName)
        val oldStates = room.states.toList()
        val common = room.fxChange
        val overrides = staged.states.associate { it.id to (it.fxChange to it.doorFxChanges.toMap()) }
        fun adopt() {
            require(room.asmFxSourceVersion == 0 && room.states == oldStates && room.fxChange == common &&
                staged.states.all { overrides[it.id] == (it.fxChange to it.doorFxChanges.toMap()) }) { "FX metadata changed; adopt again." }
            owner.rooms[owner.roomKey(roomId)] = room
            if (room.states.isEmpty()) room.states.addAll(staged.states)
            room.fxChange = null
            room.states.forEach { it.fxChange = null; it.doorFxChanges.clear() }
            room.asmFxSourceVersion = 1
            metadataChanged()
        }
        fun restore() {
            require(room.asmFxSourceVersion == 1 && room.states.map { it.id } == staged.states.map { it.id } &&
                room.fxChange == null && room.states.all { it.fxChange == null && it.doorFxChanges.isEmpty() }) { "Undo newer FX/state changes first." }
            room.asmFxSourceVersion = 0
            room.fxChange = common
            room.states.forEach { it.fxChange = overrides.getValue(it.id).first; it.doorFxChanges.putAll(overrides.getValue(it.id).second) }
            if (oldStates.isEmpty()) room.states.clear()
            metadataChanged()
        }
        AsmFxSource(after).resources(staged.copy(asmFxSourceVersion = 1, fxChange = null,
            states = staged.states.map { it.copy(fxChange = null, doorFxChanges = linkedMapOf()) }.toMutableList()))
        workspace.replaceProjectSource(FX_SOURCE_FILE, before, after, ::restore, ::adopt)
        adopt()
    }
    fun edit(roomId: Int, stateId: String, door: Int, change: FxChange, allStates: Boolean = false,
             sourceDoor: ProjectSourceDoorReference? = null) {
        val view = view(roomId, stateId)
        require(view.problem == null) { view.problem ?: "Invalid FX draft." }
        val current = source()
        val room = project().rooms.getValue(project().roomKey(roomId))
        val targets = if (allStates) current.resources(room).entries.distinctBy { it.value.identity }.map { it.key } else listOf(stateId)
        var edited = current.text
        for (target in targets) {
            val staged = AsmFxSource(edited)
            val input = staged.resource(roomId, target)
            val entry = input.entries.singleOrNull { it.doorSelect == door && it.sourceDoor == sourceDoor }
                ?: emptyDefaultFxEntry().copy(doorSelect = door, sourceDoor = sourceDoor)
            edited = staged.replaceEntry(room, target, door, entry.withFxChange(change), detach = !allStates)
        }
        val external = workspace.projectSourceTextsExcept(FX_SOURCE_FILE).flatMap { AsmFxSource.references(it).asSequence() }.toSet()
        val result = AsmFxSource(AsmFxSource(edited).tidy(room, external)).withComments(room)
        AsmFxSource(result).resources(room).values.forEach { validate(roomId, it) }
        workspace.replaceProjectSource(FX_SOURCE_FILE, current.text, result)
    }
    fun hasState(roomId: Int, stateId: String) = isManaged(roomId) && source().hasState(roomId, stateId)
    fun remove(roomId: Int, stateId: String, door: Int, sourceDoor: ProjectSourceDoorReference?) {
        val current = source()
        val room = project().rooms.getValue(project().roomKey(roomId))
        // Removing a dangling selector is a repair operation; don't require the old target to resolve.
        val edited = current.removeEntry(room, stateId, door, sourceDoor)
        AsmFxSource(edited).resources(room).values.forEach { validate(roomId, it) }
        workspace.replaceProjectSource(FX_SOURCE_FILE, current.text, edited)
    }
    fun cloneState(roomId: Int, template: String, newId: String) {
        if (!isManaged(roomId)) return
        val current = source()
        workspace.replaceProjectSource(FX_SOURCE_FILE, current.text, current.cloneState(roomId, template, newId), beforeUndo = {
            require(project().rooms[project().roomKey(roomId)]?.states?.none { it.id == newId } == true) { "Delete the new state before undoing its FX declaration." }
        })
    }
    fun line(roomId: Int, stateId: String, door: Int? = null, sourceDoor: ProjectSourceDoorReference? = null) = source().line(roomId, stateId, door, sourceDoor)
    fun prepare(text: String): () -> String {
        val rooms = project().rooms.values.filter { it.asmFxSourceVersion != 0 }.map { it.copy(states = it.states.map { state ->
            state.copy(doorFxChanges = state.doorFxChanges.toMutableMap()) }.toMutableList()) }
        val readers = workspace.projectSourceReadersExcept(FX_SOURCE_FILE)
        return {
            val external = readers.flatMap { AsmFxSource.references(it()) }.toSet()
            rooms.fold(text) { current, room -> runCatching {
                val source = AsmFxSource(current)
                source.resources(room) // Invalid drafts remain saveable, unchanged.
                AsmFxSource(source.tidy(room, external)).withComments(room)
            }.getOrDefault(current) }
        }
    }
}

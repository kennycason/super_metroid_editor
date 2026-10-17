package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*

internal data class AsmDoorSourceView(val resource: SourceRoomDoors?, val problem: String?)
internal data class AsmSharedDoorView(val entries: Map<Int, SourceRoomDoor>, val problem: String?)

internal class AsmDoorSourceBridge(private val catalog: AsmPlmSourceCatalog, private val workspace: AsmWorkspaceState,
                                   private val project: () -> SmEditProject, private val context: AsmRoomSourceContext = AsmRoomSourceContext(catalog, project), private val changed: () -> Unit) {
    private val lastGood = mutableMapOf<String, SourceRoomDoors>()
    private var lastShared = emptyMap<Int, SourceRoomDoor>()
    private data class SharedRevision(val doors: String, val arrivals: String, val sizes: Map<Int, Pair<Int?, Int?>>)
    private var sharedCache: Pair<SharedRevision, AsmSharedDoorView>? = null
    private var revision: Pair<String, String>? = null
    private data class Identity(val version: Int, val noGuiChanges: Boolean, val doorOut: Int?,
                                val dimensions: Map<Int, Pair<Int?, Int?>>)
    private val views = mutableMapOf<Int, Pair<Identity, AsmDoorSourceView>>()
    private fun dimensions(roomId: Int): Pair<Int, Int>? = context.parser.readRoomHeader(roomId)?.let { header ->
        val override = project().rooms[project().roomKey(roomId)]?.effectiveHeaderChange
        (override?.width ?: header.width) to (override?.height ?: header.height)
    }
    fun sourceText() = workspace.projectSourceText(DOOR_SOURCE_FILE)
    private val consumers by lazy { nativeDoorConsumers(catalog.parser) }
    fun sharedConsumers(pointer: Int): List<Pair<Int, Int>> = consumers[pointer].orEmpty()
        .takeIf { it.map { pair -> pair.first }.distinct().size > 1 }.orEmpty().filter { (id, _) ->
            val room = project().rooms[project().roomKey(id)]
            if (room?.asmDoorSourceVersion != 1) true else runCatching {
                document().resource(room, context.parser, ::dimensions).entries.any { it.doorDefPtr == pointer }
            }.getOrDefault(true) // An invalid draft cannot silently disarm the shared-edit warning.
        }
    fun sharedView(): AsmSharedDoorView {
        val revision = try {
            SharedRevision(sourceText(), arrivalText(), project().rooms.values.filter {
                it.roomHeaderChange?.width != null || it.roomHeaderChange?.height != null
            }.associate { it.roomId to (it.roomHeaderChange?.width to it.roomHeaderChange?.height) })
        } catch (error: Exception) {
            // Read-only room loading must also tolerate a missing/unavailable source bank.
            // Mutations still require a valid view; never silently treat unavailable source as empty.
            return AsmSharedDoorView(lastShared, error.message ?: "Shared DoorDef source is unavailable.")
        }
        sharedCache?.takeIf { it.first == revision }?.let { return it.second }
        val view = try {
            val records = document().sharedResources(context.parser)
            records.keys.forEach { pointer ->
                val (roomId, _) = consumers.getValue(pointer).first()
                val native = requireNotNull(context.parser.readRoomHeader(roomId))
                val doors = context.parser.parseDoorList(native.doorOut).mapIndexed { index, door ->
                    records[door.doorDefPtr]?.let { it.copy(change = it.change.copy(doorIndex = index), identity = index) }
                        ?: SourceRoomDoor(door.doorDefPtr, DoorChange(index, door.destRoomPtr, door.bitflag, door.doorCapCode,
                            door.screenX, door.screenY, door.distFromDoor, door.entryCode))
                }
                validateSourceRoomDoors(RoomEdits(roomId, asmDoorSourceVersion = 1), SourceRoomDoors(native.doorOut, doors), context.parser, ::dimensions)
            }
            lastShared = records
            AsmSharedDoorView(records, null)
        } catch (error: Exception) { AsmSharedDoorView(lastShared, error.message ?: "Invalid shared DoorDef source.") }
        sharedCache = revision to view
        return view
    }
    fun sharedEntries(requireValid: Boolean = false): Map<Int, SourceRoomDoor> = sharedView().let {
        require(!requireValid || it.problem == null) { it.problem ?: "Invalid shared DoorDef source." }
        it.entries
    }
    private fun arrivalText() = workspace.projectSourceText(PLM_SOURCE_FILE)
    private fun document(text: String = sourceText(), arrivals: String = arrivalText()) = AsmDoorSource(text, arrivals, catalog.symbols, context.identities(), project().newRooms)
    fun isManaged(roomId: Int) = (project().rooms[project().roomKey(roomId)]?.asmDoorSourceVersion ?: 0) != 0
    fun view(roomId: Int): AsmDoorSourceView {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val text = sourceText()
        val texts = (text + context.identities().toString()) to arrivalText()
        if (revision != texts) { views.clear(); revision = texts }
        val identity = Identity(room.asmDoorSourceVersion, room.doorChanges.isEmpty(), room.roomHeaderChange?.doorOut,
            project().rooms.values.filter { it.roomHeaderChange?.width != null || it.roomHeaderChange?.height != null }
                .associate { it.roomId to (it.roomHeaderChange?.width to it.roomHeaderChange?.height) })
        views[roomId]?.takeIf { it.first == identity }?.let { return it.second }
        val view = try {
            val source = document(text).resource(room, context.parser, ::dimensions)
            if (source.entries.any { it.shared }) require(sharedView().problem == null) { sharedView().problem ?: "Invalid shared DoorDef source." }
            lastGood[context.identity(roomId)] = source
            AsmDoorSourceView(source, null)
        } catch (error: Exception) { AsmDoorSourceView(lastGood[context.identity(roomId)], error.message ?: "Invalid door source.") }
        views[roomId] = identity to view
        return view
    }
    fun refreshAll() {
        for (room in project().rooms.values) room.asmDoorProjection = if (isManaged(room.roomId))
            view(room.roomId).resource?.entries?.map { it.change } else null
    }
    private fun effective(room: RoomEdits): SourceRoomDoors {
        val header = requireNotNull(context.parser.readRoomHeader(room.roomId))
        val changes = room.doorChanges.associateBy { it.doorIndex }
        val native = context.parser.parseDoorList(header.doorOut)
        require(changes.keys.all { it in native.indices }) { "A GUI door override has no native connection." }
        val shared = sharedEntries(requireValid = native.any { sharedConsumers(it.doorDefPtr).isNotEmpty() })
        val projectId = context.identities()[room.roomId]
        return SourceRoomDoors(if (projectId == null) header.doorOut else 0, native.mapIndexed { index, door ->
            val canonical = shared[door.doorDefPtr]
            require(canonical == null || changes[index] == null) { "A shared DoorDef has both a canonical source and a GUI override. Resolve the override before editing." }
            canonical?.copy(change = canonical.change.copy(doorIndex = index), identity = index) ?: SourceRoomDoor(door.doorDefPtr,
                changes[index] ?: DoorChange(index, door.destRoomPtr, door.bitflag, door.doorCapCode, door.screenX, door.screenY, door.distFromDoor, door.entryCode),
                shared = sharedConsumers(door.doorDefPtr).isNotEmpty())
        }.map { door -> if (projectId == null) door.copy(destinationProjectId = context.identities()[door.change.destRoomPtr]) else {
            val arrival = project().newRooms.single { it.id == projectId }.doors.getOrNull(door.identity)?.arrivalAsmLabel?.let { SourceDoorArrival(it) }
            door.copy(doorDefPtr = 0, shared = false, destinationProjectId = context.identities()[door.change.destRoomPtr],
                change = if (arrival == null) door.change else door.change.copy(entryCode = 0), arrival = arrival)
        } },
            structural = projectId != null, projectRoomId = projectId)
    }
    private fun sharedDeclarations(resource: SourceRoomDoors, existing: String = ""): String = resource.entries.filter { it.shared }
        .distinctBy { it.doorDefPtr }.filter { !Regex("(?m)^macro ${AsmDoorSource.sharedName(it.doorDefPtr)}\\(\\)").containsMatchIn(existing) }
        .joinToString("\n") { door ->
            val otherOverride = sharedConsumers(door.doorDefPtr).any { (id, index) ->
                project().rooms[project().roomKey(id)]?.doorChanges?.any { it.doorIndex == index } == true
            }
            require(!otherOverride) { "A shared native record has existing GUI overrides. Clear/review those overrides before adopting its shared source." }
            AsmDoorSource.renderShared(door, sharedConsumers(door.doorDefPtr).map { it.first }.distinct().map { id ->
                "${context.parser.readRoomHeader(id)?.name ?: "Room"} ($${id.toString(16).uppercase()})"
            })
        }
    fun preview(room: RoomEdits, name: String): String {
        val source = effective(room)
        return sharedDeclarations(source) + "\n" + AsmDoorSource.render(room.roomId, name, source)
    }

    private fun checkRetainedDeclarations(roomId: Int, text: String) {
        require(!Regex("(?im)^[ \\t]*macro[ \\t]+${Regex.escape(AsmDoorSource.prefix(roomId, context.identities()[roomId]))}").containsMatchIn(text)) {
            "Retained door declarations need review before adoption."
        }
    }

    /** Plan a real bank without attaching a room, retiring GUI deltas, or touching source history. */
    fun automaticSource(room: RoomEdits, name: String, before: String = sourceText(), arrivals: String = arrivalText()): String {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) {
            "Door source requires ASM mode."
        }
        require(!isManaged(room.roomId)) { "Door ownership changed; reopen its source." }
        val resource = effective(room)
        validateSourceDoorOwnership(room.roomId, resource, context.parser)
        val staged = snapshotRoomForResize(room).also { it.doorChanges.clear(); it.asmDoorSourceVersion = 1 }
        checkRetainedDeclarations(room.roomId, before)
        val newline = if (before.contains("\r\n")) "\r\n" else "\n"
        val separator = if (before.isEmpty() || before.endsWith(newline + newline)) "" else if (before.endsWith(newline)) newline else newline + newline
        val after = before + separator + (sharedDeclarations(resource, before) + "\n" + AsmDoorSource.render(room.roomId, name, resource)).replace("\n", newline)
        document(after, arrivals).resource(staged, context.parser, ::dimensions)
        return after
    }

    /** First text edit and ownership are one transaction, including temporarily invalid typing. */
    fun editUnmanaged(roomId: Int, name: String, expected: String, replacement: String,
                      afterUndo: () -> Unit, afterRedo: () -> Unit,
                      additionalSources: Map<String, Pair<String, String>> = emptyMap()) {
        val owner = project()
        val key = owner.roomKey(roomId)
        val existing = owner.rooms[key]
        val live = existing ?: RoomEdits(roomId)
        val original = snapshotRoomForResize(live)
        val staged = snapshotRoomForResize(live).also { it.ensureStateManifest(context.parser) }
        require(automaticSource(staged, name) == expected) { "Door source changed; reopen its current link before editing." }
        require(expected != replacement) { "No door source change to apply." }
        val resource = effective(staged)
        staged.doorChanges.clear(); staged.asmDoorSourceVersion = 1
        staged.asmDoorProjection = resource.entries.map { it.change }
        val before = sourceText()
        if (owner.newRoomForPreviewId(roomId) != null) {
            context.replace(workspace, roomId, existing?.let(::snapshotRoomForResize), staged,
                additionalSources + (DOOR_SOURCE_FILE to (before to replacement)), { lastGood[context.identity(roomId)] = resource; refreshAll(); changed() },
                afterUndo, afterRedo)
            return
        }
        fun metadata(room: RoomEdits) = room.copy(asmDoorProjection = null)
        fun checkCurrent(undo: Boolean) {
            require(project() === owner && owner.rooms[key] === (if (undo || existing != null) live else null) &&
                metadata(live) == metadata(if (undo) staged else original)) {
                "Room data changed; undo newer edits before restoring door ownership."
            }
        }
        fun setRoom(undo: Boolean) {
            val target = if (undo) original else staged
            // Keep existing state/room identities: older reciprocal histories may retain them.
            if (original.states.isEmpty()) {
                live.states.clear(); live.states.addAll(snapshotRoomForResize(target).states)
            }
            live.doorChanges.clear(); live.doorChanges.addAll(target.doorChanges)
            live.asmDoorSourceVersion = target.asmDoorSourceVersion; live.asmDoorProjection = target.asmDoorProjection
            if (undo && existing == null) owner.rooms.remove(key) else owner.rooms[key] = live
        }
        fun notify() { refreshAll(); changed() }
        checkCurrent(false)
        require(DOOR_SOURCE_FILE !in additionalSources) { "Duplicate door-source participant." }
        workspace.replaceProjectSources(additionalSources + (DOOR_SOURCE_FILE to (before to replacement)),
            beforeApply = { setRoom(false) }, afterApply = { lastGood[context.identity(roomId)] = resource; notify() },
            beforeUndo = { checkCurrent(true); setRoom(true) }, afterUndo = { notify(); afterUndo() },
            beforeRedo = { checkCurrent(false); setRoom(false) }, afterRedo = { notify(); afterRedo() })
    }
    fun validateEdit(roomId: Int, index: Int, entry: RomParser.DoorEntry) {
        val room = project().rooms[project().roomKey(roomId)] ?: RoomEdits(roomId)
        val current = if (isManaged(roomId)) view(roomId).resource else effective(room)
        require(current?.entries?.getOrNull(index)?.shared != true || entry.destRoomPtr !in context.identities()) {
            "Retargeting a shared native record to a project room requires a private DoorDef copy. Choose Make this connection independent first; no source was changed."
        }
        if (isManaged(roomId)) {
            require(view(roomId).problem == null) { view(roomId).problem ?: "Invalid door source." }
            document().edit(room, context.parser, index, entry, ::dimensions)
        } else {
            val staged = snapshotRoomForResize(room).also { it.doorChanges.clear(); it.asmDoorSourceVersion = 1 }
            document(preview(room, "Preview")).edit(staged, context.parser, index, entry, ::dimensions)
        }
    }
    fun migrate(roomId: Int, name: String) {
        if (isManaged(roomId)) return
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) {
            "Door source requires ASM mode."
        }
        val room = owner.rooms.getValue(owner.roomKey(roomId))
        val original = snapshotRoomForResize(room)
        val staged = snapshotRoomForResize(room)
        val resource = effective(room)
        validateSourceDoorOwnership(roomId, resource, context.parser)
        staged.doorChanges.clear(); staged.asmDoorSourceVersion = 1; staged.asmDoorProjection = resource.entries.map { it.change }
        if (resource.projectRoomId == null) validateSourceRoomDoors(staged, resource, context.parser, ::dimensions)
        val before = sourceText()
        checkRetainedDeclarations(roomId, before)
        val after = automaticSource(room, name)
        document(after).resource(staged, context.parser, ::dimensions)
        if (owner.newRoomForPreviewId(roomId) != null) {
            context.replace(workspace, roomId, original, staged, mapOf(DOOR_SOURCE_FILE to (before to after)), { refreshAll(); changed() })
            return
        }
        fun transition(expected: RoomEdits, target: RoomEdits) {
            require(project() === owner && owner.rooms[owner.roomKey(roomId)] === room && room == expected) { "Undo newer room changes before restoring door ownership." }
            room.doorChanges.clear(); room.doorChanges.addAll(target.doorChanges)
            room.asmDoorSourceVersion = target.asmDoorSourceVersion; room.asmDoorProjection = target.asmDoorProjection
            changed()
        }
        workspace.replaceProjectSource(DOOR_SOURCE_FILE, before, after,
            beforeUndo = { transition(staged, original) }, beforeRedo = { transition(original, staged) })
        transition(original, staged)
    }
    fun edit(roomId: Int, index: Int, entry: RomParser.DoorEntry) {
        require(view(roomId).problem == null) { view(roomId).problem ?: "Door source is invalid." }
        val room = project().rooms.getValue(project().roomKey(roomId))
        val before = sourceText()
        workspace.replaceProjectSource(DOOR_SOURCE_FILE, before, document(before).edit(room, context.parser, index, entry, ::dimensions))
        refreshAll()
    }
    fun line(roomId: Int, index: Int = 0) = document().line(roomId, index)

    /** An explicit cross-bank transaction; no source/model mutation while preparing its scaffold. */
    fun createManagedArrival(roomId: Int, index: Int, roomName: String,
                             afterUndo: () -> Unit = {}, afterRedo: () -> Unit = {}) {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Managed arrival ASM requires ASM mode." }
        val managed = isManaged(roomId)
        val staged = snapshotRoomForResize(owner.rooms[owner.roomKey(roomId)] ?: RoomEdits(roomId))
            .also { it.ensureStateManifest(context.parser) }
        val planned83 = if (managed) sourceText() else automaticSource(staged, roomName)
        val source = if (managed) {
            val view = view(roomId)
            require(view.problem == null) { view.problem ?: "Repair the door source first." }
            requireNotNull(view.resource)
        } else effective(staged)
        val door = requireNotNull(source.entries.getOrNull(index)) { "This connection changed; reopen it." }
        require(door.arrival?.label?.startsWith("SmeditArrival_") != true) { "This door already uses a managed arrival routine." }
        val originalLabel = door.arrival?.label ?: if (door.change.entryCode == 0) null else
            requireNotNull(catalog.symbols.entries.filter { it.key.startsWith("DoorASM_") && it.value == door.change.entryCode }.minByOrNull { it.key }?.key) {
                "The existing numeric arrival has no verified native label. Preserve/review it in ASM before creating a managed routine."
            }
        val before83 = sourceText()
        val before8F = arrivalText()
        val name = AsmDoorArrivalsSource.name(roomId, door.identity, context.identities()[roomId])
        require(!Regex("(?m)^[ \\t]*macro[ \\t]+${name}\\b").containsMatchIn(before8F)) { "Retained arrival declaration $name needs review; no source was changed." }
        if (originalLabel != null) require(Regex("(?m)^${Regex.escape(originalLabel)}:").containsMatchIn(before8F)) {
            "The original arrival label is missing from bank_8F.asm; no behavior-preserving scaffold can be created."
        }
        val newline = if (before8F.contains("\r\n")) "\r\n" else "\n"
        val after8F = before8F.trimEnd() + newline + newline + AsmDoorArrivalsSource.render(roomId, door.identity, originalLabel, context.identities()[roomId]).replace("\n", newline)
        val after83 = document(planned83, after8F).withArrival(roomId, index, "${name}_Entry")
        AsmDoorArrivalsSource(after8F).routine("${name}_Entry")
        staged.doorChanges.clear(); staged.asmDoorSourceVersion = 1
        document(after83, after8F).resource(staged, context.parser, ::dimensions)
        if (managed) workspace.replaceProjectSources(mapOf(PLM_SOURCE_FILE to (before8F to after8F), DOOR_SOURCE_FILE to (before83 to after83)),
            afterUndo = afterUndo, afterRedo = afterRedo)
        else editUnmanaged(roomId, roomName, planned83, after83, afterUndo, afterRedo,
            additionalSources = mapOf(PLM_SOURCE_FILE to (before8F to after8F)))
        refreshAll()
    }

    fun arrivalLine(roomId: Int, index: Int): Int {
        val arrival = if (isManaged(roomId)) requireNotNull(view(roomId).resource).entries[index].arrival else {
            val native = requireNotNull(context.parser.readRoomHeader(roomId))
            val door = requireNotNull(context.parser.parseDoorEntry(native.doorOut, index))
            project().newRoomForPreviewId(roomId)?.doors?.getOrNull(index)?.arrivalAsmLabel?.let { SourceDoorArrival(it) }
                ?: sharedEntries()[door.doorDefPtr]?.arrival
        }
        val label = requireNotNull(arrival?.label) { "This connection has no symbolic arrival target." }
        return if (label.startsWith("SmeditArrival_")) AsmDoorArrivalsSource(arrivalText()).line(label) else {
            val match = requireNotNull(Regex("(?m)^${Regex.escape(label)}:").find(arrivalText())) { "Missing arrival label $label." }
            arrivalText().take(match.range.first).count { it == '\n' }
        }
    }
}

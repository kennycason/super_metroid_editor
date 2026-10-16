package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.flattened
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.ProjectPaths
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.rom.SourceSaveResource
import com.supermetroid.editor.rom.validateSourceSaveOwnership
import com.supermetroid.editor.rom.words
import com.supermetroid.editor.rom.ensureStateManifest
import com.supermetroid.editor.rom.baseSourceStateIndex
import com.supermetroid.editor.rom.validateSourceSaveLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class AsmPlmSourceView(
    val document: AsmPlmDocument?,
    val problem: String?,
)

/** A pattern stroke is planned in memory, then published as one source/model edit. */
internal data class AsmPlmStamp(
    val entry: RomParser.PlmEntry,
    val allStates: Boolean,
    val companions: List<RomParser.PlmEntry> = emptyList(),
    val replaceIds: Set<Int> = setOf(entry.id),
    val save: SourceSaveResource? = null,
)

/** GUI lens over AsmWorkspaceState's document. Project JSON persists identity only, not object values. */
internal class AsmPlmSourceBridge(
    val catalog: AsmPlmSourceCatalog,
    private val workspace: AsmWorkspaceState,
    registerSaveParticipant: Boolean = true,
    private val project: () -> SmEditProject,
) : AsmSourceSaveParticipant {
    var onMetadataChanged: () -> Unit = {}
    val initialScrolls = AsmInitialScrollBridge(catalog, workspace, project) { onMetadataChanged() }
    val fx = AsmFxBridge(catalog, workspace, project) { onMetadataChanged() }
    val enemies = AsmEnemyBridge(catalog, workspace, project) { onMetadataChanged() }
    val layouts = AsmRoomLayoutBridge(catalog, workspace, project) { onMetadataChanged() }
    val properties = AsmRoomPropertiesBridge(catalog, workspace, project) { onMetadataChanged() }
    val doors = AsmDoorSourceBridge(catalog, workspace, project) { onMetadataChanged() }
    val doorStructure = AsmDoorStructure(catalog, workspace, project, doors, layouts) { onMetadataChanged() }
    val areaMoves = AsmRoomAreaMove(catalog, workspace, project, properties, ::requireObjectAdoptionAvailable) { onMetadataChanged() }
    var describePlm: (RomParser.PlmEntry) -> String = { AsmRoomObjectSource.defaultDescription(it) }
    private val lastValid = mutableMapOf<Int, AsmPlmDocument>()
    private var sourceRevision: Triple<String, String, AsmPlmSourceCatalog.SourceRevision>? = null
    private var managedRevision: Pair<String, AsmRoomObjectSource>? = null
    private val lastManaged = mutableMapOf<Pair<Int, String>, AsmPlmDocument>()
    private var projectedText: String? = null
    private var projectedMacros: String? = null
    private val projections = mutableMapOf<Int, AsmRoomProjection>()

    private fun projectionRepository(): AsmRoomProjectionRepository? = workspace.projectWorkspace?.root?.parentFile?.let {
        AsmRoomProjectionRepository(File(ProjectPaths.asmCacheIn(it), "projections"))
    }

    /** Capture on the UI thread; load/repair disposable projections on a worker, then publish only if still current. */
    suspend fun preloadProjections() {
        val owner = project()
        val text = sourceText()
        val macros = workspace.projectSourceText("macros.asm")
        val rooms = owner.rooms.values.filter { it.asmPlmSourceVersion != 0 }.map(::snapshotRoomObjectMetadata)
        val repository = projectionRepository() ?: return
        val loaded = withContext(Dispatchers.Default) { runCatching { repository.load(text, macros, rooms, catalog) }.getOrDefault(emptyMap()) }
        if (project() !== owner || sourceText() != text || workspace.projectSourceText("macros.asm") != macros) return
        projectedText = text
        projectedMacros = macros
        projections.clear()
        projections.putAll(loaded.filter { (id, projection) ->
            owner.rooms[owner.roomKey(id)]?.let { runCatching { roomObjectIdentity(it) == projection.identity }.getOrDefault(false) } == true
        })
    }

    override fun afterSaved(fileId: String, text: String): (() -> Unit)? {
        if (fileId != PLM_SOURCE_FILE && fileId != "macros.asm") return null
        val repository = projectionRepository() ?: return null
        val source = if (fileId == PLM_SOURCE_FILE) text else workspace.projectSourceText(PLM_SOURCE_FILE)
        val macros = if (fileId == "macros.asm") text else workspace.projectSourceText("macros.asm")
        val rooms = project().rooms.values.filter { it.asmPlmSourceVersion != 0 }.map(::snapshotRoomObjectMetadata)
        // Only publish durable saved text; unsaved buffers must never masquerade as a startup snapshot.
        if (workspace.hasUnsavedSourceChanges(PLM_SOURCE_FILE) || workspace.hasUnsavedSourceChanges("macros.asm")) return null
        return { repository.load(source, macros, rooms, catalog); Unit }
    }

    init { if (registerSaveParticipant) workspace.sourceSaveParticipant = this }

    val selectors = AsmRoomSelectorsBridge(catalog, workspace, project) { onMetadataChanged() }
    val stateLifecycle = AsmRoomStateLifecycle(catalog, workspace, project) { onMetadataChanged() }
    override val sourceFileIds: Set<String> get() = buildSet {
        if (project().rooms.values.any { it.asmDoorSourceVersion != 0 || it.asmStateSelectorsSourceVersion != 0 || it.asmRoomPropertiesSourceVersion != 0 || it.asmPlmSourceVersion != 0 || it.asmInitialScrollSourceVersion != 0 || it.asmLayoutSourceVersion != 0 }) add(PLM_SOURCE_FILE)
        if (project().rooms.values.any { it.asmDoorSourceVersion != 0 || it.asmFxSourceVersion != 0 }) add(FX_SOURCE_FILE)
        if (project().rooms.values.any { it.asmEnemySourceVersion != 0 }) add(ENEMY_SOURCE_FILE)
    }

    override fun prepare(fileId: String, text: String): () -> String {
        if (fileId == FX_SOURCE_FILE && project().rooms.values.any { it.asmFxSourceVersion != 0 }) return fx.prepare(text)
        if (fileId == ENEMY_SOURCE_FILE && fileId in sourceFileIds) return enemies.prepare(text)
        if (fileId != PLM_SOURCE_FILE || fileId !in sourceFileIds) return { text }
        val rooms = project().rooms.values.filter { it.asmPlmSourceVersion != 0 }.map(::snapshotRoomObjectMetadata)
        val initialRooms = project().rooms.values.filter { it.asmInitialScrollSourceVersion != 0 }.map { room ->
            room.copy(scrollChanges = room.scrollChanges.toMutableList(), states = room.states.map { it.copy(scrollChanges = it.scrollChanges.toMutableList()) }.toMutableList())
        }
        val macrosText = workspace.projectSourceText("macros.asm")
        val readers = workspace.projectSourceReadersExcept(PLM_SOURCE_FILE)
        val describe = describePlm
        return transform@{
            // Save must still persist an invalid draft; Build remains the full validation boundary.
            val otherTexts = readers.map { it() }
            val initialReferences = otherTexts.flatMap { AsmInitialScrollSource.references(it) }.toSet()
            var result = text
            for (room in initialRooms) result = runCatching {
                val header = requireNotNull(catalog.parser.readRoomHeader(room.roomId))
                val width = room.effectiveHeaderChange?.width ?: header.width
                val height = room.effectiveHeaderChange?.height ?: header.height
                val source = AsmInitialScrollSource(result, catalog.symbols)
                source.resources(room, width, height)
                AsmInitialScrollSource(source.tidy(room, initialReferences), catalog.symbols).withComments(room, width, height)
            }.getOrDefault(result)
            if (runCatching { catalog.requireStandardMacro(macrosText) }.isFailure) return@transform result
            val references = otherTexts.flatMap { AsmRoomObjectSource.referencedHelpers(it) }.toSet()
            for (room in rooms) {
                result = runCatching {
                    val tidy = AsmRoomObjectSource(result, catalog.symbols).tidy(room, references)
                    val annotated = AsmRoomObjectSource(tidy, catalog.symbols).withComments(room.roomId, describe)
                    val header = requireNotNull(catalog.parser.readRoomHeader(room.roomId))
                    AsmRoomObjectSource(annotated, catalog.symbols).withScrollComments(room,
                        room.effectiveHeaderChange?.width ?: header.width, room.effectiveHeaderChange?.height ?: header.height)
                }.getOrDefault(result)
            }
            result
        }
    }

    fun isManaged(roomId: Int): Boolean = (project().rooms[project().roomKey(roomId)]?.asmPlmSourceVersion ?: 0) != 0

    fun sourceText(): String = workspace.projectSourceText(PLM_SOURCE_FILE)
    fun sourceUndoHead(fileId: String = PLM_SOURCE_FILE): AsmSourceTextChange? = workspace.sourceUndoHead(fileId)
    fun undoSource(expected: String, fileId: String = PLM_SOURCE_FILE, change: AsmSourceTextChange? = null) {
        require(workspace.projectSourceText(fileId) == expected) { "Source changed; undo the newer edit in ASM first." }
        require(change == null || workspace.sourceUndoHead(fileId) === change) { "Source history changed; undo the newer edit in ASM first." }
        check(workspace.undoSourceEdit(fileId, fromRoomEditor = true)) { workspace.error ?: "Could not undo this source edit." }
    }
    fun redoSource(expected: String, fileId: String = PLM_SOURCE_FILE, change: AsmSourceTextChange? = null) {
        require(workspace.projectSourceText(fileId) == expected) { "Source changed; redo this edit in ASM instead." }
        require(change == null || workspace.sourceRedoHead(fileId) === change) { "Source history changed; redo this edit in ASM instead." }
        check(workspace.redoSourceEdit(fileId, fromRoomEditor = true)) { workspace.error ?: "Could not redo this source edit." }
    }
    fun hasStateSource(roomId: Int, stateId: String): Boolean = isManaged(roomId) && managedSource().contains(AsmRoomObjectSource.stateMacro(roomId, stateId))

    private fun managedSource(): AsmRoomObjectSource {
        catalog.requireStandardMacro(workspace.projectSourceText("macros.asm"))
        val text = sourceText()
        return managedRevision?.takeIf { it.first == text }?.second
            ?: AsmRoomObjectSource(text, catalog.symbols).also { managedRevision = text to it }
    }

    private fun dimensions(roomId: Int): Pair<Int, Int> {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val header = requireNotNull(catalog.parser.readRoomHeader(roomId))
        return (room.effectiveHeaderChange?.width ?: header.width) to (room.effectiveHeaderChange?.height ?: header.height)
    }

    fun scrollCommands(roomId: Int, stateId: String, entry: RomParser.PlmEntry): List<ScrollCommand> {
        require(entry.id == 0xB703) { "Only a scroll trigger has a command list." }
        val view = managedView(roomId, stateId)
        require(view.problem == null) { view.problem ?: "Invalid room object source." }
        val record = requireNotNull(view.document).records.singleOrNull { it.entry == entry }
            ?: error("Select one unambiguous current scroll trigger.")
        val (width, height) = dimensions(roomId)
        return record.scrollResource?.let { managedSource().scrollResource(roomId, it, width * height).commands }
            ?: AsmScrollCommandSource.original(sourceText(), catalog.symbols, entry.param, catalog.parser, width * height, catalog.originalText)
    }

    fun editScroll(roomId: Int, stateId: String, x: Int, y: Int, commands: List<ScrollCommand>, allStates: Boolean = false) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val (width, height) = dimensions(roomId)
        val source = managedSource()
        val result = source.editScroll(room, stateId, x, y, commands, width, height, externalObjectReferences(), allStates)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, source.text, AsmRoomObjectSource(result, catalog.symbols).withComments(roomId, describePlm))
    }

    fun saveResources(roomId: Int): List<SourceSaveResource> = managedSource().saveResources(roomId)

    private fun saveArea(room: com.supermetroid.editor.data.RoomEdits): Int =
        if (room.asmRoomPropertiesSourceVersion != 0) AsmRoomPropertiesSource(sourceText()).area(room.roomId)
        else room.effectiveHeaderChange?.area ?: requireNotNull(catalog.parser.readRoomHeader(room.roomId)).area

    fun saveSpawn(roomId: Int, area: Int, slot: Int): SourceSaveResource? {
        val save = saveResources(roomId).singleOrNull { it.area == area && it.slot == slot } ?: return null
        validateSourceSaveOwnership(save, catalog.parser)
        require(save.cleared || save.area == saveArea(project().rooms.getValue(project().roomKey(roomId)))) {
            "Live save source area changed; use the compound area move."
        }
        return save
    }

    private fun replaceSaveSource(roomId: Int, source: AsmRoomObjectSource, result: String, slots: Set<Pair<Int, Int>>) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val retired = room.saveStationSpawns.filter { it.area to it.saveIndex in slots }
        fun retire() {
            require(room.saveStationSpawns.filter { it.area to it.saveIndex in slots } == retired) { "Save overrides changed; reopen the station." }
            room.saveStationSpawns.removeAll(retired.toSet())
            if (retired.isNotEmpty()) onMetadataChanged()
        }
        fun restore() {
            require(room.saveStationSpawns.none { it.area to it.saveIndex in slots }) { "Undo newer save changes first." }
            room.saveStationSpawns.addAll(retired)
            if (retired.isNotEmpty()) onMetadataChanged()
        }
        // Validate the prospective source and retired metadata before publishing either.
        val candidate = room.copy(saveStationSpawns = room.saveStationSpawns.filterNot { it in retired }.toMutableList())
        val checked = AsmRoomObjectSource(result, catalog.symbols)
        val checkedResources = checked.resources(candidate)
        validateSourceSaveLinks(roomId, saveArea(candidate),
            checkedResources.values.flatMap { it.entries }, checked.saveResources(roomId), candidate.saveStationSpawns, catalog.parser)
        checked.saveResources(roomId).forEach { validateSourceSaveOwnership(it, catalog.parser) }
        project().rooms.values.filter { it.asmPlmSourceVersion != 0 }.flatMap { checked.saveResources(it.roomId) }
            .groupBy { it.area to it.slot }.forEach { (_, owners) ->
                require(owners.distinct().size == 1) { "Another source room already owns this save slot." }
            }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, source.text, checked.withComments(roomId, describePlm), ::restore, ::retire)
        retire()
    }

    fun updateSaveSpawn(resource: SourceSaveResource) {
        val source = managedSource()
        source.resources(project().rooms.getValue(project().roomKey(resource.ownerRoomId)))
        validateSourceSaveOwnership(resource, catalog.parser)
        replaceSaveSource(resource.ownerRoomId, source, source.withSave(resource), setOf(resource.area to resource.slot))
    }

    fun editSaveStation(roomId: Int, stateId: String, remove: (RomParser.PlmEntry) -> Boolean,
                        add: RomParser.PlmEntry?, spawn: SourceSaveResource, allStates: Boolean = false) {
        val source = managedSource()
        val room = project().rooms.getValue(project().roomKey(roomId))
        val stagedRoom = room.copy(saveStationSpawns = room.saveStationSpawns.filterNot { it.area == spawn.area && it.saveIndex == spawn.slot }.toMutableList())
        validateSourceSaveOwnership(spawn, catalog.parser)
        // Adopt the spawn first so deleting an original station can write an explicit released record.
        val staged = AsmRoomObjectSource(source.withSave(spawn), catalog.symbols)
        val result = staged.editSaveStation(stagedRoom, stateId, remove, add, spawn, externalObjectReferences(), allStates)
        replaceSaveSource(roomId, source, result, setOf(spawn.area to spawn.slot))
    }

    fun availableSaveSlot(roomId: Int, area: Int, reservedSlots: Set<Int> = emptySet()): Int? {
        require(area in 0..5) { "Ceres/debug load records are not ordinary save slots." }
        val all = project().rooms.values.filter { it.asmPlmSourceVersion != 0 }.flatMap { room -> saveResources(room.roomId) }
        val entries = project().rooms.values.filter { it.asmPlmSourceVersion != 0 &&
            (it.effectiveHeaderChange?.area ?: catalog.parser.readRoomHeader(it.roomId)?.area) == area
        }.flatMap { room -> room.states.flatMap { managedView(room.roomId, it.id).let { view ->
            require(view.problem == null) { view.problem ?: "Invalid save source." }
            view.document!!.entries.filter { it.id == 0xB76F }
        } } }
        val legacy = project().rooms.values.flatMap { it.saveStationSpawns }
        for (slot in 0 until catalog.parser.saveStationSlotCount(area)) {
            if (slot in reservedSlots) continue
            val claim = all.singleOrNull { it.area == area && it.slot == slot }
            if (all.count { it.area == area && it.slot == slot } > 1) error("Conflicting authored save slots need review.")
            if (claim != null) {
                if (claim.ownerRoomId == roomId && claim.cleared) return slot
                continue
            }
            if (catalog.parser.readSaveEntry(area, slot)?.roomId != 0 || legacy.any { it.area == area && it.saveIndex == slot } ||
                entries.any { (it.param and 255) == slot }) continue
            return slot
        }
        return null
    }

    fun saveTarget(roomId: Int, area: Int, slot: Int): AsmPlmPlacementTarget = try {
        val resource = requireNotNull(saveSpawn(roomId, area, slot)) { "No authored spawn record yet." }
        require(!resource.cleared) { "This save slot is released." }
        AsmPlmPlacementTarget.Source(sourceText().take(managedSource().macro(resource.identity).start).count { it == '\n' }, 0)
    } catch (error: Exception) { AsmPlmPlacementTarget.Unavailable(error.message ?: "Invalid save source") }

    fun hasSaveSource(roomId: Int, area: Int, slot: Int) = managedSource().contains(AsmSaveStationSource.name(roomId, area, slot))

    fun scrollTarget(roomId: Int, stateId: String, entry: RomParser.PlmEntry): AsmPlmPlacementTarget {
        return try {
            scrollCommands(roomId, stateId, entry) // Do not navigate to stale/invalid source.
            val record = managedView(roomId, stateId).document!!.records.single { it.entry == entry }
            val (width, height) = dimensions(roomId)
            val name = record.scrollResource ?: AsmScrollCommandSource.verifiedOriginalName(catalog.originalText, catalog.symbols,
                entry.param, catalog.parser, width * height)
                ?: return AsmPlmPlacementTarget.Unavailable("This ROM stream has no verified named ASM declaration yet; Edit commands to create one.")
            val line = if (record.scrollResource != null) sourceText().take(managedSource().macro(name).start).count { it == '\n' }
                else sourceText().lineSequence().indexOfFirst { it.startsWith("$name:") }
            AsmPlmPlacementTarget.Source(line, 0)
        } catch (error: Exception) { AsmPlmPlacementTarget.Unavailable(error.message ?: "Invalid scroll source") }
    }

    fun managedView(roomId: Int, stateId: String): AsmPlmSourceView { return try {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val identity = roomObjectIdentity(room)
        val macros = workspace.projectSourceText("macros.asm")
        val text = sourceText()
        catalog.requireStandardMacro(macros)
        if (projectedText == text && projectedMacros == macros) {
            projections[roomId]?.takeIf { it.identity == identity }?.documents?.get(stateId)?.let { document ->
                lastManaged[roomId to stateId] = document
                return AsmPlmSourceView(document, null)
            }
        } else {
            projectedText = text
            projectedMacros = macros
            projections.clear()
        }
        val source = managedSource()
        val (width, height) = dimensions(roomId)
        val inputs = source.resources(room, width * height)
        validateSourceSaveLinks(roomId, saveArea(room),
            inputs.values.flatMap { it.entries }, source.saveResources(roomId), room.saveStationSpawns, catalog.parser)
        source.saveResources(roomId).forEach { save ->
            require(save.cleared || save.area == saveArea(room)) { "Save source area ownership changed." }
            validateSourceSaveOwnership(save, catalog.parser)
        }
        val documents = room.states.associate { it.id to source.table(roomId, it.id).document }
        projections[roomId] = AsmRoomProjection(identity, documents)
        val document = requireNotNull(documents[stateId]) { "Missing authored state $stateId" }
        lastManaged[roomId to stateId] = document
        AsmPlmSourceView(document, null)
    } catch (problem: Exception) {
        AsmPlmSourceView(lastManaged[roomId to stateId], problem.message ?: "Invalid room-object source")
    } }

    fun managedPlacement(roomId: Int, stateId: String, entry: RomParser.PlmEntry): AsmPlmPlacementTarget {
        val view = managedView(roomId, stateId)
        return if (view.problem != null) AsmPlmPlacementTarget.Unavailable(view.problem)
        else view.document?.placementTarget(entry) ?: AsmPlmPlacementTarget.Unavailable("No current object source.")
    }

    /** Membership, not implicit linking: equal rows in independent lists remain independently editable. */
    fun managedIsInAllStates(roomId: Int, stateId: String, entry: RomParser.PlmEntry): Boolean = runCatching {
        val room = project().rooms.getValue(project().roomKey(roomId))
        if (managedView(roomId, stateId).let { it.problem != null || it.document?.entries?.count { row -> row == entry } != 1 }) return@runCatching false
        val commands = if (entry.id == 0xB703) scrollCommands(roomId, stateId, entry) else null
        room.states.all { managedView(roomId, it.id).let { view ->
            view.problem == null && view.document?.entries?.count { row -> row == entry } == 1 &&
                (commands == null || scrollCommands(roomId, it.id, entry) == commands)
        } }
    }.getOrDefault(false)

    private fun externalObjectReferences(): Set<String> = workspace.projectSourceTextsExcept(PLM_SOURCE_FILE)
        .flatMap { AsmRoomObjectSource.referencedHelpers(it).asSequence() }.toSet()

    fun managedEntries(): Sequence<RomParser.PlmEntry> = project().rooms.values.asSequence()
        .filter { it.asmPlmSourceVersion != 0 }.flatMap { room ->
            room.states.asSequence().flatMap { managedView(room.roomId, it.id).document?.entries.orEmpty().asSequence() }
        }

    fun editManaged(roomId: Int, stateId: String, allStates: Boolean,
                    remove: (RomParser.PlmEntry) -> Boolean, add: RomParser.PlmEntry?, companions: List<RomParser.PlmEntry> = emptyList()) {
        val room = project().rooms.getValue(project().roomKey(roomId))
        val source = managedSource()
        source.resources(room)
        val edited = AsmRoomObjectSource(source.edit(room, stateId, allStates, remove, add, externalObjectReferences(), companions = companions), catalog.symbols)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, source.text, edited.withComments(roomId, describePlm))
    }

    /** No workspace or model mutation until every row, spawn and resource link is checked. */
    fun applyStamps(roomId: Int, stateId: String, expected: String, stamps: List<AsmPlmStamp>,
                    beforeUndo: () -> Unit, beforeRedo: () -> Unit, layoutEdits: List<com.supermetroid.editor.data.TileEdit> = emptyList()): Boolean {
        require(sourceText() == expected) { "Object source changed during painting; try the stroke again." }
        val room = project().rooms.getValue(project().roomKey(roomId))
        val (width, height) = dimensions(roomId)
        val external = externalObjectReferences()
        var source = managedSource()
        source.resources(room, width * height)
        for (stamp in stamps) {
            val entry = stamp.entry
            val remove: (RomParser.PlmEntry) -> Boolean = { it.x == entry.x && it.y == entry.y && it.id in stamp.replaceIds }
            val states = if (stamp.allStates) room.states.map { it.id } else listOf(stateId)
            val target = (stamp.companions + entry).groupingBy { it }.eachCount()
            if (states.all { source.table(roomId, it).document.entries.filter(remove).groupingBy { it }.eachCount() == target } &&
                (stamp.save == null || stamp.save in source.saveResources(roomId))) continue
            val updated = if (stamp.save != null) {
                validateSourceSaveOwnership(stamp.save, catalog.parser)
                require(room.saveStationSpawns.none { it.area == stamp.save.area && it.saveIndex == stamp.save.slot }) {
                    "Adopt the save spawn before stamping over its GUI override."
                }
                val staged = AsmRoomObjectSource(source.withSave(stamp.save), catalog.symbols)
                staged.editSaveStation(room, stateId, remove, entry, stamp.save, external, stamp.allStates)
            } else source.edit(room, stateId, stamp.allStates, remove, entry, external, companions = stamp.companions)
            source = AsmRoomObjectSource(updated, catalog.symbols)
        }
        val resources = source.resources(room, width * height)
        val saves = source.saveResources(roomId)
        validateSourceSaveLinks(roomId, saveArea(room),
            resources.values.flatMap { it.entries }, saves, room.saveStationSpawns, catalog.parser)
        saves.forEach { validateSourceSaveOwnership(it, catalog.parser) }
        project().rooms.values.filter { it.asmPlmSourceVersion != 0 }.flatMap { source.saveResources(it.roomId) }
            .groupBy { it.area to it.slot }.forEach { (_, owners) ->
                require(owners.distinct().size == 1) { "Another source room already owns this save slot." }
            }
        var after = source.withComments(roomId, describePlm)
        if (room.asmLayoutSourceVersion != 0 && layoutEdits.isNotEmpty()) {
            after = AsmRoomLayoutSource(after).edit(room, stateId, width * 16, height * 16, layoutEdits)
        }
        if (after == expected) return false
        workspace.replaceProjectSource(PLM_SOURCE_FILE, expected, after, beforeUndo, beforeRedo)
        return true
    }

    fun updateManaged(roomId: Int, stateId: String, expected: AsmPlmDocument, index: Int, entry: RomParser.PlmEntry) {
        val view = managedView(roomId, stateId)
        require(view.problem == null && view.document?.text == expected.text) { view.problem ?: "Source changed; reopen this object." }
        val room = project().rooms.getValue(project().roomKey(roomId))
        val updated = AsmRoomObjectSource(AsmRoomObjectSource(expected.replace(index, entry), catalog.symbols)
            .tidy(room, externalObjectReferences()), catalog.symbols)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, expected.text, updated.withComments(roomId, describePlm))
    }

    fun cloneManagedState(roomId: Int, template: String, stateId: String) {
        if (!isManaged(roomId)) return
        val source = managedSource()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, source.text, source.cloneState(roomId, template, stateId), beforeUndo = {
            require(project().rooms[project().roomKey(roomId)]?.states?.none { it.id == stateId } == true) {
                "Delete state $stateId in Room Info before undoing its object-source declaration."
            }
        })
    }

    /** Adopt both inputs in a private model, then edit them. Failure publishes nothing, including ownership. */
    fun planGeneration(roomId: Int, name: String, stateIds: Set<String>, isItem: (Int) -> Boolean,
                       layoutEdits: List<com.supermetroid.editor.data.TileEdit> = emptyList()): AsmRoomGenerationPlan {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Room source requires ASM mode." }
        require(owner.newRooms.isEmpty()) { "Generation source for projects with added rooms needs stable source identities first." }
        catalog.requireStandardMacro(workspace.projectSourceText("macros.asm"))
        val room = owner.rooms.getValue(owner.roomKey(roomId))
        require(stateIds.isNotEmpty() && room.states.isNotEmpty() && stateIds.all { id -> room.states.any { it.id == id } }) { "Select existing room states before generating." }
        val before = sourceText()
        val snapshot = snapshotRoomObjectMetadata(room)
        val staged = snapshot.copy(scrollChanges = room.scrollChanges.toMutableList(),
            states = snapshot.states.map { it.copy(scrollChanges = it.scrollChanges.toMutableList()) }.toMutableList())
        val otherTexts = workspace.projectSourceTextsExcept(PLM_SOURCE_FILE)
        val objectReferences = otherTexts.flatMap { AsmRoomObjectSource.referencedHelpers(it) }.toSet()
        val gridReferences = otherTexts.flatMap { AsmInitialScrollSource.references(it) }.toSet()
        var text = before
        if (staged.asmPlmSourceVersion == 0) {
            requireObjectAdoptionAvailable(roomId)
            text = AsmRoomObjectSource.migrate(text, staged, catalog.parser, name, catalog.symbols, isItem, describePlm, objectReferences).text
            staged.plmChanges.clear(); staged.customScrollCommands.clear(); staged.saveStationSpawns.clear()
            staged.states.forEach { it.plmChanges.clear(); it.customScrollCommands.clear() }
            staged.asmPlmSourceVersion = 1
        }
        if (staged.asmInitialScrollSourceVersion == 0) {
            text = planInitialScrollMigration(text, staged, catalog.parser, name, catalog.symbols).text
            staged.scrollChanges.clear(); staged.states.forEach { it.scrollChanges.clear() }
            staged.asmInitialScrollSourceVersion = 1
        }
        val header = requireNotNull(catalog.parser.readRoomHeader(roomId))
        val width = room.effectiveHeaderChange?.width ?: header.width
        val height = room.effectiveHeaderChange?.height ?: header.height
        val objects = AsmRoomObjectSource(text, catalog.symbols)
        objects.resources(staged, width * height)
        val grids = AsmInitialScrollSource(text, catalog.symbols).resources(staged, width, height)
        if (stateIds.any { state -> objects.table(roomId, state).document.entries.any { RomParser.isScrollPlm(it.id) } }) {
            text = objects.edit(staged, stateIds.first(), false, { RomParser.isScrollPlm(it.id) }, null,
                objectReferences, stateIds = stateIds)
        }
        val values = List(width * height) { if (it / width == height - 1) 2 else 1 }
        text = AsmInitialScrollSource(text, catalog.symbols).editGrids(staged, stateIds, width, height, values)
        text = AsmInitialScrollSource(AsmInitialScrollSource(text, catalog.symbols).tidy(staged, gridReferences), catalog.symbols).withComments(staged, width, height)
        val finalObjects = AsmRoomObjectSource(text, catalog.symbols)
        val resources = finalObjects.resources(staged, width * height)
        val saves = finalObjects.saveResources(roomId)
        saves.forEach { validateSourceSaveOwnership(it, catalog.parser) }
        validateSourceSaveLinks(roomId, saveArea(room),
            resources.values.flatMap { it.entries }, saves, staged.saveStationSpawns, catalog.parser)
        text = finalObjects.withComments(roomId, describePlm)
        if (room.asmLayoutSourceVersion != 0 && layoutEdits.isNotEmpty()) {
            require(stateIds.map { id -> room.states.single { it.id == id }.resources.level }.distinct().size == 1) { "Generated layout targets must share one resource." }
            text = AsmRoomLayoutSource(text).edit(staged, stateIds.first(), width * 16, height * 16, layoutEdits)
        }
        val finalGrids = AsmInitialScrollSource(text, catalog.symbols).resources(staged, width, height)
        return AsmRoomGenerationPlan(owner, room, room.effectiveHeaderChange, workspace.sourceBufferRevision, before, text, AsmRoomBank8FMetadata.capture(room), AsmRoomBank8FMetadata.capture(staged),
            grids.mapValues { it.value.values }, finalGrids.mapValues { it.value.values }, resources.mapValues { it.value.entries })
    }

    fun applyGeneration(plan: AsmRoomGenerationPlan, beforeUndo: () -> Unit, beforeRedo: () -> Unit): Boolean {
        require(project().asmWorkspace.enabled && project().asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Room source requires ASM mode." }
        fun requireMetadata(expected: AsmRoomBank8FMetadata) {
            require(project() === plan.owner && project().rooms[project().roomKey(plan.room.roomId)] === plan.room &&
                plan.room.effectiveHeaderChange == plan.header && AsmRoomBank8FMetadata.capture(plan.room) == expected) {
                "Room source metadata changed; undo newer room/state edits first."
            }
        }
        requireMetadata(plan.metadataBefore)
        require(sourceText() == plan.before && workspace.sourceBufferRevision == plan.sourceRevision) { "Project source changed while generation was planned; try again." }
        if (plan.before == plan.after) return false
        workspace.replaceProjectSource(PLM_SOURCE_FILE, plan.before, plan.after, beforeUndo = {
            requireMetadata(plan.metadataAfter)
            beforeUndo()
            plan.metadataBefore.applyTo(plan.room)
            onMetadataChanged()
        }, beforeRedo = {
            requireMetadata(plan.metadataBefore)
            beforeRedo()
            plan.metadataAfter.applyTo(plan.room)
            onMetadataChanged()
        })
        plan.metadataAfter.applyTo(plan.room)
        onMetadataChanged()
        return true
    }

    private fun requireObjectAdoptionAvailable(roomId: Int) {
        for ((pointer, consumers) in catalog.consumers) if (consumers.any { it.roomId == roomId }) {
            val owner = authority(pointer)
            require(owner.authority in setOf(AsmPlmAuthority.ROOM_EDITOR, AsmPlmAuthority.UNCHANGED)) {
                owner.explanation ?: "Original PLM source already has edits; automatic migration will not overwrite them."
            }
        }
    }

    fun planReset(roomId: Int): AsmRoomResetPlan? {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Room source requires ASM mode." }
        require(owner.newRooms.isEmpty()) { "Source-aware reset for projects with added rooms needs stable source identities first." }
        val room = owner.rooms.getValue(owner.roomKey(roomId))
        requireNoExternalModelDoorReferences(owner, roomId)
        val returning = AsmRoomRelocationReset(catalog, workspace, areaMoves).plan(owner, room, describePlm)
        val sources = linkedMapOf(PLM_SOURCE_FILE to sourceText())
        returning?.dependencies?.replacements?.forEach { (id, pair) -> sources[id] = pair.first }
        if (room.asmDoorSourceVersion != 0 || room.asmFxSourceVersion != 0) sources[FX_SOURCE_FILE] = workspace.projectSourceText(FX_SOURCE_FILE)
        if (room.asmEnemySourceVersion != 0) sources[ENEMY_SOURCE_FILE] = workspace.projectSourceText(ENEMY_SOURCE_FILE)
        if (returning?.outgoing?.isNotEmpty() == true) sources[DOOR_SOURCE_FILE] = workspace.projectSourceText(DOOR_SOURCE_FILE)
        val external = workspace.projectSourceReadersExcept(sources.keys).asSequence().map { it() }
        val stagedSources = sources.mapValues { (id, before) -> returning?.dependencies?.replacements?.get(id)?.second ?: before }
        val cleanupOwner = returning?.dependencies?.rooms?.get(owner.roomKey(roomId))?.after ?: room
        val after = resetRoomSource(cleanupOwner, stagedSources, external).toMutableMap()
        // Keep stable native state identities, not a duplicate of authored resource values.
        val restored = com.supermetroid.editor.data.RoomEdits(roomId).also { it.ensureStateManifest(catalog.parser) }
        if (returning?.outgoing?.isNotEmpty() == true) {
            val native = requireNotNull(catalog.parser.readRoomHeader(roomId))
            val corrections = returning.outgoing.associateBy { it.doorIndex }
            val doors = com.supermetroid.editor.rom.SourceRoomDoors(native.doorOut,
                catalog.parser.parseDoorList(native.doorOut).mapIndexed { index, entry ->
                    com.supermetroid.editor.rom.SourceRoomDoor(entry.doorDefPtr, corrections[index] ?:
                        com.supermetroid.editor.data.DoorChange(index, entry.destRoomPtr, entry.bitflag, entry.doorCapCode,
                            entry.screenX, entry.screenY, entry.distFromDoor, entry.entryCode))
                })
            com.supermetroid.editor.rom.validateSourceDoorOwnership(roomId, doors, catalog.parser)
            require(!Regex("(?im)^[ \\t]*macro[ \\t]+${AsmDoorSource.prefix(roomId)}Door_[0-9]+\\b").containsMatchIn(after.getValue(DOOR_SOURCE_FILE))) {
                "An external source reference retains this room's DoorDefs. Review that reference before resetting its connection data."
            }
            val newline = if (after.getValue(DOOR_SOURCE_FILE).contains("\r\n")) "\r\n" else "\n"
            after[DOOR_SOURCE_FILE] = after.getValue(DOOR_SOURCE_FILE) + newline + AsmDoorSource.render(roomId, native.name, doors).replace("\n", newline)
            restored.asmDoorSourceVersion = 1
            restored.asmDoorProjection = doors.entries.map { it.change }
        }
        val native = requireNotNull(catalog.parser.readRoomHeader(roomId))
        val oldWidth = room.effectiveHeaderChange?.width ?: native.width
        val oldHeight = room.effectiveHeaderChange?.height ?: native.height
        val geometry = if (oldWidth != native.width || oldHeight != native.height)
            AsmRoomResizeDoors(catalog, workspace, areaMoves).plan(owner, restored, sourceText(), after.getValue(PLM_SOURCE_FILE),
                oldWidth, oldHeight, native.width, native.height,
                stagedRooms = returning?.dependencies?.rooms?.mapValues { it.value.after }.orEmpty(),
                staged83 = after[DOOR_SOURCE_FILE], restoreNative = true)
        else null
        if (geometry != null) {
            geometry.transaction.replacements.forEach { (id, pair) -> sources.putIfAbsent(id, pair.first); after[id] = pair.second }
            // A room-owned arrival that was previously pinned by a neighbor may now have
            // become unreachable after copy-on-write. Retire it without dropping the new
            // native DoorDefs required for moved neighbors.
            val roots = if (restored.asmDoorSourceVersion != 0) catalog.parser.parseDoorList(native.doorOut).indices
                .map { AsmDoorSource.name(roomId, it) }.toSet() else emptySet()
            val cleaned = resetRoomSource(room, after, workspace.projectSourceReadersExcept(sources.keys).asSequence().map { it() }, roots)
            after.putAll(cleaned)
        }
        val dependencyPlan = geometry?.transaction?.let { plan -> returning?.dependencies?.let { movement ->
            plan.copy(mapsAfter = movement.mapsAfter, stationsAfter = movement.stationsAfter)
        } ?: plan } ?: returning?.dependencies
        if (room.asmDoorSourceVersion != 0) {
            val references = Regex("\\b${AsmDoorSource.prefix(roomId)}Door_[0-9]+_Entry\\b")
            require((after.values.asSequence() + workspace.projectSourceReadersExcept(sources.keys).asSequence().map { it() })
                .none { text -> references.containsMatchIn(text.lineSequence().joinToString("\n") { asmCodeBeforeComment(it) }) }) {
                "This room's DoorDef symbols still have explicit ASM references. Retarget those references before resetting door ownership."
            }
        }
        val relocation = dependencyPlan?.let { dependencies ->
            val prospective = owner.copy(rooms = owner.rooms.toMutableMap().apply {
                dependencies.rooms.forEach { (key, change) -> put(key, change.after) }
                put(owner.roomKey(roomId), restored)
            })
            // Reset can shrink back to native geometry. Validate final DoorDefs against
            // that final model, not the temporary pre-reset header used for staging.
            val affected = dependencies.rooms.filterKeys { it != owner.roomKey(roomId) }.values.map { it.after } + restored
            val authored = affected.filter { it.asmDoorSourceVersion != 0 }
            if (authored.isNotEmpty()) {
                val doorSource = AsmDoorSource(after.getValue(DOOR_SOURCE_FILE), after.getValue(PLM_SOURCE_FILE), catalog.symbols)
                for (target in authored) doorSource.resource(target, catalog.parser) { id ->
                    catalog.parser.readRoomHeader(id)?.let { native ->
                        val edits = prospective.rooms[owner.roomKey(id)]
                        (edits?.roomHeaderChange?.width ?: native.width) to (edits?.roomHeaderChange?.height ?: native.height)
                    }
                }
            }
            dependencies.copy(dependenciesAfter = areaMoves.dependencies(prospective))
        }
        // A reset room can still need an authored cross-area bit because its neighbor moved.
        // Repeating Reset must not repeatedly retire/recreate that required declaration.
        if (room == restored && relocation != null && relocation.dependenciesBefore == relocation.dependenciesAfter &&
            relocation.mapsBefore == relocation.mapsAfter && relocation.stationsBefore == relocation.stationsAfter &&
            AsmDoorSource(workspace.projectSourceText(DOOR_SOURCE_FILE), sourceText(), catalog.symbols).resource(room, catalog.parser) ==
            AsmDoorSource(after.getValue(DOOR_SOURCE_FILE), after.getValue(PLM_SOURCE_FILE), catalog.symbols).resource(restored, catalog.parser)) return null
        return AsmRoomResetPlan(owner, room, snapshotRoomForResize(room), restored,
            sources.mapValues { (id, before) -> before to after.getValue(id) }, resetExternalReferences(sources.keys), relocation)
    }

    fun planTheme(roomId: Int, name: String, stateIds: Set<String>, tileset: Int?, change: com.supermetroid.editor.data.FxChange?,
                  generation: AsmRoomGenerationPlan? = null): AsmRoomThemePlan {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Room themes require ASM mode." }
        require(owner.newRooms.isEmpty()) { "Themes in projects with added rooms need stable source identities first." }
        require(tileset == null || tileset in 0..28) { "Unsupported theme tileset." }
        val room = owner.rooms.getValue(owner.roomKey(roomId))
        val beforeModel = snapshotRoomForResize(room)
        val staged = snapshotRoomForResize(room)
        require(stateIds.isNotEmpty() && staged.states.map { it.id }.containsAll(stateIds)) { "Select existing room states before applying a theme." }
        if (generation != null) {
            require(generation.owner === owner && generation.room === room && generation.sourceRevision == workspace.sourceBufferRevision) { "Generation inputs changed; try again." }
            generation.metadataAfter.applyTo(staged)
        }
        val nativeStates = catalog.parser.parseRoomStatesWithData(roomId)
        if (tileset != null && staged.asmRoomPropertiesSourceVersion == 0) for (state in staged.states.filter { it.id in stateIds }) {
            val native = nativeStates.getOrNull(requireNotNull(state.baseSourceStateIndex()))
            val existing = state.effectiveStateDataChange ?: com.supermetroid.editor.data.StateDataChange()
            state.stateDataChange = existing.copy(tileset = tileset.takeUnless {
                it == native?.tileset && staged.effectiveStateDataChange?.tileset == null
            }).takeUnless { it == com.supermetroid.editor.data.StateDataChange() }
        }
        val sources = linkedMapOf(PLM_SOURCE_FILE to (sourceText() to (generation?.after ?: sourceText())))
        if (tileset != null && staged.asmRoomPropertiesSourceVersion != 0) {
            val source = AsmRoomPropertiesSource(sources.getValue(PLM_SOURCE_FILE).second)
            val current = source.resource(staged)
            val updated = source.editStates(staged, current.states.filterKeys { it in stateIds }.mapValues { it.value.copy(tileset = tileset) })
            AsmRoomPropertiesSource(updated).resource(staged).projectInto(staged)
            sources[PLM_SOURCE_FILE] = sourceText() to updated
        }
        if (change != null || staged.asmFxSourceVersion != 0) {
            val before = workspace.projectSourceText(FX_SOURCE_FILE)
            var text = before
            if (change != null) {
                if (staged.asmFxSourceVersion == 0) {
                    text = planFxMigration(text, staged, catalog.parser, name)
                    staged.fxChange = null
                    staged.states.forEach { it.fxChange = null; it.doorFxChanges.clear() }
                    staged.asmFxSourceVersion = 1
                }
                com.supermetroid.editor.rom.requireDoorRoutedFxOwnership(staged, catalog.parser)
                text = AsmFxSource(text).replaceDefaults(staged, stateIds, change)
                val external = workspace.projectSourceTextsExcept(FX_SOURCE_FILE).flatMap { AsmFxSource.references(it).asSequence() }.toSet()
                text = AsmFxSource(AsmFxSource(text).tidy(staged, external)).withComments(staged)
                AsmFxSource(text).resources(staged).values.forEach { com.supermetroid.editor.rom.validateSourceFxDoors(roomId, it, catalog.parser) }
            }
            sources[FX_SOURCE_FILE] = before to text
        }
        if (room.asmEnemySourceVersion != 0) workspace.projectSourceText(ENEMY_SOURCE_FILE).let { sources[ENEMY_SOURCE_FILE] = it to it }
        return AsmRoomThemePlan(owner, room, beforeModel, staged, workspace.sourceBufferRevision, sources, resetExternalReferences(sources.keys))
    }

    /** A themed generation also owns layout sidecars in afterModel; publish all banks and metadata once. */
    fun applyTheme(plan: AsmRoomThemePlan, beforeTransition: () -> Unit, applySidecar: (Boolean) -> Unit,
                   afterTransition: (Boolean) -> Unit) {
        val afterSnapshot = snapshotRoomForResize(plan.afterModel)
        fun validate(undo: Boolean) {
            val expected = if (undo) plan.afterModel else plan.room
            require(project() === plan.owner && plan.owner.rooms[plan.owner.roomKey(plan.room.roomId)] === expected &&
                expected == if (undo) afterSnapshot else plan.beforeModel) { "Room data changed; undo newer room/state edits before restoring this theme." }
            require(resetExternalReferences(plan.replacements.keys) == plan.externalReferences) { "Custom source references changed; undo those edits before restoring this theme." }
            beforeTransition()
        }
        fun apply(undo: Boolean) {
            plan.owner.rooms[plan.owner.roomKey(plan.room.roomId)] = if (undo) plan.room else plan.afterModel
            applySidecar(undo)
        }
        fun notify(undo: Boolean) { onMetadataChanged(); afterTransition(undo) }
        validate(false)
        require(plan.sourceRevision == workspace.sourceBufferRevision) { "Project source changed while the theme was planned; try again." }
        workspace.replaceProjectSources(plan.replacements,
            beforeUndo = { validate(true); apply(true) }, beforeRedo = { validate(false); apply(false) },
            afterUndo = { notify(true) }, afterRedo = { notify(false) },
            beforeApply = { apply(false) }, afterApply = { notify(false) })
    }

    private fun resetExternalReferences(participants: Set<String>): Set<String> =
        workspace.projectSourceReadersExcept(participants).asSequence().flatMap { resetSourceReferences(it()).asSequence() }.toSet()

    private fun requireNoExternalModelDoorReferences(owner: SmEditProject, roomId: Int) {
        require(owner.rooms.values.filter { it.roomId != roomId }.none { other -> other.states.any { state ->
            state.effectiveCondition.flattened().any { it.sourceDoor?.roomId == roomId }
        } }) { "Another room's state condition uses this room's DoorDef identity. Retarget it before resetting door ownership." }
    }

    fun applyReset(plan: AsmRoomResetPlan, beforeTransition: () -> Unit, afterTransition: (Boolean) -> Unit): AsmRoomAreaMoveHistory {
        var history: AsmRoomAreaMoveHistory? = null
        val cleanSnapshot = snapshotRoomForResize(plan.restored)
        fun validate(undo: Boolean) {
            require(project().asmWorkspace.enabled && project().asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE && project().newRooms.isEmpty()) {
                "Restore the existing-room ASM workspace before restoring this reset."
            }
            val expected = if (undo) plan.restored else plan.room
            val snapshot = if (undo) cleanSnapshot else plan.original
            require(project() === plan.owner && plan.owner.rooms[plan.owner.roomKey(plan.room.roomId)] === expected && expected == snapshot) {
                "Room data changed; undo newer room/state edits before restoring this reset."
            }
            require(resetExternalReferences(plan.replacements.keys) == plan.externalReferences) {
                "Custom source references changed; undo those edits before restoring this reset."
            }
            if (!undo) requireNoExternalModelDoorReferences(plan.owner, plan.room.roomId)
            plan.relocation?.let { areaMoves.requireCurrent(it, undo, exceptRoomId = plan.room.roomId, action = "room reset") }
            history?.beforeTransition?.invoke()
            beforeTransition()
        }
        fun setRoom(undo: Boolean) {
            plan.relocation?.let { areaMoves.setModel(it, undo, exceptRoomId = plan.room.roomId) }
            plan.owner.rooms[plan.owner.roomKey(plan.room.roomId)] = if (undo) plan.room else plan.restored
        }
        fun notify(undo: Boolean) {
            onMetadataChanged()
            afterTransition(undo)
            history?.onTransition?.invoke(undo)
        }
        validate(false)
        workspace.replaceProjectSources(plan.replacements,
            beforeUndo = { validate(true); setRoom(true) },
            beforeRedo = { validate(false); setRoom(false) },
            afterUndo = { notify(true) }, afterRedo = { notify(false) },
            beforeApply = { setRoom(false) }, afterApply = { notify(false) })
        return AsmRoomAreaMoveHistory(workspace, plan.replacements, requireNotNull(workspace.sourceUndoHead(PLM_SOURCE_FILE)), "room reset")
            .also { history = it }
    }

    /** Stage complete room inputs before changing shared dimensions or publishing ownership. */
    fun planResize(roomId: Int, name: String, width: Int, height: Int, isItem: (Int) -> Boolean,
                   roomSnapshot: com.supermetroid.editor.data.RoomEdits? = null): AsmRoomResizePlan {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Room source requires ASM mode." }
        require(owner.newRooms.isEmpty()) { "Source-aware resize for projects with added rooms needs stable source identities first." }
        require(width in 1..15 && height in 1..15 && width * height <= 50) { "Room scroll grids support 1–15 dimensions and at most 50 screens." }
        catalog.requireStandardMacro(workspace.projectSourceText("macros.asm"))
        val existing = owner.rooms[owner.roomKey(roomId)]
        val room = existing ?: roomSnapshot ?: com.supermetroid.editor.data.RoomEdits(roomId).also { it.ensureStateManifest(catalog.parser) }
        require(room.roomId == roomId) { "The resize room identity changed; reopen Resize." }
        require(room.states.isNotEmpty()) { "Load the room's states before resizing." }
        val beforeModel = snapshotRoomForResize(room)
        val staged = snapshotRoomForResize(room)
        val before = sourceText()
        var text = before
        val references = externalObjectReferences()
        if (staged.asmPlmSourceVersion == 0) {
            requireObjectAdoptionAvailable(roomId)
            text = AsmRoomObjectSource.migrate(text, staged, catalog.parser, name, catalog.symbols, isItem, describePlm, references).text
            staged.plmChanges.clear(); staged.customScrollCommands.clear(); staged.saveStationSpawns.clear()
            staged.states.forEach { it.plmChanges.clear(); it.customScrollCommands.clear() }
            staged.asmPlmSourceVersion = 1
        }
        if (staged.asmInitialScrollSourceVersion == 0) {
            text = planInitialScrollMigration(text, staged, catalog.parser, name, catalog.symbols).text
            staged.scrollChanges.clear(); staged.states.forEach { it.scrollChanges.clear() }
            staged.asmInitialScrollSourceVersion = 1
        }
        val native = requireNotNull(catalog.parser.readRoomHeader(roomId))
        val oldWidth = room.effectiveHeaderChange?.width ?: native.width
        val oldHeight = room.effectiveHeaderChange?.height ?: native.height
        val grids = AsmInitialScrollSource(text, catalog.symbols).resources(staged, oldWidth, oldHeight)
        text = AsmRoomObjectSource(text, catalog.symbols).resizeScrolls(staged, oldWidth, oldHeight, width, height) { pointer ->
            val commands = AsmScrollCommandSource.original(before, catalog.symbols, pointer, catalog.parser, native.width * native.height, catalog.originalText)
            resizeScrollCommands(commands, native.width, oldWidth, oldHeight)
        }
        text = AsmInitialScrollSource(text, catalog.symbols).resize(staged, oldWidth, oldHeight, width, height)
        if (staged.asmLayoutSourceVersion != 0) text = AsmRoomLayoutSource(text).resize(staged, oldWidth, oldHeight, width, height)
        staged.roomHeaderChange = (staged.roomHeaderChange ?: com.supermetroid.editor.data.RoomHeaderChange()).copy(width = width, height = height)
        text = AsmRoomObjectSource(text, catalog.symbols).tidy(staged, references)
        val objects = AsmRoomObjectSource(text, catalog.symbols)
        val inputs = objects.resources(staged, width * height)
        val saves = objects.saveResources(roomId)
        saves.forEach { validateSourceSaveOwnership(it, catalog.parser) }
        validateSourceSaveLinks(roomId, saveArea(room),
            inputs.values.flatMap { it.entries }, saves, staged.saveStationSpawns, catalog.parser)
        text = objects.withScrollComments(staged, width, height)
        val resizedGrids = AsmInitialScrollSource(text, catalog.symbols).resources(staged, width, height)
        val doors = AsmRoomResizeDoors(catalog, workspace, areaMoves).plan(owner, staged, before, text, oldWidth, oldHeight, width, height)
        text = doors.transaction.replacements.getValue(PLM_SOURCE_FILE).second
        return AsmRoomResizePlan(owner, room, existing != null, beforeModel, staged, workspace.sourceBufferRevision, before, text,
            if (room.asmEnemySourceVersion != 0) workspace.projectSourceText(ENEMY_SOURCE_FILE) else null,
            grids.mapValues { it.value.values }, resizedGrids.mapValues { it.value.values }, inputs.mapValues { it.value.entries },
            doors, resetExternalReferences(doors.transaction.replacements.keys))
    }

    fun applyResize(plan: AsmRoomResizePlan, beforeTransition: () -> Unit, onTransition: (Boolean) -> Unit): AsmRoomAreaMoveHistory {
        var history: AsmRoomAreaMoveHistory? = null
        fun validate(undo: Boolean) {
            val expected = if (undo) plan.afterModel else plan.beforeModel
            require(project() === plan.owner && project().rooms[project().roomKey(plan.room.roomId)] ===
                (if (undo || plan.wasPresent) plan.room else null) && plan.room == expected) {
                "Room data changed; undo newer room/state edits before resizing."
            }
            areaMoves.requireCurrent(plan.doors.transaction, undo, exceptRoomId = plan.room.roomId, action = "room resize")
            val incoming = AsmRoomResizeDoors(catalog, workspace, areaMoves).incoming(project(), plan.room.roomId, sourceText())
            require(incoming == if (undo) plan.doors.incomingAfter else plan.doors.incomingBefore) {
                "Incoming doors changed; undo newer door edits before restoring this resize."
            }
            require(resetExternalReferences(plan.doors.transaction.replacements.keys) == plan.externalReferences) {
                "Arrival references changed; undo newer source references before restoring this resize."
            }
            require(plan.actorSource == null || workspace.projectSourceText(ENEMY_SOURCE_FILE) == plan.actorSource) {
                "Actor source changed; undo newer actor edits before resizing."
            }
            history?.beforeTransition?.invoke()
            beforeTransition()
        }
        fun apply(snapshot: com.supermetroid.editor.data.RoomEdits, undo: Boolean) {
            areaMoves.setModel(plan.doors.transaction, undo, exceptRoomId = plan.room.roomId)
            applyRoomResizeModel(plan.room, snapshot)
            if (undo && !plan.wasPresent) plan.owner.rooms.remove(plan.owner.roomKey(plan.room.roomId))
            else plan.owner.rooms[plan.owner.roomKey(plan.room.roomId)] = plan.room
        }
        fun notify(undo: Boolean) {
            onMetadataChanged()
            onTransition(undo)
            history?.onTransition?.invoke(undo)
        }
        validate(false)
        require(sourceText() == plan.before && workspace.sourceBufferRevision == plan.sourceRevision) { "Project source changed while resize was planned; try again." }
        require(plan.before != plan.after) { "Resize did not produce an authored grid change." }
        workspace.replaceProjectSources(plan.doors.transaction.replacements,
            beforeUndo = { validate(true); apply(plan.beforeModel, true) }, afterUndo = { notify(true) },
            beforeRedo = { validate(false); apply(plan.afterModel, false) }, afterRedo = { notify(false) },
            beforeApply = { apply(plan.afterModel, false) }, afterApply = { notify(false) })
        return AsmRoomAreaMoveHistory(workspace, plan.doors.transaction.replacements,
            requireNotNull(workspace.sourceUndoHead(PLM_SOURCE_FILE)), "room resize").also { history = it }
    }

    /** Stage source and retire only its GUI object streams. No ROM/build/filesystem writes. */
    fun previewObjects(room: com.supermetroid.editor.data.RoomEdits, name: String,
                       isItem: (Int) -> Boolean = { RomParser.isItemPlm(it) }): String {
        catalog.requireStandardMacro(workspace.projectSourceText("macros.asm"))
        val before = sourceText()
        return roomSourceAddition(before, AsmRoomObjectSource.migrate(before, room, catalog.parser, name,
            catalog.symbols, isItem, describePlm, externalObjectReferences()).text)
    }

    fun migrateRoom(roomId: Int, name: String, isItem: (Int) -> Boolean = { RomParser.isItemPlm(it) }) {
        if (isManaged(roomId)) return
        val current = project()
        require(current.asmWorkspace.enabled && current.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Room source requires ASM mode." }
        require(current.newRooms.isEmpty()) { "Object source migration is not supported in projects with added rooms yet; their room graph still uses the ROM backend." }
        catalog.requireStandardMacro(workspace.projectSourceText("macros.asm"))
        val room = current.rooms[current.roomKey(roomId)] ?: com.supermetroid.editor.data.RoomEdits(roomId)
        requireObjectAdoptionAvailable(roomId)
        val before = workspace.projectSourceText(PLM_SOURCE_FILE)
        val migration = AsmRoomObjectSource.migrate(before, room, catalog.parser, name, catalog.symbols, isItem, describePlm,
            externalObjectReferences())
        AsmRoomObjectSource(migration.text, catalog.symbols).saveResources(roomId).forEach { validateSourceSaveOwnership(it, catalog.parser) }
        val oldCommon = room.plmChanges.toList()
        val oldSpawns = room.saveStationSpawns.toList()
        val oldScrolls = room.customScrollCommands.mapValues { it.value.toList() }
        val oldStates = room.states.toList()
        val oldChanges = migration.states.associate { it.id to it.plmChanges.toList() }
        val oldStateScrolls = migration.states.associate { it.id to it.customScrollCommands.mapValues { (_, value) -> value.toList() } }
        fun adopt() {
            require(room.asmPlmSourceVersion == 0 && room.plmChanges == oldCommon &&
                room.states == oldStates && room.customScrollCommands == oldScrolls && room.saveStationSpawns == oldSpawns &&
                migration.states.all { it.plmChanges == oldChanges[it.id] && it.customScrollCommands == oldStateScrolls[it.id] }) {
                "Room objects changed after migration was undone; migrate again instead of replaying stale source."
            }
            current.rooms[current.roomKey(roomId)] = room
            if (room.states.isEmpty()) room.states.addAll(migration.states)
            room.plmChanges.clear()
            room.customScrollCommands.clear()
            room.saveStationSpawns.clear()
            room.states.forEach { it.plmChanges.clear(); it.customScrollCommands.clear() }
            room.asmPlmSourceVersion = 1
            onMetadataChanged()
        }
        fun restore() {
            require(room.asmPlmSourceVersion == 1 && room.states.map { it.id } == migration.states.map { it.id } &&
                room.plmChanges.isEmpty() && room.customScrollCommands.isEmpty() && room.saveStationSpawns.isEmpty() &&
                room.states.all { it.plmChanges.isEmpty() && it.customScrollCommands.isEmpty() }) {
                "Room state membership changed after migration. Undo those state changes before undoing object migration."
            }
            room.asmPlmSourceVersion = 0
            room.plmChanges.addAll(oldCommon)
            room.customScrollCommands.putAll(oldScrolls.mapValues { it.value.toMutableList() })
            room.saveStationSpawns.addAll(oldSpawns)
            room.states.forEach {
                it.plmChanges.addAll(oldChanges[it.id].orEmpty())
                it.customScrollCommands.putAll(oldStateScrolls[it.id].orEmpty().mapValues { (_, value) -> value.toMutableList() })
            }
            if (oldStates.isEmpty()) room.states.clear()
            onMetadataChanged()
        }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, before, migration.text, ::restore, ::adopt)
        adopt()
    }

    fun authority(pointer: Int): AsmPlmAuthorityView {
        val current = project()
        if (!current.asmWorkspace.enabled || current.asmWorkspace.buildMode != ProjectRomBuildMode.ASM_SOURCE)
            return AsmPlmAuthorityView(AsmPlmAuthority.UNAVAILABLE, explanation = "Room objects use the loaded-ROM workflow.")
        val source = sourceText()
        val macros = workspace.projectSourceText("macros.asm")
        val revision = sourceRevision?.takeIf { it.first == source && it.second == macros }?.third ?: catalog.SourceRevision(source, macros)
            .also { sourceRevision = Triple(source, macros, it) }
        return revision.authority(current, pointer)
    }

    fun isLinked(pointer: Int): Boolean = authority(pointer).authority in setOf(AsmPlmAuthority.SOURCE, AsmPlmAuthority.CONFLICT)

    fun placementTarget(roomId: Int, stateIndex: Int, pointer: Int, entry: RomParser.PlmEntry): AsmPlmPlacementTarget {
        if (catalog.consumers[pointer].orEmpty().none { it.roomId == roomId && it.stateIndex == stateIndex }) {
            return AsmPlmPlacementTarget.Unavailable("No verified ASM placement list matches this room state.")
        }
        val current = authority(pointer)
        return when (current.authority) {
            AsmPlmAuthority.SOURCE, AsmPlmAuthority.UNCHANGED -> current.document?.placementTarget(entry)
                ?: AsmPlmPlacementTarget.Unavailable("The current ASM placement list could not be read.")
            AsmPlmAuthority.ROOM_EDITOR -> AsmPlmPlacementTarget.Unavailable(
                "This room's placements are managed in the room editor. Editable ASM placement source is not available for this list yet.",
            )
            else -> AsmPlmPlacementTarget.Unavailable(current.explanation ?: "No verified ASM source matches this placement.")
        }
        // Deliberately do not use view(): its last-valid document may be older than an invalid draft.
    }

    /** Navigate to the authored declaration even when an invalid body cannot be decoded. */
    fun sourceLine(pointer: Int): Int? {
        val binding = runCatching { catalog.binding(pointer) }.getOrNull() ?: return null
        return workspace.projectSourceText(PLM_SOURCE_FILE).lineSequence()
            .indexOfFirst { it.substringBefore(';').trim() == "${binding.label}:" }.takeIf { it >= 0 }
    }

    fun sourceOwnedEntries(): Sequence<RomParser.PlmEntry> = catalog.consumers.keys.asSequence()
        .filter(::isLinked).flatMap { view(it).document?.entries.orEmpty().asSequence() } + managedEntries()

    fun link(pointer: Int) {
        val binding = catalog.binding(pointer)
        catalog.validateOwnership(project(), binding)
        val document = catalog.document(binding, workspace.projectSourceText(PLM_SOURCE_FILE), workspace.projectSourceText("macros.asm"))
        if (project().asmWorkspace.plmSourceBindings.none { it.pointer == pointer }) project().asmWorkspace.plmSourceBindings += binding
        lastValid[pointer] = document
    }

    fun unlink(pointer: Int) {
        val binding = project().asmWorkspace.plmSourceBindings.single { it.pointer == pointer }
        val view = view(pointer)
        require(view.problem == null && view.document?.let { catalog.isOriginal(binding, it) } == true) {
            "Restore this list's original values in ASM (or Undo its edits) before unlinking. Otherwise the room preview would lose your source edits."
        }
        project().asmWorkspace.plmSourceBindings.remove(binding)
        lastValid.remove(pointer)
    }

    fun view(pointer: Int): AsmPlmSourceView {
        val view = authority(pointer)
        return when (view.authority) {
            AsmPlmAuthority.SOURCE, AsmPlmAuthority.UNCHANGED -> {
                view.document?.let { lastValid[pointer] = it }
                AsmPlmSourceView(view.document, null)
            }
            AsmPlmAuthority.CONFLICT -> AsmPlmSourceView(
                lastValid[pointer].takeIf { catalog.guiOwner(project(), pointer) == null }, view.explanation)
            // Never project original ASM over GUI additions, per-state differences, or a rewritten state graph.
            else -> AsmPlmSourceView(null, view.explanation)
        }
    }

    fun update(pointer: Int, expected: AsmPlmDocument, index: Int, entry: RomParser.PlmEntry) {
        catalog.validateOwnership(project(), catalog.binding(pointer))
        val view = view(pointer)
        require(view.problem == null) { view.problem.orEmpty() }
        require(view.document?.text == expected.text) { "Source changed while this object was open. Reopen it before applying changes." }
        workspace.replaceProjectSource(PLM_SOURCE_FILE, expected.text, expected.replace(index, entry))
    }
}

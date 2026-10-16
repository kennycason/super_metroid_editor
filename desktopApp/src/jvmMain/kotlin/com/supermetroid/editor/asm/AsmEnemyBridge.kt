package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*

internal data class AsmEnemyView(val resource: SourceEnemyResources?, val problem: String?)

/** Paired actor/GFX transactions, independently shared tables, and last-valid live projections. */
internal class AsmEnemyBridge(private val catalog: AsmPlmSourceCatalog, private val workspace: AsmWorkspaceState,
                             private val project: () -> SmEditProject, private val metadataChanged: () -> Unit) {
    private var revision: Pair<String, AsmEnemySource>? = null
    private val lastGood = mutableMapOf<Pair<Int, String>, SourceEnemyResources>()
    fun sourceText() = workspace.projectSourceText(ENEMY_SOURCE_FILE)
    private fun source(): AsmEnemySource {
        val text = sourceText()
        return revision?.takeIf { it.first == text }?.second ?: AsmEnemySource(text, catalog.symbols).also { revision = text to it }
    }
    fun isManaged(roomId: Int) = (project().rooms[project().roomKey(roomId)]?.asmEnemySourceVersion ?: 0) != 0
    private fun original(roomId: Int, state: RoomStateEdits): SourceEnemyResources {
        val native = catalog.parser.inspectRoomStates(roomId).states
        val pc = requireNotNull(native.getOrNull(requireNotNull(state.baseSourceStateIndex()))?.stateDataPcOffset) { "Enemy state has no source/template." }
        val fields = catalog.parser.readStateData(pc)
        return SourceEnemyResources(readSourceEnemyPopulation(catalog.parser, fields.getValue("enemySetPtr"), "Original population"),
            readSourceEnemyGraphics(catalog.parser, fields.getValue("enemyGfxPtr"), "Original GFX"))
    }
    private fun validate(room: RoomEdits, stateId: String, input: SourceEnemyResources) {
        val baseline = original(room.roomId, room.states.single { it.id == stateId })
        require(completeEnemyGraphics(catalog.parser, input.population.entries, baseline.population.entries, input.graphics, baseline.graphics) == input.graphics) {
            "${input.population.identity}: a new actor needs a GFX entry. Add its transfer to the paired Graphics macro; no data was replaced."
        }
    }
    fun view(roomId: Int, stateId: String): AsmEnemyView = try {
        catalog.requireStandardEnemyMacros(workspace.projectSourceText("macros.asm"))
        val room = project().rooms.getValue(project().roomKey(roomId))
        val resource = source().resources(room).getValue(stateId)
        validate(room, stateId, resource)
        lastGood[roomId to stateId] = resource
        AsmEnemyView(resource, null)
    } catch (error: Exception) { AsmEnemyView(lastGood[roomId to stateId], error.message ?: "Invalid enemy source.") }
    fun migrate(roomId: Int, roomName: String) {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Enemy source requires ASM mode." }
        if (isManaged(roomId)) return
        require(owner.newRooms.isEmpty()) { "Enemy source for projects with added rooms is not supported yet." }
        catalog.requireStandardEnemyMacros(workspace.projectSourceText("macros.asm"))
        val room = owner.rooms[owner.roomKey(roomId)] ?: RoomEdits(roomId)
        val staged = room.copy(states = room.states.toMutableList())
        staged.ensureStateManifest(catalog.parser)
        val before = sourceText()
        val after = migrationText(before, staged, roomName)
        val oldStates = room.states.toList()
        val common = room.enemyChanges.toList()
        val scoped = staged.states.associate { it.id to it.enemyChanges.toList() }
        fun adopt() {
            require(room.asmEnemySourceVersion == 0 && room.states == oldStates && room.enemyChanges == common &&
                staged.states.all { it.enemyChanges == scoped[it.id] }) { "Enemy metadata changed; adopt again." }
            owner.rooms[owner.roomKey(roomId)] = room
            if (room.states.isEmpty()) room.states.addAll(staged.states)
            room.enemyChanges.clear(); room.states.forEach { it.enemyChanges.clear() }
            room.asmEnemySourceVersion = 1
            metadataChanged()
        }
        fun restore() {
            require(room.asmEnemySourceVersion == 1 && room.states.map { it.id } == staged.states.map { it.id } &&
                room.enemyChanges.isEmpty() && room.states.all { it.enemyChanges.isEmpty() }) { "Undo newer enemy/state changes first." }
            room.asmEnemySourceVersion = 0
            room.enemyChanges.addAll(common); room.states.forEach { it.enemyChanges.addAll(scoped.getValue(it.id)) }
            if (oldStates.isEmpty()) room.states.clear()
            metadataChanged()
        }
        workspace.replaceProjectSource(ENEMY_SOURCE_FILE, before, after, ::restore, ::adopt)
        adopt()
    }

    fun preview(room: RoomEdits, name: String): String {
        catalog.requireStandardEnemyMacros(workspace.projectSourceText("macros.asm"))
        room.ensureStateManifest(catalog.parser)
        val before = sourceText()
        return roomSourceAddition(before, migrationText(before, room, name))
    }

    /** The preview and adoption deliberately run the same planner and validation. */
    private fun migrationText(before: String, staged: RoomEdits, roomName: String): String {
        val roomId = staged.roomId
        val prefix = AsmEnemySource.prefix(roomId)
        require(!Regex("(?m)^macro $prefix").containsMatchIn(before)) { "Existing enemy declarations need review before adoption." }
        val populations = linkedMapOf<Pair<String?, SourceEnemyPopulation>, SourceEnemyPopulation>()
        val graphics = linkedMapOf<Pair<String?, SourceEnemyGraphics>, SourceEnemyGraphics>()
        val addition = buildString {
            appendLine("; SMEDIT room actors · ${roomName.replace('\n', ' ').replace('\r', ' ')}")
            appendLine("; dw species, pixel X, pixel Y, init, properties, extra properties, parameter 1, parameter 2.")
            appendLine("; GFX: dw species, VRAM destination. Checked placement emits population in A1 and GFX in B4.")
            appendLine("; State wrappers share each list independently; GUI changes detach only the selected state.")
            appendLine("; State wrappers are planner recipes, not records to emit inline; placement invokes each table in its bank.")
            for (state in staged.states) {
                val baseline = original(roomId, state)
                val pc = requireNotNull(catalog.parser.inspectRoomStates(roomId).states[requireNotNull(state.baseSourceStateIndex())].stateDataPcOffset)
                val pointers = catalog.parser.readStateData(pc)
                val entries = applyEnemyChanges(baseline.population.entries, staged.enemyChanges + state.enemyChanges)
                val population = populations.getOrPut(pointers["enemySetPtr"].toString() to baseline.population.copy(entries = entries)) {
                    baseline.population.copy(identity = prefix + "Population_${populations.size + 1}", entries = entries).also { appendLine(AsmEnemySource.renderPopulation(it)) }
                }
                val completed = completeEnemyGraphics(catalog.parser, entries, baseline.population.entries, baseline.graphics, baseline.graphics)
                val gfx = graphics.getOrPut(pointers["enemyGfxPtr"].toString() to completed) {
                    completed.copy(identity = prefix + "Graphics_${graphics.size + 1}").also { appendLine(AsmEnemySource.renderGraphics(it)) }
                }
                appendLine(AsmEnemySource.renderState(roomId, state.id, population.identity, gfx.identity))
            }
        }
        val after = before + (if (before.endsWith('\n')) "\n" else "\n\n") + addition
        AsmEnemySource(after, catalog.symbols).resources(staged.copy(asmEnemySourceVersion = 1, enemyChanges = mutableListOf(),
            states = staged.states.map { it.copy(enemyChanges = mutableListOf()) }.toMutableList()))
        return after
    }
    fun edit(roomId: Int, stateId: String, transform: (List<RomParser.EnemyEntry>) -> List<RomParser.EnemyEntry>) {
        val current = view(roomId, stateId)
        require(current.problem == null) { current.problem ?: "Resolve the enemy source draft first." }
        val room = project().rooms.getValue(project().roomKey(roomId))
        val input = requireNotNull(current.resource)
        val entries = transform(input.population.entries)
        SourceEnemyPopulation(input.population.identity, entries, input.population.killCount)
        val baseline = original(roomId, room.states.single { it.id == stateId })
        val completed = completeEnemyGraphics(catalog.parser, entries, baseline.population.entries, input.graphics, baseline.graphics)
        val source = source()
        val changed = source.replace(room, stateId, entries, completed.entries)
        val outside = workspace.projectSourceTextsExcept(ENEMY_SOURCE_FILE).flatMap { AsmEnemySource.references(it) }.toSet()
        val tidy = AsmEnemySource(changed, catalog.symbols).tidy(room, outside)
        val after = AsmEnemySource(tidy, catalog.symbols).withComments(room)
        AsmEnemySource(after, catalog.symbols).resources(room).forEach { (state, resource) -> validate(room, state, resource) }
        workspace.replaceProjectSource(ENEMY_SOURCE_FILE, source.text, after)
    }
    fun line(roomId: Int, stateId: String, index: Int? = null, graphics: Boolean = false) = source().line(roomId, stateId, index, graphics)
    fun hasState(roomId: Int, id: String) = isManaged(roomId) && source().hasState(roomId, id)
    fun cloneState(roomId: Int, template: String, newId: String) {
        if (!isManaged(roomId)) return
        val current = source()
        workspace.replaceProjectSource(ENEMY_SOURCE_FILE, current.text, current.cloneState(roomId, template, newId), beforeUndo = {
            require(project().rooms[project().roomKey(roomId)]?.states?.none { it.id == newId } == true) { "Delete the new state before undoing its actor declaration." }
        })
    }
    fun prepare(text: String): () -> String {
        val rooms = project().rooms.values.filter { it.asmEnemySourceVersion != 0 }.map { it.copy(enemyChanges = it.enemyChanges.toMutableList(),
            states = it.states.map { state -> state.copy(enemyChanges = state.enemyChanges.toMutableList()) }.toMutableList()) }
        val readers = workspace.projectSourceReadersExcept(ENEMY_SOURCE_FILE)
        return {
            val external = readers.flatMap { AsmEnemySource.references(it()) }.toSet()
            rooms.fold(text) { current, room -> runCatching {
                val source = AsmEnemySource(current, catalog.symbols)
                source.resources(room)
                AsmEnemySource(source.tidy(room, external), catalog.symbols).withComments(room)
            }.getOrDefault(current) }
        }
    }
}

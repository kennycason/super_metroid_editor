package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*

/** Private prospective metadata and all participating documents form one source-history entry. */
internal data class AsmRoomStatePlan(
    val owner: SmEditProject,
    val original: RoomEdits,
    val before: RoomEdits,
    val after: RoomEdits,
    val revision: Long,
    val replacements: Map<String, Pair<String, String>>,
    val externalReferences: Set<String>,
    val macros: String,
)

internal class AsmRoomStateLifecycle(
    private val catalog: AsmPlmSourceCatalog,
    private val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject,
    private val changed: () -> Unit,
) {
    private fun sources(room: RoomEdits) = linkedMapOf(PLM_SOURCE_FILE to workspace.projectSourceText(PLM_SOURCE_FILE)).apply {
        if (room.asmFxSourceVersion != 0) put(FX_SOURCE_FILE, workspace.projectSourceText(FX_SOURCE_FILE))
        if (room.asmEnemySourceVersion != 0) put(ENEMY_SOURCE_FILE, workspace.projectSourceText(ENEMY_SOURCE_FILE))
    }

    fun nextId(room: RoomEdits): String {
        val participants = sources(room)
        val texts = participants.values + workspace.projectSourceReadersExcept(participants.keys).map { it() }
        // Deleting an unreferenced wrapper must not recycle its identity for a different state.
        // UUIDs also avoid relying on a disposable source helper as a persisted ordinal counter.
        return generateSequence { "state-new-${java.util.UUID.randomUUID()}" }.first { id ->
            val encoded = id.toByteArray(Charsets.UTF_8).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            room.states.none { it.id == id } && texts.none { "_State_$encoded(" in it || "_Condition_$encoded(" in it }
        }
    }

    /** [changeModel] operates only on a deep snapshot. No source or live model is changed on failure. */
    fun plan(roomId: Int, name: String, added: Pair<String, String>? = null,
             condition: ProjectRoomStateCondition? = null, changeModel: (RoomEdits) -> Unit): AsmRoomStatePlan {
        val owner = project()
        if (owner.newRoomForPreviewId(roomId) != null) return planProjectResources(roomId, added, changeModel)
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE && owner.newRooms.isEmpty()) {
            "Source state editing requires existing-room ASM source mode."
        }
        val room = owner.rooms.getValue(owner.roomKey(roomId))
        val before = snapshotRoomForResize(room)
        val staged = snapshotRoomForResize(room)
        val originalSources = sources(room)
        val texts = originalSources.toMutableMap()
        val macros = workspace.projectSourceText("macros.asm")
        validate(staged, texts, macros)
        val selectors = if (staged.asmStateSelectorsSourceVersion != 0) AsmRoomSelectorsSource(texts.getValue(PLM_SOURCE_FILE)).resource(staged)
            else SourceRoomSelectors(staged.effectiveStates.map { it.id }, staged.states.associate { it.id to it.effectiveCondition }).also { input ->
                require(!Regex("(?m)^macro ${AsmRoomSelectorsSource.prefix(roomId)}").containsMatchIn(texts.getValue(PLM_SOURCE_FILE))) {
                    "Existing selector declarations need review before adoption."
                }
                texts[PLM_SOURCE_FILE] = texts.getValue(PLM_SOURCE_FILE).trimEnd() + "\n\n" + AsmRoomSelectorsSource.render(roomId, name, input)
                staged.states.forEach { it.condition = AsmRoomSelectorsSource.baseline(it); it.conditionChanged = false }
                staged.asmStateSelectorsSourceVersion = 1
                input.projectInto(staged)
            }
        selectors.projectInto(staged)
        require(selectors.routines.isEmpty()) { "State membership changes require typed selectors. Keep ASM-only predicates and their state identities in source." }
        val oldMembership = snapshotRoomForResize(staged)
        changeModel(staged)
        val removed = oldMembership.states.map { it.id }.toSet() - staged.states.map { it.id }.toSet()
        val nextOrder = selectors.order.filter { it !in removed }.toMutableList()
        var nextConditions = selectors.conditions.filterKeys { it !in removed }
        if (added != null) {
            val (template, id) = added
            require(removed.isEmpty() && staged.states.size == oldMembership.states.size + 1)
            val templateIndex = nextOrder.indexOf(template)
            require(templateIndex >= 0)
            nextOrder.add(if (templateIndex == nextOrder.lastIndex) templateIndex else templateIndex + 1, id)
            nextConditions = nextConditions + (id to requireNotNull(condition))
            val state = staged.states.single { it.id == id }
            state.condition = AsmRoomSelectorsSource.baseline(state); state.conditionChanged = false
            var bank = texts.getValue(PLM_SOURCE_FILE)
            if (staged.asmPlmSourceVersion != 0) bank = AsmRoomObjectSource(bank, catalog.symbols).cloneState(roomId, template, id)
            if (staged.asmInitialScrollSourceVersion != 0) bank = AsmInitialScrollSource(bank, catalog.symbols).cloneState(roomId, template, id)
            if (staged.asmLayoutSourceVersion != 0) bank = AsmRoomLayoutSource(bank).appendState(roomId, id, state.resources.level)
            if (staged.asmRoomPropertiesSourceVersion != 0) bank = AsmRoomPropertiesSource(bank).cloneState(oldMembership, template, id)
            texts[PLM_SOURCE_FILE] = bank
            if (staged.asmFxSourceVersion != 0) texts[FX_SOURCE_FILE] = AsmFxSource(texts.getValue(FX_SOURCE_FILE)).cloneState(roomId, template, id)
            if (staged.asmEnemySourceVersion != 0) texts[ENEMY_SOURCE_FILE] = AsmEnemySource(texts.getValue(ENEMY_SOURCE_FILE), catalog.symbols).cloneState(roomId, template, id)
        } else require(removed.size == 1 && staged.states.size == oldMembership.states.size - 1)
        val next = SourceRoomSelectors(nextOrder, nextConditions)
        texts[PLM_SOURCE_FILE] = AsmRoomSelectorsSource(texts.getValue(PLM_SOURCE_FILE)).changeStates(oldMembership, next)
        next.projectInto(staged)
        staged.stateGraphChanged = true

        val savesToRelease = mutableListOf<Pair<SourceSaveResource, List<String>>>()
        if (removed.isNotEmpty() && staged.asmPlmSourceVersion != 0) {
            val source = AsmRoomObjectSource(texts.getValue(PLM_SOURCE_FILE), catalog.symbols)
            val removedSlots = removed.flatMap { source.table(roomId, it).document.entries }.filter { it.id == 0xB76F }.map { it.param and 255 }.toSet()
            val activeSlots = staged.states.flatMap { source.table(roomId, it.id).document.entries }.filter { it.id == 0xB76F }.map { it.param and 255 }.toSet()
            for (save in source.saveResources(roomId).filter { it.slot in removedSlots - activeSlots && !it.cleared }) {
                val lists = removed.map { source.table(roomId, it) }.filter { table ->
                    table.document.entries.any { it.id == 0xB76F && it.param and 255 == save.slot }
                }.map { it.resource.name }
                savesToRelease += save to lists
            }
        }

        // Active wrappers are roots; preserve helpers pinned by any other bank or custom module.
        val roots = buildSet {
            add(AsmRoomSelectorsSource.orderName(roomId))
            if (staged.asmRoomPropertiesSourceVersion != 0) add(AsmRoomPropertiesSource.headerName(roomId))
            if (staged.asmPlmSourceVersion != 0) addAll(AsmRoomObjectSource(texts.getValue(PLM_SOURCE_FILE), catalog.symbols).saveResources(roomId).map { it.identity })
            for (state in staged.states) {
                add(AsmRoomSelectorsSource.conditionName(roomId, state.id))
                if (staged.asmPlmSourceVersion != 0) add(AsmRoomObjectSource.stateMacro(roomId, state.id))
                if (staged.asmInitialScrollSourceVersion != 0) add(AsmInitialScrollSource.stateName(roomId, state.id))
                if (staged.asmLayoutSourceVersion != 0) add(AsmRoomLayoutSource.stateName(roomId, state.id))
                if (staged.asmRoomPropertiesSourceVersion != 0) add(AsmRoomPropertiesSource.stateName(roomId, state.id))
                if (staged.asmFxSourceVersion != 0) add(AsmFxSource.stateName(roomId, state.id))
                if (staged.asmEnemySourceVersion != 0) add(AsmEnemySource.stateName(roomId, state.id))
            }
        }
        val external = workspace.projectSourceReadersExcept(texts.keys).map { it() }
        val cleaned = resetRoomSource(staged, texts, external.asSequence(), roots).toMutableMap()
        for ((save, oldLists) in savesToRelease) {
            val source = AsmRoomObjectSource(cleaned.getValue(PLM_SOURCE_FILE), catalog.symbols)
            require(oldLists.none(source::contains)) { "The final save station is still referenced by retained custom source. Resolve that reference before deleting its state." }
            cleaned[PLM_SOURCE_FILE] = source.withSave(save.copy(words = List(7) { 0 }, sourceDoor = null))
        }
        validate(staged, cleaned, macros)
        AsmRoomSelectorsSource(cleaned.getValue(PLM_SOURCE_FILE)).resource(staged).projectInto(staged)
        if (staged.asmRoomPropertiesSourceVersion != 0) AsmRoomPropertiesSource(cleaned.getValue(PLM_SOURCE_FILE)).resource(staged).projectInto(staged)
        return AsmRoomStatePlan(owner, room, before, staged, workspace.sourceBufferRevision,
            originalSources.mapValues { (file, text) -> text to cleaned.getValue(file) },
            external.flatMap(::resetSourceReferences).toSet(), macros)
    }

    /** New-room conditions remain semantic project data; owned resource wrappers join the same history. */
    private fun planProjectResources(roomId: Int, added: Pair<String, String>?, changeModel: (RoomEdits) -> Unit): AsmRoomStatePlan {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Project room source requires ASM mode." }
        val room = owner.rooms.getValue(owner.roomKey(roomId))
        val before = snapshotRoomForResize(room)
        val staged = snapshotRoomForResize(room)
        val texts = sources(room)
        val macros = workspace.projectSourceText("macros.asm")
        validate(staged, texts, macros)
        val bank = texts.getValue(PLM_SOURCE_FILE)
        val identities = AsmRoomPropertiesSource.projectIdentities(owner)
        changeModel(staged)
        var updated = bank
        if (added != null) {
            if (staged.asmRoomPropertiesSourceVersion != 0)
                updated = AsmRoomPropertiesSource(updated, identities).cloneState(before, added.first, added.second)
            if (staged.asmLayoutSourceVersion != 0) updated = AsmRoomLayoutSource(updated, identities)
                .appendState(roomId, added.second, staged.states.single { it.id == added.second }.resources.level)
        }
        staged.stateGraphChanged = true
        val replacements = texts + (PLM_SOURCE_FILE to updated)
        validate(staged, replacements, macros)
        if (staged.asmRoomPropertiesSourceVersion != 0) AsmRoomPropertiesSource(updated, identities).resource(staged).projectInto(staged)
        val external = workspace.projectSourceReadersExcept(texts.keys).flatMap { resetSourceReferences(it()) }.toSet()
        return AsmRoomStatePlan(owner, room, before, staged, workspace.sourceBufferRevision,
            texts.mapValues { (file, text) -> text to replacements.getValue(file) }, external, macros)
    }

    private fun validate(room: RoomEdits, texts: Map<String, String>, macros: String) {
        if (project().newRoomForPreviewId(room.roomId) != null) {
            require(room.asmRoomPropertiesSourceVersion in 0..1 && room.asmLayoutSourceVersion in 0..1 &&
                (room.asmRoomPropertiesSourceVersion != 0 || room.asmLayoutSourceVersion != 0) &&
                room.asmStateSelectorsSourceVersion == 0 && room.asmDoorSourceVersion == 0 &&
                room.asmPlmSourceVersion == 0 && room.asmInitialScrollSourceVersion == 0 &&
                room.asmFxSourceVersion == 0 && room.asmEnemySourceVersion == 0) { "This project-room state resource needs its reciprocal source workflow first." }
            require(room.states.map { it.id }.distinct().size == room.states.size &&
                room.states.count { it.condition.kind == ProjectRoomStateConditionKind.DEFAULT } == 1 &&
                room.states.last().condition.kind == ProjectRoomStateConditionKind.DEFAULT) { "Project-room state identities/default order changed." }
            val identities = AsmProjectRoomLabels.identities(project())
            val bank = texts.getValue(PLM_SOURCE_FILE)
            if (room.asmRoomPropertiesSourceVersion != 0) AsmRoomPropertiesSource(bank, identities).resource(room)
            if (room.asmLayoutSourceVersion != 0) {
                val seed = project().newRoomForPreviewId(room.roomId)!!.header
                AsmRoomLayoutSource(bank, identities).resources(room, (room.effectiveHeaderChange?.width ?: seed.width) * 16,
                    (room.effectiveHeaderChange?.height ?: seed.height) * 16)
            }
            return
        }
        val header = requireNotNull(catalog.parser.readRoomHeader(room.roomId))
        val width = room.effectiveHeaderChange?.width ?: header.width
        val height = room.effectiveHeaderChange?.height ?: header.height
        val bank = texts.getValue(PLM_SOURCE_FILE)
        if (room.asmStateSelectorsSourceVersion != 0) validateSelectorDoorReferences(
            AsmRoomSelectorsSource(bank).resource(room), room.roomId, project(), catalog, workspace, bank)
        if (room.asmRoomPropertiesSourceVersion != 0) AsmRoomPropertiesSource(bank).resource(room)
        if (room.asmLayoutSourceVersion != 0) AsmRoomLayoutSource(bank).resources(room, width * 16, height * 16)
        if (room.asmInitialScrollSourceVersion != 0) AsmInitialScrollSource(bank, catalog.symbols).resources(room, width, height)
        if (room.asmPlmSourceVersion != 0) {
            catalog.requireStandardMacro(macros)
            AsmRoomObjectSource(bank, catalog.symbols).resources(room, width * height)
        }
        if (room.asmFxSourceVersion != 0) AsmFxSource(texts.getValue(FX_SOURCE_FILE)).resources(room).values.forEach {
            validateSourceFxDoors(room.roomId, it, catalog.parser, liveSourceDoors(it.entries.mapNotNull { entry -> entry.sourceDoor },
                project(), catalog, workspace, bank, it.entries.map { entry -> entry.doorSelect }))
        }
        if (room.asmEnemySourceVersion != 0) {
            catalog.requireStandardEnemyMacros(macros)
            val nativeStates = catalog.parser.inspectRoomStates(room.roomId).states
            AsmEnemySource(texts.getValue(ENEMY_SOURCE_FILE), catalog.symbols).resources(room).forEach { (id, input) ->
                val state = room.states.single { it.id == id }
                val native = requireNotNull(nativeStates.getOrNull(requireNotNull(state.baseSourceStateIndex()))?.stateDataPcOffset)
                val fields = catalog.parser.readStateData(native)
                val population = readSourceEnemyPopulation(catalog.parser, fields.getValue("enemySetPtr"), "Original population")
                val graphics = readSourceEnemyGraphics(catalog.parser, fields.getValue("enemyGfxPtr"), "Original GFX")
                require(completeEnemyGraphics(catalog.parser, input.population.entries, population.entries, input.graphics, graphics) == input.graphics) {
                    "State $id has actors without their required graphics transfers; repair the paired source before changing states."
                }
            }
        }
    }

    fun apply(plan: AsmRoomStatePlan, beforeTransition: () -> Unit, afterTransition: (Boolean) -> Unit) {
        val afterSnapshot = snapshotRoomForResize(plan.after)
        val projectId = plan.owner.newRoomForPreviewId(plan.original.roomId)?.id
        fun activeRoom(): RoomEdits = if (projectId == null) plan.original else {
            val id = requireNotNull(plan.owner.newRooms.singleOrNull { it.id == projectId }?.previewRoomId) { "This project room no longer exists." }
            requireNotNull(plan.owner.rooms[plan.owner.roomKey(id)]) { "This project's room-state ownership changed." }
        }
        fun metadata(room: RoomEdits) = room.copy(roomId = if (projectId == null) room.roomId else 0, asmHeaderPropertiesProjection = null, asmSelectorOrderProjection = null,
            states = room.states.map { it.copy(asmConditionProjection = null, asmStatePropertiesProjection = null) }.toMutableList())
        fun checkCurrent(undo: Boolean) {
            val active = activeRoom()
            require(project() === plan.owner && (projectId != null || plan.owner.rooms[plan.owner.roomKey(plan.original.roomId)] === plan.original) &&
                metadata(active) == metadata(if (undo) afterSnapshot else plan.before)) { "Room/state data changed; undo newer edits before restoring this state operation." }
            require(workspace.projectSourceText("macros.asm") == plan.macros) { "Macro definitions changed; restore them before this state operation." }
            val references = workspace.projectSourceReadersExcept(plan.replacements.keys).flatMap { resetSourceReferences(it()) }.toSet()
            require(references == plan.externalReferences) { "Custom source references changed; undo them before restoring this state operation." }
            beforeTransition()
        }
        fun setRoom(undo: Boolean) {
            // Preserve the room identity held by earlier source undo callbacks. Never expose our
            // immutable history snapshots as the mutable live state list.
            val snapshot = snapshotRoomForResize(if (undo) plan.before else plan.after)
            val active = activeRoom()
            active.states.clear(); active.states.addAll(snapshot.states)
            active.stateGraphChanged = snapshot.stateGraphChanged
            active.asmStateSelectorsSourceVersion = snapshot.asmStateSelectorsSourceVersion
            active.asmSelectorOrderProjection = snapshot.asmSelectorOrderProjection
            active.levelResourceOperations.clear()
            active.levelResourceOperations.putAll(snapshot.levelResourceOperations)
        }
        fun notify(undo: Boolean) { changed(); afterTransition(undo) }
        checkCurrent(false)
        require(plan.revision == workspace.sourceBufferRevision) { "Source changed while the state operation was planned; try again." }
        workspace.replaceProjectSources(plan.replacements,
            beforeUndo = { checkCurrent(true); setRoom(true) }, beforeRedo = { checkCurrent(false); setRoom(false) },
            afterUndo = { notify(true) }, afterRedo = { notify(false) },
            beforeApply = { setRoom(false) }, afterApply = { notify(false) })
    }
}

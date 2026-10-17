package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*

/** A private DoorDef keeps its semantic identity, while the old record remains valid for other rooms. */
internal class AsmDoorIndependence(
    private val catalog: AsmPlmSourceCatalog, private val workspace: AsmWorkspaceState,
    private val project: () -> SmEditProject, private val doors: AsmDoorSourceBridge,
    private val moves: AsmRoomAreaMove, private val context: AsmRoomSourceContext,
) {
    fun plan(roomId: Int, index: Int): AsmRoomAreaMovePlan {
        val owner = project()
        require(owner.asmWorkspace.enabled && owner.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) { "Private connections require ASM source mode." }
        val models = linkedMapOf<String, RoomEdits>()
        val before8F = workspace.projectSourceText(PLM_SOURCE_FILE)
        val before83 = workspace.projectSourceText(DOOR_SOURCE_FILE)
        val (after8F, after83) = stage(roomId, index, models, before8F, before83)
        val prospective = owner.copy(rooms = owner.rooms.toMutableMap().apply { putAll(models) })
        val changes = models.mapValues { (key, model) ->
            val original = owner.rooms[key]
            AsmAreaRoomChange(original ?: RoomEdits(model.roomId), original?.let(::snapshotRoomForResize), model)
        }
        return AsmRoomAreaMovePlan(owner, changes,
            linkedMapOf(PLM_SOURCE_FILE to (before8F to after8F), DOOR_SOURCE_FILE to (before83 to after83)),
            owner.minimapEdits.mapValues { it.value.toList() }, owner.minimapEdits.mapValues { it.value.toList() },
            owner.mapStationEdits.mapValues { it.value.toList() }, owner.mapStationEdits.mapValues { it.value.toList() },
            moves.dependencies(owner), moves.dependencies(prospective), null)
    }

    /** Reusable inside an area move: this only modifies private staging snapshots and strings. */
    fun stage(roomId: Int, index: Int, models: MutableMap<String, RoomEdits>, before8F: String, before83: String): Pair<String, String> {
        val owner = project()
        val parser = context.parser
        val ids = context.identities()
        fun room(id: Int) = models.getOrPut(owner.roomKey(id)) {
            snapshotRoomForResize(owner.rooms[owner.roomKey(id)] ?: RoomEdits(id)).also { it.ensureStateManifest(parser) }
        }
        fun dimensions(id: Int) = sourceRoomDimensions(owner.copy(rooms = owner.rooms.toMutableMap().apply { putAll(models) }), parser, id)
        val source = room(roomId)
        var text8F = before8F
        var text83 = before83
        if (source.asmDoorSourceVersion == 0) {
            text83 = doors.automaticSource(source, requireNotNull(parser.readRoomHeader(roomId)).name, text83, text8F)
            source.doorChanges.clear(); source.asmDoorSourceVersion = 1
        }
        val document = AsmDoorSource(text83, text8F, catalog.symbols, ids, owner.newRooms)
        val old = document.resource(source, parser, ::dimensions).entries.getOrNull(index)
        require(old != null && old.shared) { "This shared connection changed; review its current source." }
        text83 = document.makeIndependent(source, parser, index, ::dimensions)
        source.asmDoorProjection = AsmDoorSource(text83, text8F, catalog.symbols, ids, owner.newRooms).resource(source, parser, ::dimensions).entries.map { it.change }
        val reference = ProjectSourceDoorReference(roomId, old.identity)
        val targetWasStaged = owner.roomKey(old.change.destRoomPtr) in models
        val target = room(old.change.destRoomPtr)
        val name = requireNotNull(parser.readRoomHeader(target.roomId)).name

        // A native pointer means ANY of its shared consumers. Preserve that meaning after splitting.
        fun expand(condition: ProjectRoomStateCondition): ProjectRoomStateCondition {
            if (condition.kind == ProjectRoomStateConditionKind.INCOMING_DOOR && condition.sourceDoor == null && condition.argument == old.doorDefPtr)
                return projectRoomStateCondition(ProjectRoomStateConditionKind.ANY_OF, negated = condition.negated, children = listOf(
                    projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, condition.argument),
                    projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, sourceDoor = reference)))
            return condition.copy(children = condition.children.map(::expand))
        }
        val selectors = if (target.asmStateSelectorsSourceVersion != 0) AsmRoomSelectorsSource(text8F, ids).resource(target)
            else SourceRoomSelectors(target.effectiveStates.map { it.id }, target.states.associate { it.id to it.effectiveCondition })
        val conditions = selectors.conditions.mapValues { (_, condition) -> expand(condition) }
        if (conditions != selectors.conditions) {
            if (target.asmStateSelectorsSourceVersion == 0) {
                require(!Regex("(?m)^macro ${AsmRoomSelectorsSource.prefix(target.roomId, ids[target.roomId])}").containsMatchIn(text8F)) { "Retained selectors need review before splitting this connection." }
                text8F += "\n\n" + AsmRoomSelectorsSource.render(target.roomId, name, selectors, ids[target.roomId])
                target.states.forEach { it.condition = AsmRoomSelectorsSource.baseline(it); it.conditionChanged = false }
                target.asmStateSelectorsSourceVersion = 1
            }
            for ((id, condition) in conditions) if (condition != selectors.conditions[id])
                text8F = AsmRoomSelectorsSource(text8F, ids).editCondition(target, id, condition, preserveAsmSiblings = true)
            AsmRoomSelectorsSource(text8F, ids).resource(target).projectInto(target)
        }

        val hasNativeFx = if (target.asmFxSourceVersion != 0) AsmFxSource(text83, ids).resources(target).values.any { fx -> fx.entries.any { it.sourceDoor == null && it.doorSelect == old.doorDefPtr } }
            else parser.parseRoomStatesWithData(target.roomId).any { state -> parser.parseFxEntries(state.fxPtr).any { it.doorSelect == old.doorDefPtr } }
        if (hasNativeFx) {
            if (target.asmFxSourceVersion == 0) {
                text83 = planFxMigration(text83, target, parser, name, ids)
                target.fxChange = null; target.states.forEach { it.fxChange = null; it.doorFxChanges.clear() }; target.asmFxSourceVersion = 1
            }
            val resources = AsmFxSource(text83, ids).resources(target)
            for ((stateId, fx) in resources.entries.distinctBy { it.value.identity }) {
                val entry = fx.entries.singleOrNull { it.sourceDoor == null && it.doorSelect == old.doorDefPtr } ?: continue
                text83 = AsmFxSource(text83, ids).replaceEntry(target, stateId, 0,
                    entry.copy(doorSelect = 0, sourceDoor = reference), detach = false)
            }
        }
        // Native save records deliberately keep their existing, still-valid entrance. Explicit
        // source references already follow the unchanged connection label. No guessed rebind.
        if (!targetWasStaged && conditions == selectors.conditions && !hasNativeFx) models.remove(owner.roomKey(target.roomId))
        return text8F to text83
    }
}

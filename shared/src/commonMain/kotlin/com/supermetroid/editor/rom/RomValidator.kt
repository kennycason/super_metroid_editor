package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.SmEditProject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * ROM-wide validation scanner.
 * Checks for common issues that cause in-game bugs or crashes.
 */
@OptIn(ExperimentalEncodingApi::class)
object RomValidator {

    enum class Severity { ERROR, WARNING, INFO }

    data class Issue(
        val severity: Severity,
        val category: String,
        val roomId: Int?,
        val roomName: String,
        val message: String,
    )

    private fun hasCompleteAreaSaveMigration(
        parser: RomParser,
        project: SmEditProject,
        roomId: Int,
        sourceArea: Int,
        targetArea: Int,
    ): Boolean {
        val edits = project.rooms[project.roomKey(roomId)] ?: return false
        val effectivePlms = parser.getAllPlmEntriesForRoom(roomId).toMutableList()
        for (change in edits.plmChanges) {
            when (change.action) {
                "add" -> effectivePlms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                "remove" -> effectivePlms.removeAll {
                    it.id == change.plmId && it.x == change.x && it.y == change.y
                }
            }
        }
        for (state in edits.states) {
            for (change in state.plmChanges) {
                when (change.action) {
                    "add" -> effectivePlms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                    "remove" -> effectivePlms.removeAll {
                        it.id == change.plmId && it.x == change.x && it.y == change.y && it.param == change.param
                    }
                }
            }
        }
        val effectiveSaveIndices = effectivePlms.filter { it.id == 0xB76F }
            .map { it.param and 0xFF }
            .toSet()
        if (effectiveSaveIndices.isEmpty()) return false

        val allOverrides = project.rooms.values.flatMap { it.saveStationSpawns }
        val destinationSpawns = allOverrides.filter {
            !it.clearSlot && it.roomId == roomId && it.area == targetArea
        }
        if (destinationSpawns.isEmpty() || destinationSpawns.any { it.saveIndex !in effectiveSaveIndices }) {
            return false
        }
        if (effectiveSaveIndices.any { index -> destinationSpawns.none { it.saveIndex == index } }) return false

        val romReferences = buildList {
            for (area in 0 until MinimapData.NUM_AREAS) {
                for (index in 0 until parser.saveEntryCount(area)) {
                    if (parser.readSaveEntry(area, index)?.roomId == roomId) add(area to index)
                }
            }
        }
        return romReferences.all { (area, index) ->
            area == sourceArea && allOverrides.any {
                it.clearSlot && it.area == area && it.saveIndex == index
            }
        }
    }

    /**
     * Run all validations and return a list of issues found.
     */
    fun validate(parser: RomParser, roomIds: List<Int>, project: SmEditProject? = null): List<Issue> {
        val issues = mutableListOf<Issue>()
        val rooms = mutableMapOf<Int, Room>()
        for (rid in roomIds) {
            val room = parser.readRoomHeader(rid) ?: continue
            rooms[rid] = room
        }

        issues.addAll(checkDoorConsistency(parser, rooms))
        issues.addAll(checkItemBitflagDuplicates(parser, rooms))
        issues.addAll(checkEnemyGfxLimits(parser, rooms))
        issues.addAll(checkRoomDimensions(rooms))
        issues.addAll(checkPlmSets(parser, rooms))
        if (project != null) {
            issues.addAll(checkProjectOwnerIdentities(project))
            issues.addAll(checkProjectRoomHeaders(parser, project, rooms))
            issues.addAll(checkProjectRoomStates(parser, project, rooms))
            issues.addAll(checkProjectMinimapEdits(project))
            issues.addAll(checkProjectSaveStationSpawns(parser, project, rooms))
            issues.addAll(checkProjectGraphicsExportFit(parser, project))
            issues.addAll(checkProjectEnemyTileEdits(parser, project))
            issues.addAll(checkProjectSpritePalettes(parser, project))
        }

        return issues.sortedWith(compareBy({ it.severity }, { it.category }, { it.roomName }))
    }

    /**
     * Export ownership uses patch IDs and parsed room IDs. Ambiguous imported
     * keys must not collapse into one owner and gain same-owner overlap rights.
     */
    fun checkProjectOwnerIdentities(project: SmEditProject): List<Issue> {
        return checkProjectPatchOwnerIdentities(project) + checkProjectRoomOwnerIdentities(project)
    }

    fun checkProjectPatchOwnerIdentities(project: SmEditProject): List<Issue> {
        val issues = mutableListOf<Issue>()
        val duplicateEnabledPatchIds = project.patches
            .filter { it.enabled }
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
        for ((id, count) in duplicateEnabledPatchIds) {
            issues.add(Issue(
                Severity.ERROR, "Project Identity", null, "Project",
                "$count enabled patches use ID '$id'. Patch IDs must be unique for safe ownership tracking."
            ))
        }
        return issues
    }

    private fun checkProjectRoomOwnerIdentities(project: SmEditProject): List<Issue> {
        val issues = mutableListOf<Issue>()
        val editedRoomsById = mutableMapOf<Int, MutableList<String>>()
        for ((key, edits) in project.rooms) {
            if (!edits.hasEdits) continue
            val roomId = key.toIntOrNull(16)
            if (roomId == null || roomId !in 0..0xFFFF) {
                issues.add(Issue(
                    Severity.ERROR, "Project Identity", null, "Project",
                    "Edited room key '$key' is not a 16-bit hexadecimal room ID."
                ))
                continue
            }
            if (edits.roomId != roomId) {
                issues.add(Issue(
                    Severity.ERROR, "Project Identity", roomId, "Room 0x${roomId.toString(16).uppercase()}",
                    "Room key '$key' identifies 0x${roomId.toString(16).uppercase()}, but its stored roomId is " +
                        "0x${edits.roomId.toString(16).uppercase()}."
                ))
            }
            editedRoomsById.getOrPut(roomId) { mutableListOf() }.add(key)
        }
        for ((roomId, keys) in editedRoomsById.filterValues { it.size > 1 }) {
            issues.add(Issue(
                Severity.ERROR, "Project Identity", roomId, "Room 0x${roomId.toString(16).uppercase()}",
                "Room 0x${roomId.toString(16).uppercase()} has multiple edited keys " +
                    "(${keys.sorted().joinToString()}); merge them before export."
            ))
        }
        return issues
    }

    fun checkProjectRoomHeaders(
        parser: RomParser,
        project: SmEditProject,
        rooms: Map<Int, Room>,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((roomKey, edits) in project.rooms) {
            val roomId = roomKey.toIntOrNull(16) ?: continue
            val room = rooms[roomId] ?: parser.readRoomHeader(roomId) ?: continue
            val header = edits.roomHeaderChange ?: continue
            val area = header.area ?: room.area
            val width = header.width ?: room.width
            val height = header.height ?: room.height
            val mapX = header.mapX ?: room.mapX
            val mapY = header.mapY ?: room.mapY
            if (area !in 0 until MinimapData.NUM_AREAS) {
                issues.add(Issue(
                    Severity.ERROR, "Room Header", roomId, room.name,
                    "Room area $area is not writable by the editor; writable pause-map areas are 0-6. " +
                        "Area 7 is the engine's debug/unused slot and has no normal pause map."
                ))
            }
            if (mapX !in 0..(MinimapData.MAP_WIDTH - width).coerceAtLeast(0) ||
                mapY !in 0..(MinimapData.MAP_HEIGHT - height).coerceAtLeast(0)
            ) {
                issues.add(Issue(
                    Severity.ERROR, "Room Header", roomId, room.name,
                    "Map rectangle ($mapX,$mapY ${width}x$height) exceeds the 64x32 area map."
                ))
            }
            if (area != room.area) {
                val areaSaveReference = (0 until MinimapData.NUM_AREAS).any { saveArea ->
                    (0 until parser.saveEntryCount(saveArea)).any { index ->
                        parser.readSaveEntry(saveArea, index)?.roomId == roomId
                    }
                }
                val stationPlm = parser.getAllPlmEntriesForRoom(roomId).any { it.id == 0xB76F } ||
                    edits.plmChanges.any { it.plmId == 0xB76F && it.action == "add" } ||
                    edits.states.any { state ->
                        state.plmChanges.any { it.plmId == 0xB76F && it.action == "add" }
                    }
                val hasAreaSaveDependency = areaSaveReference || stationPlm || edits.saveStationSpawns.isNotEmpty()
                if (hasAreaSaveDependency &&
                    !hasCompleteAreaSaveMigration(parser, project, roomId, room.area, area)
                ) {
                    issues.add(Issue(
                        Severity.ERROR, "AreaSave", roomId, room.name,
                        "Room was reassigned from area ${room.area} to $area but is tied to AreaSave. Migrate and validate its save slot before export."
                    ))
                }
            }
        }
        return issues
    }

    fun checkProjectRoomStates(
        parser: RomParser,
        project: SmEditProject,
        rooms: Map<Int, Room>,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((roomKey, edits) in project.rooms) {
            if (edits.states.isEmpty()) continue
            val roomId = roomKey.toIntOrNull(16) ?: continue
            val room = rooms[roomId] ?: parser.readRoomHeader(roomId) ?: continue
            fun error(message: String) {
                issues += Issue(Severity.ERROR, "Room States", roomId, room.name, message)
            }
            fun warning(message: String) {
                issues += Issue(Severity.WARNING, "Room States", roomId, room.name, message)
            }
            if (edits.states.size > 64) {
                error("The ordered state graph has ${edits.states.size} states; the supported maximum is 64")
            }
            val duplicateIds = edits.states.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
            if (duplicateIds.isNotEmpty()) error("Duplicate state IDs: ${duplicateIds.sorted().joinToString()}")
            val duplicateSources = edits.states.mapNotNull { it.sourceStateIndex }
                .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicateSources.isNotEmpty()) {
                error("Multiple project states refer to source state(s) ${duplicateSources.sorted().joinToString()}")
            }
            val defaults = edits.states.withIndex().filter {
                it.value.condition.kind == com.supermetroid.editor.data.ProjectRoomStateConditionKind.DEFAULT
            }
            if (defaults.size != 1 || defaults.singleOrNull()?.index != edits.states.lastIndex) {
                error("The ordered state graph must contain exactly one default state, last")
            }
            val duplicatePredicates = edits.states
                .filter {
                    it.condition.kind != com.supermetroid.editor.data.ProjectRoomStateConditionKind.DEFAULT &&
                        it.condition.kind != com.supermetroid.editor.data.ProjectRoomStateConditionKind.NEVER
                }
                .groupingBy { it.condition }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            for (condition in duplicatePredicates) {
                warning(
                    "Condition ${condition.kind}${condition.argument?.let { "($it)" }.orEmpty()}" +
                        (if (condition.negated) " (inverted)" else "") + " appears more than once; " +
                        "only its first branch can be selected"
                )
            }
            val inspected = parser.inspectRoomStates(roomId).states
            for (state in edits.states) {
                val canonical = projectRoomStateCondition(
                    state.condition.kind,
                    state.condition.argument,
                    state.condition.negated,
                    state.condition.children,
                )
                if (state.condition.routineCode != canonical.routineCode ||
                    state.condition.argumentKind != canonical.argumentKind
                ) {
                    error("State '${state.id}' has an inconsistent typed selector")
                }
                when (state.condition.argumentKind) {
                    com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.NONE -> Unit
                    com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.EVENT_ID,
                    com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.BOSS_BIT_MASK -> {
                        val argument = state.condition.argument
                        if (argument == null || argument !in 0..0xFF) {
                            error("State '${state.id}' selector argument must fit in one byte")
                        }
                    }
                    com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.DOOR_POINTER -> {
                        val argument = state.condition.argument
                        if (argument == null || argument !in 0x8000..0xFFFF) {
                            error("State '${state.id}' incoming-door argument must be a bank \$83 pointer")
                        }
                    }
                    com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.EQUIPMENT_MASK,
                    com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.BEAM_MASK -> {
                        val argument = state.condition.argument
                        if (argument == null || argument !in 1..0xFFFF || argument.countOneBits() != 1) {
                            error("State '${state.id}' equipment/beam selector must contain one bit")
                        }
                    }
                    com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.CAPACITY -> {
                        if (state.condition.argument == null || state.condition.argument !in 0..0xFFFF) {
                            error("State '${state.id}' capacity must fit in one word")
                        }
                    }
                    com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.ITEM_BIT_INDEX -> {
                        if (state.condition.argument == null || state.condition.argument !in 0..0x1FF) {
                            error("State '${state.id}' item pickup ID must be 0-511")
                        }
                    }
                    com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.AREA_AND_BOSS_MASK -> {
                        val argument = state.condition.argument ?: -1
                        val area = (argument ushr 8) and 0xFF
                        val mask = argument and 0xFF
                        if (argument !in 0..0xFFFF || area !in 0..7 ||
                            mask !in RoomStateCondition.BOSS_FLAG_MASKS
                        ) {
                            error("State '${state.id}' boss selector is invalid")
                        }
                    }
                    com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.DOOR_BIT_INDEX,
                    com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.CHOZO_BLOCK_BIT_INDEX -> {
                        if (state.condition.argument == null || state.condition.argument !in 0..0x1FF) {
                            error("State '${state.id}' persistent bit index must be 0-511")
                        }
                    }
                    com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.CHILDREN -> {
                        if (!SmEditCompiledRoomStateConditionFormat.conditionTreeIsValid(state.condition)) {
                            error("State '${state.id}' has an invalid compound condition tree")
                        }
                    }
                }
                val sourceIndex = state.baseSourceStateIndex()
                if (sourceIndex == null) {
                    error("State '${state.id}' has no source/template state")
                    continue
                }
                if (state.sourceStateIndex == null && !edits.stateGraphChanged) {
                    error("State '${state.id}' is new, but its state graph is not marked for rebuilding")
                }
                val source = inspected.getOrNull(sourceIndex)
                if (source == null) {
                    error("State '${state.id}' refers to missing source/template state $sourceIndex")
                    continue
                }
                if (state.sourceStateIndex != null) {
                    val sourceCondition = state.sourceCondition ?: state.condition.takeUnless { state.conditionChanged }
                    fun semanticMatch(
                        runtime: RoomStateCondition,
                        project: com.supermetroid.editor.data.ProjectRoomStateCondition,
                    ): Boolean = runtime.kind.name == project.kind.name &&
                        runtime.argument == project.argument && runtime.negated == project.negated &&
                        runtime.children.size == project.children.size &&
                        runtime.children.zip(project.children).all { (runtimeChild, projectChild) ->
                            semanticMatch(runtimeChild, projectChild)
                        }
                    val matchesSource = sourceCondition != null && if (sourceCondition.usesSmEditRuntime()) {
                        semanticMatch(source.condition, sourceCondition)
                    } else {
                        source.condition.code == sourceCondition.routineCode &&
                            source.condition.argument == sourceCondition.argument
                    }
                    if (!matchesSource) {
                        error("State '${state.id}' no longer matches source state $sourceIndex")
                    }
                }
                if (!edits.stateGraphChanged && state.conditionChanged &&
                    source.condition.entrySizeBytes != state.condition.encodedSizeBytes()
                ) {
                    error("State '${state.id}' condition changes encoded size and requires graph relocation")
                }
                val links = state.resources
                if (listOf(
                        links.level, links.effects, links.enemies, links.enemyGraphics,
                        links.scrolling, links.placedObjects, links.background, links.specialXray,
                    ).any { it.isBlank() }
                ) {
                    error("State '${state.id}' contains an empty resource identity")
                }
                if (state.doorFxChanges.isNotEmpty()) {
                    val fxDoors = source.stateDataPcOffset?.let(parser::readStateData)
                        ?.get("fxPtr")
                        ?.let(parser::parseFxEntries)
                        .orEmpty()
                        .map { it.doorSelect }
                        .toSet()
                    for (doorKey in state.doorFxChanges.keys) {
                        val doorSelect = doorKey.toIntOrNull(16)
                        when {
                            doorSelect == null || doorSelect !in 1..0xFFFF ->
                                error("State '${state.id}' has invalid door FX key '$doorKey'")
                            doorSelect !in fxDoors ->
                                error(
                                    "State '${state.id}' has no FX entry for door " +
                                        "0x${doorSelect.toString(16).uppercase().padStart(4, '0')}"
                                )
                        }
                    }
                }
            }
        }
        return issues
    }

    fun checkProjectMinimapEdits(project: SmEditProject): List<Issue> {
        val issues = mutableListOf<Issue>()

        fun validateAreaAndCoordinates(
            areaKey: String,
            coordinates: List<Pair<Int, Int>>,
            label: String,
        ) {
            val area = areaKey.toIntOrNull()
            if (area == null || area !in 0 until MinimapData.NUM_AREAS) {
                issues.add(Issue(
                    Severity.ERROR, "Minimap", null, "Project",
                    "$label area key '$areaKey' is invalid; expected 0-6."
                ))
                return
            }
            val areaName = MinimapData.AREA_NAMES[area]
            for ((x, y) in coordinates) {
                if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) {
                    issues.add(Issue(
                        Severity.ERROR, "Minimap", null, areaName,
                        "$label coordinate ($x,$y) is outside the 64x32 area map."
                    ))
                }
            }
            val duplicates = coordinates.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            for ((x, y) in duplicates) {
                issues.add(Issue(
                    Severity.WARNING, "Minimap", null, areaName,
                    "$label contains multiple edits for ($x,$y); only the last value will be exported."
                ))
            }
        }

        for ((areaKey, edits) in project.minimapEdits) {
            validateAreaAndCoordinates(areaKey, edits.map { it.x to it.y }, "Minimap")
            val areaName = areaKey.toIntOrNull()
                ?.takeIf { it in 0 until MinimapData.NUM_AREAS }
                ?.let { MinimapData.AREA_NAMES[it] }
                ?: "Project"
            for (edit in edits) {
                if (edit.tileWord !in 0..0xFFFF) {
                    issues.add(Issue(
                        Severity.ERROR, "Minimap", null, areaName,
                        "Tile word ${edit.tileWord} at (${edit.x},${edit.y}) is outside 0x0000-0xFFFF."
                    ))
                }
            }
        }
        for ((areaKey, edits) in project.mapStationEdits) {
            validateAreaAndCoordinates(areaKey, edits.map { it.x to it.y }, "Map-station reveal")
        }
        return issues
    }

    /**
     * Verify all doors point to valid destination rooms and have coordinates
     * within the destination room's dimensions.
     */
    fun checkDoorConsistency(parser: RomParser, rooms: Map<Int, Room>): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((roomId, room) in rooms) {
            val doors = parser.parseDoorList(room.doorOut)
            for ((idx, door) in doors.withIndex()) {
                val destRoom = rooms[door.destRoomPtr]
                val label = "Door #$idx (${door.directionName})"

                if (destRoom == null) {
                    // Destination room not in our known room list — could be valid but unknown
                    val destHex = "0x${door.destRoomPtr.toString(16).uppercase()}"
                    issues.add(Issue(
                        Severity.WARNING, "Doors", roomId, room.name,
                        "$label → destination room $destHex not found in ROM"
                    ))
                    continue
                }

                // Check spawn coordinates against destination room dimensions
                val maxScreenX = destRoom.width - 1
                val maxScreenY = destRoom.height - 1
                if (door.screenX > maxScreenX) {
                    issues.add(Issue(
                        Severity.ERROR, "Doors", roomId, room.name,
                        "$label → screenX=${door.screenX} exceeds ${destRoom.name} width (${destRoom.width} screens, max X=$maxScreenX)"
                    ))
                }
                if (door.screenY > maxScreenY) {
                    issues.add(Issue(
                        Severity.ERROR, "Doors", roomId, room.name,
                        "$label → screenY=${door.screenY} exceeds ${destRoom.name} height (${destRoom.height} screens, max Y=$maxScreenY)"
                    ))
                }
            }
        }
        return issues
    }

    /**
     * Scan all item PLMs across all rooms for duplicate collection bitflags.
     * Two items sharing the same param means collecting one silently collects the other.
     */
    fun checkItemBitflagDuplicates(parser: RomParser, rooms: Map<Int, Room>): List<Issue> {
        val issues = mutableListOf<Issue>()
        // Map: param → list of (roomId, plmId, itemName)
        data class ItemLocation(val roomId: Int, val roomName: String, val plmId: Int, val itemName: String)
        val paramMap = mutableMapOf<Int, MutableList<ItemLocation>>()

        for ((roomId, room) in rooms) {
            val plms = parser.getAllPlmEntriesForRoom(roomId)
            for (plm in plms) {
                if (!RomParser.isItemPlm(plm.id)) continue
                val itemName = RomParser.itemNameForPlm(plm.id) ?: "Unknown item"
                paramMap.getOrPut(plm.param) { mutableListOf() }
                    .add(ItemLocation(roomId, room.name, plm.id, itemName))
            }
        }

        for ((param, locations) in paramMap) {
            if (locations.size <= 1) continue
            // Multiple items sharing the same collection bit
            val roomNames = locations.map { "${it.itemName} in ${it.roomName}" }.joinToString(", ")
            val paramHex = "0x${param.toString(16).uppercase()}"
            issues.add(Issue(
                Severity.WARNING, "Items", null,
                locations.first().roomName,
                "Collection bit $paramHex shared by ${locations.size} items: $roomNames"
            ))
        }
        return issues
    }

    /**
     * Check that each room's enemy GFX set doesn't exceed the 4-slot hardware limit.
     */
    fun checkEnemyGfxLimits(parser: RomParser, rooms: Map<Int, Room>): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((roomId, room) in rooms) {
            val gfxEntries = parser.parseEnemyGfxSet(room.enemyGfxPtr)
            if (gfxEntries.size > 4) {
                issues.add(Issue(
                    Severity.ERROR, "Enemy GFX", roomId, room.name,
                    "${gfxEntries.size} enemy tileset slots used (SNES hardware max is 4). Excess sprites will be garbled."
                ))
            }
        }
        return issues
    }

    /**
     * Check rooms for suspicious dimensions or map positions.
     */
    fun checkRoomDimensions(rooms: Map<Int, Room>): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((_, room) in rooms) {
            if (room.mapX + room.width > MinimapData.MAP_WIDTH) {
                issues.add(Issue(
                    Severity.WARNING, "Room Header", room.roomId, room.name,
                    "Room extends past minimap right edge: mapX=${room.mapX} + width=${room.width} = ${room.mapX + room.width} > ${MinimapData.MAP_WIDTH}"
                ))
            }
            if (room.mapY + room.height > MinimapData.MAP_HEIGHT) {
                issues.add(Issue(
                    Severity.WARNING, "Room Header", room.roomId, room.name,
                    "Room extends past minimap bottom edge: mapY=${room.mapY} + height=${room.height} = ${room.mapY + room.height} > ${MinimapData.MAP_HEIGHT}"
                ))
            }
        }
        return issues
    }

    /**
     * Check raw PLM sets for terminators, count limits, and coordinates outside
     * room dimensions. This catches corrupt PLM tables before export/playtest.
     */
    fun checkPlmSets(parser: RomParser, rooms: Map<Int, Room>): List<Issue> {
        val issues = mutableListOf<Issue>()
        val rom = parser.getRomData()
        for ((roomId, room) in rooms) {
            if (room.plmSetPtr == 0 || room.plmSetPtr == 0xFFFF) continue
            val pc = parser.snesToPc(RomConstants.BANK_ROOM_DATA or room.plmSetPtr)
            if (pc < 0 || pc >= rom.size) {
                issues.add(Issue(
                    Severity.ERROR, "PLMs", roomId, room.name,
                    "PLM set pointer 0x${room.plmSetPtr.toString(16).uppercase()} resolves outside ROM bounds."
                ))
                continue
            }

            val maxEntries = 256
            var cursor = pc
            var count = 0
            var terminated = false
            while (cursor + 1 < rom.size && count < maxEntries) {
                val id = readU16(rom, cursor)
                if (id == 0) {
                    terminated = true
                    break
                }
                if (cursor + 5 >= rom.size) break
                val x = rom[cursor + 2].toInt() and 0xFF
                val y = rom[cursor + 3].toInt() and 0xFF
                if (x >= room.width * 16 || y >= room.height * 16) {
                    issues.add(Issue(
                        Severity.WARNING, "PLMs", roomId, room.name,
                        "PLM 0x${id.toString(16).uppercase()} at ($x,$y) is outside room bounds ${room.width * 16}x${room.height * 16}."
                    ))
                }
                cursor += 6
                count++
            }
            if (!terminated) {
                issues.add(Issue(
                    Severity.ERROR, "PLMs", roomId, room.name,
                    "PLM set at 0x${room.plmSetPtr.toString(16).uppercase()} has no terminator within $maxEntries entries."
                ))
            }
        }
        return issues
    }

    /**
     * Project overrides for save stations patch AreaSave table entries directly.
     * Duplicate area/index pairs or missing table slots make export ambiguous.
     */
    fun checkProjectSaveStationSpawns(
        parser: RomParser,
        project: SmEditProject,
        rooms: Map<Int, Room>,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()
        val bySlot = mutableMapOf<Pair<Int, Int>, MutableList<Pair<Int, String>>>()
        for ((roomKey, roomEdits) in project.rooms) {
            val sourceRoomId = roomKey.toIntOrNull(16)
            val sourceRoomName = sourceRoomId?.let { rooms[it]?.name } ?: "Room $roomKey"
            for (spawn in roomEdits.saveStationSpawns) {
                val slot = spawn.area to spawn.saveIndex
                bySlot.getOrPut(slot) { mutableListOf() }.add((sourceRoomId ?: spawn.roomId) to sourceRoomName)

                if (spawn.area !in 0..7) {
                    issues.add(Issue(
                        Severity.ERROR, "AreaSave", sourceRoomId, sourceRoomName,
                        "Save station override uses invalid area ${spawn.area}; valid areas are 0-7."
                    ))
                    continue
                }

                val count = parser.saveEntryCount(spawn.area)
                val romEntry = parser.readSaveEntry(spawn.area, spawn.saveIndex)
                if (spawn.saveIndex !in 0 until RomParser.SAVE_STATION_SLOT_COUNT) {
                    issues.add(Issue(
                        Severity.ERROR, "AreaSave", sourceRoomId, sourceRoomName,
                        "Save station override area=${spawn.area} index=${spawn.saveIndex} is unreachable; " +
                            "the game masks save PLM indices to 0-${RomParser.SAVE_STATION_SLOT_COUNT - 1}."
                    ))
                } else if (romEntry == null) {
                    issues.add(Issue(
                        Severity.ERROR, "AreaSave", sourceRoomId, sourceRoomName,
                        "Save station override area=${spawn.area} index=${spawn.saveIndex} has no writable AreaSave slot (area has $count entries)."
                    ))
                }

                if (spawn.clearSlot) {
                    if (spawn.roomId != 0) {
                        issues.add(Issue(
                            Severity.ERROR, "AreaSave", sourceRoomId, sourceRoomName,
                            "Cleared AreaSave slot area=${spawn.area} index=${spawn.saveIndex} must use room ID 0."
                        ))
                    }
                    continue
                }

                val targetRoom = rooms[spawn.roomId] ?: parser.readRoomHeader(spawn.roomId)
                if (targetRoom == null) {
                    issues.add(Issue(
                        Severity.ERROR, "AreaSave", sourceRoomId, sourceRoomName,
                        "Save station override points to missing room 0x${spawn.roomId.toString(16).uppercase()}."
                    ))
                } else {
                    val targetArea = project.rooms[project.roomKey(spawn.roomId)]?.roomHeaderChange?.area ?: targetRoom.area
                    if (spawn.area != targetArea) {
                        issues.add(Issue(
                            Severity.ERROR, "AreaSave", sourceRoomId, sourceRoomName,
                            "Save station override uses area ${spawn.area}, but room 0x${spawn.roomId.toString(16).uppercase()} is assigned to area $targetArea."
                        ))
                    }
                }

                if (spawn.doorPtr == 0) {
                    issues.add(Issue(
                        Severity.WARNING, "AreaSave", sourceRoomId, sourceRoomName,
                        "Save station override area=${spawn.area} index=${spawn.saveIndex} has door pointer 0; resume may enter incorrectly."
                    ))
                } else {
                    val incoming = parser.findDoorsLeadingTo(spawn.roomId).any { it.doorDefPtr == spawn.doorPtr }
                    if (!incoming) {
                        issues.add(Issue(
                            Severity.WARNING, "AreaSave", sourceRoomId, sourceRoomName,
                            "Save station override area=${spawn.area} index=${spawn.saveIndex} uses door pointer 0x${spawn.doorPtr.toString(16).uppercase()} that is not an incoming door to room 0x${spawn.roomId.toString(16).uppercase()}."
                        ))
                    }
                }
            }
        }

        for ((slot, entries) in bySlot) {
            if (entries.size <= 1) continue
            val names = entries.joinToString(", ") { (_, name) -> name }
            issues.add(Issue(
                Severity.ERROR, "AreaSave", entries.first().first, entries.first().second,
                "AreaSave slot area=${slot.first} index=${slot.second} is edited by ${entries.size} rooms: $names. Only one exported value can win."
            ))
        }
        return issues
    }

    /**
     * Check custom graphics/metatile/palette payloads for malformed data and
     * conservative in-place export size limits.
     */
    fun checkProjectGraphicsExportFit(parser: RomParser, project: SmEditProject): List<Issue> {
        val issues = mutableListOf<Issue>()
        val gfx = project.customGfx
        validateCompressedPayload(
            issues = issues,
            parser = parser,
            label = "CRE graphics",
            category = "Graphics Export",
            b64 = gfx.creGfx,
            snesAddress = TileGraphics.CRE_GFX_SNES,
            requiredMultiple = RomConstants.BYTES_PER_4BPP_TILE,
            maxSize = TileGraphics.CRE_GFX_MAX_BYTES,
            maxSizeReason = "the engine's 12 KiB CRE graphics destination",
        )
        validateCompressedPayload(
            issues = issues,
            parser = parser,
            label = "CRE metatile table",
            category = "Graphics Export",
            b64 = gfx.creTileTable,
            snesAddress = TileGraphics.CRE_TILE_TABLE_SNES,
            requiredMultiple = 8,
            maxSize = TileGraphics.CRE_TILE_TABLE_MAX_BYTES,
            maxSizeReason = "the engine's 2 KiB CRE metatile-table destination",
        )

        val tablePc = parser.snesToPc(TileGraphics.TILESET_TABLE_SNES)
        val rom = parser.getRomData()
        for ((tilesetIdText, b64) in gfx.varGfx) {
            val tilesetId = tilesetIdText.toIntOrNull()
            val snes = tilesetId?.let { tilesetPointer(rom, tablePc, it, offset = 3) }
            val layoutCapacity = tilesetId?.let { variableGraphicsMaxBytes(parser, it) }
            validateCompressedPayload(
                issues = issues,
                parser = parser,
                label = "Tileset $tilesetIdText area graphics",
                category = "Graphics Export",
                b64 = b64,
                snesAddress = snes,
                requiredMultiple = RomConstants.BYTES_PER_4BPP_TILE,
                maxSize = layoutCapacity,
                maxSizeReason = if (layoutCapacity == TileGraphics.ROOM_GFX_MAX_BYTES) {
                    "the engine's 32 KiB full room-graphics destination"
                } else {
                    "the tileset's 20 KiB area-graphics region (the remaining 12 KiB is reserved for CRE)"
                },
                relocationSupported = true,
            )
        }
        for ((tilesetIdText, b64) in gfx.tileTables) {
            val tilesetId = tilesetIdText.toIntOrNull()
            val snes = tilesetId?.let { tilesetPointer(rom, tablePc, it, offset = 0) }
            validateCompressedPayload(
                issues = issues,
                parser = parser,
                label = "Tileset $tilesetIdText metatile table",
                category = "Graphics Export",
                b64 = b64,
                snesAddress = snes,
                requiredMultiple = 8,
                maxSize = tilesetId?.let { variableTileTableMaxBytes(parser, project, it) },
                maxSizeReason = "the engine's metatile-table work buffer for rooms using this tileset",
                relocationSupported = true,
            )
        }
        for ((tilesetIdText, b64) in gfx.palettes) {
            val tilesetId = tilesetIdText.toIntOrNull()
            val snes = tilesetId?.let { tilesetPointer(rom, tablePc, it, offset = 6) }
            validateCompressedPayload(
                issues = issues,
                parser = parser,
                label = "Tileset $tilesetIdText palette",
                category = "Graphics Export",
                b64 = b64,
                snesAddress = snes,
                exactSize = 256,
                relocationSupported = true,
            )
        }
        for ((key, b64) in gfx.spriteTileBlocks) {
            val block = when {
                key.startsWith("phantoon:") -> key.removePrefix("phantoon:").toIntOrNull()
                    ?.let { EnemySpriteGraphics.PHANTOON_BLOCKS.getOrNull(it) }
                key.startsWith("kraid:") -> key.removePrefix("kraid:").toIntOrNull()
                    ?.let { EnemySpriteGraphics.KRAID_BLOCKS.getOrNull(it) }
                else -> null
            }
            if (block != null) {
                validateCompressedPayload(
                    issues = issues,
                    parser = parser,
                    label = "Sprite block $key",
                    category = "Sprite Export",
                    b64 = b64,
                    snesAddress = block.snesAddress,
                    requiredMultiple = RomConstants.BYTES_PER_4BPP_TILE,
                    limitToOriginalRawSize = true,
                )
            }
        }
        return issues
    }

    /**
     * Match the editor's effective graphics layout for this ROM/tileset. Normal
     * tilesets reserve VRAM byte $5000 onward for CRE; established no-CRE/full
     * layouts expose all 1024 tile slots to area graphics.
     */
    fun variableGraphicsMaxBytes(parser: RomParser, tilesetId: Int): Int {
        if (tilesetId !in 0 until TileGraphics.NUM_TILESETS) {
            return TileGraphics.STANDARD_VAR_GFX_MAX_BYTES
        }
        val graphics = TileGraphics(parser)
        if (!graphics.loadTileset(tilesetId)) {
            return TileGraphics.STANDARD_VAR_GFX_MAX_BYTES
        }
        return graphics.getVarTileCount() * TileGraphics.BYTES_PER_TILE
    }

    fun checkProjectEnemyTileEdits(parser: RomParser, project: SmEditProject): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((key, b64) in project.customGfx.spriteTileBlocks) {
            if (!key.startsWith("enemy:")) continue
            val speciesHex = key.removePrefix("enemy:")
            val speciesId = speciesHex.toIntOrNull(16)
            if (speciesId == null) {
                issues.add(Issue(
                    Severity.ERROR, "Sprite Export", null, "Project",
                    "Enemy tile edit key '$key' has an invalid species id."
                ))
                continue
            }
            val raw = decodeBase64Issue("Enemy $speciesHex tile edit", b64) { issues += it } ?: continue
            val validation = EnemySpriteGraphics.validateEnemyTileEdit(parser, speciesId, raw)
            for (error in validation.errors) {
                issues.add(Issue(
                    Severity.ERROR, "Sprite Export", null, RomParser.enemyName(speciesId),
                    "Enemy $speciesHex tile edit cannot export: $error"
                ))
            }
            for (warning in validation.warnings) {
                issues.add(Issue(
                    Severity.WARNING, "Sprite Export", null, RomParser.enemyName(speciesId),
                    "Enemy $speciesHex tile edit: $warning"
                ))
            }
        }
        return issues
    }

    fun checkProjectSpritePalettes(parser: RomParser, project: SmEditProject): List<Issue> {
        val issues = mutableListOf<Issue>()
        val rom = parser.getRomData()
        for ((key, b64) in project.customGfx.spritePalettes) {
            val raw = decodeBase64Issue("Sprite palette $key", b64) {
                issues += it.copy(category = "Sprite Palettes")
            } ?: continue
            if (key.startsWith("enemy_pal:")) {
                validateEnemyPalette(parser, rom, key, raw, issues)
            } else {
                val region = SpritePalettes.findRegion(key)
                if (region == null) {
                    issues.add(Issue(
                        Severity.ERROR, "Sprite Palettes", null, "Project",
                        "Sprite palette '$key' does not match a known palette region."
                    ))
                    continue
                }
                if (raw.size != region.byteSize) {
                    issues.add(Issue(
                        Severity.ERROR, "Sprite Palettes", null, region.name,
                        "Sprite palette '${region.name}' has ${raw.size} bytes; expected ${region.byteSize} bytes."
                    ))
                }
                if (region.offset < 0 || region.offset + region.byteSize > rom.size) {
                    issues.add(Issue(
                        Severity.ERROR, "Sprite Palettes", null, region.name,
                        "Sprite palette '${region.name}' writes outside ROM bounds at PC 0x${region.offset.toString(16).uppercase()}."
                    ))
                }
            }
        }
        return issues
    }

    private fun validateEnemyPalette(
        parser: RomParser,
        rom: ByteArray,
        key: String,
        raw: ByteArray,
        issues: MutableList<Issue>,
    ) {
        val speciesHex = key.removePrefix("enemy_pal:")
        val speciesId = speciesHex.toIntOrNull(16)
        if (speciesId == null) {
            issues.add(Issue(
                Severity.ERROR, "Sprite Palettes", null, "Project",
                "Enemy palette key '$key' has an invalid species id."
            ))
            return
        }
        if (raw.size != 32) {
            issues.add(Issue(
                Severity.ERROR, "Sprite Palettes", null, RomParser.enemyName(speciesId),
                "Enemy $speciesHex palette has ${raw.size} bytes; expected 32 bytes."
            ))
        }
        val headerPc = parser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        if (headerPc < 0 || headerPc + 0x0D > rom.size) {
            issues.add(Issue(
                Severity.ERROR, "Sprite Palettes", null, RomParser.enemyName(speciesId),
                "Enemy $speciesHex palette cannot export because the species header is outside ROM bounds."
            ))
            return
        }
        val palPtr = readU16(rom, headerPc + 2)
        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF
        val palSnes = (aiBank shl 16) or (palPtr and 0xFFFF)
        val palPc = parser.snesToPc(palSnes)
        if (palPc < 0 || palPc + 32 > rom.size) {
            issues.add(Issue(
                Severity.ERROR, "Sprite Palettes", null, RomParser.enemyName(speciesId),
                "Enemy $speciesHex palette destination 0x${palSnes.toString(16).uppercase()} resolves outside ROM bounds."
            ))
        }
    }

    private fun validateCompressedPayload(
        issues: MutableList<Issue>,
        parser: RomParser,
        label: String,
        category: String,
        b64: String?,
        snesAddress: Int?,
        requiredMultiple: Int? = null,
        exactSize: Int? = null,
        maxSize: Int? = null,
        maxSizeReason: String = "the engine destination",
        limitToOriginalRawSize: Boolean = false,
        relocationSupported: Boolean = false,
    ) {
        if (b64 == null) return
        val raw = decodeBase64Issue(label, b64) { issues += it.copy(category = category) } ?: return
        if (raw.isEmpty()) {
            issues.add(Issue(Severity.ERROR, category, null, "Project", "$label is empty."))
            return
        }
        if (requiredMultiple != null && raw.size % requiredMultiple != 0) {
            issues.add(Issue(
                Severity.ERROR, category, null, "Project",
                "$label has ${raw.size} bytes; expected a multiple of $requiredMultiple."
            ))
        }
        if (exactSize != null && raw.size != exactSize) {
            issues.add(Issue(
                Severity.ERROR, category, null, "Project",
                "$label has ${raw.size} bytes; expected exactly $exactSize bytes."
            ))
        }
        if (maxSize != null && raw.size > maxSize) {
            issues.add(Issue(
                Severity.ERROR, category, null, "Project",
                "$label has ${raw.size} decompressed bytes; $maxSizeReason can hold at most $maxSize bytes."
            ))
        }
        if (snesAddress == null) {
            issues.add(Issue(Severity.ERROR, category, null, "Project", "$label has an invalid ROM pointer."))
            return
        }
        try {
            val compressed = LZ5Compressor.compress(raw)
            val (originalRaw, originalSize) = parser.decompressLZ2WithSize(snesAddress)
            if (limitToOriginalRawSize && raw.size > originalRaw.size) {
                issues.add(Issue(
                    Severity.ERROR, category, null, "Project",
                    "$label has ${raw.size} decompressed bytes; its fixed sprite DMA region can hold at most " +
                        "${originalRaw.size} bytes."
                ))
            }
            if (compressed.size > originalSize && !relocationSupported) {
                issues.add(Issue(
                    Severity.ERROR, category, null, "Project",
                    "$label compresses to ${compressed.size} bytes but its fixed engine allocation is " +
                        "$originalSize bytes and safe relocation is not supported."
                ))
            }
        } catch (e: Exception) {
            issues.add(Issue(
                Severity.ERROR, category, null, "Project",
                "$label could not be compression-validated: ${e.message ?: e::class.simpleName}"
            ))
        }
    }

    /**
     * Non-Ceres rooms decompress the variable table at $7E:A800 (6 KiB
     * available). Area 6 skips the separate CRE table and starts at $7E:A000
     * (8 KiB available). Permit the larger form only when every current room
     * using the tileset has the Ceres destination, including project header and
     * state overrides.
     */
    fun variableTileTableMaxBytes(
        parser: RomParser,
        project: SmEditProject,
        tilesetId: Int,
    ): Int {
        var usedByCeresRoom = false
        for (metadata in RoomRepository().getAllRooms()) {
            val roomId = metadata.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: continue
            val edits = project.rooms[project.roomKey(roomId)]
            val effectiveArea = edits?.roomHeaderChange?.area ?: room.area
            val overrideTileset = edits?.stateDataChange?.tileset
            val stateOverrideTilesets = edits?.states.orEmpty().mapNotNull { it.stateDataChange?.tileset }
            val stateOffsets = parser.findAllStateDataOffsets(roomId)
            val usesTileset = if (tilesetId in stateOverrideTilesets) {
                true
            } else if (overrideTileset != null) {
                overrideTileset == tilesetId
            } else {
                stateOffsets.any { offset ->
                    offset + 3 in parser.getRomData().indices && parser.readByteAt(offset + 3) == tilesetId
                }
            }
            if (!usesTileset) continue
            if (effectiveArea != TileGraphics.CERES_AREA) {
                return TileGraphics.STANDARD_VAR_TILE_TABLE_MAX_BYTES
            }
            usedByCeresRoom = true
        }
        return if (usedByCeresRoom) {
            TileGraphics.CERES_VAR_TILE_TABLE_MAX_BYTES
        } else {
            // An unused table cannot currently overflow a room destination, but
            // retaining the standard bound makes later reassignment safe too.
            TileGraphics.STANDARD_VAR_TILE_TABLE_MAX_BYTES
        }
    }

    private fun decodeBase64Issue(label: String, b64: String, addIssue: (Issue) -> Unit): ByteArray? =
        try {
            Base64.decode(b64)
        } catch (e: Exception) {
            addIssue(Issue(
                Severity.ERROR, "Project Data", null, "Project",
                "$label contains invalid base64: ${e.message ?: e::class.simpleName}"
            ))
            null
        }

    private fun tilesetPointer(rom: ByteArray, tablePc: Int, tilesetId: Int, offset: Int): Int? {
        val entryOffset = tablePc + tilesetId * 9 + offset
        if (tilesetId !in 0 until TileGraphics.NUM_TILESETS || entryOffset + 2 >= rom.size) return null
        return readU24(rom, entryOffset)
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU24(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
}

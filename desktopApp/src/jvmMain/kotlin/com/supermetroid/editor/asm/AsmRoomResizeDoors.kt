package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*

internal data class AsmResizeIncomingDoor(val roomId: Int, val door: SourceRoomDoor)
internal data class AsmResizeDoorPlan(
    val transaction: AsmRoomAreaMovePlan,
    val incomingBefore: List<AsmResizeIncomingDoor>,
    val incomingAfter: List<AsmResizeIncomingDoor>,
)

/** Coordinate stable DoorDefs and proven scroll-only arrivals with a room's row stride.
 * Native routines are copied, never patched in place; opaque/custom code stays untouched. */
internal class AsmRoomResizeDoors(
    private val catalog: AsmPlmSourceCatalog,
    private val workspace: AsmWorkspaceState,
    private val moves: AsmRoomAreaMove,
    private val context: AsmRoomSourceContext? = null,
    private val doorBridge: AsmDoorSourceBridge,
) {
    fun incoming(owner: SmEditProject, roomId: Int, text8F: String, includeTargetSource: Boolean = true): List<AsmResizeIncomingDoor> {
        val parser = if (owner.newRooms.isEmpty()) catalog.parser else requireNotNull(context) { "Project-room resize requires its verified source context." }.parser
        val source83 by lazy { AsmDoorSource(workspace.projectSourceText(DOOR_SOURCE_FILE), text8F, catalog.symbols, AsmProjectRoomLabels.identities(owner), owner.newRooms) }
        val sharedPointers = nativeDoorConsumers(parser).filterValues { it.map { pair -> pair.first }.distinct().size > 1 }.keys
        val shared = if (sharedPointers.isEmpty()) emptyMap() else source83.sharedResources(parser)
        fun dimensions(id: Int) = parser.readRoomHeader(id)?.let { native ->
            val edits = owner.rooms[owner.roomKey(id)]
            (edits?.roomHeaderChange?.width ?: native.width) to (edits?.roomHeaderChange?.height ?: native.height)
        }
        val linked = owner.rooms.values.filter { it.asmDoorSourceVersion != 0 && (includeTargetSource || it.roomId != roomId) }
            .flatMap { source83.resource(it, parser, ::dimensions).entries.map { entry -> entry.change.destRoomPtr } }
        val ids = (parser.roomCatalog.rooms.map { it.getRoomIdAsInt() } + owner.rooms.values.map { it.roomId } + linked).distinct()
        return ids.filter { includeTargetSource || it != roomId }.flatMap { id ->
            val native = parser.readRoomHeader(id) ?: return@flatMap emptyList()
            val edits = owner.rooms[owner.roomKey(id)]
            val entries = if (edits != null && edits.asmDoorSourceVersion != 0) source83.resource(edits, parser, ::dimensions).entries else {
                val changes = edits?.doorChanges.orEmpty().associateBy { it.doorIndex }
                val pointer = edits?.roomHeaderChange?.doorOut ?: native.doorOut
                val doors = if (pointer in 0x8000..0xFFFE) parser.parseDoorList(pointer) else emptyList()
                require(changes.keys.all { it in doors.indices }) { "${native.name}: a GUI door override has no native connection." }
                doors.mapIndexedNotNull { index, door ->
                    val canonical = shared[door.doorDefPtr]
                    require(canonical == null || changes[index] == null) { "A shared DoorDef also has a competing GUI override." }
                    val change = canonical?.change?.copy(doorIndex = index) ?: changes[index] ?: DoorChange(index, door.destRoomPtr, door.bitflag, door.doorCapCode,
                        door.screenX, door.screenY, door.distFromDoor, door.entryCode)
                    if (change.destRoomPtr != roomId) null else SourceRoomDoor(door.doorDefPtr, change, canonical?.arrival,
                        shared = door.doorDefPtr in sharedPointers)
                }
            }
            entries.filter { it.change.destRoomPtr == roomId }.map { AsmResizeIncomingDoor(id, it) }
        }
    }

    fun plan(owner: SmEditProject, target: RoomEdits, before8F: String, staged8F: String,
             oldWidth: Int, oldHeight: Int, width: Int, height: Int,
             stagedRooms: Map<String, RoomEdits> = emptyMap(), staged83: String? = null,
             restoreNative: Boolean = false): AsmResizeDoorPlan {
        val parser = if (owner.newRooms.isEmpty()) catalog.parser else requireNotNull(context) { "Project-room resize requires its verified source context." }.parser
        val incoming = incoming(owner, target.roomId, before8F, includeTargetSource = !restoreNative)
        val nativeTarget = requireNotNull(parser.readRoomHeader(target.roomId))
        val models = linkedMapOf<String, RoomEdits>().apply { putAll(stagedRooms); put(owner.roomKey(target.roomId), target) }
        fun room(id: Int) = models.getOrPut(owner.roomKey(id)) {
            snapshotRoomForResize(owner.rooms[owner.roomKey(id)] ?: RoomEdits(id)).also { it.ensureStateManifest(parser) }
        }
        var text8F = staged8F
        var before83: String? = null
        var text83: String? = staged83
        fun bank83(): String {
            if (before83 == null) before83 = workspace.projectSourceText(DOOR_SOURCE_FILE)
            if (text83 == null) text83 = before83
            return requireNotNull(text83)
        }
        val otherTexts by lazy { workspace.projectSourceReadersExcept(setOf(PLM_SOURCE_FILE, DOOR_SOURCE_FILE)).map { it() } }
        val reference83 by lazy { staged83 ?: workspace.projectSourceText(DOOR_SOURCE_FILE) }
        val changed = mutableSetOf<String>()
        val plannedBindings = mutableMapOf<Pair<Int, Int>, String>()
        val sharedBindings = mutableMapOf<Int, String>()
        fun append(text: String, addition: String): String {
            val newline = if (text.contains("\r\n")) "\r\n" else "\n"
            return text + newline + addition.replace("\r\n", "\n").replace("\n", newline)
        }
        for (input in incoming) {
            val door = input.door
            val d = door.change
            val source = requireNotNull(parser.readRoomHeader(input.roomId))
            val description = "${source.name}, door ${d.doorIndex + 1}"
            fun check(ok: Boolean, message: String) { require(ok) { "$description: $message" } }
            check(d.screenX < width && d.screenY < height, "An incoming door leads to a removed screen. Move its destination before shrinking.")
            if (d.bitflag ushr 8 >= 4) check((d.doorCapCode and 255) < width * 16 && (d.doorCapCode ushr 8) < height * 16,
                "The closing-door cap would be outside the resized room. Move it before shrinking.")
            sharedBindings[door.doorDefPtr]?.takeIf { door.shared }?.let { binding ->
                plannedBindings[input.roomId to d.doorIndex] = binding
            }
            if (door.shared && door.doorDefPtr in sharedBindings) continue
            if ((owner.rooms[owner.roomKey(input.roomId)]?.asmDoorSourceVersion ?: 0) != 0) bank83() // Unchanged participating authority is an undo anchor too.
            val label = door.arrival?.label
            val managed = label?.startsWith("SmeditArrival_") == true
            var body: String? = null
            var macroRange: IntRange? = null
            var parsed: AsmArrivalScrollProgram? = null
            if (managed) {
                AsmDoorArrivalsSource(before8F).routine(requireNotNull(label))
                val match = requireNotNull(Regex("(?m)^macro ${Regex.escape(label.removeSuffix("_Entry"))}\\(\\)[^\\r\\n]*\\r?\\n([\\s\\S]*?)^endmacro[^\\r\\n]*(?:\\r?\\n|$)").find(before8F))
                body = match.groupValues[1]; macroRange = match.range
                parsed = AsmArrivalScrollProgram.parse(body)
            }
            val nativeLabel = if (parsed?.delegatesToNative == true) parsed.nativeContext else if (!managed)
                label ?: catalog.symbols.entries.filter { it.key.startsWith("DoorASM_") && it.value == d.entryCode }.minByOrNull { it.key }?.key
            else null
            val pointer = if (nativeLabel != null) catalog.symbols[nativeLabel] else d.entryCode
            val noArrival = !managed && d.entryCode == 0 && label == null
            if (noArrival) continue
            val nativeProgram = if (!managed || parsed?.delegatesToNative == true) {
                val analysis = analyzeDoorScrollAsm(parser, pointer ?: 0)
                // Never infer "no scrolls" merely because decoding stopped at an unknown opcode.
                val sourceProof = nativeLabel?.let { verifiedNativeProgram(before8F, it, analysis.writes) }
                if (analysis.decodedCompletely && analysis.terminatedWithRts && !analysis.hasOtherEffects && sourceProof != null) sourceProof else null
            } else null
            val program = if (managed && parsed?.delegatesToNative == false) parsed else nativeProgram
            if (program == null) {
                check(width == oldWidth && height >= oldHeight,
                    "Arrival ASM ${label ?: nativeLabel ?: d.entryCode.toString(16)} mixes scroll writes with other behavior or cannot be proven scroll-only. " +
                        "It stays managed in ASM; review its geometry before changing width or shrinking.")
                continue
            }
            val stride = if (managed && parsed?.delegatesToNative == false) oldWidth else nativeTarget.width
            val writes = program.writes.map { write ->
                val x = write.screenIndex % stride; val y = write.screenIndex / stride
                check(x < oldWidth && y < oldHeight && x < width && y < height,
                    "Incoming door ASM writes a removed screen. Update that routine before shrinking.")
                write.copy(addressLowByte = 0x20 + y * width + x)
            }
            if (writes == program.writes) continue
            val canonicalName = AsmDoorArrivalsSource.name(input.roomId, door.identity, AsmProjectRoomLabels.identities(owner)[input.roomId])
            val currentName = label?.removeSuffix("_Entry")
            var name = currentName ?: canonicalName
            if (managed) {
                val reference = Regex("\\b${Regex.escape(requireNotNull(label))}(?:_End)?\\b")
                fun count(text: String) = reference.findAll(text.lineSequence().joinToString("\n") { asmCodeBeforeComment(it) }).count()
                val references = count(staged8F) + count(reference83) + otherTexts.sumOf(::count)
                val consumers = incoming.filter { it.door.arrival?.label == label }
                    .map { if (it.door.shared) "shared:${it.door.doorDefPtr}" else "${it.roomId}:${it.door.identity}" }.distinct().size
                if (references != consumers) {
                    check(currentName != canonicalName, "Arrival $label is shared outside this resize. Give this door a private arrival before changing its geometry.")
                    name = canonicalName // Copy-on-write; the old routine and its other references survive.
                }
            }
            val replacementLabel = "${name}_Entry"
            if (name !in changed) {
                val notes = body?.lineSequence()?.mapNotNull { line -> line.indexOf(';').takeIf { it >= 0 }?.let { line.substring(it) } }?.toList().orEmpty()
                val rendered = AsmArrivalScrollProgram.render(name, writes,
                    if (managed && parsed?.delegatesToNative == false) parsed.nativeContext else nativeLabel, notes,
                    program.finalAccumulator, program.highByteKnown)
                if (name == currentName) {
                    val current = requireNotNull(macroRange)
                    // Work from the current text's declaration, not stale offsets after another routine grew.
                    val declaration = before8F.substring(current)
                    check(text8F.indexOf(declaration) >= 0, "Arrival source changed while resizing; try again.")
                    text8F = text8F.replaceFirst(declaration, rendered.replace("\n", if (declaration.contains("\r\n")) "\r\n" else "\n"))
                } else {
                    check(!Regex("(?im)^macro[ \\t]+${Regex.escape(name)}\\b").containsMatchIn(text8F), "Retained arrival $name needs review before resizing.")
                    text8F = append(text8F, rendered)
                }
                changed.add(name)
            }
            val staged = room(input.roomId)
            if (staged.asmDoorSourceVersion == 0) {
                check((staged.roomHeaderChange?.doorOut ?: source.doorOut) == source.doorOut,
                    "A relocated door list needs a structural connection transaction before adopting its arrival.")
                check(!Regex("(?im)^[ \\t]*macro[ \\t]+${AsmDoorSource.prefix(input.roomId, AsmProjectRoomLabels.identities(owner)[input.roomId])}").containsMatchIn(bank83()), "Retained DoorDefs need review before resizing.")
                text83 = doorBridge.automaticSource(staged, source.name, bank83(), text8F)
                staged.doorChanges.clear(); staged.asmDoorSourceVersion = 1
            }
            text83 = AsmDoorSource(bank83(), text8F, catalog.symbols, AsmProjectRoomLabels.identities(owner), owner.newRooms).withArrival(input.roomId, d.doorIndex, replacementLabel)
            plannedBindings[input.roomId to d.doorIndex] = replacementLabel
            if (door.shared) sharedBindings[door.doorDefPtr] = replacementLabel
        }
        val prospective = owner.copy(rooms = owner.rooms.toMutableMap().apply { putAll(models) })
        // Validate against final dimensions and complete resource coverage before any adoption is published.
        fun dimensions(id: Int) = parser.readRoomHeader(id)?.let { header ->
            val edits = prospective.rooms[owner.roomKey(id)]
            (edits?.roomHeaderChange?.width ?: header.width) to (edits?.roomHeaderChange?.height ?: header.height)
        }
        for (model in models.values.filter { it.asmDoorSourceVersion != 0 }) {
            val value = AsmDoorSource(bank83(), text8F, catalog.symbols, AsmProjectRoomLabels.identities(owner), owner.newRooms).resource(model, parser, ::dimensions)
            validateSourceDoorOwnership(model.roomId, value, parser)
            model.asmDoorProjection = value.entries.map { it.change }
        }
        val replacements = linkedMapOf(PLM_SOURCE_FILE to (before8F to text8F))
        if (before83 != null) replacements[DOOR_SOURCE_FILE] = requireNotNull(before83) to requireNotNull(text83)
        val after = incoming.map { input -> plannedBindings[input.roomId to input.door.change.doorIndex]?.let { binding ->
            input.copy(door = input.door.copy(change = input.door.change.copy(entryCode = 0), arrival = SourceDoorArrival(binding)))
        } ?: input }
        val changes = models.mapValues { (key, model) ->
            val original = owner.rooms[key]
            AsmAreaRoomChange(original ?: RoomEdits(model.roomId), original?.let(::snapshotRoomForResize), model)
        }
        return AsmResizeDoorPlan(AsmRoomAreaMovePlan(owner, changes, replacements,
            owner.minimapEdits.mapValues { it.value.toList() }, owner.minimapEdits.mapValues { it.value.toList() },
            owner.mapStationEdits.mapValues { it.value.toList() }, owner.mapStationEdits.mapValues { it.value.toList() },
            moves.dependencies(owner), moves.dependencies(prospective), null), incoming, after)
    }

    private fun verifiedNativeProgram(text: String, label: String, expected: List<DoorScrollWrite>): AsmArrivalScrollProgram? {
        fun body(source: String): String? {
            val matches = Regex("(?m)^${Regex.escape(label)}:[^\\r\\n]*(?:\\r?\\n|$)").findAll(source).toList()
            if (matches.size != 1) return null
            val lines = source.substring(matches.single().range.last + 1).lineSequence().take(100).toList()
            val end = lines.indexOfFirst { asmCodeBeforeComment(it).trim().equals("rts", true) }
            return if (end < 0) null else lines.take(end + 1).joinToString("\n")
        }
        val original = body(catalog.originalText) ?: return null
        val current = body(text) ?: return null
        if (AsmArrivalScrollProgram.code(original) != AsmArrivalScrollProgram.code(current)) return null
        return AsmArrivalScrollProgram.parse(current, allowContext = false)?.takeIf { it.writes == expected && !it.delegatesToNative }
    }
}

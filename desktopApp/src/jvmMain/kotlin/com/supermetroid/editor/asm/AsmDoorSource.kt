package com.supermetroid.editor.asm

import com.supermetroid.editor.data.DoorChange
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.ProjectNewRoom
import com.supermetroid.editor.rom.*

internal const val DOOR_SOURCE_FILE = FX_SOURCE_FILE
internal const val RELOCATABLE_DOOR_MARKER = "; @smedit-relocatable-door"

/** A twelve-byte DoorDef lens. Executable arrival code is a separate source resource. */
internal class AsmDoorSource(val text: String, private val arrivalSource: String = "", private val nativeSymbols: Map<String, Int> = emptyMap(),
                             private val projectRoomIds: Map<Int, String> = emptyMap(),
                             private val projectRooms: List<ProjectNewRoom> = emptyList()) {
    private fun prefix(roomId: Int) = Companion.prefix(roomId, projectRoomIds[roomId])
    private fun name(roomId: Int, identity: Int) = Companion.name(roomId, identity, projectRoomIds[roomId])
    private fun listName(roomId: Int) = Companion.listName(roomId, projectRoomIds[roomId])
    private fun renderOrder(roomId: Int, ids: List<Int>) = Companion.renderOrder(roomId, ids, projectRoomIds[roomId])
    private data class Field(val range: IntRange, val width: Int, val value: Int?, val symbol: String? = null, val destination: String? = null)
    private fun validateArrival(label: String) {
        if (label.startsWith("SmeditArrival_")) AsmDoorArrivalsSource(arrivalSource).routine(label, validateBody = false)
        else require(label.startsWith("DoorASM_") && label in nativeSymbols) { "Unknown arrival label $label; use a managed arrival _Entry symbol or a verified native DoorASM label." }
    }
    private fun fields(name: String, visiting: Set<String> = emptySet()): List<Field> {
        require(name !in visiting && visiting.size < 16) { "Cyclic/deep shared DoorDef alias." }
        val declarations = Regex("(?m)^macro ${Regex.escape(name)}\\(\\)[ \\t]*(?:;[^\\r\\n]*)?\\r?\\n([\\s\\S]*?)^endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)").findAll(text).toList()
        require(declarations.size == 1) { "Expected exactly one door declaration $name." }
        val body = declarations.single().groups[1]!!
        val statements = body.value.lineSequence().map { asmCodeBeforeComment(it).trim() }.filter { it.isNotEmpty() }.toList()
        val alias = statements.singleOrNull()?.let { Regex("%(SmeditSharedDoors_[0-9A-F]{4})\\(\\)").matchEntire(it)?.groupValues?.get(1) }
        if (alias != null) return fields(alias, visiting + name)
        var offset = body.range.first
        return buildList {
            for (line in body.value.split('\n')) {
                val code = asmCodeBeforeComment(line).trimEnd('\r')
                if (code.isNotBlank()) {
                    val directive = requireNotNull(Regex("^[ \\t]*(db|dw)[ \\t]+", RegexOption.IGNORE_CASE).find(code)) { "$name: expected literal db/dw fields." }
                    val width = if (directive.groupValues[1].equals("dw", true)) 2 else 1
                    var start = directive.range.last + 1
                    for (token in code.substring(start).split(',')) {
                        val literal = token.trim()
                        val first = start + token.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                        val value = AsmPlmDocument.literal(literal)
                        val destination = if (value == null && isEmpty() && width == 2) AsmProjectRoomLabels.identity(literal) else null
                        if (destination != null && literal == AsmProjectRoomLabels.entry(destination)) {
                            val roomId = requireNotNull(projectRoomIds.entries.singleOrNull { it.value == destination }?.key) { "Destination project room $destination is missing or ambiguous." }
                            add(Field(offset + first until offset + first + literal.length, width, roomId, destination = destination))
                            start += token.length + 1
                            continue
                        }
                        if (value == null && size == 8 && width == 2 && literal.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                            validateArrival(literal)
                            add(Field(offset + first until offset + first + literal.length, width, null, literal))
                            start += token.length + 1
                            continue
                        }
                        require(value != null && value in 0..if (width == 1) 255 else 65535) { "$name: '$literal' is not a ${width * 8}-bit literal." }
                        add(Field(offset + first until offset + first + literal.length, width, value))
                        start += token.length + 1
                    }
                }
                offset += line.length + 1
            }
        }
    }
    private fun sharedPointer(name: String): Int? {
        val code = authoredMacroBody(text, name).lineSequence().map { asmCodeBeforeComment(it).trim() }.filter { it.isNotEmpty() }.toList()
        return code.singleOrNull()?.let { Regex("%SmeditSharedDoors_([0-9A-F]{4})\\(\\)").matchEntire(it)?.groupValues?.get(1)?.toInt(16) }
    }
    fun sharedResources(parser: RomParser): Map<Int, SourceRoomDoor> {
        val consumers = nativeDoorConsumers(parser)
        val names = Regex("(?m)^macro (SmeditSharedDoors_([0-9A-F]{4}))\\(\\)").findAll(text).toList()
        require(names.map { it.groupValues[1] }.distinct().size == names.size) { "Duplicate canonical shared DoorDefs." }
        val references = Regex("%(SmeditSharedDoors_[0-9A-F]{4})\\(\\)").findAll(text.lineSequence().joinToString("\n") { asmCodeBeforeComment(it) }).map { it.groupValues[1] }.toSet()
        require(names.map { it.groupValues[1] }.containsAll(references)) { "Missing canonical shared DoorDef declaration." }
        return names.associate { match ->
            val pointer = match.groupValues[2].toInt(16)
            require(sharedPointer(match.groupValues[1]) == null) { "Canonical shared records must contain their own fields, not another shared alias." }
            require(consumers[pointer].orEmpty().map { it.first }.distinct().size > 1) { "${match.groupValues[1]} is not a shared native DoorDef." }
            val fields = fields(match.groupValues[1])
            require(fields.map { it.width } == listOf(2, 1, 1, 1, 1, 1, 1, 2, 2)) { "Shared DoorDef requires exactly twelve native bytes." }
            val v = fields.map { it.value ?: 0 }
            pointer to SourceRoomDoor(pointer, DoorChange(0, v[0], v[1] or (v[2] shl 8), v[3] or (v[4] shl 8), v[5], v[6], v[7], v[8]),
                fields.last().symbol?.let { SourceDoorArrival(it) }, shared = true)
        }
    }
    fun resource(room: RoomEdits, parser: RomParser, dimensions: ((Int) -> Pair<Int, Int>?)? = null): SourceRoomDoors {
        val projectId = projectRoomIds[room.roomId]
        val seed = projectId?.let { id -> requireNotNull(projectRooms.singleOrNull { it.id == id }) { "Missing project room seed $id." } }
        val header = if (seed == null) requireNotNull(parser.readRoomHeader(room.roomId)) else null
        val native = if (seed == null) parser.parseDoorList(header!!.doorOut) else emptyList()
        val declared = Regex("(?m)^macro (${prefix(room.roomId)}Door_[0-9]+)\\(").findAll(text).map { it.groupValues[1] }.toList()
        require(declared.distinct().size == declared.size) { "Duplicate door declarations." }
        val order = order(room.roomId)
        require(order != null || declared.size == native.size && declared.toSet() == native.indices.map { name(room.roomId, it) }.toSet()) {
            "${prefix(room.roomId)}: missing symbolic List declaration or incomplete fixed door coverage (declared ${declared.size}, baseline ${native.size}). Added, missing or duplicate connections need the structural workflow."
        }
        val entries = (order ?: native.indices.toList()).mapIndexed { index, identity ->
            val name = name(room.roomId, identity)
            val fields = fields(name)
            require(fields.map { it.width } == listOf(2, 1, 1, 1, 1, 1, 1, 2, 2)) { "$name: DoorDef field widths/order changed; expected 12 bytes." }
            val v = fields.map { it.value ?: 0 }
            require(v[0] !in projectRoomIds || fields.first().destination != null) {
                "$name: a project-room destination must use ${AsmProjectRoomLabels.entry(projectRoomIds.getValue(v[0]))}, not a disposable preview address."
            }
            val relocatable = authoredMacroBody(text, name).lineSequence().any { it.trim() == RELOCATABLE_DOOR_MARKER }
            require(!relocatable || order != null) { "$name: a relocatable DoorDef requires a symbolic List." }
            val pointer = if (relocatable) 0 else native.getOrNull(identity)?.doorDefPtr ?: 0
            val shared = sharedPointer(name)
            require(shared == null || shared == pointer) { "$name aliases a different shared DoorDef identity." }
            SourceRoomDoor(pointer, DoorChange(index, v[0], v[1] or (v[2] shl 8), v[3] or (v[4] shl 8), v[5], v[6], v[7], v[8]),
                fields.last().symbol?.let { SourceDoorArrival(it) }, identity, shared != null, fields.first().destination)
        }
        return SourceRoomDoors(header?.doorOut ?: 0, entries, order != null || seed != null, projectId).also { value ->
            if (seed == null) {
                if (dimensions == null) validateSourceRoomDoors(room, value, parser) else validateSourceRoomDoors(room, value, parser, dimensions)
            } else {
                require(room.asmDoorSourceVersion == 1 && room.doorChanges.isEmpty()) { "Competing door ownership." }
                require(order != null) { "Project rooms require an explicit symbolic door list." }
                value.entries.forEach { door ->
                    val id = door.change.destRoomPtr
                    val target = projectRoomIds[id]?.let { projectId -> projectRooms.singleOrNull { it.id == projectId } }
                    val size = dimensions?.invoke(id) ?: target?.header?.let { it.width to it.height }
                        ?: parser.readRoomHeader(id)?.let { it.width to it.height }
                    require(size != null) { "Door destination has no readable room." }
                    val original = seed.doors.getOrNull(door.identity)
                    val originalDestination = original?.destination?.let { destination ->
                        if (destination.startsWith("project:")) projectRoomIds.entries.singleOrNull { it.value == destination.removePrefix("project:") }?.key
                        else destination.removePrefix("rom:").toIntOrNull(16)
                    }
                    // Retain unusual native entrances copied in the creation seed; changed geometry is checked.
                    val unchanged = original != null && originalDestination == id &&
                        original.doorCapCode == door.change.doorCapCode && original.screenX == door.change.screenX &&
                        original.screenY == door.change.screenY && original.bitflag ushr 8 == door.change.bitflag ushr 8
                    if (!unchanged) {
                        require(door.change.screenX < size.first && door.change.screenY < size.second) { "Arrival screen is outside the destination." }
                        if (door.change.bitflag ushr 8 >= 4) require((door.change.doorCapCode and 255) < size.first * 16 &&
                            (door.change.doorCapCode ushr 8) < size.second * 16) { "Closing-door cap is outside the destination." }
                    }
                }
            }
        }
    }
    fun edit(room: RoomEdits, parser: RomParser, index: Int, entry: RomParser.DoorEntry, dimensions: ((Int) -> Pair<Int, Int>?)? = null): String {
        val current = resource(room, parser, dimensions)
        require(current.entries[index].doorDefPtr == entry.doorDefPtr || room.roomId in projectRoomIds) { "DoorDef identity changed; reopen this connection." }
        require(current.entries[index].arrival == null || entry.entryCode == current.entries[index].change.entryCode) {
            "Arrival target is managed in ASM. Edit its symbolic word in bank_83.asm; other connection fields remain reciprocal."
        }
        val values = listOf(entry.destRoomPtr, entry.bitflag and 255, entry.bitflag ushr 8, entry.doorCapCode and 255,
            entry.doorCapCode ushr 8, entry.screenX, entry.screenY, entry.distFromDoor, entry.entryCode)
        val result = fields(name(room.roomId, current.entries[index].identity)).mapIndexedNotNull { i, field ->
            if (field.symbol != null || field.value == values[i]) null else field.range to (if (i == 0) projectRoomIds[values[i]]?.let(AsmProjectRoomLabels::entry) ?: hex(values[i], 4) else hex(values[i], field.width * 2))
        }.sortedByDescending { it.first.first }.fold(text) { result, (range, value) -> result.replaceRange(range, value) }
        AsmDoorSource(result, arrivalSource, nativeSymbols, projectRoomIds, projectRooms).resource(room, parser, dimensions)
        return result
    }
    fun withArrival(roomId: Int, index: Int, label: String): String {
        validateArrival(label)
        val identity = order(roomId)?.get(index) ?: index
        return text.replaceRange(fields(name(roomId, identity)).last().range, label)
    }
    /** Keep the connection identity and BTS position; only its native storage is detached. */
    fun makeIndependent(room: RoomEdits, parser: RomParser, index: Int, dimensions: ((Int) -> Pair<Int, Int>?)? = null): String {
        val current = resource(room, parser, dimensions)
        val door = current.entries[index]
        require(door.shared) { "This connection is already independent." }
        val macro = name(room.roomId, door.identity)
        val declaration = requireNotNull(Regex("(?m)^macro ${Regex.escape(macro)}\\(\\)[^\\r\\n]*\\r?\\n[\\s\\S]*?^endmacro[^\\r\\n]*(?:\\r?\\n|$)").find(text))
        val notes = declaration.value.lineSequence().mapNotNull { line -> line.indexOf(';').takeIf { it >= 0 }?.let { line.substring(it).trimEnd('\r') } }
            .joinToString("\n") { "    $it" }
        val rendered = renderDoor(room.roomId, door.copy(doorDefPtr = 0, shared = false), projectRoomIds[room.roomId])
            .replaceFirst("()\n", "()\n" + if (notes.isEmpty()) "" else "$notes\n")
            .replace("\n", if (text.contains("\r\n")) "\r\n" else "\n")
        val previousLine = text.lastIndexOf('\n', declaration.range.first - 2) + 1
        val start = if (text.substring(previousLine, declaration.range.first).trim() ==
            "; Shared native record; edit its canonical declaration, not a private duplicate.") previousLine else declaration.range.first
        val replaced = text.replaceRange(start..declaration.range.last, rendered)
        return AsmDoorSource(replaced, arrivalSource, nativeSymbols, projectRoomIds, projectRooms)
            .withOrder(room.roomId, current.entries.map { it.identity })
    }
    fun line(roomId: Int, index: Int = 0): Int {
        val order = order(roomId)
        val name = if (order != null && (index < 0 || order.isEmpty())) listName(roomId) else order?.getOrNull(index)?.let { name(roomId, it) } ?: name(roomId, index)
        val start = requireNotNull(Regex("(?m)^macro $name\\(\\)").find(text)) { "Missing door declaration $name." }.range.first
        return text.take(start).count { it == '\n' }
    }
    /** An explicit order is the sole authority for structural lists; inactive macros retain identities/notes. */
    fun order(roomId: Int): List<Int>? {
        val matches = Regex("(?m)^macro ${listName(roomId)}\\(\\)[ \\t]*(?:;[^\\r\\n]*)?\\r?\\n([\\s\\S]*?)^endmacro[ \\t]*(?:;[^\\r\\n]*)?(?:\\r?\\n|$)").findAll(text).toList()
        require(matches.size <= 1) { "Duplicate door-list declarations." }
        val match = matches.singleOrNull() ?: return null
        val tokens = match.groupValues[1].lineSequence().map { asmCodeBeforeComment(it).trim() }.filter { it.isNotEmpty() }.flatMap { line ->
            require(line.startsWith("dw ") || line.startsWith("dw\t")) { "Door list expects symbolic dw entries and a final dw \$0000." }
            line.drop(2).split(',').map { it.trim() }.asSequence()
        }.toList()
        require(tokens.isNotEmpty() && AsmPlmDocument.literal(tokens.last()) == 0) { "Door list requires a final dw \$0000 terminator." }
        val ids = tokens.dropLast(1).map { token ->
            requireNotNull(Regex("${Regex.escape(prefix(roomId))}Door_([0-9]+)_Entry").matchEntire(token)) {
                "Door list expects this room's stable Door_N_Entry symbols, not numeric addresses."
            }.groupValues[1].toInt()
        }
        require(ids.size <= 16 && ids.distinct().size == ids.size) { "Door list supports at most 16 distinct connections." }
        return ids
    }
    fun withOrder(roomId: Int, ids: List<Int>): String {
        val old = Regex("(?m)^macro ${listName(roomId)}\\(\\)[^\\r\\n]*\\r?\\n[\\s\\S]*?^endmacro[^\\r\\n]*(?:\\r?\\n|$)").find(text)
        val notes = old?.value?.lineSequence()?.mapNotNull { line ->
            line.indexOf(';').takeIf { it >= 0 }?.let { line.substring(it) }
                ?.takeUnless { it.contains("List position is") || it.contains("End of connections") }
        }?.joinToString("") { "    $it\n" }.orEmpty()
        val replacement = renderOrder(roomId, ids).replaceFirst("()\n", "()\n$notes").replace("\n", if (text.contains("\r\n")) "\r\n" else "\n")
        return if (old == null) text + (if (text.contains("\r\n")) "\r\n" else "\n") + replacement else text.replaceRange(old.range, replacement)
    }
    fun nextIdentity(roomId: Int, nativeCount: Int): Int = maxOf(nativeCount - 1,
        Regex("(?m)^macro ${prefix(roomId)}Door_([0-9]+)\\(").findAll(text).maxOfOrNull { it.groupValues[1].toInt() } ?: -1) + 1
    companion object {
        fun sharedName(pointer: Int) = "SmeditSharedDoors_${pointer.toString(16).uppercase().padStart(4, '0')}"
        fun renderShared(door: SourceRoomDoor, consumers: List<String>) = buildString {
            appendLine("; Shared native DoorDef · edits affect ${consumers.joinToString()}")
            append(renderDoor(0, door.copy(shared = false)).replace(name(0, door.identity), sharedName(door.doorDefPtr)))
            appendLine("org \$83${door.doorDefPtr.toString(16).uppercase().padStart(4, '0')}")
            appendLine("${sharedName(door.doorDefPtr)}_Entry:")
            appendLine("    %${sharedName(door.doorDefPtr)}()")
            appendLine("${sharedName(door.doorDefPtr)}_End:")
            appendLine("assert ${sharedName(door.doorDefPtr)}_End-${sharedName(door.doorDefPtr)}_Entry == 12")
        }
        fun prefix(roomId: Int, projectId: String? = null) = AsmRoomSourceContext.prefix("SmeditDoors", roomId, projectId)
        fun name(roomId: Int, index: Int, projectId: String? = null) = prefix(roomId, projectId) + "Door_$index"
        fun listName(roomId: Int, projectId: String? = null) = prefix(roomId, projectId) + "List"
        fun entryLabel(roomId: Int, identity: Int, projectId: String? = null) = name(roomId, identity, projectId) + "_Entry"
        fun renderOrder(roomId: Int, ids: List<Int>, projectId: String? = null) = buildString {
            appendLine("macro ${listName(roomId, projectId)}()")
            appendLine("    ; List position is the doorway BTS index; Door_N identities never renumber.")
            ids.forEach { appendLine("    dw ${entryLabel(roomId, it, projectId)}") }
            appendLine("    dw \$0000 ; End of connections")
            appendLine("endmacro")
        }
        private fun hex(value: Int, width: Int) = "\$${value.toString(16).uppercase().padStart(width, '0')}"
        fun render(roomId: Int, roomName: String, source: SourceRoomDoors, projectId: String? = source.projectRoomId) = buildString {
            appendLine("; SMEDIT doors v1 · ${roomName.replace('\n', ' ').replace('\r', ' ')}")
            appendLine(if (source.structural) "; Stable declaration identities; the List macro determines BTS order. One-way connections are allowed."
                else "; Existing connection order and DoorDef identities stay fixed; one-way connections are allowed.")
            appendLine("; Arrival ASM is authored separately in bank_8F.asm; editing it does not disable these data fields.")
            source.entries.forEach { append(renderDoor(roomId, it, projectId)) }
            if (source.structural) appendLine(renderOrder(roomId, source.entries.map { it.identity }, projectId))
        }
        fun renderDoor(roomId: Int, door: SourceRoomDoor, projectId: String? = null) = buildString {
                if (door.shared) {
                    appendLine("; Shared native record; edit its canonical declaration, not a private duplicate.")
                    appendLine("macro ${name(roomId, door.identity, projectId)}()")
                    appendLine("    %${sharedName(door.doorDefPtr)}()")
                    appendLine("endmacro\n")
                    return@buildString
                }
                val d = door.change
                appendLine(if (door.doorDefPtr == 0) "; Door identity ${door.identity} · symbolic DoorDef, placed at build"
                    else "; Door identity ${door.identity} · DoorDef \$83:${hex(door.doorDefPtr, 4).drop(1)}")
                appendLine("macro ${name(roomId, door.identity, projectId)}()")
                if (door.doorDefPtr == 0) appendLine("    $RELOCATABLE_DOOR_MARKER")
                appendLine("    dw ${door.destinationProjectId?.let(AsmProjectRoomLabels::entry) ?: hex(d.destRoomPtr, 4)} ; Destination room · bank \$8F")
                appendLine("    db ${hex(d.bitflag and 255, 2)}, ${hex(d.bitflag ushr 8, 2)} ; Flags, direction / closing door")
                appendLine("    db ${hex(d.doorCapCode and 255, 2)}, ${hex(d.doorCapCode ushr 8, 2)} ; Door cap tile X, Y (not an ASM pointer)")
                appendLine("    db ${hex(d.screenX, 2)}, ${hex(d.screenY, 2)} ; Arrival screen X, Y")
                appendLine("    dw ${hex(d.distFromDoor, 4)} ; Distance (\$8000 = default)")
                appendLine("    dw ${door.arrival?.label ?: hex(d.entryCode, 4)} ; Arrival ASM · bank \$8F (\$0000 = none)")
                appendLine("endmacro\n")
        }
        fun targetAt(lines: List<String>, line: Int): AsmEditorTarget.Room? {
            for (index in line.coerceAtMost(lines.lastIndex) downTo 0) {
                if (lines[index].trimStart().startsWith("endmacro") && index != line) return null
                Regex("^macro SmeditDoors_([0-9A-F]{4})_(?:Door_[0-9]+|List)\\(\\)").find(lines[index])?.let {
                    return AsmEditorTarget.Room(it.groupValues[1].toInt(16))
                }
                if (lines[index].trimStart().startsWith("macro ")) return null
            }
            return null
        }
    }
}

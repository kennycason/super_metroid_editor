package com.supermetroid.editor.asm

import com.supermetroid.editor.data.DoorChange
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.rom.*

internal const val DOOR_SOURCE_FILE = FX_SOURCE_FILE

/** A twelve-byte DoorDef lens. Executable arrival code is a separate source resource. */
internal class AsmDoorSource(val text: String, private val arrivalSource: String = "", private val nativeSymbols: Map<String, Int> = emptyMap()) {
    private data class Field(val range: IntRange, val width: Int, val value: Int?, val symbol: String? = null)
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
        val header = requireNotNull(parser.readRoomHeader(room.roomId))
        val native = parser.parseDoorList(header.doorOut)
        val declared = Regex("(?m)^macro (${prefix(room.roomId)}Door_[0-9]+)\\(").findAll(text).map { it.groupValues[1] }.toList()
        require(declared.distinct().size == declared.size) { "Duplicate door declarations." }
        val order = order(room.roomId)
        require(order != null || declared.size == native.size && declared.toSet() == native.indices.map { name(room.roomId, it) }.toSet()) {
            "Door source must cover the existing list exactly; added, missing or duplicate connection declarations need a structural workflow."
        }
        val entries = (order ?: native.indices.toList()).mapIndexed { index, identity ->
            val name = name(room.roomId, identity)
            val fields = fields(name)
            require(fields.map { it.width } == listOf(2, 1, 1, 1, 1, 1, 1, 2, 2)) { "$name: DoorDef field widths/order changed; expected 12 bytes." }
            val v = fields.map { it.value ?: 0 }
            val pointer = native.getOrNull(identity)?.doorDefPtr ?: 0
            val shared = sharedPointer(name)
            require(shared == null || shared == pointer) { "$name aliases a different shared DoorDef identity." }
            SourceRoomDoor(pointer, DoorChange(index, v[0], v[1] or (v[2] shl 8), v[3] or (v[4] shl 8), v[5], v[6], v[7], v[8]),
                fields.last().symbol?.let { SourceDoorArrival(it) }, identity, shared != null)
        }
        return SourceRoomDoors(header.doorOut, entries, order != null).also { value ->
            if (dimensions == null) validateSourceRoomDoors(room, value, parser) else validateSourceRoomDoors(room, value, parser, dimensions)
        }
    }
    fun edit(room: RoomEdits, parser: RomParser, index: Int, entry: RomParser.DoorEntry, dimensions: ((Int) -> Pair<Int, Int>?)? = null): String {
        val current = resource(room, parser, dimensions)
        require(current.entries[index].doorDefPtr == entry.doorDefPtr) { "DoorDef identity changed; reopen this connection." }
        require(current.entries[index].arrival == null || entry.entryCode == current.entries[index].change.entryCode) {
            "Arrival target is managed in ASM. Edit its symbolic word in bank_83.asm; other connection fields remain reciprocal."
        }
        val values = listOf(entry.destRoomPtr, entry.bitflag and 255, entry.bitflag ushr 8, entry.doorCapCode and 255,
            entry.doorCapCode ushr 8, entry.screenX, entry.screenY, entry.distFromDoor, entry.entryCode)
        val result = fields(name(room.roomId, current.entries[index].identity)).mapIndexedNotNull { i, field ->
            if (field.symbol != null || field.value == values[i]) null else field.range to hex(values[i], field.width * 2)
        }.sortedByDescending { it.first.first }.fold(text) { result, (range, value) -> result.replaceRange(range, value) }
        AsmDoorSource(result, arrivalSource, nativeSymbols).resource(room, parser, dimensions)
        return result
    }
    fun withArrival(roomId: Int, index: Int, label: String): String {
        validateArrival(label)
        val identity = order(roomId)?.get(index) ?: index
        return text.replaceRange(fields(name(roomId, identity)).last().range, label)
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
        fun prefix(roomId: Int) = "SmeditDoors_${roomId.toString(16).uppercase().padStart(4, '0')}_"
        fun name(roomId: Int, index: Int) = prefix(roomId) + "Door_$index"
        fun listName(roomId: Int) = prefix(roomId) + "List"
        fun entryLabel(roomId: Int, identity: Int) = name(roomId, identity) + "_Entry"
        fun renderOrder(roomId: Int, ids: List<Int>) = buildString {
            appendLine("macro ${listName(roomId)}()")
            appendLine("    ; List position is the doorway BTS index; Door_N identities never renumber.")
            ids.forEach { appendLine("    dw ${entryLabel(roomId, it)}") }
            appendLine("    dw \$0000 ; End of connections")
            appendLine("endmacro")
        }
        private fun hex(value: Int, width: Int) = "\$${value.toString(16).uppercase().padStart(width, '0')}"
        fun render(roomId: Int, roomName: String, source: SourceRoomDoors) = buildString {
            appendLine("; SMEDIT doors v1 · ${roomName.replace('\n', ' ').replace('\r', ' ')}")
            appendLine(if (source.structural) "; Stable declaration identities; the List macro determines BTS order. One-way connections are allowed."
                else "; Existing connection order and DoorDef identities stay fixed; one-way connections are allowed.")
            appendLine("; Arrival ASM is authored separately in bank_8F.asm; editing it does not disable these data fields.")
            source.entries.forEach { append(renderDoor(roomId, it)) }
            if (source.structural) appendLine(renderOrder(roomId, source.entries.map { it.identity }))
        }
        fun renderDoor(roomId: Int, door: SourceRoomDoor) = buildString {
                if (door.shared) {
                    appendLine("; Shared native record; edit its canonical declaration, not a private duplicate.")
                    appendLine("macro ${name(roomId, door.identity)}()")
                    appendLine("    %${sharedName(door.doorDefPtr)}()")
                    appendLine("endmacro\n")
                    return@buildString
                }
                val d = door.change
                appendLine(if (door.doorDefPtr == 0) "; Door identity ${door.identity} · symbolic DoorDef, placed at build"
                    else "; Door identity ${door.identity} · DoorDef \$83:${hex(door.doorDefPtr, 4).drop(1)}")
                appendLine("macro ${name(roomId, door.identity)}()")
                appendLine("    dw ${hex(d.destRoomPtr, 4)} ; Destination room · bank \$8F")
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

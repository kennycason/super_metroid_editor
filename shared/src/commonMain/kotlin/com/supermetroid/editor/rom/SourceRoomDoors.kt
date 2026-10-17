package com.supermetroid.editor.rom

import com.supermetroid.editor.data.DoorChange
import com.supermetroid.editor.data.RoomEdits

/** Symbolic executable authority; a live preview has no guessed allocation address. */
data class SourceDoorArrival(val label: String, val snesAddress: Int? = null) {
    init {
        require(label.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid arrival ASM label." }
        require(snesAddress == null || snesAddress in 0x8F8000..0x8FFFFE) { "Arrival ASM must resolve inside bank \$8F." }
    }
}

/** Stable declaration identity is independent from its current BTS/list position. Zero is an unallocated new DoorDef. */
data class SourceRoomDoor(val doorDefPtr: Int, val change: DoorChange, val arrival: SourceDoorArrival? = null,
                          val identity: Int = change.doorIndex,
                          /** One canonical authored record, deliberately shared by its native list consumers. */
                          val shared: Boolean = false) {
    init {
        require((doorDefPtr == 0 || doorDefPtr in 0x8000..0xFFF4) && change.doorIndex in 0..15 && identity in 0..65535) { "Invalid DoorDef identity." }
        // Direction 0..3: no closure, 4..7: blue door, 8..11: escape gate (bank $82:E8EB).
        require(change.destRoomPtr in 0x8000..0xFFFE && change.bitflag in 0..65535 && change.bitflag ushr 8 in 0..11 &&
            change.doorCapCode in 0..65535 && change.screenX in 0..255 && change.screenY in 0..255 &&
            change.distFromDoor in 0..65535 && (change.entryCode == 0 || change.entryCode in 0x8000..0xFFFE)) {
            "Door ${change.doorIndex + 1}: invalid destination, direction, byte/word field or arrival ASM pointer."
        }
        require(arrival == null || change.entryCode == (arrival.snesAddress?.and(65535) ?: 0)) { "Arrival ASM address does not match its resolved symbol." }
        require(!shared || doorDefPtr != 0) { "A shared native record must have a native DoorDef identity." }
    }
    fun entry() = RomParser.DoorEntry(change.destRoomPtr, change.bitflag, change.doorCapCode, change.screenX,
        change.screenY, change.distFromDoor, change.entryCode, doorDefPtr)
    fun bytes(): ByteArray = buildList {
        require(arrival == null || arrival.snesAddress != null) { "Resolve arrival ASM ${arrival?.label} through assembler symbols before exporting." }
        fun word(value: Int) { add(value.toByte()); add((value ushr 8).toByte()) }
        word(change.destRoomPtr); word(change.bitflag); word(change.doorCapCode)
        add(change.screenX.toByte()); add(change.screenY.toByte()); word(change.distFromDoor); word(change.entryCode)
    }.toByteArray()
}

data class SourceRoomDoors(val doorOutPtr: Int, val entries: List<SourceRoomDoor>, val structural: Boolean = false) {
    init {
        require((doorOutPtr in 0x8000..0xFFFE || structural && doorOutPtr == 0) && entries.size in (if (structural) 0 else 1)..16 &&
            (structural || doorOutPtr + entries.size * 2 <= 0x10000) && entries.map { it.change.doorIndex } == entries.indices.toList() &&
            entries.map { it.identity }.distinct().size == entries.size &&
            entries.filter { it.doorDefPtr != 0 }.map { it.doorDefPtr }.distinct().size == entries.count { it.doorDefPtr != 0 } &&
            (structural || entries.all { it.doorDefPtr != 0 && it.identity == it.change.doorIndex })) {
            "Door source must cover each existing connection exactly once; list growth/reordering needs a structural transaction."
        }
    }
}

fun validateSourceRoomDoors(room: RoomEdits, source: SourceRoomDoors, parser: RomParser,
                            dimensions: (Int) -> Pair<Int, Int>? = { id -> parser.readRoomHeader(id)?.let { it.width to it.height } }) {
    require(room.asmDoorSourceVersion == 1 && room.doorChanges.isEmpty()) { "Door fields have unsupported or competing ASM/GUI ownership." }
    val header = requireNotNull(parser.readRoomHeader(room.roomId)) { "Missing door source room." }
    require(header.area in 0..6) { "The debug/unused area's door data is not writable." }
    require(source.doorOutPtr == header.doorOut && (room.roomHeaderChange?.doorOut ?: header.doorOut) == header.doorOut) {
        "Door-list relocation is not supported by the fixed DoorDef source format."
    }
    val native = parser.parseDoorList(header.doorOut)
    require(if (source.structural) source.entries.all { door ->
        door.doorDefPtr == (native.getOrNull(door.identity)?.doorDefPtr ?: 0)
    } else native.map { it.doorDefPtr } == source.entries.map { it.doorDefPtr }) { "Door count/order/identities changed; repair the native list before building." }
    for (door in source.entries) {
        val target = requireNotNull(dimensions(door.change.destRoomPtr)) { "Door ${door.change.doorIndex + 1}: destination has no readable room header." }
        // Vanilla includes unused/special entrances outside their target dimensions (e.g. Waterway
        // E-Tank -> Kraid Refill). Adoption and distance edits must retain them byte-for-byte, not
        // normalize them. Only the original geometry in the original-sized destination is exempt.
        val baseline = native.getOrNull(door.identity)
        val nativeTarget = parser.readRoomHeader(door.change.destRoomPtr)?.let { it.width to it.height }
        if (baseline != null && target == nativeTarget && door.entry() == baseline.copy(
                bitflag = (baseline.bitflag and 0x40.inv()) or (door.change.bitflag and 0x40),
                distFromDoor = door.change.distFromDoor, entryCode = door.change.entryCode)) continue
        require(door.change.screenX < target.first && door.change.screenY < target.second) { "Door ${door.change.doorIndex + 1}: arrival screen is outside the destination room." }
        // Blue-door and escape-gate closures both use the cap coordinates.
        if (door.change.bitflag ushr 8 >= 4) require((door.change.doorCapCode and 255) < target.first * 16 &&
            (door.change.doorCapCode ushr 8) < target.second * 16) { "Door ${door.change.doorIndex + 1}: closing-door cap is outside the destination room." }
    }
}

fun validateSourceDoorOwnership(roomId: Int, source: SourceRoomDoors, parser: RomParser) {
    val pointers = source.entries.map { it.doorDefPtr }.filter { it != 0 }.toSet()
    val shared = nativeDoorConsumers(parser).filter { (_, consumers) -> consumers.map { it.first }.distinct().size > 1 }.keys
    require(source.entries.filter { it.doorDefPtr in pointers.intersect(shared) }.all { it.shared }) {
        "Room ${roomId.toString(16)}: shared DoorDefs require their canonical SmeditSharedDoors record; independent declarations cannot own the same bytes."
    }
    require(source.entries.filter { it.shared }.all { it.doorDefPtr in shared }) { "Shared source refers to a DoorDef without multiple native room consumers." }
}

/** Source-room/index pairs, not destination rooms. Used for ownership and user-facing impact notices. */
fun nativeDoorConsumers(parser: RomParser): Map<Int, List<Pair<Int, Int>>> = buildMap<Int, MutableList<Pair<Int, Int>>> {
    for (info in parser.roomCatalog.rooms) {
        val id = info.getRoomIdAsInt()
        val header = parser.readRoomHeader(id) ?: continue
        parser.parseDoorList(header.doorOut).forEachIndexed { index, door -> getOrPut(door.doorDefPtr) { mutableListOf() }.add(id to index) }
    }
}

/** Structural lists must not turn a doorway tile in any state into an out-of-list read. */
fun validateStructuralDoorLayout(layout: SourceRoomLayoutResource, count: Int) {
    val bytes = layout.bytes()
    val layerBytes = layout.values[0] or (layout.values[1] shl 8)
    for (index in 0 until layout.width * layout.height) {
        if ((layout.values[3 + index * 2] ushr 4) == 9) {
            val bts = bytes[2 + layerBytes + index].toInt() and 0x7F // Bit 7 is the elevator flag, not part of the list index.
            require(bts < count) { "${layout.identity}: doorway (${index % layout.width}, ${index / layout.width}) uses missing connection ${bts + 1}. Relink or erase it first." }
        }
    }
}

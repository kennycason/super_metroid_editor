package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.*

internal data class AsmRoomDoorTable(val macro: String, val door: SourceRoomDoor) {
    val label get() = macro.replaceFirst("SmeditDoors_", "SmeditRoom_") + "_DoorDef"
    fun claim() = AsmGeneratedRoomClaim(ASM_ROOM_GRAPH_OWNER, label,
        requireNotNull(snesLoRomToPc(0x830000 or door.doorDefPtr)), door.bytes())
    fun renderSource(): String = buildString {
        appendLine("; Source-owned twelve-byte DoorDef; identity stays stable for BTS, FX, saves and selectors.")
        appendLine("org \$83${door.doorDefPtr.toString(16).uppercase().padStart(4, '0')}")
        appendLine("$label:")
        appendLine("${macro}_Entry:")
        appendLine("    %$macro()")
        appendLine("${label}_End:")
        appendLine("assert ${label}_End-$label == 12")
    }
}

internal fun protectDoorSourceBaseline(sources: Map<Int, SourceRoomDoors>, original: RomParser, compiled: AsmCompiledBuildBase,
                                       nativeSymbols: Map<String, Int> = emptyMap()): AsmCompiledBuildBase {
    val built = RomParser(compiled.romBytes)
    val labels = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels
    for ((roomId, source) in sources) {
        val native = original.parseDoorList(source.doorOutPtr)
        require(built.readRoomHeader(roomId)?.doorOut == source.doorOutPtr &&
            built.parseDoorList(source.doorOutPtr).map { it.doorDefPtr } == native.map { it.doorDefPtr }) {
            "Room ${roomId.toString(16)} native door-list identity also changed in ASM. Resolve ownership before building authored doors."
        }
        for (door in source.entries) {
            if (door.doorDefPtr != 0) {
                val baseline = original.parseDoorEntry(source.doorOutPtr, door.identity)
                val actual = built.parseDoorEntry(source.doorOutPtr, door.identity)
                // The original DoorHeader macro may itself relink its known arrival symbol. That
                // isn't competing data ownership when our authored word is symbolic too. No other
                // native field or arbitrary numeric replacement receives this exception.
                val relinked = if (door.arrival == null || baseline == null) null else nativeSymbols.entries.firstNotNullOfOrNull { (name, value) ->
                    labels[name]?.takeIf { name.startsWith("DoorASM_") && value == baseline.entryCode && it in 0x8F8000..0x8FFFFE }
                        ?.let { baseline.copy(entryCode = it and 65535) }?.takeIf { it == actual }
                }
                require(baseline == actual || relinked != null) { "DoorDef ${door.doorDefPtr.toString(16)} also changed in original bank_83.asm; edit its SmeditDoors declaration instead." }
            }
            nativeSymbols.entries.firstOrNull { door.arrival == null && door.change.entryCode != 0 && it.key.startsWith("DoorASM_") && it.value == door.change.entryCode }?.let { anchor ->
                require(labels[anchor.key] == (0x8F0000 or door.change.entryCode)) {
                    "Arrival ASM ${anchor.key} moved or was removed. Review the compiled target and update this DoorDef's entry word; stale numeric pointers cannot be used safely."
                }
            }
        }
    }
    // After validation, the complete fixed DoorDef belongs to the typed room backend. A
    // legitimate native-label relink must not become a second competing raw-source owner.
    val owned = sources.values.flatMap { it.entries }.filter { it.doorDefPtr != 0 }.map { door ->
        requireNotNull(snesLoRomToPc(0x830000 or door.doorDefPtr)) until requireNotNull(snesLoRomToPc(0x830000 or door.doorDefPtr)) + 12
    }
    val retained = compiled.sourceOwnedRanges.flatMap { range ->
        var spans = listOf(range.pcOffset until range.pcOffset + range.length)
        for (claim in owned) spans = spans.flatMap { span ->
            if (claim.last < span.first || claim.first > span.last) listOf(span) else buildList {
                if (span.first < claim.first) add(span.first until claim.first)
                if (claim.last < span.last) add(claim.last + 1..span.last)
            }
        }
        spans.map { range.copy(pcOffset = it.first, length = it.last - it.first + 1) }
    }
    return compiled.copy(sourceOwnedRanges = retained)
}

internal data class AsmRoomDoorListTable(val roomId: Int, val pointer: Int, val doors: List<SourceRoomDoor>) {
    private val label = "SmeditRoom_${roomId.toString(16).uppercase()}_DoorList"
    fun claim() = AsmGeneratedRoomClaim(ASM_ROOM_GRAPH_OWNER, label, requireNotNull(snesLoRomToPc(0x8F0000 or pointer)),
        (doors.flatMap { listOf(it.doorDefPtr.toByte(), (it.doorDefPtr ushr 8).toByte()) } + listOf(0.toByte(), 0.toByte())).toByteArray())
    fun renderSource() = buildString {
        appendLine("org \$8F${pointer.toString(16).uppercase().padStart(4, '0')}")
        appendLine("$label:")
        doors.forEach { appendLine("assert (${AsmDoorSource.entryLabel(roomId, it.identity)}>>16) == \$83") }
        appendLine("    %${AsmDoorSource.listName(roomId)}()")
        appendLine("${label}_End:")
        appendLine("assert ${label}_End-$label == ${doors.size * 2 + 2}")
    }
}

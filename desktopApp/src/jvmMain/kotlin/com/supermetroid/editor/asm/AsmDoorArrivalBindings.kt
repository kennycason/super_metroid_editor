package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.*

/** The native dispatcher establishes 16-bit A/X/Y and DB=$8F, and supplies an RTS return. */
internal val DOOR_ARRIVAL_DISPATCH_BYTES = listOf(0x08, 0x8B, 0xC2, 0x30, 0xAE, 0x8D, 0x07,
    0xBF, 0x0A, 0x00, 0x83, 0xF0, 0x0A, 0x85, 0x12, 0x4B, 0xAB, 0xF4, 0xB9, 0xE8, 0x6C, 0x12, 0x00, 0xAB, 0x28, 0x6B)
    .map { it.toByte() }.toByteArray()

/** No guessed live-preview address may reach the ROM writer. Resolve against this build only. */
private fun checkedDoorArrivals(labels: Collection<String>, compiled: AsmCompiledBuildBase): Map<String, SourceDoorArrival> {
    if (labels.isEmpty()) return emptyMap()
    val symbols = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels
    val parser = RomParser(compiled.romBytes)
    val engine = parser.snesToPc(0x8FE8A3)
    require(compiled.romBytes.copyOfRange(engine, engine + DOOR_ARRIVAL_DISPATCH_BYTES.size).contentEquals(DOOR_ARRIVAL_DISPATCH_BYTES)) {
        "Symbolic arrival ASM requires the verified native bank-8F/16-bit/RTS door-dispatch ABI."
    }
    return labels.distinct().associateWith { label ->
        val address = requireNotNull(symbols[label]) { "Missing arrival ASM symbol $label." }
        val resolved = SourceDoorArrival(label, address)
        if (label.startsWith("SmeditArrival_")) {
            val end = requireNotNull(symbols[label + "_End"]) { "Missing managed arrival boundary." }
            require(end > address && end - address <= 1024 && end <= 0x900000) { "Managed arrival escaped its bank/size bounds." }
            val pc = requireNotNull(snesLoRomToPc(address))
            require((pc until pc + end - address).all { offset -> compiled.sourceOwnedRanges.any { offset >= it.pcOffset && offset < it.pcOffset + it.length } }) {
                "$label: the complete managed arrival envelope must have source ownership."
            }
        } else require(label.startsWith("DoorASM_")) { "Unsupported project arrival symbol $label." }
        resolved
    }
}

internal fun resolveDoorArrivals(inputs: Map<Int, SourceRoomDoors>, compiled: AsmCompiledBuildBase): Map<Int, SourceRoomDoors> {
    val resolved = checkedDoorArrivals(inputs.values.flatMap { it.entries.mapNotNull { door -> door.arrival?.label } }, compiled)
    return inputs.mapValues { (_, doors) -> doors.copy(entries = doors.entries.map { door ->
        val arrival = door.arrival?.let { resolved.getValue(it.label) } ?: return@map door
        door.copy(change = door.change.copy(entryCode = requireNotNull(arrival.snesAddress) and 65535), arrival = arrival)
    }) }
}

internal fun resolveProjectDoorArrivals(project: com.supermetroid.editor.data.SmEditProject, compiled: AsmCompiledBuildBase): Map<String, Int> =
    checkedDoorArrivals(project.newRooms.flatMap { it.doors.mapNotNull { door -> door.arrivalAsmLabel } }, compiled)
        .mapValues { requireNotNull(it.value.snesAddress) }

/** Inherited opaque scripts/backgrounds are not silently relinked by a scalar data lens. */
internal fun validateProjectRoomSeedAnchors(project: com.supermetroid.editor.data.SmEditProject,
                                           labels: Map<String, Int>, nativeSymbols: Map<String, Int>,
                                           originalBankText: String? = null) {
    val bankLabels = originalBankText?.let { text ->
        Regex("(?m)^([A-Za-z_][A-Za-z0-9_]*):").findAll(text).map { it.groupValues[1] }.toSet()
    }
    for (room in project.newRooms) {
        val seed = room.initialState
        for ((field, pointer) in mapOf("main ASM" to seed.mainAsmPtr, "setup ASM" to seed.setupAsmPtr,
            "X-ray data" to seed.xraySpecialCasingPtr, "background data" to seed.bgDataPtr)) {
            if (pointer !in 0x8000..0xFFFE) continue
            // The PLM catalog contains low words from multiple banks. Only declarations
            // in the original bank-$8F source can anchor these bank-$8F fields.
            val anchors = nativeSymbols.filter { (name, value) -> value == pointer && (bankLabels == null || name in bankLabels) }.keys
            // The source index also contains conditional/index-only aliases. A native
            // RTS entry has an exact address-qualified name; otherwise check actual
            // compiler labels rather than treating absent index aliases as code removal.
            val rts = "RTS_8F${pointer.toString(16).uppercase().padStart(4, '0')}".takeIf { it in anchors }
            val actual = rts?.let(::setOf) ?: anchors.filter { it in labels }.toSet()
            require(anchors.isEmpty() || actual.isNotEmpty() && actual.all { labels[it] == (0x8F0000 or pointer) }) {
                "Project room '${room.name}' inherited $field at $${pointer.toString(16).uppercase()}, but its native ASM label moved or was removed. " +
                    "Review the creation-seed binding or keep that native label anchored; a stale pointer cannot be exported."
            }
        }
    }
}

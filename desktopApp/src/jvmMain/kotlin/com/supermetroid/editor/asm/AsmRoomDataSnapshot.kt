package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser

/** An explanation of a validated ROM, NOT an assembler input or a second edit authority.
 * No allocations, source changes, or ROM writes happen here. Unknown formats stay explicit. */
internal object AsmRoomDataSnapshot {
    const val FILE_NAME = "room-data.asm"
    const val MARKER = "; SMEDIT room-data snapshot v1"

    // Use the input catalog's stable identities, not a heuristic rescan of edited output.
    fun render(rom: ByteArray, rooms: List<RoomInfo>, projectRoomIds: Map<Int, String> = emptyMap()): String {
        val parser = RomParser(rom)
        fun hex(value: Int, width: Int = 4) = "\$" + value.toString(16).uppercase().padStart(width, '0')
        fun clean(text: String) = text.lineSequence().joinToString(" ")
        return buildString {
            appendLine(MARKER)
            appendLine("; Read-only view of the last validated ROM. Edit rooms in SMEDIT and Build to refresh.")
            appendLine("; This is a decoded reference, not the generated assembler input or an editable module.")
            appendLine("; org = SNES address; db/dw/dl = 1/2/3-byte values. Comments explain each field.")
            for (room in rooms.sortedBy { it.name.lowercase() }) {
                val roomId = room.getRoomIdAsInt()
                val id = roomId.toString(16).uppercase().padStart(4, '0')
                val projectId = projectRoomIds[roomId]
                val prefix = projectId?.let(AsmProjectRoomLabels::prefix) ?: "SmeditRoom_$id"
                fun section(label: String, title: String, address: Int) {
                    appendLine()
                    appendLine("; @room $id ${clean(room.name)}")
                    projectId?.let { appendLine("; @project-room ${AsmProjectRoomLabels.encoded(it)} $id") }
                    appendLine("; @section $label ${clean(title)}")
                    appendLine("; ${clean(room.name)} · ${clean(title)}")
                    appendLine("org ${hex(address, 6)}")
                    appendLine("$label:")
                }
                section("${prefix}_Header", "Header", 0x8F0000 or roomId)
                val inspection = runCatching { parser.inspectRoomStates(roomId) }.getOrNull()
                if (inspection == null || inspection.issues.isNotEmpty() || !inspection.hasDefault) {
                    appendLine("; Unavailable: room selectors could not be decoded safely. See ROM validation.")
                    continue
                }
                val headerPc = parser.snesToPc(0x8F0000 or roomId)
                listOf("Room index", "Area", "Map X", "Map Y", "Width (screens)", "Height (screens)",
                    "Up scroller", "Down scroller", "Special graphics flags").forEachIndexed { offset, name ->
                    appendLine("    db ${hex(rom[headerPc + offset].toInt() and 0xFF, 2)} ; $name")
                }
                val doorPointer = (rom[headerPc + 9].toInt() and 0xFF) or ((rom[headerPc + 10].toInt() and 0xFF) shl 8)
                appendLine("    dw ${hex(doorPointer)} ; Door list in bank \$8F")
                val states = parser.parseRoomStatesWithData(roomId)
                states.forEachIndexed { stateIndex, state ->
                    val stateLabel = "${prefix}_State${stateIndex}"
                    val condition = inspection.states[stateIndex].condition.shortSummary(inspection.area ?: 0)
                    section("${stateLabel}_Data", "$condition · State data", parser.pcToSnes(state.stateInfo.stateDataPcOffset))
                    appendLine("; Runtime state ${stateIndex + 1}/${states.size}; selectors are evaluated in this order.")
                    appendLine("    dl ${hex(state.levelDataPtr, 6)} ; Compressed level data (tiles/BTS/layer 2)")
                    appendLine("    db ${hex(state.tileset, 2)} ; Tileset")
                    appendLine("    db ${hex(state.musicData, 2)}, ${hex(state.musicTrack, 2)} ; Music data / track")
                    listOf(state.fxPtr to "FX (bank \$83)", state.enemySetPtr to "Enemy population (bank \$A1)",
                        state.enemyGfxPtr to "Enemy graphics set (bank \$B4)", state.bgScrolling to "Layer 2 scrolling",
                        state.scrollPtr to "Scroll map (bank \$8F, 0/1 = special constant modes)",
                        state.xraySpecialCasingPtr to "X-Ray handling", state.mainAsmPtr to "Main room ASM").forEach { (value, meaning) ->
                        appendLine("    dw ${hex(value)} ; $meaning")
                    }
                    val listPc = if (state.plmSetPtr in 0x8000..0xFFFE) parser.snesToPc(0x8F0000 or state.plmSetPtr) else null
                    val entries = listPc?.let { runCatching { parser.parsePlmSet(state.plmSetPtr) }.getOrNull() }
                    val payload = entries?.let { RomParser.serializePlmSet(it).map(Int::toByte).toByteArray() }
                    val valid = listPc != null && payload != null && state.plmSetPtr + payload.size <= 0x10000 &&
                        listPc + payload.size <= rom.size && rom.copyOfRange(listPc, listPc + payload.size).contentEquals(payload)
                    if (valid) appendLine("    dw ${stateLabel}_Plms&\$FFFF ; PLM/object list (bank \$8F)")
                    else appendLine("    dw ${hex(state.plmSetPtr)} ; PLM list: ${if (listPc == null) "no list" else "unsupported/truncated data"}")
                    appendLine("    dw ${hex(state.bgDataPtr)} ; Background program")
                    appendLine("    dw ${hex(state.setupAsmPtr)} ; Room setup ASM")
                    if (valid) {
                        section("${stateLabel}_Plms", "$condition · Objects (PLMs)", 0x8F0000 or state.plmSetPtr)
                        appendLine("; ${entries!!.size} object(s). Each record: word ID, byte tile X/Y, word parameter.")
                        if (states.count { it.plmSetPtr == state.plmSetPtr } > 1) {
                            appendLine("; Shared address: other states in this room use this same list (not independent copies).")
                        }
                        entries.forEach { entry ->
                            val name = RomParser.itemNameForPlm(entry.id) ?: RomParser.stationNameForPlm(entry.id)
                                ?: RomParser.doorCapNameForPlm(entry.id) ?: RomParser.scrollPlmName(entry.id)
                                ?: RomParser.gateNameForPlm(entry.id, entry.param) ?: "Object definition in bank \$84"
                            appendLine("    dw ${hex(entry.id)} ; ${clean(name)}")
                            appendLine("    db ${hex(entry.x, 2)}, ${hex(entry.y, 2)} ; Tile X, Y (zero-based)")
                            appendLine("    dw ${hex(entry.param)} ; Parameter (meaning depends on the PLM)")
                        }
                        appendLine("    dw \$0000 ; End of list, not another object")
                        appendLine("${stateLabel}_Plms_End:")
                    }
                }
            }
        }
    }
}

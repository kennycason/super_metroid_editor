package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomStateEdits
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SourceInitialScrollResource
import com.supermetroid.editor.rom.baseSourceStateIndex
import com.supermetroid.editor.rom.ensureStateManifest

internal data class AsmInitialScrollMigration(val text: String, val states: List<RoomStateEdits>)

/** Pure adoption plan, shared by individual grid edits and compound room transactions. */
internal fun planInitialScrollMigration(text: String, room: RoomEdits, parser: RomParser, roomName: String,
                                       symbols: Map<String, Int>, projectRoomIds: Map<Int, String> = emptyMap()): AsmInitialScrollMigration {
    require(room.asmInitialScrollSourceVersion == 0) { "Initial scrolls already have source ownership." }
    val staged = room.copy(states = room.states.toMutableList())
    staged.ensureStateManifest(parser)
    val header = requireNotNull(parser.readRoomHeader(room.roomId))
    val width = room.effectiveHeaderChange?.width ?: header.width
    val height = room.effectiveHeaderChange?.height ?: header.height
    val inspected = parser.inspectRoomStates(room.roomId).states
    val tables = linkedMapOf<Pair<Int, List<Int>>, SourceInitialScrollResource>()
    val prefix = AsmInitialScrollSource.prefix(room.roomId, projectRoomIds[room.roomId])
    require(!Regex("(?m)^macro ${prefix}").containsMatchIn(text)) { "Existing initial scroll declarations need review before adoption." }
    val addition = buildString {
        appendLine("; SMEDIT initial screen scrolls · ${roomName.replace('\n', ' ').replace('\r', ' ')}")
        appendLine("; Row-major db bytes: 00 Red, 01 Blue, 02 Green. No terminator.")
        appendLine("; State wrappers share tables; visual edits detach only the selected state.")
        for (state in staged.states) {
            val index = requireNotNull(state.baseSourceStateIndex()) { "Initial scroll state has no source/template." }
            val pc = requireNotNull(inspected.getOrNull(index)?.stateDataPcOffset)
            val pointer = parser.readStateData(pc).getValue("roomScrollsPtr")
            require(pointer in 0..1 || pointer in 0x8000..0xFFFF) { "Custom initial scroll presets need a matching codec; this field is not a ROM table address." }
            val original = parser.parseScrollData(pointer, header.width, header.height)
            val values = MutableList(width * height) { 1 }
            for (y in 0 until minOf(height, header.height)) for (x in 0 until minOf(width, header.width)) {
                values[y * width + x] = original[y * header.width + x]
            }
            for (change in room.scrollChanges + state.scrollChanges) {
                require(change.screenX in 0 until width && change.screenY in 0 until height && change.newValue in 0..2) { "Initial scroll edit is outside the grid." }
                values[change.screenY * width + change.screenX] = change.newValue
            }
            val table = tables.getOrPut(pointer to values.toList()) {
                SourceInitialScrollResource(prefix + "Table_${tables.size + 1}", width, height, values.toList()).also {
                    appendLine(AsmInitialScrollSource.renderTable(it))
                }
            }
            appendLine(AsmInitialScrollSource.renderState(room.roomId, state.id, table.identity, projectRoomIds[room.roomId]))
        }
    }
    val after = text.trimEnd() + "\n\n" + addition
    AsmInitialScrollSource(after, symbols, projectRoomIds).resources(staged.copy(asmInitialScrollSourceVersion = 1,
        scrollChanges = mutableListOf(), states = staged.states.map { it.copy(scrollChanges = mutableListOf()) }.toMutableList()), width, height)
    return AsmInitialScrollMigration(after, staged.states.toList())
}

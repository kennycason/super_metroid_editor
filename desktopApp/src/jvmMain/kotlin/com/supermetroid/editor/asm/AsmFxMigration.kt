package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.rom.*

/** Pure adoption planner shared by scalar FX editing and compound room themes. */
internal fun planFxMigration(before: String, room: RoomEdits, parser: RomParser, name: String,
                             projectRoomIds: Map<Int, String> = emptyMap()): String {
    require(room.asmFxSourceVersion == 0 && room.states.isNotEmpty()) { "Select native room states before adopting FX." }
    requireDoorRoutedFxOwnership(room, parser)
    val states = parser.inspectRoomStates(room.roomId).states
    val prefix = AsmFxSource.prefix(room.roomId, projectRoomIds[room.roomId])
    require(!Regex("(?m)^macro $prefix").containsMatchIn(before)) { "Existing FX declarations need review before adoption." }
    val tables = linkedMapOf<Pair<Int, List<RomParser.FxEntry>>, SourceFxResource>()
    val addition = buildString {
        appendLine("; SMEDIT room effects · ${name.replace('\n', ' ').replace('\r', ' ')}")
        appendLine("; Four dw words + eight db bytes per header. First matching incoming door wins.")
        appendLine("; A complete Default header ends lookup; otherwise dw FFFF means no FX.")
        for (state in room.states) {
            val pc = requireNotNull(states.getOrNull(requireNotNull(state.baseSourceStateIndex()))?.stateDataPcOffset)
            val pointer = parser.readStateData(pc).getValue("fxPtr")
            val entries = readSourceFxResource(parser, pointer, "FX room $name").entries.toMutableList()
            fun applyDefault(change: com.supermetroid.editor.data.FxChange?) {
                if (change == null) return
                val index = entries.indexOfFirst { it.doorSelect == 0 }
                val original = entries.getOrNull(index) ?: emptyDefaultFxEntry()
                val changed = original.withFxChange(change)
                if (index >= 0) entries[index] = changed else if (changed != original) entries.add(changed)
            }
            applyDefault(room.fxChange)
            applyDefault(state.fxChange)
            for ((key, change) in state.doorFxChanges) {
                val door = requireNotNull(key.toIntOrNull(16)) { "Invalid door FX key." }
                val index = entries.indexOfFirst { it.doorSelect == door }
                require(door != 0 && index >= 0) { "GUI door FX override has no matching native header." }
                entries[index] = entries[index].withFxChange(change)
            }
            val resource = tables.getOrPut(pointer to entries.toList()) {
                SourceFxResource(prefix + "Table_${tables.size + 1}", entries.toList()).also {
                    validateSourceFxDoors(room.roomId, it, parser)
                    appendLine(AsmFxSource.renderTable(it))
                }
            }
            appendLine(AsmFxSource.renderState(room.roomId, state.id, resource.identity, projectRoomIds[room.roomId]))
        }
    }
    return before.trimEnd() + "\n\n" + addition
}

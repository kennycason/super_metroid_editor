package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomParser

internal enum class RoomAsmSourceKind(val title: String, val fileId: String = PLM_SOURCE_FILE) {
    LAYOUT("Layout ASM"), PROPERTIES("Room header / state fields"), SELECTORS("State selectors ASM"),
    DOORS("Door connections ASM", DOOR_SOURCE_FILE),
    INITIAL_SCROLLS("Initial scrolls ASM"), FX("FX ASM", FX_SOURCE_FILE),
    ENEMIES("Enemy population ASM", ENEMY_SOURCE_FILE), ENEMY_GRAPHICS("Enemy graphics ASM", ENEMY_SOURCE_FILE),
    PLMS("Object placements ASM"), SAVE_SPAWN("Save spawn ASM"),
}

internal data class RoomAsmSourceRequest(
    val kind: RoomAsmSourceKind,
    val stateId: String? = null,
    val plm: RomParser.PlmEntry? = null,
    val enemy: RomParser.EnemyEntry? = null,
    val door: Int? = null,
    val sourceDoor: com.supermetroid.editor.data.ProjectSourceDoorReference? = null,
)

/** Show only the planned declarations, unless planning also changes existing source. */
internal fun roomSourceAddition(before: String, after: String): String =
    after.removePrefix(before.trimEnd()).trimStart()

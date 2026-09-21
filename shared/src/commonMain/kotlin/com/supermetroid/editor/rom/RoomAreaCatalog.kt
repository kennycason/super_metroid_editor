package com.supermetroid.editor.rom

/** Canonical names and bounds for Super Metroid's room-area byte. */
object RoomAreaCatalog {
    const val PAUSE_MAP_AREA_COUNT = 7
    const val ROOM_AREA_COUNT = 8

    val names: List<String> = listOf(
        "Crateria",
        "Brinstar",
        "Norfair",
        "Wrecked Ship",
        "Maridia",
        "Tourian",
        "Ceres",
        "Debug / Unused",
    )

    val pauseMapNames: List<String> = names.take(PAUSE_MAP_AREA_COUNT)

    fun name(area: Int): String = names.getOrNull(area) ?: "Area $area"
}

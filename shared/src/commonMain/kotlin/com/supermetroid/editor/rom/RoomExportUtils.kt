package com.supermetroid.editor.rom

import com.supermetroid.editor.data.RoomRepository

fun Int.toSigned16(): Int {
    val value = this and 0xFFFF
    return if (value >= 0x8000) value - 0x10000 else value
}

fun Int.toUnsigned16(): Int = this and 0xFFFF

private val NORMAL_ENEMY_GFX_VRAM_DESTINATIONS = listOf(1, 2, 3, 7)

fun selectEnemyGfxVramDestination(
    existingEntries: List<RomParser.EnemyGfxEntry>,
    vanillaDestinations: List<Int>,
): Int? {
    val usedRows = existingEntries.map { it.paletteIndex and 0xFF }.toSet()
    val vanillaNormal = vanillaDestinations
        .filter { (it and 0xFF) in NORMAL_ENEMY_GFX_VRAM_DESTINATIONS }
        .distinct()

    return vanillaNormal.firstOrNull { (it and 0xFF) !in usedRows }
        ?: NORMAL_ENEMY_GFX_VRAM_DESTINATIONS.firstOrNull { it !in usedRows }
        ?: vanillaDestinations.distinct().firstOrNull()
}

fun collectVanillaEnemyGfxDestinations(
    romParser: RomParser,
    roomRepository: RoomRepository = RoomRepository(),
): Map<Int, List<Int>> {
    val bySpecies = linkedMapOf<Int, MutableList<Int>>()
    val seenGfxPtrs = mutableSetOf<Int>()

    for (roomInfo in roomRepository.getAllRooms()) {
        val roomId = roomInfo.getRoomIdAsInt()
        val stateGfxPtrs = romParser.parseRoomStatesWithData(roomId)
            .map { it.enemyGfxPtr }
            .distinct()
        for (gfxPtr in stateGfxPtrs) {
            if (gfxPtr == 0 || gfxPtr == 0xFFFF || !seenGfxPtrs.add(gfxPtr)) continue
            for (entry in romParser.parseEnemyGfxSet(gfxPtr)) {
                val destinations = bySpecies.getOrPut(entry.speciesId) { mutableListOf() }
                if (entry.paletteIndex !in destinations) destinations.add(entry.paletteIndex)
            }
        }
    }

    return bySpecies.mapValues { it.value.toList() }
}

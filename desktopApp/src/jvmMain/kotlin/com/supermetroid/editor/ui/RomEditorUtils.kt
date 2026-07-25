package com.supermetroid.editor.ui

import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.RomParser

internal fun bytesSha256(bytes: List<Int>): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    for (b in bytes) digest.update((b and 0xFF).toByte())
    return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

internal fun Int.toSigned16(): Int {
    val v = this and 0xFFFF
    return if (v >= 0x8000) v - 0x10000 else v
}

internal fun Int.toUnsigned16(): Int = this and 0xFFFF

private val NORMAL_ENEMY_GFX_VRAM_DESTINATIONS = listOf(1, 2, 3, 7)

internal fun selectEnemyGfxVramDestination(
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

internal fun collectVanillaEnemyGfxDestinations(
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

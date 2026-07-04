package com.supermetroid.editor.procgen

import com.supermetroid.editor.data.Room
import com.supermetroid.editor.rom.RomParser

/**
 * Caches learned [TilesetProfile] instances by tileset id. Learning only
 * decompresses rooms that share the requested tileset.
 */
object TilesetProfileCache {
    private val cache = mutableMapOf<Int, TilesetProfile>()

    fun getOrLearn(romParser: RomParser, rooms: List<Room>, tilesetId: Int): TilesetProfile =
        cache.getOrPut(tilesetId) {
            val matching = rooms.filter { it.tileset == tilesetId && it.levelDataPtr != 0 }
            TilesetProfile.learn(romParser, matching, tilesetId)
        }

    fun invalidate() {
        cache.clear()
    }
}

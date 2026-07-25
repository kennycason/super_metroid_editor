package com.supermetroid.editor.procgen

import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomInfo

object BiomeRoomEligibility {
    fun shouldSkipBulkBiomeRoom(roomInfo: RoomInfo, room: Room): Boolean {
        val haystack = "${roomInfo.name} ${roomInfo.handle} ${room.name} ${room.handle}"
            .lowercase()
            .replace("'", "")
            .replace(" ", "")
        return EXCLUDED_NAME_TOKENS.any { it in haystack }
    }

    private val EXCLUDED_NAME_TOKENS = listOf(
        "save",
        "savestation",
        "station",
        "recharge",
        "refill",
        "healthrefill",
        "energycharge",
        "missilestation",
        "maproom",
        "premap",
        "boss",
        "kraid",
        "phantoon",
        "draygon",
        "ridley",
        "torizo",
        "sporespawn",
        "crocomire",
        "botwoon",
        "motherbrain",
        "goldentorizo",
        "ceres",
        "escape",
        "statue",
    )
}

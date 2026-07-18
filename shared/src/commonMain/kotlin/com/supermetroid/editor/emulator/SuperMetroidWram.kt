package com.supermetroid.editor.emulator

import kotlinx.serialization.Serializable

@Serializable
data class SuperMetroidFrameState(
    val roomId: Int? = null,
    val gameState: Int? = null,
    val samusX: Int? = null,
    val samusXSubpixel: Int? = null,
    val samusY: Int? = null,
    val samusYSubpixel: Int? = null,
    val samusHorizontalSpeedPixels: Int? = null,
    val samusHorizontalSpeedSubpixels: Int? = null,
    val samusVerticalSpeedPixels: Int? = null,
    val samusVerticalSpeedSubpixels: Int? = null,
    val health: Int? = null,
    val doorTransition: Boolean = false,
) {
    fun differingFields(actual: SuperMetroidFrameState): List<String> {
        val fields = mutableListOf<String>()
        if (roomId != actual.roomId) fields += "roomId"
        if (gameState != actual.gameState) fields += "gameState"
        if (samusX != actual.samusX) fields += "samusX"
        if (samusXSubpixel != actual.samusXSubpixel) fields += "samusXSubpixel"
        if (samusY != actual.samusY) fields += "samusY"
        if (samusYSubpixel != actual.samusYSubpixel) fields += "samusYSubpixel"
        if (samusHorizontalSpeedPixels != actual.samusHorizontalSpeedPixels) fields += "samusHorizontalSpeedPixels"
        if (samusHorizontalSpeedSubpixels != actual.samusHorizontalSpeedSubpixels) {
            fields += "samusHorizontalSpeedSubpixels"
        }
        if (samusVerticalSpeedPixels != actual.samusVerticalSpeedPixels) fields += "samusVerticalSpeedPixels"
        if (samusVerticalSpeedSubpixels != actual.samusVerticalSpeedSubpixels) fields += "samusVerticalSpeedSubpixels"
        if (health != actual.health) fields += "health"
        if (doorTransition != actual.doorTransition) fields += "doorTransition"
        return fields
    }
}

object SuperMetroidWram {
    const val ROOM_ID = 0x079B
    const val GAME_STATE = 0x0998
    const val HEALTH = 0x09C2
    const val SAMUS_X = 0x0AF6
    const val SAMUS_X_SUBPIXEL = 0x0AF8
    const val SAMUS_Y = 0x0AFA
    const val SAMUS_Y_SUBPIXEL = 0x0AFC
    const val SAMUS_VERTICAL_SPEED_SUBPIXELS = 0x0B2C
    const val SAMUS_VERTICAL_SPEED_PIXELS = 0x0B2E
    const val SAMUS_HORIZONTAL_SPEED_PIXELS = 0x0B42
    const val SAMUS_HORIZONTAL_SPEED_SUBPIXELS = 0x0B44

    private val recordedWordOffsets = intArrayOf(
        ROOM_ID,
        GAME_STATE,
        HEALTH,
        SAMUS_X,
        SAMUS_X_SUBPIXEL,
        SAMUS_Y,
        SAMUS_Y_SUBPIXEL,
        SAMUS_VERTICAL_SPEED_SUBPIXELS,
        SAMUS_VERTICAL_SPEED_PIXELS,
        SAMUS_HORIZONTAL_SPEED_PIXELS,
        SAMUS_HORIZONTAL_SPEED_SUBPIXELS,
    )

    val minimumFrameRecordBytes: Int = recordedWordOffsets.maxOrNull()!! + 2

    fun frameState(wram: ByteArray): SuperMetroidFrameState {
        val gameState = wram.readWordOrNull(GAME_STATE)
        return SuperMetroidFrameState(
            roomId = wram.readWordOrNull(ROOM_ID),
            gameState = gameState,
            samusX = wram.readWordOrNull(SAMUS_X),
            samusXSubpixel = wram.readWordOrNull(SAMUS_X_SUBPIXEL),
            samusY = wram.readWordOrNull(SAMUS_Y),
            samusYSubpixel = wram.readWordOrNull(SAMUS_Y_SUBPIXEL),
            samusHorizontalSpeedPixels = wram.readWordOrNull(SAMUS_HORIZONTAL_SPEED_PIXELS),
            samusHorizontalSpeedSubpixels = wram.readWordOrNull(SAMUS_HORIZONTAL_SPEED_SUBPIXELS),
            samusVerticalSpeedPixels = wram.readWordOrNull(SAMUS_VERTICAL_SPEED_PIXELS),
            samusVerticalSpeedSubpixels = wram.readWordOrNull(SAMUS_VERTICAL_SPEED_SUBPIXELS),
            health = wram.readWordOrNull(HEALTH),
            doorTransition = gameState?.let(::isDoorTransitionGameState) ?: false,
        )
    }

    fun isDoorTransitionGameState(gameState: Int): Boolean = gameState in 0x09..0x0B

    private fun ByteArray.readWordOrNull(offset: Int): Int? {
        if (offset < 0 || offset + 1 >= size) return null
        return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    }
}

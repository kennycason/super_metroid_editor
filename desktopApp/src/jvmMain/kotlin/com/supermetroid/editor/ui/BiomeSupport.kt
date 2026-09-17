package com.supermetroid.editor.ui

import com.supermetroid.editor.data.EditOperation
import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.procgen.BiomeGenerationRect
import com.supermetroid.editor.procgen.BiomeRoomEligibility
import com.supermetroid.editor.procgen.BiomeTheme
import com.supermetroid.editor.rom.RomParser

internal const val GENERATED_BIOME_PREFIX = "Generated biome:"

internal data class RoomGrids(
    val width: Int,
    val height: Int,
    val words: IntArray,
    val bts: IntArray,
)

internal fun isGeneratedBiomeOperation(op: EditOperation): Boolean =
    op.description.startsWith(GENERATED_BIOME_PREFIX) ||
        op.description.startsWith("Generate biome (")

internal fun stripGeneratedBiomeEdits(roomEdits: RoomEdits?): Boolean {
    if (roomEdits == null) return false
    var stripped = false
    for (state in roomEdits.states) {
        val generatedStateOps = state.operations.filter { isGeneratedBiomeOperation(it) }
        if (generatedStateOps.isEmpty()) continue
        state.operations.removeAll(generatedStateOps.toSet())
        for (op in generatedStateOps) {
            if (op.stateDataBefore != op.stateDataAfter && state.stateDataChange == op.stateDataAfter) {
                state.stateDataChange = op.stateDataBefore
            }
            if (op.fxBefore != op.fxAfter && state.fxChange == op.fxAfter) state.fxChange = op.fxBefore
            state.scrollChanges.removeAll { change ->
                op.scrollEdits.any {
                    it.screenX == change.screenX && it.screenY == change.screenY && it.newValue == change.newValue
                }
            }
            state.plmChanges.removeAll { change -> change in op.plmRemoves }
        }
        stripped = true
    }
    val generatedOps = roomEdits.operations.filter { isGeneratedBiomeOperation(it) }
    if (generatedOps.isEmpty()) return stripped
    roomEdits.operations.removeAll(generatedOps.toSet())
    for (op in generatedOps) {
        if (op.stateDataBefore != op.stateDataAfter && roomEdits.stateDataChange == op.stateDataAfter) {
            roomEdits.stateDataChange = op.stateDataBefore
        }
        if (op.fxBefore != op.fxAfter && roomEdits.fxChange == op.fxAfter) {
            roomEdits.fxChange = op.fxBefore
        }
        for (sc in op.scrollEdits) {
            roomEdits.scrollChanges.removeAll {
                it.screenX == sc.screenX && it.screenY == sc.screenY && it.newValue == sc.newValue
            }
        }
        for (plm in op.plmRemoves) {
            roomEdits.plmChanges.removeAll {
                it.action == plm.action && it.plmId == plm.plmId &&
                    it.x == plm.x && it.y == plm.y && it.param == plm.param
            }
        }
    }
    return true
}

internal fun hasManualBiomeBlockingEdits(roomEdits: RoomEdits?): Boolean {
    if (roomEdits == null) return false
    if (roomEdits.states.any { state ->
            state.operations.any { !isGeneratedBiomeOperation(it) } ||
                state.enemyChanges.isNotEmpty() || state.customScrollCommands.isNotEmpty() ||
                state.conditionChanged || state.resourcesChanged ||
                state.operations.isEmpty() && (
                    state.plmChanges.isNotEmpty() || state.scrollChanges.isNotEmpty() ||
                        state.stateDataChange != null || state.fxChange != null
                    )
        }
    ) return true
    val generatedOps = roomEdits.operations.filter { isGeneratedBiomeOperation(it) }
    if (roomEdits.operations.any { !isGeneratedBiomeOperation(it) }) return true
    if (roomEdits.doorChanges.isNotEmpty() ||
        roomEdits.enemyChanges.isNotEmpty() ||
        roomEdits.roomHeaderChange != null ||
        roomEdits.customScrollCommands.isNotEmpty() ||
        roomEdits.saveStationSpawns.isNotEmpty()
    ) return true

    val generatedScrollChanges = generatedOps.flatMap { it.scrollEdits }
    if (roomEdits.scrollChanges.any { it !in generatedScrollChanges }) return true

    val generatedPlmChanges = generatedOps.flatMap { it.plmAdds + it.plmRemoves }
    if (roomEdits.plmChanges.any { it !in generatedPlmChanges }) return true

    val generatedStateChanges = generatedOps.mapNotNull { it.stateDataAfter }
    if (roomEdits.stateDataChange != null && roomEdits.stateDataChange !in generatedStateChanges) return true

    val generatedFxChanges = generatedOps.mapNotNull { it.fxAfter }
    if (roomEdits.fxChange != null && roomEdits.fxChange !in generatedFxChanges) return true

    return false
}

internal fun shouldSkipBulkBiomeRoom(roomInfo: RoomInfo, room: Room): Boolean =
    BiomeRoomEligibility.shouldSkipBulkBiomeRoom(roomInfo, room)

internal fun applyBulkBiomeThemeToRoom(
    roomEdits: RoomEdits,
    romRoom: Room,
    effectiveRoom: Room,
    theme: BiomeTheme,
) {
    val targetTileset = theme.tilesetId
    if (targetTileset != null) {
        val existing = roomEdits.stateDataChange ?: StateDataChange()
        val change = existing.copy(tileset = targetTileset.takeIf { it != romRoom.tileset })
        roomEdits.stateDataChange = change.takeIf { it != StateDataChange() }
    }
    if (theme.fxType != null) {
        val existing = roomEdits.fxChange ?: FxChange()
        roomEdits.fxChange = if (theme.isLiquid) {
            val heightPx = effectiveRoom.height * 16 * 16
            val surface = (heightPx * theme.liquidFraction).toInt()
                .coerceIn(0x20, maxOf(0x20, heightPx - 0x20))
            existing.copy(
                fxType = theme.fxType,
                liquidSurfaceStart = surface,
                liquidSurfaceNew = surface,
                liquidSpeed = 0,
                liquidDelay = 0,
                fxBitA = 0x02,
                fxBitB = 0x02,
                fxBitC = 0,
            )
        } else {
            existing.copy(fxType = theme.fxType, liquidSurfaceStart = 0xFFFF, liquidSurfaceNew = 0xFFFF)
        }
    }
}

internal fun addLandingSiteShipProtection(
    preserveRects: MutableList<BiomeGenerationRect>,
    forceAirRects: MutableList<BiomeGenerationRect>,
) {
    preserveRects.add(BiomeGenerationRect(58, 60, 84, 73))
    forceAirRects.add(BiomeGenerationRect(55, 57, 87, 76))
}

internal fun addElevatorProtectionForRoom(
    preserveRects: MutableList<BiomeGenerationRect>,
    hardForceAirRects: MutableList<BiomeGenerationRect>,
    romParser: RomParser?,
    roomId: Int,
    width: Int,
    height: Int,
) {
    if (romParser == null || roomId == 0 || width <= 0 || height <= 0) return
    for (door in romParser.findDoorsLeadingTo(roomId).filter { it.isElevator }) {
        addElevatorProtectionForDoor(preserveRects, hardForceAirRects, door, width, height)
    }
}

internal fun addElevatorProtectionForDoor(
    preserveRects: MutableList<BiomeGenerationRect>,
    hardForceAirRects: MutableList<BiomeGenerationRect>,
    door: RomParser.DoorEntry,
    width: Int,
    height: Int,
) {
    val screenX0 = door.screenX * 16
    val screenY0 = door.screenY * 16
    if (screenX0 !in 0 until width || screenY0 !in 0 until height) return

    val centerLeftX = screenX0 + 7
    val centerRightX = screenX0 + 8
    val centerTopY = screenY0 + 7
    val centerBottomY = screenY0 + 8
    val verticalClearLeftX = centerLeftX - 1
    val verticalClearRightX = centerRightX + 1
    val horizontalClearTopY = centerTopY - 1
    val horizontalClearBottomY = centerBottomY + 1
    val elevatorClearanceDepth = 5

    fun addPreserve(rect: BiomeGenerationRect) {
        if (rect.x1 >= 0 && rect.y1 >= 0 && rect.x0 < width && rect.y0 < height) preserveRects.add(rect)
    }
    fun addHardForceAir(rect: BiomeGenerationRect) {
        if (rect.x1 >= 0 && rect.y1 >= 0 && rect.x0 < width && rect.y0 < height) hardForceAirRects.add(rect)
    }

    when (door.direction and 0x03) {
        2 -> {
            val doorY = screenY0
            addPreserve(BiomeGenerationRect(centerLeftX - 4, doorY, centerRightX + 4, doorY))
            addHardForceAir(BiomeGenerationRect(verticalClearLeftX, doorY + 1, verticalClearRightX, doorY + elevatorClearanceDepth))
        }
        3 -> {
            val doorY = minOf(screenY0 + 15, height - 1)
            addPreserve(BiomeGenerationRect(centerLeftX - 4, doorY, centerRightX + 4, doorY))
            addHardForceAir(BiomeGenerationRect(verticalClearLeftX, doorY - elevatorClearanceDepth, verticalClearRightX, doorY - 1))
        }
        0 -> {
            val doorX = screenX0
            addPreserve(BiomeGenerationRect(doorX, centerTopY - 4, doorX, centerBottomY + 4))
            addHardForceAir(BiomeGenerationRect(doorX + 1, horizontalClearTopY, doorX + elevatorClearanceDepth, horizontalClearBottomY))
        }
        1 -> {
            val doorX = minOf(screenX0 + 15, width - 1)
            addPreserve(BiomeGenerationRect(doorX, centerTopY - 4, doorX, centerBottomY + 4))
            addHardForceAir(BiomeGenerationRect(doorX - elevatorClearanceDepth, horizontalClearTopY, doorX - 1, horizontalClearBottomY))
        }
    }
}

internal fun doorCapRect(x: Int, y: Int, horizontal: Boolean): BiomeGenerationRect =
    if (horizontal) BiomeGenerationRect(x - 1, y - 2, x + 4, y + 2)
    else BiomeGenerationRect(x - 2, y - 1, x + 2, y + 4)

internal fun buildDoorCapPreserveRectsForRoom(
    romParser: RomParser?,
    roomId: Int,
    width: Int,
    height: Int,
    plms: List<RomParser.PlmEntry>,
): List<BiomeGenerationRect> {
    val rects = ArrayList<BiomeGenerationRect>()
    for (plm in plms) {
        if (!RomParser.isDoorCapPlm(plm.id)) continue
        rects.add(doorCapRect(plm.x, plm.y, RomParser.doorCapIsHorizontal(plm.id)))
    }
    for (door in romParser?.findDoorsLeadingTo(roomId).orEmpty()) {
        val x = door.doorCapCode and 0xFF
        val y = (door.doorCapCode shr 8) and 0xFF
        if (x !in 0 until width || y !in 0 until height) continue
        val horizontal = (door.direction and 0x03) == 2 || (door.direction and 0x03) == 3
        rects.add(doorCapRect(x, y, horizontal))
    }
    return rects
}

package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MapStationTileEdit
import com.supermetroid.editor.data.MinimapTileEdit
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.MapStationData
import com.supermetroid.editor.rom.MinimapData
import com.supermetroid.editor.rom.MinimapTiles
import com.supermetroid.editor.rom.RomParser

internal fun effectiveMinimapData(
    parser: RomParser,
    project: SmEditProject,
    area: Int,
): MinimapData {
    var result = parser.readMinimapTiles(area)
    for (edit in project.minimapEdits[area.toString()].orEmpty()) {
        result = result.withTile(edit.x, edit.y, edit.tileWord)
    }
    return result
}

internal fun persistMinimapData(
    parser: RomParser,
    project: SmEditProject,
    data: MinimapData,
) {
    val baseline = parser.readMinimapTiles(data.area)
    val edits = mutableListOf<MinimapTileEdit>()
    for (y in 0 until MinimapData.MAP_HEIGHT) {
        for (x in 0 until MinimapData.MAP_WIDTH) {
            val current = data.getTile(x, y)
            if (current != baseline.getTile(x, y)) {
                edits.add(MinimapTileEdit(x, y, current))
            }
        }
    }
    if (edits.isEmpty()) project.minimapEdits.remove(data.area.toString())
    else project.minimapEdits[data.area.toString()] = edits
}

internal fun effectiveMapStationData(
    parser: RomParser,
    project: SmEditProject,
    area: Int,
): MapStationData {
    var result = parser.readMapStationData(area)
    for (edit in project.mapStationEdits[area.toString()].orEmpty()) {
        result = result.withValue(edit.x, edit.y, edit.revealed)
    }
    return result
}

internal fun persistMapStationData(
    parser: RomParser,
    project: SmEditProject,
    data: MapStationData,
) {
    val baseline = parser.readMapStationData(data.area)
    val edits = mutableListOf<MapStationTileEdit>()
    for (y in 0 until MinimapData.MAP_HEIGHT) {
        for (x in 0 until MinimapData.MAP_WIDTH) {
            val revealed = data.isRevealed(x, y)
            if (revealed != baseline.isRevealed(x, y)) {
                edits.add(MapStationTileEdit(x, y, revealed))
            }
        }
    }
    if (edits.isEmpty()) project.mapStationEdits.remove(data.area.toString())
    else project.mapStationEdits[data.area.toString()] = edits
}

internal fun isEmptyMinimapTile(word: Int): Boolean {
    val index = MinimapData.tileIndex(word)
    return word == 0 || index == MinimapTiles.EMPTY
}

internal fun rectanglesOverlap(
    ax: Int,
    ay: Int,
    aw: Int,
    ah: Int,
    bx: Int,
    by: Int,
    bw: Int,
    bh: Int,
): Boolean = ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by

/**
 * Resolve map-cell ownership for intentionally nested room rectangles.
 *
 * Vanilla maps contain small rooms inside larger bounding rectangles (for
 * example, Bowling Alley Path inside West Ocean). The uniquely smallest room
 * covering a cell owns it. Equal-size ties remain unowned so an ambiguous cell
 * is preserved rather than destructively attributed to either room.
 */
internal fun roomMapOwnershipMask(room: Room, areaRooms: List<Room>): Array<BooleanArray> =
    Array(room.height) { ry ->
        BooleanArray(room.width) { rx ->
            val mapX = room.mapX + rx
            val mapY = room.mapY + ry
            val covering = areaRooms.filter { candidate ->
                candidate.area == room.area &&
                    mapX in candidate.mapX until (candidate.mapX + candidate.width) &&
                    mapY in candidate.mapY until (candidate.mapY + candidate.height)
            }
            val smallestArea = covering.minOfOrNull { it.width * it.height }
            val smallest = covering.filter { it.width * it.height == smallestArea }
            smallest.size == 1 && smallest.single().roomId == room.roomId
        }
    }

/**
 * Find the nearest destination where a room footprint owns both the room
 * rectangle and the underlying minimap/station cells. Cross-area moves cannot
 * assume that the same coordinates are free in two unrelated area maps.
 */
internal fun findAvailableRoomMapPosition(
    mapData: MinimapData,
    stationData: MapStationData,
    rooms: List<com.supermetroid.editor.data.Room>,
    width: Int,
    height: Int,
    preferredX: Int,
    preferredY: Int,
): Pair<Int, Int>? {
    if (width !in 1..MinimapData.MAP_WIDTH || height !in 1..MinimapData.MAP_HEIGHT) return null
    val maxX = MinimapData.MAP_WIDTH - width
    val maxY = MinimapData.MAP_HEIGHT - height
    val candidates = buildList {
        for (y in 0..maxY) for (x in 0..maxX) add(x to y)
    }.sortedWith(
        compareBy<Pair<Int, Int>>(
            { kotlin.math.abs(it.first - preferredX) + kotlin.math.abs(it.second - preferredY) },
            { it.second },
            { it.first },
        ),
    )
    return candidates.firstOrNull { (x, y) ->
        rooms.none { room ->
            rectanglesOverlap(x, y, width, height, room.mapX, room.mapY, room.width, room.height)
        } && (0 until height).none { ry ->
            (0 until width).any { rx ->
                !isEmptyMinimapTile(mapData.getTile(x + rx, y + ry)) ||
                    stationData.isRevealed(x + rx, y + ry)
            }
        }
    }
}

internal fun MapStationData.withValue(x: Int, y: Int, revealed: Boolean): MapStationData {
    if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) return this
    val index = y * MinimapData.MAP_WIDTH + x
    if (this.revealed[index] == revealed) return this
    val updated = this.revealed.copyOf()
    updated[index] = revealed
    return copy(revealed = updated)
}

internal fun clearMapStationRect(
    data: MapStationData,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    ownershipMask: Array<BooleanArray>? = null,
): MapStationData {
    val updated = data.revealed.copyOf()
    for (ry in 0 until height) for (rx in 0 until width) {
        if (ownershipMask != null && ownershipMask.getOrNull(ry)?.getOrNull(rx) != true) continue
        val mapX = x + rx
        val mapY = y + ry
        if (mapX in 0 until MinimapData.MAP_WIDTH && mapY in 0 until MinimapData.MAP_HEIGHT) {
            updated[mapY * MinimapData.MAP_WIDTH + mapX] = false
        }
    }
    return data.copy(revealed = updated)
}

internal fun extractMapStationRect(
    data: MapStationData,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    ownershipMask: Array<BooleanArray>? = null,
): Array<BooleanArray> = Array(height) { ry ->
    BooleanArray(width) { rx ->
        (ownershipMask == null || ownershipMask.getOrNull(ry)?.getOrNull(rx) == true) &&
            data.isRevealed(x + rx, y + ry)
    }
}

internal fun placeMapStationRect(
    data: MapStationData,
    x: Int,
    y: Int,
    values: Array<BooleanArray>,
): MapStationData {
    val updated = data.revealed.copyOf()
    for (ry in values.indices) for (rx in values[ry].indices) {
        val mapX = x + rx
        val mapY = y + ry
        if (mapX in 0 until MinimapData.MAP_WIDTH && mapY in 0 until MinimapData.MAP_HEIGHT) {
            updated[mapY * MinimapData.MAP_WIDTH + mapX] = values[ry][rx]
        }
    }
    return data.copy(revealed = updated)
}

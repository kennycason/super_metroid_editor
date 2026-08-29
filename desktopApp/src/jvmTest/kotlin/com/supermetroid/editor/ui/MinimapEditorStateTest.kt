package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.MinimapData
import com.supermetroid.editor.rom.MinimapTiles
import com.supermetroid.editor.rom.ProjectRoomExporter
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomValidator
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MinimapEditorStateTest {

    private data class PointerMoveCandidate(
        val room: com.supermetroid.editor.data.Room,
        val grabX: Int,
        val grabY: Int,
        val dx: Int,
        val dy: Int,
    )

    private fun findPointerMoveCandidate(parser: RomParser): PointerMoveCandidate? {
        val rooms = parser.roomCatalog.rooms.mapNotNull { info ->
            parser.readRoomHeader(info.getRoomIdAsInt())
        }
        return rooms.firstNotNullOfOrNull { room ->
            val areaRooms = rooms.filter { it.area == room.area }
            val grab = (0 until room.height).firstNotNullOfOrNull { ry ->
                (0 until room.width).firstNotNullOfOrNull { rx ->
                    val x = room.mapX + rx
                    val y = room.mapY + ry
                    (rx to ry).takeIf {
                        areaRooms.count { other ->
                            x in other.mapX until (other.mapX + other.width) &&
                                y in other.mapY until (other.mapY + other.height)
                        } == 1
                    }
                }
            } ?: return@firstNotNullOfOrNull null
            val map = parser.readMinimapTiles(room.area)
            listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).firstNotNullOfOrNull direction@ { (dx, dy) ->
                val newX = room.mapX + dx
                val newY = room.mapY + dy
                if (newX !in 0..(MinimapData.MAP_WIDTH - room.width) ||
                    newY !in 0..(MinimapData.MAP_HEIGHT - room.height)
                ) return@direction null
                if (areaRooms.any { other ->
                        other.roomId != room.roomId && rectanglesOverlap(
                            newX, newY, room.width, room.height,
                            other.mapX, other.mapY, other.width, other.height,
                        )
                    }
                ) return@direction null
                val hasTileConflict = (0 until room.height).any { ry ->
                    (0 until room.width).any { rx ->
                        val x = newX + rx
                        val y = newY + ry
                        val insideSource = x in room.mapX until (room.mapX + room.width) &&
                            y in room.mapY until (room.mapY + room.height)
                        !insideSource && !isEmptyMinimapTile(map.getTile(x, y))
                    }
                }
                if (hasTileConflict) null
                else PointerMoveCandidate(room, grab.first, grab.second, dx, dy)
            }
        }
    }

    @Nested
    inner class Initialization {
        @Test
        fun `room outlines are opt in by default`() {
            assertFalse(MinimapEditorState().showRoomOutlines)
        }

        @Test
        fun `edit on map initializes tile graphics before the minimap tab has opened`() {
            val parser = TestRomHelper.loadRomParser()
            assumeTrue(parser != null, "Test ROM not found")
            parser!!
            val roomId = 0x91F8
            val room = parser.readRoomHeader(roomId) ?: return
            val editor = EditorState().also {
                it.testMode = true
                it.initForRom("/tmp/minimap-edit-on-map-test.smc")
            }
            val state = MinimapEditorState()
            assertEquals(null, state.tileGraphics)

            state.openRoom(parser, roomId, editor)

            assertTrue(state.tileGraphics != null)
            assertEquals(room.area, state.selectedArea)
            assertTrue(state.mapData.tiles.any { !isEmptyMinimapTile(it) })
            assertEquals(roomId, state.selectedRoom?.roomId)
        }
    }

    @Nested
    inner class FloodFill {
        @Test
        fun `flood fill replaces connected tiles with same value`() {
            val tiles = IntArray(MinimapData.TILE_COUNT)
            // Create a 3x3 block of tile 0x1B at position (5,5)
            for (y in 5..7) for (x in 5..7) tiles[y * MinimapData.MAP_WIDTH + x] = 0x1B
            val data = MinimapData(0, tiles)
            val result = floodFillMinimap(data, 5, 5, 0x1B, 0x20)
            // All 9 tiles should be replaced
            for (y in 5..7) for (x in 5..7) assertEquals(0x20, result.getTile(x, y))
            // Adjacent tile should NOT be replaced
            assertEquals(0, result.getTile(4, 5))
        }

        @Test
        fun `flood fill does not replace different tiles`() {
            val tiles = IntArray(MinimapData.TILE_COUNT)
            tiles[5 * MinimapData.MAP_WIDTH + 5] = 0x1B
            tiles[5 * MinimapData.MAP_WIDTH + 6] = 0x20 // different
            tiles[5 * MinimapData.MAP_WIDTH + 7] = 0x1B
            val data = MinimapData(0, tiles)
            val result = floodFillMinimap(data, 5, 5, 0x1B, 0xFF)
            assertEquals(0xFF, result.getTile(5, 5))
            assertEquals(0x20, result.getTile(6, 5)) // barrier
            assertEquals(0x1B, result.getTile(7, 5)) // disconnected
        }

        @Test
        fun `flood fill with same target and replacement is no-op`() {
            val tiles = IntArray(MinimapData.TILE_COUNT)
            tiles[0] = 0x1B
            val data = MinimapData(0, tiles)
            // In practice the caller guards this, but test the function directly
            val result = floodFillMinimap(data, 0, 0, 0x1B, 0x1B)
            assertEquals(0x1B, result.getTile(0, 0))
        }
    }

    @Nested
    inner class TileWordEncoding {
        @Test
        fun `makeTileWord encodes tile index and palette correctly`() {
            val word = MinimapData.makeTileWord(0x1B, 3)
            assertEquals(0x1B, MinimapData.tileIndex(word))
            assertEquals(3, MinimapData.tilePalette(word))
        }

        @Test
        fun `flip bits are extracted correctly`() {
            val word = 0xC01B // hflip + vflip + tile 0x1B
            assertEquals(true, MinimapData.tileHFlip(word))
            assertEquals(true, MinimapData.tileVFlip(word))
            assertEquals(0x1B, MinimapData.tileIndex(word))
        }

        @Test
        fun `no flip bits when not set`() {
            val word = MinimapData.makeTileWord(0x20, 1)
            assertEquals(false, MinimapData.tileHFlip(word))
            assertEquals(false, MinimapData.tileVFlip(word))
        }
    }

    @Nested
    inner class MinimapDataModel {
        @Test
        fun `withTile creates new data with changed tile`() {
            val data = MinimapData.empty(0)
            val updated = data.withTile(10, 5, 0x1B)
            assertEquals(0x1B, updated.getTile(10, 5))
            assertEquals(0, data.getTile(10, 5)) // original unchanged
        }

        @Test
        fun `getTile returns 0 for out of bounds`() {
            val data = MinimapData.empty(0)
            assertEquals(0, data.getTile(-1, 0))
            assertEquals(0, data.getTile(0, -1))
            assertEquals(0, data.getTile(MinimapData.MAP_WIDTH, 0))
            assertEquals(0, data.getTile(0, MinimapData.MAP_HEIGHT))
        }
    }

    @Nested
    inner class ShiftRoomTiles {
        @Test
        fun `shifts tiles right and down`() {
            val tiles = IntArray(MinimapData.TILE_COUNT)
            // Place a 2x2 room at (5,5)
            tiles[5 * MinimapData.MAP_WIDTH + 5] = 0xAA
            tiles[5 * MinimapData.MAP_WIDTH + 6] = 0xBB
            tiles[6 * MinimapData.MAP_WIDTH + 5] = 0xCC
            tiles[6 * MinimapData.MAP_WIDTH + 6] = 0xDD
            val data = MinimapData(0, tiles)
            val result = shiftRoomTiles(data, 5, 5, 2, 2, 1, 1)
            // Old positions cleared (getTile is x, y)
            assertEquals(MinimapTiles.EMPTY, result.getTile(5, 5), "old (5,5) should be cleared")
            assertEquals(MinimapTiles.EMPTY, result.getTile(6, 5), "old (6,5) should be cleared")
            assertEquals(MinimapTiles.EMPTY, result.getTile(5, 6), "old (5,6) should be cleared")
            // New positions have the tiles (shifted by +1,+1)
            assertEquals(0xAA, result.getTile(6, 6), "tile from (5,5) should be at (6,6)")
            assertEquals(0xBB, result.getTile(7, 6), "tile from (6,5) should be at (7,6)")
            assertEquals(0xCC, result.getTile(6, 7), "tile from (5,6) should be at (6,7)")
            assertEquals(0xDD, result.getTile(7, 7), "tile from (6,6) should be at (7,7)")
        }

        @Test
        fun `extract and place round-trips correctly`() {
            val tiles = IntArray(MinimapData.TILE_COUNT)
            tiles[5 * MinimapData.MAP_WIDTH + 5] = 0xAA
            tiles[5 * MinimapData.MAP_WIDTH + 6] = 0xBB
            val data = MinimapData(0, tiles)
            val extracted = extractRoomTiles(data, 5, 5, 2, 1)
            assertEquals(0xAA, extracted[0][0])
            assertEquals(0xBB, extracted[0][1])
            val cleared = clearRoomTiles(data, 5, 5, 2, 1)
            assertEquals(MinimapTiles.EMPTY, cleared.getTile(5, 5))
            assertEquals(MinimapTiles.EMPTY, cleared.getTile(6, 5))
            val placed = placeRoomTilesNonEmpty(cleared, 10, 10, 2, 1, extracted)
            assertEquals(0xAA, placed.getTile(10, 10))
            assertEquals(0xBB, placed.getTile(11, 10))
        }

        @Test
        fun `buffered move does not overwrite other room tiles`() {
            val tiles = IntArray(MinimapData.TILE_COUNT)
            // Room A at (5,5) size 2x1
            tiles[5 * MinimapData.MAP_WIDTH + 5] = 0xAA
            tiles[5 * MinimapData.MAP_WIDTH + 6] = 0xBB
            // Room B at (7,5) size 1x1
            tiles[5 * MinimapData.MAP_WIDTH + 7] = 0xFF
            val data = MinimapData(0, tiles)
            // Lift Room A tiles
            val extracted = extractRoomTiles(data, 5, 5, 2, 1)
            val cleared = clearRoomTiles(data, 5, 5, 2, 1)
            // Room B should still be there
            assertEquals(0xFF, cleared.getTile(7, 5))
            // Place Room A at (6,5) — overlapping with Room B at (7,5)
            val placed = placeRoomTilesNonEmpty(cleared, 6, 5, 2, 1, extracted)
            // Room A placed, Room B overwritten (expected — user must Apply to commit)
            assertEquals(0xAA, placed.getTile(6, 5))
            assertEquals(0xBB, placed.getTile(7, 5))
        }

        @Test
        fun `does not corrupt tiles outside room bounds`() {
            val tiles = IntArray(MinimapData.TILE_COUNT)
            tiles[3 * MinimapData.MAP_WIDTH + 3] = 0xFF // outside room
            tiles[5 * MinimapData.MAP_WIDTH + 5] = 0xAA // inside room
            val data = MinimapData(0, tiles)
            val result = shiftRoomTiles(data, 5, 5, 1, 1, -1, 0)
            assertEquals(0xFF, result.getTile(3, 3)) // untouched
            assertEquals(0xAA, result.getTile(4, 5)) // shifted left
            assertEquals(MinimapTiles.EMPTY, result.getTile(5, 5)) // old position cleared
        }
    }

    @Nested
    inner class TileTransforms {
        @Test
        fun `horizontal and vertical flip bits survive tile word encoding`() {
            val word = MinimapData.makeTileWord(0x25, palette = 2, hFlip = true, vFlip = true)

            assertEquals(0x25, MinimapData.tileIndex(word))
            assertEquals(2, MinimapData.tilePalette(word))
            assertEquals(true, MinimapData.tileHFlip(word))
            assertEquals(true, MinimapData.tileVFlip(word))
        }

        @Test
        fun `rotation chooses an exact representable tile and preserves palette`() {
            val graphics = Array(256) { IntArray(64) }
            // Asymmetric L shape in tile 1.
            for (y in 1..6) graphics[1][y * 8 + 1] = 3
            for (x in 1..5) graphics[1][6 * 8 + x] = 3
            // Exact clockwise rotation in tile 2.
            for (y in 0 until 8) for (x in 0 until 8) {
                graphics[2][y * 8 + x] = graphics[1][(7 - x) * 8 + y]
            }

            val rotated = rotateMinimapTileWord(MinimapData.makeTileWord(1, palette = 3), graphics, clockwise = true)

            assertNotEquals(null, rotated)
            assertEquals(3, rotated?.let(MinimapData::tilePalette))
        }

        @Test
        fun `rotation rejects a tile with no exact ROM representation`() {
            val graphics = Array(256) { IntArray(64) }
            graphics[1][1 * 8 + 2] = 7
            graphics[1][5 * 8 + 6] = 4

            assertEquals(null, rotateMinimapTileWord(MinimapData.makeTileWord(1), graphics, clockwise = true))
        }
    }

    @Nested
    inner class AreaReassignment {
        @Test
        fun `safe area move migrates map data and fixes connected door flags`() {
            val parser = TestRomHelper.loadRomParser()
            assumeTrue(parser != null, "Test ROM not found")
            parser!!
            val rooms = parser.roomCatalog.rooms.mapNotNull { info ->
                parser.readRoomHeader(info.getRoomIdAsInt())
            }
            val saveRooms = buildSet {
                for (area in 0 until MinimapData.NUM_AREAS) {
                    for (index in 0 until parser.saveEntryCount(area)) {
                        parser.readSaveEntry(area, index)?.roomId?.let(::add)
                    }
                }
            }
            val maps = (0 until MinimapData.NUM_AREAS).associateWith(parser::readMinimapTiles)
            val candidate = rooms.firstNotNullOfOrNull { room ->
                if (room.roomId in saveRooms || parser.getAllPlmEntriesForRoom(room.roomId).any { it.id == 0xB76F }) {
                    return@firstNotNullOfOrNull null
                }
                val sourceOverlap = rooms.any { other ->
                    other.roomId != room.roomId && other.area == room.area && rectanglesOverlap(
                        room.mapX, room.mapY, room.width, room.height,
                        other.mapX, other.mapY, other.width, other.height,
                    )
                }
                if (sourceOverlap) return@firstNotNullOfOrNull null
                (0 until MinimapData.NUM_AREAS).firstOrNull { target ->
                    target != room.area &&
                        rooms.none { other ->
                            other.roomId != room.roomId && other.area == target && rectanglesOverlap(
                                room.mapX, room.mapY, room.width, room.height,
                                other.mapX, other.mapY, other.width, other.height,
                            )
                        } &&
                        (0 until room.height).none { ry ->
                            (0 until room.width).any { rx ->
                                !isEmptyMinimapTile(maps.getValue(target).getTile(room.mapX + rx, room.mapY + ry))
                            }
                        }
                }?.let { room to it }
            }
            assumeTrue(candidate != null, "No safely movable vanilla room fixture found")
            val (room, targetArea) = candidate!!
            val sourceMap = maps.getValue(room.area)
            val sourceStation = parser.readMapStationData(room.area)
            val state = EditorState().also {
                it.testMode = true
                it.initForRom("/tmp/minimap-area-reassignment-test.smc")
            }
            state.loadRoom(room.roomId, parser, room)
            val openMinimap = MinimapEditorState().also {
                it.initIfNeeded(parser, state)
                it.loadArea(parser, room.area, state, selectRoomId = room.roomId)
            }

            // A popup command must retain the room it was opened for even if
            // popup/canvas input changes the live selection before onClick.
            val contextRoomId = openMinimap.selectedRoom!!.roomId
            openMinimap.clearRoomSelection(state)
            val result = openMinimap.reassignRoomArea(contextRoomId, targetArea, state)!!

            assertTrue(result.success, result.message)
            assertEquals(targetArea, state.activeRoomAreaForEditing())
            assertEquals(targetArea, state.project.rooms[state.project.roomKey(room.roomId)]?.roomHeaderChange?.area)
            assertEquals(room.area, openMinimap.selectedArea)
            assertEquals(null, openMinimap.selectedRoom)
            assertEquals(null, openMinimap.pendingStroke)
            assertEquals(null, openMinimap.moveBuffer)
            assertTrue(openMinimap.areaRooms.none { it.roomId == room.roomId })
            val movedSource = effectiveMinimapData(parser, state.project, room.area)
            val movedDestination = effectiveMinimapData(parser, state.project, targetArea)
            val movedSourceStation = effectiveMapStationData(parser, state.project, room.area)
            val movedDestinationStation = effectiveMapStationData(parser, state.project, targetArea)
            val destinationX = result.destinationMapX!!
            val destinationY = result.destinationMapY!!
            openMinimap.initIfNeeded(parser, state)
            assertArrayEquals(movedSource.tiles, openMinimap.mapData.tiles)
            assertArrayEquals(movedSource.tiles, openMinimap.displayData.tiles)
            assertArrayEquals(movedSourceStation.revealed, openMinimap.stationData.revealed)
            assertTrue(openMinimap.areaRooms.none { it.roomId == room.roomId })
            for (ry in 0 until room.height) for (rx in 0 until room.width) {
                val x = room.mapX + rx
                val y = room.mapY + ry
                assertTrue(isEmptyMinimapTile(movedSource.getTile(x, y)))
                val original = sourceMap.getTile(x, y)
                if (!isEmptyMinimapTile(original)) {
                    assertEquals(original, movedDestination.getTile(destinationX + rx, destinationY + ry))
                }
                assertFalse(movedSourceStation.isRevealed(x, y))
                assertEquals(
                    sourceStation.isRevealed(x, y),
                    movedDestinationStation.isRevealed(destinationX + rx, destinationY + ry),
                )
            }

            for (sourceRoom in rooms) {
                val changes = state.project.rooms[state.project.roomKey(sourceRoom.roomId)]?.doorChanges.orEmpty()
                    .associateBy { it.doorIndex }
                for ((index, romDoor) in parser.parseDoorList(sourceRoom.doorOut).withIndex()) {
                    val saved = changes[index]
                    val destinationId = saved?.destRoomPtr ?: romDoor.destRoomPtr
                    if (sourceRoom.roomId != room.roomId && destinationId != room.roomId) continue
                    val bitflag = saved?.bitflag ?: romDoor.bitflag
                    val sourceArea = if (sourceRoom.roomId == room.roomId) targetArea else sourceRoom.area
                    val destination = rooms.first { it.roomId == destinationId }
                    val destinationArea = if (destinationId == room.roomId) targetArea else destination.area
                    assertEquals(sourceArea != destinationArea, bitflag and 0x40 != 0)
                }
            }
            for (door in state.doorEntries) {
                val destination = rooms.first { it.roomId == door.destRoomPtr }
                val destinationArea = if (destination.roomId == room.roomId) targetArea else destination.area
                assertEquals(targetArea != destinationArea, door.bitflag and 0x40 != 0)
            }

            val exportedBytes = parser.getRomData().copyOf()
            ProjectRoomExporter(state.project, RomParser(exportedBytes), exportedBytes).exportRooms()
            val exported = RomParser(exportedBytes)
            assertEquals(targetArea, exported.readRoomHeader(room.roomId)?.area)
            for (sourceRoom in rooms) {
                for (door in exported.parseDoorList(sourceRoom.doorOut)) {
                    if (sourceRoom.roomId != room.roomId && door.destRoomPtr != room.roomId) continue
                    val sourceArea = exported.readRoomHeader(sourceRoom.roomId)?.area ?: sourceRoom.area
                    val destinationArea = exported.readRoomHeader(door.destRoomPtr)?.area ?: continue
                    assertEquals(sourceArea != destinationArea, door.bitflag and 0x40 != 0)
                }
            }

            // Desktop write planning captures each room independently. Ensure
            // those isolated exporters still see the moved destination's area.
            val isolatedBytes = parser.getRomData().copyOf()
            val areaOverrides = state.project.rooms.mapNotNull { (key, edits) ->
                val roomId = key.toIntOrNull(16) ?: return@mapNotNull null
                edits.roomHeaderChange?.area?.let { roomId to it }
            }.toMap()
            for ((key, edits) in state.project.rooms) {
                if (!edits.hasEdits) continue
                val oneRoomProject = state.project.copy(rooms = mutableMapOf(key to edits))
                ProjectRoomExporter(
                    project = oneRoomProject,
                    romParser = RomParser(isolatedBytes),
                    romData = isolatedBytes,
                    roomAreaOverrides = areaOverrides,
                ).exportRooms()
            }
            val isolated = RomParser(isolatedBytes)
            for (sourceRoom in rooms) {
                for (door in isolated.parseDoorList(sourceRoom.doorOut)) {
                    if (sourceRoom.roomId != room.roomId && door.destRoomPtr != room.roomId) continue
                    val sourceArea = isolated.readRoomHeader(sourceRoom.roomId)?.area ?: sourceRoom.area
                    val destinationArea = isolated.readRoomHeader(door.destRoomPtr)?.area ?: continue
                    assertEquals(sourceArea != destinationArea, door.bitflag and 0x40 != 0)
                }
            }
        }

        @Test
        fun `occupied destination coordinates relocate Terminator and remove its source rendering`() {
            val parser = TestRomHelper.loadRomParser()
            assumeTrue(parser != null, "Test ROM not found")
            parser!!
            val room = parser.readRoomHeader(0x990D)
            assumeTrue(room != null, "Terminator Room fixture not found")
            room!!
            val targetArea = 1 // Brinstar: Terminator's original coordinates are heavily occupied.
            val rooms = parser.roomCatalog.rooms.mapNotNull { info ->
                parser.readRoomHeader(info.getRoomIdAsInt())
            }
            assertTrue(rooms.any { other ->
                other.area == targetArea && rectanglesOverlap(
                    room.mapX, room.mapY, room.width, room.height,
                    other.mapX, other.mapY, other.width, other.height,
                )
            })
            val sourceBefore = parser.readMinimapTiles(room.area)
            val sourceTiles = extractRoomTiles(
                sourceBefore,
                room.mapX,
                room.mapY,
                room.width,
                room.height,
            )
            val editor = EditorState().also {
                it.testMode = true
                it.initForRom("/tmp/minimap-terminator-to-brinstar-test.smc")
            }
            val minimap = MinimapEditorState().also {
                it.initIfNeeded(parser, editor)
                it.loadArea(parser, room.area, editor, selectRoomId = room.roomId)
            }

            val result = minimap.reassignRoomArea(room.roomId, targetArea, editor)!!

            assertTrue(result.success, result.message)
            val destinationX = result.destinationMapX!!
            val destinationY = result.destinationMapY!!
            assertTrue(destinationX != room.mapX || destinationY != room.mapY)
            val movedRoom = editor.applyHeaderChanges(room)
            assertEquals(targetArea, movedRoom.area)
            assertEquals(destinationX, movedRoom.mapX)
            assertEquals(destinationY, movedRoom.mapY)
            assertEquals(room.area, minimap.selectedArea)
            assertEquals(null, minimap.selectedRoom)
            assertTrue(minimap.areaRooms.none { it.roomId == room.roomId })
            for (ry in 0 until room.height) for (rx in 0 until room.width) {
                assertTrue(isEmptyMinimapTile(minimap.displayData.getTile(room.mapX + rx, room.mapY + ry)))
                val original = sourceTiles[ry][rx]
                if (!isEmptyMinimapTile(original)) {
                    assertEquals(
                        original,
                        effectiveMinimapData(parser, editor.project, targetArea)
                            .getTile(destinationX + rx, destinationY + ry),
                    )
                }
            }
            assertTrue(rooms.none { other ->
                other.area == targetArea && rectanglesOverlap(
                    destinationX, destinationY, room.width, room.height,
                    other.mapX, other.mapY, other.width, other.height,
                )
            })
        }

        @Test
        fun `nested source rooms do not block West Ocean area reassignment`() {
            val parser = TestRomHelper.loadRomParser()
            assumeTrue(parser != null, "Test ROM not found")
            parser!!
            val room = parser.readRoomHeader(0x93FE)
            assumeTrue(room != null, "West Ocean fixture not found")
            room!!
            val targetArea = 1
            val rooms = parser.roomCatalog.rooms.mapNotNull { info ->
                parser.readRoomHeader(info.getRoomIdAsInt())
            }
            val sourceRooms = rooms.filter { it.area == room.area }
            val nestedRooms = sourceRooms.filter { other ->
                other.roomId != room.roomId && rectanglesOverlap(
                    room.mapX, room.mapY, room.width, room.height,
                    other.mapX, other.mapY, other.width, other.height,
                )
            }
            assertTrue(nestedRooms.any { it.roomId == 0x9461 }, "Bowling Alley Path must overlap West Ocean")
            assertTrue(nestedRooms.any { it.roomId == 0x968F }, "Crateria Partial Room must overlap West Ocean")
            val ownership = roomMapOwnershipMask(room, sourceRooms)
            for (nested in nestedRooms) {
                for (y in nested.mapY until (nested.mapY + nested.height)) {
                    for (x in nested.mapX until (nested.mapX + nested.width)) {
                        if (x in room.mapX until (room.mapX + room.width) &&
                            y in room.mapY until (room.mapY + room.height)
                        ) {
                            assertFalse(ownership[y - room.mapY][x - room.mapX])
                        }
                    }
                }
            }
            val sourceBefore = parser.readMinimapTiles(room.area)
            val editor = EditorState().also {
                it.testMode = true
                it.initForRom("/tmp/minimap-west-ocean-area-test.smc")
            }
            val minimap = MinimapEditorState().also {
                it.initIfNeeded(parser, editor)
                it.loadArea(parser, room.area, editor, selectRoomId = room.roomId)
            }

            val result = minimap.reassignRoomArea(room.roomId, targetArea, editor)!!

            assertTrue(result.success, result.message)
            val destinationX = result.destinationMapX!!
            val destinationY = result.destinationMapY!!
            val sourceAfter = effectiveMinimapData(parser, editor.project, room.area)
            val destinationAfter = effectiveMinimapData(parser, editor.project, targetArea)
            assertEquals(targetArea, editor.applyHeaderChanges(room).area)
            assertEquals(null, minimap.selectedRoom)
            assertTrue(minimap.areaRooms.none { it.roomId == room.roomId })
            for (ry in 0 until room.height) for (rx in 0 until room.width) {
                val sourceX = room.mapX + rx
                val sourceY = room.mapY + ry
                val original = sourceBefore.getTile(sourceX, sourceY)
                if (ownership[ry][rx]) {
                    assertTrue(isEmptyMinimapTile(sourceAfter.getTile(sourceX, sourceY)))
                    if (!isEmptyMinimapTile(original)) {
                        assertEquals(original, destinationAfter.getTile(destinationX + rx, destinationY + ry))
                    }
                } else {
                    assertEquals(original, sourceAfter.getTile(sourceX, sourceY))
                }
            }
        }

        @Test
        fun `Frog Savestation migrates its AreaSave slot and PLM across areas`() {
            val parser = TestRomHelper.loadRomParser()
            assumeTrue(parser != null, "Test ROM not found")
            parser!!
            val room = parser.readRoomHeader(0xB167)
            assumeTrue(room != null, "Frog Savestation fixture not found")
            room!!
            val targetArea = 1
            val sourcePlm = parser.getAllPlmEntriesForRoom(room.roomId).single { it.id == 0xB76F }
            val sourceIndex = sourcePlm.param and 0xFF
            val sourceEntry = parser.readSaveEntry(room.area, sourceIndex)!!
            assertEquals(room.roomId, sourceEntry.roomId)
            val editor = EditorState().also {
                it.testMode = true
                it.initForRom("/tmp/minimap-frog-save-area-test.smc")
            }
            editor.loadRoom(room.roomId, parser, room)
            val minimap = MinimapEditorState().also {
                it.initIfNeeded(parser, editor)
                it.loadArea(parser, room.area, editor, selectRoomId = room.roomId)
            }

            val result = minimap.reassignRoomArea(room.roomId, targetArea, editor)!!

            assertTrue(result.success, result.message)
            val edits = editor.project.rooms.getValue(editor.project.roomKey(room.roomId))
            val clearedSource = edits.saveStationSpawns.single {
                it.clearSlot && it.area == room.area && it.saveIndex == sourceIndex
            }
            assertEquals(0, clearedSource.roomId)
            val destinationSpawn = edits.saveStationSpawns.single {
                !it.clearSlot && it.area == targetArea && it.roomId == room.roomId
            }
            assertEquals(sourceEntry.doorPtr, destinationSpawn.doorPtr)
            assertEquals(sourceEntry.scrollX, destinationSpawn.scrollX)
            assertEquals(sourceEntry.scrollY, destinationSpawn.scrollY)
            assertEquals(sourceEntry.samusX, destinationSpawn.samusX)
            assertEquals(sourceEntry.samusY, destinationSpawn.samusY)
            assertTrue(edits.plmChanges.any {
                it.action == "remove" && it.plmId == 0xB76F && it.x == sourcePlm.x && it.y == sourcePlm.y
            })
            assertTrue(edits.plmChanges.any {
                it.action == "add" && it.plmId == 0xB76F &&
                    (it.param and 0xFF) == destinationSpawn.saveIndex
            })
            val rooms = parser.roomCatalog.rooms.mapNotNull { info ->
                parser.readRoomHeader(info.getRoomIdAsInt())
            }.associateBy { it.roomId }
            assertTrue(
                RomValidator.checkProjectRoomHeaders(parser, editor.project, rooms)
                    .none { it.severity == RomValidator.Severity.ERROR && it.category == "AreaSave" },
            )
            assertTrue(
                RomValidator.checkProjectSaveStationSpawns(parser, editor.project, rooms)
                    .none { it.severity == RomValidator.Severity.ERROR },
            )

            minimap.undo(editor)
            val undone = editor.project.rooms[editor.project.roomKey(room.roomId)]
            assertEquals(null, undone?.roomHeaderChange)
            assertTrue(undone?.plmChanges.orEmpty().isEmpty())
            assertTrue(undone?.saveStationSpawns.orEmpty().isEmpty())

            minimap.redo(editor)
            val redone = editor.project.rooms.getValue(editor.project.roomKey(room.roomId))
            val redoneDestination = redone.saveStationSpawns.single { !it.clearSlot }
            val exportedBytes = parser.getRomData().copyOf()
            ProjectRoomExporter(editor.project, RomParser(exportedBytes), exportedBytes).exportRooms()
            val exported = RomParser(exportedBytes)
            assertEquals(targetArea, exported.readRoomHeader(room.roomId)?.area)
            assertEquals(0, exported.readSaveEntry(room.area, sourceIndex)?.roomId)
            val exportedDestination = exported.readSaveEntry(targetArea, redoneDestination.saveIndex)!!
            assertEquals(room.roomId, exportedDestination.roomId)
            assertEquals(sourceEntry.doorPtr, exportedDestination.doorPtr)
            val exportedSavePlm = exported.getAllPlmEntriesForRoom(room.roomId).single { it.id == 0xB76F }
            assertEquals(redoneDestination.saveIndex, exportedSavePlm.param and 0xFF)
        }

        @Test
        fun `Frog Savestation repairs a legacy mismatched PLM index while changing areas`() {
            val parser = TestRomHelper.loadRomParser()
            assumeTrue(parser != null, "Test ROM not found")
            parser!!
            val room = parser.readRoomHeader(0xB167)
            assumeTrue(room != null, "Frog Savestation fixture not found")
            room!!
            val sourcePlm = parser.getAllPlmEntriesForRoom(room.roomId).single { it.id == 0xB76F }
            val sourceIndex = sourcePlm.param and 0xFF
            val sourceEntry = parser.readSaveEntry(room.area, sourceIndex)!!
            val legacyMismatchedIndex = (0 until parser.saveEntryCount(room.area)).first { index ->
                index != sourceIndex && parser.readSaveEntry(room.area, index)?.roomId != room.roomId
            }
            val legacyParam = (sourcePlm.param and 0xFF00) or 0x8000 or legacyMismatchedIndex
            val editor = EditorState().also {
                it.testMode = true
                it.initForRom("/tmp/minimap-frog-legacy-save-area-test.smc")
            }
            val edits = editor.project.getOrCreateRoom(room.roomId)
            edits.plmChanges.add(
                com.supermetroid.editor.data.PlmChange(
                    "remove", sourcePlm.id, sourcePlm.x, sourcePlm.y, sourcePlm.param,
                ),
            )
            edits.plmChanges.add(
                com.supermetroid.editor.data.PlmChange(
                    "add", sourcePlm.id, sourcePlm.x, sourcePlm.y, legacyParam,
                ),
            )
            editor.loadRoom(room.roomId, parser, room)
            val minimap = MinimapEditorState().also {
                it.initIfNeeded(parser, editor)
                it.loadArea(parser, room.area, editor, selectRoomId = room.roomId)
            }

            val result = minimap.reassignRoomArea(room.roomId, 1, editor)!!

            assertTrue(result.success, result.message)
            val migrated = editor.project.rooms.getValue(editor.project.roomKey(room.roomId))
            val destination = migrated.saveStationSpawns.single {
                !it.clearSlot && it.area == 1 && it.roomId == room.roomId
            }
            assertTrue(migrated.saveStationSpawns.any {
                it.clearSlot && it.area == room.area && it.saveIndex == sourceIndex
            })
            assertEquals(sourceEntry.doorPtr, destination.doorPtr)
            assertTrue(migrated.plmChanges.any {
                it.action == "remove" && it.plmId == 0xB76F && it.param == legacyParam
            })
            assertTrue(migrated.plmChanges.any {
                it.action == "add" && it.plmId == 0xB76F &&
                    (it.param and 0xFF) == destination.saveIndex
            })

            val exportedBytes = parser.getRomData().copyOf()
            ProjectRoomExporter(editor.project, RomParser(exportedBytes), exportedBytes).exportRooms()
            val exported = RomParser(exportedBytes)
            assertEquals(0, exported.readSaveEntry(room.area, sourceIndex)?.roomId)
            assertEquals(room.roomId, exported.readSaveEntry(1, destination.saveIndex)?.roomId)
            val exportedSavePlm = exported.getAllPlmEntriesForRoom(room.roomId).single { it.id == 0xB76F }
            assertEquals(destination.saveIndex, exportedSavePlm.param and 0xFF)
        }

        @Test
        fun `area move is blocked for an AreaSave room`() {
            val parser = TestRomHelper.loadRomParser()
            assumeTrue(parser != null, "Test ROM not found")
            parser!!
            val saveRoomId = (0 until MinimapData.NUM_AREAS).firstNotNullOfOrNull { area ->
                (0 until parser.saveEntryCount(area)).firstNotNullOfOrNull { index ->
                    parser.readSaveEntry(area, index)?.roomId
                }
            }
            assumeTrue(saveRoomId != null, "No AreaSave room fixture found")
            val room = parser.readRoomHeader(saveRoomId!!)!!
            val targetArea = (room.area + 1) % MinimapData.NUM_AREAS
            val state = EditorState().also {
                it.testMode = true
                it.initForRom("/tmp/minimap-area-save-block-test.smc")
            }
            val minimap = MinimapEditorState().also {
                it.initIfNeeded(parser, state)
                it.loadArea(parser, room.area, state, selectRoomId = room.roomId)
            }

            val result = minimap.reassignRoomArea(room.roomId, targetArea, state)!!

            assertFalse(result.success)
            assertTrue(result.message.contains("AreaSave"))
            assertEquals(result.message, minimap.areaReassignmentError)
            minimap.dismissAreaReassignmentError()
            assertEquals(null, minimap.areaReassignmentError)
            assertEquals(null, state.project.rooms[state.project.roomKey(room.roomId)]?.roomHeaderChange)
            assertTrue(state.project.minimapEdits.isEmpty())
            assertTrue(state.project.mapStationEdits.isEmpty())
        }
    }

    @Nested
    inner class RoomMovementSafety {
        @Test
        fun `primary drag in select mode moves and deselects on release`() {
            val parser = TestRomHelper.loadRomParser()
            assumeTrue(parser != null, "Test ROM not found")
            parser!!
            val candidate = findPointerMoveCandidate(parser)
            assumeTrue(candidate != null, "No safely movable pointer-room fixture found")
            val (room, grabX, grabY, dx, dy) = candidate!!
            val editor = EditorState().also {
                it.testMode = true
                it.initForRom("/tmp/minimap-primary-drag-test.smc")
            }
            val state = MinimapEditorState()
            state.initIfNeeded(parser, editor)
            state.loadArea(parser, room.area, editor)

            assertTrue(state.beginPrimaryRoomDrag(room.mapX + grabX, room.mapY + grabY, editor))

            assertTrue(state.isPrimaryDraggingRoom)
            assertTrue(state.isMovingRoom)
            assertEquals(room.roomId, state.selectedRoom?.roomId)

            state.updatePointerRoomMove(room.mapX + dx + grabX, room.mapY + dy + grabY)
            assertEquals(room.mapX + dx, state.moveBuffer?.currentX)
            assertEquals(room.mapY + dy, state.moveBuffer?.currentY)

            state.endPrimaryRoomDrag(room.mapX + dx + grabX, room.mapY + dy + grabY, editor)

            assertFalse(state.isPrimaryDraggingRoom)
            assertFalse(state.isMovingRoom)
            assertEquals(null, state.selectedRoom)
            val moved = editor.applyHeaderChanges(room)
            assertEquals(room.mapX + dx, moved.mapX)
            assertEquals(room.mapY + dy, moved.mapY)
            assertTrue(state.undoStack.isNotEmpty())
        }

        @Test
        fun `primary click without drag does not leave a room following the pointer`() {
            val parser = TestRomHelper.loadRomParser()
            assumeTrue(parser != null, "Test ROM not found")
            parser!!
            val candidate = findPointerMoveCandidate(parser)
            assumeTrue(candidate != null, "No selectable room fixture found")
            val (room, grabX, grabY) = candidate!!
            val editor = EditorState().also {
                it.testMode = true
                it.initForRom("/tmp/minimap-primary-click-test.smc")
            }
            val state = MinimapEditorState()
            state.initIfNeeded(parser, editor)
            state.loadArea(parser, room.area, editor)
            val originalMap = state.mapData.tiles.copyOf()
            val originalStation = state.stationData.revealed.copyOf()

            assertTrue(state.beginPrimaryRoomDrag(room.mapX + grabX, room.mapY + grabY, editor))
            state.endPrimaryRoomDrag(room.mapX + grabX, room.mapY + grabY, editor)

            assertFalse(state.isPointerDraggingRoom)
            assertFalse(state.isMovingRoom)
            assertEquals(null, state.selectedRoom)
            assertArrayEquals(originalMap, state.mapData.tiles)
            assertArrayEquals(originalStation, state.stationData.revealed)
            assertTrue(editor.project.minimapEdits.isEmpty())
            assertTrue(editor.project.mapStationEdits.isEmpty())
        }

        @Test
        fun `middle drag moves from a paint tool and deselects on release`() {
            val parser = TestRomHelper.loadRomParser()
            assumeTrue(parser != null, "Test ROM not found")
            parser!!
            val candidate = findPointerMoveCandidate(parser)
            assumeTrue(candidate != null, "No safely movable middle-drag fixture found")
            val (room, grabX, grabY, dx, dy) = candidate!!
            val editor = EditorState().also {
                it.testMode = true
                it.initForRom("/tmp/minimap-middle-drag-test.smc")
            }
            val state = MinimapEditorState()
            state.initIfNeeded(parser, editor)
            state.loadArea(parser, room.area, editor)
            state.activateTool(MinimapTool.PAINT, editor)

            assertTrue(state.beginMiddleRoomDrag(room.mapX + grabX, room.mapY + grabY, editor))
            state.updatePointerRoomMove(room.mapX + dx + grabX, room.mapY + dy + grabY)
            state.endMiddleRoomDrag(room.mapX + dx + grabX, room.mapY + dy + grabY, editor)

            assertEquals(MinimapTool.PAINT, state.tool)
            assertFalse(state.isMiddleDraggingRoom)
            assertFalse(state.isMovingRoom)
            assertEquals(null, state.selectedRoom)
            val moved = editor.applyHeaderChanges(room)
            assertEquals(room.mapX + dx, moved.mapX)
            assertEquals(room.mapY + dy, moved.mapY)
        }

        @Test
        fun `changing away from select cancels floating room and clears selection`() {
            val parser = TestRomHelper.loadRomParser()
            assumeTrue(parser != null, "Test ROM not found")
            parser!!
            val candidate = findPointerMoveCandidate(parser)
            assumeTrue(candidate != null, "No selectable room fixture found")
            val (room, grabX, grabY) = candidate!!
            val editor = EditorState().also {
                it.testMode = true
                it.initForRom("/tmp/minimap-tool-change-cancel-test.smc")
            }
            val state = MinimapEditorState()
            state.initIfNeeded(parser, editor)
            state.loadArea(parser, room.area, editor)
            val originalMap = state.mapData.tiles.copyOf()
            val originalStation = state.stationData.revealed.copyOf()
            assertTrue(state.beginPrimaryRoomDrag(room.mapX + grabX, room.mapY + grabY, editor))
            assertTrue(state.isMovingRoom)

            state.activateTool(MinimapTool.ERASE, editor)

            assertEquals(MinimapTool.ERASE, state.tool)
            assertFalse(state.isMovingRoom)
            assertFalse(state.isPrimaryDraggingRoom)
            assertEquals(null, state.selectedRoom)
            assertArrayEquals(originalMap, state.mapData.tiles)
            assertArrayEquals(originalStation, state.stationData.revealed)
            assertTrue(editor.project.minimapEdits.isEmpty())
            assertTrue(editor.project.mapStationEdits.isEmpty())
        }

        @Test
        fun `selecting an overlapped room restores the rejected moving room`() {
            val parser = TestRomHelper.loadRomParser()
            assumeTrue(parser != null, "Test ROM not found")
            parser!!
            val rooms = parser.roomCatalog.rooms.mapNotNull { info ->
                parser.readRoomHeader(info.getRoomIdAsInt())
            }
            val candidate = rooms.groupBy { it.area }.values.firstNotNullOfOrNull { areaRooms ->
                areaRooms.firstNotNullOfOrNull { source ->
                    areaRooms.firstOrNull { target ->
                        target.roomId != source.roomId &&
                            target.mapX <= MinimapData.MAP_WIDTH - source.width &&
                            target.mapY <= MinimapData.MAP_HEIGHT - source.height &&
                            !rectanglesOverlap(
                                source.mapX, source.mapY, source.width, source.height,
                                target.mapX, target.mapY, target.width, target.height,
                            ) &&
                            areaRooms.count { room ->
                                target.mapX in room.mapX until (room.mapX + room.width) &&
                                    target.mapY in room.mapY until (room.mapY + room.height)
                            } == 1
                    }?.let { source to it }
                }
            }
            assumeTrue(candidate != null, "No non-overlapping same-area room pair found")
            val (source, target) = candidate!!
            val editor = EditorState().also {
                it.testMode = true
                it.initForRom("/tmp/minimap-overlap-selection-test.smc")
            }
            val state = MinimapEditorState()
            state.initIfNeeded(parser, editor)
            state.loadArea(parser, source.area, editor, selectRoomId = source.roomId)
            val originalMap = state.mapData.tiles.copyOf()
            val originalStation = state.stationData.revealed.copyOf()

            state.moveRoom(target.mapX - source.mapX, target.mapY - source.mapY, editor)
            assertTrue(state.isMovingRoom)

            state.selectRoomAt(target.mapX, target.mapY, editor)

            assertFalse(state.isMovingRoom)
            assertEquals(target.roomId, state.selectedRoom?.roomId)
            assertArrayEquals(originalMap, state.mapData.tiles)
            assertArrayEquals(originalStation, state.stationData.revealed)
            assertTrue(editor.project.minimapEdits.isEmpty())
            assertTrue(editor.project.mapStationEdits.isEmpty())
        }
    }

    @Nested
    inner class History {
        @Test
        fun `external room edit invalidates minimap history instead of rolling it back`() {
            val parser = TestRomHelper.loadRomParser()
            assumeTrue(parser != null, "Test ROM not found")
            parser!!
            val editor = EditorState().also {
                it.testMode = true
                it.initForRom("/tmp/minimap-external-history-test.smc")
            }
            val state = MinimapEditorState()
            state.initIfNeeded(parser, editor)
            val paintIndex = state.mapData.tiles.indexOfFirst { it != state.selectedTileWord }
            assumeTrue(paintIndex >= 0)
            state.paintTile(paintIndex % MinimapData.MAP_WIDTH, paintIndex / MinimapData.MAP_WIDTH, editor)
            assertTrue(state.undoStack.isNotEmpty())

            editor.notifyProjectMutation(parser)
            state.initIfNeeded(parser, editor)

            assertTrue(state.undoStack.isEmpty())
            assertTrue(state.redoStack.isEmpty())
        }

        @Test
        fun `room move undo and redo include header map and station data`() {
            val parser = TestRomHelper.loadRomParser()
            assumeTrue(parser != null, "Test ROM not found")
            parser!!
            val rooms = parser.roomCatalog.rooms.mapNotNull { info ->
                parser.readRoomHeader(info.getRoomIdAsInt())
            }
            data class Candidate(val room: com.supermetroid.editor.data.Room, val dx: Int, val dy: Int)
            val candidate = rooms.firstNotNullOfOrNull { room ->
                val map = parser.readMinimapTiles(room.area)
                listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).firstNotNullOfOrNull direction@ { (dx, dy) ->
                    val newX = room.mapX + dx
                    val newY = room.mapY + dy
                    if (newX !in 0..(MinimapData.MAP_WIDTH - room.width) ||
                        newY !in 0..(MinimapData.MAP_HEIGHT - room.height)
                    ) return@direction null
                    val overlapsRoom = rooms.any { other ->
                        other.roomId != room.roomId && other.area == room.area && rectanglesOverlap(
                            newX, newY, room.width, room.height,
                            other.mapX, other.mapY, other.width, other.height,
                        )
                    }
                    if (overlapsRoom) return@direction null
                    val hasTileConflict = (0 until room.height).any { ry ->
                        (0 until room.width).any { rx ->
                            val tx = newX + rx
                            val ty = newY + ry
                            val targetIsInsideSource = tx in room.mapX until (room.mapX + room.width) &&
                                ty in room.mapY until (room.mapY + room.height)
                            !targetIsInsideSource && !isEmptyMinimapTile(map.getTile(tx, ty))
                        }
                    }
                    if (hasTileConflict) null else Candidate(room, dx, dy)
                }
            }
            assumeTrue(candidate != null, "No safely movable room fixture found")
            val (room, dx, dy) = candidate!!
            val editor = EditorState().also {
                it.testMode = true
                it.initForRom("/tmp/minimap-history-test.smc")
            }
            val state = MinimapEditorState()
            state.initIfNeeded(parser, editor)
            state.loadArea(parser, room.area, editor, selectRoomId = room.roomId)
            val originalMap = state.mapData.tiles.copyOf()
            val originalStation = state.stationData.revealed.copyOf()

            state.moveRoom(dx, dy, editor)
            state.applyMove(editor)

            val movedRoom = editor.applyHeaderChanges(room)
            assertEquals(room.mapX + dx, movedRoom.mapX)
            assertEquals(room.mapY + dy, movedRoom.mapY)
            assertTrue(state.undoStack.isNotEmpty())

            state.undo(editor)

            assertEquals(null, editor.project.rooms[editor.project.roomKey(room.roomId)]?.roomHeaderChange)
            assertArrayEquals(originalMap, state.mapData.tiles)
            assertArrayEquals(originalStation, state.stationData.revealed)

            state.redo(editor)

            val redoneRoom = editor.applyHeaderChanges(room)
            assertEquals(room.mapX + dx, redoneRoom.mapX)
            assertEquals(room.mapY + dy, redoneRoom.mapY)
        }
    }
}

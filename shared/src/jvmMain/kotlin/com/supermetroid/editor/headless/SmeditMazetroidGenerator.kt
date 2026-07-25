package com.supermetroid.editor.headless

import com.supermetroid.editor.data.EditOperation
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.ScrollChange
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.procgen.BiomeGenerationOptions
import com.supermetroid.editor.procgen.BiomeGenerationRect
import com.supermetroid.editor.procgen.BiomeGenerator
import com.supermetroid.editor.procgen.BiomeRules
import com.supermetroid.editor.procgen.BiomeStyle
import com.supermetroid.editor.procgen.LevelGrid
import com.supermetroid.editor.procgen.TilesetProfile
import com.supermetroid.editor.rom.RomParser
import kotlin.random.Random

data class SmeditGeneratedProject(
    val project: SmEditProject,
    val report: SmeditGeneratorReport,
)

object SmeditMazetroidGenerator {
    private const val LANDING_SITE_ROOM_ID = 0x91F8
    private const val MORPH_BALL_VISIBLE_PLM = 0xEF23
    private const val BOMB_VISIBLE_PLM = 0xEEE7
    private const val GENERATED_BIOME_PREFIX = "Generated biome:"

    fun generate(
        romBytes: ByteArray,
        request: SmeditGeneratorRequest = SmeditGeneratorRequest(mazetroid = true),
    ): SmeditGeneratedProject {
        val seed = request.seed ?: Random.nextLong(0, 1_000_000_000L)
        val parser = RomParser(romBytes)
        val roomInfos = RoomRepository().getAllRooms()
        val roomIds = roomInfos.map { it.getRoomIdAsInt() }
        val headers = roomInfos
            .mapNotNull { info -> runCatching { parser.readRoomHeader(info.getRoomIdAsInt()) }.getOrNull() }
            .filter { it.levelDataPtr != 0 && it.width > 0 && it.height > 0 }
        val headersById = headers.associateBy { it.roomId }
        val profileCache = mutableMapOf<Int, TilesetProfile>()
        val project = SmEditProject(romPath = "")
        val baseRules = BiomeRules.roll(BiomeStyle.PIPE_MAZE, seed)
        val rules = baseRules.withMazeOverrides(
            branchDensity = baseRules.mazeBranchDensity,
            loopDensity = baseRules.mazeLoopDensity,
            hubSize = baseRules.mazeHubSize,
            emptyCenter = baseRules.mazeEmptyCenter,
        )

        var generated = 0
        var skipped = 0
        var changedTiles = 0

        for (roomId in roomIds.sorted()) {
            val room = headersById[roomId]
            if (room == null) {
                skipped++
                continue
            }
            val grids = readRoomGrids(parser, room)
            if (grids == null) {
                skipped++
                continue
            }

            val profile = profileCache.getOrPut(room.tileset) {
                TilesetProfile.learn(parser, headers.filter { it.tileset == room.tileset }, room.tileset)
            }
            val options = buildGenerationOptions(
                roomId = roomId,
                width = grids.width,
                height = grids.height,
                parser = parser,
            )
            val roomSeed = seed xor (roomId.toLong() * -7046029254386353131L)
            val generatedLevel = runCatching {
                BiomeGenerator(rules, profile, roomSeed, options)
                    .generate(grids.width, grids.height, grids.words, grids.bts)
            }.getOrNull()
            if (generatedLevel == null) {
                skipped++
                continue
            }

            val edits = ArrayList<TileEdit>()
            for (i in generatedLevel.words.indices) {
                if (generatedLevel.words[i] != grids.words[i] || generatedLevel.bts[i] != grids.bts[i]) {
                    edits.add(
                        TileEdit(
                            blockX = i % grids.width,
                            blockY = i / grids.width,
                            oldBlockWord = grids.words[i],
                            newBlockWord = generatedLevel.words[i],
                            oldBts = grids.bts[i],
                            newBts = generatedLevel.bts[i],
                        )
                    )
                }
            }

            val scrollEdits = buildGeneratedRoomScrollResetEdits(parser, room)
            val scrollPlmRemoves = buildScrollPlmRemovals(parser, roomId)
            if (edits.isEmpty() && scrollEdits.isEmpty() && scrollPlmRemoves.isEmpty()) continue

            recordGeneratedBiomeOperation(
                project.getOrCreateRoom(roomId),
                rules,
                seed,
                edits,
                scrollEdits,
                scrollPlmRemoves,
            )
            changedTiles += edits.size
            generated++
        }

        val placements = addStartingItems(project, parser, roomIds)
        return SmeditGeneratedProject(
            project = project,
            report = SmeditGeneratorReport(
                seed = seed,
                generatedRooms = generated,
                skippedRooms = skipped,
                changedTiles = changedTiles,
                itemPlacements = placements,
            ),
        )
    }

    private fun recordGeneratedBiomeOperation(
        roomEdits: RoomEdits,
        rules: BiomeRules,
        seed: Long,
        edits: List<TileEdit>,
        scrollEdits: List<ScrollChange>,
        scrollPlmRemoves: List<PlmChange>,
    ) {
        roomEdits.operations.add(
            EditOperation(
                description = "$GENERATED_BIOME_PREFIX ${rules.style.displayName}, seed $seed",
                edits = edits,
                plmRemoves = scrollPlmRemoves,
                scrollEdits = scrollEdits,
            )
        )
        for (sc in scrollEdits) {
            roomEdits.scrollChanges.removeAll { it.screenX == sc.screenX && it.screenY == sc.screenY }
            if (sc.newValue != sc.oldValue) roomEdits.scrollChanges.add(sc)
        }
        for (plm in scrollPlmRemoves) {
            roomEdits.plmChanges.removeAll {
                it.action == plm.action && it.plmId == plm.plmId && it.x == plm.x && it.y == plm.y && it.param == plm.param
            }
            roomEdits.plmChanges.add(plm)
        }
    }

    private fun buildGeneratedRoomScrollResetEdits(parser: RomParser, room: Room): List<ScrollChange> {
        if (room.width <= 0 || room.height <= 0) return emptyList()
        val original = parser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
        val edits = ArrayList<ScrollChange>()
        for (screenY in 0 until room.height) {
            for (screenX in 0 until room.width) {
                val index = screenY * room.width + screenX
                val target = if (screenY == room.height - 1) 0x02 else 0x01
                val old = original.getOrElse(index) { 0x01 }
                if (old != target) edits.add(ScrollChange(screenX, screenY, old, target))
            }
        }
        return edits
    }

    private fun buildScrollPlmRemovals(parser: RomParser, roomId: Int): List<PlmChange> =
        parser.getAllPlmEntriesForRoom(roomId)
            .filter { RomParser.isScrollPlm(it.id) }
            .map { PlmChange("remove", it.id, it.x, it.y, it.param) }

    private fun readRoomGrids(parser: RomParser, room: Room): RoomGrids? {
        val data = runCatching { parser.decompressLZ2(room.levelDataPtr) }.getOrNull() ?: return null
        val width = room.width * 16
        val height = room.height * 16
        val grid = LevelGrid.parse(data, width, height) ?: return null
        val words = IntArray(width * height)
        val bts = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                words[index] = grid.word(x, y)
                bts[index] = grid.bts(x, y)
            }
        }
        return RoomGrids(width, height, words, bts)
    }

    private fun buildGenerationOptions(
        roomId: Int,
        width: Int,
        height: Int,
        parser: RomParser,
    ): BiomeGenerationOptions {
        val preserveRects = ArrayList<BiomeGenerationRect>()
        val forceAirRects = ArrayList<BiomeGenerationRect>()
        if (roomId == LANDING_SITE_ROOM_ID) {
            addLandingSiteShipProtection(preserveRects, forceAirRects)
        }
        addElevatorProtectionForRoom(preserveRects, forceAirRects, parser, roomId, width, height)
        val plms = parser.getAllPlmEntriesForRoom(roomId)
        addImportantPlmProtection(preserveRects, forceAirRects, width, height, plms)
        preserveRects.addAll(buildDoorCapPreserveRectsForRoom(parser, roomId, width, height, plms))
        return BiomeGenerationOptions(
            preserveRects = preserveRects,
            forceAirRects = forceAirRects,
        )
    }

    private fun addStartingItems(
        project: SmEditProject,
        parser: RomParser,
        roomIds: List<Int>,
    ): List<SmeditGeneratorItemPlacement> {
        val usedParams = parser.scanAllItemPlms(roomIds)
            .map { it.plm.param }
            .filterTo(mutableSetOf()) { it > 0 }
        val morph = placement("Morph Ball", MORPH_BALL_VISIBLE_PLM, x = 67, y = 69, param = nextItemParam(usedParams))
        val bombs = placement("Bombs", BOMB_VISIBLE_PLM, x = 75, y = 68, param = nextItemParam(usedParams))
        val landingSite = project.getOrCreateRoom(LANDING_SITE_ROOM_ID)
        for (placement in listOf(morph, bombs)) {
            landingSite.plmChanges.removeAll { it.action == "add" && it.x == placement.x && it.y == placement.y }
            landingSite.plmChanges.add(
                PlmChange(
                    action = "add",
                    plmId = placement.plmId,
                    x = placement.x,
                    y = placement.y,
                    param = placement.param,
                )
            )
        }
        return listOf(morph, bombs)
    }

    private fun placement(
        item: String,
        plmId: Int,
        x: Int,
        y: Int,
        param: Int,
    ): SmeditGeneratorItemPlacement =
        SmeditGeneratorItemPlacement(
            roomId = LANDING_SITE_ROOM_ID,
            item = item,
            plmId = plmId,
            x = x,
            y = y,
            param = param,
        )

    private fun nextItemParam(usedParams: MutableSet<Int>): Int {
        var next = 0x51
        while (next in usedParams && next <= 0x1FF) next++
        usedParams.add(next)
        return next
    }

    private fun addImportantPlmProtection(
        preserveRects: MutableList<BiomeGenerationRect>,
        forceAirRects: MutableList<BiomeGenerationRect>,
        width: Int,
        height: Int,
        plms: List<RomParser.PlmEntry>,
    ) {
        if (width <= 0 || height <= 0) return
        fun addRect(list: MutableList<BiomeGenerationRect>, rect: BiomeGenerationRect) {
            if (rect.x1 >= 0 && rect.y1 >= 0 && rect.x0 < width && rect.y0 < height) {
                list.add(rect)
            }
        }
        for (plm in plms) {
            if (!isBiomeAnchorPlm(plm.id)) continue
            val x = plm.x
            val y = plm.y
            if (x !in 0 until width || y !in 0 until height) continue
            addRect(preserveRects, BiomeGenerationRect(x - 1, y - 1, x + 1, y + 1))
            addRect(forceAirRects, BiomeGenerationRect(x - 3, y - 4, x + 3, y + 2))
        }
    }

    private fun isBiomeAnchorPlm(plmId: Int): Boolean =
        !RomParser.isScrollPlm(plmId) &&
            !RomParser.isDoorCapPlm(plmId) &&
            (RomParser.isItemPlm(plmId) || RomParser.isStationPlm(plmId) || RomParser.isGatePlm(plmId))

    private fun addLandingSiteShipProtection(
        preserveRects: MutableList<BiomeGenerationRect>,
        forceAirRects: MutableList<BiomeGenerationRect>,
    ) {
        preserveRects.add(BiomeGenerationRect(58, 60, 84, 73))
        forceAirRects.add(BiomeGenerationRect(55, 57, 87, 76))
    }

    private fun addElevatorProtectionForRoom(
        preserveRects: MutableList<BiomeGenerationRect>,
        forceAirRects: MutableList<BiomeGenerationRect>,
        parser: RomParser,
        roomId: Int,
        width: Int,
        height: Int,
    ) {
        if (roomId == 0 || width <= 0 || height <= 0) return
        for (door in parser.findDoorsLeadingTo(roomId).filter { it.isElevator }) {
            addElevatorProtectionForDoor(preserveRects, forceAirRects, door, width, height)
        }
    }

    private fun addElevatorProtectionForDoor(
        preserveRects: MutableList<BiomeGenerationRect>,
        forceAirRects: MutableList<BiomeGenerationRect>,
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
            if (rect.x1 >= 0 && rect.y1 >= 0 && rect.x0 < width && rect.y0 < height) {
                preserveRects.add(rect)
            }
        }
        fun addForceAir(rect: BiomeGenerationRect) {
            if (rect.x1 >= 0 && rect.y1 >= 0 && rect.x0 < width && rect.y0 < height) {
                forceAirRects.add(rect)
            }
        }

        when (door.direction and 0x03) {
            2 -> {
                val doorY = screenY0
                addPreserve(BiomeGenerationRect(centerLeftX - 4, doorY, centerRightX + 4, doorY))
                addForceAir(
                    BiomeGenerationRect(
                        verticalClearLeftX,
                        doorY + 1,
                        verticalClearRightX,
                        doorY + elevatorClearanceDepth,
                    )
                )
            }
            3 -> {
                val doorY = minOf(screenY0 + 15, height - 1)
                addPreserve(BiomeGenerationRect(centerLeftX - 4, doorY, centerRightX + 4, doorY))
                addForceAir(
                    BiomeGenerationRect(
                        verticalClearLeftX,
                        doorY - elevatorClearanceDepth,
                        verticalClearRightX,
                        doorY - 1,
                    )
                )
            }
            0 -> {
                val doorX = screenX0
                addPreserve(BiomeGenerationRect(doorX, centerTopY - 4, doorX, centerBottomY + 4))
                addForceAir(
                    BiomeGenerationRect(
                        doorX + 1,
                        horizontalClearTopY,
                        doorX + elevatorClearanceDepth,
                        horizontalClearBottomY,
                    )
                )
            }
            1 -> {
                val doorX = minOf(screenX0 + 15, width - 1)
                addPreserve(BiomeGenerationRect(doorX, centerTopY - 4, doorX, centerBottomY + 4))
                addForceAir(
                    BiomeGenerationRect(
                        doorX - elevatorClearanceDepth,
                        horizontalClearTopY,
                        doorX - 1,
                        horizontalClearBottomY,
                    )
                )
            }
        }
    }

    private fun buildDoorCapPreserveRectsForRoom(
        parser: RomParser,
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
        for (door in parser.findDoorsLeadingTo(roomId)) {
            val x = door.doorCapCode and 0xFF
            val y = (door.doorCapCode shr 8) and 0xFF
            if (x !in 0 until width || y !in 0 until height) continue
            val horizontal = (door.direction and 0x03) == 2 || (door.direction and 0x03) == 3
            rects.add(doorCapRect(x, y, horizontal))
        }
        return rects
    }

    private fun doorCapRect(x: Int, y: Int, horizontal: Boolean): BiomeGenerationRect =
        if (horizontal) {
            BiomeGenerationRect(x - 1, y - 2, x + 4, y + 2)
        } else {
            BiomeGenerationRect(x - 2, y - 1, x + 2, y + 4)
        }

    private data class RoomGrids(
        val width: Int,
        val height: Int,
        val words: IntArray,
        val bts: IntArray,
    )
}

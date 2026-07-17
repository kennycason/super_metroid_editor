package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.SmEditProject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * ROM-wide validation scanner.
 * Checks for common issues that cause in-game bugs or crashes.
 */
@OptIn(ExperimentalEncodingApi::class)
object RomValidator {

    enum class Severity { ERROR, WARNING, INFO }

    data class Issue(
        val severity: Severity,
        val category: String,
        val roomId: Int?,
        val roomName: String,
        val message: String,
    )

    /**
     * Run all validations and return a list of issues found.
     */
    fun validate(parser: RomParser, roomIds: List<Int>, project: SmEditProject? = null): List<Issue> {
        val issues = mutableListOf<Issue>()
        val rooms = mutableMapOf<Int, Room>()
        for (rid in roomIds) {
            val room = parser.readRoomHeader(rid) ?: continue
            rooms[rid] = room
        }

        issues.addAll(checkDoorConsistency(parser, rooms))
        issues.addAll(checkItemBitflagDuplicates(parser, rooms))
        issues.addAll(checkEnemyGfxLimits(parser, rooms))
        issues.addAll(checkRoomDimensions(rooms))
        issues.addAll(checkPlmSets(parser, rooms))
        if (project != null) {
            issues.addAll(checkProjectSaveStationSpawns(parser, project, rooms))
            issues.addAll(checkProjectGraphicsExportFit(parser, project))
            issues.addAll(checkProjectEnemyTileEdits(parser, project))
            issues.addAll(checkProjectSpritePalettes(parser, project))
        }

        return issues.sortedWith(compareBy({ it.severity }, { it.category }, { it.roomName }))
    }

    /**
     * Verify all doors point to valid destination rooms and have coordinates
     * within the destination room's dimensions.
     */
    fun checkDoorConsistency(parser: RomParser, rooms: Map<Int, Room>): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((roomId, room) in rooms) {
            val doors = parser.parseDoorList(room.doorOut)
            for ((idx, door) in doors.withIndex()) {
                val destRoom = rooms[door.destRoomPtr]
                val label = "Door #$idx (${door.directionName})"

                if (destRoom == null) {
                    // Destination room not in our known room list — could be valid but unknown
                    val destHex = "0x${door.destRoomPtr.toString(16).uppercase()}"
                    issues.add(Issue(
                        Severity.WARNING, "Doors", roomId, room.name,
                        "$label → destination room $destHex not found in ROM"
                    ))
                    continue
                }

                // Check spawn coordinates against destination room dimensions
                val maxScreenX = destRoom.width - 1
                val maxScreenY = destRoom.height - 1
                if (door.screenX > maxScreenX) {
                    issues.add(Issue(
                        Severity.ERROR, "Doors", roomId, room.name,
                        "$label → screenX=${door.screenX} exceeds ${destRoom.name} width (${destRoom.width} screens, max X=$maxScreenX)"
                    ))
                }
                if (door.screenY > maxScreenY) {
                    issues.add(Issue(
                        Severity.ERROR, "Doors", roomId, room.name,
                        "$label → screenY=${door.screenY} exceeds ${destRoom.name} height (${destRoom.height} screens, max Y=$maxScreenY)"
                    ))
                }
            }
        }
        return issues
    }

    /**
     * Scan all item PLMs across all rooms for duplicate collection bitflags.
     * Two items sharing the same param means collecting one silently collects the other.
     */
    fun checkItemBitflagDuplicates(parser: RomParser, rooms: Map<Int, Room>): List<Issue> {
        val issues = mutableListOf<Issue>()
        // Map: param → list of (roomId, plmId, itemName)
        data class ItemLocation(val roomId: Int, val roomName: String, val plmId: Int, val itemName: String)
        val paramMap = mutableMapOf<Int, MutableList<ItemLocation>>()

        for ((roomId, room) in rooms) {
            val plms = parser.getAllPlmEntriesForRoom(roomId)
            for (plm in plms) {
                if (!RomParser.isItemPlm(plm.id)) continue
                val itemName = RomParser.itemNameForPlm(plm.id) ?: "Unknown item"
                paramMap.getOrPut(plm.param) { mutableListOf() }
                    .add(ItemLocation(roomId, room.name, plm.id, itemName))
            }
        }

        for ((param, locations) in paramMap) {
            if (locations.size <= 1) continue
            // Multiple items sharing the same collection bit
            val roomNames = locations.map { "${it.itemName} in ${it.roomName}" }.joinToString(", ")
            val paramHex = "0x${param.toString(16).uppercase()}"
            issues.add(Issue(
                Severity.WARNING, "Items", null,
                locations.first().roomName,
                "Collection bit $paramHex shared by ${locations.size} items: $roomNames"
            ))
        }
        return issues
    }

    /**
     * Check that each room's enemy GFX set doesn't exceed the 4-slot hardware limit.
     */
    fun checkEnemyGfxLimits(parser: RomParser, rooms: Map<Int, Room>): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((roomId, room) in rooms) {
            val gfxEntries = parser.parseEnemyGfxSet(room.enemyGfxPtr)
            if (gfxEntries.size > 4) {
                issues.add(Issue(
                    Severity.ERROR, "Enemy GFX", roomId, room.name,
                    "${gfxEntries.size} enemy tileset slots used (SNES hardware max is 4). Excess sprites will be garbled."
                ))
            }
        }
        return issues
    }

    /**
     * Check rooms for suspicious dimensions or map positions.
     */
    fun checkRoomDimensions(rooms: Map<Int, Room>): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((_, room) in rooms) {
            if (room.mapX + room.width > MinimapData.MAP_WIDTH) {
                issues.add(Issue(
                    Severity.WARNING, "Room Header", room.roomId, room.name,
                    "Room extends past minimap right edge: mapX=${room.mapX} + width=${room.width} = ${room.mapX + room.width} > ${MinimapData.MAP_WIDTH}"
                ))
            }
            if (room.mapY + room.height > MinimapData.MAP_HEIGHT) {
                issues.add(Issue(
                    Severity.WARNING, "Room Header", room.roomId, room.name,
                    "Room extends past minimap bottom edge: mapY=${room.mapY} + height=${room.height} = ${room.mapY + room.height} > ${MinimapData.MAP_HEIGHT}"
                ))
            }
        }
        return issues
    }

    /**
     * Check raw PLM sets for terminators, count limits, and coordinates outside
     * room dimensions. This catches corrupt PLM tables before export/playtest.
     */
    fun checkPlmSets(parser: RomParser, rooms: Map<Int, Room>): List<Issue> {
        val issues = mutableListOf<Issue>()
        val rom = parser.getRomData()
        for ((roomId, room) in rooms) {
            if (room.plmSetPtr == 0 || room.plmSetPtr == 0xFFFF) continue
            val pc = parser.snesToPc(RomConstants.BANK_ROOM_DATA or room.plmSetPtr)
            if (pc < 0 || pc >= rom.size) {
                issues.add(Issue(
                    Severity.ERROR, "PLMs", roomId, room.name,
                    "PLM set pointer 0x${room.plmSetPtr.toString(16).uppercase()} resolves outside ROM bounds."
                ))
                continue
            }

            val maxEntries = 256
            var cursor = pc
            var count = 0
            var terminated = false
            while (cursor + 1 < rom.size && count < maxEntries) {
                val id = readU16(rom, cursor)
                if (id == 0) {
                    terminated = true
                    break
                }
                if (cursor + 5 >= rom.size) break
                val x = rom[cursor + 2].toInt() and 0xFF
                val y = rom[cursor + 3].toInt() and 0xFF
                if (x >= room.width * 16 || y >= room.height * 16) {
                    issues.add(Issue(
                        Severity.WARNING, "PLMs", roomId, room.name,
                        "PLM 0x${id.toString(16).uppercase()} at ($x,$y) is outside room bounds ${room.width * 16}x${room.height * 16}."
                    ))
                }
                cursor += 6
                count++
            }
            if (!terminated) {
                issues.add(Issue(
                    Severity.ERROR, "PLMs", roomId, room.name,
                    "PLM set at 0x${room.plmSetPtr.toString(16).uppercase()} has no terminator within $maxEntries entries."
                ))
            }
        }
        return issues
    }

    /**
     * Project overrides for save stations patch AreaSave table entries directly.
     * Duplicate area/index pairs or missing table slots make export ambiguous.
     */
    fun checkProjectSaveStationSpawns(
        parser: RomParser,
        project: SmEditProject,
        rooms: Map<Int, Room>,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()
        val bySlot = mutableMapOf<Pair<Int, Int>, MutableList<Pair<Int, String>>>()
        for ((roomKey, roomEdits) in project.rooms) {
            val sourceRoomId = roomKey.toIntOrNull(16)
            val sourceRoomName = sourceRoomId?.let { rooms[it]?.name } ?: "Room $roomKey"
            for (spawn in roomEdits.saveStationSpawns) {
                val slot = spawn.area to spawn.saveIndex
                bySlot.getOrPut(slot) { mutableListOf() }.add((sourceRoomId ?: spawn.roomId) to sourceRoomName)

                if (spawn.area !in 0..7) {
                    issues.add(Issue(
                        Severity.ERROR, "AreaSave", sourceRoomId, sourceRoomName,
                        "Save station override uses invalid area ${spawn.area}; valid areas are 0-7."
                    ))
                    continue
                }

                val count = parser.saveEntryCount(spawn.area)
                val romEntry = parser.readSaveEntry(spawn.area, spawn.saveIndex)
                if (romEntry == null) {
                    issues.add(Issue(
                        Severity.ERROR, "AreaSave", sourceRoomId, sourceRoomName,
                        "Save station override area=${spawn.area} index=${spawn.saveIndex} has no writable AreaSave slot (area has $count entries)."
                    ))
                }

                val targetRoom = rooms[spawn.roomId] ?: parser.readRoomHeader(spawn.roomId)
                if (targetRoom == null) {
                    issues.add(Issue(
                        Severity.ERROR, "AreaSave", sourceRoomId, sourceRoomName,
                        "Save station override points to missing room 0x${spawn.roomId.toString(16).uppercase()}."
                    ))
                }

                if (spawn.doorPtr == 0) {
                    issues.add(Issue(
                        Severity.WARNING, "AreaSave", sourceRoomId, sourceRoomName,
                        "Save station override area=${spawn.area} index=${spawn.saveIndex} has door pointer 0; resume may enter incorrectly."
                    ))
                } else {
                    val incoming = parser.findDoorsLeadingTo(spawn.roomId).any { it.doorDefPtr == spawn.doorPtr }
                    if (!incoming) {
                        issues.add(Issue(
                            Severity.WARNING, "AreaSave", sourceRoomId, sourceRoomName,
                            "Save station override area=${spawn.area} index=${spawn.saveIndex} uses door pointer 0x${spawn.doorPtr.toString(16).uppercase()} that is not an incoming door to room 0x${spawn.roomId.toString(16).uppercase()}."
                        ))
                    }
                }
            }
        }

        for ((slot, entries) in bySlot) {
            if (entries.size <= 1) continue
            val names = entries.joinToString(", ") { (_, name) -> name }
            issues.add(Issue(
                Severity.ERROR, "AreaSave", entries.first().first, entries.first().second,
                "AreaSave slot area=${slot.first} index=${slot.second} is edited by ${entries.size} rooms: $names. Only one exported value can win."
            ))
        }
        return issues
    }

    /**
     * Check custom graphics/metatile/palette payloads for malformed data and
     * conservative in-place export size limits.
     */
    fun checkProjectGraphicsExportFit(parser: RomParser, project: SmEditProject): List<Issue> {
        val issues = mutableListOf<Issue>()
        val gfx = project.customGfx
        validateCompressedPayload(
            issues = issues,
            parser = parser,
            label = "CRE graphics",
            category = "Graphics Export",
            b64 = gfx.creGfx,
            snesAddress = TileGraphics.CRE_GFX_SNES,
            requiredMultiple = RomConstants.BYTES_PER_4BPP_TILE,
        )
        validateCompressedPayload(
            issues = issues,
            parser = parser,
            label = "CRE metatile table",
            category = "Graphics Export",
            b64 = gfx.creTileTable,
            snesAddress = TileGraphics.CRE_TILE_TABLE_SNES,
            requiredMultiple = 8,
        )

        val tablePc = parser.snesToPc(TileGraphics.TILESET_TABLE_SNES)
        val rom = parser.getRomData()
        for ((tilesetIdText, b64) in gfx.varGfx) {
            val tilesetId = tilesetIdText.toIntOrNull()
            val snes = tilesetId?.let { tilesetPointer(rom, tablePc, it, offset = 3) }
            validateCompressedPayload(
                issues = issues,
                parser = parser,
                label = "Tileset $tilesetIdText area graphics",
                category = "Graphics Export",
                b64 = b64,
                snesAddress = snes,
                requiredMultiple = RomConstants.BYTES_PER_4BPP_TILE,
            )
        }
        for ((tilesetIdText, b64) in gfx.tileTables) {
            val tilesetId = tilesetIdText.toIntOrNull()
            val snes = tilesetId?.let { tilesetPointer(rom, tablePc, it, offset = 0) }
            validateCompressedPayload(
                issues = issues,
                parser = parser,
                label = "Tileset $tilesetIdText metatile table",
                category = "Graphics Export",
                b64 = b64,
                snesAddress = snes,
                requiredMultiple = 8,
            )
        }
        for ((tilesetIdText, b64) in gfx.palettes) {
            val tilesetId = tilesetIdText.toIntOrNull()
            val snes = tilesetId?.let { tilesetPointer(rom, tablePc, it, offset = 6) }
            validateCompressedPayload(
                issues = issues,
                parser = parser,
                label = "Tileset $tilesetIdText palette",
                category = "Graphics Export",
                b64 = b64,
                snesAddress = snes,
                exactSize = 256,
            )
        }
        for ((key, b64) in gfx.spriteTileBlocks) {
            val block = when {
                key.startsWith("phantoon:") -> key.removePrefix("phantoon:").toIntOrNull()
                    ?.let { EnemySpriteGraphics.PHANTOON_BLOCKS.getOrNull(it) }
                key.startsWith("kraid:") -> key.removePrefix("kraid:").toIntOrNull()
                    ?.let { EnemySpriteGraphics.KRAID_BLOCKS.getOrNull(it) }
                else -> null
            }
            if (block != null) {
                validateCompressedPayload(
                    issues = issues,
                    parser = parser,
                    label = "Sprite block $key",
                    category = "Sprite Export",
                    b64 = b64,
                    snesAddress = block.snesAddress,
                    requiredMultiple = RomConstants.BYTES_PER_4BPP_TILE,
                )
            }
        }
        return issues
    }

    fun checkProjectEnemyTileEdits(parser: RomParser, project: SmEditProject): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((key, b64) in project.customGfx.spriteTileBlocks) {
            if (!key.startsWith("enemy:")) continue
            val speciesHex = key.removePrefix("enemy:")
            val speciesId = speciesHex.toIntOrNull(16)
            if (speciesId == null) {
                issues.add(Issue(
                    Severity.ERROR, "Sprite Export", null, "Project",
                    "Enemy tile edit key '$key' has an invalid species id."
                ))
                continue
            }
            val raw = decodeBase64Issue("Enemy $speciesHex tile edit", b64) { issues += it } ?: continue
            val validation = EnemySpriteGraphics.validateEnemyTileEdit(parser, speciesId, raw)
            for (error in validation.errors) {
                issues.add(Issue(
                    Severity.ERROR, "Sprite Export", null, RomParser.enemyName(speciesId),
                    "Enemy $speciesHex tile edit cannot export: $error"
                ))
            }
            for (warning in validation.warnings) {
                issues.add(Issue(
                    Severity.WARNING, "Sprite Export", null, RomParser.enemyName(speciesId),
                    "Enemy $speciesHex tile edit: $warning"
                ))
            }
        }
        return issues
    }

    fun checkProjectSpritePalettes(parser: RomParser, project: SmEditProject): List<Issue> {
        val issues = mutableListOf<Issue>()
        val rom = parser.getRomData()
        for ((key, b64) in project.customGfx.spritePalettes) {
            val raw = decodeBase64Issue("Sprite palette $key", b64) {
                issues += it.copy(category = "Sprite Palettes")
            } ?: continue
            if (key.startsWith("enemy_pal:")) {
                validateEnemyPalette(parser, rom, key, raw, issues)
            } else {
                val region = SpritePalettes.findRegion(key)
                if (region == null) {
                    issues.add(Issue(
                        Severity.ERROR, "Sprite Palettes", null, "Project",
                        "Sprite palette '$key' does not match a known palette region."
                    ))
                    continue
                }
                if (raw.size != region.byteSize) {
                    issues.add(Issue(
                        Severity.ERROR, "Sprite Palettes", null, region.name,
                        "Sprite palette '${region.name}' has ${raw.size} bytes; expected ${region.byteSize} bytes."
                    ))
                }
                if (region.offset < 0 || region.offset + region.byteSize > rom.size) {
                    issues.add(Issue(
                        Severity.ERROR, "Sprite Palettes", null, region.name,
                        "Sprite palette '${region.name}' writes outside ROM bounds at PC 0x${region.offset.toString(16).uppercase()}."
                    ))
                }
            }
        }
        return issues
    }

    private fun validateEnemyPalette(
        parser: RomParser,
        rom: ByteArray,
        key: String,
        raw: ByteArray,
        issues: MutableList<Issue>,
    ) {
        val speciesHex = key.removePrefix("enemy_pal:")
        val speciesId = speciesHex.toIntOrNull(16)
        if (speciesId == null) {
            issues.add(Issue(
                Severity.ERROR, "Sprite Palettes", null, "Project",
                "Enemy palette key '$key' has an invalid species id."
            ))
            return
        }
        if (raw.size != 32) {
            issues.add(Issue(
                Severity.ERROR, "Sprite Palettes", null, RomParser.enemyName(speciesId),
                "Enemy $speciesHex palette has ${raw.size} bytes; expected 32 bytes."
            ))
        }
        val headerPc = parser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        if (headerPc < 0 || headerPc + 0x0D > rom.size) {
            issues.add(Issue(
                Severity.ERROR, "Sprite Palettes", null, RomParser.enemyName(speciesId),
                "Enemy $speciesHex palette cannot export because the species header is outside ROM bounds."
            ))
            return
        }
        val palPtr = readU16(rom, headerPc + 2)
        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF
        val palSnes = (aiBank shl 16) or (palPtr and 0xFFFF)
        val palPc = parser.snesToPc(palSnes)
        if (palPc < 0 || palPc + 32 > rom.size) {
            issues.add(Issue(
                Severity.ERROR, "Sprite Palettes", null, RomParser.enemyName(speciesId),
                "Enemy $speciesHex palette destination 0x${palSnes.toString(16).uppercase()} resolves outside ROM bounds."
            ))
        }
    }

    private fun validateCompressedPayload(
        issues: MutableList<Issue>,
        parser: RomParser,
        label: String,
        category: String,
        b64: String?,
        snesAddress: Int?,
        requiredMultiple: Int? = null,
        exactSize: Int? = null,
    ) {
        if (b64 == null) return
        val raw = decodeBase64Issue(label, b64) { issues += it.copy(category = category) } ?: return
        if (raw.isEmpty()) {
            issues.add(Issue(Severity.ERROR, category, null, "Project", "$label is empty."))
            return
        }
        if (requiredMultiple != null && raw.size % requiredMultiple != 0) {
            issues.add(Issue(
                Severity.ERROR, category, null, "Project",
                "$label has ${raw.size} bytes; expected a multiple of $requiredMultiple."
            ))
        }
        if (exactSize != null && raw.size != exactSize) {
            issues.add(Issue(
                Severity.ERROR, category, null, "Project",
                "$label has ${raw.size} bytes; expected exactly $exactSize bytes."
            ))
        }
        if (snesAddress == null) {
            issues.add(Issue(Severity.ERROR, category, null, "Project", "$label has an invalid ROM pointer."))
            return
        }
        try {
            val compressed = LZ5Compressor.compress(raw)
            val (_, originalSize) = parser.decompressLZ2WithSize(snesAddress)
            if (compressed.size > originalSize) {
                issues.add(Issue(
                    Severity.ERROR, category, null, "Project",
                    "$label compresses to ${compressed.size} bytes but the original allocation is $originalSize bytes; export will skip this edit."
                ))
            }
        } catch (e: Exception) {
            issues.add(Issue(
                Severity.ERROR, category, null, "Project",
                "$label could not be compression-validated: ${e.message ?: e::class.simpleName}"
            ))
        }
    }

    private fun decodeBase64Issue(label: String, b64: String, addIssue: (Issue) -> Unit): ByteArray? =
        try {
            Base64.decode(b64)
        } catch (e: Exception) {
            addIssue(Issue(
                Severity.ERROR, "Project Data", null, "Project",
                "$label contains invalid base64: ${e.message ?: e::class.simpleName}"
            ))
            null
        }

    private fun tilesetPointer(rom: ByteArray, tablePc: Int, tilesetId: Int, offset: Int): Int? {
        val entryOffset = tablePc + tilesetId * 9 + offset
        if (tilesetId !in 0 until TileGraphics.NUM_TILESETS || entryOffset + 2 >= rom.size) return null
        return readU24(rom, entryOffset)
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU24(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
}

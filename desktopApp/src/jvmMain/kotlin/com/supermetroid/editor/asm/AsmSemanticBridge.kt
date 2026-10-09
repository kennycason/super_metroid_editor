package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.MinimapData
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TextData
import com.supermetroid.editor.rom.parseLibraryBackground

/** Existing visual editor that can explain or render a recognized ASM address. */
internal sealed interface AsmEditorTarget {
    data class Room(val roomId: Int) : AsmEditorTarget
    data class Tileset(val tilesetId: Int?, val palette: Boolean = false) : AsmEditorTarget
    data class Sprite(val speciesId: Int?) : AsmEditorTarget // null = Samus
    data class Sound(val songSet: Int) : AsmEditorTarget
    data class Map(val area: Int?) : AsmEditorTarget
    data class Text(val entryId: String) : AsmEditorTarget
}

internal data class AsmEditorLink(
    val title: String,
    val detail: String,
    val target: AsmEditorTarget,
)

internal class AsmSemanticIndex internal constructor(
    private val linksByAddress: Map<Int, List<AsmEditorLink>>,
) {
    fun linksFor(snesAddress: Int, assetPath: String? = null): List<AsmEditorLink> =
        (linksByAddress[snesAddress].orEmpty() + assetPathHints(assetPath))
            .distinctBy(AsmEditorLink::target)

    private fun assetPathHints(assetPath: String?): List<AsmEditorLink> {
        val path = assetPath?.substringAfterLast('/')?.removePrefix("UNUSED_") ?: return emptyList()
        return buildList {
            if (path.startsWith("SamusTiles_") || path == "Tiles_SamusDeathSequence.bin") {
                add(AsmEditorLink("Open Samus", "Player sprite graphics", AsmEditorTarget.Sprite(null)))
            }
            if (path == "Tiles_PauseScreen_BG1_BG2.bin") {
                add(AsmEditorLink("Open pause map", "Map tile graphics", AsmEditorTarget.Map(null)))
            }
            bossSpeciesForAsset(path)?.let { speciesId ->
                val name = EnemySpriteGraphics.EDITOR_ENEMIES.firstOrNull { it.speciesId == speciesId }?.name
                    ?: RomParser.enemyName(speciesId)
                add(AsmEditorLink("Open $name", "Composite boss graphics", AsmEditorTarget.Sprite(speciesId)))
            }
        }
    }
}

/**
 * Resolve source/assets back into SMEDIT's semantic editors using addresses
 * consumed by the production decoders. Filename hints are reserved for
 * composite boss/Samus assets that have no single species-owned pointer.
 */
internal object AsmSemanticBridge {
    fun buildIndex(romParser: RomParser): AsmSemanticIndex {
        val byAddress = linkedMapOf<Int, MutableList<AsmEditorLink>>()
        fun add(address: Int, link: AsmEditorLink) {
            byAddress.getOrPut(address) { mutableListOf() }.add(link)
        }

        for (roomInfo in romParser.roomCatalog.rooms) {
            val roomId = roomInfo.getRoomIdAsInt()
            val roomTarget = AsmEditorTarget.Room(roomId)
            fun roomLink(detail: String) = AsmEditorLink("Open ${roomInfo.name}", detail, roomTarget)
            fun addRoomPointer(bank: Int, pointer: Int, detail: String) {
                if (pointer in 0x8000..0xFFFE) add(bank or pointer, roomLink(detail))
            }
            add(
                RomConstants.BANK_ROOM_DATA or roomId,
                roomLink("Room header"),
            )

            val room = runCatching { romParser.readRoomHeader(roomId) }.getOrNull()
            room?.doorOut?.let { doorOut ->
                addRoomPointer(RomConstants.BANK_ROOM_DATA, doorOut, "Door list")
                runCatching { romParser.parseDoorList(doorOut) }.getOrDefault(emptyList()).forEach { door ->
                    addRoomPointer(RomConstants.BANK_FX, door.doorDefPtr, "Door definition")
                    addRoomPointer(RomConstants.BANK_ROOM_DATA, door.doorCapCode, "Door scroll ASM")
                    addRoomPointer(RomConstants.BANK_ROOM_DATA, door.entryCode, "Door entry ASM")
                }
            }

            runCatching { romParser.parseRoomStatesWithData(roomId) }.getOrDefault(emptyList())
                .forEach { state ->
                    val stateName = state.stateInfo.conditionName
                    runCatching { romParser.pcToSnes(state.stateInfo.stateDataPcOffset) }.getOrNull()?.let { address ->
                        add(address, roomLink("State record · $stateName"))
                    }
                    state.levelDataPtr.takeIf { it != 0 }?.let { add(it, roomLink("Room layout · $stateName")) }
                    addRoomPointer(RomConstants.BANK_FX, state.fxPtr, "FX data · $stateName")
                    addRoomPointer(RomConstants.BANK_ENEMY_SET, state.enemySetPtr, "Enemy population · $stateName")
                    addRoomPointer(RomConstants.BANK_ENEMY_GFX, state.enemyGfxPtr, "Enemy graphics set · $stateName")
                    if (state.scrollPtr > 1) {
                        addRoomPointer(RomConstants.BANK_ROOM_DATA, state.scrollPtr, "Scroll data · $stateName")
                    }
                    addRoomPointer(
                        RomConstants.BANK_ROOM_DATA,
                        state.xraySpecialCasingPtr,
                        "Special X-Ray data · $stateName",
                    )
                    addRoomPointer(RomConstants.BANK_ROOM_DATA, state.mainAsmPtr, "Main room ASM · $stateName")
                    addRoomPointer(RomConstants.BANK_ROOM_DATA, state.plmSetPtr, "Placed-object set · $stateName")
                    addRoomPointer(RomConstants.BANK_ROOM_DATA, state.bgDataPtr, "Background program · $stateName")
                    addRoomPointer(RomConstants.BANK_ROOM_DATA, state.setupAsmPtr, "Setup room ASM · $stateName")

                    if (state.plmSetPtr in 0x8000..0xFFFE) {
                        runCatching { romParser.parsePlmSet(state.plmSetPtr) }.getOrDefault(emptyList())
                            .map { it.id }
                            .distinct()
                            .forEach { plmId ->
                                addRoomPointer(RomConstants.BANK_PLM, plmId, "Placed-object definition · $stateName")
                            }
                    }
                    if (state.bgDataPtr in 0x8000..0xFFFE) {
                        runCatching { romParser.parseLibraryBackground(state.bgDataPtr) }.getOrNull()
                            ?.commands
                            ?.mapNotNull { it.sourceAddress }
                            ?.distinct()
                            ?.forEach { sourceAddress ->
                                add(sourceAddress, roomLink("Background payload · $stateName"))
                            }
                    }
                }
        }

        for (area in 0 until MinimapData.NUM_AREAS) {
            val areaName = MinimapData.AREA_NAMES.getOrElse(area) { "Area $area" }
            val target = AsmEditorTarget.Map(area)
            runCatching { romParser.readMinimapTilemapAddress(area) }.getOrNull()?.let { address ->
                add(address, AsmEditorLink("Open $areaName map", "Pause-map tilemap", target))
            }
            runCatching { romParser.readMapStationDataAddress(area) }.getOrNull()?.let { address ->
                add(address, AsmEditorLink("Open $areaName map", "Map-station reveal data", target))
            }
        }
        runCatching { romParser.readMinimapTileGraphicsAddress() }.getOrNull()?.let { address ->
            add(address, AsmEditorLink("Open pause map", "Map tile graphics", AsmEditorTarget.Map(null)))
        }

        TextData.readAllText(romParser.getRomData())
            .filter { it.writable }
            .forEach { entry ->
                add(
                    entry.snesAddress,
                    AsmEditorLink(
                        title = "Open ${entry.label}",
                        detail = "Text · ${entry.category.displayName}",
                        target = AsmEditorTarget.Text(entry.id),
                    ),
                )
            }

        val catalog = romParser.graphicsCatalog
        for ((tilesetId, entry) in catalog.entries.withIndex()) {
            if (!entry.valid) continue
            listOf(
                Triple(entry.tileTablePtr, "Metatiles", false),
                Triple(entry.gfxPtr, "Graphics", false),
                Triple(entry.palettePtr, "Palette", true),
            ).forEach { (address, kind, palette) ->
                add(
                    address,
                    AsmEditorLink(
                        title = "Open Tileset \$${tilesetId.hex(2)}",
                        detail = kind,
                        target = AsmEditorTarget.Tileset(tilesetId, palette),
                    ),
                )
            }
        }
        add(
            catalog.creTileTablePtr,
            AsmEditorLink(
                "Open shared CRE metatiles",
                "Current tileset",
                AsmEditorTarget.Tileset(null),
            ),
        )
        add(
            catalog.creGfxPtr,
            AsmEditorLink(
                "Open shared CRE graphics",
                "Current tileset",
                AsmEditorTarget.Tileset(null),
            ),
        )

        for (entry in EnemySpriteGraphics.EDITOR_ENEMIES) {
            val target = AsmEditorTarget.Sprite(entry.speciesId)
            val headerAddress = RomConstants.BANK_ENEMY_AI or entry.speciesId
            add(headerAddress, AsmEditorLink("Open ${entry.name}", "Species header", target))
            runCatching { EnemySpriteGraphics.readSpeciesHeader(romParser, entry.speciesId) }
                .getOrNull()
                ?.let { header ->
                    add(header.tileDataAddress, AsmEditorLink("Open ${entry.name}", "Sprite graphics", target))
                    add(
                        (header.aiBank shl 16) or header.palettePointer,
                        AsmEditorLink("Open ${entry.name}", "Sprite palette", target),
                    )
                }
        }

        for (songSet in SpcData.SONG_SETS) {
            val address = runCatching { SpcData.readSongSetPointer(romParser, songSet) }.getOrNull()
                ?.takeIf { it != 0 } ?: continue
            val track = SpcData.KNOWN_TRACKS.firstOrNull { it.songSet == songSet }
            add(
                address,
                AsmEditorLink(
                    title = "Open ${track?.name ?: "song set \$${songSet.hex(2)}"}",
                    detail = "Sound · song set \$${songSet.hex(2)}",
                    target = AsmEditorTarget.Sound(songSet),
                ),
            )
        }

        return AsmSemanticIndex(byAddress.mapValues { (_, links) -> links.distinctBy(AsmEditorLink::target) })
    }

    fun linksFor(
        snesAddress: Int,
        assetPath: String? = null,
        romParser: RomParser,
    ): List<AsmEditorLink> = buildIndex(romParser).linksFor(snesAddress, assetPath)
}

private fun bossSpeciesForAsset(path: String): Int? {
    val normalized = path.lowercase()
    return when {
        "minikraid" in normalized -> 0xE0FF
        "kraid" in normalized -> 0xE2BF
        "phantoon" in normalized -> 0xE4BF
        "crocomire" in normalized -> 0xDDBF
        "sporespawn" in normalized -> 0xDF3F
        "bombtorizo" in normalized || "goldentorizo" in normalized ||
            path == "Tiles_Torizo.bin" -> 0xEEFF
        "ridley" in normalized -> 0xE17F
        "draygon" in normalized -> 0xDE3F
        "motherbrain" in normalized -> 0xEC3F
        "botwoon" in normalized -> 0xF293
        path == "Tiles_Metroid.bin" -> 0xDD7F
        else -> null
    }
}

private fun Int.hex(width: Int): String = toString(16).uppercase().padStart(width, '0')

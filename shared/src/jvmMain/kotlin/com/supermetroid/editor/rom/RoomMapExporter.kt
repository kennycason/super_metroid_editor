package com.supermetroid.editor.rom

import com.supermetroid.editor.data.RoomInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

@Serializable
data class RoomMapMetadata(
    val roomId: Int,
    val roomIdHex: String,
    val handle: String,
    val name: String,
    val area: Int,
    val areaName: String,
    val mapX: Int,
    val mapY: Int,
    val widthScreens: Int,
    val heightScreens: Int,
)

data class RoomMapExportResult(
    val renderedCount: Int,
    val failedCount: Int,
    val metadata: List<RoomMapMetadata>,
)

class RoomMapExporter(private val parser: RomParser) {

    private data class ItemSpriteCoord(val x: Int, val y: Int)

    private val itemSpriteCoords = mapOf(
        "Morph Ball" to ItemSpriteCoord(0, 0),
        "Bomb" to ItemSpriteCoord(32, 0),
        "Energy Tank" to ItemSpriteCoord(64, 0),
        "Missile" to ItemSpriteCoord(0, 16),
        "Super Missile" to ItemSpriteCoord(32, 16),
        "Power Bomb" to ItemSpriteCoord(64, 16),
        "Reserve Tank" to ItemSpriteCoord(96, 16),
        "Hi-Jump Boots" to ItemSpriteCoord(0, 32),
        "Speed Booster" to ItemSpriteCoord(32, 32),
        "Grapple Beam" to ItemSpriteCoord(64, 32),
        "X-Ray Scope" to ItemSpriteCoord(96, 32),
        "Spring Ball" to ItemSpriteCoord(0, 48),
        "Space Jump" to ItemSpriteCoord(32, 48),
        "Screw Attack" to ItemSpriteCoord(64, 48),
        "Charge Beam" to ItemSpriteCoord(96, 48),
        "Spazer" to ItemSpriteCoord(0, 64),
        "Wave Beam" to ItemSpriteCoord(32, 64),
        "Ice Beam" to ItemSpriteCoord(64, 64),
        "Plasma Beam" to ItemSpriteCoord(96, 64),
        "Varia Suit" to ItemSpriteCoord(0, 80),
        "Gravity Suit" to ItemSpriteCoord(32, 80),
    )

    private val itemPlmLookup: Map<Int, Pair<String, ItemSpriteCoord?>> = buildMap {
        for (item in RomParser.ITEM_DEFS) {
            val coord = itemSpriteCoords[item.name]
            val entry = item.name to coord
            put(item.visibleId, entry)
            put(item.chozoId, entry)
            put(item.hiddenId, entry)
        }
    }

    private fun loadItemSpriteSheet(): BufferedImage? =
        RoomMapExporter::class.java.getResourceAsStream("/item_sprites.png")
            ?.use { ImageIO.read(it) }

    fun exportMetadata(
        roomInfos: List<RoomInfo> = parser.roomCatalog.rooms,
    ): List<RoomMapMetadata> {
        return roomInfos.mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            RoomMapMetadata(
                roomId = roomId,
                roomIdHex = "0x${roomId.toString(16).uppercase()}",
                handle = info.handle,
                name = info.name,
                area = room.area,
                areaName = room.areaName,
                mapX = room.mapX,
                mapY = room.mapY,
                widthScreens = room.width,
                heightScreens = room.height,
            )
        }
    }

    fun renderToZip(
        output: OutputStream,
        roomInfos: List<RoomInfo> = parser.roomCatalog.rooms,
        showItems: Boolean = false,
        highlightItems: Boolean = false,
    ): RoomMapExportResult {
        val renderer = MapRenderer(parser)
        val metadata = mutableListOf<RoomMapMetadata>()
        var rendered = 0
        var failed = 0
        val spriteSheet = if (showItems) loadItemSpriteSheet() else null

        ZipOutputStream(output).use { zip ->
            for (info in roomInfos) {
                val roomId = info.getRoomIdAsInt()
                val room = parser.readRoomHeader(roomId) ?: continue

                val meta = RoomMapMetadata(
                    roomId = roomId,
                    roomIdHex = "0x${roomId.toString(16).uppercase()}",
                    handle = info.handle,
                    name = info.name,
                    area = room.area,
                    areaName = room.areaName,
                    mapX = room.mapX,
                    mapY = room.mapY,
                    widthScreens = room.width,
                    heightScreens = room.height,
                )
                metadata.add(meta)

                val renderData = renderer.renderRoom(room)
                if (renderData == null || renderData.width == 0 || renderData.height == 0) {
                    failed++
                    continue
                }

                val img = BufferedImage(renderData.width, renderData.height, BufferedImage.TYPE_INT_ARGB)
                img.setRGB(0, 0, renderData.width, renderData.height, renderData.pixels, 0, renderData.width)

                if (showItems) {
                    val allPlms = parser.getAllPlmEntriesForRoom(roomId)
                    drawItemIcons(img, allPlms, spriteSheet, highlightItems)
                }

                val filename = "${roomId.toString(16).lowercase()}.png"
                zip.putNextEntry(ZipEntry(filename))
                ImageIO.write(img, "PNG", zip)
                zip.closeEntry()
                rendered++
            }

            val json = Json { prettyPrint = true }
            zip.putNextEntry(ZipEntry("rooms.json"))
            zip.write(json.encodeToString(metadata).toByteArray())
            zip.closeEntry()
        }

        return RoomMapExportResult(rendered, failed, metadata)
    }

    private fun drawItemIcons(
        img: BufferedImage,
        plms: List<RomParser.PlmEntry>,
        spriteSheet: BufferedImage?,
        highlight: Boolean,
    ) {
        val g2 = img.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        val itemColor = Color(0xFF, 0xCC, 0x00)

        for (plm in plms) {
            val (name, sprite) = itemPlmLookup[plm.id] ?: continue
            val cx = plm.x * 16 + 8
            val cy = plm.y * 16 + 8

            if (spriteSheet != null && sprite != null) {
                if (highlight) {
                    g2.color = Color(0, 0, 0, 160)
                    g2.fillRoundRect(cx - 10, cy - 10, 20, 20, 4, 4)
                    g2.color = itemColor
                    g2.stroke = BasicStroke(1f)
                    g2.drawRoundRect(cx - 10, cy - 10, 20, 20, 4, 4)
                }
                g2.drawImage(
                    spriteSheet,
                    cx - 8, cy - 8, cx + 8, cy + 8,
                    sprite.x, sprite.y, sprite.x + 16, sprite.y + 16,
                    null,
                )
            } else {
                val label = name.take(2)
                g2.color = Color(0, 0, 0, 200)
                g2.fillRoundRect(cx - 8, cy - 8, 16, 16, 4, 4)
                g2.color = itemColor
                g2.drawRoundRect(cx - 8, cy - 8, 16, 16, 4, 4)
                g2.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 9)
                g2.color = Color.WHITE
                val fm = g2.fontMetrics
                val labelW = fm.stringWidth(label)
                g2.drawString(label, cx - labelW / 2, cy + (fm.ascent - fm.descent) / 2)
            }
        }

        g2.dispose()
    }
}

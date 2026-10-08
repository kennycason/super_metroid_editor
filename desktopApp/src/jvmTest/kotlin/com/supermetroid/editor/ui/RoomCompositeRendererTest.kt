package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomRenderData
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

class RoomCompositeRendererTest {

    @Test
    fun `door cap badges use their actual PLM color`() {
        val expected = mapOf(
            0xC842 to RomParser.DOOR_CAP_GREY,
            0xC85A to RomParser.DOOR_CAP_YELLOW,
            0xC872 to RomParser.DOOR_CAP_GREEN,
            0xC88A to RomParser.DOOR_CAP_RED,
            0xC8A2 to RomParser.DOOR_CAP_BLUE,
        )

        for ((plmId, argb) in expected) {
            assertEquals(argb, doorCapOverlayBorderColor(plmId)?.rgb, "PLM 0x${plmId.toString(16)}")
        }
        assertNull(doorCapOverlayBorderColor(0xB703), "A scroll PLM must not receive a door color")
    }

    @Test
    fun `meta names hide non-item map text`() {
        val data = roomRenderData(
            plmEntries = listOf(
                RomParser.PlmEntry(0xC884, 4, 3, 0), // Green Down Door
                RomParser.PlmEntry(0xB703, 8, 3, 0), // Scroll trigger
            ),
            enemyEntries = listOf(
                RomParser.EnemyEntry(0xE23F, 168, 56, 0, 0), // Ceres Door FX
            ),
        )
        val activeOverlays = setOf(TileOverlay.ITEMS, TileOverlay.SCROLL_PLMS, TileOverlay.ENEMIES, TileOverlay.SCROLLS)

        val withMetaNames = buildCompositeImage(
            data = data,
            activeOverlays = activeOverlays,
            showGrid = false,
            scrollData = intArrayOf(2),
            roomWidthScreens = 1,
            roomHeightScreens = 1,
            showItemNames = false,
            showMetaNames = true,
            showEnemyNames = true,
        )
        val withoutMetaNames = buildCompositeImage(
            data = data,
            activeOverlays = activeOverlays,
            showGrid = false,
            scrollData = intArrayOf(2),
            roomWidthScreens = 1,
            roomHeightScreens = 1,
            showItemNames = false,
            showMetaNames = false,
            showEnemyNames = true,
        )

        assertFalse(
            withMetaNames.pixels().contentEquals(withoutMetaNames.pixels()),
            "Meta labels should affect the rendered image when enabled",
        )
    }

    @Test
    fun `meta names do not hide regular item or enemy names`() {
        val data = roomRenderData(
            itemBlocks = setOf(2 * 16 + 2),
            plmEntries = listOf(RomParser.PlmEntry(0xEEDB, 2, 2, 0)), // Missile
            enemyEntries = listOf(RomParser.EnemyEntry(0xD93F, 128, 64, 0, 0)), // Sidehopper
        )
        val activeOverlays = setOf(TileOverlay.ITEMS, TileOverlay.ENEMIES)

        val withMetaNames = buildCompositeImage(
            data = data,
            activeOverlays = activeOverlays,
            showGrid = false,
            showItemNames = true,
            showMetaNames = true,
            showEnemyNames = true,
        )
        val withoutMetaNames = buildCompositeImage(
            data = data,
            activeOverlays = activeOverlays,
            showGrid = false,
            showItemNames = true,
            showMetaNames = false,
            showEnemyNames = true,
        )

        assertTrue(
            withMetaNames.pixels().contentEquals(withoutMetaNames.pixels()),
            "Regular item and enemy labels should remain controlled by their existing switches",
        )
    }

    @Test
    fun `screen coordinates render independently from the grid`() {
        val data = roomRenderData()
        val plain = buildCompositeImage(
            data = data,
            activeOverlays = emptySet(),
            showGrid = false,
            roomWidthScreens = 1,
            roomHeightScreens = 1,
        )
        val gridOnly = buildCompositeImage(
            data = data,
            activeOverlays = emptySet(),
            showGrid = true,
            roomWidthScreens = 1,
            roomHeightScreens = 1,
        )
        val coordinatesOnly = buildCompositeImage(
            data = data,
            activeOverlays = setOf(TileOverlay.SCREEN_COORDINATES),
            showGrid = false,
            roomWidthScreens = 1,
            roomHeightScreens = 1,
        )

        assertFalse(plain.pixels().contentEquals(gridOnly.pixels()), "Grid should draw screen boundaries")
        assertFalse(
            plain.pixels().contentEquals(coordinatesOnly.pixels()),
            "Screen Coords should draw labels even when Grid is off",
        )
        assertFalse(
            gridOnly.pixels().contentEquals(coordinatesOnly.pixels()),
            "Grid and Screen Coords should remain separate display options",
        )
    }

    private fun roomRenderData(
        itemBlocks: Set<Int> = emptySet(),
        plmEntries: List<RomParser.PlmEntry> = emptyList(),
        enemyEntries: List<RomParser.EnemyEntry> = emptyList(),
    ): RoomRenderData {
        val width = 256
        val height = 256
        return RoomRenderData(
            width = width,
            height = height,
            pixels = IntArray(width * height) { 0xFF202020.toInt() },
            blocksWide = 16,
            blocksTall = 16,
            blockTypes = IntArray(16 * 16),
            btsData = ByteArray(16 * 16),
            itemBlocks = itemBlocks,
            plmEntries = plmEntries,
            enemyEntries = enemyEntries,
        )
    }

    private fun BufferedImage.pixels(): IntArray =
        IntArray(width * height).also { getRGB(0, 0, width, height, it, 0, width) }
}

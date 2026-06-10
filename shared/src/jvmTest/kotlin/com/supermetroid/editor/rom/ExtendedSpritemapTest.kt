package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtendedSpritemapTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `Ridley facing left extended spritemap parses four OAM children`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val ext = smap.parseExtendedSpritemap(0xA6E983)
        assertNotNull(ext, "Ridley facing-left extended spritemap should parse")
        ext!!

        assertEquals(4, ext.children.size, "Ridley body should have four OAM child spritemaps")
        assertTrue(ext.children.all { it is EnemySpritemap.ExtendedChild.Oam },
            "Ridley body children should be OAM spritemaps")
        assertEquals(
            listOf(0xA6ED29, 0xA6ED8E, 0xA6ED95, 0xA6EC5B),
            ext.children.map { it.snesAddress },
            "Ridley body should compose legs, hand, torso, and head-neck spritemaps"
        )

        val flattened = smap.flattenExtendedSpritemap(ext)
        assertEquals(26, flattened.entries.size, "Ridley closed-mouth body should flatten to 26 OAM entries")
    }

    @Test
    fun `Ridley extended spritemap renders as one body frame`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, 0xE17F) ?: return
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xE17F) ?: return
        val ext = smap.parseExtendedSpritemap(0xA6E983) ?: return

        val rendered = smap.renderExtendedSpritemap(ext, tileData, palette)
        assertNotNull(rendered, "Ridley extended body should render")
        rendered!!

        assertTrue(rendered.width in 32..160, "Ridley body width should be bounded (${rendered.width})")
        assertTrue(rendered.height in 32..160, "Ridley body height should be bounded (${rendered.height})")
        assertTrue(rendered.pixels.count { (it ushr 24) > 0 } > 500,
            "Ridley body should have visible non-transparent pixels")
    }

    @Test
    fun `Crocomire initial extended spritemap parses OAM and BG2 tilemap children`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val ext = smap.parseExtendedSpritemap(0xA4C2EC)
        assertNotNull(ext, "Crocomire initial extended spritemap should parse")
        ext!!

        assertEquals(7, ext.children.size, "Crocomire initial frame should have seven multibox children")
        assertEquals(4, ext.children.count { it is EnemySpritemap.ExtendedChild.Oam },
            "Crocomire initial frame should have four OAM children")
        assertEquals(3, ext.children.count { it is EnemySpritemap.ExtendedChild.Tilemap },
            "Crocomire initial frame should have three BG2 tilemap children")

        val tilemapTiles = ext.children
            .filterIsInstance<EnemySpritemap.ExtendedChild.Tilemap>()
            .sumOf { it.tilemap.tileCount }
        assertTrue(tilemapTiles > 100, "Crocomire BG2 tilemap children should include substantial body tiles")
    }

    @Test
    fun `Crocomire extended tilemap decodes BG2 destination runs`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val tilemap = smap.parseExtendedTilemap(0xA4D51C)
        assertNotNull(tilemap, "Crocomire extended tilemap 0 should parse")
        tilemap!!

        assertEquals(8, tilemap.runs.size, "Crocomire tilemap 0 should have eight destination runs")
        assertEquals(0x2000, tilemap.runs.first().dest, "First run should target enemy BG2 tilemap base")
        assertEquals(96, tilemap.tileCount, "Crocomire tilemap 0 should contain 96 tile words")
    }

    @Test
    fun `extended tilemaps can render behind OAM children`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val tileData = solidTileData(3)
        val palette = testPalette()
        val ext = EnemySpritemap.ExtendedSpritemap(
            children = listOf(
                EnemySpritemap.ExtendedChild.Oam(
                    xOffset = 0,
                    yOffset = 0,
                    spritemap = EnemySpritemap.Spritemap(
                        entries = listOf(
                            EnemySpritemap.OamEntry(
                                xOffset = 0,
                                yOffset = 0,
                                tileNum = 1,
                                palRow = 0,
                                hFlip = false,
                                vFlip = false,
                                is16x16 = false
                            )
                        ),
                        snesAddress = 0
                    ),
                    hitboxPtr = 0
                ),
                EnemySpritemap.ExtendedChild.Tilemap(
                    xOffset = 0,
                    yOffset = 0,
                    tilemap = EnemySpritemap.ExtendedTilemap(
                        runs = listOf(EnemySpritemap.ExtendedTilemapRun(0x2000, listOf(2))),
                        snesAddress = 0
                    ),
                    hitboxPtr = 0
                )
            ),
            snesAddress = 0
        )

        val defaultRendered = smap.renderExtendedSpritemap(ext, tileData, palette)
        assertNotNull(defaultRendered, "Synthetic extended spritemap should render")
        assertEquals(palette[2] or (0xFF shl 24), defaultRendered!!.pixels.first(),
            "Default order should preserve previous overlay behavior")

        val bgBehindRendered = smap.renderExtendedSpritemap(
            ext,
            tileData,
            palette,
            EnemySpritemap.RenderOptions(extendedTilemapsBehindOam = true)
        )
        assertNotNull(bgBehindRendered, "Synthetic extended spritemap should render with BG2 behind OAM")
        assertEquals(palette[1] or (0xFF shl 24), bgBehindRendered!!.pixels.first(),
            "Crocomire-style order should draw OAM over BG2 tilemaps")
    }

    @Test
    fun `extended tilemaps replace cells and blank tile clears previous cell`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val tileData = solidTileData(3)
        val palette = testPalette()
        val ext = EnemySpritemap.ExtendedSpritemap(
            children = listOf(
                EnemySpritemap.ExtendedChild.Tilemap(
                    xOffset = 0,
                    yOffset = 0,
                    tilemap = EnemySpritemap.ExtendedTilemap(
                        runs = listOf(
                            EnemySpritemap.ExtendedTilemapRun(0x2000, listOf(1)),
                            EnemySpritemap.ExtendedTilemapRun(0x2000, listOf(0x0338)),
                            EnemySpritemap.ExtendedTilemapRun(0x2002, listOf(2))
                        ),
                        snesAddress = 0
                    ),
                    hitboxPtr = 0
                )
            ),
            snesAddress = 0
        )

        val rendered = smap.renderExtendedSpritemap(ext, tileData, palette)
        assertNotNull(rendered, "Tilemap with one remaining cell should render")
        rendered!!

        assertEquals(8, rendered.width, "Blanked first cell should be removed from the render bounds")
        assertEquals(8, rendered.height)
        assertTrue(rendered.pixels.all { it == (palette[2] or (0xFF shl 24)) },
            "Only the later non-blank cell should remain")
    }

    @Test
    fun `Crocomire renders BG2 tilemap layer behind OAM`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val poses = scanner.scanPoses(0xDDBF, minEntries = 3)
        val initialPose = poses.find { it.frame.snesAddress == 0xA4C2EC }
        assertNotNull(initialPose, "Should find Crocomire initial extended pose")
        assertTrue(initialPose!!.renderOptions.extendedTilemapsBehindOam,
            "Crocomire's BG2 body layer should render under OAM head/limb children")
    }

    @Test
    fun `Crocomire render tile data injects enemy graphics into room VRAM layout`() {
        val rp = loadTestRom() ?: return
        val rawEnemyTiles = EnemySpriteGraphics.loadEnemyTileData(rp, 0xDDBF) ?: return
        val renderTiles = EnemySpriteGraphics.loadEnemyRenderTileData(rp, 0xDDBF, rawEnemyTiles) ?: return

        assertTrue(renderTiles.size > rawEnemyTiles.size,
            "Crocomire pose rendering should include room BG tiles plus injected enemy tiles")

        val injectedStart = 0xD0 * RomConstants.BYTES_PER_4BPP_TILE
        assertEquals(
            rawEnemyTiles[0],
            renderTiles[injectedStart],
            "Crocomire raw enemy tile 0 should land at VRAM tile \$D0 for BG/OAM rendering"
        )
    }

    @Test
    fun `extended OAM can preserve game tile base bits`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val tileData = solidTileData(0x200)
        val palette = testPalette()
        val ext = EnemySpritemap.ExtendedSpritemap(
            children = listOf(
                EnemySpritemap.ExtendedChild.Oam(
                    xOffset = 0,
                    yOffset = 0,
                    spritemap = EnemySpritemap.Spritemap(
                        entries = listOf(
                            EnemySpritemap.OamEntry(
                                xOffset = 0,
                                yOffset = 0,
                                tileNum = 0x00,
                                palRow = 0,
                                hFlip = false,
                                vFlip = false,
                                is16x16 = false
                            )
                        ),
                        snesAddress = 0
                    ),
                    hitboxPtr = 0
                )
            ),
            snesAddress = 0
        )

        val rendered = smap.renderExtendedSpritemap(
            ext,
            tileData,
            palette,
            EnemySpritemap.RenderOptions(
                oamTileNumberMode = EnemySpritemap.OamTileNumberMode.OR_BASE_LOW_9,
                oamTileNumberBase = 0x1A0
            )
        )
        assertNotNull(rendered, "Synthetic OAM frame should render")
        assertTrue(rendered!!.pixels.any { (it ushr 24) > 0 },
            "Renderer should draw from the base-adjusted OAM tile index")
    }

    private fun solidTileData(tileCount: Int): ByteArray {
        val data = ByteArray(tileCount * RomConstants.BYTES_PER_4BPP_TILE)
        for (tile in 0 until tileCount) {
            fillSolidTile(data, tile, tile.coerceIn(0, 15))
        }
        return data
    }

    private fun fillSolidTile(data: ByteArray, tile: Int, color: Int) {
        val offset = tile * RomConstants.BYTES_PER_4BPP_TILE
        for (row in 0 until 8) {
            if ((color and 1) != 0) data[offset + row * 2] = 0xFF.toByte()
            if ((color and 2) != 0) data[offset + row * 2 + 1] = 0xFF.toByte()
            if ((color and 4) != 0) data[offset + 16 + row * 2] = 0xFF.toByte()
            if ((color and 8) != 0) data[offset + 16 + row * 2 + 1] = 0xFF.toByte()
        }
    }

    private fun testPalette(): IntArray = IntArray(16) { idx ->
        when (idx) {
            1 -> 0x00112233
            2 -> 0x00445566
            else -> idx
        }
    }
}

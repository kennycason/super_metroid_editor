package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class EnemySpritemapTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `parse Zoomer spritemap from instruction list`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val zoomerSmap = smap.findDefaultSpritemap(0xDCFF)
        assertNotNull(zoomerSmap, "Should find Zoomer spritemap")
        assertEquals(4, zoomerSmap!!.entries.size, "Zoomer spritemap should have 4 OAM entries")

        val has16x16 = zoomerSmap.entries.any { it.is16x16 }
        val has8x8 = zoomerSmap.entries.any { !it.is16x16 }
        assertTrue(has16x16, "Zoomer should have 16x16 tiles")
        assertTrue(has8x8, "Zoomer should have 8x8 tiles")

        // Tile numbers should be in the 0x100-0x1FF range (name table 1)
        for (entry in zoomerSmap.entries) {
            assertTrue(entry.tileNum in 0x100..0x1FF,
                "Tile number 0x${entry.tileNum.toString(16)} should be in name table 1")
        }
    }

    @Test
    fun `parse Cacatac spritemap via JSR following`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val cacSmap = smap.findDefaultSpritemap(0xCFFF)
        assertNotNull(cacSmap, "Should find Cacatac spritemap")
        assertTrue(cacSmap!!.entries.size >= 4,
            "Cacatac should have at least 4 OAM entries (has ${cacSmap.entries.size})")
    }

    @Test
    fun `parse Skree spritemap via alternate instruction format`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val skreeSmap = smap.findDefaultSpritemap(0xD7FF)
        assertNotNull(skreeSmap, "Should find Skree spritemap")
        assertEquals(4, skreeSmap!!.entries.size, "Skree spritemap should have 4 OAM entries")
    }

    @Test
    fun `parse Sidehopper spritemap via cross-function LDA abs,x trace`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val sidehopperSmap = smap.findDefaultSpritemap(0xD93F)
        assertNotNull(sidehopperSmap, "Should find Sidehopper spritemap via cross-function trace")
        assertTrue(sidehopperSmap!!.entries.size >= 3,
            "Sidehopper should have at least 3 OAM entries (has ${sidehopperSmap.entries.size})")
    }

    @Test
    fun `render assembled sprites for supported enemies`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val enemies = mapOf(
            0xDCFF to "Zoomer",
            0xDC7F to "Zeela",
            0xD93F to "Sidehopper",
            0xD7FF to "Skree",
            0xCFFF to "Cacatac",
        )

        for ((speciesId, name) in enemies) {
            val defaultSmap = smap.findDefaultSpritemap(speciesId)
            assertNotNull(defaultSmap, "$name: should find default spritemap")

            val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId)
            assertNotNull(palette, "$name: should read palette")

            val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId)
            assertNotNull(tileData, "$name: should load tile data")

            val assembled = smap.renderSpritemap(defaultSmap!!, tileData!!, palette!!)
            assertNotNull(assembled, "$name: should render assembled sprite")

            assertTrue(assembled!!.width > 0, "$name: width should be positive")
            assertTrue(assembled.height > 0, "$name: height should be positive")

            val nonTransparent = assembled.pixels.count { (it ushr 24) and 0xFF > 0 }
            assertTrue(nonTransparent > 0, "$name: should have visible pixels")
            assertTrue(nonTransparent.toFloat() / (assembled.width * assembled.height) > 0.2f,
                "$name: should have >20% fill rate (has ${nonTransparent * 100 / (assembled.width * assembled.height)}%)")
        }
    }

    @Test
    fun `find animation frames for Zoomer`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val frames = smap.findAnimationFrames(0xDCFF)
        assertTrue(frames.size >= 3, "Zoomer should have at least 3 animation frames (has ${frames.size})")

        for ((i, frame) in frames.withIndex()) {
            assertTrue(frame.duration in 1..120,
                "Frame $i duration ${frame.duration} should be in valid range")
            assertTrue(frame.spritemap.entries.isNotEmpty(),
                "Frame $i should have spritemap entries")
        }
    }

    @Test
    fun `Space Pirate animations render extended spritemaps as full frames`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val pirates = mapOf(
            0xF353 to 5, // Wall pirate climbing loop
            0xF593 to 3, // Ninja pirate idle loop
            0xF653 to 8  // Walking pirate stride loop
        )

        for ((speciesId, minFrames) in pirates) {
            val frames = smap.findAnimationFrames(speciesId)
            assertTrue(
                frames.size >= minFrames,
                "Pirate ${speciesId.toString(16)} should have at least $minFrames frames (has ${frames.size})"
            )
            assertTrue(
                frames.first().renderableFrame is EnemySpritemap.RenderableFrame.Extended,
                "Pirate ${speciesId.toString(16)} first frame should be an extended spritemap"
            )
            assertTrue(
                frames.first().spritemap.entries.size >= 6,
                "Pirate ${speciesId.toString(16)} first frame should flatten to a full body"
            )

            val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return
            val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
            val animation = smap.buildAnimation(speciesId, tileData, palette, "Space Pirate")
            assertNotNull(animation, "Pirate ${speciesId.toString(16)} should build an animation")
            val rendered = animation!!.frames.first()
            assertTrue(rendered.width >= 16, "Pirate ${speciesId.toString(16)} frame width should be full-sized")
            assertTrue(rendered.height >= 24, "Pirate ${speciesId.toString(16)} frame height should be full-sized")
            assertTrue(
                rendered.pixels.count { (it ushr 24) > 0 } > 100,
                "Pirate ${speciesId.toString(16)} frame should have substantial visible pixels"
            )
        }
    }

    @Test
    fun `spritemap OAM entry values are reasonable`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val zoomerSmap = smap.findDefaultSpritemap(0xDCFF) ?: return
        for (entry in zoomerSmap.entries) {
            assertTrue(entry.xOffset in -128..128,
                "X offset ${entry.xOffset} should be in reasonable range")
            assertTrue(entry.yOffset in -128..128,
                "Y offset ${entry.yOffset} should be in reasonable range")
            assertTrue(entry.palRow in 0..7,
                "Palette row ${entry.palRow} should be 0-7")
            assertTrue(entry.tileNum in 0..511,
                "Tile number ${entry.tileNum} should be 0-511 (9-bit)")
        }
    }
}

package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class NativeSpriteIOTest {
    @TempDir lateinit var directory: File

    @Test
    fun `source PNG round trip preserves exact bytes for 4bpp Mode7 and G4`() {
        val parser = TestRomHelper.requireRomParser()
        val state = NativeSpriteEditorState({ SmEditProject("base.smc").customGfx }, {})
        for ((key, palette) in listOf("9AD200" to 0x9A81A0, "94E000" to 0x8CE1E9, "87B364" to 0xAAD745)) {
            val sheet = state.loadSheet(parser, key, palette)
            val file = File(directory, "$key.png")
            exportNativeSpritePng(file, sheet)
            val imported = importNativeSpritePng(file, sheet)
            assertArrayEquals(sheet.pixels, imported, key)
            assertArrayEquals(sheet.raw, state.encodePixels(sheet, imported), key)
        }
    }

    @Test
    fun `source imports match the selected palette validate dimensions and export to ROM`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject("base.smc")
        val state = NativeSpriteEditorState({ project.customGfx }, {})
        val sheet = state.loadSheet(parser, "9AD200", 0x9A81A0)
        val file = File(directory, "source.png")
        exportNativeSpritePng(file, sheet)
        val image = ImageIO.read(file)
        image.setRGB(0, 0, 0xFF057BD1.toInt()) // Deliberately outside the SNES palette.
        ImageIO.write(image, "png", file)
        val pixels = importNativeSpritePng(file, sheet)
        assertTrue(pixels[0] in sheet.palette)
        assertTrue(pixels[0] ushr 24 != 0)
        state.applyPixels(parser, sheet, pixels)
        val expected = state.encodePixels(sheet, pixels)
        val built = requireNotNull(RomExporter(project, parser).build())
        assertArrayEquals(expected, RomVisualAssets.raw(RomParser(built.resultRom), 0x9AD200, 0x2000))
        val bad = File(directory, "assembled.png")
        ImageIO.write(BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB), "png", bad)
        val before = project.customGfx.visualTileBlocks.toMap()
        assertThrows(IllegalArgumentException::class.java) { importNativeSpritePng(bad, sheet) }
        assertEquals(before, project.customGfx.visualTileBlocks)
    }

    @Test
    fun `all new workspace selections choose their actual projectile palette`() {
        val parser = TestRomHelper.requireRomParser()
        val workspace = VISUAL_SPRITE_WORKSPACES.single { it.index == -2 }
        val superIndex = ProjectileSprites.WEAPONS.indexOfFirst { it.name == "Super Missile" }
        val superTarget = visualSpriteTargets(parser, workspace, superIndex).single()
        // The right-facing source map at $93:AE0F explicitly uses OBJ palette 5.
        assertEquals(5, superTarget.defaultRow)
        val state = NativeSpriteEditorState({ SmEditProject("base.smc").customGfx }, {})
        val sheet = state.loadSheet(parser, superTarget.sourceKey, superTarget.paletteBase + superTarget.defaultRow * 32)
        assertArrayEquals(RomVisualAssets.palette(parser, 0x9A81A0, 16), sheet.palette)
        for ((index, weapon) in ProjectileSprites.WEAPONS.withIndex()) for (direction in ProjectileSprites.DIRECTIONS.indices) {
            val target = visualSpriteTargets(parser, workspace, index, direction).first()
            val animation = requireNotNull(ProjectileSprites(parser).render(weapon, direction))
            val palette = RomVisualAssets.palette(parser, target.paletteBase + target.defaultRow * 32, 16)
            assertTrue(animation.frames.first().pixels.filter { it ushr 24 != 0 }.all { it in palette }, "${weapon.name} $direction")
        }
    }

    @Test
    fun `combined beam choices edit the matching shared Plasma graphics and palettes`() {
        val parser = TestRomHelper.requireRomParser()
        val workspace = VISUAL_SPRITE_WORKSPACES.single { it.index == -2 }
        for (mask in 0xC..0xF) {
            val combined = ProjectileSprites.WEAPONS.indexOfFirst { it.beam == mask && !it.charged }
            val plasma = ProjectileSprites.WEAPONS.indexOfFirst {
                it.beam == ProjectileSprites.nativeBeamMask(mask) && !it.charged
            }
            assertEquals(
                visualSpriteTargets(parser, workspace, plasma),
                visualSpriteTargets(parser, workspace, combined),
                ProjectileSprites.BEAMS[mask],
            )
        }
    }

    @Test
    fun `new sprites export native frames timed GIFs and animation sheets`() {
        val parser = TestRomHelper.requireRomParser()
        val examples = listOf(
            requireNotNull(ProjectileSprites(parser).render(ProjectileSprites.WEAPONS.single { it.name == "Super Missile" })),
            ShipSpritemap(parser).animations().first { it.frames.size > 1 },
            requireNotNull(CinematicSprites(parser).render(CinematicSprites.SEQUENCES.first())),
            requireNotNull(SamusDeathSprites(parser).render(0, false)),
            SpriteAnimation("G4", listOf(requireNotNull(TourianStatueSprites(parser).renderComplete())), false),
        )
        for ((index, animation) in examples.withIndex()) {
            val frame = animation.frames.first()
            val png = File(directory, "$index-frame.png")
            exportFramePng(frame, png)
            val image = ImageIO.read(png)
            assertEquals(frame.width, image.width); assertEquals(frame.height, image.height)
            assertArrayEquals(frame.pixels, image.getRGB(0, 0, frame.width, frame.height, null, 0, frame.width))
            val gif = File(directory, "$index.gif")
            exportAnimationGif(animation, gif)
            ImageIO.createImageInputStream(gif).use { stream ->
                val reader = ImageIO.getImageReadersByFormatName("gif").next()
                try {
                    reader.input = stream
                    assertEquals(animation.frames.size, reader.getNumImages(true))
                    assertEquals(frame.width, reader.getWidth(0))
                    assertEquals(frame.height, reader.getHeight(0))
                    val metadata = reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0")
                    val control = (0 until metadata.childNodes.length).map { metadata.childNodes.item(it) }.single { it.nodeName == "GraphicControlExtension" }
                    assertTrue(control.attributes.getNamedItem("delayTime").nodeValue.toInt() > 0)
                } finally { reader.dispose() }
            }
            val sheetFile = File(directory, "$index-animation.png")
            exportAnimationSheet(animation, sheetFile)
            val (pixels, width, height) = renderSpriteSheet(animation.frames, columns = 8)
            val sheetImage = ImageIO.read(sheetFile)
            assertArrayEquals(pixels, sheetImage.getRGB(0, 0, width, height, null, 0, width))
        }
    }

    @Test
    fun `Golden Four pixels and palette update the assembled preview and survive export`() {
        val parser = TestRomHelper.requireRomParser()
        var project = SmEditProject("base.smc")
        val state = NativeSpriteEditorState({ project.customGfx }, {})
        val sheet = state.loadSheet(parser, "87B364", 0xAAD745)
        val before = requireNotNull(TourianStatueSprites(parser).renderComplete())
        val pixels = sheet.pixels.copyOf()
        for (i in pixels.indices) if (sheet.indices[i] >= 0) pixels[i] = sheet.palette[1]
        state.applyPixels(parser, sheet, pixels)
        val palette = state.palette(parser, 0xAAD745).also { it[1] = 0xFF1850A0.toInt() }
        state.savePalette(parser, 0xAAD745, palette)
        val preview = state.previewParser(parser)
        val after = requireNotNull(TourianStatueSprites(preview).renderComplete())
        assertFalse(before.pixels.contentEquals(after.pixels))
        val saved = File(directory, "g4.smedit")
        assertTrue(ProjectFileService.saveProject(project, saved.absolutePath, null, false) {})
        project = ProjectFileService.loadProject(saved)
        val result = requireNotNull(RomExporter(project, parser).build())
        assertArrayEquals(after.pixels, requireNotNull(TourianStatueSprites(RomParser(result.resultRom)).renderComplete()).pixels)
    }
}

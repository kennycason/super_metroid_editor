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
    fun `Super Metroid title logo exports and imports the exact assembled two-row image`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject("base.smc")
        val state = NativeSpriteEditorState({ project.customGfx }, {})
        val workspace = VISUAL_SPRITE_WORKSPACES.single { it.index == -3 }
        val selected = visualSpriteNames(workspace).indexOf("Super Metroid title logo")
        val asset = requireNotNull(visualSpriteAsset(workspace, selected))
        val target = visualSpriteTargets(parser, workspace, selected).single()

        assertEquals(RomVisualAssets.Format.OAM, asset.format)
        assertEquals("9580D8", target.sourceKey)
        assertEquals(10, target.defaultRow, "The logo OAM entries use OBJ palette 2, absolute palette row 10")
        val sheet = state.loadSheet(parser, target.sourceKey, target.paletteBase + target.defaultRow * 32)
        val composition = createNativeOamComposition(parser, asset, sheet, target.defaultRow)
        val rendered = requireNotNull(RomVisualAssets.render(parser, asset))

        assertEquals(184, composition.width)
        assertEquals(64, composition.height)
        assertEquals(9_600, composition.sourcePixelIndices.count { it >= 0 })
        assertArrayEquals(rendered.pixels, composition.pixels)

        val file = File(directory, "super_metroid_title_logo_editable.png")
        exportNativeOamCompositionPng(file, composition)
        val image = ImageIO.read(file)
        assertEquals(184, image.width)
        assertEquals(64, image.height)
        assertArrayEquals(
            composition.pixels,
            image.getRGB(0, 0, image.width, image.height, null, 0, image.width),
        )
        val imported = importNativeOamCompositionPng(file, composition)
        assertArrayEquals(sheet.pixels, imported)
        assertArrayEquals(sheet.raw, state.encodePixels(sheet, imported))
    }

    @Test
    fun `every exposed cinematic OAM image has an unambiguous exact editable composition`() {
        val parser = TestRomHelper.requireRomParser()
        val state = NativeSpriteEditorState({ SmEditProject("base.smc").customGfx }, {})
        val workspace = VISUAL_SPRITE_WORKSPACES.single { it.index == -3 }
        for (asset in RomVisualAssets.ASSETS.filter { it.format == RomVisualAssets.Format.OAM }) {
            val selected = visualSpriteNames(workspace).indexOf(asset.name)
            val target = visualSpriteTargets(parser, workspace, selected).single()
            val sheet = state.loadSheet(parser, target.sourceKey, target.paletteBase + target.defaultRow * 32)
            val composition = createNativeOamComposition(parser, asset, sheet, target.defaultRow)
            val rendered = requireNotNull(RomVisualAssets.render(parser, asset))
            assertEquals(rendered.width, composition.width, asset.name)
            assertEquals(rendered.height, composition.height, asset.name)
            assertArrayEquals(rendered.pixels, composition.pixels, asset.name)
        }
    }

    @Test
    fun `edited assembled title logo updates its owned title tiles and survives ROM export`() {
        val parser = TestRomHelper.requireRomParser()
        val originalRom = parser.getRomData().copyOf()
        val project = SmEditProject("base.smc")
        val state = NativeSpriteEditorState({ project.customGfx }, {})
        val workspace = VISUAL_SPRITE_WORKSPACES.single { it.index == -3 }
        val selected = visualSpriteNames(workspace).indexOf("Super Metroid title logo")
        val asset = requireNotNull(visualSpriteAsset(workspace, selected))
        val target = visualSpriteTargets(parser, workspace, selected).single()
        val sheet = state.loadSheet(parser, target.sourceKey, target.paletteBase + target.defaultRow * 32)
        val composition = createNativeOamComposition(parser, asset, sheet, target.defaultRow)
        val edited = composition.pixels.copyOf().also { pixels ->
            for (index in pixels.indices) if (composition.sourcePixelIndices[index] >= 0) {
                pixels[index] = sheet.palette[1]
            }
        }
        val file = File(directory, "edited-title-logo.png")
        exportFramePng(SpriteAnimationFrame(edited, composition.width, composition.height), file)

        val imported = importNativeOamCompositionPng(file, composition)
        val owned = composition.sourcePixelIndices.filter { it >= 0 }.toSet()
        for (index in sheet.pixels.indices) if (index !in owned) {
            assertEquals(sheet.pixels[index], imported[index], "Unowned source pixel $index")
        }
        assertTrue(state.applyPixels(parser, sheet, imported))
        val preview = state.previewParser(parser)
        val previewSheet = state.loadSheet(parser, target.sourceKey, target.paletteBase + target.defaultRow * 32)
        val after = createNativeOamComposition(preview, asset, previewSheet, target.defaultRow)
        assertArrayEquals(edited, after.pixels)
        assertArrayEquals(originalRom, parser.getRomData(), "Project editing must not mutate the loaded ROM")

        val built = requireNotNull(RomExporter(project, parser).build())
        val exported = RomParser(built.resultRom)
        assertArrayEquals(
            edited,
            requireNotNull(RomVisualAssets.render(exported, asset)).pixels,
            "The edited assembled logo must be written to the title sprite graphics resource",
        )
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

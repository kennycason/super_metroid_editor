package com.supermetroid.editor.rom

import com.supermetroid.editor.data.IntroImageSource
import com.supermetroid.editor.data.IntroPortraitAnimation
import java.awt.image.BufferedImage

/**
 * Renders the intro the way bank $8B composes it: BG tilemap + gameplay Samus +
 * cinematic OBJ tracks. The old implementation exposed only the OBJ track, which
 * made the scientist scenes look like a single floating tile.
 */
object IntroRomImageResolver {
    val sources = listOf(
        IntroImageSource.ROM_SAMUS_PORTRAIT,
        IntroImageSource.ROM_MOTHER_BRAIN,
        IntroImageSource.ROM_METROID_EGG,
        IntroImageSource.ROM_EGG_HATCHING,
        IntroImageSource.ROM_BABY_DELIVERED,
        IntroImageSource.ROM_BABY_EXAMINED,
    )

    val vignetteSources = sources.filter { it != IntroImageSource.ROM_SAMUS_PORTRAIT }

    fun isRomSource(source: IntroImageSource): Boolean = source in sources

    fun label(source: IntroImageSource): String = when (source) {
        IntroImageSource.NONE -> "None"
        IntroImageSource.ROM_SAMUS_PORTRAIT -> "Native Samus portrait"
        IntroImageSource.ROM_MOTHER_BRAIN -> "Mother Brain fight"
        IntroImageSource.ROM_METROID_EGG -> "Baby discovery (egg)"
        IntroImageSource.ROM_EGG_HATCHING -> "Baby discovery + hatching"
        IntroImageSource.ROM_BABY_DELIVERED -> "Baby delivered"
        IntroImageSource.ROM_BABY_EXAMINED -> "Baby examined"
        IntroImageSource.UPLOADED_PNG -> "Uploaded PNG"
    }

    fun defaultForScene(@Suppress("UNUSED_PARAMETER") index: Int): IntroImageSource =
        IntroImageSource.ROM_SAMUS_PORTRAIT

    fun defaultVignetteForScene(index: Int): IntroImageSource = when (index) {
        0 -> IntroImageSource.ROM_MOTHER_BRAIN
        1 -> IntroImageSource.ROM_EGG_HATCHING
        2 -> IntroImageSource.ROM_BABY_DELIVERED
        3 -> IntroImageSource.ROM_BABY_EXAMINED
        else -> IntroImageSource.NONE
    }

    fun defaultFrame(source: IntroImageSource): Int = when (source) {
        IntroImageSource.ROM_EGG_HATCHING -> 16
        else -> 0
    }

    fun frameCount(parser: RomParser, source: IntroImageSource): Int = frames(parser, source).size

    fun frameDurations(parser: RomParser, source: IntroImageSource): List<Int> = when (source) {
        IntroImageSource.ROM_SAMUS_PORTRAIT -> listOf(128, 10, 10, 10)
        else -> cinematicAnimation(parser, source)?.frames?.map { it.durationTicks.coerceAtLeast(1) }.orEmpty()
    }

    fun render(
        parser: RomParser,
        source: IntroImageSource,
        frameIndex: Int,
        scrollX: Int = 0,
        scrollY: Int = 0,
    ): BufferedImage? {
        val rendered = frames(parser, source, scrollX, scrollY)
        return rendered.getOrNull(frameIndex.coerceIn(0, (rendered.size - 1).coerceAtLeast(0)))
    }

    fun renderPortrait(
        parser: RomParser,
        animation: IntroPortraitAnimation,
        frameIndex: Int = 0,
    ): BufferedImage? {
        val eyeFrame = when (animation) {
            IntroPortraitAnimation.NATIVE_BLINK -> frameIndex.mod(4)
            IntroPortraitAnimation.DEADPAN -> 3
            IntroPortraitAnimation.STATIC_OPEN -> 0
            IntroPortraitAnimation.STATIC_CLOSED -> 2
            IntroPortraitAnimation.OFF -> 0
        }
        return portraitFrames(parser).getOrNull(eyeFrame)
    }

    /** Every returned image is a complete 256×224 SNES viewport. */
    fun frames(
        parser: RomParser,
        source: IntroImageSource,
        scrollX: Int = 0,
        scrollY: Int = 0,
    ): List<BufferedImage> = when (source) {
        IntroImageSource.ROM_SAMUS_PORTRAIT -> portraitFrames(parser)
        IntroImageSource.ROM_MOTHER_BRAIN -> vignetteFrames(parser, source, 0, scrollX, scrollY, samusX = 0x9B, samusY = 0x73)
        IntroImageSource.ROM_METROID_EGG -> vignetteFrames(parser, source, 1, scrollX, scrollY, samusX = 0x78, samusY = 0x93)
        IntroImageSource.ROM_EGG_HATCHING -> discoveryFrames(parser, scrollX, scrollY)
        IntroImageSource.ROM_BABY_DELIVERED -> vignetteFrames(parser, source, 2, scrollX, scrollY)
        IntroImageSource.ROM_BABY_EXAMINED -> vignetteFrames(parser, source, 3, scrollX, scrollY)
        else -> emptyList()
    }

    private fun portraitFrames(parser: RomParser): List<BufferedImage> {
        val asset = RomVisualAssets.ASSETS.single { it.name == "Intro · Samus portrait" }
        val base = RomVisualAssets.render(parser, asset)?.toImage() ?: return emptyList()
        val graphics = parser.decompressLZ2(0x95F90E)
        val colors = RomVisualAssets.palette(parser, 0x8CE3E9, transparentRows = false)
        val words = listOf(
            intArrayOf(0x0F89, 0x0F8A, 0x0F8B, 0x0F99, 0x0F9A, 0x0F9B),
            intArrayOf(0x0F1D, 0x0F1E, 0x0F1F, 0x0F2D, 0x0F2E, 0x0F2F),
            intArrayOf(0x0F3A, 0x0F3B, 0x0F3C, 0x0F4A, 0x0F4B, 0x0F4C),
            intArrayOf(0x0F3D, 0x0F3E, 0x0F3F, 0x0F4D, 0x0F4E, 0x0F4F),
        )
        val decoder = TileDecoder()
        return words.map { eyeWords ->
            copyViewport(base).also { image ->
                eyeWords.forEachIndexed { i, word ->
                    val indices = decoder.decode4bppTileIndices(graphics, (word and 0x3FF) * 32)
                    val palette = word ushr 10 and 7
                    val tileX = 17 + i % 3
                    val tileY = 13 + i / 3
                    for (y in 0..7) for (x in 0..7) {
                        val sx = if (word and 0x4000 != 0) 7 - x else x
                        val sy = if (word and 0x8000 != 0) 7 - y else y
                        image.setRGB(tileX * 8 + x, tileY * 8 + y, colors[palette * 16 + indices[sy * 8 + sx]])
                    }
                }
            }
        }
    }

    private fun discoveryFrames(parser: RomParser, scrollX: Int, scrollY: Int): List<BufferedImage> {
        val background = vignetteBackground(parser, 1, scrollX, scrollY) ?: return emptyList()
        val egg = cinematicAnimation(parser, IntroImageSource.ROM_EGG_HATCHING) ?: return listOf(background)
        val confused = CinematicSprites(parser).render(
            CinematicSprites.Sequence("Intro · Confused baby", 0x8BCC2B, 0x95E4C2, 0x8CE3E9, 0x8BCC3F, 7)
        )
        val samus = samusFrame(parser, 0x02)
        val eggPosition = nativeObjectPosition(IntroImageSource.ROM_EGG_HATCHING, scrollX, scrollY)
        return egg.frames.mapIndexed { index, frame ->
            copyViewport(background).also { image ->
                samus?.let { drawAtOrigin(image, it, 0x78, 0x93, originY = 56) }
                drawAtSourceOrigin(image, frame, eggPosition.first, eggPosition.second)
                // The confused baby becomes active when the egg list reaches
                // $8B:CB79 (frame 26), at its real $8B:BA4B screen anchor.
                if (index >= 25) {
                    confused?.frames?.getOrNull(index.mod(confused.frames.size))?.let {
                        drawAtSourceOrigin(image, it, 0x70, 0x9B)
                    }
                }
            }
        }
    }

    private fun vignetteFrames(
        parser: RomParser,
        source: IntroImageSource,
        backgroundIndex: Int,
        scrollX: Int,
        scrollY: Int,
        samusX: Int? = null,
        samusY: Int? = null,
    ): List<BufferedImage> {
        val background = vignetteBackground(parser, backgroundIndex, scrollX, scrollY) ?: return emptyList()
        val animation = cinematicAnimation(parser, source)
        val objectFrames: List<SpriteAnimationFrame?> = animation?.frames.orEmpty().ifEmpty { listOf(null) }
        val samus = if (samusX != null) samusFrame(parser, 0x02) else null
        val objectPosition = nativeObjectPosition(source, scrollX, scrollY)
        return objectFrames.map { frame ->
            copyViewport(background).also { image ->
                if (samus != null && samusX != null && samusY != null) drawAtOrigin(image, samus, samusX, samusY, originY = 56)
                if (frame != null) drawAtSourceOrigin(image, frame, objectPosition.first, objectPosition.second)
            }
        }
    }

    /**
     * Bank $8B stores cinematic OBJ positions separately from BG1 scroll. Most
     * objects stay at a fixed screen coordinate. The two scientist scenes are
     * the exceptions: their pre-instructions counter-move the baby while BG1
     * pans, so reproduce those deltas from the native starting scroll.
     */
    internal fun nativeObjectPosition(source: IntroImageSource, scrollX: Int, scrollY: Int): Pair<Int, Int> = when (source) {
        IntroImageSource.ROM_MOTHER_BRAIN -> 0x38 to 0x6F
        IntroImageSource.ROM_METROID_EGG, IntroImageSource.ROM_EGG_HATCHING -> 0x70 to 0x9B
        IntroImageSource.ROM_BABY_DELIVERED -> 0x54 + (0x20 - scrollX) to 0x8B
        IntroImageSource.ROM_BABY_EXAMINED -> 0x70 to 0x6F - (scrollY + 0x18)
        else -> 0 to 0
    }

    private fun vignetteBackground(parser: RomParser, index: Int, scrollX: Int, scrollY: Int): BufferedImage? {
        val atlasAsset = RomVisualAssets.ASSETS.single { it.name == "Intro · Mother Brain's room" }
        val atlas = RomVisualAssets.render(parser, atlasAsset)?.toImage() ?: return null
        val screenX = index % 2 * 256
        val screenY = index / 2 * 256
        val image = BufferedImage(IntroSceneCodec.WIDTH, IntroSceneCodec.HEIGHT, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val sx = screenX + (x + scrollX).mod(256)
            val sy = screenY + (y + scrollY).mod(256)
            image.setRGB(x, y, atlas.getRGB(sx, sy))
        }
        return image
    }

    private fun samusFrame(parser: RomParser, animationId: Int): SpriteAnimationFrame? {
        val decoder = SamusSpriteDecoder(parser)
        val pose = decoder.getPose(animationId, 0) ?: return null
        return SpriteAnimationFrame(
            decoder.renderPose(pose, decoder.readPalette(SamusSpriteDecoder.SuitType.POWER), 96, 96),
            96,
            96,
        )
    }

    private fun cinematicAnimation(parser: RomParser, source: IntroImageSource): SpriteAnimation? {
        val sequenceName = when (source) {
            IntroImageSource.ROM_MOTHER_BRAIN -> "Intro · Mother Brain"
            IntroImageSource.ROM_METROID_EGG -> "Intro · Metroid egg"
            IntroImageSource.ROM_EGG_HATCHING -> "Intro · Egg hatching"
            IntroImageSource.ROM_BABY_DELIVERED -> "Intro · Baby Metroid delivered"
            IntroImageSource.ROM_BABY_EXAMINED -> "Intro · Baby Metroid examined"
            else -> return null
        }
        return CinematicSprites(parser).render(CinematicSprites.SEQUENCES.single { it.name == sequenceName })
    }

    private fun SpriteAnimationFrame.toImage(): BufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
        it.setRGB(0, 0, width, height, pixels, 0, width)
    }

    private fun copyViewport(source: BufferedImage): BufferedImage =
        BufferedImage(IntroSceneCodec.WIDTH, IntroSceneCodec.HEIGHT, BufferedImage.TYPE_INT_ARGB).also { target ->
            val graphics = target.createGraphics()
            try { graphics.drawImage(source, 0, 0, null) } finally { graphics.dispose() }
        }

    private fun drawAtSourceOrigin(target: BufferedImage, frame: SpriteAnimationFrame, x: Int, y: Int) =
        drawAtOrigin(target, frame, x, y, frame.originX, frame.originY)

    private fun drawAtOrigin(
        target: BufferedImage,
        frame: SpriteAnimationFrame,
        x: Int,
        y: Int,
        originX: Int = frame.width / 2,
        originY: Int = frame.height / 2,
    ) {
        for (sy in 0 until frame.height) for (sx in 0 until frame.width) {
            val pixel = frame.pixels[sy * frame.width + sx]
            val dx = x - originX + sx
            val dy = y - originY + sy
            if (pixel ushr 24 != 0 && dx in 0 until target.width && dy in 0 until target.height) target.setRGB(dx, dy, pixel)
        }
    }
}

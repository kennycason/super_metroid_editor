package com.supermetroid.editor.ui

import com.supermetroid.editor.data.IntroAdvanceMode
import com.supermetroid.editor.data.IntroImageSource
import com.supermetroid.editor.data.IntroPortraitAnimation
import com.supermetroid.editor.data.IntroScene
import com.supermetroid.editor.data.IntroSceneLayout
import com.supermetroid.editor.data.IntroTextReveal
import com.supermetroid.editor.data.IntroTransition
import com.supermetroid.editor.data.IntroVignetteAnimation
import com.supermetroid.editor.data.IntroVignetteTiming
import com.supermetroid.editor.data.effectiveImageSource
import com.supermetroid.editor.data.effectiveVignetteSource
import com.supermetroid.editor.rom.IntroRomImageResolver
import com.supermetroid.editor.rom.IntroSceneCodec
import com.supermetroid.editor.rom.RomAllocation
import com.supermetroid.editor.rom.RomFreeSpaceAllocator
import com.supermetroid.editor.rom.RomSegmentKind
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlan
import com.supermetroid.editor.rom.RomWritePlanException
import com.supermetroid.editor.rom.RomParser

/** Installs the self-contained Mode-1 still-scene player used by the managed intro patch. */
internal object IntroSequenceRomPatch {
    private const val HOOK_SNES = 0x8BA66F
    private val HOOK_EXPECTED = listOf(0xC2, 0x30, 0x20, 0x6A, 0xA8) // REP #$30 / JSR $A86A
    private val EXPANDED_BANKS = (0xE0..0xFF).toList()
    private const val OWNER = "patch:config_intro_sequence"

    data class Result(
        val sceneCount: Int,
        val tileCount: Int,
        val payloadBytes: Int,
        val runtimeAddress: Int,
    )

    private data class Stage(
        val name: String,
        val frames: List<IntroSceneCodec.CompiledScene>,
        val advanceMode: IntroAdvanceMode,
        val durationFrames: Int,
        val transition: IntroTransition,
        val fadeInFrames: Int,
        val fadeOutFrames: Int,
        val frameDurations: List<Int>,
        val loopCount: Int,
        val loopStart: Int = 0,
        val revealEntries: List<RevealEntry> = emptyList(),
        val revealDelay: Int = 1,
        val postRevealInputDelay: Int = 0,
    )

    private data class RevealEntry(val tilemapPosition: Int, val blankWord: Int, val finalWord: Int)

    private data class FrameAsset(
        val stage: Stage,
        val compiled: IntroSceneCodec.CompiledScene,
        val allocation: RomAllocation,
        val duration: Int,
    )

    private data class AllocatedStage(val stage: Stage, val firstFrame: Int, val frames: List<FrameAsset>)

    fun install(
        scenes: List<IntroScene>,
        writePlan: RomWritePlan,
        freeSpaceAllocator: RomFreeSpaceAllocator,
        snesToPc: (Int) -> Int,
        sourceParser: RomParser? = null,
    ): Result {
        if (scenes.size !in 1..6) throw RomWritePlanException("Intro Sequence Manager requires 1–6 story frames.")
        val allocator = freeSpaceAllocator.scoped(
            owner = OWNER,
            kind = RomSegmentKind.GENERATED_PATCH,
            idPrefix = OWNER,
            constraint = "Managed intro payloads and runtime must remain within one expanded LoROM bank",
        )
        val stages = scenes.flatMapIndexed { index, scene -> compileStages(index, scene, sourceParser) }
        require(stages.size in 1..12) { "The intro runtime supports 1–12 narration/image phases." }
        val allFrames = mutableListOf<FrameAsset>()
        val reusedAllocations = java.util.IdentityHashMap<IntroSceneCodec.CompiledScene, RomAllocation>()
        val allocatedStages = stages.mapIndexed { stageIndex, stage ->
            val firstFrame = allFrames.size
            val frames = stage.frames.mapIndexed { frameIndex, compiled ->
                require(compiled.tileCount <= 1024) { "${stage.name} frame ${frameIndex + 1} exceeds the 1024-tile limit." }
                val allocation = reusedAllocations[compiled] ?: allocator.reserve(
                        size = compiled.payload.size,
                        banks = EXPANDED_BANKS,
                        label = "Intro phase ${stageIndex + 1} frame ${frameIndex + 1}: ${stage.name}",
                        alignment = 2,
                    )?.also { reusedAllocations[compiled] = it }
                    ?: throw RomWritePlanException("No expanded LoROM bank can hold ${stage.name} frame ${frameIndex + 1} (${compiled.payload.size} bytes).")
                require((allocation.snesAddress and 0xFFFF) + compiled.payload.size <= 0x10000) {
                    "${stage.name} frame ${frameIndex + 1} crossed a LoROM bank boundary."
                }
                FrameAsset(stage, compiled, allocation, stage.frameDurations[frameIndex].coerceIn(1, 3600)).also(allFrames::add)
            }
            AllocatedStage(stage, firstFrame, frames)
        }

        val provisional = RuntimeBuilder(allocatedStages, allFrames, 0xE08000).build()
        val runtimeAllocation = allocator.reserve(
            size = provisional.size,
            banks = EXPANDED_BANKS,
            label = "Managed intro runtime",
            alignment = 2,
        ) ?: throw RomWritePlanException("No expanded LoROM bank has ${provisional.size} bytes for the intro runtime.")
        val runtime = RuntimeBuilder(allocatedStages, allFrames, runtimeAllocation.snesAddress).build()
        check(runtime.size == runtimeAllocation.size)

        val allocatedPayloads = allFrames.distinctBy { it.allocation.pcOffset }
        allocatedPayloads.forEach { asset ->
            writePlan.addAllocation(asset.allocation, asset.compiled.payload)
        }
        writePlan.addAllocation(runtimeAllocation, runtime)

        val hookPc = snesToPc(HOOK_SNES) - writePlan.headerSize
        val address = runtimeAllocation.snesAddress
        writePlan.add(
            owner = OWNER,
            label = "Replace native intro story dispatcher",
            offset = hookPc,
            bytes = listOf(0x22, address and 255, address ushr 8 and 255, address ushr 16 and 255, 0x60),
            kind = RomWriteKind.HOOK,
            expectedBefore = HOOK_EXPECTED,
        )
        return Result(
            sceneCount = allocatedStages.size,
            tileCount = allocatedPayloads.sumOf { it.compiled.tileCount },
            payloadBytes = allocatedPayloads.sumOf { it.compiled.payload.size } + runtime.size,
            runtimeAddress = runtimeAllocation.snesAddress,
        )
    }

    private fun compileStages(index: Int, scene: IntroScene, parser: RomParser?): List<Stage> {
        fun compileCard(card: IntroScene, images: List<java.awt.image.BufferedImage?>, label: String): List<IntroSceneCodec.CompiledScene> =
            images.ifEmpty { listOf(null) }.mapIndexed { frame, image ->
                runCatching { IntroSceneCodec.compile(card, image) }.getOrElse {
                    throw RomWritePlanException("$label frame ${frame + 1} could not be compiled: ${it.message}")
                }
            }

        val narrationImages: List<java.awt.image.BufferedImage?> = when {
            !IntroRomImageResolver.isRomSource(scene.effectiveImageSource) -> listOf(null)
            parser == null -> throw RomWritePlanException("${scene.name} requires loaded ROM intro graphics.")
            scene.effectiveImageSource == IntroImageSource.ROM_SAMUS_PORTRAIT -> when (scene.portraitAnimation) {
                IntroPortraitAnimation.NATIVE_BLINK -> (0..3).map { IntroRomImageResolver.renderPortrait(parser, scene.portraitAnimation, it) }
                else -> listOf(IntroRomImageResolver.renderPortrait(parser, scene.portraitAnimation, scene.imageFrame))
            }
            else -> listOf(IntroRomImageResolver.render(parser, scene.effectiveImageSource, scene.imageFrame))
        }
        val compiledNarration = compileCard(scene, narrationImages, "Story beat ${index + 1} narration")
        val narrationFrames = if (scene.effectiveImageSource == IntroImageSource.ROM_SAMUS_PORTRAIT &&
            scene.portraitAnimation == IntroPortraitAnimation.NATIVE_BLINK && compiledNarration.size == 4
        ) {
            listOf(compiledNarration[0], compiledNarration[1], compiledNarration[2], compiledNarration[1],
                compiledNarration[0], compiledNarration[1], compiledNarration[2], compiledNarration[1])
        } else compiledNarration
        val narrationDurations = if (narrationFrames.size == 8) listOf(128, 10, 10, 10, 80, 8, 8, 8)
            else List(narrationFrames.size) { 255 }
        val narration = Stage(
            name = "${scene.name} narration",
            frames = narrationFrames,
            advanceMode = scene.advanceMode,
            durationFrames = scene.durationFrames,
            transition = scene.transition,
            fadeInFrames = scene.fadeInFrames,
            fadeOutFrames = scene.fadeOutFrames,
            frameDurations = narrationDurations,
            loopCount = 255,
            revealEntries = if (scene.textReveal == IntroTextReveal.NATIVE_TYPEWRITER && scene.text.isNotBlank()) {
                revealEntries(scene, narrationImages.firstOrNull(), narrationFrames.first())
            } else emptyList(),
            revealDelay = scene.typewriterFramesPerCharacter,
            postRevealInputDelay = if (scene.advanceMode == IntroAdvanceMode.TIMED) {
                0
            } else {
                scene.postRevealInputDelayFrames
            },
        )
        val vignetteSource = scene.effectiveVignetteSource
        if (vignetteSource == IntroImageSource.NONE) return listOf(narration)
        val vignetteCard = scene.copy(
            text = "",
            imagePngBase64 = scene.vignetteImagePngBase64,
            imageFileName = scene.vignetteImageFileName,
            imageSha256 = scene.vignetteImageSha256,
            imageSource = vignetteSource,
            imageFrame = scene.vignetteFrame,
            imageFit = scene.vignetteImageFit,
            layout = IntroSceneLayout.IMAGE_ONLY,
        )
        val animatedRomVignette = IntroRomImageResolver.isRomSource(vignetteSource) &&
            parser != null && scene.vignetteAnimation == IntroVignetteAnimation.NATIVE
        val vignetteFrameCount = if (animatedRomVignette) {
            IntroRomImageResolver.frameCount(requireNotNull(parser), vignetteSource).coerceAtLeast(1)
        } else 1
        val baseVignetteDurations = if (animatedRomVignette && scene.vignetteTiming == IntroVignetteTiming.NATIVE) {
            IntroRomImageResolver.frameDurations(requireNotNull(parser), vignetteSource)
                .takeIf { it.size == vignetteFrameCount }
        } else null
        val effectiveVignetteDurations = baseVignetteDurations
            ?: List(vignetteFrameCount) { scene.vignetteFrameDelay }
        val vignetteFrameStarts = effectiveVignetteDurations.runningFold(0) { total, duration -> total + duration }
            .dropLast(1)
        val initialVignetteImages: List<java.awt.image.BufferedImage?> = when {
            !IntroRomImageResolver.isRomSource(vignetteSource) -> listOf(null)
            parser == null -> throw RomWritePlanException("${scene.name} requires loaded ROM image graphics.")
            scene.vignetteAnimation == IntroVignetteAnimation.STATIC -> listOf(
                IntroRomImageResolver.render(parser, vignetteSource, scene.vignetteFrame, scene.vignettePanStartX, scene.vignettePanStartY)
            )
            else -> (0 until vignetteFrameCount).map { frame ->
                    val progress = if (vignetteFrameCount == 1) 1.0 else {
                        vignetteFrameStarts[frame].toDouble() / vignetteFrameStarts.last().coerceAtLeast(1)
                    }
                    val x = (scene.vignettePanStartX + (scene.vignettePanEndX - scene.vignettePanStartX) * progress).toInt()
                    val y = (scene.vignettePanStartY + (scene.vignettePanEndY - scene.vignettePanStartY) * progress).toInt()
                    IntroRomImageResolver.render(parser, vignetteSource, frame, x, y)
                }
        }
        val hasOneTimePan = scene.vignetteAnimation == IntroVignetteAnimation.NATIVE && scene.vignetteLoopCount > 1 &&
            (scene.vignettePanStartX != scene.vignettePanEndX || scene.vignettePanStartY != scene.vignettePanEndY)
        val vignetteImages = if (hasOneTimePan && parser != null && IntroRomImageResolver.isRomSource(vignetteSource)) {
            initialVignetteImages + (initialVignetteImages.indices).map { frame ->
                IntroRomImageResolver.render(parser, vignetteSource, frame, scene.vignettePanEndX, scene.vignettePanEndY)
            }
        } else initialVignetteImages
        val vignetteDurations = if (hasOneTimePan) {
            effectiveVignetteDurations + effectiveVignetteDurations
        } else effectiveVignetteDurations
        val vignette = Stage(
            name = "${scene.name} image",
            frames = compileCard(vignetteCard, vignetteImages, "Story frame ${index + 1} image"),
            advanceMode = scene.vignetteAdvanceMode,
            durationFrames = scene.vignetteDurationFrames,
            transition = scene.vignetteTransition,
            fadeInFrames = scene.vignetteFadeInFrames,
            fadeOutFrames = scene.vignetteFadeOutFrames,
            frameDurations = vignetteDurations,
            // The flattened first traversal contains both the one-time pan cycle
            // and the first endpoint cycle. Count those toward the requested total.
            loopCount = if (hasOneTimePan) scene.vignetteLoopCount - 1 else scene.vignetteLoopCount,
            loopStart = if (hasOneTimePan) initialVignetteImages.size else 0,
        )
        return listOf(narration, vignette)
    }

    /**
     * The player keeps the final tile payload resident and initially replaces only
     * tiles touched by glyph pixels with the legal blank tile for that palette band.
     * Revealing one tile at a time avoids duplicating a full 256×224 frame per letter.
     */
    private fun revealEntries(
        scene: IntroScene,
        image: java.awt.image.BufferedImage?,
        compiled: IntroSceneCodec.CompiledScene,
    ): List<RevealEntry> {
        val finalImage = IntroSceneCodec.render(scene, image)
        val blankImage = IntroSceneCodec.render(scene.copy(text = ""), image)
        return buildList {
            for (tileY in 0 until IntroSceneCodec.HEIGHT / 8) for (tileX in 0 until IntroSceneCodec.WIDTH / 8) {
                val differs = (0..7).any { y -> (0..7).any { x ->
                    finalImage.getRGB(tileX * 8 + x, tileY * 8 + y) != blankImage.getRGB(tileX * 8 + x, tileY * 8 + y)
                } }
                if (!differs) continue
                val position = tileY * 32 + tileX
                val mapOffset = position * 2
                val finalWord = (compiled.tilemap[mapOffset].toInt() and 0xFF) or
                    ((compiled.tilemap[mapOffset + 1].toInt() and 0xFF) shl 8)
                add(RevealEntry(position, (tileY / 4) shl 10, finalWord))
            }
        }
    }

    /** Tiny 65816 emitter. Data bank is zero so WRAM/PPU absolute addresses remain safe. */
    private class RuntimeBuilder(
        private val stages: List<AllocatedStage>,
        private val assets: List<FrameAsset>,
        private val baseAddress: Int,
    ) {
        private val bytes = mutableListOf<Int>()
        private val labels = mutableMapOf<String, Int>()
        private data class RelativeFixup(val operand: Int, val label: String)
        private data class AbsoluteFixup(val operand: Int, val label: String, val long: Boolean)
        private val relativeFixups = mutableListOf<RelativeFixup>()
        private val absoluteFixups = mutableListOf<AbsoluteFixup>()

        fun build(): ByteArray {
            emitCode()
            resolve()
            return ByteArray(bytes.size) { bytes[it].toByte() }
        }

        private fun emitCode() {
            // JSL entry. Preserve caller flags/data bank and use bank $00 for hardware/WRAM mirrors.
            b(0x08, 0x8B); sep(0x20); imm8(0xA9, 0); b(0x48, 0xAB); rep(0x30)
            ldaAbs(STATE); branch(0xD0, "dispatch")
            jsr("initialize"); branch(0x80, "return")
            label("dispatch")
            cmp16(STATE_FADE_IN); branch(0xD0, "dispatch_not_fade_in"); jmp("fade_in")
            label("dispatch_not_fade_in")
            cmp16(STATE_DISPLAY); branch(0xD0, "dispatch_fade_out"); jmp("display")
            label("dispatch_fade_out"); jmp("fade_out")
            label("return"); b(0xAB, 0x28, 0x6B) // PLB / PLP / RTL

            label("initialize")
            stzAbs(CINEMATIC_BG_ENABLE); stzAbs(CINEMATIC_BG_UPDATE)
            stzAbs(INTRO_SAMUS_DISPLAY); stzAbs(MODE7_OBJECTS); stzAbs(MODE7_OBJECTS + 2)
            for (offset in 0 until 16 step 2) stzAbs(TEXT_GLOW_OBJECTS + offset)
            // Native object handlers still run after our dispatcher; leave them no stale opening-card objects.
            b(0xA2, 0x1E, 0x00)
            label("clear_cinematic_objects")
            b(0x9E, CINEMATIC_SPRITEMAPS and 255, CINEMATIC_SPRITEMAPS ushr 8)
            b(0x9E, CINEMATIC_INSTRUCTIONS and 255, CINEMATIC_INSTRUCTIONS ushr 8)
            b(0xCA, 0xCA); branch(0x10, "clear_cinematic_objects")
            ldaAbs(PALETTE_FX_ENABLE); b(0x29, 0xFF, 0x7F); staAbs(PALETTE_FX_ENABLE)
            ldaAbs(HDMA_ENABLE); b(0x29, 0xFF, 0x7F); staAbs(HDMA_ENABLE)
            sep(0x20); b(0x9C, 0x0C, 0x42); rep(0x30)
            stzAbs(SCENE_INDEX); stzAbs(TIMER); stzAbs(FRAME_INDEX); stzAbs(FRAME_TIMER); stzAbs(LOOP_INDEX)
            stzAbs(REVEAL_INDEX); stzAbs(REVEAL_OFFSET); stzAbs(REVEAL_TIMER)
            stzAbs(BG1_X); stzAbs(BG1_Y)
            lda16(STATE_FADE_IN); staAbs(STATE)
            jsr("load_scene")
            sep(0x20); imm8(0xA9, 0); staDp(BRIGHTNESS); rep(0x30)
            b(0x60)

            label("fade_in")
            rep(0x30); jsr("scene_x"); ldaStage16(12); branch(0xF0, "fade_in_force_done")
            incAbs(FADE_TIMER); ldaAbs(FADE_TIMER); cmpStage16(12); branch(0xB0, "fade_in_step"); jmp("return")
            label("fade_in_step")
            stzAbs(FADE_TIMER)
            sep(0x20); ldaDp(BRIGHTNESS); b(0x29, 0x0F); b(0xC9, 0x0F); branch(0xF0, "fade_in_done")
            b(0x1A); staDp(BRIGHTNESS); rep(0x30); jmp("return")
            label("fade_in_force_done"); sep(0x20); imm8(0xA9, 0x0F); staDp(BRIGHTNESS)
            label("fade_in_done")
            rep(0x30); lda16(STATE_DISPLAY); staAbs(STATE); stzAbs(TIMER); stzAbs(FADE_TIMER); jmp("return")

            label("display")
            incAbs(TIMER); jsr("reveal_text"); jsr("animate"); jsr("scene_x")
            ldaStage16(18); branch(0xF0, "display_wait_input_delay")
            ldaAbs(REVEAL_INDEX); cmpStage16(18); branch(0xB0, "display_wait_input_delay"); jmp("return")
            label("display_wait_input_delay")
            ldaStage16(24); branch(0xF0, "display_check_advance")
            incAbs(REVEAL_TIMER); ldaAbs(REVEAL_TIMER); cmpStage16(24); branch(0xB0, "display_check_advance"); jmp("return")
            label("display_check_advance")
            sep(0x20); ldaStage(8); b(0xC9, ADVANCE_BUTTON); branch(0xF0, "check_button")
            b(0xC9, ADVANCE_TIMED); branch(0xF0, "check_timer")
            rep(0x20); ldaAbs(CONTROLLER_NEW); branch(0xD0, "advance")
            branch(0x80, "check_timer_16")
            label("check_button")
            rep(0x20); ldaAbs(CONTROLLER_NEW); branch(0xD0, "advance"); jmp("return")
            label("check_timer")
            rep(0x20)
            label("check_timer_16")
            ldaAbs(TIMER); cmpStage16(10); branch(0xB0, "advance"); jmp("return")

            label("advance")
            jsr("scene_x"); sep(0x20); ldaStage(9); b(0xC9, TRANSITION_CUT); branch(0xF0, "cut")
            rep(0x20); lda16(STATE_FADE_OUT); staAbs(STATE); stzAbs(FADE_TIMER); jmp("return")
            label("cut")
            rep(0x20); ldaAbs(SCENE_INDEX); b(0x1A); cmp16(stages.size); branch(0xB0, "finish")
            staAbs(SCENE_INDEX); jsr("load_scene")
            sep(0x20); imm8(0xA9, 0x0F); staDp(BRIGHTNESS); rep(0x20)
            lda16(STATE_DISPLAY); staAbs(STATE); stzAbs(TIMER); jmp("return")

            label("fade_out")
            rep(0x30); jsr("scene_x"); ldaStage16(14); branch(0xF0, "fade_out_force_done")
            incAbs(FADE_TIMER); ldaAbs(FADE_TIMER); cmpStage16(14); branch(0xB0, "fade_out_step"); jmp("return")
            label("fade_out_step")
            stzAbs(FADE_TIMER)
            sep(0x20); ldaDp(BRIGHTNESS); b(0x29, 0x0F); branch(0xF0, "fade_out_done")
            b(0x3A); staDp(BRIGHTNESS); rep(0x30); jmp("return")
            label("fade_out_force_done"); sep(0x20); imm8(0xA9, 0); staDp(BRIGHTNESS)
            label("fade_out_done")
            rep(0x30); ldaAbs(SCENE_INDEX); b(0x1A); cmp16(stages.size); branch(0xB0, "finish")
            staAbs(SCENE_INDEX); jsr("load_scene"); lda16(STATE_FADE_IN); staAbs(STATE); jmp("return")

            label("finish")
            rep(0x30); lda16(VANILLA_FINISH); staAbs(CINEMATIC_FUNCTION)
            lda16(1); staAbs(FADE_DELAY); staAbs(FADE_COUNTER)
            sep(0x20); imm8(0xA9, 1); staDp(BRIGHTNESS); rep(0x30); jmp("return")

            label("scene_x")
            ldaAbs(SCENE_INDEX); b(0x0A, 0x0A, 0x0A, 0x0A, 0x0A, 0xAA, 0x60) // ASL x5 / TAX / RTS

            label("asset_x")
            jsr("scene_x"); ldaStage16(0); b(0x18); b(0x6D, FRAME_INDEX and 255, FRAME_INDEX ushr 8)
            b(0x0A, 0x0A, 0x0A, 0x0A, 0xAA, 0x60)

            label("reveal_x")
            jsr("scene_x"); ldaStage16(16); b(0x18); b(0x6D, REVEAL_OFFSET and 255, REVEAL_OFFSET ushr 8); b(0xAA, 0x60)

            label("reveal_text")
            jsr("scene_x"); ldaStage16(18); branch(0xF0, "reveal_done")
            ldaAbs(REVEAL_INDEX); cmpStage16(18); branch(0xB0, "reveal_done")
            incAbs(REVEAL_TIMER); ldaAbs(REVEAL_TIMER); cmpStage16(20); branch(0x90, "reveal_done")
            stzAbs(REVEAL_TIMER); jsr("reveal_x")
            sep(0x20); imm8(0xA9, 0x80); b(0x8D, 0x00, 0x21); rep(0x20)
            ldaReveal16(0); b(0x18); b(0x69, 0x00, 0x38); b(0x8D, 0x16, 0x21)
            ldaReveal16(4); b(0x8D, 0x18, 0x21)
            incAbs(REVEAL_INDEX); ldaAbs(REVEAL_OFFSET); b(0x18); b(0x69, 0x06, 0x00); staAbs(REVEAL_OFFSET)
            sep(0x20); ldaDp(BRIGHTNESS); b(0x8D, 0x00, 0x21); rep(0x30)
            label("reveal_done"); b(0x60)

            label("animate")
            jsr("scene_x")
            ldaStage16(2); cmp16(1); branch(0xF0, "animate_done")
            incAbs(FRAME_TIMER); jsr("asset_x"); ldaAbs(FRAME_TIMER); cmpAsset16(11); branch(0x90, "animate_done")
            stzAbs(FRAME_TIMER); incAbs(FRAME_INDEX); ldaAbs(FRAME_INDEX); cmpStage16(2); branch(0x90, "animate_upload")
            ldaStage16(22); staAbs(FRAME_INDEX); incAbs(LOOP_INDEX); ldaAbs(LOOP_INDEX); cmpStage16(6); branch(0x90, "animate_upload")
            // A completed finite loop stays on its final frame until the phase advances.
            ldaStage16(2); b(0x3A); staAbs(FRAME_INDEX)
            label("animate_done"); b(0x60)
            label("animate_upload"); jsr("upload_frame"); jsr("reblank_pending_text"); b(0x60)

            // Animation payloads contain the final text tilemap. Keep already-revealed
            // characters while blanking only the pending entries after each frame DMA.
            label("reblank_pending_text")
            jsr("scene_x"); ldaStage16(18); branch(0xF0, "reblank_done")
            ldaAbs(REVEAL_INDEX); cmpStage16(18); branch(0xB0, "reblank_done")
            ldaAbs(REVEAL_INDEX); b(0x48); ldaAbs(REVEAL_OFFSET); b(0x48)
            sep(0x20); imm8(0xA9, 0x80); b(0x8D, 0x00, 0x21); rep(0x20)
            label("reblank_loop")
            jsr("reveal_x"); ldaReveal16(0); b(0x18); b(0x69, 0x00, 0x38); b(0x8D, 0x16, 0x21)
            ldaReveal16(2); b(0x8D, 0x18, 0x21)
            incAbs(REVEAL_INDEX); ldaAbs(REVEAL_OFFSET); b(0x18); b(0x69, 0x06, 0x00); staAbs(REVEAL_OFFSET)
            jsr("scene_x"); ldaAbs(REVEAL_INDEX); cmpStage16(18); branch(0x90, "reblank_loop")
            b(0x68); staAbs(REVEAL_OFFSET); b(0x68); staAbs(REVEAL_INDEX)
            sep(0x20); ldaDp(BRIGHTNESS); b(0x8D, 0x00, 0x21); rep(0x30)
            label("reblank_done"); b(0x60)

            label("load_scene")
            // Forced blank while changing VRAM/CGRAM.
            sep(0x20); imm8(0xA9, 0x80); b(0x8D, 0x00, 0x21); staDp(BRIGHTNESS)
            rep(0x30); stzAbs(TIMER); stzAbs(FRAME_INDEX); stzAbs(FRAME_TIMER); stzAbs(LOOP_INDEX)
            stzAbs(REVEAL_INDEX); stzAbs(REVEAL_OFFSET); stzAbs(REVEAL_TIMER)
            jsr("upload_frame"); jsr("blank_text"); b(0x60)

            label("blank_text")
            jsr("scene_x"); ldaStage16(18); branch(0xF0, "blank_done")
            label("blank_loop")
            jsr("reveal_x"); ldaReveal16(0); b(0x18); b(0x69, 0x00, 0x38); b(0x8D, 0x16, 0x21)
            ldaReveal16(2); b(0x8D, 0x18, 0x21)
            incAbs(REVEAL_INDEX); ldaAbs(REVEAL_OFFSET); b(0x18); b(0x69, 0x06, 0x00); staAbs(REVEAL_OFFSET)
            jsr("scene_x"); ldaAbs(REVEAL_INDEX); cmpStage16(18); branch(0x90, "blank_loop")
            stzAbs(REVEAL_INDEX); stzAbs(REVEAL_OFFSET)
            label("blank_done"); b(0x60)

            label("upload_frame")
            // Resolve stage.firstFrame + frame index, then multiply by the 16-byte asset row.
            jsr("asset_x")
            sep(0x20); imm8(0xA9, 0x80); b(0x8D, 0x00, 0x21)
            // Mode 1, BG1 tilemap $3800, BG1 tiles $0000, BG1 main screen only.
            imm8(0xA9, 0x01); staDp(BG_MODE); b(0x8D, 0x05, 0x21)
            imm8(0xA9, 0x38); staDp(BG1_SC); b(0x8D, 0x07, 0x21)
            imm8(0xA9, 0x00); staDp(BG_TILE_BASE); b(0x8D, 0x0B, 0x21)
            imm8(0xA9, 0x01); staDp(MAIN_SCREEN); b(0x8D, 0x2C, 0x21)
            imm8(0xA9, 0x00); staDp(SUB_SCREEN); b(0x8D, 0x2D, 0x21)
            staDp(COLOR_MATH_A); staDp(COLOR_MATH_B); b(0x8D, 0x30, 0x21, 0x8D, 0x31, 0x21)
            // VRAM increment after high byte.
            imm8(0xA9, 0x80); b(0x8D, 0x15, 0x21)
            // Tiles -> VRAM $0000, DMA channel 0 mode 1 to $2118/$2119.
            imm8(0xA9, 0x00); b(0x8D, 0x16, 0x21, 0x8D, 0x17, 0x21)
            imm8(0xA9, 0x01); b(0x8D, 0x00, 0x43)
            imm8(0xA9, 0x18); b(0x8D, 0x01, 0x43)
            ldaAsset(0); b(0x8D, 0x02, 0x43); ldaAsset(1); b(0x8D, 0x03, 0x43); ldaAsset(2); b(0x8D, 0x04, 0x43)
            ldaAsset(3); b(0x8D, 0x05, 0x43); ldaAsset(4); b(0x8D, 0x06, 0x43)
            imm8(0xA9, 0x01); b(0x8D, 0x0B, 0x42)
            // 32x32 map -> VRAM $3800.
            imm8(0xA9, 0x00); b(0x8D, 0x16, 0x21); imm8(0xA9, 0x38); b(0x8D, 0x17, 0x21)
            ldaAsset(5); b(0x8D, 0x02, 0x43); ldaAsset(6); b(0x8D, 0x03, 0x43); ldaAsset(7); b(0x8D, 0x04, 0x43)
            imm8(0xA9, 0x00); b(0x8D, 0x05, 0x43); imm8(0xA9, 0x08); b(0x8D, 0x06, 0x43)
            imm8(0xA9, 0x01); b(0x8D, 0x0B, 0x42)
            // Palette -> CGRAM color 0, mode 0 to $2122.
            imm8(0xA9, 0x00); b(0x8D, 0x21, 0x21, 0x8D, 0x00, 0x43)
            imm8(0xA9, 0x22); b(0x8D, 0x01, 0x43)
            ldaAsset(8); b(0x8D, 0x02, 0x43); ldaAsset(9); b(0x8D, 0x03, 0x43); ldaAsset(10); b(0x8D, 0x04, 0x43)
            imm8(0xA9, 0x00); b(0x8D, 0x05, 0x43); imm8(0xA9, 0x01); b(0x8D, 0x06, 0x43, 0x8D, 0x0B, 0x42)
            // Mirror the same palette into $7E:C000 so the regular NMI palette upload preserves it.
            imm8(0xA9, 0x00); b(0x8D, 0x81, 0x21); imm8(0xA9, 0xC0); b(0x8D, 0x82, 0x21)
            imm8(0xA9, 0x7E); b(0x8D, 0x83, 0x21)
            imm8(0xA9, 0x00); b(0x8D, 0x00, 0x43); imm8(0xA9, 0x80); b(0x8D, 0x01, 0x43)
            ldaAsset(8); b(0x8D, 0x02, 0x43); ldaAsset(9); b(0x8D, 0x03, 0x43); ldaAsset(10); b(0x8D, 0x04, 0x43)
            imm8(0xA9, 0x00); b(0x8D, 0x05, 0x43); imm8(0xA9, 0x01); b(0x8D, 0x06, 0x43, 0x8D, 0x0B, 0x42)
            ldaDp(BRIGHTNESS); b(0x8D, 0x00, 0x21)
            rep(0x30); stzAbs(BG1_X); stzAbs(BG1_Y); b(0x60)

            label("scene_table")
            var revealByteOffset = 0
            stages.forEach { allocated ->
                val stage = allocated.stage
                word(allocated.firstFrame); word(stage.frames.size); word(stage.frameDurations.first().coerceIn(1, 3600)); word(stage.loopCount.coerceIn(1, 255))
                b(when (stage.advanceMode) {
                    IntroAdvanceMode.BUTTON -> ADVANCE_BUTTON
                    IntroAdvanceMode.TIMED -> ADVANCE_TIMED
                    IntroAdvanceMode.EITHER -> ADVANCE_EITHER
                })
                b(if (stage.transition == IntroTransition.CUT) TRANSITION_CUT else TRANSITION_FADE)
                word(stage.durationFrames.coerceIn(30, 3600))
                word(fadeStep(stage.fadeInFrames)); word(fadeStep(stage.fadeOutFrames))
                word(revealByteOffset); word(stage.revealEntries.size); word(stage.revealDelay.coerceIn(1, 60))
                word(stage.loopStart.coerceIn(0, stage.frames.lastIndex.coerceAtLeast(0)))
                word(stage.postRevealInputDelay.coerceIn(0, 255)); b(0, 0, 0, 0, 0, 0)
                revealByteOffset += stage.revealEntries.size * 6
            }
            label("asset_table")
            assets.forEach { asset ->
                val tiles = asset.allocation.snesAddress
                val map = tiles + asset.compiled.tilemapOffset
                val palette = tiles + asset.compiled.paletteOffset
                long(tiles); word(asset.compiled.tiles.size); long(map); long(palette)
                word(asset.duration); b(0, 0, 0) // Pad to a power-of-two row for ASL x4 indexing.
            }
            label("reveal_table")
            stages.forEach { allocated ->
                allocated.stage.revealEntries.forEach { entry ->
                    word(entry.tilemapPosition); word(entry.blankWord); word(entry.finalWord)
                }
            }
        }

        private fun resolve() {
            relativeFixups.forEach { fixup ->
                val target = labels.getValue(fixup.label)
                val distance = target - (fixup.operand + 1)
                require(distance in -128..127) { "Intro runtime branch to ${fixup.label} is out of range ($distance)." }
                bytes[fixup.operand] = distance and 255
            }
            absoluteFixups.forEach { fixup ->
                val (label, addend) = fixup.label.substringBefore('+') to
                    fixup.label.substringAfter('+', "0").toInt()
                val address = baseAddress + labels.getValue(label) + addend
                bytes[fixup.operand] = address and 255
                bytes[fixup.operand + 1] = address ushr 8 and 255
                if (fixup.long) bytes[fixup.operand + 2] = address ushr 16 and 255
            }
        }

        private fun label(name: String) { check(labels.put(name, bytes.size) == null) }
        private fun b(vararg values: Int) { values.forEach { bytes += it and 255 } }
        private fun word(value: Int) = b(value, value ushr 8)
        private fun long(value: Int) = b(value, value ushr 8, value ushr 16)
        private fun rep(mask: Int) = b(0xC2, mask)
        private fun sep(mask: Int) = b(0xE2, mask)
        private fun lda16(value: Int) = b(0xA9, value, value ushr 8)
        private fun imm8(opcode: Int, value: Int) = b(opcode, value)
        private fun cmp16(value: Int) = b(0xC9, value, value ushr 8)
        private fun ldaAbs(address: Int) = b(0xAD, address, address ushr 8)
        private fun staAbs(address: Int) = b(0x8D, address, address ushr 8)
        private fun stzAbs(address: Int) = b(0x9C, address, address ushr 8)
        private fun incAbs(address: Int) = b(0xEE, address, address ushr 8)
        private fun ldaDp(address: Int) = b(0xA5, address)
        private fun staDp(address: Int) = b(0x85, address)
        private fun branch(opcode: Int, label: String) { b(opcode); relativeFixups += RelativeFixup(bytes.size, label); b(0) }
        private fun jsr(label: String) { b(0x20); absoluteFixups += AbsoluteFixup(bytes.size, label, false); b(0, 0) }
        private fun jmp(label: String) { b(0x4C); absoluteFixups += AbsoluteFixup(bytes.size, label, false); b(0, 0) }
        private fun ldaStage(offset: Int) = ldaIndexed("scene_table", offset)
        private fun ldaStage16(offset: Int) = ldaIndexed("scene_table", offset)
        private fun ldaAsset(offset: Int) = ldaIndexed("asset_table", offset)
        private fun ldaReveal16(offset: Int) = ldaIndexed("reveal_table", offset)
        private fun ldaIndexed(table: String, offset: Int) {
            b(0xBF); absoluteFixups += AbsoluteFixup(bytes.size, table, true); b(0, 0, 0)
            if (offset != 0) {
                // Fold the field offset into the long operand at resolution time via X pre-bias.
                val fixup = absoluteFixups.removeAt(absoluteFixups.lastIndex)
                absoluteFixups += fixup.copy(label = "$table+$offset")
            }
        }
        private fun cmpStage16(offset: Int) {
            b(0xDF); absoluteFixups += AbsoluteFixup(bytes.size, "scene_table+$offset", true); b(0, 0, 0)
        }
        private fun cmpAsset16(offset: Int) {
            b(0xDF); absoluteFixups += AbsoluteFixup(bytes.size, "asset_table+$offset", true); b(0, 0, 0)
        }

        /** SNES INIDISP exposes 15 brightness steps; spread them across the requested frame count. */
        private fun fadeStep(frames: Int): Int = if (frames <= 0) 0 else (frames.coerceAtMost(255) + 14) / 15

        private companion object {
            const val BRIGHTNESS = 0x51
            const val BG_MODE = 0x55
            const val BG1_SC = 0x58
            const val BG_TILE_BASE = 0x5D
            const val MAIN_SCREEN = 0x69
            const val SUB_SCREEN = 0x6B
            const val COLOR_MATH_A = 0x6F
            const val COLOR_MATH_B = 0x72
            const val CONTROLLER_NEW = 0x008F
            const val FADE_DELAY = 0x0723
            const val FADE_COUNTER = 0x0725
            const val CINEMATIC_BG_ENABLE = 0x19F1
            const val CINEMATIC_BG_UPDATE = 0x19F3
            const val PALETTE_FX_ENABLE = 0x1E79
            const val HDMA_ENABLE = 0x18B0
            const val STATE = 0x1A4D
            const val SCENE_INDEX = 0x1A4F
            const val TIMER = 0x1A51
            const val FRAME_INDEX = 0x1834
            const val FRAME_TIMER = 0x1836
            const val LOOP_INDEX = 0x1838
            const val FADE_TIMER = 0x183A
            const val REVEAL_INDEX = 0x184A
            const val REVEAL_OFFSET = 0x184C
            const val REVEAL_TIMER = 0x184E
            const val INTRO_SAMUS_DISPLAY = 0x1A57
            const val MODE7_OBJECTS = 0x19A1
            const val TEXT_GLOW_OBJECTS = 0x19F7
            const val CINEMATIC_SPRITEMAPS = 0x1A5D
            const val CINEMATIC_INSTRUCTIONS = 0x1B1D
            const val BG1_X = 0x1993
            const val BG1_Y = 0x1997
            const val CINEMATIC_FUNCTION = 0x1F51
            const val VANILLA_FINISH = 0xB72F
            const val STATE_FADE_IN = 1
            const val STATE_DISPLAY = 2
            const val STATE_FADE_OUT = 3
            const val ADVANCE_BUTTON = 0
            const val ADVANCE_TIMED = 1
            const val ADVANCE_EITHER = 2
            const val TRANSITION_CUT = 0
            const val TRANSITION_FADE = 1
        }
    }
}

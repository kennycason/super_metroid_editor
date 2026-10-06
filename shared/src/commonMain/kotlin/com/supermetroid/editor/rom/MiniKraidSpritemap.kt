package com.supermetroid.editor.rom

/** Exact source-backed rendering for enemy $E0FF (Mini Kraid / "fake Kraid"). */
class MiniKraidSpritemap(private val romParser: RomParser) {

    companion object {
        const val SPECIES_ID = 0xE0FF

        val SEQUENCES = listOf(
            SequenceDef("step-forward-left", "Step forward · facing left", 0xA699AE, 0xA699C4),
            SequenceDef("step-backward-left", "Step backward · facing left", 0xA699C6, 0xA699DC),
            SequenceDef("fire-left", "Fire spit · facing left", 0xA699DC, 0xA699F4),
            SequenceDef("step-forward-right", "Step forward · facing right", 0xA699FC, 0xA69A12),
            SequenceDef("step-backward-right", "Step backward · facing right", 0xA69A14, 0xA69A2A),
            SequenceDef("fire-right", "Fire spit · facing right", 0xA69A2A, 0xA69A42),
        )

        /** Fourteen unique active spritemaps; repeated frames remain in [SEQUENCES]. */
        val POSES = listOf(
            PoseDef("Step left 1", 0xA69C64),
            PoseDef("Step left 2", 0xA69CB6),
            PoseDef("Step left 3", 0xA69D08),
            PoseDef("Step left 4", 0xA69D5A),
            PoseDef("Spit left 1", 0xA69DAC),
            PoseDef("Spit left 2", 0xA69DFE),
            PoseDef("Spit left 3", 0xA69E50),
            PoseDef("Step right 1", 0xA69EA2),
            PoseDef("Step right 2", 0xA69EF4),
            PoseDef("Step right 3", 0xA69F46),
            PoseDef("Step right 4", 0xA69F98),
            PoseDef("Spit right 1", 0xA69FEA),
            PoseDef("Spit right 2", 0xA6A03C),
            PoseDef("Spit right 3", 0xA6A08E),
        )
    }

    data class SequenceDef(
        val key: String,
        val name: String,
        val snesAddr: Int,
        val endSnesAddrExclusive: Int,
    )

    data class PoseDef(val name: String, val snesAddr: Int)

    data class Frame(
        val duration: Int,
        val sourceSnes: Int,
        val spritemap: EnemySpritemap.Spritemap,
    )

    data class Animation(
        val definition: SequenceDef,
        val frames: List<Frame>,
        val handlerSnesAddresses: List<Int>,
    )

    fun loadAnimation(def: SequenceDef): Animation? {
        val rom = romParser.getRomData()
        val parser = EnemySpritemap(romParser)
        val frames = mutableListOf<Frame>()
        val handlers = mutableListOf<Int>()
        var snes = def.snesAddr
        while (snes < def.endSnesAddrExclusive) {
            val pc = romParser.snesToPc(snes)
            if (pc < 0 || pc + 2 > rom.size) return null
            val word = readWord(rom, pc)
            if (word < 0x8000) {
                if (pc + 4 > rom.size) return null
                val frameSnes = 0xA60000 or readWord(rom, pc + 2)
                val spritemap = parser.parseSpritemap(frameSnes) ?: return null
                frames += Frame(word, frameSnes, spritemap)
                snes += 4
            } else {
                handlers += 0xA60000 or word
                // Instruction_Common_GotoY has one target operand. Mini Kraid's move,
                // cry, spit, and choose-action handlers are all zero-operand RTL calls.
                snes += if (word == 0x80ED) 4 else 2
            }
        }
        if (snes != def.endSnesAddrExclusive || frames.isEmpty()) return null
        return Animation(def, frames, handlers)
    }

    fun loadAnimations(): List<Animation>? =
        SEQUENCES.map { loadAnimation(it) ?: return null }

    fun renderAnimation(
        def: SequenceDef,
        tileData: ByteArray,
        palette: IntArray,
    ): SpriteAnimation? {
        val animation = loadAnimation(def) ?: return null
        val renderer = EnemySpritemap(romParser)
        val rendered = animation.frames.mapNotNull { frame ->
            renderer.renderSpritemap(frame.spritemap, tileData, palette)?.let { frame to it }
        }
        if (rendered.isEmpty()) return null

        val minX = rendered.minOf { (_, sprite) -> -sprite.originX }
        val minY = rendered.minOf { (_, sprite) -> -sprite.originY }
        val maxX = rendered.maxOf { (_, sprite) -> -sprite.originX + sprite.width }
        val maxY = rendered.maxOf { (_, sprite) -> -sprite.originY + sprite.height }
        val width = maxX - minX
        val height = maxY - minY
        val frames = rendered.mapIndexed { index, (frame, sprite) ->
            val pixels = IntArray(width * height)
            val offsetX = -sprite.originX - minX
            val offsetY = -sprite.originY - minY
            for (y in 0 until sprite.height) {
                for (x in 0 until sprite.width) {
                    val color = sprite.pixels[y * sprite.width + x]
                    if (color != 0) pixels[(offsetY + y) * width + offsetX + x] = color
                }
            }
            SpriteAnimationFrame(
                pixels = pixels,
                width = width,
                height = height,
                durationTicks = frame.duration,
                label = "${def.name} ${index + 1} · $${frame.sourceSnes.toString(16).uppercase()}",
            )
        }
        return SpriteAnimation(def.name, frames, loop = false)
    }

    private fun readWord(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}

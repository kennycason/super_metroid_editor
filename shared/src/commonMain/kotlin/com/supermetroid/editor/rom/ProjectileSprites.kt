package com.supermetroid.editor.rom

/** Bank-$93 projectile lists use eight-byte frames, unlike enemy instruction lists. */
class ProjectileSprites(private val parser: RomParser) {
    data class Weapon(val name: String, val beam: Int? = null, val charged: Boolean = false, val nonBeam: Int? = null)

    companion object {
        val DIRECTIONS = listOf("Up", "Up-right", "Right", "Down-right", "Down", "Down (left)", "Down-left", "Left", "Up-left", "Up (left)")
        val BEAMS = listOf(
            "Power", "Wave", "Ice", "Ice + Wave",
            "Spazer", "Spazer + Wave", "Spazer + Ice", "Spazer + Ice + Wave",
            "Plasma", "Plasma + Wave", "Plasma + Ice", "Plasma + Ice + Wave",
            "Spazer + Plasma", "Spazer + Plasma + Wave", "Spazer + Plasma + Ice", "Spazer + Plasma + Ice + Wave",
        )
        val WEAPONS = buildList {
            for (charged in listOf(false, true)) BEAMS.forEachIndexed { mask, name ->
                add(Weapon(if (charged) "Charged $name" else name, mask, charged))
            }
            add(Weapon("Missile", nonBeam = 0))
            add(Weapon("Super Missile", nonBeam = 2))
            add(Weapon("Bomb", nonBeam = 5))
            add(Weapon("Beam impact", nonBeam = 7))
            add(Weapon("Missile impact", nonBeam = 8))
        }

        /** Combined masks share the matching native Plasma animation, tiles and palette. */
        fun nativeBeamMask(mask: Int): Int {
            require(mask in BEAMS.indices)
            return if (mask >= 0xC) mask and 0xB else mask
        }
    }

    private fun firstRecord(weapon: Weapon, direction: Int): Int {
        require(direction in DIRECTIONS.indices)
        val beam = weapon.beam?.let(::nativeBeamMask)
        val table = when {
            beam != null -> (if (weapon.charged) 0x9383D9 else 0x9383C1) + beam * 2
            weapon.nonBeam != null -> 0x9383F1 + weapon.nonBeam * 2
            else -> error("Weapon has no source definition")
        }
        val definition = 0x930000 or word(table)
        val directional = beam != null || weapon.nonBeam in listOf(0, 2)
        return 0x930000 or word(definition + 2 + if (directional) direction * 2 else 0)
    }

    /** Use the same source OAM palette row as the chosen projectile, not a generic effects row. */
    fun paletteRow(weapon: Weapon, direction: Int = 2): Int {
        val maps = EnemySpritemap(parser)
        var address = firstRecord(weapon, direction)
        val seen = mutableSetOf<Int>()
        while (seen.add(address) && seen.size <= 512) {
            when (word(address)) {
                0x8239 -> address = 0x930000 or word(address + 2)
                in 1..0x7FFF -> {
                    val map = maps.parseSpritemap(0x930000 or word(address + 2)) ?: break
                    if (map.entries.isNotEmpty()) return map.entries.groupBy { it.palRow }
                        .maxBy { (_, entries) -> entries.fold(0) { area, entry -> area + if (entry.is16x16) 256 else 64 } }.key
                    address += 8
                }
                else -> break
            }
        }
        return 6
    }

    fun render(weapon: Weapon, direction: Int = 2): SpriteAnimation? = runCatching {
        val beam = weapon.beam
        val nativeBeam = beam?.let(::nativeBeamMask)
        var address = firstRecord(weapon, direction)
        val tiles = RomVisualAssets.raw(parser, 0x9AD200, 0x2000)
        val colors = RomVisualAssets.palette(parser, 0x9A8100, 128)
        val rows = (0..7).associateWith { colors.copyOfRange(it * 16, (it + 1) * 16) }.toMutableMap()
        if (nativeBeam != null) {
            val source = 0x9A0000 or word(0x90C3B1 + nativeBeam * 2)
            // Update_Beam_Tiles_and_Palette transfers $100 bytes to VRAM $6300.
            RomVisualAssets.raw(parser, source, 0x100).copyInto(tiles, 0x600)
            rows[6] = RomVisualAssets.palette(parser, 0x900000 or word(0x90C3C9 + nativeBeam * 2), 16)
        }
        val maps = EnemySpritemap(parser)
        val seen = mutableSetOf<Int>()
        val rendered = mutableListOf<Pair<Int, EnemySpritemap.AssembledSprite>>()
        var loop = false
        for (operation in 0 until 512) {
            if (!seen.add(address)) { loop = true; break }
            val ticks = word(address)
            when (ticks) {
                0x8239 -> address = 0x930000 or word(address + 2)
                0x822F -> break
                in 1..0x7FFF -> {
                    val map = maps.parseSpritemap(0x930000 or word(address + 2)) ?: return null
                    val sprite = if (map.entries.isEmpty()) {
                        EnemySpritemap.AssembledSprite(1, 1, intArrayOf(0), 0, 0, map)
                    } else maps.renderSpritemap(map, tiles, rows.getValue(6), rows) ?: return null
                    rendered += ticks to sprite
                    address += 8
                }
                else -> return null
            }
        }
        if (rendered.none { (_, sprite) -> sprite.pixels.any { it ushr 24 != 0 } }) return null
        val animation = anchoredSpriteAnimation("${weapon.name} · ${DIRECTIONS[direction]}", rendered, loop)
        if (beam != null && beam >= 0xC) doubleLobed(animation, direction) else animation
    }.getOrNull()

    /** Match the ROM patch's perpendicular offsets and four-tick separation phase. */
    private fun doubleLobed(animation: SpriteAnimation, direction: Int): SpriteAnimation {
        fun offset(wide: Boolean): Pair<Int, Int> {
            val cardinal = if (wide) 6 else 4
            val diagonal = if (wide) 4 else 3
            return when (direction) {
                0, 4, 5, 9 -> -cardinal to 0
                2, 7 -> 0 to -cardinal
                1, 6 -> -diagonal to -diagonal
                3, 8 -> -diagonal to diagonal
                else -> error("Invalid projectile direction $direction")
            }
        }

        val phaseOffsets = listOf(offset(false), offset(true))
        val xOffsets = phaseOffsets.flatMap { (x, _) -> listOf(x, -x) }
        val yOffsets = phaseOffsets.flatMap { (_, y) -> listOf(y, -y) }
        val minX = xOffsets.min()
        val maxX = xOffsets.max()
        val minY = yOffsets.min()
        val maxY = yOffsets.max()
        val width = animation.frames.first().width + maxX - minX
        val height = animation.frames.first().height + maxY - minY

        fun compose(frame: SpriteAnimationFrame, phase: Int, ticks: Int): SpriteAnimationFrame {
            val (dx, dy) = phaseOffsets[phase]
            val pixels = IntArray(width * height)
            for ((ox, oy) in listOf(dx to dy, -dx to -dy)) {
                val left = ox - minX
                val top = oy - minY
                for (y in 0 until frame.height) for (x in 0 until frame.width) {
                    val pixel = frame.pixels[y * frame.width + x]
                    if (pixel ushr 24 != 0) pixels[(top + y) * width + left + x] = pixel
                }
            }
            return frame.copy(pixels = pixels, width = width, height = height, durationTicks = ticks)
        }

        var elapsed = 0
        val frames = buildList {
            for (frame in animation.frames) {
                if (frame.durationTicks <= 0) {
                    add(compose(frame, (elapsed / 4) and 1, frame.durationTicks))
                    continue
                }
                var remaining = frame.durationTicks
                while (remaining > 0) {
                    val ticks = minOf(remaining, 4 - elapsed % 4)
                    add(compose(frame, (elapsed / 4) and 1, ticks))
                    elapsed += ticks
                    remaining -= ticks
                }
            }
        }
        return animation.copy(frames = frames)
    }

    private fun word(address: Int): Int = parser.readUInt16At(parser.snesToPc(address))
}

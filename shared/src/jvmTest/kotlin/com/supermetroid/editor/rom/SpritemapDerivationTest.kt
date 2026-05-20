package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Old exploratory test — superseded by PhantoonSpritemapRoundtripTest which
 * achieves 100% match via direct BG2 tilemap parsing.
 */
@Disabled("Superseded by PhantoonSpritemapRoundtripTest")
class SpritemapDerivationTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    data class TileMatch(
        val tileIndex: Int,
        val hFlip: Boolean,
        val vFlip: Boolean,
        val distance: Int
    )

    /**
     * Normalize an 8x8 block of values to a palette-independent pattern.
     * First unique value -> 0, second -> 1, etc.
     * Transparent pixels (alpha < 128) all map to special index -1.
     */
    private fun normalizePattern(pixels: IntArray, w: Int, startX: Int, startY: Int): IntArray {
        val pattern = IntArray(64)
        val colorMap = mutableMapOf<Int, Int>()
        var nextIdx = 0
        colorMap[0] = -1 // transparent

        for (py in 0 until 8) {
            for (px in 0 until 8) {
                val imgX = startX + px
                val imgY = startY + py
                if (imgX >= w || imgY >= pixels.size / w) {
                    pattern[py * 8 + px] = -1
                    continue
                }
                val argb = pixels[imgY * w + imgX]
                val alpha = (argb ushr 24) and 0xFF
                if (alpha < 128) {
                    pattern[py * 8 + px] = -1
                } else {
                    val opaque = argb or (0xFF shl 24)
                    val idx = colorMap.getOrPut(opaque) { nextIdx++ }
                    pattern[py * 8 + px] = idx
                }
            }
        }
        return pattern
    }

    /**
     * Normalize a ROM tile (64 palette indices) to a pattern.
     * Index 0 = transparent.
     */
    private fun normalizeTilePattern(indices: IntArray): IntArray {
        val pattern = IntArray(64)
        val indexMap = mutableMapOf<Int, Int>()
        var nextIdx = 0
        indexMap[0] = -1

        for (i in indices.indices) {
            if (indices[i] == 0) {
                pattern[i] = -1
            } else {
                val idx = indexMap.getOrPut(indices[i]) { nextIdx++ }
                pattern[i] = idx
            }
        }
        return pattern
    }

    private fun flipH(pattern: IntArray): IntArray {
        val result = IntArray(64)
        for (py in 0 until 8) for (px in 0 until 8)
            result[py * 8 + px] = pattern[py * 8 + (7 - px)]
        return result
    }

    private fun flipV(pattern: IntArray): IntArray {
        val result = IntArray(64)
        for (py in 0 until 8) for (px in 0 until 8)
            result[py * 8 + px] = pattern[(7 - py) * 8 + px]
        return result
    }

    private fun patternsMatch(a: IntArray, b: IntArray): Boolean {
        for (i in a.indices) if (a[i] != b[i]) return false
        return true
    }

    /** Read the 64 palette indices from a global tile. */
    private fun readTileIndices(gfx: EnemySpriteGraphics, globalTile: Int): IntArray {
        val indices = IntArray(64)
        for (py in 0 until 8) for (px in 0 until 8)
            indices[py * 8 + px] = gfx.readPixelIndex(globalTile, px, py)
        return indices
    }

    @Test
    fun `derive Phantoon body spritemap from E4BF PNG via pattern matching`() {
        val parser = loadTestRom() ?: return
        val gfx = EnemySpriteGraphics(parser)
        assertTrue(gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS))

        val totalTiles = gfx.getTileCount()
        println("Loaded $totalTiles ROM tiles")

        // Build normalized patterns for all tiles (with all 4 flip variants)
        data class TilePatterns(
            val normal: IntArray,
            val hFlip: IntArray,
            val vFlip: IntArray,
            val hvFlip: IntArray
        )

        val tilePatterns = (0 until totalTiles).map { t ->
            val indices = readTileIndices(gfx, t)
            val norm = normalizeTilePattern(indices)
            TilePatterns(
                normal = norm,
                hFlip = flipH(norm),
                vFlip = flipV(norm),
                hvFlip = flipV(flipH(norm))
            )
        }

        // Load PNG
        val pngFile = File("../desktopApp/src/jvmMain/resources/enemies/E4BF.png")
        if (!pngFile.exists()) { println("E4BF.png not found"); return }
        val img = javax.imageio.ImageIO.read(pngFile)
        val w = img.width
        val h = img.height
        val pixels = IntArray(w * h)
        img.getRGB(0, 0, w, h, pixels, 0, w)
        println("PNG: ${w}x${h}")

        // Try matching at every possible pixel position (not just 8-aligned)
        // But start with 8-aligned for efficiency
        val matches = mutableListOf<Triple<Int, Int, TileMatch>>() // (x, y, match)
        val unmatchedPositions = mutableListOf<Pair<Int, Int>>()

        // Try all 1-pixel-aligned positions within the PNG
        for (startY in 0..h - 8) {
            for (startX in 0..w - 8) {
                val pngPattern = normalizePattern(pixels, w, startX, startY)
                val isTransparent = pngPattern.all { it == -1 }
                if (isTransparent) continue

                // Only check positions that haven't been part of a match yet
                // (for efficiency, just do full scan)
                for ((t, tp) in tilePatterns.withIndex()) {
                    val flipFlags = when {
                        patternsMatch(pngPattern, tp.normal) -> 0
                        patternsMatch(pngPattern, tp.hFlip) -> 1
                        patternsMatch(pngPattern, tp.vFlip) -> 2
                        patternsMatch(pngPattern, tp.hvFlip) -> 3
                        else -> -1
                    }
                    if (flipFlags >= 0) {
                        matches.add(Triple(startX, startY, TileMatch(t, flipFlags and 1 != 0, flipFlags and 2 != 0, 0)))
                        break
                    }
                }
            }
        }

        println("\nMatches found: ${matches.size}")

        // Group by unique tile positions (prefer 8-aligned)
        val uniqueMatches = mutableMapOf<Pair<Int, Int>, TileMatch>()
        for ((x, y, m) in matches) {
            val key = x to y
            uniqueMatches[key] = m
        }

        // Show matches that are on 8-pixel grid
        val gridMatches = uniqueMatches.filter { (pos, _) -> pos.first % 8 == 0 && pos.second % 8 == 0 }
        println("8-aligned matches: ${gridMatches.size}")

        // Now find the best non-overlapping set of tile placements
        // For each match position, record which tile it matched
        println("\n=== All 8-aligned tile placements ===")
        for ((pos, m) in gridMatches.entries.sortedWith(compareBy({ it.key.second }, { it.key.first }))) {
            val flipStr = when {
                m.hFlip && m.vFlip -> "HV"
                m.hFlip -> "H"
                m.vFlip -> "V"
                else -> "--"
            }
            println("  (${pos.first}, ${pos.second}) → tile ${m.tileIndex} flip=$flipStr")
        }

        // Also look for non-8-aligned matches that are most frequent
        // Group non-grid matches by offset mod 8
        val offsetCounts = mutableMapOf<Pair<Int, Int>, Int>()
        for ((x, y, _) in matches) {
            val key = (x % 8) to (y % 8)
            offsetCounts[key] = (offsetCounts[key] ?: 0) + 1
        }
        println("\nMatch count by (x%8, y%8) offset:")
        for ((offset, count) in offsetCounts.entries.sortedByDescending { it.value }.take(5)) {
            println("  offset ${offset.first},${offset.second}: $count matches")
        }

        assertTrue(matches.isNotEmpty(), "Should find at least some tile matches")
    }

    @Test
    fun `derive spritemaps for all 4 Phantoon component PNGs`() {
        val parser = loadTestRom() ?: return
        val gfx = EnemySpriteGraphics(parser)
        assertTrue(gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS))

        val totalTiles = gfx.getTileCount()

        data class TilePatterns(val normal: IntArray, val hFlip: IntArray, val vFlip: IntArray, val hvFlip: IntArray)
        val tilePatterns = (0 until totalTiles).map { t ->
            val indices = readTileIndices(gfx, t)
            val norm = normalizeTilePattern(indices)
            TilePatterns(norm, flipH(norm), flipV(norm), flipV(flipH(norm)))
        }

        val components = listOf("E4BF", "E4FF", "E53F", "E57F")
        for (speciesId in components) {
            val pngFile = File("../desktopApp/src/jvmMain/resources/enemies/$speciesId.png")
            if (!pngFile.exists()) { println("$speciesId.png not found"); continue }
            val img = javax.imageio.ImageIO.read(pngFile)
            val w = img.width
            val h = img.height
            val pixels = IntArray(w * h)
            img.getRGB(0, 0, w, h, pixels, 0, w)

            println("\n=== $speciesId (${w}x${h}) ===")

            // Try all offsets mod 8 to find the best alignment
            var bestOffset = 0 to 0
            var bestCount = 0

            for (offY in 0 until 8) {
                for (offX in 0 until 8) {
                    var count = 0
                    var y = offY
                    while (y + 8 <= h) {
                        var x = offX
                        while (x + 8 <= w) {
                            val pngPattern = normalizePattern(pixels, w, x, y)
                            if (!pngPattern.all { it == -1 }) {
                                for ((_, tp) in tilePatterns.withIndex()) {
                                    if (patternsMatch(pngPattern, tp.normal) ||
                                        patternsMatch(pngPattern, tp.hFlip) ||
                                        patternsMatch(pngPattern, tp.vFlip) ||
                                        patternsMatch(pngPattern, tp.hvFlip)) {
                                        count++
                                        break
                                    }
                                }
                            }
                            x += 8
                        }
                        y += 8
                    }
                    if (count > bestCount) {
                        bestCount = count
                        bestOffset = offX to offY
                    }
                }
            }

            println("Best alignment: offset (${bestOffset.first}, ${bestOffset.second}) with $bestCount tile matches")

            // Now report full layout at best offset
            val (offX, offY) = bestOffset
            var y = offY
            val placements = mutableListOf<Triple<Int, Int, TileMatch>>()
            while (y + 8 <= h) {
                var x = offX
                while (x + 8 <= w) {
                    val pngPattern = normalizePattern(pixels, w, x, y)
                    if (!pngPattern.all { it == -1 }) {
                        for ((t, tp) in tilePatterns.withIndex()) {
                            val flipFlags = when {
                                patternsMatch(pngPattern, tp.normal) -> 0
                                patternsMatch(pngPattern, tp.hFlip) -> 1
                                patternsMatch(pngPattern, tp.vFlip) -> 2
                                patternsMatch(pngPattern, tp.hvFlip) -> 3
                                else -> -1
                            }
                            if (flipFlags >= 0) {
                                placements.add(Triple(x, y, TileMatch(t, flipFlags and 1 != 0, flipFlags and 2 != 0, 0)))
                                break
                            }
                        }
                    }
                    x += 8
                }
                y += 8
            }

            println("Tile placements (${placements.size}):")
            for ((px, py, m) in placements.sortedWith(compareBy({ it.second }, { it.first }))) {
                val flipStr = when {
                    m.hFlip && m.vFlip -> "HV"
                    m.hFlip -> "H"
                    m.vFlip -> "V"
                    else -> "--"
                }
                println("  ($px, $py) → tile ${m.tileIndex} flip=$flipStr")
            }

            // Count non-transparent non-matched 8x8 blocks
            var nonEmpty = 0
            var unmatched = 0
            y = offY
            while (y + 8 <= h) {
                var x = offX
                while (x + 8 <= w) {
                    val pngPattern = normalizePattern(pixels, w, x, y)
                    if (!pngPattern.all { it == -1 }) {
                        nonEmpty++
                        val matched = tilePatterns.any { tp ->
                            patternsMatch(pngPattern, tp.normal) ||
                            patternsMatch(pngPattern, tp.hFlip) ||
                            patternsMatch(pngPattern, tp.vFlip) ||
                            patternsMatch(pngPattern, tp.hvFlip)
                        }
                        if (!matched) unmatched++
                    }
                    x += 8
                }
                y += 8
            }
            println("Non-empty blocks: $nonEmpty, Unmatched: $unmatched")
        }
    }
}

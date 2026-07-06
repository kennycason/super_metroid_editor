package com.supermetroid.editor.procgen

import kotlin.random.Random

internal object TileDresser {
    data class DressedGrid(val words: IntArray, val bts: IntArray)

    fun dress(
        cells: IntArray,
        width: Int,
        height: Int,
        preserved: BooleanArray,
        originalWords: IntArray,
        originalBts: IntArray,
        profile: TilesetProfile,
        rules: BiomeRules,
        rng: Random,
    ): DressedGrid {
        val n = width * height
        val words = IntArray(n)
        val bts = IntArray(n)
        val chosen = IntArray(n) { -1 }
        val pipeMaze = rules.algorithm == StructureAlgorithm.MAZE
        fun idx(x: Int, y: Int) = y * width + x
        fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height

        for (y in 0 until height) for (x in 0 until width) {
            val i = idx(x, y)
            if (preserved[i]) {
                words[i] = originalWords[i]
                bts[i] = originalBts[i]
                continue
            }
            when (cells[i]) {
                BiomeCell.AIR -> {
                    words[i] = profile.airWord
                    bts[i] = 0
                }
                BiomeCell.SOLID -> {
                    val word = if (pipeMaze) {
                        pickMazePipeWall(cells, width, height, x, y)
                    } else {
                        val mask = NeighborMask.fromStructureGrid(cells, width, height, x, y)
                        pickCoherentSolid(cells, chosen, width, height, x, y, mask, rules, rng)
                            ?: profile.pickSolid(mask, rng)
                    }
                    chosen[i] = word
                    words[i] = word
                    bts[i] = 0
                }
                BiomeCell.SPIKE -> {
                    words[i] = profile.pickSpike(rng)
                    bts[i] = 0
                }
                BiomeCell.CRUMBLE -> {
                    val p = profile.pickCrumble(rng)
                    words[i] = p.first
                    bts[i] = p.second
                }
                BiomeCell.SHOT_HIDDEN -> {
                    val p = profile.pickHiddenShot(rng)
                    words[i] = p.first
                    bts[i] = p.second
                }
                BiomeCell.BOMB -> {
                    val p = profile.pickBomb(rng)
                    words[i] = p.first
                    bts[i] = p.second
                }
            }
        }
        camouflageHiddenShots(cells, words, bts, chosen, width, height, preserved, ::idx, ::inBounds)
        return DressedGrid(words, bts)
    }

    private fun pickMazePipeWall(cells: IntArray, width: Int, height: Int, x: Int, y: Int): Int {
        fun isAir(nx: Int, ny: Int): Boolean =
            nx in 0 until width && ny in 0 until height && cells[ny * width + nx] == BiomeCell.AIR
        val openWest = isAir(x - 1, y)
        val openEast = isAir(x + 1, y)
        val openNorth = isAir(x, y - 1)
        val openSouth = isAir(x, y + 1)
        val verticalSideWall = (openWest || openEast) && !openNorth && !openSouth
        // Level words only support H/V flips, so horizontal pipe runs need a dedicated metatile.
        return if (verticalSideWall) CRE_VERTICAL_PIPE_WORD else CRE_PIPE_JOINT_WORD
    }

    private fun pickCoherentSolid(
        cells: IntArray,
        chosen: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        mask: Int,
        rules: BiomeRules,
        rng: Random,
    ): Int? {
        fun cohere(nx: Int, ny: Int): Int? {
            if (nx < 0 || ny < 0) return null
            val ni = ny * width + nx
            if (chosen[ni] < 0 || cells[ni] != BiomeCell.SOLID) return null
            val neighborMask = NeighborMask.fromStructureGrid(cells, width, height, nx, ny)
            val chance = when {
                neighborMask == mask -> rules.textureCohesion
                (neighborMask and 0x0F) == (mask and 0x0F) -> rules.textureCohesion * 0.68
                else -> return null
            }
            return if (rng.nextDouble() < chance) chosen[ni] else null
        }
        return cohere(x - 1, y) ?: cohere(x, y - 1)
    }

    private fun camouflageHiddenShots(
        cells: IntArray,
        words: IntArray,
        bts: IntArray,
        chosen: IntArray,
        width: Int,
        height: Int,
        preserved: BooleanArray,
        idx: (Int, Int) -> Int,
        inBounds: (Int, Int) -> Boolean,
    ) {
        for (y in 0 until height) for (x in 0 until width) {
            val i = idx(x, y)
            if (cells[i] != BiomeCell.SHOT_HIDDEN || preserved[i]) continue
            for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                val nx = x + dx
                val ny = y + dy
                if (!inBounds(nx, ny)) continue
                val ni = idx(nx, ny)
                if (cells[ni] == BiomeCell.SOLID && chosen[ni] >= 0) {
                    words[i] = (chosen[ni] and 0x0FFF) or (0xC shl 12)
                    bts[i] = 0x04
                    break
                }
            }
        }
    }

    private const val CRE_PIPE_JOINT = 0x05F
    private const val CRE_VERTICAL_PIPE = 0x0BE
    private const val SOLID_TYPE = 0x8 shl 12

    private const val CRE_PIPE_JOINT_WORD = SOLID_TYPE or CRE_PIPE_JOINT
    private const val CRE_VERTICAL_PIPE_WORD = SOLID_TYPE or CRE_VERTICAL_PIPE
}

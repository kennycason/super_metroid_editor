package com.supermetroid.editor.procgen

import kotlin.random.Random

/**
 * Generative biome builder: produces a full room layout (layer-1 block words +
 * BTS) from a rolled [BiomeRules] card and a learned [TilesetProfile].
 *
 * Pipeline is split across [DoorPreservation], [StructureAlgorithms],
 * [GridConnectivity], [BiomeMutators], and [TileDresser].
 *
 * Deterministic for a given (rules, seed, room contents).
 */
class BiomeGenerator(
    private val rules: BiomeRules,
    private val profile: TilesetProfile,
    private val seed: Long,
) {
    /** Result grid, row-major width*height. */
    class GeneratedLevel(
        val width: Int,
        val height: Int,
        val words: IntArray,
        val bts: IntArray,
        /** Cells copied verbatim from the original room (door frames). */
        val preserved: BooleanArray,
    )

    fun generate(width: Int, height: Int, originalWords: IntArray, originalBts: IntArray): GeneratedLevel {
        require(originalWords.size >= width * height) { "original grid smaller than room" }
        val rng = Random(seed)
        val n = width * height
        val cells = IntArray(n)
        fun idx(x: Int, y: Int) = y * width + x
        fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height

        val doorSetup = DoorPreservation.setup(originalWords, width, height)
        val preserved = doorSetup.preserved
        val forceAir = doorSetup.forceAir
        val pockets = doorSetup.pockets

        // Silhouette of the pre-generation room, used by REMIX to keep the
        // original macro layout while re-rolling detail.
        val originalSolid = BooleanArray(n) { isSilhouetteSolid((originalWords[it] shr 12) and 0xF) }
        StructureAlgorithms.build(cells, width, height, rules, rng, originalSolid, pockets)

        for (y in 0 until height) for (x in 0 until width) {
            if (x < BiomeCell.BORDER || y < BiomeCell.BORDER ||
                x >= width - BiomeCell.BORDER || y >= height - BiomeCell.BORDER
            ) {
                cells[idx(x, y)] = BiomeCell.SOLID
            }
        }
        for (i in 0 until n) if (forceAir[i]) cells[i] = BiomeCell.AIR

        val isMaze = rules.algorithm == StructureAlgorithm.MAZE
        val protectedCells = BooleanArray(n) { preserved[it] || forceAir[it] }
        if (!isMaze) {
            StructureAlgorithms.refine(cells, width, height, rules, protectedCells)
            GridConnectivity.ensureConnected(cells, width, height, preserved, rng) { it == BiomeCell.AIR }
        }
        for (p in pockets) {
            val floorY = p.y1 + 1
            if (floorY < height - 1) {
                for (x in p.x0..p.x1) {
                    if (inBounds(x, floorY) && !preserved[idx(x, floorY)]) {
                        cells[idx(x, floorY)] = BiomeCell.SOLID
                    }
                }
            }
        }

        val editable = BooleanArray(n) { !preserved[it] && !forceAir[it] }
        if (!isMaze) {
            BiomeMutators.applyAll(cells, width, height, rules, editable, rng)

            GridConnectivity.ensureConnected(cells, width, height, preserved, rng) {
                it == BiomeCell.AIR || it == BiomeCell.CRUMBLE || it == BiomeCell.SHOT_HIDDEN || it == BiomeCell.BOMB
            }
            BiomeMutators.removeFloatingDebris(cells, width, height, editable)
        }

        val dressed = TileDresser.dress(
            cells, width, height, preserved, originalWords, originalBts, profile, rules, rng,
        )
        return GeneratedLevel(width, height, dressed.words, dressed.bts, preserved)
    }

    private companion object {
        /**
         * Block types that read as open space in the room's visual silhouette.
         * Destructibles (crumble/shot/bomb) count as solid: they are part of
         * the structure even though Samus can pass through them.
         */
        fun isSilhouetteSolid(type: Int): Boolean =
            type != 0x0 && type != 0x2 && type != 0x4 && type != 0x6 && type != 0x7 && type != 0x9
    }
}

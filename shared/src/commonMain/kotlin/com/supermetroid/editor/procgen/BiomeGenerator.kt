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

        StructureAlgorithms.build(cells, width, height, rules, rng)

        for (y in 0 until height) for (x in 0 until width) {
            if (x < BiomeCell.BORDER || y < BiomeCell.BORDER ||
                x >= width - BiomeCell.BORDER || y >= height - BiomeCell.BORDER
            ) {
                cells[idx(x, y)] = BiomeCell.SOLID
            }
        }
        for (i in 0 until n) if (forceAir[i]) cells[i] = BiomeCell.AIR

        val protectedCells = BooleanArray(n) { preserved[it] || forceAir[it] }
        StructureAlgorithms.refine(cells, width, height, rules, protectedCells)

        GridConnectivity.ensureConnected(cells, width, height, preserved, rng) { it == BiomeCell.AIR }
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
        BiomeMutators.applyAll(cells, width, height, rules, editable, rng)

        GridConnectivity.ensureConnected(cells, width, height, preserved, rng) {
            it == BiomeCell.AIR || it == BiomeCell.CRUMBLE || it == BiomeCell.SHOT_HIDDEN || it == BiomeCell.BOMB
        }
        BiomeMutators.removeFloatingDebris(cells, width, height, editable)

        val dressed = TileDresser.dress(
            cells, width, height, preserved, originalWords, originalBts, profile, rules, rng,
        )
        return GeneratedLevel(width, height, dressed.words, dressed.bts, preserved)
    }
}

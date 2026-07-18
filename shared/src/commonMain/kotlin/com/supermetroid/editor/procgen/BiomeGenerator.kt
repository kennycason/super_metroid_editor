package com.supermetroid.editor.procgen

import kotlin.random.Random

data class BiomeGenerationRect(
    val x0: Int,
    val y0: Int,
    val x1: Int,
    val y1: Int,
)

data class BiomeGenerationOptions(
    val preserveRects: List<BiomeGenerationRect> = emptyList(),
    val forceAirRects: List<BiomeGenerationRect> = emptyList(),
    val wfcSamples: List<WfcSample> = emptyList(),
    val wfcOptions: WfcOptions = WfcOptions(),
)

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
    private val options: BiomeGenerationOptions = BiomeGenerationOptions(),
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

        val isMaze = rules.algorithm == StructureAlgorithm.MAZE
        val doorSetup = DoorPreservation.setup(originalWords, originalBts, width, height)
        val preserved = doorSetup.preserved
        val forceAir = doorSetup.forceAir
        val pockets = doorSetup.pockets
        val explicitPreserve = BooleanArray(n)
        val explicitForceAir = BooleanArray(n)
        applyRects(explicitForceAir, width, height, options.forceAirRects)
        for (i in 0 until n) if (explicitForceAir[i]) forceAir[i] = true
        applyRects(explicitPreserve, width, height, options.preserveRects)
        for (i in 0 until n) {
            if (explicitForceAir[i] && !explicitPreserve[i] && ((originalWords[i] shr 12) and 0xF) != 0x9) {
                preserved[i] = false
            }
        }
        for (i in 0 until n) if (explicitPreserve[i]) preserved[i] = true
        if (isMaze) {
            preserveOriginalMazeCollision(originalWords, width, preserved, forceAir)
        }

        if (rules.algorithm == StructureAlgorithm.WFC) {
            return WaveFunctionCollapseGenerator(
                rules = rules,
                profile = profile,
                seed = seed,
                samples = options.wfcSamples,
                options = options.wfcOptions,
            ).generate(width, height, originalWords, originalBts, preserved, forceAir)
        }

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

        fun preservedOriginalPassable(i: Int): Boolean =
            preserved[i] && isGameplayPassableType(resolvedBlockType(originalWords, width, i))

        fun generatedOrPreservedPassable(i: Int): Boolean =
            (cells[i] == BiomeCell.AIR && !preserved[i]) || preservedOriginalPassable(i)

        val protectedCells = BooleanArray(n) { preserved[it] || forceAir[it] }
        if (!isMaze) {
            StructureAlgorithms.refine(cells, width, height, rules, protectedCells)
            GridConnectivity.ensureConnected(cells, width, height, preserved, rng) { it == BiomeCell.AIR }
            GridConnectivity.ensureConnectedByIndex(
                cells,
                width,
                height,
                rng,
                tunnelThickness = 2,
                canCarve = { !preserved[it] },
                passable = ::generatedOrPreservedPassable,
            )
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

        if (isMaze) {
            GridConnectivity.ensureConnectedByIndex(
                cells,
                width,
                height,
                rng,
                tunnelThickness = 1,
                canCarve = { !preserved[it] },
                passable = ::generatedOrPreservedPassable,
                required = { ((originalWords[it] shr 12) and 0xF) == 0x9 },
            )
        }

        val editable = BooleanArray(n) { !preserved[it] && !forceAir[it] }
        if (!isMaze) {
            BiomeMutators.applyAll(cells, width, height, rules, editable, rng)

            GridConnectivity.ensureConnected(cells, width, height, preserved, rng) {
                it == BiomeCell.AIR || it == BiomeCell.CRUMBLE || it == BiomeCell.SHOT_HIDDEN || it == BiomeCell.BOMB
            }
            GridConnectivity.ensureConnectedByIndex(
                cells,
                width,
                height,
                rng,
                tunnelThickness = 2,
                canCarve = { !preserved[it] },
                passable = {
                    (!preserved[it] &&
                        (cells[it] == BiomeCell.AIR ||
                            cells[it] == BiomeCell.CRUMBLE ||
                            cells[it] == BiomeCell.SHOT_HIDDEN ||
                            cells[it] == BiomeCell.BOMB)) ||
                        preservedOriginalPassable(it)
                },
            )
            BiomeMutators.removeFloatingDebris(cells, width, height, editable)
        }

        val dressed = TileDresser.dress(
            cells, width, height, preserved, originalWords, originalBts, profile, rules, rng,
        )
        enforceFinalConnectivity(
            dressed.words,
            dressed.bts,
            width,
            height,
            preserved,
            profile,
            rng,
            tunnelThickness = if (isMaze) 1 else 2,
        )
        for (i in 0 until n) {
            if (forceAir[i] && !preserved[i]) {
                dressed.words[i] = FORCED_AIR_WORD
                dressed.bts[i] = 0
            }
        }
        return GeneratedLevel(width, height, dressed.words, dressed.bts, preserved)
    }

    private companion object {
        private const val FORCED_AIR_WORD = 0x00FF

        /**
         * Block types that read as open space in the room's visual silhouette.
         * Destructibles (crumble/shot/bomb) count as solid: they are part of
         * the structure even though Samus can pass through them.
         */
        fun isSilhouetteSolid(type: Int): Boolean =
            type != 0x0 && type != 0x2 && type != 0x4 && type != 0x6 && type != 0x7 && type != 0x9

        fun isGameplayPassableType(type: Int): Boolean =
            type == 0x0 || type == 0x2 || type == 0x4 || type == 0x7 ||
                type == 0x9 || type == 0xB || type == 0xC || type == 0xF

        fun resolvedBlockType(words: IntArray, width: Int, index: Int): Int {
            var cursor = index
            repeat(32) {
                val type = (words[cursor] shr 12) and 0xF
                when (type) {
                    0x5 -> {
                        if (cursor % width == 0) return 0x8
                        cursor -= 1
                    }
                    0xD -> {
                        if (cursor < width) return 0x8
                        cursor -= width
                    }
                    else -> return type
                }
            }
            return (words[index] shr 12) and 0xF
        }

        fun isMazeProtectedCollisionType(type: Int): Boolean =
            type == 0x3 || type == 0xA || type == 0xC || type == 0xE || type == 0xF

        fun preserveOriginalMazeCollision(
            originalWords: IntArray,
            width: Int,
            preserved: BooleanArray,
            forceAir: BooleanArray,
        ) {
            for (i in preserved.indices) {
                if (!preserved[i] && !forceAir[i] &&
                    isMazeProtectedCollisionType(resolvedBlockType(originalWords, width, i))
                ) {
                    preserved[i] = true
                }
            }
        }

        fun applyRects(mask: BooleanArray, width: Int, height: Int, rects: List<BiomeGenerationRect>) {
            for (rect in rects) {
                val x0 = rect.x0.coerceIn(0, width - 1)
                val x1 = rect.x1.coerceIn(0, width - 1)
                val y0 = rect.y0.coerceIn(0, height - 1)
                val y1 = rect.y1.coerceIn(0, height - 1)
                if (x1 < x0 || y1 < y0) continue
                for (y in y0..y1) for (x in x0..x1) mask[y * width + x] = true
            }
        }

        fun enforceFinalConnectivity(
            words: IntArray,
            bts: IntArray,
            width: Int,
            height: Int,
            preserved: BooleanArray,
            profile: TilesetProfile,
            rng: Random,
            tunnelThickness: Int,
        ) {
            val passableCells = IntArray(words.size) { i ->
                if (isGameplayPassableType(resolvedBlockType(words, width, i))) BiomeCell.AIR else BiomeCell.SOLID
            }
            GridConnectivity.ensureConnectedByIndex(
                passableCells,
                width,
                height,
                rng,
                tunnelThickness = tunnelThickness,
                canCarve = { !preserved[it] },
                passable = { passableCells[it] == BiomeCell.AIR },
            )
            for (i in words.indices) {
                if (passableCells[i] == BiomeCell.AIR &&
                    !preserved[i] &&
                    !isGameplayPassableType((words[i] shr 12) and 0xF)
                ) {
                    words[i] = profile.airWord
                    bts[i] = 0
                }
            }
        }
    }
}

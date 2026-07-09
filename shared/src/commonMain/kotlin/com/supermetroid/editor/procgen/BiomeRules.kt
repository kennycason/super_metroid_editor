package com.supermetroid.editor.procgen

import kotlin.random.Random

/**
 * A concrete, seeded rule set for one generation run — the "rule card".
 *
 * Rolled from a [BiomeStyle] envelope so every seed produces a related but
 * distinct set of rules (fill density, smoothing behavior, which mutators are
 * active, hazard levels...). [describe] renders a human-readable summary that
 * the UI shows so the user can see what rules were generated.
 */
data class BiomeRules(
    val style: BiomeStyle,
    val algorithm: StructureAlgorithm,
    /** Initial probability a cell starts solid (cellular automata seed). */
    val fillChance: Double,
    /** Cellular automata smoothing iterations. */
    val caIterations: Int,
    /** Neighbor count at/above which an air cell becomes solid. */
    val birthLimit: Int,
    /** Neighbor count below which a solid cell erodes to air. */
    val surviveLimit: Int,
    /** Vertical stretch of the initial noise (>1 favors columns/shafts). */
    val verticalBias: Double,
    /** 0..1 chance-scale for floating platforms in open air spans. */
    val platformDensity: Double,
    /** 0..1 chance-scale for spike placement on floor runs. */
    val hazardDensity: Double,
    /** 0..1 chance-scale for crumble/shot/bomb features. */
    val destructibleDensity: Double,
    /** Probability of reusing the left neighbor's tile word for texture runs. */
    val textureCohesion: Double,
    /** Active gameplay mutators for this rule card. */
    val mutators: Set<BiomeMutator>,
    /** 0..1 chance that the maze frontier branches from an older corridor. */
    val mazeBranchDensity: Double = 0.45,
    /** 0..1 chance to carve extra links between adjacent maze corridors. */
    val mazeLoopDensity: Double = 0.08,
    /** 0..1 size of the optional central hub room/ring in pipe mazes. */
    val mazeHubSize: Double = 0.55,
    /** Whether pipe mazes carve a central empty hub. */
    val mazeEmptyCenter: Boolean = true,
) {
    /** Human-readable rule card shown in the generator UI. */
    fun describe(): String {
        val lines = mutableListOf<String>()
        lines += "${style.displayName} — ${style.blurb}"
        lines += when (algorithm) {
            StructureAlgorithm.REMIX ->
                "Terrain: rebuilt from this room's original silhouette"
            StructureAlgorithm.RECTILINEAR, StructureAlgorithm.SETTLEMENT ->
                "Terrain: constructed ${algorithm.name.lowercase().replace('_', ' ')}, straight edges"
            StructureAlgorithm.MAZE ->
                "Terrain: one-tile pipe maze, ${(mazeLoopDensity * 100).toInt()}% loop bias" +
                    if (mazeEmptyCenter) ", empty center" else ""
            StructureAlgorithm.WFC ->
                "Terrain: door-seeded all-room pattern remix"
            else -> {
                val density = when {
                    fillChance < 0.42 -> "airy"
                    fillChance < 0.50 -> "balanced"
                    else -> "dense"
                }
                "Terrain: $density ${algorithm.name.lowercase().replace('_', ' ')}" +
                    ", smoothing x$caIterations"
            }
        }
        if (platformDensity > 0.05) {
            val amount = if (platformDensity > 0.5) "many" else "some"
            lines += "Platforms: $amount floating ledges in open spans"
        }
        if (hazardDensity > 0.05) {
            val amount = if (hazardDensity > 0.5) "frequent" else "occasional"
            lines += "Hazards: $amount spike pockets"
        }
        if (mutators.isNotEmpty()) {
            lines += "Mutators: " + mutators.joinToString(", ") { it.displayName.lowercase() }
        }
        return lines.joinToString("\n")
    }

    /**
     * Apply UI slider overrides. Mutators are derived from density thresholds so
     * [describe] and generation always reflect the same effective rules.
     */
    fun withOverrides(platformDensity: Double, hazardDensity: Double, destructibleDensity: Double): BiomeRules {
        val mutators = mutators.toMutableSet()
        if (hazardDensity > 0.02) mutators.add(BiomeMutator.SPIKE_POCKETS) else mutators.remove(BiomeMutator.SPIKE_POCKETS)
        if (destructibleDensity <= 0.02) {
            mutators.remove(BiomeMutator.CRUMBLE_BRIDGES)
            mutators.remove(BiomeMutator.HIDDEN_PASSAGES)
            mutators.remove(BiomeMutator.BOMB_WALLS)
            mutators.remove(BiomeMutator.RUBBLE_PILES)
        }
        return copy(
            platformDensity = platformDensity,
            hazardDensity = hazardDensity,
            destructibleDensity = destructibleDensity,
            mutators = mutators,
        )
    }

    fun withMazeOverrides(
        branchDensity: Double,
        loopDensity: Double,
        hubSize: Double,
        emptyCenter: Boolean,
    ): BiomeRules =
        copy(
            platformDensity = 0.0,
            hazardDensity = 0.0,
            destructibleDensity = 0.0,
            mutators = emptySet(),
            mazeBranchDensity = branchDensity.coerceIn(0.0, 1.0),
            mazeLoopDensity = loopDensity.coerceIn(0.0, 1.0),
            mazeHubSize = hubSize.coerceIn(0.0, 1.0),
            mazeEmptyCenter = emptyCenter,
        )

    companion object {
        /**
         * Roll a concrete rule card for [style] from [seed]. The same
         * (style, seed) pair always yields the same rules.
         */
        fun roll(style: BiomeStyle, seed: Long): BiomeRules {
            val rng = Random(seed * 31 + style.ordinal)
            val actualStyle = if (style == BiomeStyle.SURPRISE) {
                BiomeStyle.values().toList()
                    .filter { it != BiomeStyle.SURPRISE && it != BiomeStyle.WAVE_FUNCTION }
                    .random(rng)
            } else style
            val wild = style == BiomeStyle.SURPRISE

            fun Random.range(lo: Double, hi: Double) = lo + nextDouble() * (hi - lo)
            // SURPRISE widens every envelope so rules can land outside the usual feel.
            fun Random.env(lo: Double, hi: Double): Double =
                if (wild) range(lo - (hi - lo) * 0.5, hi + (hi - lo) * 0.5).coerceIn(0.0, 1.0)
                else range(lo, hi)

            val algorithm = when (actualStyle) {
                BiomeStyle.CAVERN, BiomeStyle.WARREN -> StructureAlgorithm.CAVE
                BiomeStyle.RUINS -> StructureAlgorithm.CHAMBERS
                BiomeStyle.SHAFT -> StructureAlgorithm.VERTICAL
                BiomeStyle.GARDEN -> StructureAlgorithm.OPEN_GALLERY
                BiomeStyle.PIPE_MAZE -> StructureAlgorithm.MAZE
                BiomeStyle.FACILITY -> StructureAlgorithm.RECTILINEAR
                BiomeStyle.SETTLEMENT -> StructureAlgorithm.SETTLEMENT
                BiomeStyle.REMIX -> StructureAlgorithm.REMIX
                BiomeStyle.SURPRISE -> listOf(
                    StructureAlgorithm.CAVE,
                    StructureAlgorithm.CHAMBERS,
                    StructureAlgorithm.VERTICAL,
                    StructureAlgorithm.OPEN_GALLERY,
                    StructureAlgorithm.MAZE,
                    StructureAlgorithm.RECTILINEAR,
                    StructureAlgorithm.SETTLEMENT,
                    StructureAlgorithm.REMIX,
                ).random(rng)
                BiomeStyle.WAVE_FUNCTION -> StructureAlgorithm.WFC
            }

            val fill = when (actualStyle) {
                BiomeStyle.CAVERN -> rng.env(0.40, 0.48)
                BiomeStyle.WARREN -> rng.env(0.50, 0.58)
                BiomeStyle.RUINS -> rng.env(0.42, 0.50)
                BiomeStyle.SHAFT -> rng.env(0.44, 0.52)
                BiomeStyle.GARDEN -> rng.env(0.30, 0.38)
                BiomeStyle.PIPE_MAZE -> rng.env(0.74, 0.82)
                BiomeStyle.FACILITY, BiomeStyle.SETTLEMENT -> rng.env(0.42, 0.50)
                BiomeStyle.REMIX -> rng.env(0.44, 0.52)
                BiomeStyle.SURPRISE -> rng.env(0.35, 0.55)
                BiomeStyle.WAVE_FUNCTION -> rng.env(0.44, 0.52)
            }

            val mutatorPool = BiomeMutator.values().toMutableList()
            if (actualStyle.isConstructed) {
                // No stalactites or rubble piles inside built structures.
                mutatorPool.remove(BiomeMutator.CEILING_FANGS)
                mutatorPool.remove(BiomeMutator.RUBBLE_PILES)
            }
            val mutatorCount = if (wild) rng.nextInt(2, 5) else rng.nextInt(1, 4)
            val mutators = buildSet {
                repeat(mutatorCount) {
                    if (mutatorPool.isNotEmpty()) add(mutatorPool.removeAt(rng.nextInt(mutatorPool.size)))
                }
            }

            return BiomeRules(
                style = actualStyle,
                algorithm = algorithm,
                fillChance = fill,
                caIterations = rng.nextInt(3, 6),
                birthLimit = 5,
                surviveLimit = if (actualStyle == BiomeStyle.WARREN) 3 else 4,
                verticalBias = when (algorithm) {
                    StructureAlgorithm.VERTICAL -> rng.range(1.8, 2.6)
                    else -> rng.range(0.9, 1.3)
                },
                platformDensity = when (actualStyle) {
                    BiomeStyle.GARDEN -> rng.env(0.5, 0.9)
                    BiomeStyle.SHAFT -> rng.env(0.4, 0.7)
                    BiomeStyle.PIPE_MAZE, BiomeStyle.WAVE_FUNCTION -> 0.0
                    // Rooftop walkways over the street.
                    BiomeStyle.SETTLEMENT -> rng.env(0.35, 0.65)
                    BiomeStyle.FACILITY -> rng.env(0.15, 0.4)
                    BiomeStyle.REMIX -> rng.env(0.2, 0.5)
                    else -> rng.env(0.1, 0.4)
                },
                hazardDensity =
                    if (actualStyle != BiomeStyle.PIPE_MAZE &&
                        actualStyle != BiomeStyle.WAVE_FUNCTION &&
                        BiomeMutator.SPIKE_POCKETS in mutators
                    ) {
                        rng.env(0.2, 0.7)
                    } else {
                        0.0
                    },
                destructibleDensity =
                    if (actualStyle == BiomeStyle.PIPE_MAZE || actualStyle == BiomeStyle.WAVE_FUNCTION) {
                        0.0
                    } else {
                        rng.env(0.15, 0.5)
                    },
                // Constructed biomes repeat the same plating for a machined look.
                textureCohesion =
                    if (actualStyle.isConstructed ||
                        actualStyle == BiomeStyle.PIPE_MAZE ||
                        actualStyle == BiomeStyle.WAVE_FUNCTION
                    ) {
                        rng.range(0.86, 0.97)
                    } else {
                        rng.range(0.74, 0.94)
                    },
                mutators =
                    if (actualStyle == BiomeStyle.PIPE_MAZE || actualStyle == BiomeStyle.WAVE_FUNCTION) {
                        emptySet()
                    } else {
                        mutators
                    },
                mazeBranchDensity = if (actualStyle == BiomeStyle.PIPE_MAZE) rng.env(0.25, 0.75) else 0.45,
                mazeLoopDensity = if (actualStyle == BiomeStyle.PIPE_MAZE) rng.env(0.02, 0.18) else 0.08,
                mazeHubSize = if (actualStyle == BiomeStyle.PIPE_MAZE) rng.env(0.35, 0.8) else 0.55,
                mazeEmptyCenter = actualStyle == BiomeStyle.PIPE_MAZE,
            )
        }
    }
}

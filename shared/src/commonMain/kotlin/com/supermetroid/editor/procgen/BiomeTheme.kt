package com.supermetroid.editor.procgen

import kotlin.random.Random

/**
 * Visual and atmospheric identity for a generated room, orthogonal to the
 * structural [BiomeStyle]: which vanilla tileset the room is dressed and
 * rendered with, an optional palette recolor (for looks beyond the vanilla
 * game), and an optional FX layer (lava/acid/water/spores).
 *
 * Tileset ids index the vanilla table at $8F:E6A2. Only tilesets whose vanilla
 * rooms yield a usable tile vocabulary are offered (verified visually via
 * TilesetPreviewDiagnostic). Each recolor targets a distinct tileset so two
 * themes never fight over the same palette override.
 */
data class BiomeTheme(
    val id: String,
    val displayName: String,
    val blurb: String,
    /** Vanilla tileset id (0-28); null = keep the room's current tileset. */
    val tilesetId: Int?,
    /** [com.supermetroid.editor.rom.PaletteEffects] effect id; null = vanilla colors. */
    val paletteEffectId: String? = null,
    /** FX Layer3Type byte (0x02 lava, 0x04 acid, 0x06 water, 0x08 spores); null = leave FX alone. */
    val fxType: Int? = null,
    /** Liquid surface as a fraction of room height from the top (liquid fxTypes only). */
    val liquidFraction: Double = 1.0,
) {
    val isLiquid: Boolean get() = fxType == FX_LAVA || fxType == FX_ACID || fxType == FX_WATER

    /**
     * Resolve to a concrete theme: [SURPRISE] deterministically picks one from
     * [seed] and sometimes layers a random recolor on top. Others return self.
     */
    fun resolve(seed: Long): BiomeTheme {
        if (this !== SURPRISE) return this
        val rng = Random(seed * 131 + 7)
        val base = CONCRETE[rng.nextInt(CONCRETE.size)]
        if (base.paletteEffectId == null && rng.nextDouble() < 0.3) {
            val effect = SURPRISE_RECOLORS[rng.nextInt(SURPRISE_RECOLORS.size)]
            return base.copy(displayName = "${base.displayName} ($effect)", paletteEffectId = effect)
        }
        return base
    }

    companion object {
        const val FX_LAVA = 0x02
        const val FX_ACID = 0x04
        const val FX_WATER = 0x06
        const val FX_SPORES = 0x08

        val KEEP = BiomeTheme(
            "keep", "Keep current look",
            "Use the room's existing tileset, colors, and FX", null,
        )
        val SURPRISE = BiomeTheme(
            "surprise", "Surprise me",
            "Random biome, sometimes with a recolor the game never shipped", null,
        )

        /** Concrete themes; tileset notes describe the verified rendered look. */
        val CONCRETE: List<BiomeTheme> = listOf(
            BiomeTheme(
                "ship", "Spaceship Interior",
                "Olive hull plating, girders, and mossy machinery", 4,
            ),
            BiomeTheme(
                "base", "Industrial Base",
                "Grey-blue metal plating threaded with pipework", 2,
            ),
            BiomeTheme(
                "cave", "Crystal Cave",
                "Dark cavern rock with moss and pale crystal veins", 0,
            ),
            BiomeTheme(
                "jungle", "Spore Jungle",
                "Pink coral rock overgrown with vines, spores adrift", 6,
                fxType = FX_SPORES,
            ),
            BiomeTheme(
                "brickworks", "Red Brickworks",
                "Crimson brick galleries braced with steel pipes", 7,
            ),
            BiomeTheme(
                "frost", "Frost Cavern",
                "Pale rock refrozen in ice-cold blues — a biome the game never had", 8,
                paletteEffectId = "icecold",
            ),
            BiomeTheme(
                "volcanic", "Volcanic Warrens",
                "Bubbling red rock, hot to the touch", 9,
            ),
            BiomeTheme(
                "magma", "Magma Chamber",
                "Deep red stone above a rising lava pool", 10,
                fxType = FX_LAVA, liquidFraction = 0.85,
            ),
            BiomeTheme(
                "scorched", "Scorched Ruins",
                "Ancient organic rock re-lit in molten oranges", 3,
                paletteEffectId = "lava",
            ),
            BiomeTheme(
                "reef", "Vapor Reef",
                "Coral tunnels in impossible neon pinks", 12,
                paletteEffectId = "vaporwave",
            ),
            BiomeTheme(
                "grotto", "Sunken Grotto",
                "Flooded tube-plant caves in pale sea-green", 13,
                fxType = FX_WATER, liquidFraction = 0.15,
            ),
            BiomeTheme(
                "abyss", "Bioluminescent Abyss",
                "Black water lit by green sparks, fully submerged", 26,
                fxType = FX_WATER, liquidFraction = 0.05,
            ),
            BiomeTheme(
                "bastion", "Flooded Bastion",
                "Blue stone bulwarks, waist-deep in seawater", 23,
                fxType = FX_WATER, liquidFraction = 0.55,
            ),
            BiomeTheme(
                "swamp", "Acid Swamp",
                "Olive masonry sinking into a caustic pool", 24,
                fxType = FX_ACID, liquidFraction = 0.85,
            ),
            BiomeTheme(
                "techno", "Techno Core",
                "White machined corridors studded with warning lights", 14,
            ),
            BiomeTheme(
                "blackout", "Blackout Lab",
                "Near-dark circuitry in deep teal", 16,
            ),
            BiomeTheme(
                "overgrown", "Overgrown Ruins",
                "Mauve stonework reclaimed by olive vegetation", 21,
            ),
            BiomeTheme(
                "temple", "Golden Temple",
                "Warm gilded brick halls", 25,
            ),
            BiomeTheme(
                "nest", "Writhing Nest",
                "Magenta alien growth in the dark", 27,
            ),
            BiomeTheme(
                "sandstone", "Sandstone Halls",
                "Pale layered stone plates", 28,
            ),
        )

        /** Full list for the UI dropdown. */
        val THEMES: List<BiomeTheme> = listOf(KEEP) + CONCRETE + SURPRISE

        /** Recolors safe to layer on any tileset when SURPRISE rolls one. */
        val SURPRISE_RECOLORS: List<String> = listOf(
            "icecold", "golden", "midnight", "vaporwave", "sepia",
            "hue90", "hue180", "hue270", "underwater", "lava",
        )
    }
}

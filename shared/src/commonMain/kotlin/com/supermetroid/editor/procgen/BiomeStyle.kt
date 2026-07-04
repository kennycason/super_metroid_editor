package com.supermetroid.editor.procgen

/**
 * High-level biome archetypes for the generative room builder.
 *
 * Each style defines an *envelope* of rule parameters; the concrete rules for a
 * given seed are rolled by [BiomeRules.roll] inside that envelope, so two rooms
 * generated with the same style still feel related but not identical.
 */
enum class BiomeStyle(val displayName: String, val blurb: String) {
    CAVERN("Cavern", "Open organic caves with rolling floors and stalactite ceilings"),
    WARREN("Warren", "Dense winding tunnels connecting small pockets"),
    RUINS("Ruins", "Rectangular chambers joined by corridors, like a buried structure"),
    SHAFT("Shaft", "Tall vertical climbs with ledges and hanging platforms"),
    GARDEN("Garden", "A large open gallery crossed by floating platforms"),
    SURPRISE("Surprise me", "Randomized style with wilder rule mutations");
}

/**
 * Structural algorithms the generator can use for the solid/air macro layout.
 * Styles map to one of these plus parameter biases.
 */
enum class StructureAlgorithm { CAVE, CHAMBERS, VERTICAL, OPEN_GALLERY }

/**
 * Optional rule "mutators" that add gameplay flavor on top of the base
 * structure. Which ones are active is part of the rolled rule card.
 */
enum class BiomeMutator(val displayName: String) {
    CRUMBLE_BRIDGES("Crumble bridges over gaps"),
    SPIKE_POCKETS("Spike pockets along floors"),
    HIDDEN_PASSAGES("Hidden shot-block passages"),
    BOMB_WALLS("Bombable wall seams"),
    CEILING_FANGS("Stalactite ceiling fangs"),
    RUBBLE_PILES("Loose rubble columns"),
}

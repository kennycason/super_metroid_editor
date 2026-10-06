package com.supermetroid.editor.rom

/**
 * Source-verified animation routes for ordinary enemies whose init AI selects
 * instruction lists through helpers or parameter tables that the generic scanner
 * deliberately does not emulate.
 */
object SourceEnemyAnimations {
    data class Definition(
        val key: String,
        val name: String,
        val speciesId: Int,
        val instructionLists: List<Int>,
        val expectedFramesPerList: List<Int>,
        val loop: Boolean,
        val aliasSpeciesIds: Set<Int> = emptySet(),
    ) {
        init {
            require(instructionLists.isNotEmpty())
            require(instructionLists.size == expectedFramesPerList.size)
            require(expectedFramesPerList.all { it > 0 })
        }
    }

    val DEFINITIONS: List<Definition> = listOf(
        Definition("puyo-ground-fast", "Grounded · fast", 0xCFBF, listOf(0xA299AD), listOf(4), true),
        Definition("puyo-ground-medium", "Grounded · medium", 0xCFBF, listOf(0xA299C1), listOf(4), true),
        Definition("puyo-ground-slow", "Grounded · slow", 0xCFBF, listOf(0xA299D5), listOf(4), true),
        Definition(
            "puyo-hop-right", "Hop poses · right", 0xCFBF,
            listOf(0xA299E9, 0xA299EF, 0xA299F5, 0xA299FB, 0xA29A01),
            listOf(1, 1, 1, 1, 1), false,
        ),
        Definition(
            "puyo-hop-left", "Hop poses · left", 0xCFBF,
            listOf(0xA29A01, 0xA299FB, 0xA299F5, 0xA299EF, 0xA299E9),
            listOf(1, 1, 1, 1, 1), false,
        ),
        // The _0 lists contain one direction-setup handler and fall through to
        // these independently looping visual lists. Rendering begins at _1.
        Definition("owtch-left", "Moving left", 0xD03F, listOf(0xA2A3AD), listOf(3), true),
        Definition("owtch-right", "Moving right", 0xD03F, listOf(0xA2A3BF), listOf(3), true),
        Definition("choot-idle", "Idle", 0xD3BF, listOf(0xA2D82C), listOf(1), false),
        Definition("choot-jumping", "Jumping", 0xD3BF, listOf(0xA2D834), listOf(2), false),
        Definition("choot-falling", "Falling", 0xD3BF, listOf(0xA2D840), listOf(2), false),
        Definition("sbug-up", "Facing up", 0xD87F, listOf(0xA3A071), listOf(4), true, setOf(0xD8BF)),
        Definition("sbug-up-left", "Facing up-left", 0xD87F, listOf(0xA3A085), listOf(4), true, setOf(0xD8BF)),
        Definition("sbug-left", "Facing left", 0xD87F, listOf(0xA3A099), listOf(4), true, setOf(0xD8BF)),
        Definition("sbug-down-left", "Facing down-left", 0xD87F, listOf(0xA3A0AD), listOf(4), true, setOf(0xD8BF)),
        Definition("sbug-down", "Facing down", 0xD87F, listOf(0xA3A0C1), listOf(4), true, setOf(0xD8BF)),
        Definition("sbug-down-right", "Facing down-right", 0xD87F, listOf(0xA3A0D5), listOf(4), true, setOf(0xD8BF)),
        Definition("sbug-right", "Facing right", 0xD87F, listOf(0xA3A0E9), listOf(4), true, setOf(0xD8BF)),
        Definition("sbug-up-right", "Facing up-right", 0xD87F, listOf(0xA3A0FD), listOf(4), true, setOf(0xD8BF)),
    )

    fun forSpecies(speciesId: Int): List<Definition> =
        DEFINITIONS.filter { it.speciesId == speciesId || speciesId in it.aliasSpeciesIds }
}

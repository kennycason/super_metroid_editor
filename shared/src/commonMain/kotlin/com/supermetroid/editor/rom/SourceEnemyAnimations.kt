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
        /** Optional full-character context rendered with this component action. */
        val contextInstructionList: Int? = null,
        val contextExpectedFrames: Int = 0,
        /** True when the context owns earlier OAM slots and therefore wins overlap. */
        val contextOnTop: Boolean = false,
    ) {
        init {
            require(instructionLists.isNotEmpty())
            require(instructionLists.size == expectedFramesPerList.size)
            require(expectedFramesPerList.all { it > 0 })
            require((contextInstructionList == null) == (contextExpectedFrames == 0))
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
        Definition("evir-body-left", "Body · facing left", 0xE63F, listOf(0xA886A7), listOf(6), true),
        Definition("evir-body-right", "Body · facing right", 0xE63F, listOf(0xA8870B), listOf(6), true),
        Definition("evir-arms-left", "Arms · facing left", 0xE63F, listOf(0xA886C3), listOf(17), true),
        Definition("evir-arms-right", "Arms · facing right", 0xE63F, listOf(0xA88727), listOf(17), true),
        Definition("evir-projectile-ready", "Projectile · ready", 0xE63F, listOf(0xA8876F), listOf(1), false),
        Definition("magdollite-idle-left", "Head · idle left", 0xE83F, listOf(0xA8AC9C), listOf(4), true),
        Definition("magdollite-idle-right", "Head · idle right", 0xE83F, listOf(0xA8AD3C), listOf(4), true),
        Definition("magdollite-throw-left", "Hand · throw left", 0xE83F, listOf(0xA8ACB0), listOf(6), false),
        Definition("magdollite-throw-right", "Hand · throw right", 0xE83F, listOf(0xA8AD50), listOf(6), false),
        Definition("magdollite-submerge-left", "Head · submerge left", 0xE83F, listOf(0xA8ACDE), listOf(5), false),
        Definition("magdollite-submerge-right", "Head · submerge right", 0xE83F, listOf(0xA8AD7E), listOf(5), false),
        Definition("magdollite-pillar-rise-left", "Base pillar · left", 0xE83F, listOf(0xA8ACFE), listOf(1), true),
        Definition("magdollite-pillar-rise-right", "Base pillar · right", 0xE83F, listOf(0xA8AD9E), listOf(1), true),
        Definition(
            "magdollite-pillar-growth", "Pillar · growth poses", 0xE83F,
            listOf(0xA8ADE8, 0xA8ADEE, 0xA8ADF4, 0xA8ADFA, 0xA8AE00, 0xA8AE06),
            listOf(1, 1, 1, 1, 1, 1), false,
        ),
        Definition(
            "magdollite-narrow-pillar", "Narrow pillar · left / right", 0xE83F,
            listOf(0xA8ADDC, 0xA8ADE2), listOf(1, 1), false,
        ),
        Definition("magdollite-pillar-cap", "Hand · pillar cap", 0xE83F, listOf(0xA8AE0C), listOf(1), false),
        Definition("beetom-crawl-left", "Crawling · left", 0xE87F, listOf(0xA8B698), listOf(4), true),
        Definition("beetom-crawl-right", "Crawling · right", 0xE87F, listOf(0xA8B6F4), listOf(4), true),
        Definition("beetom-hop-left", "Hopping · left", 0xE87F, listOf(0xA8B6AC), listOf(4), false),
        Definition("beetom-hop-right", "Hopping · right", 0xE87F, listOf(0xA8B708), listOf(4), false),
        Definition("beetom-latch-left", "Latching onto Samus · left", 0xE87F, listOf(0xA8B6CC), listOf(4), false),
        Definition("beetom-drain-left", "Draining Samus · left", 0xE87F, listOf(0xA8B6DE), listOf(4), true),
        Definition("beetom-latch-right", "Latching onto Samus · right", 0xE87F, listOf(0xA8B728), listOf(4), false),
        Definition("beetom-drain-right", "Draining Samus · right", 0xE87F, listOf(0xA8B73A), listOf(4), true),
        Definition(
            "kihunter-idle-left", "Body · idle left", 0xEABF,
            listOf(0xA8E9FA), listOf(3), true, setOf(0xEB3F, 0xEBBF),
        ),
        Definition(
            "kihunter-idle-right", "Body · idle right", 0xEABF,
            listOf(0xA8EA24), listOf(3), true, setOf(0xEB3F, 0xEBBF),
        ),
        Definition(
            "kihunter-swipe-left", "Body · swipe left", 0xEABF,
            listOf(0xA8EA08), listOf(6), false, setOf(0xEB3F, 0xEBBF),
        ),
        Definition(
            "kihunter-swipe-right", "Body · swipe right", 0xEABF,
            listOf(0xA8EA32), listOf(6), false, setOf(0xEB3F, 0xEBBF),
        ),
        Definition(
            "kihunter-hop-left", "Body · hop left", 0xEABF,
            listOf(0xA8EA8A), listOf(6), false, setOf(0xEB3F, 0xEBBF),
        ),
        Definition(
            "kihunter-hop-right", "Body · hop right", 0xEABF,
            listOf(0xA8EAA6), listOf(6), false, setOf(0xEB3F, 0xEBBF),
        ),
        Definition(
            "kihunter-land-left", "Body · land left", 0xEABF,
            listOf(0xA8EAC2), listOf(5), false, setOf(0xEB3F, 0xEBBF),
        ),
        Definition(
            "kihunter-land-right", "Body · land right", 0xEABF,
            listOf(0xA8EADA), listOf(5), false, setOf(0xEB3F, 0xEBBF),
        ),
        Definition(
            "kihunter-acid-left", "Body · fire acid left", 0xEABF,
            listOf(0xA8EAF2), listOf(6), false, setOf(0xEB3F, 0xEBBF),
        ),
        Definition(
            "kihunter-acid-right", "Body · fire acid right", 0xEABF,
            listOf(0xA8EB10), listOf(6), false, setOf(0xEB3F, 0xEBBF),
        ),
        Definition(
            "kihunter-wings-left", "Wings · flapping left", 0xEABF,
            listOf(0xA8EA4E), listOf(3), true, setOf(0xEB3F, 0xEBBF),
            contextInstructionList = 0xA8E9FA,
            contextExpectedFrames = 3,
            contextOnTop = true,
        ),
        Definition(
            "kihunter-wings-right", "Wings · flapping right", 0xEABF,
            listOf(0xA8EA5E), listOf(3), true, setOf(0xEB3F, 0xEBBF),
            contextInstructionList = 0xA8EA24,
            contextExpectedFrames = 3,
            contextOnTop = true,
        ),
        Definition(
            "kihunter-wings-falling", "Wings · falling", 0xEABF,
            listOf(0xA8EA7E), listOf(1), false, setOf(0xEB3F, 0xEBBF),
            contextInstructionList = 0xA8EA24,
            contextExpectedFrames = 1,
            contextOnTop = true,
        ),
        Definition(
            "corpse-sidehopper-hop", "Alive · hopping", 0xED7F,
            listOf(0xA9ECAC), listOf(8), false, setOf(0xEDBF),
        ),
        Definition(
            "corpse-sidehopper-idle", "Alive · idle", 0xED7F,
            listOf(0xA9ECE3), listOf(1), false, setOf(0xEDBF),
        ),
        Definition(
            "corpse-sidehopper-drained", "Drained corpse", 0xED7F,
            listOf(0xA9ECE9), listOf(1), false, setOf(0xEDBF),
        ),
        Definition(
            "corpse-sidehopper-dead", "Dead", 0xED7F,
            listOf(0xA9ECEF), listOf(1), false, setOf(0xEDBF),
        ),
    )

    fun forSpecies(speciesId: Int): List<Definition> =
        DEFINITIONS.filter { it.speciesId == speciesId || speciesId in it.aliasSpeciesIds }
}

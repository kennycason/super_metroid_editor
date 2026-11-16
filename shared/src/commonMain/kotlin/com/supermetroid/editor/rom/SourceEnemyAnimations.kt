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
        Definition("boyon-idle", "Idle", 0xCEBF, listOf(0xA286AB), listOf(4), true),
        Definition("boyon-bounce", "Bouncing", 0xCEBF, listOf(0xA286C3), listOf(6), true),
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
        Definition("holtz-idle", "Body · idle", 0xD33F, listOf(0xA2C61C), listOf(4), true),
        Definition("holtz-prepare", "Body · prepare to swoop", 0xD33F, listOf(0xA2C630), listOf(9), false),
        Definition("holtz-descend", "Body · descending", 0xD33F, listOf(0xA2C65A), listOf(1), false),
        Definition("holtz-ascend", "Body · ascending", 0xD33F, listOf(0xA2C662), listOf(3), false),
        Definition("holtz-flight", "Body · flight", 0xD33F, listOf(0xA2C676), listOf(3), true),
        Definition("holtz-cooldown", "Body · landing", 0xD33F, listOf(0xA2C686), listOf(9), false),
        Definition(
            "holtz-flames", "Flame companion · with body", 0xD33F,
            listOf(0xA2C6B0), listOf(3), true,
            contextInstructionList = 0xA2C61C,
            contextExpectedFrames = 4,
            contextOnTop = true,
        ),
        Definition(
            "dragon-idle-left", "Idle · facing left", 0xD4BF,
            listOf(0xA2E59B), listOf(1), false,
            contextInstructionList = 0xA2E5A1,
            contextExpectedFrames = 2,
        ),
        Definition(
            "dragon-idle-right", "Idle · facing right", 0xD4BF,
            listOf(0xA2E5AD), listOf(1), false,
            contextInstructionList = 0xA2E5B3,
            contextExpectedFrames = 2,
        ),
        Definition(
            "dragon-attack-left", "Attack · facing left", 0xD4BF,
            listOf(0xA2E5BF), listOf(5), false,
            contextInstructionList = 0xA2E5A1,
            contextExpectedFrames = 2,
        ),
        Definition(
            "dragon-attack-right", "Attack · facing right", 0xD4BF,
            listOf(0xA2E5D7), listOf(5), false,
            contextInstructionList = 0xA2E5B3,
            contextExpectedFrames = 2,
        ),
        Definition(
            "dragon-wings-left", "Wings · facing left", 0xD4BF,
            listOf(0xA2E5A1), listOf(2), true,
            contextInstructionList = 0xA2E59B,
            contextExpectedFrames = 1,
            contextOnTop = true,
        ),
        Definition(
            "dragon-wings-right", "Wings · facing right", 0xD4BF,
            listOf(0xA2E5B3), listOf(2), true,
            contextInstructionList = 0xA2E5AD,
            contextExpectedFrames = 1,
            contextOnTop = true,
        ),
        Definition("mochtroid-free", "Floating", 0xD8FF, listOf(0xA3A745), listOf(4), true),
        Definition("mochtroid-latched", "Touching Samus", 0xD8FF, listOf(0xA3A759), listOf(4), true),
        Definition("sbug-up", "Facing up", 0xD87F, listOf(0xA3A071), listOf(4), true, setOf(0xD8BF)),
        Definition("sbug-up-left", "Facing up-left", 0xD87F, listOf(0xA3A085), listOf(4), true, setOf(0xD8BF)),
        Definition("sbug-left", "Facing left", 0xD87F, listOf(0xA3A099), listOf(4), true, setOf(0xD8BF)),
        Definition("sbug-down-left", "Facing down-left", 0xD87F, listOf(0xA3A0AD), listOf(4), true, setOf(0xD8BF)),
        Definition("sbug-down", "Facing down", 0xD87F, listOf(0xA3A0C1), listOf(4), true, setOf(0xD8BF)),
        Definition("sbug-down-right", "Facing down-right", 0xD87F, listOf(0xA3A0D5), listOf(4), true, setOf(0xD8BF)),
        Definition("sbug-right", "Facing right", 0xD87F, listOf(0xA3A0E9), listOf(4), true, setOf(0xD8BF)),
        Definition("sbug-up-right", "Facing up-right", 0xD87F, listOf(0xA3A0FD), listOf(4), true, setOf(0xD8BF)),
        Definition("sidehopper-landed-up", "Landed · floor", 0xD93F, listOf(0xA3AA82), listOf(4), true),
        Definition("sidehopper-hopping-up", "Hopping · floor", 0xD93F, listOf(0xA3AA76), listOf(1), false),
        Definition("sidehopper-landed-down", "Landed · ceiling", 0xD93F, listOf(0xA3AAA8), listOf(4), true),
        Definition("sidehopper-hopping-down", "Hopping · ceiling", 0xD93F, listOf(0xA3AA9C), listOf(1), false),
        Definition("dessgeega-landed-up", "Landed · floor", 0xD97F, listOf(0xA3AFAD), listOf(4), true),
        Definition("dessgeega-hopping-up", "Hopping · floor", 0xD97F, listOf(0xA3AFA5), listOf(1), false),
        Definition("dessgeega-landed-down", "Landed · ceiling", 0xD97F, listOf(0xA3AFCB), listOf(4), true),
        Definition("dessgeega-hopping-down", "Hopping · ceiling", 0xD97F, listOf(0xA3AFC3), listOf(1), false),
        Definition(
            "sidehopper-large-landed-up", "Landed · floor", 0xD9BF,
            listOf(0xA3B0D1), listOf(4), true, setOf(0xD9FF),
        ),
        Definition(
            "sidehopper-large-hopping-up", "Hopping · floor", 0xD9BF,
            listOf(0xA3B0C5), listOf(1), false, setOf(0xD9FF),
        ),
        Definition(
            "sidehopper-large-landed-down", "Landed · ceiling", 0xD9BF,
            listOf(0xA3B0F7), listOf(4), true, setOf(0xD9FF),
        ),
        Definition(
            "sidehopper-large-hopping-down", "Hopping · ceiling", 0xD9BF,
            listOf(0xA3B0EB), listOf(1), false, setOf(0xD9FF),
        ),
        Definition("dessgeega-large-landed-up", "Landed · floor", 0xDA3F, listOf(0xA3B23F), listOf(4), true),
        Definition("dessgeega-large-hopping-up", "Hopping · floor", 0xDA3F, listOf(0xA3B237), listOf(1), false),
        Definition("dessgeega-large-landed-down", "Landed · ceiling", 0xDA3F, listOf(0xA3B25D), listOf(4), true),
        Definition("dessgeega-large-hopping-down", "Hopping · ceiling", 0xDA3F, listOf(0xA3B255), listOf(1), false),
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
        Definition("zebetite-big-full", "Large · full health", 0xE27F, listOf(0xA6FDCC), listOf(1), false),
        Definition("zebetite-big-800", "Large · below 800 HP", 0xE27F, listOf(0xA6FDD2), listOf(1), false),
        Definition("zebetite-big-600", "Large · below 600 HP", 0xE27F, listOf(0xA6FDD8), listOf(1), false),
        Definition("zebetite-big-400", "Large · below 400 HP", 0xE27F, listOf(0xA6FDDE), listOf(1), false),
        Definition("zebetite-big-200", "Large · below 200 HP", 0xE27F, listOf(0xA6FDE4), listOf(1), false),
        Definition("zebetite-small-full", "Small pair · full health", 0xE27F, listOf(0xA6FDEA), listOf(1), false),
        Definition("zebetite-small-800", "Small pair · below 800 HP", 0xE27F, listOf(0xA6FDF0), listOf(1), false),
        Definition("zebetite-small-600", "Small pair · below 600 HP", 0xE27F, listOf(0xA6FDF6), listOf(1), false),
        Definition("zebetite-small-400", "Small pair · below 400 HP", 0xE27F, listOf(0xA6FDFC), listOf(1), false),
        Definition("zebetite-small-200", "Small pair · below 200 HP", 0xE27F, listOf(0xA6FE02), listOf(1), false),
        Definition("robot-walk-left", "Walking · facing left", 0xE8FF, listOf(0xA8C6ED), listOf(15), true),
        Definition("robot-walk-right", "Walking · facing right", 0xE8FF, listOf(0xA8C931), listOf(15), true),
        Definition("robot-no-power-neutral", "Deactivated · neutral", 0xE93F, listOf(0xA8C6D3), listOf(1), false),
        Definition("robot-no-power-left", "Deactivated · leaning left", 0xE93F, listOf(0xA8C6D9), listOf(1), false),
        Definition("robot-no-power-right", "Deactivated · leaning right", 0xE93F, listOf(0xA8C6DF), listOf(1), false),
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

package com.supermetroid.editor.headless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.math.roundToInt

class SmeditPatchRandomizerTest {
    @Test
    fun `randomization is deterministic for a supplied seed`() {
        val randomization = SmeditRandomizationRequest(
            seed = 1234L,
            beamDamage = SmeditBeamDamageRandomization(enabled = true),
            enemyStats = SmeditEnemyStatsRandomization(enabled = true),
        )

        val first = SmeditPatchRandomizer.apply(SmeditBuildRequest(), randomization)
        val second = SmeditPatchRandomizer.apply(SmeditBuildRequest(), randomization)

        assertEquals(first, second)
        assertEquals(1234L, first.report?.seed)
        assertTrue(first.build.patches.containsKey(BEAM_DAMAGE_CONFIG_TYPE))
        assertTrue(first.build.patches.containsKey(ENEMY_STATS_CONFIG_TYPE))
    }

    @Test
    fun `direct patch config overrides randomized values`() {
        val result = SmeditPatchRandomizer.apply(
            build = SmeditBuildRequest(
                patches = mapOf(
                    BEAM_DAMAGE_CONFIG_TYPE to SmeditPatchRequest(
                        config = mapOf("power" to 777)
                    )
                )
            ),
            randomization = SmeditRandomizationRequest(
                seed = 7L,
                beamDamage = SmeditBeamDamageRandomization(enabled = true),
            ),
        )

        val beamConfig = result.build.patches.getValue(BEAM_DAMAGE_CONFIG_TYPE).config
        assertEquals(777, beamConfig["power"])
        assertTrue(beamConfig.keys.containsAll(HEADLESS_BEAMS.map { it.key }))
    }

    @Test
    fun `enemy drop randomization emits byte weights totaling requested amount per enemy`() {
        val result = SmeditPatchRandomizer.apply(
            build = SmeditBuildRequest(),
            randomization = SmeditRandomizationRequest(
                seed = 99L,
                enemyDrops = SmeditEnemyDropsRandomization(enabled = true, total = 255),
            ),
        )
        val config = result.build.patches.getValue(ENEMY_DROPS_CONFIG_TYPE).config

        for (enemy in HEADLESS_ENEMY_DEFS) {
            val values = HEADLESS_DROP_SLOTS.map { slot -> config.getValue("${enemy.key}_drop${slot.index}") }
            assertEquals(255, values.sum(), "drop total for ${enemy.key}")
            assertTrue(values.all { it in 0..255 })
        }
    }

    @Test
    fun `enemy vulnerability randomization keeps valid values and avoids fully immune enemies by default`() {
        val result = SmeditPatchRandomizer.apply(
            build = SmeditBuildRequest(),
            randomization = SmeditRandomizationRequest(
                seed = 42L,
                enemyVulnerabilities = SmeditEnemyVulnerabilityRandomization(
                    enabled = true,
                    noEffectChance = 1.0,
                    multipliers = listOf(2, 4),
                ),
            ),
        )
        val config = result.build.patches.getValue(ENEMY_VULN_CONFIG_TYPE).config

        for (enemy in HEADLESS_ENEMY_DEFS) {
            val values = HEADLESS_WEAPON_SLOTS.map { weapon -> config.getValue("${enemy.key}_vuln${weapon.index}") }
            assertTrue(values.all { it in setOf(0, 2, 4) })
            assertTrue(values.any { it != 0 }, "at least one effective weapon for ${enemy.key}")
        }
    }

    @Test
    fun `enemy stats randomization respects percentage bounds`() {
        val result = SmeditPatchRandomizer.apply(
            build = SmeditBuildRequest(),
            randomization = SmeditRandomizationRequest(
                seed = 11L,
                enemyStats = SmeditEnemyStatsRandomization(
                    enabled = true,
                    randomizeContactDamage = false,
                    enemyHpMin = 0.5,
                    enemyHpMax = 3.5,
                ),
            ),
        )
        val config = result.build.patches.getValue(ENEMY_STATS_CONFIG_TYPE).config

        for (enemy in HEADLESS_ENEMY_DEFS) {
            val hp = config.getValue("${enemy.key}_hp")
            val min = (enemy.defaultHp * 0.5).roundToInt().coerceAtLeast(1)
            val max = (enemy.defaultHp * 3.5).roundToInt().coerceAtLeast(1)
            assertTrue(hp in min..max, "${enemy.key} hp $hp should be in $min..$max")
            assertTrue("${enemy.key}_dmg" !in config)
        }
    }

    @Test
    fun `preset enables a complete randomization profile`() {
        val result = SmeditPatchRandomizer.apply(
            build = SmeditBuildRequest(),
            randomization = SmeditRandomizationRequest(
                seed = 123L,
                preset = "spicy",
            ),
        )

        assertEquals("spicy", result.report?.preset)
        assertTrue(result.build.patches.containsKey(BEAM_DAMAGE_CONFIG_TYPE))
        assertTrue(result.build.patches.containsKey(ENEMY_STATS_CONFIG_TYPE))
        assertTrue(result.build.patches.containsKey(ENEMY_DROPS_CONFIG_TYPE))
        assertTrue(result.build.patches.containsKey(ENEMY_VULN_CONFIG_TYPE))
        assertTrue(result.report?.randomizedFieldCounts?.get(ENEMY_VULN_CONFIG_TYPE).orZero() > 0)
    }

    @Test
    fun `beam filters randomize only selected beams`() {
        val result = SmeditPatchRandomizer.apply(
            build = SmeditBuildRequest(),
            randomization = SmeditRandomizationRequest(
                seed = 5L,
                beamDamage = SmeditBeamDamageRandomization(
                    enabled = true,
                    includeBeams = listOf("power", "plasma"),
                    excludeBeams = listOf("plasma"),
                ),
            ),
        )
        val config = result.build.patches.getValue(BEAM_DAMAGE_CONFIG_TYPE).config

        assertEquals(setOf("power"), config.keys)
    }

    @Test
    fun `enemy filters apply to enemy randomizers`() {
        val result = SmeditPatchRandomizer.apply(
            build = SmeditBuildRequest(),
            randomization = SmeditRandomizationRequest(
                seed = 6L,
                includeEnemyCategories = listOf("Pirate"),
                enemyStats = SmeditEnemyStatsRandomization(
                    enabled = true,
                    randomizeContactDamage = false,
                    excludeEnemies = listOf("pirate_basic"),
                ),
            ),
        )
        val config = result.build.patches.getValue(ENEMY_STATS_CONFIG_TYPE).config

        assertTrue(config.keys.all { it.startsWith("pirate_") && it.endsWith("_hp") })
        assertTrue("pirate_basic_hp" !in config)
        assertTrue("pirate_tourian_hp" in config)
    }

    @Test
    fun `enemy drop safety knobs bound nothing and nonzero slots`() {
        val result = SmeditPatchRandomizer.apply(
            build = SmeditBuildRequest(),
            randomization = SmeditRandomizationRequest(
                seed = 71L,
                enemyDrops = SmeditEnemyDropsRandomization(
                    enabled = true,
                    total = 255,
                    nothingWeight = 20.0,
                    minNonZeroSlots = 4,
                    minNothing = 16,
                    maxNothing = 128,
                ),
            ),
        )
        val config = result.build.patches.getValue(ENEMY_DROPS_CONFIG_TYPE).config

        for (enemy in HEADLESS_ENEMY_DEFS) {
            val values = HEADLESS_DROP_SLOTS.map { slot -> config.getValue("${enemy.key}_drop${slot.index}") }
            assertEquals(255, values.sum(), "drop total for ${enemy.key}")
            assertTrue(values.count { it > 0 } >= 4, "nonzero drop slots for ${enemy.key}")
            assertTrue(values[3] in 16..128, "nothing drop slot for ${enemy.key}")
        }
    }

    @Test
    fun `enemy vulnerability safety knobs force required and minimum effective weapons`() {
        val result = SmeditPatchRandomizer.apply(
            build = SmeditBuildRequest(),
            randomization = SmeditRandomizationRequest(
                seed = 19L,
                enemyVulnerabilities = SmeditEnemyVulnerabilityRandomization(
                    enabled = true,
                    noEffectChance = 1.0,
                    multipliers = listOf(2),
                    minEffectiveWeaponsPerEnemy = 3,
                    requiredEffectiveWeaponSlots = listOf(9, 21),
                    includeEnemies = listOf("zoomer"),
                ),
            ),
        )
        val config = result.build.patches.getValue(ENEMY_VULN_CONFIG_TYPE).config
        val zoomerValues = HEADLESS_WEAPON_SLOTS.map { weapon -> config.getValue("zoomer_vuln${weapon.index}") }

        assertEquals(HEADLESS_WEAPON_SLOTS.size, config.size)
        assertEquals(2, config["zoomer_vuln9"])
        assertEquals(2, config["zoomer_vuln21"])
        assertTrue(zoomerValues.count { it != 0 } >= 3)
    }

    @Test
    fun `randomizer rejects unknown filter values`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SmeditPatchRandomizer.apply(
                build = SmeditBuildRequest(),
                randomization = SmeditRandomizationRequest(
                    beamDamage = SmeditBeamDamageRandomization(
                        enabled = true,
                        includeBeams = listOf("hyper"),
                    ),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("includeBeams"))
    }

    private fun Int?.orZero(): Int = this ?: 0
}

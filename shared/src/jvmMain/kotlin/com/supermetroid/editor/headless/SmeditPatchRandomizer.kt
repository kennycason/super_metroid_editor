package com.supermetroid.editor.headless

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

@Serializable
data class SmeditRandomizationRequest(
    val seed: Long? = null,
    val preset: String? = null,
    val includeBeams: List<String> = emptyList(),
    val excludeBeams: List<String> = emptyList(),
    val includeEnemies: List<String> = emptyList(),
    val excludeEnemies: List<String> = emptyList(),
    val includeEnemyCategories: List<String> = emptyList(),
    val excludeEnemyCategories: List<String> = emptyList(),
    val beamDamage: SmeditBeamDamageRandomization = SmeditBeamDamageRandomization(),
    val enemyStats: SmeditEnemyStatsRandomization = SmeditEnemyStatsRandomization(),
    val enemyDrops: SmeditEnemyDropsRandomization = SmeditEnemyDropsRandomization(),
    val enemyVulnerabilities: SmeditEnemyVulnerabilityRandomization = SmeditEnemyVulnerabilityRandomization(),
) {
    fun hasEnabledRandomizers(): Boolean =
        !preset.isNullOrBlank() || beamDamage.enabled || enemyStats.enabled || enemyDrops.enabled || enemyVulnerabilities.enabled
}

@Serializable
data class SmeditBeamDamageRandomization(
    val enabled: Boolean = false,
    val damageMin: Double = 0.5,
    val damageMax: Double = 2.0,
    val minDamage: Int = 1,
    val maxDamage: Int = 0xFFFF,
    val includeBeams: List<String> = emptyList(),
    val excludeBeams: List<String> = emptyList(),
)

@Serializable
data class SmeditEnemyStatsRandomization(
    val enabled: Boolean = false,
    val randomizeHp: Boolean = true,
    val randomizeContactDamage: Boolean = true,
    val enemyHpMin: Double = 0.5,
    val enemyHpMax: Double = 3.5,
    val enemyDamageMin: Double = 0.5,
    val enemyDamageMax: Double = 2.0,
    val minHp: Int = 1,
    val maxHp: Int = 0xFFFF,
    val minDamage: Int = 0,
    val maxDamage: Int = 0xFFFF,
    val preserveOneHpEnemies: Boolean = true,
    val preserveZeroDamageEnemies: Boolean = true,
    val includeEnemies: List<String> = emptyList(),
    val excludeEnemies: List<String> = emptyList(),
    val includeEnemyCategories: List<String> = emptyList(),
    val excludeEnemyCategories: List<String> = emptyList(),
)

@Serializable
data class SmeditEnemyDropsRandomization(
    val enabled: Boolean = false,
    val total: Int = 255,
    val smallEnergyWeight: Double = 2.0,
    val largeEnergyWeight: Double = 1.0,
    val missileWeight: Double = 2.0,
    val nothingWeight: Double = 4.0,
    val superMissileWeight: Double = 0.6,
    val powerBombWeight: Double = 0.4,
    val minNonZeroSlots: Int = 1,
    val minNothing: Int = 0,
    val maxNothing: Int = 255,
    val includeEnemies: List<String> = emptyList(),
    val excludeEnemies: List<String> = emptyList(),
    val includeEnemyCategories: List<String> = emptyList(),
    val excludeEnemyCategories: List<String> = emptyList(),
)

@Serializable
data class SmeditEnemyVulnerabilityRandomization(
    val enabled: Boolean = false,
    val noEffectChance: Double = 0.15,
    val multipliers: List<Int> = listOf(1, 2, 4, 8),
    val ensureAtLeastOneEffectivePerEnemy: Boolean = true,
    val minEffectiveWeaponsPerEnemy: Int = 0,
    val requiredEffectiveWeaponSlots: List<Int> = emptyList(),
    val includeEnemies: List<String> = emptyList(),
    val excludeEnemies: List<String> = emptyList(),
    val includeEnemyCategories: List<String> = emptyList(),
    val excludeEnemyCategories: List<String> = emptyList(),
)

@Serializable
data class SmeditRandomizationReport(
    val seed: Long,
    val preset: String? = null,
    val randomizedConfigTypes: List<String>,
    val randomizedFieldCounts: Map<String, Int> = emptyMap(),
)

@Serializable
data class SmeditRandomizedBuildRequest(
    val build: SmeditBuildRequest,
    val report: SmeditRandomizationReport?,
)

object SmeditPatchRandomizer {
    val availablePresets: List<String> = listOf("balanced", "spicy", "chaos", "survival")

    fun apply(
        build: SmeditBuildRequest,
        randomization: SmeditRandomizationRequest,
    ): SmeditRandomizedBuildRequest {
        val resolvedRandomization = randomization.withPresetApplied()
        if (!resolvedRandomization.hasEnabledRandomizers()) {
            return SmeditRandomizedBuildRequest(build = build, report = null)
        }

        validate(resolvedRandomization)
        val seed = resolvedRandomization.seed ?: Random.Default.nextLong()
        val random = Random(seed)
        val patches = linkedMapOf<String, SmeditPatchRequest>()
        val randomizedConfigTypes = mutableListOf<String>()
        val randomizedFieldCounts = linkedMapOf<String, Int>()

        if (resolvedRandomization.beamDamage.enabled) {
            val config = randomizeBeamDamage(resolvedRandomization.beamDamage, resolvedRandomization, random)
            patches.mergeGeneratedConfig(
                configType = BEAM_DAMAGE_CONFIG_TYPE,
                config = config,
            )
            randomizedConfigTypes.add(BEAM_DAMAGE_CONFIG_TYPE)
            randomizedFieldCounts[BEAM_DAMAGE_CONFIG_TYPE] = config.size
        }

        if (resolvedRandomization.enemyStats.enabled) {
            val config = randomizeEnemyStats(resolvedRandomization.enemyStats, resolvedRandomization, random)
            patches.mergeGeneratedConfig(
                configType = ENEMY_STATS_CONFIG_TYPE,
                config = config,
            )
            randomizedConfigTypes.add(ENEMY_STATS_CONFIG_TYPE)
            randomizedFieldCounts[ENEMY_STATS_CONFIG_TYPE] = config.size
        }

        if (resolvedRandomization.enemyDrops.enabled) {
            val config = randomizeEnemyDrops(resolvedRandomization.enemyDrops, resolvedRandomization, random)
            patches.mergeGeneratedConfig(
                configType = ENEMY_DROPS_CONFIG_TYPE,
                config = config,
            )
            randomizedConfigTypes.add(ENEMY_DROPS_CONFIG_TYPE)
            randomizedFieldCounts[ENEMY_DROPS_CONFIG_TYPE] = config.size
        }

        if (resolvedRandomization.enemyVulnerabilities.enabled) {
            val config = randomizeEnemyVulnerabilities(resolvedRandomization.enemyVulnerabilities, resolvedRandomization, random)
            patches.mergeGeneratedConfig(
                configType = ENEMY_VULN_CONFIG_TYPE,
                config = config,
            )
            randomizedConfigTypes.add(ENEMY_VULN_CONFIG_TYPE)
            randomizedFieldCounts[ENEMY_VULN_CONFIG_TYPE] = config.size
        }

        for ((key, request) in build.patches) {
            val configType = SmeditPatchCatalog.configSchema(key)?.configType
            if (configType != null && configType in patches) {
                patches[configType] = patches.getValue(configType).mergeDirectOverride(request)
            } else {
                patches[key] = request
            }
        }

        return SmeditRandomizedBuildRequest(
            build = build.copy(patches = patches),
            report = SmeditRandomizationReport(
                seed = seed,
                preset = resolvedRandomization.preset,
                randomizedConfigTypes = randomizedConfigTypes,
                randomizedFieldCounts = randomizedFieldCounts,
            ),
        )
    }

    private fun validate(randomization: SmeditRandomizationRequest) {
        randomization.preset?.takeIf { it.isNotBlank() }?.let { preset ->
            require(preset.normalizedPresetKey() in availablePresets) {
                "Unknown randomization preset '$preset'. Available presets: ${availablePresets.joinToString(", ")}."
            }
        }
        validateBeamFilters("randomize", randomization.includeBeams, randomization.excludeBeams)
        validateEnemyFilters(
            scope = "randomize",
            includeEnemies = randomization.includeEnemies,
            excludeEnemies = randomization.excludeEnemies,
            includeEnemyCategories = randomization.includeEnemyCategories,
            excludeEnemyCategories = randomization.excludeEnemyCategories,
        )

        randomization.beamDamage.takeIf { it.enabled }?.let { config ->
            require(config.damageMin >= 0.0 && config.damageMax >= config.damageMin) {
                "beamDamage damageMin/damageMax must be non-negative and ordered."
            }
            require(config.minDamage in 0..0xFFFF && config.maxDamage in 0..0xFFFF && config.maxDamage >= config.minDamage) {
                "beamDamage minDamage/maxDamage must be within 0..65535 and ordered."
            }
            validateBeamFilters("beamDamage", config.includeBeams, config.excludeBeams)
            require(selectBeams(randomization, config).isNotEmpty()) {
                "beamDamage filters selected no beams."
            }
        }

        randomization.enemyStats.takeIf { it.enabled }?.let { config ->
            require(config.randomizeHp || config.randomizeContactDamage) {
                "enemyStats must randomize at least HP or contact damage."
            }
            require(config.enemyHpMin >= 0.0 && config.enemyHpMax >= config.enemyHpMin) {
                "enemyStats enemyHpMin/enemyHpMax must be non-negative and ordered."
            }
            require(config.enemyDamageMin >= 0.0 && config.enemyDamageMax >= config.enemyDamageMin) {
                "enemyStats enemyDamageMin/enemyDamageMax must be non-negative and ordered."
            }
            require(config.minHp in 0..0xFFFF && config.maxHp in 0..0xFFFF && config.maxHp >= config.minHp) {
                "enemyStats minHp/maxHp must be within 0..65535 and ordered."
            }
            require(config.minDamage in 0..0xFFFF && config.maxDamage in 0..0xFFFF && config.maxDamage >= config.minDamage) {
                "enemyStats minDamage/maxDamage must be within 0..65535 and ordered."
            }
            validateEnemyFilters(
                scope = "enemyStats",
                includeEnemies = config.includeEnemies,
                excludeEnemies = config.excludeEnemies,
                includeEnemyCategories = config.includeEnemyCategories,
                excludeEnemyCategories = config.excludeEnemyCategories,
            )
            require(selectEnemies("enemyStats", randomization, config).isNotEmpty()) {
                "enemyStats filters selected no enemies."
            }
        }

        randomization.enemyDrops.takeIf { it.enabled }?.let { config ->
            require(config.total in 1..255) {
                "enemyDrops total must be within 1..255."
            }
            require(config.minNonZeroSlots in 1..HEADLESS_DROP_SLOTS.size) {
                "enemyDrops minNonZeroSlots must be within 1..${HEADLESS_DROP_SLOTS.size}."
            }
            require(config.minNonZeroSlots <= config.total) {
                "enemyDrops minNonZeroSlots cannot exceed total."
            }
            require(config.minNothing in 0..config.total && config.maxNothing in config.minNothing..config.total) {
                "enemyDrops minNothing/maxNothing must be within 0..total and ordered."
            }
            val weights = config.weights()
            require(weights.all { it >= 0.0 && it.isFinite() }) {
                "enemyDrops weights must be finite non-negative numbers."
            }
            require(weights.any { it > 0.0 }) {
                "enemyDrops must have at least one positive slot weight."
            }
            val positiveSlots = weights.withIndex().count { (index, weight) ->
                weight > 0.0 && (index != NOTHING_DROP_POSITION || config.maxNothing > 0)
            }
            require(config.minNonZeroSlots <= positiveSlots) {
                "enemyDrops minNonZeroSlots cannot exceed the number of positive slot weights."
            }
            require(config.maxNothing == config.total || weights.withIndex().any { (index, weight) ->
                index != NOTHING_DROP_POSITION && weight > 0.0
            }) {
                "enemyDrops maxNothing below total requires at least one positive non-nothing weight."
            }
            validateEnemyFilters(
                scope = "enemyDrops",
                includeEnemies = config.includeEnemies,
                excludeEnemies = config.excludeEnemies,
                includeEnemyCategories = config.includeEnemyCategories,
                excludeEnemyCategories = config.excludeEnemyCategories,
            )
            require(selectEnemies("enemyDrops", randomization, config).isNotEmpty()) {
                "enemyDrops filters selected no enemies."
            }
        }

        randomization.enemyVulnerabilities.takeIf { it.enabled }?.let { config ->
            require(config.noEffectChance in 0.0..1.0) {
                "enemyVulnerabilities noEffectChance must be within 0.0..1.0."
            }
            require(config.multipliers.isNotEmpty()) {
                "enemyVulnerabilities multipliers must not be empty."
            }
            require(config.multipliers.all { it in 1..255 }) {
                "enemyVulnerabilities multipliers must be within 1..255. Use noEffectChance for 0/no effect."
            }
            require(config.minEffectiveWeaponsPerEnemy in 0..HEADLESS_WEAPON_SLOTS.size) {
                "enemyVulnerabilities minEffectiveWeaponsPerEnemy must be within 0..${HEADLESS_WEAPON_SLOTS.size}."
            }
            val knownWeaponSlots = HEADLESS_WEAPON_SLOTS.map { it.index }.toSet()
            val unknownSlots = config.requiredEffectiveWeaponSlots.filterNot { it in knownWeaponSlots }
            require(unknownSlots.isEmpty()) {
                "enemyVulnerabilities requiredEffectiveWeaponSlots contains unknown weapon slot(s): ${unknownSlots.joinToString(", ")}."
            }
            validateEnemyFilters(
                scope = "enemyVulnerabilities",
                includeEnemies = config.includeEnemies,
                excludeEnemies = config.excludeEnemies,
                includeEnemyCategories = config.includeEnemyCategories,
                excludeEnemyCategories = config.excludeEnemyCategories,
            )
            require(selectEnemies("enemyVulnerabilities", randomization, config).isNotEmpty()) {
                "enemyVulnerabilities filters selected no enemies."
            }
        }
    }

    private fun randomizeBeamDamage(
        config: SmeditBeamDamageRandomization,
        randomization: SmeditRandomizationRequest,
        random: Random,
    ): Map<String, Int> =
        selectBeams(randomization, config).associate { beam ->
            beam.key to scaledInt(
                defaultValue = beam.defaultDamage,
                minRate = config.damageMin,
                maxRate = config.damageMax,
                minValue = config.minDamage,
                maxValue = config.maxDamage,
                random = random,
            )
        }

    private fun randomizeEnemyStats(
        config: SmeditEnemyStatsRandomization,
        randomization: SmeditRandomizationRequest,
        random: Random,
    ): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        for (enemy in selectEnemies("enemyStats", randomization, config)) {
            if (config.randomizeHp) {
                result["${enemy.key}_hp"] =
                    if (config.preserveOneHpEnemies && enemy.defaultHp <= 1) {
                        enemy.defaultHp
                    } else {
                        scaledInt(
                            defaultValue = enemy.defaultHp,
                            minRate = config.enemyHpMin,
                            maxRate = config.enemyHpMax,
                            minValue = config.minHp,
                            maxValue = config.maxHp,
                            random = random,
                        )
                    }
            }
            if (config.randomizeContactDamage) {
                result["${enemy.key}_dmg"] =
                    if (config.preserveZeroDamageEnemies && enemy.defaultDamage == 0) {
                        enemy.defaultDamage
                    } else {
                        scaledInt(
                            defaultValue = enemy.defaultDamage,
                            minRate = config.enemyDamageMin,
                            maxRate = config.enemyDamageMax,
                            minValue = config.minDamage,
                            maxValue = config.maxDamage,
                            random = random,
                        )
                    }
            }
        }
        return result
    }

    private fun randomizeEnemyDrops(
        config: SmeditEnemyDropsRandomization,
        randomization: SmeditRandomizationRequest,
        random: Random,
    ): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        val weights = config.weights()
        for (enemy in selectEnemies("enemyDrops", randomization, config)) {
            val slots = safeDropDistribution(config, weights, random)
            for ((index, value) in slots.withIndex()) {
                result["${enemy.key}_drop$index"] = value
            }
        }
        return result
    }

    private fun randomizeEnemyVulnerabilities(
        config: SmeditEnemyVulnerabilityRandomization,
        randomization: SmeditRandomizationRequest,
        random: Random,
    ): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        val requiredPositions = config.requiredEffectiveWeaponSlots.map { requiredSlot ->
            HEADLESS_WEAPON_SLOTS.indexOfFirst { it.index == requiredSlot }
        }
        val minEffective = maxOf(
            config.minEffectiveWeaponsPerEnemy,
            requiredPositions.size,
            if (config.ensureAtLeastOneEffectivePerEnemy) 1 else 0,
        )

        for (enemy in selectEnemies("enemyVulnerabilities", randomization, config)) {
            val values = HEADLESS_WEAPON_SLOTS.map {
                if (random.nextDouble() < config.noEffectChance) {
                    0
                } else {
                    config.multipliers[random.nextInt(config.multipliers.size)]
                }
            }.toMutableList()

            for (position in requiredPositions) {
                values[position] = config.randomMultiplier(random)
            }

            while (values.count { it != 0 } < minEffective) {
                val positions = values.indices.filter { values[it] == 0 }
                if (positions.isEmpty()) break
                values[positions[random.nextInt(positions.size)]] = config.randomMultiplier(random)
            }

            for ((slotPosition, weapon) in HEADLESS_WEAPON_SLOTS.withIndex()) {
                result["${enemy.key}_vuln${weapon.index}"] = values[slotPosition]
            }
        }
        return result
    }

    private fun SmeditRandomizationRequest.withPresetApplied(): SmeditRandomizationRequest {
        val presetKey = preset?.normalizedPresetKey()?.takeIf { it.isNotEmpty() } ?: return this.copy(preset = null)
        val presetRequest = randomizerPreset(presetKey)
        return presetRequest.copy(
            seed = seed,
            preset = presetKey,
            includeBeams = includeBeams,
            excludeBeams = excludeBeams,
            includeEnemies = includeEnemies,
            excludeEnemies = excludeEnemies,
            includeEnemyCategories = includeEnemyCategories,
            excludeEnemyCategories = excludeEnemyCategories,
            beamDamage = if (beamDamage.enabled) beamDamage else presetRequest.beamDamage,
            enemyStats = if (enemyStats.enabled) enemyStats else presetRequest.enemyStats,
            enemyDrops = if (enemyDrops.enabled) enemyDrops else presetRequest.enemyDrops,
            enemyVulnerabilities = if (enemyVulnerabilities.enabled) {
                enemyVulnerabilities
            } else {
                presetRequest.enemyVulnerabilities
            },
        )
    }

    private fun randomizerPreset(key: String): SmeditRandomizationRequest =
        when (key) {
            "balanced" -> SmeditRandomizationRequest(
                preset = key,
                beamDamage = SmeditBeamDamageRandomization(
                    enabled = true,
                    damageMin = 0.75,
                    damageMax = 1.75,
                ),
                enemyStats = SmeditEnemyStatsRandomization(
                    enabled = true,
                    enemyHpMin = 0.75,
                    enemyHpMax = 2.0,
                    enemyDamageMin = 0.75,
                    enemyDamageMax = 1.5,
                ),
                enemyDrops = SmeditEnemyDropsRandomization(
                    enabled = true,
                    nothingWeight = 3.0,
                    minNonZeroSlots = 3,
                    maxNothing = 180,
                ),
                enemyVulnerabilities = SmeditEnemyVulnerabilityRandomization(
                    enabled = true,
                    noEffectChance = 0.10,
                    multipliers = listOf(1, 2, 4),
                    minEffectiveWeaponsPerEnemy = 3,
                    requiredEffectiveWeaponSlots = listOf(9, 21),
                ),
            )
            "spicy" -> SmeditRandomizationRequest(
                preset = key,
                beamDamage = SmeditBeamDamageRandomization(
                    enabled = true,
                    damageMin = 0.5,
                    damageMax = 2.75,
                ),
                enemyStats = SmeditEnemyStatsRandomization(
                    enabled = true,
                    enemyHpMin = 0.5,
                    enemyHpMax = 3.5,
                    enemyDamageMin = 0.5,
                    enemyDamageMax = 2.25,
                ),
                enemyDrops = SmeditEnemyDropsRandomization(
                    enabled = true,
                    nothingWeight = 5.0,
                    minNonZeroSlots = 2,
                    maxNothing = 220,
                ),
                enemyVulnerabilities = SmeditEnemyVulnerabilityRandomization(
                    enabled = true,
                    noEffectChance = 0.25,
                    multipliers = listOf(1, 2, 4, 8),
                    minEffectiveWeaponsPerEnemy = 2,
                    requiredEffectiveWeaponSlots = listOf(21),
                ),
            )
            "chaos" -> SmeditRandomizationRequest(
                preset = key,
                beamDamage = SmeditBeamDamageRandomization(
                    enabled = true,
                    damageMin = 0.25,
                    damageMax = 4.0,
                ),
                enemyStats = SmeditEnemyStatsRandomization(
                    enabled = true,
                    enemyHpMin = 0.25,
                    enemyHpMax = 5.0,
                    enemyDamageMin = 0.25,
                    enemyDamageMax = 3.0,
                    preserveOneHpEnemies = false,
                ),
                enemyDrops = SmeditEnemyDropsRandomization(
                    enabled = true,
                    smallEnergyWeight = 1.0,
                    largeEnergyWeight = 1.0,
                    missileWeight = 1.0,
                    nothingWeight = 5.0,
                    superMissileWeight = 1.0,
                    powerBombWeight = 1.0,
                    minNonZeroSlots = 2,
                    maxNothing = 235,
                ),
                enemyVulnerabilities = SmeditEnemyVulnerabilityRandomization(
                    enabled = true,
                    noEffectChance = 0.40,
                    multipliers = listOf(1, 2, 4, 8, 16),
                    minEffectiveWeaponsPerEnemy = 1,
                    requiredEffectiveWeaponSlots = listOf(21),
                ),
            )
            "survival" -> SmeditRandomizationRequest(
                preset = key,
                beamDamage = SmeditBeamDamageRandomization(
                    enabled = true,
                    damageMin = 0.6,
                    damageMax = 1.3,
                ),
                enemyStats = SmeditEnemyStatsRandomization(
                    enabled = true,
                    enemyHpMin = 1.25,
                    enemyHpMax = 4.0,
                    enemyDamageMin = 1.0,
                    enemyDamageMax = 2.5,
                ),
                enemyDrops = SmeditEnemyDropsRandomization(
                    enabled = true,
                    smallEnergyWeight = 1.0,
                    largeEnergyWeight = 0.6,
                    missileWeight = 1.0,
                    nothingWeight = 8.0,
                    superMissileWeight = 0.35,
                    powerBombWeight = 0.25,
                    minNonZeroSlots = 2,
                    minNothing = 64,
                    maxNothing = 240,
                ),
                enemyVulnerabilities = SmeditEnemyVulnerabilityRandomization(
                    enabled = true,
                    noEffectChance = 0.20,
                    multipliers = listOf(1, 2, 4),
                    minEffectiveWeaponsPerEnemy = 2,
                    requiredEffectiveWeaponSlots = listOf(9, 21),
                ),
            )
            else -> throw IllegalArgumentException(
                "Unknown randomization preset '$key'. Available presets: ${availablePresets.joinToString(", ")}."
            )
        }

    private fun MutableMap<String, SmeditPatchRequest>.mergeGeneratedConfig(
        configType: String,
        config: Map<String, Int>,
    ) {
        if (config.isEmpty()) return
        this[configType] = SmeditPatchRequest(
            enabled = true,
            config = config,
        )
    }

    private fun SmeditPatchRequest.mergeDirectOverride(
        direct: SmeditPatchRequest,
    ): SmeditPatchRequest {
        val directConfig = direct.configData + direct.config
        return direct.copy(
            config = config + directConfig,
            configData = emptyMap(),
        )
    }

    private fun SmeditEnemyDropsRandomization.weights(): List<Double> =
        listOf(
            smallEnergyWeight,
            largeEnergyWeight,
            missileWeight,
            nothingWeight,
            superMissileWeight,
            powerBombWeight,
        )

    private fun selectBeams(
        randomization: SmeditRandomizationRequest,
        config: SmeditBeamDamageRandomization,
    ): List<HeadlessBeamDef> {
        val globalInclude = randomization.includeBeams.normalizedSet()
        val localInclude = config.includeBeams.normalizedSet()
        val excludes = randomization.excludeBeams.normalizedSet() + config.excludeBeams.normalizedSet()
        return HEADLESS_BEAMS.filter { beam ->
            beam.key.matchesInclude(globalInclude) &&
                beam.key.matchesInclude(localInclude) &&
                beam.key !in excludes
        }
    }

    private fun selectEnemies(
        scope: String,
        randomization: SmeditRandomizationRequest,
        config: SmeditEnemyStatsRandomization,
    ): List<HeadlessEnemyDef> =
        selectEnemies(
            scope = scope,
            global = EnemyFilterInput(
                includeEnemies = randomization.includeEnemies,
                excludeEnemies = randomization.excludeEnemies,
                includeEnemyCategories = randomization.includeEnemyCategories,
                excludeEnemyCategories = randomization.excludeEnemyCategories,
            ),
            local = EnemyFilterInput(
                includeEnemies = config.includeEnemies,
                excludeEnemies = config.excludeEnemies,
                includeEnemyCategories = config.includeEnemyCategories,
                excludeEnemyCategories = config.excludeEnemyCategories,
            ),
        )

    private fun selectEnemies(
        scope: String,
        randomization: SmeditRandomizationRequest,
        config: SmeditEnemyDropsRandomization,
    ): List<HeadlessEnemyDef> =
        selectEnemies(
            scope = scope,
            global = EnemyFilterInput(
                includeEnemies = randomization.includeEnemies,
                excludeEnemies = randomization.excludeEnemies,
                includeEnemyCategories = randomization.includeEnemyCategories,
                excludeEnemyCategories = randomization.excludeEnemyCategories,
            ),
            local = EnemyFilterInput(
                includeEnemies = config.includeEnemies,
                excludeEnemies = config.excludeEnemies,
                includeEnemyCategories = config.includeEnemyCategories,
                excludeEnemyCategories = config.excludeEnemyCategories,
            ),
        )

    private fun selectEnemies(
        scope: String,
        randomization: SmeditRandomizationRequest,
        config: SmeditEnemyVulnerabilityRandomization,
    ): List<HeadlessEnemyDef> =
        selectEnemies(
            scope = scope,
            global = EnemyFilterInput(
                includeEnemies = randomization.includeEnemies,
                excludeEnemies = randomization.excludeEnemies,
                includeEnemyCategories = randomization.includeEnemyCategories,
                excludeEnemyCategories = randomization.excludeEnemyCategories,
            ),
            local = EnemyFilterInput(
                includeEnemies = config.includeEnemies,
                excludeEnemies = config.excludeEnemies,
                includeEnemyCategories = config.includeEnemyCategories,
                excludeEnemyCategories = config.excludeEnemyCategories,
            ),
        )

    private fun selectEnemies(
        scope: String,
        global: EnemyFilterInput,
        local: EnemyFilterInput,
    ): List<HeadlessEnemyDef> {
        val globalIncludeEnemies = global.includeEnemies.normalizedSet()
        val globalIncludeCategories = global.includeEnemyCategories.normalizedSet()
        val localIncludeEnemies = local.includeEnemies.normalizedSet()
        val localIncludeCategories = local.includeEnemyCategories.normalizedSet()
        val excludeEnemies = global.excludeEnemies.normalizedSet() + local.excludeEnemies.normalizedSet()
        val excludeCategories = global.excludeEnemyCategories.normalizedSet() + local.excludeEnemyCategories.normalizedSet()

        return HEADLESS_ENEMY_DEFS.filter { enemy ->
            val key = enemy.key.normalizedKey()
            val category = enemy.category.normalizedKey()
            enemyMatchesInclude(key, category, globalIncludeEnemies, globalIncludeCategories) &&
                enemyMatchesInclude(key, category, localIncludeEnemies, localIncludeCategories) &&
                key !in excludeEnemies &&
                category !in excludeCategories
        }.also {
            require(it.isNotEmpty()) {
                "$scope filters selected no enemies."
            }
        }
    }

    private data class EnemyFilterInput(
        val includeEnemies: List<String>,
        val excludeEnemies: List<String>,
        val includeEnemyCategories: List<String>,
        val excludeEnemyCategories: List<String>,
    )

    private fun validateBeamFilters(
        scope: String,
        includeBeams: List<String>,
        excludeBeams: List<String>,
    ) {
        val known = HEADLESS_BEAMS.map { it.key }.toSet()
        requireKnownValues(scope, "includeBeams", includeBeams, known)
        requireKnownValues(scope, "excludeBeams", excludeBeams, known)
    }

    private fun validateEnemyFilters(
        scope: String,
        includeEnemies: List<String>,
        excludeEnemies: List<String>,
        includeEnemyCategories: List<String>,
        excludeEnemyCategories: List<String>,
    ) {
        val knownEnemies = HEADLESS_ENEMY_DEFS.map { it.key }.toSet()
        val knownCategories = HEADLESS_ENEMY_DEFS.map { it.category.normalizedKey() }.toSet()
        requireKnownValues(scope, "includeEnemies", includeEnemies, knownEnemies)
        requireKnownValues(scope, "excludeEnemies", excludeEnemies, knownEnemies)
        requireKnownValues(scope, "includeEnemyCategories", includeEnemyCategories, knownCategories)
        requireKnownValues(scope, "excludeEnemyCategories", excludeEnemyCategories, knownCategories)
    }

    private fun requireKnownValues(
        scope: String,
        field: String,
        values: List<String>,
        known: Set<String>,
    ) {
        val unknown = values.map { it.normalizedKey() }.filterNot { it in known }
        require(unknown.isEmpty()) {
            "$scope $field contains unknown value(s): ${unknown.joinToString(", ")}."
        }
    }

    private fun String.matchesInclude(include: Set<String>): Boolean =
        include.isEmpty() || normalizedKey() in include

    private fun enemyMatchesInclude(
        key: String,
        category: String,
        includeEnemies: Set<String>,
        includeCategories: Set<String>,
    ): Boolean =
        (includeEnemies.isEmpty() && includeCategories.isEmpty()) ||
            key in includeEnemies ||
            category in includeCategories

    private fun List<String>.normalizedSet(): Set<String> =
        map { it.normalizedKey() }.filter { it.isNotEmpty() }.toSet()

    private fun String.normalizedKey(): String =
        trim().lowercase()

    private fun String.normalizedPresetKey(): String =
        normalizedKey().replace('_', '-')

    private fun SmeditEnemyVulnerabilityRandomization.randomMultiplier(random: Random): Int =
        multipliers[random.nextInt(multipliers.size)]

    private fun safeDropDistribution(
        config: SmeditEnemyDropsRandomization,
        weights: List<Double>,
        random: Random,
    ): List<Int> {
        val slots = randomWeightedByteDistribution(weights, config.total, random).toMutableList()
        clampNothingSlot(slots, config, weights, random)
        ensureMinimumNonZeroSlots(slots, config, weights, random)
        clampNothingSlot(slots, config, weights, random)
        return slots
    }

    private fun clampNothingSlot(
        slots: MutableList<Int>,
        config: SmeditEnemyDropsRandomization,
        weights: List<Double>,
        random: Random,
    ) {
        while (slots[NOTHING_DROP_POSITION] < config.minNothing) {
            val donor = randomDonorSlot(
                slots = slots,
                random = random,
                except = NOTHING_DROP_POSITION,
                minNothing = config.minNothing,
                minimumDonorValue = 0,
            ) ?: break
            slots[donor]--
            slots[NOTHING_DROP_POSITION]++
        }

        val receivers = weights.indices.filter { index ->
            index != NOTHING_DROP_POSITION && weights[index] > 0.0
        }
        while (slots[NOTHING_DROP_POSITION] > config.maxNothing) {
            val receiver = receivers[random.nextInt(receivers.size)]
            slots[NOTHING_DROP_POSITION]--
            slots[receiver]++
        }
    }

    private fun ensureMinimumNonZeroSlots(
        slots: MutableList<Int>,
        config: SmeditEnemyDropsRandomization,
        weights: List<Double>,
        random: Random,
    ) {
        while (slots.count { it > 0 } < config.minNonZeroSlots) {
            val targets = weights.indices.filter { index ->
                slots[index] == 0 &&
                    weights[index] > 0.0 &&
                    (index != NOTHING_DROP_POSITION || config.maxNothing > 0)
            }
            val target = targets.getOrNull(random.nextIntOrNull(targets.size)) ?: break
            val donor = randomDonorSlot(
                slots = slots,
                random = random,
                except = target,
                minNothing = config.minNothing,
                minimumDonorValue = 1,
            ) ?: break
            slots[donor]--
            slots[target]++
        }
    }

    private fun randomDonorSlot(
        slots: List<Int>,
        random: Random,
        except: Int,
        minNothing: Int,
        minimumDonorValue: Int,
    ): Int? {
        val donors = slots.indices.filter { index ->
            index != except &&
                slots[index] > minimumDonorValue &&
                (index != NOTHING_DROP_POSITION || slots[index] > minNothing)
        }
        return donors.getOrNull(random.nextIntOrNull(donors.size))
    }

    private fun Random.nextIntOrNull(bound: Int): Int =
        if (bound <= 0) 0 else nextInt(bound)

    private fun scaledInt(
        defaultValue: Int,
        minRate: Double,
        maxRate: Double,
        minValue: Int,
        maxValue: Int,
        random: Random,
    ): Int {
        val rate = randomDouble(minRate, maxRate, random)
        return (defaultValue * rate)
            .roundToInt()
            .coerceIn(minValue, maxValue)
            .coerceIn(0, 0xFFFF)
    }

    private fun randomWeightedByteDistribution(
        weights: List<Double>,
        total: Int,
        random: Random,
    ): List<Int> {
        val raw = weights.map { weight ->
            if (weight <= 0.0) 0.0 else -ln(random.nextDouble().coerceAtLeast(1e-12)) * weight
        }
        val rawTotal = raw.sum()
        val exact = raw.map { it / rawTotal * total }
        val floors = exact.map { floor(it).toInt() }.toMutableList()
        var remainder = total - floors.sum()

        exact.withIndex()
            .sortedByDescending { (_, value) -> value - floor(value) }
            .forEach { (index, _) ->
                if (remainder <= 0) return@forEach
                floors[index]++
                remainder--
            }

        return floors
    }

    private fun randomDouble(
        min: Double,
        max: Double,
        random: Random,
    ): Double =
        if (min == max) min else min + (max - min) * random.nextDouble()

    private const val NOTHING_DROP_POSITION = 3
}

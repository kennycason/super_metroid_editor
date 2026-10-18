package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.EnvironmentalDamagePatch
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomNamePauseMapPatch

/** Compare actual settings, not merely the presence of a config map. Unknown settings err on confirmation. */
internal fun patchSettingsDifferFromBaseline(patch: SmPatch, parser: RomParser?): Boolean {
    fun word(snes: Int): Int? = runCatching {
        val rom = requireNotNull(parser).getRomData()
        val pc = parser.snesToPc(snes)
        (rom[pc].toInt() and 255) or ((rom[pc + 1].toInt() and 255) shl 8)
    }.getOrNull()
    fun timer(snes: Int, fallback: Int): Int = word(snes)?.let { bcd ->
        fun decimal(value: Int) = (value ushr 4) * 10 + (value and 15)
        decimal(bcd ushr 8) * 60 + decimal(bcd and 255)
    } ?: fallback
    val scalarDefault = when (patch.configType) {
        "ceres_escape_seconds" -> timer(CERES_TIMER_OPERAND_SNES, 60)
        ZEBES_ESCAPE_CONFIG_TYPE -> timer(ZEBES_TIMER_OPERAND_SNES, ZEBES_ESCAPE_DEFAULT_SECONDS)
        SHORT_CHARGE_CONFIG_TYPE -> word(SHORT_CHARGE_INITIAL_TIMER_SNES)?.let {
            (SHORT_CHARGE_MAX_STAGES - (it ushr 8)).coerceIn(SHORT_CHARGE_MIN_STAGES, SHORT_CHARGE_MAX_STAGES)
        } ?: SHORT_CHARGE_DEFAULT_STAGES
        else -> GUI_CONFIG_PATCHES.firstOrNull { it.configType == patch.configType }?.configValue
    }
    if (patch.configValue != null && patch.configValue != scalarDefault) return true
    val data = patch.configData.orEmpty()
    if (data.isEmpty()) return false
    if (patch.configType == EnvironmentalDamagePatch.CONFIG_TYPE && parser != null) {
        val rom = parser.getRomData()
        val header = if (rom.size % 0x8000 == 512) 512 else 0
        fun pcWord(pc: Int) = if (pc + header + 1 < rom.size) (rom[pc + header].toInt() and 255) or ((rom[pc + header + 1].toInt() and 255) shl 8) else null
        return data.any { (key, value) ->
            val field = EnvironmentalDamagePatch.FIELDS.singleOrNull { it.key == key } ?: return@any true
            if (value !in EnvironmentalDamagePatch.MIN_ENERGY_PER_SECOND..EnvironmentalDamagePatch.MAX_ENERGY_PER_SECOND) return@any true
            val fixed = EnvironmentalDamagePatch.encodeEnergyPerSecond(value)
            fixed.lowWord != pcWord(field.lowWordPc) || fixed.highWord != pcWord(field.highWordPc)
        }
    }
    if (patch.configType == "boss_stats") return data.any { (key, value) ->
        val field = ALL_BOSS_FIELDS.singleOrNull { it.key == key } ?: return@any true
        field.writeSpeciesIds.any { species -> (word((0xA00000 or species) + field.offset) ?: field.defaultValue) != value }
    }
    val defaults: Map<String, Int> = when (patch.configType) {
        "beam_damage" -> ALL_BEAMS.flatMap { beam -> listOf(
            beam.key to (word(beam.snesAddress) ?: beam.defaultDamage),
            beam.chargedKey to (word(beam.chargedSnesAddress) ?: beam.defaultDamage * 3),
        ) }.toMap()
        EnvironmentalDamagePatch.CONFIG_TYPE -> readEnvironmentalDamageRomRates(parser)
        BOMB_CONFIG_TYPE -> readBombsRomDefaults(parser).let { mapOf(BOMB_MAX_ACTIVE_KEY to it.maxActiveBombs,
            BOMB_FUSE_FRAMES_KEY to it.fuseFrames, BOMB_COOLDOWN_FRAMES_KEY to it.cooldownFrames,
            BOMB_EXPLOSION_FRAME_DELAY_KEY to it.explosionFrameDelay) }
        FANFARE_CONFIG_TYPE -> readFanfareRomDefaults(parser).let { mapOf(
            FANFARE_FRAMES_KEY to it.itemFanfareFrames) }
        "samus_physics" -> ALL_PHYSICS_FIELDS.associate { it.key to (readPhysicsValue(parser, it) ?: it.defaultValue) }
        "phantoon" -> ALL_PHANTOON_FIELDS.associate { it.key to (readPhantoonFromRom(parser, it) ?: it.defaultValue) }
        KRAID_CONFIG_TYPE -> ALL_KRAID_FIELDS.associate { it.key to (readKraidFromRom(parser, it) ?: it.defaultValue) }
        "boss_defeated" -> data.keys.associateWith { 0 }
        "controller_config" -> CONTROLLER_SLOTS.associate { it.key to (word(0x82F575 + it.tableIndex * 2) ?: it.defaultButton) }
        RoomNamePauseMapPatch.CONFIG_TYPE -> mapOf(RoomNamePauseMapPatch.CONFIG_ALIGNMENT_KEY to RoomNamePauseMapPatch.RoomNameAlignment.CENTER.configValue)
        "enemy_stats" -> ENEMY_DEFS.flatMap { enemy -> listOf(
            "${enemy.key}_hp" to (word((0xA00000 or enemy.speciesId) + 4) ?: enemy.defaultHp),
            "${enemy.key}_dmg" to (word((0xA00000 or enemy.speciesId) + 6) ?: enemy.defaultDamage)) }.toMap()
        "enemy_drops" -> ENEMY_DEFS.flatMap { enemy ->
            val table = readDropTable(parser, enemy.speciesId)
            DROP_SLOTS.map { "${enemy.key}_drop${it.index}" to (table?.get(it.index) ?: 0) }
        }.toMap()
        "enemy_vuln" -> ENEMY_DEFS.flatMap { enemy ->
            val table = readResistanceTable(parser, enemy.speciesId)
            WEAPON_SLOTS.map { "${enemy.key}_vuln${it.index}" to (table?.get(it.index) ?: 2) }
        }.toMap()
        else -> BOSS_BEHAVIOR_FIELDS_BY_CONFIG_TYPE[patch.configType]?.associate {
            it.key to (readBossBehaviorFromRom(parser, it) ?: it.defaultValue)
        }.orEmpty()
    }
    val values = if (patch.configType == "beam_damage") runCatching { beamDamageConfigValues(data) }.getOrElse { return true } else data
    return values.any { (key, value) -> defaults[key] != value }
}

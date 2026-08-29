package com.supermetroid.editor.headless

import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.data.withVanillaHexPatchPreconditions
import com.supermetroid.editor.rom.RoomNamePauseMapPatch

const val CERES_ESCAPE_CONFIG_TYPE = "ceres_escape_seconds"
const val CERES_TIMER_OPERAND_SNES = 0x809E0E

const val BOMB_CONFIG_TYPE = "bombs"
const val BOMB_MAX_ACTIVE_KEY = "max_active_bombs"
const val BOMB_FUSE_FRAMES_KEY = "fuse_frames"
const val BOMB_COOLDOWN_FRAMES_KEY = "cooldown_frames"
const val BOMB_EXPLOSION_FRAME_DELAY_KEY = "explosion_frame_delay"
const val BOMB_FUSE_TIMER_PC = 0x083F9B
const val BOMB_ACTIVE_HARD_CAP_OPERAND_PC = 0x0840F4
const val BOMB_COOLDOWN_PC = 0x08427F
const val BOMB_EXPLOSION_FRAME_DELAY_OPERAND_PC = 0x09815B
const val BOMB_DEFAULT_MAX_ACTIVE = 3
const val BOMB_DEFAULT_HARD_CAP = 5
const val BOMB_DEFAULT_FUSE_FRAMES = 0x003C
const val BOMB_DEFAULT_COOLDOWN_FRAMES = 0x10
const val BOMB_DEFAULT_EXPLOSION_FRAME_DELAY = 1
const val BOMB_MAX_PROJECTILE_SLOTS = 5

const val FANFARE_CONFIG_TYPE = "fanfares"
const val FANFARE_FRAMES_KEY = "item_fanfare_frames"
const val FANFARE_MESSAGE_BOX_WAIT_PC = 0x028491
val FANFARE_MUSIC_RESUME_DELAY_PCS = listOf(
    0x0208DF,
    0x020906,
    0x020931,
    0x020958,
    0x020976,
    0x020999,
    0x0209C2,
    0x0209EB,
    0x020A14,
)
const val FANFARE_DEFAULT_FRAMES = 0x0168
const val FANFARE_MIN_FRAMES = 1
const val FANFARE_MAX_FRAMES = 9999

const val BEAM_DAMAGE_CONFIG_TYPE = "beam_damage"
const val ENEMY_STATS_CONFIG_TYPE = "enemy_stats"
const val ENEMY_DROPS_CONFIG_TYPE = "enemy_drops"
const val ENEMY_VULN_CONFIG_TYPE = "enemy_vuln"
const val BOSS_STATS_CONFIG_TYPE = "boss_stats"
const val HYPER_BEAM_CONFIG_TYPE = "hyper_beam"
const val BOSS_DEFEATED_CONFIG_TYPE = "boss_defeated"
const val ROOM_NAME_PAUSE_MAP_CONFIG_TYPE = "room_name_pause_map"
const val PHANTOON_CONFIG_TYPE = "phantoon"
const val KRAID_CONFIG_TYPE = "kraid"
const val RIDLEY_CONFIG_TYPE = "ridley"
const val DRAYGON_CONFIG_TYPE = "draygon"
const val SPORE_SPAWN_CONFIG_TYPE = "spore_spawn"
const val CROCOMIRE_CONFIG_TYPE = "crocomire"
const val BOTWOON_CONFIG_TYPE = "botwoon"
const val TORIZO_CONFIG_TYPE = "torizo"
const val MOTHER_BRAIN_CONFIG_TYPE = "mother_brain"
const val SAMUS_PHYSICS_CONFIG_TYPE = "samus_physics"
const val CONTROLLER_CONFIG_TYPE = "controller_config"

object SmeditPatchCatalog {
    fun defaultPatches(): List<SmPatch> =
        withVanillaHexPatchPreconditions(hardcodedPatches()) +
            configPatches() + unsupportedConfigPatches() + PatchRepository.loadBundledPatches()

    fun resolvePatchKey(key: String): String =
        patchAliasesByPublicId()[key] ?: key

    fun publicPatchId(patch: SmPatch): String =
        explicitPublicPatchIds[patch.id]
            ?: patch.configType
            ?: patch.id.removePrefix("hex_").removePrefix("bundled_")

    fun patchAliasesFor(internalPatchId: String): List<String> {
        val publicId = defaultPatches()
            .firstOrNull { it.id == internalPatchId }
            ?.let(::publicPatchId)
            ?: explicitPublicPatchIds[internalPatchId]
        return patchAliasesByPublicId()
            .filterValues { it == internalPatchId }
            .keys
            .filter { it != publicId }
            .sorted()
    }

    fun supportedConfigTypes(): Set<String> =
        setOf(
            CERES_ESCAPE_CONFIG_TYPE,
            BOMB_CONFIG_TYPE,
            FANFARE_CONFIG_TYPE,
            BEAM_DAMAGE_CONFIG_TYPE,
            ENEMY_STATS_CONFIG_TYPE,
            ENEMY_DROPS_CONFIG_TYPE,
            ENEMY_VULN_CONFIG_TYPE,
            BOSS_STATS_CONFIG_TYPE,
            HYPER_BEAM_CONFIG_TYPE,
            BOSS_DEFEATED_CONFIG_TYPE,
            ROOM_NAME_PAUSE_MAP_CONFIG_TYPE,
            SAMUS_PHYSICS_CONFIG_TYPE,
            CONTROLLER_CONFIG_TYPE,
        ) + HEADLESS_BOSS_BEHAVIOR_CONFIG_TYPES

    fun configSchemas(): List<SmeditConfigSchema> =
        listOf(
            ceresSchema(),
            bombsSchema(),
            fanfaresSchema(),
            beamDamageSchema(),
            enemyStatsSchema(),
            enemyDropsSchema(),
            enemyVulnerabilitySchema(),
            bossStatsSchema(),
            hyperBeamSchema(),
            bossDefeatedSchema(),
            roomNamePauseMapSchema(),
            samusPhysicsSchema(),
            controllerConfigSchema(),
        ) + HEADLESS_BOSS_BEHAVIOR_DEFINITIONS.map(::bossBehaviorSchema)

    fun configSchema(key: String): SmeditConfigSchema? {
        val resolvedKey = resolvePatchKey(key)
        val catalogPatch = defaultPatches().firstOrNull {
            it.id == resolvedKey || it.configType == resolvedKey || it.id == key || it.configType == key
        }
        val configType = catalogPatch?.configType ?: resolvedKey
        return configSchemas().firstOrNull {
            it.configType == configType || it.patchId == resolvedKey || it.patchId == key
        }
    }

    private fun patchAliasesByPublicId(): Map<String, String> {
        val aliases = linkedMapOf<String, String>()
        for (patch in defaultPatches()) {
            val publicId = publicPatchId(patch)
            aliases[publicId] = patch.id

            if (patch.id.startsWith("hex_")) {
                aliases.putIfAbsent(patch.id.removePrefix("hex_"), patch.id)
            }
            if (patch.id.startsWith("bundled_")) {
                aliases.putIfAbsent(patch.id.removePrefix("bundled_"), patch.id)
            }
            patch.configType?.let { aliases.putIfAbsent(it, patch.id) }
        }
        aliases.putAll(explicitPatchAliases)
        return aliases
    }

    private val explicitPublicPatchIds = mapOf(
        "bundled_skip_intro" to "skip_intro_and_ceres",
        "bundled_skip_intro_ceres" to "skip_intro",
        "bundled_elevators_speed" to "fast_elevators",
        "bundled_no_beeping" to "no_low_energy_beeping",
        "hex_always_charged_shots" to "always_fire_charged_shots",
        "hex_infinite_supers" to "infinite_super_missiles",
        "hex_infinite_pbs" to "infinite_power_bombs",
        "hex_morph_ball_no_item" to "morph_ball_without_item",
        "hex_space_jump_no_item" to "space_jump_without_item",
        "hex_speed_boost_no_item" to "speed_booster_without_item",
        "hex_supers_dont_open_reds" to "supers_dont_open_red_doors",
    )

    private val explicitPatchAliases = mapOf(
        "skip_ceres_and_intro" to "bundled_skip_intro",
        "elevators_speed" to "bundled_elevators_speed",
        "no_beeping" to "bundled_no_beeping",
        "always_charged_shots" to "hex_always_charged_shots",
        "infinite_supers" to "hex_infinite_supers",
        "infinite_pbs" to "hex_infinite_pbs",
        "morph_ball_no_item" to "hex_morph_ball_no_item",
        "space_jump_no_item" to "hex_space_jump_no_item",
        "speed_boost_no_item" to "hex_speed_boost_no_item",
        "supers_dont_open_reds" to "hex_supers_dont_open_reds",
    )

    private fun hardcodedPatches(): List<SmPatch> = listOf(
        SmPatch(
            id = "hex_hyper_beam",
            name = "Hyper Beam",
            description = "Start with Hyper Beam enabled using the shared per-frame hook.",
            enabled = false,
            writes = mutableListOf(),
            configType = HYPER_BEAM_CONFIG_TYPE,
        ),
        SmPatch(
            id = "hex_higher_jump",
            name = "Higher Jump",
            description = "Increases standard jump height.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x81EB9, listOf(0x05))),
        ),
        SmPatch(
            id = "hex_faster_charged_shots",
            name = "Faster Charged Shots",
            description = "Reduces delay between charged shots.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x83860, listOf(0x1C))),
        ),
        SmPatch(
            id = "hex_fast_run_speed",
            name = "Faster Running Speed",
            description = "Increases default running acceleration and max speed.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x81F64, listOf(0x50, 0x04))),
        ),
        SmPatch(
            id = "hex_speed_morph",
            name = "Speed Booster in Morph Ball",
            description = "Enables speed booster while in morph ball by holding run with spring ball.",
            enabled = false,
            writes = mutableListOf(
                PatchWrite(0x8054E, listOf(0x0F)),
                PatchWrite(0x81775, listOf(0x0F)),
            ),
        ),
        SmPatch(
            id = "hex_fast_shinespark_recovery",
            name = "Fast Shinespark Recovery",
            description = "Greatly reduces wait time after shinesparking into a wall.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x85396, listOf(0x80, 0x27))),
        ),
        SmPatch(
            id = "hex_keep_blue_speed_air",
            name = "Keep Blue Speed in Air",
            description = "Moving left/right during spin jump no longer cancels speed booster blue effect.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x8F66F, listOf(0xEA, 0xEA, 0xEA, 0xEA))),
        ),
        SmPatch(
            id = "hex_no_spin_speed_loss",
            name = "No Spin Jump Speed Loss",
            description = "Samus does not lose speed turning left/right during spin jump.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x8F625, listOf(0x22))),
        ),
        SmPatch(
            id = "hex_lower_gravity",
            name = "Lower Gravity",
            description = "Reduces planet gravity for floatier jumps.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x81EA2, listOf(0x0C))),
        ),
        SmPatch(
            id = "hex_higher_gravity",
            name = "Higher Gravity",
            description = "Increases planet gravity for heavier feel.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x81EA2, listOf(0x2C))),
        ),
        SmPatch(
            id = "hex_instant_stop",
            name = "Instant Stop (No Skid)",
            description = "Disables the skid animation when turning.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x8267F, listOf(0xEA, 0xEA, 0xEA))),
        ),
        SmPatch(
            id = "hex_no_walljump_kickoff",
            name = "No Walljump Wall Push",
            description = "Walljumping no longer forces Samus away from the wall.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x81006, listOf(0x00, 0x00))),
        ),
        SmPatch(
            id = "hex_realistic_air_physics",
            name = "Realistic Air Physics",
            description = "No mid-air movement from standstill jumps or direction reversal in mid-air.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x81B2F, listOf(0x04))),
        ),
        SmPatch(
            id = "hex_space_jump_underwater",
            name = "Space Jump in Water (No Gravity)",
            description = "Space jump works underwater/lava/acid without Gravity suit.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x82445, listOf(0xEA, 0xEA, 0xEA))),
        ),
        SmPatch(
            id = "hex_smooth_beam_shots",
            name = "Smooth Beam Shots",
            description = "Removes flicker from uncharged beam shots.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x9826B, listOf(0x80))),
        ),
        SmPatch(
            id = "hex_always_charged_shots",
            name = "Always Fire Charged Shots",
            description = "Samus always fires charged shots.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x838D4, listOf(0x10))),
        ),
        SmPatch(
            id = "hex_remove_all_trails",
            name = "Remove All Beam/Missile Trails",
            description = "Removes trails from shots, charged shots, missiles, and SBAs.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x982F7, listOf(0xEA, 0xEA, 0xEA, 0xEA))),
        ),
        SmPatch(
            id = "hex_disable_pseudo_screw",
            name = "Disable Pseudo Screw Attack",
            description = "Spinning jump during beam charge no longer does screw attack damage.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x824F5, listOf(0xEA, 0xEA, 0xEA))),
        ),
        SmPatch(
            id = "hex_infinite_missiles",
            name = "Infinite Missiles",
            description = "Missiles never deplete.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x83EBF, listOf(0xAD))),
        ),
        SmPatch(
            id = "hex_infinite_supers",
            name = "Infinite Super Missiles",
            description = "Super missiles never deplete.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x83EC4, listOf(0xAD))),
        ),
        SmPatch(
            id = "hex_infinite_pbs",
            name = "Infinite Power Bombs",
            description = "Power bombs never deplete.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x8402E, listOf(0xAD))),
        ),
        SmPatch(
            id = "hex_morph_ball_no_item",
            name = "Morph Ball Without Item",
            description = "Allows Morph Ball without collecting it.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x8F7D5, listOf(0x00))),
        ),
        SmPatch(
            id = "hex_space_jump_no_item",
            name = "Space Jump Without Item",
            description = "Enables space jump without having the item.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x82474, listOf(0x80))),
        ),
        SmPatch(
            id = "hex_speed_boost_no_item",
            name = "Speed Booster Without Item",
            description = "Samus can run fast without collecting speed booster.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x8178C, listOf(0xA9))),
        ),
        SmPatch(
            id = "hex_no_screen_shake",
            name = "No Screen Shake",
            description = "Disables screen shaking effects globally.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x10BAF, listOf(0xEA, 0xEA, 0xEA, 0xEA))),
        ),
        SmPatch(
            id = "hex_no_suit_flash",
            name = "No Suit Collection Flash",
            description = "Varia/Gravity suits collect without the flash and sound.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x20717, listOf(0xEA, 0xEA, 0xEA, 0xEA))),
        ),
        SmPatch(
            id = "hex_fast_xray",
            name = "Fast X-Ray Scope",
            description = "X-ray scope beam widens almost instantly.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x4079A, listOf(0x01))),
        ),
        SmPatch(
            id = "hex_disable_grapple_camera_scroll",
            name = "Disable Grapple Slow Camera",
            description = "Turns off the slow scrolling camera when swinging with grapple beam.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0xDBDAA, listOf(0x00))),
        ),
        SmPatch(
            id = "hex_disable_bomb_jump",
            name = "Disable Bomb Jump",
            description = "Bomb jumping no longer works.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x10B61, listOf(0xEA, 0xEA, 0xEA, 0xEA))),
        ),
        SmPatch(
            id = "hex_no_crystal_flash",
            name = "Disable Crystal Flash",
            description = "Crystal flash can no longer be used.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x40B5F, listOf(0xEA, 0xEA, 0xEA, 0xEA))),
        ),
        SmPatch(
            id = "hex_gravity_no_heat_protect",
            name = "Gravity Suit No Heat Protection",
            description = "Removes Gravity suit heat protection.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x6E37D, listOf(0x01))),
        ),
        SmPatch(
            id = "hex_supers_dont_open_reds",
            name = "Supers Don't Open Red Doors",
            description = "Super missiles no longer open red or missile doors.",
            enabled = false,
            writes = mutableListOf(PatchWrite(0x23D58, listOf(0xEA, 0xEA, 0xEA, 0xEA, 0xEA))),
        ),
    )

    private fun configPatches(): List<SmPatch> = listOf(
        SmPatch(
            id = "config_ceres_escape_time",
            name = "Ceres Escape Time",
            description = "Sets the Ceres station escape timer in seconds.",
            enabled = false,
            configType = CERES_ESCAPE_CONFIG_TYPE,
            configValue = 60,
        ),
        SmPatch(
            id = "config_bombs",
            name = "Bombs",
            description = "Control active normal bomb count, lay cooldown, bomb fuse timing, and explosion animation timing.",
            enabled = false,
            configType = BOMB_CONFIG_TYPE,
        ),
        SmPatch(
            id = "config_fanfares",
            name = "Fanfares",
            description = "Control item fanfare box duration and room-music resume timing.",
            enabled = false,
            configType = FANFARE_CONFIG_TYPE,
        ),
        SmPatch(
            id = "config_beam_damage",
            name = "Beam Damage",
            description = "Set uncharged beam damage values. Charged values are written as 3x the configured damage.",
            enabled = false,
            configType = BEAM_DAMAGE_CONFIG_TYPE,
        ),
        SmPatch(
            id = "config_enemy_stats",
            name = "Enemy Stats",
            description = "Set enemy HP, contact damage, AI routine pointers, graphics fields, and PB vulnerability fields.",
            enabled = false,
            configType = ENEMY_STATS_CONFIG_TYPE,
        ),
        SmPatch(
            id = "config_enemy_drops",
            name = "Enemy Drops",
            description = "Set enemy drop table weights from ROM-resolved drop table pointers.",
            enabled = false,
            configType = ENEMY_DROPS_CONFIG_TYPE,
        ),
        SmPatch(
            id = "config_enemy_vuln",
            name = "Enemy Vulnerabilities",
            description = "Set enemy weapon vulnerability table entries from ROM-resolved resistance table pointers.",
            enabled = false,
            configType = ENEMY_VULN_CONFIG_TYPE,
        ),
        SmPatch(
            id = "config_boss_stats",
            name = "Boss Stats",
            description = "Set HP and contact/projectile damage fields for major and mini-boss enemy headers.",
            enabled = false,
            configType = BOSS_STATS_CONFIG_TYPE,
        ),
        SmPatch(
            id = "config_boss_defeated",
            name = "Boss Defeated Flags",
            description = "Mark bosses as already defeated using the shared per-frame hook.",
            enabled = false,
            configType = BOSS_DEFEATED_CONFIG_TYPE,
        ),
        SmPatch(
            id = "config_room_name_pause_map",
            name = "Room Name Pause Map",
            description = "Draw the current room name on the pause map using SMEDIT room metadata and project overrides.",
            enabled = false,
            configType = ROOM_NAME_PAUSE_MAP_CONFIG_TYPE,
        ),
        SmPatch(
            id = "config_samus_physics",
            name = "Samus Physics",
            description = "Set Samus movement physics bytes for jump, gravity, run speed, and air control.",
            enabled = false,
            configType = SAMUS_PHYSICS_CONFIG_TYPE,
        ),
        SmPatch(
            id = "config_controller",
            name = "Controller Config",
            description = "Remap the default controller button table.",
            enabled = false,
            configType = CONTROLLER_CONFIG_TYPE,
        ),
    ) + HEADLESS_BOSS_BEHAVIOR_DEFINITIONS.map { definition ->
        SmPatch(
            id = definition.patchId,
            name = definition.name,
            description = definition.description,
            enabled = false,
            configType = definition.configType,
        )
    }

    private fun unsupportedConfigPatches(): List<SmPatch> = emptyList()

    private fun ceresSchema(): SmeditConfigSchema =
        SmeditConfigSchema(
            configType = CERES_ESCAPE_CONFIG_TYPE,
            patchId = "config_ceres_escape_time",
            name = "Ceres Escape Time",
            description = "Sets the Ceres station escape timer in seconds. configValue is also accepted.",
            headlessSupported = true,
            supportsPatchOnly = true,
            requiresRom = false,
            fields = listOf(
                intField("seconds", "Seconds", min = 15, max = 600, defaultValue = 60),
                intField("total_seconds", "Total Seconds", min = 15, max = 600, defaultValue = 60),
                intField("totalSeconds", "Total Seconds", min = 15, max = 600, defaultValue = 60),
            ),
        )

    private fun bombsSchema(): SmeditConfigSchema =
        SmeditConfigSchema(
            configType = BOMB_CONFIG_TYPE,
            patchId = "config_bombs",
            name = "Bombs",
            description = "Controls active normal bomb count, lay cooldown, bomb fuse timing, and explosion animation timing.",
            headlessSupported = true,
            supportsPatchOnly = true,
            requiresRom = false,
            fields = listOf(
                intField(BOMB_MAX_ACTIVE_KEY, "Max Active Bombs", 1, BOMB_MAX_PROJECTILE_SLOTS, BOMB_DEFAULT_MAX_ACTIVE),
                intField(BOMB_FUSE_FRAMES_KEY, "Fuse Frames", 1, 9999, BOMB_DEFAULT_FUSE_FRAMES),
                intField(BOMB_COOLDOWN_FRAMES_KEY, "Cooldown Frames", 0, 255, BOMB_DEFAULT_COOLDOWN_FRAMES),
                intField(BOMB_EXPLOSION_FRAME_DELAY_KEY, "Explosion Frame Delay", 1, 255, BOMB_DEFAULT_EXPLOSION_FRAME_DELAY),
            ),
        )

    private fun fanfaresSchema(): SmeditConfigSchema =
        SmeditConfigSchema(
            configType = FANFARE_CONFIG_TYPE,
            patchId = "config_fanfares",
            name = "Fanfares",
            description = "Controls item fanfare message duration and room-music resume timing.",
            headlessSupported = true,
            supportsPatchOnly = true,
            requiresRom = false,
            fields = listOf(
                intField(FANFARE_FRAMES_KEY, "Item Fanfare Frames", FANFARE_MIN_FRAMES, FANFARE_MAX_FRAMES, FANFARE_DEFAULT_FRAMES),
            ),
        )

    private fun beamDamageSchema(): SmeditConfigSchema =
        SmeditConfigSchema(
            configType = BEAM_DAMAGE_CONFIG_TYPE,
            patchId = "config_beam_damage",
            name = "Beam Damage",
            description = "Sets uncharged beam damage. Charged beam damage is written as 3x the configured value.",
            headlessSupported = true,
            supportsPatchOnly = true,
            requiresRom = false,
            fields = HEADLESS_BEAMS.map { beam ->
                intField(beam.key, beam.label, min = 0, max = 0xFFFF, defaultValue = beam.defaultDamage)
            },
        )

    private fun enemyStatsSchema(): SmeditConfigSchema =
        SmeditConfigSchema(
            configType = ENEMY_STATS_CONFIG_TYPE,
            patchId = "config_enemy_stats",
            name = "Enemy Stats",
            description = "Sets enemy HP, contact damage, AI routine pointers, graphics fields, and PB vulnerability fields.",
            headlessSupported = true,
            supportsPatchOnly = true,
            requiresRom = false,
            fields = HEADLESS_ENEMY_DEFS.flatMap { enemy ->
                listOf(
                    intField("${enemy.key}_hp", "${enemy.label} HP", 0, 0xFFFF, enemy.defaultHp, enemy.category),
                    intField("${enemy.key}_dmg", "${enemy.label} Contact Damage", 0, 0xFFFF, enemy.defaultDamage, enemy.category),
                ) + ENEMY_HEADER_POINTER_FIELDS.map { field ->
                    intField(
                        key = "${enemy.key}${field.suffix}",
                        label = "${enemy.label} ${field.label}",
                        min = 0,
                        max = 0xFFFF,
                        defaultValue = null,
                        category = enemy.category,
                        description = field.description,
                    )
                }
            },
        )

    private fun enemyDropsSchema(): SmeditConfigSchema =
        SmeditConfigSchema(
            configType = ENEMY_DROPS_CONFIG_TYPE,
            patchId = "config_enemy_drops",
            name = "Enemy Drops",
            description = "Sets enemy drop table weights. Values are 0-255 and usually sum to about 255 per enemy.",
            headlessSupported = true,
            supportsPatchOnly = false,
            requiresRom = true,
            fields = HEADLESS_ENEMY_DEFS.flatMap { enemy ->
                HEADLESS_DROP_SLOTS.map { slot ->
                    intField(
                        key = "${enemy.key}_drop${slot.index}",
                        label = "${enemy.label} ${slot.label}",
                        min = 0,
                        max = 255,
                        defaultValue = null,
                        category = enemy.category,
                        requiresRom = true,
                    )
                }
            },
        )

    private fun enemyVulnerabilitySchema(): SmeditConfigSchema =
        SmeditConfigSchema(
            configType = ENEMY_VULN_CONFIG_TYPE,
            patchId = "config_enemy_vuln",
            name = "Enemy Vulnerabilities",
            description = "Sets enemy weapon vulnerability multipliers. 0=immune, 2=normal, 4=2x.",
            headlessSupported = true,
            supportsPatchOnly = false,
            requiresRom = true,
            fields = HEADLESS_ENEMY_DEFS.flatMap { enemy ->
                HEADLESS_WEAPON_SLOTS.map { weapon ->
                    intField(
                        key = "${enemy.key}_vuln${weapon.index}",
                        label = "${enemy.label} ${weapon.label}",
                        min = 0,
                        max = 255,
                        defaultValue = weapon.defaultValue,
                        category = enemy.category,
                        requiresRom = true,
                    )
                }
            },
        )

    private fun bossStatsSchema(): SmeditConfigSchema =
        SmeditConfigSchema(
            configType = BOSS_STATS_CONFIG_TYPE,
            patchId = "config_boss_stats",
            name = "Boss Stats",
            description = "Sets HP and contact/projectile damage fields for major and mini-boss enemy headers.",
            headlessSupported = true,
            supportsPatchOnly = true,
            requiresRom = false,
            fields = HEADLESS_BOSS_STAT_FIELDS.map { field ->
                intField(field.key, field.label, 0, 0xFFFF, field.defaultValue, field.category)
            },
        )

    private fun hyperBeamSchema(): SmeditConfigSchema =
        SmeditConfigSchema(
            configType = HYPER_BEAM_CONFIG_TYPE,
            patchId = "hex_hyper_beam",
            name = "Hyper Beam",
            description = "Starts Samus with Hyper Beam active via the shared per-frame hook.",
            headlessSupported = true,
            supportsPatchOnly = true,
            requiresRom = false,
            fields = emptyList(),
        )

    private fun bossDefeatedSchema(): SmeditConfigSchema =
        SmeditConfigSchema(
            configType = BOSS_DEFEATED_CONFIG_TYPE,
            patchId = "config_boss_defeated",
            name = "Boss Defeated Flags",
            description = "Marks selected bosses as already defeated. Values are 0=clear and 1=defeated.",
            headlessSupported = true,
            supportsPatchOnly = true,
            requiresRom = false,
            fields = HEADLESS_BOSS_FLAGS.map { flag ->
                intField(
                    key = flag.key,
                    label = flag.label,
                    min = 0,
                    max = 1,
                    defaultValue = 0,
                    category = if (flag.key in MAIN_BOSS_FLAG_KEYS) "Main Bosses" else "Mini-Bosses",
                )
            },
        )

    private fun roomNamePauseMapSchema(): SmeditConfigSchema =
        SmeditConfigSchema(
            configType = ROOM_NAME_PAUSE_MAP_CONFIG_TYPE,
            patchId = "config_room_name_pause_map",
            name = "Room Name Pause Map",
            description = "Draws the current room name on the pause map. Project roomNameOverrides are included when a project is supplied.",
            headlessSupported = true,
            supportsPatchOnly = false,
            requiresRom = true,
            fields = listOf(
                SmeditConfigFieldSchema(
                    key = RoomNamePauseMapPatch.CONFIG_ALIGNMENT_KEY,
                    label = "Alignment",
                    type = "enum",
                    min = RoomNamePauseMapPatch.RoomNameAlignment.entries.minOf { it.configValue },
                    max = RoomNamePauseMapPatch.RoomNameAlignment.entries.maxOf { it.configValue },
                    defaultValue = RoomNamePauseMapPatch.RoomNameAlignment.CENTER.configValue,
                    category = "Display",
                    requiresRom = true,
                    choices = RoomNamePauseMapPatch.RoomNameAlignment.entries.map {
                        SmeditConfigChoiceSchema(label = it.label, value = it.configValue)
                    },
                )
            ),
        )

    private fun samusPhysicsSchema(): SmeditConfigSchema =
        SmeditConfigSchema(
            configType = SAMUS_PHYSICS_CONFIG_TYPE,
            patchId = "config_samus_physics",
            name = "Samus Physics",
            description = "Sets one-byte Samus movement physics values for jump, gravity, run speed, and air control.",
            headlessSupported = true,
            supportsPatchOnly = true,
            requiresRom = false,
            fields = HEADLESS_PHYSICS_FIELDS.map { field ->
                intField(field.key, field.label, 0, 255, field.defaultValue, field.category, field.description)
            },
        )

    private fun controllerConfigSchema(): SmeditConfigSchema =
        SmeditConfigSchema(
            configType = CONTROLLER_CONFIG_TYPE,
            patchId = "config_controller",
            name = "Controller Config",
            description = "Remaps the 7-slot default controller button table.",
            headlessSupported = true,
            supportsPatchOnly = true,
            requiresRom = false,
            fields = HEADLESS_CONTROLLER_SLOTS.map { slot ->
                SmeditConfigFieldSchema(
                    key = slot.key,
                    label = slot.label,
                    type = "enum",
                    min = 0,
                    max = 0xFFFF,
                    defaultValue = slot.defaultButton,
                    category = "Controller",
                    choices = HEADLESS_SNES_BUTTONS.map { button ->
                        SmeditConfigChoiceSchema(label = button.label, value = button.bitmask)
                    },
                )
            },
        )

    private fun bossBehaviorSchema(definition: HeadlessBossBehaviorDefinition): SmeditConfigSchema =
        SmeditConfigSchema(
            configType = definition.configType,
            patchId = definition.patchId,
            name = definition.name,
            description = definition.description,
            headlessSupported = true,
            supportsPatchOnly = true,
            requiresRom = false,
            fields = definition.fields.map { field ->
                intField(
                    key = field.key,
                    label = field.label,
                    min = if (field.signed) -32768 else field.logicalMinValue(),
                    max = if (field.signed) 0xFFFF else field.logicalMaxValue(),
                    defaultValue = field.defaultValue,
                    category = field.category,
                    description = "SNES ${field.snesAddress.toString(16).uppercase()}" +
                        if (field.writeSnesAddresses.size > 1) {
                            "; writes ${field.writeSnesAddresses.size} mirrored operands"
                        } else {
                            ""
                        },
                    unit = field.unit,
                    signed = field.signed,
                    logicalMin = field.logicalMinValue(),
                    logicalMax = field.logicalMaxValue(),
                )
            },
        )

    private fun intField(
        key: String,
        label: String,
        min: Int,
        max: Int,
        defaultValue: Int? = null,
        category: String? = null,
        description: String = "",
        unit: String = "",
        signed: Boolean = false,
        logicalMin: Int? = null,
        logicalMax: Int? = null,
        requiresRom: Boolean = false,
    ): SmeditConfigFieldSchema =
        SmeditConfigFieldSchema(
            key = key,
            label = label,
            type = "int",
            min = min,
            max = max,
            defaultValue = defaultValue,
            description = description,
            category = category,
            unit = unit,
            signed = signed,
            logicalMin = logicalMin,
            logicalMax = logicalMax,
            requiresRom = requiresRom,
        )

}

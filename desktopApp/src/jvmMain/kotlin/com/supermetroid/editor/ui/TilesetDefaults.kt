package com.supermetroid.editor.ui

import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.SmPatch

// ─── Tileset defaults: metatile → (block type, BTS) ─────────────

data class TileDefault(val blockType: Int, val bts: Int = 0)

/**
 * Hardcoded defaults for well-known metatiles. When a tile from the tileset
 * is placed on the map, these defaults are applied automatically so the user
 * doesn't have to right-click and set properties for every block.
 *
 * Block types: 0x0=Air, 0x3=Speed Booster, 0x4=Shot, 0x5=H-Extend, 0x8=Solid,
 *   0x9=Door, 0xA=Spike, 0xB=Crumble, 0xC=Shot(reform), 0xD=V-Extend,
 *   0xE=Grapple, 0xF=Bomb(reform)
 *
 * Type 0xC BTS (verified against PLM table at $94:9EA6):
 *   0x00-0x03=any weapon breakable (sizes 1×1..2×2)
 *   0x04-0x07=hidden (same breakability, invisible)
 *   0x08-0x09=power bomb only (reform/permanent)
 *   0x0A-0x0B=super missile only (reform/permanent)
 *   0x0C-0x0F=NON-FUNCTIONAL in vanilla SM (map to no-op PLM $B62F)
 * Type 0x3: speed booster breakable (solid, immune to shots/bombs, breaks on speed boost)
 */
object TilesetDefaults {
    val defaults: Map<Int, TileDefault> = mapOf(
        74  to TileDefault(0x4),
        75  to TileDefault(0x4),
        76  to TileDefault(0x4),
        77  to TileDefault(0x4),
        78  to TileDefault(0x4),
        79  to TileDefault(0x4),
        80  to TileDefault(0x4),
        81  to TileDefault(0x4),
        82  to TileDefault(0xC, 0x00),
        83  to TileDefault(0xC, 0x01),
        84  to TileDefault(0xC, 0x04),
        85  to TileDefault(0xC, 0x05),
        86  to TileDefault(0xA),
        87  to TileDefault(0xC, 0x08),
        88  to TileDefault(0xF),
        89  to TileDefault(0x8),
        90  to TileDefault(0x8),
        91  to TileDefault(0x0),
        92  to TileDefault(0x0),
        95  to TileDefault(0x8),
        114 to TileDefault(0x4, 0x00),
        122 to TileDefault(0x8),
        123 to TileDefault(0x8),
        124 to TileDefault(0x8),
        125 to TileDefault(0x8),
        126 to TileDefault(0x8),
        127 to TileDefault(0x8),
        155 to TileDefault(0xE),
        156 to TileDefault(0xA),
        157 to TileDefault(0xE, 0x01),
        158 to TileDefault(0xE, 0x02),
        159 to TileDefault(0xC, 0x0A),
        160 to TileDefault(0xC, 0x0B),
        182 to TileDefault(0xB, 0x0E),
        183 to TileDefault(0xE),
        188 to TileDefault(0xB, 0x00),
        189 to TileDefault(0xB, 0x04),
        190 to TileDefault(0xB, 0x0E),
        191 to TileDefault(0xB, 0x0F),
        192 to TileDefault(0x2),
        // Multi-tile shot blocks: anchor has BTS for size (2x1=0x01, 1x2=0x02, 2x2=0x03), extensions 0x00
        150 to TileDefault(0xC, 0x01),
        151 to TileDefault(0xC, 0x00),
        152 to TileDefault(0xC, 0x02),
        184 to TileDefault(0xC, 0x00),
        153 to TileDefault(0xC, 0x03),
        154 to TileDefault(0xC, 0x00),
        185 to TileDefault(0xC, 0x00),
        186 to TileDefault(0xC, 0x00),
        // Chozo tiles (CRE): solid
        69 to TileDefault(0x8),
        70 to TileDefault(0x8),
        71 to TileDefault(0x8),
        72 to TileDefault(0x8),
        73 to TileDefault(0x8),
        100 to TileDefault(0x8),
        101 to TileDefault(0x8),
        102 to TileDefault(0x8),
        // Gate tiles (CRE): 187 = gate closed, 214-215 = gate center anim, 216-220 = colored gates
        187 to TileDefault(0x8),
        214 to TileDefault(0x8),
        215 to TileDefault(0x8),
        216 to TileDefault(0x8),
        217 to TileDefault(0x8),
        218 to TileDefault(0x8),
        219 to TileDefault(0x8),
        220 to TileDefault(0x8),
        // Door transition tiles (CRE): block type 0x9
        0x040 to TileDefault(0x9),
        0x060 to TileDefault(0x9),
        // Gate cap tiles (CRE): solid so gates block passage
        0x340 to TileDefault(0x8),
        0x341 to TileDefault(0x8),
        0x342 to TileDefault(0x8),
        0x343 to TileDefault(0x8),
    )

    fun get(metatileIndex: Int): TileDefault? = defaults[metatileIndex]
}

// ─── Patch helpers ──────────────────────────────────────────────

/** UI-facing write entry (same shape as PatchWrite but not serializable). */
data class SmPatchWrite(val offset: Long, val bytes: List<Int>)

/**
 * Curated hex-edit patches from community documentation (begrimed.com hex edits).
 * All other patches load from bundled IPS in resources/patches/.
 */
val HARDCODED_PATCHES: List<SmPatch> = listOf(
    // ── Hyper Beam ──
    // Handled at export time via the per-frame hook at $82:896E.
    // Sets WRAM $7E:0A76 bit 15 ($8000) every frame to enable the rainbow beam.
    SmPatch(id = "hex_hyper_beam", name = "Hyper Beam",
        description = "Start with Hyper Beam enabled (the rainbow beam from the Mother Brain fight).",
        enabled = false, writes = mutableListOf(),
        configType = "hyper_beam"),

    // ── Popular / featured patches (sorted to top) ──
    SmPatch(id = "hex_higher_jump", name = "Higher Jump",
        description = "Increases standard jump height (0x81EB9: 04 → 05).",
        enabled = false, writes = mutableListOf(PatchWrite(0x81EB9, listOf(0x05)))),
    SmPatch(id = "hex_faster_charged_shots", name = "Faster Charged Shots",
        description = "Reduces delay between charged shots (0x3C → 0x1C at 0x83860).",
        enabled = false, writes = mutableListOf(PatchWrite(0x83860, listOf(0x1C)))),
    SmPatch(id = "hex_fast_run_speed", name = "Faster Running Speed",
        description = "Increases default running acceleration and max speed (0x81F64: 30 02 → 50 04).",
        enabled = false, writes = mutableListOf(PatchWrite(0x81F64, listOf(0x50, 0x04)))),
    SmPatch(id = "hex_speed_morph", name = "Speed Booster in Morph Ball",
        description = "Enables speed booster while in morph ball by holding run with spring ball (0x8054E: FF → 0F, 0x81775: FF → 0F).",
        enabled = false, writes = mutableListOf(PatchWrite(0x8054E, listOf(0x0F)), PatchWrite(0x81775, listOf(0x0F)))),
    SmPatch(id = "hex_fast_shinespark_recovery", name = "Fast Shinespark Recovery",
        description = "Greatly reduces wait time after shinesparking into a wall (0x85396: AD C0 → 80 27).",
        enabled = false, writes = mutableListOf(PatchWrite(0x85396, listOf(0x80, 0x27)))),
    SmPatch(id = "hex_keep_blue_speed_air", name = "Keep Blue Speed in Air",
        description = "Moving left/right during spin jump no longer cancels speed booster blue effect (0x8F66F: NOP×4).",
        enabled = false, writes = mutableListOf(PatchWrite(0x8F66F, listOf(0xEA, 0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_no_spin_speed_loss", name = "No Spin Jump Speed Loss",
        description = "Samus doesn't lose speed turning left/right during spin jump (0x8F625: 23 → 22).",
        enabled = false, writes = mutableListOf(PatchWrite(0x8F625, listOf(0x22)))),
    // ── Physics & movement ──
    SmPatch(id = "hex_lower_gravity", name = "Lower Gravity",
        description = "Reduces planet gravity for floatier jumps (0x81EA2: 1C → 0C).",
        enabled = false, writes = mutableListOf(PatchWrite(0x81EA2, listOf(0x0C)))),
    SmPatch(id = "hex_higher_gravity", name = "Higher Gravity",
        description = "Increases planet gravity for heavier feel (0x81EA2: 1C → 2C).",
        enabled = false, writes = mutableListOf(PatchWrite(0x81EA2, listOf(0x2C)))),
    SmPatch(id = "hex_instant_stop", name = "Instant Stop (No Skid)",
        description = "Disables the skid animation when turning; allows instant stopping (0x8267F: NOP×3).",
        enabled = false, writes = mutableListOf(PatchWrite(0x8267F, listOf(0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_no_walljump_kickoff", name = "No Walljump Wall Push",
        description = "Walljumping no longer forces Samus away from the wall (0x81006: FF 00 → 00 00).",
        enabled = false, writes = mutableListOf(PatchWrite(0x81006, listOf(0x00, 0x00)))),
    SmPatch(id = "hex_realistic_air_physics", name = "Realistic Air Physics",
        description = "No mid-air movement from standstill jumps or direction reversal in mid-air (0x81B2F: 02 → 04).",
        enabled = false, writes = mutableListOf(PatchWrite(0x81B2F, listOf(0x04)))),
    SmPatch(id = "hex_space_jump_underwater", name = "Space Jump in Water (No Gravity)",
        description = "Space jump works underwater/lava/acid without Gravity suit (0x82445: NOP×3).",
        enabled = false, writes = mutableListOf(PatchWrite(0x82445, listOf(0xEA, 0xEA, 0xEA)))),
    // ── Weapons & beams ──
    SmPatch(id = "hex_smooth_beam_shots", name = "Smooth Beam Shots",
        description = "Removes the flicker from uncharged beam shots for smoother appearance (0x9826B: D0 → 80).",
        enabled = false, writes = mutableListOf(PatchWrite(0x9826B, listOf(0x80)))),
    SmPatch(id = "hex_always_charged_shots", name = "Always Fire Charged Shots",
        description = "Samus always fires charged shots (0x838D4: 00 → 10).",
        enabled = false, writes = mutableListOf(PatchWrite(0x838D4, listOf(0x10)))),
    SmPatch(id = "hex_remove_all_trails", name = "Remove All Beam/Missile Trails",
        description = "Removes ALL trails from shots, charged shots, missiles, and SBAs (0x982F7: NOP×4).",
        enabled = false, writes = mutableListOf(PatchWrite(0x982F7, listOf(0xEA, 0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_disable_pseudo_screw", name = "Disable Pseudo Screw Attack",
        description = "Spinning jump during beam charge no longer does screw attack damage (0x824F5: NOP×3).",
        enabled = false, writes = mutableListOf(PatchWrite(0x824F5, listOf(0xEA, 0xEA, 0xEA)))),
    // ── Items & infinite ammo ──
    SmPatch(id = "hex_infinite_missiles", name = "Infinite Missiles",
        description = "Missiles never deplete (0x83EBF: CE → AD).",
        enabled = false, writes = mutableListOf(PatchWrite(0x83EBF, listOf(0xAD)))),
    SmPatch(id = "hex_infinite_supers", name = "Infinite Super Missiles",
        description = "Super missiles never deplete (0x83EC4: CE → AD).",
        enabled = false, writes = mutableListOf(PatchWrite(0x83EC4, listOf(0xAD)))),
    SmPatch(id = "hex_infinite_pbs", name = "Infinite Power Bombs",
        description = "Power bombs never deplete (0x8402E: 8D → AD).",
        enabled = false, writes = mutableListOf(PatchWrite(0x8402E, listOf(0xAD)))),
    SmPatch(id = "hex_morph_ball_no_item", name = "Morph Ball Without Item",
        description = "Allows Morph Ball without collecting it (0x8F7D5: 16 → 00).",
        enabled = false, writes = mutableListOf(PatchWrite(0x8F7D5, listOf(0x00)))),
    SmPatch(id = "hex_space_jump_no_item", name = "Space Jump Without Item",
        description = "Enables space jump without having the item (0x82474: D0 → 80).",
        enabled = false, writes = mutableListOf(PatchWrite(0x82474, listOf(0x80)))),
    SmPatch(id = "hex_speed_boost_no_item", name = "Speed Booster Without Item",
        description = "Samus can run fast without collecting speed booster; no blue echoes until item equipped (0x8178C: 89 → A9).",
        enabled = false, writes = mutableListOf(PatchWrite(0x8178C, listOf(0xA9)))),
    // ── Visual & QOL ──
    SmPatch(id = "hex_no_screen_shake", name = "No Screen Shake",
        description = "Disables all screen shaking effects globally (0x10BAF: NOP×4).",
        enabled = false, writes = mutableListOf(PatchWrite(0x10BAF, listOf(0xEA, 0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_no_suit_flash", name = "No Suit Collection Flash",
        description = "Varia/Gravity suits collect like regular items without the flash and sound (0x20717: NOP×4).",
        enabled = false, writes = mutableListOf(PatchWrite(0x20717, listOf(0xEA, 0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_fast_xray", name = "Fast X-Ray Scope",
        description = "X-ray scope beam widens almost instantly (0x4079A: 0A → 01).",
        enabled = false, writes = mutableListOf(PatchWrite(0x4079A, listOf(0x01)))),
    SmPatch(id = "hex_disable_grapple_camera_scroll", name = "Disable Grapple Slow Camera",
        description = "Turns off the slow scrolling camera when swinging with grapple beam (0xDBDAA: 01 → 00).",
        enabled = false, writes = mutableListOf(PatchWrite(0xDBDAA, listOf(0x00)))),
    // ── Difficulty / challenge ──
    SmPatch(id = "hex_disable_bomb_jump", name = "Disable Bomb Jump",
        description = "Bomb jumping no longer works (0x10B61: NOP×4).",
        enabled = false, writes = mutableListOf(PatchWrite(0x10B61, listOf(0xEA, 0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_no_crystal_flash", name = "Disable Crystal Flash",
        description = "Crystal flash can no longer be used (0x40B5F: NOP×4).",
        enabled = false, writes = mutableListOf(PatchWrite(0x40B5F, listOf(0xEA, 0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_gravity_no_heat_protect", name = "Gravity Suit No Heat Protection",
        description = "Removes Gravity suit's protection against heated rooms, making Varia always useful (0x6E37D: 21 → 01).",
        enabled = false, writes = mutableListOf(PatchWrite(0x6E37D, listOf(0x01)))),
    SmPatch(id = "hex_supers_dont_open_reds", name = "Supers Don't Open Red Doors",
        description = "Super missiles no longer open red/missile doors or eye doors (0x23D58: NOP×5).",
        enabled = false, writes = mutableListOf(PatchWrite(0x23D58, listOf(0xEA, 0xEA, 0xEA, 0xEA, 0xEA)))),
)

/**
 * Ceres escape timer LDA operand at $80:9E0D.
 * The 2-byte immediate operand stores seconds (BCD) in the low byte and minutes (BCD) in the high byte.
 * Vanilla = $0100 → 1 min 0 sec = 60s.
 */
const val CERES_TIMER_OPERAND_SNES = 0x809E0E

/** Config patch: Ceres escape time in seconds. */
val CERES_ESCAPE_PATCH = SmPatch(
    id = "config_ceres_escape_time",
    name = "Ceres Escape Time",
    description = "Sets the Ceres station escape timer in seconds (vanilla: 60). Override when enabled.",
    enabled = false,
    writes = mutableListOf(),
    configType = "ceres_escape_seconds",
    configValue = 60
)

/** Config patch: Beam Damage overrides. Stores per-beam damage values in configData. */
val BEAM_DAMAGE_PATCH = SmPatch(
    id = "config_beam_damage",
    name = "Beam Damage Override",
    description = "Override uncharged and charged beam damage values for all beam types.",
    enabled = false,
    writes = mutableListOf(),
    configType = "beam_damage"
)

/** Config patch: Boss Stats overrides. Stores per-boss HP/damage in configData. */
val BOSS_STATS_PATCH = SmPatch(
    id = "config_boss_stats",
    name = "Boss Stats Override",
    description = "Override HP and damage values for all major bosses and mini-bosses.",
    enabled = false,
    writes = mutableListOf(),
    configType = "boss_stats"
)

/** Config patch: Phantoon AI behavior. Stores timer/movement/position values in configData. */
val PHANTOON_PATCH = SmPatch(
    id = "config_phantoon",
    name = "Phantoon Behavior",
    description = "Deep-dive editor for Phantoon's AI timers, movement speed, flame rain, and wavy effect parameters.",
    enabled = false,
    writes = mutableListOf(),
    configType = "phantoon"
)

/** Config patch: Kraid AI behavior. Stores timer/movement/projectile values in configData. */
val KRAID_PATCH = SmPatch(
    id = "config_kraid",
    name = "Kraid Behavior",
    description = "Deep-dive editor for Kraid's AI timers, movement speed, lint, and fingernail parameters.",
    enabled = false,
    writes = mutableListOf(),
    configType = KRAID_CONFIG_TYPE
)

/** Config patch: Ridley AI behavior. Stores timer/movement/projectile values in configData. */
val RIDLEY_PATCH = SmPatch(
    id = "config_ridley",
    name = "Ridley Behavior",
    description = "Deep-dive editor for Ridley's intro, movement, swoop, hover, and pogo parameters.",
    enabled = false,
    writes = mutableListOf(),
    configType = RIDLEY_CONFIG_TYPE
)

/** Config patch: Draygon AI behavior. Stores timer/movement/projectile values in configData. */
val DRAYGON_PATCH = SmPatch(
    id = "config_draygon",
    name = "Draygon Behavior",
    description = "Deep-dive editor for Draygon's intro, swoop, goop, grab, spiral, foam, and bubble parameters.",
    enabled = false,
    writes = mutableListOf(),
    configType = DRAYGON_CONFIG_TYPE
)

/** Config patch: Spore Spawn AI behavior. Stores timer/movement/death values in configData. */
val SPORE_SPAWN_PATCH = SmPatch(
    id = "config_spore_spawn",
    name = "Spore Spawn Behavior",
    description = "Deep-dive editor for Spore Spawn's movement, hardening, dust, and death parameters.",
    enabled = false,
    writes = mutableListOf(),
    configType = SPORE_SPAWN_CONFIG_TYPE
)

/** Config patch: Crocomire AI behavior. Stores timer/movement/projectile values in configData. */
val CROCOMIRE_PATCH = SmPatch(
    id = "config_crocomire",
    name = "Crocomire Behavior",
    description = "Deep-dive editor for Crocomire's reactions, projectiles, scrolling, bridge, acid, and skeleton parameters.",
    enabled = false,
    writes = mutableListOf(),
    configType = CROCOMIRE_CONFIG_TYPE
)

/** Config patch: Botwoon AI behavior. Stores movement/pathing/projectile values in configData. */
val BOTWOON_PATCH = SmPatch(
    id = "config_botwoon",
    name = "Botwoon Behavior",
    description = "Deep-dive editor for Botwoon's speed, pathing, spit, and death parameters.",
    enabled = false,
    writes = mutableListOf(),
    configType = BOTWOON_CONFIG_TYPE
)

/** Config patch: Torizo / Golden Torizo AI behavior. Stores movement/attack values in configData. */
val TORIZO_PATCH = SmPatch(
    id = "config_torizo",
    name = "Torizo Behavior",
    description = "Deep-dive editor for Bomb Torizo and Golden Torizo spawn, jump, landing, and attack parameters.",
    enabled = false,
    writes = mutableListOf(),
    configType = TORIZO_CONFIG_TYPE
)

/** Config patch: Mother Brain AI behavior. Stores phase/timer/attack values in configData. */
val MOTHER_BRAIN_PATCH = SmPatch(
    id = "config_mother_brain",
    name = "Mother Brain Behavior",
    description = "Deep-dive editor for Mother Brain phase one, phase two, rainbow beam, laser, death, and escape parameters.",
    enabled = false,
    writes = mutableListOf(),
    configType = MOTHER_BRAIN_CONFIG_TYPE
)

internal val DEDICATED_EDITOR_CONFIG_TYPES = setOf(
    "enemy_stats",
    "enemy_drops",
    "enemy_vuln",
    "boss_stats",
    "boss_defeated",
    "phantoon",
    KRAID_CONFIG_TYPE,
    RIDLEY_CONFIG_TYPE,
    DRAYGON_CONFIG_TYPE,
    SPORE_SPAWN_CONFIG_TYPE,
    CROCOMIRE_CONFIG_TYPE,
    BOTWOON_CONFIG_TYPE,
    TORIZO_CONFIG_TYPE,
    MOTHER_BRAIN_CONFIG_TYPE,
)

/** Config patch: Enemy Stats overrides. Stores per-enemy HP/damage in configData. */
val ENEMY_STATS_PATCH = SmPatch(
    id = "config_enemy_stats",
    name = "Enemy Stats Override",
    description = "Override HP and contact damage for common enemies.",
    enabled = false,
    writes = mutableListOf(),
    configType = "enemy_stats"
)

/** Config patch: Enemy Drop Rate overrides. Stores per-enemy drop probabilities in configData. */
val ENEMY_DROP_RATE_PATCH = SmPatch(
    id = "config_enemy_drops",
    name = "Enemy Drop Rates",
    description = "Override item drop probabilities (small/large energy, missile, super, PB) for each enemy.",
    enabled = false,
    writes = mutableListOf(),
    configType = "enemy_drops"
)

/** Config patch: Enemy Vulnerability overrides. Stores per-weapon damage multipliers in configData. */
val ENEMY_VULNERABILITY_PATCH = SmPatch(
    id = "config_enemy_vuln",
    name = "Enemy Vulnerabilities",
    description = "Override per-weapon damage multipliers for each enemy. 0=immune, 2=normal, 4=double.",
    enabled = false,
    writes = mutableListOf(),
    configType = "enemy_vuln"
)

/** Config patch: Samus Physics overrides. Stores movement/gravity/speed values in configData. */
val SAMUS_PHYSICS_PATCH = SmPatch(
    id = "config_samus_physics",
    name = "Samus Physics",
    description = "Override Samus movement physics — jump heights, gravity, run speed, air control, and more.",
    enabled = false,
    writes = mutableListOf(),
    configType = "samus_physics"
)

/** Config patch: Bomb behavior. Stores active bomb cap and timing values in configData. */
val BOMBS_PATCH = SmPatch(
    id = "config_bombs",
    name = "Bombs",
    description = "Control active normal bomb count, lay cooldown, bomb fuse timing, and explosion animation timing.",
    enabled = false,
    writes = mutableListOf(),
    configType = BOMB_CONFIG_TYPE
)

/** Config patch: Item fanfare/message timing. Stores frame count in configData. */
val FANFARE_PATCH = SmPatch(
    id = "config_fanfares",
    name = "Fanfares",
    description = "Control item fanfare box duration and room-music resume timing.",
    enabled = false,
    writes = mutableListOf(),
    configType = FANFARE_CONFIG_TYPE
)

/** Config patch: generated pause-map room name display. */
val ROOM_NAME_PAUSE_MAP_PATCH = SmPatch(
    id = "config_room_name_pause_map",
    name = "Room Name Pause Map",
    description = "Draw the current room name on the pause map using SMEDIT room metadata and project overrides.",
    enabled = false,
    writes = mutableListOf(),
    configType = "room_name_pause_map"
)

/** Legacy/superseded patch IDs — removed on seed to avoid duplicates from old configs. */
internal val LEGACY_PATCH_IDS = setOf(
    "respin", "fast_doors", "no_fanfare", "blue_speed_air", "no_walljump_kick", "instant_stop",
    "no_beeping", "energy_free_shinesparks", "fast_saves", "enable_moonwalk", "skip_ceres", "fast_mb_cutscene",
    "hex_no_spin_speed_loss", "hex_keep_blue_speed", "hex_no_walljump_kick", "hex_no_skid",
    "hex_faster_charged_shots_demo",
    // Old broken IPS-based boss defeated patches (wrote to unused ROM space)
    "bundled_boss_kraid_defeated", "bundled_boss_phantoon_defeated",
    "bundled_boss_ridley_defeated", "bundled_boss_draygon_defeated",
    "bundled_boss_all_defeated",
)

/** Config patch: Boss Defeated Flags. Toggleable per-boss defeat flags via configData. */
val BOSS_DEFEATED_PATCH = SmPatch(
    id = "config_boss_defeated",
    name = "Boss Defeated Flags",
    description = "Mark bosses as already defeated. Rooms load in post-boss state and Tourian unlocks when all four main bosses are defeated.",
    enabled = false,
    writes = mutableListOf(),
    configType = "boss_defeated"
)

/**
 * Boss flag definitions verified against vanilla ROM E629 conditions.
 * Each boss flag is a bit in a per-area byte at WRAM $7E:D828+area.
 */
data class BossFlagDef(val key: String, val name: String, val wramAddr: Int, val bit: Int)

val BOSS_FLAG_DEFS = listOf(
    BossFlagDef("kraid",    "Kraid",              0xD829, 0x01),
    BossFlagDef("phantoon", "Phantoon",           0xD82B, 0x01),
    BossFlagDef("ridley",   "Ridley",             0xD82A, 0x02),
    BossFlagDef("draygon",  "Draygon",            0xD82C, 0x02),
    BossFlagDef("spore",    "Spore Spawn",        0xD829, 0x02),
    BossFlagDef("croc",     "Crocomire",          0xD82A, 0x04),
    BossFlagDef("botwoon",  "Botwoon",            0xD82C, 0x01),
)

// ─── Controller Configuration ──────────────────────────────────

/** Config patch: Controller button mapping. */
val CONTROLLER_CONFIG_PATCH = SmPatch(
    id = "config_controller",
    name = "Controller Configuration",
    description = "Remap the default button assignments for Shot, Jump, Dash, Item Select/Cancel, and Aim.",
    enabled = false,
    writes = mutableListOf(),
    configType = "controller_config"
)

data class ControllerSlot(val key: String, val name: String, val tableIndex: Int, val defaultButton: Int)

/** The 7 configurable actions in the order they appear in the ROM table at $82:F575 (PC 0x017575). */
val CONTROLLER_SLOTS = listOf(
    ControllerSlot("shot",        "Shot",        0, 0x0040),
    ControllerSlot("jump",        "Jump",        1, 0x0080),
    ControllerSlot("dash",        "Dash (Run)",  2, 0x8000),
    ControllerSlot("item_select", "Item Select", 3, 0x2000),
    ControllerSlot("item_cancel", "Item Cancel", 4, 0x4000),
    ControllerSlot("angle_down",  "Angle Down",  5, 0x0020),
    ControllerSlot("angle_up",    "Angle Up",    6, 0x0010),
)

data class SnesButton(val name: String, val bitmask: Int)

val SNES_BUTTONS = listOf(
    SnesButton("A",      0x0080),
    SnesButton("B",      0x8000),
    SnesButton("X",      0x0040),
    SnesButton("Y",      0x4000),
    SnesButton("L",      0x0020),
    SnesButton("R",      0x0010),
    SnesButton("Select", 0x2000),
)

/** PC offset of the 7×2-byte default button table in the ROM. */
const val CONTROLLER_TABLE_PC = 0x017575

/**
 * Build the ASM payload + hook for boss-defeated flags at export time.
 * Routine lives at $DF:F040 (PC $2FF040). Hooks the per-frame main loop
 * dispatch at $82:896E (JSL $8289EF) to chain through our code.
 *
 * Generated code: JSL $8289EF, then ORA each enabled boss flag into WRAM, RTL.
 */
fun buildBossDefeatedPayload(enabledBosses: Set<String>): Pair<List<Int>, List<Int>> {
    if (enabledBosses.isEmpty()) return emptyList<Int>() to emptyList()

    val code = mutableListOf<Int>()
    // Chain to original: JSL $8289EF
    code.addAll(listOf(0x22, 0xEF, 0x89, 0x82))
    code.add(0x08)        // PHP
    code.addAll(listOf(0xC2, 0x20)) // REP #$20

    // Group flags by WRAM address to minimize writes
    val byAddr = mutableMapOf<Int, Int>()
    for (flag in BOSS_FLAG_DEFS) {
        if (flag.key in enabledBosses) {
            byAddr[flag.wramAddr] = (byAddr[flag.wramAddr] ?: 0) or flag.bit
        }
    }

    for ((addr, bits) in byAddr) {
        code.addAll(listOf(0xAF, addr and 0xFF, (addr shr 8) and 0xFF, 0x7E))  // LDA $7E:xxxx
        code.addAll(listOf(0x09, bits and 0xFF, 0x00))                          // ORA #$00xx
        code.addAll(listOf(0x8F, addr and 0xFF, (addr shr 8) and 0xFF, 0x7E))   // STA $7E:xxxx
    }

    code.add(0x28)  // PLP
    code.add(0x6B)  // RTL

    // Hook: replace JSL $8289EF at $82:896E (PC $1096E) with JSL $DFF040
    val hook = listOf(0x22, 0x40, 0xF0, 0xDF)

    return code to hook
}

package com.supermetroid.editor.headless

import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.SmPatch

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

object SmeditPatchCatalog {
    fun defaultPatches(): List<SmPatch> =
        hardcodedPatches() + configPatches() + unsupportedConfigPatches() + PatchRepository.loadBundledPatches()

    fun supportedConfigTypes(): Set<String> =
        setOf(CERES_ESCAPE_CONFIG_TYPE, BOMB_CONFIG_TYPE, FANFARE_CONFIG_TYPE)

    private fun hardcodedPatches(): List<SmPatch> = listOf(
        SmPatch(
            id = "hex_hyper_beam",
            name = "Hyper Beam",
            description = "Start with Hyper Beam enabled. Headless build v1 lists this patch but does not emit the desktop per-frame hook yet.",
            enabled = false,
            writes = mutableListOf(),
            configType = "hyper_beam",
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
    )

    private fun unsupportedConfigPatches(): List<SmPatch> = listOf(
        unsupportedConfigPatch(
            id = "config_beam_damage",
            name = "Beam Damage",
            configType = "beam_damage",
            description = "Desktop-only v1: beam damage table writer has not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_boss_stats",
            name = "Boss Stats",
            configType = "boss_stats",
            description = "Desktop-only v1: boss enemy-header stat writer has not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_phantoon",
            name = "Phantoon",
            configType = "phantoon",
            description = "Desktop-only v1: Phantoon behavior fields have not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_kraid",
            name = "Kraid",
            configType = "kraid",
            description = "Desktop-only v1: Kraid behavior fields have not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_ridley",
            name = "Ridley",
            configType = "ridley",
            description = "Desktop-only v1: Ridley behavior fields have not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_draygon",
            name = "Draygon",
            configType = "draygon",
            description = "Desktop-only v1: Draygon behavior fields have not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_spore_spawn",
            name = "Spore Spawn",
            configType = "spore_spawn",
            description = "Desktop-only v1: Spore Spawn behavior fields have not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_crocomire",
            name = "Crocomire",
            configType = "crocomire",
            description = "Desktop-only v1: Crocomire behavior fields have not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_botwoon",
            name = "Botwoon",
            configType = "botwoon",
            description = "Desktop-only v1: Botwoon behavior fields have not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_torizo",
            name = "Torizo",
            configType = "torizo",
            description = "Desktop-only v1: Torizo behavior fields have not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_mother_brain",
            name = "Mother Brain",
            configType = "mother_brain",
            description = "Desktop-only v1: Mother Brain behavior fields have not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_enemy_stats",
            name = "Enemy Stats",
            configType = "enemy_stats",
            description = "Desktop-only v1: enemy stats and AI/GFX pointer writer has not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_enemy_drops",
            name = "Enemy Drops",
            configType = "enemy_drops",
            description = "Desktop-only v1: enemy drop table writer has not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_enemy_vuln",
            name = "Enemy Vulnerabilities",
            configType = "enemy_vuln",
            description = "Desktop-only v1: enemy vulnerability table writer has not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_samus_physics",
            name = "Samus Physics",
            configType = "samus_physics",
            description = "Desktop-only v1: Samus physics byte fields have not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_controller",
            name = "Controller Config",
            configType = "controller_config",
            description = "Desktop-only v1: controller remapping table writer has not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_room_name_pause_map",
            name = "Room Names on Pause Map",
            configType = "room_name_pause_map",
            description = "Desktop-only v1: generated room-name pause map payload has not been moved to the headless SDK yet.",
        ),
        unsupportedConfigPatch(
            id = "config_boss_defeated",
            name = "Boss Defeated Flags",
            configType = "boss_defeated",
            description = "Desktop-only v1: combined per-frame hook generation has not been moved to the headless SDK yet.",
        ),
    )

    private fun unsupportedConfigPatch(
        id: String,
        name: String,
        configType: String,
        description: String,
    ): SmPatch =
        SmPatch(
            id = id,
            name = name,
            description = description,
            enabled = false,
            configType = configType,
        )
}

package com.supermetroid.editor.headless

internal data class HeadlessBeamDef(
    val key: String,
    val label: String,
    val defaultDamage: Int,
    val entryIndex: Int,
    val chargedEntryIndex: Int,
) {
    val snesAddress: Int get() = BEAM_DAMAGE_TABLE_SNES + entryIndex * BEAM_DAMAGE_ENTRY_STRIDE
    val chargedSnesAddress: Int get() = BEAM_DAMAGE_TABLE_SNES + chargedEntryIndex * BEAM_DAMAGE_ENTRY_STRIDE
}

internal data class HeadlessEnemyDef(
    val key: String,
    val label: String,
    val speciesId: Int,
    val defaultHp: Int,
    val defaultDamage: Int,
    val category: String,
)

internal data class HeadlessEnemyHeaderField(
    val suffix: String,
    val label: String,
    val offset: Int,
    val description: String,
)

internal data class HeadlessDropSlot(
    val index: Int,
    val label: String,
)

internal data class HeadlessWeaponSlot(
    val index: Int,
    val label: String,
    val defaultValue: Int = 2,
)

internal data class HeadlessBossStatField(
    val key: String,
    val label: String,
    val speciesId: Int,
    val offset: Int,
    val defaultValue: Int,
    val category: String,
    val additionalSpeciesIds: List<Int> = emptyList(),
) {
    val writeSpeciesIds: List<Int> get() = (listOf(speciesId) + additionalSpeciesIds).distinct()
}

internal data class HeadlessPhysicsField(
    val key: String,
    val label: String,
    val pcOffset: Int,
    val defaultValue: Int,
    val category: String,
    val description: String,
)

internal data class HeadlessControllerSlot(
    val key: String,
    val label: String,
    val tableIndex: Int,
    val defaultButton: Int,
)

internal data class HeadlessSnesButton(
    val label: String,
    val bitmask: Int,
)

internal data class HeadlessBossFlagDef(
    val key: String,
    val label: String,
    val wramAddr: Int,
    val bit: Int,
)

internal val HEADLESS_BEAMS = listOf(
    HeadlessBeamDef("power", "Power Beam", 20, entryIndex = 0, chargedEntryIndex = 12),
    HeadlessBeamDef("ice", "Ice Beam", 30, entryIndex = 5, chargedEntryIndex = 17),
    HeadlessBeamDef("spazer", "Spazer", 40, entryIndex = 1, chargedEntryIndex = 13),
    HeadlessBeamDef("wave", "Wave Beam", 50, entryIndex = 6, chargedEntryIndex = 19),
    HeadlessBeamDef("plasma", "Plasma", 150, entryIndex = 7, chargedEntryIndex = 18),
    HeadlessBeamDef("is", "Ice + Spazer", 60, entryIndex = 2, chargedEntryIndex = 14),
    HeadlessBeamDef("iw", "Ice + Wave", 60, entryIndex = 8, chargedEntryIndex = 20),
    HeadlessBeamDef("ws", "Wave + Spazer", 70, entryIndex = 9, chargedEntryIndex = 21),
    HeadlessBeamDef("iws", "Ice + Wave + Spazer", 100, entryIndex = 3, chargedEntryIndex = 15),
    HeadlessBeamDef("ip", "Ice + Plasma", 200, entryIndex = 11, chargedEntryIndex = 22),
    HeadlessBeamDef("wp", "Wave + Plasma", 250, entryIndex = 10, chargedEntryIndex = 23),
    HeadlessBeamDef("iwp", "Ice + Wave + Plasma", 300, entryIndex = 4, chargedEntryIndex = 16),
)

internal val HEADLESS_ENEMY_DEFS = listOf(
    HeadlessEnemyDef("zoomer", "Zoomer", 0xDCFF, 15, 5, "Crawler"),
    HeadlessEnemyDef("geemer_horiz", "Geemer (horizontal)", 0xDC3F, 15, 5, "Crawler"),
    HeadlessEnemyDef("sidehopper", "Sidehopper", 0xD93F, 60, 20, "Hopper"),
    HeadlessEnemyDef("sidehopper_large", "Sidehopper (large)", 0xD97F, 120, 80, "Hopper"),
    HeadlessEnemyDef("dessgeega", "Dessgeega", 0xD9BF, 320, 80, "Hopper"),
    HeadlessEnemyDef("tripper", "Tripper", 0xD7FF, 20, 40, "Flyer"),
    HeadlessEnemyDef("reo", "Reo", 0xD87F, 20, 40, "Flyer"),
    HeadlessEnemyDef("waver", "Waver", 0xD63F, 100, 16, "Flyer"),
    HeadlessEnemyDef("ripper", "Ripper", 0xD47F, 200, 5, "Flyer"),
    HeadlessEnemyDef("ripper2", "Ripper II", 0xD3FF, 400, 20, "Flyer"),
    HeadlessEnemyDef("kihunter", "Kihunter", 0xDFBF, 20, 40, "Flyer"),
    HeadlessEnemyDef("kihunter_green", "Kihunter (green)", 0xE03F, 400, 30, "Flyer"),
    HeadlessEnemyDef("sciser", "Sciser", 0xD77F, 100, 12, "Crawler"),
    HeadlessEnemyDef("zeela", "Zeela", 0xDC7F, 100, 16, "Crawler"),
    HeadlessEnemyDef("sova", "Sova", 0xDD3F, 100, 16, "Crawler"),
    HeadlessEnemyDef("beetom", "Beetom", 0xDCBF, 50, 8, "Crawler"),
    HeadlessEnemyDef("rinka", "Rinka", 0xD23F, 10, 40, "Spawner"),
    HeadlessEnemyDef("zeb", "Zeb", 0xF193, 20, 8, "Spawner"),
    HeadlessEnemyDef("zebbo", "Zebbo", 0xF1D3, 20, 8, "Spawner"),
    HeadlessEnemyDef("gamet", "Gamet", 0xF213, 20, 12, "Spawner"),
    HeadlessEnemyDef("oum", "Oum", 0xD7BF, 200, 20, "Aquatic"),
    HeadlessEnemyDef("skultera", "Skultera", 0xD6FF, 100, 12, "Aquatic"),
    HeadlessEnemyDef("yard", "Yard", 0xDBBF, 100, 16, "Aquatic"),
    HeadlessEnemyDef("pirate_basic", "Space Pirate", 0xF353, 20, 15, "Pirate"),
    HeadlessEnemyDef("pirate_norfair", "Space Pirate (Norfair)", 0xF413, 600, 40, "Pirate"),
    HeadlessEnemyDef("pirate_maridia", "Space Pirate (Maridia)", 0xF453, 600, 40, "Pirate"),
    HeadlessEnemyDef("pirate_tourian", "Space Pirate (Tourian)", 0xF493, 800, 50, "Pirate"),
    HeadlessEnemyDef("pirate_mk2_norfair", "Space Pirate Mk.II (Norfair)", 0xF593, 1000, 60, "Pirate"),
    HeadlessEnemyDef("pirate_mk2_tourian", "Space Pirate Mk.II (Tourian)", 0xF613, 1200, 70, "Pirate"),
    HeadlessEnemyDef("metroid", "Big Metroid", 0xEEBF, 1, 0, "Special"),
    HeadlessEnemyDef("fireflea", "Fireflea", 0xD6BF, 1, 0, "Special"),
    HeadlessEnemyDef("cacatac", "Cacatac", 0xCFFF, 200, 20, "Special"),
    HeadlessEnemyDef("magdollite", "Magdollite", 0xD4BF, 200, 30, "Special"),
    HeadlessEnemyDef("boyon", "Boyon", 0xCEBF, 100, 16, "Special"),
)

internal val ENEMY_HEADER_POINTER_FIELDS = listOf(
    HeadlessEnemyHeaderField("_initAi", "Init AI", 0x12, "Runs once when enemy spawns"),
    HeadlessEnemyHeaderField("_mainAi", "Main AI", 0x16, "Runs every frame"),
    HeadlessEnemyHeaderField("_touchAi", "Touch AI", 0x30, "Runs when Samus touches enemy"),
    HeadlessEnemyHeaderField("_shotAi", "Shot AI", 0x32, "Runs when projectile hits enemy"),
    HeadlessEnemyHeaderField("_hurtAi", "Hurt AI", 0x1C, "Runs when enemy takes damage"),
    HeadlessEnemyHeaderField("_frozenAi", "Frozen AI", 0x1E, "Runs while enemy is frozen"),
    HeadlessEnemyHeaderField("_grappleAi", "Grapple AI", 0x1A, "Reaction to Grapple Beam"),
    HeadlessEnemyHeaderField("_deathAnim", "Death Anim", 0x22, "Death explosion/effect type"),
    HeadlessEnemyHeaderField("_extraGfx", "Extra GFX", 0x18, "Pointer to additional graphics"),
    HeadlessEnemyHeaderField("_pbVuln", "PB Vuln", 0x28, "0000=vulnerable, xx80=immune"),
)

internal val HEADLESS_DROP_SLOTS = listOf(
    HeadlessDropSlot(0, "Small Energy"),
    HeadlessDropSlot(1, "Large Energy"),
    HeadlessDropSlot(2, "Missile"),
    HeadlessDropSlot(3, "Nothing"),
    HeadlessDropSlot(4, "Super Missile"),
    HeadlessDropSlot(5, "Power Bomb"),
)

internal val HEADLESS_WEAPON_SLOTS = listOf(
    HeadlessWeaponSlot(0, "Unused"),
    HeadlessWeaponSlot(1, "Pseudo-Screw"),
    HeadlessWeaponSlot(2, "Charge/Hyper"),
    HeadlessWeaponSlot(3, "Screw Attack"),
    HeadlessWeaponSlot(4, "Speed Jump"),
    HeadlessWeaponSlot(5, "Speed Running"),
    HeadlessWeaponSlot(6, "Power Bombs"),
    HeadlessWeaponSlot(7, "Bombs"),
    HeadlessWeaponSlot(8, "Super Missiles"),
    HeadlessWeaponSlot(9, "Missiles"),
    HeadlessWeaponSlot(10, "Wave+Ice+Plasma"),
    HeadlessWeaponSlot(11, "Ice+Plasma"),
    HeadlessWeaponSlot(12, "Wave+Plasma"),
    HeadlessWeaponSlot(13, "Plasma"),
    HeadlessWeaponSlot(14, "Wave+Ice+Spazer"),
    HeadlessWeaponSlot(15, "Ice+Spazer"),
    HeadlessWeaponSlot(16, "Wave+Spazer"),
    HeadlessWeaponSlot(17, "Spazer"),
    HeadlessWeaponSlot(18, "Wave+Ice"),
    HeadlessWeaponSlot(19, "Ice"),
    HeadlessWeaponSlot(20, "Wave"),
    HeadlessWeaponSlot(21, "Normal"),
)

internal val HEADLESS_BOSS_STAT_FIELDS = listOf(
    HeadlessBossStatField("kraid_hp", "Kraid HP", 0xE2BF, 4, 1000, "Kraid", listOf(0xE2FF)),
    HeadlessBossStatField("kraid_contact", "Contact Damage", 0xE2BF, 6, 20, "Kraid", listOf(0xE2FF)),
    HeadlessBossStatField("kraid_belly_spike", "Belly Spike Damage", 0xE33F, 6, 10, "Kraid", listOf(0xE37F, 0xE3BF)),
    HeadlessBossStatField("kraid_claw", "Flying Claw Damage", 0xE3FF, 6, 20, "Kraid"),
    HeadlessBossStatField("phantoon_hp", "Phantoon HP", 0xE4BF, 4, 2500, "Phantoon"),
    HeadlessBossStatField("phantoon_contact", "Contact Damage", 0xE4BF, 6, 40, "Phantoon"),
    HeadlessBossStatField("phantoon_flame1", "Eye Contact", 0xE4FF, 6, 40, "Phantoon"),
    HeadlessBossStatField("phantoon_flame2", "Tentacles Contact", 0xE53F, 6, 40, "Phantoon"),
    HeadlessBossStatField("phantoon_flame3", "Mouth Contact", 0xE57F, 6, 40, "Phantoon"),
    HeadlessBossStatField("ridley_hp", "Ridley HP", 0xE17F, 4, 18000, "Ridley"),
    HeadlessBossStatField("ridley_contact", "Contact Damage", 0xE17F, 6, 160, "Ridley"),
    HeadlessBossStatField("draygon_hp", "Draygon HP", 0xDE3F, 4, 6000, "Draygon"),
    HeadlessBossStatField("draygon_body", "Body Contact", 0xDE3F, 6, 160, "Draygon"),
    HeadlessBossStatField("draygon_eye", "Eye Contact", 0xDE7F, 6, 160, "Draygon"),
    HeadlessBossStatField("draygon_tail", "Tail Swipe", 0xDEBF, 6, 160, "Draygon"),
    HeadlessBossStatField("draygon_arms", "Arm Grab", 0xDEFF, 6, 160, "Draygon"),
    HeadlessBossStatField("mb_phase1_hp", "Phase 1 HP (in glass)", 0xEC3F, 4, 18000, "Mother Brain"),
    HeadlessBossStatField("mb_phase2_hp", "Phase 2 HP (walking)", 0xEC7F, 4, 18000, "Mother Brain"),
    HeadlessBossStatField("mb_phase1_contact", "Phase 1 Contact", 0xEC3F, 6, 120, "Mother Brain"),
    HeadlessBossStatField("mb_phase2_contact", "Phase 2 Contact", 0xEC7F, 6, 120, "Mother Brain"),
    HeadlessBossStatField("sporespawn_hp", "Spore Spawn HP", 0xDF3F, 4, 960, "Spore Spawn"),
    HeadlessBossStatField("sporespawn_contact", "Contact Damage", 0xDF3F, 6, 12, "Spore Spawn"),
    HeadlessBossStatField("crocomire_hp", "Crocomire HP", 0xDDBF, 4, 32767, "Crocomire"),
    HeadlessBossStatField("crocomire_contact", "Contact Damage", 0xDDBF, 6, 40, "Crocomire"),
    HeadlessBossStatField("botwoon_hp", "Botwoon HP", 0xF293, 4, 3000, "Botwoon"),
    HeadlessBossStatField("botwoon_contact", "Contact Damage", 0xF293, 6, 120, "Botwoon"),
    HeadlessBossStatField("golden_torizo_hp", "Golden Torizo HP", 0xEF7F, 4, 13500, "Golden Torizo"),
    HeadlessBossStatField("golden_torizo_contact", "Contact Damage", 0xEF7F, 6, 160, "Golden Torizo"),
    HeadlessBossStatField("torizo_hp", "Bomb Torizo HP", 0xEEFF, 4, 800, "Torizo"),
    HeadlessBossStatField("torizo_contact", "Contact Damage", 0xEEFF, 6, 8, "Torizo"),
)

internal val HEADLESS_PHYSICS_FIELDS = listOf(
    HeadlessPhysicsField("jump_height", "Jump Height", 0x081EB9, 0x04, "Jump", "Standing/springball height"),
    HeadlessPhysicsField("hijump_height", "Jump Height (Hi-Jump)", 0x081EC5, 0x06, "Jump", "Height with Hi-Jump Boots"),
    HeadlessPhysicsField("walljump", "Walljump Height", 0x081ED1, 0x04, "Jump", "Walljump height"),
    HeadlessPhysicsField("walljump_hijump", "Walljump (Hi-Jump)", 0x081EDD, 0x05, "Jump", "Walljump height with Hi-Jump Boots"),
    HeadlessPhysicsField("jump_water", "Jump Height (Water)", 0x081EBB, 0x01, "Jump", "Jump height underwater without Gravity Suit"),
    HeadlessPhysicsField("hijump_water", "Jump (Hi-Jump, Water)", 0x081EC7, 0x02, "Jump", "Hi-Jump height underwater"),
    HeadlessPhysicsField("walljump_water", "Walljump (Water)", 0x081ED3, 0x00, "Jump", "Walljump height underwater"),
    HeadlessPhysicsField("jump_lava", "Jump Height (Lava)", 0x081EBD, 0x02, "Jump", "Jump height in lava/acid"),
    HeadlessPhysicsField("hijump_lava", "Jump (Hi-Jump, Lava)", 0x081EC9, 0x03, "Jump", "Hi-Jump height in lava"),
    HeadlessPhysicsField("walljump_lava", "Walljump (Lava)", 0x081ED5, 0x02, "Jump", "Walljump height in lava"),
    HeadlessPhysicsField("gravity", "Gravity", 0x081EA2, 0x1C, "Gravity & Falling", "Downward acceleration per frame"),
    HeadlessPhysicsField("max_fall", "Max Fall Speed", 0x081110, 0x05, "Gravity & Falling", "Terminal fall velocity"),
    HeadlessPhysicsField("run_accel", "Run Acceleration", 0x081F64, 0x30, "Running", "Ground acceleration per frame"),
    HeadlessPhysicsField("run_max", "Run Max Speed", 0x081F65, 0x02, "Running", "Max run speed"),
    HeadlessPhysicsField("air_spin", "Air Speed (Spin Jump)", 0x081F7D, 0x01, "Air Control", "Horizontal speed mid-air during spin jump"),
    HeadlessPhysicsField("air_normal", "Air Speed (Normal Jump)", 0x081F71, 0x01, "Air Control", "Horizontal speed mid-air during normal jump/fall"),
    HeadlessPhysicsField("air_physics", "Air Physics Mode", 0x081B2F, 0x02, "Air Control", "Mid-air control mode"),
)

internal val HEADLESS_CONTROLLER_SLOTS = listOf(
    HeadlessControllerSlot("shot", "Shot", 0, 0x0040),
    HeadlessControllerSlot("jump", "Jump", 1, 0x0080),
    HeadlessControllerSlot("dash", "Dash (Run)", 2, 0x8000),
    HeadlessControllerSlot("item_select", "Item Select", 3, 0x2000),
    HeadlessControllerSlot("item_cancel", "Item Cancel", 4, 0x4000),
    HeadlessControllerSlot("angle_down", "Angle Down", 5, 0x0020),
    HeadlessControllerSlot("angle_up", "Angle Up", 6, 0x0010),
)

internal val HEADLESS_SNES_BUTTONS = listOf(
    HeadlessSnesButton("A", 0x0080),
    HeadlessSnesButton("B", 0x8000),
    HeadlessSnesButton("X", 0x0040),
    HeadlessSnesButton("Y", 0x4000),
    HeadlessSnesButton("L", 0x0020),
    HeadlessSnesButton("R", 0x0010),
    HeadlessSnesButton("Select", 0x2000),
)

internal val HEADLESS_BOSS_FLAGS = listOf(
    HeadlessBossFlagDef("kraid", "Kraid", 0xD829, 0x01),
    HeadlessBossFlagDef("phantoon", "Phantoon", 0xD82B, 0x01),
    HeadlessBossFlagDef("ridley", "Ridley", 0xD82A, 0x02),
    HeadlessBossFlagDef("draygon", "Draygon", 0xD82C, 0x02),
    HeadlessBossFlagDef("spore", "Spore Spawn", 0xD829, 0x02),
    HeadlessBossFlagDef("croc", "Crocomire", 0xD82A, 0x04),
    HeadlessBossFlagDef("botwoon", "Botwoon", 0xD82C, 0x01),
)

internal val MAIN_BOSS_FLAG_KEYS = setOf("kraid", "phantoon", "ridley", "draygon")

internal fun buildHeadlessPerFrameHook(
    enabledBosses: Set<String>,
    hyperBeam: Boolean,
    infiniteBlueSuit: Boolean,
): List<Int> {
    if (enabledBosses.isEmpty() && !hyperBeam && !infiniteBlueSuit) return emptyList()

    val code = mutableListOf<Int>()
    code.addAll(listOf(0x22, 0xEF, 0x89, 0x82)) // JSL $8289EF
    code.add(0x08) // PHP
    code.addAll(listOf(0xC2, 0x20)) // REP #$20

    code.addAll(listOf(0xAD, 0x9B, 0x07)) // LDA $079B
    code.addAll(listOf(0xC9, 0x58, 0xDD)) // CMP #$DD58
    val beqPos = code.size
    code.addAll(listOf(0xF0, 0x00)) // BEQ done; patched below

    if (enabledBosses.isNotEmpty()) {
        val byAddr = linkedMapOf<Int, Int>()
        for (flag in HEADLESS_BOSS_FLAGS) {
            if (flag.key in enabledBosses) {
                byAddr[flag.wramAddr] = (byAddr[flag.wramAddr] ?: 0) or flag.bit
            }
        }

        val bossStatueEvents = mapOf(
            "phantoon" to (0xD820 to 0x40),
            "ridley" to (0xD820 to 0x80),
            "draygon" to (0xD821 to 0x01),
            "kraid" to (0xD821 to 0x02),
        )
        for ((boss, addrBit) in bossStatueEvents) {
            if (boss in enabledBosses) {
                byAddr[addrBit.first] = (byAddr[addrBit.first] ?: 0) or addrBit.second
            }
        }

        if (MAIN_BOSS_FLAG_KEYS.all { it in enabledBosses }) {
            byAddr[0xD821] = (byAddr[0xD821] ?: 0) or 0x04
        }

        for ((addr, bits) in byAddr) {
            code.addAll(listOf(0xAF, addr and 0xFF, (addr shr 8) and 0xFF, 0x7E))
            code.addAll(listOf(0x09, bits and 0xFF, 0x00))
            code.addAll(listOf(0x8F, addr and 0xFF, (addr shr 8) and 0xFF, 0x7E))
        }
    }

    if (hyperBeam) {
        code.addAll(listOf(0xA9, 0x00, 0x80))
        code.addAll(listOf(0x8F, 0x76, 0x0A, 0x7E))
    }

    if (infiniteBlueSuit) {
        code.addAll(listOf(0xA9, 0x00, 0x04))
        code.addAll(listOf(0x8F, 0x3E, 0x0B, 0x7E))
    }

    code.add(0x28) // PLP
    code.add(0x6B) // RTL

    val plpPos = code.size - 2
    val branchOffset = plpPos - (beqPos + 2)
    require(branchOffset in 0..127) {
        "combined per-frame hook body is too large for its Mother Brain room guard branch"
    }
    code[beqPos + 1] = branchOffset

    return code
}

internal const val BEAM_DAMAGE_TABLE_SNES = 0x938431
internal const val BEAM_DAMAGE_ENTRY_STRIDE = 22
internal const val ENEMY_DATA_BANK_SNES = 0xB40000
internal const val ENEMY_HEADER_HP_OFFSET = 0x04
internal const val ENEMY_HEADER_CONTACT_DAMAGE_OFFSET = 0x06
internal const val ENEMY_HEADER_DROP_TABLE_PTR_OFFSET = 0x3A
internal const val ENEMY_HEADER_VULNERABILITY_TABLE_PTR_OFFSET = 0x3C
internal const val ENEMY_DROP_TABLE_BYTES = 6
internal const val ENEMY_VULNERABILITY_TABLE_BYTES = 22
internal const val CONTROLLER_TABLE_PC = 0x017575
internal const val PER_FRAME_HOOK_PAYLOAD_PC = 0x2FF040
internal const val PER_FRAME_HOOK_PATCH_PC = 0x01096E
internal val PER_FRAME_HOOK_JSL = listOf(0x22, 0x40, 0xF0, 0xDF)

package com.supermetroid.editor.rom

/**
 * Source-backed renderer for the Bomb Torizo / Golden Torizo family.
 *
 * Both encounters share one bank-$AF enemy sheet and the same bank-$AA
 * extended spritemaps. Bank $AA replaces small VRAM ranges at runtime for
 * blinking eyes, Bomb Torizo damage, and Golden Torizo's egg-release belly.
 * Golden Torizo eggs are a third, room-loaded graphics owner. Keeping those
 * owners separate here prevents the editor from presenting runtime overlays
 * as independent enemy sheets.
 */
class TorizoSpritemap(private val romParser: RomParser) {

    companion object {
        const val BOMB_SPECIES_ID = 0xEEFF
        const val BOMB_ORB_SPECIES_ID = 0xEF3F
        const val GOLDEN_SPECIES_ID = 0xEF7F
        const val GOLDEN_ORB_SPECIES_ID = 0xEFBF

        const val BASE_TILES_SNES = 0xAFC200
        const val BASE_TILES_SIZE = 0x2000
        const val RUNTIME_TILES_SNES = 0xAAB279
        const val RUNTIME_TILES_SIZE = 0x0600
        const val EGG_TILES_SNES = 0xAFE200
        const val EGG_TILES_SIZE = 0x0600
        const val CRUMBLING_CHOZO_TILES_SNES = 0xADB200
        const val CRUMBLING_CHOZO_TILES_SIZE = 0x0400

        private const val PHYSICAL_TILE_COUNT = 0x200
        private const val SPECIES_PHYSICAL_BASE = 0x100
        private const val EGG_PHYSICAL_BASE = 0x0D0
        private const val CRUMBLING_CHOZO_PHYSICAL_BASE = 0x0E0
        private const val COMMON_SPRITE_PALETTE_5_SNES = 0x9A81A0
        private const val ORB_PALETTE_SNES = 0xAA8687

        private const val GOLD_HEALTH_PALETTE_1_SNES = 0x848032
        private const val GOLD_HEALTH_PALETTE_2_SNES = 0x848132

        val PALETTE_STAGES = listOf(
            PaletteStageDef("bomb-statue", "Bomb · statue", 0xAA86A7, 0xAA86A7, Encounter.BOMB),
            PaletteStageDef("bomb-initial", "Bomb · awakening", 0xAA86C7, 0xAA86E7, Encounter.BOMB),
            PaletteStageDef("bomb-normal", "Bomb · active", 0xAA8707, 0xAA8727, Encounter.BOMB),
            PaletteStageDef("gold-initial", "Golden · awakening", 0xAA8747, 0xAA8767, Encounter.GOLDEN),
            PaletteStageDef("gold-active", "Golden · active", 0xAA8787, 0xAA87A7, Encounter.GOLDEN),
            PaletteStageDef("gold-hp-overflow", "Golden · HP 14336+", GOLD_HEALTH_PALETTE_1_SNES + 7 * 0x20, GOLD_HEALTH_PALETTE_2_SNES + 7 * 0x20, Encounter.GOLDEN),
            PaletteStageDef("gold-hp-full", "Golden · HP 12288–14335 (full)", GOLD_HEALTH_PALETTE_1_SNES + 6 * 0x20, GOLD_HEALTH_PALETTE_2_SNES + 6 * 0x20, Encounter.GOLDEN),
            PaletteStageDef("gold-hp-10240", "Golden · HP 10240–12287", GOLD_HEALTH_PALETTE_1_SNES + 5 * 0x20, GOLD_HEALTH_PALETTE_2_SNES + 5 * 0x20, Encounter.GOLDEN),
            PaletteStageDef("gold-hp-8192", "Golden · HP 8192–10239", GOLD_HEALTH_PALETTE_1_SNES + 4 * 0x20, GOLD_HEALTH_PALETTE_2_SNES + 4 * 0x20, Encounter.GOLDEN),
            PaletteStageDef("gold-hp-6144", "Golden · HP 6144–8191", GOLD_HEALTH_PALETTE_1_SNES + 3 * 0x20, GOLD_HEALTH_PALETTE_2_SNES + 3 * 0x20, Encounter.GOLDEN),
            PaletteStageDef("gold-hp-4096", "Golden · HP 4096–6143", GOLD_HEALTH_PALETTE_1_SNES + 2 * 0x20, GOLD_HEALTH_PALETTE_2_SNES + 2 * 0x20, Encounter.GOLDEN),
            PaletteStageDef("gold-hp-2048", "Golden · HP 2048–4095", GOLD_HEALTH_PALETTE_1_SNES + 0x20, GOLD_HEALTH_PALETTE_2_SNES + 0x20, Encounter.GOLDEN),
            PaletteStageDef("gold-hp-0", "Golden · HP 0–2047", GOLD_HEALTH_PALETTE_1_SNES, GOLD_HEALTH_PALETTE_2_SNES, Encounter.GOLDEN),
        )

        val COMPOSITIONS = listOf(
            CompositionDef("bomb-awake-left", "Bomb · active left", Encounter.BOMB, Facing.LEFT, 0xAAAA5E),
            CompositionDef("bomb-awake-right", "Bomb · active right", Encounter.BOMB, Facing.RIGHT, 0xAAAFE8),
            CompositionDef("bomb-facing", "Bomb · facing screen", Encounter.BOMB, Facing.FORWARD, 0xAAA4F0),
            CompositionDef("bomb-gut", "Bomb · gut destroyed", Encounter.BOMB, Facing.LEFT, 0xAAAA5E, RuntimeTiles.GUT_DESTROYED),
            CompositionDef("bomb-face", "Bomb · face destroyed", Encounter.BOMB, Facing.FORWARD, 0xAAA4F0, RuntimeTiles.FACE_DESTROYED),
            CompositionDef("gold-left", "Golden · active left", Encounter.GOLDEN, Facing.LEFT, 0xAAAA5E),
            CompositionDef("gold-right", "Golden · active right", Encounter.GOLDEN, Facing.RIGHT, 0xAAAFE8),
            CompositionDef("gold-facing", "Golden · dodge / facing screen", Encounter.GOLDEN, Facing.FORWARD, 0xAAA4F0),
            CompositionDef("gold-egg-1", "Golden · egg release 1", Encounter.GOLDEN, Facing.LEFT, 0xAAA6EA, RuntimeTiles.EGG_1),
            CompositionDef("gold-egg-2", "Golden · egg release 2", Encounter.GOLDEN, Facing.LEFT, 0xAAA6EA, RuntimeTiles.EGG_2),
            CompositionDef("gold-egg-3", "Golden · egg release 3", Encounter.GOLDEN, Facing.LEFT, 0xAAA6EA, RuntimeTiles.EGG_3),
        )

        val COMPONENTS = listOf(
            ComponentDef("facing", "Facing screen / dodge", ComponentGroup.BODY, 0xAAA4F0),
            ComponentDef("walk-left-a", "Walk left · right leg", ComponentGroup.BODY, 0xAAA53E),
            ComponentDef("walk-left-b", "Walk left · left leg", ComponentGroup.BODY, 0xAAA5E8),
            ComponentDef("attack-left", "Attack left", ComponentGroup.BODY, 0xAAA6EA),
            ComponentDef("swipe-left", "Swipe left", ComponentGroup.BODY, 0xAAA9BA),
            ComponentDef("sit-left", "Sit / stand left", ComponentGroup.BODY, 0xAAAA30),
            ComponentDef("jump-left", "Jump / fall left", ComponentGroup.BODY, 0xAAB02E),
            ComponentDef("walk-right-a", "Walk right · left leg", ComponentGroup.BODY, 0xAAAADC),
            ComponentDef("walk-right-b", "Walk right · right leg", ComponentGroup.BODY, 0xAAAB86),
            ComponentDef("attack-right", "Attack right", ComponentGroup.BODY, 0xAAAC88),
            ComponentDef("swipe-right", "Swipe right", ComponentGroup.BODY, 0xAAAE2C),
            ComponentDef("sit-right", "Sit / stand right", ComponentGroup.BODY, 0xAAAFBA),
            ComponentDef("jump-right", "Jump / fall right", ComponentGroup.BODY, 0xAAB07C),
            ComponentDef("orb-left", "Chozo orb · left", ComponentGroup.PROJECTILES, 0x8D8C70, projectile = true),
            ComponentDef("orb-right", "Chozo orb · right", ComponentGroup.PROJECTILES, 0x8D8C77, projectile = true),
            ComponentDef("sonic-left", "Sonic boom · left", ComponentGroup.PROJECTILES, 0x8D8D1F, projectile = true),
            ComponentDef("sonic-right", "Sonic boom · right", ComponentGroup.PROJECTILES, 0x8D8D75, projectile = true),
            ComponentDef("egg", "Golden egg", ComponentGroup.PROJECTILES, 0x8D8F17, projectile = true),
            ComponentDef("hatched-left", "Hatched egg · left", ComponentGroup.PROJECTILES, 0x8D8F3A, projectile = true),
            ComponentDef("hatched-right", "Hatched egg · right", ComponentGroup.PROJECTILES, 0x8D8F98, projectile = true),
            ComponentDef("chozo-fragment-0", "Bomb statue fragment · 1", ComponentGroup.PROJECTILES, 0x8D8DFB, projectile = true),
            ComponentDef("chozo-fragment-5", "Bomb statue fragment · 6", ComponentGroup.PROJECTILES, 0x8D8E1E, projectile = true),
            ComponentDef("chozo-fragment-a", "Bomb statue fragment · 11", ComponentGroup.PROJECTILES, 0x8D8E41, projectile = true),
            ComponentDef("chozo-fragment-f", "Bomb statue fragment · 16", ComponentGroup.PROJECTILES, 0x8D8E64, projectile = true),
        )

        val ANIMATIONS = buildList {
            add(AnimationDef("bomb-awaken", "Awaken", Encounter.BOMB, Facing.LEFT, standFrames(Facing.LEFT), false, "InstList_Torizo_BombTorizo_Initial"))
            add(AnimationDef("bomb-walk-left", "Walk", Encounter.BOMB, Facing.LEFT, walkFrames(Facing.LEFT), true, "InstList_Torizo_FacingLeft_Walking"))
            add(AnimationDef("bomb-walk-right", "Walk", Encounter.BOMB, Facing.RIGHT, walkFrames(Facing.RIGHT), true, "InstList_Torizo_FacingRight_Walking"))
            add(AnimationDef("bomb-turn-right", "Turn to face right", Encounter.BOMB, Facing.LEFT, listOf(FrameDef(0xAAAA5E, 4), FrameDef(0xAAA4F0, 0x18), FrameDef(0xAAAFE8, 4)), false, "InstList_Torizo_FacingLeft_TurningLeft"))
            add(AnimationDef("bomb-turn-left", "Turn to face left", Encounter.BOMB, Facing.RIGHT, listOf(FrameDef(0xAAAFE8, 4), FrameDef(0xAAA4F0, 0x18), FrameDef(0xAAAA5E, 4)), false, "InstList_Torizo_FacingRight_TurningRight"))
            add(AnimationDef("bomb-orbs-left", "Spew Chozo orbs", Encounter.BOMB, Facing.LEFT, orbFrames(Facing.LEFT), false, "InstList_Torizo_FacingLeft_SpewingChozoOrbs"))
            add(AnimationDef("bomb-orbs-right", "Spew Chozo orbs", Encounter.BOMB, Facing.RIGHT, orbFrames(Facing.RIGHT), false, "InstList_Torizo_FacingRight_SpewingChozoOrbs"))
            add(AnimationDef("bomb-swipe-left", "Explosive swipe", Encounter.BOMB, Facing.LEFT, swipeFrames(Facing.LEFT), false, "InstList_Torizo_FacingLeft_ExplosiveSwipe"))
            add(AnimationDef("bomb-swipe-right", "Explosive swipe", Encounter.BOMB, Facing.RIGHT, swipeFrames(Facing.RIGHT), false, "InstList_Torizo_FacingRight_ExplosiveSwipe"))
            add(AnimationDef("bomb-jump-left", "Jump / fall", Encounter.BOMB, Facing.LEFT, jumpFrames(Facing.LEFT), false, "InstList_Torizo_FacingLeft_JumpingForwards"))
            add(AnimationDef("bomb-jump-right", "Jump / fall", Encounter.BOMB, Facing.RIGHT, jumpFrames(Facing.RIGHT), false, "InstList_Torizo_FacingRight_JumpingForwards"))

            add(AnimationDef("gold-awaken", "Awaken", Encounter.GOLDEN, Facing.LEFT, standFrames(Facing.LEFT), false, "InstList_GoldenTorizo_Initial"))
            add(AnimationDef("gold-walk-left", "Walk", Encounter.GOLDEN, Facing.LEFT, walkFrames(Facing.LEFT), true, "InstList_GoldenTorizo_WalkingLeft"))
            add(AnimationDef("gold-walk-right", "Walk", Encounter.GOLDEN, Facing.RIGHT, walkFrames(Facing.RIGHT), true, "InstList_GoldenTorizo_WalkingRight"))
            add(AnimationDef("gold-dodge-left", "Dodge / turn", Encounter.GOLDEN, Facing.LEFT, listOf(FrameDef(0xAAAA5E, 4), FrameDef(0xAAA4F0, 8), FrameDef(0xAAAFE8, 4)), false, "InstList_GoldenTorizo_Dodge_TurningLeft"))
            add(AnimationDef("gold-dodge-right", "Dodge / turn", Encounter.GOLDEN, Facing.RIGHT, listOf(FrameDef(0xAAAFE8, 4), FrameDef(0xAAA4F0, 8), FrameDef(0xAAAA5E, 4)), false, "InstList_GoldenTorizo_Dodge_TurningRight"))
            add(AnimationDef("gold-orbs-left", "Spew Chozo orbs", Encounter.GOLDEN, Facing.LEFT, orbFrames(Facing.LEFT), false, "InstList_GoldenTorizo_SpewChozoOrbs_FacingLeft"))
            add(AnimationDef("gold-orbs-right", "Spew Chozo orbs", Encounter.GOLDEN, Facing.RIGHT, orbFrames(Facing.RIGHT), false, "InstList_GoldenTorizo_SpewChozoOrb_FacingRight"))
            add(AnimationDef("gold-catch-left", "Catch super missile", Encounter.GOLDEN, Facing.LEFT, catchFrames(Facing.LEFT), false, "InstList_GoldenTorizo_CaughtSuper_FacingLeft"))
            add(AnimationDef("gold-catch-right", "Catch super missile", Encounter.GOLDEN, Facing.RIGHT, catchFrames(Facing.RIGHT), false, "InstList_GoldenTorizo_CaughtSuper_FacingRight"))
            add(AnimationDef("gold-sit-left", "Sit-down attack", Encounter.GOLDEN, Facing.LEFT, sitAttackFrames(Facing.LEFT), false, "InstList_GoldenTorizo_SitDownAttack_FacingLeft"))
            add(AnimationDef("gold-sit-right", "Sit-down attack", Encounter.GOLDEN, Facing.RIGHT, sitAttackFrames(Facing.RIGHT), false, "InstList_GoldenTorizo_SitDownAttack_FacingRight"))
            add(AnimationDef("gold-eggs-left", "Release eggs", Encounter.GOLDEN, Facing.LEFT, eggReleaseFrames(Facing.LEFT), false, "InstList_GoldenTorizo_ReleaseGoldenTorizoEggs"))
            add(AnimationDef("gold-eggs-right", "Release eggs", Encounter.GOLDEN, Facing.RIGHT, eggReleaseFrames(Facing.RIGHT), false, "InstList_GoldenTorizo_ReleaseGoldenTorizoEggs"))
            add(AnimationDef("gold-eyes-left", "Eye beam charge", Encounter.GOLDEN, Facing.LEFT, eyeFrames(Facing.LEFT), true, "InstList_GoldenTorizo_EyeBeamAttack"))
            add(AnimationDef("gold-eyes-right", "Eye beam charge", Encounter.GOLDEN, Facing.RIGHT, eyeFrames(Facing.RIGHT), true, "InstList_GoldenTorizo_EyeBeamAttack"))
        }

        val PROJECTILE_ANIMATIONS = listOf(
            ProjectileAnimationDef("orb-left", "Chozo orb · left", intArrayOf(0x8D8C70), intArrayOf(0x55), true),
            ProjectileAnimationDef("orb-right", "Chozo orb · right", intArrayOf(0x8D8C77), intArrayOf(0x55), true),
            ProjectileAnimationDef("orb-break", "Chozo orb · floor impact", intArrayOf(0x8D8C7E, 0x8D8C85, 0x8D8C91, 0x8D8CA7, 0x8D8CB8, 0x8D8CC9), intArrayOf(4, 5, 6, 7, 8, 9), false),
            ProjectileAnimationDef("sonic-left", "Sonic boom · left", intArrayOf(0x8D8CE9, 0x8D8CFF, 0x8D8D1F), intArrayOf(6, 6, 0x50), false),
            ProjectileAnimationDef("sonic-right", "Sonic boom · right", intArrayOf(0x8D8D3F, 0x8D8D55, 0x8D8D75), intArrayOf(6, 6, 0x50), false),
            ProjectileAnimationDef("egg-left", "Golden egg · hatch left", intArrayOf(0x8D8F17, 0x8D8F1E, 0x8D8F25, 0x8D8F2C, 0x8D8F33, 0x8D8F3A, 0x8D8F41, 0x8D8F3A), intArrayOf(0x30, 4, 4, 4, 6, 6, 6, 6), true),
            ProjectileAnimationDef("egg-right", "Golden egg · hatch right", intArrayOf(0x8D8F75, 0x8D8F7C, 0x8D8F83, 0x8D8F8A, 0x8D8F91, 0x8D8F98, 0x8D8F9F, 0x8D8F98), intArrayOf(0x30, 4, 4, 4, 6, 6, 6, 6), true),
        )

        val PIXEL_SOURCES = listOf(
            PixelSourceDef("shared-obj", "Bomb + Golden Torizo OBJ", BASE_TILES_SNES, BASE_TILES_SIZE, true, "Tiles_BombTorizo_GoldenTorizo"),
            PixelSourceDef("runtime-overlays", "Runtime overlays", RUNTIME_TILES_SNES, RUNTIME_TILES_SIZE, false, "Tiles_Torizo"),
            PixelSourceDef("golden-egg", "Golden Torizo egg", EGG_TILES_SNES, EGG_TILES_SIZE, false, "Tiles_GoldenTorizoEgg"),
            PixelSourceDef("bomb-statue", "Bomb Torizo crumbling statue", CRUMBLING_CHOZO_TILES_SNES, CRUMBLING_CHOZO_TILES_SIZE, false, "Tiles_BombTorizosCrumblingChozo"),
        )

        private fun standFrames(facing: Facing): List<FrameDef> {
            val maps = if (facing == Facing.LEFT) intArrayOf(0xAAAA12, 0xAAAA1C, 0xAAAA26, 0xAAAA30, 0xAAAA3A, 0xAAAA4C, 0xAAAA5E)
            else intArrayOf(0xAAAF9C, 0xAAAFA6, 0xAAAFB0, 0xAAAFBA, 0xAAAFC4, 0xAAAFD6, 0xAAAFE8)
            return maps.mapIndexed { index, address -> FrameDef(address, if (index == 0) 0x18 else if (index < 4) 8 else 10) }
        }

        private fun walkFrames(facing: Facing): List<FrameDef> {
            val maps = if (facing == Facing.LEFT) {
                intArrayOf(0xAAA4FA, 0xAAA51C, 0xAAA53E, 0xAAA560, 0xAAA582, 0xAAA5A4, 0xAAA5C6, 0xAAA5E8, 0xAAA60A, 0xAAA62C)
            } else {
                intArrayOf(0xAAAA98, 0xAAAABA, 0xAAAADC, 0xAAAAFE, 0xAAAB20, 0xAAAB42, 0xAAAB64, 0xAAAB86, 0xAAABA8, 0xAAABCA)
            }
            return maps.map { FrameDef(it, 6) }
        }

        private fun orbFrames(facing: Facing): List<FrameDef> {
            val attack = if (facing == Facing.LEFT) 0xAAA6EA else 0xAAAC88
            val maps = if (facing == Facing.LEFT) intArrayOf(0xAAA704, 0xAAA71E, 0xAAA738, 0xAAA752, 0xAAA76C)
            else intArrayOf(0xAAACA2, 0xAAACBC, 0xAAACD6, 0xAAACF0, 0xAAAD0A)
            return listOf(FrameDef(attack, 0x10)) + maps.map { FrameDef(it, 8) } + maps.dropLast(1).asReversed().map { FrameDef(it, 8) }
        }

        private fun swipeFrames(facing: Facing): List<FrameDef> {
            val maps = if (facing == Facing.LEFT) intArrayOf(0xAAA6EA, 0xAAA8D2, 0xAAA8EC, 0xAAA906, 0xAAA920, 0xAAA93A, 0xAAA920, 0xAAA906, 0xAAA8EC, 0xAAA8D2, 0xAAA954)
            else intArrayOf(0xAAAC88, 0xAAAE70, 0xAAAE8A, 0xAAAEA4, 0xAAAEBE, 0xAAAED8, 0xAAAEBE, 0xAAAEA4, 0xAAAE8A, 0xAAAE70, 0xAAAEF2)
            return maps.mapIndexed { index, address -> FrameDef(address, if (index in 6..9) 1 else 3) }
        }

        private fun jumpFrames(facing: Facing): List<FrameDef> {
            val maps = if (facing == Facing.LEFT) intArrayOf(0xAAAFFA, 0xAAB014, 0xAAB02E, 0xAAB014)
            else intArrayOf(0xAAB048, 0xAAB062, 0xAAB07C, 0xAAB062)
            return maps.mapIndexed { index, address -> FrameDef(address, if (index == 2) 12 else 5) }
        }

        private fun catchFrames(facing: Facing): List<FrameDef> {
            val maps = if (facing == Facing.LEFT) intArrayOf(0xAAA6EA, 0xAAA786, 0xAAA7A0, 0xAAA7C2, 0xAAA7E4, 0xAAA806, 0xAAA7E4, 0xAAA7C2, 0xAAA7A0, 0xAAA806, 0xAAA828)
            else intArrayOf(0xAAAC88, 0xAAAD24, 0xAAAD3E, 0xAAAD60, 0xAAAD82, 0xAAADA4, 0xAAAD82, 0xAAAD60, 0xAAAD3E, 0xAAADA4, 0xAAADC6)
            return maps.mapIndexed { index, address -> FrameDef(address, if (index == 9) 0x30 else if (index < 6) 1 else 2) }
        }

        private fun sitAttackFrames(facing: Facing): List<FrameDef> {
            val down = standFrames(facing).asReversed()
            return down + standFrames(facing).drop(1)
        }

        private fun eggReleaseFrames(facing: Facing): List<FrameDef> {
            val pose = if (facing == Facing.LEFT) 0xAAA6EA else 0xAAAC88
            return listOf(
                FrameDef(pose, 8), FrameDef(pose, 8, RuntimeTiles.EGG_1),
                FrameDef(pose, 8, RuntimeTiles.EGG_2), FrameDef(pose, 0x10, RuntimeTiles.EGG_3),
                FrameDef(pose, 8, RuntimeTiles.EGG_2), FrameDef(pose, 8, RuntimeTiles.EGG_1), FrameDef(pose, 8),
            )
        }

        private fun eyeFrames(facing: Facing): List<FrameDef> {
            val pose = if (facing == Facing.LEFT) 0xAAAA5E else 0xAAAFE8
            return listOf(RuntimeTiles.EYES_0, RuntimeTiles.EYES_1, RuntimeTiles.EYES_2, RuntimeTiles.EYES_3).map {
                FrameDef(pose, 3, it)
            }
        }
    }

    enum class Encounter(val displayName: String) { BOMB("Bomb Torizo"), GOLDEN("Golden Torizo") }
    enum class Facing(val displayName: String) { LEFT("Left"), FORWARD("Forward"), RIGHT("Right") }
    enum class RuntimeTiles { BASE, EYES_0, EYES_1, EYES_2, EYES_3, GUT_DESTROYED, FACE_DESTROYED, EGG_1, EGG_2, EGG_3 }
    enum class ComponentGroup(val displayName: String) { BODY("Body poses"), PROJECTILES("Projectiles") }

    data class PaletteStageDef(val key: String, val name: String, val palette1Snes: Int, val palette2Snes: Int, val encounter: Encounter)
    data class CompositionDef(val key: String, val name: String, val encounter: Encounter, val facing: Facing, val snesAddress: Int, val runtimeTiles: RuntimeTiles = RuntimeTiles.BASE)
    data class ComponentDef(val key: String, val name: String, val group: ComponentGroup, val snesAddress: Int, val projectile: Boolean = false)
    data class FrameDef(val snesAddress: Int, val duration: Int, val runtimeTiles: RuntimeTiles = RuntimeTiles.BASE)
    data class AnimationDef(val key: String, val name: String, val encounter: Encounter, val facing: Facing, val frames: List<FrameDef>, val loop: Boolean, val sourceLabel: String)
    data class ProjectileAnimationDef(val key: String, val name: String, val addresses: IntArray, val durations: IntArray, val loop: Boolean)
    data class PixelSourceDef(val key: String, val name: String, val snesAddress: Int, val byteCount: Int, val editable: Boolean, val sourceLabel: String)

    private data class PalettePair(val first: IntArray, val second: IntArray, val rows: Map<Int, IntArray>)
    private data class NormalizedFrames(val width: Int, val height: Int, val pixels: List<IntArray>)

    private val renderer = EnemySpritemap(romParser)
    private val physicalOptions get() = EnemySpritemap.RenderOptions(
        oamTileNumberMode = EnemySpritemap.OamTileNumberMode.LOW_9,
        // Torizo's extended spritemaps append their child entries to ascending
        // OAM slots. On SNES, the lower OAM index wins an overlap, so paint the
        // flattened stream backwards to keep heads and foreground arms in front.
        reverseExtendedOamDrawOrder = true,
    )
    private var baseTiles: ByteArray? = null

    fun load(enemyTiles: ByteArray?): Boolean {
        if (enemyTiles == null || enemyTiles.size != BASE_TILES_SIZE) return false
        baseTiles = enemyTiles.copyOf()
        return readPalette(PALETTE_STAGES.first()) != null
    }

    fun getRawTileData(): ByteArray? = baseTiles?.copyOf()

    fun readPixelSource(definition: PixelSourceDef): ByteArray? = when (definition.key) {
        "shared-obj" -> getRawTileData()
        "runtime-overlays" -> readBytes(RUNTIME_TILES_SNES, RUNTIME_TILES_SIZE)
        "golden-egg" -> readBytes(EGG_TILES_SNES, EGG_TILES_SIZE)
        "bomb-statue" -> readBytes(CRUMBLING_CHOZO_TILES_SNES, CRUMBLING_CHOZO_TILES_SIZE)
        else -> null
    }

    fun readPalette(definition: PaletteStageDef): IntArray? = readPalettePair(definition)?.first

    fun renderComposition(definition: CompositionDef, palette: PaletteStageDef): EnemySpritemap.AssembledSprite? =
        renderBody(definition.snesAddress, palette, definition.runtimeTiles)

    fun renderComponent(definition: ComponentDef, palette: PaletteStageDef): EnemySpritemap.AssembledSprite? =
        if (definition.projectile) renderProjectile(definition.snesAddress, palette)
        else renderBody(definition.snesAddress, palette)

    fun renderAnimation(definition: AnimationDef, palette: PaletteStageDef): SpriteAnimation? {
        val sprites = definition.frames.map { frame ->
            renderBody(frame.snesAddress, palette, frame.runtimeTiles) ?: return null
        }
        val normalized = normalize(sprites) ?: return null
        return SpriteAnimation(
            "${definition.encounter.displayName} · ${definition.facing.displayName} · ${definition.name}",
            definition.frames.zip(normalized.pixels).mapIndexed { index, (source, pixels) ->
                SpriteAnimationFrame(pixels, normalized.width, normalized.height, source.duration, "${definition.name} ${index + 1}/${definition.frames.size}")
            },
            definition.loop,
        )
    }

    fun renderProjectileAnimation(definition: ProjectileAnimationDef, palette: PaletteStageDef): SpriteAnimation? {
        if (definition.addresses.size != definition.durations.size) return null
        val sprites = definition.addresses.map { renderProjectile(it, palette) ?: return null }
        val normalized = normalize(sprites) ?: return null
        return SpriteAnimation(
            definition.name,
            definition.addresses.indices.map { index ->
                SpriteAnimationFrame(normalized.pixels[index], normalized.width, normalized.height, definition.durations[index], "${definition.name} ${index + 1}/${definition.addresses.size}")
            },
            definition.loop,
        )
    }

    private fun renderBody(address: Int, palette: PaletteStageDef, runtime: RuntimeTiles = RuntimeTiles.BASE): EnemySpritemap.AssembledSprite? {
        val tiles = buildPhysicalTiles(runtime) ?: return null
        val colors = readPalettePair(palette) ?: return null
        val frame = renderer.parseRenderableFrame(address) ?: return null
        return renderer.renderRenderableFrame(
            frame, tiles, colors.first,
            physicalOptions.copy(oamPaletteRows = colors.rows),
        )
    }

    private fun renderProjectile(address: Int, palette: PaletteStageDef): EnemySpritemap.AssembledSprite? {
        val isChozoFragment = address in 0x8D8DFB..0x8D8E64
        val tiles = buildPhysicalTiles(
            RuntimeTiles.BASE,
            includeEgg = !isChozoFragment,
            includeCrumblingChozo = isChozoFragment,
        ) ?: return null
        val colors = readPalettePair(palette) ?: return null
        val map = renderer.parseSpritemap(address) ?: return null
        return renderer.renderRenderableFrame(
            EnemySpritemap.RenderableFrame.Oam(map), tiles, colors.first,
            physicalOptions.copy(oamPaletteRows = colors.rows),
        )
    }

    private fun buildPhysicalTiles(
        runtime: RuntimeTiles,
        includeEgg: Boolean = false,
        includeCrumblingChozo: Boolean = false,
    ): ByteArray? {
        val raw = baseTiles ?: return null
        val output = ByteArray(PHYSICAL_TILE_COUNT * EnemySpriteGraphics.BYTES_PER_TILE)
        raw.copyInto(output, SPECIES_PHYSICAL_BASE * EnemySpriteGraphics.BYTES_PER_TILE)
        if (includeEgg) {
            readBytes(EGG_TILES_SNES, EGG_TILES_SIZE)?.copyInto(
                output, EGG_PHYSICAL_BASE * EnemySpriteGraphics.BYTES_PER_TILE,
            ) ?: return null
        }
        if (includeCrumblingChozo) {
            readBytes(CRUMBLING_CHOZO_TILES_SNES, CRUMBLING_CHOZO_TILES_SIZE)?.copyInto(
                output, CRUMBLING_CHOZO_PHYSICAL_BASE * EnemySpriteGraphics.BYTES_PER_TILE,
            ) ?: return null
        }
        when (runtime) {
            RuntimeTiles.BASE -> Unit
            RuntimeTiles.EYES_0 -> copyRuntime(output, 0x000, 0x40, 0x1B00)
            RuntimeTiles.EYES_1 -> copyRuntime(output, 0x040, 0x40, 0x1B00)
            RuntimeTiles.EYES_2 -> copyRuntime(output, 0x080, 0x40, 0x1B00)
            RuntimeTiles.EYES_3 -> copyRuntime(output, 0x0C0, 0x40, 0x1B00)
            RuntimeTiles.GUT_DESTROYED -> {
                copyRuntime(output, 0x200, 0x40, 0x0600)
                copyRuntime(output, 0x400, 0x40, 0x0800)
                copyRuntime(output, 0x240, 0x20, 0x1CE0)
                copyRuntime(output, 0x440, 0x20, 0x1EE0)
            }
            RuntimeTiles.FACE_DESTROYED -> {
                copyRuntime(output, 0x260, 0x20, 0x1CA0)
                copyRuntime(output, 0x460, 0x20, 0x1EA0)
            }
            RuntimeTiles.EGG_1 -> copyEggBelly(output, 0x280, 0x480)
            RuntimeTiles.EGG_2 -> copyEggBelly(output, 0x2C0, 0x4C0)
            RuntimeTiles.EGG_3 -> copyEggBelly(output, 0x300, 0x500)
        }
        return output
    }

    private fun copyEggBelly(destination: ByteArray, topSource: Int, bottomSource: Int) {
        copyRuntime(destination, topSource, 0x40, 0x0600)
        copyRuntime(destination, bottomSource, 0x40, 0x0800)
    }

    private fun copyRuntime(destination: ByteArray, sourceOffset: Int, count: Int, speciesOffset: Int) {
        val data = readBytes(RUNTIME_TILES_SNES + sourceOffset, count) ?: return
        data.copyInto(destination, SPECIES_PHYSICAL_BASE * EnemySpriteGraphics.BYTES_PER_TILE + speciesOffset)
    }

    private fun readPalettePair(definition: PaletteStageDef): PalettePair? {
        val first = readPaletteAt(definition.palette1Snes) ?: return null
        val second = readPaletteAt(definition.palette2Snes) ?: return null
        val orb = readPaletteAt(ORB_PALETTE_SNES) ?: return null
        val common = readPaletteAt(COMMON_SPRITE_PALETTE_5_SNES) ?: return null
        return PalettePair(first, second, mapOf(1 to first, 2 to second, 3 to orb, 5 to common, 7 to first))
    }

    private fun normalize(sprites: List<EnemySpritemap.AssembledSprite>): NormalizedFrames? {
        if (sprites.isEmpty()) return null
        val minX = sprites.minOf { -it.originX }
        val minY = sprites.minOf { -it.originY }
        val maxX = sprites.maxOf { it.width - it.originX }
        val maxY = sprites.maxOf { it.height - it.originY }
        val width = maxX - minX
        val height = maxY - minY
        if (width <= 0 || height <= 0) return null
        return NormalizedFrames(width, height, sprites.map { sprite ->
            IntArray(width * height).also { output ->
                for (y in 0 until sprite.height) for (x in 0 until sprite.width) {
                    val color = sprite.pixels[y * sprite.width + x]
                    if ((color ushr 24) == 0) continue
                    val dx = x - sprite.originX - minX
                    val dy = y - sprite.originY - minY
                    if (dx in 0 until width && dy in 0 until height) output[dy * width + dx] = color
                }
            }
        })
    }

    private fun readPaletteAt(snesAddress: Int): IntArray? {
        val raw = readBytes(snesAddress, 32) ?: return null
        return IntArray(16) { index ->
            if (index == 0) 0 else EnemySpriteGraphics.snesColorToArgb(readWord(raw, index * 2))
        }
    }

    private fun readBytes(snesAddress: Int, count: Int): ByteArray? {
        val pc = romParser.snesToPc(snesAddress)
        val rom = romParser.getRomData()
        if (pc < 0 || pc + count > rom.size) return null
        return rom.copyOfRange(pc, pc + count)
    }

    private fun readWord(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}

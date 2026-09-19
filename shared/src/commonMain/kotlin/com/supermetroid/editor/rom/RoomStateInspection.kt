package com.supermetroid.editor.rom

/**
 * SMEDIT's stable room-header bridge to a relocatable selector graph.
 *
 * The room engine enters selector routines with X pointing immediately after
 * the routine word. The executable five bytes load the graph pointer stored at
 * that X, transfer it back to X, and return to the vanilla selector loop:
 * `LDA $0000,X; TAX; RTS` (`BD 00 00 AA 60`). An unreachable `SMEDITSG` tag
 * and version byte make this ABI distinguishable from arbitrary custom code.
 */
object SmEditRoomStateGraphFormat {
    val redirectRoutineBytes: ByteArray = byteArrayOf(
        0xBD.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x60,
        0x53, 0x4D, 0x45, 0x44, 0x49, 0x54, 0x53, 0x47, 0x01,
    )
}

/**
 * Semantic meaning of a room-state selector routine in bank $8F.
 *
 * These are runtime predicates, evaluated in order when a room is loaded. The
 * first predicate that succeeds chooses its state; [DEFAULT] terminates the
 * list and always succeeds.
 */
enum class RoomStateConditionKind {
    DEFAULT,
    INCOMING_DOOR,
    AREA_MAIN_BOSS_DEAD,
    NEVER,
    EVENT_SET,
    AREA_BOSS_BIT_SET,
    MORPH_BALL_COLLECTED,
    MORPH_BALL_AND_MISSILES,
    POWER_BOMBS_COLLECTED,
    SPEED_BOOSTER_COLLECTED,
    EQUIPMENT_COLLECTED,
    BEAM_COLLECTED,
    MISSILE_CAPACITY_AT_LEAST,
    SUPER_MISSILE_CAPACITY_AT_LEAST,
    POWER_BOMB_CAPACITY_AT_LEAST,
    ENERGY_CAPACITY_AT_LEAST,
    RESERVE_CAPACITY_AT_LEAST,
    ITEM_PICKUP_COLLECTED,
    BOSS_DEFEATED,
    EQUIPMENT_EQUIPPED,
    BEAM_EQUIPPED,
    CURRENT_ENERGY_AT_LEAST,
    CURRENT_MISSILES_AT_LEAST,
    CURRENT_SUPER_MISSILES_AT_LEAST,
    CURRENT_POWER_BOMBS_AT_LEAST,
    CURRENT_RESERVE_ENERGY_AT_LEAST,
    DOOR_BIT_SET,
    CHOZO_BLOCK_DESTROYED,
    ESCAPE_ACTIVE,
    ALL_OF,
    ANY_OF,
}

enum class RoomStateConditionArgumentKind {
    NONE,
    EVENT_ID,
    BOSS_BIT_MASK,
    DOOR_POINTER,
    EQUIPMENT_MASK,
    BEAM_MASK,
    CAPACITY,
    ITEM_BIT_INDEX,
    AREA_AND_BOSS_MASK,
    DOOR_BIT_INDEX,
    CHOZO_BLOCK_BIT_INDEX,
    CHILDREN,
}

/**
 * A decoded selector entry. [entrySizeBytes] includes the two-byte routine
 * address, its optional argument, and the state pointer (except for the
 * default entry, whose state data follows inline).
 */
data class RoomStateCondition(
    val code: Int,
    val kind: RoomStateConditionKind,
    val argumentKind: RoomStateConditionArgumentKind,
    val argument: Int? = null,
    val entrySizeBytes: Int,
    val negated: Boolean = false,
    val children: List<RoomStateCondition> = emptyList(),
) {
    val isDefault: Boolean get() = kind == RoomStateConditionKind.DEFAULT

    /** Compact condition name for places where the surrounding UI supplies the IF/ELSE context. */
    fun shortSummary(area: Int): String = when (kind) {
        RoomStateConditionKind.DEFAULT -> "Default"
        RoomStateConditionKind.INCOMING_DOOR ->
            if (negated) "Not entered through door \$${hex(argument, 4)}"
            else "Entered through door \$${hex(argument, 4)}"
        RoomStateConditionKind.AREA_MAIN_BOSS_DEAD ->
            if (negated) "${areaName(area)} main boss not defeated" else "${areaName(area)} main boss defeated"
        RoomStateConditionKind.NEVER -> if (negated) "Always" else "Never"
        RoomStateConditionKind.EVENT_SET -> {
            val event = EVENT_NAMES[argument ?: 0]
            if (event != null) {
                if (negated) "NOT ($event)" else event
            } else if (negated) {
                "Event \$${hex(argument, 2)} not set"
            } else {
                "Event \$${hex(argument, 2)} set"
            }
        }
        RoomStateConditionKind.AREA_BOSS_BIT_SET -> {
            val boss = BOSS_NAMES[area to (argument ?: 0)]
                ?: "${areaName(area)} boss bit \$${hex(argument, 2)}"
            if (negated) "$boss not defeated" else "$boss defeated"
        }
        RoomStateConditionKind.MORPH_BALL_COLLECTED ->
            if (negated) "Morph Ball not collected" else "Morph Ball collected"
        RoomStateConditionKind.MORPH_BALL_AND_MISSILES ->
            if (negated) "Not (Morph Ball + missiles collected)" else "Morph Ball + missiles collected"
        RoomStateConditionKind.POWER_BOMBS_COLLECTED ->
            if (negated) "No Power Bomb capacity" else "Power Bombs collected"
        RoomStateConditionKind.SPEED_BOOSTER_COLLECTED ->
            if (negated) "Speed Booster not collected" else "Speed Booster collected"
        RoomStateConditionKind.EQUIPMENT_COLLECTED ->
            collectionSummary(EQUIPMENT_NAMES[argument ?: 0] ?: "Equipment mask \$${hex(argument, 4)}")
        RoomStateConditionKind.BEAM_COLLECTED ->
            collectionSummary(BEAM_NAMES[argument ?: 0] ?: "Beam mask \$${hex(argument, 4)}")
        RoomStateConditionKind.MISSILE_CAPACITY_AT_LEAST -> thresholdSummary("Missiles")
        RoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST -> thresholdSummary("Super Missiles")
        RoomStateConditionKind.POWER_BOMB_CAPACITY_AT_LEAST -> thresholdSummary("Power Bombs")
        RoomStateConditionKind.ENERGY_CAPACITY_AT_LEAST -> thresholdSummary("Energy")
        RoomStateConditionKind.RESERVE_CAPACITY_AT_LEAST -> thresholdSummary("Reserve Energy")
        RoomStateConditionKind.ITEM_PICKUP_COLLECTED ->
            collectionSummary("Item pickup ID \$${hex(argument, 3)}")
        RoomStateConditionKind.BOSS_DEFEATED -> {
            val packed = argument ?: 0
            val bossArea = (packed ushr 8) and 0xFF
            val mask = packed and 0xFF
            val name = BOSS_NAMES[bossArea to mask] ?: "${areaName(bossArea)} boss bit \$${hex(mask, 2)}"
            if (negated) "$name not defeated" else "$name defeated"
        }
        RoomStateConditionKind.EQUIPMENT_EQUIPPED ->
            collectionSummary(EQUIPMENT_NAMES[argument ?: 0] ?: "Equipment mask \$${hex(argument, 4)}", "equipped")
        RoomStateConditionKind.BEAM_EQUIPPED ->
            collectionSummary(BEAM_NAMES[argument ?: 0] ?: "Beam mask \$${hex(argument, 4)}", "equipped")
        RoomStateConditionKind.CURRENT_ENERGY_AT_LEAST -> thresholdSummary("Current energy")
        RoomStateConditionKind.CURRENT_MISSILES_AT_LEAST -> thresholdSummary("Current missiles")
        RoomStateConditionKind.CURRENT_SUPER_MISSILES_AT_LEAST -> thresholdSummary("Current Super Missiles")
        RoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST -> thresholdSummary("Current Power Bombs")
        RoomStateConditionKind.CURRENT_RESERVE_ENERGY_AT_LEAST -> thresholdSummary("Current reserve energy")
        RoomStateConditionKind.DOOR_BIT_SET -> collectionSummary("Door bit \$${hex(argument, 3)}", "set")
        RoomStateConditionKind.CHOZO_BLOCK_DESTROYED ->
            collectionSummary("Chozo block bit \$${hex(argument, 3)}", "destroyed")
        RoomStateConditionKind.ESCAPE_ACTIVE -> if (negated) "Escape not active" else "Escape active"
        RoomStateConditionKind.ALL_OF -> groupSummary("AND", area)
        RoomStateConditionKind.ANY_OF -> groupSummary("OR", area)
    }

    fun summary(area: Int): String = when (kind) {
        RoomStateConditionKind.DEFAULT -> "Default (always used if no earlier condition matches)"
        RoomStateConditionKind.INCOMING_DOOR -> shortSummary(area)
        RoomStateConditionKind.AREA_MAIN_BOSS_DEAD -> shortSummary(area)
        RoomStateConditionKind.NEVER -> if (negated) "Always (inverted always-false predicate)" else "Never (always false)"
        RoomStateConditionKind.EVENT_SET -> {
            val event = argument ?: 0
            val name = EVENT_NAMES[event]
            if (name != null) {
                if (negated) "NOT ($name) (event \$${hex(event, 2)})" else "$name (event \$${hex(event, 2)})"
            } else if (negated) {
                "Event \$${hex(event, 2)} is not set"
            } else {
                "Event \$${hex(event, 2)} is set"
            }
        }
        RoomStateConditionKind.AREA_BOSS_BIT_SET -> {
            val mask = argument ?: 0
            val name = BOSS_NAMES[area to mask]
            val subject = name ?: "Boss bit \$${hex(mask, 2)} for ${areaName(area)}"
            if (negated) "$subject is not defeated" else "$subject is defeated"
        }
        RoomStateConditionKind.MORPH_BALL_COLLECTED,
        RoomStateConditionKind.MORPH_BALL_AND_MISSILES,
        RoomStateConditionKind.POWER_BOMBS_COLLECTED,
        RoomStateConditionKind.SPEED_BOOSTER_COLLECTED -> shortSummary(area)
        RoomStateConditionKind.EQUIPMENT_COLLECTED,
        RoomStateConditionKind.BEAM_COLLECTED,
        RoomStateConditionKind.ITEM_PICKUP_COLLECTED,
        RoomStateConditionKind.MISSILE_CAPACITY_AT_LEAST,
        RoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST,
        RoomStateConditionKind.POWER_BOMB_CAPACITY_AT_LEAST,
        RoomStateConditionKind.ENERGY_CAPACITY_AT_LEAST,
        RoomStateConditionKind.RESERVE_CAPACITY_AT_LEAST,
        RoomStateConditionKind.BOSS_DEFEATED -> shortSummary(area)
        RoomStateConditionKind.EQUIPMENT_EQUIPPED,
        RoomStateConditionKind.BEAM_EQUIPPED,
        RoomStateConditionKind.CURRENT_ENERGY_AT_LEAST,
        RoomStateConditionKind.CURRENT_MISSILES_AT_LEAST,
        RoomStateConditionKind.CURRENT_SUPER_MISSILES_AT_LEAST,
        RoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST,
        RoomStateConditionKind.CURRENT_RESERVE_ENERGY_AT_LEAST,
        RoomStateConditionKind.DOOR_BIT_SET,
        RoomStateConditionKind.CHOZO_BLOCK_DESTROYED,
        RoomStateConditionKind.ESCAPE_ACTIVE,
        RoomStateConditionKind.ALL_OF,
        RoomStateConditionKind.ANY_OF -> shortSummary(area)
    }

    private fun collectionSummary(subject: String, positiveVerb: String = "collected"): String =
        if (negated) "$subject not $positiveVerb" else "$subject $positiveVerb"

    private fun thresholdSummary(subject: String): String =
        if (negated) "$subject below ${argument ?: 0}" else "$subject ≥ ${argument ?: 0}"

    private fun groupSummary(operator: String, area: Int): String {
        val body = children.joinToString(" $operator ") { child ->
            if (child.kind == RoomStateConditionKind.ALL_OF || child.kind == RoomStateConditionKind.ANY_OF) {
                "(${child.shortSummary(area)})"
            } else {
                child.shortSummary(area)
            }
        }.ifBlank { "Empty group" }
        return if (negated) "NOT ($body)" else body
    }

    companion object {
        /** Event meanings verified against the vanilla event table. */
        val EVENT_NAMES: Map<Int, String> = mapOf(
            0x00 to "Zebes is awake",
            0x01 to "Giant metroid ate sidehopper",
            0x02 to "Mother Brain glass is broken",
            0x03 to "Zebetite 1 is destroyed",
            0x04 to "Zebetite 2 is destroyed",
            0x05 to "Zebetite 3 is destroyed",
            0x06 to "Phantoon statue is grey",
            0x07 to "Ridley statue is grey",
            0x08 to "Draygon statue is grey",
            0x09 to "Kraid statue is grey",
            0x0A to "Path to Tourian is open",
            0x0B to "Maridia tube is broken",
            0x0C to "Lower Norfair Chozo lowered the acid",
            0x0D to "Shaktool cleared the path",
            0x0E to "Zebes timebomb is set",
            0x0F to "Animals are saved",
        )

        /**
         * Boss bits are per-area masks, not global boss IDs. These names are
         * verified from the vanilla room selector arguments and boss routines.
         */
        val BOSS_NAMES: Map<Pair<Int, Int>, String> = mapOf(
            (0 to 0x04) to "Bomb Torizo",
            (1 to 0x01) to "Kraid",
            (1 to 0x02) to "Spore Spawn",
            (2 to 0x01) to "Ridley",
            (2 to 0x02) to "Crocomire",
            (2 to 0x04) to "Golden Torizo",
            (3 to 0x01) to "Phantoon",
            (4 to 0x01) to "Draygon",
            (4 to 0x02) to "Botwoon",
            (5 to 0x01) to "Mother Brain",
            (6 to 0x01) to "Ceres Ridley",
        )

        /** Bit masks stored in Samus's collected-equipment word at $7E:09A4. */
        val EQUIPMENT_NAMES: Map<Int, String> = linkedMapOf(
            0x0001 to "Varia Suit",
            0x0002 to "Spring Ball",
            0x0004 to "Morph Ball",
            0x0008 to "Screw Attack",
            0x0020 to "Gravity Suit",
            0x0100 to "Hi-Jump Boots",
            0x0200 to "Space Jump",
            0x1000 to "Bombs",
            0x2000 to "Speed Booster",
            0x4000 to "Grapple Beam",
            0x8000 to "X-Ray Scope",
        )

        /** Bit masks stored in Samus's collected-beam word at $7E:09A8. */
        val BEAM_NAMES: Map<Int, String> = linkedMapOf(
            0x0001 to "Wave Beam",
            0x0002 to "Ice Beam",
            0x0004 to "Spazer",
            0x0008 to "Plasma Beam",
            0x1000 to "Charge Beam",
        )

        private val AREA_NAMES = listOf(
            "Crateria",
            "Brinstar",
            "Norfair",
            "Wrecked Ship",
            "Maridia",
            "Tourian",
            "Ceres",
            "Debug/Unused",
        )

        private fun areaName(area: Int): String = AREA_NAMES.getOrNull(area) ?: "area $area"

        private fun hex(value: Int?, digits: Int): String =
            (value ?: 0).toString(16).uppercase().padStart(digits, '0')
    }
}

data class InspectedRoomState(
    val index: Int,
    val selectorPcOffset: Int,
    /** Null only when a malformed pointer was decoded. */
    val stateDataPcOffset: Int?,
    /** Null for the inline default state. */
    val stateDataPointer: Int?,
    val condition: RoomStateCondition,
)

data class RoomStateParseIssue(
    val pcOffset: Int,
    val message: String,
)

/**
 * Lossless-enough read model for explaining the room's runtime state logic.
 * It deliberately reports malformed or unknown selectors instead of silently
 * scanning past them.
 */
data class RoomStateInspection(
    val roomId: Int,
    val area: Int?,
    val states: List<InspectedRoomState>,
    val issues: List<RoomStateParseIssue>,
    val hasDefault: Boolean,
) {
    val isComplete: Boolean get() = hasDefault && issues.isEmpty()
}

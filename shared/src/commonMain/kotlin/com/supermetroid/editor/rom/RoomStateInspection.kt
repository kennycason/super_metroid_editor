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
}

enum class RoomStateConditionArgumentKind {
    NONE,
    EVENT_ID,
    BOSS_BIT_MASK,
    DOOR_POINTER,
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
) {
    val isDefault: Boolean get() = kind == RoomStateConditionKind.DEFAULT

    /** Compact condition name for places where the surrounding UI supplies the IF/ELSE context. */
    fun shortSummary(area: Int): String = when (kind) {
        RoomStateConditionKind.DEFAULT -> "Default"
        RoomStateConditionKind.INCOMING_DOOR -> "Entered through door \$${hex(argument, 4)}"
        RoomStateConditionKind.AREA_MAIN_BOSS_DEAD -> "${areaName(area)} main boss defeated"
        RoomStateConditionKind.NEVER -> "Never"
        RoomStateConditionKind.EVENT_SET -> EVENT_NAMES[argument ?: 0]
            ?: "Event \$${hex(argument, 2)} set"
        RoomStateConditionKind.AREA_BOSS_BIT_SET -> BOSS_NAMES[area to (argument ?: 0)]
            ?.let { "$it defeated" }
            ?: "${areaName(area)} boss bit \$${hex(argument, 2)} set"
        RoomStateConditionKind.MORPH_BALL_COLLECTED -> "Morph Ball collected"
        RoomStateConditionKind.MORPH_BALL_AND_MISSILES -> "Morph Ball + missiles collected"
        RoomStateConditionKind.POWER_BOMBS_COLLECTED -> "Power Bombs collected"
        RoomStateConditionKind.SPEED_BOOSTER_COLLECTED -> "Speed Booster collected"
    }

    fun summary(area: Int): String = when (kind) {
        RoomStateConditionKind.DEFAULT -> "Default (always used if no earlier condition matches)"
        RoomStateConditionKind.INCOMING_DOOR ->
            "Incoming door is \$${hex(argument, 4)}"
        RoomStateConditionKind.AREA_MAIN_BOSS_DEAD ->
            "Main boss for ${areaName(area)} is defeated"
        RoomStateConditionKind.NEVER -> "Never (always false)"
        RoomStateConditionKind.EVENT_SET -> {
            val event = argument ?: 0
            val name = EVENT_NAMES[event]
            if (name == null) "Event \$${hex(event, 2)} is set" else "$name (event \$${hex(event, 2)})"
        }
        RoomStateConditionKind.AREA_BOSS_BIT_SET -> {
            val mask = argument ?: 0
            val name = BOSS_NAMES[area to mask]
            if (name == null) {
                "Boss bit \$${hex(mask, 2)} for ${areaName(area)} is set"
            } else {
                "$name is defeated (${areaName(area)} boss bit \$${hex(mask, 2)})"
            }
        }
        RoomStateConditionKind.MORPH_BALL_COLLECTED -> "Morph Ball is collected"
        RoomStateConditionKind.MORPH_BALL_AND_MISSILES -> "Morph Ball and at least one missile are collected"
        RoomStateConditionKind.POWER_BOMBS_COLLECTED -> "At least one Power Bomb has been collected"
        RoomStateConditionKind.SPEED_BOOSTER_COLLECTED -> "Speed Booster is collected"
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

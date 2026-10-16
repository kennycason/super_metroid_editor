package com.supermetroid.editor.rom

import com.supermetroid.editor.data.*

/** An explicit carry-result/RTS predicate. Addresses exist only in the compiled build input. */
data class SourceRoomSelectorRoutine(val label: String, val snesAddress: Int? = null, val sizeBytes: Int? = null) {
    init {
        require(label.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Predicate requires a plain global label." }
        require((snesAddress == null) == (sizeBytes == null)) { "Incomplete compiled predicate placement." }
        if (snesAddress != null) require(snesAddress in 0x8F8000..0x8FFFFF && sizeBytes!! in 1..1024 && snesAddress + sizeBytes <= 0x900000) {
            "Predicate must fit in bank \$8F (1–1024 bytes)."
        }
    }
}

/** Source-owned branch order; opaque routines are NOT replaced with guessed typed conditions. */
data class SourceRoomSelectors(val order: List<String>, val conditions: Map<String, ProjectRoomStateCondition>,
                               val routines: Map<String, SourceRoomSelectorRoutine> = emptyMap()) {
    init {
        require(conditions.keys.intersect(routines.keys).isEmpty()) { "A selector cannot have both typed and executable authority." }
        require(order.size in 1..64 && order.distinct().size == order.size && order.toSet() == conditions.keys + routines.keys) { "Selector identities must cover every state exactly once." }
        require(conditions.filterValues { it.kind == ProjectRoomStateConditionKind.DEFAULT }.keys == setOf(order.last())) { "Exactly one mandatory default selector must be last." }
        var nodes = 0
        fun validate(c: ProjectRoomStateCondition, depth: Int) {
            require(depth <= 8 && ++nodes <= 64) { "Condition tree exceeds 8 levels or 64 nodes." }
            require(c == projectRoomStateCondition(c.kind, c.argument, c.negated, c.children, c.sourceDoor)) { "Inconsistent typed selector." }
            val argument = c.argument
            val valid = when (c.argumentKind) {
                ProjectRoomStateConditionArgumentKind.NONE -> argument == null && c.children.isEmpty() && (c.kind != ProjectRoomStateConditionKind.DEFAULT || !c.negated && depth == 0)
                ProjectRoomStateConditionArgumentKind.CHILDREN -> argument == null && c.children.size >= 2 && c.children.none { it.kind == ProjectRoomStateConditionKind.DEFAULT }
                ProjectRoomStateConditionArgumentKind.EVENT_ID, ProjectRoomStateConditionArgumentKind.BOSS_BIT_MASK -> argument in 0..255
                ProjectRoomStateConditionArgumentKind.DOOR_POINTER -> if (c.sourceDoor != null) argument == null else argument in 0x8000..0xFFFF
                ProjectRoomStateConditionArgumentKind.EQUIPMENT_MASK, ProjectRoomStateConditionArgumentKind.BEAM_MASK -> argument != null && argument in 1..65535 && argument.countOneBits() == 1
                ProjectRoomStateConditionArgumentKind.CAPACITY -> argument in 0..65535
                ProjectRoomStateConditionArgumentKind.ITEM_BIT_INDEX, ProjectRoomStateConditionArgumentKind.DOOR_BIT_INDEX, ProjectRoomStateConditionArgumentKind.CHOZO_BLOCK_BIT_INDEX -> argument in 0..511
                ProjectRoomStateConditionArgumentKind.AREA_AND_BOSS_MASK -> argument != null && argument in 0..65535 && argument ushr 8 in 0..7 && argument and 255 in RoomStateCondition.BOSS_FLAG_MASKS
            }
            require(valid && (c.argumentKind == ProjectRoomStateConditionArgumentKind.CHILDREN || c.children.isEmpty())) { "Invalid ${c.kind} selector argument/tree." }
            c.children.forEach { validate(it, depth + 1) }
        }
        conditions.values.forEach { nodes = 0; validate(it, 0) }
    }
}

package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomStateConditionPickerTest {
    @Test
    fun `picker groups every selectable leaf exactly once`() {
        val actual = STATE_CONDITION_KIND_PICKER_GROUPS.flatMap { it.kinds }
        val expected = ProjectRoomStateConditionKind.entries.filter {
            it != ProjectRoomStateConditionKind.DEFAULT &&
                it != ProjectRoomStateConditionKind.ALL_OF &&
                it != ProjectRoomStateConditionKind.ANY_OF
        }

        assertEquals(expected.toSet(), actual.toSet())
        assertEquals(actual.size, actual.distinct().size)
        assertTrue(STATE_CONDITION_KIND_PICKER_GROUPS.all { it.title.isNotBlank() && it.kinds.isNotEmpty() })
    }

    @Test
    fun `threshold picker labels use N instead of a stale default value`() {
        assertEquals(
            "Super Missile capacity ≥ N",
            stateConditionKindPickerLabel(ProjectRoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST),
        )
        assertEquals(
            "Current Power Bombs ≥ N",
            stateConditionKindPickerLabel(ProjectRoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST),
        )
    }
}

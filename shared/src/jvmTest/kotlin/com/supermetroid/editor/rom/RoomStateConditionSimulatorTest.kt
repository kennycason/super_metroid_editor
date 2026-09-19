package com.supermetroid.editor.rom

import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.data.RoomStateEdits
import com.supermetroid.editor.data.RoomStateResourceLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomStateConditionSimulatorTest {
    @Test
    fun `nested AND OR NOT conditions evaluate against load-time values`() {
        val condition = projectRoomStateCondition(
            ProjectRoomStateConditionKind.ALL_OF,
            children = listOf(
                projectRoomStateCondition(ProjectRoomStateConditionKind.ESCAPE_ACTIVE),
                projectRoomStateCondition(
                    ProjectRoomStateConditionKind.ANY_OF,
                    children = listOf(
                        projectRoomStateCondition(ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED, 0x0001),
                        projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST, 10),
                    ),
                ),
                projectRoomStateCondition(
                    ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED,
                    0x51,
                    negated = true,
                ),
            ),
        )

        assertTrue(
            condition.matches(
                RoomStateSimulationContext(
                    area = 2,
                    events = setOf(0x0E),
                    currentMissiles = 12,
                )
            )
        )
        assertFalse(
            condition.matches(
                RoomStateSimulationContext(
                    area = 2,
                    events = setOf(0x0E),
                    currentMissiles = 12,
                    collectedItemPickupIds = setOf(0x51),
                )
            )
        )
        assertFalse(condition.matches(RoomStateSimulationContext(area = 2, currentMissiles = 12)))
    }

    @Test
    fun `first matching branch wins and default remains fallback`() {
        val links = RoomStateResourceLinks("l", "f", "e", "g", "s", "p", "b", "x")
        val states = listOf(
            RoomStateEdits(
                id = "low-health",
                condition = projectRoomStateCondition(
                    ProjectRoomStateConditionKind.CURRENT_ENERGY_AT_LEAST,
                    30,
                    negated = true,
                ),
                resources = links,
            ),
            RoomStateEdits(
                id = "escape",
                condition = projectRoomStateCondition(ProjectRoomStateConditionKind.ESCAPE_ACTIVE),
                resources = links,
            ),
            RoomStateEdits(
                id = "default",
                condition = projectRoomStateCondition(ProjectRoomStateConditionKind.DEFAULT),
                resources = links,
            ),
        )
        val result = simulateRoomStateGraph(
            states,
            RoomStateSimulationContext(area = 0, currentEnergy = 20, events = setOf(0x0E)),
        )

        assertEquals(listOf(true, true, true), result.map { it.matches })
        assertEquals("low-health", result.single { it.selected }.stateId)
    }
}

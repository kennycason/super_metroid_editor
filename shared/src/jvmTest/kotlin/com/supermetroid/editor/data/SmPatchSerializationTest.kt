package com.supermetroid.editor.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmPatchSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `exclusive variant group survives project round trip`() {
        val project = SmEditProject(romPath = "/tmp/sm.smc")
        project.patches.add(
            SmPatch(
                id = "hold_aim_down",
                name = "Hold Aim Down",
                exclusiveGroup = "spider_ball_activation",
            )
        )

        val encoded = json.encodeToString(SmEditProject.serializer(), project)
        val decoded = json.decodeFromString(SmEditProject.serializer(), encoded)

        assertEquals("spider_ball_activation", decoded.patches.single().exclusiveGroup)
    }

    @Test
    fun `legacy patch json defaults to no exclusive variant group`() {
        val decoded = json.decodeFromString(
            SmPatch.serializer(),
            """{"id":"legacy","name":"Legacy"}""",
        )

        assertNull(decoded.exclusiveGroup)
    }
}

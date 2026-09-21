package com.supermetroid.editor.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `runtime effect custom items round trip without changing legacy inventory defaults`() {
        val effectItem = CustomItemDef(
            id = "hyper_beam",
            name = "Hyper Beam",
            shortLabel = "HB",
            inventoryTracked = false,
        )
        val decodedEffect = json.decodeFromString(
            CustomItemDef.serializer(),
            json.encodeToString(CustomItemDef.serializer(), effectItem),
        )
        val decodedLegacy = json.decodeFromString(
            CustomItemDef.serializer(),
            """{"id":"legacy","name":"Legacy","shortLabel":"L"}""",
        )

        assertEquals(false, decodedEffect.inventoryTracked)
        assertTrue(decodedLegacy.inventoryTracked)
    }
}

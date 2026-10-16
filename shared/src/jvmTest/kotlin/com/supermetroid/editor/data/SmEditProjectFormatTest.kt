package com.supermetroid.editor.data

import kotlinx.serialization.json.*
import kotlin.test.*

class SmEditProjectFormatTest {
    private fun minimal() = buildJsonObject {
        put("projectFormat", SmEditProject.PROJECT_FORMAT)
        put("projectFormatVersion", SmEditProject.CURRENT_PROJECT_FORMAT_VERSION)
        put("projectId", "format-test")
        put("romPath", "base.smc")
    }

    @Test fun `current envelope is explicit even with a serializer that omits default values`() {
        val project = SmEditProject("base.smc")
        val encoded = Json.encodeToString(SmEditProject.serializer(), project)
        assertContains(encoded, "\"projectFormat\":\"smedit-project\"")
        assertContains(encoded, "\"projectFormatVersion\":1")
        assertEquals(project, SmEditProjectFormat.decode(encoded))
        assertEquals(project.projectId, SmEditProjectFormat.decode(SmEditProjectFormat.encode(project)).projectId)
        assertNotEquals(project.projectId, SmEditProject("base.smc").projectId)
        assertTrue(project.projectId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
    }

    @Test fun `optional domain values default normally without a legacy upgrade layer`() {
        val project = SmEditProjectFormat.decode(minimal().toString())
        assertTrue(project.rooms.isEmpty()); assertTrue(project.newRooms.isEmpty())
        assertFalse(project.asmWorkspace.enabled)
        assertEquals(ProjectRomBuildMode.PATCHED_ROM, project.asmWorkspace.buildMode)
        assertEquals(PatchSortOrder.NAME_ASCENDING, project.generalSettings.patchBrowser.sortOrder)
        assertEquals(project, SmEditProjectFormat.decode(SmEditProjectFormat.encode(project)))
    }

    @Test fun `missing old future malformed and wrong format headers are rejected before their contents`() {
        val header = minimal()
        val bad = listOf(JsonPrimitive(0), JsonPrimitive(2), JsonPrimitive(21), JsonPrimitive("1"), JsonNull, JsonPrimitive(true), JsonPrimitive(1.5))
        bad.forEach { version ->
            val text = JsonObject(header + ("projectFormatVersion" to version) + ("rooms" to JsonPrimitive("invalid"))).toString()
            assertContains(assertFailsWith<IllegalArgumentException> { SmEditProjectFormat.decode(text) }.message.orEmpty(), "Unsupported project format")
        }
        for (key in listOf("projectFormat", "projectFormatVersion")) {
            assertFailsWith<IllegalArgumentException> { SmEditProjectFormat.decode(JsonObject(header - key).toString()) }
        }
        assertFailsWith<IllegalArgumentException> { SmEditProjectFormat.decode("""{"romPath":"base.smc"}""") }
        assertFailsWith<IllegalArgumentException> { SmEditProjectFormat.decode(JsonObject(header + ("projectFormat" to JsonPrimitive("other"))).toString()) }
    }

    @Test fun `unknown fields and invalid enums are errors rather than silently discarded authoring data`() {
        assertFails { SmEditProjectFormat.decode(JsonObject(minimal() + ("unknown" to JsonPrimitive(1))).toString()) }
        assertFails { SmEditProjectFormat.decode(JsonObject(minimal() + ("asmWorkspace" to buildJsonObject {
            put("enabled", true); put("buildMode", "FUTURE_ASM_MODE")
        })).toString()) }
        assertFails { SmEditProjectFormat.decode(JsonObject(minimal() + ("asmWorkspace" to buildJsonObject {
            put("enabled", JsonNull)
        })).toString()) }
        assertFails { SmEditProjectFormat.decode(JsonObject(minimal() + ("rooms" to buildJsonObject {
            put("91F8", buildJsonObject { put("roomId", 0x91F8); put("unknownResourceOwner", 1) })
        })).toString()) }
        assertFails { SmEditProjectFormat.decode(JsonObject(minimal() - "projectId").toString()) }
        for (key in listOf("projectId", "romPath")) {
            assertFails { SmEditProjectFormat.decode(JsonObject(minimal() + (key to JsonPrimitive(7))).toString()) }
            assertFails { SmEditProjectFormat.decode(JsonObject(minimal() + (key to JsonNull)).toString()) }
        }
        assertFailsWith<IllegalArgumentException> { SmEditProjectFormat.decode(JsonObject(minimal() + ("projectId" to JsonPrimitive(""))).toString()) }
        assertFailsWith<IllegalArgumentException> { SmEditProjectFormat.encode(SmEditProject("base.smc", projectId = "")) }
        assertFailsWith<IllegalArgumentException> { SmEditProjectFormat.encode(SmEditProject("base.smc", projectFormatVersion = 21)) }
    }
}

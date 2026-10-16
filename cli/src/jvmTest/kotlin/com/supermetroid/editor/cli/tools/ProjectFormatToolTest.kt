package com.supermetroid.editor.cli.tools

import com.supermetroid.editor.data.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class ProjectFormatToolTest {
    @TempDir lateinit var directory: File

    @Test fun `checked in active projects all use the current complete baseline without requiring ROM fixtures`() {
        val root = File(System.getProperty("smedit.repositoryRoot"), "projects")
        val files = root.listFiles()!!.filter { it.isDirectory }.flatMap { folder ->
            folder.listFiles().orEmpty().filter { it.extension == "smedit" }
        }
        assertTrue(files.isNotEmpty(), "No active project files found")
        files.forEach { file ->
            val plan = planProjectNormalization(file)
            assertEquals(plan.before.length, plan.after.length, "Noncanonical project: $file")
            assertTrue(plan.before == plan.after, "Project needs explicit normalization: $file")
            val project = SmEditProjectFormat.decode(plan.after)
            assertTrue(project.projectId.isNotBlank())
            assertNotNull(project.baseRom, "Missing base ROM identity: $file")
        }
    }

    @Test fun `offline normalization preserves authored values adds defaults and is idempotent with recovery copies`() {
        val original = """{"projectFormatVersion":18,"romPath":"base.smc","projectId":"stable-id","rooms":{"91F8":{"roomId":37368,"asmDoorSourceVersion":1}},"asmWorkspace":{"enabled":true,"buildMode":"ASM_SOURCE"},"patches":[{"id":"custom","name":"Authored patch","writes":[{"offset":123,"bytes":[7,8]}]}]}"""
        val file = File(directory, "Test.smedit").apply { writeText(original) }
        val plan = planProjectNormalization(file)
        assertEquals(original, file.readText(), "Planning must be read-only")
        val normalized = SmEditProjectFormat.decode(plan.after)
        assertEquals("stable-id", normalized.projectId)
        assertEquals(ProjectRomBuildMode.ASM_SOURCE, normalized.asmWorkspace.buildMode)
        assertEquals(1, normalized.rooms.getValue("91F8").asmDoorSourceVersion)
        assertEquals(listOf(7, 8), normalized.patches.single().writes.single().bytes)
        assertContains(plan.after, "\"newRooms\":[]")
        val recovery = assertNotNull(applyProjectNormalizations(listOf(plan)) {})
        assertEquals(original, recovery.listFiles()!!.single().readText())
        assertEquals(plan.after, file.readText())
        val repeat = planProjectNormalization(file)
        assertEquals(repeat.before, repeat.after)
        assertNull(applyProjectNormalizations(listOf(repeat)) {})
    }

    @Test fun `unknown or future data and source races fail without overwriting files`() {
        val file = File(directory, "Unknown.smedit").apply { writeText("""{"romPath":"base.smc","unmodeledEdit":5}""") }
        assertFails { planProjectNormalization(file) }; assertContains(file.readText(), "unmodeledEdit")
        file.writeText("""{"romPath":"base.smc","projectId":{"doNotDiscard":"authored"}}""")
        assertFails { planProjectNormalization(file) }; assertContains(file.readText(), "doNotDiscard")
        file.writeText(SmEditProjectFormat.encode(SmEditProject("base.smc")))
        val current = Json.parseToJsonElement(file.readText()).jsonObject
        file.writeText(JsonObject(current + ("projectFormatVersion" to JsonPrimitive(2))).toString())
        assertFails { planProjectNormalization(file) }
        file.writeText("""{"romPath":"base.smc"}""")
        val plan = planProjectNormalization(file)
        assertTrue(SmEditProjectFormat.decode(plan.after).projectId.isNotBlank())
        assertEquals(plan.after, planProjectNormalization(file).after, "Missing IDs are filled deterministically during the explicit conversion")
        file.writeText("new user edit")
        assertFailsWith<IllegalArgumentException> { applyProjectNormalizations(listOf(plan)) {} }
        assertEquals("new user edit", file.readText())
    }
}

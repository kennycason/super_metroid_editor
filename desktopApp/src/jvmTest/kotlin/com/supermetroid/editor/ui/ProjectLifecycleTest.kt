package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ProjectPaths
import com.supermetroid.editor.data.SmEditProject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProjectLifecycleTest {
    @TempDir lateinit var temporary: File

    private fun rom(name: String = "Super Metroid [JU].smc", header: Boolean = false): File {
        val body = ByteArray(0x300000)
        "Super Metroid        ".encodeToByteArray().copyInto(body, 0x7FC0)
        body[0x7FD5] = 0x30
        body[0x7FD6] = 2
        body[0x7FD7] = 0x0C
        body[0x7FDC] = 0xFF.toByte()
        body[0x7FDD] = 0xFF.toByte()
        for (room in com.supermetroid.editor.data.RoomRepository().getAllRooms()) {
            val pc = 0x78000 + (room.getRoomIdAsInt() and 0x7FFF)
            body[pc + 4] = 1
            body[pc + 5] = 1
            body[pc + 6] = 0x70
            body[pc + 7] = 0xA0.toByte()
            body[pc + 11] = 0xE6.toByte()
            body[pc + 12] = 0xE5.toByte()
        }
        return File(temporary, name).apply { writeBytes(if (header) ByteArray(512) + body else body) }
    }

    @Test fun `new project preserves original ROM name and persists a portable identity`() {
        val original = rom()
        val originalBytes = original.readBytes()
        val open = ProjectLifecycle.create("ASM Sandbox", temporary, original)
        val paths = ProjectPaths(open.file)
        assertEquals("ASM Sandbox.smedit", open.file.name)
        assertEquals(File(temporary, "ASM Sandbox"), paths.root)
        assertEquals(File(paths.root, original.name).absolutePath, open.project.romPath)
        assertArrayEquals(originalBytes, File(open.project.romPath).readBytes())
        assertArrayEquals(originalBytes, original.readBytes())
        assertFalse(open.project.asmWorkspace.enabled)
        assertTrue(open.project.projectId.isNotBlank())
        assertEquals(original.name, open.project.baseRom?.originalFileName)
        val json = Json.parseToJsonElement(open.file.readText()).jsonObject
        assertEquals(original.name, json.getValue("romPath").jsonPrimitive.content)
        assertFalse(File(paths.root, "ASM Sandbox_smedit").exists())
        assertFalse(paths.data.exists(), "Unused asset directories are not eagerly created")
        val ignore = File(paths.root, ".gitignore").readText()
        assertTrue(ignore.contains("/smedit/"))
        assertTrue(ignore.contains("/states/"))
    }

    @Test fun `project directory can move without changing ROM reference or project identity`() {
        val open = ProjectLifecycle.create("Portable", temporary, rom())
        val movedRoot = File(temporary, "Moved")
        assertTrue(open.file.parentFile.renameTo(movedRoot))
        val moved = ProjectFileService.loadProject(File(movedRoot, open.file.name))
        assertEquals(File(movedRoot, "Super Metroid [JU].smc").absolutePath, moved.romPath)
        assertEquals(open.project.projectId, moved.projectId)
        ProjectLifecycle.verify(moved, File(moved.romPath))
    }

    @Test fun `projects made from the same base have distinct identities and save-state folders`() {
        val base = rom()
        val first = ProjectLifecycle.create("First", temporary, base)
        val second = ProjectLifecycle.create("Second", temporary, base)
        assertNotEquals(first.project.projectId, second.project.projectId)
        assertNotEquals(ProjectPaths(first.file).emulatorStates, ProjectPaths(second.file).emulatorStates)
        assertEquals(first.project.baseRom, second.project.baseRom)
    }

    @Test fun `opening base or output ROM finds the project not an archived nested project`() {
        val open = ProjectLifecycle.create("Sandbox", temporary, rom())
        File(open.file.parentFile, "archive/Old.smedit").apply { parentFile.mkdirs(); writeText("not a project") }
        assertEquals(open.file, ProjectLifecycle.projectBeside(File(open.project.romPath)))
        assertEquals(open.file, ProjectLifecycle.projectBeside(File(open.file.parentFile, "Sandbox-v1.0.smc")))
        assertEquals(open.file, ProjectLifecycle.projectBeside(open.file))
        assertEquals(open.file, ProjectLifecycle.projectBeside(open.file.parentFile))
        File(open.file.parentFile, "Other.smedit").writeText("{}")
        assertThrows<IllegalArgumentException> { ProjectLifecycle.projectBeside(File(open.project.romPath)) }
        assertEquals(open.file, ProjectLifecycle.projectBeside(open.file), "Explicit project opening remains unambiguous")
    }

    @Test fun `missing ROM accepts a renamed headered copy but refuses a different revision`() {
        val open = ProjectLifecycle.create("Missing", temporary, rom())
        assertTrue(File(open.project.romPath).delete())
        assertThrows<IllegalArgumentException> { ProjectLifecycle.verify(open.project, File(open.project.romPath)) }
        val wrong = rom("Wrong.smc").apply {
            val bytes = readBytes(); bytes[42] = 123; writeBytes(bytes)
        }
        val before = open.file.readBytes()
        assertThrows<IllegalArgumentException> { ProjectLifecycle.importBase(open, wrong) }
        assertArrayEquals(before, open.file.readBytes())
        assertFalse(File(open.file.parentFile, wrong.name).exists())
        val replacement = rom("Renamed.sfc", header = true)
        val repaired = ProjectLifecycle.importBase(open, replacement)
        assertEquals(File(open.file.parentFile, replacement.name).absolutePath, repaired.project.romPath)
        assertEquals("Super Metroid [JU].smc", repaired.project.baseRom?.originalFileName)
        assertEquals(open.project.baseRom, repaired.project.baseRom)
        val reopened = ProjectFileService.loadProject(open.file)
        ProjectLifecycle.verify(reopened, File(reopened.romPath))
        assertEquals(repaired.project.romPath, reopened.romPath)
    }

    @Test fun `creation and ROM import never overwrite existing user files`() {
        val base = rom()
        val occupied = File(temporary, "Occupied").apply { mkdirs() }
        val notes = File(occupied, "notes.txt").apply { writeText("keep") }
        assertThrows<IllegalArgumentException> { ProjectLifecycle.create("Occupied", temporary, base) }
        assertEquals("keep", notes.readText())
        val open = ProjectLifecycle.create("Import", temporary, base)
        val before = File(open.project.romPath).readBytes()
        assertThrows<IllegalArgumentException> { ProjectLifecycle.importBase(open, base) }
        assertArrayEquals(before, File(open.project.romPath).readBytes())
    }

    @Test fun `project-named output and generic folders are independent of base name`() {
        val open = ProjectLifecycle.create("Sandbox", temporary, rom())
        val paths = ProjectPaths(open.file)
        assertEquals("Sandbox-v1.0.smc", paths.romOutput(open.project).name)
        open.project.buildName = "preview"
        assertEquals("Sandbox-preview-v1.0.smc", paths.romOutput(open.project).name)
        assertEquals(File(paths.root, "states"), paths.emulatorStates)
        assertEquals(File(paths.root, "asm"), paths.asm)
        assertEquals(File(paths.root, "smedit"), paths.cache)
        assertEquals(File(paths.cache, "asm"), paths.asmCache)
        assertEquals(File(paths.root, "smedit/asm/build"), paths.asmBuild)
        assertEquals(File(paths.root, "exports"), paths.exports)
        assertThrows<IllegalArgumentException> { paths.romOutput(open.project.copy(buildName = "../../unsafe")) }
        assertThrows<IllegalArgumentException> { paths.romOutput(open.project.copy(romPath = File(paths.root, "Sandbox-preview-v1.0.smc").path)) }
    }

    @Test fun `unsafe names and malformed project files fail without a blank replacement project`() {
        listOf("../escape", "..", "bad/name", "bad\\name", "CON", "name.", "bad:name").forEach { name ->
            assertThrows<IllegalArgumentException> { ProjectPaths.requireFileName(name) }
        }
        val damaged = File(temporary, "Broken.smedit").apply { writeText("{broken") }
        assertThrows<Exception> { ProjectFileService.loadProject(damaged) }
        assertEquals("{broken", damaged.readText())
    }

    @Test fun `saving refuses an external base and keeps existing project intact`() {
        val file = File(temporary, "Project.smedit").apply { writeText("keep") }
        val external = File(temporary, "elsewhere/Base.smc").apply { parentFile.mkdirs(); writeBytes(ByteArray(0x8000)) }
        assertFalse(ProjectFileService.saveProject(SmEditProject(external.path), file.path, null, false) {})
        assertEquals("keep", file.readText())
    }

    @Test fun `opening metadata-less projects refuses implicit migration without rewriting them`() {
        val file = File(temporary, "Existing.smedit").apply { writeText("""{"romPath":"Base.smc"}""") }
        val original = file.readText()
        assertThrows<IllegalArgumentException> { ProjectFileService.loadProject(file) }
        assertEquals(original, file.readText())
    }

    @Test fun `saved project preferences follow identity but read only ROM preferences follow the ROM`() {
        val project = SmEditProject("Base.smc")
        assertEquals(project.projectId, ProjectLifecycle.preferenceKey(project, "/one/Project.smedit", project.romPath))
        assertEquals(project.projectId, ProjectLifecycle.preferenceKey(project, "/moved/Project.smedit", project.romPath))
        assertEquals(ProjectLifecycle.preferenceKey(project, "", project.romPath),
            ProjectLifecycle.preferenceKey(SmEditProject(project.romPath), "", project.romPath))
    }
}

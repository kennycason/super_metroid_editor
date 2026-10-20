package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assumptions.assumeTrue

class AsmBaselineBackupsTest {
    @TempDir lateinit var temporary: File
    private data class Fixture(val project: File, val paths: ProjectPaths, val backups: AsmBaselineBackupRepository) {
        fun createBackup(): File {
            val stage = File(paths.cache, "stage-${UUID.randomUUID()}")
            assertTrue(paths.asm.copyRecursively(File(stage, "asm")))
            assertTrue(paths.asmCache.copyRecursively(File(stage, "smedit/asm")))
            return AsmBaselineUpdateTransaction.install(project.path, stage)
        }
    }
    private fun fixture(): Fixture {
        val root = File(temporary, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc bank_80.asm\n")
        File(src, "bank_80.asm").writeText("Root:\n RTL\n")
        val reference = AsmReferenceWorkspace(root, AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL,
            AsmReferenceContract.COMMIT, "fixture", "fixture.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val project = File(temporary, "Project-${UUID.randomUUID()}/Test.smedit").apply { parentFile.mkdirs(); writeText("{\"keep\":true}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList(), AsmSourceAugmentation("test-v1"), emptyList())
        repository.initialize(project.path, reference)
        repository.createModule(project.path, "Objectives", initialSource = "Objectives:\n RTL\n")
        return Fixture(project, ProjectPaths.forProject(project.path), AsmBaselineBackupRepository(repository))
    }
    private fun inventory(directory: File) = AsmBaselineUpdateRepository.inventory(directory)
    private fun symlink(link: Path, target: Path) {
        try { Files.createSymbolicLink(link, target) }
        catch (problem: java.io.IOException) { assumeTrue(false, "Symlink creation is unavailable on this runner: ${problem.message}") }
        catch (problem: UnsupportedOperationException) { assumeTrue(false, "This filesystem does not support symbolic links") }
    }

    @Test fun `opening an empty backup list creates nothing and does not touch active source`() {
        val f = fixture()
        val before = inventory(f.paths.asm)
        assertTrue(f.backups.list(f.project.path).entries.isEmpty())
        assertFalse(File(f.paths.root, "asm-backups").exists())
        assertFalse(File(f.paths.cache, "asm-update-backups").exists())
        assertEquals(before, inventory(f.paths.asm))
    }

    @Test fun `discovery reports frozen revision combined size date and stable identity without changing files`() {
        val f = fixture()
        val backup = f.createBackup()
        val cached = File(f.paths.cache, "asm-update-backups/${backup.name}")
        val before = inventory(backup)
        val entry = f.backups.list(f.project.path).entries.single()
        assertEquals(backup.name, entry.id)
        assertEquals("test-v1", entry.revision)
        assertTrue(entry.createdAtMillis > 0)
        assertEquals((backup.walkTopDown() + cached.walkTopDown()).filter { it.isFile }.sumOf { it.length() }, entry.bytes)
        assertNull(entry.problem)
        assertNotNull(entry.fingerprint)
        assertEquals(entry, f.backups.list(f.project.path).entries.single())
        assertEquals(backup.canonicalFile, f.backups.folder(f.project.path, entry).canonicalFile)
        assertEquals(before, inventory(backup))
    }

    @Test fun `confirmed deletion removes only the selected backup pair not active modules project or other backups`() {
        val f = fixture()
        val first = f.createBackup()
        val second = f.createBackup()
        val beforeActive = inventory(f.paths.asm)
        val beforeProject = f.project.readBytes()
        val beforeSecond = inventory(second)
        val selected = f.backups.list(f.project.path).entries.single { it.id == first.name }
        f.backups.delete(f.project.path, selected)
        assertFalse(first.exists())
        assertFalse(File(f.paths.cache, "asm-update-backups/${first.name}").exists())
        assertEquals(beforeActive, inventory(f.paths.asm))
        assertContentEquals(beforeProject, f.project.readBytes())
        assertEquals(beforeSecond, inventory(second))
        assertEquals(listOf(second.name), f.backups.list(f.project.path).entries.map { it.id })
    }

    @Test fun `authored changes invalidate confirmation even when size and modification time are retained`() {
        val f = fixture()
        val backup = f.createBackup()
        val selected = f.backups.list(f.project.path).entries.single()
        val module = File(backup, "asm/modules/Objectives.asm")
        val mtime = Files.getLastModifiedTime(module.toPath())
        module.writeText(module.readText().replace("RTL", "RTS"))
        Files.setLastModifiedTime(module.toPath(), mtime)
        assertFailsWith<IllegalArgumentException> { f.backups.delete(f.project.path, selected) }
        assertTrue(backup.isDirectory)
        assertContains(module.readText(), "RTS")
    }

    @Test fun `any pending or malformed update journal protects all recovery copies`() {
        val f = fixture()
        val backup = f.createBackup()
        val selected = f.backups.list(f.project.path).entries.single()
        File(f.paths.cache, "asm-baseline-update.json").writeText("incomplete journal")
        val listing = f.backups.list(f.project.path)
        assertNotNull(listing.notice)
        assertNotNull(listing.entries.single().problem)
        assertFailsWith<IllegalArgumentException> { f.backups.delete(f.project.path, selected) }
        assertTrue(backup.exists())
    }

    @Test fun `interrupted installation artifacts incomplete copies and unknown folders are retained`() {
        val f = fixture()
        val backup = f.createBackup()
        File(f.paths.cache, "asm-update-backups/${backup.name}/uncommitted-asm-test").mkdirs()
        val incomplete = File(f.paths.root, "asm-backups/${UUID.randomUUID()}").apply { mkdirs() }
        File(f.paths.root, "asm-backups/handwritten-notes").apply { mkdirs() }
        val listing = f.backups.list(f.project.path)
        assertNotNull(listing.notice)
        assertEquals(2, listing.entries.size)
        listing.entries.forEach {
            assertNotNull(it.problem)
            assertFailsWith<IllegalArgumentException> { f.backups.delete(f.project.path, it) }
        }
        assertTrue(backup.exists())
        assertTrue(incomplete.exists())
    }

    @Test fun `links introduced after confirmation are not followed or deleted`() {
        val f = fixture()
        val backup = f.createBackup()
        val selected = f.backups.list(f.project.path).entries.single()
        val outside = File(temporary, "Outside").apply { mkdirs() }
        val note = File(outside, "keep.txt").apply { writeText("keep") }
        symlink(File(backup, "asm/outside").toPath(), outside.toPath())
        assertNotNull(f.backups.list(f.project.path).entries.single().problem)
        assertFailsWith<IllegalArgumentException> { f.backups.delete(f.project.path, selected) }
        assertEquals("keep", note.readText())
        assertTrue(backup.exists())
    }

    @Test fun `symlinked backup ancestors are rejected before discovery or deletion`() {
        for (relative in listOf("asm-backups", "smedit/asm-update-backups")) {
            val f = fixture()
            f.createBackup()
            val selected = f.backups.list(f.project.path).entries.single()
            val original = File(f.paths.root, relative)
            val moved = File(temporary, "moved-${UUID.randomUUID()}")
            Files.move(original.toPath(), moved.toPath())
            symlink(original.toPath(), moved.toPath())
            assertFailsWith<IllegalArgumentException> { f.backups.list(f.project.path) }
            assertFailsWith<IllegalArgumentException> { f.backups.delete(f.project.path, selected) }
            assertTrue(File(moved, selected.id).exists())
            Files.delete(original.toPath())
            Files.move(moved.toPath(), original.toPath())
        }
    }

    @Test fun `forged identities cross-project entries and already removed backups cannot be deleted`() {
        val f = fixture()
        val backup = f.createBackup()
        val selected = f.backups.list(f.project.path).entries.single()
        for (id in listOf("..", "../asm", "", "asm", "${selected.id}/../..")) {
            assertFailsWith<IllegalArgumentException> { f.backups.delete(f.project.path, selected.copy(id = id)) }
            assertFailsWith<IllegalArgumentException> { f.backups.folder(f.project.path, selected.copy(id = id)) }
        }
        assertFailsWith<IllegalArgumentException> { f.backups.delete(File(temporary, "Other/Test.smedit").path, selected) }
        assertTrue(backup.exists())
        f.backups.delete(f.project.path, selected)
        assertFailsWith<IllegalArgumentException> { f.backups.delete(f.project.path, selected) }
    }
}

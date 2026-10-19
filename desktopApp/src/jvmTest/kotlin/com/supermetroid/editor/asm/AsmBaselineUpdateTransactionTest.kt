package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectPaths
import java.io.File
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmBaselineUpdateTransactionTest {
    @TempDir lateinit var temporary: File
    private class SimulatedProcessDeath : Error()
    private fun write(file: File, text: String) { file.parentFile.mkdirs(); file.writeText(text) }

    @Test fun `opening a workspace rolls back an interruption after any installation move`() {
        for (checkpoint in 1..4) {
            val project = File(temporary, "project-$checkpoint/Test.smedit")
            val paths = ProjectPaths.forProject(project.path)
            write(File(paths.asm, "modules/objectives.asm"), "old objectives")
            write(File(paths.asmCache, "old-cache.txt"), "old cache")
            val staging = File(paths.cache, "candidate")
            write(File(staging, "asm/modules/objectives.asm"), "updated objectives")
            write(File(staging, "smedit/asm/new-cache.txt"), "new cache")
            assertFailsWith<SimulatedProcessDeath> {
                AsmBaselineUpdateTransaction.install(project.path, staging) { if (it == checkpoint) throw SimulatedProcessDeath() }
            }
            assertTrue(File(paths.cache, "asm-baseline-update.json").exists())
            // Even before metadata/index validation, a load must recover both old directory identities.
            assertNull(AsmProjectWorkspaceRepository().loadForBuild(project.path))
            assertEquals("old objectives", File(paths.asm, "modules/objectives.asm").readText())
            assertEquals("old cache", File(paths.asmCache, "old-cache.txt").readText())
            assertFalse(File(paths.asmCache, "new-cache.txt").exists())
            assertFalse(File(paths.cache, "asm-baseline-update.json").exists())
            if (checkpoint >= 3) assertTrue(File(paths.cache, "asm-update-backups").walkTopDown()
                .any { it.isFile && it.readText() == "updated objectives" }, "Partly installed source must remain recoverable")
        }
    }

    @Test fun `ordinary installation failures roll back immediately and successful updates retain old authored inputs`() {
        val project = File(temporary, "Test.smedit")
        val paths = ProjectPaths.forProject(project.path)
        write(File(paths.asm, "old.txt"), "original")
        write(File(paths.asmCache, "old.txt"), "original cache")
        val stage = File(paths.cache, "candidate")
        write(File(stage, "asm/new.txt"), "new")
        write(File(stage, "smedit/asm/new.txt"), "new cache")
        assertFailsWith<IllegalStateException> { AsmBaselineUpdateTransaction.install(project.path, stage) { if (it == 2) error("failed move") } }
        assertEquals("original", File(paths.asm, "old.txt").readText())
        val backup = AsmBaselineUpdateTransaction.install(project.path, stage)
        assertEquals("new", File(paths.asm, "new.txt").readText())
        assertEquals("original", File(backup, "asm/old.txt").readText())
        assertEquals("*\n", File(backup, ".gitignore").readText(), "Recovery snapshots must not be committed as project source")
        assertFalse(stage.exists())
    }

    @Test fun `recovery backups ignore their entire contents in a standalone project repository`() {
        val project = File(temporary, "Standalone project/Test.smedit")
        val paths = ProjectPaths.forProject(project.path)
        write(File(paths.asm, "modules/objectives.asm"), "old objectives")
        write(File(paths.asmCache, "old.txt"), "old cache")
        val stage = File(paths.cache, "candidate")
        write(File(stage, "asm/modules/objectives.asm"), "new objectives")
        write(File(stage, "smedit/asm/new.txt"), "new cache")
        val backup = AsmBaselineUpdateTransaction.install(project.path, stage)
        fun git(vararg arguments: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf("git", "-C", paths.root.path) + arguments)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return process.waitFor() to output
        }
        assertEquals(0, git("init", "-q").first)
        val oldModule = File(backup, "asm/modules/objectives.asm").relativeTo(paths.root).invariantSeparatorsPath
        assertEquals(0, git("check-ignore", "--quiet", "--", oldModule).first)
        assertEquals(1, git("check-ignore", "--quiet", "--", "asm/modules/objectives.asm").first,
            "The active module must remain eligible for source control")
        assertEquals("old objectives", File(backup, "asm/modules/objectives.asm").readText())
        assertEquals("new objectives", File(paths.asm, "modules/objectives.asm").readText())
    }

    @Test fun `committed journal recovery keeps the new workspace and rejects unsafe backup identities`() {
        val project = File(temporary, "Test.smedit")
        val paths = ProjectPaths.forProject(project.path)
        write(File(paths.asm, "new.txt"), "new")
        write(File(paths.cache, "asm-baseline-update.json"), "{\"id\":\"${UUID.randomUUID()}\",\"committed\":true}")
        AsmBaselineUpdateTransaction.recover(project.path)
        assertEquals("new", File(paths.asm, "new.txt").readText())
        write(File(paths.cache, "asm-baseline-update.json"), "{\"id\":\"../outside\"}")
        assertFailsWith<IllegalArgumentException> { AsmBaselineUpdateTransaction.recover(project.path) }
        assertEquals("new", File(paths.asm, "new.txt").readText())
    }
}

package com.supermetroid.editor.asm

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AsmProjectWorkspaceTest {
    @TempDir
    lateinit var tempDirectory: File

    @Test
    fun `project workspace is isolated persistent and restorable from its offline snapshot`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range))
        val projectFile = File(tempDirectory, "My Hack.smedit").apply { writeText("{}") }
        val referenceSource = File(reference.root, "src/bank_80.asm")
        val originalText = referenceSource.readText()

        val created = repository.initialize(projectFile.absolutePath, reference)

        assertTrue(File(created.root, "original/src/bank_80.asm").isFile)
        assertTrue(File(created.root, "workspace/src/bank_80.asm").isFile)
        assertEquals(emptySet<String>(), created.modifiedFileIds)

        val editedText = originalText.replace("RTS", "NOP\n    RTS")
        val saved = repository.saveSource(projectFile.absolutePath, "bank_80.asm", editedText)

        assertEquals(originalText, referenceSource.readText(), "project edits must never mutate the global reference")
        assertEquals(editedText, File(saved.workingDirectory, "src/bank_80.asm").readText())
        assertEquals(editedText, File(saved.root, "overrides/src/bank_80.asm").readText())
        assertTrue(File(saved.root, ".gitignore").readText().contains("/workspace/"))
        assertEquals(setOf("bank_80.asm"), saved.modifiedFileIds)
        assertEquals(setOf("bank_80.asm"), repository.sourceOverrideFileIds(projectFile.absolutePath))
        val reopened = requireNotNull(repository.load(projectFile.absolutePath))
        assertNotNull(reopened)
        assertEquals(editedText, reopened.referenceWorkspace.index.file("bank_80.asm")?.file?.readText())

        // The immutable project snapshot, not the global cache, owns restore.
        referenceSource.writeText("Global reference changed after project creation")
        val restored = repository.restoreSource(projectFile.absolutePath, "bank_80.asm")
        assertEquals(originalText, File(restored.workingDirectory, "src/bank_80.asm").readText())
        assertFalse(File(restored.root, "overrides/src/bank_80.asm").exists())
        assertEquals(emptySet<String>(), restored.modifiedFileIds)
        assertEquals(emptySet<String>(), repository.sourceOverrideFileIds(projectFile.absolutePath))
    }

    @Test
    fun `source writes cannot escape the managed project tree`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range))
        val projectFile = File(tempDirectory, "Safe.smedit").apply { writeText("{}") }
        repository.initialize(projectFile.absolutePath, reference)

        assertThrows<IllegalArgumentException> {
            repository.saveSource(projectFile.absolutePath, "../escaped.asm", "nope")
        }
        assertFalse(File(tempDirectory, "Safe_smedit/asm/workspace/escaped.asm").exists())
    }

    @Test
    fun `failed initialization does not activate a partial workspace`() {
        val range = AsmAssetRange("Tiles_Missing.bin", 0x10, 4)
        val reference = fakeReference(emptyList())
        val repository = AsmProjectWorkspaceRepository(listOf(range))
        val projectFile = File(tempDirectory, "Broken.smedit").apply { writeText("{}") }

        assertThrows<IllegalArgumentException> {
            repository.initialize(projectFile.absolutePath, reference)
        }
        assertFalse(File(tempDirectory, "Broken_smedit/asm").exists())
        assertTrue(File(tempDirectory, "Broken_smedit").listFiles().orEmpty().none { it.name.startsWith(".asm.installing-") })
    }

    @Test
    fun `repair preserves an incomplete prior sidecar as a recoverable backup`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range))
        val projectFile = File(tempDirectory, "Repair.smedit").apply { writeText("{}") }
        val incomplete = File(tempDirectory, "Repair_smedit/asm").apply { mkdirs() }
        File(incomplete, "user-notes.txt").writeText("preserve me")

        val repaired = repository.initialize(projectFile.absolutePath, reference)

        assertTrue(File(repaired.workingDirectory, "src/main.asm").isFile)
        val backup = File(tempDirectory, "Repair_smedit").listFiles().orEmpty()
            .single { it.name.startsWith(".asm.incomplete-backup-") }
        assertEquals("preserve me", File(backup, "user-notes.txt").readText())
    }

    @Test
    fun `recreating a local tree reapplies trackable source overrides`() {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val repository = AsmProjectWorkspaceRepository(listOf(range))
        val projectFile = File(tempDirectory, "Portable.smedit").apply { writeText("{}") }
        val root = File(tempDirectory, "Portable_smedit/asm")
        File(root, "overrides/src").mkdirs()
        val overrideText = "org ${'$'}808000\nBoot:\n    NOP\n    RTS\n"
        File(root, "overrides/src/bank_80.asm").writeText(overrideText)

        val recreated = repository.initialize(projectFile.absolutePath, reference)

        assertEquals(overrideText, File(recreated.workingDirectory, "src/bank_80.asm").readText())
        assertEquals(setOf("bank_80.asm"), recreated.modifiedFileIds)
        assertEquals(overrideText, File(recreated.root, "overrides/src/bank_80.asm").readText())
    }

    @Test
    fun `workspace state tracks unsaved saved and restored project source`() = runBlocking {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val projectRepository = AsmProjectWorkspaceRepository(listOf(range))
        val projectFile = File(tempDirectory, "State.smedit").apply { writeText("{}") }
        projectRepository.initialize(projectFile.absolutePath, reference)
        val buildArtifacts = AsmBuildArtifactRepository(projectRepository)
        buildArtifacts.publishSuccess(
            projectFilePath = projectFile.absolutePath,
            symbols = ByteArray(0),
            output = listOf("Build succeeded"),
            assemblerVersion = "fixture",
        )
        val state = AsmWorkspaceState(
            repository = AsmReferenceRepository(
                referenceRoot = File(tempDirectory, "unused-global-cache"),
                fetchBytes = { error("network should not be used") },
                assetRanges = listOf(range),
            ),
            projectRepository = projectRepository,
            buildArtifactRepository = buildArtifacts,
        )

        state.bindProject(projectFile.absolutePath, enabled = true)
        state.openSource("bank_80.asm", lineIndex = 3, columnIndex = 4)
        assertEquals(
            AsmWorkspaceKind.PROJECT,
            (state.locationSnapshot() as AsmWorkspaceLocation.Source).workspaceKind,
        )
        assertEquals(4, (state.locationSnapshot() as AsmWorkspaceLocation.Source).columnIndex)
        assertTrue(state.isProjectSourceEditable)
        assertFalse(state.sourceBuildRequired)
        val original = state.sourceEditText("bank_80.asm")
        state.updateSourceEditText("bank_80.asm", "$original\n; project edit")

        assertTrue(state.hasUnsavedSourceChanges("bank_80.asm"))
        assertTrue(state.sourceBuildRequired)
        assertTrue(state.hasProjectSourceChanges)
        assertEquals(setOf("bank_80.asm"), state.unsavedSourceFileIds)
        state.discardSourceBuffer("bank_80.asm")
        assertFalse(state.sourceBuildRequired, "reverting an unsaved edit retains the matching successful build")
        state.updateSourceEditText("bank_80.asm", "$original\n; project edit")
        assertTrue(state.saveSource("bank_80.asm"))
        assertFalse(state.hasUnsavedSourceChanges("bank_80.asm"))
        assertTrue(state.sourceBuildRequired, "newly saved source requires an explicit build")
        assertEquals(setOf("bank_80.asm"), state.projectModifiedFileIds)

        assertTrue(state.restoreOriginalSource("bank_80.asm"))
        assertEquals(original, state.sourceEditText("bank_80.asm"))
        assertEquals(emptySet<String>(), state.projectModifiedFileIds)
        assertFalse(state.hasProjectSourceChanges)
    }

    @Test
    fun `reviewed project replacement stages buffers without writing source`() = runBlocking {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val reference = fakeReference(listOf(range))
        val projectRepository = AsmProjectWorkspaceRepository(listOf(range))
        val projectFile = File(tempDirectory, "Replace.smedit").apply { writeText("{}") }
        val project = projectRepository.initialize(projectFile.absolutePath, reference)
        val state = AsmWorkspaceState(
            repository = AsmReferenceRepository(
                referenceRoot = File(tempDirectory, "unused-global-cache"),
                fetchBytes = { error("network should not be used") },
                assetRanges = listOf(range),
            ),
            projectRepository = projectRepository,
        )
        state.bindProject(projectFile.absolutePath, enabled = true)
        val diskSource = File(project.workingDirectory, "src/bank_80.asm")
        val original = diskSource.readText()
        val preview = state.previewProjectSourceReplacement("RTS", "NOP", caseSensitive = true)

        assertEquals(1, preview.occurrenceCount)
        assertEquals(1, state.stageProjectSourceReplacement(preview))
        assertEquals(original, diskSource.readText(), "staging must not write the working tree")
        assertEquals(setOf("bank_80.asm"), state.unsavedSourceFileIds)
        assertTrue(state.sourceEditText("bank_80.asm").contains("NOP"))
        assertEquals(emptySet<String>(), state.projectModifiedFileIds)
    }

    private fun fakeReference(ranges: List<AsmAssetRange>): AsmReferenceWorkspace {
        val root = File(tempDirectory, "reference-${ranges.size}").apply { mkdirs() }
        val source = File(root, "src").apply { mkdirs() }
        File(source, "main.asm").writeText("incsrc bank_80.asm ; Engine\n")
        File(source, "bank_80.asm").writeText(
            """
            ; Engine
            org ${'$'}808000
            Boot:
                RTS
            """.trimIndent(),
        )
        val data = File(root, "data").apply { mkdirs() }
        ranges.forEach { range ->
            File(data, range.path).apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(range.length) { it.toByte() })
            }
        }
        val metadata = AsmReferenceMetadata(
            repositoryUrl = AsmReferenceContract.REPOSITORY_URL,
            commit = AsmReferenceContract.COMMIT,
            installedAt = "2026-10-08T00:00:00Z",
            romName = "fixture.sfc",
            romSha256 = "ab".repeat(32),
            romSize = 0x300000,
            assetCount = ranges.size,
        )
        return AsmReferenceWorkspace(
            root = root,
            metadata = metadata,
            index = AsmSourceParser().parse(source, data, ranges),
        )
    }
}

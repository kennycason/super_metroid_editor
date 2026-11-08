package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.asm.*
import com.supermetroid.editor.rom.RomAllocation
import com.supermetroid.editor.rom.RomFreeSpaceBank
import com.supermetroid.editor.rom.RomFreeSpaceSnapshot
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlan
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalComposeUiApi::class)
class AsmBaselineDialogsTest {
    @TempDir lateinit var directory: File
    private val entry = AsmBaselineBackup("Test.smedit", "00000000-0000-0000-0000-000000000001", 1_700_000_000_000L, "test-v1", 4096, null, "fixture")
    private fun withDialog(content: @Composable () -> Unit, test: (ComposeScene, () -> Unit) -> Unit) {
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(1100, 1000)
        try {
            scene.constraints = Constraints(maxWidth = 1100, maxHeight = 1000)
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides EditorThemeState()) { MaterialTheme { content() } }
            }
            var time = System.nanoTime()
            val render = { repeat(10) { time += 16_666_667; scene.render(surface.canvas, time) }; Unit }
            render(); test(scene, render)
        } finally { scene.close(); surface.close() }
    }
    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    private fun tag(scene: ComposeScene, tag: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun texts(scene: ComposeScene) = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("\n") { it.text }
    private fun click(scene: ComposeScene, value: String) { assertTrue(tag(scene, value).config[SemanticsActions.OnClick].action!!.invoke()) }

    @Test fun `backup deletion needs a separate confirmation and cancellation never calls delete`() {
        val listing = AsmBaselineBackupList(listOf(entry))
        var deleted = 0
        var opened = 0
        withDialog({ AsmBaselineBackupsDialog(listing, false, null, null, {}, {}, { opened++ }, { deleted++ }) }) { scene, render ->
            assertEquals(0, deleted)
            assertContains(texts(scene), "not full-project restore points")
            assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Restore" } })
            click(scene, "asm-backup-open-${entry.id}")
            assertEquals(1, opened)
            click(scene, "asm-backup-delete-${entry.id}"); render()
            assertEquals(0, deleted)
            assertContains(texts(scene), "This cannot be undone")
            click(scene, "asm-backup-delete-cancel"); render()
            assertEquals(0, deleted)
            click(scene, "asm-backup-delete-${entry.id}"); render()
            click(scene, "asm-backup-delete-confirm"); render()
            assertEquals(1, deleted)
        }
    }

    @Test fun `protected copies cannot be deleted and empty lists explain when backups appear`() {
        withDialog({ AsmBaselineBackupsDialog(AsmBaselineBackupList(listOf(entry.copy(problem = "Pending recovery"))),
            false, null, null, {}, {}, {}, { fail("Protected backup") }) }) { scene, _ ->
            assertTrue(tag(scene, "asm-backup-delete-${entry.id}").config.contains(SemanticsProperties.Disabled))
            assertContains(texts(scene), "Pending recovery")
        }
        withDialog({ AsmBaselineBackupsDialog(AsmBaselineBackupList(emptyList()), false, null, null, {}, {}, {}, {}) }) { scene, _ ->
            assertNotNull(tag(scene, "asm-backups-empty"))
        }
    }

    private fun plan(conflict: Boolean): AsmBaselineUpdatePlan {
        val metadata = AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT,
            "fixture", "test.sfc", "ab".repeat(32), 0x300000, 0)
        val candidate = AsmSourceBaseline(reference = metadata, augmentationRevision = "v2",
            upstreamSourceHashes = emptyMap(), sourceHashes = emptyMap(), assets = emptyList())
        val base = if (conflict) "old\n" else "Root:\n dw \$0001\n RTL\n"
        val project = if (conflict) "mine\n" else "Root:\n dw \$0009\n RTL\n"
        val updated = if (conflict) "new\n" else "; documented\n$base"
        val merge = AsmThreeWayMerge.merge(base, project, updated)
        return AsmBaselineUpdatePlan("Test.smedit", "v1", "v2", listOf(AsmBaselineFileUpdate("bank_A3.asm", merge, true,
            baselineChanged = true, projectEdited = true, documentationOnly = true,
            baseText = base, projectText = project, updatedText = updated)), directory, emptyMap(), candidate)
    }

    @Test fun `clean updates expose selectable project version and result diffs before apply`() {
        val plan = plan(conflict = false)
        var applied: Map<String, Map<Int, String>>? = null
        withDialog({ AsmBaselineUpdateDialog(plan, false, null, null, {}, { applied = it }) }) { scene, render ->
            assertContains(texts(scene), "comments/formatting only")
            assertContains(texts(scene), "bank_A3.asm")
            assertContains(texts(scene), "\$0009")
            assertContains(texts(scene), "+1 / -1")
            assertContains(texts(scene), "Old")
            assertContains(texts(scene), "New")
            click(scene, "asm-baseline-view-version"); render()
            assertContains(texts(scene), "; documented")
            assertContains(texts(scene), "+1 / -0")
            click(scene, "asm-baseline-view-result"); render()
            assertContains(texts(scene), "update will change")
            click(scene, "asm-baseline-apply")
            assertEquals(emptyMap(), applied)
        }
    }

    @Test fun `conflicts require explicit choices before validate and apply becomes available`() {
        val plan = plan(conflict = true)
        var applied: Map<String, Map<Int, String>>? = null
        withDialog({ AsmBaselineUpdateDialog(plan, false, null, null, {}, { applied = it }) }) { scene, render ->
            assertTrue(tag(scene, "asm-baseline-apply").config.contains(SemanticsProperties.Disabled))
            click(scene, "asm-baseline-keep-bank_A3.asm-0"); render()
            assertFalse(tag(scene, "asm-baseline-apply").config.contains(SemanticsProperties.Disabled))
            assertContains(texts(scene), "Ready to validate")
            click(scene, "asm-baseline-apply")
            assertEquals(mapOf("bank_A3.asm" to mapOf(0 to "mine\n")), applied)
        }
    }

    @Test fun `changed file selection opens each files own diff`() {
        val first = plan(conflict = false)
        val base = "Stable:\n RTL\n"
        val updated = "Updated:\n RTL\n"
        val second = AsmBaselineFileUpdate(
            "bank_B0.asm", AsmThreeWayMerge.merge(base, base, updated), changed = true,
            baselineChanged = true, baseText = base, projectText = base, updatedText = updated,
        )
        val plan = AsmBaselineUpdatePlan(first.projectFilePath, first.previousRevision, first.nextRevision,
            first.files + second, directory, emptyMap(), first.candidate)

        withDialog({ AsmBaselineUpdateDialog(plan, false, null, null, {}, {}) }) { scene, render ->
            assertContains(texts(scene), "\$0009")
            click(scene, "asm-baseline-file-bank_B0.asm"); render()
            assertContains(texts(scene), "Updated:")
            assertContains(texts(scene), "What v2 changed from v1")
            assertContains(texts(scene), "+1 / -1")
        }
    }

    @Test fun `diff review pages through one separated change at a time`() {
        val fixture = plan(conflict = false)
        val baseLines = MutableList(20) { "NativeLine$it\n" }
        val projectLines = baseLines.toMutableList().apply {
            this[1] = "FirstProjectChange\n"
            this[18] = "SecondProjectChange\n"
        }
        val base = baseLines.joinToString("")
        val project = projectLines.joinToString("")
        val file = AsmBaselineFileUpdate(
            "bank_90.asm", AsmThreeWayMerge.merge(base, project, base), changed = true,
            projectEdited = true, baseText = base, projectText = project, updatedText = base,
        )
        val plan = AsmBaselineUpdatePlan(fixture.projectFilePath, fixture.previousRevision, fixture.nextRevision,
            listOf(file), directory, emptyMap(), fixture.candidate)

        withDialog({ AsmBaselineUpdateDialog(plan, false, null, null, {}, {}) }) { scene, render ->
            assertContains(texts(scene), "Change 1 of 2")
            assertContains(texts(scene), "FirstProjectChange")
            assertFalse(texts(scene).contains("SecondProjectChange"))
            assertTrue(tag(scene, "asm-baseline-previous-change").config.contains(SemanticsProperties.Disabled))
            click(scene, "asm-baseline-next-change"); render()
            assertContains(texts(scene), "Change 2 of 2")
            assertContains(texts(scene), "SecondProjectChange")
            assertFalse(texts(scene).contains("FirstProjectChange"))
        }
    }

    @Test fun `whole-file conflicts use bounded previews and complete version choices`() {
        val huge = buildString { repeat(45_000) { append("    LDA.W #\$1234 ; guarded conflict\n") } }
        val metadata = AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT,
            "fixture", "test.sfc", "ab".repeat(32), 0x300000, 0)
        val candidate = AsmSourceBaseline(reference = metadata, augmentationRevision = "v2",
            upstreamSourceHashes = emptyMap(), sourceHashes = emptyMap(), assets = emptyList())
        val conflict = AsmMergeConflict(huge, "$huge; project tail\n", "$huge; updated tail\n")
        val merge = AsmTextMerge(listOf(AsmMergePiece(conflict = conflict)))
        val plan = AsmBaselineUpdatePlan("Test.smedit", "v1", "v2",
            listOf(AsmBaselineFileUpdate("bank_84.asm", merge, true, true, true,
                baseText = huge, projectText = conflict.project, updatedText = conflict.updated)), directory, emptyMap(), candidate)
        var applied: Map<String, Map<Int, String>>? = null

        withDialog({ AsmBaselineUpdateDialog(plan, false, null, null, {}, { applied = it }) }) { scene, render ->
            assertContains(texts(scene), "no stable line anchors")
            assertContains(texts(scene), "more characters in this version")
            click(scene, "asm-baseline-keep-bank_84.asm-0"); render()
            click(scene, "asm-baseline-apply")
            assertEquals(conflict.project, applied?.get("bank_84.asm")?.get(0))
        }
    }

    @Test fun `project changes distinguish saved unsaved and disabled module source`() {
        val bank = AsmProjectSourceChange(
            fileId = "bank_8F.asm",
            baselineText = "org \$8F8000\nValue: dw \$0001\n",
            savedText = "org \$8F8000\nValue: dw \$0002\n",
            workingText = "org \$8F8000\nValue: dw \$0003\n",
            projectModule = false,
            enabled = true,
        )
        val module = AsmProjectSourceChange(
            fileId = "project/custom.asm",
            baselineText = "",
            savedText = "; Project module: custom\nCustom: RTS\n",
            workingText = "; Project module: custom\nCustom: RTS\n",
            projectModule = true,
            enabled = false,
        )
        val asset = AsmProjectAssetChange("Palettes_0.bin", 225, 17, listOf(1, 2, 9))
        var opened: String? = null

        withDialog({ AsmProjectChangesDialog(listOf(bank, module), {}, { opened = it }, listOf(asset)) }) { scene, render ->
            assertContains(texts(scene), "reciprocal Visual Editor changes")
            assertContains(texts(scene), "Saved bank override")
            assertContains(texts(scene), "Unsaved buffer")
            assertContains(texts(scene), "1 data asset")
            assertContains(texts(scene), "\$0003")
            click(scene, "asm-project-change-view-baseline_to_saved"); render()
            assertContains(texts(scene), "\$0002")
            click(scene, "asm-project-change-file-project/custom.asm"); render()
            assertContains(texts(scene), "Disabled module")
            assertContains(texts(scene), "whole-file addition")
            assertTrue(tag(scene, "asm-project-change-view-saved_to_unsaved").config.contains(SemanticsProperties.Disabled))
            click(scene, "asm-project-change-asset-Palettes_0.bin"); render()
            assertContains(texts(scene), "17 changed")
            assertContains(texts(scene), "+\$0001")
            assertTrue(tag(scene, "asm-project-changes-open-source").config.contains(SemanticsProperties.Disabled))
            click(scene, "asm-project-change-file-project/custom.asm"); render()
            click(scene, "asm-project-changes-open-source")
            assertEquals("project/custom.asm", opened)
        }
    }

    @Test fun `ROM space planner renders exact bank pressure placements and ownership`() {
        val loaded = ByteArray(0x8000) { -1 }
        val writePlan = RomWritePlan(loaded)
        writePlan.add(
            owner = "room-graph:project",
            label = "Room 91F8 level data",
            offset = 0x7000,
            bytes = List(0x100) { 0x5A },
            kind = RomWriteKind.ALLOCATION,
        )
        val before = RomFreeSpaceSnapshot(
            listOf(RomFreeSpaceBank(0x80, 0, 0x8000, 0x7000, 0x1000, 0xFF)),
            emptyList(),
        )
        val after = RomFreeSpaceSnapshot(
            listOf(RomFreeSpaceBank(0x80, 0, 0x8000, 0x7100, 0x0F00, 0xFF)),
            listOf(RomAllocation(0x80, 0x7000, 0x80F000, 0x100, "Room 91F8 level data")),
        )
        val preview = AsmRomPreview.create(
            loaded,
            writePlan.finalRom(),
            0,
            writePlan.report(),
            romSpaceBefore = before,
            romSpaceAfter = after,
        )

        withDialog({ AsmRomSpacePlannerCanvas(AsmWorkspaceState(), preview.spacePlan, true, Modifier.fillMaxSize()) }) { scene, _ ->
            assertContains(texts(scene), "Plan fits")
            assertContains(texts(scene), "Room 91F8 level data")
            assertContains(texts(scene), "room-graph:project")
            assertContains(texts(scene), "4.0 KiB → 3.8 KiB")
            assertNotNull(tag(scene, "asm-space-bank-80"))
        }
    }

    @Test fun `backup sizes are clear and do not turn an unavailable size into zero`() {
        assertEquals("Size unavailable", formatAsmBackupSize(null))
        assertEquals("0 B", formatAsmBackupSize(0))
        assertEquals("1.0 KiB", formatAsmBackupSize(1024))
        assertEquals("2.5 MiB", formatAsmBackupSize(2_621_440))
    }

    @Test fun `slow discovery can be dismissed but deletion cannot be interrupted from the dialog`() {
        var closed = 0
        withDialog({ AsmBaselineBackupsDialog(null, true, null, null, { closed++ }, {}, {}, {}) }) { scene, _ ->
            assertFalse(tag(scene, "asm-backups-close").config.contains(SemanticsProperties.Disabled))
            click(scene, "asm-backups-close")
            assertEquals(1, closed)
        }
        withDialog({ AsmBaselineBackupsDialog(null, true, null, null, { closed++ }, {}, {}, {}, deleting = true) }) { scene, _ ->
            assertTrue(tag(scene, "asm-backups-close").config.contains(SemanticsProperties.Disabled))
        }
    }
}

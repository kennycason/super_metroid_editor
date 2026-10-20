package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.asm.*
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
        val merge = if (conflict) AsmTextMerge(listOf(AsmMergePiece(conflict = AsmMergeConflict("old", "mine", "new"))))
            else AsmTextMerge(listOf(AsmMergePiece("merged")))
        return AsmBaselineUpdatePlan("Test.smedit", "v1", "v2", listOf(AsmBaselineFileUpdate("bank_A3.asm", merge, true,
            baselineChanged = true, projectEdited = true, documentationOnly = true)), directory, emptyMap(), candidate)
    }

    @Test fun `clean updates keep file details collapsed and apply without an extra acceptance step`() {
        val plan = plan(conflict = false)
        var applied: Map<String, Map<Int, String>>? = null
        withDialog({ AsmBaselineUpdateDialog(plan, false, null, null, {}, { applied = it }) }) { scene, render ->
            assertContains(texts(scene), "comments/formatting only")
            assertContains(texts(scene), "No changes need review")
            assertFalse(texts(scene).contains("bank_A3.asm"))
            click(scene, "asm-baseline-details-toggle"); render()
            assertContains(texts(scene), "bank_A3.asm")
            click(scene, "asm-baseline-details-toggle"); render()
            assertFalse(texts(scene).contains("bank_A3.asm"))
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
            assertContains(texts(scene), "Review complete")
            click(scene, "asm-baseline-apply")
            assertEquals(mapOf("bank_A3.asm" to mapOf(0 to "mine")), applied)
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

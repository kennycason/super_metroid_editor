package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.asm.AsmEnemyTuningSection
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class EnemyTabNavigationTest {
    @Test fun `each sidebar category has a right aligned source action without editing the project`() {
        for (font in FontSize.entries) {
            val editor = editor(asm = true)
            val before = Json.encodeToString(SmEditProject.serializer(), editor.project)
            val opened = mutableListOf<AsmEnemyTuningSection>()
            withSidebar(editor, font, opened::add) { nodes ->
                val links = nodes.filter { text(it) == "ASM" }.sortedBy { it.boundsInRoot.top }
                assertEquals(3, links.size)
                val labels = listOf("Stats", "Drop Rates", "Vulnerabilities")
                links.zip(labels).forEach { (link, label) ->
                    val category = nodes.single { text(it) == label }
                    assertTrue(link.boundsInRoot.left >= category.boundsInRoot.right - 1f, "$font $label link must be to the right without overlap")
                    assertTrue(abs(link.boundsInRoot.right - links.first().boundsInRoot.right) < 1f)
                    val action = link.config[SemanticsActions.OnClick]
                    assertEquals("Open $label ASM", action.label)
                    assertTrue(action.action!!.invoke())
                }
            }
            assertEquals(AsmEnemyTuningSection.entries.toList(), opened)
            assertEquals(before, Json.encodeToString(SmEditProject.serializer(), editor.project))
            assertFalse(editor.dirty)
        }
    }

    @Test fun `source actions are hidden outside ASM source mode or without a route`() {
        val cases = listOf(editor(asm = false), editor(asm = true).apply {
            project.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM
        })
        cases.forEach { editor -> withSidebar(editor, onOpen = { fail("ROM mode must not navigate to project ASM") }) { nodes ->
            assertTrue(nodes.none { text(it) == "ASM" })
        } }
        withSidebar(editor(asm = true), onOpen = null) { nodes ->
            assertTrue(nodes.none { text(it) == "ASM" })
        }
    }

    private fun editor(asm: Boolean) = EditorState().apply {
        testMode = true
        project.asmWorkspace.apply {
            enabled = asm
            buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
    }

    private fun withSidebar(editor: EditorState, font: FontSize = FontSize.MEDIUM,
                            onOpen: ((AsmEnemyTuningSection) -> Unit)?, check: (List<SemanticsNode>) -> Unit) {
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(500, 400)
        try {
            scene.constraints = Constraints.fixed(500, 400)
            val settings = EditorThemeState().apply { fontSize.value = font }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(typography = font.materialTypography()) {
                        EnemyTabSidebar(editor, onNavigateToAsm = onOpen)
                    }
                }
            }
            var time = System.nanoTime()
            repeat(8) { time += 16_666_667; scene.render(surface.canvas, time) }
            check(scene.roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) })
        } finally { scene.close(); surface.close() }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
}

package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.ComposeScene
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.TestRomHelper
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalComposeUiApi::class)
class RoomRuntimeInspectorTest {
    @Test fun `snapshot inspection is read only and covers every runtime resource`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val room = requireNotNull(parser.readRoomHeader(0x91F8))
        val editor = EditorState().apply { loadRoom(room.roomId, parser, room) }
        val asm = AsmWorkspaceState()
        val projectBefore = Json.encodeToString(SmEditProject.serializer(), editor.project)
        val historyBefore = editor.undoStack.toList()

        val snapshot = buildRoomRuntimeSnapshot(room, parser, editor, asm)

        assertEquals(RoomRuntimeResourceKind.entries.toSet(), snapshot.resources.map { it.kind }.toSet())
        assertEquals(projectBefore, Json.encodeToString(SmEditProject.serializer(), editor.project))
        assertEquals(historyBefore, editor.undoStack)
        assertFalse(editor.dirty)
        assertTrue(snapshot.stateSummary.startsWith("State "))
    }

    @Test fun `ownership and health stay compact and explicit`() {
        assertEquals(RoomRuntimeOwnership.ASM, runtimeOwnership(asmOwned = true, visualOwned = true))
        assertEquals(RoomRuntimeOwnership.VISUAL, runtimeOwnership(asmOwned = false, visualOwned = true))
        assertEquals(RoomRuntimeOwnership.NATIVE, runtimeOwnership(asmOwned = false, visualOwned = false))
        assertEquals(RoomRuntimeHealth.CURRENT, runtimeHealth(null))
        assertEquals(RoomRuntimeHealth.LOADING, runtimeHealth("Wait for source indexing"))
        assertEquals(RoomRuntimeHealth.STALE, runtimeHealth("Showing last valid preview"))
        assertEquals(RoomRuntimeHealth.STALE, runtimeHealth(null, explicitlyStale = true))
        assertEquals(RoomRuntimeHealth.ERROR, runtimeHealth("Invalid selector argument"))
        assertEquals(RoomRuntimeHealth.LOADING, runtimeBuildHealth("ASM loading"))
        assertEquals(RoomRuntimeHealth.STALE, runtimeBuildHealth("Build needed"))
        assertEquals(RoomRuntimeHealth.ERROR, runtimeBuildHealth("Build failed"))
        assertEquals(RoomRuntimeHealth.CURRENT, runtimeBuildHealth("Built"))
    }

    @Test fun `unified room overview renders ownership and problems in a narrow sidebar`() {
        val snapshot = RoomRuntimeSnapshot(
            roomId = 0x91F8,
            roomName = "Landing Site",
            stateSummary = "State 1/4 · Event 0 set",
            buildSummary = "Build needed",
            resources = RoomRuntimeResourceKind.entries.map { kind ->
                RoomRuntimeResource(
                    kind = kind,
                    title = kind.name.lowercase().replaceFirstChar(Char::uppercase),
                    ownership = if (kind == RoomRuntimeResourceKind.SELECTORS) RoomRuntimeOwnership.ASM else RoomRuntimeOwnership.NATIVE,
                    summary = if (kind == RoomRuntimeResourceKind.SELECTORS) "4 branches" else "Current",
                    scope = "Selected state",
                    health = if (kind == RoomRuntimeResourceKind.SELECTORS) RoomRuntimeHealth.ERROR else RoomRuntimeHealth.CURRENT,
                    problems = if (kind == RoomRuntimeResourceKind.SELECTORS) {
                        listOf(RoomRuntimeProblem("Invalid selector argument", kind))
                    } else emptyList(),
                )
            },
        )
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(330, 720)
        try {
            scene.constraints = Constraints.fixed(330, 720)
            scene.setContent {
                MaterialTheme {
                    RoomRuntimeOverview(snapshot, RoomRuntimeNavigation())
                }
            }
            render(scene, surface)
            assertTrue(texts(scene).any { it.contains("Landing Site") })
            assertTrue(texts(scene).any { it == "Needs attention · 1" })
            assertTrue(texts(scene).any { it.contains("Selectors · ASM") })
            assertTrue(texts(scene).any { it.contains("Saves · Native") })
        } finally {
            scene.close()
            surface.close()
        }
    }

    private fun render(scene: ComposeScene, surface: Surface) {
        var time = System.nanoTime()
        repeat(8) { time += 16_666_667; scene.render(surface.canvas, time) }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    private fun nodes(scene: ComposeScene): List<SemanticsNode> =
        scene.roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun texts(scene: ComposeScene): List<String> = nodes(scene).flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

}

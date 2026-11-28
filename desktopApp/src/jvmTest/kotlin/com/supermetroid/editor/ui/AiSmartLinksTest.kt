package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.ai.AiConversationStore
import com.supermetroid.editor.ai.AiCredentialStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.io.TempDir

class AiSmartLinksTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `only real room ids and explicit banked addresses become smart links`() {
        val text = "Rooms 0x91F8, \$A0D2, and ADAD; unknown 0xBEEF; source \$95:80D8; header \$8F:91F8; color 0xFF00FF."
        val links = aiSmartLinkSpans(text, setOf(0x91F8, 0xA0D2, 0xADAD), asmLinksEnabled = true)

        assertEquals(listOf("0x91F8", "\$A0D2", "ADAD", "\$95:80D8", "\$8F:91F8"), links.map {
            text.substring(it.start, it.endExclusive)
        })
        assertEquals(
            listOf(
                AiSmartLinkTarget.Room(0x91F8),
                AiSmartLinkTarget.Room(0xA0D2),
                AiSmartLinkTarget.Room(0xADAD),
                AiSmartLinkTarget.AsmAddress(0x9580D8),
                AiSmartLinkTarget.Room(0x91F8),
            ),
            links.map(AiSmartLinkSpan::target),
        )
        assertEquals(4, aiSmartLinkSpans(text, setOf(0x91F8, 0xA0D2, 0xADAD), asmLinksEnabled = false).size)

        val annotated = aiSmartAnnotatedString(text, links, Color.Blue)
        assertEquals(text, annotated.text)
        assertEquals(5, annotated.getStringAnnotations(0, annotated.length).size)
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `clicking an assistant room id invokes room navigation while text stays selectable`() = runBlocking {
        val state = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
        )
        state.addChatMessage(AiChatRole.ASSISTANT, "0x91F8")
        var opened: AiSmartLinkTarget? = null
        val scene = ComposeScene(coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(700, 420)
        try {
            scene.constraints = Constraints.fixed(700, 420)
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides EditorThemeState()) {
                    MaterialTheme {
                        AiWorkspaceCanvas(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            smartLinkRoomIds = setOf(0x91F8),
                            onSmartLink = { opened = it },
                        )
                    }
                }
            }
            var frameTime = System.nanoTime()
            suspend fun render() {
                repeat(16) {
                    Snapshot.sendApplyNotifications()
                    yield()
                    frameTime += 16_666_667
                    scene.render(surface.canvas, frameTime)
                }
            }
            render()
            val link = semanticsNodes(scene).filter { semanticsText(it) == "0x91F8" }
                .maxByOrNull { it.boundsInRoot.height }
                ?: error("Smart room text was not rendered")
            val click = Offset(link.boundsInRoot.center.x, link.boundsInRoot.center.y)
            scene.sendPointerEvent(PointerEventType.Press, click, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, click, button = PointerButton.Primary)
            render()
            assertEquals(AiSmartLinkTarget.Room(0x91F8), opened)
        } finally {
            scene.close()
            surface.close()
        }
    }

    private fun semanticsNodes(scene: ComposeScene): List<SemanticsNode> =
        scene.roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    private fun semanticsText(node: SemanticsNode): String =
        if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].joinToString("") { it.text }
        } else ""
}

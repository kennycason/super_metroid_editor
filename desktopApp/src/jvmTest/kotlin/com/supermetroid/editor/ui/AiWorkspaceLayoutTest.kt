package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.ai.AiConversationStore
import com.supermetroid.editor.ai.AiCredentialStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.io.TempDir

class AiWorkspaceLayoutTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `composer keeps useful height grows for multiline text and conversation stays at bottom`() {
        val state = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
        )
        repeat(18) { index ->
            state.addChatMessage(
                if (index % 2 == 0) AiChatRole.USER else AiChatRole.ASSISTANT,
                "Conversation history line $index with enough text to occupy the viewport.",
            )
        }
        state.addChatMessage(AiChatRole.ASSISTANT, "Newest marker")
        val listState = LazyListState()

        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(900, 600)
        try {
            scene.constraints = Constraints.fixed(900, 600)
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides EditorThemeState()) {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            AiWorkspaceCanvas(state, Modifier.fillMaxSize(), listState)
                        }
                    }
                }
            }
            var frameTime = System.nanoTime()
            fun render() {
                repeat(16) {
                    frameTime += 16_666_667
                    scene.render(surface.canvas, frameTime)
                }
            }

            render()
            val initialComposerHeight = tagged(scene, "ai-prompt").boundsInRoot.height
            assertTrue(
                initialComposerHeight >= 96f,
                "Composer collapsed to $initialComposerHeight px; tags=" + nodes(scene).filter {
                    it.config.contains(SemanticsProperties.TestTag)
                }.joinToString { "${it.config[SemanticsProperties.TestTag]}=${it.boundsInRoot}" },
            )
            assertConversationAtBottom(scene, listState)
            assertNewestMessageVisible(scene)

            val prompt = tagged(scene, "ai-prompt")
            val multiline = (1..8).joinToString("\n") { "A longer prompt line $it" }
            assertTrue(prompt.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(multiline)))
            render()

            val grownComposerHeight = tagged(scene, "ai-prompt").boundsInRoot.height
            assertTrue(grownComposerHeight > initialComposerHeight, "Composer did not grow for multiline input")
            assertTrue(grownComposerHeight <= 240f, "Composer exceeded its bounded maximum")
            assertConversationAtBottom(scene, listState)
            assertNewestMessageVisible(scene)
        } finally {
            scene.close()
            surface.close()
        }
    }

    private fun assertConversationAtBottom(scene: ComposeScene, state: LazyListState) {
        val list = tagged(scene, "ai-message-list")
        val range = list.config[SemanticsProperties.VerticalScrollAxisRange]
        val remaining = range.maxValue() - range.value()
        val newest = nodes(scene).filter { text(it) == "Newest marker" }.maxByOrNull { it.boundsInRoot.height }
        assertTrue(
            remaining < 1f,
            "Conversation is not at its newest message: value=${range.value()}, max=${range.maxValue()}, " +
                "remaining=$remaining, list=${list.boundsInRoot}, conversation=${tagged(scene, "ai-conversation").boundsInRoot}, " +
                "prompt=${tagged(scene, "ai-prompt").boundsInRoot}, newest=${newest?.boundsInRoot}, " +
                "canScrollForward=${state.canScrollForward}, viewport=${state.layoutInfo.viewportSize}, " +
                "visible=${state.layoutInfo.visibleItemsInfo.joinToString { "${it.index}@${it.offset}+${it.size}" }}",
        )
    }

    private fun assertNewestMessageVisible(scene: ComposeScene) {
        val newest = nodes(scene).filter { text(it) == "Newest marker" }
            .maxByOrNull { it.boundsInRoot.height }?.boundsInRoot
            ?: error("Newest message semantics were not composed")
        val conversation = tagged(scene, "ai-conversation").boundsInRoot
        assertTrue(
            newest.height > 0f && newest.top >= conversation.top && newest.bottom <= conversation.bottom,
            "Newest message is outside the conversation viewport: newest=$newest, conversation=$conversation",
        )
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    private fun nodes(scene: ComposeScene): List<SemanticsNode> =
        scene.roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun tagged(scene: ComposeScene, tag: String): SemanticsNode =
        nodes(scene).single {
            it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == tag
        }

    private fun text(node: SemanticsNode): String =
        if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].joinToString("") { it.text }
        } else ""
}

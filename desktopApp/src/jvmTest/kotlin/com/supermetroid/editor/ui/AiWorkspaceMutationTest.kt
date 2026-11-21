package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.ai.AiCredentialStore
import com.supermetroid.editor.ai.AiConversationStore
import com.supermetroid.editor.ai.AiToolCall
import com.supermetroid.editor.api.SmeditAsmSearchMatch
import com.supermetroid.editor.api.SmeditEditorSettingsSummary
import com.supermetroid.editor.api.SmeditMutationController
import com.supermetroid.editor.api.SmeditMutationPlan
import com.supermetroid.editor.api.SmeditMutationPlanKind
import com.supermetroid.editor.api.SmeditMutationPlanningResult
import com.supermetroid.editor.api.SmeditMutationTransition
import com.supermetroid.editor.api.SmeditPatchMutationPlan
import com.supermetroid.editor.api.SmeditPatchSummary
import com.supermetroid.editor.api.SmeditPatchDetails
import com.supermetroid.editor.api.SmeditPatchValueChange
import com.supermetroid.editor.api.SmeditRoomDetails
import com.supermetroid.editor.api.SmeditRoomSummary
import com.supermetroid.editor.api.SmeditToolRegistry
import com.supermetroid.editor.api.SmeditToolResult
import com.supermetroid.editor.api.SmeditWorkspaceAccess
import com.supermetroid.editor.api.SmeditWorkspaceSummary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import org.jetbrains.skia.Surface

class AiWorkspaceMutationTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `mutation tool call returns approval required and waits for explicit approval`() {
        val registry = SmeditToolRegistry(EmptyWorkspace())
        val mutations = FakeMutationController()
        val state = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
            toolRegistry = registry,
            mutationController = mutations,
        )
        state.addChatMessage(AiChatRole.USER, "Set the music.")
        val plansCreatedAfterSequence = state.conversationItems.last().sequence

        val result = state.executeToolCall(
            registry,
            AiToolCall(
                callId = "call-music",
                name = "set_room_music",
                arguments = buildJsonObject {
                    put("music_data", 0x18)
                    put("music_track", 5)
                },
                rawArguments = "{\"music_data\":24,\"music_track\":5}",
            ),
        )

        assertFalse(result.isError)
        assertEquals("approval_required", result.output.getValue("status").toString().trim('"'))
        assertEquals("plan-1", state.pendingMutation?.id)
        assertEquals(AiToolActivityStatus.AWAITING_APPROVAL, state.toolActivity.single().status)
        assertEquals(0, mutations.approvals)
        state.commitAssistantReply("The plan is ready for approval.", plansCreatedAfterSequence)
        assertEquals(
            listOf(AiChatMessage::class, AiChatMessage::class, AiMutationPlanMessage::class),
            state.conversationItems.map { it::class },
        )

        mutations.beforeApprove = {
            assertEquals(AiMutationAction.APPLYING, state.mutationActionInFlight)
        }
        runBlocking { state.approvePendingMutation() }

        assertEquals(1, mutations.approvals)
        assertNull(state.pendingMutation)
        assertNull(state.mutationActionInFlight)
        assertEquals(AiToolActivityStatus.APPLIED, state.toolActivity.single().status)
        assertTrue(state.canUndoAiMutation)
        assertEquals(4, state.conversationItems.size)
        assertTrue(state.conversationItems[2] is AiMutationPlanMessage)
        assertEquals(AiToolActivityStatus.APPLIED, (state.conversationItems[2] as AiMutationPlanMessage).status)
        assertTrue(state.conversationItems[3] is AiMutationNotice)
        assertEquals("Applied room music.", (state.conversationItems[3] as AiMutationNotice).text)

        val restored = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
            toolRegistry = registry,
            mutationController = FakeMutationController(),
        )
        assertEquals(4, restored.conversationItems.size)
        assertEquals("Set the music.", (restored.conversationItems[0] as AiChatMessage).text)
        assertEquals("The plan is ready for approval.", (restored.conversationItems[1] as AiChatMessage).text)
        assertEquals(AiToolActivityStatus.APPLIED, (restored.conversationItems[2] as AiMutationPlanMessage).status)
        assertEquals("Applied room music.", (restored.conversationItems[3] as AiMutationNotice).text)
        assertNull(restored.pendingMutation)
        assertFalse(restored.canUndoAiMutation, "In-memory optimistic undo snapshots must not be revived after restart")
    }

    @Test
    fun `unapproved plans expire safely when conversation history is restored`() {
        val registry = SmeditToolRegistry(EmptyWorkspace())
        val state = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
            toolRegistry = registry,
            mutationController = FakeMutationController(),
        )
        state.executeToolCall(
            registry,
            AiToolCall(
                callId = "call-music",
                name = "set_room_music",
                arguments = buildJsonObject {
                    put("music_data", 0x18)
                    put("music_track", 5)
                },
                rawArguments = "{}",
            ),
        )

        val restored = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
            toolRegistry = registry,
            mutationController = FakeMutationController(),
        )

        assertNull(restored.pendingMutation)
        assertEquals(AiToolActivityStatus.REJECTED, (restored.conversationItems[0] as AiMutationPlanMessage).status)
        val notice = restored.conversationItems[1] as AiMutationNotice
        assertTrue(notice.text.contains("expired"))
    }

    @Test
    fun `reject exposes progress and records its completion`() {
        val registry = SmeditToolRegistry(EmptyWorkspace())
        val mutations = FakeMutationController()
        val state = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
            toolRegistry = registry,
            mutationController = mutations,
        )
        state.executeToolCall(
            registry,
            AiToolCall(
                callId = "call-to-reject",
                name = "set_room_music",
                arguments = buildJsonObject {
                    put("music_data", 0x18)
                    put("music_track", 5)
                },
                rawArguments = "{}",
            ),
        )
        mutations.beforeReject = {
            assertEquals(AiMutationAction.REJECTING, state.mutationActionInFlight)
        }

        runBlocking { state.rejectPendingMutation() }

        assertNull(state.pendingMutation)
        assertNull(state.mutationActionInFlight)
        assertEquals(AiToolActivityStatus.REJECTED, (state.conversationItems[0] as AiMutationPlanMessage).status)
        assertTrue((state.conversationItems[1] as AiMutationNotice).text.startsWith("Rejected"))
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `rendered approve button applies its pending plan`() {
        val registry = SmeditToolRegistry(EmptyWorkspace())
        val mutations = FakeMutationController()
        val state = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
            toolRegistry = registry,
            mutationController = mutations,
        )
        state.executeToolCall(
            registry,
            AiToolCall(
                callId = "rendered-approval",
                name = "set_room_music",
                arguments = buildJsonObject {
                    put("music_data", 0x18)
                    put("music_track", 5)
                },
                rawArguments = "{}",
            ),
        )
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
            val approve = semanticsNodes(scene).singleOrNull {
                it.config.contains(SemanticsActions.OnClick) && semanticsTextDeep(it) == "Approve"
            } ?: error(
                "Approve action not found; clickable nodes=" + semanticsNodes(scene)
                    .filter { it.config.contains(SemanticsActions.OnClick) }
                    .joinToString { "text='${semanticsTextDeep(it)}' bounds=${it.boundsInRoot}" },
            )
            val clickPosition = Offset(approve.boundsInRoot.center.x, approve.boundsInRoot.center.y)
            scene.sendPointerEvent(PointerEventType.Press, clickPosition, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, clickPosition, button = PointerButton.Primary)
            render()
            runBlocking { delay(50) }
            render()

            assertEquals(1, mutations.approvals)
            assertNull(state.pendingMutation)
            assertTrue(semanticsNodes(scene).any { semanticsText(it) == "Approved plan" })
            assertTrue(semanticsNodes(scene).any { semanticsText(it) == "Applied room music." })
            assertFalse(listState.canScrollForward, "Completion notice must be visible at the conversation bottom")
        } finally {
            scene.close()
            surface.close()
        }
    }

    @Test
    fun `legacy conversation history moves approval plan below its assistant reply`() {
        val registry = SmeditToolRegistry(EmptyWorkspace())
        val state = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
            toolRegistry = registry,
            mutationController = FakeMutationController(),
        )
        state.addChatMessage(AiChatRole.USER, "Stage a music change.")
        state.executeToolCall(
            registry,
            AiToolCall(
                callId = "legacy-call",
                name = "set_room_music",
                arguments = buildJsonObject {
                    put("music_data", 0x18)
                    put("music_track", 5)
                },
                rawArguments = "{}",
            ),
        )
        state.addChatMessage(AiChatRole.ASSISTANT, "This plan needs your approval.")

        val restored = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
            toolRegistry = registry,
            mutationController = FakeMutationController(),
        )

        assertEquals(
            listOf(AiChatMessage::class, AiChatMessage::class, AiMutationPlanMessage::class, AiMutationNotice::class),
            restored.conversationItems.map { it::class },
        )
        assertEquals("This plan needs your approval.", (restored.conversationItems[1] as AiChatMessage).text)
    }

    @Test
    fun `applied patch plan details persist across restart`() {
        val registry = SmeditToolRegistry(EmptyWorkspace())
        val state = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
            toolRegistry = registry,
            mutationController = PatchMutationController(),
        )

        state.executeToolCall(
            registry,
            AiToolCall(
                callId = "call-patch",
                name = "configure_patch",
                arguments = buildJsonObject {
                    put("patch", "environmental_damage")
                    put("enabled", true)
                },
                rawArguments = "{}",
            ),
        )
        runBlocking { state.approvePendingMutation() }

        val restored = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
            toolRegistry = registry,
            mutationController = PatchMutationController(),
        )
        val message = restored.conversationItems.first() as AiMutationPlanMessage
        val patch = message.plan.patch

        assertEquals(SmeditMutationPlanKind.PATCH, message.plan.kind)
        assertEquals(AiToolActivityStatus.APPLIED, message.status)
        assertEquals("Environmental Damage", patch?.patchName)
        assertEquals(false, patch?.beforeEnabled)
        assertEquals(true, patch?.afterEnabled)
        assertEquals(
            listOf(
                SmeditPatchValueChange("heat_per_second", "Heated rooms", 0, 2),
                SmeditPatchValueChange("lava_per_second", "Lava", 0, 5),
                SmeditPatchValueChange("acid_per_second", "Acid", 0, 10),
            ),
            patch?.valueChanges,
        )
    }

    private class FakeMutationController : SmeditMutationController {
        var approvals = 0
        var beforeApprove: (() -> Unit)? = null
        var beforeReject: (() -> Unit)? = null
        override var canUndo = false
        override val canRedo = false

        override fun plan(toolName: String, arguments: JsonObject): SmeditMutationPlanningResult {
            val plan = SmeditMutationPlan(
                id = "plan-1",
                toolName = toolName,
                title = "Set room music to 0x18:0x05",
                musicData = 0x18,
                musicTrack = 5,
                affectedRoomCount = 2,
                affectedStateCount = 5,
                affectedRoomPreview = emptyList(),
                excludedRoomIds = emptyList(),
                excludedAreas = listOf("Ceres"),
                warnings = emptyList(),
            )
            return SmeditMutationPlanningResult(
                result = SmeditToolResult(
                    output = buildJsonObject {
                        put("ok", true)
                        put("status", "approval_required")
                    },
                    isError = false,
                ),
                plan = plan,
            )
        }

        override fun approve(planId: String): SmeditMutationTransition {
            beforeApprove?.invoke()
            approvals++
            canUndo = true
            return SmeditMutationTransition(planId, "Applied room music.")
        }

        override fun reject(planId: String) {
            beforeReject?.invoke()
        }
        override fun undo() = error("not used")
        override fun redo() = error("not used")
    }

    private class PatchMutationController : SmeditMutationController {
        override var canUndo = false
        override val canRedo = false

        override fun plan(toolName: String, arguments: JsonObject): SmeditMutationPlanningResult {
            val plan = SmeditMutationPlan(
                id = "patch-plan-1",
                toolName = toolName,
                title = "Configure Environmental Damage",
                musicData = 0,
                musicTrack = 0,
                affectedRoomCount = 0,
                affectedStateCount = 0,
                affectedRoomPreview = emptyList(),
                excludedRoomIds = emptyList(),
                excludedAreas = emptyList(),
                warnings = emptyList(),
                kind = SmeditMutationPlanKind.PATCH,
                patch = SmeditPatchMutationPlan(
                    patchId = "environmental_damage",
                    patchName = "Environmental Damage",
                    configType = "environmental_damage",
                    beforeEnabled = false,
                    afterEnabled = true,
                    valueChanges = listOf(
                        SmeditPatchValueChange("heat_per_second", "Heated rooms", 0, 2),
                        SmeditPatchValueChange("lava_per_second", "Lava", 0, 5),
                        SmeditPatchValueChange("acid_per_second", "Acid", 0, 10),
                    ),
                    automaticallyDisabledPatches = emptyList(),
                ),
            )
            return SmeditMutationPlanningResult(
                result = SmeditToolResult(
                    output = buildJsonObject {
                        put("ok", true)
                        put("status", "approval_required")
                    },
                    isError = false,
                ),
                plan = plan,
            )
        }

        override fun approve(planId: String): SmeditMutationTransition {
            canUndo = true
            return SmeditMutationTransition(planId, "Applied configure environmental damage.")
        }

        override fun reject(planId: String) = Unit
        override fun undo() = error("not used")
        override fun redo() = error("not used")
    }

    private class EmptyWorkspace : SmeditWorkspaceAccess {
        override fun workspaceSummary() = SmeditWorkspaceSummary(projectLoaded = true)
        override fun rooms() = emptyList<SmeditRoomSummary>()
        override fun room(roomId: Int): SmeditRoomDetails? = null
        override fun patches() = emptyList<SmeditPatchSummary>()
        override fun patch(identifier: String): SmeditPatchDetails? = null
        override fun editorSettings() = SmeditEditorSettingsSummary(
            theme = "DARK",
            fontSize = "MEDIUM",
            emulatorBackend = "libretro",
            emulatorFollowLiveRoom = true,
            roomSidebarTab = "TILESET",
            roomItemNamesVisible = true,
            roomMetaNamesVisible = true,
            roomEnemyNamesVisible = true,
        )
        override fun searchAsm(query: String, limit: Int) = emptyList<SmeditAsmSearchMatch>()
    }

    private fun semanticsDescendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::semanticsDescendants)

    private fun semanticsNodes(scene: ComposeScene): List<SemanticsNode> =
        scene.roots.flatMap { semanticsDescendants(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun semanticsText(node: SemanticsNode): String =
        if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].joinToString("") { it.text }
        } else ""

    private fun semanticsTextDeep(node: SemanticsNode): String =
        semanticsText(node) + node.children.joinToString("") { semanticsTextDeep(it) }
}

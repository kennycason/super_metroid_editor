package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
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
import com.supermetroid.editor.ai.AiChatReply
import com.supermetroid.editor.ai.AiModel
import com.supermetroid.editor.ai.AiProvider
import com.supermetroid.editor.ai.AiProviderId
import com.supermetroid.editor.ai.AiToolCall
import com.supermetroid.editor.ai.StoredAiConversation
import com.supermetroid.editor.ai.StoredAiConversationItem
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
import com.supermetroid.editor.api.SmeditRoomStateFieldChange
import com.supermetroid.editor.api.SmeditRoomStateFieldsMutationPlan
import com.supermetroid.editor.api.SmeditPaletteColorChange
import com.supermetroid.editor.api.SmeditPaletteMutationPlan
import com.supermetroid.editor.api.SmeditPaletteTargetMutationPlan
import com.supermetroid.editor.api.SmeditItemMutationPlan
import com.supermetroid.editor.api.SmeditRoomDetails
import com.supermetroid.editor.api.SmeditRoomSummary
import com.supermetroid.editor.api.SmeditToolRegistry
import com.supermetroid.editor.api.SmeditToolDefinition
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import org.jetbrains.skia.Surface

class AiWorkspaceMutationTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `historical conversations can be selected without losing the composer draft`() {
        val state = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
        )
        state.addChatMessage(AiChatRole.USER, "First historical prompt")
        val firstId = state.activeConversationId
        state.newConversation()
        state.addChatMessage(AiChatRole.USER, "Second historical prompt")
        val secondId = state.activeConversationId
        state.promptDraft = "Keep this draft while browsing"

        assertEquals(2, state.conversationHistory.size)
        assertEquals(secondId, state.activeConversationId)
        assertTrue(state.selectConversation(firstId))
        assertEquals(firstId, state.activeConversationId)
        assertEquals("First historical prompt", (state.conversationItems.single() as AiChatMessage).text)
        assertEquals("Keep this draft while browsing", state.promptDraft)
        assertTrue(state.selectConversation(secondId))
        assertEquals("Second historical prompt", (state.conversationItems.single() as AiChatMessage).text)
    }

    @Test
    fun `conversation custom names persist and replace generated tab titles`() {
        val conversations = AiConversationStore(temporary)
        val state = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = conversations,
            providerList = emptyList(),
        )
        state.addChatMessage(AiChatRole.USER, "A very long generated conversation title about Kraid palettes")
        val conversationId = state.activeConversationId

        assertTrue(state.renameConversation(conversationId, "Red Kraid"))
        assertEquals("Red Kraid", state.conversationHistory.single { it.id == conversationId }.title)
        assertEquals("Red Kraid", state.conversationHistory.single { it.id == conversationId }.customTitle)

        val restored = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = conversations,
            providerList = emptyList(),
        )
        assertEquals("Red Kraid", restored.conversationHistory.single { it.id == conversationId }.title)
        restored.showConversationHistory()
        assertTrue(restored.historyVisible)
        assertTrue(restored.selectConversation(conversationId))
        assertFalse(restored.historyVisible)

        assertTrue(restored.renameConversation(conversationId, ""))
        assertEquals(
            "A very long generated conversation title about Kraid palettes".take(64),
            restored.conversationHistory.single { it.id == conversationId }.title,
        )
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `rendered History tab opens renameable conversation list`() {
        val state = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
        )
        state.addChatMessage(AiChatRole.USER, "Generated palette conversation title")
        state.renameConversation(state.activeConversationId, "Red Kraid")
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(900, 700)
        try {
            scene.constraints = Constraints.fixed(900, 700)
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides EditorThemeState()) {
                    MaterialTheme { AiWorkspaceCanvas(state, Modifier.fillMaxSize()) }
                }
            }
            var frameTime = System.nanoTime()
            fun render() {
                repeat(12) {
                    frameTime += 16_666_667
                    scene.render(surface.canvas, frameTime)
                }
            }

            render()
            assertTrue(semanticsNodes(scene).any { semanticsText(it) == "Red Kraid" })
            val history = semanticsNodes(scene).single {
                it.config.contains(SemanticsActions.OnClick) && semanticsTextDeep(it) == "History"
            }
            val clickPosition = Offset(history.boundsInRoot.center.x, history.boundsInRoot.center.y)
            scene.sendPointerEvent(PointerEventType.Press, clickPosition, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, clickPosition, button = PointerButton.Primary)
            render()

            assertTrue(state.historyVisible)
            assertTrue(semanticsNodes(scene).any { semanticsText(it) == "Conversation history" })
            assertTrue(semanticsNodes(scene).any { semanticsTextDeep(it).contains("Conversation name") })
            assertTrue(semanticsNodes(scene).any { semanticsTextDeep(it) == "Open" })
        } finally {
            scene.close()
            surface.close()
        }
    }

    @Test
    fun `request failures appear chronologically after the user message and persist`() = runBlocking {
        val credentials = AiCredentialStore(temporary) { null }.also {
            it.save(AiProviderId.OPENAI, "test-key")
        }
        val conversations = AiConversationStore(temporary)
        val provider = TestProvider { _, _ -> error("OpenAI exceeded the SMEDIT tool-call limit.") }
        val state = AiWorkspaceState(
            credentialStore = credentials,
            conversationStore = conversations,
            providerList = listOf(provider),
        )
        state.refreshModels()
        state.selectModel("test-model")

        state.send("Recolor Kraid.")

        assertFalse(state.requestInFlight)
        assertNull(state.errorMessage)
        assertEquals(listOf(AiChatMessage::class, AiMutationNotice::class), state.conversationItems.map { it::class })
        val failure = state.conversationItems.last() as AiMutationNotice
        assertTrue(failure.isError)
        assertEquals("OpenAI request failed", failure.title)
        assertTrue(failure.text.contains("tool-call limit"))

        val restored = AiWorkspaceState(
            credentialStore = credentials,
            conversationStore = conversations,
            providerList = listOf(provider),
        )
        val restoredFailure = restored.conversationItems.last() as AiMutationNotice
        assertEquals("OpenAI request failed", restoredFailure.title)
        assertTrue(restoredFailure.isError)
    }

    @Test
    fun `request timeout clears thinking state and appends a failure message`() = runBlocking {
        val credentials = AiCredentialStore(temporary) { null }.also {
            it.save(AiProviderId.OPENAI, "test-key")
        }
        val state = AiWorkspaceState(
            credentialStore = credentials,
            conversationStore = AiConversationStore(temporary),
            providerList = listOf(TestProvider { _, _ -> awaitCancellation() }),
            requestTimeoutMillis = 25,
        )
        state.refreshModels()
        state.selectModel("test-model")

        state.send("Do a bounded step.")

        assertFalse(state.requestInFlight)
        val failure = state.conversationItems.last() as AiMutationNotice
        assertTrue(failure.isError)
        assertTrue(failure.text.contains("timed out after 25 ms"))
    }

    @Test
    fun `follow-up after a failed turn includes the failed request as provider context`() = runBlocking {
        val credentials = AiCredentialStore(temporary) { null }.also {
            it.save(AiProviderId.OPENAI, "test-key")
        }
        val runtimeContexts = mutableListOf<String?>()
        var requestCount = 0
        val provider = TestProvider { _, runtimeContext ->
            runtimeContexts += runtimeContext
            if (requestCount++ == 0) error("Temporary provider failure")
            AiChatReply("response-2", "I can continue in a bounded step.")
        }
        val state = AiWorkspaceState(
            credentialStore = credentials,
            conversationStore = AiConversationStore(temporary),
            providerList = listOf(provider),
        )
        state.refreshModels()
        state.selectModel("test-model")

        state.send("Recolor every related Kraid palette.")
        state.send("Can you do that in smaller steps?")

        assertTrue(runtimeContexts[1]!!.contains("Recolor every related Kraid palette."))
        assertEquals(AiChatRole.ASSISTANT, (state.conversationItems.last() as AiChatMessage).role)
        assertFalse(state.requestInFlight)
    }

    @Test
    fun `palette and item approval details survive history switching and restart`() {
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
            AiToolCall("palette-call", "set_palette_colors", buildJsonObject {}, "{}"),
        )
        runBlocking { state.approvePendingMutation() }
        val paletteConversationId = state.activeConversationId
        state.newConversation()
        state.executeToolCall(
            registry,
            AiToolCall("item-call", "place_item", buildJsonObject {}, "{}"),
        )
        runBlocking { state.approvePendingMutation() }

        val restored = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
            toolRegistry = registry,
            mutationController = FakeMutationController(),
        )
        val item = (restored.conversationItems.first() as AiMutationPlanMessage).plan.item
        assertEquals("Missile (Visible)", item?.itemName)
        assertTrue(restored.selectConversation(paletteConversationId))
        val palette = (restored.conversationItems.first() as AiMutationPlanMessage).plan.palette
        assertEquals("sprite:boss_kraid_health_bg", palette?.paletteId)
        assertEquals("sprite:boss_kraid_health_oam", palette?.additionalTargets?.single()?.paletteId)
    }

    @Test
    fun `room state field approval details survive restart`() {
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
            AiToolCall("state-fields-call", "set_room_state_fields", buildJsonObject {}, "{}"),
        )
        runBlocking { state.approvePendingMutation() }

        val restored = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
            toolRegistry = registry,
            mutationController = FakeMutationController(),
        )
        val message = restored.conversationItems.first() as AiMutationPlanMessage
        val fields = message.plan.roomStateFields

        assertEquals(SmeditMutationPlanKind.ROOM_STATE_FIELDS, message.plan.kind)
        assertEquals("state-1", fields?.stateId)
        assertEquals("Music data", fields?.changes?.first()?.label)
        assertEquals(0x24, fields?.changes?.first()?.after)
        assertEquals(AiToolActivityStatus.APPLIED, message.status)
    }

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
    fun `mutation validation failure records its exact error in the conversation`() {
        val registry = SmeditToolRegistry(EmptyWorkspace())
        val state = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = AiConversationStore(temporary),
            providerList = emptyList(),
            toolRegistry = registry,
            mutationController = FakeMutationController(),
        )

        fun reject() = state.executeToolCall(
            registry,
            AiToolCall(
                callId = "bad-palette-call-${state.toolActivity.size}",
                name = "set_palette_colors",
                arguments = buildJsonObject {},
                rawArguments = "{bad json}",
                argumentError = "Tool arguments were not a JSON object.",
            ),
        )
        val result = reject()
        val repeated = reject()

        assertTrue(result.isError)
        assertTrue(repeated.isError)
        val notice = state.conversationItems.single() as AiMutationNotice
        assertTrue(notice.isError)
        assertEquals("SMEDIT rejected set_palette_colors", notice.title)
        assertTrue(notice.text.contains("Tool arguments were not a JSON object."))
        assertTrue(notice.text.contains("invalid_arguments"))
    }

    @Test
    fun `restoring history collapses repeated identical validation notices`() {
        val conversations = AiConversationStore(temporary)
        conversations.save(
            StoredAiConversation(
                id = "duplicate-errors",
                providerId = AiProviderId.OPENAI.configId,
                updatedAtEpochMillis = 1,
                nextSequence = 3,
                items = listOf(1L, 2L, 3L).map { sequence ->
                    StoredAiConversationItem(
                        sequence = sequence,
                        kind = "notice",
                        text = "Use palette_edits or palette + changes, not both.",
                        title = "SMEDIT rejected set_palette_colors",
                        isError = true,
                    )
                },
            ),
        )

        val restored = AiWorkspaceState(
            credentialStore = AiCredentialStore(temporary) { null },
            conversationStore = conversations,
            providerList = emptyList(),
        )

        assertEquals(1, restored.conversationItems.size)
        assertEquals(1, conversations.loadActive(AiProviderId.OPENAI)?.items?.size)
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
    fun `rendered approve button applies its pending plan`() = runBlocking {
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

        val scene = ComposeScene(coroutineContext = coroutineContext)
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
            suspend fun render() {
                repeat(16) {
                    Snapshot.sendApplyNotifications()
                    yield()
                    frameTime += 16_666_667
                    scene.render(surface.canvas, frameTime)
                    delay(1)
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
            assertFalse(
                semanticsNodes(scene).any {
                    semanticsText(it) == "Approve" && it.config.contains(SemanticsActions.SetSelection)
                },
                "Button labels must not join the selectable conversation text.",
            )
            val clickPosition = Offset(approve.boundsInRoot.center.x, approve.boundsInRoot.center.y)
            scene.sendPointerEvent(PointerEventType.Press, clickPosition, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, clickPosition, button = PointerButton.Primary)
            render()
            delay(50)
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
            val base = SmeditMutationPlan(
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
            val plan = when (toolName) {
                "set_palette_colors" -> base.copy(
                    title = "Recolor Kraid",
                    kind = SmeditMutationPlanKind.PALETTE,
                    palette = SmeditPaletteMutationPlan(
                        paletteId = "sprite:boss_kraid_health_bg",
                        paletteName = "Kraid (health stages · BG)",
                        paletteKind = "sprite",
                        sourceManaged = false,
                        affectedPaletteIds = emptyList(),
                        colorChanges = listOf(SmeditPaletteColorChange(128, 8, 0, "#00FF00", "#FF0000")),
                        additionalTargets = listOf(
                            SmeditPaletteTargetMutationPlan(
                                paletteId = "sprite:boss_kraid_health_oam",
                                paletteName = "Kraid (health stages · OAM)",
                                paletteKind = "sprite",
                                sourceManaged = false,
                                affectedPaletteIds = emptyList(),
                                colorChanges = listOf(SmeditPaletteColorChange(128, 8, 0, "#00FF00", "#FF0000")),
                            ),
                        ),
                    ),
                )
                "place_item" -> base.copy(
                    title = "Place missile",
                    kind = SmeditMutationPlanKind.ITEM,
                    item = SmeditItemMutationPlan(
                        roomId = "0x91F8",
                        roomName = "Landing Site",
                        itemName = "Missile (Visible)",
                        plmIdHex = "0xEEDB",
                        x = 4,
                        y = 6,
                        parameterHex = "0x0051",
                        stateScope = "all_states",
                        stateIds = listOf("state-1", "state-2"),
                        sourceManaged = false,
                    ),
                )
                "set_room_state_fields" -> base.copy(
                    title = "Edit Landing Site · state-1",
                    kind = SmeditMutationPlanKind.ROOM_STATE_FIELDS,
                    roomStateFields = SmeditRoomStateFieldsMutationPlan(
                        roomId = "0x91F8",
                        roomName = "Landing Site",
                        stateId = "state-1",
                        stateCondition = "Event 0 set",
                        sourceManaged = false,
                        changes = listOf(
                            SmeditRoomStateFieldChange("music_data", "Music data", 0, 0x24),
                            SmeditRoomStateFieldChange("music_track", "Music track", 0, 3),
                        ),
                    ),
                )
                else -> base
            }
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

    private class TestProvider(
        private val response: suspend (prompt: String, runtimeContext: String?) -> AiChatReply,
    ) : AiProvider {
        override val id = AiProviderId.OPENAI

        override suspend fun listModels(apiKey: String) = listOf(AiModel("test-model"))

        override suspend fun createResponse(
            apiKey: String,
            modelId: String,
            prompt: String,
            previousResponseId: String?,
            runtimeContext: String?,
            toolDefinitions: List<SmeditToolDefinition>,
            executeTool: (suspend (AiToolCall) -> SmeditToolResult)?,
        ): AiChatReply = response(prompt, runtimeContext)
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

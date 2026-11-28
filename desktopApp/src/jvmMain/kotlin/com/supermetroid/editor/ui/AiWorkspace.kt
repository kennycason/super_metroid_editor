package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.ai.AiCredentialSource
import com.supermetroid.editor.ai.AiCredentialStore
import com.supermetroid.editor.ai.AiConversationStore
import com.supermetroid.editor.ai.AiModel
import com.supermetroid.editor.ai.AiProvider
import com.supermetroid.editor.ai.AiProviderId
import com.supermetroid.editor.ai.OpenAiProvider
import com.supermetroid.editor.ai.retainedProviderModelId
import com.supermetroid.editor.ai.AiToolCall
import com.supermetroid.editor.ai.StoredAiConversation
import com.supermetroid.editor.ai.StoredAiConversationItem
import com.supermetroid.editor.ai.StoredAiMutationPlan
import com.supermetroid.editor.ai.StoredAiPatchMutationPlan
import com.supermetroid.editor.ai.StoredAiPatchValueChange
import com.supermetroid.editor.ai.StoredAiRoomFxMutationPlan
import com.supermetroid.editor.ai.StoredAiRoomStateFieldChange
import com.supermetroid.editor.ai.StoredAiRoomStateFieldsMutationPlan
import com.supermetroid.editor.ai.StoredAiPaletteColorChange
import com.supermetroid.editor.ai.StoredAiPaletteMutationPlan
import com.supermetroid.editor.ai.StoredAiPaletteTargetMutationPlan
import com.supermetroid.editor.ai.StoredAiItemMutationPlan
import com.supermetroid.editor.ai.StoredAiRoomTileChangePlan
import com.supermetroid.editor.ai.StoredAiRoomTilesMutationPlan
import com.supermetroid.editor.api.SmeditToolRegistry
import com.supermetroid.editor.api.SmeditToolEffect
import com.supermetroid.editor.api.SmeditToolResult
import com.supermetroid.editor.api.SmeditMutationController
import com.supermetroid.editor.api.SmeditMutationPlanKind
import com.supermetroid.editor.api.SmeditMutationPlan
import com.supermetroid.editor.api.SmeditPatchMutationPlan
import com.supermetroid.editor.api.SmeditPatchValueChange
import com.supermetroid.editor.api.SmeditRoomFxMutationPlan
import com.supermetroid.editor.api.SmeditRoomStateFieldChange
import com.supermetroid.editor.api.SmeditRoomStateFieldsMutationPlan
import com.supermetroid.editor.api.SmeditPaletteColorChange
import com.supermetroid.editor.api.SmeditPaletteMutationPlan
import com.supermetroid.editor.api.SmeditPaletteTargetMutationPlan
import com.supermetroid.editor.api.SmeditItemMutationPlan
import com.supermetroid.editor.api.SmeditRoomTileChangePlan
import com.supermetroid.editor.api.SmeditRoomTilesMutationPlan
import com.supermetroid.editor.data.AppConfig
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

enum class AiChatRole { USER, ASSISTANT }

sealed interface AiConversationItem {
    val sequence: Long
}

data class AiChatMessage(
    val role: AiChatRole,
    val text: String,
    override val sequence: Long,
) : AiConversationItem

data class AiMutationPlanMessage(
    val plan: SmeditMutationPlan,
    val status: AiToolActivityStatus,
    override val sequence: Long,
) : AiConversationItem

data class AiMutationNotice(
    val planId: String?,
    val text: String,
    val isError: Boolean,
    override val sequence: Long,
    val title: String? = null,
) : AiConversationItem

enum class AiToolActivityStatus { RUNNING, SUCCEEDED, FAILED, AWAITING_APPROVAL, APPLIED, REJECTED, UNDONE, REDONE }

enum class AiMutationAction { APPLYING, REJECTING }

private val AI_CONTROL_SHAPE = RoundedCornerShape(4.dp)

data class AiToolActivity(
    val callId: String,
    val name: String,
    val arguments: String,
    val status: AiToolActivityStatus,
    val outputPreview: String? = null,
    val planId: String? = null,
)

data class AiConversationHistoryEntry(
    val id: String,
    val title: String,
    val customTitle: String?,
    val modelId: String?,
    val updatedAtEpochMillis: Long,
    val itemCount: Int,
)

internal fun maskedAiCredentialPreview(value: String): String = when {
    value.length > 20 -> "${value.take(10)}……${value.takeLast(10)}"
    else -> "Configured key (${value.length} characters)"
}

/** Shared reactive state for Settings and the AI workspace. */
class AiWorkspaceState(
    private val credentialStore: AiCredentialStore = AiCredentialStore(),
    private val conversationStore: AiConversationStore = AiConversationStore(),
    providerList: List<AiProvider> = listOf(OpenAiProvider()),
    private val toolRegistry: SmeditToolRegistry? = null,
    private val mutationController: SmeditMutationController? = null,
    private val requestTimeoutMillis: Long = DEFAULT_AI_REQUEST_TIMEOUT_MILLIS,
) {
    private val providers = providerList.associateBy(AiProvider::id)
    private var credentialRevision by mutableStateOf(0)

    var selectedProvider by mutableStateOf(initialProvider())
        private set
    var models by mutableStateOf<List<AiModel>>(emptyList())
        private set
    var selectedModelId by mutableStateOf<String?>(null)
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var requestInFlight by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var promptDraft by mutableStateOf("")
    var pendingMutation by mutableStateOf<SmeditMutationPlan?>(null)
        private set
    var mutationActionInFlight by mutableStateOf<AiMutationAction?>(null)
        private set
    val interactionInFlight: Boolean
        get() = requestInFlight || mutationActionInFlight != null
    var canUndoAiMutation by mutableStateOf(false)
        private set
    var canRedoAiMutation by mutableStateOf(false)
        private set
    val messages = mutableStateListOf<AiChatMessage>()
    private val mutationPlans = mutableStateListOf<AiMutationPlanMessage>()
    private val mutationNotices = mutableStateListOf<AiMutationNotice>()
    val toolActivity = mutableStateListOf<AiToolActivity>()
    val conversationHistory = mutableStateListOf<AiConversationHistoryEntry>()
    var historyVisible by mutableStateOf(false)
        private set

    private var previousResponseId: String? = null
    private var nextConversationSequence = 0L
    private var conversationId = UUID.randomUUID().toString()
    private var conversationCustomTitle: String? = null
    private var conversationModelId: String? = null
    val activeConversationId: String get() = conversationId

    val conversationItems: List<AiConversationItem>
        get() = (messages + mutationPlans + mutationNotices).sortedBy(AiConversationItem::sequence)

    private fun nextSequence(): Long = ++nextConversationSequence

    internal fun addChatMessage(role: AiChatRole, text: String) {
        messages += AiChatMessage(role, text, nextSequence())
        persistConversation()
    }

    internal fun commitAssistantReply(text: String, plansCreatedAfterSequence: Long) {
        messages += AiChatMessage(AiChatRole.ASSISTANT, text, nextSequence())
        mutationPlans.indices
            .filter { mutationPlans[it].sequence > plansCreatedAfterSequence }
            .forEach { index ->
                mutationPlans[index] = mutationPlans[index].copy(sequence = nextSequence())
            }
        persistConversation()
    }

    init {
        restoreConversation(selectedProvider)
    }

    private fun restoreConversation(provider: AiProviderId) {
        clearConversationMemory()
        val stored = conversationStore.loadActive(provider)
        if (stored == null) {
            conversationId = UUID.randomUUID().toString()
            conversationCustomTitle = null
            conversationModelId = AppConfig.load().aiSelectedModels[provider.configId]
            refreshConversationHistory()
            return
        }
        conversationId = stored.id
        conversationCustomTitle = stored.customTitle
        conversationModelId = stored.modelId
        nextConversationSequence = stored.nextSequence
        val expiredPlanIds = mutableListOf<String>()
        stored.items.sortedBy(StoredAiConversationItem::sequence).forEach { item ->
            nextConversationSequence = maxOf(nextConversationSequence, item.sequence)
            when (item.kind) {
                ITEM_CHAT -> {
                    val role = AiChatRole.entries.firstOrNull { it.name == item.role } ?: return@forEach
                    val text = item.text ?: return@forEach
                    messages += AiChatMessage(role, text, item.sequence)
                }
                ITEM_PLAN -> {
                    val plan = item.plan?.toRuntime() ?: return@forEach
                    val restoredStatus = AiToolActivityStatus.entries.firstOrNull { it.name == item.status }
                        ?: AiToolActivityStatus.FAILED
                    val status = if (restoredStatus == AiToolActivityStatus.AWAITING_APPROVAL) {
                        expiredPlanIds += plan.id
                        AiToolActivityStatus.REJECTED
                    } else restoredStatus
                    mutationPlans += AiMutationPlanMessage(plan, status, item.sequence)
                }
                ITEM_NOTICE -> {
                    val text = item.text ?: return@forEach
                    mutationNotices += AiMutationNotice(item.planId, text, item.isError, item.sequence, item.title)
                }
            }
        }
        val normalizedLegacyPlanOrdering = normalizeLegacyPlanOrdering()
        val normalizedDuplicateNotices = normalizeDuplicateNotices()
        val configuredModel = AppConfig.load().aiSelectedModels[provider.configId]
        previousResponseId = stored.previousResponseId.takeIf { stored.modelId != null && stored.modelId == configuredModel }
        expiredPlanIds.forEach { planId ->
            mutationNotices += AiMutationNotice(
                planId = planId,
                text = "This unapproved plan expired when SMEDIT closed. Ask again to create a fresh, revalidated plan.",
                isError = false,
                sequence = nextSequence(),
            )
        }
        if (expiredPlanIds.isNotEmpty() || normalizedLegacyPlanOrdering || normalizedDuplicateNotices) persistConversation()
        else refreshConversationHistory()
    }

    private fun normalizeLegacyPlanOrdering(): Boolean {
        val legacyPairs = conversationItems.zipWithNext().filter { (first, second) ->
            first is AiMutationPlanMessage && second is AiChatMessage && second.role == AiChatRole.ASSISTANT
        }
        legacyPairs.forEach { (first, second) ->
            val plan = first as AiMutationPlanMessage
            val assistant = second as AiChatMessage
            val planIndex = mutationPlans.indexOfFirst { it.sequence == plan.sequence }
            val assistantIndex = messages.indexOfFirst { it.sequence == assistant.sequence }
            if (planIndex >= 0 && assistantIndex >= 0) {
                mutationPlans[planIndex] = plan.copy(sequence = assistant.sequence)
                messages[assistantIndex] = assistant.copy(sequence = plan.sequence)
            }
        }
        return legacyPairs.isNotEmpty()
    }

    private fun normalizeDuplicateNotices(): Boolean {
        val duplicates = conversationItems.zipWithNext().mapNotNull { (first, second) ->
            val left = first as? AiMutationNotice ?: return@mapNotNull null
            val right = second as? AiMutationNotice ?: return@mapNotNull null
            right.sequence.takeIf {
                left.planId == right.planId &&
                    left.text == right.text &&
                    left.isError == right.isError &&
                    left.title == right.title
            }
        }.toSet()
        if (duplicates.isEmpty()) return false
        mutationNotices.removeAll { it.sequence in duplicates }
        return true
    }

    private fun clearConversationMemory() {
        pendingMutation = null
        mutationActionInFlight = null
        previousResponseId = null
        messages.clear()
        mutationPlans.clear()
        mutationNotices.clear()
        toolActivity.clear()
        canUndoAiMutation = false
        canRedoAiMutation = false
        nextConversationSequence = 0L
        conversationCustomTitle = null
    }

    private fun persistConversation() {
        val snapshot = StoredAiConversation(
            id = conversationId,
            providerId = selectedProvider.configId,
            customTitle = conversationCustomTitle,
            modelId = conversationModelId ?: selectedModelId,
            previousResponseId = previousResponseId,
            updatedAtEpochMillis = System.currentTimeMillis(),
            nextSequence = nextConversationSequence,
            items = conversationItems.map { it.toStored() },
        )
        runCatching { conversationStore.save(snapshot) }.onFailure { problem ->
            errorMessage = "Could not save AI conversation history: ${problem.message ?: "unknown file error"}"
        }.onSuccess { refreshConversationHistory() }
    }

    private fun refreshConversationHistory() {
        val entries = conversationStore.conversations(selectedProvider).map { stored ->
            val generatedTitle = stored.items.asSequence()
                .filter { it.kind == ITEM_CHAT && it.role == AiChatRole.USER.name }
                .mapNotNull(StoredAiConversationItem::text)
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .firstOrNull(String::isNotEmpty)
                ?.take(64)
                ?: "New conversation"
            AiConversationHistoryEntry(
                id = stored.id,
                title = stored.customTitle ?: generatedTitle,
                customTitle = stored.customTitle,
                modelId = stored.modelId,
                updatedAtEpochMillis = stored.updatedAtEpochMillis,
                itemCount = stored.items.size,
            )
        }.toMutableList()
        if (entries.none { it.id == conversationId }) {
            entries.add(
                0,
                AiConversationHistoryEntry(
                    id = conversationId,
                    title = conversationCustomTitle ?: "New conversation",
                    customTitle = conversationCustomTitle,
                    modelId = conversationModelId ?: selectedModelId,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    itemCount = conversationItems.size,
                ),
            )
        }
        conversationHistory.clear()
        conversationHistory.addAll(entries)
    }

    private fun AiConversationItem.toStored(): StoredAiConversationItem = when (this) {
        is AiChatMessage -> StoredAiConversationItem(
            sequence = sequence,
            kind = ITEM_CHAT,
            role = role.name,
            text = text,
        )
        is AiMutationPlanMessage -> StoredAiConversationItem(
            sequence = sequence,
            kind = ITEM_PLAN,
            planId = plan.id,
            plan = plan.toStored(),
            status = status.name,
        )
        is AiMutationNotice -> StoredAiConversationItem(
            sequence = sequence,
            kind = ITEM_NOTICE,
            planId = planId,
            text = text,
            title = title,
            isError = isError,
        )
    }

    private fun SmeditMutationPlan.toStored() = StoredAiMutationPlan(
        id = id,
        toolName = toolName,
        title = title,
        musicData = musicData,
        musicTrack = musicTrack,
        affectedRoomCount = affectedRoomCount,
        affectedStateCount = affectedStateCount,
        affectedRoomPreview = affectedRoomPreview,
        excludedRoomIds = excludedRoomIds,
        excludedAreas = excludedAreas,
        warnings = warnings,
        protectedRoomIds = protectedRoomIds,
        protectedRoomPreview = protectedRoomPreview,
        kind = kind.name,
        roomStateFields = roomStateFields?.let { fields ->
            StoredAiRoomStateFieldsMutationPlan(
                roomId = fields.roomId,
                roomName = fields.roomName,
                stateId = fields.stateId,
                stateCondition = fields.stateCondition,
                sourceManaged = fields.sourceManaged,
                changes = fields.changes.map { change ->
                    StoredAiRoomStateFieldChange(
                        change.field,
                        change.label,
                        change.before,
                        change.after,
                    )
                },
            )
        },
        patch = patch?.let { patch ->
            StoredAiPatchMutationPlan(
                patchId = patch.patchId,
                patchName = patch.patchName,
                configType = patch.configType,
                beforeEnabled = patch.beforeEnabled,
                afterEnabled = patch.afterEnabled,
                valueChanges = patch.valueChanges.map { change ->
                    StoredAiPatchValueChange(change.key, change.label, change.before, change.after)
                },
                automaticallyDisabledPatches = patch.automaticallyDisabledPatches,
            )
        },
        fx = fx?.let { fx ->
            StoredAiRoomFxMutationPlan(fx.requestedChanges, fx.affectedStatePreview)
        },
        palette = palette?.let { palette ->
            StoredAiPaletteMutationPlan(
                paletteId = palette.paletteId,
                paletteName = palette.paletteName,
                paletteKind = palette.paletteKind,
                sourceManaged = palette.sourceManaged,
                affectedPaletteIds = palette.affectedPaletteIds,
                colorChanges = palette.colorChanges.map { change ->
                    StoredAiPaletteColorChange(
                        change.index,
                        change.row,
                        change.column,
                        change.beforeRgb,
                        change.afterRgb,
                    )
                },
                additionalTargets = palette.additionalTargets.map { target ->
                    StoredAiPaletteTargetMutationPlan(
                        paletteId = target.paletteId,
                        paletteName = target.paletteName,
                        paletteKind = target.paletteKind,
                        sourceManaged = target.sourceManaged,
                        affectedPaletteIds = target.affectedPaletteIds,
                        colorChanges = target.colorChanges.map { change ->
                            StoredAiPaletteColorChange(
                                change.index,
                                change.row,
                                change.column,
                                change.beforeRgb,
                                change.afterRgb,
                            )
                        },
                    )
                },
            )
        },
        item = item?.let { item ->
            StoredAiItemMutationPlan(
                roomId = item.roomId,
                roomName = item.roomName,
                itemName = item.itemName,
                plmIdHex = item.plmIdHex,
                x = item.x,
                y = item.y,
                parameterHex = item.parameterHex,
                stateScope = item.stateScope,
                stateIds = item.stateIds,
                sourceManaged = item.sourceManaged,
            )
        },
        roomTiles = roomTiles?.let { tiles ->
            StoredAiRoomTilesMutationPlan(
                roomId = tiles.roomId,
                roomName = tiles.roomName,
                stateScope = tiles.stateScope,
                stateIds = tiles.stateIds,
                forkedStateIds = tiles.forkedStateIds,
                changes = tiles.changes.map { change ->
                    StoredAiRoomTileChangePlan(
                        stateId = change.stateId,
                        layoutResourceId = change.layoutResourceId,
                        x = change.x,
                        y = change.y,
                        layer = change.layer,
                        beforeWordHex = change.beforeWordHex,
                        afterWordHex = change.afterWordHex,
                        beforeBtsHex = change.beforeBtsHex,
                        afterBtsHex = change.afterBtsHex,
                    )
                },
            )
        },
    )

    private fun StoredAiMutationPlan.toRuntime() = SmeditMutationPlan(
        id = id,
        toolName = toolName,
        title = title,
        musicData = musicData,
        musicTrack = musicTrack,
        affectedRoomCount = affectedRoomCount,
        affectedStateCount = affectedStateCount,
        affectedRoomPreview = affectedRoomPreview,
        excludedRoomIds = excludedRoomIds,
        excludedAreas = excludedAreas,
        warnings = warnings,
        protectedRoomIds = protectedRoomIds,
        protectedRoomPreview = protectedRoomPreview,
        kind = SmeditMutationPlanKind.entries.firstOrNull { it.name == kind }
            ?: SmeditMutationPlanKind.ROOM_MUSIC,
        roomStateFields = roomStateFields?.let { fields ->
            SmeditRoomStateFieldsMutationPlan(
                roomId = fields.roomId,
                roomName = fields.roomName,
                stateId = fields.stateId,
                stateCondition = fields.stateCondition,
                sourceManaged = fields.sourceManaged,
                changes = fields.changes.map { change ->
                    SmeditRoomStateFieldChange(
                        change.field,
                        change.label,
                        change.before,
                        change.after,
                    )
                },
            )
        },
        patch = patch?.let { patch ->
            SmeditPatchMutationPlan(
                patchId = patch.patchId,
                patchName = patch.patchName,
                configType = patch.configType,
                beforeEnabled = patch.beforeEnabled,
                afterEnabled = patch.afterEnabled,
                valueChanges = patch.valueChanges.map { change ->
                    SmeditPatchValueChange(change.key, change.label, change.before, change.after)
                },
                automaticallyDisabledPatches = patch.automaticallyDisabledPatches,
            )
        },
        fx = fx?.let { fx ->
            SmeditRoomFxMutationPlan(fx.requestedChanges, fx.affectedStatePreview)
        },
        palette = palette?.let { palette ->
            SmeditPaletteMutationPlan(
                paletteId = palette.paletteId,
                paletteName = palette.paletteName,
                paletteKind = palette.paletteKind,
                sourceManaged = palette.sourceManaged,
                affectedPaletteIds = palette.affectedPaletteIds,
                colorChanges = palette.colorChanges.map { change ->
                    SmeditPaletteColorChange(
                        change.index,
                        change.row,
                        change.column,
                        change.beforeRgb,
                        change.afterRgb,
                    )
                },
                additionalTargets = palette.additionalTargets.map { target ->
                    SmeditPaletteTargetMutationPlan(
                        paletteId = target.paletteId,
                        paletteName = target.paletteName,
                        paletteKind = target.paletteKind,
                        sourceManaged = target.sourceManaged,
                        affectedPaletteIds = target.affectedPaletteIds,
                        colorChanges = target.colorChanges.map { change ->
                            SmeditPaletteColorChange(
                                change.index,
                                change.row,
                                change.column,
                                change.beforeRgb,
                                change.afterRgb,
                            )
                        },
                    )
                },
            )
        },
        item = item?.let { item ->
            SmeditItemMutationPlan(
                roomId = item.roomId,
                roomName = item.roomName,
                itemName = item.itemName,
                plmIdHex = item.plmIdHex,
                x = item.x,
                y = item.y,
                parameterHex = item.parameterHex,
                stateScope = item.stateScope,
                stateIds = item.stateIds,
                sourceManaged = item.sourceManaged,
            )
        },
        roomTiles = roomTiles?.let { tiles ->
            SmeditRoomTilesMutationPlan(
                roomId = tiles.roomId,
                roomName = tiles.roomName,
                stateScope = tiles.stateScope,
                stateIds = tiles.stateIds,
                forkedStateIds = tiles.forkedStateIds,
                changes = tiles.changes.map { change ->
                    SmeditRoomTileChangePlan(
                        stateId = change.stateId,
                        layoutResourceId = change.layoutResourceId,
                        x = change.x,
                        y = change.y,
                        layer = change.layer,
                        beforeWordHex = change.beforeWordHex,
                        afterWordHex = change.afterWordHex,
                        beforeBtsHex = change.beforeBtsHex,
                        afterBtsHex = change.afterBtsHex,
                    )
                },
            )
        },
    )

    private fun initialProvider(): AiProviderId {
        val preferred = AiProviderId.fromConfigId(AppConfig.load().aiSelectedProvider)
        if (preferred != null && activeCredential(preferred) != null) return preferred
        return AiProviderId.entries.firstOrNull { activeCredential(it) != null }
            ?: preferred
            ?: AiProviderId.OPENAI
    }

    private fun configuredCredentialSource(provider: AiProviderId): AiCredentialSource? =
        AiCredentialSource.fromConfigId(AppConfig.load().aiCredentialSources[provider.configId])

    private fun credentialModeValue(provider: AiProviderId): AiCredentialSource {
        val configured = configuredCredentialSource(provider)
        if (configured == AiCredentialSource.SMEDIT_STORE) return configured
        if (credentialStore.hasCredential(provider, AiCredentialSource.ENVIRONMENT)) {
            return AiCredentialSource.ENVIRONMENT
        }
        return AiCredentialSource.SMEDIT_STORE
    }

    private fun activeCredential(provider: AiProviderId) =
        credentialStore.credential(provider, credentialModeValue(provider))

    val hasAnyCredential: Boolean
        get() {
            credentialRevision
            return AiProviderId.entries.any { activeCredential(it) != null }
        }

    fun credentialSource(provider: AiProviderId): AiCredentialSource? {
        credentialRevision
        return activeCredential(provider)?.source
    }

    fun credentialMode(provider: AiProviderId): AiCredentialSource {
        credentialRevision
        return credentialModeValue(provider)
    }

    fun credentialPreview(provider: AiProviderId, source: AiCredentialSource = credentialModeValue(provider)): String? {
        credentialRevision
        return credentialStore.credential(provider, source)?.value?.let(::maskedAiCredentialPreview)
    }

    fun hasCredential(provider: AiProviderId): Boolean {
        credentialRevision
        return activeCredential(provider) != null
    }

    fun hasCredential(provider: AiProviderId, source: AiCredentialSource): Boolean {
        credentialRevision
        return credentialStore.hasCredential(provider, source)
    }

    fun selectCredentialSource(provider: AiProviderId, source: AiCredentialSource) {
        if (source == AiCredentialSource.ENVIRONMENT && !credentialStore.hasCredential(provider, source)) return
        if (credentialModeValue(provider) == source && configuredCredentialSource(provider) == source) return
        AppConfig.update {
            copy(aiCredentialSources = aiCredentialSources + (provider.configId to source.configId))
        }
        credentialRevision++
        if (selectedProvider == provider) {
            models = emptyList()
            selectedModelId = null
            errorMessage = null
            newConversation()
        }
    }

    suspend fun saveCredential(provider: AiProviderId, apiKey: String): Boolean {
        return try {
            credentialStore.save(provider, apiKey)
            AppConfig.update {
                copy(aiCredentialSources = aiCredentialSources +
                    (provider.configId to AiCredentialSource.SMEDIT_STORE.configId))
            }
            credentialRevision++
            selectProvider(provider)
            models = emptyList()
            selectedModelId = null
            newConversation()
            refreshModels()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "Could not save the API key."
            false
        }
    }

    fun deleteCredential(provider: AiProviderId) {
        val deletingActiveCredential = credentialModeValue(provider) == AiCredentialSource.SMEDIT_STORE
        credentialStore.delete(provider)
        if (configuredCredentialSource(provider) == AiCredentialSource.SMEDIT_STORE &&
            credentialStore.hasCredential(provider, AiCredentialSource.ENVIRONMENT)
        ) {
            AppConfig.update {
                copy(aiCredentialSources = aiCredentialSources +
                    (provider.configId to AiCredentialSource.ENVIRONMENT.configId))
            }
        }
        credentialRevision++
        if (selectedProvider == provider && deletingActiveCredential) {
            models = emptyList()
            selectedModelId = null
            errorMessage = null
            newConversation()
            if (!hasCredential(provider)) {
                AiProviderId.entries.firstOrNull { activeCredential(it) != null }?.let(::selectProvider)
            }
        }
    }

    fun selectProvider(provider: AiProviderId) {
        if (selectedProvider == provider) {
            if (AppConfig.load().aiSelectedProvider != provider.configId) {
                AppConfig.update { copy(aiSelectedProvider = provider.configId) }
            }
            return
        }
        abandonPendingPlan("The unapproved plan was closed when the AI provider changed. No project data changed.")
        selectedProvider = provider
        models = emptyList()
        selectedModelId = null
        errorMessage = null
        restoreConversation(provider)
        AppConfig.update { copy(aiSelectedProvider = provider.configId) }
    }

    suspend fun refreshModels() {
        if (modelsLoading) return
        val provider = providers[selectedProvider]
        val credential = activeCredential(selectedProvider)
        if (credential == null) {
            models = emptyList()
            selectedModelId = null
            errorMessage = "Add a ${selectedProvider.displayName} API key in Settings → AI."
            return
        }
        if (provider == null) {
            models = emptyList()
            selectedModelId = null
            errorMessage = "${selectedProvider.displayName} support is coming next."
            return
        }
        modelsLoading = true
        errorMessage = null
        try {
            val fetched = provider.listModels(credential.value)
            models = fetched
            val saved = AppConfig.load().aiSelectedModels[selectedProvider.configId]
            selectedModelId = retainedProviderModelId(saved, fetched)
            if (conversationItems.isEmpty() && conversationModelId == null) {
                conversationModelId = selectedModelId
            }
            if (saved != null && selectedModelId == null) {
                AppConfig.update {
                    copy(aiSelectedModels = aiSelectedModels - selectedProvider.configId)
                }
            }
            if (fetched.isEmpty()) errorMessage = "The provider returned no models for this account."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            models = emptyList()
            selectedModelId = null
            errorMessage = e.message ?: "Could not load models."
        } finally {
            modelsLoading = false
        }
    }

    fun selectModel(modelId: String) {
        require(models.any { it.id == modelId }) { "Model must come from the provider model list." }
        val changed = selectedModelId != modelId
        selectedModelId = modelId
        if (changed) newConversation()
        AppConfig.update {
            copy(aiSelectedModels = aiSelectedModels + (selectedProvider.configId to modelId))
        }
    }

    suspend fun send(prompt: String) {
        val normalized = prompt.trim()
        if (normalized.isEmpty() || interactionInFlight) return
        val modelId = selectedModelId
        if (modelId == null || models.none { it.id == modelId }) {
            errorMessage = "Select a model returned by ${selectedProvider.displayName}."
            return
        }
        val provider = providers[selectedProvider] ?: run {
            errorMessage = "${selectedProvider.displayName} support is coming next."
            return
        }
        val credential = activeCredential(selectedProvider) ?: run {
            errorMessage = "Add a ${selectedProvider.displayName} API key in Settings → AI."
            return
        }
        requestInFlight = true
        errorMessage = null
        conversationModelId = modelId
        addChatMessage(AiChatRole.USER, normalized)
        val plansCreatedAfterSequence = nextConversationSequence
        try {
            val reply = withTimeout(requestTimeoutMillis) {
                provider.createResponse(
                    apiKey = credential.value,
                    modelId = modelId,
                    prompt = normalized,
                    previousResponseId = previousResponseId,
                    runtimeContext = providerRuntimeContext(plansCreatedAfterSequence),
                    toolDefinitions = toolRegistry?.definitions.orEmpty(),
                    executeTool = toolRegistry?.let { registry ->
                        { call -> executeToolCall(registry, call) }
                    },
                )
            }
            previousResponseId = reply.responseId
            commitAssistantReply(reply.text, plansCreatedAfterSequence)
        } catch (_: TimeoutCancellationException) {
            addRequestNotice(
                "The request timed out after ${formatRequestTimeout(requestTimeoutMillis)}. " +
                    "SMEDIT stopped waiting; try again or ask for a smaller bounded step.",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addRequestNotice(e.message ?: "The AI request failed.")
        } finally {
            requestInFlight = false
        }
    }

    fun requestCancelled() {
        if (!requestInFlight) return
        requestInFlight = false
        addRequestNotice("The request was cancelled. No unapproved project changes were applied.", isError = false)
    }

    private fun approvalRuntimeContext(): String = pendingMutation?.let { plan ->
        "At the start of this user request, mutation plan '${plan.title}' (${plan.id}) is awaiting " +
            "approval in the SMEDIT UI. Do not stage another mutation until the user approves or rejects it."
    } ?: "At the start of this user request, NO mutation plan is awaiting approval in SMEDIT. " +
        "Any approval_required or pending-plan statement in an earlier provider response is historical and stale; " +
        "it must not block a new mutation tool call. A mutation tool successfully called during this request may " +
        "create a new pending plan."

    private fun providerRuntimeContext(currentUserSequence: Long): String {
        val lastAssistantSequence = messages.asSequence()
            .filter { it.role == AiChatRole.ASSISTANT }
            .maxOfOrNull(AiChatMessage::sequence)
            ?: 0L
        val failedTurnPrompts = messages.asSequence()
            .filter {
                it.role == AiChatRole.USER &&
                    it.sequence > lastAssistantSequence &&
                    it.sequence < currentUserSequence
            }
            .toList()
            .takeLast(3)
        if (failedTurnPrompts.isEmpty()) return approvalRuntimeContext()
        return buildString {
            append(approvalRuntimeContext())
            append("\n\nLocal user request(s) after the last successful provider response are included below because " +
                "their provider turns failed or timed out. The current prompt may refer to them:\n")
            failedTurnPrompts.forEach { prompt ->
                append("- ")
                append(prompt.text.take(2_000))
                append('\n')
            }
        }.trimEnd()
    }

    fun newConversation() {
        abandonPendingPlan("The unapproved plan was closed when a new conversation started. No project data changed.")
        clearConversationMemory()
        conversationId = UUID.randomUUID().toString()
        conversationModelId = selectedModelId
        historyVisible = false
        errorMessage = null
        persistConversation()
    }

    fun selectConversation(id: String): Boolean {
        if (id == conversationId) {
            historyVisible = false
            return true
        }
        if (interactionInFlight) return false
        val stored = conversationStore.load(selectedProvider, id) ?: return false
        abandonPendingPlan("The unapproved plan was closed when another conversation was opened. No project data changed.")
        if (!conversationStore.setActive(selectedProvider, stored.id)) return false
        restoreConversation(selectedProvider)
        historyVisible = false
        errorMessage = null
        return conversationId == stored.id
    }

    fun showConversationHistory() {
        historyVisible = true
        refreshConversationHistory()
    }

    fun renameConversation(id: String, title: String): Boolean {
        val normalized = title.trim()
            .take(AiConversationStore.MAX_CONVERSATION_TITLE_LENGTH)
            .takeIf(String::isNotEmpty)
        val renamed = if (id == conversationId) {
            conversationCustomTitle = normalized
            persistConversation()
            true
        } else {
            conversationStore.rename(selectedProvider, id, normalized).also {
                if (it) refreshConversationHistory()
            }
        }
        return renamed
    }

    private fun abandonPendingPlan(message: String) {
        val plan = pendingMutation ?: return
        runCatching { mutationController?.reject(plan.id) }
        pendingMutation = null
        updatePlanStatus(plan.id, AiToolActivityStatus.REJECTED)
        updateActivity(plan.id, AiToolActivityStatus.REJECTED)
        addMutationNotice(plan.id, message)
    }

    suspend fun approvePendingMutation() {
        if (mutationActionInFlight != null) return
        val plan = pendingMutation ?: return
        mutationActionInFlight = AiMutationAction.APPLYING
        try {
            delay(MUTATION_ACTION_FEEDBACK_DELAY_MS)
            val transition = requireNotNull(mutationController) { "SMEDIT mutation approval is unavailable." }.approve(plan.id)
            pendingMutation = null
            updatePlanStatus(plan.id, AiToolActivityStatus.APPLIED)
            addMutationNotice(plan.id, transition.summary)
            updateActivity(plan.id, AiToolActivityStatus.APPLIED)
            syncMutationHistory()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            addMutationNotice(plan.id, problem.message ?: "The AI action could not be applied.", isError = true)
        } finally {
            mutationActionInFlight = null
        }
    }

    suspend fun rejectPendingMutation() {
        if (mutationActionInFlight != null) return
        val plan = pendingMutation ?: return
        mutationActionInFlight = AiMutationAction.REJECTING
        try {
            delay(MUTATION_ACTION_FEEDBACK_DELAY_MS)
            requireNotNull(mutationController) { "SMEDIT mutation approval is unavailable." }.reject(plan.id)
            pendingMutation = null
            updatePlanStatus(plan.id, AiToolActivityStatus.REJECTED)
            addMutationNotice(plan.id, "Rejected ${plan.title.lowercase()}. No project data changed.")
            updateActivity(plan.id, AiToolActivityStatus.REJECTED)
            syncMutationHistory()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            addMutationNotice(plan.id, problem.message ?: "The AI action could not be rejected.", isError = true)
        } finally {
            mutationActionInFlight = null
        }
    }

    fun undoAiMutation() {
        try {
            val transition = requireNotNull(mutationController) { "SMEDIT AI undo is unavailable." }.undo()
            updatePlanStatus(transition.planId, AiToolActivityStatus.UNDONE)
            addMutationNotice(transition.planId, transition.summary)
            updateActivity(transition.planId, AiToolActivityStatus.UNDONE)
            syncMutationHistory()
        } catch (problem: Exception) {
            addMutationNotice(null, problem.message ?: "The AI action could not be undone.", isError = true)
        }
    }

    fun redoAiMutation() {
        try {
            val transition = requireNotNull(mutationController) { "SMEDIT AI redo is unavailable." }.redo()
            updatePlanStatus(transition.planId, AiToolActivityStatus.REDONE)
            addMutationNotice(transition.planId, transition.summary)
            updateActivity(transition.planId, AiToolActivityStatus.REDONE)
            syncMutationHistory()
        } catch (problem: Exception) {
            addMutationNotice(null, problem.message ?: "The AI action could not be redone.", isError = true)
        }
    }

    private fun addMutationNotice(
        planId: String?,
        text: String,
        isError: Boolean = false,
        title: String? = null,
        deduplicateConsecutive: Boolean = false,
    ) {
        val notice = AiMutationNotice(planId, text, isError, nextConversationSequence + 1, title)
        if (deduplicateConsecutive) {
            val previous = conversationItems.lastOrNull() as? AiMutationNotice
            if (previous != null && previous.planId == notice.planId && previous.text == notice.text &&
                previous.isError == notice.isError && previous.title == notice.title
            ) {
                return
            }
        }
        nextConversationSequence++
        mutationNotices += notice
        persistConversation()
    }

    private fun addRequestNotice(text: String, isError: Boolean = true) {
        addMutationNotice(
            planId = null,
            text = text,
            isError = isError,
            title = if (isError) "${selectedProvider.displayName} request failed" else "Request cancelled",
        )
    }

    private fun updatePlanStatus(planId: String, status: AiToolActivityStatus) {
        val index = mutationPlans.indexOfLast { it.plan.id == planId }
        if (index >= 0) mutationPlans[index] = mutationPlans[index].copy(status = status)
    }

    private fun syncMutationHistory() {
        canUndoAiMutation = mutationController?.canUndo == true
        canRedoAiMutation = mutationController?.canRedo == true
    }

    private fun updateActivity(planId: String, status: AiToolActivityStatus) {
        val index = toolActivity.indexOfLast { it.planId == planId }
        if (index >= 0) toolActivity[index] = toolActivity[index].copy(status = status)
    }

    internal fun executeToolCall(registry: SmeditToolRegistry, call: AiToolCall): SmeditToolResult {
        val activity = AiToolActivity(
            callId = call.callId,
            name = call.name,
            arguments = call.rawArguments.take(300),
            status = AiToolActivityStatus.RUNNING,
        )
        toolActivity += activity
        var plan: SmeditMutationPlan? = null
        val result = call.argumentError?.let { problem ->
            SmeditToolResult(
                output = buildJsonObject {
                    put("ok", false)
                    put("error", buildJsonObject {
                        put("code", "invalid_arguments")
                        put("message", problem)
                    })
                },
                isError = true,
            )
        } ?: if (registry.definition(call.name)?.effect == SmeditToolEffect.MUTATION) {
            val controller = mutationController
            if (controller == null) {
                SmeditToolResult(
                    output = buildJsonObject {
                        put("ok", false)
                        put("error", buildJsonObject {
                            put("code", "mutations_unavailable")
                            put("message", "Approval-backed SMEDIT mutations are unavailable in this workspace.")
                        })
                    },
                    isError = true,
                )
            } else {
                controller.plan(call.name, call.arguments).also { planning ->
                    plan = planning.plan
                    if (planning.plan != null) {
                        pendingMutation = planning.plan
                        mutationPlans += AiMutationPlanMessage(
                            plan = planning.plan,
                            status = AiToolActivityStatus.AWAITING_APPROVAL,
                            sequence = nextSequence(),
                        )
                        persistConversation()
                    }
                }.result
            }
        } else registry.execute(call.name, call.arguments)
        val index = toolActivity.indexOfFirst { it.callId == call.callId }
        if (index >= 0) {
            toolActivity[index] = activity.copy(
                status = when {
                    result.isError -> AiToolActivityStatus.FAILED
                    plan != null -> AiToolActivityStatus.AWAITING_APPROVAL
                    else -> AiToolActivityStatus.SUCCEEDED
                },
                outputPreview = result.outputJson().take(300),
                planId = plan?.id,
            )
        }
        if (result.isError && registry.definition(call.name)?.effect == SmeditToolEffect.MUTATION) {
            val error = result.output["error"] as? JsonObject
            val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull
            val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
                ?: "The mutation request failed validation."
            addMutationNotice(
                planId = null,
                text = if (code.isNullOrBlank()) message else "$message (error: $code)",
                isError = true,
                title = "SMEDIT rejected ${call.name}",
            deduplicateConsecutive = true,
            )
        }
        return result
    }

    private companion object {
        const val MUTATION_ACTION_FEEDBACK_DELAY_MS = 32L
        const val ITEM_CHAT = "chat"
        const val ITEM_PLAN = "plan"
        const val ITEM_NOTICE = "notice"
    }
}

@Composable
fun AiSettingsTab(state: AiWorkspaceState, fontSize: FontSize) {
    var apiKey by remember { mutableStateOf("") }
    var editingStoredKey by remember { mutableStateOf(false) }
    var actionInFlight by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val provider = AiProviderId.OPENAI
    val credentialMode = state.credentialMode(provider)
    val source = state.credentialSource(provider)
    val environmentAvailable = state.hasCredential(provider, AiCredentialSource.ENVIRONMENT)
    val storedPreview = state.credentialPreview(provider, AiCredentialSource.SMEDIT_STORE)
    val activePreview = state.credentialPreview(provider, credentialMode)

    Text(
        "OpenAI",
        fontSize = fontSize.body,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        when (source) {
            AiCredentialSource.ENVIRONMENT -> "Connected through ${provider.environmentVariable}."
            AiCredentialSource.SMEDIT_STORE -> "Key saved globally in ~/.smedit/credentials.json."
            null -> "No API key configured."
        },
        fontSize = fontSize.detail,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Authentication", fontSize = fontSize.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                apiKey = ""
                editingStoredKey = false
                state.selectCredentialSource(provider, AiCredentialSource.ENVIRONMENT)
            },
            enabled = environmentAvailable && !state.modelsLoading && !state.interactionInFlight,
            shape = AI_CONTROL_SHAPE,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (credentialMode == AiCredentialSource.ENVIRONMENT) {
                    MaterialTheme.colorScheme.primaryContainer
                } else MaterialTheme.colorScheme.surface,
            ),
        ) { Text("Environment", fontSize = fontSize.body) }
        OutlinedButton(
            onClick = {
                apiKey = ""
                editingStoredKey = false
                state.selectCredentialSource(provider, AiCredentialSource.SMEDIT_STORE)
            },
            enabled = !state.modelsLoading && !state.interactionInFlight,
            shape = AI_CONTROL_SHAPE,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (credentialMode == AiCredentialSource.SMEDIT_STORE) {
                    MaterialTheme.colorScheme.primaryContainer
                } else MaterialTheme.colorScheme.surface,
            ),
        ) { Text("Open API Key", fontSize = fontSize.body) }
    }
    OutlinedTextField(
        value = when (credentialMode) {
            AiCredentialSource.ENVIRONMENT -> activePreview.orEmpty()
            AiCredentialSource.SMEDIT_STORE -> if (editingStoredKey) apiKey else storedPreview.orEmpty()
        },
        onValueChange = { value ->
            if (credentialMode == AiCredentialSource.SMEDIT_STORE) {
                if (!editingStoredKey) editingStoredKey = true
                apiKey = value
            }
        },
        modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
            if (credentialMode == AiCredentialSource.SMEDIT_STORE) {
                if (focus.isFocused && !editingStoredKey) {
                    editingStoredKey = true
                    apiKey = ""
                } else if (!focus.isFocused && apiKey.isBlank()) {
                    editingStoredKey = false
                }
            }
        },
        label = {
            Text(
                when (credentialMode) {
                    AiCredentialSource.ENVIRONMENT -> provider.environmentVariable
                    AiCredentialSource.SMEDIT_STORE -> if (storedPreview == null) "OpenAI API key" else "Replace OpenAI API key"
                },
            )
        },
        placeholder = {
            Text(
                if (credentialMode == AiCredentialSource.ENVIRONMENT) "Environment variable is not available"
                else "Paste a key to save with owner-only permissions",
            )
        },
        readOnly = credentialMode == AiCredentialSource.ENVIRONMENT,
        enabled = !actionInFlight,
        singleLine = true,
        visualTransformation = if (
            credentialMode == AiCredentialSource.SMEDIT_STORE && editingStoredKey
        ) PasswordVisualTransformation() else VisualTransformation.None,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                actionInFlight = true
                scope.launch {
                    try {
                        if (state.saveCredential(provider, apiKey)) {
                            apiKey = ""
                            editingStoredKey = false
                        }
                    } finally {
                        actionInFlight = false
                    }
                }
            },
            enabled = credentialMode == AiCredentialSource.SMEDIT_STORE && editingStoredKey &&
                apiKey.isNotBlank() && !actionInFlight && !state.modelsLoading && !state.interactionInFlight,
            shape = AI_CONTROL_SHAPE,
        ) {
            Text(if (storedPreview == null) "Save & connect" else "Replace & connect", fontSize = fontSize.body)
        }
        if (credentialMode == AiCredentialSource.SMEDIT_STORE && storedPreview != null) {
            TextButton(
                onClick = { state.deleteCredential(provider) },
                enabled = !state.modelsLoading && !state.interactionInFlight,
                shape = AI_CONTROL_SHAPE,
            ) { Text("Remove", fontSize = fontSize.body) }
        }
    }
    if (actionInFlight || state.modelsLoading) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Contacting OpenAI…", fontSize = fontSize.detail)
        }
    }
    state.errorMessage?.let {
        Text(it, color = MaterialTheme.colorScheme.error, fontSize = fontSize.detail)
    }
    if (state.models.isNotEmpty()) {
        Text(
            "Model (${state.models.size} returned by OpenAI)",
            fontSize = fontSize.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AiModelPicker(state)
    }
    Text(
        "Claude provider support comes next; its models will also be loaded from the provider rather than hard-coded.",
        fontSize = fontSize.detail,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun AiWorkspaceCanvas(
    state: AiWorkspaceState,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    showHeader: Boolean = true,
    smartLinkRoomIds: Set<Int> = emptySet(),
    asmSmartLinksEnabled: Boolean = false,
    onSmartLink: (AiSmartLinkTarget) -> Unit = {},
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val actionScope = rememberCoroutineScope()
    val scrollScope = rememberCoroutineScope()
    val conversationItems = state.conversationItems
    var sendJob by remember { mutableStateOf<Job?>(null) }
    var conversationHeightPx by remember { mutableStateOf(0) }
    val lazyItemCount = conversationItems.size +
        (if (conversationItems.isEmpty()) 1 else 0) +
        (if (state.requestInFlight) 1 else 0)

    LaunchedEffect(
        conversationItems.lastOrNull()?.sequence,
        state.requestInFlight,
    ) {
        listState.scrollConversationToBottom(lazyItemCount, conversationHeightPx)
    }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("SMEDIT AI", fontSize = fs.heading, fontWeight = FontWeight.Bold)
                AiModelPicker(state)
                if (state.modelsLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.weight(1f))
            }
        }
        AiConversationTabs(state)
        if (state.historyVisible) {
            AiConversationHistoryPanel(state, Modifier.weight(1f).fillMaxWidth())
        } else {
            state.errorMessage?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(6.dp)) {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = fs.detail,
                )
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged {
                conversationHeightPx = it.height
                scrollScope.launch {
                    try {
                        listState.scrollConversationToBottom(lazyItemCount, it.height)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Scrolling is best-effort and must never disable chat or approval actions.
                    }
                }
            }
                .testTag("ai-conversation"),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("ai-message-list"),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                    if (conversationItems.isEmpty()) {
                        item {
                            Text(
                                "Ask about the project, rooms, patches, palettes, items, settings, or loaded ASM source. You can also request music, FX, patch, palette, and item changes; SMEDIT will preview the exact scope for approval.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = fs.body,
                            )
                        }
                    }
                    items(conversationItems, key = { it.sequence }) { item ->
                        when (item) {
                            is AiChatMessage -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (item.role == AiChatRole.USER) Arrangement.End else Arrangement.Start,
                                ) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(0.86f),
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (item.role == AiChatRole.USER) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        SelectionContainer {
                                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    if (item.role == AiChatRole.USER) "You" else state.selectedProvider.displayName,
                                                    fontSize = fs.detail,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                                if (item.role == AiChatRole.ASSISTANT) {
                                                    AiSmartText(
                                                        item.text,
                                                        smartLinkRoomIds,
                                                        asmSmartLinksEnabled,
                                                        onSmartLink,
                                                        fontSize = fs.body,
                                                    )
                                                } else {
                                                    Text(item.text, fontSize = fs.body)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            is AiMutationPlanMessage -> AiMutationApprovalCard(
                                state = state,
                                message = item,
                                onApprove = { actionScope.launch { state.approvePendingMutation() } },
                                onReject = { actionScope.launch { state.rejectPendingMutation() } },
                                smartLinkRoomIds = smartLinkRoomIds,
                                asmSmartLinksEnabled = asmSmartLinksEnabled,
                                onSmartLink = onSmartLink,
                            )
                            is AiMutationNotice -> AiMutationNoticeCard(
                                item,
                                smartLinkRoomIds,
                                asmSmartLinksEnabled,
                                onSmartLink,
                            )
                        }
                    }
                    if (state.requestInFlight) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("Thinking…", fontSize = fs.detail)
                            }
                        }
                    }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag("ai-composer"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.promptDraft,
                onValueChange = { state.promptDraft = it },
                modifier = Modifier.weight(1f).heightIn(min = 96.dp, max = 240.dp).testTag("ai-prompt"),
                textStyle = LocalTextStyle.current.copy(fontSize = fs.body),
                placeholder = { Text("Ask SMEDIT AI…", fontSize = fs.body) },
                enabled = !state.interactionInFlight,
                minLines = 3,
                maxLines = 10,
            )
            if (state.requestInFlight) {
                OutlinedButton(
                    onClick = {
                        sendJob?.cancel()
                        sendJob = null
                        state.requestCancelled()
                    },
                    shape = AI_CONTROL_SHAPE,
                ) { Text("Cancel", fontSize = fs.body) }
            } else {
                Button(
                    onClick = {
                        val message = state.promptDraft
                        state.promptDraft = ""
                        sendJob = actionScope.launch { state.send(message) }
                    },
                    enabled = state.promptDraft.isNotBlank() && state.selectedModelId != null && !state.interactionInFlight,
                    shape = AI_CONTROL_SHAPE,
                ) { Text("Send", fontSize = fs.body) }
            }
        }
        }
    }
}

@Composable
private fun AiConversationTabs(state: AiWorkspaceState) {
    val fs = LocalEditorTheme.current.fontSize.value
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("ai-conversation-tabs"),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val historySelected = state.historyVisible
        Surface(
            color = if (historySelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            shape = AI_CONTROL_SHAPE,
            modifier = Modifier.clickable(enabled = !historySelected) {
                state.showConversationHistory()
            }.testTag("ai-history-tab"),
        ) {
            Text(
                "History",
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                fontSize = fs.detail,
                fontWeight = if (historySelected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        state.conversationHistory.forEach { conversation ->
            val selected = !historySelected && conversation.id == state.activeConversationId
            Surface(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = AI_CONTROL_SHAPE,
                modifier = Modifier
                    .widthIn(max = 190.dp)
                    .clickable(enabled = !state.interactionInFlight && !selected) {
                        state.selectConversation(conversation.id)
                    }
                    .testTag("ai-conversation-tab-${conversation.id}"),
            ) {
                Text(
                    conversation.title,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    fontSize = fs.detail,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AiConversationHistoryPanel(
    state: AiWorkspaceState,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    LazyColumn(
        modifier = modifier.testTag("ai-history-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Conversation history", fontSize = fs.body, fontWeight = FontWeight.Bold)
            Text(
                "Rename a conversation to use that name on its tab. Clear a custom name to return to its first prompt.",
                fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.conversationHistory, key = { it.id }) { conversation ->
            var renameDraft by remember(conversation.id, conversation.customTitle, conversation.title) {
                mutableStateOf(conversation.customTitle ?: conversation.title)
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    OutlinedTextField(
                        value = renameDraft,
                        onValueChange = {
                            renameDraft = it.take(AiConversationStore.MAX_CONVERSATION_TITLE_LENGTH)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("ai-history-name-${conversation.id}"),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = fs.body),
                        label = { Text("Conversation name", fontSize = fs.detail) },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildString {
                                append(conversation.itemCount)
                                append(if (conversation.itemCount == 1) " message" else " messages")
                                conversation.modelId?.let { append(" · "); append(it) }
                            },
                            modifier = Modifier.weight(1f),
                            fontSize = fs.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (conversation.customTitle != null) {
                            TextButton(
                                onClick = {
                                    state.renameConversation(conversation.id, "")
                                },
                                shape = AI_CONTROL_SHAPE,
                            ) { Text("Reset name", fontSize = fs.detail) }
                        }
                        Button(
                            onClick = { state.renameConversation(conversation.id, renameDraft) },
                            enabled = renameDraft.trim().isNotEmpty() &&
                                renameDraft.trim() != conversation.customTitle,
                            shape = AI_CONTROL_SHAPE,
                        ) { Text("Save name", fontSize = fs.detail) }
                        OutlinedButton(
                            onClick = { state.selectConversation(conversation.id) },
                            enabled = !state.interactionInFlight,
                            shape = AI_CONTROL_SHAPE,
                        ) { Text("Open", fontSize = fs.detail) }
                    }
                }
            }
        }
    }
}

private suspend fun LazyListState.scrollConversationToBottom(expectedItemCount: Int, expectedViewportHeight: Int) {
    if (expectedItemCount <= 0) return
    if (expectedViewportHeight > 0 && layoutInfo.viewportSize.height != expectedViewportHeight) {
        snapshotFlow { layoutInfo.viewportSize.height }.first { it == expectedViewportHeight }
    }
    if (layoutInfo.totalItemsCount < expectedItemCount) {
        snapshotFlow { layoutInfo.totalItemsCount }.first { it >= expectedItemCount }
    }
    val lastIndex = expectedItemCount - 1
    scrollToItem(lastIndex)
    val layout = layoutInfo
    val lastItem = layout.visibleItemsInfo.firstOrNull { it.index == lastIndex } ?: return
    val hiddenBottom = lastItem.offset + lastItem.size - layout.viewportEndOffset
    if (hiddenBottom > 0) scrollBy(hiddenBottom.toFloat())
}

@Composable
private fun AiMutationApprovalCard(
    state: AiWorkspaceState,
    message: AiMutationPlanMessage,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    smartLinkRoomIds: Set<Int>,
    asmSmartLinksEnabled: Boolean,
    onSmartLink: (AiSmartLinkTarget) -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val plan = message.plan
    val awaitingApproval = message.status == AiToolActivityStatus.AWAITING_APPROVAL &&
        state.pendingMutation?.id == plan.id
    val mutationAction = state.mutationActionInFlight.takeIf { awaitingApproval }
    val statusLabel = when (mutationAction) {
        AiMutationAction.APPLYING -> "Applying approved plan…"
        AiMutationAction.REJECTING -> "Rejecting plan…"
        null -> when (message.status) {
            AiToolActivityStatus.AWAITING_APPROVAL -> "Approval required"
            AiToolActivityStatus.APPLIED -> "Approved plan"
            AiToolActivityStatus.REJECTED -> "Rejected plan"
            AiToolActivityStatus.UNDONE -> "Undone plan"
            AiToolActivityStatus.REDONE -> "Redone plan"
            AiToolActivityStatus.RUNNING -> "Preparing plan"
            AiToolActivityStatus.SUCCEEDED -> "Completed plan"
            AiToolActivityStatus.FAILED -> "Failed plan"
        }
    }
    Surface(
        color = if (awaitingApproval) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
    ) {
        SelectionContainer {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(statusLabel, fontSize = fs.body, fontWeight = FontWeight.Bold)
            AiSmartText(
                plan.title,
                smartLinkRoomIds,
                asmSmartLinksEnabled,
                onSmartLink,
                fontSize = fs.body,
            )
            when (plan.kind) {
                SmeditMutationPlanKind.ROOM_MUSIC -> {
                    Text(
                        "Music data 0x${plan.musicData.toString(16).uppercase().padStart(2, '0')} · " +
                            "track 0x${plan.musicTrack.toString(16).uppercase().padStart(2, '0')}",
                        fontSize = fs.detail,
                    )
                    Text(
                        "${plan.affectedRoomCount} room(s) · ${plan.affectedStateCount} current state(s)",
                        fontSize = fs.detail,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (plan.affectedRoomPreview.isNotEmpty()) {
                        AiSmartText(
                            plan.affectedRoomPreview.joinToString() +
                                if (plan.affectedRoomCount > plan.affectedRoomPreview.size) " …" else "",
                            smartLinkRoomIds,
                            asmSmartLinksEnabled,
                            onSmartLink,
                            fontSize = fs.detail,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 3,
                        )
                    }
                    if (plan.excludedAreas.isNotEmpty()) {
                        Text("Excluded areas: ${plan.excludedAreas.joinToString()}", fontSize = fs.detail)
                    }
                    if (plan.excludedRoomIds.isNotEmpty()) {
                        AiSmartText(
                            "Excluded rooms: ${plan.excludedRoomIds.joinToString()}",
                            smartLinkRoomIds,
                            asmSmartLinksEnabled,
                            onSmartLink,
                            fontSize = fs.detail,
                        )
                    }
                    if (plan.protectedRoomIds.isNotEmpty()) {
                        AiSmartText(
                            "Protected music-sensitive rooms: " +
                                plan.protectedRoomPreview.joinToString() +
                                if (plan.protectedRoomIds.size > plan.protectedRoomPreview.size) " …" else "",
                            smartLinkRoomIds,
                            asmSmartLinksEnabled,
                            onSmartLink,
                            fontSize = fs.detail,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                SmeditMutationPlanKind.ROOM_STATE_FIELDS -> plan.roomStateFields?.let { fields ->
                    AiSmartText(
                        "${fields.roomName} (${fields.roomId}) · ${fields.stateId}",
                        smartLinkRoomIds,
                        asmSmartLinksEnabled,
                        onSmartLink,
                        fontSize = fs.detail,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(fields.stateCondition, fontSize = fs.detail)
                    fields.changes.forEach { change ->
                        fun formatted(value: Int): String = when (change.field) {
                            "music_data", "music_track" -> "0x${value.toString(16).uppercase().padStart(2, '0')}"
                            "bg_scrolling" -> "0x${value.toString(16).uppercase().padStart(4, '0')}"
                            else -> value.toString()
                        }
                        Text(
                            "${change.label}: ${formatted(change.before)} → ${formatted(change.after)}",
                            fontSize = fs.detail,
                        )
                    }
                    Text(
                        if (fields.sourceManaged) "Writes project ASM room-property source" else "Writes the project room model",
                        fontSize = fs.detail,
                    )
                }
                SmeditMutationPlanKind.PATCH -> plan.patch?.let { patch ->
                    Text(
                        "${patch.patchName} · ${if (patch.beforeEnabled) "enabled" else "disabled"} → " +
                            if (patch.afterEnabled) "enabled" else "disabled",
                        fontSize = fs.detail,
                        fontWeight = FontWeight.SemiBold,
                    )
                    patch.configType?.let { Text("Config type: $it", fontSize = fs.detail) }
                    patch.valueChanges.forEach { change ->
                        Text(
                            "${change.label}: ${change.before?.toString() ?: "unset"} → ${change.after?.toString() ?: "unset"}",
                            fontSize = fs.detail,
                        )
                    }
                    if (patch.automaticallyDisabledPatches.isNotEmpty()) {
                        Text(
                            "Also disables: ${patch.automaticallyDisabledPatches.joinToString()}",
                            fontSize = fs.detail,
                        )
                    }
                }
                SmeditMutationPlanKind.ROOM_FX -> plan.fx?.let { fx ->
                    Text(
                        "${plan.affectedRoomCount} room(s) · ${plan.affectedStateCount} state(s)",
                        fontSize = fs.detail,
                        fontWeight = FontWeight.SemiBold,
                    )
                    fx.requestedChanges.forEach { change -> Text(change, fontSize = fs.detail) }
                    if (fx.affectedStatePreview.isNotEmpty()) {
                        AiSmartText(
                            fx.affectedStatePreview.joinToString() +
                                if (plan.affectedStateCount > fx.affectedStatePreview.size) " …" else "",
                            smartLinkRoomIds,
                            asmSmartLinksEnabled,
                            onSmartLink,
                            fontSize = fs.detail,
                            maxLines = 4,
                        )
                    }
                    if (plan.protectedRoomIds.isNotEmpty()) {
                        AiSmartText(
                            "Protected rooms: ${plan.protectedRoomPreview.joinToString()}" +
                                if (plan.protectedRoomIds.size > plan.protectedRoomPreview.size) " …" else "",
                            smartLinkRoomIds,
                            asmSmartLinksEnabled,
                            onSmartLink,
                            fontSize = fs.detail,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                SmeditMutationPlanKind.PALETTE -> plan.palette?.let { palette ->
                    val targets = listOf(
                        SmeditPaletteTargetMutationPlan(
                            palette.paletteId,
                            palette.paletteName,
                            palette.paletteKind,
                            palette.sourceManaged,
                            palette.affectedPaletteIds,
                            palette.colorChanges,
                        ),
                    ) + palette.additionalTargets
                    targets.forEach { target ->
                        Text(
                            "${target.paletteName} · ${target.paletteKind}" +
                                if (target.sourceManaged) " · ASM source" else " · project override",
                            fontSize = fs.detail,
                            fontWeight = FontWeight.SemiBold,
                        )
                        target.colorChanges.take(16).forEach { change ->
                            Text(
                                "[${change.row},${change.column}] ${change.beforeRgb} → ${change.afterRgb}",
                                fontSize = fs.detail,
                            )
                        }
                        if (target.colorChanges.size > 16) {
                            Text("…and ${target.colorChanges.size - 16} more color(s)", fontSize = fs.detail)
                        }
                        if (target.affectedPaletteIds.isNotEmpty()) {
                            Text("Shared with: ${target.affectedPaletteIds.joinToString()}", fontSize = fs.detail)
                        }
                    }
                }
                SmeditMutationPlanKind.ITEM -> plan.item?.let { item ->
                    AiSmartText(
                        "${item.roomName} (${item.roomId}) · ${item.itemName} · ${item.plmIdHex} · tile (${item.x}, ${item.y})",
                        smartLinkRoomIds,
                        asmSmartLinksEnabled,
                        onSmartLink,
                        fontSize = fs.detail,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Parameter ${item.parameterHex} · " +
                            if (item.stateScope == "all_states") "all ${item.stateIds.size} state(s)"
                            else "state ${item.stateIds.singleOrNull() ?: item.stateIds.joinToString()}",
                        fontSize = fs.detail,
                    )
                    Text(
                        if (item.sourceManaged) "Writes project ASM object source" else "Writes the project room model",
                        fontSize = fs.detail,
                    )
                }
                SmeditMutationPlanKind.ROOM_TILES -> plan.roomTiles?.let { tiles ->
                    AiSmartText(
                        "${tiles.roomName} (${tiles.roomId}) · ${tiles.stateIds.size} state(s) · ${tiles.changes.size} previewed change(s)",
                        smartLinkRoomIds,
                        asmSmartLinksEnabled,
                        onSmartLink,
                        fontSize = fs.detail,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (tiles.stateScope == "all_states") "Targets all room states" else "Targets state ${tiles.stateIds.joinToString()}",
                        fontSize = fs.detail,
                    )
                    if (tiles.forkedStateIds.isNotEmpty()) {
                        Text(
                            "Creates an independent layout for: ${tiles.forkedStateIds.joinToString()}",
                            fontSize = fs.detail,
                        )
                    }
                    tiles.changes.take(20).forEach { change ->
                        Text(
                            "${change.stateId} · L${change.layer} (${change.x},${change.y}) " +
                                "${change.beforeWordHex} → ${change.afterWordHex}" +
                                if (change.beforeBtsHex != null && change.beforeBtsHex != change.afterBtsHex) {
                                    " · BTS ${change.beforeBtsHex} → ${change.afterBtsHex}"
                                } else "",
                            fontSize = fs.detail,
                        )
                    }
                    if (tiles.changes.size > 20) {
                        Text("…and ${tiles.changes.size - 20} more previewed change(s)", fontSize = fs.detail)
                    }
                }
            }
            plan.warnings.forEach { warning ->
                AiSmartText(
                    "Warning: $warning",
                    smartLinkRoomIds,
                    asmSmartLinksEnabled,
                    onSmartLink,
                    fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (awaitingApproval) {
                Text(
                    "Approve applies this frozen plan atomically. Newer overlapping edits block approval.",
                    fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                DisableSelection {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onApprove,
                            enabled = !state.requestInFlight && mutationAction == null,
                            shape = AI_CONTROL_SHAPE,
                        ) {
                            if (mutationAction == AiMutationAction.APPLYING) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(6.dp))
                            }
                            Text(if (mutationAction == AiMutationAction.APPLYING) "Applying…" else "Approve", fontSize = fs.body)
                        }
                        OutlinedButton(
                            onClick = onReject,
                            enabled = !state.requestInFlight && mutationAction == null,
                            shape = AI_CONTROL_SHAPE,
                        ) {
                            if (mutationAction == AiMutationAction.REJECTING) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(6.dp))
                            }
                            Text(if (mutationAction == AiMutationAction.REJECTING) "Rejecting…" else "Reject", fontSize = fs.body)
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun AiMutationNoticeCard(
    notice: AiMutationNotice,
    smartLinkRoomIds: Set<Int>,
    asmSmartLinksEnabled: Boolean,
    onSmartLink: (AiSmartLinkTarget) -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(
        color = if (notice.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        SelectionContainer {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    notice.title ?: if (notice.isError) "SMEDIT action blocked" else "SMEDIT",
                    fontSize = fs.detail,
                    fontWeight = FontWeight.Bold,
                    color = if (notice.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                )
                AiSmartText(
                    notice.text,
                    smartLinkRoomIds,
                    asmSmartLinksEnabled,
                    onSmartLink,
                    fontSize = fs.detail,
                    color = if (notice.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

private fun formatRequestTimeout(timeoutMillis: Long): String = when {
    timeoutMillis % 60_000L == 0L -> {
        val minutes = timeoutMillis / 60_000L
        "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
    }
    timeoutMillis % 1_000L == 0L -> "${timeoutMillis / 1_000L} seconds"
    else -> "$timeoutMillis ms"
}

private const val DEFAULT_AI_REQUEST_TIMEOUT_MILLIS = 4 * 60 * 1_000L

@Composable
internal fun AiCompactModelPicker(state: AiWorkspaceState) {
    val fs = LocalEditorTheme.current.fontSize.value
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = state.models.isNotEmpty() && !state.modelsLoading && !state.interactionInFlight,
            modifier = Modifier.widthIn(max = 190.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp),
            shape = AI_CONTROL_SHAPE,
        ) {
            Text(
                state.selectedModelId ?: if (state.modelsLoading) "Loading models…" else "Select model",
                fontSize = fs.detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.id, fontSize = fs.body) },
                    onClick = {
                        state.selectModel(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AiModelPicker(state: AiWorkspaceState) {
    val fs = LocalEditorTheme.current.fontSize.value
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = state.models.isNotEmpty() && !state.modelsLoading && !state.interactionInFlight,
            shape = AI_CONTROL_SHAPE,
        ) {
            Text(
                state.selectedModelId ?: if (state.modelsLoading) "Loading models…" else "Select model",
                fontSize = fs.body,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.id, fontSize = fs.body) },
                    onClick = {
                        state.selectModel(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

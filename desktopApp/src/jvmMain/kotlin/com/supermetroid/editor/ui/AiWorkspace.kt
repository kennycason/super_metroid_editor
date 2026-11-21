package com.supermetroid.editor.ui

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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.supermetroid.editor.api.SmeditToolRegistry
import com.supermetroid.editor.api.SmeditToolEffect
import com.supermetroid.editor.api.SmeditToolResult
import com.supermetroid.editor.api.SmeditMutationController
import com.supermetroid.editor.api.SmeditMutationPlanKind
import com.supermetroid.editor.api.SmeditMutationPlan
import com.supermetroid.editor.api.SmeditPatchMutationPlan
import com.supermetroid.editor.api.SmeditPatchValueChange
import com.supermetroid.editor.data.AppConfig
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
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
) : AiConversationItem

enum class AiToolActivityStatus { RUNNING, SUCCEEDED, FAILED, AWAITING_APPROVAL, APPLIED, REJECTED, UNDONE, REDONE }

enum class AiMutationAction { APPLYING, REJECTING }

data class AiToolActivity(
    val callId: String,
    val name: String,
    val arguments: String,
    val status: AiToolActivityStatus,
    val outputPreview: String? = null,
    val planId: String? = null,
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

    private var previousResponseId: String? = null
    private var nextConversationSequence = 0L
    private var conversationId = UUID.randomUUID().toString()
    private var conversationModelId: String? = null

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
            conversationModelId = AppConfig.load().aiSelectedModels[provider.configId]
            return
        }
        conversationId = stored.id
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
                    mutationNotices += AiMutationNotice(item.planId, text, item.isError, item.sequence)
                }
            }
        }
        val normalizedLegacyPlanOrdering = normalizeLegacyPlanOrdering()
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
        if (expiredPlanIds.isNotEmpty() || normalizedLegacyPlanOrdering) persistConversation()
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
    }

    private fun persistConversation() {
        val snapshot = StoredAiConversation(
            id = conversationId,
            providerId = selectedProvider.configId,
            modelId = conversationModelId ?: selectedModelId,
            previousResponseId = previousResponseId,
            updatedAtEpochMillis = System.currentTimeMillis(),
            nextSequence = nextConversationSequence,
            items = conversationItems.map { it.toStored() },
        )
        runCatching { conversationStore.save(snapshot) }.onFailure { problem ->
            errorMessage = "Could not save AI conversation history: ${problem.message ?: "unknown file error"}"
        }
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
            val reply = provider.createResponse(
                apiKey = credential.value,
                modelId = modelId,
                prompt = normalized,
                previousResponseId = previousResponseId,
                toolDefinitions = toolRegistry?.definitions.orEmpty(),
                executeTool = toolRegistry?.let { registry ->
                    { call -> executeToolCall(registry, call) }
                },
            )
            previousResponseId = reply.responseId
            commitAssistantReply(reply.text, plansCreatedAfterSequence)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "The AI request failed."
        } finally {
            requestInFlight = false
        }
    }

    fun requestCancelled() {
        requestInFlight = false
        errorMessage = "Request cancelled."
    }

    fun newConversation() {
        abandonPendingPlan("The unapproved plan was closed when a new conversation started. No project data changed.")
        clearConversationMemory()
        conversationId = UUID.randomUUID().toString()
        conversationModelId = selectedModelId
        errorMessage = null
        persistConversation()
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

    private fun addMutationNotice(planId: String?, text: String, isError: Boolean = false) {
        mutationNotices += AiMutationNotice(planId, text, isError, nextSequence())
        persistConversation()
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
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (credentialMode == AiCredentialSource.ENVIRONMENT) {
                    MaterialTheme.colorScheme.primaryContainer
                } else MaterialTheme.colorScheme.surface,
            ),
        ) { Text("Environment") }
        OutlinedButton(
            onClick = {
                apiKey = ""
                editingStoredKey = false
                state.selectCredentialSource(provider, AiCredentialSource.SMEDIT_STORE)
            },
            enabled = !state.modelsLoading && !state.interactionInFlight,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (credentialMode == AiCredentialSource.SMEDIT_STORE) {
                    MaterialTheme.colorScheme.primaryContainer
                } else MaterialTheme.colorScheme.surface,
            ),
        ) { Text("Open API Key") }
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
        ) {
            Text(if (storedPreview == null) "Save & connect" else "Replace & connect")
        }
        if (credentialMode == AiCredentialSource.SMEDIT_STORE && storedPreview != null) {
            TextButton(
                onClick = { state.deleteCredential(provider) },
                enabled = !state.modelsLoading && !state.interactionInFlight,
            ) { Text("Remove") }
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
fun AiWorkspaceSidebar(state: AiWorkspaceState, modifier: Modifier = Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("AI Assistant", fontSize = fs.heading, fontWeight = FontWeight.Bold)
        Text(
            "Approval-backed workspace",
            fontSize = fs.detail,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Workspace reads are logged. Room-music and patch writes show an exact plan and cannot run until you approve them.",
            fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Conversation history is saved globally in ~/.smedit/ai/conversations/.",
            fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Provider", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state.selectedProvider.displayName, fontSize = fs.body, fontWeight = FontWeight.SemiBold)
        Text("Model", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state.selectedModelId ?: "Not selected", fontSize = fs.body)
        OutlinedButton(
            onClick = state::newConversation,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.interactionInFlight,
        ) {
            Text("New conversation")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = state::undoAiMutation,
                enabled = state.canUndoAiMutation && state.pendingMutation == null && !state.interactionInFlight,
            ) { Text("AI Undo") }
            OutlinedButton(
                onClick = state::redoAiMutation,
                enabled = state.canRedoAiMutation && state.pendingMutation == null && !state.interactionInFlight,
            ) { Text("AI Redo") }
        }
        if (state.toolActivity.isNotEmpty()) {
            Text("Tool activity", fontSize = fs.body, fontWeight = FontWeight.SemiBold)
            state.toolActivity.takeLast(6).forEach { activity ->
                val marker = when (activity.status) {
                    AiToolActivityStatus.RUNNING -> "…"
                    AiToolActivityStatus.SUCCEEDED -> "✓"
                    AiToolActivityStatus.FAILED -> "!"
                    AiToolActivityStatus.AWAITING_APPROVAL -> "?"
                    AiToolActivityStatus.APPLIED -> "✓"
                    AiToolActivityStatus.REJECTED -> "×"
                    AiToolActivityStatus.UNDONE -> "↶"
                    AiToolActivityStatus.REDONE -> "↷"
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("$marker ${activity.name}", fontSize = fs.detail, fontWeight = FontWeight.Medium)
                    Text(
                        activity.arguments,
                        fontSize = fs.detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
fun AiWorkspaceCanvas(
    state: AiWorkspaceState,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
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
                                "Ask about the current project, rooms, patches, settings, or loaded ASM source. You can also ask to change room music or configure patches; SMEDIT will preview the exact scope for approval.",
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
                                                Text(item.text, fontSize = fs.body)
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
                            )
                            is AiMutationNotice -> AiMutationNoticeCard(item)
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
                placeholder = { Text("Ask SMEDIT AI…") },
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
                    shape = RoundedCornerShape(6.dp),
                ) { Text("Cancel") }
            } else {
                Button(
                    onClick = {
                        val message = state.promptDraft
                        state.promptDraft = ""
                        sendJob = actionScope.launch { state.send(message) }
                    },
                    enabled = state.promptDraft.isNotBlank() && state.selectedModelId != null && !state.interactionInFlight,
                    shape = RoundedCornerShape(6.dp),
                ) { Text("Send") }
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
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(statusLabel, fontSize = fs.body, fontWeight = FontWeight.Bold)
            Text(plan.title, fontSize = fs.body)
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
                        Text(
                            plan.affectedRoomPreview.joinToString() +
                                if (plan.affectedRoomCount > plan.affectedRoomPreview.size) " …" else "",
                            fontSize = fs.detail,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 3,
                        )
                    }
                    if (plan.excludedAreas.isNotEmpty()) {
                        Text("Excluded areas: ${plan.excludedAreas.joinToString()}", fontSize = fs.detail)
                    }
                    if (plan.excludedRoomIds.isNotEmpty()) {
                        Text("Excluded rooms: ${plan.excludedRoomIds.joinToString()}", fontSize = fs.detail)
                    }
                    if (plan.protectedRoomIds.isNotEmpty()) {
                        Text(
                            "Protected state-sensitive rooms: " +
                                plan.protectedRoomPreview.joinToString() +
                                if (plan.protectedRoomIds.size > plan.protectedRoomPreview.size) " …" else "",
                            fontSize = fs.detail,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
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
            }
            plan.warnings.forEach { warning ->
                Text("Warning: $warning", fontSize = fs.detail, color = MaterialTheme.colorScheme.error)
            }
            if (awaitingApproval) {
                Text(
                    "Approve applies this frozen plan atomically. Newer music edits block approval.",
                    fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onApprove,
                        enabled = !state.requestInFlight && mutationAction == null,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        if (mutationAction == AiMutationAction.APPLYING) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(if (mutationAction == AiMutationAction.APPLYING) "Applying…" else "Approve")
                    }
                    OutlinedButton(
                        onClick = onReject,
                        enabled = !state.requestInFlight && mutationAction == null,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        if (mutationAction == AiMutationAction.REJECTING) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(if (mutationAction == AiMutationAction.REJECTING) "Rejecting…" else "Reject")
                    }
                }
            }
        }
    }
}

@Composable
private fun AiMutationNoticeCard(notice: AiMutationNotice) {
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(
        color = if (notice.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                if (notice.isError) "SMEDIT action blocked" else "SMEDIT",
                fontSize = fs.detail,
                fontWeight = FontWeight.Bold,
                color = if (notice.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                notice.text,
                fontSize = fs.detail,
                color = if (notice.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun AiModelPicker(state: AiWorkspaceState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = state.models.isNotEmpty() && !state.modelsLoading && !state.interactionInFlight,
        ) {
            Text(state.selectedModelId ?: if (state.modelsLoading) "Loading models…" else "Select model")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.id) },
                    onClick = {
                        state.selectModel(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

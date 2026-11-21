package com.supermetroid.editor.ai

import com.supermetroid.editor.api.SmeditToolDefinition
import com.supermetroid.editor.api.SmeditToolResult
import kotlinx.serialization.json.JsonObject

data class AiModel(
    val id: String,
    val createdAtEpochSeconds: Long? = null,
    val ownedBy: String? = null,
)

data class AiChatReply(
    val responseId: String,
    val text: String,
)

data class AiToolCall(
    val callId: String,
    val name: String,
    val arguments: JsonObject,
    val rawArguments: String,
    val argumentError: String? = null,
)

internal fun retainedProviderModelId(savedModelId: String?, models: List<AiModel>): String? =
    savedModelId?.takeIf { saved -> models.any { it.id == saved } }

interface AiProvider {
    val id: AiProviderId

    suspend fun listModels(apiKey: String): List<AiModel>

    suspend fun createResponse(
        apiKey: String,
        modelId: String,
        prompt: String,
        previousResponseId: String? = null,
        toolDefinitions: List<SmeditToolDefinition> = emptyList(),
        executeTool: (suspend (AiToolCall) -> SmeditToolResult)? = null,
    ): AiChatReply
}

class AiProviderException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)


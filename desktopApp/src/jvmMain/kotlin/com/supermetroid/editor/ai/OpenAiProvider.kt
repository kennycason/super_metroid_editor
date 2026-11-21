package com.supermetroid.editor.ai

import com.supermetroid.editor.api.SmeditToolDefinition
import com.supermetroid.editor.api.SmeditToolResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal data class AiHttpResponse(val statusCode: Int, val body: String)

internal fun interface AiHttpTransport {
    suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): AiHttpResponse
}

internal class JdkAiHttpTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : AiHttpTransport {
    override suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): AiHttpResponse = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90))
        headers.forEach(builder::header)
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody())
        else builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        AiHttpResponse(response.statusCode(), response.body())
    }
}

class OpenAiProvider internal constructor(
    private val transport: AiHttpTransport,
    private val baseUrl: String,
) : AiProvider {
    constructor() : this(JdkAiHttpTransport(), "https://api.openai.com/v1")

    override val id: AiProviderId = AiProviderId.OPENAI
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listModels(apiKey: String): List<AiModel> {
        val response = transport.send("GET", "$baseUrl/models", headers(apiKey), null)
        val root = parseSuccessful(response, apiKey)
        return (root["data"] as? JsonArray).orEmpty()
            .mapNotNull { element ->
                val model = element as? JsonObject ?: return@mapNotNull null
                val modelId = model["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (modelId.isEmpty()) return@mapNotNull null
                AiModel(
                    id = modelId,
                    createdAtEpochSeconds = model["created"]?.jsonPrimitive?.longOrNull,
                    ownedBy = model["owned_by"]?.jsonPrimitive?.contentOrNull,
                )
            }
            .distinctBy(AiModel::id)
            .sortedBy(AiModel::id)
    }

    override suspend fun createResponse(
        apiKey: String,
        modelId: String,
        prompt: String,
        previousResponseId: String?,
        toolDefinitions: List<SmeditToolDefinition>,
        executeTool: (suspend (AiToolCall) -> SmeditToolResult)?,
    ): AiChatReply {
        require(modelId.isNotBlank()) { "Select a model returned by OpenAI." }
        require(prompt.isNotBlank()) { "Message cannot be blank." }
        var payload = responsePayload(
            modelId = modelId,
            input = JsonPrimitive(prompt),
            previousResponseId = previousResponseId,
            toolDefinitions = toolDefinitions,
        )
        val collectedText = mutableListOf<String>()
        var toolRound = 0
        while (true) {
            val response = transport.send(
                "POST",
                "$baseUrl/responses",
                headers(apiKey) + ("Content-Type" to "application/json"),
                payload.toString(),
            )
            val root = parseSuccessful(response, apiKey)
            val responseId = root["id"]?.jsonPrimitive?.contentOrNull
                ?: throw AiProviderException(response.statusCode, "OpenAI returned a response without an id.")
            (root["output_text"]?.jsonPrimitive?.contentOrNull
                ?: root["output"]?.let(::extractOutputText))
                ?.takeIf(String::isNotBlank)
                ?.let(collectedText::add)

            val calls = parseToolCalls(root)
            if (calls.isEmpty()) {
                val text = collectedText.joinToString("\n").takeIf(String::isNotBlank)
                    ?: throw AiProviderException(response.statusCode, "OpenAI returned no text output.")
                return AiChatReply(responseId, text)
            }
            if (++toolRound > MAX_TOOL_ROUNDS) {
                throw AiProviderException(response.statusCode, "OpenAI exceeded the SMEDIT tool-call limit.")
            }
            val outputs = calls.map { call ->
                val result = if (executeTool == null) {
                    toolFailure("tools_unavailable", "SMEDIT tools are not available in this context.")
                } else executeTool(call)
                buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", call.callId)
                    put("output", result.outputJson())
                }
            }
            payload = responsePayload(
                modelId = modelId,
                input = JsonArray(outputs),
                previousResponseId = responseId,
                toolDefinitions = toolDefinitions,
            )
        }
    }

    private fun responsePayload(
        modelId: String,
        input: kotlinx.serialization.json.JsonElement,
        previousResponseId: String?,
        toolDefinitions: List<SmeditToolDefinition>,
    ): JsonObject = buildJsonObject {
        put("model", modelId)
        put("input", input)
        put(
            "instructions",
            "You are the SMEDIT assistant. Be precise, evidence-driven, and transparent. Use the provided SMEDIT tools for the current workspace. Use search_docs/read_doc for ROM and editor constraints, and search_asm for live ASM source when relevant; do not guess when those tools can answer. Preserve state-specific boss, event, and escape behavior unless the user explicitly asks to replace it. Read tools may inspect data. Mutation tools only stage an exact plan for explicit user approval; never claim a staged plan was applied. Tell the user when approval is required.",
        )
        previousResponseId?.takeIf(String::isNotBlank)?.let { put("previous_response_id", it) }
        if (toolDefinitions.isNotEmpty()) {
            put("tools", JsonArray(toolDefinitions.map { definition ->
                buildJsonObject {
                    put("type", "function")
                    put("name", definition.name)
                    put("description", definition.description)
                    put("parameters", definition.inputSchema)
                }
            }))
        }
    }

    private fun headers(apiKey: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${apiKey.trim()}",
        "Accept" to "application/json",
    )

    private fun parseSuccessful(response: AiHttpResponse, apiKey: String): JsonObject {
        val parsed = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        if (response.statusCode !in 200..299) {
            val providerMessage = parsed?.get("error")?.let { error ->
                (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            }
            val message = providerMessage?.takeIf(String::isNotBlank)?.replace(apiKey, "[REDACTED]")
                ?: "OpenAI request failed with HTTP ${response.statusCode}."
            throw AiProviderException(response.statusCode, message)
        }
        return parsed ?: throw AiProviderException(
            response.statusCode,
            "OpenAI returned an invalid JSON response.",
        )
    }

    private fun extractOutputText(output: kotlinx.serialization.json.JsonElement): String? {
        val chunks = (output as? JsonArray).orEmpty().flatMap { item ->
            val content = (item as? JsonObject)?.get("content") as? JsonArray ?: return@flatMap emptyList()
            content.mapNotNull { part ->
                val value = part as? JsonObject ?: return@mapNotNull null
                when (value["type"]?.jsonPrimitive?.contentOrNull) {
                    "output_text" -> value["text"]?.jsonPrimitive?.contentOrNull
                    "refusal" -> value["refusal"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            }
        }
        return chunks.takeIf(List<String>::isNotEmpty)?.joinToString("\n")
    }

    private fun parseToolCalls(root: JsonObject): List<AiToolCall> =
        (root["output"] as? JsonArray).orEmpty().mapNotNull { item ->
            val value = item as? JsonObject ?: return@mapNotNull null
            if (value["type"]?.jsonPrimitive?.contentOrNull != "function_call") return@mapNotNull null
            val callId = value["call_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = value["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rawArguments = value["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
            val parsed = runCatching { json.parseToJsonElement(rawArguments).jsonObject }
            AiToolCall(
                callId = callId,
                name = name,
                arguments = parsed.getOrDefault(JsonObject(emptyMap())),
                rawArguments = rawArguments,
                argumentError = parsed.exceptionOrNull()?.let { "Tool arguments were not a JSON object." },
            )
        }

    private fun toolFailure(code: String, message: String) = SmeditToolResult(
        output = buildJsonObject {
            put("ok", false)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        },
        isError = true,
    )

    private companion object {
        const val MAX_TOOL_ROUNDS = 8
    }
}

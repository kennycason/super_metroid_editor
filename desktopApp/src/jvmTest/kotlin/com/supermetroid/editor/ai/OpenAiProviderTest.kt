package com.supermetroid.editor.ai

import com.supermetroid.editor.api.SmeditToolDefinition
import com.supermetroid.editor.api.SmeditToolEffect
import com.supermetroid.editor.api.SmeditToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class OpenAiProviderTest {
    @Test
    fun `saved model is retained only while the provider still returns it`() {
        val models = listOf(AiModel("available-now"))
        assertEquals("available-now", retainedProviderModelId("available-now", models))
        assertEquals(null, retainedProviderModelId("removed-model", models))
        assertEquals(null, retainedProviderModelId(null, models))
    }

    @Test
    fun `models are populated only from the provider response without name assumptions`() = runBlocking {
        val transport = RecordingTransport(
            AiHttpResponse(
                200,
                """{"data":[
                    {"id":"future-text-model","created":42,"owned_by":"provider"},
                    {"id":"alpha-model","created":1,"owned_by":"provider"},
                    {"id":"future-text-model","created":42,"owned_by":"provider"}
                ]}""",
            ),
        )
        val provider = OpenAiProvider(transport, "https://example.invalid/v1")

        val models = provider.listModels("top-secret")

        assertEquals(listOf("alpha-model", "future-text-model"), models.map(AiModel::id))
        assertEquals("https://example.invalid/v1/models", transport.requests.single().url)
        assertEquals("Bearer top-secret", transport.requests.single().headers["Authorization"])
    }

    @Test
    fun `responses request preserves provider model id and conversation id`() = runBlocking {
        val transport = RecordingTransport(
            AiHttpResponse(
                200,
                """{"id":"resp_2","output":[{"type":"message","content":[{"type":"output_text","text":"Hello from OpenAI"}]}]}""",
            ),
        )
        val provider = OpenAiProvider(transport, "https://example.invalid/v1")

        val reply = provider.createResponse(
            apiKey = "top-secret",
            modelId = "provider-returned-model",
            prompt = "What changed?",
            previousResponseId = "resp_1",
        )

        assertEquals("resp_2", reply.responseId)
        assertEquals("Hello from OpenAI", reply.text)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://example.invalid/v1/responses", request.url)
        val payload = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("provider-returned-model", payload.getValue("model").jsonPrimitive.content)
        assertEquals("What changed?", payload.getValue("input").jsonPrimitive.content)
        assertEquals("resp_1", payload.getValue("previous_response_id").jsonPrimitive.content)
        assertFalse(request.body.contains("top-secret"))
    }

    @Test
    fun `provider errors expose useful detail but redact the credential`() = runBlocking {
        val transport = RecordingTransport(
            AiHttpResponse(401, """{"error":{"message":"Invalid credential top-secret"}}"""),
        )
        val provider = OpenAiProvider(transport, "https://example.invalid/v1")

        val error = assertFailsWith<AiProviderException> { provider.listModels("top-secret") }

        assertEquals(401, error.statusCode)
        assertTrue(error.message!!.contains("Invalid credential"))
        assertTrue(error.message!!.contains("[REDACTED]"))
        assertFalse(error.message!!.contains("top-secret"))
    }

    @Test
    fun `Responses function calls execute through the supplied SMEDIT registry adapter`() = runBlocking {
        val transport = RecordingTransport(
            AiHttpResponse(
                200,
                """{"id":"resp_tool","output":[{"type":"function_call","call_id":"call_1","name":"get_room","arguments":"{\"room_id\":\"91F8\"}"}]}""",
            ),
            AiHttpResponse(
                200,
                """{"id":"resp_final","output":[{"type":"message","content":[{"type":"output_text","text":"Landing Site uses music 6/5."}]}]}""",
            ),
        )
        val provider = OpenAiProvider(transport, "https://example.invalid/v1")
        val definition = SmeditToolDefinition(
            name = "get_room",
            description = "Get a room.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
                put("additionalProperties", false)
            },
            effect = SmeditToolEffect.READ_ONLY,
        )
        var executed: AiToolCall? = null

        val reply = provider.createResponse(
            apiKey = "top-secret",
            modelId = "provider-returned-model",
            prompt = "What music is in Landing Site?",
            toolDefinitions = listOf(definition),
            executeTool = { call ->
                executed = call
                SmeditToolResult(
                    buildJsonObject {
                        put("ok", true)
                        put("musicData", 6)
                        put("musicTrack", 5)
                    },
                    isError = false,
                )
            },
        )

        assertEquals("resp_final", reply.responseId)
        assertEquals("Landing Site uses music 6/5.", reply.text)
        assertEquals("get_room", executed?.name)
        assertEquals("91F8", executed?.arguments?.get("room_id")?.jsonPrimitive?.content)
        assertEquals(2, transport.requests.size)
        val firstPayload = Json.parseToJsonElement(transport.requests[0].body!!).jsonObject
        assertEquals("get_room", firstPayload.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        val continuation = Json.parseToJsonElement(transport.requests[1].body!!).jsonObject
        assertEquals("resp_tool", continuation.getValue("previous_response_id").jsonPrimitive.content)
        val output = continuation.getValue("input").jsonArray.single().jsonObject
        assertEquals("function_call_output", output.getValue("type").jsonPrimitive.content)
        assertEquals("call_1", output.getValue("call_id").jsonPrimitive.content)
        assertTrue(output.getValue("output").jsonPrimitive.content.contains("musicData"))
    }

    @Test
    fun `mutation tool output tells OpenAI that user approval is required`() = runBlocking {
        val transport = RecordingTransport(
            AiHttpResponse(
                200,
                """{"id":"resp_plan","output":[{"type":"function_call","call_id":"call_music","name":"set_room_music","arguments":"{\"music_data\":24,\"music_track\":5,\"exclude_areas\":[\"Ceres\"]}"}]}""",
            ),
            AiHttpResponse(
                200,
                """{"id":"resp_final","output":[{"type":"message","content":[{"type":"output_text","text":"The exact room-music plan is waiting for your approval."}]}]}""",
            ),
        )
        val provider = OpenAiProvider(transport, "https://example.invalid/v1")
        val definition = SmeditToolDefinition(
            name = "set_room_music",
            description = "Stage room music.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
                put("additionalProperties", false)
            },
            effect = SmeditToolEffect.MUTATION,
        )

        val reply = provider.createResponse(
            apiKey = "top-secret",
            modelId = "provider-returned-model",
            prompt = "Set every room to Lower Norfair except Ceres.",
            toolDefinitions = listOf(definition),
            executeTool = {
                SmeditToolResult(
                    buildJsonObject {
                        put("ok", true)
                        put("status", "approval_required")
                        put("affected_room_count", 250)
                    },
                    isError = false,
                )
            },
        )

        assertEquals("The exact room-music plan is waiting for your approval.", reply.text)
        val output = Json.parseToJsonElement(transport.requests[1].body!!).jsonObject
            .getValue("input").jsonArray.single().jsonObject.getValue("output").jsonPrimitive.content
        assertTrue(output.contains("approval_required"))
        assertTrue(output.contains("affected_room_count"))
    }

    private data class RecordedRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?,
    )

    private class RecordingTransport(vararg responses: AiHttpResponse) : AiHttpTransport {
        private val queued = ArrayDeque(responses.toList())
        val requests = mutableListOf<RecordedRequest>()

        override suspend fun send(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): AiHttpResponse {
            requests += RecordedRequest(method, url, headers, body)
            return queued.removeFirst()
        }
    }
}


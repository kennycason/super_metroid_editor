package com.supermetroid.editor.ai

import com.supermetroid.editor.data.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AiSettingsSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `legacy settings remain compatible and AI preferences contain no credential`() {
        val legacy = json.decodeFromString<AppSettings>("{}")
        assertNull(legacy.aiSelectedProvider)
        assertEquals(emptyMap(), legacy.aiSelectedModels)
        assertEquals(emptyMap(), legacy.aiCredentialSources)

        val configured = legacy.copy(
            aiSelectedProvider = AiProviderId.OPENAI.configId,
            aiSelectedModels = mapOf(AiProviderId.OPENAI.configId to "provider-model-id"),
            aiCredentialSources = mapOf(
                AiProviderId.OPENAI.configId to AiCredentialSource.SMEDIT_STORE.configId,
            ),
        )
        val encoded = json.encodeToString(configured)
        val decoded = json.decodeFromString<AppSettings>(encoded)
        assertEquals("provider-model-id", decoded.aiSelectedModels[AiProviderId.OPENAI.configId])
        assertEquals(
            AiCredentialSource.SMEDIT_STORE.configId,
            decoded.aiCredentialSources[AiProviderId.OPENAI.configId],
        )
    }
}


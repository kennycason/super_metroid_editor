package com.supermetroid.editor.ai

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AiConversationStoreTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `conversation histories are global owner only and older conversations are retained`() {
        val store = AiConversationStore(temporary)
        val first = conversation("conversation-one", 10, "First prompt")
        val second = conversation("conversation-two", 20, "Second prompt")

        store.save(first)
        store.save(second)

        assertEquals(second, store.loadActive(AiProviderId.OPENAI))
        assertEquals(listOf("conversation-two", "conversation-one"), store.conversations(AiProviderId.OPENAI).map { it.id })
        assertTrue(store.setActive(AiProviderId.OPENAI, "conversation-one"))
        assertEquals(first, store.loadActive(AiProviderId.OPENAI))
        assertTrue(store.rename(AiProviderId.OPENAI, "conversation-two", "Kraid palette work"))
        assertEquals("Kraid palette work", store.load(AiProviderId.OPENAI, "conversation-two")?.customTitle)
        assertEquals(
            "conversation-one",
            store.loadActive(AiProviderId.OPENAI)?.id,
            "Renaming history must not switch the active conversation.",
        )
        assertTrue(store.rename(AiProviderId.OPENAI, "conversation-two", "  "))
        assertEquals(null, store.load(AiProviderId.OPENAI, "conversation-two")?.customTitle)
        assertFalse(store.setActive(AiProviderId.OPENAI, "missing-conversation"))
        val historyFile = File(temporary, "ai/conversations/conversation-two.json")
        val activeFile = File(temporary, "ai/active.json")
        assertTrue(historyFile.isFile)
        assertTrue(activeFile.isFile)
        assertFalse(historyFile.readText().contains("apiKey", ignoreCase = true))
        runCatching { Files.getPosixFilePermissions(historyFile.toPath()) }.getOrNull()?.let { permissions ->
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions)
        }
        runCatching { Files.getPosixFilePermissions(historyFile.parentFile.toPath()) }.getOrNull()?.let { permissions ->
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                permissions,
            )
        }
        assertFalse(File(temporary, "credentials.json").exists())
    }

    private fun conversation(id: String, updated: Long, prompt: String) = StoredAiConversation(
        id = id,
        providerId = AiProviderId.OPENAI.configId,
        modelId = "provider-model",
        previousResponseId = "response-$id",
        updatedAtEpochMillis = updated,
        nextSequence = 1,
        items = listOf(
            StoredAiConversationItem(
                sequence = 1,
                kind = "chat",
                role = "USER",
                text = prompt,
            ),
        ),
    )
}

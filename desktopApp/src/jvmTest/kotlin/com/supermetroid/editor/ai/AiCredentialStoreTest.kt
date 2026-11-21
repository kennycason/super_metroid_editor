package com.supermetroid.editor.ai

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AiCredentialStoreTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `stored credentials are global separate secrets with owner only permissions`() {
        val store = AiCredentialStore(temporary) { null }
        store.save(AiProviderId.OPENAI, "  secret-key  ")

        val credential = store.credential(AiProviderId.OPENAI)
        assertEquals("secret-key", credential?.value)
        assertEquals(AiCredentialSource.SMEDIT_STORE, credential?.source)
        assertFalse(File(temporary, "config.json").exists())

        val secretFile = File(temporary, "credentials.json")
        assertTrue(secretFile.isFile)
        runCatching { Files.getPosixFilePermissions(secretFile.toPath()) }.getOrNull()?.let { permissions ->
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                permissions,
            )
        }

        store.delete(AiProviderId.OPENAI)
        assertNull(store.credential(AiProviderId.OPENAI))
    }

    @Test
    fun `environment credentials take precedence and cannot be removed by settings`() {
        val environment = mapOf(AiProviderId.OPENAI.environmentVariable to "environment-key")
        val store = AiCredentialStore(temporary, environment::get)
        store.save(AiProviderId.OPENAI, "stored-key")

        assertEquals("environment-key", store.credential(AiProviderId.OPENAI)?.value)
        assertEquals(AiCredentialSource.ENVIRONMENT, store.credential(AiProviderId.OPENAI)?.source)
        assertEquals(
            "stored-key",
            store.credential(AiProviderId.OPENAI, AiCredentialSource.SMEDIT_STORE)?.value,
            "An explicitly selected SMEDIT key must be able to override an available environment key.",
        )
        store.delete(AiProviderId.OPENAI)
        assertEquals("environment-key", store.credential(AiProviderId.OPENAI)?.value)
        assertNull(store.credential(AiProviderId.OPENAI, AiCredentialSource.SMEDIT_STORE))
    }

    @Test
    fun `blank keys are rejected`() {
        val store = AiCredentialStore(temporary) { null }
        assertFailsWith<IllegalArgumentException> { store.save(AiProviderId.OPENAI, "  ") }
    }
}


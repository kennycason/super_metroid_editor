package com.supermetroid.editor.ai

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class AiProviderId(
    val configId: String,
    val displayName: String,
    val environmentVariable: String,
) {
    OPENAI("openai", "OpenAI", "OPENAI_API_KEY"),
    ANTHROPIC("anthropic", "Claude", "ANTHROPIC_API_KEY");

    companion object {
        fun fromConfigId(value: String?): AiProviderId? = entries.firstOrNull { it.configId == value }
    }
}

enum class AiCredentialSource(val configId: String) {
    ENVIRONMENT("environment"),
    SMEDIT_STORE("smedit");

    companion object {
        fun fromConfigId(value: String?): AiCredentialSource? = entries.firstOrNull { it.configId == value }
    }
}

data class AiCredential(
    val value: String,
    val source: AiCredentialSource,
)

@Serializable
private data class StoredAiCredentials(
    val keys: Map<String, String> = emptyMap(),
)

/**
 * Global AI credential storage. Secrets deliberately live outside AppConfig/config.json.
 * Environment variables take precedence so headless and development launches remain predictable.
 */
class AiCredentialStore(
    private val configDirectory: File = File(System.getProperty("user.home"), ".smedit"),
    private val environment: (String) -> String? = System::getenv,
) {
    private val credentialsFile = File(configDirectory, "credentials.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Synchronized
    fun credential(provider: AiProviderId, source: AiCredentialSource? = null): AiCredential? {
        if (source == null || source == AiCredentialSource.ENVIRONMENT) {
            environment(provider.environmentVariable)?.trim()?.takeIf(String::isNotEmpty)?.let {
                return AiCredential(it, AiCredentialSource.ENVIRONMENT)
            }
        }
        if (source == null || source == AiCredentialSource.SMEDIT_STORE) {
            return readStored().keys[provider.configId]?.trim()?.takeIf(String::isNotEmpty)?.let {
                AiCredential(it, AiCredentialSource.SMEDIT_STORE)
            }
        }
        return null
    }

    @Synchronized
    fun hasCredential(provider: AiProviderId): Boolean = credential(provider) != null

    @Synchronized
    fun hasCredential(provider: AiProviderId, source: AiCredentialSource): Boolean =
        credential(provider, source) != null

    @Synchronized
    fun save(provider: AiProviderId, apiKey: String) {
        val normalized = apiKey.trim()
        require(normalized.isNotEmpty()) { "API key cannot be blank." }
        val updated = readStored().keys.toMutableMap().apply { put(provider.configId, normalized) }
        writeStored(StoredAiCredentials(updated))
    }

    /** Removes the SMEDIT-stored key. An environment-provided key remains available. */
    @Synchronized
    fun delete(provider: AiProviderId) {
        val updated = readStored().keys.toMutableMap()
        if (updated.remove(provider.configId) != null) writeStored(StoredAiCredentials(updated))
    }

    private fun readStored(): StoredAiCredentials {
        if (!credentialsFile.isFile) return StoredAiCredentials()
        return runCatching { json.decodeFromString<StoredAiCredentials>(credentialsFile.readText()) }
            .getOrDefault(StoredAiCredentials())
    }

    private fun writeStored(credentials: StoredAiCredentials) {
        configDirectory.mkdirs()
        restrictToOwner(configDirectory, directory = true)
        val temporary = File(configDirectory, "credentials.json.tmp")
        try {
            temporary.writeText(json.encodeToString(credentials))
            restrictToOwner(temporary, directory = false)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    credentialsFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), credentialsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            restrictToOwner(credentialsFile, directory = false)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun restrictToOwner(file: File, directory: Boolean) {
        runCatching {
            val permissions = if (directory) {
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                )
            } else {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
            Files.setPosixFilePermissions(file.toPath(), permissions)
        }
    }
}


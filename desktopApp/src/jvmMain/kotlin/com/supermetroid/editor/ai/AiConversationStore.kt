package com.supermetroid.editor.ai

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StoredAiPatchValueChange(
    val key: String,
    val label: String,
    val before: Int? = null,
    val after: Int? = null,
)

@Serializable
data class StoredAiPatchMutationPlan(
    val patchId: String,
    val patchName: String,
    val configType: String? = null,
    val beforeEnabled: Boolean,
    val afterEnabled: Boolean,
    val valueChanges: List<StoredAiPatchValueChange> = emptyList(),
    val automaticallyDisabledPatches: List<String> = emptyList(),
)

@Serializable
data class StoredAiMutationPlan(
    val id: String,
    val toolName: String,
    val title: String,
    val musicData: Int,
    val musicTrack: Int,
    val affectedRoomCount: Int,
    val affectedStateCount: Int,
    val affectedRoomPreview: List<String> = emptyList(),
    val excludedRoomIds: List<String> = emptyList(),
    val excludedAreas: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val protectedRoomIds: List<String> = emptyList(),
    val protectedRoomPreview: List<String> = emptyList(),
    val kind: String = "ROOM_MUSIC",
    val patch: StoredAiPatchMutationPlan? = null,
)

@Serializable
data class StoredAiConversationItem(
    val sequence: Long,
    val kind: String,
    val role: String? = null,
    val text: String? = null,
    val planId: String? = null,
    val plan: StoredAiMutationPlan? = null,
    val status: String? = null,
    val isError: Boolean = false,
)

@Serializable
data class StoredAiConversation(
    val id: String,
    val providerId: String,
    val modelId: String? = null,
    val previousResponseId: String? = null,
    val updatedAtEpochMillis: Long,
    val nextSequence: Long,
    val items: List<StoredAiConversationItem> = emptyList(),
    val formatVersion: Int = 1,
)

@Serializable
private data class ActiveAiConversations(
    val providers: Map<String, String> = emptyMap(),
)

/** Owner-only global conversation history, deliberately separate from projects and credentials. */
class AiConversationStore(
    configDirectory: File = File(System.getProperty("user.home"), ".smedit"),
) {
    private val configDirectory = configDirectory
    private val aiDirectory = File(configDirectory, "ai")
    private val conversationsDirectory = File(aiDirectory, "conversations")
    private val activeFile = File(aiDirectory, "active.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Synchronized
    fun loadActive(provider: AiProviderId): StoredAiConversation? {
        val id = readActive().providers[provider.configId]?.takeIf(::safeConversationId) ?: return null
        return readConversation(File(conversationsDirectory, "$id.json"))
            ?.takeIf { it.id == id && it.providerId == provider.configId }
    }

    @Synchronized
    fun conversations(provider: AiProviderId): List<StoredAiConversation> =
        conversationsDirectory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .mapNotNull(::readConversation)
            .filter { it.providerId == provider.configId }
            .sortedByDescending(StoredAiConversation::updatedAtEpochMillis)

    @Synchronized
    fun save(conversation: StoredAiConversation) {
        require(safeConversationId(conversation.id)) { "Invalid AI conversation id." }
        require(AiProviderId.fromConfigId(conversation.providerId) != null) { "Unknown AI provider." }
        ensureDirectories()
        writeOwnerOnly(
            File(conversationsDirectory, "${conversation.id}.json"),
            json.encodeToString(conversation),
        )
        val active = readActive().providers + (conversation.providerId to conversation.id)
        writeOwnerOnly(activeFile, json.encodeToString(ActiveAiConversations(active)))
    }

    private fun readConversation(file: File): StoredAiConversation? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<StoredAiConversation>(file.readText()) }.getOrNull()
            ?.takeIf { it.formatVersion == 1 && safeConversationId(it.id) }
    }

    private fun readActive(): ActiveAiConversations {
        if (!activeFile.isFile) return ActiveAiConversations()
        return runCatching { json.decodeFromString<ActiveAiConversations>(activeFile.readText()) }
            .getOrDefault(ActiveAiConversations())
    }

    private fun ensureDirectories() {
        conversationsDirectory.mkdirs()
        restrictToOwner(configDirectory, directory = true)
        restrictToOwner(aiDirectory, directory = true)
        restrictToOwner(conversationsDirectory, directory = true)
    }

    private fun writeOwnerOnly(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.writeText(text)
            restrictToOwner(temporary, directory = false)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            restrictToOwner(file, directory = false)
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

    private fun safeConversationId(value: String): Boolean =
        value.length in 1..100 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}

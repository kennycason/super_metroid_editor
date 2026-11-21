package com.supermetroid.editor.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

enum class SmeditToolEffect { READ_ONLY, MUTATION }

data class SmeditToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val effect: SmeditToolEffect,
)

data class SmeditToolResult(
    val output: JsonObject,
    val isError: Boolean,
) {
    fun outputJson(): String = output.toString()
}

@Serializable
data class SmeditWorkspaceSummary(
    val projectLoaded: Boolean,
    val projectName: String? = null,
    val romFileName: String? = null,
    val projectFilePath: String? = null,
    val readOnly: Boolean = false,
    val dirty: Boolean = false,
    val roomCount: Int = 0,
    val editedRoomCount: Int = 0,
    val patchCount: Int = 0,
    val enabledPatchCount: Int = 0,
    val asmWorkspaceEnabled: Boolean = false,
    val asmSourceAvailable: Boolean = false,
    val asmUnsavedFileCount: Int = 0,
    val selectedRoomId: String? = null,
    val selectedRoomName: String? = null,
)

@Serializable
data class SmeditRoomSummary(
    val id: String,
    val name: String,
    val handle: String? = null,
    val area: String? = null,
    val widthScreens: Int? = null,
    val heightScreens: Int? = null,
    val musicData: Int? = null,
    val musicTrack: Int? = null,
    val edited: Boolean = false,
    val projectOwned: Boolean = false,
)

@Serializable
data class SmeditRoomMusicState(
    val index: Int,
    val condition: String,
    val musicData: Int,
    val musicTrack: Int,
)

@Serializable
data class SmeditRoomDetails(
    val id: String,
    val name: String,
    val handle: String,
    val comment: String? = null,
    val area: String,
    val areaIndex: Int,
    val mapX: Int,
    val mapY: Int,
    val widthScreens: Int,
    val heightScreens: Int,
    val tileset: Int,
    val musicData: Int,
    val musicTrack: Int,
    val stateCount: Int,
    val projectStateEditCount: Int,
    val edited: Boolean,
    val projectOwned: Boolean,
    /** Music bytes in each source ROM state, before project-level overrides are applied. */
    val sourceStateMusic: List<SmeditRoomMusicState> = emptyList(),
    /** True when the source states intentionally use more than one music pair. */
    val hasDistinctSourceMusic: Boolean = false,
    /** True when this project already owns music fields on one or more individual states. */
    val hasStateMusicEdits: Boolean = false,
)

@Serializable
data class SmeditPatchSummary(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val configType: String? = null,
    val configurable: Boolean = false,
)

@Serializable
data class SmeditPatchConfigChoice(
    val label: String,
    val value: Int,
)

@Serializable
data class SmeditPatchConfigField(
    val key: String,
    val label: String,
    val type: String,
    val min: Int,
    val max: Int,
    val currentValue: Int? = null,
    val defaultValue: Int? = null,
    val description: String = "",
    val category: String? = null,
    val unit: String = "",
    val choices: List<SmeditPatchConfigChoice> = emptyList(),
)

@Serializable
data class SmeditPatchDetails(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val configType: String? = null,
    val configValue: Int? = null,
    val configData: Map<String, Int> = emptyMap(),
    val configurable: Boolean = false,
    val configFieldCount: Int = 0,
    val configFields: List<SmeditPatchConfigField> = emptyList(),
)

@Serializable
data class SmeditEditorSettingsSummary(
    val theme: String,
    val fontSize: String,
    val emulatorBackend: String,
    val emulatorFollowLiveRoom: Boolean,
    val roomSidebarTab: String,
    val roomItemNamesVisible: Boolean,
    val roomMetaNamesVisible: Boolean,
    val roomEnemyNamesVisible: Boolean,
    val aiSelectedProvider: String? = null,
    val aiSelectedModel: String? = null,
)

@Serializable
data class SmeditAsmSearchMatch(
    val fileId: String,
    val displayName: String,
    val line: Int,
    val column: Int,
    val text: String,
)

@Serializable
data class SmeditDocumentationMatch(
    val path: String,
    val line: Int,
    val text: String,
)

@Serializable
data class SmeditDocumentationExcerpt(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
)

/** Semantic workspace reads implemented by desktop today and reusable by MCP/CLI adapters later. */
interface SmeditWorkspaceAccess {
    fun workspaceSummary(): SmeditWorkspaceSummary
    fun rooms(): List<SmeditRoomSummary>
    fun room(roomId: Int): SmeditRoomDetails?
    fun patches(): List<SmeditPatchSummary>
    fun patch(identifier: String): SmeditPatchDetails?
    fun editorSettings(): SmeditEditorSettingsSummary
    fun searchDocs(query: String, limit: Int): List<SmeditDocumentationMatch> = emptyList()
    fun readDoc(path: String, startLine: Int, maxLines: Int): SmeditDocumentationExcerpt? = null
    fun searchAsm(query: String, limit: Int): List<SmeditAsmSearchMatch>
}

/**
 * Provider- and transport-neutral semantic tool registry. OpenAI, Claude, MCP,
 * CLI, and future web adapters should expose these definitions rather than
 * reaching into EditorState independently.
 */
class SmeditToolRegistry(private val access: SmeditWorkspaceAccess) {
    val definitions: List<SmeditToolDefinition> = listOf(
        SmeditToolDefinition(
            name = "get_workspace_summary",
            description = "Describe the currently open SMEDIT project or ROM, edit state, selected room, patches, and ASM workspace.",
            inputSchema = objectSchema(),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "list_rooms",
            description = "List rooms in the current ROM hack. Optionally filter by room name, hexadecimal room id, handle, or area.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "query" to stringSchema("Optional case-insensitive room filter."),
                    "limit" to integerSchema("Maximum rooms to return, from 1 through 100."),
                ),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "get_room",
            description = "Get effective metadata for one room, including dimensions, map position, tileset, effective music, source music for every runtime state, and edit state.",
            inputSchema = objectSchema(
                properties = mapOf("room_id" to stringSchema("Room id in hexadecimal, such as 91F8, 0x91F8, or \$91F8.")),
                required = listOf("room_id"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "list_patches",
            description = "List project patches and whether they are enabled. Optionally return only enabled or disabled patches.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "status" to enumSchema("Patch status filter.", listOf("all", "enabled", "disabled")),
                ),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "get_patch",
            description = "Get one patch and its provider-neutral configuration schema, including canonical field keys, ranges, defaults, choices, and current values.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "patch" to stringSchema("Patch id, config type, or exact patch name."),
                    "field_query" to stringSchema("Optional case-insensitive field key or label filter."),
                    "limit" to integerSchema("Maximum configuration fields to return, from 1 through 100."),
                ),
                required = listOf("patch"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "get_editor_settings",
            description = "Read non-secret global SMEDIT preferences. API credentials are never exposed.",
            inputSchema = objectSchema(),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "search_docs",
            description = "Search SMEDIT's curated ROM, editor, boss, sound, validation, and ASM-workflow documentation. Use this for implementation constraints and engine behavior instead of guessing.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "query" to stringSchema("Non-empty documentation search terms."),
                    "limit" to integerSchema("Maximum matches to return, from 1 through 100."),
                ),
                required = listOf("query"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "read_doc",
            description = "Read a bounded line range from a SMEDIT documentation file returned by search_docs. Paths are restricted to the curated docs tree.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "path" to stringSchema("Documentation path returned by search_docs, such as rom/write_safety.md."),
                    "start_line" to integerSchema("First one-based line to read, from 1 through 100000.", 1, 100000),
                    "max_lines" to integerSchema("Maximum lines to return, from 1 through 200.", 1, 200),
                ),
                required = listOf("path"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "search_asm",
            description = "Search the loaded ASM workspace, including live unsaved editor buffers, for literal source text.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "query" to stringSchema("Non-empty literal source text to find."),
                    "limit" to integerSchema("Maximum matches to return, from 1 through 100."),
                ),
                required = listOf("query"),
            ),
            effect = SmeditToolEffect.READ_ONLY,
        ),
        SmeditToolDefinition(
            name = "set_room_music",
            description = "Stage a room-music change for explicit user approval. This never applies the change itself. Empty room_ids means every room. By default SMEDIT protects rooms whose runtime states use distinct music (boss/event/escape behavior) or have state-specific project music edits. Only set overwrite_special_state_music when the user explicitly asks to flatten those state-specific values.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "music_data" to integerSchema("Music data/song-set byte, from 0 through 255.", 0, 255),
                    "music_track" to integerSchema("Music track/play-index byte, from 0 through 255.", 0, 255),
                    "room_ids" to stringArraySchema("Optional hexadecimal room ids. An empty array targets all rooms."),
                    "exclude_room_ids" to stringArraySchema("Optional hexadecimal room ids to exclude."),
                    "exclude_areas" to stringArraySchema("Optional case-insensitive area names to exclude, such as Ceres."),
                    "overwrite_special_state_music" to booleanSchema(
                        "Explicit opt-in to overwrite distinct boss/event/state-specific music. Defaults to false.",
                    ),
                ),
                required = listOf("music_data", "music_track"),
            ),
            effect = SmeditToolEffect.MUTATION,
        ),
        SmeditToolDefinition(
            name = "configure_patch",
            description = "Stage enabling, disabling, or configuring one existing patch for explicit user approval. Use get_patch first and send canonical configuration keys. This never applies the change itself.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "patch" to stringSchema("Patch id, config type, or exact patch name."),
                    "enabled" to booleanSchema("Optional requested enabled state."),
                    "values" to integerMapSchema("Optional configuration values keyed by canonical get_patch field keys."),
                ),
                required = listOf("patch"),
            ),
            effect = SmeditToolEffect.MUTATION,
        ),
    )

    private val definitionsByName = definitions.associateBy(SmeditToolDefinition::name)
    private val json = Json { encodeDefaults = true }

    fun definition(name: String): SmeditToolDefinition? = definitionsByName[name]

    fun execute(name: String, arguments: JsonObject): SmeditToolResult {
        val definition = definitionsByName[name]
            ?: return failure("unknown_tool", "SMEDIT does not expose a tool named '$name'.")
        if (definition.effect != SmeditToolEffect.READ_ONLY) {
            return failure("approval_required", "Mutation tools require a separately approved action plan.")
        }
        return try {
            when (name) {
                "get_workspace_summary" -> success(json.encodeToJsonElement(SmeditWorkspaceSummary.serializer(), access.workspaceSummary()))
                "list_rooms" -> {
                    val query = arguments.string("query")?.trim().orEmpty()
                    val limit = arguments.limit()
                    val rooms = access.rooms().asSequence().filter { room ->
                        query.isEmpty() || listOf(room.id, room.name, room.handle.orEmpty(), room.area.orEmpty()).any {
                            it.contains(query, ignoreCase = true)
                        }
                    }.take(limit).toList()
                    success(json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(SmeditRoomSummary.serializer()), rooms))
                }
                "get_room" -> {
                    val rawId = arguments.string("room_id")
                        ?: return failure("invalid_arguments", "room_id is required.")
                    val roomId = parseRoomId(rawId)
                        ?: return failure("invalid_room_id", "'$rawId' is not a 16-bit hexadecimal room id.")
                    val room = access.room(roomId)
                        ?: return failure("room_not_found", "No readable room exists at ${formatRoomId(roomId)}.")
                    success(json.encodeToJsonElement(SmeditRoomDetails.serializer(), room))
                }
                "list_patches" -> {
                    val status = arguments.string("status")?.lowercase() ?: "all"
                    if (status !in setOf("all", "enabled", "disabled")) {
                        return failure("invalid_arguments", "status must be all, enabled, or disabled.")
                    }
                    val patches = access.patches().filter {
                        status == "all" || (status == "enabled") == it.enabled
                    }
                    success(json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(SmeditPatchSummary.serializer()), patches))
                }
                "get_patch" -> {
                    val identifier = arguments.string("patch")?.trim()?.takeIf(String::isNotEmpty)
                        ?: return failure("invalid_arguments", "patch is required.")
                    val patch = access.patch(identifier)
                        ?: return failure("patch_not_found", "No patch uniquely matches '$identifier'.")
                    val query = arguments.string("field_query")?.trim().orEmpty()
                    val fields = patch.configFields.asSequence().filter { field ->
                        query.isEmpty() || field.key.contains(query, ignoreCase = true) ||
                            field.label.contains(query, ignoreCase = true)
                    }.take(arguments.limit()).toList()
                    success(json.encodeToJsonElement(SmeditPatchDetails.serializer(), patch.copy(configFields = fields)))
                }
                "get_editor_settings" -> success(
                    json.encodeToJsonElement(SmeditEditorSettingsSummary.serializer(), access.editorSettings()),
                )
                "search_docs" -> {
                    val query = arguments.string("query")?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return failure("invalid_arguments", "query must not be blank.")
                    val matches = access.searchDocs(query, arguments.limit())
                    success(
                        json.encodeToJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(SmeditDocumentationMatch.serializer()),
                            matches,
                        ),
                    )
                }
                "read_doc" -> {
                    val path = arguments.string("path")?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return failure("invalid_arguments", "path must not be blank.")
                    val startLine = arguments.positiveInt("start_line", default = 1, maximum = 100000)
                        ?: return failure("invalid_arguments", "start_line must be an integer from 1 through 100000.")
                    val maxLines = arguments.positiveInt("max_lines", default = 120, maximum = 200)
                        ?: return failure("invalid_arguments", "max_lines must be an integer from 1 through 200.")
                    val excerpt = access.readDoc(path, startLine, maxLines)
                        ?: return failure("doc_not_found", "No readable SMEDIT documentation file exists at '$path'.")
                    success(json.encodeToJsonElement(SmeditDocumentationExcerpt.serializer(), excerpt))
                }
                "search_asm" -> {
                    val query = arguments.string("query")?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return failure("invalid_arguments", "query must not be blank.")
                    val matches = access.searchAsm(query, arguments.limit())
                    success(json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(SmeditAsmSearchMatch.serializer()), matches))
                }
                else -> failure("unknown_tool", "SMEDIT does not expose a tool named '$name'.")
            }
        } catch (problem: Exception) {
            failure("tool_failed", problem.message ?: "The SMEDIT tool failed.")
        }
    }

    private fun success(data: JsonElement) = SmeditToolResult(
        buildJsonObject {
            put("ok", true)
            put("data", data)
        },
        isError = false,
    )

    private fun failure(code: String, message: String) = SmeditToolResult(
        buildJsonObject {
            put("ok", false)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        },
        isError = true,
    )

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.limit(): Int =
        (get("limit") as? JsonPrimitive)?.intOrNull?.coerceIn(1, 100) ?: 25

    private fun JsonObject.positiveInt(name: String, default: Int, maximum: Int): Int? {
        val value = get(name) ?: return default
        return (value as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..maximum }
    }

    private fun parseRoomId(value: String): Int? {
        val normalized = value.trim().removePrefix("0x").removePrefix("0X").removePrefix("\$")
        return normalized.toIntOrNull(16)?.takeIf { it in 0..0xFFFF }
    }

    private fun formatRoomId(roomId: Int): String = "$" + roomId.toString(16).uppercase().padStart(4, '0')

    companion object {
        private fun objectSchema(
            properties: Map<String, JsonObject> = emptyMap(),
            required: List<String> = emptyList(),
        ) = buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties))
            put("required", JsonArray(required.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }

        private fun stringSchema(description: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
        }

        private fun integerSchema(description: String, minimum: Int = 1, maximum: Int = 100) = buildJsonObject {
            put("type", "integer")
            put("description", description)
            put("minimum", minimum)
            put("maximum", maximum)
        }

        private fun stringArraySchema(description: String) = buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject { put("type", "string") })
        }

        private fun enumSchema(description: String, values: List<String>) = buildJsonObject {
            put("type", "string")
            put("description", description)
            put("enum", JsonArray(values.map(::JsonPrimitive)))
        }

        private fun booleanSchema(description: String) = buildJsonObject {
            put("type", "boolean")
            put("description", description)
        }

        private fun integerMapSchema(description: String) = buildJsonObject {
            put("type", "object")
            put("description", description)
            put("additionalProperties", buildJsonObject { put("type", "integer") })
        }
    }
}

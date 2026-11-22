package com.supermetroid.editor.api

import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomStateEdits
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.headless.BOMB_CONFIG_TYPE
import com.supermetroid.editor.headless.BOMB_COOLDOWN_FRAMES_KEY
import com.supermetroid.editor.headless.BOMB_MAX_ACTIVE_KEY
import com.supermetroid.editor.headless.CERES_ESCAPE_CONFIG_TYPE
import com.supermetroid.editor.headless.CONTROLLER_CONFIG_TYPE
import com.supermetroid.editor.headless.INTRO_SEQUENCE_CONFIG_TYPE
import com.supermetroid.editor.headless.SHORT_CHARGE_CONFIG_TYPE
import com.supermetroid.editor.headless.SHORT_CHARGE_STAGES_KEY
import com.supermetroid.editor.headless.SHORT_CHARGE_TAPS_KEY
import com.supermetroid.editor.headless.SmeditPatchCatalog
import com.supermetroid.editor.headless.ZEBES_ESCAPE_CONFIG_TYPE
import com.supermetroid.editor.rom.RomValidator
import com.supermetroid.editor.rom.NativeLiquidOptions
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.baseSourceStateIndex
import com.supermetroid.editor.rom.emptyDefaultFxEntry
import com.supermetroid.editor.rom.ensureStateManifest
import com.supermetroid.editor.rom.withFxChange
import com.supermetroid.editor.asm.snapshotRoomForResize
import com.supermetroid.editor.ui.EditorState
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

enum class SmeditMutationPlanKind { ROOM_MUSIC, PATCH, ROOM_FX }

data class SmeditPatchValueChange(
    val key: String,
    val label: String,
    val before: Int?,
    val after: Int?,
)

data class SmeditPatchMutationPlan(
    val patchId: String,
    val patchName: String,
    val configType: String?,
    val beforeEnabled: Boolean,
    val afterEnabled: Boolean,
    val valueChanges: List<SmeditPatchValueChange>,
    val automaticallyDisabledPatches: List<String>,
)

data class SmeditRoomFxMutationPlan(
    val requestedChanges: List<String>,
    val affectedStatePreview: List<String>,
)

data class SmeditMutationPlan(
    val id: String,
    val toolName: String,
    val title: String,
    val musicData: Int,
    val musicTrack: Int,
    val affectedRoomCount: Int,
    val affectedStateCount: Int,
    val affectedRoomPreview: List<String>,
    val excludedRoomIds: List<String>,
    val excludedAreas: List<String>,
    val warnings: List<String>,
    val protectedRoomIds: List<String> = emptyList(),
    val protectedRoomPreview: List<String> = emptyList(),
    val kind: SmeditMutationPlanKind = SmeditMutationPlanKind.ROOM_MUSIC,
    val patch: SmeditPatchMutationPlan? = null,
    val fx: SmeditRoomFxMutationPlan? = null,
)

data class SmeditMutationPlanningResult(
    val result: SmeditToolResult,
    val plan: SmeditMutationPlan? = null,
)

data class SmeditMutationTransition(
    val planId: String,
    val summary: String,
)

interface SmeditMutationController {
    fun plan(toolName: String, arguments: JsonObject): SmeditMutationPlanningResult
    fun approve(planId: String): SmeditMutationTransition
    fun reject(planId: String)
    fun undo(): SmeditMutationTransition
    fun redo(): SmeditMutationTransition
    val canUndo: Boolean
    val canRedo: Boolean
}

/**
 * Desktop mutation boundary for approval-backed AI edits. Plans contain only
 * optimistic snapshots; no project state changes until [approve].
 */
internal class DesktopSmeditMutationController(
    private val editorState: EditorState,
    private val workspace: SmeditWorkspaceAccess,
    private val parser: () -> RomParser? = { editorState.roomSourceParser },
) : SmeditMutationController {
    private sealed interface PendingPlan {
        val presentation: SmeditMutationPlan
        val projectId: String
    }

    private sealed interface AppliedAction {
        val presentation: SmeditMutationPlan
        val projectId: String
    }

    private data class MusicFields(val data: Int?, val track: Int?)
    private data class StateMusicFields(val id: String, val music: MusicFields)
    private data class RoomMusicSnapshot(
        val roomId: Int,
        val wasPresent: Boolean,
        val common: MusicFields,
        val states: List<StateMusicFields>,
    )
    private data class RoomMusicPlan(
        override val presentation: SmeditMutationPlan,
        override val projectId: String,
        val roomIds: List<Int>,
        val before: List<RoomMusicSnapshot>,
    ) : PendingPlan
    private data class AppliedRoomMusicAction(
        val plan: RoomMusicPlan,
        val after: List<RoomMusicSnapshot>,
    ) : AppliedAction {
        override val presentation: SmeditMutationPlan get() = plan.presentation
        override val projectId: String get() = plan.projectId
    }

    private data class PatchFields(
        val enabled: Boolean,
        val configValue: Int?,
        val configData: Map<String, Int>,
    )

    private data class PatchSnapshot(
        val patchId: String,
        val target: PatchFields,
        val exclusivePeerEnabled: Map<String, Boolean>,
    )

    private data class PatchPlan(
        override val presentation: SmeditMutationPlan,
        override val projectId: String,
        val patchId: String,
        val before: PatchSnapshot,
        val after: PatchSnapshot,
    ) : PendingPlan

    private data class AppliedPatchAction(
        val plan: PatchPlan,
    ) : AppliedAction {
        override val presentation: SmeditMutationPlan get() = plan.presentation
        override val projectId: String get() = plan.projectId
    }

    private data class RoomModelSnapshot(
        val roomId: Int,
        val room: RoomEdits?,
    )

    private data class RoomFxPlan(
        override val presentation: SmeditMutationPlan,
        override val projectId: String,
        val before: List<RoomModelSnapshot>,
        val after: List<RoomModelSnapshot>,
    ) : PendingPlan

    private data class AppliedRoomFxAction(
        val plan: RoomFxPlan,
    ) : AppliedAction {
        override val presentation: SmeditMutationPlan get() = plan.presentation
        override val projectId: String get() = plan.projectId
    }

    private var pending: PendingPlan? = null
    private val undoStack = mutableListOf<AppliedAction>()
    private val redoStack = mutableListOf<AppliedAction>()

    override val canUndo: Boolean get() = undoStack.isNotEmpty()
    override val canRedo: Boolean get() = redoStack.isNotEmpty()

    override fun plan(toolName: String, arguments: JsonObject): SmeditMutationPlanningResult {
        if (pending != null) return failure("pending_approval_exists", "Approve or reject the existing AI plan before staging another write.")
        return when (toolName) {
            SET_ROOM_MUSIC -> planRoomMusic(arguments)
            CONFIGURE_PATCH -> planPatch(arguments)
            SET_ROOM_FX -> planRoomFx(arguments)
            else -> failure("unknown_mutation", "SMEDIT does not expose a mutation named '$toolName'.")
        }
    }

    private fun planRoomMusic(arguments: JsonObject): SmeditMutationPlanningResult {
        val toolName = SET_ROOM_MUSIC
        val summary = workspace.workspaceSummary()
        if (!summary.projectLoaded) return failure("workspace_unavailable", "Open a project or editable ROM before changing room music.")
        if (summary.readOnly) return failure("workspace_read_only", "The current room catalog is read-only.")
        val project = editorState.project
        if (project.asmWorkspace.enabled && project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) {
            return failure(
                "asm_source_unsupported",
                "ASM-source room music writes are not yet supported. No project data was changed.",
            )
        }
        val musicData = arguments.byte("music_data")
            ?: return failure("invalid_arguments", "music_data must be an integer from 0 through 255.")
        val musicTrack = arguments.byte("music_track")
            ?: return failure("invalid_arguments", "music_track must be an integer from 0 through 255.")
        val requestedIds = arguments.stringList("room_ids")
            ?: return failure("invalid_arguments", "room_ids must be an array of hexadecimal room ids.")
        val excludedIds = arguments.stringList("exclude_room_ids")
            ?: return failure("invalid_arguments", "exclude_room_ids must be an array of hexadecimal room ids.")
        val excludedAreas = arguments.stringList("exclude_areas")
            ?: return failure("invalid_arguments", "exclude_areas must be an array of area names.")
        val overwriteSpecialStateMusic = arguments.optionalBoolean("overwrite_special_state_music")
            ?: if ("overwrite_special_state_music" in arguments) {
                return failure("invalid_arguments", "overwrite_special_state_music must be true or false.")
            } else false
        val overwriteAudioSensitiveMusic = arguments.optionalBoolean("overwrite_audio_sensitive_music")
            ?: if ("overwrite_audio_sensitive_music" in arguments) {
                return failure("invalid_arguments", "overwrite_audio_sensitive_music must be true or false.")
            } else false

        val catalog = workspace.rooms().distinctBy { parseRoomId(it.id) }.mapNotNull { room ->
            parseRoomId(room.id)?.let { it to room }
        }
        val catalogById = catalog.toMap()
        if (catalogById.isEmpty()) return failure("no_rooms", "The current workspace has no writable rooms.")
        val selected = parseIds(requestedIds) ?: return invalidRoomIds("room_ids")
        val excluded = parseIds(excludedIds) ?: return invalidRoomIds("exclude_room_ids")
        val unknown = (selected + excluded).filterNot(catalogById::containsKey).distinct()
        if (unknown.isNotEmpty()) {
            return failure("room_not_found", "Unknown room id(s): ${unknown.joinToString { formatRoomId(it) }}.")
        }
        val normalizedAreas = excludedAreas.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)
        val unmatchedAreas = normalizedAreas.filter { requested ->
            catalog.none { (_, room) -> room.area?.equals(requested, ignoreCase = true) == true }
        }
        val candidateIds = (selected.takeIf(List<Int>::isNotEmpty) ?: catalog.map(Pair<Int, SmeditRoomSummary>::first)).toSet()
        val candidateRooms = catalog.filter { (id, room) ->
            id in candidateIds && id !in excluded && normalizedAreas.none { area -> room.area?.equals(area, ignoreCase = true) == true }
        }
        if (candidateRooms.isEmpty()) return failure("empty_scope", "The requested filters leave no rooms to change.")

        val roomDetails = candidateRooms.associate { (roomId, _) -> roomId to workspace.room(roomId) }
        val stateSensitiveRooms = candidateRooms.filter { (roomId, _) ->
            roomDetails[roomId]?.let { it.hasDistinctSourceMusic || it.hasStateMusicEdits } == true
        }
        // Tourian's Metroid, Baby Metroid, Mother Brain, and escape sounds depend on
        // the native Tourian song's instruments. Protect the whole area because many
        // room headers use 00:00 to inherit music from the preceding room; protecting
        // only the enemy room would still allow an unsafe song to flow into it.
        val audioSensitiveRooms = candidateRooms.filter { (_, room) ->
            room.area?.equals(TOURIAN_AREA, ignoreCase = true) == true
        }
        val uninspectableRooms = candidateRooms.filter { (roomId, _) -> roomDetails[roomId] == null }
        val protectedRooms = (
            uninspectableRooms +
                (if (overwriteSpecialStateMusic) emptyList() else stateSensitiveRooms) +
                (if (overwriteAudioSensitiveMusic) emptyList() else audioSensitiveRooms)
            )
            .distinctBy(Pair<Int, SmeditRoomSummary>::first)
        val protectedIds = protectedRooms.map(Pair<Int, SmeditRoomSummary>::first).toSet()
        val targetRooms = candidateRooms.filterNot { (roomId, _) -> roomId in protectedIds }
        if (targetRooms.isEmpty()) {
            val protected = protectedRooms.joinToString { (roomId, room) -> "${room.name} (${formatRoomId(roomId)})" }
            return failure(
                "protected_music",
                "Every requested room was protected from an unsafe music write: $protected. " +
                    "Distinct state music requires overwrite_special_state_music=true; Tourian's song-dependent audio " +
                    "requires the separate overwrite_audio_sensitive_music=true opt-in.",
            )
        }
        val managed = targetRooms.map(Pair<Int, SmeditRoomSummary>::first).filter { roomId ->
            (project.rooms[project.roomKey(roomId)]?.asmRoomPropertiesSourceVersion ?: 0) != 0
        }
        if (managed.isNotEmpty()) {
            return failure(
                "source_managed_rooms",
                "Room music is source-managed for ${managed.joinToString { formatRoomId(it) }}; no project data was changed.",
            )
        }

        val roomIds = targetRooms.map(Pair<Int, SmeditRoomSummary>::first)
        val stateCount = roomIds.sumOf { roomId ->
            maxOf(1, roomDetails[roomId]?.stateCount ?: 0, project.rooms[project.roomKey(roomId)]?.effectiveStates?.size ?: 0)
        }
        val warnings = buildList {
            if (unmatchedAreas.isNotEmpty()) add("No rooms matched excluded area(s): ${unmatchedAreas.joinToString()}.")
            if (uninspectableRooms.isNotEmpty()) {
                add("SMEDIT could not inspect ${uninspectableRooms.size} room(s), so they were protected from this bulk write.")
            }
            if (overwriteSpecialStateMusic && stateSensitiveRooms.isNotEmpty()) {
                add(
                    "Explicit override will flatten distinct boss/event/state music in ${stateSensitiveRooms.size} room(s): " +
                        stateSensitiveRooms.take(8).joinToString { (id, room) -> "${room.name} (${formatRoomId(id)})" } +
                        if (stateSensitiveRooms.size > 8) " …" else "",
                )
            }
            if (overwriteAudioSensitiveMusic && audioSensitiveRooms.isNotEmpty()) {
                add(
                    "Explicit override will replace native Tourian music in ${audioSensitiveRooms.size} room(s). " +
                        "Metroid, Baby Metroid, Mother Brain, and escape sounds depend on Tourian's song instruments " +
                        "and may glitch or deadlock door transitions.",
                )
            }
        }
        val presentation = SmeditMutationPlan(
            id = UUID.randomUUID().toString(),
            toolName = toolName,
            title = "Set room music to ${formatByte(musicData)}:${formatByte(musicTrack)}",
            musicData = musicData,
            musicTrack = musicTrack,
            affectedRoomCount = roomIds.size,
            affectedStateCount = stateCount,
            affectedRoomPreview = targetRooms.take(12).map { (id, room) -> "${room.name} (${formatRoomId(id)})" },
            excludedRoomIds = excluded.distinct().map(::formatRoomId),
            excludedAreas = normalizedAreas,
            warnings = warnings,
            protectedRoomIds = protectedRooms.map { (id, _) -> formatRoomId(id) },
            protectedRoomPreview = protectedRooms.take(12).map { (id, room) -> "${room.name} (${formatRoomId(id)})" },
        )
        val plan = RoomMusicPlan(
            presentation = presentation,
            projectId = project.projectId,
            roomIds = roomIds,
            before = roomIds.map(::snapshot),
        )
        pending = plan
        return SmeditMutationPlanningResult(
            plan = presentation,
            result = SmeditToolResult(
                output = buildJsonObject {
                    put("ok", true)
                    put("status", "approval_required")
                    put("plan_id", presentation.id)
                    put("tool", toolName)
                    put("music_data", musicData)
                    put("music_track", musicTrack)
                    put("affected_room_count", roomIds.size)
                    put("affected_state_count", stateCount)
                    put("affected_room_ids_preview", JsonArray(roomIds.take(25).map { JsonPrimitive(formatRoomId(it)) }))
                    put("excluded_room_ids", JsonArray(presentation.excludedRoomIds.map(::JsonPrimitive)))
                    put("excluded_areas", JsonArray(normalizedAreas.map(::JsonPrimitive)))
                    put("protected_room_ids", JsonArray(presentation.protectedRoomIds.map(::JsonPrimitive)))
                    put("protected_room_preview", JsonArray(presentation.protectedRoomPreview.map(::JsonPrimitive)))
                    put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
                    put("message", "The exact SMEDIT plan is waiting for user approval. No project data has changed.")
                },
                isError = false,
            ),
        )
    }

    private fun planPatch(arguments: JsonObject) = try {
        planPatchChecked(arguments)
    } catch (problem: Exception) {
        failure("patch_plan_failed", problem.message ?: "The patch plan could not be created.")
    }

    private fun planPatchChecked(arguments: JsonObject): SmeditMutationPlanningResult {
        val summary = workspace.workspaceSummary()
        if (!summary.projectLoaded) return failure("workspace_unavailable", "Open a project or editable ROM before changing patches.")
        if (summary.readOnly) return failure("workspace_read_only", "The current workspace is read-only.")
        val project = editorState.project
        if (project.asmWorkspace.enabled && project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) {
            return failure(
                "asm_source_unsupported",
                "Approval-backed patch writes in ASM-source mode are not yet supported. No project data was changed.",
            )
        }
        val identifier = arguments.string("patch")?.trim()?.takeIf(String::isNotEmpty)
            ?: return failure("invalid_arguments", "patch is required.")
        val patch = resolveProjectPatch(project, identifier)
            ?: return failure("patch_not_found", "No patch uniquely matches '$identifier'.")
        if (patch.configType == INTRO_SEQUENCE_CONFIG_TYPE) {
            return failure(
                "patch_unsupported",
                "The Intro Sequence Manager has additional project-owned data and is not yet safe for AI patch mutation.",
            )
        }
        val requestedEnabled = arguments.optionalBoolean("enabled")
            ?: if ("enabled" in arguments) return failure("invalid_arguments", "enabled must be true or false.") else null
        val requestedValues = arguments.integerMap("values")
            ?: return failure("invalid_arguments", "values must be an object whose values are integers.")
        if (requestedEnabled == null && requestedValues.isEmpty()) {
            return failure("invalid_arguments", "Provide enabled, one or more configuration values, or both.")
        }
        val schema = SmeditPatchCatalog.configSchema(patch.configType ?: patch.id)
        if (requestedValues.isNotEmpty() && schema == null) {
            return failure("patch_not_configurable", "'${patch.name}' does not expose typed configuration fields.")
        }
        val fields = schema?.fields.orEmpty().associateBy { it.key }
        val unknownKeys = requestedValues.keys - fields.keys
        if (unknownKeys.isNotEmpty()) {
            return failure(
                "unknown_config_field",
                "Unknown ${patch.name} configuration field(s): ${unknownKeys.sorted().joinToString()}. Use get_patch for canonical keys.",
            )
        }
        requestedValues.forEach { (key, value) ->
            val field = fields.getValue(key)
            val minimum = field.logicalMin ?: field.min
            val maximum = field.logicalMax ?: field.max
            if (value !in minimum..maximum) {
                return failure("config_out_of_range", "${field.label} must be from $minimum through $maximum.")
            }
            if (field.choices.isNotEmpty() && field.choices.none { it.value == value }) {
                return failure(
                    "invalid_config_choice",
                    "${field.label} must be one of ${field.choices.joinToString { "${it.label}=${it.value}" }}.",
                )
            }
        }
        if (patch.configType == CONTROLLER_CONFIG_TYPE && requestedValues.isNotEmpty()) {
            return failure(
                "configuration_unsupported",
                "Controller bindings require collision-aware action swaps and are not enabled for AI mutation yet.",
            )
        }
        if (patch.configType == BOMB_CONFIG_TYPE && BOMB_MAX_ACTIVE_KEY in requestedValues &&
            BOMB_COOLDOWN_FRAMES_KEY !in requestedValues
        ) {
            return failure(
                "configuration_requires_companion_field",
                "Changing the bomb slot limit also derives cooldown. Include cooldown_frames explicitly so the approval plan is exact.",
            )
        }

        var afterConfigValue = patch.configValue
        val afterConfigData = patch.configData.orEmpty().toMutableMap()
        val normalizedValues = linkedMapOf<String, Int>()
        when (patch.configType) {
            CERES_ESCAPE_CONFIG_TYPE, ZEBES_ESCAPE_CONFIG_TYPE -> {
                val aliases = requestedValues.filterKeys { it in CONFIG_VALUE_ALIASES }
                if (aliases.values.distinct().size > 1) {
                    return failure("conflicting_config_aliases", "Escape timer aliases must specify the same value.")
                }
                aliases.values.singleOrNull()?.let { seconds ->
                    afterConfigValue = seconds
                    normalizedValues["seconds"] = seconds
                }
            }
            SHORT_CHARGE_CONFIG_TYPE -> {
                val aliases = requestedValues.filterKeys { it == SHORT_CHARGE_STAGES_KEY || it == SHORT_CHARGE_TAPS_KEY }
                if (aliases.values.distinct().size > 1) {
                    return failure("conflicting_config_aliases", "Short Charge stages and taps must specify the same value.")
                }
                aliases.values.singleOrNull()?.let { stages ->
                    afterConfigValue = null
                    afterConfigData.keys.removeAll { it == SHORT_CHARGE_STAGES_KEY || it == SHORT_CHARGE_TAPS_KEY }
                    afterConfigData[SHORT_CHARGE_STAGES_KEY] = stages
                    normalizedValues[SHORT_CHARGE_STAGES_KEY] = stages
                }
            }
            else -> {
                afterConfigData.putAll(requestedValues)
                normalizedValues.putAll(requestedValues)
            }
        }
        val afterEnabled = requestedEnabled ?: if (requestedValues.isNotEmpty()) true else patch.enabled
        validatePatchTransition(patch, afterEnabled)
        val before = patchSnapshot(patch)
        val peerEnabled = before.exclusivePeerEnabled.mapValues { (_, enabled) ->
            if (afterEnabled) false else enabled
        }
        val after = PatchSnapshot(
            patchId = patch.id,
            target = PatchFields(afterEnabled, afterConfigValue, afterConfigData.toMap()),
            exclusivePeerEnabled = peerEnabled,
        )
        if (before == after) return failure("no_changes", "${patch.name} already has the requested state and values.")
        val valueChanges = normalizedValues.map { (key, value) ->
            val field = fields[key] ?: fields["seconds"]
            val beforeValue = when {
                key == "seconds" -> patch.configValue ?: field?.defaultValue
                else -> patch.configData.orEmpty()[key] ?: field?.defaultValue
            }
            SmeditPatchValueChange(key, field?.label ?: key, beforeValue, value)
        }
        val automaticallyDisabled = before.exclusivePeerEnabled.filterValues { it }
            .filterKeys { after.exclusivePeerEnabled[it] == false }
            .keys.mapNotNull { id -> project.patches.firstOrNull { it.id == id }?.name }
        val warnings = if (automaticallyDisabled.isEmpty()) emptyList() else listOf(
            "Enabling ${patch.name} also disables exclusive variant(s): ${automaticallyDisabled.joinToString()}.",
        )
        val title = when {
            requestedValues.isNotEmpty() -> "Configure ${patch.name}"
            afterEnabled -> "Enable ${patch.name}"
            else -> "Disable ${patch.name}"
        }
        val patchPresentation = SmeditPatchMutationPlan(
            patchId = patch.id,
            patchName = patch.name,
            configType = patch.configType,
            beforeEnabled = patch.enabled,
            afterEnabled = afterEnabled,
            valueChanges = valueChanges,
            automaticallyDisabledPatches = automaticallyDisabled,
        )
        val presentation = SmeditMutationPlan(
            id = UUID.randomUUID().toString(),
            toolName = CONFIGURE_PATCH,
            title = title,
            musicData = 0,
            musicTrack = 0,
            affectedRoomCount = 0,
            affectedStateCount = 0,
            affectedRoomPreview = emptyList(),
            excludedRoomIds = emptyList(),
            excludedAreas = emptyList(),
            warnings = warnings,
            kind = SmeditMutationPlanKind.PATCH,
            patch = patchPresentation,
        )
        pending = PatchPlan(presentation, project.projectId, patch.id, before, after)
        return SmeditMutationPlanningResult(
            plan = presentation,
            result = SmeditToolResult(
                output = buildJsonObject {
                    put("ok", true)
                    put("status", "approval_required")
                    put("plan_id", presentation.id)
                    put("tool", CONFIGURE_PATCH)
                    put("patch_id", patch.id)
                    put("patch_name", patch.name)
                    patch.configType?.let { put("config_type", it) }
                    put("before_enabled", patch.enabled)
                    put("after_enabled", afterEnabled)
                    put("value_changes", JsonArray(valueChanges.map { change ->
                        buildJsonObject {
                            put("key", change.key)
                            put("label", change.label)
                            change.before?.let { put("before", it) }
                            change.after?.let { put("after", it) }
                        }
                    }))
                    put("automatically_disabled_patches", JsonArray(automaticallyDisabled.map(::JsonPrimitive)))
                    put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
                    put("message", "The exact SMEDIT patch plan is waiting for user approval. No project data has changed.")
                },
                isError = false,
            ),
        )
    }

    private fun planRoomFx(arguments: JsonObject): SmeditMutationPlanningResult = try {
        planRoomFxChecked(arguments)
    } catch (problem: Exception) {
        failure("fx_plan_failed", problem.message ?: "The room FX plan could not be created.")
    }

    private fun planRoomFxChecked(arguments: JsonObject): SmeditMutationPlanningResult {
        val summary = workspace.workspaceSummary()
        if (!summary.projectLoaded) return failure("workspace_unavailable", "Open a project or editable ROM before changing room FX.")
        if (summary.readOnly) return failure("workspace_read_only", "The current room catalog is read-only.")
        val project = editorState.project
        if (project.asmWorkspace.enabled && project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) {
            return failure(
                "asm_source_unsupported",
                "Approval-backed room FX writes in ASM-source mode are not yet supported. No project data was changed.",
            )
        }
        val currentParser = parser()
            ?: return failure("workspace_unavailable", "The current ROM parser is unavailable. Reopen the project before changing room FX.")

        val numericRanges = linkedMapOf(
            "fx_type" to 0..0x2C,
            "liquid_surface_start" to 0..0xFFFF,
            "liquid_surface_new" to 0..0xFFFF,
            "liquid_speed" to 0..0xFFFF,
            "liquid_delay" to 0..0xFF,
            "fx_bit_a" to 0..0xFF,
            "fx_bit_b" to 0..0xFF,
            "fx_bit_c" to 0..0xFF,
            "palette_fx_bitflags" to 0..0xFF,
            "tile_anim_bitflags" to 0..0xFF,
            "palette_blend" to 0..0xFF,
        )
        val numeric = linkedMapOf<String, Int>()
        numericRanges.forEach { (name, range) ->
            val element = arguments[name] ?: return@forEach
            val value = (element as? JsonPrimitive)?.intOrNull
                ?: return failure("invalid_arguments", "$name must be an integer from ${range.first} through ${range.last}.")
            if (value !in range) return failure("invalid_arguments", "$name must be an integer from ${range.first} through ${range.last}.")
            numeric[name] = value
        }
        numeric["fx_type"]?.let { type ->
            if (type % 2 != 0) return failure("invalid_arguments", "fx_type must be an even native dispatch offset from 0 through 44.")
        }
        val requestedUpdateFields = arguments.stringList("update_fields")
            ?: return failure("invalid_arguments", "update_fields must be an array of canonical FX field names.")
        val updateFieldsProvided = "update_fields" in arguments
        val allowedUpdateFields = numericRanges.keys + "liquid_options"
        val updateFields = if (updateFieldsProvided) {
            requestedUpdateFields.toSet()
        } else {
            buildSet {
                addAll(numeric.keys)
                if ("liquid_options" in arguments) add("liquid_options")
            }
        }
        if (updateFields.isEmpty()) {
            return failure("invalid_arguments", "update_fields must name at least one FX field to change.")
        }
        val unknownUpdateFields = updateFields - allowedUpdateFields
        if (unknownUpdateFields.isNotEmpty()) {
            return failure(
                "invalid_arguments",
                "Unknown update_fields value(s): ${unknownUpdateFields.sorted().joinToString()}.",
            )
        }
        val missingUpdateValues = updateFields.filter { field ->
            field !in arguments
        }
        if (missingUpdateValues.isNotEmpty()) {
            return failure(
                "invalid_arguments",
                "update_fields requires a value for: ${missingUpdateValues.sorted().joinToString()}.",
            )
        }
        val liquidOptions = arguments.stringListOrNull("liquid_options")
            ?: return failure("invalid_arguments", "liquid_options must be an array of canonical option names.")
        val semanticOptionsProvided = "liquid_options" in updateFields
        val unknownOptions = liquidOptions - NativeLiquidOptions.semanticNames.keys
        if (unknownOptions.isNotEmpty()) {
            return failure(
                "invalid_liquid_option",
                "Unknown liquid option(s): ${unknownOptions.joinToString()}. Use ${NativeLiquidOptions.semanticNames.keys.joinToString()}.",
            )
        }
        val semanticBitC = liquidOptions.distinct().fold(0) { value, name ->
            value or NativeLiquidOptions.semanticNames.getValue(name)
        }
        val requested = FxChange(
            fxType = numeric["fx_type"].takeIf { "fx_type" in updateFields },
            liquidSurfaceStart = numeric["liquid_surface_start"].takeIf { "liquid_surface_start" in updateFields },
            liquidSurfaceNew = numeric["liquid_surface_new"].takeIf { "liquid_surface_new" in updateFields },
            liquidSpeed = numeric["liquid_speed"].takeIf { "liquid_speed" in updateFields },
            liquidDelay = numeric["liquid_delay"].takeIf { "liquid_delay" in updateFields },
            fxBitA = numeric["fx_bit_a"].takeIf { "fx_bit_a" in updateFields },
            fxBitB = numeric["fx_bit_b"].takeIf { "fx_bit_b" in updateFields },
            fxBitC = when {
                semanticOptionsProvided -> semanticBitC
                "fx_bit_c" in updateFields -> numeric["fx_bit_c"]
                else -> null
            },
            paletteFxBitflags = numeric["palette_fx_bitflags"].takeIf { "palette_fx_bitflags" in updateFields },
            tileAnimBitflags = numeric["tile_anim_bitflags"].takeIf { "tile_anim_bitflags" in updateFields },
            paletteBlend = numeric["palette_blend"].takeIf { "palette_blend" in updateFields },
        )
        if (requested == FxChange()) {
            return failure("invalid_arguments", "Provide at least one FX field or liquid_options value to change.")
        }
        val requestedBitC = requested.fxBitC
        if (requestedBitC != null && requestedBitC and NativeLiquidOptions.LARGE_TIDE != 0 &&
            requestedBitC and NativeLiquidOptions.SMALL_TIDE != 0
        ) {
            return failure("conflicting_tides", "Choose Large Tide or Small Tide, not both. Native Small Tide silently wins when both bits are set.")
        }

        val requestedIds = arguments.stringList("room_ids")
            ?: return failure("invalid_arguments", "room_ids must be an array of hexadecimal room ids.")
        val includedAreas = arguments.stringList("areas")
            ?: return failure("invalid_arguments", "areas must be an array of area names.")
        val excludedIds = arguments.stringList("exclude_room_ids")
            ?: return failure("invalid_arguments", "exclude_room_ids must be an array of hexadecimal room ids.")
        val excludedAreas = arguments.stringList("exclude_areas")
            ?: return failure("invalid_arguments", "exclude_areas must be an array of area names.")
        val requestedStateIds = arguments.stringList("state_ids")
            ?: return failure("invalid_arguments", "state_ids must be an array of stable state ids.")
        val rawStateTargets = arguments.stringList("state_targets")
            ?: return failure("invalid_arguments", "state_targets must be an array of room-id:state-id values.")
        if (requestedStateIds.isNotEmpty() && rawStateTargets.isNotEmpty()) {
            return failure("invalid_arguments", "Use state_ids or state_targets, not both.")
        }
        val stateTargets = rawStateTargets.map { raw ->
            val parts = raw.split(':', limit = 2)
            val roomId = parts.getOrNull(0)?.let(::parseRoomId)
            val stateId = parts.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
            if (roomId == null || stateId == null) {
                return failure("invalid_arguments", "state_targets contains '$raw'; expected a value such as 0x91F8:state-2.")
            }
            roomId to stateId
        }.toSet()
        val onlyIfTypes = arguments.intList("only_if_fx_types")
            ?: return failure("invalid_arguments", "only_if_fx_types must contain even native FX types from 0 through 44.")
        if (onlyIfTypes.any { it !in 0..0x2C || it % 2 != 0 }) {
            return failure("invalid_arguments", "only_if_fx_types must contain even native FX types from 0 through 44.")
        }
        val includeConditional = arguments.optionalBoolean("include_conditional_states")
            ?: if ("include_conditional_states" in arguments) {
                return failure("invalid_arguments", "include_conditional_states must be true or false.")
            } else false

        val catalog = workspace.rooms().distinctBy { parseRoomId(it.id) }.mapNotNull { room ->
            parseRoomId(room.id)?.let { it to room }
        }
        val catalogById = catalog.toMap()
        if (catalogById.isEmpty()) return failure("no_rooms", "The current workspace has no writable rooms.")
        val selected = parseIds(requestedIds) ?: return invalidRoomIds("room_ids")
        val excluded = parseIds(excludedIds) ?: return invalidRoomIds("exclude_room_ids")
        val unknown = (selected + excluded + stateTargets.map(Pair<Int, String>::first)).filterNot(catalogById::containsKey).distinct()
        if (unknown.isNotEmpty()) {
            return failure("room_not_found", "Unknown room id(s): ${unknown.joinToString { formatRoomId(it) }}.")
        }
        val normalizedIncludedAreas = includedAreas.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)
        val normalizedExcludedAreas = excludedAreas.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)
        val unmatchedAreas = (normalizedIncludedAreas + normalizedExcludedAreas).filter { requestedArea ->
            catalog.none { (_, room) -> room.area?.equals(requestedArea, ignoreCase = true) == true }
        }.distinctBy(String::lowercase)
        val candidateIds = when {
            selected.isNotEmpty() -> selected.toSet()
            stateTargets.isNotEmpty() -> stateTargets.map(Pair<Int, String>::first).toSet()
            else -> catalog.map(Pair<Int, SmeditRoomSummary>::first).toSet()
        }
        val candidates = catalog.filter { (roomId, room) ->
            roomId in candidateIds && roomId !in excluded &&
                (normalizedIncludedAreas.isEmpty() || normalizedIncludedAreas.any { it.equals(room.area, ignoreCase = true) }) &&
                normalizedExcludedAreas.none { it.equals(room.area, ignoreCase = true) }
        }
        if (candidates.isEmpty()) return failure("empty_scope", "The requested room and area filters leave no rooms to change.")
        if (requestedStateIds.isNotEmpty() || stateTargets.isNotEmpty()) {
            val knownTargets = candidates.flatMap { (roomId, _) ->
                workspace.room(roomId)?.states.orEmpty().map { state -> roomId to state.id }
            }.toSet()
            val missingStateIds = requestedStateIds.toSet() - knownTargets.map(Pair<Int, String>::second).toSet()
            if (missingStateIds.isNotEmpty()) {
                return failure("state_not_found", "No selected room contains state id(s): ${missingStateIds.sorted().joinToString()}.")
            }
            val missing = stateTargets - knownTargets
            if (missing.isNotEmpty()) {
                return failure(
                    "state_not_found",
                    "No selected room contains state target(s): ${missing.sortedWith(compareBy({ it.first }, { it.second })).joinToString { (id, state) -> "${formatRoomId(id)}:$state" }}.",
                )
            }
        }

        val before = mutableListOf<RoomModelSnapshot>()
        val after = mutableListOf<RoomModelSnapshot>()
        val affectedStatePreview = mutableListOf<String>()
        val protectedRooms = mutableListOf<Pair<Int, SmeditRoomSummary>>()
        var protectedConditionalStateCount = 0
        for ((roomId, summaryRoom) in candidates) {
            val live = project.rooms[project.roomKey(roomId)]
            val inspection = currentParser.inspectRoomStates(roomId)
            if ((live?.asmFxSourceVersion ?: 0) != 0 || !inspection.isComplete) {
                protectedRooms += roomId to summaryRoom
                continue
            }
            val staged = snapshotRoomForResize(live ?: RoomEdits(roomId))
            staged.ensureStateManifest(currentParser)
            if (staged.states.any { state -> sourceFxPointer(staged, state, currentParser) == MOTHER_BRAIN_RUNTIME_FX_POINTER }) {
                protectedRooms += roomId to summaryRoom
                continue
            }
            var changedStates = 0
            for (state in staged.effectiveStates) {
                if (requestedStateIds.isNotEmpty() && state.id !in requestedStateIds) continue
                if (stateTargets.isNotEmpty() && (roomId to state.id) !in stateTargets) continue
                if (!includeConditional && state.effectiveCondition.kind != ProjectRoomStateConditionKind.DEFAULT) {
                    protectedConditionalStateCount++
                    continue
                }
                val current = effectiveDefaultFx(staged, state, currentParser) ?: continue
                if (onlyIfTypes.isNotEmpty() && current.fxType !in onlyIfTypes) continue
                val originalChange = state.fxChange
                state.fxChange = originalChange.merge(requested)
                val updated = effectiveDefaultFx(staged, state, currentParser) ?: current
                val tideMask = NativeLiquidOptions.LARGE_TIDE or NativeLiquidOptions.SMALL_TIDE
                if (updated.fxBitC and NativeLiquidOptions.LARGE_TIDE != 0 &&
                    updated.fxBitC and NativeLiquidOptions.SMALL_TIDE != 0
                ) {
                    return failure(
                        "conflicting_tides",
                        "${summaryRoom.name} (${formatRoomId(roomId)}) state ${state.id} would enable both tide modes. Choose one.",
                    )
                }
                if (updated.fxBitC and tideMask != 0 && updated.liquidSurfaceStart == 0xFFFF && updated.liquidSurfaceNew == 0xFFFF) {
                    return failure(
                        "unsafe_hidden_liquid_tide",
                        "${summaryRoom.name} (${formatRoomId(roomId)}) state ${state.id} would combine a tide with \$FFFF/\$FFFF liquid heights, which can wrap liquid across the room. Set explicit heights or disable tides.",
                    )
                }
                if (updated == current) {
                    state.fxChange = originalChange
                    continue
                }
                changedStates++
                affectedStatePreview += "${summaryRoom.name} (${formatRoomId(roomId)}) · ${state.id} · ${state.effectiveCondition.kind.name.lowercase()}"
            }
            if (changedStates > 0) {
                before += modelSnapshot(roomId)
                after += RoomModelSnapshot(roomId, staged)
            }
        }
        if (after.isEmpty()) {
            val reason = if (protectedRooms.isNotEmpty()) {
                "All matching states are protected, filtered out, or already have the requested values."
            } else "All matching states already have the requested values or were excluded by the state/FX-type filters."
            return failure("no_changes", reason)
        }

        val changedRoomIds = after.map(RoomModelSnapshot::roomId)
        val requestedChanges = describeFxChange(requested, liquidOptions.takeIf { semanticOptionsProvided })
        val warnings = buildList {
            if (unmatchedAreas.isNotEmpty()) add("No rooms matched area filter(s): ${unmatchedAreas.joinToString()}.")
            if (protectedRooms.isNotEmpty()) {
                add("Protected ${protectedRooms.size} room(s) with ASM-managed FX, unreadable state graphs, or runtime-indexed Mother Brain FX.")
            }
            if (protectedConditionalStateCount > 0) {
                add("Protected $protectedConditionalStateCount conditional boss/event/door state(s). Set include_conditional_states=true only after reviewing them with list_room_fx.")
            }
            if (includeConditional) add("Explicit override includes conditional boss/event/door states.")
        }
        val presentation = SmeditMutationPlan(
            id = UUID.randomUUID().toString(),
            toolName = SET_ROOM_FX,
            title = "Configure room FX",
            musicData = 0,
            musicTrack = 0,
            affectedRoomCount = changedRoomIds.size,
            affectedStateCount = affectedStatePreview.size,
            affectedRoomPreview = affectedStatePreview.take(12),
            excludedRoomIds = excluded.distinct().map(::formatRoomId),
            excludedAreas = normalizedExcludedAreas,
            warnings = warnings,
            protectedRoomIds = protectedRooms.map { (id, _) -> formatRoomId(id) },
            protectedRoomPreview = protectedRooms.take(12).map { (id, room) -> "${room.name} (${formatRoomId(id)})" },
            kind = SmeditMutationPlanKind.ROOM_FX,
            fx = SmeditRoomFxMutationPlan(requestedChanges, affectedStatePreview.take(20)),
        )
        val plan = RoomFxPlan(presentation, project.projectId, before, after)
        pending = plan
        return SmeditMutationPlanningResult(
            plan = presentation,
            result = SmeditToolResult(
                output = buildJsonObject {
                    put("ok", true)
                    put("status", "approval_required")
                    put("plan_id", presentation.id)
                    put("tool", SET_ROOM_FX)
                    put("affected_room_count", presentation.affectedRoomCount)
                    put("affected_state_count", presentation.affectedStateCount)
                    put("requested_changes", JsonArray(requestedChanges.map(::JsonPrimitive)))
                    put("affected_state_preview", JsonArray(affectedStatePreview.take(25).map(::JsonPrimitive)))
                    put("protected_room_ids", JsonArray(presentation.protectedRoomIds.map(::JsonPrimitive)))
                    put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
                    put("message", "The exact SMEDIT FX plan is waiting for user approval. No project data has changed.")
                },
                isError = false,
            ),
        )
    }

    override fun approve(planId: String): SmeditMutationTransition {
        val plan = pending?.takeIf { it.presentation.id == planId }
            ?: error("The pending AI plan is no longer available.")
        return when (plan) {
            is RoomMusicPlan -> {
                requireProject(plan.projectId)
                requireSnapshots(plan.before, "approval")
                val rollback = plan.roomIds.map(::snapshot)
                try {
                    plan.roomIds.forEach { roomId -> setMusic(roomId, plan.presentation.musicData, plan.presentation.musicTrack) }
                } catch (problem: Throwable) {
                    rollback.forEach(::restore)
                    throw problem
                }
                undoStack += AppliedRoomMusicAction(plan, plan.roomIds.map(::snapshot))
                redoStack.clear()
                pending = null
                editorState.recordExternalRoomMutation(plan.roomIds)
                transition(plan.presentation, "Applied")
            }
            is PatchPlan -> {
                requireProject(plan.projectId)
                requirePatchSnapshot(plan.before, "approval")
                val target = requirePatch(plan.patchId)
                validatePatchTransition(target, plan.after.target.enabled)
                val rollback = patchSnapshot(target)
                try {
                    restorePatchSnapshot(plan.after)
                    check(patchSnapshot(requirePatch(plan.patchId)) == plan.after) {
                        "Patch application did not produce the approved state."
                    }
                } catch (problem: Throwable) {
                    restorePatchSnapshot(rollback)
                    throw problem
                }
                undoStack += AppliedPatchAction(plan)
                redoStack.clear()
                pending = null
                editorState.recordExternalPatchMutation()
                transition(plan.presentation, "Applied")
            }
            is RoomFxPlan -> {
                requireProject(plan.projectId)
                requireRoomModels(plan.before, "approval")
                val rollback = plan.before.map { modelSnapshot(it.roomId) }
                try {
                    restoreRoomModels(plan.after)
                } catch (problem: Throwable) {
                    restoreRoomModels(rollback)
                    throw problem
                }
                undoStack += AppliedRoomFxAction(plan)
                redoStack.clear()
                pending = null
                editorState.recordExternalRoomMutation(plan.after.map(RoomModelSnapshot::roomId))
                transition(plan.presentation, "Applied")
            }
        }
    }

    override fun reject(planId: String) {
        val plan = pending?.takeIf { it.presentation.id == planId }
            ?: error("The pending AI plan is no longer available.")
        pending = null
        check(plan.presentation.id == planId)
    }

    override fun undo(): SmeditMutationTransition {
        val action = undoStack.lastOrNull() ?: error("There is no applied AI action to undo.")
        when (action) {
            is AppliedRoomMusicAction -> {
                requireProject(action.plan.projectId)
                requireSnapshots(action.after, "undo")
                val rollback = action.plan.roomIds.map(::snapshot)
                try {
                    action.plan.before.forEach(::restore)
                } catch (problem: Throwable) {
                    rollback.forEach(::restore)
                    throw problem
                }
                editorState.recordExternalRoomMutation(action.plan.roomIds)
            }
            is AppliedPatchAction -> {
                requireProject(action.projectId)
                requirePatchSnapshot(action.plan.after, "undo")
                val target = requirePatch(action.plan.patchId)
                validatePatchTransition(target, action.plan.before.target.enabled)
                val rollback = patchSnapshot(target)
                try {
                    restorePatchSnapshot(action.plan.before)
                } catch (problem: Throwable) {
                    restorePatchSnapshot(rollback)
                    throw problem
                }
                editorState.recordExternalPatchMutation()
            }
            is AppliedRoomFxAction -> {
                requireProject(action.projectId)
                requireRoomModels(action.plan.after, "undo")
                val rollback = action.plan.after.map { modelSnapshot(it.roomId) }
                try {
                    restoreRoomModels(action.plan.before)
                } catch (problem: Throwable) {
                    restoreRoomModels(rollback)
                    throw problem
                }
                editorState.recordExternalRoomMutation(action.plan.before.map(RoomModelSnapshot::roomId))
            }
        }
        undoStack.removeAt(undoStack.lastIndex)
        redoStack += action
        return transition(action.presentation, "Undid")
    }

    override fun redo(): SmeditMutationTransition {
        val action = redoStack.lastOrNull() ?: error("There is no AI action to redo.")
        when (action) {
            is AppliedRoomMusicAction -> {
                requireProject(action.plan.projectId)
                requireSnapshots(action.plan.before, "redo")
                val rollback = action.plan.roomIds.map(::snapshot)
                try {
                    action.after.forEach(::restore)
                } catch (problem: Throwable) {
                    rollback.forEach(::restore)
                    throw problem
                }
                editorState.recordExternalRoomMutation(action.plan.roomIds)
            }
            is AppliedPatchAction -> {
                requireProject(action.projectId)
                requirePatchSnapshot(action.plan.before, "redo")
                val target = requirePatch(action.plan.patchId)
                validatePatchTransition(target, action.plan.after.target.enabled)
                val rollback = patchSnapshot(target)
                try {
                    restorePatchSnapshot(action.plan.after)
                } catch (problem: Throwable) {
                    restorePatchSnapshot(rollback)
                    throw problem
                }
                editorState.recordExternalPatchMutation()
            }
            is AppliedRoomFxAction -> {
                requireProject(action.projectId)
                requireRoomModels(action.plan.before, "redo")
                val rollback = action.plan.before.map { modelSnapshot(it.roomId) }
                try {
                    restoreRoomModels(action.plan.after)
                } catch (problem: Throwable) {
                    restoreRoomModels(rollback)
                    throw problem
                }
                editorState.recordExternalRoomMutation(action.plan.after.map(RoomModelSnapshot::roomId))
            }
        }
        redoStack.removeAt(redoStack.lastIndex)
        undoStack += action
        return transition(action.presentation, "Redid")
    }

    private fun requireProject(expectedId: String) {
        check(editorState.project.projectId == expectedId) {
            "The project changed after this AI action was planned. Nothing was changed."
        }
    }

    private fun requireSnapshots(expected: List<RoomMusicSnapshot>, operation: String) {
        val stale = expected.filterNot { it.relevantEquals(snapshot(it.roomId)) }
        check(stale.isEmpty()) {
            "AI $operation was blocked because newer room-music edits affect ${stale.joinToString { formatRoomId(it.roomId) }}. Nothing was changed."
        }
    }

    private fun RoomMusicSnapshot.relevantEquals(other: RoomMusicSnapshot): Boolean =
        roomId == other.roomId && common == other.common && states == other.states

    private fun modelSnapshot(roomId: Int): RoomModelSnapshot = RoomModelSnapshot(
        roomId,
        editorState.project.rooms[editorState.project.roomKey(roomId)]?.let(::snapshotRoomForResize),
    )

    private fun requireRoomModels(expected: List<RoomModelSnapshot>, operation: String) {
        val stale = expected.filter { it != modelSnapshot(it.roomId) }
        check(stale.isEmpty()) {
            "AI $operation was blocked because newer room edits affect ${stale.joinToString { formatRoomId(it.roomId) }}. Nothing was changed."
        }
    }

    private fun restoreRoomModels(snapshots: List<RoomModelSnapshot>) {
        val project = editorState.project
        snapshots.forEach { snapshot ->
            val key = project.roomKey(snapshot.roomId)
            if (snapshot.room == null) project.rooms.remove(key)
            else project.rooms[key] = snapshotRoomForResize(snapshot.room)
        }
    }

    private fun snapshot(roomId: Int): RoomMusicSnapshot {
        val room = editorState.project.rooms[editorState.project.roomKey(roomId)]
        return RoomMusicSnapshot(
            roomId = roomId,
            wasPresent = room != null,
            common = room?.stateDataChange.musicFields(),
            states = room?.states.orEmpty().map { state -> StateMusicFields(state.id, state.stateDataChange.musicFields()) },
        )
    }

    private fun setMusic(roomId: Int, data: Int, track: Int) {
        val room = editorState.project.getOrCreateRoom(roomId)
        room.stateDataChange = room.stateDataChange.withMusic(data, track)
        room.states.forEach { state -> state.stateDataChange = state.stateDataChange.withMusic(null, null) }
    }

    private fun restore(target: RoomMusicSnapshot) {
        val project = editorState.project
        val key = project.roomKey(target.roomId)
        val room = project.rooms[key] ?: RoomEdits(target.roomId).also { project.rooms[key] = it }
        room.stateDataChange = room.stateDataChange.withMusic(target.common.data, target.common.track)
        val targetStates = target.states.associateBy(StateMusicFields::id)
        room.states.forEach { state ->
            val music = targetStates.getValue(state.id).music
            state.stateDataChange = state.stateDataChange.withMusic(music.data, music.track)
        }
        if (!target.wasPresent && !room.hasEdits) project.rooms.remove(key)
    }

    private fun StateDataChange?.musicFields() = MusicFields(this?.musicData, this?.musicTrack)

    private fun StateDataChange?.withMusic(data: Int?, track: Int?): StateDataChange? {
        val updated = StateDataChange(
            tileset = this?.tileset,
            musicData = data,
            musicTrack = track,
            bgScrolling = this?.bgScrolling,
        )
        return updated.takeUnless { it == StateDataChange() }
    }

    private fun requirePatch(patchId: String): SmPatch =
        editorState.project.patches.singleOrNull { it.id == patchId }
            ?: error("The target patch changed or was removed. Nothing was changed.")

    private fun patchSnapshot(patch: SmPatch): PatchSnapshot {
        val peers = patch.exclusiveGroup?.let { group ->
            editorState.project.patches.filter { it.id != patch.id && it.exclusiveGroup == group }
                .associate { it.id to it.enabled }
        }.orEmpty()
        return PatchSnapshot(
            patchId = patch.id,
            target = PatchFields(patch.enabled, patch.configValue, patch.configData.orEmpty().toMap()),
            exclusivePeerEnabled = peers,
        )
    }

    private fun requirePatchSnapshot(expected: PatchSnapshot, operation: String) {
        val current = patchSnapshot(requirePatch(expected.patchId))
        check(current == expected) {
            "AI $operation was blocked because newer edits affect the patch or its exclusive variants. Nothing was changed."
        }
    }

    private fun restorePatchSnapshot(snapshot: PatchSnapshot) {
        val patch = requirePatch(snapshot.patchId)
        val currentPeers = patch.exclusiveGroup?.let { group ->
            editorState.project.patches.filter { it.id != patch.id && it.exclusiveGroup == group }.associateBy { it.id }
        }.orEmpty()
        check(currentPeers.keys == snapshot.exclusivePeerEnabled.keys) {
            "The patch's exclusive variants changed. Nothing was changed."
        }
        patch.configValue = snapshot.target.configValue
        patch.configData = snapshot.target.configData.toMutableMap().takeIf { it.isNotEmpty() }
        patch.enabled = snapshot.target.enabled
        snapshot.exclusivePeerEnabled.forEach { (id, enabled) -> currentPeers.getValue(id).enabled = enabled }
    }

    private fun validatePatchTransition(patch: SmPatch, enabled: Boolean) {
        if (!enabled && patch.enabled) {
            patch.enabled = false
            val dependency = try {
                RomValidator.checkProjectCustomItemDependencies(editorState.project)
                    .firstOrNull { it.severity == RomValidator.Severity.ERROR }
            } finally {
                patch.enabled = true
            }
            require(dependency == null) { "Cannot disable '${patch.name}': ${dependency?.message}" }
        }
        if (enabled) {
            val incompatible = editorState.enabledPatchIncompatibilities(patch)
            require(incompatible.isEmpty()) {
                "'${patch.name}' cannot be enabled with ${incompatible.joinToString { "'${it.name}'" }}."
            }
        }
    }

    private fun transition(plan: SmeditMutationPlan, verb: String) = SmeditMutationTransition(
        planId = plan.id,
        summary = when (plan.kind) {
            SmeditMutationPlanKind.ROOM_MUSIC ->
                "$verb ${plan.title.lowercase()} for ${plan.affectedRoomCount} room(s) and ${plan.affectedStateCount} state(s)."
            SmeditMutationPlanKind.PATCH -> "$verb ${plan.title.lowercase()}."
            SmeditMutationPlanKind.ROOM_FX ->
                "$verb room FX for ${plan.affectedRoomCount} room(s) and ${plan.affectedStateCount} state(s)."
        },
    )

    private fun JsonObject.byte(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..255 }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.stringList(name: String): List<String>? {
        val value = get(name) ?: return emptyList()
        return (value as? JsonArray)?.map { element ->
            (element as? JsonPrimitive)?.contentOrNull ?: return null
        }
    }

    private fun JsonObject.stringListOrNull(name: String): List<String>? = stringList(name)

    private fun JsonObject.intList(name: String): List<Int>? {
        val value = get(name) ?: return emptyList()
        return (value as? JsonArray)?.map { element ->
            (element as? JsonPrimitive)?.intOrNull ?: return null
        }
    }

    private fun JsonObject.optionalBoolean(name: String): Boolean? =
        (get(name) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.integerMap(name: String): Map<String, Int>? {
        val value = get(name) ?: return emptyMap()
        val objectValue = value as? JsonObject ?: return null
        return objectValue.mapValues { (_, element) ->
            (element as? JsonPrimitive)?.intOrNull ?: return null
        }
    }

    private fun parseIds(values: List<String>): List<Int>? = values.map { raw ->
        parseRoomId(raw) ?: return null
    }.distinct()

    private fun parseRoomId(value: String): Int? {
        val normalized = value.trim().removePrefix("0x").removePrefix("0X").removePrefix("\$")
        return normalized.toIntOrNull(16)?.takeIf { it in 0..0xFFFF }
    }

    private fun invalidRoomIds(field: String) = failure("invalid_arguments", "$field contains a value that is not a 16-bit hexadecimal room id.")

    private fun failure(code: String, message: String) = SmeditMutationPlanningResult(
        SmeditToolResult(
            output = buildJsonObject {
                put("ok", false)
                put("error", buildJsonObject {
                    put("code", code)
                    put("message", message)
                })
            },
            isError = true,
        ),
    )

    private fun formatRoomId(roomId: Int): String = "0x" + roomId.toString(16).uppercase().padStart(4, '0')
    private fun formatByte(value: Int): String = "0x" + value.toString(16).uppercase().padStart(2, '0')

    private fun sourceFxPointer(room: RoomEdits, state: RoomStateEdits, parser: RomParser): Int? {
        val sourceIndex = state.baseSourceStateIndex() ?: return null
        val inspected = parser.inspectRoomStates(room.roomId).states.getOrNull(sourceIndex) ?: return null
        return inspected.stateDataPcOffset?.let(parser::readStateData)?.get("fxPtr")
    }

    private fun effectiveDefaultFx(room: RoomEdits, state: RoomStateEdits, parser: RomParser): RomParser.FxEntry? {
        val sourceIndex = state.baseSourceStateIndex() ?: return null
        val inspected = parser.inspectRoomStates(room.roomId).states.getOrNull(sourceIndex) ?: return null
        val fields = inspected.stateDataPcOffset?.let(parser::readStateData).orEmpty()
        val pointer = fields["fxPtr"] ?: return null
        val base = parser.parseFxEntries(pointer).lastOrNull(RomParser.FxEntry::isDefault) ?: emptyDefaultFxEntry()
        return base.withFxChange(room.fxChange).withFxChange(state.fxChange)
    }

    private fun FxChange?.merge(requested: FxChange): FxChange = FxChange(
        fxType = requested.fxType ?: this?.fxType,
        liquidSurfaceStart = requested.liquidSurfaceStart ?: this?.liquidSurfaceStart,
        liquidSurfaceNew = requested.liquidSurfaceNew ?: this?.liquidSurfaceNew,
        liquidSpeed = requested.liquidSpeed ?: this?.liquidSpeed,
        liquidDelay = requested.liquidDelay ?: this?.liquidDelay,
        fxBitA = requested.fxBitA ?: this?.fxBitA,
        fxBitB = requested.fxBitB ?: this?.fxBitB,
        fxBitC = requested.fxBitC ?: this?.fxBitC,
        paletteFxBitflags = requested.paletteFxBitflags ?: this?.paletteFxBitflags,
        tileAnimBitflags = requested.tileAnimBitflags ?: this?.tileAnimBitflags,
        paletteBlend = requested.paletteBlend ?: this?.paletteBlend,
    )

    private fun describeFxChange(change: FxChange, semanticOptions: List<String>?): List<String> = buildList {
        change.fxType?.let { add("FX type = $it") }
        change.liquidSurfaceStart?.let { add("Liquid start = ${formatWord(it)}") }
        change.liquidSurfaceNew?.let { add("Liquid target = ${formatWord(it)}") }
        change.liquidSpeed?.let { add("Liquid speed = ${formatWord(it)}") }
        change.liquidDelay?.let { add("Liquid delay = $it") }
        change.fxBitA?.let { add("FX bit A = ${formatByte(it)}") }
        change.fxBitB?.let { add("FX bit B = ${formatByte(it)}") }
        change.fxBitC?.let {
            add("Liquid options = ${semanticOptions?.joinToString()?.ifBlank { "none" } ?: formatByte(it)}")
        }
        change.paletteFxBitflags?.let { add("Palette FX flags = ${formatByte(it)}") }
        change.tileAnimBitflags?.let { add("Tile animation flags = ${formatByte(it)}") }
        change.paletteBlend?.let { add("Palette blend = ${formatByte(it)}") }
    }

    private fun formatWord(value: Int): String = "0x" + value.toString(16).uppercase().padStart(4, '0')

    private companion object {
        const val SET_ROOM_MUSIC = "set_room_music"
        const val CONFIGURE_PATCH = "configure_patch"
        const val SET_ROOM_FX = "set_room_fx"
        const val TOURIAN_AREA = "Tourian"
        const val MOTHER_BRAIN_RUNTIME_FX_POINTER = 0xA0A4
        val CONFIG_VALUE_ALIASES = setOf("seconds", "total_seconds", "totalSeconds")
    }
}

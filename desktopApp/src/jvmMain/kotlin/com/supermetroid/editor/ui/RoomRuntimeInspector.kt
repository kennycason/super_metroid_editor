package com.supermetroid.editor.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.AsmDiagnosticSeverity
import com.supermetroid.editor.asm.AsmSelectorProjectionStatus
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomStateEdits
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.displaySummary

internal enum class RoomRuntimeResourceKind {
    HEADER, SELECTORS, LAYOUT, OBJECTS, SCROLLS, FX, ENEMIES, DOORS, SAVES,
}

internal enum class RoomRuntimeOwnership(val label: String) {
    NATIVE("Native"), VISUAL("Visual"), ASM("ASM"),
}

internal enum class RoomRuntimeHealth { CURRENT, LOADING, STALE, ERROR }

internal data class RoomRuntimeProblem(
    val message: String,
    val resource: RoomRuntimeResourceKind,
    val fileId: String? = null,
    val lineIndex: Int? = null,
)

internal data class RoomRuntimeResource(
    val kind: RoomRuntimeResourceKind,
    val title: String,
    val ownership: RoomRuntimeOwnership,
    val summary: String,
    val scope: String,
    val health: RoomRuntimeHealth,
    val problems: List<RoomRuntimeProblem> = emptyList(),
)

internal data class RoomRuntimeSnapshot(
    val roomId: Int,
    val roomName: String,
    val stateSummary: String,
    val buildSummary: String,
    val resources: List<RoomRuntimeResource>,
) {
    val problems: List<RoomRuntimeProblem> get() = resources.flatMap(RoomRuntimeResource::problems).distinct()
}

internal data class RoomRuntimeNavigation(
    val openHeader: (() -> Unit)? = null,
    val openSelectors: (() -> Unit)? = null,
    val openLayout: (() -> Unit)? = null,
    val openObjects: (() -> Unit)? = null,
    val openScrolls: (() -> Unit)? = null,
    val openFx: (() -> Unit)? = null,
    val openEnemies: (() -> Unit)? = null,
    val openDoors: (() -> Unit)? = null,
    val openSaves: (() -> Unit)? = null,
    val openSource: ((String, Int) -> Unit)? = null,
) {
    fun action(kind: RoomRuntimeResourceKind): (() -> Unit)? = when (kind) {
        RoomRuntimeResourceKind.HEADER -> openHeader
        RoomRuntimeResourceKind.SELECTORS -> openSelectors
        RoomRuntimeResourceKind.LAYOUT -> openLayout
        RoomRuntimeResourceKind.OBJECTS -> openObjects
        RoomRuntimeResourceKind.SCROLLS -> openScrolls
        RoomRuntimeResourceKind.FX -> openFx
        RoomRuntimeResourceKind.ENEMIES -> openEnemies
        RoomRuntimeResourceKind.DOORS -> openDoors
        RoomRuntimeResourceKind.SAVES -> openSaves
    }
}

internal fun runtimeOwnership(asmOwned: Boolean, visualOwned: Boolean): RoomRuntimeOwnership = when {
    asmOwned -> RoomRuntimeOwnership.ASM
    visualOwned -> RoomRuntimeOwnership.VISUAL
    else -> RoomRuntimeOwnership.NATIVE
}

internal fun runtimeHealth(problem: String?, explicitlyStale: Boolean = false): RoomRuntimeHealth = when {
    problem?.contains("loading", ignoreCase = true) == true || problem?.startsWith("Wait for", ignoreCase = true) == true -> RoomRuntimeHealth.LOADING
    explicitlyStale || problem?.contains("last valid", ignoreCase = true) == true || problem?.contains("out of date", ignoreCase = true) == true -> RoomRuntimeHealth.STALE
    problem == null -> RoomRuntimeHealth.CURRENT
    else -> RoomRuntimeHealth.ERROR
}

internal fun runtimeBuildHealth(summary: String): RoomRuntimeHealth = when (summary) {
    "ASM loading" -> RoomRuntimeHealth.LOADING
    "Build needed", "Not built" -> RoomRuntimeHealth.STALE
    "Build failed" -> RoomRuntimeHealth.ERROR
    else -> RoomRuntimeHealth.CURRENT
}

private fun RoomRuntimeResource.statusLabel(): String = when (health) {
    RoomRuntimeHealth.CURRENT -> ownership.label
    RoomRuntimeHealth.LOADING -> "${ownership.label} · loading"
    RoomRuntimeHealth.STALE -> "${ownership.label} · stale"
    RoomRuntimeHealth.ERROR -> "${ownership.label} · issue"
}

internal fun buildRoomRuntimeSnapshot(
    room: Room,
    romParser: RomParser,
    editorState: EditorState,
    asmWorkspaceState: AsmWorkspaceState,
): RoomRuntimeSnapshot {
    val edits = editorState.project.rooms[editorState.project.roomKey(room.roomId)]
    val inspection = romParser.inspectRoomStates(room.roomId)
    val effectiveStates = edits?.effectiveStates.orEmpty()
    val selectedState = effectiveStates.firstOrNull { it.id == editorState.currentStateId }
        ?: effectiveStates.getOrNull(editorState.currentStateIndex)
        ?: effectiveStates.lastOrNull()
    val nativeState = inspection.states.getOrNull(editorState.currentStateIndex.coerceAtLeast(0))
        ?: inspection.states.lastOrNull()
    val condition = selectedState?.effectiveCondition?.displaySummary(room.area)
        ?: nativeState?.condition?.shortSummary(room.area)
        ?: "State unavailable"
    val stateIndex = selectedState?.let(effectiveStates::indexOf)?.takeIf { it >= 0 }
        ?: nativeState?.index
        ?: 0
    val stateCount = effectiveStates.size.takeIf { it > 0 } ?: inspection.states.size
    val stateSummary = "State ${stateIndex + 1}/$stateCount · $condition"

    fun RoomEdits?.anyState(predicate: (RoomStateEdits) -> Boolean): Boolean = this?.states?.any(predicate) == true
    fun sharedScope(resource: (RoomStateEdits) -> String): String {
        val state = selectedState ?: return "ROM state"
        val count = effectiveStates.count { resource(it) == resource(state) }
        return if (count > 1) "Shared · $count states" else "Selected state"
    }
    fun problems(kind: RoomRuntimeResourceKind, message: String?): MutableList<RoomRuntimeProblem> =
        message?.let { mutableListOf(RoomRuntimeProblem(it, kind)) } ?: mutableListOf()

    val selectorProblems = problems(RoomRuntimeResourceKind.SELECTORS, editorState.selectorPreviewProblem)
    inspection.issues.forEach { issue ->
        selectorProblems += RoomRuntimeProblem(
            "${issue.message} · PC $${issue.pcOffset.toString(16).uppercase()}",
            RoomRuntimeResourceKind.SELECTORS,
        )
    }

    val objectCount = runCatching { editorState.effectivePlmsForRoom(room.roomId, romParser).size }
        .getOrDefault(editorState.workingPlms.size)
    val doorCount = runCatching { editorState.effectiveDoorsForRoom(room.roomId, romParser).size }
        .getOrDefault(editorState.doorEntries.size)
    val fxCount = runCatching { editorState.effectiveFxEntries(room.roomId, romParser, editorState.currentStateId).size }
        .getOrDefault(0)
    val saveCount = runCatching { editorState.effectivePlmsForRoom(room.roomId, romParser).count { it.id == 0xB76F } }
        .getOrDefault(editorState.workingPlms.count { it.id == 0xB76F })

    val resources = mutableListOf(
        RoomRuntimeResource(
            RoomRuntimeResourceKind.HEADER,
            "Room & state fields",
            runtimeOwnership(edits?.asmRoomPropertiesSourceVersion != 0,
                edits?.roomHeaderChange != null || edits?.stateDataChange != null || edits.anyState { it.stateDataChange != null }),
            "${edits?.effectiveHeaderChange?.width ?: room.width}×${edits?.effectiveHeaderChange?.height ?: room.height} screens · ${room.areaName}",
            "Room-wide",
            runtimeHealth(editorState.roomPropertiesPreviewProblem),
            problems(RoomRuntimeResourceKind.HEADER, editorState.roomPropertiesPreviewProblem),
        ),
        RoomRuntimeResource(
            RoomRuntimeResourceKind.SELECTORS,
            "State selectors",
            runtimeOwnership(edits?.asmStateSelectorsSourceVersion != 0,
                edits?.stateGraphChanged == true || edits.anyState { it.conditionChanged }),
            "$stateCount branches · $condition",
            "Room-wide",
            runtimeHealth(
                editorState.selectorPreviewProblem,
                editorState.selectorProjectionStatus in setOf(AsmSelectorProjectionStatus.STALE, AsmSelectorProjectionStatus.RECOGNIZED),
            ),
            selectorProblems,
        ),
        RoomRuntimeResource(
            RoomRuntimeResourceKind.LAYOUT,
            "Layout / BTS / Layer 2",
            runtimeOwnership(edits?.asmLayoutSourceVersion != 0,
                edits?.levelResourceOperations?.values?.any { it.isNotEmpty() } == true || edits.anyState { it.operations.isNotEmpty() }),
            "${editorState.workingBlocksWide}×${editorState.workingBlocksTall} blocks",
            selectedState?.let { sharedScope { state -> state.resources.level } } ?: "ROM state",
            runtimeHealth(editorState.layoutPreviewProblem),
            problems(RoomRuntimeResourceKind.LAYOUT, editorState.layoutPreviewProblem),
        ),
        RoomRuntimeResource(
            RoomRuntimeResourceKind.OBJECTS,
            "Objects / PLMs",
            runtimeOwnership(edits?.asmPlmSourceVersion != 0,
                edits?.plmChanges?.isNotEmpty() == true || edits.anyState { it.plmChanges.isNotEmpty() }),
            "$objectCount placement${if (objectCount == 1) "" else "s"}",
            selectedState?.let { sharedScope { state -> state.resources.placedObjects } } ?: "ROM state",
            runtimeHealth(editorState.objectPreviewProblem),
            problems(RoomRuntimeResourceKind.OBJECTS, editorState.objectPreviewProblem),
        ),
        RoomRuntimeResource(
            RoomRuntimeResourceKind.SCROLLS,
            "Initial scrolls",
            runtimeOwnership(edits?.asmInitialScrollSourceVersion != 0,
                edits?.scrollChanges?.isNotEmpty() == true || edits.anyState { it.scrollChanges.isNotEmpty() }),
            "${editorState.workingScrolls.size} screen value${if (editorState.workingScrolls.size == 1) "" else "s"}",
            selectedState?.let { sharedScope { state -> state.resources.scrolling } } ?: "ROM state",
            runtimeHealth(editorState.initialScrollPreviewProblem),
            problems(RoomRuntimeResourceKind.SCROLLS, editorState.initialScrollPreviewProblem),
        ),
        RoomRuntimeResource(
            RoomRuntimeResourceKind.FX,
            "FX",
            runtimeOwnership(edits?.asmFxSourceVersion != 0,
                edits?.fxChange != null || edits.anyState { it.fxChange != null || it.doorFxChanges.isNotEmpty() }),
            "$fxCount runtime entr${if (fxCount == 1) "y" else "ies"}",
            selectedState?.let { sharedScope { state -> state.resources.effects } } ?: "ROM state",
            runtimeHealth(editorState.fxPreviewProblem),
            problems(RoomRuntimeResourceKind.FX, editorState.fxPreviewProblem),
        ),
        RoomRuntimeResource(
            RoomRuntimeResourceKind.ENEMIES,
            "Enemies / graphics",
            runtimeOwnership(edits?.asmEnemySourceVersion != 0,
                edits?.enemyChanges?.isNotEmpty() == true || edits.anyState { it.enemyChanges.isNotEmpty() }),
            "${editorState.workingEnemies.size} actors · ${editorState.workingEnemyGraphics.size} graphics",
            selectedState?.let { sharedScope { state -> state.resources.enemies } } ?: "ROM state",
            runtimeHealth(editorState.enemyPreviewProblem),
            problems(RoomRuntimeResourceKind.ENEMIES, editorState.enemyPreviewProblem),
        ),
        RoomRuntimeResource(
            RoomRuntimeResourceKind.DOORS,
            "Door connections",
            runtimeOwnership(edits?.asmDoorSourceVersion != 0, edits?.doorChanges?.isNotEmpty() == true),
            "$doorCount connection${if (doorCount == 1) "" else "s"}",
            "Room-wide",
            runtimeHealth(editorState.doorPreviewProblem),
            problems(RoomRuntimeResourceKind.DOORS, editorState.doorPreviewProblem),
        ),
        RoomRuntimeResource(
            RoomRuntimeResourceKind.SAVES,
            "Save spawns",
            runtimeOwnership(edits?.asmPlmSourceVersion != 0,
                edits?.saveStationSpawns?.isNotEmpty() == true),
            "$saveCount station${if (saveCount == 1) "" else "s"}",
            "Room-wide",
            runtimeHealth(editorState.objectPreviewProblem),
        ),
    )

    val roomTokens = listOf(
        "room 0x${room.roomId.toString(16).uppercase()}",
        "room $${room.roomId.toString(16).uppercase()}",
        room.name,
    )
    asmWorkspaceState.displayedDiagnostics.filter { diagnostic ->
        roomTokens.any { diagnostic.message.contains(it, ignoreCase = true) }
    }.forEach { diagnostic ->
        val text = "${diagnostic.fileId.orEmpty()} ${diagnostic.message}".lowercase()
        val kind = when {
            "selector" in text || "condition" in text || "state graph" in text -> RoomRuntimeResourceKind.SELECTORS
            "level" in text || "layout" in text || "bts" in text -> RoomRuntimeResourceKind.LAYOUT
            "enemy" in text || "graphic" in text -> RoomRuntimeResourceKind.ENEMIES
            "door" in text -> RoomRuntimeResourceKind.DOORS
            "scroll" in text -> RoomRuntimeResourceKind.SCROLLS
            "save" in text -> RoomRuntimeResourceKind.SAVES
            "fx" in text -> RoomRuntimeResourceKind.FX
            "plm" in text || "object" in text -> RoomRuntimeResourceKind.OBJECTS
            else -> RoomRuntimeResourceKind.HEADER
        }
        val problem = RoomRuntimeProblem(diagnostic.message, kind, diagnostic.fileId, diagnostic.lineIndex)
        val index = resources.indexOfFirst { it.kind == kind }
        if (index >= 0) {
            val resource = resources[index]
            resources[index] = resource.copy(
                health = if (diagnostic.severity == AsmDiagnosticSeverity.ERROR) RoomRuntimeHealth.ERROR else resource.health,
                problems = (resource.problems + problem).distinct(),
            )
        }
    }

    val buildSummary = when {
        !editorState.project.asmWorkspace.enabled -> "Loaded ROM"
        asmWorkspaceState.projectWorkspace == null -> "ASM loading"
        asmWorkspaceState.sourceBuildRequired -> "Build needed"
        asmWorkspaceState.buildReport?.succeeded == true -> "Built"
        asmWorkspaceState.buildReport?.succeeded == false -> "Build failed"
        else -> "Not built"
    }
    return RoomRuntimeSnapshot(room.roomId, room.name, stateSummary, buildSummary, resources)
}

@Composable
internal fun RoomInfoModeSwitch(runtime: Boolean, onRuntimeChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("room-info-mode-switch"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(false to "Edit", true to "Runtime").forEach { (value, label) ->
            Surface(
                color = if (runtime == value) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(5.dp),
                modifier = Modifier.weight(1f).clickable { onRuntimeChange(value) }
                    .testTag("room-info-mode-${label.lowercase()}"),
            ) {
                Text(
                    label,
                    fontSize = ROOM_INFO_COMPACT_FONT_SIZE,
                    fontWeight = if (runtime == value) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (runtime == value) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 5.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun RoomRuntimeInspector(
    snapshot: RoomRuntimeSnapshot,
    navigation: RoomRuntimeNavigation,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(snapshot.roomId) { mutableStateOf<RoomRuntimeResourceKind?>(null) }
    val problems = snapshot.problems
    Column(
        modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${snapshot.roomName} · $${snapshot.roomId.toString(16).uppercase()}",
                    fontSize = ROOM_INFO_BODY_FONT_SIZE,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    snapshot.stateSummary,
                    fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RuntimeChip(snapshot.buildSummary, runtimeBuildHealth(snapshot.buildSummary))
        }

        if (problems.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().testTag("room-runtime-problems"),
            ) {
                Column(Modifier.padding(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Problems · ${problems.size}",
                        fontSize = ROOM_INFO_COMPACT_FONT_SIZE,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    problems.take(4).forEach { problem ->
                        val open = if (problem.fileId != null && problem.lineIndex != null) {
                            navigation.openSource?.let { callback -> { callback(problem.fileId, problem.lineIndex); Unit } }
                        } else navigation.action(problem.resource)
                        Text(
                            "• ${problem.message}",
                            fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (open == null) Modifier else Modifier.clickable(onClick = open),
                        )
                    }
                    if (problems.size > 4) Text(
                        "+${problems.size - 4} more in the resource rows",
                        fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        snapshot.resources.forEach { resource ->
            val isExpanded = expanded == resource.kind
            val action = navigation.action(resource.kind)
            Surface(
                color = if (resource.health == RoomRuntimeHealth.ERROR) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().clickable {
                    expanded = resource.kind.takeUnless { isExpanded }
                }.testTag("room-runtime-resource-${resource.kind.name.lowercase()}"),
            ) {
                Column(Modifier.padding(horizontal = 7.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (isExpanded) "▾" else "▸", fontSize = ROOM_INFO_CAPTION_FONT_SIZE)
                        Column(Modifier.weight(1f)) {
                            Text(resource.title, fontSize = ROOM_INFO_BODY_FONT_SIZE, fontWeight = FontWeight.SemiBold)
                            Text(
                                resource.summary,
                                fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        RuntimeChip(resource.statusLabel(), resource.health)
                    }
                    if (isExpanded) {
                        Text(
                            resource.scope,
                            fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        resource.problems.forEach { problem ->
                            Text(
                                problem.message,
                                fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (action != null) {
                            TextButton(onClick = action, modifier = Modifier.align(Alignment.End)) {
                                Text("Open ASM", fontSize = ROOM_INFO_COMPACT_FONT_SIZE)
                            }
                        }
                    }
                }
            }
        }

        Text(
            "Read-only · reflects pending source and the selected room state",
            fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 3.dp),
        )
    }
}

@Composable
private fun RuntimeChip(label: String, health: RoomRuntimeHealth) {
    val color = when (health) {
        RoomRuntimeHealth.ERROR -> MaterialTheme.colorScheme.error
        RoomRuntimeHealth.STALE -> Color(0xFFE0A12B)
        RoomRuntimeHealth.LOADING -> MaterialTheme.colorScheme.tertiary
        RoomRuntimeHealth.CURRENT -> MaterialTheme.colorScheme.primary
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Text(
            label,
            fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            maxLines = 1,
        )
    }
}

package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.CustomItemDef
import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RoomNamePauseMapPatch
import com.supermetroid.editor.rom.RomParser
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.FileDialog
import java.io.File

private val patchEditorLog = KotlinLogging.logger {}

// ─── Left column: patch list panel ──────────────────────────────────────

private val SYSTEM_PATCH_PREFIXES = listOf("hex_", "config_", "bundled_")

fun isSystemPatch(id: String): Boolean =
    SYSTEM_PATCH_PREFIXES.any { id.startsWith(it) }

internal fun importIpsPatch(editorState: EditorState, fileName: String, ipsData: ByteArray): SmPatch {
    val writes = PatchRepository.parseIps(ipsData)
    val name = File(fileName).nameWithoutExtension.replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
    val patch = editorState.addPatch(name, "Imported from $fileName", enabled = true)
    editorState.setPatchWrites(
        patch.id,
        writes.map { SmPatchWrite(it.offset, it.bytes) },
    )
    return patch
}

@Composable
fun PatchListPanel(
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE") val pv = editorState.patchVersion
    // Filter out enemy/boss patches — they have their own top-level tabs
    val patches = editorState.project.patches.filter { it.configType !in DEDICATED_EDITOR_CONFIG_TYPES }
    val selectedId = editorState.selectedPatchId
    var searchQuery by remember { mutableStateOf("") }
    val filtered = if (searchQuery.isBlank()) patches
        else patches.filter { it.name.contains(searchQuery, ignoreCase = true) }
    val selectedIndex = filtered.indexOfFirst { it.id == selectedId }
    val navigationFocusRequester = rememberVerticalSelectionFocusRequester()

    Column(
        modifier = modifier.verticalSelectionKeyNavigation(
            focusRequester = navigationFocusRequester,
            itemCount = filtered.size,
            selectedIndex = selectedIndex,
            onSelectIndex = { index -> editorState.selectPatch(filtered[index].id) }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Patches", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = {
                        requestVerticalSelectionFocus(navigationFocusRequester)
                        val fd = FileDialog(null as java.awt.Frame?, "Import IPS Patch", FileDialog.LOAD)
                        fd.file = "*.ips"
                        fd.isVisible = true
                        val dir = fd.directory
                        val file = fd.file
                        if (dir != null && file != null) {
                            try {
                                val ipsFile = File(dir, file)
                                importIpsPatch(editorState, ipsFile.name, ipsFile.readBytes())
                            } catch (e: Exception) {
                                patchEditorLog.error(e) { "IPS import failed: ${e.message}" }
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text("+IPS", fontSize = 11.sp)
                }
            }
        }

        // Search field
        BasicTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    if (searchQuery.isEmpty()) {
                        Text("Search patches...", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                    inner()
                }
            }
        )

        Divider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            if (filtered.isEmpty()) {
                Text(
                    if (searchQuery.isNotBlank()) "No patches match \"$searchQuery\"."
                    else "No patches yet.\nClick +IPS to import an external patch.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            for (patch in filtered) {
                PatchListItem(
                    patch = patch,
                    isSelected = patch.id == selectedId,
                    onSelect = {
                        requestVerticalSelectionFocus(navigationFocusRequester)
                        editorState.selectPatch(patch.id)
                    },
                    onToggle = {
                        requestVerticalSelectionFocus(navigationFocusRequester)
                        editorState.togglePatch(patch.id)
                    },
                    onDelete = if (isSystemPatch(patch.id)) null
                               else {{
                                   requestVerticalSelectionFocus(navigationFocusRequester)
                                   editorState.removePatch(patch.id)
                               }}
                )
            }
        }
    }
}

@Composable
private fun PatchListItem(
    patch: SmPatch,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer
             else Color.Transparent

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 2.dp),
        color = bg,
        shape = RoundedCornerShape(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Checkbox(
                checked = patch.enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.size(16.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF4CAF50),
                    uncheckedColor = Color(0xFF888888),
                    checkmarkColor = Color.White
                )
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    patch.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (patch.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            if (onDelete != null) {
                Text(
                    "✕",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    modifier = Modifier
                        .clickable { onDelete() }
                        .padding(2.dp)
                )
            }
        }
    }
}

// ─── Right canvas: patch editor view ────────────────────────────────────

@Composable
fun PatchEditorCanvas(
    editorState: EditorState,
    romParser: RomParser? = null,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE") val pv = editorState.patchVersion
    val patch = editorState.getSelectedPatch()

    if (patch == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Select a patch from the left panel", fontSize = 14.sp,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("or click +IPS to import an external patch.", fontSize = 12.sp,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Toolbar
        PatchToolbar(patch, editorState)
        Divider()
        // Config panel for GUI patches, hex editor for manual patches
        when (patch.configType) {
            "ceres_escape_seconds" -> CeresEscapeTimeConfig(patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            ZEBES_ESCAPE_CONFIG_TYPE -> ZebesEscapeTimeConfig(patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            SHORT_CHARGE_CONFIG_TYPE -> ShortChargeConfig(patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            "beam_damage" -> BeamDamageEditor(patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            "boss_stats" -> BossStatsEditor(patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            "phantoon" -> PhantoonEditor(patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            KRAID_CONFIG_TYPE -> KraidEditor(patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            RIDLEY_CONFIG_TYPE -> BossBehaviorEditor(RIDLEY_BEHAVIOR, patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            DRAYGON_CONFIG_TYPE -> BossBehaviorEditor(DRAYGON_BEHAVIOR, patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            SPORE_SPAWN_CONFIG_TYPE -> BossBehaviorEditor(SPORE_SPAWN_BEHAVIOR, patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            CROCOMIRE_CONFIG_TYPE -> BossBehaviorEditor(CROCOMIRE_BEHAVIOR, patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            BOTWOON_CONFIG_TYPE -> BossBehaviorEditor(BOTWOON_BEHAVIOR, patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            TORIZO_CONFIG_TYPE -> BossBehaviorEditor(TORIZO_BEHAVIOR, patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            MOTHER_BRAIN_CONFIG_TYPE -> BossBehaviorEditor(MOTHER_BRAIN_BEHAVIOR, patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            "enemy_stats" -> EnemyStatsEditor(patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            "enemy_drops" -> EnemyDropRateEditor(patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            "enemy_vuln" -> EnemyVulnerabilityEditor(patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            "samus_physics" -> SamusPhysicsEditor(patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            BOMB_CONFIG_TYPE -> BombsEditor(patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            FANFARE_CONFIG_TYPE -> FanfareEditor(patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
            RoomNamePauseMapPatch.CONFIG_TYPE -> RoomNamePauseMapConfig(patch, editorState, Modifier.weight(1f).fillMaxWidth())
            "boss_defeated" -> BossDefeatedEditor(patch, editorState, Modifier.weight(1f).fillMaxWidth())
            "controller_config" -> ControllerConfigEditor(patch, editorState, Modifier.weight(1f).fillMaxWidth())
            else -> PatchHexEditor(patch, editorState, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun PatchToolbar(patch: SmPatch, editorState: EditorState) {
    @Suppress("UNUSED_VARIABLE") val pv = editorState.patchVersion
    var editingName by remember(patch.id) { mutableStateOf(false) }
    var nameText by remember(patch.id) { mutableStateOf(patch.name) }
    var descText by remember(patch.id) { mutableStateOf(patch.description) }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Editable name
            if (editingName) {
                BasicTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Button(
                    onClick = {
                        editorState.updatePatch(patch.id, name = nameText, description = descText)
                        editingName = false
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) { Text("Save", fontSize = 11.sp) }
            } else {
                Text(
                    patch.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clickable { editingName = true }
                )
                // Enabled badge
                Surface(
                    color = if (patch.enabled) Color(0xFF4CAF50) else Color(0xFF888888),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        if (patch.enabled) "ENABLED" else "DISABLED",
                        fontSize = 10.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Button(
                    onClick = { editorState.togglePatch(patch.id) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (patch.enabled) Color(0xFF888888) else Color(0xFF4CAF50)
                    )
                ) {
                    Text(if (patch.enabled) "Disable" else "Enable", fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Description
        if (editingName) {
            BasicTextField(
                value = descText,
                onValueChange = { descText = it },
                textStyle = TextStyle(
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .padding(8.dp)
            )
        } else if (patch.description.isNotEmpty()) {
            Text(
                patch.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { editingName = true }
            )
        }

        CustomItemsEditor(patch = patch, editorState = editorState)
    }
}

@Composable
private fun CustomItemsEditor(patch: SmPatch, editorState: EditorState) {
    var expanded by remember(patch.id) { mutableStateOf(patch.customItems.isNotEmpty()) }

    Spacer(Modifier.height(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Custom items (${patch.customItems.size})",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            modifier = Modifier.height(26.dp),
        ) {
            Text(if (expanded) "Hide" else "Show", fontSize = 10.sp)
        }
        Button(
            onClick = {
                editorState.addPatchCustomItem(patch.id)
                expanded = true
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            modifier = Modifier.height(26.dp),
        ) {
            Text("+ Add Item", fontSize = 10.sp)
        }
    }

    if (!expanded) return

    if (patch.customItems.isEmpty()) {
        Text(
            "No custom items declared by this patch.",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }

    Column(
        modifier = Modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Name", fontSize = 10.sp, modifier = Modifier.width(150.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Label", fontSize = 10.sp, modifier = Modifier.width(48.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Bit", fontSize = 10.sp, modifier = Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Icon X", fontSize = 10.sp, modifier = Modifier.width(50.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Icon Y", fontSize = 10.sp, modifier = Modifier.width(50.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (item in patch.customItems) {
            CustomItemEditorRow(patch = patch, item = item, editorState = editorState)
        }
    }
}

@Composable
private fun CustomItemEditorRow(
    patch: SmPatch,
    item: CustomItemDef,
    editorState: EditorState,
) {
    var nameText by remember(item.id, item.name) { mutableStateOf(item.name) }
    var labelText by remember(item.id, item.shortLabel) { mutableStateOf(item.shortLabel) }
    var bitText by remember(item.id, item.bitMask) { mutableStateOf("0x${item.bitMask.toString(16).uppercase().padStart(4, '0')}") }
    var iconXText by remember(item.id, item.iconX) { mutableStateOf(item.iconX.toString()) }
    var iconYText by remember(item.id, item.iconY) { mutableStateOf(item.iconY.toString()) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactPatchField(
            value = nameText,
            widthDp = 150,
            onValueChange = {
                nameText = it
                editorState.updatePatchCustomItem(patch.id, item.id, name = it)
            },
        )
        CompactPatchField(
            value = labelText,
            widthDp = 48,
            onValueChange = {
                labelText = it.take(4)
                editorState.updatePatchCustomItem(patch.id, item.id, shortLabel = labelText)
            },
        )
        CompactPatchField(
            value = bitText,
            widthDp = 72,
            onValueChange = {
                bitText = it
                parsePatchInt(it)?.let { parsed ->
                    editorState.updatePatchCustomItem(patch.id, item.id, bitMask = parsed)
                }
            },
            monospace = true,
        )
        CompactPatchField(
            value = iconXText,
            widthDp = 50,
            onValueChange = {
                iconXText = it
                it.toIntOrNull()?.let { parsed ->
                    editorState.updatePatchCustomItem(patch.id, item.id, iconX = parsed)
                }
            },
            monospace = true,
        )
        CompactPatchField(
            value = iconYText,
            widthDp = 50,
            onValueChange = {
                iconYText = it
                it.toIntOrNull()?.let { parsed ->
                    editorState.updatePatchCustomItem(patch.id, item.id, iconY = parsed)
                }
            },
            monospace = true,
        )
        Text(
            "Remove",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.clickable { editorState.removePatchCustomItem(patch.id, item.id) }.padding(4.dp),
        )
    }
}

@Composable
private fun CompactPatchField(
    value: String,
    widthDp: Int,
    onValueChange: (String) -> Unit,
    monospace: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .width(widthDp.dp)
            .height(26.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RoomNamePauseMapConfig(
    patch: SmPatch,
    editorState: EditorState,
    modifier: Modifier
) {
    @Suppress("UNUSED_VARIABLE") val pv = editorState.patchVersion
    val rooms = remember { RoomRepository().getAllRooms().sortedBy { it.getRoomIdAsInt() } }
    val baseRoomIds = remember(rooms) { rooms.map { it.getRoomIdAsInt() }.toSet() }
    val overrides = editorState.project.roomNameOverrides
    val alignment = RoomNamePauseMapPatch.RoomNameAlignment.fromConfig(
        patch.configData?.get(RoomNamePauseMapPatch.CONFIG_ALIGNMENT_KEY)
    )
    val customOverrides = overrides.entries
        .mapNotNull { (rawKey, name) ->
            val key = editorState.normalizedRoomNameOverrideKey(rawKey) ?: return@mapNotNull null
            val roomId = key.toInt(16)
            if (roomId in baseRoomIds || name.isBlank()) null else key to name
        }
        .distinctBy { it.first }
        .sortedBy { it.first }
    val overrideCount = overrides.keys
        .mapNotNull { editorState.normalizedRoomNameOverrideKey(it) }
        .distinct()
        .size
    var customRoomId by remember { mutableStateOf("") }
    var customRoomName by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(
            "Draws the current room name on the pause map. Edits below are saved as project overrides; base room names stay unchanged.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Rooms: ${rooms.size}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Overrides: $overrideCount",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (patch.enabled) "Enabled" else "Disabled",
                fontSize = 12.sp,
                color = if (patch.enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Alignment",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in RoomNamePauseMapPatch.RoomNameAlignment.entries) {
                FilterChip(
                    selected = alignment == option,
                    onClick = {
                        editorState.setPatchConfigData(
                            patch.id,
                            RoomNamePauseMapPatch.CONFIG_ALIGNMENT_KEY,
                            option.configValue,
                        )
                    },
                    label = { Text(option.label, fontSize = 11.sp) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        RoomNameHeaderRow()
        Spacer(Modifier.height(4.dp))

        for (room in rooms) {
            val roomId = room.getRoomIdAsInt()
            val key = roomId.toString(16).uppercase().padStart(4, '0')
            val overrideName = overrides[key]
            RoomNameOverrideRow(
                roomIdText = key,
                currentName = overrideName ?: room.name,
                hasOverride = overrideName != null,
                resetLabel = "Reset",
                onNameChange = { editorState.setRoomNameOverride(key, it, room.name) },
                onReset = { editorState.removeRoomNameOverride(key) },
            )
        }

        if (customOverrides.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Custom room IDs",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            for ((key, name) in customOverrides) {
                RoomNameOverrideRow(
                    roomIdText = key,
                    currentName = name,
                    hasOverride = true,
                    resetLabel = "Remove",
                    onNameChange = { editorState.setRoomNameOverride(key, it) },
                    onReset = { editorState.removeRoomNameOverride(key) },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "Add custom room ID",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            RoomNameSmallField(
                value = customRoomId,
                onValueChange = {
                    customRoomId = it.take(6)
                    customError = null
                },
                widthDp = 76,
                monospace = true,
            )
            RoomNameTextField(
                value = customRoomName,
                onValueChange = {
                    customRoomName = it
                    customError = null
                },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val validKey = editorState.normalizedRoomNameOverrideKey(customRoomId)
                    when {
                        validKey == null -> customError = "Invalid room ID"
                        customRoomName.isBlank() -> customError = "Name required"
                        else -> {
                            editorState.setRoomNameOverride(validKey, customRoomName)
                            customRoomId = ""
                            customRoomName = ""
                            customError = null
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("Add", fontSize = 11.sp)
            }
        }
        customError?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RoomNameHeaderRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
    ) {
        Text("Room ID", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp))
        Text("Room name", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(64.dp))
    }
}

@Composable
private fun RoomNameOverrideRow(
    roomIdText: String,
    currentName: String,
    hasOverride: Boolean,
    resetLabel: String,
    onNameChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    var nameText by remember(roomIdText, currentName) { mutableStateOf(currentName) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Text(
            roomIdText,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp)
        )
        RoomNameTextField(
            value = nameText,
            onValueChange = {
                nameText = it
                onNameChange(it)
            },
            modifier = Modifier.weight(1f),
        )
        Box(modifier = Modifier.width(64.dp)) {
            if (hasOverride) {
                OutlinedButton(
                    onClick = onReset,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text(resetLabel, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun RoomNameTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(26.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
    )
}

@Composable
private fun RoomNameSmallField(
    value: String,
    onValueChange: (String) -> Unit,
    widthDp: Int,
    monospace: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .width(widthDp.dp)
            .height(26.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
    )
}

private fun parsePatchInt(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val cleaned = when {
        trimmed.startsWith("0x", ignoreCase = true) -> trimmed.drop(2)
        trimmed.startsWith('$') -> trimmed.drop(1)
        else -> trimmed
    }
    val radix = if (cleaned.any { it in 'A'..'F' || it in 'a'..'f' } || trimmed.startsWith("0x", true) || trimmed.startsWith('$')) 16 else 10
    return cleaned.toIntOrNull(radix)
}

// ─── Config patch: Ceres escape time ────────────────────────────────────

@Composable
private fun CeresEscapeTimeConfig(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier
) {
    val currentValue = (patch.configValue ?: 60).coerceIn(1, 600)
    val romValue = remember(romParser) {
        romParser?.let {
            val off = it.snesToPc(CERES_TIMER_OPERAND_SNES)
            val rom = it.getRomData()
            if (off + 1 < rom.size) {
                val secsBcd = rom[off].toInt() and 0xFF
                val minsBcd = rom[off + 1].toInt() and 0xFF
                val secs = (secsBcd shr 4) * 10 + (secsBcd and 0x0F)
                val mins = (minsBcd shr 4) * 10 + (minsBcd and 0x0F)
                mins * 60 + secs
            } else 60
        } ?: 60
    }

    var inputText by remember(currentValue) { mutableStateOf(currentValue.toString()) }

    Column(modifier = modifier.padding(16.dp)) {
        Text(
            "Set Ceres station escape timer (seconds). Override applies when patch is enabled.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "ROM default: ${romValue}s",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            BasicTextField(
                value = inputText,
                onValueChange = { newText ->
                    val digits = newText.filter { it.isDigit() }
                    if (digits.length <= 3) {
                        inputText = digits
                        val parsed = digits.toIntOrNull()
                        if (parsed != null && parsed in 1..600) {
                            editorState.setPatchConfigValue(patch.id, parsed)
                        }
                    }
                },
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(
                        Modifier
                            .width(72.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        innerTextField()
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            Text("seconds (1–600)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Contextual info/warnings
        val parsed = inputText.toIntOrNull()
        if (parsed != null) {
            Spacer(Modifier.height(6.dp))
            when {
                parsed < 15 -> Text(
                    "Warning: this timer may be impossible to escape in time",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
                parsed == 15 -> Text(
                    "Kaizo Super Metroid timer (original)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                parsed == 16 -> Text(
                    "Kaizo-possible — tightest feasible escape",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                parsed == 60 -> Text(
                    "Vanilla Super Metroid default",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (parsed > 600) {
                Text(
                    "Maximum is 600 seconds",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ─── Config patch: end-game Zebes escape time ──────────────────────────

@Composable
private fun ZebesEscapeTimeConfig(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier
) {
    val currentValue = (patch.configValue ?: ZEBES_ESCAPE_DEFAULT_SECONDS)
        .coerceIn(ZEBES_ESCAPE_MIN_SECONDS, ZEBES_ESCAPE_MAX_SECONDS)
    val romValue = remember(romParser) {
        romParser?.let {
            val off = it.snesToPc(ZEBES_TIMER_OPERAND_SNES)
            val rom = it.getRomData()
            if (off + 1 < rom.size) {
                val secsBcd = rom[off].toInt() and 0xFF
                val minsBcd = rom[off + 1].toInt() and 0xFF
                val secs = (secsBcd shr 4) * 10 + (secsBcd and 0x0F)
                val mins = (minsBcd shr 4) * 10 + (minsBcd and 0x0F)
                mins * 60 + secs
            } else ZEBES_ESCAPE_DEFAULT_SECONDS
        } ?: ZEBES_ESCAPE_DEFAULT_SECONDS
    }
    var inputText by remember(currentValue) { mutableStateOf(currentValue.toString()) }

    Column(modifier = modifier.padding(16.dp)) {
        Text(
            "Set the Zebes escape countdown that starts after Mother Brain. Override applies when enabled.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "ROM default: ${formatEscapeTime(romValue)} ($romValue seconds)",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = inputText,
                onValueChange = { newText ->
                    val digits = newText.filter { it.isDigit() }
                    if (digits.length <= 4) {
                        inputText = digits
                        val parsed = digits.toIntOrNull()
                        if (parsed != null && parsed in ZEBES_ESCAPE_MIN_SECONDS..ZEBES_ESCAPE_MAX_SECONDS) {
                            editorState.setPatchConfigValue(patch.id, parsed)
                        }
                    }
                },
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(
                        Modifier
                            .width(80.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        innerTextField()
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "seconds (1–5999)",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val parsed = inputText.toIntOrNull()
        if (parsed != null) {
            Spacer(Modifier.height(6.dp))
            if (parsed in ZEBES_ESCAPE_MIN_SECONDS..ZEBES_ESCAPE_MAX_SECONDS) {
                Text(
                    if (parsed == ZEBES_ESCAPE_DEFAULT_SECONDS) {
                        "${formatEscapeTime(parsed)} — vanilla Super Metroid default"
                    } else {
                        "Countdown: ${formatEscapeTime(parsed)}"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Enter 1–5999 seconds (up to 99:59)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun formatEscapeTime(totalSeconds: Int): String =
    "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"

// ─── Config patch: Short Charge ────────────────────────────────────────

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ShortChargeConfig(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier
) {
    val stages = (patch.configValue ?: SHORT_CHARGE_DEFAULT_STAGES)
        .coerceIn(SHORT_CHARGE_MIN_STAGES, SHORT_CHARGE_MAX_STAGES)
    val romStages = remember(romParser) {
        romParser?.let {
            val off = it.snesToPc(SHORT_CHARGE_INITIAL_TIMER_SNES)
            val rom = it.getRomData()
            if (off + 1 < rom.size) {
                val initialCounter = rom[off + 1].toInt() and 0xFF
                (SHORT_CHARGE_MAX_STAGES - initialCounter)
                    .coerceIn(SHORT_CHARGE_MIN_STAGES, SHORT_CHARGE_MAX_STAGES)
            } else SHORT_CHARGE_DEFAULT_STAGES
        } ?: SHORT_CHARGE_DEFAULT_STAGES
    }

    Column(modifier = modifier.padding(16.dp)) {
        Text(
            "Choose how many Speed Booster charge stages must complete before Samus gains blue speed.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "ROM default: $romStages stages. Vanilla uses 4; 0 activates blue speed as soon as Dash running begins.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (value in SHORT_CHARGE_MIN_STAGES..SHORT_CHARGE_MAX_STAGES) {
                FilterChip(
                    selected = stages == value,
                    onClick = { editorState.setPatchConfigValue(patch.id, value) },
                    label = { Text(value.toString(), fontSize = 12.sp) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            when (stages) {
                0 -> "Immediate: pressing Dash while running starts blue speed."
                1 -> "One charge stage before blue speed."
                4 -> "Vanilla charge length (four stages)."
                else -> "$stages charge stages before blue speed."
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "This changes the engine's charge-stage requirement; it does not automate the advanced tap timing technique.",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControllerConfigEditor(
    patch: SmPatch,
    editorState: EditorState,
    modifier: Modifier
) {
    val data = patch.configData ?: mutableMapOf()

    Column(modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(
            "Remap the default controller buttons. Changes apply to new saves and the options menu default.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "ROM table: \$82:F575 (PC 0x017575) — 7 slots × 2 bytes",
            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(16.dp))

        for (slot in CONTROLLER_SLOTS) {
            val current = data[slot.key] ?: slot.defaultButton
            val defaultName = SNES_BUTTONS.find { it.bitmask == slot.defaultButton }?.name ?: "?"
            var expanded by remember(slot.key) { mutableStateOf(false) }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    slot.name,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(100.dp)
                )
                Box {
                    Surface(
                        modifier = Modifier.width(120.dp).height(32.dp)
                            .clickable { expanded = true },
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp).fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val btnName = SNES_BUTTONS.find { it.bitmask == current }?.name ?: "0x${current.toString(16)}"
                            Text(btnName, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("▼", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        for (btn in SNES_BUTTONS) {
                            DropdownMenuItem(
                                text = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(btn.name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        if (btn.bitmask == slot.defaultButton) {
                                            Text("(default)", fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                onClick = {
                                    expanded = false
                                    editorState.setPatchConfigData(patch.id, slot.key, btn.bitmask)
                                },
                                modifier = Modifier.height(32.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "default: $defaultName",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                for (slot in CONTROLLER_SLOTS) {
                    editorState.setPatchConfigData(patch.id, slot.key, slot.defaultButton)
                }
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) { Text("Reset to Defaults", fontSize = 11.sp) }
    }
}

private enum class PatchWriteViewMode {
    HEX,
    ASM,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatchHexEditor(patch: SmPatch, editorState: EditorState, modifier: Modifier) {
    var rawText by remember(patch.id, editorState.patchVersion) {
        mutableStateOf(writesToText(patch.writes))
    }
    var parseError by remember(patch.id) { mutableStateOf<String?>(null) }
    var viewMode by remember(patch.id) { mutableStateOf(PatchWriteViewMode.ASM) }
    val asmText = remember(rawText, viewMode) {
        if (viewMode != PatchWriteViewMode.ASM) {
            ""
        } else {
            val result = parseText(rawText)
            if (result.error != null) {
                "; Cannot disassemble: ${result.error}"
            } else {
                disassemblePatchWrites(result.writes)
            }
        }
    }

    Column(modifier = modifier.padding(12.dp)) {
        if (viewMode == PatchWriteViewMode.HEX) {
            Text(
                "Format: one record per line — OFFSET: BB BB BB ...  (hex values, offset is PC file address)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Example: 8F625: 22    or    15962: 04    or    8267F: EA EA EA",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "Read-only 65816 disassembly generated from the current hex text. Addresses use headerless LoROM mapping.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "M/X immediate widths default to 16-bit and track REP/SEP inside each write record.",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = viewMode == PatchWriteViewMode.ASM,
                onClick = { viewMode = PatchWriteViewMode.ASM },
                label = { Text("ASM", fontSize = 11.sp) },
                modifier = Modifier.height(32.dp)
            )
            FilterChip(
                selected = viewMode == PatchWriteViewMode.HEX,
                onClick = { viewMode = PatchWriteViewMode.HEX },
                label = { Text("Hex", fontSize = 11.sp) },
                modifier = Modifier.height(32.dp)
            )

            Spacer(Modifier.weight(1f))

            if (viewMode == PatchWriteViewMode.HEX) {
                Button(
                    onClick = {
                        val result = parseText(rawText)
                        if (result.error != null) {
                            parseError = result.error
                        } else {
                            parseError = null
                            editorState.setPatchWrites(patch.id, result.writes)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) { Text("Apply Changes", fontSize = 12.sp) }

                Button(
                    onClick = { rawText = writesToText(patch.writes) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) { Text("Revert", fontSize = 12.sp) }
            }

            if (parseError != null) {
                Text(
                    parseError!!,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // The patch text viewer/editor
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E2E), RoundedCornerShape(6.dp))
                .padding(2.dp)
        ) {
            val verticalScrollState = rememberScrollState()
            val horizontalScrollState = rememberScrollState()
            BasicTextField(
                value = if (viewMode == PatchWriteViewMode.HEX) rawText else asmText,
                onValueChange = {
                    if (viewMode == PatchWriteViewMode.HEX) {
                        rawText = it
                        parseError = null
                    }
                },
                readOnly = viewMode == PatchWriteViewMode.ASM,
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFCDD6F4),
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(Color(0xFFF5C2E7)),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScrollState)
                    .horizontalScroll(horizontalScrollState)
                    .padding(12.dp)
            )
        }
    }
}

// ─── Text ↔ PatchWrite conversion ───────────────────────────────────────

private fun writesToText(writes: List<PatchWrite>): String {
    if (writes.isEmpty()) return ""
    return writes.joinToString("\n") { w ->
        val addr = w.offset.toString(16).uppercase().padStart(5, '0')
        val bytes = w.bytes.joinToString(" ") {
            it.toString(16).uppercase().padStart(2, '0')
        }
        "$addr: $bytes"
    }
}

private data class ParseResult(
    val writes: List<SmPatchWrite> = emptyList(),
    val error: String? = null
)

private fun parseText(text: String): ParseResult {
    val writes = mutableListOf<SmPatchWrite>()
    for ((lineNum, raw) in text.lines().withIndex()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue

        val colonIdx = line.indexOf(':')
        if (colonIdx < 0) return ParseResult(error = "Line ${lineNum + 1}: missing ':' separator")

        val addrStr = line.substring(0, colonIdx).trim()
        val bytesStr = line.substring(colonIdx + 1).trim()

        val offset = try {
            addrStr.toLong(16)
        } catch (_: NumberFormatException) {
            return ParseResult(error = "Line ${lineNum + 1}: invalid address '$addrStr'")
        }

        if (bytesStr.isEmpty()) continue

        val byteTokens = bytesStr.split("\\s+".toRegex())
        val bytes = mutableListOf<Int>()
        for (token in byteTokens) {
            if (token.isEmpty()) continue
            val b = try {
                token.toInt(16)
            } catch (_: NumberFormatException) {
                return ParseResult(error = "Line ${lineNum + 1}: invalid byte '$token'")
            }
            if (b !in 0..255) return ParseResult(error = "Line ${lineNum + 1}: byte $token out of range (00-FF)")
            bytes.add(b)
        }
        if (bytes.isNotEmpty()) {
            writes.add(SmPatchWrite(offset, bytes))
        }
    }
    return ParseResult(writes = writes)
}

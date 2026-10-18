package com.supermetroid.editor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.*
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.ControllerBindings
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.ShortChargePatch

private var nativeSelection by mutableStateOf<Triple<String, String, Int>?>(null)
internal fun selectNativePatchField(type: String, key: String) { nativeSelection = Triple(type, key, (nativeSelection?.third ?: 0) + 1) }

internal fun nativeRomValues(definition: DataPatchDefinition, parser: RomParser?): List<Int> {
    if (definition.type == ShortChargePatch.CONFIG_TYPE) return parser?.getRomData()?.let(ShortChargePatch::read) ?: ShortChargePatch.defaults
    val fallback = when (definition.type) {
        "ceres_escape_seconds" -> 0x0100
        ZEBES_ESCAPE_CONFIG_TYPE -> 0x0300
        BOMB_CONFIG_TYPE -> when (definition.key) { BOMB_MAX_ACTIVE_KEY -> 5; BOMB_FUSE_FRAMES_KEY -> 60; BOMB_COOLDOWN_FRAMES_KEY -> 16; else -> 1 }
        FANFARE_CONFIG_TYPE -> 360
        PHYSICS_CONFIG_TYPE -> definition.sites.first().nativeValue ?: ALL_PHYSICS_FIELDS.single { it.key == definition.key }.defaultValue
        else -> ControllerBindings.defaults.getValue(definition.key)
    }
    return definition.sites.map { site ->
        if (parser == null) fallback else {
            val pc = parser.snesToPc(site.native); val rom = parser.getRomData()
            require(pc >= 0 && pc + site.size <= rom.size) { "${definition.label}: ROM data is unavailable." }
            (0 until site.size).fold(0) { value, index -> value or ((rom[pc + index].toInt() and 255) shl (index * 8)) }
        }
    }
}

internal fun nativePatchRomView(patch: SmPatch, definition: DataPatchDefinition, parser: RomParser?): DataPatchView = try {
    if (definition.type == ShortChargePatch.CONFIG_TYPE) {
        val table = nativeRomValues(definition, parser)
        val source = ShortChargePatch.decode(table)
        val stages = ShortChargePatch.requestedStages(patch.configValue, patch.configData)
        DataPatchView(stages ?: source, if (source == null) "Custom counter/timer values are managed in ASM; restore supported timing before using the stage control." else null)
    } else {
    if (definition.key == "seconds") require(patch.configValue == null || patch.configData?.get("seconds") == null ||
        patch.configValue == patch.configData?.get("seconds")) { "Conflicting timer overrides; reset to original or repair the project settings." }
    val value = if (definition.type == ControllerBindings.CONFIG_TYPE) {
        val base = parser?.getRomData()?.let { ControllerBindings.read(it) } ?: ControllerBindings.defaults
        ControllerBindings.resolve(base, patch.configData.orEmpty()).getValue(definition.key)
    } else patch.configData?.get(definition.key) ?: patch.configValue?.takeIf { definition.key == "seconds" }
        ?: definition.decode(nativeRomValues(definition, parser).first())
    DataPatchView(value, if (value == null || value !in definition.range) "This value is outside the supported format/range." else null)
    }
} catch (error: Exception) { DataPatchView(null, error.message ?: "ROM data unavailable.") }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun NativeDataPatchEditor(patch: SmPatch, editor: EditorState, parser: RomParser?, modifier: Modifier = Modifier,
                                   onNavigateToAsm: (() -> Unit)? = null) {
    val type = requireNotNull(patch.configType)
    val definitions = remember(type) { DATA_PATCH_DEFINITIONS.filter { it.type == type } }
    val sourceMode = editor.asmNavigationEnabled
    val bridge = editor.dataPatchBridge.takeIf { sourceMode }
    @Suppress("UNUSED_VARIABLE") val revision = bridge?.revision
    @Suppress("UNUSED_VARIABLE") val version = editor.patchVersion
    fun view(def: DataPatchDefinition) = if (sourceMode) bridge?.value(patch, def.key)
        ?: DataPatchView(null, "Wait for patch source indexing.") else nativePatchRomView(patch, def, parser)
    val views = definitions.associate { it.key to view(it) }
    fun open(key: String? = null) { if (editor.openDataPatchSource(type, key)) onNavigateToAsm?.invoke() }
    fun apply(key: String, value: Int) {
        val changes = if (type == ControllerBindings.CONFIG_TYPE) {
            val mapping = views.mapValues { requireNotNull(it.value.value) }
            ControllerBindings.swap(mapping, key, value)
        } else mapOf(key to value)
        editor.setNativePatchValues(patch.id, changes)
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when (type) { BOMB_CONFIG_TYPE -> "Bombs"; FANFARE_CONFIG_TYPE -> "Item Fanfares"; PHYSICS_CONFIG_TYPE -> "Samus Physics"; ShortChargePatch.CONFIG_TYPE -> "Short Charge"; ControllerBindings.CONFIG_TYPE -> "Controller Defaults"; else -> "Escape Timer" },
                fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (sourceMode) TextButton(onClick = { open() }, enabled = bridge != null, modifier = Modifier.width(64.dp).testTag("native-patch-asm")) { Text("ASM", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { editor.resetNativePatchToOriginal(patch.id) }) { Text(if (sourceMode) "Original ASM" else "Original ROM") }
        }
        Text(when (type) {
            ControllerBindings.CONFIG_TYPE -> "Sets new-game defaults and the title/options screen's Reset to default. Choosing an occupied button swaps its action, just like the game. Existing saves keep their stored controls until reset in Options."
            BOMB_CONFIG_TYPE -> "Five slots are available. The stock three-bomb feel comes from fuse length and cooldown; the slot limit is a separate hard cap."
            FANFARE_CONFIG_TYPE -> "The item-message wait and all nine music-resume delays edit together."
            PHYSICS_CONFIG_TYPE -> "Tune jump heights, gravity, running and air control. Values are bytes (0–255); source edits preserve the complete native value and its other bytes."
            ShortChargePatch.CONFIG_TYPE -> "Choose how many Speed Booster charge stages complete before blue speed. Vanilla uses 4; 0 starts blue speed when Dash running begins. Custom stage timing is preserved. This does not automate the advanced tap timing technique."
            else -> "Time is entered in seconds; ASM stores packed BCD minutes and seconds."
        }, fontSize = LocalEditorTheme.current.fontSize.value.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (definition in definitions) {
            val physicsCategory = if (type == PHYSICS_CONFIG_TYPE) PHYSICS_CATEGORIES.first { category -> category.fields.any { it.key == definition.key } } else null
            if (physicsCategory?.fields?.first()?.key == definition.key) Text(physicsCategory.name,
                fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.SemiBold, color = physicsCategory.color)
            val state = views.getValue(definition.key)
            val selected = nativeSelection?.let { it.first == type && it.second == definition.key } == true
            val bring = remember(definition.id) { BringIntoViewRequester() }
            LaunchedEffect(nativeSelection) { if (selected) bring.bringIntoView() }
            Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bring)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(definition.label, fontSize = LocalEditorTheme.current.fontSize.value.body, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        if (sourceMode) TextButton(onClick = { open(definition.key) }, enabled = bridge != null,
                            modifier = Modifier.width(64.dp).testTag("native-patch-asm-${definition.key}")) { Text("ASM", maxLines = 1, softWrap = false) }
                        val editable = state.problem == null && state.value != null
                        if (type == ControllerBindings.CONFIG_TYPE) {
                            var expanded by remember(definition.id) { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { expanded = true }, enabled = editable,
                                    modifier = Modifier.width(110.dp).testTag("native-patch-${definition.key}")) {
                                    Text(SNES_BUTTONS.firstOrNull { it.bitmask == state.value }?.name ?: "ASM")
                                }
                                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                    SNES_BUTTONS.forEach { button -> DropdownMenuItem(text = { Text(button.name) }, onClick = {
                                        expanded = false; apply(definition.key, button.bitmask)
                                    }, modifier = Modifier.testTag("native-controller-${definition.key}-${button.name}")) }
                                }
                            }
                        } else if (type == ShortChargePatch.CONFIG_TYPE) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (stages in 0..4) FilterChip(selected = state.value == stages, enabled = editable,
                                    onClick = { apply(definition.key, stages) }, label = { Text(stages.toString()) },
                                    modifier = Modifier.testTag("native-short-charge-$stages"))
                            }
                        } else {
                            OutlinedButton(onClick = { apply(definition.key, requireNotNull(state.value) - 1) }, enabled = editable && state.value!! > definition.range.first,
                                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(30.dp)) { Text("−") }
                            var text by remember(definition.id, state.value) { mutableStateOf(state.value?.toString() ?: "—") }
                            BasicTextField(text, onValueChange = { input ->
                                text = input
                                input.toIntOrNull()?.takeIf { it in definition.range }?.let { apply(definition.key, it) }
                            }, enabled = editable, singleLine = true, textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface,
                                fontSize = LocalEditorTheme.current.fontSize.value.body, textAlign = TextAlign.Center),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.width(90.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small).padding(8.dp)
                                    .onFocusChanged { if (!it.isFocused) text = state.value?.toString() ?: "—" }
                                    .testTag("native-patch-${definition.key}"))
                            OutlinedButton(onClick = { apply(definition.key, requireNotNull(state.value) + 1) }, enabled = editable && state.value!! < definition.range.last,
                                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(30.dp)) { Text("+") }
                            if (type == PHYSICS_CONFIG_TYPE) Text(state.value?.let { "\$" + it.toString(16).uppercase().padStart(2, '0') } ?: "—",
                                fontSize = LocalEditorTheme.current.fontSize.value.detail, modifier = Modifier.width(40.dp))
                        }
                    }
                    state.problem?.let { Text(it, fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (type == PHYSICS_CONFIG_TYPE) Text(ALL_PHYSICS_FIELDS.single { it.key == definition.key }.description,
                        fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val presets = when (type) { "ceres_escape_seconds" -> listOf(60, 90, 120, 180, 300)
                        ZEBES_ESCAPE_CONFIG_TYPE -> listOf(60, 180, 300, 600); FANFARE_CONFIG_TYPE -> listOf(16, 240, 360); else -> emptyList() }
                    if (presets.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { value -> OutlinedButton(onClick = { apply(definition.key, value) }, enabled = state.problem == null,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text(value.toString()) } }
                    }
                    if (sourceMode && state.conflict) TextButton(onClick = {
                        runCatching { bridge?.useSource(type, definition.key) }.onFailure { editor.postStatus("Patch edit blocked: ${it.message}") }
                    }) { Text("Use ASM values") }
                }
            }
        }
        if (type == BOMB_CONFIG_TYPE) {
            val fuse = views[BOMB_FUSE_FRAMES_KEY]?.value; val cooldown = views[BOMB_COOLDOWN_FRAMES_KEY]?.value; val cap = views[BOMB_MAX_ACTIVE_KEY]?.value
            if (fuse != null && cooldown != null && cap != null) Text("With this timing: up to ${derivePracticalBombCap(fuse, cooldown, cap)} simultaneous bombs.", fontSize = LocalEditorTheme.current.fontSize.value.detail)
        }
        if (sourceMode) Text("Edits enable this patch automatically. Disabling keeps custom values for later; Undo includes the source and checkbox.",
            fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

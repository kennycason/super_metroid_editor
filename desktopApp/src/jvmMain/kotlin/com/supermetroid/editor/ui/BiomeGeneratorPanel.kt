package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.procgen.BiomeRules
import com.supermetroid.editor.procgen.BiomeStyle
import com.supermetroid.editor.procgen.BiomeTheme
import com.supermetroid.editor.procgen.StructureAlgorithm
import com.supermetroid.editor.procgen.TilesetProfile
import com.supermetroid.editor.procgen.TilesetProfileCache
import com.supermetroid.editor.procgen.WfcOptions
import com.supermetroid.editor.rom.RomParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Generative biome builder panel: rolls a seeded rule card for the chosen
 * structural style, picks a visual/atmospheric theme (tileset + optional
 * recolor + FX), learns a tile vocabulary from every vanilla room sharing the
 * theme's tileset, and regenerates the current room as one undoable layout
 * operation. Doors and their frames are always preserved. Theme changes
 * (tileset, palette, FX) persist on the room and are not part of undo.
 */
@Composable
fun BiomeGeneratorPanel(
    editorState: EditorState,
    romParser: RomParser?,
    rooms: List<RoomInfo>,
    modifier: Modifier = Modifier,
) {
    val styleChoices = remember {
        listOf(BiomeStyle.PIPE_MAZE, BiomeStyle.WAVE_FUNCTION) +
            BiomeStyle.values().filter { it != BiomeStyle.PIPE_MAZE && it != BiomeStyle.WAVE_FUNCTION }
    }
    var style by remember { mutableStateOf(BiomeStyle.PIPE_MAZE) }
    var theme by remember { mutableStateOf(BiomeTheme.KEEP) }
    var seed by remember { mutableStateOf(Random.nextInt(0, 1_000_000).toLong()) }
    var seedText by remember { mutableStateOf(seed.toString()) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmGenerateAll by remember { mutableStateOf(false) }
    var confirmRevertAll by remember { mutableStateOf(false) }
    var omitSpecialRooms by remember { mutableStateOf(true) }

    val roomLoaded = editorState.workingBlocksWide > 0 && editorState.workingBlocksTall > 0
    val resolvedTheme = remember(theme, seed) { theme.resolve(seed) }
    // Dress with the theme's tileset when set, else the loaded room's tileset.
    val targetTilesetId = resolvedTheme.tilesetId ?: editorState.currentTilesetId

    var profile by remember { mutableStateOf<Pair<Int, TilesetProfile>?>(null) }
    LaunchedEffect(romParser, targetTilesetId) {
        if (profile?.first == targetTilesetId) return@LaunchedEffect
        profile = null
        val rp = romParser ?: return@LaunchedEffect
        profile = withContext(Dispatchers.Default) {
            val headers = rooms.mapNotNull { rp.readRoomHeader(it.getRoomIdAsInt()) }
            targetTilesetId to TilesetProfileCache.getOrLearn(rp, headers, targetTilesetId)
        }
    }

    val baseRules = remember(style, seed) { BiomeRules.roll(style, seed) }
    var platforms by remember(baseRules) { mutableStateOf(baseRules.platformDensity.toFloat()) }
    var hazards by remember(baseRules) { mutableStateOf(baseRules.hazardDensity.toFloat()) }
    var destructibles by remember(baseRules) { mutableStateOf(baseRules.destructibleDensity.toFloat()) }
    var mazeBranches by remember(baseRules) { mutableStateOf(baseRules.mazeBranchDensity.toFloat()) }
    var mazeLoops by remember(baseRules) { mutableStateOf(baseRules.mazeLoopDensity.toFloat()) }
    var mazeHub by remember(baseRules) { mutableStateOf(baseRules.mazeHubSize.toFloat()) }
    var mazeEmptyCenter by remember(baseRules) { mutableStateOf(baseRules.mazeEmptyCenter) }
    var wfcDetail by remember { mutableStateOf(0.55f) }
    var wfcTunnelWidth by remember { mutableStateOf(2f) }
    var wfcBendiness by remember { mutableStateOf(0.35f) }
    var wfcBombs by remember { mutableStateOf(false) }
    var wfcMissiles by remember { mutableStateOf(false) }
    var wfcCrumble by remember { mutableStateOf(false) }
    var wfcSpikes by remember { mutableStateOf(false) }
    var keepLandingSiteShipClear by remember { mutableStateOf(true) }
    val wfcTunnelTiles = (wfcTunnelWidth + 0.5f).toInt().coerceIn(1, 4)
    val effectiveRules = remember(
        baseRules, platforms, hazards, destructibles, mazeBranches, mazeLoops, mazeHub, mazeEmptyCenter,
    ) {
        if (baseRules.algorithm == StructureAlgorithm.MAZE) {
            baseRules.withMazeOverrides(
                mazeBranches.toDouble(),
                mazeLoops.toDouble(),
                mazeHub.toDouble(),
                mazeEmptyCenter,
            )
        } else {
            baseRules.withOverrides(platforms.toDouble(), hazards.toDouble(), destructibles.toDouble())
        }
    }
    fun currentWfcOptions() = WfcOptions(
        morphAmount = wfcDetail.toDouble(),
        tunnelWidth = wfcTunnelTiles,
        tunnelBendiness = wfcBendiness.toDouble(),
        allowBombs = wfcBombs,
        allowMissiles = wfcMissiles,
        allowCrumble = wfcCrumble,
        allowSpikes = wfcSpikes,
    )
    val prof = profile?.takeIf { it.first == targetTilesetId }?.second

    Column(
        modifier = modifier.padding(8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            var styleMenuOpen by remember { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { styleMenuOpen = true },
            ) {
                Text(
                    style.displayName,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
                DropdownMenu(expanded = styleMenuOpen, onDismissRequest = { styleMenuOpen = false }) {
                    for (s in styleChoices) {
                        DropdownMenuItem(
                            text = { Text(s.displayName, fontSize = 11.sp) },
                            onClick = { style = s; styleMenuOpen = false },
                        )
                    }
                }
            }
            var themeMenuOpen by remember { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { themeMenuOpen = true },
            ) {
                Text(
                    theme.displayName,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
                DropdownMenu(expanded = themeMenuOpen, onDismissRequest = { themeMenuOpen = false }) {
                    for (t in BiomeTheme.THEMES) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(t.displayName, fontSize = 11.sp)
                                    Text(
                                        t.blurb,
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = { theme = t; themeMenuOpen = false },
                        )
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = seedText,
                onValueChange = { text ->
                    seedText = text
                    text.toLongOrNull()?.let { seed = it }
                },
                label = { Text("Seed", fontSize = 9.sp) },
                textStyle = TextStyle(fontSize = 11.sp),
                singleLine = true,
                modifier = Modifier.width(132.dp).height(52.dp),
            )
            OutlinedButton(
                onClick = {
                    seed = Random.nextInt(0, 1_000_000).toLong()
                    seedText = seed.toString()
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            ) {
                Text("Random", fontSize = 11.sp)
            }
        }

        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val themeLine = if (resolvedTheme.tilesetId != null) {
                "\nTheme: ${resolvedTheme.displayName} — ${resolvedTheme.blurb}"
            } else ""
            Text(
                effectiveRules.describe() + themeLine,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }

        if (effectiveRules.algorithm == StructureAlgorithm.MAZE) {
            LabeledSlider("Branches", mazeBranches) { mazeBranches = it }
            LabeledSlider("Loops", mazeLoops) { mazeLoops = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = mazeEmptyCenter, onCheckedChange = { mazeEmptyCenter = it })
                Text("Empty center", fontSize = 10.sp)
            }
            if (mazeEmptyCenter) {
                LabeledSlider("Center size", mazeHub) { mazeHub = it }
            }
        } else if (effectiveRules.algorithm == StructureAlgorithm.WFC) {
            LabeledSlider("Sample detail", wfcDetail) { wfcDetail = it }
            LabeledSlider(
                "Tunnel width",
                wfcTunnelWidth,
                valueRange = 1f..4f,
                steps = 2,
                valueText = "${wfcTunnelTiles}t",
            ) { wfcTunnelWidth = it }
            LabeledSlider("Bendiness", wfcBendiness) { wfcBendiness = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactCheckbox("Bombs", wfcBombs) { wfcBombs = it }
                CompactCheckbox("Missiles", wfcMissiles) { wfcMissiles = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactCheckbox("Crumble", wfcCrumble) { wfcCrumble = it }
                CompactCheckbox("Spikes", wfcSpikes) { wfcSpikes = it }
            }
        } else {
            LabeledSlider("Platforms", platforms) { platforms = it }
            LabeledSlider("Hazards", hazards) { hazards = it }
            LabeledSlider("Destructibles", destructibles) { destructibles = it }
        }

        if (editorState.currentRoomId == 0x91F8) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = keepLandingSiteShipClear, onCheckedChange = { keepLandingSiteShipClear = it })
                Text("Keep Landing Site ship clear", fontSize = 10.sp)
            }
        }

        Button(
            enabled = roomLoaded && prof != null && romParser != null,
            onClick = {
                if (prof == null || romParser == null) return@Button
                editorState.applyBiomeTheme(resolvedTheme, romParser)
                val applied = editorState.generateBiome(
                    effectiveRules,
                    prof,
                    seed,
                    keepLandingSiteShipClear = keepLandingSiteShipClear,
                    romParser = romParser,
                    wfcOptions = currentWfcOptions(),
                )
                status = buildString {
                    append(if (applied > 0) "Rewrote $applied tiles" else "No layout changes")
                    if (resolvedTheme.tilesetId != null) append(" as ${resolvedTheme.displayName}")
                    append(" (scrolls reset, Ctrl+Z undoes)")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Generate room", fontSize = 11.sp)
        }
        Text(
            when {
                !roomLoaded -> "Load a room first"
                prof == null -> "Learning tileset..."
                else -> "Learned from ${prof.roomsSampled} room(s), tileset $targetTilesetId"
            },
            fontSize = 9.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        status?.let {
            Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
        }
        OutlinedButton(
            enabled = roomLoaded && romParser != null,
            onClick = {
                val rp = romParser ?: return@OutlinedButton
                status = if (editorState.resetCurrentRoomToOriginal(rp)) {
                    "Reset room to original ROM state"
                } else {
                    "Could not reset room"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset room", fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = omitSpecialRooms, onCheckedChange = { omitSpecialRooms = it })
            Text("Omit utility and boss rooms", fontSize = 10.sp)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                enabled = prof != null && romParser != null,
                onClick = { confirmGenerateAll = true },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            ) {
                Text("Generate all", fontSize = 11.sp)
            }
            OutlinedButton(
                enabled = romParser != null,
                onClick = { confirmRevertAll = true },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            ) {
                Text("Revert all", fontSize = 11.sp)
            }
        }
    }

    if (confirmGenerateAll) {
        AlertDialog(
            onDismissRequest = { confirmGenerateAll = false },
            title = { Text("Generate all rooms?") },
            text = {
                Text(
                    buildString {
                        append("This will replace generated biome edits across supported rooms using the current style, theme, and seed.")
                        if (omitSpecialRooms) append(" Utility and boss rooms will be skipped.")
                        append(" Rooms with manual edits will be skipped; use Generate room for those.")
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmGenerateAll = false
                        val rp = romParser ?: return@Button
                        val result = editorState.generateBiomeForAllRooms(
                            effectiveRules,
                            resolvedTheme,
                            seed,
                            rp,
                            wfcOptions = currentWfcOptions(),
                            omitSpecialRooms = omitSpecialRooms,
                        )
                        status = buildString {
                            append("Generated ${result.generatedRooms} rooms, skipped ${result.skippedRooms}, rewrote ${result.changedTiles} tiles")
                            if (result.manualSkippedRooms > 0) append(" (${result.manualSkippedRooms} manual)")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Generate all")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmGenerateAll = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (confirmRevertAll) {
        AlertDialog(
            onDismissRequest = { confirmRevertAll = false },
            title = { Text("Revert all generated rooms?") },
            text = {
                Text("This will remove generated biome edits from every room in the project.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmRevertAll = false
                        val rp = romParser ?: return@Button
                        val result = editorState.resetGeneratedBiomeRooms(rp)
                        status = "Reverted generated edits in ${result.generatedRooms} rooms"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Revert all")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevertAll = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    valueText: String = "${(value * 100).toInt()}%",
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, modifier = Modifier.width(96.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.weight(1f).height(24.dp),
        )
        Text(valueText, fontSize = 9.sp, modifier = Modifier.width(38.dp))
    }
}

@Composable
private fun CompactCheckbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(118.dp)) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, fontSize = 10.sp)
    }
}

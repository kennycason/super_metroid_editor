package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.supermetroid.editor.procgen.TilesetProfile
import com.supermetroid.editor.procgen.TilesetProfileCache
import com.supermetroid.editor.rom.RomParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Generative biome builder panel: rolls a seeded rule card for the chosen
 * biome style, learns a tile vocabulary from every vanilla room that shares
 * the current room's tileset, and regenerates the current room's layout as a
 * single undoable operation. Doors and their frames are always preserved.
 */
@Composable
fun BiomeGeneratorPanel(
    editorState: EditorState,
    romParser: RomParser?,
    rooms: List<RoomInfo>,
    modifier: Modifier = Modifier,
) {
    var style by remember { mutableStateOf(BiomeStyle.CAVERN) }
    var seed by remember { mutableStateOf(Random.nextInt(0, 1_000_000).toLong()) }
    var seedText by remember { mutableStateOf(seed.toString()) }
    var status by remember { mutableStateOf<String?>(null) }

    val tilesetId = editorState.editorTilesetId
    val roomLoaded = editorState.workingBlocksWide > 0 && editorState.workingBlocksTall > 0

    var profile by remember { mutableStateOf<TilesetProfile?>(null) }
    LaunchedEffect(romParser, tilesetId) {
        profile = null
        val rp = romParser ?: return@LaunchedEffect
        profile = withContext(Dispatchers.Default) {
            val headers = rooms.mapNotNull { rp.readRoomHeader(it.getRoomIdAsInt()) }
            TilesetProfileCache.getOrLearn(rp, headers, tilesetId)
        }
    }

    val baseRules = remember(style, seed) { BiomeRules.roll(style, seed) }
    var platforms by remember(baseRules) { mutableStateOf(baseRules.platformDensity.toFloat()) }
    var hazards by remember(baseRules) { mutableStateOf(baseRules.hazardDensity.toFloat()) }
    var destructibles by remember(baseRules) { mutableStateOf(baseRules.destructibleDensity.toFloat()) }
    val effectiveRules = remember(baseRules, platforms, hazards, destructibles) {
        baseRules.withOverrides(platforms.toDouble(), hazards.toDouble(), destructibles.toDouble())
    }

    Column(
        modifier = modifier.padding(8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    for (s in BiomeStyle.values()) {
                        DropdownMenuItem(
                            text = { Text(s.displayName, fontSize = 11.sp) },
                            onClick = { style = s; styleMenuOpen = false },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = seedText,
                onValueChange = { text ->
                    seedText = text
                    text.toLongOrNull()?.let { seed = it }
                },
                label = { Text("Seed", fontSize = 9.sp) },
                textStyle = TextStyle(fontSize = 11.sp),
                singleLine = true,
                modifier = Modifier.width(110.dp).height(52.dp),
            )
            OutlinedButton(
                onClick = {
                    seed = Random.nextInt(0, 1_000_000).toLong()
                    seedText = seed.toString()
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            ) {
                Text("🎲", fontSize = 12.sp)
            }
        }

        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                effectiveRules.describe(),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }

        LabeledSlider("Platforms", platforms) { platforms = it }
        LabeledSlider("Hazards", hazards) { hazards = it }
        LabeledSlider("Destructibles", destructibles) { destructibles = it }

        val prof = profile
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = roomLoaded && prof != null,
                onClick = {
                    if (prof == null) return@Button
                    val applied = editorState.generateBiome(effectiveRules, prof, seed)
                    status = if (applied > 0) "Rewrote $applied tiles (Ctrl+Z to undo)" else "No changes"
                },
            ) {
                Text("Generate room", fontSize = 11.sp)
            }
            Text(
                when {
                    !roomLoaded -> "Load a room first"
                    prof == null -> "Learning tileset…"
                    else -> "Learned from ${prof.roomsSampled} room(s), tileset $tilesetId"
                },
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        status?.let {
            Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, modifier = Modifier.width(84.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f).height(24.dp),
        )
        Text("${(value * 100).toInt()}%", fontSize = 9.sp, modifier = Modifier.width(32.dp))
    }
}

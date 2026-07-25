package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.RomParser

internal enum class SpriteSortMode(val label: String) {
    DEFAULT("Default"),
    NAME("A-Z"),
    SPECIES_ID("ID"),
}

internal fun spriteMatchesQuery(
    entry: EnemySpriteGraphics.Companion.EnemySpriteEntry,
    query: String,
): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    val lower = trimmed.lowercase()
    val compact = lower.filter { it.isLetterOrDigit() }.removePrefix("0x")
    val speciesHex = entry.speciesId.toString(16).padStart(4, '0').lowercase()
    return entry.name.lowercase().contains(lower) ||
        entry.category.lowercase().contains(lower) ||
        speciesHex.contains(compact) ||
        "a0$speciesHex".contains(compact)
}

internal fun samusMatchesSpriteQuery(query: String): Boolean {
    val lower = query.trim().lowercase()
    return lower.isEmpty() || "samus".contains(lower) || "player".contains(lower)
}

internal fun sortSpriteEntries(
    entries: List<EnemySpriteGraphics.Companion.EnemySpriteEntry>,
    mode: SpriteSortMode,
): List<EnemySpriteGraphics.Companion.EnemySpriteEntry> =
    when (mode) {
        SpriteSortMode.DEFAULT -> entries
        SpriteSortMode.NAME -> entries.sortedWith(compareBy({ it.name.lowercase() }, { it.speciesId }))
        SpriteSortMode.SPECIES_ID -> entries.sortedBy { it.speciesId }
    }

/**
 * Left sidebar for the Sprites tab: search bar, sort controls, and scrollable sprite list.
 *
 * [selectedSpriteIdx] is -1 for Samus, otherwise an index into
 * [EnemySpriteGraphics.EDITOR_ENEMIES]. [onSelectSprite] is called when the user picks a sprite.
 */
@Composable
internal fun SpritesTabSidebar(
    selectedSpriteIdx: Int,
    onSelectSprite: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    var spriteSearchQuery by remember { mutableStateOf("") }
    var spriteSortMode by remember { mutableStateOf(SpriteSortMode.DEFAULT) }

    val entries = EnemySpriteGraphics.EDITOR_ENEMIES
    val focusRequester = rememberVerticalSelectionFocusRequester()
    val indexBySpecies = remember(entries) {
        entries.mapIndexed { index, entry -> entry.speciesId to index }.toMap()
    }
    val categories = remember(entries) { entries.map { it.category }.distinct() }
    val showSamus = samusMatchesSpriteQuery(spriteSearchQuery)
    val visibleGroups = remember(categories, spriteSearchQuery, spriteSortMode) {
        val filtered = entries.filter { spriteMatchesQuery(it, spriteSearchQuery) }
        categories.mapNotNull { category ->
            val items = sortSpriteEntries(filtered.filter { it.category == category }, spriteSortMode)
            if (items.isEmpty()) null else category to items
        }
    }
    val visibleSelectionKeys = remember(showSamus, visibleGroups, indexBySpecies) {
        buildList {
            if (showSamus) add(-1)
            visibleGroups.forEach { (_, items) ->
                items.forEach { entry -> indexBySpecies[entry.speciesId]?.let(::add) }
            }
        }
    }
    val selectedVisibleIndex = visibleSelectionKeys.indexOf(selectedSpriteIdx)

    // When search/sort filters change the visible list, ensure selected sprite stays visible.
    LaunchedEffect(visibleSelectionKeys) {
        if (visibleSelectionKeys.isNotEmpty() && selectedSpriteIdx !in visibleSelectionKeys) {
            onSelectSprite(visibleSelectionKeys.first())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp)
            .verticalSelectionKeyNavigation(
                focusRequester = focusRequester,
                itemCount = visibleSelectionKeys.size,
                selectedIndex = selectedVisibleIndex,
                onSelectIndex = { index ->
                    visibleSelectionKeys.getOrNull(index)?.let { onSelectSprite(it) }
                }
            )
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AppOutlinedTextField(
            value = spriteSearchQuery,
            onValueChange = { spriteSearchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Search sprites",
            singleLine = true,
            fontSize = fs.body
        )
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SpriteSortMode.values().forEach { mode ->
                val selected = spriteSortMode == mode
                val colors = if (selected) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) else ButtonDefaults.outlinedButtonColors()
                val buttonModifier = Modifier.height(28.dp)
                val contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                if (selected) {
                    Button(
                        onClick = { requestVerticalSelectionFocus(focusRequester); spriteSortMode = mode },
                        modifier = buttonModifier, contentPadding = contentPadding, colors = colors,
                    ) { Text(mode.label, fontSize = fs.detail) }
                } else {
                    OutlinedButton(
                        onClick = { requestVerticalSelectionFocus(focusRequester); spriteSortMode = mode },
                        modifier = buttonModifier, contentPadding = contentPadding, colors = colors,
                    ) { Text(mode.label, fontSize = fs.detail) }
                }
            }
        }
        Text(
            "${visibleSelectionKeys.size}/${entries.size + 1} sprites",
            fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        if (showSamus) {
            Text("Player", fontSize = fs.body, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    requestVerticalSelectionFocus(focusRequester)
                    onSelectSprite(-1)
                },
                color = if (selectedSpriteIdx == -1) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    "Samus", fontSize = fs.body,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    color = if (selectedSpriteIdx == -1) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (!showSamus && visibleGroups.isEmpty()) {
            Text("No sprites match this search.", fontSize = fs.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        for ((category, items) in visibleGroups) {
            Text(category, fontSize = fs.body, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            for (entry in items) {
                val idx = indexBySpecies[entry.speciesId] ?: continue
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        requestVerticalSelectionFocus(focusRequester)
                        onSelectSprite(idx)
                    },
                    color = if (selectedSpriteIdx == idx) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        entry.name, fontSize = fs.body,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        color = if (selectedSpriteIdx == idx) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Right canvas for the Sprites tab: dispatches to Samus, Phantoon, Kraid, or generic enemy viewer. */
@Composable
internal fun SpritesTabCanvas(
    selectedSpriteIdx: Int,
    romParser: RomParser?,
    editorState: EditorState,
    modifier: Modifier = Modifier,
) {
    if (selectedSpriteIdx == -1) {
        SamusSpriteViewer(romParser = romParser, editorState = editorState, modifier = modifier)
    } else {
        val entries = EnemySpriteGraphics.EDITOR_ENEMIES
        val selected = entries.getOrNull(selectedSpriteIdx) ?: entries.first()
        when (selected.speciesId) {
            0xE4BF -> PhantoonSpriteEditor(editorState = editorState, romParser = romParser, modifier = modifier)
            0xE2BF -> KraidSpriteEditor(editorState = editorState, romParser = romParser, showOamComponents = true, modifier = modifier)
            else -> EnemySpriteViewer(entry = selected, romParser = romParser, editorState = editorState, modifier = modifier)
        }
    }
}

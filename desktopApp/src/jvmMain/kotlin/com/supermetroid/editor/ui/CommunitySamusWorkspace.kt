@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.SamusCommunityAnimationCatalog
import com.supermetroid.editor.rom.SamusCommunitySheetDecoder
import com.supermetroid.editor.rom.SamusSpriteDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CommunitySamusView(val label: String) {
    ANIMATIONS("Animations"),
    ALL_FRAMES("All Frames"),
    SOURCE_DETAILS("Source Details"),
}

/** Animation-first workspace for a decoded SpriteSomething community sheet. */
@Composable
internal fun CommunitySamusWorkspace(
    sheet: SamusCommunitySheetDecoder.DecodedSheet,
    sessionKey: String,
    selectedSuit: SamusSpriteDecoder.SuitType,
    onStatus: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val font = LocalEditorTheme.current.fontSize.value
    val catalogResult = remember { runCatching(SamusCommunityAnimationCatalog::loadBundled) }
    val catalog = catalogResult.getOrNull()
    var selectedView by remember(sessionKey) { mutableStateOf(CommunitySamusView.ANIMATIONS) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF1C2033)).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommunitySamusView.entries.forEach { view ->
                FilterChip(
                    selected = selectedView == view,
                    onClick = { selectedView = view },
                    label = { Text(view.label, fontSize = font.body) },
                    modifier = Modifier.height(30.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "SpriteSomething ${SamusCommunitySheetDecoder.FORMAT_ID.substringAfter('-')}",
                fontSize = font.detail,
                color = Color(0xFF7F89A5),
            )
        }

        when (selectedView) {
            CommunitySamusView.ANIMATIONS -> {
                if (catalog == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "The bundled animation map could not be loaded: ${catalogResult.exceptionOrNull()?.message}",
                            fontSize = font.body,
                            color = Color(0xFFFCA5A5),
                        )
                    }
                } else {
                    CommunitySamusAnimationBrowser(catalog, sheet, sessionKey, selectedSuit, onStatus, Modifier.fillMaxSize())
                }
            }
            CommunitySamusView.ALL_FRAMES -> CommunitySamusFrameGallery(sheet, sessionKey, selectedSuit, Modifier.fillMaxSize())
            CommunitySamusView.SOURCE_DETAILS -> Column(Modifier.fillMaxSize()) {
                CommunitySheetMetrics(sheet)
                CommunityRegionBrowser(sheet, sessionKey, selectedSuit, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CommunitySamusAnimationBrowser(
    catalog: SamusCommunityAnimationCatalog,
    sheet: SamusCommunitySheetDecoder.DecodedSheet,
    sessionKey: String,
    selectedSuit: SamusSpriteDecoder.SuitType,
    onStatus: (String) -> Unit,
    modifier: Modifier,
) {
    val font = LocalEditorTheme.current.fontSize.value
    var selectedGroupIndex by remember(sessionKey) { mutableStateOf(0) }
    var selectedVariantIndex by remember(sessionKey) { mutableStateOf(0) }
    var playing by remember(sessionKey) { mutableStateOf(false) }
    var currentFrame by remember(sessionKey) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val group = catalog.groups.getOrElse(selectedGroupIndex) { catalog.groups.first() }
    val variant = group.variants.getOrElse(selectedVariantIndex) { group.variants.first() }
    val animation = remember(sheet, group, variant, selectedSuit) {
        currentFrame = 0
        catalog.buildAnimation(sheet, group, variant, selectedSuit)
    }
    val unresolved = remember(sheet, group) {
        group.variants.asSequence()
            .flatMap { it.frames.asSequence() }
            .flatMap { it.tiles.asSequence() }
            .map { it.imageName }
            .filter { requested ->
                requested !in sheet.images &&
                    (!requested.startsWith("optional_") || requested.removePrefix("optional_") !in sheet.images)
            }
            .toSortedSet()
    }

    Row(modifier) {
        Column(
            Modifier.width(220.dp).fillMaxHeight().background(Color(0xFF191D2F))
                .verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Animations", fontSize = font.body, color = Color(0xFFB8C0D8), fontWeight = FontWeight.SemiBold)
            Text(
                "${catalog.groups.size} groups · ${catalog.variantCount} variants",
                fontSize = font.detail,
                color = Color(0xFF737D99),
                modifier = Modifier.padding(bottom = 5.dp),
            )
            catalog.groups.forEachIndexed { index, choice ->
                val selected = index == selectedGroupIndex
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        selectedGroupIndex = index
                        selectedVariantIndex = 0
                        currentFrame = 0
                    },
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                        Text(
                            choice.name,
                            fontSize = font.body,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFFC0C7DC),
                        )
                        Text(
                            "${choice.variants.size} variants · ${choice.variants.sumOf { it.frames.size }} frames",
                            fontSize = font.detail,
                            color = Color(0xFF747E99),
                        )
                    }
                }
            }
        }

        Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF30354E)))

        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(group.name, fontSize = font.heading, color = Color(0xFFF0F2FC), fontWeight = FontWeight.Bold)
            Text(
                "Community sheet animation · ${selectedSuit.name.lowercase().replaceFirstChar { it.uppercase() }} palette · SpriteSomething timing and composition",
                fontSize = font.detail,
                color = Color(0xFF8993AE),
            )
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                group.variants.forEachIndexed { index, choice ->
                    FilterChip(
                        selected = index == selectedVariantIndex,
                        onClick = {
                            selectedVariantIndex = index
                            currentFrame = 0
                        },
                        label = {
                            Text(
                                SamusCommunityAnimationCatalog.humanize(choice.name),
                                fontSize = font.body,
                            )
                        },
                    )
                }
            }

            if (unresolved.isNotEmpty()) {
                Surface(
                    color = Color(0xFF3A321C),
                    shape = RoundedCornerShape(5.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                ) {
                    Text(
                        if (unresolved.all { it.startsWith("optional_ship_") }) {
                            "This WIP preview references SpriteSomething's external ship art; the custom sheet does not contain those pieces."
                        } else {
                            "${unresolved.size} optional composition piece${if (unresolved.size == 1) " is" else "s are"} not stored in this sheet."
                        },
                        fontSize = font.detail,
                        color = Color(0xFFFDE68A),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            AnimationPlayer(
                animation = animation,
                previewSize = 320,
                playing = playing,
                onPlayingChanged = { playing = it },
                currentFrame = currentFrame,
                onFrameChanged = { currentFrame = it },
                onExportPng = { frame, frameIndex ->
                    val file = choosePngFile(
                        dialogTitle = "Save Community Samus Frame as PNG",
                        defaultName = "samus_community_${exportSafeName(group.name)}_${exportSafeName(variant.name)}_frame$frameIndex.png",
                    ) ?: return@AnimationPlayer
                    scope.launch {
                        onStatus("Exporting ${file.name}…")
                        val result = runCatching { withContext(Dispatchers.IO) { exportFramePng(frame, file) } }
                        onStatus(result.fold(
                            onSuccess = { "Exported ${file.name}" },
                            onFailure = { problem -> "Export failed: ${problem.message ?: problem::class.simpleName}" },
                        ))
                    }
                },
                onExportGif = { selectedAnimation ->
                    val file = chooseGifFile(
                        dialogTitle = "Save Community Samus Animation as GIF",
                        defaultName = "samus_community_${exportSafeName(group.name)}_${exportSafeName(variant.name)}.gif",
                    ) ?: return@AnimationPlayer
                    scope.launch {
                        onStatus("Exporting ${file.name}…")
                        val result = runCatching {
                            withContext(Dispatchers.Default) { exportAnimationGif(selectedAnimation, file) }
                        }
                        onStatus(result.fold(
                            onSuccess = { "Exported ${file.name}" },
                            onFailure = { problem -> "Export failed: ${problem.message ?: problem::class.simpleName}" },
                        ))
                    }
                },
                onExportSheet = { selectedAnimation ->
                    val file = choosePngFile(
                        dialogTitle = "Save Community Samus Sprite Sheet as PNG",
                        defaultName = "samus_community_${exportSafeName(group.name)}_${exportSafeName(variant.name)}_sheet.png",
                    ) ?: return@AnimationPlayer
                    scope.launch {
                        onStatus("Exporting ${file.name}…")
                        val result = runCatching {
                            withContext(Dispatchers.IO) { exportAnimationSheet(selectedAnimation, file) }
                        }
                        onStatus(result.fold(
                            onSuccess = { "Exported ${file.name}" },
                            onFailure = { problem -> "Export failed: ${problem.message ?: problem::class.simpleName}" },
                        ))
                    }
                },
            )
        }
    }
}

@Composable
private fun CommunitySamusFrameGallery(
    sheet: SamusCommunitySheetDecoder.DecodedSheet,
    sessionKey: String,
    selectedSuit: SamusSpriteDecoder.SuitType,
    modifier: Modifier,
) {
    val font = LocalEditorTheme.current.fontSize.value
    var query by remember(sessionKey) { mutableStateOf("") }
    var category by remember(sessionKey) { mutableStateOf(CommunitySamusRegionCategory.ALL) }
    var selectedName by remember(sessionKey) {
        mutableStateOf(if ("stand_right" in sheet.images) "stand_right" else sheet.images.keys.first())
    }
    val images = remember(sheet, query, category) {
        sheet.images.values.filter { communitySamusRegionMatches(it, query, category) }
    }
    val selected = sheet.images[selectedName] ?: images.firstOrNull() ?: sheet.images.values.first()

    Column(modifier.background(Color(0xFF171A2A)).padding(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Find a frame", fontSize = font.detail) },
                placeholder = { Text("run, morph, file select…", fontSize = font.detail) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = font.body),
                modifier = Modifier.width(270.dp),
            )
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CommunitySamusRegionCategory.entries.forEach { choice ->
                    FilterChip(
                        selected = category == choice,
                        onClick = { category = choice },
                        label = { Text(choice.label, fontSize = font.detail) },
                        modifier = Modifier.height(29.dp),
                    )
                }
            }
            Text("${images.size} frames", fontSize = font.detail, color = Color(0xFF8993AE))
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(118.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(images, key = { it.name }) { image ->
                    val active = image.name == selected.name
                    val bitmap = remember(image, sheet, selectedSuit) { image.toImageBitmap(sheet, selectedSuit) }
                    Box(
                        modifier = Modifier.fillMaxWidth().height(142.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            modifier = Modifier.width(118.dp).fillMaxHeight().clickable { selectedName = image.name },
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else Color(0xFF22263B),
                            shape = RoundedCornerShape(7.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (active) MaterialTheme.colorScheme.primary else Color(0xFF343A54),
                            ),
                        ) {
                            Column(
                                Modifier.fillMaxSize().padding(7.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().weight(1f)
                                        .background(Color(0xFF1B1F31), RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = image.name,
                                        modifier = Modifier.fillMaxSize().padding(4.dp),
                                        contentScale = ContentScale.Fit,
                                        filterQuality = FilterQuality.None,
                                    )
                                }
                                Text(
                                    humanizeCommunityName(image.name),
                                    fontSize = font.detail,
                                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFFD0D6E9),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 5.dp),
                                )
                                Text(
                                    "${image.width} × ${image.height}",
                                    fontSize = font.statusBar,
                                    color = Color(0xFF7D87A2),
                                )
                            }
                        }
                    }
                }
            }

            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF30354E)))
            CommunityRegionDetails(selected, sheet, selectedSuit, Modifier.width(330.dp))
        }
    }
}

package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.MapRandoSamusSprite
import com.supermetroid.editor.rom.SamusCommunitySheetDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal fun CommunitySamusCatalogBrowser(
    snapshot: CommunitySamusCatalogSnapshot,
    activeCatalogName: String?,
    downloadingName: String?,
    isDownloaded: (MapRandoSamusSprite) -> Boolean,
    loadShowcase: (MapRandoSamusSprite) -> CommunitySamusCatalogShowcase?,
    onDownloadPreview: (MapRandoSamusSprite) -> Unit,
    onUseInProject: (MapRandoSamusSprite) -> Unit,
    onOpenDetails: (MapRandoSamusSprite) -> Unit,
    onRefresh: () -> Unit,
    onImportFile: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val sidebarWidth = when (fs) {
        FontSize.SMALL -> 310.dp
        FontSize.MEDIUM -> 340.dp
        FontSize.LARGE -> 380.dp
        FontSize.LARGER -> 430.dp
    }
    var query by remember(snapshot.revision) { mutableStateOf("") }
    var category by remember(snapshot.revision) { mutableStateOf<String?>(null) }
    var selectedName by remember(snapshot.revision) {
        mutableStateOf(
            snapshot.sprites.firstOrNull { it.name == activeCatalogName }?.name
                ?: snapshot.sprites.firstOrNull()?.name.orEmpty(),
        )
    }
    val categories = remember(snapshot.revision) { snapshot.sprites.map { it.category }.distinct() }
    val filtered = remember(snapshot.revision, query, category) {
        snapshot.sprites.filter { communitySamusCatalogMatches(it, query, category) }
    }
    val selected = snapshot.sprites.firstOrNull { it.name == selectedName }
        ?: filtered.firstOrNull()
        ?: snapshot.sprites.first()
    val romReadyCatalog = snapshot.revision.equals(
        CommunitySamusCatalogRepository.INJECTABLE_CATALOG_REVISION,
        ignoreCase = true,
    )
    val selectedDownloaded = isDownloaded(selected)
    var showcase by remember(snapshot.revision, selected.name) {
        mutableStateOf<CommunitySamusCatalogShowcase?>(null)
    }
    var showcaseLoading by remember(snapshot.revision, selected.name) { mutableStateOf(false) }
    LaunchedEffect(snapshot.revision, selected.name, selectedDownloaded) {
        showcase = null
        if (!selectedDownloaded) return@LaunchedEffect
        showcaseLoading = true
        showcase = withContext(Dispatchers.IO) {
            runCatching { loadShowcase(selected) }.getOrNull()
        }
        showcaseLoading = false
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Column(Modifier.width(COMMUNITY_CATALOG_HEADER_WIDTH.dp)) {
                    Text("Community Samus Catalog", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = fs.heading)
                    Text(
                        "${snapshot.sprites.size} sprites · upstream ${snapshot.revision.take(8)}" +
                            (if (snapshot.usedCachedCatalog) " · saved catalog" else "") +
                            (if (romReadyCatalog) " · ROM-ready" else " · preview-only revision"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = fs.detail,
                    )
                }
                CatalogAction("Refresh", onRefresh)
                CatalogAction("Import Local PNG…", onImportFile)
                CatalogAction("ROM Animations", onBack)
            }
        }

        snapshot.notice?.let { notice ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(notice, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = fs.detail, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val detailsWidth = responsiveCommunityPaneWidth(
                availableWidth = maxWidth,
                leadingWidth = sidebarWidth + COMMUNITY_PANE_DIVIDER_WIDTH.dp,
                minimumWidth = COMMUNITY_CATALOG_DETAILS_WIDTH.dp,
            )
            Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                Column(
                    Modifier.width(sidebarWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Find sprite or artist", fontSize = fs.detail) },
                        placeholder = { Text("Zero Mission, TarThoron…", fontSize = fs.detail) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = fs.body),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = category == null,
                            onClick = { category = null },
                            label = { Text("All", fontSize = fs.detail) },
                            modifier = Modifier.height(30.dp),
                        )
                        categories.forEach { choice ->
                            FilterChip(
                                selected = category == choice,
                                onClick = { category = choice },
                                label = { Text(choice, fontSize = fs.detail) },
                                modifier = Modifier.height(30.dp),
                            )
                        }
                    }
                    Text("${filtered.size} matches", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(filtered, key = { it.name }) { sprite ->
                            val active = sprite.name == selected.name
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { selectedName = sprite.name },
                                color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            sprite.displayName,
                                            fontSize = fs.body,
                                            fontWeight = if (sprite.name == activeCatalogName) FontWeight.Bold else FontWeight.Normal,
                                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            sprite.authors.joinToString(),
                                            fontSize = fs.detail,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (sprite.name == activeCatalogName) CatalogBadge("PROJECT", MaterialTheme.colorScheme.tertiary)
                                    else if (isDownloaded(sprite)) CatalogBadge("SAVED", MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }

                Box(Modifier.width(COMMUNITY_PANE_DIVIDER_WIDTH.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Column(
                    Modifier.width(detailsWidth).fillMaxHeight()
                        .testTag("community-samus-catalog-details")
                        .verticalScroll(rememberScrollState()),
                ) {
                    // ── Info header (always visible at top) ──
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(selected.displayName, fontSize = fs.heading, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("by ${selected.authors.joinToString()}", fontSize = fs.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${selected.category} · v${selected.version}",
                                    fontSize = fs.detail,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                CatalogBadge(
                                    if (romReadyCatalog) "ROM-READY" else "PREVIEW",
                                    if (romReadyCatalog) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            if (!selectedDownloaded) {
                                CatalogAction(
                                    if (downloadingName == selected.name) "Downloading & validating…" else "Download Preview",
                                    onClick = { onDownloadPreview(selected) },
                                    enabled = downloadingName == null,
                                    emphasized = true,
                                )
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CatalogAction(
                                        when {
                                            downloadingName == selected.name -> "Preparing Project…"
                                            selected.name == activeCatalogName -> "Using in Project"
                                            showcaseLoading -> "Building Preview…"
                                            showcase == null -> "Preview unavailable"
                                            else -> "Use in Project"
                                        },
                                        onClick = { onUseInProject(selected) },
                                        enabled = downloadingName == null &&
                                            selected.name != activeCatalogName &&
                                            !showcaseLoading &&
                                            showcase != null,
                                        emphasized = true,
                                    )
                                    CatalogAction(
                                        "Open Detailed View",
                                        onClick = { onOpenDetails(selected) },
                                        enabled = downloadingName == null,
                                    )
                                }
                            }
                            if (selected.name == activeCatalogName) {
                                Text("This is the project’s selected Samus source.", fontSize = fs.detail, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }

                    // ── All sprite poses (flowing grid, full width) ──
                    when {
                        showcase != null -> CommunitySamusShowcase(showcase!!, selected.displayName)
                        showcaseLoading -> ShowcaseLoading()
                        else -> {
                            Box(
                                Modifier.fillMaxWidth().height(120.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (selectedDownloaded) "Saved preview unavailable" else "Download to see all in-game poses",
                                    fontSize = fs.detail,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun CommunitySamusShowcase(
    showcase: CommunitySamusCatalogShowcase,
    displayName: String,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val bitmaps = remember(showcase) { showcase.images.map { it to it.toCatalogImageBitmap() } }
    Box(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        if (!showcase.hasVisiblePixels) {
            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f), shape = RoundedCornerShape(5.dp)) {
                    Text(
                        "All poses are transparent for this sprite",
                        fontSize = fs.body,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SHOWCASE_GAP.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(SHOWCASE_GAP.dp),
            ) {
                bitmaps.forEach { (image, bitmap) ->
                    Surface(
                        modifier = Modifier.requiredSize(SHOWCASE_CELL_SIZE.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "$displayName ${image.name}",
                                modifier = Modifier.fillMaxSize().padding(7.dp),
                                contentScale = ContentScale.Fit,
                                filterQuality = FilterQuality.None,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowcaseLoading() {
    val fs = LocalEditorTheme.current.fontSize.value
    Box(
        Modifier.fillMaxWidth().height(200.dp)
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Text("Building all pose previews…", fontSize = fs.body, color = MaterialTheme.colorScheme.primary)
    }
}

private const val SHOWCASE_CELL_SIZE = 90
private const val SHOWCASE_GAP = 5
internal const val COMMUNITY_CATALOG_HEADER_WIDTH = 520
internal const val COMMUNITY_CATALOG_DETAILS_WIDTH = 650

@Composable
internal fun CommunitySamusCatalogStatus(
    message: String,
    isError: Boolean,
    onRetry: () -> Unit,
    onImportFile: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(9.dp), modifier = Modifier.width(500.dp)) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Text(
                    if (isError) "Community catalog unavailable" else "Loading community catalog…",
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = fs.heading,
                )
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = fs.body)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (isError) CatalogAction("Try Again", onRetry, emphasized = true)
                    CatalogAction("Import Local PNG…", onImportFile)
                    CatalogAction("ROM Animations", onBack)
                }
            }
        }
    }
}

private fun SamusCommunitySheetDecoder.DecodedImage.toCatalogImageBitmap(): ImageBitmap {
    val buffered = BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
    if (width > 0 && height > 0) buffered.setRGB(0, 0, width, height, pixels, 0, width)
    return buffered.toComposeImageBitmap()
}

@Composable
private fun CatalogBadge(text: String, foreground: Color) {
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
        Text(text, fontSize = fs.statusBar, color = foreground, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
    }
}

@Composable
private fun CatalogAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant
            emphasized -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(
            text,
            fontSize = fs.body,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                emphasized -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = if (emphasized) 13.dp else 9.dp, vertical = if (emphasized) 8.dp else 6.dp),
        )
    }
}

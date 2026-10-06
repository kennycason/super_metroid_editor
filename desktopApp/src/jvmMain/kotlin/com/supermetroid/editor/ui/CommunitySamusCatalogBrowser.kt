package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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

    Column(modifier.fillMaxSize().background(Color(0xFF171A2A))) {
        Surface(color = Color(0xFF20243A), modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Community Samus Catalog", color = Color(0xFFF1F3FF), fontWeight = FontWeight.Bold, fontSize = fs.heading)
                    Text(
                        "${snapshot.sprites.size} sprites · upstream ${snapshot.revision.take(8)}" +
                            (if (snapshot.usedCachedCatalog) " · saved catalog" else "") +
                            (if (romReadyCatalog) " · ROM-ready" else " · preview-only revision"),
                        color = Color(0xFF8D97B3),
                        fontSize = fs.detail,
                    )
                }
                CatalogAction("Refresh", onRefresh)
                CatalogAction("Import Local PNG…", onImportFile)
                CatalogAction("ROM Animations", onBack)
            }
        }

        snapshot.notice?.let { notice ->
            Surface(color = Color(0xFF3A321C), modifier = Modifier.fillMaxWidth()) {
                Text(notice, color = Color(0xFFFDE68A), fontSize = fs.detail, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }

        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(sidebarWidth).fillMaxHeight().background(Color(0xFF1C2033)).padding(10.dp),
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
                Text("${filtered.size} matches", fontSize = fs.detail, color = Color(0xFF8D97B3))
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
                                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFFC6CCE0),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        sprite.authors.joinToString(),
                                        fontSize = fs.detail,
                                        color = Color(0xFF8792AF),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (sprite.name == activeCatalogName) CatalogBadge("PROJECT", Color(0xFF86EFAC))
                                else if (isDownloaded(sprite)) CatalogBadge("SAVED", Color(0xFF93C5FD))
                            }
                        }
                    }
                }
            }

            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF30354E)))
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = Color(0xFF22263B),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(0.9f).widthIn(max = 780.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when {
                            showcase != null -> CommunitySamusShowcase(showcase!!, selected.displayName)
                            showcaseLoading -> ShowcaseLoading()
                            else -> {
                                Box(
                                    Modifier.size(110.dp).background(Color(0xFF303650), RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        selected.displayName.take(1).uppercase(),
                                        fontSize = fs.display,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF9CC7FF),
                                    )
                                }
                                Text(
                                    if (selectedDownloaded) "Saved preview unavailable" else "Download to see representative in-game poses",
                                    fontSize = fs.detail,
                                    color = Color(0xFF8D97B3),
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(selected.displayName, fontSize = fs.display, fontWeight = FontWeight.Bold, color = Color(0xFFF1F3FF))
                        Text("by ${selected.authors.joinToString()}", fontSize = fs.body, color = Color(0xFFB7C0D9))
                        Text(
                            "${selected.category} · catalog v${selected.version}",
                            fontSize = fs.detail,
                            color = Color(0xFF7F8AA7),
                        )
                        Text(selected.name, fontSize = fs.detail, fontFamily = FontFamily.Monospace, color = Color(0xFF7E89A6))
                        CatalogBadge(
                            if (romReadyCatalog) "VERIFIED ROM PATCH AVAILABLE" else "SOURCE PREVIEW ONLY",
                            if (romReadyCatalog) Color(0xFF86EFAC) else Color(0xFFFDE68A),
                        )
                        Spacer(Modifier.height(5.dp))
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
                            Text("This is the project’s selected Samus source.", fontSize = fs.detail, color = Color(0xFF86EFAC))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Artwork downloads directly from MapRandoSprites only when requested and stays in SMEDIT’s local cache.",
                    fontSize = fs.detail,
                    color = Color(0xFF747F9B),
                )
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun CommunitySamusShowcase(
    showcase: CommunitySamusCatalogShowcase,
    displayName: String,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val bitmaps = remember(showcase) { showcase.images.map { it.toCatalogImageBitmap() } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = showcaseColumnCount(maxWidth.value)
        val rows = (bitmaps.size + columns - 1) / columns
        val showcaseHeight = (rows * SHOWCASE_CELL_SIZE + (rows - 1).coerceAtLeast(0) * SHOWCASE_GAP + 24).dp
        Box(
            Modifier.fillMaxWidth().height(showcaseHeight)
                .background(Color(0xFF171A2A), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(SHOWCASE_GAP.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                bitmaps.indices.chunked(columns).forEach { indices ->
                    Row(horizontalArrangement = Arrangement.spacedBy(SHOWCASE_GAP.dp)) {
                        indices.forEach { index ->
                            Surface(
                                modifier = Modifier.requiredSize(SHOWCASE_CELL_SIZE.dp),
                                color = Color(0xFF1D2235),
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                                    Image(
                                        bitmap = bitmaps[index],
                                        contentDescription = "$displayName ${showcase.images[index].name}",
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
            if (!showcase.hasVisiblePixels) {
                Surface(color = Color(0xE6292E43), shape = RoundedCornerShape(5.dp)) {
                    Text(
                        "Representative gameplay poses are transparent",
                        fontSize = fs.body,
                        color = Color(0xFFFDE68A),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowcaseLoading() {
    val fs = LocalEditorTheme.current.fontSize.value
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = showcaseColumnCount(maxWidth.value)
        val rows = (SHOWCASE_ITEM_COUNT + columns - 1) / columns
        val showcaseHeight = (rows * SHOWCASE_CELL_SIZE + (rows - 1) * SHOWCASE_GAP + 24).dp
        Box(
            Modifier.fillMaxWidth().height(showcaseHeight)
                .background(Color(0xFF171A2A), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(SHOWCASE_GAP.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                repeat(rows) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(SHOWCASE_GAP.dp)) {
                        repeat(minOf(columns, SHOWCASE_ITEM_COUNT - row * columns)) {
                            Box(
                                Modifier.requiredSize(SHOWCASE_CELL_SIZE.dp)
                                    .background(Color(0xFF1D2235), RoundedCornerShape(6.dp)),
                            )
                        }
                    }
                }
            }
            Text("Building downloaded pose preview…", fontSize = fs.body, color = Color(0xFF9CC7FF))
        }
    }
}

private fun showcaseColumnCount(availableWidthDp: Float): Int =
    (((availableWidthDp - 20f) + SHOWCASE_GAP) / (SHOWCASE_CELL_SIZE + SHOWCASE_GAP))
        .toInt()
        .coerceIn(1, SHOWCASE_MAX_COLUMNS)

private const val SHOWCASE_MAX_COLUMNS = 4
private const val SHOWCASE_ITEM_COUNT = 16
private const val SHOWCASE_CELL_SIZE = 104
private const val SHOWCASE_GAP = 7

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
    Box(modifier.fillMaxSize().background(Color(0xFF171A2A)), contentAlignment = Alignment.Center) {
        Surface(color = Color(0xFF22263B), shape = RoundedCornerShape(9.dp), modifier = Modifier.width(500.dp)) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Text(
                    if (isError) "Community catalog unavailable" else "Loading community catalog…",
                    color = if (isError) Color(0xFFFCA5A5) else Color(0xFFF1F3FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = fs.heading,
                )
                Text(message, color = Color(0xFF9AA4C1), fontSize = fs.body)
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
    Surface(color = Color(0xFF263149), shape = RoundedCornerShape(4.dp)) {
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
            !enabled -> Color(0xFF2A2D3B)
            emphasized -> Color(0xFF244B68)
            else -> Color(0xFF303650)
        },
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(
            text,
            fontSize = fs.body,
            color = if (enabled) Color(0xFFD8DEF2) else Color(0xFF74798A),
            modifier = Modifier.padding(horizontal = if (emphasized) 13.dp else 9.dp, vertical = if (emphasized) 8.dp else 6.dp),
        )
    }
}

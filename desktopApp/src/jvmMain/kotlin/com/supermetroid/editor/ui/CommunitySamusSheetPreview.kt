package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.CommunitySamusSpriteSource
import com.supermetroid.editor.rom.CommunitySamusSourceCodec
import com.supermetroid.editor.rom.MapRandoSamusCatalog
import com.supermetroid.editor.rom.MapRandoSamusSprite
import com.supermetroid.editor.rom.SamusCommunitySheetDecoder
import java.awt.image.BufferedImage
import java.io.File
import java.text.NumberFormat

/** One non-mutating community-sheet inspection session. */
internal data class CommunitySamusPreviewSession(
    val sourceName: String,
    val sourceKey: String,
    val pngBytes: ByteArray,
    val result: SamusCommunitySheetDecoder.Result,
    val metadata: CommunitySamusPreviewMetadata?,
    val catalogRevision: String? = null,
    val sourceUrl: String? = null,
    val catalogMessage: String? = null,
)

internal data class CommunitySamusPreviewMetadata(
    val name: String,
    val version: Int?,
    val displayName: String,
    val authors: List<String>,
    val creditsName: String?,
    val category: String?,
) {
    companion object {
        fun from(sprite: MapRandoSamusSprite): CommunitySamusPreviewMetadata =
            CommunitySamusPreviewMetadata(
                name = sprite.name,
                version = sprite.version,
                displayName = sprite.displayName,
                authors = sprite.authors,
                creditsName = sprite.creditsName,
                category = sprite.category,
            )
    }
}

/** File/catalog boundary kept outside Compose so validation behavior is unit-testable. */
internal class CommunitySamusPreviewLoader(
    private val decoder: SamusCommunitySheetDecoder = SamusCommunitySheetDecoder(),
) {
    fun load(file: File): CommunitySamusPreviewSession {
        val bytes = runCatching(file::readBytes).getOrElse { ByteArray(0) }
        val result = decoder.decode(bytes, file.name)
        val manifest = File(file.parentFile, "manifest.json")
        if (!manifest.isFile) {
            return CommunitySamusPreviewSession(file.name, file.absolutePath, bytes, result, null)
        }

        val catalog = runCatching { MapRandoSamusCatalog.parse(manifest) }
        val metadata = catalog.getOrNull()
            ?.firstOrNull { it.name == file.nameWithoutExtension }
            ?.let(CommunitySamusPreviewMetadata::from)
        val catalogMessage = catalog.exceptionOrNull()?.let { problem ->
            "Catalog metadata could not be read: ${problem.message ?: problem::class.simpleName}"
        }
        return CommunitySamusPreviewSession(
            file.name,
            file.absolutePath,
            bytes,
            result,
            metadata,
            catalogMessage = catalogMessage,
        )
    }

    fun load(
        file: File,
        metadata: MapRandoSamusSprite,
        catalogRevision: String,
        sourceUrl: String,
    ): CommunitySamusPreviewSession {
        val bytes = file.readBytes()
        return CommunitySamusPreviewSession(
            sourceName = file.name,
            sourceKey = "$catalogRevision:${metadata.name}",
            pngBytes = bytes,
            result = decoder.decode(bytes, file.name),
            metadata = CommunitySamusPreviewMetadata.from(metadata),
            catalogRevision = catalogRevision,
            sourceUrl = sourceUrl,
        )
    }

    fun load(source: CommunitySamusSpriteSource): CommunitySamusPreviewSession {
        return try {
            val loaded = CommunitySamusSourceCodec.load(source, decoder)
            CommunitySamusPreviewSession(
                sourceName = source.sourceName,
                sourceKey = "project:${source.sha256}",
                pngBytes = loaded.pngBytes,
                result = loaded.result,
                metadata = CommunitySamusPreviewMetadata(
                    name = source.catalogName ?: source.sha256,
                    version = source.catalogVersion,
                    displayName = source.displayName,
                    authors = source.authors,
                    creditsName = null,
                    category = source.category,
                ),
                catalogRevision = source.catalogRevision,
                sourceUrl = source.sourceUrl,
            )
        } catch (problem: Exception) {
            CommunitySamusPreviewSession(
                sourceName = source.sourceName,
                sourceKey = "project:${source.sha256}",
                pngBytes = ByteArray(0),
                result = SamusCommunitySheetDecoder.Result(
                    sheet = null,
                    issues = listOf(
                        SamusCommunitySheetDecoder.Issue(
                            SamusCommunitySheetDecoder.Severity.ERROR,
                            "PROJECT_SOURCE_INVALID",
                            problem.message ?: "The stored project source is invalid",
                        ),
                    ),
                ),
                metadata = null,
            )
        }
    }
}

internal enum class CommunitySamusRegionCategory(val label: String) {
    ALL("All"),
    GAMEPLAY("Gameplay"),
    SPECIAL("Special"),
    DEATH("Death"),
    FILE_SELECT("File Select"),
    GUN_PORTS("Gun Ports"),
    PALETTES("Palettes"),
}

internal fun communitySamusRegionCategory(name: String): CommunitySamusRegionCategory = when {
    name == "palette_block" || "palette" in name -> CommunitySamusRegionCategory.PALETTES
    name.startsWith("file_select_") -> CommunitySamusRegionCategory.FILE_SELECT
    name.startsWith("gun_port_") -> CommunitySamusRegionCategory.GUN_PORTS
    "death" in name -> CommunitySamusRegionCategory.DEATH
    name.startsWith("crystal_") || name.startsWith("xray_") ||
        name.startsWith("shine_spark_") || name.startsWith("supplication_") ->
        CommunitySamusRegionCategory.SPECIAL
    else -> CommunitySamusRegionCategory.GAMEPLAY
}

internal fun communitySamusRegionMatches(
    image: SamusCommunitySheetDecoder.DecodedImage,
    query: String,
    category: CommunitySamusRegionCategory,
): Boolean {
    if (category != CommunitySamusRegionCategory.ALL && communitySamusRegionCategory(image.name) != category) {
        return false
    }
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (terms.isEmpty()) return true
    val searchable = "${image.name} ${humanizeCommunityName(image.name)}".lowercase()
    return terms.all(searchable::contains)
}

@Composable
internal fun CommunitySamusSheetPreview(
    session: CommunitySamusPreviewSession,
    onChooseAnother: () -> Unit,
    onUseInProject: (() -> Unit)? = null,
    onExportSource: (() -> Unit)? = null,
    onStatus: (String) -> Unit = {},
    isProjectSource: Boolean = false,
    projectSource: CommunitySamusSpriteSource? = null,
    onPreviewProjectSource: (() -> Unit)? = null,
    onRestoreBase: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sheet = session.result.sheet
    Column(modifier.fillMaxSize().background(Color(0xFF171A2A))) {
        CommunitySheetHeader(
            session = session,
            onUseInProject = onUseInProject,
            onExportSource = onExportSource,
            isProjectSource = isProjectSource,
            projectSource = projectSource,
            onPreviewProjectSource = onPreviewProjectSource,
            onRestoreBase = onRestoreBase,
        )
        CommunityIssueSummary(session)

        if (sheet == null) {
            InvalidCommunitySheet(session, onChooseAnother, Modifier.weight(1f))
        } else {
            CommunitySamusWorkspace(sheet, session.sourceKey, onStatus, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CommunitySheetHeader(
    session: CommunitySamusPreviewSession,
    onUseInProject: (() -> Unit)?,
    onExportSource: (() -> Unit)?,
    isProjectSource: Boolean,
    projectSource: CommunitySamusSpriteSource?,
    onPreviewProjectSource: (() -> Unit)?,
    onRestoreBase: (() -> Unit)?,
) {
    Surface(color = Color(0xFF20243A), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        session.metadata?.displayName ?: session.sourceName.substringBeforeLast('.'),
                        fontWeight = FontWeight.Bold,
                        fontSize = LocalEditorTheme.current.fontSize.value.heading,
                        color = Color(0xFFF1F3FF),
                    )
                    CompactBadge(
                        if (isProjectSource) "PROJECT SOURCE" else "PREVIEW",
                        if (isProjectSource) Color(0xFF86EFAC) else Color(0xFF93C5FD),
                        if (isProjectSource) Color(0xFF173322) else Color(0xFF172B45),
                    )
                    if (isProjectSource && projectSource != null) {
                        CompactBadge(
                            if (projectSource.injectionArtifact != null) "ROM READY" else "SOURCE ONLY",
                            if (projectSource.injectionArtifact != null) Color(0xFF86EFAC) else Color(0xFFFDE68A),
                            if (projectSource.injectionArtifact != null) Color(0xFF28503B) else Color(0xFF4A3E20),
                        )
                    }
                    if (session.result.isValid) {
                        CompactBadge("VALID", Color(0xFF86EFAC), Color(0xFF173322))
                    } else {
                        CompactBadge("NEEDS ATTENTION", Color(0xFFFCA5A5), Color(0xFF3B1E25))
                    }
                }
                val details = session.metadata?.let { metadata ->
                    buildString {
                        metadata.category?.let { append(it).append("  •  ") }
                        if (metadata.authors.isNotEmpty()) append("by ${metadata.authors.joinToString()}  •  ")
                        if (metadata.version != null) append("catalog v${metadata.version}") else append(session.sourceName)
                    }
                } ?: "Uncataloged PNG  •  ${session.sourceName}"
                Text(details, fontSize = LocalEditorTheme.current.fontSize.value.detail, color = Color(0xFF9AA4C1), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!isProjectSource && projectSource != null && onPreviewProjectSource != null) {
                Surface(color = Color(0xFF173322), shape = RoundedCornerShape(5.dp)) {
                    Row(
                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Column {
                            Text(
                                "Current project",
                                fontSize = LocalEditorTheme.current.fontSize.value.statusBar,
                                color = Color(0xFF78AA8A),
                            )
                            Text(
                                projectSource.displayName,
                                fontSize = LocalEditorTheme.current.fontSize.value.detail,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFB7F7CA),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        CompactBadge(
                            if (projectSource.injectionArtifact != null) "ROM READY" else "SOURCE ONLY",
                            if (projectSource.injectionArtifact != null) Color(0xFF86EFAC) else Color(0xFFFDE68A),
                            if (projectSource.injectionArtifact != null) Color(0xFF28503B) else Color(0xFF4A3E20),
                        )
                        CompactAction("View", onPreviewProjectSource)
                    }
                }
            }
            if (onExportSource != null && session.result.isValid) {
                CompactAction("Export Source PNG…", onExportSource)
            }
            if (onUseInProject != null && session.result.isValid && !isProjectSource) {
                CompactAction(if (projectSource == null) "Use in Project" else "Replace Project Samus", onUseInProject)
            }
            if (isProjectSource && onRestoreBase != null) {
                CompactAction("Restore Base ROM Samus", onRestoreBase)
            }
        }
    }
}

@Composable
private fun CommunityIssueSummary(session: CommunitySamusPreviewSession) {
    val visibleIssues = session.result.issues.filter { it.severity != SamusCommunitySheetDecoder.Severity.INFO }
    val messages = buildList {
        session.catalogMessage?.let(::add)
        addAll(visibleIssues.map { issue -> issue.message })
    }
    if (messages.isEmpty()) return

    val hasError = session.result.issues.any { it.severity == SamusCommunitySheetDecoder.Severity.ERROR }
    val background = if (hasError) Color(0xFF3A2028) else Color(0xFF3A321C)
    val foreground = if (hasError) Color(0xFFFCA5A5) else Color(0xFFFDE68A)
    Surface(color = background, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            messages.take(3).forEach { message ->
                Text("• $message", fontSize = LocalEditorTheme.current.fontSize.value.detail, color = foreground)
            }
            if (messages.size > 3) {
                Text("${messages.size - 3} more validation messages", fontSize = LocalEditorTheme.current.fontSize.value.detail, color = foreground.copy(alpha = 0.72f))
            }
        }
    }
}

@Composable
private fun InvalidCommunitySheet(
    session: CommunitySamusPreviewSession,
    onChooseAnother: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(color = Color(0xFF22263B), shape = RoundedCornerShape(8.dp), modifier = Modifier.width(460.dp)) {
            Column(
                Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "This PNG is not a supported community Samus sheet",
                    color = Color(0xFFF1F3FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = LocalEditorTheme.current.fontSize.value.heading,
                )
                Text(
                    "Expected ${SamusCommunitySheetDecoder.FORMAT_WIDTH} × ${SamusCommunitySheetDecoder.FORMAT_HEIGHT} using the pinned SpriteSomething layout.",
                    color = Color(0xFFAAB2CA),
                    fontSize = LocalEditorTheme.current.fontSize.value.body,
                )
                session.result.issues.filter { it.severity == SamusCommunitySheetDecoder.Severity.ERROR }
                    .take(4)
                    .forEach { issue -> Text(issue.message, color = Color(0xFFFCA5A5), fontSize = LocalEditorTheme.current.fontSize.value.detail) }
                CompactAction("Choose a different PNG", onChooseAnother)
            }
        }
    }
}

@Composable
internal fun CommunitySheetMetrics(sheet: SamusCommunitySheetDecoder.DecodedSheet) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        MetricCard("Format", "876 × 2543", Modifier.weight(1f))
        MetricCard("Named regions", "${sheet.images.size} / ${SamusCommunitySheetDecoder.IMAGE_COUNT}", Modifier.weight(1f))
        MetricCard("Palette colors", sheet.masterPaletteRgb.size.toString(), Modifier.weight(1f))
        MetricCard(
            "Opaque pixels",
            NumberFormat.getIntegerInstance().format(sheet.opaqueIndexedPixelCount),
            Modifier.weight(1f),
        )
        MetricCard(
            "Palette match",
            if (sheet.quantizedPixelCount == 0) "Exact" else "${sheet.quantizedPixelCount} adjusted",
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier) {
    Surface(modifier, color = Color(0xFF22263B), shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
            Text(label, fontSize = LocalEditorTheme.current.fontSize.value.detail, color = Color(0xFF79839F))
            Text(value, fontSize = LocalEditorTheme.current.fontSize.value.body, color = Color(0xFFDCE2F8), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal fun CommunityRegionBrowser(
    sheet: SamusCommunitySheetDecoder.DecodedSheet,
    sessionKey: String,
    modifier: Modifier,
) {
    var query by remember(sessionKey) { mutableStateOf("") }
    var category by remember(sessionKey) { mutableStateOf(CommunitySamusRegionCategory.ALL) }
    var selectedName by remember(sessionKey) {
        mutableStateOf(if ("stand_right" in sheet.images) "stand_right" else sheet.images.keys.first())
    }
    val filtered = remember(sheet, query, category) {
        sheet.images.values
            .filter { communitySamusRegionMatches(it, query, category) }
    }
    val selected = sheet.images[selectedName] ?: sheet.images.values.first()
    val listState = rememberLazyListState()
    LaunchedEffect(filtered, selectedName) {
        val selectedIndex = filtered.indexOfFirst { it.name == selectedName }
        if (selectedIndex >= 0) listState.scrollToItem(selectedIndex)
    }

    Row(modifier.fillMaxWidth()) {
        Column(
            Modifier.width(265.dp).fillMaxHeight().background(Color(0xFF1C2033)).padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Find named region", fontSize = LocalEditorTheme.current.fontSize.value.detail) },
                placeholder = { Text("run, morph, file select…", fontSize = LocalEditorTheme.current.fontSize.value.detail) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = LocalEditorTheme.current.fontSize.value.body),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CommunitySamusRegionCategory.entries.forEach { choice ->
                    FilterChip(
                        selected = category == choice,
                        onClick = {
                            category = choice
                            if (!communitySamusRegionMatches(selected, query, choice)) {
                                sheet.images.values.firstOrNull { image ->
                                    communitySamusRegionMatches(image, query, choice)
                                }?.let { image -> selectedName = image.name }
                            }
                        },
                        label = { Text(choice.label, fontSize = LocalEditorTheme.current.fontSize.value.detail) },
                        modifier = Modifier.height(27.dp),
                    )
                }
            }
            Text("${filtered.size} regions", fontSize = LocalEditorTheme.current.fontSize.value.detail, color = Color(0xFF79839F))
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filtered, key = { it.name }) { image ->
                    val active = image.name == selected.name
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { selectedName = image.name },
                        color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    humanizeCommunityName(image.name),
                                    fontSize = LocalEditorTheme.current.fontSize.value.detail,
                                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFFC6CCE0),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(image.name, fontSize = LocalEditorTheme.current.fontSize.value.statusBar, color = Color(0xFF69738E), fontFamily = FontFamily.Monospace)
                            }
                            Text("${image.width}×${image.height}", fontSize = LocalEditorTheme.current.fontSize.value.statusBar, color = Color(0xFF7E88A4))
                        }
                    }
                }
            }
        }

        Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF30354E)))
        CommunityRegionDetails(selected, sheet, Modifier.weight(1f))
    }
}

@Composable
internal fun CommunityRegionDetails(
    image: SamusCommunitySheetDecoder.DecodedImage,
    sheet: SamusCommunitySheetDecoder.DecodedSheet,
    modifier: Modifier,
) {
    val bitmap = remember(image) { image.toImageBitmap() }
    val opaquePixels = remember(image) { image.pixels.count { (it ushr 24) != 0 } }
    Column(
        modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(humanizeCommunityName(image.name), fontSize = LocalEditorTheme.current.fontSize.value.display, fontWeight = FontWeight.Bold, color = Color(0xFFF0F2FC))
        Text(image.name, fontSize = LocalEditorTheme.current.fontSize.value.detail, fontFamily = FontFamily.Monospace, color = Color(0xFF8993B0))
        Spacer(Modifier.height(10.dp))

        Box(
            Modifier.fillMaxWidth().height(380.dp)
                .border(1.dp, Color(0xFF3A405B), RoundedCornerShape(8.dp))
                .background(Color(0xFF20243A), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Checkerboard(Modifier.fillMaxSize().padding(1.dp))
            Image(
                bitmap = bitmap,
                contentDescription = image.name,
                modifier = Modifier.fillMaxSize().padding(18.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
            if (opaquePixels == 0) {
                Surface(color = Color(0xDD272B40), shape = RoundedCornerShape(5.dp)) {
                    Text("Transparent region", fontSize = LocalEditorTheme.current.fontSize.value.body, color = Color(0xFFFDE68A), modifier = Modifier.padding(8.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MetricCard("Dimensions", "${image.width} × ${image.height}", Modifier.weight(1f))
            MetricCard("Opaque pixels", NumberFormat.getIntegerInstance().format(opaquePixels), Modifier.weight(1f))
            MetricCard("Group", communitySamusRegionCategory(image.name).label, Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        if (image.name == "palette_block") {
            Text("Master palettes", fontSize = LocalEditorTheme.current.fontSize.value.body, color = Color(0xFFB8C0D8), fontWeight = FontWeight.SemiBold)
            Text("7 rows × 15 colors", fontSize = LocalEditorTheme.current.fontSize.value.detail, color = Color(0xFF76809D))
            Spacer(Modifier.height(5.dp))
            sheet.masterPaletteRgb.toList().chunked(15).forEach { row ->
                PaletteRow(row)
                Spacer(Modifier.height(2.dp))
            }
        } else {
            val interval = image.paletteInterval
            if (interval != null) {
                Text(
                    "Imported palette interval ${interval.first}–${interval.last}  •  decoded index 0 stays transparent",
                    fontSize = LocalEditorTheme.current.fontSize.value.detail,
                    color = Color(0xFF8D97B3),
                )
                Spacer(Modifier.height(5.dp))
                PaletteRow(interval.map { sheet.masterPaletteRgb[it] })
            }
        }
    }
}

@Composable
private fun PaletteRow(colors: List<Int>) {
    Row(Modifier.fillMaxWidth().height(20.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        colors.forEach { rgb ->
            val red = (rgb ushr 16) and 0xFF
            val green = (rgb ushr 8) and 0xFF
            val blue = rgb and 0xFF
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(Color(red / 255f, green / 255f, blue / 255f), RoundedCornerShape(2.dp))
                    .border(1.dp, Color(0x553A405B), RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun Checkerboard(modifier: Modifier) {
    Canvas(modifier) {
        val cell = 12.dp.toPx()
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var column = 0
            while (x < size.width) {
                drawRect(
                    color = if ((row + column) % 2 == 0) Color(0xFF272C42) else Color(0xFF20243A),
                    topLeft = Offset(x, y),
                    size = Size(cell.coerceAtMost(size.width - x), cell.coerceAtMost(size.height - y)),
                )
                x += cell
                column++
            }
            y += cell
            row++
        }
    }
}

@Composable
private fun CompactBadge(text: String, foreground: Color, background: Color) {
    Surface(color = background, shape = RoundedCornerShape(4.dp)) {
        Text(text, fontSize = LocalEditorTheme.current.fontSize.value.statusBar, fontWeight = FontWeight.Bold, color = foreground, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

@Composable
private fun CompactAction(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = Color(0xFF303650),
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(text, fontSize = LocalEditorTheme.current.fontSize.value.detail, color = Color(0xFFD8DEF2), modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
    }
}

internal fun SamusCommunitySheetDecoder.DecodedImage.toImageBitmap(): ImageBitmap {
    val buffered = BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
    if (width > 0 && height > 0) buffered.setRGB(0, 0, width, height, pixels, 0, width)
    return buffered.toComposeImageBitmap()
}

internal fun humanizeCommunityName(name: String): String =
    name.replace('_', ' ')
        .replace(Regex("(?<=\\D)(\\d+)$"), " $1")
        .split(' ')
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

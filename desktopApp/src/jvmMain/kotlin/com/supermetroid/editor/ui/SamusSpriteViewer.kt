package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.CommunitySamusSpriteSource
import com.supermetroid.editor.rom.CommunitySamusSourceCodec
import com.supermetroid.editor.rom.GifEncoder
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SamusCommunitySheetDecoder
import com.supermetroid.editor.rom.SamusSpriteDecoder
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.SpriteAnimationFrame
import com.supermetroid.editor.rom.renderSpriteSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SamusSpriteViewer(
    romParser: RomParser?,
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    val rp = romParser
    if (rp == null) {
        Text(
            "Load a ROM to view Samus sprites.",
            fontSize = LocalEditorTheme.current.fontSize.value.body,
            modifier = modifier.padding(16.dp),
        )
        return
    }

    val decoder = remember(rp) { SamusSpriteDecoder(rp) }
    var selectedSuit by remember { mutableStateOf(SamusSpriteDecoder.SuitType.POWER) }
    var selectedGroupIdx by remember { mutableStateOf(0) }
    var selectedAnimIdx by remember { mutableStateOf(0) }
    var exportStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val communityLoader = remember { CommunitySamusPreviewLoader(SamusCommunitySheetDecoder()) }
    val communityCatalog = remember { CommunitySamusCatalogRepository() }
    var communitySession by remember { mutableStateOf<CommunitySamusPreviewSession?>(null) }
    var communityLoading by remember { mutableStateOf(false) }
    var catalogVisible by remember { mutableStateOf(false) }
    var catalogSnapshot by remember { mutableStateOf<CommunitySamusCatalogSnapshot?>(null) }
    var catalogLoading by remember { mutableStateOf(false) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var downloadingCatalogName by remember { mutableStateOf<String?>(null) }
    var confirmRestoreBase by remember { mutableStateOf(false) }
    val samusSourceVersion = editorState.samusSourceVersion
    val activeCommunitySource = remember(samusSourceVersion, editorState.project) {
        editorState.project.customGfx.samusCommunitySource
    }

    // A project-owned community sheet is the active Samus source, so opening the
    // Samus workspace should show its mapped animations immediately. The user can
    // still switch to ROM Animations for a base-ROM comparison.
    LaunchedEffect(activeCommunitySource?.sha256) {
        val source = activeCommunitySource ?: return@LaunchedEffect
        if (communitySession != null) return@LaunchedEffect
        communityLoading = true
        val session = withContext(Dispatchers.IO) { communityLoader.load(source) }
        communitySession = session
        communityLoading = false
        exportStatus = if (session.result.isValid) {
            ""
        } else {
            "Project Samus source needs attention"
        }
    }

    fun openCommunitySheet() {
        val file = chooseCommunitySamusPng() ?: return
        catalogVisible = false
        scope.launch {
            communityLoading = true
            exportStatus = "Validating ${file.name}…"
            val loaded = withContext(Dispatchers.IO) { communityLoader.load(file) }
            communitySession = loaded
            communityLoading = false
            val errors = loaded.result.issues.count { it.severity == SamusCommunitySheetDecoder.Severity.ERROR }
            val warnings = loaded.result.issues.count { it.severity == SamusCommunitySheetDecoder.Severity.WARNING }
            exportStatus = if (loaded.result.isValid) {
                "Validated ${file.name}: ${loaded.result.sheet?.images?.size ?: 0} named regions" +
                    if (warnings > 0) " · $warnings warning${if (warnings == 1) "" else "s"}" else ""
            } else {
                "${file.name}: $errors validation error${if (errors == 1) "" else "s"}"
            }
        }
    }

    fun refreshCatalog() {
        if (catalogLoading) return
        scope.launch {
            catalogLoading = true
            catalogError = null
            val refreshed = runCatching {
                withContext(Dispatchers.IO) { communityCatalog.refresh() }
            }
            refreshed.onSuccess { snapshot ->
                catalogSnapshot = snapshot
                exportStatus = "Loaded ${snapshot.sprites.size} community sprites"
            }.onFailure { problem ->
                val message = problem.message ?: "Could not load the community catalog"
                catalogError = message
                catalogSnapshot = catalogSnapshot?.copy(
                    usedCachedCatalog = true,
                    notice = "Could not refresh the community catalog; showing the current saved list. $message",
                )
                exportStatus = "Catalog refresh failed: $message"
            }
            catalogLoading = false
        }
    }

    fun openCatalog() {
        catalogVisible = true
        communitySession = null
        if (catalogSnapshot == null && !catalogLoading) refreshCatalog()
    }

    fun downloadCatalogPreview(sprite: com.supermetroid.editor.rom.MapRandoSamusSprite) {
        val snapshot = catalogSnapshot ?: return
        if (downloadingCatalogName != null) return
        scope.launch {
            downloadingCatalogName = sprite.name
            catalogError = null
            exportStatus = "Downloading ${sprite.displayName}…"
            val loaded = runCatching {
                withContext(Dispatchers.IO) {
                    val download = communityCatalog.download(snapshot, sprite)
                    communityLoader.load(download.file, sprite, snapshot.revision, download.sourceUrl)
                }
            }
            loaded.onSuccess { session ->
                exportStatus = "Downloaded ${sprite.displayName}: ${session.result.sheet?.images?.size ?: 0} named regions"
            }.onFailure { problem ->
                val message = problem.message ?: "Could not download ${sprite.displayName}"
                catalogError = message
                catalogSnapshot = snapshot.copy(notice = "Could not download ${sprite.displayName}. $message")
                exportStatus = "Download failed: $message"
            }
            downloadingCatalogName = null
        }
    }

    fun openCatalogSpriteDetails(sprite: com.supermetroid.editor.rom.MapRandoSamusSprite) {
        val snapshot = catalogSnapshot ?: return
        if (downloadingCatalogName != null) return
        scope.launch {
            downloadingCatalogName = sprite.name
            catalogError = null
            val loaded = runCatching {
                withContext(Dispatchers.IO) {
                    val download = communityCatalog.download(snapshot, sprite)
                    communityLoader.load(download.file, sprite, snapshot.revision, download.sourceUrl)
                }
            }
            loaded.onSuccess { session ->
                communitySession = session
                catalogVisible = false
                exportStatus = "Opened ${sprite.displayName} in the detailed animation view"
            }.onFailure { problem ->
                val message = problem.message ?: "Could not open ${sprite.displayName}"
                catalogError = message
                catalogSnapshot = snapshot.copy(notice = "Could not open ${sprite.displayName}. $message")
                exportStatus = "Preview failed: $message"
            }
            downloadingCatalogName = null
        }
    }

    fun useCatalogSpriteInProject(sprite: com.supermetroid.editor.rom.MapRandoSamusSprite) {
        val snapshot = catalogSnapshot ?: return
        if (downloadingCatalogName != null) return
        scope.launch {
            downloadingCatalogName = sprite.name
            catalogError = null
            exportStatus = "Preparing ${sprite.displayName} for the project…"
            val created = runCatching {
                withContext(Dispatchers.IO) {
                    val download = communityCatalog.download(snapshot, sprite)
                    val session = communityLoader.load(download.file, sprite, snapshot.revision, download.sourceUrl)
                    createCommunitySamusProjectSource(session, snapshot, communityCatalog)
                }
            }
            val source = created.getOrNull()
            if (source != null) {
                editorState.setCommunitySamusSource(source)
                communitySession = withContext(Dispatchers.Default) { communityLoader.load(source) }
                exportStatus = "Project Samus: ${source.displayName} · ROM ready · browsing catalog"
            } else {
                val problem = created.exceptionOrNull()
                val message = problem?.message ?: problem?.let { it::class.simpleName } ?: "unknown error"
                catalogError = message
                catalogSnapshot = snapshot.copy(notice = "Could not use ${sprite.displayName}. $message")
                exportStatus = "Could not use ${sprite.displayName}: $message"
            }
            downloadingCatalogName = null
        }
    }

    fun previewProjectSource() {
        val source = activeCommunitySource ?: return
        scope.launch {
            communityLoading = true
            val session = withContext(Dispatchers.IO) { communityLoader.load(source) }
            communitySession = session
            catalogVisible = false
            communityLoading = false
            exportStatus = if (session.result.isValid) {
                ""
            } else {
                "Project Samus source needs attention"
            }
        }
    }

    fun useSessionInProject() {
        val session = communitySession ?: return
        val metadata = session.metadata
        scope.launch {
            communityLoading = true
            exportStatus = "Preparing ${metadata?.displayName ?: session.sourceName} as a project source…"
            val created = runCatching {
                withContext(Dispatchers.IO) {
                    createCommunitySamusProjectSource(session, catalogSnapshot, communityCatalog)
                }
            }
            val source = created.getOrNull()
            if (source != null) {
                editorState.setCommunitySamusSource(source)
                communitySession = withContext(Dispatchers.Default) { communityLoader.load(source) }
                exportStatus = if (source.injectionArtifact != null) {
                    "Project Samus: ${source.displayName} · ROM ready · save the project to keep it"
                } else {
                    "Project Samus source: ${source.displayName} · preview only until a compatible injector is available"
                }
            } else {
                val problem = created.exceptionOrNull()
                exportStatus = "Could not use this sheet: ${problem?.message ?: problem?.let { it::class.simpleName } ?: "unknown error"}"
            }
            communityLoading = false
        }
    }

    fun exportCommunitySource() {
        val session = communitySession ?: return
        val file = choosePngFile(
            dialogTitle = "Export SpriteSomething Community Sheet",
            defaultName = session.sourceName,
        ) ?: return
        scope.launch {
            val written = runCatching {
                withContext(Dispatchers.IO) { writeBytesAtomically(file, session.pngBytes) }
            }
            exportStatus = written.fold(
                onSuccess = { "Exported community source to ${file.absolutePath}" },
                onFailure = { problem -> "Export failed: ${problem.message ?: problem::class.simpleName}" },
            )
        }
    }

    if (confirmRestoreBase) {
        AlertDialog(
            onDismissRequest = { confirmRestoreBase = false },
            title = {
                Text(
                    "Restore base-ROM Samus?",
                    fontSize = LocalEditorTheme.current.fontSize.value.heading,
                )
            },
            text = {
                Text(
                    "This removes ${activeCommunitySource?.displayName ?: "the community sheet"} from the project. " +
                        "The original imported PNG is not changed, and the next export will use Samus from the base ROM.",
                    fontSize = LocalEditorTheme.current.fontSize.value.body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val removed = editorState.restoreBaseRomSamus()
                    confirmRestoreBase = false
                    if (removed) {
                        communitySession = null
                        exportStatus = "Restored base-ROM Samus · save the project to keep this change"
                    }
                }) { Text("Restore Base ROM", fontSize = LocalEditorTheme.current.fontSize.value.body) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestoreBase = false }) {
                    Text("Cancel", fontSize = LocalEditorTheme.current.fontSize.value.body)
                }
            },
        )
    }

    if (catalogVisible) {
        val snapshot = catalogSnapshot
        if (snapshot != null) {
            CommunitySamusCatalogBrowser(
                snapshot = snapshot,
                activeCatalogName = activeCommunitySource?.catalogName,
                downloadingName = downloadingCatalogName,
                isDownloaded = { sprite -> communityCatalog.isDownloaded(snapshot, sprite) },
                loadShowcase = { sprite -> communityCatalog.loadShowcase(snapshot, sprite) },
                onDownloadPreview = ::downloadCatalogPreview,
                onUseInProject = ::useCatalogSpriteInProject,
                onOpenDetails = ::openCatalogSpriteDetails,
                onRefresh = ::refreshCatalog,
                onImportFile = ::openCommunitySheet,
                onBack = { catalogVisible = false },
                modifier = modifier,
            )
        } else {
            CommunitySamusCatalogStatus(
                message = catalogError ?: "Fetching the latest names, artists, and versions from MapRandoSprites.",
                isError = catalogError != null && !catalogLoading,
                onRetry = ::refreshCatalog,
                onImportFile = ::openCommunitySheet,
                onBack = { catalogVisible = false },
                modifier = modifier,
            )
        }
        return
    }

    // Global playback state — persists across group/variant toggles
    var isPlaying by remember { mutableStateOf(false) }
    var animFrame by remember { mutableStateOf(0) }

    val groups = SamusSpriteDecoder.ANIMATION_GROUPS
    val currentGroup = groups.getOrNull(selectedGroupIdx) ?: groups.first()
    val currentAnimId = currentGroup.animationIds.getOrNull(selectedAnimIdx) ?: currentGroup.animationIds.first()

    // Build animation for the current selection
    val animation = remember(currentAnimId, selectedSuit) {
        // Reset frame to 0 when animation changes, but keep playing state
        animFrame = 0
        decoder.buildAnimation(currentAnimId, selectedSuit, renderSize = 96)
    }
    val displayedProjectSource = activeCommunitySource?.sha256?.let { hash ->
        communitySession?.sourceKey == "project:$hash"
    } == true

    Column(modifier = modifier.fillMaxSize().background(Color(0xFF1A1A2E))) {
        // ── Header ──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Samus Sprites", fontWeight = FontWeight.Bold, fontSize = LocalEditorTheme.current.fontSize.value.heading,
                    color = MaterialTheme.colorScheme.onSurface)

                Spacer(Modifier.width(16.dp))

                if (communitySession == null) {
                    // Suit selector
                    for (suit in SamusSpriteDecoder.SuitType.entries) {
                        FilterChip(
                            selected = selectedSuit == suit,
                            onClick = { selectedSuit = suit },
                            label = { Text(suit.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = LocalEditorTheme.current.fontSize.value.body) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                } else {
                    Surface(color = Color(0xFF1B3148), shape = RoundedCornerShape(4.dp)) {
                        Text(
                            if (displayedProjectSource) "Community Samus · Project Source" else "Community Samus · Preview",
                            fontSize = LocalEditorTheme.current.fontSize.value.detail,
                            color = Color(0xFF93C5FD),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                Surface(
                    modifier = Modifier.clickable(enabled = !communityLoading) { openCatalog() },
                    color = Color(0xFF244B68),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        "Community Catalog…",
                        fontSize = LocalEditorTheme.current.fontSize.value.detail,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = Color(0xFFB9DEFF),
                    )
                }

                Surface(
                    modifier = Modifier.clickable(enabled = !communityLoading) { openCommunitySheet() },
                    color = Color(0xFF263755),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        if (communityLoading) "Validating…" else "Import Local PNG…",
                        fontSize = LocalEditorTheme.current.fontSize.value.detail,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = Color(0xFF9CC7FF),
                    )
                }

                if (communitySession != null) {
                    Surface(
                        modifier = Modifier.clickable {
                            communitySession = null
                            exportStatus = ""
                        },
                        color = Color(0xFF2A2D45),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "ROM Animations",
                            fontSize = LocalEditorTheme.current.fontSize.value.detail,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = Color(0xFFCCD2E7),
                        )
                    }
                } else {
                    // Export All Samus Sprites button
                Surface(
                    modifier = Modifier.clickable {
                        val file = choosePngFile(
                            dialogTitle = "Save All Samus Sprites as Sprite Sheet",
                            defaultName = "samus_all_sprites_${selectedSuit.name.lowercase()}.png",
                        ) ?: return@clickable
                        scope.launch {
                            exportStatus = "Building all sprites..."
                            val result = runCatching {
                                withContext(Dispatchers.Default) {
                                    exportAllSamusSprites(decoder, selectedSuit, file)
                                }
                            }
                            exportStatus = result.fold(
                                onSuccess = { frameCount ->
                                    if (frameCount > 0) "Exported $frameCount frames to ${file.name}"
                                    else "No Samus sprites found to export"
                                },
                                onFailure = { err -> "Export failed: ${err.message ?: err::class.simpleName}" },
                            )
                        }
                    },
                    color = Color(0xFF2A3A2A),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("Export All Sprites", fontSize = LocalEditorTheme.current.fontSize.value.detail,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = Color(0xFF90D090))
                }
                }
            }
        }

        // Status bar
        if (exportStatus.isNotEmpty()) {
            Text(exportStatus, fontSize = LocalEditorTheme.current.fontSize.value.detail, color = Color(0xFFFFD54F),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        }

        if (activeCommunitySource != null && communitySession == null) {
            Surface(color = Color(0xFF173322), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                "Project Samus · ${activeCommunitySource.displayName}",
                                color = Color(0xFFB7F7CA),
                                fontSize = LocalEditorTheme.current.fontSize.value.body,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Surface(
                                color = if (activeCommunitySource.injectionArtifact != null) Color(0xFF28503B) else Color(0xFF4A3E20),
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                Text(
                                    if (activeCommunitySource.injectionArtifact != null) "ROM READY" else "SOURCE ONLY",
                                    color = if (activeCommunitySource.injectionArtifact != null) Color(0xFFB7F7CA) else Color(0xFFFDE68A),
                                    fontSize = LocalEditorTheme.current.fontSize.value.statusBar,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                        val attribution = if (activeCommunitySource.authors.isNotEmpty()) {
                            "by ${activeCommunitySource.authors.joinToString()} · ${activeCommunitySource.sha256.take(10)}"
                        } else {
                            "Local source · ${activeCommunitySource.sha256.take(10)}"
                        }
                        Text(attribution, color = Color(0xFF78AA8A), fontSize = LocalEditorTheme.current.fontSize.value.detail)
                    }
                    Surface(
                        modifier = Modifier.clickable(enabled = !communityLoading) { previewProjectSource() },
                        color = Color(0xFF28503B),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text("Preview Source", color = Color(0xFFD4F8DE), fontSize = LocalEditorTheme.current.fontSize.value.detail, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                    }
                    Surface(
                        modifier = Modifier.clickable { confirmRestoreBase = true },
                        color = Color(0xFF3D3940),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text("Restore Base ROM Samus", color = Color(0xFFE0D6DF), fontSize = LocalEditorTheme.current.fontSize.value.detail, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                    }
                }
            }
        }

        val activeCommunitySession = communitySession
        if (activeCommunitySession != null) {
            CommunitySamusSheetPreview(
                session = activeCommunitySession,
                onChooseAnother = ::openCommunitySheet,
                onUseInProject = if (activeCommunitySession.result.isValid && !displayedProjectSource) ::useSessionInProject else null,
                onExportSource = ::exportCommunitySource,
                onStatus = { exportStatus = it },
                isProjectSource = displayedProjectSource,
                projectSource = activeCommunitySource,
                onPreviewProjectSource = if (!displayedProjectSource && activeCommunitySource != null) ::previewProjectSource else null,
                onRestoreBase = if (displayedProjectSource) {
                    { confirmRestoreBase = true }
                } else {
                    null
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
        Row(modifier = Modifier.fillMaxSize()) {
            // ── Left: Animation group list ──
            Column(
                modifier = Modifier.width(180.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("Animations", fontSize = LocalEditorTheme.current.fontSize.value.body, color = Color(0xFFB0B8D1),
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))

                for ((gIdx, group) in groups.withIndex()) {
                    val frameCountPreview = decoder.getFrameCount(group.animationIds.first())
                    Surface(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                selectedGroupIdx = gIdx
                                selectedAnimIdx = 0
                            },
                        color = if (gIdx == selectedGroupIdx) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(group.name, fontSize = LocalEditorTheme.current.fontSize.value.body,
                                fontWeight = if (gIdx == selectedGroupIdx) FontWeight.Bold else FontWeight.Normal,
                                color = if (gIdx == selectedGroupIdx) MaterialTheme.colorScheme.onPrimaryContainer
                                else Color(0xFFB0B8D1))
                            Text("${group.animationIds.size} variants, $frameCountPreview frames — ${group.description}",
                                fontSize = LocalEditorTheme.current.fontSize.value.detail, color = Color(0xFF6A6F88))
                        }
                    }
                }
            }

            // ── Divider ──
            Box(Modifier.width(1.dp).fillMaxSize().background(Color(0xFF2A2D45)))

            // ── Right: Animation player + details ──
            Column(
                modifier = Modifier.weight(1f).fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Direction selector (if group has multiple anims)
                if (currentGroup.animationIds.size > 1) {
                    Text("Direction / Variant", fontSize = LocalEditorTheme.current.fontSize.value.body, color = Color(0xFFB0B8D1),
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for ((aIdx, _) in currentGroup.animationIds.withIndex()) {
                            Surface(
                                modifier = Modifier.clickable {
                                    selectedAnimIdx = aIdx
                                },
                                color = if (aIdx == selectedAnimIdx) MaterialTheme.colorScheme.primaryContainer
                                else Color(0xFF2A2D45),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("$aIdx", fontSize = LocalEditorTheme.current.fontSize.value.detail,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = if (aIdx == selectedAnimIdx) MaterialTheme.colorScheme.onPrimaryContainer
                                    else Color(0xFFB0B8D1),
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Animation name
                Text(
                    "Anim 0x${currentAnimId.toString(16).uppercase()} — ${currentGroup.name}",
                    fontSize = LocalEditorTheme.current.fontSize.value.body, color = Color(0xFFB0B8D1)
                )
                Spacer(Modifier.height(8.dp))

                // ── Animation Player (state hoisted for persistence across toggles) ──
                AnimationPlayer(
                    animation = animation,
                    previewSize = 288,
                    playing = isPlaying,
                    onPlayingChanged = { isPlaying = it },
                    currentFrame = animFrame,
                    onFrameChanged = { animFrame = it },
                    onExportPng = { frame, idx ->
                        val file = choosePngFile(
                            dialogTitle = "Save Frame as PNG",
                            defaultName = "samus_${exportSafeName(currentGroup.name)}_${currentAnimId}_frame$idx.png",
                        )
                        if (file != null) {
                            scope.launch {
                                exportStatus = "Exporting ${file.name}..."
                                val result = runCatching {
                                    withContext(Dispatchers.IO) { exportFramePng(frame, file) }
                                }
                                exportStatus = result.fold(
                                    onSuccess = { "Exported ${file.name}" },
                                    onFailure = { err -> "Export failed: ${err.message ?: err::class.simpleName}" },
                                )
                            }
                        }
                    },
                    onExportGif = { anim ->
                        val file = chooseGifFile(
                            dialogTitle = "Save Animation as GIF",
                            defaultName = "samus_${exportSafeName(currentGroup.name)}_${currentAnimId}.gif",
                        )
                        if (file != null) {
                            scope.launch {
                                exportStatus = "Exporting ${file.name}..."
                                val result = runCatching {
                                    withContext(Dispatchers.Default) { exportAnimationGif(anim, file) }
                                }
                                exportStatus = result.fold(
                                    onSuccess = { "Exported ${file.name}" },
                                    onFailure = { err -> "Export failed: ${err.message ?: err::class.simpleName}" },
                                )
                            }
                        }
                    },
                    onExportSheet = { anim ->
                        val file = choosePngFile(
                            dialogTitle = "Save Sprite Sheet as PNG",
                            defaultName = "samus_${exportSafeName(currentGroup.name)}_${currentAnimId}_sheet.png",
                        )
                        if (file != null) {
                            scope.launch {
                                exportStatus = "Exporting ${file.name}..."
                                val result = runCatching {
                                    withContext(Dispatchers.IO) { exportAnimationSheet(anim, file) }
                                }
                                exportStatus = result.fold(
                                    onSuccess = { "Exported ${file.name}" },
                                    onFailure = { err -> "Export failed: ${err.message ?: err::class.simpleName}" },
                                )
                            }
                        }
                    }
                )

                // Palette display
                Spacer(Modifier.height(16.dp))
                Text("Palette (${selectedSuit.name})", fontSize = LocalEditorTheme.current.fontSize.value.body, color = Color(0xFFB0B8D1),
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))

                val palette = remember(selectedSuit) { decoder.readPalette(selectedSuit) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for (i in 0 until 16) {
                        val argb = palette[i]
                        val r = (argb shr 16) and 0xFF
                        val g = (argb shr 8) and 0xFF
                        val b = argb and 0xFF
                        Box(
                            modifier = Modifier
                                .weight(1f).height(20.dp)
                                .background(
                                    if (i == 0) Color(0xFF2A2A3A) else Color(r / 255f, g / 255f, b / 255f),
                                    RoundedCornerShape(2.dp)
                                )
                                .border(1.dp, Color(0xFF3A3F5C), RoundedCornerShape(2.dp))
                        ) {
                            if (i == 0) {
                                Text("T", fontSize = LocalEditorTheme.current.fontSize.value.statusBar, color = Color(0xFF4A4C5E),
                                    modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

// ─── Export helpers ──────────────────────────────────────────────────

private fun createCommunitySamusProjectSource(
    session: CommunitySamusPreviewSession,
    catalogSnapshot: CommunitySamusCatalogSnapshot?,
    communityCatalog: CommunitySamusCatalogRepository,
): CommunitySamusSpriteSource {
    require(session.result.isValid) { "${session.sourceName} is not a valid community Samus sheet" }
    val metadata = session.metadata
    val matchingSnapshot = catalogSnapshot?.takeIf {
        it.revision.equals(session.catalogRevision, ignoreCase = true)
    }
    val matchingSprite = matchingSnapshot?.sprites?.firstOrNull { it.name == metadata?.name }
    val injectionArtifact = if (
        matchingSnapshot != null &&
        matchingSprite != null &&
        communityCatalog.hasVerifiedInjector(matchingSnapshot)
    ) {
        communityCatalog.downloadInjectionArtifact(
            snapshot = matchingSnapshot,
            sprite = matchingSprite,
            sourceSheetSha256 = CommunitySamusSourceCodec.sha256(session.pngBytes),
        ).artifact
    } else {
        null
    }
    return CommunitySamusSourceCodec.create(
        pngBytes = session.pngBytes,
        sourceName = session.sourceName,
        displayName = metadata?.displayName ?: session.sourceName.substringBeforeLast('.'),
        authors = metadata?.authors.orEmpty(),
        category = metadata?.category,
        catalogName = metadata?.name,
        catalogVersion = metadata?.version,
        catalogRevision = session.catalogRevision,
        sourceUrl = session.sourceUrl,
        injectionArtifact = injectionArtifact,
    )
}

internal fun exportSafeName(value: String): String =
    value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "sprite" }

internal fun exportFramePng(frame: SpriteAnimationFrame, file: File) {
    val bi = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
    bi.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
    ImageIO.write(bi, "png", file)
}

internal fun exportAnimationGif(animation: SpriteAnimation, file: File) {
    val gifBytes = GifEncoder.encode(animation.frames)
    file.writeBytes(gifBytes)
}

internal fun exportAnimationSheet(animation: SpriteAnimation, file: File) {
    val (pixels, w, h) = renderSpriteSheet(animation.frames, columns = 8)
    if (w > 0 && h > 0) {
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        bi.setRGB(0, 0, w, h, pixels, 0, w)
        ImageIO.write(bi, "png", file)
    }
}

internal fun choosePngFile(dialogTitle: String, defaultName: String): File? {
    val chooser = JFileChooser().apply {
        this.dialogTitle = dialogTitle
        selectedFile = File(defaultName)
        fileFilter = FileNameExtensionFilter("PNG Images", "png")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile.let {
        if (!it.name.endsWith(".png", ignoreCase = true)) File(it.parentFile, "${it.name}.png") else it
    }
}

private fun chooseCommunitySamusPng(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Open SpriteSomething / MapRando Samus Sheet"
        fileFilter = FileNameExtensionFilter("Community Samus PNG (876 × 2543)", "png")
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

internal fun chooseGifFile(dialogTitle: String, defaultName: String): File? {
    val chooser = JFileChooser().apply {
        this.dialogTitle = dialogTitle
        selectedFile = File(defaultName)
        fileFilter = FileNameExtensionFilter("GIF Images", "gif")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile.let {
        if (!it.name.endsWith(".gif", ignoreCase = true)) File(it.parentFile, "${it.name}.gif") else it
    }
}

private fun exportAllSamusSprites(
    decoder: SamusSpriteDecoder,
    suit: SamusSpriteDecoder.SuitType,
    file: File,
): Int {
    val allFrames = decoder.buildAllAnimations(suit, renderSize = 64)
        .flatMap { animation ->
            animation.frames.mapIndexed { index, frame ->
                frame.copy(label = "${animation.name} #${index + 1}")
            }
        }
    if (allFrames.isEmpty()) return 0

    val (pixels, w, h) = renderSpriteSheet(allFrames, columns = 16)
    if (w > 0 && h > 0) {
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        bi.setRGB(0, 0, w, h, pixels, 0, w)
        ImageIO.write(bi, "png", file)
    }
    return allFrames.size
}

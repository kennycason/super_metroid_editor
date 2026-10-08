package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.AsmAsset
import com.supermetroid.editor.asm.AsmBrowserMode
import com.supermetroid.editor.asm.AsmInstructionReference
import com.supermetroid.editor.asm.AsmReferenceContract
import com.supermetroid.editor.asm.AsmReferenceIndex
import com.supermetroid.editor.asm.AsmSourceFile
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.rom.RomParser
import kotlinx.coroutines.launch

private data class AsmSearchResult(
    val title: String,
    val detail: String,
    val fileId: String,
    val lineIndex: Int,
)

@Composable
internal fun AsmWorkspaceSidebar(
    state: AsmWorkspaceState,
    romParser: RomParser?,
    romName: String?,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val scope = rememberCoroutineScope()
    val workspace = state.workspace

    fun download() {
        val parser = romParser ?: return
        scope.launch { state.download(parser.copyRomData(), romName ?: "loaded ROM") }
    }

    fun syncAssets() {
        val parser = romParser ?: return
        scope.launch { state.refreshAssets(parser.copyRomData(), romName ?: "loaded ROM") }
    }

    Column(modifier = modifier.padding(8.dp)) {
        Text("ASM Reference", fontSize = fs.heading, fontWeight = FontWeight.Bold)
        Text(
            "Read-only, source-backed Super Metroid knowledge workspace",
            fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        AsmReferenceStatus(state)
        if (state.busy) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            state.progress?.let {
                Text(it, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.error?.let { error ->
            Spacer(Modifier.height(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
                    Text(error, fontSize = fs.detail, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                    IconButton(onClick = state::dismissError, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (workspace == null) {
            Button(
                onClick = ::download,
                enabled = romParser != null && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Download ASM Reference", fontSize = fs.body)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "SMEDIT downloads the pinned source and derives its .bin assets from the ROM already open above. The ROM itself is never copied into the reference.",
                fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = ::syncAssets,
                    enabled = romParser != null && !state.busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sync assets", fontSize = fs.detail)
                }
                OutlinedButton(
                    onClick = ::download,
                    enabled = romParser != null && !state.busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Redownload", fontSize = fs.detail)
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth()) {
                AsmModeButton("Source", state.browserMode == AsmBrowserMode.SOURCE) {
                    state.showSourceBrowser()
                }
                AsmModeButton("Assets", state.browserMode == AsmBrowserMode.ASSETS) {
                    state.showAssetBrowser()
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(fontSize = fs.body),
                label = { Text(if (state.browserMode == AsmBrowserMode.SOURCE) "Find bank, section, or label" else "Find extracted asset", fontSize = fs.detail) },
            )
            Spacer(Modifier.height(6.dp))

            when (state.browserMode) {
                AsmBrowserMode.SOURCE -> AsmSourceTree(state, workspace.index, Modifier.weight(1f).fillMaxWidth())
                AsmBrowserMode.ASSETS -> AsmAssetTree(state, workspace.index.assets, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AsmReferenceStatus(state: AsmWorkspaceState) {
    val fs = LocalEditorTheme.current.fontSize.value
    val workspace = state.workspace
    val background = when {
        workspace == null -> MaterialTheme.colorScheme.surfaceVariant
        state.assetsMatchCurrentRom -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    }
    Surface(color = background, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text(
                when {
                    workspace == null -> "Reference not downloaded"
                    state.assetsMatchCurrentRom -> "Source + assets ready"
                    else -> "Source ready · assets belong to another ROM"
                },
                fontSize = fs.body,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "InsaneFirebat/sm_disassembly · ${AsmReferenceContract.COMMIT.take(12)}" +
                    (workspace?.let { " · ${it.metadata.assetCount} assets" } ?: ""),
                fontSize = fs.detail,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            workspace?.metadata?.romName?.let {
                Text("Assets: $it", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RowScope.AsmModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier.weight(1f).clickable(onClick = onClick),
    ) {
        Text(
            label,
            fontSize = fs.body,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun AsmSourceTree(state: AsmWorkspaceState, index: AsmReferenceIndex, modifier: Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    val query = state.query.trim()
    val results = remember(index, query) {
        if (query.isEmpty()) emptyList() else sourceSearch(index, query)
    }
    LazyColumn(modifier) {
        if (query.isNotEmpty()) {
            items(results, key = { "${it.fileId}:${it.lineIndex}:${it.title}" }) { result ->
                Column(
                    Modifier.fillMaxWidth().clickable { state.openSource(result.fileId, result.lineIndex) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(result.title, fontSize = fs.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(result.detail, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (results.isEmpty()) item { Text("No source matches", fontSize = fs.detail, modifier = Modifier.padding(8.dp)) }
        } else {
            val banks = index.files.filter(AsmSourceFile::isBank)
            val references = index.files.filterNot(AsmSourceFile::isBank)
            item { TreeHeading("Banks") }
            banks.forEach { source ->
                item(key = source.id) { SourceFileRow(state, source) }
                if (state.selectedFileId == source.id && state.selectedAssetPath == null) {
                    source.sections.forEach { section ->
                        item(key = "${source.id}:${section.lineIndex}") {
                            Row(
                                Modifier.fillMaxWidth().clickable { state.openSource(source.id, section.lineIndex) }
                                    .padding(start = 22.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                section.address?.let {
                                    Text(it, fontSize = fs.detail, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.secondary)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(section.title, fontSize = fs.detail, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            item { TreeHeading("Build & symbols") }
            references.forEach { source -> item(key = source.id) { SourceFileRow(state, source) } }
        }
    }
}

@Composable
private fun SourceFileRow(state: AsmWorkspaceState, source: AsmSourceFile) {
    val fs = LocalEditorTheme.current.fontSize.value
    val selected = state.selectedFileId == source.id && state.selectedAssetPath == null
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
            .clickable { state.openSource(source.id) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(source.displayName, fontSize = fs.body, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        if (source.description.isNotBlank()) {
            Text(source.description, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TreeHeading(label: String) {
    val fs = LocalEditorTheme.current.fontSize.value
    Text(
        label.uppercase(),
        fontSize = fs.detail,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
    )
}

@Composable
private fun AsmAssetTree(state: AsmWorkspaceState, assets: List<AsmAsset>, modifier: Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    val query = state.query.trim()
    val categories = remember(assets) { assets.groupBy(AsmAsset::category).toSortedMap() }
    val visible = remember(assets, query, state.selectedAssetCategory) {
        when {
            query.isNotEmpty() -> assets.filter { it.range.path.contains(query, ignoreCase = true) }.take(500)
            state.selectedAssetCategory != null -> categories[state.selectedAssetCategory].orEmpty()
            else -> emptyList()
        }
    }
    LazyColumn(modifier) {
        if (query.isEmpty() && state.selectedAssetCategory == null) {
            categories.forEach { (category, categoryAssets) ->
                item(key = category) {
                    Row(
                        Modifier.fillMaxWidth().clickable { state.selectedAssetCategory = category }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    ) {
                        Text(category, fontSize = fs.body, modifier = Modifier.weight(1f))
                        Text(categoryAssets.size.toString(), fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            if (query.isEmpty()) {
                item {
                    TextButton(onClick = { state.selectedAssetCategory = null }) {
                        Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("All asset groups", fontSize = fs.detail)
                    }
                }
            }
            items(visible, key = { it.range.path }) { asset ->
                val selected = state.selectedAssetPath == asset.range.path
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
                        .clickable { state.openAsset(asset.range.path) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(asset.range.path, fontSize = fs.detail, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "PC \$${asset.range.pcOffset.toString(16).uppercase().padStart(6, '0')} · ${asset.range.length} bytes",
                        fontSize = fs.statusBar,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (visible.isEmpty()) item { Text("No asset matches", fontSize = fs.detail, modifier = Modifier.padding(8.dp)) }
        }
    }
}

@Composable
internal fun AsmWorkspaceCanvas(
    state: AsmWorkspaceState,
    modifier: Modifier = Modifier,
) {
    val workspace = state.workspace
    if (workspace == null) {
        AsmWelcomeCanvas(state, modifier)
        return
    }
    val asset = workspace.index.asset(state.selectedAssetPath)
    if (asset != null) {
        AsmAssetCanvas(state, asset, workspace.metadata.romName, modifier)
    } else {
        val source = workspace.index.file(state.selectedFileId)
        if (source == null) AsmWelcomeCanvas(state, modifier)
        else AsmSourceCanvas(state, workspace.index, source, modifier)
    }
}

@Composable
private fun AsmWelcomeCanvas(state: AsmWorkspaceState, modifier: Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(0.68f),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Super Metroid ASM", fontSize = fs.display, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.busy) state.progress ?: "Preparing the reference…"
                    else "Choose Download ASM Reference on the left. SMEDIT will open the pinned annotated source as banks and functional sections, then derive every required binary asset from your already-loaded ROM.",
                    fontSize = fs.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AsmSourceCanvas(
    state: AsmWorkspaceState,
    index: AsmReferenceIndex,
    source: AsmSourceFile,
    modifier: Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    LaunchedEffect(state.navigationSerial, source.id) {
        if (source.lines.isNotEmpty()) listState.scrollToItem(state.selectedLineIndex.coerceIn(source.lines.indices))
    }
    Column(modifier.fillMaxSize()) {
        AsmNavigationHeader(state, source.displayName, source.description, "${source.lines.size} lines · read-only")
        AsmInstructionReference.find(state.selectedInstruction)?.let { info ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(info.mnemonic, fontSize = fs.body, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(info.name, fontSize = fs.body, fontWeight = FontWeight.SemiBold)
                        Text("${info.summary}  Flags: ${info.flags}", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(state::closeInstruction, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Default.Close, "Close instruction reference", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().background(codeBackground())) {
            items(source.lines.size, key = { it }) { lineIndex ->
                val selected = lineIndex == state.selectedLineIndex
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.11f) else Color.Transparent)
                        .padding(vertical = 1.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        (lineIndex + 1).toString(),
                        fontSize = fs.detail,
                        fontFamily = FontFamily.Monospace,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.width(60.dp).padding(end = 10.dp),
                    )
                    val annotated = asmAnnotatedLine(source.lines[lineIndex], source.id, lineIndex, index)
                    Box(Modifier.weight(1f).horizontalScroll(horizontal)) {
                        ClickableText(
                            text = annotated,
                            style = TextStyle(
                                fontSize = fs.body,
                                fontFamily = FontFamily.Monospace,
                                color = codeForeground(),
                            ),
                            softWrap = false,
                            onClick = { offset ->
                                annotated.getStringAnnotations(start = offset, end = offset).firstOrNull()?.let { annotation ->
                                    when (annotation.tag) {
                                        "label" -> state.openLabel(source.id, lineIndex, annotation.item)
                                        "asset" -> state.openAssetReference(annotation.item)
                                        "instruction" -> state.showInstruction(annotation.item)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AsmNavigationHeader(state: AsmWorkspaceState, title: String, detail: String, trailing: String) {
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = state::goBack, enabled = state.canGoBack, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.ArrowBack, "Back", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = state::goForward, enabled = state.canGoForward, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.ArrowForward, "Forward", modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = fs.heading, fontWeight = FontWeight.Bold)
                if (detail.isNotBlank()) Text(detail, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(trailing, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Divider()
}

@Composable
private fun AsmAssetCanvas(state: AsmWorkspaceState, asset: AsmAsset, romName: String, modifier: Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    val bytesResult = remember(asset.file.absolutePath, asset.file.lastModified()) { runCatching { asset.file.readBytes() } }
    val bytes = bytesResult.getOrNull()
    val rows = remember(bytes) { bytes?.asList()?.chunked(16).orEmpty() }
    Column(modifier.fillMaxSize()) {
        AsmNavigationHeader(state, asset.range.path, asset.category, "${asset.range.length} bytes · read-only")
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                AssetFact("ROM source", romName)
                AssetFact("PC range", "\$${asset.range.pcOffset.hex(6)}–\$${(asset.range.endExclusive - 1).hex(6)}")
                AssetFact("SNES start", "\$${asset.range.snesAddress.hex(6)}")
                AssetFact("File", if (asset.file.isFile) "Extracted" else "Missing")
            }
        }
        if (bytes == null) {
            Text(
                bytesResult.exceptionOrNull()?.message ?: "This asset has not been extracted.",
                fontSize = fs.body,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().background(codeBackground()).padding(vertical = 6.dp)) {
                items(rows.size) { rowIndex ->
                    val offset = rowIndex * 16
                    val row = rows[rowIndex]
                    val hex = row.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }.padEnd(47)
                    val ascii = row.joinToString("") { byte ->
                        val value = byte.toInt() and 0xFF
                        if (value in 0x20..0x7E) value.toChar().toString() else "."
                    }
                    Text(
                        "${offset.hex(6)}  $hex  |$ascii|",
                        fontSize = fs.body,
                        fontFamily = FontFamily.Monospace,
                        color = codeForeground(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetFact(label: String, value: String) {
    val fs = LocalEditorTheme.current.fontSize.value
    Column {
        Text(label, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = fs.body, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

private fun sourceSearch(index: AsmReferenceIndex, query: String): List<AsmSearchResult> {
    val results = mutableListOf<AsmSearchResult>()
    index.files.forEach { source ->
        if (source.displayName.contains(query, true) || source.description.contains(query, true) || source.id.contains(query, true)) {
            results += AsmSearchResult(source.displayName, source.description.ifBlank { source.id }, source.id, 0)
        }
        source.sections.asSequence().filter { it.title.contains(query, true) || it.address?.contains(query, true) == true }
            .take(80).forEach { section ->
                results += AsmSearchResult(section.title, "${source.displayName} · ${section.address ?: "line ${section.lineIndex + 1}"}", source.id, section.lineIndex)
            }
    }
    index.labels.asSequence().filter { it.name.contains(query, true) }.take(120).forEach { label ->
        val source = index.file(label.fileId) ?: return@forEach
        results += AsmSearchResult(label.name, "${source.displayName} · line ${label.lineIndex + 1}", label.fileId, label.lineIndex)
    }
    return results.take(250)
}

@Composable
private fun asmAnnotatedLine(line: String, fileId: String, lineIndex: Int, index: AsmReferenceIndex): AnnotatedString {
    val commentColor = if (isDarkCodeTheme()) Color(0xFF789879) else Color(0xFF4D7A50)
    val numberColor = if (isDarkCodeTheme()) Color(0xFFFFB86C) else Color(0xFF9A4F00)
    val directiveColor = if (isDarkCodeTheme()) Color(0xFFC792EA) else Color(0xFF7B1FA2)
    val labelColor = if (isDarkCodeTheme()) Color(0xFF82AAFF) else Color(0xFF1455A0)
    val stringColor = if (isDarkCodeTheme()) Color(0xFFC3E88D) else Color(0xFF397B24)
    val definitionColor = if (isDarkCodeTheme()) Color(0xFFFFCB6B) else Color(0xFF8A6100)
    val foreground = codeForeground()
    val commentStart = line.indexOf(';').let { if (it < 0) line.length else it }
    val code = line.substring(0, commentStart)
    val tokenRegex = Regex("\"[^\"]*\"|\\$[0-9A-Fa-f]+|%[01]+|\\b[0-9]+\\b|\\.?[A-Za-z_][A-Za-z0-9_.]*")
    val matches = tokenRegex.findAll(code).toList()
    val definition = Regex("^\\s*([A-Za-z_][A-Za-z0-9_]*|\\.[A-Za-z0-9_]+):").find(code)?.groupValues?.get(1)
    val instructionMatch = matches.firstOrNull { match ->
        match.value != definition && AsmInstructionReference.find(match.value.substringBefore('.')) != null
    }
    return buildAnnotatedString {
        var cursor = 0
        matches.forEach { match ->
            append(code.substring(cursor, match.range.first))
            val token = match.value
            when {
                token.startsWith('"') -> {
                    val reference = token.removeSurrounding("\"")
                    val asset = index.resolveAsset(reference)
                    if (asset != null) pushStringAnnotation("asset", reference)
                    pushStyle(SpanStyle(color = if (asset != null) labelColor else stringColor, textDecoration = if (asset != null) TextDecoration.Underline else null))
                    append(token)
                    pop()
                    if (asset != null) pop()
                }
                token == definition -> {
                    pushStyle(SpanStyle(color = definitionColor, fontWeight = FontWeight.SemiBold)); append(token); pop()
                }
                match == instructionMatch -> {
                    pushStringAnnotation("instruction", token.substringBefore('.').uppercase())
                    pushStyle(SpanStyle(color = directiveColor, fontWeight = FontWeight.SemiBold)); append(token); pop(); pop()
                }
                token.startsWith('$') || token.startsWith('%') || token.firstOrNull()?.isDigit() == true -> {
                    pushStyle(SpanStyle(color = numberColor)); append(token); pop()
                }
                token.lowercase() in ASM_DIRECTIVES -> {
                    pushStyle(SpanStyle(color = directiveColor)); append(token); pop()
                }
                index.resolveLabel(fileId, lineIndex, token) != null -> {
                    pushStringAnnotation("label", token)
                    pushStyle(SpanStyle(color = labelColor, textDecoration = TextDecoration.Underline)); append(token); pop(); pop()
                }
                else -> {
                    pushStyle(SpanStyle(color = foreground)); append(token); pop()
                }
            }
            cursor = match.range.last + 1
        }
        append(code.substring(cursor))
        if (commentStart < line.length) {
            pushStyle(SpanStyle(color = commentColor)); append(line.substring(commentStart)); pop()
        }
    }
}

@Composable
private fun isDarkCodeTheme(): Boolean = LocalEditorTheme.current.theme.value == EditorTheme.DARK

@Composable
private fun codeBackground(): Color = if (isDarkCodeTheme()) Color(0xFF11131A) else Color(0xFFF8F8F5)

@Composable
private fun codeForeground(): Color = if (isDarkCodeTheme()) Color(0xFFD8DEE9) else Color(0xFF262626)

private fun Int.hex(width: Int): String = toString(16).uppercase().padStart(width, '0')

private val ASM_DIRECTIVES = setOf(
    "org", "warnpc", "incsrc", "incbin", "lorom", "hirom", "math", "reset", "print", "assert",
    "if", "else", "elseif", "endif", "while", "endwhile", "macro", "endmacro", "namespace", "struct", "endstruct",
    "db", "dw", "dl", "dd", "fill", "fillbyte", "pad", "padbyte", "skip", "base", "table", "cleartable",
)

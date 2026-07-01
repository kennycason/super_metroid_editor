package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import com.supermetroid.editor.rom.TilesetGridData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TilesetPreview(
    room: RoomInfo?,
    romParser: RomParser?,
    editorState: EditorState? = null,
    modifier: Modifier = Modifier
) {
    var gridData by remember { mutableStateOf<TilesetGridData?>(null) }
    var tilesetId by remember { mutableStateOf<Int?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var zoomLevel by remember { mutableStateOf(1.8f) }
    var isDragging by remember { mutableStateOf(false) }

    val gfxVersion = editorState?.editVersion ?: 0

    LaunchedEffect(room?.id, romParser, gfxVersion) {
        gridData = null; tilesetId = null; errorMessage = null
        if (room == null || romParser == null) return@LaunchedEffect
        isLoading = true
        try {
            val roomId = room.getRoomIdAsInt()
            val header = withContext(Dispatchers.Default) { romParser.readRoomHeader(roomId) }
            if (header == null) { errorMessage = "Room header not found"; return@LaunchedEffect }
            tilesetId = header.tileset
            val tg = TileGraphics(romParser)
            if (!withContext(Dispatchers.Default) { tg.loadTileset(header.tileset) }) {
                errorMessage = "Failed to load tileset"; return@LaunchedEffect
            }
            editorState?.applyCustomGfxToTileGraphics(tg, header.tileset)
            gridData = withContext(Dispatchers.Default) { tg.renderTilesetGrid() }
        } catch (e: Exception) { errorMessage = e.message ?: "Error" } finally { isLoading = false }
    }

    val fs = LocalEditorTheme.current.fontSize.value
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Text(
                text = if (tilesetId != null) "Tileset ($tilesetId)" else "Tileset",
                fontSize = fs.body, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(EditorColors.romBackground)) {
                when {
                    isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Loading…", color = Color.White, fontSize = fs.body) }
                    errorMessage != null -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(errorMessage!!, color = MaterialTheme.colorScheme.error, fontSize = fs.detail) }
                    room == null || romParser == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Select a room", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = fs.detail) }
                    gridData != null -> {
                        val data = gridData!!
                        val selStart = editorState?.tilesetSelStart
                        val selEnd = editorState?.tilesetSelEnd
                        val bitmap = remember(data, selStart, selEnd) {
                            tilesetGridWithSelection(data, selStart, selEnd).toComposeImageBitmap()
                        }
                        val hScroll = rememberScrollState()
                        val vScroll = rememberScrollState()
                        val coroutineScope = rememberCoroutineScope()

                        var isPanning by remember { mutableStateOf(false) }
                        var lastPanX by remember { mutableStateOf(0f) }
                        var lastPanY by remember { mutableStateOf(0f) }
                        val density = LocalDensity.current.density

                        fun pointerToTile(px: Float, py: Float): Pair<Int, Int> {
                            // Pointer & scroll are in physical pixels; layout uses dp.
                            // Tile display size in pointer units = 16 * zoom * density.
                            val tilePx = 16f * zoomLevel * density
                            val tx = ((px + hScroll.value) / tilePx).toInt()
                            val ty = ((py + vScroll.value) / tilePx).toInt()
                            return Pair(
                                tx.coerceIn(0, data.gridCols - 1),
                                ty.coerceIn(0, data.gridRows - 1)
                            )
                        }

                        Box(
                            modifier = Modifier.fillMaxSize()
                                .onPointerEvent(PointerEventType.Scroll) { event ->
                                    val ne = event.nativeEvent as? MouseEvent
                                    val sd = event.changes.first().scrollDelta
                                    val zoom = ne?.let { it.isControlDown || it.isMetaDown } ?: false
                                    if (zoom) {
                                        zoomLevel = (zoomLevel * if (sd.y < 0) EditorColors.ZOOM_FACTOR else 1f / EditorColors.ZOOM_FACTOR).coerceIn(0.5f, 4f)
                                    } else if (ne?.isShiftDown == true) {
                                        val delta = if (sd.x != 0f) sd.x else sd.y
                                        coroutineScope.launch {
                                            hScroll.scrollTo((hScroll.value + delta.toInt()).coerceIn(0, hScroll.maxValue))
                                        }
                                    }
                                }
                                .onPointerEvent(PointerEventType.Press) { event ->
                                    val ne = event.nativeEvent as? MouseEvent ?: return@onPointerEvent
                                    if (ne.button == MouseEvent.BUTTON2) {
                                        isPanning = true
                                        val p = event.changes.first().position
                                        lastPanX = p.x; lastPanY = p.y
                                    } else if (ne.button == MouseEvent.BUTTON1 && editorState != null) {
                                        val pos = event.changes.first().position
                                        val (tx, ty) = pointerToTile(pos.x, pos.y)
                                        editorState.beginTilesetDrag(tx, ty)
                                        isDragging = true
                                    }
                                }
                                .onPointerEvent(PointerEventType.Move) { event ->
                                    val pos = event.changes.first().position
                                    if (isPanning) {
                                        val ne = event.nativeEvent as? MouseEvent
                                        if (ne != null && (ne.modifiersEx and java.awt.event.InputEvent.BUTTON2_DOWN_MASK) == 0) {
                                            isPanning = false
                                        }
                                        val dx = lastPanX - pos.x; val dy = lastPanY - pos.y
                                        lastPanX = pos.x; lastPanY = pos.y
                                        coroutineScope.launch {
                                            hScroll.scrollTo((hScroll.value + dx.toInt()).coerceIn(0, hScroll.maxValue))
                                            vScroll.scrollTo((vScroll.value + dy.toInt()).coerceIn(0, vScroll.maxValue))
                                        }
                                    }
                                    if (isDragging && editorState != null) {
                                        val (tx, ty) = pointerToTile(pos.x, pos.y)
                                        editorState.updateTilesetDrag(tx, ty)
                                    }
                                }
                                .onPointerEvent(PointerEventType.Release) { event ->
                                    val ne = event.nativeEvent as? MouseEvent
                                    if (ne == null || ne.button == MouseEvent.BUTTON2) isPanning = false
                                    if (isDragging && editorState != null) {
                                        isDragging = false
                                        editorState.endTilesetDrag(gridData!!.gridCols)
                                    }
                                }
                                .horizontalScroll(hScroll)
                                .verticalScroll(vScroll)
                        ) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Tileset grid",
                                modifier = Modifier
                                    .requiredWidth((data.width * zoomLevel).dp)
                                    .requiredHeight((data.height * zoomLevel).dp),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Render tileset grid with rectangle selection highlight. */
private fun tilesetGridWithSelection(
    data: TilesetGridData,
    selStart: Pair<Int, Int>?,
    selEnd: Pair<Int, Int>?
): BufferedImage {
    val img = BufferedImage(data.width, data.height, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, data.width, data.height, data.pixels, 0, data.width)
    if (selStart != null && selEnd != null) {
        val c0 = minOf(selStart.first, selEnd.first)
        val c1 = maxOf(selStart.first, selEnd.first)
        val r0 = minOf(selStart.second, selEnd.second)
        val r1 = maxOf(selStart.second, selEnd.second)
        val g = img.createGraphics()
        val px = c0 * 16; val py = r0 * 16
        val w = (c1 - c0 + 1) * 16; val h = (r1 - r0 + 1) * 16
        g.color = java.awt.Color(255, 255, 255, 60)
        g.fillRect(px, py, w, h)
        g.color = java.awt.Color(255, 255, 255, 220)
        g.stroke = java.awt.BasicStroke(2f)
        g.drawRect(px, py, w - 1, h - 1)
        g.dispose()
    }
    return img
}

package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser

private data class ItemSpriteCoord(val x: Int, val y: Int)

private data class ItemLocation(
    val itemName: String,
    val shortLabel: String,
    val sprite: ItemSpriteCoord?,
    val plm: RomParser.PlmEntry,
    val room: RoomInfo,
    val area: Int,
) {
    val areaName: String get() = itemAreaName(area)
}

private val ITEM_SPRITE_COORDS = mapOf(
    "Morph Ball" to ItemSpriteCoord(0, 0),
    "Bomb" to ItemSpriteCoord(32, 0),
    "Energy Tank" to ItemSpriteCoord(64, 0),
    "Missile" to ItemSpriteCoord(0, 16),
    "Super Missile" to ItemSpriteCoord(32, 16),
    "Power Bomb" to ItemSpriteCoord(64, 16),
    "Reserve Tank" to ItemSpriteCoord(96, 16),
    "Hi-Jump Boots" to ItemSpriteCoord(0, 32),
    "Speed Booster" to ItemSpriteCoord(32, 32),
    "Grapple Beam" to ItemSpriteCoord(64, 32),
    "X-Ray Scope" to ItemSpriteCoord(96, 32),
    "Spring Ball" to ItemSpriteCoord(0, 48),
    "Space Jump" to ItemSpriteCoord(32, 48),
    "Screw Attack" to ItemSpriteCoord(64, 48),
    "Charge Beam" to ItemSpriteCoord(96, 48),
    "Spazer" to ItemSpriteCoord(0, 64),
    "Wave Beam" to ItemSpriteCoord(32, 64),
    "Ice Beam" to ItemSpriteCoord(64, 64),
    "Plasma Beam" to ItemSpriteCoord(96, 64),
    "Varia Suit" to ItemSpriteCoord(0, 80),
    "Gravity Suit" to ItemSpriteCoord(32, 80),
)

private val MAJOR_ITEM_ORDER = listOf(
    "Morph Ball",
    "Bomb",
    "Spring Ball",
    "Hi-Jump Boots",
    "Speed Booster",
    "Space Jump",
    "Screw Attack",
    "Varia Suit",
    "Gravity Suit",
    "Grapple Beam",
    "X-Ray Scope",
    "Charge Beam",
    "Ice Beam",
    "Wave Beam",
    "Spazer",
    "Plasma Beam",
)

private val LOCATION_ITEM_SORT = compareBy<ItemLocation>(
    { if (it.area < 0) Int.MAX_VALUE else it.area },
    { it.room.getRoomIdAsInt() },
    { it.plm.y },
    { it.plm.x },
    { it.plm.id },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemLocationPanel(
    rooms: List<RoomInfo>,
    selectedRoom: RoomInfo?,
    romParser: RomParser?,
    editorState: EditorState,
    onRoomSelected: (RoomInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val itemBitmap = remember {
        try {
            useResource("item_sprites.png") { loadImageBitmap(it) }
        } catch (_: Exception) {
            null
        }
    }

    val editVersion = editorState.editVersion
    val romVersion = editorState.romVersion
    val itemLocations = remember(rooms, romParser, editVersion, romVersion, editorState.project) {
        if (romParser == null) emptyList()
        else collectItemLocations(rooms, romParser, editorState)
    }

    val majorLocations = remember(itemLocations) {
        MAJOR_ITEM_ORDER.mapNotNull { name -> itemLocations.firstOrNull { it.itemName == name } }
    }
    val energyTanks = remember(itemLocations) { itemLocations.ofType("Energy Tank") }
    val reserveTanks = remember(itemLocations) { itemLocations.ofType("Reserve Tank") }
    val missiles = remember(itemLocations) { itemLocations.ofType("Missile") }
    val superMissiles = remember(itemLocations) { itemLocations.ofType("Super Missile") }
    val powerBombs = remember(itemLocations) { itemLocations.ofType("Power Bomb") }
    val knownGroupedNames = remember {
        (MAJOR_ITEM_ORDER + listOf("Energy Tank", "Reserve Tank", "Missile", "Super Missile", "Power Bomb")).toSet()
    }
    val otherItems = remember(itemLocations, knownGroupedNames) {
        itemLocations.filter { it.itemName !in knownGroupedNames }.sortedWith(LOCATION_ITEM_SORT)
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Items (${itemLocations.size})",
                    fontSize = fs.heading,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            Divider()

            if (romParser == null || rooms.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Open a ROM to see item locations",
                        fontSize = fs.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "major_header") {
                    ItemSectionHeader("Major")
                }
                item(key = "major_grid") {
                    if (majorLocations.isEmpty()) {
                        EmptyItemSection("No major items found")
                    } else {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (location in majorLocations) {
                                MajorItemButton(
                                    bitmap = itemBitmap,
                                    location = location,
                                    isSelected = selectedRoom?.handle == location.room.handle,
                                    onClick = { onRoomSelected(location.room) },
                                )
                            }
                        }
                    }
                }

                itemRows(
                    title = "Energy Tanks",
                    locations = energyTanks,
                    selectedRoom = selectedRoom,
                    bitmap = itemBitmap,
                    onRoomSelected = onRoomSelected,
                )
                itemRows(
                    title = "Reserve Tanks",
                    locations = reserveTanks,
                    selectedRoom = selectedRoom,
                    bitmap = itemBitmap,
                    onRoomSelected = onRoomSelected,
                )
                itemRows(
                    title = "Missiles",
                    locations = missiles,
                    selectedRoom = selectedRoom,
                    bitmap = itemBitmap,
                    onRoomSelected = onRoomSelected,
                )
                itemRows(
                    title = "Super Missiles",
                    locations = superMissiles,
                    selectedRoom = selectedRoom,
                    bitmap = itemBitmap,
                    onRoomSelected = onRoomSelected,
                )
                itemRows(
                    title = "Power Bombs",
                    locations = powerBombs,
                    selectedRoom = selectedRoom,
                    bitmap = itemBitmap,
                    onRoomSelected = onRoomSelected,
                )
                itemRows(
                    title = "Other Items",
                    locations = otherItems,
                    selectedRoom = selectedRoom,
                    bitmap = itemBitmap,
                    onRoomSelected = onRoomSelected,
                    skipWhenEmpty = true,
                )
            }
        }
    }
}

private fun collectItemLocations(
    rooms: List<RoomInfo>,
    romParser: RomParser,
    editorState: EditorState,
): List<ItemLocation> {
    val itemDefByPlmId = buildMap {
        for (def in RomParser.ITEM_DEFS) {
            put(def.chozoId, def)
            put(def.visibleId, def)
            put(def.hiddenId, def)
        }
    }

    val result = mutableListOf<ItemLocation>()
    for (roomInfo in rooms) {
        val roomId = try {
            roomInfo.getRoomIdAsInt()
        } catch (_: Exception) {
            continue
        }
        val roomHeader = try {
            romParser.readRoomHeader(roomId)
        } catch (_: Exception) {
            null
        }
        val plms = try {
            editorState.effectivePlmsForRoom(roomId, romParser)
        } catch (_: Exception) {
            emptyList()
        }

        for (plm in plms) {
            val def = itemDefByPlmId[plm.id] ?: continue
            result.add(
                ItemLocation(
                    itemName = def.name,
                    shortLabel = def.shortLabel,
                    sprite = ITEM_SPRITE_COORDS[def.name],
                    plm = plm,
                    room = roomInfo,
                    area = roomHeader?.area ?: -1,
                )
            )
        }
    }

    return result.sortedWith(LOCATION_ITEM_SORT)
}

private fun List<ItemLocation>.ofType(itemName: String): List<ItemLocation> =
    filter { it.itemName == itemName }.sortedWith(LOCATION_ITEM_SORT)

@Composable
private fun ItemSectionHeader(title: String) {
    val fs = LocalEditorTheme.current.fontSize.value
    Text(
        text = title,
        fontSize = fs.body,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyItemSection(text: String) {
    val fs = LocalEditorTheme.current.fontSize.value
    Text(
        text = text,
        fontSize = fs.detail,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemRows(
    title: String,
    locations: List<ItemLocation>,
    selectedRoom: RoomInfo?,
    bitmap: ImageBitmap?,
    onRoomSelected: (RoomInfo) -> Unit,
    skipWhenEmpty: Boolean = false,
) {
    if (skipWhenEmpty && locations.isEmpty()) return

    item(key = "${title}_header") {
        ItemSectionHeader(title)
    }
    if (locations.isEmpty()) {
        item(key = "${title}_empty") {
            EmptyItemSection("No ${title.lowercase()} found")
        }
        return
    }

    itemsIndexed(
        items = locations,
        key = { index, location ->
            "${title}_${location.room.handle}_${location.plm.id}_${location.plm.x}_${location.plm.y}_${location.plm.param}_$index"
        },
    ) { index, location ->
        ItemLocationRow(
            bitmap = bitmap,
            location = location,
            index = index + 1,
            isSelected = selectedRoom?.handle == location.room.handle,
            onClick = { onRoomSelected(location.room) },
        )
    }
}

@Composable
private fun MajorItemButton(
    bitmap: ImageBitmap?,
    location: ItemLocation,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(32.dp)
            .clickable(onClick = onClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            ItemGlyph(
                bitmap = bitmap,
                sprite = location.sprite,
                label = location.shortLabel,
                sizeDp = 24,
            )
        }
    }
}

@Composable
private fun ItemLocationRow(
    bitmap: ImageBitmap?,
    location: ItemLocation,
    index: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ItemGlyph(
                bitmap = bitmap,
                sprite = location.sprite,
                label = location.shortLabel,
                sizeDp = 20,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "#$index ${location.areaName}",
                    fontSize = fs.body,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = location.room.name,
                    fontSize = fs.detail,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "(${location.plm.x},${location.plm.y})",
                fontSize = fs.detail,
                fontFamily = FontFamily.Monospace,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ItemGlyph(
    bitmap: ImageBitmap?,
    sprite: ItemSpriteCoord?,
    label: String,
    sizeDp: Int,
) {
    if (bitmap != null && sprite != null) {
        Canvas(modifier = Modifier.size(sizeDp.dp)) {
            drawImage(
                image = bitmap,
                srcOffset = IntOffset(sprite.x, sprite.y),
                srcSize = IntSize(16, 16),
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            )
        }
    } else {
        Surface(
            modifier = Modifier.size(sizeDp.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(4.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = label.take(2),
                    fontSize = LocalEditorTheme.current.fontSize.value.detail,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

private fun itemAreaName(area: Int): String = when (area) {
    0 -> "Crateria"
    1 -> "Brinstar"
    2 -> "Norfair"
    3 -> "Wrecked Ship"
    4 -> "Maridia"
    5 -> "Tourian"
    6 -> "Ceres"
    else -> "Unknown"
}

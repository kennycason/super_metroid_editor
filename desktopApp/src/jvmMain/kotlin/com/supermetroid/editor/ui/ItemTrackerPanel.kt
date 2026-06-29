package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.CustomItemDef
import com.supermetroid.editor.emulator.GameSnapshot
import io.github.oshai.kotlinlogging.KotlinLogging

private val itemTrackerLog = KotlinLogging.logger {}

/** Bit flags for collected items at 0x7E09A4 */
private object ItemFlags {
    const val VARIA_SUIT = 0x0001
    const val SPRING_BALL = 0x0002
    const val MORPH_BALL = 0x0004
    const val SCREW_ATTACK = 0x0008
    const val GRAVITY_SUIT = 0x0020
    const val HI_JUMP = 0x0100
    const val SPACE_JUMP = 0x0200
    const val BOMBS = 0x1000
    const val SPEED_BOOSTER = 0x2000
    const val GRAPPLE = 0x4000
    const val XRAY = 0x8000
}

/** Bit flags for collected beams at 0x7E09A8 */
private object BeamFlags {
    const val WAVE = 0x0001
    const val ICE = 0x0002
    const val SPAZER = 0x0004
    const val PLASMA = 0x0008
    const val CHARGE = 0x1000
}

/** Sprite coordinates in item_sprites.png (16x16 grid) */
private data class SpriteCoord(val x: Int, val y: Int)
private data class ItemSpriteEntry(val label: String, val coord: SpriteCoord, val flag: Int)
private data class CustomItemSpriteEntry(val item: CustomItemDef, val coord: SpriteCoord, val flag: Int)

private val ITEM_SPRITES: List<ItemSpriteEntry> = listOf(
    // name, sprite coord, bit flag from collectedItems
    ItemSpriteEntry("Morph", SpriteCoord(0, 0), ItemFlags.MORPH_BALL),
    ItemSpriteEntry("Bombs", SpriteCoord(32, 0), ItemFlags.BOMBS),
    ItemSpriteEntry("Spring", SpriteCoord(0, 48), ItemFlags.SPRING_BALL),
    ItemSpriteEntry("Screw", SpriteCoord(64, 48), ItemFlags.SCREW_ATTACK),
    ItemSpriteEntry("HiJump", SpriteCoord(0, 32), ItemFlags.HI_JUMP),
    ItemSpriteEntry("Space", SpriteCoord(32, 48), ItemFlags.SPACE_JUMP),
    ItemSpriteEntry("Speed", SpriteCoord(32, 32), ItemFlags.SPEED_BOOSTER),
    ItemSpriteEntry("Grapple", SpriteCoord(64, 32), ItemFlags.GRAPPLE),
    ItemSpriteEntry("XRay", SpriteCoord(96, 32), ItemFlags.XRAY),
    ItemSpriteEntry("Varia", SpriteCoord(0, 80), ItemFlags.VARIA_SUIT),
    ItemSpriteEntry("Gravity", SpriteCoord(32, 80), ItemFlags.GRAVITY_SUIT),
)

private val BEAM_SPRITES: List<Triple<String, SpriteCoord, Int>> = listOf(
    Triple("Charge", SpriteCoord(96, 48), BeamFlags.CHARGE),
    Triple("Wave", SpriteCoord(32, 64), BeamFlags.WAVE),
    Triple("Ice", SpriteCoord(64, 64), BeamFlags.ICE),
    Triple("Spazer", SpriteCoord(0, 64), BeamFlags.SPAZER),
    Triple("Plasma", SpriteCoord(96, 64), BeamFlags.PLASMA),
)

/** Ammo item sprite coords */
private val MISSILE_SPRITE = SpriteCoord(0, 16)
private val SUPER_SPRITE = SpriteCoord(32, 16)
private val PB_SPRITE = SpriteCoord(64, 16)
private val ETANK_SPRITE = SpriteCoord(64, 0)
private val RESERVE_SPRITE = SpriteCoord(96, 16)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemTrackerPanel(
    snapshot: GameSnapshot?,
    customItems: List<CustomItemDef> = emptyList(),
    onCustomItemToggled: ((CustomItemDef, Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val themeState = LocalEditorTheme.current
    val currentFontSize = themeState.fontSize.value

    val itemBitmap = remember {
        try {
            useResource("item_sprites.png") { loadImageBitmap(it) }
        } catch (e: Exception) {
            itemTrackerLog.warn(e) { "[ItemTracker] Failed to load item_sprites.png: ${e.message}" }
            null
        }
    }

    if (itemBitmap == null) {
        Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), tonalElevation = 2.dp) {
            Text("Inventory sprites not found", modifier = Modifier.padding(12.dp), fontSize = currentFontSize.detail)
        }
        return
    }

    val items = snapshot?.collectedItems ?: 0
    val beams = snapshot?.collectedBeams ?: 0
    val customItemSprites = remember(customItems) {
        customItems
            .filter { it.itemWordAddress == 0x09A4 && it.bitMask != 0 }
            .map { item -> CustomItemSpriteEntry(item, SpriteCoord(item.iconX, item.iconY), item.bitMask) }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Inventory",
                fontWeight = FontWeight.Bold,
                fontSize = currentFontSize.body,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // ── Ammo row ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AmmoIcon(itemBitmap, ETANK_SPRITE, snapshot?.health, snapshot?.maxHealth)
                AmmoIcon(itemBitmap, MISSILE_SPRITE, snapshot?.missiles, snapshot?.maxMissiles)
                AmmoIcon(itemBitmap, SUPER_SPRITE, snapshot?.superMissiles, snapshot?.maxSuperMissiles)
                AmmoIcon(itemBitmap, PB_SPRITE, snapshot?.powerBombs, snapshot?.maxPowerBombs)
                AmmoIcon(itemBitmap, RESERVE_SPRITE, snapshot?.reserveEnergy, snapshot?.maxReserveEnergy)
            }

            // ── Items grid ──
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (entry in ITEM_SPRITES) {
                    SpriteCell(itemBitmap, entry.coord, obtained = items and entry.flag != 0)
                }
                for (entry in customItemSprites) {
                    val obtained = items and entry.flag != 0
                    SpriteCell(
                        itemBitmap,
                        entry.coord,
                        obtained = obtained,
                        modifier = if (onCustomItemToggled != null) {
                            Modifier.clickable { onCustomItemToggled(entry.item, !obtained) }
                        } else {
                            Modifier
                        },
                    )
                }
            }

            // ── Beams row ──
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for ((_, coord, flag) in BEAM_SPRITES) {
                    SpriteCell(itemBitmap, coord, obtained = beams and flag != 0)
                }
            }
        }
    }
}

@Composable
private fun SpriteCell(
    bitmap: ImageBitmap,
    coord: SpriteCoord,
    obtained: Boolean,
    displaySize: Int = 28,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(displaySize.dp)) {
        drawImage(
            image = bitmap,
            srcOffset = IntOffset(coord.x, coord.y),
            srcSize = IntSize(16, 16),
            dstOffset = IntOffset(0, 0),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            alpha = if (obtained) 1.0f else 0.3f,
            colorFilter = if (obtained) null else ColorFilter.colorMatrix(
                ColorMatrix().apply { setToSaturation(0f) }
            ),
        )
    }
}

@Composable
private fun AmmoIcon(
    bitmap: ImageBitmap,
    coord: SpriteCoord,
    current: Int?,
    max: Int?,
) {
    val themeState = LocalEditorTheme.current
    val currentFontSize = themeState.fontSize.value
    val hasAny = (max ?: 0) > 0

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SpriteCell(bitmap, coord, obtained = hasAny, displaySize = 22)
        Text(
            text = if (hasAny) "${current ?: 0}" else "-",
            fontSize = currentFontSize.detail,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = if (hasAny) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

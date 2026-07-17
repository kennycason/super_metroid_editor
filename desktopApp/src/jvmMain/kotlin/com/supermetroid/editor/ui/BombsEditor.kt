package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RomParser
import kotlin.math.max
import kotlin.math.min

const val BOMB_CONFIG_TYPE = "bombs"

const val BOMB_MAX_ACTIVE_KEY = "max_active_bombs"
const val BOMB_FUSE_FRAMES_KEY = "fuse_frames"
const val BOMB_COOLDOWN_FRAMES_KEY = "cooldown_frames"
const val BOMB_EXPLOSION_FRAME_DELAY_KEY = "explosion_frame_delay"

const val BOMB_FUSE_TIMER_PC = 0x083F9B
const val BOMB_ACTIVE_HARD_CAP_OPERAND_PC = 0x0840F4
const val BOMB_COOLDOWN_PC = 0x08427F
const val BOMB_EXPLOSION_FRAME_DELAY_OPERAND_PC = 0x09815B

const val BOMB_DEFAULT_MAX_ACTIVE = 3
const val BOMB_DEFAULT_HARD_CAP = 5
const val BOMB_DEFAULT_FUSE_FRAMES = 0x003C
const val BOMB_DEFAULT_COOLDOWN_FRAMES = 0x10
const val BOMB_DEFAULT_EXPLOSION_FRAME_DELAY = 1

const val BOMB_MAX_PROJECTILE_SLOTS = 5

data class BombsRomDefaults(
    val maxActiveBombs: Int,
    val fuseFrames: Int,
    val explosionFrameDelay: Int,
    val hardCap: Int,
    val cooldownFrames: Int,
)

fun readBombsRomDefaults(romParser: RomParser?): BombsRomDefaults {
    val rom = try {
        romParser?.getRomData()
    } catch (_: Exception) {
        null
    }

    val fuseFrames = rom?.readWordOrNull(BOMB_FUSE_TIMER_PC) ?: BOMB_DEFAULT_FUSE_FRAMES
    val hardCap = rom?.readWordOrNull(BOMB_ACTIVE_HARD_CAP_OPERAND_PC) ?: BOMB_DEFAULT_HARD_CAP
    val cooldownFrames = rom?.readByteOrNull(BOMB_COOLDOWN_PC) ?: BOMB_DEFAULT_COOLDOWN_FRAMES
    val explosionFrameDelay =
        rom?.readWordOrNull(BOMB_EXPLOSION_FRAME_DELAY_OPERAND_PC) ?: BOMB_DEFAULT_EXPLOSION_FRAME_DELAY

    return BombsRomDefaults(
        maxActiveBombs = derivePracticalBombCap(fuseFrames, cooldownFrames, hardCap),
        fuseFrames = fuseFrames,
        explosionFrameDelay = explosionFrameDelay,
        hardCap = hardCap,
        cooldownFrames = cooldownFrames,
    )
}

fun calculateBombCooldownForConfig(
    maxActiveBombs: Int,
    fuseFrames: Int,
    baseCooldownFrames: Int = BOMB_DEFAULT_COOLDOWN_FRAMES,
): Int {
    val cap = maxActiveBombs.coerceIn(1, BOMB_MAX_PROJECTILE_SLOTS)
    val base = baseCooldownFrames.coerceIn(0, 255)
    if (cap <= BOMB_DEFAULT_MAX_ACTIVE) return base

    val fuse = fuseFrames.coerceIn(1, 65535)
    val neededToReachCap = max(0, (fuse - 1) / cap)
    return min(base, neededToReachCap).coerceIn(0, 255)
}

fun derivePracticalBombCap(fuseFrames: Int, cooldownFrames: Int, hardCap: Int): Int {
    val cap = hardCap.coerceIn(1, BOMB_MAX_PROJECTILE_SLOTS)
    val interval = (cooldownFrames + 1).coerceIn(1, 256)
    return (fuseFrames / interval).coerceAtLeast(1).coerceAtMost(cap)
}

private fun ByteArray.readByteOrNull(offset: Int): Int? =
    if (offset in indices) this[offset].toInt() and 0xFF else null

private fun ByteArray.readWordOrNull(offset: Int): Int? =
    if (offset >= 0 && offset + 1 < size) {
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    } else {
        null
    }

@Composable
fun BombsEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    val romDefaults = remember(romParser) { readBombsRomDefaults(romParser) }
    val values = remember(patch.id, editorState.patchVersion, romDefaults) {
        val stored = patch.configData
        val initialMaxActive = stored?.get(BOMB_MAX_ACTIVE_KEY) ?: romDefaults.maxActiveBombs
        val initialFuseFrames = stored?.get(BOMB_FUSE_FRAMES_KEY) ?: romDefaults.fuseFrames
        mutableStateMapOf(
            BOMB_MAX_ACTIVE_KEY to initialMaxActive,
            BOMB_FUSE_FRAMES_KEY to initialFuseFrames,
            BOMB_COOLDOWN_FRAMES_KEY to (
                stored?.get(BOMB_COOLDOWN_FRAMES_KEY)
                    ?: calculateBombCooldownForConfig(initialMaxActive, initialFuseFrames, romDefaults.cooldownFrames)
            ),
            BOMB_EXPLOSION_FRAME_DELAY_KEY to (
                stored?.get(BOMB_EXPLOSION_FRAME_DELAY_KEY) ?: romDefaults.explosionFrameDelay
            ),
        )
    }

    fun apply(key: String, value: Int) {
        values[key] = value
        editorState.setPatchConfigData(patch.id, key, value)
    }

    val maxActive = (values[BOMB_MAX_ACTIVE_KEY] ?: romDefaults.maxActiveBombs)
        .coerceIn(1, BOMB_MAX_PROJECTILE_SLOTS)
    val fuseFrames = (values[BOMB_FUSE_FRAMES_KEY] ?: romDefaults.fuseFrames).coerceIn(1, 9999)
    val cooldownFrames = (values[BOMB_COOLDOWN_FRAMES_KEY] ?: romDefaults.cooldownFrames).coerceIn(0, 255)
    val explosionDelay = (values[BOMB_EXPLOSION_FRAME_DELAY_KEY] ?: romDefaults.explosionFrameDelay)
        .coerceIn(1, 255)
    val recommendedCooldown = calculateBombCooldownForConfig(
        maxActiveBombs = maxActive,
        fuseFrames = fuseFrames,
        baseCooldownFrames = romDefaults.cooldownFrames,
    )
    val reachableBombs = derivePracticalBombCap(fuseFrames, cooldownFrames, maxActive)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Bombs", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Controls normal bomb count and timing. Super Metroid has five bomb projectile slots; the stock three-bomb feel comes from fuse length plus bomb cooldown.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF00BCD4).copy(alpha = 0.06f),
            border = BorderStroke(1.dp, Color(0xFF00BCD4).copy(alpha = 0.2f)),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Normal Bombs", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF00838F))
                Spacer(Modifier.height(8.dp))

                BombChoiceRow(
                    label = "Max Active Bombs",
                    description = "Hard cap for live normal bombs. The engine has five bomb-specific projectile slots.",
                    value = maxActive,
                    options = 1..BOMB_MAX_PROJECTILE_SLOTS,
                    onChange = { selected ->
                        apply(BOMB_MAX_ACTIVE_KEY, selected)
                        val adjustedCooldown = calculateBombCooldownForConfig(
                            maxActiveBombs = selected,
                            fuseFrames = fuseFrames,
                            baseCooldownFrames = romDefaults.cooldownFrames,
                        )
                        if (cooldownFrames > adjustedCooldown) {
                            apply(BOMB_COOLDOWN_FRAMES_KEY, adjustedCooldown)
                        }
                    },
                )
                Divider(modifier = Modifier.padding(vertical = 6.dp))
                BombConfigRow(
                    label = "Fuse Frames",
                    description = "Frames before a normal or power bomb begins exploding. Vanilla is 60 frames.",
                    value = fuseFrames,
                    range = 1..9999,
                    suffix = "frames",
                    onChange = { apply(BOMB_FUSE_FRAMES_KEY, it) },
                )
                Divider(modifier = Modifier.padding(vertical = 6.dp))
                BombConfigRow(
                    label = "Lay Cooldown",
                    description = "Frames before another bomb can be laid. Vanilla is 16; lower values lay bombs faster.",
                    value = cooldownFrames,
                    range = 0..255,
                    suffix = "frames",
                    onChange = { apply(BOMB_COOLDOWN_FRAMES_KEY, it) },
                )
                Divider(modifier = Modifier.padding(vertical = 6.dp))
                BombConfigRow(
                    label = "Explosion Frame Delay",
                    description = "Instruction timer used after the fuse expires. Higher values make the explosion animation linger.",
                    value = explosionDelay,
                    range = 1..255,
                    suffix = "frames",
                    onChange = { apply(BOMB_EXPLOSION_FRAME_DELAY_KEY, it) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "ROM defaults: ${romDefaults.maxActiveBombs} active, ${romDefaults.fuseFrames} fuse frames, " +
                "${romDefaults.cooldownFrames} cooldown frames, ${romDefaults.explosionFrameDelay} explosion frame delay.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (reachableBombs >= maxActive) {
                "Current timing can reach the selected cap. Recommended cooldown for this fuse/cap is $recommendedCooldown or lower."
            } else {
                "Current timing reaches about $reachableBombs bombs before the oldest one explodes. Lower lay cooldown or raise fuse to reach $maxActive."
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                apply(BOMB_MAX_ACTIVE_KEY, romDefaults.maxActiveBombs)
                apply(BOMB_FUSE_FRAMES_KEY, romDefaults.fuseFrames)
                apply(BOMB_COOLDOWN_FRAMES_KEY, romDefaults.cooldownFrames)
                apply(BOMB_EXPLOSION_FRAME_DELAY_KEY, romDefaults.explosionFrameDelay)
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text("Reset to ROM Defaults", fontSize = 12.sp)
        }
    }
}

@Composable
private fun BombChoiceRow(
    label: String,
    description: String,
    value: Int,
    options: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(description, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row {
            for (option in options) {
                val selected = option == value
                val shape = RoundedCornerShape(4.dp)
                Surface(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(32.dp, 28.dp)
                        .clip(shape)
                        .border(
                            width = 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = shape,
                        )
                        .clickable { onChange(option) },
                    shape = shape,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            option.toString(),
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BombConfigRow(
    label: String,
    description: String,
    value: Int,
    range: IntRange,
    suffix: String,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(description, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BombNumberInput(value, range, onChange, Modifier.width(88.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            suffix,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.Left,
        )
    }
}

@Composable
private fun BombNumberInput(
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(5)
            text = filtered
            filtered.toIntOrNull()?.let { onChange(it.coerceIn(range.first, range.last)) }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

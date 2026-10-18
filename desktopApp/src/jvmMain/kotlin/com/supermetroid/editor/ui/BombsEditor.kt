package com.supermetroid.editor.ui

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

    val header = if (rom != null && rom.size % 0x8000 == 512) 512 else 0
    val fuseFrames = rom?.readWordOrNull(header + BOMB_FUSE_TIMER_PC) ?: BOMB_DEFAULT_FUSE_FRAMES
    val hardCap = rom?.readWordOrNull(header + BOMB_ACTIVE_HARD_CAP_OPERAND_PC) ?: BOMB_DEFAULT_HARD_CAP
    val cooldownFrames = rom?.readByteOrNull(header + BOMB_COOLDOWN_PC) ?: BOMB_DEFAULT_COOLDOWN_FRAMES
    val explosionFrameDelay =
        rom?.readWordOrNull(header + BOMB_EXPLOSION_FRAME_DELAY_OPERAND_PC) ?: BOMB_DEFAULT_EXPLOSION_FRAME_DELAY

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

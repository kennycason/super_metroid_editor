package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser

const val FANFARE_CONFIG_TYPE = "fanfares"

const val FANFARE_FRAMES_KEY = "item_fanfare_frames"

const val FANFARE_MESSAGE_BOX_WAIT_PC = 0x028491
val FANFARE_MUSIC_RESUME_DELAY_PCS = listOf(
    0x0208DF, // Beam pickups
    0x020906, // Equipment pickups
    0x020931, // Grapple beam
    0x020958, // X-ray scope
    0x020976, // Energy tank
    0x020999, // Reserve tank
    0x0209C2, // Missile tank
    0x0209EB, // Super missile tank
    0x020A14, // Power bomb tank
)

const val FANFARE_DEFAULT_FRAMES = 0x0168
const val FANFARE_TRIMMED_FRAMES = 0x00F0
const val FANFARE_QUICK_FRAMES = 0x0010
const val FANFARE_MIN_FRAMES = 1
const val FANFARE_MAX_FRAMES = 9999

data class FanfareRomDefaults(
    val itemFanfareFrames: Int,
    val roomMusicResumeFrames: Int,
)

fun readFanfareRomDefaults(romParser: RomParser?): FanfareRomDefaults {
    val rom = try {
        romParser?.getRomData()
    } catch (_: Exception) {
        null
    }
    val header = if (rom != null && rom.size % 0x8000 == 512) 512 else 0
    val messageFrames = rom?.readWordOrNull(header + FANFARE_MESSAGE_BOX_WAIT_PC) ?: FANFARE_DEFAULT_FRAMES
    val musicFrames = rom?.readWordOrNull(header + FANFARE_MUSIC_RESUME_DELAY_PCS.first()) ?: FANFARE_DEFAULT_FRAMES
    return FanfareRomDefaults(
        itemFanfareFrames = messageFrames,
        roomMusicResumeFrames = musicFrames,
    )
}

private fun ByteArray.readWordOrNull(offset: Int): Int? =
    if (offset >= 0 && offset + 1 < size) {
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    } else {
        null
    }

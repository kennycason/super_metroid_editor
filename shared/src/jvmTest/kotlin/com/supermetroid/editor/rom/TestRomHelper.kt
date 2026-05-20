package com.supermetroid.editor.rom

import java.io.File

/**
 * Shared helper for tests that need the Super Metroid ROM.
 * Returns null (and tests should skip) if the ROM is not available.
 */
object TestRomHelper {
    private val ROM_PATHS = listOf(
        "test-resources/Super Metroid (JU) [!].smc",
        "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
    )

    /** Load the ROM as a byte array, or null if not found. */
    fun loadRomBytes(): ByteArray? {
        for (path in ROM_PATHS) {
            val f = File(path)
            if (f.exists()) return f.readBytes()
        }
        return null
    }

    /** Load the ROM into a RomParser, or null if not found. */
    fun loadRomParser(): RomParser? {
        val bytes = loadRomBytes() ?: return null
        return RomParser(bytes)
    }
}

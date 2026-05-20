package com.supermetroid.editor.rom

import java.io.File

/**
 * Shared helper for tests that need the Super Metroid ROM.
 * Duplicate of shared module's TestRomHelper (test sources aren't cross-module visible).
 */
object TestRomHelper {
    private val ROM_PATHS = listOf(
        "test-resources/Super Metroid (JU) [!].smc",
        "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
    )

    fun loadRomBytes(): ByteArray? {
        for (path in ROM_PATHS) {
            val f = File(path)
            if (f.exists()) return f.readBytes()
        }
        return null
    }

    fun loadRomParser(): RomParser? {
        val bytes = loadRomBytes() ?: return null
        return RomParser(bytes)
    }
}

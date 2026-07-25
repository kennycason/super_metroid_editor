package com.supermetroid.editor.procgen

import com.supermetroid.editor.rom.RomParser

object BiomeSafetyMask {
    fun protectNonPlainMetadata(
        width: Int,
        height: Int,
        originalWords: IntArray,
        originalBts: IntArray,
        plms: List<RomParser.PlmEntry> = emptyList(),
        metadataHaloBlocks: Int = DEFAULT_METADATA_HALO_BLOCKS,
        plmHaloBlocks: Int = DEFAULT_PLM_HALO_BLOCKS,
    ): BooleanArray {
        require(width >= 0 && height >= 0) { "room dimensions must be non-negative" }
        val total = width * height
        require(originalWords.size >= total) { "original word grid smaller than room" }
        require(originalBts.size >= total) { "original BTS grid smaller than room" }

        val mask = BooleanArray(total)
        val metadataHalo = metadataHaloBlocks.coerceAtLeast(0)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val type = (originalWords[i] shr 12) and 0xF
                if ((type != PLAIN_AIR_TYPE && type != PLAIN_SOLID_TYPE) || originalBts[i] != 0) {
                    protectRect(mask, width, height, x - metadataHalo, y - metadataHalo, x + metadataHalo, y + metadataHalo)
                }
            }
        }

        val halo = plmHaloBlocks.coerceAtLeast(0)
        for (plm in plms) {
            protectRect(mask, width, height, plm.x - halo, plm.y - halo, plm.x + halo, plm.y + halo)
        }
        return mask
    }

    private fun protectRect(mask: BooleanArray, width: Int, height: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        if (width <= 0 || height <= 0) return
        if (x1 < 0 || y1 < 0 || x0 >= width || y0 >= height) return
        val clampedX0 = x0.coerceIn(0, width - 1)
        val clampedX1 = x1.coerceIn(0, width - 1)
        val clampedY0 = y0.coerceIn(0, height - 1)
        val clampedY1 = y1.coerceIn(0, height - 1)
        if (clampedX1 < clampedX0 || clampedY1 < clampedY0) return
        for (y in clampedY0..clampedY1) {
            for (x in clampedX0..clampedX1) {
                mask[y * width + x] = true
            }
        }
    }

    private const val PLAIN_AIR_TYPE = 0x0
    private const val PLAIN_SOLID_TYPE = 0x8
    private const val DEFAULT_METADATA_HALO_BLOCKS = 1
    private const val DEFAULT_PLM_HALO_BLOCKS = 2
}

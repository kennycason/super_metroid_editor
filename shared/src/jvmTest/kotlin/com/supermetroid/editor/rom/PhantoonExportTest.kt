package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Regression coverage for the disproved legacy Phantoon compressed-block mapping. */
@Suppress("DEPRECATION")
class PhantoonExportTest {

    @Test
    fun `legacy Phantoon addresses are rejected as LZ5 streams`() {
        val parser = TestRomHelper.loadRomParser() ?: return

        EnemySpriteGraphics.PHANTOON_BLOCKS.forEach { block ->
            assertFailsWith<LZ5Codec.FormatException>(block.label) {
                parser.decompressLZ5AtPc(block.pcAddress)
            }
        }
        assertFalse(EnemySpriteGraphics(parser).load(EnemySpriteGraphics.PHANTOON_BLOCKS))
    }
}

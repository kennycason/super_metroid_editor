package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

class SamusFrameCountTest {
    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        return null
    }

    @Test
    fun `standing animation variants have bounded frame counts`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        // Stand group variants 1-8 should have small frame counts (bounded by next pointer)
        for (animId in 1..8) {
            val count = decoder.getFrameCount(animId)
            assertTrue(count in 1..20, "Anim $animId: expected 1-20 frames, got $count")
        }
    }

    @Test
    fun `running animations have 10 frames each`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        for (animId in 9..17) {
            val count = decoder.getFrameCount(animId)
            assertTrue(count == 10, "Run anim $animId: expected 10 frames, got $count")
        }
    }

    @Test
    fun `spin jump has 3 frames`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        assertTrue(decoder.getFrameCount(0x25) == 3, "Spin jump L should have 3 frames")
        assertTrue(decoder.getFrameCount(0x26) == 3, "Spin jump R should have 3 frames")
    }
}

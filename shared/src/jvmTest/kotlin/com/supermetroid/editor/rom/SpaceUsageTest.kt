package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpaceUsageTest {

    @Test
    fun `Landing Site space usage has valid sizes`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val usage = rp.readRoomSpaceUsage(0x91F8)
        assertNotNull(usage)
        assertTrue(usage!!.levelDataCompressed > 0, "Level data should have non-zero compressed size")
        assertTrue(usage.levelDataDecompressed > 0, "Level data should have non-zero decompressed size")
        assertTrue(usage.plmCount > 0, "Landing Site should have PLMs")
        assertTrue(usage.enemyCount > 0, "Landing Site should have enemies")
        assertTrue(usage.doorCount > 0, "Landing Site should have doors")
        assertTrue(usage.scrollBytes > 0, "Landing Site should have scroll data")
    }

    @Test
    fun `Parlor space usage has expected PLM and enemy counts`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val usage = rp.readRoomSpaceUsage(0x92FD)
        assertNotNull(usage)
        assertTrue(usage!!.plmCount >= 10, "Parlor should have at least 10 PLMs")
        assertTrue(usage.enemyCount >= 2, "Parlor should have at least 2 enemies")
        assertTrue(usage.doorCount >= 4, "Parlor should have at least 4 doors")
    }

    @Test
    fun `space usage byte counts are consistent`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val usage = rp.readRoomSpaceUsage(0x91F8) ?: return
        // PLM bytes = count * 6 + 2 terminator
        assertTrue(usage.plmBytes == usage.plmCount * 6 + 2)
        // Enemy bytes = count * 16 + 2 terminator
        assertTrue(usage.enemyBytes == usage.enemyCount * 16 + 2)
    }
}

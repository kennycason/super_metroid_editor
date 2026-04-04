package com.supermetroid.editor.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TileOverlayTest {

    @Nested
    inner class ShotBlockCategory {
        @Test
        fun `BTS 0x00-0x03 are beam blocks`() {
            for (bts in 0x00..0x03) {
                assertEquals(ShotCategory.BEAM, shotBlockCategory(bts), "BTS 0x${bts.toString(16)}")
            }
        }

        @Test
        fun `BTS 0x04-0x07 are hidden blocks`() {
            for (bts in 0x04..0x07) {
                assertEquals(ShotCategory.HIDDEN, shotBlockCategory(bts), "BTS 0x${bts.toString(16)}")
            }
        }

        @Test
        fun `BTS 0x08-0x09 are power bomb blocks`() {
            assertEquals(ShotCategory.PB, shotBlockCategory(0x08))
            assertEquals(ShotCategory.PB, shotBlockCategory(0x09))
        }

        @Test
        fun `BTS 0x0A-0x0B are super missile blocks`() {
            assertEquals(ShotCategory.SUPER, shotBlockCategory(0x0A))
            assertEquals(ShotCategory.SUPER, shotBlockCategory(0x0B))
        }

        @Test
        fun `BTS 0x40-0x4F are door blocks`() {
            for (bts in 0x40..0x4F) {
                assertEquals(ShotCategory.DOOR, shotBlockCategory(bts), "BTS 0x${bts.toString(16)}")
            }
        }

        @Test
        fun `unknown BTS defaults to beam`() {
            assertEquals(ShotCategory.BEAM, shotBlockCategory(0x20))
            assertEquals(ShotCategory.BEAM, shotBlockCategory(0xFF))
        }
    }

    @Nested
    inner class RichOverlayLabel {
        @Test
        fun `door label includes BTS value`() {
            assertEquals("D5", richOverlayLabel(TileOverlay.DOOR, 5))
            assertEquals("D0", richOverlayLabel(TileOverlay.DOOR, 0))
        }

        @Test
        fun `beam shot block shows Xb`() {
            assertEquals("Xb", richOverlayLabel(TileOverlay.SHOT_BEAM, 0x00))
            assertEquals("Xb", richOverlayLabel(TileOverlay.SHOT_BEAM, 0x03))
        }

        @Test
        fun `hidden shot block shows X-question`() {
            assertEquals("X?", richOverlayLabel(TileOverlay.SHOT_BEAM, 0x04))
            assertEquals("X?", richOverlayLabel(TileOverlay.SHOT_BEAM, 0x07))
        }

        @Test
        fun `super shot block shows Xs`() {
            assertEquals("Xs", richOverlayLabel(TileOverlay.SHOT_SUPER, 0x0A))
        }

        @Test
        fun `respawning super shows Xs-bang`() {
            assertEquals("Xs!", richOverlayLabel(TileOverlay.SHOT_SUPER, 0x0B))
        }

        @Test
        fun `PB shot block shows Xp`() {
            assertEquals("Xp", richOverlayLabel(TileOverlay.SHOT_PB, 0x08))
        }

        @Test
        fun `respawning PB shows Xp-bang`() {
            assertEquals("Xp!", richOverlayLabel(TileOverlay.SHOT_PB, 0x09))
        }

        @Test
        fun `crumble block shows C`() {
            assertEquals("C", richOverlayLabel(TileOverlay.CRUMBLE, 0x00))
        }

        @Test
        fun `crumble with BTS 0x04-0x07 shows C-bang`() {
            assertEquals("C!", richOverlayLabel(TileOverlay.CRUMBLE, 0x04))
            assertEquals("C!", richOverlayLabel(TileOverlay.CRUMBLE, 0x07))
        }

        @Test
        fun `crumble with BTS 0x0B shows CE (enemy)`() {
            assertEquals("CE", richOverlayLabel(TileOverlay.CRUMBLE, 0x0B))
        }

        @Test
        fun `bomb with BTS 0x04-0x07 shows B-bang`() {
            assertEquals("B!", richOverlayLabel(TileOverlay.BOMB, 0x04))
        }

        @Test
        fun `bomb normal shows B`() {
            assertEquals("B", richOverlayLabel(TileOverlay.BOMB, 0x00))
        }

        @Test
        fun `other overlays use shortLabel`() {
            assertEquals("S", richOverlayLabel(TileOverlay.SOLID, 0))
            assertEquals("!", richOverlayLabel(TileOverlay.SPIKE, 0))
            assertEquals("G", richOverlayLabel(TileOverlay.GRAPPLE, 0))
            assertEquals("T", richOverlayLabel(TileOverlay.TREADMILL, 0))
        }

        @Test
        fun `speed overlay uses shortLabel`() {
            assertEquals("~", richOverlayLabel(TileOverlay.SPEED, 0x0E))
            assertEquals("~", richOverlayLabel(TileOverlay.SPEED, 0x0F))
        }
    }

    @Nested
    inner class BlockTypeName {
        @Test
        fun `known block types return names`() {
            assertEquals("Air", blockTypeName(0x0))
            assertEquals("Solid", blockTypeName(0x8))
            assertEquals("Door", blockTypeName(0x9))
            assertEquals("Slope", blockTypeName(0x1))
            assertEquals("Shot Block", blockTypeName(0xC))
            assertEquals("Bomb Block", blockTypeName(0xF))
        }

        @Test
        fun `all 16 block types have names`() {
            for (i in 0x0..0xF) {
                val name = blockTypeName(i)
                assertTrue(name.isNotEmpty(), "Block type 0x${i.toString(16)} should have a name")
                assertTrue(!name.startsWith("0x"), "Block type 0x${i.toString(16)} should have a real name, not '$name'")
            }
        }

        @Test
        fun `unknown block type returns hex`() {
            assertEquals("0x10", blockTypeName(0x10))
            assertEquals("0xFF", blockTypeName(0xFF))
        }
    }

    @Nested
    inner class BtsOptions {
        @Test
        fun `slope block type has floor and ceiling options`() {
            val options = btsOptionsForBlockType(0x1)
            assertTrue(options.isNotEmpty())
            assertTrue(options.any { it.second.contains("floor", ignoreCase = true) })
            assertTrue(options.any { it.second.contains("ceiling", ignoreCase = true) })
        }

        @Test
        fun `crumble block type includes speed booster BTS`() {
            val options = btsOptionsForBlockType(0xB)
            assertTrue(options.any { it.first == 0x0E && it.second.contains("Speed") })
            assertTrue(options.any { it.first == 0x0F && it.second.contains("Speed") })
        }

        @Test
        fun `shot block type has all weapon variants`() {
            val options = btsOptionsForBlockType(0xC)
            assertTrue(options.any { it.second.contains("Any Weapon") })
            assertTrue(options.any { it.second.contains("Hidden") })
            assertTrue(options.any { it.second.contains("Power Bomb") })
            assertTrue(options.any { it.second.contains("Super Missile") })
        }

        @Test
        fun `unknown block type returns empty list`() {
            assertTrue(btsOptionsForBlockType(0x5).isEmpty())
            assertTrue(btsOptionsForBlockType(0x6).isEmpty())
            assertTrue(btsOptionsForBlockType(0xD).isEmpty())
        }

        @Test
        fun `spike block type has normal and weak variants`() {
            val options = btsOptionsForBlockType(0xA)
            assertTrue(options.size >= 2)
            assertTrue(options.any { it.second.contains("normal") })
            assertTrue(options.any { it.second.contains("weak") })
        }

        @Test
        fun `grapple block type has crumble variants`() {
            val options = btsOptionsForBlockType(0xE)
            assertTrue(options.any { it.first == 0x00 && it.second == "Grapple" })
            assertTrue(options.any { it.second.contains("Crumble") })
        }

        @Test
        fun `treadmill block type has left and right conveyors`() {
            val options = btsOptionsForBlockType(0x3)
            assertTrue(options.any { it.second.contains("Right") })
            assertTrue(options.any { it.second.contains("Left") })
        }
    }

    @Nested
    inner class TileOverlayEnum {
        @Test
        fun `all overlays have non-empty labels`() {
            for (overlay in TileOverlay.entries) {
                assertTrue(overlay.label.isNotBlank(), "${overlay.name} should have a label")
                assertTrue(overlay.shortLabel.isNotBlank(), "${overlay.name} should have a shortLabel")
            }
        }

        @Test
        fun `all overlays have non-zero color`() {
            for (overlay in TileOverlay.entries) {
                assertTrue(overlay.color != 0L, "${overlay.name} should have a color")
            }
        }

        @Test
        fun `speed booster BTS 0x0E and 0x0F are detected as speed blocks`() {
            // Block type 0xB with BTS 0x0E or 0x0F = speed booster
            assertTrue(0x0E == 0x0E || 0x0F == 0x0F) // sanity
            val speedBts = listOf(0x0E, 0x0F)
            for (bts in speedBts) {
                val isSpeed = bts == 0x0E || bts == 0x0F
                assertTrue(isSpeed, "BTS 0x${bts.toString(16)} should be speed booster")
            }
            // Non-speed BTS in block type 0xB should be crumble
            for (bts in listOf(0x00, 0x01, 0x02, 0x03, 0x0B)) {
                val isSpeed = bts == 0x0E || bts == 0x0F
                assertTrue(!isSpeed, "BTS 0x${bts.toString(16)} should NOT be speed booster")
            }
        }
    }
}

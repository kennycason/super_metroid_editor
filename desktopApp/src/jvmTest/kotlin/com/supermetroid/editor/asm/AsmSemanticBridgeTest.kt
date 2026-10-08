package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsmSemanticBridgeTest {
    @Test
    fun `room headers and level assets open the room editor`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val landingRoom = AsmEditorTarget.Room(0x91F8)

        assertTrue(
            AsmSemanticBridge.linksFor(0x8F91F8, romParser = parser).any { it.target == landingRoom },
            "The Landing Site source header should link back to the room editor",
        )

        val level = AsmAssetManifest.loadBundled().single { it.path == "LevelData_LandingSite.bin" }
        assertTrue(
            AsmSemanticBridge.linksFor(level.snesAddress, level.path, parser).any { it.target == landingRoom },
            "The extracted Landing Site layout should link back to the room editor",
        )
    }

    @Test
    fun `tileset assets open the matching tileset surface`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val asset = AsmAssetManifest.loadBundled().single { it.path == "Tiles_1A_Kraid.bin" }
        val links = AsmSemanticBridge.linksFor(asset.snesAddress, asset.path, parser)

        assertTrue(
            links.any { it.target == AsmEditorTarget.Tileset(0x1A) },
            "Kraid's area graphics should link to tileset 1A",
        )
    }

    @Test
    fun `player and composite boss assets open sprite editors`() {
        val index = AsmSemanticIndex(emptyMap())

        assertTrue(
            index.linksFor(0x9D8000, "SamusTiles_Top_Set8_Entry0.bin")
                .any { it.target == AsmEditorTarget.Sprite(null) },
        )
        assertTrue(
            index.linksFor(0xB0C800, "Tiles_Draygon.bin")
                .any { it.target == AsmEditorTarget.Sprite(0xDE3F) },
        )
    }

    @Test
    fun `music assets open their decoded song set`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val asset = AsmAssetManifest.loadBundled().single { it.path == "Music_UpperCrateria.bin" }
        val links = AsmSemanticBridge.linksFor(asset.snesAddress, asset.path, parser)

        assertTrue(
            links.any { it.target == AsmEditorTarget.Sound(0x0C) },
            "Upper Crateria's extracted transfer data should open song set 0C",
        )
    }
}

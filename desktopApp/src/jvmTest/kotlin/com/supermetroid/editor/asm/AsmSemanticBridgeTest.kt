package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.TestRomHelper
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.TextData
import com.supermetroid.editor.rom.parseLibraryBackground
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsmSemanticBridgeTest {
    @Test
    fun `beam pointers and records return to their normal or charged damage editor`() {
        val parser = TestRomHelper.requireRomParser()
        val index = AsmSemanticBridge.buildIndex(parser)
        for (beam in com.supermetroid.editor.ui.ALL_BEAMS) for (charged in listOf(false, true)) {
            val binding = (if (charged) 0x9383D9 else 0x9383C1) + BEAM_POINTER_KEYS.indexOf(beam.key) * 2
            val key = if (charged) beam.chargedKey else beam.key
            val address = 0x930000 or parser.readUInt16At(parser.snesToPc(binding))
            for (source in listOf(binding, address)) assertTrue(index.linksFor(source).any { it.target == AsmEditorTarget.BeamDamage(key) })
        }
    }

    @Test
    fun `enemy tuning tables and native binding words return to the correct enemy tab`() {
        val parser = TestRomHelper.requireRomParser()
        val address = 0xA0DCFF
        val pc = parser.snesToPc(address)
        val drop = parser.readUInt16At(pc + 0x3A)
        val vulnerabilities = parser.readUInt16At(pc + 0x3C)
        val dropTarget = AsmEditorTarget.EnemyTuning(0xDCFF, AsmEnemyTuningSection.DROPS)
        val vulnTarget = AsmEditorTarget.EnemyTuning(0xDCFF, AsmEnemyTuningSection.VULNERABILITIES)
        for (source in listOf(address + 0x3A, 0xB40000 or drop))
            assertTrue(AsmSemanticBridge.linksFor(source, romParser = parser).any { it.target == dropTarget })
        for (source in listOf(address + 0x3C, 0xB40000 or vulnerabilities))
            assertTrue(AsmSemanticBridge.linksFor(source, romParser = parser).any { it.target == vulnTarget })
        assertTrue(AsmSemanticBridge.linksFor(0xB40000 or vulnerabilities, romParser = parser)
            .any { it.target == AsmEditorTarget.EnemyTuning(0xDC3F, AsmEnemyTuningSection.VULNERABILITIES) })
    }

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

    @Test
    fun `text and pause map data open their exact visual surfaces`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val text = TextData.readAllText(parser.getRomData()).first { it.writable }

        assertTrue(
            AsmSemanticBridge.linksFor(text.snesAddress, romParser = parser)
                .any { it.target == AsmEditorTarget.Text(text.id) },
            "An editable text address should select that text entry",
        )

        val area = 0
        val mapAddress = parser.readMinimapTilemapAddress(area)
        assertTrue(
            AsmSemanticBridge.linksFor(mapAddress, romParser = parser)
                .any { it.target == AsmEditorTarget.Map(area) },
            "A pause-map tilemap should open its exact area",
        )
        assertTrue(
            AsmSemanticIndex(emptyMap()).linksFor(
                parser.readMinimapTileGraphicsAddress(),
                "Tiles_PauseScreen_BG1_BG2.bin",
            ).any { it.target == AsmEditorTarget.Map(null) },
            "The shared pause-map graphics should open the map editor",
        )
    }

    @Test
    fun `room runtime resources and background payloads return to owning rooms`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val candidate = parser.roomCatalog.rooms.firstNotNullOfOrNull { info ->
            val roomId = info.getRoomIdAsInt()
            val state = parser.parseRoomStatesWithData(roomId).firstOrNull {
                it.fxPtr in 0x8000..0xFFFE && it.bgDataPtr in 0x8000..0xFFFE
            } ?: return@firstNotNullOfOrNull null
            val backgroundSource = parser.parseLibraryBackground(state.bgDataPtr)
                ?.commands
                ?.firstNotNullOfOrNull { it.sourceAddress }
                ?: return@firstNotNullOfOrNull null
            Triple(roomId, state, backgroundSource)
        } ?: return
        val (roomId, state, backgroundSource) = candidate
        val roomTarget = AsmEditorTarget.Room(roomId)

        assertTrue(
            AsmSemanticBridge.linksFor(RomConstants.BANK_FX or state.fxPtr, romParser = parser)
                .any { it.target == roomTarget },
            "FX data should return to every room state that consumes it",
        )
        assertTrue(
            AsmSemanticBridge.linksFor(backgroundSource, romParser = parser)
                .any { it.target == roomTarget },
            "A library-background payload should return to its consuming room",
        )
    }

    @Test
    fun `door definitions return to their source room`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val candidate = parser.roomCatalog.rooms.firstNotNullOfOrNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@firstNotNullOfOrNull null
            val door = parser.parseDoorList(room.doorOut).firstOrNull() ?: return@firstNotNullOfOrNull null
            roomId to door
        } ?: return
        val (roomId, door) = candidate

        assertTrue(
            AsmSemanticBridge.linksFor(RomConstants.BANK_FX or door.doorDefPtr, romParser = parser)
                .any { it.target == AsmEditorTarget.Room(roomId) },
            "A DoorDef should return to the room whose door list owns it",
        )
    }
}

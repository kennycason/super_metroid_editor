package com.supermetroid.editor.ui

import com.supermetroid.editor.data.DoorChange
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DoorDependentBgTransferTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `Landing Site bg data has door-dependent BG2 transfers`() {
        val romParser = TestRomHelper.loadRomParser() ?: return

        val transfers = parseDoorDependentBgTransfers(romParser, 0xB76A)

        assertEquals(6, transfers.size)
        assertEquals(DoorDependentBgTransfer(0x8946, 0x8AC180, 0x4800, 0x0800), transfers.first())
    }

    @Test
    fun `new Landing Site entrance clones matching vanilla door-dependent transfer`() {
        val romParser = TestRomHelper.loadRomParser() ?: return
        val vanillaTopLeftDoor = readDoorEntryAtDoorDefPtr(romParser, 0x8946)
            ?: error("Expected vanilla Landing Site top-left entrance")
        val newDoor = vanillaTopLeftDoor.copy(
            bitflag = vanillaTopLeftDoor.bitflag or 0x0040,
            entryCode = 0xEB01,
            doorDefPtr = 0xAA80,
        )

        val template = findMatchingDoorDependentBgTransfer(romParser, 0xB76A, newDoor)
            ?: error("Expected matching door-dependent BG transfer")
        val cloned = buildBgDataWithClonedDoorDependentTransfer(
            romParser,
            bgDataPtr = 0xB76A,
            newDoorDefPtr = newDoor.doorDefPtr,
            template = template,
        )!!.map { it.toInt() and 0xFF }

        assertEquals(0x8946, template.doorDefPtr)
        assertEquals(
            listOf(
                0x0E, 0x00,
                0x80, 0xAA,
                0x80, 0xC1, 0x8A,
                0x00, 0x48,
                0x00, 0x08,
                0x00, 0x00,
            ),
            cloned.takeLast(13),
        )
    }

    @Test
    fun `existing door-dependent BG transfer is not cloned again`() {
        val romParser = TestRomHelper.loadRomParser() ?: return
        val vanillaTopLeftDoor = readDoorEntryAtDoorDefPtr(romParser, 0x8946)
            ?: error("Expected vanilla Landing Site top-left entrance")

        assertNull(findMatchingDoorDependentBgTransfer(romParser, 0xB76A, vanillaTopLeftDoor))
    }

    @Test
    fun `export clones Landing Site door-dependent BG transfer for repointed Mother Brain door`() {
        val romBytes = TestRomHelper.loadRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        romBytes!!

        val inputRom = File(tempDir, "SuperMetroid.smc")
        inputRom.writeBytes(romBytes)
        val parser = RomParser(inputRom.readBytes())

        val state = EditorState()
        state.testMode = true
        state.initForRom(inputRom.absolutePath)
        state.project.getOrCreateRoom(0xDD58).doorChanges.add(
            DoorChange(
                doorIndex = 0,
                destRoomPtr = 0x91F8,
                bitflag = 0x0440,
                doorCapCode = 0x2601,
                screenX = 0,
                screenY = 2,
                distFromDoor = 0x8000,
                entryCode = 0xB997,
            )
        )

        val exportedPath = state.exportToRom(parser) ?: error("Expected export path")
        val exportedParser = RomParser(File(exportedPath).readBytes())
        val landingSite = exportedParser.readRoomHeader(0x91F8)
            ?: error("Expected Landing Site room")
        val transfers = parseDoorDependentBgTransfers(exportedParser, landingSite.bgDataPtr)
        val motherBrainDoor = exportedParser.parseDoorEntry(
            exportedParser.readRoomHeader(0xDD58)!!.doorOut,
            0,
        ) ?: error("Expected exported Mother Brain door")

        assertTrue(
            transfers.any {
                it.doorDefPtr == 0xAA80 &&
                    it.srcAddr == 0x8AC180 &&
                    it.vramDst == 0x4800 &&
                    it.size == 0x0800
            },
            "Landing Site bgData should clone vanilla door \$8946 transfer for new door \$AA80",
        )
        assertTrue(motherBrainDoor.entryCode != 0xB997, "door should still get stale-BG2 cleanup ASM")
    }
}

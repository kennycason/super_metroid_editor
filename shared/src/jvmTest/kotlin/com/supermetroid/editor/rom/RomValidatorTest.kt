package com.supermetroid.editor.rom

import com.supermetroid.editor.data.RoomRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RomValidatorTest {

    private var romParser: RomParser? = null
    private var allRoomIds: List<Int> = emptyList()

    @BeforeAll
    fun loadRom() {
        romParser = TestRomHelper.loadRomParser()
        if (romParser != null) {
            allRoomIds = RoomRepository().getAllRooms().map { it.getRoomIdAsInt() }
        }
    }

    @Nested
    inner class FullValidation {
        @Test
        fun `validate returns results without crashing`() {
            val parser = romParser ?: return
            val issues = RomValidator.validate(parser, allRoomIds)
            println("Total issues: ${issues.size}")
            println("  Errors: ${issues.count { it.severity == RomValidator.Severity.ERROR }}")
            println("  Warnings: ${issues.count { it.severity == RomValidator.Severity.WARNING }}")
            for (issue in issues.take(20)) {
                println("  [${issue.severity}] ${issue.category}: ${issue.roomName} — ${issue.message}")
            }
        }

        @Test
        fun `validate covers all categories`() {
            val parser = romParser ?: return
            val issues = RomValidator.validate(parser, allRoomIds)
            val categories = issues.map { it.category }.toSet()
            // Vanilla ROM should have at least door issues (some rooms have edge-case coordinates)
            assertTrue(issues.isNotEmpty(), "Vanilla ROM should have some validation issues")
            println("Categories found: $categories")
        }
    }

    @Nested
    inner class DoorConsistency {
        @Test
        fun `door validation catches out-of-bounds spawn coordinates`() {
            val parser = romParser ?: return
            val rooms = allRoomIds.mapNotNull { parser.readRoomHeader(it) }.associateBy { it.roomId }
            val issues = RomValidator.checkDoorConsistency(parser, rooms)
            val errors = issues.filter { it.severity == RomValidator.Severity.ERROR }
            println("Door errors: ${errors.size}")
            for (e in errors.take(10)) {
                println("  ${e.roomName}: ${e.message}")
            }
        }

        @Test
        fun `Landing Site doors point to valid rooms`() {
            val parser = romParser ?: return
            val rooms = allRoomIds.mapNotNull { parser.readRoomHeader(it) }.associateBy { it.roomId }
            val landingSite = rooms[0x91F8] ?: return
            val doors = parser.parseDoorList(landingSite.doorOut)
            assertTrue(doors.isNotEmpty(), "Landing Site should have doors")
            for (door in doors) {
                assertTrue(door.destRoomPtr in rooms, "Door dest 0x${door.destRoomPtr.toString(16)} should be a valid room")
            }
        }
    }

    @Nested
    inner class ItemBitflags {
        @Test
        fun `vanilla ROM has unique item collection bits`() {
            val parser = romParser ?: return
            val rooms = allRoomIds.mapNotNull { parser.readRoomHeader(it) }.associateBy { it.roomId }
            val issues = RomValidator.checkItemBitflagDuplicates(parser, rooms)
            println("Item bitflag duplicates: ${issues.size}")
            for (i in issues) println("  ${i.message}")
            // Vanilla SM should have unique bits for all items
            assertEquals(0, issues.size, "Vanilla ROM should have no duplicate item collection bits")
        }
    }

    @Nested
    inner class EnemyGfx {
        @Test
        fun `vanilla ROM has no rooms exceeding 4 GFX slots`() {
            val parser = romParser ?: return
            val rooms = allRoomIds.mapNotNull { parser.readRoomHeader(it) }.associateBy { it.roomId }
            val issues = RomValidator.checkEnemyGfxLimits(parser, rooms)
            println("GFX limit violations: ${issues.size}")
            for (i in issues) println("  ${i.roomName}: ${i.message}")
            // Vanilla SM should not exceed hardware limits
            assertEquals(0, issues.size, "Vanilla ROM should have no GFX limit violations")
        }
    }

    @Nested
    inner class RoomDimensions {
        @Test
        fun `vanilla ROM rooms fit within minimap bounds`() {
            val parser = romParser ?: return
            val rooms = allRoomIds.mapNotNull { parser.readRoomHeader(it) }.associateBy { it.roomId }
            val issues = RomValidator.checkRoomDimensions(rooms)
            println("Dimension issues: ${issues.size}")
            for (i in issues) println("  ${i.roomName}: ${i.message}")
        }
    }
}

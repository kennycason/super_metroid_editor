package com.supermetroid.editor.rom

import com.supermetroid.editor.data.RoomRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Verifies boss enemy IDs, HP/damage values, and boss-defeated flag bits
 * against the vanilla ROM.
 */
class BossStatsTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun readU16(rom: ByteArray, pc: Int): Int =
        (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `boss species IDs point to valid enemy stat blocks`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()

        val bossIds = listOf(
            0xE2BF to "Kraid",
            0xE2FF to "Kraid (upper body)",
            0xE33F to "Kraid (belly spike 1)",
            0xE37F to "Kraid (belly spike 2)",
            0xE3BF to "Kraid (belly spike 3)",
            0xE3FF to "Kraid (flying claw 1)",
            0xE43F to "Kraid (flying claw 2)",
            0xE47F to "Kraid (flying claw 3)",
            0xE4BF to "Phantoon",
            0xE4FF to "Phantoon Flame 1",
            0xE53F to "Phantoon Flame 2",
            0xE57F to "Phantoon Flame 3",
            0xE17F to "Ridley",
            0xDE3F to "Draygon Body",
            0xDE7F to "Draygon Eye",
            0xDEBF to "Draygon Tail",
            0xDEFF to "Draygon Arms",
            0xEC3F to "Mother Brain Phase 1",
            0xEC7F to "Mother Brain Phase 2",
            0xDF3F to "Spore Spawn",
            0xDDBF to "Crocomire",
            0xF293 to "Botwoon",
            0xEF7F to "Golden Torizo",
            0xEEFF to "Bomb Torizo",
        )

        for ((id, name) in bossIds) {
            val snes = 0xA00000 or id
            val pc = parser.snesToPc(snes)
            assertTrue(pc + 64 <= rom.size, "$name (0x${id.toString(16)}): stat block out of bounds")
            val hp = readU16(rom, pc + 4)
            val dmg = readU16(rom, pc + 6)
            println("$name: species=0x${id.toString(16)}, HP=$hp, Damage=$dmg")
            assertTrue(hp > 0 || name.contains("Mother Brain Phase 1"),
                "$name should have non-zero HP (got $hp)")
        }
    }

    @Test
    fun `vanilla boss HP values match known defaults`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()

        fun hp(id: Int): Int = readU16(rom, parser.snesToPc(0xA00000 or id) + 4)
        fun dmg(id: Int): Int = readU16(rom, parser.snesToPc(0xA00000 or id) + 6)

        println("Kraid HP=${hp(0xE2BF)}, Damage=${dmg(0xE2BF)}")
        println("Phantoon HP=${hp(0xE4BF)}, Damage=${dmg(0xE4BF)}")
        println("Ridley HP=${hp(0xE17F)}, Damage=${dmg(0xE17F)}")
        println("Draygon Body HP=${hp(0xDE3F)}, Damage=${dmg(0xDE3F)}")
        println("Mother Brain P1 HP=${hp(0xEC3F)}, Damage=${dmg(0xEC3F)}")
        println("Mother Brain P2 HP=${hp(0xEC7F)}, Damage=${dmg(0xEC7F)}")
        println("Spore Spawn HP=${hp(0xDF3F)}, Damage=${dmg(0xDF3F)}")
        println("Crocomire HP=${hp(0xDDBF)}, Damage=${dmg(0xDDBF)}")
        println("Botwoon HP=${hp(0xF293)}, Damage=${dmg(0xF293)}")
        println("Golden Torizo HP=${hp(0xEF7F)}, Damage=${dmg(0xEF7F)}")
        println("Bomb Torizo HP=${hp(0xEEFF)}, Damage=${dmg(0xEEFF)}")

        // Verify correct vanilla values
        assertEquals(1000, hp(0xE2BF), "Kraid vanilla HP should be 1000 (species 0xE2BF, not 0xD2BF which is Squeept)")
        assertEquals(2500, hp(0xE4BF), "Phantoon vanilla HP should be 2500")
        assertEquals(18000, hp(0xE17F), "Ridley vanilla HP should be 18000")
        assertEquals(6000, hp(0xDE3F), "Draygon vanilla HP should be 6000")
    }

    @Test
    fun `old IPS boss patches targeted unused ROM space - confirms they were broken`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()

        // These offsets are where the old broken IPS patches wrote.
        // They are in unused $FF-filled space (bank $B5), confirming the patches had no effect.
        val oldOffset = 0x1AFE0C
        val value = rom[oldOffset].toInt() and 0xFF
        assertEquals(0xFF, value,
            "PC 0x1AFE0C should be 0xFF (unused space), confirming old IPS was wrong")
        println("Confirmed: old IPS boss patches wrote to unused ROM space (0xFF at PC 0x1AFE0C)")
    }

    @Test
    fun `per-frame hook at 82_896E contains expected JSL instruction`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()

        // Verify the hook target at $82:896E is JSL $8289EF (22 EF 89 82)
        val hookPc = 0x1096E
        assertEquals(0x22, rom[hookPc].toInt() and 0xFF, "Should be JSL opcode")
        assertEquals(0xEF, rom[hookPc + 1].toInt() and 0xFF)
        assertEquals(0x89, rom[hookPc + 2].toInt() and 0xFF)
        assertEquals(0x82, rom[hookPc + 3].toInt() and 0xFF)
        println("Confirmed: $82:896E contains JSL \$8289EF — safe to hook")
    }

    @Test
    fun `find E629 boss condition args for all boss rooms`() {
        val parser = loadTestRom() ?: return
        val rooms = RoomRepository().getAllRooms()

        val bossConditions = mutableListOf<Triple<String, Int, Int>>()
        for (room in rooms) {
            val roomId = room.getRoomIdAsInt()
            val states = parser.parseRoomStates(roomId)
            for (state in states) {
                if (state.conditionCode == 0xE629) {
                    val header = parser.readRoomHeader(roomId) ?: continue
                    val area = header.area
                    bossConditions.add(Triple(
                        "Room 0x${roomId.toString(16)} area=$area",
                        state.conditionArg,
                        area
                    ))
                    println("Room 0x${roomId.toString(16)} (area $area): E629 arg=0x${state.conditionArg.toString(16)}")
                }
            }
        }

        assertTrue(bossConditions.isNotEmpty(), "Should find E629 boss conditions in ROM")

        val area1Conditions = bossConditions.filter { it.third == 1 }
        println("\nBrinstar (area 1) boss conditions:")
        for ((desc, arg, _) in area1Conditions) {
            println("  $desc: boss bit mask = 0x${arg.toString(16)}")
        }

        val area2Conditions = bossConditions.filter { it.third == 2 }
        println("\nNorfair (area 2) boss conditions:")
        for ((desc, arg, _) in area2Conditions) {
            println("  $desc: boss bit mask = 0x${arg.toString(16)}")
        }

        val area3Conditions = bossConditions.filter { it.third == 3 }
        println("\nWrecked Ship (area 3) boss conditions:")
        for ((desc, arg, _) in area3Conditions) {
            println("  $desc: boss bit mask = 0x${arg.toString(16)}")
        }

        val area4Conditions = bossConditions.filter { it.third == 4 }
        println("\nMaridia (area 4) boss conditions:")
        for ((desc, arg, _) in area4Conditions) {
            println("  $desc: boss bit mask = 0x${arg.toString(16)}")
        }
    }

    @Test
    fun `Kraid sub-enemy damage values are readable`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()

        val subEnemies = listOf(
            0xE33F to "Kraid Belly Spike 1",
            0xE37F to "Kraid Belly Spike 2",
            0xE3BF to "Kraid Belly Spike 3",
            0xE3FF to "Kraid Flying Claw 1",
            0xE43F to "Kraid Flying Claw 2",
            0xE47F to "Kraid Flying Claw 3",
        )

        for ((id, name) in subEnemies) {
            val pc = parser.snesToPc(0xA00000 or id)
            val hp = readU16(rom, pc + 4)
            val dmg = readU16(rom, pc + 6)
            val width = readU16(rom, pc + 8)
            val height = readU16(rom, pc + 10)
            println("$name: HP=$hp, Damage=$dmg, Size=${width}x${height}")
        }
    }

    @Test
    fun `common enemy HP and damage values are readable`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()

        val enemies = listOf(
            0xDCFF to "Zoomer",
            0xD91F to "Geemer",
            0xD93F to "Sidehopper",
            0xD87F to "Reo",
            0xD47F to "Ripper",
            0xDFBF to "Kihunter",
            0xF353 to "Space Pirate",
            0xD23F to "Rinka",
        )

        for ((id, name) in enemies) {
            val pc = parser.snesToPc(0xA00000 or id)
            val hp = readU16(rom, pc + 4)
            val dmg = readU16(rom, pc + 6)
            println("$name: HP=$hp, Damage=$dmg")
            assertTrue(hp >= 0, "$name HP should be non-negative")
        }
    }
}

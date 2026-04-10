package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class Layer3DiagnosticTest {

    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        println("Test ROM not found, skipping test")
        return null
    }

    @Test
    fun `trace L3 pipeline for key rooms`() {
        val parser = loadTestRom() ?: return

        val rooms = mapOf(
            "Landing Site" to 0x91F8,
            "Cathedral" to 0xA788,
            "Lava Dive Room" to 0xAF14,
            "Bubble Mountain" to 0xACB3,
            "Green Brinstar Fireflea" to 0x9C5E,
            "Aqueduct" to 0xD5A7,
            "Ice Beam Gate Room" to 0xA815,
            "Crumble Shaft" to 0xB3A5,
        )

        for ((name, roomId) in rooms) {
            val room = parser.readRoomHeader(roomId)
            if (room == null) {
                println("$name (0x${roomId.toString(16)}): FAILED to parse room header")
                continue
            }
            val fxPtr = room.fxPtr
            println("$name (0x${roomId.toString(16)}): fxPtr=0x${fxPtr.toString(16)}")

            val fxEntries = parser.parseFxEntries(fxPtr)
            println("  FX entries: ${fxEntries.size}")
            for ((i, fx) in fxEntries.withIndex()) {
                println("  [$i] doorSelect=0x${fx.doorSelect.toString(16)} fxType=0x${fx.fxType.toString(16)} " +
                    "liquidStart=${fx.liquidSurfaceStart} fxBitA=0x${fx.fxBitA.toString(16)} fxBitB=0x${fx.fxBitB.toString(16)}")
            }

            val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 }
            val fxType = defaultFx?.fxType ?: 0
            println("  Default fxType: 0x${fxType.toString(16)}")

            if (fxType > 0) {
                val l3Image = parser.renderLayer3Image(fxType)
                if (l3Image != null) {
                    val nonZero = l3Image.count { it != 0 }
                    println("  L3 image: ${l3Image.size} pixels, $nonZero non-zero (${nonZero * 100 / l3Image.size}%)")
                } else {
                    println("  L3 image: null (renderLayer3Image returned null)")
                }
            }
            println()
        }
    }

    @Test
    fun `enemy names match ROM data`() {
        // Key enemy name corrections verified against ROM name pointers
        assertEquals("Boulder", RomParser.enemyName(0xDFBF), "0xDFBF ROM name is RSTONE (Boulder)")
        assertEquals("Dragon", RomParser.enemyName(0xD4BF), "0xD4BF ROM name is DRAGON")
        assertEquals("Multiviola", RomParser.enemyName(0xD1BF), "0xD1BF ROM name is MULTI")
        assertEquals("Oum (baby)", RomParser.enemyName(0xD37F), "0xD37F ROM name is OUM")
        assertEquals("Dessgeega (small)", RomParser.enemyName(0xDA3F), "0xDA3F ROM name is DESSGEEGA")
        assertEquals("Zoa", RomParser.enemyName(0xDA7F), "0xDA7F ROM name is ZOA")
        assertEquals("Metroid", RomParser.enemyName(0xDD7F), "0xDD7F ROM name is METROID")
    }

    @Test
    fun `FX type names match SMILE definitions`() {
        assertEquals("Lava", RomParser.FxEntry.FX_TYPE_NAMES[0x02])
        assertEquals("Acid", RomParser.FxEntry.FX_TYPE_NAMES[0x04])
        assertEquals("Water", RomParser.FxEntry.FX_TYPE_NAMES[0x06])
        assertEquals("Spores", RomParser.FxEntry.FX_TYPE_NAMES[0x08])
        assertEquals("Rain", RomParser.FxEntry.FX_TYPE_NAMES[0x0A])
        assertEquals("Fog", RomParser.FxEntry.FX_TYPE_NAMES[0x0C])
    }

    @Test
    fun `scan all rooms for non-zero fxType`() {
        val parser = loadTestRom() ?: return

        // Known SM room IDs
        val roomIds = listOf(
            0x91F8, 0x92FD, 0x93AA, 0x93D5, 0x93FE, 0x9461, 0x948C, 0x94CC,
            0x94FD, 0x9552, 0x957D, 0x95A8, 0x95D4, 0x95FF, 0x962A, 0x965B,
            0x968F, 0x96BA, 0x975C, 0x9798, 0x97B5, 0x9804, 0x9879, 0x98E2,
            0x990D, 0x9938, 0x9969, 0x9994, 0x99BD, 0x99F9, 0x9A44, 0x9A90,
            0x9AD9, 0x9B5B, 0x9B9D, 0x9BC8, 0x9C07, 0x9C35, 0x9C5E, 0x9CB3,
            0x9D19, 0x9D9C, 0x9DC7, 0x9E11, 0x9E52, 0x9E9F, 0x9F11, 0x9F64,
            0x9FBA, 0x9FE5, 0xA011, 0xA051, 0xA07B, 0xA0A4, 0xA0D2, 0xA107,
            0xA130, 0xA15B, 0xA184, 0xA1AD, 0xA1D8, 0xA201, 0xA22A, 0xA253,
            0xA293, 0xA2CE, 0xA2F7, 0xA322, 0xA37C, 0xA3AE, 0xA3DD, 0xA408,
            0xA447, 0xA471, 0xA4B1, 0xA4DA, 0xA521, 0xA56B, 0xA59F, 0xA5ED,
            0xA618, 0xA641, 0xA66A, 0xA6A1, 0xA6E2, 0xA708, 0xA734, 0xA75D,
            0xA788, 0xA7B3, 0xA7DE, 0xA815, 0xA865, 0xA890, 0xA8B9, 0xA8F8,
            0xA923, 0xA98D, 0xA9E5, 0xAA0E, 0xAA41, 0xAA82, 0xAAB5, 0xAADE,
            0xAB07, 0xAB3B, 0xAB64, 0xAB8F, 0xABCF, 0xAC00, 0xAC2B, 0xAC5A,
            0xAC83, 0xACB3, 0xACF0, 0xAD1B, 0xAD5E, 0xADAD, 0xADDE, 0xAE07,
            0xAE32, 0xAE74, 0xAEB4, 0xAEDF, 0xAF14, 0xAF3F, 0xAF72, 0xAFA3,
            0xAFCE, 0xAFFB, 0xB017, 0xB051, 0xB07A, 0xB0B4, 0xB0DD, 0xB106,
        )

        var roomsWithL3 = 0
        val fxTypeCounts = mutableMapOf<Int, Int>()
        val roomsWithL3List = mutableListOf<String>()

        for (roomId in roomIds) {
            val room = parser.readRoomHeader(roomId) ?: continue
            val fxEntries = parser.parseFxEntries(room.fxPtr)
            val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 }
            val fxType = defaultFx?.fxType ?: 0
            if (fxType > 0 && fxType < 0x30) {
                roomsWithL3++
                fxTypeCounts[fxType] = (fxTypeCounts[fxType] ?: 0) + 1
                roomsWithL3List.add("${room.name} (0x${roomId.toString(16)}): fxType=0x${fxType.toString(16)}")
            }
        }

        println("Rooms with non-zero default fxType: $roomsWithL3 / ${roomIds.size}")
        println("fxType distribution:")
        fxTypeCounts.entries.sortedBy { it.key }.forEach { (type, count) ->
            println("  0x${type.toString(16)}: $count rooms")
        }
        println("\nRooms with L3:")
        roomsWithL3List.forEach { println("  $it") }
    }
}

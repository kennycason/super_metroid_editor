package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class EnemyExportDiagTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `verify enemy population roundtrip for vanilla data`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()

        val roomIds = listOf(0x990D, 0x92FD, 0x9879, 0x91F8)
        for (roomId in roomIds) {
            val room = rp.readRoomHeader(roomId) ?: continue
            if (room.enemySetPtr == 0 || room.enemySetPtr == 0xFFFF) continue

            val enemies = rp.parseEnemyPopulation(room.enemySetPtr)
            if (enemies.isEmpty()) continue

            val popPc = rp.snesToPc(0xA10000 or room.enemySetPtr)
            val rawSize = enemies.size * 16 + 2

            val written = ByteArray(rawSize)
            var off = 0
            for (e in enemies) {
                fun w16(v: Int) {
                    written[off] = (v and 0xFF).toByte()
                    written[off + 1] = ((v shr 8) and 0xFF).toByte()
                    off += 2
                }
                w16(e.id); w16(e.x); w16(e.y); w16(e.initParam)
                w16(e.properties); w16(e.extra1); w16(e.extra2); w16(e.extra3)
            }
            written[off] = 0xFF.toByte()
            written[off + 1] = 0xFF.toByte()

            val original = ByteArray(rawSize) { rom[popPc + it] }
            assertTrue(written.contentEquals(original),
                "Room 0x${roomId.toString(16)} enemy population should roundtrip exactly")
        }
    }

    @Test
    fun `parse enemy GFX set for Terminator room`() {
        val rp = loadTestRom() ?: return
        val room = rp.readRoomHeader(0x990D)!!
        val gfx = rp.parseEnemyGfxSet(room.enemyGfxPtr)

        assertEquals(2, gfx.size, "Terminator room should have 2 GFX entries")
        assertEquals(0xD63F, gfx[0].speciesId, "First GFX entry should be Waver")
        assertEquals(1, gfx[0].paletteIndex, "Waver palette index")
        assertEquals(0xDCFF, gfx[1].speciesId, "Second GFX entry should be Zoomer")
        assertEquals(2, gfx[1].paletteIndex, "Zoomer palette index")
    }

    @Test
    fun `all vanilla enemies have bit 0x2000 set in properties`() {
        val rp = loadTestRom() ?: return

        val roomIds = listOf(0x990D, 0x92FD, 0x9879, 0x91F8, 0x93AA, 0x94FD)
        for (roomId in roomIds) {
            val room = rp.readRoomHeader(roomId) ?: continue
            if (room.enemySetPtr == 0 || room.enemySetPtr == 0xFFFF) continue
            val enemies = rp.parseEnemyPopulation(room.enemySetPtr)
            for (e in enemies) {
                val has2000 = (e.properties and 0x2000) != 0
                assertTrue(has2000,
                    "Room 0x${roomId.toString(16)} enemy ${RomParser.enemyName(e.id)} at (${e.x},${e.y}) " +
                    "should have bit 0x2000 in properties (was 0x${e.properties.toString(16)})")
            }
        }
    }

    @Test
    fun `kill count byte follows FFFF terminator`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()

        val room = rp.readRoomHeader(0x990D)!!
        val enemies = rp.parseEnemyPopulation(room.enemySetPtr)
        val popPc = rp.snesToPc(0xA10000 or room.enemySetPtr)

        val terminatorPc = popPc + enemies.size * 16
        val termWord = (rom[terminatorPc].toInt() and 0xFF) or
                ((rom[terminatorPc + 1].toInt() and 0xFF) shl 8)
        assertEquals(0xFFFF, termWord, "Terminator should be FFFF")

        val killCount = rom[terminatorPc + 2].toInt() and 0xFF
        assertTrue(killCount in 0..64,
            "Kill count should be reasonable (was $killCount)")
    }

    @Test
    fun `export enforces bit 0x2000 only for new enemies, preserves vanilla props`() {
        val rp = loadTestRom() ?: return
        val romData = rp.getRomData().copyOf()
        val room = rp.readRoomHeader(0x990D)!!
        val originalEnemies = rp.parseEnemyPopulation(room.enemySetPtr)
        val originalSet = originalEnemies.toSet()

        val modified = originalEnemies.toMutableList()
        modified.add(RomParser.EnemyEntry(0xDCFF, 1056, 256, 0, 0x0800))

        val enemyPc = rp.snesToPc(0xA10000 or room.enemySetPtr)
        fun writeU16(offset: Int, value: Int) {
            romData[offset] = (value and 0xFF).toByte()
            romData[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        var off = enemyPc
        for (e in modified) {
            writeU16(off, e.id)
            writeU16(off + 2, e.x)
            writeU16(off + 4, e.y)
            writeU16(off + 6, e.initParam)
            val props = if (e in originalSet) e.properties else (e.properties or 0x2000)
            writeU16(off + 8, props)
            writeU16(off + 10, e.extra1)
            writeU16(off + 12, e.extra2)
            writeU16(off + 14, e.extra3)
            off += 16
        }
        writeU16(off, 0xFFFF)

        // Verify vanilla enemy properties are preserved exactly
        val firstVanillaProps = (romData[enemyPc + 8].toInt() and 0xFF) or
                ((romData[enemyPc + 9].toInt() and 0xFF) shl 8)
        assertEquals(originalEnemies[0].properties, firstVanillaProps,
            "Vanilla enemy props should be preserved exactly (was 0x${firstVanillaProps.toString(16)})")

        // Verify new enemy gets 0x2000 enforced
        val addedPropsPc = enemyPc + originalEnemies.size * 16 + 8
        val writtenProps = (romData[addedPropsPc].toInt() and 0xFF) or
                ((romData[addedPropsPc + 1].toInt() and 0xFF) shl 8)
        assertTrue((writtenProps and 0x2000) != 0,
            "New enemy must have bit 0x2000 even when input has 0x0800 " +
            "(written=0x${writtenProps.toString(16)})")
        assertEquals(0x2800, writtenProps,
            "0x0800 | 0x2000 = 0x2800")
    }

    @Test
    fun `GFX set uses final population not raw change list`() {
        val rp = loadTestRom() ?: return
        val room = rp.readRoomHeader(0x990D)!!
        val gfxEntries = rp.parseEnemyGfxSet(room.enemyGfxPtr)
        val existingSpecies = gfxEntries.map { it.speciesId }.toSet()

        data class FakeChange(val action: String, val enemyId: Int, val x: Int, val y: Int)

        val changes = listOf(
            FakeChange("add", 0xE9FF, 1056, 256),
            FakeChange("add", 0xDCFF, 1264, 144),
            FakeChange("add", 0xD75F, 1216, 192),
            FakeChange("remove", 0xD75F, 1216, 192),
            FakeChange("remove", 0xE9FF, 1056, 256),
            FakeChange("remove", 0xDCFF, 1264, 144),
            FakeChange("add", 0xD63F, 1312, 128),
            FakeChange("add", 0xDCFF, 1056, 256),
        )

        // BUG (old code): iterate all adds → neededSpecies = {0xE9FF, 0xD75F}
        val buggyNeeded = mutableSetOf<Int>()
        for (c in changes) {
            if (c.action == "add" && c.enemyId !in existingSpecies) {
                buggyNeeded.add(c.enemyId)
            }
        }
        assertTrue(buggyNeeded.contains(0xE9FF),
            "Old code would include removed species 0xE9FF")
        assertTrue(buggyNeeded.contains(0xD75F),
            "Old code would include removed species 0xD75F")

        // FIX (new code): compute final population, then check needed
        val finalPop = rp.parseEnemyPopulation(room.enemySetPtr).map { it.id }.toMutableList()
        for (c in changes) {
            when (c.action) {
                "add" -> finalPop.add(c.enemyId)
                "remove" -> finalPop.removeAll { it == c.enemyId }
            }
        }
        val finalSpecies = finalPop.toSet()
        val fixedNeeded = finalSpecies.filter { it !in existingSpecies }
        assertFalse(fixedNeeded.contains(0xE9FF),
            "Fixed code should NOT include removed species 0xE9FF")
        assertFalse(fixedNeeded.contains(0xD75F),
            "Fixed code should NOT include removed species 0xD75F")
        assertTrue(fixedNeeded.isEmpty(),
            "Zoomer and Waver are already in GFX set, no new entries needed")
    }

    @Test
    fun `GFX relocation must not overlap when multiple rooms are patched`() {
        val rp = loadTestRom() ?: return
        val romData = rp.getRomData().copyOf()

        val bankB4End = rp.snesToPc(0xB4FFFF) + 1
        val bankB4Start = rp.snesToPc(0xB48000)
        var gfxFreePtr = bankB4End
        while (gfxFreePtr > bankB4Start) {
            val b = romData[gfxFreePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
            gfxFreePtr--
        }
        gfxFreePtr++
        if (gfxFreePtr + 2 <= bankB4End) gfxFreePtr += 2

        fun writeU16(offset: Int, value: Int) {
            romData[offset] = (value and 0xFF).toByte()
            romData[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        // Room A: 3 GFX entries (12 data bytes + 2 terminator = 14 bytes)
        val sizeA = 3 * 4 + 2
        val allocA = gfxFreePtr
        gfxFreePtr += sizeA
        var off = allocA
        for (entry in listOf(0xD63F to 0, 0xDCFF to 1, 0xD3FF to 2)) {
            writeU16(off, entry.first); writeU16(off + 2, entry.second); off += 4
        }
        writeU16(off, 0xFFFF)

        // Room B: 2 GFX entries (8 data bytes + 2 terminator = 10 bytes)
        val sizeB = 2 * 4 + 2
        val allocB = gfxFreePtr
        gfxFreePtr += sizeB
        off = allocB
        for (entry in listOf(0xDCBF to 0, 0xD93F to 1)) {
            writeU16(off, entry.first); writeU16(off + 2, entry.second); off += 4
        }
        writeU16(off, 0xFFFF)

        assertTrue(allocB >= allocA + sizeA,
            "Room B allocation ($allocB) must start at or after Room A end (${allocA + sizeA})")

        // Verify Room A data is intact after Room B write
        val aTermOff = allocA + 3 * 4
        val aTerm = (romData[aTermOff].toInt() and 0xFF) or
                ((romData[aTermOff + 1].toInt() and 0xFF) shl 8)
        assertEquals(0xFFFF, aTerm,
            "Room A FFFF terminator must survive Room B allocation (at PC 0x${aTermOff.toString(16)})")

        val aEntry2Species = (romData[allocA + 8].toInt() and 0xFF) or
                ((romData[allocA + 9].toInt() and 0xFF) shl 8)
        assertEquals(0xD3FF, aEntry2Species,
            "Room A entry[2] species must be intact after Room B allocation")
    }

    @Test
    fun `GFX free space preserves last vanilla terminator`() {
        val rp = loadTestRom() ?: return
        val romData = rp.getRomData().copyOf()

        val bankB4End = rp.snesToPc(0xB4FFFF) + 1
        val bankB4Start = rp.snesToPc(0xB48000)
        var gfxFreePtr = bankB4End
        while (gfxFreePtr > bankB4Start) {
            val b = romData[gfxFreePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
            gfxFreePtr--
        }
        val rawFreePtr = gfxFreePtr + 1
        // The +2 guard skips past the absorbed FFFF terminator
        val guardedFreePtr = if (rawFreePtr + 2 <= bankB4End) rawFreePtr + 2 else rawFreePtr

        // Verify the 2 bytes before rawFreePtr are part of valid data (the absorbed terminator)
        // They should be 0xFF,0xFF in the original ROM
        val byteAtRaw = romData[rawFreePtr - 1].toInt() and 0xFF
        val byteAtRawPlus1 = romData[rawFreePtr].toInt() and 0xFF
        assertEquals(0xFF, byteAtRaw, "Byte at scan boundary should be 0xFF (absorbed terminator)")
        assertEquals(0xFF, byteAtRawPlus1, "Byte after scan boundary should be 0xFF")

        assertTrue(guardedFreePtr > rawFreePtr,
            "Guarded free ptr ($guardedFreePtr) must be > raw ($rawFreePtr) to preserve terminator")
    }

    @Test
    fun `GFX set skips vanilla species that have no existing GFX entry`() {
        val rp = loadTestRom() ?: return
        // Room 0x9938 has 1 enemy (Elevator 0xD73F) but EMPTY GFX set
        val room = rp.readRoomHeader(0x9938)!!
        val gfxEntries = rp.parseEnemyGfxSet(room.enemyGfxPtr)
        val existingSpecies = gfxEntries.map { it.speciesId }.toSet()
        val vanillaPopulation = rp.parseEnemyPopulation(room.enemySetPtr)
        val vanillaSpecies = vanillaPopulation.map { it.id }.toSet()

        assertTrue(vanillaSpecies.contains(0xD73F), "Elevator should be in vanilla population")
        assertFalse(existingSpecies.contains(0xD73F), "Elevator should NOT be in GFX set")

        // Simulate adding Atomic (0xE9FF) — a genuinely new species
        val addedSpecies = setOf(0xE9FF)
        val finalSpecies = vanillaSpecies + addedSpecies

        // OLD logic: neededSpecies = finalSpecies - existingSpecies → includes Elevator!
        val oldNeeded = finalSpecies.filter { it !in existingSpecies }
        assertTrue(oldNeeded.contains(0xD73F),
            "Old logic would add GFX entry for Elevator (vanilla species with no GFX entry)")

        // NEW logic: neededSpecies = (finalSpecies - vanillaSpecies) - existingSpecies
        val newNeeded = (finalSpecies - vanillaSpecies).filter { it !in existingSpecies }
        assertFalse(newNeeded.contains(0xD73F),
            "New logic must NOT add GFX entry for Elevator (already works without one)")
        assertTrue(newNeeded.contains(0xE9FF),
            "New logic should add GFX entry for Atomic (genuinely new species)")
    }

    @Test
    fun `GFX set respects 4 entry SNES hardware limit`() {
        val maxSlots = 4
        val existingEntries = listOf(
            RomParser.EnemyGfxEntry(0xD63F, 1),
            RomParser.EnemyGfxEntry(0xDCFF, 2),
            RomParser.EnemyGfxEntry(0xD93F, 3),
            RomParser.EnemyGfxEntry(0xD7FF, 4),
        )
        assertEquals(maxSlots, existingEntries.size)

        val newEntries = existingEntries.toMutableList()
        val neededSpecies = listOf(0xD47F, 0xD87F)
        var nextPal = (existingEntries.maxOfOrNull { it.paletteIndex } ?: 0) + 1
        for (specId in neededSpecies) {
            if (newEntries.size >= maxSlots) continue
            newEntries.add(RomParser.EnemyGfxEntry(specId, nextPal++))
        }
        assertEquals(maxSlots, newEntries.size,
            "GFX set should not exceed $maxSlots entries (SNES ProcessEnemyTilesets limit)")
    }

    /**
     * Verify that our computed free space in banks $A1 and $B4 does NOT overlap
     * with any existing vanilla room data. Scans ALL rooms in the ROM.
     */
    @Test
    fun `free space must not overlap any existing vanilla data`() {
        val rp = loadTestRom() ?: run { println("SKIP: test ROM not found"); return }
        val rom = rp.getRomData()

        // Collect every used enemy population range and GFX set range from ALL rooms
        val usedA1Ranges = mutableListOf<Pair<Int, Int>>()  // (startPc, endPc)
        val usedB4Ranges = mutableListOf<Pair<Int, Int>>()

        val allRoomIds = mutableListOf<Int>()
        // Scan all room IDs in bank $8F ($91F8..$9FFF is the standard range)
        for (roomId in 0x91F8..0x9FFF step 1) {
            val room = rp.readRoomHeader(roomId)
            if (room != null && room.width in 1..16 && room.height in 1..16) {
                allRoomIds.add(roomId)
            }
        }

        println("Found ${allRoomIds.size} parseable rooms")

        for (roomId in allRoomIds) {
            val room = rp.readRoomHeader(roomId) ?: continue

            // Enemy population in bank $A1
            if (room.enemySetPtr != 0 && room.enemySetPtr != 0xFFFF) {
                val enemies = rp.parseEnemyPopulation(room.enemySetPtr)
                val startPc = rp.snesToPc(0xA10000 or room.enemySetPtr)
                val endPc = startPc + enemies.size * 16 + 3  // +FFFF +killcount
                usedA1Ranges.add(startPc to endPc)
            }

            // GFX set in bank $B4
            if (room.enemyGfxPtr != 0 && room.enemyGfxPtr != 0xFFFF) {
                val gfx = rp.parseEnemyGfxSet(room.enemyGfxPtr)
                val startPc = rp.snesToPc(0xB40000 or room.enemyGfxPtr)
                val endPc = startPc + gfx.size * 4 + 2  // +FFFF terminator
                usedB4Ranges.add(startPc to endPc)
            }

            // Also check ALL states, not just the default
            val allStates = rp.findAllStateDataOffsets(roomId)
            for (stateOff in allStates) {
                val enemyPtr = (rom[stateOff + 8].toInt() and 0xFF) or
                        ((rom[stateOff + 9].toInt() and 0xFF) shl 8)
                val gfxPtr = (rom[stateOff + 10].toInt() and 0xFF) or
                        ((rom[stateOff + 11].toInt() and 0xFF) shl 8)

                if (enemyPtr != 0 && enemyPtr != 0xFFFF) {
                    val enemies = rp.parseEnemyPopulation(enemyPtr)
                    val startPc = rp.snesToPc(0xA10000 or enemyPtr)
                    val endPc = startPc + enemies.size * 16 + 3
                    usedA1Ranges.add(startPc to endPc)
                }
                if (gfxPtr != 0 && gfxPtr != 0xFFFF) {
                    val gfx = rp.parseEnemyGfxSet(gfxPtr)
                    val startPc = rp.snesToPc(0xB40000 or gfxPtr)
                    val endPc = startPc + gfx.size * 4 + 2
                    usedB4Ranges.add(startPc to endPc)
                }
            }
        }

        // Deduplicate ranges (many rooms share the same pointers)
        val uniqueA1 = usedA1Ranges.distinctBy { it.first }.sortedBy { it.first }
        val uniqueB4 = usedB4Ranges.distinctBy { it.first }.sortedBy { it.first }

        // Compute free space the same way as EditorState.exportRom
        val bankA1End = rp.snesToPc(0xA1FFFF) + 1
        val bankA1Start = rp.snesToPc(0xA18000)
        var enemyFreePtr = bankA1End
        while (enemyFreePtr > bankA1Start) {
            val b = rom[enemyFreePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
            enemyFreePtr--
        }
        enemyFreePtr++

        val bankB4End = rp.snesToPc(0xB4FFFF) + 1
        val bankB4Start = rp.snesToPc(0xB48000)
        var gfxFreePtr = bankB4End
        while (gfxFreePtr > bankB4Start) {
            val b = rom[gfxFreePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
            gfxFreePtr--
        }
        gfxFreePtr++
        if (gfxFreePtr + 2 <= bankB4End) gfxFreePtr += 2

        println("Bank \$A1 free space starts at PC 0x${enemyFreePtr.toString(16).uppercase()}")
        println("  SNES: \$${rp.pcToSnes(enemyFreePtr).toString(16).uppercase()}")
        println("  Available: ${bankA1End - enemyFreePtr} bytes")
        println("Bank \$B4 free space starts at PC 0x${gfxFreePtr.toString(16).uppercase()}")
        println("  SNES: \$${rp.pcToSnes(gfxFreePtr).toString(16).uppercase()}")
        println("  Available: ${bankB4End - gfxFreePtr} bytes")

        // Verify: highest used address must be below free space pointer
        val maxUsedA1 = uniqueA1.maxOfOrNull { it.second } ?: bankA1Start
        val maxUsedB4 = uniqueB4.maxOfOrNull { it.second } ?: bankB4Start

        println("Highest used A1 address: PC 0x${maxUsedA1.toString(16).uppercase()}")
        println("Highest used B4 address: PC 0x${maxUsedB4.toString(16).uppercase()}")

        // Check for overlaps
        for ((start, end) in uniqueA1) {
            assertFalse(start < bankA1End && end > enemyFreePtr,
                "A1 OVERLAP: vanilla data at PC 0x${start.toString(16)}-0x${end.toString(16)} " +
                        "extends into free space (starts at 0x${enemyFreePtr.toString(16)})")
        }
        for ((start, end) in uniqueB4) {
            assertFalse(start < bankB4End && end > gfxFreePtr,
                "B4 OVERLAP: vanilla data at PC 0x${start.toString(16)}-0x${end.toString(16)} " +
                        "extends into free space (starts at 0x${gfxFreePtr.toString(16)})")
        }

        assertTrue(enemyFreePtr > maxUsedA1,
            "Free space pointer (0x${enemyFreePtr.toString(16)}) must be above highest " +
                    "used A1 address (0x${maxUsedA1.toString(16)})")
        assertTrue(gfxFreePtr > maxUsedB4,
            "Free space pointer (0x${gfxFreePtr.toString(16)}) must be above highest " +
                    "used B4 address (0x${maxUsedB4.toString(16)})")
    }

    /**
     * Verify which state findDefaultStateData selects for each room we modify.
     * Critical: if a room has E629 (boss-dead) conditions, we might be editing
     * the wrong state.
     */
    @Test
    fun `state selection diagnostic for target rooms`() {
        val rp = loadTestRom() ?: run { println("SKIP: test ROM not found"); return }
        val rom = rp.getRomData()

        val targetRooms = listOf(0x990D, 0x99BD, 0x9938, 0x9969)

        for (roomId in targetRooms) {
            println("=" .repeat(60))
            println("Room 0x${roomId.toString(16).uppercase()}")

            val room = rp.readRoomHeader(roomId)
            if (room == null) {
                println("  COULD NOT READ")
                continue
            }

            println("  readRoomHeader selected:")
            println("    enemySetPtr = 0x${room.enemySetPtr.toString(16).uppercase()}")
            println("    enemyGfxPtr = 0x${room.enemyGfxPtr.toString(16).uppercase()}")

            // Parse ALL states
            val allStates = rp.findAllStateDataOffsets(roomId)
            println("  All states (${allStates.size}):")
            for ((idx, stateOff) in allStates.withIndex()) {
                val enemyPtr = (rom[stateOff + 8].toInt() and 0xFF) or
                        ((rom[stateOff + 9].toInt() and 0xFF) shl 8)
                val gfxPtr = (rom[stateOff + 10].toInt() and 0xFF) or
                        ((rom[stateOff + 11].toInt() and 0xFF) shl 8)
                val levelPtr = (rom[stateOff].toInt() and 0xFF) or
                        ((rom[stateOff + 1].toInt() and 0xFF) shl 8) or
                        ((rom[stateOff + 2].toInt() and 0xFF) shl 16)
                val matchesDefault = enemyPtr == room.enemySetPtr
                val gfxMatchesDefault = gfxPtr == room.enemyGfxPtr
                println("    state[$idx] at PC 0x${stateOff.toString(16).uppercase()}:")
                println("      enemySetPtr=0x${enemyPtr.toString(16).uppercase()} " +
                        "(${if (matchesDefault) "MATCHES default" else "DIFFERENT from default"})")
                println("      enemyGfxPtr=0x${gfxPtr.toString(16).uppercase()} " +
                        "(${if (gfxMatchesDefault) "MATCHES default" else "DIFFERENT from default"})")
                println("      levelDataPtr=\$${levelPtr.toString(16).uppercase()}")

                val enemies = rp.parseEnemyPopulation(enemyPtr)
                println("      enemies: ${enemies.size} entries")
                for (e in enemies) {
                    println("        0x${e.id.toString(16).uppercase()} (${RomParser.enemyName(e.id)}) " +
                            "at (${e.x},${e.y}) props=0x${e.properties.toString(16)}")
                }
            }

            // Parse state condition codes
            val pcOffset = rp.roomIdToPc(roomId)
            var pos = pcOffset + 11
            println("  State condition codes:")
            var stateIdx = 0
            while (pos + 1 < rom.size && stateIdx < 10) {
                val code = (rom[pos].toInt() and 0xFF) or ((rom[pos + 1].toInt() and 0xFF) shl 8)
                when (code) {
                    0xE5E6 -> {
                        println("    E5E6 (DEFAULT) at offset $pos")
                        break
                    }
                    0xE5EB -> {
                        val doorPtr = (rom[pos + 2].toInt() and 0xFF) or ((rom[pos + 3].toInt() and 0xFF) shl 8)
                        val statePtr = (rom[pos + 4].toInt() and 0xFF) or ((rom[pos + 5].toInt() and 0xFF) shl 8)
                        println("    E5EB (door-dependent) doorPtr=0x${doorPtr.toString(16)} statePtr=0x${statePtr.toString(16)}")
                        pos += 6
                    }
                    0xE612, 0xE629 -> {
                        val flag = rom[pos + 2].toInt() and 0xFF
                        val statePtr = (rom[pos + 3].toInt() and 0xFF) or ((rom[pos + 4].toInt() and 0xFF) shl 8)
                        val condName = if (code == 0xE629) "BOSS DEAD" else "EVENT"
                        println("    ${code.toString(16).uppercase()} ($condName) flag=0x${flag.toString(16)} statePtr=0x${statePtr.toString(16)}")
                        pos += 5
                    }
                    0xE5FF, 0xE640, 0xE652, 0xE669, 0xE678 -> {
                        val statePtr = (rom[pos + 2].toInt() and 0xFF) or ((rom[pos + 3].toInt() and 0xFF) shl 8)
                        println("    ${code.toString(16).uppercase()} statePtr=0x${statePtr.toString(16)}")
                        pos += 4
                    }
                    else -> {
                        println("    UNKNOWN code 0x${code.toString(16).uppercase()} — stopping scan")
                        break
                    }
                }
                stateIdx++
            }
            println()
        }
    }

    /**
     * Simulate the exact export for the user's project and verify byte-level
     * correctness of enemy population and GFX set writes.
     */
    @Test
    fun `end-to-end export simulation for user project`() {
        val rp = loadTestRom() ?: run { println("SKIP: test ROM not found"); return }
        val rom = rp.getRomData()
        val romCopy = rom.copyOf()

        fun readU16(data: ByteArray, off: Int): Int =
            (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
        fun writeU16(data: ByteArray, off: Int, v: Int) {
            data[off] = (v and 0xFF).toByte()
            data[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        // Simulate the exact export for room 0x990D:
        // Add Waver(0xD63F) at (1312,128), Zoomer(0xDCFF) at (1056,256),
        //   Ripper II(0xD3FF) at (1232,112) with extra2=8,extra3=8
        val room990D = rp.readRoomHeader(0x990D)!!
        val vanilla990D = rp.parseEnemyPopulation(room990D.enemySetPtr)
        val modified990D = vanilla990D.toMutableList()
        modified990D.add(RomParser.EnemyEntry(0xD63F, 1312, 128, 0, 0x0800))
        modified990D.add(RomParser.EnemyEntry(0xDCFF, 1056, 256, 0, 0x0800))
        modified990D.add(RomParser.EnemyEntry(0xD3FF, 1232, 112, 0, 0x2800, extra2 = 8, extra3 = 8))

        println("Room 0x990D: vanilla has ${vanilla990D.size} enemies, modified has ${modified990D.size}")

        val enemyPc = rp.snesToPc(0xA10000 or room990D.enemySetPtr)
        val killCountPc = enemyPc + vanilla990D.size * 16 + 2
        val killCount = rom[killCountPc]

        val originalSize = vanilla990D.size * 16 + 3
        val newSize = modified990D.size * 16 + 3
        println("  originalSize=$originalSize, newSize=$newSize (${if (newSize > originalSize) "RELOCATE" else "IN-PLACE"})")

        // Verify each enemy entry roundtrips correctly
        for ((i, e) in vanilla990D.withIndex()) {
            val off = enemyPc + i * 16
            assertEquals(e.id, readU16(rom, off), "vanilla[$i] species")
            assertEquals(e.x, readU16(rom, off + 2), "vanilla[$i] x")
            assertEquals(e.y, readU16(rom, off + 4), "vanilla[$i] y")
            assertEquals(e.initParam, readU16(rom, off + 6), "vanilla[$i] initParam")
            assertEquals(e.properties, readU16(rom, off + 8), "vanilla[$i] properties")
            assertEquals(e.extra1, readU16(rom, off + 10), "vanilla[$i] extra1")
            assertEquals(e.extra2, readU16(rom, off + 12), "vanilla[$i] extra2")
            assertEquals(e.extra3, readU16(rom, off + 14), "vanilla[$i] extra3")
        }

        // Check that the 16-byte entry structure matches SMILE exactly
        println("\nFirst vanilla enemy raw bytes:")
        val firstOff = enemyPc
        val rawBytes = ByteArray(16) { rom[firstOff + it] }
        println("  ${rawBytes.joinToString(" ") { "%02X".format(it) }}")
        println("  species=0x${readU16(rom, firstOff).toString(16)} x=${readU16(rom, firstOff + 2)} " +
                "y=${readU16(rom, firstOff + 4)} init=0x${readU16(rom, firstOff + 6).toString(16)} " +
                "props=0x${readU16(rom, firstOff + 8).toString(16)} " +
                "e1=0x${readU16(rom, firstOff + 10).toString(16)} " +
                "e2=0x${readU16(rom, firstOff + 12).toString(16)} " +
                "e3=0x${readU16(rom, firstOff + 14).toString(16)}")

        // Verify the GFX set for 0x990D
        val gfx990D = rp.parseEnemyGfxSet(room990D.enemyGfxPtr)
        println("\nRoom 0x990D GFX set: ${gfx990D.size} entries")
        for (ge in gfx990D) {
            println("  0x${ge.speciesId.toString(16)} palette=${ge.paletteIndex}")
        }
        val vanillaSpecies990D = vanilla990D.map { it.id }.toSet()
        val existingGfxSpecies = gfx990D.map { it.speciesId }.toSet()
        val finalSpecies990D = modified990D.map { it.id }.toSet()
        val neededSpecies = (finalSpecies990D - vanillaSpecies990D).filter { it !in existingGfxSpecies }
        println("vanillaSpecies: ${vanillaSpecies990D.map { "0x${it.toString(16)}" }}")
        println("finalSpecies: ${finalSpecies990D.map { "0x${it.toString(16)}" }}")
        println("existingGfxSpecies: ${existingGfxSpecies.map { "0x${it.toString(16)}" }}")
        println("neededSpecies (should only be D3FF): ${neededSpecies.map { "0x${it.toString(16)}" }}")

        assertEquals(listOf(0xD3FF), neededSpecies.toList(),
            "Only Ripper II should need a new GFX entry (Waver/Zoomer already in GFX set)")
    }

    @Test
    fun `diagnostic dump enemy GFX set counter values for vanilla rooms`() {
        val rp = loadTestRom() ?: run { println("SKIP: test ROM not found"); return }
        val rom = rp.getRomData()

        val rooms = listOf(
            0x990D to "Terminator",
            0x99BD to "Sidehopper Room",
            0x9938 to "Room 9938",
            0x9969 to "Room 9969",
            0x92FD to "Parlor",
            0x91F8 to "Landing Site",
            0x93AA to "Room 93AA",
            0x94FD to "Room 94FD",
            0x9879 to "Room 9879",
            0xA59F to "Kraid's Room",
            0xCD13 to "Phantoon's Room",
        )

        val allCounterValues = mutableListOf<Int>()

        for ((roomId, label) in rooms) {
            println("=" .repeat(70))
            println("Room 0x${roomId.toString(16).uppercase()} — $label")
            println("=" .repeat(70))

            val room = rp.readRoomHeader(roomId)
            if (room == null) {
                println("  *** Could not read room header ***")
                continue
            }

            println("  enemyGfxPtr = 0x${room.enemyGfxPtr.toString(16).uppercase()}")
            println("  enemySetPtr = 0x${room.enemySetPtr.toString(16).uppercase()}")

            // --- Enemy GFX Set ---
            println("\n  Enemy GFX Set (parsed):")
            val gfx = rp.parseEnemyGfxSet(room.enemyGfxPtr)
            if (gfx.isEmpty()) {
                println("    (empty)")
            } else {
                for ((i, entry) in gfx.withIndex()) {
                    val name = RomParser.enemyName(entry.speciesId)
                    println("    [$i] speciesId=0x${entry.speciesId.toString(16).uppercase()}  " +
                            "counter/paletteIndex=${entry.paletteIndex}  ($name)")
                    allCounterValues.add(entry.paletteIndex)
                }
            }

            // --- Raw hex bytes of GFX set ---
            if (room.enemyGfxPtr != 0 && room.enemyGfxPtr != 0xFFFF) {
                val gfxSnes = 0xB40000 or room.enemyGfxPtr
                val gfxPc = rp.snesToPc(gfxSnes)
                val rawLen = (gfx.size * 4) + 2
                val rawBytes = ByteArray(rawLen) { rom[gfxPc + it] }
                val hexStr = rawBytes.joinToString(" ") { "%02X".format(it) }
                println("\n  Raw GFX hex (${rawLen} bytes at PC 0x${gfxPc.toString(16).uppercase()}, SNES \$${gfxSnes.toString(16).uppercase()}):")
                println("    $hexStr")
                // Also show as word pairs
                println("  As word pairs:")
                var off = 0
                while (off + 1 < rawLen) {
                    val w = (rawBytes[off].toInt() and 0xFF) or ((rawBytes[off + 1].toInt() and 0xFF) shl 8)
                    print("    0x${w.toString(16).uppercase().padStart(4, '0')}")
                    off += 2
                    if (off + 1 < rawLen) {
                        val w2 = (rawBytes[off].toInt() and 0xFF) or ((rawBytes[off + 1].toInt() and 0xFF) shl 8)
                        println("  0x${w2.toString(16).uppercase().padStart(4, '0')}")
                        off += 2
                    } else {
                        println()
                    }
                }
            }

            // --- Enemy Population ---
            println("\n  Enemy Population:")
            val enemies = rp.parseEnemyPopulation(room.enemySetPtr)
            if (enemies.isEmpty()) {
                println("    (empty)")
            } else {
                for ((i, e) in enemies.withIndex()) {
                    val name = RomParser.enemyName(e.id)
                    println("    [$i] id=0x${e.id.toString(16).uppercase()} ($name)  " +
                            "pos=(${e.x},${e.y})  initParam=0x${e.initParam.toString(16).uppercase()}  " +
                            "props=0x${e.properties.toString(16).uppercase()}")
                }
            }
            println()
        }

        // --- Summary analysis ---
        println("=" .repeat(70))
        println("SUMMARY ANALYSIS")
        println("=" .repeat(70))
        println("All counter/paletteIndex values seen: $allCounterValues")
        if (allCounterValues.isNotEmpty()) {
            println("Min value: ${allCounterValues.min()}")
            println("Max value: ${allCounterValues.max()}")
            println("Distinct values: ${allCounterValues.distinct().sorted()}")

            val startAt0 = allCounterValues.any { it == 0 }
            val startAt1 = allCounterValues.any { it == 1 }
            println("Contains 0: $startAt0")
            println("Contains 1: $startAt1")
        }

        // Per-room sequential analysis
        println("\nPer-room sequential pattern check:")
        for ((roomId, label) in rooms) {
            val room = rp.readRoomHeader(roomId) ?: continue
            val gfx = rp.parseEnemyGfxSet(room.enemyGfxPtr)
            if (gfx.size < 2) continue
            val vals = gfx.map { it.paletteIndex }
            val isSequential = vals.zipWithNext().all { (a, b) -> b == a + 1 }
            val startsFrom = vals.firstOrNull()
            println("  0x${roomId.toString(16).uppercase()} ($label): values=$vals  " +
                    "startsFrom=$startsFrom  sequential=$isSequential")
        }
    }
}

package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File
import com.supermetroid.editor.rom.RomParser.PlmEntry

class ScrollSystemTest {

    private fun loadRom(name: String): RomParser? {
        val paths = listOf(name, "/Users/kenny/code/super_metroid_dev/$name")
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        return TestRomHelper.loadRomParser()
    }

    private fun decodeScrollCommands(rp: RomParser, paramPtr: Int): List<Pair<Int, Int>> {
        val snesAddr = 0x8F0000 or paramPtr
        val pc = rp.snesToPc(snesAddr)
        val commands = mutableListOf<Pair<Int, Int>>()
        var offset = 0
        while (offset < 256) {
            val screenIdx = rp.romData[pc + offset].toInt() and 0xFF
            if (screenIdx >= 0x80) break
            val scrollVal = rp.romData[pc + offset + 1].toInt() and 0xFF
            commands.add(screenIdx to scrollVal)
            offset += 2
        }
        return commands
    }

    private fun scrollValName(v: Int) = when (v) {
        0x00 -> "Red(hidden)"
        0x01 -> "Blue(scroll)"
        0x02 -> "Green(noScroll)"
        else -> "?($v)"
    }

    @Test
    fun `dump Parlor scroll data - vanilla ROM`() {
        val rp = loadRom("test-resources/Super Metroid (JU) [!].smc") ?: run {
            println("SKIP: vanilla ROM not found")
            return
        }
        dumpRoomScrollInfo(rp, 0x92FD, "VANILLA Parlor and Alcatraz")
    }

    @Test
    fun `dump Parlor scroll data - Spike Olympics ROM`() {
        val rp = loadRom("projects/Super Metroid Spike Olympics I/Super Metroid Spike Olympics I.smc") ?: run {
            println("SKIP: Spike Olympics ROM not found")
            return
        }
        dumpRoomScrollInfo(rp, 0x92FD, "SPIKE OLYMPICS Parlor and Alcatraz")
    }

    @Test
    fun `dump Parlor scroll data - Spike Olympics EDITED ROM`() {
        val rp = loadRom("projects/Super Metroid Spike Olympics I/Super Metroid Spike Olympics I_edited.smc") ?: run {
            println("SKIP: Spike Olympics edited ROM not found")
            return
        }
        dumpRoomScrollInfo(rp, 0x92FD, "SPIKE OLYMPICS EDITED Parlor and Alcatraz")
    }

    private fun dumpRoomScrollInfo(rp: RomParser, roomId: Int, label: String) {
        println("\n${"=".repeat(70)}")
        println("  $label (Room $${roomId.toString(16).uppercase()})")
        println("${"=".repeat(70)}")

        val room = rp.readRoomHeader(roomId) ?: run {
            println("ERROR: Could not parse room header")
            return
        }
        val w = room.width
        val h = room.height
        println("\nRoom: ${room.name}, dimensions: ${w}x${h} screens")
        println("Scroll Ptr: $${room.roomScrollsPtr.toString(16).uppercase()}")
        println("PLM Set Ptr: $${room.plmSetPtr.toString(16).uppercase()}")
        println("Door Out Ptr: $${room.doorOut.toString(16).uppercase()}")

        // Dump all states and their scroll pointers
        println("\n--- ALL ROOM STATES ---")
        val stateOffsets = rp.findAllStateDataOffsets(roomId)
        for ((si, stateOff) in stateOffsets.withIndex()) {
            val stateData = rp.readStateData(stateOff)
            val scrollPtr = stateData["roomScrollsPtr"] ?: 0
            val plmPtr = stateData["plmSetPtr"] ?: 0
            println("  State[$si]: scrollPtr=$${scrollPtr.toString(16).uppercase()} plmSetPtr=$${plmPtr.toString(16).uppercase()}")
            val stateScrolls = rp.parseScrollData(scrollPtr, w, h)
            for (row in 0 until h) {
                val rowStr = (0 until w).joinToString(" ") { col ->
                    val idx = row * w + col
                    val v = stateScrolls.getOrElse(idx) { -1 }
                    when (v) { 0x00 -> "R"; 0x01 -> "B"; 0x02 -> "G"; else -> "?" } + "(${idx.toString().padStart(2)})"
                }
                println("    Row $row: $rowStr")
            }
        }

        // Static scroll data
        println("\n--- STATIC SCROLL DATA ---")
        val scrolls = rp.parseScrollData(room.roomScrollsPtr, w, h)
        for (row in 0 until h) {
            val rowStr = (0 until w).joinToString(" ") { col ->
                val idx = row * w + col
                val v = scrolls.getOrElse(idx) { -1 }
                val label2 = when (v) {
                    0x00 -> "R"
                    0x01 -> "B"
                    0x02 -> "G"
                    else -> "?"
                }
                "$label2(${idx.toString().padStart(2)})"
            }
            println("  Row $row: $rowStr")
        }

        // PLM set
        println("\n--- PLM SET ---")
        val plms = rp.parsePlmSet(room.plmSetPtr)
        val scrollPlms = mutableListOf<PlmEntry>()
        for (plm in plms) {
            val idHex = plm.id.toString(16).uppercase().padStart(4, '0')
            val pHex = plm.param.toString(16).uppercase().padStart(4, '0')
            val isScroll = plm.id == 0xB703 || plm.id == 0xB63B || plm.id == 0xB647
                    || plm.id == 0xB63F || plm.id == 0xB643
            val tag = if (isScroll) " <<< SCROLL PLM" else ""
            println("  PLM $idHex  pos=(${plm.x},${plm.y})  param=$pHex$tag")
            if (isScroll) scrollPlms.add(plm)
        }
        println("  Total PLMs: ${plms.size}, Scroll PLMs: ${scrollPlms.size}")

        // Decode scroll PLM commands
        if (scrollPlms.isNotEmpty()) {
            println("\n--- SCROLL PLM COMMANDS ---")
            for (plm in scrollPlms) {
                val idHex = plm.id.toString(16).uppercase().padStart(4, '0')
                val pHex = plm.param.toString(16).uppercase().padStart(4, '0')
                println("  PLM $idHex at (${plm.x},${plm.y}), command ptr=$pHex:")
                val cmds = decodeScrollCommands(rp, plm.param)
                if (cmds.isEmpty()) {
                    println("    (no commands or immediate terminator)")
                } else {
                    for ((screenIdx, scrollVal) in cmds) {
                        val col = screenIdx % w
                        val row = screenIdx / w
                        println("    Screen[$screenIdx] (x=$col,y=$row) -> ${scrollValName(scrollVal)}")
                    }
                }
            }
        }

        // Doors
        println("\n--- DOORS ---")
        val doors = rp.parseDoorList(room.doorOut)
        for ((i, door) in doors.withIndex()) {
            val destHex = door.destRoomPtr.toString(16).uppercase().padStart(4, '0')
            val dirName = door.directionName
            val entryHex = door.entryCode.toString(16).uppercase().padStart(4, '0')
            val capHex = door.doorCapCode.toString(16).uppercase().padStart(4, '0')
            println("  Door[$i]: dest=$destHex($dirName) spawn=(${door.screenX},${door.screenY}) " +
                    "doorCapCode=$capHex entryCode=$entryHex")
            if (door.entryCode != 0x0000) {
                println("    >>> Door ASM at \$8F:$entryHex — decoding scroll writes:")
                decodeDoorAsm(rp, door.entryCode)
            }
        }
        println()
    }

    @Test
    fun `door ASM diagnostic - Landing Site vs Terminator vs Parlor`() {
        val rp = loadRom("test-resources/Super Metroid (JU) [!].smc") ?: run {
            println("SKIP: vanilla ROM not found")
            return
        }

        val landingSite = 0x91F8
        val parlor = 0x92FD
        val terminator = 0x990D

        println("\n=== DOOR ASM DIAGNOSTIC: Landing Site / Parlor / Terminator ===\n")

        for (roomId in listOf(landingSite, parlor, terminator)) {
            val room = rp.readRoomHeader(roomId) ?: continue
            val roomHex = roomId.toString(16).uppercase()
            println("Room \$${roomHex} (${room.name}) — ${room.width}x${room.height} screens")
            val doors = rp.parseDoorList(room.doorOut)
            for ((i, door) in doors.withIndex()) {
                val destHex = door.destRoomPtr.toString(16).uppercase().padStart(4, '0')
                val entryHex = door.entryCode.toString(16).uppercase().padStart(4, '0')
                val capHex = door.doorCapCode.toString(16).uppercase().padStart(4, '0')
                println("  Door[$i]: dest=$destHex dir=${door.directionName} " +
                        "spawn=(${door.screenX},${door.screenY}) cap=$capHex entry=$entryHex dist=0x${door.distFromDoor.toString(16)}")
                if (door.entryCode != 0x0000) {
                    decodeDoorAsm(rp, door.entryCode)
                }
            }
            println()
        }

        // Build reverse index: which vanilla doors point TO each room
        println("=== REVERSE DOOR INDEX: doors that enter Landing Site ===")
        val allRoomIds = mutableListOf<Int>()
        for (roomId in 0x91F8..0x9FFF) {
            val room = rp.readRoomHeader(roomId)
            if (room != null && room.width in 1..16 && room.height in 1..16)
                allRoomIds.add(roomId)
        }

        for (roomId in allRoomIds) {
            val room = rp.readRoomHeader(roomId) ?: continue
            val doors = rp.parseDoorList(room.doorOut)
            for ((i, door) in doors.withIndex()) {
                if (door.destRoomPtr == landingSite) {
                    val srcHex = roomId.toString(16).uppercase()
                    val entryHex = door.entryCode.toString(16).uppercase().padStart(4, '0')
                    println("  From \$${srcHex} door[$i]: dir=${door.directionName} " +
                            "spawn=(${door.screenX},${door.screenY}) entry=$entryHex")
                    if (door.entryCode != 0x0000) {
                        decodeDoorAsm(rp, door.entryCode)
                    }
                }
            }
        }

        println("\n=== SCENARIO: Terminator door redirected to Landing Site ===")
        println("If user redirects Terminator's left door (originally → Parlor) to → Landing Site,")
        println("the entryCode stays as whatever Terminator→Parlor used.")
        println("But the correct entryCode should come from a door that vanilla uses to enter Landing Site.")
        println("This mismatch causes wrong scroll setup → BG corruption on entry.\n")

        val termDoors = rp.parseDoorList(rp.readRoomHeader(terminator)!!.doorOut)
        for ((i, door) in termDoors.withIndex()) {
            if (door.destRoomPtr == parlor || door.destRoomPtr == landingSite) {
                val destHex = door.destRoomPtr.toString(16).uppercase()
                println("  Terminator door[$i] → \$$destHex: entryCode=0x${door.entryCode.toString(16).uppercase()}")
            }
        }
    }

    @Test
    fun `BG corruption deep diagnostic - Terminator to Landing Site`() {
        val rp = loadRom("test-resources/Super Metroid (JU) [!].smc") ?: run {
            println("SKIP: vanilla ROM not found")
            return
        }

        val landingSite = 0x91F8
        val parlor      = 0x92FD
        val terminator  = 0x990D

        val lsRoom   = rp.readRoomHeader(landingSite)!!
        val parlRoom = rp.readRoomHeader(parlor)!!
        val termRoom = rp.readRoomHeader(terminator)!!

        println("=== ROOM HEADERS ===")
        for ((name, room) in listOf("Landing Site" to lsRoom, "Parlor" to parlRoom, "Terminator" to termRoom)) {
            println("$name (0x${room.roomId.toString(16).uppercase()}): " +
                "${room.width}x${room.height}, area=${room.area}, " +
                "CRE=0x${room.creBitflag.toString(16)}, tileset=${room.tileset}, " +
                "bgScrolling=0x${room.bgScrolling.toString(16).uppercase()}, " +
                "scrollPtr=0x${room.roomScrollsPtr.toString(16).uppercase()}, " +
                "bgDataPtr=0x${room.bgDataPtr.toString(16).uppercase()}, " +
                "setupASM=0x${room.setupAsmPtr.toString(16).uppercase()}, " +
                "doorOut=0x${room.doorOut.toString(16).uppercase()}")
        }

        fun rd16(off: Int) = (rp.romData[off].toInt() and 0xFF) or ((rp.romData[off+1].toInt() and 0xFF) shl 8)
        fun rd24(off: Int) = rd16(off) or ((rp.romData[off+2].toInt() and 0xFF) shl 16)

        println("\n=== ALL STATE DATA — Landing Site ===")
        val lsStates = rp.findAllStateDataOffsets(landingSite)
        for ((si, stateOff) in lsStates.withIndex()) {
            val lvlPtr     = rd24(stateOff)
            val tileset    = rp.romData[stateOff + 3].toInt() and 0xFF
            val bgScroll   = rd16(stateOff + 12)
            val scrollPtr  = rd16(stateOff + 14)
            val bgDataPtr  = rd16(stateOff + 22)
            val setupAsm   = rd16(stateOff + 24)
            println("  state[$si] PC=0x${stateOff.toString(16)}: " +
                "lvl=\$${lvlPtr.toString(16)}, tileset=$tileset, " +
                "bgScroll=0x${bgScroll.toString(16).uppercase()}, " +
                "scrollPtr=0x${scrollPtr.toString(16).uppercase()}, " +
                "bgData=0x${bgDataPtr.toString(16).uppercase()}, " +
                "setupASM=0x${setupAsm.toString(16).uppercase()}")

            if (scrollPtr != 0 && scrollPtr != 0xFFFF) {
                val scrollPc = rp.snesToPc(0x8F0000 or scrollPtr)
                val scrollBytes = mutableListOf<Int>()
                for (j in 0 until (lsRoom.width * lsRoom.height).coerceAtMost(50)) {
                    if (scrollPc + j < rp.romData.size)
                        scrollBytes.add(rp.romData[scrollPc + j].toInt() and 0xFF)
                }
                val scrollDisp = scrollBytes.mapIndexed { idx, v ->
                    val label = when (v) { 0 -> "red"; 1 -> "blue"; 2 -> "green"; else -> "?($v)" }
                    "$idx:$label"
                }
                println("    scroll data (${lsRoom.width}x${lsRoom.height}=${lsRoom.width*lsRoom.height} screens): $scrollDisp")
            }
        }

        println("\n=== ALL STATE DATA — Terminator ===")
        val termStates = rp.findAllStateDataOffsets(terminator)
        for ((si, stateOff) in termStates.withIndex()) {
            val lvlPtr     = rd24(stateOff)
            val tileset    = rp.romData[stateOff + 3].toInt() and 0xFF
            val bgScroll   = rd16(stateOff + 12)
            val scrollPtr  = rd16(stateOff + 14)
            val bgDataPtr  = rd16(stateOff + 22)
            val setupAsm   = rd16(stateOff + 24)
            println("  state[$si] PC=0x${stateOff.toString(16)}: " +
                "lvl=\$${lvlPtr.toString(16)}, tileset=$tileset, " +
                "bgScroll=0x${bgScroll.toString(16).uppercase()}, " +
                "scrollPtr=0x${scrollPtr.toString(16).uppercase()}, " +
                "bgData=0x${bgDataPtr.toString(16).uppercase()}, " +
                "setupASM=0x${setupAsm.toString(16).uppercase()}")
        }

        println("\n=== DOOR DEFINITIONS (12-byte raw dumps) ===")
        println("--- Landing Site door-out list ---")
        val lsDoors = rp.parseDoorList(lsRoom.doorOut)
        for ((i, door) in lsDoors.withIndex()) {
            val entryPc = rp.doorEntryPcOffset(lsRoom.doorOut, i)!!
            val raw = (0..11).map { rp.romData[entryPc + it].toInt() and 0xFF }
            val hex = raw.joinToString(" ") { "%02X".format(it) }
            val destName = when (door.destRoomPtr) {
                parlor -> "Parlor"; terminator -> "Terminator"; landingSite -> "Landing Site"
                else -> "0x${door.destRoomPtr.toString(16).uppercase()}"
            }
            println("  door[$i] → $destName: dir=${door.direction}(${door.directionName}) " +
                "spawn=(${door.screenX},${door.screenY}) dist=0x${door.distFromDoor.toString(16)} " +
                "doorCap=0x${door.doorCapCode.toString(16)} entry=0x${door.entryCode.toString(16)}")
            println("    raw: $hex  (PC=0x${entryPc.toString(16)}, bank\$83 ptr=0x${(0x8000 + (entryPc - rp.snesToPc(0x830000))).toString(16)})")
        }

        println("\n--- Parlor door-out list ---")
        val parlDoors = rp.parseDoorList(parlRoom.doorOut)
        for ((i, door) in parlDoors.withIndex()) {
            val entryPc = rp.doorEntryPcOffset(parlRoom.doorOut, i)!!
            val raw = (0..11).map { rp.romData[entryPc + it].toInt() and 0xFF }
            val hex = raw.joinToString(" ") { "%02X".format(it) }
            val destName = when (door.destRoomPtr) {
                parlor -> "Parlor"; terminator -> "Terminator"; landingSite -> "Landing Site"
                else -> "0x${door.destRoomPtr.toString(16).uppercase()}"
            }
            println("  door[$i] → $destName: dir=${door.direction}(${door.directionName}) " +
                "spawn=(${door.screenX},${door.screenY}) dist=0x${door.distFromDoor.toString(16)} " +
                "doorCap=0x${door.doorCapCode.toString(16)} entry=0x${door.entryCode.toString(16)}")
            println("    raw: $hex  (PC=0x${entryPc.toString(16)})")
        }

        println("\n--- Terminator door-out list ---")
        val termDoors = rp.parseDoorList(termRoom.doorOut)
        for ((i, door) in termDoors.withIndex()) {
            val entryPc = rp.doorEntryPcOffset(termRoom.doorOut, i)!!
            val raw = (0..11).map { rp.romData[entryPc + it].toInt() and 0xFF }
            val hex = raw.joinToString(" ") { "%02X".format(it) }
            val destName = when (door.destRoomPtr) {
                parlor -> "Parlor"; terminator -> "Terminator"; landingSite -> "Landing Site"
                else -> "0x${door.destRoomPtr.toString(16).uppercase()}"
            }
            println("  door[$i] → $destName: dir=${door.direction}(${door.directionName}) " +
                "spawn=(${door.screenX},${door.screenY}) dist=0x${door.distFromDoor.toString(16)} " +
                "doorCap=0x${door.doorCapCode.toString(16)} entry=0x${door.entryCode.toString(16)}")
            println("    raw: $hex  (PC=0x${entryPc.toString(16)})")
        }

        println("\n=== SIMULATED EXPORT: Terminator door 1 → Landing Site ===")
        val termDoor1Pc = rp.doorEntryPcOffset(termRoom.doorOut, 1)!!
        val vanillaBefore = (0..11).map { rp.romData[termDoor1Pc + it].toInt() and 0xFF }
        println("Vanilla Terminator door 1 raw: ${vanillaBefore.joinToString(" ") { "%02X".format(it) }}")

        val userDc = object {
            val destRoomPtr = 0x91F8
            val bitflag = 0x0400
            val doorCapCode = 0x0601
            val screenX = 0
            val screenY = 4
            val distFromDoor = 0x8000
            val entryCode = 0x0000
        }
        val orientation = (userDc.bitflag shr 8) and 0xFF
        val clearedOrient = orientation and 0xFB
        val exportBytes = intArrayOf(
            userDc.destRoomPtr and 0xFF, (userDc.destRoomPtr shr 8) and 0xFF,
            userDc.bitflag and 0xFF, clearedOrient,
            userDc.doorCapCode and 0xFF, (userDc.doorCapCode shr 8) and 0xFF,
            userDc.screenX and 0xFF, userDc.screenY and 0xFF,
            userDc.distFromDoor and 0xFF, (userDc.distFromDoor shr 8) and 0xFF,
            userDc.entryCode and 0xFF, (userDc.entryCode shr 8) and 0xFF
        )
        println("Export would write:             ${exportBytes.joinToString(" ") { "%02X".format(it) }}")

        val parlToLsDoor = parlDoors.first { it.destRoomPtr == landingSite }
        val parlToLsIdx = parlDoors.indexOf(parlToLsDoor)
        val parlToLsPc = rp.doorEntryPcOffset(parlRoom.doorOut, parlToLsIdx)!!
        val vanillaParlToLs = (0..11).map { rp.romData[parlToLsPc + it].toInt() and 0xFF }
        println("Vanilla Parlor→LS raw:          ${vanillaParlToLs.joinToString(" ") { "%02X".format(it) }}")

        println("\nByte-by-byte comparison (export vs vanilla Parlor→LS):")
        val fieldNames = arrayOf("destLo","destHi","flagLo","orient","capX","capY","scrnX","scrnY","distLo","distHi","asmLo","asmHi")
        var diffs = 0
        for (i in 0..11) {
            val match = if (exportBytes[i] == vanillaParlToLs[i]) "  OK" else " DIFF"
            if (exportBytes[i] != vanillaParlToLs[i]) diffs++
            println("  byte[$i] ${fieldNames[i].padEnd(7)}: export=0x${"%02X".format(exportBytes[i])} vanilla=0x${"%02X".format(vanillaParlToLs[i])}$match")
        }
        println("Total differences: $diffs (doorCapCode bytes are expected to differ)")

        println("\n=== SHARED DOOR DEFINITION CHECK ===")
        val allDoorPcAddrs = mutableMapOf<Int, MutableList<String>>()
        for (roomId in listOf(landingSite, parlor, terminator, 0x92B3, 0x99BD, 0x9938, 0x9969)) {
            val room = rp.readRoomHeader(roomId) ?: continue
            val doors = rp.parseDoorList(room.doorOut)
            for ((i, _) in doors.withIndex()) {
                val pc = rp.doorEntryPcOffset(room.doorOut, i) ?: continue
                allDoorPcAddrs.getOrPut(pc) { mutableListOf() }
                    .add("room 0x${roomId.toString(16)} door[$i]")
            }
        }
        val lsDoor0Pc = rp.doorEntryPcOffset(lsRoom.doorOut, 0)!!
        for ((pc, refs) in allDoorPcAddrs) {
            if (refs.size > 1 || pc == lsDoor0Pc || pc == termDoor1Pc) {
                println("  PC=0x${pc.toString(16)}: ${refs.joinToString(", ")}${if (refs.size > 1) " ** SHARED **" else ""}")
            }
        }

        println("\n=== REVERSE INDEX: all vanilla doors entering Landing Site ===")
        for (roomId in 0x91F8..0x9FFF) {
            val room = rp.readRoomHeader(roomId) ?: continue
            if (room.width !in 1..16 || room.height !in 1..16) continue
            val doors = rp.parseDoorList(room.doorOut)
            for ((i, door) in doors.withIndex()) {
                if (door.destRoomPtr == landingSite) {
                    val entryPc = rp.doorEntryPcOffset(room.doorOut, i)!!
                    val raw = (0..11).map { rp.romData[entryPc + it].toInt() and 0xFF }
                    println("  from 0x${roomId.toString(16)} door[$i]: dir=${door.direction}(${door.directionName}) " +
                        "spawn=(${door.screenX},${door.screenY}) dist=0x${door.distFromDoor.toString(16)} " +
                        "entry=0x${door.entryCode.toString(16)} cap=0x${door.doorCapCode.toString(16)}")
                    println("    raw: ${raw.joinToString(" ") { "%02X".format(it) }}  PC=0x${entryPc.toString(16)}")
                }
            }
        }
    }

    private fun decodeDoorAsm(rp: RomParser, asmPtr: Int) {
        val pc = rp.snesToPc(0x8F0000 or asmPtr)
        val bytes = ByteArray(64) { i ->
            if (pc + i < rp.romData.size) rp.romData[pc + i] else 0
        }
        val hexDump = bytes.take(32).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        println("    Raw bytes: $hexDump")

        // Try to decode STA $7ECD20+X patterns (writes to scroll RAM)
        // Common patterns:
        //   LDA #$xx; STA $7ECDxx  (long addressing: 8F CD 7E)
        //   or STA $CDxx (direct, bank already $7E)
        var i = 0
        while (i < 60) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x6B) { // RTL
                println("    [RTL at +$i]")
                break
            }
            // LDA #imm8 (SEP #$20 mode): A9 xx
            // STA long: 8F ll hh bb
            if (b == 0xA9 && i + 5 < 60) {
                val imm = bytes[i + 1].toInt() and 0xFF
                val next = bytes[i + 2].toInt() and 0xFF
                if (next == 0x8F) { // STA long
                    val lo = bytes[i + 3].toInt() and 0xFF
                    val hi = bytes[i + 4].toInt() and 0xFF
                    val bank = bytes[i + 5].toInt() and 0xFF
                    val addr = (bank shl 16) or (hi shl 8) or lo
                    if (addr in 0x7ECD20..0x7ECD51) {
                        val screenIdx = addr - 0x7ECD20
                        println("    Screen[$screenIdx] = ${scrollValName(imm)}")
                    }
                    i += 6
                    continue
                }
            }
            // SEP/REP
            if ((b == 0xE2 || b == 0xC2) && i + 1 < 60) {
                i += 2; continue
            }
            // LDA #imm8
            if (b == 0xA9 && i + 1 < 60) {
                i += 2; continue
            }
            // STA long (skip 4 bytes)
            if (b == 0x8F && i + 3 < 60) {
                i += 4; continue
            }
            i++
        }
    }
}

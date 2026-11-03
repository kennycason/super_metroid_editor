package com.supermetroid.editor.asm

import kotlin.test.*

/** Independent compiled/source contracts for PLM runtime comments; not a CPU/gameplay emulator. */
internal fun verifyAsmPlmRuntimeContracts(rom: ByteArray, symbols: Map<String, Int>, index: AsmReferenceIndex, names: AsmSourceSymbolNames) {
    fun address(name: String) = assertNotNull(symbols[name], name)
    fun loWord(value: Int) = listOf(value and 255, (value ushr 8) and 255)
    fun bytes(name: String, offset: Int, size: Int): List<Int> {
        val pc = assertNotNull(snesLoRomToPc(address(name) + offset))
        return rom.copyOfRange(pc, pc + size).map { it.toInt() and 255 }
    }
    fun word(name: String, offset: Int = 0): Int {
        val data = bytes(name, offset, 2)
        return data[0] or (data[1] shl 8)
    }

    for ((name, ram) in mapOf(
        "PLM_Flag" to 0x1C23, "PLM_Index" to 0x1C27, "PLM_XBlock" to 0x1C29, "PLM_YBlock" to 0x1C2B,
        "PLM_IDs" to 0x1C37, "PLM_BlockIndices" to 0x1C87, "PLM_PreInstructions" to 0x1CD7,
        "PLM_InstListPointers" to 0x1D27, "PLM_Timers" to 0x1D77, "PLM_RoomArgs" to 0x1DC7,
        "PLMExtra_InstructionTimers" to 0x7EDE1C, "PLMExtra_DrawInstructionPointers" to 0x7EDE6C,
        "PLMExtra_Vars" to 0x7EDF0C, "SRAMMirror_Items" to 0x7ED870,
    )) assertEquals(ram, address(name), name)
    assertEquals(listOf(0xA0, 0x4E, 0x00), bytes("Spawn_Room_PLM", 6, 3), "Room spawns scan forty even PLM slots")
    assertEquals(listOf(0xF4) + loWord(address("Process_PLM_loop") - 1), bytes("Process_PLM", 0x1D, 3),
        "PLM commands receive a synthetic near return to the fetch loop")
    assertEquals(listOf(0x68, 0x60), bytes("Instruction_PLM_Sleep", 6, 2), "Sleep discards that return before RTS")
    assertEquals(listOf(0x68, 0x60), bytes("Instruction_PLM_Delete", 3, 2), "Delete discards that return before RTS")
    val missileWords = listOf(
        "Instruction_PLM_GotoY_ifRoomArg_ItemIsCollected", "InstList_PLM_MissileTank_3",
        "Instruction_PLM_LinkInstruction_Y", "InstList_PLM_MissileTank_2",
        "Instruction_PLM_PreInstruction_inY", "PreInstruction_PLM_GotoLinkInstructionIfTriggered",
    )
    missileWords.forEachIndexed { slot, target ->
        assertEquals(address(target) and 0xFFFF, word("InstList_PLM_MissileTank_0", slot * 2), target)
    }

    fun rows(symbol: String, local: String? = null): List<String> {
        val root = index.labels.single { it.name == names.active(symbol) && it.localScope == null }
        val source = assertNotNull(index.file(root.fileId))
        val start = local?.let { name ->
            index.labels.single { it.fileId == root.fileId && it.localScope == root.name && it.name == name }.lineIndex
        } ?: root.lineIndex
        val labelLines = index.labels.filter { it.fileId == root.fileId }.map { it.lineIndex }.toSet()
        val end = (start + 1 until source.lines.size).firstOrNull { line ->
            source.scopePathByLine[line].firstOrNull() != root.name || (local != null && line in labelLines)
        } ?: source.lines.size
        return source.lines.subList(start + 1, end)
            .map { asmStatementSignature(names.canonicalCode(asmExecutableCode(it))) }.filter(String::isNotBlank)
    }
    fun signature(vararg code: String) = code.map(::asmStatementSignature)
    fun containsSequence(actual: List<String>, vararg code: String) {
        val expected = signature(*code)
        assertTrue(actual.windowed(expected.size).any { it == expected }, "Expected consecutive code: $expected")
    }

    val spawn = rows("Spawn_Room_PLM")
    containsSequence(spawn, "LDY.W #\$004E", ".loop:", "LDA.W PLM_IDs,Y", "BEQ .found", "DEY", "DEY", "BPL .loop")
    containsSequence(spawn, "LDA.L PLMPopulations_Y,X", "STA.W \$4202", "LDA.W RoomWidthBlocks", "STA.W \$4203",
        "LDA.L PLMPopulations_X,X", "REP #\$20", "AND.W #\$00FF", "CLC", "ADC.W \$4216", "ASL", "STA.W PLM_BlockIndices,Y")
    containsSequence(spawn, "STX.W PLM_Index", "TYX", "LDY.W PLM_Index", "JSR.W (\$0000,X)", "PLX", "PLY", "PLB", "PLP", "CLC", "RTL")
    val handler = rows("PLM_Handler")
    containsSequence(handler, "BIT.W PLM_Flag", "BPL .return", "STZ.W PLM_DrawTilemapIndex", "LDX.W #\$004E")
    containsSequence(handler, "STX.W PLM_Index", "LDA.W PLM_IDs,X", "BEQ .next", "JSR.W Process_PLM", "LDX.W PLM_Index")
    val process = rows("Process_PLM")
    containsSequence(process, "JSR.W (PLM_PreInstructions,X)", "LDX.W PLM_Index", "LDA.L PLMExtra_InstructionTimers,X",
        "DEC", "STA.L PLMExtra_InstructionTimers,X", "BNE .return", "LDY.W PLM_InstListPointers,X")
    containsSequence(process, ".loop:", "LDA.W \$0000,Y", "BPL .nonZeroTimer", "STA.B DP_Temp12", "INY", "INY",
        "PEA.W .loop-1", "JMP.W (DP_Temp12)")
    assertEquals(signature("STA.L PLMExtra_InstructionTimers,X", "LDA.W \$0002,Y", "STA.L PLMExtra_DrawInstructionPointers,X",
        "TYA", "CLC", "ADC.W #\$0004", "STA.W PLM_InstListPointers,X", "JSR.W Process_PLM_Draw_Instruction",
        "LDX.W PLM_Index", "JSL.L Calculate_PLM_Block_Coordinates", "JSR.W DrawPLM_HardCoded"), rows("Process_PLM", ".nonZeroTimer"))
    assertEquals(signature("DEY", "DEY", "TYA", "STA.W PLM_InstListPointers,X", "PLA", "RTS"), rows("Instruction_PLM_Sleep"))
    assertEquals(signature("STZ.W PLM_IDs,X", "PLA", "RTS"), rows("Instruction_PLM_Delete"))
    containsSequence(rows("Instruction_PLM_GotoY_ifRoomArg_ItemIsCollected"), "LDA.W PLM_RoomArgs,X", "BMI .pullX",
        "JSL.L BitIndexToByteIndexAndBitmask", "LDA.L SRAMMirror_Items,X", "PLX", "AND.W Bitmask", "BEQ .return",
        "JMP.W Instruction_PLM_GotoY")
    containsSequence(rows("Instruction_PLM_SetRoomArg_ItemCollected"), "LDA.W PLM_RoomArgs,X", "BMI .return",
        "JSL.L BitIndexToByteIndexAndBitmask", "LDA.L SRAMMirror_Items,X", "ORA.W Bitmask", "STA.L SRAMMirror_Items,X")
}

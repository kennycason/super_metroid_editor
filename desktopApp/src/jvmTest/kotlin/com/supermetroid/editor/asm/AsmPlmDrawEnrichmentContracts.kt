package com.supermetroid.editor.asm

import kotlin.test.*

/** Independent compiled/source contracts for PLM drawing comments; not a CPU or PPU emulator. */
internal fun verifyAsmPlmDrawContracts(rom: ByteArray, symbols: Map<String, Int>, index: AsmReferenceIndex, names: AsmSourceSymbolNames) {
    fun address(name: String) = assertNotNull(symbols[name], name)
    fun loWord(value: Int) = listOf(value and 255, (value ushr 8) and 255)
    fun loLong(value: Int) = loWord(value) + (value ushr 16 and 255)
    fun bytes(name: String, offset: Int, size: Int): List<Int> {
        val pc = assertNotNull(snesLoRomToPc(address(name) + offset))
        return rom.copyOfRange(pc, pc + size).map { it.toInt() and 255 }
    }

    assertEquals(0x7EC6C8, address("PLMDrawTilemap"))
    assertEquals(0x0330, address("VRAMWriteStack"))
    assertEquals(listOf(0xC2, 0x30, 0xA9, 0x00, 0x50, 0x85, 0x09, 0xA9, 0xE0, 0x53, 0x85, 0x0C),
        bytes("DrawPLM_HardCoded", 0, 12), "Hard-coded rendering uses BG1 bases ${'$'}5000 and ${'$'}53E0")
    assertEquals(listOf(0xB9, 0x00, 0x00, 0x30, 0x19, 0x29, 0xFF, 0x00),
        bytes("Process_PLM_Draw_Instruction_loopDrawEntry", 0, 8), "Bit 15 selects a column; the low byte is the count")
    assertEquals(listOf(0xB9, 0x00, 0x00, 0xD0, 0x01, 0x60, 0x88, 0xB9, 0x00, 0x00, 0xEB, 0x10, 0x05),
        bytes("Process_PLM_Draw_Instruction_next", 0, 13), "A zero offset pair terminates; X is read through an overlapping signed-byte load")
    assertEquals(listOf(0x09, 0x00, 0xFF), bytes("Process_PLM_Draw_Instruction_next", 13, 3), "Negative X is sign-extended")
    assertEquals(listOf(0x09, 0x00, 0xFF, 0x49, 0xFF, 0xFF, 0x1A),
        bytes("Process_PLM_Draw_Instruction", 0x67, 7), "Negative Y is sign-extended and converted to a magnitude")
    assertEquals(listOf(0xB9, 0x00, 0x00, 0x9F) + loLong(address("LevelData")) + listOf(0xC8, 0xC8, 0xE8, 0xE8),
        bytes("Process_PLM_Draw_Instruction_loopRow", 0, 11), "Rows replace consecutive level words")
    assertEquals(listOf(0xB9, 0x00, 0x00, 0x9F) + loLong(address("LevelData")) + listOf(0xC8, 0xC8, 0x8A, 0x18, 0x6D) +
        loWord(address("RoomWidthBlocks")) + listOf(0x6D) + loWord(address("RoomWidthBlocks")) + listOf(0xAA),
        bytes("Process_PLM_Draw_Instruction_loopColumn", 0, 18), "Columns advance by two room-width byte counts")
    assertEquals(listOf(0xB2, 0x03, 0x85, 0x1E, 0x29, 0xFF, 0x03, 0x0A, 0x0A, 0x0A, 0xAA,
        0xA5, 0x1E, 0x29, 0x00, 0x0C), bytes("DrawPLM_loopHorizBlock", 0, 16))
    assertEquals(bytes("DrawPLM_loopHorizBlock", 0, 16), bytes("DrawPLM_loopVertBlock", 0, 16),
        "Both orientations mask the ten-bit metatile index, multiply by eight, and isolate flip bits")
    for ((symbol, offset, mask) in listOf(
        Triple("DrawPLM_horizCopyBlockWithFlip", 9, 0x4000),
        Triple("DrawPLM_horizCopyBlockWithVertFlip", 9, 0x8000),
        Triple("DrawPLM_horizCopyBlockWithBothFlips", 4, 0xC000),
        Triple("DrawPLM_vertCopyBlockWithFlip", 9, 0x4000),
        Triple("DrawPLM_vertCopyBlockWithVertFlip", 9, 0x8000),
        Triple("DrawPLM_vertCopyBlockWithBothFlips", 4, 0xC000),
    )) assertEquals(listOf(0x49) + loWord(mask), bytes(symbol, offset, 3), symbol)
    val advance = listOf(0xC8, 0xC8, 0xE6, 0x03, 0xE6, 0x03, 0xAD) + loWord(address("PLM_DrawTilemapIndex")) +
        listOf(0x18, 0x69, 0x08, 0x00, 0x8D) + loWord(address("PLM_DrawTilemapIndex")) + listOf(0xC9, 0x00, 0x02)
    assertEquals(advance, bytes("DrawPLM_horizNextBlock", 0, advance.size))
    assertEquals(advance, bytes("DrawPLM_vertNextBlock", 0, advance.size))
    assertEquals(listOf(0xA9) + loWord(address("PLMDrawTilemap")),
        bytes("PartiallySetupVRAMWriteTableEntries_SingleScrnPLMDrawTilemap", 12, 3))
    assertEquals(listOf(0x0A, 0x0A), bytes("PartiallySetupVRAMWriteTableEntries_SingleScrnPLMDrawTilemap", 6, 2),
        "Each VRAM record receives four bytes per metatile")
    assertEquals(listOf(0xE2, 0x20, 0xA9, 0x7E),
        bytes("PartiallySetupVRAMWriteTableEntries_SingleScrnPLMDrawTilemap", 30, 4), "Staging sources use WRAM bank ${'$'}7E")

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

    val draw = rows("DrawPLM")
    containsSequence(draw, "LDA.W Layer1YPosition", "LSR", "LSR", "LSR", "LSR", "STA.B DP_Temp1A", "CLC",
        "ADC.W #${'$'}000F", "CMP.B DP_Temp20", "BPL +", "RTS")
    containsSequence(draw, "AND.W #${'$'}7FFF", "STA.B DP_Temp14", "STZ.B DP_Temp1C", "LDA.B DP_Temp20",
        "CMP.B DP_Temp1A", "BMI .return8E2F", "STA.B DP_Temp1A", "STZ.B DP_Temp12")
    containsSequence(draw, "LDA.W Layer1XPosition", "CLC", "ADC.W #${'$'}000F", "LSR", "LSR", "LSR", "LSR", "DEC", "STA.B DP_Temp16")
    containsSequence(draw, "+ LDA.B DP_Temp16", "CLC", "ADC.W #${'$'}0011", "STA.B DP_Temp16", "CMP.B DP_Temp1E", "BPL +")
    containsSequence(draw, ".vertical:", "AND.W #${'$'}7FFF", "STA.B DP_Temp14", "LDA.W Layer1XPosition",
        "LSR", "LSR", "LSR", "LSR", "CMP.B DP_Temp1E", "BEQ +", "BPL .return903B")
    containsSequence(draw, "LDA.B DP_Temp1A", "CLC", "ADC.W #${'$'}0010", "STA.B DP_Temp16", "LDA.B DP_Temp1A",
        "SEC", "SBC.B DP_Temp20", "BMI .drawBlockGreaterThanScreenBlock")
    containsSequence(draw, "LDA.W #${'$'}8000", "STA.B DP_Temp1C", "JSR.W Calculate_PLMDrawTilemap_VRAMDestination",
        "LDA.B VRAMWrite.dest,X", "INC", "STA.B VRAMWrite[1].dest,X")
    containsSequence(draw, ".horizOneScreen:", "PLA", "JSR.W PartiallySetupVRAMWriteTableEntries_SingleScrnPLMDrawTilemap",
        "LDA.B VRAMWrite.dest,X", "CLC", "ADC.W #${'$'}0020", "STA.B VRAMWrite[1].dest,X")

    val destination = rows("Calculate_PLMDrawTilemap_VRAMDestination")
    containsSequence(destination, "LDA.B DP_Temp1A", "AND.W #${'$'}000F", "ORA.W #${'$'}4000", "STA.W ${'$'}4202",
        "LDA.B DP_Temp18", "AND.W #${'$'}001F", "CMP.W #${'$'}0010", "BCS .greaterThanF")
    containsSequence(destination, "LDA.W BG1XOffset", "AND.W #${'$'}0100", "BEQ .fallthrough", "PLA", "CLC",
        "ADC.W #${'$'}0400", "PHA", "BRA .fallthrough")
    containsSequence(destination, ".greaterThanF:", "ASL", "CLC", "ADC.B DP_Temp0C", "ADC.W ${'$'}4216", "PHA",
        "LDA.W BG1XOffset", "AND.W #${'$'}0100", "BEQ .fallthrough", "PLA", "SEC", "SBC.W #${'$'}0400", "PHA")
    assertEquals(signature("ORA.B DP_Temp1C", "STA.B VRAMWrite.dest,X", "LDA.B DP_Temp14", "ASL", "ASL",
        "STA.B VRAMWrite.size,X", "STA.B VRAMWrite[1].size,X", "LDA.W #PLMDrawTilemap", "CLC",
        "ADC.W PLM_DrawTilemapIndex", "STA.B VRAMWrite.src,X", "STA.B DP_Temp00", "CLC", "ADC.B VRAMWrite.size,X",
        "STA.B VRAMWrite[1].src,X", "STA.B DP_Temp06", "SEP #${'$'}20", "LDA.B #${'$'}7E", "STA.B VRAMWrite.src+2,X",
        "STA.B VRAMWrite[1].src+2,X", "STA.B DP_Temp02", "STA.B DP_Temp08", "REP #${'$'}20", "RTS",
        "if !FEATURE_KEEP_UNREFERENCED"),
        rows("PartiallySetupVRAMWriteTableEntries_SingleScrnPLMDrawTilemap"))
}

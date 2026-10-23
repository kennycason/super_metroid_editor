package com.supermetroid.editor.asm

import kotlin.test.*

/** Independent compiled/source contracts for scheduling and instruction comments; not a CPU/gameplay emulator. */
internal fun verifyAsmEnemyRuntimeContracts(rom: ByteArray, symbols: Map<String, Int>, index: AsmReferenceIndex, names: AsmSourceSymbolNames) {
    fun address(name: String) = assertNotNull(symbols[name], name)
    fun loWord(value: Int) = listOf(value and 255, (value ushr 8) and 255)
    fun bytes(name: String, offset: Int, size: Int): List<Int> {
        val pc = assertNotNull(snesLoRomToPc(address(name) + offset))
        return rom.copyOfRange(pc, pc + size).map { it.toInt() and 255 }
    }
    for (layer in 0..7) assertEquals(loWord(address("EnemyDrawingQueues_Layer$layer")), bytes("AddressesForEnemyDrawingQueues", layer * 2, 2))
    for ((name, ram) in mapOf("ActiveEnemyIndicesStackPointer" to 0x17A4, "InteractiveEnemyIndicesStackPointer" to 0x17A6,
        "ActiveEnemyIndicesIndex" to 0x17A8, "InteractiveEnemyIndicesIndex" to 0x17AA, "ActiveEnemyIndices" to 0x17AC,
        "InteractiveEnemyIndices" to 0x17EC, "EnemyDrawingQueues_Sizes" to 0x0F68)) assertEquals(ram, address(name), name)
    for (header in listOf("EnemyHeaders_Zoomer", "EnemyHeaders_Waver")) {
        for ((offset, member) in listOf(0x18 to "mainAI", 0x1A to "grappleAI", 0x1C to "hurtAI", 0x1E to "frozenAI", 0x20 to "timeIsFrozen"))
            assertEquals(address(header) + offset, address("${header}_$member"))
        assertEquals(0xA3, bytes(header, 0x0C, 1).single(), "Commands in these enemies' scripts execute in bank A3")
    }
    assertEquals(listOf(0x4B, 0xF4) + loWord(address("ProcessEnemyInstructions_loop") - 1) + listOf(0xDC) + loWord(address("EnemyAIPointer")),
        bytes("ProcessEnemyInstructions", 0x24, 7), "PHK/PEA supplies a long loop return for command RTL")
    assertEquals(listOf(0xAB, 0x60), bytes("ProcessEnemyInstructions_return", 0, 2), "Terminating commands exit through PLB/RTS")
    val aiHelper = address("Main_Enemy_Routine_executeAI")
    assertEquals(listOf(0x22) + loWord(aiHelper) + listOf(aiHelper ushr 16), bytes("Main_Enemy_Routine", 0xBB, 4))
    assertEquals(listOf(0xAE) + loWord(address("EnemyIndex")) + listOf(0xBD) + loWord(address("Enemy.bank")) +
        listOf(0x8D) + loWord(address("EnemyAIPointer") + 2) + listOf(0xEB, 0x48, 0xAB, 0xAB, 0xDC) + loWord(address("EnemyAIPointer")),
        bytes("Main_Enemy_Routine_executeAI", 0, 16), "AI bank selection and indirect tail jump preserve the prior JSL frame")
    val commandLengths = mapOf("GotoY" to 5, "TimerInY" to 9, "DecrementTimer_GotoYIfNonZero" to 8,
        "Sleep" to 11, "WaitYFrames" to 17, "DeleteEnemy" to 14)
    for ((suffix, length) in commandLengths) {
        val a0 = "Instruction_Common_$suffix"
        val a3 = "Instruction_CommonA3_$suffix"
        assertEquals(address(a0) and 0xFFFF, address(a3) and 0xFFFF, suffix)
        assertEquals(bytes(a0, 0, length), bytes(a3, 0, length), "The reviewed bank-local copies are byte-identical: $suffix")
    }
    for ((suffix, offset) in listOf("Sleep" to 6, "WaitYFrames" to 12, "DeleteEnemy" to 9)) {
        for (prefix in listOf("Instruction_Common_", "Instruction_CommonA3_"))
            assertEquals(listOf(0x68, 0xF4) + loWord(address("ProcessEnemyInstructions_return") - 1) + listOf(0x6B),
                bytes(prefix + suffix, offset, 5), "Replace only the synthetic word, preserving its A0 bank byte: $prefix$suffix")
    }

    fun rows(symbol: String, local: String? = null): List<String> {
        val root = index.labels.single { it.name == names.active(symbol) && it.localScope == null }
        val source = assertNotNull(index.file(root.fileId))
        val start = local?.let { name -> index.labels.single { it.fileId == root.fileId && it.localScope == root.name && it.name == name }.lineIndex }
            ?: root.lineIndex
        val labelLines = index.labels.filter { it.fileId == root.fileId }.map { it.lineIndex }.toSet()
        val end = (start + 1 until source.lines.size).firstOrNull { line ->
            source.scopePathByLine[line].firstOrNull() != root.name || (local != null && line in labelLines)
        } ?: source.lines.size
        return source.lines.subList(start + 1, end).map { asmStatementSignature(names.canonicalCode(asmExecutableCode(it))) }.filter(String::isNotBlank)
    }
    fun signature(vararg code: String) = code.map(::asmStatementSignature)
    fun containsSequence(actual: List<String>, vararg code: String) {
        val expected = signature(*code)
        assertTrue(actual.windowed(expected.size).any { it == expected }, "Expected consecutive code: $expected")
    }
    val selection = rows("Determine_Which_Enemies_to_Process")
    containsSequence(selection, "STZ.W EnemyIndex", "STZ.W ActiveEnemyIndicesStackPointer", "STZ.W InteractiveEnemyIndicesStackPointer",
        "LDA.W GlobalOffScreenEnemyProcessingFlag", "BEQ .resetIndex", "JMP.W .loopProcessOffscreen")
    containsSequence(selection, "LDA.W Enemy.properties,X", "BIT.W #\$0200", "BEQ ..notDeleted", "STZ.W Enemy.ID,X", "JMP.W .next")
    containsSequence(selection, "BIT.W #\$0800", "BNE .activeEnemies", "LDA.W Enemy.AI,X", "BIT.W #\$0004", "BNE .activeEnemies")
    containsSequence(selection, "BIT.W #\$0400", "BNE .next", "TXA", "LDY.W InteractiveEnemyIndicesStackPointer", "STA.W InteractiveEnemyIndices,Y")
    assertEquals(signature("LDA.W EnemyIndex", "CLC", "ADC.W #\$0040", "STA.W EnemyIndex", "CMP.W #\$0800", "BPL .done", "JMP.W .loop"),
        rows("Determine_Which_Enemies_to_Process", ".next"))
    val override = selection.dropWhile { it != asmStatementSignature(".loopProcessOffscreen:") }.drop(1)
    assertTrue(override.isNotEmpty())
    assertTrue(override.none { "Layer1" in it || "HitboxRadius" in it })
    // The global override's extra interaction-array store is deliberate preservation, not an inferred cleanup.
    containsSequence(selection, "STA.W ActiveEnemyIndices,Y", "STA.W InteractiveEnemyIndices,Y", "INY", "INY", "STY.W ActiveEnemyIndicesStackPointer",
        "LDA.W Enemy.properties,X", "BIT.W #\$0400", "BNE .nextProcessOffscreen")
    containsSequence(rows("Determine_Which_Enemies_to_Process", ".done"), "LDA.W #\$FFFF", "LDY.W ActiveEnemyIndicesStackPointer",
        "STA.W ActiveEnemyIndices,Y", "LDY.W InteractiveEnemyIndicesStackPointer", "STA.W InteractiveEnemyIndices,Y")
    val main = rows("Main_Enemy_Routine")
    containsSequence(main, "LDA.W FirstFreeEnemyIndex", "BNE .processEnemies", "JMP.W .return")
    assertTrue(rows("Main_Enemy_Routine", ".return").first() == asmStatementSignature("JSL.L HandleSpriteObjects"))
    containsSequence(main, "BIT.W #\$0400", "BNE .interactEnd", "LDA.W Enemy.invincibilityTimer,X", "BEQ .notInvincible",
        "DEC.W Enemy.invincibilityTimer,X", "JMP.W .interactEnd")
    assertEquals(signature("LDX.W EnemyIndex", "LDA.W Enemy.properties2,X", "BIT.W #\$0001", "BNE .processAIEnd"), rows("Main_Enemy_Routine", ".checkParalyzed"))
    containsSequence(main, "LDA.W \$0020,Y", "BEQ .processAIEnd", "STA.W EnemyAIPointer", "BRA .determineAIEnd")
    assertEquals(signature("LDX.W #\$0000", "LDY.W EnemyIndex", "LDA.W Enemy.AI,Y", "BEQ +"), rows("Main_Enemy_Routine", ".timeNotFrozen"))
    containsSequence(main, ".loopBitIndex:", "INX", "LSR", "BCS +", "BRA .loopBitIndex", "+ TXA", "ASL", "CLC", "ADC.W Enemy.ID,Y", "TAX",
        "LDA.W \$0018,X", "STA.W EnemyAIPointer")
    containsSequence(main, "BNE .processAIEnd", "LDX.W EnemyIndex", "INC.W Enemy.frameCounter,X", "LDA.W Enemy.properties,X", "BIT.W #\$2000", "BEQ .processAIEnd")
    containsSequence(main, "JSL.L CheckIfEnemyIsOnScreen", "BEQ +", "BRA .drawEnemyEnd")
    containsSequence(main, "BIT.W #\$0300", "BNE .drawEnemyEnd", "LDA.W DisableDrawingOfEnemies", "BIT.W #\$0001", "BNE .drawEnemyEnd", "JSR.W AddEnemyToDrawingQueue")
    containsSequence(main, "DEC.W Enemy.flashTimer,X", "LDA.W Enemy.flashTimer,X", "CMP.W #\$0008", "BPL +", "LDA.W Enemy.AI,X", "AND.W #\$FFFD", "STA.W Enemy.AI,X")
    val interpreter = rows("ProcessEnemyInstructions")
    assertEquals(signature("PHB", "LDX.W EnemyIndex", "LDA.W Enemy.AI,X", "AND.W #\$0004", "BNE .return", "DEC.W Enemy.instTimer,X",
        "BNE .noUpdate", "LDA.W Enemy.bank-1,X", "PHA", "PLB", "PLB", "LDY.W Enemy.instList,X"), interpreter.take(12))
    assertTrue(interpreter.none { it == asmStatementSignature("STA.W EnemyAIPointer+2") }, "Command bank is supplied by the caller")
    assertEquals(signature("LDA.W \$0000,Y", "BPL .timer", "STA.W EnemyAIPointer", "INY", "INY", "PHK", "PEA.W .loop-1", "JML.W [EnemyAIPointer]"),
        rows("ProcessEnemyInstructions", ".loop"))
    assertEquals(signature("STA.W Enemy.instTimer,X", "LDA.W \$0002,Y", "STA.W Enemy.spritemap,X", "TYA", "CLC", "ADC.W #\$0004", "STA.W Enemy.instList,X",
        "LDA.W Enemy.properties2,X", "ORA.W #\$8000", "STA.W Enemy.properties2,X"), rows("ProcessEnemyInstructions", ".timer"))
    assertEquals(signature("LDA.W Enemy.properties2,X", "AND.W #\$7FFF", "STA.W Enemy.properties2,X", "PLB", "RTS"), rows("ProcessEnemyInstructions", ".noUpdate"))
    val visibility = rows("CheckIfEnemyIsOnScreen")
    assertTrue(visibility.none { "YHitboxRadius" in it })
    containsSequence(visibility, "ADC.W #\$0008", "CMP.W Layer1YPosition", "BMI .offScreen", "LDA.W Layer1YPosition", "CLC", "ADC.W #\$00F8")
    containsSequence(visibility, "ADC.W #\$0100", "CLC", "ADC.W Enemy.XHitboxRadius,X", "CMP.W Enemy.XPosition,X", "BMI .offScreen")
    containsSequence(rows("AddEnemyToDrawingQueue"), "LDA.W AddressesForEnemyDrawingQueues,Y", "CLC", "ADC.W EnemyDrawingQueues_Sizes,Y", "TAY",
        "LDA.W EnemyIndex", "STA.W \$0000,Y", "LDX.W Temp_DrawingQueueIndex0E34", "INC.W EnemyDrawingQueues_Sizes,X", "INC.W EnemyDrawingQueues_Sizes,X")
    for (prefix in listOf("Instruction_Common_", "Instruction_CommonA3_")) {
        assertEquals(signature("DEY", "DEY", "TYA", "STA.W Enemy.instList,X", "PLA", "PEA.W ProcessEnemyInstructions_return-1", "RTL"), rows(prefix + "Sleep"))
        assertEquals(signature("LDA.W \$0000,Y", "STA.W Enemy.instTimer,X", "INY", "INY", "TYA", "STA.W Enemy.instList,X", "PLA",
            "PEA.W ProcessEnemyInstructions_return-1", "RTL"), rows(prefix + "WaitYFrames"))
        assertEquals(signature("LDA.W Enemy.properties,X", "ORA.W #\$0200", "STA.W Enemy.properties,X", "PLA", "PEA.W ProcessEnemyInstructions_return-1", "RTL"), rows(prefix + "DeleteEnemy"))
    }
}

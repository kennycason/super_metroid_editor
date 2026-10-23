package com.supermetroid.editor.asm

import kotlin.test.*

/** Pin room/state records and the actual loader contracts; this is not a CPU/gameplay emulator. */
internal fun verifyAsmRoomLoadingContracts(rom: ByteArray, symbols: Map<String, Int>, index: AsmReferenceIndex, names: AsmSourceSymbolNames) {
    fun address(name: String) = assertNotNull(symbols[name], name)
    fun loWord(value: Int) = listOf(value and 255, (value ushr 8) and 255)
    fun bytes(name: String, offset: Int, size: Int): List<Int> {
        val pc = assertNotNull(snesLoRomToPc(address(name) + offset))
        return rom.copyOfRange(pc, pc + size).map { it.toInt() and 255 }
    }
    fun word(name: String, offset: Int = 0) = bytes(name, offset, 2).let { it[0] or (it[1] shl 8) }
    fun long(name: String, offset: Int = 0) = bytes(name, offset, 3).let { it[0] or (it[1] shl 8) or (it[2] shl 16) }
    val room = "RoomHeader_LandingSite"
    assertEquals(listOf(0, 0, 0x17, 0, 9, 5, 0x70, 0xA0, 0), bytes(room, 0, 9))
    assertEquals(address("RoomDoors_LandingSite") and 0xFFFF, word(room, 9))
    val checks = loWord(address("RoomStateCheck_EventHasBeenSet")) + listOf(0x0E) + loWord(address("RoomState_LandingSite_3")) +
        loWord(address("RoomStateCheck_PowerBombs")) + loWord(address("RoomState_LandingSite_2")) +
        loWord(address("RoomStateCheck_EventHasBeenSet")) + listOf(0) + loWord(address("RoomState_LandingSite_1")) +
        loWord(address("Use_StatePointer_inX"))
    assertEquals(checks, bytes(room, 11, checks.size), "Condition payloads have different lengths and preserve priority order")
    assertEquals(address(room) + 11 + checks.size, address("RoomState_LandingSite_0"), "The default state is inline, not an extra pointer")
    assertEquals(0x1A, address("RoomState_LandingSite_1") - address("RoomState_LandingSite_0"))
    val state = "RoomState_LandingSite_0"
    assertEquals(address("LevelData_LandingSite"), long(state))
    assertEquals(listOf(0, 6, 5), bytes(state, 3, 3), "Tileset and two distinct music bytes")
    for ((offset, target) in mapOf(6 to "FXHeader_LandingSite_State0_1", 8 to "EnemyPopulations_LandingSite_0_1_2",
        10 to "EnemySets_LandingSite_0_1_2", 14 to "RoomScrolls_LandingSite", 18 to "MainASM_ScrollingSkyLand",
        20 to "PLMPopulation_LandingSite_State0_1_2", 22 to "LibBG_ScrollingSky_Tilemaps_LandingSite", 24 to "SetupASM_ScrollingSkyLand"))
        assertEquals(address(target) and 0xFFFF, word(state, offset), "State field at $offset")
    assertEquals(0x0181, word(state, 12), "Layer-2 scroll controls occupy adjacent bytes")
    val population = "EnemyPopulations_LandingSite_0_1_2"
    for ((slot, header) in listOf("EnemyHeaders_ShipTop", "EnemyHeaders_ShipBottomEntrance", "EnemyHeaders_ShipBottomEntrance").withIndex()) {
        val offset = slot * 0x10
        assertEquals(address(header) and 0xFFFF, word(population, offset))
        assertEquals(0x0480, word(population, offset + 2))
        assertEquals(0x0478, word(population, offset + 4))
        assertEquals(0, word(population, offset + 6), "Generic init word is separate from parameter words")
        assertEquals(0x2400, word(population, offset + 8))
        assertEquals(0, word(population, offset + 10))
        assertEquals(0, word(population, offset + 12))
        assertEquals(if (slot == 2) 1 else 0, word(population, offset + 14))
    }
    assertEquals(listOf(255, 255, 0), bytes(population, 3 * 0x10, 3), "Terminator word followed by a required-kill byte")
    val plms = "PLMPopulation_LandingSite_State0_1_2"
    assertEquals(loWord(address("PLMEntries_ScrollPLM")) + listOf(0x1E, 0x28) + loWord(address("RoomPLM_LandingSite_0")), bytes(plms, 0, 6))
    assertEquals(0, word(plms, 6 * 6), "Six six-byte PLM entries precede the zero-ID terminator")

    assertEquals(listOf(0xF4) + loWord(address("Room_State_Checking_Handler_loop") - 1) + listOf(0x6C) + loWord(address("EventPointer")),
        bytes("Room_State_Checking_Handler", 0x0E, 6))
    assertEquals(listOf(0x8E) + loWord(address("RoomStatePointer")) + listOf(0x68, 0x6B), bytes("Use_StatePointer_inX", 0, 5),
        "STX selects the header address; PLA discards the synthetic RTS frame before RTL")
    assertEquals(listOf(0xFC, 0x18, 0), bytes("Execute_Room_Setup_ASM", 0x0E, 3), "JSR (state + $18,X) is bank-local")
    assertEquals(listOf(0xF4) + loWord(address("Execute_Door_ASM_return") - 1) + listOf(0x6C) + loWord(address("DP_Temp12")),
        bytes("Execute_Door_ASM", 0x11, 6))
    assertEquals(listOf(0xFC) + loWord(address("RoomMainASMPointer")), bytes("Execute_Room_Main_ASM", 0x0B, 3))
    assertEquals(listOf(0xB9) + loWord(address("Enemy.bank")) + listOf(0xEB, 0x48, 0xAB, 0xAB, 0xDC) + loWord(address("EnemyAIPointer")),
        bytes("Initialise_Enemies_executeInitAI", 0, 10), "Select AI data bank, then tail-jump using the prior JSL return")
    val initHelper = address("Initialise_Enemies_executeInitAI")
    assertEquals(listOf(0x22) + loWord(initHelper) + listOf(initHelper ushr 16), bytes("Initialise_Enemies", 0xEE, 4))
    for ((symbol, expected) in mapOf("Enemy.ID" to 0x0F78, "Enemy.health" to 0x0F8C, "Enemy.bank" to 0x0FA6,
        "Enemy.init0" to 0x0FB4, "Enemy.init1" to 0x0FB6, "EnemyAIPointer" to 0x1784, "EnemyTileData" to 0x7E7000))
        assertEquals(expected, address(symbol), symbol)

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
        assertTrue(actual.windowed(expected.size).any { it == expected }, "Expected consecutive instructions: $expected")
    }
    fun calls(symbol: String) = rows(symbol).mapNotNull { Regex("(?:JSL\\.L|JSR\\.W)(.+)").find(it)?.groupValues?.get(1) }
    containsSequence(rows("Room_State_Checking_Handler"), "TXA", "CLC", "ADC.W #\$000B", "TAX", ".loop:", "LDA.W \$0000,X",
        "STA.W EventPointer", "INX", "INX", "PEA.W .loop-1", "JMP.W (EventPointer)")
    assertEquals(signature("LDA.W \$0000,X", "AND.W #\$00FF", "JSL.L CheckIfEvent_inA_HasHappened", "BCC +", "LDA.W \$0001,X",
        "TAX", "JMP.W Use_StatePointer_inX", "+ INX", "INX", "INX", "RTS"), rows("RoomStateCheck_EventHasBeenSet"))
    val stateLoader = rows("Load_State_Header")
    containsSequence(stateLoader, "LDA.W \$0001,X", "STA.W LevelDataPointer+1", "LDA.W \$0000,X", "STA.W LevelDataPointer")
    containsSequence(stateLoader, "LDA.W \$0008,X", "STA.W EnemyPopulationPointer", "LDA.W \$000A,X", "STA.W EnemySetPointer")
    containsSequence(stateLoader, "LDA.W \$000C,X", "STA.W Layer2ScrollX", "LDA.W \$0012,X", "STA.W RoomMainASMPointer")
    val headerLoader = rows("Load_Room_Header")
    containsSequence(headerLoader, "JSL.L Room_State_Checking_Handler", "SEP #\$20", "LDA.W RoomWidthBlocks", "STA.W \$4202",
        "LDA.W RoomHeightBlocks", "STA.W \$4203", "REP #\$20", "NOP", "NOP", "NOP", "LDA.W \$4216", "ASL", "STA.W LevelDataSize")
    assertEquals(listOf("Spawn_Room_PLM", "Execute_Door_ASM", "Execute_Room_Setup_ASM"),
        calls("CreatePLMs_ExecuteDoorASM_RoomSetupASM_SetElevatorStatus"))
    containsSequence(rows("CreatePLMs_ExecuteDoorASM_RoomSetupASM_SetElevatorStatus"), "JSL.L Spawn_Room_PLM", "TXA", "CLC", "ADC.W #\$0006", "TAX", "BRA .loop")
    val main = rows("Execute_Room_Main_ASM")
    assertTrue(main.none { it == "PHP" || it == "PLP" || it.startsWith("REP") || it.startsWith("SEP") })
    assertEquals(signature("STA.L Enemy.ID,X", "INX", "DEY", "INX", "DEY", "BNE .loop"), rows("ClearEnemyData_ProcessEnemySet", ".loop").take(6))
    assertEquals(signature("STA.L EnemyTileData,X", "INX", "INX", "DEY", "BNE .loopExtraEnemyRAM"), rows("Initialise_Enemies", ".loopExtraEnemyRAM").take(5))
    assertEquals(signature("PHP", "PHB", "LDY.W #\$1400", "LDX.W #\$0000", "LDA.W #\$0000"), rows("Initialise_Enemies").take(5),
        "The $1400 counter counts word stores, and register width is an entry precondition")
    val initialise = rows("Initialise_Enemies")
    containsSequence(initialise, "LDA.W \$0004,X", "STA.W Enemy.health,Y")
    containsSequence(initialise, "LDA.L EnemyPopulations_init,X", "STA.W Enemy.instList,Y")
    containsSequence(rows("InitAI_Zoomer_MZoomer"), "LDX.W EnemyIndex", "LDA.W Enemy.instList,X", "AND.W #\$0003", "ASL", "TAY")
    containsSequence(initialise, "LDA.L EnemyPopulations_param1,X", "STA.W Enemy.init0,Y", "LDA.L EnemyPopulations_param2,X", "STA.W Enemy.init1,Y")
    containsSequence(initialise, "PHX", "PHY", "STY.W EnemyIndex", "LDX.W Enemy.ID,Y", "LDA.W \$0012,X", "STA.W EnemyAIPointer",
        "LDA.W \$000C,X", "STA.W EnemyAIPointer+2", "PHB", "JSL.L .executeInitAI", "PLB", "PLY", "PLX", "LDA.W #\$0000", "STA.W Enemy.spritemap,Y")
    containsSequence(initialise, "TYA", "CLC", "ADC.W #\$0040", "TAY", "TXA", "CLC", "ADC.W #\$0010", "TAX")
    containsSequence(initialise, "LDA.L EnemyPopulations_XPosition,X", "AND.W #\$00FF", "STA.W NumberOfEnemiesRequiredToKill")
    val loading = "DoorTransitionFunction_LoadSpritesBGPLMsAudio_RunDoorRoomASM"
    assertEquals(listOf("Load_EnemyGFX_to_VRAM", "Queue_Room_Music_Data", "Clear_Enemy_Projectiles", "Clear_AnimatedTilesObjects",
        "Clear_PaletteFXObjects", "Clear_PLMs", "CreatePLMs_ExecuteDoorASM_RoomSetupASM_SetElevatorStatus", "Load_FX_Header",
        "Spawn_Door_Closing_PLM", "Update_Beam_Tiles_and_Palette", "Load_Target_Colors_for_Common_SpritesBeamsFlashingEnemies",
        "Load_Enemies", "Initialise_Enemies", "Reset_Projectile_Data", "LoadSamusSuitTargetPalette"), calls(loading).take(15))
    assertEquals(signature("BIT.W DoorTransitionVRAM_Flag", "BMI .waitVRAM"), rows(loading, ".waitVRAM").take(2))
    containsSequence(rows(loading, ".waitDoor"), "LDA.W DoorTransitionFinishScrolling", "BPL .waitDoor")
    containsSequence(rows(loading, ".waitDoor"), "JSL.L PLM_Handler", "JSL.L WaitForNMI", "LDA.W DoorDirection")
}

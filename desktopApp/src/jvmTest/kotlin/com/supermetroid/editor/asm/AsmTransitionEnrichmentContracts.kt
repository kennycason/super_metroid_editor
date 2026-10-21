package com.supermetroid.editor.asm

import kotlin.test.*

/** Independent source/compiled contracts for the pause and door commentary, not a CPU or gameplay emulator. */
internal fun verifyAsmTransitionContracts(rom: ByteArray, symbols: Map<String, Int>, index: AsmReferenceIndex, names: AsmSourceSymbolNames) {
    fun address(name: String) = assertNotNull(symbols[name], name)
    fun loWord(value: Int) = listOf(value and 255, (value ushr 8) and 255)
    fun bytes(name: String, offset: Int, size: Int): List<Int> {
        val pc = assertNotNull(snesLoRomToPc(address(name) + offset))
        return rom.copyOfRange(pc, pc + size).map { it.toInt() and 255 }
    }
    val pages = listOf("PauseMenu_0_MapScreen", "PauseScreen_1_EquipmentScreen",
        "PauseMenu_2_MapScreenToEquipmentScreen_FadingOut", "PauseMenu_3_MapScreenToEquipmentScreen_LoadEquipmentScreen",
        "PauseMenu_4_MapScreenToEquipmentScreen_FadingIn", "PauseMenu_5_EquipmentScreenToMapScreen_FadingOut",
        "PauseMenu_6_EquipmentScreenToMapScreen_LoadMapScreen", "PauseMenu_7_EquipmentScreenToMapScreen_FadingIn")
    pages.forEachIndexed { slot, page ->
        assertEquals(loWord(address(page)), bytes("MainPauseRoutine_pointers", slot * 2, 2), "Pause page $slot")
    }
    assertEquals(listOf(0xFC) + loWord(address("MainPauseRoutine_pointers")), bytes("MainPauseRoutine", 11, 3))
    for ((entry, offset, hook) in listOf(
        Triple("GameState_D_Pausing_LoadingPauseScreen", 0x1C, "PauseHook_Pause"),
        Triple("GameState_11_Unpausing_LoadingNormalGameplay", 0x1E, "PauseHook_Unpause"),
    )) {
        assertEquals(listOf(0x08, 0x8B, 0x4B, 0xF4) + loWord(address("${entry}_returnPEA") - 1) + listOf(0xDC) + loWord(address(hook)),
            bytes(entry, offset, 9), "PHP/PHB protection is separate from PHK/PEA's RTL frame: $entry")
    }
    for ((entry, offset) in listOf("GameState_9_HitADoorBlock" to 3, "GameState_B_LoadingNextRoom" to 4)) {
        assertEquals(listOf(0xF4) + loWord(address("${entry}_manualReturn") - 1) + listOf(0x6C) + loWord(address("DoorTransitionFunction")),
            bytes(entry, offset, 6), "Near JMP relies on the synthetic RTS frame: $entry")
    }
    for ((symbol, ram) in mapOf("PauseHook_Pause" to 0x0601, "PauseHook_Unpause" to 0x0604,
        "PauseMenu_MenuIndex" to 0x0727, "DoorTransitionFunction" to 0x099C, "DownwardsElevatorDelayTimer" to 0x092F))
        assertEquals(ram, address(symbol), symbol)
    assertEquals(listOf(0x30, 0), bytes("DoorTransitionFunction_HandleElevator", 0x12, 2), "NTSC delay before the immediate first decrement")

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
    fun calls(symbol: String) = rows(symbol).mapNotNull { Regex("(?:JSL\\.L|JSR\\.W)(.+)").find(it)?.groupValues?.get(1) }
    assertEquals(signature("PHP", "REP #\$30", "LDA.W SamusProjectile_PowerBombFlag", "BNE .return", "LDA.W TimeIsFrozenFlag",
        "BNE .return", "LDA.W DoorTransitionFlagEnemiesPause", "BNE .return", "LDA.W AreaIndex", "CMP.W #\$0006", "BEQ .return",
        "LDA.W GameState", "CMP.W #\$0008", "BNE .return", "LDA.B DP_Controller1New", "BIT.W #\$1000", "BEQ .return",
        "LDA.W #\$0001", "STA.W ScreenFadeDelay", "STA.W ScreenFadeCounter", "LDA.W #\$000C", "STA.W GameState", ".return:", "PLP", "RTS"), rows("PauseCheck"))
    assertEquals(listOf("UpdateHeldInput", "MainPauseRoutine", "HandleHUDTilemap_PausedAndRunning", "Handle_PauseScreen_PaletteAnimation"),
        calls("GameState_F_Paused_MapAndItemScreens"))
    containsSequence(rows("GameState_C_Pausing_NormalGameplayDarkening"), "JSR.W GameState_8_MainGameplay", "JSL.L HandleFadingOut",
        "LDA.B DP_Brightness", "AND.W #\$000F", "BNE .return")
    containsSequence(rows("GameState_10_Unpausing_LoadingNormalGameplay"), "JSL.L HandleFadingOut", "SEP #\$20",
        "LDA.B DP_Brightness", "CMP.B #\$80", "BNE .return")
    assertEquals(listOf("Clear_Samus_Beam_Tiles", "ContinueInitialising_GameplayResume", "ResumeGameplay",
        "Restore_SomeGraphicsState_from_PauseScreen", "Restore_BG2Tilemap_from_PauseMenu", "Enable_HDMAObjects",
        "Enable_AnimatedTilesObjects", "Queue_Samus_Movement_SoundEffects"), calls("GameState_11_Unpausing_LoadingNormalGameplay"))
    containsSequence(rows("GameState_12_Unpausing_NormalGameplayBrightening"), "JSR.W GameState_8_MainGameplay", "JSL.L HandleFadingIn",
        "SEP #\$20", "LDA.B DP_Brightness", "CMP.B #\$0F", "BNE .return", "REP #\$20", "STZ.W ScreenFadeDelay",
        "STZ.W ScreenFadeCounter", "LDA.W #\$0008", "STA.W GameState")
    containsSequence(rows("GameState_9_HitADoorBlock"), ".manualReturn:", "BCS .gameStateA", "PLP", "RTS", ".gameStateA:",
        "INC.W GameState", "PLP", "JMP.W GameState_A_LoadingNextRoom")
    val waitElevator = rows("DoorTransitionFunction_Wait48FramesForDownElevator")
    assertEquals(signature("DEC.W DownwardsElevatorDelayTimer", "BMI .return"), waitElevator.take(2))
    assertEquals(signature("CLC", "RTS", ".return:", "SEC", "RTS"), waitElevator.takeLast(5))
    val sounds = rows("DoorTransitionFunction_WaitForSoundsToFinish")
    assertEquals(3, sounds.count { it == asmStatementSignature("AND.B #\$0F") })
    for (library in 1..3) containsSequence(sounds, "LDA.W APU_SoundQueueNextIndexLib$library", "SEC",
        "SBC.W APU_SoundQueueStartIndexLib$library", "AND.B #\$0F", "BNE .return")
    containsSequence(sounds, "REP #\$20", "LDA.W #DoorTransitionFunction_FadeOutTheScreen", "STA.W DoorTransitionFunction")
    val fadeOut = rows("DoorTransitionFunction_FadeOutTheScreen")
    assertEquals(signature("JSR.W Advance_GradualColorChange_ofAllPalettes_Denominator_C", "BCS +"), fadeOut.take(2))
    assertTrue(fadeOut.none { "HandleFadingOut" in it }, "Door fade changes palettes, not the display-brightness shadow")
    containsSequence(rows("DoorTransitionFunction_LoadDoorHeader_DeleteHDMAObjects_IRQ"), "LDA.W #\$8000", "TRB.W HDMAObject_Enable",
        "LDA.W #\$0008", "STA.B DP_NextIRQCmd", "LDA.W #DoorTransitionFunction_ScrollScreenToAlignment", "STA.W DoorTransitionFunction")

    val align = "DoorTransitionFunction_ScrollScreenToAlignment"
    // Pin the actual unaligned absolute loads, BIT-immediate opcodes and both relative branches.
    data class AlignmentRead(val entry: String, val offset: Int, val coordinate: String, val increment: String)
    for ((entry, offset, coordinate, increment) in listOf(
        AlignmentRead(align, 0x0D, "Layer1YPosition", "scrollUp"),
        AlignmentRead("${align}_vertical", 0, "Layer1XPosition", "scrollRight"),
    )) {
        val start = address(entry) + offset
        val alignedBranch = (address("${align}_done") - (start + 8)) and 255
        val incrementBranch = (address("${align}_$increment") - (start + 10)) and 255
        assertEquals(listOf(0xAD) + loWord(address(coordinate) - 1) + listOf(0x89, 0, 255, 0xF0, alignedBranch, 0x30, incrementBranch),
            bytes(entry, offset, 10), "BIT #immediate retains the preceding load's N flag: $coordinate")
    }
    containsSequence(rows(align), "LDA.W DoorDirection", "BIT.W #\$0002", "BNE .vertical", "LDA.W Layer1YPosition-1")
    assertEquals(signature("JSL.L Calc_Layer2Position_BGScrolls_UpdateBGGraphics_WhenScrolling", "RTS"), rows(align, ".return"))
    assertEquals(signature("JSL.L Calc_Layer2Position_BGScrolls_UpdateBGGraphics_WhenScrolling", "LDA.W #DoorTransitionFunction_FixDoorsMovingUp",
        "STA.W DoorTransitionFunction", "RTS"), rows(align, ".done"))
    containsSequence(rows("DoorTransitionFunction_FadeInTheScreen_and_RunEnemies_Finish"),
        "JSR.W Advance_GradualColorChange_ofAllPalettes_Denominator_C", "BCC .return", "STZ.W DisableSounds",
        "JSL.L Play_SpinJumpSound_if_SpinJumping", "STZ.W DoorTransitionFlagElevatorsZebetites", "STZ.W DoorTransitionFlagEnemiesPause",
        "LDA.W #\$0008", "STA.W GameState")
}

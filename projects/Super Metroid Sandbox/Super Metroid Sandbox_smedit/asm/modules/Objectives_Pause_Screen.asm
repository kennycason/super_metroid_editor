;;; Objectives Pause Screen (SMEDIT reference module)
;;;
;;; Adds a third pause page without replacing either vanilla page:
;;;
;;;     OBJECTIVES <-- L -- MAP -- R --> SAMUS / EQUIPMENT
;;;
;;; Press R on the Objectives page to return to the map. Start still closes
;;; the pause menu normally. Boss completion is read from the live WRAM mirror
;;; of SRAM; Mother Brain uses event $0E, which the game sets at the same
;;; instant that it starts the Zebes escape timer.
;;;
;;; This module demonstrates four common extension techniques:
;;;   1. Verify and hook a small vanilla entry point.
;;;   2. Extend an existing state-machine dispatch table.
;;;   3. Build and DMA a tilemap at runtime.
;;;   4. Read persistent game state without modifying it.

;;; --------------------------------------------------------------------------
;;; Constants
;;; --------------------------------------------------------------------------

!Objectives_State_Active        = $0008
!Objectives_State_MapFadeOut    = $0009
!Objectives_State_Load          = $000A
!Objectives_State_FadeIn        = $000B
!Objectives_State_FadeOut       = $000C
!Objectives_State_LoadMap       = $000D
!Objectives_State_MapFadeIn     = $000E

!Objectives_MenuMode            = $0002
!Objectives_TextTileHigh        = $28   ; Native pause font, priority + palette 2
!Objectives_ZebesTimebombEvent  = $000E

;;; A 32x32 screen uses 64 bytes per tilemap row. These offsets select the
;;; visible left-hand screen of BG1's 64x32 tilemap.
!Objectives_TitleOffset         = $0156 ; Row 5,  column 11
!Objectives_KraidOffset         = $0202 ; Row 8,  column 1
!Objectives_KraidStatusOffset   = $022A ; Row 8,  column 21
!Objectives_PhantoonOffset      = $0282 ; Row 10, column 1
!Objectives_PhantoonStatusOffset = $02AA ; Row 10, column 21
!Objectives_DraygonOffset       = $0302 ; Row 12, column 1
!Objectives_DraygonStatusOffset = $032A ; Row 12, column 21
!Objectives_RidleyOffset        = $0382 ; Row 14, column 1
!Objectives_RidleyStatusOffset  = $03AA ; Row 14, column 21
!Objectives_MotherBrainOffset   = $0402 ; Row 16, column 1
!Objectives_MotherStatusOffset  = $042A ; Row 16, column 21
!Objectives_HelpOffset          = $0550 ; Row 21, column 8

;;; --------------------------------------------------------------------------
;;; Surgical hooks
;;; --------------------------------------------------------------------------

;;; Vanilla enters MainPauseRoutine with JSL, so a long jump can relocate its
;;; dispatcher while preserving the original RTL calling convention. SMEDIT
;;; compiles into a blank output image, so read1() cannot validate bytes emitted
;;; earlier in this same pass. The pinned symbolic address is the safe contract.
assert MainPauseRoutine == $8290FF

org MainPauseRoutine
    JML Objectives_MainPauseRoutine

;;; While Start is fading out, vanilla draws either the map icons or equipment
;;; selector over BG1. Menu mode 2 identifies our static page, so the relocated
;;; helper simply leaves it alone. Modes 0 and 1 retain their vanilla behavior.
assert Draw_PauseMenu_During_FadeOut == $82934B

org Draw_PauseMenu_During_FadeOut
    JMP.W Objectives_DrawPauseMenuDuringFadeOut

;;; --------------------------------------------------------------------------
;;; New pause logic in bank $82's documented free-space region
;;; --------------------------------------------------------------------------

;;; $82:F70F..FFFF is the vanilla free-space block. SMEDIT's bundled Spider
;;; Ball uses $82:F7C0..F835 for its equipment-screen extension, so begin on
;;; the very next byte. This makes the two independently toggleable features
;;; coexist instead of competing for the beginning of the same free region.
org $82F836

Objectives_MainPauseRoutine:
    PHP
    PHB
    PHK
    PLB
    REP #$30
    LDA.W PauseMenu_MenuIndex
    ASL
    TAX
    JSR.W (Objectives_PauseStatePointers,X)
    PLB
    PLP
    RTL

Objectives_PauseStatePointers:
    dw Objectives_MapScreen
    dw PauseScreen_1_EquipmentScreen
    dw PauseMenu_2_MapScreenToEquipmentScreen_FadingOut
    dw PauseMenu_3_MapScreenToEquipmentScreen_LoadEquipmentScreen
    dw PauseMenu_4_MapScreenToEquipmentScreen_FadingIn
    dw PauseMenu_5_EquipmentScreenToMapScreen_FadingOut
    dw PauseMenu_6_EquipmentScreenToMapScreen_LoadMapScreen
    dw PauseMenu_7_EquipmentScreenToMapScreen_FadingIn
    dw Objectives_Screen
    dw PauseMenu_2_MapScreenToEquipmentScreen_FadingOut
    dw Objectives_LoadScreen
    dw Objectives_FadeIn
    dw Objectives_FadeOut
    dw PauseMenu_6_EquipmentScreenToMapScreen_LoadMapScreen
    dw PauseMenu_7_EquipmentScreenToMapScreen_FadingIn

;;; Map + L is unused by vanilla. Set our first transition state, then let the
;;; normal map routine finish this frame (including map icons and highlights).
Objectives_MapScreen:
    REP #$30
    LDA.W Input_TimedHeldNew
    BIT.W #$0020                 ; L
    BEQ .vanilla
    LDA.W Duration_Of_L_R_Button_Pressed_Highlight
    STA.W PauseMenu_ButtonPressedHighlightTimer
    LDA.W #!Objectives_State_MapFadeOut
    STA.W PauseMenu_MenuIndex
    LDA.W #$0001
    STA.W PauseMenu_ShoulderButtonPressedHighlight
    LDA.W #$0038
    JSL.L QueueSound_Lib1_Max6

  .vanilla:
    JMP.W PauseMenu_0_MapScreen

;;; The page itself has no cursor. R returns to the map and Start delegates to
;;; the original unpause handler, so save/resume behavior remains untouched.
Objectives_Screen:
    REP #$30
    STZ.B DP_BG1XScroll
    STZ.B DP_BG1YScroll
    LDA.W #!Objectives_MenuMode
    STA.W PauseMenu_MenuMode
    LDA.W Input_TimedHeldNew
    BIT.W #$0010                 ; R
    BEQ .start
    LDA.W Duration_Of_L_R_Button_Pressed_Highlight
    STA.W PauseMenu_ButtonPressedHighlightTimer
    LDA.W #!Objectives_State_FadeOut
    STA.W PauseMenu_MenuIndex
    LDA.W #$0002
    STA.W PauseMenu_ShoulderButtonPressedHighlight
    LDA.W #$0038
    JSL.L QueueSound_Lib1_Max6

  .start:
    JSR.W EquipmentScreen_Draw_L_R_Highlight
    JMP.W Handle_PauseScreen_StartButton

;;; At full black, replace the old map in the shared $7E:4000 BG1 work buffer.
;;; The equipment buffer at $7E:3800 and BG2's button-label buffer at $7E:3000
;;; remain intact. Returning to the map asks vanilla to rebuild $7E:4000.
Objectives_LoadScreen:
    REP #$30
    JSR.W Objectives_BuildTilemap
    JSR.W Objectives_TransferTilemap
    STZ.B DP_BG1XScroll
    STZ.B DP_BG1YScroll
    LDA.W #!Objectives_MenuMode
    STA.W PauseMenu_MenuMode
    STZ.W PauseMenu_HighlightAnimationFrame
    LDA.W L_R_HighlightAnimationData_PauseScreenPaletteAnimationDelays
    STA.W PauseMenu_HighlightAnimationTimer
    LDA.W #$0001
    STA.W ScreenFadeDelay
    STA.W ScreenFadeCounter
    INC.W PauseMenu_MenuIndex
    RTS

Objectives_FadeIn:
    REP #$30
    STZ.B DP_BG1XScroll
    STZ.B DP_BG1YScroll
    LDA.W #!Objectives_MenuMode
    STA.W PauseMenu_MenuMode
    JSL.L HandleFadingIn
    SEP #$20
    LDA.B DP_Brightness
    CMP.B #$0F
    BNE .return
    REP #$20
    STZ.W ScreenFadeDelay
    STZ.W ScreenFadeCounter
    LDA.W #!Objectives_State_Active
    STA.W PauseMenu_MenuIndex

  .return:
    RTS

Objectives_FadeOut:
    REP #$30
    JSR.W Handle_PauseMenu_L_R_PressedHighlight
    LDA.W #!Objectives_MenuMode
    STA.W PauseMenu_MenuMode
    JSL.L HandleFadingOut
    SEP #$20
    LDA.B DP_Brightness
    CMP.B #$80
    BNE .return
    JSL.L EnableNMI
    REP #$20
    STZ.W ScreenFadeDelay
    STZ.W ScreenFadeCounter
    INC.W PauseMenu_MenuIndex

  .return:
    RTS

;;; Replacement for the tiny vanilla draw selector used only while Start is
;;; fading the complete pause menu out. A third menu mode means "static BG1".
Objectives_DrawPauseMenuDuringFadeOut:
    REP #$30
    LDA.W PauseMenu_MenuMode
    CMP.W #!Objectives_MenuMode
    BEQ .return
    CMP.W #$0001
    BEQ .equipment
    JSL.L Display_Map_Elevator_Destinations
    JSL.L Draw_Map_Icons
    JMP.W MapScreen_DrawSamusPositionIndicator

  .equipment:
    JSR.W EquipmentScreen_DrawItemSelector
    JSR.W EquipmentScreen_DisplayReserveTankAmount_shell
    JMP.W Handle_PauseMenu_L_R_PressedHighlight

  .return:
    RTS

;;; --------------------------------------------------------------------------
;;; Runtime tilemap construction
;;; --------------------------------------------------------------------------

Objectives_BuildTilemap:
    PHP
    REP #$30

    ; Tile $0000 is transparent in the pause BG1 set. Clear both 32x32 screens
    ; in the 64x32 map buffer so horizontal scroll state cannot reveal debris.
    LDX.W #$0FFE
    LDA.W #$0000
  .clear:
    STA.L BG2Tilemap,X
    DEX
    DEX
    BPL .clear

    LDX.W #!Objectives_TitleOffset
    LDY.W #Objectives_Text_Title
    JSR.W Objectives_WriteString

    LDX.W #!Objectives_KraidOffset
    LDY.W #Objectives_Text_Kraid
    JSR.W Objectives_WriteString
    LDX.W #!Objectives_KraidStatusOffset
    LDA.L SRAMMirror_Boss+$01     ; Brinstar
    AND.W #$0001                 ; Area boss: Kraid
    JSR.W Objectives_WriteBossStatus

    LDX.W #!Objectives_PhantoonOffset
    LDY.W #Objectives_Text_Phantoon
    JSR.W Objectives_WriteString
    LDX.W #!Objectives_PhantoonStatusOffset
    LDA.L SRAMMirror_Boss+$03     ; Wrecked Ship
    AND.W #$0001                 ; Area boss: Phantoon
    JSR.W Objectives_WriteBossStatus

    LDX.W #!Objectives_DraygonOffset
    LDY.W #Objectives_Text_Draygon
    JSR.W Objectives_WriteString
    LDX.W #!Objectives_DraygonStatusOffset
    LDA.L SRAMMirror_Boss+$04     ; Maridia
    AND.W #$0001                 ; Area boss: Draygon
    JSR.W Objectives_WriteBossStatus

    LDX.W #!Objectives_RidleyOffset
    LDY.W #Objectives_Text_Ridley
    JSR.W Objectives_WriteString
    LDX.W #!Objectives_RidleyStatusOffset
    LDA.L SRAMMirror_Boss+$02     ; Norfair
    AND.W #$0001                 ; Area boss: Ridley
    JSR.W Objectives_WriteBossStatus

    LDX.W #!Objectives_MotherBrainOffset
    LDY.W #Objectives_Text_MotherBrain
    JSR.W Objectives_WriteString
    LDX.W #!Objectives_MotherStatusOffset
    LDA.W #!Objectives_ZebesTimebombEvent
    JSL.L CheckIfEvent_inA_HasHappened
    BCC .motherIncomplete
    LDY.W #Objectives_Text_Complete
    BRA .motherWrite

  .motherIncomplete:
    LDY.W #Objectives_Text_Incomplete

  .motherWrite:
    JSR.W Objectives_WriteString

    LDX.W #!Objectives_HelpOffset
    LDY.W #Objectives_Text_Help
    JSR.W Objectives_WriteString
    PLP
    RTS

;;; A non-zero A means the corresponding boss flag was present.
Objectives_WriteBossStatus:
    BEQ .incomplete
    LDY.W #Objectives_Text_Complete
    BRA Objectives_WriteString

  .incomplete:
    LDY.W #Objectives_Text_Incomplete
    BRA Objectives_WriteString

;;; X = byte offset in BG2Tilemap, Y = bank-$82 zero-terminated ASCII string.
;;; Super Metroid's native pause-menu A-Z glyphs occupy tiles $30..$49, in
;;; alphabetical order. Spaces remain transparent.
Objectives_WriteString:
    PHP
    SEP #$20
  .next:
    LDA.W $0000,Y
    BEQ .done
    CMP.B #$20
    BEQ .advance
    SEC
    SBC.B #$11                  ; ASCII 'A' ($41) -> pause tile $30
    STA.L BG2Tilemap,X
    LDA.B #!Objectives_TextTileHigh
    STA.L BG2Tilemap+1,X

  .advance:
    INX
    INX
    INY
    BRA .next

  .done:
    PLP
    RTS

;;; Transfer the complete 64x32 BG1 tilemap while the display is forced black.
Objectives_TransferTilemap:
    PHP
    SEP #$30
    LDA.B #$00
    STA.W $2116
    LDA.B #$30
    STA.W $2117
    LDA.B #$80
    STA.W $2115
    JSL.L SetupHDMATransfer
    db $01,$01,$18
    dl BG2Tilemap
    dw $1000
    LDA.B #$02
    STA.W $420B
    PLP
    RTS

Objectives_Text_Title:
    db "OBJECTIVES",$00
Objectives_Text_Kraid:
    db "DEFEAT KRAID",$00
Objectives_Text_Phantoon:
    db "DEFEAT PHANTOON",$00
Objectives_Text_Draygon:
    db "DEFEAT DRAYGON",$00
Objectives_Text_Ridley:
    db "DEFEAT RIDLEY",$00
Objectives_Text_MotherBrain:
    db "DEFEAT MOTHER BRAIN",$00
Objectives_Text_Complete:
    db "COMPLETE",$00
Objectives_Text_Incomplete:
    db "INCOMPLETE",$00
Objectives_Text_Help:
    db "PRESS R FOR MAP",$00

Objectives_CodeEnd:
warnpc $838000

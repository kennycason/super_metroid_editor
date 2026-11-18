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
;;;
;;; Read this file from top to bottom as a complete hook lifecycle. The two
;;; hooks redirect vanilla entry points, the replacement dispatcher preserves
;;; vanilla states 0..7, and custom states 8..14 add one round trip through the
;;; Objectives page. All new routines stay in bank $82 so their JSRs and 16-bit
;;; table pointers remain bank-local; cross-bank calls and WRAM reads are long.

;;; --------------------------------------------------------------------------
;;; Constants
;;; --------------------------------------------------------------------------

;;; PauseMenu_MenuIndex is a word-sized, zero-based dispatch-table index.
;;; Vanilla owns states 0..7; this module appends the following state path:
;;;
;;;   map (0) -- L --> 9 fade out --> 10 load Objectives --> 11 fade in
;;;       --> 8 active -- R --> 12 fade out --> 13 load map --> 14 fade in
;;;       --> map (0)
;;;
;;; States 9, 13, and 14 intentionally reuse vanilla transition routines.
!Objectives_State_Active        = $0008
!Objectives_State_MapFadeOut    = $0009
!Objectives_State_Load          = $000A
!Objectives_State_FadeIn        = $000B
!Objectives_State_FadeOut       = $000C
!Objectives_State_LoadMap       = $000D
!Objectives_State_MapFadeIn     = $000E

;;; PauseMenu_MenuMode is separate from PauseMenu_MenuIndex. Vanilla draw code
;;; uses modes 0 (map) and 1 (equipment); mode 2 identifies our static BG1 page.
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

;;; Vanilla enters MainPauseRoutine with JSL. The four-byte JML replaces the
;;; entry, and Objectives_MainPauseRoutine below reconstructs the complete
;;; dispatcher prologue and exits with RTL. There is therefore no displaced
;;; instruction to run after the jump and no trampoline back into this hook.
;;;
;;; SMEDIT compiles into a blank output image, so read1() cannot validate bytes
;;; emitted earlier in this same pass. The pinned symbolic address is the safe
;;; source contract: a changed base disassembly fails here instead of silently
;;; patching the wrong routine.
assert MainPauseRoutine == $8290FF

org MainPauseRoutine
    JML Objectives_MainPauseRoutine

;;; While Start is fading out, vanilla calls this same-bank routine to draw
;;; either the map icons or equipment selector over BG1. The three-byte JMP
;;; replaces its entry; our routine reproduces both vanilla branches and adds
;;; mode 2 as a no-draw branch, then returns to the original JSR caller with RTS.
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

;;; Calling contract: entered by the hook's JML, but originally called by JSL.
;;; Preserve P and DBR, use bank $82 as the data bank, and return with RTL so
;;; the original caller consumes exactly the long return frame it created.
Objectives_MainPauseRoutine:
    PHP                         ; Preserve caller widths and flags
    PHB                         ; Preserve caller data bank
    PHK
    PLB                         ; DBR = $82 for table/routine .W accesses
    REP #$30                    ; 16-bit A and 16-bit X/Y
    LDA.W PauseMenu_MenuIndex
    ASL                         ; Two bytes per state-table entry
    TAX
    JSR.W (Objectives_PauseStatePointers,X) ; Same-bank indirect subroutine
    PLB
    PLP
    RTL

;;; Index comments are PauseMenu_MenuIndex values, not byte offsets. Entries
;;; 0..7 preserve the vanilla dispatch table exactly except that state 0 passes
;;; through our L-button wrapper. Entries 8..14 are the extension.
Objectives_PauseStatePointers:
    dw Objectives_MapScreen                                  ; 00 map
    dw PauseScreen_1_EquipmentScreen                         ; 01 equipment
    dw PauseMenu_2_MapScreenToEquipmentScreen_FadingOut      ; 02 map -> equipment
    dw PauseMenu_3_MapScreenToEquipmentScreen_LoadEquipmentScreen ; 03 load equipment
    dw PauseMenu_4_MapScreenToEquipmentScreen_FadingIn       ; 04 equipment fade in
    dw PauseMenu_5_EquipmentScreenToMapScreen_FadingOut      ; 05 equipment -> map
    dw PauseMenu_6_EquipmentScreenToMapScreen_LoadMapScreen  ; 06 load map
    dw PauseMenu_7_EquipmentScreenToMapScreen_FadingIn       ; 07 map fade in
    dw Objectives_Screen                                    ; 08 Objectives active
    dw PauseMenu_2_MapScreenToEquipmentScreen_FadingOut      ; 09 map fade out
    dw Objectives_LoadScreen                                ; 0A build/load Objectives
    dw Objectives_FadeIn                                    ; 0B Objectives fade in
    dw Objectives_FadeOut                                   ; 0C Objectives fade out
    dw PauseMenu_6_EquipmentScreenToMapScreen_LoadMapScreen  ; 0D rebuild/load map
    dw PauseMenu_7_EquipmentScreenToMapScreen_FadingIn       ; 0E map fade in

;;; Map + L is unused by vanilla. State 9 reuses vanilla's fade-out animation;
;;; that routine increments the index to state 10 when black. Until then, tail-
;;; jump to state 0 so the map, icons, Samus marker, and highlights finish this
;;; frame exactly as they do without the module.
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

;;; The page itself has no cursor. R selects state 12; states 13 and 14 then
;;; reuse vanilla's map loader and fade-in. Start delegates to the original
;;; unpause handler, so save/resume behavior remains untouched.
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

;;; State 10 runs at full black. Replace the old map in the shared $7E:4000 BG1
;;; work buffer and upload it before fading in; mutating visible VRAM mid-frame
;;; is thereby avoided. The historical symbol for $7E:4000 is BG2Tilemap, but
;;; during the pause screen this buffer is the source for BG1's tilemap.
;;;
;;; The equipment buffer at $7E:3800 and BG2's button-label buffer at $7E:3000
;;; remain intact. Returning through vanilla state 13 rebuilds $7E:4000 as the
;;; map, so the custom page does not need a second persistent tilemap buffer.
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
    ; Brightness is a byte. $0F is full brightness; keep state 11 until then.
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
    ; Bit 7 marks forced blank. Once set, it is safe to enable NMI and load BG1.
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
;;; fading the complete pause menu out. A third menu mode means "static BG1":
;;; our Objectives tilemap is already complete, so drawing map/equipment UI on
;;; top would be both unnecessary and visibly wrong.
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

;;; Every caller has DBR = $82, A/X/Y = 16-bit. The routine preserves the
;;; caller's status because the string writer temporarily makes A 8-bit.
;;;
;;; Despite its historical name, BG2Tilemap is the $7E:4000 scratch buffer that
;;; pause setup uploads to BG1. Long addressing is required because DBR remains
;;; $82 while the destination is WRAM bank $7E.
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
    ; SRAMMirror_Boss is the live WRAM mirror of persisted boss bits. Byte
    ; offsets select the save area's region and bit 0 is that region's main
    ; boss. Reading the mirror makes the page update immediately and agree with
    ; the data that will be written to SRAM at the next save.
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

    ; Mother Brain is not represented by those regional boss bits. Event $0E
    ; is set when her defeat starts the Zebes escape, so the event system is the
    ; persistent canonical completion signal for this objective.
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

;;; Input: A = masked boss bit, X = destination byte offset. Tail-branching to
;;; Objectives_WriteString lets that routine's RTS return directly to our
;;; caller; no extra stack frame is needed.
Objectives_WriteBossStatus:
    BEQ .incomplete
    LDY.W #Objectives_Text_Complete
    BRA Objectives_WriteString

  .incomplete:
    LDY.W #Objectives_Text_Incomplete
    BRA Objectives_WriteString

;;; Input: X = byte offset in BG2Tilemap; Y = bank-$82 zero-terminated string.
;;; Supported text is uppercase ASCII plus spaces. Super Metroid's native pause
;;; A-Z glyphs occupy tiles $30..$49, so subtracting $11 maps ASCII 'A' ($41)
;;; to tile $30, 'B' to $31, and so on. A tilemap entry is little-endian: the
;;; low byte is the tile number and the high byte supplies palette/priority.
;;; Spaces only advance X, preserving the transparent tile written by .clear.
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
;;; $2116/$2117 select VRAM word address $3000 and $2115 = $80 increments that
;;; address after the high-byte port. SetupHDMATransfer consumes the eight bytes
;;; immediately after its JSL as an inline descriptor:
;;;
;;;   channel 1, DMA mode 1, B-bus target $18 ($2118/$2119),
;;;   source BG2Tilemap, length $1000 bytes.
;;;
;;; The helper only configures $4310..$4316; writing bit 1 to $420B starts DMA
;;; channel 1. PHP/PLP hide the required 8-bit A/X/Y setup from our caller.
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
;;; All code and text above started at $82:F836. Crossing $83:8000 would wrap
;;; past bank $82's upper LoROM half, so this guard makes growth visible during
;;; assembly instead of allowing the module to overwrite another bank.
warnpc $838000

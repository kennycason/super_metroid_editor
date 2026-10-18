; Project module: Fast Doors
; SMEDIT description: Twice-per-frame transitions and two-pixel screen alignment. Original patch by NobodyNada.
; Toggle through Patches > Fast Doors, or edit this literal (0/1).
; Disabling emits no hooks or helpers; all custom source below is retained.
!SMEDIT_FastDoorsEnabled = 0

if !SMEDIT_FastDoorsEnabled

; SMEDIT checks the native routines before using these instruction offsets.
org DoorTransitionScrolling+$0E
SMEDIT_FastDoors_ScrollHook:
    JSR.W SMEDIT_FastDoors_ScrollTwice

org DoorTransitionFunction_ScrollScreenToAlignment+$15
SMEDIT_FastDoors_AlignYHook:
    NOP
    PHP
    LDX.W #$0004
    PLP
    JSL.L SMEDIT_FastDoors_Align

org DoorTransitionFunction_ScrollScreenToAlignment+$29
SMEDIT_FastDoors_AlignXHook:
    NOP
    PHP
    LDX.W #$0000
    PLP
    JSL.L SMEDIT_FastDoors_Align

; Default placements match the bundled IPS. These helpers may be relocated
; within their banks' verified free space; hooks use the symbols above.
org Freespace_Bank80_CD8E+$0372
SMEDIT_FastDoors_ScrollTwice:
    PHX
    JSR.W (DoorTransitionScrolling_pointers,X)
    PLX
    BCC .again
    RTS
  .again:
    JMP.W (DoorTransitionScrolling_pointers,X)
SMEDIT_FastDoors_ScrollTwice_End:

org Freespace_Bank82_F70F+$0671
SMEDIT_FastDoors_Align:
    SEP #$20
    BMI .negative
    LDA.W Layer1XPosition,X
    CMP.B #$02
    BPL .subtractTwo
    BRA .zeroLow
  .negative:
    LDA.W Layer1XPosition,X
    CMP.B #$FE
    BMI .addTwo
  .onePixel:
    INC.W Layer1XPosition+1,X
  .zeroLow:
    STZ.W Layer1XPosition,X
    REP #$20
    RTL
  .subtractTwo:
    REP #$20
    LDA.W #$FFFE
    BRA .apply
  .addTwo:
    REP #$20
    LDA.W #$0002
  .apply:
    CLC
    ADC.W Layer1XPosition,X
    STA.W Layer1XPosition,X
    RTL
SMEDIT_FastDoors_Align_End:

endif

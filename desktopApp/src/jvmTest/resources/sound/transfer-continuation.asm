; Independent assembler contract for MusicTransferLoader.code, not an enabled
; project patch. The normal uploader has already pushed the 8-bit kick and
; selected 16-bit A/X when this JSL replacement runs.
lorom
org $B88000
    rep #$30 ; assembler width prelude, excluded from the compared hook bytes
ReadSize:
    lda $0000,y
    cmp #$FFFF
    beq Continue
    ; Match native IncYTwice_OverflowCheck, including first-increment overflow.
    iny
    beq Overflow
    iny
    beq Overflow
    rtl
Overflow:
    inc $02
    pei ($01)
    plb
    plb
    ldy #$8000
    rtl
Continue:
    ; Prefix and complete five-byte marker are within a single LoROM bank.
    lda $0002,y
    pha
    sep #$20
    lda $0004,y
    sta $02
    pha
    plb
    rep #$20
    ply
    bra ReadSize

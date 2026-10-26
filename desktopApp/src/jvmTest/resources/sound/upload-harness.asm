; Test ROM only. Retain the real CPU upload routine and SPC engine; replace
; reset/gameplay with a mailbox so tests exercise the actual CPU/APU protocol.
lorom
org $80FFFC
dw TestReset

org $80A000
TestReset:
    sei
    clc
    xce
    rep #$30
    ldx #$1FFF
    txs
    lda #$0000
    tcd
    sep #$20
    pha
    plb
    lda #$80
    sta $2100
    stz $4200
    stz $420B
    stz $420C
    stz $1E00
    stz $1E01
    rep #$20
    lda #$8000
    sta $00
    lda #$00CF
    sta $02
    jsl $808024
    sep #$20
    inc $1E01

TestMailbox:
    rep #$30
    sep #$20
    lda $1E00
    beq TestMailbox
    cmp #$01
    beq TestUpload
    cmp #$02
    beq TestMusic
    cmp #$03
    beq TestEffect1
    cmp #$04
    beq TestEffect2
    lda $1E02
    sta $2143
    bra TestDone
TestEffect2:
    lda $1E02
    sta $2142
    bra TestDone
TestEffect1:
    lda $1E02
    sta $2141
    bra TestDone
TestMusic:
    lda $1E02
    sta $2140
    bra TestDone
TestUpload:
    rep #$20
    lda $1E02
    sta $00
    lda $1E04
    and #$00FF
    sta $02
    sep #$20
    lda $1E0C
    beq TestWide
    sec
    sep #$30
    jsl $808024
    bra TestReturned
TestWide:
    rep #$30
    clc
    jsl $808024
TestReturned:
    php
    sep #$20
    pla
    sta $1E06
    phb
    pla
    sta $1E07
    rep #$20
    tsc
    sta $1E08
    sep #$20
TestDone:
    inc $1E01
    stz $1E00
    bra TestMailbox

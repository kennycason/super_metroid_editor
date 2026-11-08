; Project module: hook 81A0A8
; SMEDIT description: Checked hook at $81:A0A8
; @smedit-checked-hook-v1 hook=$81A0A8 continuation=$81A0AE helper=$E08002 limit=$E08402 native=ADC2098D0442 hook_label=SMEDIT_Hook81A0A8_81A0A8_Hook helper_label=SMEDIT_Hook81A0A8_81A0A8_Helper helper_end_label=SMEDIT_Hook81A0A8_81A0A8_Helper_End
; Generated from bank_81.asm:4085.
; SMEDIT verifies the native bytes, detour, displaced instructions, helper range,
; continuation jump, and every byte this module emits on each build.

org $81A0A8
SMEDIT_Hook81A0A8_81A0A8_Hook:
    JML.L SMEDIT_Hook81A0A8_81A0A8_Helper
    NOP
    NOP
warnpc $81A0AE

org $E08002
SMEDIT_Hook81A0A8_81A0A8_Helper:
    LDA.W Energy
    STA.W $4204

    ; Add custom code here. Keep the final JML and boundary labels intact.
    JML.L $81A0AE
SMEDIT_Hook81A0A8_81A0A8_Helper_End:
warnpc $E08402

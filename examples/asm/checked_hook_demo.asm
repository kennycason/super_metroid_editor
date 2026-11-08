; Project module: Checked Hook Demo
; SMEDIT description: Raises the low-energy warning threshold through a verified bank-$90 detour.
;;;
;;; This intentionally small example demonstrates the complete hook shape:
;;;
;;;   native entry -> same-bank JMP -> custom decision -> native continuation
;;;
;;; Vanilla warns below 31 energy. This module warns below 51 instead. It does
;;; not replace the sound logic: both custom branches tail-jump back into the
;;; original routine, which still owns the final RTS and warning state.
;;;
;;; The module is installed disabled. Enable it in the Project Modules list.
;;; SMEDIT validates the native routine, overwritten instruction, continuation
;;; labels, helper placement, module order, and conflicts before accepting a
;;; build. Keep the gate and SMEDIT_* boundary labels intact.
;;; The checked tutorial permits changing the threshold value; copy/rename the
;;; module before making structural changes to its hook or helper routine.

!SMEDIT_CheckedHookDemoEnabled = 1

if !SMEDIT_CheckedHookDemoEnabled

!SMEDIT_CheckedHookDemoThreshold = $0033 ; 51 energy

;;; Hook contract: LowEnergyCheck begins with the complete three-byte
;;; instruction `LDA.W Energy`. A three-byte JMP replaces exactly that one
;;; instruction; execution never falls through into the old CMP/BMI pair.
assert LowEnergyCheck == $90EA7F
assert LowEnergyCheck_lowEnergy == $90EA98

org LowEnergyCheck
SMEDIT_CheckedHookDemo_Hook:
    JMP.W SMEDIT_CheckedHookDemo_Threshold

;;; The hook and every continuation are bank-local, so this helper must remain
;;; in bank $90. Freespace_Bank90_F63A is the disassembly's documented trailing
;;; free-space label. warnpc prevents accidental bank crossing.
org Freespace_Bank90_F63A
SMEDIT_CheckedHookDemo_Threshold:
    LDA.W Energy
    CMP.W #!SMEDIT_CheckedHookDemoThreshold
    BMI .lowEnergy
    JMP.W LowEnergyCheck+$0008  ; Resume at LDA CriticalEnergyWarning

  .lowEnergy:
    JMP.W LowEnergyCheck_lowEnergy
SMEDIT_CheckedHookDemo_Threshold_End:

warnpc $918000

endif

package com.supermetroid.editor.asm

/** Native code shapes which give the packed counter/timer table its stage semantics. */
internal val SHORT_CHARGE_BINDINGS = listOf(
    DataPatchBinding("bank_90.asm", "Handle_Samus_XExtraRunSpeed", listOf(
        "LDA.L SpeedBoostTimerResetValues", "STA.W SamusBoostTimer")),
    DataPatchBinding("bank_91.asm", "UpdateSamusPoseDueToChangeOfEquipment", listOf(
        "LDA.W SpeedBoostTimerResetValues", "STA.W SamusBoostTimer")),
    DataPatchBinding("bank_90.asm", "Handle_SpeedBooster_AnimationDelay", listOf(
        "LDA.W SamusBoostTimer", "DEC", "STA.W SamusBoostTimer", "BIT.W #\$00FF", "BNE .finish",
        "LDA.W SamusBoostCounter-1", "BIT.W #\$0400", "BNE +", "CLC", "ADC.W #\$0100",
        "STA.W SamusBoostCounter-1", "BIT.W #\$0400", "BEQ +", "LDA.W #\$0001", "STA.W SamusEchoesSFXFlag",
        "LDA.W #\$0003", "JSL.L QueueSound_Lib3_Max6", "XBA", "AND.W #\$00FF", "ASL", "TAX",
        "LDA.W SamusBoostTimer", "AND.W #\$FF00", "ORA.L SpeedBoostTimerResetValues,X", "STA.W SamusBoostTimer")),
)

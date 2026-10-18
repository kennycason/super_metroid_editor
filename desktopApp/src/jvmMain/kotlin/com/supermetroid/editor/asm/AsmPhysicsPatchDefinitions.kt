package com.supermetroid.editor.asm

import com.supermetroid.editor.ui.ALL_PHYSICS_FIELDS

/** Byte controls retain the complete native word/dword, including independent fractional bits. */
internal val PHYSICS_PATCH_DEFINITIONS: List<DataPatchDefinition> = buildList {
    fun table(key: String, symbol: String, index: Int, native: Int, expression: String, value: Int,
              size: Int = 2, shift: Int = 0, unrepresentable: Int = 0xFF00) {
        val field = ALL_PHYSICS_FIELDS.single { it.key == key }
        add(DataPatchDefinition(PHYSICS_CONFIG_TYPE, key, field.label, 0..255,
            listOf(DataPatchSite("bank_90.asm", symbol, native, op = if (size == 4) "dd" else "dw", index = index,
                size = size, nativeExpression = expression, nativeValue = value)),
            mask = 255 shl shift, shift = shift, unrepresentableMask = unrepresentable))
    }
    for ((stem, keys, amounts, native) in listOf(
        PhysicsJump("Jumping", listOf("jump_height", "jump_water", "jump_lava"), listOf("04E0", "01C0", "02C0"), 0x909EB9),
        PhysicsJump("HiJumpJumping", listOf("hijump_height", "hijump_water", "hijump_lava"), listOf("0600", "0280", "0380"), 0x909EC5),
        PhysicsJump("WallJumping", listOf("walljump", "walljump_water", "walljump_lava"), listOf("04A0", "0040", "02A0"), 0x909ED1),
        PhysicsJump("HiJumpWallJumping", listOf("walljump_hijump"), listOf("0580"), 0x909EDD),
    )) for ((index, key) in keys.withIndex()) table(key, "SamusPhysicsConstants_InitialYSpeeds_$stem", index,
        native + index * 2, "$" + amounts[index] + "*!SPF/\$100", amounts[index].toInt(16) ushr 8)
    table("gravity", "SamusPhysicsConstants_YSubAccelerationInAir", 0, 0x909EA1, "regional(\$1C00, \$2800)",
        0x1C00, shift = 8, unrepresentable = 0)
    table("run_accel", "SamusXSpeedTable_Normal", 3, 0x909F61, "wordSwap(\$0030*!SPF*\$100)",
        0x30000000, size = 4, shift = 24, unrepresentable = 0)
    table("run_max", "SamusXSpeedTable_Normal", 4, 0x909F65, "wordSwap(\$02C0*!SPF*\$100)",
        0xC0000002.toInt(), size = 4)
    table("air_normal", "SamusXSpeedTable_Normal", 7, 0x909F71, "wordSwap(\$0140*!SPF*\$100)",
        0x40000001, size = 4)
    table("air_spin", "SamusXSpeedTable_Normal", 10, 0x909F7D, "wordSwap(\$0160*!SPF*\$100)",
        0x60000001, size = 4)
    add(DataPatchDefinition(PHYSICS_CONFIG_TYPE, "max_fall", "Max Fall Speed", 0..255,
        listOf(DataPatchSite("bank_90.asm", "Samus_Y_Movement_WithSpeedCalculations_down", 0x909110,
            op = "CMP.W", consumer = "BEQ +", before = listOf("LDA.W SamusYSpeed"),
            nativeExpression = "\$0005*!SPF", nativeValue = 5)), mask = 255, unrepresentableMask = 0xFF00))
    add(DataPatchDefinition(PHYSICS_CONFIG_TYPE, "air_physics", "Air Physics Mode", 0..255,
        listOf(DataPatchSite("bank_90.asm", "CalculateSamusXBaseSpeed_DecelerationDisallowed", 0x909B2F,
            op = "ADC.W", consumer = "STA.W SamusXBaseSubSpeed", before = listOf("LDA.W SamusXBaseSubSpeed", "CLC"),
            immediate = false)), mask = 255, unrepresentableMask = 0xFF00))
}.sortedBy { definition -> ALL_PHYSICS_FIELDS.indexOfFirst { it.key == definition.key } }

private data class PhysicsJump(val stem: String, val keys: List<String>, val amounts: List<String>, val native: Int)
internal data class DataPatchBinding(val file: String, val symbol: String, val instructions: List<String>)

internal fun physicsBindings(definition: DataPatchDefinition): List<DataPatchBinding> {
    val site = definition.sites.single()
    val symbol = site.symbol
    return when {
        symbol.startsWith("SamusPhysicsConstants_InitialYSpeeds_") -> listOf(DataPatchBinding("bank_90.asm",
            if ("WallJumping" in symbol) "Make_Samus_WallJump" else "Make_Samus_Jump",
            listOf("LDA.W $symbol,X", "STA.W SamusYSpeed")))
        definition.key == "gravity" -> listOf(DataPatchBinding("bank_90.asm", "Determine_Samus_YAcceleration",
            listOf("LDA.W $symbol", "STA.W SamusYSubAcceleration")))
        site.op == "dd" -> listOf(
            DataPatchBinding("bank_90.asm", "Determine_SamusXSpeedTable_EntryPointer", listOf(
                "LDA.W MovementType", "AND.W #\$00FF", "STA.B DP_Temp14", "ASL", "CLC", "ADC.B DP_Temp14", "ASL", "ASL", "CLC", "ADC.W XSpeedTablePointer", "TAX")),
            DataPatchBinding("bank_90.asm", "CalculateSamusXBaseSpeed_DecelerationAllowed", listOf(
                "LDA.W SamusXBaseSubSpeed", "CLC", "ADC.W \$0002,X", "STA.W SamusXBaseSubSpeed",
                "LDA.W SamusXBaseSpeed", "ADC.W \$0000,X", "STA.W SamusXBaseSpeed", "CMP.W \$0004,X")),
            DataPatchBinding("bank_90.asm", "CalculateSamusXBaseSpeed_DecelerationDisallowed", listOf(
                "LDA.W SamusXBaseSpeed", "ADC.W \$0000,X", "STA.W SamusXBaseSpeed", "CMP.W \$0004,X")),
        )
        else -> emptyList()
    }
}

/** Recognize only the exact pinned NTSC forms. Authored arithmetic stays in ASM. */
internal fun requirePhysicsExpressionDefinitions(originals: Map<String, String>, texts: Map<String, String>) {
    fun signature(source: Map<String, String>, file: String, pattern: Regex) = source[file].orEmpty().lineSequence()
        .map { asmCodeBeforeComment(it).replace(Regex("[ \\t]+"), "").trim() }.filter { pattern.containsMatchIn(it) }.toList()
    for ((file, pattern) in listOf("main.asm" to Regex("!SPF|!PAL|^if|^else|^endif|^math"),
        "macros.asm" to Regex("^function(?:regional|wordSwap)\\("))) {
        require(signature(originals, file, pattern) == signature(texts, file, pattern)) {
            "Physics region, frame-rate or wordSwap definitions changed; use explicit literals for two-way editing."
        }
    }
}

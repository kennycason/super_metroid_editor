package com.supermetroid.editor.ui

interface BossTuningField {
    val key: String
    val label: String
    val snesAddress: Int
    val defaultValue: Int
    val unit: String
    val signed: Boolean
    val hex: Boolean
    val minValue: Int?
    val maxValue: Int?
    val writeSnesAddresses: List<Int>
}

internal fun BossTuningField.logicalDefaultValue(): Int =
    if (signed && defaultValue > 32767) defaultValue - 65536 else defaultValue

internal fun BossTuningField.logicalValue(storedValue: Int): Int {
    val word = storedValue and 0xFFFF
    return if (signed && word > 32767) word - 65536 else word
}

internal fun BossTuningField.storedValue(logicalValue: Int): Int =
    if (signed && logicalValue < 0) logicalValue + 65536 else logicalValue

internal fun BossTuningField.logicalMinValue(): Int =
    minValue ?: when {
        signed && unit in setOf("px/frame", "steps/frame") -> -255
        signed -> -32768
        else -> 0
    }

internal fun BossTuningField.logicalMaxValue(): Int {
    val explicitMax = maxValue
    val inferred = when {
        explicitMax != null -> explicitMax
        hex -> 65535
        unit == "hp" -> 32767
        unit == "frames" -> 32767
        unit == "px" -> 4095
        unit == "missiles" -> 999
        unit == "damage" -> 9999
        unit in setOf("px/frame", "steps/frame") -> 255
        unit == "subpx/frame" -> 32767
        unit == "speed" -> 32767
        signed -> 32767
        else -> 65535
    }
    return maxOf(inferred, logicalDefaultValue())
}

internal fun coerceBossTuningValue(field: BossTuningField, storedValue: Int): Int {
    val min = field.logicalMinValue()
    val max = field.logicalMaxValue()
    val logical = field.logicalValue(storedValue).coerceIn(min, max)
    return field.storedValue(logical).coerceIn(0, 65535)
}

internal fun formatBossTuningSnesAddress(snesAddress: Int): String {
    val bank = (snesAddress ushr 16) and 0xFF
    val offset = snesAddress and 0xFFFF
    return "$" + bank.toString(16).uppercase().padStart(2, '0') +
        ":" + offset.toString(16).uppercase().padStart(4, '0')
}

internal fun BossTuningField.rangeText(): String {
    val min = logicalMinValue()
    val max = logicalMaxValue()
    return if (hex && min >= 0) {
        val lo = min.toString(16).uppercase().padStart(4, '0')
        val hi = max.toString(16).uppercase().padStart(4, '0')
        "\$$lo..\$$hi"
    } else {
        "$min..$max"
    }
}

internal fun BossTuningField.metadataText(): String {
    val mirrors = writeSnesAddresses.size - 1
    val mirrorText = if (mirrors > 0) " +$mirrors mirror" + if (mirrors == 1) "" else "s" else ""
    return "${formatBossTuningSnesAddress(snesAddress)}$mirrorText | ${rangeText()}"
}

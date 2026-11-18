package com.supermetroid.editor.ui

import com.supermetroid.editor.emulator.DebugWatchpointHit

enum class AsmDebugWatchWidth(val byteCount: Int, val displayName: String) {
    BYTE(1, "8-bit"),
    WORD(2, "16-bit"),
    LONG(3, "24-bit"),
    DWORD(4, "32-bit"),
    ;

    companion object {
        fun nearest(byteCount: Int): AsmDebugWatchWidth = entries.minBy { kotlin.math.abs(it.byteCount - byteCount) }
    }
}

data class AsmDebugWatchDraft(
    val id: Long? = null,
    val name: String = "",
    val expression: String = "",
    val resolvedAddress: Int? = null,
    val width: AsmDebugWatchWidth = AsmDebugWatchWidth.WORD,
    /** True only when this address was explicit or resolved against the ROM currently running. */
    val currentAddressVerified: Boolean = false,
)

data class AsmDebugWatch(
    val id: Long,
    val name: String,
    val expression: String,
    val address: Int,
    val width: AsmDebugWatchWidth,
    val currentAddressVerified: Boolean = false,
    val bytes: ByteArray = ByteArray(0),
    val readError: String? = null,
) {
    val value: Long?
        get() = bytes.takeIf { it.size == width.byteCount }?.foldIndexed(0L) { index, value, byte ->
            value or ((byte.toLong() and 0xFF) shl (index * 8))
        }

    fun decimalValue(): String = value?.toString() ?: "—"

    fun hexadecimalValue(): String = value
        ?.toString(16)
        ?.uppercase()
        ?.padStart(width.byteCount * 2, '0')
        ?.let { "\$$it" }
        ?: "—"

    fun binaryValue(): String = value
        ?.toString(2)
        ?.padStart(width.byteCount * 8, '0')
        ?.chunked(4)
        ?.joinToString(" ")
        ?: "—"

    fun draft(): AsmDebugWatchDraft = AsmDebugWatchDraft(
        id = id,
        name = name,
        expression = expression,
        resolvedAddress = address,
        width = width,
        currentAddressVerified = currentAddressVerified,
    )
}

data class AsmDebugWatchpointSettings(
    val pauseOnRead: Boolean = false,
    val pauseOnWrite: Boolean = false,
    val historyLimit: Int = 25,
) {
    val active: Boolean get() = pauseOnRead || pauseOnWrite
}

data class AsmDebugWatchpointHistoryEntry(
    val id: Long,
    val hit: DebugWatchpointHit,
    val coreFrame: Long,
)

internal fun isWramDebugAddress(address: Int): Boolean = address ushr 16 in 0x7E..0x7F

internal fun isDebugWatchRangeValid(address: Int, width: AsmDebugWatchWidth): Boolean =
    address in 0..0xFFFFFF && address.toLong() + width.byteCount <= 0x1000000L

internal fun debugWatchWramOffset(address: Int): Int? = when (address ushr 16) {
    0x7E -> address and 0xFFFF
    0x7F -> 0x10000 or (address and 0xFFFF)
    else -> null
}

internal fun wramDebugAddress(offset: Int): Int? = when (offset) {
    in 0..0xFFFF -> 0x7E0000 or offset
    in 0x10000..0x1FFFF -> 0x7F0000 or (offset and 0xFFFF)
    else -> null
}

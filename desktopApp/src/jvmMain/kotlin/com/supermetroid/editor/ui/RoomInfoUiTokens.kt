package com.supermetroid.editor.ui

import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.RoomAreaCatalog

internal val ROOM_AREA_NAMES = RoomAreaCatalog.names

// Keep one compact, readable scale across the Room Info panel and its dialogs.
internal val ROOM_INFO_BODY_FONT_SIZE = 11.sp
internal val ROOM_INFO_COMPACT_FONT_SIZE = 10.sp
internal val ROOM_INFO_CAPTION_FONT_SIZE = 9.sp
internal val ROOM_INFO_SECTION_FONT_SIZE = 13.sp

internal fun hex8(value: Int): String = "0x${value.toString(16).uppercase().padStart(2, '0')}"
internal fun hex16(value: Int): String = "0x${value.toString(16).uppercase().padStart(4, '0')}"
internal fun snesAddr24(value: Int): String {
    val bank = (value shr 16) and 0xFF
    val address = value and 0xFFFF
    return "\$${bank.toString(16).uppercase().padStart(2, '0')}:" +
        address.toString(16).uppercase().padStart(4, '0')
}

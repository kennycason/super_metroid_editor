package com.supermetroid.editor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.TextUnit
import com.supermetroid.editor.rom.RoomAreaCatalog

internal val ROOM_AREA_NAMES = RoomAreaCatalog.names

// Room Info uses the same semantic type scale as the rest of the editor. Keeping
// these aliases in one place lets the dense panel remain consistent without
// bypassing Settings -> Font size.
internal val ROOM_INFO_BODY_FONT_SIZE: TextUnit
    @Composable get() = LocalEditorTheme.current.fontSize.value.body

internal val ROOM_INFO_COMPACT_FONT_SIZE: TextUnit
    @Composable get() = LocalEditorTheme.current.fontSize.value.detail

internal val ROOM_INFO_CAPTION_FONT_SIZE: TextUnit
    @Composable get() = LocalEditorTheme.current.fontSize.value.detail

internal val ROOM_INFO_SECTION_FONT_SIZE: TextUnit
    @Composable get() = LocalEditorTheme.current.fontSize.value.heading

internal fun hex8(value: Int): String = "0x${value.toString(16).uppercase().padStart(2, '0')}"
internal fun hex16(value: Int): String = "0x${value.toString(16).uppercase().padStart(4, '0')}"
internal fun snesAddr24(value: Int): String {
    val bank = (value shr 16) and 0xFF
    val address = value and 0xFFFF
    return "\$${bank.toString(16).uppercase().padStart(2, '0')}:" +
        address.toString(16).uppercase().padStart(4, '0')
}

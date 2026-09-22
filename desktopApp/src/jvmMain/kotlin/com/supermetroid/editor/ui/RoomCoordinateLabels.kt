package com.supermetroid.editor.ui

internal fun oneBasedRoomCoordinate(x: Int, y: Int): String = "(${x + 1}, ${y + 1})"

internal fun roomScreenCoordinateForBlock(blockX: Int, blockY: Int): String =
    oneBasedRoomCoordinate(blockX / 16, blockY / 16)

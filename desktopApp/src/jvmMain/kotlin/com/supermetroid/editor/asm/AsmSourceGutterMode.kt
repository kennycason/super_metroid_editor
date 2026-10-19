package com.supermetroid.editor.asm

/** A workspace display preference; never part of a project's source baseline. */
internal enum class AsmSourceGutterMode(val title: String, val lines: Boolean, val addresses: Boolean) {
    LINES("Line numbers", true, false),
    ADDRESSES("Original SNES addresses", false, true),
    BOTH("Lines + original SNES addresses", true, true);

    fun next(): AsmSourceGutterMode = values()[(ordinal + 1) % values().size]
}

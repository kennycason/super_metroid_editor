package com.supermetroid.editor.procgen

/** Logical cell states used during structure generation before tile dressing. */
internal object BiomeCell {
    const val AIR = 0
    const val SOLID = 1
    const val SPIKE = 2
    const val CRUMBLE = 3
    const val SHOT_HIDDEN = 4
    const val BOMB = 5

    /** Solid border ring thickness applied after macro structure. */
    const val BORDER = 2
}

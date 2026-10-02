package com.supermetroid.editor.rom

/**
 * Fail-closed boundary for legacy boss sprite-tile project data whose original
 * ROM mappings were disproved by the exact assembly audit.
 *
 * Keep these blockers until the editor has source-backed ownership for every
 * tile range written by the corresponding boss editor.
 */
object BossSpriteExportSafety {
    const val PHANTOON_KEY_PREFIX = "phantoon:"
    const val KRAID_KEY_PREFIX = "kraid:"

    const val PHANTOON_LEGACY_TILE_SHEET_REASON =
        "Phantoon legacy tile-sheet export is temporarily disabled: the previous mapping lands inside " +
            "Mother Brain leg graphics at \$B7:9000..\$B7:9FFF. Use the Phantoon Components editor, " +
            "or reset this legacy tile-sheet edit before export."

    const val KRAID_PIXEL_EDIT_REASON =
        "Kraid sprite pixel export is temporarily disabled: the previous mapping targets the compressed " +
            "BG2 tilemap at \$B9:FA38, not tile graphics. Reset the Kraid pixel edit before export while " +
            "the exact tileset/OAM ownership mapping is completed."

    fun blockedReason(projectKey: String): String? = when {
        projectKey.startsWith(PHANTOON_KEY_PREFIX) -> PHANTOON_LEGACY_TILE_SHEET_REASON
        projectKey.startsWith(KRAID_KEY_PREFIX) -> KRAID_PIXEL_EDIT_REASON
        else -> null
    }
}

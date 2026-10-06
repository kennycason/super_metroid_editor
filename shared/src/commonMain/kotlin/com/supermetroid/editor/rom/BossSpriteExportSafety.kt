package com.supermetroid.editor.rom

/**
 * Fail-closed boundary for legacy boss sprite-tile project data whose original
 * ROM mappings were disproved by the exact assembly audit.
 *
 * These prefixes are permanently legacy-only. Current source-backed component editors
 * use normal tileset keys and never pass through this mapping.
 */
object BossSpriteExportSafety {
    const val PHANTOON_KEY_PREFIX = "phantoon:"
    const val KRAID_KEY_PREFIX = "kraid:"

    const val PHANTOON_LEGACY_TILE_SHEET_REASON =
        "Phantoon legacy tile-sheet export is temporarily disabled: the previous mapping lands inside " +
            "Mother Brain leg graphics at \$B7:9000..\$B7:9FFF. Use the Phantoon Components editor, " +
            "or reset this legacy tile-sheet edit before export."

    const val KRAID_PIXEL_EDIT_REASON =
        "Kraid legacy pixel export is disabled: this old project key targets the compressed BG2 " +
            "tilemap at \$B9:FA38, not tile graphics. Reset the legacy edit; current Kraid head edits " +
            "are safely stored as the complete tileset \$1A graphics resource."

    fun blockedReason(projectKey: String): String? = when {
        projectKey.startsWith(PHANTOON_KEY_PREFIX) -> PHANTOON_LEGACY_TILE_SHEET_REASON
        projectKey.startsWith(KRAID_KEY_PREFIX) -> KRAID_PIXEL_EDIT_REASON
        else -> null
    }
}

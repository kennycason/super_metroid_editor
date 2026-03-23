package com.supermetroid.editor.rom

/**
 * Ridley-specific sprite assembly. Delegates to [BossPoseScanner].
 *
 * Species: $E17F (Ridley), $E13F (Ceres Ridley) — AI bank $A6, 256 tiles.
 */
class RidleySpritemap(private val romParser: RomParser) {

    companion object {
        const val RIDLEY_SPECIES_ID = 0xE17F
        const val CERES_RIDLEY_SPECIES_ID = 0xE13F
    }

    private val scanner = BossPoseScanner(romParser)

    fun loadPoses(speciesId: Int = RIDLEY_SPECIES_ID): List<BossPoseScanner.BossPose> {
        return scanner.scanPoses(speciesId, minEntries = 4)
    }

    fun renderPose(
        pose: BossPoseScanner.BossPose,
        tileData: ByteArray,
        palette: IntArray
    ): EnemySpritemap.AssembledSprite? {
        return scanner.renderPose(pose, tileData, palette)
    }
}

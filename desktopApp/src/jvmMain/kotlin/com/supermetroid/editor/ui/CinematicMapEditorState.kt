package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmCinematicMapAssetAccess
import com.supermetroid.editor.rom.CinematicMapAssets
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomVisualAssets
import com.supermetroid.editor.rom.SpriteAnimationFrame

internal class CinematicMapEditorState(
    private val asmAccess: (String) -> AsmCinematicMapAssetAccess?,
    private val saveAsm: (String, ByteArray, ByteArray) -> Boolean,
    private val restoreAsm: (String) -> Boolean,
    private val onDirty: () -> Unit,
) {
    data class Sheet(
        val visualAsset: RomVisualAssets.Asset,
        val mapAsset: CinematicMapAssets.Asset,
        val rawData: ByteArray,
        val sourceBytes: ByteArray,
    )

    fun load(visualAsset: RomVisualAssets.Asset): Sheet {
        val mapAsset = requireNotNull(CinematicMapAssets.forVisualAsset(visualAsset)) {
            "${visualAsset.name} has no supported reciprocal scene-map model."
        }
        return when (val access = asmAccess(mapAsset.key)) {
            is AsmCinematicMapAssetAccess.Editable -> Sheet(
                visualAsset,
                mapAsset,
                access.rawData.copyOf(),
                access.sourceBytes.copyOf(),
            )
            is AsmCinematicMapAssetAccess.AsmOnly -> throw IllegalStateException(access.reason)
            null -> throw IllegalStateException("Scene-layout editing is available in ASM source mode.")
        }
    }

    fun apply(sheet: Sheet, rawData: ByteArray): Boolean {
        sheet.mapAsset.validate(rawData)
        if (!saveAsm(sheet.mapAsset.key, rawData, sheet.sourceBytes)) return false
        onDirty()
        return true
    }

    fun restore(sourceKey: String): Boolean {
        if (!restoreAsm(sourceKey)) return false
        onDirty()
        return true
    }

    fun hasChanges(sourceKey: String): Boolean =
        (asmAccess(sourceKey) as? AsmCinematicMapAssetAccess.Editable)?.modified == true

    fun render(
        parser: RomParser,
        sheet: Sheet,
        rawData: ByteArray = sheet.rawData,
        frameIndex: Int = 0,
    ): SpriteAnimationFrame? {
        sheet.mapAsset.validate(rawData)
        val frame = sheet.mapAsset.frame(frameIndex)
        val layoutAsset = if (sheet.visualAsset.format == RomVisualAssets.Format.TITLE_SCENE) {
            sheet.visualAsset.copy(format = RomVisualAssets.Format.MODE7_MAP)
        } else sheet.visualAsset
        return RomVisualAssets.render(
            parser,
            layoutAsset.copy(columns = frame.columns),
            mapOverride = sheet.mapAsset.frameData(rawData, frameIndex),
        )
    }
}

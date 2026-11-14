package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.LZ5Codec
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.RomConstants
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmNativeSpriteCompilerTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `sprite pixels and palette survive cache-free source build and restore`() {
        val rom = ByteArray(RomConstants.ROM_SIZE)
        val raw = ByteArray(256) { (it and 3).toByte() }
        val compressed = LZ5Compressor.compress(raw)
        val asset = AsmAssetRange(ASSET_PATH, ASSET_PC, compressed.size + 64)
        ByteArray(asset.length) { 0xFF.toByte() }.also(compressed::copyInto).copyInto(rom, asset.pcOffset)
        val baselinePalette = ByteArray(32) { it.toByte() }
        baselinePalette.copyInto(rom, PALETTE_PC)

        val referenceRoot = File(temporary, "reference")
        val sourceRoot = File(referenceRoot, "src").apply { mkdirs() }
        val dataRoot = File(referenceRoot, "data").apply { mkdirs() }
        File(dataRoot, "base.bin").writeBytes(rom)
        File(dataRoot, ASSET_PATH).writeBytes(
            ByteArray(asset.length) { 0xFF.toByte() }.also(compressed::copyInto),
        )
        File(sourceRoot, "main.asm").writeText(buildString {
            appendLine("lorom")
            for (offset in rom.indices step 0x8000) {
                val address = (0x80 + offset / 0x8000) * 0x10000 + 0x8000
                appendLine("org \$${address.toString(16).uppercase()}")
                appendLine("incbin \"../data/base.bin\":${offset.toString(16)}-${(offset + 0x8000).toString(16)}")
            }
            appendLine("incsrc \"bank_8C.asm\"")
            appendLine("incsrc \"bank_95.asm\"")
            appendLine("print \"Assembly complete. Total bytes written: \", bytes")
        })
        File(sourceRoot, "bank_95.asm").writeText(
            """
            org ${'$'}9580D8
            NativeSprite_Test:
                incbin "../data/$ASSET_PATH"
            """.trimIndent() + "\n",
        )
        File(sourceRoot, "bank_8C.asm").writeText(paletteSource(baselinePalette))
        val reference = AsmReferenceWorkspace(
            referenceRoot,
            AsmReferenceMetadata(
                AsmReferenceContract.REPOSITORY_URL,
                AsmReferenceContract.COMMIT,
                "fixture",
                "fixture.sfc",
                AsmVerifiedCache.hash(rom),
                rom.size,
                1,
            ),
            AsmSourceParser().parse(sourceRoot, dataRoot, listOf(asset)),
        )
        val projectFile = File(temporary, "NativeSprite.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(
            assetRanges = listOf(asset),
            starterModules = emptyList(),
            augmentation = AsmSourceAugmentation.NONE,
        )
        repository.initialize(projectFile.path, reference)

        val graphics = AsmNativeSpriteAssetBridge(repository)
        val openedGraphics = assertIs<AsmNativeSpriteAssetAccess.Editable>(
            graphics.access(projectFile.path, SOURCE_KEY),
        )
        val editedRaw = raw.copyOf().also { it[0] = 0x0F }
        val savedGraphics = graphics.save(
            projectFile.path,
            SOURCE_KEY,
            editedRaw,
            openedGraphics.sourceBytes,
        )
        val palettes = AsmNativeSpritePaletteBridge(repository)
        val openedPalette = assertIs<AsmNativeSpritePaletteAccess.Editable>(
            palettes.access(projectFile.path, PALETTE_ADDRESS),
        )
        val editedPalette = openedPalette.rawPalette.copyOf().also { it[2] = 0x55 }
        val savedPalette = palettes.save(
            projectFile.path,
            PALETTE_ADDRESS,
            editedPalette,
            openedPalette.sourceText,
        )
        assertTrue(File(repository.loadForBuild(projectFile.path)!!.workingDirectory, "src/bank_8C.asm")
            .readText().contains("\$0355"))
        assertTrue("bank_8C.asm" in repository.load(projectFile.path)!!.modifiedFileIds)

        val compiled = AsmProjectCompiler(repository).compile(projectFile.path, rom)
        assertContentEquals(editedRaw, LZ5Codec.decompress(
            compiled.romBytes.copyOfRange(asset.pcOffset, asset.pcOffset + asset.length),
        ).data)
        assertContentEquals(editedPalette, compiled.romBytes.copyOfRange(PALETTE_PC, PALETTE_PC + 32))

        AsmProjectCompiler.clearBuildCacheForTests()
        val coldRepository = AsmProjectWorkspaceRepository(
            assetRanges = listOf(asset),
            starterModules = emptyList(),
            augmentation = AsmSourceAugmentation.NONE,
        )
        val cold = AsmProjectCompiler(coldRepository).compile(projectFile.path, rom)
        assertContentEquals(compiled.romBytes, cold.romBytes)

        val restoredGraphics = graphics.restore(projectFile.path, SOURCE_KEY, savedGraphics.sourceBytes)
        val restoredPalette = palettes.restore(projectFile.path, PALETTE_ADDRESS, savedPalette.sourceText)
        assertFalse(restoredGraphics.modified)
        assertFalse(restoredPalette.modified)
        val restored = AsmProjectCompiler(repository).compile(projectFile.path, rom)
        assertContentEquals(raw, LZ5Codec.decompress(
            restored.romBytes.copyOfRange(asset.pcOffset, asset.pcOffset + asset.length),
        ).data)
        assertContentEquals(baselinePalette, restored.romBytes.copyOfRange(PALETTE_PC, PALETTE_PC + 32))
        assertTrue(repository.assetOverridePaths(projectFile.path).isEmpty())
    }

    private fun paletteSource(bytes: ByteArray): String {
        val words = (0 until 16).map { index ->
            val word = (bytes[index * 2].toInt() and 0xFF) or ((bytes[index * 2 + 1].toInt() and 0xFF) shl 8)
            "\$${word.toString(16).uppercase().padStart(4, '0')}"
        }
        return """
            org ${'$'}8CE1E9
            Palettes_TitleScreen:
                dw ${words.take(8).joinToString(",")} ;8CE1E9;
                dw ${words.drop(8).joinToString(",")} ;8CE1F9;
        """.trimIndent() + "\n"
    }

    private companion object {
        const val ASSET_PATH = "Tiles_Title_Sprite.bin"
        const val SOURCE_KEY = "9580D8"
        const val ASSET_PC = 0x0A80D8
        const val PALETTE_ADDRESS = 0x8CE1E9
        const val PALETTE_PC = 0x0661E9
    }
}

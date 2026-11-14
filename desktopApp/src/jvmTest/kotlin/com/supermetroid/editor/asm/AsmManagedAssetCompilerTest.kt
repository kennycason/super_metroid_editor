package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.RomConstants
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AsmManagedAssetCompilerTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `relocated tileset asset compiles in verified expansion and cold rebuild retains symbolic pointer`() {
        val raw = ByteArray(64)
        val compressed = LZ5Compressor.compress(raw)
        val edited = ByteArray(64) { index -> ((index * 73 + index / 5) and 0xFF).toByte() }
        assertTrue(LZ5Compressor.compress(edited).size > compressed.size)
        val asset = AsmAssetRange("Tiles_Shared.bin", 0x100, compressed.size)
        val rom = ByteArray(RomConstants.ROM_SIZE)
        compressed.copyInto(rom, asset.pcOffset)
        val tablePc = requireNotNull(snesLoRomToPc(0x8FE6A2))
        val originalAddress = requireNotNull(pcToSnesLoRom(asset.pcOffset))
        repeat(3) { field ->
            val at = tablePc + field * 3
            rom[at] = originalAddress.toByte()
            rom[at + 1] = (originalAddress ushr 8).toByte()
            rom[at + 2] = (originalAddress ushr 16).toByte()
        }
        val referenceRoot = File(temporary, "reference")
        val sourceRoot = File(referenceRoot, "src").apply { mkdirs() }
        val dataRoot = File(referenceRoot, "data").apply { mkdirs() }
        File(dataRoot, "base.bin").writeBytes(rom)
        File(dataRoot, asset.path).writeBytes(compressed)
        File(sourceRoot, "main.asm").writeText(buildString {
            appendLine("lorom")
            for (offset in rom.indices step 0x8000) {
                val address = (0x80 + offset / 0x8000) * 0x10000 + 0x8000
                appendLine("org ${'$'}${address.toString(16).uppercase()}")
                appendLine("incbin \"../data/base.bin\":${offset.toString(16)}-${(offset + 0x8000).toString(16)}")
            }
            appendLine("incsrc \"bank_8F.asm\"")
            appendLine("incsrc \"bank_B9..CE.asm\"")
            appendLine("print \"Assembly complete. Total bytes written: \", bytes")
        })
        File(sourceRoot, "bank_8F.asm").writeText(
            """
            org ${'$'}8FE6A2
            Tileset_Table_0_Test:
                dl Tiles_Shared
                dl Tiles_Shared
                dl Tiles_Shared
            """.trimIndent() + "\n",
        )
        File(sourceRoot, "bank_B9..CE.asm").writeText(
            """
            org ${'$'}${originalAddress.toString(16).uppercase()}
            Tiles_Shared:
            incbin "../data/Tiles_Shared.bin"
            """.trimIndent() + "\n",
        )
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
        val projectFile = File(temporary, "Managed.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(
            assetRanges = listOf(asset),
            starterModules = emptyList(),
            augmentation = AsmSourceAugmentation.NONE,
        )
        repository.initialize(projectFile.path, reference)
        val bridge = AsmTilesetGraphicsBridge(repository)
        val opened = bridge.access(projectFile.path, AsmTilesetGraphicsKind.AREA_TILES, 0)
            as AsmTilesetGraphicsAccess.Editable
        val relocated = bridge.relocate(
            projectFile.path,
            AsmTilesetGraphicsKind.AREA_TILES,
            0,
            edited,
            opened.sourceBytes,
        )

        val noSpace = assertFailsWith<AsmCompilationException> {
            AsmProjectCompiler(repository).compile(
                projectFile.path,
                rom,
                outputRomSize = RomConstants.ROM_SIZE,
            )
        }
        assertTrue(noSpace.message.orEmpty().contains("needs ${relocated.sourceBytes.size} contiguous bytes"))
        assertTrue(noSpace.message.orEmpty().contains("Increase project ROM capacity"))

        val compiled = AsmProjectCompiler(repository).compile(
            projectFile.path,
            rom,
            outputRomSize = RomConstants.EXPANDED_ROM_SIZE,
        )
        val claim = compiled.generatedAssetClaims.single { it.owner.startsWith("asm-managed-asset:") }
        assertTrue(requireNotNull(pcToSnesLoRom(claim.pcOffset)) ushr 16 >= 0xE0)
        assertContentEquals(relocated.sourceBytes, claim.bytes)
        assertContentEquals(claim.bytes, compiled.romBytes.copyOfRange(claim.pcOffset, claim.endExclusive))
        assertContentEquals(compressed, compiled.romBytes.copyOfRange(asset.pcOffset, asset.endExclusive))
        val compiledPointer = (compiled.romBytes[tablePc].toInt() and 0xFF) or
            ((compiled.romBytes[tablePc + 1].toInt() and 0xFF) shl 8) or
            ((compiled.romBytes[tablePc + 2].toInt() and 0xFF) shl 16)
        assertEquals(requireNotNull(pcToSnesLoRom(claim.pcOffset)), compiledPointer)
        assertTrue(compiled.sourceOwnedRanges.none { claim.pcOffset in it.pcOffset until it.pcOffset + it.length })

        AsmProjectCompiler.clearBuildCacheForTests()
        val reopenedRepository = AsmProjectWorkspaceRepository(
            assetRanges = listOf(asset),
            starterModules = emptyList(),
            augmentation = AsmSourceAugmentation.NONE,
        )
        val cold = AsmProjectCompiler(reopenedRepository).compile(
            projectFile.path,
            rom,
            outputRomSize = RomConstants.EXPANDED_ROM_SIZE,
        )
        val coldClaim = cold.generatedAssetClaims.single { it.owner.startsWith("asm-managed-asset:") }
        assertEquals(claim.pcOffset, coldClaim.pcOffset)
        assertContentEquals(claim.bytes, coldClaim.bytes)

        val restored = bridge.restore(
            projectFile.path,
            AsmTilesetGraphicsKind.AREA_TILES,
            0,
            relocated.sourceBytes,
        )
        assertFalse(restored.modified)
        val native = AsmProjectCompiler(repository).compile(
            projectFile.path,
            rom,
            outputRomSize = RomConstants.EXPANDED_ROM_SIZE,
        )
        assertTrue(native.generatedAssetClaims.none { it.owner.startsWith("asm-managed-asset:") })
        assertEquals(originalAddress, (native.romBytes[tablePc].toInt() and 0xFF) or
            ((native.romBytes[tablePc + 1].toInt() and 0xFF) shl 8) or
            ((native.romBytes[tablePc + 2].toInt() and 0xFF) shl 16))
    }

    @Test
    fun `all tileset asset kinds relocate together and compile every supported consumer`() {
        val rawAssets = linkedMapOf(
            "AreaTiles.bin" to ByteArray(64),
            "AreaMetatiles.bin" to ByteArray(16),
            "Palette.bin" to ByteArray(256),
            "CRETiles.bin" to ByteArray(64),
            "CREMetatiles.bin" to ByteArray(16),
        )
        val labels = linkedMapOf(
            "AreaTiles.bin" to "AreaTiles",
            "AreaMetatiles.bin" to "AreaMetatiles",
            "Palette.bin" to "AreaPalette",
            "CRETiles.bin" to "CRE_Tiles_Compressed",
            "CREMetatiles.bin" to "CRE_TileTable_Compressed",
        )
        var nextPc = 0x100
        val assets = rawAssets.map { (path, raw) ->
            val compressed = LZ5Compressor.compress(raw)
            AsmAssetRange(path, nextPc, compressed.size).also { nextPc += compressed.size }
        }
        val rom = ByteArray(RomConstants.ROM_SIZE)
        assets.forEach { asset -> LZ5Compressor.compress(rawAssets.getValue(asset.path)).copyInto(rom, asset.pcOffset) }
        val tableAddress = 0x8F9000
        val tablePc = requireNotNull(snesLoRomToPc(tableAddress))
        fun write24(pc: Int, address: Int) {
            rom[pc] = address.toByte()
            rom[pc + 1] = (address ushr 8).toByte()
            rom[pc + 2] = (address ushr 16).toByte()
        }
        val nativeAddress = assets.associate { it.path to requireNotNull(pcToSnesLoRom(it.pcOffset)) }
        write24(tablePc, nativeAddress.getValue("AreaMetatiles.bin"))
        write24(tablePc + 3, nativeAddress.getValue("AreaTiles.bin"))
        write24(tablePc + 6, nativeAddress.getValue("Palette.bin"))
        val creCodeAddress = 0x82E000
        val creCodePc = requireNotNull(snesLoRomToPc(creCodeAddress))
        fun writeLda(pc: Int, value: Int) {
            rom[pc] = 0xA9.toByte(); rom[pc + 1] = value.toByte(); rom[pc + 2] = (value ushr 8).toByte()
        }
        val creTilesNative = nativeAddress.getValue("CRETiles.bin")
        val creMetatilesNative = nativeAddress.getValue("CREMetatiles.bin")
        writeLda(creCodePc, creTilesNative ushr 8 and 0xFF00)
        writeLda(creCodePc + 3, creTilesNative and 0xFFFF)
        writeLda(creCodePc + 6, creMetatilesNative ushr 8 and 0xFF00)
        writeLda(creCodePc + 9, creMetatilesNative and 0xFFFF)

        val referenceRoot = File(temporary, "all-kinds-reference")
        val sourceRoot = File(referenceRoot, "src").apply { mkdirs() }
        val dataRoot = File(referenceRoot, "data").apply { mkdirs() }
        File(dataRoot, "base.bin").writeBytes(rom)
        assets.forEach { asset ->
            File(dataRoot, asset.path).writeBytes(LZ5Compressor.compress(rawAssets.getValue(asset.path)))
        }
        File(sourceRoot, "main.asm").writeText(buildString {
            appendLine("lorom")
            appendLine("arch 65816")
            for (offset in rom.indices step 0x8000) {
                val address = (0x80 + offset / 0x8000) * 0x10000 + 0x8000
                appendLine("org ${'$'}${address.toString(16).uppercase()}")
                appendLine("incbin \"../data/base.bin\":${offset.toString(16)}-${(offset + 0x8000).toString(16)}")
            }
            appendLine("incsrc \"bank_82.asm\"")
            appendLine("incsrc \"bank_8F.asm\"")
            appendLine("incsrc \"bank_B9..CE.asm\"")
            appendLine("print \"Assembly complete. Total bytes written: \", bytes")
        })
        File(sourceRoot, "bank_82.asm").writeText(
            """
            org ${'$'}82E000
            LoadCRE:
                LDA.W #CRE_Tiles_Compressed>>8&${'$'}FF00
                LDA.W #CRE_Tiles_Compressed
                LDA.W #CRE_TileTable_Compressed>>8&${'$'}FF00
                LDA.W #CRE_TileTable_Compressed
            """.trimIndent() + "\n",
        )
        File(sourceRoot, "bank_8F.asm").writeText(
            """
            org ${'$'}8F9000
            Tileset_Table_0_Test:
                dl AreaMetatiles
                dl AreaTiles
                dl AreaPalette
            """.trimIndent() + "\n",
        )
        File(sourceRoot, "bank_B9..CE.asm").writeText(buildString {
            assets.forEach { asset ->
                appendLine("org ${'$'}${nativeAddress.getValue(asset.path).toString(16).uppercase()}")
                appendLine("${labels.getValue(asset.path)}:")
                appendLine("incbin \"../data/${asset.path}\"")
            }
        })
        val reference = AsmReferenceWorkspace(
            referenceRoot,
            AsmReferenceMetadata(
                AsmReferenceContract.REPOSITORY_URL,
                AsmReferenceContract.COMMIT,
                "fixture",
                "fixture.sfc",
                AsmVerifiedCache.hash(rom),
                rom.size,
                assets.size,
            ),
            AsmSourceParser().parse(sourceRoot, dataRoot, assets),
        )
        val projectFile = File(temporary, "AllKinds.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(
            assetRanges = assets,
            starterModules = emptyList(),
            augmentation = AsmSourceAugmentation.NONE,
        )
        repository.initialize(projectFile.path, reference)
        val graphics = AsmTilesetGraphicsBridge(repository)
        val palette = AsmTilesetPaletteBridge(repository)
        val editedByKind = AsmTilesetGraphicsKind.entries.associateWith { kind ->
            val access = graphics.access(projectFile.path, kind, if (kind.name.startsWith("AREA")) 0 else null)
                as AsmTilesetGraphicsAccess.Editable
            ByteArray(access.rawData.size) { index -> ((index * 73 + kind.ordinal * 29 + index / 3) and 0xFF).toByte() }
                .also { edited -> graphics.relocate(projectFile.path, kind, access.key.tilesetId, edited, access.sourceBytes) }
        }
        val paletteAccess = palette.access(projectFile.path, 0) as AsmTilesetPaletteAccess.Editable
        val editedPalette = ByteArray(256) { index -> ((index * 61 + index / 7) and 0xFF).toByte() }
        palette.relocate(projectFile.path, 0, editedPalette, paletteAccess.sourceBytes)

        val compiled = AsmProjectCompiler(repository).compile(
            projectFile.path,
            rom,
            outputRomSize = RomConstants.EXPANDED_ROM_SIZE,
        )
        val claims = compiled.generatedAssetClaims.filter { it.owner.startsWith("asm-managed-asset:") }
        assertEquals(5, claims.size)
        val reopenedGraphics = AsmTilesetGraphicsBridge(repository)
        editedByKind.forEach { (kind, edited) ->
            assertContentEquals(edited, (reopenedGraphics.access(
                projectFile.path, kind, if (kind.name.startsWith("AREA")) 0 else null,
            ) as AsmTilesetGraphicsAccess.Editable).rawData)
        }
        assertContentEquals(editedPalette, (AsmTilesetPaletteBridge(repository).access(
            projectFile.path, 0,
        ) as AsmTilesetPaletteAccess.Editable).rawPalette)
    }
}


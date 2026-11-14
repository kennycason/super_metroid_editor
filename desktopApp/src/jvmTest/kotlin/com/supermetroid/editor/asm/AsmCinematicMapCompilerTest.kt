package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.CinematicMapAssets
import com.supermetroid.editor.rom.LZ5Codec
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.RomConstants
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmCinematicMapCompilerTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `scene map survives cache-free source build and restore`() {
        val fixture = fixture(tightEnvelope = false)
        val bridge = AsmCinematicMapAssetBridge(fixture.repository)
        val opened = assertIs<AsmCinematicMapAssetAccess.Editable>(
            bridge.access(fixture.projectFile.path, MAP.key),
        )
        val edited = MAP.withEntry(opened.rawData, 41, 7, 0xA5)
        val saved = bridge.save(fixture.projectFile.path, MAP.key, edited, opened.sourceBytes)

        val compiled = AsmProjectCompiler(fixture.repository).compile(fixture.projectFile.path, fixture.rom)
        assertContentEquals(edited, decodedAt(compiled.romBytes, MAP.address, fixture.asset.length))

        AsmProjectCompiler.clearBuildCacheForTests()
        val coldRepository = fixture.newRepository()
        val cold = AsmProjectCompiler(coldRepository).compile(fixture.projectFile.path, fixture.rom)
        assertContentEquals(compiled.romBytes, cold.romBytes)

        val restored = bridge.restore(fixture.projectFile.path, MAP.key, saved.sourceBytes)
        assertFalse(restored.modified)
        val native = AsmProjectCompiler(fixture.repository).compile(fixture.projectFile.path, fixture.rom)
        assertContentEquals(fixture.raw, decodedAt(native.romBytes, MAP.address, fixture.asset.length))
    }

    @Test
    fun `relocated scene map compiles bytes and split pointer consumers after cold reopen`() {
        val fixture = fixture(tightEnvelope = true)
        val bridge = AsmCinematicMapAssetBridge(fixture.repository)
        val opened = assertIs<AsmCinematicMapAssetAccess.Editable>(
            bridge.access(fixture.projectFile.path, MAP.key),
        )
        val noisy = ByteArray(MAP.rawBytes).also { Random(0x2B).nextBytes(it) }
        val relocated = bridge.relocate(
            fixture.projectFile.path,
            MAP.key,
            noisy,
            opened.sourceBytes,
        )

        val compiled = AsmProjectCompiler(fixture.repository).compile(
            fixture.projectFile.path,
            fixture.rom,
            outputRomSize = RomConstants.EXPANDED_ROM_SIZE,
        )
        val claim = compiled.generatedAssetClaims.single { it.owner.startsWith("asm-managed-asset:") }
        val placement = compiled.segmentPlacements.single { it.request.owner == claim.owner }
        assertEquals(claim.pcOffset, placement.allocation.pcOffset)
        assertEquals(claim.bytes.size, placement.request.size)
        assertContentEquals(relocated.sourceBytes, claim.bytes)
        assertContentEquals(noisy, LZ5Codec.decompress(claim.bytes, maxOutputSize = MAP.rawBytes).data)
        assertContentEquals(claim.bytes, compiled.romBytes.copyOfRange(claim.pcOffset, claim.endExclusive))
        assertContentEquals(
            fixture.rom.copyOfRange(fixture.asset.pcOffset, fixture.asset.endExclusive),
            compiled.romBytes.copyOfRange(fixture.asset.pcOffset, fixture.asset.endExclusive),
        )

        val destination = requireNotNull(pcToSnesLoRom(claim.pcOffset))
        val pointerPc = requireNotNull(snesLoRomToPc(POINTER_ADDRESS))
        assertEquals(0xA9, compiled.romBytes[pointerPc].toInt() and 0xFF)
        assertEquals(destination ushr 8 and 0xFF00, readWord(compiled.romBytes, pointerPc + 1))
        assertEquals(0xA9, compiled.romBytes[pointerPc + 3].toInt() and 0xFF)
        assertEquals(destination and 0xFFFF, readWord(compiled.romBytes, pointerPc + 4))

        AsmProjectCompiler.clearBuildCacheForTests()
        val cold = AsmProjectCompiler(fixture.newRepository()).compile(
            fixture.projectFile.path,
            fixture.rom,
            outputRomSize = RomConstants.EXPANDED_ROM_SIZE,
        )
        val coldClaim = cold.generatedAssetClaims.single { it.owner.startsWith("asm-managed-asset:") }
        assertEquals(claim.pcOffset, coldClaim.pcOffset)
        assertContentEquals(claim.bytes, coldClaim.bytes)

        val restored = bridge.restore(fixture.projectFile.path, MAP.key, relocated.sourceBytes)
        assertFalse(restored.modified)
        val native = AsmProjectCompiler(fixture.repository).compile(
            fixture.projectFile.path,
            fixture.rom,
            outputRomSize = RomConstants.EXPANDED_ROM_SIZE,
        )
        assertTrue(native.generatedAssetClaims.none { it.owner.startsWith("asm-managed-asset:") })
        assertContentEquals(fixture.raw, decodedAt(native.romBytes, MAP.address, fixture.asset.length))
    }

    private data class Fixture(
        val projectFile: File,
        val repository: AsmProjectWorkspaceRepository,
        val asset: AsmAssetRange,
        val raw: ByteArray,
        val rom: ByteArray,
    ) {
        fun newRepository() = AsmProjectWorkspaceRepository(
            assetRanges = listOf(asset),
            starterModules = emptyList(),
            augmentation = AsmSourceAugmentation.NONE,
        )
    }

    private fun fixture(tightEnvelope: Boolean): Fixture {
        val raw = ByteArray(MAP.rawBytes) { (it / 64 and 3).toByte() }
        val compressed = LZ5Compressor.compress(raw)
        val capacity = compressed.size + if (tightEnvelope) 0 else 64
        val asset = AsmAssetRange(ASSET_PATH, requireNotNull(snesLoRomToPc(MAP.address)), capacity)
        val rom = ByteArray(RomConstants.ROM_SIZE)
        ByteArray(capacity) { 0xFF.toByte() }.also(compressed::copyInto).copyInto(rom, asset.pcOffset)

        val referenceRoot = File(temporary, "reference-${temporary.listFiles().orEmpty().size}")
        val sourceRoot = File(referenceRoot, "src").apply { mkdirs() }
        val dataRoot = File(referenceRoot, "data").apply { mkdirs() }
        File(dataRoot, "base.bin").writeBytes(rom)
        File(dataRoot, ASSET_PATH).writeBytes(
            ByteArray(capacity) { 0xFF.toByte() }.also(compressed::copyInto),
        )
        File(sourceRoot, "main.asm").writeText(buildString {
            appendLine("lorom")
            appendLine("arch 65816")
            for (offset in rom.indices step 0x8000) {
                val address = (0x80 + offset / 0x8000) * 0x10000 + 0x8000
                appendLine("org ${'$'}${address.toString(16).uppercase()}")
                appendLine("incbin \"../data/base.bin\":${offset.toString(16)}-${(offset + 0x8000).toString(16)}")
            }
            appendLine("incsrc \"$POINTER_FILE\"")
            appendLine("incsrc \"$SOURCE_FILE\"")
            appendLine("print \"Assembly complete. Total bytes written: \", bytes")
        })
        File(sourceRoot, SOURCE_FILE).writeText(
            """
            org ${'$'}${MAP.address.toString(16).uppercase()}
            ${MAP.sourceLabel}:
                incbin "../data/$ASSET_PATH"
            """.trimIndent() + "\n",
        )
        File(sourceRoot, POINTER_FILE).writeText(
            """
            org ${'$'}${POINTER_ADDRESS.toString(16).uppercase()}
            LoadTitleMap:
                LDA.W #${MAP.sourceLabel}>>8&${'$'}FF00
                LDA.W #${MAP.sourceLabel}
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
        val projectFile = File(temporary, "project-${temporary.listFiles().orEmpty().size}/Cinematic.smedit").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        val repository = AsmProjectWorkspaceRepository(
            assetRanges = listOf(asset),
            starterModules = emptyList(),
            augmentation = AsmSourceAugmentation.NONE,
        )
        repository.initialize(projectFile.path, reference)
        return Fixture(projectFile, repository, asset, raw, rom)
    }

    private fun decodedAt(rom: ByteArray, address: Int, length: Int): ByteArray {
        val pc = requireNotNull(snesLoRomToPc(address))
        return LZ5Codec.decompress(
            rom.copyOfRange(pc, pc + length),
            maxOutputSize = MAP.rawBytes,
        ).data
    }

    private fun readWord(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private companion object {
        val MAP = CinematicMapAssets.source("96FC04")
        const val ASSET_PATH = "Title_Mode7_Tilemap.bin"
        const val SOURCE_FILE = "bank_94..99.asm"
        const val POINTER_FILE = "bank_8B.asm"
        const val POINTER_ADDRESS = 0x8BE000
    }
}

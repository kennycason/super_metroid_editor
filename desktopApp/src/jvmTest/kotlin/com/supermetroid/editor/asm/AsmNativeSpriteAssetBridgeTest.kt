package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.LZ5Compressor
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmNativeSpriteAssetBridgeTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `native sprite pixels save reopen and restore through source authority`() {
        val fixture = fixture()
        val opened = assertIs<AsmNativeSpriteAssetAccess.Editable>(
            fixture.bridge.access(fixture.project.path, SOURCE_KEY),
        )
        assertFalse(opened.modified)
        assertContentEquals(fixture.raw, opened.rawData)
        assertContentEquals(fixture.raw, opened.baselineRawData)

        val edited = opened.rawData.copyOf().also { it[0] = 0x31 }
        val saved = fixture.bridge.save(fixture.project.path, SOURCE_KEY, edited, opened.sourceBytes)
        assertTrue(saved.modified)
        assertContentEquals(edited, saved.rawData)
        assertContentEquals(
            saved.sourceBytes,
            AsmProjectWorkspaceRepository(starterModules = emptyList())
                .readAsset(fixture.project.path, ASSET_PATH),
        )

        val reopened = AsmNativeSpriteAssetBridge(
            AsmProjectWorkspaceRepository(starterModules = emptyList()),
        ).access(fixture.project.path, SOURCE_KEY)
        assertContentEquals(edited, assertIs<AsmNativeSpriteAssetAccess.Editable>(reopened).rawData)

        val restored = fixture.bridge.restore(fixture.project.path, SOURCE_KEY, saved.sourceBytes)
        assertFalse(restored.modified)
        assertContentEquals(fixture.raw, restored.rawData)
        assertTrue(fixture.repository.assetOverridePaths(fixture.project.path).isEmpty())
    }

    @Test
    fun `native sprite source fails closed for custom shapes stale saves and invalid tiles`() {
        val fixture = fixture()
        val opened = assertIs<AsmNativeSpriteAssetAccess.Editable>(
            fixture.bridge.access(fixture.project.path, SOURCE_KEY),
        )
        val saved = fixture.bridge.save(
            fixture.project.path,
            SOURCE_KEY,
            opened.rawData.copyOf().also { it[1] = 0x22 },
            opened.sourceBytes,
        )
        assertFailsWith<IllegalArgumentException> {
            fixture.bridge.save(fixture.project.path, SOURCE_KEY, opened.rawData, opened.sourceBytes)
        }
        assertFailsWith<IllegalArgumentException> {
            fixture.bridge.save(
                fixture.project.path,
                SOURCE_KEY,
                saved.rawData.copyOf(saved.rawData.size - 1),
                saved.sourceBytes,
            )
        }

        val source = File(fixture.workspace.workingDirectory, "src/bank_95.asm")
        val custom = source.readText().replace(
            "incbin \"../data/$ASSET_PATH\"",
            "incbin \"../data/$ASSET_PATH\":0-10",
        )
        source.writeText(custom)
        File(fixture.workspace.root, "overrides/src/bank_95.asm").apply {
            parentFile.mkdirs()
            writeText(custom)
        }
        val rejected = assertIs<AsmNativeSpriteAssetAccess.AsmOnly>(
            fixture.bridge.access(fixture.project.path, SOURCE_KEY),
        )
        assertTrue(rejected.reason.contains("not one direct"))
    }

    @Test
    fun `compressed sprite growth relocates to managed source and restores atomically`() {
        val fixture = fixture(tightEnvelope = true)
        val opened = assertIs<AsmNativeSpriteAssetAccess.Editable>(
            fixture.bridge.access(fixture.project.path, SOURCE_KEY),
        )
        val incompressible = ByteArray(opened.rawData.size) { index ->
            ((index * 73 + index / 5) and 0xFF).toByte()
        }
        val overflow = assertFailsWith<AsmAssetRelocationRequiredException> {
            fixture.bridge.save(fixture.project.path, SOURCE_KEY, incompressible, opened.sourceBytes)
        }
        assertTrue(overflow.requiredBytes > overflow.inPlaceCapacity)
        assertNotNull(opened.relocation)

        val relocated = fixture.bridge.relocate(
            fixture.project.path,
            SOURCE_KEY,
            incompressible,
            opened.sourceBytes,
        )
        assertTrue(relocated.modified)
        assertNotNull(relocated.managedAssetId)
        assertContentEquals(incompressible, relocated.rawData)
        assertEquals(1, fixture.repository.managedAssets(fixture.project.path).size)
        assertTrue(File(fixture.workspace.workingDirectory, "src/bank_95.asm").readText()
            .contains("smedit:managed-asset-v1 begin"))

        val restored = fixture.bridge.restore(fixture.project.path, SOURCE_KEY, relocated.sourceBytes)
        assertFalse(restored.modified)
        assertContentEquals(fixture.raw, restored.rawData)
        assertTrue(fixture.repository.managedAssets(fixture.project.path).isEmpty())
        assertFalse(File(fixture.workspace.workingDirectory, "src/bank_95.asm").readText()
            .contains("smedit:managed-asset-v1 begin"))
    }

    private data class Fixture(
        val project: File,
        val workspace: AsmProjectWorkspace,
        val repository: AsmProjectWorkspaceRepository,
        val bridge: AsmNativeSpriteAssetBridge,
        val raw: ByteArray,
    )

    private fun fixture(tightEnvelope: Boolean = false): Fixture {
        val raw = ByteArray(256) { (it and 7).toByte() }
        val compressed = LZ5Compressor.compress(raw)
        val capacity = compressed.size + if (tightEnvelope) 1 else 32
        val asset = AsmAssetRange(ASSET_PATH, requireNotNull(snesLoRomToPc(SOURCE_ADDRESS)), capacity)
        val root = File(temporary, "reference-${temporary.listFiles().orEmpty().size}")
        val source = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(source, "main.asm").writeText("incsrc bank_95.asm\n")
        File(source, "bank_95.asm").writeText(
            """
            Pointer_Test:
                dl NativeSprite_Test
            NativeSprite_Test:
                incbin "../data/$ASSET_PATH"
            """.trimIndent(),
        )
        File(data, ASSET_PATH).writeBytes(
            ByteArray(capacity) { 0xFF.toByte() }.also(compressed::copyInto),
        )
        val metadata = AsmReferenceMetadata(
            AsmReferenceContract.REPOSITORY_URL,
            AsmReferenceContract.COMMIT,
            "fixture",
            "fixture.sfc",
            "ab".repeat(32),
            0x300000,
            1,
        )
        val reference = AsmReferenceWorkspace(
            root,
            metadata,
            AsmSourceParser().parse(source, data, listOf(asset)),
        )
        val project = File(temporary, "project-${temporary.listFiles().orEmpty().size}/Test.smedit").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        val repository = AsmProjectWorkspaceRepository(listOf(asset), starterModules = emptyList())
        val workspace = repository.initialize(project.path, reference)
        return Fixture(project, workspace, repository, AsmNativeSpriteAssetBridge(repository), raw)
    }

    private companion object {
        const val SOURCE_ADDRESS = 0x9580D8
        const val SOURCE_KEY = "9580D8"
        const val ASSET_PATH = "Tiles_Title_Sprite.bin"
    }
}

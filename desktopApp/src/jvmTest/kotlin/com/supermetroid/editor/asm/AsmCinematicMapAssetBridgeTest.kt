package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.CinematicMapAssets
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.TestRomHelper
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.random.Random
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmCinematicMapAssetBridgeTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `scene map saves reopens and restores through one source asset`() {
        val fixture = fixture()
        val opened = assertIs<AsmCinematicMapAssetAccess.Editable>(
            fixture.bridge.access(fixture.project.path, MAP.key),
        )
        assertFalse(opened.modified)
        assertContentEquals(fixture.raw, opened.rawData)

        val edited = MAP.withEntry(opened.rawData, 17, 3, 0x6A)
        val saved = fixture.bridge.save(fixture.project.path, MAP.key, edited, opened.sourceBytes)
        assertTrue(saved.modified)
        assertContentEquals(edited, saved.rawData)
        assertFailsWith<IllegalArgumentException> {
            fixture.bridge.save(fixture.project.path, MAP.key, fixture.raw, opened.sourceBytes)
        }

        val reopened = AsmCinematicMapAssetBridge(
            AsmProjectWorkspaceRepository(listOf(fixture.asset), starterModules = emptyList()),
        ).access(fixture.project.path, MAP.key)
        assertContentEquals(edited, assertIs<AsmCinematicMapAssetAccess.Editable>(reopened).rawData)

        val restored = fixture.bridge.restore(fixture.project.path, MAP.key, saved.sourceBytes)
        assertFalse(restored.modified)
        assertContentEquals(fixture.raw, restored.rawData)
        assertTrue(fixture.repository.assetOverridePaths(fixture.project.path).isEmpty())
    }

    @Test
    fun `scene map growth relocates and restore returns the direct declaration`() {
        val fixture = fixture(tightEnvelope = true)
        val opened = assertIs<AsmCinematicMapAssetAccess.Editable>(
            fixture.bridge.access(fixture.project.path, MAP.key),
        )
        val noisy = ByteArray(MAP.rawBytes).also { Random(0x2B).nextBytes(it) }
        val overflow = assertFailsWith<AsmAssetRelocationRequiredException> {
            fixture.bridge.save(fixture.project.path, MAP.key, noisy, opened.sourceBytes)
        }
        assertTrue(overflow.requiredBytes > overflow.inPlaceCapacity)
        assertNotNull(opened.relocation)

        val relocated = fixture.bridge.relocate(
            fixture.project.path,
            MAP.key,
            noisy,
            opened.sourceBytes,
        )
        assertTrue(relocated.modified)
        assertNotNull(relocated.managedAssetId)
        assertContentEquals(noisy, relocated.rawData)
        assertTrue(File(fixture.workspace.workingDirectory, "src/bank_94..99.asm").readText()
            .contains("smedit:managed-asset-v1 begin"))

        val restored = fixture.bridge.restore(fixture.project.path, MAP.key, relocated.sourceBytes)
        assertFalse(restored.modified)
        assertContentEquals(fixture.raw, restored.rawData)
        assertTrue(fixture.repository.managedAssets(fixture.project.path).isEmpty())
    }

    @Test
    fun `custom scene map declaration fails closed`() {
        val fixture = fixture()
        val source = File(fixture.workspace.workingDirectory, "src/bank_94..99.asm")
        val custom = source.readText().replace(
            "incbin \"../data/$ASSET_PATH\"",
            "incbin \"../data/$ASSET_PATH\":0-10",
        )
        source.writeText(custom)
        File(fixture.workspace.root, "overrides/src/bank_94..99.asm").apply {
            parentFile.mkdirs()
            writeText(custom)
        }

        val rejected = assertIs<AsmCinematicMapAssetAccess.AsmOnly>(
            fixture.bridge.access(fixture.project.path, MAP.key),
        )
        assertTrue(rejected.reason.contains("not one direct"))
    }

    @Test
    fun `native scene catalog resolves exact pinned assets and relocation consumers`() {
        val rom = TestRomHelper.requireRomBytes()
        val root = TestRomHelper.requireDisassemblyDir()
        val sourceRoot = File(root, "src")
        val dataRoot = File(root, "data")
        val pcs = CinematicMapAssets.SUPPORTED.mapTo(hashSetOf()) { requireNotNull(snesLoRomToPc(it.address)) }
        val assets = AsmAssetManifest.loadBundled().filter { it.pcOffset in pcs }
        assertTrue(assets.size == CinematicMapAssets.SUPPORTED.size)
        val reference = AsmReferenceWorkspace(
            root,
            AsmReferenceMetadata(
                AsmReferenceContract.REPOSITORY_URL,
                AsmReferenceContract.COMMIT,
                "native-cinematic-fixture",
                "SM.sfc",
                AsmVerifiedCache.hash(rom),
                rom.size,
                assets.size,
            ),
            AsmSourceParser().parse(sourceRoot, dataRoot, assets),
        )
        val project = File(temporary, "native-project/Native.smedit").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        val repository = AsmProjectWorkspaceRepository(
            assetRanges = assets,
            starterModules = emptyList(),
            augmentation = AsmSourceAugmentation.NONE,
        )
        repository.initialize(project.path, reference)

        val resolved = AsmCinematicMapAssetBridge(repository).accessAll(project.path)

        CinematicMapAssets.SUPPORTED.forEach { map ->
            val access = assertIs<AsmCinematicMapAssetAccess.Editable>(resolved.getValue(map.key), map.name)
            assertContentEquals(map.read(com.supermetroid.editor.rom.RomParser(rom)), access.rawData, map.name)
            assertNotNull(access.relocation, map.name)
        }
    }

    private data class Fixture(
        val project: File,
        val workspace: AsmProjectWorkspace,
        val repository: AsmProjectWorkspaceRepository,
        val bridge: AsmCinematicMapAssetBridge,
        val asset: AsmAssetRange,
        val raw: ByteArray,
    )

    private fun fixture(tightEnvelope: Boolean = false): Fixture {
        val raw = ByteArray(MAP.rawBytes) { (it / 64 and 3).toByte() }
        val compressed = LZ5Compressor.compress(raw)
        val capacity = compressed.size + if (tightEnvelope) 1 else 64
        val asset = AsmAssetRange(ASSET_PATH, requireNotNull(snesLoRomToPc(MAP.address)), capacity)
        val root = File(temporary, "reference-${temporary.listFiles().orEmpty().size}")
        val source = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(source, "main.asm").writeText("incsrc bank_94..99.asm\nincsrc bank_8B.asm\n")
        File(source, "bank_94..99.asm").writeText(
            """
            ${MAP.sourceLabel}:
                incbin "../data/$ASSET_PATH"
            """.trimIndent() + "\n",
        )
        File(source, "bank_8B.asm").writeText(
            """
            Loader:
                LDA.W #${MAP.sourceLabel}>>8&${'$'}FF00
                LDA.W #${MAP.sourceLabel}
            """.trimIndent() + "\n",
        )
        File(data, ASSET_PATH).writeBytes(ByteArray(capacity) { 0xFF.toByte() }.also(compressed::copyInto))
        val reference = AsmReferenceWorkspace(
            root,
            AsmReferenceMetadata(
                AsmReferenceContract.REPOSITORY_URL,
                AsmReferenceContract.COMMIT,
                "fixture",
                "fixture.sfc",
                "ab".repeat(32),
                0x300000,
                1,
            ),
            AsmSourceParser().parse(source, data, listOf(asset)),
        )
        val project = File(temporary, "project-${temporary.listFiles().orEmpty().size}/Test.smedit").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        val repository = AsmProjectWorkspaceRepository(listOf(asset), starterModules = emptyList())
        val workspace = repository.initialize(project.path, reference)
        return Fixture(project, workspace, repository, AsmCinematicMapAssetBridge(repository), asset, raw)
    }

    private companion object {
        val MAP = CinematicMapAssets.source("96FC04")
        const val ASSET_PATH = "Title_Mode7_Tilemap.bin"
    }
}

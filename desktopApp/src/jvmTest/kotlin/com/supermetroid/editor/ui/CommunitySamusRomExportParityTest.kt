package com.supermetroid.editor.ui

import com.supermetroid.editor.data.CommunitySamusInjectionArtifact
import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.VANILLA_JU_SHA256
import com.supermetroid.editor.rom.CommunitySamusSourceCodec
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("community-samus-rom")
class CommunitySamusRomExportParityTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `catalog sources export byte exactly like pinned Map Randomizer patches`() {
        val base = TestRomHelper.requireRomBytes()
        val sheetDirectory = File(requireNotNull(System.getProperty("smedit.communitySamusDir")))
        val patchDirectory = File(requireNotNull(System.getProperty("smedit.communitySamusPatchDir")))

        for ((name, expectedOutputHash) in EXPECTED_OUTPUT_HASHES) {
            val png = File(sheetDirectory, "$name.png").readBytes()
            val ips = File(patchDirectory, "$name.ips").readBytes()
            val sheetHash = CommunitySamusSourceCodec.sha256(png)
            val source = CommunitySamusSourceCodec.create(
                pngBytes = png,
                sourceName = "$name.png",
                displayName = name,
                catalogName = name,
                catalogRevision = CommunitySamusCatalogRepository.INJECTABLE_CATALOG_REVISION,
                injectionArtifact = CommunitySamusInjectionArtifact(
                    formatId = CommunitySamusInjectionArtifact.MAP_RANDOMIZER_IPS_V1,
                    ipsBase64 = Base64.getEncoder().encodeToString(ips),
                    sha256 = CommunitySamusSourceCodec.sha256(ips),
                    baseRomSha256 = VANILLA_JU_SHA256,
                    baseRomSize = CommunitySamusCatalogRepository.VANILLA_ROM_SIZE,
                    outputRomSize = CommunitySamusCatalogRepository.EXPANDED_ROM_SIZE,
                    providerRevision = CommunitySamusCatalogRepository.MAP_RANDOMIZER_REVISION,
                    sourceSheetSha256 = sheetHash,
                    sourceUrl = CommunitySamusCatalogRepository.patchUrl(name),
                ),
            )
            val input = File(tempDir, "$name.sfc").apply { writeBytes(base) }
            val project = SmEditProject(romPath = input.absolutePath).also {
                it.customGfx.samusCommunitySource = source
            }

            val outputPath = RomExporter(project, RomParser(base)).export()
            val actual = File(assertNotNull(outputPath, name)).readBytes()
            val expected = applyIpsToExpandedBase(base, ips)

            assertContentEquals(expected, actual, name)
            assertEquals(expectedOutputHash, bytesSha256(actual), name)
        }
    }

    @Test
    fun `catalog Samus and Spider Ball export through the adaptive compatibility path`() {
        val base = TestRomHelper.requireRomBytes()
        val name = "samus_zero-mission"
        val sheetDirectory = File(requireNotNull(System.getProperty("smedit.communitySamusDir")))
        val patchDirectory = File(requireNotNull(System.getProperty("smedit.communitySamusPatchDir")))
        val png = File(sheetDirectory, "$name.png").readBytes()
        val ips = File(patchDirectory, "$name.ips").readBytes()
        val sheetHash = CommunitySamusSourceCodec.sha256(png)
        val source = CommunitySamusSourceCodec.create(
            pngBytes = png,
            sourceName = "$name.png",
            displayName = name,
            catalogName = name,
            catalogRevision = CommunitySamusCatalogRepository.INJECTABLE_CATALOG_REVISION,
            injectionArtifact = CommunitySamusInjectionArtifact(
                formatId = CommunitySamusInjectionArtifact.MAP_RANDOMIZER_IPS_V1,
                ipsBase64 = Base64.getEncoder().encodeToString(ips),
                sha256 = CommunitySamusSourceCodec.sha256(ips),
                baseRomSha256 = VANILLA_JU_SHA256,
                baseRomSize = CommunitySamusCatalogRepository.VANILLA_ROM_SIZE,
                outputRomSize = CommunitySamusCatalogRepository.EXPANDED_ROM_SIZE,
                providerRevision = CommunitySamusCatalogRepository.MAP_RANDOMIZER_REVISION,
                sourceSheetSha256 = sheetHash,
                sourceUrl = CommunitySamusCatalogRepository.patchUrl(name),
            ),
        )
        val spider = PatchRepository.loadBundledPatches()
            .first { it.id == "bundled_spider_ball_hold_aim_down" }
            .also { it.enabled = true }
        val input = File(tempDir, "community-spider.sfc").apply { writeBytes(base) }
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.customGfx.samusCommunitySource = source
            it.patches += spider
        }
        val logs = mutableListOf<String>()

        val outputPath = RomExporter(project, RomParser(base), onLog = logs::add).export()

        val output = File(assertNotNull(outputPath)).readBytes()
        val communityOnly = applyIpsToExpandedBase(base, ips)
        assertContentEquals(
            communityOnly.copyOfRange(0x0FF740, 0x0FF780),
            output.copyOfRange(0x0FF740, 0x0FF780),
            "Spider Ball must retain the catalog character's morph-ball graphics",
        )
        assertContentEquals(
            byteArrayOf(0x5C, 0x80.toByte(), 0xF6.toByte(), 0x90.toByte()),
            output.copyOfRange(0x0881A9, 0x0881AD),
            "Catalog pose handling must enter the adaptive Spider Ball trampoline",
        )
        val spiderPlm = spider.writes.first { it.offset == 0x027200L }
        assertContentEquals(
            spiderPlm.bytes.map(Int::toByte).toByteArray(),
            output.copyOfRange(0x027200, 0x027200 + spiderPlm.bytes.size),
            "Spider Ball's custom item PLM must remain installed",
        )
        assertTrue(logs.any { it.contains("Community Samus compatibility") })
    }

    private fun applyIpsToExpandedBase(base: ByteArray, ips: ByteArray): ByteArray {
        val output = base.copyOf(CommunitySamusCatalogRepository.EXPANDED_ROM_SIZE)
        for (write in PatchRepository.parseIps(ips)) {
            for (index in write.bytes.indices) {
                output[write.offset.toInt() + index] = write.bytes[index].toByte()
            }
        }
        return output
    }

    companion object {
        private val EXPECTED_OUTPUT_HASHES = linkedMapOf(
            "samus_vanilla" to "d0532aec55804b9169f93a9550c692412af4cd20d7e1fe86adc3eff15875f47a",
            "samus_invisible" to "f1cc8baa4e02e7831e200bfd37ddfe3489c9b5ed46982f922de6f7c6015bc319",
            "samus_outline" to "962d804ce4267aba9ea5b3f8c716ff45b273d963d66ec39339f259419a9e790b",
            "samus_zero-mission" to "8e6ecd0dcae9307ecb35a4ce1ddbde1bfe1607c999b45a59257d2052e56f49d4",
        )
    }
}

package com.supermetroid.editor.asm

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AsmReferenceTest {
    @TempDir
    lateinit var tempDirectory: File

    @Test
    fun `bundled asset manifest matches pinned reference contract`() {
        val ranges = AsmAssetManifest.loadBundled()
        assertEquals(1130, ranges.size)
        assertEquals(ranges.size, ranges.map { it.path }.distinct().size)
        assertTrue(ranges.all { it.pcOffset >= 0 && it.length > 0 && it.endExclusive <= 0x300000 })
        assertEquals(
            AsmAssetRange("Tiles_EnemyProj_QuestionMark.bin", 0x0329BD, 0x80),
            ranges.first(),
        )
        assertEquals(0x2F8000, ranges.single { it.path == "UNUSED_Music_DF8000.bin" }.pcOffset)

        val propertiesFile = findRepositoryFile("parity/reference.properties")
        val properties = Properties().apply { propertiesFile.inputStream().use(::load) }
        assertEquals(properties.getProperty("disassembly.repoUrl"), AsmReferenceContract.REPOSITORY_URL)
        assertEquals(properties.getProperty("disassembly.commit"), AsmReferenceContract.COMMIT)
        assertEquals(properties.getProperty("assets.ntsc.count").toInt(), AsmReferenceContract.ASSET_COUNT)
    }

    @Test
    fun `source parser preserves banks sections and cross-file labels`() {
        val source = File(tempDirectory, "src").apply { mkdirs() }
        File(source, "main.asm").writeText(
            """
            lorom
            incsrc bank_80.asm ; Game engine
            incsrc bank_84.asm ; PLMs
            incsrc labels.asm ; Shared labels
            """.trimIndent(),
        )
        File(source, "bank_80.asm").writeText(
            """
            ; Game engine
            org ${'$'}808000
            ;;; ${'$'}8000: Boot game ;;;
            BootGame:
                JSR SetupPLMs
              .wait:
                BRA .wait
            """.trimIndent(),
        )
        File(source, "bank_84.asm").writeText(
            """
            ; PLMs
            org ${'$'}848000
            ;;; ${'$'}8000: Set up PLMs ;;;
            SetupPLMs:
                RTS
            """.trimIndent(),
        )
        File(source, "labels.asm").writeText("ExternalThing:\n")
        File(source, "memory.asm").writeText(
            """
            struct Enemy ${'$'}0F78
              .var5: skip 2
            endstruct
            """.trimIndent(),
        )

        val index = AsmSourceParser().parse(source, File(tempDirectory, "data"), emptyList())
        val bank80 = assertNotNull(index.file("bank_80.asm"))
        assertEquals("Bank \$80", bank80.displayName)
        assertEquals("Game engine", bank80.description)
        assertEquals("Boot game", bank80.sections.single { it.address == "\$8000" }.title)
        assertEquals("bank_84.asm", assertNotNull(index.resolveLabel("bank_80.asm", 4, "SetupPLMs")).fileId)
        assertEquals(5, assertNotNull(index.resolveLabel("bank_80.asm", 6, ".wait")).lineIndex)
        assertEquals("memory.asm", assertNotNull(index.resolveLabel("bank_80.asm", 4, "Enemy.var5")).fileId)
        assertNull(index.resolveLabel("bank_84.asm", 4, ".wait"))
    }

    @Test
    fun `managed install derives assets without retaining a ROM copy and can resync`() {
        val ranges = listOf(
            AsmAssetRange("Tiles_Test.bin", 0x10, 4),
            AsmAssetRange("nested/Palette_Test.bin", 0x20, 3),
        )
        val repository = AsmReferenceRepository(
            referenceRoot = File(tempDirectory, "asm"),
            fetchBytes = { fakeSourceArchive() },
            assetRanges = ranges,
        )
        val rom = ByteArray(0x300000) { (it and 0xFF).toByte() }
        val installed = repository.installOrRefresh(rom, "My Hack.sfc")

        assertEquals(AsmReferenceContract.COMMIT, installed.metadata.commit)
        assertEquals("My Hack.sfc", installed.metadata.romName)
        assertEquals(2, installed.metadata.assetCount)
        assertContentEquals(byteArrayOf(0x10, 0x11, 0x12, 0x13), File(installed.root, "data/Tiles_Test.bin").readBytes())
        assertContentEquals(byteArrayOf(0x20, 0x21, 0x22), File(installed.root, "data/nested/Palette_Test.bin").readBytes())
        assertFalse(installed.root.walkTopDown().any { it.isFile && it.extension.lowercase() in setOf("smc", "sfc") })

        val changed = rom.copyOf().also { it[0x10] = 0x7F }
        val refreshed = repository.refreshAssets(changed, "My Hack edited.sfc")
        assertEquals(0x7F.toByte(), File(refreshed.root, "data/Tiles_Test.bin").readBytes().first())
        assertEquals("My Hack edited.sfc", refreshed.metadata.romName)
        assertTrue(File(refreshed.root, "src/main.asm").isFile)
    }

    @Test
    fun `managed install rejects archive traversal`() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("sm_disassembly-test/../../escaped.asm"))
            zip.write("nope".toByteArray())
            zip.closeEntry()
        }
        val repository = AsmReferenceRepository(
            referenceRoot = File(tempDirectory, "asm"),
            fetchBytes = { output.toByteArray() },
            assetRanges = emptyList(),
        )
        assertFailsWith<IllegalArgumentException> {
            repository.installOrRefresh(ByteArray(0x300000), "fixture.sfc")
        }
        assertFalse(File(tempDirectory, "escaped.asm").exists())
    }

    @Test
    fun `bank chapters expand and collapse independently of source selection`() = runBlocking {
        val repository = AsmReferenceRepository(
            referenceRoot = File(tempDirectory, "asm"),
            fetchBytes = { fakeSourceArchive() },
            assetRanges = emptyList(),
        )
        repository.installOrRefresh(ByteArray(0x300000), "fixture.sfc")
        val state = AsmWorkspaceState(repository)
        state.loadInstalled()

        assertEquals("bank_80.asm", state.selectedFileId)
        assertEquals("bank_80.asm", state.expandedSourceFileId)

        state.toggleSourceChapter("bank_80.asm")
        assertEquals("bank_80.asm", state.selectedFileId)
        assertNull(state.expandedSourceFileId)

        state.toggleSourceChapter("bank_80.asm")
        assertEquals("bank_80.asm", state.expandedSourceFileId)
    }

    @Test
    fun `workspace address navigation prefers a named exact source anchor`() = runBlocking {
        val repository = AsmReferenceRepository(
            referenceRoot = File(tempDirectory, "asm"),
            fetchBytes = { fakeSourceArchive() },
            assetRanges = emptyList(),
        )
        repository.installOrRefresh(ByteArray(0x300000), "fixture.sfc")
        val state = AsmWorkspaceState(repository)
        state.showLibraryBrowser()
        state.openAddress(0x808000)
        state.loadInstalled()

        assertEquals(AsmBrowserMode.SOURCE, state.browserMode)
        assertEquals("bank_80.asm", state.selectedFileId)
        assertEquals(2, state.selectedLineIndex)
    }

    @Test
    fun `workspace address navigation opens an owning asset before nearby source`() = runBlocking {
        val range = AsmAssetRange("Tiles_Test.bin", 0x10, 4)
        val repository = AsmReferenceRepository(
            referenceRoot = File(tempDirectory, "asm"),
            fetchBytes = { fakeSourceArchive() },
            assetRanges = listOf(range),
        )
        repository.installOrRefresh(ByteArray(0x300000), "fixture.sfc")
        val state = AsmWorkspaceState(repository)
        state.loadInstalled()

        state.openAddress(0x808011)

        assertEquals(AsmBrowserMode.ASSETS, state.browserMode)
        assertEquals(range.path, state.selectedAssetPath)
    }

    private fun fakeSourceArchive(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(path: String, text: String) {
                zip.putNextEntry(ZipEntry("sm_disassembly-test/$path"))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            entry("src/main.asm", "incsrc bank_80.asm ; Game engine\n")
            entry("src/bank_80.asm", "; Game engine\norg \$808000\nBoot:\n    RTS\n")
            entry("README.md", "test")
        }
        return output.toByteArray()
    }

    private fun findRepositoryFile(relativePath: String): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath")
    }
}

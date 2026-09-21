package com.supermetroid.editor.ui

import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import com.supermetroid.editor.rom.TileGraphics
import com.supermetroid.editor.rom.readU24
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64
import kotlin.random.Random

class RomExporterSafetyTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `final output replacement is staged through a complete temporary file`() {
        val target = File(tempDir, "atomic-output.smc")
        target.writeBytes(byteArrayOf(1, 2, 3))

        writeBytesAtomically(target, byteArrayOf(4, 5, 6, 7))

        assertArrayEquals(byteArrayOf(4, 5, 6, 7), target.readBytes())
        assertTrue(tempDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `oversized variable metatile table relocates and updates its U24 pointer`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "metatile-relocation.smc")
        input.writeBytes(original!!)
        val parser = RomParser(original)
        val tilesetId = 0
        val tableEntryPc = parser.snesToPc(TileGraphics.TILESET_TABLE_SNES) + tilesetId * 9
        val originalPointer = readU24(original, tableEntryPc)
        val (originalRaw, originalCompressedSize) = parser.decompressLZ2WithSize(originalPointer)
        val replacement = ByteArray(originalRaw.size).also { Random(0x5A17).nextBytes(it) }
        assumeTrue(
            LZ5Compressor.compress(replacement).size > originalCompressedSize,
            "Deterministic fixture must exceed the original allocation",
        )
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.customGfx.tileTables[tilesetId.toString()] = Base64.getEncoder().encodeToString(replacement)
        }

        val outputPath = RomExporter(project, parser).export()

        assertNotNull(outputPath)
        val exportedBytes = File(outputPath!!).readBytes()
        val exportedParser = RomParser(exportedBytes)
        val relocatedPointer = readU24(exportedBytes, tableEntryPc)
        assertNotEquals(originalPointer, relocatedPointer)
        assertArrayEquals(replacement, exportedParser.decompressLZ2(relocatedPointer))
        assertArrayEquals(
            originalRaw,
            exportedParser.decompressLZ2(originalPointer),
            "Relocation must preserve the old allocation because another tileset may alias it",
        )
    }

    @Test
    fun `malformed graphics data blocks export before creating an output ROM`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "malformed-gfx.smc")
        input.writeBytes(original!!)
        val statuses = mutableListOf<String>()
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.customGfx.tileTables["0"] = "not base64!"
        }

        val outputPath = RomExporter(project, RomParser(original), onStatus = statuses::add).export()

        assertNull(outputPath)
        assertTrue(statuses.lastOrNull().orEmpty().contains("invalid base64"))
        assertTrue(tempDir.listFiles().orEmpty().map { it.name }.none { it != input.name })
    }

    @Test
    fun `full layout graphics larger than the engine destination block even when highly compressible`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "oversized-raw-gfx.smc")
        input.writeBytes(original!!)
        val statuses = mutableListOf<String>()
        val replacement = ByteArray(TileGraphics.ROOM_GFX_MAX_BYTES + TileGraphics.BYTES_PER_TILE)
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.customGfx.varGfx[TileGraphics.MODE7_CERES_TILESETS.first.toString()] =
                Base64.getEncoder().encodeToString(replacement)
        }

        val outputPath = RomExporter(project, RomParser(original), onStatus = statuses::add).export()

        assertNull(outputPath)
        assertTrue(statuses.lastOrNull().orEmpty().contains("32 KiB full room-graphics destination"))
        assertTrue(tempDir.listFiles().orEmpty().map { it.name }.none { it != input.name })
    }

    @Test
    fun `ordinary tileset graphics cannot overwrite the CRE region`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "oversized-standard-gfx.smc")
        input.writeBytes(original!!)
        val statuses = mutableListOf<String>()
        val replacement = ByteArray(TileGraphics.STANDARD_VAR_GFX_MAX_BYTES + TileGraphics.BYTES_PER_TILE)
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.customGfx.varGfx["0"] = Base64.getEncoder().encodeToString(replacement)
        }

        val outputPath = RomExporter(project, RomParser(original), onStatus = statuses::add).export()

        assertNull(outputPath)
        assertTrue(statuses.lastOrNull().orEmpty().contains("20 KiB area-graphics region"))
        assertTrue(tempDir.listFiles().orEmpty().map { it.name }.none { it != input.name })
    }

    @Test
    fun `unknown configured patch fields block instead of being silently omitted`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "unknown-config-field.smc")
        input.writeBytes(original!!)
        val statuses = mutableListOf<String>()
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.patches.add(
                BEAM_DAMAGE_PATCH.copy(
                    enabled = true,
                    configData = mutableMapOf("power_beem_typo" to 99),
                )
            )
        }

        val outputPath = RomExporter(project, RomParser(original), onStatus = statuses::add).export()

        assertNull(outputPath)
        assertTrue(statuses.lastOrNull().orEmpty().contains("unsupported field(s): power_beem_typo"))
        assertTrue(tempDir.listFiles().orEmpty().map { it.name }.none { it != input.name })
    }

    @Test
    fun `desktop export permits declared identical Hyper and Spider message infrastructure`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "shared-item-messages.smc")
        input.writeBytes(original!!)
        val patches = PatchRepository.loadBundledPatches().filter {
            it.id == "bundled_hyper_beam_item" ||
                it.id == "bundled_spider_ball_hold_aim_down"
        }
        assertEquals(2, patches.size)
        val project = SmEditProject(romPath = input.absolutePath).also { project ->
            project.patches.addAll(patches.onEach { it.enabled = true })
        }

        val outputPath = RomExporter(project, RomParser(original)).export()

        assertNotNull(outputPath)
        val exported = File(outputPath!!).readBytes()
        assertArrayEquals(
            byteArrayOf(0x02, 0x9C.toByte()),
            exported.copyOfRange(0x28251, 0x28253),
        )
        assertArrayEquals(
            byteArrayOf(0x36, 0x84.toByte(), 0x89.toByte(), 0x82.toByte(), 0x40, 0x9D.toByte()),
            exported.copyOfRange(0x29CB4, 0x29CBA),
        )
    }

    @Test
    fun `out of range configured patch values block instead of being clamped`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "out-of-range-config.smc")
        input.writeBytes(original!!)
        val statuses = mutableListOf<String>()
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.patches.add(
                ZEBES_ESCAPE_PATCH.copy(
                    enabled = true,
                    configValue = ZEBES_ESCAPE_MAX_SECONDS + 1,
                )
            )
        }

        val outputPath = RomExporter(project, RomParser(original), onStatus = statuses::add).export()

        assertNull(outputPath)
        assertTrue(statuses.lastOrNull().orEmpty().contains("field 'seconds'"))
        assertTrue(statuses.lastOrNull().orEmpty().contains("expected 1..5999"))
        assertTrue(tempDir.listFiles().orEmpty().map { it.name }.none { it != input.name })
    }

    @Test
    fun `editing a shared tileset pointer uses copy on write`() {
        val fixture = TestRomHelper.loadRomBytes()?.copyOf()
        assumeTrue(fixture != null, "Test ROM not found")
        val tablePc = RomParser(fixture!!).snesToPc(TileGraphics.TILESET_TABLE_SNES)
        val sharedPointer = readU24(fixture, tablePc)
        val originalRaw = RomParser(fixture).decompressLZ2(sharedPointer)
        writeU24(fixture, tablePc + 9, sharedPointer)
        val input = File(tempDir, "shared-metatile-pointer.smc")
        input.writeBytes(fixture)
        val replacement = ByteArray(originalRaw.size)
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.customGfx.tileTables["0"] = Base64.getEncoder().encodeToString(replacement)
        }

        val outputPath = RomExporter(project, RomParser(fixture)).export()

        assertNotNull(outputPath)
        val exported = File(outputPath!!).readBytes()
        val exportedParser = RomParser(exported)
        val tilesetZeroPointer = readU24(exported, tablePc)
        val tilesetOnePointer = readU24(exported, tablePc + 9)
        assertNotEquals(sharedPointer, tilesetZeroPointer)
        org.junit.jupiter.api.Assertions.assertEquals(sharedPointer, tilesetOnePointer)
        assertArrayEquals(replacement, exportedParser.decompressLZ2(tilesetZeroPointer))
        assertArrayEquals(originalRaw, exportedParser.decompressLZ2(tilesetOnePointer))
    }

    @Test
    fun `oversized fixed CRE metatile data blocks instead of guessing a relocation`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val parser = RomParser(original!!)
        val crePointer = parser.graphicsCatalog.creTileTablePtr
        val (rawCre, originalCompressedSize) = parser.decompressLZ2WithSize(crePointer)
        val replacement = ByteArray(rawCre.size).also { Random(0xC0DE).nextBytes(it) }
        assumeTrue(
            LZ5Compressor.compress(replacement).size > originalCompressedSize,
            "Deterministic CRE fixture must exceed the fixed allocation",
        )
        val input = File(tempDir, "oversized-cre.smc")
        input.writeBytes(original)
        val statuses = mutableListOf<String>()
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.customGfx.creTileTable = Base64.getEncoder().encodeToString(replacement)
        }

        val outputPath = RomExporter(project, parser, onStatus = statuses::add).export()

        assertNull(outputPath)
        assertTrue(statuses.lastOrNull().orEmpty().contains("fixed engine allocation"))
        assertTrue(statuses.lastOrNull().orEmpty().contains("relocation is not supported"))
        assertTrue(tempDir.listFiles().orEmpty().map { it.name }.none { it != input.name })
    }

    @Test
    fun `legacy PNG enemy graphics block instead of being silently omitted`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "legacy-enemy-gfx.smc")
        input.writeBytes(original!!)
        val statuses = mutableListOf<String>()
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.customGfx.enemyGfx["E4BF"] = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        }

        val outputPath = RomExporter(project, RomParser(original), onStatus = statuses::add).export()

        assertNull(outputPath)
        assertTrue(statuses.lastOrNull().orEmpty().contains("Legacy PNG enemy graphics"))
        assertTrue(tempDir.listFiles().orEmpty().map { it.name }.none { it != input.name })
    }

    private fun writeU24(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }
}

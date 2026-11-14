package com.supermetroid.editor.ui

import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.IntroImageSource
import com.supermetroid.editor.data.IntroSceneLayout
import com.supermetroid.editor.asm.AsmCompiledBuildBase
import com.supermetroid.editor.asm.AsmPatchBackendRegistry
import com.supermetroid.editor.asm.AsmSourceOwnedRange
import com.supermetroid.editor.asm.AsmSourcePatchMaterializer
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.NativeSpriteAssets
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpritePalettes
import com.supermetroid.editor.rom.TestRomHelper
import com.supermetroid.editor.rom.TextCategory
import com.supermetroid.editor.rom.TextData
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
    fun `managed intro expands ROM installs checked hook and materializes to ASM`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "managed-intro.smc")
        input.writeBytes(original!!)
        val parser = RomParser(original)
        val project = SmEditProject(romPath = input.absolutePath).also { project ->
            project.patches += INTRO_SEQUENCE_PATCH.copy(enabled = true)
            project.introSequence.scenes.single().apply {
                name = "Friend's intro"
                text = "THE ADVENTURE BEGINS."
                imageSource = IntroImageSource.ROM_MOTHER_BRAIN
                layout = IntroSceneLayout.TEXT_PANEL_BOTTOM
            }
        }

        val build = requireNotNull(RomExporter(project, parser).build())

        assertEquals(com.supermetroid.editor.rom.RomConstants.EXPANDED_ROM_SIZE, build.resultRom.size)
        assertTrue(build.romSpaceAfter.allocations.isNotEmpty())
        assertTrue(
            build.romSpaceAfter.allocations.all { it.request != null },
            "Every production allocation must retain typed owner/request evidence",
        )
        val hook = parser.snesToPc(0x8BA66F)
        assertEquals(0x22, build.resultRom[hook].toInt() and 255)
        assertEquals(0x60, build.resultRom[hook + 4].toInt() and 255)
        assertTrue((build.resultRom[hook + 3].toInt() and 255) in 0xE0..0xFF)
        val owners = AsmPatchBackendRegistry.sourceOwners(project.patches)
        val materialized = AsmSourcePatchMaterializer().materialize(
            build.writeReport,
            owners,
            com.supermetroid.editor.rom.RomConstants.EXPANDED_ROM_SIZE,
        )
        assertTrue(materialized.claims.isNotEmpty())
        assertEquals(0, materialized.skippedWrites)
        assertTrue(materialized.source.contains("Intro Sequence Manager"))
    }

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
    fun `Kraid BG2 pixel edits export as the complete tileset 1A resource`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "kraid-tileset-gfx.smc")
        input.writeBytes(original!!)
        val parser = RomParser(original)
        val tilesetId = TileGraphics.KRAID_TILESET
        val graphics = TileGraphics(parser)
        assertTrue(graphics.loadTileset(tilesetId))
        val replacement = requireNotNull(graphics.getRawVarGfx()).copyOf()
        assertEquals(TileGraphics.ROOM_GFX_MAX_BYTES, replacement.size)
        replacement[0] = (replacement[0].toInt() xor 1).toByte()
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.customGfx.varGfx[tilesetId.toString()] =
                Base64.getEncoder().encodeToString(replacement)
        }

        val outputPath = requireNotNull(RomExporter(project, parser).export())
        val exported = File(outputPath).readBytes()
        val tableEntryPc = parser.snesToPc(TileGraphics.TILESET_TABLE_SNES) + tilesetId * 9
        val exportedPointer = readU24(exported, tableEntryPc + 3)
        assertArrayEquals(replacement, RomParser(exported).decompressLZ2(exportedPointer))

        val legacyBg2Pc = parser.snesToPc(0xB9FA38)
        assertArrayEquals(
            original.copyOfRange(legacyBg2Pc, legacyBg2Pc + 1030),
            exported.copyOfRange(legacyBg2Pc, legacyBg2Pc + 1030),
            "The old false Kraid target is placement data and must remain untouched",
        )
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
    fun `desktop export blocks a placed bundled item when its patch is disabled`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "orphan-custom-item.smc")
        input.writeBytes(original!!)
        val spider = PatchRepository.loadBundledPatches()
            .first { it.id == "bundled_spider_ball_hold_aim_down" }
            .copy(enabled = false, customItems = mutableListOf())
        val statuses = mutableListOf<String>()
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.patches += spider
            it.getOrCreateRoom(0x91F8).plmChanges +=
                PlmChange("add", 0xF200, 71, 55, 0x53)
        }

        val outputPath = RomExporter(project, RomParser(original), onStatus = statuses::add).export()

        assertNull(outputPath)
        assertTrue(statuses.lastOrNull().orEmpty().contains("code patch is disabled"))
        assertTrue(statuses.lastOrNull().orEmpty().contains("Spider Ball — Hold Aim Down"))
        assertTrue(tempDir.listFiles().orEmpty().map { it.name }.none { it != input.name })
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

    @Test
    fun `legacy Phantoon tile sheet blocks before touching Mother Brain graphics`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "phantoon-quarantine.smc")
        input.writeBytes(original!!)
        val statuses = mutableListOf<String>()
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.customGfx.spriteTileBlocks["phantoon:0"] =
                Base64.getEncoder().encodeToString(ByteArray(32))
        }

        val outputPath = RomExporter(project, RomParser(original), onStatus = statuses::add).export()

        assertNull(outputPath)
        assertTrue(statuses.lastOrNull().orEmpty().contains("Mother Brain leg graphics"))
        assertArrayEquals(original, input.readBytes())
        assertTrue(tempDir.listFiles().orEmpty().map { it.name }.none { it != input.name })
    }

    @Test
    fun `Kraid pixel edit blocks before replacing the BG2 tilemap`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "kraid-quarantine.smc")
        input.writeBytes(original!!)
        val statuses = mutableListOf<String>()
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.customGfx.spriteTileBlocks["kraid:0"] =
                Base64.getEncoder().encodeToString(ByteArray(32))
        }

        val outputPath = RomExporter(project, RomParser(original), onStatus = statuses::add).export()

        assertNull(outputPath)
        assertTrue(statuses.lastOrNull().orEmpty().contains("BG2 tilemap at \$B9:FA38"))
        assertArrayEquals(original, input.readBytes())
        assertTrue(tempDir.listFiles().orEmpty().map { it.name }.none { it != input.name })
    }

    @Test
    fun `ASM native palette row wins over a stale legacy multi-row override`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val parser = RomParser(original!!)
        val nativeRows = NativeSpriteAssets.paletteRows(parser).mapTo(hashSetOf(), parser::snesToPc)
        val region = SpritePalettes.REGIONS.firstOrNull { candidate ->
            candidate.rowCount > 1 &&
                (0 until candidate.rowCount).any { candidate.offset + it * 32 in nativeRows } &&
                (0 until candidate.rowCount).any { candidate.offset + it * 32 !in nativeRows }
        }
        assertNotNull(region, "Fixture must expose a mixed native/legacy sprite-palette region")
        region!!
        val sourceRow = (0 until region.rowCount).first { region.offset + it * 32 in nativeRows }
        val legacyRow = (0 until region.rowCount).first { region.offset + it * 32 !in nativeRows }
        val sourceBytes = ByteArray(32) { 0x11 }
        val legacyBytes = ByteArray(region.byteSize) { 0x22 }
        val compiledRom = original.copyOf().also {
            sourceBytes.copyInto(it, region.offset + sourceRow * 32)
        }
        val input = File(tempDir, "asm-native-palette-ownership.smc").apply { writeBytes(original) }
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.asmWorkspace.enabled = true
            it.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
            it.customGfx.spritePalettes[region.id] = Base64.getEncoder().encodeToString(legacyBytes)
        }
        val compiled = AsmCompiledBuildBase(
            romBytes = compiledRom,
            referenceRomBody = original,
            referenceRomSha256 = bytesSha256(original),
            sourceOwnedRanges = listOf(
                AsmSourceOwnedRange(region.offset + sourceRow * 32, 32, "bank_native_palette.asm"),
            ),
            assemblerVersion = "fixture",
            compilerOutput = emptyList(),
        )

        val result = requireNotNull(RomExporter(project, parser, compiledAsmBase = compiled).build())

        assertArrayEquals(
            sourceBytes,
            result.resultRom.copyOfRange(
                region.offset + sourceRow * 32,
                region.offset + (sourceRow + 1) * 32,
            ),
            "The stale broad override must not overwrite an ASM-owned row",
        )
        assertArrayEquals(
            ByteArray(32) { 0x22 },
            result.resultRom.copyOfRange(
                region.offset + legacyRow * 32,
                region.offset + (legacyRow + 1) * 32,
            ),
            "Rows outside native ASM ownership remain eligible for the legacy override",
        )
    }

    @Test
    fun `ASM fixed text wins over a stale project text override`() {
        val original = TestRomHelper.requireRomBytes()
        val parser = RomParser(original)
        val allText = TextData.readAllText(original)
        val entry = allText.first { it.category == TextCategory.AREA_NAME }
        val typewriter = allText.first { it.id == "tourian_escape" }
        val intro = allText.first { it.id == "intro_0" }
        val sourceBytes = TextData.encodeEntry(entry, "ZEBES")
        val typewriterBytes = TextData.encodeTypewriterText(
            "ALERT!\nRUN!",
            typewriter.rawBytes,
            preserveLineSlots = true,
        )
        val introBytes = TextData.encodeGreenText(intro.text.replaceFirst("I", "A"), intro.rawBytes)
        val compiledRom = original.copyOf().also {
            sourceBytes.copyInto(it, entry.pcOffset)
            typewriterBytes.copyInto(it, typewriter.pcOffset)
            introBytes.copyInto(it, intro.pcOffset)
        }
        val input = File(tempDir, "asm-text-ownership.smc").apply { writeBytes(original) }
        val project = SmEditProject(romPath = input.absolutePath).also {
            it.asmWorkspace.enabled = true
            it.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
            it.textEdits[entry.id] = "STALE"
            it.textEdits[typewriter.id] = "STALE\nSTALE"
            it.textEdits[intro.id] = "STALE"
        }
        val compiled = AsmCompiledBuildBase(
            romBytes = compiledRom,
            referenceRomBody = original,
            referenceRomSha256 = bytesSha256(original),
            sourceOwnedRanges = listOf(
                AsmSourceOwnedRange(entry.pcOffset, entry.rawBytes.size, "bank_82.asm"),
                AsmSourceOwnedRange(typewriter.pcOffset, typewriter.rawBytes.size, "bank_A6.asm"),
                AsmSourceOwnedRange(intro.pcOffset, intro.rawBytes.size, "bank_8C.asm"),
            ),
            assemblerVersion = "fixture",
            compilerOutput = emptyList(),
        )
        val log = mutableListOf<String>()

        val result = requireNotNull(
            RomExporter(project, parser, onLog = log::add, compiledAsmBase = compiled).build(),
        )

        assertArrayEquals(
            sourceBytes,
            result.resultRom.copyOfRange(entry.pcOffset, entry.pcOffset + sourceBytes.size),
        )
        assertArrayEquals(
            typewriterBytes,
            result.resultRom.copyOfRange(typewriter.pcOffset, typewriter.pcOffset + typewriterBytes.size),
        )
        assertArrayEquals(
            introBytes,
            result.resultRom.copyOfRange(intro.pcOffset, intro.pcOffset + introBytes.size),
        )
        assertEquals(3, log.count { "Ignored stale project text override" in it })
    }

    private fun writeU24(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }
}

package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.procgen.BiomeTheme
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.LayoutEditScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64
import kotlin.test.*

class AsmRoomThemeSourceTest {
    @TempDir lateinit var directory: File
    private val theme = BiomeTheme("test", "Frozen lava", "Fixture", 1, "icecold", BiomeTheme.FX_LAVA, 0.5)
    private data class Fixture(val workspace: AsmWorkspaceState, val bridge: AsmPlmSourceBridge,
                               val paletteBridge: AsmTilesetPaletteBridge, val project: SmEditProject,
                               val editor: EditorState, val file: File)
    private suspend fun fixture(adoptFx: Boolean = false): Fixture {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        val macros = "macro PLMPopEntry(ID, X, Y, Param)\n dw <ID>\n db <X>, <Y>\n dw <Param>\nendmacro\n"
        val palette = ByteArray(256).also { bytes -> for (i in 0 until 128) {
            val color = (i * 97) and 0x7FFF; bytes[i * 2] = color.toByte(); bytes[i * 2 + 1] = (color ushr 8).toByte()
        } }
        val compressedPalette = LZ5Compressor.compress(palette)
        val paletteAsset = AsmAssetRange("Palettes_Theme.bin", 0x208000, 0x200)
        File(data, paletteAsset.path).writeBytes(ByteArray(paletteAsset.length) { 0xFF.toByte() }.also(compressedPalette::copyInto))
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\nincsrc bank_83.asm\nincsrc bank_B9..CE.asm\n")
        File(src, PLM_SOURCE_FILE).writeText("""
            ; Original objects
            Tileset_Table_0_Test:
                dl DummyTable
                dl DummyTiles
                dl Palettes_Theme
            Tileset_Table_1_Test:
                dl DummyTable
                dl DummyTiles
                dl Palettes_Theme
        """.trimIndent() + "\r\n")
        File(src, FX_SOURCE_FILE).writeText("; Original effects\r\n")
        File(src, "bank_B9..CE.asm").writeText("org ${'$'}C18000\nPalettes_Theme:\nincbin \"../data/${paletteAsset.path}\"\n")
        File(src, "macros.asm").writeText(macros)
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "test.sfc", "ab".repeat(32), 0x300000, 1),
            AsmSourceParser().parse(src, data, listOf(paletteAsset)))
        val file = File(directory, "Theme.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(listOf(paletteAsset), emptyList())
        repository.initialize(file.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(file.path, true)
        val rom = saveSourceTestRom()
        fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value ushr 8).toByte() }
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) {
            rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte(); word(pc + 6, 0x8800)
        }
        word(0x19000, 0x91F8)
        SourceFxResource("native", listOf(emptyDefaultFxEntry().copy(doorSelect = 0x9000, fxType = 4), emptyDefaultFxEntry().copy(fxType = 6)))
            .bytes().copyInto(rom, 0x18800)
        compressedPalette.copyInto(rom, 0x208000)
        for (id in listOf(0, 1)) {
            val pc = 0x7E6A2 + id * 9 + 6
            rom[pc] = 0; rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC1.toByte()
        }
        val parser = RomParser(rom)
        val project = SmEditProject("test.sfc").apply {
            asmWorkspace.enabled = true; asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
            customGfx.palettes["1"] = Base64.getEncoder().encodeToString(ByteArray(256) { 7 })
            customGfx.paletteEffects["tileset:1"] = "previous"
        }
        val room = project.getOrCreateRoom(0x91F8).also { it.ensureStateManifest(parser) }
        room.fxChange = FxChange(paletteBlend = 0x20)
        room.states.first().doorFxChanges["9000"] = FxChange(liquidSpeed = 7)
        room.plmChanges += PlmChange("add", 0xB703, 4, 4, 0xCC00)
        room.plmChanges += PlmChange("add", 0xB76F, 8, 8, 0x8002)
        room.customScrollCommands["cmd_0"] = mutableListOf(ScrollCommand(0, 0))
        room.saveStationSpawns += SaveStationSpawnChange(0, 2, room.roomId, 0x8000, 0, 0, 128, 128)
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog(File(src, PLM_SOURCE_FILE).readText(), macros, emptyMap(), parser, emptyMap()), workspace) { project }
        if (adoptFx) bridge.fx.migrate(room.roomId, "Test")
        val editor = EditorState().apply {
            testMode = true; initForProject(file, project); asmPlmSourceBridge = bridge
            loadRoom(room.roomId, parser, parser.readRoomHeader(room.roomId)!!)
            setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        }
        return Fixture(workspace, bridge, AsmTilesetPaletteBridge(repository), project, editor, file)
    }
    private fun Fixture.sources() = listOf(PLM_SOURCE_FILE, FX_SOURCE_FILE).associateWith { workspace.projectSourceText(it) }
    private fun Fixture.palette() = assertIs<AsmTilesetPaletteAccess.Editable>(paletteBridge.access(file.path, 1))
    private fun Fixture.generate(theme: BiomeTheme = this@AsmRoomThemeSourceTest.theme): Boolean {
        val width = editor.workingBlocksWide / 16
        val height = editor.workingBlocksTall / 16
        val scrolls = editor.workingScrolls.toList().mapIndexedNotNull { index, old ->
            val target = if (index / width == height - 1) 2 else 1
            if (old == target) null else ScrollChange(index % width, index / width, old, target)
        }
        val removals = editor.workingPlms.filter { RomParser.isScrollPlm(it.id) }.map { PlmChange("remove", it.id, it.x, it.y, it.param) }
        return editor.applyGeneratedRoomOperation("Generate themed fixture", listOf(TileEdit(8, 8, editor.readBlockWord(8, 8), 0x8123, editor.readBts(8, 8), 3)), scrolls, removals, theme)
    }

    @Test fun `themed generation adopts FX objects and grids with palette geometry and reciprocal cross bank history`() = runBlocking {
        val f = fixture()
        val room = f.project.rooms.getValue("91F8")
        val before = snapshotRoomForResize(room)
        val sources = f.sources()
        val palette = f.project.customGfx.palettes["1"]
        val paletteSource = f.palette().sourceBytes
        val pixels = f.editor.workingLevelData!!.copyOf()
        assertTrue(f.generate(), f.editor.statusMessage)
        val themed = f.project.rooms.getValue("91F8")
        assertNotSame(room, themed)
        assertEquals(1, themed.asmPlmSourceVersion); assertEquals(1, themed.asmInitialScrollSourceVersion); assertEquals(1, themed.asmFxSourceVersion)
        assertTrue(themed.states.all { it.stateDataChange?.tileset == 1 && it.fxChange == null && it.doorFxChanges.isEmpty() })
        assertNull(themed.fxChange)
        assertEquals(0x8123, f.editor.readBlockWord(8, 8)); assertEquals(3, f.editor.readBts(8, 8))
        assertEquals(1, f.editor.currentTilesetId); assertEquals("icecold", f.project.customGfx.paletteEffects["tileset:1"])
        assertNull(f.project.customGfx.palettes["1"])
        val themedPaletteSource = f.palette().sourceBytes
        assertFalse(paletteSource.contentEquals(themedPaletteSource))
        assertTrue(f.editor.workingPlms.none { RomParser.isScrollPlm(it.id) })
        assertTrue(f.editor.workingPlms.any { it.id == 0xB76F })
        for (state in themed.states) {
            val values = f.bridge.fx.view(themed.roomId, state.id).resource!!.entries
            assertEquals(2, values.last().fxType); assertEquals(256, values.last().liquidSurfaceStart)
            assertEquals(0x20, values.last().paletteBlend)
            assertEquals(if (state.id == room.states.first().id) 7 else 0, values.first().liquidSpeed)
        }
        assertTrue(f.workspace.undoSourceEdit(FX_SOURCE_FILE), f.workspace.error)
        assertSame(room, f.project.rooms["91F8"]); assertEquals(before, room)
        assertEquals(sources, f.sources()); assertContentEquals(pixels, f.editor.workingLevelData)
        assertEquals(palette, f.project.customGfx.palettes["1"])
        assertContentEquals(paletteSource, f.palette().sourceBytes)
        assertEquals("previous", f.project.customGfx.paletteEffects["tileset:1"])
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertSame(themed, f.project.rooms["91F8"])
        assertNull(f.project.customGfx.palettes["1"])
        assertContentEquals(themedPaletteSource, f.palette().sourceBytes)
        assertEquals(0x8123, f.editor.readBlockWord(8, 8))
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(before, room)
        assertEquals(palette, f.project.customGfx.palettes["1"])
        assertContentEquals(paletteSource, f.palette().sourceBytes)
    }

    @Test fun `standalone theme detaches only selected FX and leaves a sibling and unrelated palettes untouched`() = runBlocking {
        val f = fixture(adoptFx = true)
        val parser = f.bridge.catalog.parser
        val room = f.project.rooms.getValue("91F8")
        val selected = f.editor.currentPlmStateId()!!
        val other = room.states.single { it.id != selected }.id
        val originalOther = f.bridge.fx.view(room.roomId, other).resource!!
        f.project.customGfx.palettes["2"] = "unrelated"
        assertTrue(f.editor.applyBiomeTheme(theme, parser), f.editor.statusMessage)
        assertEquals(originalOther, f.bridge.fx.view(room.roomId, other).resource)
        assertEquals(2, f.bridge.fx.view(room.roomId, selected).resource!!.entries.last().fxType)
        assertNull(f.project.rooms.getValue("91F8").states.single { it.id == other }.stateDataChange)
        assertEquals("unrelated", f.project.customGfx.palettes["2"])
        val fxBeforeRaw = f.bridge.fx.sourceText()
        f.workspace.replaceProjectSource(FX_SOURCE_FILE, fxBeforeRaw, fxBeforeRaw.replace("dw ${'$'}0100 ; Liquid Start", "dw ${'$'}0120 ; Liquid Start"))
        f.editor.refreshLinkedPlms()
        assertEquals(0x120, f.editor.effectiveFxEntries(room.roomId, parser).last().liquidSurfaceStart)
        assertFalse(f.editor.undo(), "Newer raw FX source must block the whole theme undo")
        assertTrue(f.workspace.undoSourceEdit(FX_SOURCE_FILE))
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertSame(room, f.project.rooms["91F8"])
        assertEquals("unrelated", f.project.customGfx.palettes["2"])
    }

    @Test fun `invalid FX palette and scalar inputs never publish partial theme or generation changes`() = runBlocking {
        val f = fixture(adoptFx = true)
        val original = f.bridge.fx.sourceText()
        val invalid = original.replace("db ${'$'}06 ; FX Type", "db ${'$'}03 ; FX Type")
        assertNotEquals(original, invalid)
        f.workspace.replaceProjectSource(FX_SOURCE_FILE, original, invalid)
        for (candidate in listOf(theme, theme.copy(paletteEffectId = "unknown"), theme.copy(liquidFraction = Double.NaN))) {
            val before = snapshotRoomForResize(f.project.rooms.getValue("91F8"))
            val sources = f.sources()
            val palettes = f.project.customGfx.palettes.toMap()
            val pixels = f.editor.workingLevelData!!.copyOf()
            assertFalse(f.generate(candidate), f.editor.statusMessage)
            assertEquals(before, f.project.rooms["91F8"]); assertEquals(sources, f.sources())
            assertEquals(palettes, f.project.customGfx.palettes); assertContentEquals(pixels, f.editor.workingLevelData)
        }
    }

    @Test fun `newer shared palette and room metadata block the entire transaction undo`() = runBlocking {
        val f = fixture()
        assertTrue(f.generate(), f.editor.statusMessage)
        val themed = f.project.rooms.getValue("91F8")
        val sources = f.sources()
        val palette = f.palette()
        val newerRaw = palette.rawPalette.copyOf().also { it[0] = (it[0].toInt() xor 0x1F).toByte() }
        val newer = f.paletteBridge.save(f.file.path, 1, newerRaw, palette.sourceBytes)
        assertFalse(f.workspace.undoSourceEdit(FX_SOURCE_FILE))
        assertEquals(sources, f.sources()); assertSame(themed, f.project.rooms["91F8"])
        f.paletteBridge.replaceSourceBytes(f.file.path, 1, palette.sourceBytes, newer.sourceBytes)
        f.project.customGfx.palettes["1"] = "newer"
        assertFalse(f.workspace.undoSourceEdit(FX_SOURCE_FILE))
        assertEquals(sources, f.sources()); assertSame(themed, f.project.rooms["91F8"])
        f.project.customGfx.palettes.remove("1")
        themed.roomHeaderChange = RoomHeaderChange(mapX = 4)
        assertFalse(f.workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(sources, f.sources())
        themed.roomHeaderChange = null
        assertTrue(f.workspace.undoSourceEdit(FX_SOURCE_FILE), f.workspace.error)
    }

    @Test fun `FX batch detaches one shared subset while independent equal valued resources stay independent`() {
        val room = RoomEdits(0x91F8, asmFxSourceVersion = 1).also { it.ensureStateManifest(RomParser(objectSourceTestRom())) }
        room.states += room.states.first().copy(id = "extra", sourceStateIndex = null, templateSourceStateIndex = 0)
        val table = SourceFxResource("SmeditFx_91F8_Table_1", listOf(emptyDefaultFxEntry().copy(fxType = 6)))
        val original = AsmFxSource.renderTable(table) + room.states.joinToString("") { AsmFxSource.renderState(room.roomId, it.id, table.identity) }
        val targets = room.states.take(2).map { it.id }.toSet()
        val edited = AsmFxSource(AsmFxSource(original).replaceDefaults(room, targets, FxChange(fxType = 2))).resources(room)
        assertEquals(1, edited.filterKeys { it in targets }.values.map { it.identity }.distinct().size)
        assertEquals(6, edited.getValue("extra").entries.last().fxType)
        assertNotEquals(table.identity, edited.getValue(room.states.first().id).identity)
        val separate = original + AsmFxSource.renderTable(table.copy(identity = "SmeditFx_91F8_Table_7"))
        val split = separate.replace(AsmFxSource.renderState(room.roomId, "extra", table.identity), AsmFxSource.renderState(room.roomId, "extra", "SmeditFx_91F8_Table_7"))
        val changed = AsmFxSource(AsmFxSource(split).replaceDefaults(room, room.states.map { it.id }.toSet(), FxChange(fxType = 2))).resources(room)
        assertEquals(2, changed.values.map { it.identity }.distinct().size)
    }

    @Test fun `save reopen retains themed source metadata and palette without duplicate FX values`() = runBlocking {
        val f = fixture()
        assertTrue(f.generate(), f.editor.statusMessage)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val reopened = Json.decodeFromString(SmEditProject.serializer(), Json.encodeToString(SmEditProject.serializer(), f.project))
        val workspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        workspace.bindProject(f.file.path, true, progressive = true)
        val bridge = AsmPlmSourceBridge(f.bridge.catalog, workspace) { reopened }
        val editor = EditorState().apply {
            testMode = true; initForProject(f.file, reopened); asmPlmSourceBridge = bridge
            loadRoom(0x91F8, f.bridge.catalog.parser, f.bridge.catalog.parser.readRoomHeader(0x91F8)!!)
        }
        assertEquals(1, editor.currentTilesetId)
        assertEquals(0x8123, editor.readBlockWord(8, 8))
        assertNull(reopened.customGfx.palettes["1"])
        assertEquals("icecold", reopened.customGfx.paletteEffects["tileset:1"])
        assertTrue(assertIs<AsmTilesetPaletteAccess.Editable>(
            AsmTilesetPaletteBridge().access(f.file.path, 1),
        ).modified)
        assertEquals(2, editor.effectiveFxEntries(0x91F8, f.bridge.catalog.parser).last().fxType)
        assertTrue(reopened.rooms.getValue("91F8").states.all { it.fxChange == null && it.doorFxChanges.isEmpty() })
    }

    @Test fun `Generate room entry point commits the theme once and older geometry undo remains usable`() = runBlocking {
        val f = fixture()
        val parser = f.bridge.catalog.parser
        f.editor.setTileProperties(12, 12, 0, 7)
        val before = snapshotRoomForResize(f.project.rooms.getValue("91F8"))
        val pixels = f.editor.workingLevelData!!.copyOf()
        val count = f.editor.generateBiome(
            com.supermetroid.editor.procgen.BiomeRules.roll(com.supermetroid.editor.procgen.BiomeStyle.PIPE_MAZE, 1234L),
            com.supermetroid.editor.procgen.TilesetProfile.synthetic(), 1234L, romParser = parser, theme = theme)
        assertTrue(count > 0, f.editor.statusMessage)
        assertEquals(2, f.editor.undoStack.size)
        assertEquals(1, f.editor.currentTilesetId)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(before, f.project.rooms["91F8"]); assertContentEquals(pixels, f.editor.workingLevelData)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(0, f.editor.readBts(12, 12))
    }

    @Test fun `private layout theme leaves the sibling geometry grid objects FX and tileset unchanged`() = runBlocking {
        val f = fixture(adoptFx = true)
        val parser = f.bridge.catalog.parser
        val room = f.project.rooms.getValue("91F8")
        val selected = f.editor.currentPlmStateId()!!
        val other = room.states.single { it.id != selected }.id
        val otherFx = f.bridge.fx.view(room.roomId, other).resource!!
        f.editor.setCurrentLayoutEditScope(LayoutEditScope.THIS_STATE)
        assertTrue(f.generate(), f.editor.statusMessage)
        val after = f.project.rooms.getValue("91F8")
        assertEquals(otherFx, f.bridge.fx.view(room.roomId, other).resource)
        assertNull(after.states.single { it.id == other }.stateDataChange)
        assertNotEquals(after.states.single { it.id == selected }.resources.level, after.states.single { it.id == other }.resources.level)
        f.editor.switchRoomState(other, parser)
        assertEquals(0, f.editor.currentTilesetId)
        assertEquals(0, f.editor.readBlockWord(8, 8))
        assertTrue(f.editor.workingPlms.any { it.id == 0xB703 })
        assertTrue(f.workspace.undoSourceEdit(FX_SOURCE_FILE), f.workspace.error)
        assertEquals(other, f.editor.currentPlmStateId())
    }

    @Test fun `reapplying identical theme does not add an empty undo transaction`() = runBlocking {
        val f = fixture()
        val parser = f.bridge.catalog.parser
        assertTrue(f.editor.applyBiomeTheme(theme, parser), f.editor.statusMessage)
        val head = f.workspace.sourceUndoHead(PLM_SOURCE_FILE)
        val count = f.editor.undoStack.size
        assertFalse(f.editor.applyBiomeTheme(theme, parser))
        assertSame(head, f.workspace.sourceUndoHead(PLM_SOURCE_FILE))
        assertEquals(count, f.editor.undoStack.size)
    }

    @Test fun `native tileset theme overrides a room wide tileset without erasing sibling or other state fields`() = runBlocking {
        val f = fixture()
        val parser = f.bridge.catalog.parser
        val room = f.project.rooms.getValue("91F8")
        room.stateDataChange = StateDataChange(tileset = 1, musicData = 4)
        f.editor.loadRoom(room.roomId, parser, parser.readRoomHeader(room.roomId)!!)
        val selected = f.editor.currentPlmStateId()!!
        assertEquals(1, f.editor.currentTilesetId)
        assertTrue(f.editor.applyBiomeTheme(theme.copy(tilesetId = 0, paletteEffectId = null, fxType = null), parser), f.editor.statusMessage)
        val after = f.project.rooms.getValue("91F8")
        assertEquals(0, f.editor.currentTilesetId)
        assertEquals(0, after.states.single { it.id == selected }.stateDataChange?.tileset)
        assertNull(after.states.single { it.id != selected }.stateDataChange)
        assertEquals(4, after.stateDataChange?.musicData)
        assertEquals(0, after.asmFxSourceVersion)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertSame(room, f.project.rooms["91F8"])
        assertEquals(1, f.editor.currentTilesetId)
    }
}

package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpriteWorkspaceCoverageTest {
    private val dedicatedSpecies = setOf(
        0xE4BF, 0xE2BF, 0xDE3F, 0xDDBF, 0xDD7F,
        0xDF3F, 0xF293, 0xEEFF, 0xEC3F, 0xE17F,
    )
    private val shipSpecies = setOf(0xD07F, 0xD0BF)

    @Test
    fun `every regular sprite entry resolves an owned editor or its real shared owner`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject("base.smc")
        val state = EnemySpriteEditorState({ project.customGfx }, {})
        val catalogIds = EnemySpriteGraphics.EDITOR_ENEMIES.map { it.speciesId }.toSet()
        assertEquals(124, catalogIds.size, "new picker entries must receive the same editor-owner audit")

        EnemySpriteGraphics.EDITOR_ENEMIES
            .filterNot { it.speciesId in dedicatedSpecies || it.speciesId in shipSpecies }
            .forEach { entry ->
                val context = "${entry.name} (${entry.speciesId.toString(16).uppercase()})"
                val source = assertNotNull(
                    EnemySpriteGraphics.loadEnemyPreviewTileSource(parser, entry.speciesId),
                    "$context preview source",
                )
                val palette = assertNotNull(
                    EnemySpriteGraphics.loadEnemyPreviewPaletteSource(parser, entry.speciesId),
                    "$context preview palette",
                )
                if (source.editable) {
                    val raw = assertNotNull(
                        state.loadEnemyTileData(parser, entry.speciesId),
                        "$context owned editor source",
                    )
                    val graphics = EnemySpriteGraphics(parser).also { it.loadFromRaw(listOf(raw)) }
                    assertNotNull(graphics.renderSheet(palette.colors, cols = 16), "$context editable tile sheet")
                } else {
                    val editorIndex = assertNotNull(
                        sharedSpriteSourceEditorIndex(source),
                        "$context borrowed source has no real-owner editor route",
                    )
                    assertTrue(
                        editorIndex in EnemySpriteGraphics.EDITOR_ENEMIES.indices ||
                            VISUAL_SPRITE_WORKSPACES.any { it.index == editorIndex },
                    )
                    assertFalse(state.hasCustomEnemyTiles(entry.speciesId), "$context must not claim borrowed bytes")
                }
            }
    }

    @Test
    fun `every dedicated sprite workspace loads its embedded editable source`() {
        val parser = TestRomHelper.requireRomParser()
        val editor = EditorState()

        assertNotNull(editor.renderPhantoonComponent(parser, PhantoonSpritemap.COMPONENT_TILEMAPS.first()))
        assertNotNull(editor.renderKraidHeadTilemap(parser, KraidSpritemap.HEAD_TILEMAPS.first()))
        assertNotNull(editor.loadDraygonObjSheet(parser))
        assertNotNull(editor.loadCrocomireSource(parser, CrocomireSpritemap.PIXEL_SOURCES.first { it.editable }))
        assertNotNull(editor.loadMetroidSource(parser, MetroidSpritemap.PIXEL_SOURCES.first()))
        assertNotNull(editor.loadSporeSpawnSource(
            parser, SporeSpawnSpritemap.PIXEL_SOURCES.first(), SporeSpawnSpritemap.PALETTE_STAGES.first(),
        ))
        assertNotNull(editor.loadBotwoonSource(
            parser, BotwoonSpritemap.PIXEL_SOURCES.first(), BotwoonSpritemap.PALETTE_STAGES.first(),
        ))
        assertNotNull(editor.loadTorizoSource(
            parser, TorizoSpritemap.PIXEL_SOURCES.first { it.editable }, TorizoSpritemap.PALETTE_STAGES.first(),
        ))
        assertTrue(assertNotNull(editor.loadMotherBrainHeadSource(parser)).editable)
        assertTrue(assertNotNull(editor.loadRidleyBaseSource(parser)).editable)
    }

    @Test
    fun `every native misc sprite loads an editable source and assembled preview`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject("base.smc")
        val state = NativeSpriteEditorState({ project.customGfx }, {})

        val shipTarget = NativeSpriteTarget("ADB600", NativeSpriteAssets.shipPalette(parser), 1)
        assertNotNull(state.loadSheet(parser, shipTarget.sourceKey, shipTarget.paletteBase), "Samus' Ship source sheet")
        assertTrue(ShipSpritemap(parser).animations().isNotEmpty(), "Samus' Ship assembled animations")

        VISUAL_SPRITE_WORKSPACES.forEach { workspace ->
            visualSpriteNames(workspace).indices.forEach { selected ->
                val directions = if (workspace.index == -2) ProjectileSprites.DIRECTIONS.indices else listOf(2)
                directions.forEach { direction ->
                    val context = "${workspace.name} / ${visualSpriteNames(workspace)[selected]} / $direction"
                    val targets = visualSpriteTargets(parser, workspace, selected, direction)
                    assertTrue(targets.isNotEmpty(), "$context editor target")
                    targets.forEach { target ->
                        val paletteAddress = target.paletteBase + target.defaultRow * 32
                        assertNotNull(state.loadSheet(parser, target.sourceKey, paletteAddress), "$context source sheet")
                    }
                    assertNotNull(renderVisualSprite(parser, workspace, selected, direction, targets.first().defaultRow), "$context preview")
                }
            }
        }
    }
}

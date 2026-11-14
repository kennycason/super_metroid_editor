package com.supermetroid.editor.rom

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class CinematicMapAssetsTest {
    @Test
    fun `mode 7 entries use exact row-major bytes`() {
        val map = CinematicMapAssets.source("96FC04")
        val raw = ByteArray(map.rawBytes)

        val edited = map.withEntry(raw, 17, 3, 0xA5)

        assertEquals(3 * 128 + 17, map.rawIndex(17, 3))
        assertEquals(0xA5, map.entry(edited, 17, 3))
        assertEquals(0xA5.toByte(), edited[3 * 128 + 17])
        assertContentEquals(ByteArray(raw.size - 1), edited.filterIndexed { index, _ ->
            index != 3 * 128 + 17
        }.toByteArray())
    }

    @Test
    fun `BG entries use SNES screen blocks and preserve complete words`() {
        val map = CinematicMapAssets.Asset(
            "Synthetic 64×64 BG",
            0x808000,
            "SyntheticMap",
            CinematicMapAssets.EntryFormat.SNES_BG_WORD,
            listOf(CinematicMapAssets.Asset.Frame("Complete BG", 64, 64)),
        )
        val raw = ByteArray(map.rawBytes)
        val word = 0xE6A5 // tile, palette, priority and both flip bits

        val edited = map.withEntry(raw, 33, 34, word)

        val entryIndex = 3 * 1024 + 2 * 32 + 1
        assertEquals(entryIndex, map.rawIndex(33, 34))
        assertEquals(word, map.entry(edited, 33, 34))
        assertEquals(0xA5.toByte(), edited[entryIndex * 2])
        assertEquals(0xE6.toByte(), edited[entryIndex * 2 + 1])
        assertEquals(0, map.entry(edited, 1, 34))
    }

    @Test
    fun `multi-frame assets keep exact source slices`() {
        val map = CinematicMapAssets.source("96FF14")
        val raw = ByteArray(map.rawBytes)
        val word = 0xA345

        val edited = map.withEntry(raw, 1, 2, word, frameIndex = 3)

        assertEquals(4 * 0x800, map.rawBytes)
        assertEquals(3 * 1024 + 2 * 32 + 1, map.rawIndex(1, 2, frameIndex = 3))
        assertEquals(word, map.entry(edited, 1, 2, frameIndex = 3))
        assertEquals(0x800, map.frameData(edited, 3).size)
        assertContentEquals(ByteArray(0x800), map.frameData(edited, 0))
    }

    @Test
    fun `title visual aliases resolve to one source identity`() {
        val title = RomVisualAssets.ASSETS.single { it.name == "Title screen" }
        val background = RomVisualAssets.ASSETS.single { it.name == "Title background" }

        assertSame(CinematicMapAssets.forVisualAsset(title), CinematicMapAssets.forVisualAsset(background))
        assertEquals("96FC04", CinematicMapAssets.forVisualAsset(title)?.key)
    }

    @Test
    fun `native compressed maps match every declared frame boundary`() {
        val parser = TestRomHelper.requireRomParser()

        CinematicMapAssets.SUPPORTED.forEach { map ->
            val raw = map.read(parser)
            assertEquals(map.rawBytes, raw.size, map.name)
            assertContentEquals(
                raw,
                map.frames.indices.flatMap { frame -> map.frameData(raw, frame).asIterable() }.toByteArray(),
                map.name,
            )
            val visual = RomVisualAssets.ASSETS.first { it.map == map.address }.let { asset ->
                if (asset.format == RomVisualAssets.Format.TITLE_SCENE) {
                    asset.copy(format = RomVisualAssets.Format.MODE7_MAP)
                } else asset
            }
            map.frames.forEachIndexed { index, frame ->
                val rendered = assertNotNull(
                    RomVisualAssets.render(
                        parser,
                        visual.copy(columns = frame.columns),
                        mapOverride = map.frameData(raw, index),
                    ),
                    "${map.name} · ${frame.name}",
                )
                assertEquals(frame.columns * 8, rendered.width, frame.name)
                assertEquals(frame.rows * 8, rendered.height, frame.name)
            }
        }
    }
}

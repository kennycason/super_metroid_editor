package com.supermetroid.editor.procgen

import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiomeGeneratorTest {

    /** Block types Samus can occupy or break through. */
    private fun isPassableType(type: Int) = type in setOf(0x0, 0x2, 0x4, 0x7, 0x9, 0xB, 0xC, 0xF)

    /**
     * Synthetic 3x2-screen room: solid shell, air interior, and a vanilla-style
     * left door (4 type-0x9 blocks in column 1 with a solid frame).
     */
    private fun syntheticRoom(width: Int = 48, height: Int = 32): Pair<IntArray, IntArray> {
        val words = IntArray(width * height)
        val bts = IntArray(width * height)
        val solid = (0x8 shl 12) or 0x120
        val air = 0x00FF
        for (y in 0 until height) for (x in 0 until width) {
            words[y * width + x] = if (x < 2 || y < 2 || x >= width - 2 || y >= height - 2) solid else air
        }
        val doorTop = height / 2 - 2
        for (dy in 0 until 4) {
            words[(doorTop + dy) * width + 0] = solid
            words[(doorTop + dy) * width + 1] = (0x9 shl 12) or 0x040
        }
        return words to bts
    }

    private fun rules(seed: Long, style: BiomeStyle = BiomeStyle.CAVERN) = BiomeRules.roll(style, seed)

    @Test
    fun `same seed produces identical rooms`() {
        val (words, bts) = syntheticRoom()
        val profile = TilesetProfile.synthetic()
        for (style in BiomeStyle.values()) {
            val a = BiomeGenerator(rules(42, style), profile, 42).generate(48, 32, words, bts)
            val b = BiomeGenerator(rules(42, style), profile, 42).generate(48, 32, words, bts)
            assertTrue(a.words.contentEquals(b.words), "words must be deterministic ($style)")
            assertTrue(a.bts.contentEquals(b.bts), "bts must be deterministic ($style)")
        }
    }

    @Test
    fun `different seeds produce different rooms`() {
        val (words, bts) = syntheticRoom()
        val profile = TilesetProfile.synthetic()
        val a = BiomeGenerator(rules(1), profile, 1).generate(48, 32, words, bts)
        val b = BiomeGenerator(rules(2), profile, 2).generate(48, 32, words, bts)
        assertTrue(!a.words.contentEquals(b.words), "seeds 1 and 2 should differ")
    }

    @Test
    fun `door blocks and frames are preserved`() {
        val (words, bts) = syntheticRoom()
        val profile = TilesetProfile.synthetic()
        for (style in BiomeStyle.values()) {
            val gen = BiomeGenerator(rules(7, style), profile, 7).generate(48, 32, words, bts)
            for (i in words.indices) {
                if (((words[i] shr 12) and 0xF) == 0x9) {
                    assertEquals(words[i], gen.words[i], "door block $i must be untouched ($style)")
                    assertTrue(gen.preserved[i], "door block $i must be flagged preserved ($style)")
                }
            }
        }
    }

    @Test
    fun `all passable space is one connected region reaching the door`() {
        val (words, bts) = syntheticRoom()
        val profile = TilesetProfile.synthetic()
        for (style in BiomeStyle.values()) {
            for (seed in longArrayOf(3, 99, 12345)) {
                val gen = BiomeGenerator(rules(seed, style), profile, seed).generate(48, 32, words, bts)
                assertSingleRegion(gen, 48, 32, "style=$style seed=$seed")
            }
        }
    }

    private fun assertSingleRegion(gen: BiomeGenerator.GeneratedLevel, w: Int, h: Int, label: String) {
        val passable = BooleanArray(w * h) { isPassableType((gen.words[it] shr 12) and 0xF) }
        val doorIdx = gen.words.indices.first { ((gen.words[it] shr 12) and 0xF) == 0x9 }
        val seen = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        queue.add(doorIdx); seen[doorIdx] = true
        var reached = 0
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            reached++
            val cx = c % w; val cy = c / w
            for ((dx, dy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)) {
                val nx = cx + dx; val ny = cy + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val ni = ny * w + nx
                if (passable[ni] && !seen[ni]) { seen[ni] = true; queue.add(ni) }
            }
        }
        val total = passable.count { it }
        // Preserved door-frame zones may hold original air cells the generator
        // must not touch; allow a small remainder outside the main region.
        assertTrue(
            reached >= (total * 0.97).toInt(),
            "$label: only $reached of $total passable cells reachable from door"
        )
        assertTrue(total > w * h / 10, "$label: suspiciously little open space ($total cells)")
    }

    @Test
    fun `outer ring stays sealed`() {
        val (words, bts) = syntheticRoom()
        val profile = TilesetProfile.synthetic()
        val gen = BiomeGenerator(rules(11), profile, 11).generate(48, 32, words, bts)
        for (x in 0 until 48) {
            for (y in intArrayOf(0, 31)) {
                val i = y * 48 + x
                val type = (gen.words[i] shr 12) and 0xF
                assertTrue(!isPassableType(type) || gen.preserved[i], "edge cell ($x,$y) must be sealed")
            }
        }
        for (y in 0 until 32) {
            for (x in intArrayOf(0, 47)) {
                val i = y * 48 + x
                val type = (gen.words[i] shr 12) and 0xF
                assertTrue(!isPassableType(type) || gen.preserved[i], "edge cell ($x,$y) must be sealed")
            }
        }
    }

    @Test
    fun `large open rooms avoid confetti ledges`() {
        val w = 144
        val h = 80
        val (words, bts) = syntheticRoom(w, h)
        val profile = TilesetProfile.synthetic()
        for (seed in longArrayOf(7, 2026, 99173)) {
            val baseRules = BiomeRules.roll(BiomeStyle.GARDEN, seed)
            val tunedRules = baseRules.withOverrides(
                platformDensity = 0.85,
                hazardDensity = baseRules.hazardDensity,
                destructibleDensity = baseRules.destructibleDensity,
            )
            val gen = BiomeGenerator(tunedRules, profile, seed).generate(w, h, words, bts)
            val runs = floatingLedgeRuns(gen, w, h)
            assertTrue(
                runs.none { it in 1..3 },
                "seed=$seed produced tiny floating ledges: ${runs.filter { it in 1..3 }}"
            )
            assertTrue(
                runs.size <= 24,
                "seed=$seed produced too many floating ledges (${runs.size}): $runs"
            )
        }
    }

    private fun floatingLedgeRuns(gen: BiomeGenerator.GeneratedLevel, w: Int, h: Int): List<Int> {
        fun type(i: Int) = (gen.words[i] shr 12) and 0xF
        fun isLedgeType(t: Int) = t == 0x8 || t == 0xB || t == 0xC || t == 0xF
        val runs = ArrayList<Int>()
        for (y in 2 until h - 2) {
            var x = 1
            while (x < w - 1) {
                val start = x
                while (x < w - 1 &&
                    isLedgeType(type(y * w + x)) &&
                    isPassableType(type((y - 1) * w + x)) &&
                    isPassableType(type((y + 1) * w + x)) &&
                    !gen.preserved[y * w + x]
                ) {
                    x++
                }
                val run = x - start
                if (run > 0 &&
                    isPassableType(type(y * w + start - 1)) &&
                    isPassableType(type(y * w + x))
                ) {
                    runs.add(run)
                }
                x = maxOf(x + 1, start + 1)
            }
        }
        return runs
    }

    @Test
    fun `facility decks form long flat walkable floors`() {
        val (words, bts) = syntheticRoom()
        val profile = TilesetProfile.synthetic()
        for (seed in longArrayOf(5, 77, 901)) {
            val gen = BiomeGenerator(rules(seed, BiomeStyle.FACILITY), profile, seed)
                .generate(48, 32, words, bts)
            fun type(x: Int, y: Int) = (gen.words[y * 48 + x] shr 12) and 0xF
            var flatRows = 0
            for (y in 2 until 29) {
                var best = 0
                var run = 0
                for (x in 2 until 46) {
                    if (isPassableType(type(x, y)) && !isPassableType(type(x, y + 1))) {
                        run++
                        best = maxOf(best, run)
                    } else {
                        run = 0
                    }
                }
                if (best >= 8) flatRows++
            }
            assertTrue(flatRows >= 2, "seed=$seed expected at least 2 long flat decks, got $flatRows")
        }
    }

    @Test
    fun `settlement builds a mostly solid street with structures above`() {
        val (words, bts) = syntheticRoom()
        val profile = TilesetProfile.synthetic()
        for (seed in longArrayOf(9, 314, 5150)) {
            val gen = BiomeGenerator(rules(seed, BiomeStyle.SETTLEMENT), profile, seed)
                .generate(48, 32, words, bts)
            fun type(x: Int, y: Int) = (gen.words[y * 48 + x] shr 12) and 0xF
            val openStreet = (2 until 46).count { isPassableType(type(it, 29)) }
            assertTrue(openStreet <= 4, "seed=$seed street row should be mostly solid, $openStreet open cells")
            var built = 0
            for (y in 4 until 27) for (x in 2 until 46) if (!isPassableType(type(x, y))) built++
            assertTrue(built >= 30, "seed=$seed expected building structure above the street, got $built solid cells")
        }
    }

    @Test
    fun `remix keeps the original macro silhouette while re-rolling detail`() {
        val w = 48
        val h = 32
        val (words, bts) = syntheticRoom(w, h)
        // Give the room a distinctive silhouette: solid lower third.
        val solid = (0x8 shl 12) or 0x120
        for (y in 20 until 30) for (x in 2 until 46) words[y * w + x] = solid
        val profile = TilesetProfile.synthetic()

        fun silhouette(word: Int): Boolean {
            val t = (word shr 12) and 0xF
            return t != 0x0 && t != 0x2 && t != 0x4 && t != 0x6 && t != 0x7 && t != 0x9
        }

        val a = BiomeGenerator(rules(5, BiomeStyle.REMIX), profile, 5).generate(w, h, words, bts)
        var agree = 0
        var total = 0
        for (i in words.indices) {
            if (a.preserved[i]) continue
            total++
            if (silhouette(words[i]) == silhouette(a.words[i])) agree++
        }
        assertTrue(
            agree.toDouble() / total >= 0.7,
            "remix drifted too far from the original silhouette: $agree/$total"
        )

        val b = BiomeGenerator(rules(6, BiomeStyle.REMIX), profile, 6).generate(w, h, words, bts)
        assertTrue(!a.words.contentEquals(b.words), "remix must re-roll detail between seeds")
    }

    // ─── ROM-backed tests (skip when the ROM is unavailable) ────────

    @Test
    fun `learned profile generates a playable room from real ROM data`() {
        val romParser = TestRomHelper.loadRomParser()
        assumeTrue(romParser != null, "ROM not available; skipping")
        romParser!!

        val repo = com.supermetroid.editor.data.RoomRepository()
        val headers = repo.getAllRooms().mapNotNull { romParser.readRoomHeader(it.getRoomIdAsInt()) }
        val landingSite = headers.first { it.roomId == 0x91F8 }
        val profile = TilesetProfile.learn(romParser, headers, landingSite.tileset)
        assertTrue(profile.roomsSampled >= 3, "expected several rooms sharing tileset ${landingSite.tileset}")

        val w = landingSite.width * 16
        val h = landingSite.height * 16
        val grid = LevelGrid.parse(romParser.decompressLZ2(landingSite.levelDataPtr), w, h)!!
        val words = IntArray(w * h)
        val bts = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            words[y * w + x] = grid.word(x, y)
            bts[y * w + x] = grid.bts(x, y)
        }

        for (style in BiomeStyle.values()) {
            val rules = BiomeRules.roll(style, 2026)
            val gen = BiomeGenerator(rules, profile, 2026).generate(w, h, words, bts)
            // Doors survive
            for (i in words.indices) {
                if (((words[i] shr 12) and 0xF) == 0x9) {
                    assertEquals(words[i], gen.words[i], "door must survive ($style)")
                    assertEquals(bts[i], gen.bts[i], "door BTS must survive ($style)")
                }
            }
            assertSingleRegion(gen, w, h, "ROM style=$style")
            // Solid cells should be dressed with real tileset words, not bare type bits
            val solidWords = gen.words.filter { ((it shr 12) and 0xF) == 0x8 }
            assertTrue(solidWords.isNotEmpty(), "expected solid terrain ($style)")
            assertTrue(
                solidWords.count { (it and 0x3FF) != 0 } > solidWords.size / 2,
                "solid terrain should use learned metatiles ($style)"
            )
        }
    }
}

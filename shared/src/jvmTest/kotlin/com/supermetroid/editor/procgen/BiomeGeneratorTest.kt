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
    fun `door cap and pipe tiles are preserved verbatim`() {
        val w = 64
        val h = 40
        val (words, bts) = syntheticRoom(w, h)
        val fixtureCells = addDoorPipeFixture(words, bts, w, h)
        val profile = TilesetProfile.synthetic()
        val doorTop = h / 2 - 2
        val options = BiomeGenerationOptions(
            preserveRects = listOf(BiomeGenerationRect(2, doorTop - 3, 9, doorTop + 6)),
        )

        for (style in BiomeStyle.values()) {
            val gen = BiomeGenerator(rules(727, style), profile, 727, options).generate(w, h, words, bts)
            for (i in fixtureCells) {
                assertEquals(words[i], gen.words[i], "door fixture word $i must be untouched ($style)")
                assertEquals(bts[i], gen.bts[i], "door fixture BTS $i must be untouched ($style)")
                assertTrue(gen.preserved[i], "door fixture $i must be flagged preserved ($style)")
            }
        }
    }

    private fun addDoorPipeFixture(words: IntArray, bts: IntArray, w: Int, h: Int): List<Int> {
        val doorTop = h / 2 - 2
        val cells = ArrayList<Int>()
        for (y in doorTop - 3..doorTop + 6) {
            for (x in 2..9) {
                val i = y * w + x
                val opening = y in doorTop..doorTop + 3
                words[i] = if (opening) {
                    0x00D0 + x
                } else {
                    (0x8 shl 12) or (0x260 + ((x + y) and 0x1F))
                }
                bts[i] = (0x40 + x + y) and 0xFF
                cells.add(i)
            }
        }
        return cells
    }

    @Test
    fun `generation options preserve structures and clear surrounding space`() {
        val w = 64
        val h = 48
        val (words, bts) = syntheticRoom(w, h)
        val profile = TilesetProfile.synthetic()
        val shipTile = (0x8 shl 12) or 0x222
        val shipBts = 0x34
        for (y in 30..34) for (x in 28..35) {
            val i = y * w + x
            words[i] = shipTile
            bts[i] = shipBts
        }

        val options = BiomeGenerationOptions(
            preserveRects = listOf(BiomeGenerationRect(28, 30, 35, 34)),
            forceAirRects = listOf(BiomeGenerationRect(24, 26, 39, 38)),
        )
        val gen = BiomeGenerator(rules(2468, BiomeStyle.PIPE_MAZE), profile, 2468, options)
            .generate(w, h, words, bts)

        for (y in 30..34) for (x in 28..35) {
            val i = y * w + x
            assertEquals(shipTile, gen.words[i], "preserved structure tile ($x,$y) must remain unchanged")
            assertEquals(shipBts, gen.bts[i], "preserved structure BTS ($x,$y) must remain unchanged")
            assertTrue(gen.preserved[i], "preserved structure tile ($x,$y) must be flagged preserved")
        }
        for (x in 24..39) {
            val top = 26 * w + x
            val bottom = 38 * w + x
            assertTrue(isPassableType((gen.words[top] shr 12) and 0xF), "force-air top edge ($x,26) must be open")
            assertTrue(isPassableType((gen.words[bottom] shr 12) and 0xF), "force-air bottom edge ($x,38) must be open")
        }
        for (y in 26..38) {
            val left = y * w + 24
            val right = y * w + 39
            assertTrue(isPassableType((gen.words[left] shr 12) and 0xF), "force-air left edge (24,$y) must be open")
            assertTrue(isPassableType((gen.words[right] shr 12) and 0xF), "force-air right edge (39,$y) must be open")
        }
    }

    @Test
    fun `wave function uses learned sample tiles and keeps original doors connected`() {
        val w = 48
        val h = 32
        val (words, bts) = syntheticRoom(w, h)
        val sampleW = 24
        val sampleH = 24
        val sampleWords = IntArray(sampleW * sampleH)
        val sampleBts = IntArray(sampleW * sampleH)
        val learnedAir = 0x00F1
        val learnedSolid = (0x8 shl 12) or 0x221
        for (y in 0 until sampleH) for (x in 0 until sampleW) {
            sampleWords[y * sampleW + x] = if (x % 5 == 0 || y % 7 == 0) learnedSolid else learnedAir
        }
        val options = BiomeGenerationOptions(
            wfcSamples = listOf(WfcSample(sampleW, sampleH, sampleWords, sampleBts)),
            wfcOptions = WfcOptions(morphAmount = 0.8, originalSpaceBias = 0.45),
        )
        val gen = BiomeGenerator(rules(8642, BiomeStyle.WAVE_FUNCTION), TilesetProfile.synthetic(), 8642, options)
            .generate(w, h, words, bts)

        assertDoorGroupsConnected(gen, words, w, h, "WFC synthetic")
        assertTrue(
            gen.words.indices.any { !gen.preserved[it] && gen.words[it] == learnedSolid },
            "WFC should copy solid tile states from the supplied sample"
        )
        for (i in words.indices) {
            if (((words[i] shr 12) and 0xF) == 0x9) {
                assertEquals(words[i], gen.words[i], "WFC must preserve original door cell $i")
                assertEquals(bts[i], gen.bts[i], "WFC must preserve original door BTS $i")
            }
            if (!gen.preserved[i]) {
                val type = (gen.words[i] shr 12) and 0xF
                assertTrue(type != 0x5 && type != 0x9 && type != 0xD, "WFC should not synthesize extend/door type $type")
            }
        }
    }

    @Test
    fun `wave function keeps door approach corridors open under heavy morphing`() {
        val w = 64
        val h = 40
        val (words, bts) = syntheticRoom(w, h)
        val sampleW = 32
        val sampleH = 32
        val sampleWords = IntArray(sampleW * sampleH)
        val sampleBts = IntArray(sampleW * sampleH)
        val learnedAir = 0x00E1
        val learnedSolid = (0x8 shl 12) or 0x242
        for (y in 0 until sampleH) for (x in 0 until sampleW) {
            sampleWords[y * sampleW + x] = if (x % 7 == 0 || y % 6 == 0) learnedAir else learnedSolid
        }
        val options = BiomeGenerationOptions(
            wfcSamples = listOf(WfcSample(sampleW, sampleH, sampleWords, sampleBts)),
            wfcOptions = WfcOptions(
                morphAmount = 1.0,
                originalSpaceBias = 1.0,
                tunnelWidth = 3,
                tunnelBendiness = 0.8,
            ),
        )
        val gen = BiomeGenerator(rules(1212, BiomeStyle.WAVE_FUNCTION), TilesetProfile.synthetic(), 1212, options)
            .generate(w, h, words, bts)
        val doorTop = h / 2 - 2

        for (x in 2..16) {
            for (y in doorTop..doorTop + 3) {
                val type = (gen.words[y * w + x] shr 12) and 0xF
                assertTrue(isPassableType(type), "door approach cell ($x,$y) must remain passable")
            }
        }
        assertDoorGroupsConnected(gen, words, w, h, "WFC heavy morph")
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

    private fun assertDoorGroupsConnected(
        gen: BiomeGenerator.GeneratedLevel,
        originalWords: IntArray,
        w: Int,
        h: Int,
        label: String,
    ) {
        val groups = doorGroups(originalWords, w, h)
        assertTrue(groups.isNotEmpty(), "$label: expected at least one original door group")
        val passable = BooleanArray(w * h) { isPassableType((gen.words[it] shr 12) and 0xF) }
        val comp = IntArray(w * h) { -1 }
        var compCount = 0
        for (start in passable.indices) {
            if (!passable[start] || comp[start] != -1) continue
            val queue = ArrayDeque<Int>()
            queue.add(start)
            comp[start] = compCount
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                val cx = c % w
                val cy = c / w
                for ((dx, dy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (passable[ni] && comp[ni] == -1) {
                        comp[ni] = compCount
                        queue.add(ni)
                    }
                }
            }
            compCount++
        }

        val expected = groups.first().map { comp[it] }.firstOrNull { it >= 0 }
        assertTrue(expected != null, "$label: first door group is not passable")
        for (group in groups) {
            val groupComp = group.map { comp[it] }.firstOrNull { it >= 0 }
            val where = group.joinToString { "(${it % w},${it / w})" }
            assertEquals(expected, groupComp, "$label: door group $where is not connected to the main door path")
        }
    }

    private fun doorGroups(words: IntArray, w: Int, h: Int): List<List<Int>> {
        val doorCells = words.indices.filter { ((words[it] shr 12) and 0xF) == 0x9 }.toMutableSet()
        val groups = ArrayList<List<Int>>()
        while (doorCells.isNotEmpty()) {
            val start = doorCells.first()
            doorCells.remove(start)
            val group = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                group.add(c)
                val cx = c % w
                val cy = c / w
                for ((dx, dy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (doorCells.remove(ni)) queue.add(ni)
                }
            }
            groups.add(group)
        }
        return groups
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
    fun `pipe maze uses CRE pipe and joint solids`() {
        val (words, bts) = syntheticRoom()
        val profile = TilesetProfile.synthetic()
        val rules = rules(2026, BiomeStyle.PIPE_MAZE)
        val gen = BiomeGenerator(rules, profile, 2026).generate(48, 32, words, bts)

        val mazeSolidMetatiles = gen.words.indices
            .filter { !gen.preserved[it] && ((gen.words[it] shr 12) and 0xF) == 0x8 }
            .map { gen.words[it] and 0x3FF }
            .toSet()

        assertTrue(0x05F in mazeSolidMetatiles, "pipe maze should use CRE joint tile 0x05F")
        assertTrue(0x0BE in mazeSolidMetatiles, "pipe maze should use CRE pipe tile 0x0BE")
        assertTrue(
            mazeSolidMetatiles.all { it == 0x05F || it == 0x0BE },
            "pipe maze solids should only use requested CRE tiles, got $mazeSolidMetatiles"
        )
    }

    @Test
    fun `pipe maze corridors stay mostly one tile wide`() {
        val (words, bts) = syntheticRoom()
        val profile = TilesetProfile.synthetic()
        val rules = rules(5150, BiomeStyle.PIPE_MAZE)
            .withMazeOverrides(branchDensity = 0.45, loopDensity = 0.05, hubSize = 0.0, emptyCenter = false)
        val gen = BiomeGenerator(rules, profile, 5150).generate(48, 32, words, bts)

        var openTwoByTwos = 0
        for (y in 2 until 29) for (x in 2 until 45) {
            val cells = intArrayOf(y * 48 + x, y * 48 + x + 1, (y + 1) * 48 + x, (y + 1) * 48 + x + 1)
            if (cells.all { isPassableType((gen.words[it] shr 12) and 0xF) }) openTwoByTwos++
        }
        assertTrue(
            openTwoByTwos <= 16,
            "pipe maze should not widen into broad rooms; found $openTwoByTwos open 2x2 blocks"
        )
    }

    @Test
    fun `pipe maze connects every Parlor door group`() {
        val romParser = TestRomHelper.loadRomParser()
        assumeTrue(romParser != null, "ROM not available; skipping")
        romParser!!

        val parlor = romParser.readRoomHeader(0x92FD) ?: error("Parlor room missing")
        val w = parlor.width * 16
        val h = parlor.height * 16
        val grid = LevelGrid.parse(romParser.decompressLZ2(parlor.levelDataPtr), w, h)!!
        val words = IntArray(w * h)
        val bts = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            words[y * w + x] = grid.word(x, y)
            bts[y * w + x] = grid.bts(x, y)
        }
        val profile = TilesetProfile.synthetic()

        for (seed in longArrayOf(7, 42, 2026, 5150, 99173)) {
            val rules = rules(seed, BiomeStyle.PIPE_MAZE)
            val gen = BiomeGenerator(rules, profile, seed).generate(w, h, words, bts)
            assertDoorGroupsConnected(gen, words, w, h, "Parlor pipe maze seed=$seed")
        }
    }

    @Test
    fun `pipe maze preserves original non-slope special collision tiles`() {
        val w = 64
        val h = 40
        val (words, bts) = syntheticRoom(w, h)
        val profile = TilesetProfile.synthetic()
        val collisionTypes = listOf(0x3, 0xA, 0xB, 0xC, 0xE, 0xF)
        val protectedCells = collisionTypes.mapIndexedTo(ArrayList()) { offset, type ->
            val x = 12 + offset * 3
            val y = 8 + (offset % 3) * 6
            val i = y * w + x
            words[i] = (type shl 12) or (0x220 + offset)
            bts[i] = 0x40 + offset
            i
        }
        val spikeAnchor = 26 * w + 28
        words[spikeAnchor] = (0xA shl 12) or 0x260
        words[spikeAnchor + 1] = (0x5 shl 12) or 0x261
        words[spikeAnchor + w] = (0xD shl 12) or 0x262
        bts[spikeAnchor] = 0x50
        bts[spikeAnchor + 1] = 0x51
        bts[spikeAnchor + w] = 0x52
        protectedCells.add(spikeAnchor)
        protectedCells.add(spikeAnchor + 1)
        protectedCells.add(spikeAnchor + w)

        val gen = BiomeGenerator(rules(13579, BiomeStyle.PIPE_MAZE), profile, 13579)
            .generate(w, h, words, bts)

        for (i in protectedCells) {
            assertEquals(words[i], gen.words[i], "collidable word $i must remain unchanged")
            assertEquals(bts[i], gen.bts[i], "collidable BTS $i must remain unchanged")
            assertTrue(gen.preserved[i], "collidable tile $i must be flagged preserved")
        }
    }

    @Test
    fun `pipe maze clears original slopes from generated maze cells`() {
        val w = 64
        val h = 40
        val seed = 97531L
        val (baseWords, baseBts) = syntheticRoom(w, h)
        val profile = TilesetProfile.synthetic()
        val base = BiomeGenerator(rules(seed, BiomeStyle.PIPE_MAZE), profile, seed)
            .generate(w, h, baseWords, baseBts)

        fun type(i: Int) = (base.words[i] shr 12) and 0xF
        fun open(i: Int) = !base.preserved[i] && isPassableType(type(i))
        fun wall(i: Int) = !base.preserved[i] && !isPassableType(type(i))
        val used = mutableSetOf<Int>()
        val hAnchor = (2 until h - 2).firstNotNullOf { y ->
            (2 until w - 3).firstOrNull { x ->
                val i = y * w + x
                open(i) && open(i + 1)
            }?.let { x -> y * w + x }
        }
        used.add(hAnchor)
        used.add(hAnchor + 1)
        val vAnchor = (2 until h - 3).firstNotNullOf { y ->
            (2 until w - 2).firstOrNull { x ->
                val i = y * w + x
                i !in used && i + w !in used && open(i) && open(i + w)
            }?.let { x -> y * w + x }
        }
        used.add(vAnchor)
        used.add(vAnchor + w)
        val wallHAnchor = (2 until h - 2).firstNotNullOf { y ->
            (2 until w - 3).firstOrNull { x ->
                val i = y * w + x
                i !in used && i + 1 !in used && wall(i) && wall(i + 1)
            }?.let { x -> y * w + x }
        }
        used.add(wallHAnchor)
        used.add(wallHAnchor + 1)
        val wallVAnchor = (2 until h - 3).firstNotNullOf { y ->
            (2 until w - 2).firstOrNull { x ->
                val i = y * w + x
                i !in used && i + w !in used && wall(i) && wall(i + w)
            }?.let { x -> y * w + x }
        }

        val (words, bts) = syntheticRoom(w, h)
        words[hAnchor] = (0x1 shl 12) or 0x2A0
        words[hAnchor + 1] = (0x5 shl 12) or 0x2A1
        words[vAnchor] = (0x1 shl 12) or 0x2A2
        words[vAnchor + w] = (0xD shl 12) or 0x2A3
        words[wallHAnchor] = (0x1 shl 12) or 0x2A4
        words[wallHAnchor + 1] = (0x5 shl 12) or 0x2A5
        words[wallVAnchor] = (0x1 shl 12) or 0x2A6
        words[wallVAnchor + w] = (0xD shl 12) or 0x2A7
        bts[hAnchor] = 0x01
        bts[hAnchor + 1] = 0x02
        bts[vAnchor] = 0x03
        bts[vAnchor + w] = 0x04
        bts[wallHAnchor] = 0x05
        bts[wallHAnchor + 1] = 0x06
        bts[wallVAnchor] = 0x07
        bts[wallVAnchor + w] = 0x08

        val gen = BiomeGenerator(rules(seed, BiomeStyle.PIPE_MAZE), profile, seed)
            .generate(w, h, words, bts)

        val openSlopeCells = listOf(hAnchor, hAnchor + 1, vAnchor, vAnchor + w)
        val wallSlopeCells = listOf(wallHAnchor, wallHAnchor + 1, wallVAnchor, wallVAnchor + w)
        for (i in openSlopeCells) {
            val generatedType = (gen.words[i] shr 12) and 0xF
            assertTrue(!gen.preserved[i], "slope path cell $i should not stay preserved")
            assertTrue(isPassableType(generatedType), "slope path cell $i should be cleared to passable space")
            assertTrue(generatedType !in setOf(0x1, 0x5, 0xD), "slope path cell $i should not keep slope or extension type")
        }
        for (i in wallSlopeCells) {
            val generatedType = (gen.words[i] shr 12) and 0xF
            assertTrue(!gen.preserved[i], "slope wall cell $i should not stay preserved")
            assertEquals(0x8, generatedType, "slope wall cell $i should be dressed as a normal maze wall")
        }
    }

    @Test
    fun `pipe maze keeps a three by three opening in front of edge doors`() {
        val w = 64
        val h = 40
        val (words, bts) = syntheticRoom(w, h)
        addRightDoor(words, w, h)
        addTopDoor(words, w)
        addBottomDoor(words, w, h)
        val profile = TilesetProfile.synthetic()

        val gen = BiomeGenerator(rules(24601, BiomeStyle.PIPE_MAZE), profile, 24601)
            .generate(w, h, words, bts)

        val sideDoorTop = h / 2 - 2
        assertPassableRect(gen, w, x0 = 2, y0 = sideDoorTop, width = 3, height = 3, label = "left door")
        assertPassableRect(gen, w, x0 = w - 5, y0 = sideDoorTop, width = 3, height = 3, label = "right door")
        val topDoorLeft = w / 2 - 2
        assertPassableRect(gen, w, x0 = topDoorLeft, y0 = 2, width = 3, height = 3, label = "top door")
        assertPassableRect(gen, w, x0 = topDoorLeft, y0 = h - 5, width = 3, height = 3, label = "bottom door")
        assertDoorGroupsConnected(gen, words, w, h, "four-door pipe maze")
    }

    @Test
    fun `connectivity tunnels route around protected collision barriers`() {
        val w = 64
        val h = 40
        val cells = IntArray(w * h) { BiomeCell.SOLID }
        val protected = BooleanArray(w * h)
        fun idx(x: Int, y: Int) = y * w + x
        cells[idx(5, 20)] = BiomeCell.AIR
        cells[idx(58, 20)] = BiomeCell.AIR
        for (y in 5..34) {
            if (y == 8) continue
            protected[idx(32, y)] = true
        }

        GridConnectivity.ensureConnectedByIndex(
            cells,
            w,
            h,
            kotlin.random.Random(2026),
            tunnelThickness = 1,
            canCarve = { !protected[it] },
            passable = { cells[it] == BiomeCell.AIR },
        )

        assertTrue(connected(cells, w, idx(5, 20), idx(58, 20)), "path must route through the barrier gap")
        for (y in 5..34) {
            if (y == 8) continue
            assertEquals(BiomeCell.SOLID, cells[idx(32, y)], "protected barrier cell (32,$y) must remain solid")
        }
    }

    private fun addRightDoor(words: IntArray, w: Int, h: Int) {
        val solid = (0x8 shl 12) or 0x120
        val doorTop = h / 2 - 2
        for (dy in 0 until 4) {
            words[(doorTop + dy) * w + (w - 1)] = solid
            words[(doorTop + dy) * w + (w - 2)] = (0x9 shl 12) or 0x040
        }
    }

    private fun addTopDoor(words: IntArray, w: Int) {
        val solid = (0x8 shl 12) or 0x120
        val left = w / 2 - 2
        for (dx in 0 until 4) {
            words[dx + left] = solid
            words[w + left + dx] = (0x9 shl 12) or 0x040
        }
    }

    private fun addBottomDoor(words: IntArray, w: Int, h: Int) {
        val solid = (0x8 shl 12) or 0x120
        val left = w / 2 - 2
        for (dx in 0 until 4) {
            words[(h - 1) * w + left + dx] = solid
            words[(h - 2) * w + left + dx] = (0x9 shl 12) or 0x040
        }
    }

    private fun assertPassableRect(
        gen: BiomeGenerator.GeneratedLevel,
        w: Int,
        x0: Int,
        y0: Int,
        width: Int,
        height: Int,
        label: String,
    ) {
        for (y in y0 until y0 + height) for (x in x0 until x0 + width) {
            val type = (gen.words[y * w + x] shr 12) and 0xF
            assertTrue(isPassableType(type), "$label opening cell ($x,$y) must be passable")
        }
    }

    private fun connected(cells: IntArray, w: Int, start: Int, target: Int): Boolean {
        val seen = BooleanArray(cells.size)
        val queue = ArrayDeque<Int>()
        seen[start] = true
        queue.add(start)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (c == target) return true
            val x = c % w
            val y = c / w
            for ((dx, dy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until cells.size / w) continue
                val ni = ny * w + nx
                if (!seen[ni] && cells[ni] == BiomeCell.AIR) {
                    seen[ni] = true
                    queue.add(ni)
                }
            }
        }
        return false
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

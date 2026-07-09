package com.supermetroid.editor.procgen

import kotlin.random.Random

data class WfcSample(
    val width: Int,
    val height: Int,
    val words: IntArray,
    val bts: IntArray,
    val sampleRank: Int = 0,
)

data class WfcOptions(
    /** 0..1 bias toward repeating compatible neighbor states. */
    val coherence: Double = 0.72,
    /** 0..1 amount of learned room material painted over the generated structure. */
    val morphAmount: Double = 0.55,
    /** 0..1 amount of the original room's walkable silhouette to keep open. */
    val originalSpaceBias: Double = 0.72,
    /** Target fraction of passable room space after sampling and cleanup. */
    val openSpaceTarget: Double = 0.38,
    /** Width in tiles for guaranteed door/connectivity tunnels. */
    val tunnelWidth: Int = 2,
    /** 0..1 randomness in tunnel turns; higher values produce less L-shaped routes. */
    val tunnelBendiness: Double = 0.35,
    val allowBombs: Boolean = false,
    val allowMissiles: Boolean = false,
    val allowCrumble: Boolean = false,
    val allowSpikes: Boolean = false,
)

internal class WaveFunctionCollapseGenerator(
    private val rules: BiomeRules,
    private val profile: TilesetProfile,
    private val seed: Long,
    private val samples: List<WfcSample>,
    private val options: WfcOptions,
) {
    fun generate(
        width: Int,
        height: Int,
        originalWords: IntArray,
        originalBts: IntArray,
        preserved: BooleanArray,
        forceAir: BooleanArray,
    ): BiomeGenerator.GeneratedLevel {
        val rng = Random(seed * 59 + rules.style.ordinal)
        val n = width * height
        val words = IntArray(n)
        val bts = IntArray(n)
        val airWord = profile.airWord
        val airBts = 0
        val originalOpen = buildOriginalOpenMask(width, height, originalWords, preserved, forceAir)
        val doorGroups = buildDoorGroups(originalWords, width, height)
        val doorHalo = buildDoorHalo(width, height, doorGroups)
        val cells = buildDoorSeededStructure(width, height, originalWords, preserved, forceAir, originalOpen, doorGroups, rng)

        val dressed = TileDresser.dress(cells, width, height, preserved, originalWords, originalBts, profile, rules, rng)
        dressed.words.copyInto(words)
        dressed.bts.copyInto(bts)
        paintLearnedDetail(width, height, cells, words, bts, preserved, forceAir, doorHalo, rng)
        ensureMinimumOpenSpace(width, height, words, bts, preserved, rng, airWord, airBts)
        carveDoorApproaches(width, height, originalWords, words, bts, preserved, rng, airWord, airBts)
        connectPassable(width, height, originalWords, originalBts, words, bts, preserved, rng, airWord, airBts)
        restoreDoorHalo(originalWords, originalBts, words, bts, preserved, forceAir, doorHalo)
        for (i in 0 until n) {
            if (preserved[i]) {
                words[i] = originalWords[i]
                bts[i] = originalBts[i]
            }
            if (forceAir[i] && !preserved[i]) {
                words[i] = profile.airWord
                bts[i] = 0
            }
        }
        return BiomeGenerator.GeneratedLevel(width, height, words, bts, preserved)
    }

    private fun buildDoorSeededStructure(
        width: Int,
        height: Int,
        originalWords: IntArray,
        preserved: BooleanArray,
        forceAir: BooleanArray,
        originalOpen: BooleanArray,
        doorGroups: List<WfcDoorGroup>,
        rng: Random,
    ): IntArray {
        val cells = IntArray(width * height) { BiomeCell.SOLID }
        val seeds = buildDoorSeeds(width, height, doorGroups)
        val effectiveSeeds = if (seeds.isNotEmpty()) seeds else listOf(DoorSeed(width / 2, height / 2))
        val meeting = chooseMeetingPoint(width, height, effectiveSeeds, originalOpen, rng)
        val thickness = options.tunnelWidth.coerceIn(1, 4)
        val bendiness = options.tunnelBendiness.coerceIn(0.0, 1.0)

        fun canOpen(x: Int, y: Int): Boolean =
            x in 1 until width - 1 && y in 1 until height - 1 && !preserved[y * width + x]

        fun carveBrush(cx: Int, cy: Int, rx: Int, ry: Int): Int {
            var opened = 0
            for (y in cy - ry..cy + ry) for (x in cx - rx..cx + rx) {
                if (!canOpen(x, y)) continue
                val i = y * width + x
                if (cells[i] != BiomeCell.AIR) opened++
                cells[i] = BiomeCell.AIR
            }
            return opened
        }

        for (i in forceAir.indices) if (forceAir[i] && !preserved[i]) cells[i] = BiomeCell.AIR
        for (seed in effectiveSeeds) {
            carveBrush(seed.x, seed.y, thickness / 2, maxOf(1, thickness / 2))
            carveOrganicTunnel(cells, width, height, seed.x, seed.y, meeting.x, meeting.y, thickness, bendiness, preserved, rng)
        }
        if (effectiveSeeds.size > 2) {
            val loops = (effectiveSeeds.size * (0.15 + options.morphAmount.coerceIn(0.0, 1.0) * 0.35)).toInt()
            repeat(loops) {
                val a = effectiveSeeds[rng.nextInt(effectiveSeeds.size)]
                val b = effectiveSeeds[rng.nextInt(effectiveSeeds.size)]
                if (a != b) carveOrganicTunnel(cells, width, height, a.x, a.y, b.x, b.y, thickness, bendiness, preserved, rng)
            }
        }

        val originalOpenTarget = (originalOpen.count { it } * options.originalSpaceBias.coerceIn(0.0, 1.0)).toInt()
        val target = maxOf(
            (cells.size * options.openSpaceTarget.coerceIn(MIN_OPEN_FRACTION, MAX_OPEN_FRACTION)).toInt(),
            originalOpenTarget,
            effectiveSeeds.size * 48,
        ).coerceAtMost((cells.size * MAX_OPEN_FRACTION).toInt())
        growConnectedOpenSpace(cells, width, height, target, originalOpen, preserved, rng)

        for (i in cells.indices) {
            if (forceAir[i] && !preserved[i]) cells[i] = BiomeCell.AIR
            if (preserved[i] && isGameplayPassableType(typeOf(originalWords[i]))) cells[i] = BiomeCell.AIR
        }
        return cells
    }

    private fun buildDoorSeeds(width: Int, height: Int, groups: List<WfcDoorGroup>): List<DoorSeed> {
        val seeds = ArrayList<DoorSeed>()
        for (group in groups) {
            if (group.vertical) {
                val dir = when {
                    group.minX <= 4 -> 1
                    group.maxX >= width - 5 -> -1
                    (group.minX + group.maxX) / 2 < width / 2 -> 1
                    else -> -1
                }
                seeds.add(DoorSeed(
                    x = (if (dir > 0) group.maxX + 1 else group.minX - 1).coerceIn(1, width - 2),
                    y = ((group.minY + group.maxY) / 2).coerceIn(1, height - 2),
                ))
            } else {
                val dir = when {
                    group.minY <= 4 -> 1
                    group.maxY >= height - 5 -> -1
                    (group.minY + group.maxY) / 2 < height / 2 -> 1
                    else -> -1
                }
                seeds.add(DoorSeed(
                    x = ((group.minX + group.maxX) / 2).coerceIn(1, width - 2),
                    y = (if (dir > 0) group.maxY + 1 else group.minY - 1).coerceIn(1, height - 2),
                ))
            }
        }
        return seeds.distinct()
    }

    private fun chooseMeetingPoint(
        width: Int,
        height: Int,
        seeds: List<DoorSeed>,
        originalOpen: BooleanArray,
        rng: Random,
    ): DoorSeed {
        val avgX = seeds.sumOf { it.x } / seeds.size
        val avgY = seeds.sumOf { it.y } / seeds.size
        var best = DoorSeed(avgX.coerceIn(3, width - 4), avgY.coerceIn(3, height - 4))
        var bestScore = Int.MAX_VALUE
        val search = 10
        for (y in best.y - search..best.y + search) for (x in best.x - search..best.x + search) {
            if (x !in 3 until width - 3 || y !in 3 until height - 3) continue
            val i = y * width + x
            val dist = kotlin.math.abs(x - avgX) + kotlin.math.abs(y - avgY)
            val score = dist - if (originalOpen[i]) 8 else 0
            if (score < bestScore || (score == bestScore && rng.nextBoolean())) {
                bestScore = score
                best = DoorSeed(x, y)
            }
        }
        return best
    }

    private fun carveOrganicTunnel(
        cells: IntArray,
        width: Int,
        height: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        thickness: Int,
        bendiness: Double,
        preserved: BooleanArray,
        rng: Random,
    ) {
        fun carve(cx: Int, cy: Int) {
            val r = thickness / 2
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                if (x !in 1 until width - 1 || y !in 1 until height - 1) continue
                val i = y * width + x
                if (!preserved[i]) cells[i] = BiomeCell.AIR
            }
        }
        var x = x0
        var y = y0
        var sideDrift = 0
        var guard = 0
        while ((x != x1 || y != y1) && guard++ < width + height + 96) {
            carve(x, y)
            if (rng.nextDouble() < bendiness * 0.22) {
                sideDrift = (sideDrift + rng.nextInt(-1, 2)).coerceIn(-4, 4)
            }
            val dx = x1 - x
            val dy = y1 - y
            val moveX = when {
                dx == 0 -> false
                dy == 0 -> true
                rng.nextDouble() < bendiness * 0.28 -> rng.nextBoolean()
                else -> kotlin.math.abs(dx) >= kotlin.math.abs(dy)
            }
            if (moveX) {
                x += if (dx > 0) 1 else -1
                if (sideDrift != 0 && y + sideDrift.sign() in 1 until height - 1 && rng.nextDouble() < bendiness * 0.18) {
                    y += sideDrift.sign()
                }
            } else {
                y += if (dy > 0) 1 else -1
                if (sideDrift != 0 && x + sideDrift.sign() in 1 until width - 1 && rng.nextDouble() < bendiness * 0.18) {
                    x += sideDrift.sign()
                }
            }
        }
        carve(x1, y1)
    }

    private fun growConnectedOpenSpace(
        cells: IntArray,
        width: Int,
        height: Int,
        targetOpen: Int,
        originalOpen: BooleanArray,
        preserved: BooleanArray,
        rng: Random,
    ) {
        val frontier = ArrayList<Int>()
        val queued = BooleanArray(cells.size)
        fun canOpen(i: Int): Boolean {
            val x = i % width
            val y = i / width
            return x in 2 until width - 2 && y in 2 until height - 2 && !preserved[i]
        }
        fun addFrontierAround(i: Int) {
            val x = i % width
            val y = i / width
            for ((dx, dy) in CARDINALS) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 2 until width - 2 || ny !in 2 until height - 2) continue
                val ni = ny * width + nx
                if (cells[ni] == BiomeCell.SOLID && !queued[ni] && canOpen(ni)) {
                    queued[ni] = true
                    frontier.add(ni)
                }
            }
        }
        var open = 0
        for (i in cells.indices) {
            if (cells[i] == BiomeCell.AIR) {
                open++
                addFrontierAround(i)
            }
        }
        var guard = 0
        while (open < targetOpen && frontier.isNotEmpty() && guard++ < cells.size * 8) {
            val slot = rng.nextInt(frontier.size)
            val i = frontier[slot]
            frontier[slot] = frontier.last()
            frontier.removeAt(frontier.lastIndex)
            queued[i] = false
            if (!canOpen(i) || cells[i] != BiomeCell.SOLID) continue
            val chance = if (originalOpen[i]) 0.92 else 0.52 + options.morphAmount.coerceIn(0.0, 1.0) * 0.22
            if (rng.nextDouble() > chance) {
                addFrontierAround(i)
                continue
            }
            val chamber = rng.nextDouble() < 0.12 + options.morphAmount.coerceIn(0.0, 1.0) * 0.10
            val rx = if (chamber) rng.nextInt(1, 4) else 0
            val ry = if (chamber) rng.nextInt(1, 3) else 0
            val cx = i % width
            val cy = i / width
            for (y in cy - ry..cy + ry) for (x in cx - rx..cx + rx) {
                if (x !in 2 until width - 2 || y !in 2 until height - 2) continue
                val ni = y * width + x
                if (!canOpen(ni) || cells[ni] == BiomeCell.AIR) continue
                cells[ni] = BiomeCell.AIR
                open++
                addFrontierAround(ni)
                if (open >= targetOpen) return
            }
        }
    }

    private fun Int.sign(): Int = when {
        this < 0 -> -1
        this > 0 -> 1
        else -> 0
    }

    private fun paintLearnedDetail(
        width: Int,
        height: Int,
        cells: IntArray,
        words: IntArray,
        bts: IntArray,
        preserved: BooleanArray,
        forceAir: BooleanArray,
        doorHalo: BooleanArray,
        rng: Random,
    ) {
        val sourceSamples = samples.filter {
            it.width >= MIN_CHUNK_SIZE && it.height >= MIN_CHUNK_SIZE &&
                it.words.size >= it.width * it.height &&
                it.bts.size >= it.width * it.height
        }
        if (sourceSamples.isEmpty()) return

        val detail = options.morphAmount.coerceIn(0.0, 1.0)
        val chunkSizes = listOf(8 to 8, 12 to 12, 16 to 12, 16 to 16, 24 to 16, 24 to 24, 32 to 24)
        val stampCount = (width * height / 420.0 * (0.35 + detail * 1.35)).toInt().coerceIn(4, 56)
        repeat(stampCount * 5) { attempt ->
            if (attempt >= stampCount && rng.nextDouble() < 0.58) return@repeat
            val sample = pickVisualSample(sourceSamples, rng)
            val (cw0, ch0) = chunkSizes[rng.nextInt(chunkSizes.size)]
            val cw = minOf(cw0, sample.width, width - BiomeCell.BORDER * 2)
            val ch = minOf(ch0, sample.height, height - BiomeCell.BORDER * 2)
            if (cw < MIN_CHUNK_SIZE || ch < MIN_CHUNK_SIZE) return@repeat
            val sx = rng.nextInt(0, sample.width - cw + 1)
            val sy = rng.nextInt(0, sample.height - ch + 1)
            val dx = rng.nextInt(BiomeCell.BORDER, width - BiomeCell.BORDER - cw + 1)
            val dy = rng.nextInt(BiomeCell.BORDER, height - BiomeCell.BORDER - ch + 1)
            if (!chunkMatchesGeneratedPassability(sample, sx, sy, cw, ch, cells, width, dx, dy)) return@repeat

            for (yy in 0 until ch) {
                for (xx in 0 until cw) {
                    val src = (sy + yy) * sample.width + sx + xx
                    val dst = (dy + yy) * width + dx + xx
                    if (preserved[dst] || forceAir[dst] || doorHalo[dst]) continue
                    if (acceptedKey(sample.words[src], sample.bts[src]) == null) continue
                    val srcPassable = isGameplayPassableType(typeOf(sample.words[src]))
                    val dstPassable = cells[dst] == BiomeCell.AIR
                    if (srcPassable != dstPassable) continue
                    words[dst] = sample.words[src]
                    bts[dst] = if (srcPassable && typeOf(sample.words[src]) in AIR_TYPES) 0 else sample.bts[src]
                }
            }
        }
    }

    private fun pickVisualSample(samples: List<WfcSample>, rng: Random): WfcSample {
        fun weight(sample: WfcSample): Int = when (sample.sampleRank) {
            0 -> 12
            1 -> 10
            2 -> 6
            3 -> 2
            else -> 1
        }
        val total = samples.sumOf { weight(it) }
        var roll = rng.nextInt(total.coerceAtLeast(1))
        for (sample in samples) {
            roll -= weight(sample)
            if (roll < 0) return sample
        }
        return samples.first()
    }

    private fun chunkMatchesGeneratedPassability(
        sample: WfcSample,
        sx: Int,
        sy: Int,
        cw: Int,
        ch: Int,
        cells: IntArray,
        width: Int,
        dx: Int,
        dy: Int,
    ): Boolean {
        var accepted = 0
        var matching = 0
        var solid = 0
        var open = 0
        for (yy in 0 until ch) {
            for (xx in 0 until cw) {
                val src = (sy + yy) * sample.width + sx + xx
                if (acceptedKey(sample.words[src], sample.bts[src]) == null) continue
                accepted++
                val srcPassable = isGameplayPassableType(typeOf(sample.words[src]))
                val dstPassable = cells[(dy + yy) * width + dx + xx] == BiomeCell.AIR
                if (srcPassable) open++ else solid++
                if (srcPassable == dstPassable) matching++
            }
        }
        if (accepted < cw * ch * 0.6) return false
        if (solid < 6 || open < 6) return false
        return matching.toDouble() / accepted >= 0.46
    }

    private fun buildDoorHalo(width: Int, height: Int, groups: List<WfcDoorGroup>): BooleanArray {
        val mask = BooleanArray(width * height)
        for (group in groups) {
            val x0 = (group.minX - 2).coerceAtLeast(0)
            val x1 = (group.maxX + 2).coerceAtMost(width - 1)
            val y0 = (group.minY - 2).coerceAtLeast(0)
            val y1 = (group.maxY + 2).coerceAtMost(height - 1)
            for (y in y0..y1) for (x in x0..x1) mask[y * width + x] = true
        }
        return mask
    }

    private fun restoreDoorHalo(
        originalWords: IntArray,
        originalBts: IntArray,
        words: IntArray,
        bts: IntArray,
        preserved: BooleanArray,
        forceAir: BooleanArray,
        doorHalo: BooleanArray,
    ) {
        for (i in words.indices) {
            if (!doorHalo[i] && !preserved[i]) continue
            val originalType = typeOf(originalWords[i])
            val keepOriginal = preserved[i] ||
                (!forceAir[i] && (!isGameplayPassableType(originalType) || originalBts.getOrElse(i) { 0 } != 0))
            if (!keepOriginal) continue
            words[i] = originalWords[i]
            bts[i] = originalBts[i]
        }
    }

    private fun fillNaturalBase(
        width: Int,
        height: Int,
        originalWords: IntArray,
        originalBts: IntArray,
        words: IntArray,
        bts: IntArray,
        preserved: BooleanArray,
        forceAir: BooleanArray,
        rng: Random,
    ) {
        val cells = IntArray(width * height)
        val caveRules = rules.copy(
            algorithm = StructureAlgorithm.CAVE,
            fillChance = 0.46,
            caIterations = 5,
            birthLimit = 5,
            surviveLimit = 4,
            verticalBias = 1.05,
            platformDensity = 0.0,
            hazardDensity = 0.0,
            destructibleDensity = 0.0,
            mutators = emptySet(),
        )
        StructureAlgorithms.build(cells, width, height, caveRules, rng)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if (x < BiomeCell.BORDER || y < BiomeCell.BORDER ||
                x >= width - BiomeCell.BORDER || y >= height - BiomeCell.BORDER
            ) {
                cells[i] = BiomeCell.SOLID
            }
            if (forceAir[i]) cells[i] = BiomeCell.AIR
        }
        val protectedCells = BooleanArray(cells.size) { preserved[it] || forceAir[it] }
        StructureAlgorithms.refine(cells, width, height, caveRules, protectedCells)
        GridConnectivity.ensureConnected(cells, width, height, preserved, rng) { it == BiomeCell.AIR }
        val dressed = TileDresser.dress(cells, width, height, preserved, originalWords, originalBts, profile, caveRules, rng)
        dressed.words.copyInto(words)
        dressed.bts.copyInto(bts)
    }

    private fun analyze(width: Int, height: Int, originalWords: IntArray, originalBts: IntArray): WfcModel {
        val fallbackSolid = profile.pickSolid(0xFF, Random(seed + 0x51C0FFEEL))
        val airKey = pack(profile.airWord, 0)
        val solidKey = pack(fallbackSolid, 0)
        val counts = HashMap<Int, Int>()
        val rightEdges = HashSet<Long>()
        val leftEdges = HashSet<Long>()
        val downEdges = HashSet<Long>()
        val upEdges = HashSet<Long>()
        val sourceSamples = if (samples.isEmpty()) {
            listOf(WfcSample(width, height, originalWords, originalBts))
        } else {
            samples
        }

        for (sample in sourceSamples) {
            if (sample.width <= 0 || sample.height <= 0) continue
            val n = sample.width * sample.height
            if (sample.words.size < n || sample.bts.size < n) continue
            fun sampleKey(x: Int, y: Int): Int? {
                val i = y * sample.width + x
                return acceptedKey(sample.words[i], sample.bts[i])
            }
            for (y in 0 until sample.height) {
                for (x in 0 until sample.width) {
                    val key = sampleKey(x, y) ?: continue
                    counts.merge(key, 1, Int::plus)
                    if (x + 1 < sample.width) {
                        val next = sampleKey(x + 1, y)
                        if (next != null) {
                            rightEdges.add(edge(key, next))
                            leftEdges.add(edge(next, key))
                        }
                    }
                    if (y + 1 < sample.height) {
                        val next = sampleKey(x, y + 1)
                        if (next != null) {
                            downEdges.add(edge(key, next))
                            upEdges.add(edge(next, key))
                        }
                    }
                }
            }
        }

        counts.merge(airKey, 24, Int::plus)
        counts.merge(solidKey, 24, Int::plus)

        val kept = counts.entries
            .sortedByDescending { it.value }
            .take(MAX_STATES)
            .map { it.key }
            .toMutableSet()
        kept.add(airKey)
        kept.add(solidKey)

        val keys = kept.sortedWith(compareByDescending<Int> { counts[it] ?: 0 }.thenBy { it }).toIntArray()
        val indexByKey = keys.indices.associateBy { keys[it] }
        val weights = IntArray(keys.size) { counts[keys[it]] ?: 1 }
        val right = Array(keys.size) { BooleanArray(keys.size) }
        val left = Array(keys.size) { BooleanArray(keys.size) }
        val down = Array(keys.size) { BooleanArray(keys.size) }
        val up = Array(keys.size) { BooleanArray(keys.size) }

        fun applyEdges(edges: Set<Long>, target: Array<BooleanArray>) {
            for (e in edges) {
                val from = indexByKey[(e shr 32).toInt()] ?: continue
                val to = indexByKey[e.toInt()] ?: continue
                target[from][to] = true
            }
        }
        applyEdges(rightEdges, right)
        applyEdges(leftEdges, left)
        applyEdges(downEdges, down)
        applyEdges(upEdges, up)

        for (i in keys.indices) {
            right[i][i] = true
            left[i][i] = true
            down[i][i] = true
            up[i][i] = true
        }

        val passable = BooleanArray(keys.size) { isGameplayPassableType(typeOf(unpackWord(keys[it]))) }
        val solid = BooleanArray(keys.size) { TilesetProfile.isSolidType(typeOf(unpackWord(keys[it]))) }
        val airIndex = indexByKey[airKey] ?: keys.indices.firstOrNull { passable[it] } ?: 0
        val solidIndex = keys.indices.filter { solid[it] }.maxByOrNull { weights[it] }
            ?: indexByKey[solidKey]
            ?: airIndex
        return WfcModel(keys, weights, indexByKey, right, down, passable, airIndex, solidIndex)
    }

    private fun acceptedKey(word: Int, bts: Int): Int? {
        if (isUnsafeCreWord(word)) return null
        return when (typeOf(word)) {
            0x5, 0x6, 0x9, 0xD -> null
            0xA -> if (options.allowSpikes) pack(word, bts) else null
            0xB -> if (options.allowCrumble) pack(word, bts) else null
            0xC -> if (options.allowMissiles) pack(word, bts) else null
            0xF -> if (options.allowBombs) pack(word, bts) else null
            else -> pack(word, bts)
        }
    }

    private fun chooseState(
        model: WfcModel,
        left: Int,
        above: Int,
        candidates: IntArray,
        rng: Random,
    ): Int {
        var count = collectCandidates(model, left, above, candidates, useLeft = true, useAbove = true)
        if (count == 0) count = collectCandidates(model, left, above, candidates, useLeft = true, useAbove = false)
        if (count == 0) count = collectCandidates(model, left, above, candidates, useLeft = false, useAbove = true)
        if (count == 0) {
            for (i in model.keys.indices) candidates[i] = i
            count = model.keys.size
        }

        var total = 0.0
        for (i in 0 until count) total += adjustedWeight(model, candidates[i], left, above)
        var roll = rng.nextDouble() * total
        for (i in 0 until count) {
            val state = candidates[i]
            roll -= adjustedWeight(model, state, left, above)
            if (roll <= 0.0) return state
        }
        return candidates[count - 1]
    }

    private fun collectCandidates(
        model: WfcModel,
        left: Int,
        above: Int,
        candidates: IntArray,
        useLeft: Boolean,
        useAbove: Boolean,
    ): Int {
        var count = 0
        for (state in model.keys.indices) {
            if (useLeft && left >= 0 && !model.right[left][state]) continue
            if (useAbove && above >= 0 && !model.down[above][state]) continue
            candidates[count++] = state
        }
        return count
    }

    private fun adjustedWeight(model: WfcModel, state: Int, left: Int, above: Int): Double {
        var weight = model.weights[state].toDouble()
        val coherence = options.coherence.coerceIn(0.0, 1.0)
        if (state == left || state == above) weight *= 1.0 + coherence * 3.0
        if (left >= 0 && model.passable[state] == model.passable[left]) weight *= 1.0 + coherence * 0.35
        if (above >= 0 && model.passable[state] == model.passable[above]) weight *= 1.0 + coherence * 0.35
        if (model.passable[state]) weight *= 1.35
        return weight
    }

    private fun replaceUnsafeCreNoise(
        words: IntArray,
        bts: IntArray,
        preserved: BooleanArray,
        model: WfcModel,
        rng: Random,
    ) {
        val safeSolids = model.keys.indices
            .filter { index ->
                val word = unpackWord(model.keys[index])
                TilesetProfile.isSolidType(typeOf(word)) && !isUnsafeCreWord(word)
            }
        if (safeSolids.isEmpty()) return

        fun pickSafeSolid(): Pair<Int, Int> {
            var total = 0
            for (state in safeSolids) total += model.weights[state]
            var roll = rng.nextInt(total.coerceAtLeast(1))
            for (state in safeSolids) {
                roll -= model.weights[state]
                if (roll < 0) {
                    val key = model.keys[state]
                    return unpackWord(key) to unpackBts(key)
                }
            }
            val key = model.keys[safeSolids.first()]
            return unpackWord(key) to unpackBts(key)
        }

        for (i in words.indices) {
            if (preserved[i] || !isUnsafeCreWord(words[i])) continue
            val replacement = pickSafeSolid()
            words[i] = replacement.first
            bts[i] = replacement.second
        }
    }

    private fun stampSampleChunks(
        width: Int,
        height: Int,
        originalWords: IntArray,
        words: IntArray,
        bts: IntArray,
        preserved: BooleanArray,
        forceAir: BooleanArray,
        originalOpen: BooleanArray,
        rng: Random,
    ) {
        val sourceSamples = if (samples.isEmpty()) return else samples.filter {
            it.width >= MIN_CHUNK_SIZE && it.height >= MIN_CHUNK_SIZE &&
                it.words.size >= it.width * it.height &&
                it.bts.size >= it.width * it.height
        }
        if (sourceSamples.isEmpty()) return

        val morph = options.morphAmount.coerceIn(0.0, 1.0)
        val chunkSizes = listOf(12 to 12, 16 to 12, 16 to 16, 24 to 16, 24 to 24, 32 to 16, 32 to 24)
        val stampCount = (width * height / 384.0 * (0.6 + morph * 1.15)).toInt().coerceIn(6, 44)
        repeat(stampCount * 5) { attempt ->
            if (attempt >= stampCount && rng.nextDouble() < 0.55) return@repeat
            val sample = sourceSamples[rng.nextInt(sourceSamples.size)]
            val (cw0, ch0) = chunkSizes[rng.nextInt(chunkSizes.size)]
            val cw = minOf(cw0, sample.width, width - BiomeCell.BORDER * 2)
            val ch = minOf(ch0, sample.height, height - BiomeCell.BORDER * 2)
            if (cw < MIN_CHUNK_SIZE || ch < MIN_CHUNK_SIZE) return@repeat
            val sx = rng.nextInt(0, sample.width - cw + 1)
            val sy = rng.nextInt(0, sample.height - ch + 1)
            val dx = rng.nextInt(BiomeCell.BORDER, width - BiomeCell.BORDER - cw + 1)
            val dy = rng.nextInt(BiomeCell.BORDER, height - BiomeCell.BORDER - ch + 1)
            if (!chunkLooksUseful(sample, sx, sy, cw, ch)) return@repeat

            for (yy in 0 until ch) {
                for (xx in 0 until cw) {
                    val src = (sy + yy) * sample.width + sx + xx
                    val dst = (dy + yy) * width + dx + xx
                    if (preserved[dst] || forceAir[dst]) continue
                    if (acceptedKey(sample.words[src], sample.bts[src]) == null) continue
                    val samplePassable = isGameplayPassableType(typeOf(sample.words[src]))
                    val sampleSolid = !samplePassable
                    if (originalOpen[dst] && sampleSolid && rng.nextDouble() > morph * 0.55) continue
                    if (!originalOpen[dst] &&
                        samplePassable &&
                        !isGameplayPassableType(typeOf(originalWords[dst])) &&
                        rng.nextDouble() > 0.55 + morph * 0.25
                    ) {
                        continue
                    }
                    words[dst] = sample.words[src]
                    bts[dst] = sample.bts[src]
                }
            }
        }
    }

    private fun chunkLooksUseful(sample: WfcSample, sx: Int, sy: Int, cw: Int, ch: Int): Boolean {
        var accepted = 0
        var passable = 0
        var solid = 0
        for (y in sy until sy + ch) {
            for (x in sx until sx + cw) {
                val i = y * sample.width + x
                if (acceptedKey(sample.words[i], sample.bts[i]) == null) continue
                accepted++
                if (isGameplayPassableType(typeOf(sample.words[i]))) passable++ else solid++
            }
        }
        if (accepted < cw * ch * 0.75) return false
        val openFraction = passable.toDouble() / accepted
        return openFraction in 0.10..0.86 && solid > 12
    }

    private fun connectPassable(
        width: Int,
        height: Int,
        originalWords: IntArray,
        originalBts: IntArray,
        words: IntArray,
        bts: IntArray,
        preserved: BooleanArray,
        rng: Random,
        airWord: Int,
        airBts: Int,
    ) {
        val cells = IntArray(words.size) { i ->
            if (isGameplayPassableType(typeOf(words[i])) ||
                (preserved[i] && isGameplayPassableType(typeOf(originalWords[i])))
            ) {
                BiomeCell.AIR
            } else {
                BiomeCell.SOLID
            }
        }

        GridConnectivity.ensureConnectedByIndex(
            cells,
            width,
            height,
            rng,
            tunnelThickness = options.tunnelWidth.coerceIn(1, 4),
            tunnelBendiness = options.tunnelBendiness.coerceIn(0.0, 1.0),
            canCarve = { !preserved[it] },
            passable = { cells[it] == BiomeCell.AIR },
            required = { cells[it] == BiomeCell.AIR },
        )

        for (i in cells.indices) {
            if (cells[i] == BiomeCell.AIR && !preserved[i] && !isGameplayPassableType(typeOf(words[i]))) {
                words[i] = airWord
                bts[i] = airBts
            }
            if (preserved[i]) {
                words[i] = originalWords[i]
                bts[i] = originalBts[i]
            }
        }
    }

    private fun ensureMinimumOpenSpace(
        width: Int,
        height: Int,
        words: IntArray,
        bts: IntArray,
        preserved: BooleanArray,
        rng: Random,
        airWord: Int,
        airBts: Int,
    ) {
        val openFraction = options.openSpaceTarget.coerceIn(MIN_OPEN_FRACTION, MAX_OPEN_FRACTION)
        val target = (words.size * openFraction).toInt().coerceAtLeast(width + height)
        var open = words.count { isGameplayPassableType(typeOf(it)) }
        if (open >= target) return

        val minX = BiomeCell.BORDER + 1
        val maxX = width - BiomeCell.BORDER - 2
        val minY = BiomeCell.BORDER + 1
        val maxY = height - BiomeCell.BORDER - 2
        if (minX > maxX || minY > maxY) return

        repeat(96) {
            if (open >= target) return
            val roomW = rng.nextInt(6, minOf(18, maxX - minX + 1).coerceAtLeast(6) + 1)
            val roomH = rng.nextInt(4, minOf(12, maxY - minY + 1).coerceAtLeast(4) + 1)
            val x0 = rng.nextInt(minX, maxX - roomW + 2)
            val y0 = rng.nextInt(minY, maxY - roomH + 2)
            for (y in y0 until y0 + roomH) {
                for (x in x0 until x0 + roomW) {
                    val i = y * width + x
                    if (preserved[i] || isGameplayPassableType(typeOf(words[i]))) continue
                    words[i] = airWord
                    bts[i] = airBts
                    open++
                }
            }
        }

        repeat(160) {
            if (open >= target) return
            val horizontal = rng.nextBoolean()
            val maxLen = if (horizontal) maxX - minX + 1 else maxY - minY + 1
            val len = rng.nextInt(6, maxOf(7, minOf(maxLen, 24)) + 1)
            val startX = rng.nextInt(minX, maxX + 1)
            val startY = rng.nextInt(minY, maxY + 1)
            for (step in 0 until len) {
                val x = if (horizontal) (startX + step).coerceAtMost(maxX) else startX
                val y = if (horizontal) startY else (startY + step).coerceAtMost(maxY)
                val i = y * width + x
                if (preserved[i] || isGameplayPassableType(typeOf(words[i]))) continue
                words[i] = airWord
                bts[i] = airBts
                open++
            }
        }
    }

    private fun buildOriginalOpenMask(
        width: Int,
        height: Int,
        originalWords: IntArray,
        preserved: BooleanArray,
        forceAir: BooleanArray,
    ): BooleanArray {
        val base = BooleanArray(width * height) { i ->
            forceAir[i] || isGameplayPassableType(typeOf(originalWords[i]))
        }
        val radius = when {
            options.originalSpaceBias >= 0.78 -> 2
            options.originalSpaceBias >= 0.35 -> 1
            else -> 0
        }
        if (radius == 0) return base

        val expanded = base.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                if (!base[i]) continue
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > radius) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 1 until width - 1 || ny !in 1 until height - 1) continue
                        val ni = ny * width + nx
                        if (!preserved[ni]) expanded[ni] = true
                    }
                }
            }
        }
        return expanded
    }

    private fun applyOriginalOpenSpaceBias(
        words: IntArray,
        bts: IntArray,
        preserved: BooleanArray,
        originalOpen: BooleanArray,
        rng: Random,
        airWord: Int,
        airBts: Int,
    ) {
        val bias = options.originalSpaceBias.coerceIn(0.0, 1.0)
        if (bias <= 0.0) return
        val morph = options.morphAmount.coerceIn(0.0, 1.0)
        val keepChance = (bias * (1.0 - morph * 0.25)).coerceIn(0.0, 1.0)
        for (i in words.indices) {
            if (preserved[i] || !originalOpen[i]) continue
            if (rng.nextDouble() > keepChance) continue
            words[i] = airWord
            bts[i] = airBts
        }
    }

    private fun carveDoorApproaches(
        width: Int,
        height: Int,
        originalWords: IntArray,
        words: IntArray,
        bts: IntArray,
        preserved: BooleanArray,
        rng: Random,
        airWord: Int,
        airBts: Int,
    ) {
        val groups = buildDoorGroups(originalWords, width, height)
        if (groups.isEmpty()) return
        val thickness = options.tunnelWidth.coerceIn(1, 4)
        val radius = thickness / 2
        val length = (10 + thickness * 2 + options.tunnelBendiness.coerceIn(0.0, 1.0) * 8).toInt()
        val bendiness = options.tunnelBendiness.coerceIn(0.0, 1.0)

        fun carveBrush(cx: Int, cy: Int, halfX: Int, halfY: Int) {
            for (y in cy - halfY..cy + halfY) for (x in cx - halfX..cx + halfX) {
                if (x !in 1 until width - 1 || y !in 1 until height - 1) continue
                val i = y * width + x
                if (preserved[i]) continue
                words[i] = airWord
                bts[i] = airBts
            }
        }

        for (group in groups) {
            if (group.vertical) {
                val dirs = when {
                    group.minX <= 4 -> intArrayOf(1)
                    group.maxX >= width - 5 -> intArrayOf(-1)
                    else -> intArrayOf(1, -1)
                }
                val centerY = (group.minY + group.maxY) / 2
                val halfY = maxOf(radius, minOf(2, (group.maxY - group.minY + 1) / 2))
                for (dir in dirs) {
                    var bend = 0
                    for (step in 0..length) {
                        if (step > 4 && rng.nextDouble() < bendiness * 0.32) {
                            bend = (bend + rng.nextInt(-1, 2)).coerceIn(-5, 5)
                        }
                        val x = if (dir > 0) group.maxX + 1 + step else group.minX - 1 - step
                        if (x !in 1 until width - 1) break
                        carveBrush(x, (centerY + bend).coerceIn(1, height - 2), radius, halfY)
                    }
                }
            } else {
                val dirs = when {
                    group.minY <= 4 -> intArrayOf(1)
                    group.maxY >= height - 5 -> intArrayOf(-1)
                    else -> intArrayOf(1, -1)
                }
                val centerX = (group.minX + group.maxX) / 2
                val halfX = maxOf(radius, minOf(2, (group.maxX - group.minX + 1) / 2))
                for (dir in dirs) {
                    var bend = 0
                    for (step in 0..length) {
                        if (step > 4 && rng.nextDouble() < bendiness * 0.32) {
                            bend = (bend + rng.nextInt(-1, 2)).coerceIn(-5, 5)
                        }
                        val y = if (dir > 0) group.maxY + 1 + step else group.minY - 1 - step
                        if (y !in 1 until height - 1) break
                        carveBrush((centerX + bend).coerceIn(1, width - 2), y, halfX, radius)
                    }
                }
            }
        }
    }

    private data class DoorSeed(val x: Int, val y: Int)

    private data class WfcDoorGroup(
        val cells: List<Int>,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
    ) {
        val vertical: Boolean get() = maxX - minX < maxY - minY + 2
    }

    private fun buildDoorGroups(originalWords: IntArray, width: Int, height: Int): List<WfcDoorGroup> {
        val remaining = originalWords.indices
            .filter { typeOf(originalWords[it]) == 0x9 }
            .toMutableSet()
        val groups = ArrayList<WfcDoorGroup>()
        while (remaining.isNotEmpty()) {
            val start = remaining.first()
            remaining.remove(start)
            val cells = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                cells.add(c)
                val cx = c % width
                val cy = c / width
                for ((dx, dy) in CARDINALS) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val ni = ny * width + nx
                    if (remaining.remove(ni)) queue.add(ni)
                }
            }
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            for (cell in cells) {
                minX = minOf(minX, cell % width)
                maxX = maxOf(maxX, cell % width)
                minY = minOf(minY, cell / width)
                maxY = maxOf(maxY, cell / width)
            }
            groups.add(WfcDoorGroup(cells, minX, minY, maxX, maxY))
        }
        return groups
    }

    private data class WfcModel(
        val keys: IntArray,
        val weights: IntArray,
        val indexByKey: Map<Int, Int>,
        val right: Array<BooleanArray>,
        val down: Array<BooleanArray>,
        val passable: BooleanArray,
        val airIndex: Int,
        val solidIndex: Int,
    )

    private companion object {
        private const val MAX_STATES = 384
        private const val MIN_CHUNK_SIZE = 8
        private const val MIN_OPEN_FRACTION = 0.28
        private const val MAX_OPEN_FRACTION = 0.56
        private const val CRE_METATILE_LIMIT = 0x100
        private val CARDINALS = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)
        private val AIR_TYPES = setOf(0x0, 0x2, 0x4, 0x7)

        fun pack(word: Int, bts: Int): Int = ((word and 0xFFFF) shl 8) or (bts and 0xFF)
        fun unpackWord(key: Int): Int = (key ushr 8) and 0xFFFF
        fun unpackBts(key: Int): Int = key and 0xFF
        fun typeOf(word: Int): Int = (word ushr 12) and 0xF
        fun edge(from: Int, to: Int): Long = (from.toLong() shl 32) or (to.toLong() and 0xFFFFFFFFL)

        fun isUnsafeCreWord(word: Int): Boolean {
            val type = typeOf(word)
            val metatile = word and 0x3FF
            if (type == 0x0 || type == 0x2 || type == 0x4 || type == 0x7) return false
            return metatile < CRE_METATILE_LIMIT
        }

        fun isGameplayPassableType(type: Int): Boolean =
            type == 0x0 || type == 0x2 || type == 0x4 || type == 0x7 ||
                type == 0x9 || type == 0xB || type == 0xC || type == 0xF
    }
}

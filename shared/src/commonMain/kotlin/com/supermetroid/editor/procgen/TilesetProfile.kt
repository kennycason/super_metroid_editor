package com.supermetroid.editor.procgen

import com.supermetroid.editor.data.Room
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import kotlin.random.Random

/**
 * Learned tile vocabulary for one tileset, sampled from every vanilla room
 * that uses it. Maps structural roles (solid interior, floor top, corners,
 * spikes, crumble blocks...) to weighted block-word candidates so generated
 * rooms are dressed with the tiles the tileset actually uses in those roles.
 *
 * Solid tiles are keyed by an 8-bit neighbor-solidity mask
 * (bit 0=N, 1=S, 2=W, 3=E, 4=NW, 5=NE, 6=SW, 7=SE; 1 = solid neighbor),
 * with graceful fallback exact mask → 4-way mask → most common solid.
 *
 * A block word is the ROM's 16-bit layer-1 value:
 * bits 0-9 metatile, 10 h-flip, 11 v-flip, 12-15 block type.
 */
class TilesetProfile private constructor(
    private val solidByMask: Map<Int, WeightedWords>,
    private val solidByMask4: Map<Int, WeightedWords>,
    private val solidFallback: Int,
    val airWord: Int,
    private val spikes: WeightedWords,
    private val crumbles: WeightedPairs,
    private val hiddenShots: WeightedPairs,
    private val bombs: WeightedPairs,
    /** Rooms sampled during learning; 0 means fallback-only profile. */
    val roomsSampled: Int,
) {
    /** Pick a solid block word appropriate for the given neighbor mask. */
    fun pickSolid(mask8: Int, rng: Random): Int {
        solidByMask[mask8]?.let { return it.pick(rng) }
        solidByMask4[mask8 and 0xF]?.let { return it.pick(rng) }
        return solidFallback
    }

    fun pickSpike(rng: Random): Int = spikes.pickOr(rng, (0xA shl 12) or CRE_SPIKE)
    fun pickCrumble(rng: Random): Pair<Int, Int> = crumbles.pickOr(rng, ((0xB shl 12) or CRE_CRUMBLE) to 0x00)
    fun pickHiddenShot(rng: Random): Pair<Int, Int> = hiddenShots.pickOr(rng, ((0xC shl 12) or CRE_SHOT_HIDDEN) to 0x04)
    fun pickBomb(rng: Random): Pair<Int, Int> = bombs.pickOr(rng, ((0xF shl 12) or CRE_BOMB) to 0x00)

    /** Weighted multiset of block words. */
    private class WeightedWords(val counts: Map<Int, Int>) {
        val total = counts.values.sum()
        fun pick(rng: Random): Int {
            var roll = rng.nextInt(total)
            for ((word, count) in counts) {
                roll -= count
                if (roll < 0) return word
            }
            return counts.keys.first()
        }
        fun pickOr(rng: Random, fallback: Int): Int = if (total > 0) pick(rng) else fallback
    }

    /** Weighted multiset of (block word, BTS) pairs. */
    private class WeightedPairs(val counts: Map<Pair<Int, Int>, Int>) {
        val total = counts.values.sum()
        fun pickOr(rng: Random, fallback: Pair<Int, Int>): Pair<Int, Int> {
            if (total == 0) return fallback
            var roll = rng.nextInt(total)
            for ((pair, count) in counts) {
                roll -= count
                if (roll < 0) return pair
            }
            return fallback
        }
    }

    companion object {
        // CRE metatile indices (valid 0-255 slots for tilesets with CRE prepended)
        // used when a tileset's vanilla rooms never use a special block type.
        private const val CRE_SPIKE = 86
        private const val CRE_CRUMBLE = 188
        private const val CRE_SHOT_HIDDEN = 84
        private const val CRE_BOMB = 88

        /** Block types that read as solid ground for structure classification. */
        fun isSolidType(type: Int): Boolean = when (type) {
            0x1, 0x3, 0x8, 0xA, 0xB, 0xC, 0xE, 0xF -> true
            else -> false // 0/2/4/7 air variants, 9 door; 5/D resolved by caller
        }

        /**
         * Learn tile vocabulary for [tilesetId] from every room in [rooms] that
         * uses that tileset.
         */
        fun learn(romParser: RomParser, rooms: List<Room>, tilesetId: Int): TilesetProfile {
            // Words referencing undefined or placeholder "X" metatiles render
            // as junk (vanilla hides them behind layer 2) — skip them.
            val tileGraphics = TileGraphics(romParser).takeIf { it.loadTileset(tilesetId) }
            val solidByMask = HashMap<Int, HashMap<Int, Int>>()
            val solidByMask4 = HashMap<Int, HashMap<Int, Int>>()
            val solidAll = HashMap<Int, Int>()
            val airCounts = HashMap<Int, Int>()
            val spikeCounts = HashMap<Int, Int>()
            val crumbleCounts = HashMap<Pair<Int, Int>, Int>()
            val hiddenShotCounts = HashMap<Pair<Int, Int>, Int>()
            val bombCounts = HashMap<Pair<Int, Int>, Int>()
            var sampled = 0

            for (room in rooms) {
                if (room.tileset != tilesetId || room.levelDataPtr == 0) continue
                val levelData = try {
                    romParser.decompressLZ2(room.levelDataPtr)
                } catch (_: Exception) {
                    continue
                }
                val grid = LevelGrid.parse(levelData, room.width * 16, room.height * 16) ?: continue
                sampled++

                val w = grid.width
                val h = grid.height
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val word = grid.word(x, y)
                        if (tileGraphics != null && tileGraphics.isPlaceholderMetatile(word and 0x3FF)) continue
                        val bts = grid.bts(x, y)
                        when (grid.resolvedType(x, y)) {
                            0x0 -> airCounts.merge(word, 1, Int::plus)
                            0x8 -> {
                                val mask = NeighborMask.fromPredicate(w, h, x, y) { nx, ny ->
                                    isSolidType(grid.resolvedType(nx, ny))
                                }
                                solidByMask.getOrPut(mask) { HashMap() }.merge(word, 1, Int::plus)
                                solidByMask4.getOrPut(mask and 0xF) { HashMap() }.merge(word, 1, Int::plus)
                                solidAll.merge(word, 1, Int::plus)
                            }
                            0xA -> if (bts == 0) spikeCounts.merge(word, 1, Int::plus)
                            0xB -> if (bts <= 0x0F) crumbleCounts.merge(word to bts, 1, Int::plus)
                            0xC -> if (bts == 0x04) hiddenShotCounts.merge(word to bts, 1, Int::plus)
                            0xF -> if (bts == 0) bombCounts.merge(word to bts, 1, Int::plus)
                        }
                    }
                }
            }

            val solidFallback = solidAll.maxByOrNull { it.value }?.key ?: ((0x8 shl 12) or 0)
            val airWord = airCounts.maxByOrNull { it.value }?.key ?: 0x00FF
            // Drop rare one-off words (lamps, crates, ship fittings) from each
            // bucket so sampled terrain doesn't get speckled with decorations.
            fun trim(counts: Map<Int, Int>): Map<Int, Int> {
                val total = counts.values.sum()
                if (total < 20) return counts
                val cutoff = maxOf(2, (total * 0.05).toInt())
                val kept = counts.filterValues { it >= cutoff }.ifEmpty { counts }
                val limit = if (total >= 60) 8 else kept.size
                return kept.entries
                    .sortedByDescending { it.value }
                    .take(limit)
                    .associate { it.key to it.value }
            }
            return TilesetProfile(
                solidByMask = solidByMask.mapValues { WeightedWords(trim(it.value)) },
                solidByMask4 = solidByMask4.mapValues { WeightedWords(trim(it.value)) },
                solidFallback = solidFallback,
                airWord = airWord,
                spikes = WeightedWords(spikeCounts),
                crumbles = WeightedPairs(crumbleCounts),
                hiddenShots = WeightedPairs(hiddenShotCounts),
                bombs = WeightedPairs(bombCounts),
                roomsSampled = sampled,
            )
        }

        /** Minimal profile for tests / no-ROM contexts: one word per role. */
        fun synthetic(): TilesetProfile =
            TilesetProfile(
                solidByMask = emptyMap(),
                solidByMask4 = emptyMap(),
                solidFallback = (0x8 shl 12) or 0x120,
                airWord = 0x00FF,
                spikes = WeightedWords(emptyMap()),
                crumbles = WeightedPairs(emptyMap()),
                hiddenShots = WeightedPairs(emptyMap()),
                bombs = WeightedPairs(emptyMap()),
                roomsSampled = 0,
            )
    }
}

package com.supermetroid.editor.procgen

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LearnedRoomProposalTest {
    private val width = 32
    private val height = 16
    private val size = width * height
    private val air = 0x00FF
    private val solid = 0x8120

    private fun room(): Pair<IntArray, IntArray> {
        val words = IntArray(size) { air }
        val bts = IntArray(size)
        for (y in 0 until height) for (x in 0 until width) {
            if (x <= 1 || x >= width - 2 || y <= 1 || y >= height - 2) {
                words[y * width + x] = solid
            }
        }
        for (y in 6..9) words[y * width + 1] = 0x9040
        return words to bts
    }

    private fun proposal(words: IntArray, bts: IntArray = IntArray(size)) = LearnedRoomProposal(
        schemaVersion = 1,
        kind = "smedit-room-proposal",
        generator = LearnedRoomProposalGenerator(seed = 1234),
        source = LearnedRoomProposalSource(roomId = 0x91F8, roomIdHex = "0x91F8"),
        tileset = 8,
        widthScreens = 2,
        heightScreens = 1,
        widthBlocks = width,
        heightBlocks = height,
        layer1Words = words.toList(),
        blockTypes = words.map { (it ushr 12) and 0xF },
        resolvedBlockTypes = resolve(words).toList(),
        bts = bts.toList(),
    )

    @Test
    fun `processor restores door fixtures and metadata protected cells`() {
        val (originalWords, originalBts) = room()
        val proposed = IntArray(size) { solid }
        val proposedBts = IntArray(size) { 0x7F }
        val protectedIndex = 8 * width + 20
        originalWords[protectedIndex] = 0x1123
        originalBts[protectedIndex] = 0x1B
        val protected = BooleanArray(size).also { it[protectedIndex] = true }

        val prepared = LearnedRoomProposalProcessor.prepare(
            proposal(proposed, proposedBts),
            expectedRoomId = 0x91F8,
            expectedTileset = 8,
            originalWords = originalWords,
            originalBts = originalBts,
            width = width,
            height = height,
            options = BiomeGenerationOptions(protectedCells = protected),
        )

        assertEquals(originalWords[protectedIndex], prepared.words[protectedIndex])
        assertEquals(originalBts[protectedIndex], prepared.bts[protectedIndex])
        for (y in 6..9) assertEquals(originalWords[y * width + 1], prepared.words[y * width + 1])
        assertTrue(prepared.protectedCellCount > 4)
    }

    @Test
    fun `processor removes interior generated doors and connects open regions`() {
        val (originalWords, originalBts) = room()
        val proposed = originalWords.copyOf()
        for (y in 2 until height - 2) proposed[y * width + 16] = solid
        proposed[6 * width + 12] = 0x9040
        proposed[7 * width + 12] = 0x9040

        val prepared = LearnedRoomProposalProcessor.prepare(
            proposal(proposed),
            expectedRoomId = 0x91F8,
            expectedTileset = 8,
            originalWords = originalWords,
            originalBts = originalBts,
            width = width,
            height = height,
        )

        assertEquals(1, prepared.removedInvalidDoorGroups)
        assertTrue((prepared.words[6 * width + 12] ushr 12) != 0x9)
        assertEquals(1, prepared.metrics.passableComponents)
        assertEquals(1.0, prepared.metrics.doorReachableFraction)
        assertTrue(prepared.repairChangeCount > 0)
    }

    @Test
    fun `processor redresses changed plain terrain with the live tileset profile`() {
        val (originalWords, originalBts) = room()
        val proposed = originalWords.copyOf()
        val changed = 8 * width + 10
        proposed[changed] = 0x8999

        val prepared = LearnedRoomProposalProcessor.prepare(
            proposal = proposal(proposed),
            expectedRoomId = 0x91F8,
            expectedTileset = 8,
            originalWords = originalWords,
            originalBts = originalBts,
            width = width,
            height = height,
            tilesetProfile = TilesetProfile.synthetic(),
        )

        assertEquals(0x8120, prepared.words[changed])
        assertEquals(0, prepared.bts[changed])
    }

    @Test
    fun `processor rejects a proposal for a different room`() {
        val (words, bts) = room()
        val error = assertFailsWith<IllegalArgumentException> {
            LearnedRoomProposalProcessor.prepare(
                proposal(words, bts),
                expectedRoomId = 0x92FD,
                expectedTileset = 8,
                originalWords = words,
                originalBts = bts,
                width = width,
                height = height,
            )
        }
        assertTrue(error.message.orEmpty().contains("not 0x92FD"))
    }

    @Test
    fun `codec accepts a ranked proposal bundle`() {
        val cells = List(256) { if (it < 16) 33056 else 255 }
        val bts = List(256) { 0 }
        val candidate = """
            {
              "schemaVersion": 1,
              "kind": "smedit-room-proposal",
              "source": {"roomId": 37368},
              "tileset": 8,
              "widthScreens": 1,
              "heightScreens": 1,
              "widthBlocks": 16,
              "heightBlocks": 16,
              "layer1Words": $cells,
              "bts": $bts,
              "rank": 1
            }
        """.trimIndent()
        val bundle = """
            {"schemaVersion":1,"kind":"smedit-room-proposal-bundle","candidates":[$candidate]}
        """.trimIndent()

        val decoded = LearnedRoomProposalCodec.decode(bundle)

        assertEquals(1, decoded.size)
        assertEquals(1, decoded.single().rank)
        assertEquals(0x91F8, decoded.single().source.roomId)
    }

    private fun resolve(words: IntArray): IntArray = IntArray(words.size) { index ->
        var cursor = index
        var resolved = (words[index] ushr 12) and 0xF
        repeat(32) {
            resolved = (words[cursor] ushr 12) and 0xF
            when (resolved) {
                0x5 -> if (cursor % width > 0) cursor-- else return@repeat
                0xD -> if (cursor >= width) cursor -= width else return@repeat
                else -> return@IntArray resolved
            }
        }
        resolved
    }
}

package com.supermetroid.editor.procgen

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.random.Random

const val LEARNED_ROOM_PROPOSAL_SCHEMA_VERSION = 1

@Serializable
data class LearnedRoomProposalGenerator(
    val modelFormat: String = "",
    val modelFormatVersion: Int = 0,
    val modelSha256: String = "",
    val mode: String = "",
    val strength: Double = 0.0,
    val seed: Long = 0,
    val steps: Int = 0,
    val temperature: Double = 0.0,
    val preservedExistingDoors: Boolean = true,
    val structureSmoothing: Boolean = false,
    val contextRetiling: Boolean = false,
    val allowedNewDoors: Boolean = false,
    val targetOpenFraction: Double? = null,
)

@Serializable
data class LearnedRoomProposalSource(
    val roomId: Int,
    val roomIdHex: String = "",
    val handle: String = "",
    val name: String = "",
    val contentHash: String = "",
)

@Serializable
data class LearnedRoomDoorGroup(
    val edge: String = "interior",
    val orientation: String = "vertical",
    val cellIndices: List<Int> = emptyList(),
)

@Serializable
data class LearnedRoomProposalMetrics(
    val openCellFraction: Double = 0.0,
    val passableComponents: Int = 0,
    val largestPassableComponentFraction: Double = 0.0,
    val doorGroupCount: Int = 0,
    val doorReachableFraction: Double = 0.0,
    val repairChangeCount: Int = 0,
    val passabilityTransitionFraction: Double = 0.0,
    val isolatedCellFraction: Double = 0.0,
    val smallSolidIslandFraction: Double = 0.0,
    val detailAdjacencyFraction: Double = 0.0,
    val detailAdjacencyRetention: Double = 0.0,
    val structureCoherence: Double = 0.0,
    val score: Double = 0.0,
)

@Serializable
data class LearnedRoomProposal(
    val schemaVersion: Int,
    val kind: String,
    val generator: LearnedRoomProposalGenerator = LearnedRoomProposalGenerator(),
    val source: LearnedRoomProposalSource,
    val area: Int = 0,
    val areaName: String = "",
    val tileset: Int,
    val widthScreens: Int,
    val heightScreens: Int,
    val widthBlocks: Int,
    val heightBlocks: Int,
    val layer1Words: List<Int>,
    val blockTypes: List<Int> = emptyList(),
    val resolvedBlockTypes: List<Int> = emptyList(),
    val bts: List<Int>,
    val generatedCellIndices: List<Int> = emptyList(),
    val changedCellIndices: List<Int> = emptyList(),
    val doorGroups: List<LearnedRoomDoorGroup> = emptyList(),
    val metrics: LearnedRoomProposalMetrics = LearnedRoomProposalMetrics(),
    val rank: Int = 0,
)

@Serializable
data class LearnedRoomProposalBundle(
    val schemaVersion: Int,
    val kind: String,
    val candidates: List<LearnedRoomProposal>,
)

/** Decode either a legacy single proposal or a ranked multi-candidate bundle. */
object LearnedRoomProposalCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): List<LearnedRoomProposal> {
        val element = json.parseToJsonElement(text)
        val kind = element.jsonObject["kind"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Learned room JSON has no kind")
        val proposals = when (kind) {
            "smedit-room-proposal" -> listOf(json.decodeFromJsonElement<LearnedRoomProposal>(element))
            "smedit-room-proposal-bundle" -> {
                val bundle = json.decodeFromJsonElement<LearnedRoomProposalBundle>(element)
                require(bundle.schemaVersion == LEARNED_ROOM_PROPOSAL_SCHEMA_VERSION) {
                    "Unsupported proposal bundle schema ${bundle.schemaVersion}"
                }
                bundle.candidates
            }
            else -> throw IllegalArgumentException("Unsupported learned room JSON kind: $kind")
        }
        require(proposals.isNotEmpty()) { "Learned room JSON contains no candidates" }
        require(proposals.size <= MAX_CANDIDATES) {
            "Learned room JSON contains ${proposals.size} candidates; maximum is $MAX_CANDIDATES"
        }
        return proposals
    }

    private const val MAX_CANDIDATES = 32
}

data class LearnedRoomLayoutMetrics(
    val openCellFraction: Double,
    val passableComponents: Int,
    val largestPassableComponentFraction: Double,
    val doorGroupCount: Int,
    val addedDoorGroupCount: Int,
    val doorReachableFraction: Double,
    val passabilityTransitionFraction: Double,
    val isolatedCellFraction: Double,
    val smallSolidIslandFraction: Double,
    val detailAdjacencyFraction: Double,
)

data class PreparedLearnedRoomCandidate(
    val proposal: LearnedRoomProposal,
    val words: IntArray,
    val bts: IntArray,
    val resolvedBlockTypes: IntArray,
    val baseWords: IntArray,
    val baseBts: IntArray,
    val changedCellCount: Int,
    val repairChangeCount: Int,
    val protectedCellCount: Int,
    val removedInvalidDoorGroups: Int,
    val metrics: LearnedRoomLayoutMetrics,
    val score: Double,
    val warnings: List<String>,
)

/**
 * Editor-side safety boundary for learned output. It validates the proposal,
 * restores room fixtures and metadata-sensitive cells, removes unusable
 * generated doors, opens door approaches, and connects passable components.
 */
object LearnedRoomProposalProcessor {
    fun prepare(
        proposal: LearnedRoomProposal,
        expectedRoomId: Int,
        expectedTileset: Int,
        originalWords: IntArray,
        originalBts: IntArray,
        width: Int,
        height: Int,
        options: BiomeGenerationOptions = BiomeGenerationOptions(),
        tilesetProfile: TilesetProfile? = null,
    ): PreparedLearnedRoomCandidate {
        validate(proposal, expectedRoomId, expectedTileset, width, height)
        val size = width * height
        require(originalWords.size == size && originalBts.size == size) {
            "Current room grid does not match ${width}x$height"
        }

        val proposedWords = proposal.layer1Words.toIntArray()
        val proposedBts = proposal.bts.toIntArray()
        val words = proposedWords.copyOf()
        val bts = proposedBts.copyOf()
        val airWord = chooseAirWord(originalWords, originalBts, proposedWords, proposedBts)
        val originalDoorSetup = DoorPreservation.setup(originalWords, originalBts, width, height)
        val preserved = originalDoorSetup.preserved.copyOf()
        val forceAir = originalDoorSetup.forceAir.copyOf()

        applyRects(preserved, width, height, options.preserveRects)
        applyRects(forceAir, width, height, options.forceAirRects)
        options.protectedCells?.let { mask ->
            require(mask.size >= size) { "Protected cell mask is smaller than the room" }
            for (i in 0 until size) if (mask[i]) {
                preserved[i] = true
                forceAir[i] = false
            }
        }
        if (options.hardForceAirRects.isNotEmpty()) {
            val hardForceAir = BooleanArray(size)
            applyRects(hardForceAir, width, height, options.hardForceAirRects)
            for (i in 0 until size) {
                if (hardForceAir[i] && blockType(originalWords[i]) != DOOR_TYPE) {
                    preserved[i] = false
                    forceAir[i] = true
                }
            }
        }

        for (i in 0 until size) {
            if (preserved[i]) {
                words[i] = originalWords[i]
                bts[i] = originalBts[i]
            }
        }
        for (i in 0 until size) {
            if (forceAir[i] && !preserved[i] && blockType(words[i]) != DOOR_TYPE) {
                words[i] = airWord
                bts[i] = 0
            }
        }

        val originalDoorCells = originalWords.indices.filterTo(mutableSetOf()) {
            blockType(originalWords[it]) == DOOR_TYPE
        }
        var removedInvalidDoorGroups = 0
        var candidateDoorGroups = findDoorGroups(words, width, height)
        for (group in candidateDoorGroups) {
            if (group.cells.any { it in originalDoorCells }) continue
            if (!group.isUsableGeneratedDoor()) {
                removedInvalidDoorGroups++
                for (i in group.cells) {
                    if (!preserved[i]) {
                        words[i] = airWord
                        bts[i] = 0
                    }
                }
            }
        }

        candidateDoorGroups = findDoorGroups(words, width, height)
        for (group in candidateDoorGroups) {
            carveDoorApproach(group, words, bts, preserved, airWord, width, height)
        }

        val connectivityCarves = connectPassableRegions(
            words = words,
            bts = bts,
            preserved = preserved,
            airWord = airWord,
            width = width,
            height = height,
        )
        if (tilesetProfile != null) {
            dressPlainTerrainWithLiveTileset(
                words = words,
                bts = bts,
                originalWords = originalWords,
                preserved = preserved,
                width = width,
                height = height,
                profile = tilesetProfile,
                seed = proposal.generator.seed,
            )
        }

        // A connectivity tunnel may pass near a protected fixture. Restore it
        // once more so learned output can never overwrite editor-owned state.
        for (i in 0 until size) {
            if (preserved[i]) {
                words[i] = originalWords[i]
                bts[i] = originalBts[i]
            }
        }

        val repairChanges = words.indices.count {
            words[it] != proposedWords[it] || bts[it] != proposedBts[it]
        }
        val changed = words.indices.count {
            words[it] != originalWords[it] || bts[it] != originalBts[it]
        }
        val resolved = resolveBlockTypes(words, width)
        val finalDoorGroups = findDoorGroups(words, width, height)
        val originalDoorGroups = findDoorGroups(originalWords, width, height)
        val originalGroupCount = originalDoorGroups.size
        val changedMask = BooleanArray(size) {
            words[it] != originalWords[it] || bts[it] != originalBts[it]
        }
        val detailGrammar = detailGrammar(originalWords, originalBts, width, height)
        val metrics = layoutMetrics(
            words,
            bts,
            resolved,
            finalDoorGroups,
            originalGroupCount,
            width,
            height,
            detailGrammar,
            changedMask,
        )
        val originalMetrics = layoutMetrics(
            originalWords,
            originalBts,
            resolveBlockTypes(originalWords, width),
            originalDoorGroups,
            originalGroupCount,
            width,
            height,
            detailGrammar,
            changedMask,
        )
        val score = score(
            metrics,
            originalMetrics,
            changed.toDouble() / size,
            repairChanges.toDouble() / size,
            removedInvalidDoorGroups,
        )
        val warnings = buildList {
            if (metrics.passableComponents > 1) {
                add("${metrics.passableComponents} passable regions remain after repair")
            }
            if (metrics.doorReachableFraction < 1.0) add("Not every door reaches the main passable region")
            if (removedInvalidDoorGroups > 0) add("Removed $removedInvalidDoorGroups interior or malformed generated door group(s)")
            if (metrics.addedDoorGroupCount > 0) {
                add("${metrics.addedDoorGroupCount} added door group(s) still need door PLMs, covers, and destinations")
            }
            if (connectivityCarves > size / 20) add("Connectivity repair carved $connectivityCarves cells")
            if (metrics.detailAdjacencyFraction < 0.90) {
                add("Only ${(metrics.detailAdjacencyFraction * 100).toInt()}% of changed tile edges match source-room patterns")
            }
        }
        return PreparedLearnedRoomCandidate(
            proposal = proposal,
            words = words,
            bts = bts,
            resolvedBlockTypes = resolved,
            baseWords = originalWords.copyOf(),
            baseBts = originalBts.copyOf(),
            changedCellCount = changed,
            repairChangeCount = repairChanges,
            protectedCellCount = preserved.count { it },
            removedInvalidDoorGroups = removedInvalidDoorGroups,
            metrics = metrics,
            score = score,
            warnings = warnings,
        )
    }

    private fun validate(
        proposal: LearnedRoomProposal,
        expectedRoomId: Int,
        expectedTileset: Int,
        width: Int,
        height: Int,
    ) {
        require(proposal.schemaVersion == LEARNED_ROOM_PROPOSAL_SCHEMA_VERSION) {
            "Unsupported proposal schema ${proposal.schemaVersion}"
        }
        require(proposal.kind == "smedit-room-proposal") { "Unexpected candidate kind ${proposal.kind}" }
        require(proposal.source.roomId == expectedRoomId) {
            "Proposal is for room ${proposal.source.roomIdHex.ifBlank { proposal.source.roomId.toString() }}, not 0x${expectedRoomId.toString(16).uppercase()}"
        }
        require(proposal.tileset == expectedTileset) {
            "Proposal tileset ${proposal.tileset} does not match current tileset $expectedTileset"
        }
        require(proposal.widthBlocks == width && proposal.heightBlocks == height) {
            "Proposal is ${proposal.widthBlocks}x${proposal.heightBlocks}; current room is ${width}x$height"
        }
        require(proposal.widthScreens * 16 == width && proposal.heightScreens * 16 == height) {
            "Proposal screen dimensions do not match its block dimensions"
        }
        val size = width * height
        require(proposal.layer1Words.size == size) { "Proposal layer1Words has ${proposal.layer1Words.size} cells; expected $size" }
        require(proposal.bts.size == size) { "Proposal bts has ${proposal.bts.size} cells; expected $size" }
        require(proposal.layer1Words.all { it in 0..0xFFFF }) { "Proposal contains a layer-1 word outside 0..65535" }
        require(proposal.bts.all { it in 0..0xFF }) { "Proposal contains BTS outside 0..255" }
        if (proposal.blockTypes.isNotEmpty()) {
            require(proposal.blockTypes.size == size) { "Proposal blockTypes has the wrong size" }
            require(proposal.blockTypes.indices.all { proposal.blockTypes[it] == blockType(proposal.layer1Words[it]) }) {
                "Proposal blockTypes do not match layer1Words"
            }
        }
        if (proposal.resolvedBlockTypes.isNotEmpty()) {
            require(proposal.resolvedBlockTypes.size == size) { "Proposal resolvedBlockTypes has the wrong size" }
            val resolved = resolveBlockTypes(proposal.layer1Words.toIntArray(), width)
            require(resolved.indices.all { proposal.resolvedBlockTypes[it] == resolved[it] }) {
                "Proposal resolvedBlockTypes do not match layer1Words"
            }
        }
    }

    private fun chooseAirWord(
        originalWords: IntArray,
        originalBts: IntArray,
        proposedWords: IntArray,
        proposedBts: IntArray,
    ): Int {
        fun mostFrequent(words: IntArray, bts: IntArray): Int? = words.indices
            .asSequence()
            .filter { blockType(words[it]) == 0 && bts[it] == 0 }
            .map { words[it] }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
        return mostFrequent(originalWords, originalBts)
            ?: mostFrequent(proposedWords, proposedBts)
            ?: 0x00FF
    }

    private fun layoutMetrics(
        words: IntArray,
        bts: IntArray,
        resolved: IntArray,
        doors: List<DoorGroup>,
        originalDoorGroupCount: Int,
        width: Int,
        height: Int,
        detailGrammar: DetailGrammar,
        relevantCells: BooleanArray,
    ): LearnedRoomLayoutMetrics {
        val labels = IntArray(resolved.size) { -1 }
        val sizes = ArrayList<Int>()
        for (start in resolved.indices) {
            if (!isPassable(resolved[start]) || labels[start] >= 0) continue
            val label = sizes.size
            var count = 0
            val queue = ArrayDeque<Int>()
            queue.add(start)
            labels[start] = label
            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                count++
                val x = cell % width
                val y = cell / width
                for ((dx, dy) in CARDINALS) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val neighbor = ny * width + nx
                    if (isPassable(resolved[neighbor]) && labels[neighbor] < 0) {
                        labels[neighbor] = label
                        queue.add(neighbor)
                    }
                }
            }
            sizes.add(count)
        }
        val allOpenCells = sizes.sum()
        val doorCells = doors.flatMapTo(mutableSetOf()) { it.cells }
        val relevantLabels = sizes.indices.filter { label ->
            sizes[label] >= MIN_GAMEPLAY_COMPONENT_CELLS || doorCells.any { labels[it] == label }
        }
        val relevantOpenCells = relevantLabels.sumOf { sizes[it] }
        val largestLabel = relevantLabels.maxByOrNull { sizes[it] } ?: -1
        val largest = sizes.getOrElse(largestLabel) { 0 }
        val reachableDoors = doors.count { group -> group.cells.any { labels[it] == largestLabel } }
        var transitions = 0
        var edgeCount = 0
        var isolated = 0
        for (i in resolved.indices) {
            val x = i % width
            val y = i / width
            val open = isPassable(resolved[i])
            if (x + 1 < width) {
                edgeCount++
                if (isPassable(resolved[i + 1]) != open) transitions++
            }
            if (y + 1 < height) {
                edgeCount++
                if (isPassable(resolved[i + width]) != open) transitions++
            }
            var neighborCount = 0
            var sameCategory = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                neighborCount++
                if (isPassable(resolved[ny * width + nx]) == open) sameCategory++
            }
            if (neighborCount > 0 && sameCategory <= 1) isolated++
        }

        val solidSeen = BooleanArray(resolved.size)
        var smallSolidCells = 0
        for (start in resolved.indices) {
            if (isPassable(resolved[start]) || solidSeen[start]) continue
            val component = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            solidSeen[start] = true
            queue.add(start)
            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                component.add(cell)
                val x = cell % width
                val y = cell / width
                for ((dx, dy) in CARDINALS) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val neighbor = ny * width + nx
                    if (!isPassable(resolved[neighbor]) && !solidSeen[neighbor]) {
                        solidSeen[neighbor] = true
                        queue.add(neighbor)
                    }
                }
            }
            if (component.size <= SMALL_SOLID_COMPONENT_CELLS) smallSolidCells += component.size
        }

        var matchingDetails = 0
        var consideredDetails = 0
        for (i in words.indices) {
            val x = i % width
            val y = i / width
            val detail = detailPair(words[i], bts[i])
            if (x + 1 < width && (relevantCells[i] || relevantCells[i + 1])) {
                consideredDetails++
                if (packDetailAdjacency(detail, detailPair(words[i + 1], bts[i + 1])) in detailGrammar.horizontal) {
                    matchingDetails++
                }
            }
            if (y + 1 < height && (relevantCells[i] || relevantCells[i + width])) {
                consideredDetails++
                if (packDetailAdjacency(detail, detailPair(words[i + width], bts[i + width])) in detailGrammar.vertical) {
                    matchingDetails++
                }
            }
        }
        return LearnedRoomLayoutMetrics(
            openCellFraction = if (resolved.isEmpty()) 0.0 else allOpenCells.toDouble() / resolved.size,
            passableComponents = relevantLabels.size,
            largestPassableComponentFraction = if (relevantOpenCells == 0) 0.0 else largest.toDouble() / relevantOpenCells,
            doorGroupCount = doors.size,
            addedDoorGroupCount = (doors.size - originalDoorGroupCount).coerceAtLeast(0),
            doorReachableFraction = if (doors.isEmpty()) 1.0 else reachableDoors.toDouble() / doors.size,
            passabilityTransitionFraction = if (edgeCount == 0) 0.0 else transitions.toDouble() / edgeCount,
            isolatedCellFraction = if (resolved.isEmpty()) 0.0 else isolated.toDouble() / resolved.size,
            smallSolidIslandFraction = if (resolved.isEmpty()) 0.0 else smallSolidCells.toDouble() / resolved.size,
            detailAdjacencyFraction = if (consideredDetails == 0) 1.0 else matchingDetails.toDouble() / consideredDetails,
        )
    }

    private fun score(
        metrics: LearnedRoomLayoutMetrics,
        originalMetrics: LearnedRoomLayoutMetrics,
        changedFraction: Double,
        repairFraction: Double,
        invalidDoorGroups: Int,
    ): Double {
        val densityQuality = (
            1.0 - abs(metrics.openCellFraction - originalMetrics.openCellFraction) / 0.5
        ).coerceIn(0.0, 1.0)
        val novelty = (changedFraction / 0.45).coerceIn(0.0, 1.0)
        val detailRetention = if (originalMetrics.detailAdjacencyFraction <= 0.0) {
            1.0
        } else {
            (metrics.detailAdjacencyFraction / originalMetrics.detailAdjacencyFraction).coerceIn(0.0, 1.0)
        }
        val transitionExcess = (
            metrics.passabilityTransitionFraction - originalMetrics.passabilityTransitionFraction
        ).coerceAtLeast(0.0)
        val isolatedExcess = (
            metrics.isolatedCellFraction - originalMetrics.isolatedCellFraction
        ).coerceAtLeast(0.0)
        val islandExcess = (
            metrics.smallSolidIslandFraction - originalMetrics.smallSolidIslandFraction
        ).coerceAtLeast(0.0)
        val structureCoherence = (
            1.0 - (
                transitionExcess / 0.08 +
                    isolatedExcess / 0.03 +
                    islandExcess / 0.025
                ).coerceAtMost(1.0)
            ).coerceIn(0.0, 1.0)
        return (
            metrics.largestPassableComponentFraction * 20.0 +
                (if (metrics.passableComponents <= 1) 8.0 else 0.0) +
                metrics.doorReachableFraction * 20.0 +
                densityQuality * 20.0 +
                novelty * 3.0 +
                detailRetention * detailRetention * 24.0 +
                structureCoherence * structureCoherence * 5.0 -
                repairFraction * 30.0 -
                invalidDoorGroups.coerceAtMost(5) * 4.0
            ).coerceIn(0.0, 100.0)
    }

    private fun detailGrammar(
        words: IntArray,
        bts: IntArray,
        width: Int,
        height: Int,
    ): DetailGrammar {
        val horizontal = HashSet<Long>()
        val vertical = HashSet<Long>()
        for (i in words.indices) {
            val x = i % width
            val y = i / width
            val detail = detailPair(words[i], bts[i])
            if (x + 1 < width) {
                horizontal.add(packDetailAdjacency(detail, detailPair(words[i + 1], bts[i + 1])))
            }
            if (y + 1 < height) {
                vertical.add(packDetailAdjacency(detail, detailPair(words[i + width], bts[i + width])))
            }
        }
        return DetailGrammar(horizontal, vertical)
    }

    private fun detailPair(word: Int, bts: Int): Int = ((word and 0xFFF) shl 8) or (bts and 0xFF)

    private fun packDetailAdjacency(first: Int, second: Int): Long =
        (first.toLong() shl DETAIL_PAIR_BITS) or second.toLong()

    private fun dressPlainTerrainWithLiveTileset(
        words: IntArray,
        bts: IntArray,
        originalWords: IntArray,
        preserved: BooleanArray,
        width: Int,
        height: Int,
        profile: TilesetProfile,
        seed: Long,
    ) {
        val resolved = resolveBlockTypes(words, width)
        val chosenMasks = IntArray(words.size) { -1 }
        val rng = Random(seed xor LIVE_DRESSING_SEED_SALT)
        for (i in words.indices) {
            if (preserved[i] || blockType(words[i]) == blockType(originalWords[i])) continue
            val rawType = blockType(words[i])
            when {
                rawType == 0x0 && resolved[i] == 0x0 -> {
                    words[i] = profile.airWord
                    bts[i] = 0
                }
                rawType == 0x8 && resolved[i] == 0x8 -> {
                    val x = i % width
                    val y = i / width
                    val mask = NeighborMask.fromPredicate(width, height, x, y) { nx, ny ->
                        TilesetProfile.isSolidType(resolved[ny * width + nx])
                    }
                    fun coherentNeighbor(neighbor: Int): Int? {
                        if (neighbor !in words.indices || chosenMasks[neighbor] < 0) return null
                        val neighborMask = chosenMasks[neighbor]
                        if (neighborMask != mask && (neighborMask and 0xF) != (mask and 0xF)) return null
                        return words[neighbor]
                    }
                    val coherent = if (rng.nextDouble() < LIVE_DRESSING_COHESION) {
                        (if (x > 0) coherentNeighbor(i - 1) else null)
                            ?: (if (y > 0) coherentNeighbor(i - width) else null)
                    } else {
                        null
                    }
                    words[i] = coherent ?: profile.pickSolid(mask, rng)
                    bts[i] = 0
                    chosenMasks[i] = mask
                }
            }
        }
    }

    private fun carveDoorApproach(
        group: DoorGroup,
        words: IntArray,
        bts: IntArray,
        preserved: BooleanArray,
        airWord: Int,
        width: Int,
        height: Int,
    ) {
        val rect = when (group.edge) {
            DoorEdge.LEFT -> intArrayOf(group.maxX + 1, group.minY - 1, group.maxX + DOOR_APPROACH, group.maxY + 1)
            DoorEdge.RIGHT -> intArrayOf(group.minX - DOOR_APPROACH, group.minY - 1, group.minX - 1, group.maxY + 1)
            DoorEdge.TOP -> intArrayOf(group.minX - 1, group.maxY + 1, group.maxX + 1, group.maxY + DOOR_APPROACH)
            DoorEdge.BOTTOM -> intArrayOf(group.minX - 1, group.minY - DOOR_APPROACH, group.maxX + 1, group.minY - 1)
            DoorEdge.INTERIOR -> return
        }
        for (y in rect[1]..rect[3]) for (x in rect[0]..rect[2]) {
            if (x !in 0 until width || y !in 0 until height) continue
            val i = y * width + x
            if (!preserved[i] && blockType(words[i]) != DOOR_TYPE) {
                words[i] = airWord
                bts[i] = 0
            }
        }
    }

    /** Join the nearest passable island to the largest one on every pass. */
    private fun connectPassableRegions(
        words: IntArray,
        bts: IntArray,
        preserved: BooleanArray,
        airWord: Int,
        width: Int,
        height: Int,
    ): Int {
        val changed = BooleanArray(words.size)
        val maxIterations = (width * height / 4).coerceIn(64, 1024)
        repeat(maxIterations) {
            val resolved = resolveBlockTypes(words, width)
            val labels = IntArray(words.size) { -1 }
            val components = ArrayList<MutableList<Int>>()
            for (start in words.indices) {
                if (!isPassable(resolved[start]) || labels[start] >= 0) continue
                val label = components.size
                val members = ArrayList<Int>()
                val queue = ArrayDeque<Int>()
                labels[start] = label
                queue.add(start)
                while (queue.isNotEmpty()) {
                    val cell = queue.removeFirst()
                    members.add(cell)
                    val x = cell % width
                    val y = cell / width
                    for ((dx, dy) in CARDINALS) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val neighbor = ny * width + nx
                        if (labels[neighbor] < 0 && isPassable(resolved[neighbor])) {
                            labels[neighbor] = label
                            queue.add(neighbor)
                        }
                    }
                }
                components.add(members)
            }
            if (components.size <= 1) return changed.count { it }

            val mainLabel = components.indices.maxBy { components[it].size }
            val previous = IntArray(words.size) { -2 }
            val queue = ArrayDeque<Int>()
            for (cell in components[mainLabel]) {
                previous[cell] = -1
                queue.add(cell)
            }
            var target = -1
            while (queue.isNotEmpty() && target < 0) {
                val cell = queue.removeFirst()
                val x = cell % width
                val y = cell / width
                for ((dx, dy) in CARDINALS) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val neighbor = ny * width + nx
                    if (previous[neighbor] != -2) continue
                    if ((nx == 0 || nx == width - 1 || ny == 0 || ny == height - 1) && labels[neighbor] < 0) {
                        continue
                    }
                    previous[neighbor] = cell
                    if (labels[neighbor] >= 0 && labels[neighbor] != mainLabel) {
                        target = neighbor
                        break
                    }
                    queue.add(neighbor)
                }
            }
            if (target < 0) return changed.count { it }

            var cursor = target
            while (cursor >= 0) {
                val x = cursor % width
                val y = cursor / width
                for (dy in 0..1) for (dx in 0..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 1 until width - 1 || ny !in 1 until height - 1) continue
                    val index = ny * width + nx
                    if (preserved[index] || blockType(words[index]) == DOOR_TYPE) continue
                    if (!isPassable(resolveBlockType(words, width, index)) || bts[index] != 0) {
                        words[index] = airWord
                        bts[index] = 0
                        changed[index] = true
                    }
                }
                cursor = previous[cursor]
            }
        }
        return changed.count { it }
    }

    private fun findDoorGroups(words: IntArray, width: Int, height: Int): List<DoorGroup> {
        val remaining = words.indices.filterTo(mutableSetOf()) { blockType(words[it]) == DOOR_TYPE }
        val groups = ArrayList<DoorGroup>()
        while (remaining.isNotEmpty()) {
            val start = remaining.minOrNull()!!
            remaining.remove(start)
            val queue = ArrayDeque<Int>()
            val cells = ArrayList<Int>()
            queue.add(start)
            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                cells.add(cell)
                val x = cell % width
                val y = cell / width
                for ((dx, dy) in CARDINALS) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val neighbor = ny * width + nx
                    if (remaining.remove(neighbor)) queue.add(neighbor)
                }
            }
            val minX = cells.minOf { it % width }
            val maxX = cells.maxOf { it % width }
            val minY = cells.minOf { it / width }
            val maxY = cells.maxOf { it / width }
            val edge = when {
                minX <= 1 -> DoorEdge.LEFT
                maxX >= width - 2 -> DoorEdge.RIGHT
                minY <= 1 -> DoorEdge.TOP
                maxY >= height - 2 -> DoorEdge.BOTTOM
                else -> DoorEdge.INTERIOR
            }
            groups.add(DoorGroup(cells, minX, minY, maxX, maxY, edge))
        }
        return groups
    }

    private fun resolveBlockTypes(words: IntArray, width: Int): IntArray =
        IntArray(words.size) { resolveBlockType(words, width, it) }

    private fun resolveBlockType(words: IntArray, width: Int, index: Int): Int {
        var cursor = index
        repeat(32) {
            when (val type = blockType(words[cursor])) {
                0x5 -> {
                    if (cursor % width == 0) return 0x8
                    cursor--
                }
                0xD -> {
                    if (cursor < width) return 0x8
                    cursor -= width
                }
                else -> return type
            }
        }
        return blockType(words[index])
    }

    private fun applyRects(mask: BooleanArray, width: Int, height: Int, rects: List<BiomeGenerationRect>) {
        if (width <= 0 || height <= 0) return
        for (rect in rects) {
            val x0 = rect.x0.coerceIn(0, width - 1)
            val x1 = rect.x1.coerceIn(0, width - 1)
            val y0 = rect.y0.coerceIn(0, height - 1)
            val y1 = rect.y1.coerceIn(0, height - 1)
            if (x1 < x0 || y1 < y0) continue
            for (y in y0..y1) for (x in x0..x1) mask[y * width + x] = true
        }
    }

    private fun blockType(word: Int): Int = (word ushr 12) and 0xF

    private fun isPassable(type: Int): Boolean = type in PASSABLE_TYPES

    private enum class DoorEdge { LEFT, RIGHT, TOP, BOTTOM, INTERIOR }

    private data class DoorGroup(
        val cells: List<Int>,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val edge: DoorEdge,
    ) {
        private val vertical: Boolean get() = maxY - minY >= maxX - minX

        fun isUsableGeneratedDoor(): Boolean =
            cells.size in 2..12 && when (edge) {
                DoorEdge.LEFT, DoorEdge.RIGHT -> vertical && maxX - minX <= 1 && maxY - minY <= 7
                DoorEdge.TOP, DoorEdge.BOTTOM -> !vertical && maxY - minY <= 1 && maxX - minX <= 7
                DoorEdge.INTERIOR -> false
        }
    }

    private data class DetailGrammar(
        val horizontal: Set<Long>,
        val vertical: Set<Long>,
    )

    private val PASSABLE_TYPES = setOf(0x0, 0x2, 0x4, 0x7, 0x9, 0xB, 0xC, 0xF)
    private val CARDINALS = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)
    private const val DOOR_TYPE = 0x9
    private const val DOOR_APPROACH = 4
    private const val MIN_GAMEPLAY_COMPONENT_CELLS = 16
    private const val SMALL_SOLID_COMPONENT_CELLS = 4
    private const val DETAIL_PAIR_BITS = 20
    private const val LIVE_DRESSING_COHESION = 0.9
    private const val LIVE_DRESSING_SEED_SALT = 0x51ED270BL
}

package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.MapStationTileEdit
import com.supermetroid.editor.data.MinimapTileEdit
import com.supermetroid.editor.rom.MapStationData
import com.supermetroid.editor.rom.MinimapData
import com.supermetroid.editor.rom.RomParser

internal const val MINIMAP_TILE_SOURCE_FILE = "bank_B5.asm"
internal const val MINIMAP_REVEAL_SOURCE_FILE = "bank_82.asm"

internal data class AsmMinimapSourceView(
    val map: MinimapData?,
    val stations: MapStationData?,
    val problem: String?,
) {
    val editable: Boolean get() = map != null && stations != null && problem == null
}

internal data class AsmMinimapSourcePlan(
    val replacements: Map<String, Pair<String, String>>,
    val areas: Set<Int>,
)

/**
 * A lossless visual-editor lens over the vanilla inline pause-map arrays.
 *
 * The source arrays use SNES storage order, including the fake top row. The visual editor uses
 * logical x/y order, so every conversion deliberately passes through [MinimapData.storageWordIndex]
 * or [MinimapData.mapDataByteAndMask]. Only numeric operands are replaced; labels, comments,
 * whitespace, line endings and address annotations remain handwritten source.
 */
internal class AsmMinimapSourceBridge(private val workspace: AsmWorkspaceState) {
    private data class Token(val start: Int, val end: Int, val digits: Int, val lowerCase: Boolean)
    private data class ArrayBlock(val tokens: List<Token>, val values: IntArray) {
        fun render(text: String, replacement: IntArray): String {
            require(replacement.size == tokens.size)
            val out = StringBuilder(text)
            for (index in tokens.indices.reversed()) {
                val token = tokens[index]
                val rendered = replacement[index].toString(16).padStart(token.digits, '0').let {
                    if (token.lowerCase) it.lowercase() else it.uppercase()
                }
                require(rendered.length <= token.digits) { "A minimap value no longer fits its source declaration." }
                out.replace(token.start, token.end, "\$$rendered")
            }
            return out.toString()
        }
    }

    /** Missing banks are possible in small test/custom workspaces; malformed present banks are not silently bypassed. */
    fun isAvailable(): Boolean = workspace.projectSourceTextsForNavigation(SOURCE_FILES) != null

    fun view(area: Int): AsmMinimapSourceView {
        require(area in 0 until MinimapData.NUM_AREAS)
        val texts = workspace.projectSourceTextsForNavigation(SOURCE_FILES)
            ?: return AsmMinimapSourceView(null, null, "Pause-map source banks are unavailable.")
        return runCatching {
            val mapValues = tileBlock(texts.getValue(MINIMAP_TILE_SOURCE_FILE), area).values
            val tiles = IntArray(MinimapData.TILE_COUNT)
            for (y in 0 until MinimapData.MAP_HEIGHT) for (x in 0 until MinimapData.MAP_WIDTH) {
                tiles[y * MinimapData.MAP_WIDTH + x] = mapValues[MinimapData.storageWordIndex(x, y)]
            }
            val packed = revealBlock(texts.getValue(MINIMAP_REVEAL_SOURCE_FILE), area).values
            val revealed = BooleanArray(MinimapData.TILE_COUNT)
            for (y in 0 until MinimapData.MAP_HEIGHT) for (x in 0 until MinimapData.MAP_WIDTH) {
                val (byte, mask) = MinimapData.mapDataByteAndMask(x, y)
                revealed[y * MinimapData.MAP_WIDTH + x] = packed[byte] and mask != 0
            }
            AsmMinimapSourceView(MinimapData(area, tiles), MapStationData(area, revealed), null)
        }.getOrElse { problem ->
            AsmMinimapSourceView(null, null, problem.message ?: "This pause-map source is ASM-only.")
        }
    }

    fun effectiveView(area: Int, owner: SmEditProject): AsmMinimapSourceView {
        val view = view(area)
        if (!view.editable) return view
        var map = requireNotNull(view.map)
        var stations = requireNotNull(view.stations)
        owner.minimapEdits[area.toString()].orEmpty().forEach { map = map.withTile(it.x, it.y, it.tileWord) }
        owner.mapStationEdits[area.toString()].orEmpty().forEach {
            if (stations.isRevealed(it.x, it.y) != it.revealed) stations = stations.withToggle(it.x, it.y)
        }
        return AsmMinimapSourceView(map, stations, null)
    }

    /** Convert a legacy sparse transaction into full source values before retiring those sparse entries. */
    fun planLegacyEdits(
        parser: RomParser,
        maps: Map<String, List<MinimapTileEdit>>,
        stations: Map<String, List<MapStationTileEdit>>,
        areas: Set<Int>,
    ): AsmMinimapSourcePlan {
        val updates = areas.associateWith { area ->
            var map = parser.readMinimapTiles(area)
            maps[area.toString()].orEmpty().forEach { map = map.withTile(it.x, it.y, it.tileWord) }
            var reveal = parser.readMapStationData(area)
            stations[area.toString()].orEmpty().forEach {
                if (reveal.isRevealed(it.x, it.y) != it.revealed) reveal = reveal.withToggle(it.x, it.y)
            }
            map to reveal
        }
        return plan(updates)
    }

    fun plan(updates: Map<Int, Pair<MinimapData, MapStationData>>): AsmMinimapSourcePlan {
        require(updates.isNotEmpty())
        require(updates.keys.all { it in 0 until MinimapData.NUM_AREAS })
        val before = workspace.projectSourceTextsForNavigation(SOURCE_FILES)
            ?: error("Pause-map source banks are unavailable.")
        validatePointers(before.getValue(MINIMAP_REVEAL_SOURCE_FILE))
        var tilesText = before.getValue(MINIMAP_TILE_SOURCE_FILE)
        var revealText = before.getValue(MINIMAP_REVEAL_SOURCE_FILE)
        for ((area, data) in updates.toSortedMap()) {
            val (map, stations) = data
            require(map.area == area && map.tiles.size == MinimapData.TILE_COUNT)
            require(stations.area == area && stations.revealed.size == MinimapData.TILE_COUNT)
            val words = IntArray(MinimapData.TILE_COUNT)
            val bytes = IntArray(MinimapData.MAP_STATION_DATA_SIZE)
            for (y in 0 until MinimapData.MAP_HEIGHT) for (x in 0 until MinimapData.MAP_WIDTH) {
                words[MinimapData.storageWordIndex(x, y)] = map.getTile(x, y)
                if (stations.isRevealed(x, y)) {
                    val (byte, mask) = MinimapData.mapDataByteAndMask(x, y)
                    bytes[byte] = bytes[byte] or mask
                }
            }
            tilesText = tileBlock(tilesText, area).render(tilesText, words)
            revealText = revealBlock(revealText, area).render(revealText, bytes)
        }
        val replacements = linkedMapOf<String, Pair<String, String>>()
        if (tilesText != before.getValue(MINIMAP_TILE_SOURCE_FILE)) {
            replacements[MINIMAP_TILE_SOURCE_FILE] = before.getValue(MINIMAP_TILE_SOURCE_FILE) to tilesText
        }
        if (revealText != before.getValue(MINIMAP_REVEAL_SOURCE_FILE)) {
            replacements[MINIMAP_REVEAL_SOURCE_FILE] = before.getValue(MINIMAP_REVEAL_SOURCE_FILE) to revealText
        }
        return AsmMinimapSourcePlan(replacements, updates.keys)
    }

    fun apply(
        plan: AsmMinimapSourcePlan,
        owner: SmEditProject,
        changed: () -> Unit,
    ): AsmRoomAreaMoveHistory {
        require(plan.replacements.isNotEmpty()) { "The pause-map source already contains these values." }
        val mapsBefore = owner.minimapEdits.mapValues { it.value.toList() }
        val stationsBefore = owner.mapStationEdits.mapValues { it.value.toList() }
        val mapsAfter = mapsBefore - plan.areas.map(Int::toString).toSet()
        val stationsAfter = stationsBefore - plan.areas.map(Int::toString).toSet()
        var history: AsmRoomAreaMoveHistory? = null
        fun requireModel(expectedMaps: Map<String, *>, expectedStations: Map<String, *>) {
            require(owner.minimapEdits == expectedMaps && owner.mapStationEdits == expectedStations) {
                "Undo newer minimap edits before restoring this source edit."
            }
            history?.beforeTransition?.invoke()
        }
        fun setModel(maps: Map<String, List<com.supermetroid.editor.data.MinimapTileEdit>>,
                     stations: Map<String, List<com.supermetroid.editor.data.MapStationTileEdit>>) {
            owner.minimapEdits.clear(); maps.forEach { (area, edits) -> owner.minimapEdits[area] = edits.toMutableList() }
            owner.mapStationEdits.clear(); stations.forEach { (area, edits) -> owner.mapStationEdits[area] = edits.toMutableList() }
        }
        fun notify(undo: Boolean) { changed(); history?.onTransition?.invoke(undo) }
        requireModel(mapsBefore, stationsBefore)
        workspace.replaceProjectSources(
            plan.replacements,
            beforeApply = { setModel(mapsAfter, stationsAfter) },
            afterApply = { notify(false) },
            beforeUndo = { requireModel(mapsAfter, stationsAfter); setModel(mapsBefore, stationsBefore) },
            afterUndo = { notify(true) },
            beforeRedo = { requireModel(mapsBefore, stationsBefore); setModel(mapsAfter, stationsAfter) },
            afterRedo = { notify(false) },
        )
        val anchor = plan.replacements.keys.first()
        return AsmRoomAreaMoveHistory(
            workspace,
            plan.replacements,
            requireNotNull(workspace.sourceUndoHead(anchor)),
            action = "minimap edit",
            anchorFile = anchor,
        ).also { history = it }
    }

    private fun tileBlock(text: String, area: Int): ArrayBlock {
        val positions = TILE_LABELS.mapValues { (_, label) -> uniqueLabel(text, label, 0, text.length) }
        val boundary = uniqueLabel(text, TILE_END_LABEL, 0, text.length)
        val label = TILE_LABELS.getValue(area)
        val start = positions.getValue(area)
        val end = (positions.values + boundary).filter { it > start }.minOrNull()
            ?: error("The $label source boundary is missing; this map is ASM-only.")
        return parseArray(text, lineEnd(text, start), end, "dw", MinimapData.TILE_COUNT, label)
    }

    private fun revealBlock(text: String, area: Int): ArrayBlock {
        validatePointers(text)
        val mapData = uniqueLabel(text, "MapData", 0, text.length)
        val boundary = uniqueLabel(text, REVEAL_END_LABEL, mapData, text.length)
        val positions = REVEAL_LABELS.mapValues { (_, label) -> uniqueLocalLabel(text, label, mapData, boundary) }
        val label = REVEAL_LABELS.getValue(area)
        val start = positions.getValue(area)
        val end = (positions.values + boundary).filter { it > start }.minOrNull()
            ?: error("MapData.$label has no safe source boundary; this reveal mask is ASM-only.")
        return parseArray(text, lineEnd(text, start), end, "db", MinimapData.MAP_STATION_DATA_SIZE, "MapData.$label")
    }

    private fun parseArray(text: String, start: Int, end: Int, directive: String, count: Int, name: String): ArrayBlock {
        val tokens = mutableListOf<Token>()
        var offset = start
        while (offset < end) {
            val next = text.indexOf('\n', offset).let { if (it < 0 || it >= end) end else it + 1 }
            val raw = text.substring(offset, next)
            val codeLength = raw.indexOf(';').let { if (it < 0) raw.length else it }
            val code = raw.substring(0, codeLength).trimEnd('\r', '\n')
            if (code.isNotBlank()) {
                val match = Regex("^\\s*$directive\\b(.*)$", RegexOption.IGNORE_CASE).matchEntire(code)
                    ?: error("$name contains a non-$directive declaration; it is ASM-only.")
                val operands = match.groupValues[1]
                val operandStart = offset + match.groups[1]!!.range.first
                val valueMatches = HEX.findAll(operands).toList()
                val residue = HEX.replace(operands, "").replace(",", "").trim()
                require(valueMatches.isNotEmpty() && residue.isEmpty()) {
                    "$name contains an unsupported expression; it is ASM-only."
                }
                for (value in valueMatches) {
                    val literal = value.value
                    val digits = literal.drop(1)
                    tokens += Token(
                        start = operandStart + value.range.first,
                        end = operandStart + value.range.last + 1,
                        digits = digits.length,
                        lowerCase = digits.any(Char::isLetter) && digits.none(Char::isUpperCase),
                    )
                }
            }
            offset = next
        }
        require(tokens.size == count) { "$name has ${tokens.size} values; expected $count. It is ASM-only." }
        val values = IntArray(tokens.size) { index -> text.substring(tokens[index].start + 1, tokens[index].end).toInt(16) }
        return ArrayBlock(tokens, values)
    }

    private fun validatePointers(text: String) {
        val areaPointers = uniqueLabel(text, "AreaMapPointers", 0, text.length)
        val areaEnd = uniqueLabel(text, "AreaLabelTilemaps", areaPointers, text.length)
        require(symbolOperands(text, lineEnd(text, areaPointers), areaEnd, "dl") == TILE_LABELS.values.toList()) {
            "AreaMapPointers is custom; pause maps are ASM-only."
        }
        val mapData = uniqueLabel(text, "MapData", 0, text.length)
        val firstMask = uniqueLocalLabel(text, REVEAL_LABELS.getValue(0), mapData, text.length)
        val expected = REVEAL_POINTER_LABELS + REVEAL_POINTER_LABELS[5]
        require(symbolOperands(text, lineEnd(text, mapData), firstMask, "dw") == expected) {
            "MapData.pointers is custom; station reveal masks are ASM-only."
        }
    }

    private fun symbolOperands(text: String, start: Int, end: Int, directive: String): List<String> {
        val result = mutableListOf<String>()
        var offset = start
        while (offset < end) {
            val next = text.indexOf('\n', offset).let { if (it < 0 || it >= end) end else it + 1 }
            val code = text.substring(offset, next).substringBefore(';').trim()
            Regex("^$directive\\s+([A-Za-z_][A-Za-z0-9_]*)$", RegexOption.IGNORE_CASE).matchEntire(code)
                ?.let { result += it.groupValues[1] }
            offset = next
        }
        return result
    }

    private fun uniqueLabel(text: String, label: String, start: Int, end: Int): Int {
        val matches = Regex("(?m)^${Regex.escape(label)}:[^\\r\\n]*$").findAll(text, start)
            .map { it.range.first }.takeWhile { it < end }.toList()
        require(matches.size == 1) { "$label must have one direct declaration; this map is ASM-only." }
        return matches.single()
    }

    private fun uniqueLocalLabel(text: String, label: String, start: Int, end: Int): Int {
        val matches = Regex("(?m)^\\s+\\.${Regex.escape(label)}:[^\\r\\n]*$").findAll(text, start)
            .map { it.range.first }.takeWhile { it < end }.toList()
        require(matches.size == 1) { "MapData.$label must have one local declaration; this reveal mask is ASM-only." }
        return matches.single()
    }

    private fun lineEnd(text: String, offset: Int): Int = text.indexOf('\n', offset).let { if (it < 0) text.length else it + 1 }

    private companion object {
        val SOURCE_FILES = setOf(MINIMAP_TILE_SOURCE_FILE, MINIMAP_REVEAL_SOURCE_FILE)
        val HEX = Regex("\\$[0-9A-Fa-f]+")
        val TILE_LABELS = linkedMapOf(
            0 to "MapTilemaps_crateria",
            1 to "MapTilemaps_brinstar",
            2 to "MapTilemaps_norfair",
            3 to "MapTilemaps_wreckedShip",
            4 to "MapTilemaps_maridia",
            5 to "MapTilemaps_tourian",
            6 to "UNUSED_MapTilemaps_ceres_B5E000",
        )
        const val TILE_END_LABEL = "Freespace_BankB5_F000"
        val REVEAL_LABELS = linkedMapOf(
            0 to "crateria",
            1 to "brinstar",
            2 to "norfair",
            3 to "wreckedShip",
            4 to "maridia",
            5 to "tourianDebug",
            6 to "ceres",
        )
        val REVEAL_POINTER_LABELS = listOf(
            "MapData_crateria", "MapData_brinstar", "MapData_norfair", "MapData_wreckedShip",
            "MapData_maridia", "MapData_tourianDebug", "MapData_ceres",
        )
        const val REVEAL_END_LABEL = "SetupMapScrollingForPauseMenu"
    }
}

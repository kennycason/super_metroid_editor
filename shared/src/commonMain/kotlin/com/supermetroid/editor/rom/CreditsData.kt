package com.supermetroid.editor.rom

/**
 * Parser and encoder for Super Metroid's end-of-game credits.
 *
 * The credits system has two parts:
 * 1. **Credits tilemap** — LZ5-compressed at SNES $97:EEFF, decompresses to 8192 bytes
 *    (128 rows × 64 bytes). Each row is 32 SNES BG-word tiles.
 * 2. **Credits instruction list** — In bank $8C starting at $8C:D91B. Sequences which
 *    tilemap rows to display, with timing. Rows are referenced by byte offset into the
 *    decompressed tilemap ($7F:0000).
 *
 * Character encoding in the credits font (Tiles_Font3_Background):
 * - Small font (section headers): tile = letterIndex (A=0x00 .. Z=0x19)
 * - Large font top half (names): A-P = 0x20+i, Q-Z = 0x40+(i-16)
 * - Large font bottom half: top tile + 0x10
 * - Space = 0x4F, blank margin = 0x7F
 */
object CreditsData {

    // ─── ROM addresses ───────────────────────────────────────────
    const val CREDITS_TILEMAP_SNES = 0x97EEFF
    const val INST_LIST_SNES = 0x8CD91B
    const val INST_LIST_END_SNES = 0x8CDC9B  // byte after last instruction
    const val INST_LIST_BANK = 0x8C
    const val CREDITS_TILEMAP_CAPACITY = 0x0A88

    // Instruction opcodes (16-bit addresses in bank $8B)
    const val OP_TIMER_IN_Y = 0x9A17
    const val OP_DEC_TIMER_GOTO = 0x9A0D
    const val OP_END_CREDITS = 0xF6FE
    const val OP_DELETE = 0x99FE

    // Credits object definition: the instruction list pointer is at this SNES address
    // (the word at $8B:F6FC stores the 16-bit address of InstList_Credits_Start within bank $8C)
    const val CREDITS_OBJ_DEF_INSTLIST_SNES = 0x8BF6FC

    // Tilemap layout
    const val ROW_BYTES = 64        // 32 tiles × 2 bytes
    const val ROW_TILES = 32
    const val TOTAL_ROWS = 128
    const val TILEMAP_SIZE = ROW_BYTES * TOTAL_ROWS  // 8192
    const val BLANK_ROW_OFFSET = 0x1FC0   // Row 127 = universal blank spacer
    private const val SECTION_BLANK_ROWS = 0x0010
    private const val MIN_FINAL_BLANK_ROWS = 0x0022

    // Font tile encoding
    const val SPACE_TILE = 0x4F
    const val MARGIN_TILE = 0x7F

    // Palette values for BG words: (palette << 10) | tile
    const val HEADER_PALETTE = 1    // ordinary section-title palette
    const val BANNER_PALETTE = 4    // main "SUPER METROID STAFF" banner
    const val NAME_PALETTE = 0      // white for name rows

    // ─── Character ↔ tile mapping ─────────────────────────────────

    /** Small font tile for section headers. A=0x00..Z=0x19. */
    fun smallTile(ch: Char): Int = when {
        ch in 'A'..'Z' -> ch - 'A'
        ch == ' ' -> SPACE_TILE
        else -> SPACE_TILE
    }

    fun smallTileToChar(tile: Int): Char = when {
        tile in 0x00..0x19 -> 'A' + tile
        tile == SPACE_TILE -> ' '
        else -> ' '
    }

    /** Large font top-half tile. A-P: 0x20+i, Q-Z: 0x40+(i-16). */
    fun largeTileTop(ch: Char): Int = when {
        ch in 'A'..'P' -> 0x20 + (ch - 'A')
        ch in 'Q'..'Z' -> 0x40 + (ch - 'Q')
        ch == ' ' -> SPACE_TILE
        else -> SPACE_TILE
    }

    /** Large font bottom-half tile = top + 0x10. */
    fun largeTileBottom(ch: Char): Int {
        val top = largeTileTop(ch)
        return if (top == SPACE_TILE) SPACE_TILE else top + 0x10
    }

    fun largeTileTopToChar(tile: Int): Char = when {
        tile in 0x20..0x2F -> 'A' + (tile - 0x20)
        tile in 0x40..0x49 -> 'Q' + (tile - 0x40)
        tile == SPACE_TILE -> ' '
        else -> ' '
    }

    // ─── Tilemap row encoding ────────────────────────────────────

    /** Encode a text string as a centered small-font tilemap row (64 bytes). */
    fun encodeHeaderRow(text: String, palette: Int = HEADER_PALETTE): ByteArray {
        val upper = text.uppercase().take(ROW_TILES)
        return encodeRow(upper, palette) { smallTile(it) }
    }

    /** Encode a text string as a centered large-font top-half row (64 bytes). */
    fun encodeNameTopRow(text: String, palette: Int = NAME_PALETTE): ByteArray {
        val upper = text.uppercase().take(ROW_TILES)
        return encodeRow(upper, palette) { largeTileTop(it) }
    }

    /** Encode a text string as a centered large-font bottom-half row (64 bytes). */
    fun encodeNameBottomRow(text: String, palette: Int = NAME_PALETTE): ByteArray {
        val upper = text.uppercase().take(ROW_TILES)
        return encodeRow(upper, palette) { largeTileBottom(it) }
    }

    private inline fun encodeRow(
        text: String,
        palette: Int,
        tileMapper: (Char) -> Int,
    ): ByteArray {
        val row = ByteArray(ROW_BYTES)
        // Fill with margin blank tiles
        for (col in 0 until ROW_TILES) {
            writeWord(row, col * 2, bgWord(MARGIN_TILE, 0))
        }
        // Center the text
        val startCol = maxOf(0, (ROW_TILES - text.length) / 2)
        for (i in text.indices) {
            val col = startCol + i
            if (col >= ROW_TILES) break
            writeWord(row, col * 2, bgWord(tileMapper(text[i]), palette))
        }
        return row
    }

    /** Build a SNES BG word: tile number (10 bits) | palette (3 bits) << 10. */
    fun bgWord(tile: Int, palette: Int): Int =
        (tile and 0x3FF) or ((palette and 7) shl 10)

    // ─── Tilemap row decoding ────────────────────────────────────

    /** Decode a tilemap row to text using the small font. */
    fun decodeHeaderRow(row: ByteArray): String =
        decodeRowText(row) { smallTileToChar(it) }

    /** Decode a tilemap row to text using the large font top-half. */
    fun decodeNameRow(row: ByteArray): String =
        decodeRowText(row) { largeTileTopToChar(it) }

    /**
     * Decode a tilemap row to text. Margin/space tiles between letters become
     * single spaces; leading/trailing padding is trimmed.
     */
    private inline fun decodeRowText(row: ByteArray, charMapper: (Int) -> Char): String {
        val sb = StringBuilder()
        for (col in 0 until ROW_TILES) {
            val word = readWord(row, col * 2)
            val tile = word and 0x3FF
            when {
                tile == MARGIN_TILE || tile == SPACE_TILE -> sb.append(' ')
                else -> sb.append(charMapper(tile))
            }
        }
        // Collapse multiple spaces into single spaces, then trim outer padding
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }

    /** Classify a row: returns true if it contains large-font (name) tiles. */
    fun isLargeFont(row: ByteArray): Boolean {
        for (col in 0 until ROW_TILES) {
            val tile = readWord(row, col * 2) and 0x3FF
            if (tile in 0x20..0x2F || tile in 0x40..0x49) return true
        }
        return false
    }

    /** Returns true if a row is entirely blank/margin tiles. */
    fun isBlankRow(row: ByteArray): Boolean {
        for (col in 0 until ROW_TILES) {
            val tile = readWord(row, col * 2) and 0x3FF
            if (tile != MARGIN_TILE && tile != SPACE_TILE) return false
        }
        return true
    }

    // ─── Credits parsing ─────────────────────────────────────────

    data class CreditSection(
        val header: String,
        val names: List<String>,
        val instListPc: Int,
    )

    data class ParsedCredits(
        val sections: List<CreditSection>,
        val tilemapRows: List<ByteArray>,
        val usedRowIndices: Set<Int>,
        val freeRowIndices: List<Int>,
        val compressedTilemapSize: Int,
        val instListSnesAddr: Int,
        val instListSize: Int,
    )

    /** Parse credits from the ROM. Returns null if data is unreadable. */
    fun parseCredits(parser: RomParser): ParsedCredits? {
        val rom = parser.getRomData()
        // Decompress tilemap
        val tilemapData = try {
            parser.decompressLZ2(CREDITS_TILEMAP_SNES)
        } catch (_: Exception) { return null }

        if (tilemapData.size < TILEMAP_SIZE) return null
        val compressedSize = try {
            parser.decompressLZ2WithSize(CREDITS_TILEMAP_SNES).second
        } catch (_: Exception) { return null }

        // Split into rows
        val rows = (0 until TOTAL_ROWS).map { i ->
            tilemapData.copyOfRange(i * ROW_BYTES, (i + 1) * ROW_BYTES)
        }

        // Follow the live object-definition pointer. Credits instructions execute
        // with DB=$8C, so the stored word is a bank-local address.
        val pointerPc = parser.snesToPc(CREDITS_OBJ_DEF_INSTLIST_SNES)
        if (pointerPc < 0 || pointerPc + 1 >= rom.size) return null
        val instListLow = readWordRom(rom, pointerPc)
        if (instListLow !in 0x8000..0xFFFF) return null
        val instListSnes = (INST_LIST_BANK shl 16) or instListLow
        val instListPc = parser.snesToPc(instListSnes)
        val instListEndPc = parser.snesToPc((INST_LIST_BANK shl 16) or 0xFFFF) + 1
        if (instListPc !in rom.indices || instListEndPc > rom.size) return null
        val usedOffsets = mutableSetOf<Int>()
        val sections = mutableListOf<CreditSection>()

        var pos = instListPc
        var currentSectionPc = instListPc
        var sectionRows = mutableListOf<Int>()  // row offsets for current section

        var sawEndCredits = false
        var terminated = false
        while (pos + 1 < instListEndPc && pos + 1 < rom.size) {
            val word = readWordRom(rom, pos)

            if (word and 0x8000 != 0) {
                // Negative = ASM instruction
                val opcode = word and 0xFFFF
                when (opcode) {
                    OP_TIMER_IN_Y -> {
                        // Timer instruction: starts or ends a section
                        if (sectionRows.isNotEmpty()) {
                            sections.add(buildSection(sectionRows, rows, currentSectionPc))
                            sectionRows = mutableListOf()
                        }
                        currentSectionPc = pos
                        pos += 4  // skip opcode + timer value
                    }
                    OP_DEC_TIMER_GOTO -> {
                        pos += 4  // skip opcode + goto target
                    }
                    OP_END_CREDITS -> {
                        sawEndCredits = true
                        pos += 2
                    }
                    OP_DELETE -> {
                        pos += 2
                        if (sawEndCredits) {
                            terminated = true
                            break
                        }
                    }
                    // Unknown negative words may have operands with unknown
                    // widths. Refuse instead of losing instruction alignment.
                    else -> return null
                }
            } else {
                // Positive = row copy: word1 (ignored), word2 (tilemap offset)
                if (pos + 3 >= instListEndPc || pos + 3 >= rom.size) return null
                val offset = readWordRom(rom, pos + 2)
                if (offset % ROW_BYTES != 0 || offset !in 0 until TILEMAP_SIZE) return null
                usedOffsets.add(offset)
                if (offset != BLANK_ROW_OFFSET) {
                    sectionRows.add(offset)
                }
                pos += 4
            }
        }
        if (!terminated) return null
        // Flush last section
        if (sectionRows.isNotEmpty()) {
            sections.add(buildSection(sectionRows, rows, currentSectionPc))
        }

        val usedRowIndices = usedOffsets.map { it / ROW_BYTES }.toSet()
        val freeIndices = (0 until TOTAL_ROWS).filter { it !in usedRowIndices && it != TOTAL_ROWS - 1 }

        return ParsedCredits(
            sections = sections,
            tilemapRows = rows,
            usedRowIndices = usedRowIndices,
            freeRowIndices = freeIndices,
            compressedTilemapSize = compressedSize,
            instListSnesAddr = instListSnes,
            instListSize = pos - instListPc,
        )
    }

    private fun buildSection(
        rowOffsets: List<Int>,
        rows: List<ByteArray>,
        instListPc: Int,
    ): CreditSection {
        if (rowOffsets.isEmpty()) return CreditSection("", emptyList(), instListPc)

        // First row offset is usually the header
        val headerRowIdx = rowOffsets[0] / ROW_BYTES
        val headerRow = rows.getOrNull(headerRowIdx)
        val header = if (headerRow != null) {
            if (isLargeFont(headerRow)) decodeNameRow(headerRow) else decodeHeaderRow(headerRow)
        } else ""

        // Remaining rows are names (in pairs for large font: top + bottom)
        val names = mutableListOf<String>()
        var i = 1
        while (i < rowOffsets.size) {
            val idx = rowOffsets[i] / ROW_BYTES
            val row = rows.getOrNull(idx)
            if (row != null && isLargeFont(row)) {
                names.add(decodeNameRow(row))
                i += 2  // skip bottom-half row
            } else if (row != null) {
                names.add(decodeHeaderRow(row))
                i += 1
            } else {
                i += 1
            }
        }

        return CreditSection(header, names, instListPc)
    }

    // ─── Credits export (tilemap + instruction list) ─────────────

    data class CreditsExportResult(
        val tilemapBytes: ByteArray,
        val instListBytes: ByteArray,
        val instListSnesAddr: Int,
    )

    /**
     * Build the export data for modified credits.
     *
     * @param parser ROM parser for reading original data
     * @param newGroups List of custom credit groups to append before the final section
     * @return Export result with recompressed tilemap and new instruction list bytes,
     *         or null if the modifications exceed ROM limits.
     */
    fun buildCreditsExport(
        parser: RomParser,
        newGroups: List<CreditGroup>,
        relocatedInstListSnesAddr: Int? = null,
    ): CreditsExportResult? {
        if (newGroups.isEmpty()) return null

        val parsed = parseCredits(parser) ?: return null
        if (validateGroups(newGroups, parsed.freeRowIndices.size).isNotEmpty()) return null
        val rom = parser.getRomData()

        // ── 1. Build modified tilemap ─────────────────────────────
        val tilemap = ByteArray(TILEMAP_SIZE)
        // Copy original tilemap
        for (i in parsed.tilemapRows.indices) {
            parsed.tilemapRows[i].copyInto(tilemap, i * ROW_BYTES)
        }

        // Write new group rows into free slots
        val freeRows = parsed.freeRowIndices.toMutableList()
        val groupRowAssignments = mutableListOf<GroupRowAssignment>()

        for (group in newGroups) {
            val headerText = group.header.uppercase().take(ROW_TILES - 2)
            val nameTexts = group.names.map { it.uppercase().take(ROW_TILES - 2) }

            // Need: 1 header row + 2 rows per name (large font top + bottom)
            val rowsNeeded = 1 + nameTexts.size * 2
            if (freeRows.size < rowsNeeded) return null  // out of space

            val headerIdx = freeRows.removeFirst()
            encodeHeaderRow(headerText).copyInto(tilemap, headerIdx * ROW_BYTES)

            val nameRowPairs = mutableListOf<Pair<Int, Int>>()
            for (name in nameTexts) {
                val topIdx = freeRows.removeFirst()
                val bottomIdx = freeRows.removeFirst()
                encodeNameTopRow(name).copyInto(tilemap, topIdx * ROW_BYTES)
                encodeNameBottomRow(name).copyInto(tilemap, bottomIdx * ROW_BYTES)
                nameRowPairs.add(topIdx to bottomIdx)
            }

            groupRowAssignments.add(GroupRowAssignment(headerIdx, nameRowPairs))
        }

        // Recompress tilemap
        val compressedTilemap = LZ5Compressor.compress(tilemap)
        // The native source asset has one fixed $A88-byte envelope. Growth is
        // never allowed to consume the following asset implicitly.
        if (compressedTilemap.size > CREDITS_TILEMAP_CAPACITY) return null

        // ── 2. Build new instruction list ─────────────────────────
        val originalInstListPc = parser.snesToPc(parsed.instListSnesAddr)
        val originalInstListEndPc = originalInstListPc + parsed.instListSize

        // Read original instruction list bytes (minus the final End section)
        // Find the last TimerInY before the End section
        val endBoundary = findEndSectionBoundary(rom, originalInstListPc, originalInstListEndPc)
            ?: return null

        // Copy everything up to and including the last section's TimerInY
        val originalPrefix = rom.copyOfRange(originalInstListPc, endBoundary.endSectionStartPc)

        val newInstListSnes = relocatedInstListSnesAddr ?: parsed.instListSnesAddr
        if (newInstListSnes ushr 16 != INST_LIST_BANK || (newInstListSnes and 0xFFFF) < 0x8000) return null

        // Build the full instruction list
        val instList = mutableListOf<Byte>()

        // Write original prefix (all sections up to end section)
        // Need to fix self-references: the original uses absolute addresses in bank $8C.
        // The loop-back targets (dw InstList_Credits_XXX) reference the OLD location.
        // We need to rebase them to the new location.
        val oldBase = parsed.instListSnesAddr and 0xFFFF
        val newBase = newInstListSnes and 0xFFFF
        val rebasedPrefix = rebaseInstructionList(originalPrefix, oldBase, newBase)
        // The native final section uses a longer delay to replace every visible
        // credits-ring row with blanks before the post-credits screen appears.
        // This timer now leads into the first custom section, so make it an
        // ordinary inter-section delay and move the native final flush to the
        // last custom section below.
        writeWord(rebasedPrefix, rebasedPrefix.size - 2, SECTION_BLANK_ROWS)
        instList.addAll(rebasedPrefix.toList())

        // Add new custom sections
        for ((assignmentIndex, assignment) in groupRowAssignments.withIndex()) {
            val sectionOffset = instList.size
            val sectionSnesLow = newBase + sectionOffset

            // Blank spacer row
            addWord(instList, 0x0000)
            addWord(instList, BLANK_ROW_OFFSET)

            // Decrement timer + loop back
            addWord(instList, OP_DEC_TIMER_GOTO)
            addWord(instList, sectionSnesLow)  // loop back to this section's spacer

            // Header row
            addWord(instList, 0x0000)
            addWord(instList, assignment.headerRowIdx * ROW_BYTES)

            // Blank spacer
            addWord(instList, 0x0000)
            addWord(instList, BLANK_ROW_OFFSET)

            // Name rows (top + bottom pairs)
            for ((topIdx, bottomIdx) in assignment.nameRowPairs) {
                addWord(instList, 0x0000)
                addWord(instList, topIdx * ROW_BYTES)
                addWord(instList, 0x0000)
                addWord(instList, bottomIdx * ROW_BYTES)
            }

            // Intermediate groups use the normal spacing. The final group must
            // retain the native end delay (at least 34 blank rows) so its text
            // has completely scrolled out of the 32-row ring before EndCredits
            // switches to the post-credits screen.
            addWord(instList, OP_TIMER_IN_Y)
            addWord(
                instList,
                if (assignmentIndex == groupRowAssignments.lastIndex) {
                    maxOf(endBoundary.finalBlankRows, MIN_FINAL_BLANK_ROWS)
                } else {
                    SECTION_BLANK_ROWS
                },
            )
        }

        // Final end section
        val endSectionOffset = instList.size
        val endSnesLow = newBase + endSectionOffset

        addWord(instList, 0x0000)
        addWord(instList, BLANK_ROW_OFFSET)
        addWord(instList, OP_DEC_TIMER_GOTO)
        addWord(instList, endSnesLow)
        addWord(instList, OP_END_CREDITS)
        addWord(instList, OP_DELETE)

        val instListBytes = instList.toByteArray()
        if (newBase + instListBytes.size > 0x10000) return null

        return CreditsExportResult(
            tilemapBytes = compressedTilemap,
            instListBytes = instListBytes,
            instListSnesAddr = newInstListSnes,
        )
    }

    private data class EndSectionBoundary(
        val endSectionStartPc: Int,
        val finalBlankRows: Int,
    )

    /** Find the final blank-flush timer immediately before the End section. */
    private fun findEndSectionBoundary(rom: ByteArray, startPc: Int, endPc: Int): EndSectionBoundary? {
        var pos = startPc
        var lastTimerPos = -1

        while (pos + 1 < endPc && pos + 1 < rom.size) {
            val word = readWordRom(rom, pos)
            if (word and 0x8000 != 0) {
                val opcode = word and 0xFFFF
                when (opcode) {
                    OP_TIMER_IN_Y -> {
                        lastTimerPos = pos
                        pos += 4
                    }
                    OP_END_CREDITS, OP_DELETE -> {
                        if (lastTimerPos < startPc || lastTimerPos + 3 >= endPc) return null
                        // The last TimerInY before EndCredits controls how many
                        // blank rows flush the ring before the ending transition.
                        return EndSectionBoundary(
                            endSectionStartPc = lastTimerPos + 4,
                            finalBlankRows = readWordRom(rom, lastTimerPos + 2),
                        )
                    }
                    else -> pos += if (opcode == OP_DEC_TIMER_GOTO) 4 else 2
                }
            } else {
                pos += 4
            }
        }
        return null
    }

    /**
     * Rebase self-referencing addresses in the instruction list prefix.
     * The DecrementTimer_GotoYIfNonZero instructions reference loop-back targets
     * that are absolute addresses within bank $8C. When the list is relocated,
     * these references must be updated.
     */
    private fun rebaseInstructionList(
        prefix: ByteArray,
        oldBase: Int,
        newBase: Int,
    ): ByteArray {
        val result = prefix.copyOf()
        val delta = newBase - oldBase
        var pos = 0

        while (pos + 1 < result.size) {
            val word = readWord(result, pos)
            if (word and 0x8000 != 0) {
                val opcode = word and 0xFFFF
                when (opcode) {
                    OP_TIMER_IN_Y -> pos += 4
                    OP_DEC_TIMER_GOTO -> {
                        // Next word is the loop-back target — rebase it
                        if (pos + 3 < result.size) {
                            val target = readWord(result, pos + 2)
                            writeWord(result, pos + 2, target + delta)
                        }
                        pos += 4
                    }
                    else -> pos += 2
                }
            } else {
                pos += 4  // row copy entry (no rebasing needed — tilemap offsets are unchanged)
            }
        }
        return result
    }

    // ─── Limits ──────────────────────────────────────────────────

    const val MAX_HEADER_LENGTH = 26
    const val MAX_NAME_LENGTH = 26
    const val MAX_NAMES_PER_GROUP = 6
    const val MAX_CUSTOM_GROUPS = 5
    val ALLOWED_CHARS = ('A'..'Z').toSet() + setOf(' ')

    fun validateHeader(text: String): String? = when {
        text.isBlank() -> "Header cannot be empty"
        text.length > MAX_HEADER_LENGTH -> "Header exceeds $MAX_HEADER_LENGTH characters"
        text.uppercase().any { it !in ALLOWED_CHARS } -> "Only A-Z and spaces allowed"
        else -> null
    }

    fun validateName(text: String): String? = when {
        text.isBlank() -> "Name cannot be empty"
        text.length > MAX_NAME_LENGTH -> "Name exceeds $MAX_NAME_LENGTH characters"
        text.uppercase().any { it !in ALLOWED_CHARS } -> "Only A-Z and spaces allowed"
        else -> null
    }

    fun rowsRequired(group: CreditGroup): Int = 1 + group.names.size * 2

    fun rowsRequired(groups: List<CreditGroup>): Int = groups.sumOf(::rowsRequired)

    fun validateGroups(groups: List<CreditGroup>, availableRows: Int? = null): List<String> = buildList {
        if (groups.size > MAX_CUSTOM_GROUPS) add("At most $MAX_CUSTOM_GROUPS custom credit groups are supported")
        groups.forEachIndexed { groupIndex, group ->
            validateHeader(group.header)?.let { add("Group ${groupIndex + 1}: $it") }
            if (group.names.isEmpty()) add("Group ${groupIndex + 1}: at least one name is required")
            if (group.names.size > MAX_NAMES_PER_GROUP) {
                add("Group ${groupIndex + 1}: at most $MAX_NAMES_PER_GROUP names are supported")
            }
            group.names.forEachIndexed { nameIndex, name ->
                validateName(name)?.let { add("Group ${groupIndex + 1}, name ${nameIndex + 1}: $it") }
            }
        }
        if (availableRows != null && rowsRequired(groups) > availableRows) {
            add("Credits need ${rowsRequired(groups)} tilemap rows, but only $availableRows are available")
        }
    }

    // ─── Utility ─────────────────────────────────────────────────

    private fun readWord(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readWordRom(rom: ByteArray, pc: Int): Int =
        (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)

    private fun writeWord(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun addWord(list: MutableList<Byte>, value: Int) {
        list.add((value and 0xFF).toByte())
        list.add(((value shr 8) and 0xFF).toByte())
    }

    data class CreditGroup(
        val header: String,
        val names: List<String>,
    )

    private data class GroupRowAssignment(
        val headerRowIdx: Int,
        val nameRowPairs: List<Pair<Int, Int>>,
    )
}

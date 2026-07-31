package com.supermetroid.editor.rom

/**
 * Parsed text strings from the Super Metroid ROM.
 *
 * Three text categories:
 * 1. Area names — 7 entries, tilemap encoding, displayed on pause screen
 * 2. Escape sequences — ASCII-encoded timer messages (Ceres + Tourian)
 * 3. Green intro text — 6-byte entries with tilemap character IDs (scrolling story text)
 */
data class TextEntry(
    val id: String,
    val label: String,
    val category: TextCategory,
    val pcOffset: Int,
    val snesAddress: Int,
    val maxLength: Int,
    val text: String,
    val rawBytes: ByteArray,
    val writable: Boolean = true,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TextEntry) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

enum class TextCategory(val displayName: String) {
    INTRO_STORY("Intro Story"),
    AREA_NAME("Area Names"),
    ESCAPE_TEXT("Escape Messages"),
    UI_MESSAGE("In-Game Messages"),
    ITEM_NAME("Item Pickups"),
}

object TextData {
    // ─── Intro story (green text) ─────────────────────────────────
    // 6-byte records: [2-byte delay][1-byte X][1-byte Y][2-byte char pointer]
    // Character pointers map to tile graphics in bank $8C.
    // Sections are separated by special marker pointers (MOVIE, WAIT).
    const val GREEN_TEXT_PC = 0x064385  // Bank $8C, offset $C385
    const val GREEN_TEXT_SNES = 0x8CC385
    const val GREEN_TEXT_RECORD_SIZE = 6
    const val GREEN_TEXT_MAX_RECORDS = 781

    // Character pointer → ASCII mapping
    private val CHAR_POINTER_MAP = mapOf(
        0xD67D to ' ', 0xD685 to 'A', 0xD68B to 'B', 0xD691 to 'C',
        0xD697 to 'D', 0xD69D to 'E', 0xD6A3 to 'F', 0xD6A9 to 'G',
        0xD6AF to 'H', 0xD6B5 to 'I', 0xD6BB to 'J', 0xD6C1 to 'K',
        0xD6C7 to 'L', 0xD6CD to 'M', 0xD6D3 to 'N', 0xD6D9 to 'O',
        0xD6DF to 'P', 0xD6E5 to 'Q', 0xD6EB to 'R', 0xD6F1 to 'S',
        0xD6F7 to 'T', 0xD6FD to 'U', 0xD703 to 'V', 0xD709 to 'W',
        0xD70F to 'X', 0xD715 to 'Y', 0xD71B to 'Z',
        0xD721 to '0', 0xD727 to '1', 0xD72D to '2', 0xD733 to '3',
        0xD739 to '4', 0xD73F to '5', 0xD745 to '6', 0xD74B to '7',
        0xD751 to '8', 0xD757 to '9',
        0xD75D to '.', 0xD763 to ',', 0xD76F to '\'', 0xD77B to '!',
    )
    private val CHAR_TO_POINTER = CHAR_POINTER_MAP.entries.associate { (ptr, ch) -> ch to ptr }

    // Special marker pointers that separate story sections
    private const val MARKER_NEW = 0xD683
    private const val MARKER_MOVIE1 = 0xAE79
    private const val MARKER_MOVIE2 = 0xB074
    private const val MARKER_MOVIE3 = 0xB0B3
    private const val MARKER_MOVIE4 = 0xB19B
    private const val MARKER_WAIT = 0xB228
    private val SECTION_MARKERS = setOf(MARKER_MOVIE1, MARKER_MOVIE2, MARKER_MOVIE3, MARKER_MOVIE4, MARKER_WAIT)
    private val INTRO_PAGE_START_MARKERS = listOf(0xAE43, MARKER_MOVIE1, MARKER_MOVIE2, MARKER_MOVIE3, MARKER_MOVIE4, MARKER_WAIT)
    private val INTRO_RUN_MARKERS = setOf(
        0xAE43, 0xAE5B, MARKER_MOVIE1, 0xAE91,
        MARKER_MOVIE2, 0xB08C, MARKER_MOVIE3, 0xB0CB,
        MARKER_MOVIE4, 0xB1B3, MARKER_WAIT, 0xADD4, 0xB240,
    )
    private val INTRO_START_RECORD = byteArrayOf(
        0x01.toByte(), 0x00.toByte(), 0x01.toByte(), 0x01.toByte(), 0x83.toByte(), 0xD6.toByte(),
    )

    // Area name tile encoding: 0x30='A', 0x31='B', ..., 0x49='Z'
    // Blank/padding = 0x01, space separator = 0x01
    // Each area name: 12 entries × 2 bytes (tile index + properties) = 24 bytes
    const val AREA_NAMES_PC = 0x1166F
    const val AREA_NAMES_SNES = 0x82966F  // $82:966F
    const val AREA_NAME_ENTRY_SIZE = 24
    const val AREA_NAME_CHAR_COUNT = 12

    val AREA_LABELS = listOf(
        "Crateria", "Brinstar", "Norfair", "Wrecked Ship",
        "Maridia", "Tourian", "Ceres"
    )
    private val AREA_DEFAULT_TEXT = listOf(
        "CRATERIA", "BRINSTAR", "NORFAIR", "WRECKED SHIP",
        "MARIDIA", "TOURIAN", "COLONY"
    )

    // Escape timer messages — mostly ASCII with control bytes
    const val CERES_ESCAPE_PC = 0x134450
    const val CERES_ESCAPE_SNES = 0xA6C450
    const val CERES_ESCAPE_SIZE = 76

    const val TOURIAN_ESCAPE_PC = 0x13449C
    const val TOURIAN_ESCAPE_SNES = 0xA6C49C
    const val TOURIAN_ESCAPE_SIZE = 58

    // ─── In-game UI messages ───────────────────────────────────
    // Stored as 32-tile tilemap rows (64 bytes each) in bank $85.
    // Character encoding: A=0xE0..Z=0xF9, period=0xFA, space=0x4E
    // Each tile entry is 2 bytes: [tile_index, palette_byte (0x3C for text)]
    // Padding/blank tiles use 0x0E with palette 0x00.
    const val UI_MSG_TILE_COLS = 32
    const val UI_MSG_ROW_BYTES = 64  // 32 tiles × 2 bytes
    const val UI_MSG_TEXT_PALETTE = 0x3C

    data class UiMessageDef(val id: String, val label: String, val pc: Int, val snes: Int, val maxChars: Int)

    val UI_MESSAGES = listOf(
        UiMessageDef("energy_recharge",   "Energy Recharge",     0x2923F, 0x85923F, 32),
        UiMessageDef("energy_done",       "Completed (Energy)",  0x292BF, 0x8592BF, 32),
        UiMessageDef("missile_reload",    "Missile Reload",      0x292FF, 0x8592FF, 32),
        UiMessageDef("missile_done",      "Completed (Missile)", 0x2937F, 0x85937F, 32),
        UiMessageDef("save_prompt_1",     "Save Prompt Line 1",  0x293BF, 0x8593BF, 32),
        UiMessageDef("save_prompt_2",     "Save Prompt Line 2",  0x293FF, 0x8593FF, 32),
        UiMessageDef("save_done",         "Save Completed",      0x294BF, 0x8594BF, 32),
        UiMessageDef("reserve_label",     "Reserve Tank Label",  0x294FF, 0x8594FF, 32),
        UiMessageDef("gravity_label",     "Gravity Suit Label",  0x2953F, 0x85953F, 32),
    )

    /** Decode a UI/item message row from ROM. Reads up to maxTiles tile entries, extracts text. */
    fun decodeUiMessage(rom: ByteArray, pc: Int, maxTiles: Int = UI_MSG_TILE_COLS): String {
        val sb = StringBuilder()
        for (col in 0 until maxTiles) {
            val off = pc + col * 2
            if (off + 1 >= rom.size) break
            val tile = rom[off].toInt() and 0xFF
            sb.append(uiTileToChar(tile))
        }
        return sb.toString().trim()
    }

    /** Encode a UI message string back to tilemap bytes, preserving non-text tiles. */
    fun encodeUiMessage(text: String, originalBytes: ByteArray): ByteArray {
        val result = originalBytes.copyOf()
        val upper = text.uppercase()
        val tileCount = originalBytes.size / 2
        // Find the range of text tiles in the original row (first..last non-padding tile)
        var firstText = -1
        var lastText = -1
        for (col in 0 until tileCount) {
            val tile = originalBytes[col * 2].toInt() and 0xFF
            if (tile in 0xE0..0xFA || tile == 0x4E) {
                if (firstText < 0) firstText = col
                lastText = col
            }
        }
        if (firstText < 0) { firstText = 6; lastText = 25 } // default centered range
        val fieldWidth = lastText - firstText + 1
        val padded = upper.take(fieldWidth).padEnd(fieldWidth)
        for (i in padded.indices) {
            val col = firstText + i
            val off = col * 2
            result[off] = uiCharToTile(padded[i]).toByte()
            result[off + 1] = UI_MSG_TEXT_PALETTE.toByte()
        }
        return result
    }

    private fun uiTileToChar(tile: Int): Char = when {
        tile in 0xE0..0xF9 -> ('A' + (tile - 0xE0))
        tile == 0xFA || tile == 0xFE -> '.'
        tile == 0xCF -> '-'  // hyphen tile (used in HI-JUMP, X-RAY)
        tile == 0x4E -> ' '
        tile == 0x0E || tile == 0x00 -> ' '
        else -> ' '
    }

    private fun uiCharToTile(ch: Char): Int = when {
        ch in 'A'..'Z' -> 0xE0 + (ch - 'A')
        ch in 'a'..'z' -> 0xE0 + (ch - 'a')
        ch == '.' -> 0xFA
        ch == '-' -> 0xCF
        ch == ' ' -> 0x4E
        else -> 0x4E // unknown → space
    }

    // ─── Item pickup names ──────────────────────────────────────
    // Item names are embedded as text tiles within BG3 tilemap data at $85:876B+.
    // Same encoding as UI messages: A=0xE0..Z=0xF9, space=0x4E, period=0xFA.
    // Each name occupies a fixed-width tile field within the larger tilemap.
    // Addresses found by scanning the tilemap for text character tile runs.

    data class ItemNameDef(val id: String, val label: String, val pc: Int, val snes: Int, val tileCount: Int)

    val ITEM_NAMES = listOf(
        ItemNameDef("item_energy_tank",   "Energy Tank",    0x28793, 0x858793, 15),
        ItemNameDef("item_missile",       "Missile",        0x287D9, 0x8587D9, 16),
        ItemNameDef("item_super_missile", "Super Missile",  0x288D1, 0x8588D1, 20),
        ItemNameDef("item_power_bomb",    "Power Bomb",     0x289D5, 0x8589D5, 18),
        ItemNameDef("item_grapple_beam",  "Grapple Beam",   0x28AD1, 0x858AD1, 20),
        ItemNameDef("item_xray_scope",    "X-Ray Scope",    0x28BD5, 0x858BD5, 18),
        ItemNameDef("item_varia_suit",    "Varia Suit",     0x28CD3, 0x858CD3, 15),
        ItemNameDef("item_spring_ball",   "Spring Ball",    0x28D13, 0x858D13, 15),
        ItemNameDef("item_morphing_ball", "Morphing Ball",  0x28D51, 0x858D51, 16),
        ItemNameDef("item_screw_attack",  "Screw Attack",   0x28D91, 0x858D91, 16),
        ItemNameDef("item_hijump_boots",  "Hi-Jump Boots",  0x28DD1, 0x858DD1, 16),
        ItemNameDef("item_space_jump",    "Space Jump",     0x28E13, 0x858E13, 15),
        ItemNameDef("item_speed_booster", "Speed Booster",  0x28E51, 0x858E51, 20),
        ItemNameDef("item_charge_beam",   "Charge Beam",    0x28F53, 0x858F53, 15),
        ItemNameDef("item_ice_beam",      "Ice Beam",       0x28F95, 0x858F95, 14),
        ItemNameDef("item_wave_beam",     "Wave Beam",      0x28FD5, 0x858FD5, 14),
        ItemNameDef("item_spazer",        "Spazer",         0x29017, 0x859017, 13),
        ItemNameDef("item_plasma_beam",   "Plasma Beam",    0x29053, 0x859053, 15),
        ItemNameDef("item_bomb",          "Bomb",           0x2909B, 0x85909B, 15),
    )

    /** Decode an area name from ROM bytes. */
    fun decodeAreaName(rom: ByteArray, areaIndex: Int): String {
        val offset = resolveAreaNameEntries(rom).getOrNull(areaIndex)?.pcOffset
            ?: (AREA_NAMES_PC + areaIndex * AREA_NAME_ENTRY_SIZE)
        if (offset < 0 || offset + AREA_NAME_ENTRY_SIZE > rom.size) {
            return AREA_DEFAULT_TEXT.getOrElse(areaIndex) { "" }
        }
        return decodeAreaNameAt(rom, offset)
    }

    private fun decodeAreaNameAt(rom: ByteArray, offset: Int): String {
        val sb = StringBuilder()
        for (i in 0 until AREA_NAME_CHAR_COUNT) {
            val tile = rom[offset + i * 2].toInt() and 0xFF
            sb.append(tileToChar(tile))
        }
        return sb.toString().trim()
    }

    /** Encode an area name string back to ROM bytes. Returns 24 bytes. */
    fun encodeAreaName(name: String, originalBytes: ByteArray): ByteArray {
        val result = originalBytes.copyOf()
        val padded = name.uppercase().padEnd(AREA_NAME_CHAR_COUNT)
        for (i in 0 until AREA_NAME_CHAR_COUNT) {
            val ch = if (i < padded.length) padded[i] else ' '
            result[i * 2] = charToTile(ch).toByte()
            // Preserve original properties byte (result[i * 2 + 1] unchanged)
        }
        return result
    }

    /** Decode escape text from ROM bytes. Control bytes are stripped. */
    fun decodeEscapeText(rom: ByteArray, pc: Int, size: Int): String {
        val sb = StringBuilder()
        var i = 0
        var atLineStart = true
        var lineControlSkipped = false
        var sawVisible = false
        while (i < size && pc + i < rom.size) {
            val b = rom[pc + i].toInt() and 0xFF
            val controlLength = if (atLineStart && !lineControlSkipped) {
                escapeLineControlLength(rom, pc, size, i)
            } else {
                0
            }
            when {
                b == 0x00 -> {
                    if (sawVisible && i + 1 < size && pc + i + 1 < rom.size &&
                        (rom[pc + i + 1].toInt() and 0xFF) == 0x00
                    ) {
                        break
                    }
                    i++
                }
                b == 0x0D -> {
                    if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                    atLineStart = true
                    lineControlSkipped = false
                    i++
                }
                controlLength > 0 -> {
                    i += controlLength
                    lineControlSkipped = true
                }
                b in 0x20..0x7E -> {
                    sb.append(b.toChar())
                    sawVisible = true
                    atLineStart = false
                    i++
                }
                // Skip control bytes (0x00-0x1F except 0x0D)
                else -> i++
            }
        }
        return sb.toString()
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()
    }

    /** Encode escape text back to ROM bytes, preserving control byte positions. */
    fun encodeEscapeText(text: String, originalBytes: ByteArray): ByteArray {
        val result = originalBytes.copyOf()
        val upper = text.uppercase()
        var textIdx = 0
        var i = 0
        var atLineStart = true
        var lineControlSkipped = false
        var sawVisible = false
        while (i < result.size) {
            val orig = result[i].toInt() and 0xFF
            val controlLength = if (atLineStart && !lineControlSkipped) {
                escapeLineControlLength(result, 0, result.size, i)
            } else {
                0
            }
            when {
                orig == 0x00 -> {
                    if (sawVisible && i + 1 < result.size && (result[i + 1].toInt() and 0xFF) == 0x00) {
                        break
                    }
                    i++
                }
                orig == 0x0D -> {
                    // Keep newline control byte
                    if (textIdx < upper.length && upper[textIdx] == '\n') textIdx++
                    atLineStart = true
                    lineControlSkipped = false
                    i++
                }
                controlLength > 0 -> {
                    i += controlLength
                    lineControlSkipped = true
                }
                orig in 0x20..0x7E -> {
                    while (textIdx < upper.length && upper[textIdx] == '\n') textIdx++
                    // Replace with new character or space if text is shorter
                    result[i] = if (textIdx < upper.length) {
                        val ch = upper[textIdx++]
                        if (ch in ' '..'~') ch.code.toByte() else 0x20.toByte()
                    } else {
                        0x20.toByte() // pad with spaces
                    }
                    atLineStart = false
                    sawVisible = true
                    i++
                }
                // Preserve control bytes as-is
                else -> i++
            }
        }
        return result
    }

    private fun escapeLineControlLength(bytes: ByteArray, pc: Int, size: Int, localIndex: Int): Int {
        if (localIndex + 1 >= size || pc + localIndex + 1 >= bytes.size) return 0
        val command = bytes[pc + localIndex].toInt() and 0xFF
        val parameter = bytes[pc + localIndex + 1].toInt() and 0xFF
        if (parameter !in 0x20..0x7E) return 0
        return when (command) {
            0x05, 0x85 -> 2
            0x45 -> 2
            else -> 0
        }
    }

    /**
     * Encode edited green text back into ROM format.
     * Replaces character pointers in existing 6-byte records while preserving
     * delay, X, Y positions and marker records. Text is mapped character-by-character
     * to the original records — the section must have the same number of visible
     * characters (extra chars are ignored, missing chars become spaces).
     */
    fun encodeGreenText(newText: String, originalBytes: ByteArray): ByteArray {
        val result = originalBytes.copyOf()
        val recordCount = originalBytes.size / GREEN_TEXT_RECORD_SIZE
        // Flatten the new text into a list of characters (skip newlines — Y positions handle line breaks)
        val chars = newText.uppercase().replace("\n", "").toList()
        var charIdx = 0

        for (i in 0 until recordCount) {
            val off = i * GREEN_TEXT_RECORD_SIZE
            if (off + GREEN_TEXT_RECORD_SIZE > result.size) break
            val charPtr = (result[off + 4].toInt() and 0xFF) or ((result[off + 5].toInt() and 0xFF) shl 8)

            // Skip section markers and line break markers
            if (charPtr in SECTION_MARKERS || charPtr == MARKER_NEW) continue

            // This is a character record — replace the pointer
            val ch = if (charIdx < chars.size) chars[charIdx] else ' '
            charIdx++
            val newPtr = CHAR_TO_POINTER[ch] ?: CHAR_TO_POINTER[' '] ?: 0xD67D
            result[off + 4] = (newPtr and 0xFF).toByte()
            result[off + 5] = ((newPtr shr 8) and 0xFF).toByte()
        }
        return result
    }

    /** Decode green text records into story sections (split by markers). */
    fun decodeGreenText(rom: ByteArray): List<GreenTextSection> {
        val fixed = decodeContiguousGreenText(rom)
        if (greenTextLooksValid(fixed)) return fixed

        val relocated = decodeRelocatedGreenText(rom)
        return if (greenTextLooksValid(relocated)) relocated else fixed
    }

    private fun decodeContiguousGreenText(rom: ByteArray): List<GreenTextSection> {
        val sections = mutableListOf<GreenTextSection>()
        val sectionNames = listOf("Intro Part 1", "Intro Part 2", "Intro Part 3", "Intro Part 4", "Intro Part 5", "Intro Part 6")
        var currentLines = mutableListOf<String>()
        var currentLine = StringBuilder()
        var lastY = -1
        var sectionStart = GREEN_TEXT_PC
        var sectionIdx = 0

        for (i in 0 until GREEN_TEXT_MAX_RECORDS) {
            val off = GREEN_TEXT_PC + i * GREEN_TEXT_RECORD_SIZE
            if (off + GREEN_TEXT_RECORD_SIZE > rom.size) break
            val charPtr = (rom[off + 4].toInt() and 0xFF) or ((rom[off + 5].toInt() and 0xFF) shl 8)

            if (charPtr in SECTION_MARKERS) {
                // Flush current line
                if (currentLine.isNotEmpty()) currentLines.add(currentLine.toString())
                if (currentLines.isNotEmpty() || sectionIdx == 0) {
                    val rawSize = off + GREEN_TEXT_RECORD_SIZE - sectionStart
                    sections.add(GreenTextSection(
                        name = sectionNames.getOrElse(sectionIdx) { "Part ${sectionIdx + 1}" },
                        text = currentLines.joinToString("\n"),
                        pcOffset = sectionStart,
                        snesAddress = GREEN_TEXT_SNES + (sectionStart - GREEN_TEXT_PC),
                        rawSize = rawSize,
                    ))
                    sectionIdx++
                }
                currentLines = mutableListOf()
                currentLine = StringBuilder()
                lastY = -1
                sectionStart = off + GREEN_TEXT_RECORD_SIZE
                continue
            }

            if (charPtr == MARKER_NEW) continue // line break marker within section

            val y = rom[off + 3].toInt() and 0xFF
            val ch = CHAR_POINTER_MAP[charPtr] ?: '?'

            if (lastY >= 0 && y != lastY) {
                currentLines.add(currentLine.toString())
                currentLine = StringBuilder()
            }
            currentLine.append(ch)
            lastY = y
        }
        // Flush remaining
        if (currentLine.isNotEmpty()) currentLines.add(currentLine.toString())
        if (currentLines.isNotEmpty()) {
            sections.add(GreenTextSection(
                name = sectionNames.getOrElse(sectionIdx) { "Part ${sectionIdx + 1}" },
                text = currentLines.joinToString("\n"),
                pcOffset = sectionStart,
                snesAddress = GREEN_TEXT_SNES + (sectionStart - GREEN_TEXT_PC),
                rawSize = GREEN_TEXT_PC + GREEN_TEXT_MAX_RECORDS * GREEN_TEXT_RECORD_SIZE - sectionStart,
            ))
        }
        return sections
    }

    private fun decodeRelocatedGreenText(rom: ByteArray): List<GreenTextSection> {
        val sectionNames = listOf("Intro Part 1", "Intro Part 2", "Intro Part 3", "Intro Part 4", "Intro Part 5", "Intro Part 6")
        return INTRO_PAGE_START_MARKERS.mapIndexedNotNull { index, marker ->
            findIntroPageStarts(rom, marker)
                .asSequence()
                .map { startPc -> decodeGreenTextPageAt(rom, startPc, sectionNames[index]) }
                .filter { it.text.isNotBlank() }
                .minByOrNull { section -> greenTextScore(section.text) }
        }
    }

    private fun findIntroPageStarts(rom: ByteArray, marker: Int): List<Int> {
        val lo = marker and 0xFF
        val hi = (marker shr 8) and 0xFF
        val starts = mutableListOf<Int>()
        var pc = 0
        while (pc + 2 + INTRO_START_RECORD.size <= rom.size) {
            if ((rom[pc].toInt() and 0xFF) == lo && (rom[pc + 1].toInt() and 0xFF) == hi &&
                matchesIntroStartRecord(rom, pc + 2)
            ) {
                starts += pc + 2
                pc += 2 + INTRO_START_RECORD.size
            } else {
                pc++
            }
        }
        return starts
    }

    private fun matchesIntroStartRecord(rom: ByteArray, pc: Int): Boolean {
        if (pc < 0 || pc + INTRO_START_RECORD.size > rom.size) return false
        for (i in INTRO_START_RECORD.indices) {
            if (rom[pc + i] != INTRO_START_RECORD[i]) return false
        }
        return true
    }

    private fun decodeGreenTextPageAt(rom: ByteArray, startPc: Int, name: String): GreenTextSection {
        val currentLines = mutableListOf<String>()
        val currentLine = StringBuilder()
        var lastY = -1
        var off = startPc
        var records = 0

        while (records < GREEN_TEXT_MAX_RECORDS && off + GREEN_TEXT_RECORD_SIZE <= rom.size) {
            val charPtr = (rom[off + 4].toInt() and 0xFF) or ((rom[off + 5].toInt() and 0xFF) shl 8)
            if (charPtr in INTRO_RUN_MARKERS) break
            if (!isPlausibleGreenTextRecord(rom, off, charPtr)) break

            if (charPtr == MARKER_NEW) {
                off += GREEN_TEXT_RECORD_SIZE
                records++
                continue
            }

            val y = rom[off + 3].toInt() and 0xFF
            val ch = CHAR_POINTER_MAP[charPtr] ?: '?'
            if (lastY >= 0 && y != lastY) {
                currentLines.add(currentLine.toString())
                currentLine.clear()
            }
            currentLine.append(ch)
            lastY = y
            off += GREEN_TEXT_RECORD_SIZE
            records++
        }

        if (currentLine.isNotEmpty()) currentLines.add(currentLine.toString())
        return GreenTextSection(
            name = name,
            text = currentLines.joinToString("\n"),
            pcOffset = startPc,
            snesAddress = pcToGreenTextSnes(startPc),
            rawSize = off - startPc,
        )
    }

    private fun isPlausibleGreenTextRecord(rom: ByteArray, off: Int, charPtr: Int): Boolean {
        if (charPtr != MARKER_NEW && charPtr !in CHAR_POINTER_MAP) return false
        val delay = (rom[off].toInt() and 0xFF) or ((rom[off + 1].toInt() and 0xFF) shl 8)
        val x = rom[off + 2].toInt() and 0xFF
        val y = rom[off + 3].toInt() and 0xFF
        return delay in 0..0x20 && x in 0..0x30 && y in 0..0x30
    }

    private fun greenTextLooksValid(sections: List<GreenTextSection>): Boolean {
        if (sections.size < 6) return false
        val text = sections.joinToString("\n") { it.text }
        if (text.length < 100) return false
        val unknowns = text.count { it == '?' }
        return unknowns * 20 < text.length
    }

    private fun greenTextScore(text: String): Int {
        val unknownPenalty = text.count { it == '?' } * 100
        val lengthPenalty = kotlin.math.abs(260 - text.count { it != '\n' })
        return unknownPenalty + lengthPenalty
    }

    private fun pcToGreenTextSnes(pc: Int): Int {
        val bank = pc / 0x8000 + 0x80
        val addr = pc % 0x8000 + 0x8000
        return (bank shl 16) or addr
    }

    data class GreenTextSection(
        val name: String,
        val text: String,
        val pcOffset: Int,
        val snesAddress: Int,
        val rawSize: Int,
    )

    /** Read all text entries from the ROM. */
    fun readAllText(rom: ByteArray): List<TextEntry> {
        val entries = mutableListOf<TextEntry>()

        // Intro story (green text sections)
        val greenSections = decodeGreenText(rom)
        for ((idx, section) in greenSections.withIndex()) {
            val raw = if (section.pcOffset + section.rawSize <= rom.size)
                rom.copyOfRange(section.pcOffset, section.pcOffset + section.rawSize)
            else ByteArray(0)
            entries.add(TextEntry(
                id = "intro_$idx",
                label = section.name,
                category = TextCategory.INTRO_STORY,
                pcOffset = section.pcOffset,
                snesAddress = section.snesAddress,
                maxLength = section.text.length + 20, // allow some extra
                text = section.text,
                rawBytes = raw,
            ))
        }

        // Area names
        val areaNameEntries = resolveAreaNameEntries(rom)
        for (i in 0 until AREA_LABELS.size) {
            val areaName = areaNameEntries[i]
            entries.add(TextEntry(
                id = "area_$i",
                label = AREA_LABELS[i],
                category = TextCategory.AREA_NAME,
                pcOffset = areaName.pcOffset,
                snesAddress = areaName.snesAddress,
                maxLength = AREA_NAME_CHAR_COUNT,
                text = areaName.text,
                rawBytes = areaName.rawBytes,
                writable = areaName.writable,
            ))
        }

        // Escape text
        for ((id, label, pc, snes, size) in listOf(
            EscapeDef("ceres_escape", "Ceres Escape", CERES_ESCAPE_PC, CERES_ESCAPE_SNES, CERES_ESCAPE_SIZE),
            EscapeDef("tourian_escape", "Tourian Escape", TOURIAN_ESCAPE_PC, TOURIAN_ESCAPE_SNES, TOURIAN_ESCAPE_SIZE),
        )) {
            val raw = rom.copyOfRange(pc, pc + size)
            entries.add(TextEntry(
                id = id,
                label = label,
                category = TextCategory.ESCAPE_TEXT,
                pcOffset = pc,
                snesAddress = snes,
                maxLength = size,
                text = decodeEscapeText(rom, pc, size),
                rawBytes = raw,
            ))
        }

        // In-game UI messages (save station, refill station, etc.)
        for (def in UI_MESSAGES) {
            val raw = rom.copyOfRange(def.pc, def.pc + UI_MSG_ROW_BYTES)
            entries.add(TextEntry(
                id = def.id,
                label = def.label,
                category = TextCategory.UI_MESSAGE,
                pcOffset = def.pc,
                snesAddress = def.snes,
                maxLength = def.maxChars,
                text = decodeUiMessage(rom, def.pc),
                rawBytes = raw,
            ))
        }

        // Item pickup names (embedded in BG3 tilemap, same character encoding as UI messages)
        for (def in ITEM_NAMES) {
            val rawSize = def.tileCount * 2
            val raw = if (def.pc + rawSize <= rom.size) rom.copyOfRange(def.pc, def.pc + rawSize) else ByteArray(0)
            entries.add(TextEntry(
                id = def.id,
                label = def.label,
                category = TextCategory.ITEM_NAME,
                pcOffset = def.pc,
                snesAddress = def.snes,
                maxLength = def.tileCount,
                text = decodeUiMessage(rom, def.pc, def.tileCount),
                rawBytes = raw,
            ))
        }

        return entries
    }

    private data class EscapeDef(val id: String, val label: String, val pc: Int, val snes: Int, val size: Int)

    private data class AreaNameEntry(
        val pcOffset: Int,
        val snesAddress: Int,
        val text: String,
        val rawBytes: ByteArray,
        val writable: Boolean,
    )

    private fun resolveAreaNameEntries(rom: ByteArray): List<AreaNameEntry> {
        val vanilla = (0 until AREA_LABELS.size).map { i ->
            val pc = AREA_NAMES_PC + i * AREA_NAME_ENTRY_SIZE
            areaNameEntryAt(rom, pc, fallbackText = AREA_DEFAULT_TEXT[i])
        }
        if (vanilla.all { it.writable && areaNameRawLooksValid(it.rawBytes) }) {
            return vanilla
        }

        return AREA_DEFAULT_TEXT.map { expected ->
            val pc = findAreaNamePcByText(rom, expected)
            if (pc >= 0) {
                areaNameEntryAt(rom, pc, fallbackText = expected)
            } else {
                AreaNameEntry(
                    pcOffset = -1,
                    snesAddress = 0,
                    text = expected,
                    rawBytes = ByteArray(0),
                    writable = false,
                )
            }
        }
    }

    private fun areaNameEntryAt(rom: ByteArray, pc: Int, fallbackText: String): AreaNameEntry {
        if (pc < 0 || pc + AREA_NAME_ENTRY_SIZE > rom.size) {
            return AreaNameEntry(-1, 0, fallbackText, ByteArray(0), writable = false)
        }
        val raw = rom.copyOfRange(pc, pc + AREA_NAME_ENTRY_SIZE)
        val text = decodeAreaNameAt(rom, pc)
        return AreaNameEntry(
            pcOffset = pc,
            snesAddress = pcToSnes(rom, pc),
            text = text.ifBlank { fallbackText },
            rawBytes = raw,
            writable = true,
        )
    }

    private fun findAreaNamePcByText(rom: ByteArray, expected: String): Int {
        val limit = rom.size - AREA_NAME_ENTRY_SIZE
        for (pc in 0..limit) {
            if (!areaNameRawLooksValid(rom, pc)) continue
            if (decodeAreaNameAt(rom, pc) == expected) return pc
        }
        return -1
    }

    private fun areaNameRawLooksValid(raw: ByteArray): Boolean {
        if (raw.size < AREA_NAME_ENTRY_SIZE) return false
        var letters = 0
        var validProps = 0
        for (i in 0 until AREA_NAME_CHAR_COUNT) {
            val tile = raw[i * 2].toInt() and 0xFF
            val props = raw[i * 2 + 1].toInt() and 0xFF
            if (!(tile == 0x01 || tile in 0x30..0x49)) return false
            if (tile in 0x30..0x49) letters++
            if (props == 0x28 || props == 0x38) validProps++
        }
        return letters >= 3 && validProps >= AREA_NAME_CHAR_COUNT - 1
    }

    private fun areaNameRawLooksValid(rom: ByteArray, pc: Int): Boolean {
        if (pc < 0 || pc + AREA_NAME_ENTRY_SIZE > rom.size) return false
        var letters = 0
        var validProps = 0
        for (i in 0 until AREA_NAME_CHAR_COUNT) {
            val tile = rom[pc + i * 2].toInt() and 0xFF
            val props = rom[pc + i * 2 + 1].toInt() and 0xFF
            if (!(tile == 0x01 || tile in 0x30..0x49)) return false
            if (tile in 0x30..0x49) letters++
            if (props == 0x28 || props == 0x38) validProps++
        }
        return letters >= 3 && validProps >= AREA_NAME_CHAR_COUNT - 1
    }

    private fun pcToSnes(rom: ByteArray, pcOffset: Int): Int {
        val startOffset = if (rom.size % 0x8000 == 0x200) 0x200 else 0
        val adjusted = pcOffset - startOffset
        if (adjusted < 0) return 0
        val bank = (adjusted / 0x8000) or 0x80
        val offset = (adjusted % 0x8000) + 0x8000
        return (bank shl 16) or offset
    }

    private fun tileToChar(tile: Int): Char = when {
        tile in 0x30..0x49 -> ('A' + (tile - 0x30))
        tile == 0x01 || tile == 0x00 -> ' '
        else -> ' '
    }

    private fun charToTile(ch: Char): Int = when {
        ch in 'A'..'Z' -> 0x30 + (ch - 'A')
        ch in 'a'..'z' -> 0x30 + (ch - 'a')
        ch == ' ' -> 0x01
        else -> 0x01 // unknown → blank
    }
}

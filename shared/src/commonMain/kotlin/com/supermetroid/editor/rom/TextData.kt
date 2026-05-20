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
    ITEM_NAME("Item Names"),
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

    // Escape timer messages — mostly ASCII with control bytes
    const val CERES_ESCAPE_PC = 0x134450
    const val CERES_ESCAPE_SNES = 0xA6C450
    const val CERES_ESCAPE_SIZE = 76

    const val TOURIAN_ESCAPE_PC = 0x13449C
    const val TOURIAN_ESCAPE_SNES = 0xA6C49C
    const val TOURIAN_ESCAPE_SIZE = 58

    // Item pickup names displayed in the HUD message box
    // Tilemap format: each char is 2 bytes (tile + properties)
    // Tile encoding: 0x30='A', same as area names
    // Each item name: 8 chars × 2 bytes = 16 bytes, but stored inline in the HUD tilemap
    // Located via pointer table at $85:878B — not simple contiguous text.
    // Instead we define the known item name locations directly.

    data class ItemNameDef(val label: String, val pc: Int, val snes: Int, val charCount: Int)

    // Item pickup message locations in bank $85.
    // These are 2-line messages: line 1 = item category, line 2 = item name.
    // Stored as tilemap rows. Addresses from disassembly.
    val ITEM_NAMES: List<ItemNameDef> = listOf(
        // HUD message tilemaps in bank $85 — these are the 2×32 tilemap rows
        // Each tilemap row is 64 bytes (32 tiles × 2 bytes).
        // The text occupies the center tiles within each row.
        // For simplicity, we expose area names and escape text first,
        // and item names as a future enhancement (more complex format).
    )

    /** Decode an area name from ROM bytes. */
    fun decodeAreaName(rom: ByteArray, areaIndex: Int): String {
        val offset = AREA_NAMES_PC + areaIndex * AREA_NAME_ENTRY_SIZE
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
        for (i in 0 until size) {
            val b = rom[pc + i].toInt() and 0xFF
            when {
                b == 0x0D -> sb.append('\n')
                b in 0x20..0x7E -> sb.append(b.toChar())
                // Skip control bytes (0x00-0x1F except 0x0D)
            }
        }
        return sb.toString().trim()
    }

    /** Encode escape text back to ROM bytes, preserving control byte positions. */
    fun encodeEscapeText(text: String, originalBytes: ByteArray): ByteArray {
        val result = originalBytes.copyOf()
        var textIdx = 0
        val lines = text.uppercase()
        for (i in result.indices) {
            val orig = result[i].toInt() and 0xFF
            when {
                orig == 0x0D -> {
                    // Keep newline control byte
                    if (textIdx < lines.length && lines[textIdx] == '\n') textIdx++
                }
                orig in 0x20..0x7E -> {
                    // Replace with new character or space if text is shorter
                    result[i] = if (textIdx < lines.length && lines[textIdx] != '\n') {
                        val ch = lines[textIdx++]
                        if (ch in ' '..'~') ch.code.toByte() else 0x20.toByte()
                    } else {
                        textIdx++
                        0x20.toByte() // pad with spaces
                    }
                }
                // Preserve control bytes as-is
            }
        }
        return result
    }

    /** Decode green text records into story sections (split by markers). */
    fun decodeGreenText(rom: ByteArray): List<GreenTextSection> {
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
                rawSize = GREEN_TEXT_PC + GREEN_TEXT_MAX_RECORDS * GREEN_TEXT_RECORD_SIZE - sectionStart,
            ))
        }
        return sections
    }

    data class GreenTextSection(val name: String, val text: String, val pcOffset: Int, val rawSize: Int)

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
                snesAddress = GREEN_TEXT_SNES + (section.pcOffset - GREEN_TEXT_PC),
                maxLength = section.text.length + 20, // allow some extra
                text = section.text,
                rawBytes = raw,
            ))
        }

        // Area names
        for (i in 0 until AREA_LABELS.size) {
            val pc = AREA_NAMES_PC + i * AREA_NAME_ENTRY_SIZE
            val raw = rom.copyOfRange(pc, pc + AREA_NAME_ENTRY_SIZE)
            entries.add(TextEntry(
                id = "area_$i",
                label = AREA_LABELS[i],
                category = TextCategory.AREA_NAME,
                pcOffset = pc,
                snesAddress = AREA_NAMES_SNES + i * AREA_NAME_ENTRY_SIZE,
                maxLength = AREA_NAME_CHAR_COUNT,
                text = decodeAreaName(rom, i),
                rawBytes = raw,
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

        return entries
    }

    private data class EscapeDef(val id: String, val label: String, val pc: Int, val snes: Int, val size: Int)

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

package com.supermetroid.editor.rom

/** Exact scene-map shapes exposed by the existing visual Scenes workspace. */
object CinematicMapAssets {
    enum class EntryFormat(val bytesPerEntry: Int) {
        MODE7_BYTE(1),
        SNES_BG_WORD(2),
    }

    data class Asset(
        val name: String,
        val address: Int,
        val sourceLabel: String,
        val format: EntryFormat,
        val frames: List<Frame>,
    ) {
        data class Frame(val name: String, val columns: Int, val rows: Int)

        val key: String get() = address.toString(16).uppercase().padStart(6, '0')
        val rawBytes: Int get() = frames.sumOf(::frameBytes)

        fun read(parser: RomParser): ByteArray = parser.decompressLZ2(address).also(::validate)

        fun validate(raw: ByteArray) {
            require(raw.size == rawBytes) {
                "$name requires exactly $rawBytes decoded bytes across ${frames.size} proven map frame(s); " +
                    "received ${raw.size}."
            }
        }

        fun frame(index: Int): Frame = frames.getOrNull(index)
            ?: throw IllegalArgumentException("$name has no map frame ${index + 1}")

        fun frameBytes(frame: Frame): Int = frame.columns * frame.rows * format.bytesPerEntry

        fun frameData(raw: ByteArray, frameIndex: Int): ByteArray {
            validate(raw)
            val offset = frameByteOffset(frameIndex)
            return raw.copyOfRange(offset, offset + frameBytes(frame(frameIndex)))
        }

        fun rawIndex(x: Int, y: Int, frameIndex: Int = 0): Int {
            val frame = frame(frameIndex)
            require(x in 0 until frame.columns && y in 0 until frame.rows) {
                "$name ${frame.name} cell ($x,$y) is outside ${frame.columns}×${frame.rows}"
            }
            val entryOffset = frameByteOffset(frameIndex) / format.bytesPerEntry
            if (format == EntryFormat.MODE7_BYTE) return entryOffset + y * frame.columns + x
            val screensPerRow = frame.columns / 32
            require(frame.columns % 32 == 0 && frame.rows % 32 == 0) {
                "$name BG frame dimensions must use complete 32×32 screens"
            }
            val screen = (y / 32) * screensPerRow + x / 32
            return entryOffset + screen * 1024 + (y % 32) * 32 + x % 32
        }

        fun entry(raw: ByteArray, x: Int, y: Int, frameIndex: Int = 0): Int {
            validate(raw)
            val index = rawIndex(x, y, frameIndex) * format.bytesPerEntry
            return if (format == EntryFormat.MODE7_BYTE) raw[index].toInt() and 0xFF
            else (raw[index].toInt() and 0xFF) or ((raw[index + 1].toInt() and 0xFF) shl 8)
        }

        fun withEntry(raw: ByteArray, x: Int, y: Int, value: Int, frameIndex: Int = 0): ByteArray {
            validate(raw)
            val limit = if (format == EntryFormat.MODE7_BYTE) 0xFF else 0xFFFF
            require(value in 0..limit) { "$name entry must fit ${format.bytesPerEntry * 8} bits" }
            val updated = raw.copyOf()
            val index = rawIndex(x, y, frameIndex) * format.bytesPerEntry
            updated[index] = value.toByte()
            if (format == EntryFormat.SNES_BG_WORD) updated[index + 1] = (value ushr 8).toByte()
            return updated
        }

        private fun frameByteOffset(frameIndex: Int): Int {
            frame(frameIndex)
            return frames.take(frameIndex).sumOf(::frameBytes)
        }
    }

    data class RegionalInventory(
        val name: String,
        val sourceLabel: String,
        val region: String,
        val boundary: String,
    )

    val SUPPORTED: List<Asset> = listOf(
        Asset("Title Mode 7 layout", 0x96FC04, "Title_Mode7_Tilemap", EntryFormat.MODE7_BYTE, listOf(
            Asset.Frame("Title background", 128, 32),
        )),
        Asset("Ceres / gunship Mode 7 layouts", 0x96FE69, "Gunship_Ceres_Tilemap", EntryFormat.MODE7_BYTE, listOf(
            Asset.Frame("Gunship distress · frame 1", 128, 6),
            Asset.Frame("Gunship distress · frame 2", 128, 6),
            Asset.Frame("Ceres explosion / flight", 128, 12),
            Asset.Frame("Clear map", 128, 8),
        )),
        Asset("Intro Mother Brain room layouts", 0x96FF14, "Intro_BG1_MotherBrainsRoom_Tilemap", EntryFormat.SNES_BG_WORD, listOf(
            Asset.Frame("Old Mother Brain room", 32, 32),
            Asset.Frame("Baby Metroid discovery", 32, 32),
            Asset.Frame("Baby Metroid delivery", 32, 32),
            Asset.Frame("Baby Metroid examination", 32, 32),
        )),
        Asset("Intro Samus portrait layout", 0x9788CC, "Intro_BG2_SamusHead_Tilemap", EntryFormat.SNES_BG_WORD, listOf(
            Asset.Frame("Samus portrait", 32, 32),
        )),
        Asset("Ending Samus waiting layout", 0x9796F4, "Samus_Waiting_for_Credits_to_End_Tilemap", EntryFormat.SNES_BG_WORD, listOf(
            Asset.Frame("Samus waiting", 32, 32),
        )),
        Asset("Post-credits Super Metroid icon layout", 0x99ECC4, "PostCredits_SuperMetroid_Icon_Tilemap", EntryFormat.SNES_BG_WORD, listOf(
            Asset.Frame("Super Metroid icon", 32, 32),
        )),
    )

    /** Explicitly inventoried so an NTSC scene editor is never presented as regional coverage. */
    val REGIONAL_INVENTORY: List<RegionalInventory> = listOf(
        RegionalInventory("Options screen · NTSC", "GameOptionsMenu_OptionsScreen_Tilemap", "NTSC", "No menu-layout visual selection yet"),
        RegionalInventory("Options screen · PAL", "GameOptionsMenu_OptionsScreen_Tilemap", "PAL conditional branch", "Active source branch must be modeled first"),
        RegionalInventory("Controller settings", "GameOptionsMenu_ControllerSettings_English_Tilemap", "English", "No menu-layout visual selection yet"),
        RegionalInventory("Controller settings", "GameOptionsMenu_ControllerSettings_Japanese_Tilemap", "Japanese", "Japanese font and encoding must stay paired"),
        RegionalInventory("Special settings", "GameOptionsMenu_SpecialSettings_English_Tilemap", "English", "No menu-layout visual selection yet"),
        RegionalInventory("Special settings", "GameOptionsMenu_SpecialSettings_Japanese_Tilemap", "Japanese", "Japanese font and encoding must stay paired"),
        RegionalInventory("Intro text/font path", "Tiles_Font2_BG3", "Japanese / regional", "Text controls and font encoding belong to 2C"),
        RegionalInventory("Credits map/font path", "Credits_Tilemap", "NTSC/PAL conditional branch", "Active branch and credits text model are not yet reciprocal"),
    )

    fun source(key: String): Asset = requireNotNull(SUPPORTED.singleOrNull { it.key == key }) {
        "Unknown cinematic map source '$key'"
    }

    fun forVisualAsset(asset: RomVisualAssets.Asset): Asset? =
        SUPPORTED.singleOrNull { it.address == asset.map }
}

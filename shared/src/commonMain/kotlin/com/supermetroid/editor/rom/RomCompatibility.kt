package com.supermetroid.editor.rom

import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.RomConstants.ROM_SIZE
import com.supermetroid.editor.rom.RomConstants.SMC_HEADER_SIZE
import kotlin.math.max
import kotlin.math.roundToInt

object RomCompatibility {
    private const val LOROM_HEADER_PC = 0x7FC0

    data class Report(
        val fileSize: Int,
        val smcHeaderOffset: Int,
        val headerlessSize: Int,
        val supportedForEditing: Boolean,
        val snesHeader: SnesHeader?,
        val expandedBytes: Int,
        val expandedFfPercent: Int?,
        val expandedZeroPercent: Int?,
        val vanillaRoomHeadersParsed: Int,
        val vanillaRoomHeadersTotal: Int,
        val candidateRoomHeadersInBank8F: Int,
        val candidateExpandedLevelPointers: Int,
    ) {
        val hasSmcHeader: Boolean get() = smcHeaderOffset == SMC_HEADER_SIZE

        fun userMessage(fileName: String? = null): String =
            buildString {
                appendLine("Unsupported ROM layout: editing disabled.")
                if (!fileName.isNullOrBlank()) {
                    appendLine("File: $fileName")
                }
                appendLine(
                    "SMEDIT currently supports vanilla-layout Super Metroid ROMs: " +
                        "3.00 MiB headerless (0x300000) or 3.00 MiB with a 512-byte SMC copier header."
                )
                appendLine(
                    "This ROM is ${formatBytes(fileSize)} (${hex(fileSize)} bytes), " +
                        if (hasSmcHeader) "with a 512-byte SMC header." else "with no SMC header detected."
                )
                snesHeader?.let { header ->
                    appendLine(
                        "SNES header: ${header.mapModeLabel}, title '${header.title}', " +
                            "mapMode=${hexByte(header.mapMode)}, checksum ${if (header.checksumValid) "valid" else "does not match complement"}."
                    )
                } ?: appendLine("SNES header: no plausible LoROM header found at 0x7FC0.")

                if (expandedBytes > 0) {
                    val fill = if (expandedFfPercent != null && expandedZeroPercent != null) {
                        " (${expandedFfPercent}% FF, ${expandedZeroPercent}% 00)"
                    } else {
                        ""
                    }
                    appendLine(
                        "Expanded data: ${formatBytes(expandedBytes)} (${hex(expandedBytes)} bytes) beyond the vanilla 3 MiB area$fill."
                    )
                }

                if (vanillaRoomHeadersTotal > 0) {
                    appendLine(
                        "Vanilla room map: $vanillaRoomHeadersParsed/$vanillaRoomHeadersTotal known SMEDIT room headers parse at their expected offsets."
                    )
                }
                appendLine(
                    "Room scan: found $candidateRoomHeadersInBank8F plausible room headers in bank \$8F; " +
                        "$candidateExpandedLevelPointers level-data pointer(s) target expanded ROM space."
                )
                appendLine(
                    "Why editing is disabled: expanded or relocated room data can be valid in-game, " +
                        "but SMEDIT's editor/export pipeline assumes the vanilla room map. Loading it as editable could corrupt the hack."
                )
            }.trim()
    }

    data class SnesHeader(
        val title: String,
        val mapMode: Int,
        val romType: Int,
        val romSizeByte: Int,
        val sramSizeByte: Int,
        val country: Int,
        val version: Int,
        val checksum: Int,
        val complement: Int,
        val checksumValid: Boolean,
    ) {
        val mapModeLabel: String
            get() = when (mapMode and 0xEF) {
                0x20, 0x30 -> "LoROM"
                0x21, 0x31 -> "HiROM"
                else -> "unknown map mode"
            }
    }

    fun analyze(romData: ByteArray): Report {
        val smcHeaderOffset = if (romData.size % 0x8000 == SMC_HEADER_SIZE) SMC_HEADER_SIZE else 0
        val headerlessSize = max(0, romData.size - smcHeaderOffset)
        val expandedBytes = max(0, headerlessSize - ROM_SIZE)
        val expandedRangeStart = smcHeaderOffset + ROM_SIZE
        val expandedRange = if (expandedBytes > 0 && expandedRangeStart < romData.size) {
            expandedRangeStart until romData.size
        } else {
            IntRange.EMPTY
        }
        val expandedSize = expandedRange.count()
        val expandedFfPercent = expandedSize.takeIf { it > 0 }?.let { size ->
            romData.countBytes(expandedRange, 0xFF) * 100 / size
        }
        val expandedZeroPercent = expandedSize.takeIf { it > 0 }?.let { size ->
            romData.countBytes(expandedRange, 0x00) * 100 / size
        }
        val roomStats = scanRoomCompatibility(romData)

        return Report(
            fileSize = romData.size,
            smcHeaderOffset = smcHeaderOffset,
            headerlessSize = headerlessSize,
            supportedForEditing = headerlessSize == ROM_SIZE,
            snesHeader = readLoRomHeader(romData, smcHeaderOffset),
            expandedBytes = expandedBytes,
            expandedFfPercent = expandedFfPercent,
            expandedZeroPercent = expandedZeroPercent,
            vanillaRoomHeadersParsed = roomStats.vanillaParsed,
            vanillaRoomHeadersTotal = roomStats.vanillaTotal,
            candidateRoomHeadersInBank8F = roomStats.candidateHeaders,
            candidateExpandedLevelPointers = roomStats.expandedLevelPointers,
        )
    }

    private fun readLoRomHeader(romData: ByteArray, smcHeaderOffset: Int): SnesHeader? {
        val offset = smcHeaderOffset + LOROM_HEADER_PC
        if (offset + 0x20 > romData.size) return null
        val titleBytes = romData.copyOfRange(offset, offset + 21)
        val printable = titleBytes.count { byte ->
            val value = byte.toInt() and 0xFF
            value in 0x20..0x7E
        }
        if (printable < 12) return null

        val complement = readU16OrNull(romData, offset + 0x1C) ?: return null
        val checksum = readU16OrNull(romData, offset + 0x1E) ?: return null
        return SnesHeader(
            title = decodeTitle(titleBytes),
            mapMode = romData[offset + 0x15].toInt() and 0xFF,
            romType = romData[offset + 0x16].toInt() and 0xFF,
            romSizeByte = romData[offset + 0x17].toInt() and 0xFF,
            sramSizeByte = romData[offset + 0x18].toInt() and 0xFF,
            country = romData[offset + 0x19].toInt() and 0xFF,
            version = romData[offset + 0x1B].toInt() and 0xFF,
            checksum = checksum,
            complement = complement,
            checksumValid = (checksum xor complement) == 0xFFFF,
        )
    }

    private fun scanRoomCompatibility(romData: ByteArray): RoomStats {
        val roomInfos = runCatching { RoomRepository().getAllRooms() }.getOrDefault(emptyList())
        val parser = RomParser(romData)
        val vanillaParsed = roomInfos.count { roomInfo ->
            runCatching { parser.readRoomHeader(roomInfo.getRoomIdAsInt()) != null }.getOrDefault(false)
        }
        val discovered = RomRoomScanner.discover(parser, validateLevelData = false)

        return RoomStats(
            vanillaParsed = vanillaParsed,
            vanillaTotal = roomInfos.size,
            candidateHeaders = discovered.size,
            expandedLevelPointers = discovered.sumOf { it.expandedLevelPointerCount },
        )
    }

    private fun ByteArray.countBytes(range: IntRange, value: Int): Int {
        var count = 0
        for (index in range) {
            if ((this[index].toInt() and 0xFF) == value) count++
        }
        return count
    }

    private fun readU16OrNull(data: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 1 >= data.size) return null
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun decodeTitle(bytes: ByteArray): String =
        bytes.joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            if (value in 0x20..0x7E) value.toChar().toString() else "."
        }.trim()

    private fun formatBytes(bytes: Int): String {
        val mibHundredths = (bytes.toDouble() * 100.0 / (1024.0 * 1024.0)).roundToInt()
        val whole = mibHundredths / 100
        val fraction = (mibHundredths % 100).toString().padStart(2, '0')
        return "$whole.$fraction MiB"
    }

    private fun hex(value: Int): String =
        "0x${value.toString(16).uppercase()}"

    private fun hexByte(value: Int): String =
        "0x${(value and 0xFF).toString(16).uppercase().padStart(2, '0')}"

    private data class RoomStats(
        val vanillaParsed: Int,
        val vanillaTotal: Int,
        val candidateHeaders: Int,
        val expandedLevelPointers: Int,
    )
}

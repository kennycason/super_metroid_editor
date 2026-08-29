package com.supermetroid.editor.rom

import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.RoomInfo

object RoomNamePauseMapPatch {
    const val CONFIG_TYPE = "room_name_pause_map"
    const val CONFIG_ALIGNMENT_KEY = "alignment"
    const val ROOM_NAME_CHAR_COUNT = 24

    private const val WRAPPER_SIZE = 9
    private const val ROOM_NAME_TABLE_ENTRY_BYTES = 8
    private const val LOAD_PAUSE_MAP_AND_AREA_LABEL = 0x8293C3
    private const val LOAD_PAUSE_MAP_CALL_1 = 0x828D25
    private const val LOAD_PAUSE_MAP_CALL_2 = 0x8291DD
    private const val ROOM_ID_WRAM = 0x079B
    private const val ROOM_NAME_VRAM = 0x38C0
    private const val BLANK_TILE_WORD = 0x2801
    private const val TILE_WORD_HIGH = 0x3800
    private val ORIGINAL_LOAD_MAP_JSL = byteArrayOf(0x22, 0xC3.toByte(), 0x93.toByte(), 0x82.toByte())

    /**
     * Banks preferred for generated patch payloads.
     *
     * Export writes this patch before room relocation and after fixed patches, so later scanners will see these
     * bytes. Bank $DF is intentionally absent because the current per-frame hook still writes there directly.
     */
    val ALLOCATION_BANKS = listOf(
        0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x8E,
        0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F,
        0xA0, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xAB, 0xAC, 0xAD, 0xAE, 0xAF,
        0xB0, 0xB1, 0xB2, 0xB3, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xBB, 0xBC, 0xBD, 0xBE,
    )

    fun install(
        romData: ByteArray,
        snesToPc: (Int) -> Int,
        pcToSnes: (Int) -> Int,
        rooms: List<RoomInfo>,
        overrides: Map<String, String> = emptyMap(),
        alignment: RoomNameAlignment = RoomNameAlignment.CENTER,
    ): RoomNamePauseMapPatchResult {
        val entries = buildEntries(rooms, overrides, alignment)
        require(entries.isNotEmpty()) { "room-name pause map patch has no room names to write" }

        val dryRunPayload = buildPayload(baseSnesAddress = 0x828000, bank = 0x82, entries = entries)
        val allocator = RomFreeSpaceAllocator(romData, snesToPc, pcToSnes)
        val allocation = allocator.reserve(
            size = dryRunPayload.size,
            banks = ALLOCATION_BANKS,
            label = "room-name pause-map payload",
            alignment = 1,
        ) ?: error("not enough contiguous free ROM space for ${dryRunPayload.size}-byte room-name pause-map payload")

        val payload = buildPayload(allocation.snesAddress, allocation.bank, entries)
        require(payload.size == dryRunPayload.size) {
            "room-name payload size changed after allocation (${dryRunPayload.size} -> ${payload.size})"
        }

        val wrapperSnes = allocation.snesAddress
        val hookBytes = listOf(0x22, wrapperSnes and 0xFF, (wrapperSnes shr 8) and 0xFF, (wrapperSnes shr 16) and 0xFF)
        val payloadWrite = PatchWrite(
            allocation.pcOffset.toLong(),
            payload.map { it.toInt() and 0xFF },
            List(payload.size) { 0xFF },
        )
        val hookWrites = listOf(
            PatchWrite(
                snesToPc(LOAD_PAUSE_MAP_CALL_1).toLong(),
                hookBytes,
                ORIGINAL_LOAD_MAP_JSL.map { it.toInt() and 0xFF },
            ),
            PatchWrite(
                snesToPc(LOAD_PAUSE_MAP_CALL_2).toLong(),
                hookBytes,
                ORIGINAL_LOAD_MAP_JSL.map { it.toInt() and 0xFF },
            ),
        )

        for (write in hookWrites) verifyHookIsUnmodified(romData, write.offset.toInt(), pcToSnes(write.offset.toInt()))
        allocator.write(allocation, payload)
        for (write in hookWrites) writeToRom(romData, write)

        return RoomNamePauseMapPatchResult(
            allocation = allocation,
            writes = listOf(payloadWrite) + hookWrites,
            roomCount = entries.size,
            payloadSize = payload.size,
        )
    }

    fun layoutRoomName(
        name: String,
        alignment: RoomNameAlignment = RoomNameAlignment.CENTER,
    ): EncodedRoomName {
        val cleaned = cleanRoomName(name)
        val leftPad = when (alignment) {
            RoomNameAlignment.LEFT -> 0
            RoomNameAlignment.CENTER -> ((ROOM_NAME_CHAR_COUNT - cleaned.length) / 2).coerceAtLeast(0)
            RoomNameAlignment.RIGHT -> (ROOM_NAME_CHAR_COUNT - cleaned.length).coerceAtLeast(0)
        }
        return EncodedRoomName(
            tilemapBytes = encodeRoomNameTilemap(cleaned),
            startTile = leftPad,
        )
    }

    fun encodeRoomNameTilemap(name: String): List<Int> {
        return cleanRoomName(name).flatMap { tileWordForChar(it) }
    }

    private fun buildEntries(
        rooms: List<RoomInfo>,
        overrides: Map<String, String>,
        alignment: RoomNameAlignment,
    ): List<RoomNameEntry> {
        val byRoomId = linkedMapOf<Int, RoomNameEntry>()
        for (room in rooms) {
            val roomId = room.getRoomIdAsInt()
            val name = overrideFor(roomId, overrides) ?: room.name
            layoutRoomName(name, alignment).takeIf { it.tilemapBytes.isNotEmpty() }?.let {
                byRoomId[roomId] = RoomNameEntry(roomId, it)
            }
        }
        for ((key, name) in overrides) {
            val roomId = parseRoomIdKey(key) ?: continue
            if (name.isBlank()) continue
            layoutRoomName(name, alignment).takeIf { it.tilemapBytes.isNotEmpty() }?.let {
                byRoomId.putIfAbsent(roomId, RoomNameEntry(roomId, it))
            }
        }
        return byRoomId.values.sortedBy { it.roomId }
    }

    private fun cleanRoomName(name: String): String {
        return name.uppercase()
            .map { if (it in 'A'..'Z') it else ' ' }
            .joinToString("")
            .replace(Regex(" +"), " ")
            .trim()
            .take(ROOM_NAME_CHAR_COUNT)
    }

    private fun overrideFor(roomId: Int, overrides: Map<String, String>): String? {
        val hex = roomId.toString(16).uppercase().padStart(4, '0')
        return overrides[hex]
            ?: overrides["0x$hex"]
            ?: overrides["0X$hex"]
            ?: overrides[hex.lowercase()]
            ?: overrides["0x${hex.lowercase()}"]
    }

    private fun parseRoomIdKey(key: String): Int? {
        val trimmed = key.trim()
        val hex = when {
            trimmed.startsWith("0x", ignoreCase = true) -> trimmed.drop(2)
            trimmed.startsWith('$') -> trimmed.drop(1)
            else -> trimmed
        }
        return hex.toIntOrNull(16)?.takeIf { it in 0x0000..0xFFFF }
    }

    private fun buildPayload(
        baseSnesAddress: Int,
        bank: Int,
        entries: List<RoomNameEntry>,
    ): ByteArray {
        val baseLow = baseSnesAddress and 0xFFFF
        val dryRunDraw = buildDrawRoutine(bank = bank, roomTableAddress = 0x8000)
        val wrapperAddress = baseLow
        val drawAddress = wrapperAddress + WRAPPER_SIZE
        val roomTableAddress = drawAddress + dryRunDraw.size

        val wrapper = buildWrapper(drawAddress, bank)
        val drawRoutine = buildDrawRoutine(bank, roomTableAddress)
        val tilemapStartAddress = roomTableAddress + entries.size * ROOM_NAME_TABLE_ENTRY_BYTES + 2
        val payload = ByteBuilder()
        payload.addBytes(wrapper)
        payload.addBytes(drawRoutine)
        var tilemapAddress = tilemapStartAddress
        for (entry in entries) {
            payload.addWord(entry.roomId)
            payload.addWord(tilemapAddress)
            payload.addWord(ROOM_NAME_VRAM + entry.layout.startTile)
            payload.addWord(entry.layout.tilemapBytes.size)
            tilemapAddress += entry.layout.tilemapBytes.size
        }
        payload.addWord(0xFFFF)
        for (entry in entries) {
            payload.addBytes(entry.layout.tilemapBytes)
        }
        return payload.toByteArray()
    }

    private fun buildWrapper(drawAddress: Int, bank: Int): ByteArray {
        val bytes = ByteBuilder()
        bytes.addByte(0x22)
        bytes.addLongAddress(LOAD_PAUSE_MAP_AND_AREA_LABEL)
        bytes.addByte(0x22)
        bytes.addByte(drawAddress and 0xFF)
        bytes.addByte((drawAddress shr 8) and 0xFF)
        bytes.addByte(bank)
        bytes.addByte(0x6B)
        return bytes.toByteArray()
    }

    private fun buildDrawRoutine(bank: Int, roomTableAddress: Int): ByteArray {
        val asm = AsmBuilder()
        asm.add(0x08) // PHP
        asm.add(0x8B) // PHB
        asm.add(0x4B) // PHK
        asm.add(0xAB) // PLB
        asm.add(0xC2, 0x30) // REP #$30
        asm.add(0xA2)
        asm.word(roomTableAddress)
        asm.label("loop")
        asm.add(0xBD, 0x00, 0x00) // LDA $0000,X
        asm.add(0xC9)
        asm.word(0xFFFF)
        asm.branch(0xF0, "done") // BEQ done
        asm.add(0xCD)
        asm.word(ROOM_ID_WRAM) // CMP $079B
        asm.branch(0xF0, "found") // BEQ found
        asm.add(0x8A) // TXA
        asm.add(0x18) // CLC
        asm.add(0x69)
        asm.word(ROOM_NAME_TABLE_ENTRY_BYTES) // ADC #entry size
        asm.add(0xAA) // TAX
        asm.branch(0x80, "loop") // BRA loop
        asm.label("found")
        asm.add(0xBD, 0x02, 0x00) // LDA $0002,X
        asm.add(0x8D, 0x12, 0x43) // STA $4312
        asm.add(0xBD, 0x04, 0x00) // LDA $0004,X
        asm.add(0x8D, 0x16, 0x21) // STA $2116
        asm.add(0xBD, 0x06, 0x00) // LDA $0006,X
        asm.add(0x8D, 0x15, 0x43) // STA $4315
        asm.add(0xE2, 0x20) // SEP #$20
        asm.add(0xA9, bank)
        asm.add(0x8D, 0x14, 0x43) // STA $4314
        asm.add(0xA9, 0x80)
        asm.add(0x8D, 0x15, 0x21) // STA $2115
        asm.add(0xA9, 0x01)
        asm.add(0x8D, 0x10, 0x43) // STA $4310
        asm.add(0xA9, 0x18)
        asm.add(0x8D, 0x11, 0x43) // STA $4311
        asm.add(0xA9, 0x02)
        asm.add(0x8D, 0x0B, 0x42) // STA $420B
        asm.label("done")
        asm.add(0xAB) // PLB
        asm.add(0x28) // PLP
        asm.add(0x6B) // RTL
        return asm.toByteArray()
    }

    private fun tileWordForChar(ch: Char): List<Int> {
        val word = if (ch in 'A'..'Z') TILE_WORD_HIGH or (0x30 + (ch - 'A')) else BLANK_TILE_WORD
        return listOf(word and 0xFF, (word shr 8) and 0xFF)
    }

    private fun verifyHookIsUnmodified(romData: ByteArray, pcOffset: Int, snesAddress: Int) {
        if (pcOffset < 0 || pcOffset + ORIGINAL_LOAD_MAP_JSL.size > romData.size) {
            error("room-name pause-map hook is outside ROM bounds at SNES ${hex24(snesAddress)}")
        }
        for (i in ORIGINAL_LOAD_MAP_JSL.indices) {
            if (romData[pcOffset + i] != ORIGINAL_LOAD_MAP_JSL[i]) {
                error(
                    "room-name pause-map hook conflict at SNES ${hex24(snesAddress)}; " +
                        "another enabled patch already modified the pause map loader"
                )
            }
        }
    }

    private fun writeToRom(romData: ByteArray, write: PatchWrite) {
        val off = write.offset.toInt()
        require(off >= 0 && off + write.bytes.size <= romData.size) {
            "room-name pause-map write outside ROM bounds at PC $off"
        }
        for ((index, byte) in write.bytes.withIndex()) {
            romData[off + index] = (byte and 0xFF).toByte()
        }
    }

    private fun hex24(value: Int): String = "$" + value.toString(16).uppercase().padStart(6, '0')

    private data class RoomNameEntry(
        val roomId: Int,
        val layout: EncodedRoomName,
    )

    enum class RoomNameAlignment(val configValue: Int, val label: String) {
        LEFT(0, "Left"),
        CENTER(1, "Center"),
        RIGHT(2, "Right");

        companion object {
            fun fromConfig(value: Int?): RoomNameAlignment =
                entries.firstOrNull { it.configValue == value } ?: CENTER
        }
    }
}

data class EncodedRoomName(
    val tilemapBytes: List<Int>,
    val startTile: Int,
)

data class RoomNamePauseMapPatchResult(
    val allocation: RomAllocation,
    val writes: List<PatchWrite>,
    val roomCount: Int,
    val payloadSize: Int,
)

private class ByteBuilder {
    private val bytes = mutableListOf<Int>()

    fun addByte(value: Int) {
        bytes.add(value and 0xFF)
    }

    fun addBytes(values: ByteArray) {
        for (value in values) bytes.add(value.toInt() and 0xFF)
    }

    fun addBytes(values: List<Int>) {
        for (value in values) bytes.add(value and 0xFF)
    }

    fun addWord(value: Int) {
        addByte(value)
        addByte(value shr 8)
    }

    fun addLongAddress(address: Int) {
        addByte(address)
        addByte(address shr 8)
        addByte(address shr 16)
    }

    fun toByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }
}

private class AsmBuilder {
    private val bytes = mutableListOf<Int>()
    private val labels = mutableMapOf<String, Int>()
    private val branchFixups = mutableListOf<Pair<Int, String>>()

    fun add(vararg values: Int) {
        for (value in values) bytes.add(value and 0xFF)
    }

    fun word(value: Int) {
        add(value and 0xFF, (value shr 8) and 0xFF)
    }

    fun label(name: String) {
        labels[name] = bytes.size
    }

    fun branch(opcode: Int, label: String) {
        add(opcode)
        branchFixups.add(bytes.size to label)
        add(0x00)
    }

    fun toByteArray(): ByteArray {
        for ((offsetIndex, label) in branchFixups) {
            val target = labels[label] ?: error("missing branch label: $label")
            val relative = target - (offsetIndex + 1)
            require(relative in -128..127) { "branch to $label is out of range: $relative" }
            bytes[offsetIndex] = relative and 0xFF
        }
        return ByteArray(bytes.size) { bytes[it].toByte() }
    }
}

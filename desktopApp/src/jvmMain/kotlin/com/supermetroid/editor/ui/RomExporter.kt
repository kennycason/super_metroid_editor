package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicTrackEdit
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.data.TILE_EDIT_LAYER_2
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomFreeSpaceAllocator
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomNamePauseMapPatch
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TextCategory
import com.supermetroid.editor.rom.TextData
import com.supermetroid.editor.rom.TileGraphics
import java.io.File

private fun ByteArray.hexAt(offset: Int, count: Int): String {
    if (offset < 0 || offset >= size) return "<out-of-range>"
    val end = (offset + count).coerceAtMost(size)
    return (offset until end).joinToString(" ") { (this[it].toInt() and 0xFF).toString(16).padStart(2, '0') }
}

internal fun buildIpsPatch(original: ByteArray, patched: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write("PATCH".toByteArray(Charsets.US_ASCII))

    var i = 0
    val len = minOf(original.size, patched.size)
    while (i < len) {
        if (original[i] != patched[i]) {
            val start = i
            while (i < len && original[i] != patched[i] && (i - start) < 0xFFFF) i++
            val size = i - start

            // IPS record: 3-byte offset, 2-byte size, data
            out.write((start shr 16) and 0xFF)
            out.write((start shr 8) and 0xFF)
            out.write(start and 0xFF)
            out.write((size shr 8) and 0xFF)
            out.write(size and 0xFF)
            out.write(patched, start, size)
        } else {
            i++
        }
    }

    out.write("EOF".toByteArray(Charsets.US_ASCII))
    return out.toByteArray()
}

/**
 * Handles all ROM patching and export logic for a given [project] snapshot.
 *
 * Callers are responsible for any pre-export setup (e.g. seeding default patches,
 * saving project state) before constructing this class. [onLog] receives diagnostic
 * log lines; [onStatus] receives user-visible status messages.
 */
internal class RomExporter(
    private val project: SmEditProject,
    private val romParser: RomParser,
    private val onLog: (String) -> Unit = {},
    private val onStatus: (String) -> Unit = {},
) {
    private fun exportSuffix(): String {
        val version = "v${project.versionMajor}.${project.versionMinor}"
        val build = project.buildName.trim()
        return if (build.isNotEmpty()) "$build-$version" else version
    }

    private fun applyMusicEditsToRom(romData: ByteArray): Int {
        if (project.musicEdits.isEmpty()) return 0

        var patched = 0
        val editsBySongSet = project.musicEdits.toSortedMap().entries.groupBy { it.value.songSet }.toSortedMap()
        for ((songSet, edits) in editsBySongSet) {
            val exportParser = RomParser(romData)
            val baseBlocks = SpcData.parseTransferBlocks(romData, exportParser.snesToPc(0xCF8000))
            val baseRam = SpcData.buildInitialSpcRam(exportParser)
            val songBlocks = SpcData.findSongSetTransferData(exportParser, songSet)
            val originalSpcRam = baseRam.copyOf()
            SpcData.applyTransferBlocks(originalSpcRam, songBlocks)
            val accumulatedWrites = SpcWriteAccumulator(songSet)
            val knownPlayIndexes = SpcData.KNOWN_TRACKS
                .filter { it.songSet == songSet }
                .mapTo(mutableSetOf()) { it.playIndex }
            edits.mapTo(knownPlayIndexes) { it.value.playIndex }
            val editedPlayIndexes = edits.mapTo(mutableSetOf()) { it.value.playIndex }
            val protectedPlayIndexes = knownPlayIndexes - editedPlayIndexes
            val occupiedSequenceRanges = MusicSequenceBudget.buildProtectedSpcOccupancy(
                baseBlocks = baseBlocks,
                songBlocks = songBlocks,
                spcRam = originalSpcRam,
                protectedPlayIndexes = protectedPlayIndexes
            ).toMutableList()

            for ((key, edit) in edits) {
                val nativePayload = edit.nativePayload
                if (nativePayload != null) {
                    val nativeWrites = nativeMusicPayloadWrites(edit)
                    require(nativeWrites.isNotEmpty()) { "native music payload $key produced no SPC writes" }
                    val owner = "native music payload '$key' (${edit.trackName})"
                    mergeSpcWrites(accumulatedWrites, owner, nativeWrites, rejectAnyOverlap = true)
                    val totalBytes = nativeWrites.values.sumOf { it.size }
                    patched++
                    onLog(
                        "[EXPORT] Native music payload '$key' ${edit.trackName}: " +
                            "${nativeWrites.size} SPC write records, $totalBytes payload bytes, " +
                            "sourcePlayIndex=${nativePayload.sourcePlayIndex}"
                    )
                    continue
                }

                val originalSong = NspcSequence.parse(originalSpcRam, edit.playIndex)
                val editedSong = MusicEditConversion.toSong(edit)
                val hasNoteDelta = PianoRollPreviewLogic.deltaOverlayPlan(editedSong, originalSong)?.hasDelta
                    ?: editedSong.isModified

                val relocatedSequence = if (hasNoteDelta) {
                    MusicSequenceBudget.encodeRelocated(
                        song = editedSong,
                        playIndex = edit.playIndex,
                        spcRam = originalSpcRam,
                        occupiedRanges = occupiedSequenceRanges,
                        key = key
                    ).also { patch ->
                        if (patch.fit.trimmed) {
                            onLog(
                                "WARN [EXPORT] Music edit '$key' ${edit.trackName}: " +
                                    "trimmed ${patch.fit.removedNotes} notes and ${patch.fit.removedCommands} commands after tick " +
                                    "${patch.fit.cutoffTick} to fit ${patch.fit.encodedBytes}/${patch.fit.budgetBytes} sequence bytes"
                            )
                        }
                    }
                } else {
                    null
                }
                val relocationRange = relocatedSequence?.allocation
                val sequenceWrites = relocatedSequence?.writes ?: emptyMap()
                validateMusicSequenceWrites(
                    key = key,
                    playIndex = edit.playIndex,
                    writes = sequenceWrites,
                    allocatedRange = relocationRange
                )
                if (relocationRange != null) {
                    occupiedSequenceRanges += relocationRange
                }

                val originalInstruments = NspcRenderer.readInstrumentTable(originalSpcRam)
                val editedInstruments = MusicEditConversion.toInstrumentEntries(edit)
                val instrumentWrites = if (editedInstruments.isNotEmpty()) {
                    PianoRollPreviewLogic.instrumentPatchWrites(editedInstruments, originalInstruments)
                } else {
                    emptyMap()
                }

                if (sequenceWrites.isEmpty() && instrumentWrites.isEmpty()) {
                    onLog("[EXPORT] Music edit $key has no note/instrument delta; skipping")
                    continue
                }

                val owner = "music edit '$key' (${edit.trackName})"
                mergeSpcWrites(accumulatedWrites, owner, sequenceWrites, rejectAnyOverlap = true)
                mergeSpcWrites(accumulatedWrites, owner, instrumentWrites, rejectAnyOverlap = false)
                val spcWrites = sequenceWrites + instrumentWrites
                val totalBytes = spcWrites.values.sumOf { it.size }

                patched++
                onLog(
                    "[EXPORT] Music edit '$key' ${edit.trackName}: ${spcWrites.size} SPC write records, " +
                        "$totalBytes payload bytes, noteDelta=$hasNoteDelta, instrumentWrites=${instrumentWrites.size}"
                )
            }

            if (accumulatedWrites.isNotEmpty()) {
                val chainBytes = writeRelocatedSongSetTransferChain(romData, songSet, accumulatedWrites.toWriteMap())
                onLog(
                    "[EXPORT] SongSet 0x${songSet.toString(16).padStart(2, '0')}: relocated " +
                        "${edits.size} music edit(s) into $chainBytes-byte transfer chain"
                )
            }
        }
        return patched
    }

    private data class SpcWriteAccumulator(
        val songSet: Int,
        val bytesByAddr: java.util.TreeMap<Int, Int> = java.util.TreeMap(),
        val ownersByAddr: MutableMap<Int, String> = mutableMapOf()
    ) {
        fun isNotEmpty(): Boolean = bytesByAddr.isNotEmpty()

        fun toWriteMap(): Map<Int, ByteArray> {
            val writes = linkedMapOf<Int, ByteArray>()
            var runStart = -1
            val runBytes = mutableListOf<Int>()
            var previousAddr = -1

            fun flushRun() {
                if (runStart >= 0) {
                    writes[runStart] = ByteArray(runBytes.size) { runBytes[it].toByte() }
                    runBytes.clear()
                    runStart = -1
                }
            }

            for ((addr, value) in bytesByAddr) {
                if (runStart < 0 || addr != previousAddr + 1) {
                    flushRun()
                    runStart = addr
                }
                runBytes += value
                previousAddr = addr
            }
            flushRun()
            return writes
        }
    }

    private fun validateMusicSequenceWrites(
        key: String,
        playIndex: Int,
        writes: Map<Int, ByteArray>,
        allocatedRange: MusicSequenceBudget.SpcRange?
    ) {
        val songTableEntry = MusicSequenceBudget.SONG_TABLE_BASE + playIndex * 2
        for ((addr, data) in writes) {
            val dest = addr and 0xFFFF
            val endExclusive = dest + data.size
            require(data.isNotEmpty()) { "music edit $key produced an empty sequence write at 0x${dest.toString(16)}" }
            if (dest == songTableEntry && data.size == 2) continue
            require(dest >= MusicSequenceBudget.SONG_TABLE_BASE && endExclusive <= MusicSequenceBudget.SEQUENCE_EXPORT_MAX) {
                "music edit $key produced unsafe sequence SPC write " +
                    "0x${dest.toString(16).padStart(4, '0')}.." +
                    "0x${(endExclusive - 1).toString(16).padStart(4, '0')}"
            }
            if (allocatedRange != null) {
                require(dest >= allocatedRange.start && endExclusive <= allocatedRange.endExclusive) {
                    "music edit $key re-encode escaped relocated allocation " +
                        "0x${allocatedRange.start.toString(16).padStart(4, '0')}.." +
                        "0x${(allocatedRange.endExclusive - 1).toString(16).padStart(4, '0')} with write " +
                        "0x${dest.toString(16).padStart(4, '0')}.." +
                        "0x${(endExclusive - 1).toString(16).padStart(4, '0')}"
                }
            }
        }
    }

    private fun nativeMusicPayloadWrites(edit: MusicTrackEdit): Map<Int, ByteArray> {
        val payload = edit.nativePayload ?: return emptyMap()
        val blocks = MusicEditConversion.toTransferBlocks(payload)
        val sourcePlayIndex = payload.sourcePlayIndex.takeIf { it >= 0 } ?: edit.playIndex
        val sourceEntry = MusicSequenceBudget.SONG_TABLE_BASE + sourcePlayIndex * 2
        val targetEntry = MusicSequenceBudget.SONG_TABLE_BASE + edit.playIndex * 2
        val bytesByAddr = java.util.TreeMap<Int, Int>()
        var sawSourceEntry = false

        fun putByte(addr: Int, value: Int) {
            require(addr in 0 until RomConstants.SPC_RAM_SIZE) {
                "native music payload ${MusicEditConversion.key(edit.songSet, edit.playIndex)} writes outside SPC RAM at " +
                    "0x${addr.toString(16).padStart(4, '0')}"
            }
            bytesByAddr[addr] = value and 0xFF
        }

        for (block in blocks) {
            val dest = block.destAddr and 0xFFFF
            require(dest + block.data.size <= RomConstants.SPC_RAM_SIZE) {
                "native music payload ${MusicEditConversion.key(edit.songSet, edit.playIndex)} has out-of-bounds block " +
                    "0x${dest.toString(16).padStart(4, '0')}.." +
                    "0x${(dest + block.data.size - 1).toString(16).padStart(4, '0')}"
            }
            for (i in block.data.indices) {
                val addr = dest + i
                val value = block.data[i].toInt() and 0xFF
                if (addr == sourceEntry || addr == sourceEntry + 1) {
                    sawSourceEntry = true
                    val offset = addr - sourceEntry
                    putByte(targetEntry + offset, value)
                    if (sourceEntry == targetEntry) {
                        continue
                    }
                } else {
                    putByte(addr, value)
                }
            }
        }

        require(sawSourceEntry) {
            "native music payload ${MusicEditConversion.key(edit.songSet, edit.playIndex)} did not include its source " +
                "song-table entry for play index $sourcePlayIndex"
        }
        return compactSpcWriteBytes(bytesByAddr)
    }

    private fun compactSpcWriteBytes(bytesByAddr: java.util.TreeMap<Int, Int>): Map<Int, ByteArray> {
        val writes = linkedMapOf<Int, ByteArray>()
        var runStart = -1
        val runBytes = mutableListOf<Int>()
        var previousAddr = -1

        fun flushRun() {
            if (runStart >= 0) {
                writes[runStart] = ByteArray(runBytes.size) { runBytes[it].toByte() }
                runBytes.clear()
                runStart = -1
            }
        }

        for ((addr, value) in bytesByAddr) {
            if (runStart < 0 || addr != previousAddr + 1) {
                flushRun()
                runStart = addr
            }
            runBytes += value
            previousAddr = addr
        }
        flushRun()
        return writes
    }

    private fun mergeSpcWrites(
        accumulator: SpcWriteAccumulator,
        owner: String,
        writes: Map<Int, ByteArray>,
        rejectAnyOverlap: Boolean
    ) {
        val songSetLabel = "0x${accumulator.songSet.toString(16).padStart(2, '0')}"
        for ((addr, data) in writes) {
            val dest = addr and 0xFFFF
            require(data.isNotEmpty()) { "$owner produced an empty SPC write at 0x${dest.toString(16)}" }
            require(dest + data.size <= RomConstants.SPC_RAM_SIZE) {
                "$owner produced out-of-bounds SPC write " +
                    "0x${dest.toString(16).padStart(4, '0')}.." +
                    "0x${(dest + data.size - 1).toString(16).padStart(4, '0')}"
            }
            for (i in data.indices) {
                val spcAddr = dest + i
                val value = data[i].toInt() and 0xFF
                val existing = accumulator.bytesByAddr[spcAddr]
                val previousOwner = accumulator.ownersByAddr[spcAddr]
                if (existing != null && previousOwner != owner) {
                    require(!rejectAnyOverlap) {
                        "music edits for songSet $songSetLabel overlap at SPC " +
                            "0x${spcAddr.toString(16).padStart(4, '0')} ($previousOwner vs $owner)"
                    }
                    require(existing == value) {
                        "music edits for songSet $songSetLabel write different values at SPC " +
                            "0x${spcAddr.toString(16).padStart(4, '0')} ($previousOwner vs $owner)"
                    }
                } else if (existing != null && existing != value) {
                    require(false) {
                        "$owner produced conflicting SPC bytes at 0x${spcAddr.toString(16).padStart(4, '0')}"
                    }
                }
                accumulator.bytesByAddr[spcAddr] = value
                accumulator.ownersByAddr[spcAddr] = owner
            }
        }
    }

    private fun writeRelocatedSongSetTransferChain(
        romData: ByteArray,
        songSet: Int,
        spcWrites: Map<Int, ByteArray>
    ): Int {
        require(spcWrites.isNotEmpty()) { "music export has no SPC writes for songSet 0x${songSet.toString(16)}" }

        val parser = RomParser(romData)
        val pointerEntryPc = SpcData.findSongSetPointerEntryPc(parser, songSet)
        require(pointerEntryPc >= 0) {
            "songSet 0x${songSet.toString(16)} has no writable transfer pointer table entry"
        }

        val originalPointer = SpcData.readSongSetPointer(parser, songSet)
        val originalBlocks = SpcData.findSongSetTransferData(parser, songSet)
        require(originalBlocks.isNotEmpty()) {
            "songSet 0x${songSet.toString(16)} has no transfer blocks to relocate"
        }

        val patchBlocks = buildSpcPatchBlocks(spcWrites)

        val relocatedChain = serializeTransferChain(originalBlocks + patchBlocks)
        val writePc = findMusicTransferFreeSpace(parser, romData, relocatedChain.size)
        relocatedChain.copyInto(romData, writePc)

        val relocatedPointer = parser.pcToSnes(writePc)
        writeRomU24(romData, pointerEntryPc, relocatedPointer)

        onLog(
            "[EXPORT] Relocated songSet 0x${songSet.toString(16).padStart(2, '0')} transfer chain " +
                "\$${originalPointer.toString(16).uppercase().padStart(6, '0')} -> " +
                "\$${relocatedPointer.toString(16).uppercase().padStart(6, '0')} " +
                "(${originalBlocks.size} original + ${patchBlocks.size} patch blocks)"
        )
        return relocatedChain.size
    }

    private fun buildSpcPatchBlocks(spcWrites: Map<Int, ByteArray>): List<SpcData.TransferBlock> {
        val bytesByAddr = sortedMapOf<Int, Int>()
        for ((addr, data) in spcWrites) {
            val dest = addr and 0xFFFF
            require(data.isNotEmpty()) { "empty SPC write at 0x${dest.toString(16)}" }
            require(data.size <= 0xFFFF) { "SPC write at 0x${dest.toString(16)} is too large (${data.size} bytes)" }
            require(dest + data.size <= RomConstants.SPC_RAM_SIZE) {
                "SPC write 0x${dest.toString(16)}..0x${(dest + data.size).toString(16)} exceeds SPC RAM"
            }
            for (i in data.indices) {
                bytesByAddr[dest + i] = data[i].toInt() and 0xFF
            }
        }
        if (bytesByAddr.isEmpty()) return emptyList()

        val blocks = mutableListOf<SpcData.TransferBlock>()
        var runStart = -1
        val runBytes = mutableListOf<Int>()
        var previousAddr = -1
        for ((addr, value) in bytesByAddr) {
            if (runStart < 0 || addr != previousAddr + 1) {
                if (runStart >= 0) {
                    blocks += SpcData.TransferBlock(runStart, ByteArray(runBytes.size) { runBytes[it].toByte() })
                    runBytes.clear()
                }
                runStart = addr
            }
            runBytes += value
            previousAddr = addr
        }
        if (runStart >= 0) {
            blocks += SpcData.TransferBlock(runStart, ByteArray(runBytes.size) { runBytes[it].toByte() })
        }
        return blocks
    }

    private fun serializeTransferChain(blocks: List<SpcData.TransferBlock>): ByteArray {
        val out = java.io.ByteArrayOutputStream(blocks.sumOf { 4 + it.data.size } + 2)
        for (block in blocks) {
            require(block.data.size <= 0xFFFF) {
                "transfer block at 0x${block.destAddr.toString(16)} is too large (${block.data.size} bytes)"
            }
            writeStreamU16(out, block.data.size)
            writeStreamU16(out, block.destAddr and 0xFFFF)
            out.write(block.data)
        }
        writeStreamU16(out, 0)
        return out.toByteArray()
    }

    private fun writeStreamU16(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeRomU24(romData: ByteArray, offset: Int, value: Int) {
        require(offset >= 0 && offset + 2 < romData.size) {
            "out-of-bounds ROM pointer write at 0x${offset.toString(16)}"
        }
        romData[offset] = (value and 0xFF).toByte()
        romData[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        romData[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }

    private fun findMusicTransferFreeSpace(
        parser: RomParser,
        romData: ByteArray,
        requiredBytes: Int
    ): Int {
        require(requiredBytes > 0) { "music transfer chain must not be empty" }
        val maxLoRomBankBytes = MusicTransferChainBudget.MAX_SINGLE_LOROM_BANK_BYTES
        if (requiredBytes > maxLoRomBankBytes) {
            error(
                "music transfer chain needs $requiredBytes bytes, but one LoROM bank can hold at most " +
                    "$maxLoRomBankBytes bytes. Large native IT/custom-sample imports can preview and export raw .nspc, " +
                    "but cannot be ROM-exported yet without a smaller payload or a future multi-bank/compact exporter"
            )
        }
        val preferredBanks = listOf(0xB8, 0xCE, 0x85, 0x83, 0x89)
        val fallbackBanks = (0x80..0xFF).filterNot { it in preferredBanks }
        for (bank in preferredBanks + fallbackBanks) {
            val bankStart = parser.snesToPc((bank shl 16) or 0x8000)
            val bankEndExclusive = parser.snesToPc((bank shl 16) or 0xFFFF) + 1
            if (bankStart < 0 || bankEndExclusive > romData.size || bankStart >= bankEndExclusive) continue

            var freeStart = bankEndExclusive
            while (freeStart > bankStart && romData[freeStart - 1] == 0xFF.toByte()) {
                freeStart--
            }
            if (freeStart >= bankEndExclusive) continue

            val alignedStart = ((freeStart + 0x0F) / 0x10) * 0x10
            if (alignedStart + requiredBytes <= bankEndExclusive) {
                return alignedStart
            }
        }

        error(
            "not enough contiguous free ROM space for $requiredBytes-byte relocated SPC transfer chain"
        )
    }

    private fun isEditorItemPlm(plmId: Int): Boolean {
        if (RomParser.isItemPlm(plmId)) return true
        return project.patches
            .filter { it.enabled }
            .flatMap { it.customItems }
            .any { it.visiblePlmId == plmId || it.chozoPlmId == plmId || it.hiddenPlmId == plmId }
    }

    fun export(): String? {
        val romPath = project.romPath
        if (romPath.isEmpty()) return null
        onLog("[EXPORT] Starting export — romPath=$romPath, romSize=${romParser.getRomData().size}")
        onLog("[EXPORT] Project spriteTileBlocks keys: ${project.customGfx.spriteTileBlocks.keys}")
        val romData = romParser.getRomData().copyOf()
        val roomsPatched = mutableSetOf<String>()

        // Apply patches FIRST so free-space scanners see any code/data
        // that patches write into otherwise-empty banks (e.g. skip_intro
        // writes custom ASM into bank $A1 free space).
        var patchesApplied = 0
        val enabledCount = project.patches.count { it.enabled }
        val disabledCount = project.patches.size - enabledCount
        val deferredGeneratedPatches = mutableListOf<SmPatch>()
        onLog("[EXPORT] Patches: $enabledCount enabled, $disabledCount disabled (${project.patches.size} total)")
        for (patch in project.patches) {
            if (!patch.enabled) continue
            onLog("[EXPORT] Applying patch: '${patch.name}' [${patch.id}] configType=${patch.configType ?: "hex"}")
            if (patch.configType == "ceres_escape_seconds") {
                val totalSecs = (patch.configValue ?: 60).coerceIn(15, 600)
                val mins = totalSecs / 60
                val secs = totalSecs % 60
                val secsBcd = ((secs / 10) shl 4) or (secs % 10)
                val minsBcd = ((mins / 10) shl 4) or (mins % 10)
                val off = romParser.snesToPc(CERES_TIMER_OPERAND_SNES)
                if (off + 1 < romData.size) {
                    romData[off] = secsBcd.toByte()
                    romData[off + 1] = minsBcd.toByte()
                }
                onLog("[EXPORT]   Ceres timer: ${mins}m${secs}s")
            } else if (patch.configType == "beam_damage") {
                val data = patch.configData ?: continue
                var beamCount = 0
                for (beam in ALL_BEAMS) {
                    val dmg = data[beam.key] ?: continue
                    val charged = dmg * 3
                    val pcUncharged = romParser.snesToPc(beam.snesAddress)
                    if (pcUncharged + 1 < romData.size) {
                        romData[pcUncharged] = (dmg and 0xFF).toByte()
                        romData[pcUncharged + 1] = ((dmg shr 8) and 0xFF).toByte()
                    }
                    val pcCharged = romParser.snesToPc(beam.chargedSnesAddress)
                    if (pcCharged + 1 < romData.size) {
                        romData[pcCharged] = (charged and 0xFF).toByte()
                        romData[pcCharged + 1] = ((charged shr 8) and 0xFF).toByte()
                    }
                    beamCount++
                }
                onLog("[EXPORT]   Beam damage: $beamCount beams modified")
            } else if (patch.configType == "boss_stats") {
                val data = patch.configData ?: continue
                var fieldCount = 0
                for (field in ALL_BOSS_FIELDS) {
                    val value = data[field.key] ?: continue
                    for (speciesId in field.writeSpeciesIds) {
                        val snesAddress = RomConstants.BANK_ENEMY_AI or speciesId
                        val pc = romParser.snesToPc(snesAddress) + field.offset
                        if (pc + 1 < romData.size) {
                            romData[pc] = (value and 0xFF).toByte()
                            romData[pc + 1] = ((value shr 8) and 0xFF).toByte()
                            fieldCount++
                        }
                    }
                }
                onLog("[EXPORT]   Boss stats: $fieldCount fields modified")
            } else if (patch.configType == "phantoon") {
                val data = patch.configData ?: continue
                var fieldCount = 0
                for (field in ALL_PHANTOON_FIELDS) {
                    val value = coercePhantoonValue(field, data[field.key] ?: continue)
                    val pc = romParser.snesToPc(field.snesAddress)
                    if (pc + 1 < romData.size) {
                        romData[pc] = (value and 0xFF).toByte()
                        romData[pc + 1] = ((value shr 8) and 0xFF).toByte()
                    }
                    fieldCount++
                }
                onLog("[EXPORT]   Phantoon behavior: $fieldCount fields modified")
            } else if (patch.configType == KRAID_CONFIG_TYPE) {
                val data = patch.configData ?: continue
                var fieldCount = 0
                for (field in ALL_KRAID_FIELDS) {
                    val value = coerceKraidValue(field, data[field.key] ?: continue)
                    for (snesAddress in field.writeSnesAddresses) {
                        val pc = romParser.snesToPc(snesAddress)
                        if (pc + 1 < romData.size) {
                            romData[pc] = (value and 0xFF).toByte()
                            romData[pc + 1] = ((value shr 8) and 0xFF).toByte()
                            fieldCount++
                        }
                    }
                }
                onLog("[EXPORT]   Kraid behavior: $fieldCount fields modified")
            } else if (patch.configType in BOSS_BEHAVIOR_FIELDS_BY_CONFIG_TYPE) {
                val data = patch.configData ?: continue
                val configType = patch.configType ?: continue
                val definition = BOSS_BEHAVIOR_BY_CONFIG_TYPE[configType]
                val fields = BOSS_BEHAVIOR_FIELDS_BY_CONFIG_TYPE.getValue(configType)
                var fieldCount = 0
                for (field in fields) {
                    val value = coerceBossBehaviorValue(field, data[field.key] ?: continue)
                    for (snesAddress in field.writeSnesAddresses) {
                        val pc = romParser.snesToPc(snesAddress)
                        if (pc + 1 < romData.size) {
                            romData[pc] = (value and 0xFF).toByte()
                            romData[pc + 1] = ((value shr 8) and 0xFF).toByte()
                            fieldCount++
                        }
                    }
                }
                onLog("[EXPORT]   ${definition?.title ?: "Boss"} behavior: $fieldCount fields modified")
            } else if (patch.configType == "enemy_stats") {
                val data = patch.configData ?: continue
                var modCount = 0
                for (e in ENEMY_DEFS) {
                    val hp = data["${e.key}_hp"]
                    val dmg = data["${e.key}_dmg"]
                    val snesAddr = RomConstants.BANK_ENEMY_AI or e.speciesId
                    if (hp != null) {
                        val pc = romParser.snesToPc(snesAddr) + 4
                        if (pc + 1 < romData.size) {
                            romData[pc] = (hp and 0xFF).toByte()
                            romData[pc + 1] = ((hp shr 8) and 0xFF).toByte()
                        }
                        modCount++
                    }
                    if (dmg != null) {
                        val pc = romParser.snesToPc(snesAddr) + 6
                        if (pc + 1 < romData.size) {
                            romData[pc] = (dmg and 0xFF).toByte()
                            romData[pc + 1] = ((dmg shr 8) and 0xFF).toByte()
                        }
                        modCount++
                    }
                }
                // AI routine pointers and graphics fields
                val aiFields = listOf(
                    "_initAi" to 0x12, "_mainAi" to 0x16, "_touchAi" to 0x30,
                    "_shotAi" to 0x32, "_hurtAi" to 0x1C, "_frozenAi" to 0x1E,
                    "_grappleAi" to 0x1A, "_deathAnim" to 0x22,
                    "_extraGfx" to 0x18, "_pbVuln" to 0x28,
                )
                for (e in ENEMY_DEFS) {
                    val snesAddr = RomConstants.BANK_ENEMY_AI or e.speciesId
                    for ((suffix, offset) in aiFields) {
                        val value = data["${e.key}$suffix"] ?: continue
                        val pc = romParser.snesToPc(snesAddr) + offset
                        if (pc + 1 < romData.size) {
                            romData[pc] = (value and 0xFF).toByte()
                            romData[pc + 1] = ((value shr 8) and 0xFF).toByte()
                            modCount++
                        }
                    }
                }
                onLog("[EXPORT]   Enemy stats: $modCount values modified (HP/DMG + AI/GFX)")
            } else if (patch.configType == "enemy_drops") {
                val data = patch.configData ?: continue
                var modCount = 0
                for (e in ENEMY_DEFS) {
                    // Resolve drop table pointer: species header +$3A → bank $B4
                    val snesAddr = RomConstants.BANK_ENEMY_AI or e.speciesId
                    val headerPc = romParser.snesToPc(snesAddr)
                    if (headerPc + 0x3C > romData.size) continue
                    val ptr = (romData[headerPc + 0x3A].toInt() and 0xFF) or
                            ((romData[headerPc + 0x3B].toInt() and 0xFF) shl 8)
                    if (ptr == 0 || ptr == 0xFFFF) continue
                    val dropPc = romParser.snesToPc(0xB40000 or ptr)
                    if (dropPc + 6 > romData.size) continue
                    for (i in 0..5) {
                        val value = data["${e.key}_drop$i"] ?: continue
                        romData[dropPc + i] = (value and 0xFF).toByte()
                        modCount++
                    }
                }
                onLog("[EXPORT]   Enemy drop rates: $modCount values modified")
            } else if (patch.configType == "enemy_vuln") {
                val data = patch.configData ?: continue
                var modCount = 0
                for (e in ENEMY_DEFS) {
                    // Resolve resistance table pointer: species header +$3C → bank $B4
                    val snesAddr = RomConstants.BANK_ENEMY_AI or e.speciesId
                    val headerPc = romParser.snesToPc(snesAddr)
                    if (headerPc + 0x3E > romData.size) continue
                    val ptr = (romData[headerPc + 0x3C].toInt() and 0xFF) or
                            ((romData[headerPc + 0x3D].toInt() and 0xFF) shl 8)
                    if (ptr == 0 || ptr == 0xFFFF) continue
                    val resPc = romParser.snesToPc(0xB40000 or ptr)
                    if (resPc + 22 > romData.size) continue
                    for (i in 0..21) {
                        val value = data["${e.key}_vuln$i"] ?: continue
                        romData[resPc + i] = (value and 0xFF).toByte()
                        modCount++
                    }
                }
                onLog("[EXPORT]   Enemy vulnerabilities: $modCount values modified")
            } else if (patch.configType == "samus_physics") {
                val data = patch.configData ?: continue
                var modCount = 0
                for (field in ALL_PHYSICS_FIELDS) {
                    val value = data[field.key] ?: continue
                    val pc = field.pcOffset
                    if (pc < romData.size) {
                        romData[pc] = (value and 0xFF).toByte()
                    }
                    modCount++
                }
                onLog("[EXPORT]   Samus physics: $modCount values modified")
            } else if (patch.configType == BOMB_CONFIG_TYPE) {
                val data = patch.configData
                val defaults = readBombsRomDefaults(romParser)
                val maxActive = (data?.get(BOMB_MAX_ACTIVE_KEY) ?: defaults.maxActiveBombs)
                    .coerceIn(1, BOMB_MAX_PROJECTILE_SLOTS)
                val fuseFrames = (data?.get(BOMB_FUSE_FRAMES_KEY) ?: defaults.fuseFrames)
                    .coerceIn(1, 9999)
                val cooldownFrames = (
                    data?.get(BOMB_COOLDOWN_FRAMES_KEY)
                        ?: calculateBombCooldownForConfig(
                            maxActiveBombs = maxActive,
                            fuseFrames = fuseFrames,
                            baseCooldownFrames = defaults.cooldownFrames,
                        )
                    ).coerceIn(0, 255)
                val explosionDelay = (data?.get(BOMB_EXPLOSION_FRAME_DELAY_KEY) ?: defaults.explosionFrameDelay)
                    .coerceIn(1, 255)

                fun writeByte(offset: Int, value: Int) {
                    if (offset < romData.size) romData[offset] = (value and 0xFF).toByte()
                }

                fun writeWord(offset: Int, value: Int) {
                    if (offset + 1 < romData.size) {
                        romData[offset] = (value and 0xFF).toByte()
                        romData[offset + 1] = ((value shr 8) and 0xFF).toByte()
                    }
                }

                writeWord(BOMB_ACTIVE_HARD_CAP_OPERAND_PC, maxActive)
                writeByte(BOMB_COOLDOWN_PC, cooldownFrames)
                writeWord(BOMB_FUSE_TIMER_PC, fuseFrames)
                writeWord(BOMB_EXPLOSION_FRAME_DELAY_OPERAND_PC, explosionDelay)
                onLog(
                    "[EXPORT]   Bombs: maxActive=$maxActive, fuse=$fuseFrames frames, " +
                        "cooldown=$cooldownFrames frames, explosionDelay=$explosionDelay"
                )
            } else if (patch.configType == FANFARE_CONFIG_TYPE) {
                val data = patch.configData
                val defaults = readFanfareRomDefaults(romParser)
                val frames = (data?.get(FANFARE_FRAMES_KEY) ?: defaults.itemFanfareFrames)
                    .coerceIn(FANFARE_MIN_FRAMES, FANFARE_MAX_FRAMES)

                fun writeWord(offset: Int, value: Int) {
                    if (offset + 1 < romData.size) {
                        romData[offset] = (value and 0xFF).toByte()
                        romData[offset + 1] = ((value shr 8) and 0xFF).toByte()
                    }
                }

                writeWord(FANFARE_MESSAGE_BOX_WAIT_PC, frames)
                for (offset in FANFARE_MUSIC_RESUME_DELAY_PCS) {
                    writeWord(offset, frames)
                }
                onLog(
                    "[EXPORT]   Fanfares: item box/music resume delay=$frames frames, " +
                        "${FANFARE_MUSIC_RESUME_DELAY_PCS.size + 1} values modified"
                )
            } else if (patch.configType == "controller_config") {
                val data = patch.configData ?: continue
                var slotCount = 0
                for (slot in CONTROLLER_SLOTS) {
                    val value = data[slot.key] ?: continue
                    val off = CONTROLLER_TABLE_PC + slot.tableIndex * 2
                    if (off + 1 < romData.size) {
                        romData[off] = (value and 0xFF).toByte()
                        romData[off + 1] = ((value shr 8) and 0xFF).toByte()
                    }
                    slotCount++
                }
                onLog("[EXPORT]   Controller config: $slotCount buttons remapped")
            } else if (patch.configType == RoomNamePauseMapPatch.CONFIG_TYPE) {
                deferredGeneratedPatches.add(patch)
                onLog("[EXPORT]   (deferred until fixed patch writes are applied)")
            } else if (patch.configType == "boss_defeated" || patch.configType == "hyper_beam") {
                onLog("[EXPORT]   (deferred to combined per-frame hook)")
            } else {
                val totalBytes = patch.writes.sumOf { it.bytes.size }
                for (write in patch.writes) {
                    val off = write.offset.toInt()
                    for ((i, b) in write.bytes.withIndex()) {
                        if (off + i < romData.size) romData[off + i] = b.toByte()
                    }
                }
                onLog("[EXPORT]   Hex writes: ${patch.writes.size} records, $totalBytes bytes")
                if (patch.id == "bundled_spider_ball") {
                    val flatHash = bytesSha256(patch.writes.flatMap { it.bytes })
                    onLog(
                        "[EXPORT]   Spider Ball proof: records=${patch.writes.size}, bytes=$totalBytes, sha256=$flatHash, " +
                            "movePtr@0x82353=${romData.hexAt(0x82353, 2)}, " +
                            "posePtr@0x8801C=${romData.hexAt(0x8801C, 2)}, " +
                            "code@0x87800=${romData.hexAt(0x87800, 12)}, " +
                            "guard@0x880BE=${romData.hexAt(0x880BE, 12)}, " +
                            "plm@0x27200=${romData.hexAt(0x27200, 12)}"
                    )
                }
            }
            patchesApplied++
        }

        for (patch in deferredGeneratedPatches) {
            try {
                val result = RoomNamePauseMapPatch.install(
                    romData = romData,
                    snesToPc = romParser::snesToPc,
                    pcToSnes = romParser::pcToSnes,
                    rooms = RoomRepository().getAllRooms(),
                    overrides = project.roomNameOverrides,
                    alignment = RoomNamePauseMapPatch.RoomNameAlignment.fromConfig(
                        patch.configData?.get(RoomNamePauseMapPatch.CONFIG_ALIGNMENT_KEY)
                    ),
                )
                onLog(
                    "[EXPORT]   Generated '${patch.name}': ${result.roomCount} room names, " +
                        "${result.payloadSize} bytes at SNES $" +
                        result.allocation.snesAddress.toString(16).uppercase().padStart(6, '0')
                )
            } catch (e: Exception) {
                val message = "Export failed: ${patch.name} could not be written safely (${e.message})"
                onLog("ERROR: $message")
                onStatus(message)
                return null
            }
        }

        val musicPatched = try {
            applyMusicEditsToRom(romData)
        } catch (e: Exception) {
            val message = "Export failed: music edit could not be written safely (${e.message})"
            onLog("ERROR: $message")
            onStatus(message)
            return null
        }
        if (musicPatched > 0) onLog("[EXPORT] Patched $musicPatched music track edit(s)")

        // Combined per-frame hook: boss-defeated + hyper beam + infinite blue suit
        // Writes a single routine at $DF:F040 (PC $2FF040) and hooks $82:896E.
        run {
            val enabledBosses = mutableSetOf<String>()
            var hyperBeam = false
            var infiniteBlueSuit = false
            for (patch in project.patches) {
                if (!patch.enabled) continue
                if (patch.configType == "boss_defeated") {
                    val data = patch.configData ?: continue
                    enabledBosses.addAll(data.filter { it.value != 0 }.keys)
                }
                if (patch.configType == "hyper_beam") hyperBeam = true
                if (patch.id == "bundled_infinite_blue_suit") infiniteBlueSuit = true
            }
            if (enabledBosses.isNotEmpty() || hyperBeam || infiniteBlueSuit) {
                onLog("[EXPORT] Per-frame hook active: bosses=${enabledBosses.ifEmpty { "none" }}, hyperBeam=$hyperBeam, infiniteBlueSuit=$infiniteBlueSuit")
                val code = mutableListOf<Int>()
                // Chain to original: JSL $8289EF
                code.addAll(listOf(0x22, 0xEF, 0x89, 0x82))
                code.add(0x08) // PHP
                code.addAll(listOf(0xC2, 0x20)) // REP #$20

                // Skip flag-setting in Mother Brain's room ($8F:DD58).
                // MB's AI uses event flags at $D820-$D821 for its multi-phase
                // state machine (MB1→MB2→Baby Metroid→escape). Force-ORing
                // boss/Tourian event bits every frame prevents these transitions.
                // Flags are already in WRAM from prior rooms, so skipping here is safe.
                code.addAll(listOf(0xAD, 0x9B, 0x07))         // LDA $079B (room_ptr)
                code.addAll(listOf(0xC9, 0x58, 0xDD))         // CMP #$DD58
                // BEQ to the PLP;RTL at the end — offset will be patched below
                val beqPos = code.size
                code.addAll(listOf(0xF0, 0x00))                // BEQ .done (placeholder)

                // Boss flags + associated event flags (long addressing for WRAM from bank $DF)
                if (enabledBosses.isNotEmpty()) {
                    val byAddr = mutableMapOf<Int, Int>()
                    for (flag in BOSS_FLAG_DEFS) {
                        if (flag.key in enabledBosses) {
                            byAddr[flag.wramAddr] = (byAddr[flag.wramAddr] ?: 0) or flag.bit
                        }
                    }

                    // Per-boss golden-statue events ($7E:D820-D821 event bitfield)
                    val bossStatueEvents = mapOf(
                        "phantoon" to (0xD820 to 0x40), // Event 0x06
                        "ridley"   to (0xD820 to 0x80), // Event 0x07
                        "draygon"  to (0xD821 to 0x01), // Event 0x08
                        "kraid"    to (0xD821 to 0x02), // Event 0x09
                    )
                    for ((boss, addrBit) in bossStatueEvents) {
                        if (boss in enabledBosses) {
                            byAddr[addrBit.first] = (byAddr[addrBit.first] ?: 0) or addrBit.second
                        }
                    }
                    val mainBosses = setOf("kraid", "phantoon", "ridley", "draygon")
                    if (mainBosses.all { it in enabledBosses }) {
                        byAddr[0xD821] = (byAddr[0xD821] ?: 0) or 0x04 // Event 0x0A: Path to Tourian open
                    }

                    for ((addr, bits) in byAddr) {
                        code.addAll(listOf(0xAF, addr and 0xFF, (addr shr 8) and 0xFF, 0x7E))
                        code.addAll(listOf(0x09, bits and 0xFF, 0x00))
                        code.addAll(listOf(0x8F, addr and 0xFF, (addr shr 8) and 0xFF, 0x7E))
                    }
                }

                // Hyper beam (long addressing: STA $7E:0A76)
                if (hyperBeam) {
                    code.addAll(listOf(0xA9, 0x00, 0x80))             // LDA #$8000
                    code.addAll(listOf(0x8F, 0x76, 0x0A, 0x7E))      // STA $7E0A76
                }

                // Infinite blue suit: force dash counter to $0400 every frame
                if (infiniteBlueSuit) {
                    code.addAll(listOf(0xA9, 0x00, 0x04))             // LDA #$0400
                    code.addAll(listOf(0x8F, 0x3E, 0x0B, 0x7E))      // STA $7E0B3E
                }

                code.add(0x28) // PLP
                code.add(0x6B) // RTL

                // Patch the BEQ offset to jump to PLP (skip the flag-setting body)
                val plpPos = code.size - 2  // position of PLP
                val branchOffset = plpPos - (beqPos + 2)  // +2 for the BEQ instruction size
                if (branchOffset in 0..127) {
                    code[beqPos + 1] = branchOffset
                }

                // Write payload at PC $2FF040
                for ((i, b) in code.withIndex()) {
                    val addr = 0x2FF040 + i
                    if (addr < romData.size) romData[addr] = b.toByte()
                }
                // Hook $82:896E (PC $1096E): JSL $DFF040
                val hook = listOf(0x22, 0x40, 0xF0, 0xDF)
                for ((i, b) in hook.withIndex()) {
                    val addr = 0x1096E + i
                    if (addr < romData.size) romData[addr] = b.toByte()
                }
                onLog("[EXPORT]   Per-frame hook: ${code.size} bytes at \$DF:F040, hook at \$82:896E")
            } else {
                onLog("[EXPORT] Per-frame hook: not needed (no boss flags, hyper beam, or blue suit)")
            }
        }

        // Free space allocator for bank $8F (PLM sets live here).
        // Scanned AFTER patches so we don't hand out space a patch already uses.
        val bank8FEnd = romParser.snesToPc(0x8FFFFF) + 1
        val bank8FStart = romParser.snesToPc(0x8F8000)
        var freePtr = bank8FEnd
        while (freePtr > bank8FStart) {
            val b = romData[freePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
            freePtr--
        }
        freePtr++

        // Free space allocator for bank $A1 (enemy population sets).
        val bankA1End = romParser.snesToPc(0xA1FFFF) + 1
        val bankA1Start = romParser.snesToPc(0xA18000)
        var enemyFreePtr = bankA1End
        while (enemyFreePtr > bankA1Start) {
            val b = romData[enemyFreePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
            enemyFreePtr--
        }
        enemyFreePtr++

        // Free space allocator for bank $B4 (enemy GFX sets).
        // Must be computed ONCE and incremented — rescanning per-room fails
        // because written GFX data contains 0xFF (species IDs, FFFF terminator)
        // and 0x00 (palette high bytes), which a rescan treats as free space,
        // causing subsequent rooms to overwrite earlier relocated GFX sets.
        //
        // The backward scan also absorbs any FFFF terminator at the boundary
        // (both bytes are 0xFF). We skip forward +2 to preserve it — otherwise
        // the last vanilla GFX set loses its terminator and the engine reads
        // garbage entries past it, corrupting VRAM and palettes.
        val bankB4End = romParser.snesToPc(0xB4FFFF) + 1
        val bankB4Start = romParser.snesToPc(0xB48000)
        var gfxFreePtr = bankB4End
        while (gfxFreePtr > bankB4Start) {
            val b = romData[gfxFreePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
            gfxFreePtr--
        }
        gfxFreePtr++
        val rawGfxFreePtr = gfxFreePtr
        // The scan absorbed any FFFF terminator at the boundary; skip past it.
        // Cost: at most 2 wasted bytes if there was no terminator.
        if (gfxFreePtr + 2 <= bankB4End) gfxFreePtr += 2
        onLog("[EXPORT] B4 free space: raw=0x${rawGfxFreePtr.toString(16)}, guarded=0x${gfxFreePtr.toString(16)} (+2 terminator guard)")

        // Free space tracker for level data banks ($C0-$CE).
        // Each bank is scanned from the end to find trailing 0xFF padding.
        val levelBankFree = mutableMapOf<Int, Int>()  // bank -> next free PC offset
        fun getLevelBankFreePtr(bank: Int): Int {
            return levelBankFree.getOrPut(bank) {
                val bankEnd = romParser.snesToPc((bank shl 16) or 0xFFFF) + 1
                val bankStart = romParser.snesToPc((bank shl 16) or 0x8000)
                var ptr = bankEnd
                while (ptr > bankStart) {
                    if ((romData[ptr - 1].toInt() and 0xFF) != 0xFF) break
                    ptr--
                }
                ptr + 1
            }
        }

        val vanillaEnemyGfxDestinationsBySpecies by lazy {
            collectVanillaEnemyGfxDestinations(romParser)
        }

        for ((roomKey, roomEdits) in project.rooms) {
            val hasTileEdits = roomEdits.operations.isNotEmpty()
            val hasPlmEdits = roomEdits.plmChanges.isNotEmpty()
            val hasDoorEdits = roomEdits.doorChanges.isNotEmpty()
            val hasEnemyEdits = roomEdits.enemyChanges.isNotEmpty()
            val hasScrollEdits = roomEdits.scrollChanges.isNotEmpty()
            val hasFxEdits = roomEdits.fxChange != null
            val hasStateEdits = roomEdits.stateDataChange != null
            val hasHeaderEdits = roomEdits.roomHeaderChange != null
            val hasCustomScrollCmds = roomEdits.customScrollCommands.isNotEmpty()
            val hasSaveStationSpawnEdits = roomEdits.saveStationSpawns.isNotEmpty()
            if (!hasTileEdits && !hasPlmEdits && !hasDoorEdits && !hasEnemyEdits &&
                !hasScrollEdits && !hasFxEdits && !hasStateEdits && !hasHeaderEdits &&
                !hasCustomScrollCmds && !hasSaveStationSpawnEdits) continue
            val roomId = roomKey.toIntOrNull(16) ?: continue
            val room = romParser.readRoomHeader(roomId) ?: continue

            // Patch tile data — apply edits to ALL states' level data so that
            // non-default states (boss-dead, escape, etc.) also reflect tile changes.
            // Without this, rooms with multiple states show the original layout when
            // a non-default state is active, causing phantom door blocks/caps.
            // ─── Room resize export ─────────────────────────────────────
            // When a room is resized, the export handles 5 things:
            //  1. Level data — resized (L1/BTS/L2), recompressed, auto-relocated if too large
            //  2. Scroll data — resized array relocated to free space in $8F, all state pointers updated
            //  3. Scroll PLM commands — screen indices remapped from old width to new width
            //     (in-place; these are room-specific data, not shared)
            //  4. Door entry ASM — generates brand new 65816 routines with remapped scroll writes,
            //     written to free space. Original shared routines are never touched.
            //     Door entry pointers updated to the new routines.
            //  5. Room header — width/height patched
            // Determine effective dimensions (accounting for resize)
            val hc = roomEdits.roomHeaderChange
            val effectiveWidth = hc?.width ?: room.width
            val effectiveHeight = hc?.height ?: room.height
            val isResized = effectiveWidth != room.width || effectiveHeight != room.height

            if ((hasTileEdits || isResized) && room.levelDataPtr != 0) {
                val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                val bw = effectiveWidth * 16

                // Group states by their level data pointer
                val ptrToStates = mutableMapOf<Int, MutableList<Int>>()
                for (stateOffset in allStateOffsets) {
                    val lvlPtr = (romData[stateOffset].toInt() and 0xFF) or
                            ((romData[stateOffset + 1].toInt() and 0xFF) shl 8) or
                            ((romData[stateOffset + 2].toInt() and 0xFF) shl 16)
                    if (lvlPtr != 0) ptrToStates.getOrPut(lvlPtr) { mutableListOf() }.add(stateOffset)
                }

                if (ptrToStates.size > 1) {
                    onLog("Room 0x$roomKey: ${ptrToStates.size} distinct level data pointers across ${allStateOffsets.size} states — applying edits to ALL")
                }

                for ((lvlPtr, statesForPtr) in ptrToStates) {
                    val (originalData, origSize) = romParser.decompressLZ2WithSize(lvlPtr)
                    // Apply resize to ROM data if dimensions changed
                    val editedData = if (isResized) {
                        resizeLevelData(originalData, room.width, room.height, effectiveWidth, effectiveHeight)
                    } else {
                        originalData.copyOf()
                    }
                    val layer1Size = (editedData[0].toInt() and 0xFF) or ((editedData[1].toInt() and 0xFF) shl 8)
                    val totalBlocks = bw * effectiveHeight * 16
                    val layer2Start = 2 + layer1Size + totalBlocks
                    val hasEmbeddedLayer2 = layer2Start + totalBlocks * 2 <= editedData.size &&
                        (roomEdits.stateDataChange?.bgScrolling ?: room.bgScrolling) == 0
                    for (op in roomEdits.operations) for (edit in op.edits) {
                        val idx = edit.blockY * bw + edit.blockX
                        if (idx < 0 || idx >= totalBlocks) continue
                        if (edit.layer == TILE_EDIT_LAYER_2) {
                            if (hasEmbeddedLayer2) {
                                val off = layer2Start + idx * 2
                                val word = edit.newBlockWord and 0x0FFF
                                editedData[off] = (word and 0xFF).toByte()
                                editedData[off + 1] = ((word shr 8) and 0xFF).toByte()
                            }
                            continue
                        }
                        val off = 2 + idx * 2
                        if (off + 1 < editedData.size) {
                            editedData[off] = (edit.newBlockWord and 0xFF).toByte()
                            editedData[off + 1] = ((edit.newBlockWord shr 8) and 0xFF).toByte()
                        }
                        val btsOff = 2 + layer1Size + idx
                        if (btsOff < editedData.size) editedData[btsOff] = edit.newBts.toByte()
                    }
                    val compressed = LZ5Compressor.compress(editedData)

                    // LZ5 round-trip verification
                    val roundTripped = LZ5Compressor.decompress(compressed)
                    if (!roundTripped.contentEquals(editedData)) {
                        val diffIdx = editedData.indices.firstOrNull { roundTripped.getOrNull(it) != editedData[it] }
                        onLog("ERROR: LZ5 round-trip FAILED for room 0x$roomKey lvlPtr=\$${lvlPtr.toString(16)}! Size: orig=${editedData.size} rt=${roundTripped.size}, first diff at byte $diffIdx")
                    }

                    val pcOff = romParser.snesToPc(lvlPtr)
                    if (compressed.size <= origSize) {
                        System.arraycopy(compressed, 0, romData, pcOff, compressed.size)
                        for (i in compressed.size until origSize) romData[pcOff + i] = 0xFF.toByte()
                    } else {
                        val origBank = (lvlPtr shr 16) and 0xFF
                        val banksToTry = listOf(origBank) +
                                (0xCE downTo 0xC0).filter { it != origBank }
                        var relocated = false
                        for (tryBank in banksToTry) {
                            val bEnd = romParser.snesToPc((tryBank shl 16) or 0xFFFF) + 1
                            val freeStart = getLevelBankFreePtr(tryBank)
                            if (freeStart + compressed.size <= bEnd) {
                                System.arraycopy(compressed, 0, romData, freeStart, compressed.size)
                                val newSnes = romParser.pcToSnes(freeStart)
                                levelBankFree[tryBank] = freeStart + compressed.size
                                for (stateOffset in statesForPtr) {
                                    romData[stateOffset] = (newSnes and 0xFF).toByte()
                                    romData[stateOffset + 1] = ((newSnes shr 8) and 0xFF).toByte()
                                    romData[stateOffset + 2] = ((newSnes shr 16) and 0xFF).toByte()
                                }
                                for (i in pcOff until pcOff + origSize) romData[i] = 0xFF.toByte()
                                onLog("Room 0x$roomKey: relocated level data \$${lvlPtr.toString(16)} to \$${tryBank.toString(16).uppercase()}:${(newSnes and 0xFFFF).toString(16).uppercase()} (${compressed.size} bytes, updated ${statesForPtr.size} state(s))")
                                relocated = true
                                break
                            }
                        }
                        if (!relocated) {
                            onLog("WARN: Room 0x$roomKey lvlPtr=\$${lvlPtr.toString(16)} compressed ${compressed.size} > orig $origSize and no free space — skipped")
                        }
                    }
                }
                roomsPatched.add(roomKey)
            }

            // Patch PLM sets — rooms can have multiple state conditions (E629, E612, E5E6, etc.)
            // each pointing to a DIFFERENT PLM set. We must apply user changes to every
            // distinct PLM set so items/stations appear regardless of which state is active.
            if (hasPlmEdits) {
                val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                val distinctPlmPtrs = mutableSetOf<Int>()
                for (stateOffset in allStateOffsets) {
                    val plmPtr = (romData[stateOffset + 20].toInt() and 0xFF) or
                            ((romData[stateOffset + 21].toInt() and 0xFF) shl 8)
                    if (plmPtr == 0 || plmPtr == 0xFFFF) continue
                    distinctPlmPtrs.add(plmPtr)
                }

                data class PlmSetData(val plmSetPtr: Int, val originalSize: Int, val plms: MutableList<RomParser.PlmEntry>)
                val plmSets = mutableListOf<PlmSetData>()

                for (plmSetPtr in distinctPlmPtrs) {
                    val originalPlms = romParser.parsePlmSet(plmSetPtr)
                    val modifiedPlms = originalPlms.toMutableList()
                    for (change in roomEdits.plmChanges) {
                        when (change.action) {
                            "add" -> modifiedPlms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                            "remove" -> modifiedPlms.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y }
                        }
                    }
                    val seen = mutableSetOf<Long>()
                    val deduped = mutableListOf<RomParser.PlmEntry>()
                    for (plm in modifiedPlms.reversed()) {
                        val key = (plm.x.toLong() shl 16) or plm.y.toLong()
                        if (isEditorItemPlm(plm.id)) {
                            if (key in seen) continue
                            seen.add(key)
                        }
                        deduped.add(plm)
                    }
                    deduped.reverse()
                    plmSets.add(PlmSetData(plmSetPtr, originalPlms.size * 6 + 2, deduped))
                }

                // Write all PLM sets to ROM
                for (psd in plmSets) {
                    val newSize = psd.plms.size * 6 + 2
                    val plmPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or psd.plmSetPtr)

                    val writePc: Int
                    if (newSize <= psd.originalSize) {
                        writePc = plmPc
                    } else if (freePtr + newSize <= bank8FEnd) {
                        writePc = freePtr
                        freePtr += newSize
                        val newSnes = romParser.pcToSnes(writePc)
                        val newPtr = newSnes and 0xFFFF
                        var updatedStates = 0
                        for (stateOffset in allStateOffsets) {
                            val existingPtr = (romData[stateOffset + 20].toInt() and 0xFF) or
                                    ((romData[stateOffset + 21].toInt() and 0xFF) shl 8)
                            if (existingPtr == psd.plmSetPtr) {
                                romData[stateOffset + 20] = (newPtr and 0xFF).toByte()
                                romData[stateOffset + 21] = ((newPtr shr 8) and 0xFF).toByte()
                                updatedStates++
                            }
                        }
                        onLog("Room 0x$roomKey: relocated PLM set 0x${psd.plmSetPtr.toString(16)} to 0x${newSnes.toString(16)} (updated $updatedStates states)")
                    } else {
                        onLog("WARN: Room 0x$roomKey no free space for expanded PLM set 0x${psd.plmSetPtr.toString(16)} — skipped")
                        continue
                    }

                    var offset = writePc
                    for (plm in psd.plms) {
                        romData[offset] = (plm.id and 0xFF).toByte()
                        romData[offset + 1] = ((plm.id shr 8) and 0xFF).toByte()
                        romData[offset + 2] = plm.x.toByte()
                        romData[offset + 3] = plm.y.toByte()
                        romData[offset + 4] = (plm.param and 0xFF).toByte()
                        romData[offset + 5] = ((plm.param shr 8) and 0xFF).toByte()
                        offset += 6
                        val name = RomParser.plmDisplayName(plm.id, plm.param)
                        onLog("  PLM: $name (0x${plm.id.toString(16)}) at (${plm.x},${plm.y}) param=0x${plm.param.toString(16)}")
                    }
                    romData[offset] = 0; romData[offset + 1] = 0
                    if (writePc == plmPc) {
                        for (i in offset + 2 until plmPc + psd.originalSize) romData[i] = 0
                    }
                }
                roomsPatched.add(roomKey)
            }

            // Write custom scroll command data to free space in $8F and resolve PLM params
            if (hasCustomScrollCmds) {
                val cmdIdToAddr = mutableMapOf<String, Int>() // cmdId → SNES 16-bit ptr
                for ((cmdId, commands) in roomEdits.customScrollCommands) {
                    if (commands.isEmpty()) continue
                    val dataSize = commands.size * 2 + 1 // pairs + terminator
                    if (freePtr + dataSize <= bank8FEnd) {
                        for (cmd in commands) {
                            romData[freePtr++] = cmd.screenIndex.toByte()
                            romData[freePtr++] = cmd.scrollValue.toByte()
                        }
                        romData[freePtr++] = 0x80.toByte() // terminator
                        val snesPtr = romParser.pcToSnes(freePtr - dataSize) and 0xFFFF
                        cmdIdToAddr[cmdId] = snesPtr
                        onLog("Room 0x$roomKey: wrote custom scroll command '$cmdId' (${commands.size} entries) at \$8F:${snesPtr.toString(16).uppercase()}")
                    }
                }
                // Patch PLM params: replace 0xCC00|idx with actual ROM address
                // Scan the PLM data we just wrote to ROM for custom params
                if (cmdIdToAddr.isNotEmpty()) {
                    val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                    for (stateOffset in allStateOffsets) {
                        val plmPtr = (romData[stateOffset + 20].toInt() and 0xFF) or
                            ((romData[stateOffset + 21].toInt() and 0xFF) shl 8)
                        if (plmPtr == 0 || plmPtr == 0xFFFF) continue
                        val plmPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or plmPtr)
                        var off = plmPc
                        while (off + 5 < romData.size) {
                            val plmId = (romData[off].toInt() and 0xFF) or ((romData[off + 1].toInt() and 0xFF) shl 8)
                            if (plmId == 0) break
                            val paramOff = off + 4
                            val param = (romData[paramOff].toInt() and 0xFF) or ((romData[paramOff + 1].toInt() and 0xFF) shl 8)
                            if (plmId == 0xB703 && (param and 0xFF00) == 0xCC00) {
                                val cmdIdx = param and 0xFF
                                val cmdId = "cmd_$cmdIdx"
                                val addr = cmdIdToAddr[cmdId]
                                if (addr != null) {
                                    romData[paramOff] = (addr and 0xFF).toByte()
                                    romData[paramOff + 1] = ((addr shr 8) and 0xFF).toByte()
                                }
                            }
                            off += 6
                        }
                    }
                }
                roomsPatched.add(roomKey)
            }

            // Patch door entries (last change per index wins)
            // The full 12-byte door definition is written as-is from the user's
            // DoorChange, including the orientation byte with cap flag (bit 2).
            // doorCapCode and entryCode are auto-set from vanilla when the
            // destination changes, so the cap flag is safe to preserve.
            if (hasDoorEdits && room.doorOut != 0 && room.doorOut != 0xFFFF) {
                val byIndex = roomEdits.doorChanges.groupBy { it.doorIndex }
                for ((doorIndex, changes) in byIndex) {
                    val dc = changes.last()
                    val entryPc = romParser.doorEntryPcOffset(room.doorOut, doorIndex) ?: continue
                    if (entryPc + 11 >= romData.size) continue

                    val orientation = (dc.bitflag shr 8) and 0xFF
                    val dirName = arrayOf("Right","Left","Down","Up")[orientation and 3]
                    val capStr = if (orientation and 0x04 != 0) " +cap" else ""
                    val capX = dc.doorCapCode and 0xFF
                    val capY = (dc.doorCapCode shr 8) and 0xFF
                    // Read vanilla door for comparison (from original ROM, not patched copy)
                    val vanillaDestPtr = romParser.readUInt16At(entryPc)
                    val vanillaOrient = romParser.readByteAt(entryPc + 3)
                    val vanillaCapX = romParser.readByteAt(entryPc + 4)
                    val vanillaCapY = romParser.readByteAt(entryPc + 5)
                    val crossArea = if (dc.bitflag and 0x40 != 0) " CROSS-AREA" else ""
                    onLog("Room 0x$roomKey door $doorIndex: orient=$orientation($dirName$capStr) cap=($capX,$capY) dest=0x${dc.destRoomPtr.toString(16)} entry=0x${dc.entryCode.toString(16)} bitflag=0x${dc.bitflag.toString(16)}$crossArea")
                    onLog("  vanilla: dest=0x${vanillaDestPtr.toString(16)} orient=$vanillaOrient cap=($vanillaCapX,$vanillaCapY) bitflag=0x${romParser.readUInt16At(entryPc + 2).toString(16)}")

                    var finalCapCode = dc.doorCapCode
                    var finalOrientation = orientation
                    val destRoom = romParser.readRoomHeader(dc.destRoomPtr)
                    if (destRoom != null) {
                        val maxX = destRoom.width * 16
                        val maxY = destRoom.height * 16
                        if (capX >= maxX || capY >= maxY) {
                            // Cap position is out of bounds — try to auto-derive a valid one
                            onLog("WARN: Room 0x$roomKey door $doorIndex cap position ($capX,$capY) " +
                                "is OUT OF BOUNDS for dest room 0x${dc.destRoomPtr.toString(16)} " +
                                "(${destRoom.width}x${destRoom.height} screens = ${maxX}x${maxY} blocks). " +
                                "screenX=${dc.screenX} screenY=${dc.screenY} orient=$orientation bitflag=0x${dc.bitflag.toString(16)}")
                            val derived = romParser.deriveDoorCapPosition(
                                dc.destRoomPtr, orientation and 3, dc.screenX, dc.screenY
                            )
                            if (derived != null) {
                                finalCapCode = derived
                                val newCapX = derived and 0xFF
                                val newCapY = (derived shr 8) and 0xFF
                                onLog("  FIX: auto-derived valid cap → ($newCapX,$newCapY)")
                            } else {
                                // Cannot derive — clear cap flag (bit 2) to prevent corrupt PLM
                                finalOrientation = orientation and 0xFB.toInt()
                                onLog("  FIX: could not derive cap, cleared cap flag (orient $orientation → $finalOrientation)")
                            }
                        }
                    } else {
                        onLog("WARN: Room 0x$roomKey door $doorIndex dest 0x${dc.destRoomPtr.toString(16)} — could not read dest room header!")
                    }

                    // Auto-fix cross-area flag if source and dest are in different areas
                    var finalBitflag = dc.bitflag
                    if (destRoom != null) {
                        if (room.area != destRoom.area) {
                            if (finalBitflag and 0x40 == 0) {
                                finalBitflag = finalBitflag or 0x40
                                onLog("  FIX: auto-set cross-area flag (area ${room.area} → ${destRoom.area})")
                            }
                        }
                    }

                    var finalEntryCode = dc.entryCode
                    if (shouldClearEnemyBg2TransferOnDoor(roomId, dc.destRoomPtr)) {
                        val scrollWrites = parseDoorScrollWrites(romParser, dc.entryCode)
                        if (dc.entryCode == 0 || scrollWrites.isNotEmpty()) {
                            val asm = buildDoorAsmClearingEnemyBg2Transfer(scrollWrites)
                            if (freePtr + asm.size <= bank8FEnd) {
                                val newPc = freePtr
                                for (byte in asm) romData[freePtr++] = byte
                                finalEntryCode = romParser.pcToSnes(newPc) and 0xFFFF
                                val preserved = if (scrollWrites.isNotEmpty()) {
                                    ", preserved ${scrollWrites.size} scroll write(s)"
                                } else {
                                    ""
                                }
                                onLog("  FIX: generated arrival ASM to clear stale enemy BG2 transfer flag$preserved " +
                                    "(was \$8F:${dc.entryCode.toString(16).uppercase()}, now \$8F:${finalEntryCode.toString(16).uppercase()})")
                            } else {
                                onLog("WARN: Room 0x$roomKey door $doorIndex: no free space for enemy BG2 cleanup door ASM (${asm.size} bytes)")
                            }
                        } else {
                            onLog("WARN: Room 0x$roomKey door $doorIndex: preserves custom entry ASM " +
                                "\$8F:${dc.entryCode.toString(16).uppercase()}; could not safely add enemy BG2 cleanup")
                        }
                    }

                    val doorListPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or room.doorOut)
                    val doorDefPtr = romParser.readUInt16At(doorListPc + doorIndex * 2)
                    if (doorDefPtr >= 0x8000 && destRoom != null) {
                        val bgDoor = RomParser.DoorEntry(
                            destRoomPtr = dc.destRoomPtr,
                            bitflag = finalBitflag,
                            doorCapCode = finalCapCode,
                            screenX = dc.screenX,
                            screenY = dc.screenY,
                            distFromDoor = dc.distFromDoor,
                            entryCode = finalEntryCode,
                            doorDefPtr = doorDefPtr,
                        )
                        val bgParser = RomParser(romData)
                        val destStateOffsets = bgParser.findAllStateDataOffsets(dc.destRoomPtr)
                        val distinctBgPtrs = destStateOffsets
                            .map { stateOffset ->
                                (romData[stateOffset + 22].toInt() and 0xFF) or
                                    ((romData[stateOffset + 23].toInt() and 0xFF) shl 8)
                            }
                            .filter { it != 0 && it != 0xFFFF }
                            .distinct()
                        for (oldBgPtr in distinctBgPtrs) {
                            val currentBgParser = RomParser(romData)
                            val template = findMatchingDoorDependentBgTransfer(currentBgParser, oldBgPtr, bgDoor)
                                ?: continue
                            val newBgData = buildBgDataWithClonedDoorDependentTransfer(
                                currentBgParser,
                                oldBgPtr,
                                doorDefPtr,
                                template,
                            ) ?: continue
                            if (freePtr + newBgData.size > bank8FEnd) {
                                onLog("WARN: Room 0x$roomKey door $doorIndex: no free space to clone door-dependent BG data " +
                                    "for dest 0x${dc.destRoomPtr.toString(16)} (${newBgData.size} bytes)")
                                continue
                            }
                            val newPc = freePtr
                            for (byte in newBgData) romData[freePtr++] = byte
                            val newBgPtr = romParser.pcToSnes(newPc) and 0xFFFF
                            for (stateOffset in destStateOffsets) {
                                val stateBgPtr = (romData[stateOffset + 22].toInt() and 0xFF) or
                                    ((romData[stateOffset + 23].toInt() and 0xFF) shl 8)
                                if (stateBgPtr == oldBgPtr) {
                                    romData[stateOffset + 22] = (newBgPtr and 0xFF).toByte()
                                    romData[stateOffset + 23] = ((newBgPtr shr 8) and 0xFF).toByte()
                                }
                            }
                            roomsPatched.add(dc.destRoomPtr.toString(16).uppercase())
                            onLog("  FIX: cloned door-dependent BG transfer for dest room " +
                                "0x${dc.destRoomPtr.toString(16)} door \$83:${doorDefPtr.toString(16).uppercase()} " +
                                "from template door \$83:${template.doorDefPtr.toString(16).uppercase()} " +
                                "(bg \$8F:${oldBgPtr.toString(16).uppercase()} → \$8F:${newBgPtr.toString(16).uppercase()})")
                        }
                    }

                    romData[entryPc] = (dc.destRoomPtr and 0xFF).toByte()
                    romData[entryPc + 1] = ((dc.destRoomPtr shr 8) and 0xFF).toByte()
                    romData[entryPc + 2] = (finalBitflag and 0xFF).toByte()
                    romData[entryPc + 3] = finalOrientation.toByte()
                    romData[entryPc + 4] = (finalCapCode and 0xFF).toByte()
                    romData[entryPc + 5] = ((finalCapCode shr 8) and 0xFF).toByte()
                    romData[entryPc + 6] = (dc.screenX and 0xFF).toByte()
                    romData[entryPc + 7] = (dc.screenY and 0xFF).toByte()
                    romData[entryPc + 8] = (dc.distFromDoor and 0xFF).toByte()
                    romData[entryPc + 9] = ((dc.distFromDoor shr 8) and 0xFF).toByte()
                    romData[entryPc + 10] = (finalEntryCode and 0xFF).toByte()
                    romData[entryPc + 11] = ((finalEntryCode shr 8) and 0xFF).toByte()
                }
                roomsPatched.add(roomKey)
            }

            // Patch enemy population
            if (hasEnemyEdits && room.enemySetPtr != 0 && room.enemySetPtr != 0xFFFF) run enemyPatch@{
                val originalEnemies = romParser.parseEnemyPopulation(room.enemySetPtr)
                val originalSet = originalEnemies.toSet()
                val modified = originalEnemies.toMutableList()
                for (ec in roomEdits.enemyChanges) {
                    when (ec.action) {
                        "add" -> modified.add(
                            RomParser.EnemyEntry(ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                                ec.extra1, ec.extra2, ec.extra3)
                        )
                        "remove" -> modified.removeAll {
                            it.id == ec.enemyId && it.x == ec.origX && it.y == ec.origY
                        }
                        "update" -> {
                            val idx = modified.indexOfFirst {
                                it.id == ec.enemyId && it.x == ec.origX && it.y == ec.origY
                            }
                            if (idx >= 0) modified[idx] = RomParser.EnemyEntry(
                                ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                                ec.extra1, ec.extra2, ec.extra3
                            )
                        }
                    }
                }
                val enemyPc = romParser.snesToPc(0xA10000 or room.enemySetPtr)
                val killCountPc = enemyPc + originalEnemies.size * 16 + 2
                val killCount = if (killCountPc < romData.size) romData[killCountPc] else 0
                // +3 = FFFF terminator (2) + kill count byte (1)
                val originalSize = originalEnemies.size * 16 + 3
                val newSize = modified.size * 16 + 3

                val writePc: Int
                if (newSize <= originalSize) {
                    writePc = enemyPc
                } else if (enemyFreePtr + newSize <= bankA1End) {
                    writePc = enemyFreePtr
                    enemyFreePtr += newSize
                    val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                    val newSnes = romParser.pcToSnes(writePc)
                    val newPtr = newSnes and 0xFFFF
                    for (stateOffset in allStateOffsets) {
                        val existingPtr = (romData[stateOffset + 8].toInt() and 0xFF) or
                                ((romData[stateOffset + 9].toInt() and 0xFF) shl 8)
                        if (existingPtr == room.enemySetPtr) {
                            romData[stateOffset + 8] = (newPtr and 0xFF).toByte()
                            romData[stateOffset + 9] = ((newPtr shr 8) and 0xFF).toByte()
                        }
                    }
                    onLog("Room 0x$roomKey: relocated enemy set to 0x${newSnes.toString(16)}")
                } else {
                    onLog("WARN: Room 0x$roomKey no free space for expanded enemy set — skipped enemy patch")
                    return@enemyPatch
                }

                fun writeU16(offset: Int, value: Int) {
                    romData[offset] = (value and 0xFF).toByte()
                    romData[offset + 1] = ((value shr 8) and 0xFF).toByte()
                }

                val originalSpeciesIds = originalEnemies.map { it.id }.toSet()
                var off = writePc
                for (e in modified) {
                    writeU16(off, e.id)
                    writeU16(off + 2, e.x)
                    writeU16(off + 4, e.y)
                    writeU16(off + 6, e.initParam)
                    // Only force 0x2000 (spritemap init) for truly NEW species not
                    // present in the vanilla population. If the user modified an
                    // existing enemy's properties, respect their exact value.
                    val props = if (e in originalSet || e.id in originalSpeciesIds) e.properties
                                else (e.properties or 0x2000)
                    writeU16(off + 8, props)
                    writeU16(off + 10, e.extra1)
                    writeU16(off + 12, e.extra2)
                    writeU16(off + 14, e.extra3)
                    off += 16
                }
                writeU16(off, 0xFFFF)
                off += 2
                romData[off] = killCount
                off++
                if (writePc == enemyPc) {
                    while (off < enemyPc + originalSize) { romData[off] = 0; off++ }
                }
                roomsPatched.add(roomKey)
            }

            // Patch enemy GFX set (bank $B4) — add GFX entries only for
            // species the USER is adding, not vanilla species that already work
            // without entries (e.g. Elevator, special entities). Adding unneeded
            // GFX entries causes ProcessEnemyTilesets to load tile data to VRAM
            // that the room wasn't designed for, corrupting the VRAM layout.
            if (hasEnemyEdits && room.enemyGfxPtr != 0 && room.enemyGfxPtr != 0xFFFF) run gfxPatch@{
                val gfxEntries = romParser.parseEnemyGfxSet(room.enemyGfxPtr)
                val existingSpecies = gfxEntries.map { it.speciesId }.toSet()

                val vanillaPopulation = romParser.parseEnemyPopulation(room.enemySetPtr)
                val vanillaSpecies = vanillaPopulation.map { it.id }.toSet()

                val finalPopulation = vanillaPopulation.toMutableList()
                for (ec in roomEdits.enemyChanges) {
                    when (ec.action) {
                        "add" -> finalPopulation.add(RomParser.EnemyEntry(ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties))
                        "remove" -> finalPopulation.removeAll { it.id == ec.enemyId && it.x == ec.origX && it.y == ec.origY }
                    }
                }
                val finalSpecies = finalPopulation.map { it.id }.toSet()
                // Only add GFX entries for species that are (a) genuinely new
                // (not in vanilla population), (b) still present after removes,
                // and (c) not already covered by the existing GFX set.
                val neededSpecies = (finalSpecies - vanillaSpecies).filter { it !in existingSpecies }
                val skippedVanilla = (finalSpecies intersect vanillaSpecies) - existingSpecies
                if (skippedVanilla.isNotEmpty()) {
                    onLog("Room 0x$roomKey: skipped ${skippedVanilla.size} vanilla species from GFX set " +
                            "(${skippedVanilla.joinToString { "0x${it.toString(16)}" }})")
                }
                if (neededSpecies.isEmpty()) return@gfxPatch

                val newEntries = gfxEntries.toMutableList()
                for (specId in neededSpecies) {
                    if (newEntries.size >= 4) {
                        onLog("WARN: Room 0x$roomKey GFX set already has ${newEntries.size} entries (SNES max 4) — skipping species 0x${specId.toString(16)}")
                        continue
                    }
                    // Validate species: read HP from DNA at offset +4.
                    // Species with HP=0 are likely invalid (mid-structure reads from
                    // wrong species IDs) and would cause ProcessEnemyTilesets to load
                    // garbage tile data into VRAM, corrupting the room's graphics.
                    val specPc = romParser.snesToPc(0xA00000 or specId)
                    val specHp = if (specPc + 6 < romData.size) {
                        (romData[specPc + 4].toInt() and 0xFF) or ((romData[specPc + 5].toInt() and 0xFF) shl 8)
                    } else 0
                    if (specHp == 0) {
                        onLog("WARN: Room 0x$roomKey: skipping species 0x${specId.toString(16)} from GFX set — HP=0 (invalid species ID)")
                        continue
                    }
                    val vramDestination = selectEnemyGfxVramDestination(
                        existingEntries = newEntries,
                        vanillaDestinations = vanillaEnemyGfxDestinationsBySpecies[specId].orEmpty(),
                    )
                    if (vramDestination == null) {
                        onLog("WARN: Room 0x$roomKey: skipping species 0x${specId.toString(16)} from GFX set — no safe VRAM destination available")
                        continue
                    }
                    newEntries.add(RomParser.EnemyGfxEntry(specId, vramDestination))
                    onLog("Room 0x$roomKey: added species 0x${specId.toString(16)} to GFX set (vramDst=0x${vramDestination.toString(16)})")
                }
                if (newEntries.size == gfxEntries.size) return@gfxPatch

                val gfxPc = romParser.snesToPc(0xB40000 or room.enemyGfxPtr)
                val originalGfxSize = gfxEntries.size * 4 + 2
                val newGfxSize = newEntries.size * 4 + 2

                val writeGfxPc: Int
                if (newGfxSize <= originalGfxSize) {
                    writeGfxPc = gfxPc
                } else if (gfxFreePtr + newGfxSize <= bankB4End) {
                    writeGfxPc = gfxFreePtr
                    gfxFreePtr += newGfxSize
                    val newGfxSnes = romParser.pcToSnes(writeGfxPc)
                    val newGfxOffset = newGfxSnes and 0xFFFF
                    val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                    for (stateOffset in allStateOffsets) {
                        val existingPtr = (romData[stateOffset + 10].toInt() and 0xFF) or
                                ((romData[stateOffset + 11].toInt() and 0xFF) shl 8)
                        if (existingPtr == room.enemyGfxPtr) {
                            romData[stateOffset + 10] = (newGfxOffset and 0xFF).toByte()
                            romData[stateOffset + 11] = ((newGfxOffset shr 8) and 0xFF).toByte()
                        }
                    }
                    onLog("Room 0x$roomKey: relocated GFX set to 0x${newGfxSnes.toString(16)}")
                } else {
                    onLog("WARN: Room 0x$roomKey no free space in bank \$B4 for expanded GFX set")
                    return@gfxPatch
                }

                var goff = writeGfxPc
                for (ge in newEntries) {
                    romData[goff] = (ge.speciesId and 0xFF).toByte()
                    romData[goff + 1] = ((ge.speciesId shr 8) and 0xFF).toByte()
                    romData[goff + 2] = (ge.paletteIndex and 0xFF).toByte()
                    romData[goff + 3] = ((ge.paletteIndex shr 8) and 0xFF).toByte()
                    goff += 4
                }
                romData[goff] = 0xFF.toByte()
                romData[goff + 1] = 0xFF.toByte()
            }

            // Patch scroll data
            if ((hasScrollEdits || isResized) && room.roomScrollsPtr > 1) {
                val originalScrolls = romParser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
                // Build scroll array at effective dimensions
                val modifiedScrolls = if (isResized) {
                    val resized = IntArray(effectiveWidth * effectiveHeight) { 1 }
                    for (sy in 0 until minOf(room.height, effectiveHeight))
                        for (sx in 0 until minOf(room.width, effectiveWidth)) {
                            val oldIdx = sy * room.width + sx
                            val newIdx = sy * effectiveWidth + sx
                            if (oldIdx in originalScrolls.indices) resized[newIdx] = originalScrolls[oldIdx]
                        }
                    resized
                } else {
                    originalScrolls.copyOf()
                }
                for (sc in roomEdits.scrollChanges) {
                    val idx = sc.screenY * effectiveWidth + sc.screenX
                    if (idx in modifiedScrolls.indices) modifiedScrolls[idx] = sc.newValue
                }
                val scrollPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or room.roomScrollsPtr)
                if (modifiedScrolls.size <= originalScrolls.size) {
                    // Fits in-place — write and zero any leftover
                    for (i in modifiedScrolls.indices) {
                        if (scrollPc + i < romData.size) romData[scrollPc + i] = modifiedScrolls[i].toByte()
                    }
                    for (i in modifiedScrolls.size until originalScrolls.size) {
                        if (scrollPc + i < romData.size) romData[scrollPc + i] = 0.toByte()
                    }
                } else {
                    // Scroll data grew — MUST relocate to avoid corrupting adjacent data.
                    // Find free space at end of bank $8F by scanning backwards from bank end.
                    val bank8F = (RomConstants.BANK_ROOM_DATA shr 16) and 0xFF
                    val bankEnd = romParser.snesToPc((bank8F shl 16) or 0xFFFF) + 1
                    val bankStart = romParser.snesToPc((bank8F shl 16) or 0x8000)
                    var freePtr = bankEnd
                    while (freePtr > bankStart && (romData[freePtr - 1].toInt() and 0xFF) == 0xFF) freePtr--
                    freePtr++ // first free byte
                    if (freePtr + modifiedScrolls.size <= bankEnd) {
                        // Write scroll data at free space
                        for (i in modifiedScrolls.indices) romData[freePtr + i] = modifiedScrolls[i].toByte()
                        val newSnesPtr = romParser.pcToSnes(freePtr) and 0xFFFF // 16-bit offset within bank
                        // Zero out old scroll data
                        for (i in originalScrolls.indices) {
                            if (scrollPc + i < romData.size) romData[scrollPc + i] = 0xFF.toByte()
                        }
                        // Update scroll pointer in ALL states for this room
                        val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                        for (stateOff in allStateOffsets) {
                            val scrollPtrOff = stateOff + 14
                            romData[scrollPtrOff] = (newSnesPtr and 0xFF).toByte()
                            romData[scrollPtrOff + 1] = ((newSnesPtr shr 8) and 0xFF).toByte()
                        }
                        onLog("Room 0x$roomKey: relocated scroll data \$${room.roomScrollsPtr.toString(16)} to \$8F:${newSnesPtr.toString(16).uppercase()} (${modifiedScrolls.size} bytes, updated ${allStateOffsets.size} state(s))")
                    } else {
                        onLog("WARN: Room 0x$roomKey: no free space in bank \$8F for expanded scroll data (${modifiedScrolls.size} bytes) — writing in-place (WILL CORRUPT adjacent data!)")
                        for (i in modifiedScrolls.indices) {
                            if (scrollPc + i < romData.size) romData[scrollPc + i] = modifiedScrolls[i].toByte()
                        }
                    }
                }
                roomsPatched.add(roomKey)
            }

            // Step 3: Remap scroll PLM command screen indices (old width → new width)
            if (isResized && effectiveWidth != room.width) {
                val allPlms = romParser.getAllPlmEntriesForRoom(roomId)
                val scrollTriggerPlms = allPlms.filter { it.id == 0xB703 }
                val remappedPtrs = mutableSetOf<Int>()
                for (plm in scrollTriggerPlms) {
                    val cmdPtr = plm.param and 0xFFFF
                    if (cmdPtr == 0 || cmdPtr in remappedPtrs) continue
                    remappedPtrs.add(cmdPtr)
                    val snesAddr = 0x8F0000 or cmdPtr
                    val pc = romParser.snesToPc(snesAddr)
                    var offset = 0
                    var remapped = 0
                    while (offset < 256) {
                        val screenIdx = romData[pc + offset].toInt() and 0xFF
                        if (screenIdx >= 0x80) break // terminator
                        // Convert: old flat index → (col, row) → new flat index
                        val col = screenIdx % room.width
                        val row = screenIdx / room.width
                        if (row < effectiveHeight && col < effectiveWidth) {
                            val newIdx = row * effectiveWidth + col
                            romData[pc + offset] = newIdx.toByte()
                            if (newIdx != screenIdx) remapped++
                        }
                        offset += 2
                    }
                    if (remapped > 0) {
                        onLog("Room 0x$roomKey: remapped $remapped screen indices in scroll command at \$8F:${cmdPtr.toString(16).uppercase()} (width ${ room.width}→$effectiveWidth)")
                    }
                }
            }

            // Step 4: Generate new door entry ASM with remapped scroll indices.
            // Door entry ASM sets initial scroll state when Samus enters through a door.
            // These routines are often SHARED across multiple rooms, so patching in-place
            // would corrupt other rooms. Instead we: read scroll writes from the original,
            // generate a new routine with remapped indices, write to free space, and
            // update the door entry pointer.
            if (isResized && effectiveWidth != room.width) {
                val incomingDoors = romParser.findDoorsLeadingTo(roomId)
                val generatedAsmPtrs = mutableMapOf<Int, Int>() // old entryCode → new SNES ptr
                for (door in incomingDoors) {
                    if (door.entryCode == 0 || door.entryCode == 0xFFFF) continue
                    if (door.entryCode in generatedAsmPtrs) continue

                    // Read scroll writes from original ASM
                    val origPc = romParser.snesToPc(0x8F0000 or door.entryCode)
                    data class ScrollWrite(val scrollValue: Int, val screenIdx: Int)
                    val writes = mutableListOf<ScrollWrite>()
                    var i = 0
                    while (i < 60) {
                        val b = romParser.readByteAt(origPc + i)
                        if (b == 0x6B) break // RTL
                        if (b == 0xA9 && i + 5 < 60) {
                            val imm = romParser.readByteAt(origPc + i + 1)
                            val next = romParser.readByteAt(origPc + i + 2)
                            if (next == 0x8F) {
                                val lo = romParser.readByteAt(origPc + i + 3)
                                val hi = romParser.readByteAt(origPc + i + 4)
                                val bank = romParser.readByteAt(origPc + i + 5)
                                if (hi == 0xCD && bank == 0x7E && lo in 0x20..0x7F) {
                                    writes.add(ScrollWrite(imm, lo - 0x20))
                                }
                                i += 6; continue
                            }
                        }
                        if ((b == 0xE2 || b == 0xC2) && i + 1 < 60) { i += 2; continue }
                        i++
                    }
                    if (writes.isEmpty()) continue

                    // Generate new ASM: SEP #$20; [LDA #val; STA $7ECD{20+newIdx}]...; RTL
                    // Each write = 6 bytes (A9 xx 8F ll CD 7E). Header = 2 (E2 20). Footer = 1 (6B).
                    val asmSize = 2 + writes.size * 6 + 1
                    if (freePtr + asmSize > bank8FEnd) {
                        onLog("WARN: Room 0x$roomKey: no free space for door ASM generation (${asmSize} bytes)")
                        continue
                    }
                    val newPc = freePtr
                    romData[freePtr++] = 0xE2.toByte() // SEP
                    romData[freePtr++] = 0x20.toByte() // #$20
                    for (w in writes) {
                        val col = w.screenIdx % room.width
                        val row = w.screenIdx / room.width
                        val newIdx = if (row < effectiveHeight && col < effectiveWidth)
                            row * effectiveWidth + col else w.screenIdx
                        romData[freePtr++] = 0xA9.toByte() // LDA #imm8
                        romData[freePtr++] = w.scrollValue.toByte()
                        romData[freePtr++] = 0x8F.toByte() // STA long
                        romData[freePtr++] = (0x20 + newIdx).toByte()
                        romData[freePtr++] = 0xCD.toByte()
                        romData[freePtr++] = 0x7E.toByte()
                    }
                    romData[freePtr++] = 0x6B.toByte() // RTL
                    val newSnesPtr = romParser.pcToSnes(newPc) and 0xFFFF
                    generatedAsmPtrs[door.entryCode] = newSnesPtr
                    onLog("Room 0x$roomKey: generated new door ASM at \$8F:${newSnesPtr.toString(16).uppercase()} (${writes.size} scroll writes, was \$8F:${door.entryCode.toString(16).uppercase()})")
                }

                // Update door entry entryCode pointers in ROM
                if (generatedAsmPtrs.isNotEmpty()) {
                    val allRooms = com.supermetroid.editor.data.RoomRepository().getAllRooms()
                    for (info in allRooms) {
                        val srcId = info.getRoomIdAsInt()
                        val srcRoom = romParser.readRoomHeader(srcId) ?: continue
                        if (srcRoom.doorOut == 0) continue
                        val doors = romParser.parseDoorList(srcRoom.doorOut)
                        for ((di, d) in doors.withIndex()) {
                            if (d.destRoomPtr == roomId && d.entryCode in generatedAsmPtrs) {
                                val entryPc = romParser.doorEntryPcOffset(srcRoom.doorOut, di) ?: continue
                                val newPtr = generatedAsmPtrs[d.entryCode]!!
                                romData[entryPc + 10] = (newPtr and 0xFF).toByte()
                                romData[entryPc + 11] = ((newPtr shr 8) and 0xFF).toByte()
                            }
                        }
                    }
                }
            }

            // Patch FX data — apply to ALL states' FX pointers, not just default
            if (hasFxEdits) {
                val fx = roomEdits.fxChange!!
                val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                val patchedFxPtrs = mutableSetOf<Int>()
                for (stateOffset in allStateOffsets) {
                    val stateFxPtr = romParser.readUInt16At(stateOffset + 6)
                    if (stateFxPtr == 0 || stateFxPtr == 0xFFFF || stateFxPtr in patchedFxPtrs) continue
                    patchedFxPtrs.add(stateFxPtr)
                    val fxEntries = romParser.parseFxEntries(stateFxPtr)
                    if (fxEntries.isEmpty()) continue
                    val fxSnesAddr = RomConstants.BANK_FX or stateFxPtr
                    var fxPc = romParser.snesToPc(fxSnesAddr)
                    for (entry in fxEntries) {
                        if (entry.doorSelect == 0) {
                            fx.liquidSurfaceStart?.let { v ->
                                romData[fxPc + 2] = (v and 0xFF).toByte()
                                romData[fxPc + 3] = ((v shr 8) and 0xFF).toByte()
                            }
                            fx.liquidSurfaceNew?.let { v ->
                                romData[fxPc + 4] = (v and 0xFF).toByte()
                                romData[fxPc + 5] = ((v shr 8) and 0xFF).toByte()
                            }
                            fx.liquidSpeed?.let { v ->
                                romData[fxPc + 6] = (v and 0xFF).toByte()
                                romData[fxPc + 7] = ((v shr 8) and 0xFF).toByte()
                            }
                            fx.liquidDelay?.let { v -> romData[fxPc + 8] = v.toByte() }
                            fx.fxType?.let { v -> romData[fxPc + 9] = v.toByte() }
                            fx.fxBitA?.let { v -> romData[fxPc + 10] = v.toByte() }
                            fx.fxBitB?.let { v -> romData[fxPc + 11] = v.toByte() }
                            fx.fxBitC?.let { v -> romData[fxPc + 12] = v.toByte() }
                            fx.paletteFxBitflags?.let { v -> romData[fxPc + 13] = v.toByte() }
                            fx.tileAnimBitflags?.let { v -> romData[fxPc + 14] = v.toByte() }
                            fx.paletteBlend?.let { v -> romData[fxPc + 15] = v.toByte() }
                            break
                        }
                        fxPc += 16
                    }
                }
                if (patchedFxPtrs.isNotEmpty()) {
                    onLog("Room 0x$roomKey: patched FX for ${patchedFxPtrs.size} state(s)")
                    roomsPatched.add(roomKey)
                }
            }

            // Patch room header fields (area, map position, scrollers, CRE)
            if (hasHeaderEdits) {
                val hc = roomEdits.roomHeaderChange!!
                val headerPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or roomId)
                hc.index?.let { romData[headerPc] = it.toByte() }
                hc.area?.let { romData[headerPc + 1] = it.toByte() }
                hc.mapX?.let { romData[headerPc + 2] = it.toByte() }
                hc.mapY?.let { romData[headerPc + 3] = it.toByte() }
                hc.width?.let { romData[headerPc + 4] = it.toByte() }
                hc.height?.let { romData[headerPc + 5] = it.toByte() }
                hc.upScroller?.let { romData[headerPc + 6] = it.toByte() }
                hc.downScroller?.let { romData[headerPc + 7] = it.toByte() }
                hc.creBitflag?.let { romData[headerPc + 8] = it.toByte() }
                hc.doorOut?.let {
                    romData[headerPc + 9] = (it and 0xFF).toByte()
                    romData[headerPc + 10] = ((it shr 8) and 0xFF).toByte()
                }
                onLog("Room 0x$roomKey: patched room header")
            }

            // Patch state data fields (tileset, music, BG scrolling)
            if (hasStateEdits) {
                val sd = roomEdits.stateDataChange!!
                val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                for (stateOffset in allStateOffsets) {
                    sd.tileset?.let { v ->
                        romData[stateOffset + 3] = v.toByte()
                    }
                    sd.musicData?.let { v ->
                        romData[stateOffset + 4] = v.toByte()
                    }
                    sd.musicTrack?.let { v ->
                        romData[stateOffset + 5] = v.toByte()
                    }
                    sd.bgScrolling?.let { v ->
                        romData[stateOffset + 12] = (v and 0xFF).toByte()
                        romData[stateOffset + 13] = ((v shr 8) and 0xFF).toByte()
                    }
                }
                roomsPatched.add(roomKey)
            }

            if (hasSaveStationSpawnEdits) {
                fun writeAreaSaveU16(offset: Int, value: Int) {
                    if (offset + 1 >= romData.size) return
                    romData[offset] = (value and 0xFF).toByte()
                    romData[offset + 1] = ((value shr 8) and 0xFF).toByte()
                }

                for (spawn in roomEdits.saveStationSpawns) {
                    val romEntry = romParser.readSaveEntry(spawn.area, spawn.saveIndex)
                    if (romEntry == null) {
                        onLog(
                            "WARN: Room 0x$roomKey save station ${spawn.area}:${spawn.saveIndex} " +
                                "has no writable AreaSave entry; skipped",
                        )
                        continue
                    }
                    val off = romEntry.pcOffset
                    writeAreaSaveU16(off, spawn.roomId)
                    writeAreaSaveU16(off + 2, spawn.doorPtr)
                    writeAreaSaveU16(off + 6, spawn.scrollX)
                    writeAreaSaveU16(off + 8, spawn.scrollY)
                    writeAreaSaveU16(off + 10, spawn.samusY)
                    writeAreaSaveU16(off + 12, spawn.samusX)
                    onLog(
                        "Room 0x$roomKey: patched AreaSave area=${spawn.area} index=${spawn.saveIndex} " +
                            "room=0x${spawn.roomId.toString(16)} door=0x${spawn.doorPtr.toString(16)} " +
                            "scroll=(${spawn.scrollX},${spawn.scrollY}) samus=(${spawn.samusX.toSigned16()},${spawn.samusY.toSigned16()})",
                    )
                    roomsPatched.add(roomKey)
                }
            }
        }
        // Apply custom tileset graphics
        var gfxPatched = 0
        val gfxData = project.customGfx

        // Custom CRE graphics (shared, always at $B9:8000)
        val creB64 = gfxData.creGfx
        if (creB64 != null) {
            try {
                val rawCre = java.util.Base64.getDecoder().decode(creB64)
                val compressed = LZ5Compressor.compress(rawCre)
                val crePc = romParser.snesToPc(TileGraphics.CRE_GFX_SNES)
                val (_, origSize) = romParser.decompressLZ2WithSize(TileGraphics.CRE_GFX_SNES)
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, crePc, compressed.size)
                    for (i in compressed.size until origSize) romData[crePc + i] = 0xFF.toByte()
                    gfxPatched++
                    onLog("Patched CRE graphics in-place (${compressed.size}/$origSize bytes)")
                } else {
                    onLog("WARN: Compressed CRE gfx (${compressed.size}) exceeds original ($origSize) — skipped")
                }
            } catch (e: Exception) { onLog("WARN: CRE gfx patch failed: ${e.message}") }
        }

        // Custom variable (URE) graphics per tileset
        val tablePC = romParser.snesToPc(TileGraphics.TILESET_TABLE_SNES)
        fun writeUInt24(pcOffset: Int, value: Int) {
            romData[pcOffset] = (value and 0xFF).toByte()
            romData[pcOffset + 1] = ((value shr 8) and 0xFF).toByte()
            romData[pcOffset + 2] = ((value shr 16) and 0xFF).toByte()
        }
        val tilesetPaletteAllocator = RomFreeSpaceAllocator(
            romData = romData,
            snesToPc = romParser::snesToPc,
            pcToSnes = romParser::pcToSnes,
            guardBytes = 2,
        )
        for ((tsIdStr, varB64) in gfxData.varGfx) {
            val tsId = tsIdStr.toIntOrNull() ?: continue
            try {
                val rawVar = java.util.Base64.getDecoder().decode(varB64)
                val compressed = LZ5Compressor.compress(rawVar)
                val entryOffset = tablePC + tsId * 9
                val gfxSnes = (romData[entryOffset + 3].toInt() and 0xFF) or
                        ((romData[entryOffset + 4].toInt() and 0xFF) shl 8) or
                        ((romData[entryOffset + 5].toInt() and 0xFF) shl 16)
                val gfxPc = romParser.snesToPc(gfxSnes)
                val (_, origSize) = romParser.decompressLZ2WithSize(gfxSnes)
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, gfxPc, compressed.size)
                    for (i in compressed.size until origSize) romData[gfxPc + i] = 0xFF.toByte()
                    gfxPatched++
                    onLog("Patched tileset $tsId variable gfx in-place (${compressed.size}/$origSize bytes)")
                } else {
                    onLog("WARN: Compressed tileset $tsId gfx (${compressed.size}) exceeds original ($origSize) — skipped")
                }
            } catch (e: Exception) { onLog("WARN: Tileset $tsId gfx patch failed: ${e.message}") }
        }

        // Custom shared CRE metatile table (raw 4-word metatile entries -> LZ5 compress -> write in-place)
        val creTableB64 = gfxData.creTileTable
        if (creTableB64 != null) {
            try {
                val rawCreTable = java.util.Base64.getDecoder().decode(creTableB64)
                if (rawCreTable.isEmpty() || rawCreTable.size % 8 != 0) {
                    onLog("WARN: CRE metatile table has ${rawCreTable.size} bytes (expected non-empty multiple of 8) — skipped")
                } else {
                    val compressed = LZ5Compressor.compress(rawCreTable)
                    val creTablePc = romParser.snesToPc(TileGraphics.CRE_TILE_TABLE_SNES)
                    val (_, origSize) = romParser.decompressLZ2WithSize(TileGraphics.CRE_TILE_TABLE_SNES)
                    if (compressed.size <= origSize) {
                        System.arraycopy(compressed, 0, romData, creTablePc, compressed.size)
                        for (i in compressed.size until origSize) romData[creTablePc + i] = 0xFF.toByte()
                        gfxPatched++
                        onLog("Patched CRE metatile table in-place (${compressed.size}/$origSize bytes)")
                    } else {
                        onLog("WARN: Compressed CRE metatile table (${compressed.size}) exceeds original ($origSize) — skipped")
                    }
                }
            } catch (e: Exception) { onLog("WARN: CRE metatile table patch failed: ${e.message}") }
        }

        // Custom variable (URE) metatile tables per tileset
        for ((tsIdStr, tableB64) in gfxData.tileTables) {
            val tsId = tsIdStr.toIntOrNull() ?: continue
            try {
                val rawTable = java.util.Base64.getDecoder().decode(tableB64)
                if (rawTable.isEmpty() || rawTable.size % 8 != 0) {
                    onLog("WARN: Tileset $tsId metatile table has ${rawTable.size} bytes (expected non-empty multiple of 8) — skipped")
                    continue
                }
                val compressed = LZ5Compressor.compress(rawTable)
                val entryOffset = tablePC + tsId * 9
                val tableSnes = (romData[entryOffset].toInt() and 0xFF) or
                        ((romData[entryOffset + 1].toInt() and 0xFF) shl 8) or
                        ((romData[entryOffset + 2].toInt() and 0xFF) shl 16)
                val tablePc = romParser.snesToPc(tableSnes)
                val (_, origSize) = romParser.decompressLZ2WithSize(tableSnes)
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, tablePc, compressed.size)
                    for (i in compressed.size until origSize) romData[tablePc + i] = 0xFF.toByte()
                    gfxPatched++
                    onLog("Patched tileset $tsId metatile table in-place (${compressed.size}/$origSize bytes)")
                } else {
                    onLog("WARN: Compressed tileset $tsId metatile table (${compressed.size}) exceeds original ($origSize) — skipped")
                }
            } catch (e: Exception) { onLog("WARN: Tileset $tsId metatile table patch failed: ${e.message}") }
        }

        // Custom palette overrides per tileset (raw BGR555 -> LZ5 compress).
        // Randomized palettes often compress larger than vanilla, so relocate
        // them and update the tileset table when an in-place write will not fit.
        for ((tsIdStr, palB64) in gfxData.palettes) {
            val tsId = tsIdStr.toIntOrNull() ?: continue
            try {
                val rawPal = java.util.Base64.getDecoder().decode(palB64)
                if (rawPal.size != 256) { onLog("WARN: Palette $tsId has ${rawPal.size} bytes (expected 256) — skipped"); continue }
                val compressed = LZ5Compressor.compress(rawPal)
                val entryOffset = tablePC + tsId * 9
                val palSnes = (romData[entryOffset + 6].toInt() and 0xFF) or
                        ((romData[entryOffset + 7].toInt() and 0xFF) shl 8) or
                        ((romData[entryOffset + 8].toInt() and 0xFF) shl 16)
                val palPc = romParser.snesToPc(palSnes)
                val (_, origSize) = romParser.decompressLZ2WithSize(palSnes)
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, palPc, compressed.size)
                    for (i in compressed.size until origSize) romData[palPc + i] = 0xFF.toByte()
                    gfxPatched++
                    onLog("Patched tileset $tsId palette in-place (${compressed.size}/$origSize bytes)")
                } else {
                    val origBank = (palSnes shr 16) and 0xFF
                    val banksToTry = (listOf(origBank) + (0xCE downTo 0xC0) + (0xBF downTo 0xB0))
                        .distinct()
                        .filter { bank ->
                            val bankStart = runCatching { romParser.snesToPc((bank shl 16) or 0x8000) }.getOrNull()
                            val bankEnd = runCatching { romParser.snesToPc((bank shl 16) or 0xFFFF) + 1 }.getOrNull()
                            bankStart != null && bankEnd != null && bankStart >= 0 && bankEnd <= romData.size
                        }
                    val allocation = tilesetPaletteAllocator.allocate(
                        bytes = compressed,
                        banks = banksToTry,
                        label = "tileset $tsId palette",
                    )
                    if (allocation != null) {
                        writeUInt24(entryOffset + 6, allocation.snesAddress)
                        for (i in palPc until palPc + origSize) romData[i] = 0xFF.toByte()
                        gfxPatched++
                        onLog(
                            "Relocated tileset $tsId palette \$${palSnes.toString(16)} -> " +
                                "\$${allocation.snesAddress.toString(16)} (${compressed.size}/$origSize bytes)"
                        )
                    } else {
                        onLog("WARN: Compressed tileset $tsId palette (${compressed.size}) exceeds original ($origSize) and no free space was found — skipped")
                    }
                }
            } catch (e: Exception) { onLog("WARN: Tileset $tsId palette patch failed: ${e.message}") }
        }

        // Apply sprite palette overrides (Samus, beams, bosses, enemies — raw BGR555, no compression)
        for ((regionId, palB64) in gfxData.spritePalettes) {
            val region = com.supermetroid.editor.rom.SpritePalettes.findRegion(regionId) ?: continue
            try {
                val rawBytes = java.util.Base64.getDecoder().decode(palB64)
                val colors = com.supermetroid.editor.rom.SpritePalettes.bytesToColors(rawBytes)
                if (colors.size == region.colorCount) {
                    com.supermetroid.editor.rom.SpritePalettes.writeColors(romData, region, colors)
                    gfxPatched++
                    onLog("Patched sprite palette '${region.name}' (${region.byteSize} bytes at 0x${region.offset.toString(16)})")
                }
            } catch (e: Exception) { onLog("WARN: Sprite palette '$regionId' patch failed: ${e.message}") }
        }

        // Apply Phantoon sprite tile patches (raw 4bpp → LZ5 compress → write to $B7)
        onLog("[EXPORT] Phantoon sprite blocks: spriteTileBlocks.keys=${gfxData.spriteTileBlocks.keys}, size=${gfxData.spriteTileBlocks.size}")
        for ((i, block) in com.supermetroid.editor.rom.EnemySpriteGraphics.PHANTOON_BLOCKS.withIndex()) {
            val b64 = gfxData.spriteTileBlocks["phantoon:$i"]
            if (b64 == null) {
                onLog("[EXPORT] Phantoon block $i: NO DATA in spriteTileBlocks (key 'phantoon:$i' not found)")
                continue
            }
            onLog("[EXPORT] Phantoon block $i: found ${b64.length} b64 chars")
            try {
                val rawBytes = java.util.Base64.getDecoder().decode(b64)
                onLog("[EXPORT] Phantoon block $i: decoded to ${rawBytes.size} raw bytes")
                val compressed = LZ5Compressor.compress(rawBytes)
                onLog("[EXPORT] Phantoon block $i: compressed to ${compressed.size} bytes")
                val (_, origSize) = romParser.decompressLZ2WithSize(block.snesAddress)
                onLog("[EXPORT] Phantoon block $i: original compressed size=$origSize, fits=${compressed.size <= origSize}")
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, block.pcAddress, compressed.size)
                    for (j in compressed.size until origSize) romData[block.pcAddress + j] = 0xFF.toByte()
                    gfxPatched++
                    onLog("[EXPORT] Patched Phantoon sprite tile block $i: ${compressed.size}/$origSize bytes at PC=0x${block.pcAddress.toString(16)}")
                } else {
                    onLog("[EXPORT] WARN: Phantoon sprite block $i compressed size ${compressed.size} exceeds original $origSize — skipped")
                }
            } catch (e: Exception) {
                onLog("[EXPORT] WARN: Phantoon sprite block $i patch failed: ${e.message}")
                onLog("[EXPORT] ERROR: Phantoon sprite block $i: ${e.stackTraceToString().lines().first()}")
            }
        }

        // Apply Kraid sprite tile patches (raw 4bpp → LZ5 compress → write to $B9)
        for ((i, block) in com.supermetroid.editor.rom.EnemySpriteGraphics.KRAID_BLOCKS.withIndex()) {
            val b64 = gfxData.spriteTileBlocks["kraid:$i"]
            if (b64 == null) continue
            onLog("[EXPORT] Kraid block $i: found ${b64.length} b64 chars")
            try {
                val rawBytes = java.util.Base64.getDecoder().decode(b64)
                val compressed = LZ5Compressor.compress(rawBytes)
                val (_, origSize) = romParser.decompressLZ2WithSize(block.snesAddress)
                onLog("[EXPORT] Kraid block $i: ${compressed.size}/$origSize bytes")
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, block.pcAddress, compressed.size)
                    for (j in compressed.size until origSize) romData[block.pcAddress + j] = 0xFF.toByte()
                    gfxPatched++
                    onLog("[EXPORT] Patched Kraid sprite tile block $i at PC=0x${block.pcAddress.toString(16)}")
                } else {
                    onLog("[EXPORT] WARN: Kraid sprite block $i compressed size ${compressed.size} exceeds original $origSize — skipped")
                }
            } catch (e: Exception) {
                onLog("[EXPORT] WARN: Kraid sprite block $i patch failed: ${e.message}")
            }
        }

        // Apply generic enemy sprite tile patches (raw 4bpp, uncompressed, write in-place)
        for ((key, b64) in gfxData.spriteTileBlocks) {
            if (!key.startsWith("enemy:")) continue
            val speciesHex = key.removePrefix("enemy:")
            val speciesId = speciesHex.toIntOrNull(16) ?: continue
            try {
                val rawBytes = java.util.Base64.getDecoder().decode(b64)
                val validation = com.supermetroid.editor.rom.EnemySpriteGraphics.validateEnemyTileEdit(
                    romParser = romParser,
                    speciesId = speciesId,
                    rawBytes = rawBytes
                )
                if (!validation.isExportable) {
                    validation.errors.forEach { reason ->
                        onLog("[EXPORT] WARN: Enemy $speciesHex: $reason")
                    }
                    onLog("[EXPORT] WARN: Enemy $speciesHex sprite tile patch skipped")
                    continue
                }
                validation.warnings.forEach { reason ->
                    onLog("[EXPORT] INFO: Enemy $speciesHex: $reason")
                }
                val pcAddress = validation.pcAddress ?: continue
                val snesAddress = validation.snesAddress ?: 0
                System.arraycopy(rawBytes, 0, romData, pcAddress, rawBytes.size)
                gfxPatched++
                onLog("[EXPORT] Patched enemy $speciesHex sprite tiles: ${rawBytes.size} bytes at PC=0x${pcAddress.toString(16)} (SNES \$${snesAddress.toString(16).uppercase()})")
            } catch (e: Exception) {
                onLog("[EXPORT] WARN: Enemy $speciesHex sprite patch failed: ${e.message}")
            }
        }

        // Apply enemy palette patches (32 bytes BGR555 at palPtr address)
        for ((key, b64) in gfxData.spritePalettes) {
            if (!key.startsWith("enemy_pal:")) continue
            val speciesHex = key.removePrefix("enemy_pal:")
            val speciesId = speciesHex.toIntOrNull(16) ?: continue
            try {
                val rawBytes = java.util.Base64.getDecoder().decode(b64)
                if (rawBytes.size != 32) {
                    onLog("[EXPORT] WARN: Enemy palette $speciesHex: expected 32 bytes, got ${rawBytes.size} — skipped")
                    continue
                }
                val rom = romParser.getRomData()
                val headerPc = romParser.snesToPc(com.supermetroid.editor.rom.RomConstants.BANK_ENEMY_AI or speciesId)
                if (headerPc < 0 || headerPc + 0x0D > rom.size) {
                    onLog("[EXPORT] WARN: Enemy palette $speciesHex: invalid species header — skipped")
                    continue
                }
                val palPtr = com.supermetroid.editor.rom.readU16(rom, headerPc + 2)
                val aiBank = com.supermetroid.editor.rom.readU8(rom, headerPc + 0x0C)
                val palSnes = (aiBank shl 16) or (palPtr and 0xFFFF)
                val palPc = romParser.snesToPc(palSnes)
                if (palPc < 0 || palPc + 32 > romData.size) {
                    onLog("[EXPORT] WARN: Enemy palette $speciesHex: palette address out of bounds — skipped")
                    continue
                }
                System.arraycopy(rawBytes, 0, romData, palPc, 32)
                gfxPatched++
                onLog("[EXPORT] Patched enemy $speciesHex palette: 32 bytes at PC=0x${palPc.toString(16)} (SNES \$${palSnes.toString(16).uppercase()})")
            } catch (e: Exception) {
                onLog("[EXPORT] WARN: Enemy palette $speciesHex patch failed: ${e.message}")
            }
        }

        // Apply minimap tile edits
        var minimapPatched = 0
        for ((areaKey, edits) in project.minimapEdits) {
            val area = areaKey.toIntOrNull() ?: continue
            if (area !in 0 until com.supermetroid.editor.rom.MinimapData.NUM_AREAS) continue
            val baseline = romParser.readMinimapTiles(area)
            var patched = baseline
            for (edit in edits) {
                patched = patched.withTile(edit.x, edit.y, edit.tileWord)
            }
            for ((offset, byte) in romParser.writeMinimapTiles(patched)) {
                romData[offset] = byte
            }
            minimapPatched += edits.size
            onLog("Minimap area $area: patched ${edits.size} tiles")
        }

        // ─── Text edits ────────────────────────────────────────────
        var textPatched = 0
        val allText = if (project.textEdits.isNotEmpty()) TextData.readAllText(romParser.getRomData()) else emptyList()
        for ((id, newText) in project.textEdits) {
            val entry = allText.find { it.id == id } ?: continue
            val patched = when (entry.category) {
                TextCategory.AREA_NAME -> TextData.encodeAreaName(newText, entry.rawBytes)
                TextCategory.ESCAPE_TEXT -> TextData.encodeEscapeText(newText, entry.rawBytes)
                TextCategory.UI_MESSAGE -> TextData.encodeUiMessage(newText, entry.rawBytes)
                TextCategory.ITEM_NAME -> TextData.encodeUiMessage(newText, entry.rawBytes)
                TextCategory.INTRO_STORY -> TextData.encodeGreenText(newText, entry.rawBytes)
            }
            for (i in patched.indices) {
                if (entry.pcOffset + i < romData.size) {
                    romData[entry.pcOffset + i] = patched[i]
                }
            }
            textPatched++
        }
        if (textPatched > 0) onLog("[EXPORT] Patched $textPatched text entries")

        // ─── Custom ASM embedding ─────────────────────────────────────
        // Write custom code bytes to free space in bank $A0 and update
        // the species header pointer to the new address.
        var asmPatched = 0
        for ((key, entry) in project.customAsm) {
            val parts = key.split(":")
            if (parts.size != 2) continue
            val speciesId = parts[0].toIntOrNull(16) ?: continue
            val fieldName = parts[1]

            val headerOffset = when (fieldName) {
                "initAi" -> 0x12; "mainAi" -> 0x16; "touchAi" -> 0x30
                "shotAi" -> 0x32; "hurtAi" -> 0x1C; "frozenAi" -> 0x1E
                "grappleAi" -> 0x1A; "deathAnim" -> 0x22
                else -> continue
            }

            val codeBytes = entry.hexBytes.trim().split("\\s+".toRegex())
                .filter { it.isNotEmpty() }
                .mapNotNull { it.toIntOrNull(16)?.toByte() }
                .toByteArray()
            if (codeBytes.isEmpty()) continue

            // Find free space in bank $A0 (scan backwards from end)
            val bankStart = romParser.snesToPc(0xA08000)
            val bankEnd = romParser.snesToPc(0xA0FFFF) + 1
            var freePtr = bankEnd
            while (freePtr > bankStart && romData[freePtr - 1] == 0xFF.toByte()) freePtr--
            freePtr++ // leave 1 byte gap

            if (freePtr + codeBytes.size > bankEnd) {
                onLog("[EXPORT] WARN: Not enough free space in bank \$A0 for custom ASM ($key)")
                continue
            }

            // Write code bytes
            System.arraycopy(codeBytes, 0, romData, freePtr, codeBytes.size)

            // Calculate SNES address
            val newSnesPtr = 0x8000 + (freePtr - bankStart)

            // Update species header pointer
            val headerPc = romParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
            if (headerPc + headerOffset + 1 < romData.size) {
                romData[headerPc + headerOffset] = (newSnesPtr and 0xFF).toByte()
                romData[headerPc + headerOffset + 1] = ((newSnesPtr shr 8) and 0xFF).toByte()
            }

            asmPatched++
            val label = entry.label.ifEmpty { fieldName }
            onLog("[EXPORT] Custom ASM: $label → \$A0:${newSnesPtr.toString(16).uppercase()} (${codeBytes.size} bytes) for species \$${parts[0]}")
        }
        if (asmPatched > 0) onLog("[EXPORT] Embedded $asmPatched custom ASM routine(s)")

        if (roomsPatched.isEmpty() && patchesApplied == 0 && musicPatched == 0 && gfxPatched == 0 && minimapPatched == 0 && textPatched == 0 && asmPatched == 0) {
            val orig = File(romPath)
            val out = File(orig.parent, "${orig.nameWithoutExtension}-${exportSuffix()}.${orig.extension}")
            out.writeBytes(romData)
            onLog("Exported (vanilla copy, no edits): ${out.absolutePath}")
            return out.absolutePath
        }

        // ─── Export verification pass ───────────────────────────────
        // Re-read all modified data from the export copy and validate.
        onLog("\n=== Export Verification ===")
        var verifyErrors = 0
        val exportParser = RomParser(romData)
        for (roomKey in roomsPatched) {
            val roomId = roomKey.toIntOrNull(16) ?: continue
            val room = romParser.readRoomHeader(roomId) ?: continue
            val allStateOffsets = romParser.findAllStateDataOffsets(roomId)

            // Collect per-state data from the EXPORT copy
            val stateInfos = mutableListOf<String>()
            val distinctLevelPtrs = mutableSetOf<Int>()
            val distinctPlmPtrs = mutableSetOf<Int>()
            for ((si, stateOffset) in allStateOffsets.withIndex()) {
                val lvlPtr = (romData[stateOffset].toInt() and 0xFF) or
                        ((romData[stateOffset + 1].toInt() and 0xFF) shl 8) or
                        ((romData[stateOffset + 2].toInt() and 0xFF) shl 16)
                val plmPtr = (romData[stateOffset + 20].toInt() and 0xFF) or
                        ((romData[stateOffset + 21].toInt() and 0xFF) shl 8)
                distinctLevelPtrs.add(lvlPtr)
                distinctPlmPtrs.add(plmPtr)
                stateInfos.add("  state[$si] levelData=\$${lvlPtr.toString(16)} plmSet=\$${plmPtr.toString(16)}")
            }

            if (allStateOffsets.size > 1 || distinctLevelPtrs.size > 1 || distinctPlmPtrs.size > 1) {
                onLog("Room 0x$roomKey: ${allStateOffsets.size} states, ${distinctLevelPtrs.size} distinct level ptrs, ${distinctPlmPtrs.size} distinct PLM ptrs")
                for (info in stateInfos) onLog(info)
            }

            // Verify each distinct level data pointer decompresses correctly
            for (lvlPtr in distinctLevelPtrs) {
                if (lvlPtr == 0) continue
                try {
                    val decompressed = exportParser.decompressLZ2(lvlPtr)
                    if (decompressed.isEmpty()) {
                        onLog("  ERROR: level data at \$${lvlPtr.toString(16)} decompressed to 0 bytes!")
                        verifyErrors++
                    }
                    // Check for door blocks (type 9) and report them
                    val blockCount = room.width * 16 * room.height * 16
                    val l1size = if (decompressed.size >= 2) (decompressed[0].toInt() and 0xFF) or ((decompressed[1].toInt() and 0xFF) shl 8) else 0
                    var doorBlockCount = 0
                    for (bi in 0 until minOf(blockCount, l1size / 2)) {
                        val off = 2 + bi * 2
                        if (off + 1 >= decompressed.size) break
                        val word = (decompressed[off].toInt() and 0xFF) or ((decompressed[off + 1].toInt() and 0xFF) shl 8)
                        if ((word shr 12) and 0xF == 9) doorBlockCount++
                    }
                    if (doorBlockCount > 0) {
                        onLog("  level data \$${lvlPtr.toString(16)}: $doorBlockCount door blocks (type 9)")
                    }
                } catch (e: Exception) {
                    onLog("  ERROR: failed to decompress level data at \$${lvlPtr.toString(16)}: ${e.message}")
                    verifyErrors++
                }
            }

            // Verify each distinct PLM set is properly terminated
            for (plmPtr in distinctPlmPtrs) {
                if (plmPtr == 0 || plmPtr == 0xFFFF) continue
                val plms = exportParser.parsePlmSet(plmPtr)
                val doorCaps = plms.filter { RomParser.doorCapColor(it.id) != null }
                if (doorCaps.isNotEmpty()) {
                    onLog("  PLM set \$${plmPtr.toString(16)}: ${plms.size} entries, ${doorCaps.size} door cap(s):")
                    for (dc in doorCaps) {
                        val name = RomParser.doorCapDisplayName(dc.id) ?: "Unknown"
                        onLog("    $name at (${dc.x},${dc.y}) param=0x${dc.param.toString(16)}")
                    }
                }
            }
        }
        if (verifyErrors > 0) {
            onLog("EXPORT VERIFICATION: $verifyErrors error(s) found!")
        } else {
            onLog("EXPORT VERIFICATION: all checks passed")
        }
        onLog("=== End Verification ===\n")

        val orig = File(romPath)
        val out = File(orig.parent, "${orig.nameWithoutExtension}-${exportSuffix()}.${orig.extension}")
        out.writeBytes(romData)
        val msg = "Exported ROM: ${out.absolutePath} (${roomsPatched.size} rooms, $patchesApplied patches, $musicPatched music, $gfxPatched gfx)"
        onLog(msg)
        onStatus(msg)
        return out.absolutePath
    }

    /**
     * Export an IPS patch by diffing the patched ROM against the original.
     * Reuses [exportToRom] to build the patched data, then generates IPS records
     * for every changed byte range.
     */
    fun exportIps(): String? {
        val romPath = project.romPath
        if (romPath.isEmpty()) return null

        val original = romParser.getRomData()
        val smcPath = export() ?: return null
        val patched = File(smcPath).readBytes()

        if (original.size != patched.size) {
            onLog("[IPS] ROM size mismatch: ${original.size} vs ${patched.size}")
            return null
        }

        val ipsData = buildIpsPatch(original, patched)
        val orig = File(romPath)
        val ipsFile = File(orig.parent, "${orig.nameWithoutExtension}-${exportSuffix()}.ips")
        ipsFile.writeBytes(ipsData)
        val msg = "Exported IPS: ${ipsFile.absolutePath} (${ipsData.size} bytes)"
        onLog(msg)
        onStatus(msg)
        return ipsFile.absolutePath
    }

}

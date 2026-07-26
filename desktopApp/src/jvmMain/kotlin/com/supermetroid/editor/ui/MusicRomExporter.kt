package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicTrackEdit
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData

/** Handles SPC music edit export — writing relocated transfer chains to the ROM. */
internal class MusicRomExporter(
    private val project: SmEditProject,
    private val onLog: (String) -> Unit,
) {
    internal fun applyMusicEditsToRom(romData: ByteArray): Int {
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
                    accumulatedWrites.hasNativePayload = true
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
                val chainBytes = writeRelocatedSongSetTransferChain(
                    romData, songSet, accumulatedWrites.toWriteMap(), accumulatedWrites.hasNativePayload
                )
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
        val ownersByAddr: MutableMap<Int, String> = mutableMapOf(),
        var hasNativePayload: Boolean = false
    ) {
        fun isNotEmpty(): Boolean = bytesByAddr.isNotEmpty()

        fun toWriteMap(): Map<Int, ByteArray> = SpcData.packSpcBytesToWrites(bytesByAddr)
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

    private fun compactSpcWriteBytes(bytesByAddr: java.util.TreeMap<Int, Int>): Map<Int, ByteArray> =
        SpcData.packSpcBytesToWrites(bytesByAddr)

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
        spcWrites: Map<Int, ByteArray>,
        hasNativePayload: Boolean = false
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

        // Always try merging at the SPC RAM byte level first — this preserves data for
        // other play indices in the same song set while applying the patch writes on top.
        // If the merged chain exceeds one LoROM bank (32 KiB), fall back to payload-only
        // for native payloads: those payloads replace the instrument/sample table so other
        // arrangements would be broken by them anyway, and the original bytes only add size.
        val mergedBlocks = mergeSpcRamBlocks(originalBlocks, spcWrites)
        val mergedChain = serializeTransferChain(mergedBlocks)
        val maxBank = MusicTransferChainBudget.MAX_SINGLE_LOROM_BANK_BYTES
        val (relocatedChain, chainMode) = when {
            mergedChain.size <= maxBank -> mergedChain to "merged"
            hasNativePayload -> {
                val payloadOnlyBlocks = buildSpcPatchBlocks(spcWrites)
                val payloadChain = serializeTransferChain(payloadOnlyBlocks)
                onLog(
                    "[EXPORT] SongSet 0x${songSet.toString(16).padStart(2, '0')}: merged chain " +
                        "${mergedChain.size} B > $maxBank B bank limit; falling back to payload-only " +
                        "(${payloadChain.size} B) — other arrangements in this song set may be affected"
                )
                payloadChain to "payload-only"
            }
            else -> mergedChain to "merged" // let findMusicTransferFreeSpace report the error
        }
        val writePc = findMusicTransferFreeSpace(parser, romData, relocatedChain.size)
        relocatedChain.copyInto(romData, writePc)

        val relocatedPointer = parser.pcToSnes(writePc)
        writeRomU24(romData, pointerEntryPc, relocatedPointer)

        onLog(
            "[EXPORT] Relocated songSet 0x${songSet.toString(16).padStart(2, '0')} transfer chain " +
                "\$${originalPointer.toString(16).uppercase().padStart(6, '0')} -> " +
                "\$${relocatedPointer.toString(16).uppercase().padStart(6, '0')} " +
                "($chainMode, ${relocatedChain.size} bytes)"
        )
        return relocatedChain.size
    }

    /**
     * Merges [originalBlocks] and [spcWrites] at the SPC RAM byte level.
     * Original bytes are applied first; [spcWrites] overwrite them where they overlap.
     * The result contains exactly the union of all covered SPC RAM addresses.
     */
    private fun mergeSpcRamBlocks(
        originalBlocks: List<SpcData.TransferBlock>,
        spcWrites: Map<Int, ByteArray>
    ): List<SpcData.TransferBlock> {
        val ram = ByteArray(RomConstants.SPC_RAM_SIZE)
        val covered = BooleanArray(RomConstants.SPC_RAM_SIZE)

        for (block in originalBlocks) {
            val dest = block.destAddr and 0xFFFF
            for (i in block.data.indices) {
                ram[dest + i] = block.data[i]
                covered[dest + i] = true
            }
        }
        for ((addr, data) in spcWrites) {
            val dest = addr and 0xFFFF
            for (i in data.indices) {
                ram[dest + i] = data[i]
                covered[dest + i] = true
            }
        }

        val bytesByAddr = sortedMapOf<Int, Int>()
        for (i in covered.indices) {
            if (covered[i]) bytesByAddr[i] = ram[i].toInt() and 0xFF
        }
        return SpcData.packSpcBytesToWrites(bytesByAddr)
            .map { (addr, data) -> SpcData.TransferBlock(addr, data) }
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
            for (i in data.indices) bytesByAddr[dest + i] = data[i].toInt() and 0xFF
        }
        return SpcData.packSpcBytesToWrites(bytesByAddr)
            .map { (addr, data) -> SpcData.TransferBlock(addr, data) }
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
}

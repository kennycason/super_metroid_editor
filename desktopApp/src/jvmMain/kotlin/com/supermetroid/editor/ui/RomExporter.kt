package com.supermetroid.editor.ui

import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.data.withVanillaHexPatchPreconditions
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.ProjectRoomExportException
import com.supermetroid.editor.rom.ProjectRoomExporter
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomFreeSpaceAllocator
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlan
import com.supermetroid.editor.rom.RomWritePlanException
import com.supermetroid.editor.rom.RomResourceAccess
import com.supermetroid.editor.rom.RomResourceClaim
import com.supermetroid.editor.rom.RoomNamePauseMapPatch
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

    private val music = MusicRomExporter(project, onLog)

    private fun exportSuffix(): String {
        val version = "v${project.versionMajor}.${project.versionMinor}"
        val build = project.buildName.trim()
        return if (build.isNotEmpty()) "$build-$version" else version
    }

    fun export(): String? {
        val romPath = project.romPath
        if (romPath.isEmpty()) return null
        hydratePatchSafetyMetadata()
        onLog("[EXPORT] Starting export — romPath=$romPath, romSize=${romParser.getRomData().size}")
        onLog("[EXPORT] Project spriteTileBlocks keys: ${project.customGfx.spriteTileBlocks.keys}")
        val originalRom = romParser.getRomData()
        val headerSize = if (originalRom.size % 0x8000 == RomConstants.SMC_HEADER_SIZE) {
            RomConstants.SMC_HEADER_SIZE
        } else {
            0
        }
        val writePlan = RomWritePlan(originalRom, headerSize)
        val romData = writePlan.romData
        val inputRomHash = bytesSha256(originalRom.copyOfRange(headerSize, originalRom.size))
        val roomsPatched = mutableSetOf<String>()

        val patchesApplied: Int
        val musicPatched: Int
        val gfxPatched: Int
        val minimapPatched: Int
        val textPatched: Int
        val asmPatched: Int
        try {
            // Apply patches FIRST so free-space scanners see code/data already
            // claimed by fixed patches before generated allocators run.
            patchesApplied = applyPatches(writePlan, inputRomHash) ?: return null

            musicPatched = writePlan.capture(
                owner = "music:project",
                label = "Project music edits",
                kind = RomWriteKind.MUSIC,
            ) { music.applyMusicEditsToRom(it) }
            if (musicPatched > 0) onLog("[EXPORT] Patched $musicPatched music track edit(s)")

            claimPerFrameHookResources(writePlan)
            writePlan.capture(
                owner = "generated:per-frame-hook",
                label = "Combined per-frame hook",
                kind = RomWriteKind.HOOK,
            ) { applyPerFrameHook(it) }

            try {
                for ((roomKey, roomEdits) in project.rooms) {
                    if (!roomEdits.hasEdits) continue
                    val owner = "room-graph:project"
                    val roomProject = project.copy(rooms = mutableMapOf(roomKey to roomEdits))
                    val roomExportResult = writePlan.capture(
                        owner = owner,
                        label = "Room 0x$roomKey edits",
                        kind = RomWriteKind.ROOM,
                        overlapPolicy = com.supermetroid.editor.rom.RomOverlapPolicy.ALLOW_SAME_OWNER,
                    ) { workingRom ->
                        ProjectRoomExporter(
                            project = roomProject,
                            romParser = RomParser(workingRom),
                            romData = workingRom,
                            roomAreaOverrides = project.rooms.mapNotNull { (key, edits) ->
                                val roomId = key.toIntOrNull(16) ?: return@mapNotNull null
                                edits.roomHeaderChange?.area?.let { roomId to it }
                            }.toMap(),
                            onLog = onLog,
                        ).exportRooms()
                    }
                    roomsPatched.addAll(roomExportResult.roomsPatched)
                    for (allocation in roomExportResult.allocations) {
                        writePlan.claimCurrentRange(
                            owner = owner,
                            label = allocation.label,
                            offset = allocation.pcOffset - writePlan.headerSize,
                            size = allocation.size,
                        )
                    }
                }
            } catch (e: ProjectRoomExportException) {
                val message = "Export failed: ${e.message ?: "room edits could not be written safely"}"
                onLog("ERROR: $message")
                onStatus(message)
                return null
            }

            gfxPatched = applyCustomGfxPatches(writePlan)
            minimapPatched = applyMinimapEdits(writePlan)
            textPatched = applyTextEdits(writePlan)
            asmPatched = applyCustomAsm(writePlan)
        } catch (e: RomWritePlanException) {
            val message = "Export failed safely: ${e.message}"
            onLog("ERROR: $message")
            onStatus(message)
            return null
        } catch (e: Exception) {
            val message = "Export failed safely: ${e.message ?: e::class.simpleName}"
            onLog("ERROR: $message")
            onStatus(message)
            return null
        }

        for (line in writePlan.report().logLines()) onLog(line)

        if (roomsPatched.isEmpty() && patchesApplied == 0 && musicPatched == 0 && gfxPatched == 0 && minimapPatched == 0 && textPatched == 0 && asmPatched == 0) {
            val orig = File(romPath)
            val out = File(orig.parent, "${orig.nameWithoutExtension}-${exportSuffix()}.${orig.extension}")
            out.writeBytes(romData)
            onLog("Exported (vanilla copy, no edits): ${out.absolutePath}")
            return out.absolutePath
        }

        val verificationErrors = try {
            verifyExportedRom(romData, roomsPatched)
        } catch (e: Exception) {
            val message = "Export failed safely during verification: ${e.message ?: e::class.simpleName}"
            onLog("ERROR: $message")
            onStatus(message)
            return null
        }
        if (verificationErrors > 0) {
            val message = "Export failed safely: post-export verification found $verificationErrors error(s)"
            onLog("ERROR: $message")
            onStatus(message)
            return null
        }

        val orig = File(romPath)
        val out = File(orig.parent, "${orig.nameWithoutExtension}-${exportSuffix()}.${orig.extension}")
        out.writeBytes(romData)
        val msg = "Exported ROM: ${out.absolutePath} (${roomsPatched.size} rooms, $patchesApplied patches, $musicPatched music, $gfxPatched gfx)"
        onLog(msg)
        onStatus(msg)
        return out.absolutePath
    }

    private fun hydratePatchSafetyMetadata() {
        val catalog = (
            withVanillaHexPatchPreconditions(HARDCODED_PATCHES) +
                runCatching { PatchRepository.loadBundledPatches() }.getOrDefault(emptyList())
            ).associateBy { it.id }
        for (target in project.patches) {
            val source = catalog[target.id] ?: continue
            if (target.compatibleRomHashes.isEmpty()) {
                target.compatibleRomHashes.addAll(source.compatibleRomHashes)
            }
            if (target.resources.isEmpty()) {
                target.resources.addAll(source.resources.map { it.copy() })
            }
            val sourceWrites = source.writes.associateBy { it.offset to it.bytes }
            for (index in target.writes.indices) {
                val current = target.writes[index]
                if (current.expectedBytes != null) continue
                val expected = sourceWrites[current.offset to current.bytes]?.expectedBytes ?: continue
                target.writes[index] = current.copy(expectedBytes = expected.toList())
            }
        }
    }

    /**
     * Applies all enabled patches to romData (configType-specific handlers + raw hex writes),
     * then applies any deferred generated patches (e.g. room name pause map).
     * Returns total patches applied, or null if a deferred patch fails.
     */
    private fun applyPatches(writePlan: RomWritePlan, inputRomHash: String): Int? {
        val romData = writePlan.romData
        var patchesApplied = 0
        val enabledCount = project.patches.count { it.enabled }
        val disabledCount = project.patches.size - enabledCount
        val deferredGeneratedPatches = mutableListOf<SmPatch>()
        onLog("[EXPORT] Patches: $enabledCount enabled, $disabledCount disabled (${project.patches.size} total)")
        for (patch in project.patches) {
            if (!patch.enabled) continue
            onLog("[EXPORT] Applying patch: '${patch.name}' [${patch.id}] configType=${patch.configType ?: "hex"}")
            claimPatchResources(writePlan, patch)
            if (patch.configType == null) {
                if (patch.id == "bundled_infinite_blue_suit") {
                    onLog("[EXPORT]   (deferred to combined per-frame hook; legacy standalone hook suppressed)")
                    patchesApplied++
                    continue
                }
                if (patch.compatibleRomHashes.isNotEmpty() &&
                    patch.compatibleRomHashes.none { it.equals(inputRomHash, ignoreCase = true) }
                ) {
                    error(
                        "'${patch.name}' is not compatible with input ROM SHA-256 $inputRomHash; " +
                            "supported hashes: ${patch.compatibleRomHashes.joinToString()}"
                    )
                }
                val totalBytes = patch.writes.sumOf { it.bytes.size }
                for ((index, write) in patch.writes.withIndex()) {
                    writePlan.add(
                        owner = "patch:${patch.id}",
                        label = "${patch.name} record ${index + 1}",
                        offset = write.offset.toInt(),
                        bytes = write.bytes,
                        kind = RomWriteKind.FIXED_PATCH,
                        expectedBefore = write.expectedBytes,
                        preconditionRecommended = patch.compatibleRomHashes.isEmpty(),
                    )
                }
                onLog("[EXPORT]   Hex writes: ${patch.writes.size} records, $totalBytes bytes")
                if (patch.id == "bundled_spider_ball") {
                    val flatHash = bytesSha256(patch.writes.flatMap { it.bytes })
                    val header = writePlan.headerSize
                    onLog(
                        "[EXPORT]   Spider Ball proof: records=${patch.writes.size}, bytes=$totalBytes, sha256=$flatHash, " +
                            "movePtr@0x82353=${romData.hexAt(header + 0x82353, 2)}, " +
                            "posePtr@0x8801C=${romData.hexAt(header + 0x8801C, 2)}, " +
                            "code@0x87800=${romData.hexAt(header + 0x87800, 12)}, " +
                            "guard@0x880BE=${romData.hexAt(header + 0x880BE, 12)}, " +
                            "plm@0x27200=${romData.hexAt(header + 0x27200, 12)}"
                    )
                }
                patchesApplied++
                continue
            }

            val beforePatch = romData.copyOf()
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
                    writeU16(romData, romParser.snesToPc(field.snesAddress), value)
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
                    val snesAddr = RomConstants.BANK_ENEMY_AI or e.speciesId
                    val basePc = romParser.snesToPc(snesAddr)
                    data["${e.key}_hp"]?.let { writeU16(romData, basePc + 4, it); modCount++ }
                    data["${e.key}_dmg"]?.let { writeU16(romData, basePc + 6, it); modCount++ }
                }
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
                    writeU8(romData, field.pcOffset, value)
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
                writeU16(romData, BOMB_ACTIVE_HARD_CAP_OPERAND_PC, maxActive)
                writeU8(romData, BOMB_COOLDOWN_PC, cooldownFrames)
                writeU16(romData, BOMB_FUSE_TIMER_PC, fuseFrames)
                writeU16(romData, BOMB_EXPLOSION_FRAME_DELAY_OPERAND_PC, explosionDelay)
                onLog(
                    "[EXPORT]   Bombs: maxActive=$maxActive, fuse=$fuseFrames frames, " +
                        "cooldown=$cooldownFrames frames, explosionDelay=$explosionDelay"
                )
            } else if (patch.configType == FANFARE_CONFIG_TYPE) {
                val data = patch.configData
                val defaults = readFanfareRomDefaults(romParser)
                val frames = (data?.get(FANFARE_FRAMES_KEY) ?: defaults.itemFanfareFrames)
                    .coerceIn(FANFARE_MIN_FRAMES, FANFARE_MAX_FRAMES)
                writeU16(romData, FANFARE_MESSAGE_BOX_WAIT_PC, frames)
                for (offset in FANFARE_MUSIC_RESUME_DELAY_PCS) {
                    writeU16(romData, offset, frames)
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
                    writeU16(romData, CONTROLLER_TABLE_PC + slot.tableIndex * 2, value)
                    slotCount++
                }
                onLog("[EXPORT]   Controller config: $slotCount buttons remapped")
            } else if (patch.configType == RoomNamePauseMapPatch.CONFIG_TYPE) {
                deferredGeneratedPatches.add(patch)
                onLog("[EXPORT]   (deferred until fixed patch writes are applied)")
            } else if (patch.configType == "boss_defeated" || patch.configType == "hyper_beam") {
                onLog("[EXPORT]   (deferred to combined per-frame hook)")
            } else {
                error("Unsupported configured patch type '${patch.configType}' for '${patch.name}'")
            }
            writePlan.recordExternalMutation(
                owner = "patch:${patch.id}",
                label = patch.name,
                before = beforePatch,
                kind = RomWriteKind.CONFIG,
            )
            patchesApplied++
        }

        for (patch in deferredGeneratedPatches) {
            try {
                val beforeGeneratedPatch = romData.copyOf()
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
                writePlan.recordDeclaredMutation(
                    before = beforeGeneratedPatch,
                    declaredWrites = result.writes.mapIndexed { index, write ->
                        com.supermetroid.editor.rom.RomWriteIntent(
                            owner = "patch:${patch.id}",
                            label = if (index == 0) "${patch.name} payload" else "${patch.name} hook $index",
                            offset = write.offset.toInt() - writePlan.headerSize,
                            bytes = write.bytes,
                            kind = if (index == 0) RomWriteKind.ALLOCATION else RomWriteKind.HOOK,
                            expectedBefore = write.expectedBytes,
                        )
                    },
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

        return patchesApplied
    }

    private fun claimPatchResources(writePlan: RomWritePlan, patch: SmPatch) {
        val owner = "patch:${patch.id}"
        for (resource in patch.resources) {
            writePlan.claimResource(
                RomResourceClaim(
                    owner = owner,
                    namespace = resource.namespace,
                    start = resource.start,
                    endInclusive = resource.endInclusive,
                    label = resource.label.ifBlank { patch.name },
                    access = if (resource.access.equals("shared", ignoreCase = true)) {
                        RomResourceAccess.SHARED
                    } else {
                        RomResourceAccess.EXCLUSIVE
                    },
                    sharedGroup = resource.sharedGroup,
                )
            )
        }
        if (patch.configType == RoomNamePauseMapPatch.CONFIG_TYPE) {
            for (hookSnes in listOf(0x828D25, 0x8291DD)) {
                val hookPc = romParser.snesToPc(hookSnes) - writePlan.headerSize
                writePlan.claimResource(
                    RomResourceClaim(owner, "rom_hook", hookPc, hookPc + 3, "Pause-map load hook")
                )
            }
            writePlan.claimResource(
                RomResourceClaim(owner, "vram", 0x38C0, 0x38EF, "Pause-map room-name row")
            )
        }
    }

    private fun claimPerFrameHookResources(writePlan: RomWritePlan) {
        val participants = project.patches.filter { patch ->
            patch.enabled && (
                patch.configType == "boss_defeated" ||
                    patch.configType == "hyper_beam" ||
                    patch.id == "bundled_infinite_blue_suit"
                )
        }
        for (patch in participants) {
            writePlan.claimResource(
                RomResourceClaim(
                    owner = "patch:${patch.id}",
                    namespace = "rom_hook",
                    start = 0x1096E,
                    endInclusive = 0x10971,
                    label = "Combined per-frame hook",
                    access = RomResourceAccess.SHARED,
                    sharedGroup = "combined-per-frame-hook",
                )
            )
        }
    }

    /** Applies all custom GFX edits (tileset gfx/tables/palettes, sprite tiles, enemy palettes). Returns count of items patched. */
    private fun applyCustomGfxPatches(writePlan: RomWritePlan): Int {
        val romData = writePlan.romData
        var gfxPatched = 0
        val gfxData = project.customGfx

        // Helper: LZ5-compress rawData and write in-place at snesPtr if it fits, or warn and skip.
        fun writeLZ5InPlace(rawData: ByteArray, snesPtr: Int, label: String): Boolean {
            val compressed = LZ5Compressor.compress(rawData)
            val pcOffset = romParser.snesToPc(snesPtr)
            val (_, origSize) = romParser.decompressLZ2WithSize(snesPtr)
            return if (compressed.size <= origSize) {
                writePlan.capture("graphics:$label", label, RomWriteKind.GRAPHICS) { _ ->
                    System.arraycopy(compressed, 0, romData, pcOffset, compressed.size)
                    for (i in compressed.size until origSize) romData[pcOffset + i] = 0xFF.toByte()
                }
                onLog("Patched $label in-place (${compressed.size}/$origSize bytes)")
                true
            } else {
                onLog("WARN: Compressed $label (${compressed.size}) exceeds original ($origSize) — skipped")
                false
            }
        }

        // Custom CRE graphics (shared, always at $B9:8000)
        val creB64 = gfxData.creGfx
        if (creB64 != null) {
            try {
                val rawCre = java.util.Base64.getDecoder().decode(creB64)
                if (writeLZ5InPlace(rawCre, TileGraphics.CRE_GFX_SNES, "CRE graphics")) gfxPatched++
            } catch (e: Exception) { onLog("WARN: CRE gfx patch failed: ${e.message}") }
        }

        // Custom variable (URE) graphics per tileset
        val tablePC = romParser.snesToPc(TileGraphics.TILESET_TABLE_SNES)
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
                val entryOffset = tablePC + tsId * 9
                val gfxSnes = (romData[entryOffset + 3].toInt() and 0xFF) or
                        ((romData[entryOffset + 4].toInt() and 0xFF) shl 8) or
                        ((romData[entryOffset + 5].toInt() and 0xFF) shl 16)
                if (writeLZ5InPlace(rawVar, gfxSnes, "tileset $tsId variable gfx")) gfxPatched++
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
                    if (writeLZ5InPlace(rawCreTable, TileGraphics.CRE_TILE_TABLE_SNES, "CRE metatile table")) gfxPatched++
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
                val entryOffset = tablePC + tsId * 9
                val tableSnes = (romData[entryOffset].toInt() and 0xFF) or
                        ((romData[entryOffset + 1].toInt() and 0xFF) shl 8) or
                        ((romData[entryOffset + 2].toInt() and 0xFF) shl 16)
                if (writeLZ5InPlace(rawTable, tableSnes, "tileset $tsId metatile table")) gfxPatched++
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
                    writePlan.capture(
                        "graphics:tileset-$tsId-palette",
                        "Tileset $tsId palette",
                        RomWriteKind.GRAPHICS,
                    ) { _ ->
                        System.arraycopy(compressed, 0, romData, palPc, compressed.size)
                        for (i in compressed.size until origSize) romData[palPc + i] = 0xFF.toByte()
                    }
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
                    val allocation = writePlan.capture(
                        "graphics:tileset-$tsId-palette",
                        "Tileset $tsId palette relocation",
                        RomWriteKind.GRAPHICS,
                    ) { _ ->
                        tilesetPaletteAllocator.allocate(
                            bytes = compressed,
                            banks = banksToTry,
                            label = "tileset $tsId palette",
                        )?.also { allocated ->
                            writeU24(romData, entryOffset + 6, allocated.snesAddress)
                            for (i in palPc until palPc + origSize) romData[i] = 0xFF.toByte()
                        }
                    }
                    if (allocation != null) {
                        writePlan.claimCurrentRange(
                            owner = "graphics:tileset-$tsId-palette",
                            label = allocation.label,
                            offset = allocation.pcOffset - writePlan.headerSize,
                            size = allocation.size,
                        )
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
                    writePlan.capture(
                        "graphics:sprite-palette-$regionId",
                        "Sprite palette ${region.name}",
                        RomWriteKind.GRAPHICS,
                    ) { _ ->
                        com.supermetroid.editor.rom.SpritePalettes.writeColors(romData, region, colors)
                    }
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
                    writePlan.capture(
                        "graphics:phantoon-$i",
                        "Phantoon sprite block $i",
                        RomWriteKind.GRAPHICS,
                    ) { _ ->
                        System.arraycopy(compressed, 0, romData, block.pcAddress, compressed.size)
                        for (j in compressed.size until origSize) romData[block.pcAddress + j] = 0xFF.toByte()
                    }
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
                    writePlan.capture(
                        "graphics:kraid-$i",
                        "Kraid sprite block $i",
                        RomWriteKind.GRAPHICS,
                    ) { _ ->
                        System.arraycopy(compressed, 0, romData, block.pcAddress, compressed.size)
                        for (j in compressed.size until origSize) romData[block.pcAddress + j] = 0xFF.toByte()
                    }
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
                    validation.errors.forEach { reason -> onLog("[EXPORT] WARN: Enemy $speciesHex: $reason") }
                    onLog("[EXPORT] WARN: Enemy $speciesHex sprite tile patch skipped")
                    continue
                }
                validation.warnings.forEach { reason -> onLog("[EXPORT] INFO: Enemy $speciesHex: $reason") }
                val pcAddress = validation.pcAddress ?: continue
                val snesAddress = validation.snesAddress ?: 0
                writePlan.capture(
                    "graphics:enemy-$speciesHex",
                    "Enemy $speciesHex sprite tiles",
                    RomWriteKind.GRAPHICS,
                ) { _ ->
                    System.arraycopy(rawBytes, 0, romData, pcAddress, rawBytes.size)
                }
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
                writePlan.capture(
                    "graphics:enemy-palette-$speciesHex",
                    "Enemy $speciesHex palette",
                    RomWriteKind.GRAPHICS,
                ) { _ ->
                    System.arraycopy(rawBytes, 0, romData, palPc, 32)
                }
                gfxPatched++
                onLog("[EXPORT] Patched enemy $speciesHex palette: 32 bytes at PC=0x${palPc.toString(16)} (SNES \$${palSnes.toString(16).uppercase()})")
            } catch (e: Exception) {
                onLog("[EXPORT] WARN: Enemy palette $speciesHex patch failed: ${e.message}")
            }
        }

        return gfxPatched
    }

    private fun writeU8(romData: ByteArray, offset: Int, value: Int) {
        if (offset < romData.size) romData[offset] = (value and 0xFF).toByte()
    }

    private fun writeU16(romData: ByteArray, offset: Int, value: Int) {
        if (offset + 1 < romData.size) {
            romData[offset] = (value and 0xFF).toByte()
            romData[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
    }

    private fun writeU24(romData: ByteArray, offset: Int, value: Int) {
        if (offset + 2 < romData.size) {
            romData[offset] = (value and 0xFF).toByte()
            romData[offset + 1] = ((value shr 8) and 0xFF).toByte()
            romData[offset + 2] = ((value shr 16) and 0xFF).toByte()
        }
    }

    /** Scans backwards from [end] to find the first non-0xFF byte, then returns that position + 1. */
    private fun scanFreeSpaceEnd(romData: ByteArray, start: Int, end: Int): Int {
        var ptr = end
        while (ptr > start && romData[ptr - 1] == 0xFF.toByte()) ptr--
        return ptr + 1
    }

    private fun applyMinimapEdits(writePlan: RomWritePlan): Int {
        val romData = writePlan.romData
        var patched = 0
        for ((areaKey, edits) in project.minimapEdits) {
            val area = areaKey.toIntOrNull()
                ?: throw RomWritePlanException("Invalid minimap area key '$areaKey'; expected 0-6")
            if (area !in 0 until com.supermetroid.editor.rom.MinimapData.NUM_AREAS) {
                throw RomWritePlanException("Invalid minimap area $area; expected 0-6")
            }
            val baseline = romParser.readMinimapTiles(area)
            var tiles = baseline
            for (edit in edits) {
                if (edit.x !in 0 until com.supermetroid.editor.rom.MinimapData.MAP_WIDTH ||
                    edit.y !in 0 until com.supermetroid.editor.rom.MinimapData.MAP_HEIGHT
                ) {
                    throw RomWritePlanException("Minimap edit ($area:${edit.x},${edit.y}) is outside the 64x32 map")
                }
                if (edit.tileWord !in 0..0xFFFF) {
                    throw RomWritePlanException(
                        "Minimap tile word ${edit.tileWord} at ($area:${edit.x},${edit.y}) is outside 0x0000-0xFFFF"
                    )
                }
                tiles = tiles.withTile(edit.x, edit.y, edit.tileWord)
            }
            writePlan.capture(
                owner = "minimap:area-$area",
                label = "Minimap area $area",
                kind = RomWriteKind.MINIMAP,
            ) { _ ->
                for ((offset, byte) in romParser.writeMinimapTiles(tiles)) {
                    romData[offset] = byte
                }
            }
            patched += edits.size
            onLog("Minimap area $area: patched ${edits.size} tiles")
        }
        for ((areaKey, edits) in project.mapStationEdits) {
            val area = areaKey.toIntOrNull()
                ?: throw RomWritePlanException("Invalid map-station area key '$areaKey'; expected 0-6")
            if (area !in 0 until com.supermetroid.editor.rom.MinimapData.NUM_AREAS) {
                throw RomWritePlanException("Invalid map-station area $area; expected 0-6")
            }
            var station = romParser.readMapStationData(area)
            for (edit in edits) {
                if (edit.x !in 0 until com.supermetroid.editor.rom.MinimapData.MAP_WIDTH ||
                    edit.y !in 0 until com.supermetroid.editor.rom.MinimapData.MAP_HEIGHT
                ) {
                    throw RomWritePlanException("Map-station edit ($area:${edit.x},${edit.y}) is outside the 64x32 map")
                }
                station = station.withValue(edit.x, edit.y, edit.revealed)
            }
            writePlan.capture(
                owner = "minimap:station-area-$area",
                label = "Map-station reveal area $area",
                kind = RomWriteKind.MINIMAP,
            ) { _ ->
                for ((offset, byte) in romParser.writeMapStationData(station)) {
                    romData[offset] = byte
                }
            }
            patched += edits.size
            onLog("Map-station area $area: patched ${edits.size} reveal cells")
        }
        return patched
    }

    private fun applyTextEdits(writePlan: RomWritePlan): Int {
        val romData = writePlan.romData
        var patched = 0
        val allText = if (project.textEdits.isNotEmpty()) TextData.readAllText(romParser.getRomData()) else emptyList()
        for ((id, newText) in project.textEdits) {
            val entry = allText.find { it.id == id } ?: continue
            if (!entry.writable || entry.pcOffset < 0) {
                onLog("[EXPORT] Skipped text entry '${entry.label}': ROM location is not writable")
                continue
            }
            val encoded = when (entry.category) {
                TextCategory.AREA_NAME -> TextData.encodeAreaName(newText, entry.rawBytes)
                TextCategory.ESCAPE_TEXT -> TextData.encodeEscapeText(newText, entry.rawBytes)
                TextCategory.UI_MESSAGE -> TextData.encodeUiMessage(newText, entry.rawBytes)
                TextCategory.ITEM_NAME -> TextData.encodeUiMessage(newText, entry.rawBytes)
                TextCategory.INTRO_STORY -> TextData.encodeGreenText(newText, entry.rawBytes)
            }
            writePlan.capture(
                owner = "text:$id",
                label = entry.label,
                kind = RomWriteKind.TEXT,
            ) { _ ->
                for (i in encoded.indices) {
                    val offset = entry.pcOffset + i
                    if (offset in romData.indices) romData[offset] = encoded[i]
                }
            }
            patched++
        }
        if (patched > 0) onLog("[EXPORT] Patched $patched text entries")
        return patched
    }

    /**
     * Embeds custom ASM hex bytes into free space in bank $A0 and updates
     * the species header pointer field to point at the new routine.
     */
    private fun applyCustomAsm(writePlan: RomWritePlan): Int {
        val romData = writePlan.romData
        var patched = 0
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

            val bankStart = romParser.snesToPc(0xA08000)
            val bankEnd = romParser.snesToPc(0xA0FFFF) + 1
            val freePtr = scanFreeSpaceEnd(romData, bankStart, bankEnd)

            if (freePtr + codeBytes.size > bankEnd) {
                onLog("[EXPORT] WARN: Not enough free space in bank \$A0 for custom ASM ($key)")
                continue
            }
            require((freePtr until freePtr + codeBytes.size).all { romData[it] == 0xFF.toByte() }) {
                "custom ASM allocation for $key is not free"
            }

            val newSnesPtr = 0x8000 + (freePtr - bankStart)
            val headerPc = romParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
            val owner = "asm:$key"
            writePlan.capture(owner, entry.label.ifEmpty { fieldName }, RomWriteKind.CUSTOM_ASM) { _ ->
                System.arraycopy(codeBytes, 0, romData, freePtr, codeBytes.size)
                writeU16(romData, headerPc + headerOffset, newSnesPtr)
            }
            writePlan.claimCurrentRange(
                owner = owner,
                label = "${entry.label.ifEmpty { fieldName }} allocation",
                offset = freePtr - writePlan.headerSize,
                size = codeBytes.size,
            )
            patched++
            val label = entry.label.ifEmpty { fieldName }
            onLog("[EXPORT] Custom ASM: $label → \$A0:${newSnesPtr.toString(16).uppercase()} (${codeBytes.size} bytes) for species \$${parts[0]}")
        }
        if (patched > 0) onLog("[EXPORT] Embedded $patched custom ASM routine(s)")
        return patched
    }

    /**
     * Combined per-frame hook: boss-defeated + hyper beam + infinite blue suit.
     * Writes a single routine at $DF:F040 (PC $2FF040) and hooks $82:896E.
     */
    private fun applyPerFrameHook(romData: ByteArray) {
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

            val headerSize = if (romData.size % 0x8000 == RomConstants.SMC_HEADER_SIZE) {
                RomConstants.SMC_HEADER_SIZE
            } else {
                0
            }
            val payloadPc = headerSize + 0x2FF040
            val hookPc = headerSize + 0x1096E
            val originalHook = listOf(0x22, 0xEF, 0x89, 0x82)
            for (i in originalHook.indices) {
                val actual = romData[hookPc + i].toInt() and 0xFF
                require(actual == originalHook[i]) {
                    "combined per-frame hook expected " +
                        originalHook.joinToString(" ") { it.toString(16).padStart(2, '0') } +
                        " at PC 0x1096E, found ${romData.hexAt(hookPc, 4)}"
                }
            }
            for (i in code.indices) {
                require((romData[payloadPc + i].toInt() and 0xFF) == 0xFF) {
                    "combined per-frame hook payload overlaps used ROM at PC 0x${(0x2FF040 + i).toString(16)}"
                }
            }

            // Write payload at PC $2FF040
            for ((i, b) in code.withIndex()) {
                val addr = payloadPc + i
                if (addr < romData.size) romData[addr] = b.toByte()
            }
            // Hook $82:896E (PC $1096E): JSL $DFF040
            val hook = listOf(0x22, 0x40, 0xF0, 0xDF)
            for ((i, b) in hook.withIndex()) {
                val addr = hookPc + i
                if (addr < romData.size) romData[addr] = b.toByte()
            }
            onLog("[EXPORT]   Per-frame hook: ${code.size} bytes at \$DF:F040, hook at \$82:896E")
        } else {
            onLog("[EXPORT] Per-frame hook: not needed (no boss flags, hyper beam, or blue suit)")
        }
    }

    /** Re-reads all modified data from the patched ROM and logs any integrity errors. */
    private fun verifyExportedRom(romData: ByteArray, roomsPatched: Set<String>): Int {
        onLog("\n=== Export Verification ===")
        var verifyErrors = 0
        val exportParser = RomParser(romData)
        for (roomKey in roomsPatched) {
            val roomId = roomKey.toIntOrNull(16) ?: continue
            val room = romParser.readRoomHeader(roomId) ?: continue
            val allStateOffsets = romParser.findAllStateDataOffsets(roomId)

            // Collect per-state data from the export copy
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
        return verifyErrors
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

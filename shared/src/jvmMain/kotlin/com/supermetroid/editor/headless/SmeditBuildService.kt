package com.supermetroid.editor.headless

import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.ScrollChange
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.data.TILE_EDIT_LAYER_2
import com.supermetroid.editor.data.TilesetGfxData
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.PaletteEffects
import com.supermetroid.editor.rom.RomFreeSpaceAllocator
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomNamePauseMapPatch
import com.supermetroid.editor.rom.SpritePalettes
import com.supermetroid.editor.rom.TileGraphics
import com.supermetroid.editor.rom.readU16
import com.supermetroid.editor.rom.readU24
import com.supermetroid.editor.rom.readU8
import java.util.Base64
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class SmeditBuildService(
    catalogPatches: List<SmPatch> = SmeditPatchCatalog.defaultPatches(),
) {
    private val catalog = catalogPatches.map { PatchEntry(it.deepCopy(), "catalog") }
    private val itemDefinitions = buildItemDefinitions(catalogPatches)
    private val itemPlmIds = itemDefinitions.flatMap { it.plmIds() }.toSet()

    fun build(
        inputRom: ByteArray,
        request: SmeditBuildRequest,
        project: SmEditProject? = null,
    ): SmeditBuildResult {
        val outputRom = inputRom.copyOf()
        val context = ApplyContext(
            outputRom = outputRom,
            parser = RomParser(outputRom),
            warnings = mutableListOf(),
            strictConfigValidation = request.strictConfigValidation,
        )
        val applied = applyRequest(request, project, context)
        val changedBytes = outputRom.changedByteCountFrom(inputRom)

        return SmeditBuildResult(
            romBytes = outputRom,
            ipsPatchBytes = IpsPatch.encodeDiff(inputRom.headerlessRomBytes(), outputRom.headerlessRomBytes()),
            report = SmeditBuildReport(
                mode = "rom",
                inputRomBytes = inputRom.size,
                outputRomBytes = outputRom.size,
                changedBytes = changedBytes,
                patchBytes = context.patchWrites.totalByteCount(),
                applied = applied,
                warnings = context.warnings.distinct(),
            ),
        )
    }

    fun buildPatch(
        request: SmeditBuildRequest,
        project: SmEditProject? = null,
    ): SmeditPatchBuildResult {
        val context = ApplyContext(
            outputRom = null,
            parser = null,
            warnings = mutableListOf(),
            strictConfigValidation = request.strictConfigValidation,
        )
        val applied = applyRequest(request, project, context)
        val patchBytes = context.patchWrites.totalByteCount()

        return SmeditPatchBuildResult(
            ipsPatchBytes = IpsPatch.encodeWrites(context.patchWrites),
            report = SmeditBuildReport(
                mode = "patch",
                inputRomBytes = 0,
                outputRomBytes = 0,
                changedBytes = patchBytes,
                patchBytes = patchBytes,
                applied = applied,
                warnings = context.warnings.distinct(),
            ),
        )
    }

    private fun applyRequest(
        request: SmeditBuildRequest,
        project: SmEditProject?,
        context: ApplyContext,
    ): List<SmeditAppliedPatchReport> {
        require(request.schemaVersion == 1) {
            "Unsupported build schemaVersion ${request.schemaVersion}; expected 1"
        }

        val applied = mutableListOf<SmeditAppliedPatchReport>()
        val patchEntries = resolvedPatchEntries(project, request, context.warnings)
        val requestedKeys = request.patches.keys
        val roomNamePatchEntry = patchEntries.firstOrNull {
            it.patch.enabled && it.patch.configType == ROOM_NAME_PAUSE_MAP_CONFIG_TYPE
        }

        for (entry in patchEntries) {
            val patch = entry.patch
            if (!patch.enabled) continue

            val beforePatchBytes = context.patchWrites.totalByteCount()
            val beforeWrites = context.appliedWriteCount
            validateConfigPatch(patch, context)
            val handledConfig = if (patch.configType.isDeferredGeneratedConfigType()) {
                patch.configType
            } else {
                applyConfigPatch(patch, context)
            }
            val writeCount = applyRawPatchWrites(patch, context)
            val wroteConfig = handledConfig != null

            if (patch.configType != null && !wroteConfig && writeCount == 0) {
                val message = "Config patch '${patch.configType}' is not supported by headless build v1."
                if (entry.source == "request" || patch.id in requestedKeys || patch.configType in requestedKeys) {
                    throw IllegalArgumentException(message)
                }
                context.warnings.add(message)
            }

            val bytes = max(0, context.patchWrites.totalByteCount() - beforePatchBytes)
            val writes = context.appliedWriteCount - beforeWrites
            if (bytes > 0 || writes > 0 || (wroteConfig && !patch.configType.isDeferredGeneratedConfigType())) {
                applied.add(
                    SmeditAppliedPatchReport(
                        identifier = SmeditPatchCatalog.publicPatchId(patch),
                        name = patch.name,
                        source = entry.source,
                        configType = patch.configType,
                        writes = writes,
                        bytes = bytes,
                    )
                )
            }
        }

        for (write in request.rawWrites) {
            val offset = resolveRawWriteOffset(write, context)
            val bytes = write.bytes.map { it.coerceIn(0, 255) }
            if (writeBytes(context, offset, bytes, write.label.ifBlank { "raw write" })) {
                applied.add(
                    SmeditAppliedPatchReport(
                        identifier = write.label.ifBlank { "raw_write_${offset.toString(16)}" },
                        name = write.label.ifBlank { "Raw Write" },
                        source = "request",
                        writes = 1,
                        bytes = bytes.size,
                    )
                )
            }
        }

        if (project != null) {
            applied.addAll(applyProjectCustomGraphics(project.customGfx, context))
        }
        request.colorize?.let { colorize ->
            applyColorizeRequest(colorize, context)?.let(applied::add)
        }
        if (project != null) {
            applied.addAll(applyProjectRoomEdits(project, context))
        }
        createItemPlacementProject(request.items, patchEntries, context)?.let { itemProject ->
            applied.addAll(
                applyProjectRoomEdits(
                    project = itemProject,
                    context = context,
                    identifier = "request_item_placements",
                    name = "Request Item Placements",
                    source = "request",
                )
            )
        }
        roomNamePatchEntry?.let {
            applied.addAll(applyRoomNamePauseMapPatch(it, project, context))
        }
        applyCombinedPerFrameHook(patchEntries, context)?.let(applied::add)

        if (project != null) {
            addUnsupportedProjectWarnings(
                project = project,
                context = context,
                roomNameOverridesHandled = roomNamePatchEntry != null && context.outputRom != null,
            )
        }

        return applied
    }

    private fun resolvedPatchEntries(
        project: SmEditProject?,
        request: SmeditBuildRequest,
        warnings: MutableList<String>,
    ): List<PatchEntry> {
        val entries = linkedMapOf<String, PatchEntry>()
        for (entry in catalog) {
            entries[entry.patch.id] = entry.copy(patch = entry.patch.deepCopy())
        }

        if (project != null) {
            for (patch in project.patches) {
                entries[patch.id] = PatchEntry(patch.deepCopy(), "project")
            }
        }

        for ((key, patchRequest) in request.patches) {
            val entry = findEntry(entries, key)
                ?: createRequestPatch(key, patchRequest).also { entries[it.patch.id] = it }
            val patch = entry.patch
            patch.enabled = patchRequest.enabled
            if (patchRequest.configType != null) patch.configType = patchRequest.configType
            if (patchRequest.configValue != null) patch.configValue = patchRequest.configValue
            val config = patchRequest.configData + patchRequest.config
            if (config.isNotEmpty()) {
                val data = patch.configData ?: mutableMapOf()
                data.putAll(config)
                patch.configData = data
            }

            if (patch.writes.isEmpty() &&
                patch.configType == null &&
                patchRequest.configType == null &&
                SmeditPatchCatalog.resolvePatchKey(key) !in SmeditPatchCatalog.supportedConfigTypes()
            ) {
                val message = "Patch '$key' was created from request but has no writes or configType."
                if (request.strictConfigValidation) {
                    throw IllegalArgumentException(message)
                }
                warnings.add(message)
            }
        }

        return entries.values.toList()
    }

    private fun addUnsupportedProjectWarnings(
        project: SmEditProject,
        context: ApplyContext,
        roomNameOverridesHandled: Boolean,
    ) {
        val ignored = mutableListOf<String>()
        val unsupportedRoomEdits = project.rooms.values.count { it.hasUnsupportedHeadlessRoomEdits() }
        if (unsupportedRoomEdits > 0) ignored.add("unsupported room edits: $unsupportedRoomEdits")
        if (project.tileDefaults.isNotEmpty()) ignored.add("tile defaults: ${project.tileDefaults.size}")
        ignored.addAll(project.customGfx.unsupportedDescriptions(hasRom = context.outputRom != null))
        if (project.patterns.isNotEmpty()) ignored.add("patterns: ${project.patterns.size}")
        if (project.minimapEdits.isNotEmpty()) ignored.add("minimap edits: ${project.minimapEdits.size}")
        if (project.textEdits.isNotEmpty()) ignored.add("text edits: ${project.textEdits.size}")
        if (!roomNameOverridesHandled && project.roomNameOverrides.isNotEmpty()) {
            ignored.add("room name overrides: ${project.roomNameOverrides.size}")
        }
        if (project.customAsm.isNotEmpty()) ignored.add("custom ASM entries: ${project.customAsm.size}")
        if (project.musicEdits.isNotEmpty()) ignored.add("music edits: ${project.musicEdits.size}")

        if (ignored.isNotEmpty()) {
            context.warnings.add("Headless build v1 ignored unsupported project data: ${ignored.joinToString()}.")
        }
    }

    private fun findEntry(entries: Map<String, PatchEntry>, key: String): PatchEntry? =
        SmeditPatchCatalog.resolvePatchKey(key).let { resolvedKey ->
            entries[key]
                ?: entries[resolvedKey]
                ?: entries.values.firstOrNull { it.patch.configType == key || it.patch.configType == resolvedKey }
        }

    private fun buildItemDefinitions(patches: List<SmPatch>): List<HeadlessItemDefinition> =
        buildList {
            for (item in RomParser.ITEM_DEFS) {
                add(
                    HeadlessItemDefinition(
                        id = itemApiId(item.name),
                        name = item.name,
                        shortLabel = item.shortLabel,
                        visiblePlmId = item.visibleId,
                        chozoPlmId = item.chozoId,
                        hiddenPlmId = item.hiddenId,
                    )
                )
            }
            for (patch in patches) {
                for (item in patch.customItems) {
                    add(
                        HeadlessItemDefinition(
                            id = item.id,
                            name = item.name,
                            shortLabel = item.shortLabel,
                            visiblePlmId = item.visiblePlmId,
                            chozoPlmId = item.chozoPlmId,
                            hiddenPlmId = item.hiddenPlmId,
                            sourcePatchId = SmeditPatchCatalog.publicPatchId(patch),
                        )
                    )
                }
            }
        }

    private fun itemApiId(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

    private fun createItemPlacementProject(
        items: List<SmeditItemPlacementRequest>,
        patchEntries: List<PatchEntry>,
        context: ApplyContext,
    ): SmEditProject? {
        if (items.isEmpty()) return null
        val parser = context.parser
        if (context.outputRom == null || parser == null) {
            context.warnings.add(
                "Request item placements require --rom because PLM data is compressed and pointer-based."
            )
            return null
        }

        val roomInfos = RoomRepository().getAllRooms()
        val roomsById = roomInfos.associateBy { it.getRoomIdAsInt() }
        val usedParams = collectUsedItemParams(parser, roomInfos.map { it.getRoomIdAsInt() })
        val itemProject = SmEditProject(romPath = "")

        for (placement in items) {
            val item = resolveItemDefinition(placement.item)
            requireCustomItemPatchEnabled(item, patchEntries, context)
            val roomId = resolvePlacementRoomId(placement, roomsById)
            val plmId = item.plmIdFor(placement.kind)
                ?: throw IllegalArgumentException(
                    "Item '${placement.item}' does not support placement kind '${placement.kind}'."
                )
            require(placement.x in 0..0xFF) {
                "Item '${placement.item}' x coordinate must be from 0 to 255."
            }
            require(placement.y in 0..0xFF) {
                "Item '${placement.item}' y coordinate must be from 0 to 255."
            }
            val param = placement.param?.also {
                require(it in 0..0xFFFF) {
                    "Item '${placement.item}' param must be from 0 to 65535."
                }
                if (it > 0) usedParams.add(it)
            } ?: nextItemParam(usedParams)

            val room = itemProject.getOrCreateRoom(roomId)
            room.plmChanges.removeAll {
                it.action == "add" && it.x == placement.x && it.y == placement.y && it.plmId in itemPlmIds
            }
            room.plmChanges.add(
                PlmChange(
                    action = "add",
                    plmId = plmId,
                    x = placement.x,
                    y = placement.y,
                    param = param,
                )
            )
        }

        return itemProject
    }

    private fun collectUsedItemParams(parser: RomParser, roomIds: List<Int>): MutableSet<Int> {
        val used = mutableSetOf<Int>()
        for (roomId in roomIds) {
            for (plm in parser.getAllPlmEntriesForRoom(roomId)) {
                if (plm.id in itemPlmIds && plm.param > 0) {
                    used.add(plm.param)
                }
            }
        }
        return used
    }

    private fun resolveItemDefinition(item: String): HeadlessItemDefinition {
        val key = lookupKey(item)
        return itemDefinitions.firstOrNull { definition ->
            key == lookupKey(definition.id) ||
                key == lookupKey(definition.name) ||
                key == lookupKey(definition.shortLabel)
        } ?: throw IllegalArgumentException("Unknown item '$item'.")
    }

    private fun requireCustomItemPatchEnabled(
        item: HeadlessItemDefinition,
        patchEntries: List<PatchEntry>,
        context: ApplyContext,
    ) {
        val sourcePatchId = item.sourcePatchId ?: return
        val enabled = patchEntries.any {
            it.patch.enabled && SmeditPatchCatalog.publicPatchId(it.patch) == sourcePatchId
        }
        if (enabled) return

        val message = "Item '${item.id}' requires patch '$sourcePatchId' to be enabled."
        if (context.strictConfigValidation) {
            throw IllegalArgumentException(message)
        }
        context.warnings.add(message)
    }

    private fun resolvePlacementRoomId(
        placement: SmeditItemPlacementRequest,
        roomsById: Map<Int, RoomInfo>,
    ): Int {
        placement.roomId?.let { roomId ->
            require(roomsById.containsKey(roomId)) {
                "Unknown roomId 0x${roomId.toString(16).uppercase()} for item '${placement.item}'."
            }
            return roomId
        }

        val roomRef = placement.room?.trim().orEmpty()
        require(roomRef.isNotEmpty()) {
            "Item '${placement.item}' requires roomId or room."
        }

        val roomIdCandidates = parseRoomReferences(roomRef)
        roomIdCandidates.firstOrNull(roomsById::containsKey)?.let { roomId ->
            return roomId
        }
        if (roomIdCandidates.isNotEmpty()) {
            throw IllegalArgumentException(
                "Unknown room '$roomRef' for item '${placement.item}'."
            )
        }

        val key = lookupKey(roomRef)
        val room = roomsById.values.firstOrNull {
            key == lookupKey(it.handle) || key == lookupKey(it.name)
        } ?: throw IllegalArgumentException("Unknown room '$roomRef' for item '${placement.item}'.")
        return room.getRoomIdAsInt()
    }

    private fun parseRoomReferences(roomRef: String): List<Int> {
        val trimmed = roomRef.trim()
        val hex = trimmed.removePrefix("0x").removePrefix("0X")
        if (trimmed.startsWith("0x", ignoreCase = true)) {
            return listOfNotNull(hex.toIntOrNull(16))
        }
        return buildList {
            hex.takeIf { it.all { char -> char.isDigitOrHexLetter() } }
                ?.toIntOrNull(16)
                ?.let(::add)
            trimmed.toIntOrNull()?.let(::add)
        }.distinct()
    }

    private fun nextItemParam(usedParams: MutableSet<Int>): Int {
        var candidate = 0x51
        while (candidate in usedParams && candidate <= 0xFFFF) candidate++
        require(candidate <= 0xFFFF) {
            "No free item collection parameters remain."
        }
        usedParams.add(candidate)
        return candidate
    }

    private fun Char.isDigitOrHexLetter(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun lookupKey(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private fun applyProjectCustomGraphics(
        gfx: TilesetGfxData,
        context: ApplyContext,
    ): List<SmeditAppliedPatchReport> {
        val applied = mutableListOf<SmeditAppliedPatchReport>()

        val beforeTilesetRecords = context.patchWrites.size
        val beforeTilesetBytes = context.patchWrites.totalByteCount()
        applyTilesetPaletteOverrides(gfx, context)
        val tilesetRecords = context.patchWrites.size - beforeTilesetRecords
        val tilesetBytes = context.patchWrites.totalByteCount() - beforeTilesetBytes
        if (tilesetRecords > 0) {
            applied.add(
                SmeditAppliedPatchReport(
                    identifier = "project_tileset_palettes",
                    name = "Project Tileset Palettes",
                    source = "project",
                    writes = tilesetRecords,
                    bytes = tilesetBytes,
                )
            )
        }

        val beforeSpriteRecords = context.patchWrites.size
        val beforeSpriteBytes = context.patchWrites.totalByteCount()
        applySpritePaletteOverrides(gfx, context)
        val spriteRecords = context.patchWrites.size - beforeSpriteRecords
        val spriteBytes = context.patchWrites.totalByteCount() - beforeSpriteBytes
        if (spriteRecords > 0) {
            applied.add(
                SmeditAppliedPatchReport(
                    identifier = "project_sprite_palettes",
                    name = "Project Sprite Palettes",
                    source = "project",
                    writes = spriteRecords,
                    bytes = spriteBytes,
                )
            )
        }

        return applied
    }

    private fun applyTilesetPaletteOverrides(
        gfx: TilesetGfxData,
        context: ApplyContext,
    ) {
        if (gfx.palettes.isEmpty()) return

        val writer = createTilesetPaletteWriter(
            context = context,
            missingRomMessage = "Tileset palette overrides require --rom because compressed palette pointers and free space are ROM-dependent.",
        ) ?: return

        for ((tilesetKey, paletteBase64) in gfx.palettes) {
            val tilesetId = tilesetKey.toIntOrNull()
            if (tilesetId == null || tilesetId !in 0 until TileGraphics.NUM_TILESETS) {
                context.warnings.add("Tileset palette '$tilesetKey' is not a valid tileset id.")
                continue
            }

            val rawPalette = decodeBase64(paletteBase64, "tileset $tilesetId palette", context.warnings) ?: continue
            if (rawPalette.size != TILESET_PALETTE_BYTES) {
                context.warnings.add(
                    "Tileset $tilesetId palette has ${rawPalette.size} bytes; expected $TILESET_PALETTE_BYTES."
                )
                continue
            }

            val target = resolveTilesetPaletteTarget(writer, tilesetId, context) ?: continue
            writeTilesetPalette(writer, target, rawPalette, context, clearOriginalOnRelocate = true)
        }
    }

    private fun applyColorizeRequest(
        colorize: SmeditColorizeRequest,
        context: ApplyContext,
    ): SmeditAppliedPatchReport? {
        val effectId = colorize.effect.trim()
        if (effectId.isBlank()) {
            reportValidationIssue(context, "colorize.effect must not be blank.")
            return null
        }
        val effect = PaletteEffects.findEffect(effectId)
        if (effect == null) {
            reportValidationIssue(
                context,
                "Unknown colorize effect '$effectId'. Valid effects: ${PaletteEffects.EFFECTS.joinToString { it.id }}.",
            )
            return null
        }
        if (!colorize.includeTilesets && !colorize.includeSprites) {
            reportValidationIssue(context, "colorize must include tilesets, sprites, or both.")
            return null
        }
        if (context.outputRom == null || context.parser == null) {
            reportValidationIssue(context, "colorize requires --rom because palette effects must read base ROM palette data.")
            return null
        }

        val random = colorize.seed?.let(::Random)
        val beforeRecords = context.patchWrites.size
        val beforeBytes = context.patchWrites.totalByteCount()
        if (colorize.includeTilesets) {
            applyTilesetPaletteColorize(effect, colorize, context, random)
        }
        if (colorize.includeSprites) {
            applySpritePaletteColorize(effect, colorize, context, random)
        }

        val writes = context.patchWrites.size - beforeRecords
        if (writes == 0) return null

        return SmeditAppliedPatchReport(
            identifier = "colorize",
            name = "Colorize Palettes (${effect.name}${colorize.seed?.let { ", seed $it" } ?: ""})",
            source = "request",
            configType = "colorize",
            writes = writes,
            bytes = context.patchWrites.totalByteCount() - beforeBytes,
        )
    }

    private fun applyProjectRoomEdits(
        project: SmEditProject,
        context: ApplyContext,
        identifier: String = "project_room_edits",
        name: String = "Project Room Edits",
        source: String = "project",
    ): List<SmeditAppliedPatchReport> {
        val rom = context.outputRom
        val parser = context.parser
        if (project.rooms.values.none {
                it.operations.isNotEmpty() || it.plmChanges.isNotEmpty() || it.scrollChanges.isNotEmpty()
            }
        ) {
            return emptyList()
        }
        if (rom == null || parser == null) {
            context.warnings.add(
                "Project room edits require --rom because level and PLM data are compressed and pointer-based."
            )
            return emptyList()
        }

        val beforeRecords = context.patchWrites.size
        val beforeBytes = context.patchWrites.totalByteCount()
        val roomDataAllocator = RomFreeSpaceAllocator(
            romData = rom,
            snesToPc = parser::snesToPc,
            pcToSnes = parser::pcToSnes,
            guardBytes = 1,
        )
        // Include custom item PLM IDs from this project's enabled patches so that dedup
        // also covers project-defined items (not just catalog items).
        val projectCustomItemPlmIds = project.patches
            .filter { it.enabled }
            .flatMap { it.customItems }
            .flatMap { listOfNotNull(it.visiblePlmId, it.chozoPlmId, it.hiddenPlmId) }
            .toSet()
        val effectiveItemPlmIds = itemPlmIds + projectCustomItemPlmIds
        var patchedRooms = 0

        for ((roomKey, roomEdits) in project.rooms) {
            val roomId = roomKey.toIntOrNull(16) ?: continue
            val room = parser.readRoomHeader(roomId) ?: continue
            var patched = false
            if (roomEdits.operations.any { it.edits.isNotEmpty() } && room.levelDataPtr != 0) {
                patched = applyRoomTileEdits(roomKey, roomId, room, roomEdits, roomDataAllocator, context) || patched
            }
            if (roomEdits.plmChanges.isNotEmpty()) {
                patched = applyRoomPlmChanges(roomKey, roomId, roomEdits, roomDataAllocator, effectiveItemPlmIds, context) || patched
            }
            if (roomEdits.effectiveScrollChanges().isNotEmpty()) {
                patched = applyRoomScrollChanges(roomKey, roomId, room, roomEdits, roomDataAllocator, context) || patched
            }
            if (patched) patchedRooms++
        }

        val writes = context.patchWrites.size - beforeRecords
        if (writes == 0) return emptyList()

        return listOf(
            SmeditAppliedPatchReport(
                identifier = identifier,
                name = name,
                source = source,
                writes = writes,
                bytes = context.patchWrites.totalByteCount() - beforeBytes,
            )
        ).also {
            if (patchedRooms == 0) {
                context.warnings.add("Project room edits were present but no room data could be written.")
            }
        }
    }

    private fun applyRoomTileEdits(
        roomKey: String,
        roomId: Int,
        room: com.supermetroid.editor.data.Room,
        roomEdits: RoomEdits,
        levelAllocator: RomFreeSpaceAllocator,
        context: ApplyContext,
    ): Boolean {
        val rom = context.outputRom ?: return false
        val parser = context.parser ?: return false
        val allStateOffsets = parser.findAllStateDataOffsets(roomId)
        if (allStateOffsets.isEmpty()) {
            context.warnings.add("Room 0x$roomKey has tile edits but no editable room states.")
            return false
        }

        val width = room.width * 16
        val height = room.height * 16
        val totalBlocks = width * height
        val ptrToStates = linkedMapOf<Int, MutableList<Int>>()
        for (stateOffset in allStateOffsets) {
            val levelPtr = readU24(rom, stateOffset)
            if (levelPtr != 0) ptrToStates.getOrPut(levelPtr) { mutableListOf() }.add(stateOffset)
        }

        var wrote = false
        val tileEdits = roomEdits.operations.flatMap { it.edits }
        for ((levelPtr, statesForPtr) in ptrToStates) {
            val decompressed = runCatching { parser.decompressLZ2WithSize(levelPtr) }.getOrNull()
            if (decompressed == null) {
                context.warnings.add("Room 0x$roomKey level data at 0x${levelPtr.toString(16)} could not be decompressed.")
                continue
            }
            val (originalData, originalSize) = decompressed
            val editedData = originalData.copyOf()
            if (editedData.size < 2) continue
            val layer1Size = (editedData[0].toInt() and 0xFF) or ((editedData[1].toInt() and 0xFF) shl 8)
            val layer2Start = 2 + layer1Size + totalBlocks
            val hasEmbeddedLayer2 = layer2Start + totalBlocks * 2 <= editedData.size && room.bgScrolling == 0
            var changedPointerData = false

            for (edit in tileEdits) {
                if (edit.blockX !in 0 until width || edit.blockY !in 0 until height) continue
                val index = edit.blockY * width + edit.blockX
                if (edit.layer == TILE_EDIT_LAYER_2) {
                    if (hasEmbeddedLayer2) {
                        val offset = layer2Start + index * 2
                        val word = edit.newBlockWord and 0x0FFF
                        editedData[offset] = (word and 0xFF).toByte()
                        editedData[offset + 1] = ((word shr 8) and 0xFF).toByte()
                        changedPointerData = true
                    }
                    continue
                }

                val wordOffset = 2 + index * 2
                if (wordOffset + 1 < editedData.size) {
                    editedData[wordOffset] = (edit.newBlockWord and 0xFF).toByte()
                    editedData[wordOffset + 1] = ((edit.newBlockWord shr 8) and 0xFF).toByte()
                    changedPointerData = true
                }
                val btsOffset = 2 + layer1Size + index
                if (btsOffset < editedData.size) {
                    editedData[btsOffset] = edit.newBts.toByte()
                }
            }
            if (!changedPointerData) continue

            val compressed = LZ5Compressor.compress(editedData)
            val roundTripped = runCatching { LZ5Compressor.decompress(compressed) }.getOrNull()
            if (roundTripped == null || !roundTripped.contentEquals(editedData)) {
                context.warnings.add("Room 0x$roomKey level data failed LZ5 round-trip validation.")
                continue
            }

            val levelPc = context.snesToPc(levelPtr)
            if (compressed.size <= originalSize) {
                val bytes = (compressed + ByteArray(originalSize - compressed.size) { 0xFF.toByte() }).toIntList()
                wrote = writeTrackedBytes(context, levelPc, bytes, "room 0x$roomKey level data") || wrote
            } else {
                val allocation = levelAllocator.reserve(
                    size = compressed.size,
                    banks = levelDataRelocationBanks(levelPtr),
                    label = "room 0x$roomKey level data",
                )
                if (allocation == null) {
                    context.warnings.add(
                        "Room 0x$roomKey level data compressed to ${compressed.size} bytes, exceeds original " +
                            "$originalSize bytes, and no free space was found."
                    )
                    continue
                }
                wrote = writeTrackedBytes(
                    context,
                    context.fileToPcOffset(allocation.pcOffset),
                    compressed.toIntList(),
                    "room 0x$roomKey relocated level data",
                ) || wrote
                for (stateOffset in statesForPtr) {
                    wrote = writeTrackedBytes(
                        context,
                        context.fileToPcOffset(stateOffset),
                        u24Bytes(allocation.snesAddress),
                        "room 0x$roomKey level data pointer",
                    ) || wrote
                }
                wrote = writeTrackedBytes(
                    context,
                    levelPc,
                    List(originalSize) { 0xFF },
                    "room 0x$roomKey old level data free fill",
                ) || wrote
            }
        }
        return wrote
    }

    private fun applyRoomPlmChanges(
        roomKey: String,
        roomId: Int,
        roomEdits: RoomEdits,
        plmAllocator: RomFreeSpaceAllocator,
        effectiveItemPlmIds: Set<Int>,
        context: ApplyContext,
    ): Boolean {
        val rom = context.outputRom ?: return false
        val parser = context.parser ?: return false
        val allStateOffsets = parser.findAllStateDataOffsets(roomId)
        if (allStateOffsets.isEmpty()) {
            context.warnings.add("Room 0x$roomKey has PLM edits but no editable room states.")
            return false
        }

        val distinctPlmPtrs = linkedSetOf<Int>()
        for (stateOffset in allStateOffsets) {
            val plmPtr = readU16(rom, stateOffset + 20)
            if (plmPtr != 0 && plmPtr != 0xFFFF) distinctPlmPtrs.add(plmPtr)
        }
        if (distinctPlmPtrs.isEmpty()) {
            context.warnings.add("Room 0x$roomKey has PLM edits but no PLM set pointer.")
            return false
        }

        var wrote = false
        for (plmSetPtr in distinctPlmPtrs) {
            val originalPlms = parser.parsePlmSet(plmSetPtr)
            val modifiedPlms = originalPlms.toMutableList()
            for (change in roomEdits.plmChanges) {
                when (change.action) {
                    "add" -> modifiedPlms.add(change.toPlmEntry())
                    "remove" -> modifiedPlms.removeAll {
                        it.id == change.plmId && it.x == change.x && it.y == change.y
                    }
                }
            }
            val deduped = dedupeItemPlmsByPosition(modifiedPlms, effectiveItemPlmIds)
            val originalSize = originalPlms.size * 6 + 2
            val bytes = RomParser.serializePlmSet(deduped)
            val plmPc = context.snesToPc(RomConstants.BANK_ROOM_DATA or plmSetPtr)

            if (bytes.size <= originalSize) {
                val padded = bytes + List(originalSize - bytes.size) { 0 }
                wrote = writeTrackedBytes(context, plmPc, padded, "room 0x$roomKey PLM set") || wrote
            } else {
                val allocation = plmAllocator.reserve(
                    size = bytes.size,
                    banks = listOf(0x8F),
                    label = "room 0x$roomKey PLM set",
                )
                if (allocation == null) {
                    context.warnings.add(
                        "Room 0x$roomKey PLM set expanded to ${bytes.size} bytes and no bank 8F free space was found."
                    )
                    continue
                }
                wrote = writeTrackedBytes(
                    context,
                    context.fileToPcOffset(allocation.pcOffset),
                    bytes,
                    "room 0x$roomKey relocated PLM set",
                ) || wrote
                val newPtr = allocation.snesAddress and 0xFFFF
                for (stateOffset in allStateOffsets) {
                    val existingPtr = readU16(rom, stateOffset + 20)
                    if (existingPtr != plmSetPtr) continue
                    wrote = writeTrackedBytes(
                        context,
                        context.fileToPcOffset(stateOffset + 20),
                        u16Bytes(newPtr),
                        "room 0x$roomKey PLM set pointer",
                    ) || wrote
                }
            }
        }
        return wrote
    }

    private fun applyRoomScrollChanges(
        roomKey: String,
        roomId: Int,
        room: com.supermetroid.editor.data.Room,
        roomEdits: RoomEdits,
        scrollAllocator: RomFreeSpaceAllocator,
        context: ApplyContext,
    ): Boolean {
        val parser = context.parser ?: return false
        val allStateOffsets = parser.findAllStateDataOffsets(roomId)
        if (allStateOffsets.isEmpty()) {
            context.warnings.add("Room 0x$roomKey has scroll edits but no editable room states.")
            return false
        }
        if (room.width <= 0 || room.height <= 0) return false

        val scrollChanges = roomEdits.effectiveScrollChanges()
        if (scrollChanges.isEmpty()) return false

        val distinctScrollPtrs = linkedSetOf<Int>()
        for (stateOffset in allStateOffsets) {
            distinctScrollPtrs.add(readU16(context.outputRom ?: return false, stateOffset + 14))
        }
        if (distinctScrollPtrs.isEmpty()) {
            context.warnings.add("Room 0x$roomKey has scroll edits but no scroll data pointer.")
            return false
        }

        var wrote = false
        for (scrollPtr in distinctScrollPtrs) {
            val originalScrolls = parser.parseScrollData(scrollPtr, room.width, room.height)
            val modifiedScrolls = originalScrolls.copyOf()
            for (change in scrollChanges) {
                val index = change.screenY * room.width + change.screenX
                if (index in modifiedScrolls.indices) modifiedScrolls[index] = change.newValue
            }
            if (modifiedScrolls.contentEquals(originalScrolls)) continue

            val bytes = modifiedScrolls.map { it and 0xFF }
            if (scrollPtr > 1) {
                val scrollPc = context.snesToPc(RomConstants.BANK_ROOM_DATA or scrollPtr)
                wrote = writeTrackedBytes(context, scrollPc, bytes, "room 0x$roomKey scroll data") || wrote
            } else {
                val allocation = scrollAllocator.reserve(
                    size = bytes.size,
                    banks = listOf(0x8F),
                    label = "room 0x$roomKey scroll data",
                )
                if (allocation == null) {
                    context.warnings.add(
                        "Room 0x$roomKey scroll data expanded from a special pointer to ${bytes.size} bytes " +
                            "and no bank 8F free space was found."
                    )
                    continue
                }
                wrote = writeTrackedBytes(
                    context,
                    context.fileToPcOffset(allocation.pcOffset),
                    bytes,
                    "room 0x$roomKey relocated scroll data",
                ) || wrote
                val newPtr = allocation.snesAddress and 0xFFFF
                for (stateOffset in allStateOffsets) {
                    val existingPtr = readU16(context.outputRom ?: return false, stateOffset + 14)
                    if (existingPtr != scrollPtr) continue
                    wrote = writeTrackedBytes(
                        context,
                        context.fileToPcOffset(stateOffset + 14),
                        u16Bytes(newPtr),
                        "room 0x$roomKey scroll data pointer",
                    ) || wrote
                }
            }
        }
        return wrote
    }

    private fun writeTrackedBytes(
        context: ApplyContext,
        offset: Int,
        bytes: List<Int>,
        label: String,
    ): Boolean {
        val wrote = writeBytes(context, offset, bytes, label)
        if (wrote) context.appliedWriteCount++
        return wrote
    }

    private fun PlmChange.toPlmEntry(): RomParser.PlmEntry =
        RomParser.PlmEntry(plmId, x, y, param)

    private fun dedupeItemPlmsByPosition(
        plms: List<RomParser.PlmEntry>,
        itemPlmIds: Set<Int>,
    ): List<RomParser.PlmEntry> {
        val seenItemPositions = mutableSetOf<Long>()
        val deduped = mutableListOf<RomParser.PlmEntry>()
        for (plm in plms.asReversed()) {
            val key = (plm.x.toLong() shl 16) or plm.y.toLong()
            if (plm.id in itemPlmIds) {
                if (key in seenItemPositions) continue
                seenItemPositions.add(key)
            }
            deduped.add(plm)
        }
        deduped.reverse()
        return deduped
    }

    private fun levelDataRelocationBanks(originalSnesAddress: Int): List<Int> {
        val originalBank = (originalSnesAddress shr 16) and 0xFF
        return (listOf(originalBank) + (0xCE downTo 0xC0)).distinct()
    }

    private fun applyTilesetPaletteColorize(
        effect: PaletteEffects.EffectDef,
        colorize: SmeditColorizeRequest,
        context: ApplyContext,
        random: Random?,
    ) {
        val writer = createTilesetPaletteWriter(
            context = context,
            missingRomMessage = "colorize tilesets requires --rom because tileset palettes are compressed and pointer-based.",
        ) ?: return
        val tilesets = resolveColorizeTilesets(colorize, context) ?: return
        val snapshots = tilesets.mapNotNull { tilesetId ->
            val target = resolveTilesetPaletteTarget(writer, tilesetId, context) ?: return@mapNotNull null
            val decompressed = runCatching { writer.parser.decompressLZ2WithSize(target.paletteSnes).first }.getOrNull()
            if (decompressed == null || decompressed.size < TILESET_PALETTE_BYTES) {
                context.warnings.add("Tileset $tilesetId palette could not be decompressed for colorize.")
                return@mapNotNull null
            }
            TilesetPaletteSnapshot(
                target = target,
                rawPalette = decompressed.copyOfRange(0, TILESET_PALETTE_BYTES),
            )
        }

        for (snapshot in snapshots) {
            val colors = SpritePalettes.bytesToColors(snapshot.rawPalette)
            PaletteEffects.applyEffect(effect, colors, random)
            writeTilesetPalette(
                writer = writer,
                target = snapshot.target,
                rawPalette = SpritePalettes.colorsToBytes(colors),
                context = context,
                clearOriginalOnRelocate = false,
            )
        }
    }

    private fun applySpritePaletteColorize(
        effect: PaletteEffects.EffectDef,
        colorize: SmeditColorizeRequest,
        context: ApplyContext,
        random: Random?,
    ) {
        val rom = context.outputRom ?: return
        val regions = resolveColorizeSpriteRegions(colorize, context) ?: return
        for (region in regions) {
            val fileOffset = context.pcToFileOffset(region.offset)
            if (fileOffset < 0 || fileOffset + region.byteSize > rom.size) {
                context.warnings.add("Sprite palette '${region.id}' is out of ROM range.")
                continue
            }
            val colors = readSpritePaletteColors(rom, fileOffset, region)
            if (colors == null) {
                context.warnings.add("Sprite palette '${region.id}' could not be read for colorize.")
                continue
            }
            PaletteEffects.applyEffect(effect, colors, random)
            writeBytes(context, region.offset, SpritePalettes.colorsToBytes(colors).toIntList(), "colorize ${region.name}")
        }
    }

    private fun resolveColorizeTilesets(
        colorize: SmeditColorizeRequest,
        context: ApplyContext,
    ): List<Int>? {
        if (colorize.tilesets.isEmpty()) return (0 until TileGraphics.NUM_TILESETS).toList()

        val invalid = colorize.tilesets.filter { it !in 0 until TileGraphics.NUM_TILESETS }.distinct()
        if (invalid.isNotEmpty()) {
            reportValidationIssue(context, "colorize.tilesets contains invalid tileset id(s): ${invalid.joinToString()}.")
            return null
        }
        return colorize.tilesets.distinct()
    }

    private fun resolveColorizeSpriteRegions(
        colorize: SmeditColorizeRequest,
        context: ApplyContext,
    ): List<SpritePalettes.PaletteRegion>? {
        if (colorize.spriteRegions.isEmpty()) return SpritePalettes.REGIONS

        val regions = mutableListOf<SpritePalettes.PaletteRegion>()
        val invalid = mutableListOf<String>()
        for (regionId in colorize.spriteRegions.distinct()) {
            val region = SpritePalettes.findRegion(regionId)
            if (region == null) invalid.add(regionId) else regions.add(region)
        }
        if (invalid.isNotEmpty()) {
            reportValidationIssue(context, "colorize.spriteRegions contains unknown region id(s): ${invalid.joinToString()}.")
            return null
        }
        return regions
    }

    private fun readSpritePaletteColors(
        rom: ByteArray,
        fileOffset: Int,
        region: SpritePalettes.PaletteRegion,
    ): IntArray? {
        if (fileOffset < 0 || fileOffset + region.byteSize > rom.size) return null
        val colors = IntArray(region.colorCount)
        for (i in 0 until region.colorCount) {
            val addr = fileOffset + i * 2
            colors[i] = (rom[addr].toInt() and 0xFF) or ((rom[addr + 1].toInt() and 0xFF) shl 8)
        }
        return colors
    }

    private fun createTilesetPaletteWriter(
        context: ApplyContext,
        missingRomMessage: String,
    ): TilesetPaletteWriter? {
        val rom = context.outputRom
        val parser = context.parser
        if (rom == null || parser == null) {
            context.warnings.add(missingRomMessage)
            return null
        }

        return TilesetPaletteWriter(
            rom = rom,
            parser = parser,
            tableFileOffset = parser.snesToPc(TileGraphics.TILESET_TABLE_SNES),
            allocator = RomFreeSpaceAllocator(
                romData = rom,
                snesToPc = parser::snesToPc,
                pcToSnes = parser::pcToSnes,
                guardBytes = 2,
            ),
        )
    }

    private fun resolveTilesetPaletteTarget(
        writer: TilesetPaletteWriter,
        tilesetId: Int,
        context: ApplyContext,
    ): TilesetPaletteTarget? {
        val entryFileOffset = writer.tableFileOffset + tilesetId * TILESET_TABLE_ENTRY_BYTES
        if (entryFileOffset < 0 || entryFileOffset + TILESET_TABLE_ENTRY_BYTES > writer.rom.size) {
            context.warnings.add("Tileset $tilesetId table entry is out of ROM range.")
            return null
        }

        val paletteSnes = readU24(writer.rom, entryFileOffset + TILESET_PALETTE_PTR_OFFSET)
        val paletteFileOffset = runCatching { writer.parser.snesToPc(paletteSnes) }.getOrNull()
        if (paletteFileOffset == null || paletteFileOffset !in writer.rom.indices) {
            context.warnings.add("Tileset $tilesetId palette pointer is out of ROM range.")
            return null
        }

        val originalSize = runCatching { writer.parser.decompressLZ2WithSize(paletteSnes).second }.getOrNull()
        if (originalSize == null || paletteFileOffset + originalSize > writer.rom.size) {
            context.warnings.add("Tileset $tilesetId original palette could not be decompressed safely.")
            return null
        }

        return TilesetPaletteTarget(
            tilesetId = tilesetId,
            entryPc = context.fileToPcOffset(entryFileOffset),
            paletteSnes = paletteSnes,
            palettePc = context.fileToPcOffset(paletteFileOffset),
            originalSize = originalSize,
        )
    }

    private fun writeTilesetPalette(
        writer: TilesetPaletteWriter,
        target: TilesetPaletteTarget,
        rawPalette: ByteArray,
        context: ApplyContext,
        clearOriginalOnRelocate: Boolean,
    ) {
        val compressed = LZ5Compressor.compress(rawPalette)
        if (compressed.size <= target.originalSize) {
            writeBytes(
                context = context,
                offset = target.palettePc,
                bytes = (compressed + ByteArray(target.originalSize - compressed.size) { 0xFF.toByte() }).toIntList(),
                label = "tileset ${target.tilesetId} palette",
            )
            return
        }

        val allocation = writer.allocator.allocatePalette(
            parser = writer.parser,
            romSize = writer.rom.size,
            originalSnesAddress = target.paletteSnes,
            compressed = compressed,
            tilesetId = target.tilesetId,
        )
        if (allocation == null) {
            context.warnings.add(
                "Tileset ${target.tilesetId} palette compressed to ${compressed.size} bytes, exceeds original " +
                    "${target.originalSize} bytes, and no free space was found."
            )
            return
        }

        writeBytes(
            context,
            context.fileToPcOffset(allocation.pcOffset),
            compressed.toIntList(),
            "tileset ${target.tilesetId} relocated palette",
        )
        writeBytes(
            context,
            target.entryPc + TILESET_PALETTE_PTR_OFFSET,
            u24Bytes(allocation.snesAddress),
            "tileset ${target.tilesetId} palette pointer",
        )
        if (clearOriginalOnRelocate) {
            writeBytes(
                context = context,
                offset = target.palettePc,
                bytes = List(target.originalSize) { 0xFF },
                label = "tileset ${target.tilesetId} old palette free fill",
            )
        }
    }

    private fun applySpritePaletteOverrides(
        gfx: TilesetGfxData,
        context: ApplyContext,
    ) {
        if (gfx.spritePalettes.isEmpty()) return

        for ((regionId, paletteBase64) in gfx.spritePalettes) {
            if (regionId.startsWith(ENEMY_PALETTE_PREFIX)) {
                applyEnemyPaletteOverride(regionId, paletteBase64, context)
                continue
            }

            val region = SpritePalettes.findRegion(regionId)
            if (region == null) {
                context.warnings.add("Sprite palette '$regionId' is not a known fixed palette region.")
                continue
            }
            val rawBytes = decodeBase64(paletteBase64, "sprite palette ${region.id}", context.warnings) ?: continue
            val colors = SpritePalettes.bytesToColors(rawBytes)
            if (colors.size != region.colorCount) {
                context.warnings.add(
                    "Sprite palette '${region.id}' has ${rawBytes.size} bytes; expected ${region.byteSize}."
                )
                continue
            }
            writeBytes(context, region.offset, SpritePalettes.colorsToBytes(colors).toIntList(), region.name)
        }
    }

    private fun applyEnemyPaletteOverride(
        regionId: String,
        paletteBase64: String,
        context: ApplyContext,
    ) {
        val rom = context.outputRom
        if (rom == null) {
            context.warnings.add("Enemy palette '$regionId' requires --rom because the palette pointer is species-dependent.")
            return
        }

        val speciesHex = regionId.removePrefix(ENEMY_PALETTE_PREFIX)
        val speciesId = speciesHex.toIntOrNull(16)
        if (speciesId == null) {
            context.warnings.add("Enemy palette '$regionId' has an invalid species id.")
            return
        }

        val rawBytes = decodeBase64(paletteBase64, "enemy palette $speciesHex", context.warnings) ?: return
        if (rawBytes.size != ENEMY_PALETTE_BYTES) {
            context.warnings.add("Enemy palette $speciesHex has ${rawBytes.size} bytes; expected $ENEMY_PALETTE_BYTES.")
            return
        }

        val headerPc = context.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        val headerFileOffset = context.pcToFileOffset(headerPc)
        if (headerFileOffset < 0 || headerFileOffset + ENEMY_HEADER_AI_BANK_OFFSET >= rom.size) {
            context.warnings.add("Enemy palette $speciesHex has an invalid species header.")
            return
        }

        val palettePtr = readU16(rom, headerFileOffset + ENEMY_HEADER_PALETTE_PTR_OFFSET)
        val aiBank = readU8(rom, headerFileOffset + ENEMY_HEADER_AI_BANK_OFFSET)
        val paletteSnes = (aiBank shl 16) or (palettePtr and 0xFFFF)
        val palettePc = context.snesToPc(paletteSnes)
        val paletteFileOffset = context.pcToFileOffset(palettePc)
        if (paletteFileOffset < 0 || paletteFileOffset + ENEMY_PALETTE_BYTES > rom.size) {
            context.warnings.add("Enemy palette $speciesHex resolved outside ROM bounds.")
            return
        }

        writeBytes(context, palettePc, rawBytes.toIntList(), "enemy palette $speciesHex")
    }

    private fun createRequestPatch(key: String, request: SmeditPatchRequest): PatchEntry {
        val resolvedKey = SmeditPatchCatalog.resolvePatchKey(key)
        val configType = request.configType ?: resolvedKey.takeIf { it in SmeditPatchCatalog.supportedConfigTypes() }
        return PatchEntry(
            SmPatch(
                id = if (configType != null) "config_$configType" else resolvedKey,
                name = resolvedKey.replace('_', ' ').replaceFirstChar { it.uppercase() },
                enabled = request.enabled,
                configType = configType,
            ),
            "request",
        )
    }

    private fun applyConfigPatch(
        patch: SmPatch,
        context: ApplyContext,
    ): String? =
        when (patch.configType) {
            CERES_ESCAPE_CONFIG_TYPE -> {
                val data = patch.configData
                val totalSecs = (
                    patch.configValue
                        ?: data?.get("seconds")
                        ?: data?.get("total_seconds")
                        ?: data?.get("totalSeconds")
                        ?: 60
                    ).coerceIn(15, 600)
                val mins = totalSecs / 60
                val secs = totalSecs % 60
                val minsBcd = ((mins / 10) shl 4) or (mins % 10)
                val secsBcd = ((secs / 10) shl 4) or (secs % 10)
                val value = (minsBcd shl 8) or secsBcd
                writeWord(context, context.snesToPc(CERES_TIMER_OPERAND_SNES), value, "Ceres escape timer")
                CERES_ESCAPE_CONFIG_TYPE
            }
            BOMB_CONFIG_TYPE -> {
                val data = patch.configData
                val defaults = if (context.outputRom != null) bombDefaults(context) else BombDefaults.vanilla()
                val maxActive = (data?.get(BOMB_MAX_ACTIVE_KEY) ?: defaults.maxActiveBombs)
                    .coerceIn(1, BOMB_MAX_PROJECTILE_SLOTS)
                val fuseFrames = (data?.get(BOMB_FUSE_FRAMES_KEY) ?: defaults.fuseFrames)
                    .coerceIn(1, 9999)
                val cooldownFrames = (
                    data?.get(BOMB_COOLDOWN_FRAMES_KEY)
                        ?: calculateBombCooldownForConfig(maxActive, fuseFrames, defaults.cooldownFrames)
                    ).coerceIn(0, 255)
                val explosionDelay = (data?.get(BOMB_EXPLOSION_FRAME_DELAY_KEY) ?: defaults.explosionFrameDelay)
                    .coerceIn(1, 255)

                writeWord(context, BOMB_ACTIVE_HARD_CAP_OPERAND_PC, maxActive, "Bomb hard cap")
                writeByte(context, BOMB_COOLDOWN_PC, cooldownFrames, "Bomb cooldown")
                writeWord(context, BOMB_FUSE_TIMER_PC, fuseFrames, "Bomb fuse")
                writeWord(context, BOMB_EXPLOSION_FRAME_DELAY_OPERAND_PC, explosionDelay, "Bomb explosion delay")
                BOMB_CONFIG_TYPE
            }
            FANFARE_CONFIG_TYPE -> {
                val data = patch.configData
                val defaultFrames = context.readWordOrNull(FANFARE_MESSAGE_BOX_WAIT_PC)
                    ?: FANFARE_DEFAULT_FRAMES
                val frames = (data?.get(FANFARE_FRAMES_KEY) ?: defaultFrames)
                    .coerceIn(FANFARE_MIN_FRAMES, FANFARE_MAX_FRAMES)
                writeWord(context, FANFARE_MESSAGE_BOX_WAIT_PC, frames, "Fanfare message wait")
                for (offset in FANFARE_MUSIC_RESUME_DELAY_PCS) {
                    writeWord(context, offset, frames, "Fanfare music resume")
                }
                FANFARE_CONFIG_TYPE
            }
            BEAM_DAMAGE_CONFIG_TYPE -> {
                applyBeamDamagePatch(patch, context)
                BEAM_DAMAGE_CONFIG_TYPE
            }
            ENEMY_STATS_CONFIG_TYPE -> {
                applyEnemyStatsPatch(patch, context)
                ENEMY_STATS_CONFIG_TYPE
            }
            ENEMY_DROPS_CONFIG_TYPE -> {
                applyEnemyDropsPatch(patch, context)
                ENEMY_DROPS_CONFIG_TYPE
            }
            ENEMY_VULN_CONFIG_TYPE -> {
                applyEnemyVulnerabilityPatch(patch, context)
                ENEMY_VULN_CONFIG_TYPE
            }
            BOSS_STATS_CONFIG_TYPE -> {
                applyBossStatsPatch(patch, context)
                BOSS_STATS_CONFIG_TYPE
            }
            SAMUS_PHYSICS_CONFIG_TYPE -> {
                applySamusPhysicsPatch(patch, context)
                SAMUS_PHYSICS_CONFIG_TYPE
            }
            CONTROLLER_CONFIG_TYPE -> {
                applyControllerConfigPatch(patch, context)
                CONTROLLER_CONFIG_TYPE
            }
            else -> {
                val configType = patch.configType
                if (configType != null && configType in HEADLESS_BOSS_BEHAVIOR_BY_CONFIG_TYPE) {
                    applyBossBehaviorPatch(configType, patch, context)
                    configType
                } else {
                    null
                }
            }
        }

    private fun validateConfigPatch(
        patch: SmPatch,
        context: ApplyContext,
    ) {
        val configType = patch.configType ?: return
        val data = patch.configData ?: return
        if (data.isEmpty()) return

        val schema = SmeditPatchCatalog.configSchema(configType) ?: return
        val fieldsByKey = schema.fields.associateBy { it.key }
        val unknownKeys = data.keys.filter { it !in fieldsByKey }.sorted()
        if (unknownKeys.isNotEmpty()) {
            reportConfigValidationIssue(
                context,
                "Config patch '$configType' has unknown config key(s): ${unknownKeys.joinToString()}."
            )
        }

        for ((key, value) in data) {
            val field = fieldsByKey[key] ?: continue
            if (value < field.min || value > field.max) {
                reportConfigValidationIssue(
                    context,
                    "Config patch '$configType' key '$key' value $value is outside ${field.min}..${field.max}; " +
                        "headless build will clamp it."
                )
            }
            val choiceValues = field.choices.map { it.value }.toSet()
            if (choiceValues.isNotEmpty() && value !in choiceValues) {
                val choices = field.choices.joinToString { "${it.label}=0x${it.value.toString(16)}" }
                reportConfigValidationIssue(
                    context,
                    "Config patch '$configType' key '$key' value 0x${value.toString(16)} is not one of: $choices."
                )
            }
        }
    }

    private fun reportConfigValidationIssue(
        context: ApplyContext,
        message: String,
    ) {
        reportValidationIssue(context, message)
    }

    private fun reportValidationIssue(
        context: ApplyContext,
        message: String,
    ) {
        if (context.strictConfigValidation) {
            throw IllegalArgumentException(message)
        }
        context.warnings.add(message)
    }

    private fun applyBeamDamagePatch(
        patch: SmPatch,
        context: ApplyContext,
    ) {
        val data = patch.configData ?: return
        for (beam in HEADLESS_BEAMS) {
            val damage = data[beam.key]?.coerceIn(0, 0xFFFF) ?: continue
            val chargedDamage = (damage * 3).coerceIn(0, 0xFFFF)
            writeWord(context, context.snesToPc(beam.snesAddress), damage, "Beam damage ${beam.key}")
            writeWord(context, context.snesToPc(beam.chargedSnesAddress), chargedDamage, "Charged beam damage ${beam.key}")
        }
    }

    private fun applyEnemyStatsPatch(
        patch: SmPatch,
        context: ApplyContext,
    ) {
        val data = patch.configData ?: return
        for (enemy in HEADLESS_ENEMY_DEFS) {
            val enemyHeaderPc = context.snesToPc(RomConstants.BANK_ENEMY_AI or enemy.speciesId)
            val hp = data["${enemy.key}_hp"]
            if (hp != null) {
                writeWord(context, enemyHeaderPc + ENEMY_HEADER_HP_OFFSET, hp.coerceIn(0, 0xFFFF), "Enemy HP ${enemy.key}")
            }

            val damage = data["${enemy.key}_dmg"]
            if (damage != null) {
                writeWord(
                    context,
                    enemyHeaderPc + ENEMY_HEADER_CONTACT_DAMAGE_OFFSET,
                    damage.coerceIn(0, 0xFFFF),
                    "Enemy contact damage ${enemy.key}",
                )
            }

            for (field in ENEMY_HEADER_POINTER_FIELDS) {
                val value = data["${enemy.key}${field.suffix}"] ?: continue
                writeWord(
                    context,
                    enemyHeaderPc + field.offset,
                    value.coerceIn(0, 0xFFFF),
                    "Enemy field ${enemy.key}${field.suffix}",
                )
            }
        }
    }

    private fun applyBossStatsPatch(
        patch: SmPatch,
        context: ApplyContext,
    ) {
        val data = patch.configData ?: return
        for (field in HEADLESS_BOSS_STAT_FIELDS) {
            val value = data[field.key]?.coerceIn(0, 0xFFFF) ?: continue
            for (speciesId in field.writeSpeciesIds) {
                val enemyHeaderPc = context.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
                writeWord(context, enemyHeaderPc + field.offset, value, "Boss stat ${field.key}")
            }
        }
    }

    private fun applySamusPhysicsPatch(
        patch: SmPatch,
        context: ApplyContext,
    ) {
        val data = patch.configData ?: return
        for (field in HEADLESS_PHYSICS_FIELDS) {
            val value = data[field.key]?.coerceIn(0, 255) ?: continue
            writeByte(context, field.pcOffset, value, "Samus physics ${field.key}")
        }
    }

    private fun applyControllerConfigPatch(
        patch: SmPatch,
        context: ApplyContext,
    ) {
        val data = patch.configData ?: return
        for (slot in HEADLESS_CONTROLLER_SLOTS) {
            val value = data[slot.key]?.coerceIn(0, 0xFFFF) ?: continue
            writeWord(context, CONTROLLER_TABLE_PC + slot.tableIndex * 2, value, "Controller config ${slot.key}")
        }
    }

    private fun applyBossBehaviorPatch(
        configType: String,
        patch: SmPatch,
        context: ApplyContext,
    ) {
        val data = patch.configData ?: return
        val definition = HEADLESS_BOSS_BEHAVIOR_BY_CONFIG_TYPE.getValue(configType)
        for (field in definition.fields) {
            val value = data[field.key] ?: continue
            val coerced = coerceHeadlessBossBehaviorValue(field, value)
            for (snesAddress in field.writeSnesAddresses) {
                writeWord(
                    context,
                    context.snesToPc(snesAddress),
                    coerced,
                    "Boss behavior ${definition.configType}.${field.key}",
                )
            }
        }
    }

    private fun applyRoomNamePauseMapPatch(
        entry: PatchEntry,
        project: SmEditProject?,
        context: ApplyContext,
    ): List<SmeditAppliedPatchReport> {
        val rom = context.outputRom
        val parser = context.parser
        if (rom == null || parser == null) {
            context.warnings.add("Room-name pause-map patch requires --rom because it allocates ROM free space.")
            return emptyList()
        }

        val patch = entry.patch
        val result = try {
            RoomNamePauseMapPatch.install(
                romData = rom,
                snesToPc = parser::snesToPc,
                pcToSnes = parser::pcToSnes,
                rooms = RoomRepository().getAllRooms(),
                overrides = project?.roomNameOverrides ?: emptyMap(),
                alignment = RoomNamePauseMapPatch.RoomNameAlignment.fromConfig(
                    patch.configData?.get(RoomNamePauseMapPatch.CONFIG_ALIGNMENT_KEY)
                ),
            )
        } catch (e: Exception) {
            throw IllegalArgumentException("Room-name pause-map patch could not be written safely: ${e.message}", e)
        }

        val logicalWrites = result.writes.map { write ->
            PatchWrite(context.fileToPcOffset(write.offset.toInt()).toLong(), write.bytes)
        }
        context.patchWrites.addAll(logicalWrites)
        context.appliedWriteCount += result.writes.size
        return listOf(
            SmeditAppliedPatchReport(
                identifier = SmeditPatchCatalog.publicPatchId(patch),
                name = patch.name,
                source = entry.source,
                configType = patch.configType,
                writes = result.writes.size,
                bytes = logicalWrites.totalByteCount(),
            )
        )
    }

    private fun applyCombinedPerFrameHook(
        patchEntries: List<PatchEntry>,
        context: ApplyContext,
    ): SmeditAppliedPatchReport? {
        val bossPatch = patchEntries.firstOrNull {
            it.patch.enabled && it.patch.configType == BOSS_DEFEATED_CONFIG_TYPE
        }?.patch
        val enabledBosses = bossPatch?.configData
            ?.filterValues { it != 0 }
            ?.keys
            ?.toSet()
            ?: emptySet()
        val hyperBeam = patchEntries.any { it.patch.enabled && it.patch.configType == HYPER_BEAM_CONFIG_TYPE }
        val infiniteBlueSuit = patchEntries.any { it.patch.enabled && it.patch.id == INFINITE_BLUE_SUIT_PATCH_ID }
        val code = buildHeadlessPerFrameHook(enabledBosses, hyperBeam, infiniteBlueSuit)
        if (code.isEmpty()) return null

        val beforePatchBytes = context.patchWrites.totalByteCount()
        val beforeWrites = context.appliedWriteCount
        if (writeBytes(context, PER_FRAME_HOOK_PAYLOAD_PC, code, "combined per-frame hook payload")) {
            context.appliedWriteCount++
        }
        if (writeBytes(context, PER_FRAME_HOOK_PATCH_PC, PER_FRAME_HOOK_JSL, "combined per-frame hook JSL")) {
            context.appliedWriteCount++
        }
        val writes = context.appliedWriteCount - beforeWrites
        val bytes = context.patchWrites.totalByteCount() - beforePatchBytes
        if (writes == 0 && bytes == 0) return null

        val parts = mutableListOf<String>()
        if (enabledBosses.isNotEmpty()) parts.add("boss_defeated")
        if (hyperBeam) parts.add("hyper_beam")
        if (infiniteBlueSuit) parts.add(INFINITE_BLUE_SUIT_PATCH_ID)
        return SmeditAppliedPatchReport(
            identifier = "combined_per_frame_hook",
            name = "Combined Per-frame Hook (${parts.joinToString()})",
            source = "generated",
            configType = "per_frame_hook",
            writes = writes,
            bytes = bytes,
        )
    }

    private fun applyEnemyDropsPatch(
        patch: SmPatch,
        context: ApplyContext,
    ) {
        val data = patch.configData ?: return
        for (enemy in HEADLESS_ENEMY_DEFS) {
            val dropTablePc = resolveEnemyBankB4TablePc(
                context = context,
                enemy = enemy,
                pointerOffset = ENEMY_HEADER_DROP_TABLE_PTR_OFFSET,
                tableSize = ENEMY_DROP_TABLE_BYTES,
                tableLabel = "drop table",
            ) ?: continue

            for (index in 0 until ENEMY_DROP_TABLE_BYTES) {
                val value = data["${enemy.key}_drop$index"] ?: continue
                writeByte(context, dropTablePc + index, value.coerceIn(0, 255), "Enemy drop ${enemy.key}[$index]")
            }
        }
    }

    private fun applyEnemyVulnerabilityPatch(
        patch: SmPatch,
        context: ApplyContext,
    ) {
        val data = patch.configData ?: return
        for (enemy in HEADLESS_ENEMY_DEFS) {
            val vulnerabilityTablePc = resolveEnemyBankB4TablePc(
                context = context,
                enemy = enemy,
                pointerOffset = ENEMY_HEADER_VULNERABILITY_TABLE_PTR_OFFSET,
                tableSize = ENEMY_VULNERABILITY_TABLE_BYTES,
                tableLabel = "vulnerability table",
            ) ?: continue

            for (index in 0 until ENEMY_VULNERABILITY_TABLE_BYTES) {
                val value = data["${enemy.key}_vuln$index"] ?: continue
                writeByte(
                    context,
                    vulnerabilityTablePc + index,
                    value.coerceIn(0, 255),
                    "Enemy vulnerability ${enemy.key}[$index]",
                )
            }
        }
    }

    private fun resolveEnemyBankB4TablePc(
        context: ApplyContext,
        enemy: HeadlessEnemyDef,
        pointerOffset: Int,
        tableSize: Int,
        tableLabel: String,
    ): Int? {
        val rom = context.outputRom
        if (rom == null) {
            context.warnings.add("Enemy $tableLabel patch requires --rom because enemy table pointers are ROM-dependent.")
            return null
        }

        val headerPc = context.snesToPc(RomConstants.BANK_ENEMY_AI or enemy.speciesId)
        val headerFileOffset = context.pcToFileOffset(headerPc)
        if (headerFileOffset < 0 || headerFileOffset + pointerOffset + 1 >= rom.size) {
            context.warnings.add("Enemy ${enemy.key} $tableLabel pointer is out of ROM range.")
            return null
        }

        val pointer = readU16(rom, headerFileOffset + pointerOffset)
        if (pointer == 0 || pointer == 0xFFFF) return null

        val tablePc = context.snesToPc(ENEMY_DATA_BANK_SNES or pointer)
        val tableFileOffset = context.pcToFileOffset(tablePc)
        if (tableFileOffset < 0 || tableFileOffset + tableSize > rom.size) {
            context.warnings.add("Enemy ${enemy.key} $tableLabel resolved outside ROM bounds.")
            return null
        }

        return tablePc
    }

    private fun applyRawPatchWrites(
        patch: SmPatch,
        context: ApplyContext,
    ): Int {
        var writes = 0
        for (write in patch.writes) {
            val offset = write.offset.toInt()
            if (writeBytes(context, offset, write.bytes, patch.name)) {
                writes++
                context.appliedWriteCount++
            }
        }
        return writes
    }

    private fun resolveRawWriteOffset(write: SmeditRawWriteRequest, context: ApplyContext): Int {
        val explicitCount = listOf(write.pcOffset, write.snesAddress, write.address).count { it != null }
        require(explicitCount == 1) {
            "Raw write '${write.label}' must specify exactly one of pcOffset, snesAddress, or address"
        }
        require(write.bytes.isNotEmpty()) { "Raw write '${write.label}' has no bytes" }

        return when {
            write.pcOffset != null -> write.pcOffset
            write.snesAddress != null -> context.snesToPc(write.snesAddress)
            else -> parseAddress(write.address ?: error("address missing"), context)
        }
    }

    private fun parseAddress(address: String, context: ApplyContext): Int {
        val trimmed = address.trim()
        return when {
            trimmed.startsWith("pc:", ignoreCase = true) ->
                parseNumber(trimmed.substringAfter(':'))
            trimmed.startsWith("snes:", ignoreCase = true) ->
                context.snesToPc(parseSnesAddress(trimmed.substringAfter(':')))
            ':' in trimmed ->
                context.snesToPc(parseSnesAddress(trimmed))
            else -> parseNumber(trimmed)
        }
    }

    private fun parseSnesAddress(address: String): Int {
        val cleaned = address.trim().removePrefix("$")
        val parts = cleaned.split(':')
        require(parts.size == 2) { "SNES address must look like \$80:9E0E: $address" }
        val bank = parseHexComponent(parts[0])
        val offset = parseHexComponent(parts[1])
        return ((bank and 0xFF) shl 16) or (offset and 0xFFFF)
    }

    private fun parseHexComponent(value: String): Int {
        val trimmed = value.trim()
        val normalized = when {
            trimmed.startsWith("0x", ignoreCase = true) -> trimmed.drop(2)
            trimmed.startsWith("$") -> trimmed.drop(1)
            else -> trimmed
        }
        return normalized.toInt(16)
    }

    private fun parseNumber(value: String): Int {
        val trimmed = value.trim()
        val normalized = when {
            trimmed.startsWith("0x", ignoreCase = true) -> trimmed.drop(2)
            trimmed.startsWith("$") -> trimmed.drop(1)
            else -> return trimmed.toInt()
        }
        return normalized.toInt(16)
    }

    private fun writeBytes(
        context: ApplyContext,
        offset: Int,
        bytes: List<Int>,
        label: String,
    ): Boolean {
        val normalizedBytes = bytes.map { it.coerceIn(0, 255) }
        if (normalizedBytes.isEmpty()) {
            context.warnings.add("$label write has no bytes")
            return false
        }
        val endOffset = offset.toLong() + normalizedBytes.size - 1
        if (offset < 0 || offset > 0xFFFFFF || endOffset > 0xFFFFFF) {
            context.warnings.add("$label write out of IPS range at 0x${offset.toString(16)} (${bytes.size} bytes)")
            return false
        }

        val rom = context.outputRom
        if (rom != null) {
            val fileOffset = context.pcToFileOffset(offset)
            val fileEndOffset = fileOffset.toLong() + normalizedBytes.size - 1
            if (fileOffset < 0 || fileEndOffset >= rom.size) {
                context.warnings.add("$label write out of ROM range at 0x${offset.toString(16)} (${bytes.size} bytes)")
                return false
            }
            for (i in normalizedBytes.indices) {
                rom[fileOffset + i] = normalizedBytes[i].toByte()
            }
        }

        context.patchWrites.add(PatchWrite(offset.toLong(), normalizedBytes))
        return true
    }

    private fun writeByte(
        context: ApplyContext,
        offset: Int,
        value: Int,
        label: String,
    ) {
        if (writeBytes(context, offset, listOf(value), label)) {
            context.appliedWriteCount++
        }
    }

    private fun writeWord(
        context: ApplyContext,
        offset: Int,
        value: Int,
        label: String,
    ) {
        if (writeBytes(context, offset, listOf(value and 0xFF, (value ushr 8) and 0xFF), label)) {
            context.appliedWriteCount++
        }
    }

    private fun RomFreeSpaceAllocator.allocatePalette(
        parser: RomParser,
        romSize: Int,
        originalSnesAddress: Int,
        compressed: ByteArray,
        tilesetId: Int,
    ) = reserve(
        size = compressed.size,
        banks = paletteRelocationBanks(parser, romSize, originalSnesAddress),
        label = "tileset $tilesetId palette",
    )

    private data class ApplyContext(
        val outputRom: ByteArray?,
        val parser: RomParser?,
        val warnings: MutableList<String>,
        val strictConfigValidation: Boolean,
        val patchWrites: MutableList<PatchWrite> = mutableListOf(),
        var appliedWriteCount: Int = 0,
    ) {
        val romHeaderOffset: Int = outputRom?.smcHeaderOffset() ?: 0

        fun snesToPc(snesAddress: Int): Int =
            snesToPcLoRom(snesAddress)

        fun pcToFileOffset(pcOffset: Int): Int =
            pcOffset + romHeaderOffset

        fun fileToPcOffset(fileOffset: Int): Int =
            fileOffset - romHeaderOffset

        fun readByteOrNull(pcOffset: Int): Int? =
            outputRom?.readByteOrNull(pcToFileOffset(pcOffset))

        fun readWordOrNull(pcOffset: Int): Int? =
            outputRom?.readWordOrNull(pcToFileOffset(pcOffset))
    }

    private data class TilesetPaletteWriter(
        val rom: ByteArray,
        val parser: RomParser,
        val tableFileOffset: Int,
        val allocator: RomFreeSpaceAllocator,
    )

    private data class TilesetPaletteTarget(
        val tilesetId: Int,
        val entryPc: Int,
        val paletteSnes: Int,
        val palettePc: Int,
        val originalSize: Int,
    )

    private data class TilesetPaletteSnapshot(
        val target: TilesetPaletteTarget,
        val rawPalette: ByteArray,
    )

    private data class HeadlessItemDefinition(
        val id: String,
        val name: String,
        val shortLabel: String,
        val visiblePlmId: Int?,
        val chozoPlmId: Int?,
        val hiddenPlmId: Int?,
        val sourcePatchId: String? = null,
    ) {
        fun plmIds(): List<Int> =
            listOfNotNull(visiblePlmId, chozoPlmId, hiddenPlmId)

        fun plmIdFor(kind: String): Int? =
            when (kind.lowercase().filter { it.isLetterOrDigit() }) {
                "visible" -> visiblePlmId
                "chozo", "chozostatue" -> chozoPlmId
                "hidden" -> hiddenPlmId
                else -> null
            }
    }

    private data class PatchEntry(
        val patch: SmPatch,
        val source: String,
    )

    private data class BombDefaults(
        val maxActiveBombs: Int,
        val fuseFrames: Int,
        val cooldownFrames: Int,
        val explosionFrameDelay: Int,
        val hardCap: Int,
    ) {
        companion object {
            fun vanilla(): BombDefaults =
                BombDefaults(
                    maxActiveBombs = BOMB_DEFAULT_MAX_ACTIVE,
                    fuseFrames = BOMB_DEFAULT_FUSE_FRAMES,
                    cooldownFrames = BOMB_DEFAULT_COOLDOWN_FRAMES,
                    explosionFrameDelay = BOMB_DEFAULT_EXPLOSION_FRAME_DELAY,
                    hardCap = BOMB_DEFAULT_HARD_CAP,
                )
        }
    }

    private fun bombDefaults(context: ApplyContext): BombDefaults {
        val fuse = context.readWordOrNull(BOMB_FUSE_TIMER_PC) ?: BOMB_DEFAULT_FUSE_FRAMES
        val hardCap = context.readWordOrNull(BOMB_ACTIVE_HARD_CAP_OPERAND_PC) ?: BOMB_DEFAULT_HARD_CAP
        val cooldown = context.readByteOrNull(BOMB_COOLDOWN_PC) ?: BOMB_DEFAULT_COOLDOWN_FRAMES
        val explosionDelay = context.readWordOrNull(BOMB_EXPLOSION_FRAME_DELAY_OPERAND_PC)
            ?: BOMB_DEFAULT_EXPLOSION_FRAME_DELAY
        return BombDefaults(
            maxActiveBombs = derivePracticalBombCap(fuse, cooldown, hardCap),
            fuseFrames = fuse,
            cooldownFrames = cooldown,
            explosionFrameDelay = explosionDelay,
            hardCap = hardCap,
        )
    }
}

private fun calculateBombCooldownForConfig(
    maxActiveBombs: Int,
    fuseFrames: Int,
    baseCooldownFrames: Int = BOMB_DEFAULT_COOLDOWN_FRAMES,
): Int {
    val cap = maxActiveBombs.coerceIn(1, BOMB_MAX_PROJECTILE_SLOTS)
    val base = baseCooldownFrames.coerceIn(0, 255)
    if (cap <= BOMB_DEFAULT_MAX_ACTIVE) return base

    val fuse = fuseFrames.coerceIn(1, 65535)
    val neededToReachCap = max(0, (fuse - 1) / cap)
    return min(base, neededToReachCap).coerceIn(0, 255)
}

private fun derivePracticalBombCap(fuseFrames: Int, cooldownFrames: Int, hardCap: Int): Int {
    val cap = hardCap.coerceIn(1, BOMB_MAX_PROJECTILE_SLOTS)
    val interval = (cooldownFrames + 1).coerceIn(1, 256)
    return (fuseFrames / interval).coerceAtLeast(1).coerceAtMost(cap)
}

private fun snesToPcLoRom(snesAddress: Int): Int {
    val bank = (snesAddress shr 16) and 0xFF
    val address = snesAddress and 0xFFFF
    return ((bank and 0x7F) * 0x8000) + (address and 0x7FFF)
}

private fun paletteRelocationBanks(parser: RomParser, romSize: Int, originalSnesAddress: Int): List<Int> {
    val originalBank = (originalSnesAddress shr 16) and 0xFF
    return (listOf(originalBank) + (0xCE downTo 0xC0) + (0xBF downTo 0xB0))
        .distinct()
        .filter { bank ->
            val bankStart = runCatching { parser.snesToPc((bank shl 16) or 0x8000) }.getOrNull()
            val bankEnd = runCatching { parser.snesToPc((bank shl 16) or 0xFFFF) + 1 }.getOrNull()
            bankStart != null && bankEnd != null && bankStart >= 0 && bankEnd <= romSize
        }
}

private fun String?.isDeferredGeneratedConfigType(): Boolean =
    this == ROOM_NAME_PAUSE_MAP_CONFIG_TYPE || this == BOSS_DEFEATED_CONFIG_TYPE || this == HYPER_BEAM_CONFIG_TYPE

private fun decodeBase64(value: String, label: String, warnings: MutableList<String>): ByteArray? =
    try {
        Base64.getDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
        warnings.add("$label is not valid base64: ${e.message}")
        null
    }

private fun u24Bytes(value: Int): List<Int> =
    listOf(value and 0xFF, (value ushr 8) and 0xFF, (value ushr 16) and 0xFF)

private fun u16Bytes(value: Int): List<Int> =
    listOf(value and 0xFF, (value ushr 8) and 0xFF)

private fun ByteArray.toIntList(): List<Int> =
    map { it.toInt() and 0xFF }

private fun ByteArray.smcHeaderOffset(): Int =
    if (size == RomConstants.ROM_SIZE_WITH_HEADER) RomConstants.SMC_HEADER_SIZE else 0

private fun ByteArray.headerlessRomBytes(): ByteArray =
    if (smcHeaderOffset() == 0) this else copyOfRange(RomConstants.SMC_HEADER_SIZE, size)

private fun ByteArray.readByteOrNull(offset: Int): Int? =
    if (offset in indices) this[offset].toInt() and 0xFF else null

private fun ByteArray.readWordOrNull(offset: Int): Int? =
    if (offset >= 0 && offset + 1 < size) {
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    } else {
        null
    }

private fun ByteArray.changedByteCountFrom(original: ByteArray): Int {
    val limit = minOf(size, original.size)
    var changed = kotlin.math.abs(size - original.size)
    for (i in 0 until limit) {
        if (this[i] != original[i]) changed++
    }
    return changed
}

private fun List<PatchWrite>.totalByteCount(): Int =
    sumOf { it.bytes.size }

private fun TilesetGfxData.unsupportedDescriptions(hasRom: Boolean): List<String> {
    val ignored = mutableListOf<String>()
    if (varGfx.isNotEmpty()) ignored.add("tileset graphics: ${varGfx.size}")
    if (creGfx != null) ignored.add("CRE graphics")
    if (tileTables.isNotEmpty()) ignored.add("tileset metatile tables: ${tileTables.size}")
    if (creTileTable != null) ignored.add("CRE metatile table")
    if (enemyGfx.isNotEmpty()) ignored.add("enemy graphics: ${enemyGfx.size}")
    if (spriteTileBlocks.isNotEmpty()) ignored.add("sprite tile blocks: ${spriteTileBlocks.size}")
    if (paletteEffects.isNotEmpty()) ignored.add("palette effect metadata: ${paletteEffects.size}")
    if (!hasRom && palettes.isNotEmpty()) ignored.add("tileset palettes require --rom: ${palettes.size}")

    val dynamicEnemyPalettes = spritePalettes.keys.count { it.startsWith(ENEMY_PALETTE_PREFIX) }
    if (!hasRom && dynamicEnemyPalettes > 0) {
        ignored.add("enemy palettes require --rom: $dynamicEnemyPalettes")
    }
    return ignored
}

private fun RoomEdits.hasUnsupportedHeadlessRoomEdits(): Boolean =
    doorChanges.isNotEmpty() ||
        enemyChanges.isNotEmpty() ||
        fxChange != null ||
        stateDataChange != null ||
        roomHeaderChange != null ||
        customScrollCommands.isNotEmpty() ||
        saveStationSpawns.isNotEmpty() ||
        operations.any {
            it.enemyAdds.isNotEmpty() ||
                it.enemyRemoves.isNotEmpty() ||
                it.enemyUpdates.isNotEmpty() ||
                it.stateDataBefore != null ||
                it.stateDataAfter != null ||
                it.fxBefore != null ||
                it.fxAfter != null
        }

private fun RoomEdits.effectiveScrollChanges(): List<ScrollChange> {
    val byScreen = linkedMapOf<Pair<Int, Int>, ScrollChange>()
    for (operation in operations) {
        for (change in operation.scrollEdits) {
            byScreen[change.screenX to change.screenY] = change
        }
    }
    for (change in scrollChanges) {
        byScreen[change.screenX to change.screenY] = change
    }
    return byScreen.values.toList()
}

private const val TILESET_TABLE_ENTRY_BYTES = 9
private const val TILESET_PALETTE_PTR_OFFSET = 6
private const val TILESET_PALETTE_BYTES = 256
private const val ENEMY_PALETTE_PREFIX = "enemy_pal:"
private const val ENEMY_PALETTE_BYTES = 32
private const val ENEMY_HEADER_PALETTE_PTR_OFFSET = 2
private const val ENEMY_HEADER_AI_BANK_OFFSET = 0x0C
private const val INFINITE_BLUE_SUIT_PATCH_ID = "bundled_infinite_blue_suit"

private fun SmPatch.deepCopy(): SmPatch =
    SmPatch(
        id = id,
        name = name,
        description = description,
        enabled = enabled,
        writes = writes.map { PatchWrite(it.offset, it.bytes.toList()) }.toMutableList(),
        configType = configType,
        configValue = configValue,
        configData = configData?.toMutableMap(),
        customItems = customItems.map { it.copy() }.toMutableList(),
    )

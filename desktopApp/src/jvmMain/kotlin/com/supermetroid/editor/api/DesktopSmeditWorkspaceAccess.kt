package com.supermetroid.editor.api

import com.supermetroid.editor.ai.AiCredentialStore
import com.supermetroid.editor.ai.AiCredentialSource
import com.supermetroid.editor.ai.AiProviderId
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.asm.findAsmSourceText
import com.supermetroid.editor.data.AppConfig
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.headless.SmeditPatchCatalog
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.NativeLiquidOptions
import com.supermetroid.editor.rom.SpritePalettes
import com.supermetroid.editor.rom.TileGraphics
import com.supermetroid.editor.rom.baseSourceStateIndex
import com.supermetroid.editor.rom.displaySummary
import com.supermetroid.editor.rom.emptyDefaultFxEntry
import com.supermetroid.editor.rom.ensureStateManifest
import com.supermetroid.editor.rom.withFxChange
import com.supermetroid.editor.asm.snapshotRoomForResize
import com.supermetroid.editor.ui.EditorState
import java.io.File

internal class DesktopSmeditWorkspaceAccess(
    private val editorState: EditorState,
    private val asmWorkspaceState: AsmWorkspaceState,
    private val parser: () -> RomParser?,
    private val roomInfos: () -> List<RoomInfo>,
    private val selectedRoom: () -> RoomInfo?,
    private val credentialStore: AiCredentialStore = AiCredentialStore(),
) : SmeditWorkspaceAccess {
    override fun workspaceSummary(): SmeditWorkspaceSummary {
        val currentParser = parser()
        val project = editorState.project
        val projectPath = editorState.projectFilePath.takeIf(String::isNotBlank)
        val selected = selectedRoom()
        return SmeditWorkspaceSummary(
            projectLoaded = currentParser != null,
            projectName = projectPath?.let { File(it).nameWithoutExtension }
                ?: project.romPath.takeIf(String::isNotBlank)?.let { File(it).nameWithoutExtension },
            romFileName = project.romPath.takeIf(String::isNotBlank)?.let { File(it).name },
            projectFilePath = projectPath,
            readOnly = currentParser?.roomCatalog?.readOnly == true,
            dirty = editorState.dirty,
            roomCount = roomInfos().size,
            editedRoomCount = (
                project.rooms.values.filter { it.hasEdits }.map { it.roomId } +
                    project.newRooms.map { it.previewRoomId }
                ).distinct().size,
            patchCount = project.patches.size,
            enabledPatchCount = project.patches.count { it.enabled },
            asmWorkspaceEnabled = project.asmWorkspace.enabled,
            asmSourceAvailable = asmWorkspaceState.workspace != null,
            asmUnsavedFileCount = asmWorkspaceState.unsavedSourceFileIds.size,
            selectedRoomId = selected?.id,
            selectedRoomName = selected?.name,
        )
    }

    override fun rooms(): List<SmeditRoomSummary> {
        val currentParser = parser()
        return roomInfos().map { info ->
            val roomId = info.getRoomIdAsInt()
            val room = currentParser?.readRoomHeader(roomId)?.let { effectiveRoom(it, currentParser) }
            val edits = editorState.project.rooms[editorState.project.roomKey(roomId)]
            SmeditRoomSummary(
                id = formatRoomId(roomId),
                name = info.name,
                handle = info.handle,
                area = room?.areaName,
                widthScreens = room?.width,
                heightScreens = room?.height,
                musicData = room?.musicData,
                musicTrack = room?.musicTrack,
                edited = edits?.hasEdits == true,
                projectOwned = editorState.project.newRooms.any { it.previewRoomId == roomId },
            )
        }
    }

    override fun room(roomId: Int): SmeditRoomDetails? {
        val currentParser = parser() ?: return null
        val info = roomInfos().firstOrNull { it.getRoomIdAsInt() == roomId }
        val room = currentParser.readRoomHeader(roomId)?.let { effectiveRoom(it, currentParser) } ?: return null
        val edits = editorState.project.rooms[editorState.project.roomKey(roomId)]
        val sourceStateMusic = runCatching {
            currentParser.parseRoomStatesWithData(roomId).mapIndexed { index, state ->
                SmeditRoomMusicState(
                    index = index,
                    condition = state.stateInfo.conditionName,
                    musicData = state.musicData,
                    musicTrack = state.musicTrack,
                )
            }
        }.getOrDefault(emptyList())
        val hasStateMusicEdits = edits?.effectiveStates.orEmpty().any { state ->
            state.effectiveStateDataChange?.let { it.musicData != null || it.musicTrack != null } == true
        }
        val states = effectiveRoomStates(roomId, currentParser, edits)
        return SmeditRoomDetails(
            id = formatRoomId(roomId),
            name = info?.name ?: room.name,
            handle = info?.handle ?: room.handle,
            comment = info?.comment,
            area = room.areaName,
            areaIndex = room.area,
            mapX = room.mapX,
            mapY = room.mapY,
            widthScreens = room.width,
            heightScreens = room.height,
            tileset = room.tileset,
            musicData = room.musicData,
            musicTrack = room.musicTrack,
            stateCount = maxOf(sourceStateMusic.size, edits?.effectiveStates?.size ?: 0),
            projectStateEditCount = edits?.effectiveStates?.count { it.hasEdits } ?: 0,
            edited = edits?.hasEdits == true,
            projectOwned = editorState.project.newRooms.any { it.previewRoomId == roomId },
            sourceStateMusic = sourceStateMusic,
            hasDistinctSourceMusic = sourceStateMusic.map { it.musicData to it.musicTrack }.distinct().size > 1,
            hasStateMusicEdits = hasStateMusicEdits,
            states = states,
        )
    }

    private fun effectiveRoomStates(
        roomId: Int,
        currentParser: RomParser,
        liveEdits: RoomEdits?,
    ): List<SmeditRoomStateDetails> {
        val inspection = currentParser.inspectRoomStates(roomId)
        val room = snapshotRoomForResize(liveEdits ?: RoomEdits(roomId))
        if (room.states.isEmpty()) {
            if (!inspection.isComplete) return inspection.states.mapIndexed { index, inspected ->
                SmeditRoomStateDetails(
                    id = "source-state-${index + 1}",
                    index = index,
                    sourceIndex = index,
                    condition = inspected.condition.shortSummary(inspection.area ?: 0),
                    conditionKind = inspected.condition.kind.name,
                    defaultState = inspected.condition.isDefault,
                    readable = false,
                    projectEdited = false,
                    projectFxEdited = false,
                    fxSourceManaged = false,
                    fxReadable = false,
                )
            }
            room.ensureStateManifest(currentParser)
        }
        val sourceManaged = room.asmFxSourceVersion != 0
        return room.effectiveStates.mapIndexed { index, state ->
            val sourceIndex = state.baseSourceStateIndex()
            val inspected = sourceIndex?.let(inspection.states::getOrNull)
            val sourceFields = inspected?.stateDataPcOffset?.let(currentParser::readStateData).orEmpty()
            val commonChange = room.effectiveStateDataChange
            val stateChange = state.effectiveStateDataChange
            fun effectiveField(name: String, common: Int?, specific: Int?): Int? =
                specific ?: common ?: sourceFields[name]
            val managedFxResource = if (sourceManaged) {
                editorState.asmPlmSourceBridge?.fx?.view(roomId, state.id)?.resource
            } else null
            val nativeEntries = if (sourceManaged) {
                managedFxResource?.entries.orEmpty()
            } else {
                sourceFields["fxPtr"]?.let(currentParser::parseFxEntries).orEmpty()
            }
            var effectiveEntries = nativeEntries
            val synthesizedDefault = effectiveEntries.none(RomParser.FxEntry::isDefault)
            if (synthesizedDefault) {
                effectiveEntries = effectiveEntries + emptyDefaultFxEntry()
            }
            if (!sourceManaged) {
                effectiveEntries = effectiveEntries.map { entry ->
                    if (entry.isDefault) {
                        entry.withFxChange(room.fxChange).withFxChange(state.fxChange)
                    } else {
                        val key = entry.doorSelect.toString(16).uppercase().padStart(4, '0')
                        entry.withFxChange(state.doorFxChanges[key])
                    }
                }
            }
            SmeditRoomStateDetails(
                id = state.id,
                index = index,
                sourceIndex = sourceIndex,
                condition = state.effectiveCondition.displaySummary(inspection.area ?: 0),
                conditionKind = state.effectiveCondition.kind.name,
                defaultState = state.effectiveCondition.kind.name == "DEFAULT",
                readable = sourceFields.isNotEmpty() || state.templateSourceStateIndex != null,
                projectEdited = state.hasEdits,
                projectFxEdited = state.fxChange != null || state.doorFxChanges.isNotEmpty() || room.fxChange != null,
                fxSourceManaged = sourceManaged,
                fxReadable = !sourceManaged || managedFxResource != null,
                runtimeIndexedFx = sourceFields["fxPtr"] == 0xA0A4,
                tileset = effectiveField("tileset", commonChange?.tileset, stateChange?.tileset),
                musicData = effectiveField("musicData", commonChange?.musicData, stateChange?.musicData),
                musicTrack = effectiveField("musicTrack", commonChange?.musicTrack, stateChange?.musicTrack),
                bgScrolling = effectiveField("bgScrolling", commonChange?.bgScrolling, stateChange?.bgScrolling),
                levelDataPointer = sourceFields["levelDataPtr"],
                fxPointer = sourceFields["fxPtr"],
                enemySetPointer = sourceFields["enemySetPtr"],
                enemyGraphicsPointer = sourceFields["enemyGfxPtr"],
                roomScrollsPointer = sourceFields["roomScrollsPtr"],
                xraySpecialCasingPointer = sourceFields["xraySpecialCasingPtr"],
                mainAsmPointer = sourceFields["mainAsmPtr"],
                plmSetPointer = sourceFields["plmSetPtr"],
                backgroundPointer = sourceFields["bgDataPtr"],
                setupAsmPointer = sourceFields["setupAsmPtr"],
                fxEntries = effectiveEntries.map { entry -> fxDetails(entry, synthesizedDefault && entry.isDefault) },
            )
        }
    }

    private fun fxDetails(entry: RomParser.FxEntry, synthesized: Boolean): SmeditFxEntryDetails = SmeditFxEntryDetails(
        selector = if (entry.isDefault) "default" else "door_specific",
        synthesized = synthesized,
        doorSelect = entry.doorSelect,
        doorSelectHex = formatWord(entry.doorSelect),
        sourceDoor = entry.sourceDoor?.caption,
        liquidSurfaceStart = entry.liquidSurfaceStart,
        liquidSurfaceNew = entry.liquidSurfaceNew,
        liquidSpeed = entry.liquidSpeed,
        liquidDelay = entry.liquidDelay,
        fxType = entry.fxType,
        fxTypeName = entry.fxTypeName,
        fxBitA = entry.fxBitA,
        fxBitB = entry.fxBitB,
        fxBitC = entry.fxBitC,
        liquidOptions = NativeLiquidOptions.decodedNames(entry.fxBitC),
        unknownLiquidOptionMask = entry.fxBitC and NativeLiquidOptions.knownMask.inv() and 0xFF,
        paletteFxBitflags = entry.paletteFxBitflags,
        tileAnimBitflags = entry.tileAnimBitflags,
        paletteBlend = entry.paletteBlend,
    )

    override fun patches(): List<SmeditPatchSummary> = editorState.project.patches.map { patch ->
        SmeditPatchSummary(
            id = patch.id,
            name = patch.name,
            description = patch.description,
            enabled = patch.enabled,
            configType = patch.configType,
            configurable = patch.configType != null || patch.configValue != null || !patch.configData.isNullOrEmpty(),
        )
    }

    override fun patch(identifier: String): SmeditPatchDetails? {
        val patch = resolveProjectPatch(editorState.project, identifier) ?: return null
        val schema = patch.configType?.let(SmeditPatchCatalog::configSchema)
            ?: SmeditPatchCatalog.configSchema(patch.id)
        val fields = schema?.fields.orEmpty().map { field ->
            val configured = patch.configData.orEmpty()[field.key]
                ?: patch.configValue.takeIf { field.key in CONFIG_VALUE_ALIASES }
            SmeditPatchConfigField(
                key = field.key,
                label = field.label,
                type = field.type,
                min = field.logicalMin ?: field.min,
                max = field.logicalMax ?: field.max,
                currentValue = configured ?: field.defaultValue,
                defaultValue = field.defaultValue,
                description = field.description,
                category = field.category,
                unit = field.unit,
                choices = field.choices.map { SmeditPatchConfigChoice(it.label, it.value) },
            )
        }
        return SmeditPatchDetails(
            id = patch.id,
            name = patch.name,
            description = patch.description,
            enabled = patch.enabled,
            configType = patch.configType,
            configValue = patch.configValue,
            configData = patch.configData.orEmpty(),
            configurable = schema != null || patch.configValue != null || !patch.configData.isNullOrEmpty(),
            configFieldCount = fields.size,
            configFields = fields,
        )
    }

    override fun palettes(): List<SmeditPaletteSummary> {
        val currentParser = parser() ?: return emptyList()
        val tilesets = (0 until TileGraphics.NUM_TILESETS).map { tilesetId ->
            val status = editorState.tilesetPaletteEditingStatus(tilesetId)
            SmeditPaletteSummary(
                id = "tileset:$tilesetId",
                name = SpritePalettes.tilesetName(tilesetId),
                kind = "tileset",
                category = "rooms",
                description = "Tileset $tilesetId palette (8 rows × 16 colors).",
                colorCount = 128,
                rowCount = 8,
                editable = status.editable && editorState.readTilesetPalette(tilesetId, currentParser) != null,
                sourceManaged = status.sourceManaged,
                modified = editorState.hasCustomPalette(tilesetId),
                affectedPaletteIds = status.affectedTilesetIds.filterNot { it == tilesetId }.map { "tileset:$it" },
                message = status.message,
            )
        }
        val sprites = SpritePalettes.REGIONS.map { region ->
            val related = when (region.id) {
                "boss_kraid" -> listOf("boss_kraid_health_bg", "boss_kraid_health_oam")
                "boss_kraid_health_bg" -> listOf("boss_kraid", "boss_kraid_health_oam")
                "boss_kraid_health_oam" -> listOf("boss_kraid", "boss_kraid_health_bg")
                "boss_phantoon" -> listOf("boss_phantoon_health")
                "boss_phantoon_health" -> listOf("boss_phantoon")
                else -> emptyList()
            }.map { "sprite:$it" }
            val usage = when (region.id) {
                "boss_kraid" -> "Initial/base Kraid rows. The runtime health handler replaces the assembled body with the health-stage BG and OAM tables."
                "boss_kraid_bg" -> "Kraid room background environment colors; this is not Kraid's body palette."
                "boss_kraid_health_bg" -> "Kraid BG2 body runtime stages. Row 8 (indices 128–143) drives the default full-health assembled preview."
                "boss_kraid_health_oam" -> "Kraid arm, foot, lint, and nail runtime stages. Row 8 (indices 128–143) drives the default full-health assembled preview."
                "boss_phantoon" -> "Phantoon's initial/base rows. The fight's health handler replaces these with the separate runtime health-stage table."
                "boss_phantoon_health" -> "Phantoon's eight runtime health stages. Row 7 (indices 112–127) drives the default full-health assembled preview."
                else -> null
            }
            SmeditPaletteSummary(
                id = "sprite:${region.id}",
                name = region.name,
                kind = "sprite",
                category = region.category,
                description = region.description,
                colorCount = region.colorCount,
                rowCount = region.rowCount,
                editable = editorState.readSpritePalette(region.id, currentParser) != null,
                sourceManaged = false,
                modified = editorState.hasSpritePaletteOverride(region.id),
                relatedPaletteIds = related,
                usage = usage,
            )
        }
        return tilesets + sprites
    }

    override fun palette(identifier: String): SmeditPaletteDetails? {
        val currentParser = parser() ?: return null
        val raw = identifier.trim()
        val summaries = palettes()
        val summary = summaries.singleOrNull { it.id.equals(raw, ignoreCase = true) }
            ?: summaries.singleOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: return null
        val colors = when (summary.kind) {
            "tileset" -> summary.id.substringAfter(':').toIntOrNull()
                ?.let { editorState.readTilesetPalette(it, currentParser) }
            "sprite" -> editorState.readSpritePalette(summary.id.substringAfter(':'), currentParser)
            else -> null
        } ?: return null
        return SmeditPaletteDetails(
            id = summary.id,
            name = summary.name,
            kind = summary.kind,
            category = summary.category,
            description = summary.description,
            colorCount = summary.colorCount,
            rowCount = summary.rowCount,
            editable = summary.editable,
            sourceManaged = summary.sourceManaged,
            modified = summary.modified,
            affectedPaletteIds = summary.affectedPaletteIds,
            relatedPaletteIds = summary.relatedPaletteIds,
            usage = summary.usage,
            message = summary.message,
            colorsBgr555 = colors.toList(),
            colorsRgbHex = colors.map(::bgr555ToRgbHex),
        )
    }

    override fun itemTypes(): List<SmeditItemType> = buildList {
        RomParser.ITEM_DEFS.forEach { item ->
            add(SmeditItemType(item.name, "Visible", item.visibleId, formatWord(item.visibleId)))
            add(SmeditItemType(item.name, "Chozo", item.chozoId, formatWord(item.chozoId)))
            add(SmeditItemType(item.name, "Hidden", item.hiddenId, formatWord(item.hiddenId)))
        }
        editorState.enabledCustomItemPlms().forEach { item ->
            add(SmeditItemType(item.item.name, item.variant, item.plmId, formatWord(item.plmId), custom = true))
        }
    }

    override fun roomItems(roomId: Int): SmeditRoomItemsDetails? {
        val currentParser = parser() ?: return null
        val header = currentParser.readRoomHeader(roomId)?.let { effectiveRoom(it, currentParser) } ?: return null
        val live = editorState.project.rooms[editorState.project.roomKey(roomId)]
        val room = snapshotRoomForResize(live ?: RoomEdits(roomId))
        if (room.states.isEmpty()) {
            if (!currentParser.inspectRoomStates(roomId).isComplete) return null
            room.ensureStateManifest(currentParser)
        }
        val runtimeStates = currentParser.parseRoomStatesWithData(roomId)
        val sourceManaged = room.asmPlmSourceVersion != 0
        val states = room.effectiveStates.mapIndexedNotNull { index, state ->
            val entries = if (sourceManaged) {
                val view = editorState.asmPlmSourceBridge?.managedView(roomId, state.id) ?: return@mapIndexedNotNull null
                if (view.problem != null) return@mapIndexedNotNull null
                view.document?.entries.orEmpty()
            } else {
                val sourceIndex = state.baseSourceStateIndex() ?: return@mapIndexedNotNull null
                val base = runtimeStates.getOrNull(sourceIndex)?.plmSetPtr?.let(currentParser::parsePlmSet).orEmpty()
                applyPlmChanges(applyPlmChanges(base, room.plmChanges), state.plmChanges)
            }
            SmeditRoomItemState(
                id = state.id,
                index = index,
                condition = state.effectiveCondition.displaySummary(header.area),
                items = entries.filter { editorState.isEditorItemPlm(it.id) }.map { entry ->
                    SmeditPlacedItem(
                        name = editorState.customItemNameForPlm(entry.id)
                            ?: RomParser.itemNameForPlm(entry.id)
                            ?: RomParser.plmDisplayName(entry.id, entry.param),
                        plmId = entry.id,
                        plmIdHex = formatWord(entry.id),
                        x = entry.x,
                        y = entry.y,
                        parameter = entry.param,
                        parameterHex = formatWord(entry.param),
                    )
                },
            )
        }
        if (states.size != room.effectiveStates.size) return null
        return SmeditRoomItemsDetails(
            roomId = formatRoomId(roomId),
            roomName = roomInfos().firstOrNull { it.getRoomIdAsInt() == roomId }?.name ?: header.name,
            widthBlocks = header.width * 16,
            heightBlocks = header.height * 16,
            sourceManaged = sourceManaged,
            states = states,
        )
    }

    override fun editorSettings(): SmeditEditorSettingsSummary {
        val settings = AppConfig.load()
        val selectedProvider = AiProviderId.fromConfigId(settings.aiSelectedProvider)
        val configuredSource = selectedProvider?.let { provider ->
            AiCredentialSource.fromConfigId(settings.aiCredentialSources[provider.configId])
                ?: if (credentialStore.hasCredential(provider, AiCredentialSource.ENVIRONMENT)) {
                    AiCredentialSource.ENVIRONMENT
                } else AiCredentialSource.SMEDIT_STORE
        }
        val configuredProvider = selectedProvider?.takeIf { provider ->
            configuredSource != null && credentialStore.hasCredential(provider, configuredSource)
        }
        return SmeditEditorSettingsSummary(
            theme = settings.theme,
            fontSize = settings.fontSize,
            emulatorBackend = settings.emulatorBackend,
            emulatorFollowLiveRoom = settings.emulatorFollowLiveRoom,
            roomSidebarTab = settings.roomSidebarTab,
            roomItemNamesVisible = settings.roomEditorShowItemNames,
            roomMetaNamesVisible = settings.roomEditorShowMetaNames,
            roomEnemyNamesVisible = settings.roomEditorShowEnemyNames,
            aiSelectedProvider = configuredProvider?.configId,
            aiSelectedModel = configuredProvider?.let { settings.aiSelectedModels[it.configId] },
        )
    }

    override fun searchDocs(query: String, limit: Int): List<SmeditDocumentationMatch> {
        val root = documentationRoot() ?: return emptyList()
        val phrase = query.trim().lowercase()
        val tokens = phrase.split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .distinct()
        if (phrase.isEmpty()) return emptyList()
        data class ScoredMatch(val score: Int, val match: SmeditDocumentationMatch)

        return root.walkTopDown()
            .filter { file ->
                file.isFile && file.length() <= MAX_DOCUMENTATION_BYTES &&
                    file.extension.lowercase() in DOCUMENTATION_EXTENSIONS
            }
            .flatMap { file ->
                val relativePath = file.relativeTo(root).invariantSeparatorsPath
                val pathText = relativePath.lowercase()
                runCatching { file.readLines() }.getOrDefault(emptyList()).asSequence().mapIndexedNotNull { index, line ->
                    val text = line.trim()
                    if (text.isEmpty()) return@mapIndexedNotNull null
                    val lower = text.lowercase()
                    val tokenHits = tokens.count { it in lower }
                    val pathHits = tokens.count { it in pathText }
                    val exactPhrase = phrase.length >= 3 && phrase in lower
                    if (!exactPhrase && tokenHits == 0 && pathHits == 0) return@mapIndexedNotNull null
                    ScoredMatch(
                        score = (if (exactPhrase) 1000 else 0) + tokenHits * 20 + pathHits * 3,
                        match = SmeditDocumentationMatch(relativePath, index + 1, text.take(MAX_DOCUMENTATION_SNIPPET)),
                    )
                }
            }
            .sortedWith(compareByDescending<ScoredMatch> { it.score }.thenBy { it.match.path }.thenBy { it.match.line })
            .take(limit.coerceIn(1, 100))
            .map(ScoredMatch::match)
            .toList()
    }

    override fun readDoc(path: String, startLine: Int, maxLines: Int): SmeditDocumentationExcerpt? {
        val root = documentationRoot() ?: return null
        val normalized = path.trim().replace('\\', '/')
        if (normalized.startsWith('/') || normalized.split('/').any { it == ".." }) return null
        val file = runCatching { File(root, normalized).canonicalFile }.getOrNull() ?: return null
        val rootPath = root.canonicalFile.path + File.separator
        if (!file.path.startsWith(rootPath) || !file.isFile || file.length() > MAX_DOCUMENTATION_BYTES ||
            file.extension.lowercase() !in DOCUMENTATION_EXTENSIONS
        ) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val from = (startLine - 1).coerceAtLeast(0)
        if (from >= lines.size) return null
        val selected = lines.subList(from, minOf(lines.size, from + maxLines.coerceIn(1, 200)))
        return SmeditDocumentationExcerpt(
            path = file.relativeTo(root).invariantSeparatorsPath,
            startLine = from + 1,
            endLine = from + selected.size,
            text = selected.joinToString("\n").take(MAX_DOCUMENTATION_EXCERPT),
        )
    }

    override fun searchAsm(query: String, limit: Int): List<SmeditAsmSearchMatch> =
        findAsmSourceText(asmWorkspaceState.searchSourceTexts(), query, limit = limit).map { match ->
            SmeditAsmSearchMatch(
                fileId = match.fileId,
                displayName = match.displayName,
                line = match.lineIndex + 1,
                column = match.columnIndex + 1,
                text = match.line.trimEnd().take(500),
            )
        }

    private fun documentationRoot(): File? {
        val seeds = buildList {
            add(File(System.getProperty("user.dir")))
            editorState.projectFilePath.takeIf(String::isNotBlank)?.let { add(File(it).absoluteFile.parentFile) }
        }
        return seeds.asSequence().flatMap { seed ->
            generateSequence(runCatching { seed.canonicalFile }.getOrNull()) { it.parentFile }.take(12)
        }.map { File(it, "docs") }
            .firstOrNull { it.isDirectory && File(it, "CONTEXT.md").isFile }
    }

    private fun effectiveRoom(base: Room, currentParser: RomParser): Room {
        val edits = editorState.project.rooms[editorState.project.roomKey(base.roomId)] ?: return base
        val header = edits.effectiveHeaderChange
        val selectedState = if (editorState.currentRoomId == base.roomId) {
            edits.effectiveStates.firstOrNull { it.id == editorState.currentStateId }
        } else null
        val state = selectedState?.effectiveStateDataChange ?: edits.effectiveStateDataChange
        val runtime = if (editorState.currentRoomId == base.roomId) {
            runCatching { editorState.applyCurrentStateData(base, currentParser) }.getOrDefault(base)
        } else base
        return runtime.copy(
            index = header?.index ?: runtime.index,
            area = header?.area ?: runtime.area,
            mapX = header?.mapX ?: runtime.mapX,
            mapY = header?.mapY ?: runtime.mapY,
            width = header?.width ?: runtime.width,
            height = header?.height ?: runtime.height,
            upScroller = header?.upScroller ?: runtime.upScroller,
            downScroller = header?.downScroller ?: runtime.downScroller,
            creBitflag = header?.creBitflag ?: runtime.creBitflag,
            doorOut = header?.doorOut ?: runtime.doorOut,
            tileset = state?.tileset ?: runtime.tileset,
            musicData = state?.musicData ?: runtime.musicData,
            musicTrack = state?.musicTrack ?: runtime.musicTrack,
            bgScrolling = state?.bgScrolling ?: runtime.bgScrolling,
        )
    }

    private fun formatRoomId(roomId: Int): String = "0x" + roomId.toString(16).uppercase().padStart(4, '0')
    private fun formatWord(value: Int): String = "0x" + value.toString(16).uppercase().padStart(4, '0')

    private fun applyPlmChanges(
        source: List<RomParser.PlmEntry>,
        changes: List<com.supermetroid.editor.data.PlmChange>,
    ): List<RomParser.PlmEntry> = source.toMutableList().also { result ->
        changes.forEach { change ->
            when (change.action) {
                "add" -> result += RomParser.PlmEntry(change.plmId, change.x, change.y, change.param)
                "remove" -> result.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y }
            }
        }
    }

    private fun bgr555ToRgbHex(color: Int): String {
        val red = color and 0x1F
        val green = (color ushr 5) and 0x1F
        val blue = (color ushr 10) and 0x1F
        fun expand(value: Int) = (value * 255 + 15) / 31
        return "#%02X%02X%02X".format(expand(red), expand(green), expand(blue))
    }

    private companion object {
        val CONFIG_VALUE_ALIASES = setOf("seconds", "total_seconds", "totalSeconds")
        val DOCUMENTATION_EXTENSIONS = setOf("md", "txt")
        const val MAX_DOCUMENTATION_BYTES = 2L * 1024L * 1024L
        const val MAX_DOCUMENTATION_SNIPPET = 700
        const val MAX_DOCUMENTATION_EXCERPT = 30_000
    }
}

internal fun resolveProjectPatch(project: SmEditProject, identifier: String): SmPatch? {
    val raw = identifier.trim()
    if (raw.isEmpty()) return null
    val resolved = SmeditPatchCatalog.resolvePatchKey(raw)
    fun unique(predicate: (SmPatch) -> Boolean) = project.patches.filter(predicate).singleOrNull()
    unique { it.id.equals(raw, ignoreCase = true) }?.let { return it }
    unique { it.configType?.equals(raw, ignoreCase = true) == true }?.let { return it }
    unique { it.id.equals(resolved, ignoreCase = true) }?.let { return it }
    unique { it.name.equals(raw, ignoreCase = true) }?.let { return it }
    val normalized = raw.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    return unique { patch ->
        patch.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') == normalized
    }
}

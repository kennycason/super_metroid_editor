package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.asm.AsmPlmSourceBridge
import com.supermetroid.editor.asm.AsmPlmStamp
import com.supermetroid.editor.asm.AsmSourceTextChange
import com.supermetroid.editor.asm.PLM_SOURCE_FILE
import com.supermetroid.editor.asm.FX_SOURCE_FILE
import com.supermetroid.editor.asm.ENEMY_SOURCE_FILE
import com.supermetroid.editor.asm.DOOR_SOURCE_FILE
import com.supermetroid.editor.asm.RoomAsmSourceRequest
import com.supermetroid.editor.asm.RoomAsmSourceKind
import com.supermetroid.editor.asm.AsmPlmSourceCatalog
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.asm.publishSourceModelChanges
import com.supermetroid.editor.rom.withFxChange
import com.supermetroid.editor.rom.emptyDefaultFxEntry
import com.supermetroid.editor.data.CustomItemDef
import com.supermetroid.editor.data.CommunitySamusSpriteSource
import com.supermetroid.editor.data.DoorChange
import com.supermetroid.editor.data.EditOperation
import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.EnemyChange
import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.ItemStateScope
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.PatchSortOrder
import com.supermetroid.editor.data.withVanillaHexPatchPreconditions
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.SaveStationSpawnChange
import com.supermetroid.editor.data.ScrollChange
import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.rom.SourceSaveResource
import com.supermetroid.editor.rom.words
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.data.TILE_EDIT_LAYER_1
import com.supermetroid.editor.data.TILE_EDIT_LAYER_2
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.data.TilePattern
import com.supermetroid.editor.data.PatternCell
import com.supermetroid.editor.data.PatternLibrary
import com.supermetroid.editor.data.EnemyUpdate
import com.supermetroid.editor.data.MusicTrackEdit
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomStateEdits
import com.supermetroid.editor.data.ProjectRoomStateCondition
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.ProjectSourceDoorReference
import com.supermetroid.editor.data.ProjectDoorDefinition
import com.supermetroid.editor.data.ProjectEnemyEntry
import com.supermetroid.editor.data.ProjectEnemyGfxEntry
import com.supermetroid.editor.data.ProjectFxEntry
import com.supermetroid.editor.data.ProjectNewRoom
import com.supermetroid.editor.data.ProjectNewRoomHeader
import com.supermetroid.editor.data.ProjectNewRoomOrigin
import com.supermetroid.editor.data.ProjectNewRoomState
import com.supermetroid.editor.data.ProjectPlmEntry
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.procgen.BiomeGenerator
import com.supermetroid.editor.procgen.BiomeGenerationOptions
import com.supermetroid.editor.procgen.BiomeGenerationRect
import com.supermetroid.editor.procgen.BiomeRoomEligibility
import com.supermetroid.editor.procgen.BiomeRules
import com.supermetroid.editor.procgen.BiomeSafetyMask
import com.supermetroid.editor.procgen.BiomeTheme
import com.supermetroid.editor.procgen.LevelGrid
import com.supermetroid.editor.procgen.LearnedRoomProposal
import com.supermetroid.editor.procgen.LearnedRoomProposalProcessor
import com.supermetroid.editor.procgen.PreparedLearnedRoomCandidate
import com.supermetroid.editor.procgen.TilesetProfile
import com.supermetroid.editor.procgen.TilesetProfileCache
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.MinimapData
import com.supermetroid.editor.rom.MapStationData
import com.supermetroid.editor.rom.PaletteEffects
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomFreeSpaceAllocator
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.flattened
import com.supermetroid.editor.rom.RomRoomCatalog
import com.supermetroid.editor.rom.RomValidator
import com.supermetroid.editor.rom.ProjectNewRoomMaterializer
import com.supermetroid.editor.rom.RoomNamePauseMapPatch
import com.supermetroid.editor.rom.ensureStateManifest
import com.supermetroid.editor.rom.embeddedLayer2StartOffset
import com.supermetroid.editor.rom.displaySummary
import com.supermetroid.editor.rom.encodedSizeBytes
import com.supermetroid.editor.rom.baseSourceStateIndex
import com.supermetroid.editor.rom.stateEditsForId
import com.supermetroid.editor.rom.stateEditsForSourceIndex
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TextData
import com.supermetroid.editor.rom.TileGraphics
import com.supermetroid.editor.rom.toSigned16
import com.supermetroid.editor.rom.toUnsigned16
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

private val editorStateLog = KotlinLogging.logger {}

private fun editorLog(message: Any? = "") {
    val text = message?.toString() ?: ""
    when {
        text.startsWith("ERROR") || text.contains(" ERROR:") -> editorStateLog.error { text }
        text.startsWith("WARN") || text.contains(" WARN:") -> editorStateLog.warn { text }
        else -> editorStateLog.info { text }
    }
}

/**
 * Run synchronous build work on a worker thread while delivering ordered
 * progress messages on the caller's coroutine context. Compose Desktop uses
 * its own AWT dispatcher and does not necessarily install Dispatchers.Main.
 */
internal suspend fun <T> runBackgroundBuildWithProgress(
    onProgress: (String) -> Unit,
    work: ((String) -> Unit) -> T,
): T = coroutineScope {
    val updates = Channel<String>(Channel.UNLIMITED)
    val reporter = launch {
        for (message in updates) onProgress(message)
    }
    try {
        val result = withContext(Dispatchers.Default) {
            work { message -> updates.trySend(message).getOrThrow() }
        }
        updates.close()
        reporter.join()
        result
    } finally {
        updates.close()
    }
}

internal fun writePng(filePath: String, pixels: IntArray, w: Int, h: Int): Boolean {
    return try {
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, pixels, 0, w)
        javax.imageio.ImageIO.write(img, "png", java.io.File(filePath))
    } catch (_: Exception) { false }
}

data class FloatingMapSelection(val x: Int, val y: Int)

data class EffectiveSaveStationSpawn(
    val area: Int,
    val saveIndex: Int,
    val roomId: Int,
    val doorPtr: Int,
    val scrollX: Int,
    val scrollY: Int,
    val samusY: Int,
    val samusX: Int,
    val pcOffset: Int?,
    val source: String,
    val sourceDoor: ProjectSourceDoorReference? = null,
) {
    val samusXSigned: Int get() = samusX.toSigned16()
    val samusYSigned: Int get() = samusY.toSigned16()
}

data class RoomAreaReassignmentResult(
    val success: Boolean,
    val message: String,
    val sourceArea: Int,
    val targetArea: Int,
    val updatedDoorCount: Int = 0,
    val destinationMapX: Int? = null,
    val destinationMapY: Int? = null,
) {
    internal var sourceHistory: com.supermetroid.editor.asm.AsmRoomAreaMoveHistory? = null
}

data class NewRoomCreationRequest(
    val name: String,
    val cloneCurrentRoom: Boolean,
    val copyDoors: Boolean = true,
    val area: Int,
    val mapX: Int,
    val mapY: Int,
    val width: Int,
    val height: Int,
    val tileset: Int,
)

data class ProjectRoomDeletionReference(
    val kind: String,
    val description: String,
)

data class ProjectRoomDeletionPreview(
    val roomId: Int,
    val roomName: String,
    val removalSummary: List<String>,
    val blockers: List<ProjectRoomDeletionReference>,
) {
    val canDelete: Boolean get() = blockers.isEmpty()
}

internal data class AreaSaveMigrationPlan(
    val plmReplacements: List<Pair<RomParser.PlmEntry, Int>> = emptyList(),
    val spawnOverrides: List<SaveStationSpawnChange> = emptyList(),
    val migratedSlotCount: Int = 0,
    val reconstructedSlotCount: Int = 0,
    val sourceRecords: List<SourceSaveResource> = emptyList(),
)

private data class AreaSaveMigrationPlanning(
    val plan: AreaSaveMigrationPlan? = null,
    val error: String? = null,
)

private data class MapSelectionBounds(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
)

internal data class MapSelectionProperties(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val tileCount: Int,
    val metatile: Int,
    val blockType: Int?,
    val bts: Int?,
)

internal data class DoorCapPlacement(
    val anchorX: Int,
    val anchorY: Int,
    val direction: String,
    val doorwayX: Int,
    val doorwayY: Int,
    val snappedToDoorway: Boolean,
)

enum class LayoutEditScope {
    THIS_STATE,
    ALL_SHARING_STATES,
}

data class LayoutEditingContext(
    val roomId: Int,
    val stateId: String,
    val stateName: String,
    val resourceId: String,
    val allStateIds: List<String>,
    val allStateNames: List<String>,
    val sharingStateIds: List<String>,
    val sharingStateNames: List<String>,
    val scope: LayoutEditScope?,
    val hasResourceEdits: Boolean,
    val hasLegacyPrivateEdits: Boolean,
) {
    val sharingStateCount: Int get() = sharingStateIds.size
    val isShared: Boolean get() = sharingStateCount > 1
    val requiresScopeChoice: Boolean get() = sharingStateCount > 1 && scope == null
}

// ─── Editor State ───────────────────────────────────────────────

private data class ItemScopeSnapshot(
    val commonChanges: List<PlmChange>,
    val stateChanges: Map<String, List<PlmChange>>,
    val workingPlms: List<RomParser.PlmEntry>,
    val saveSpawns: List<SaveStationSpawnChange>,
    val commonScrollCommands: Map<String, List<ScrollCommand>>,
    val stateScrollCommands: Map<String, Map<String, List<ScrollCommand>>>,
)

private data class ItemScopeUndo(
    val before: ItemScopeSnapshot,
    val after: ItemScopeSnapshot,
)

class EditorState(
    private val patchFavoriteStore: PatchFavoriteStore = PatchFavoriteStore(),
) {
    internal var testMode = false

    var brush by mutableStateOf<TileBrush?>(null)
        internal set

    var activeTool by mutableStateOf(EditorTool.SELECT)
    var activeRoomLayer by mutableStateOf(RoomEditLayer.LAYER1)

    /** Map selection rectangle in block coordinates (inclusive). */
    var mapSelStart by mutableStateOf<Pair<Int, Int>?>(null)
    var mapSelEnd by mutableStateOf<Pair<Int, Int>?>(null)
    var floatingSelection by mutableStateOf<FloatingMapSelection?>(null)
        private set

    /** Selection rectangle in tileset: (startCol, startRow, endCol, endRow). */
    var tilesetSelStart by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var tilesetSelEnd by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** Last sampled palette row (set by sample tool). Palette editor reads this. */
    var sampledPaletteRow by mutableStateOf(-1)
    var sampledPaletteCol by mutableStateOf(-1)

    /** Incremented when palette colors change — triggers reactive re-read in pixel editor and tileset grid. */
    var paletteVersion by mutableStateOf(0)

    /** Incremented when the project-owned community Samus source changes. */
    var samusSourceVersion by mutableStateOf(0)
        private set

    val undoStack = mutableListOf<EditOperation>()
    val redoStack = mutableListOf<EditOperation>()
    var undoVersion by mutableStateOf(0)
        private set

    private val pendingEdits = mutableListOf<TileEdit>()
    private val pendingPositions = mutableSetOf<Long>()
    private val pendingPlmAdds = mutableListOf<PlmChange>()
    private val pendingPlmRemoves = mutableListOf<PlmChange>()
    private var pendingAllStateItemBefore: ItemScopeSnapshot? = null
    private var pendingPlmGeometry: ByteArray? = null
    private data class SourcePatternStroke(
        val owner: SmEditProject,
        val room: RoomEdits,
        val stateId: String,
        val levelId: String,
        val width: Int,
        val height: Int,
        val before: String,
        val plmsBefore: List<RomParser.PlmEntry>,
        val stamps: MutableList<AsmPlmStamp> = mutableListOf(),
    )
    private var pendingSourcePattern: SourcePatternStroke? = null
    private val itemScopeSnapshots = java.util.IdentityHashMap<EditOperation, ItemScopeUndo>()
    private val layoutEditScopes = mutableMapOf<String, LayoutEditScope>()
    private var pendingLayoutEditAction: (() -> Unit)? = null
    var pendingLayoutEditScope by mutableStateOf<LayoutEditingContext?>(null)
        private set

    var project by mutableStateOf(SmEditProject(romPath = ""))
        private set
    var projectFilePath: String = ""
        private set
    private var sourceRomData: ByteArray? = null
    private var sourceRoomCatalog: RomRoomCatalog? = null
    private var projectRoomWorkspaceParser: RomParser? = null
    internal fun matchesRoomSourceContext(base: RomParser): Boolean = currentRomParser === base ||
        currentRomParser != null && currentRomParser === projectRoomWorkspaceParser && sourceRomData?.contentEquals(base.getRomData()) == true

    var workingLevelData: ByteArray? = null
        private set
    var originalLevelData: ByteArray? = null
        private set
    var workingBlocksWide: Int = 0
        private set
    var workingBlocksTall: Int = 0
        private set
    var currentRoomId: Int = 0
        private set
    var currentTilesetId by mutableStateOf(0)
        private set
    /** The loaded room's tileset as stored in the ROM (before project overrides). */
    private var romTilesetId: Int = 0
    private var currentBgScrolling: Int = 0
    private var currentArea: Int = 0
    private var currentIncomingDoorPtrs: List<Int> = emptyList()
    private var currentAreaRomSaveEntries: Map<Int, RomParser.Companion.SaveEntry> = emptyMap()
    private var currentAreaSaveEntryCount: Int = 0
    private var vanillaSaveIndicesByArea: Map<Int, Set<Int>> = emptyMap()
    /** Currently active room state index (0 = first conditional, last = default). */
    var currentStateIndex: Int = -1
        private set
    /** Stable project state identity; new/reordered states do not have a unique ROM source index. */
    var currentStateId by mutableStateOf<String?>(null)
        private set
    /** Placement preference only. Existing objects keep their observed membership. */
    var itemEditScope by mutableStateOf(ItemStateScope.ALL_STATES)
        private set
    private val plmEditScopeOverrides = mutableStateMapOf<String, ItemStateScope>()
    private var currentRomParser: RomParser? = null
    internal val roomSourceParser: RomParser? get() = currentRomParser
    private var dirtyValue by mutableStateOf(false)
    private var projectDirtyRevision = 0L
    var dirty: Boolean
        get() = dirtyValue
        private set(value) {
            if (value) projectDirtyRevision++
            dirtyValue = value
        }
    var projectSaveInProgress by mutableStateOf(false)
        private set
    private val projectSaveMutex = Mutex()

    /** Monotonic counter incremented each time any room is edited. */
    private var _editCounter = 0L

    /** Maps roomId → last-edit counter value. Higher = more recently edited. */
    private val _roomEditOrder = mutableMapOf<Int, Long>()
    val roomEditOrder: Map<Int, Long> get() = _roomEditOrder

    fun markDirty() {
        dirty = true
        _roomEditOrder[currentRoomId] = ++_editCounter
    }

    private var asmModeRevision by mutableStateOf(0)
    internal val asmNavigationEnabled: Boolean get() {
        asmModeRevision // Nested project flags are not Compose state; mode changes must invalidate links.
        return projectAsmNavigationEnabled(project)
    }
    /** Structured room resources use the same source workflow for native and project rooms. */
    private val usesRoomAsmAuthoring: Boolean get() = project.asmWorkspace.enabled && project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE

    /**
     * Persist the opt-in ASM workspace contract without treating it as a room edit.
     * Enabling the editable workspace also makes it the build source: otherwise a
     * user can successfully edit ASM while exports silently continue from the ROM.
     */
    fun enableProjectAsmWorkspace(sourceRevision: String) {
        if (
            project.asmWorkspace.enabled &&
            project.asmWorkspace.sourceRevision == sourceRevision &&
            project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE
        ) return
        project.asmWorkspace.enabled = true
        project.asmWorkspace.sourceRevision = sourceRevision
        project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        asmModeRevision++
        dirty = true
    }

    fun setProjectRomBuildMode(mode: ProjectRomBuildMode) {
        if (mode == ProjectRomBuildMode.PATCHED_ROM && (project.asmWorkspace.plmSourceBindings.isNotEmpty() ||
                project.rooms.values.any { it.asmDoorSourceVersion != 0 || it.asmStateSelectorsSourceVersion != 0 || it.asmRoomPropertiesSourceVersion != 0 || it.asmLayoutSourceVersion != 0 || it.asmPlmSourceVersion != 0 || it.asmInitialScrollSourceVersion != 0 || it.asmFxSourceVersion != 0 || it.asmEnemySourceVersion != 0 })) {
            postStatus("Source-authored room data requires ASM mode.")
            return
        }
        if (project.asmWorkspace.buildMode == mode) return
        project.asmWorkspace.buildMode = mode
        asmModeRevision++
        dirty = true
    }

    fun setCommunitySamusSource(source: CommunitySamusSpriteSource): Boolean {
        if (project.customGfx.samusCommunitySource == source) return false
        project.customGfx.samusCommunitySource = source
        dirty = true
        samusSourceVersion++
        return true
    }

    fun restoreBaseRomSamus(): Boolean {
        if (project.customGfx.samusCommunitySource == null) return false
        project.customGfx.samusCommunitySource = null
        dirty = true
        samusSourceVersion++
        return true
    }

    internal fun notifyProjectMutation(romParser: RomParser? = null) {
        dirty = true
        if (romParser != null && currentRoomId != 0) {
            val baseRoom = romParser.readRoomHeader(currentRoomId)
            if (baseRoom != null) {
                currentArea = project.rooms[project.roomKey(currentRoomId)]?.effectiveHeaderChange?.area ?: baseRoom.area
                refreshVanillaSaveIndices(romParser)
                currentAreaSaveEntryCount = romParser.saveEntryCount(currentArea)
                currentAreaRomSaveEntries = (0 until currentAreaSaveEntryCount.coerceAtMost(RomParser.SAVE_STATION_SLOT_COUNT))
                    .mapNotNull { index -> romParser.readSaveEntry(currentArea, index)?.let { index to it } }
                    .toMap()

                val effectiveDoors = effectiveDoorsForRoom(currentRoomId, romParser)
                _workingDoors.clear()
                _workingDoors.addAll(effectiveDoors)
                _workingPlms.clear()
                _workingPlms.addAll(effectiveCurrentStatePlms(romParser))
            }
        }
        editVersion++
    }

    // ── Sprite editor state objects ───────────────────────────────────────

    var spriteVersion by mutableStateOf(0)
        private set

    val nativeSprites = NativeSpriteEditorState(
        customGfx = { project.customGfx },
        onDirty = { dirty = true; spriteVersion++; editVersion++; paletteVersion++ },
    )

    val enemySprite = EnemySpriteEditorState(
        customGfx = { project.customGfx },
        onDirty = { dirty = true; spriteVersion++; editVersion++; paletteVersion++ },
    )

    val phantoonSprite = PhantoonSpriteEditorState(
        customGfx = { project.customGfx },
        applyCustomGfx = ::applyCustomGfxToTileGraphics,
        onDirty = { dirty = true },
    )

    val draygonSprite = DraygonSpriteEditorState(
        loadEnemyTileData = { parser, speciesId -> enemySprite.loadEnemyTileData(parser, speciesId) },
        applyCustomGfx = ::applyCustomGfxToTileGraphics,
    )

    val crocomireSprite = CrocomireSpriteEditorState(
        loadEnemyTileData = { parser, speciesId -> enemySprite.loadEnemyTileData(parser, speciesId) },
        loadEnemyPalette = { parser, speciesId -> enemySprite.loadEnemyPalette(parser, speciesId) },
        applyCustomGfx = ::applyCustomGfxToTileGraphics,
    )

    val sporeSpawnSprite = SporeSpawnSpriteEditorState(
        loadEnemyTileData = { parser, speciesId -> enemySprite.loadEnemyTileData(parser, speciesId) },
    )

    val metroidSprite = MetroidSpriteEditorState(
        loadEnemyTileData = { parser, speciesId -> enemySprite.loadEnemyTileData(parser, speciesId) },
    )

    val botwoonSprite = BotwoonSpriteEditorState(
        loadEnemyTileData = { parser, speciesId -> enemySprite.loadEnemyTileData(parser, speciesId) },
    )

    val torizoSprite = TorizoSpriteEditorState(
        loadEnemyTileData = { parser, speciesId -> enemySprite.loadEnemyTileData(parser, speciesId) },
    )

    val motherBrainSprite = MotherBrainSpriteEditorState(
        loadEnemyTileData = { parser, speciesId -> enemySprite.loadEnemyTileData(parser, speciesId) },
    )

    val ridleySprite = RidleySpriteEditorState(
        loadEnemyTileData = { parser, speciesId -> enemySprite.loadEnemyTileData(parser, speciesId) },
    )

    val kraidSprite = KraidSpriteEditorState(
        customGfx = { project.customGfx },
        loadEnemyTileData = { parser, speciesId -> enemySprite.loadEnemyTileData(parser, speciesId) },
        applyCustomGfx = ::applyCustomGfxToTileGraphics,
        onDirty = { dirty = true },
    )

    /** Incremented on every edit to trigger map re-render + update room edit order. */
    private val _editVersionState = mutableStateOf(0)
    var editVersion: Int
        get() = _editVersionState.value
        private set(value) {
            _editVersionState.value = value
            if (currentRoomId != 0) {
                _roomEditOrder[currentRoomId] = ++_editCounter
            }
        }

    /** Incremented when a new ROM is loaded to force full UI refresh. */
    var romVersion by mutableStateOf(0)
        private set

    /** Mouse position on the map in block coordinates (for cursor preview). -1 = off map. */
    var hoverBlockX by mutableStateOf(-1)
    var hoverBlockY by mutableStateOf(-1)

    /** Target block coordinates to scroll to (set by item click, consumed by MapCanvas). */
    var scrollTargetBlockX by mutableStateOf(-1)
    var scrollTargetBlockY by mutableStateOf(-1)

    /** Tile info at hover position (metatile index, block type). */
    var hoverTileWord by mutableStateOf(0)
        private set

    /** Transient status message shown in the bottom bar (auto-clears). */
    var statusMessage by mutableStateOf("")
        private set
    var statusMessageTimestamp by mutableStateOf(0L)
        private set
    val statusIsError: Boolean get() = statusMessage.startsWith("Export failed", ignoreCase = true) ||
        statusMessage.startsWith("Export blocked", ignoreCase = true) ||
        statusMessage.startsWith("Save failed", ignoreCase = true) ||
        statusMessage.startsWith("Enemy edit blocked", ignoreCase = true) ||
        statusMessage.startsWith("Beam edit blocked", ignoreCase = true) ||
        statusMessage.startsWith("Boss stats edit blocked", ignoreCase = true) ||
        statusMessage.startsWith("Environmental edit blocked", ignoreCase = true) ||
        statusMessage.startsWith("Patch edit blocked", ignoreCase = true) ||
        statusMessage.startsWith("Patch toggle blocked", ignoreCase = true) ||
        statusMessage.startsWith("Patch synchronization blocked", ignoreCase = true) ||
        statusMessage.startsWith("Source edit blocked", ignoreCase = true) ||
        statusMessage.startsWith("Object edit blocked", ignoreCase = true)

    /** Long-running ROM build state stays visible instead of expiring like a transient status. */
    var romBuildInProgress by mutableStateOf(false)
        private set
    var romBuildStatus by mutableStateOf("")
        private set

    fun postStatus(msg: String) {
        statusMessage = msg
        statusMessageTimestamp = System.currentTimeMillis()
    }

    private fun reportRomBuildStatus(message: String, onProgress: (String) -> Unit) {
        romBuildStatus = message
        postStatus(message)
        onProgress(message)
    }

    /** TileGraphics for rendering brush preview. Set when room loads. */
    var tileGraphics: TileGraphics? = null
        private set

    /** Metatile index → most common block type, learned from room data + user edits. */
    var metatileBlockTypePresets: Map<Int, Int> = emptyMap()
        private set

    /** Working PLMs for the current room (includes edits). */
    private val _workingPlms = mutableListOf<RomParser.PlmEntry>()
    val workingPlms: List<RomParser.PlmEntry> get() = _workingPlms
    internal var asmPlmSourceBridge by mutableStateOf<AsmPlmSourceBridge?>(null)
    private data class ManagedSourceUndo(val fileId: String, val before: String, val after: String,
                                         val change: AsmSourceTextChange? = null)
    private val managedPlmUndo = java.util.IdentityHashMap<EditOperation, ManagedSourceUndo>()
    private var linkedObjectPreviewRevision: Triple<Int, String?, String>? = null
    internal var plmSourceMigrationProblem: String? = null
        private set
    internal var objectPreviewLoading by mutableStateOf(false)
    internal var objectPreviewProblem by mutableStateOf<String?>(null)
        private set
    internal var initialScrollPreviewProblem by mutableStateOf<String?>(null)
        private set
    internal var fxPreviewProblem by mutableStateOf<String?>(null)
        private set
    private var linkedFxPreview: Pair<String, List<RomParser.FxEntry>>? = null

    internal fun hasManagedFx(): Boolean = (project.rooms[project.roomKey(currentRoomId)]?.asmFxSourceVersion ?: 0) != 0

    internal fun ensureFxSource(roomName: String): Boolean = runCatching {
        requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }.fx.migrate(currentRoomId, roomName)
        currentStateId = currentPlmStateId()
        refreshFx()
        true
    }.getOrElse { postStatus("FX edit blocked: ${it.message}"); false }

    /** One preview route for native overrides and authored FX. No compilation or IDE-index dependency. */
    internal fun effectiveFxEntries(roomId: Int, parser: RomParser, stateId: String? = if (roomId == currentRoomId) currentPlmStateId() else null): List<RomParser.FxEntry> {
        val room = project.rooms[project.roomKey(roomId)]
        val index = if (roomId == currentRoomId) currentStateIndex else parser.preferredPreviewStateIndex(roomId)
        val state = room?.stateEditsForId(stateId) ?: room?.stateEditsForSourceIndex(index) ?: room?.states?.lastOrNull()
        if ((room?.asmFxSourceVersion ?: 0) != 0) return state?.let { asmPlmSourceBridge?.fx?.view(roomId, it.id)?.resource?.entries }.orEmpty()
        val native = parser.parseRoomStatesWithData(roomId).getOrNull(state?.baseSourceStateIndex() ?: index)
        val pointer = native?.fxPtr ?: parser.readRoomHeader(roomId)?.fxPtr ?: return emptyList()
        var entries = parser.parseFxEntries(pointer)
        val change = state?.fxChange ?: room?.fxChange
        if (entries.none { it.isDefault } && change != null) entries = entries + emptyDefaultFxEntry()
        return entries.map { entry ->
            if (entry.isDefault) entry.withFxChange(room?.fxChange).withFxChange(state?.fxChange)
            else entry.withFxChange(state?.doorFxChanges?.entries?.firstOrNull { it.key.toIntOrNull(16) == entry.doorSelect }?.value)
        }
    }

    private fun refreshFx() {
        if (!hasManagedFx()) { fxPreviewProblem = null; linkedFxPreview = null; return }
        val bridge = asmPlmSourceBridge?.fx
        val stateId = currentPlmStateId()
        if (bridge == null || stateId == null) { fxPreviewProblem = "FX source is loading…"; return }
        val view = bridge.view(currentRoomId, stateId)
        fxPreviewProblem = view.problem
        val preview = "$currentRoomId:$stateId" to view.resource?.entries.orEmpty()
        if (preview != linkedFxPreview) { linkedFxPreview = preview; _editVersionState.value++ }
    }

    internal var enemyPreviewProblem by mutableStateOf<String?>(null)
        private set
    internal var workingEnemyGraphics by mutableStateOf<List<RomParser.EnemyGfxEntry>>(emptyList())
        private set
    internal fun hasManagedEnemies() = (project.rooms[project.roomKey(currentRoomId)]?.asmEnemySourceVersion ?: 0) != 0
    internal fun hasManagedLayouts() = (project.rooms[project.roomKey(currentRoomId)]?.asmLayoutSourceVersion ?: 0) != 0
    internal fun hasManagedRoomProperties(roomId: Int = currentRoomId) = (project.rooms[project.roomKey(roomId)]?.asmRoomPropertiesSourceVersion ?: 0) != 0
    internal fun hasManagedSelectors(roomId: Int = currentRoomId) = (project.rooms[project.roomKey(roomId)]?.asmStateSelectorsSourceVersion ?: 0) != 0
    internal fun hasManagedDoors(roomId: Int = currentRoomId) = (project.rooms[project.roomKey(roomId)]?.asmDoorSourceVersion ?: 0) != 0
    internal fun hasStructuralDoors(roomId: Int = currentRoomId) = hasManagedDoors(roomId) && asmPlmSourceBridge?.doors?.view(roomId)?.resource?.structural == true
    internal var doorPreviewProblem by mutableStateOf<String?>(null)
        private set
    internal var doorArrivalBindings by mutableStateOf<Map<Int, com.supermetroid.editor.rom.SourceDoorArrival>>(emptyMap())
        private set
    internal var doorSourceIdentities by mutableStateOf<Map<Int, Int>>(emptyMap())
        private set
    private val armedSharedDoors = mutableStateMapOf<Int, Boolean>()
    internal fun sharedDoorRooms(pointer: Int): List<String> = asmPlmSourceBridge?.doors?.sharedConsumers(pointer).orEmpty()
        .map { it.first }.distinct().map { id -> "${currentRomParser?.readRoomHeader(id)?.name ?: "Room"} ($${id.toString(16).uppercase()})" }
    internal fun canEditSharedDoor(pointer: Int) = armedSharedDoors[pointer] == true
    internal fun enableSharedDoorEditing(pointer: Int) { armedSharedDoors[pointer] = true }

    internal fun makeDoorIndependent(index: Int, expectedIdentity: Int? = null): Boolean = runCatching {
        require(asmNavigationEnabled) { "Enable the project ASM source workspace first." }
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the ASM workspace." }
        val parser = requireNotNull(currentRomParser)
        val roomId = currentRoomId
        fun idle() {
            require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null && pendingPlmAdds.isEmpty() && pendingPlmRemoves.isEmpty()) {
                "Finish the current stroke or selection before making a connection independent."
            }
            require(currentRomParser === parser && asmPlmSourceBridge === bridge) { "The room ROM or ASM workspace changed." }
        }
        idle(); refreshLinkedPlms()
        if (expectedIdentity != null) require((if (bridge.doors.isManaged(roomId)) bridge.doors.view(roomId).resource?.entries?.getOrNull(index)?.identity else index) == expectedIdentity) {
            "Door order changed; review the current connection."
        }
        val plan = bridge.doorIndependence.plan(roomId, index)
        val recordMap = prepareSourceMapTransaction?.invoke(parser)
        var history: com.supermetroid.editor.asm.AsmRoomAreaMoveHistory? = null
        recordRoomSourceOperation(roomId, "Make connection independent", fileId = PLM_SOURCE_FILE) { _, undo, redo ->
            history = bridge.areaMoves.apply(plan, ::idle) { restoring ->
                if (currentRoomId == roomId) doorEntries = effectiveDoorsForRoom(roomId, parser)
                if (restoring) undo() else redo()
                dirty = true; editVersion++
            }
        }
        recordMap?.invoke(requireNotNull(history))
        postStatus("Connection ${index + 1} is independent. Other rooms keep their shared record; state/FX behavior and existing checkpoints are preserved.")
        true
    }.getOrElse { postStatus("Private connection blocked: ${it.message}"); false }

    internal fun createManagedDoorArrival(index: Int, expectedIdentity: Int? = null): Boolean = runCatching {
        val pointer = doorEntries.getOrNull(index)?.doorDefPtr ?: 0
        require(sharedDoorRooms(pointer).size < 2 || canEditSharedDoor(pointer)) { "Review the affected rooms and choose Edit shared record first." }
        if (expectedIdentity != null) require(asmPlmSourceBridge?.doors?.view(currentRoomId)?.resource?.entries?.getOrNull(index)?.identity == expectedIdentity) {
            "Door order changed in ASM; review the current connection before creating arrival code."
        }
        require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null) {
            "Finish the current tile stroke or selection before creating arrival ASM."
        }
        val name = requireNotNull(currentRomParser?.readRoomHeader(currentRoomId)) { "Select a current room first." }.name
        recordRoomSourceOperation(currentRoomId, "Create managed arrival ASM") { bridge, undo, redo ->
            bridge.doors.createManagedArrival(currentRoomId, index, name, undo, redo)
        }
        true
    }.getOrElse { postStatus("Arrival ASM blocked: ${it.message}"); false }

    internal fun ensureDoorSource(name: String): Boolean = runCatching {
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        val key = project.roomKey(currentRoomId)
        val existing = project.rooms[key]
        val oldStates = existing?.states?.toList().orEmpty()
        val head = bridge.sourceUndoHead(FX_SOURCE_FILE)
        val room = project.getOrCreateRoom(currentRoomId)
        room.ensureStateManifest(requireNotNull(currentRomParser))
        if (!hasManagedDoors()) {
            val before = bridge.doors.sourceText()
            try { bridge.doors.migrate(currentRoomId, name) }
            catch (error: Exception) {
                if (bridge.sourceUndoHead(FX_SOURCE_FILE) === head) {
                    if (existing == null) project.rooms.remove(key)
                    else { existing.states.clear(); existing.states.addAll(oldStates) }
                }
                throw error
            }
            recordManagedRoomOperation(EditOperation("Adopt door source"), before, bridge, FX_SOURCE_FILE)
        }
        refreshDoorSource()
        true
    }.getOrElse { postStatus("Door source blocked: ${it.message}"); false }

    internal fun editInspectedDoorSource(roomId: Int, name: String, expected: String, replacement: String,
                                         afterUndo: () -> Unit, afterRedo: () -> Unit): Boolean = runCatching {
        require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null) {
            "Finish the current tile stroke or selection before editing door source."
        }
        recordRoomSourceOperation(roomId, "Edit door source", afterUndo, afterRedo) { bridge, undo, redo ->
            bridge.doors.editUnmanaged(roomId, name, expected, replacement, undo, redo)
        }
        true
    }.getOrElse { postStatus("Door source edit blocked: ${it.message}"); false }

    internal fun editInspectedRoomPropertiesSource(roomId: Int, name: String, expected: String, replacement: String,
                                                   afterUndo: () -> Unit, afterRedo: () -> Unit): Boolean = runCatching {
        require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null &&
            pendingPlmAdds.isEmpty() && pendingPlmRemoves.isEmpty() && pendingLayoutEditScope == null) {
            "Finish the current tile stroke, selection or scope choice before editing room-property source."
        }
        recordRoomSourceOperation(roomId, "Edit room header/state source", afterUndo, afterRedo, PLM_SOURCE_FILE) { bridge, undo, redo ->
            bridge.properties.editUnmanaged(roomId, name, expected, replacement, undo, redo)
        }
        true
    }.getOrElse { postStatus("Room-property source edit blocked: ${it.message}"); false }

    internal fun roomLayoutSourceLevels(room: RoomEdits): Map<String, ByteArray> {
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        require(currentRomParser == null || matchesRoomSourceContext(bridge.catalog.parser)) { "The room ROM changed; wait for the source workspace." }
        val parser = if (project.newRoomForPreviewId(room.roomId) == null) bridge.catalog.parser else bridge.context.parser
        return room.states.associate { it.id to requireNotNull(effectiveLevelDataForState(room, it, parser)) { "Read every room state before editing its layout source." } }
    }

    internal fun editInspectedLayoutSource(roomId: Int, name: String, expected: String, replacement: String,
                                           afterUndo: () -> Unit, afterRedo: () -> Unit): Boolean = runCatching {
        require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null &&
            pendingPlmAdds.isEmpty() && pendingPlmRemoves.isEmpty() && pendingLayoutEditScope == null) {
            "Finish the current tile stroke, selection or scope choice before editing layout source."
        }
        val owner = project
        val projectId = owner.newRoomForPreviewId(roomId)?.id
        val levels = roomLayoutSourceLevels(roomSourceSnapshot(roomId))
        recordRoomSourceOperation(roomId, "Edit room layout source", afterUndo = {
            val liveId = if (projectId == null) roomId else owner.newRooms.singleOrNull { it.id == projectId }?.previewRoomId
            if (project === owner && currentRoomId == liveId && !hasManagedLayouts() &&
                asmPlmSourceBridge?.catalog?.parser?.let(::matchesRoomSourceContext) == true) {
                val snapshot = roomSourceSnapshot(currentRoomId)
                val selected = currentPlmStateId() ?: snapshot.stateEditsForSourceIndex(currentStateIndex)?.id
                levels[selected]?.let { workingLevelData = it.copyOf(); layoutPreviewProblem = null; _editVersionState.value++ }
            }
            afterUndo()
        }, afterRedo = afterRedo, fileId = PLM_SOURCE_FILE) { bridge, undo, redo ->
            bridge.layouts.editUnmanaged(roomId, name, levels, expected, replacement, undo, redo)
        }
        true
    }.getOrElse { postStatus("Layout source edit blocked: ${it.message}"); false }

    internal fun editInspectedProjectResource(roomId: Int, name: String, kind: RoomAsmSourceKind, expected: String, replacement: String,
                                              afterUndo: () -> Unit, afterRedo: () -> Unit): Boolean = runCatching {
        require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null &&
            pendingPlmAdds.isEmpty() && pendingPlmRemoves.isEmpty() && pendingLayoutEditScope == null) { "Finish the current stroke or selection before editing source." }
        recordRoomSourceOperation(roomId, "Edit ${kind.title}", afterUndo, afterRedo, kind.fileId) { bridge, undo, redo ->
            bridge.editAutomaticProjectResource(roomId, name, kind, expected, replacement, undo, redo)
        }
        true
    }.getOrElse { postStatus("Source edit blocked: ${it.message}"); false }

    /** Keep the visible GUI and source histories aligned for one action, including automatic ownership. */
    private fun recordRoomSourceOperation(roomId: Int, description: String, afterUndo: () -> Unit = {}, afterRedo: () -> Unit = {},
                                          fileId: String = DOOR_SOURCE_FILE,
                                          action: (AsmPlmSourceBridge, () -> Unit, () -> Unit) -> Unit) {
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        val before = when (fileId) { DOOR_SOURCE_FILE -> bridge.doors.sourceText(); ENEMY_SOURCE_FILE -> bridge.enemies.sourceText(); else -> bridge.sourceText() }
        val owner = project
        val projectId = owner.newRoomForPreviewId(roomId)?.id
        fun liveRoomId() = if (projectId == null) roomId else owner.newRooms.singleOrNull { it.id == projectId }?.previewRoomId
        val operation = EditOperation(description)
        var recorded = false
        fun moveHistory(undo: Boolean) {
            if (recorded && project === owner && currentRoomId == liveRoomId() &&
                (undoStack.any { it === operation } || redoStack.any { it === operation })) {
                undoStack.removeAll { it === operation }; redoStack.removeAll { it === operation }
                if (undo) redoStack.add(operation) else undoStack.add(operation)
                undoVersion++
            }
        }
        action(bridge, {
                moveHistory(true)
                if (fileId == DOOR_SOURCE_FILE && roomId == currentRoomId) currentRomParser?.let { doorEntries = effectiveDoorsForRoom(roomId, it) }
                refreshLinkedPlms(); afterUndo()
            }, { moveHistory(false); refreshLinkedPlms(); afterRedo() })
        if (roomId == currentRoomId) {
            recordManagedRoomOperation(operation, before, bridge, fileId)
            recorded = true
        }
        refreshLinkedPlms()
    }

    private fun refreshDoorSource() {
        val sharedView = asmPlmSourceBridge?.doors?.sharedView()
        asmPlmSourceBridge?.doors?.refreshAll()
        if (!hasManagedDoors()) {
            doorSourceIdentities = emptyMap()
            runCatching { currentRomParser?.let { effectiveDoorsForRoom(currentRoomId, it) } }.onSuccess { entries ->
                doorPreviewProblem = sharedView?.problem?.takeIf {
                    entries.orEmpty().any { door -> asmPlmSourceBridge?.doors?.sharedConsumers(door.doorDefPtr)?.isNotEmpty() == true }
                }
                doorArrivalBindings = entries.orEmpty().mapIndexedNotNull { index, door ->
                    (project.newRoomForPreviewId(currentRoomId)?.doors?.getOrNull(index)?.arrivalAsmLabel
                        ?.let { com.supermetroid.editor.rom.SourceDoorArrival(it) }
                        ?: sharedView?.entries?.get(door.doorDefPtr)?.arrival)?.let { index to it }
                }.toMap()
                if (entries != null && entries != _workingDoors) { _workingDoors.clear(); _workingDoors.addAll(entries); _editVersionState.value++ }
            }.onFailure { doorPreviewProblem = it.message }
            return
        }
        val view = asmPlmSourceBridge?.doors?.view(currentRoomId)
        doorPreviewProblem = view?.problem ?: if (view == null) "Wait for the door source." else null
        val entries = view?.resource?.entries?.map { it.entry() }.orEmpty()
        val bindings = view?.resource?.entries.orEmpty().mapNotNull { door -> door.arrival?.let { door.change.doorIndex to it } }.toMap()
        val identities = view?.resource?.entries.orEmpty().associate { it.change.doorIndex to it.identity }
        if (_workingDoors != entries || doorArrivalBindings != bindings || doorSourceIdentities != identities) {
            _workingDoors.clear(); _workingDoors.addAll(entries); doorArrivalBindings = bindings; doorSourceIdentities = identities; _editVersionState.value++
        }
    }
    internal fun roomPropertiesPreviewAvailable() = (!hasManagedRoomProperties() ||
        project.rooms[project.roomKey(currentRoomId)]?.asmHeaderPropertiesProjection != null) &&
        (!hasManagedSelectors() || project.rooms[project.roomKey(currentRoomId)]?.asmSelectorOrderProjection != null)
    internal var selectorPreviewProblem by mutableStateOf<String?>(null)
        private set
    internal var selectorProjectionStatus by mutableStateOf(com.supermetroid.editor.asm.AsmSelectorProjectionStatus.CURRENT)
        private set
    internal var selectorConversionPreview by mutableStateOf<com.supermetroid.editor.asm.AsmSelectorConversionPreview?>(null)
        private set
    internal var selectorAsmOnlyLabels by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    private var romHasOpaqueSelectors by mutableStateOf(false)
    internal fun selectorEditingAvailable() = !romHasOpaqueSelectors && (!hasManagedSelectors() ||
        asmPlmSourceBridge != null && selectorPreviewProblem == null && selectorProjectionStatus == com.supermetroid.editor.asm.AsmSelectorProjectionStatus.CURRENT)
    internal fun convertRecognizedSelectors(preview: com.supermetroid.editor.asm.AsmSelectorConversionPreview): Boolean = runCatching {
        val owner = project
        val roomId = currentRoomId
        require(hasManagedSelectors()) { "The room no longer owns its selector source." }
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        val operation = EditOperation("Convert recognized selector predicates")
        lateinit var managed: ManagedSourceUndo
        bridge.selectors.convert(roomId, preview) { undo ->
            if (project === owner && currentRoomId == roomId) {
                undoStack.removeAll { it === operation }; redoStack.removeAll { it === operation }
                managedPlmUndo[operation] = managed
                if (undo) redoStack.add(operation) else undoStack.add(operation)
                refreshLinkedPlms()
            }
            dirty = true; undoVersion++; editVersion++
        }
        recordManagedRoomOperation(operation, preview.sourceText, bridge)
        managed = managedPlmUndo.getValue(operation)
        refreshLinkedPlms()
        true
    }.getOrElse { postStatus("Selector conversion blocked: ${it.message}"); false }
    private var selectorPreviewRevision = emptyMap<Int, Pair<List<String>?, List<com.supermetroid.editor.data.ProjectRoomStateCondition?>>>()

    internal fun ensureSelectorSource(name: String): Boolean = runCatching {
        val parser = requireNotNull(currentRomParser)
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        project.getOrCreateRoom(currentRoomId).ensureStateManifest(parser)
        if (!hasManagedSelectors()) {
            val before = bridge.sourceText()
            bridge.selectors.migrate(currentRoomId, name)
            recordManagedRoomOperation(EditOperation("Adopt state selector source"), before, bridge)
        }
        refreshSelectors()
        true
    }.getOrElse { postStatus("Selectors blocked: ${it.message}"); false }

    private fun refreshSelectors() {
        asmPlmSourceBridge?.selectors?.refreshAll()
        val projections = project.rooms.values.filter { it.asmStateSelectorsSourceVersion != 0 }.associate {
            it.roomId to (it.asmSelectorOrderProjection to it.states.map { state -> state.asmConditionProjection })
        }
        if (selectorPreviewRevision != projections) { selectorPreviewRevision = projections; _editVersionState.value++ }
        val view = if (!hasManagedSelectors()) null else asmPlmSourceBridge?.selectors?.view(currentRoomId)
        selectorConversionPreview = view?.conversion
        selectorAsmOnlyLabels = view?.resource?.routines.orEmpty().mapValues { it.value.label }
        selectorPreviewProblem = if (!hasManagedSelectors()) null else view?.problem
            ?: if (asmPlmSourceBridge == null) "Wait for the state selector source." else null
        selectorProjectionStatus = if (!hasManagedSelectors()) com.supermetroid.editor.asm.AsmSelectorProjectionStatus.CURRENT
            else view?.status ?: com.supermetroid.editor.asm.AsmSelectorProjectionStatus.UNAVAILABLE
    }

    private fun editSourceSelectors(description: String, action: (AsmPlmSourceBridge) -> Unit): Boolean = runCatching {
        if (!ensureSelectorSource(currentRomParser?.readRoomHeader(currentRoomId)?.name ?: "Room")) return false
        val bridge = requireNotNull(asmPlmSourceBridge)
        val before = bridge.sourceText()
        action(bridge)
        if (before == bridge.sourceText()) return false
        recordManagedRoomOperation(EditOperation(description), before, bridge)
        refreshLinkedPlms()
        dirty = true; editVersion++
        true
    }.getOrElse { postStatus("Selectors blocked: ${it.message}"); false }
    var roomPropertiesPreviewProblem by mutableStateOf<String?>(null)
        private set
    private var roomPropertiesPreviewRevision = emptyMap<Int, Pair<RoomHeaderChange?, List<StateDataChange?>>>()

    internal fun ensureRoomPropertiesSource(name: String): Boolean = runCatching {
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        val parser = requireNotNull(currentRomParser)
        project.getOrCreateRoom(currentRoomId).ensureStateManifest(parser)
        if (!hasManagedRoomProperties()) {
            val before = bridge.sourceText()
            bridge.properties.migrate(currentRoomId, name)
            recordManagedRoomOperation(EditOperation("Adopt room header/state properties"), before, bridge)
        }
        refreshRoomProperties()
        true
    }.getOrElse { postStatus("Room properties blocked: ${it.message}"); false }

    private fun refreshRoomProperties() {
        val wasManaged = currentRoomId in roomPropertiesPreviewRevision
        asmPlmSourceBridge?.properties?.refreshAll()
        val projections = project.rooms.values.filter { it.asmRoomPropertiesSourceVersion != 0 }.associate {
            it.roomId to (it.asmHeaderPropertiesProjection to it.states.map { state -> state.asmStatePropertiesProjection })
        }
        if (roomPropertiesPreviewRevision != projections) {
            roomPropertiesPreviewRevision = projections
            _editVersionState.value++ // Plain transient projections must still invalidate Compose consumers.
        }
        if (!hasManagedRoomProperties()) {
            roomPropertiesPreviewProblem = null
            // Ownership Undo must also restore the legacy state/graphics preview, without reloading the canvas/history.
            if (wasManaged) currentRomParser?.let { parser -> parser.readRoomHeader(currentRoomId)?.let { native ->
                val state = applyCurrentStateData(native, parser)
                applyRoomPropertiesPreview(applyHeaderChanges(native).area, state.bgScrolling, state.tileset)
            } }
            return
        }
        val view = asmPlmSourceBridge?.properties?.view(currentRoomId)
        roomPropertiesPreviewProblem = view?.problem ?: if (view == null) "Room properties are loading…" else null
        val resource = view?.resource ?: return
        val state = currentPlmStateId()?.let { resource.states[it] } ?: return
        applyRoomPropertiesPreview(requireNotNull(resource.header.area), requireNotNull(state.bgScrolling), requireNotNull(state.tileset))
    }

    private fun applyRoomPropertiesPreview(area: Int, bgScrolling: Int, tileset: Int) {
        if (currentArea != area) {
            currentArea = area
            currentRomParser?.let { parser ->
                refreshVanillaSaveIndices(parser)
                currentAreaSaveEntryCount = parser.saveEntryCount(area)
                currentAreaRomSaveEntries = (0 until currentAreaSaveEntryCount.coerceAtMost(RomParser.SAVE_STATION_SLOT_COUNT))
                    .mapNotNull { index -> parser.readSaveEntry(area, index)?.let { index to it } }.toMap()
            }
        }
        currentBgScrolling = bgScrolling
        if (!canEditEmbeddedLayer2()) activeRoomLayer = RoomEditLayer.LAYER1
        if (currentTilesetId != tileset) {
            currentTilesetId = tileset
            currentRomParser?.let(::reloadCurrentRoomTileGraphics)
        }
    }

    private fun editSourceRoomProperties(description: String, roomId: Int = currentRoomId, action: (AsmPlmSourceBridge) -> Unit): Boolean = runCatching {
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        require(currentRomParser == null || matchesRoomSourceContext(bridge.catalog.parser)) { "The room ROM changed; wait for the source workspace." }
        if (roomId == currentRoomId && !ensureRoomPropertiesSource(currentRomParser?.readRoomHeader(roomId)?.name ?: "Room")) return false
        val before = bridge.sourceText()
        action(bridge)
        if (before != bridge.sourceText() && roomId == currentRoomId) recordManagedRoomOperation(EditOperation(description), before, bridge)
        refreshLinkedPlms()
        dirty = true; editVersion++
        true
    }.getOrElse { postStatus("Room properties blocked: ${it.message}"); false }
    var layoutPreviewProblem by mutableStateOf<String?>(null)
        private set
    private var pendingLayoutNavigation: Pair<Int, String>? = null
    private var loadedLayoutRomVersion = -1

    internal fun requestLayoutNavigation(roomId: Int, layoutId: String?, stateId: String?) {
        val room = project.rooms[project.roomKey(roomId)] ?: return
        val state = room.states.firstOrNull { it.id == stateId } ?: room.states.firstOrNull {
            it.resources.level == layoutId && it.id == currentPlmStateId() && currentRoomId == roomId
        } ?: room.states.firstOrNull { it.resources.level == layoutId } ?: return
        pendingLayoutNavigation = roomId to state.id
        if (currentRoomId == roomId && currentPlmStateId() != state.id) currentRomParser?.let { switchRoomState(state.id, it) }
    }

    internal fun ensureLayoutSource(roomName: String): Boolean = runCatching {
        require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null) { "Finish the current tile stroke or selection before opening layout source." }
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        val parser = requireNotNull(currentRomParser)
        require(matchesRoomSourceContext(bridge.catalog.parser)) { "The room ROM changed; wait for the source workspace." }
        val room = project.getOrCreateRoom(currentRoomId)
        room.ensureStateManifest(parser)
        if (!hasManagedLayouts()) {
            val levels = room.states.associate { it.id to requireNotNull(effectiveLevelDataForState(room, it, parser)) }
            require(levels.getValue(requireNotNull(currentPlmStateId())).contentEquals(workingLevelData)) { "Wait for the current layout preview before adopting it." }
            val before = bridge.sourceText()
            bridge.layouts.migrate(currentRoomId, roomName, levels)
            val operation = EditOperation("Adopt room layout source")
            recordManagedRoomOperation(operation, before, bridge)
        }
        refreshLayoutSource()
        true
    }.getOrElse { postStatus("Layout source blocked: ${it.message}"); false }

    internal fun inspectRoomSource(name: String, properties: Boolean = false, selectors: Boolean = false): String {
        return inspectRoomSource(name, RoomAsmSourceRequest(when {
            selectors -> RoomAsmSourceKind.SELECTORS
            properties -> RoomAsmSourceKind.PROPERTIES
            else -> RoomAsmSourceKind.LAYOUT
        }))
    }

    internal fun roomSourceSnapshot(roomId: Int = currentRoomId): RoomEdits {
        val parser = requireNotNull(if (project.newRoomForPreviewId(roomId) != null) asmPlmSourceBridge?.context?.parser ?: currentRomParser else asmPlmSourceBridge?.catalog?.parser ?: currentRomParser)
        val room = project.rooms[project.roomKey(roomId)]?.let { com.supermetroid.editor.asm.snapshotRoomForResize(it) } ?: RoomEdits(roomId)
        room.ensureStateManifest(parser) // Snapshot only: inspection must not even add a project manifest.
        return room
    }

    internal fun inspectRoomSource(name: String, request: RoomAsmSourceRequest, roomId: Int = currentRoomId): String {
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        val parser = if (project.newRoomForPreviewId(roomId) == null) bridge.catalog.parser else {
            require(matchesRoomSourceContext(bridge.catalog.parser)) { "The room ROM changed; wait for the source workspace." }
            bridge.context.parser
        }
        val room = roomSourceSnapshot(roomId)
        return when (request.kind) {
            RoomAsmSourceKind.SELECTORS -> bridge.selectors.preview(room, name)
            RoomAsmSourceKind.DOORS -> bridge.doors.preview(room, name)
            RoomAsmSourceKind.PROPERTIES -> bridge.properties.preview(room, name)
            RoomAsmSourceKind.LAYOUT -> bridge.layouts.preview(room, name,
                room.states.associate { it.id to requireNotNull(effectiveLevelDataForState(room, it, parser)) })
            RoomAsmSourceKind.INITIAL_SCROLLS -> bridge.initialScrolls.preview(room, name)
            RoomAsmSourceKind.FX -> bridge.fx.preview(room, name)
            RoomAsmSourceKind.ENEMIES, RoomAsmSourceKind.ENEMY_GRAPHICS -> bridge.enemies.preview(room, name)
            RoomAsmSourceKind.PLMS -> bridge.previewObjects(room, name, ::isEditorItemPlm)
            RoomAsmSourceKind.SAVE_SPAWN -> com.supermetroid.editor.asm.AsmSaveStationSource.render(saveSpawnSourceResource(requireNotNull(request.plm)))
        }
    }

    /** Production wiring is shared with the workflow tests, including late-bound metadata notifications. */
    internal var enemyTuningBridge by mutableStateOf<com.supermetroid.editor.asm.AsmEnemyTuningBridge?>(null)
        private set
    internal var beamDamageBridge by mutableStateOf<com.supermetroid.editor.asm.AsmBeamDamageBridge?>(null)
        private set
    internal var bossStatsBridge by mutableStateOf<com.supermetroid.editor.asm.AsmBossStatsBridge?>(null)
        private set
    internal var environmentalDamageBridge by mutableStateOf<com.supermetroid.editor.asm.AsmEnvironmentalDamageBridge?>(null)
        private set
    internal var dataPatchBridge by mutableStateOf<com.supermetroid.editor.asm.AsmDataPatchBridge?>(null)
        private set
    internal var patchToggleController: com.supermetroid.editor.asm.AsmPatchToggleController? = null
        private set
    internal var pendingPatchDisable by mutableStateOf<SmPatch?>(null)
        private set

    fun requestTogglePatch(id: String) {
        val patch = project.patches.find { it.id == id } ?: return
        if (patch.enabled && patchHasCustomSettings(patch)) pendingPatchDisable = patch
        else togglePatch(id)
    }

    internal fun patchHasCustomSettings(patch: SmPatch): Boolean =
        (patch.configType == com.supermetroid.editor.rom.RoomNamePauseMapPatch.CONFIG_TYPE && project.roomNameOverrides.isNotEmpty()) ||
        (asmNavigationEnabled && patch.configType in com.supermetroid.editor.asm.NATIVE_PATCH_CONFIG_TYPES &&
            runCatching { requireNotNull(patchToggleController).customized(requireNotNull(patch.configType)) }.getOrDefault(true)) ||
            runCatching { patchSettingsDifferFromBaseline(patch, currentRomParser) }.getOrDefault(true)

    internal fun dismissPatchDisable() { pendingPatchDisable = null }

    internal fun confirmPatchDisable() {
        val patch = pendingPatchDisable ?: return
        pendingPatchDisable = null
        if (project.patches.any { it === patch } && patch.enabled) togglePatch(patch.id)
    }

    internal fun detachRoomSourceBridges() {
        asmPlmSourceBridge = null
        enemyTuningBridge = null
        beamDamageBridge = null
        bossStatsBridge = null
        environmentalDamageBridge = null
        dataPatchBridge = null
        patchToggleController = null
        pendingPatchDisable = null
    }

    internal fun openEnemyTuningSource(key: String): Boolean = runCatching {
        require(asmNavigationEnabled) { "Enable the project ASM source workspace first." }
        val bridge = requireNotNull(enemyTuningBridge) { "Wait for enemy source indexing." }
        val (file, line) = bridge.line(key)
        bridge.workspace.showProjectWorkspace()
        bridge.workspace.openSource(file, line)
        true
    }.getOrElse { postStatus("Enemy source unavailable: ${it.message}"); false }

    internal fun openBeamDamageSource(key: String? = null): Boolean = runCatching {
        require(asmNavigationEnabled) { "Enable the project ASM source workspace first." }
        val bridge = requireNotNull(beamDamageBridge) { "Wait for beam source indexing." }
        val line = bridge.line(key)
        bridge.workspace.showProjectWorkspace()
        bridge.workspace.openSource(com.supermetroid.editor.asm.BEAM_DAMAGE_SOURCE_FILE, line)
        true
    }.getOrElse { postStatus("Beam source unavailable: ${it.message}"); false }

    internal fun openBossStatsSource(key: String? = null): Boolean = runCatching {
        require(asmNavigationEnabled) { "Enable the project ASM source workspace first." }
        val bridge = requireNotNull(bossStatsBridge) { "Wait for boss stats source indexing." }
        val line = bridge.line(key)
        bridge.workspace.showProjectWorkspace()
        bridge.workspace.openSource(com.supermetroid.editor.asm.ENEMY_HEADER_SOURCE_FILE, line)
        true
    }.getOrElse { postStatus("Boss stats source unavailable: ${it.message}"); false }

    internal fun openEnvironmentalDamageSource(key: String? = null): Boolean = runCatching {
        require(asmNavigationEnabled) { "Enable the project ASM source workspace first." }
        val bridge = requireNotNull(environmentalDamageBridge) { "Wait for environmental source indexing." }
        val (file, line) = bridge.line(key)
        bridge.workspace.showProjectWorkspace(); bridge.workspace.openSource(file, line)
        true
    }.getOrElse { postStatus("Environmental source unavailable: ${it.message}"); false }

    internal fun bindRoomSources(catalog: AsmPlmSourceCatalog,
                                 workspace: AsmWorkspaceState): AsmPlmSourceBridge {
        val owner = project
        val root = workspace.projectWorkspace?.root
        val bridge = AsmPlmSourceBridge(catalog, workspace, registerSaveParticipant = false) {
            check(project === owner && workspace.projectWorkspace?.root == root) { "The ASM project changed; reopen the room source." }
            owner
        }
        bridge.onMetadataChanged = { if (project === owner) notifyPlmSourceLinked() }
        bridge.describePlm = { entry -> customItemNameForPlm(entry.id) ?: RomParser.plmDisplayName(entry.id, entry.param) }
        bridge.properties.projectRoomParser = {
            require(currentRomParser == null || matchesRoomSourceContext(catalog.parser)) { "The room ROM changed; wait for the source workspace." }
            bridge.context.parser
        }
        bridge.layouts.projectRoomParser = bridge.properties.projectRoomParser
        bridge.context.workspaceParser = {
            require(currentRomParser == null || matchesRoomSourceContext(catalog.parser)) {
                "The room ROM changed; wait for the project room workspace."
            }
            val parser = projectRoomWorkspaceParser ?: currentRomParser
            require(parser != null && (parser === projectRoomWorkspaceParser || parser === catalog.parser)) {
                "The room ROM changed; wait for the project room workspace."
            }
            parser
        }
        asmPlmSourceBridge = bridge
        workspace.sourceSaveParticipant = bridge
        enemyTuningBridge = root?.let { com.supermetroid.editor.asm.AsmEnemyTuningSource.load(workspace.projectWorkspace!!.originalDirectory) }?.let { tuning ->
            com.supermetroid.editor.asm.AsmEnemyTuningBridge(tuning, workspace, {
                check(project === owner && workspace.projectWorkspace?.root == root &&
                    (currentRomParser == null || matchesRoomSourceContext(catalog.parser))) { "The enemy ROM or ASM workspace changed." }
                owner
            }) { dirty = true; patchVersion++ }
        }
        beamDamageBridge = root?.let { com.supermetroid.editor.asm.AsmBeamDamageSource.load(workspace.projectWorkspace!!.originalDirectory) }?.let { damage ->
            com.supermetroid.editor.asm.AsmBeamDamageBridge(damage, workspace, {
                check(project === owner && workspace.projectWorkspace?.root == root &&
                    (currentRomParser == null || matchesRoomSourceContext(catalog.parser))) { "The beam ROM or ASM workspace changed." }
                owner
            }) { dirty = true; patchVersion++ }
        }
        bossStatsBridge = root?.let { com.supermetroid.editor.asm.AsmBossStatsSource.load(workspace.projectWorkspace!!.originalDirectory) }?.let { stats ->
            com.supermetroid.editor.asm.AsmBossStatsBridge(stats, workspace, {
                check(project === owner && workspace.projectWorkspace?.root == root &&
                    (currentRomParser == null || matchesRoomSourceContext(catalog.parser))) { "The boss stats ROM or ASM workspace changed." }
                owner
            }) { dirty = true; patchVersion++ }
        }
        environmentalDamageBridge = root?.let { com.supermetroid.editor.asm.AsmEnvironmentalDamageSource.load(workspace.projectWorkspace!!.originalDirectory) }?.let { source ->
            com.supermetroid.editor.asm.AsmEnvironmentalDamageBridge(source, workspace, {
                check(project === owner && workspace.projectWorkspace?.root == root &&
                    (currentRomParser == null || matchesRoomSourceContext(catalog.parser))) { "The environmental ROM or ASM workspace changed." }
                owner
            }) { dirty = true; patchVersion++ }
        }
        dataPatchBridge = root?.let { com.supermetroid.editor.asm.AsmDataPatchSource.load(workspace.projectWorkspace!!.originalDirectory) }?.let { source ->
            com.supermetroid.editor.asm.AsmDataPatchBridge(source, workspace, {
                check(project === owner && workspace.projectWorkspace?.root == root &&
                    (currentRomParser == null || matchesRoomSourceContext(catalog.parser))) { "The patch ROM or ASM workspace changed." }
                owner
            }) { dirty = true; patchVersion++ }
        }
        val toggleController = com.supermetroid.editor.asm.AsmPatchToggleController(workspace, {
            check(project === owner && workspace.projectWorkspace?.root == root &&
                (currentRomParser == null || matchesRoomSourceContext(catalog.parser))) { "The patch ROM or ASM workspace changed." }
            owner
        }, com.supermetroid.editor.asm.patchToggleFamilies(beamDamageBridge?.catalog, bossStatsBridge?.catalog, environmentalDamageBridge?.catalog, dataPatchBridge?.catalog)) {
            dirty = true; patchVersion++
        }
        patchToggleController = toggleController
        beamDamageBridge?.publisher = toggleController::publish
        bossStatsBridge?.publisher = toggleController::publish
        environmentalDamageBridge?.publisher = toggleController::publish
        dataPatchBridge?.publisher = toggleController::publish
        workspace.sourceEditorInterceptor = { file, before, after ->
            if (!asmNavigationEnabled) null else runCatching {
                val changes = listOfNotNull(enemyTuningBridge?.prepareIntercept(file, before, after),
                    bossStatsBridge?.prepareIntercept(file, before, after), environmentalDamageBridge?.prepareIntercept(file, before, after)) +
                    dataPatchBridge?.prepareIntercept(file, before, after).orEmpty()
                val sources = mapOf(file to (before to after))
                val touches = toggleController.touches(sources).filterKeys { it != "beam_damage" }
                if (changes.isNotEmpty() || touches.keys.any(toggleController::needsActivation)) {
                    toggleController.publishEdits(touches, sources, changes)
                    true
                } else beamDamageBridge?.intercept(file, before, after)
            }.getOrElse { postStatus("Source edit blocked: ${it.message}"); false }
        }
        workspace.sourceEditorLinks = { file, line ->
            enemyTuningBridge?.editorLinks(file, line).orEmpty() +
                bossStatsBridge?.editorLinks(file, line).orEmpty() +
                beamDamageBridge?.editorLinks(file, line).orEmpty() + environmentalDamageBridge?.editorLinks(file, line).orEmpty() +
                dataPatchBridge?.editorLinks(file, line).orEmpty()
        }
        runCatching { toggleController.reconcileSourceFlags() }
            .onFailure { postStatus("Patch synchronization blocked: ${it.message}") }
        return bridge
    }

    /** Explicit navigation adoption has one GUI undo boundary, even for banks other than $8F. */
    internal fun adoptInspectedRoomSource(request: RoomAsmSourceRequest, name: String, roomId: Int = currentRoomId): Boolean {
        if (roomId != currentRoomId || currentRomParser == null) return adoptUnloadedRoomSource(request, name, roomId)
        val bridge = requireNotNull(asmPlmSourceBridge)
        val before = when (request.kind.fileId) {
            FX_SOURCE_FILE -> bridge.fx.sourceText()
            ENEMY_SOURCE_FILE -> bridge.enemies.sourceText()
            else -> bridge.sourceText()
        }
        val historySize = undoStack.size
        val adopted = when (request.kind) {
            RoomAsmSourceKind.LAYOUT -> ensureLayoutSource(name)
            RoomAsmSourceKind.PROPERTIES -> ensureRoomPropertiesSource(name)
            RoomAsmSourceKind.SELECTORS -> ensureSelectorSource(name)
            RoomAsmSourceKind.DOORS -> ensureDoorSource(name)
            RoomAsmSourceKind.INITIAL_SCROLLS -> ensureInitialScrollSource(name)
            RoomAsmSourceKind.FX -> ensureFxSource(name)
            RoomAsmSourceKind.ENEMIES, RoomAsmSourceKind.ENEMY_GRAPHICS -> ensureEnemySource(name)
            RoomAsmSourceKind.PLMS -> ensureCurrentPlmSource(name)
            RoomAsmSourceKind.SAVE_SPAWN -> ensureSaveSpawnSource(requireNotNull(request.plm))
        }
        if (adopted && undoStack.size == historySize) recordManagedRoomOperation(EditOperation("Adopt ${request.kind.title}"), before, bridge, request.kind.fileId)
        return adopted
    }

    /** ASM-first adoption must not load a different canvas or discard its selected state/history. */
    private fun adoptUnloadedRoomSource(request: RoomAsmSourceRequest, name: String, roomId: Int): Boolean = runCatching {
        require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null) { "Finish the current tile stroke or selection before adopting room source." }
        val bridge = requireNotNull(asmPlmSourceBridge)
        val parser = if (project.newRoomForPreviewId(roomId) == null) bridge.catalog.parser else {
            require(matchesRoomSourceContext(bridge.catalog.parser)) { "The room ROM changed; wait for the source workspace." }
            bridge.context.parser
        }
        val staged = roomSourceSnapshot(roomId)
        // Validate/compute geometry before attaching even identity metadata to the project.
        val levels = if (request.kind == RoomAsmSourceKind.LAYOUT) staged.states.associate {
            it.id to requireNotNull(effectiveLevelDataForState(staged, it, parser))
        } else emptyMap()
        val key = project.roomKey(roomId)
        val existing = project.rooms[key]
        val oldStates = existing?.states?.toList().orEmpty()
        val revision = bridge.sourceUndoHead(request.kind.fileId)
        try {
            if (request.kind in setOf(RoomAsmSourceKind.LAYOUT, RoomAsmSourceKind.PROPERTIES, RoomAsmSourceKind.SELECTORS, RoomAsmSourceKind.DOORS)) {
                val room = existing ?: staged.also { project.rooms[key] = it }
                if (room.states.isEmpty()) room.states.addAll(staged.states)
            }
            when (request.kind) {
                RoomAsmSourceKind.LAYOUT -> bridge.layouts.migrate(roomId, name, levels)
                RoomAsmSourceKind.PROPERTIES -> bridge.properties.migrate(roomId, name)
                RoomAsmSourceKind.SELECTORS -> bridge.selectors.migrate(roomId, name)
                RoomAsmSourceKind.DOORS -> bridge.doors.migrate(roomId, name)
                RoomAsmSourceKind.INITIAL_SCROLLS -> bridge.initialScrolls.migrate(roomId, name)
                RoomAsmSourceKind.FX -> bridge.fx.migrate(roomId, name)
                RoomAsmSourceKind.ENEMIES, RoomAsmSourceKind.ENEMY_GRAPHICS -> bridge.enemies.migrate(roomId, name)
                RoomAsmSourceKind.PLMS -> bridge.migrateRoom(roomId, name, ::isEditorItemPlm)
                RoomAsmSourceKind.SAVE_SPAWN -> error("Select a save station in the room editor first.")
            }
        } catch (problem: Exception) {
            if (bridge.sourceUndoHead(request.kind.fileId) === revision) {
                if (existing == null) project.rooms.remove(key)
                else { existing.states.clear(); existing.states.addAll(oldStates) }
            }
            throw problem
        }
        true // The source document owns this undo step; the unrelated room's GUI stack is untouched.
    }.getOrElse { postStatus("Room source blocked: ${it.message}"); false }

    private fun refreshLayoutSource() {
        if (!hasManagedLayouts()) { layoutPreviewProblem = null; return }
        if (pendingEdits.isNotEmpty()) return // Never replace an in-progress canvas stroke with a source projection.
        val id = currentPlmStateId()
        val view = id?.let { asmPlmSourceBridge?.layouts?.view(currentRoomId, it) }
        layoutPreviewProblem = view?.problem ?: if (view == null) "Room layout source is loading…" else null
        val data = view?.resource?.bytes()
        if (data == null) {
            if (workingLevelData != null) { workingLevelData = null; _editVersionState.value++ }
        } else if (workingLevelData?.contentEquals(data) != true) {
            workingLevelData = data
            if (!canEditEmbeddedLayer2()) activeRoomLayer = RoomEditLayer.LAYER1
            _editVersionState.value++
        }
    }
    internal fun ensureEnemySource(roomName: String): Boolean = runCatching {
        requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }.enemies.migrate(currentRoomId, roomName)
        currentStateId = currentPlmStateId()
        refreshEnemies()
        true
    }.getOrElse { postStatus("Enemy edit blocked: ${it.message}"); false }

    private fun refreshEnemies() {
        if (!hasManagedEnemies()) {
            enemyPreviewProblem = null; workingEnemyGraphics = emptyList()
            if (project.newRoomForPreviewId(currentRoomId) != null) currentRomParser?.let { parser ->
                val room = project.rooms[project.roomKey(currentRoomId)]
                val state = selectedStateEdits(room)
                parser.parseRoomStatesWithData(currentRoomId).getOrNull(state?.baseSourceStateIndex() ?: currentStateIndex)?.let { runtime ->
                    val entries = com.supermetroid.editor.rom.applyEnemyChanges(parser.parseEnemyPopulation(runtime.enemySetPtr), room?.enemyChanges.orEmpty() + state?.enemyChanges.orEmpty())
                    if (_workingEnemies != entries) { _workingEnemies.clear(); _workingEnemies.addAll(entries); _editVersionState.value++ }
                }
            }
            return
        }
        val stateId = currentPlmStateId()
        val bridge = asmPlmSourceBridge?.enemies
        if (stateId == null || bridge == null) { enemyPreviewProblem = "Enemy source is loading…"; return }
        val view = bridge.view(currentRoomId, stateId)
        enemyPreviewProblem = view.problem
        val resource = view.resource ?: return
        if (_workingEnemies != resource.population.entries || workingEnemyGraphics != resource.graphics.entries) {
            _workingEnemies.clear(); _workingEnemies.addAll(resource.population.entries)
            workingEnemyGraphics = resource.graphics.entries
            _editVersionState.value++
        }
    }

    private fun editSourceEnemies(name: String, transform: (List<RomParser.EnemyEntry>) -> List<RomParser.EnemyEntry>) {
        if (!ensureEnemySource(currentRomParser?.readRoomHeader(currentRoomId)?.name ?: "Room")) return
        runCatching {
            val bridge = requireNotNull(asmPlmSourceBridge).enemies
            val before = bridge.sourceText()
            bridge.edit(currentRoomId, requireNotNull(currentPlmStateId()), transform)
            val after = bridge.sourceText()
            if (before != after) {
                val op = EditOperation(name)
                managedPlmUndo[op] = ManagedSourceUndo(ENEMY_SOURCE_FILE, before, after,
                    requireNotNull(asmPlmSourceBridge).sourceUndoHead(ENEMY_SOURCE_FILE))
                undoStack.add(op); redoStack.clear(); undoVersion++
                refreshEnemies(); editVersion++
            }
        }.onFailure { postStatus("Enemy edit blocked: ${it.message}") }
    }

    private fun editSourceFx(stateId: String, door: Int, change: FxChange?, allStates: Boolean = false, sourceDoor: ProjectSourceDoorReference? = null) {
        if (change == null) return // Source-mode GUI edits are scalar updates, not clearing a parallel override.
        // A stale picker must not adopt/dirty a previously untouched FX resource.
        if (!runCatching {
            requireNotNull(asmPlmSourceBridge).fx.validate(currentRoomId,
                com.supermetroid.editor.rom.SourceFxResource("Incoming FX edit", listOf(emptyDefaultFxEntry().copy(doorSelect = door, sourceDoor = sourceDoor))))
        }.onFailure { postStatus("FX edit blocked: ${it.message}") }.isSuccess) return
        if (!ensureFxSource(currentRomParser?.readRoomHeader(currentRoomId)?.name ?: "Room ${currentRoomId.toString(16).uppercase()}")) return
        runCatching {
            val bridge = requireNotNull(asmPlmSourceBridge)
            val before = bridge.fx.sourceText()
            bridge.fx.edit(currentRoomId, stateId, door, change, allStates, sourceDoor)
            val after = bridge.fx.sourceText()
            if (before != after) {
                val op = EditOperation(if (door == 0 && sourceDoor == null) "Edit Default FX · ASM" else "Edit door FX · ASM")
                managedPlmUndo[op] = ManagedSourceUndo(FX_SOURCE_FILE, before, after, bridge.sourceUndoHead(FX_SOURCE_FILE))
                undoStack.add(op); redoStack.clear(); undoVersion++
                refreshFx()
                editVersion++
            }
        }.onFailure { postStatus("FX edit blocked: ${it.message}") }
    }

    internal fun removeSourceDoorFx(stateId: String, entry: RomParser.FxEntry): Boolean = runCatching {
        val bridge = requireNotNull(asmPlmSourceBridge)
        val before = bridge.fx.sourceText()
        bridge.fx.remove(currentRoomId, stateId, entry.doorSelect, entry.sourceDoor)
        val after = bridge.fx.sourceText()
        if (before != after) {
            val op = EditOperation("Remove entrance FX · ASM")
            managedPlmUndo[op] = ManagedSourceUndo(FX_SOURCE_FILE, before, after, bridge.sourceUndoHead(FX_SOURCE_FILE))
            undoStack.add(op); redoStack.clear(); undoVersion++
            refreshFx(); editVersion++
        }
        true
    }.getOrElse { postStatus("FX removal blocked: ${it.message}"); false }

    internal fun ensureInitialScrollSource(roomName: String): Boolean = runCatching {
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        bridge.initialScrolls.migrate(currentRoomId, roomName)
        currentStateId = currentPlmStateId()
        refreshInitialScrolls()
        true
    }.getOrElse { postStatus("Initial scroll edit blocked: ${it.message}"); false }

    private fun refreshInitialScrolls() {
        if ((project.rooms[project.roomKey(currentRoomId)]?.asmInitialScrollSourceVersion ?: 0) == 0) {
            initialScrollPreviewProblem = null
            if (project.newRoomForPreviewId(currentRoomId) != null) currentRomParser?.let { parser ->
                val room = project.rooms[project.roomKey(currentRoomId)]
                val state = selectedStateEdits(room)
                val header = parser.readRoomHeader(currentRoomId)
                val runtime = parser.parseRoomStatesWithData(currentRoomId).getOrNull(state?.baseSourceStateIndex() ?: currentStateIndex)
                if (header != null && runtime != null) {
                    val w = workingBlocksWide / 16; val h = workingBlocksTall / 16
                    val values = resizeScrollGrid(parser.parseScrollData(runtime.scrollPtr, header.width, header.height), header.width, header.height, w, h)
                    (room?.scrollChanges.orEmpty() + state?.scrollChanges.orEmpty()).forEach { edit ->
                        if (edit.screenX in 0 until w && edit.screenY in 0 until h) values[edit.screenY * w + edit.screenX] = edit.newValue
                    }
                    if (!_workingScrolls.contentEquals(values)) { _workingScrolls = values; scrollVersion++; _editVersionState.value++ }
                }
            }
            return
        }
        val bridge = asmPlmSourceBridge?.initialScrolls
        val stateId = currentPlmStateId()
        if (bridge == null || stateId == null) {
            initialScrollPreviewProblem = "Initial scroll source is loading…"
            return
        }
        val view = bridge.view(currentRoomId, stateId)
        initialScrollPreviewProblem = view.problem
        val values = view.resource?.values?.toIntArray() ?: return
        if (!_workingScrolls.contentEquals(values)) {
            _workingScrolls = values
            scrollVersion++
            _editVersionState.value++
        }
    }

    internal fun hasManagedPlms(): Boolean = (project.rooms[project.roomKey(currentRoomId)]?.asmPlmSourceVersion ?: 0) != 0
    internal fun hasManagedInitialScrolls(): Boolean = (project.rooms[project.roomKey(currentRoomId)]?.asmInitialScrollSourceVersion ?: 0) != 0
    internal fun currentPlmStateId(): String? = selectedStateEdits(project.rooms[project.roomKey(currentRoomId)])?.id

    internal fun ensureCurrentPlmSource(roomName: String): Boolean {
        plmSourceMigrationProblem = null
        val bridge = asmPlmSourceBridge ?: run {
            plmSourceMigrationProblem = "The project ASM workspace is not ready yet."
            return false
        }
        if (hasManagedPlms()) return true
        return runCatching {
            bridge.migrateRoom(currentRoomId, roomName, ::isEditorItemPlm)
            currentStateId = currentPlmStateId()
            refreshLinkedPlms()
            postStatus("Room objects now use editable ASM. Project Save keeps the source and room-state identities together.")
            true
        }.getOrElse {
            plmSourceMigrationProblem = it.message ?: "Object source migration failed safely."
            postStatus(plmSourceMigrationProblem!!)
            false
        }
    }

    private fun editManagedPlms(name: String, action: (AsmPlmSourceBridge, String) -> Unit): Boolean {
        val bridge = asmPlmSourceBridge ?: run {
            postStatus("Object edit blocked: wait for the ASM workspace before editing these objects.")
            return false
        }
        val stateId = currentPlmStateId() ?: run {
            postStatus("Object edit blocked: select a room state before editing these objects.")
            return false
        }
        return runCatching {
            val view = bridge.managedView(currentRoomId, stateId)
            require(view.problem == null) { view.problem ?: "Invalid room source; resolve it in ASM before visual editing." }
            val before = bridge.sourceText()
            action(bridge, stateId)
            val after = bridge.sourceText()
            if (before != after) {
                val op = EditOperation(name, emptyList())
                managedPlmUndo[op] = ManagedSourceUndo(PLM_SOURCE_FILE, before, after, bridge.sourceUndoHead())
                undoStack.add(op)
                redoStack.clear()
                undoVersion++
                refreshLinkedPlms()
                editVersion++
                postStatus("$name applied.")
            }
            true
        }.getOrElse {
            postStatus("Object edit blocked: ${it.message ?: "invalid object source; no edit applied."}")
            false
        }
    }

    internal fun notifyPlmSourceLinked() {
        markDirty()
        editVersion++
    }

    internal fun currentPlmSourcePointer(): Int? {
        val parser = currentRomParser ?: return null
        val state = parser.findAllStateDataOffsets(currentRoomId).getOrNull(currentStateIndex) ?: return null
        return parser.readStateData(state)["plmSetPtr"]
    }

    private fun stateDisplayName(state: RoomStateEdits): String = selectorAsmOnlyLabels[state.id]?.let {
        "Managed in ASM · ${selectorRoutineCaption(currentRoomId, state.id, it)}"
    }
        ?: if (hasManagedSelectors() && selectorProjectionStatus == com.supermetroid.editor.asm.AsmSelectorProjectionStatus.UNAVAILABLE)
            "${state.id} · selector unavailable" else state.effectiveCondition.displaySummary(currentArea)

    internal fun currentPlmStateName(): String =
        selectedStateEdits(project.rooms[project.roomKey(currentRoomId)])?.let(::stateDisplayName)
            ?: currentRomParser?.parseRoomStates(currentRoomId)?.getOrNull(currentStateIndex)?.conditionName
            ?: "Selected state"

    internal fun refreshLinkedPlms() {
        refreshDoorSource()
        refreshSelectors()
        refreshRoomProperties()
        refreshLayoutSource()
        refreshInitialScrolls()
        refreshFx()
        refreshEnemies()
        if (hasManagedPlms()) {
            val stateId = currentPlmStateId()
            val view = stateId?.let { asmPlmSourceBridge?.managedView(currentRoomId, it) }
            if (view == null) {
                objectPreviewProblem = if (stateId == null) "Missing source-owned room state" else null
                if (_workingPlms.isNotEmpty()) {
                    _workingPlms.clear()
                    _editVersionState.value++
                }
                return
            }
            objectPreviewProblem = view.problem
            val entries = view.document?.entries.orEmpty()
            val revision = Triple(currentRoomId, stateId, requireNotNull(asmPlmSourceBridge).sourceText())
            val sourceChanged = linkedObjectPreviewRevision != revision
            linkedObjectPreviewRevision = revision
            if (_workingPlms != entries || sourceChanged) {
                _workingPlms.clear()
                _workingPlms.addAll(entries)
                _editVersionState.value++ // Referenced command values can change without changing a PLM row.
            }
            return
        }
        linkedObjectPreviewRevision = null
        objectPreviewProblem = null
        if (project.newRoomForPreviewId(currentRoomId) != null) {
            val parser = currentRomParser ?: return
            val room = project.rooms[project.roomKey(currentRoomId)]
            val state = selectedStateEdits(room)
            val runtime = parser.parseRoomStatesWithData(currentRoomId).getOrNull(state?.baseSourceStateIndex() ?: currentStateIndex) ?: return
            val entries = applyPlmChanges(parser.parsePlmSet(runtime.plmSetPtr), room?.plmChanges.orEmpty() + state?.plmChanges.orEmpty())
            if (_workingPlms != entries) { _workingPlms.clear(); _workingPlms.addAll(entries); _editVersionState.value++ }
            return
        }
        val pointer = currentPlmSourcePointer() ?: return
        val bridge = asmPlmSourceBridge ?: return
        val entries = bridge.view(pointer).document?.entries ?: return
        if (_workingPlms != entries) {
            _workingPlms.clear()
            _workingPlms.addAll(entries)
            _editVersionState.value++ // Source projections are not independent project edits.
        }
    }

    private fun blockLinkedPlmMutation(allStates: Boolean = false): Boolean {
        if (hasManagedPlms()) {
            postStatus("These objects use project ASM. Use item/PLM add/remove or source fields; this combined room operation is not source-aware yet.")
            return true
        }
        val pointers = if (allStates) currentRomParser?.let { parser ->
            parser.findAllStateDataOffsets(currentRoomId).mapNotNull { parser.readStateData(it)["plmSetPtr"] }
        }.orEmpty() else listOfNotNull(currentPlmSourcePointer())
        if (pointers.none { pointer -> asmPlmSourceBridge?.isLinked(pointer) == true ||
                project.asmWorkspace.plmSourceBindings.any { it.pointer == pointer } }) return false
        postStatus("These objects have ASM source edits. Use Room Info → Edit objects, or restore the list in ASM before adding room-editor changes. Source-list resizing and detachment are not supported yet.")
        return true
    }
    private var originalPlmCount = 0

    /**
     * PLMs for any room with project-level PLM additions/removals applied.
     * For the active room, return the live working list so unsaved edits are visible immediately.
     */
    fun effectivePlmsForRoom(roomId: Int, romParser: RomParser): List<RomParser.PlmEntry> {
        if (roomId == currentRoomId && workingLevelData != null) {
            return _workingPlms.toList()
        }

        val plms = romParser.getAllPlmEntriesForRoom(roomId).toMutableList()
        val roomEdits = project.rooms[project.roomKey(roomId)] ?: return plms
        if (roomEdits.asmPlmSourceVersion != 0) return roomEdits.states.flatMap { state ->
            asmPlmSourceBridge?.managedView(roomId, state.id)?.document?.entries.orEmpty()
        }.distinct()

        for (change in roomEdits.plmChanges) {
            when (change.action) {
                "add" -> plms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                "remove" -> plms.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y }
            }
        }
        for (state in roomEdits.states) {
            for (change in state.plmChanges) {
                when (change.action) {
                    "add" -> plms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                    "remove" -> plms.removeAll {
                        it.id == change.plmId && it.x == change.x && it.y == change.y && it.param == change.param
                    }
                }
            }
        }

        return plms
    }

    /** Door entries for the current room (mutable for editing). */
    private val _workingDoors = mutableListOf<RomParser.DoorEntry>()
    var doorEntries: List<RomParser.DoorEntry>
        get() = _workingDoors
        private set(value) { _workingDoors.clear(); _workingDoors.addAll(value) }

    /**
     * Door entries with project edits applied. This keeps UI validation and
     * room navigation semantic: callers do not need to inspect DoorDef ROM
     * addresses or duplicate the last-change-wins merge.
     */
    internal fun effectiveDoorsForRoom(roomId: Int, romParser: RomParser): List<RomParser.DoorEntry> {
        val room = romParser.readRoomHeader(roomId) ?: return emptyList()
        val doors = romParser.parseDoorList(room.doorOut).toMutableList()
        val edits = project.rooms[project.roomKey(roomId)]
        if (edits != null && edits.asmDoorSourceVersion != 0) return asmPlmSourceBridge?.doors?.view(roomId)?.resource?.entries?.map { it.entry() }.orEmpty()
        if (edits != null && edits.asmDoorSourceVersion != 0 && edits.asmDoorProjection == null) return emptyList()
        val changes = edits?.effectiveDoorChanges.orEmpty()
        for (change in changes) {
            val original = doors.getOrNull(change.doorIndex) ?: continue
            doors[change.doorIndex] = RomParser.DoorEntry(
                destRoomPtr = change.destRoomPtr,
                bitflag = change.bitflag,
                doorCapCode = change.doorCapCode,
                screenX = change.screenX,
                screenY = change.screenY,
                distFromDoor = change.distFromDoor,
                entryCode = change.entryCode,
                doorDefPtr = original.doorDefPtr,
            )
        }
        if (asmNavigationEnabled) {
            val shared = asmPlmSourceBridge?.doors?.sharedEntries().orEmpty()
            return doors.map { door -> shared[door.doorDefPtr]?.entry() ?: door }
        }
        return doors
    }

    /** Working enemy population for the current room (includes edits). */
    private val _workingEnemies = mutableListOf<RomParser.EnemyEntry>()
    val workingEnemies: List<RomParser.EnemyEntry> get() = _workingEnemies

    /** Working scroll data for the current room. One byte per screen (R/B/G). */
    private var _workingScrolls = IntArray(0)
    val workingScrolls: IntArray get() = _workingScrolls
    private var _originalScrolls = IntArray(0)
    var scrollVersion by mutableStateOf(0)
        private set

    // ─── Tileset editor ─────────────────────────────────────────

    /** The currently-viewed tileset in the tileset editor (independent of loaded room). */
    var editorTilesetId by mutableStateOf(0)
        private set

    /** TileGraphics for the tileset editor view (may differ from room's tileGraphics). */
    var editorTileGraphics by mutableStateOf<TileGraphics?>(null)
        private set

    /** Currently selected metatile index in the tileset editor (-1 = none). */
    var editorSelectedMetatile by mutableStateOf(-1)
        private set

    fun loadEditorTileset(tilesetId: Int, romParser: RomParser): Boolean {
        editorTilesetId = tilesetId
        editorSelectedMetatile = -1
        val tg = TileGraphics(romParser)
        if (!tg.loadTileset(tilesetId)) { editorTileGraphics = null; return false }
        applyCustomGfxToTileGraphics(tg, tilesetId)
        editorTileGraphics = tg
        return true
    }

    /** Apply any project-stored custom graphics, metatile tables, and palette to a TileGraphics instance. */
    internal fun applyCustomGfxToTileGraphics(tg: TileGraphics, tilesetId: Int) {
        val gfxData = project.customGfx
        val varB64 = gfxData.varGfx[tilesetId.toString()]
        if (varB64 != null) {
            try { tg.applyCustomVarGfx(java.util.Base64.getDecoder().decode(varB64)) }
            catch (_: Exception) {}
        }
        val creB64 = gfxData.creGfx
        if (creB64 != null) {
            try { tg.applyCustomCreGfx(java.util.Base64.getDecoder().decode(creB64)) }
            catch (_: Exception) {}
        }
        val tableB64 = gfxData.tileTables[tilesetId.toString()]
        if (tableB64 != null) {
            try { tg.applyCustomVarTileTable(java.util.Base64.getDecoder().decode(tableB64)) }
            catch (_: Exception) {}
        }
        val creTableB64 = gfxData.creTileTable
        if (creTableB64 != null) {
            try { tg.applyCustomCreTileTable(java.util.Base64.getDecoder().decode(creTableB64)) }
            catch (_: Exception) {}
        }
        applyCustomPaletteToTileGraphics(tg, tilesetId)
    }

    // ─── Palette editing ──────────────────────────────────────────────

    /** Apply any project-stored custom palette to a TileGraphics instance. */
    internal fun applyCustomPaletteToTileGraphics(tg: TileGraphics, tilesetId: Int) {
        val palB64 = project.customGfx.palettes[tilesetId.toString()] ?: return
        try {
            val rawBgr = java.util.Base64.getDecoder().decode(palB64)
            if (rawBgr.size != 256) return  // 8 rows × 16 colors × 2 bytes
            for (row in 0..7) for (col in 0..15) {
                val offset = (row * 16 + col) * 2
                val bgr555 = (rawBgr[offset].toInt() and 0xFF) or
                        ((rawBgr[offset + 1].toInt() and 0xFF) shl 8)
                tg.setPaletteEntry(row, col, bgr555)
            }
        } catch (_: Exception) {}
    }

    /** Save the current TileGraphics palette to the project as a base64 BGR555 override. */
    fun savePaletteOverride(tilesetId: Int) {
        val tg = editorTileGraphics ?: return
        val rawBgr = tg.getRawPaletteData() ?: return
        project.customGfx.palettes[tilesetId.toString()] = java.util.Base64.getEncoder().encodeToString(rawBgr)
        dirty = true
        paletteVersion++
        if (tilesetId == currentTilesetId) {
            tileGraphics?.let {
                applyCustomPaletteToTileGraphics(it, tilesetId)
                _editVersionState.value++
            }
        }
    }

    private fun tilesetPaletteEffectKey(tilesetId: Int) = "tileset:$tilesetId"

    /** Remove the custom palette override, restoring the ROM default. */
    fun resetPaletteOverride(tilesetId: Int): Boolean {
        val removedPalette = project.customGfx.palettes.remove(tilesetId.toString()) != null
        val removedEffect = project.customGfx.paletteEffects.remove(tilesetPaletteEffectKey(tilesetId)) != null
        if (removedPalette || removedEffect) {
            dirty = true
            paletteVersion++
            return true
        }
        return false
    }

    /** Check if the project has a custom palette for the given tileset. */
    fun hasCustomPalette(tilesetId: Int): Boolean =
        project.customGfx.palettes.containsKey(tilesetId.toString()) ||
                project.customGfx.paletteEffects.containsKey(tilesetPaletteEffectKey(tilesetId))

    fun hasCurrentTilesetOverrides(): Boolean =
        hasCustomVarGfx() ||
                hasCustomCreGfx() ||
                hasCustomVarTileTable() ||
                hasCustomCreTileTable() ||
                hasCustomPalette(editorTilesetId)

    fun resetCurrentTilesetOverrides(
        areaTiles: Boolean,
        commonTiles: Boolean,
        palette: Boolean,
        areaMetatiles: Boolean = false,
        commonMetatiles: Boolean = false,
    ): Boolean {
        var changed = false
        val tilesetKey = editorTilesetId.toString()
        if (areaTiles && project.customGfx.varGfx.remove(tilesetKey) != null) {
            changed = true
        }
        if (commonTiles && project.customGfx.creGfx != null) {
            project.customGfx.creGfx = null
            changed = true
        }
        if (areaMetatiles && project.customGfx.tileTables.remove(tilesetKey) != null) {
            changed = true
        }
        if (commonMetatiles && project.customGfx.creTileTable != null) {
            project.customGfx.creTileTable = null
            changed = true
        }
        if (palette) {
            var paletteChanged = false
            if (project.customGfx.palettes.remove(tilesetKey) != null) {
                paletteChanged = true
            }
            if (project.customGfx.paletteEffects.remove(tilesetPaletteEffectKey(editorTilesetId)) != null) {
                paletteChanged = true
            }
            if (paletteChanged) {
                paletteVersion++
                changed = true
            }
        }
        if (changed) dirty = true
        return changed
    }

    // ─── Sprite palette editing (Samus, beams, bosses, enemies) ─────

    /**
     * Read a sprite palette region from ROM, applying any project overrides.
     * Returns BGR555 color array, or null if ROM not loaded.
     */
    fun readSpritePalette(regionId: String, romParser: RomParser): IntArray? {
        val region = com.supermetroid.editor.rom.SpritePalettes.findRegion(regionId) ?: return null
        val romData = romParser.getRomData()
        // Start with ROM data
        val colors = com.supermetroid.editor.rom.SpritePalettes.readColors(romData, region) ?: return null
        // Apply project override if present
        val b64 = project.customGfx.spritePalettes[regionId]
        if (b64 != null) {
            try {
                val overrideBytes = java.util.Base64.getDecoder().decode(b64)
                val overrideColors = com.supermetroid.editor.rom.SpritePalettes.bytesToColors(overrideBytes)
                if (overrideColors.size == colors.size) return overrideColors
            } catch (_: Exception) {}
        }
        return colors
    }

    /** Save a sprite palette override to the project. */
    fun saveSpritePaletteOverride(regionId: String, colors: IntArray) {
        val region = com.supermetroid.editor.rom.SpritePalettes.findRegion(regionId) ?: return
        require(colors.size == region.colorCount)
        val bytes = com.supermetroid.editor.rom.SpritePalettes.colorsToBytes(colors)
        project.customGfx.spritePalettes[regionId] = java.util.Base64.getEncoder().encodeToString(bytes)
        dirty = true
        paletteVersion++
    }

    /** Remove sprite palette override, restoring ROM default. */
    fun resetSpritePaletteOverride(regionId: String): Boolean {
        val removedPalette = project.customGfx.spritePalettes.remove(regionId) != null
        val removedEffect = project.customGfx.paletteEffects.remove(regionId) != null
        if (removedPalette || removedEffect) {
            dirty = true
            paletteVersion++
            return true
        }
        return false
    }

    /** Check if a sprite palette has a project override. */
    fun hasSpritePaletteOverride(regionId: String): Boolean =
        project.customGfx.spritePalettes.containsKey(regionId) ||
                project.customGfx.paletteEffects.containsKey(regionId)

    // ─── Palette effect tracking ───────────────────────────────────

    /** Get the applied effect ID for a palette key, or null if none/custom. */
    fun getPaletteEffect(key: String): String? = project.customGfx.paletteEffects[key]

    /** Set the applied effect ID for a palette key. */
    fun setPaletteEffect(key: String, effectId: String) {
        project.customGfx.paletteEffects[key] = effectId
        dirty = true
    }

    /** Clear the effect tracking for a palette key (e.g. on reset or manual edit). */
    fun clearPaletteEffect(key: String): Boolean {
        val changed = project.customGfx.paletteEffects.remove(key) != null
        if (changed) {
            dirty = true
            paletteVersion++
        }
        return changed
    }

    /**
     * Read the palette for any tileset as BGR555 colors (128 = 8 rows × 16).
     * Applies project override if present unless [includeOverride] is false.
     */
    fun readTilesetPalette(tilesetId: Int, romParser: RomParser, includeOverride: Boolean = true): IntArray? {
        try {
            val romData = romParser.getRomData()
            val tablePC = romParser.snesToPc(0x8FE6A2)
            val entryOffset = tablePC + tilesetId * 9
            if (entryOffset + 9 > romData.size) return null
            val palettePtr = (romData[entryOffset + 6].toInt() and 0xFF) or
                    ((romData[entryOffset + 7].toInt() and 0xFF) shl 8) or
                    ((romData[entryOffset + 8].toInt() and 0xFF) shl 16)
            val raw = romParser.decompressLZ2(palettePtr)
            if (raw.size < 256) return null
            val colors = IntArray(128)
            for (i in 0 until 128) {
                colors[i] = (raw[i * 2].toInt() and 0xFF) or
                        ((raw[i * 2 + 1].toInt() and 0xFF) shl 8)
            }
            val b64 = if (includeOverride) project.customGfx.palettes[tilesetId.toString()] else null
            if (b64 != null) {
                try {
                    val overrideBytes = java.util.Base64.getDecoder().decode(b64)
                    if (overrideBytes.size == 256) {
                        for (i in 0 until 128) {
                            colors[i] = (overrideBytes[i * 2].toInt() and 0xFF) or
                                    ((overrideBytes[i * 2 + 1].toInt() and 0xFF) shl 8)
                        }
                    }
                } catch (_: Exception) {}
            }
            return colors
        } catch (_: Exception) { return null }
    }

    /** Save a tileset palette from BGR555 color array (128 colors). */
    fun saveTilesetPaletteFromColors(tilesetId: Int, colors: IntArray) {
        if (colors.size != 128) return
        val raw = ByteArray(256)
        for (i in 0 until 128) {
            raw[i * 2] = (colors[i] and 0xFF).toByte()
            raw[i * 2 + 1] = ((colors[i] shr 8) and 0xFF).toByte()
        }
        project.customGfx.palettes[tilesetId.toString()] = java.util.Base64.getEncoder().encodeToString(raw)
        dirty = true
        paletteVersion++
        if (tilesetId == editorTilesetId) {
            editorTileGraphics?.let { applyCustomPaletteToTileGraphics(it, tilesetId) }
        }
        if (tilesetId == currentTilesetId) {
            tileGraphics?.let {
                applyCustomPaletteToTileGraphics(it, tilesetId)
                _editVersionState.value++
            }
        }
    }

    fun reloadCurrentRoomTileGraphics(romParser: RomParser): Boolean {
        val tg = TileGraphics(romParser)
        tileGraphics = if (tg.loadTileset(currentTilesetId)) {
            applyCustomGfxToTileGraphics(tg, currentTilesetId)
            tg
        } else {
            null
        }
        _editVersionState.value++
        return tileGraphics != null
    }

    // ─── Tileset graphics export / import ────────────────────────────

    /**
     * Export an 8x8 tile sheet as a PNG file.
     * @param isCre true = CRE (common), false = URE (area-specific)
     */
    fun exportTileSheet(filePath: String, isCre: Boolean): Boolean {
        val tg = editorTileGraphics ?: return false
        val startTile = if (isCre) tg.getCreOffset() else 0
        val numTiles = if (isCre) tg.getCreTileCount() else tg.getVarTileCount()
        val result = tg.renderTileSheet(startTile, numTiles) ?: return false
        val (pixels, w, h) = result
        return writePng(filePath, pixels, w, h)
    }

    /** Export the palette as a PNG reference image. */
    fun exportPalette(filePath: String): Boolean {
        val tg = editorTileGraphics ?: return false
        val result = tg.renderPaletteImage(16) ?: return false
        val (pixels, w, h) = result
        return writePng(filePath, pixels, w, h)
    }

    /**
     * Import an edited tile sheet PNG and store in the project.
     * @param isCre true = CRE (common), false = URE (area-specific)
     */
    fun importTileSheet(filePath: String, isCre: Boolean): Boolean {
        val tg = editorTileGraphics ?: return false
        val img = try { javax.imageio.ImageIO.read(java.io.File(filePath)) } catch (_: Exception) { return false }
            ?: return false
        val w = img.width; val h = img.height
        val pixels = img.getRGB(0, 0, w, h, null, 0, w)

        val startTile = if (isCre) tg.getCreOffset() else 0
        val numTiles = if (isCre) tg.getCreTileCount() else tg.getVarTileCount()
        val raw4bpp = tg.importTileSheet(pixels, w, startTile, numTiles)

        val b64 = java.util.Base64.getEncoder().encodeToString(raw4bpp)
        if (isCre) {
            project.customGfx.creGfx = b64
            tg.applyCustomCreGfx(raw4bpp)
        } else {
            project.customGfx.varGfx[editorTilesetId.toString()] = b64
            tg.applyCustomVarGfx(raw4bpp)
        }
        dirty = true
        return true
    }

    /** Check whether custom graphics exist for the current tileset. */
    fun hasCustomVarGfx(): Boolean = project.customGfx.varGfx.containsKey(editorTilesetId.toString())
    fun hasCustomCreGfx(): Boolean = project.customGfx.creGfx != null
    fun hasCustomVarTileTable(): Boolean = project.customGfx.tileTables.containsKey(editorTilesetId.toString())
    fun hasCustomCreTileTable(): Boolean = project.customGfx.creTileTable != null

    fun saveCurrentMetatileTableOverride(): Boolean {
        val tg = editorTileGraphics ?: return false
        val selected = editorSelectedMetatile
        if (selected !in 0 until TileGraphics.METATILE_COUNT) return false
        if (!tg.isCreMetatileIndex(selected) && !tg.isVariableMetatileIndex(selected)) return false

        val raw = if (tg.isCreMetatileIndex(selected)) {
            tg.getRawCreTileTable() ?: return false
        } else {
            tg.getRawVarTileTable() ?: return false
        }
        val b64 = java.util.Base64.getEncoder().encodeToString(raw)
        if (tg.isCreMetatileIndex(selected)) {
            project.customGfx.creTileTable = b64
        } else {
            project.customGfx.tileTables[editorTilesetId.toString()] = b64
        }
        dirty = true
        return true
    }

    fun setCurrentMetatileWords(words: IntArray): Boolean {
        val tg = editorTileGraphics ?: return false
        val selected = editorSelectedMetatile
        if (!tg.isCreMetatileIndex(selected) && !tg.isVariableMetatileIndex(selected)) return false
        if (!tg.setMetatileWords(selected, words)) return false
        return saveCurrentMetatileTableOverride()
    }

    // ── Enemy / Boss sprite graphics ──────────────────────────────────────────

    fun loadEnemyTileData(romParser: RomParser, speciesId: Int) = enemySprite.loadEnemyTileData(romParser, speciesId)
    fun applyEnemyTileSheetEdits(romParser: RomParser, speciesId: Int, pixels: IntArray, w: Int, h: Int) = enemySprite.applyEnemyTileSheetEdits(romParser, speciesId, pixels, w, h)
    fun hasCustomEnemyTiles(speciesId: Int) = enemySprite.hasCustomEnemyTiles(speciesId)
    fun resetEnemyTiles(speciesId: Int) = enemySprite.resetEnemyTiles(speciesId)
    fun loadEnemyPalette(romParser: RomParser, speciesId: Int) = enemySprite.loadEnemyPalette(romParser, speciesId)
    fun applyEnemyPalette(speciesId: Int, palette: IntArray) = enemySprite.applyEnemyPalette(speciesId, palette)
    fun hasCustomEnemyPalette(speciesId: Int) = enemySprite.hasCustomEnemyPalette(speciesId)
    fun resetEnemyPalette(speciesId: Int) = enemySprite.resetEnemyPalette(speciesId)

    // ── Phantoon sprite delegates ─────────────────────────────────────────

    fun getPhantoonSpritemap(romParser: RomParser) = phantoonSprite.getSpritemap(romParser)
    fun renderPhantoonComponent(
        romParser: RomParser,
        def: com.supermetroid.editor.rom.PhantoonSpritemap.ComponentDef,
        paletteStage: com.supermetroid.editor.rom.PhantoonSpritemap.PaletteStageDef =
            com.supermetroid.editor.rom.PhantoonSpritemap.PALETTE_STAGES.last(),
    ) = phantoonSprite.renderComponent(romParser, def, paletteStage)
    fun renderPhantoonFullBody(
        romParser: RomParser,
        paletteStage: com.supermetroid.editor.rom.PhantoonSpritemap.PaletteStageDef =
            com.supermetroid.editor.rom.PhantoonSpritemap.PALETTE_STAGES.last(),
        eyeball: com.supermetroid.editor.rom.PhantoonSpritemap.ComponentDef? = null,
    ) = phantoonSprite.renderFullBody(romParser, paletteStage, eyeball)
    fun renderPhantoonAnimation(
        romParser: RomParser,
        def: com.supermetroid.editor.rom.PhantoonSpritemap.AnimationDef,
        paletteStage: com.supermetroid.editor.rom.PhantoonSpritemap.PaletteStageDef =
            com.supermetroid.editor.rom.PhantoonSpritemap.PALETTE_STAGES.last(),
    ) = phantoonSprite.renderAnimation(romParser, def, paletteStage)
    fun applyPhantoonComponentEdits(romParser: RomParser, sprite: com.supermetroid.editor.rom.PhantoonSpritemap.AssembledSprite, editedPixels: IntArray) = phantoonSprite.applyComponentEdits(romParser, sprite, editedPixels)
    fun getPhantoonPalette(romParser: RomParser) = phantoonSprite.getPalette(romParser)
    fun hasCustomPhantoonComponents() = phantoonSprite.hasCustomComponents()
    fun hasCustomPhantoonTileSheet() = phantoonSprite.hasCustomTileSheet()
    fun resetPhantoonTileSheet() = phantoonSprite.resetTileSheet()

    // ── Draygon sprite delegates ──────────────────────────────────────────

    fun renderDraygonComposition(
        romParser: RomParser,
        def: com.supermetroid.editor.rom.DraygonSpritemap.CompositionDef,
        palette: com.supermetroid.editor.rom.DraygonSpritemap.PaletteStageDef,
    ) = draygonSprite.renderComposition(romParser, def, palette)
    fun renderDraygonComponent(
        romParser: RomParser,
        def: com.supermetroid.editor.rom.DraygonSpritemap.ComponentDef,
        side: com.supermetroid.editor.rom.DraygonSpritemap.Side,
        palette: com.supermetroid.editor.rom.DraygonSpritemap.PaletteStageDef,
    ) = draygonSprite.renderComponent(romParser, def, side, palette)
    fun renderDraygonAnimation(
        romParser: RomParser,
        def: com.supermetroid.editor.rom.DraygonSpritemap.AnimationDef,
        palette: com.supermetroid.editor.rom.DraygonSpritemap.PaletteStageDef,
    ) = draygonSprite.renderAnimation(romParser, def, palette)
    fun loadDraygonObjSheet(romParser: RomParser) = draygonSprite.loadObjSheet(romParser)
    fun renderEditedDraygonObjCompositions(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: com.supermetroid.editor.rom.DraygonSpritemap.PaletteStageDef,
    ) = draygonSprite.renderEditedObjCompositions(romParser, pixels, width, height, palette)
    fun applyDraygonObjSheetEdits(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
    ) {
        val colors = draygonSprite.getPalette(
            romParser,
            com.supermetroid.editor.rom.DraygonSpritemap.PALETTE_STAGES.first(),
        ) ?: return
        enemySprite.applyEnemyTileSheetEdits(
            romParser,
            com.supermetroid.editor.rom.DraygonSpritemap.BODY_SPECIES_ID,
            pixels,
            width,
            height,
            paletteOverride = colors,
        )
        draygonSprite.invalidate()
    }
    fun hasCustomDraygonObjSheet() =
        enemySprite.hasCustomEnemyTiles(com.supermetroid.editor.rom.DraygonSpritemap.BODY_SPECIES_ID)
    fun resetDraygonObjSheet() {
        enemySprite.resetEnemyTiles(com.supermetroid.editor.rom.DraygonSpritemap.BODY_SPECIES_ID)
        draygonSprite.invalidate()
    }

    // ── Crocomire sprite delegates ───────────────────────────────────────

    fun renderCrocomireComposition(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.CrocomireSpritemap.CompositionDef,
    ) = crocomireSprite.renderComposition(romParser, definition)
    fun renderCrocomireComponent(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.CrocomireSpritemap.ComponentDef,
    ) = crocomireSprite.renderComponent(romParser, definition)
    fun renderCrocomireAnimation(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.CrocomireSpritemap.InstructionListDef,
    ) = crocomireSprite.renderAnimation(romParser, definition)
    fun loadCrocomireSource(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.CrocomireSpritemap.PixelSourceDef,
    ) = crocomireSprite.loadSourceSheet(romParser, definition)
    fun renderEditedCrocomireCompositions(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
    ) = crocomireSprite.renderEditedCompositions(romParser, pixels, width, height)
    fun applyCrocomireObjSheetEdits(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
    ) {
        val colors = crocomireSprite.getEditingPalette(romParser) ?: return
        enemySprite.applyEnemyTileSheetEdits(
            romParser,
            com.supermetroid.editor.rom.CrocomireSpritemap.SPECIES_ID,
            pixels,
            width,
            height,
            paletteOverride = colors,
        )
        crocomireSprite.invalidate()
    }
    fun hasCustomCrocomireObjSheet() =
        enemySprite.hasCustomEnemyTiles(com.supermetroid.editor.rom.CrocomireSpritemap.SPECIES_ID)
    fun resetCrocomireObjSheet() {
        enemySprite.resetEnemyTiles(com.supermetroid.editor.rom.CrocomireSpritemap.SPECIES_ID)
        crocomireSprite.invalidate()
    }

    // ── Spore Spawn sprite delegates ────────────────────────────────────

    fun renderSporeSpawnComposition(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.SporeSpawnSpritemap.CompositionDef,
        palette: com.supermetroid.editor.rom.SporeSpawnSpritemap.PaletteStageDef,
    ) = sporeSpawnSprite.renderComposition(romParser, definition, palette)
    fun renderSporeSpawnComponent(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.SporeSpawnSpritemap.ComponentDef,
        palette: com.supermetroid.editor.rom.SporeSpawnSpritemap.PaletteStageDef,
    ) = sporeSpawnSprite.renderComponent(romParser, definition, palette)
    fun renderSporeSpawnAnimation(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.SporeSpawnSpritemap.InstructionListDef,
        palette: com.supermetroid.editor.rom.SporeSpawnSpritemap.PaletteStageDef,
    ) = sporeSpawnSprite.renderAnimation(romParser, definition, palette)
    fun renderSporeSpawnSpawnerAnimation(
        romParser: RomParser,
        palette: com.supermetroid.editor.rom.SporeSpawnSpritemap.PaletteStageDef,
    ) = sporeSpawnSprite.renderSpawnerAnimation(romParser, palette)
    fun renderSporeSpawnSporeAnimation(romParser: RomParser) =
        sporeSpawnSprite.renderSporeAnimation(romParser)
    fun loadSporeSpawnSource(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.SporeSpawnSpritemap.PixelSourceDef,
        palette: com.supermetroid.editor.rom.SporeSpawnSpritemap.PaletteStageDef,
    ) = sporeSpawnSprite.loadSourceSheet(romParser, definition, palette)
    fun renderEditedSporeSpawnCompositions(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: com.supermetroid.editor.rom.SporeSpawnSpritemap.PaletteStageDef,
    ) = sporeSpawnSprite.renderEditedCompositions(romParser, pixels, width, height, palette)
    fun applySporeSpawnObjSheetEdits(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: com.supermetroid.editor.rom.SporeSpawnSpritemap.PaletteStageDef,
    ) {
        val colors = sporeSpawnSprite.getEditingPalette(romParser, palette) ?: return
        enemySprite.applyEnemyTileSheetEdits(
            romParser,
            com.supermetroid.editor.rom.SporeSpawnSpritemap.SPECIES_ID,
            pixels,
            width,
            height,
            paletteOverride = colors,
        )
        sporeSpawnSprite.invalidate()
    }
    fun hasCustomSporeSpawnObjSheet() =
        enemySprite.hasCustomEnemyTiles(com.supermetroid.editor.rom.SporeSpawnSpritemap.SPECIES_ID)
    fun resetSporeSpawnObjSheet() {
        enemySprite.resetEnemyTiles(com.supermetroid.editor.rom.SporeSpawnSpritemap.SPECIES_ID)
        sporeSpawnSprite.invalidate()
    }

    // ── Metroid sprite delegates ───────────────────────────────────────

    fun renderMetroidComposition(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.MetroidSpritemap.CompositionDef,
    ) = metroidSprite.renderComposition(romParser, definition)
    fun renderMetroidComponent(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.MetroidSpritemap.ComponentDef,
    ) = metroidSprite.renderComponent(romParser, definition)
    fun renderMetroidAnimation(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.MetroidSpritemap.AnimationDef,
    ) = metroidSprite.renderAnimation(romParser, definition)
    fun loadMetroidSource(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.MetroidSpritemap.PixelSourceDef,
    ) = metroidSprite.loadSourceSheet(romParser, definition)
    fun renderEditedMetroidCompositions(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
    ) = metroidSprite.renderEditedCompositions(romParser, pixels, width, height)
    fun applyMetroidObjSheetEdits(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
    ) {
        val colors = metroidSprite.getEditingPalette(romParser) ?: return
        enemySprite.applyEnemyTileSheetEdits(
            romParser,
            com.supermetroid.editor.rom.MetroidSpritemap.SPECIES_ID,
            pixels,
            width,
            height,
            paletteOverride = colors,
        )
        metroidSprite.invalidate()
    }
    fun hasCustomMetroidObjSheet() =
        enemySprite.hasCustomEnemyTiles(com.supermetroid.editor.rom.MetroidSpritemap.SPECIES_ID)
    fun resetMetroidObjSheet() {
        enemySprite.resetEnemyTiles(com.supermetroid.editor.rom.MetroidSpritemap.SPECIES_ID)
        metroidSprite.invalidate()
    }

    // ── Botwoon sprite delegates ────────────────────────────────────────

    fun renderBotwoonComposition(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.BotwoonSpritemap.CompositionDef,
        palette: com.supermetroid.editor.rom.BotwoonSpritemap.PaletteStageDef,
        mouthOpen: Boolean,
    ) = botwoonSprite.renderComposition(romParser, definition, palette, mouthOpen)
    fun renderBotwoonComponent(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.BotwoonSpritemap.ComponentDef,
        palette: com.supermetroid.editor.rom.BotwoonSpritemap.PaletteStageDef,
    ) = botwoonSprite.renderComponent(romParser, definition, palette)
    fun renderBotwoonSwimAnimation(
        romParser: RomParser,
        direction: com.supermetroid.editor.rom.BotwoonSpritemap.DirectionDef,
        palette: com.supermetroid.editor.rom.BotwoonSpritemap.PaletteStageDef,
    ) = botwoonSprite.renderSwimAnimation(romParser, direction, palette)
    fun renderBotwoonSpitAnimation(
        romParser: RomParser,
        direction: com.supermetroid.editor.rom.BotwoonSpritemap.DirectionDef,
        palette: com.supermetroid.editor.rom.BotwoonSpritemap.PaletteStageDef,
    ) = botwoonSprite.renderSpitAnimation(romParser, direction, palette)
    fun renderBotwoonSpitProjectileAnimation(
        romParser: RomParser,
        palette: com.supermetroid.editor.rom.BotwoonSpritemap.PaletteStageDef,
    ) = botwoonSprite.renderSpitProjectileAnimation(romParser, palette)
    fun loadBotwoonSource(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.BotwoonSpritemap.PixelSourceDef,
        palette: com.supermetroid.editor.rom.BotwoonSpritemap.PaletteStageDef,
    ) = botwoonSprite.loadSourceSheet(romParser, definition, palette)
    fun renderEditedBotwoonCompositions(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: com.supermetroid.editor.rom.BotwoonSpritemap.PaletteStageDef,
    ) = botwoonSprite.renderEditedCompositions(romParser, pixels, width, height, palette)
    fun applyBotwoonObjSheetEdits(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: com.supermetroid.editor.rom.BotwoonSpritemap.PaletteStageDef,
    ) {
        val colors = botwoonSprite.getEditingPalette(romParser, palette) ?: return
        enemySprite.applyEnemyTileSheetEdits(
            romParser,
            com.supermetroid.editor.rom.BotwoonSpritemap.SPECIES_ID,
            pixels,
            width,
            height,
            paletteOverride = colors,
        )
        botwoonSprite.invalidate()
    }
    fun hasCustomBotwoonObjSheet() =
        enemySprite.hasCustomEnemyTiles(com.supermetroid.editor.rom.BotwoonSpritemap.SPECIES_ID)
    fun resetBotwoonObjSheet() {
        enemySprite.resetEnemyTiles(com.supermetroid.editor.rom.BotwoonSpritemap.SPECIES_ID)
        botwoonSprite.invalidate()
    }

    // ── Torizo sprite delegates ────────────────────────────────────────

    fun renderTorizoComposition(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.TorizoSpritemap.CompositionDef,
        palette: com.supermetroid.editor.rom.TorizoSpritemap.PaletteStageDef,
    ) = torizoSprite.renderComposition(romParser, definition, palette)
    fun renderTorizoComponent(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.TorizoSpritemap.ComponentDef,
        palette: com.supermetroid.editor.rom.TorizoSpritemap.PaletteStageDef,
    ) = torizoSprite.renderComponent(romParser, definition, palette)
    fun renderTorizoAnimation(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.TorizoSpritemap.AnimationDef,
        palette: com.supermetroid.editor.rom.TorizoSpritemap.PaletteStageDef,
    ) = torizoSprite.renderAnimation(romParser, definition, palette)
    fun renderTorizoProjectileAnimation(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.TorizoSpritemap.ProjectileAnimationDef,
        palette: com.supermetroid.editor.rom.TorizoSpritemap.PaletteStageDef,
    ) = torizoSprite.renderProjectileAnimation(romParser, definition, palette)
    fun loadTorizoSource(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.TorizoSpritemap.PixelSourceDef,
        palette: com.supermetroid.editor.rom.TorizoSpritemap.PaletteStageDef,
    ) = torizoSprite.loadSourceSheet(romParser, definition, palette)
    fun renderEditedTorizoCompositions(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: com.supermetroid.editor.rom.TorizoSpritemap.PaletteStageDef,
    ) = torizoSprite.renderEditedCompositions(romParser, pixels, width, height, palette)
    fun applyTorizoObjSheetEdits(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: com.supermetroid.editor.rom.TorizoSpritemap.PaletteStageDef,
    ) {
        val colors = torizoSprite.getEditingPalette(romParser, palette) ?: return
        enemySprite.applyEnemyTileSheetEdits(
            romParser,
            com.supermetroid.editor.rom.TorizoSpritemap.BOMB_SPECIES_ID,
            pixels,
            width,
            height,
            paletteOverride = colors,
        )
        torizoSprite.invalidate()
    }
    fun hasCustomTorizoObjSheet() =
        enemySprite.hasCustomEnemyTiles(com.supermetroid.editor.rom.TorizoSpritemap.BOMB_SPECIES_ID)
    fun resetTorizoObjSheet() {
        enemySprite.resetEnemyTiles(com.supermetroid.editor.rom.TorizoSpritemap.BOMB_SPECIES_ID)
        torizoSprite.invalidate()
    }

    // ── Mother Brain sprite delegates ──────────────────────────────────

    fun renderMotherBrainComposition(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.MotherBrainSpritemap.CompositionDef,
        palette: com.supermetroid.editor.rom.MotherBrainSpritemap.PaletteStageDef,
    ) = motherBrainSprite.renderComposition(romParser, definition, palette)
    fun renderMotherBrainAnimation(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.MotherBrainSpritemap.AnimationDef,
        palette: com.supermetroid.editor.rom.MotherBrainSpritemap.PaletteStageDef,
    ) = motherBrainSprite.renderAnimation(romParser, definition, palette)
    fun renderMotherBrainHead(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.MotherBrainSpritemap.HeadDef,
        palette: com.supermetroid.editor.rom.MotherBrainSpritemap.PaletteStageDef,
    ) = motherBrainSprite.renderHead(romParser, definition, palette)
    fun renderMotherBrainBody(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.MotherBrainSpritemap.BodyDef,
        palette: com.supermetroid.editor.rom.MotherBrainSpritemap.PaletteStageDef,
    ) = motherBrainSprite.renderBody(romParser, definition, palette)
    fun renderMotherBrainNeck(
        romParser: RomParser,
        palette: com.supermetroid.editor.rom.MotherBrainSpritemap.PaletteStageDef,
    ) = motherBrainSprite.renderNeck(romParser, palette)
    fun loadMotherBrainHeadSource(romParser: RomParser) = motherBrainSprite.loadHeadSourceSheet(romParser)
    fun loadMotherBrainBodySource(romParser: RomParser) = motherBrainSprite.loadBodySourceSheet(romParser)
    fun renderEditedMotherBrainHeadCompositions(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: com.supermetroid.editor.rom.MotherBrainSpritemap.PaletteStageDef,
    ) = motherBrainSprite.renderEditedHeadCompositions(romParser, pixels, width, height, palette)
    fun applyMotherBrainHeadSourceEdits(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
    ) {
        val palette = motherBrainSprite.getSpritemap(romParser)?.readPalette() ?: return
        enemySprite.applyEnemyTileSheetEdits(
            romParser,
            com.supermetroid.editor.rom.MotherBrainSpritemap.HEAD_SPECIES_ID,
            pixels,
            width,
            height,
            paletteOverride = palette,
        )
        motherBrainSprite.invalidate()
    }
    fun hasCustomMotherBrainHeadSource() =
        enemySprite.hasCustomEnemyTiles(com.supermetroid.editor.rom.MotherBrainSpritemap.HEAD_SPECIES_ID)
    fun resetMotherBrainHeadSource() {
        enemySprite.resetEnemyTiles(com.supermetroid.editor.rom.MotherBrainSpritemap.HEAD_SPECIES_ID)
        motherBrainSprite.invalidate()
    }

    // ── Ridley sprite delegates ─────────────────────────────────────────

    fun renderRidleyComposition(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.RidleySpritemap.CompositionDef,
        palette: com.supermetroid.editor.rom.RidleySpritemap.PaletteStageDef,
    ) = ridleySprite.renderComposition(romParser, definition, palette)
    fun renderRidleyAnimation(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.RidleySpritemap.AnimationDef,
        palette: com.supermetroid.editor.rom.RidleySpritemap.PaletteStageDef,
    ) = ridleySprite.renderAnimation(romParser, definition, palette)
    fun renderRidleyBody(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.RidleySpritemap.BodyDef,
        palette: com.supermetroid.editor.rom.RidleySpritemap.PaletteStageDef,
    ) = ridleySprite.renderBody(romParser, definition, palette)
    fun renderRidleyWing(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.RidleySpritemap.WingDef,
        palette: com.supermetroid.editor.rom.RidleySpritemap.PaletteStageDef,
    ) = ridleySprite.renderWing(romParser, definition, palette)
    fun renderRidleyTail(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.RidleySpritemap.OamComponentDef,
        palette: com.supermetroid.editor.rom.RidleySpritemap.PaletteStageDef,
    ) = ridleySprite.renderTail(romParser, definition, palette)
    fun loadRidleyBaseSource(romParser: RomParser) = ridleySprite.loadBaseSource(romParser)
    fun loadRidleyRuntimeSource(
        romParser: RomParser,
        definition: com.supermetroid.editor.rom.RidleySpritemap.RuntimeSourceDef,
    ) = ridleySprite.loadRuntimeSource(romParser, definition)
    fun renderEditedRidleyCompositions(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: com.supermetroid.editor.rom.RidleySpritemap.PaletteStageDef,
    ) = ridleySprite.renderEditedCompositions(romParser, pixels, width, height, palette)
    fun applyRidleySourceEdits(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
    ) {
        val palette = ridleySprite.getSpritemap(romParser)?.readPalette() ?: return
        enemySprite.applyEnemyTileSheetEdits(
            romParser,
            com.supermetroid.editor.rom.RidleySpritemap.RIDLEY_SPECIES_ID,
            pixels,
            width,
            height,
            paletteOverride = palette,
        )
        ridleySprite.invalidate()
    }
    fun hasCustomRidleySource() =
        enemySprite.hasCustomEnemyTiles(com.supermetroid.editor.rom.RidleySpritemap.RIDLEY_SPECIES_ID)
    fun resetRidleySource() {
        enemySprite.resetEnemyTiles(com.supermetroid.editor.rom.RidleySpritemap.RIDLEY_SPECIES_ID)
        ridleySprite.invalidate()
    }

    // ── Kraid sprite delegates ────────────────────────────────────────────

    fun getKraidSpritemap(romParser: RomParser) = kraidSprite.getSpritemap(romParser)
    fun renderKraidFullBody(
        romParser: RomParser,
        head: com.supermetroid.editor.rom.KraidSpritemap.HeadTilemapDef =
            com.supermetroid.editor.rom.KraidSpritemap.HEAD_TILEMAPS.first(),
        paletteStage: com.supermetroid.editor.rom.KraidSpritemap.PaletteStageDef =
            com.supermetroid.editor.rom.KraidSpritemap.PALETTE_STAGES.first { it.key == "health-8" },
    ) = kraidSprite.renderFullBody(romParser, head, paletteStage)
    fun renderKraidFullBodyAnimation(
        romParser: RomParser,
        def: com.supermetroid.editor.rom.KraidSpritemap.HeadSequenceDef,
        paletteStage: com.supermetroid.editor.rom.KraidSpritemap.PaletteStageDef,
    ) = kraidSprite.renderFullBodyAnimation(romParser, def, paletteStage)
    fun renderKraidOamAnimation(
        romParser: RomParser,
        def: com.supermetroid.editor.rom.KraidSpritemap.OamSequenceDef,
        paletteStage: com.supermetroid.editor.rom.KraidSpritemap.PaletteStageDef,
    ): com.supermetroid.editor.rom.SpriteAnimation? {
        val tileData = loadEnemyTileData(romParser, def.speciesId) ?: return null
        return kraidSprite.renderOamAnimation(romParser, def, tileData, paletteStage)
    }
    fun renderKraidHeadTilemap(romParser: RomParser, def: com.supermetroid.editor.rom.KraidSpritemap.HeadTilemapDef) = kraidSprite.renderHeadTilemap(romParser, def)
    fun applyKraidHeadEdits(romParser: RomParser, sprite: com.supermetroid.editor.rom.KraidSpritemap.AssembledSprite, editedPixels: IntArray) = kraidSprite.applyHeadEdits(romParser, sprite, editedPixels)
    fun getKraidPalette(romParser: RomParser) = kraidSprite.getPalette(romParser)
    fun hasCustomKraidComponents() = kraidSprite.hasCustomComponents()
    fun hasCustomKraidTileSheet() = kraidSprite.hasCustomTileSheet()
    fun resetKraidTileSheet() = kraidSprite.resetTileSheet()


    fun selectEditorMetatile(index: Int) {
        editorSelectedMetatile = index
    }

    /** Get the effective default for a metatile: project override > hardcoded > learned > solid. */
    fun getEffectiveTileDefault(tilesetId: Int, metatileIndex: Int): TileDefault {
        val projectOverride = project.getTileDefault(tilesetId, metatileIndex)
        if (projectOverride != null) return TileDefault(projectOverride.blockType, projectOverride.bts)
        val hardcoded = TilesetDefaults.get(metatileIndex)
        if (hardcoded != null) return hardcoded
        val learned = metatileBlockTypePresets[metatileIndex]
        if (learned != null) return TileDefault(learned)
        return TileDefault(0x0) // air by default (no assumption)
    }

    /** Set a project-level tile default override. */
    fun setTileDefault(tilesetId: Int, metatileIndex: Int, blockType: Int, bts: Int) {
        project.setTileDefault(tilesetId, metatileIndex, blockType, bts)
        dirty = true
    }

    /** Remove a project-level tile default override (revert to hardcoded/learned). */
    fun clearTileDefault(tilesetId: Int, metatileIndex: Int) {
        project.removeTileDefault(tilesetId, metatileIndex)
        dirty = true
    }

    /** Check if there's a project override for this metatile. */
    fun hasProjectOverride(tilesetId: Int, metatileIndex: Int): Boolean =
        project.getTileDefault(tilesetId, metatileIndex) != null

    // ─── Patch management ───────────────────────────────────────

    /** Currently selected patch in the patch editor. */
    var selectedPatchId by mutableStateOf<String?>(null)
        private set

    /** Compose-observable version counter for patch list changes. */
    var patchVersion by mutableStateOf(0)
        private set

    /** Application-wide favorites, persisted immediately rather than with a project save. */
    val favoritePatchIds: Set<String>
        get() = patchFavoriteStore.favoriteIds

    /** Compose-observable version counter for saved music edits. */
    var musicEditVersion by mutableStateOf(0)
        private set

    fun musicEditKey(songSet: Int, playIndex: Int): String = MusicTrackEdit.key(songSet, playIndex)

    fun hasMusicEdit(songSet: Int, playIndex: Int): Boolean =
        project.musicEdits.containsKey(musicEditKey(songSet, playIndex))

    fun getMusicEdit(songSet: Int, playIndex: Int): MusicTrackEdit? =
        project.musicEdits[musicEditKey(songSet, playIndex)]

    fun setMusicEdit(key: String, edit: MusicTrackEdit) {
        project.musicEdits[key] = edit
        dirty = true
        musicEditVersion++
    }

    fun removeMusicEdit(key: String): Boolean {
        val removed = project.musicEdits.remove(key) != null
        if (removed) {
            dirty = true
            musicEditVersion++
        }
        return removed
    }

    fun selectPatch(id: String?) { selectedPatchId = id }

    fun addPatch(name: String, description: String = "", enabled: Boolean = false): SmPatch {
        val id = java.util.UUID.randomUUID().toString().take(8)
        val patch = SmPatch(id = id, name = name, description = description, enabled = enabled)
        project.patches.add(patch)
        dirty = true; patchVersion++
        selectedPatchId = id
        return patch
    }

    fun removePatch(id: String) {
        if (isSystemPatch(id)) return
        project.patches.removeAll { it.id == id }
        patchFavoriteStore.remove(id, project.generalSettings.patchBrowser.favoritePatchIds)
        if (selectedPatchId == id) selectedPatchId = null
        dirty = true; patchVersion++
    }

    fun isPatchFavorite(id: String): Boolean =
        id in favoritePatchIds

    fun togglePatchFavorite(id: String) {
        if (project.patches.none { it.id == id }) return
        patchFavoriteStore.toggle(id, project.generalSettings.patchBrowser.favoritePatchIds)
        patchVersion++
    }

    fun setPatchSortOrder(sortOrder: PatchSortOrder) {
        val settings = project.generalSettings.patchBrowser
        if (settings.sortOrder == sortOrder) return
        settings.sortOrder = sortOrder
        dirty = true
        patchVersion++
    }

    fun setPatchFavoritesFirst(enabled: Boolean) {
        val settings = project.generalSettings.patchBrowser
        if (settings.favoritesFirst == enabled) return
        settings.favoritesFirst = enabled
        dirty = true
        patchVersion++
    }

    fun togglePatch(id: String) {
        val patch = project.patches.find { it.id == id } ?: return
        runCatching {
            setPatchEnabled(patch, !patch.enabled)
            dirty = true; patchVersion++
        }.onFailure { postStatus("Patch toggle blocked: ${it.message}") }
    }

    fun updatePatch(id: String, name: String? = null, description: String? = null, enabled: Boolean? = null) {
        val patch = project.patches.find { it.id == id } ?: return
        if (name != null) patch.name = name
        if (description != null) patch.description = description
        if (enabled != null) setPatchEnabled(patch, enabled)
        dirty = true; patchVersion++
    }

    private fun setPatchEnabled(patch: SmPatch, enabled: Boolean) {
        // Preflight before either backend changes source, snapshots, or ownership.
        if (!enabled && patch.enabled) {
            patch.enabled = false
            val dependency = try {
                RomValidator.checkProjectCustomItemDependencies(project)
                    .firstOrNull { it.severity == RomValidator.Severity.ERROR }
            } finally { patch.enabled = true }
            if (dependency != null) {
                val message = "Cannot disable '${patch.name}': ${dependency.message}"
                editorLog("WARN: $message")
                postStatus(message)
                return
            }
        }
        if (asmNavigationEnabled && patch.configType in com.supermetroid.editor.asm.NATIVE_PATCH_CONFIG_TYPES) {
            if (enabled == patch.enabled && (!enabled || patch.configType !in project.asmWorkspace.disabledPatchValues)) return
            requireNotNull(patchToggleController) { "Wait for patch source indexing." }.setEnabled(patch, enabled)
            return
        }
        if (enabled) {
            patch.exclusiveGroup?.let { group ->
                project.patches.forEach { other ->
                    if (other.id != patch.id && other.exclusiveGroup == group) {
                        other.enabled = false
                    }
                }
            }
        }
        patch.enabled = enabled
    }

    fun enabledCustomItems(): List<CustomItemDef> =
        project.patches
            .asSequence()
            .filter { it.enabled }
            .flatMap { it.customItems.asSequence() }
            .map { it.copy() }
            .toList()

    data class CustomItemPlmDef(
        val item: CustomItemDef,
        val variant: String,
        val plmId: Int,
    )

    fun enabledCustomItemPlms(): List<CustomItemPlmDef> =
        enabledCustomItems().flatMap { item ->
            buildList {
                item.visiblePlmId?.let { add(CustomItemPlmDef(item, "Visible", it)) }
                item.chozoPlmId?.let { add(CustomItemPlmDef(item, "Chozo", it)) }
                item.hiddenPlmId?.let { add(CustomItemPlmDef(item, "Hidden", it)) }
            }
        }

    fun customItemPlmDef(plmId: Int): CustomItemPlmDef? =
        enabledCustomItemPlms().firstOrNull { it.plmId == plmId }

    fun customItemNameForPlm(plmId: Int): String? =
        customItemPlmDef(plmId)?.let { "${it.item.name} (${it.variant})" }

    fun isCustomItemPlm(plmId: Int): Boolean = customItemPlmDef(plmId) != null

    fun isEditorItemPlm(plmId: Int): Boolean = RomParser.isItemPlm(plmId) || isCustomItemPlm(plmId)

    fun setPatchWrites(id: String, writes: List<SmPatchWrite>) {
        val patch = project.patches.find { it.id == id } ?: return
        patch.writes.clear()
        patch.writes.addAll(writes.map { PatchWrite(it.offset, it.bytes) })
        dirty = true; patchVersion++
    }

    fun setPatchConfigValue(id: String, value: Int) {
        val patch = project.patches.find { it.id == id }
        if (patch?.configType in com.supermetroid.editor.asm.DATA_PATCH_TYPES) {
            setNativePatchValues(id, mapOf("seconds" to value)); return
        }
        project.patches.find { it.id == id }?.let {
            it.configValue = value
            if (it.configType != null) setPatchEnabled(it, true)
        }
        dirty = true; patchVersion++
    }

    fun setPatchConfigData(id: String, key: String, value: Int) {
        val patch = project.patches.find { it.id == id } ?: return
        if (patch.configType in com.supermetroid.editor.asm.DATA_PATCH_TYPES) {
            setNativePatchValues(id, mapOf(key to value)); return
        }
        if (usesRoomAsmAuthoring && patch.configType == com.supermetroid.editor.rom.EnvironmentalDamagePatch.CONFIG_TYPE) {
            runCatching { requireNotNull(environmentalDamageBridge) { "Wait for environmental source indexing." }.edit(key, value) }
                .onFailure { postStatus("Environmental edit blocked: ${it.message}") }
            return
        }
        if (project.asmWorkspace.enabled && project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE && patch.configType == "beam_damage") {
            runCatching { requireNotNull(beamDamageBridge) { "Wait for the beam ASM workspace." }.edit(key, value) }
                .onFailure { postStatus("Beam edit blocked: ${it.message}") }
            return
        }
        if (project.asmWorkspace.enabled && project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE &&
            patch.configType in com.supermetroid.editor.asm.ENEMY_TUNING_CONFIG_TYPES) {
            runCatching { requireNotNull(enemyTuningBridge) { "Wait for the enemy ASM workspace." }.edit(key, value) }
                .onFailure { postStatus("Enemy edit blocked: ${it.message}") }
            return
        }
        if (project.asmWorkspace.enabled && project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE && patch.configType == "boss_stats") {
            runCatching { requireNotNull(bossStatsBridge) { "Wait for the boss stats ASM workspace." }.edit(key, value) }
                .onFailure { postStatus("Boss stats edit blocked: ${it.message}") }
            return
        }
        val data = patch.configData ?: mutableMapOf()
        data[key] = value
        patch.configData = data
        if (patch.configType != null) setPatchEnabled(patch, true)
        dirty = true; patchVersion++
    }

    internal fun setNativePatchValues(id: String, values: Map<String, Int>) {
        val patch = project.patches.find { it.id == id } ?: return
        runCatching {
            if (asmNavigationEnabled) {
                requireNotNull(dataPatchBridge) { "Wait for patch source indexing." }.editValues(requireNotNull(patch.configType), values)
            } else {
                val definitions = com.supermetroid.editor.asm.DATA_PATCH_DEFINITIONS.filter { it.type == patch.configType }.associateBy { it.key }
                val current = definitions.mapValues { nativePatchRomView(patch, it.value, currentRomParser) }
                values.forEach { (key, value) -> definitions.getValue(key).encode(value); require(current.getValue(key).problem == null) { current.getValue(key).problem.orEmpty() } }
                if (values.all { (key, value) -> current.getValue(key).value == value }) return
                val changes = values.toMutableMap()
                if (patch.configType == com.supermetroid.editor.rom.ControllerBindings.CONFIG_TYPE) {
                    changes.putAll(com.supermetroid.editor.rom.ControllerBindings.resolve(current.mapValues { requireNotNull(it.value.value) }, values))
                }
                if (patch.configType == BOMB_CONFIG_TYPE && BOMB_MAX_ACTIVE_KEY in changes && BOMB_COOLDOWN_FRAMES_KEY !in changes) {
                    changes[BOMB_COOLDOWN_FRAMES_KEY] = calculateBombCooldownForConfig(changes.getValue(BOMB_MAX_ACTIVE_KEY),
                        changes[BOMB_FUSE_FRAMES_KEY] ?: requireNotNull(current[BOMB_FUSE_FRAMES_KEY]?.value), requireNotNull(current[BOMB_COOLDOWN_FRAMES_KEY]?.value))
                }
                if (patch.configType in setOf("ceres_escape_seconds", ZEBES_ESCAPE_CONFIG_TYPE)) patch.configValue = changes.getValue("seconds")
                else patch.configData = (patch.configData.orEmpty() + changes).toMutableMap()
                setPatchEnabled(patch, true); dirty = true; patchVersion++
            }
        }.onFailure { postStatus("Patch edit blocked: ${it.message}") }
    }

    internal fun openDataPatchSource(type: String, key: String? = null): Boolean = runCatching {
        require(asmNavigationEnabled) { "Enable the project ASM source workspace first." }
        val bridge = requireNotNull(dataPatchBridge) { "Wait for patch source indexing." }
        val (file, line) = bridge.line(type, key)
        bridge.workspace.showProjectWorkspace(); bridge.workspace.openSource(file, line); true
    }.getOrElse { postStatus("Patch source unavailable: ${it.message}"); false }

    internal fun resetNativePatchToOriginal(id: String) {
        val patch = project.patches.find { it.id == id } ?: return
        runCatching {
            if (asmNavigationEnabled) requireNotNull(dataPatchBridge) { "Wait for patch source indexing." }.reset(requireNotNull(patch.configType))
            else { patch.configData = null; patch.configValue = null; patch.enabled = true; dirty = true; patchVersion++ }
        }.onFailure { postStatus("Patch edit blocked: ${it.message}") }
    }

    fun normalizedRoomNameOverrideKey(roomIdText: String): String? {
        val trimmed = roomIdText.trim()
        val hex = when {
            trimmed.startsWith("0x", ignoreCase = true) -> trimmed.drop(2)
            trimmed.startsWith('$') -> trimmed.drop(1)
            else -> trimmed
        }
        val roomId = hex.toIntOrNull(16)?.takeIf { it in 0x0000..0xFFFF } ?: return null
        return roomId.toString(16).uppercase().padStart(4, '0')
    }

    fun setRoomNameOverride(roomIdText: String, roomName: String, defaultName: String? = null): Boolean {
        val key = normalizedRoomNameOverrideKey(roomIdText) ?: return false
        val cleaned = roomName.replace('\r', ' ').replace('\n', ' ')
        val matchingKeys = project.roomNameOverrides.keys
            .filter { normalizedRoomNameOverrideKey(it) == key }
        val shouldRemove = (defaultName == null && cleaned.isBlank()) ||
            (defaultName != null && cleaned == defaultName)

        var changed = false
        for (existingKey in matchingKeys) {
            if (existingKey != key || shouldRemove) {
                project.roomNameOverrides.remove(existingKey)
                changed = true
            }
        }

        if (!shouldRemove) {
            if (project.roomNameOverrides[key] != cleaned) {
                project.roomNameOverrides[key] = cleaned
                changed = true
            }
        }

        if (changed) {
            dirty = true
            patchVersion++
        }
        return true
    }

    fun removeRoomNameOverride(roomIdText: String): Boolean {
        val key = normalizedRoomNameOverrideKey(roomIdText) ?: return false
        val matchingKeys = project.roomNameOverrides.keys
            .filter { normalizedRoomNameOverrideKey(it) == key }
        if (matchingKeys.isEmpty()) return true
        for (existingKey in matchingKeys) {
            project.roomNameOverrides.remove(existingKey)
        }
        dirty = true
        patchVersion++
        return true
    }

    // ─── Pattern management ──────────────────────────────────────

    var patternVersion by mutableStateOf(0)
        private set
    var selectedPatternId by mutableStateOf<String?>(null)
        private set
    var activePattern by mutableStateOf<TilePattern?>(null)
        private set

    fun selectPattern(id: String?) {
        selectedPatternId = id
        activePattern = if (id != null) project.patterns.find { it.id == id } else null
    }

    fun addPattern(name: String, cols: Int, rows: Int, tilesetId: Int? = null): TilePattern {
        val id = "pattern_${System.currentTimeMillis()}"
        val cells = MutableList<PatternCell?>(rows * cols) { null }
        val pattern = TilePattern(id, name, cols, rows, tilesetId, cells)
        project.patterns.add(pattern)
        dirty = true; patternVersion++
        if (!testMode) PatternLibrary.saveAll(project.patterns)
        return pattern
    }

    fun removePattern(id: String) {
        project.patterns.removeAll { it.id == id }
        if (selectedPatternId == id) selectPattern(null)
        dirty = true; patternVersion++
        if (!testMode) PatternLibrary.saveAll(project.patterns)
    }

    fun updatePatternCell(patternId: String, r: Int, c: Int, cell: PatternCell) {
        project.patterns.find { it.id == patternId }?.setCell(r, c, cell)
        dirty = true; patternVersion++
    }

    fun renamePattern(id: String, name: String) {
        project.patterns.find { it.id == id }?.name = name
        dirty = true; patternVersion++
    }

    /** Convert a pattern to a TileBrush for placement on the map. */
    fun patternToBrush(pattern: TilePattern, hFlip: Boolean = false, vFlip: Boolean = false): TileBrush {
        val skip = mutableSetOf<Long>()
        val tiles = List(pattern.rows) { r ->
            List(pattern.cols) { c ->
                val cell = pattern.getCell(r, c)
                if (cell == null) {
                    skip.add((r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL))
                    0
                } else cell.metatile
            }
        }
        val btOverrides = mutableMapOf<Long, Int>()
        val btsOverrides = mutableMapOf<Long, Int>()
        val flipOverrides = mutableMapOf<Long, Int>()
        val plmOverrides = mutableMapOf<Long, Pair<Int, Int>>()
        for (r in 0 until pattern.rows) for (c in 0 until pattern.cols) {
            val key = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
            val cell = pattern.getCell(r, c) ?: continue
            btOverrides[key] = cell.blockType
            if (cell.bts != 0) btsOverrides[key] = cell.bts
            val flipBits = (if (cell.hFlip) 1 else 0) or (if (cell.vFlip) 2 else 0)
            if (flipBits != 0) flipOverrides[key] = flipBits
            if (cell.plmId != 0) plmOverrides[key] = Pair(cell.plmId, cell.plmParam)
        }
        return TileBrush(tiles, blockType = 0x8, hFlip = hFlip, vFlip = vFlip,
            blockTypeOverrides = btOverrides, btsOverrides = btsOverrides,
            flipOverrides = flipOverrides, plmOverrides = plmOverrides,
            skipCells = skip)
    }

    fun selectAndApplyPattern(id: String) {
        val pattern = project.patterns.find { it.id == id } ?: return
        selectedPatternId = id
        activePattern = pattern
        brush = patternToBrush(pattern)
        activeTool = EditorTool.PAINT
    }

    /** Patterns visible for the current tileset: CRE patterns + current tileset patterns. */
    fun patternsForTileset(tilesetId: Int): List<TilePattern> {
        return project.patterns.filter { it.tilesetId == null || it.tilesetId == tilesetId }
    }

    // ─── Pattern editing (mini room editor for patterns) ────────────

    data class PatternEdit(val r: Int, val c: Int, val old: PatternCell?, val new: PatternCell?)
    data class PatternOperation(val edits: List<PatternEdit>)

    val patternUndoStack = mutableListOf<PatternOperation>()
    val patternRedoStack = mutableListOf<PatternOperation>()
    private val pendingPatEdits = mutableListOf<PatternEdit>()
    private val pendingPatPositions = mutableSetOf<Long>()

    var patternEditVersion by mutableStateOf(0)
        private set
    var patUndoVersion by mutableStateOf(0)
        private set
    var patHoverX by mutableStateOf(-1)
    var patHoverY by mutableStateOf(-1)

    fun loadPatternForEdit(patternId: String) {
        val pat = project.patterns.find { it.id == patternId } ?: return
        selectedPatternId = patternId
        activePattern = pat
        patternUndoStack.clear()
        patternRedoStack.clear()
        patternEditVersion++; patUndoVersion++
    }

    fun resizePattern(patternId: String, newCols: Int, newRows: Int) {
        val pat = project.patterns.find { it.id == patternId } ?: return
        if (newCols == pat.cols && newRows == pat.rows) return
        val newCells = MutableList<PatternCell?>(newRows * newCols) { idx ->
            val r = idx / newCols; val c = idx % newCols
            if (r < pat.rows && c < pat.cols) pat.getCell(r, c) else null
        }
        val idx = project.patterns.indexOfFirst { it.id == patternId }
        if (idx >= 0) {
            project.patterns[idx] = pat.copy(cols = newCols, rows = newRows, cells = newCells)
            activePattern = project.patterns[idx]
        }
        patternUndoStack.clear(); patternRedoStack.clear()
        dirty = true; patternVersion++; patternEditVersion++; patUndoVersion++
    }

    fun patBeginStroke() {
        pendingPatEdits.clear(); pendingPatPositions.clear()
    }

    fun patPaintAt(bx: Int, by: Int): Boolean {
        val pat = activePattern ?: return false
        val b = brush ?: return false
        var changed = false
        for (r in 0 until b.rows) {
            for (c in 0 until b.cols) {
                val tx = bx + if (b.hFlip) (b.cols - 1 - c) else c
                val ty = by + if (b.vFlip) (b.rows - 1 - r) else r
                if (tx < 0 || ty < 0 || tx >= pat.cols || ty >= pat.rows) continue
                val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
                if (pendingPatPositions.contains(key)) continue
                val oldCell = pat.getCell(ty, tx)
                val word = b.blockWordAt(r, c)
                val mt = word and 0x3FF
                val hf = (word shr 10) and 1 != 0
                val vf = (word shr 11) and 1 != 0
                val bt = (word shr 12) and 0xF
                val bts = b.btsAt(r, c)
                val newCell = PatternCell(mt, bt, bts, hf, vf)
                if (oldCell == newCell) continue
                pat.setCell(ty, tx, newCell)
                pendingPatEdits.add(PatternEdit(ty, tx, oldCell, newCell))
                pendingPatPositions.add(key)
                changed = true
            }
        }
        if (changed) patternEditVersion++
        return changed
    }

    fun patEraseAt(bx: Int, by: Int): Boolean {
        val pat = activePattern ?: return false
        if (bx < 0 || by < 0 || bx >= pat.cols || by >= pat.rows) return false
        val key = (bx.toLong() shl 32) or (by.toLong() and 0xFFFFFFFFL)
        if (pendingPatPositions.contains(key)) return false
        val oldCell = pat.getCell(by, bx) ?: return false
        pat.setCell(by, bx, null)
        pendingPatEdits.add(PatternEdit(by, bx, oldCell, null))
        pendingPatPositions.add(key)
        patternEditVersion++
        return true
    }

    fun patEndStroke() {
        if (pendingPatEdits.isEmpty()) return
        val op = PatternOperation(pendingPatEdits.toList())
        patternUndoStack.add(op)
        patternRedoStack.clear()
        dirty = true; patternVersion++; patUndoVersion++
        pendingPatEdits.clear(); pendingPatPositions.clear()
    }

    fun patFloodFill(bx: Int, by: Int) {
        val pat = activePattern ?: return
        val b = brush ?: return
        if (b.rows != 1 || b.cols != 1) return
        if (bx < 0 || by < 0 || bx >= pat.cols || by >= pat.rows) return
        val targetCell = pat.getCell(by, bx)
        val word = b.blockWordAt(0, 0)
        val mt = word and 0x3FF; val hf = (word shr 10) and 1 != 0
        val vf = (word shr 11) and 1 != 0; val bt = (word shr 12) and 0xF
        val bts = b.btsAt(0, 0)
        val fillCell = PatternCell(mt, bt, bts, hf, vf)
        if (targetCell == fillCell) return
        val edits = mutableListOf<PatternEdit>()
        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(Pair(bx, by))
        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.removeFirst()
            val k = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
            if (k in visited) continue
            if (cx < 0 || cy < 0 || cx >= pat.cols || cy >= pat.rows) continue
            val cell = pat.getCell(cy, cx)
            if (cell != targetCell) continue
            visited.add(k)
            edits.add(PatternEdit(cy, cx, cell, fillCell))
            pat.setCell(cy, cx, fillCell)
            queue.add(Pair(cx - 1, cy)); queue.add(Pair(cx + 1, cy))
            queue.add(Pair(cx, cy - 1)); queue.add(Pair(cx, cy + 1))
        }
        if (edits.isNotEmpty()) {
            patternUndoStack.add(PatternOperation(edits))
            patternRedoStack.clear()
            dirty = true; patternVersion++; patternEditVersion++; patUndoVersion++
        }
    }

    fun patSampleTile(bx: Int, by: Int) {
        val pat = activePattern ?: return
        if (bx < 0 || by < 0 || bx >= pat.cols || by >= pat.rows) return
        val cell = pat.getCell(by, bx) ?: return
        val btsMap = if (cell.bts != 0) mapOf(0L to cell.bts) else emptyMap()
        val flipBits = (if (cell.hFlip) 1 else 0) or (if (cell.vFlip) 2 else 0)
        val flipMap = if (flipBits != 0) mapOf(0L to flipBits) else emptyMap()
        brush = TileBrush(
            tiles = listOf(listOf(cell.metatile)),
            blockType = cell.blockType,
            btsOverrides = btsMap,
            flipOverrides = flipMap
        )
        tilesetSelStart = Pair(cell.metatile % 32, cell.metatile / 32)
        tilesetSelEnd = tilesetSelStart
        activeTool = EditorTool.PAINT
    }

    fun patUndo() {
        if (patternUndoStack.isEmpty()) return
        val pat = activePattern ?: return
        val op = patternUndoStack.removeAt(patternUndoStack.lastIndex)
        for (edit in op.edits.reversed()) {
            pat.setCell(edit.r, edit.c, edit.old)
        }
        patternRedoStack.add(op)
        dirty = true; patternVersion++; patternEditVersion++; patUndoVersion++
    }

    fun patRedo(): Boolean {
        if (patternRedoStack.isEmpty()) return false
        val pat = activePattern ?: return false
        val op = patternRedoStack.removeAt(patternRedoStack.lastIndex)
        for (edit in op.edits) {
            pat.setCell(edit.r, edit.c, edit.new)
        }
        patternUndoStack.add(op)
        dirty = true; patternVersion++; patternEditVersion++; patUndoVersion++
        return true
    }

    fun patReadCell(bx: Int, by: Int): PatternCell? {
        val pat = activePattern ?: return null
        return pat.getCell(by, bx)
    }

    fun patCellWord(bx: Int, by: Int): Int {
        val cell = patReadCell(bx, by) ?: return 0
        var w = cell.metatile and 0x3FF
        if (cell.hFlip) w = w or (1 shl 10)
        if (cell.vFlip) w = w or (1 shl 11)
        w = w or ((cell.blockType and 0xF) shl 12)
        return w
    }

    fun patSetCellProperties(bx: Int, by: Int, blockType: Int, bts: Int) {
        val pat = activePattern ?: return
        val old = pat.getCell(by, bx) ?: PatternCell(0, blockType = 0)
        val newCell = old.copy(blockType = blockType, bts = bts)
        if (old == newCell) return
        pat.setCell(by, bx, newCell)
        val edit = PatternEdit(bx, by, old, newCell)
        val op = PatternOperation(listOf(edit))
        patternUndoStack.add(op)
        patternRedoStack.clear()
        patUndoVersion++
        dirty = true
        patternEditVersion++
    }

    fun patSetCellPlm(bx: Int, by: Int, plmId: Int, plmParam: Int) {
        val pat = activePattern ?: return
        val old = pat.getCell(by, bx) ?: PatternCell(0, blockType = 0)
        val newCell = old.copy(plmId = plmId, plmParam = plmParam)
        if (old == newCell) return
        pat.setCell(by, bx, newCell)
        val edit = PatternEdit(bx, by, old, newCell)
        val op = PatternOperation(listOf(edit))
        patternUndoStack.add(op)
        patternRedoStack.clear()
        patUndoVersion++
        dirty = true
        patternEditVersion++
    }

    fun patRemoveCellPlm(bx: Int, by: Int) {
        patSetCellPlm(bx, by, 0, 0)
    }

    /** Save the current map selection rectangle as a new pattern. */
    fun saveSelectionAsPattern(name: String, isCre: Boolean = true): TilePattern? {
        val s = mapSelStart ?: return null
        val e = mapSelEnd ?: return null
        val minX = minOf(s.first, e.first).coerceIn(0, workingBlocksWide - 1)
        val maxX = maxOf(s.first, e.first).coerceIn(0, workingBlocksWide - 1)
        val minY = minOf(s.second, e.second).coerceIn(0, workingBlocksTall - 1)
        val maxY = maxOf(s.second, e.second).coerceIn(0, workingBlocksTall - 1)
        val cols = maxX - minX + 1
        val rows = maxY - minY + 1
        val cells = mutableListOf<PatternCell?>()
        for (by in minY..maxY) {
            for (bx in minX..maxX) {
                val word = readBlockWord(bx, by)
                val metatile = word and 0x3FF
                val hFlip = (word shr 10) and 1 != 0
                val vFlip = (word shr 11) and 1 != 0
                val blockType = (word shr 12) and 0xF
                val bts = readBts(bx, by)
                val plmsAtTile = getPlmsAt(bx, by)
                val plm = plmsAtTile.firstOrNull()
                cells.add(PatternCell(metatile, blockType, bts, hFlip, vFlip,
                    plm?.id ?: 0, plm?.param ?: 0))
            }
        }
        val id = "pattern_${System.currentTimeMillis()}"
        val tsId = if (isCre) null else currentTilesetId
        val pat = TilePattern(id, name, cols, rows, tsId, cells)
        project.patterns.add(pat)
        dirty = true; patternVersion++
        mapSelStart = null; mapSelEnd = null
        return pat
    }

    /** Move the current selection preview without touching room tiles. */
    fun shiftSelection(dx: Int, dy: Int) {
        if (floatingSelection == null && mapSelStart != null && mapSelEnd != null) {
            beginFloatingSelectionFromMapSelection()
        }
        val floating = floatingSelection ?: return
        setFloatingSelectionPosition(floating.x + dx, floating.y + dy)
    }

    /**
     * Remove project tile-default overrides for tiles we've fixed in TilesetDefaults.
     * Lets the core config take effect so users don't need to manually clear overrides.
     */
    private fun migrateTileDefaultsToCore() {
        val fixedIndices = setOf(
            69, 70, 71, 72, 73, 89, 90, 91, 92, 95, 100, 101, 102,
            122, 123, 124, 125, 126, 127,
            150, 151, 152, 153, 154, 182, 184, 185, 186,
            187, 214, 215, 216, 217, 218, 219, 220
        )
        val keysToRemove = project.tileDefaults.keys.filter { key ->
            key.split(":").getOrNull(1)?.toIntOrNull() in fixedIndices
        }
        if (keysToRemove.isNotEmpty()) {
            keysToRemove.forEach { project.tileDefaults.remove(it) }
            dirty = true
        }
    }

    /**
     * Seed built-in patterns by extracting tile data from known vanilla ROM rooms.
     * Only adds patterns whose IDs don't already exist in the project.
     */
    fun seedBuiltInPatterns(romParser: RomParser?) {
        val builtInIds = listOf(
            "builtin_door_blue_left", "builtin_door_blue_right",
            "builtin_door_red_left", "builtin_door_red_right",
            "builtin_door_green_left", "builtin_door_green_right",
            "builtin_door_yellow_left", "builtin_door_yellow_right",
            "builtin_save_station", "builtin_energy_refill",
            "builtin_missile_refill", "builtin_chozo_statue"
        )

        // Remove all gate patterns — gates are better placed via BTS directly
        val gatePatternIds = setOf(
            "builtin_left_gate", "builtin_right_gate",
            "builtin_gate_blue_left", "builtin_gate_blue_right",
            "builtin_gate_pink_left", "builtin_gate_pink_right",
            "builtin_gate_green_left", "builtin_gate_green_right",
            "builtin_gate_yellow_left", "builtin_gate_yellow_right"
        )
        project.patterns.removeAll { it.id in gatePatternIds && it.builtIn }

        // Migrate old incorrect door patterns so they get re-seeded correctly.
        val wrongDoorRightIds = setOf(
            "builtin_door_blue_right", "builtin_door_red_right",
            "builtin_door_green_right", "builtin_door_yellow_right"
        )
        val wrongPlmIds = setOf(0xC8A6, 0xC88E, 0xC876, 0xC85E)
        project.patterns.removeAll { pat ->
            pat.id in wrongDoorRightIds && pat.builtIn &&
                pat.cells.any { it != null && it.plmId in wrongPlmIds }
        }

        // Migrate old placeholder station/chozo patterns (wrong dimensions or generic tiles)
        val stationChozoIds = setOf("builtin_energy_refill", "builtin_missile_refill", "builtin_chozo_statue")
        project.patterns.removeAll { pat ->
            pat.id in stationChozoIds && pat.builtIn && (pat.rows != 3 || pat.cols != 3)
        }

        // Migrate save station: updated to use CRE tiles 89/91 instead of 0xFF placeholders
        project.patterns.removeAll { pat ->
            pat.id == "builtin_save_station" && pat.builtIn &&
                (pat.cols != 2 || pat.rows != 5 || pat.cells.firstOrNull()?.metatile != 89)
        }

        // Migrate energy/missile refill patterns: wrong PLM param, non-null bottom corners,
        // or wrong block types (middle/bottom rows must be solid 0x8 to match vanilla)
        val refillIds = setOf("builtin_energy_refill", "builtin_missile_refill")
        project.patterns.removeAll { pat ->
            pat.id in refillIds && pat.builtIn && (
                pat.cells.any { it != null && it.plmParam == 0x8000 } ||
                (pat.cells.size == 9 && pat.cells[6] != null) ||
                (pat.cells.size == 9 && pat.cells[4]?.blockType == 0x0)
            )
        }

        val existing = project.patterns.map { it.id }.toSet()
        if (builtInIds.all { it in existing }) return

        if (romParser == null) return

        fun addBuiltIn(id: String, name: String, cols: Int, rows: Int, cells: List<PatternCell?>,
                       tilesetId: Int? = null, noFlip: Boolean = false) {
            if (id in existing) return
            val pat = TilePattern(id, name, cols, rows, tilesetId, cells.toMutableList(),
                builtIn = true, noFlip = noFlip)
            project.patterns.add(pat)
        }

        // Door helper: 1x4 door transition tiles with door cap PLM on top cell.
        // CRE tiles 0x040 (top/bottom) and 0x060 (middle), block type 0x9,
        // BTS defaults to 0x00 (user must set correct door index per room).
        // Left-side doors have hFlip=true, right-side have hFlip=false.
        fun doorPatternCells(capPlmId: Int, hFlip: Boolean): List<PatternCell> = listOf(
            PatternCell(0x040, blockType = 0x9, bts = 0x00, hFlip = hFlip, plmId = capPlmId, plmParam = 0x0000),
            PatternCell(0x060, blockType = 0x9, bts = 0x00, hFlip = hFlip),
            PatternCell(0x060, blockType = 0x9, bts = 0x00, hFlip = hFlip, vFlip = true),
            PatternCell(0x040, blockType = 0x9, bts = 0x00, hFlip = hFlip, vFlip = true)
        )

        try {
            // ── Doors: all colors, left and right facing ──
            // Door PLM headers are 6 bytes (3 pointers: setup, open, close),
            // so Left→Right offset is +6, NOT +4.
            // Left (on left wall): Blue 0xC8A2, Red 0xC88A, Green 0xC872, Yellow 0xC85A
            // Right (on right wall): Blue 0xC8A8, Red 0xC890, Green 0xC878, Yellow 0xC860
            addBuiltIn("builtin_door_blue_left",   "Door: Blue (Left)",   1, 4, doorPatternCells(0xC8A2, true), noFlip = true)
            addBuiltIn("builtin_door_blue_right",  "Door: Blue (Right)",  1, 4, doorPatternCells(0xC8A8, false), noFlip = true)
            addBuiltIn("builtin_door_red_left",    "Door: Red (Left)",    1, 4, doorPatternCells(0xC88A, true), noFlip = true)
            addBuiltIn("builtin_door_red_right",   "Door: Red (Right)",   1, 4, doorPatternCells(0xC890, false), noFlip = true)
            addBuiltIn("builtin_door_green_left",  "Door: Green (Left)",  1, 4, doorPatternCells(0xC872, true), noFlip = true)
            addBuiltIn("builtin_door_green_right", "Door: Green (Right)", 1, 4, doorPatternCells(0xC878, false), noFlip = true)
            addBuiltIn("builtin_door_yellow_left", "Door: Yellow (Left)", 1, 4, doorPatternCells(0xC85A, true), noFlip = true)
            addBuiltIn("builtin_door_yellow_right","Door: Yellow (Right)", 1, 4, doorPatternCells(0xC860, false), noFlip = true)

            // ── Save Station: 2x5, CRE tiles 89 (solid top/bottom) + 91 (air BG).
            // PLM on bottom-left (row 4, col 0). PLM renders animated graphic at runtime.
            addBuiltIn("builtin_save_station", "Save Station", 2, 5, listOf(
                PatternCell(89, blockType = 0x8, bts = 4),
                PatternCell(89, blockType = 0x8, bts = 4, hFlip = true),
                PatternCell(91, blockType = 0x0),
                PatternCell(91, blockType = 0x0, hFlip = true),
                PatternCell(91, blockType = 0x0),
                PatternCell(91, blockType = 0x0, hFlip = true),
                PatternCell(91, blockType = 0x0),
                PatternCell(91, blockType = 0x0, hFlip = true),
                PatternCell(89, blockType = 0x8, bts = 4, vFlip = true, plmId = 0xB76F, plmParam = 0x8000),
                PatternCell(89, blockType = 0x8, bts = 4, hFlip = true, vFlip = true),
            ))

            // ── Energy Refill: 3x3, CRE tiles matching vanilla station layout ──
            // Bottom corners are null: the PLM draws its own CRE tiles at runtime,
            // and null cells let the room's background show through.
            // Station PLM placement rules (from snesrev/sm decompilation):
            //  - PLM center block gets type 0x8, left/right get type 0xB + BTS at runtime
            //  - Activation requires: Samus NOT at full health, facing station, ran-into-wall pose
            //  - Pixel-exact Y check: samus_y_pos == plm_block_y * 16 + 11
            //    → floor must be EXACTLY 2 blocks below the PLM center
            //  - Middle + bottom rows use blockType 0x8 (solid) to match vanilla layout
            addBuiltIn("builtin_energy_refill", "Energy Refill", 3, 3, listOf(
                PatternCell(0x0A3, blockType = 0x0),
                PatternCell(0x0A4, blockType = 0x0),
                PatternCell(0x0A3, blockType = 0x0, hFlip = true),
                PatternCell(0x0C3, blockType = 0x8),
                PatternCell(0x0C4, blockType = 0x8, plmId = 0xB6DF, plmParam = 0x0000),
                PatternCell(0x0C3, blockType = 0x8, hFlip = true),
                null,
                PatternCell(0x0C2, blockType = 0x8),
                null,
            ))

            // ── Missile Refill: 3x3, CRE tiles matching vanilla station layout ──
            addBuiltIn("builtin_missile_refill", "Missile Refill", 3, 3, listOf(
                PatternCell(0x0A3, blockType = 0x0),
                PatternCell(0x0A7, blockType = 0x0),
                PatternCell(0x0A3, blockType = 0x0, hFlip = true),
                PatternCell(0x0C3, blockType = 0x8),
                PatternCell(0x0C7, blockType = 0x8, plmId = 0xB6EB, plmParam = 0x0000),
                PatternCell(0x0C3, blockType = 0x8, hFlip = true),
                null,
                PatternCell(0x0C2, blockType = 0x8),
                null,
            ))

            // ── Chozo Statue: 3x3, CRE chozo statue tiles ──
            addBuiltIn("builtin_chozo_statue", "Chozo Statue", 3, 3, listOf(
                PatternCell(0x044, blockType = 0x0),
                PatternCell(0x045),
                PatternCell(0x046),
                PatternCell(0x064),
                PatternCell(0x065),
                PatternCell(0x066),
                PatternCell(0x047),
                PatternCell(0x048),
                PatternCell(0x049),
            ))
        } catch (e: Exception) {
            editorLog("Failed to seed some built-in patterns: ${e.message}")
        }

        dirty = true
        patternVersion++
    }

    fun getSelectedPatch(): SmPatch? = project.patches.find { it.id == selectedPatchId }

    /** Find an existing patch by configType, or create and add one. Used by Enemy/Boss tabs. */
    fun findOrCreateConfigPatch(configType: String): SmPatch {
        seedDefaultPatches(forceRefreshBundled = true)
        val existing = project.patches.find { it.configType == configType }
        if (existing != null) return existing
        val patch = SmPatch(
            id = configType,
            name = configType.replace("_", " ").replaceFirstChar { it.uppercase() },
            configType = configType,
            enabled = true,
        )
        project.patches.add(patch)
        markDirty()
        return patch
    }

    private var patchesSeeded = false

    /** Ensure all default patches exist; loads bundled IPS + hardcoded hex demos. Idempotent. */
    fun seedDefaultPatches(forceRefreshBundled: Boolean = false) {
        if (patchesSeeded && !forceRefreshBundled) return
        patchesSeeded = true
        // Remove legacy/duplicate patches from old configs
        val removed = project.patches.removeAll { it.id in LEGACY_PATCH_IDS }
        if (removed) {
            if (selectedPatchId in LEGACY_PATCH_IDS) selectedPatchId = null
            patchVersion++
        }

        val existingIds = project.patches.map { it.id }.toSet()
        var added = 0
        var refreshed = 0

        // Collect patches in desired display order: config → hardcoded → bundled IPS
        val ordered = mutableListOf<SmPatch>()

        // 1. GUI config patches (featured at top)
        for (guiPatch in GUI_CONFIG_PATCHES) {
            if (guiPatch.id !in existingIds) {
                ordered.add(SmPatch(
                    id = guiPatch.id,
                    name = guiPatch.name,
                    description = guiPatch.description,
                    enabled = guiPatch.enabled,
                    writes = mutableListOf(),
                    configType = guiPatch.configType,
                    configValue = guiPatch.configValue
                ))
            }
        }

        // 2. Hardcoded hex-tweak patches (popular ones first via list order)
        for (def in withVanillaHexPatchPreconditions(HARDCODED_PATCHES)) {
            if (def.id !in existingIds) {
                ordered.add(def.copy(writes = def.writes.toMutableList()))
            } else {
                project.patches.find { it.id == def.id }?.let { existing ->
                    existing.name = def.name
                    existing.description = def.description
                    if (existing.exclusiveGroup != def.exclusiveGroup) {
                        existing.exclusiveGroup = def.exclusiveGroup
                        refreshed++
                    }
                    val expectedByWrite = def.writes.associateBy { it.offset to it.bytes }
                    var expectedChanged = false
                    for (index in existing.writes.indices) {
                        val current = existing.writes[index]
                        val expected = expectedByWrite[current.offset to current.bytes]?.expectedBytes
                        if (current.expectedBytes != expected && expected != null) {
                            existing.writes[index] = current.copy(expectedBytes = expected.toList())
                            expectedChanged = true
                        }
                    }
                    if (expectedChanged) refreshed++
                }
            }
        }

        // 3. Bundled IPS patches
        try {
            for (patch in PatchRepository.loadBundledPatches()) {
                if (patch.id !in existingIds) {
                    ordered.add(patch)
                } else {
                    // Bundled IPS patches are source-controlled resources. Refresh existing
                    // project copies so iterative bundled patch development is not trapped
                    // behind stale serialized write lists.
                    project.patches.find { it.id == patch.id }?.let {
                        it.name = patch.name
                        it.description = patch.description
                        if (it.exclusiveGroup != patch.exclusiveGroup) {
                            it.exclusiveGroup = patch.exclusiveGroup
                            refreshed++
                        }
                        if (it.customItems != patch.customItems) {
                            it.customItems.clear()
                            it.customItems.addAll(patch.customItems.map { customItem -> customItem.copy() })
                            refreshed++
                        }
                        if (it.compatibleRomHashes != patch.compatibleRomHashes) {
                            it.compatibleRomHashes.clear()
                            it.compatibleRomHashes.addAll(patch.compatibleRomHashes)
                            refreshed++
                        }
                        if (it.resources != patch.resources) {
                            it.resources.clear()
                            it.resources.addAll(patch.resources.map { resource -> resource.copy() })
                            refreshed++
                        }
                        val oldHash = bytesSha256(it.writes.flatMap { write -> write.bytes })
                        val newHash = bytesSha256(patch.writes.flatMap { write -> write.bytes })
                        if (oldHash != newHash || it.writes.size != patch.writes.size) {
                            it.writes.clear()
                            it.writes.addAll(patch.writes.map { write ->
                                PatchWrite(write.offset, write.bytes.toList(), write.expectedBytes?.toList())
                            })
                            refreshed++
                            if (patch.id.startsWith("bundled_spider_ball")) {
                                editorLog("[PATCH-SEED] Refreshed Spider Ball bundled writes: ${patch.writes.size} records, sha256=$newHash")
                            }
                        } else {
                            val expectedByWrite = patch.writes.associateBy { write -> write.offset to write.bytes }
                            var expectedChanged = false
                            for (index in it.writes.indices) {
                                val current = it.writes[index]
                                val expected = expectedByWrite[current.offset to current.bytes]?.expectedBytes
                                if (current.expectedBytes != expected && expected != null) {
                                    it.writes[index] = current.copy(expectedBytes = expected.toList())
                                    expectedChanged = true
                                }
                            }
                            if (expectedChanged) refreshed++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            editorLog("Failed to load bundled patches: ${e.message}")
        }

        // Insert new patches at position 0 so they appear before any user patches
        if (ordered.isNotEmpty()) {
            project.patches.addAll(0, ordered)
            added = ordered.size
        }

        val autoEnabled = enableConfiguredDedicatedEditorPatches()

        if (added > 0 || refreshed > 0 || autoEnabled > 0) { patchVersion++ }
    }

    private fun enableConfiguredDedicatedEditorPatches(): Int {
        var count = 0
        for (patch in project.patches) {
            if (patch.enabled) continue
            if (patch.configType !in DEDICATED_EDITOR_CONFIG_TYPES) continue
            if (patch.configData.isNullOrEmpty()) continue

            setPatchEnabled(patch, true)
            count++
        }
        if (count > 0) {
            dirty = true
            editorLog("[PATCH-SEED] Enabled $count configured enemy/boss patch(es) for export")
        }
        return count
    }

    // ─── Tile selection ─────────────────────────────────────────

    fun selectMetatile(index: Int, gridCols: Int = 32) {
        tilesetSelStart = Pair(index % gridCols, index / gridCols)
        tilesetSelEnd = tilesetSelStart
        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        val eff = getEffectiveTileDefault(currentTilesetId, index)
        brush = TileBrush.single(index, eff.blockType, eff.bts)
    }

    fun beginTilesetDrag(col: Int, row: Int) {
        tilesetSelStart = Pair(col, row)
        tilesetSelEnd = Pair(col, row)
    }

    fun updateTilesetDrag(col: Int, row: Int) {
        tilesetSelEnd = Pair(col, row)
    }

    /** Finalize rectangle selection → build multi-tile brush with per-tile defaults. */
    fun endTilesetDrag(gridCols: Int) {
        val s = tilesetSelStart ?: return
        val e = tilesetSelEnd ?: return
        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        val c0 = minOf(s.first, e.first)
        val c1 = maxOf(s.first, e.first)
        val r0 = minOf(s.second, e.second)
        val r1 = maxOf(s.second, e.second)
        val tiles = (r0..r1).map { r ->
            (c0..c1).map { c -> r * gridCols + c }
        }

        val btOverrides = mutableMapOf<Long, Int>()
        val btsOverrides = mutableMapOf<Long, Int>()
        for ((ri, row) in tiles.withIndex()) {
            for ((ci, meta) in row.withIndex()) {
                val key = (ri.toLong() shl 32) or (ci.toLong() and 0xFFFFFFFFL)
                val eff = getEffectiveTileDefault(currentTilesetId, meta)
                btOverrides[key] = eff.blockType
                if (eff.bts != 0) btsOverrides[key] = eff.bts
            }
        }
        val primaryIdx = tiles.first().first()
        val primaryEff = getEffectiveTileDefault(currentTilesetId, primaryIdx)
        val fallbackBlockType = primaryEff.blockType

        brush = TileBrush(
            tiles = tiles,
            blockType = fallbackBlockType,
            blockTypeOverrides = btOverrides,
            btsOverrides = btsOverrides
        )
    }

    fun toggleHFlip() {
        if (activePattern?.noFlip == true) return
        brush = brush?.copy(hFlip = !brush!!.hFlip)
    }
    fun toggleVFlip() {
        if (activePattern?.noFlip == true) return
        brush = brush?.copy(vFlip = !brush!!.vFlip)
    }

    fun flipOrCaptureH() {
        if (activeTool == EditorTool.SELECT && mapSelStart != null && mapSelEnd != null) beginFloatingSelectionFromMapSelection()
        toggleHFlip()
    }
    fun flipOrCaptureV() {
        if (activeTool == EditorTool.SELECT && mapSelStart != null && mapSelEnd != null) beginFloatingSelectionFromMapSelection()
        toggleVFlip()
    }
    fun rotateOrCapture() {
        if (activeTool == EditorTool.SELECT && mapSelStart != null && mapSelEnd != null) beginFloatingSelectionFromMapSelection()
        rotateClockwise()
    }

    fun rotateClockwise() {
        if (activePattern?.noFlip == true) return
        val b = brush ?: return
        val oldTiles = b.tiles
        val oldRowCount = oldTiles.size
        if (oldRowCount == 0) return
        val newTiles = oldTiles[0].indices.map { c ->
            oldTiles.indices.reversed().map { r -> oldTiles[r][c] }
        }

        fun remapKeys(old: Map<Long, Int>): Map<Long, Int> = buildMap {
            for ((key, value) in old) {
                val oldR = (key shr 32).toInt()
                val oldC = (key and 0xFFFFFFFFL).toInt()
                val newR = oldC
                val newC = oldRowCount - 1 - oldR
                put((newR.toLong() shl 32) or (newC.toLong() and 0xFFFFFFFFL), value)
            }
        }

        val newFlipOverrides = buildMap {
            for ((key, value) in b.flipOverrides) {
                val oldR = (key shr 32).toInt()
                val oldC = (key and 0xFFFFFFFFL).toInt()
                val newR = oldC
                val newC = oldRowCount - 1 - oldR
                val newKey = (newR.toLong() shl 32) or (newC.toLong() and 0xFFFFFFFFL)
                val h = value and 1
                val v = (value shr 1) and 1
                put(newKey, v or (h shl 1))
            }
        }

        brush = b.copy(
            tiles = newTiles,
            hFlip = !b.vFlip,
            vFlip = b.hFlip,
            blockTypeOverrides = remapKeys(b.blockTypeOverrides),
            btsOverrides = remapKeys(b.btsOverrides),
            flipOverrides = newFlipOverrides
        )
        clampFloatingSelectionToRoom()
    }

    fun setBlockType(type: Int) { brush = brush?.copy(blockType = type) }

    /** Update hover info when mouse moves on map. */
    fun updateHover(bx: Int, by: Int) {
        hoverBlockX = bx; hoverBlockY = by
        hoverTileWord = if (bx >= 0 && by >= 0) {
            if (activeRoomLayer == RoomEditLayer.LAYER2) readLayer2BlockWord(bx, by) else readBlockWord(bx, by)
        } else 0
    }

    /** Sample (eyedropper): pick the tile at (bx, by) from the map as the current brush. */
    fun sampleTile(bx: Int, by: Int, gridCols: Int = 32) {
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return
        val word = if (activeRoomLayer == RoomEditLayer.LAYER2) readLayer2BlockWord(bx, by) else readBlockWord(bx, by)
        val metatileIdx = word and 0x3FF
        val hf = (word shr 10) and 1 != 0
        val vf = (word shr 11) and 1 != 0
        val bt = if (activeRoomLayer == RoomEditLayer.LAYER2) 0x8 else (word shr 12) and 0xF
        val sampledBts = if (activeRoomLayer == RoomEditLayer.LAYER2) 0 else readBts(bx, by)
        val btsMap = if (sampledBts != 0) mapOf(0L to sampledBts) else emptyMap()
        brush = TileBrush(tiles = listOf(listOf(metatileIdx)), blockType = bt, hFlip = hf, vFlip = vf, btsOverrides = btsMap)
        floatingSelection = null
        tilesetSelStart = Pair(metatileIdx % gridCols, metatileIdx / gridCols)
        tilesetSelEnd = tilesetSelStart
        // Capture palette row from the sampled metatile's first sub-tile
        val tg = editorTileGraphics
        if (tg != null) {
            val palRows = tg.getMetatilePalettes(metatileIdx)
            sampledPaletteRow = palRows.firstOrNull() ?: -1
            // Read the pixel's palette color index at the click position within the metatile
            val px = (bx * 16) % 16  // sub-pixel position defaults to top-left
            val py = (by * 16) % 16
            sampledPaletteCol = tg.readMetatilePixel(metatileIdx, px, py).coerceIn(0, 15)
        }
        activeTool = EditorTool.PAINT
    }

    private fun mapSelectionBounds(): MapSelectionBounds? {
        val s = mapSelStart ?: return null
        val e = mapSelEnd ?: return null
        if (workingBlocksWide <= 0 || workingBlocksTall <= 0) return null
        return MapSelectionBounds(
            minX = minOf(s.first, e.first).coerceIn(0, workingBlocksWide - 1),
            minY = minOf(s.second, e.second).coerceIn(0, workingBlocksTall - 1),
            maxX = maxOf(s.first, e.first).coerceIn(0, workingBlocksWide - 1),
            maxY = maxOf(s.second, e.second).coerceIn(0, workingBlocksTall - 1),
        )
    }

    /**
     * Metadata shared by every tile in the current map selection. A null block
     * type or BTS means that property is heterogeneous across the selection.
     */
    internal fun mapSelectionProperties(): MapSelectionProperties? {
        val bounds = mapSelectionBounds() ?: return null
        val firstWord = readBlockWord(bounds.minX, bounds.minY)
        val firstBlockType = (firstWord ushr 12) and 0xF
        val firstBts = readBts(bounds.minX, bounds.minY)
        var blockTypeIsCommon = true
        var btsIsCommon = true

        for (by in bounds.minY..bounds.maxY) {
            for (bx in bounds.minX..bounds.maxX) {
                if (((readBlockWord(bx, by) ushr 12) and 0xF) != firstBlockType) {
                    blockTypeIsCommon = false
                }
                if (readBts(bx, by) != firstBts) btsIsCommon = false
            }
        }

        return MapSelectionProperties(
            minX = bounds.minX,
            minY = bounds.minY,
            maxX = bounds.maxX,
            maxY = bounds.maxY,
            tileCount = (bounds.maxX - bounds.minX + 1) * (bounds.maxY - bounds.minY + 1),
            metatile = firstWord and 0x3FF,
            blockType = firstBlockType.takeIf { blockTypeIsCommon },
            bts = firstBts.takeIf { btsIsCommon },
        )
    }

    private fun brushFromMapSelection(bounds: MapSelectionBounds): TileBrush {
        val rows = mutableListOf<List<Int>>()
        val btsMap = mutableMapOf<Long, Int>()
        val btMap = mutableMapOf<Long, Int>()
        val flipMap = mutableMapOf<Long, Int>()
        for (by in bounds.minY..bounds.maxY) {
            val row = mutableListOf<Int>()
            for (bx in bounds.minX..bounds.maxX) {
                val word = readBlockWord(bx, by)
                row.add(word and 0x3FF)
                val r = by - bounds.minY
                val c = bx - bounds.minX
                val key = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
                val tileH = (word shr 10) and 1
                val tileV = (word shr 11) and 1
                if (tileH != 0 || tileV != 0) {
                    flipMap[key] = tileH or (tileV shl 1)
                }
                val bt = (word shr 12) and 0xF
                if (r != 0 || c != 0) btMap[key] = bt
                val bts = readBts(bx, by)
                if (bts != 0) btsMap[key] = bts
            }
            rows.add(row)
        }
        val primaryBt = (readBlockWord(bounds.minX, bounds.minY) shr 12) and 0xF
        return TileBrush(
            tiles = rows,
            blockType = primaryBt,
            blockTypeOverrides = btMap,
            btsOverrides = btsMap,
            flipOverrides = flipMap
        )
    }

    /** Convert the current map selection rectangle into a paint brush. */
    fun captureMapSelection() {
        val bounds = mapSelectionBounds() ?: return
        if (bounds.minX == bounds.maxX && bounds.minY == bounds.maxY) {
            sampleTile(bounds.minX, bounds.minY)
            return
        }
        brush = brushFromMapSelection(bounds)
        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        activeTool = EditorTool.PAINT
    }

    /** Copy the current map selection into the brush without changing map tiles. */
    fun copyMapSelectionToBrush(): Boolean {
        val bounds = mapSelectionBounds() ?: return false
        brush = if (bounds.minX == bounds.maxX && bounds.minY == bounds.maxY) {
            val word = readBlockWord(bounds.minX, bounds.minY)
            val bts = readBts(bounds.minX, bounds.minY)
            val btsMap = if (bts != 0) mapOf(0L to bts) else emptyMap()
            TileBrush(
                tiles = listOf(listOf(word and 0x3FF)),
                blockType = (word shr 12) and 0xF,
                hFlip = (word shr 10) and 1 != 0,
                vFlip = (word shr 11) and 1 != 0,
                btsOverrides = btsMap,
            )
        } else {
            brushFromMapSelection(bounds)
        }
        return true
    }

    /** Lift the current selection into a floating preview. This does not modify room tiles. */
    fun beginFloatingSelectionFromMapSelection(): Boolean {
        val bounds = mapSelectionBounds() ?: return false
        brush = if (bounds.minX == bounds.maxX && bounds.minY == bounds.maxY) {
            val word = readBlockWord(bounds.minX, bounds.minY)
            val bts = readBts(bounds.minX, bounds.minY)
            val btsMap = if (bts != 0) mapOf(0L to bts) else emptyMap()
            TileBrush(
                tiles = listOf(listOf(word and 0x3FF)),
                blockType = (word shr 12) and 0xF,
                hFlip = (word shr 10) and 1 != 0,
                vFlip = (word shr 11) and 1 != 0,
                btsOverrides = btsMap,
            )
        } else {
            brushFromMapSelection(bounds)
        }
        mapSelStart = null
        mapSelEnd = null
        activeTool = EditorTool.SELECT
        setFloatingSelectionPosition(bounds.minX, bounds.minY)
        return true
    }

    fun beginFloatingSelectionFromBrushAt(x: Int, y: Int): Boolean {
        if (brush == null) return false
        mapSelStart = null
        mapSelEnd = null
        activeTool = EditorTool.SELECT
        setFloatingSelectionPosition(x, y)
        return true
    }

    private fun clampedFloatingPosition(x: Int, y: Int, b: TileBrush): FloatingMapSelection {
        if (workingBlocksWide <= 0 || workingBlocksTall <= 0) return FloatingMapSelection(0, 0)
        val maxX = (workingBlocksWide - b.cols).coerceAtLeast(0)
        val maxY = (workingBlocksTall - b.rows).coerceAtLeast(0)
        return FloatingMapSelection(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }

    private fun clampFloatingSelectionToRoom() {
        val floating = floatingSelection ?: return
        val b = brush ?: return
        floatingSelection = clampedFloatingPosition(floating.x, floating.y, b)
    }

    fun setFloatingSelectionPosition(x: Int, y: Int) {
        val b = brush ?: return
        floatingSelection = clampedFloatingPosition(x, y, b)
    }

    fun commitFloatingSelection(): Boolean {
        val floating = floatingSelection ?: return false
        if (brush == null) return false
        if (!allowOrQueueLayoutEdit { commitFloatingSelection() }) return false
        beginStroke()
        val changed = paintAt(floating.x, floating.y)
        val committed = endStroke()
        if (!committed) return false
        floatingSelection = null
        activeTool = EditorTool.SELECT
        return changed
    }

    fun cancelFloatingSelection(): Boolean {
        if (floatingSelection == null) return false
        floatingSelection = null
        return true
    }

    // ─── Project lifecycle ──────────────────────────────────────

    internal fun initForProject(file: File, loaded: SmEditProject) {
        layoutEditScopes.clear()
        dismissPendingLayoutEditScope()
        sourceRomData = null
        sourceRoomCatalog = null
        projectRoomWorkspaceParser = null
        projectFilePath = file.absolutePath
        project = loaded
        resetForLoadedProject(seedPatches = true)
        editorLog("Loaded project: ${file.absolutePath} (${project.rooms.size} rooms, ${project.patches.size} patches)")
    }

    fun initForRom(romPath: String) {
        // Programmatic/in-memory callers may not have persisted their ROM yet.
        // Desktop NEW/OPEN resolves a project explicitly via ProjectLifecycle.
        val file = File(romPath).resolveSibling("${File(romPath).nameWithoutExtension}.smedit")
        val loaded = if (file.isFile) ProjectFileService.loadProject(file).also {
            ProjectLifecycle.verify(it, File(it.romPath))
        } else SmEditProject(romPath = romPath)
        initForProject(file, loaded)
    }

    fun initForReadOnlyRom(romPath: String) {
        sourceRomData = null
        sourceRoomCatalog = null
        projectRoomWorkspaceParser = null
        projectFilePath = ""
        project = SmEditProject(romPath = romPath)
        resetForLoadedProject(seedPatches = true)
    }

    /**
     * Rebuild an isolated parser containing project-owned rooms. The input ROM
     * bytes remain untouched; [ProjectNewRoom.previewRoomId] is only the address
     * of this workspace representation.
     */
    fun prepareWorkspaceParser(sourceParser: RomParser? = null): RomParser {
        if (sourceParser != null) {
            sourceRomData = sourceParser.copyRomData()
            sourceRoomCatalog = sourceParser.roomCatalog
        }
        val source = sourceRomData ?: sourceParser?.copyRomData()
            ?: error("No source ROM is available for the room workspace")
        val data = source.copyOf()
        val result = ProjectNewRoomMaterializer(
            project = project,
            parser = RomParser(data),
            romData = data,
            onLog = ::editorLog,
            allowUnresolvedArrivals = true,
        ).materialize()

        val oldToNew = linkedMapOf<Int, Int>()
        val oldDoorToNew = linkedMapOf<Int, Int>()
        for (room in project.newRooms) {
            val allocated = result.roomIdsByProjectId.getValue(room.id)
            if (room.previewRoomId != 0 && room.previewRoomId != allocated) {
                oldToNew[room.previewRoomId] = allocated
            }
            if (room.previewRoomId != allocated) {
                room.previewRoomId = allocated
                dirty = true
            }
            val doorPointers = result.doorDefPtrsByProjectId[room.id].orEmpty()
            room.previewDoorDefPtrs.zip(doorPointers).forEach { (oldPointer, newPointer) ->
                if (oldPointer != 0 && oldPointer != newPointer) oldDoorToNew[oldPointer] = newPointer
            }
            if (room.previewDoorDefPtrs != doorPointers) {
                room.previewDoorDefPtrs.clear()
                room.previewDoorDefPtrs.addAll(doorPointers)
                dirty = true
            }
        }
        if (oldToNew.isNotEmpty() || oldDoorToNew.isNotEmpty()) {
            fun remapCondition(condition: ProjectRoomStateCondition): ProjectRoomStateCondition = condition.copy(
                argument = if (
                    condition.argumentKind == com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.DOOR_POINTER
                ) condition.argument?.let { oldDoorToNew[it] ?: it } else condition.argument,
                children = condition.children.map(::remapCondition),
            )
            val remapped = linkedMapOf<String, RoomEdits>()
            for ((key, edits) in project.rooms) {
                val oldId = key.toIntOrNull(16)
                val newId = oldId?.let { oldToNew[it] } ?: oldId
                val mappedDoors = edits.doorChanges.mapTo(mutableListOf()) { change ->
                    change.copy(destRoomPtr = oldToNew[change.destRoomPtr] ?: change.destRoomPtr)
                }
                val mappedSpawns = edits.saveStationSpawns.mapTo(mutableListOf()) { spawn ->
                    spawn.copy(
                        roomId = oldToNew[spawn.roomId] ?: spawn.roomId,
                        doorPtr = oldDoorToNew[spawn.doorPtr] ?: spawn.doorPtr,
                    )
                }
                val mappedStates = edits.states.mapTo(mutableListOf()) { state ->
                    state.copy(
                        condition = remapCondition(state.condition),
                        doorFxChanges = state.doorFxChanges.entries.associateTo(linkedMapOf()) { (doorKey, change) ->
                            val oldPointer = doorKey.toIntOrNull(16)
                            val newPointer = oldPointer?.let { oldDoorToNew[it] ?: it }
                            (newPointer?.toString(16)?.uppercase()?.padStart(4, '0') ?: doorKey) to change
                        },
                    )
                }
                val mappedEdits = edits.copy(
                    roomId = newId ?: edits.roomId,
                    states = mappedStates,
                    doorChanges = mappedDoors,
                    saveStationSpawns = mappedSpawns,
                )
                remapped[project.roomKey(mappedEdits.roomId)] = mappedEdits
            }
            project.rooms.clear()
            project.rooms.putAll(remapped)
        }
        val workspaceCatalog = sourceRoomCatalog?.let { catalog ->
            catalog.copy(
                rooms = (catalog.rooms + projectRoomInfos()).distinctBy { it.getRoomIdAsInt() },
                discoveredRoomCount = catalog.discoveredRoomCount + project.newRooms.size,
            )
        }
        return RomParser(data, workspaceCatalog).also { projectRoomWorkspaceParser = it }
    }

    fun projectRoomInfos(): List<RoomInfo> = project.newRooms.map { room ->
        RoomInfo(
            id = "0x${room.previewRoomId.toString(16).uppercase().padStart(4, '0')}",
            handle = room.handle,
            name = room.name,
            comment = "Project-owned ${room.origin.name.lowercase()} room",
        )
    }

    /** Create a complete project-owned room seed. Call [prepareWorkspaceParser] afterwards. */
    fun createNewRoom(request: NewRoomCreationRequest, romParser: RomParser): ProjectNewRoom {
        val name = request.name.trim()
        require(name.isNotEmpty()) { "Room name cannot be empty" }
        require(request.area in 0..6) { "Room area must be Crateria through Ceres" }
        require(
            request.mapX in 0 until MinimapData.MAP_WIDTH &&
                request.mapY in 0 until MinimapData.ROOM_MAP_HEIGHT
        ) { "Map position is outside the 64x31 room-coordinate area" }
        require(request.width in 1..16 && request.height in 1..16) { "Room dimensions must be 1-16 screens" }
        require(request.tileset in 0 until TileGraphics.NUM_TILESETS) { "Tileset ${request.tileset} is invalid" }

        val sourceBase = if (request.cloneCurrentRoom) {
            require(currentRoomId != 0) { "Load a room before cloning it" }
            require(currentRomParser === romParser) { "The room workspace changed; reopen the room before cloning it." }
            require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null) {
                "Finish the current tile stroke or selection before cloning the room."
            }
            // A last-good preview is useful while typing, but must never become a new
            // room's persisted seed when the actual source is invalid or unavailable.
            refreshLinkedPlms()
            val problems = listOfNotNull(layoutPreviewProblem, roomPropertiesPreviewProblem,
                objectPreviewProblem, initialScrollPreviewProblem, fxPreviewProblem, enemyPreviewProblem,
                doorPreviewProblem.takeIf { request.copyDoors })
            require(problems.isEmpty()) { "Clone blocked: ${problems.first()}. Repair the source before cloning; no room was created." }
            romParser.readRoomHeader(currentRoomId) ?: error("The current room could not be read")
        } else null
        val sourceRoom = sourceBase?.let { applyHeaderChanges(applyCurrentStateData(it, romParser)) }
        val width = sourceRoom?.width ?: request.width
        val height = sourceRoom?.height ?: request.height
        val tileset = sourceRoom?.tileset ?: request.tileset
        require(
            request.mapX + width <= MinimapData.MAP_WIDTH &&
                request.mapY + height <= MinimapData.ROOM_MAP_HEIGHT
        ) {
            "The ${width}x$height room does not fit at map position (${request.mapX}, ${request.mapY})"
        }

        val catalogRoomIds = romParser.roomCatalog.rooms.mapTo(mutableSetOf()) { it.getRoomIdAsInt() }
        val existingAreaRooms = romParser.roomCatalog.rooms.mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            romParser.readRoomHeader(roomId)?.let(::applyHeaderChanges)
        }.filter { it.area == request.area }
        val overlappingRomRoom = existingAreaRooms.firstOrNull { room ->
            rectanglesOverlap(
                request.mapX, request.mapY, width, height,
                room.mapX, room.mapY, room.width, room.height,
            )
        }
        val overlappingProjectRoom = project.newRooms.firstOrNull { other ->
            if (other.previewRoomId in catalogRoomIds) return@firstOrNull false
            val changes = other.previewRoomId.takeIf { it != 0 }
                ?.let { project.rooms[project.roomKey(it)]?.effectiveHeaderChange }
            val otherArea = changes?.area ?: other.header.area
            val otherX = changes?.mapX ?: other.header.mapX
            val otherY = changes?.mapY ?: other.header.mapY
            val otherWidth = changes?.width ?: other.header.width
            val otherHeight = changes?.height ?: other.header.height
            otherArea == request.area && rectanglesOverlap(
                request.mapX, request.mapY, width, height,
                otherX, otherY, otherWidth, otherHeight,
            )
        }
        require(overlappingRomRoom == null && overlappingProjectRoom == null) {
            val occupiedBy = overlappingProjectRoom?.name ?: overlappingRomRoom?.name ?: "another room"
            "Map Chunk Coords (${request.mapX}, ${request.mapY}) overlap $occupiedBy. Choose an empty footprint for this room."
        }
        val destinationMap = effectiveMinimapData(romParser, project, request.area)
        val destinationStation = effectiveMapStationData(romParser, project, request.area)
        val occupiedCell = (0 until height).firstNotNullOfOrNull { ry ->
            (0 until width).firstNotNullOfOrNull { rx ->
                val x = request.mapX + rx
                val y = request.mapY + ry
                (x to y).takeIf {
                    !isEmptyMinimapTile(destinationMap.getTile(x, y)) || destinationStation.isRevealed(x, y)
                }
            }
        }
        require(occupiedCell == null) {
            "Map Chunk Coords ${occupiedCell!!.first}, ${occupiedCell.second} already contain map or Map Station data. Choose an empty footprint."
        }

        val automaticMapTiles = if (sourceRoom != null) {
            val sourceAreaRooms = romParser.roomCatalog.rooms.mapNotNull { info ->
                romParser.readRoomHeader(info.getRoomIdAsInt())?.let(::applyHeaderChanges)
            }.filter { it.area == sourceRoom.area }
            val sourceTiles = extractRoomTiles(
                effectiveMinimapData(romParser, project, sourceRoom.area),
                sourceRoom.mapX,
                sourceRoom.mapY,
                sourceRoom.width,
                sourceRoom.height,
                roomMapOwnershipMask(sourceRoom, sourceAreaRooms),
            )
            sourceTiles.takeIf { it.hasVisibleMinimapTile() }
                ?: automaticRoomMinimapTiles(width, height)
        } else {
            automaticRoomMinimapTiles(width, height)
        }

        // Deleting a room must never let an old source/build link identify a different new room.
        val stableId = "room-${java.util.UUID.randomUUID()}"
        val baseHandle = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { stableId }
        var handle = baseHandle
        var handleOrdinal = 2
        val usedHandles = RoomRepository().getAllRooms().mapTo(mutableSetOf()) { it.handle }
            .also { handles -> handles += project.newRooms.map { it.handle } }
        while (handle in usedHandles) handle = "$baseHandle-${handleOrdinal++}"

        val usedIndices = buildSet {
            for (info in romParser.roomCatalog.rooms) {
                val room = romParser.readRoomHeader(info.getRoomIdAsInt()) ?: continue
                if (room.area == request.area) add(room.index)
            }
            project.newRooms.filter { it.header.area == request.area }.forEach { add(it.header.index) }
        }
        val roomIndex = (0..0xFF).firstOrNull { it !in usedIndices }
            ?: error("Area ${request.area} has no unused room index")

        val levelData = if (sourceRoom != null) {
            workingLevelData?.copyOf() ?: romParser.decompressLZ2(sourceRoom.levelDataPtr)
        } else {
            blankLevelData(width, height, includeLayer2 = true)
        }
        val scrolls = if (sourceRoom != null) {
            if (_workingScrolls.size == width * height) _workingScrolls.toList()
            else romParser.parseScrollData(sourceRoom.roomScrollsPtr, width, height).toList()
        } else List(width * height) { 1 }
        val clonedPlms = if (sourceRoom == null) emptyList() else _workingPlms.filterNot { it.id == 0xB76F }
        val seedScrollCommands = clonedPlms.mapIndexedNotNull { index, plm ->
            if (plm.id != 0xB703) null else {
                val key = "cmd_${plm.param and 255}"
                val roomEdits = project.rooms[project.roomKey(currentRoomId)]
                val custom = if (plm.param and 0xFF00 == 0xCC00)
                    selectedStateEdits(roomEdits)?.customScrollCommands?.get(key) ?: roomEdits?.customScrollCommands?.get(key) else null
                val bridge = asmPlmSourceBridge
                val commands = if (hasManagedPlms() || custom != null) requireNotNull(scrollCommandsFor(plm)) {
                    "Clone needs a readable command resource for every scroll trigger; repair its source first."
                } else com.supermetroid.editor.asm.AsmScrollCommandSource.original(bridge?.sourceText().orEmpty(),
                    bridge?.catalog?.symbols.orEmpty(), plm.param, romParser, width * height, bridge?.catalog?.originalText)
                com.supermetroid.editor.rom.validateScrollCommands(commands, width * height)
                index to commands
            }
        }.toMap()
        val plms = clonedPlms.mapIndexed { index, plm -> ProjectPlmEntry(plm.id, plm.x, plm.y, if (index in seedScrollCommands) 0 else plm.param) }
        val enemies = if (sourceRoom != null) {
            _workingEnemies.map {
                ProjectEnemyEntry(it.id, it.x, it.y, it.initParam, it.properties, it.extra1, it.extra2, it.extra3)
            }
        } else emptyList()
        val enemyGfx = sourceRoom?.let { room ->
            (if (hasManagedEnemies()) workingEnemyGraphics else romParser.parseEnemyGfxSet(room.enemyGfxPtr))
                .map { ProjectEnemyGfxEntry(it.speciesId, it.paletteIndex) }
        }.orEmpty()
        val missingGfxSpecies = enemies.map { it.id }.toSet() - enemyGfx.map { it.speciesId }.toSet()
        require(missingGfxSpecies.isEmpty()) {
            "Clone cannot snapshot newly added enemy graphics until the source room is exported: " +
                missingGfxSpecies.joinToString { "0x${it.toString(16).uppercase()}" }
        }
        val enemyKillCount = sourceRoom?.let { room ->
            if (hasManagedEnemies()) requireNotNull(asmPlmSourceBridge?.enemies?.view(currentRoomId,
                requireNotNull(currentPlmStateId()))?.resource).population.killCount
            else com.supermetroid.editor.rom.readSourceEnemyPopulation(romParser, room.enemySetPtr, "Cloned population").killCount
        } ?: 0
        val fxEntries = sourceRoom?.let { room ->
            // We copy outgoing connections, not incoming ones. The original incoming
            // FX selectors must not be rebound to unrelated outgoing DoorDefs in the clone.
            effectiveFxEntries(room.roomId, romParser).filter { it.isDefault }.map {
                ProjectFxEntry(
                    it.doorSelect, it.liquidSurfaceStart, it.liquidSurfaceNew, it.liquidSpeed,
                    it.liquidDelay, it.fxType, it.fxBitA, it.fxBitB, it.fxBitC,
                    it.paletteFxBitflags, it.tileAnimBitflags, it.paletteBlend,
                )
            }
        }.orEmpty().ifEmpty { listOf(ProjectFxEntry()) }
        val doors = if (sourceRoom != null && request.copyDoors) {
            _workingDoors.mapIndexed { index, door ->
                val projectDestination = project.newRooms.firstOrNull { it.previewRoomId == door.destRoomPtr }
                ProjectDoorDefinition(
                    destination = projectDestination?.let { project.newRoomDestination(it.id) }
                        ?: project.romRoomDestination(door.destRoomPtr),
                    bitflag = door.bitflag,
                    doorCapCode = door.doorCapCode,
                    screenX = door.screenX,
                    screenY = door.screenY,
                    distFromDoor = door.distFromDoor,
                    entryCode = door.entryCode,
                    sourceDoorDefPtr = door.doorDefPtr,
                    arrivalAsmLabel = doorArrivalBindings[index]?.label,
                )
            }
        } else emptyList()

        val room = ProjectNewRoom(
            id = stableId,
            name = name,
            handle = handle,
            origin = if (sourceRoom == null) ProjectNewRoomOrigin.BLANK else ProjectNewRoomOrigin.CLONED,
            sourceRoomId = sourceRoom?.roomId,
            header = ProjectNewRoomHeader(
                index = roomIndex,
                area = request.area,
                mapX = request.mapX,
                mapY = request.mapY,
                width = width,
                height = height,
                upScroller = sourceRoom?.upScroller ?: 0x70,
                downScroller = sourceRoom?.downScroller ?: 0xA0,
                creBitflag = sourceRoom?.creBitflag ?: 0,
            ),
            initialState = ProjectNewRoomState(
                levelDataBase64 = Base64.getEncoder().encodeToString(levelData),
                tileset = tileset,
                musicData = sourceRoom?.musicData ?: 0,
                musicTrack = sourceRoom?.musicTrack ?: 0,
                fxEntries = fxEntries.toMutableList(),
                enemies = enemies.toMutableList(),
                enemyGfx = enemyGfx.toMutableList(),
                bgScrolling = sourceRoom?.bgScrolling ?: 0,
                scrollData = scrolls.toMutableList(),
                xraySpecialCasingPtr = sourceRoom?.xraySpecialCasingPtr ?: 0,
                mainAsmPtr = sourceRoom?.mainAsmPtr ?: 0,
                plms = plms.toMutableList(),
                bgDataPtr = sourceRoom?.bgDataPtr ?: 0,
                setupAsmPtr = sourceRoom?.setupAsmPtr ?: 0,
                enemyKillCount = enemyKillCount,
                scrollCommands = seedScrollCommands,
            ),
            doors = doors.toMutableList(),
        )
        project.newRooms += room
        persistMinimapData(
            romParser,
            project,
            placeRoomTilesNonEmpty(
                destinationMap,
                request.mapX,
                request.mapY,
                width,
                height,
                automaticMapTiles,
            ),
        )
        dirty = true
        editVersion++
        val saveNote = if (sourceRoom != null && _workingPlms.any { it.id == 0xB76F })
            ". Save stations weren't copied: add an incoming connection, then place a station with its own save slot" else ""
        postStatus("Created ${if (sourceRoom == null) "blank" else "cloned"} room '$name' with pause-map tiles$saveNote")
        return room
    }

    /**
     * Describe everything that would be removed or invalidated before deleting
     * a project-owned room. Vanilla/core rooms deliberately return null: they
     * can be edited, but this workflow never deletes them.
     */
    fun previewProjectRoomDeletion(roomId: Int, romParser: RomParser): ProjectRoomDeletionPreview? {
        if (roomId == 0) return null
        val target = project.newRoomForPreviewId(roomId) ?: return null
        val effectiveTarget = romParser.readRoomHeader(roomId)?.let(::applyHeaderChanges)
        val targetKey = project.roomKey(roomId)
        val targetDoorPointers = target.previewDoorDefPtrs.toSet()
        val targetDestination = project.newRoomDestination(target.id)
        val blockers = linkedMapOf<String, ProjectRoomDeletionReference>()

        fun sourceName(sourceRoomId: Int): String =
            project.newRoomForPreviewId(sourceRoomId)?.name
                ?: romParser.roomCatalog.rooms.firstOrNull { it.getRoomIdAsInt() == sourceRoomId }?.name
                ?: "Room 0x${sourceRoomId.toString(16).uppercase().padStart(4, '0')}"

        fun addBlocker(kind: String, description: String) {
            blockers.putIfAbsent("$kind:$description", ProjectRoomDeletionReference(kind, description))
        }
        if (project.asmWorkspace.enabled && project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) {
            val bridge = asmPlmSourceBridge
            if (bridge == null) addBlocker("ASM", "Wait for the ASM workspace before checking source references.")
            else runCatching { bridge.projectRoomReferenceProblems(roomId) }
                .onSuccess { it.forEach { problem -> addBlocker("ASM", problem) } }
                .onFailure { addBlocker("ASM", "Source references could not be checked: ${it.message}. Repair the source before deleting this room.") }
        }

        // Stable project-room destinations are authoritative even before the
        // next workspace materialization assigns fresh preview pointers.
        project.newRooms.filter { it.id != target.id }.forEach { source ->
            source.doors.forEachIndexed { index, door ->
                if (door.destination == targetDestination) {
                    addBlocker("Door", "${source.name}, door ${index + 1}, leads to ${target.name}.")
                }
            }
        }

        // The workspace catches incoming doors from existing ROM rooms and any
        // already-materialized project edits.
        romParser.roomCatalog.rooms.forEach { info ->
            val sourceId = info.getRoomIdAsInt()
            if (sourceId == roomId) return@forEach
            val source = romParser.readRoomHeader(sourceId) ?: return@forEach
            romParser.parseDoorList(source.doorOut).forEachIndexed { index, door ->
                if (door.destRoomPtr == roomId) {
                    addBlocker("Door", "${sourceName(sourceId)}, door ${index + 1}, leads to ${target.name}.")
                }
            }
        }
        project.rooms.forEach { (roomKey, edits) ->
            if (roomKey == targetKey) return@forEach
            edits.doorChanges.forEach { door ->
                if (door.destRoomPtr == roomId) {
                    addBlocker("Door", "${sourceName(edits.roomId)}, door ${door.doorIndex + 1}, leads to ${target.name}.")
                }
            }
            edits.saveStationSpawns.filterNot { it.clearSlot }.forEach { spawn ->
                if (spawn.roomId == roomId) {
                    addBlocker("Save", "Area ${spawn.area} save slot ${spawn.saveIndex} loads ${target.name}.")
                }
                if (spawn.doorPtr in targetDoorPointers) {
                    addBlocker("Save", "Area ${spawn.area} save slot ${spawn.saveIndex} uses a door owned by ${target.name}.")
                }
            }

            fun conditionUsesTargetDoor(condition: ProjectRoomStateCondition): Boolean =
                (condition.argumentKind == com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.DOOR_POINTER &&
                    condition.argument?.let { it in targetDoorPointers } == true) ||
                    condition.children.any(::conditionUsesTargetDoor)

            edits.states.forEach { state ->
                if (conditionUsesTargetDoor(state.effectiveCondition)) {
                    addBlocker("Room state", "${sourceName(edits.roomId)} state '${state.id}' tests a door owned by ${target.name}.")
                }
                state.doorFxChanges.keys.forEach { key ->
                    if (key.toIntOrNull(16)?.let { it in targetDoorPointers } == true) {
                        addBlocker("FX", "${sourceName(edits.roomId)} state '${state.id}' has door-specific FX for ${target.name}.")
                    }
                }
            }
        }
        project.newRooms.filter { it.id != target.id }.forEach { source ->
            source.initialState.fxEntries.forEachIndexed { index, fx ->
                if (fx.doorSelect in targetDoorPointers) {
                    addBlocker("FX", "${source.name} FX entry ${index + 1} selects a door owned by ${target.name}.")
                }
            }
        }

        val ownEdits = project.rooms[targetKey]
        val itemCount = target.initialState.plms.size + ownEdits?.plmChanges.orEmpty().count { it.action == "add" }
        val enemyCount = target.initialState.enemies.size + ownEdits?.enemyChanges.orEmpty().count { it.action == "add" }
        val saveCount = ownEdits?.saveStationSpawns.orEmpty().count { !it.clearSlot }
        val removalSummary = buildList {
            add("${effectiveTarget?.width ?: target.header.width}×${effectiveTarget?.height ?: target.header.height} room and its pause-map footprint")
            if (target.doors.isNotEmpty()) add("${target.doors.size} outgoing door${if (target.doors.size == 1) "" else "s"}")
            if (ownEdits?.states?.isNotEmpty() == true) add("${ownEdits.states.size} authored room state${if (ownEdits.states.size == 1) "" else "s"}")
            if (itemCount > 0) add("$itemCount placed object${if (itemCount == 1) "" else "s"}")
            if (enemyCount > 0) add("$enemyCount enem${if (enemyCount == 1) "y" else "ies"}")
            if (saveCount > 0) add("$saveCount save-station override${if (saveCount == 1) "" else "s"}")
        }
        return ProjectRoomDeletionPreview(roomId, target.name, removalSummary, blockers.values.toList())
    }

    /** Delete a project-owned room only after its live references have been proven safe. */
    fun deleteProjectRoom(roomId: Int, romParser: RomParser): ProjectRoomDeletionPreview {
        val preview = previewProjectRoomDeletion(roomId, romParser)
            ?: throw IllegalArgumentException("Only project-created rooms can be deleted. Core ROM rooms remain editable.")
        require(preview.canDelete) {
            "${preview.roomName} still has ${preview.blockers.size} external reference${if (preview.blockers.size == 1) "" else "s"}."
        }
        val target = requireNotNull(project.newRoomForPreviewId(roomId))
        val room = romParser.readRoomHeader(roomId)?.let(::applyHeaderChanges)
        if (room != null) {
            val areaRooms = romParser.roomCatalog.rooms.mapNotNull { info ->
                romParser.readRoomHeader(info.getRoomIdAsInt())?.let(::applyHeaderChanges)
            }.filter { it.area == room.area }
            val ownership = roomMapOwnershipMask(room, areaRooms)
            persistMinimapData(
                romParser,
                project,
                clearRoomTiles(
                    effectiveMinimapData(romParser, project, room.area),
                    room.mapX, room.mapY, room.width, room.height, ownership,
                ),
            )
            persistMapStationData(
                romParser,
                project,
                clearMapStationRect(
                    effectiveMapStationData(romParser, project, room.area),
                    room.mapX, room.mapY, room.width, room.height, ownership,
                ),
            )
        }
        project.rooms.remove(project.roomKey(roomId))
        project.roomNameOverrides.remove(project.roomKey(roomId))
        project.newRooms.removeAll { it.id == target.id }
        _roomEditOrder.remove(roomId)
        dirty = true
        editVersion++
        postStatus("Deleted project room '${preview.roomName}'.")
        return preview
    }

    /** Create and workspace-materialize atomically from the UI's perspective. */
    fun createNewRoomWorkspace(
        request: NewRoomCreationRequest,
        romParser: RomParser,
    ): Pair<ProjectNewRoom, RomParser> {
        val wasDirty = dirty
        val previousMinimapEdits = project.minimapEdits.mapValues { (_, edits) -> edits.map { it.copy() }.toMutableList() }
        val previousMapStationEdits = project.mapStationEdits.mapValues { (_, edits) -> edits.map { it.copy() }.toMutableList() }
        val room = createNewRoom(request, romParser)
        return try {
            room to prepareWorkspaceParser()
        } catch (failure: Throwable) {
            project.newRooms.removeAll { it.id == room.id }
            if (room.previewRoomId != 0) project.rooms.remove(project.roomKey(room.previewRoomId))
            project.minimapEdits.clear()
            project.minimapEdits.putAll(previousMinimapEdits)
            project.mapStationEdits.clear()
            project.mapStationEdits.putAll(previousMapStationEdits)
            dirty = wasDirty
            editVersion++
            throw failure
        }
    }

    private fun blankLevelData(width: Int, height: Int, includeLayer2: Boolean): ByteArray {
        val blocks = width * 16 * height * 16
        val layer1Bytes = blocks * 2
        return ByteArray(2 + layer1Bytes + blocks + if (includeLayer2) layer1Bytes else 0).also {
            it[0] = (layer1Bytes and 0xFF).toByte()
            it[1] = ((layer1Bytes ushr 8) and 0xFF).toByte()
            for (index in 0 until blocks) {
                val offset = 2 + index * 2
                it[offset] = (RomConstants.AIR_TILE_WORD and 0xFF).toByte()
                it[offset + 1] = ((RomConstants.AIR_TILE_WORD ushr 8) and 0xFF).toByte()
            }
        }
    }

    private fun resetForLoadedProject(seedPatches: Boolean) {
        armedSharedDoors.clear()
        asmPlmSourceBridge = null
        enemyTuningBridge = null
        beamDamageBridge = null
        bossStatsBridge = null
        objectPreviewLoading = false
        environmentalDamageBridge = null
        dataPatchBridge = null
        patchToggleController = null
        pendingPatchDisable = null
        objectPreviewProblem = null
        initialScrollPreviewProblem = null
        fxPreviewProblem = null
        enemyPreviewProblem = null
        roomPropertiesPreviewProblem = null
        doorPreviewProblem = null
        doorArrivalBindings = emptyMap()
        doorSourceIdentities = emptyMap()
        selectorPreviewProblem = null
        selectorPreviewRevision = emptyMap()
        layoutPreviewProblem = null
        pendingLayoutNavigation = null
        loadedLayoutRomVersion = -1
        workingEnemyGraphics = emptyList()
        linkedFxPreview = null
        dirty = false
        patchesSeeded = false
        if (seedPatches) {
            seedDefaultPatches(forceRefreshBundled = true)
        }
        patchFavoriteStore.migrateLegacyFavorites(project.generalSettings.patchBrowser.favoritePatchIds)
        brush = null
        activeTool = EditorTool.SELECT
        tileGraphics = null
        editorTileGraphics = null
        editorSelectedMetatile = -1
        tilesetSelStart = null
        tilesetSelEnd = null
        sampledPaletteRow = -1
        sampledPaletteCol = -1
        workingLevelData = null
        originalLevelData = null
        workingBlocksWide = 0
        workingBlocksTall = 0
        currentRoomId = 0
        currentRomParser = null
        currentTilesetId = 0
        romTilesetId = 0
        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        activeRoomLayer = RoomEditLayer.LAYER1
        currentBgScrolling = 0
        currentArea = 0
        currentIncomingDoorPtrs = emptyList()
        currentAreaRomSaveEntries = emptyMap()
        currentAreaSaveEntryCount = 0
        vanillaSaveIndicesByArea = emptyMap()
        currentStateIndex = -1
        currentStateId = null
        hoverBlockX = -1
        hoverBlockY = -1
        hoverTileWord = 0
        scrollTargetBlockX = -1
        scrollTargetBlockY = -1
        metatileBlockTypePresets = emptyMap()
        undoStack.clear()
        managedPlmUndo.clear()
        redoStack.clear()
        undoVersion++
        pendingEdits.clear()
        pendingPositions.clear()
        pendingPlmAdds.clear()
        pendingPlmRemoves.clear()
        pendingAllStateItemBefore = null
        pendingPlmGeometry = null
        pendingSourcePattern = null
        itemScopeSnapshots.clear()
        itemEditScope = ItemStateScope.ALL_STATES
        plmEditScopeOverrides.clear()
        _workingPlms.clear()
        originalPlmCount = 0
        _workingDoors.clear()
        _workingEnemies.clear()
        _workingScrolls = IntArray(0)
        _originalScrolls = IntArray(0)
        scrollVersion++
        selectedPatternId = null
        activePattern = null
        patternUndoStack.clear()
        patternRedoStack.clear()
        pendingPatEdits.clear()
        pendingPatPositions.clear()
        patternEditVersion++
        patUndoVersion++

        // Clear cached sprite editor state so it reloads from the new ROM/project
        phantoonSprite.invalidate()
        draygonSprite.invalidate()
        crocomireSprite.invalidate()
        sporeSpawnSprite.invalidate()
        botwoonSprite.invalidate()
        torizoSprite.invalidate()
        motherBrainSprite.invalidate()
        ridleySprite.invalidate()
        kraidSprite.invalidate()

        _roomEditOrder.clear()
        _editCounter = 0L
        for ((key, edits) in project.rooms) {
            if (!edits.hasEdits) continue
            val rid = key.toIntOrNull(16) ?: continue
            _roomEditOrder[rid] = ++_editCounter
        }

        romVersion++
        paletteVersion++

        migrateTileDefaultsToCore()

        // Merge in library patterns that aren't already in the project
        val existingIds = project.patterns.map { it.id }.toSet()
        val libraryPatterns = PatternLibrary.loadAllPatterns()
        for (pat in libraryPatterns) {
            if (pat.id !in existingIds) {
                project.patterns.add(pat)
            }
        }
        patternVersion++
    }

    internal fun initTestLevel(blocksWide: Int, blocksTall: Int, includeLayer2: Boolean = false) {
        val totalTiles = blocksWide * blocksTall
        val layer1Bytes = totalTiles * 2
        val data = ByteArray(2 + layer1Bytes + totalTiles + if (includeLayer2) layer1Bytes else 0).also {
            it[0] = (layer1Bytes and 0xFF).toByte()
            it[1] = ((layer1Bytes shr 8) and 0xFF).toByte()
        }
        originalLevelData = data.copyOf()
        workingLevelData = data
        workingBlocksWide = blocksWide
        workingBlocksTall = blocksTall
        currentBgScrolling = 0
        activeRoomLayer = RoomEditLayer.LAYER1
        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        undoStack.clear()
        managedPlmUndo.clear()
        redoStack.clear()
        undoVersion++
    }

    internal fun setBrushForTest(b: TileBrush?) { brush = b }
    internal fun setRoomIdForTest(id: Int) { currentRoomId = id }

    // ─── Working level data ─────────────────────────────────────

    /** A tab remount is not a room/state change. Preserve history for every source family and read-only preview. */
    internal fun loadRoomForCanvas(roomId: Int, parser: RomParser, room: com.supermetroid.editor.data.Room) {
        if (currentRoomId != roomId || currentRomParser !== parser || loadedLayoutRomVersion != romVersion) {
            loadRoom(roomId, parser, room)
        } else {
            refreshLinkedPlms()
            finishLayoutNavigation(roomId, parser)
        }
    }

    private fun finishLayoutNavigation(roomId: Int, parser: RomParser) {
        pendingLayoutNavigation?.takeIf { it.first == roomId }?.let { (_, state) ->
            pendingLayoutNavigation = null
            if (currentPlmStateId() != state) switchRoomState(state, parser)
        }
    }

    fun loadRoom(roomId: Int, romParser: RomParser, room: com.supermetroid.editor.data.Room) {
        asmPlmSourceBridge?.selectors?.refreshAll()
        asmPlmSourceBridge?.properties?.refreshAll()
        asmPlmSourceBridge?.doors?.refreshAll()
        currentRomParser = romParser
        loadedLayoutRomVersion = romVersion
        currentRoomId = roomId
        romHasOpaqueSelectors = romParser.inspectRoomStates(roomId).states.any { it.condition.kind == com.supermetroid.editor.rom.RoomStateConditionKind.ASM_ONLY }
        currentStateIndex = romParser.preferredPreviewStateIndex(roomId)
        romTilesetId = room.tileset
        // A stored state-data change (e.g. from the biome generator or room
        // properties panel) overrides the ROM tileset for editing/rendering.
        val roomEdits = project.rooms[project.roomKey(roomId)]
        currentStateId = roomEdits?.states
            ?.firstOrNull { it.sourceStateIndex == currentStateIndex }
            ?.id
        val stateDataChange = selectedStateEdits(roomEdits)?.effectiveStateDataChange
        val commonStateDataChange = roomEdits?.effectiveStateDataChange
        currentTilesetId = stateDataChange?.tileset ?: commonStateDataChange?.tileset ?: room.tileset
        currentBgScrolling = stateDataChange?.bgScrolling ?: commonStateDataChange?.bgScrolling ?: room.bgScrolling
        currentArea = project.rooms[project.roomKey(roomId)]?.effectiveHeaderChange?.area ?: room.area
        refreshVanillaSaveIndices(romParser)
        currentIncomingDoorPtrs = romParser.findDoorsLeadingTo(roomId)
            .map { it.doorDefPtr }
            .filter { it != 0 }
            .distinct()
        currentAreaSaveEntryCount = romParser.saveEntryCount(currentArea)
        currentAreaRomSaveEntries = (0 until currentAreaSaveEntryCount.coerceAtMost(RomParser.SAVE_STATION_SLOT_COUNT))
            .mapNotNull { idx -> romParser.readSaveEntry(currentArea, idx)?.let { idx to it } }
            .toMap()
        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        val tg = TileGraphics(romParser)
        if (tg.loadTileset(currentTilesetId)) {
            applyCustomGfxToTileGraphics(tg, currentTilesetId)
            tileGraphics = tg
        }
        var levelData = romParser.decompressLZ2(room.levelDataPtr)
        val romWidth = room.width
        val romHeight = room.height

        // Check for a stored resize — if the project has different width/height, resize now
        val hc = project.rooms[project.roomKey(roomId)]?.effectiveHeaderChange
        val effectiveWidth = hc?.width ?: romWidth
        val effectiveHeight = hc?.height ?: romHeight
        if (effectiveWidth != romWidth || effectiveHeight != romHeight) {
            levelData = resizeLevelData(levelData, romWidth, romHeight, effectiveWidth, effectiveHeight)
        }

        originalLevelData = levelData.copyOf()
        workingLevelData = levelData.copyOf()
        workingBlocksWide = effectiveWidth * 16
        workingBlocksTall = effectiveHeight * 16
        if (!canEditEmbeddedLayer2()) activeRoomLayer = RoomEditLayer.LAYER1

        // Build metatile → block type presets by scanning room data
        val typeCounts = mutableMapOf<Int, MutableMap<Int, Int>>()
        for (y in 0 until workingBlocksTall) {
            for (x in 0 until workingBlocksWide) {
                val word = readBlockWord(x, y)
                val meta = word and 0x3FF
                val bt = (word shr 12) and 0xF
                if (bt != 0x0 && bt != 0x5 && bt != 0xD) {
                    typeCounts.getOrPut(meta) { mutableMapOf() }.merge(bt, 1, Int::plus)
                }
            }
        }
        val roomPresets = typeCounts.mapValues { (_, counts) ->
            counts.maxByOrNull { it.value }?.key ?: 0x8
        }
        // Merge with existing presets: prefer non-Solid (non-0x8) types
        val merged = metatileBlockTypePresets.toMutableMap()
        for ((meta, bt) in roomPresets) {
            val existing = merged[meta]
            if (existing == null || (bt != 0x8 && existing == 0x8)) {
                merged[meta] = bt
            }
        }
        metatileBlockTypePresets = merged

        undoStack.clear()
        managedPlmUndo.clear()
        redoStack.clear()
        undoVersion++
        pendingEdits.clear()
        pendingPositions.clear()
        pendingAllStateItemBefore = null
        pendingPlmGeometry = null
        pendingSourcePattern = null
        itemScopeSnapshots.clear()

        // Load scroll data for this room (resize if dimensions changed)
        val romScrolls = romParser.parseScrollData(room.roomScrollsPtr, romWidth, romHeight)
        _originalScrolls = if (effectiveWidth != romWidth || effectiveHeight != romHeight) {
            resizeScrollGrid(romScrolls, romWidth, romHeight, effectiveWidth, effectiveHeight)
        } else {
            romScrolls
        }
        // A pending/invalid source projection must not masquerade as the original ROM grid.
        _workingScrolls = if (hasManagedInitialScrolls()) IntArray(0) else _originalScrolls.copyOf()
        scrollVersion++

        // Load the selected preview state's PLMs. Other states are available
        // through the state selector instead of being merged into one view.
        _workingPlms.clear()
        val plms = romParser.parsePlmSet(room.plmSetPtr)
        // A source-owned room must not temporarily masquerade as the vanilla ROM's object list.
        if (!hasManagedPlms()) _workingPlms.addAll(plms)
        originalPlmCount = plms.size

        // Parse door entries for this room
        doorEntries = romParser.parseDoorList(room.doorOut)

        // Load enemies for this room
        _workingEnemies.clear()
        _workingEnemies.addAll(romParser.parseEnemyPopulation(room.enemySetPtr))

        val roomKey = project.roomKey(roomId)
        val savedRoom = project.rooms[roomKey]
        if (savedRoom != null) {
            if (savedRoom.states.isNotEmpty()) migrateLegacyStateLayoutOperations(savedRoom, romParser)
            replaySavedRoomEdits(savedRoom, effectiveWidth, roomKey)
        }
        refreshLinkedPlms()
        // Bump render version without marking room as user-edited
        _editVersionState.value++
        finishLayoutNavigation(roomId, romParser)
    }

    /**
     * Editing through the room canvas always targets the state currently being
     * previewed. Tests and synthetic canvases without a ROM parser retain the
     * older room-wide lists so those isolated tools remain usable.
     */
    private fun currentStateEdits(): RoomStateEdits? {
        val parser = currentRomParser ?: return null
        if (currentRoomId == 0 || currentStateIndex < 0) return null
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        if (romHasOpaqueSelectors && roomEdits.states.isEmpty()) return null
        roomEdits.ensureStateManifest(parser)
        val state = selectedStateEdits(roomEdits)
            ?: return null
        return state
    }

    private fun selectedStateEdits(roomEdits: RoomEdits?): RoomStateEdits? =
        roomEdits?.stateEditsForId(currentStateId)
            ?: roomEdits?.stateEditsForSourceIndex(currentStateIndex)

    private fun layoutScopeKey(roomId: Int, resourceId: String): String = "$roomId:$resourceId"

    private fun levelResourceOwner(roomEdits: RoomEdits, state: RoomStateEdits): RoomStateEdits =
        if (!state.levelResourceChanged) {
            state
        } else {
            roomEdits.states.firstOrNull { candidate ->
                candidate.resources.level == state.resources.level && !candidate.levelResourceChanged
            } ?: state
        }

    fun currentLayoutEditingContext(): LayoutEditingContext? {
        val parser = currentRomParser ?: return null
        if (currentRoomId == 0) return null
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        if (romHasOpaqueSelectors && roomEdits.states.isEmpty()) return null
        roomEdits.ensureStateManifest(parser)
        val state = selectedStateEdits(roomEdits) ?: return null
        val resourceId = state.resources.level
        val sharingStates = roomEdits.states.filter { it.resources.level == resourceId }
        val resourceOperations = roomEdits.levelResourceOperations[resourceId].orEmpty()
        val explicitScope = layoutEditScopes[layoutScopeKey(currentRoomId, resourceId)]
        val inferredScope = when {
            sharingStates.size <= 1 -> LayoutEditScope.THIS_STATE
            explicitScope == LayoutEditScope.ALL_SHARING_STATES -> explicitScope
            resourceOperations.any { it.edits.isNotEmpty() } -> LayoutEditScope.ALL_SHARING_STATES
            else -> null
        }
        return LayoutEditingContext(
            roomId = currentRoomId,
            stateId = state.id,
            stateName = stateDisplayName(state),
            resourceId = resourceId,
            allStateIds = roomEdits.states.map { it.id },
            allStateNames = roomEdits.states.map(::stateDisplayName),
            sharingStateIds = sharingStates.map { it.id },
            sharingStateNames = sharingStates.map(::stateDisplayName),
            scope = inferredScope,
            hasResourceEdits = hasManagedLayouts() || resourceOperations.any { it.edits.isNotEmpty() },
            hasLegacyPrivateEdits = state.operations.any { it.edits.isNotEmpty() },
        )
    }

    /** Acknowledge shared editing or explicitly fork the active state's complete layout. */
    fun setCurrentLayoutEditScope(scope: LayoutEditScope) {
        val context = currentLayoutEditingContext() ?: return
        if (scope == LayoutEditScope.THIS_STATE) {
            currentRomParser?.let { makeCurrentLayoutUnique(it) }
        } else {
            layoutEditScopes[layoutScopeKey(context.roomId, context.resourceId)] = scope
            pendingLayoutEditScope = null
            editVersion++
        }
    }

    fun resolvePendingLayoutEditScope(scope: LayoutEditScope) {
        val prompt = pendingLayoutEditScope ?: return
        val action = pendingLayoutEditAction
        pendingLayoutEditScope = null
        pendingLayoutEditAction = null
        if (scope == LayoutEditScope.THIS_STATE) {
            currentRomParser?.let { makeCurrentLayoutUnique(it) }
        } else {
            layoutEditScopes[layoutScopeKey(prompt.roomId, prompt.resourceId)] = scope
        }
        action?.invoke()
        editVersion++
    }

    fun dismissPendingLayoutEditScope() {
        pendingLayoutEditScope = null
        pendingLayoutEditAction = null
    }

    /**
     * Queue the first direct edit until the user acknowledges that the complete
     * layout is shared or explicitly forks it for this state.
     */
    private fun allowOrQueueLayoutEdit(action: () -> Unit): Boolean {
        if (romHasOpaqueSelectors) {
            postStatus("Open the original source project/base ROM to edit rooms with ASM-only predicates; compiled ROM predicates cannot be imported as GUI conditions.")
            return false
        }
        if (hasManagedLayouts()) {
            val bridge = asmPlmSourceBridge
            if (bridge == null || !matchesRoomSourceContext(bridge.catalog.parser)) {
                postStatus("Layout edit blocked: the room ROM changed; wait for the source workspace.")
                return false
            }
            val view = currentPlmStateId()?.let { asmPlmSourceBridge?.layouts?.view(currentRoomId, it) }
            if (view == null || view.problem != null || view.resource == null) {
                postStatus("Layout edit blocked: ${view?.problem ?: "Wait for the layout source."}")
                return false
            }
        }
        val context = currentLayoutEditingContext() ?: return true
        if (!context.requiresScopeChoice) return true
        if (pendingLayoutEditAction == null) pendingLayoutEditAction = action
        pendingLayoutEditScope = context
        return false
    }

    private fun currentOperations(): MutableList<EditOperation> {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        val state = currentStateEdits() ?: return roomEdits.operations
        return roomEdits.levelResourceOperations.getOrPut(state.resources.level) { mutableListOf() }
    }

    private fun EditOperation.hasNonLayoutChanges(): Boolean =
        plmAdds.isNotEmpty() || plmRemoves.isNotEmpty() || enemyAdds.isNotEmpty() ||
            enemyRemoves.isNotEmpty() || enemyUpdates.isNotEmpty() || scrollEdits.isNotEmpty() ||
            stateDataBefore != null || stateDataAfter != null || fxBefore != null || fxAfter != null

    private fun EditOperation.tileOnly(): EditOperation = copy(
        plmAdds = emptyList(),
        plmRemoves = emptyList(),
        itemScope = null,
        enemyAdds = emptyList(),
        enemyRemoves = emptyList(),
        enemyUpdates = emptyList(),
        scrollEdits = emptyList(),
        stateDataBefore = null,
        stateDataAfter = null,
        fxBefore = null,
        fxAfter = null,
    )

    private fun EditOperation.nonLayoutOnly(): EditOperation = copy(edits = emptyList())

    /** Keep resource-owned tile edits separate from state-owned objects/effects in mixed actions. */
    private fun recordOperationForCurrentScope(operation: EditOperation) {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        val state = currentStateEdits()
        if (operation.edits.isNotEmpty() && state != null) {
            roomEdits.levelResourceOperations
                .getOrPut(state.resources.level) { mutableListOf() }
                .add(operation.tileOnly())
            if (operation.hasNonLayoutChanges()) state.operations.add(operation.nonLayoutOnly())
        } else {
            (state?.operations ?: roomEdits.operations).add(operation)
        }
    }

    private fun removeRecordedOperationForCurrentScope(operation: EditOperation) {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        val state = currentStateEdits()
        if (operation.edits.isNotEmpty() && state != null) {
            val resourceOperations = roomEdits.levelResourceOperations[state.resources.level]
            if (resourceOperations?.lastOrNull() == operation.tileOnly()) {
                resourceOperations.removeAt(resourceOperations.lastIndex)
                if (resourceOperations.isEmpty()) {
                    roomEdits.levelResourceOperations.remove(state.resources.level)
                }
            }
            if (operation.hasNonLayoutChanges() && state.operations.lastOrNull() == operation.nonLayoutOnly()) {
                state.operations.removeAt(state.operations.lastIndex)
            }
        } else {
            val operations = state?.operations ?: roomEdits.operations
            val index = operations.indexOfLast { it == operation }
            if (index >= 0) operations.removeAt(index)
        }
    }

    private fun effectiveLevelDataForState(
        roomEdits: RoomEdits,
        state: RoomStateEdits,
        romParser: RomParser,
        followLinkedResource: Boolean = true,
        includeSharedOperations: Boolean = true,
        includeStateOperations: Boolean = true,
        prospectiveLayoutEdits: List<TileEdit> = emptyList(),
    ): ByteArray? {
        if (roomEdits.asmLayoutSourceVersion != 0) {
            val view = asmPlmSourceBridge?.layouts?.view(roomEdits.roomId, state.id) ?: return null
            require(view.problem == null) { view.problem ?: "Invalid room layout source." }
            val resource = view.resource ?: return null
            return if (prospectiveLayoutEdits.isEmpty()) resource.bytes() else resource.edited(prospectiveLayoutEdits).bytes()
        }
        val room = romParser.readRoomHeader(roomEdits.roomId) ?: return null
        val runtimeStates = romParser.parseRoomStatesWithData(roomEdits.roomId)
        if (runtimeStates.getOrNull(state.baseSourceStateIndex() ?: return null) == null) return null
        val levelOwner = if (followLinkedResource) levelResourceOwner(roomEdits, state) else state
        val sourceIndex = levelOwner.baseSourceStateIndex() ?: return null
        val levelRuntimeState = runtimeStates.getOrNull(sourceIndex) ?: return null
        val effectiveWidth = roomEdits.effectiveHeaderChange?.width ?: room.width
        val effectiveHeight = roomEdits.effectiveHeaderChange?.height ?: room.height
        val levelPtr = levelRuntimeState.levelDataPtr.takeIf { it != 0 } ?: room.levelDataPtr
        var data = romParser.decompressLZ2(levelPtr)
        if (effectiveWidth != room.width || effectiveHeight != room.height) {
            data = resizeLevelData(data, room.width, room.height, effectiveWidth, effectiveHeight)
        } else {
            data = data.copyOf()
        }
        fun applyOperations(operations: List<EditOperation>) {
            val blocksWide = effectiveWidth * 16
            val blocksTall = effectiveHeight * 16
            val totalBlocks = blocksWide * blocksTall
            if (data.size < 2) return
            val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            val layer2Start = embeddedLayer2StartOffset(data, totalBlocks)
            for (operation in operations) for (edit in operation.edits) {
                if (edit.blockX !in 0 until blocksWide || edit.blockY !in 0 until blocksTall) continue
                val index = edit.blockY * blocksWide + edit.blockX
                if (edit.layer == TILE_EDIT_LAYER_2) {
                    if (layer2Start == null) continue
                    val offset = layer2Start + index * 2
                    data[offset] = (edit.newBlockWord and 0xFF).toByte()
                    data[offset + 1] = ((edit.newBlockWord ushr 8) and 0xFF).toByte()
                } else {
                    val wordOffset = 2 + index * 2
                    val btsOffset = 2 + layer1Size + index
                    if (wordOffset + 1 >= data.size || btsOffset >= data.size) continue
                    data[wordOffset] = (edit.newBlockWord and 0xFF).toByte()
                    data[wordOffset + 1] = ((edit.newBlockWord ushr 8) and 0xFF).toByte()
                    data[btsOffset] = edit.newBts.toByte()
                }
            }
        }
        applyOperations(roomEdits.operations)
        if (includeSharedOperations) {
            applyOperations(roomEdits.levelResourceOperations[state.resources.level].orEmpty())
        }
        if (prospectiveLayoutEdits.isNotEmpty()) applyOperations(listOf(EditOperation("Pattern preview", prospectiveLayoutEdits)))
        if (includeStateOperations) applyOperations(state.operations)
        return data
    }

    private fun levelWordAt(
        data: ByteArray,
        blockX: Int,
        blockY: Int,
        layer: Int,
        blocksWide: Int,
        blocksTall: Int,
    ): Int? {
        if (blockX !in 0 until blocksWide || blockY !in 0 until blocksTall || data.size < 2) return null
        val index = blockY * blocksWide + blockX
        val offset = if (layer == TILE_EDIT_LAYER_2) {
            val start = embeddedLayer2StartOffset(data, blocksWide * blocksTall) ?: return null
            start + index * 2
        } else {
            2 + index * 2
        }
        if (offset + 1 >= data.size) return null
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun levelBtsAt(data: ByteArray, blockX: Int, blockY: Int, blocksWide: Int): Int? {
        if (blockX < 0 || blockY < 0 || blockX >= blocksWide || data.size < 2) return null
        val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val offset = 2 + layer1Size + blockY * blocksWide + blockX
        if (offset !in data.indices) return null
        return data[offset].toInt() and 0xFF
    }

    private fun buildLevelDataDiff(
        oldData: ByteArray,
        newData: ByteArray,
        blocksWide: Int,
        blocksTall: Int,
    ): List<TileEdit> {
        val edits = mutableListOf<TileEdit>()
        for (y in 0 until blocksTall) for (x in 0 until blocksWide) {
            val oldWord = levelWordAt(oldData, x, y, TILE_EDIT_LAYER_1, blocksWide, blocksTall) ?: continue
            val newWord = levelWordAt(newData, x, y, TILE_EDIT_LAYER_1, blocksWide, blocksTall) ?: continue
            val oldBts = levelBtsAt(oldData, x, y, blocksWide) ?: continue
            val newBts = levelBtsAt(newData, x, y, blocksWide) ?: continue
            if (oldWord != newWord || oldBts != newBts) {
                edits += TileEdit(x, y, oldWord, newWord, oldBts, newBts)
            }
        }
        if (embeddedLayer2StartOffset(oldData, blocksWide * blocksTall) != null &&
            embeddedLayer2StartOffset(newData, blocksWide * blocksTall) != null
        ) {
            for (y in 0 until blocksTall) for (x in 0 until blocksWide) {
                val oldWord = levelWordAt(oldData, x, y, TILE_EDIT_LAYER_2, blocksWide, blocksTall) ?: continue
                val newWord = levelWordAt(newData, x, y, TILE_EDIT_LAYER_2, blocksWide, blocksTall) ?: continue
                if (oldWord != newWord) {
                    edits += TileEdit(x, y, oldWord, newWord, layer = TILE_EDIT_LAYER_2)
                }
            }
        }
        return edits
    }

    private fun pruneUnusedLevelResources(roomEdits: RoomEdits) {
        val activeResourceIds = roomEdits.states.mapTo(mutableSetOf()) { it.resources.level }
        roomEdits.levelResourceOperations.keys.removeAll { it !in activeResourceIds }
    }

    private fun stripTileEdits(operations: MutableList<EditOperation>) {
        operations.replaceAll { operation -> operation.copy(edits = emptyList()) }
        operations.removeAll { operation -> !operation.hasNonLayoutChanges() }
    }

    private fun nextUniqueLevelResourceId(roomEdits: RoomEdits): String {
        var ordinal = 1
        var candidate: String
        do {
            candidate = "level-unique-$ordinal"
            ordinal++
        } while (
            roomEdits.states.any { it.resources.level == candidate } ||
            candidate in roomEdits.levelResourceOperations ||
            (roomEdits.asmLayoutSourceVersion != 0 && asmPlmSourceBridge?.layouts?.hasLayout(roomEdits.roomId, candidate) == true)
        )
        return candidate
    }

    /**
     * Give [state] one complete semantic layout resource. Any legacy private
     * tile overlay is normalized into that resource; non-layout operation
     * metadata remains state-owned.
     */
    private fun makeLayoutUniqueForState(
        roomEdits: RoomEdits,
        state: RoomStateEdits,
        romParser: RomParser,
    ): Boolean {
        val sharingCount = roomEdits.states.count { it.resources.level == state.resources.level }
        val hasLegacyOverlay = state.operations.any { it.edits.isNotEmpty() }
        if (sharingCount <= 1 && !hasLegacyOverlay) return false

        if (roomEdits.asmLayoutSourceVersion != 0) {
            val bridge = requireNotNull(asmPlmSourceBridge)
            require(matchesRoomSourceContext(bridge.catalog.parser) && currentRomParser === romParser) { "The room ROM changed; reopen its layout." }
            bridge.layouts.changeLink(roomEdits.roomId, state.id, nextUniqueLevelResourceId(roomEdits), clone = true)
            dirty = true
            return true
        }

        val effectiveLayout = effectiveLevelDataForState(roomEdits, state, romParser) ?: return false
        val stateBaseline = effectiveLevelDataForState(
            roomEdits = roomEdits,
            state = state,
            romParser = romParser,
            followLinkedResource = false,
            includeSharedOperations = false,
            includeStateOperations = false,
        ) ?: return false
        val resourceId = if (sharingCount > 1) {
            nextUniqueLevelResourceId(roomEdits)
        } else {
            state.resources.level
        }
        val edits = buildLevelDataDiff(
            oldData = stateBaseline,
            newData = effectiveLayout,
            blocksWide = workingBlocksWide,
            blocksTall = workingBlocksTall,
        )
        if (edits.isEmpty()) {
            roomEdits.levelResourceOperations.remove(resourceId)
        } else {
            roomEdits.levelResourceOperations[resourceId] = mutableListOf(
                EditOperation("Unique layout for ${stateDisplayName(state)}", edits)
            )
        }
        stripTileEdits(state.operations)
        state.resources = state.resources.copy(level = resourceId)
        state.levelResourceChanged = false
        pruneUnusedLevelResources(roomEdits)
        dirty = true
        return true
    }

    /** Explicitly fork the active state's whole layout resource. */
    fun makeCurrentLayoutUnique(romParser: RomParser): Boolean {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.ensureStateManifest(romParser)
        val state = selectedStateEdits(roomEdits) ?: return false
        if (!runCatching { makeLayoutUniqueForState(roomEdits, state, romParser) }.getOrElse {
                postStatus("Layout fork blocked: ${it.message}"); false
            }) return false
        layoutEditScopes[layoutScopeKey(currentRoomId, state.resources.level)] = LayoutEditScope.THIS_STATE
        switchRoomState(state.id, romParser)
        editVersion++
        return true
    }

    /** Preserve old project semantics while upgrading private tile overlays to whole resources. */
    private fun migrateLegacyStateLayoutOperations(roomEdits: RoomEdits, romParser: RomParser) {
        val legacyStates = roomEdits.states.filter { state -> state.operations.any { it.edits.isNotEmpty() } }
        if (legacyStates.isEmpty()) return
        var migrated = 0
        for (state in legacyStates) {
            if (makeLayoutUniqueForState(roomEdits, state, romParser)) migrated++
        }
        if (migrated > 0) {
            editorLog("Migrated $migrated legacy state layout overlay(s) to complete layout resources")
            editVersion++
        }
    }

    /** Replace the active state's complete tile/BTS layout with another state's effective layout. */
    fun copyLayoutFromState(sourceStateId: String, romParser: RomParser): Int {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.ensureStateManifest(romParser)
        val sourceState = roomEdits.stateEditsForId(sourceStateId) ?: return 0
        if (sourceState.id == currentStateId) return 0
        val sourceData = runCatching { effectiveLevelDataForState(roomEdits, sourceState, romParser) }.getOrElse {
            postStatus("Layout copy blocked: ${it.message}"); return 0
        } ?: return 0
        makeCurrentLayoutUnique(romParser)
        val blocksWide = workingBlocksWide
        val blocksTall = workingBlocksTall
        val edits = mutableListOf<TileEdit>()
        for (y in 0 until blocksTall) for (x in 0 until blocksWide) {
            val oldWord = readBlockWord(x, y)
            val oldBts = readBts(x, y)
            val newWord = levelWordAt(sourceData, x, y, TILE_EDIT_LAYER_1, blocksWide, blocksTall) ?: continue
            val newBts = levelBtsAt(sourceData, x, y, blocksWide) ?: continue
            if (oldWord != newWord || oldBts != newBts) {
                edits += TileEdit(x, y, oldWord, newWord, oldBts, newBts)
            }
        }
        if (canEditEmbeddedLayer2()) {
            for (y in 0 until blocksTall) for (x in 0 until blocksWide) {
                val oldWord = readLayer2BlockWord(x, y)
                val newWord = levelWordAt(sourceData, x, y, TILE_EDIT_LAYER_2, blocksWide, blocksTall) ?: continue
                if (oldWord != newWord) {
                    edits += TileEdit(x, y, oldWord, newWord, layer = TILE_EDIT_LAYER_2)
                }
            }
        }
        val sourceName = stateDisplayName(sourceState)
        applyBulkEdits("Copy layout from $sourceName", edits)
        return edits.size
    }

    /** Apply the selected tile rectangle as independent edits in chosen states. */
    fun applySelectionToStates(targetStateIds: Set<String>, romParser: RomParser): Int {
        if (hasManagedLayouts()) { postStatus("Apply selection to other states is not source-aware yet. Use Copy another state's layout, then edit its source."); return 0 }
        val bounds = mapSelectionBounds() ?: return 0
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.ensureStateManifest(romParser)
        val sourceName = selectedStateEdits(roomEdits)?.let(::stateDisplayName) ?: "selected state"
        val layer = if (activeRoomLayer == RoomEditLayer.LAYER2) TILE_EDIT_LAYER_2 else TILE_EDIT_LAYER_1
        var editCount = 0
        for (target in roomEdits.states.filter { it.id in targetStateIds && it.id != currentStateId }) {
            val targetData = effectiveLevelDataForState(roomEdits, target, romParser) ?: continue
            val edits = mutableListOf<TileEdit>()
            for (y in bounds.minY..bounds.maxY) for (x in bounds.minX..bounds.maxX) {
                val oldWord = levelWordAt(targetData, x, y, layer, workingBlocksWide, workingBlocksTall) ?: continue
                val newWord = if (layer == TILE_EDIT_LAYER_2) readLayer2BlockWord(x, y) else readBlockWord(x, y)
                val oldBts = if (layer == TILE_EDIT_LAYER_1) {
                    levelBtsAt(targetData, x, y, workingBlocksWide) ?: continue
                } else 0
                val newBts = if (layer == TILE_EDIT_LAYER_1) readBts(x, y) else 0
                if (oldWord != newWord || oldBts != newBts) {
                    edits += TileEdit(x, y, oldWord, newWord, oldBts, newBts, layer)
                }
            }
            if (edits.isNotEmpty()) {
                makeLayoutUniqueForState(roomEdits, target, romParser)
                roomEdits.levelResourceOperations
                    .getOrPut(target.resources.level) { mutableListOf() }
                    .add(EditOperation("Apply selection from $sourceName", edits))
                editCount += edits.size
            }
        }
        if (editCount > 0) {
            dirty = true
            editVersion++
        }
        return editCount
    }

    fun revertCurrentStateLayoutEdits(romParser: RomParser): Boolean {
        if (hasManagedLayouts()) return revertSourceLayout()
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.ensureStateManifest(romParser)
        val state = selectedStateEdits(roomEdits) ?: return false
        val removed = roomEdits.levelResourceOperations.remove(state.resources.level)?.isNotEmpty() == true
        if (!removed) return false
        layoutEditScopes.remove(layoutScopeKey(currentRoomId, state.resources.level))
        dirty = true
        switchRoomState(state.id, romParser)
        editVersion++
        return true
    }

    /** Replace this state's layout with another state's and keep their future shared edits linked. */
    fun shareCurrentLayoutWithState(sourceStateId: String, romParser: RomParser): Boolean {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.ensureStateManifest(romParser)
        val target = selectedStateEdits(roomEdits) ?: return false
        val source = roomEdits.stateEditsForId(sourceStateId) ?: return false
        if (source.id == target.id) return false

        if (hasManagedLayouts()) {
            return runCatching {
                val bridge = requireNotNull(asmPlmSourceBridge)
                require(matchesRoomSourceContext(bridge.catalog.parser) && currentRomParser === romParser) { "The room ROM changed; reopen its layout." }
                bridge.layouts.changeLink(currentRoomId, target.id, source.resources.level, clone = false)
                switchRoomState(target.id, romParser)
                layoutEditScopes[layoutScopeKey(currentRoomId, source.resources.level)] = LayoutEditScope.ALL_SHARING_STATES
                dirty = true; editVersion++
                true
            }.getOrElse { postStatus("Layout sharing blocked: ${it.message}"); false }
        }

        val legacySourceTileOperations = source.operations.filter { it.edits.isNotEmpty() }
        val destinationResourceId = if (legacySourceTileOperations.isEmpty()) {
            source.resources.level
        } else {
            val sourceLayout = effectiveLevelDataForState(roomEdits, source, romParser) ?: return false
            val sourceBaseline = effectiveLevelDataForState(
                roomEdits = roomEdits,
                state = source,
                romParser = romParser,
                followLinkedResource = false,
                includeSharedOperations = false,
                includeStateOperations = false,
            ) ?: return false
            var ordinal = 1
            var candidate: String
            do {
                candidate = "level-shared-$ordinal"
                ordinal++
            } while (
                roomEdits.states.any { it.resources.level == candidate } ||
                candidate in roomEdits.levelResourceOperations
            )
            val edits = buildLevelDataDiff(
                oldData = sourceBaseline,
                newData = sourceLayout,
                blocksWide = workingBlocksWide,
                blocksTall = workingBlocksTall,
            )
            if (edits.isNotEmpty()) {
                roomEdits.levelResourceOperations[candidate] = mutableListOf(
                    EditOperation("Share layout from ${stateDisplayName(source)}", edits)
                )
            }
            stripTileEdits(source.operations)
            source.resources = source.resources.copy(level = candidate)
            source.levelResourceChanged = false
            candidate
        }

        stripTileEdits(target.operations)
        target.resources = target.resources.copy(level = destinationResourceId)
        target.levelResourceChanged = true
        pruneUnusedLevelResources(roomEdits)
        layoutEditScopes[layoutScopeKey(currentRoomId, destinationResourceId)] = LayoutEditScope.ALL_SHARING_STATES
        dirty = true
        switchRoomState(target.id, romParser)
        layoutEditScopes[layoutScopeKey(currentRoomId, destinationResourceId)] = LayoutEditScope.ALL_SHARING_STATES
        editVersion++
        return true
    }

    fun revertSharedLayoutEdits(romParser: RomParser): Boolean {
        if (hasManagedLayouts()) return revertSourceLayout()
        val context = currentLayoutEditingContext() ?: return false
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        val removed = roomEdits.levelResourceOperations.remove(context.resourceId)?.isNotEmpty() == true
        if (!removed) return false
        layoutEditScopes.remove(layoutScopeKey(currentRoomId, context.resourceId))
        dirty = true
        switchRoomState(context.stateId, romParser)
        editVersion++
        return true
    }

    private fun revertSourceLayout(): Boolean {
        val parser = currentRomParser ?: return false
        val state = currentStateEdits() ?: return false
        val header = parser.readRoomHeader(currentRoomId) ?: return false
        val runtime = parser.parseRoomStatesWithData(currentRoomId).getOrNull(state.baseSourceStateIndex() ?: -1) ?: return false
        var native = parser.decompressLZ2(runtime.levelDataPtr)
        if (workingBlocksWide != header.width * 16 || workingBlocksTall != header.height * 16)
            native = resizeLevelData(native, header.width, header.height, workingBlocksWide / 16, workingBlocksTall / 16)
        val current = workingLevelData ?: return false
        val edits = buildLevelDataDiff(current, native, workingBlocksWide, workingBlocksTall)
        if (edits.isEmpty()) return false
        applyBulkEdits("Revert layout to native geometry", edits)
        return layoutPreviewProblem == null && workingLevelData?.contentEquals(native) == true
    }

    fun selectItemEditScope(scope: ItemStateScope) {
        itemEditScope = scope
    }

    private fun plmScopeKey(plm: RomParser.PlmEntry): String {
        val family = itemFamilyName(plm.id) ?: when {
            RomParser.isDoorCapPlm(plm.id) -> "door-cap"
            RomParser.isGatePlm(plm.id) -> "gate"
            else -> plm.id.toString()
        }
        return "$currentRoomId:${currentPlmStateId()}:${plm.x}:${plm.y}:$family"
    }

    fun plmEditScope(plm: RomParser.PlmEntry): ItemStateScope =
        plmEditScopeOverrides[plmScopeKey(plm)] ?: plmStateMembership(plm).editScope

    fun selectPlmEditScope(plm: RomParser.PlmEntry, scope: ItemStateScope) {
        if (plm in _workingPlms) plmEditScopeOverrides[plmScopeKey(plm)] = scope
    }

    private fun currentPlmChanges(): MutableList<PlmChange> {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        return currentStateEdits()?.plmChanges ?: roomEdits.plmChanges
    }

    private fun itemScopeSnapshot(): ItemScopeSnapshot {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        return ItemScopeSnapshot(
            commonChanges = roomEdits.plmChanges.toList(),
            stateChanges = roomEdits.states.associate { it.id to it.plmChanges.toList() },
            workingPlms = _workingPlms.toList(),
            saveSpawns = roomEdits.saveStationSpawns.toList(),
            commonScrollCommands = roomEdits.customScrollCommands.mapValues { it.value.toList() },
            stateScrollCommands = roomEdits.states.associate { state ->
                state.id to state.customScrollCommands.mapValues { it.value.toList() }
            },
        )
    }

    private fun restoreItemScopeSnapshot(snapshot: ItemScopeSnapshot) {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.plmChanges.clear()
        roomEdits.plmChanges.addAll(snapshot.commonChanges)
        for (state in roomEdits.states) {
            state.plmChanges.clear()
            state.plmChanges.addAll(snapshot.stateChanges[state.id].orEmpty())
        }
        _workingPlms.clear()
        _workingPlms.addAll(snapshot.workingPlms)
        roomEdits.saveStationSpawns.clear()
        roomEdits.saveStationSpawns.addAll(snapshot.saveSpawns)
        roomEdits.customScrollCommands.clear()
        snapshot.commonScrollCommands.forEach { (id, commands) -> roomEdits.customScrollCommands[id] = commands.toMutableList() }
        roomEdits.states.forEach { state ->
            state.customScrollCommands.clear()
            snapshot.stateScrollCommands[state.id].orEmpty().forEach { (id, commands) -> state.customScrollCommands[id] = commands.toMutableList() }
        }
    }

    private fun itemFamilyName(plmId: Int): String? =
        (customItemNameForPlm(plmId) ?: RomParser.itemNameForPlm(plmId))?.lowercase()

    private fun applyPlmChanges(
        source: List<RomParser.PlmEntry>,
        changes: List<PlmChange>,
    ): List<RomParser.PlmEntry> {
        val result = source.toMutableList()
        for (change in changes) {
            when (change.action) {
                "add" -> result.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                "remove" -> result.removeAll {
                    it.id == change.plmId && it.x == change.x && it.y == change.y
                }
            }
        }
        return result
    }

    private fun effectiveAuthoredStatePlms(): List<Pair<String, List<RomParser.PlmEntry>>> {
        val parser = currentRomParser ?: return listOf("This state" to _workingPlms.toList())
        val roomEdits = project.rooms[project.roomKey(currentRoomId)]
        if (hasManagedPlms()) return roomEdits!!.states.map { state ->
            val view = requireNotNull(asmPlmSourceBridge).managedView(currentRoomId, state.id)
            require(view.problem == null) { view.problem ?: "Review invalid object source before editing." }
            stateDisplayName(state) to requireNotNull(view.document).entries
        }
        val runtimeStates = parser.parseRoomStatesWithData(currentRoomId)
        if (roomEdits?.states.isNullOrEmpty()) {
            return runtimeStates.mapIndexed { index, runtimeState ->
                val base = parser.parsePlmSet(runtimeState.plmSetPtr)
                val effective = applyPlmChanges(base, roomEdits?.plmChanges.orEmpty())
                "State ${index + 1}" to effective
            }
        }
        val authoredRoom = checkNotNull(roomEdits)
        return authoredRoom.states.mapNotNull { state ->
            val sourceIndex = state.baseSourceStateIndex() ?: return@mapNotNull null
            val runtimeState = runtimeStates.getOrNull(sourceIndex) ?: return@mapNotNull null
            val base = parser.parsePlmSet(runtimeState.plmSetPtr)
            val common = applyPlmChanges(base, authoredRoom.plmChanges)
            stateDisplayName(state) to applyPlmChanges(common, state.plmChanges)
        }
    }

    /** Equal placement AND linked behavior. Named scroll rows use param=0 until Build. */
    fun plmStateMembership(plm: RomParser.PlmEntry): PlmStateMembership = runCatching {
        val lists = effectiveAuthoredStatePlms()
        val room = project.rooms[project.roomKey(currentRoomId)]
        val currentCommands = if (plm.id == 0xB703) scrollCommandsFor(plm) else null
        val matching = lists.withIndex().count { (index, list) ->
            val entry = list.second.singleOrNull { it == plm } ?: return@count false
            val bodyIds = gatePartnerIds(plm.id)
            if (bodyIds.isNotEmpty() && list.second.filter { it.x == plm.x && it.y == plm.y && it.id in bodyIds } !=
                _workingPlms.filter { it.x == plm.x && it.y == plm.y && it.id in bodyIds }) return@count false
            if (plm.id != 0xB703) return@count true
            val stateId = room?.states?.getOrNull(index)?.id
            currentCommands != null && scrollCommandsFor(entry, stateId) == currentCommands
        }
        PlmStateMembership(matching, lists.size)
    }.getOrElse { PlmStateMembership(0, 0) }

    fun configuredItemScope(plm: RomParser.PlmEntry): ItemStateScope? =
        if (isEditorItemPlm(plm.id)) plmStateMembership(plm).editScope else null

    private fun samePlmFamily(first: Int, second: Int): Boolean = first == second ||
        (itemFamilyName(first)?.let { it == itemFamilyName(second) } == true) ||
        (RomParser.isDoorCapPlm(first) && RomParser.isDoorCapPlm(second)) ||
        (first in setOf(0xC826, 0xC82A) && second in setOf(0xC826, 0xC82A)) ||
        (first in setOf(0xC82E, 0xC832) && second in setOf(0xC82E, 0xC832))

    private fun gateBodyIds(shotId: Int): Set<Int> = when (shotId) {
        0xC836 -> setOf(0xC826, 0xC82A)
        0xC83A -> setOf(0xC82E, 0xC832)
        else -> emptySet()
    }

    private fun gatePartnerIds(plmId: Int): Set<Int> = when (plmId) {
        0xC826, 0xC82A -> setOf(0xC836)
        0xC82E, 0xC832 -> setOf(0xC83A)
        else -> gateBodyIds(plmId)
    }

    private fun plmPlacementScope(plmId: Int, x: Int, y: Int, override: ItemStateScope?): ItemStateScope {
        if (override != null) return override
        val existing = _workingPlms.singleOrNull { it.x == x && it.y == y && samePlmFamily(it.id, plmId) }
        return existing?.let { plmEditScope(it) } ?: itemEditScope
    }

    /** Never let the placement preference erase another branch's distinct object or behavior. */
    private fun validatePlmPlacement(plmId: Int, x: Int, y: Int, param: Int, scope: ItemStateScope,
                                     commands: List<ScrollCommand>? = null, geometryReference: ByteArray? = null): Boolean = runCatching {
        require(x in 0 until workingBlocksWide && y in 0 until workingBlocksTall && x <= 255 && y <= 255) {
            "PLM placement must be inside the room and fit byte coordinates."
        }
        require(plmId in 1..0xFFFF && param in 0..0xFFFF) { "PLM types and parameters must fit a word." }
        if (plmId in setOf(0xC836, 0xC83A)) require(param in 0..14 && param % 2 == 0) { "Gate color/direction must use an even selector from 0 to 14." }
        if (plmId == 0xB76F) require((param and 255) in 0..7) { "Ordinary saves have only eight slots (0–7)." }
        if (commands != null) require(commands.all {
            it.screenIndex in 0 until (workingBlocksWide / 16) * (workingBlocksTall / 16) && it.scrollValue in 0..2
        }) { "Scroll commands must target existing room screens and valid colors." }
        if (isEditorItemPlm(plmId) && !validateItemPlacement(plmId, x, y, scope)) return false
        validatePlmSharedGeometry(plmId, x, y, scope == ItemStateScope.ALL_STATES, geometryReference)
        if (scope == ItemStateScope.THIS_STATE) return true
        val lists = effectiveAuthoredStatePlms()
        val room = project.rooms[project.roomKey(currentRoomId)]
        val selected = _workingPlms.filter { it.x == x && it.y == y && samePlmFamily(it.id, plmId) }
        for ((index, state) in lists.withIndex()) {
            val here = state.second.filter { it.x == x && it.y == y }
            require(here.count { samePlmFamily(it.id, plmId) } <= 1) {
                "Multiple matching PLMs in ${state.first}; review them before applying to all states."
            }
            for (old in here) {
                // Gate connectors and their shot blocks can legitimately share a tile.
                val gatePair = old.id in gateBodyIds(plmId) || plmId in gateBodyIds(old.id)
                // $DB44 selects an event pre-instruction and sleeps, without using X/Y
                // or drawing tiles ($84:DB1E..DB48). It is a verified script-only PLM.
                // Keep unknown helpers conservative; a name alone does not prove no footprint.
                val scriptOnly = old.id == 0xDB44 || plmId == 0xDB44
                val unrelated = !samePlmFamily(old.id, plmId) && !gatePair && !scriptOnly &&
                    (isEditorItemPlm(old.id) || RomParser.isStationPlm(old.id) || RomParser.isDoorCapPlm(old.id) ||
                        isEditorItemPlm(plmId) || RomParser.isStationPlm(plmId) || RomParser.isDoorCapPlm(plmId))
                require(!unrelated) { "${RomParser.plmDisplayName(old.id, old.param)} occupies this position in ${state.first}. Use This state or resolve the conflict first." }
                if (!samePlmFamily(old.id, plmId)) continue
                require(isEditorItemPlm(plmId) || old in selected || (old.id == plmId && old.param == param) ||
                    (plmId == 0xB703 && commands != null && scrollCommandsFor(old, room?.states?.getOrNull(index)?.id) == commands)) {
                    "A different ${RomParser.plmDisplayName(old.id, old.param)} exists in ${state.first}. Use This state; it was not overwritten."
                }
                if (plmId == 0xB703 && old !in selected) {
                    require(commands == null || scrollCommandsFor(old, room?.states?.getOrNull(index)?.id) == commands) {
                        "Different scroll commands exist in ${state.first}. Use This state."
                    }
                } else if (plmId == 0xB703 && commands != null && old in selected &&
                    room?.states?.getOrNull(index)?.id != currentPlmStateId()) {
                    require(scrollCommandsFor(old, room?.states?.getOrNull(index)?.id) == scrollCommandsFor(old)) {
                        "Different scroll commands exist in ${state.first}. Use This state."
                    }
                }
            }
        }
        true
    }.getOrElse { postStatus(it.message ?: "PLM scope needs review"); false }

    /** The same coordinates must not silently target a different doorway/obstruction in another state. */
    private fun validatePlmSharedGeometry(plmId: Int, x: Int, y: Int, allStates: Boolean, geometryReference: ByteArray?) {
        val offsets = when {
            RomParser.isDoorCapPlm(plmId) -> if (RomParser.doorCapIsHorizontal(plmId))
                (0..3).map { it to 0 } else (0..3).map { 0 to it }
            plmId in setOf(0xC836, 0xC826, 0xC82A) -> (0..5).map { 0 to it }
            plmId in setOf(0xC83A, 0xC82E, 0xC832) -> (0..5).map { 0 to -it }
            else -> return
        }
        val points = offsets.map { (dx, dy) -> x + dx to y + dy }
        require(points.all { (px, py) -> px in 0 until workingBlocksWide && py in 0 until workingBlocksTall }) {
            "The complete gate/door-cap footprint must fit the room."
        }
        if (!allStates) return
        val parser = currentRomParser ?: return
        val room = project.rooms[project.roomKey(currentRoomId)] ?: return
        if (room.states.size < 2) return
        fun collision(data: ByteArray, point: Pair<Int, Int>): Pair<Int?, Int?> =
            levelWordAt(data, point.first, point.second, TILE_EDIT_LAYER_1, workingBlocksWide, workingBlocksTall)?.ushr(12) to
                levelBtsAt(data, point.first, point.second, workingBlocksWide)
        val selected = geometryReference ?: workingLevelData ?: error("Room layout is unavailable.")
        for (state in room.states.filter { it.id != currentPlmStateId() }) {
            val preview = if (geometryReference == null && state.resources.level == currentStateEdits()?.resources?.level)
                pendingEdits.toList() else emptyList()
            val other = effectiveLevelDataForState(room, state, parser, prospectiveLayoutEdits = preview)
                ?: error("State layout is unavailable.")
            require(points.all { collision(selected, it) == collision(other, it) }) {
                "Gate/door-cap geometry differs in ${stateDisplayName(state)}. Use This state or align the layouts first."
            }
        }
    }

    private fun itemEntriesAt(x: Int, y: Int): List<Pair<String, RomParser.PlmEntry>> =
        effectiveAuthoredStatePlms().flatMap { (stateName, plms) ->
            plms.filter { it.x == x && it.y == y && isEditorItemPlm(it.id) }
                .map { stateName to it }
        }

    private fun validateItemPlacement(plmId: Int, x: Int, y: Int, scope: ItemStateScope): Boolean {
        val family = itemFamilyName(plmId) ?: return true
        val candidates = if (scope == ItemStateScope.ALL_STATES) {
            itemEntriesAt(x, y)
        } else {
            _workingPlms.filter { it.x == x && it.y == y && isEditorItemPlm(it.id) }
                .map { "This state" to it }
        }
        val conflicts = candidates.filter { (_, entry) -> itemFamilyName(entry.id) != family }
        if (conflicts.isEmpty()) return true
        val states = conflicts.map { it.first }.distinct().joinToString()
        val existing = conflicts.mapNotNull { itemFamilyName(it.second.id) }.distinct().joinToString()
        postStatus(
            "Cannot place ${itemFamilyName(plmId)} at ($x,$y) for " +
                "${if (scope == ItemStateScope.ALL_STATES) "all states" else "this state"}: " +
                "$existing already occupies that item position in $states. Remove it first.",
        )
        return false
    }

    /**
     * Canonicalize one object into the room-wide PLM stream. State-local
     * overrides for the same object family are removed so no current branch can
     * mask it, while the room-wide remove/add pair also governs future states.
     */
    private fun addItemToAllStates(plmId: Int, x: Int, y: Int, param: Int): List<PlmChange> {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        val familyEntries = effectiveAuthoredStatePlms().flatMap { it.second }
            .filter { it.x == x && it.y == y && samePlmFamily(it.id, plmId) }
        val ids = (familyEntries.map { it.id } + plmId).distinct()
        fun isFamilyChange(change: PlmChange): Boolean =
            change.x == x && change.y == y && samePlmFamily(change.plmId, plmId)

        roomEdits.plmChanges.removeAll(::isFamilyChange)
        for (state in roomEdits.states) state.plmChanges.removeAll(::isFamilyChange)

        val removals = ids.map { PlmChange("remove", it, x, y, param) }
        roomEdits.plmChanges.addAll(removals)
        roomEdits.plmChanges.add(PlmChange("add", plmId, x, y, param))
        _workingPlms.removeAll { it.x == x && it.y == y && samePlmFamily(it.id, plmId) }
        _workingPlms.add(RomParser.PlmEntry(plmId, x, y, param))
        return familyEntries.distinct().map { PlmChange("remove", it.id, it.x, it.y, it.param) }
    }

    private fun removeItemFromAllStates(plm: RomParser.PlmEntry): List<PlmChange> {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        val familyEntries = effectiveAuthoredStatePlms().flatMap { it.second }
            .filter { it.x == plm.x && it.y == plm.y && samePlmFamily(it.id, plm.id) }
        val ids = (familyEntries.map { it.id } + plm.id).distinct()
        fun isFamilyChange(change: PlmChange): Boolean =
            change.x == plm.x && change.y == plm.y && samePlmFamily(change.plmId, plm.id)

        roomEdits.plmChanges.removeAll(::isFamilyChange)
        for (state in roomEdits.states) state.plmChanges.removeAll(::isFamilyChange)
        val removals = ids.map { PlmChange("remove", it, plm.x, plm.y, plm.param) }
        roomEdits.plmChanges.addAll(removals)
        _workingPlms.removeAll {
            it.x == plm.x && it.y == plm.y && samePlmFamily(it.id, plm.id)
        }
        return removals
    }

    private fun currentEnemyChanges(): MutableList<EnemyChange> =
        currentStateEdits()?.enemyChanges ?: project.getOrCreateRoom(currentRoomId).enemyChanges

    private fun currentScrollChanges(): MutableList<ScrollChange> =
        currentStateEdits()?.scrollChanges ?: project.getOrCreateRoom(currentRoomId).scrollChanges

    private fun currentScrollCommands(): MutableMap<String, MutableList<ScrollCommand>> =
        currentStateEdits()?.customScrollCommands ?: project.getOrCreateRoom(currentRoomId).customScrollCommands

    private fun replaySavedRoomEdits(savedRoom: RoomEdits, effectiveWidth: Int, roomKey: String) {
        // Replay saved tile edits
        if (savedRoom.operations.isNotEmpty()) {
            var count = 0
            for (op in savedRoom.operations) {
                for (edit in op.edits) {
                    applyTileEdit(edit, useNew = true)
                    count++
                }
                undoStack.add(op)
            }
            undoVersion++
            editorLog("Replayed $count saved edits for room 0x$roomKey")
        }
        // Replay saved PLM changes
        for (change in savedRoom.plmChanges) {
            when (change.action) {
                "add" -> _workingPlms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                "remove" -> _workingPlms.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y }
            }
        }
        // Replay saved door changes (last change per index wins)
        for (dc in savedRoom.effectiveDoorChanges) {
            if (dc.doorIndex in 0 until _workingDoors.size) {
                _workingDoors[dc.doorIndex] = RomParser.DoorEntry(
                    destRoomPtr = dc.destRoomPtr,
                    bitflag = dc.bitflag,
                    doorCapCode = dc.doorCapCode,
                    screenX = dc.screenX,
                    screenY = dc.screenY,
                    distFromDoor = dc.distFromDoor,
                    entryCode = dc.entryCode,
                    doorDefPtr = _workingDoors[dc.doorIndex].doorDefPtr,
                )
            }
        }
        // Replay saved scroll changes (use effective width, not ROM width)
        for (sc in savedRoom.scrollChanges) {
            val idx = sc.screenY * effectiveWidth + sc.screenX
            if (idx in _workingScrolls.indices) _workingScrolls[idx] = sc.newValue
        }
        if (savedRoom.scrollChanges.isNotEmpty()) scrollVersion++
        // Replay saved enemy changes (including extra fields)
        for (ec in savedRoom.enemyChanges) {
            when (ec.action) {
                "add" -> _workingEnemies.add(
                    RomParser.EnemyEntry(ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                        ec.extra1, ec.extra2, ec.extra3)
                )
                "remove" -> _workingEnemies.removeAll {
                    it.id == ec.enemyId && it.x == ec.origX && it.y == ec.origY
                }
                "update" -> {
                    val idx = _workingEnemies.indexOfFirst {
                        it.id == ec.enemyId && it.x == ec.origX && it.y == ec.origY
                    }
                    if (idx >= 0) {
                        _workingEnemies[idx] = RomParser.EnemyEntry(
                            ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                            ec.extra1, ec.extra2, ec.extra3
                        )
                    }
                }
            }
        }
        selectedStateEdits(savedRoom)?.let { stateEdits ->
            savedRoom.levelResourceOperations[stateEdits.resources.level]?.let { resourceOperations ->
                var resourceTileCount = 0
                for (operation in resourceOperations) {
                    for (edit in operation.edits) {
                        applyTileEdit(edit, useNew = true)
                        resourceTileCount++
                    }
                    undoStack.add(operation)
                }
                if (resourceOperations.isNotEmpty()) undoVersion++
                if (resourceTileCount > 0) {
                    editorLog(
                        "Replayed $resourceTileCount layout-resource edits for room 0x$roomKey " +
                            "resource ${stateEdits.resources.level}"
                    )
                }
            }
            replayStateEdits(stateEdits, effectiveWidth, roomKey)
        }
    }

    private fun replayStateEdits(stateEdits: RoomStateEdits, effectiveWidth: Int, roomKey: String) {
        var tileCount = 0
        for (op in stateEdits.operations) {
            for (edit in op.edits) {
                applyTileEdit(edit, useNew = true)
                tileCount++
            }
            undoStack.add(op)
        }
        if (stateEdits.operations.isNotEmpty()) undoVersion++
        for (change in stateEdits.plmChanges) {
            when (change.action) {
                "add" -> _workingPlms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                "remove" -> _workingPlms.removeAll {
                    it.id == change.plmId && it.x == change.x && it.y == change.y
                }
            }
        }
        for (change in stateEdits.scrollChanges) {
            val index = change.screenY * effectiveWidth + change.screenX
            if (index in _workingScrolls.indices) _workingScrolls[index] = change.newValue
        }
        if (stateEdits.scrollChanges.isNotEmpty()) scrollVersion++
        for (change in stateEdits.enemyChanges) {
            when (change.action) {
                "add" -> _workingEnemies.add(
                    RomParser.EnemyEntry(
                        change.enemyId, change.x, change.y, change.initParam, change.properties,
                        change.extra1, change.extra2, change.extra3,
                    )
                )
                "remove" -> _workingEnemies.removeAll {
                    it.id == change.enemyId && it.x == change.origX && it.y == change.origY
                }
                "update" -> {
                    val index = _workingEnemies.indexOfFirst {
                        it.id == change.enemyId && it.x == change.origX && it.y == change.origY
                    }
                    if (index >= 0) {
                        _workingEnemies[index] = RomParser.EnemyEntry(
                            change.enemyId, change.x, change.y, change.initParam, change.properties,
                            change.extra1, change.extra2, change.extra3,
                        )
                    }
                }
            }
        }
        if (tileCount > 0) {
            editorLog("Replayed $tileCount state-specific edits for room 0x$roomKey state ${stateEdits.id}")
        }
    }

    /**
     * Preview a different room state from its complete set of state-owned
     * resources. Common room edits are replayed because they apply to every state;
     * selecting a preview must not make already-saved edits disappear.
     */
    fun switchRoomState(stateIndex: Int, romParser: RomParser) {
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.ensureStateManifest(romParser)
        val stateId = roomEdits.states.firstOrNull { it.sourceStateIndex == stateIndex }?.id ?: return
        switchRoomState(stateId, romParser)
    }

    fun switchRoomState(stateId: String, romParser: RomParser) {
        dismissPendingLayoutEditScope()
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.ensureStateManifest(romParser)
        val selectedState = roomEdits.stateEditsForId(stateId) ?: return
        val baseStateIndex = selectedState.baseSourceStateIndex() ?: return
        val states = romParser.parseRoomStatesWithData(currentRoomId)
        val state = states.getOrNull(baseStateIndex) ?: return
        currentStateIndex = baseStateIndex
        currentStateId = selectedState.id
        val room = romParser.readRoomHeader(currentRoomId) ?: return
        val roomKey = project.roomKey(currentRoomId)
        val savedRoom = project.rooms[roomKey]
        val hc = savedRoom?.effectiveHeaderChange
        val effectiveWidth = hc?.width ?: room.width
        val effectiveHeight = hc?.height ?: room.height
        val stateDataChange = selectedState.effectiveStateDataChange
        val commonStateDataChange = savedRoom?.effectiveStateDataChange

        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        undoStack.clear()
        managedPlmUndo.clear()
        redoStack.clear()
        pendingEdits.clear()
        pendingPositions.clear()
        pendingPlmAdds.clear()
        pendingPlmRemoves.clear()
        pendingAllStateItemBefore = null
        pendingPlmGeometry = null
        pendingSourcePattern = null
        itemScopeSnapshots.clear()
        undoVersion++

        currentTilesetId = stateDataChange?.tileset ?: commonStateDataChange?.tileset ?: state.tileset
        currentBgScrolling = stateDataChange?.bgScrolling ?: commonStateDataChange?.bgScrolling ?: state.bgScrolling
        val tg = TileGraphics(romParser)
        if (tg.loadTileset(currentTilesetId)) {
            applyCustomGfxToTileGraphics(tg, currentTilesetId)
            tileGraphics = tg
        }

        val levelOwner = levelResourceOwner(roomEdits, selectedState)
        val levelOwnerIndex = levelOwner.baseSourceStateIndex() ?: baseStateIndex
        val levelOwnerState = states.getOrNull(levelOwnerIndex) ?: state
        val levelDataPtr = levelOwnerState.levelDataPtr.takeIf { it != 0 } ?: room.levelDataPtr
        var levelData = romParser.decompressLZ2(levelDataPtr)
        if (effectiveWidth != room.width || effectiveHeight != room.height) {
            levelData = resizeLevelData(
                levelData,
                room.width,
                room.height,
                effectiveWidth,
                effectiveHeight,
            )
        }
        originalLevelData = levelData.copyOf()
        workingLevelData = levelData.copyOf()
        workingBlocksWide = effectiveWidth * 16
        workingBlocksTall = effectiveHeight * 16
        if (!canEditEmbeddedLayer2()) activeRoomLayer = RoomEditLayer.LAYER1

        // Reload enemies from this state's enemy set pointer
        _workingEnemies.clear()
        _workingEnemies.addAll(romParser.parseEnemyPopulation(state.enemySetPtr))

        // Reload PLMs from this state's PLM set pointer
        _workingPlms.clear()
        _workingPlms.addAll(romParser.parsePlmSet(state.plmSetPtr))
        originalPlmCount = _workingPlms.size

        // Reload scroll data from this state's scroll pointer
        val romScrolls = romParser.parseScrollData(state.scrollPtr, room.width, room.height)
        _originalScrolls = if (effectiveWidth != room.width || effectiveHeight != room.height) {
            resizeScrollGrid(romScrolls, room.width, room.height, effectiveWidth, effectiveHeight)
        } else {
            romScrolls
        }
        _workingScrolls = if (hasManagedInitialScrolls()) IntArray(0) else _originalScrolls.copyOf()
        scrollVersion++

        if (savedRoom != null) replaySavedRoomEdits(savedRoom, effectiveWidth, roomKey)

        refreshLinkedPlms()
        _editVersionState.value++
        editorLog(
            "Switched to state '${selectedState.id}' (source $baseStateIndex): " +
                "${stateDisplayName(selectedState)} (enemies=${_workingEnemies.size}, PLMs=${_workingPlms.size})"
        )
    }

    /**
     * Overlay the selected runtime state's complete 26-byte record onto the
     * shared room header. Canvas renderers use this so FX, liquids, separate
     * backgrounds, and other state-owned resources change with the preview.
     */
    internal fun applyCurrentStateData(room: Room, romParser: RomParser): Room {
        val roomEdits = project.rooms[project.roomKey(room.roomId)]
        val selectedState = selectedStateEdits(roomEdits)
        val baseStateIndex = selectedState?.baseSourceStateIndex() ?: currentStateIndex
        val states = romParser.parseRoomStatesWithData(room.roomId)
        val state = states.getOrNull(baseStateIndex) ?: return room
        val levelState = if (roomEdits != null && selectedState != null) {
            val ownerIndex = levelResourceOwner(roomEdits, selectedState).baseSourceStateIndex()
            ownerIndex?.let(states::getOrNull) ?: state
        } else {
            state
        }
        val stateDataChange = selectedState?.effectiveStateDataChange
        val commonStateDataChange = roomEdits?.effectiveStateDataChange
        return room.copy(
            levelDataPtr = levelState.levelDataPtr,
            tileset = stateDataChange?.tileset ?: commonStateDataChange?.tileset ?: state.tileset,
            musicData = stateDataChange?.musicData ?: commonStateDataChange?.musicData ?: state.musicData,
            musicTrack = stateDataChange?.musicTrack ?: commonStateDataChange?.musicTrack ?: state.musicTrack,
            fxPtr = state.fxPtr,
            enemySetPtr = state.enemySetPtr,
            enemyGfxPtr = state.enemyGfxPtr,
            bgScrolling = stateDataChange?.bgScrolling ?: commonStateDataChange?.bgScrolling ?: state.bgScrolling,
            roomScrollsPtr = state.scrollPtr,
            xraySpecialCasingPtr = state.xraySpecialCasingPtr,
            mainAsmPtr = state.mainAsmPtr,
            plmSetPtr = state.plmSetPtr,
            bgDataPtr = state.bgDataPtr,
            setupAsmPtr = state.setupAsmPtr,
        )
    }

    fun readBlockWord(bx: Int, by: Int): Int {
        val data = workingLevelData ?: return 0
        val idx = by * workingBlocksWide + bx
        val offset = 2 + idx * 2
        if (offset + 1 >= data.size) return 0
        return ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)
    }

    fun readBts(bx: Int, by: Int): Int {
        val data = workingLevelData ?: return 0
        val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val idx = by * workingBlocksWide + bx
        val btsOffset = 2 + layer1Size + idx
        if (btsOffset >= data.size) return 0
        return data[btsOffset].toInt() and 0xFF
    }

    private fun embeddedLayer2StartOffset(data: ByteArray): Int? {
        if (workingBlocksWide <= 0 || workingBlocksTall <= 0) return null
        return embeddedLayer2StartOffset(data, workingBlocksWide * workingBlocksTall)
    }

    fun canEditEmbeddedLayer2(): Boolean =
        workingLevelData?.let { embeddedLayer2StartOffset(it) != null } == true

    fun readLayer2BlockWord(bx: Int, by: Int): Int {
        val data = workingLevelData ?: return 0
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return 0
        val start = embeddedLayer2StartOffset(data) ?: return 0
        val idx = by * workingBlocksWide + bx
        val offset = start + idx * 2
        if (offset + 1 >= data.size) return 0
        return ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)
    }

    private fun readOriginalBlockWord(bx: Int, by: Int): Int {
        val data = originalLevelData ?: return 0
        val idx = by * workingBlocksWide + bx
        val offset = 2 + idx * 2
        if (offset + 1 >= data.size) return 0
        return ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)
    }

    private fun readOriginalBts(bx: Int, by: Int): Int {
        val data = originalLevelData ?: return 0
        val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val idx = by * workingBlocksWide + bx
        val btsOffset = 2 + layer1Size + idx
        if (btsOffset >= data.size) return 0
        return data[btsOffset].toInt() and 0xFF
    }

    private fun writeBts(bx: Int, by: Int, bts: Int) {
        val data = workingLevelData ?: return
        val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val idx = by * workingBlocksWide + bx
        val btsOffset = 2 + layer1Size + idx
        if (btsOffset < data.size) data[btsOffset] = bts.toByte()
    }

    private fun writeBlockWord(bx: Int, by: Int, word: Int) {
        val data = workingLevelData ?: return
        val idx = by * workingBlocksWide + bx
        val offset = 2 + idx * 2
        if (offset + 1 >= data.size) return
        data[offset] = (word and 0xFF).toByte()
        data[offset + 1] = ((word shr 8) and 0xFF).toByte()
    }

    private fun writeLayer2BlockWord(bx: Int, by: Int, word: Int) {
        val data = workingLevelData ?: return
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return
        val start = embeddedLayer2StartOffset(data) ?: return
        val idx = by * workingBlocksWide + bx
        val offset = start + idx * 2
        if (offset + 1 >= data.size) return
        val layer2Word = word and 0x0FFF
        data[offset] = (layer2Word and 0xFF).toByte()
        data[offset + 1] = ((layer2Word shr 8) and 0xFF).toByte()
    }

    private fun applyTileEdit(edit: TileEdit, useNew: Boolean) {
        val word = if (useNew) edit.newBlockWord else edit.oldBlockWord
        val bts = if (useNew) edit.newBts else edit.oldBts
        if (edit.layer == TILE_EDIT_LAYER_2) {
            writeLayer2BlockWord(edit.blockX, edit.blockY, word)
        } else {
            writeBlockWord(edit.blockX, edit.blockY, word)
            writeBts(edit.blockX, edit.blockY, bts)
        }
    }

    // ─── Paint / Erase / Fill ───────────────────────────────────

    fun beginStroke() {
        // A lost mouse release must not strand a preview outside the undo/model history.
        if (pendingSourcePattern != null) rollbackSourcePattern()
        else if (hasManagedLayouts() && pendingEdits.isNotEmpty()) pendingEdits.asReversed().forEach { applyTileEdit(it, useNew = false) }
        pendingEdits.clear()
        pendingPositions.clear()
        pendingPlmAdds.clear()
        pendingPlmRemoves.clear()
        pendingAllStateItemBefore = null
        refreshLayoutSource()
        pendingPlmGeometry = workingLevelData?.copyOf()
    }

    private fun rollbackSourcePattern() {
        val stroke = pendingSourcePattern ?: return
        if (project === stroke.owner && currentRoomId == stroke.room.roomId &&
            workingBlocksWide == stroke.width && workingBlocksTall == stroke.height) {
            pendingEdits.asReversed().forEach { applyTileEdit(it, useNew = false) }
            _workingPlms.clear()
            _workingPlms.addAll(stroke.plmsBefore)
            refreshLinkedPlms()
            editVersion++
        }
        pendingSourcePattern = null
        pendingEdits.clear()
        pendingPositions.clear()
        pendingPlmAdds.clear()
        pendingPlmRemoves.clear()
    }

    private fun prepareSourcePattern(): Boolean = runCatching {
        if (pendingSourcePattern != null) return true
        require(pendingEdits.isEmpty()) { "Finish the current tile stroke before switching to a functional pattern." }
        if (!hasManagedPlms()) require(ensureCurrentPlmSource(currentRomParser?.readRoomHeader(currentRoomId)?.name ?: "Room")) {
            plmSourceMigrationProblem ?: "Object source is unavailable."
        }
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        val room = project.rooms.getValue(project.roomKey(currentRoomId))
        val state = requireNotNull(currentStateEdits()) { "Select a room state before painting objects." }
        val view = bridge.managedView(currentRoomId, state.id)
        require(view.problem == null) { view.problem ?: "Review invalid object source first." }
        pendingSourcePattern = SourcePatternStroke(project, room, state.id, state.resources.level,
            workingBlocksWide, workingBlocksTall, bridge.sourceText(), _workingPlms.toList())
        true
    }.getOrElse { postStatus("Pattern blocked: ${it.message}"); false }

    /** Paint the full brush at map position (bx, by). Returns true if anything changed. */
    fun paintAt(bx: Int, by: Int): Boolean {
        if (!allowOrQueueLayoutEdit {
                beginStroke()
                paintAt(bx, by)
                endStroke()
            }
        ) return false
        if (activeRoomLayer == RoomEditLayer.LAYER2) return paintLayer2At(bx, by)
        val b = brush ?: return false
        if (pendingPlmGeometry == null) pendingPlmGeometry = workingLevelData?.copyOf()
        val functional = (0 until b.rows).any { r -> (0 until b.cols).any { c -> b.plmAt(r, c)?.first?.let { it != 0 } == true } }
        if (functional) {
            if (hasManagedPlms() || usesRoomAsmAuthoring) {
                if (b.plmOverrides.values.any { it.first == 0xB703 }) {
                    postStatus("Use Add scroll trigger to place its command list; a tile pattern cannot copy that linked resource.")
                    return false
                }
                if (!prepareSourcePattern()) return false
            } else if (blockLinkedPlmMutation(allStates = itemEditScope == ItemStateScope.ALL_STATES)) return false
        }
        // A brush stamp may contain both decorative tiles and a functional PLM.
        // If the PLM cannot be represented (for example, all save slots are in
        // use), roll back this whole stamp while preserving earlier drag-stroke
        // stamps that are already pending.
        val editCheckpoint = pendingEdits.size
        val plmAddCheckpoint = pendingPlmAdds.size
        val plmRemoveCheckpoint = pendingPlmRemoves.size
        val sourceStampCheckpoint = pendingSourcePattern?.stamps?.size ?: 0
        val positionsBefore = pendingPositions.toSet()
        val pendingAllStateBeforeCheckpoint = pendingAllStateItemBefore
        val workingPlmsBefore = _workingPlms.toList()
        val roomKey = project.roomKey(currentRoomId)
        val existingRoomEdits = project.rooms[roomKey]
        val plmScopeBefore = itemScopeSnapshot()
        val roomSaveSpawnsBefore = existingRoomEdits?.saveStationSpawns?.toList()
        val dirtyBefore = dirty
        val editVersionBefore = editVersion
        fun rollbackStamp() {
            val newEdits = pendingEdits.subList(editCheckpoint, pendingEdits.size).toList()
            for (edit in newEdits.asReversed()) applyTileEdit(edit, useNew = false)
            for (edit in newEdits) {
                pendingPositions.remove(
                    (edit.blockX.toLong() shl 32) or (edit.blockY.toLong() and 0xFFFFFFFFL)
                )
            }
            pendingEdits.subList(editCheckpoint, pendingEdits.size).clear()
            pendingPlmAdds.subList(plmAddCheckpoint, pendingPlmAdds.size).clear()
            pendingPlmRemoves.subList(plmRemoveCheckpoint, pendingPlmRemoves.size).clear()
            pendingSourcePattern?.stamps?.let { it.subList(sourceStampCheckpoint, it.size).clear() }
            pendingPositions.clear()
            pendingPositions.addAll(positionsBefore)
            pendingAllStateItemBefore = pendingAllStateBeforeCheckpoint
            _workingPlms.clear()
            _workingPlms.addAll(workingPlmsBefore)
            if (existingRoomEdits == null) {
                project.rooms.remove(roomKey)
            } else {
                restoreItemScopeSnapshot(plmScopeBefore)
                existingRoomEdits.saveStationSpawns.clear()
                existingRoomEdits.saveStationSpawns.addAll(roomSaveSpawnsBefore.orEmpty())
            }
            dirty = dirtyBefore
            editVersion = editVersionBefore
        }
        var changed = false
        for (r in 0 until b.rows) {
            for (c in 0 until b.cols) {
                val cellKey = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
                if (cellKey in b.skipCells) continue
                val tx = bx + if (b.hFlip) (b.cols - 1 - c) else c
                val ty = by + if (b.vFlip) (b.rows - 1 - r) else r
                if (tx < 0 || ty < 0 || tx >= workingBlocksWide || ty >= workingBlocksTall) continue
                val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
                if (pendingPositions.contains(key)) continue
                val oldWord = readBlockWord(tx, ty)
                val newWord = b.blockWordAt(r, c)
                val oldBts = readBts(tx, ty)
                val newBts = b.btsAt(r, c)
                val tileChanged = oldWord != newWord || oldBts != newBts
                val plm = b.plmAt(r, c)
                if (!tileChanged && (pendingSourcePattern == null || plm?.first?.let { it != 0 } != true)) continue
                if (tileChanged) {
                    writeBlockWord(tx, ty, newWord)
                    writeBts(tx, ty, newBts)
                    pendingEdits.add(TileEdit(tx, ty, oldWord, newWord, oldBts, newBts))
                }
                pendingPositions.add(key)
                changed = true
                // Collect pattern PLMs for grouped undo (applied in endStroke)
                if (plm != null && plm.first != 0) {
                    val plmId = plm.first
                    val param = plm.second
                    val isItem = isEditorItemPlm(plmId)
                    val scope = plmPlacementScope(plmId, tx, ty, null)
                    if (isItem && !validateItemPlacement(plmId, tx, ty, scope)) {
                        rollbackStamp()
                        return false
                    }
                    val family = itemFamilyName(plmId)
                    val existing = _workingPlms.filter {
                        it.x == tx && it.y == ty &&
                            (it.id == plmId || (family != null && itemFamilyName(it.id) == family))
                    }
                    // Repainting an existing save-station pattern must retain its
                    // AreaSave index. Older builds treated the pattern's 0x8000
                    // parameter as a request for a new index, which could leave
                    // the PLM pointing at another room's save record.
                    val retainedSaveParam = existing.singleOrNull()
                        ?.takeIf { plmId == 0xB76F && param == 0x8000 }
                        ?.param
                    val retainedItemParam = existing.singleOrNull()
                        ?.takeIf { isItem && param == 0 }
                        ?.param
                    val actualParam = if (pendingSourcePattern != null && plmId == 0xB76F && retainedSaveParam == null && param == 0x8000) {
                        runCatching { requireNotNull(asmPlmSourceBridge).availableSaveSlot(currentRoomId, currentArea,
                            pendingSourcePattern!!.stamps.mapNotNull { it.save?.slot }.toSet())?.let { 0x8000 or it }
                            ?: error("No free ordinary save slot in this area (maximum eight).")
                        }.getOrElse { postStatus(it.message ?: "Save allocation failed"); null }
                    } else retainedSaveParam ?: retainedItemParam ?: autoAssignParam(plmId, param)
                    if (actualParam == null) {
                        rollbackStamp()
                        return false
                    }
                    if (!validatePlmPlacement(plmId, tx, ty, actualParam, scope, geometryReference = pendingPlmGeometry)) {
                        rollbackStamp()
                        return false
                    }
                    val bodyIds = gateBodyIds(plmId)
                    val companion = if (bodyIds.isNotEmpty()) {
                        val bodies = _workingPlms.filter { it.x == tx && it.y == ty && it.id in bodyIds }
                        if (bodies.size > 1) { rollbackStamp(); postStatus("Multiple gate bodies; review them first."); return false }
                        bodies.singleOrNull() ?: RomParser.PlmEntry(if (plmId == 0xC836) 0xC82A else 0xC832, tx, ty, 0x8000)
                    } else null
                    if (companion != null && !validatePlmPlacement(companion.id, tx, ty, companion.param, scope, geometryReference = pendingPlmGeometry)) {
                        rollbackStamp()
                        return false
                    }
                    if (pendingSourcePattern != null) {
                        val staged = runCatching {
                            val bridge = requireNotNull(asmPlmSourceBridge)
                            val replaceIds = (effectiveAuthoredStatePlms().flatMap { it.second }.filter { samePlmFamily(it.id, plmId) }
                                .map { it.id } + plmId + bodyIds).toSet()
                            val spawn = if (plmId == 0xB76F) {
                                val slot = actualParam and 255
                                val saved = bridge.saveSpawn(currentRoomId, currentArea, slot)?.takeUnless { it.cleared }
                                val derived = deriveSaveStationSpawn(tx, ty, slot)
                                val values = if (retainedSaveParam != null) saved?.words ?: currentAreaRomSaveEntries[slot]
                                    ?.takeIf { it.roomId == currentRoomId }?.words() else null
                                saved?.takeIf { retainedSaveParam != null } ?: if (values != null) roomSaveResource(currentRoomId, currentArea, slot, values) else derivedSourceSaveResource(derived, currentAreaRomSaveEntries[slot]?.doorBts ?: 0)
                            } else null
                            val entry = RomParser.PlmEntry(plmId, tx, ty, actualParam)
                            pendingSourcePattern!!.stamps += AsmPlmStamp(entry, scope == ItemStateScope.ALL_STATES,
                                listOfNotNull(companion), replaceIds, spawn)
                            _workingPlms.removeAll { it.x == tx && it.y == ty && it.id in replaceIds }
                            _workingPlms.addAll(listOfNotNull(companion))
                            _workingPlms.add(entry)
                        }
                        if (staged.isFailure) {
                            rollbackStamp()
                            postStatus("Pattern blocked: ${staged.exceptionOrNull()?.message}")
                            return false
                        }
                        continue
                    }
                    if (scope == ItemStateScope.ALL_STATES) {
                        if (pendingAllStateItemBefore == null) pendingAllStateItemBefore = plmScopeBefore
                        if (companion != null) {
                            pendingPlmRemoves.addAll(addItemToAllStates(companion.id, tx, ty, companion.param))
                            pendingPlmAdds.add(PlmChange("add", companion.id, tx, ty, companion.param))
                        }
                        val removed = addItemToAllStates(plmId, tx, ty, actualParam)
                        pendingPlmRemoves.addAll(removed)
                        pendingPlmAdds.add(PlmChange("add", plmId, tx, ty, actualParam))
                        if (plmId == 0xB76F && retainedSaveParam == null) ensureAutoSaveStationSpawn(tx, ty, actualParam and 255)
                        continue
                    }
                    val targetPlmChanges = currentPlmChanges()
                    // Remove existing PLMs at same position with same ID only
                    // after the replacement parameter has been secured.
                    for (old in (existing + _workingPlms.filter { it.x == tx && it.y == ty && it.id in bodyIds }).distinct()) {
                        _workingPlms.remove(old)
                        val rc = PlmChange("remove", old.id, old.x, old.y, old.param)
                        targetPlmChanges.add(rc)
                        pendingPlmRemoves.add(rc)
                    }
                    if (companion != null) {
                        _workingPlms.add(companion)
                        val bodyAdd = PlmChange("add", companion.id, tx, ty, companion.param)
                        targetPlmChanges.add(bodyAdd)
                        pendingPlmAdds.add(bodyAdd)
                    }
                    _workingPlms.add(RomParser.PlmEntry(plmId, tx, ty, actualParam))
                    val addChange = PlmChange("add", plmId, tx, ty, actualParam)
                    targetPlmChanges.add(addChange)
                    pendingPlmAdds.add(addChange)
                    if (plmId == 0xB76F && retainedSaveParam == null) {
                        ensureAutoSaveStationSpawn(tx, ty, actualParam and 0xFF)
                    }
                }
            }
        }
        if (changed) editVersion++
        return changed
    }

    private fun paintLayer2At(bx: Int, by: Int): Boolean {
        val b = brush ?: return false
        if (!canEditEmbeddedLayer2()) return false
        var changed = false
        for (r in 0 until b.rows) {
            for (c in 0 until b.cols) {
                val cellKey = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
                if (cellKey in b.skipCells) continue
                val tx = bx + if (b.hFlip) (b.cols - 1 - c) else c
                val ty = by + if (b.vFlip) (b.rows - 1 - r) else r
                if (tx < 0 || ty < 0 || tx >= workingBlocksWide || ty >= workingBlocksTall) continue
                val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
                if (pendingPositions.contains(key)) continue
                val oldWord = readLayer2BlockWord(tx, ty)
                val newWord = b.blockWordAt(r, c) and 0x0FFF
                if (oldWord == newWord) continue
                writeLayer2BlockWord(tx, ty, newWord)
                pendingEdits.add(
                    TileEdit(tx, ty, oldWord, newWord, layer = TILE_EDIT_LAYER_2),
                )
                pendingPositions.add(key)
                changed = true
            }
        }
        if (changed) editVersion++
        return changed
    }

    fun eraseAt(bx: Int, by: Int): Boolean {
        if (!allowOrQueueLayoutEdit {
                beginStroke()
                eraseAt(bx, by)
                endStroke()
            }
        ) return false
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return false
        val key = (bx.toLong() shl 32) or (by.toLong() and 0xFFFFFFFFL)
        if (pendingPositions.contains(key)) return false
        if (activeRoomLayer == RoomEditLayer.LAYER2) {
            if (!canEditEmbeddedLayer2()) return false
            val oldWord = readLayer2BlockWord(bx, by)
            val newWord = 0
            if (oldWord == newWord) return false
            writeLayer2BlockWord(bx, by, newWord)
            pendingEdits.add(TileEdit(bx, by, oldWord, newWord, layer = TILE_EDIT_LAYER_2))
            pendingPositions.add(key)
            editVersion++
            return true
        }
        val oldWord = readBlockWord(bx, by)
        val oldBts = readBts(bx, by)
        val newWord = RomConstants.AIR_TILE_WORD
        val newBts = 0
        if (oldWord == newWord && oldBts == newBts) return false
        writeBlockWord(bx, by, newWord)
        writeBts(bx, by, newBts)
        pendingEdits.add(TileEdit(bx, by, oldWord, newWord, oldBts, newBts))
        pendingPositions.add(key)
        editVersion++
        return true
    }

    /** Erase the selected room rectangle as one undoable edit on the active layer. */
    fun eraseMapSelection(): Boolean {
        if (!allowOrQueueLayoutEdit { eraseMapSelection() }) return false
        val bounds = mapSelectionBounds() ?: return false
        beginStroke()
        var changed = false
        for (by in bounds.minY..bounds.maxY) {
            for (bx in bounds.minX..bounds.maxX) {
                changed = eraseAt(bx, by) || changed
            }
        }
        val layerLabel = if (activeRoomLayer == RoomEditLayer.LAYER2) "Layer 2 " else ""
        endStroke("${layerLabel}Erase selection ${pendingEdits.size} tile(s)")
        return changed
    }

    /** Set block type and BTS for a single tile (used by right-click properties).
     *  Coalesces consecutive changes on the same tile into one undo entry. */
    fun setTileProperties(bx: Int, by: Int, blockType: Int, bts: Int) {
        if (!allowOrQueueLayoutEdit { setTileProperties(bx, by, blockType, bts) }) return
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return
        val oldWord = readBlockWord(bx, by)
        val oldBts = readBts(bx, by)
        val metatile = oldWord and 0x3FF
        val flips = oldWord and 0x0C00
        val newWord = metatile or flips or ((blockType and 0xF) shl 12)
        if (oldWord == newWord && oldBts == bts) return

        writeBlockWord(bx, by, newWord)
        writeBts(bx, by, bts)

        if (hasManagedLayouts()) {
            val applied = pushEditOperation(EditOperation("Properties ($bx,$by)", listOf(TileEdit(bx, by, oldWord, newWord, oldBts, bts))))
            if (applied && blockType != 0) metatileBlockTypePresets = metatileBlockTypePresets + (metatile to blockType)
            return
        }

        val roomOps = currentOperations()
        val lastOp = undoStack.lastOrNull()
        if (lastOp != null && lastOp !in managedPlmUndo && lastOp.edits.size == 1 &&
            lastOp.edits[0].blockX == bx && lastOp.edits[0].blockY == by &&
            lastOp.description.startsWith("Properties")) {
            val origEdit = lastOp.edits[0]
            val merged = EditOperation("Properties ($bx,$by)", listOf(
                TileEdit(bx, by, origEdit.oldBlockWord, newWord, origEdit.oldBts, bts)
            ))
            undoStack[undoStack.lastIndex] = merged
            if (roomOps.isNotEmpty()) roomOps[roomOps.lastIndex] = merged
        } else {
            val op = EditOperation("Properties ($bx,$by)", listOf(
                TileEdit(bx, by, oldWord, newWord, oldBts, bts)
            ))
            undoStack.add(op)
            roomOps.add(op)
        }
        redoStack.clear()
        undoVersion++
        dirty = true
        editVersion++
        // Learn this metatile → block type mapping for future brush presets
        if (blockType != 0x0) {
            val meta = (readBlockWord(bx, by) and 0x3FF)
            metatileBlockTypePresets = metatileBlockTypePresets + (meta to blockType)
        }
    }

    /** Set block type and BTS on every tile in the map selection as one undoable edit. */
    fun setMapSelectionProperties(blockType: Int, bts: Int): Boolean {
        require(blockType in 0..0xF) { "Block type is outside the 4-bit range" }
        require(bts in 0..0xFF) { "BTS is outside the byte range" }
        if (!allowOrQueueLayoutEdit { setMapSelectionProperties(blockType, bts) }) return false
        val bounds = mapSelectionBounds() ?: return false
        val edits = mutableListOf<TileEdit>()
        val editedMetatiles = mutableSetOf<Int>()

        for (by in bounds.minY..bounds.maxY) {
            for (bx in bounds.minX..bounds.maxX) {
                val oldWord = readBlockWord(bx, by)
                val oldBts = readBts(bx, by)
                val newWord = (oldWord and 0x0FFF) or (blockType shl 12)
                if (oldWord == newWord && oldBts == bts) continue
                edits += TileEdit(bx, by, oldWord, newWord, oldBts, bts)
                editedMetatiles += oldWord and 0x3FF
            }
        }
        if (edits.isEmpty()) return false

        edits.forEach { applyTileEdit(it, useNew = true) }
        pushEditOperation(EditOperation("Properties selection ${edits.size} tile(s)", edits))
        if (blockType != 0x0) {
            metatileBlockTypePresets = metatileBlockTypePresets + editedMetatiles.associateWith { blockType }
        }
        return true
    }

    /**
     * Assign one connection index to the contiguous doorway containing the
     * selected tile. Door patterns are normally several adjacent type-9
     * blocks; treating them as one edit avoids a doorway whose blocks point at
     * different DoorDefs.
     */
    fun linkDoorwayTilesToConnection(startX: Int, startY: Int, doorIndex: Int, expectedIdentity: Int? = null, onLinked: (Int) -> Unit = {}): Int {
        val roomId = currentRoomId; val stateId = currentStateId
        if (!allowOrQueueLayoutEdit {
                if (currentRoomId == roomId && currentStateId == stateId) runCatching {
                    linkDoorwayTilesToConnection(startX, startY, doorIndex, expectedIdentity, onLinked)
                }.onFailure { postStatus("Doorway link blocked: ${it.message}") }
                else postStatus("The selected room/state changed; choose the doorway again.")
            }) return 0
        val doorView = if (hasManagedDoors()) asmPlmSourceBridge?.doors?.view(currentRoomId) else null
        if (hasManagedDoors() && (doorView == null || doorView.problem != null)) {
            postStatus("Doorway link blocked: ${doorView?.problem ?: "Wait for the door source."}")
            return 0
        }
        if (expectedIdentity != null && doorView?.resource?.entries?.getOrNull(doorIndex)?.identity != expectedIdentity) {
            postStatus("Door order changed in ASM; choose its current connection before linking tiles.")
            return 0
        }
        require(doorIndex in 0..0xFF) { "Door connection index is outside the BTS byte range" }
        val doorway = connectedDoorwayTiles(startX, startY)
        if (doorway.isEmpty()) return 0
        val sourceBts = readBts(startX, startY)
        val edits = mutableListOf<TileEdit>()
        for ((x, y) in doorway) {
            val word = readBlockWord(x, y)
            val newBts = (sourceBts and 0x80) or doorIndex
            if (sourceBts != newBts) {
                writeBts(x, y, newBts)
                edits += TileEdit(x, y, word, word, sourceBts, newBts)
            }
        }
        if (edits.isEmpty()) { onLinked(sourceBts); return doorway.size }
        val operation = EditOperation(
            "Link doorway to connection ${doorIndex + 1}",
            edits,
        )
        return if (pushEditOperation(operation)) { onLinked(readBts(startX, startY)); edits.size } else 0
    }

    /** Whether the same BTS connection is also used by another disconnected doorway. */
    fun isDoorConnectionSharedOutsideDoorway(startX: Int, startY: Int, doorIndex: Int): Boolean {
        val selectedDoorway = connectedDoorwayTiles(startX, startY).toSet()
        if (selectedDoorway.isEmpty()) return false
        for (y in 0 until workingBlocksTall) for (x in 0 until workingBlocksWide) {
            if (x to y in selectedDoorway) continue
            if (((readBlockWord(x, y) ushr 12) and 0xF) == 0x9 && (readBts(x, y) and 0x7F) == doorIndex) {
                return true
            }
        }
        return false
    }

    /** Infer travel direction from the selected doorway's shape and screen edge. */
    fun suggestedDoorDirection(startX: Int, startY: Int): Int {
        val doorway = connectedDoorwayTiles(startX, startY)
        if (doorway.isEmpty()) return 0
        val minX = doorway.minOf { it.first }
        val maxX = doorway.maxOf { it.first }
        val minY = doorway.minOf { it.second }
        val maxY = doorway.maxOf { it.second }
        return if (maxY - minY >= maxX - minX) {
            // A vertical strip is a left/right opening.
            if (((minX + maxX) / 2) % 16 >= 8) 0 else 1
        } else {
            // A horizontal strip is a top/bottom opening.
            if (((minY + maxY) / 2) % 16 >= 8) 2 else 3
        }
    }

    private fun connectedDoorwayTiles(startX: Int, startY: Int): List<Pair<Int, Int>> {
        if (startX !in 0 until workingBlocksWide || startY !in 0 until workingBlocksTall) return emptyList()
        if (((readBlockWord(startX, startY) ushr 12) and 0xF) != 0x9) return emptyList()
        val sourceBts = readBts(startX, startY)
        val pending = mutableListOf(startX to startY)
        val visited = mutableSetOf<Pair<Int, Int>>()
        val doorway = mutableListOf<Pair<Int, Int>>()
        var cursor = 0
        while (cursor < pending.size) {
            val (x, y) = pending[cursor++]
            if (!visited.add(x to y)) continue
            if (x !in 0 until workingBlocksWide || y !in 0 until workingBlocksTall) continue
            if (((readBlockWord(x, y) ushr 12) and 0xF) != 0x9 || readBts(x, y) != sourceBts) continue
            doorway += x to y
            pending += x - 1 to y
            pending += x + 1 to y
            pending += x to y - 1
            pending += x to y + 1
        }
        return doorway
    }

    // ─── PLM editing ────────────────────────────────────────────

    fun getPlmsAt(x: Int, y: Int): List<RomParser.PlmEntry> =
        _workingPlms.filter { plm ->
            if (plm.x == x && plm.y == y) return@filter true
            if (RomParser.doorCapColor(plm.id) != null) {
                if (RomParser.doorCapIsHorizontal(plm.id)) {
                    if (plm.y == y && x in plm.x..(plm.x + 3)) return@filter true
                } else {
                    if (plm.x == x && y in plm.y..(plm.y + 3)) return@filter true
                }
            }
            false
        }

    /**
     * Resolve the canonical PLM anchor for a door cap selected on or near a
     * type-9 doorway. Super Metroid stores the cap one block inward for a
     * left/right door and two blocks inward for an up/down door, so the tile a
     * user naturally clicks is not necessarily the PLM's stored coordinate.
     *
     * A result is returned only when the nearest doorway is unambiguous. An
     * existing cap remains a fallback for intentionally unusual layouts.
     */
    internal fun suggestedDoorCapPlacement(
        x: Int,
        y: Int,
        requiredDirection: String? = null,
    ): DoorCapPlacement? {
        val existing = getPlmsAt(x, y)
            .firstOrNull { RomParser.isDoorCapPlm(it.id) }
            ?.let { plm ->
                val direction = RomParser.doorCapDefFor(plm.id)?.direction ?: return@let null
                if (requiredDirection != null && direction != requiredDirection) return@let null
                DoorCapPlacement(
                    anchorX = plm.x,
                    anchorY = plm.y,
                    direction = direction,
                    doorwayX = plm.x,
                    doorwayY = plm.y,
                    snappedToDoorway = false,
                )
            }
        if (workingLevelData == null || workingBlocksWide <= 0 || workingBlocksTall <= 0) return existing

        data class Candidate(
            val placement: DoorCapPlacement,
            val doorwayTiles: List<Pair<Int, Int>>,
        )

        val candidates = mutableListOf<Candidate>()
        val visited = mutableSetOf<Pair<Int, Int>>()
        for (blockY in 0 until workingBlocksTall) {
            for (blockX in 0 until workingBlocksWide) {
                if (blockX to blockY in visited) continue
                if (((readBlockWord(blockX, blockY) ushr 12) and 0xF) != 0x9) continue
                val doorway = connectedDoorwayTiles(blockX, blockY)
                if (doorway.isEmpty()) continue
                visited += doorway
                val minX = doorway.minOf { it.first }
                val maxX = doorway.maxOf { it.first }
                val minY = doorway.minOf { it.second }
                val maxY = doorway.maxOf { it.second }
                val vertical = maxY - minY >= maxX - minX

                if (vertical) {
                    for (edgeX in doorway.map { it.first }.distinct()) {
                        val direction = when (edgeX.mod(16)) {
                            0 -> "Right"
                            15 -> "Left"
                            else -> continue
                        }
                        if (requiredDirection != null && direction != requiredDirection) continue
                        val edgeTiles = doorway.filter { it.first == edgeX }
                        val edgeY = edgeTiles.minOf { it.second }
                        val anchorX = if (direction == "Right") edgeX + 1 else edgeX - 1
                        if (anchorX !in 0 until workingBlocksWide) continue
                        candidates += Candidate(
                            DoorCapPlacement(anchorX, edgeY, direction, edgeX, edgeY, true),
                            edgeTiles,
                        )
                    }
                } else {
                    for (edgeY in doorway.map { it.second }.distinct()) {
                        val direction = when (edgeY.mod(16)) {
                            0 -> "Down"
                            15 -> "Up"
                            else -> continue
                        }
                        if (requiredDirection != null && direction != requiredDirection) continue
                        val edgeTiles = doorway.filter { it.second == edgeY }
                        val edgeX = edgeTiles.minOf { it.first }
                        val anchorY = if (direction == "Down") edgeY + 2 else edgeY - 2
                        if (anchorY !in 0 until workingBlocksTall) continue
                        candidates += Candidate(
                            DoorCapPlacement(edgeX, anchorY, direction, edgeX, edgeY, true),
                            edgeTiles,
                        )
                    }
                }
            }
        }

        fun capTiles(placement: DoorCapPlacement): List<Pair<Int, Int>> =
            if (placement.direction == "Left" || placement.direction == "Right") {
                (0 until 4).map { placement.anchorX to placement.anchorY + it }
            } else {
                (0 until 4).map { placement.anchorX + it to placement.anchorY }
            }

        fun distance(candidate: Candidate): Int =
            (candidate.doorwayTiles + capTiles(candidate.placement)).minOf { (tileX, tileY) ->
                kotlin.math.abs(tileX - x) + kotlin.math.abs(tileY - y)
            }

        val scored = candidates.map { it to distance(it) }
        val nearestDistance = scored.minOfOrNull { it.second } ?: return existing
        if (nearestDistance > 2) return existing
        val nearest = scored
            .filter { it.second == nearestDistance }
            .map { it.first.placement }
            .distinctBy { Triple(it.anchorX, it.anchorY, it.direction) }
        return nearest.singleOrNull() ?: existing
    }

    /** Find the cap represented by a click, including a legacy cap shifted a
     * tile or two away from an otherwise unambiguous doorway anchor. */
    internal fun existingDoorCapForPlacement(
        x: Int,
        y: Int,
        placement: DoorCapPlacement?,
        requiredDirection: String? = null,
    ): RomParser.PlmEntry? {
        getPlmsAt(x, y)
            .firstOrNull { plm ->
                RomParser.isDoorCapPlm(plm.id) &&
                    (requiredDirection == null || RomParser.doorCapDefFor(plm.id)?.direction == requiredDirection)
            }
            ?.let { return it }
        if (placement?.snappedToDoorway != true) return null
        val nearby = _workingPlms
            .filter { plm ->
                RomParser.isDoorCapPlm(plm.id) &&
                    RomParser.doorCapDefFor(plm.id)?.direction == placement.direction
            }
            .map { plm ->
                plm to kotlin.math.abs(plm.x - placement.anchorX) + kotlin.math.abs(plm.y - placement.anchorY)
            }
            .filter { it.second <= 2 }
        val nearestDistance = nearby.minOfOrNull { it.second } ?: return null
        return nearby.filter { it.second == nearestDistance }.singleOrNull()?.first
    }

    private fun refreshVanillaSaveIndices(romParser: RomParser) {
        if (vanillaSaveIndicesByArea.isNotEmpty()) return
        val byArea = mutableMapOf<Int, MutableSet<Int>>()
        val repository = RoomRepository()
        for (info in repository.getAllRooms()) {
            val room = romParser.readRoomHeader(info.getRoomIdAsInt()) ?: continue
            val plms = romParser.parsePlmSet(room.plmSetPtr)
            for (plm in plms) {
                if (plm.id == 0xB76F) {
                    byArea.getOrPut(room.area) { mutableSetOf() }.add(plm.param and 0xFF)
                }
            }
        }
        vanillaSaveIndicesByArea = byArea.mapValues { it.value.toSet() }
    }

    private fun saveSpawnOverride(area: Int, saveIndex: Int): SaveStationSpawnChange? {
        project.rooms[project.roomKey(currentRoomId)]?.saveStationSpawns
            ?.lastOrNull { it.area == area && it.saveIndex == saveIndex }
            ?.let { return it }
        return project.rooms.values
            .asSequence()
            .flatMap { it.saveStationSpawns.asSequence() }
            .lastOrNull { it.area == area && it.saveIndex == saveIndex }
    }

    fun effectiveSaveStationSpawn(area: Int, saveIndex: Int, romParser: RomParser): EffectiveSaveStationSpawn? {
        if (hasManagedPlms()) {
            val resource = try { asmPlmSourceBridge?.saveSpawn(currentRoomId, area, saveIndex) }
                catch (error: Exception) { objectPreviewProblem = error.message ?: "Invalid save spawn source"; return null }
            if (resource != null) {
                if (resource.cleared) return null
                val w = resource.words
                return EffectiveSaveStationSpawn(area, saveIndex, w[0], w[1], w[3], w[4], w[5], w[6],
                    romParser.readSaveEntry(area, saveIndex)?.pcOffset, "ASM", resource.sourceDoor)
            }
        }
        val override = saveSpawnOverride(area, saveIndex)
        val romEntry = romParser.readSaveEntry(area, saveIndex)
        if (override != null) {
            return EffectiveSaveStationSpawn(
                area = override.area,
                saveIndex = override.saveIndex,
                roomId = override.roomId,
                doorPtr = override.doorPtr,
                scrollX = override.scrollX,
                scrollY = override.scrollY,
                samusY = override.samusY,
                samusX = override.samusX,
                pcOffset = romEntry?.pcOffset,
                source = if (override.autoDerived) "Auto" else "Override",
            )
        }
        return romEntry?.let {
            EffectiveSaveStationSpawn(
                area = area,
                saveIndex = saveIndex,
                roomId = it.roomId,
                doorPtr = it.doorPtr,
                scrollX = it.scrollX,
                scrollY = it.scrollY,
                samusY = it.samusY,
                samusX = it.samusX,
                pcOffset = it.pcOffset,
                source = "ROM",
            )
        }
    }

    private fun deriveSaveStationSpawn(x: Int, y: Int, saveIndex: Int): SaveStationSpawnChange {
        val scrollX = (x / 16) * 256
        val scrollY = (y / 16) * 256
        val existing = currentAreaRomSaveEntries[saveIndex]?.takeIf { it.roomId == currentRoomId }
        val doorPtr = existing?.doorPtr
            ?: currentIncomingDoorPtrs.firstOrNull()
            ?: doorEntries.firstOrNull { it.doorDefPtr != 0 }?.doorDefPtr
            ?: 0
        return SaveStationSpawnChange(
            area = currentArea,
            saveIndex = saveIndex,
            roomId = currentRoomId,
            doorPtr = doorPtr,
            scrollX = scrollX.toUnsigned16(),
            scrollY = scrollY.toUnsigned16(),
            samusY = (y * 16 - scrollY - 24).toUnsigned16(),
            samusX = (x * 16 - scrollX - 112).toUnsigned16(),
            autoDerived = true,
        )
    }

    private fun upsertSaveStationSpawn(change: SaveStationSpawnChange) {
        if (hasManagedPlms()) {
            editManagedPlms("Edit save spawn · ASM") { bridge, _ ->
                val existing = bridge.saveSpawn(currentRoomId, change.area, change.saveIndex)
                val bts = existing?.words?.get(2)
                    ?: currentRomParser?.readSaveEntry(change.area, change.saveIndex)?.doorBts ?: 0
                bridge.updateSaveSpawn(roomSaveResource(currentRoomId, change.area, change.saveIndex, listOf(change.roomId, if (existing?.sourceDoor != null) 0 else change.doorPtr, bts,
                        change.scrollX, change.scrollY, change.samusY, change.samusX), existing?.sourceDoor))
            }
            return
        }
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.saveStationSpawns.removeAll { it.area == change.area && it.saveIndex == change.saveIndex }
        roomEdits.saveStationSpawns.add(change)
        dirty = true
        editVersion++
    }

    private fun roomSaveResource(roomId: Int, area: Int, slot: Int, words: List<Int>,
                                 sourceDoor: com.supermetroid.editor.data.ProjectSourceDoorReference? = null): SourceSaveResource {
        val projectId = project.newRoomForPreviewId(roomId)?.id
        return SourceSaveResource(com.supermetroid.editor.asm.AsmSaveStationSource.name(roomId, area, slot, projectId),
            roomId, area, slot, words, sourceDoor, projectId)
    }

    /** New save records can use an unbuilt entrance; never fall back to an unrelated outgoing door. */
    private fun derivedSourceSaveResource(change: SaveStationSpawnChange, bts: Int): SourceSaveResource {
        val parser = requireNotNull(currentRomParser)
        val choices = incomingDoorConditionOptions(currentRoomId, parser)
        val entrance = choices.firstOrNull { it.pointer == change.doorPtr } ?: choices.firstOrNull()
            ?: error("A save station needs an incoming connection. Create one before placing its spawn.")
        return roomSaveResource(currentRoomId, change.area, change.saveIndex, listOf(currentRoomId, if (entrance.sourceDoor != null) 0 else requireNotNull(entrance.pointer), bts,
                change.scrollX, change.scrollY, change.samusY, change.samusX), entrance.sourceDoor)
    }

    private fun ensureAutoSaveStationSpawn(x: Int, y: Int, saveIndex: Int) {
        val existing = saveSpawnOverride(currentArea, saveIndex)
        if (existing != null && !existing.autoDerived) return
        upsertSaveStationSpawn(deriveSaveStationSpawn(x, y, saveIndex))
    }

    private fun cleanupSaveStationSpawnIfUnreferenced(saveIndex: Int) {
        if (effectiveAuthoredStatePlms().any { (_, entries) -> entries.any { it.id == 0xB76F && (it.param and 0xFF) == saveIndex } }) return
        val roomEdits = project.rooms[project.roomKey(currentRoomId)] ?: return
        val removed = roomEdits.saveStationSpawns.removeAll {
            it.area == currentArea && it.saveIndex == saveIndex
        }
        if (removed) {
            dirty = true
            editVersion++
        }
        // Removing the final original station must not resurrect its native checkpoint on export.
        val native = currentAreaRomSaveEntries[saveIndex]
        if (native?.roomId == currentRoomId) roomEdits.saveStationSpawns.add(
            SaveStationSpawnChange(currentArea, saveIndex, currentRoomId, native.doorPtr,
                native.scrollX, native.scrollY, native.samusY, native.samusX, clearSlot = true),
        )
    }

    fun resetSaveStationSpawnToAuto(plm: RomParser.PlmEntry) {
        if (plm.id != 0xB76F) return
        upsertSaveStationSpawn(deriveSaveStationSpawn(plm.x, plm.y, plm.param and 0xFF))
    }

    internal fun ensureSaveSpawnSource(plm: RomParser.PlmEntry): Boolean {
        if (!hasManagedPlms() || plm.id != 0xB76F || plm !in _workingPlms) return false
        if (currentRomParser == null) return false
        val slot = plm.param and 255
        if (runCatching { asmPlmSourceBridge?.hasSaveSource(currentRoomId, currentArea, slot) == true }.getOrDefault(false)) return true
        val resource = runCatching { saveSpawnSourceResource(plm) }.getOrElse { postStatus(it.message ?: "Save source unavailable."); return false }
        return editManagedPlms("Adopt save spawn · ASM") { bridge, _ ->
            bridge.updateSaveSpawn(resource)
        }
    }

    private fun saveSpawnSourceResource(plm: RomParser.PlmEntry): SourceSaveResource {
        require(hasManagedPlms() && plm.id == 0xB76F && plm in _workingPlms) { "Select a current save station with editable object source first." }
        val parser = requireNotNull(currentRomParser)
        val slot = plm.param and 255
        val base = requireNotNull(effectiveSaveStationSpawn(currentArea, slot, parser)) { "Save spawn unavailable." }
        val bts = parser.readSaveEntry(currentArea, slot)?.doorBts ?: 0
        return roomSaveResource(currentRoomId, currentArea, slot, listOf(base.roomId, base.doorPtr, bts, base.scrollX, base.scrollY, base.samusY, base.samusX), base.sourceDoor)
    }

    fun updateSaveStationSpawnPosition(area: Int, saveIndex: Int, samusX: Int, samusY: Int, romParser: RomParser) {
        val base = effectiveSaveStationSpawn(area, saveIndex, romParser) ?: return
        upsertSaveStationSpawn(
            SaveStationSpawnChange(
                area = area,
                saveIndex = saveIndex,
                roomId = base.roomId,
                doorPtr = base.doorPtr,
                scrollX = base.scrollX,
                scrollY = base.scrollY,
                samusY = samusY.toUnsigned16(),
                samusX = samusX.toUnsigned16(),
                autoDerived = false,
            ),
        )
    }

    /** One Apply action, one authored-source undo step, even when both position and camera change. */
    fun updateSaveStationSpawnFields(area: Int, saveIndex: Int, samusX: Int, samusY: Int, scrollX: Int, scrollY: Int, romParser: RomParser) {
        if (samusX !in -32768..65535 || samusY !in -32768..65535 || scrollX !in 0..65535 || scrollY !in 0..65535) {
            postStatus("Spawn offsets must fit a signed/unsigned 16-bit word; camera coordinates must be 0–65535. No edit applied.")
            return
        }
        val base = effectiveSaveStationSpawn(area, saveIndex, romParser) ?: return
        upsertSaveStationSpawn(SaveStationSpawnChange(area, saveIndex, base.roomId, base.doorPtr,
            scrollX.toUnsigned16(), scrollY.toUnsigned16(), samusY.toUnsigned16(), samusX.toUnsigned16()))
    }

    internal fun updateSaveStationIncomingDoor(plm: RomParser.PlmEntry, option: IncomingDoorConditionOption): Boolean {
        if (plm !in _workingPlms || plm.id != 0xB76F || !hasManagedPlms()) return false
        val parser = currentRomParser ?: return false
        val choices = incomingDoorConditionOptions(currentRoomId, parser)
        if (choices.none { it.sourceDoor == option.sourceDoor && it.pointer == option.pointer }) {
            postStatus("This entrance changed; choose a current incoming connection.")
            return false
        }
        return editManagedPlms("Change save entrance · ASM") { bridge, _ ->
            val current = bridge.saveSpawn(currentRoomId, currentArea, plm.param and 255) ?: saveSpawnSourceResource(plm)
            bridge.updateSaveSpawn(current.copy(words = current.words.toMutableList().also {
                it[1] = if (option.sourceDoor != null) 0 else requireNotNull(option.pointer)
            }, sourceDoor = option.sourceDoor))
        }
    }

    fun updateSaveStationSpawnScroll(area: Int, saveIndex: Int, scrollX: Int, scrollY: Int, romParser: RomParser) {
        val base = effectiveSaveStationSpawn(area, saveIndex, romParser) ?: return
        upsertSaveStationSpawn(
            SaveStationSpawnChange(
                area = area,
                saveIndex = saveIndex,
                roomId = base.roomId,
                doorPtr = base.doorPtr,
                scrollX = scrollX.toUnsigned16(),
                scrollY = scrollY.toUnsigned16(),
                samusY = base.samusY,
                samusX = base.samusX,
                autoDerived = false,
            ),
        )
    }

    fun activeRoomAreaForEditing(): Int = currentArea

    private fun autoAssignParam(plmId: Int, param: Int): Int? = when {
        param == 0 && isEditorItemPlm(plmId) -> {
            val usedIndices = mutableSetOf<Int>()
            // Replay add/remove history to find NET used params (not ghost entries)
            for ((_, roomEdits) in project.rooms) {
                val changeSets = listOf(roomEdits.plmChanges) + roomEdits.states.map { it.plmChanges }
                for (changes in changeSets) {
                    val netItems = mutableListOf<Triple<Int, Int, Int>>() // (plmId, xy, param)
                    for (change in changes) {
                        val xy = (change.x shl 16) or change.y
                        if (change.action == "add") {
                            netItems.add(Triple(change.plmId, xy, change.param))
                        } else if (change.action == "remove") {
                            netItems.removeAll { it.first == change.plmId && it.second == xy }
                        }
                    }
                    for ((_, _, p) in netItems) {
                        if (p > 0) usedIndices.add(p)
                    }
                }
            }
            // Include vanilla item params from current room
            for (plm in _workingPlms) {
                if (isEditorItemPlm(plm.id) && plm.param > 0) usedIndices.add(plm.param)
            }
            // Authored ASM values are not duplicated in project.rooms. Reserve their collection bits too,
            // including source-edited rooms the user has never selected in this session.
            asmPlmSourceBridge?.sourceOwnedEntries()?.forEach { plm ->
                if (isEditorItemPlm(plm.id) && plm.param > 0) usedIndices.add(plm.param)
            }
            // Vanilla items use 0x00-0x50; search 0x51-0x1FF (431 slots)
            var idx = 0x51
            while (idx in usedIndices && idx <= 0x1FF) idx++
            if (idx > 0x1FF) {
                val message = "Cannot add item: all 431 safe item collection bits (0x51-0x1FF) are in use."
                editorLog("ERROR: $message")
                postStatus(message)
                null
            } else {
                idx
            }
        }
        plmId == 0xB76F && param == 0x8000 -> {
            val usedSaveIndices = mutableSetOf<Int>()
            usedSaveIndices.addAll(vanillaSaveIndicesByArea[currentArea].orEmpty())
            // Not every occupied runtime save slot has a normal save-station PLM.
            // Crateria slot 0 is the gunship/landing-site load record, and Ceres
            // uses all eight addressable indices for its scripted sequence.
            currentAreaRomSaveEntries.forEach { (index, entry) ->
                if (entry.roomId != 0) usedSaveIndices.add(index)
            }
            for (roomEdits in project.rooms.values) {
                for (spawn in roomEdits.saveStationSpawns) {
                    if (spawn.area == currentArea) usedSaveIndices.add(spawn.saveIndex)
                }
            }
            for (plm in _workingPlms) {
                if (plm.id == 0xB76F) usedSaveIndices.add(plm.param and 0xFF)
            }
            for ((_, roomEdits) in project.rooms) {
                val changes = roomEdits.plmChanges + roomEdits.states.flatMap { it.plmChanges }
                for (change in changes) {
                    if (change.action == "add" && change.plmId == 0xB76F) usedSaveIndices.add(change.param and 0xFF)
                }
            }
            val slotCount = when {
                currentAreaSaveEntryCount > 0 -> minOf(currentAreaSaveEntryCount, RomParser.SAVE_STATION_SLOT_COUNT)
                testMode -> RomParser.SAVE_STATION_SLOT_COUNT
                else -> 0
            }
            var idx = 0
            while (idx in usedSaveIndices && idx < slotCount) idx++
            if (idx >= slotCount) {
                val areaName = MinimapData.AREA_NAMES.getOrNull(currentArea) ?: "area $currentArea"
                val message = "Cannot add save station in $areaName: all $slotCount runtime save slots are in use."
                editorLog("ERROR: $message")
                postStatus(message)
                null
            } else {
                0x8000 or idx
            }
        }
        else -> param
    }

    fun addPlm(
        plmId: Int,
        x: Int,
        y: Int,
        param: Int,
        itemScopeOverride: ItemStateScope? = null,
    ) {
        val isItem = isEditorItemPlm(plmId)
        val isDoorCap = RomParser.isDoorCapPlm(plmId)
        val requestedDoorDirection = RomParser.doorCapDefFor(plmId)?.direction
        val doorPlacement = if (isDoorCap) {
            suggestedDoorCapPlacement(x, y, requestedDoorDirection)
        } else {
            null
        }
        // A cap occupies four visible blocks, but its PLM coordinate is the
        // engine-defined anchor beside the type-9 doorway. Prefer that derived
        // anchor; retain an existing cap when a custom layout has no clear door.
        val existingDoorCap = if (isDoorCap) {
            existingDoorCapForPlacement(x, y, doorPlacement, requestedDoorDirection)
        } else {
            null
        }
        val targetX = doorPlacement?.anchorX ?: existingDoorCap?.x ?: x
        val targetY = doorPlacement?.anchorY ?: existingDoorCap?.y ?: y
        val scope = itemScopeOverride ?: existingDoorCap?.let { plmEditScope(it) }
            ?: plmPlacementScope(plmId, targetX, targetY, null)
        if (!hasManagedPlms() && blockLinkedPlmMutation(scope == ItemStateScope.ALL_STATES)) return
        val family = itemFamilyName(plmId)
        val existing = _workingPlms.filter { plm ->
            val replacesSelectedShiftedCap = isDoorCap && plm == existingDoorCap
            val replacesAtResolvedAnchor = plm.x == targetX && plm.y == targetY &&
                (
                    plm.id == plmId ||
                        (family != null && itemFamilyName(plm.id) == family) ||
                        (isDoorCap && RomParser.isDoorCapPlm(plm.id))
                )
            replacesSelectedShiftedCap || replacesAtResolvedAnchor
        }
        val counterparts = runCatching { effectiveAuthoredStatePlms().flatMap { it.second }
            .filter { it.x == targetX && it.y == targetY && samePlmFamily(it.id, plmId) }.distinct() }
            .getOrElse { postStatus("Object edit blocked: ${it.message ?: "Review object source first"}"); return }
        val retained = existing.singleOrNull() ?: counterparts.singleOrNull().takeIf { scope == ItemStateScope.ALL_STATES }
        val retainedSaveParam = retained
            ?.takeIf { plmId == 0xB76F && param == 0x8000 }
            ?.param
        val retainedItemParam = retained
            ?.takeIf { isItem && param == 0 }
            ?.param
        val actualParam = if (hasManagedPlms() && plmId == 0xB76F && retainedSaveParam == null && param == 0x8000) {
            runCatching { asmPlmSourceBridge?.availableSaveSlot(currentRoomId, currentArea)?.let { 0x8000 or it }
                ?: error("No free ordinary save slot in this area (maximum eight).")
            }.getOrElse { postStatus(it.message ?: "Save slot allocation failed"); null } ?: return
        } else retainedSaveParam ?: retainedItemParam ?: autoAssignParam(plmId, param) ?: return
        val proposedCommands = if (plmId == 0xB703 && param != 0) {
            scrollCommandsFor(RomParser.PlmEntry(plmId, targetX, targetY, actualParam))
        } else null
        if (!validatePlmPlacement(plmId, targetX, targetY, actualParam, scope, proposedCommands)) return
        if (plmId == 0xB703 && proposedCommands == null) { postStatus("Scroll commands are unavailable; create or review the command list first."); return }
        val bodyIds = gateBodyIds(plmId)
        val companions = if (bodyIds.isNotEmpty()) {
            val current = _workingPlms.filter { it.x == targetX && it.y == targetY && it.id in bodyIds }
            if (current.size > 1) { postStatus("Multiple gate bodies occupy this tile; review them first."); return }
            val body = current.singleOrNull() ?: RomParser.PlmEntry(if (plmId == 0xC836) 0xC82A else 0xC832, targetX, targetY, 0x8000)
            if (!validatePlmPlacement(body.id, targetX, targetY, body.param, scope)) return
            listOf(body)
        } else emptyList()
        if (!ensureProjectObjectEdit()) return
        if (hasManagedPlms()) {
            editManagedPlms("Add ${RomParser.plmDisplayName(plmId)} · ASM") { bridge, stateId ->
                if (plmId == 0xB76F) {
                    val slot = actualParam and 255
                    val existingSpawn = bridge.saveSpawn(currentRoomId, currentArea, slot)?.takeUnless { it.cleared }
                    val derived = deriveSaveStationSpawn(targetX, targetY, slot)
                    val values = if (retainedSaveParam != null) existingSpawn?.words ?: currentAreaRomSaveEntries[slot]
                        ?.takeIf { it.roomId == currentRoomId }?.words() else null
                    val spawn = existingSpawn?.takeIf { retainedSaveParam != null } ?: if (values != null) roomSaveResource(currentRoomId, currentArea, slot, values) else derivedSourceSaveResource(derived, currentAreaRomSaveEntries[slot]?.doorBts ?: 0)
                    bridge.editSaveStation(currentRoomId, stateId,
                        { it.x == targetX && it.y == targetY && it.id == plmId },
                        RomParser.PlmEntry(plmId, targetX, targetY, actualParam), spawn, scope == ItemStateScope.ALL_STATES)
                    return@editManagedPlms
                }
                bridge.editManaged(currentRoomId, stateId, scope == ItemStateScope.ALL_STATES, { old ->
                    old in existing || (old.x == targetX && old.y == targetY &&
                        (samePlmFamily(old.id, plmId) || old.id in bodyIds))
                }, RomParser.PlmEntry(plmId, targetX, targetY, actualParam), companions)
            }
            return
        }
        val before = itemScopeSnapshot()
        if (scope == ItemStateScope.ALL_STATES) {
            existing.filter { it.x != targetX || it.y != targetY }.forEach(::removeItemFromAllStates)
            val removedChanges = companions.flatMap { addItemToAllStates(it.id, it.x, it.y, it.param) } +
                addItemToAllStates(plmId, targetX, targetY, actualParam)
            val addChange = PlmChange("add", plmId, targetX, targetY, actualParam)
            if (plmId == 0xB76F && retainedSaveParam == null) ensureAutoSaveStationSpawn(targetX, targetY, actualParam and 255)
            val name = customItemNameForPlm(plmId) ?: RomParser.plmDisplayName(plmId)
            val operation = EditOperation(
                "Add $name to all states ($targetX,$targetY)",
                plmAdds = companions.map { PlmChange("add", it.id, it.x, it.y, it.param) } + addChange,
                plmRemoves = removedChanges,
                itemScope = ItemStateScope.ALL_STATES,
            )
            undoStack.add(operation)
            itemScopeSnapshots[operation] = ItemScopeUndo(before, itemScopeSnapshot())
            redoStack.clear()
            undoVersion++
            dirty = true
            editVersion++
            postStatus("$name now appears in all current and future room states.")
            return
        }

        val plmChanges = currentPlmChanges()
        val removedChanges = mutableListOf<PlmChange>()
        for (old in (existing + _workingPlms.filter { it.x == targetX && it.y == targetY && it.id in bodyIds }).distinct()) {
            _workingPlms.remove(old)
            val rc = PlmChange("remove", old.id, old.x, old.y, old.param)
            plmChanges.add(rc)
            removedChanges.add(rc)
        }

        _workingPlms.addAll(companions)
        companions.forEach { plmChanges.add(PlmChange("add", it.id, it.x, it.y, it.param)) }
        _workingPlms.add(RomParser.PlmEntry(plmId, targetX, targetY, actualParam))
        val addChange = PlmChange("add", plmId, targetX, targetY, actualParam)
        plmChanges.add(addChange)
        if (plmId == 0xB76F && retainedSaveParam == null) {
            ensureAutoSaveStationSpawn(targetX, targetY, actualParam and 0xFF)
        }

        val name = customItemNameForPlm(plmId) ?: RomParser.plmDisplayName(plmId)
        val op = EditOperation(
            "Add $name ($targetX,$targetY)",
            plmAdds = companions.map { PlmChange("add", it.id, it.x, it.y, it.param) } + addChange,
            plmRemoves = removedChanges,
            itemScope = ItemStateScope.THIS_STATE,
        )
        undoStack.add(op)
        itemScopeSnapshots[op] = ItemScopeUndo(before, itemScopeSnapshot())
        redoStack.clear()
        undoVersion++
        dirty = true
        editVersion++
        if (doorPlacement?.snappedToDoorway == true) {
            postStatus(
                "$name anchored to nearby ${doorPlacement.direction.lowercase()} doorway at " +
                    "tile (${targetX + 1}, ${targetY + 1}).",
            )
        }
    }

    /** Scalar object dialog for ROM projects; one scope-preserving, reversible operation. */
    internal fun updateRoomObjectFields(roomId: Int, stateId: String?, expected: List<RomParser.PlmEntry>, index: Int, entry: RomParser.PlmEntry) {
        require(project.asmWorkspace.buildMode == ProjectRomBuildMode.PATCHED_ROM && !hasManagedPlms()) { "Use the current object source in ASM mode." }
        require(currentRoomId == roomId && currentPlmStateId() == stateId && _workingPlms == expected && index in expected.indices) {
            "Room objects changed; reopen this object before applying."
        }
        com.supermetroid.editor.asm.AsmPlmDocument.validateEntry(entry)
        val original = expected[index]
        if (entry == original) return
        require(entry.x < workingBlocksWide && entry.y < workingBlocksTall) { "Object coordinates are outside the room." }
        require(expected.count { it.id == original.id && it.x == original.x && it.y == original.y } == 1) { "Duplicate placements need review before field editing." }
        require(listOf(original, entry).none { it.id == 0xB76F || it.id == 0xB703 || RomParser.isGatePlm(it.id) }) {
            "Use the map's station, scroll or gate controls to keep paired data together."
        }
        require(!blockLinkedPlmMutation()) { "Review object source before changing fields." }
        val scope = plmEditScope(original)
        val branches = if (scope == ItemStateScope.ALL_STATES) effectiveAuthoredStatePlms().map { it.second } else listOf(expected)
        require(branches.all { values -> values.count { it == original } == 1 }) { "State variants differ; choose Edit this state on the map first." }
        require(branches.all { values -> values.none { other -> other != original && other.x == entry.x && other.y == entry.y &&
            (other.id == entry.id || (isEditorItemPlm(entry.id) && isEditorItemPlm(other.id))) } }) { "Another object already occupies the destination." }
        require(!isEditorItemPlm(entry.id) || entry.param in 0..0x1FF) { "Item pickup flag IDs must fit 0x000–0x1FF." }
        val before = itemScopeSnapshot()
        val removed = PlmChange("remove", original.id, original.x, original.y, original.param)
        val added = PlmChange("add", entry.id, entry.x, entry.y, entry.param)
        if (scope == ItemStateScope.ALL_STATES) {
            removeItemFromAllStates(original)
            addItemToAllStates(entry.id, entry.x, entry.y, entry.param)
        } else {
            currentPlmChanges().addAll(listOf(removed, added))
            _workingPlms[index] = entry
        }
        val operation = EditOperation("Edit ${RomParser.plmDisplayName(original.id)}", plmAdds = listOf(added), plmRemoves = listOf(removed), itemScope = scope)
        undoStack.add(operation)
        itemScopeSnapshots[operation] = ItemScopeUndo(before, itemScopeSnapshot())
        redoStack.clear(); undoVersion++
        dirty = true; editVersion++
    }

    // ─── Custom scroll command management ─────────────────────

    /** Create a new custom scroll command set, returns the command ID. */
    fun createScrollCommand(entries: List<ScrollCommand>): String {
        val commands = currentScrollCommands()
        val id = "cmd_${commands.size}"
        commands[id] = entries.toMutableList()
        dirty = true
        return id
    }

    /** Update an existing custom scroll command set. */
    fun updateScrollCommand(cmdId: String, entries: List<ScrollCommand>) {
        if (blockLinkedPlmMutation()) return
        currentScrollCommands()[cmdId] = entries.toMutableList()
        dirty = true
        editVersion++
    }

    /** The room inspector and trigger editor use the same source-aware read path. */
    fun scrollCommandsFor(trigger: RomParser.PlmEntry, stateId: String? = currentPlmStateId()): List<ScrollCommand>? = runCatching {
        if (hasManagedPlms()) {
            return@runCatching requireNotNull(asmPlmSourceBridge).scrollCommands(currentRoomId, requireNotNull(stateId), trigger)
        }
        if ((trigger.param and 0xFF00) == 0xCC00) {
            val id = "cmd_${trigger.param and 255}"
            val room = project.rooms[project.roomKey(currentRoomId)]
            val local = stateId?.let { room?.stateEditsForId(it)?.customScrollCommands?.get(id) }
            (local ?: room?.customScrollCommands?.get(id))?.let { return@runCatching it.toList() }
        }
        val parser = requireNotNull(currentRomParser)
        RomParser.decodeScrollCommands(parser, trigger.param, workingBlocksWide / 16).map { (screen, _, value) -> ScrollCommand(screen, value) }
    }.getOrNull()

    fun updateScrollTriggerCommands(trigger: RomParser.PlmEntry, entries: List<ScrollCommand>, expectedSource: String? = null): Boolean {
        if (trigger !in _workingPlms || trigger.id != 0xB703) {
            postStatus("Scroll trigger changed; select it again before editing commands.")
            return false
        }
        val scope = plmEditScope(trigger)
        if (!validatePlmPlacement(trigger.id, trigger.x, trigger.y, trigger.param, scope, entries)) return false
        if (hasManagedPlms()) return editManagedPlms("Edit scroll commands · ASM") { bridge, stateId ->
            require(expectedSource == null || bridge.sourceText() == expectedSource) { "Source changed; reopen the command editor before applying this draft." }
            bridge.editScroll(currentRoomId, stateId, trigger.x, trigger.y, entries, scope == ItemStateScope.ALL_STATES)
        }
        addScrollTriggerWithCommands(trigger.x, trigger.y, entries, scope)
        return true
    }

    /** Get custom scroll commands for a command ID, or null if not found. */
    fun getScrollCommand(cmdId: String): List<ScrollCommand>? {
        val stateCommands = project.rooms[project.roomKey(currentRoomId)]
            ?.let(::selectedStateEdits)
            ?.customScrollCommands
        return stateCommands?.get(cmdId)
            ?: project.rooms[project.roomKey(currentRoomId)]?.customScrollCommands?.get(cmdId)
    }

    /** Add a B703 scroll trigger with a new custom command set. Returns the PLM param (command ID encoded). */
    fun addScrollTriggerWithCommands(x: Int, y: Int, entries: List<ScrollCommand>, scopeOverride: ItemStateScope? = null) {
        val scope = plmPlacementScope(0xB703, x, y, scopeOverride)
        if (!validatePlmPlacement(0xB703, x, y, 0, scope, entries)) return
        if (!ensureProjectObjectEdit()) return
        if (hasManagedPlms()) {
            editManagedPlms("Add scroll trigger · ASM") { bridge, stateId ->
                bridge.editScroll(currentRoomId, stateId, x, y, entries, scope == ItemStateScope.ALL_STATES)
            }
            return
        }
        if (blockLinkedPlmMutation(scope == ItemStateScope.ALL_STATES)) return
        val room = project.getOrCreateRoom(currentRoomId)
        val used = room.customScrollCommands.keys + room.states.flatMap { it.customScrollCommands.keys }
        val cmdIndex = (0..255).firstOrNull { "cmd_$it" !in used }
            ?: run { postStatus("No free custom scroll command identifiers"); return }
        val cmdId = "cmd_$cmdIndex"
        val before = itemScopeSnapshot()
        val commands = if (scope == ItemStateScope.ALL_STATES) room.customScrollCommands else currentScrollCommands()
        commands[cmdId] = entries.toMutableList()
        // Use a custom param range starting from 0x0100 for custom commands
        // Format: 0xCC00 | cmdIndex (to distinguish from ROM pointers)
        val customParam = 0xCC00 or (cmdIndex and 0xFF)
        // The commands and row are one transaction; do not revalidate an unallocated pointer.
        val old = _workingPlms.filter { it.id == 0xB703 && it.x == x && it.y == y }
        val removed = if (scope == ItemStateScope.ALL_STATES) addItemToAllStates(0xB703, x, y, customParam)
            else old.map { PlmChange("remove", it.id, x, y, it.param) }.also { changes ->
                currentPlmChanges().addAll(changes)
                _workingPlms.removeAll(old.toSet())
                _workingPlms.add(RomParser.PlmEntry(0xB703, x, y, customParam))
                currentPlmChanges().add(PlmChange("add", 0xB703, x, y, customParam))
            }
        val operation = EditOperation("Edit scroll trigger", plmAdds = listOf(PlmChange("add", 0xB703, x, y, customParam)), plmRemoves = removed, itemScope = scope)
        undoStack.add(operation)
        itemScopeSnapshots[operation] = ItemScopeUndo(before, itemScopeSnapshot())
        redoStack.clear()
        undoVersion++
        dirty = true
        editVersion++
    }

    fun applyItemToAllStates(plm: RomParser.PlmEntry) {
        if (!isEditorItemPlm(plm.id)) return
        applyPlmToAllStates(plm)
    }

    fun applyPlmToAllStates(plm: RomParser.PlmEntry) {
        if (plm !in _workingPlms) { postStatus("Object changed; select it again."); return }
        if (plm.id == 0xB703) {
            val commands = scrollCommandsFor(plm) ?: run { postStatus("Scroll commands need source review"); return }
            addScrollTriggerWithCommands(plm.x, plm.y, commands, ItemStateScope.ALL_STATES)
            return
        }
        addPlm(plm.id, plm.x, plm.y, plm.param, ItemStateScope.ALL_STATES)
    }

    fun removePlm(
        x: Int,
        y: Int,
        plmId: Int,
        itemScopeOverride: ItemStateScope? = null,
    ) {
        val bodyIds = gatePartnerIds(plmId)
        val removed = _workingPlms.filter { it.x == x && it.y == y && (it.id == plmId || it.id in bodyIds) }
        if (removed.isEmpty()) return
        if (!ensureProjectObjectEdit()) return
        val scope = itemScopeOverride ?: plmEditScope(removed.first { it.id == plmId })
        if (!hasManagedPlms() && blockLinkedPlmMutation(scope == ItemStateScope.ALL_STATES)) return
        if (hasManagedPlms()) {
            val family = itemFamilyName(plmId)
            editManagedPlms("Remove ${RomParser.plmDisplayName(plmId)} · ASM") { bridge, stateId ->
                if (plmId == 0xB76F) {
                    require(removed.size == 1) { "Multiple stations occupy this tile; resolve them in ASM first." }
                    val slot = removed.single().param and 255
                    val save = bridge.saveSpawn(currentRoomId, currentArea, slot) ?: effectiveSaveStationSpawn(currentArea, slot, requireNotNull(currentRomParser))
                        ?.let { base -> roomSaveResource(currentRoomId, currentArea, slot, listOf(base.roomId, base.doorPtr,
                                currentAreaRomSaveEntries[slot]?.doorBts ?: 0, base.scrollX, base.scrollY, base.samusY, base.samusX)) }
                        ?: error("Station has no valid spawn record; review ASM before removal.")
                    bridge.editSaveStation(currentRoomId, stateId, { it in removed }, null, save, scope == ItemStateScope.ALL_STATES)
                    return@editManagedPlms
                }
                bridge.editManaged(currentRoomId, stateId, scope == ItemStateScope.ALL_STATES, { old ->
                    old.x == x && old.y == y && (old.id == plmId || old.id in bodyIds ||
                        (scope == ItemStateScope.ALL_STATES && family != null && itemFamilyName(old.id) == family))
                }, null)
            }
            return
        }
        val before = itemScopeSnapshot()
        if (scope == ItemStateScope.ALL_STATES) {
            val changes = removed.flatMap(::removeItemFromAllStates)
            if (plmId == 0xB76F) cleanupSaveStationSpawnIfUnreferenced(removed.first().param and 255)
            val name = customItemNameForPlm(plmId) ?: RomParser.plmDisplayName(plmId)
            val operation = EditOperation(
                "Remove $name from all states ($x,$y)",
                plmRemoves = changes,
                itemScope = ItemStateScope.ALL_STATES,
            )
            undoStack.add(operation)
            itemScopeSnapshots[operation] = ItemScopeUndo(before, itemScopeSnapshot())
            redoStack.clear()
            undoVersion++
            dirty = true
            editVersion++
            postStatus("$name removed from all current and future room states.")
            return
        }
        _workingPlms.removeAll(removed.toSet())
        val changes = removed.map { PlmChange("remove", it.id, it.x, it.y, it.param) }
        currentPlmChanges().addAll(changes)
        for (old in removed) {
            if (old.id == 0xB76F) cleanupSaveStationSpawnIfUnreferenced(old.param and 0xFF)
        }

        val name = customItemNameForPlm(plmId) ?: RomParser.plmDisplayName(plmId)
        val op = EditOperation(
            "Remove $name ($x,$y)",
            plmRemoves = changes,
            itemScope = ItemStateScope.THIS_STATE,
        )
        undoStack.add(op)
        itemScopeSnapshots[op] = ItemScopeUndo(before, itemScopeSnapshot())
        redoStack.clear()
        undoVersion++
        dirty = true
        editVersion++
    }

    // ─── Enemy editing ────────────────────────────────────────

    fun getEnemiesNear(pixelX: Int, pixelY: Int, radius: Int = 16): List<RomParser.EnemyEntry> =
        _workingEnemies.filter { kotlin.math.abs(it.x - pixelX) < radius && kotlin.math.abs(it.y - pixelY) < radius }

    fun addEnemy(enemyId: Int, pixelX: Int, pixelY: Int, initParam: Int = 0, properties: Int = 0x2800) {
        val entry = RomParser.EnemyEntry(enemyId, pixelX, pixelY, initParam, properties)
        if (usesRoomAsmAuthoring || hasManagedEnemies()) { editSourceEnemies("Add ${RomParser.enemyName(enemyId)}", { it + entry }); return }
        _workingEnemies.add(entry)
        val ec = EnemyChange("add", enemyId, pixelX, pixelY, initParam, properties)
        currentEnemyChanges().add(ec)

        val name = RomParser.enemyName(enemyId)
        val op = EditOperation("Add $name", enemyAdds = listOf(ec))
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
        dirty = true
        editVersion++
    }

    fun removeEnemy(enemy: RomParser.EnemyEntry) {
        if (usesRoomAsmAuthoring || hasManagedEnemies()) {
            val index = _workingEnemies.indexOfFirst { it === enemy }.takeIf { it >= 0 } ?: _workingEnemies.indexOf(enemy)
            if (index < 0) { postStatus("This actor changed; select its current placement again."); return }
            editSourceEnemies("Remove ${RomParser.enemyName(enemy.id)}") { entries ->
                require(index >= 0 && entries.getOrNull(index) == enemy) { "This actor changed; select its current placement again." }
                entries.toMutableList().also { it.removeAt(index) }
            }
            return
        }
        _workingEnemies.removeAll { it.id == enemy.id && it.x == enemy.x && it.y == enemy.y }
        val ec = EnemyChange("remove", enemy.id, enemy.x, enemy.y, enemy.initParam, enemy.properties,
            enemy.extra1, enemy.extra2, enemy.extra3, origX = enemy.x, origY = enemy.y)
        currentEnemyChanges().add(ec)

        val name = RomParser.enemyName(enemy.id)
        val op = EditOperation("Remove $name", enemyRemoves = listOf(ec))
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
        dirty = true
        editVersion++
    }

    fun updateEnemy(old: RomParser.EnemyEntry, new: RomParser.EnemyEntry) {
        if (usesRoomAsmAuthoring || hasManagedEnemies()) {
            val index = _workingEnemies.indexOfFirst { it === old }.takeIf { it >= 0 } ?: _workingEnemies.indexOf(old)
            if (index < 0) { postStatus("This actor changed; select its current placement again."); return }
            editSourceEnemies("Update ${RomParser.enemyName(new.id)}") { entries ->
                require(index >= 0 && entries.getOrNull(index) == old) { "This actor changed; select its current placement again." }
                entries.toMutableList().also { it[index] = new }
            }
            return
        }
        val idx = _workingEnemies.indexOfFirst { it.id == old.id && it.x == old.x && it.y == old.y }
        if (idx < 0) return
        _workingEnemies[idx] = new
        val newEc = EnemyChange("update", new.id, new.x, new.y, new.initParam, new.properties,
            new.extra1, new.extra2, new.extra3, origX = old.x, origY = old.y)
        currentEnemyChanges().add(newEc)

        val oldEc = EnemyChange("update", old.id, old.x, old.y, old.initParam, old.properties,
            old.extra1, old.extra2, old.extra3, origX = old.x, origY = old.y)
        val name = RomParser.enemyName(new.id)
        val op = EditOperation("Update $name", enemyUpdates = listOf(EnemyUpdate(oldEc, newEc)))
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
        dirty = true
        editVersion++
    }

    // ─── Door editing ──────────────────────────────────────────

    private fun ensureProjectObjectEdit(): Boolean =
        !usesRoomAsmAuthoring || project.newRoomForPreviewId(currentRoomId) == null || hasManagedPlms() ||
            ensureCurrentPlmSource(currentRomParser?.readRoomHeader(currentRoomId)?.name ?: "Room")

    internal fun canEditDoorStructure(roomId: Int = currentRoomId): Boolean =
        project.newRoomForPreviewId(roomId) != null || usesRoomAsmAuthoring

    /** Existing layout-scope choices still apply; the connection and doorway publish in one step. */
    internal fun addSourceDoorwayConnection(roomId: Int, opening: DoorwayOpening, x: Int, y: Int, onCreated: (Int) -> Unit) {
        require(currentRoomId == roomId) { "Reopen the source doorway." }
        if (!allowOrQueueLayoutEdit {
                runCatching { addSourceDoorwayConnection(roomId, opening, x, y, onCreated) }
                    .onFailure { postStatus("Door connection blocked: ${it.message}") }
            }) return
        val parser = requireNotNull(currentRomParser)
        val destination = requireNotNull(parser.readRoomHeader(opening.roomId)).let(::applyHeaderChanges)
        val sourceArea = parser.readRoomHeader(roomId)?.let(::applyHeaderChanges)?.area
        val match = parser.findVanillaDoorMatch(opening.roomId, opening.direction, opening.screenX, opening.screenY)
        val flag = mergeDoorBitflagWithMatchedOrientation(opening.direction shl 8, match?.orientation, sourceArea != destination.area)
        val entry = RomParser.DoorEntry(opening.roomId, flag, opening.doorCapCode, opening.screenX, opening.screenY, 0x8000, match?.entryCode ?: 0)
        onCreated(editSourceDoorStructure(entry, null, x to y))
    }

    private fun editSourceDoorStructure(addition: RomParser.DoorEntry?, removeIndex: Int?, doorway: Pair<Int, Int>? = null): Int {
        val parser = requireNotNull(currentRomParser)
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the ASM workspace." }
        val roomId = currentRoomId
        fun idle() {
            require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null && pendingPlmAdds.isEmpty() &&
                pendingPlmRemoves.isEmpty() && pendingLayoutEditScope == null) { "Finish the active stroke, selection or layout-scope choice before changing door structure." }
            require(currentRomParser === parser && asmPlmSourceBridge === bridge) { "The room ROM or ASM link changed; reopen the workspace." }
        }
        idle()
        refreshLinkedPlms()
        val room = project.rooms[project.roomKey(roomId)] ?: RoomEdits(roomId).also { it.ensureStateManifest(parser) }
        val staged = com.supermetroid.editor.asm.snapshotRoomForResize(room).also { it.ensureStateManifest(parser) }
        val selected = requireNotNull(selectedStateEdits(staged)) { "Select a readable room state." }
        val levels = staged.states.associate { it.id to requireNotNull(effectiveLevelDataForState(staged, it, parser)) { "Read every state before changing doors." } }
        require(levels.getValue(selected.id).contentEquals(workingLevelData)) { "The layout preview changed; review it before changing doors." }
        val index = if (addition != null) doorEntries.size else requireNotNull(removeIndex)
        val linkEdits = doorway?.let { (x, y) -> connectedDoorwayTiles(x, y).map { (bx, by) ->
            val word = readBlockWord(bx, by); val bts = readBts(bx, by)
            TileEdit(bx, by, word, word, bts, (bts and 0x80) or index)
        } }.orEmpty()
        if (doorway != null) require(linkEdits.isNotEmpty()) { "Select a doorway tile first." }
        val plan = bridge.doorStructure.plan(room, levels, addition, removeIndex, selected.id, linkEdits)
        val native = requireNotNull(parser.readRoomHeader(roomId))
        val afterLevels = com.supermetroid.editor.asm.AsmRoomLayoutSource(plan.replacements.getValue(PLM_SOURCE_FILE).second, bridge.context.identities())
            .resources(plan.after, (plan.after.effectiveHeaderChange?.width ?: native.width) * 16, (plan.after.effectiveHeaderChange?.height ?: native.height) * 16)
        val recordMapTransaction = prepareSourceMapTransaction?.invoke(parser)
        var history: com.supermetroid.editor.asm.AsmRoomAreaMoveHistory? = null
        recordRoomSourceOperation(roomId, if (addition != null) "Add door connection" else "Remove door connection", fileId = PLM_SOURCE_FILE) { _, undo, redo ->
            history = bridge.doorStructure.apply(plan, ::idle) { restoring ->
                if (currentRoomId == roomId) {
                    val id = currentPlmStateId() ?: selectedStateEdits(staged)?.id
                    workingLevelData = if (restoring) levels[id]?.copyOf() else afterLevels[id]?.bytes()
                    doorEntries = effectiveDoorsForRoom(roomId, parser)
                }
                if (restoring) undo() else redo()
                dirty = true; editVersion++
            }
        }
        refreshLinkedPlms()
        recordMapTransaction?.invoke(requireNotNull(history))
        postStatus(if (addition != null) "Added connection ${index + 1}. Door data and layout are editable in ASM and the editor." else "Removed connection ${index + 1}; doorway indices were updated in all states. Surviving DoorDefs retain their identities.")
        return index
    }

    fun updateDoor(index: Int, entry: RomParser.DoorEntry, expectedIdentity: Int? = null) {
        if (index < 0 || index >= _workingDoors.size) return
        if (_workingDoors[index] == entry) return
        if (asmNavigationEnabled && sharedDoorRooms(entry.doorDefPtr).size > 1 && !canEditSharedDoor(entry.doorDefPtr)) {
            postStatus("This native DoorDef is shared. Choose Edit shared record after reviewing the affected rooms.")
            return
        }
        if (hasManagedDoors() || project.asmWorkspace.enabled && usesRoomAsmAuthoring) {
            val source = asmPlmSourceBridge
            if (source == null) { postStatus("Door edit blocked: wait for the ASM workspace."); return }
            val current = if (hasManagedDoors()) source.doors.view(currentRoomId).resource?.entries?.getOrNull(index) else null
            if (hasManagedDoors() && (current?.entry() != _workingDoors[index] || current?.identity != (expectedIdentity ?: doorSourceIdentities[index]))) {
                refreshDoorSource()
                postStatus("Door source changed; review the refreshed connection before applying this edit.")
                return
            }
            if (runCatching { source.doors.validateEdit(currentRoomId, index, entry) }
                    .onFailure { postStatus("Door edit blocked: ${it.message}") }.isFailure) return
            if (!ensureDoorSource(currentRomParser?.readRoomHeader(currentRoomId)?.name ?: "Room")) return
            val bridge = requireNotNull(asmPlmSourceBridge)
            runCatching {
                val before = bridge.doors.sourceText()
                bridge.doors.edit(currentRoomId, index, entry)
                if (before != bridge.doors.sourceText()) recordManagedRoomOperation(EditOperation("Edit door ${index + 1}"), before, bridge, FX_SOURCE_FILE)
                refreshDoorSource()
                refreshSelectors() // A retargeted DoorDef may invalidate an incoming-door condition.
                dirty = true; editVersion++
            }.onFailure { postStatus("Door edit blocked: ${it.message}") }
            return
        }
        _workingDoors[index] = entry
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        val dc = DoorChange(
            doorIndex = index,
            destRoomPtr = entry.destRoomPtr,
            bitflag = entry.bitflag,
            doorCapCode = entry.doorCapCode,
            screenX = entry.screenX,
            screenY = entry.screenY,
            distFromDoor = entry.distFromDoor,
            entryCode = entry.entryCode
        )
        roomEdits.doorChanges.removeAll { it.doorIndex == index }
        roomEdits.doorChanges.add(dc)
        dirty = true
        editVersion++
    }

    fun addProjectRoomDoor(
        roomId: Int,
        destinationRoomId: Int,
        direction: Int,
        screenX: Int,
        screenY: Int,
        romParser: RomParser,
        doorCapCode: Int? = null,
    ): Int {
        val projectRoom = project.newRoomForPreviewId(roomId)
        if (projectRoom == null || usesRoomAsmAuthoring) {
            require(roomId == currentRoomId && canEditDoorStructure(roomId)) { "Load an existing room in ASM mode before adding a connection." }
            val destination = requireNotNull(romParser.readRoomHeader(destinationRoomId)).let(::applyHeaderChanges)
            require(direction in 0..3 && screenX in 0 until destination.width && screenY in 0 until destination.height) { "Invalid door direction or destination screen." }
            val sourceArea = requireNotNull(romParser.readRoomHeader(roomId)).let(::applyHeaderChanges).area
            val match = romParser.findVanillaDoorMatch(destinationRoomId, direction, screenX, screenY)
            val flags = mergeDoorBitflagWithMatchedOrientation(direction shl 8, match?.orientation, sourceArea != destination.area)
            val cap = doorCapCode ?: deriveEffectiveDoorCapPosition(romParser, destinationRoomId, direction, screenX, screenY)
                ?: romParser.deriveDoorCapPosition(destinationRoomId, direction, screenX, screenY) ?: match?.doorCapCode ?: 0
            return editSourceDoorStructure(RomParser.DoorEntry(destinationRoomId, flags, cap, screenX, screenY, 0x8000, match?.entryCode ?: 0), null)
        }
        require((project.rooms[project.roomKey(roomId)]?.asmLayoutSourceVersion ?: 0) == 0) {
            "Project-room door-list changes with authored layouts need joint layout/source support. No changes were applied."
        }
        require(projectRoom.doors.size < 16) { "SMEDIT currently supports at most 16 door connections per room" }
        val destination = romParser.readRoomHeader(destinationRoomId)?.let(::applyHeaderChanges)
            ?: error("Destination room could not be read")
        require(direction in 0..3) { "Door direction is invalid" }
        require(screenX in 0 until destination.width && screenY in 0 until destination.height) {
            "Destination entrance is outside the room"
        }
        val sourceArea = projectRoom.header.area
        val crossArea = sourceArea != destination.area
        val match = romParser.findVanillaDoorMatch(destinationRoomId, direction, screenX, screenY)
        val bitflag = (direction shl 8) or if (crossArea) 0x40 else 0
        val cap = doorCapCode
            ?: deriveEffectiveDoorCapPosition(
                romParser, destinationRoomId, direction, screenX, screenY,
            )
            ?: romParser.deriveDoorCapPosition(destinationRoomId, direction, screenX, screenY)
            ?: match?.doorCapCode
            ?: 0
        val destinationRef = project.newRooms.firstOrNull { it.previewRoomId == destinationRoomId }
            ?.let { project.newRoomDestination(it.id) }
            ?: project.romRoomDestination(destinationRoomId)
        projectRoom.doors += ProjectDoorDefinition(
            destination = destinationRef,
            bitflag = mergeDoorBitflagWithMatchedOrientation(bitflag, match?.orientation, crossArea),
            doorCapCode = cap,
            screenX = screenX,
            screenY = screenY,
            distFromDoor = 0x8000,
            entryCode = match?.entryCode ?: 0,
        )
        _workingDoors += RomParser.DoorEntry(
            destRoomPtr = destinationRoomId,
            bitflag = projectRoom.doors.last().bitflag,
            doorCapCode = cap,
            screenX = screenX,
            screenY = screenY,
            distFromDoor = 0x8000,
            entryCode = match?.entryCode ?: 0,
        )
        dirty = true
        editVersion++
        return projectRoom.doors.lastIndex
    }

    /** Undo only the most recent add when workspace materialization fails. */
    internal fun rollbackAddedProjectRoomDoor(roomId: Int, doorIndex: Int) {
        val projectRoom = project.newRoomForPreviewId(roomId)
            ?: return
        require(doorIndex == projectRoom.doors.lastIndex) {
            "Only the most recently added room connection can be rolled back"
        }
        projectRoom.doors.removeAt(doorIndex)
        if (doorIndex in projectRoom.previewDoorDefPtrs.indices) {
            projectRoom.previewDoorDefPtrs.removeAt(doorIndex)
        }
        if (doorIndex in _workingDoors.indices) {
            _workingDoors.removeAt(doorIndex)
        }
        project.rooms[project.roomKey(roomId)]?.doorChanges?.removeAll { it.doorIndex == doorIndex }
        dirty = true
        editVersion++
    }

    /**
     * Remove a project-room connection. Door tiles referencing the removed
     * index must be erased first; higher BTS indices are shifted with the list.
     */
    fun removeProjectRoomDoor(roomId: Int, doorIndex: Int, expectedIdentity: Int? = null) {
        val projectRoom = project.newRoomForPreviewId(roomId)
        if (projectRoom == null || usesRoomAsmAuthoring) {
            require(roomId == currentRoomId && canEditDoorStructure(roomId)) { "Load an existing room in ASM mode before removing a connection." }
            if (expectedIdentity != null) require(asmPlmSourceBridge?.doors?.view(roomId)?.resource?.entries?.getOrNull(doorIndex)?.identity == expectedIdentity) {
                "Door order changed in ASM; review the current list before removing a connection."
            }
            editSourceDoorStructure(null, doorIndex)
            return
        }
        require((project.rooms[project.roomKey(roomId)]?.asmLayoutSourceVersion ?: 0) == 0) {
            "Project-room door-list changes with authored layouts need joint layout/source support. No changes were applied."
        }
        require(doorIndex in projectRoom.doors.indices) { "Door ${doorIndex + 1} does not exist" }
        require(currentRoomId == roomId && workingLevelData != null) { "Load the room before removing a door" }
        val removedDoorPtr = projectRoom.previewDoorDefPtrs.getOrNull(doorIndex)
        if (removedDoorPtr != null) {
            fun ProjectRoomStateCondition.referencesDoor(pointer: Int): Boolean =
                (argumentKind == com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.DOOR_POINTER &&
                    argument == pointer) || children.any { it.referencesDoor(pointer) }

            val conditionReferences = project.rooms.values.sumOf { edits ->
                edits.states.count { it.effectiveCondition.referencesDoor(removedDoorPtr) }
            }
            val fxReferences = project.rooms.values.sumOf { edits ->
                edits.states.count { state ->
                    state.doorFxChanges.keys.any { it.toIntOrNull(16) == removedDoorPtr }
                }
            }
            val saveReferences = project.rooms.values.sumOf { edits ->
                edits.saveStationSpawns.count { it.doorPtr == removedDoorPtr && !it.clearSlot }
            }
            require(conditionReferences + fxReferences + saveReferences == 0) {
                buildString {
                    append("Door ${doorIndex + 1} is still referenced by ")
                    append(
                        listOfNotNull(
                            conditionReferences.takeIf { it > 0 }?.let { "$it room-state condition(s)" },
                            fxReferences.takeIf { it > 0 }?.let { "$it door-specific FX override(s)" },
                            saveReferences.takeIf { it > 0 }?.let { "$it save-station spawn(s)" },
                        ).joinToString(),
                    )
                    append("; remove or retarget those references first")
                }
            }
        }
        val exactReferences = buildList {
            for (y in 0 until workingBlocksTall) for (x in 0 until workingBlocksWide) {
                if (((readBlockWord(x, y) ushr 12) and 0xF) == 0x9 && (readBts(x, y) and 0x7F) == doorIndex) add(x to y)
            }
        }
        require(exactReferences.isEmpty()) {
            "Door ${doorIndex + 1} is still used by ${exactReferences.size} doorway tile(s); erase or relink them first"
        }
        for (y in 0 until workingBlocksTall) for (x in 0 until workingBlocksWide) {
            if (((readBlockWord(x, y) ushr 12) and 0xF) == 0x9) {
                val bts = readBts(x, y)
                if ((bts and 0x7F) > doorIndex) setTileProperties(x, y, 0x9, (bts and 0x80) or ((bts and 0x7F) - 1))
            }
        }
        projectRoom.doors.removeAt(doorIndex)
        if (doorIndex in projectRoom.previewDoorDefPtrs.indices) {
            // Keep the disposable pointer adapter parallel with the semantic
            // door list so the next workspace rebuild remaps surviving DDBs
            // by identity rather than by their former list position.
            projectRoom.previewDoorDefPtrs.removeAt(doorIndex)
        }
        val edits = project.rooms[project.roomKey(roomId)]
        if (edits != null) {
            val remapped = edits.doorChanges.mapNotNull { change ->
                when {
                    change.doorIndex == doorIndex -> null
                    change.doorIndex > doorIndex -> change.copy(doorIndex = change.doorIndex - 1)
                    else -> change
                }
            }
            edits.doorChanges.clear()
            edits.doorChanges.addAll(remapped)
        }
        if (doorIndex in _workingDoors.indices) _workingDoors.removeAt(doorIndex)
        dirty = true
        editVersion++
    }

    // ─── Scroll editing ─────────────────────────────────────────

    fun setScroll(screenX: Int, screenY: Int, newValue: Int, roomWidth: Int) {
        if (newValue !in 0..2 || roomWidth != workingBlocksWide / 16 || screenX !in 0 until roomWidth ||
            screenY !in 0 until workingBlocksTall / 16) return
        val idx = screenY * roomWidth + screenX
        if (idx !in _workingScrolls.indices) return
        val oldValue = _workingScrolls[idx]
        if (oldValue == newValue) return
        if (project.asmWorkspace.buildMode == com.supermetroid.editor.data.ProjectRomBuildMode.ASM_SOURCE) {
            if (!ensureInitialScrollSource(currentRomParser?.readRoomHeader(currentRoomId)?.name ?: "Room ${currentRoomId.toString(16).uppercase()}")) return
            val bridge = requireNotNull(asmPlmSourceBridge)
            val stateId = currentPlmStateId() ?: return
            runCatching {
                val before = bridge.sourceText()
                bridge.initialScrolls.edit(currentRoomId, stateId, idx, newValue)
                val after = bridge.sourceText()
                if (before != after) {
                    val op = EditOperation("Initial Screen Scroll ${oneBasedRoomCoordinate(screenX, screenY)} → ${listOf("Red", "Blue", "Green")[newValue]}")
                    managedPlmUndo[op] = ManagedSourceUndo(PLM_SOURCE_FILE, before, after, bridge.sourceUndoHead())
                    undoStack.add(op)
                    redoStack.clear()
                    undoVersion++
                    refreshInitialScrolls()
                    editVersion++
                }
            }.onFailure { postStatus("Initial scroll edit blocked: ${it.message}") }
            return
        }
        _workingScrolls[idx] = newValue
        val scrollChanges = currentScrollChanges()
        scrollChanges.removeAll { it.screenX == screenX && it.screenY == screenY }
        if (newValue != _originalScrolls[idx]) {
            scrollChanges.add(ScrollChange(screenX, screenY, _originalScrolls[idx], newValue))
        }

        val scrollName = when (newValue) { 0 -> "Red"; 1 -> "Blue"; 2 -> "Green"; else -> "0x${newValue.toString(16)}" }
        val sc = ScrollChange(screenX, screenY, oldValue, newValue)
        val op = EditOperation(
            "Screen Scroll ${oneBasedRoomCoordinate(screenX, screenY)} → $scrollName",
            scrollEdits = listOf(sc),
        )
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
        scrollVersion++
        dirty = true
    }

    // ─── Room resize ─────────────────────────────────────────────

    /**
     * Resize the current room from (oldW x oldH) to (newW x newH) screens.
     * Copies existing tile data, BTS, and L2 into the new dimensions.
     * New columns/rows are filled with empty tiles (air). Truncated areas are discarded.
     */
    fun resizeRoom(
        oldWidth: Int, oldHeight: Int,
        newWidth: Int, newHeight: Int,
    ): Boolean {
        val data = workingLevelData ?: return false
        if (newWidth == oldWidth && newHeight == oldHeight) return false
        if (newWidth !in 1..15 || newHeight !in 1..15) return false
        if (project.rooms.values.any { it.asmDoorSourceVersion != 0 && asmPlmSourceBridge?.doors?.view(it.roomId)?.let { view -> view.problem != null } != false }) {
            postStatus("Resize blocked: repair or load the authored door sources first so incoming connections can be checked.")
            return false
        }
        if (usesRoomAsmAuthoring || hasManagedPlms() || hasManagedInitialScrolls() || hasManagedLayouts() || hasManagedRoomProperties() ||
            project.rooms.values.any { it.asmDoorSourceVersion != 0 }) {
            return resizeSourceRoom(oldWidth, oldHeight, newWidth, newHeight)
        }

        val newData = resizeLevelData(data, oldWidth, oldHeight, newWidth, newHeight)

        // Update working state
        workingLevelData = newData
        originalLevelData = newData.copyOf()
        workingBlocksWide = newWidth * 16
        workingBlocksTall = newHeight * 16

        // Resize scroll data: one byte per screen, default Blue (1)
        val newScrolls = resizeScrollGrid(_workingScrolls, oldWidth, oldHeight, newWidth, newHeight)
        _originalScrolls = newScrolls.copyOf()
        _workingScrolls = newScrolls
        scrollVersion++

        // Update room header with new dimensions
        val change = (project.getOrCreateRoom(currentRoomId).effectiveHeaderChange ?: RoomHeaderChange())
            .copy(width = newWidth, height = newHeight)
        setRoomHeaderChange(change)

        // Clear tile edit history (no longer valid for new dimensions)
        currentOperations().clear()
        currentScrollChanges().clear()
        undoStack.clear()
        managedPlmUndo.clear()
        redoStack.clear()
        undoVersion++

        // Force re-render
        editVersion++
        dirty = true
        return true
    }

    private fun resizeIncomingDoors(roomId: Int, parser: RomParser): List<Pair<Int, DoorChange>> = buildList {
        val sourceRooms = parser.roomCatalog.rooms.map { it.getRoomIdAsInt() } + project.rooms.values.map { it.roomId }
        for (sourceId in sourceRooms.distinct()) {
            val header = parser.readRoomHeader(sourceId) ?: continue
            val edits = project.rooms[project.roomKey(sourceId)]
            if (edits?.asmDoorSourceVersion != 0 && edits != null) {
                val view = requireNotNull(asmPlmSourceBridge?.doors?.view(sourceId)) { "Wait for authored door sources." }
                require(view.problem == null) { view.problem ?: "Repair the door source." }
                requireNotNull(view.resource).entries.filter { it.change.destRoomPtr == roomId }.forEach { add(sourceId to it.change) }
                continue
            }
            val pointer = edits?.effectiveHeaderChange?.doorOut ?: header.doorOut
            val doors = if (pointer in 0x8000..0xFFFE) parser.parseDoorList(pointer) else emptyList()
            val changes = edits?.effectiveDoorChanges.orEmpty().associateBy { it.doorIndex }
            for (index in (doors.indices.toList() + changes.keys).distinct().sorted()) {
                val door = changes[index] ?: doors.getOrNull(index)?.let {
                    DoorChange(index, it.destRoomPtr, it.bitflag, it.doorCapCode, it.screenX, it.screenY, it.distFromDoor, it.entryCode)
                } ?: continue
                if (door.destRoomPtr == roomId) add(sourceId to door)
            }
        }
    }

    /** Dimensions belong to the room, so every layout and camera resource participates in one transaction. */
    private fun resizeSourceRoom(oldWidth: Int, oldHeight: Int, width: Int, height: Int): Boolean = runCatching {
        require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null) { "Finish the current tile stroke or floating selection before resizing." }
        require(workingBlocksWide == oldWidth * 16 && workingBlocksTall == oldHeight * 16) { "The room dimensions changed; reopen Resize." }
        val parser = requireNotNull(currentRomParser)
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        val room = project.rooms[project.roomKey(currentRoomId)] ?: RoomEdits(currentRoomId).also { it.ensureStateManifest(parser) }
        val selected = requireNotNull(currentPlmStateId() ?: selectedStateEdits(room)?.id)
        val native = requireNotNull(parser.readRoomHeader(room.roomId))
        require((room.effectiveHeaderChange?.width ?: native.width) == oldWidth && (room.effectiveHeaderChange?.height ?: native.height) == oldHeight) {
            "The room dimensions changed; reopen Resize."
        }
        val lists = effectiveAuthoredStatePlms()
        require(lists.size == room.states.size) { "Object state coverage is incomplete." }
        val objectsBefore = room.states.mapIndexed { index, state -> state.id to lists[index].second }.toMap()
        fun newlyCropped(x: Int, y: Int, scale: Int): Boolean =
            x in 0 until oldWidth * scale && y in 0 until oldHeight * scale && (x >= width * scale || y >= height * scale)
        val runtime = parser.parseRoomStatesWithData(room.roomId)
        for (state in room.states) {
            require(objectsBefore.getValue(state.id).none { newlyCropped(it.x, it.y, 16) }) {
                "Objects in state ${state.id} would be outside the resized room. Move or remove them before shrinking."
            }
            val actors = if (hasManagedEnemies()) {
                val view = bridge.enemies.view(room.roomId, state.id)
                require(view.problem == null) { view.problem ?: "Invalid actor source." }
                requireNotNull(view.resource).population.entries
            } else {
                val base = requireNotNull(runtime.getOrNull(state.baseSourceStateIndex() ?: -1))
                com.supermetroid.editor.rom.applyEnemyChanges(parser.parseEnemyPopulation(base.enemySetPtr), room.enemyChanges + state.enemyChanges)
            }
            require(actors.none { newlyCropped(it.x, it.y, 256) }) {
                "Actors in state ${state.id} would be outside the resized room. Move or remove them before shrinking."
            }
        }
        val beforeLevels = room.states.associate { it.id to requireNotNull(effectiveLevelDataForState(room, it, parser)) }
        require(beforeLevels.getValue(selected).contentEquals(workingLevelData) && objectsBefore.getValue(selected) == _workingPlms) {
            "The room preview changed; wait for the source preview, then resize again."
        }
        val plan = bridge.planResize(room.roomId, native.name, width, height, ::isEditorItemPlm, roomSnapshot = room)
        require(plan.gridsBefore.getValue(selected) == _workingScrolls.toList()) { "The scroll grid changed; wait for the source preview, then resize again." }
        val saves = com.supermetroid.editor.asm.AsmRoomObjectSource(plan.after, bridge.catalog.symbols, bridge.context.identities()).saveResources(room.roomId)
        for (save in saves.filterNot { it.cleared }) require(!newlyCropped(save.words[3], save.words[4], 256)) {
            "A save spawn's camera would be outside the resized room. Update its spawn before shrinking."
        }
        val area = room.effectiveHeaderChange?.area ?: native.area
        for (slot in objectsBefore.values.flatten().filter { it.id == 0xB76F }.map { it.param and 255 }.distinct()) {
            if (saves.any { it.slot == slot }) continue
            parser.readSaveEntry(area, slot)?.let { save ->
                require(!newlyCropped(save.scrollX, save.scrollY, 256)) {
                    "A save spawn's camera would be outside the resized room. Update its spawn before shrinking."
                }
            }
        }
        val sharedTargets = room.states.associate { state -> state.id to resizeLevelData(
            requireNotNull(effectiveLevelDataForState(room, state, parser, includeStateOperations = false)), oldWidth, oldHeight, width, height) }
        val targets = beforeLevels.mapValues { resizeLevelData(it.value, oldWidth, oldHeight, width, height) }
        val after = plan.afterModel
        stripTileEdits(after.operations)
        after.states.forEach { stripTileEdits(it.operations) }
        after.levelResourceOperations.clear()
        val operation = EditOperation("Resize room ${oldWidth}×${oldHeight} → ${width}×${height}")
        for ((resource, states) in after.states.groupBy { it.resources.level }.filter { after.asmLayoutSourceVersion == 0 }) {
            val state = states.first()
            val baseline = requireNotNull(effectiveLevelDataForState(after, state, parser, includeSharedOperations = false, includeStateOperations = false))
            val shared = sharedTargets.getValue(state.id)
            val edits = buildLevelDataDiff(baseline, shared, width * 16, height * 16)
            if (edits.isNotEmpty()) after.levelResourceOperations[resource] = mutableListOf(operation.copy(edits = edits))
            for (member in states) {
                val privateEdits = buildLevelDataDiff(shared, targets.getValue(member.id), width * 16, height * 16)
                if (privateEdits.isNotEmpty()) member.operations.add(operation.copy(edits = privateEdits))
            }
        }
        val sourceAfterLevels = if (after.asmLayoutSourceVersion == 0) emptyMap() else
            com.supermetroid.editor.asm.AsmRoomLayoutSource(plan.after, bridge.context.identities()).resources(after, width * 16, height * 16).mapValues { it.value.bytes() }
        val afterLevels = after.states.associate { state ->
            val data = sourceAfterLevels[state.id] ?: requireNotNull(effectiveLevelDataForState(after, state, parser))
            require(buildLevelDataDiff(data, targets.getValue(state.id), width * 16, height * 16).isEmpty()) { "A shared layout could not be resized without losing state differences." }
            state.id to data
        }
        val beforeBaselines = plan.beforeModel.states.associate { state -> state.id to requireNotNull(effectiveLevelDataForState(
            plan.beforeModel.copy(operations = mutableListOf()), state, parser, includeSharedOperations = false, includeStateOperations = false)) }
        val afterBaselines = if (after.asmLayoutSourceVersion != 0) afterLevels else after.states.associate { state -> state.id to requireNotNull(effectiveLevelDataForState(
            after.copy(operations = mutableListOf()), state, parser, includeSharedOperations = false, includeStateOperations = false)) }
        fun nativeGrids(w: Int, h: Int) = room.states.associate { state ->
            val base = requireNotNull(runtime.getOrNull(state.baseSourceStateIndex() ?: -1))
            state.id to resizeScrollGrid(parser.parseScrollData(base.scrollPtr, native.width, native.height), native.width, native.height, w, h)
        }
        val beforeNativeGrids = nativeGrids(oldWidth, oldHeight)
        val afterNativeGrids = nativeGrids(width, height)
        val recordMapTransaction = prepareSourceMapTransaction?.invoke(parser)
        val history = bridge.applyResize(plan, beforeTransition = {
            require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null &&
                pendingPlmAdds.isEmpty() && pendingPlmRemoves.isEmpty() && pendingLayoutEditScope == null) { "Finish the active tile edit before restoring resize." }
            require(asmPlmSourceBridge === bridge && currentRomParser === parser) { "The room ROM or ASM link changed; reopen the current workspace." }
        }, onTransition = { undo ->
            if (currentRoomId == room.roomId) {
                val id = currentPlmStateId() ?: selectedStateEdits(room)?.id
                val levels = if (undo) beforeLevels else afterLevels
                if (id != null) levels[id]?.let { data ->
                    workingLevelData = data.copyOf()
                    originalLevelData = (if (undo) beforeBaselines else afterBaselines).getValue(id).copyOf()
                    workingBlocksWide = (if (undo) oldWidth else width) * 16
                    workingBlocksTall = (if (undo) oldHeight else height) * 16
                    _workingScrolls = (if (undo) plan.gridsBefore else plan.gridsAfter).getValue(id).toIntArray()
                    _originalScrolls = (if (undo) beforeNativeGrids else afterNativeGrids).getValue(id).copyOf()
                    _workingPlms.clear(); _workingPlms.addAll((if (undo) objectsBefore else plan.objectsAfter).getValue(id))
                    mapSelStart = null; mapSelEnd = null
                    scrollVersion++
                }
                val from = if (undo) undoStack else redoStack
                val to = if (undo) redoStack else undoStack
                val index = from.indexOfFirst { it === operation }
                if (index >= 0) { from.removeAt(index); to.add(operation) }
            }
            dirty = true; undoVersion++; editVersion++
        })
        recordManagedRoomOperation(operation, plan.before, bridge)
        refreshLinkedPlms()
        recordMapTransaction?.invoke(history)
        postStatus("Resized all ${room.states.size} state(s) to ${width}×${height} screens.")
        true
    }.getOrElse { postStatus("Resize blocked: ${it.message ?: "No changes applied."}"); false }

    // ─── FX editing ─────────────────────────────────────────────

    fun setFxChange(change: FxChange) {
        if (usesRoomAsmAuthoring || hasManagedFx()) {
            currentStateEdits()?.id?.let { editSourceFx(it, 0, change, allStates = true) }
            return
        }
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.fxChange = change
        dirty = true
        editVersion++
    }

    fun setRoomStateFxChange(stateId: String, change: FxChange?, romParser: RomParser) {
        if (usesRoomAsmAuthoring || hasManagedFx()) { editSourceFx(stateId, 0, change); return }
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.ensureStateManifest(romParser)
        val stateEdits = roomEdits.stateEditsForId(stateId)
            ?: error("Room state '$stateId' is not available for room 0x${currentRoomId.toString(16)}")
        stateEdits.fxChange = change
        dirty = true
        editVersion++
    }

    fun setRoomStateDoorFxChange(
        stateId: String,
        doorSelect: Int,
        change: FxChange?,
        romParser: RomParser,
        sourceDoor: ProjectSourceDoorReference? = null,
    ) {
        require((sourceDoor != null && doorSelect == 0) || (sourceDoor == null && doorSelect in 0x8000..0xFFF4)) { "Door-specific FX requires an incoming DoorDef reference." }
        if (usesRoomAsmAuthoring || hasManagedFx()) { editSourceFx(stateId, doorSelect, change, sourceDoor = sourceDoor); return }
        require(sourceDoor == null) { "Symbolic door FX requires ASM source mode." }
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.ensureStateManifest(romParser)
        val stateEdits = roomEdits.stateEditsForId(stateId)
            ?: error("Room state '$stateId' is not available for room 0x${currentRoomId.toString(16)}")
        val key = doorSelect.toString(16).uppercase().padStart(4, '0')
        if (change == null) {
            stateEdits.doorFxChanges.remove(key)
        } else {
            stateEdits.doorFxChanges[key] = change
        }
        dirty = true
        editVersion++
    }

    // ─── Room header editing (area, map position, scrollers, CRE) ──

    fun setRoomHeaderChange(change: RoomHeaderChange) {
        if (hasManagedRoomProperties() || asmNavigationEnabled) {
            editSourceRoomProperties("Edit room header · ASM") { it.properties.editHeader(currentRoomId, change) }
            return
        }
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.roomHeaderChange = change
        dirty = true
        editVersion++
    }

    fun setRoomHeaderChangeForId(roomAddress: Int, change: RoomHeaderChange) {
        if (hasManagedRoomProperties(roomAddress)) {
            editSourceRoomProperties("Edit room header · ASM", roomAddress) { it.properties.editHeader(roomAddress, change) }
            return
        }
        val roomEdits = project.getOrCreateRoom(roomAddress)
        roomEdits.roomHeaderChange = change
        dirty = true
        editVersion++
    }

    /**
     * Move a room to another real ROM area and keep area-coupled data coherent.
     *
     * The room header has no "unassigned" area value: 0 is Crateria. This method
     * therefore accepts only the seven real areas, migrates the room's visible
     * minimap and map-station reveal rectangle, and recomputes the cross-area bit
     * for every incoming and outgoing door. Standard save-station AreaSave
     * entries are migrated into an unused preallocated destination slot.
     */
    private fun effectiveRoomPlms(roomId: Int, romParser: RomParser): List<RomParser.PlmEntry> {
        val room = project.rooms[project.roomKey(roomId)]
        if ((room?.asmPlmSourceVersion ?: 0) != 0) {
            val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the authored object source." }
            return room!!.states.flatMap { state ->
                val view = bridge.managedView(roomId, state.id)
                require(view.problem == null) { "Room ${roomId.toString(16)} objects: ${view.problem}" }
                requireNotNull(view.document).entries
            }.distinct()
        }
        val snapshot = com.supermetroid.editor.asm.snapshotRoomForResize(room ?: RoomEdits(roomId)).also { it.ensureStateManifest(romParser) }
        val runtime = romParser.parseRoomStatesWithData(roomId)
        return snapshot.states.flatMap { state ->
            val effective = romParser.parsePlmSet(runtime[requireNotNull(state.baseSourceStateIndex())].plmSetPtr).toMutableList()
            for (change in snapshot.plmChanges + state.plmChanges) {
                when (change.action) {
                    "add" -> effective.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                    "remove" -> effective.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y }
                }
            }
            effective
        }.distinct()
    }

    private fun effectiveCurrentStatePlms(parser: RomParser): List<RomParser.PlmEntry> {
        val edits = project.rooms[project.roomKey(currentRoomId)]
        val state = selectedStateEdits(edits)
        if (hasManagedPlms()) return state?.let { asmPlmSourceBridge?.managedView(currentRoomId, it.id)?.document?.entries }.orEmpty()
        val native = parser.parseRoomStatesWithData(currentRoomId).getOrNull(state?.baseSourceStateIndex() ?: currentStateIndex) ?: return emptyList()
        return applyPlmChanges(applyPlmChanges(parser.parsePlmSet(native.plmSetPtr), edits?.plmChanges.orEmpty()), state?.plmChanges.orEmpty())
    }

    private fun effectiveIncomingDoorPtr(roomId: Int, romParser: RomParser): Int? {
        for (info in romParser.roomCatalog.rooms) {
            val sourceRoomId = info.getRoomIdAsInt()
            for (door in effectiveDoorsForRoom(sourceRoomId, romParser)) {
                if (door.destRoomPtr == roomId && door.doorDefPtr != 0) return door.doorDefPtr
            }
        }
        return null
    }

    private fun planAreaSaveMigration(
        roomId: Int,
        sourceArea: Int,
        targetArea: Int,
        effectiveRooms: List<Room>,
        romParser: RomParser,
    ): AreaSaveMigrationPlanning {
        val authored = asmPlmSourceBridge?.let { bridge -> project.rooms.values.filter { it.asmPlmSourceVersion != 0 }
            .flatMap { bridge.saveResources(it.roomId) } }.orEmpty()
        val authoredBySlot = authored.associateBy { it.area to it.slot }
        val allOverrides = project.rooms.values.flatMap { it.saveStationSpawns } + authored.map { save ->
            SaveStationSpawnChange(save.area, save.slot, save.words[0], save.words[1], save.words[3], save.words[4], save.words[5], save.words[6], clearSlot = save.cleared)
        }
        val overridesBySlot = allOverrides.groupBy { it.area to it.saveIndex }
        val duplicateSlot = overridesBySlot.entries.firstOrNull { (_, entries) -> entries.size > 1 }
        if (duplicateSlot != null) {
            val (area, index) = duplicateSlot.key
            return AreaSaveMigrationPlanning(error = "Cannot migrate AreaSave: slot $area:$index already has conflicting project overrides.")
        }

        fun overrideAt(area: Int, index: Int): SaveStationSpawnChange? =
            overridesBySlot[area to index]?.singleOrNull()

        fun effectiveSlot(area: Int, index: Int): SaveStationSpawnChange? {
            val override = overrideAt(area, index)
            if (override != null) return override
            val rom = romParser.readSaveEntry(area, index) ?: return null
            return SaveStationSpawnChange(
                area = area,
                saveIndex = index,
                roomId = rom.roomId,
                doorPtr = rom.doorPtr,
                scrollX = rom.scrollX,
                scrollY = rom.scrollY,
                samusY = rom.samusY,
                samusX = rom.samusX,
            )
        }

        val savePlms = effectiveRoomPlms(roomId, romParser).filter { it.id == 0xB76F }
        val effectiveLoadReferences = buildList {
            for (area in 0 until MinimapData.NUM_AREAS) {
                for (index in 0 until romParser.saveEntryCount(area)) {
                    val slot = effectiveSlot(area, index) ?: continue
                    if (!slot.clearSlot && slot.roomId == roomId) add(index to slot)
                }
            }
        }
        val specialLoadReference = effectiveLoadReferences.firstOrNull {
            (index, _) -> index !in 0 until RomParser.SAVE_STATION_SLOT_COUNT
        }
        if (specialLoadReference != null) {
            val (index, slot) = specialLoadReference
            return AreaSaveMigrationPlanning(
                error = "Cannot move this room yet: AreaSave load-station entry ${slot.area}:$index is an " +
                    "elevator/start/debug record rather than a save-station slot.",
            )
        }
        val effectiveReferences = effectiveLoadReferences.map { it.second }
        val invalidProjectReference = allOverrides.firstOrNull {
            !it.clearSlot && it.roomId == roomId &&
                (it.area !in 0 until MinimapData.NUM_AREAS ||
                    it.saveIndex !in 0 until RomParser.SAVE_STATION_SLOT_COUNT ||
                    romParser.readSaveEntry(it.area, it.saveIndex) == null)
        }
        if (invalidProjectReference != null) {
            return AreaSaveMigrationPlanning(error = "Cannot migrate AreaSave: project slot ${invalidProjectReference.area}:${invalidProjectReference.saveIndex} is not writable.")
        }
        if (savePlms.isEmpty() && effectiveReferences.isEmpty()) return AreaSaveMigrationPlanning(plan = AreaSaveMigrationPlan())
        if (savePlms.isEmpty()) {
            return AreaSaveMigrationPlanning(
                error = "Cannot move this room yet: its AreaSave reference is a start/elevator entry rather than a save-station PLM.",
            )
        }

        val unrelatedReference = effectiveReferences.firstOrNull { it.area != sourceArea }
        if (unrelatedReference != null) {
            return AreaSaveMigrationPlanning(
                error = "Cannot migrate AreaSave: room reference ${unrelatedReference.area}:${unrelatedReference.saveIndex} is outside its current area $sourceArea.",
            )
        }

        // AreaSave entries are authoritative for room ownership. A few projects
        // created by older editor builds contain a save PLM whose low parameter
        // byte was reassigned while repainting the station, without a matching
        // AreaSave override. Reconcile the unique unmatched PLM/index pair here
        // so the move repairs that project instead of permanently blocking it.
        val plmsByParameterIndex = savePlms.groupBy { it.param and 0xFF }
        val sourceIndexByParameterIndex = mutableMapOf<Int, Int>()
        val sourceSlots: List<Pair<Int, SaveStationSpawnChange>>
        val sourceIndicesToClear: Set<Int>
        var reconstructedSlotCount = 0
        if (effectiveReferences.isEmpty()) {
            val legacyPlm = savePlms.singleOrNull()
            val wasAddedByProject = legacyPlm != null &&
                project.rooms[project.roomKey(roomId)]?.plmChanges.orEmpty().any { change ->
                    change.action == "add" && change.plmId == legacyPlm.id &&
                        change.x == legacyPlm.x && change.y == legacyPlm.y && change.param == legacyPlm.param
                }
            if (!wasAddedByProject || legacyPlm == null) {
                return AreaSaveMigrationPlanning(
                    error = "Cannot move this room yet: its save-station PLMs have no room-owned AreaSave reference.",
                )
            }
            val incomingDoorPtr = effectiveIncomingDoorPtr(roomId, romParser)
                ?: return AreaSaveMigrationPlanning(
                    error = "Cannot reconstruct this added save station because the room has no incoming door for its resume record.",
                )
            val parameterIndex = legacyPlm.param and 0xFF
            val scrollX = (legacyPlm.x / 16) * 256
            val scrollY = (legacyPlm.y / 16) * 256
            sourceIndexByParameterIndex[parameterIndex] = parameterIndex
            sourceSlots = listOf(
                parameterIndex to SaveStationSpawnChange(
                    area = sourceArea,
                    saveIndex = parameterIndex,
                    roomId = roomId,
                    doorPtr = incomingDoorPtr,
                    scrollX = scrollX.toUnsigned16(),
                    scrollY = scrollY.toUnsigned16(),
                    samusY = (legacyPlm.y * 16 - scrollY - 24).toUnsigned16(),
                    samusX = (legacyPlm.x * 16 - scrollX - 112).toUnsigned16(),
                    autoDerived = true,
                ),
            )
            sourceIndicesToClear = emptySet()
            reconstructedSlotCount = 1
        } else {
            val referencesByIndex = effectiveReferences.associateBy { it.saveIndex }
            val claimedReferenceIndices = mutableSetOf<Int>()
            for (parameterIndex in plmsByParameterIndex.keys.sorted()) {
                if (parameterIndex in referencesByIndex) {
                    sourceIndexByParameterIndex[parameterIndex] = parameterIndex
                    claimedReferenceIndices.add(parameterIndex)
                }
            }
            val unmatchedParameterIndices = plmsByParameterIndex.keys - sourceIndexByParameterIndex.keys
            val unmatchedReferences = effectiveReferences.filter { it.saveIndex !in claimedReferenceIndices }
            if (unmatchedParameterIndices.size == 1 && unmatchedReferences.size == 1) {
                sourceIndexByParameterIndex[unmatchedParameterIndices.single()] = unmatchedReferences.single().saveIndex
                claimedReferenceIndices.add(unmatchedReferences.single().saveIndex)
            }
            if (sourceIndexByParameterIndex.size != plmsByParameterIndex.size ||
                claimedReferenceIndices.size != effectiveReferences.size
            ) {
                return AreaSaveMigrationPlanning(
                    error = "Cannot move this room yet: its save-station PLMs and AreaSave references cannot be paired unambiguously.",
                )
            }
            sourceSlots = claimedReferenceIndices.sorted().map { index ->
                index to referencesByIndex.getValue(index)
            }
            sourceIndicesToClear = claimedReferenceIndices
        }

        val targetPlmIndices = effectiveRooms.asSequence()
            .filter { it.roomId != roomId && it.area == targetArea }
            .flatMap { room -> effectiveRoomPlms(room.roomId, romParser).asSequence() }
            .filter { it.id == 0xB76F }
            .map { it.param and 0xFF }
            .toMutableSet()
        val allocated = mutableSetOf<Int>()
        val destinationBySource = mutableMapOf<Int, Int>()
        for ((sourceIndex, _) in sourceSlots) {
            val candidates = (0 until romParser.saveStationSlotCount(targetArea)).filter { index ->
                if (index in targetPlmIndices || index in allocated) return@filter false
                if (authored.any { it.area == targetArea && it.slot == index && it.ownerRoomId != roomId }) return@filter false
                if (romParser.readSaveEntry(targetArea, index)?.roomId !in listOf(0, roomId)) return@filter false
                val slot = effectiveSlot(targetArea, index) ?: return@filter false
                slot.clearSlot || slot.roomId == 0
            }
            val destinationIndex = when {
                sourceIndex in candidates -> sourceIndex
                candidates.isNotEmpty() -> candidates.first()
                else -> return AreaSaveMigrationPlanning(
                    error = "Cannot move this save station to ${MinimapData.AREA_NAMES[targetArea]}: it has no empty preallocated AreaSave slot. Table expansion is required for this destination.",
                )
            }
            allocated.add(destinationIndex)
            destinationBySource[sourceIndex] = destinationIndex
        }

        val replacements = savePlms.map { plm ->
            val sourceIndex = sourceIndexByParameterIndex.getValue(plm.param and 0xFF)
            val destinationIndex = destinationBySource.getValue(sourceIndex)
            plm to ((plm.param and 0xFF00) or destinationIndex)
        }
        val overrides = buildList {
            for ((sourceIndex, sourceSlot) in sourceSlots) {
                if (sourceIndex in sourceIndicesToClear) {
                    add(
                        SaveStationSpawnChange(
                            area = sourceArea,
                            saveIndex = sourceIndex,
                            roomId = 0,
                            doorPtr = 0,
                            scrollX = 0x0400,
                            scrollY = 0x0400,
                            samusY = 0x00B0,
                            samusX = 0,
                            autoDerived = true,
                            clearSlot = true,
                        ),
                    )
                }
                add(
                    sourceSlot.copy(
                        area = targetArea,
                        saveIndex = destinationBySource.getValue(sourceIndex),
                        clearSlot = false,
                    ),
                )
            }
        }
        return AreaSaveMigrationPlanning(
            plan = AreaSaveMigrationPlan(
                plmReplacements = replacements,
                spawnOverrides = overrides,
                migratedSlotCount = sourceIndicesToClear.size,
                reconstructedSlotCount = reconstructedSlotCount,
                sourceRecords = overrides.map { spawn ->
                    val sourceIndex = destinationBySource.entries.singleOrNull { it.value == spawn.saveIndex }?.key
                    val sourceWords = sourceIndex?.let { authoredBySlot[sourceArea to it]?.words ?: romParser.readSaveEntry(sourceArea, it)?.words() }
                    val words = if (spawn.clearSlot) List(7) { 0 } else listOf(spawn.roomId, spawn.doorPtr,
                        if (reconstructedSlotCount > 0) 0 else requireNotNull(sourceWords)[2],
                        spawn.scrollX, spawn.scrollY, spawn.samusY, spawn.samusX)
                    roomSaveResource(roomId, spawn.area, spawn.saveIndex, words,
                        if (spawn.clearSlot) null else sourceIndex?.let { authoredBySlot[sourceArea to it]?.sourceDoor })
                },
            ),
        )
    }

    private fun applyAreaSaveMigration(roomId: Int, plan: AreaSaveMigrationPlan) {
        if (plan.migratedSlotCount == 0 && plan.reconstructedSlotCount == 0) return
        val roomEdits = project.getOrCreateRoom(roomId)
        for ((plm, newParam) in plan.plmReplacements) {
            roomEdits.plmChanges.add(PlmChange("remove", plm.id, plm.x, plm.y, plm.param))
            roomEdits.plmChanges.add(PlmChange("add", plm.id, plm.x, plm.y, newParam))
        }
        for (override in plan.spawnOverrides) {
            for (edits in project.rooms.values) {
                edits.saveStationSpawns.removeAll {
                    it.area == override.area && it.saveIndex == override.saveIndex
                }
            }
            roomEdits.saveStationSpawns.add(override)
        }
    }

    fun reassignRoomArea(
        roomId: Int,
        targetArea: Int,
        romParser: RomParser,
    ): RoomAreaReassignmentResult {
        val baseRoom = romParser.readRoomHeader(roomId)
            ?: return RoomAreaReassignmentResult(false, "Room 0x${roomId.toString(16).uppercase()} could not be read.", -1, targetArea)
        var sourceArea = applyHeaderChanges(baseRoom).area
        if (targetArea !in 0 until com.supermetroid.editor.rom.MinimapData.NUM_AREAS) {
            return RoomAreaReassignmentResult(false, "Area $targetArea is invalid; rooms must use Crateria through Ceres.", sourceArea, targetArea)
        }
        val sourceMove = project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE || project.rooms.values.any {
            it.asmRoomPropertiesSourceVersion != 0 || it.asmDoorSourceVersion != 0 || it.asmPlmSourceVersion != 0
        }
        if (sourceMove) runCatching {
            require(asmNavigationEnabled) { "Enable the project ASM source workspace before moving source-managed rooms." }
            val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the ASM workspace." }
            require(bridge.context.parser === romParser) { "The room ROM changed; wait for the current ASM link." }
            bridge.properties.refreshAll(); bridge.doors.refreshAll()
            for (room in project.rooms.values) {
                if (room.asmRoomPropertiesSourceVersion != 0) require(bridge.properties.view(room.roomId).problem == null) { "Repair the authored header/state fields for ${room.roomId.toString(16)} before moving areas." }
                if (room.asmDoorSourceVersion != 0) require(bridge.doors.view(room.roomId).problem == null) { "Repair the authored doors for ${room.roomId.toString(16)} before moving areas." }
            }
        }.onFailure {
            val message = "Cannot move ${baseRoom.name}: ${it.message}"
            postStatus(message)
            return RoomAreaReassignmentResult(false, message, sourceArea, targetArea)
        }
        sourceArea = applyHeaderChanges(baseRoom).area
        if (sourceArea == targetArea) {
            return RoomAreaReassignmentResult(true, "${baseRoom.name} is already in ${MinimapData.AREA_NAMES[targetArea]}.", sourceArea, targetArea)
        }

        val linkedRoomIds = project.rooms.values.filter { it.asmDoorSourceVersion != 0 }.flatMap { edits ->
            asmPlmSourceBridge?.doors?.view(edits.roomId)?.resource?.entries.orEmpty().map { it.change.destRoomPtr }
        }
        val effectiveRooms = (romParser.roomCatalog.rooms.map { it.getRoomIdAsInt() } + project.rooms.values.map { it.roomId } + linkedRoomIds + roomId).distinct().mapNotNull { id ->
            romParser.readRoomHeader(id)?.let(::applyHeaderChanges)
        }
        val areaSavePlanning = runCatching { planAreaSaveMigration(roomId, sourceArea, targetArea, effectiveRooms, romParser) }
            .getOrElse {
                val message = "Cannot move ${baseRoom.name}: ${it.message}"
                postStatus(message)
                return RoomAreaReassignmentResult(false, message, sourceArea, targetArea)
            }
        if (areaSavePlanning.error != null) {
            val message = "Cannot move ${baseRoom.name}: ${areaSavePlanning.error}"
            postStatus(message)
            return RoomAreaReassignmentResult(false, message, sourceArea, targetArea)
        }
        val areaSavePlan = areaSavePlanning.plan ?: AreaSaveMigrationPlan()
        val movingRoom = applyHeaderChanges(baseRoom)
        val sourceOwnership = roomMapOwnershipMask(
            movingRoom,
            effectiveRooms.filter { it.area == sourceArea },
        )
        val sourceMap = effectiveMinimapData(romParser, project, sourceArea)
        val destinationMap = effectiveMinimapData(romParser, project, targetArea)
        val roomTiles = extractRoomTiles(
            sourceMap,
            movingRoom.mapX,
            movingRoom.mapY,
            movingRoom.width,
            movingRoom.height,
            sourceOwnership,
        )
        val sourceStation = effectiveMapStationData(romParser, project, sourceArea)
        val destinationStation = effectiveMapStationData(romParser, project, targetArea)
        val destinationPosition = findAvailableRoomMapPosition(
            mapData = destinationMap,
            stationData = destinationStation,
            rooms = effectiveRooms.filter { it.roomId != roomId && it.area == targetArea },
            width = movingRoom.width,
            height = movingRoom.height,
            preferredX = movingRoom.mapX,
            preferredY = movingRoom.mapY,
        )
        if (destinationPosition == null) {
            val message = "Cannot move ${baseRoom.name}: ${com.supermetroid.editor.rom.MinimapData.AREA_NAMES[targetArea]} has no safe ${movingRoom.width}x${movingRoom.height} map rectangle available."
            postStatus(message)
            return RoomAreaReassignmentResult(false, message, sourceArea, targetArea)
        }
        val (destinationX, destinationY) = destinationPosition
        val stationRect = extractMapStationRect(
            sourceStation,
            movingRoom.mapX,
            movingRoom.mapY,
            movingRoom.width,
            movingRoom.height,
            sourceOwnership,
        )
        val mapPlan = project.copy(minimapEdits = project.minimapEdits.mapValuesTo(linkedMapOf()) { it.value.toMutableList() },
            mapStationEdits = project.mapStationEdits.mapValuesTo(linkedMapOf()) { it.value.toMutableList() })
        persistMinimapData(
            romParser,
            mapPlan,
            clearRoomTiles(
                sourceMap,
                movingRoom.mapX,
                movingRoom.mapY,
                movingRoom.width,
                movingRoom.height,
                sourceOwnership,
            ),
        )
        persistMinimapData(
            romParser,
            mapPlan,
            placeRoomTilesNonEmpty(
                destinationMap,
                destinationX,
                destinationY,
                movingRoom.width,
                movingRoom.height,
                roomTiles,
            ),
        )
        persistMapStationData(
            romParser,
            mapPlan,
            clearMapStationRect(
                sourceStation,
                movingRoom.mapX,
                movingRoom.mapY,
                movingRoom.width,
                movingRoom.height,
                sourceOwnership,
            ),
        )
        persistMapStationData(
            romParser,
            mapPlan,
            placeMapStationRect(destinationStation, destinationX, destinationY, stationRect),
        )

        val updatedHeader = (project.rooms[project.roomKey(roomId)]?.effectiveHeaderChange ?: RoomHeaderChange()).copy(
            area = targetArea.takeIf { it != baseRoom.area },
            mapX = destinationX.takeIf { it != baseRoom.mapX },
            mapY = destinationY.takeIf { it != baseRoom.mapY },
        )
        val doorUpdates = linkedMapOf<Int, MutableList<DoorChange>>()
        var updatedDoors = 0
        for (sourceRoom in effectiveRooms) {
            val sourceId = sourceRoom.roomId
            val effectiveSourceArea = if (sourceId == roomId) targetArea else sourceRoom.area
            val doors = effectiveDoorsForRoom(sourceId, romParser)
            for ((doorIndex, door) in doors.withIndex()) {
                val destination = effectiveRooms.firstOrNull { it.roomId == door.destRoomPtr } ?: continue
                if (sourceId != roomId && destination.roomId != roomId) continue
                val effectiveDestinationArea = if (destination.roomId == roomId) targetArea else destination.area
                val desiredBitflag = if (effectiveSourceArea != effectiveDestinationArea) {
                    door.bitflag or 0x40
                } else {
                    door.bitflag and 0x40.inv()
                }
                if (desiredBitflag == door.bitflag) continue
                val updatedDoor = DoorChange(
                    doorIndex = doorIndex,
                    destRoomPtr = door.destRoomPtr,
                    bitflag = desiredBitflag,
                    doorCapCode = door.doorCapCode,
                    screenX = door.screenX,
                    screenY = door.screenY,
                    distFromDoor = door.distFromDoor,
                    entryCode = door.entryCode,
                )
                doorUpdates.getOrPut(sourceId) { mutableListOf() }.add(updatedDoor)
                updatedDoors++
            }
        }

        val sourceHistory = if (sourceMove) runCatching {
            val bridge = requireNotNull(asmPlmSourceBridge)
            val plan = bridge.areaMoves.plan(roomId, updatedHeader.copy(area = targetArea, mapX = destinationX, mapY = destinationY),
                doorUpdates, areaSavePlan, mapPlan.minimapEdits, mapPlan.mapStationEdits, ::isEditorItemPlm, bridge.describePlm)
            applySourceRoomMove(roomId, romParser, plan)
        }.getOrElse {
            val message = "Cannot move ${baseRoom.name}: ${it.message}"
            postStatus(message)
            return RoomAreaReassignmentResult(false, message, sourceArea, targetArea)
        } else {
            project.minimapEdits.clear(); project.minimapEdits.putAll(mapPlan.minimapEdits)
            project.mapStationEdits.clear(); project.mapStationEdits.putAll(mapPlan.mapStationEdits)
            project.getOrCreateRoom(roomId).roomHeaderChange = updatedHeader.takeUnless { it == RoomHeaderChange() }
            applyAreaSaveMigration(roomId, areaSavePlan)
            for ((id, changes) in doorUpdates) {
                val edits = project.getOrCreateRoom(id)
                val native = romParser.parseDoorList(requireNotNull(romParser.readRoomHeader(id)).doorOut)
                for (change in changes) {
                    edits.doorChanges.removeAll { it.doorIndex == change.doorIndex }
                    val original = native[change.doorIndex]
                    if (change != DoorChange(change.doorIndex, original.destRoomPtr, original.bitflag, original.doorCapCode,
                            original.screenX, original.screenY, original.distFromDoor, original.entryCode)) edits.doorChanges.add(change)
                }
            }
            notifyProjectMutation(romParser)
            null
        }
        val repositioned = destinationX != movingRoom.mapX || destinationY != movingRoom.mapY
        val placementMessage = if (repositioned) {
            " and placed it at the nearest free map position ($destinationX, $destinationY)"
        } else ""
        val saveMessage = buildList {
            if (areaSavePlan.migratedSlotCount > 0) {
                add("migrated ${areaSavePlan.migratedSlotCount} AreaSave slot(s)")
            }
            if (areaSavePlan.reconstructedSlotCount > 0) {
                add("reconstructed ${areaSavePlan.reconstructedSlotCount} missing AreaSave slot(s)")
            }
        }.takeIf { it.isNotEmpty() }?.joinToString(prefix = ", ") ?: ""
        val message = "Moved ${baseRoom.name} to ${com.supermetroid.editor.rom.MinimapData.AREA_NAMES[targetArea]}$placementMessage; migrated map data$saveMessage and updated $updatedDoors door flag(s)."
        postStatus(message)
        return RoomAreaReassignmentResult(
            success = true,
            message = message,
            sourceArea = sourceArea,
            targetArea = targetArea,
            updatedDoorCount = updatedDoors,
            destinationMapX = destinationX,
            destinationMapY = destinationY,
        ).also { it.sourceHistory = sourceHistory }
    }

    private fun requireAreaMoveIdle() {
        require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null &&
            pendingPlmAdds.isEmpty() && pendingPlmRemoves.isEmpty() && pendingLayoutEditScope == null) {
            "Finish the current tile stroke, selection or scope choice before moving or restoring a room."
        }
    }

    /** Read authored positions before hit testing or checking room/map ownership. This is inspection only. */
    internal fun refreshRoomMapHeaders(parser: RomParser) {
        val owned = project.rooms.values.filter { it.asmRoomPropertiesSourceVersion != 0 }
        if (owned.isEmpty()) return
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the authored room headers." }
        require(bridge.context.parser === parser) { "The room ROM changed; reopen its current ASM workspace." }
        bridge.properties.refreshAll()
        for (room in owned) require(bridge.properties.view(room.roomId).problem == null) {
            "Repair the authored header/state fields for ${room.roomId.toString(16)} before moving rooms on the map."
        }
    }

    internal fun applySourceRoomMapMove(buffer: RoomMoveBuffer, parser: RomParser, map: MinimapData,
                                      stations: MapStationData): com.supermetroid.editor.asm.AsmRoomAreaMoveHistory {
        require(asmNavigationEnabled) {
            "Enable the project ASM source workspace before moving source-managed rooms."
        }
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the ASM workspace." }
        require(bridge.context.parser === parser) { "The room ROM changed; wait for the current ASM link." }
        refreshRoomMapHeaders(parser)
        val room = applyHeaderChanges(requireNotNull(parser.readRoomHeader(buffer.roomId)))
        require(room.area == map.area && stations.area == map.area && room.mapX == buffer.origX && room.mapY == buffer.origY &&
            room.width == buffer.width && room.height == buffer.height) { "The room's map position or dimensions changed; cancel and start this move again." }
        val planned = project.copy(minimapEdits = project.minimapEdits.toMutableMap(), mapStationEdits = project.mapStationEdits.toMutableMap())
        persistMinimapData(parser, planned, map)
        persistMapStationData(parser, planned, stations)
        val plan = bridge.areaMoves.plan(buffer.roomId,
            RoomHeaderChange(area = room.area, mapX = buffer.currentX, mapY = buffer.currentY), emptyMap(), AreaSaveMigrationPlan(),
            planned.minimapEdits, planned.mapStationEdits, ::isEditorItemPlm, bridge.describePlm)
        return applySourceRoomMove(buffer.roomId, parser, plan, "Move room on map")
    }

    private fun applySourceRoomMove(roomId: Int, parser: RomParser, plan: com.supermetroid.editor.asm.AsmRoomAreaMovePlan,
                                    description: String = "Move room to another area"): com.supermetroid.editor.asm.AsmRoomAreaMoveHistory {
        val bridge = requireNotNull(asmPlmSourceBridge)
        val activeRoom = currentRoomId
        val operation = EditOperation(description)
        var recorded = false
        return bridge.areaMoves.apply(plan, beforeTransition = {
            requireAreaMoveIdle()
            require(asmPlmSourceBridge?.catalog?.parser === parser) { "The room ROM changed; reopen its current ASM workspace." }
        }, afterTransition = { undo ->
            bridge.properties.refreshAll(); bridge.doors.refreshAll()
            if (!hasManagedPlms() && currentRomParser === parser) {
                _workingPlms.clear(); _workingPlms.addAll(effectiveCurrentStatePlms(parser))
            }
            refreshLinkedPlms()
            currentRomParser?.takeIf { it === parser }?.readRoomHeader(currentRoomId)?.let { native ->
                currentArea = applyHeaderChanges(native).area
                refreshVanillaSaveIndices(parser)
                currentAreaSaveEntryCount = parser.saveEntryCount(currentArea)
                currentAreaRomSaveEntries = (0 until currentAreaSaveEntryCount.coerceAtMost(RomParser.SAVE_STATION_SLOT_COUNT))
                    .mapNotNull { index -> parser.readSaveEntry(currentArea, index)?.let { index to it } }.toMap()
                if (!hasManagedDoors()) doorEntries = effectiveDoorsForRoom(currentRoomId, parser)
            }
            if (currentRoomId == activeRoom && activeRoom == roomId) {
                if (!recorded) {
                    recordManagedRoomOperation(operation, plan.replacements.getValue(PLM_SOURCE_FILE).first, bridge)
                    recorded = true
                } else if (undoStack.any { it === operation } || redoStack.any { it === operation }) {
                    undoStack.removeAll { it === operation }; redoStack.removeAll { it === operation }
                    if (undo) redoStack.add(operation) else undoStack.add(operation)
                }
            }
            dirty = true; undoVersion++; editVersion++
        })
    }

    /**
     * Return a copy of [room] with any project header changes (width, height,
     * area, etc.) and the state-data tileset override applied.
     */
    fun applyHeaderChanges(room: com.supermetroid.editor.data.Room): com.supermetroid.editor.data.Room {
        val edits = project.rooms[project.roomKey(room.roomId)] ?: return room
        val hc = edits.effectiveHeaderChange
        val sd = selectedStateEdits(edits)?.effectiveStateDataChange
        val commonSd = edits.effectiveStateDataChange
        val tileset = sd?.tileset ?: commonSd?.tileset ?: room.tileset
        val bgScrolling = sd?.bgScrolling ?: commonSd?.bgScrolling ?: room.bgScrolling
        if (hc == null && tileset == room.tileset && bgScrolling == room.bgScrolling) return room
        return room.copy(
            width = hc?.width ?: room.width,
            height = hc?.height ?: room.height,
            area = hc?.area ?: room.area,
            mapX = hc?.mapX ?: room.mapX,
            mapY = hc?.mapY ?: room.mapY,
            index = hc?.index ?: room.index,
            upScroller = hc?.upScroller ?: room.upScroller,
            downScroller = hc?.downScroller ?: room.downScroller,
            creBitflag = hc?.creBitflag ?: room.creBitflag,
            tileset = tileset,
            bgScrolling = bgScrolling,
        )
    }

    // ─── State data editing (tileset, music, BG scrolling) ──────

    fun setStateDataChange(change: StateDataChange) {
        if (hasManagedRoomProperties()) {
            postStatus("Room properties use source. Edit the selected state's fields, or apply a theme for all sharing states.")
            return
        }
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.stateDataChange = change
        change.bgScrolling?.let { currentBgScrolling = it }
        if (!canEditEmbeddedLayer2()) activeRoomLayer = RoomEditLayer.LAYER1
        dirty = true
        editVersion++
    }

    fun setRoomStateDataChange(stateId: String, change: StateDataChange?, romParser: RomParser) {
        if (hasManagedRoomProperties() || asmNavigationEnabled) {
            editSourceRoomProperties("Edit state metadata · ASM") { it.properties.editState(currentRoomId, stateId, change) }
            return
        }
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.ensureStateManifest(romParser)
        val stateEdits = roomEdits.stateEditsForId(stateId)
            ?: error("Room state '$stateId' is not available for room 0x${currentRoomId.toString(16)}")
        stateEdits.stateDataChange = change
        val common = roomEdits.effectiveStateDataChange
        val state = stateEdits.baseSourceStateIndex()
            ?.let { romParser.parseRoomStatesWithData(currentRoomId).getOrNull(it) }
        currentBgScrolling = change?.bgScrolling ?: common?.bgScrolling ?: state?.bgScrolling ?: currentBgScrolling
        if (!canEditEmbeddedLayer2()) activeRoomLayer = RoomEditLayer.LAYER1
        dirty = true
        editVersion++
    }

    fun setRoomStateCondition(
        stateId: String,
        condition: ProjectRoomStateCondition,
        romParser: RomParser,
    ) {
        if (hasManagedSelectors() || usesRoomAsmAuthoring) {
            editSourceSelectors("Edit state condition · ASM") { it.selectors.editCondition(currentRoomId, stateId, condition) }
            return
        }
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.ensureStateManifest(romParser)
        val stateEdits = roomEdits.stateEditsForId(stateId)
            ?: error("Room state '$stateId' is not available for room 0x${currentRoomId.toString(16)}")
        val source = stateEdits.sourceCondition ?: stateEdits.condition
        require(source.kind != com.supermetroid.editor.data.ProjectRoomStateConditionKind.DEFAULT) {
            "The mandatory default state condition cannot be changed"
        }
        if (stateEdits.sourceStateIndex == null || source.encodedSizeBytes() != condition.encodedSizeBytes()) {
            roomEdits.stateGraphChanged = true
        }
        stateEdits.condition = condition
        stateEdits.conditionChanged = stateEdits.sourceCondition?.let { condition != it } ?: true
        dirty = true
        editVersion++
    }

    /**
     * Add a conditional branch by cloning the selected state's effective
     * project deltas. Unedited source resources begin linked; copied local
     * deltas preserve the template's visible content and export privately.
     */
    fun addRoomState(
        templateStateId: String,
        condition: ProjectRoomStateCondition,
        romParser: RomParser,
    ): String {
        if (usesSourceStateLifecycle() || condition.flattened().any { it.sourceDoor != null })
            return addSourceRoomState(templateStateId, condition, romParser)
        require(condition.kind != com.supermetroid.editor.data.ProjectRoomStateConditionKind.DEFAULT) {
            "A room may contain only one mandatory default state"
        }
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.ensureStateManifest(romParser)
        val template = roomEdits.stateEditsForId(templateStateId)
            ?: error("Template state '$templateStateId' is not available")
        val templateSource = template.baseSourceStateIndex()
            ?: error("Template state '$templateStateId' has no ROM source state")
        if (hasManagedInitialScrolls()) {
            val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the initial scroll source before cloning this state." }
            val view = bridge.initialScrolls.view(currentRoomId, template.id)
            require(view.problem == null) { view.problem ?: "Resolve the initial scroll draft before cloning its state." }
        }
        if (hasManagedFx()) {
            val view = requireNotNull(asmPlmSourceBridge) { "Wait for the FX source before cloning this state." }.fx.view(currentRoomId, template.id)
            require(view.problem == null) { view.problem ?: "Resolve the FX draft before cloning its state." }
        }
        if (hasManagedEnemies()) {
            val view = requireNotNull(asmPlmSourceBridge).enemies.view(currentRoomId, template.id)
            require(view.problem == null) { view.problem ?: "Resolve the enemy draft before cloning its state." }
        }
        if (hasManagedRoomProperties()) {
            val view = requireNotNull(asmPlmSourceBridge).properties.view(currentRoomId)
            require(view.problem == null) { view.problem ?: "Resolve the room-property draft before cloning its state." }
        }
        var ordinal = 1
        var id: String
        do {
            id = "state-new-$ordinal"
            ordinal++
        } while (roomEdits.states.any { it.id == id } || asmPlmSourceBridge?.hasStateSource(currentRoomId, id) == true ||
            asmPlmSourceBridge?.initialScrolls?.hasState(currentRoomId, id) == true || asmPlmSourceBridge?.fx?.hasState(currentRoomId, id) == true ||
            asmPlmSourceBridge?.enemies?.hasState(currentRoomId, id) == true || asmPlmSourceBridge?.properties?.hasState(currentRoomId, id) == true)

        val newState = RoomStateEdits(
            id = id,
            templateSourceStateIndex = templateSource,
            condition = condition,
            resources = template.resources.copy(),
            conditionChanged = true,
            operations = template.operations.toMutableList(),
            plmChanges = template.plmChanges.toMutableList(),
            enemyChanges = template.enemyChanges.toMutableList(),
            scrollChanges = template.scrollChanges.toMutableList(),
            fxChange = template.fxChange?.copy(),
            doorFxChanges = template.doorFxChanges.mapValuesTo(linkedMapOf()) { it.value.copy() },
            stateDataChange = template.stateDataChange?.copy(),
            customScrollCommands = template.customScrollCommands
                .mapValuesTo(linkedMapOf()) { it.value.toMutableList() },
        )
        val templateIndex = roomEdits.states.indexOf(template)
        val defaultIndex = roomEdits.states.lastIndex
        val insertionIndex = if (templateIndex < defaultIndex) templateIndex + 1 else defaultIndex
        if (hasManagedPlms()) {
            val bridge = requireNotNull(asmPlmSourceBridge) { "ASM workspace is not ready." }
            bridge.cloneManagedState(currentRoomId, template.id, newState.id)
        }
        asmPlmSourceBridge?.initialScrolls?.cloneState(currentRoomId, template.id, newState.id)
        asmPlmSourceBridge?.fx?.cloneState(currentRoomId, template.id, newState.id)
        asmPlmSourceBridge?.enemies?.cloneState(currentRoomId, template.id, newState.id)
        asmPlmSourceBridge?.layouts?.cloneState(currentRoomId, template.id, newState.id)
        asmPlmSourceBridge?.properties?.cloneState(currentRoomId, template.id, newState.id)
        newState.asmStatePropertiesProjection = template.asmStatePropertiesProjection
        roomEdits.states.add(insertionIndex, newState)
        roomEdits.stateGraphChanged = true
        dirty = true
        editVersion++
        return id
    }

    /** Delete a conditional state and return the state that should remain selected. */
    fun deleteRoomState(stateId: String, romParser: RomParser): String {
        if (usesSourceStateLifecycle()) return changeSourceRoomStates("Delete room state", romParser) { bridge, _ ->
            var next = ""
            val selected = currentPlmStateId()
            val plan = bridge.stateLifecycle.plan(currentRoomId, romParser.readRoomHeader(currentRoomId)?.name ?: "Room") {
                val index = it.effectiveStates.indexOfFirst { state -> state.id == stateId }
                deleteRoomStateModel(it, stateId, romParser)
                next = selected?.takeIf { id -> it.states.any { state -> state.id == id } }
                    ?: (it.effectiveStates.getOrNull(index) ?: it.effectiveStates.last()).id
            }
            plan to next
        }
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.ensureStateManifest(romParser)
        val oldLevel = roomEdits.states.firstOrNull { it.id == stateId }?.resources?.level
        val next = deleteRoomStateModel(roomEdits, stateId, romParser)
        if (oldLevel != null && roomEdits.states.none { it.resources.level == oldLevel }) {
            layoutEditScopes.remove(layoutScopeKey(currentRoomId, oldLevel))
        }
        dirty = true; editVersion++
        return next
    }

    /** Private staging helper: no GUI history, selection or live project mutation. */
    private fun deleteRoomStateModel(roomEdits: RoomEdits, stateId: String, romParser: RomParser): String {
        val index = roomEdits.states.indexOfFirst { it.id == stateId }
        require(index >= 0) { "Room state '$stateId' is not available" }
        require(roomEdits.states[index].condition.kind !=
            com.supermetroid.editor.data.ProjectRoomStateConditionKind.DEFAULT
        ) { "The mandatory default state cannot be deleted" }
        val deleting = roomEdits.states[index]
        val linkedDependents = if (!deleting.levelResourceChanged) {
            roomEdits.states.filter { state ->
                state.id != deleting.id && state.resources.level == deleting.resources.level
            }
        } else {
            emptyList()
        }
        if (linkedDependents.isNotEmpty() && roomEdits.asmLayoutSourceVersion == 0) {
            // Keep a valid owner for the semantic resource. Normalize the shared
            // layout against the promoted state's own ROM source before the old
            // owner disappears from the authored graph.
            val promoted = linkedDependents.first()
            val sharedLayout = effectiveLevelDataForState(
                roomEdits = roomEdits,
                state = deleting,
                romParser = romParser,
                includeStateOperations = false,
            ) ?: error("Could not preserve the shared layout while deleting state '${deleting.id}'")
            val promotedBaseline = effectiveLevelDataForState(
                roomEdits = roomEdits,
                state = promoted,
                romParser = romParser,
                followLinkedResource = false,
                includeSharedOperations = false,
                includeStateOperations = false,
            ) ?: error("Could not prepare a replacement owner for layout '${deleting.resources.level}'")
            val edits = buildLevelDataDiff(
                oldData = promotedBaseline,
                newData = sharedLayout,
                blocksWide = workingBlocksWide,
                blocksTall = workingBlocksTall,
            )
            if (edits.isEmpty()) {
                roomEdits.levelResourceOperations.remove(deleting.resources.level)
            } else {
                roomEdits.levelResourceOperations[deleting.resources.level] = mutableListOf(
                    EditOperation("Preserve shared layout after deleting a state", edits)
                )
            }
            promoted.levelResourceChanged = false
        }
        roomEdits.states.removeAt(index)
        pruneUnusedLevelResources(roomEdits)
        roomEdits.stateGraphChanged = true
        val selected = roomEdits.states.getOrNull(index) ?: roomEdits.states.last()
        return selected.id
    }

    private fun usesSourceStateLifecycle(): Boolean = hasManagedDoors() || hasManagedSelectors() || hasManagedRoomProperties() || hasManagedLayouts() ||
        hasManagedPlms() || hasManagedInitialScrolls() || hasManagedFx() || hasManagedEnemies()

    private fun addSourceRoomState(templateId: String, condition: ProjectRoomStateCondition, parser: RomParser): String =
        changeSourceRoomStates("Add room state", parser) { bridge, room ->
            require(condition.kind != com.supermetroid.editor.data.ProjectRoomStateConditionKind.DEFAULT) { "A room may contain only one mandatory default state." }
            val id = bridge.stateLifecycle.nextId(room)
            val plan = bridge.stateLifecycle.plan(currentRoomId, parser.readRoomHeader(currentRoomId)?.name ?: "Room", templateId to id, condition) { staged ->
                val template = requireNotNull(staged.states.firstOrNull { it.id == templateId }) { "The template state is no longer available." }
                val state = template.copy(id = id, sourceStateIndex = null, sourceCondition = null,
                    templateSourceStateIndex = requireNotNull(template.baseSourceStateIndex()), condition = condition, conditionChanged = true,
                    operations = template.operations.toMutableList(), plmChanges = template.plmChanges.toMutableList(),
                    enemyChanges = template.enemyChanges.toMutableList(), scrollChanges = template.scrollChanges.toMutableList(),
                    doorFxChanges = template.doorFxChanges.toMutableMap(),
                    customScrollCommands = template.customScrollCommands.mapValuesTo(linkedMapOf()) { it.value.toMutableList() })
                val index = staged.states.indexOf(template)
                staged.states.add(if (index == staged.states.lastIndex) index else index + 1, state)
            }
            plan to id
        }

    /** The same compound source entry is undoable from any bank or the visual editor. */
    private fun changeSourceRoomStates(description: String, parser: RomParser,
        prepare: (AsmPlmSourceBridge, RoomEdits) -> Pair<com.supermetroid.editor.asm.AsmRoomStatePlan, String>): String {
        val roomId = currentRoomId
        val projectId = project.newRoomForPreviewId(roomId)?.id
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        require(matchesRoomSourceContext(bridge.catalog.parser) && currentRomParser === parser) { "The room ROM changed; reopen the room before changing states." }
        fun requireIdle() {
            require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null &&
                pendingPlmAdds.isEmpty() && pendingPlmRemoves.isEmpty() && pendingLayoutEditScope == null) {
                "Finish the active stroke, selection or scope choice before changing states."
            }
        }
        requireIdle()
        val room = requireNotNull(project.rooms[project.roomKey(roomId)]) { "Load the room's state manifest before changing states." }
        require(room.states.isNotEmpty()) { "The source-owned room has no state manifest; repair its identity metadata before changing states." }
        val (plan, selectedAfter) = prepare(bridge, room)
        val selectedBefore = currentPlmStateId()
        val previousUndo = undoStack.toList()
        val previousManaged = java.util.IdentityHashMap(managedPlmUndo)
        val previousScopes = java.util.IdentityHashMap(itemScopeSnapshots)
        val operation = EditOperation(description)
        var sourceChange: AsmSourceTextChange? = null
        bridge.stateLifecycle.apply(plan, beforeTransition = { requireIdle() }, afterTransition = { undo ->
            val liveId = if (projectId == null) roomId else project.newRooms.singleOrNull { it.id == projectId }?.previewRoomId
            val viewParser = if (projectId == null) parser else currentRomParser
            if (currentRoomId == liveId && viewParser != null && (currentRomParser === parser || projectId != null && matchesRoomSourceContext(bridge.catalog.parser))) {
                val retainedRedo = if (sourceChange == null) emptyList() else redoStack.filter { it !== operation }
                val retainedManaged = java.util.IdentityHashMap(managedPlmUndo)
                val retainedScopes = java.util.IdentityHashMap(itemScopeSnapshots)
                loadRoom(currentRoomId, viewParser, requireNotNull(viewParser.readRoomHeader(currentRoomId)))
                val selected = if (undo) selectedBefore else selectedAfter
                if (selected != null && project.rooms[project.roomKey(currentRoomId)]?.states?.any { it.id == selected } == true) switchRoomState(selected, viewParser)
                undoStack.clear(); redoStack.clear(); managedPlmUndo.clear(); itemScopeSnapshots.clear()
                if (sourceChange == null) sourceChange = requireNotNull(bridge.sourceUndoHead())
                managedPlmUndo.putAll(previousManaged)
                managedPlmUndo.putAll(retainedManaged)
                managedPlmUndo[operation] = ManagedSourceUndo(PLM_SOURCE_FILE,
                    plan.replacements.getValue(PLM_SOURCE_FILE).first, plan.replacements.getValue(PLM_SOURCE_FILE).second, sourceChange)
                itemScopeSnapshots.putAll(previousScopes)
                itemScopeSnapshots.putAll(retainedScopes)
                undoStack.addAll(previousUndo)
                redoStack.addAll(retainedRedo)
                if (undo) redoStack.add(operation) else undoStack.add(operation)
            }
            dirty = true; undoVersion++; editVersion++
        })
        return selectedAfter
    }

    fun moveRoomState(stateId: String, direction: Int, romParser: RomParser): Boolean {
        require(direction == -1 || direction == 1) { "State move direction must be -1 or 1" }
        if (hasManagedSelectors() || usesRoomAsmAuthoring) {
            return editSourceSelectors("Reorder state selectors · ASM") { it.selectors.move(currentRoomId, stateId, direction) }
        }
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        roomEdits.ensureStateManifest(romParser)
        val index = roomEdits.states.indexOfFirst { it.id == stateId }
        if (index < 0) return false
        val state = roomEdits.states[index]
        if (state.condition.kind == com.supermetroid.editor.data.ProjectRoomStateConditionKind.DEFAULT) return false
        val target = index + direction
        if (target !in 0 until roomEdits.states.lastIndex) return false
        roomEdits.states[index] = roomEdits.states[target]
        roomEdits.states[target] = state
        roomEdits.stateGraphChanged = true
        dirty = true
        editVersion++
        return true
    }

    /** Flood fill: replace all connected tiles matching the one at (bx, by) with brush. */
    fun floodFill(bx: Int, by: Int): Boolean {
        if (!allowOrQueueLayoutEdit {
                beginStroke()
                floodFill(bx, by)
                endStroke()
            }
        ) return false
        val b = brush ?: return false
        if (b.cols != 1 || b.rows != 1) return false  // fill only works with 1×1 brush
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return false
        if (activeRoomLayer == RoomEditLayer.LAYER2) return floodFillLayer2(bx, by, b)
        val targetWord = readBlockWord(bx, by)
        val newWord = b.blockWordAt(0, 0)
        val newBts = b.btsAt(0, 0)
        if (targetWord == newWord) return false

        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(Pair(bx, by))
        var changed = false
        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.removeFirst()
            if (cx < 0 || cy < 0 || cx >= workingBlocksWide || cy >= workingBlocksTall) continue
            val key = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
            if (visited.contains(key)) continue
            visited.add(key)
            if (readBlockWord(cx, cy) != targetWord) continue
            writeBlockWord(cx, cy, newWord)
            val oldBts = readBts(cx, cy)
            writeBts(cx, cy, newBts)
            pendingEdits.add(TileEdit(cx, cy, targetWord, newWord, oldBts, newBts))
            changed = true
            queue.add(Pair(cx - 1, cy))
            queue.add(Pair(cx + 1, cy))
            queue.add(Pair(cx, cy - 1))
            queue.add(Pair(cx, cy + 1))
        }
        return changed
    }

    private fun floodFillLayer2(bx: Int, by: Int, b: TileBrush): Boolean {
        if (!canEditEmbeddedLayer2()) return false
        val targetWord = readLayer2BlockWord(bx, by)
        val newWord = b.blockWordAt(0, 0) and 0x0FFF
        if (targetWord == newWord) return false

        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(Pair(bx, by))
        var changed = false
        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.removeFirst()
            if (cx < 0 || cy < 0 || cx >= workingBlocksWide || cy >= workingBlocksTall) continue
            val key = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
            if (visited.contains(key)) continue
            visited.add(key)
            if (readLayer2BlockWord(cx, cy) != targetWord) continue
            writeLayer2BlockWord(cx, cy, newWord)
            pendingEdits.add(TileEdit(cx, cy, targetWord, newWord, layer = TILE_EDIT_LAYER_2))
            changed = true
            queue.add(Pair(cx - 1, cy))
            queue.add(Pair(cx + 1, cy))
            queue.add(Pair(cx, cy - 1))
            queue.add(Pair(cx, cy + 1))
        }
        return changed
    }

    fun endStroke(description: String? = null): Boolean {
        pendingPlmGeometry = null
        if (pendingEdits.isEmpty() && pendingPlmAdds.isEmpty() && pendingSourcePattern?.stamps.isNullOrEmpty()) {
            pendingSourcePattern = null
            return false
        }
        val layerLabel = if (pendingEdits.any { it.layer == TILE_EDIT_LAYER_2 }) "Layer 2 " else ""
        val desc = description ?: if (floatingSelection != null) {
            "${layerLabel}Place selection ${pendingEdits.size} tile(s)"
        } else {
            when (activeTool) {
                EditorTool.PAINT -> "${layerLabel}Paint ${pendingEdits.size} tile(s)"
                EditorTool.FILL -> "${layerLabel}Fill ${pendingEdits.size} tile(s)"
                EditorTool.ERASE -> "${layerLabel}Erase ${pendingEdits.size} tile(s)"
                EditorTool.SAMPLE -> "Sample"
                EditorTool.SELECT -> "Select"
            }
        }
        val operation = EditOperation(
            desc, pendingEdits.toList(),
            plmAdds = pendingPlmAdds.toList(),
            plmRemoves = pendingPlmRemoves.toList(),
            itemScope = if (pendingAllStateItemBefore != null) ItemStateScope.ALL_STATES else null,
        )
        if (pendingSourcePattern?.stamps?.isNotEmpty() == true) return commitSourcePattern(operation)
        pendingSourcePattern = null
        val applied = pushEditOperation(operation)
        pendingAllStateItemBefore?.takeIf { applied }?.let { before ->
            itemScopeSnapshots[operation] = ItemScopeUndo(before, itemScopeSnapshot())
        }
        pendingEdits.clear()
        pendingPositions.clear()
        pendingPlmAdds.clear()
        pendingPlmRemoves.clear()
        pendingAllStateItemBefore = null
        refreshLayoutSource()
        return applied
    }

    /** Source and layout history share a reversible boundary, not a second set of PLM deltas. */
    private fun commitSourcePattern(operation: EditOperation): Boolean {
        val stroke = requireNotNull(pendingSourcePattern)
        return runCatching {
            val bridge = requireNotNull(asmPlmSourceBridge)
            require(project === stroke.owner && currentRoomId == stroke.room.roomId &&
                currentPlmStateId() == stroke.stateId && currentStateEdits()?.resources?.level == stroke.levelId &&
                workingBlocksWide == stroke.width && workingBlocksTall == stroke.height) {
                "Room or layout changed during painting; try the stroke again."
            }
            val room = stroke.room
            val projected = effectiveLevelDataForState(room, requireNotNull(currentStateEdits()), requireNotNull(currentRomParser),
                prospectiveLayoutEdits = operation.edits)
            require(projected?.contentEquals(workingLevelData) == true) {
                "Older state-specific layout overrides conflict with this pattern. Reconcile the layout before stamping."
            }
            stroke.stamps.forEach { stamp ->
                validatePlmSharedGeometry(stamp.entry.id, stamp.entry.x, stamp.entry.y, stamp.allStates, geometryReference = null)
            }
            val layout = prepareSourceLayoutEdit(operation)
            val changed = bridge.applyStamps(room.roomId, stroke.stateId, stroke.before, stroke.stamps,
                beforeUndo = { require(room.asmPlmSourceVersion == 1); layout.undo() },
                beforeRedo = { require(room.asmPlmSourceVersion == 1); layout.redo() }, layoutEdits = operation.edits)
            // From here onward there are no fallible resource operations. Tiles are already previewed.
            if (changed) {
                layout.publish()
                recordManagedRoomOperation(operation, stroke.before, bridge)
            } else if (operation.edits.isNotEmpty()) pushEditOperation(operation)
            pendingSourcePattern = null
            pendingEdits.clear()
            pendingPositions.clear()
            pendingPlmAdds.clear()
            pendingPlmRemoves.clear()
            pendingAllStateItemBefore = null
            refreshLinkedPlms()
            true
        }.getOrElse {
            rollbackSourcePattern()
            postStatus("Pattern blocked: ${it.message ?: "No changes applied."}")
            false
        }
    }

    private data class SourceLayoutEdit(val publish: () -> Unit, val undo: () -> Unit, val redo: () -> Unit)

    /** Shared reversible layout sidecar for source-owned patterns and generated-room transactions. */
    private fun prepareSourceLayoutEdit(operation: EditOperation): SourceLayoutEdit {
        val owner = project
        val room = project.getOrCreateRoom(currentRoomId)
        val levelId = requireNotNull(currentStateEdits()).resources.level
        val statesBefore = room.states.map { it.id to it.resources.level }
        val headerBefore = room.effectiveHeaderChange
        val previous = room.levelResourceOperations[levelId].orEmpty().toList()
        val next = if (operation.edits.isEmpty() || room.asmLayoutSourceVersion != 0) previous else previous + operation.tileOnly()
        fun applyModel(undo: Boolean, history: Boolean) {
            val target = if (undo) previous else next
            if (target.isEmpty()) room.levelResourceOperations.remove(levelId)
            else room.levelResourceOperations[levelId] = target.toMutableList()
            if (currentRoomId == room.roomId && currentStateEdits()?.resources?.level == levelId) {
                (if (undo) operation.edits.asReversed() else operation.edits).forEach { applyTileEdit(it, useNew = !undo) }
                if (history) {
                    val from = if (undo) undoStack else redoStack
                    val to = if (undo) redoStack else undoStack
                    val index = from.indexOfFirst { it === operation }
                    if (index >= 0) { from.removeAt(index); to.add(operation) }
                }
            }
            dirty = true; undoVersion++; editVersion++
        }
        fun transition(undo: Boolean) {
            require(project === owner && project.rooms[project.roomKey(room.roomId)] === room &&
                room.states.map { it.id to it.resources.level } == statesBefore && room.effectiveHeaderChange == headerBefore) {
                "Undo newer room/state changes before this source/layout edit."
            }
            require(room.levelResourceOperations[levelId].orEmpty() == if (undo) next else previous) {
                "Undo newer layout edits before this source/layout edit."
            }
            require(pendingEdits.isEmpty()) { "Finish the current tile stroke before undoing source." }
            applyModel(undo, history = true)
        }
        return SourceLayoutEdit({ applyModel(undo = false, history = false) }, { transition(true) }, { transition(false) })
    }

    private fun recordManagedRoomOperation(operation: EditOperation, before: String, bridge: AsmPlmSourceBridge, fileId: String = PLM_SOURCE_FILE) {
        val after = when (fileId) { FX_SOURCE_FILE -> bridge.fx.sourceText(); ENEMY_SOURCE_FILE -> bridge.enemies.sourceText(); else -> bridge.sourceText() }
        managedPlmUndo[operation] = ManagedSourceUndo(fileId, before, after, requireNotNull(bridge.sourceUndoHead(fileId)))
        undoStack.add(operation); redoStack.clear(); undoVersion++
        dirty = true; editVersion++
    }

    /**
     * Apply a batch of tile edits as a single undoable operation.
     * Used by the biome generator to replace a whole room in one step.
     */
    fun applyBulkEdits(description: String, edits: List<TileEdit>) {
        if (edits.isEmpty()) return
        if (!allowOrQueueLayoutEdit { applyBulkEdits(description, edits) }) return
        // Bulk commands edit the active whole layout resource. If that resource
        // is shared, every linked state receives the same layout change.
        for (e in edits) {
            applyTileEdit(e, useNew = true)
        }
        pushEditOperation(EditOperation(description, edits))
    }

    /**
     * Validate and repair learned proposals against the currently loaded room.
     * No editor state is changed; callers can safely render and rank the result
     * before the user chooses one candidate to apply.
     */
    fun prepareLearnedRoomProposals(
        proposals: List<LearnedRoomProposal>,
        romParser: RomParser?,
        keepLandingSiteShipClear: Boolean = true,
        tilesetProfile: TilesetProfile? = null,
    ): List<PreparedLearnedRoomCandidate> {
        val width = workingBlocksWide
        val height = workingBlocksTall
        require(currentRoomId != 0 && width > 0 && height > 0) { "Load a room first" }
        require(proposals.isNotEmpty()) { "Proposal file contains no candidates" }
        val size = width * height
        val originalWords = IntArray(size) { readBlockWord(it % width, it / width) }
        val originalBts = IntArray(size) { readBts(it % width, it / width) }
        val options = buildBiomeGenerationOptions(
            keepLandingSiteShipClear,
            romParser,
            originalWords,
            originalBts,
        )
        return proposals.map { proposal ->
            LearnedRoomProposalProcessor.prepare(
                proposal = proposal,
                expectedRoomId = currentRoomId,
                expectedTileset = currentTilesetId,
                originalWords = originalWords,
                originalBts = originalBts,
                width = width,
                height = height,
                options = options,
                tilesetProfile = tilesetProfile,
            )
        }.sortedWith(
            compareByDescending<PreparedLearnedRoomCandidate> { it.score }
                .thenBy { it.proposal.rank.takeIf { rank -> rank > 0 } ?: Int.MAX_VALUE }
                .thenBy { it.proposal.generator.seed }
        )
    }

    /**
     * Apply one reviewed learned candidate as a single undoable room edit.
     * The current layout may be the original preview base or another candidate
     * from the same still-open gallery, which makes visual A/B comparison safe.
     */
    fun applyLearnedRoomProposal(
        candidate: PreparedLearnedRoomCandidate,
        reviewedCandidates: List<PreparedLearnedRoomCandidate> = listOf(candidate),
    ): Int {
        val width = workingBlocksWide
        val height = workingBlocksTall
        require(currentRoomId == candidate.proposal.source.roomId) { "The loaded room changed; reload candidates" }
        require(currentTilesetId == candidate.proposal.tileset) { "The room tileset changed; reload candidates" }
        require(width == candidate.proposal.widthBlocks && height == candidate.proposal.heightBlocks) {
            "The room dimensions changed; reload candidates"
        }
        val size = width * height
        val currentWords = IntArray(size) { readBlockWord(it % width, it / width) }
        val currentBts = IntArray(size) { readBts(it % width, it / width) }
        val matchesPreviewBase = currentWords.contentEquals(candidate.baseWords) &&
            currentBts.contentEquals(candidate.baseBts)
        val matchesReviewedCandidate = reviewedCandidates.any { reviewed ->
            reviewed.proposal.source.roomId == candidate.proposal.source.roomId &&
                reviewed.proposal.tileset == candidate.proposal.tileset &&
                currentWords.contentEquals(reviewed.words) &&
                currentBts.contentEquals(reviewed.bts)
        }
        require(matchesPreviewBase || matchesReviewedCandidate) {
            "The room layout changed after preview and does not match a reviewed candidate; reload candidates"
        }

        val edits = ArrayList<TileEdit>()
        for (i in 0 until size) {
            if (candidate.words[i] != currentWords[i] || candidate.bts[i] != currentBts[i]) {
                edits.add(
                    TileEdit(
                        blockX = i % width,
                        blockY = i / width,
                        oldBlockWord = currentWords[i],
                        newBlockWord = candidate.words[i],
                        oldBts = currentBts[i],
                        newBts = candidate.bts[i],
                    )
                )
            }
        }
        val scrollEdits = buildGeneratedRoomScrollResetEdits()
        val scrollPlmRemoves = buildScrollPlmRemovals()
        val rank = candidate.proposal.rank.takeIf { it > 0 }?.let { " #$it" }.orEmpty()
        val applied = applyGeneratedRoomOperation(
            description = "Apply learned room$rank (seed ${candidate.proposal.generator.seed})",
            edits = edits,
            scrollEdits = scrollEdits,
            scrollPlmRemoves = scrollPlmRemoves,
        )
        return if (applied) edits.size else 0
    }

    data class BulkBiomeResult(
        val generatedRooms: Int,
        val skippedRooms: Int,
        val changedTiles: Int,
        val manualSkippedRooms: Int = 0,
    )

    /**
     * Run the biome generator on the working room and apply the result as one
     * undoable operation. Returns the number of tiles changed.
     */
    fun generateBiome(
        rules: BiomeRules,
        profile: TilesetProfile,
        seed: Long,
        keepLandingSiteShipClear: Boolean = true,
        romParser: RomParser? = null,
        theme: BiomeTheme? = null,
    ): Int {
        val w = workingBlocksWide
        val h = workingBlocksTall
        if (w <= 0 || h <= 0) return 0
        if (theme != null && (theme.tilesetId != null || theme.paletteEffectId != null || theme.fxType != null) &&
            !usesRoomAsmAuthoring && !hasManagedPlms() && !hasManagedInitialScrolls() && !hasManagedFx() && !hasManagedEnemies() && !hasManagedLayouts() && !hasManagedRoomProperties()) {
            val parser = romParser ?: currentRomParser
            if (parser == null) { postStatus("Theme blocked: load a ROM first."); return 0 }
            applyBiomeTheme(theme, parser)
        }

        val n = w * h
        val origWords = IntArray(n) { readBlockWord(it % w, it / w) }
        val origBts = IntArray(n) { readBts(it % w, it / w) }
        val stateContents = runCatching { biomeGenerationStateContents() }.getOrElse {
            postStatus("Generation blocked: ${it.message ?: "Review the room source before generating."}")
            return 0
        }
        val options = buildBiomeGenerationOptions(
            keepLandingSiteShipClear,
            romParser,
            origWords,
            origBts,
            stateContents,
        )
        val result = BiomeGenerator(rules, profile, seed, options).generate(w, h, origWords, origBts)

        val edits = ArrayList<TileEdit>()
        for (i in 0 until n) {
            if (result.words[i] != origWords[i] || result.bts[i] != origBts[i]) {
                edits.add(TileEdit(i % w, i / w, origWords[i], result.words[i], origBts[i], result.bts[i]))
            }
        }
        val scrollEdits = buildGeneratedRoomScrollResetEdits()
        val scrollPlmRemoves = buildScrollPlmRemovals()
        val applied = applyGeneratedRoomOperation(
            "Generate biome (${rules.style.displayName}, seed $seed)",
            edits,
            scrollEdits,
            scrollPlmRemoves,
            theme,
            validatePreparedInputs = { validateBiomeGenerationStateContents(stateContents) },
        )
        return if (applied) edits.size else 0
    }

    fun generateBiomeForAllRooms(
        rules: BiomeRules,
        theme: BiomeTheme,
        seed: Long,
        romParser: RomParser,
        omitSpecialRooms: Boolean = true,
    ): BulkBiomeResult {
        val repository = RoomRepository()
        val roomInfos = repository.getAllRooms()
        val headers = roomInfos
            .mapNotNull { info -> romParser.readRoomHeader(info.getRoomIdAsInt()) }
            .filter { it.levelDataPtr != 0 && it.width > 0 && it.height > 0 }
        val headersById = headers.associateBy { it.roomId }

        prepareBulkTheme(theme, romParser)
        var generated = 0
        var skipped = 0
        var manualSkipped = 0
        var changedTiles = 0

        for (roomInfo in roomInfos.sortedBy { it.getRoomIdAsInt() }) {
            val roomId = roomInfo.getRoomIdAsInt()
            val linkedPointers = project.asmWorkspace.plmSourceBindings.map { it.pointer }.toSet()
            if (romParser.findAllStateDataOffsets(roomId).any {
                    val pointer = romParser.readStateData(it)["plmSetPtr"]
                    pointer in linkedPointers || (pointer != null && asmPlmSourceBridge?.isLinked(pointer) == true)
                }) {
                skipped++
                continue
            }
            val roomKey = project.roomKey(roomId)
            val romRoom = headersById[roomId]
            if ((project.rooms[roomKey]?.asmPlmSourceVersion ?: 0) != 0 ||
                (project.rooms[roomKey]?.asmInitialScrollSourceVersion ?: 0) != 0 || (project.rooms[roomKey]?.asmFxSourceVersion ?: 0) != 0 ||
                (project.rooms[roomKey]?.asmEnemySourceVersion ?: 0) != 0 || (project.rooms[roomKey]?.asmLayoutSourceVersion ?: 0) != 0 || (project.rooms[roomKey]?.asmRoomPropertiesSourceVersion ?: 0) != 0 || (project.rooms[roomKey]?.asmStateSelectorsSourceVersion ?: 0) != 0 || (project.rooms[roomKey]?.asmDoorSourceVersion ?: 0) != 0) {
                skipped++
                manualSkipped++
                continue
            }
            if (romRoom == null) {
                skipped++
                continue
            }
            if (omitSpecialRooms && shouldSkipBulkBiomeRoom(roomInfo, romRoom)) {
                skipped++
                continue
            }
            if (hasManualBiomeBlockingEdits(project.rooms[roomKey])) {
                skipped++
                manualSkipped++
                continue
            }

            val stripped = stripGeneratedBiomeEdits(project.rooms[roomKey])
            val effectiveRoom = applyHeaderChanges(romRoom)
            val targetTileset = theme.tilesetId ?: project.rooms[roomKey]?.effectiveStateDataChange?.tileset ?: effectiveRoom.tileset
            val profile = TilesetProfileCache.getOrLearn(romParser, headers, targetTileset)
            val grids = buildEffectiveRoomGrids(romParser, romRoom, effectiveRoom)
            if (grids == null) {
                skipped++
                continue
            }
            val options = buildBiomeGenerationOptionsForRoom(
                roomId = roomId,
                width = grids.width,
                height = grids.height,
                originalWords = grids.words,
                originalBts = grids.bts,
                romParser = romParser,
            )
            val roomSeed = seed xor (roomId.toLong() * -7046029254386353131L)
            val generatedLevel = BiomeGenerator(rules, profile, roomSeed, options)
                .generate(grids.width, grids.height, grids.words, grids.bts)

            val edits = ArrayList<TileEdit>()
            for (i in generatedLevel.words.indices) {
                if (generatedLevel.words[i] != grids.words[i] || generatedLevel.bts[i] != grids.bts[i]) {
                    edits.add(
                        TileEdit(
                            i % grids.width,
                            i / grids.width,
                            grids.words[i],
                            generatedLevel.words[i],
                            grids.bts[i],
                            generatedLevel.bts[i],
                        )
                    )
                }
            }
            val scrollEdits = buildGeneratedRoomScrollResetEditsForRoom(romParser, roomId, romRoom, effectiveRoom)
            val scrollPlmRemoves = buildScrollPlmRemovalsForRoom(romParser, roomId)

            val roomEdits = project.getOrCreateRoom(roomId)
            val stateDataBefore = roomEdits.effectiveStateDataChange
            val fxBefore = roomEdits.fxChange
            applyBulkBiomeThemeToRoom(roomEdits, romRoom, effectiveRoom, theme)
            val stateDataAfter = roomEdits.effectiveStateDataChange
            val fxAfter = roomEdits.fxChange
            val hasGeneratedChanges = edits.isNotEmpty() ||
                scrollEdits.isNotEmpty() ||
                scrollPlmRemoves.isNotEmpty() ||
                stateDataBefore != stateDataAfter ||
                fxBefore != fxAfter ||
                stripped
            if (!hasGeneratedChanges) {
                if (!roomEdits.hasEdits) project.rooms.remove(project.roomKey(roomId))
                continue
            }
            recordGeneratedBiomeOperation(
                roomEdits,
                roomId,
                rules,
                seed,
                edits,
                scrollEdits,
                scrollPlmRemoves,
                stateDataBefore,
                stateDataAfter,
                fxBefore,
                fxAfter,
            )
            if (!roomEdits.hasEdits) project.rooms.remove(project.roomKey(roomId))
            generated++
            changedTiles += edits.size
        }

        val activeRoom = if (currentRoomId != 0) romParser.readRoomHeader(currentRoomId) else null
        if (activeRoom != null) loadRoom(currentRoomId, romParser, activeRoom)
        dirty = true
        editVersion++
        editorLog(
            "Generated biome for $generated rooms ($changedTiles tile edits), skipped $skipped rooms" +
                if (manualSkipped > 0) " ($manualSkipped with manual edits)" else ""
        )
        return BulkBiomeResult(generated, skipped, changedTiles, manualSkipped)
    }

    fun resetGeneratedBiomeRooms(romParser: RomParser): BulkBiomeResult {
        var resetRooms = 0
        var sourceSkipped = 0
        var removedTiles = 0
        val keys = project.rooms.keys.toList()
        for (key in keys) {
            val roomEdits = project.rooms[key] ?: continue
            val oldTileCount = (
                roomEdits.operations.asSequence() +
                    roomEdits.states.asSequence().flatMap { it.operations.asSequence() } +
                    roomEdits.levelResourceOperations.values.asSequence().flatMap { it.asSequence() }
                )
                .filter { isGeneratedBiomeOperation(it) }
                .sumOf { it.edits.size }
            if (roomEdits.asmPlmSourceVersion != 0 || roomEdits.asmInitialScrollSourceVersion != 0 ||
                roomEdits.asmFxSourceVersion != 0 || roomEdits.asmEnemySourceVersion != 0 || roomEdits.asmLayoutSourceVersion != 0 || roomEdits.asmRoomPropertiesSourceVersion != 0 || roomEdits.asmStateSelectorsSourceVersion != 0 || roomEdits.asmDoorSourceVersion != 0) {
                if (oldTileCount > 0) sourceSkipped++
                continue
            }
            if (!stripGeneratedBiomeEdits(roomEdits)) continue
            removedTiles += oldTileCount
            resetRooms++
            if (!roomEdits.hasEdits) project.rooms.remove(key)
        }
        val activeRoom = if (currentRoomId != 0) romParser.readRoomHeader(currentRoomId) else null
        if (activeRoom != null) loadRoom(currentRoomId, romParser, activeRoom)
        if (resetRooms > 0) {
            dirty = true
            editVersion++
        }
        editorLog("Reset generated biome edits in $resetRooms rooms ($removedTiles tile edits removed)")
        if (sourceSkipped > 0) postStatus("Skipped $sourceSkipped source-owned room(s). Use Undo in ASM to reverse their complete generated-room edits.")
        return BulkBiomeResult(resetRooms, sourceSkipped, removedTiles, manualSkippedRooms = sourceSkipped)
    }

    internal fun buildEffectiveRoomGrids(romParser: RomParser, romRoom: Room, effectiveRoom: Room): RoomGrids? {
        val data = runCatching { romParser.decompressLZ2(romRoom.levelDataPtr) }.getOrNull() ?: return null
        val effectiveData = if (effectiveRoom.width != romRoom.width || effectiveRoom.height != romRoom.height) {
            resizeLevelData(data, romRoom.width, romRoom.height, effectiveRoom.width, effectiveRoom.height)
        } else {
            data.copyOf()
        }
        val width = effectiveRoom.width * 16
        val height = effectiveRoom.height * 16
        val grid = LevelGrid.parse(effectiveData, width, height) ?: return null
        val words = IntArray(width * height)
        val bts = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                words[i] = grid.word(x, y)
                bts[i] = grid.bts(x, y)
            }
        }
        val roomEdits = project.rooms[project.roomKey(romRoom.roomId)]
        if (roomEdits != null) {
            val previewStateIndex = romParser.preferredPreviewStateIndex(romRoom.roomId)
            val previewState = roomEdits.stateEditsForSourceIndex(previewStateIndex)
            val effectiveOperations = roomEdits.operations +
                previewState?.let { roomEdits.levelResourceOperations[it.resources.level] }.orEmpty() +
                previewState?.operations.orEmpty()
            for (op in effectiveOperations) {
                for (edit in op.edits) {
                    if (edit.layer != TILE_EDIT_LAYER_1) continue
                    if (edit.blockX !in 0 until width || edit.blockY !in 0 until height) continue
                    val i = edit.blockY * width + edit.blockX
                    words[i] = edit.newBlockWord
                    bts[i] = edit.newBts
                }
            }
        }
        return RoomGrids(width, height, words, bts)
    }

    /** Effective level data for door picking/validation, including live unsaved edits. */
    internal fun effectiveRoomGridsForRoom(roomId: Int, romParser: RomParser): RoomGrids? {
        if (roomId == currentRoomId && workingLevelData != null) {
            val data = requireNotNull(workingLevelData)
            val width = workingBlocksWide
            val height = workingBlocksTall
            val grid = LevelGrid.parse(data, width, height) ?: return null
            return RoomGrids(
                width = width,
                height = height,
                words = IntArray(width * height) { index -> grid.word(index % width, index / width) },
                bts = IntArray(width * height) { index -> grid.bts(index % width, index / width) },
            )
        }
        val romRoom = romParser.readRoomHeader(roomId) ?: return null
        return buildEffectiveRoomGrids(romParser, romRoom, applyHeaderChanges(romRoom))
    }

    internal fun effectiveDoorwayOpenings(
        roomId: Int,
        direction: Int,
        romParser: RomParser,
    ): List<DoorwayOpening> = effectiveRoomGridsForRoom(roomId, romParser)
        ?.let { doorwayOpenings(roomId, it, direction) }
        .orEmpty()

    internal fun deriveEffectiveDoorCapPosition(
        romParser: RomParser,
        roomId: Int,
        direction: Int,
        screenX: Int,
        screenY: Int,
    ): Int? = effectiveDoorwayOpenings(roomId, direction, romParser)
        .firstOrNull { it.screenX == screenX && it.screenY == screenY }
        ?.doorCapCode

    private fun recordGeneratedBiomeOperation(
        roomEdits: RoomEdits,
        roomId: Int,
        rules: BiomeRules,
        seed: Long,
        edits: List<TileEdit>,
        scrollEdits: List<ScrollChange>,
        scrollPlmRemoves: List<PlmChange>,
        stateDataBefore: StateDataChange?,
        stateDataAfter: StateDataChange?,
        fxBefore: FxChange?,
        fxAfter: FxChange?,
    ) {
        val op = EditOperation(
            "$GENERATED_BIOME_PREFIX ${rules.style.displayName}, seed $seed",
            edits,
            plmRemoves = scrollPlmRemoves,
            scrollEdits = scrollEdits,
            stateDataBefore = stateDataBefore,
            stateDataAfter = stateDataAfter,
            fxBefore = fxBefore,
            fxAfter = fxAfter,
        )
        if (
            edits.isNotEmpty() ||
            scrollEdits.isNotEmpty() ||
            scrollPlmRemoves.isNotEmpty() ||
            stateDataBefore != stateDataAfter ||
            fxBefore != fxAfter
        ) {
            roomEdits.operations.add(op)
        }
        for (sc in scrollEdits) {
            roomEdits.scrollChanges.removeAll { it.screenX == sc.screenX && it.screenY == sc.screenY }
            if (sc.newValue != sc.oldValue) roomEdits.scrollChanges.add(sc)
        }
        for (plm in scrollPlmRemoves) {
            roomEdits.plmChanges.removeAll {
                it.action == plm.action && it.plmId == plm.plmId && it.x == plm.x && it.y == plm.y && it.param == plm.param
            }
            roomEdits.plmChanges.add(plm)
        }
        _roomEditOrder[roomId] = ++_editCounter
    }

    private fun prepareBulkTheme(theme: BiomeTheme, romParser: RomParser) {
        val targetTileset = theme.tilesetId ?: return
        val effectId = theme.paletteEffectId ?: return
        val effect = PaletteEffects.findEffect(effectId) ?: return
        val colors = readTilesetPalette(targetTileset, romParser, includeOverride = false) ?: return
        effect.apply(colors)
        saveTilesetPaletteFromColors(targetTileset, colors)
        setPaletteEffect("tileset:$targetTileset", effect.id)
    }

    private fun buildBiomeGenerationOptionsForRoom(
        roomId: Int,
        width: Int,
        height: Int,
        originalWords: IntArray,
        originalBts: IntArray,
        romParser: RomParser?,
    ): BiomeGenerationOptions {
        val preserveRects = ArrayList<BiomeGenerationRect>()
        val forceAirRects = ArrayList<BiomeGenerationRect>()
        val hardForceAirRects = ArrayList<BiomeGenerationRect>()
        if (roomId == 0x91F8) {
            addLandingSiteShipProtection(preserveRects, forceAirRects)
        }
        addElevatorProtectionForRoom(preserveRects, hardForceAirRects, romParser, roomId, width, height)
        val plms = effectivePlmsForBiomeRoom(roomId, romParser)
        val enemies = if (roomId == currentRoomId && workingLevelData != null) {
            _workingEnemies.toList()
        } else {
            romParser?.readRoomHeader(roomId)?.let { romParser.parseEnemyPopulation(it.enemySetPtr) }.orEmpty()
        }
        addImportantPlmProtection(preserveRects, hardForceAirRects, width, height, plms)
        preserveRects.addAll(buildDoorCapPreserveRectsForRoom(romParser, roomId, width, height, plms))
        return BiomeGenerationOptions(
            preserveRects = preserveRects,
            forceAirRects = forceAirRects,
            hardForceAirRects = hardForceAirRects,
            protectedCells = BiomeSafetyMask.protectNonPlainMetadata(
                width = width,
                height = height,
                originalWords = originalWords,
                originalBts = originalBts,
                plms = plms,
                enemies = enemies,
            ),
        )
    }

    private fun buildGeneratedRoomScrollResetEditsForRoom(
        romParser: RomParser,
        roomId: Int,
        romRoom: Room,
        effectiveRoom: Room,
    ): List<ScrollChange> {
        val roomWidthScreens = effectiveRoom.width
        val roomHeightScreens = effectiveRoom.height
        if (roomWidthScreens <= 0 || roomHeightScreens <= 0) return emptyList()
        val romScrolls = romParser.parseScrollData(romRoom.roomScrollsPtr, romRoom.width, romRoom.height)
        val original = if (effectiveRoom.width != romRoom.width || effectiveRoom.height != romRoom.height) {
            resizeScrollGrid(romScrolls, romRoom.width, romRoom.height, roomWidthScreens, roomHeightScreens)
        } else {
            romScrolls
        }
        val current = original.copyOf()
        project.rooms[project.roomKey(roomId)]?.scrollChanges.orEmpty().forEach { sc ->
            val idx = sc.screenY * roomWidthScreens + sc.screenX
            if (idx in current.indices) current[idx] = sc.newValue
        }
        val edits = ArrayList<ScrollChange>()
        for (screenY in 0 until roomHeightScreens) {
            for (screenX in 0 until roomWidthScreens) {
                val idx = screenY * roomWidthScreens + screenX
                val target = if (screenY == roomHeightScreens - 1) 0x02 else 0x01
                val old = current.getOrElse(idx) { original.getOrElse(idx) { 1 } }
                if (old != target) {
                    val originalValue = original.getOrElse(idx) { old }
                    edits.add(ScrollChange(screenX, screenY, originalValue, target))
                }
            }
        }
        return edits
    }

    private fun buildScrollPlmRemovalsForRoom(romParser: RomParser, roomId: Int): List<PlmChange> =
        effectivePlmsForBiomeRoom(roomId, romParser)
            .filter { RomParser.isScrollPlm(it.id) }
            .map { PlmChange("remove", it.id, it.x, it.y, it.param) }

    private fun effectivePlmsForBiomeRoom(roomId: Int, romParser: RomParser?): List<RomParser.PlmEntry> {
        romParser ?: return emptyList()
        if (roomId == currentRoomId && workingLevelData != null) return _workingPlms.toList()
        val plms = romParser.getAllPlmEntriesForRoom(roomId).toMutableList()
        val roomEdits = project.rooms[project.roomKey(roomId)] ?: return plms
        for (change in roomEdits.plmChanges) {
            when (change.action) {
                "add" -> plms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                "remove" -> plms.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y && it.param == change.param }
            }
        }
        return plms
    }

    private data class BiomeGenerationStateContents(
        val plms: List<RomParser.PlmEntry>,
        val enemies: List<RomParser.EnemyEntry>,
    )

    /** Protect every branch using this layout, without merging its state-owned resources. */
    private fun biomeGenerationStateContents(): Map<String, BiomeGenerationStateContents> {
        if (project.asmWorkspace.buildMode != ProjectRomBuildMode.ASM_SOURCE && !hasManagedPlms() && !hasManagedInitialScrolls()) {
            return mapOf("This state" to BiomeGenerationStateContents(_workingPlms.toList(), _workingEnemies.toList()))
        }
        val parser = requireNotNull(currentRomParser)
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        val room = project.getOrCreateRoom(currentRoomId)
        val selected = requireNotNull(currentStateEdits())
        val lists = effectiveAuthoredStatePlms()
        require(lists.size == room.states.size) { "Room object state coverage is incomplete." }
        val objects = room.states.mapIndexed { index, state -> state.id to lists[index].second }.toMap()
        require(objects.getValue(selected.id) == _workingPlms) { "Room objects changed; wait for the source preview, then generate again." }
        val nativeStates = parser.parseRoomStatesWithData(currentRoomId)
        return room.states.filter { it.resources.level == selected.resources.level }.associate { state ->
            require(effectiveLevelDataForState(room, state, parser)?.contentEquals(workingLevelData) == true) {
                "States sharing this layout have different private geometry. Make the layout unique to this state before generating."
            }
            val actors = if (hasManagedEnemies()) {
                val view = bridge.enemies.view(currentRoomId, state.id)
                require(view.problem == null) { view.problem ?: "Invalid actor source." }
                requireNotNull(view.resource).population.entries
            } else {
                val native = requireNotNull(nativeStates.getOrNull(state.baseSourceStateIndex() ?: -1))
                com.supermetroid.editor.rom.applyEnemyChanges(parser.parseEnemyPopulation(native.enemySetPtr), room.enemyChanges + state.enemyChanges)
            }
            if (state.id == selected.id) require(actors == _workingEnemies) {
                "Actors changed; wait for the source preview, then generate again."
            }
            state.id to BiomeGenerationStateContents(objects.getValue(state.id).toList(), actors.toList())
        }
    }

    private fun validateBiomeGenerationStateContents(prepared: Map<String, BiomeGenerationStateContents>) {
        // Choosing Make unique may narrow the target set while the scope prompt is open.
        // Its already-generated maze is conservative; changed/new placements need a new run.
        require(biomeGenerationStateContents().all { (id, contents) -> prepared[id] == contents }) {
            "Room objects or actors changed since generation was prepared. Generate again."
        }
    }

    private fun buildBiomeGenerationOptions(
        keepLandingSiteShipClear: Boolean,
        romParser: RomParser?,
        originalWords: IntArray,
        originalBts: IntArray,
        stateContents: Map<String, BiomeGenerationStateContents> = biomeGenerationStateContents(),
    ): BiomeGenerationOptions {
        val plms = stateContents.values.flatMap { it.plms }.distinct()
        val enemies = stateContents.values.flatMap { it.enemies }.distinct()
        val preserveRects = ArrayList<BiomeGenerationRect>()
        val forceAirRects = ArrayList<BiomeGenerationRect>()
        val hardForceAirRects = ArrayList<BiomeGenerationRect>()
        if (keepLandingSiteShipClear && currentRoomId == 0x91F8) {
            addLandingSiteShipProtection(preserveRects, forceAirRects)
        }
        addElevatorProtectionForRoom(
            preserveRects,
            hardForceAirRects,
            romParser,
            currentRoomId,
            workingBlocksWide,
            workingBlocksTall,
        )
        addImportantPlmProtection(preserveRects, hardForceAirRects, workingBlocksWide, workingBlocksTall, plms)
        preserveRects.addAll(
            buildDoorCapPreserveRectsForRoom(
                romParser,
                currentRoomId,
                workingBlocksWide,
                workingBlocksTall,
                plms,
            )
        )
        return BiomeGenerationOptions(
            preserveRects = preserveRects,
            forceAirRects = forceAirRects,
            hardForceAirRects = hardForceAirRects,
            protectedCells = BiomeSafetyMask.protectNonPlainMetadata(
                width = workingBlocksWide,
                height = workingBlocksTall,
                originalWords = originalWords,
                originalBts = originalBts,
                plms = plms,
                enemies = enemies,
            ),
        )
    }

    private fun addImportantPlmProtection(
        preserveRects: MutableList<BiomeGenerationRect>,
        hardForceAirRects: MutableList<BiomeGenerationRect>,
        width: Int,
        height: Int,
        plms: List<RomParser.PlmEntry>,
    ) {
        if (width <= 0 || height <= 0) return
        fun addRect(list: MutableList<BiomeGenerationRect>, rect: BiomeGenerationRect) {
            if (rect.x1 >= 0 && rect.y1 >= 0 && rect.x0 < width && rect.y0 < height) {
                list.add(rect)
            }
        }
        for (plm in plms) {
            if (!isBiomeAnchorPlm(plm.id)) continue
            val x = plm.x
            val y = plm.y
            if (x !in 0 until width || y !in 0 until height) continue
            addRect(preserveRects, BiomeGenerationRect(x - 1, y - 1, x + 1, y + 1))
            addRect(hardForceAirRects, BiomeGenerationRect(x - 3, y - 4, x + 3, y + 2))
        }
    }

    private fun isBiomeAnchorPlm(plmId: Int): Boolean =
        !RomParser.isScrollPlm(plmId) &&
            !RomParser.isDoorCapPlm(plmId) &&
            (isEditorItemPlm(plmId) || RomParser.isStationPlm(plmId) || RomParser.isGatePlm(plmId))

    private fun buildGeneratedRoomScrollResetEdits(): List<ScrollChange> {
        val roomWidthScreens = workingBlocksWide / 16
        val roomHeightScreens = workingBlocksTall / 16
        if (roomWidthScreens <= 0 || roomHeightScreens <= 0) return emptyList()
        val edits = ArrayList<ScrollChange>()
        for (screenY in 0 until roomHeightScreens) {
            for (screenX in 0 until roomWidthScreens) {
                val idx = screenY * roomWidthScreens + screenX
                if (idx !in _workingScrolls.indices) continue
                val target = if (screenY == roomHeightScreens - 1) 0x02 else 0x01
                val old = _workingScrolls[idx]
                if (old != target) edits.add(ScrollChange(screenX, screenY, old, target))
            }
        }
        return edits
    }

    private fun buildScrollPlmRemovals(): List<PlmChange> =
        _workingPlms
            .filter { RomParser.isScrollPlm(it.id) }
            .map { PlmChange("remove", it.id, it.x, it.y, it.param) }

    internal fun applyGeneratedRoomOperation(
        description: String,
        edits: List<TileEdit>,
        scrollEdits: List<ScrollChange>,
        scrollPlmRemoves: List<PlmChange>,
        theme: BiomeTheme? = null,
        validatePreparedInputs: (() -> Unit)? = null,
    ): Boolean {
        if (edits.isEmpty() && scrollEdits.isEmpty() && scrollPlmRemoves.isEmpty() &&
            !usesRoomAsmAuthoring && !hasManagedPlms() && !hasManagedInitialScrolls()) return false
        val roomId = currentRoomId
        val stateId = currentPlmStateId()
        if (!allowOrQueueLayoutEdit {
                if (currentRoomId == roomId && currentPlmStateId() == stateId) {
                    applyGeneratedRoomOperation(description, edits, scrollEdits, scrollPlmRemoves, theme, validatePreparedInputs)
                } else postStatus("Generation cancelled: the selected room/state changed.")
            }
        ) return false
        if (runCatching { validatePreparedInputs?.invoke() }.onFailure {
                postStatus("Generation blocked: ${it.message ?: "Room inputs changed. Generate again."}")
            }.isFailure) return false
        if (usesRoomAsmAuthoring || hasManagedPlms() || hasManagedInitialScrolls()) {
            return applySourceGeneratedRoomOperation(description, edits, scrollEdits, scrollPlmRemoves, theme)
        }
        if (scrollPlmRemoves.isNotEmpty() && blockLinkedPlmMutation()) return false
        // Generation edits the active whole layout resource. State-owned PLMs
        // and scroll metadata below remain separate even when tiles are shared.

        for (e in edits) {
            applyTileEdit(e, useNew = true)
        }

        val scrollChanges = currentScrollChanges()
        val plmChanges = currentPlmChanges()
        val roomWidthScreens = workingBlocksWide / 16
        for (sc in scrollEdits) {
            val idx = sc.screenY * roomWidthScreens + sc.screenX
            if (idx !in _workingScrolls.indices) continue
            _workingScrolls[idx] = sc.newValue
            scrollChanges.removeAll { it.screenX == sc.screenX && it.screenY == sc.screenY }
            val original = _originalScrolls.getOrElse(idx) { sc.oldValue }
            if (sc.newValue != original) {
                scrollChanges.add(ScrollChange(sc.screenX, sc.screenY, original, sc.newValue))
            }
        }
        if (scrollEdits.isNotEmpty()) scrollVersion++

        for (plm in scrollPlmRemoves) {
            _workingPlms.removeAll { it.id == plm.plmId && it.x == plm.x && it.y == plm.y && it.param == plm.param }
            plmChanges.add(plm)
        }

        pushEditOperation(
            EditOperation(
                description,
                edits,
                plmRemoves = scrollPlmRemoves,
                scrollEdits = scrollEdits,
            )
        )
        return true
    }

    private fun applySourceGeneratedRoomOperation(description: String, edits: List<TileEdit>,
                                                 scrollEdits: List<ScrollChange>, removals: List<PlmChange>, theme: BiomeTheme? = null): Boolean = runCatching {
        require(pendingEdits.isEmpty() && pendingSourcePattern == null) { "Finish the active tile stroke before generating." }
        val parser = requireNotNull(currentRomParser)
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        val room = project.getOrCreateRoom(currentRoomId)
        val selected = requireNotNull(currentStateEdits())
        val targets = room.states.filter { it.resources.level == selected.resources.level }
        require(edits.map { it.blockX to it.blockY }.distinct().size == edits.size && edits.all {
            it.layer == TILE_EDIT_LAYER_1 && it.blockX in 0 until workingBlocksWide && it.blockY in 0 until workingBlocksTall &&
                it.newBlockWord in 0..65535 && it.newBts in 0..255 && readBlockWord(it.blockX, it.blockY) == it.oldBlockWord && readBts(it.blockX, it.blockY) == it.oldBts
        }) { "The layout changed since generation was prepared. Generate again." }
        val width = workingBlocksWide / 16
        val height = workingBlocksTall / 16
        require(scrollEdits.all { it.screenX in 0 until width && it.screenY in 0 until height &&
            _workingScrolls.getOrNull(it.screenY * width + it.screenX) == it.oldValue &&
            it.newValue == if (it.screenY == height - 1) 2 else 1 }) { "The initial scroll grid changed since generation was prepared." }
        require(removals == buildScrollPlmRemovals()) { "Room objects changed since generation was prepared." }
        val lists = effectiveAuthoredStatePlms()
        require(lists.size == room.states.size) { "Room object state coverage is incomplete." }
        val objectsBefore = room.states.mapIndexed { index, state -> state.id to lists[index].second }.toMap()
        require(objectsBefore.getValue(selected.id) == _workingPlms) { "Room objects changed; wait for the source preview, then generate again." }
        biomeGenerationStateContents()
        val projected = effectiveLevelDataForState(room, selected, parser, prospectiveLayoutEdits = edits)
        val expected = requireNotNull(workingLevelData).copyOf()
        val layerBytes = (expected[0].toInt() and 255) or ((expected[1].toInt() and 255) shl 8)
        edits.forEach { edit ->
            val index = edit.blockY * workingBlocksWide + edit.blockX
            expected[2 + index * 2] = edit.newBlockWord.toByte()
            expected[3 + index * 2] = (edit.newBlockWord ushr 8).toByte()
            expected[2 + layerBytes + index] = edit.newBts.toByte()
        }
        require(projected?.contentEquals(expected) == true) { "Older state-specific overrides conflict with the generated layout. Make its layout independent first." }
        val plan = bridge.planGeneration(currentRoomId, parser.readRoomHeader(currentRoomId)?.name ?: "Room",
            targets.map { it.id }.toSet(), ::isEditorItemPlm, edits)
        require(plan.gridsBefore.getValue(selected.id) == _workingScrolls.toList()) { "Initial scrolls changed; wait for the source preview, then generate again." }
        if (theme != null && (theme.tilesetId != null || theme.paletteEffectId != null || theme.fxType != null)) {
            return@runCatching applySourceRoomTheme(theme, parser, targets.map { it.id }.toSet(), plan, edits, description)
        }
        val operation = EditOperation(description, edits)
        val layout = prepareSourceLayoutEdit(operation)
        fun preview(undo: Boolean) {
            if (currentRoomId != room.roomId) return
            val id = currentPlmStateId() ?: return
            _workingPlms.clear(); _workingPlms.addAll((if (undo) objectsBefore else plan.objectsAfter).getValue(id))
            _workingScrolls = (if (undo) plan.gridsBefore else plan.gridsAfter).getValue(id).toIntArray()
            scrollVersion++
        }
        val changed = bridge.applyGeneration(plan,
            beforeUndo = { layout.undo(); preview(true) }, beforeRedo = { layout.redo(); preview(false) })
        if (changed) {
            layout.publish()
            recordManagedRoomOperation(operation, plan.before, bridge)
        } else {
            edits.forEach { applyTileEdit(it, useNew = true) }
            if (edits.isNotEmpty()) pushEditOperation(operation)
        }
        preview(false)
        refreshLinkedPlms()
        val applied = changed || edits.isNotEmpty()
        if (applied) postStatus("Generated layout and camera data for ${targets.size} state(s).")
        applied
    }.getOrElse { postStatus("Generation blocked: ${it.message ?: "No changes applied."}"); false }

    fun resetCurrentRoomToOriginal(romParser: RomParser): Boolean {
        if (usesRoomAsmAuthoring || hasManagedDoors() || hasManagedSelectors() || hasManagedPlms() || hasManagedInitialScrolls() || hasManagedFx() || hasManagedEnemies() || hasManagedLayouts() || hasManagedRoomProperties())
            return resetSourceRoom(romParser)
        val roomId = currentRoomId
        if (roomId == 0) return false
        val room = romParser.readRoomHeader(roomId) ?: return false
        val roomKey = project.roomKey(roomId)
        val removedEdits = project.rooms.remove(roomKey) != null

        romTilesetId = room.tileset
        currentTilesetId = room.tileset
        currentStateIndex = -1
        currentStateId = null

        val tg = TileGraphics(romParser)
        tileGraphics = if (tg.loadTileset(currentTilesetId)) {
            applyCustomGfxToTileGraphics(tg, currentTilesetId)
            tg
        } else {
            null
        }

        val levelData = romParser.decompressLZ2(room.levelDataPtr)
        originalLevelData = levelData.copyOf()
        workingLevelData = levelData.copyOf()
        workingBlocksWide = room.width * 16
        workingBlocksTall = room.height * 16

        _originalScrolls = romParser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
        _workingScrolls = _originalScrolls.copyOf()

        _workingPlms.clear()
        val plms = romParser.getAllPlmEntriesForRoom(roomId)
        _workingPlms.addAll(plms)
        originalPlmCount = plms.size

        doorEntries = romParser.parseDoorList(room.doorOut)

        _workingEnemies.clear()
        _workingEnemies.addAll(romParser.parseEnemyPopulation(room.enemySetPtr))

        mapSelStart = null
        mapSelEnd = null
        floatingSelection = null
        pendingEdits.clear()
        pendingPositions.clear()
        pendingPlmAdds.clear()
        pendingPlmRemoves.clear()
        undoStack.clear()
        managedPlmUndo.clear()
        redoStack.clear()

        undoVersion++
        scrollVersion++
        _editVersionState.value++
        _roomEditOrder.remove(roomId)
        if (removedEdits) dirty = true
        editorLog("Reset room 0x$roomKey to original ROM state")
        return true
    }

    /** An open map can preflight pending gestures and join a structural source transaction's history. */
    internal var prepareSourceMapTransaction: ((RomParser) -> ((com.supermetroid.editor.asm.AsmRoomAreaMoveHistory) -> Unit))? = null

    /** Reset this room and its relocation dependencies, never global assets or unrelated edits. */
    private fun resetSourceRoom(parser: RomParser): Boolean = runCatching {
        val roomId = currentRoomId
        val native = requireNotNull(parser.readRoomHeader(roomId)) { "Load an existing room before resetting." }
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        require(bridge.context.parser === parser) { "The room ROM changed; wait for the source workspace." }
        fun requireIdle() {
            require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null &&
                pendingPlmAdds.isEmpty() && pendingPlmRemoves.isEmpty() && pendingLayoutEditScope == null) {
                "Finish the active tile stroke, selection or scope choice before resetting or restoring the room."
            }
        }
        requireIdle()
        refreshRoomMapHeaders(parser)
        bridge.doors.refreshAll()
        val room = project.rooms[project.roomKey(roomId)]
        if (room == null || !room.hasEdits) {
            postStatus("No room-editor changes to reset. Handwritten ASM is unchanged.")
            return@runCatching true
        }
        val incoming = resizeIncomingDoors(roomId, parser).filter { it.first != roomId }
        val oldWidth = room.effectiveHeaderChange?.width ?: native.width
        val oldHeight = room.effectiveHeaderChange?.height ?: native.height
        require(incoming.none { (_, door) ->
            door.screenX in 0 until oldWidth && door.screenY in 0 until oldHeight &&
                (door.screenX >= native.width || door.screenY >= native.height)
        }) { "An incoming door leads to a screen removed by reset. Move its destination before resetting." }
        val plan = bridge.planReset(roomId) ?: run {
            postStatus("Room is already reset. Required connection flags for moved neighbors are unchanged.")
            return@runCatching true
        }
        val recordMapTransaction = prepareSourceMapTransaction?.invoke(parser)
        val selectedBefore = currentPlmStateId()
        val previousUndo = undoStack.toList()
        val previousManaged = java.util.IdentityHashMap(managedPlmUndo)
        val previousItemScopes = java.util.IdentityHashMap(itemScopeSnapshots)
        val operation = EditOperation("Reset room to original")
        var sourceChange: AsmSourceTextChange? = null
        val history = bridge.applyReset(plan, beforeTransition = {
            requireIdle()
            require(asmPlmSourceBridge === bridge && currentRomParser === parser) {
                "The room ROM or ASM link changed; reopen the current workspace before restoring this reset."
            }
            fun geometry(doors: List<Pair<Int, DoorChange>>) = doors.map { (id, door) -> id to door.copy(bitflag = door.bitflag and 0x40.inv()) }
            require(geometry(resizeIncomingDoors(roomId, parser).filter { it.first != roomId }) == geometry(incoming)) {
                "Incoming doors changed; undo newer door edits before restoring this reset."
            }
        }, afterTransition = { undo ->
            bridge.properties.refreshAll(); bridge.doors.refreshAll()
            if (currentRoomId == roomId) {
                // Source and ownership are already committed, so all four lenses see one state.
                loadRoom(roomId, parser, native)
                if (undo && selectedBefore != null && plan.room.states.any { it.id == selectedBefore })
                    switchRoomState(selectedBefore, parser)
                // Reload replays persisted operations into GUI history; replace that projection
                // with the exact session history, including its source transaction identities.
                undoStack.clear(); redoStack.clear(); managedPlmUndo.clear(); itemScopeSnapshots.clear()
                if (sourceChange == null) sourceChange = requireNotNull(bridge.sourceUndoHead())
                managedPlmUndo.putAll(previousManaged)
                managedPlmUndo[operation] = ManagedSourceUndo(PLM_SOURCE_FILE,
                    plan.replacements.getValue(PLM_SOURCE_FILE).first,
                    plan.replacements.getValue(PLM_SOURCE_FILE).second, sourceChange)
                itemScopeSnapshots.putAll(previousItemScopes)
                if (undo) { undoStack.addAll(previousUndo); redoStack.add(operation) }
                else undoStack.add(operation)
            }
            dirty = true; undoVersion++; editVersion++
        })
        recordMapTransaction?.invoke(history)
        _roomEditOrder.remove(roomId)
        val dependencies = if (plan.relocation != null) " Map, save and connection dependencies were restored together." else ""
        val neighbors = if (plan.restored.asmDoorSourceVersion != 0) " Required area flags remain for moved neighbors." else ""
        postStatus("Reset room to original ROM state.$dependencies$neighbors Undo restores its authored source and edits.")
        true
    }.getOrElse { postStatus("Reset blocked: ${it.message ?: "No changes applied."}"); false }

    /**
     * Apply a [BiomeTheme] to the loaded room: switch its tileset (persisted
     * as a state-data change for ROM export), install the theme's palette
     * recolor as a tileset palette override, and set liquid/atmosphere FX.
     * Reloads the room's TileGraphics so the canvas shows the new look
     * immediately. For generation, pass the theme to [generateBiome] so source,
     * geometry and visual metadata are committed in one transaction.
     */
    fun applyBiomeTheme(theme: BiomeTheme, romParser: RomParser): Boolean {
        if (theme.tilesetId == null && theme.paletteEffectId == null && theme.fxType == null) return false
        if (usesRoomAsmAuthoring || hasManagedPlms() || hasManagedInitialScrolls() || hasManagedFx() || hasManagedEnemies() || hasManagedLayouts() || hasManagedRoomProperties()) {
            return applySourceRoomTheme(theme, romParser)
        }
        val targetTileset = theme.tilesetId ?: currentTilesetId
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        val stateEdits = currentStateEdits()

        // Recolor from the vanilla palette so re-applying a theme is stable.
        val effectId = theme.paletteEffectId
        if (effectId != null) {
            val effect = PaletteEffects.findEffect(effectId)
            val colors = readTilesetPalette(targetTileset, romParser, includeOverride = false)
            if (effect != null && colors != null) {
                effect.apply(colors)
                saveTilesetPaletteFromColors(targetTileset, colors)
                setPaletteEffect("tileset:$targetTileset", effect.id)
            }
        }

        if (theme.tilesetId != null) {
            val existing = stateEdits?.effectiveStateDataChange ?: roomEdits.effectiveStateDataChange ?: StateDataChange()
            val change = existing.copy(tileset = targetTileset.takeIf { it != romTilesetId })
            if (stateEdits != null) {
                stateEdits.stateDataChange = change.takeIf { it != StateDataChange() }
            } else {
                roomEdits.stateDataChange = change.takeIf { it != StateDataChange() }
            }
            currentTilesetId = targetTileset
        }
        val tg = TileGraphics(romParser)
        if (tg.loadTileset(currentTilesetId)) {
            applyCustomGfxToTileGraphics(tg, currentTilesetId)
            tileGraphics = tg
        }

        if (theme.fxType != null) {
            val existing = stateEdits?.fxChange ?: roomEdits.fxChange ?: FxChange()
            val change = if (theme.isLiquid) {
                val heightPx = workingBlocksTall * 16
                val surface = (heightPx * theme.liquidFraction).toInt()
                    .coerceIn(0x20, maxOf(0x20, heightPx - 0x20))
                existing.copy(
                    fxType = theme.fxType,
                    liquidSurfaceStart = surface, liquidSurfaceNew = surface,
                    liquidSpeed = 0, liquidDelay = 0,
                    fxBitA = 0x02, fxBitB = 0x02, fxBitC = 0,
                )
            } else {
                existing.copy(fxType = theme.fxType, liquidSurfaceStart = 0xFFFF, liquidSurfaceNew = 0xFFFF)
            }
            if (stateEdits != null) stateEdits.fxChange = change else roomEdits.fxChange = change
        }

        dirty = true
        editVersion++
        return true
    }

    /** Theme values and optional generated geometry cross one source/model history boundary. */
    private fun applySourceRoomTheme(
        theme: BiomeTheme,
        parser: RomParser,
        targets: Set<String>? = null,
        generation: com.supermetroid.editor.asm.AsmRoomGenerationPlan? = null,
        edits: List<TileEdit> = emptyList(),
        description: String = "Apply theme (${theme.displayName})",
    ): Boolean = runCatching {
        val bridge = requireNotNull(asmPlmSourceBridge) { "Wait for the project ASM workspace." }
        require(bridge.context.parser === parser) { "The room ROM changed; wait for the source workspace." }
        fun requireIdle() {
            require(pendingEdits.isEmpty() && pendingSourcePattern == null && floatingSelection == null &&
                pendingPlmAdds.isEmpty() && pendingPlmRemoves.isEmpty() && pendingLayoutEditScope == null) {
                "Finish the active tile stroke, selection or scope choice before applying or restoring a theme."
            }
        }
        requireIdle()
        val selected = requireNotNull(currentStateEdits()) { "Select an existing room state." }
        val roomId = currentRoomId
        val native = requireNotNull(parser.readRoomHeader(roomId))
        val targetTileset = theme.tilesetId ?: currentTilesetId
        require(targetTileset in 0..28) { "Unsupported theme tileset." }
        val paletteKey = targetTileset.toString()
        val effectKey = "tileset:$targetTileset"
        val paletteBefore = project.customGfx.palettes[paletteKey]
        val effectBefore = project.customGfx.paletteEffects[effectKey]
        var paletteAfter = paletteBefore
        var effectAfter = effectBefore
        val paletteEffectId = theme.paletteEffectId
        if (paletteEffectId != null) {
            val effect = requireNotNull(PaletteEffects.findEffect(paletteEffectId)) { "Unknown theme palette effect." }
            val colors = requireNotNull(readTilesetPalette(targetTileset, parser, includeOverride = false)) { "The theme's native palette is unavailable." }
            effect.apply(colors)
            val bytes = ByteArray(256)
            for (i in colors.indices) { bytes[i * 2] = colors[i].toByte(); bytes[i * 2 + 1] = (colors[i] ushr 8).toByte() }
            paletteAfter = Base64.getEncoder().encodeToString(bytes)
            effectAfter = paletteEffectId
        }
        val change = theme.fxType?.let { type ->
            require(!theme.isLiquid || theme.liquidFraction.isFinite() && theme.liquidFraction in 0.0..1.0) { "Invalid theme liquid height." }
            if (theme.isLiquid) {
                val heightPx = workingBlocksTall * 16
                val surface = (heightPx * theme.liquidFraction).toInt().coerceIn(0x20, maxOf(0x20, heightPx - 0x20))
                FxChange(fxType = type, liquidSurfaceStart = surface, liquidSurfaceNew = surface, liquidSpeed = 0, liquidDelay = 0,
                    fxBitA = 2, fxBitB = 2, fxBitC = 0)
            } else FxChange(fxType = type, liquidSurfaceStart = 0xFFFF, liquidSurfaceNew = 0xFFFF)
        }
        val plan = bridge.planTheme(roomId, native.name, targets ?: setOf(selected.id), theme.tilesetId, change, generation)
        if (edits.isEmpty() && plan.beforeModel == plan.afterModel && plan.replacements.values.all { it.first == it.second } &&
            paletteBefore == paletteAfter && effectBefore == effectAfter) {
            postStatus("${theme.displayName} already matches the room.")
            return@runCatching false
        }
        val operation = EditOperation(description, edits)
        if (edits.isNotEmpty() && plan.afterModel.asmLayoutSourceVersion == 0) {
            val level = selected.resources.level
            plan.afterModel.levelResourceOperations.getOrPut(level, ::mutableListOf).add(operation.tileOnly())
        }
        val previousUndo = undoStack.toList()
        val previousManaged = java.util.IdentityHashMap(managedPlmUndo)
        val previousItemScopes = java.util.IdentityHashMap(itemScopeSnapshots)
        var changeHead: AsmSourceTextChange? = null
        var applied = false
        bridge.applyTheme(plan, beforeTransition = {
            requireIdle()
            require(project.customGfx.palettes[paletteKey] == (if (applied) paletteAfter else paletteBefore) &&
                project.customGfx.paletteEffects[effectKey] == (if (applied) effectAfter else effectBefore)) {
                "The shared tileset palette changed; restore the newer palette edit before undoing this theme."
            }
        }, applySidecar = { undo ->
            val palette = if (undo) paletteBefore else paletteAfter
            val effect = if (undo) effectBefore else effectAfter
            if (palette == null) project.customGfx.palettes.remove(paletteKey) else project.customGfx.palettes[paletteKey] = palette
            if (effect == null) project.customGfx.paletteEffects.remove(effectKey) else project.customGfx.paletteEffects[effectKey] = effect
            applied = !undo
        }, afterTransition = { undo ->
            if (currentRoomId == roomId) {
                val active = currentPlmStateId() ?: selected.id
                loadRoom(roomId, parser, native)
                switchRoomState(active, parser)
                undoStack.clear(); redoStack.clear(); managedPlmUndo.clear(); itemScopeSnapshots.clear()
                if (changeHead == null) changeHead = requireNotNull(bridge.sourceUndoHead())
                managedPlmUndo.putAll(previousManaged)
                managedPlmUndo[operation] = ManagedSourceUndo(PLM_SOURCE_FILE,
                    plan.replacements.getValue(PLM_SOURCE_FILE).first,
                    plan.replacements.getValue(PLM_SOURCE_FILE).second, changeHead)
                itemScopeSnapshots.putAll(previousItemScopes)
                undoStack.addAll(previousUndo)
                if (undo) redoStack.add(operation) else undoStack.add(operation)
            } else if (currentTilesetId == targetTileset) reloadCurrentRoomTileGraphics(parser)
            if (editorTilesetId == targetTileset) loadEditorTileset(targetTileset, parser)
            paletteVersion++; dirty = true; undoVersion++; editVersion++
        })
        postStatus("Applied ${theme.displayName}${if (generation != null) " with generated layout and camera data" else ""}." +
            if (theme.paletteEffectId != null) " The palette is shared by rooms using tileset $targetTileset." else "")
        true
    }.getOrElse { postStatus("Theme blocked: ${it.message ?: "No changes applied."}"); false }

    private fun pushEditOperation(op: EditOperation): Boolean {
        if (hasManagedLayouts() && op.edits.isNotEmpty()) {
            return runCatching {
                require(!op.hasNonLayoutChanges()) { "This combined operation needs one source-aware transaction." }
                val bridge = requireNotNull(asmPlmSourceBridge)
                val before = bridge.sourceText()
                val roomId = currentRoomId
                val level = requireNotNull(currentStateEdits()).resources.level
                fun preview(undo: Boolean) {
                    require(pendingEdits.isEmpty()) { "Finish the current tile stroke before undoing source." }
                    if (currentRoomId == roomId && currentStateEdits()?.resources?.level == level) {
                        (if (undo) op.edits.asReversed() else op.edits).forEach { applyTileEdit(it, useNew = !undo) }
                    }
                    dirty = true; undoVersion++; editVersion++
                }
                if (bridge.layouts.edit(currentRoomId, requireNotNull(currentPlmStateId()), op.edits, { preview(true) }, { preview(false) }))
                    recordManagedRoomOperation(op, before, bridge)
                true
            }.getOrElse {
                op.edits.asReversed().forEach { edit -> applyTileEdit(edit, useNew = false) }
                if (pendingEdits.isEmpty()) refreshLayoutSource()
                postStatus("Layout edit blocked: ${it.message}")
                false
            }
        }
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
        recordOperationForCurrentScope(op)
        dirty = true
        editVersion++
        return true
    }

    // ─── Undo / Redo ────────────────────────────────────────────

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val pending = undoStack.last()
        if (hasManagedLayouts() && pending.edits.isNotEmpty() && pending !in managedPlmUndo) {
            postStatus("Undo the layout source adoption in ASM before undoing older GUI tile edits."); return false
        }
        if (hasManagedEnemies() && (pending.enemyAdds.isNotEmpty() || pending.enemyRemoves.isNotEmpty() || pending.enemyUpdates.isNotEmpty())) {
            postStatus("Enemy edit blocked: undo the source adoption in ASM before undoing older ROM enemy edits."); return false
        }
        managedPlmUndo[pending]?.let { (fileId, _, after, change) ->
            return runCatching {
                requireNotNull(asmPlmSourceBridge).undoSource(after, fileId, change)
                if (undoStack.lastOrNull() === pending) undoStack.removeAt(undoStack.lastIndex)
                if (redoStack.none { it === pending }) redoStack.add(pending)
                undoVersion++
                refreshLinkedPlms()
                true
            }.getOrElse { postStatus(it.message ?: "ASM source changed; use Undo in ASM to follow its current history."); false }
        }
        if ((pending.plmAdds.isNotEmpty() || pending.plmRemoves.isNotEmpty()) &&
            blockLinkedPlmMutation(pending.itemScope == ItemStateScope.ALL_STATES)) return false
        val op = undoStack.removeAt(undoStack.lastIndex)
        val scopedItemUndo = itemScopeSnapshots[op]
        val plmChanges = currentPlmChanges()
        val enemyChanges = currentEnemyChanges()
        val scrollChanges = currentScrollChanges()

        // Undo tile edits
        for (edit in op.edits.reversed()) {
            applyTileEdit(edit, useNew = false)
        }
        if (op.edits.isNotEmpty() || scopedItemUndo == null) removeRecordedOperationForCurrentScope(op)

        if (scopedItemUndo != null) {
            restoreItemScopeSnapshot(scopedItemUndo.before)
        } else {
            // Undo PLM adds (reverse = remove them)
            for (plm in op.plmAdds) {
                _workingPlms.removeAll { it.id == plm.plmId && it.x == plm.x && it.y == plm.y && it.param == plm.param }
                plmChanges.add(PlmChange("remove", plm.plmId, plm.x, plm.y, plm.param))
                if (plm.plmId == 0xB76F) cleanupSaveStationSpawnIfUnreferenced(plm.param and 0xFF)
            }
            // Undo PLM removes (reverse = re-add them)
            for (plm in op.plmRemoves) {
                _workingPlms.add(RomParser.PlmEntry(plm.plmId, plm.x, plm.y, plm.param))
                plmChanges.add(PlmChange("add", plm.plmId, plm.x, plm.y, plm.param))
                if (plm.plmId == 0xB76F) ensureAutoSaveStationSpawn(plm.x, plm.y, plm.param and 0xFF)
            }
        }

        // Undo enemy adds
        for (ec in op.enemyAdds) {
            _workingEnemies.removeAll { it.id == ec.enemyId && it.x == ec.x && it.y == ec.y }
            enemyChanges.add(EnemyChange("remove", ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                ec.extra1, ec.extra2, ec.extra3, origX = ec.x, origY = ec.y))
        }
        // Undo enemy removes
        for (ec in op.enemyRemoves) {
            _workingEnemies.add(RomParser.EnemyEntry(ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                ec.extra1, ec.extra2, ec.extra3))
            enemyChanges.add(EnemyChange("add", ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                ec.extra1, ec.extra2, ec.extra3))
        }
        // Undo enemy updates (swap back to old)
        for (eu in op.enemyUpdates) {
            val idx = _workingEnemies.indexOfFirst { it.id == eu.new.enemyId && it.x == eu.new.x && it.y == eu.new.y }
            if (idx >= 0) {
                val o = eu.old
                _workingEnemies[idx] = RomParser.EnemyEntry(o.enemyId, o.x, o.y, o.initParam, o.properties, o.extra1, o.extra2, o.extra3)
                enemyChanges.add(EnemyChange("update", o.enemyId, o.x, o.y, o.initParam, o.properties,
                    o.extra1, o.extra2, o.extra3, origX = eu.new.x, origY = eu.new.y))
            }
        }

        // Undo scroll edits
        for (sc in op.scrollEdits) {
            val roomWidthScreens = workingBlocksWide / 16
            val scrollIdx = sc.screenY * roomWidthScreens + sc.screenX
            if (scrollIdx in _workingScrolls.indices) {
                _workingScrolls[scrollIdx] = sc.oldValue
                scrollChanges.removeAll { it.screenX == sc.screenX && it.screenY == sc.screenY }
                if (sc.oldValue != _originalScrolls.getOrElse(scrollIdx) { sc.oldValue }) {
                    scrollChanges.add(ScrollChange(sc.screenX, sc.screenY, _originalScrolls[scrollIdx], sc.oldValue))
                }
                scrollVersion++
            }
        }

        redoStack.add(op)
        undoVersion++
        dirty = true
        editVersion++
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val pending = redoStack.last()
        if (hasManagedEnemies() && (pending.enemyAdds.isNotEmpty() || pending.enemyRemoves.isNotEmpty() || pending.enemyUpdates.isNotEmpty())) {
            postStatus("Enemy edit blocked: older ROM enemy edits cannot overwrite authored population source."); return false
        }
        managedPlmUndo[pending]?.let { (fileId, before, _, change) ->
            return runCatching {
                requireNotNull(asmPlmSourceBridge).redoSource(before, fileId, change)
                if (redoStack.lastOrNull() === pending) redoStack.removeAt(redoStack.lastIndex)
                if (undoStack.none { it === pending }) undoStack.add(pending)
                undoVersion++
                refreshLinkedPlms()
                true
            }.getOrElse { postStatus(it.message ?: "ASM source changed; use Redo in ASM to follow its current history."); false }
        }
        if ((pending.plmAdds.isNotEmpty() || pending.plmRemoves.isNotEmpty()) &&
            blockLinkedPlmMutation(pending.itemScope == ItemStateScope.ALL_STATES)) return false
        val op = redoStack.removeAt(redoStack.lastIndex)
        val scopedItemUndo = itemScopeSnapshots[op]
        val plmChanges = currentPlmChanges()
        val enemyChanges = currentEnemyChanges()
        val scrollChanges = currentScrollChanges()

        // Redo tile edits
        for (edit in op.edits) {
            applyTileEdit(edit, useNew = true)
        }
        if (op.edits.isNotEmpty() || (op.hasNonLayoutChanges() && scopedItemUndo == null)) {
            recordOperationForCurrentScope(op)
        }

        if (scopedItemUndo != null) {
            restoreItemScopeSnapshot(scopedItemUndo.after)
        } else {
            // Redo PLM adds
            for (plm in op.plmAdds) {
                _workingPlms.add(RomParser.PlmEntry(plm.plmId, plm.x, plm.y, plm.param))
                plmChanges.add(PlmChange("add", plm.plmId, plm.x, plm.y, plm.param))
                if (plm.plmId == 0xB76F) ensureAutoSaveStationSpawn(plm.x, plm.y, plm.param and 0xFF)
            }
            // Redo PLM removes
            for (plm in op.plmRemoves) {
                _workingPlms.removeAll { it.id == plm.plmId && it.x == plm.x && it.y == plm.y && it.param == plm.param }
                plmChanges.add(PlmChange("remove", plm.plmId, plm.x, plm.y, plm.param))
                if (plm.plmId == 0xB76F) cleanupSaveStationSpawnIfUnreferenced(plm.param and 0xFF)
            }
        }

        // Redo enemy adds
        for (ec in op.enemyAdds) {
            _workingEnemies.add(RomParser.EnemyEntry(ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                ec.extra1, ec.extra2, ec.extra3))
            enemyChanges.add(EnemyChange("add", ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                ec.extra1, ec.extra2, ec.extra3))
        }
        // Redo enemy removes
        for (ec in op.enemyRemoves) {
            _workingEnemies.removeAll { it.id == ec.enemyId && it.x == ec.x && it.y == ec.y }
            enemyChanges.add(EnemyChange("remove", ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                ec.extra1, ec.extra2, ec.extra3, origX = ec.x, origY = ec.y))
        }
        // Redo enemy updates
        for (eu in op.enemyUpdates) {
            val idx = _workingEnemies.indexOfFirst { it.id == eu.old.enemyId && it.x == eu.old.x && it.y == eu.old.y }
            if (idx >= 0) {
                val n = eu.new
                _workingEnemies[idx] = RomParser.EnemyEntry(n.enemyId, n.x, n.y, n.initParam, n.properties, n.extra1, n.extra2, n.extra3)
                enemyChanges.add(EnemyChange("update", n.enemyId, n.x, n.y, n.initParam, n.properties,
                    n.extra1, n.extra2, n.extra3, origX = eu.old.x, origY = eu.old.y))
            }
        }

        // Redo scroll edits
        for (sc in op.scrollEdits) {
            val roomWidthScreens = workingBlocksWide / 16
            val scrollIdx = sc.screenY * roomWidthScreens + sc.screenX
            if (scrollIdx in _workingScrolls.indices) {
                _workingScrolls[scrollIdx] = sc.newValue
                scrollChanges.removeAll { it.screenX == sc.screenX && it.screenY == sc.screenY }
                if (sc.newValue != _originalScrolls.getOrElse(scrollIdx) { sc.newValue }) {
                    scrollChanges.add(ScrollChange(sc.screenX, sc.screenY, _originalScrolls[scrollIdx], sc.newValue))
                }
                scrollVersion++
            }
        }

        undoStack.add(op)
        undoVersion++
        dirty = true
        editVersion++
        return true
    }

    // ─── Project file I/O ───────────────────────────────────────

    // ─── Room JSON Export/Import ────────────────────────────────
    fun exportRoomToJson(roomId: Int, romParser: RomParser): String {
        if (hasManagedDoors(roomId)) {
            val view = asmPlmSourceBridge?.doors?.view(roomId)
            require(view != null && view.problem == null && view.resource != null) { "Repair/load the room's authored doors before exporting room JSON." }
            require(view.resource.entries.none { it.arrival != null }) {
                "Room-only JSON cannot carry symbolic arrival code. Save the ASM project, or build/export a ROM instead."
            }
        }
        val room = romParser.readRoomHeader(roomId) ?: error("Room not found")
        val roomKey = project.roomKey(roomId)
        val hc = project.rooms[roomKey]?.effectiveHeaderChange
        val effectiveWidth = hc?.width ?: room.width
        val effectiveHeight = hc?.height ?: room.height

        var levelData = romParser.decompressLZ2(room.levelDataPtr)
        if (effectiveWidth != room.width || effectiveHeight != room.height) {
            levelData = resizeLevelData(levelData, room.width, room.height, effectiveWidth, effectiveHeight)
        }
        // Apply tile edits from project
        val wd = workingLevelData
        val useLive = (currentRoomId == roomId && wd != null)
        val dataToExport = if (useLive) wd else levelData

        val scrolls = if (useLive) _workingScrolls.toList()
        else romParser.parseScrollData(room.roomScrollsPtr, effectiveWidth, effectiveHeight).toList()

        val enemies = if (useLive) _workingEnemies.toList()
        else romParser.parseEnemyPopulation(room.enemySetPtr)

        val plms = romParser.getAllPlmEntriesForRoom(roomId)
        val doors = effectiveDoorsForRoom(roomId, romParser)

        val roomName = com.supermetroid.editor.data.RoomRepository().getAllRooms()
            .find { it.getRoomIdAsInt() == roomId }?.name ?: "Room \$${roomKey}"

        val export = com.supermetroid.editor.data.RoomExportData(
            roomId = roomKey,
            roomName = roomName,
            width = effectiveWidth,
            height = effectiveHeight,
            tileset = room.tileset,
            area = hc?.area ?: room.area,
            levelDataBase64 = java.util.Base64.getEncoder().encodeToString(dataToExport),
            scrollData = scrolls,
            enemies = enemies.map { e ->
                com.supermetroid.editor.data.EnemyExport(
                    species = e.id.toString(16).uppercase(),
                    x = e.x, y = e.y, initParam = e.initParam,
                    properties = e.properties, extra1 = e.extra1, extra2 = e.extra2, extra3 = e.extra3
                )
            },
            plms = plms.map { p ->
                com.supermetroid.editor.data.PlmExport(
                    id = p.id.toString(16).uppercase(),
                    x = p.x, y = p.y, param = p.param
                )
            },
            doors = doors.map { d ->
                com.supermetroid.editor.data.DoorExport(
                    destRoom = d.destRoomPtr.toString(16).uppercase(),
                    bitflag = d.bitflag, direction = d.direction,
                    doorCapCode = d.doorCapCode, screenX = d.screenX, screenY = d.screenY,
                    distFromDoor = d.distFromDoor, entryCode = d.entryCode
                )
            },
            musicTrack = room.musicTrack,
        )
        return kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(
            com.supermetroid.editor.data.RoomExportData.serializer(), export
        )
    }

    fun saveProject(romParser: RomParser? = null): Boolean {
        // Saving must capture the same deterministic patch catalog hydration
        // that a following Build/preview/export will use. Otherwise Save can
        // clear dirty state and the next build can immediately mutate the live
        // project back to Save*.
        seedDefaultPatches(forceRefreshBundled = true)
        val saved = ProjectFileService.saveProject(
            project = project,
            projectFilePath = projectFilePath,
            romParser = romParser,
            savePatternLibrary = !testMode,
            onLog = ::editorLog,
        )
        if (saved) {
            dirty = false
            postStatus("Project saved: $projectFilePath")
        } else {
            postStatus("Save failed")
        }
        return saved
    }

    /** Save a detached project snapshot without blocking the editor. Source auto-save skips optional
     * PNG/pattern exports; explicit Project Save supplies those options to preserve its workflow. */
    internal suspend fun saveProjectAsync(
        romParser: RomParser? = null,
        savePatternLibrary: Boolean = false,
        persist: suspend (String, String) -> Boolean = { text, path ->
            withContext(Dispatchers.IO) {
                ProjectFileService.saveSerializedProject(text, path, ::editorLog, romParser, savePatternLibrary && !testMode)
            }
        },
    ): Boolean {
        val owner = project
        val path = projectFilePath
        if (path.isBlank()) return false
        return projectSaveMutex.withLock {
            if (project !== owner || projectFilePath != path) return@withLock false
            projectSaveInProgress = true
            try {
                // Project load already hydrates the catalog. An ASM text save does not require a
                // forced ZIP reload/hash comparison of every bundled patch.
                seedDefaultPatches()
                val revision = projectDirtyRevision
                val text = ProjectFileService.serializeProject(owner)
                val saved = persist(text, path)
                if (project === owner && projectFilePath == path) {
                    if (saved && projectDirtyRevision == revision) dirty = false
                    postStatus(if (saved) "Project saved: $path" else "Save failed")
                }
                saved
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                if (project === owner && projectFilePath == path) postStatus("Save failed: ${problem.message}")
                false
            } finally {
                projectSaveInProgress = false
            }
        }
    }

    // ─── Export: patch ROM ──────────────────────────────────────

    fun exportToRom(romParser: RomParser): String? {
        seedDefaultPatches(forceRefreshBundled = true)
        if (project.romPath.isEmpty()) return null
        saveProject(romParser)
        val sourceParser = sourceRomData?.let { RomParser(it.copyOf(), sourceRoomCatalog) } ?: romParser
        return ProjectFileService.exportToRom(
            project,
            sourceParser,
            ::editorLog,
            ::postStatus,
            projectFilePath,
        )
    }

    /**
     * Builds an emulator ROM without monopolizing Compose's UI thread. Mutable
     * editor state is snapshotted before dispatch; compiler/export work then
     * operates on that immutable snapshot while progress returns to the
     * caller's Compose/AWT coroutine context.
     */
    suspend fun exportToRomAsync(
        romParser: RomParser,
        onProgress: (String) -> Unit = {},
    ): String? {
        if (romBuildInProgress) return null
        romBuildInProgress = true
        reportRomBuildStatus("Preparing ROM build…", onProgress)
        // Let Compose paint the busy state before project serialization begins.
        yield()
        return try {
            seedDefaultPatches(forceRefreshBundled = true)
            if (project.romPath.isEmpty()) return null
            if (!saveProject(romParser)) return null
            val projectSnapshot = ProjectFileService.snapshotProject(project)
            val sourceParser = sourceRomData?.let { RomParser(it.copyOf(), sourceRoomCatalog) } ?: romParser
            runBackgroundBuildWithProgress(
                onProgress = { message -> reportRomBuildStatus(message, onProgress) },
            ) { reportProgress ->
                ProjectFileService.exportToRom(
                    projectSnapshot,
                    sourceParser,
                    ::editorLog,
                    reportProgress,
                    projectFilePath,
                )
            }
        } finally {
            romBuildInProgress = false
        }
    }

    internal fun buildRomPreview(romParser: RomParser): com.supermetroid.editor.asm.AsmRomPreview? {
        seedDefaultPatches(forceRefreshBundled = true)
        if (project.romPath.isEmpty()) return null
        val projectSnapshot = ProjectFileService.snapshotProject(project)
        val sourceParser = sourceRomData?.let { RomParser(it.copyOf(), sourceRoomCatalog) } ?: romParser
        val result = ProjectFileService.buildRom(
            projectSnapshot,
            sourceParser,
            ::editorLog,
            ::postStatus,
            projectFilePath,
        ) ?: return null
        return com.supermetroid.editor.asm.AsmRomPreview.create(
            loadedRom = result.loadedRom,
            resultRom = result.resultRom,
            headerSize = result.headerSize,
            writeReport = result.writeReport,
        )
    }

    fun exportToIps(romParser: RomParser): String? {
        seedDefaultPatches(forceRefreshBundled = true)
        if (project.romPath.isEmpty()) return null
        saveProject(romParser)
        val sourceParser = sourceRomData?.let { RomParser(it.copyOf(), sourceRoomCatalog) } ?: romParser
        return ProjectFileService.exportToIps(
            project,
            sourceParser,
            ::editorLog,
            ::postStatus,
            projectFilePath,
        )
    }

}

/**
 * Copy a flat scroll-value grid from one screen dimension to another.
 * New cells default to [default] (1 = Blue/visible). Overlapping cells are preserved.
 */
internal fun resizeScrollGrid(
    source: IntArray,
    oldWidth: Int, oldHeight: Int,
    newWidth: Int, newHeight: Int,
    default: Int = 1,
): IntArray {
    val result = IntArray(newWidth * newHeight) { default }
    for (sy in 0 until minOf(oldHeight, newHeight)) {
        for (sx in 0 until minOf(oldWidth, newWidth)) {
            val oldIdx = sy * oldWidth + sx
            if (oldIdx in source.indices) result[sy * newWidth + sx] = source[oldIdx]
        }
    }
    return result
}

/**
 * Resize level data from (oldW x oldH) screens to (newW x newH) screens.
 * Layout: [2-byte L1 size header][L1 tile words][BTS bytes][L2 tile words (optional)].
 * Existing tiles are preserved where dimensions overlap; new areas are filled with 0 (air).
 */
internal fun resizeLevelData(
    data: ByteArray, oldWidthScreens: Int, oldHeightScreens: Int,
    newWidthScreens: Int, newHeightScreens: Int
): ByteArray {
    val oldBlocksW = oldWidthScreens * 16
    val oldBlocksH = oldHeightScreens * 16
    val newBlocksW = newWidthScreens * 16
    val newBlocksH = newHeightScreens * 16
    val oldTotal = oldBlocksW * oldBlocksH
    val newTotal = newBlocksW * newBlocksH

    val oldL1Size = if (data.size >= 2) (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8) else 0
    val btsStart = 2 + oldL1Size
    val oldL2Start = embeddedLayer2StartOffset(data, oldTotal)

    fun readWord(offset: Int): Int =
        if (offset + 1 < data.size) ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF) else 0
    fun readByte(offset: Int): Int =
        if (offset < data.size) data[offset].toInt() and 0xFF else 0

    val newL1Size = newTotal * 2
    val newDataSize = 2 + newL1Size + newTotal + (if (oldL2Start != null) newTotal * 2 else 0)
    val out = ByteArray(newDataSize)
    out[0] = (newL1Size and 0xFF).toByte()
    out[1] = ((newL1Size shr 8) and 0xFF).toByte()

    val copyW = minOf(oldBlocksW, newBlocksW)
    val copyH = minOf(oldBlocksH, newBlocksH)

    // L1 tiles
    for (by in 0 until newBlocksH) for (bx in 0 until newBlocksW) {
        val word = if (bx < copyW && by < copyH) readWord(2 + (by * oldBlocksW + bx) * 2) else RomConstants.AIR_TILE_WORD
        val off = 2 + (by * newBlocksW + bx) * 2
        out[off] = (word and 0xFF).toByte()
        out[off + 1] = ((word shr 8) and 0xFF).toByte()
    }
    // BTS
    val newBtsStart = 2 + newL1Size
    for (by in 0 until newBlocksH) for (bx in 0 until newBlocksW) {
        val bts = if (bx < copyW && by < copyH) readByte(btsStart + by * oldBlocksW + bx) else 0
        out[newBtsStart + by * newBlocksW + bx] = bts.toByte()
    }
    // L2
    if (oldL2Start != null) {
        val newL2Start = newBtsStart + newTotal
        for (by in 0 until newBlocksH) for (bx in 0 until newBlocksW) {
            val word = if (bx < copyW && by < copyH) readWord(oldL2Start + (by * oldBlocksW + bx) * 2) else 0
            val off = newL2Start + (by * newBlocksW + bx) * 2
            out[off] = (word and 0xFF).toByte()
            out[off + 1] = ((word shr 8) and 0xFF).toByte()
        }
    }
    return out
}

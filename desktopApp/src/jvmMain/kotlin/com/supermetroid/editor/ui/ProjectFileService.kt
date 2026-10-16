package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmCompiledBuildBase
import com.supermetroid.editor.asm.AsmBuildArtifactRepository
import com.supermetroid.editor.asm.AsmCompilationException
import com.supermetroid.editor.asm.ASM_ROOM_GRAPH_OWNER
import com.supermetroid.editor.asm.AsmProjectCompiler
import com.supermetroid.editor.asm.AsmPatchBackendRegistry
import com.supermetroid.editor.asm.AsmProjectWorkspaceRepository
import com.supermetroid.editor.asm.AsmPlmSourceCatalog
import com.supermetroid.editor.asm.AsmRoomObjectSource
import com.supermetroid.editor.asm.AsmScrollCommandSource
import com.supermetroid.editor.asm.AsmInitialScrollSource
import com.supermetroid.editor.asm.protectInitialScrollBaseline
import com.supermetroid.editor.asm.AsmFxSource
import com.supermetroid.editor.asm.FX_SOURCE_FILE
import com.supermetroid.editor.asm.protectFxBaseline
import com.supermetroid.editor.asm.AsmEnemySource
import com.supermetroid.editor.asm.ENEMY_SOURCE_FILE
import com.supermetroid.editor.asm.protectEnemyBaseline
import com.supermetroid.editor.rom.readSourceEnemyPopulation
import com.supermetroid.editor.rom.readSourceEnemyGraphics
import com.supermetroid.editor.rom.completeEnemyGraphics
import com.supermetroid.editor.rom.baseSourceStateIndex
import com.supermetroid.editor.asm.AsmWlaSymbolParser
import com.supermetroid.editor.asm.PLM_SOURCE_FILE
import com.supermetroid.editor.asm.AsmSourceAssetMaterializer
import com.supermetroid.editor.asm.AsmSourcePatchMaterializer
import com.supermetroid.editor.asm.AsmSourceRoomMaterializer
import com.supermetroid.editor.asm.copyForReuse
import com.supermetroid.editor.data.PatternLibrary
import com.supermetroid.editor.data.ProjectPaths
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmEditProjectFormat
import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import com.supermetroid.editor.rom.words
import java.io.File
import java.security.MessageDigest

internal object ProjectFileService {
    private val asmProjectCompiler = AsmProjectCompiler()
    private const val PREPARED_ASM_CACHE_VERSION = "smedit-prepared-asm-base-v12-arrivals"
    private const val MAX_PREPARED_ASM_BASES = 2
    private val preparedAsmBases = object : LinkedHashMap<String, AsmCompiledBuildBase>(
        MAX_PREPARED_ASM_BASES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, AsmCompiledBuildBase>?,
        ): Boolean = size > MAX_PREPARED_ASM_BASES
    }
    fun loadProject(file: File): SmEditProject =
        SmEditProjectFormat.decode(file.readText()).let { saved ->
            saved.copy(romPath = ProjectPaths(file).resolveBaseRom(saved.romPath).absolutePath)
        }

    /** Isolate non-writing previews from exporter metadata hydration. */
    fun snapshotProject(project: SmEditProject): SmEditProject =
        SmEditProjectFormat.decode(SmEditProjectFormat.encode(project))

    /** Capture mutable editor data on its caller thread; decode/persist this detached snapshot on a worker. */
    fun serializeProject(project: SmEditProject): String = SmEditProjectFormat.encode(project)

    fun saveSerializedProject(
        text: String,
        projectFilePath: String,
        onLog: (String) -> Unit,
        romParser: RomParser? = null,
        savePatternLibrary: Boolean = false,
    ): Boolean = saveProject(SmEditProjectFormat.decode(text), projectFilePath, romParser, savePatternLibrary, onLog)

    fun saveProject(
        project: SmEditProject,
        projectFilePath: String,
        romParser: RomParser?,
        savePatternLibrary: Boolean,
        onLog: (String) -> Unit,
    ): Boolean {
        if (projectFilePath.isEmpty()) return false
        return try {
            val projectFile = File(projectFilePath)
            val paths = ProjectPaths(projectFile)
            val base = paths.resolveBaseRom(project.romPath)
            val portable = project.copy(
                // Prune the serialized snapshot only. Source/GUI undo retains references to live room
                // identities, including a room whose last ownership flag was just undone.
                rooms = project.rooms.filterValues { it.hasEdits }.toMutableMap(),
                romPath = paths.relativeBaseRom(base),
                baseRom = project.baseRom ?: base.takeIf(File::isFile)?.let(ProjectLifecycle::identity),
            )
            writeBytesAtomically(projectFile, SmEditProjectFormat.encode(portable).toByteArray())
            onLog("Project saved: $projectFilePath")
            romParser?.let { exportCustomGfxPngs(project, projectFilePath, it, onLog) }
            if (savePatternLibrary) PatternLibrary.saveAll(project.patterns)
            true
        } catch (e: Exception) {
            onLog("Save failed: ${e.message}")
            false
        }
    }

    fun exportToRom(
        project: SmEditProject,
        romParser: RomParser,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        projectFilePath: String = "",
    ): String? = withBuildFailureReport(project, projectFilePath, onLog, onStatus, "Export failed safely") { status ->
        val compiledAsmBase = prepareBuildBase(project, projectFilePath, romParser, onLog, status)
        val exporter = RomExporter(project, romParser, onLog, status, compiledAsmBase = compiledAsmBase, projectFilePath = projectFilePath)
        val built = exporter.build()
        exporter.export(built).also { path ->
            if (path != null && built != null) publishRoomData(project, projectFilePath, romParser, built, onLog)
        }
    }

    fun buildRom(
        project: SmEditProject,
        romParser: RomParser,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        projectFilePath: String = "",
    ): RomBuildResult? = withBuildFailureReport(project, projectFilePath, onLog, onStatus, "Export preview failed safely") { status ->
        val compiledAsmBase = prepareBuildBase(project, projectFilePath, romParser, onLog, status)
        RomExporter(
            project = project,
            romParser = romParser,
            onLog = onLog,
            onStatus = status,
            logWritePlanDetails = false,
            compiledAsmBase = compiledAsmBase,
        ).build()?.also { publishRoomData(project, projectFilePath, romParser, it, onLog) }
    }

    private fun publishRoomData(project: SmEditProject, projectFilePath: String, inputParser: RomParser, build: RomBuildResult, onLog: (String) -> Unit) {
        if (project.asmWorkspace.buildMode != ProjectRomBuildMode.ASM_SOURCE || projectFilePath.isBlank()) return
        runCatching {
            // Keep new-room builds explicit until stable source identities are supported.
            val source = if (project.newRooms.isEmpty()) {
                // Headers retain their input identities. Rediscovering the built ROM can
                // drop rooms with generated selector redirects from the heuristic scanner.
                com.supermetroid.editor.asm.AsmRoomDataSnapshot.render(build.resultRom, inputParser.roomCatalog.rooms)
            } else "; Room Data is not available for projects with added rooms yet. Room edits still use the ROM backend.\n"
            AsmBuildArtifactRepository().publishRoomData(projectFilePath, source)
        }.onFailure { onLog("Could not retain Room Data snapshot: ${it.message}") }
    }

    /** Retain failures from planning/final export as well as from Asar itself.
     * Both the emulator export and non-writing Build use the same reporting path. */
    private fun <T> withBuildFailureReport(
        project: SmEditProject,
        projectFilePath: String,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        failurePrefix: String,
        build: ((String) -> Unit) -> T?,
    ): T? {
        var latestStatus = ""
        fun status(message: String) {
            latestStatus = message
            onStatus(message)
        }
        val result = try {
            build(::status)
        } catch (problem: Exception) {
            val message = "$failurePrefix: ${problem.message ?: problem::class.simpleName}"
            onLog("ERROR: $message")
            status(message)
            null
        }
        if (result == null && project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE && projectFilePath.isNotBlank()) {
            runCatching {
                AsmBuildArtifactRepository().publishPipelineFailure(
                    projectFilePath,
                    latestStatus.ifBlank { "$failurePrefix. No ROM was built." },
                )
            }.onFailure { onLog("Could not retain ASM build failure: ${it.message}") }
        }
        return result
    }

    fun exportToIps(
        project: SmEditProject,
        romParser: RomParser,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        projectFilePath: String = "",
    ): String? {
        val original = romParser.getRomData()
        val smcPath = exportToRom(project, romParser, onLog, onStatus, projectFilePath) ?: return null
        val patched = File(smcPath).readBytes()
        val ipsData = buildIpsPatch(original, patched)
        val ipsFile = File(smcPath).let { File(it.parentFile, "${it.nameWithoutExtension}.ips") }
        try {
            writeBytesAtomically(ipsFile, ipsData)
        } catch (e: Exception) {
            val message = "Export failed safely while writing IPS: ${e.message ?: e::class.simpleName}"
            onLog("ERROR: $message")
            onStatus(message)
            return null
        }
        val message = "Exported IPS: ${ipsFile.absolutePath} (${ipsData.size} bytes)"
        onLog(message)
        onStatus(message)
        return ipsFile.absolutePath
    }

    private fun prepareBuildBase(
        project: SmEditProject,
        projectFilePath: String,
        romParser: RomParser,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
    ): AsmCompiledBuildBase? {
        if (project.asmWorkspace.buildMode == ProjectRomBuildMode.PATCHED_ROM) {
            require(project.rooms.values.none { it.asmDoorSourceVersion != 0 || it.asmStateSelectorsSourceVersion != 0 || it.asmRoomPropertiesSourceVersion != 0 || it.asmLayoutSourceVersion != 0 || it.asmPlmSourceVersion != 0 || it.asmInitialScrollSourceVersion != 0 || it.asmFxSourceVersion != 0 || it.asmEnemySourceVersion != 0 }) { "Authored room data requires ASM source mode." }
            require(project.asmWorkspace.plmSourceBindings.isEmpty()) { "Linked room objects require ASM source mode." }
            val sourceEdits = if (project.asmWorkspace.enabled && projectFilePath.isNotBlank()) {
                AsmProjectWorkspaceRepository().sourceOverrideFileIds(projectFilePath)
            } else {
                emptySet()
            }
            require(sourceEdits.isEmpty()) {
                "Loaded ROM mode cannot ignore saved ASM source edits (${sourceEdits.sorted().joinToString()}). " +
                    "Restore those files to their original source or select ASM source mode."
            }
            return null
        }
        require(project.asmWorkspace.enabled) { "Enable Project ASM before selecting the ASM source build" }
        require(projectFilePath.isNotBlank()) { "Save the SMEDIT project before building from ASM source" }
        onLog("[ASM-BUILD] Preparing clean source build for $projectFilePath")
        val compiler = asmProjectCompiler
        val loadedRom = romParser.copyRomData()
        val sourceInputs = compiler.sourceInputsFingerprint(projectFilePath, loadedRom) { progress ->
            onLog("[ASM-BUILD] $progress")
            onStatus(progress)
        }
        // Automatic ownership must work even when this room was never visited and no binding was saved.
        val linkedWorkspace = requireNotNull(AsmProjectWorkspaceRepository().loadForBuild(projectFilePath))
        linkedWorkspace.let { tree ->
            val files = listOf(PLM_SOURCE_FILE, "macros.asm") +
                (if (project.rooms.values.any { it.asmDoorSourceVersion != 0 || it.asmFxSourceVersion != 0 }) listOf(FX_SOURCE_FILE) else emptyList()) +
                (if (project.rooms.values.any { it.asmEnemySourceVersion != 0 }) listOf(ENEMY_SOURCE_FILE) else emptyList())
            for (fileId in files) {
                val override = File(tree.root, "overrides/src/$fileId")
                val authored = override.takeIf { it.isFile } ?: File(tree.originalDirectory, "src/$fileId")
                require(authored.readText() == File(tree.workingDirectory, "src/$fileId").readText()) {
                    "$fileId changed outside SMEDIT. Reconcile the source and its workspace copy before building room objects."
                }
            }
        }
        val preparedKey = preparedAsmCacheKey(project, sourceInputs)
        getPreparedAsmBase(preparedKey)?.let { cached ->
            // A different prepared build may have replaced the on-disk report.
            // Restore symbols and generated source together when revisiting this build.
            runCatching {
                AsmBuildArtifactRepository().publishSuccess(
                    projectFilePath, cached.compilerSymbols, cached.compilerOutput, cached.assemblerVersion,
                    generatedRoomSource = cached.generatedRoomSource,
                )
            }
            val message = "ASM/project inputs unchanged — reusing prepared source base…"
            onLog("[ASM-BUILD] $message")
            onStatus(message)
            return cached
        }
        val linkedCatalog = AsmPlmSourceCatalog.load(linkedWorkspace.originalDirectory, romParser)
        val linkedSource = File(linkedWorkspace.workingDirectory, "src/$PLM_SOURCE_FILE").readText()
        val linkedMacros = File(linkedWorkspace.workingDirectory, "src/macros.asm").readText()
        val managedRooms = project.rooms.values.filter { it.asmPlmSourceVersion != 0 }
        if (managedRooms.isNotEmpty()) linkedCatalog.requireStandardMacro(linkedMacros)
        val managedSource = AsmRoomObjectSource(linkedSource, linkedCatalog.symbols)
        val initialScrollSource = AsmInitialScrollSource(linkedSource, linkedCatalog.symbols)
        val layoutSource = com.supermetroid.editor.asm.AsmRoomLayoutSource(linkedSource)
        val roomPropertiesSource = com.supermetroid.editor.asm.AsmRoomPropertiesSource(linkedSource)
        val sourceRoomProperties = project.rooms.values.filter { it.asmRoomPropertiesSourceVersion != 0 }.associate { room ->
            room.roomId to roomPropertiesSource.resource(room)
        }
        val selectorSource = com.supermetroid.editor.asm.AsmRoomSelectorsSource(linkedSource)
        val selectorDeclarations = project.rooms.values.filter { it.asmStateSelectorsSourceVersion != 0 }.associate { room -> room.roomId to selectorSource.resource(room) }
        val doorRooms = project.rooms.values.filter { it.asmDoorSourceVersion != 0 }
        val doorDeclarations = if (doorRooms.isEmpty()) emptyMap() else {
            val source = com.supermetroid.editor.asm.AsmDoorSource(File(linkedWorkspace.workingDirectory, "src/$FX_SOURCE_FILE").readText(), linkedSource, linkedCatalog.symbols)
            doorRooms.associate { room -> room.roomId to source.resource(room, romParser) { destination ->
                romParser.readRoomHeader(destination)?.let { header ->
                    val override = project.rooms[project.roomKey(destination)]?.effectiveHeaderChange
                    (override?.width ?: header.width) to (override?.height ?: header.height)
                }
            } }
        }
        val sourceLayouts = project.rooms.values.filter { it.asmLayoutSourceVersion != 0 }.associate { room ->
            val header = requireNotNull(romParser.readRoomHeader(room.roomId))
            room.roomId to layoutSource.resources(room, (room.effectiveHeaderChange?.width ?: header.width) * 16, (room.effectiveHeaderChange?.height ?: header.height) * 16)
        }
        val fxRooms = project.rooms.values.filter { it.asmFxSourceVersion != 0 }
        val sourceFx = if (fxRooms.isEmpty()) emptyMap() else {
            val fxSource = AsmFxSource(File(linkedWorkspace.workingDirectory, "src/$FX_SOURCE_FILE").readText())
            fxRooms.associate { it.roomId to fxSource.resources(it) }
        }
        val sourceInitialScrolls = project.rooms.values.filter { it.asmInitialScrollSourceVersion != 0 }.associate { room ->
            val header = requireNotNull(romParser.readRoomHeader(room.roomId))
            room.roomId to initialScrollSource.resources(room, room.effectiveHeaderChange?.width ?: header.width, room.effectiveHeaderChange?.height ?: header.height)
        }
        val enemyRooms = project.rooms.values.filter { it.asmEnemySourceVersion != 0 }
        val sourceEnemies = if (enemyRooms.isEmpty()) emptyMap() else {
            linkedCatalog.requireStandardEnemyMacros(linkedMacros)
            val source = AsmEnemySource(File(linkedWorkspace.workingDirectory, "src/$ENEMY_SOURCE_FILE").readText(), linkedCatalog.symbols)
            enemyRooms.associate { room ->
                val inputs = source.resources(room)
                val states = romParser.inspectRoomStates(room.roomId).states
                inputs.forEach { (id, input) ->
                    val index = requireNotNull(room.states.single { it.id == id }.baseSourceStateIndex())
                    val pc = requireNotNull(states.getOrNull(index)?.stateDataPcOffset)
                    val baseline = readSourceEnemyPopulation(romParser, romParser.readStateData(pc).getValue("enemySetPtr"), "Original population")
                    val baselineGraphics = readSourceEnemyGraphics(romParser, romParser.readStateData(pc).getValue("enemyGfxPtr"), "Original GFX")
                    try {
                        require(completeEnemyGraphics(romParser, input.population.entries, baseline.entries, input.graphics, baselineGraphics) == input.graphics) {
                            "${input.population.identity}: this actor needs its paired GFX entry."
                        }
                    } catch (error: Exception) {
                        throw IllegalArgumentException("$ENEMY_SOURCE_FILE:${source.errorLine(room.roomId, id, error.message.orEmpty()) + 1}: ${error.message}", error)
                    }
                }
                room.roomId to inputs
            }
        }
        val sourcePlmSets = managedRooms.associate { room ->
            val header = requireNotNull(romParser.readRoomHeader(room.roomId))
            room.roomId to managedSource.resources(room, (room.effectiveHeaderChange?.width ?: header.width) * (room.effectiveHeaderChange?.height ?: header.height))
        }
        val sourceScrolls = sourcePlmSets.values.flatMap { states -> states.values.flatMap { it.scrollReferences.values } }
            .groupBy { it.identity }.mapValues { (name, resources) ->
                require(resources.distinct().size == 1) { "Shared scroll source $name has inconsistent values." }
                resources.first()
            }
        val sourceSaves = sourcePlmSets.values.flatMap { states -> states.values.flatMap { it.saves } }.groupBy { it.identity }
            .mapValues { (_, references) ->
                require(references.distinct().size == 1) { "Shared save source has inconsistent fields." }
                references.first().also { com.supermetroid.editor.rom.validateSourceSaveOwnership(it, romParser) }
            }
        // Numeric original pointers are fixed-layout references. Validate their current source too;
        // raw byte syntax alone would happily compile invalid colors or a shifted command stream.
        val originalScrollInputs = linkedMapOf<Int, List<ScrollCommand>>()
        for (room in managedRooms) {
            val header = requireNotNull(romParser.readRoomHeader(room.roomId))
            val screens = (room.effectiveHeaderChange?.width ?: header.width) * (room.effectiveHeaderChange?.height ?: header.height)
            for (resource in sourcePlmSets.getValue(room.roomId).values) resource.entries.forEachIndexed { index, entry ->
                if (entry.id == 0xB703 && index !in resource.scrollReferences) {
                    val commands = AsmScrollCommandSource.original(linkedSource, linkedCatalog.symbols,
                        entry.param, romParser, screens, linkedCatalog.originalText)
                    val prior = originalScrollInputs.put(entry.param, commands)
                    require(prior == null || prior == commands) { "Shared original scroll commands have inconsistent values." }
                }
            }
        }
        // Resolve known collisions before spending time in Asar. GUI-only lists stay GUI-owned.
        linkedCatalog.sourceBindings(project, linkedSource, linkedMacros)
        fun protectLinkedObjects(base: AsmCompiledBuildBase): AsmCompiledBuildBase = linkedCatalog.protectBuild(
            project,
            linkedSource,
            linkedMacros,
            base,
        )
        val resolvedSelectors = com.supermetroid.editor.asm.resolveExecutableRoomSelectors(selectorDeclarations, protectLinkedObjects(compiler.compile(
            projectFilePath = projectFilePath,
            loadedRom = loadedRom,
        ) { progress ->
            onLog("[ASM-BUILD] $progress")
            onStatus(progress)
        }))
        val sourceSelectors = resolvedSelectors.selectors
        val sourceDoors = com.supermetroid.editor.asm.resolveDoorArrivals(doorDeclarations, resolvedSelectors.compiled)
        val initial = com.supermetroid.editor.asm.protectDoorSourceBaseline(sourceDoors, romParser, resolvedSelectors.compiled, linkedCatalog.symbols)
        val compiledSaveParser = RomParser(initial.romBytes)
        protectInitialScrollBaseline(project, romParser, compiledSaveParser)
        protectFxBaseline(project, romParser, compiledSaveParser)
        protectEnemyBaseline(project, romParser, compiledSaveParser, sourceEnemies)
        for (save in sourceSaves.values) {
            val baseline = requireNotNull(romParser.readSaveEntry(save.area, save.slot))
            val compiled = requireNotNull(compiledSaveParser.readSaveEntry(save.area, save.slot))
            require(baseline.words() == compiled.words()) {
                "Save slot ${save.area}:${save.slot} also changed in original Bank \$80 source. Resolve ownership before building the authored save record."
            }
        }
        if (originalScrollInputs.isNotEmpty()) {
            val built = RomParser(initial.romBytes)
            val labels = AsmWlaSymbolParser.parse(initial.compilerSymbols.toString(Charsets.UTF_8)).labels
            for ((pointer, expected) in originalScrollInputs) {
                val name = linkedCatalog.symbols.entries.firstOrNull { it.value == pointer && it.key.startsWith("RoomPLM_") }?.key
                require(name == null || labels[name] == (0x8F0000 or pointer)) {
                    "$name moved from its original address. Use Edit commands to create a relocatable scroll resource."
                }
                val actual = AsmScrollCommandSource.original("", emptyMap(), pointer, built, 128)
                require(actual == expected) {
                    "Scroll pointer \$${pointer.toString(16).uppercase()} no longer matches the room preview. Use Edit commands to import a named resource."
                }
            }
        }
        onLog("[ASM-BUILD] Planning editor-owned source assets…")
        val planningLog = mutableListOf<String>()
        val planned = RomExporter(
            project = project,
            romParser = romParser,
            onLog = planningLog::add,
            onStatus = {},
            logWritePlanDetails = false,
            compiledAsmBase = initial,
            sourcePlmSets = sourcePlmSets,
            sourceInitialScrolls = sourceInitialScrolls,
            sourceFx = sourceFx,
            sourceEnemies = sourceEnemies,
            sourceLayouts = sourceLayouts,
            sourceRoomProperties = sourceRoomProperties,
            sourceSelectors = sourceSelectors,
            sourceDoors = sourceDoors,
        ).build() ?: throw AsmCompilationException(
            planningLog.lastOrNull { it.startsWith("ERROR:") }
                ?.removePrefix("ERROR: ")
                ?: "Could not validate editor data against the compiled ASM source"
        )
        // Pointer discovery must use the same source branch order as the private ROM encoder,
        // including on a headless build with no GUI preview caches. Never reorder persisted identities.
        val placementProject = project.copy(rooms = project.rooms.mapValuesTo(linkedMapOf()) { (_, room) ->
            val selectors = sourceSelectors[room.roomId]
            if (selectors == null) room else room.copy(
                states = selectors.order.map { id -> room.states.single { it.id == id }.copy(
                    // Opaque predicates have no GUI condition. This private placement
                    // model uses provenance only; the graph encoder emits their adapter.
                    condition = selectors.conditions[id] ?: room.states.single { it.id == id }.condition,
                    conditionChanged = true, asmConditionProjection = null,
                ) }.toMutableList(),
                stateGraphChanged = true, asmStateSelectorsSourceVersion = 0, asmSelectorOrderProjection = null,
            )
        })
        val roomMaterialization = AsmSourceRoomMaterializer().materialize(
            placementProject, planned.writeReport, planned.resultRom, initial.referenceRomBody.size, sourceScrolls, sourceSaves, sourceInitialScrolls, sourceFx, sourceEnemies, sourceDoors,
        )
        require(sourceDoors.keys.all { project.roomKey(it) in roomMaterialization.roomIds } &&
            roomMaterialization.doorTables.size == sourceDoors.values.sumOf { it.entries.size }) {
            "Authored DoorDefs could not be included atomically; no ROM was produced."
        }
        require(sourceLayouts.keys.all { project.roomKey(it) in roomMaterialization.roomIds }) {
            "Authored layouts could not be included atomically in the ASM build; no ROM was produced."
        }
        require(sourceRoomProperties.keys.all { project.roomKey(it) in roomMaterialization.roomIds }) {
            "Authored room properties could not be included atomically in the ASM build; no ROM was produced."
        }
        require(sourceSelectors.keys.all { project.roomKey(it) in roomMaterialization.roomIds }) { "Selectors could not be included atomically; no ROM was produced." }
        val enemyIdentities = sourceEnemies.values.flatMap { it.values }.flatMap { listOf(it.population.identity, it.graphics.identity) }.toSet()
        require(roomMaterialization.enemyTables.map { it.identity }.toSet() == enemyIdentities &&
            roomMaterialization.enemyTables.sumOf { it.pointerOffsets.size } == sourceEnemies.values.sumOf { it.size * 2 }) {
            "Authored population/GFX inputs could not be included atomically; no ROM was produced."
        }
        require(roomMaterialization.fxTables.map { it.resource.identity }.toSet() == sourceFx.values.flatMap { it.values }.map { it.identity }.toSet() &&
            roomMaterialization.fxTables.sumOf { it.pointerOffsets.size } == sourceFx.values.sumOf { it.size }) {
            "Authored FX could not be included atomically; no ROM was produced."
        }
        require(roomMaterialization.initialScrollTables.map { it.resource.identity }.toSet() ==
            sourceInitialScrolls.values.flatMap { it.values }.map { it.identity }.toSet() &&
            roomMaterialization.initialScrollTables.sumOf { it.pointerOffsets.size } == sourceInitialScrolls.values.sumOf { it.size }) {
            "Authored initial scrolls could not be included atomically; no ROM was produced."
        }
        val authoredResources = managedRooms.flatMap { room -> room.states.map { state ->
            AsmRoomObjectSource.stateMacro(room.roomId, state.id) to managedSource.table(room.roomId, state.id).resource.name
        } }.toMap()
        val emitted = roomMaterialization.plmTables.filter { it.authoredMacro != null }
        require(roomMaterialization.saveTables.map { it.resource.identity }.toSet() == sourceSaves.keys) {
            "Authored save records could not be included atomically in this build; no ROM was produced."
        }
        require(roomMaterialization.scrollTables.map { it.resource.identity }.toSet() == sourceScrolls.keys) {
            "Authored scroll source could not be included atomically in this room build; no ROM was produced."
        }
        require(emitted.map { authoredResources.getValue(it.authoredMacro!!) }.toSet() == authoredResources.values.toSet() &&
            emitted.sumOf { it.pointerPcOffsets.size } == authoredResources.size) {
            "Authored object source could not be included atomically in this room build; no ROM was produced."
        }
        val materialization = AsmSourceAssetMaterializer().materialize(
            projectFilePath = projectFilePath,
            report = planned.writeReport,
            excludedOwners = if (roomMaterialization.isEmpty) emptySet() else setOf(ASM_ROOM_GRAPH_OWNER),
        )
        val patchMaterialization = AsmSourcePatchMaterializer().materialize(
            report = planned.writeReport,
            sourceOwners = AsmPatchBackendRegistry.sourceOwners(project.patches),
            compiledRomSize = AsmProjectCompiler.ASM_ROM_SIZE,
        )
        val compiled = if (materialization.isEmpty && roomMaterialization.isEmpty && patchMaterialization.isEmpty) {
            onLog(
                "[ASM-BUILD] No fixed source assets or source-capable patches required generation " +
                    "(${materialization.skippedWrites}/${materialization.eligibleWrites} asset candidate writes " +
                    "and ${roomMaterialization.skippedWrites}/${roomMaterialization.eligibleWrites} room writes " +
                    "remain post-compile)"
            )
            initial
        } else {
            val generatedBytes = materialization.claims.sumOf { it.length }
            val generatedRoomBytes = roomMaterialization.claims.sumOf { it.length }
            val generatedPatchBytes = patchMaterialization.claims.sumOf { it.length }
            if (!materialization.isEmpty) {
                onLog(
                    "[ASM-BUILD] Staging ${materialization.assetOverrides.size} source asset(s): " +
                        "$generatedBytes byte(s) across ${materialization.claims.size} range(s); " +
                        "${materialization.skippedWrites} candidate write(s) remain post-compile"
                )
            }
            if (!roomMaterialization.isEmpty) {
                onLog(
                    "[ASM-BUILD] Selected atomic ASM backend for ${roomMaterialization.roomIds.size} existing room(s): " +
                        "$generatedRoomBytes byte(s) across ${roomMaterialization.claims.size} range(s), " +
                        "${roomMaterialization.plmTables.size} symbolic PLM table(s)"
                )
            } else if (roomMaterialization.skippedWrites > 0) {
                onLog(
                    "[ASM-BUILD] ${roomMaterialization.skippedWrites} room write(s) remain on the ROM backend; " +
                        "this graph requires room identities, write kinds, or ROM space outside the current ASM backend"
                )
            }
            if (!patchMaterialization.isEmpty) {
                onLog(
                    "[ASM-BUILD] Selected ASM backend for ${patchMaterialization.claims.map { it.owner }.distinct().size} " +
                        "patch(es): $generatedPatchBytes byte(s) across ${patchMaterialization.claims.size} range(s); " +
                        "${patchMaterialization.skippedWrites} write(s) remain on the ROM backend"
                )
            }
            val resolvedMaterialized = com.supermetroid.editor.asm.resolveExecutableRoomSelectors(selectorDeclarations, protectLinkedObjects(compiler.compile(
                projectFilePath = projectFilePath,
                loadedRom = loadedRom,
                materialization = materialization,
                patchMaterialization = patchMaterialization,
                roomMaterialization = roomMaterialization,
                referenceRomBody = initial.referenceRomBody,
                managedBank8FStart = initial.managedBank8FStart,
            ) { progress ->
                onLog("[ASM-BUILD] $progress")
                onStatus(progress)
            }))
            require(resolvedMaterialized.selectors == sourceSelectors) { "Executable predicate labels moved during generated-room compilation; no ROM was produced." }
            val materializedCompile = resolvedMaterialized.compiled
            require(com.supermetroid.editor.asm.resolveDoorArrivals(doorDeclarations, materializedCompile) == sourceDoors) {
                "Arrival ASM symbols moved during generated-room compilation; no ROM was produced."
            }
            materializedCompile.copy(
                compilerOutput = (initial.compilerOutput + materializedCompile.compilerOutput).distinct(),
                expectedFinalParitySha256 = asmGeneratedParitySha256(planned.resultRom),
                expectedFinalRomBytes = planned.resultRom.copyOf(),
                roomValidationRomBytes = if (roomMaterialization.isEmpty) null else initial.romBytes.copyOf(),
            )
        }
        compiled.compilerOutput
            .filter { it.contains("warn", ignoreCase = true) || it.contains("error", ignoreCase = true) }
            .forEach { onLog("[ASAR] $it") }
        onLog(
            "[ASM-BUILD] Source base ready: ${compiled.sourceOwnedRanges.sumOf { it.length }} source-owned byte(s) " +
                "across ${compiled.sourceOwnedRanges.size} authored range(s), " +
                "${compiled.generatedAssetClaims.sumOf { it.length }} generated asset byte(s), " +
                "${compiled.generatedRoomClaims.sumOf { it.length }} generated room byte(s), " +
                "${compiled.generatedPatchClaims.sumOf { it.length }} ASM patch byte(s)"
        )
        putPreparedAsmBase(preparedKey, compiled)
        // The exporter may hydrate deterministic patch safety metadata on its
        // isolated project snapshot. Accept that equivalent post-plan snapshot
        // too, without allowing unrelated project changes to hit this entry.
        val hydratedPreparedKey = preparedAsmCacheKey(project, sourceInputs)
        if (hydratedPreparedKey != preparedKey) putPreparedAsmBase(hydratedPreparedKey, compiled)
        return compiled
    }

    private fun preparedAsmCacheKey(project: SmEditProject, sourceInputs: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: ByteArray) {
            val size = value.size
            digest.update((size ushr 24).toByte())
            digest.update((size ushr 16).toByte())
            digest.update((size ushr 8).toByte())
            digest.update(size.toByte())
            digest.update(value)
        }
        update(PREPARED_ASM_CACHE_VERSION.toByteArray())
        update(sourceInputs.toByteArray())
        update(SmEditProjectFormat.encode(project).toByteArray())
        return digest.digest().joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    @Synchronized
    private fun getPreparedAsmBase(key: String): AsmCompiledBuildBase? =
        preparedAsmBases[key]?.copyForReuse()

    @Synchronized
    private fun putPreparedAsmBase(key: String, base: AsmCompiledBuildBase) {
        preparedAsmBases[key] = base.copyForReuse()
    }

    @Synchronized
    internal fun clearAsmBuildCachesForTests() {
        preparedAsmBases.clear()
        AsmProjectCompiler.clearBuildCacheForTests()
    }

    private fun exportCustomGfxPngs(
        project: SmEditProject,
        projectFilePath: String,
        romParser: RomParser,
        onLog: (String) -> Unit,
    ) {
        val gfx = project.customGfx
        val hasVar = gfx.varGfx.isNotEmpty()
        val hasCre = gfx.creGfx != null
        if (!hasVar && !hasCre) return

        val folder = File(ProjectPaths.forProject(projectFilePath).exports, "graphics")
        folder.mkdirs()
        val tileGraphics = TileGraphics(romParser)

        for ((tilesetId, base64) in gfx.varGfx) {
            if (!tileGraphics.loadTileset(tilesetId.toIntOrNull() ?: continue)) continue
            try {
                tileGraphics.applyCustomVarGfx(java.util.Base64.getDecoder().decode(base64))
                val result = tileGraphics.renderTileSheet(0, tileGraphics.getVarTileCount())
                if (result != null) {
                    val (pixels, width, height) = result
                    val out = File(folder, "ure_$tilesetId.png")
                    if (writePng(out.absolutePath, pixels, width, height)) onLog("Exported $out")
                }
            } catch (_: Exception) {
            }
        }

        if (hasCre && gfx.creGfx != null && tileGraphics.loadTileset(0)) {
            try {
                tileGraphics.applyCustomCreGfx(java.util.Base64.getDecoder().decode(gfx.creGfx))
                val result = tileGraphics.renderTileSheet(tileGraphics.getCreOffset(), tileGraphics.getCreTileCount())
                if (result != null) {
                    val (pixels, width, height) = result
                    val out = File(folder, "cre.png")
                    if (writePng(out.absolutePath, pixels, width, height)) onLog("Exported $out")
                }
            } catch (_: Exception) {
            }
        }
    }
}

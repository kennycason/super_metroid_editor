package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectPaths
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal enum class AsmWorkspaceKind { REFERENCE, PROJECT }

internal const val ASM_PROJECT_MODULE_PREFIX = "project/"
internal const val ASM_PROJECT_MODULE_ORDER_FILE = ".order"
internal const val ASM_PROJECT_MODULE_DISABLED_FILE = ".disabled"
private val ASM_PROJECT_MODULE_TITLE_REGEX = Regex("^;\\s*Project module:\\s*.*$", RegexOption.IGNORE_CASE)
private val ASM_PROJECT_MODULE_DESCRIPTION_REGEX = Regex("^;\\s*SMEDIT description:\\s*(.*)$", RegexOption.IGNORE_CASE)

internal fun projectModuleDescription(text: String): String? = text.lineSequence()
    .mapNotNull { ASM_PROJECT_MODULE_DESCRIPTION_REGEX.matchEntire(it.trimEnd()) }
    .firstOrNull()
    ?.groupValues
    ?.get(1)
    ?.trim()

internal data class AsmStarterModule(
    val fileName: String,
    val resourcePath: String,
)

internal val DEFAULT_ASM_STARTER_MODULES = listOf(
    AsmStarterModule(
        fileName = "objectives_pause_screen.asm",
        resourcePath = "/asm-templates/objectives_pause_screen.asm",
    ),
)

internal data class AsmProjectWorkspace(
    val root: File,
    val originalDirectory: File,
    val workingDirectory: File,
    val referenceWorkspace: AsmReferenceWorkspace,
    val modifiedFileIds: Set<String>,
    val projectModuleFileIds: List<String>,
    val disabledProjectModuleFileIds: Set<String>,
    val indexComplete: Boolean = true,
    val baselineRevision: String = "",
    val baselineId: String = "",
)

/** Minimal workspace view for compilation. It avoids rebuilding the browser's
 * complete parsed source index when Asar only needs the filesystem trees. */
internal data class AsmProjectBuildWorkspace(
    val root: File,
    val originalDirectory: File,
    val workingDirectory: File,
    val disabledProjectModuleFileIds: Set<String>,
    val baselineId: String = "",
    val assets: List<AsmAssetRange> = emptyList(),
)

internal data class AsmSavedSource(val modified: Boolean)

/**
 * Owns authored asm/ plus the generated smedit/asm/ cache. Initial creation is transactional and
 * never mutates the global downloaded reference. An immutable original tree is
 * retained beside the working tree so restore remains available offline.
 */
internal class AsmProjectWorkspaceRepository(
    private val assetRanges: List<AsmAssetRange> = AsmAssetManifest.loadBundled(),
    private val starterModules: List<AsmStarterModule> = DEFAULT_ASM_STARTER_MODULES,
    private val augmentation: AsmSourceAugmentation? = null,
    private val nativeSymbols: List<AsmNativeSymbol>? = null,
) {
    fun load(projectFilePath: String, fileIds: Set<String>? = null): AsmProjectWorkspace? = withBaselineUpdateLock(projectFilePath) {
        AsmBaselineUpdateTransaction.recover(projectFilePath)
        loadAfterRecovery(projectFilePath, fileIds)
    }

    private fun loadAfterRecovery(projectFilePath: String, fileIds: Set<String>?): AsmProjectWorkspace? {
        synchronizeProjectModules(projectFilePath)
        synchronizeSourceMirrors(projectFilePath, fileIds)
        val buildWorkspace = loadForBuild(projectFilePath) ?: return null
        val metadata = readMetadata(File(cacheRoot(buildWorkspace.root), METADATA_FILE)) ?: return null
        val workspace = referenceWorkspace(buildWorkspace.workingDirectory, metadata.reference, fileIds, buildWorkspace.assets)
        val projectModuleFileIds = readProjectModuleOrder(buildWorkspace.workingDirectory)
        val disabledProjectModuleFileIds = buildWorkspace.disabledProjectModuleFileIds
        return AsmProjectWorkspace(
            root = buildWorkspace.root,
            originalDirectory = buildWorkspace.originalDirectory,
            workingDirectory = buildWorkspace.workingDirectory,
            referenceWorkspace = workspace,
            modifiedFileIds = modifiedSourceFiles(
                buildWorkspace.originalDirectory,
                buildWorkspace.workingDirectory,
            ).filterNotTo(linkedSetOf(), disabledProjectModuleFileIds::contains),
            projectModuleFileIds = projectModuleFileIds,
            disabledProjectModuleFileIds = disabledProjectModuleFileIds,
            indexComplete = fileIds == null,
            baselineRevision = AsmSourceBaseline.load(buildWorkspace.root)?.augmentationRevision.orEmpty(),
            baselineId = buildWorkspace.baselineId,
        )
    }

    /** Enough source for room rendering/editing; the rest of the IDE index can follow independently. */
    fun loadRoomWorkspace(projectFilePath: String): AsmProjectWorkspace? =
        load(projectFilePath, setOf("main.asm", "macros.asm", PLM_SOURCE_FILE, FX_SOURCE_FILE, ENEMY_SOURCE_FILE))

    fun loadForBuild(projectFilePath: String): AsmProjectBuildWorkspace? = withSourceLock(projectFilePath) {
        AsmBaselineUpdateTransaction.recover(projectFilePath)
        loadForBuildAfterRecovery(projectFilePath)
    }

    private fun loadForBuildAfterRecovery(projectFilePath: String): AsmProjectBuildWorkspace? {
        val root = projectRoot(projectFilePath) ?: return null
        if (!root.isDirectory) return null
        val cache = cacheRoot(root)
        val metadata = readMetadata(File(cache, METADATA_FILE)) ?: return null
        if (metadata.layoutVersion != LAYOUT_VERSION) {
            return null
        }
        val baseline = AsmSourceBaseline.load(root) ?: return null
        require(baseline.id == metadata.baselineId && baseline.reference == metadata.reference) { "The frozen ASM baseline does not match this workspace" }
        val original = File(cache, ORIGINAL_DIRECTORY)
        val working = File(cache, WORKING_DIRECTORY)
        if (!File(original, "src/main.asm").isFile || !File(working, "src/main.asm").isFile) return null
        if (!baseline.matches(File(original, "src"))) return null
        if (!baseline.assets.all { File(working, "data/${it.path}").isFile }) return null
        val modulesDirectory = File(root, MODULES_DIRECTORY)
        val workingModules = File(working, "src/$ASM_PROJECT_MODULE_DIRECTORY")
        val moduleOrder = runCatching { readModuleOrder(modulesDirectory) }.getOrNull() ?: return null
        val workingOrder = runCatching { readWorkingModuleOrder(workingModules) }.getOrNull() ?: return null
        if (moduleOrder != workingOrder) return null
        val disabledModules = runCatching { readDisabledModules(modulesDirectory, moduleOrder) }.getOrNull() ?: return null
        val workingDisabledModules = runCatching {
            readDisabledModules(workingModules, workingOrder)
        }.getOrNull() ?: return null
        if (disabledModules != workingDisabledModules) return null
        if (moduleOrder.any { moduleName ->
                val canonical = File(modulesDirectory, moduleName)
                val mirror = File(workingModules, moduleName)
                !canonical.isFile || !mirror.isFile || !canonical.readBytes().contentEquals(mirror.readBytes())
            }
        ) return null
        return AsmProjectBuildWorkspace(
            root = root,
            originalDirectory = original,
            workingDirectory = working,
            disabledProjectModuleFileIds = disabledModules.mapTo(linkedSetOf()) {
                ASM_PROJECT_MODULE_PREFIX + it
            },
            baselineId = baseline.id,
            assets = baseline.assets,
        )
    }

    fun initialize(
        projectFilePath: String,
        reference: AsmReferenceWorkspace,
        onProgress: (String) -> Unit = {},
    ): AsmProjectWorkspace = initializeFromRom(projectFilePath, reference, null, onProgress = onProgress)

    fun initializeFromRom(
        projectFilePath: String,
        reference: AsmReferenceWorkspace,
        romBytes: ByteArray?,
        requireExistingBaseline: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): AsmProjectWorkspace {
        val target = requireNotNull(projectRoot(projectFilePath)) {
            "Save the SMEDIT project before enabling project ASM"
        }
        val romBody = romBytes?.let { if (it.size % 0x8000 == 0x200) it.copyOfRange(0x200, it.size) else it }
        require(romBody == null || AsmVerifiedCache.hash(romBody) == reference.metadata.romSha256) {
            "The supplied ROM does not match the ASM reference assets"
        }
        val priorMetadata = readMetadata(File(cacheRoot(target), METADATA_FILE))
        require(File(target, AsmSourceBaseline.DIRECTORY).exists() ||
            !requireExistingBaseline && priorMetadata?.baselineId.orEmpty().isBlank()) {
            "The project's frozen ASM baseline is missing. Restore asm/base from the project backup; repair will not replace it with the current app's source."
        }
        load(projectFilePath)?.let { return it }
        val cache = cacheRoot(target)
        val sidecar = cache.parentFile
        sidecar.mkdirs()
        val staging = File(sidecar, ".asm.installing-${UUID.randomUUID()}")
        requireManagedChild(sidecar, staging)
        try {
            onProgress("Preparing the project's frozen ASM baseline…")
            val baseline = AsmSourceBaseline.load(target) ?: AsmSourceBaseline.create(target, reference,
                augmentation ?: if (assetRanges == AsmAssetManifest.loadBundled()) AsmSourceAugmentation.bundled() else AsmSourceAugmentation.NONE,
                assetRanges, nativeSymbols)
            require(baseline.reference.romSha256 == reference.metadata.romSha256) { "The supplied ROM assets do not match the project's frozen ASM baseline" }
            val original = File(staging, ORIGINAL_DIRECTORY)
            baseline.extract(target, File(original, "src"))
            copyTree(File(reference.root, "data"), File(original, "data"))
            if (romBody != null) {
                for (asset in baseline.assets) {
                    val output = File(original, "data/${asset.path}")
                    output.parentFile.mkdirs()
                    output.writeBytes(romBody.copyOfRange(asset.pcOffset, asset.endExclusive))
                }
            }
            onProgress("Creating the editable ASM workspace…")
            val working = File(staging, WORKING_DIRECTORY)
            copyTree(File(original, "src"), File(working, "src"))
            copyTree(File(original, "data"), File(working, "data"))
            val existingOverrides = File(target, OVERRIDES_DIRECTORY)
            if (existingOverrides.isDirectory) {
                applySourceOverrides(existingOverrides, File(working, "src"))
            }
            val existingModules = File(target, MODULES_DIRECTORY)
            if (existingModules.isDirectory) {
                ensureProjectModuleDirectory(existingModules)
                syncProjectModules(
                    modulesDirectory = existingModules,
                    workingSourceRoot = File(working, "src"),
                )
            } else if (starterModules.isNotEmpty()) {
                installStarterModules(
                    modulesDirectory = File(staging, MODULES_DIRECTORY),
                    workingSourceRoot = File(working, "src"),
                    bindings = baseline.bindings,
                )
            }
            File(sidecar, ".gitignore").writeText("*\n")
            writeMetadata(
                File(staging, METADATA_FILE),
                AsmProjectMetadata(
                    layoutVersion = LAYOUT_VERSION,
                    createdAt = Instant.now().toString(),
                    reference = baseline.reference,
                    baselineId = baseline.id,
                ),
            )
            require(File(staging, "$WORKING_DIRECTORY/src/main.asm").isFile) {
                "Project ASM staging has no src/main.asm"
            }
            require(baseline.assets.all { File(working, "data/${it.path}").let { file -> file.isFile && file.length() == it.length.toLong() } }) {
                "Project ASM staging is missing one or more extracted assets"
            }
            // Parse before activation so an incomplete or malformed tree never
            // becomes the project's live workspace.
            referenceWorkspace(working, baseline.reference, ranges = baseline.assets)
            // Authored source survives cache rebuilds. Publish starter modules only
            // when this project has no modules; never replace user-authored files.
            val starters = File(staging, MODULES_DIRECTORY)
            if (starters.isDirectory) {
                target.mkdirs()
                require(!existingModules.exists()) { "ASM modules folder already exists; no authored files were replaced." }
                Files.move(starters.toPath(), existingModules.toPath())
            }
            target.mkdirs()
            activateDirectory(staging, cache)
            return checkNotNull(load(projectFilePath)) { "Project ASM workspace did not validate after creation" }
        } catch (problem: Exception) {
            deleteTree(sidecar, staging)
            throw problem
        }
    }

    fun saveSource(projectFilePath: String, fileId: String, text: String, expectedText: String? = null): AsmProjectWorkspace {
        persistSource(projectFilePath, fileId, text, expectedText)
        return checkNotNull(load(projectFilePath)) { "Project ASM workspace did not validate after saving $fileId" }
    }

    /** Durable save only. Browser indexing/validation is scheduled separately by the UI. */
    fun persistSource(projectFilePath: String, fileId: String, text: String, expectedText: String? = null): AsmSavedSource = withSourceLock(projectFilePath) {
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        val sourceRoot = File(current.workingDirectory, "src")
        val target = resolveManagedSource(sourceRoot, fileId)
        require(target.isFile) { "ASM source does not exist: $fileId" }
        require(expectedText == null || target.readText() == expectedText) {
            "$fileId changed on disk since it was opened. No source was overwritten; reconcile the external edit first."
        }
        if (isProjectModuleFileId(fileId)) {
            val module = resolveProjectModule(File(current.root, MODULES_DIRECTORY), fileId)
            require(module.isFile) { "Project ASM module does not exist: $fileId" }
            require(expectedText == null || module.readText() == expectedText) {
                "$fileId changed outside SMEDIT. No source was overwritten."
            }
            writeTextAtomically(module, text)
            writeTextAtomically(target, text)
            return@withSourceLock AsmSavedSource(modified = true)
        }
        val original = resolveManagedSource(File(current.originalDirectory, "src"), fileId)
        val override = resolveManagedSource(File(current.root, "$OVERRIDES_DIRECTORY/src"), fileId)
        require(expectedText == null || (if (override.isFile) override.readText() else original.readText()) == expectedText) {
            "$fileId's authored override changed outside SMEDIT. No source was overwritten."
        }
        writeTextAtomically(target, text)
        val modified = !original.readBytes().contentEquals(text.toByteArray())
        if (!modified) {
            Files.deleteIfExists(override.toPath())
            deleteEmptyOverrideParents(override.parentFile, File(current.root, OVERRIDES_DIRECTORY))
        } else {
            writeTextAtomically(override, text)
        }
        AsmSavedSource(modified)
    }

    fun restoreSource(projectFilePath: String, fileId: String): AsmProjectWorkspace {
        require(!isProjectModuleFileId(fileId)) {
            "Project modules have no immutable original; delete the module instead"
        }
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        val originalRoot = File(current.originalDirectory, "src")
        val workingRoot = File(current.workingDirectory, "src")
        val original = resolveManagedSource(originalRoot, fileId)
        val target = resolveManagedSource(workingRoot, fileId)
        require(original.isFile && target.isFile) { "ASM source does not exist: $fileId" }
        writeBytesAtomically(target, original.readBytes())
        val override = resolveManagedSource(File(current.root, "$OVERRIDES_DIRECTORY/src"), fileId)
        Files.deleteIfExists(override.toPath())
        deleteEmptyOverrideParents(override.parentFile, File(current.root, OVERRIDES_DIRECTORY))
        return checkNotNull(load(projectFilePath)) { "Project ASM workspace did not validate after restoring $fileId" }
    }

    fun rootFor(projectFilePath: String): File? = projectRoot(projectFilePath)

    fun cacheFor(projectFilePath: String): File? = projectRoot(projectFilePath)?.let(::cacheRoot)

    /**
     * Saved project source edits are mirrored as small override files. Reading
     * this ledger does not require parsing the generated source/data trees, so
     * export-mode validation stays cheap and also works before the workspace UI
     * has been opened.
     */
    fun sourceOverrideFileIds(projectFilePath: String): Set<String> {
        val root = projectRoot(projectFilePath) ?: return emptySet()
        val sourceRoot = File(root, "$OVERRIDES_DIRECTORY/src")
        val overrides = if (!sourceRoot.isDirectory) emptySequence() else sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("asm", ignoreCase = true) }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
        val modulesRoot = File(root, MODULES_DIRECTORY)
        val modules = if (!modulesRoot.isDirectory) {
            emptySequence()
        } else {
            val enabledNames = runCatching {
                val order = readModuleOrder(modulesRoot)
                val disabled = readDisabledModules(modulesRoot, order)
                order.filterNot(disabled::contains)
            }.getOrElse {
                // A malformed manifest must never make Loaded ROM mode silently
                // ignore authored ASM. Conservatively treat every module as active.
                modulesRoot.listFiles().orEmpty()
                    .filter { it.isFile && it.extension.equals("asm", ignoreCase = true) }
                    .map(File::getName)
            }
            enabledNames.asSequence().map { ASM_PROJECT_MODULE_PREFIX + it }
        }
        return (overrides + modules).toCollection(linkedSetOf())
    }

    fun createModule(
        projectFilePath: String,
        requestedName: String,
        description: String = "",
        initialSource: String? = null,
    ): Pair<AsmProjectWorkspace, String> {
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        val modulesDirectory = File(current.root, MODULES_DIRECTORY)
        ensureProjectModuleDirectory(modulesDirectory)
        val fileName = normalizeProjectModuleName(requestedName)
        val target = resolveProjectModule(modulesDirectory, ASM_PROJECT_MODULE_PREFIX + fileName)
        require(!target.exists()) { "A project module named $fileName already exists" }
        val order = readModuleOrder(modulesDirectory).toMutableList()
        val displayName = fileName.removeSuffix(".asm").replace('_', ' ')
        writeTextAtomically(target, initialSource ?: projectModuleTemplate(displayName, normalizeProjectModuleDescription(description)))
        order.add(fileName)
        writeModuleOrder(modulesDirectory, order)
        syncProjectModules(modulesDirectory, File(current.workingDirectory, "src"))
        val fileId = ASM_PROJECT_MODULE_PREFIX + fileName
        return checkNotNull(load(projectFilePath)) {
            "Project ASM workspace did not validate after creating $fileName"
        } to fileId
    }

    fun renameModule(projectFilePath: String, fileId: String, requestedName: String): Pair<AsmProjectWorkspace, String> {
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        val source = resolveProjectModule(File(current.root, MODULES_DIRECTORY), fileId)
        return updateModuleSettings(
            projectFilePath = projectFilePath,
            fileId = fileId,
            requestedName = requestedName,
            description = projectModuleDescription(source.readText()).orEmpty(),
        )
    }

    fun updateModuleSettings(
        projectFilePath: String,
        fileId: String,
        requestedName: String,
        description: String,
    ): Pair<AsmProjectWorkspace, String> {
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        require(isProjectModuleFileId(fileId)) { "Not a project ASM module: $fileId" }
        val modulesDirectory = File(current.root, MODULES_DIRECTORY)
        val oldName = fileId.removePrefix(ASM_PROJECT_MODULE_PREFIX)
        val newName = normalizeProjectModuleName(requestedName)
        val source = resolveProjectModule(modulesDirectory, fileId)
        val target = resolveProjectModule(modulesDirectory, ASM_PROJECT_MODULE_PREFIX + newName)
        require(source.isFile) { "Project ASM module does not exist: $fileId" }
        require(oldName == newName || !target.exists()) { "A project module named $newName already exists" }
        val order = readModuleOrder(modulesDirectory).map { if (it == oldName) newName else it }
        val disabled = readDisabledModules(modulesDirectory, readModuleOrder(modulesDirectory))
            .mapTo(linkedSetOf()) { if (it == oldName) newName else it }
        val displayName = newName.removeSuffix(".asm").replace('_', ' ')
        val updatedSource = updateProjectModuleMetadata(
            text = source.readText(),
            displayName = displayName,
            description = normalizeProjectModuleDescription(description),
        )
        if (oldName == newName) {
            writeTextAtomically(source, updatedSource)
        } else {
            writeTextAtomically(target, updatedSource)
            Files.delete(source.toPath())
        }
        writeModuleOrder(modulesDirectory, order)
        writeDisabledModules(modulesDirectory, disabled)
        syncProjectModules(modulesDirectory, File(current.workingDirectory, "src"))
        val newFileId = ASM_PROJECT_MODULE_PREFIX + newName
        return checkNotNull(load(projectFilePath)) {
            "Project ASM workspace did not validate after updating $oldName"
        } to newFileId
    }

    fun deleteModule(projectFilePath: String, fileId: String): AsmProjectWorkspace {
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        require(isProjectModuleFileId(fileId)) { "Not a project ASM module: $fileId" }
        val modulesDirectory = File(current.root, MODULES_DIRECTORY)
        val fileName = fileId.removePrefix(ASM_PROJECT_MODULE_PREFIX)
        val target = resolveProjectModule(modulesDirectory, fileId)
        require(target.isFile) { "Project ASM module does not exist: $fileId" }
        val existingOrder = readModuleOrder(modulesDirectory)
        val order = existingOrder.filterNot { it == fileName }
        val disabled = readDisabledModules(modulesDirectory, existingOrder).filterNotTo(linkedSetOf()) {
            it == fileName
        }
        Files.delete(target.toPath())
        if (order.isEmpty()) {
            Files.deleteIfExists(File(modulesDirectory, ASM_PROJECT_MODULE_ORDER_FILE).toPath())
            Files.deleteIfExists(File(modulesDirectory, ASM_PROJECT_MODULE_DISABLED_FILE).toPath())
            modulesDirectory.delete()
        } else {
            writeModuleOrder(modulesDirectory, order)
            writeDisabledModules(modulesDirectory, disabled)
        }
        syncProjectModules(modulesDirectory, File(current.workingDirectory, "src"))
        return checkNotNull(load(projectFilePath)) {
            "Project ASM workspace did not validate after deleting $fileName"
        }
    }

    fun moveModule(projectFilePath: String, fileId: String, offset: Int): AsmProjectWorkspace {
        require(offset == -1 || offset == 1) { "Module order offset must be -1 or 1" }
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        require(isProjectModuleFileId(fileId)) { "Not a project ASM module: $fileId" }
        val modulesDirectory = File(current.root, MODULES_DIRECTORY)
        val fileName = fileId.removePrefix(ASM_PROJECT_MODULE_PREFIX)
        val order = readModuleOrder(modulesDirectory).toMutableList()
        val index = order.indexOf(fileName)
        require(index >= 0) { "Project ASM module is missing from its order: $fileId" }
        val destination = (index + offset).coerceIn(order.indices)
        if (destination != index) {
            order[index] = order[destination]
            order[destination] = fileName
            writeModuleOrder(modulesDirectory, order)
            syncProjectModules(modulesDirectory, File(current.workingDirectory, "src"))
        }
        return checkNotNull(load(projectFilePath)) { "Project ASM workspace did not validate after reordering $fileName" }
    }

    fun setModuleEnabled(projectFilePath: String, fileId: String, enabled: Boolean): AsmProjectWorkspace {
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        require(isProjectModuleFileId(fileId)) { "Not a project ASM module: $fileId" }
        val modulesDirectory = File(current.root, MODULES_DIRECTORY)
        val fileName = fileId.removePrefix(ASM_PROJECT_MODULE_PREFIX)
        val order = readModuleOrder(modulesDirectory)
        require(fileName in order) { "Project ASM module is missing from its order: $fileId" }
        val disabled = readDisabledModules(modulesDirectory, order).toMutableSet()
        if (enabled) disabled.remove(fileName) else disabled.add(fileName)
        writeDisabledModules(modulesDirectory, order.filterTo(linkedSetOf(), disabled::contains))
        syncProjectModules(modulesDirectory, File(current.workingDirectory, "src"))
        return checkNotNull(load(projectFilePath)) {
            "Project ASM workspace did not validate after ${if (enabled) "enabling" else "disabling"} $fileName"
        }
    }

    private fun referenceWorkspace(root: File, metadata: AsmReferenceMetadata, fileIds: Set<String>? = null,
                                   ranges: List<AsmAssetRange> = assetRanges): AsmReferenceWorkspace {
        val index = AsmSourceParser().parse(File(root, "src"), File(root, "data"), ranges, fileIds)
        return AsmReferenceWorkspace(root, metadata, index)
    }

    private fun synchronizeProjectModules(projectFilePath: String) = withSourceLock(projectFilePath) {
        val root = projectRoot(projectFilePath) ?: return@withSourceLock
        val workingSourceRoot = File(cacheRoot(root), "$WORKING_DIRECTORY/src")
        if (!workingSourceRoot.isDirectory) return@withSourceLock
        val modulesDirectory = File(root, MODULES_DIRECTORY)
        // The cache is never an alternate authority. In particular, deleting an
        // authored module must not resurrect it from an old generated mirror.
        syncProjectModules(modulesDirectory, workingSourceRoot)
    }

    /** Authored overrides/original snapshot are authority; generated mirrors are recoverable caches. */
    private fun synchronizeSourceMirrors(projectFilePath: String, fileIds: Set<String>?) = withSourceLock(projectFilePath) {
        val root = projectRoot(projectFilePath) ?: return@withSourceLock
        val cache = cacheRoot(root)
        val original = File(cache, "$ORIGINAL_DIRECTORY/src")
        val working = File(cache, "$WORKING_DIRECTORY/src")
        if (!original.isDirectory || !working.isDirectory) return@withSourceLock
        original.walkTopDown().filter { it.isFile && it.extension.equals("asm", true) }.forEach { source ->
            val id = source.relativeTo(original).invariantSeparatorsPath
            if (fileIds != null && id !in fileIds) return@forEach
            val target = resolveManagedSource(working, id)
            val override = resolveManagedSource(File(root, "$OVERRIDES_DIRECTORY/src"), id)
            val authoritative = if (override.isFile) override else source
            val expected = authoritative.readBytes()
            val previous = target.takeIf(File::isFile)?.readBytes()
            if (previous != null && previous.contentEquals(expected)) return@forEach
            if (previous != null) {
                val recoveryRoot = File(cache, "recovery")
                val recovery = resolveManagedSource(recoveryRoot, "$id.${AsmVerifiedCache.hash(previous)}.asm")
                if (!recovery.exists()) writeBytesAtomically(recovery, previous)
            }
            writeBytesAtomically(target, expected)
        }
    }

    private fun ensureProjectModuleDirectory(modulesDirectory: File) {
        modulesDirectory.mkdirs()
        val order = File(modulesDirectory, ASM_PROJECT_MODULE_ORDER_FILE)
        if (!order.exists()) {
            val discovered = modulesDirectory.listFiles().orEmpty()
                .filter { it.isFile && it.extension.equals("asm", ignoreCase = true) }
                .map(File::getName)
                .sorted()
            writeModuleOrder(modulesDirectory, discovered)
        }
        readModuleOrder(modulesDirectory)
        readDisabledModules(modulesDirectory, readModuleOrder(modulesDirectory))
    }

    private fun installStarterModules(modulesDirectory: File, workingSourceRoot: File, bindings: AsmSourceBindings) {
        require(starterModules.map(AsmStarterModule::fileName).distinct().size == starterModules.size) {
            "Bundled ASM starter modules contain duplicate names"
        }
        modulesDirectory.mkdirs()
        starterModules.forEach { starter ->
            require(normalizeProjectModuleName(starter.fileName) == starter.fileName) {
                "Unsafe bundled ASM starter module name: ${starter.fileName}"
            }
            val text = checkNotNull(javaClass.getResourceAsStream(starter.resourcePath)) {
                "Missing bundled ASM starter module ${starter.resourcePath}"
            }.bufferedReader().use { it.readText() }
            writeTextAtomically(File(modulesDirectory, starter.fileName), bindings.template(text))
        }
        val order = starterModules.map(AsmStarterModule::fileName)
        writeModuleOrder(modulesDirectory, order)
        // Starter modules are readable references, but a new workspace remains
        // byte-identical until the user explicitly enables one.
        writeDisabledModules(modulesDirectory, order)
        syncProjectModules(modulesDirectory, workingSourceRoot)
    }

    private fun syncProjectModules(modulesDirectory: File, workingSourceRoot: File) {
        val projectSource = File(workingSourceRoot, ASM_PROJECT_MODULE_DIRECTORY)
        if (!modulesDirectory.isDirectory) {
            if (projectSource.exists()) deleteTree(workingSourceRoot, projectSource)
            return
        }
        ensureProjectModuleDirectory(modulesDirectory)
        if (projectSource.exists()) deleteTree(workingSourceRoot, projectSource)
        projectSource.mkdirs()
        val order = readModuleOrder(modulesDirectory)
        order.forEach { fileName ->
            Files.copy(
                File(modulesDirectory, fileName).toPath(),
                File(projectSource, fileName).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        writeTextAtomically(
            File(projectSource, ASM_PROJECT_MODULE_ORDER_FILE),
            order.joinToString("\n", postfix = if (order.isEmpty()) "" else "\n"),
        )
        val disabled = readDisabledModules(modulesDirectory, order)
        writeDisabledModules(projectSource, disabled)
    }

    private fun readProjectModuleOrder(workingDirectory: File): List<String> =
        readWorkingModuleOrder(File(workingDirectory, "src/$ASM_PROJECT_MODULE_DIRECTORY"))
            .map { ASM_PROJECT_MODULE_PREFIX + it }

    private fun readWorkingModuleOrder(directory: File): List<String> {
        if (!directory.isDirectory) return emptyList()
        val orderFile = File(directory, ASM_PROJECT_MODULE_ORDER_FILE)
        if (!orderFile.isFile) return emptyList()
        val order = orderFile.readLines().map(String::trim).filter(String::isNotEmpty)
        validateModuleOrder(directory, order)
        return order
    }

    private fun readModuleOrder(modulesDirectory: File): List<String> {
        if (!modulesDirectory.isDirectory) return emptyList()
        val orderFile = File(modulesDirectory, ASM_PROJECT_MODULE_ORDER_FILE)
        if (!orderFile.isFile) return emptyList()
        val order = orderFile.readLines().map(String::trim).filter(String::isNotEmpty)
        validateModuleOrder(modulesDirectory, order)
        return order
    }

    private fun readDisabledModules(directory: File, order: List<String>): Set<String> {
        if (!directory.isDirectory) return emptySet()
        val disabledFile = File(directory, ASM_PROJECT_MODULE_DISABLED_FILE)
        if (!disabledFile.isFile) return emptySet()
        val disabled = disabledFile.readLines().map(String::trim).filter(String::isNotEmpty)
        require(disabled.distinct().size == disabled.size) { "Disabled project ASM modules contain duplicates" }
        require(disabled.all(order::contains)) { "Disabled project ASM modules reference an unknown module" }
        return disabled.toCollection(linkedSetOf())
    }

    private fun validateModuleOrder(directory: File, order: List<String>) {
        require(order.distinct().size == order.size) { "Project ASM module order contains duplicates" }
        order.forEach { fileName ->
            require(normalizeProjectModuleName(fileName) == fileName) { "Unsafe project ASM module name: $fileName" }
            require(File(directory, fileName).isFile) { "Project ASM module order references missing $fileName" }
        }
        val discovered = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("asm", ignoreCase = true) }
            .map(File::getName)
            .toSet()
        require(discovered == order.toSet()) {
            "Project ASM module order does not match the module files"
        }
    }

    private fun writeModuleOrder(modulesDirectory: File, order: List<String>) {
        writeTextAtomically(
            File(modulesDirectory, ASM_PROJECT_MODULE_ORDER_FILE),
            order.joinToString("\n", postfix = if (order.isEmpty()) "" else "\n"),
        )
    }

    private fun writeDisabledModules(directory: File, disabled: Collection<String>) {
        val target = File(directory, ASM_PROJECT_MODULE_DISABLED_FILE)
        if (disabled.isEmpty()) {
            Files.deleteIfExists(target.toPath())
        } else {
            writeTextAtomically(target, disabled.joinToString("\n", postfix = "\n"))
        }
    }

    private fun normalizeProjectModuleName(requestedName: String): String {
        val requested = requestedName.trim()
        val stem = (if (requested.endsWith(".asm", ignoreCase = true)) requested.dropLast(4) else requested).trim()
            .replace(Regex("[^A-Za-z0-9_-]+"), "_")
            .trim('_', '-')
        require(stem.isNotBlank()) { "Enter a module name using letters, numbers, spaces, _ or -" }
        require(stem.length <= 80) { "Project ASM module names must be 80 characters or fewer" }
        return "$stem.asm"
    }

    private fun normalizeProjectModuleDescription(requestedDescription: String): String {
        val description = requestedDescription.lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        require(description.length <= 240) { "Project ASM module descriptions must be 240 characters or fewer" }
        return description
    }

    private fun updateProjectModuleMetadata(text: String, displayName: String, description: String): String {
        val newline = if ("\r\n" in text) "\r\n" else "\n"
        val lines = text.split(Regex("\\r?\\n")).toMutableList()
        val title = "; Project module: $displayName"
        val titleIndex = lines.indexOfFirst { ASM_PROJECT_MODULE_TITLE_REGEX.matches(it.trimEnd()) }
            .also { index ->
                if (index >= 0) lines[index] = title else lines.add(0, title)
            }
            .let { if (it >= 0) it else 0 }
        val descriptionLine = "; SMEDIT description: $description"
        val descriptionIndex = lines.indexOfFirst {
            ASM_PROJECT_MODULE_DESCRIPTION_REGEX.matches(it.trimEnd())
        }
        if (descriptionIndex >= 0) lines[descriptionIndex] = descriptionLine
        else lines.add(titleIndex + 1, descriptionLine)
        return lines.joinToString(newline)
    }

    private fun resolveProjectModule(modulesDirectory: File, fileId: String): File {
        require(isProjectModuleFileId(fileId)) { "Invalid project ASM module path: $fileId" }
        val relativeName = fileId.removePrefix(ASM_PROJECT_MODULE_PREFIX)
        require('/' !in relativeName && '\\' !in relativeName) { "Project ASM modules cannot contain folders" }
        val resolved = File(modulesDirectory, relativeName).canonicalFile
        require(resolved.parentFile == modulesDirectory.canonicalFile && resolved.extension.equals("asm", true)) {
            "Unsafe project ASM module path: $fileId"
        }
        return resolved
    }

    private fun isProjectModuleFileId(fileId: String): Boolean =
        fileId.startsWith(ASM_PROJECT_MODULE_PREFIX) && fileId.endsWith(".asm", ignoreCase = true)

    private fun projectModuleTemplate(displayName: String, description: String): String = """
        ; Project module: $displayName
        ; SMEDIT description: $description
        ; Included by SMEDIT after the vanilla source banks and before generated patch modules.
        ; Add an org/free-space target, labels, and code here. Build errors link back to this file.

    """.trimIndent()

    private fun projectRoot(projectFilePath: String): File? {
        if (projectFilePath.isBlank()) return null
        return ProjectPaths.forProject(projectFilePath).asm
    }

    private fun cacheRoot(asmRoot: File): File = ProjectPaths.asmCacheIn(asmRoot.parentFile)

    private inline fun <T> withSourceLock(projectFilePath: String, action: () -> T): T {
        val key = projectRoot(projectFilePath)?.canonicalPath ?: projectFilePath
        return synchronized(SOURCE_LOCKS.computeIfAbsent(key) { Any() }) { action() }
    }

    internal fun <T> withBaselineUpdateLock(projectFilePath: String, action: () -> T): T = withSourceLock(projectFilePath, action)

    private fun modifiedSourceFiles(original: File, working: File): Set<String> {
        val originalRoot = File(original, "src")
        val workingRoot = File(working, "src")
        if (!originalRoot.isDirectory || !workingRoot.isDirectory) return emptySet()
        val originals = originalRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("asm", ignoreCase = true) }
            .associateBy { it.relativeTo(originalRoot).invariantSeparatorsPath }
        val workings = workingRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("asm", ignoreCase = true) }
            .associateBy { it.relativeTo(workingRoot).invariantSeparatorsPath }
        return (originals.keys + workings.keys).filterTo(linkedSetOf()) { path ->
            val baseline = originals[path]
            val edited = workings[path]
            baseline == null || edited == null || !baseline.readBytes().contentEquals(edited.readBytes())
        }
    }

    private fun resolveManagedSource(root: File, fileId: String): File {
        require(fileId.isNotBlank() && !fileId.contains('\\')) { "Invalid ASM source path: $fileId" }
        val normalizedRoot = root.canonicalFile.toPath()
        val resolved = File(root, fileId).canonicalFile
        require(resolved.toPath().startsWith(normalizedRoot) && resolved.extension.equals("asm", true)) {
            "Unsafe ASM source path: $fileId"
        }
        return resolved
    }

    private fun copyTree(source: File, destination: File) {
        require(source.isDirectory) { "ASM reference directory is missing: $source" }
        val sourcePath = source.canonicalFile.toPath()
        val destinationPath = destination.toPath()
        Files.walk(sourcePath).use { paths ->
            paths.forEach { input ->
                val output = destinationPath.resolve(sourcePath.relativize(input).toString()).normalize()
                require(output.startsWith(destinationPath.normalize())) { "Unsafe ASM copy destination: $output" }
                if (Files.isDirectory(input)) Files.createDirectories(output)
                else {
                    Files.createDirectories(output.parent)
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    private fun applySourceOverrides(overridesRoot: File, workingSourceRoot: File) {
        val overrideSourceRoot = File(overridesRoot, "src")
        if (!overrideSourceRoot.isDirectory) return
        overrideSourceRoot.walkTopDown().filter { it.isFile }.forEach { override ->
            val fileId = override.relativeTo(overrideSourceRoot).invariantSeparatorsPath
            val target = resolveManagedSource(workingSourceRoot, fileId)
            require(target.isFile) { "Project ASM override has no pinned source file: $fileId" }
            writeBytesAtomically(target, override.readBytes())
        }
    }

    private fun writeTextAtomically(target: File, text: String) = writeBytesAtomically(target, text.toByteArray())

    private fun writeBytesAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            Files.write(
                temporary.toPath(),
                bytes,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun deleteEmptyOverrideParents(start: File?, stop: File) {
        var directory = start
        val boundary = stop.canonicalFile
        while (directory != null && directory.canonicalFile != boundary) {
            if (directory.listFiles()?.isNotEmpty() == true) break
            if (!directory.delete()) break
            directory = directory.parentFile
        }
    }

    private fun moveDirectory(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun activateDirectory(staging: File, target: File) {
        val sidecar = target.parentFile
        requireManagedChild(sidecar, staging)
        requireManagedChild(sidecar, target)
        if (!target.exists()) {
            moveDirectory(staging, target)
            return
        }
        // A project marked ASM-enabled can be moved without its complete
        // sidecar. Repair preserves any partial tree instead of deleting it.
        val backup = File(sidecar, ".asm.incomplete-backup-${UUID.randomUUID()}")
        requireManagedChild(sidecar, backup)
        moveDirectory(target, backup)
        try {
            moveDirectory(staging, target)
        } catch (problem: Exception) {
            if (!target.exists() && backup.exists()) runCatching { moveDirectory(backup, target) }
            throw problem
        }
    }

    private fun deleteTree(managedRoot: File, target: File) {
        if (!target.exists()) return
        requireManagedChild(managedRoot, target)
        Files.walk(target.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun requireManagedChild(root: File, child: File) {
        val rootPath = root.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        require(childPath.startsWith(rootPath) && childPath != rootPath) { "Refusing unmanaged ASM path: $child" }
    }

    private fun writeMetadata(file: File, metadata: AsmProjectMetadata) {
        val properties = Properties().apply {
            setProperty("layoutVersion", metadata.layoutVersion.toString())
            setProperty("baselineId", metadata.baselineId)
            setProperty("createdAt", metadata.createdAt)
            setProperty("repositoryUrl", metadata.reference.repositoryUrl)
            setProperty("commit", metadata.reference.commit)
            setProperty("installedAt", metadata.reference.installedAt)
            setProperty("romName", metadata.reference.romName)
            setProperty("romSha256", metadata.reference.romSha256)
            setProperty("romSize", metadata.reference.romSize.toString())
            setProperty("assetCount", metadata.reference.assetCount.toString())
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { properties.store(it, "SMEDIT project-owned ASM workspace") }
    }

    private fun readMetadata(file: File): AsmProjectMetadata? = runCatching {
        if (!file.isFile) return null
        val properties = Properties().apply { file.inputStream().use(::load) }
        AsmProjectMetadata(
            layoutVersion = properties.getProperty("layoutVersion").toInt(),
            baselineId = properties.getProperty("baselineId", ""),
            createdAt = properties.getProperty("createdAt"),
            reference = AsmReferenceMetadata(
                repositoryUrl = properties.getProperty("repositoryUrl"),
                commit = properties.getProperty("commit"),
                installedAt = properties.getProperty("installedAt"),
                romName = properties.getProperty("romName"),
                romSha256 = properties.getProperty("romSha256"),
                romSize = properties.getProperty("romSize").toInt(),
                assetCount = properties.getProperty("assetCount").toInt(),
            ),
        )
    }.getOrNull()

    private data class AsmProjectMetadata(
        val layoutVersion: Int,
        val createdAt: String,
        val reference: AsmReferenceMetadata,
        val baselineId: String,
    )

    private companion object {
        val SOURCE_LOCKS = ConcurrentHashMap<String, Any>()
        const val LAYOUT_VERSION = 2
        const val METADATA_FILE = ".smedit-project-asm.properties"
        const val ORIGINAL_DIRECTORY = "original"
        const val WORKING_DIRECTORY = "workspace"
        const val OVERRIDES_DIRECTORY = "overrides"
        const val MODULES_DIRECTORY = "modules"
        const val ASM_PROJECT_MODULE_DIRECTORY = "project"
    }
}

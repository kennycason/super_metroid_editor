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
    AsmStarterModule(
        fileName = "checked_hook_demo.asm",
        resourcePath = "/asm-templates/checked_hook_demo.asm",
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
internal data class AsmSavedAsset(val modified: Boolean)

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
    private data class ReadWorkspaceCache(
        val projectFilePath: String,
        val baselineModified: Long,
        val baselineLength: Long,
        val metadataModified: Long,
        val metadataLength: Long,
        val workspace: AsmProjectBuildWorkspace,
    )

    @Volatile private var readWorkspaceCache: ReadWorkspaceCache? = null
    private data class PointerIndexCache(
        val sourceFingerprint: String,
        val index: AsmManagedAssets.PointerIndex,
    )
    @Volatile private var pointerIndexCache: PointerIndexCache? = null

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
        synchronizeAssetMirrorsAfterRecovery(projectFilePath)
        synchronizeManagedAssetMirrors(projectFilePath)
        synchronizeBundledStarterModules(projectFilePath)
        loadForBuildAfterRecovery(projectFilePath)
    }

    /** Read-only visual lenses share one already-verified filesystem view.
     * Export/build entry points still use [loadForBuild] and repeat complete
     * recovery and baseline validation. */
    fun loadForRead(projectFilePath: String): AsmProjectBuildWorkspace? {
        val root = projectRoot(projectFilePath) ?: return null
        val baseline = File(root, "${AsmSourceBaseline.DIRECTORY}/manifest.json")
        val metadata = File(cacheRoot(root), METADATA_FILE)
        val cached = readWorkspaceCache
        if (cached != null && cached.projectFilePath == File(projectFilePath).absolutePath &&
            cached.baselineModified == baseline.lastModified() && cached.baselineLength == baseline.length() &&
            cached.metadataModified == metadata.lastModified() && cached.metadataLength == metadata.length() &&
            File(cached.workspace.originalDirectory, "src/main.asm").isFile &&
            File(cached.workspace.workingDirectory, "src/main.asm").isFile
        ) return cached.workspace
        return loadForBuild(projectFilePath)?.also { workspace ->
            readWorkspaceCache = ReadWorkspaceCache(
                File(projectFilePath).absolutePath,
                baseline.lastModified(),
                baseline.length(),
                metadata.lastModified(),
                metadata.length(),
                workspace,
            )
        }
    }

    /** Share one conservative pointer-consumer index across the graphics,
     * palette and cinematic lenses for the same exact saved source tree. */
    fun pointerBindings(
        sources: Map<String, String>,
        sourceLabels: Set<String>,
    ): Map<String, List<AsmManagedPointerBinding>?> {
        if (sourceLabels.isEmpty()) return emptyMap()
        val fingerprint = AsmVerifiedCache.fingerprint(sources)
        val cached = pointerIndexCache
        val index = if (cached?.sourceFingerprint == fingerprint) cached.index else {
            AsmManagedAssets.PointerIndex(sources).also {
                pointerIndexCache = PointerIndexCache(fingerprint, it)
            }
        }
        return index.bindings(sourceLabels)
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
        if (!baseline.assets.all { asset ->
                File(original, "data/${asset.path}").let { it.isFile && it.length() == asset.length.toLong() } &&
                    File(working, "data/${asset.path}").let { it.isFile && it.length() == asset.length.toLong() }
            }
        ) return null
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
                applyAssetOverrides(existingOverrides, File(working, "data"), baseline.assets)
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

    fun restoreSource(
        projectFilePath: String,
        fileId: String,
        expectedText: String? = null,
        expectedOriginalText: String? = null,
    ): AsmProjectWorkspace = withSourceLock(projectFilePath) {
        require(!isProjectModuleFileId(fileId)) {
            "Project modules have no immutable original; delete the module instead"
        }
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        val managedOwners = AsmManagedAssets.load(current.root).assets.filter { it.sourceFileId == fileId }
        require(managedOwners.isEmpty()) {
            "Restore ${managedOwners.size} managed asset transaction${if (managedOwners.size == 1) "" else "s"} before restoring $fileId."
        }
        val originalRoot = File(current.originalDirectory, "src")
        val workingRoot = File(current.workingDirectory, "src")
        val original = resolveManagedSource(originalRoot, fileId)
        val target = resolveManagedSource(workingRoot, fileId)
        require(original.isFile && target.isFile) { "ASM source does not exist: $fileId" }
        require(expectedOriginalText == null || original.readText() == expectedOriginalText) {
            "$fileId's immutable baseline changed after review. Nothing was restored."
        }
        val override = resolveManagedSource(File(current.root, "$OVERRIDES_DIRECTORY/src"), fileId)
        val authoritative = if (override.isFile) override else original
        require(expectedText == null || target.readText() == expectedText) {
            "$fileId changed in the generated ASM workspace. No source was overwritten."
        }
        require(expectedText == null || authoritative.readText() == expectedText) {
            "$fileId's authored override changed outside SMEDIT. No source was overwritten."
        }
        val previousTarget = target.readBytes()
        val previousOverride = override.takeIf(File::isFile)?.readBytes()
        try {
            writeBytesAtomically(target, original.readBytes())
            Files.deleteIfExists(override.toPath())
            deleteEmptyOverrideParents(override.parentFile, File(current.root, OVERRIDES_DIRECTORY))
            checkNotNull(load(projectFilePath)) { "Project ASM workspace did not validate after restoring $fileId" }
        } catch (problem: Exception) {
            writeBytesAtomically(target, previousTarget)
            restoreOptionalFile(override, previousOverride)
            throw problem
        }
    }

    /**
     * Saves a complete extracted `incbin` asset as portable project authority.
     * Assets keep their frozen manifest path and byte length; codecs must prove
     * that constraint before publishing a visual edit here.
     */
    fun saveAsset(
        projectFilePath: String,
        assetPath: String,
        bytes: ByteArray,
        expectedBytes: ByteArray? = null,
    ): AsmSavedAsset = withSourceLock(projectFilePath) {
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        val asset = requireAsset(current.assets, assetPath)
        require(bytes.size == asset.length) {
            "Source asset $assetPath must remain exactly ${asset.length} bytes; received ${bytes.size}"
        }
        val original = resolveManagedAsset(File(current.originalDirectory, "data"), assetPath, current.assets)
        val target = resolveManagedAsset(File(current.workingDirectory, "data"), assetPath, current.assets)
        val override = resolveManagedAsset(File(current.root, "$OVERRIDES_DIRECTORY/data"), assetPath, current.assets)
        require(original.isFile && original.length() == asset.length.toLong() &&
            target.isFile && target.length() == asset.length.toLong()) {
            "Source asset $assetPath is missing or does not match its frozen manifest"
        }
        val authoritative = if (override.isFile) override else original
        require(expectedBytes == null || target.readBytes().contentEquals(expectedBytes)) {
            "$assetPath changed in the generated ASM workspace. No asset was overwritten."
        }
        require(expectedBytes == null || authoritative.readBytes().contentEquals(expectedBytes)) {
            "$assetPath's authored data override changed outside SMEDIT. No asset was overwritten."
        }
        writeBytesAtomically(target, bytes)
        val modified = !original.readBytes().contentEquals(bytes)
        if (!modified) {
            Files.deleteIfExists(override.toPath())
            deleteEmptyOverrideParents(override.parentFile, File(current.root, OVERRIDES_DIRECTORY))
        } else {
            writeBytesAtomically(override, bytes)
        }
        AsmSavedAsset(modified)
    }

    fun restoreAsset(
        projectFilePath: String,
        assetPath: String,
        expectedBytes: ByteArray? = null,
        expectedOriginalBytes: ByteArray? = null,
    ): AsmSavedAsset = withSourceLock(projectFilePath) {
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        val asset = requireAsset(current.assets, assetPath)
        val original = resolveManagedAsset(File(current.originalDirectory, "data"), assetPath, current.assets)
        val target = resolveManagedAsset(File(current.workingDirectory, "data"), assetPath, current.assets)
        val override = resolveManagedAsset(File(current.root, "$OVERRIDES_DIRECTORY/data"), assetPath, current.assets)
        require(original.isFile && original.length() == asset.length.toLong() &&
            target.isFile && target.length() == asset.length.toLong()) {
            "Source asset $assetPath is missing or does not match its frozen manifest"
        }
        require(expectedOriginalBytes == null || original.readBytes().contentEquals(expectedOriginalBytes)) {
            "$assetPath's immutable baseline changed after review. Nothing was restored."
        }
        val authoritative = if (override.isFile) override else original
        require(expectedBytes == null || target.readBytes().contentEquals(expectedBytes)) {
            "$assetPath changed in the generated ASM workspace. No asset was overwritten."
        }
        require(expectedBytes == null || authoritative.readBytes().contentEquals(expectedBytes)) {
            "$assetPath's authored data override changed outside SMEDIT. No asset was overwritten."
        }
        val previousTarget = target.readBytes()
        val previousOverride = override.takeIf(File::isFile)?.readBytes()
        try {
            writeBytesAtomically(target, original.readBytes())
            val modified = Files.deleteIfExists(override.toPath())
            deleteEmptyOverrideParents(override.parentFile, File(current.root, OVERRIDES_DIRECTORY))
            requireNotNull(loadForBuildAfterRecovery(projectFilePath)) {
                "Project ASM workspace did not validate after restoring $assetPath"
            }
            AsmSavedAsset(modified)
        } catch (problem: Exception) {
            writeBytesAtomically(target, previousTarget)
            restoreOptionalFile(override, previousOverride)
            throw problem
        }
    }

    fun readAsset(projectFilePath: String, assetPath: String): ByteArray = withSourceLock(projectFilePath) {
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        val target = resolveManagedAsset(File(current.workingDirectory, "data"), assetPath, current.assets)
        require(target.isFile) { "Source asset does not exist: $assetPath" }
        target.readBytes()
    }

    /**
     * Atomically converts one direct frozen `incbin` declaration into a relocatable
     * project asset. The original bytes remain emitted in place without their
     * public label; the checked compiler supplies that same label at the managed
     * allocation. No address is persisted here.
     */
    fun installManagedAsset(
        projectFilePath: String,
        record: AsmManagedAssetRecord,
        bytes: ByteArray,
        expectedSourceText: String,
    ): AsmManagedAssetRecord = withSourceLock(projectFilePath) {
        record.validate()
        require(bytes.isNotEmpty()) { "Managed ASM asset ${record.id} cannot be empty" }
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        if (!record.inlineSource) {
            val frozen = requireAsset(current.assets, record.originalAssetPath)
            require(frozen.pcOffset == record.originalPcOffset && frozen.length == record.originalLength) {
                "${record.id} does not match the frozen source-asset manifest"
            }
        } else {
            require(snesLoRomToPc(pcToSnesLoRom(record.originalPcOffset) ?: -1) == record.originalPcOffset) {
                "${record.id} has an invalid original inline range"
            }
        }
        val manifest = AsmManagedAssets.load(current.root)
        require(manifest.assets.none { it.id == record.id || it.sourceLabel == record.sourceLabel ||
            it.managedAssetPath == record.managedAssetPath }) {
            "Managed ASM asset ${record.sourceLabel} already exists"
        }
        val sourceTarget = resolveManagedSource(File(current.workingDirectory, "src"), record.sourceFileId)
        val sourceOverride = resolveManagedSource(File(current.root, "$OVERRIDES_DIRECTORY/src"), record.sourceFileId)
        val originalSource = resolveManagedSource(File(current.originalDirectory, "src"), record.sourceFileId)
        require(sourceTarget.isFile && originalSource.isFile && sourceTarget.readText() == expectedSourceText) {
            "${record.sourceFileId} changed since the relocation was prepared. No source was overwritten."
        }
        val authoritativeSource = if (sourceOverride.isFile) sourceOverride else originalSource
        require(authoritativeSource.readText() == expectedSourceText) {
            "${record.sourceFileId}'s authored override changed outside SMEDIT. No source was overwritten."
        }
        require(expectedSourceText.windowed(record.originalDeclaration.length, 1)
            .count { it == record.originalDeclaration } == 1) {
            "${record.sourceLabel}'s direct incbin declaration is no longer unique"
        }
        require(AsmManagedAssets.markerRegex(record).find(expectedSourceText) == null) {
            "${record.sourceLabel} is already marked as a managed asset"
        }
        val marker = AsmManagedAssets.marker(record).let { generated ->
            if ("\r\n" in expectedSourceText) generated.replace("\n", "\r\n") else generated
        }
        val updatedSource = expectedSourceText.replaceFirst(record.originalDeclaration, marker)
        val authoredAsset = AsmManagedAssets.assetFile(current.root, record)
        val workingAsset = AsmManagedAssets.workingAssetFile(current.workingDirectory, record)
        require(!authoredAsset.exists() && !workingAsset.exists()) {
            "Managed ASM asset path already exists: ${record.managedAssetPath}"
        }
        val manifestFile = File(current.root, ASM_MANAGED_ASSET_MANIFEST)
        val previousManifest = manifestFile.takeIf(File::isFile)?.readBytes()
        val previousSourceOverride = sourceOverride.takeIf(File::isFile)?.readBytes()
        val previousWorkingSource = sourceTarget.readBytes()
        try {
            writeBytesAtomically(authoredAsset, bytes)
            writeBytesAtomically(workingAsset, bytes)
            writeTextAtomically(manifestFile, AsmManagedAssets.encode(
                manifest.copy(assets = (manifest.assets + record).sortedBy(AsmManagedAssetRecord::id)),
            ))
            writeTextAtomically(sourceOverride, updatedSource)
            writeTextAtomically(sourceTarget, updatedSource)
            val checked = requireNotNull(loadForBuildAfterRecovery(projectFilePath)) {
                "Project ASM workspace did not validate after relocating ${record.sourceLabel}"
            }
            AsmManagedAssets.validateWorkspace(checked.root, checked.workingDirectory)
            record
        } catch (problem: Exception) {
            restoreOptionalFile(manifestFile, previousManifest)
            restoreOptionalFile(sourceOverride, previousSourceOverride)
            writeBytesAtomically(sourceTarget, previousWorkingSource)
            Files.deleteIfExists(authoredAsset.toPath())
            Files.deleteIfExists(workingAsset.toPath())
            deleteEmptyOverrideParents(authoredAsset.parentFile, File(current.root, ASM_MANAGED_ASSET_DIRECTORY))
            throw problem
        }
    }

    fun saveManagedAsset(
        projectFilePath: String,
        id: String,
        bytes: ByteArray,
        expectedBytes: ByteArray,
    ): AsmManagedAssetRecord = withSourceLock(projectFilePath) {
        require(bytes.isNotEmpty()) { "Managed ASM asset $id cannot be empty" }
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        val record = requireNotNull(AsmManagedAssets.load(current.root).assets.singleOrNull { it.id == id }) {
            "Unknown managed ASM asset: $id"
        }
        val authored = AsmManagedAssets.assetFile(current.root, record)
        val mirror = AsmManagedAssets.workingAssetFile(current.workingDirectory, record)
        require(authored.isFile && mirror.isFile && authored.readBytes().contentEquals(expectedBytes) &&
            mirror.readBytes().contentEquals(expectedBytes)) {
            "${record.sourceLabel}'s managed data changed after the visual resource was opened. Reload it before saving."
        }
        val previous = authored.readBytes()
        try {
            writeBytesAtomically(authored, bytes)
            writeBytesAtomically(mirror, bytes)
            record
        } catch (problem: Exception) {
            writeBytesAtomically(authored, previous)
            writeBytesAtomically(mirror, previous)
            throw problem
        }
    }

    fun restoreManagedAsset(
        projectFilePath: String,
        id: String,
        expectedBytes: ByteArray,
        replacementOriginalBytes: ByteArray? = null,
        replacementInlineDeclaration: String? = null,
        expectedSourceText: String? = null,
    ): AsmManagedAssetRecord = withSourceLock(projectFilePath) {
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        val manifest = AsmManagedAssets.load(current.root)
        val record = requireNotNull(manifest.assets.singleOrNull { it.id == id }) {
            "Unknown managed ASM asset: $id"
        }
        val authored = AsmManagedAssets.assetFile(current.root, record)
        val mirror = AsmManagedAssets.workingAssetFile(current.workingDirectory, record)
        require(authored.isFile && mirror.isFile && authored.readBytes().contentEquals(expectedBytes) &&
            mirror.readBytes().contentEquals(expectedBytes)) {
            "${record.sourceLabel}'s managed data changed after the visual resource was opened. Reload it before restoring."
        }
        val sourceTarget = resolveManagedSource(File(current.workingDirectory, "src"), record.sourceFileId)
        val sourceOverride = resolveManagedSource(File(current.root, "$OVERRIDES_DIRECTORY/src"), record.sourceFileId)
        val originalSource = resolveManagedSource(File(current.originalDirectory, "src"), record.sourceFileId)
        val sourceText = sourceTarget.readText()
        require(expectedSourceText == null || sourceText == expectedSourceText) {
            "${record.sourceFileId} changed after Project ASM Changes was opened. Nothing was restored."
        }
        require(sourceOverride.isFile && sourceOverride.readText() == sourceText) {
            "${record.sourceFileId}'s authored override changed outside SMEDIT. No source was overwritten."
        }
        val marker = requireNotNull(AsmManagedAssets.markerRegex(record).find(sourceText)) {
            "${record.sourceLabel}'s managed source marker is missing"
        }
        require(replacementInlineDeclaration == null || record.inlineSource) {
            "${record.sourceLabel} is not an inline source asset"
        }
        val restoredDeclaration = replacementInlineDeclaration ?: record.originalDeclaration
        require(!record.inlineSource || record.sourceLabel in restoredDeclaration) {
            "${record.sourceLabel}'s restored inline declaration lost its public label"
        }
        val restoredSource = sourceText.replaceRange(marker.range, restoredDeclaration)
        require(replacementOriginalBytes == null || replacementOriginalBytes.size == record.originalLength) {
            "${record.sourceLabel}'s restored native asset must contain exactly ${record.originalLength} bytes"
        }
        require(!record.inlineSource || replacementOriginalBytes == null) {
            "${record.sourceLabel}'s inline source cannot restore an incbin override"
        }
        val originalAsset = record.takeUnless(AsmManagedAssetRecord::inlineSource)?.let {
            resolveManagedAsset(File(current.originalDirectory, "data"), it.originalAssetPath, current.assets)
        }
        val workingOriginalAsset = record.takeUnless(AsmManagedAssetRecord::inlineSource)?.let {
            resolveManagedAsset(File(current.workingDirectory, "data"), it.originalAssetPath, current.assets)
        }
        val originalAssetOverride = record.takeUnless(AsmManagedAssetRecord::inlineSource)?.let {
            resolveManagedAsset(File(current.root, "$OVERRIDES_DIRECTORY/data"), it.originalAssetPath, current.assets)
        }
        require(record.inlineSource || originalAsset?.isFile == true && workingOriginalAsset?.isFile == true) {
            "${record.sourceLabel}'s native source asset is unavailable"
        }
        val manifestFile = File(current.root, ASM_MANAGED_ASSET_MANIFEST)
        val previousManifest = manifestFile.readBytes()
        val previousOverride = sourceOverride.readBytes()
        val previousWorking = sourceTarget.readBytes()
        val previousAsset = authored.readBytes()
        val previousWorkingOriginalAsset = workingOriginalAsset?.readBytes()
        val previousOriginalAssetOverride = originalAssetOverride?.takeIf(File::isFile)?.readBytes()
        try {
            val remaining = manifest.assets.filterNot { it.id == id }
            if (remaining.isEmpty()) Files.deleteIfExists(manifestFile.toPath())
            else writeTextAtomically(manifestFile, AsmManagedAssets.encode(manifest.copy(assets = remaining)))
            if (restoredSource.toByteArray().contentEquals(originalSource.readBytes())) {
                Files.deleteIfExists(sourceOverride.toPath())
            } else {
                writeTextAtomically(sourceOverride, restoredSource)
            }
            writeTextAtomically(sourceTarget, restoredSource)
            Files.deleteIfExists(authored.toPath())
            Files.deleteIfExists(mirror.toPath())
            replacementOriginalBytes?.let { replacement ->
                writeBytesAtomically(requireNotNull(workingOriginalAsset), replacement)
                if (replacement.contentEquals(requireNotNull(originalAsset).readBytes())) {
                    Files.deleteIfExists(requireNotNull(originalAssetOverride).toPath())
                } else {
                    writeBytesAtomically(requireNotNull(originalAssetOverride), replacement)
                }
            }
            deleteEmptyOverrideParents(authored.parentFile, File(current.root, ASM_MANAGED_ASSET_DIRECTORY))
            deleteEmptyOverrideParents(mirror.parentFile, File(current.workingDirectory, "data/$ASM_MANAGED_ASSET_DATA_DIRECTORY"))
            originalAssetOverride?.let { deleteEmptyOverrideParents(it.parentFile, File(current.root, OVERRIDES_DIRECTORY)) }
            val checked = requireNotNull(loadForBuildAfterRecovery(projectFilePath)) {
                "Project ASM workspace did not validate after restoring ${record.sourceLabel}"
            }
            require(AsmManagedAssets.load(checked.root).assets.none { it.id == id }) {
                "Managed ASM asset ${record.sourceLabel} was not removed"
            }
            record
        } catch (problem: Exception) {
            writeBytesAtomically(manifestFile, previousManifest)
            writeBytesAtomically(sourceOverride, previousOverride)
            writeBytesAtomically(sourceTarget, previousWorking)
            writeBytesAtomically(authored, previousAsset)
            writeBytesAtomically(mirror, previousAsset)
            if (workingOriginalAsset != null && previousWorkingOriginalAsset != null) {
                writeBytesAtomically(workingOriginalAsset, previousWorkingOriginalAsset)
            }
            originalAssetOverride?.let { restoreOptionalFile(it, previousOriginalAssetOverride) }
            throw problem
        }
    }

    fun managedAssets(projectFilePath: String): List<AsmManagedAssetRecord> = withSourceLock(projectFilePath) {
        val root = projectRoot(projectFilePath) ?: return@withSourceLock emptyList()
        AsmManagedAssets.load(root).assets
    }

    fun rootFor(projectFilePath: String): File? = projectRoot(projectFilePath)

    fun cacheFor(projectFilePath: String): File? = projectRoot(projectFilePath)?.let(::cacheRoot)

    /**
     * Saved project source edits are mirrored as complete changed-bank overrides;
     * unchanged banks are omitted. Reading
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

    /** Portable binary inputs which Loaded ROM mode must never silently ignore. */
    fun assetOverridePaths(projectFilePath: String): Set<String> {
        val root = projectRoot(projectFilePath) ?: return emptySet()
        val dataRoot = File(root, "$OVERRIDES_DIRECTORY/data")
        if (!dataRoot.isDirectory) return emptySet()
        return dataRoot.walkTopDown()
            .filter(File::isFile)
            .map { it.relativeTo(dataRoot).invariantSeparatorsPath }
            .toCollection(linkedSetOf())
    }

    fun createModule(
        projectFilePath: String,
        requestedName: String,
        description: String = "",
        initialSource: String? = null,
    ): Pair<AsmProjectWorkspace, String> = withSourceLock(projectFilePath) {
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
        checkNotNull(load(projectFilePath)) {
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
        val managedOwners = AsmManagedAssets.load(current.root).assets.filter { it.sourceFileId == fileId }
        require(managedOwners.isEmpty()) {
            "Restore ${managedOwners.size} managed asset transaction${if (managedOwners.size == 1) "" else "s"} before changing $fileId's settings."
        }
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

    fun deleteModule(
        projectFilePath: String,
        fileId: String,
        expectedText: String? = null,
        expectedModuleOrder: List<String>? = null,
        expectedDisabledModules: Set<String>? = null,
    ): AsmProjectWorkspace = withSourceLock(projectFilePath) {
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        require(isProjectModuleFileId(fileId)) { "Not a project ASM module: $fileId" }
        val managedOwners = AsmManagedAssets.load(current.root).assets.filter { it.sourceFileId == fileId }
        require(managedOwners.isEmpty()) {
            "Restore ${managedOwners.size} managed asset transaction${if (managedOwners.size == 1) "" else "s"} before deleting $fileId."
        }
        val modulesDirectory = File(current.root, MODULES_DIRECTORY)
        val fileName = fileId.removePrefix(ASM_PROJECT_MODULE_PREFIX)
        val target = resolveProjectModule(modulesDirectory, fileId)
        require(target.isFile) { "Project ASM module does not exist: $fileId" }
        val workingTarget = resolveManagedSource(File(current.workingDirectory, "src"), fileId)
        require(expectedText == null || target.readText() == expectedText) {
            "$fileId changed outside SMEDIT. No module was deleted."
        }
        require(expectedText == null || workingTarget.isFile && workingTarget.readText() == expectedText) {
            "$fileId changed in the generated ASM workspace. No module was deleted."
        }
        val existingOrder = readModuleOrder(modulesDirectory)
        require(expectedModuleOrder == null || existingOrder == expectedModuleOrder) {
            "Project module order changed after review. No module was deleted."
        }
        val order = existingOrder.filterNot { it == fileName }
        val existingDisabled = readDisabledModules(modulesDirectory, existingOrder)
        require(expectedDisabledModules == null || existingDisabled == expectedDisabledModules) {
            "Project module enabled state changed after review. No module was deleted."
        }
        val disabled = existingDisabled.filterNotTo(linkedSetOf()) {
            it == fileName
        }
        val previousTarget = target.readBytes()
        val previousWorking = workingTarget.takeIf(File::isFile)?.readBytes()
        try {
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
            checkNotNull(load(projectFilePath)) {
                "Project ASM workspace did not validate after deleting $fileName"
            }
        } catch (problem: Exception) {
            writeBytesAtomically(target, previousTarget)
            writeModuleOrder(modulesDirectory, existingOrder)
            writeDisabledModules(modulesDirectory, existingDisabled)
            syncProjectModules(modulesDirectory, File(current.workingDirectory, "src"))
            previousWorking?.let { restored ->
                require(workingTarget.isFile && workingTarget.readBytes().contentEquals(restored)) {
                    "Could not recover $fileId's generated module mirror"
                }
            }
            throw problem
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

    private fun synchronizeAssetMirrorsAfterRecovery(projectFilePath: String) {
        val root = projectRoot(projectFilePath) ?: return
        val baseline = AsmSourceBaseline.load(root) ?: return
        val cache = cacheRoot(root)
        val originalRoot = File(cache, "$ORIGINAL_DIRECTORY/data")
        val workingRoot = File(cache, "$WORKING_DIRECTORY/data")
        if (!originalRoot.isDirectory || !workingRoot.isDirectory) return
        val overrideRoot = File(root, "$OVERRIDES_DIRECTORY/data")
        validateAssetOverrideInventory(overrideRoot, baseline.assets)
        baseline.assets.forEach { asset ->
            val original = resolveManagedAsset(originalRoot, asset.path, baseline.assets)
            if (!original.isFile || original.length() != asset.length.toLong()) return@forEach
            val target = resolveManagedAsset(workingRoot, asset.path, baseline.assets)
            val override = resolveManagedAsset(overrideRoot, asset.path, baseline.assets)
            val authoritative = if (override.isFile) override else original
            if (override.isFile) {
                require(!Files.isSymbolicLink(override.toPath()) && override.length() == asset.length.toLong()) {
                    "Project ASM data override ${asset.path} does not match its frozen ${asset.length}-byte asset"
                }
            }
            val expected = authoritative.readBytes()
            val previous = target.takeIf(File::isFile)?.readBytes()
            if (previous != null && previous.contentEquals(expected)) return@forEach
            if (previous != null) {
                val recoveryRoot = File(cache, "recovery/data")
                val recovery = resolveManagedRecoveryAsset(
                    recoveryRoot,
                    "${asset.path}.${AsmVerifiedCache.hash(previous)}.bin",
                )
                if (!recovery.exists()) writeBytesAtomically(recovery, previous)
            }
            writeBytesAtomically(target, expected)
        }
    }

    /** Authored variable-sized project assets mirror into the generated data tree.
     * The dedicated subtree is rebuilt from its manifest so deleted intent can
     * never be resurrected by a stale cache file. */
    private fun synchronizeManagedAssetMirrors(projectFilePath: String) {
        val root = projectRoot(projectFilePath) ?: return
        if (!root.isDirectory) return
        val cache = cacheRoot(root)
        val working = File(cache, WORKING_DIRECTORY)
        if (!File(working, "data").isDirectory) return
        val manifest = AsmManagedAssets.load(root)
        val managedMirrorRoot = File(working, "data/$ASM_MANAGED_ASSET_DATA_DIRECTORY")
        if (managedMirrorRoot.exists()) deleteTree(File(working, "data"), managedMirrorRoot)
        manifest.assets.forEach { record ->
            val authored = AsmManagedAssets.assetFile(root, record)
            require(authored.isFile && !Files.isSymbolicLink(authored.toPath()) && authored.length() > 0) {
                "Managed ASM asset ${record.id} is missing its authored data"
            }
            writeBytesAtomically(AsmManagedAssets.workingAssetFile(working, record), authored.readBytes())
        }
        AsmManagedAssets.validateWorkspace(root, working, manifest)
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
        writeTextAtomically(
            File(modulesDirectory, BUNDLED_STARTERS_FILE),
            order.joinToString("\n", postfix = if (order.isEmpty()) "" else "\n"),
        )
        syncProjectModules(modulesDirectory, workingSourceRoot)
    }

    /**
     * Appends newly shipped examples once, disabled, without replacing authored
     * modules. The inventory also remembers deletion: a starter the project has
     * already seen is never silently recreated.
     */
    private fun synchronizeBundledStarterModules(projectFilePath: String) {
        if (starterModules.isEmpty()) return
        val root = projectRoot(projectFilePath) ?: return
        val cache = cacheRoot(root)
        val workingSourceRoot = File(cache, "$WORKING_DIRECTORY/src")
        if (!File(workingSourceRoot, "main.asm").isFile) return
        val baseline = AsmSourceBaseline.load(root) ?: return
        val modulesDirectory = File(root, MODULES_DIRECTORY)
        ensureProjectModuleDirectory(modulesDirectory)
        val order = readModuleOrder(modulesDirectory).toMutableList()
        val marker = File(modulesDirectory, BUNDLED_STARTERS_FILE)
        val seen = if (marker.isFile) {
            marker.readLines().map(String::trim).filter(String::isNotEmpty).toMutableSet()
        } else {
            // Objectives was the sole starter before this inventory existed.
            mutableSetOf("objectives_pause_screen.asm")
        }
        require(seen.all { normalizeProjectModuleName(it) == it }) {
            "Bundled ASM starter inventory contains an unsafe module name"
        }
        val disabled = readDisabledModules(modulesDirectory, order).toMutableSet()
        var changed = false
        starterModules.forEach { starter ->
            require(normalizeProjectModuleName(starter.fileName) == starter.fileName) {
                "Unsafe bundled ASM starter module name: ${starter.fileName}"
            }
            val target = File(modulesDirectory, starter.fileName)
            if (starter.fileName !in seen && !target.exists()) {
                val text = checkNotNull(javaClass.getResourceAsStream(starter.resourcePath)) {
                    "Missing bundled ASM starter module ${starter.resourcePath}"
                }.bufferedReader().use { it.readText() }
                writeTextAtomically(target, baseline.bindings.template(text))
                order += starter.fileName
                disabled += starter.fileName
                changed = true
            }
            seen += starter.fileName
        }
        if (changed) {
            writeModuleOrder(modulesDirectory, order)
            writeDisabledModules(modulesDirectory, order.filter(disabled::contains))
            syncProjectModules(modulesDirectory, workingSourceRoot)
        }
        val markerText = seen.sorted().joinToString("\n", postfix = if (seen.isEmpty()) "" else "\n")
        if (!marker.isFile || marker.readText() != markerText) writeTextAtomically(marker, markerText)
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

    private fun requireAsset(assets: List<AsmAssetRange>, assetPath: String): AsmAssetRange =
        requireNotNull(assets.singleOrNull { it.path == assetPath }) {
            "Unknown project ASM source asset: $assetPath"
        }

    private fun resolveManagedAsset(root: File, assetPath: String, assets: List<AsmAssetRange>): File {
        requireAsset(assets, assetPath)
        return resolveManagedRecoveryAsset(root, assetPath)
    }

    private fun resolveManagedRecoveryAsset(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !relativePath.contains('\\')) {
            "Invalid ASM data path: $relativePath"
        }
        val normalizedRoot = root.canonicalFile.toPath()
        val resolved = File(root, relativePath).canonicalFile
        require(resolved.toPath().startsWith(normalizedRoot) && resolved.toPath() != normalizedRoot) {
            "Unsafe ASM data path: $relativePath"
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

    private fun applyAssetOverrides(
        overridesRoot: File,
        workingDataRoot: File,
        assets: List<AsmAssetRange>,
    ) {
        val overrideDataRoot = File(overridesRoot, "data")
        validateAssetOverrideInventory(overrideDataRoot, assets)
        if (!overrideDataRoot.isDirectory) return
        overrideDataRoot.walkTopDown().filter(File::isFile).forEach { override ->
            val assetPath = override.relativeTo(overrideDataRoot).invariantSeparatorsPath
            val asset = requireAsset(assets, assetPath)
            require(!Files.isSymbolicLink(override.toPath()) && override.length() == asset.length.toLong()) {
                "Project ASM data override $assetPath does not match its frozen ${asset.length}-byte asset"
            }
            val target = resolveManagedAsset(workingDataRoot, assetPath, assets)
            require(target.isFile && target.length() == asset.length.toLong()) {
                "Project ASM data override has no pinned source asset: $assetPath"
            }
            writeBytesAtomically(target, override.readBytes())
        }
    }

    private fun validateAssetOverrideInventory(overrideDataRoot: File, assets: List<AsmAssetRange>) {
        if (!overrideDataRoot.exists()) return
        require(overrideDataRoot.isDirectory && !Files.isSymbolicLink(overrideDataRoot.toPath())) {
            "Project ASM data overrides must be a real directory"
        }
        val known = assets.mapTo(hashSetOf(), AsmAssetRange::path)
        overrideDataRoot.walkTopDown().forEach { entry ->
            require(!Files.isSymbolicLink(entry.toPath()) && (entry.isDirectory || entry.isFile)) {
                "Project ASM data overrides cannot contain links or special files: $entry"
            }
            if (entry.isFile) {
                val path = entry.relativeTo(overrideDataRoot).invariantSeparatorsPath
                require(path in known) { "Project ASM data override has no frozen source asset: $path" }
            }
        }
    }

    private fun writeTextAtomically(target: File, text: String) = writeBytesAtomically(target, text.toByteArray())

    private fun restoreOptionalFile(target: File, previous: ByteArray?) {
        if (previous == null) Files.deleteIfExists(target.toPath())
        else writeBytesAtomically(target, previous)
    }

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
        const val BUNDLED_STARTERS_FILE = ".bundled-starters"
    }
}

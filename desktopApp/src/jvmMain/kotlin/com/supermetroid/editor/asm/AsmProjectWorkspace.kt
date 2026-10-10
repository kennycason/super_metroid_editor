package com.supermetroid.editor.asm

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Properties
import java.util.UUID

internal enum class AsmWorkspaceKind { REFERENCE, PROJECT }

internal const val ASM_PROJECT_MODULE_PREFIX = "project/"
internal const val ASM_PROJECT_MODULE_ORDER_FILE = ".order"
internal const val ASM_PROJECT_MODULE_DISABLED_FILE = ".disabled"

internal data class AsmProjectWorkspace(
    val root: File,
    val originalDirectory: File,
    val workingDirectory: File,
    val referenceWorkspace: AsmReferenceWorkspace,
    val modifiedFileIds: Set<String>,
    val projectModuleFileIds: List<String>,
    val disabledProjectModuleFileIds: Set<String>,
)

/** Minimal workspace view for compilation. It avoids rebuilding the browser's
 * complete parsed source index when Asar only needs the filesystem trees. */
internal data class AsmProjectBuildWorkspace(
    val root: File,
    val originalDirectory: File,
    val workingDirectory: File,
    val disabledProjectModuleFileIds: Set<String>,
)

/**
 * Owns the project-local ASM sidecar. Initial creation is transactional and
 * never mutates the global downloaded reference. An immutable original tree is
 * retained beside the working tree so restore remains available offline.
 */
internal class AsmProjectWorkspaceRepository(
    private val assetRanges: List<AsmAssetRange> = AsmAssetManifest.loadBundled(),
) {
    fun load(projectFilePath: String): AsmProjectWorkspace? {
        synchronizeProjectModules(projectFilePath)
        val buildWorkspace = loadForBuild(projectFilePath) ?: return null
        val metadata = readMetadata(File(buildWorkspace.root, METADATA_FILE)) ?: return null
        val workspace = referenceWorkspace(buildWorkspace.workingDirectory, metadata.reference)
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
        )
    }

    fun loadForBuild(projectFilePath: String): AsmProjectBuildWorkspace? {
        val root = projectRoot(projectFilePath) ?: return null
        if (!root.isDirectory) return null
        val metadata = readMetadata(File(root, METADATA_FILE)) ?: return null
        if (metadata.layoutVersion != LAYOUT_VERSION || metadata.reference.commit != AsmReferenceContract.COMMIT) {
            return null
        }
        val original = File(root, ORIGINAL_DIRECTORY)
        val working = File(root, WORKING_DIRECTORY)
        if (!File(original, "src/main.asm").isFile || !File(working, "src/main.asm").isFile) return null
        if (!assetRanges.all { File(working, "data/${it.path}").isFile }) return null
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
        )
    }

    fun initialize(
        projectFilePath: String,
        reference: AsmReferenceWorkspace,
        onProgress: (String) -> Unit = {},
    ): AsmProjectWorkspace {
        val target = requireNotNull(projectRoot(projectFilePath)) {
            "Save the SMEDIT project before enabling project ASM"
        }
        load(projectFilePath)?.let { return it }
        val sidecar = target.parentFile
        sidecar.mkdirs()
        val staging = File(sidecar, ".asm.installing-${UUID.randomUUID()}")
        requireManagedChild(sidecar, staging)
        try {
            onProgress("Copying the pinned ASM source into the project…")
            val original = File(staging, ORIGINAL_DIRECTORY)
            copyTree(File(reference.root, "src"), File(original, "src"))
            copyTree(File(reference.root, "data"), File(original, "data"))
            onProgress("Creating the editable ASM workspace…")
            val working = File(staging, WORKING_DIRECTORY)
            copyTree(File(original, "src"), File(working, "src"))
            copyTree(File(original, "data"), File(working, "data"))
            val existingOverrides = File(target, OVERRIDES_DIRECTORY)
            if (existingOverrides.isDirectory) {
                copyTree(existingOverrides, File(staging, OVERRIDES_DIRECTORY))
                applySourceOverrides(File(staging, OVERRIDES_DIRECTORY), File(working, "src"))
            }
            val existingModules = File(target, MODULES_DIRECTORY)
            if (existingModules.isDirectory && existingModules.listFiles().orEmpty().any {
                    it.isFile && it.extension.equals("asm", ignoreCase = true)
                }
            ) {
                copyTree(existingModules, File(staging, MODULES_DIRECTORY))
                ensureProjectModuleDirectory(File(staging, MODULES_DIRECTORY))
                syncProjectModules(
                    modulesDirectory = File(staging, MODULES_DIRECTORY),
                    workingSourceRoot = File(working, "src"),
                )
            }
            File(staging, ".gitignore").writeText(PROJECT_ASM_GITIGNORE)
            writeMetadata(
                File(staging, METADATA_FILE),
                AsmProjectMetadata(
                    layoutVersion = LAYOUT_VERSION,
                    createdAt = Instant.now().toString(),
                    reference = reference.metadata,
                ),
            )
            require(File(staging, "$WORKING_DIRECTORY/src/main.asm").isFile) {
                "Project ASM staging has no src/main.asm"
            }
            require(assetRanges.all { File(working, "data/${it.path}").isFile }) {
                "Project ASM staging is missing one or more extracted assets"
            }
            // Parse before activation so an incomplete or malformed tree never
            // becomes the project's live workspace.
            referenceWorkspace(working, reference.metadata)
            activateDirectory(staging, target)
            return checkNotNull(load(projectFilePath)) { "Project ASM workspace did not validate after creation" }
        } catch (problem: Exception) {
            deleteTree(sidecar, staging)
            throw problem
        }
    }

    fun saveSource(projectFilePath: String, fileId: String, text: String): AsmProjectWorkspace {
        // Resolve and validate the lightweight filesystem workspace first. A
        // complete source-index parse is needed only once, after the atomic
        // write, when the browser adopts the new saved tree.
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        val sourceRoot = File(current.workingDirectory, "src")
        val target = resolveManagedSource(sourceRoot, fileId)
        require(target.isFile) { "ASM source does not exist: $fileId" }
        if (isProjectModuleFileId(fileId)) {
            val module = resolveProjectModule(File(current.root, MODULES_DIRECTORY), fileId)
            require(module.isFile) { "Project ASM module does not exist: $fileId" }
            writeTextAtomically(module, text)
            writeTextAtomically(target, text)
            return checkNotNull(load(projectFilePath)) { "Project ASM workspace did not validate after saving $fileId" }
        }
        writeTextAtomically(target, text)
        val original = resolveManagedSource(File(current.originalDirectory, "src"), fileId)
        val override = resolveManagedSource(File(current.root, "$OVERRIDES_DIRECTORY/src"), fileId)
        if (original.readBytes().contentEquals(text.toByteArray())) {
            Files.deleteIfExists(override.toPath())
            deleteEmptyOverrideParents(override.parentFile, File(current.root, OVERRIDES_DIRECTORY))
        } else {
            writeTextAtomically(override, text)
        }
        return checkNotNull(load(projectFilePath)) { "Project ASM workspace did not validate after saving $fileId" }
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

    fun createModule(projectFilePath: String, requestedName: String): Pair<AsmProjectWorkspace, String> {
        val current = requireNotNull(loadForBuild(projectFilePath)) { "Project ASM workspace is unavailable" }
        val modulesDirectory = File(current.root, MODULES_DIRECTORY)
        ensureProjectModuleDirectory(modulesDirectory)
        val fileName = normalizeProjectModuleName(requestedName)
        val target = resolveProjectModule(modulesDirectory, ASM_PROJECT_MODULE_PREFIX + fileName)
        require(!target.exists()) { "A project module named $fileName already exists" }
        val order = readModuleOrder(modulesDirectory).toMutableList()
        val displayName = fileName.removeSuffix(".asm").replace('_', ' ')
        writeTextAtomically(target, projectModuleTemplate(displayName))
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
        require(isProjectModuleFileId(fileId)) { "Not a project ASM module: $fileId" }
        val modulesDirectory = File(current.root, MODULES_DIRECTORY)
        val oldName = fileId.removePrefix(ASM_PROJECT_MODULE_PREFIX)
        val newName = normalizeProjectModuleName(requestedName)
        if (oldName == newName) return checkNotNull(load(projectFilePath)) to fileId
        val source = resolveProjectModule(modulesDirectory, fileId)
        val target = resolveProjectModule(modulesDirectory, ASM_PROJECT_MODULE_PREFIX + newName)
        require(source.isFile) { "Project ASM module does not exist: $fileId" }
        require(!target.exists()) { "A project module named $newName already exists" }
        val order = readModuleOrder(modulesDirectory).map { if (it == oldName) newName else it }
        val disabled = readDisabledModules(modulesDirectory, readModuleOrder(modulesDirectory))
            .mapTo(linkedSetOf()) { if (it == oldName) newName else it }
        moveFileAtomically(source, target)
        writeModuleOrder(modulesDirectory, order)
        writeDisabledModules(modulesDirectory, disabled)
        syncProjectModules(modulesDirectory, File(current.workingDirectory, "src"))
        val newFileId = ASM_PROJECT_MODULE_PREFIX + newName
        return checkNotNull(load(projectFilePath)) {
            "Project ASM workspace did not validate after renaming $oldName"
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

    private fun referenceWorkspace(root: File, metadata: AsmReferenceMetadata): AsmReferenceWorkspace {
        val index = AsmSourceParser().parse(File(root, "src"), File(root, "data"), assetRanges)
        return AsmReferenceWorkspace(root, metadata, index)
    }

    private fun synchronizeProjectModules(projectFilePath: String) {
        val root = projectRoot(projectFilePath) ?: return
        val workingSourceRoot = File(root, "$WORKING_DIRECTORY/src")
        if (!workingSourceRoot.isDirectory) return
        val modulesDirectory = File(root, MODULES_DIRECTORY)
        val legacyProjectSource = File(workingSourceRoot, ASM_PROJECT_MODULE_DIRECTORY)
        val legacyModules = legacyProjectSource.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("asm", ignoreCase = true) }
        if (modulesDirectory.isDirectory && modulesDirectory.listFiles().orEmpty().none {
                it.isFile && it.extension.equals("asm", ignoreCase = true)
            }
        ) {
            Files.deleteIfExists(File(modulesDirectory, ASM_PROJECT_MODULE_ORDER_FILE).toPath())
            Files.deleteIfExists(File(modulesDirectory, ASM_PROJECT_MODULE_DISABLED_FILE).toPath())
            modulesDirectory.delete()
            if (legacyModules.isEmpty()) {
                if (legacyProjectSource.exists()) deleteTree(workingSourceRoot, legacyProjectSource)
                return
            }
        }
        if (!modulesDirectory.exists()) {
            if (legacyModules.isEmpty()) {
                if (legacyProjectSource.exists()) deleteTree(workingSourceRoot, legacyProjectSource)
                return
            }
            modulesDirectory.mkdirs()
            legacyModules.forEach { source ->
                Files.copy(source.toPath(), File(modulesDirectory, source.name).toPath())
            }
            val legacyOrder = File(legacyProjectSource, ASM_PROJECT_MODULE_ORDER_FILE)
                .takeIf(File::isFile)
                ?.readLines()
                ?.map(String::trim)
                ?.filter { name -> legacyModules.any { it.name == name } }
                .orEmpty()
            val remaining = legacyModules.map(File::getName).filterNot(legacyOrder::contains).sorted()
            writeModuleOrder(modulesDirectory, legacyOrder + remaining)
        }
        ensureProjectModuleDirectory(modulesDirectory)
        syncProjectModules(modulesDirectory, workingSourceRoot)
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

    private fun projectModuleTemplate(displayName: String): String = """
        ; Project module: $displayName
        ; Included by SMEDIT after the vanilla source banks and before generated patch modules.
        ; Add an org/free-space target, labels, and code here. Build errors link back to this file.

    """.trimIndent()

    private fun projectRoot(projectFilePath: String): File? {
        if (projectFilePath.isBlank()) return null
        val projectFile = File(projectFilePath).absoluteFile
        val parent = projectFile.parentFile ?: return null
        val sidecar = File(parent, "${projectFile.nameWithoutExtension}_smedit")
        return File(sidecar, "asm")
    }

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

    private fun moveFileAtomically(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
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
    )

    private companion object {
        const val LAYOUT_VERSION = 1
        const val METADATA_FILE = ".smedit-project-asm.properties"
        const val ORIGINAL_DIRECTORY = "original"
        const val WORKING_DIRECTORY = "workspace"
        const val OVERRIDES_DIRECTORY = "overrides"
        const val MODULES_DIRECTORY = "modules"
        const val ASM_PROJECT_MODULE_DIRECTORY = "project"
        val PROJECT_ASM_GITIGNORE = """
            # Generated ASM snapshots and ROM-derived assets stay local.
            # overrides/ and modules/ are intentional project-authored source.
            /original/
            /workspace/
            /build/
            /.smedit-project-asm.properties
        """.trimIndent() + "\n"
    }
}

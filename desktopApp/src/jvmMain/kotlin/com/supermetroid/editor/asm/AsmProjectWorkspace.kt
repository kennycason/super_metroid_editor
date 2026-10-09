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

internal data class AsmProjectWorkspace(
    val root: File,
    val originalDirectory: File,
    val workingDirectory: File,
    val referenceWorkspace: AsmReferenceWorkspace,
    val modifiedFileIds: Set<String>,
)

/** Minimal workspace view for compilation. It avoids rebuilding the browser's
 * complete parsed source index when Asar only needs the filesystem trees. */
internal data class AsmProjectBuildWorkspace(
    val root: File,
    val originalDirectory: File,
    val workingDirectory: File,
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
        val buildWorkspace = loadForBuild(projectFilePath) ?: return null
        val metadata = readMetadata(File(buildWorkspace.root, METADATA_FILE)) ?: return null
        val workspace = referenceWorkspace(buildWorkspace.workingDirectory, metadata.reference)
        return AsmProjectWorkspace(
            root = buildWorkspace.root,
            originalDirectory = buildWorkspace.originalDirectory,
            workingDirectory = buildWorkspace.workingDirectory,
            referenceWorkspace = workspace,
            modifiedFileIds = modifiedSourceFiles(
                buildWorkspace.originalDirectory,
                buildWorkspace.workingDirectory,
            ),
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
        return AsmProjectBuildWorkspace(
            root = root,
            originalDirectory = original,
            workingDirectory = working,
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
        val current = requireNotNull(load(projectFilePath)) { "Project ASM workspace is unavailable" }
        val sourceRoot = File(current.workingDirectory, "src")
        val target = resolveManagedSource(sourceRoot, fileId)
        require(target.isFile) { "ASM source does not exist: $fileId" }
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
        val current = requireNotNull(load(projectFilePath)) { "Project ASM workspace is unavailable" }
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
        if (!sourceRoot.isDirectory) return emptySet()
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("asm", ignoreCase = true) }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toCollection(linkedSetOf())
    }

    private fun referenceWorkspace(root: File, metadata: AsmReferenceMetadata): AsmReferenceWorkspace {
        val index = AsmSourceParser().parse(File(root, "src"), File(root, "data"), assetRanges)
        return AsmReferenceWorkspace(root, metadata, index)
    }

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
            .filter { it.isFile }
            .associateBy { it.relativeTo(originalRoot).invariantSeparatorsPath }
        val workings = workingRoot.walkTopDown()
            .filter { it.isFile }
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
        val PROJECT_ASM_GITIGNORE = """
            # Generated ASM snapshots and ROM-derived assets stay local.
            /original/
            /workspace/
            /.smedit-project-asm.properties
        """.trimIndent() + "\n"
    }
}

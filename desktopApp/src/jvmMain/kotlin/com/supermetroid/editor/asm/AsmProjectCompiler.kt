package com.supermetroid.editor.asm

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class AsmSourceOwnedRange(
    val pcOffset: Int,
    val length: Int,
)

/**
 * An immutable source-built base for the normal SMEDIT export transaction.
 * [referenceRomSha256] identifies the unedited project snapshot against which
 * source changes were measured; [sourceOwnedRanges] makes those changes visible
 * to the shared ROM ownership/conflict planner. [generatedAssetClaims] keeps
 * staged editor data separate from source the user authored directly, while
 * [generatedPatchClaims] identifies patches selected for the ASM backend.
 */
internal data class AsmCompiledBuildBase(
    val romBytes: ByteArray,
    /** Headerless immutable snapshot output, reusable by later passes in the same build. */
    val referenceRomBody: ByteArray,
    val referenceRomSha256: String,
    val sourceOwnedRanges: List<AsmSourceOwnedRange>,
    val generatedAssetClaims: List<AsmGeneratedAssetClaim> = emptyList(),
    val generatedPatchClaims: List<AsmGeneratedPatchClaim> = emptyList(),
    val assemblerVersion: String,
    val compilerOutput: List<String>,
    val expectedFinalRomSha256: String? = null,
)

internal fun AsmCompiledBuildBase.copyForReuse(): AsmCompiledBuildBase = copy(
    romBytes = romBytes.copyOf(),
    referenceRomBody = referenceRomBody.copyOf(),
    sourceOwnedRanges = sourceOwnedRanges.toList(),
    generatedAssetClaims = generatedAssetClaims.map { it.copy(bytes = it.bytes.copyOf()) },
    generatedPatchClaims = generatedPatchClaims.map { it.copy(bytes = it.bytes.copyOf()) },
    compilerOutput = compilerOutput.toList(),
)

internal class AsmCompilationException(message: String) : IllegalStateException(message)

/** Small session-local LRU. Entries are copied at the boundary so no exporter
 * can mutate a cached ROM or generated claim. */
private object AsmCompiledBuildCache {
    private const val MAX_ENTRIES = 6
    private val entries = object : LinkedHashMap<String, AsmCompiledBuildBase>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, AsmCompiledBuildBase>?,
        ): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: String): AsmCompiledBuildBase? = entries[key]?.copyForReuse()

    @Synchronized
    fun put(key: String, value: AsmCompiledBuildBase) {
        entries[key] = value.copyForReuse()
    }

    @Synchronized
    fun clear() = entries.clear()
}

/** Clean, out-of-tree Asar compiler for a project-owned ASM workspace. */
internal class AsmProjectCompiler(
    private val workspaceRepository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
    private val toolchain: AsmToolchain = AsmToolchain(),
) {
    private data class ResolvedToolchain(
        val executable: File,
        val length: Long,
        val lastModified: Long,
        val version: String,
    )

    @Volatile
    private var resolvedToolchain: ResolvedToolchain? = null

    fun compile(
        projectFilePath: String,
        loadedRom: ByteArray,
        materialization: AsmAssetMaterialization = AsmAssetMaterialization.EMPTY,
        patchMaterialization: AsmPatchMaterialization = AsmPatchMaterialization.EMPTY,
        referenceRomBody: ByteArray? = null,
        onProgress: (String) -> Unit = {},
    ): AsmCompiledBuildBase {
        val workspace = workspaceRepository.loadForBuild(projectFilePath)
            ?: throw AsmCompilationException("Project ASM is enabled, but its local workspace is missing or incomplete")
        val resolved = resolveToolchain(workspace.workingDirectory, onProgress)
        val asar = resolved.executable
        val version = resolved.version
        val cacheKey = buildCacheKey(
            workspace = workspace,
            asar = asar,
            assemblerVersion = version,
            loadedRom = loadedRom,
            materialization = materialization,
            patchMaterialization = patchMaterialization,
            referenceRomBody = referenceRomBody,
        )
        AsmCompiledBuildCache.get(cacheKey)?.let { cached ->
            val label = if (materialization.isEmpty && patchMaterialization.isEmpty) {
                "ASM source unchanged — reusing compiled base…"
            } else {
                "Generated ASM inputs unchanged — reusing compiled base…"
            }
            onProgress(label)
            return cached
        }

        val reference = if (referenceRomBody == null) {
            onProgress("Compiling immutable ASM snapshot…")
            compileTree(workspace.originalDirectory, asar)
        } else {
            require(referenceRomBody.size == ASM_ROM_SIZE) {
                "Reusable immutable ASM snapshot has ${referenceRomBody.size} bytes; expected $ASM_ROM_SIZE"
            }
            onProgress("Reusing immutable ASM snapshot…")
            CompiledTree(referenceRomBody.copyOf(), emptyList())
        }
        onProgress("Compiling project ASM source…")
        val project = compileTree(
            sourceTree = workspace.workingDirectory,
            asar = asar,
            assetOverrides = materialization.assetOverrides,
            generatedPatchSource = patchMaterialization.source,
        )
        if (reference.rom.size != project.rom.size) {
            throw AsmCompilationException(
                "Project ASM produced ${project.rom.size} bytes; the immutable snapshot produced ${reference.rom.size}"
            )
        }

        val headerSize = if (loadedRom.size % 0x8000 == 0x200) 0x200 else 0
        val expectedBodySize = loadedRom.size - headerSize
        if (project.rom.size != expectedBodySize) {
            throw AsmCompilationException(
                "ASM produced ${project.rom.size} bytes, but the loaded ROM body is $expectedBodySize bytes"
            )
        }
        materialization.claims.forEach { claim ->
            validateCompiledClaim(project.rom, claim.pcOffset, claim.bytes, "source asset ${claim.assetPath}")
        }
        patchMaterialization.claims.forEach { claim ->
            validateCompiledClaim(project.rom, claim.pcOffset, claim.bytes, "patch ${claim.owner}/${claim.label}")
        }
        val output = if (headerSize == 0) {
            project.rom
        } else {
            loadedRom.copyOfRange(0, headerSize) + project.rom
        }
        return AsmCompiledBuildBase(
            romBytes = output,
            referenceRomBody = reference.rom,
            referenceRomSha256 = sha256(reference.rom),
            // The internal-header checksum/complement are Asar build metadata,
            // not authored source. Downstream SMEDIT writes may legitimately
            // replace them (community sprite IPS files commonly do), so keep
            // them out of source ownership.
            sourceOwnedRanges = changedRanges(
                reference.rom,
                project.rom,
                listOf(SNES_CHECKSUM_RANGE) +
                    materialization.claims.map { it.pcOffset until it.endExclusive } +
                    patchMaterialization.claims.map { it.pcOffset until it.endExclusive },
            ),
            generatedAssetClaims = materialization.claims,
            generatedPatchClaims = patchMaterialization.claims,
            assemblerVersion = version.lineSequence().firstOrNull().orEmpty(),
            compilerOutput = (reference.output + project.output).distinct(),
        ).also { compiled -> AsmCompiledBuildCache.put(cacheKey, compiled) }
    }

    /** Exact fingerprint used by the higher-level prepared-base cache. */
    fun sourceInputsFingerprint(
        projectFilePath: String,
        loadedRom: ByteArray,
        onProgress: (String) -> Unit = {},
    ): String {
        val workspace = workspaceRepository.loadForBuild(projectFilePath)
            ?: throw AsmCompilationException("Project ASM is enabled, but its local workspace is missing or incomplete")
        val resolved = resolveToolchain(workspace.workingDirectory, onProgress)
        return buildCacheKey(
            workspace = workspace,
            asar = resolved.executable,
            assemblerVersion = resolved.version,
            loadedRom = loadedRom,
            materialization = AsmAssetMaterialization.EMPTY,
            patchMaterialization = AsmPatchMaterialization.EMPTY,
            referenceRomBody = null,
        )
    }

    @Synchronized
    private fun resolveToolchain(referenceRoot: File, onProgress: (String) -> Unit): ResolvedToolchain {
        resolvedToolchain?.takeIf { cached ->
            cached.executable.isFile &&
                cached.executable.length() == cached.length &&
                cached.executable.lastModified() == cached.lastModified
        }?.let { return it }
        val executable = toolchain.resolve(referenceRoot, onProgress)
        return ResolvedToolchain(
            executable = executable,
            length = executable.length(),
            lastModified = executable.lastModified(),
            version = toolchain.version(executable),
        ).also { resolvedToolchain = it }
    }

    private fun buildCacheKey(
        workspace: AsmProjectBuildWorkspace,
        asar: File,
        assemblerVersion: String,
        loadedRom: ByteArray,
        materialization: AsmAssetMaterialization,
        patchMaterialization: AsmPatchMaterialization,
        referenceRomBody: ByteArray?,
    ): String {
        val fingerprint = BuildFingerprint()
        fingerprint.addText(CACHE_FORMAT_VERSION)
        fingerprint.addText(assemblerVersion)
        fingerprint.addFile("asar", asar)
        fingerprint.addBytes("loaded-rom", loadedRom)
        if (referenceRomBody == null) {
            fingerprint.addTree("reference-src", File(workspace.originalDirectory, "src"))
            fingerprint.addTree("reference-data", File(workspace.originalDirectory, "data"))
        } else {
            fingerprint.addBytes("reference-rom", referenceRomBody)
        }
        fingerprint.addTree("project-src", File(workspace.workingDirectory, "src"))
        fingerprint.addTree("project-data", File(workspace.workingDirectory, "data"))
        materialization.assetOverrides.toSortedMap().forEach { (path, bytes) ->
            fingerprint.addBytes("asset:$path", bytes)
        }
        materialization.claims
            .sortedWith(compareBy({ it.pcOffset }, { it.assetPath }, { it.owner }, { it.label }))
            .forEach { claim ->
                fingerprint.addText("asset-claim:${claim.owner}:${claim.label}:${claim.assetPath}:${claim.pcOffset}")
                fingerprint.addBytes("asset-claim-bytes", claim.bytes)
            }
        fingerprint.addText("generated-patch-source:${patchMaterialization.source}")
        patchMaterialization.claims
            .sortedWith(compareBy({ it.pcOffset }, { it.owner }, { it.label }))
            .forEach { claim ->
                fingerprint.addText("patch-claim:${claim.owner}:${claim.label}:${claim.pcOffset}")
                fingerprint.addBytes("patch-claim-bytes", claim.bytes)
            }
        return fingerprint.finish()
    }

    private fun compileTree(
        sourceTree: File,
        asar: File,
        assetOverrides: Map<String, ByteArray> = emptyMap(),
        generatedPatchSource: String = "",
    ): CompiledTree {
        require(File(sourceTree, "src/main.asm").isFile) { "ASM source tree has no src/main.asm: $sourceTree" }
        require(File(sourceTree, "data").isDirectory) { "ASM source tree has no extracted data: $sourceTree" }
        val staging = Files.createTempDirectory("smedit-asm-build-").toFile()
        try {
            copyTree(File(sourceTree, "src"), File(staging, "src"))
            copyTree(File(sourceTree, "data"), File(staging, "data"))
            applyAssetOverrides(File(staging, "data"), assetOverrides)
            applyGeneratedPatchSource(File(staging, "src"), generatedPatchSource)
            val rom = File(staging, "SM.sfc")
            rom.writeBytes(ByteArray(ASM_ROM_SIZE) { 0xFF.toByte() })
            val symbols = File(staging, "symbols.sym")
            val result = runProcess(
                command = listOf(
                    asar.absolutePath,
                    "--no-title-check",
                    "--symbols=wla",
                    "--symbols-path=${symbols.name}",
                    "src/main.asm",
                    rom.name,
                ),
                workingDirectory = staging,
                timeoutMinutes = 5,
            )
            if (result.exitCode != 0) {
                throw AsmCompilationException(
                    buildString {
                        append("Asar failed with exit code ${result.exitCode}")
                        result.output.takeLast(40).forEach { append("\n").append(it) }
                    }
                )
            }
            if (!rom.isFile || rom.length() != ASM_ROM_SIZE.toLong()) {
                throw AsmCompilationException("Asar did not produce the expected $ASM_ROM_SIZE-byte SM.sfc")
            }
            if (!symbols.isFile || symbols.length() == 0L) {
                throw AsmCompilationException("Asar completed without a symbol map")
            }
            return CompiledTree(rom.readBytes(), result.output)
        } finally {
            deleteTree(staging)
        }
    }

    private fun validateCompiledClaim(
        rom: ByteArray,
        pcOffset: Int,
        bytes: ByteArray,
        description: String,
    ) {
        val endExclusive = pcOffset + bytes.size
        require(pcOffset >= 0 && endExclusive <= rom.size) {
            "Generated ASM $description claim is outside the compiled ROM"
        }
        if (!rom.copyOfRange(pcOffset, endExclusive).contentEquals(bytes)) {
            throw AsmCompilationException(
                "Generated ASM $description was not assembled at PC 0x${pcOffset.toString(16).uppercase()}"
            )
        }
    }

    private fun applyGeneratedPatchSource(sourceRoot: File, source: String) {
        if (source.isBlank()) return
        val generated = File(sourceRoot, GENERATED_PATCH_FILE)
        require(!generated.exists()) { "Project source uses reserved SMEDIT file name $GENERATED_PATCH_FILE" }
        generated.writeText(source)
        val main = File(sourceRoot, "main.asm")
        val include = "; SMEDIT generated patch backend\nincsrc \"$GENERATED_PATCH_FILE\"\n\n"
        val original = main.readText()
        val marker = "print \"Assembly complete. Total bytes written: \", bytes"
        main.writeText(
            if (marker in original) original.replaceFirst(marker, include + marker)
            else original + "\n" + include
        )
    }

    private fun applyAssetOverrides(dataRoot: File, overrides: Map<String, ByteArray>) {
        val rootPath = dataRoot.canonicalFile.toPath()
        overrides.forEach { (relativePath, bytes) ->
            val target = rootPath.resolve(relativePath).normalize()
            require(target.startsWith(rootPath)) { "Unsafe generated ASM asset path: $relativePath" }
            require(Files.isRegularFile(target)) { "Generated ASM asset does not exist: $relativePath" }
            require(Files.size(target) == bytes.size.toLong()) {
                "Generated ASM asset $relativePath has ${bytes.size} bytes; expected ${Files.size(target)}"
            }
            Files.write(target, bytes)
        }
    }

    private fun copyTree(source: File, destination: File) {
        val sourcePath = source.canonicalFile.toPath()
        val destinationPath = destination.toPath().normalize()
        Files.walk(sourcePath).use { paths ->
            paths.forEach { input ->
                val output = destinationPath.resolve(sourcePath.relativize(input).toString()).normalize()
                require(output.startsWith(destinationPath)) { "Unsafe ASM build path: $output" }
                if (Files.isDirectory(input)) Files.createDirectories(output)
                else {
                    Files.createDirectories(output.parent)
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    private fun deleteTree(root: File) {
        if (!root.exists()) return
        val temporaryRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile.toPath()
        val rootPath = root.canonicalFile.toPath()
        require(rootPath.startsWith(temporaryRoot) && root.name.startsWith("smedit-asm-build-")) {
            "Refusing to remove unmanaged ASM build directory: $root"
        }
        Files.walk(rootPath).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private data class CompiledTree(val rom: ByteArray, val output: List<String>)

    companion object {
        const val ASM_ROM_SIZE = 3 * 1024 * 1024
        private const val GENERATED_PATCH_FILE = "__smedit_generated_patches.asm"
        private const val CACHE_FORMAT_VERSION = "smedit-asm-build-cache-v1"

        internal fun clearBuildCacheForTests() = AsmCompiledBuildCache.clear()

        internal fun changedRanges(
            reference: ByteArray,
            project: ByteArray,
            excludedOffsets: List<IntRange> = emptyList(),
        ): List<AsmSourceOwnedRange> {
            require(reference.size == project.size) { "ASM build products must have equal sizes" }
            val excluded = BooleanArray(reference.size)
            excludedOffsets.forEach { range ->
                require(range.first >= 0 && range.last < reference.size) {
                    "ASM ownership exclusion is outside the compiled ROM: $range"
                }
                for (index in range) excluded[index] = true
            }
            val ranges = mutableListOf<AsmSourceOwnedRange>()
            var offset = 0
            while (offset < reference.size) {
                if (excluded[offset] || reference[offset] == project[offset]) {
                    offset++
                    continue
                }
                val start = offset
                do {
                    offset++
                } while (
                    offset < reference.size &&
                    !excluded[offset] &&
                    reference[offset] != project[offset]
                )
                ranges += AsmSourceOwnedRange(start, offset - start)
            }
            return ranges
        }

        private val SNES_CHECKSUM_RANGE = 0x007FDC..0x007FDF
    }
}

private class BuildFingerprint {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun addText(value: String) = addBytes("text", value.toByteArray(Charsets.UTF_8))

    fun addFile(label: String, file: File) {
        require(file.isFile) { "ASM build input is missing: $file" }
        addBytes(label, file.readBytes())
    }

    fun addTree(label: String, root: File) {
        require(root.isDirectory) { "ASM build input tree is missing: $root" }
        addText("tree:$label")
        root.walkTopDown()
            .filter(File::isFile)
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                addBytes(file.relativeTo(root).invariantSeparatorsPath, file.readBytes())
            }
    }

    fun addBytes(label: String, bytes: ByteArray) {
        val labelBytes = label.toByteArray(Charsets.UTF_8)
        addInt(labelBytes.size)
        digest.update(labelBytes)
        addInt(bytes.size)
        digest.update(bytes)
    }

    fun finish(): String = digest.digest().joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private fun addInt(value: Int) {
        digest.update((value ushr 24).toByte())
        digest.update((value ushr 16).toByte())
        digest.update((value ushr 8).toByte())
        digest.update(value.toByte())
    }
}

/** Resolves an exact Asar 1.81 executable without trusting an arbitrary version. */
internal class AsmToolchain(
    private val homeDirectory: File = File(System.getProperty("user.home")),
    private val environment: Map<String, String> = System.getenv(),
) {
    fun resolve(referenceRoot: File, onProgress: (String) -> Unit = {}): File {
        val candidates = buildList {
            environment["SMEDIT_ASAR"]?.takeIf(String::isNotBlank)?.let { add(File(it)) }
            System.getProperty("smedit.asar")?.takeIf(String::isNotBlank)?.let { add(File(it)) }
            val suffix = if (isWindows()) ".exe" else ""
            add(File(homeDirectory, ".smedit/asm/asar/build/asar/asar-standalone$suffix"))
            add(File(referenceRoot, "tools/asar-standalone$suffix"))
            if (isWindows()) add(File(referenceRoot, "tools/asar.exe"))
            workspaceAncestors().forEach { root ->
                add(File(root, "parity/work/asar/build/asar/asar-standalone$suffix"))
            }
        }.distinctBy { it.absolutePath }
        candidates.firstOrNull(::isExpectedAsar)?.let { return it }
        return buildManagedAsar(onProgress)
    }

    fun version(executable: File): String {
        val result = runCatching {
            runProcess(listOf(executable.absolutePath, "--version"), executable.parentFile, timeoutMinutes = 1)
        }.getOrElse { throw AsmCompilationException("Could not run Asar at ${executable.absolutePath}: ${it.message}") }
        // Asar 1.81 prints the correct version banner for --version but exits
        // non-zero after also showing its usage text on some platforms.
        if (result.output.none { it.contains("Asar $ASAR_VERSION") }) {
            throw AsmCompilationException("SMEDIT requires Asar $ASAR_VERSION: ${executable.absolutePath}")
        }
        return result.output.joinToString("\n")
    }

    private fun isExpectedAsar(candidate: File): Boolean = candidate.isFile && runCatching {
        version(candidate)
        true
    }.getOrDefault(false)

    private fun buildManagedAsar(onProgress: (String) -> Unit): File {
        val managed = File(homeDirectory, ".smedit/asm/asar")
        val suffix = if (isWindows()) ".exe" else ""
        val expected = File(managed, "build/asar/asar-standalone$suffix")
        if (isExpectedAsar(expected)) return expected
        val parent = managed.parentFile.also(File::mkdirs)
        val staging = File(parent, "asar.installing-${UUID.randomUUID()}")
        var backup: File? = null
        try {
            onProgress("Downloading pinned Asar $ASAR_VERSION source…")
            requireSuccessful(
                runProcess(
                    listOf("git", "clone", "--filter=blob:none", "--no-checkout", ASAR_REPOSITORY_URL, staging.absolutePath),
                    parent,
                    5,
                ),
                "Could not download Asar; install Git/CMake or set SMEDIT_ASAR",
            )
            requireSuccessful(
                runProcess(listOf("git", "checkout", "--detach", ASAR_COMMIT), staging, 2),
                "Could not select SMEDIT's pinned Asar revision",
            )
            onProgress("Building pinned Asar $ASAR_VERSION…")
            requireSuccessful(
                runProcess(
                    listOf("cmake", "-S", "src", "-B", "build", "-DCMAKE_BUILD_TYPE=Release"),
                    staging,
                    5,
                ),
                "Could not configure Asar; install CMake or set SMEDIT_ASAR",
            )
            requireSuccessful(
                runProcess(
                    listOf("cmake", "--build", "build", "--target", "asar-standalone", "--parallel"),
                    staging,
                    10,
                ),
                "Could not build Asar",
            )
            val stagedExecutable = File(staging, "build/asar/asar-standalone$suffix")
            if (!isExpectedAsar(stagedExecutable)) {
                throw AsmCompilationException("Managed Asar build did not produce Asar $ASAR_VERSION")
            }
            if (managed.exists()) {
                backup = File(parent, "asar.backup-${UUID.randomUUID()}")
                moveDirectory(managed, checkNotNull(backup))
            }
            try {
                moveDirectory(staging, managed)
            } catch (problem: Exception) {
                if (!managed.exists() && backup?.exists() == true) runCatching {
                    moveDirectory(checkNotNull(backup), managed)
                    backup = null
                }
                throw problem
            }
            return expected
        } catch (problem: AsmCompilationException) {
            throw problem
        } catch (problem: Exception) {
            throw AsmCompilationException("Could not prepare Asar $ASAR_VERSION: ${problem.message}")
        } finally {
            deleteManagedTree(parent, staging)
            backup?.let { deleteManagedTree(parent, it) }
        }
    }

    private fun moveDirectory(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun deleteManagedTree(parent: File, target: File) {
        if (!target.exists()) return
        val parentPath = parent.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        require(targetPath.startsWith(parentPath) && targetPath != parentPath) {
            "Refusing to remove unmanaged Asar path: $target"
        }
        Files.walk(targetPath).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun requireSuccessful(result: ProcessResult, message: String) {
        if (result.exitCode == 0) return
        throw AsmCompilationException(
            "$message (exit ${result.exitCode})" + result.output.takeLast(20).joinToString("\n", prefix = "\n")
        )
    }

    private fun workspaceAncestors(): List<File> {
        val roots = mutableListOf<File>()
        var cursor: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            cursor?.let(roots::add)
            cursor = cursor?.parentFile
        }
        return roots
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").contains("Windows", ignoreCase = true)

    private companion object {
        const val ASAR_REPOSITORY_URL = "https://github.com/RPGHacker/asar.git"
        const val ASAR_COMMIT = "a8538ca8582cdc81de6941223b358aa851e3b7b1"
        const val ASAR_VERSION = "1.81"
    }
}

private data class ProcessResult(val exitCode: Int, val output: List<String>)

private fun runProcess(command: List<String>, workingDirectory: File, timeoutMinutes: Long): ProcessResult {
    val log = Files.createTempFile("smedit-process-", ".log").toFile()
    try {
        val process = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw AsmCompilationException("Command timed out: ${command.firstOrNull()}")
        }
        return ProcessResult(process.exitValue(), log.takeIf(File::isFile)?.readLines().orEmpty())
    } catch (problem: AsmCompilationException) {
        throw problem
    } catch (problem: Exception) {
        throw AsmCompilationException("Could not run ${command.firstOrNull()}: ${problem.message}")
    } finally {
        Files.deleteIfExists(log.toPath())
    }
}

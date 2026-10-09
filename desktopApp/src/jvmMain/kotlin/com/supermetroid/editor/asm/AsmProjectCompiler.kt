package com.supermetroid.editor.asm

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * to the shared ROM ownership/conflict planner.
 */
internal data class AsmCompiledBuildBase(
    val romBytes: ByteArray,
    val referenceRomSha256: String,
    val sourceOwnedRanges: List<AsmSourceOwnedRange>,
    val assemblerVersion: String,
    val compilerOutput: List<String>,
)

internal class AsmCompilationException(message: String) : IllegalStateException(message)

/** Clean, out-of-tree Asar compiler for a project-owned ASM workspace. */
internal class AsmProjectCompiler(
    private val workspaceRepository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
    private val toolchain: AsmToolchain = AsmToolchain(),
) {
    fun compile(
        projectFilePath: String,
        loadedRom: ByteArray,
        onProgress: (String) -> Unit = {},
    ): AsmCompiledBuildBase {
        val workspace = workspaceRepository.load(projectFilePath)
            ?: throw AsmCompilationException("Project ASM is enabled, but its local workspace is missing or incomplete")
        val asar = toolchain.resolve(workspace.referenceWorkspace.root, onProgress)
        val version = toolchain.version(asar)

        onProgress("Compiling immutable ASM snapshot…")
        val reference = compileTree(workspace.originalDirectory, asar)
        onProgress("Compiling project ASM source…")
        val project = compileTree(workspace.workingDirectory, asar)
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
        val output = if (headerSize == 0) {
            project.rom
        } else {
            loadedRom.copyOfRange(0, headerSize) + project.rom
        }
        return AsmCompiledBuildBase(
            romBytes = output,
            referenceRomSha256 = sha256(reference.rom),
            // The internal-header checksum/complement are Asar build metadata,
            // not authored source. Downstream SMEDIT writes may legitimately
            // replace them (community sprite IPS files commonly do), so keep
            // them out of source ownership.
            sourceOwnedRanges = changedRanges(reference.rom, project.rom, SNES_CHECKSUM_RANGE),
            assemblerVersion = version.lineSequence().firstOrNull().orEmpty(),
            compilerOutput = (reference.output + project.output).distinct(),
        )
    }

    private fun compileTree(sourceTree: File, asar: File): CompiledTree {
        require(File(sourceTree, "src/main.asm").isFile) { "ASM source tree has no src/main.asm: $sourceTree" }
        require(File(sourceTree, "data").isDirectory) { "ASM source tree has no extracted data: $sourceTree" }
        val staging = Files.createTempDirectory("smedit-asm-build-").toFile()
        try {
            copyTree(File(sourceTree, "src"), File(staging, "src"))
            copyTree(File(sourceTree, "data"), File(staging, "data"))
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

        internal fun changedRanges(
            reference: ByteArray,
            project: ByteArray,
            excludedOffsets: IntRange = IntRange.EMPTY,
        ): List<AsmSourceOwnedRange> {
            require(reference.size == project.size) { "ASM build products must have equal sizes" }
            val ranges = mutableListOf<AsmSourceOwnedRange>()
            var offset = 0
            while (offset < reference.size) {
                if (offset in excludedOffsets || reference[offset] == project[offset]) {
                    offset++
                    continue
                }
                val start = offset
                do {
                    offset++
                } while (
                    offset < reference.size &&
                    offset !in excludedOffsets &&
                    reference[offset] != project[offset]
                )
                ranges += AsmSourceOwnedRange(start, offset - start)
            }
            return ranges
        }

        private val SNES_CHECKSUM_RANGE = 0x007FDC..0x007FDF
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

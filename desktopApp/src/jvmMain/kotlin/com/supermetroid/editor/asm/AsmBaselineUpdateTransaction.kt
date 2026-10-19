package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Two-directory installation journal. Interrupted updates roll back on the next workspace load. */
internal object AsmBaselineUpdateTransaction {
    @Serializable private data class Journal(val id: String, val committed: Boolean = false)
    private fun journal(paths: ProjectPaths) = File(paths.cache, "asm-baseline-update.json")
    private fun backup(paths: ProjectPaths, id: String) = File(paths.root, "asm-backups/$id")
    private fun cachedBackup(paths: ProjectPaths, id: String) = File(paths.cache, "asm-update-backups/$id")

    fun install(projectFilePath: String, staging: File, afterMove: (Int) -> Unit = {}): File {
        val paths = ProjectPaths.forProject(projectFilePath)
        recover(projectFilePath)
        requireManaged(paths, paths.asm, paths.asmCache, staging)
        val state = Journal(UUID.randomUUID().toString())
        val backup = backup(paths, state.id)
        val cacheBackup = cachedBackup(paths, state.id)
        requireManaged(paths, backup, cacheBackup)
        backup.mkdirs()
        cacheBackup.mkdirs()
        write(journal(paths), state)
        try {
            move(paths.asm, File(backup, "asm")); afterMove(1)
            move(paths.asmCache, File(cacheBackup, "cache")); afterMove(2)
            move(File(staging, "asm"), paths.asm); afterMove(3)
            move(File(staging, "smedit/asm"), paths.asmCache); afterMove(4)
            write(journal(paths), state.copy(committed = true))
        } catch (problem: Exception) {
            try { recover(projectFilePath) } catch (recovery: Exception) { problem.addSuppressed(recovery) }
            throw problem
        }
        // Installation has committed. A cleanup failure must not misreport it as a failed update.
        runCatching { Files.deleteIfExists(journal(paths).toPath()) }
        runCatching { staging.deleteRecursively() }
        return backup
    }

    fun recover(projectFilePath: String) {
        val paths = ProjectPaths.forProject(projectFilePath)
        val file = journal(paths)
        if (!file.exists()) return
        requireManaged(paths, file, paths.asm, paths.asmCache)
        require(file.isFile && file.length() in 1..1024) { "Invalid ASM baseline-update journal" }
        val state = Json.decodeFromString(Journal.serializer(), file.readText())
        require(runCatching { UUID.fromString(state.id).toString() == state.id }.getOrDefault(false)) { "Invalid ASM update backup identity" }
        requireManaged(paths, backup(paths, state.id), cachedBackup(paths, state.id))
        if (!state.committed) {
            val backup = backup(paths, state.id)
            val cacheBackup = cachedBackup(paths, state.id).apply { mkdirs() }
            // Preserve any partly installed tree, including external edits made after interruption.
            for ((active, old, rejected) in listOf(
                Triple(paths.asm, File(backup, "asm"), File(cacheBackup, "uncommitted-asm")),
                Triple(paths.asmCache, File(cacheBackup, "cache"), File(cacheBackup, "uncommitted-cache")),
            )) {
                requireManaged(paths, active, old, rejected)
                if (old.exists()) {
                    if (active.exists()) move(active, File(rejected.parentFile, "${rejected.name}-${UUID.randomUUID()}"))
                    move(old, active)
                }
            }
        }
        Files.delete(file.toPath())
    }

    private fun write(file: File, state: Journal) {
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}-${UUID.randomUUID()}")
        try {
            temporary.writeText(Json.encodeToString(Journal.serializer(), state))
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    private fun move(from: File, to: File) {
        to.parentFile.mkdirs()
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath()) }
    }

    private fun requireManaged(paths: ProjectPaths, vararg targets: File) {
        val root = paths.root.canonicalFile.toPath()
        targets.forEach { target ->
            val resolved = target.canonicalFile.toPath()
            require(resolved.startsWith(root) && resolved != root && !Files.isSymbolicLink(target.toPath())) {
                "Refusing an ASM update outside the project: $target"
            }
        }
    }
}

package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal data class AsmBaselineBackup(
    val projectFilePath: String,
    val id: String,
    val createdAtMillis: Long,
    val revision: String?,
    val bytes: Long?,
    val problem: String?,
    internal val fingerprint: String?,
)

internal data class AsmBaselineBackupList(val entries: List<AsmBaselineBackup>, val notice: String? = null)

/** Read-only discovery; deletion is restricted to a confirmed, unchanged pair of UUID backup trees. */
internal class AsmBaselineBackupRepository(
    private val projects: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
) {
    private data class Roots(val project: Path, val authored: Path, val cached: Path, val journal: Path)

    private fun roots(projectFilePath: String): Roots {
        val project = ProjectPaths.forProject(projectFilePath).root.toPath().toRealPath()
        fun managed(relative: String): Path {
            var path = project
            relative.split('/').forEach { part ->
                path = path.resolve(part)
                require(!Files.isSymbolicLink(path)) { "Backup management cannot follow symbolic links: $path" }
            }
            return path
        }
        return Roots(project, managed("asm-backups"), managed("smedit/asm-update-backups"), managed("smedit/asm-baseline-update.json"))
    }

    fun list(projectFilePath: String): AsmBaselineBackupList = projects.withBaselineUpdateLock(projectFilePath) {
        val roots = roots(projectFilePath)
        val interrupted = Files.exists(roots.journal, NOFOLLOW_LINKS)
        val ids = linkedSetOf<String>()
        var unrecognized = false
        for (parent in listOf(roots.authored, roots.cached)) {
            if (!Files.exists(parent, NOFOLLOW_LINKS)) continue
            require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "Backup storage is not a directory: $parent" }
            Files.newDirectoryStream(parent).use { children ->
                children.forEach { child ->
                    val id = child.fileName.toString()
                    if (id in setOf(".gitignore", ".DS_Store", "Thumbs.db", "desktop.ini")) return@forEach
                    if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) ids += id
                    else unrecognized = true
                    require(ids.size <= 4096) { "Too many ASM backups to inspect. Open the backup folder to manage them." }
                }
            }
        }
        AsmBaselineBackupList(ids.map { inspect(projectFilePath, roots, it, interrupted) }
            .sortedWith(compareByDescending<AsmBaselineBackup> { it.createdAtMillis }.thenBy { it.id }),
            when {
                interrupted -> "An ASM update needs recovery. Reopen the project before deleting backups."
                unrecognized -> "Unrecognized items in the backup folders were left untouched."
                else -> null
            })
    }

    private fun inspect(project: String, roots: Roots, id: String, interrupted: Boolean): AsmBaselineBackup {
        val authored = roots.authored.resolve(id)
        val cached = roots.cached.resolve(id)
        var created = 0L
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            var authoredBytes = 0L
            var count = 0
            for (tree in listOf(authored, cached)) {
                digest.update(tree.toString().toByteArray())
                if (!Files.exists(tree, NOFOLLOW_LINKS)) continue
                require(Files.isDirectory(tree, NOFOLLOW_LINKS)) { "This recovery copy contains a link or is not a directory." }
                val attributes = Files.readAttributes(tree, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (created == 0L) created = attributes.creationTime().toMillis().takeIf { it > 0 } ?: attributes.lastModifiedTime().toMillis()
                // No FOLLOW_LINKS: even the read-only size/fingerprint scan refuses links and special files.
                Files.walk(tree).use { paths ->
                    val entries = paths.iterator().asSequence().take(100_001).toList()
                    require(entries.size <= 100_000) { "This recovery copy has too many files to inspect safely." }
                    entries.sorted().forEach { path ->
                        require(++count <= 100_000) { "This recovery copy has too many files to inspect safely." }
                        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        require(!attrs.isSymbolicLink && (attrs.isDirectory || attrs.isRegularFile)) { "This recovery copy contains links or special files." }
                        val relative = tree.relativize(path).toString()
                        require(!relative.startsWith("uncommitted-")) { "Contains interrupted-update recovery data; inspect it in the backup folder." }
                        digest.update("$relative\u0000${attrs.size()}\u0000${attrs.lastModifiedTime()}\u0000${attrs.fileKey()}\u0000".toByteArray())
                        if (attrs.isRegularFile) {
                            bytes = Math.addExact(bytes, attrs.size())
                            // Authored recovery data must not change unnoticed, even at the same size/mtime.
                            if (tree == authored) {
                                authoredBytes = Math.addExact(authoredBytes, attrs.size())
                                require(attrs.size() <= 128L * 1024 * 1024 && authoredBytes <= 256L * 1024 * 1024) {
                                    "This ASM copy exceeds managed snapshot limits; inspect it in the backup folder."
                                }
                                Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                                    val buffer = ByteArray(8192)
                                    var read = input.read(buffer)
                                    while (read >= 0) { digest.update(buffer, 0, read); read = input.read(buffer) }
                                }
                            }
                        }
                    }
                }
            }
            val baseline = AsmSourceBaseline.load(authored.resolve("asm").toFile())
            require(baseline != null && Files.isRegularFile(authored.resolve("asm/base/source.zip"), NOFOLLOW_LINKS)) {
                "Incomplete recovery copy; inspect it in the backup folder."
            }
            AsmBaselineBackup(project, id, created, baseline.augmentationRevision, bytes,
                if (interrupted) "Needed while an ASM update is pending recovery." else null,
                digest.digest().joinToString("") { "%02x".format(it) })
        } catch (problem: Exception) {
            AsmBaselineBackup(project, id, created, null, null, problem.message ?: "Could not inspect this recovery copy.", null)
        }
    }

    /** Folder access uses a freshly validated fixed path, never a caller-supplied directory. */
    fun folder(projectFilePath: String, selected: AsmBaselineBackup): File {
        require(selected.projectFilePath == projectFilePath && validId(selected.id)) { "This backup belongs to another project." }
        val roots = roots(projectFilePath)
        val tree = listOf(roots.authored.resolve(selected.id), roots.cached.resolve(selected.id))
            .firstOrNull { Files.isDirectory(it, NOFOLLOW_LINKS) } ?: error("This backup no longer exists. Refresh the list.")
        return tree.toFile()
    }

    fun delete(projectFilePath: String, selected: AsmBaselineBackup) = projects.withBaselineUpdateLock(projectFilePath) {
        require(selected.projectFilePath == projectFilePath && validId(selected.id)) { "This backup belongs to another project." }
        val roots = roots(projectFilePath)
        require(!Files.exists(roots.journal, NOFOLLOW_LINKS)) { "An ASM update needs recovery. Reopen the project before deleting backups." }
        val current = inspect(projectFilePath, roots, selected.id, interrupted = false)
        require(selected.problem == null && current.problem == null && selected.fingerprint != null && selected.fingerprint == current.fingerprint) {
            current.problem ?: "This backup changed after confirmation. Refresh and review it again."
        }
        // Preserve the authored recovery tree if removal of its rebuildable companion cache fails.
        for (tree in listOf(roots.cached.resolve(selected.id), roots.authored.resolve(selected.id))) {
            if (!Files.exists(tree, NOFOLLOW_LINKS)) continue
            Files.walk(tree).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    var ancestor = roots.project
                    for (part in roots.project.relativize(path)) {
                        ancestor = ancestor.resolve(part)
                        require(!Files.isSymbolicLink(ancestor)) { "The recovery copy changed during deletion. Refresh and inspect the backup folder." }
                    }
                    Files.delete(path)
                }
            }
        }
    }

    private fun validId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
}

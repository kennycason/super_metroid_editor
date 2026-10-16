package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ProjectBaseRom
import com.supermetroid.editor.data.ProjectPaths
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

internal data class OpenProject(val file: File, val project: SmEditProject)

/** File operations shared by NEW, OPEN and missing-ROM recovery. No recursive discovery or migration. */
internal object ProjectLifecycle {
    /** Read-only ROMs have no saved project identity; their navigation preferences remain ROM-local. */
    fun preferenceKey(project: SmEditProject, projectFilePath: String, romPath: String): String =
        if (projectFilePath.isEmpty()) File(romPath).absolutePath else project.projectId

    fun projectBeside(selected: File): File? {
        if (selected.extension.equals("smedit", true)) return selected
        val directory = if (selected.isDirectory) selected else selected.absoluteFile.parentFile
        val projects = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("smedit", true) }
        require(projects.size <= 1) {
            "More than one .smedit file is in ${directory.name}. Open the desired .smedit file directly."
        }
        return projects.singleOrNull()
    }

    fun identity(file: File): ProjectBaseRom {
        val bytes = file.readBytes()
        val offset = if (bytes.size % 0x8000 == 512) 512 else 0
        require(bytes.size > offset) { "The selected ROM is empty." }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes, offset, bytes.size - offset)
        return ProjectBaseRom(file.name, digest.digest().joinToString("") { "%02x".format(it) }, bytes.size - offset)
    }

    fun verify(project: SmEditProject, rom: File) {
        require(rom.isFile) { "Locate the base ROM: ${project.baseRom?.originalFileName ?: rom.name}" }
        val expected = project.baseRom ?: return
        val actual = identity(rom)
        require(actual.sha256 == expected.sha256 && actual.size == expected.size) {
            "This ROM does not match ${expected.originalFileName}. Select the same original ROM/revision used by this project (a renamed copy is fine)."
        }
    }

    /** NEW always creates a new folder; it never adopts/overwrites an existing directory. */
    fun create(name: String, parent: File, base: File): OpenProject {
        ProjectPaths.requireFileName(name)
        ProjectPaths.requireFileName(base.name)
        require(base.extension.lowercase() in setOf("smc", "sfc")) { "Select an .smc or .sfc base ROM." }
        require(parent.isDirectory) { "Select an existing parent folder." }
        val baseIdentity = identity(base)
        require(RomParser.loadRom(base.absolutePath).roomCatalog.editable) {
            "This ROM cannot be used for an editable project. OPEN it to inspect it read-only."
        }
        val root = File(parent, name)
        require(!root.exists()) { "A folder named '$name' already exists. Choose another project name or location." }
        // The final project appears only after its complete base and metadata exist.
        val staging = Files.createTempDirectory(parent.toPath(), ".smedit-new-").toFile()
        try {
            val copiedBase = base.copyTo(File(staging, base.name), overwrite = false)
            val copiedIdentity = identity(copiedBase)
            require(copiedIdentity.sha256 == baseIdentity.sha256 && copiedIdentity.size == baseIdentity.size) {
                "The base ROM changed while the project was being created. Please try again."
            }
            val file = File(staging, "$name.smedit")
            val project = SmEditProject(
                romPath = File(staging, base.name).absolutePath,
                baseRom = baseIdentity,
            )
            check(ProjectFileService.saveProject(project, file.absolutePath, null, false) {}) { "Could not save the new project." }
            File(staging, ".gitignore").writeText(
                "# Local ROMs, emulator saves and generated artifacts. Authored asm/ and data/ are shareable.\n" +
                    "*.[sS][mM][cC]\n*.[sS][fF][cC]\n/smedit/\n/states/\n/exports/\n",
            )
            Files.move(staging.toPath(), root.toPath())
            val target = File(root, file.name)
            return OpenProject(target, ProjectFileService.loadProject(target))
        } finally {
            // Only the exact temporary directory created by this invocation.
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun importBase(open: OpenProject, selected: File): OpenProject {
        require(selected.extension.lowercase() in setOf("smc", "sfc")) { "Select an .smc or .sfc base ROM." }
        ProjectPaths.requireFileName(selected.name)
        verify(open.project, selected)
        require(RomParser.loadRom(selected.absolutePath).roomCatalog.editable) {
            "This ROM is not supported as an editable project base."
        }
        val paths = ProjectPaths(open.file)
        val target = File(paths.root, selected.name)
        if (target.canonicalFile != selected.canonicalFile) {
            require(!target.exists()) { "${target.name} already exists in this project. No file was replaced." }
            val expectedCopy = identity(selected)
            val temporary = Files.createTempFile(paths.root.toPath(), ".smedit-import-", ".tmp")
            try {
                Files.copy(selected.toPath(), temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                val copied = identity(temporary.toFile())
                require(copied.sha256 == expectedCopy.sha256 && copied.size == expectedCopy.size) {
                    "The base ROM changed while it was being copied. Please try again."
                }
                verify(open.project, temporary.toFile())
                Files.move(temporary, target.toPath())
            } finally { Files.deleteIfExists(temporary) }
        }
        val updated = open.project.copy(
            romPath = target.absolutePath,
            baseRom = open.project.baseRom ?: identity(target),
        )
        check(ProjectFileService.saveProject(updated, open.file.absolutePath, null, false) {}) {
            "The ROM was imported but the project could not be saved."
        }
        return open.copy(project = updated)
    }
}

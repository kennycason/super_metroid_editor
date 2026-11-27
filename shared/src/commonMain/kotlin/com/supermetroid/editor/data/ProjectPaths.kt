package com.supermetroid.editor.data

import java.io.File

/** One project per directory. Constructing paths never creates files or migrates old layouts. */
class ProjectPaths(projectFile: File) {
    val projectFile: File = projectFile.absoluteFile.normalize()
    val root: File = this.projectFile.parentFile
    val name: String = this.projectFile.nameWithoutExtension
    val asm: File get() = File(root, "asm")
    val data: File get() = File(root, "data")
    /** Exact user-supplied images retained for provenance; builds never read from this directory. */
    val sourceImages: File get() = File(data, "source-images")
    val emulatorStates: File get() = File(root, "states")
    val exports: File get() = File(root, "exports")
    val cache: File get() = File(root, "smedit")
    val asmCache: File get() = asmCacheIn(root)
    val asmBuild: File get() = File(asmCache, "build")

    fun resolveBaseRom(path: String): File =
        if (File(path).isAbsolute) File(path) else File(root, path)

    fun relativeBaseRom(file: File): String {
        require(file.canonicalFile.parentFile == root.canonicalFile) {
            "The base ROM must live directly in the project folder. Locate/import it before saving."
        }
        return file.name
    }

    fun romOutput(project: SmEditProject): File {
        requireFileName(name)
        val build = project.buildName.trim()
        if (build.isNotEmpty()) requireFileName(build)
        val suffix = listOf(build, "v${project.versionMajor}.${project.versionMinor}")
            .filter { it.isNotEmpty() }.joinToString("-")
        val output = File(root, "$name-$suffix.smc")
        require(output.canonicalFile.parentFile == root.canonicalFile) { "Export must remain inside the project folder." }
        require(output.canonicalFile != resolveBaseRom(project.romPath).canonicalFile) {
            "Export would overwrite the base ROM. Change the build name or version."
        }
        return output
    }

    companion object {
        fun forProject(path: String): ProjectPaths = ProjectPaths(File(path))
        fun asmCacheIn(projectRoot: File): File = File(projectRoot, "smedit/asm")

        /** Safe on all supported platforms, including Windows. */
        fun requireFileName(name: String) {
            require(name.isNotBlank() && name == name.trim() && name != "." && name != ".." &&
                !name.endsWith('.') && name.none { it.code < 32 || it in "<>:\"/\\|?*" } &&
                !Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?").matches(name)) {
                "Use a file name without path separators or reserved characters."
            }
        }
    }
}

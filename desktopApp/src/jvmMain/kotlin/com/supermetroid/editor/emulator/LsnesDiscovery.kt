package com.supermetroid.editor.emulator

import java.io.File

/** Locates a user-installed popup-free worker build of lsnes rr2-beta25. */
object LsnesDiscovery {
    const val MISSING_EXTRA_MESSAGE =
        "lsnes b25 extra is not installed. It is not bundled with SMEDIT. On Linux: " +
            "git submodule update --init --recursive tools/lsnes && " +
            "./gradlew buildLsnesWorker installLsnesWorker " +
            "(or set SMEDIT_LSNES_PATH / browse in Settings)."

    private const val UNIX_NAME = "smedit-lsnes-worker"
    private const val WINDOWS_NAME = "$UNIX_NAME.exe"

    fun findWorker(explicitPath: String? = null): String? {
        val candidates = buildList {
            explicitPath?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            System.getenv("SMEDIT_LSNES_PATH")?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            addAll(candidatePaths())
        }
        return candidates.asSequence()
            .map(::File)
            .firstOrNull { it.isFile && it.canExecute() && isWorkerExecutable(it) }
            ?.absolutePath
    }

    internal fun candidatePaths(
        osName: String = System.getProperty("os.name", ""),
        userHome: String = System.getProperty("user.home", ""),
        userDir: String = System.getProperty("user.dir", ""),
        resourcesDir: String? = System.getProperty("compose.application.resources.dir"),
        path: String? = System.getenv("PATH"),
    ): List<String> {
        val windows = osName.lowercase().contains("win")
        val executableName = if (windows) WINDOWS_NAME else UNIX_NAME
        return buildList {
            add(File(userHome, ".smedit/lsnes/$executableName").path)
            add(File(userHome, ".smedit/extras/lsnes/$executableName").path)
            add(File(userDir, "tools/lsnes-smedit/bin/$executableName").path)
            add(File(userDir, "../tools/lsnes-smedit/bin/$executableName").path)
            if (windows) {
                System.getenv("LOCALAPPDATA")?.let { add(File(it, "SMEDIT/lsnes/$executableName").path) }
            } else {
                add("/usr/local/bin/$executableName")
                add("/usr/bin/$executableName")
            }
            path.orEmpty().split(File.pathSeparatorChar)
                .filter { it.isNotBlank() }
                .forEach { add(File(it, executableName).path) }
            // Last resort: a user-copied worker next to the app, not a shipped extra.
            resourcesDir?.let { add(File(it, executableName).path) }
        }.distinct()
    }

    fun isWorkerExecutable(file: File): Boolean =
        file.name.equals(UNIX_NAME, ignoreCase = true) ||
            file.name.equals(WINDOWS_NAME, ignoreCase = true)
}

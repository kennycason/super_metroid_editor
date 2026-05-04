package com.supermetroid.editor.libretro

import java.io.File

/**
 * Discovers a SNES libretro core on the system.
 * Checks explicit config path first, then common installation locations.
 */
object LibretroCoreDiscovery {

    private val PREFERRED_CORES = listOf("bsnes", "snes9x", "mesen-s")

    private val SEARCH_DIRS = buildList {
        val home = System.getProperty("user.home")
        val cwd = System.getProperty("user.dir")

        // Packaged app resources (Compose Desktop sets this in distributed builds)
        System.getProperty("compose.application.resources.dir")?.let { add(it) }

        // Project-local: submodule build output (highest priority)
        add("$cwd/tools/snes9x/libretro")
        // When run from a submodule dir (e.g. desktopApp/), check parent
        add("$cwd/../tools/snes9x/libretro")

        // SMEDIT user cores dir
        add("$home/.smedit/cores")

        // Project-local cores dir
        add("$cwd/cores")
        add("$cwd/../cores")

        // System-wide libretro cores (Linux)
        add("/usr/lib/libretro")
        add("/usr/lib64/libretro")
        add("/usr/local/lib/libretro")

        // macOS Homebrew (Apple Silicon + Intel)
        add("/opt/homebrew/lib/retroarch/cores")
        add("/usr/local/lib/retroarch/cores")
        // RetroArch.app bundle
        add("/Applications/RetroArch.app/Contents/Resources/cores")

        // RetroArch user config (Linux/macOS)
        add("$home/.config/retroarch/cores")

        // Flatpak RetroArch (Linux)
        add("$home/.var/app/org.libretro.RetroArch/config/retroarch/cores")

        // Windows common paths
        add("$home/AppData/Roaming/RetroArch/cores")
        add("C:/RetroArch/cores")
        add("C:/RetroArch-Win64/cores")
    }

    val coreExtension: String = when {
        System.getProperty("os.name").lowercase().contains("mac") -> ".dylib"
        System.getProperty("os.name").lowercase().contains("win") -> ".dll"
        else -> ".so"
    }

    /**
     * Find a SNES libretro core.
     * @param explicitPath Optional explicit path from config/env
     * @return Absolute path to the core shared library, or null if not found
     */
    fun findCore(explicitPath: String? = null): String? {
        // 1. Check explicit path
        if (!explicitPath.isNullOrBlank()) {
            val f = File(explicitPath)
            if (f.isFile && f.canRead()) return f.absolutePath
            System.err.println("[LibretroDiscovery] Explicit core path not found: $explicitPath")
        }

        // 2. Check environment variable
        val envPath = System.getenv("SMEDIT_LIBRETRO_CORE")?.trim()?.takeIf { it.isNotEmpty() }
        if (envPath != null) {
            val f = File(envPath)
            if (f.isFile && f.canRead()) return f.absolutePath
            System.err.println("[LibretroDiscovery] Env SMEDIT_LIBRETRO_CORE not found: $envPath")
        }

        // 3. Search known directories, preferring cores in PREFERRED_CORES order
        for (coreName in PREFERRED_CORES) {
            for (dir in SEARCH_DIRS) {
                val candidate = File(dir, "${coreName}_libretro$coreExtension")
                if (candidate.isFile && candidate.canRead()) {
                    System.err.println("[LibretroDiscovery] Found core: ${candidate.absolutePath}")
                    return candidate.absolutePath
                }
            }
        }

        // 4. Fallback: any *_libretro.so in search dirs that looks SNES-related
        for (dir in SEARCH_DIRS) {
            val dirFile = File(dir)
            if (!dirFile.isDirectory) continue
            val candidates = dirFile.listFiles { f ->
                f.name.endsWith("_libretro$coreExtension") &&
                    PREFERRED_CORES.any { f.name.startsWith(it) }
            }
            val found = candidates?.firstOrNull()
            if (found != null) return found.absolutePath
        }

        return null
    }

    /** List all discovered SNES cores (for UI display). */
    fun listCores(): List<CoreInfo> {
        val results = mutableListOf<CoreInfo>()
        for (coreName in PREFERRED_CORES) {
            for (dir in SEARCH_DIRS) {
                val candidate = File(dir, "${coreName}_libretro$coreExtension")
                if (candidate.isFile && candidate.canRead()) {
                    results.add(CoreInfo(coreName, candidate.absolutePath))
                }
            }
        }
        return results
    }

    data class CoreInfo(val name: String, val path: String)
}

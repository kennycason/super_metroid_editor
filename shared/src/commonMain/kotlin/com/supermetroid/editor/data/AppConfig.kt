package com.supermetroid.editor.data

import com.supermetroid.editor.util.EditorLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private fun env(name: String): String? {
    return System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
}

private fun defaultNavExportDir(): String {
    val configured = env("SMEDIT_NAV_EXPORT_DIR")
    if (!configured.isNullOrEmpty()) return configured
    val cwd = File(System.getProperty("user.dir")).absoluteFile
    val editorDir = when {
        cwd.name == "super_metroid_editor" -> cwd
        File(cwd, "super_metroid_rl/super_metroid_editor").isDirectory ->
            File(cwd, "super_metroid_rl/super_metroid_editor")
        else -> cwd
    }
    return File(editorDir, "export/sm_nav").absolutePath
}

@Serializable
data class WindowConfig(
    val x: Int = -1,
    val y: Int = -1,
    val width: Int = 1400,
    val height: Int = 900,
)

@Serializable
data class AppSettings(
    val lastRomPath: String? = null,
    val window: WindowConfig = WindowConfig(),
    val lastRoomPerRom: Map<String, String> = emptyMap(),
    val emulatorBackend: String = "libretro",
    val emulatorNavExportDir: String = defaultNavExportDir(),
    val emulatorFollowLiveRoom: Boolean = true,
    val libretroCorePath: String? = null,
    val libretroAudioEnabled: Boolean = true,
    val retroArchPath: String? = null,
    val retroArchCorePath: String? = null,
    val retroArchNwaPort: Int = 55355,
    val theme: String = "DARK",
    val fontSize: String = "MEDIUM",
    val emulatorAudioVolume: Float = 1.0f,
    val emulatorAudioMuted: Boolean = false,
    val emulatorWindowWidth: Float = 512f,
    val emulatorWindowX: Float = 40f,
    val emulatorWindowY: Float = 40f,
)

object AppConfig {
    private val configDir = File(System.getProperty("user.home"), ".smedit")
    private val configFile = File(configDir, "config.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private var cached: AppSettings? = null

    private fun withEnvOverrides(settings: AppSettings): AppSettings {
        return settings.copy(
            emulatorBackend = env("SMEDIT_EMULATOR_BACKEND") ?: settings.emulatorBackend,
            emulatorNavExportDir = env("SMEDIT_NAV_EXPORT_DIR") ?: settings.emulatorNavExportDir,
        )
    }

    fun load(): AppSettings {
        cached?.let { return it }
        if (!configFile.exists()) {
            val defaults = withEnvOverrides(AppSettings())
            cached = defaults
            return defaults
        }
        return try {
            val settings = withEnvOverrides(json.decodeFromString<AppSettings>(configFile.readText()))
            cached = settings
            settings
        } catch (e: Exception) {
            EditorLog.error(e, "[Config] Failed to read config: ${e.message}")
            val defaults = withEnvOverrides(AppSettings())
            cached = defaults
            defaults
        }
    }

    fun save(settings: AppSettings) {
        cached = settings
        try {
            configDir.mkdirs()
            configFile.writeText(json.encodeToString(settings))
        } catch (e: Exception) {
            EditorLog.error(e, "[Config] Failed to write config: ${e.message}")
        }
    }

    fun update(block: AppSettings.() -> AppSettings) {
        save(block(load()))
    }
}

package com.supermetroid.editor.ui

import com.supermetroid.editor.data.PatternLibrary
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import kotlinx.serialization.json.Json
import java.io.File

internal object ProjectFileService {
    private val json = Json {
        // Project files can contain hundreds of thousands of generated tile edits.
        // Keep saves compact; use jq or an editor formatter when human-readable JSON is needed.
        prettyPrint = false
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun loadProject(file: File): SmEditProject =
        json.decodeFromString(SmEditProject.serializer(), file.readText())

    fun saveProject(
        project: SmEditProject,
        projectFilePath: String,
        romParser: RomParser?,
        savePatternLibrary: Boolean,
        onLog: (String) -> Unit,
    ): Boolean {
        if (projectFilePath.isEmpty()) return false
        return try {
            val emptyKeys = project.rooms.entries.filter { !it.value.hasEdits }.map { it.key }
            for (key in emptyKeys) project.rooms.remove(key)

            File(projectFilePath).writeText(json.encodeToString(SmEditProject.serializer(), project))
            onLog("Project saved: $projectFilePath")
            romParser?.let { exportCustomGfxPngs(project, projectFilePath, it, onLog) }
            if (savePatternLibrary) PatternLibrary.saveAll(project.patterns)
            true
        } catch (e: Exception) {
            onLog("Save failed: ${e.message}")
            false
        }
    }

    fun exportToRom(
        project: SmEditProject,
        romParser: RomParser,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
    ): String? =
        RomExporter(project, romParser, onLog, onStatus).export()

    fun exportToIps(
        project: SmEditProject,
        romParser: RomParser,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
    ): String? {
        val original = romParser.getRomData()
        val smcPath = exportToRom(project, romParser, onLog, onStatus) ?: return null
        val patched = File(smcPath).readBytes()
        if (original.size != patched.size) {
            onLog("[IPS] ROM size mismatch: ${original.size} vs ${patched.size}")
            return null
        }
        val ipsData = buildIpsPatch(original, patched)
        val orig = File(project.romPath)
        val version = "v${project.versionMajor}.${project.versionMinor}"
        val build = project.buildName.trim()
        val suffix = if (build.isNotEmpty()) "$build-$version" else version
        val ipsFile = File(orig.parent, "${orig.nameWithoutExtension}-$suffix.ips")
        ipsFile.writeBytes(ipsData)
        val message = "Exported IPS: ${ipsFile.absolutePath} (${ipsData.size} bytes)"
        onLog(message)
        onStatus(message)
        return ipsFile.absolutePath
    }

    private fun exportCustomGfxPngs(
        project: SmEditProject,
        projectFilePath: String,
        romParser: RomParser,
        onLog: (String) -> Unit,
    ) {
        val gfx = project.customGfx
        val hasVar = gfx.varGfx.isNotEmpty()
        val hasCre = gfx.creGfx != null
        if (!hasVar && !hasCre) return

        val projectFile = File(projectFilePath)
        val folder = File(projectFile.parentFile, "${projectFile.nameWithoutExtension}_smedit")
        folder.mkdirs()
        val tileGraphics = TileGraphics(romParser)

        for ((tilesetId, base64) in gfx.varGfx) {
            if (!tileGraphics.loadTileset(tilesetId.toIntOrNull() ?: continue)) continue
            try {
                tileGraphics.applyCustomVarGfx(java.util.Base64.getDecoder().decode(base64))
                val result = tileGraphics.renderTileSheet(0, tileGraphics.getVarTileCount())
                if (result != null) {
                    val (pixels, width, height) = result
                    val out = File(folder, "ure_$tilesetId.png")
                    if (writePng(out.absolutePath, pixels, width, height)) onLog("Exported $out")
                }
            } catch (_: Exception) {
            }
        }

        if (hasCre && gfx.creGfx != null && tileGraphics.loadTileset(0)) {
            try {
                tileGraphics.applyCustomCreGfx(java.util.Base64.getDecoder().decode(gfx.creGfx))
                val result = tileGraphics.renderTileSheet(tileGraphics.getCreOffset(), tileGraphics.getCreTileCount())
                if (result != null) {
                    val (pixels, width, height) = result
                    val out = File(folder, "cre.png")
                    if (writePng(out.absolutePath, pixels, width, height)) onLog("Exported $out")
                }
            } catch (_: Exception) {
            }
        }
    }
}
